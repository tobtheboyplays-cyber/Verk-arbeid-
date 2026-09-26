#!/usr/bin/env python3
"""Extract the approved 2x1 post-raid doctrine emblem atlas."""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image


NAMES = ("sawyer_emblem", "scholar_emblem")


def reduce_icon(cell: Image.Image, name: str) -> Image.Image:
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
    return Image.merge("RGBA", (*rgb.split(), alpha))


def extract(source: Path, destination: Path, preview: Path | None) -> None:
    atlas = Image.open(source).convert("RGBA")
    if atlas.width % 2:
        raise SystemExit(f"atlas must divide into two equal cells, got {atlas.size}")
    cell_w = atlas.width // 2
    destination.mkdir(parents=True, exist_ok=True)
    icons: list[Image.Image] = []

    for index, name in enumerate(NAMES):
        cell = atlas.crop((index * cell_w, 0, (index + 1) * cell_w, atlas.height))
        icon = reduce_icon(cell, name)
        icon.save(destination / f"{name}.png", optimize=True)
        icons.append(icon)

    if preview is not None:
        preview.parent.mkdir(parents=True, exist_ok=True)
        scale = 10
        gap = 24
        sheet = Image.new("RGBA", (32 * scale * 2 + gap, 32 * scale),
                          (18, 15, 13, 255))
        for index, icon in enumerate(icons):
            enlarged = icon.resize((32 * scale, 32 * scale), Image.Resampling.NEAREST)
            sheet.alpha_composite(enlarged, (index * (32 * scale + gap), 0))
        sheet.save(preview, optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--preview", type=Path)
    args = parser.parse_args()
    extract(args.source, args.destination, args.preview)


if __name__ == "__main__":
    main()
