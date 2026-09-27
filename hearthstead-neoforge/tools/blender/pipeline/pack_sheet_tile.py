"""Assemble pack_sheet.py tiles into one labelled contact sheet per profession (plain Python + Pillow).

    python pack_sheet_tile.py OUT_DIR [fixed|legacy|both]

both: legacy tiles on the left half, fixed on the right half ("before | after").
"""
import json
import os
import sys

from PIL import Image, ImageDraw, ImageFont

VIEWS = ["side", "back", "back34"]


def font(size):
    for f in ("arial.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            pass
    return ImageFont.load_default()


def sheet(out, prof, geos):
    idx = {g: json.load(open(os.path.join(out, "_tiles", g, "index.json"))) for g in geos}
    tiles = {g: idx[g][prof] for g in geos}
    rows = sorted({t[0] for t in tiles[geos[0]]})
    labels = {t[0]: t[2] for t in tiles[geos[0]]}
    tw, th = Image.open(tiles[geos[0]][0][3]).size
    lw, top = 150, 34
    W = lw + len(geos) * len(VIEWS) * tw + (len(geos) - 1) * 12
    H = top + len(rows) * th
    img = Image.new("RGB", (W, H), (32, 30, 28))
    d = ImageDraw.Draw(img)
    d.text((8, 8), prof, font=font(18), fill=(240, 220, 170))
    for gi, g in enumerate(geos):
        x0 = lw + gi * (len(VIEWS) * tw + 12)
        head = {"legacy": "BEFORE", "fixed": "AFTER"}[g] if len(geos) > 1 else ""
        for vi, v in enumerate(VIEWS):
            d.text((x0 + vi * tw + 6, 10), (head + " " + v).strip(), font=font(14), fill=(220, 220, 220))
        for r, view, label, path in tiles[g]:
            img.paste(Image.open(path).convert("RGB"), (x0 + VIEWS.index(view) * tw, top + rows.index(r) * th))
    for r in rows:
        d.text((8, top + rows.index(r) * th + th // 2 - 8), labels[r], font=font(13), fill=(230, 230, 230))
    name = f"{prof.lower()}_pack_sheet" + ("_before_after" if len(geos) > 1 else "") + ".png"
    img.save(os.path.join(out, name))
    return name


def main():
    out = sys.argv[1]
    mode = sys.argv[2] if len(sys.argv) > 2 else "fixed"
    geos = ["legacy", "fixed"] if mode == "both" else [mode]
    profs = json.load(open(os.path.join(out, "_tiles", geos[0], "index.json"))).keys()
    for p in profs:
        print(sheet(out, p, geos))


if __name__ == "__main__":
    main()
