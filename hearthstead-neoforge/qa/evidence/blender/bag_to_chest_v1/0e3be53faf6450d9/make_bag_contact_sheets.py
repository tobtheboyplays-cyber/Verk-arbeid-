"""Create review contact sheets for one content-addressed evidence package."""

from __future__ import annotations

import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


TICK_RE = re.compile(r"^(front34|left|right|back34)_tick(\d+)\.png$")


def main() -> None:
    root = Path(sys.argv[1]).resolve()
    font = ImageFont.load_default(size=18)
    title_font = ImageFont.load_default(size=24)
    for view in ("front34", "left", "right", "back34"):
        frames = []
        for path in root.glob(f"{view}_tick*.png"):
            match = TICK_RE.match(path.name)
            if match:
                frames.append((int(match.group(2)), path))
        frames.sort()
        if not frames:
            raise RuntimeError(f"No evidence frames for {view}")

        columns = 5
        thumb_size = 180
        label_height = 28
        margin = 18
        title_height = 48
        rows = (len(frames) + columns - 1) // columns
        sheet = Image.new("RGB", (
            margin * 2 + columns * thumb_size,
            title_height + margin + rows * (thumb_size + label_height) + margin,
        ), (20, 25, 22))
        draw = ImageDraw.Draw(sheet)
        draw.text((margin, 10), f"Hearthstead bag-to-chest — {view}",
                  fill=(232, 224, 194), font=title_font)
        for index, (tick, path) in enumerate(frames):
            row, column = divmod(index, columns)
            x = margin + column * thumb_size
            y = title_height + margin + row * (thumb_size + label_height)
            with Image.open(path) as image:
                thumb = image.convert("RGB")
                thumb.thumbnail((thumb_size, thumb_size), Image.Resampling.LANCZOS)
                sheet.paste(thumb, (x + (thumb_size - thumb.width) // 2,
                                    y + (thumb_size - thumb.height) // 2))
            draw.rectangle((x, y, x + thumb_size - 1, y + thumb_size - 1),
                           outline=(105, 119, 100), width=1)
            draw.text((x + 7, y + thumb_size + 4), f"tick {tick}",
                      fill=(232, 224, 194), font=font)
        sheet.save(root / f"contact_sheet_{view}.png", optimize=True)


if __name__ == "__main__":
    main()
