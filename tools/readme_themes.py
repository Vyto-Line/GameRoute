#!/usr/bin/env python3
"""Builds docs/screenshots/themes-light.webp and themes-dark.webp from ReadmeShotsTest's
rd_theme_<style>_<light|dark>.png shots: one tile per style, three across, the same street corner.

usage: python3 tools/readme_themes.py <folder with rd_theme_*.png> <docs/screenshots>
"""
import os, sys
from PIL import Image, ImageDraw, ImageFont

STYLES = [  # Theme order
    ("open_world", "Free Roam"), ("neon_noir", "Might City"), ("neon_racer", "Midnight Run"),
    ("blocky", "Blockworld"), ("parchment_quest", "Realm Map"), ("lookout", "Fire Tower"),
    ("retro", "8-Bit"), ("trailblazer", "Catch 'Em Trail"), ("violet_rain", "Violet Rain"),
    ("rally", "Rally Roadbook"), ("blueprint", "Blueprint"), ("nautical", "Nautical Chart"),
    ("arctic", "Arctic"), ("tokyo_night", "Tokyo Night"), ("watercolour", "Watercolour"),
    ("high_contrast", "High Contrast"),
]
W, COLS, M, GAP = 1078, 3, 22, 22
TW = (W - 2 * M - (COLS - 1) * GAP) // COLS  # 330
MH, LH, R = 425, 52, 22                        # map, label band, corner radius
CROP = (80, 345, 1170, 345 + int((1170 - 80) * MH / TW))  # the corner around Omonoia, under the chips
FONT = "/usr/share/fonts/opentype/inter/Inter-Bold.otf"


def build(src, out, mode):
    rows = (len(STYLES) + COLS - 1) // COLS
    H = M * 2 + rows * (MH + LH) + (rows - 1) * GAP
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    font = ImageFont.truetype(FONT, 27)
    band, ink = ((255, 255, 255), (24, 25, 32)) if mode == "light" else ((24, 25, 32), (236, 237, 242))
    for k, (key, name) in enumerate(STYLES):
        x = M + (k % COLS) * (TW + GAP); y = M + (k // COLS) * (MH + LH + GAP)
        tile = Image.new("RGB", (TW, MH + LH), band)
        shot = Image.open(os.path.join(src, f"rd_theme_{key}_{mode}.png")).convert("RGB")
        tile.paste(shot.crop(CROP).resize((TW, MH), Image.LANCZOS), (0, 0))
        ImageDraw.Draw(tile).text((16, MH + LH / 2), name, font=font, fill=ink, anchor="lm")
        mask = Image.new("L", tile.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, TW - 1, MH + LH - 1), R, fill=255)
        img.paste(tile, (x, y), mask)
    img.save(os.path.join(out, f"themes-{mode}.webp"), quality=86, method=6)


if __name__ == "__main__":
    for mode in ("light", "dark"):
        build(sys.argv[1], sys.argv[2], mode)
