#!/usr/bin/env python3
"""Extract the approved 3x2 emblem concept atlas into Minecraft textures."""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image


NAMES = (
    "lumberer_emblem",
    "farmer_emblem",
    "courier_emblem",
    "innkeeper_emblem",
    "guard_emblem",
    "archer_emblem",
)


def extract(source: Path, destination: Path) -> None:
    atlas = Image.open(source).convert("RGBA")
    if atlas.width % 3 or atlas.height % 2:
        raise SystemExit(f"atlas must divide into 3x2 cells, got {atlas.size}")
    cell_w = atlas.width // 3
    cell_h = atlas.height // 2
    destination.mkdir(parents=True, exist_ok=True)

    for index, name in enumerate(NAMES):
        column = index % 3
        row = index // 3
        cell = atlas.crop((column * cell_w, row * cell_h,
                           (column + 1) * cell_w, (row + 1) * cell_h))
        alpha_box = cell.getchannel("A").getbbox()
        if alpha_box is None:
            raise SystemExit(f"{name}: empty alpha cell")
        symbol = cell.crop(alpha_box)
        side = max(symbol.width, symbol.height)
        padded_side = max(side + 24, round(side * 1.08))
        padded = Image.new("RGBA", (padded_side, padded_side), (0, 0, 0, 0))
        padded.alpha_composite(symbol,
            ((padded_side - symbol.width) // 2, (padded_side - symbol.height) // 2))

        reduced = padded.resize((32, 32), Image.Resampling.LANCZOS)
        alpha = reduced.getchannel("A").point(lambda value: 255 if value >= 72 else 0)
        rgb = reduced.convert("RGB").quantize(colors=48,
            method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).convert("RGB")
        final = Image.merge("RGBA", (*rgb.split(), alpha))
        final.save(destination / f"{name}.png", optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    extract(args.source, args.destination)


if __name__ == "__main__":
    main()
