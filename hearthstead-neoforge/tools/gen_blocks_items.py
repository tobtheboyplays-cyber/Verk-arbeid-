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


if __name__ == "__main__":
    gen_hearth_stone()
    gen_hearth_bowl()
    gen_hearth_top()
    gen_hearth_ember()
    gen_handbook()
    gen_logo()
    print("blocks/items/logo done")
