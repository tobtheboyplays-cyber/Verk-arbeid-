#!/usr/bin/env python3
"""GUI sheets: hearth management screen, settler card, handbook.
All 256x256 (blit-friendly default atlas size)."""
import os
import random
import sys

sys.path.insert(0, os.path.dirname(__file__))
from texlib import ramp, shade, mix, new_image, fill, put, save

ASSETS = os.path.join(os.path.dirname(__file__), "..",
                      "src/main/resources/assets/hearthstead")

PARCH = ramp("parchment")
OAK = ramp("oak")
IRON = ramp("iron")
INK = ramp("ink")
LEATHER = ramp("leather")


def parchment_fill(img, x, y, w, h, rng, dark=0):
    for j in range(h):
        for i in range(w):
            r = rng.random()
            idx = 3 - dark
            if r < 0.06:
                idx = max(0, idx - 1)
            elif r < 0.10:
                idx = min(4, idx + 1)
            c = PARCH[idx]
            # subtle vertical vignette
            edge = min(j, h - 1 - j, i, w - 1 - i)
            if edge < 3:
                c = shade(c, 0.97)
            put(img, x + i, y + j, c)
    # scattered fibers
    for _ in range(w * h // 160):
        fx, fy = x + rng.randint(1, w - 3), y + rng.randint(1, h - 2)
        put(img, fx, fy, shade(PARCH[2], 0.95))
        if rng.random() < 0.5:
            put(img, fx + 1, fy, shade(PARCH[2], 0.97))


def oak_frame(img, x, y, w, h, rng, t=6):
    """Weathered oak border, light source top-left, iron corner plates."""
    for j in range(h):
        for i in range(w):
            edge = min(i, j, w - 1 - i, h - 1 - j)
            if edge >= t:
                continue
            grain = rng.random()
            if edge == 0:
                idx = 0
            elif edge < 3:
                idx = 1 if grain < 0.75 else 2
            elif edge < t - 1:
                idx = 3 if grain < 0.8 else 2
            else:
                idx = 4  # inner catch-light
            # top/left slightly lighter than bottom/right
            c = OAK[idx]
            if i < w - 1 - i and j < h - 1 - j:
                c = shade(c, 1.06)
            put(img, x + i, y + j, c)
    # iron corner plates with rivets
    ps = 9
    for (cx, cy) in ((x, y), (x + w - ps, y), (x, y + h - ps), (x + w - ps, y + h - ps)):
        for j in range(ps):
            for i in range(ps):
                edge = min(i, j, ps - 1 - i, ps - 1 - j)
                idx = 1 if edge == 0 else (3 if edge < 3 else 2)
                put(img, cx + i, cy + j, IRON[idx])
        put(img, cx + 2, cy + 2, IRON[4])
        put(img, cx + ps - 3, cy + 2, IRON[4])
        put(img, cx + 2, cy + ps - 3, IRON[4])
        put(img, cx + ps - 3, cy + ps - 3, IRON[4])


def title_band(img, x, y, w, h, rng):
    for j in range(h):
        for i in range(w):
            grain = rng.random()
            idx = 1 if grain < 0.7 else (0 if grain < 0.85 else 2)
            if j == 0:
                idx = 2
            if j == h - 1:
                idx = 0
            put(img, x + i, y + j, OAK[idx])
    # thin wheat-gold underline
    gold = ramp("wheat")[3]
    for i in range(6, w - 6):
        put(img, x + i, y + h - 2, shade(gold, 0.8))


def inset_panel(img, x, y, w, h, rng):
    parchment_fill(img, x, y, w, h, rng, dark=1)
    dark = shade(PARCH[0], 0.8)
    light = PARCH[4]
    for i in range(w):
        put(img, x + i, y, dark)
        put(img, x + i, y + h - 1, light)
    for j in range(h):
        put(img, x, y + j, dark)
        put(img, x + w - 1, y + j, light)


def slot_well(img, x, y):
    """18x18 recessed item well."""
    inner = shade(PARCH[1], 0.92)
    for j in range(18):
        for i in range(18):
            put(img, x + i, y + j, inner)
    dark = shade(INK[1], 1.1)
    light = PARCH[4]
    for i in range(18):
        put(img, x + i, y, dark)
        put(img, x + i, y + 17, light)
    for j in range(18):
        put(img, x, y + j, dark)
        put(img, x + 17, y + j, light)
    put(img, x + 17, y, mix(dark, light, 0.5))
    put(img, x, y + 17, mix(dark, light, 0.5))


def groove(img, x, y, w, h):
    inner = shade(LEATHER[1], 0.75)
    for j in range(h):
        for i in range(w):
            put(img, x + i, y + j, inner)
    dark = shade(INK[0], 0.9)
    light = PARCH[3]
    for i in range(w):
        put(img, x + i, y, dark)
        put(img, x + i, y + h - 1, light)
    for j in range(h):
        put(img, x, y + j, dark)
        put(img, x + w - 1, y + j, light)


# ------------------------------------------------------------------ icons ---

def icon(img, u, v, rows):
    """Draw a 12x12 glyph; '#' ink, '+' highlight, '.' empty."""
    shadow = shade(PARCH[4], 1.0)
    for j, row in enumerate(rows):
        for i, cell in enumerate(row):
            if cell == "#":
                put(img, u + i + 1, v + j + 1, shadow)
    for j, row in enumerate(rows):
        for i, cell in enumerate(row):
            if cell == "#":
                put(img, u + i, v + j, INK[1])
            elif cell == "+":
                put(img, u + i, v + j, ramp("wheat")[3])


ICON_POPULATION = [
    "..##....##..",
    ".####..####.",
    ".####..####.",
    "..##....##..",
    ".####..####.",
    "############",
    "############",
    "############",
    "............",
    "............",
    "............",
    "............",
]
ICON_EMPLOYED = [
    "....######..",
    "...#######..",
    "..########..",
    "..###..###..",
    "....##......",
    "...###......",
    "..###.......",
    ".###........",
    "###.........",
    "##..........",
    "............",
    "............",
]
ICON_FOOD = [
    "............",
    "....++......",
    "..######....",
    ".########...",
    "############",
    "############",
    ".##########.",
    "..########..",
    "............",
    "............",
    "............",
    "............",
]
ICON_RADIUS = [
    "...######...",
    "..#......#..",
    ".#........#.",
    "#..........#",
    "#....##....#",
    "#....##....#",
    "#..........#",
    ".#........#.",
    "..#......#..",
    "...######...",
    "............",
    "............",
]


def gen_hearth_screen():
    img = new_image(256, 256)
    rng = random.Random(201)
    W, H = 220, 222
    parchment_fill(img, 0, 0, W, H, rng)
    oak_frame(img, 0, 0, W, H, rng)
    title_band(img, 6, 6, W - 12, 16, rng)
    inset_panel(img, 10, 26, 90, 106, rng)
    groove(img, 14, 103, 80, 8)

    # Communal grid 6x4 at (104,30).
    for row in range(4):
        for col in range(6):
            slot_well(img, 104 + col * 18, 30 + row * 18)
    # Player inventory 9x3 at (29,140) + hotbar (29,198).
    for row in range(3):
        for col in range(9):
            slot_well(img, 29 + col * 18, 140 + row * 18)
    for col in range(9):
        slot_well(img, 29 + col * 18, 198)

    # Icon strip.
    icon(img, 224, 0, ICON_POPULATION)
    icon(img, 224, 16, ICON_EMPLOYED)
    icon(img, 224, 32, ICON_FOOD)
    icon(img, 224, 48, ICON_RADIUS)

    save(img, f"{ASSETS}/textures/gui/hearth_screen.png")


def gen_settler_card():
    img = new_image(256, 256)
    rng = random.Random(202)
    W, H = 176, 120
    parchment_fill(img, 0, 0, W, H, rng)
    oak_frame(img, 0, 0, W, H, rng, t=5)
    title_band(img, 5, 5, W - 10, 15, rng)
    # Bar grooves are blitted from a strip below the card region.
    groove(img, 0, 122, 96, 9)
    save(img, f"{ASSETS}/textures/gui/settler_card.png")


def gen_handbook():
    img = new_image(256, 256)
    rng = random.Random(203)
    W, H = 200, 176
    # Leather cover.
    for j in range(H):
        for i in range(W):
            r = rng.random()
            idx = 2 if r < 0.75 else (1 if r < 0.9 else 3)
            edge = min(i, j, W - 1 - i, H - 1 - j)
            if edge == 0:
                idx = 0
            elif edge < 2:
                idx = 1
            put(img, i, j, LEATHER[idx])
    # Page block inset.
    parchment_fill(img, 8, 8, W - 16, H - 16, rng)
    dark = shade(PARCH[0], 0.85)
    for i in range(8, W - 8):
        put(img, i, 8, dark)
    for j in range(8, H - 8):
        put(img, 8, j, dark)
    # Page curl lines bottom-right.
    for k in range(3):
        for i in range(10 + k * 4):
            put(img, W - 12 - i, H - 12 + k, shade(PARCH[1], 0.95))
    # Corner stitching on the cover.
    for (cx, cy) in ((3, 3), (W - 5, 3), (3, H - 5), (W - 5, H - 5)):
        put(img, cx, cy, LEATHER[4])
        put(img, cx + 1, cy + 1, LEATHER[4])
    save(img, f"{ASSETS}/textures/gui/handbook.png")


if __name__ == "__main__":
    gen_hearth_screen()
    gen_settler_card()
    gen_handbook()
    print("gui sheets done")
