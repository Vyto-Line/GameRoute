"""
Builds the pictures on the GitHub page from the app's own screenshots (ReadmeShotsTest):
python3 tools/readme_images.py <folder with rd_*.png> [icon.png]  →  docs/screenshots/*.webp|jpg
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SRC = sys.argv[1]
ICON = sys.argv[2] if len(sys.argv) > 2 else None
OUT = os.path.join(os.path.dirname(__file__), "..", "docs", "screenshots")
os.makedirs(OUT, exist_ok=True)
FONT = "/usr/share/fonts/opentype/inter/Inter-SemiBold.otf"
FONT_B = "/usr/share/fonts/opentype/inter/Inter-Bold.otf"

TITLES = {
    "open_world": "Free Roam", "neon_noir": "Might City", "neon_racer": "Midnight Run", "blocky": "Blockworld",
    "parchment_quest": "Realm Map", "lookout": "Fire Tower", "retro": "8-Bit", "trailblazer": "Catch 'Em Trail",
}


def shot(name):
    return Image.open(os.path.join(SRC, f"rd_{name}.png")).convert("RGB")


def rounded(img, r):
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, img.width - 1, img.height - 1), r, fill=255)
    out = img.convert("RGBA")
    out.putalpha(m)
    return out


def phone(img, width):
    """A phone: the screen in a thin dark bezel, rounded, with a soft shadow (transparent around)."""
    s = width / img.width
    scr = img.resize((width, int(img.height * s)), Image.LANCZOS)
    bez = max(6, width // 40)
    body = Image.new("RGBA", (scr.width + 2 * bez, scr.height + 2 * bez), (0, 0, 0, 0))
    ImageDraw.Draw(body).rounded_rectangle((0, 0, body.width - 1, body.height - 1), int(width * 0.11), fill=(18, 18, 22, 255))
    body.alpha_composite(rounded(scr, int(width * 0.09)), (bez, bez))
    pad = width // 9
    canvas = Image.new("RGBA", (body.width + 2 * pad, body.height + 2 * pad), (0, 0, 0, 0))
    sh = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle((pad, pad + pad // 3, pad + body.width, pad + body.height + pad // 3), int(width * 0.11), fill=(0, 0, 0, 120))
    sh = sh.filter(ImageFilter.GaussianBlur(pad // 2.5))
    canvas.alpha_composite(sh)
    canvas.alpha_composite(body, (pad, pad))
    return canvas


def save_phone(name, out, width=560):
    phone(shot(name), width).save(os.path.join(OUT, out), "WEBP", quality=84, method=6)


def car(name, out, width=1600):
    img = shot(f"car_{name}")
    img = img.resize((width, int(img.height * width / img.width)), Image.LANCZOS)
    r = rounded(img, width // 60)
    pad = width // 40
    canvas = Image.new("RGBA", (r.width + 2 * pad, r.height + 2 * pad), (0, 0, 0, 0))
    sh = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle((pad, pad + 8, pad + r.width, pad + r.height + 8), width // 60, fill=(0, 0, 0, 110))
    canvas.alpha_composite(sh.filter(ImageFilter.GaussianBlur(pad // 2.5)))
    canvas.alpha_composite(r, (pad, pad))
    canvas.save(os.path.join(OUT, out), "WEBP", quality=84, method=6)


def hero():
    W, H = 2400, 1240
    bg = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(bg)
    for y in range(H):  # deep navy to violet
        t = y / H
        d.line([(0, y), (W, y)], fill=(int(14 + 30 * t), int(16 + 6 * t), int(38 + 50 * t)))
    for x in range(0, W, 80):  # a faint game grid
        d.line([(x, 0), (x, H)], fill=(40, 44, 80))
    for y in range(0, H, 80):
        d.line([(0, y), (W, y)], fill=(40, 44, 80))
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((W // 2 - 700, 160, W // 2 + 700, H + 300), fill=(140, 90, 255, 70))
    bg = bg.convert("RGBA")
    bg.alpha_composite(glow.filter(ImageFilter.GaussianBlur(160)))
    lineup = [("home", 430, -560), ("drive", 470, -300), ("convoy", 520, 0), ("routes", 470, 300), ("arrival", 430, 560)]
    for name, w, dx in sorted(lineup, key=lambda t: t[1]):  # the big one last, on top
        p = phone(shot(name), w)
        x = W // 2 + int(dx * 1.45) - p.width // 2
        y = H // 2 - p.height // 2 + (0 if w == 520 else 40 if w == 470 else 80)
        bg.alpha_composite(p, (x, y))
    bg.convert("RGB").save(os.path.join(OUT, "hero.jpg"), quality=86, optimize=True)


def pair(a, b, out, width=520):
    """Two phones side by side: big enough to read on a phone, where the page is one column wide."""
    pa, pb = phone(shot(a), width), phone(shot(b), width)
    gap = -width // 14  # the shadows overlap a little
    canvas = Image.new("RGBA", (pa.width + pb.width + gap, max(pa.height, pb.height)), (0, 0, 0, 0))
    canvas.alpha_composite(pa, (0, 0))
    canvas.alpha_composite(pb, (pa.width + gap, 0))
    canvas.save(os.path.join(OUT, out), "WEBP", quality=84, method=6)


def hero3():
    """The top picture: three phones, big enough to make out on a phone screen."""
    W, H = 1600, 1260
    bg = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(bg)
    for y in range(H):  # deep navy to violet
        t = y / H
        d.line([(0, y), (W, y)], fill=(int(14 + 30 * t), int(16 + 6 * t), int(38 + 50 * t)))
    for x in range(0, W, 80):
        d.line([(x, 0), (x, H)], fill=(40, 44, 80))
    for y in range(0, H, 80):
        d.line([(0, y), (W, y)], fill=(40, 44, 80))
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((W // 2 - 560, 120, W // 2 + 560, H + 260), fill=(140, 90, 255, 75))
    bg = bg.convert("RGBA")
    bg.alpha_composite(glow.filter(ImageFilter.GaussianBlur(140)))
    for name, w, dx, dy in [("drive", 400, -470, 50), ("friends", 400, 470, 50), ("convoy", 460, 0, 0)]:
        p = phone(shot(name), w)
        bg.alpha_composite(p, (W // 2 + dx - p.width // 2, H // 2 - p.height // 2 + dy))
    bg.convert("RGB").save(os.path.join(OUT, "hero.jpg"), quality=86, optimize=True)


def themes(dark):
    tw, th = 330, 470
    gap, lab = 22, 54
    names = list(TITLES)
    W = 4 * tw + 5 * gap
    H = 2 * (th + lab) + 3 * gap
    bg = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    f = ImageFont.truetype(FONT_B, 26)
    for i, n in enumerate(names):
        img = shot(f"theme_{n}_{'dark' if dark else 'light'}")
        # the middle of the screen: map, the player and a few streets (no bars)
        crop = img.crop((0, int(img.height * 0.17), img.width, int(img.height * 0.17) + int(img.width * th / tw)))
        crop = crop.resize((tw, th), Image.LANCZOS)
        x = gap + (i % 4) * (tw + gap)
        y = gap + (i // 4) * (th + lab + gap)
        card = Image.new("RGBA", (tw, th + lab), (24, 26, 34, 255) if dark else (255, 255, 255, 255))
        card.paste(crop, (0, 0))
        dd = ImageDraw.Draw(card)
        dd.text((16, th + 12), TITLES[n], font=f, fill=(240, 240, 245) if dark else (30, 32, 40))
        card = rounded(card.convert("RGB"), 22)
        sh = Image.new("RGBA", bg.size, (0, 0, 0, 0))
        ImageDraw.Draw(sh).rounded_rectangle((x, y + 6, x + tw, y + th + lab + 6), 22, fill=(0, 0, 0, 90))
        bg.alpha_composite(sh.filter(ImageFilter.GaussianBlur(8)))
        bg.alpha_composite(card, (x, y))
    bg.save(os.path.join(OUT, f"themes-{'dark' if dark else 'light'}.webp"), "WEBP", quality=84, method=6)


if __name__ == "__main__":
    for f in os.listdir(OUT):  # the old pictures go (the icon stays unless a new one is given)
        if f != "icon.png" or ICON:
            os.remove(os.path.join(OUT, f))
    hero3()
    # two phones per picture: one column on a phone, still readable
    for a, b, out in [("search", "routes", "find.webp"), ("drive", "arrival", "drive.webp"), ("place", "fuel", "places.webp"),
                      ("convoy", "convoy_list", "convoy.webp"), ("race", "convoy_qr", "race.webp"),
                      ("friends", "together", "friends.webp"), ("countries", "styles", "map.webp")]:
        pair(a, b, out)
    for name in ["drive", "convoy", "place"]:
        car(name, f"car-{name}.webp")
    themes(False)
    themes(True)
    if ICON:
        ic = Image.open(ICON).convert("RGBA").crop((20, 20, 452, 452)).resize((240, 240), Image.LANCZOS)
        ic.save(os.path.join(OUT, "icon.png"), optimize=True)
    print(sorted(os.listdir(OUT)))
