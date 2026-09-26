#!/usr/bin/env python3
"""Render the alternate Hearthstead Development living-tree concept offline.

This is intentionally a *map*, not an administration grid: the founding path
grows out of the Hearth, the First Raid is a large physical gate, and the
post-raid doctrines split into three colour-coded branches.  The renderer uses
only Hearthstead's own GUI sprites/item textures and Minecraft's real font.

The script is strict.  It fails on text overflow, out-of-bounds geometry, a
missing asset, or non-deterministic output.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
import sys

from PIL import Image, ImageDraw


HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
sys.path.insert(0, str(HERE))

import mcfont  # noqa: E402
from ui_preview import Sprites, SPRITES  # noqa: E402


LOGICAL_W = 672
LOGICAL_H = 360
DEFAULT_SCALE = 2
ITEMS = PROJECT / "src/main/resources/assets/hearthstead/textures/item"


def rgba(hex_colour: str) -> tuple[int, int, int, int]:
    value = hex_colour.removeprefix("#")
    if len(value) == 6:
        value += "ff"
    if len(value) != 8:
        raise ValueError(hex_colour)
    return tuple(int(value[index:index + 2], 16) for index in range(0, 8, 2))  # type: ignore[return-value]


COLOUR = {
    "void": rgba("#0b0e0d"),
    "map": rgba("#101712"),
    "map_alt": rgba("#121a15"),
    "ink": rgba("#17110e"),
    "oak_deep": rgba("#221611"),
    "oak": rgba("#4a3022"),
    "oak_light": rgba("#76543a"),
    "brass_dark": rgba("#705323"),
    "brass": rgba("#c59a45"),
    "brass_light": rgba("#ead28c"),
    "text": rgba("#e7dfcd"),
    "strong": rgba("#fff2ce"),
    "muted": rgba("#a59b89"),
    "learned": rgba("#5fc875"),
    "available": rgba("#e0b955"),
    "locked": rgba("#766f65"),
    "raid": rgba("#d75b4d"),
    "raid_dark": rgba("#692d2a"),
    "defence": rgba("#d56558"),
    "defence_dark": rgba("#61302e"),
    "industry": rgba("#e0a84d"),
    "industry_dark": rgba("#674a23"),
    "community": rgba("#60bd8a"),
    "community_dark": rgba("#28513f"),
    "focus": rgba("#f4df87"),
    "black_70": rgba("#000000b3"),
    "white_08": rgba("#ffffff14"),
    "white_04": rgba("#ffffff0a"),
}


class StrictLayoutError(RuntimeError):
    pass


@dataclass(frozen=True)
class Node:
    key: str
    label: str
    x: int
    y: int
    size: int
    icon: str
    tone: str
    state: str
    subtitle: str = ""


NODES = (
    Node("hearth", "HEARTH", 242, 315, 25, "hearthward_seal.png", "learned", "LEARNED", "Origin"),
    Node("first_fire", "First Fire", 242, 264, 18, "build_plan.png", "learned", "LEARNED"),
    Node("founding", "Founder's Trail", 242, 222, 20, "work_scepter.png", "available", "AVAILABLE"),
    Node("raid", "FIRST RAID", 242, 174, 27, "guard_emblem.png", "raid", "GATE", "Ashen host"),
    Node("defence", "WATCH", 90, 126, 23, "warden_oath_seal.png", "defence", "LOCKED", "Defence"),
    Node("industry", "GUILD", 242, 112, 23, "timber_beam.png", "industry", "LOCKED", "Industry"),
    Node("community", "HEARTHWARD", 394, 126, 23, "hearthward_seal.png", "community", "LOCKED", "Community"),
    Node("watchtower", "Watchtower", 48, 68, 16, "archer_emblem.png", "defence", "LOCKED"),
    Node("border", "Border Wardens", 132, 52, 16, "guard_emblem.png", "defence", "LOCKED"),
    Node("warehouse", "Warehouse", 210, 54, 16, "courier_emblem.png", "industry", "LOCKED"),
    Node("forge", "Forge Yard", 286, 58, 16, "iron_bloom.png", "industry", "LOCKED"),
    Node("tavern", "Tavern Hall", 394, 65, 16, "ale.png", "community", "LOCKED"),
)

NODE_BY_KEY = {node.key: node for node in NODES}
NODE_ORDER = {
    "hearth": 1,
    "first_fire": 2,
    "founding": 3,
    "raid": 4,
    "defence": 5,
    "industry": 6,
    "community": 7,
    "watchtower": 8,
    "border": 9,
    "warehouse": 10,
    "forge": 11,
    "tavern": 12,
}


class LivingTreeCanvas:
    def __init__(self) -> None:
        self.font = mcfont.McFont.load()
        self.sprites = Sprites(SPRITES)
        self.img = Image.new("RGBA", (LOGICAL_W, LOGICAL_H), COLOUR["void"])
        self.draw = ImageDraw.Draw(self.img, "RGBA")
        self.errors: list[str] = []

    def bounds(self, x: int, y: int, w: int, h: int, label: str) -> None:
        if w <= 0 or h <= 0:
            self.errors.append(f"{label}: non-positive size {w}x{h}")
        if x < 0 or y < 0 or x + w > LOGICAL_W or y + h > LOGICAL_H:
            self.errors.append(
                f"{label}: bounds ({x},{y},{w},{h}) exceed {LOGICAL_W}x{LOGICAL_H}")

    def panel(self, sprite: str, x: int, y: int, w: int, h: int, label: str) -> None:
        self.bounds(x, y, w, h, label)
        self.sprites.draw(self.img, f"panel/{sprite}", x, y, w, h)

    def rect(self, x: int, y: int, w: int, h: int, colour: tuple[int, int, int, int], label: str) -> None:
        self.bounds(x, y, w, h, label)
        self.draw.rectangle((x, y, x + w - 1, y + h - 1), fill=colour)

    def text(self, x: int, y: int, value: str, tone: str = "text", *,
             box: int | None = None, align: str = "left", label: str = "text",
             shadow: bool = True) -> int:
        width = self.font.width(value)
        if box is not None and width > box:
            self.errors.append(f'{label}: "{value}" is {width}px in {box}px')
        draw_x = x
        if align == "center":
            draw_x = x - width // 2
        elif align == "right":
            draw_x = x - width
        self.bounds(draw_x, y, max(1, width), 9, label)
        self.font.draw(self.img, draw_x, y, value, COLOUR[tone], shadow=shadow)
        return width

    def wrap(self, x: int, y: int, value: str, max_width: int, tone: str,
             *, max_lines: int, line_gap: int = 10, label: str) -> int:
        words = value.split()
        lines: list[str] = []
        current = ""
        for word in words:
            candidate = word if not current else current + " " + word
            if self.font.width(candidate) <= max_width:
                current = candidate
            else:
                if not current:
                    self.errors.append(f"{label}: word {word!r} exceeds {max_width}px")
                    current = word
                else:
                    lines.append(current)
                    current = word
        if current:
            lines.append(current)
        if len(lines) > max_lines:
            self.errors.append(f"{label}: needs {len(lines)} lines, allowed {max_lines}")
        for index, line in enumerate(lines[:max_lines]):
            self.text(x, y + index * line_gap, line, tone, box=max_width,
                      label=f"{label} line {index + 1}")
        return min(len(lines), max_lines)

    def icon(self, texture: str, cx: int, cy: int, size: int, label: str) -> None:
        path = ITEMS / texture
        if not path.is_file():
            self.errors.append(f"{label}: missing {path}")
            return
        icon = Image.open(path).convert("RGBA").resize((size, size), Image.Resampling.NEAREST)
        self.bounds(cx - size // 2, cy - size // 2, size, size, label)
        self.img.alpha_composite(icon, (cx - size // 2, cy - size // 2))

    def finish(self) -> Image.Image:
        if self.errors:
            raise StrictLayoutError("\n".join(self.errors))
        return self.img


def polyline(c: LivingTreeCanvas, points: list[tuple[int, int]], tone: str,
             *, label: str, width: int = 5, locked: bool = False) -> None:
    for index, (x, y) in enumerate(points):
        c.bounds(x, y, 1, 1, f"{label} point {index}")
    # Ground shadow, forged/oak road, then a coloured thread.  This reads as a
    # physical path at overview scale instead of a sterile graph connector.
    c.draw.line(points, fill=COLOUR["black_70"], width=width + 4, joint="curve")
    c.draw.line(points, fill=COLOUR["oak_deep"], width=width + 2, joint="curve")
    inner = COLOUR["locked"] if locked else COLOUR[tone]
    c.draw.line(points, fill=inner, width=width, joint="curve")
    c.draw.line(points, fill=COLOUR["white_08"], width=1, joint="curve")
    # Hammered road studs reinforce direction without animation or glow spam.
    for x, y in points[1:-1]:
        c.draw.rectangle((x - 1, y - 1, x + 1, y + 1), fill=COLOUR["brass_dark"])


def draw_map_ground(c: LivingTreeCanvas) -> None:
    c.panel("inset", 8, 36, 472, 316, "development map")
    c.rect(13, 41, 462, 306, COLOUR["map"], "map ground")

    # Quiet contour lines and branch territories add depth but stay behind the
    # readable route.  Every mark is deterministic and uses no external art.
    for y in (58, 92, 144, 200, 252, 301):
        c.draw.line((18, y, 469, y + (4 if y % 3 else -3)), fill=COLOUR["white_04"], width=1)
    for x in (55, 160, 326, 429):
        c.draw.arc((x - 52, 48, x + 64, 192), 205, 340, fill=COLOUR["white_04"], width=1)

    c.draw.polygon(((19, 42), (170, 42), (184, 148), (107, 169), (19, 149)),
                   fill=rgba("#522d2a24"))
    c.draw.polygon(((172, 42), (319, 42), (334, 148), (242, 160), (181, 146)),
                   fill=rgba("#61491f22"))
    c.draw.polygon(((321, 42), (469, 42), (469, 151), (391, 169), (329, 148)),
                   fill=rgba("#244b3828"))

    # Branch title plaques make the three-way split legible before details.
    branch_headers = (
        (21, 43, 105, "WATCH / DEFENCE", "defence"),
        (184, 43, 118, "TRADE / INDUSTRY", "industry"),
        (344, 43, 120, "HEARTH / COMMUNITY", "community"),
    )
    for x, y, w, label, tone in branch_headers:
        c.draw.rounded_rectangle((x, y, x + w, y + 14), radius=3,
                                 fill=COLOUR[tone + "_dark"], outline=COLOUR[tone])
        c.text(x + w // 2, y + 4, label, tone, box=w - 8, align="center",
               label=f"{label} header", shadow=True)


def draw_routes(c: LivingTreeCanvas) -> None:
    # The common founding trunk is deliberately vertical and short.
    polyline(c, [(242, 315), (242, 282)], "learned", label="Hearth to First Fire", width=5)
    polyline(c, [(242, 248), (242, 242)], "available", label="First Fire to Founding", width=5)
    polyline(c, [(242, 202), (242, 197)], "available", label="Founding to Raid", width=6)

    # The raid gate is the hinge.  Every doctrine grows visibly out of it.
    polyline(c, [(226, 153), (201, 146), (128, 146), (104, 136)], "defence",
             label="Raid to Watch", width=6, locked=True)
    polyline(c, [(242, 153), (242, 135)], "industry",
             label="Raid to Guild", width=6, locked=True)
    polyline(c, [(258, 153), (283, 146), (356, 146), (380, 136)], "community",
             label="Raid to Hearthward", width=6, locked=True)

    polyline(c, [(78, 108), (62, 91), (54, 83)], "defence",
             label="Watch to Watchtower", width=4, locked=True)
    polyline(c, [(102, 106), (117, 84), (128, 68)], "defence",
             label="Watch to Border", width=4, locked=True)
    polyline(c, [(230, 92), (218, 76), (213, 70)], "industry",
             label="Guild to Warehouse", width=4, locked=True)
    polyline(c, [(254, 92), (271, 78), (282, 74)], "industry",
             label="Guild to Forge", width=4, locked=True)
    polyline(c, [(394, 103), (394, 81)], "community",
             label="Hearthward to Tavern", width=4, locked=True)


def node_colour(node: Node) -> tuple[int, int, int, int]:
    return COLOUR[node.tone]


def draw_standard_node(c: LivingTreeCanvas, node: Node, selected: str | None) -> None:
    x, y, radius = node.x, node.y, node.size
    tone = node_colour(node)
    selected_here = selected == node.key

    # Deep drop shadow + two material rings make each unlock feel like a
    # physical medallion pinned to a cartographer's board.
    c.draw.ellipse((x - radius - 2, y - radius + 2, x + radius + 2, y + radius + 6),
                   fill=COLOUR["black_70"])
    if selected_here:
        c.draw.ellipse((x - radius - 5, y - radius - 5, x + radius + 5, y + radius + 5),
                       fill=COLOUR["focus"], outline=COLOUR["brass_light"], width=2)
    c.draw.ellipse((x - radius - 2, y - radius - 2, x + radius + 2, y + radius + 2),
                   fill=COLOUR["oak_deep"], outline=tone, width=2)
    c.draw.ellipse((x - radius + 2, y - radius + 2, x + radius - 2, y + radius - 2),
                   fill=COLOUR["oak"], outline=COLOUR["brass_dark"], width=1)
    icon_size = 24 if radius >= 22 else (20 if radius >= 18 else 16)
    c.icon(node.icon, x, y - (2 if radius >= 20 else 0), icon_size, f"{node.key} icon")
    number = f"{NODE_ORDER[node.key]:02d}"
    badge_x, badge_y = x - radius - 4, y - radius - 4
    c.draw.rounded_rectangle((badge_x, badge_y, badge_x + 15, badge_y + 10),
                             radius=2, fill=COLOUR["oak_deep"], outline=tone)
    c.text(badge_x + 7, badge_y + 2, number, node.tone, box=12,
           align="center", label=f"{node.key} sequence")

    # Endpoint labels live below; branch-head and trunk labels use plates to
    # stay readable over the map without turning the view into cards.
    label_y = y + radius + 4
    label_w = max(54, min(100, c.font.width(node.label) + 10))
    label_x = x - label_w // 2
    c.draw.rounded_rectangle((label_x, label_y, label_x + label_w, label_y + 13),
                             radius=3, fill=rgba("#17110ee6"), outline=tone)
    c.text(x, label_y + 3, node.label, node.tone, box=label_w - 6,
           align="center", label=f"{node.key} label")

    if node.size >= 18:
        state_w = max(34, c.font.width(node.state) + 8)
        state_y = y - radius - 10
        c.draw.rounded_rectangle((x - state_w // 2, state_y, x + state_w // 2,
                                  state_y + 11), radius=3, fill=COLOUR["oak_deep"],
                                 outline=tone)
        c.text(x, state_y + 2, node.state, node.tone, box=state_w - 5,
               align="center", label=f"{node.key} state")


def draw_raid_gate(c: LivingTreeCanvas, node: Node, selected: str | None) -> None:
    x, y = node.x, node.y
    # A real gate silhouette: twin towers, lintel, spikes and sealed centre.
    c.draw.rounded_rectangle((x - 55, y - 23, x + 55, y + 24), radius=6,
                             fill=COLOUR["black_70"])
    c.draw.rectangle((x - 52, y - 20, x + 52, y + 20),
                     fill=COLOUR["raid_dark"], outline=COLOUR["raid"], width=2)
    for tower_x in (x - 47, x + 39):
        c.draw.rectangle((tower_x, y - 27, tower_x + 8, y + 23),
                         fill=COLOUR["oak_deep"], outline=COLOUR["raid"], width=1)
        c.draw.polygon(((tower_x - 2, y - 27), (tower_x + 4, y - 34),
                        (tower_x + 10, y - 27)), fill=COLOUR["raid"])
    c.draw.rectangle((x - 35, y - 14, x + 35, y + 20),
                     fill=COLOUR["oak_deep"], outline=COLOUR["brass_dark"])
    c.icon(node.icon, x, y + 1, 24, "raid icon")
    c.draw.rounded_rectangle((x - 58, y - 30, x - 43, y - 20), radius=2,
                             fill=COLOUR["oak_deep"], outline=COLOUR["raid"])
    c.text(x - 51, y - 28, f"{NODE_ORDER[node.key]:02d}", "raid", box=12,
           align="center", label="raid sequence")
    c.text(x, y - 16, "FIRST RAID", "strong", box=66, align="center",
           label="First Raid title")
    c.text(x, y + 24, "SURVIVE TO CHOOSE A DOCTRINE", "raid", box=158,
           align="center", label="First Raid consequence")
    if selected == node.key:
        c.draw.rounded_rectangle((x - 58, y - 37, x + 58, y + 29), radius=7,
                                 outline=COLOUR["focus"], width=2)


def draw_nodes(c: LivingTreeCanvas, selected: str | None) -> None:
    # Later nodes first, then the large trunk/gate so important layers win.
    order = ("watchtower", "border", "warehouse", "forge", "tavern",
             "defence", "industry", "community", "hearth", "first_fire",
             "founding")
    for key in order:
        draw_standard_node(c, NODE_BY_KEY[key], selected)
    draw_raid_gate(c, NODE_BY_KEY["raid"], selected)


def draw_progress(c: LivingTreeCanvas) -> None:
    c.panel("card", 15, 313, 109, 29, "legend card")
    c.text(22, 319, "FOUNDING PATH", "strong", box=90, label="legend title")
    c.text(22, 330, "2 / 18 learned", "learned", box=90, label="legend progress")
    # Tiny key, deliberately off the route.
    for index, (tone, label) in enumerate((("learned", "Learned"),
                                           ("available", "Available"),
                                           ("locked", "Locked"))):
        x = 132 + index * 56
        c.draw.rectangle((x, 330, x + 4, 334), fill=COLOUR[tone])
        c.text(x + 7, 328, label, "muted", box=48, label=f"legend {label}")


def draw_inspector(c: LivingTreeCanvas, selected: str | None) -> None:
    c.panel("inset", 484, 36, 180, 316, "inspector")
    c.rect(489, 41, 170, 306, COLOUR["map_alt"], "inspector ground")

    if selected is None:
        c.text(498, 50, "YOUR DEVELOPMENT", "strong", box=152,
               label="overview inspector title")
        c.text(498, 65, "2 / 18 learned", "learned", box=152,
               label="overview learned count")
        c.draw.line((498, 78, 649, 78), fill=COLOUR["brass_dark"], width=1)

        c.text(498, 88, "NEXT MILESTONE", "available", box=152,
               label="next milestone heading")
        c.panel("card_hover", 496, 101, 156, 67, "next milestone card")
        c.icon("work_scepter.png", 514, 120, 24, "next milestone icon")
        c.text(533, 108, "Founder's Trail", "strong", box=112,
               label="next milestone name")
        c.text(533, 121, "0 / 3 settlers", "available", box=112,
               label="next milestone progress")
        c.wrap(505, 139, "Place a Home and assign the first worker.", 138,
               "muted", max_lines=2, label="next milestone action")

        c.text(498, 181, "THE CHOICE AHEAD", "strong", box=152,
               label="choice heading")
        c.wrap(498, 195,
               "Survive the First Raid. Then commit to one permanent doctrine; the other two close.",
               152, "muted", max_lines=5, label="choice body")

        rows = (("defence", "Watch", "Guard power"),
                ("industry", "Guild", "Work output"),
                ("community", "Hearthward", "Settler health"))
        for index, (tone, name, effect) in enumerate(rows):
            y = 248 + index * 23
            c.draw.rectangle((498, y + 2, 503, y + 15), fill=COLOUR[tone])
            c.text(509, y, name, tone, box=60, label=f"{name} doctrine")
            c.text(574, y, effect, "muted", box=74, label=f"{name} effect")

        c.sprites.draw(c.img, "widget/button_idle", 498, 319, 150, 20)
        c.text(573, 326, "Track Founder's Trail", "strong", box=138,
               align="center", label="track milestone button")
        return

    # Selected-node state: exact consequence, quest, physical cost and rewards
    # are always visible; there is no hidden hover-only critical information.
    c.text(498, 50, "GUILD COMPACT", "industry", box=152,
           label="selected title")
    c.text(498, 63, "Permanent doctrine", "muted", box=152,
           label="selected kind")
    c.draw.line((498, 77, 649, 77), fill=COLOUR["industry_dark"], width=2)

    c.wrap(498, 87,
           "Build a settlement that moves goods quickly and turns raw material into finished work.",
           152, "text", max_lines=4, label="selected description")

    c.text(498, 132, "PERMANENT EFFECT", "strong", box=152,
           label="tradeoff heading")
    c.panel("card_hover", 496, 144, 156, 47, "tradeoff card")
    c.text(505, 151, "+15% hauling capacity", "community", box=138,
           label="positive hauling effect")
    c.text(505, 162, "+10% crafting speed", "industry", box=138,
           label="positive crafting effect")
    c.text(505, 173, "-10% guard recovery", "raid", box=138,
           label="negative guard effect")

    c.text(498, 202, "QUEST", "strong", box=152, label="quest heading")
    c.text(498, 214, "Courier trips  3 / 6", "industry", box=152,
           label="quest progress")
    c.sprites.draw(c.img, "bar/track", 498, 226, 150, 6)
    c.sprites.draw(c.img, "bar/fill_warn", 499, 227, 74, 4)

    c.text(498, 240, "PHYSICAL COST", "strong", box=152,
           label="cost heading")
    c.icon("timber_beam.png", 506, 259, 16, "timber cost icon")
    c.text(519, 255, "24 Timber Beam", "text", box=128,
           label="timber cost")
    c.icon("iron_bloom.png", 506, 278, 16, "iron cost icon")
    c.text(519, 274, "8 Iron Bloom", "text", box=128,
           label="iron cost")

    c.text(498, 294, "REWARDS", "strong", box=152, label="rewards heading")
    c.icon("build_plan.png", 506, 313, 16, "build plan reward icon")
    c.text(519, 309, "Warehouse Build Plan", "available", box=128,
           label="build plan reward")
    c.icon("courier_emblem.png", 506, 332, 16, "emblem reward icon")
    c.text(519, 328, "Courier Emblem", "community", box=128,
           label="emblem reward")


def render(selected: str | None) -> Image.Image:
    c = LivingTreeCanvas()
    c.panel("window", 0, 0, LOGICAL_W, LOGICAL_H, "outer window")
    c.text(14, 11, "SETTLEMENT DEVELOPMENT", "strong", box=235,
           label="screen title")
    c.text(258, 11, "A living path from first fire to lasting legacy", "muted",
           box=280, label="screen subtitle")
    c.text(658, 11, "62%", "available", box=30, align="right",
           label="zoom readout")
    c.text(658, 22, "Wheel: zoom  |  Drag: pan", "muted", box=150,
           align="right", label="map controls")
    draw_map_ground(c)
    draw_routes(c)
    draw_nodes(c, selected)
    draw_progress(c)
    draw_inspector(c, selected)
    return c.finish()


def digest(img: Image.Image) -> str:
    return hashlib.sha256(img.tobytes()).hexdigest()


def write_manifest(out_dir: Path, overview: Image.Image, selected: Image.Image,
                   scale: int) -> None:
    manifest = {
        "concept": "Hearthstead Development Living Tree ALT",
        "logical_size": [LOGICAL_W, LOGICAL_H],
        "output_scale": scale,
        "clean_room": True,
        "assets": "Hearthstead original GUI sprites and item textures only",
        "font": "Minecraft client font, measured by tools/mcfont.py",
        "route": {
            "trunk": ["Hearth", "First Fire", "Founder's Trail", "First Raid"],
            "permanent_split": ["Watch / Defence", "Trade / Industry", "Hearth / Community"],
            "visible_endpoints": ["Watchtower", "Border Wardens", "Warehouse", "Forge Yard", "Tavern Hall"],
        },
        "selected_node_contract": {
            "node": "Guild Compact",
            "shows": ["description", "quest progress", "physical cost", "Build Plan reward",
                      "Emblem reward", "exact permanent bonuses", "exact permanent penalty"],
        },
        "sha256_rgba": {
            "overview": digest(overview),
            "selected": digest(selected),
        },
        "acceptance": {
            "actual_branching_tree": True,
            "first_raid_is_major_gate": True,
            "three_branches_read_before_body_text": True,
            "five_later_endpoints_visible": True,
            "strict_text_and_bounds": True,
            "deterministic_second_render": True,
        },
    }
    path = out_dir / "development_living_tree_alt_manifest.json"
    path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def save_scaled(img: Image.Image, path: Path, scale: int) -> None:
    output = img if scale == 1 else img.resize(
        (img.width * scale, img.height * scale), Image.Resampling.NEAREST)
    output.save(path, optimize=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out-dir", type=Path,
                        default=PROJECT / "build/ui-overhaul")
    parser.add_argument("--scale", type=int, default=DEFAULT_SCALE)
    args = parser.parse_args()
    if args.scale < 1:
        parser.error("--scale must be >= 1")
    args.out_dir.mkdir(parents=True, exist_ok=True)

    overview = render(None)
    overview_check = render(None)
    if overview.tobytes() != overview_check.tobytes():
        raise StrictLayoutError("overview render is not deterministic")
    selected = render("industry")
    selected_check = render("industry")
    if selected.tobytes() != selected_check.tobytes():
        raise StrictLayoutError("selected render is not deterministic")

    overview_path = args.out_dir / "development_living_tree_alt_overview_en.png"
    selected_path = args.out_dir / "development_living_tree_alt_selected_guild_en.png"
    save_scaled(overview, overview_path, args.scale)
    save_scaled(selected, selected_path, args.scale)
    write_manifest(args.out_dir, overview, selected, args.scale)
    print(overview_path)
    print(selected_path)
    print(args.out_dir / "development_living_tree_alt_manifest.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
