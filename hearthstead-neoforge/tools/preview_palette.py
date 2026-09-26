#!/usr/bin/env python3
"""Render deterministic B1 palette and material review sheets.

This is an offline checkpoint generator, not a source-asset generator.  It
deliberately renders the same tiny material tiles the game generators use so
palette changes can be reviewed before they are propagated into every PNG.
"""

from __future__ import annotations

import argparse
import colorsys
import hashlib
import json
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import texlib


RAW_SCALE = 3
INK = (230, 222, 202, 255)
MUTED = (159, 151, 135, 255)
PANEL = (24, 24, 28, 255)
PANEL_ALT = (34, 33, 35, 255)
SEPARATOR = (76, 70, 63, 255)


def _font():
    return ImageFont.load_default()


def _hex_rgb(value: str) -> tuple[int, int, int]:
    return texlib.hx(value)[:3]


def _metrics(values: list[str]) -> tuple[int, int]:
    hsv = [colorsys.rgb_to_hsv(*(channel / 255 for channel in _hex_rgb(value)))
           for value in values]
    value_span = round((hsv[-1][2] - hsv[0][2]) * 100)
    hue_drift = round((((hsv[-1][0] - hsv[0][0]) * 360 + 180) % 360) - 180)
    return value_span, hue_drift


def _upscale(image: Image.Image) -> Image.Image:
    return image.resize((image.width * RAW_SCALE, image.height * RAW_SCALE),
                        Image.Resampling.NEAREST)


def render_palette_atlas(label: str) -> Image.Image:
    row_h = 13
    width = 276
    height = 25 + len(texlib.PALETTES) * row_h + 8
    image = Image.new("RGBA", (width, height), PANEL)
    draw = ImageDraw.Draw(image)
    font = _font()

    draw.text((6, 5), f"HEARTHSTEAD B1 / {label.upper()}", fill=INK, font=font)
    draw.text((174, 5), "V span / H drift", fill=MUTED, font=font)
    for row, (name, values) in enumerate(texlib.PALETTES.items()):
        y = 20 + row * row_h
        if row % 2:
            draw.rectangle((0, y - 1, width - 1, y + row_h - 2), fill=PANEL_ALT)
        draw.text((6, y + 1), name, fill=INK, font=font)
        for index, value in enumerate(values):
            x = 76 + index * 19
            draw.rectangle((x, y, x + 17, y + 9), fill=texlib.hx(value))
        span, drift = _metrics(values)
        draw.text((174, y + 1), f"{span:>2} / {drift:+3}", fill=MUTED, font=font)

    return _upscale(image)


def _render_tile(kind: str, size: int = 16) -> Image.Image:
    image = texlib.new_image(size, size)
    rng = random.Random({"stone": 101, "wood": 3102, "metal": 3103,
                         "cloth": 3104}[kind])
    if kind == "stone":
        texlib.stone(image, 0, 0, size, size, texlib.ramp("stone"), rng)
    elif kind == "wood":
        painter = getattr(texlib, "wood_grain", texlib.planks)
        painter(image, 0, 0, size, size, texlib.ramp("oak"), rng)
    elif kind == "metal":
        if hasattr(texlib, "metal"):
            texlib.metal(image, 0, 0, size, size, texlib.ramp("iron"), rng,
                         axis="vertical", forged=True)
        else:
            colors = texlib.ramp("iron")
            for y in range(size):
                for x in range(size):
                    band = (0, 1, 4, 1)[min(3, x * 4 // size)]
                    texlib.put(image, x, y, colors[band])
    elif kind == "cloth":
        texlib.cloth(image, 0, 0, size, size, texlib.ramp("burgundy"), rng,
                     weave=0.22, v_grad=0.08)
        if hasattr(texlib, "fold"):
            texlib.fold(image, 8, 2, 12, texlib.ramp("burgundy"), vertical=True)
    return image


def _tile_3x3(tile: Image.Image) -> Image.Image:
    image = Image.new("RGBA", (tile.width * 3, tile.height * 3))
    for row in range(3):
        for column in range(3):
            image.paste(tile, (column * tile.width, row * tile.height))
    return image


def render_material_sheet(label: str) -> tuple[Image.Image, Image.Image]:
    kinds = ("stone", "wood", "metal", "cloth")
    tiles = {kind: _render_tile(kind) for kind in kinds}
    raw = Image.new("RGBA", (244, 88), PANEL)
    draw = ImageDraw.Draw(raw)
    font = _font()
    draw.text((6, 5), f"MATERIAL PRIMITIVES / {label.upper()}", fill=INK, font=font)

    for column, kind in enumerate(kinds):
        x = 7 + column * 59
        draw.text((x, 18), kind.upper(), fill=MUTED, font=font)
        tiled = _tile_3x3(tiles[kind])
        raw.paste(tiled, (x, 30))
        draw.rectangle((x - 1, 29, x + 48, 78), outline=SEPARATOR)

    stone = _upscale(_tile_3x3(tiles["stone"]))
    return _upscale(raw), stone


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def render_checkpoint(output_dir: Path, label: str) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    palette_path = output_dir / "palette_atlas.png"
    materials_path = output_dir / "materials_3x3.png"
    stone_path = output_dir / "stone_tile_3x3.png"
    render_palette_atlas(label).save(palette_path)
    materials, stone = render_material_sheet(label)
    materials.save(materials_path)
    stone.save(stone_path)

    manifest = {
        "label": label,
        "palette_count": len(texlib.PALETTES),
        "palettes": texlib.PALETTES,
        "files": {
            path.name: _sha256(path)
            for path in (palette_path, materials_path, stone_path)
        },
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def build_comparison(before_dir: Path, after_dir: Path, output: Path) -> None:
    pairs = (
        ("PALETTE ATLAS", "palette_atlas.png"),
        ("MATERIALS TILED 3x3", "materials_3x3.png"),
        ("STONE SEAM CHECK", "stone_tile_3x3.png"),
    )
    loaded = []
    for title, filename in pairs:
        before = Image.open(before_dir / filename).convert("RGBA")
        after = Image.open(after_dir / filename).convert("RGBA")
        loaded.append((title, before, after))

    margin = 24
    header_h = 54
    section_h = 34
    gap = 24
    width = max(before.width + after.width + margin * 3
                for _, before, after in loaded)
    height = header_h + sum(section_h + max(before.height, after.height) + gap
                            for _, before, after in loaded)
    sheet = Image.new("RGBA", (width, height), (18, 18, 21, 255))
    draw = ImageDraw.Draw(sheet)
    font = _font()
    draw.text((margin, 16), "HEARTHSTEAD B1 / BEFORE vs AFTER", fill=INK, font=font)
    draw.text((margin, 31), "left: legacy generator   right: premium palette foundation",
              fill=MUTED, font=font)
    y = header_h
    for title, before, after in loaded:
        draw.text((margin, y), title, fill=INK, font=font)
        draw.text((margin, y + 15), "BEFORE", fill=MUTED, font=font)
        draw.text((margin * 2 + before.width, y + 15), "AFTER", fill=MUTED, font=font)
        y += section_h
        sheet.paste(before, (margin, y))
        sheet.paste(after, (margin * 2 + before.width, y))
        y += max(before.height, after.height) + gap

    output.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(output)


def build_stone_comparison(legacy_texture: Path, output: Path) -> None:
    """Compare the committed hearth-stone tile to B1's seed-101 output."""
    before = Image.open(legacy_texture).convert("RGBA")
    if before.size != (16, 16):
        raise ValueError(f"legacy hearth stone must be 16x16, got {before.size}")
    after = _render_tile("stone")
    before_tiled = _upscale(_tile_3x3(before))
    after_tiled = _upscale(_tile_3x3(after))
    margin = 18
    header = 50
    width = before_tiled.width + after_tiled.width + margin * 3
    height = header + before_tiled.height + margin
    sheet = Image.new("RGBA", (width, height), (18, 18, 21, 255))
    draw = ImageDraw.Draw(sheet)
    font = _font()
    draw.text((margin, 10), "HEARTH_STONE / 3x3 SEAM PROOF", fill=INK, font=font)
    draw.text((margin, 27), "BEFORE: clipped courses", fill=MUTED, font=font)
    draw.text((margin * 2 + before_tiled.width, 27),
              "AFTER: exact 16-periodic wrap", fill=MUTED, font=font)
    sheet.paste(before_tiled, (margin, header))
    sheet.paste(after_tiled, (margin * 2 + before_tiled.width, header))
    output.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path,
                        help="Render the current palette checkpoint into this directory")
    parser.add_argument("--label", default="checkpoint")
    parser.add_argument("--compare", nargs=3, metavar=("BEFORE", "AFTER", "OUTPUT"),
                        help="Build a side-by-side sheet from two checkpoint directories")
    parser.add_argument("--stone-compare", nargs=2, metavar=("LEGACY_TEXTURE", "OUTPUT"),
                        help="Build the seed-101 hearth-stone seam proof")
    args = parser.parse_args()
    if args.stone_compare:
        build_stone_comparison(Path(args.stone_compare[0]), Path(args.stone_compare[1]))
        return
    if args.compare:
        build_comparison(Path(args.compare[0]), Path(args.compare[1]), Path(args.compare[2]))
        return
    if args.output_dir is None:
        parser.error("--output-dir is required unless --compare is used")
    render_checkpoint(args.output_dir, args.label)


if __name__ == "__main__":
    main()
