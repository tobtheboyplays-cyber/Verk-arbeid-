#!/usr/bin/env python3
"""Builder lane assets (plan/BUILDER.md): the Builder emblem and the Builder's
Plan item icon, painted deterministically with texlib (16x16, upscaled NEAREST
to 32x32 like every other emblem), plus their item model JSON.

    python tools/gen_builder_assets.py
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from PIL import Image  # noqa: E402
from texlib import ramp, new_image, put, save  # noqa: E402

ASSETS = os.path.join(os.path.dirname(__file__), "..",
                      "src", "main", "resources", "assets", "hearthstead")


def gen_builder_emblem():
    """A walnut leather crest with a hammer over a plumb line: a builder's
    marks, distinct from the mason's chisel and the carpenter's plane."""
    img = new_image(16, 16)
    leather = ramp("leather")
    oak = ramp("oak_light")
    iron = ramp("iron")
    brass = ramp("brass")
    parchment = ramp("parchment")

    for x, y, colour in ((7, 0, iron[3]), (8, 0, iron[2]),
                         (6, 1, iron[2]), (7, 1, iron[1]), (8, 1, iron[3]), (9, 1, iron[1])):
        put(img, x, y, colour)
    rows = {2: (4, 11), 3: (3, 12), 4: (2, 13), 5: (2, 13), 6: (2, 13), 7: (2, 13),
            8: (2, 13), 9: (2, 13), 10: (3, 12), 11: (3, 12), 12: (4, 11), 13: (5, 10), 14: (7, 8)}
    for y, (left, right) in rows.items():
        for x in range(left, right + 1):
            edge = x in (left, right) or y in (2, 14)
            put(img, x, y, leather[1 if edge else 3])
    # Parchment field: a plan sheet behind the tools.
    for y in range(4, 12):
        inset = 1 if y >= 10 else 0
        for x in range(4 + inset, 12 - inset):
            put(img, x, y, parchment[3 if (x + y) % 5 else 2])
    # Plumb line: a brass bob on a dark cord, down the right third.
    for y in range(4, 10):
        put(img, 9, y, iron[1])
    put(img, 9, 10, brass[4])
    put(img, 8, 10, brass[2])
    put(img, 10, 10, brass[2])
    put(img, 9, 11, brass[1])
    # Hammer: oak haft on the diagonal, iron head top-left.
    for x, y in ((5, 10), (6, 9), (7, 8), (8, 7)):
        put(img, x, y, oak[3])
    for x, y in ((4, 11), (5, 11)):
        put(img, x, y, oak[1])
    for x, y, c in ((7, 5, iron[4]), (8, 5, iron[3]), (9, 6, iron[2]), (6, 6, iron[3]),
                    (7, 6, iron[4]), (8, 6, iron[2]), (6, 5, iron[2])):
        put(img, x, y, c)
    save(img.resize((32, 32), Image.Resampling.NEAREST),
         os.path.join(ASSETS, "textures", "item", "builder_emblem.png"))


def gen_builders_plan():
    """A rolled drawing half open on a board: blue-black plan lines on
    parchment, an oak board edge and a charcoal stick."""
    img = new_image(16, 16)
    parchment = ramp("parchment")
    oak = ramp("oak")
    ink = ramp("ink")
    charcoal = ramp("charcoal")
    # Board
    for y in range(3, 14):
        for x in range(1, 15):
            put(img, x, y, oak[2 if (y % 3) else 1])
    # Sheet
    for y in range(4, 13):
        for x in range(2, 12):
            put(img, x, y, parchment[4 if x > 3 else 3])
    # Rolled edge on the right
    for y in range(4, 13):
        put(img, 12, y, parchment[2])
        put(img, 13, y, parchment[1])
    # Plan lines: a house outline with a pitched roof
    for x in range(4, 10):
        put(img, x, 11, ink[2])
    for y in range(8, 12):
        put(img, 4, y, ink[2])
        put(img, 9, y, ink[2])
    for i, (x, y) in enumerate(((4, 8), (5, 7), (6, 6), (7, 6), (8, 7), (9, 8))):
        put(img, x, y, ink[3])
    put(img, 6, 10, ink[1])
    put(img, 6, 11, ink[1])
    # Charcoal stick
    for x, y in ((10, 13), (11, 14), (12, 15)):
        put(img, x, min(y, 15), charcoal[2])
    save(img.resize((32, 32), Image.Resampling.NEAREST),
         os.path.join(ASSETS, "textures", "item", "builders_plan.png"))


def gen_survey_rod():
    """A surveyor's rod: an oak staff banded with red/white measure marks and a
    brass plumb bob hanging from its head on a string."""
    img = new_image(16, 16)
    oak = ramp("oak_light")
    crimson = ramp("crimson")
    linen = ramp("linen")
    brass = ramp("brass")
    iron = ramp("iron")
    # staff on the diagonal, bottom-left to top-right, banded every 2 px
    for i, (x, y) in enumerate(((2, 14), (3, 13), (4, 12), (5, 11), (6, 10), (7, 9), (8, 8), (9, 7),
                                (10, 6), (11, 5), (12, 4))):
        band = crimson[3] if (i // 2) % 2 == 0 else linen[4]
        put(img, x, y, band if 2 <= i <= 9 else oak[3])
        put(img, x + 1, y, oak[1])
    # head cap and plumb string + bob
    put(img, 13, 3, iron[3])
    put(img, 13, 2, iron[2])
    for y in range(4, 9):
        put(img, 14, y, iron[1])
    put(img, 14, 9, brass[4])
    put(img, 13, 10, brass[2])
    put(img, 14, 10, brass[3])
    put(img, 15, 10, brass[2])
    put(img, 14, 11, brass[1])
    save(img.resize((32, 32), Image.Resampling.NEAREST),
         os.path.join(ASSETS, "textures", "item", "survey_rod.png"))


def gen_resource_scroll():
    """A rolled parchment tied with a leather cord and stamped with the
    builder's hammer seal -- the hut's list of needs, carried in a pocket."""
    img = new_image(16, 16)
    parchment = ramp("parchment")
    leather = ramp("leather")
    ink = ramp("ink")
    burgundy = ramp("burgundy")
    for i in range(10):
        x0, y0 = 3 + i, 12 - i
        for d in range(3):
            put(img, x0 + d - 1, y0 + d - 1, parchment[4 if d == 0 else 3 if d == 1 else 2])
    for d in range(3):  # rolled ends
        put(img, 2 + d - 1, 13 + d - 1, parchment[1])
        put(img, 13 + d - 1, 2 + d - 1, parchment[1])
    for x, y in ((6, 9), (7, 8), (8, 7)):   # ink lines of the list
        put(img, x, y + 1, ink[2])
    put(img, 7, 7, leather[2])
    put(img, 8, 8, leather[1])
    put(img, 9, 9, burgundy[3])               # wax seal
    put(img, 10, 9, burgundy[2])
    put(img, 9, 10, burgundy[2])
    save(img.resize((32, 32), Image.Resampling.NEAREST),
         os.path.join(ASSETS, "textures", "item", "resource_scroll.png"))


def gen_models():
    for name in ("builder_emblem", "builders_plan", "survey_rod", "resource_scroll"):
        path = os.path.join(ASSETS, "models", "item", name + ".json")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            json.dump({"parent": "minecraft:item/generated",
                       "textures": {"layer0": "hearthstead:item/" + name}}, f, indent=2)
            f.write("\n")


if __name__ == "__main__":
    gen_builder_emblem()
    gen_builders_plan()
    gen_survey_rod()
    gen_resource_scroll()
    gen_models()
    print("builder assets written")
