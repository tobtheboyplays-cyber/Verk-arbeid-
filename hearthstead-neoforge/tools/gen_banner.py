#!/usr/bin/env python3
"""Settlement Banner set (block id hearthstead:hearth): the ledger counter,
the gallows pole and arm drawn by SettlementBannerRenderer, and the item icon.
Matches COORD/refs/block-banner-stand.png as far as 16px block art allows.
All deterministic pixel art from texlib's shared palettes."""
import os
import random
import sys

sys.path.insert(0, os.path.dirname(__file__))
from texlib import (ramp, shade, mix, new_image, fill, put, save, planks,
                    metal, cloth, outline_rect)

ASSETS = os.path.join(os.path.dirname(__file__), "..",
                      "src/main/resources/assets/hearthstead")
BLOCK = f"{ASSETS}/textures/block"


def gen_stand_wood():
    """Counter sides: horizontal oak boards inside a darker frame."""
    img = new_image(16, 16)
    oak = ramp("oak")
    planks(img, 0, 0, 16, 16, oak, random.Random(701), board=5)
    carved = ramp("oak_carved")
    for i in range(16):
        for j in (0, 15):
            put(img, i, j, carved[2])
            put(img, j, i, carved[2])
        put(img, i, 1, carved[3])
        put(img, 1, i, shade(oak[3], 1.05))
    save(img, f"{BLOCK}/banner_stand_wood.png")


def gen_stand_top():
    """Counter top: oak boards running front to back, worn lighter lip."""
    img = new_image(16, 16)
    oak = ramp("oak")
    planks(img, 0, 0, 16, 16, oak, random.Random(702), vertical=True, board=4)
    outline_rect(img, 0, 0, 16, 16, ramp("oak_carved")[2])
    for i in range(1, 15):
        put(img, i, 1, shade(oak[4], 0.95))
    save(img, f"{BLOCK}/banner_stand_top.png")


def gen_pole():
    """Pole, arm and brace: dark vertical oak grain."""
    img = new_image(16, 16)
    planks(img, 0, 0, 16, 16, ramp("oak_carved"), random.Random(705), vertical=True, board=4)
    save(img, f"{BLOCK}/banner_pole.png")


def gen_pole_end():
    """End grain for the pole cap and arm tip."""
    img = new_image(16, 16)
    carved = ramp("oak_carved")
    oak = ramp("oak")
    for y in range(16):
        for x in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5))
            put(img, x, y, oak[3] if int(d) % 3 == 0 else carved[3])
    outline_rect(img, 0, 0, 16, 16, carved[1])
    save(img, f"{BLOCK}/banner_pole_end.png")


def gen_iron():
    """Iron strap with two rivets per band."""
    img = new_image(16, 16)
    iron = ramp("iron_forged")
    metal(img, 0, 0, 16, 16, iron, random.Random(706), axis="horizontal", forged=True)
    for x in (2, 6, 10, 13):
        for y in (4, 11):
            put(img, x, y, iron[4])
            put(img, x + 1, y + 1, iron[0])
    save(img, f"{BLOCK}/banner_iron.png")


def gen_brass():
    """Brass lantern frame and fittings."""
    img = new_image(16, 16)
    metal(img, 0, 0, 16, 16, ramp("brass"), random.Random(707), axis="horizontal")
    save(img, f"{BLOCK}/banner_brass.png")


def gen_lantern():
    """Lantern glass: brass corner posts, warm candle glow."""
    img = new_image(16, 16)
    brass = ramp("brass")
    amber = ramp("amber")
    parchment = ramp("parchment")
    for y in range(16):
        for x in range(16):
            t = abs(x - 7.5) / 7.5
            put(img, x, y, mix(amber[4], amber[2], t * 0.8))
    fill(img, 0, 0, 2, 16, brass[2])
    fill(img, 14, 0, 2, 16, brass[1])
    fill(img, 0, 0, 16, 2, brass[3])
    fill(img, 0, 14, 16, 2, brass[1])
    fill(img, 6, 7, 4, 7, parchment[4])       # candle
    fill(img, 7, 4, 2, 3, amber[4])           # flame
    put(img, 7, 3, parchment[4])
    save(img, f"{BLOCK}/banner_lantern.png")


def gen_runner():
    """Table runner: burgundy cloth, gold edges, pointed hem (cut out)."""
    img = new_image(16, 16)
    burgundy = ramp("burgundy")
    wheat = ramp("wheat")
    cloth(img, 0, 0, 16, 16, burgundy, random.Random(708), base_idx=2, weave=0.25)
    for y in range(16):
        put(img, 1, y, wheat[3])
        put(img, 14, y, wheat[3])
    for y in range(11, 16):
        cut = y - 10
        for x in range(16):
            if x < cut + 1 or x > 14 - cut:
                put(img, x, y, (0, 0, 0, 0))
            elif x == cut + 1 or x == 14 - cut:
                put(img, x, y, wheat[3])
    save(img, f"{BLOCK}/banner_runner.png")


def gen_book():
    """Closed ledger: red leather boards with a brass-buckled strap."""
    img = new_image(16, 16)
    crimson = ramp("crimson")
    wheat = ramp("wheat")
    cloth(img, 0, 0, 16, 16, crimson, random.Random(709), base_idx=1, weave=0.2, v_grad=0.1)
    outline_rect(img, 0, 0, 16, 16, crimson[0])
    fill(img, 0, 7, 16, 2, ramp("leather")[2])
    fill(img, 7, 0, 2, 16, ramp("leather")[2])
    fill(img, 6, 6, 4, 4, wheat[3])
    save(img, f"{BLOCK}/banner_book.png")


def gen_pages():
    """Page edges seen on the book's sides."""
    img = new_image(16, 16)
    parchment = ramp("parchment")
    for y in range(16):
        for x in range(16):
            put(img, x, y, parchment[3] if y % 2 else parchment[2])
    outline_rect(img, 0, 0, 16, 16, ramp("crimson")[1])
    save(img, f"{BLOCK}/banner_pages.png")


def gen_pouch():
    """Coin pouch leather with a drawstring."""
    img = new_image(16, 16)
    leather = ramp("leather")
    cloth(img, 0, 0, 16, 16, leather, random.Random(710), base_idx=3, weave=0.3, v_grad=0.3)
    fill(img, 0, 4, 16, 1, leather[0])
    put(img, 8, 5, ramp("wheat")[3])
    save(img, f"{BLOCK}/banner_pouch.png")


def gen_coin():
    """Gold coins: rimmed face, milled edge."""
    img = new_image(16, 16)
    brass = ramp("brass")
    wheat = ramp("wheat")
    fill(img, 0, 0, 16, 16, brass[2])
    for y in range(16):
        for x in range(16):
            if (x + y) % 3 == 0:
                put(img, x, y, brass[1])
    fill(img, 3, 3, 10, 10, wheat[3])
    outline_rect(img, 3, 3, 10, 10, brass[3])
    fill(img, 6, 6, 4, 4, wheat[4])
    save(img, f"{BLOCK}/banner_coin.png")


def draw_banner_cloth(img, x, y, w, h, rng):
    """Bannerhold's own colours: burgundy field, gold bordure and lozenge,
    red roundel. Used by the item icon and the logo emblem."""
    burgundy = ramp("burgundy")
    crimson = ramp("crimson")
    wheat = ramp("wheat")
    cloth(img, x, y, w, h, burgundy, rng, base_idx=2, weave=0.25, v_grad=0.25)
    b = max(1, w // 10)
    for j in range(h):
        for i in range(w):
            if i < b or i >= w - b or j >= h - b:
                put(img, x + i, y + j, wheat[3 if (i + j) % 4 else 2])
    cx = x + (w - 1) / 2.0
    cy = y + (h - 1) / 2.0
    rx = w * 0.30
    ry = h * 0.38
    for j in range(h):
        for i in range(w):
            px, py = x + i, y + j
            d = abs(px - cx) / rx + abs(py - cy) / ry
            if d <= 1.0:
                put(img, px, py, wheat[4] if d > 0.82 else wheat[3])
            if d <= 0.42:
                put(img, px, py, crimson[2] if d > 0.2 else crimson[3])


def gen_item_icon():
    """Front view: gallows pole on the left, banner under its arm, counter below."""
    img = new_image(16, 16)
    carved = ramp("oak_carved")
    oak = ramp("oak")
    iron = ramp("iron_forged")
    amber = ramp("amber")
    burgundy = ramp("burgundy")
    # pole (left) and arm
    for yy in range(0, 14):
        put(img, 1, yy, carved[3])
        put(img, 2, yy, carved[2])
    for xx in range(1, 15):
        put(img, xx, 1, carved[3])
        put(img, xx, 2, carved[2])
    put(img, 3, 3, carved[2])
    put(img, 4, 3, carved[1])
    put(img, 3, 4, carved[1])
    for xx in (1, 2):
        put(img, xx, 1, iron[2])
        put(img, xx, 11, iron[2])
    # cloth under the arm
    draw_banner_cloth(img, 6, 3, 7, 7, random.Random(711))
    put(img, 7, 3, iron[3])
    put(img, 11, 3, iron[3])
    # counter with runner, book and lantern
    for xx in range(3, 16):
        put(img, xx, 11, oak[4])
        for yy in range(12, 16):
            put(img, xx, yy, oak[3] if yy % 2 else oak[2])
    for yy in range(12, 15):
        put(img, 8, yy, burgundy[2])
        put(img, 9, yy, burgundy[2])
    put(img, 4, 10, ramp("crimson")[2])
    put(img, 5, 10, ramp("crimson")[2])
    put(img, 13, 9, ramp("brass")[3])
    put(img, 13, 10, amber[4])
    put(img, 3, 15, iron[2])
    put(img, 15, 15, iron[2])
    save(img, f"{ASSETS}/textures/item/settlement_banner.png")


if __name__ == "__main__":
    gen_stand_wood()
    gen_stand_top()
    gen_pole()
    gen_pole_end()
    gen_iron()
    gen_brass()
    gen_lantern()
    gen_runner()
    gen_book()
    gen_pages()
    gen_pouch()
    gen_coin()
    gen_item_icon()
    print("banner set done")
