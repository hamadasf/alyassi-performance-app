"""Builds the launcher icon and splash image from the company emblem.

usage: python3 scripts/make_icons.py [emblem.png]
Without an emblem (or if it can't be read) a simple gold teardrop is drawn instead,
so the app always builds.
"""
import os
import sys

from PIL import Image, ImageDraw

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
GOLD = (201, 162, 75, 255)
NAVY = (15, 42, 74, 255)


def fallback_emblem(size=1024):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    r = size * 0.32
    cx, cy = size / 2, size * 0.60
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=GOLD)
    d.polygon([(cx - r * 0.93, cy - r * 0.35), (cx, size * 0.06), (cx + r * 0.93, cy - r * 0.35)], fill=GOLD)
    ri = r * 0.45
    d.ellipse([cx - ri, cy - ri, cx + ri, cy + ri], fill=NAVY)
    return img


def load_emblem(path):
    if path and os.path.isfile(path) and os.path.getsize(path) > 0:
        try:
            img = Image.open(path).convert("RGBA")
            box = img.getbbox()
            if box:
                img = img.crop(box)
            print("using emblem", path, img.size)
            return img
        except Exception as e:  # noqa: BLE001
            print("emblem unreadable, using fallback:", e)
    print("using fallback emblem")
    return fallback_emblem()


def place(emblem, canvas_px, content_px, bg=None):
    canvas = Image.new("RGBA", (canvas_px, canvas_px), bg or (0, 0, 0, 0))
    e = emblem.copy()
    e.thumbnail((content_px, content_px), Image.LANCZOS)
    canvas.alpha_composite(e, ((canvas_px - e.width) // 2, (canvas_px - e.height) // 2))
    return canvas


def save(img, folder, name):
    d = os.path.join(RES, folder)
    os.makedirs(d, exist_ok=True)
    img.save(os.path.join(d, name), optimize=True)


def main():
    emblem = load_emblem(sys.argv[1] if len(sys.argv) > 1 else None)
    for dens, k in DENSITIES.items():
        # Adaptive icon foreground: 108dp canvas, logo inside the 66dp safe zone.
        save(place(emblem, round(108 * k), round(60 * k)), f"mipmap-{dens}", "ic_launcher_foreground.png")
        # Legacy icon (Android 7): 48dp, white rounded square with the logo.
        size = round(48 * k)
        legacy = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        ImageDraw.Draw(legacy).rounded_rectangle([0, 0, size - 1, size - 1], radius=size // 5, fill=(255, 255, 255, 255))
        legacy = Image.alpha_composite(legacy, place(emblem, size, round(36 * k)))
        save(legacy, f"mipmap-{dens}", "ic_launcher.png")
        # Splash icon: 288dp canvas, visible circle is 192dp.
        save(place(emblem, round(288 * k), round(150 * k)), f"drawable-{dens}", "splash_icon.png")


if __name__ == "__main__":
    main()
