#!/usr/bin/env python3
"""Preview sheets for gen_gear_armor: flat UV sheets and front/back
composites of a guard in every gear material, wearing the realm's tabard.
Orthographic stills only (no game client); the in-game look is the same
texels on the bending settler mesh.

    python tools/gen_gear_armor.py --preview <out_dir>
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from PIL import Image, ImageDraw
import gen_settler
import preview_settler as pv

SCALE = 8
DYES = {  # vanilla DyeColor texture diffuse colours
    "red": (0xB0, 0x2E, 0x26), "yellow": (0xFE, 0xD8, 0x3D),
    "blue": (0x3C, 0x44, 0xAA), "white": (0xF9, 0xFF, 0xFE),
    "green": (0x5E, 0x7C, 0x16), "black": (0x1D, 0x1D, 0x21),
}
ORDER = ("feet", "legs", "chest", "TABARD", "head")


def tint(img, rgb):
    out = img.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a:
                px[x, y] = (r * rgb[0] // 255, g * rgb[1] // 255, b * rgb[2] // 255, a)
    return out


def dress(base, sheets, material, field, trim, heraldry):
    img = base.copy()
    for step in ORDER:
        if step == "TABARD":
            if heraldry:
                img.alpha_composite(tint(field, DYES[heraldry[0]]))
                img.alpha_composite(tint(trim, DYES[heraldry[1]]))
            continue
        if material:
            img.alpha_composite(sheets[(material, step)])
    return img


def views(skin):
    front = pv.front_view(skin, "helm")
    back = pv.back_view(skin, "helm")
    w, h = front.size
    c = Image.new("RGBA", (w * 2 + 2, h), (0, 0, 0, 0))
    c.alpha_composite(front, (0, 0))
    c.alpha_composite(back, (w + 2, 0))
    return c.resize((c.width * SCALE, c.height * SCALE), Image.NEAREST)


def render(sheets, field, trim, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    base = gen_settler.build("guard")
    mats = [None, "leather", "chain", "iron", "gold", "diamond", "netherite"]
    labels = ["recruit (no armour)", "leather T0", "chain T1", "iron T2",
              "gold T0", "diamond T3", "netherite T4"]
    rows = [("red", "yellow"), ("blue", "white"), None]
    cells = []
    for heraldry in rows:
        row = [views(dress(base, sheets, m, field, trim, heraldry)) for m in mats]
        cells.append(row)
    cw, ch = cells[0][0].size
    pad, top = 16, 22
    sheet = Image.new("RGBA", (len(mats) * (cw + pad) + pad,
                               len(rows) * (ch + top + pad) + pad), (38, 36, 34, 255))
    d = ImageDraw.Draw(sheet)
    for r, row in enumerate(cells):
        for c, img in enumerate(row):
            x = pad + c * (cw + pad)
            y = pad + r * (ch + top + pad)
            d.text((x, y), labels[c] + ("" if rows[r] is None
                                        else "  %s/%s" % rows[r]), fill=(230, 222, 200, 255))
            sheet.alpha_composite(img, (x, y + top))
    sheet.save(os.path.join(out_dir, "gear_composite_front_back.png"))
    print("  wrote", os.path.join(out_dir, "gear_composite_front_back.png"))

    # flat UV sheets (upscaled) with a faint grid, one row per material
    S = 3
    names = [(m, s) for m in gen_gear_materials(sheets) for s in ("head", "chest", "legs", "feet")]
    flat = Image.new("RGBA", (4 * (128 * S + 8) + 8, 7 * (64 * S + 8) + 8), (60, 58, 54, 255))
    for i, key in enumerate(names):
        img = sheets[key].resize((128 * S, 64 * S), Image.NEAREST)
        x = 8 + (i % 4) * (128 * S + 8)
        y = 8 + (i // 4) * (64 * S + 8)
        flat.alpha_composite(img, (x, y))
    for j, img in enumerate((field, trim)):
        flat.alpha_composite(img.resize((128 * S, 64 * S), Image.NEAREST),
                             (8 + j * (128 * S + 8), 8 + 6 * (64 * S + 8)))
    flat.save(os.path.join(out_dir, "gear_uv_sheets.png"))
    print("  wrote", os.path.join(out_dir, "gear_uv_sheets.png"))


def gen_gear_materials(sheets):
    seen = []
    for m, _ in sheets:
        if m not in seen:
            seen.append(m)
    return seen
