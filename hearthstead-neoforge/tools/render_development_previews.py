#!/usr/bin/env python3
"""Render honest, boot-free previews of the Hearth tree and Mayor shop.

The preview reuses the mod's generated nine-slice sprites, colour tokens and
Minecraft font. It is an overview for early art-direction review, not a claim
that the client has been booted.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import mcfont  # noqa: E402
from ui_preview import Canvas, Sprites, SPRITES, WARNINGS, argb, colour  # noqa: E402


ITEM_TEXTURES = (HERE.parent / "src/main/resources/assets/hearthstead/textures/item")

NODE_W = 146
NODE_H = 54
WORLD_X = {
    "Settlement Charter": 0,
    "First Fire": 180,
    "Lumber Camp": 360,
    "Warehouse": 540,
    "Cultivated Ground": 720,
    "Home": 900,
    "Tavern": 1080,
    "First Watch": 1260,
    "Arm the Watch": 1440,
    "First Raid Aftermath": 1620,
    "Shield Doctrine": 1810,
    "Guild Doctrine": 1810,
    "Hearth Doctrine": 1810,
    "Fortification": 2000,
    "Border Wardens": 2000,
    "Land and Harvest": 2000,
    "Craft and Industry": 2000,
    "Hall and Learning": 2000,
}
WORLD_Y = {
    "Shield Doctrine": 0,
    "Guild Doctrine": 80,
    "Hearth Doctrine": 160,
    "Fortification": -40,
    "Border Wardens": 24,
    "Land and Harvest": 88,
    "Craft and Industry": 152,
    "Hall and Learning": 216,
}

QUESTS = {
    "Settlement Charter": "Quest: free tutorial",
    "First Fire": "Quest: free tutorial",
    "Lumber Camp": "Quest: Mayor 1/1",
    "Warehouse": "Quest: Lumberer log 0/1",
    "Cultivated Ground": "Quest: Courier trip 0/1",
    "Home": "Quest: Farmer crop 0/1",
    "Tavern": "Quest: housed 0/3",
    "First Watch": "Quest: housed 0/4",
    "Arm the Watch": "Quest: Guard weapon 0/1",
    "First Raid Aftermath": "Quest: 0/1",
    "Shield Doctrine": "Quest: 0/40",
    "Guild Doctrine": "Quest: 0/64 + 0/3",
    "Hearth Doctrine": "Quest: 0/20m + 1/5",
    "Fortification": "Quest: planned",
    "Border Wardens": "Quest: planned",
    "Land and Harvest": "Quest: planned",
    "Craft and Industry": "Quest: planned",
    "Hall and Learning": "Quest: planned",
}

COSTS = {
    "Settlement Charter": "Cost: milestone only",
    "First Fire": "Cost: milestone only",
    "Lumber Camp": "Cost: 8 logs + 8 cobble",
    "Warehouse": "Cost: 8 logs + 2 leather",
    "Cultivated Ground": "Cost: 8 seeds + 4 logs",
    "Home": "Cost: 12 logs + 8 cobble",
    "Tavern": "Cost: 8 bread + 2 leather",
    "First Watch": "Cost: 8 iron + 8 logs",
    "Arm the Watch": "Cost: milestone only",
    "First Raid Aftermath": "Cost: milestone only",
    "Shield Doctrine": "Cost: 16 iron + 4 leather",
    "Guild Doctrine": "Cost: 24 logs + 8 iron",
    "Hearth Doctrine": "Cost: 4 books + 8 bread",
    "Fortification": "Cost: planned",
    "Border Wardens": "Cost: planned",
    "Land and Harvest": "Cost: planned",
    "Craft and Industry": "Cost: planned",
    "Hall and Learning": "Cost: planned",
}

ICONS = {
    "Shield Doctrine": "archer_emblem.png",
    "Guild Doctrine": "sawyer_emblem.png",
    "Hearth Doctrine": "scholar_emblem.png",
}

EDGES = (
    ("Settlement Charter", "First Fire"),
    ("First Fire", "Lumber Camp"),
    ("Lumber Camp", "Warehouse"),
    ("Warehouse", "Cultivated Ground"),
    ("Cultivated Ground", "Home"),
    ("Home", "Tavern"),
    ("Tavern", "First Watch"),
    ("First Watch", "Arm the Watch"),
    ("Arm the Watch", "First Raid Aftermath"),
    ("First Watch", "First Raid Aftermath"),
    ("First Raid Aftermath", "Shield Doctrine"),
    ("First Raid Aftermath", "Guild Doctrine"),
    ("First Raid Aftermath", "Hearth Doctrine"),
    ("Shield Doctrine", "Fortification"),
    ("Shield Doctrine", "Border Wardens"),
    ("Guild Doctrine", "Land and Harvest"),
    ("Guild Doctrine", "Craft and Industry"),
    ("Hearth Doctrine", "Hall and Learning"),
)


def item_icon(canvas: Canvas, texture: str, x: int, y: int) -> None:
    icon = Image.open(ITEM_TEXTURES / texture).convert("RGBA")
    icon = icon.resize((16, 16), Image.Resampling.NEAREST)
    canvas.img.alpha_composite(icon, (x, y))


def node_xy(name: str) -> tuple[int, int]:
    return 30 + WORLD_X[name], 105 + WORLD_Y.get(name, 80)


def line_h(canvas: Canvas, x1: int, x2: int, y: int, shade) -> None:
    canvas.rect(min(x1, x2), y, abs(x2 - x1) + 1, 2, shade)


def line_v(canvas: Canvas, x: int, y1: int, y2: int, shade) -> None:
    canvas.rect(x, min(y1, y2), 2, abs(y2 - y1) + 1, shade)


def render_tree() -> Image.Image:
    font = mcfont.McFont.load()
    sprites = Sprites(SPRITES)
    # A stitched overview of the same pannable world coordinates used by the
    # in-game screen. The running client shows a viewport into this map.
    canvas = Canvas(2210, 506, font, sprites)
    sprites.draw(canvas.img, "panel/window", 8, 8, 2194, 490)
    canvas.text(24, 21, "Settlement Development", colour("text_strong"), box=300)
    canvas.text(24, 38,
                "HEARTH • drag/arrows pan • Ctrl+wheel zooms • R refreshes server progress",
                colour("text_muted"), box=700)
    canvas.text(2180, 21, "CANDIDATE • NOT IN-GAME APPROVAL", colour("text_muted"),
                align="right")
    sprites.draw(canvas.img, "panel/inset", 20, 58, 2170, 330)

    for start, end in EDGES:
        sx, sy = node_xy(start)
        ex, ey = node_xy(end)
        from_x = sx + NODE_W
        from_y = sy + NODE_H // 2
        to_x = ex
        to_y = ey + NODE_H // 2
        mid_x = from_x + (to_x - from_x) // 2
        shade = colour("accent") if "Doctrine" in end else colour("good")
        if end in ("Fortification", "Border Wardens", "Land and Harvest",
                   "Craft and Industry", "Hall and Learning"):
            shade = argb(0xFF695978)
        line_h(canvas, from_x, mid_x, from_y, shade)
        line_v(canvas, mid_x, from_y, to_y, shade)
        line_h(canvas, mid_x, to_x, to_y, shade)

    for name in WORLD_X:
        x, y = node_xy(name)
        future = name in ("Fortification", "Border Wardens", "Land and Harvest",
                          "Craft and Industry", "Hall and Learning")
        doctrine = "Doctrine" in name
        outline = argb(0xFF8E789C) if future else (
            colour("accent") if doctrine else colour("good"))
        sprites.draw(canvas.img, "panel/card_hover" if name == "Warehouse"
                     else "panel/card", x, y, NODE_W, NODE_H)
        canvas.rect(x, y, NODE_W, 2, outline)
        canvas.rect(x, y + NODE_H - 2, NODE_W, 2, outline)
        texture = ICONS.get(name, "build_plan.png")
        item_icon(canvas, texture, x + 5, y + 5)
        canvas.text(x + 25, y + 5, name,
                    colour("text_strong"), box=NODE_W - 30, label=name)
        canvas.text(x + 6, y + 21, QUESTS[name],
                    colour("text_muted"), box=NODE_W - 12,
                    label=f"{name} quest")
        canvas.text(x + 6, y + 31, COSTS[name],
                    colour("text_muted"), box=NODE_W - 12,
                    label=f"{name} cost")
        state = "PLANNED" if future else ("LOCKED" if doctrine else (
            "AVAILABLE" if name == "Lumber Camp" else (
                "LEARNED" if name in ("Settlement Charter", "First Fire") else "LOCKED")))
        canvas.text(x + 6, y + NODE_H - 12, state, outline,
                    box=NODE_W - 12)

    sprites.draw(canvas.img, "widget/divider", 20, 397, 2170, 2)
    sprites.draw(canvas.img, "panel/card_hover", 24, 408, 1370, 67)
    canvas.text(34, 417, "HOVER / KEYBOARD FOCUS — Warehouse",
                colour("text_strong"), box=500)
    canvas.text(34, 432,
                "Store one real Lumberer log at the linked Lumber Camp: 0/1 • Cost: 8 logs + 2 leather",
                colour("text_muted"), box=1330)
    canvas.text(34, 447,
                "Reward: learn the Warehouse plan and unlock Courier Emblems • Locked until the physical quest passes",
                colour("accent"), box=1330)
    canvas.text(1425, 420,
                "Common trunk → physical work loops → first raid → one permanent doctrine",
                colour("accent"), box=740)
    canvas.text(1425, 440,
                "Descriptions, quest progress, exact cost and reward stay visible in the inspector.",
                colour("text_muted"), box=740)
    return canvas.img


def draw_button(canvas: Canvas, sprites: Sprites, x: int, y: int,
                label: str, active: bool) -> None:
    state = "idle" if active else "disabled"
    sprites.draw(canvas.img, f"widget/button_{state}", x, y, 64, 20)
    canvas.text(x + 32, y + 7, label,
                colour("text" if active else "text_muted"),
                align="center", box=56)


def render_shop() -> Image.Image:
    font = mcfont.McFont.load()
    sprites = Sprites(SPRITES)
    canvas = Canvas(354, 264, font, sprites)
    sprites.draw(canvas.img, "panel/window", 0, 0, 354, 264)
    canvas.text(177, 12, "The Mayor's Job Emblems",
                colour("text_strong"), align="center", box=320)
    canvas.text(10, 29, "Physical authorizations • payment from the Hearth",
                colour("text_muted"), box=334)
    sprites.draw(canvas.img, "widget/divider", 10, 44, 334, 2)

    rows = (
        ("Guard Emblem", "guard_emblem.png", "4 iron + 2 leather", True),
        ("Archer Emblem", "archer_emblem.png", "12 arrows + 2 leather", False),
        ("Sawyer Emblem", "sawyer_emblem.png", "2 iron + 2 leather", True),
        ("Scholar Emblem", "scholar_emblem.png", "2 books + 2 leather", False),
    )
    for index, (name, texture, cost, active) in enumerate(rows):
        y = 52 + index * 43
        sprites.draw(canvas.img, "panel/card_hover" if index == 2 else "panel/card",
                     10, y, 334, 39)
        item_icon(canvas, texture, 17, y + 6)
        canvas.text(39, y + 6, name, colour("text_strong"), box=222)
        canvas.text(39, y + 21, cost,
                    colour("good" if active else "text_muted"), box=222)
        draw_button(canvas, sprites, 280, y + 9, "Buy", active)

    sprites.draw(canvas.img, "widget/scroll_track", 349, 52, 5, 168)
    sprites.draw(canvas.img, "widget/scroll_thumb", 349, 132, 5, 84)
    sprites.draw(canvas.img, "widget/divider", 10, 226, 334, 2)
    canvas.text(10, 235, "Guild Doctrine active • other doctrines are locked",
                colour("accent"), box=255)
    draw_button(canvas, sprites, 280, 234, "Close", True)
    return canvas.img


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)
    WARNINGS.clear()
    render_tree().save(args.out_dir / "development_tree_overview_en.png",
                       optimize=True)
    shop = render_shop().resize((1062, 792), Image.Resampling.NEAREST)
    shop.save(args.out_dir / "mayor_emblem_shop_doctrines_en.png", optimize=True)
    if WARNINGS:
        raise SystemExit("preview overflow:\n" + "\n".join(WARNINGS))
    print(args.out_dir / "development_tree_overview_en.png")
    print(args.out_dir / "mayor_emblem_shop_doctrines_en.png")


if __name__ == "__main__":
    main()
