#!/usr/bin/env python3
"""Deterministic offline layout evidence for DevelopmentScreen's survey identity.

This is not an in-game approval. It mirrors the Java geometry at the two
important logical sizes and rejects escaped landmarks or a clipped detail sheet.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
sys.path.insert(0, str(HERE))
import mcfont  # noqa: E402

OUT = PROJECT / "build" / "ui-overhaul" / "development-survey"
ITEMS = PROJECT / "src/main/resources/assets/hearthstead/textures/item"

PANEL_MAX = (854, 480)
VIEW_X = 12
VIEW_Y = 44
FOOTER = 28
def rgba(value: str) -> tuple[int, int, int, int]:
    raw = value.removeprefix("#")
    if len(raw) == 6:
        raw += "FF"
    return tuple(int(raw[index:index + 2], 16) for index in range(0, 8, 2))  # type: ignore[return-value]


P = {key: rgba(value) for key, value in {
    "shadow": "#000000A0", "oak_dark": "#26180F", "oak": "#5A3A22",
    "oak_light": "#825D35", "brass_dark": "#765623", "brass": "#C5A45B",
    "paper_dark": "#9A7C4B", "paper": "#D7C394", "paper_light": "#E7D5A8",
    "ink": "#2B2117", "muted": "#65513A", "learned": "#4D6A43",
    "available": "#9B5C2B", "locked": "#88704C", "white": "#F4E5BE",
}.items()}

WORLD = {
    "01": (30, 270, "hearthward_seal.png"),
    "02": (120, 255, "hearthward_seal.png"),
    "03": (217, 270, "lumberer_emblem.png"),
    "04": (315, 250, "courier_emblem.png"),
    "05": (412, 270, "farmer_emblem.png"),
    "06": (510, 245, "build_plan.png"),
    "07": (600, 215, "innkeeper_emblem.png"),
    "08": (697, 175, "guard_emblem.png"),
    "09": (600, 135, "archer_emblem.png"),
    "10": (480, 95, "guard_emblem.png"),
    "11": (180, 40, "warden_oath_seal.png"),
    "12": (480, 35, "timber_beam.png"),
    "13": (780, 40, "hearthward_seal.png"),
    "14": (82, -10, "guard_emblem.png"),
    "15": (270, -10, "archer_emblem.png"),
    "16": (405, -20, "farmer_emblem.png"),
    "17": (570, -10, "iron_bloom.png"),
    "18": (780, -20, "scholar_emblem.png"),
}

EDGES = tuple((f"{index:02d}", f"{index + 1:02d}") for index in range(1, 10)) + (
    ("10", "11"), ("10", "12"), ("10", "13"),
    ("11", "14"), ("11", "15"), ("12", "16"),
    ("12", "17"), ("13", "18"),
)


def rect(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], colour) -> None:
    draw.rectangle(box, fill=colour)


def panel_size(logical: tuple[int, int]) -> tuple[int, int]:
    return min(PANEL_MAX[0], logical[0] - 16), min(PANEL_MAX[1], logical[1] - 16)


def zoom_for(width: int) -> float:
    return 0.86 if width >= 760 else 0.56 if width >= 560 else 0.42


def octagon(draw: ImageDraw.ImageDraw, x: int, y: int, size: int, colour) -> None:
    cut = max(2, size // 5)
    draw.polygon(((x + cut, y), (x + size - cut, y), (x + size, y + cut),
                  (x + size, y + size - cut), (x + size - cut, y + size),
                  (x + cut, y + size), (x, y + size - cut), (x, y + cut)),
                 fill=colour)


def icon(image: Image.Image, name: str, x: int, y: int) -> None:
    source = Image.open(ITEMS / name).convert("RGBA").resize((16, 16), Image.Resampling.NEAREST)
    image.alpha_composite(source, (x, y))


def wrap(font: mcfont.McFont, value: str, width: int) -> list[str]:
    lines: list[str] = []
    current = ""
    for word in value.split():
        candidate = word if not current else f"{current} {word}"
        if font.width(candidate) <= width:
            current = candidate
        else:
            if current:
                lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def render(logical: tuple[int, int], selected: bool) -> tuple[Image.Image, dict[str, object]]:
    font = mcfont.McFont.load()
    image = Image.new("RGBA", logical, "#11100E")
    draw = ImageDraw.Draw(image, "RGBA")
    panel_w, panel_h = panel_size(logical)
    left, top = (logical[0] - panel_w) // 2, (logical[1] - panel_h) // 2
    right, bottom = left + panel_w, top + panel_h
    view_w, view_h = panel_w - 2 * VIEW_X, panel_h - VIEW_Y - FOOTER
    vx, vy = left + VIEW_X, top + VIEW_Y
    rect(draw, (left + 4, top + 5, right + 4, bottom + 5), P["shadow"])
    rect(draw, (left, top, right, bottom), P["oak_dark"])
    rect(draw, (left + 2, top + 2, right - 2, bottom - 2), P["oak"])
    rect(draw, (left + 4, top + 4, right - 4, top + VIEW_Y - 3), P["oak_dark"])
    rect(draw, (left + 6, top + 6, right - 6, top + 7), P["oak_light"])
    rect(draw, (vx - 2, vy - 2, vx + view_w + 2, vy + view_h + 2), P["brass_dark"])
    rect(draw, (vx, vy, vx + view_w, vy + view_h), P["paper"])
    font.draw(image, left + 10, top + 12, "SETTLEMENT SURVEY", P["white"], shadow=True)
    font.draw(image, left + 10, top + 31, "Drag to pan  •  Ctrl+wheel to zoom", rgba("#CCB889"), shadow=True)

    zoom = zoom_for(panel_w)
    node_size = max(18, round(48 * zoom)) if zoom < 0.56 else max(32, round(48 * zoom))
    points: dict[str, tuple[int, int]] = {}
    for key, (wx, wy, _icon) in WORLD.items():
        points[key] = (vx + round((wx + 45) * zoom), vy + round((wy + 20) * zoom))
    for source, target in EDGES:
        a, b = points[source], points[target]
        tone = P["learned"] if int(target) <= 2 else P["available"] if target == "03" else P["locked"]
        draw.line((a[0] + node_size // 2, a[1] + node_size // 2,
                   b[0] + node_size // 2, b[1] + node_size // 2),
                  fill=rgba("#38271888"), width=4)
        draw.line((a[0] + node_size // 2, a[1] + node_size // 2,
                   b[0] + node_size // 2, b[1] + node_size // 2),
                  fill=tone, width=1)
    escaped: list[str] = []
    for key, (x, y) in points.items():
        tone = P["learned"] if int(key) <= 2 else P["available"] if key == "03" else P["locked"]
        if x < vx or y < vy or x + node_size > vx + view_w or y + node_size > vy + view_h:
            escaped.append(key)
        octagon(draw, x + 2, y + 3, node_size, rgba("#00000055"))
        octagon(draw, x, y, node_size, tone)
        octagon(draw, x + 2, y + 2, node_size - 4, P["paper_light"])
        icon(image, WORLD[key][2], x + (node_size - 16) // 2, y + (node_size - 16) // 2)
        rect(draw, (x - 1, y - 1, x + 10, y + 9), P["oak_dark"])
        rect(draw, (x, y, x + 9, y + 8), P["paper_light"])
        font.draw(image, x + 1, y, str(int(key)), P["ink"], shadow=False)

    detail = None
    if selected:
        h = 80 if view_h >= 132 else 54
        detail = (vx + 6, vy + view_h - h - 6, vx + view_w - 6, vy + view_h - 6)
        x1, y1, x2, y2 = detail
        rect(draw, (x1 + 2, y1 + 3, x2 + 2, y2 + 3), rgba("#00000066"))
        rect(draw, detail, P["paper_dark"])
        rect(draw, (x1 + 2, y1 + 2, x2 - 2, y2 - 2), P["paper_light"])
        rect(draw, (x1 + 2, y1 + 2, x1 + 5, y2 - 2), P["available"])
        lines = (
            ("LUMBER CAMP", P["ink"]), ("AVAILABLE", P["available"]),
            ("Needs: First Fire", P["muted"]), ("Foundation ready 1/1", P["available"]),
            ("Cost: 8 Any Log + 8 Cobblestone", P["ink"]),
            ("Plan: Lumber Camp", P["learned"]),
            ("Lumberer: fells trees; logs fill the chest", P["muted"]),
            ("Emblem: Lumberer", P["learned"]),
            ("Recipe appears here after research", P["ink"]),
        )
        columns = 3 if x2 - x1 >= 540 else 2 if x2 - x1 >= 260 else 1
        rows = max(1, (h - 14) // 10)
        col_w = ((x2 - x1) - 16 - (columns - 1) * 10) // columns
        wrapped: list[tuple[str, tuple[int, int, int, int]]] = []
        for value, tone in lines:
            wrapped.extend((line, tone) for line in wrap(font, value, col_w))
        for index, (value, tone) in enumerate(wrapped[:rows * columns]):
            col, row = min(columns - 1, index // rows), index % rows
            tx, ty = x1 + 8 + col * (col_w + 10), y1 + 7 + row * 10
            font.draw(image, tx, ty, value, tone, shadow=False)

    font.draw(image, left + 10, bottom - 19, "Knowledge is saved to this settlement", rgba("#D4BD81"), shadow=True)
    report = {
        "logical_size": list(logical), "panel": [left, top, panel_w, panel_h],
        "viewport": [vx, vy, view_w, view_h], "zoom": zoom,
        "node_size": node_size, "escaped_nodes": escaped, "detail": detail,
    }
    return image, report


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    outputs: dict[str, object] = {}
    failed = False
    for logical, selected, name in (
        ((854, 480), True, "development_survey_wide_selected.png"),
        ((427, 240), False, "development_survey_compact_overview.png"),
        ((427, 240), True, "development_survey_compact_selected.png"),
    ):
        first, report = render(logical, selected)
        second, _ = render(logical, selected)
        deterministic = first.tobytes() == second.tobytes()
        output = OUT / name
        first.save(output, optimize=True)
        report["deterministic_double_render"] = deterministic
        report["sha256"] = hashlib.sha256(output.read_bytes()).hexdigest()
        outputs[name] = report
        failed |= bool(report["escaped_nodes"]) or not deterministic
        print(output)
    manifest = OUT / "development_survey_report.json"
    manifest.write_text(json.dumps({"offline_only": True, "strict_pass": not failed,
                                    "outputs": outputs}, indent=2) + "\n", encoding="utf-8")
    print(manifest)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
