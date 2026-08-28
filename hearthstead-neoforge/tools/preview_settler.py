#!/usr/bin/env python3
"""Orthographic turnaround previews: composite a curated set of modular
appearance combinations onto the model geometry (front/side/back) so the
art is reviewed as it will render. Each combo varies exactly one axis away
from a baseline, giving full single-axis coverage without the full cross
product (SKIN x HAIR_STYLE x HAIR_COLOR x FACE x CLOTHING x PROFESSION)."""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from PIL import Image
from gen_settler import (UV, SKIN_KEYS, HAIR_STYLES, HAIR_COLOR_KEYS,
                         FACE_VARIANTS, CLOTHING_PALETTES, PROFESSION_OUTFITS,
                         build_base, build_hair, build_face, build_clothing,
                         build_outfit, compose)
from texlib import box_faces

SCALE = 6
# Canvas in model px: x -10..10, y -10..25  (y down, 0 = neck)
CW, CH = 22, 36
OX, OY = 11, 10


def sample_combos():
    """>=12 combos, each one axis away from a fixed baseline: full coverage
    of every skin/hair-style/hair-color/face/clothing/profession variant."""
    profs = list(PROFESSION_OUTFITS)
    base = dict(skin=0, hair_style=0, hair_color=0, face=0, clothing=0, prof=profs[0])
    combos = [dict(base)]
    for i in range(1, len(SKIN_KEYS)):
        combos.append(dict(base, skin=i))
    for i in range(1, len(HAIR_STYLES)):
        combos.append(dict(base, hair_style=i))
    for i in range(1, len(HAIR_COLOR_KEYS)):
        combos.append(dict(base, hair_color=i))
    for i in range(1, len(FACE_VARIANTS)):
        combos.append(dict(base, face=i))
    for i in range(1, len(CLOTHING_PALETTES)):
        combos.append(dict(base, clothing=i))
    for prof in profs[1:]:
        combos.append(dict(base, prof=prof))
    return combos


def build_combo(combo):
    return compose(
        build_base(combo["skin"]),
        build_hair(combo["hair_style"], combo["hair_color"]),
        build_face(combo["face"]),
        build_clothing(combo["clothing"]),
        build_outfit(combo["prof"]),
    )


def face_img(skin, part, face):
    u, v, w, h, d = UV[part]
    rect = box_faces(u, v, w, h, d)[face]
    return skin.crop((rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3]))


def paste(canvas, img, x, y):
    canvas.alpha_composite(img, (int((x + OX)), int((y + OY))))


def front_view(skin, headgear):
    c = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    paste(c, face_img(skin, "backpack", "front"), -3, 3)
    paste(c, face_img(skin, "torso", "front"), -5, 0)
    paste(c, face_img(skin, "belt", "front"), -5, 7)
    paste(c, face_img(skin, "cloak", "front"), -5.5, 0)
    paste(c, face_img(skin, "right_leg", "front"), -4.6, 12)
    paste(c, face_img(skin, "left_leg", "front"), 0.6, 12)
    paste(c, face_img(skin, "right_arm", "front"), -8, 0)
    paste(c, face_img(skin, "left_arm", "front"), 4, 0)
    paste(c, face_img(skin, "head", "front"), -4, -8)
    if headgear in ("hood", "helm"):
        paste(c, face_img(skin, "hood", "front"), -4, -8)
    if headgear == "straw_hat":
        paste(c, face_img(skin, "hat_brim", "front"), -6, -5)
    return c


def back_view(skin, headgear):
    c = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    paste(c, face_img(skin, "torso", "back"), -5, 0)
    paste(c, face_img(skin, "belt", "back"), -5, 7)
    paste(c, face_img(skin, "cloak", "back"), -5.5, 0)
    paste(c, face_img(skin, "backpack", "back"), -3, 3)
    paste(c, face_img(skin, "right_leg", "back"), 0.6, 12)
    paste(c, face_img(skin, "left_leg", "back"), -4.6, 12)
    paste(c, face_img(skin, "right_arm", "back"), 4, 0)
    paste(c, face_img(skin, "left_arm", "back"), -8, 0)
    paste(c, face_img(skin, "head", "back"), -4, -8)
    if headgear in ("hood", "helm"):
        paste(c, face_img(skin, "hood", "back"), -4, -8)
    if headgear == "straw_hat":
        paste(c, face_img(skin, "hat_brim", "back"), -6, -5)
    return c


def side_view(skin, headgear):
    c = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    paste(c, face_img(skin, "left_leg", "right"), -2, 12)
    paste(c, face_img(skin, "torso", "right"), -2.5, 0)
    paste(c, face_img(skin, "cloak", "right"), -3, 0)
    paste(c, face_img(skin, "backpack", "right"), 2.5, 3)
    paste(c, face_img(skin, "right_leg", "right"), -2, 12)
    paste(c, face_img(skin, "right_arm", "right"), -2, 0)
    paste(c, face_img(skin, "head", "right"), -4, -8)
    if headgear in ("hood", "helm"):
        paste(c, face_img(skin, "hood", "right"), -4, -8)
    if headgear == "straw_hat":
        paste(c, face_img(skin, "hat_brim", "right"), -6, -5)
    return c


def main():
    combos = sample_combos()
    sheet = Image.new("RGBA", (CW * 3 * SCALE + 40,
                               CH * SCALE * len(combos) + 20 + 20 * len(combos)),
                      (38, 34, 30, 255))
    y = 10
    for combo in combos:
        skin = build_combo(combo)
        headgear = PROFESSION_OUTFITS[combo["prof"]]["headgear"]
        x = 10
        for view_fn in (front_view, side_view, back_view):
            v = view_fn(skin, headgear)
            v = v.resize((CW * SCALE, CH * SCALE), Image.NEAREST)
            sheet.alpha_composite(v, (x, y))
            x += CW * SCALE
        y += CH * SCALE + 20
    out = os.path.join(os.path.dirname(__file__), "preview_settlers.png")
    sheet.save(out)
    print("wrote", out, f"({len(combos)} combos)")


if __name__ == "__main__":
    main()
