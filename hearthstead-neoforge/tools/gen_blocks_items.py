#!/usr/bin/env python3
"""Hearth block set, handbook, spawn-egg-free extras and
the mod logo. All deterministic pixel art."""
import os
import random
import sys

sys.path.insert(0, os.path.dirname(__file__))
from PIL import Image
from texlib import (ramp, shade, mix, new_image, fill, put, save, stone,
                    outline_rect)

ASSETS = os.path.join(os.path.dirname(__file__), "..",
                      "src/main/resources/assets/hearthstead")
RESOURCES = os.path.join(os.path.dirname(__file__), "..", "src/main/resources")


# ------------------------------------------------------------ block set ---

def gen_hearth_stone():
    img = new_image(16, 16)
    rng = random.Random(101)
    stone(img, 0, 0, 16, 16, ramp("stone"), rng)
    save(img, f"{ASSETS}/textures/block/hearth_stone.png")


def gen_hearth_bowl():
    """Brazier side: fieldstone with a wrought-iron band on top."""
    img = new_image(16, 16)
    rng = random.Random(102)
    stone(img, 0, 0, 16, 16, ramp("stone"), rng, block_w=(2, 4), block_h=(2, 3))
    iron = ramp("iron")
    for x in range(16):
        put(img, x, 0, iron[4 if x % 5 else 3])
        put(img, x, 1, iron[2 if x % 4 else 3])
    # rivets
    for x in (1, 6, 11, 15):
        put(img, x, 1, iron[4])
    save(img, f"{ASSETS}/textures/block/hearth_bowl.png")


def gen_hearth_top():
    """Plinth top: stone ring around the bowl footprint, iron corners."""
    img = new_image(16, 16)
    rng = random.Random(103)
    stone(img, 0, 0, 16, 16, ramp("stone"), rng, block_w=(3, 4), block_h=(3, 4))
    iron = ramp("iron")
    for (cx, cy) in ((1, 1), (13, 1), (1, 13), (13, 13)):
        for dx in range(2):
            for dy in range(2):
                put(img, cx + dx, cy + dy, iron[3 if (dx + dy) % 2 else 2])
    # scorched inner rim where the bowl sits
    coal = (30, 24, 20, 255)
    for i in range(2, 14):
        put(img, i, 2, shade(coal, 1.2))
        put(img, i, 13, coal)
        put(img, 2, i, coal)
        put(img, 13, i, shade(coal, 1.2))
    save(img, f"{ASSETS}/textures/block/hearth_top.png")


def gen_hearth_ember():
    """Animated ember bed: 3 frames, 16x48, warm pulsing coals."""
    frames = 3
    img = new_image(16, 16 * frames)
    rng = random.Random(104)
    ember = ramp("ember")
    coals = []
    for _ in range(26):
        coals.append((rng.randint(1, 14), rng.randint(1, 14), rng.random()))
    for f in range(frames):
        base_y = f * 16
        # dark coal bed
        bed_rng = random.Random(300)  # same bed every frame; only glow moves
        for y in range(16):
            for x in range(16):
                r = bed_rng.random()
                idx = 1 if r < 0.25 else 0
                f_shade = 0.5 + bed_rng.random() * 0.18
                put(img, x, base_y + y, shade(ember[idx], f_shade))
        # glowing coals, phase-shifted per frame
        for (cx, cy, phase) in coals:
            heat = (phase + f / frames) % 1.0
            idx = 2 + int(heat * 2.999)
            put(img, cx, base_y + cy, ember[idx])
            if heat > 0.6:
                put(img, cx + 1, base_y + cy, ember[2])
            if heat > 0.85:
                put(img, cx, base_y + cy + 1, ember[3])
    save(img, f"{ASSETS}/textures/block/hearth_ember.png")
    with open(f"{ASSETS}/textures/block/hearth_ember.png.mcmeta", "w") as fp:
        fp.write('{"animation": {"frametime": 10, "interpolate": true}}\n')


# ---------------------------------------------------------------- items ---

def gen_handbook():
    img = new_image(16, 16)
    leather = ramp("leather")
    parch = ramp("parchment")
    amber = ramp("amber")
    # cover
    for y in range(2, 14):
        for x in range(3, 13):
            idx = 3
            if x == 3 or y in (2, 13):
                idx = 2
            if x == 12:
                idx = 1
            put(img, x, y, leather[idx])
    # spine
    for y in range(2, 14):
        put(img, 3, y, leather[1])
        put(img, 4, y, leather[2])
    # page block edge
    for y in range(3, 13):
        put(img, 13, y, parch[3] if y % 2 else parch[2])
    # clasp
    put(img, 12, 7, amber[3])
    put(img, 12, 8, amber[2])
    # hearth emblem: small flame
    put(img, 8, 6, amber[4])
    put(img, 7, 7, amber[3])
    put(img, 8, 7, amber[4])
    put(img, 9, 7, amber[2])
    put(img, 7, 8, amber[2])
    put(img, 8, 8, amber[3])
    put(img, 9, 8, amber[2])
    put(img, 8, 9, ramp("ember")[2])
    save(img, f"{ASSETS}/textures/item/handbook.png")


# ----------------------------------------------------------------- logo ---

FONT5 = {  # 5-row uppercase pixel font, widths vary
    "H": ["#.#", "#.#", "###", "#.#", "#.#"],
    "E": ["###", "#..", "##.", "#..", "###"],
    "A": [".#.", "#.#", "###", "#.#", "#.#"],
    "R": ["##.", "#.#", "##.", "#.#", "#.#"],
    "T": ["###", ".#.", ".#.", ".#.", ".#."],
    "S": [".##", "#..", ".#.", "..#", "##."],
    "D": ["##.", "#.#", "#.#", "#.#", "##."],
}


def draw_text(img, text, x, y, color, scale=2):
    cx = x
    for ch in text:
        glyph = FONT5[ch]
        for gy, row in enumerate(glyph):
            for gx, cell in enumerate(row):
                if cell == "#":
                    fill(img, cx + gx * scale, y + gy * scale, scale, scale, color)
        cx += (len(glyph[0]) + 1) * scale
    return cx - x


def gen_logo():
    img = new_image(256, 128)
    rng = random.Random(105)
    # Emblem: stone ring with fire, left of wordmark.
    stone_r = ramp("stone")
    ember = ramp("ember")
    amber = ramp("amber")
    cx, cy, r = 52, 64, 40
    ring_rng = random.Random(106)
    for y in range(128):
        for x in range(110):
            d2 = (x - cx) ** 2 + (y - cy) ** 2
            if r * r * 0.62 < d2 <= r * r:
                rv = ring_rng.random()
                idx = 3 if rv < 0.55 else (2 if rv < 0.85 else 4)
                c = stone_r[idx]
                if d2 > r * r * 0.9 or d2 < r * r * 0.68:
                    c = shade(c, 0.82)  # beveled inner/outer edge
                put(img, x, y, c)
    # flame inside the ring
    flame = [
        "....##....",
        "...###....",
        "...####...",
        "..#####...",
        "..######..",
        ".#######..",
        ".########.",
        "##########",
        ".########.",
        "..######..",
    ]
    fs = 4
    fx, fy = cx - len(flame[0]) * fs // 2, cy - len(flame) * fs // 2
    for gy, row in enumerate(flame):
        for gx, cell in enumerate(row):
            if cell == "#":
                depth = gy / len(flame)
                color = amber[4] if depth < 0.35 else amber[3] if depth < 0.6 \
                    else ember[3] if depth < 0.85 else ember[2]
                fill(img, fx + gx * fs, fy + gy * fs, fs, fs, color)
    # Wordmark with drop shadow.
    ink = ramp("ink")
    text_x = 112
    draw_text(img, "HEARTHSTEAD", text_x + 2, 50 + 2, shade(ink[0], 0.7), scale=3)
    draw_text(img, "HEARTHSTEAD", text_x, 50, ramp("wheat")[3], scale=3)
    save(img, f"{RESOURCES}/hearthstead_logo.png")


# ------------------------------------------------- SLICE CHAINS item icons ---
# Six intermediate goods bound by FLOWS.md (docs/project/FLOWS.md), plus ALE
# (the brewery's fed path has to end somewhere -- see ModItems.java). Flat
# 16x16 icons in the vanilla convention (project standard per
# validate_assets.py), each a plain silhouette built from texlib's shared
# palettes so the set reads as one family with the rest of the mod's items.
# Every rng below is a fresh, fixed-seed random.Random -- never the bare
# `random` module -- so PYTHONHASHSEED cannot perturb a single pixel
# (tools/validate_assets.py's pipeline check runs this file twice, once per
# hash seed, and requires byte-identical output).

def gen_item_flour():
    """A tied cloth sack, pale flour dusting its neck."""
    img = new_image(16, 16)
    rng = random.Random(201)
    linen = ramp("linen_raw")
    # sack body: a rounded triangle widening toward the base
    for y in range(4, 15):
        half = min(5, 1 + (y - 4))
        for x in range(8 - half, 8 + half):
            idx = 2
            r = rng.random()
            if r < 0.18:
                idx = 1
            elif r < 0.32:
                idx = 3
            if x == 8 - half:
                idx = max(0, idx - 1)          # left shade
            if x == 8 + half - 1:
                idx = min(4, idx + 1)           # right catch-light
            put(img, x, y, linen[idx])
    # tied neck
    for y in range(2, 4):
        for x in range(7, 10):
            put(img, x, y, linen[1] if y == 2 else linen[2])
    put(img, 7, 2, linen[0])
    put(img, 9, 2, linen[0])
    # a wisp of flour dust escaping the tie
    put(img, 6, 3, shade(linen[4], 1.05))
    put(img, 10, 3, shade(linen[4], 1.05))
    put(img, 8, 1, shade(linen[4], 1.1))
    save(img, f"{ASSETS}/textures/item/flour.png")


def gen_item_malt():
    """Toasted grain, a small heap on a wooden board."""
    img = new_image(16, 16)
    rng = random.Random(202)
    wheat = ramp("wheat")
    oak = ramp("oak_light")
    # board
    for y in range(11, 14):
        for x in range(2, 14):
            put(img, x, y, oak[1] if y == 13 else oak[2 if (x + y) % 3 else 1])
    # heap of kernels, denser toward the middle, darker (toasted) than raw wheat
    for y in range(3, 12):
        width = max(0, 6 - abs(y - 8))
        for x in range(8 - width, 8 + width):
            r = rng.random()
            idx = 1 if r < 0.4 else (0 if r < 0.55 else (2 if r < 0.85 else 3))
            put(img, x, y, wheat[idx])
    # a few lit kernels breaking the silhouette on top
    for (x, y) in ((6, 3), (8, 2), (10, 4), (7, 4), (9, 3)):
        put(img, x, y, wheat[3])
    save(img, f"{ASSETS}/textures/item/malt.png")


def gen_item_ale():
    """A short wooden tankard, foam over amber ale."""
    img = new_image(16, 16)
    oak = ramp("oak_light")
    amber = ramp("amber")
    foam = ramp("linen")
    # body
    for y in range(6, 15):
        for x in range(4, 12):
            idx = 3
            if x == 4:
                idx = 1
            elif x == 5:
                idx = 2
            elif x == 11:
                idx = 1
            put(img, x, y, oak[idx])
    # handle
    for y in range(8, 12):
        put(img, 12, y, oak[2])
    put(img, 13, 8, oak[1])
    put(img, 13, 11, oak[1])
    # ale within the rim
    for y in range(7, 9):
        for x in range(5, 11):
            put(img, x, y, amber[2 if y == 7 else 3])
    # foam cap
    for x in range(4, 12):
        put(img, x, 6, foam[4 if x % 2 else 3])
    put(img, 5, 5, foam[3])
    put(img, 8, 5, foam[4])
    put(img, 10, 5, foam[3])
    # rim highlight
    put(img, 4, 6, shade(oak[4], 1.1))
    save(img, f"{ASSETS}/textures/item/ale.png")


def gen_item_iron_bloom():
    """A rough, still-hot lump straight off the bloomery -- no clean facets."""
    img = new_image(16, 16)
    rng = random.Random(203)
    iron = ramp("iron")
    ember = ramp("ember")
    cx, cy = 8, 9
    for y in range(3, 15):
        for x in range(2, 14):
            dx, dy = x - cx, (y - cy) * 1.25
            d2 = dx * dx + dy * dy
            wob = 1.0 + 0.22 * ((x * 7 + y * 5) % 5 - 2) / 2
            if d2 > 34 * wob:
                continue
            r = rng.random()
            idx = 2
            if r < 0.20:
                idx = 1
            elif r < 0.35:
                idx = 3
            if y < 6:
                idx = min(4, idx + 1)           # top catch-light
            put(img, x, y, iron[idx])
    # hot cracks still glowing
    for (x, y) in ((6, 7), (7, 8), (9, 9), (8, 10), (10, 8)):
        put(img, x, y, ember[2])
    put(img, 7, 7, ember[3])
    save(img, f"{ASSETS}/textures/item/iron_bloom.png")


def gen_item_timber_beam():
    """A short dressed beam, seen three-quarter with its cut end showing rings."""
    img = new_image(16, 16)
    oak = ramp("oak")
    oak_l = ramp("oak_light")
    # cut end (left): concentric rings
    for y in range(3, 13):
        for x in range(1, 6):
            d = max(abs(x - 3), abs(y - 8) * 0.7)
            idx = 1 if int(d) % 2 == 0 else 2
            put(img, x, y, oak_l[idx])
    put(img, 3, 8, oak_l[0])
    # shaft, planed flat with visible grain lines
    for y in range(4, 12):
        for x in range(6, 15):
            idx = 3
            if y in (4, 11):
                idx = 2
            if (x + y * 3) % 7 == 0:
                idx = 4                          # grain streak, lit
            put(img, x, y, oak_l[idx])
    for x in range(6, 15):
        put(img, x, 3, oak[2])                  # top edge, darker cap rail
        put(img, x, 12, oak[0])                 # underside, shadow
    save(img, f"{ASSETS}/textures/item/timber_beam.png")


def gen_item_cured_hide():
    """A stretched pelt, corners pegged out flat -- rawer and paler than tanned leather."""
    img = new_image(16, 16)
    rng = random.Random(204)
    hide = ramp("leather")
    outline = shade(hide[0], 0.8)
    # rounded body with four rough leg-corners
    rows = {
        2: (5, 11), 3: (3, 13), 4: (2, 14), 5: (2, 14), 6: (1, 15),
        7: (1, 15), 8: (2, 14), 9: (2, 14), 10: (3, 13), 11: (4, 12),
        12: (5, 11), 13: (6, 10),
    }
    for y, (x0, x1) in rows.items():
        for x in range(x0, x1):
            r = rng.random()
            idx = 3 if r < 0.55 else (2 if r < 0.85 else 4)
            if x in (x0, x1 - 1):
                idx = max(0, idx - 1)
            put(img, x, y, hide[idx])
    for y, (x0, x1) in rows.items():
        put(img, x0, y, outline)
        put(img, x1 - 1, y, outline)
    put(img, 8, 6, hide[4])                     # a raw highlight, off-centre
    save(img, f"{ASSETS}/textures/item/cured_hide.png")


def gen_item_wool_bolt():
    """A rolled bolt of woven cloth, end-on, cord-tied like the build plan's scroll."""
    img = new_image(16, 16)
    rng = random.Random(205)
    linen = ramp("linen")
    leather = ramp("leather")
    cx, cy = 8, 8
    for y in range(1, 15):
        for x in range(2, 14):
            dx, dy = x - cx, y - cy
            d2 = dx * dx + dy * dy * 0.36
            if d2 > 46:
                continue
            f = dx / 7.0
            if f < -0.5:
                idx = 4
            elif f < 0.0:
                idx = 3
            elif f < 0.5:
                idx = 2
            else:
                idx = 1
            if rng.random() < 0.10:
                idx = max(0, idx - 1)
            put(img, x, y, linen[idx])
    # end cap rings (the roll's face)
    for y in range(4, 12):
        for x in range(2, 6):
            dx, dy = x - 4, y - 7.5
            if dx * dx + dy * dy * 0.6 <= 4:
                put(img, x, y, linen[1 if (int(dx) + int(dy)) % 2 else 2])
    # cord ties, two bands
    for x in (6, 10):
        for y in range(1, 15):
            if 2 <= y <= 13:
                put(img, x, y, leather[2 if y % 2 else 1])
    save(img, f"{ASSETS}/textures/item/wool_bolt.png")


if __name__ == "__main__":
    gen_hearth_stone()
    gen_hearth_bowl()
    gen_hearth_top()
    gen_hearth_ember()
    gen_handbook()
    gen_logo()
    gen_item_flour()
    gen_item_malt()
    gen_item_ale()
    gen_item_iron_bloom()
    gen_item_timber_beam()
    gen_item_cured_hide()
    gen_item_wool_bolt()
    print("blocks/items/logo done")
