"""TRADER lane: the Trader's display-only props, drawn as original 16x16 pixel art.

    python tools/gen_trader_props.py

-> assets/hearthstead/textures/item/prop_ledger.png      (leather-bound account book, brass clasp)
-> assets/hearthstead/textures/item/prop_coin_purse.png  (drawstring leather pouch, a coin at the neck)
Hand-placed pixels only (no external art). Re-run to regenerate.
"""
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "main", "resources", "assets", "hearthstead", "textures", "item")

LEDGER = [
    "................",
    "...OOOOOOOOO....",
    "..OkhhhhhhhbO...",
    "..OkhLLLLLLbPO..",
    "..OkhLCCCCLbPO..",
    "..OkhLCLLCLbPO..",
    "..OkhLCCCCLbPO..",
    "..OkhLLLLLSSGO..",
    "..OkhLLLLLSSgO..",
    "..OkhLLLLLLbPO..",
    "..OkhLLLLLdbPO..",
    "..OkLLLLLddbPO..",
    "..OkddddddddpO..",
    "..OOPPPPPPPPpO..",
    "...OOOOOOOOOO...",
    "................",
]
LEDGER_PAL = {
    "O": (38, 22, 14, 255),     # outline
    "k": (70, 42, 24, 255),     # spine
    "L": (118, 74, 40, 255),    # brown leather cover
    "h": (156, 104, 58, 255),   # lit edge (top-left light)
    "d": (90, 56, 30, 255),     # shade
    "b": (98, 60, 32, 255),     # right board edge
    "C": (178, 132, 60, 255),   # tooled gilt frame
    "S": (62, 38, 22, 255),     # strap
    "s": (46, 28, 16, 255),     # strap end
    "G": (240, 204, 90, 255),   # brass buckle, lit
    "g": (168, 128, 40, 255),   # brass buckle, shade
    "P": (236, 224, 188, 255),  # page block
    "p": (196, 182, 146, 255),  # page shade
}

PURSE = [
    "................",
    "......OOO.......",
    ".....OYyyO......",
    "....OOyggOO.....",
    "...OhLOOOLdO....",
    "....OsssssOs....",
    "....OLhLLdO.s...",
    "...OhhLLLLdO....",
    "..OhhLLLLLLdO...",
    "..OhLLLLLLLdO...",
    ".OhLLLLLLLLLdO..",
    ".OhLLLLLLLLddO..",
    ".OLLLLLLLLLddO..",
    "..OLLLLLLLddO...",
    "...OOOOOOOOO....",
    "................",
]
PURSE_PAL = {
    "O": (46, 28, 16, 255),     # outline
    "L": (150, 100, 54, 255),   # leather
    "h": (192, 140, 82, 255),   # lit side
    "d": (104, 66, 34, 255),    # shade
    "s": (222, 204, 160, 255),  # drawstring
    "y": (232, 188, 60, 255),   # coin
    "Y": (255, 236, 150, 255),  # coin glint
    "g": (170, 126, 30, 255),   # coin shade
}


def draw(rows, pal, name):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        assert len(row) == 16, (name, y, len(row))
        for x, ch in enumerate(row):
            if ch != ".":
                img.putpixel((x, y), pal[ch])
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name + ".png")
    img.save(path)
    print("wrote", os.path.normpath(path))


draw(LEDGER, LEDGER_PAL, "prop_ledger")
draw(PURSE, PURSE_PAL, "prop_coin_purse")
