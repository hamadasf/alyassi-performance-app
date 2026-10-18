package com.omaralyassi.performance

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var offline: View

    private var splashDone = false
    private var mainFrameFailed = false
    private var lastBackPress = 0L

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null

    private var geoOrigin: String? = null
    private var geoCallback: GeolocationPermissions.Callback? = null

    /** Hosts that stay inside the app. Everything else opens in the phone's browser / apps. */
    private fun isInternal(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return host == "script.google.com" ||
            host.endsWith(".googleusercontent.com") ||
            host == "accounts.google.com" ||
            host.endsWith(".gstatic.com") ||
            host == "www.google.com"
    }

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        var uris: Array<Uri>? = null
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val clip = data?.clipData
            uris = when {
                clip != null -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                data?.data != null -> arrayOf(data.data!!)
                cameraUri != null && cameraFileHasContent() -> arrayOf(cameraUri!!)
                else -> null
            }
        }
        cameraUri = null
        cb.onReceiveValue(uris)
    }

    private val askLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = granted.values.any { it }
        geoCallback?.invoke(geoOrigin, ok, false)
        geoCallback = null
        geoOrigin = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { !splashDone }
        window.decorView.postDelayed({ splashDone = true }, 6000)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        web = findViewById(R.id.web)
        progress = findViewById(R.id.progress)
        offline = findViewById(R.id.offline)
        findViewById<Button>(R.id.retry).setOnClickListener { reload() }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            setGeolocationEnabled(true)
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString AlYassiPerformance/${BuildConfigVersion.name(this@MainActivity)}"
        }

        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, contentDisposition, mimeType)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    offline.visibility == View.VISIBLE -> finish()
                    web.canGoBack() -> web.goBack()
                    System.currentTimeMillis() - lastBackPress < 2000 -> finish()
                    else -> {
                        lastBackPress = System.currentTimeMillis()
                        Toast.makeText(this@MainActivity, R.string.press_again, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            loadHome()
        }
    }

    private fun loadHome() {
        if (!isOnline()) {
            showOffline()
            return
        }
        web.loadUrl(getString(R.string.app_url))
    }

    private fun reload() {
        if (!isOnline()) {
            Toast.makeText(this, R.string.offline_title, Toast.LENGTH_SHORT).show()
            return
        }
        offline.visibility = View.GONE
        mainFrameFailed = false
        val current = web.url
        if (current.isNullOrEmpty() || current.startsWith("about:") || current.startsWith("data:")) {
            web.loadUrl(getString(R.string.app_url))
        } else {
            web.reload()
        }
    }

    private fun showOffline() {
        offline.visibility = View.VISIBLE
        progress.visibility = View.GONE
        splashDone = true
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Opens a link outside the app (browser, WhatsApp, phone, mail, maps…). */
    private fun openExternal(uri: Uri) {
        try {
            if (uri.scheme.equals("intent", true)) {
                val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.component = null
                intent.selector = null
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    val fallback = intent.getStringExtra("browser_fallback_url")
                    if (fallback != null) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)))
                    else throw e
                }
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    /** Decides where a link goes. Returns true when the app handled it itself. */
    private fun route(uri: Uri, mainFrame: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https") {
            if (!mainFrame) return false          // pages inside the Apps Script frame
            if (isInternal(uri)) return false
            openExternal(uri)
            return true
        }
        if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript") return false
        openExternal(uri)                          // tel:, mailto:, whatsapp:, intent:, geo: …
        return true
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            route(request.url, request.isForMainFrame)

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            mainFrameFailed = false
            progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            progress.visibility = View.GONE
            if (!mainFrameFailed) offline.visibility = View.GONE
            splashDone = true
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                mainFrameFailed = true
                showOffline()
            }
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            if (newProgress >= 70) splashDone = true
        }

        /** window.open / target=_blank: catch the address, then route it. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val temp = WebView(this@MainActivity)
            temp.webViewClient = object : WebViewClient() {
                private var done = false
                private fun handle(uri: Uri) {
                    if (done) return
                    done = true
                    if (uri.scheme == "http" || uri.scheme == "https") {
                        if (isInternal(uri)) web.loadUrl(uri.toString()) else openExternal(uri)
                    } else {
                        route(uri, true)
                    }
                    temp.destroy()
                }
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    handle(r.url); return true
                }
                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    if (url != null && url != "about:blank") handle(Uri.parse(url))
                }
            }
            temp.settings.javaScriptEnabled = true
            temp.setDownloadListener { url, ua, cd, mime, _ -> download(url, ua, cd, mime) }
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = temp
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback

            val accepts = params.acceptTypes.filter { it.isNotBlank() }
            val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (accepts.size == 1 && accepts[0].contains('/')) accepts[0] else "*/*"
                val mimes = accepts.filter { it.contains('/') }
                if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }

            val wantsImage = accepts.isEmpty() || accepts.any { it.startsWith("image") || it == "*/*" }
            val extras = mutableListOf<Intent>()
            if (wantsImage) {
                try {
                    val dir = File(cacheDir, "camera").apply { mkdirs() }
                    val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                    cameraUri = uri
                    extras += Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                    cameraUri = null
                }
            }

            val chooser = Intent.createChooser(pick, getString(R.string.choose_file))
            if (extras.isNotEmpty()) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
            return try {
                pickFiles.launch(chooser)
                true
            } catch (e: Exception) {
                fileCallback = null
                cameraUri = null
                false
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
                callback.invoke(origin, true, false)
            } else {
                geoOrigin = origin
                geoCallback = callback
                askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }

    private fun cameraFileHasContent(): Boolean = try {
        contentResolver.openFileDescriptor(cameraUri!!, "r")?.use { it.statSize > 0 } ?: false
    } catch (_: Exception) {
        false
    }

    private fun download(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            if (url.startsWith("data:")) {
                saveDataUrl(url)
                return
            }
            if (url.startsWith("blob:")) {
                Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
                return
            }
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                userAgent?.let { addRequestHeader("User-Agent", it) }
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            getSystemService(DownloadManager::class.java).enqueue(req)
            Toast.makeText(this, R.string.downloading, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Fall back to the browser, which can always download it.
            openExternal(Uri.parse(url))
        }
    }

    /** Saves a data: URL (for example an exported PDF or CSV) into Downloads. */
    private fun saveDataUrl(url: String) {
        try {
            val comma = url.indexOf(',')
            val meta = url.substring(5, comma)
            val mime = meta.substringBefore(';').ifBlank { "application/octet-stream" }
            val payload = url.substring(comma + 1)
            val bytes = if (meta.contains(";base64")) Base64.decode(payload, Base64.DEFAULT)
            else Uri.decode(payload).toByteArray()
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            val name = "AlYassi_${System.currentTimeMillis()}.$ext"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                FileOutputStream(File(dir, name)).use { it.write(bytes) }
            }
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}

private object BuildConfigVersion {
    fun name(activity: AppCompatActivity): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "1"
    } catch (_: Exception) {
        "1"
    }
}
