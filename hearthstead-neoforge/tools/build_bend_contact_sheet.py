#!/usr/bin/env python3
"""Build a labelled Candidate contact sheet from exact Blockbench frames.

The caller supplies files in semantic review order. This script does not
discover or approve frames: it only makes the already-selected evidence easy
to inspect side by side without changing its pixels beyond thumbnail scaling.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


BACKGROUND = (14, 18, 22)
PANEL = (24, 29, 35)
TEXT = (233, 238, 242)
ACCENT = (255, 181, 61)
CELL = (360, 290)
IMAGE_BOX = (348, 245)


def font(size: int):
    candidates = (
        Path("C:/Windows/Fonts/segoeui.ttf"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
    )
    for candidate in candidates:
        if candidate.is_file():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--title", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--columns", type=int, default=4)
    parser.add_argument("frames", nargs="+", type=Path)
    args = parser.parse_args()
    if args.columns < 1:
        parser.error("--columns must be positive")
    missing = [str(frame) for frame in args.frames if not frame.is_file()]
    if missing:
        parser.error("missing frame(s): " + ", ".join(missing))

    rows = (len(args.frames) + args.columns - 1) // args.columns
    header = 70
    footer = 34
    canvas = Image.new(
        "RGB", (args.columns * CELL[0], header + rows * CELL[1] + footer), BACKGROUND
    )
    draw = ImageDraw.Draw(canvas)
    draw.text((16, 12), args.title, fill=TEXT, font=font(24))
    draw.text(
        (16, 43),
        "CANDIDATE OFFLINE EVIDENCE — native Minecraft film remains the final gate",
        fill=ACCENT,
        font=font(14),
    )

    for index, frame_path in enumerate(args.frames):
        col = index % args.columns
        row = index // args.columns
        x = col * CELL[0]
        y = header + row * CELL[1]
        draw.rectangle((x + 4, y + 4, x + CELL[0] - 5, y + CELL[1] - 5), fill=PANEL)
        with Image.open(frame_path) as source:
            image = source.convert("RGB")
        image.thumbnail(IMAGE_BOX, Image.Resampling.LANCZOS)
        px = x + (CELL[0] - image.width) // 2
        py = y + 7
        canvas.paste(image, (px, py))
        label = f"{frame_path.parent.name} / {frame_path.stem}"
        draw.text((x + 10, y + 258), label[:52], fill=TEXT, font=font(13))

    draw.text(
        (16, canvas.height - 25),
        "Reject the full state on backward spine/weight, unsupported feet, clipping, or a false prop/contact.",
        fill=(178, 188, 197),
        font=font(13),
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(args.output, optimize=True)
    print(args.output.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
