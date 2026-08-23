#!/usr/bin/env python3
"""Orthographic turnaround previews: composite each settler skin onto the
model geometry (front/side/back) so the art is reviewed as it will render."""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from PIL import Image
from gen_settler import UV, PROFILES, generate
from texlib import box_faces

SCALE = 6
# Canvas in model px: x -10..10, y -10..25  (y down, 0 = neck)
CW, CH = 22, 36
OX, OY = 11, 10


def face_img(skin, part, face):
    u, v, w, h, d = UV[part]
    rect = box_faces(u, v, w, h, d)[face]
    return skin.crop((rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3]))


def paste(canvas, img, x, y):
    canvas.alpha_composite(img, (int((x + OX)), int((y + OY))))


def front_view(skin, prof):
    c = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    paste(c, face_img(skin, "backpack", "front"), -3, 3)      # peeks over shoulders
    paste(c, face_img(skin, "torso", "front"), -5, 0)
    paste(c, face_img(skin, "belt", "front"), -5, 7)
    paste(c, face_img(skin, "cloak", "front"), -5.5, 0)
    paste(c, face_img(skin, "right_leg", "front"), -4.6, 12)
    paste(c, face_img(skin, "left_leg", "front"), 0.6, 12)
    paste(c, face_img(skin, "right_arm", "front"), -8, 0)
    paste(c, face_img(skin, "left_arm", "front"), 4, 0)
    paste(c, face_img(skin, "head", "front"), -4, -8)
    p = PROFILES[prof]
    if p["headgear"] in ("hood", "helm"):
        paste(c, face_img(skin, "hood", "front"), -4, -8)
    if p["headgear"] == "straw_hat":
        paste(c, face_img(skin, "hat_brim", "front"), -6, -5)
    return c


def back_view(skin, prof):
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
    p = PROFILES[prof]
    if p["headgear"] in ("hood", "helm"):
        paste(c, face_img(skin, "hood", "back"), -4, -8)
    if p["headgear"] == "straw_hat":
        paste(c, face_img(skin, "hat_brim", "back"), -6, -5)
    return c


def side_view(skin, prof):
    c = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    paste(c, face_img(skin, "left_leg", "right"), -2, 12)
    paste(c, face_img(skin, "torso", "right"), -2.5, 0)
    paste(c, face_img(skin, "cloak", "right"), -3, 0)
    paste(c, face_img(skin, "backpack", "right"), 2.5, 3)
    paste(c, face_img(skin, "right_leg", "right"), -2, 12)
    paste(c, face_img(skin, "right_arm", "right"), -2, 0)
    paste(c, face_img(skin, "head", "right"), -4, -8)
    p = PROFILES[prof]
    if p["headgear"] in ("hood", "helm"):
        paste(c, face_img(skin, "hood", "right"), -4, -8)
    if p["headgear"] == "straw_hat":
        paste(c, face_img(skin, "hat_brim", "right"), -6, -5)
    return c


def main():
    profs = list(PROFILES)
    sheet = Image.new("RGBA", (CW * 3 * len(profs) * SCALE // 1 + 40,
                               CH * SCALE + 20), (38, 34, 30, 255))
    x = 10
    for prof in profs:
        skin = generate(prof)
        for view_fn in (front_view, side_view, back_view):
            v = view_fn(skin, prof)
            v = v.resize((CW * SCALE, CH * SCALE), Image.NEAREST)
            sheet.alpha_composite(v, (x, 10))
            x += CW * SCALE
    out = os.path.join(os.path.dirname(__file__), "preview_settlers.png")
    sheet.save(out)
    print("wrote", out)


if __name__ == "__main__":
    main()
