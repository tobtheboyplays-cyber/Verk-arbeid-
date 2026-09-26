#!/usr/bin/env python3
"""Downsample the three divergent Hearth IA concepts for visual selection.

This is deliberately concept-only. It does not create runtime textures or
touch Java. Exact slot frames are re-authored only after a structure is chosen;
the immutable menu coordinate contract is recorded beside the outputs.
"""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageEnhance, ImageFont


ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "tools/ui/art_direction/hearth_ia_divergence"
OUT = ROOT / "build/ui_art_direction/hearth_ia_divergence"
FONT = ImageFont.load_default(size=11)
TITLE = ImageFont.load_default(size=14)

CONCEPTS = (
    ("A", "THE HEARTH CODEX", "concept_a_hearth_codex_source.png",
     "Side bookmarks  |  spine counters  |  left-page order"),
    ("B", "SURVEYOR'S ROLL-TOP", "concept_b_surveyor_rolltop_source.png",
     "Rotary chapter wheel  |  one abacus  |  wide scroll"),
    ("C", "EMBERKEEPER'S FOLIO", "concept_c_emberkeeper_folio_source.png",
     "Stamped chapter seals  |  census medallion  |  layered folds"),
)


def thumbnail(path):
    with Image.open(path) as source:
        image = source.convert("RGB").resize((427, 240), Image.Resampling.BOX)
    image = ImageEnhance.Contrast(image).enhance(1.04)
    return image.quantize(colors=192, method=Image.Quantize.MEDIANCUT,
                          dither=Image.Dither.NONE).convert("RGB")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    board = Image.new("RGB", (1321, 300), (14, 11, 8))
    draw = ImageDraw.Draw(board)
    for index, (letter, name, filename, note) in enumerate(CONCEPTS):
        image = thumbnail(SOURCE / filename)
        image.save(OUT / filename.replace("_source", "_427x240"))
        x = 8 + index * 439
        board.paste(image, (x, 28))
        draw.text((x, 7), f"{letter}  {name}", font=TITLE,
                  fill=(241, 214, 161))
        draw.text((x, 274), note, font=FONT, fill=(190, 168, 129))
    board.save(OUT / "hearth_ia_three_way_selection.png")
    contract = {
        "status": "concept_selection_only_no_runtime_integration",
        "immutable_interactive_core": [320, 220],
        "communal_slots": {"indices": [0, 23], "grid": [6, 4],
                             "frame_origin": [106, 42]},
        "player_inventory": {"indices": [24, 50], "grid": [9, 3],
                               "frame_origin": [79, 142]},
        "hotbar": {"indices": [51, 59], "grid": [9, 1],
                   "frame_origin": [79, 200]},
        "selection_rule": "Choose structure first; re-author exact slot wells around immutable coordinates afterward.",
    }
    (OUT / "runtime_feasibility_contract.json").write_text(
        json.dumps(contract, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
