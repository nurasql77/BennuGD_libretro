#!/usr/bin/env python3
"""Иконка приложения и фон меню.

  branding/icon.png               -> app/src/main/res/mipmap-*/ic_launcher.png
  branding/background.jpg|.png    -> app/src/main/res/drawable-nodpi/menu_bg.jpg

Если branding/icon.png нет, остаётся иконка по умолчанию.
Запуск с флагом --default пересоздаёт иконку по умолчанию.
Нужен Pillow: pip install pillow
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def font(size, bold=True):
    for path in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ):
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def default_icon(size=512):
    """Оригинальная иконка: тёмный градиент, красная полоса, надпись SoR."""
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        t = y / (size - 1)
        r = int(18 + 20 * t)
        g = int(24 + 28 * t)
        b = int(52 + 60 * t)
        for x in range(size):
            px[x, y] = (r, g, b)

    d = ImageDraw.Draw(img)
    s = size / 512
    # красная диагональная полоса
    d.polygon(
        [(0, 330 * s), (size, 190 * s), (size, 290 * s), (0, 430 * s)],
        fill=(214, 40, 40),
    )
    # жёлтая тонкая линия
    d.polygon(
        [(0, 318 * s), (size, 178 * s), (size, 190 * s), (0, 330 * s)],
        fill=(255, 196, 0),
    )

    f_big = font(int(210 * s))
    text = "SoR"
    w = d.textlength(text, font=f_big)
    d.text(((size - w) / 2 + 6 * s, 60 * s + 6 * s), text, font=f_big, fill=(0, 0, 0))
    d.text(((size - w) / 2, 60 * s), text, font=f_big, fill=(255, 255, 255))

    f_small = font(int(62 * s))
    text2 = "REMAKE"
    w2 = d.textlength(text2, font=f_small)
    d.text(((size - w2) / 2, 400 * s), text2, font=f_small, fill=(255, 255, 255))
    return img


def square(img):
    w, h = img.size
    m = min(w, h)
    left, top = (w - m) // 2, (h - m) // 2
    return img.crop((left, top, left + m, top + m))


def save_mipmaps(img):
    img = square(img.convert("RGBA"))
    for name, size in SIZES.items():
        folder = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(folder, exist_ok=True)
        img.resize((size, size), Image.LANCZOS).save(os.path.join(folder, "ic_launcher.png"))
    print("launcher icons written")


def save_background(path):
    img = Image.open(path).convert("RGB")
    if img.width > 1600:
        img = img.resize((1600, int(img.height * 1600 / img.width)), Image.LANCZOS)
    folder = os.path.join(RES, "drawable-nodpi")
    os.makedirs(folder, exist_ok=True)
    img.save(os.path.join(folder, "menu_bg.jpg"), quality=85)
    print("menu background written")


def main():
    if "--default" in sys.argv:
        save_mipmaps(default_icon())
        return 0

    icon = os.path.join(ROOT, "branding", "icon.png")
    if os.path.exists(icon):
        save_mipmaps(Image.open(icon))
    else:
        # Нет своей иконки: если иконок по умолчанию нет в репозитории, создаём их сами
        missing = [
            n for n in SIZES
            if not os.path.exists(os.path.join(RES, f"mipmap-{n}", "ic_launcher.png"))
        ]
        if missing:
            print("branding/icon.png not found and icons are missing: generating the default icon")
            save_mipmaps(default_icon())
        else:
            print("branding/icon.png not found: committed default icon is used")

    for name in ("background.jpg", "background.jpeg", "background.png"):
        bg = os.path.join(ROOT, "branding", name)
        if os.path.exists(bg):
            save_background(bg)
            break
    return 0


if __name__ == "__main__":
    sys.exit(main())
