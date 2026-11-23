#!/usr/bin/env python3
"""Builds the README pictures (docs/screenshots) from ReadmeShotsTest's rd_*.png shots:
phone pairs in frames, the hero (three phones on a grid), the car screens.
The style grids are tools/readme_themes.py.

usage: python3 tools/readme_shots.py <folder with rd_*.png> <docs/screenshots>
"""
import os, sys
from PIL import Image, ImageDraw, ImageFilter

PAIRS = {
    "find": ("search", "routes"),
    "drive": ("drive", "quiet"),
    "convoy": ("convoy", "lobby"),
    "race": ("invitecard", "race"),
    "friends": ("you", "friends"),
    "xp": ("arrival", "progress"),
    "garage": ("garage", "styles"),
    "places": ("place", "fuel"),
    "map": ("countries", "settings"),
}
CARS = ("drive", "convoy", "place")


def phone(shot, w):
    """The shot in a dark rounded phone body, w wide; returns RGBA."""
    s = Image.open(shot).convert("RGB")
    bez = max(8, w // 42)
    sw = w - 2 * bez; sh = round(sw * s.height / s.width)
    h = sh + 2 * bez
    body = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(body).rounded_rectangle((0, 0, w - 1, h - 1), w // 9, fill=(16, 16, 20, 255))
    scr = s.resize((sw, sh), Image.LANCZOS)
    m = Image.new("L", (sw, sh), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, sw - 1, sh - 1), w // 11, fill=255)
    body.paste(scr, (bez, bez), m)
    return body


def shadowed(canvas, item, x, y, blur=18, alpha=110, dy=10):
    sh = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    a = item.split()[3].point(lambda v: alpha if v else 0)
    blk = Image.new("RGBA", item.size, (0, 0, 0, 255)); blk.putalpha(a)
    sh.paste(blk, (x, y + dy), blk)
    canvas.alpha_composite(sh.filter(ImageFilter.GaussianBlur(blur)))
    canvas.alpha_composite(item, (x, y))


def pair(src, out, name, a, b):
    W, H = 1282, 1265
    c = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    for k, s in enumerate((a, b)):
        p = phone(os.path.join(src, f"rd_{s}.png"), 545)
        shadowed(c, p, 58 + k * 622, (H - p.height) // 2 - 8)
    c.save(os.path.join(out, f"{name}.webp"), quality=86, method=6)


def hero(src, out):
    W, H = 1600, 1260
    c = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(c)
    for y in range(H):  # dark navy to purple
        t = y / H
        d.line((0, y, W, y), fill=(int(22 + 40 * t), int(20 + 14 * t), int(42 + 70 * t), 255))
    grid = Image.new("RGBA", (W, H), (0, 0, 0, 0)); g = ImageDraw.Draw(grid)
    for x in range(0, W, 80): g.line((x, 0, x, H), fill=(255, 255, 255, 18))
    for y in range(0, H, 80): g.line((0, y, W, y), fill=(255, 255, 255, 18))
    c.alpha_composite(grid)
    left, mid, right = (phone(os.path.join(src, f"rd_{s}.png"), w) for s, w in (("lobby", 420), ("drive", 482), ("you", 420)))
    shadowed(c, left, 120, 240, 30, 150, 16)
    shadowed(c, right, W - 120 - 420, 240, 30, 150, 16)
    shadowed(c, mid, (W - 482) // 2, 122, 34, 170, 18)
    c.convert("RGB").save(os.path.join(out, "hero.jpg"), quality=88)


def car(src, out, name):
    W, H = 1680, 680
    c = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    s = Image.open(os.path.join(src, f"rd_car_{name}.png")).convert("RGB").resize((1600, 600), Image.LANCZOS)
    m = Image.new("L", s.size, 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, 1599, 599), 34, fill=255)
    item = Image.new("RGBA", s.size, (0, 0, 0, 0)); item.paste(s, (0, 0), m)
    shadowed(c, item, 40, 40, 16, 100, 8)
    c.save(os.path.join(out, f"car-{name}.webp"), quality=86, method=6)


if __name__ == "__main__":
    src, out = sys.argv[1], sys.argv[2]
    for n, (a, b) in PAIRS.items(): pair(src, out, n, a, b)
    hero(src, out)
    for n in CARS: car(src, out, n)
