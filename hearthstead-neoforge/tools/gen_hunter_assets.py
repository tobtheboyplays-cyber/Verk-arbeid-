#!/usr/bin/env python3
"""Hunter rework textures: the carcass item icon and the butchering table
top/side. Deterministic pixel art in the shared texlib palette."""
import os
import random
import sys

sys.path.insert(0, os.path.dirname(__file__))
from texlib import ramp, shade, mix, new_image, put, save, planks

ASSETS = os.path.join(os.path.dirname(__file__), "..",
                      "src/main/resources/assets/hearthstead")


def gen_carcass():
    """A game carcass lying on its side, legs trussed together for carrying."""
    img = new_image(16, 16)
    fur = ramp("leather")
    belly = ramp("linen_raw")
    bone = ramp("bone")
    rope = ramp("straw")
    blood = ramp("crimson")
    outline = shade(fur[0], 0.6)
    # body: an oval 11 x 5 centred low
    cx, cy, rx, ry = 8.0, 10.0, 5.6, 2.8
    for y in range(16):
        for x in range(16):
            d = ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2
            if d <= 1.0:
                tone = 3 if y < cy - 1 else 2 if y < cy + 1 else 1
                put(img, x, y, fur[tone])
                if y >= cy + 1 and 4 <= x <= 11:
                    put(img, x, y, belly[2])
            elif d <= 1.35:
                put(img, x, y, outline)
    # head and ear on the left, drooping
    for (x, y, c) in ((1, 10, fur[2]), (2, 10, fur[3]), (1, 11, fur[1]), (2, 11, fur[2]),
                      (0, 11, outline), (3, 9, fur[3]), (2, 9, fur[4]), (0, 12, bone[1])):
        put(img, x, y, c)
    put(img, 1, 10, shade(fur[0], 0.5))  # closed eye
    # four legs raised and trussed together over the body
    legs = [(5, 7), (6, 6), (9, 6), (10, 7)]
    for (lx, ly) in legs:
        for step in range(4):
            put(img, lx + (1 if lx < 8 else -1) * (step // 2), ly - step, fur[1 + step % 2])
    for (x, y) in ((6, 3), (7, 3), (8, 3), (9, 3)):
        put(img, x, y, rope[3])
    put(img, 7, 2, rope[4])
    put(img, 8, 2, rope[2])
    for (x, y) in ((6, 2), (9, 2)):
        put(img, x, y, bone[2])  # hooves peeking past the knot
    # tail and a small wound
    put(img, 14, 9, fur[4])
    put(img, 15, 9, outline)
    put(img, 11, 10, blood[3])
    put(img, 11, 11, blood[2])
    save(img, f"{ASSETS}/textures/item/carcass.png")


def gen_table_top():
    """Scrubbed oak slab: knife scores and an old dark stain."""
    img = new_image(16, 16)
    rng = random.Random(1717)
    planks(img, 0, 0, 16, 16, ramp("oak"), rng, board=4)
    score = shade(ramp("oak")[1], 0.9)
    for (x0, y0, length) in ((2, 3, 5), (8, 6, 6), (3, 11, 4), (10, 12, 4)):
        for i in range(length):
            put(img, x0 + i, y0 + (i // 3), score)
    stain = ramp("burgundy")
    for (x, y, t) in ((6, 8, 1), (7, 8, 2), (7, 9, 1), (8, 9, 2), (6, 9, 0), (8, 8, 1)):
        base = img.getpixel((x, y))
        put(img, x, y, mix(base, stain[t], 0.55))
    save(img, f"{ASSETS}/textures/block/butchering_table_top.png")


def gen_table_side():
    """Thick slab edge: dark oak end grain band."""
    img = new_image(16, 16)
    rng = random.Random(1718)
    oak = ramp("oak")
    planks(img, 0, 0, 16, 16, oak, rng, board=16)
    for x in range(16):
        put(img, x, 0, oak[4])
        put(img, x, 15, oak[0])
    save(img, f"{ASSETS}/textures/block/butchering_table_side.png")


if __name__ == "__main__":
    gen_carcass()
    gen_table_top()
    gen_table_side()
