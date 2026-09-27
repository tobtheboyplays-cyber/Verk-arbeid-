#!/usr/bin/env python3
"""Render the collision-checked Hearthstead Living Tree v2 review set.

The first Living Tree proved the desired progression concept but overloaded
the map with labels, status plates and sequence tags.  V2 keeps the physical
tree, First Raid gate and three doctrine branches while moving requirements,
costs, rewards and recipes into one stable inspector.  The overview map uses
only route, milestone, sequence and one non-colour state mark.

This remains an offline composition contract.  It does not claim native input,
frame-time, multiplayer authority or release approval.
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


LOGICAL_W = 854
LOGICAL_H = 480
DEFAULT_SCALE = 2
ITEMS = PROJECT / "src/main/resources/assets/hearthstead/textures/item"


def rgba(value: str) -> tuple[int, int, int, int]:
    raw = value.removeprefix("#")
    if len(raw) == 6:
        raw += "ff"
    if len(raw) != 8:
        raise ValueError(value)
    return tuple(int(raw[index:index + 2], 16)
                 for index in range(0, 8, 2))  # type: ignore[return-value]


COLOUR = {
    "void": rgba("#0b0e0d"),
    "map": rgba("#101712"),
    "map_alt": rgba("#121a15"),
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
    "future": rgba("#8e789c"),
    "raid": rgba("#d75b4d"),
    "raid_dark": rgba("#692d2a"),
    "defence": rgba("#d56558"),
    "defence_dark": rgba("#522d2a"),
    "industry": rgba("#e0a84d"),
    "industry_dark": rgba("#61491f"),
    "community": rgba("#60bd8a"),
    "community_dark": rgba("#244b38"),
    "focus": rgba("#f4df87"),
    "good_bg": rgba("#173122"),
    "warn_bg": rgba("#382b14"),
    "bad_bg": rgba("#391a18"),
    "card": rgba("#1b211d"),
    "black_70": rgba("#000000b3"),
    "white_04": rgba("#ffffff0a"),
    "white_08": rgba("#ffffff14"),
}


class StrictLayoutError(RuntimeError):
    pass


@dataclass(frozen=True)
class Rect:
    x: int
    y: int
    w: int
    h: int

    def intersects(self, other: "Rect", gap: int = 0) -> bool:
        return not (
            self.x + self.w + gap <= other.x
            or other.x + other.w + gap <= self.x
            or self.y + self.h + gap <= other.y
            or other.y + other.h + gap <= self.y
        )


@dataclass(frozen=True)
class Node:
    key: str
    label: str
    x: int
    y: int
    radius: int
    icon: str
    tone: str
    state: str
    sequence: int


NODES = (
    Node("charter", "Settlement Charter", 58, 429, 16, "hearthward_seal.png", "learned", "LEARNED", 1),
    Node("first_fire", "First Fire", 112, 411, 16, "hearthward_seal.png", "learned", "LEARNED", 2),
    Node("lumber", "Lumber Camp", 169, 430, 19, "lumberer_emblem.png", "available", "AVAILABLE", 3),
    Node("warehouse", "Warehouse", 226, 407, 17, "courier_emblem.png", "locked", "LOCKED", 4),
    Node("farm", "Cultivated Ground", 283, 427, 17, "farmer_emblem.png", "locked", "LOCKED", 5),
    Node("home", "Home", 338, 401, 17, "build_plan.png", "locked", "LOCKED", 6),
    Node("tavern", "Tavern", 390, 369, 17, "innkeeper_emblem.png", "locked", "LOCKED", 7),
    Node("first_watch", "First Watch", 450, 325, 18, "guard_emblem.png", "locked", "LOCKED", 8),
    Node("arm_watch", "Arm the Watch", 430, 276, 18, "archer_emblem.png", "locked", "LOCKED", 9),
    Node("raid", "First Raid", 315, 224, 25, "guard_emblem.png", "raid", "GATE", 10),
    Node("shield", "Shield Doctrine", 127, 150, 23, "warden_oath_seal.png", "defence", "LOCKED", 11),
    Node("guild", "Guild Doctrine", 315, 141, 23, "timber_beam.png", "industry", "LOCKED", 12),
    Node("hearth", "Hearth Doctrine", 503, 150, 23, "hearthward_seal.png", "community", "LOCKED", 13),
    Node("fortification", "Fortification", 71, 96, 15, "guard_emblem.png", "future", "PLANNED", 14),
    Node("border", "Border Wardens", 184, 96, 15, "archer_emblem.png", "future", "PLANNED", 15),
    Node("land", "Land and Harvest", 269, 88, 15, "farmer_emblem.png", "future", "PLANNED", 16),
    Node("craft", "Craft and Industry", 367, 96, 15, "iron_bloom.png", "future", "PLANNED", 17),
    Node("hall", "Hall and Learning", 503, 96, 15, "scholar_emblem.png", "future", "PLANNED", 18),
)

NODE_BY_KEY = {node.key: node for node in NODES}

EDGES = (
    ("charter", "first_fire"), ("first_fire", "lumber"),
    ("lumber", "warehouse"), ("warehouse", "farm"),
    ("farm", "home"), ("home", "tavern"),
    ("tavern", "first_watch"), ("first_watch", "arm_watch"),
    ("arm_watch", "raid"), ("raid", "shield"),
    ("raid", "guild"), ("raid", "hearth"),
    ("shield", "fortification"), ("shield", "border"),
    ("guild", "land"), ("guild", "craft"),
    ("hearth", "hall"),
)


class Canvas:
    def __init__(self) -> None:
        self.font = mcfont.McFont.load()
        self.sprites = Sprites(SPRITES)
        self.img = Image.new("RGBA", (LOGICAL_W, LOGICAL_H), COLOUR["void"])
        self.draw = ImageDraw.Draw(self.img, "RGBA")
        self.errors: list[str] = []
        self.reserved: list[tuple[Rect, str]] = []

    def bounds(self, rect: Rect, label: str) -> None:
        if rect.w <= 0 or rect.h <= 0:
            self.errors.append(f"{label}: non-positive size {rect.w}x{rect.h}")
        if (rect.x < 0 or rect.y < 0 or rect.x + rect.w > LOGICAL_W
                or rect.y + rect.h > LOGICAL_H):
            self.errors.append(f"{label}: {rect} exceeds {LOGICAL_W}x{LOGICAL_H}")

    def reserve(self, rect: Rect, label: str, gap: int = 1) -> None:
        self.bounds(rect, label)
        for other, other_label in self.reserved:
            if rect.intersects(other, gap):
                self.errors.append(f"{label} overlaps {other_label}: {rect} vs {other}")
        self.reserved.append((rect, label))

    def panel(self, sprite: str, rect: Rect, label: str) -> None:
        self.bounds(rect, label)
        self.sprites.draw(self.img, f"panel/{sprite}", rect.x, rect.y,
                          rect.w, rect.h)

    def text(self, x: int, y: int, value: str, tone: str = "text", *,
             box: int | None = None, align: str = "left", label: str,
             shadow: bool = True) -> int:
        width = self.font.width(value)
        if box is not None and width > box:
            self.errors.append(f'{label}: "{value}" is {width}px in {box}px')
        draw_x = x
        if align == "center":
            draw_x = x - width // 2
        elif align == "right":
            draw_x = x - width
        self.bounds(Rect(draw_x, y, max(1, width), 9), label)
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
            elif current:
                lines.append(current)
                current = word
            else:
                self.errors.append(f"{label}: word {word!r} exceeds {max_width}px")
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
        rect = Rect(cx - size // 2, cy - size // 2, size, size)
        self.bounds(rect, label)
        self.img.alpha_composite(icon, (rect.x, rect.y))

    def finish(self) -> Image.Image:
        if self.errors:
            raise StrictLayoutError("\n".join(self.errors))
        return self.img


def draw_header(c: Canvas) -> None:
    c.text(16, 11, "SETTLEMENT DEVELOPMENT", "strong", box=222,
           label="screen title")
    c.text(252, 11, "A living path from first fire to lasting legacy", "muted",
           box=302, label="screen subtitle")
    c.text(694, 11, "3 / 18 LEARNED", "learned", box=92,
           label="learned summary")
    c.text(840, 11, "62%", "available", box=32, align="right",
           label="zoom")
    c.text(840, 23, "Drag: pan  |  Wheel: zoom  |  Home: centre", "muted",
           box=246, align="right", label="controls")


def draw_map(c: Canvas) -> None:
    c.panel("inset", Rect(8, 38, 590, 434), "map panel")
    c.draw.rectangle((13, 43, 592, 466), fill=COLOUR["map"])
    for y in (95, 174, 254, 334, 414):
        c.draw.line((17, y, 588, y + 3), fill=COLOUR["white_04"], width=1)
    c.draw.polygon(((17, 47), (214, 47), (226, 186), (112, 197), (17, 160)),
                   fill=rgba("#522d2a20"))
    c.draw.polygon(((216, 47), (414, 47), (422, 186), (315, 195), (224, 184)),
                   fill=rgba("#61491f20"))
    c.draw.polygon(((416, 47), (588, 47), (588, 174), (505, 198), (421, 184)),
                   fill=rgba("#244b3826"))

    headers = (
        (24, 50, 170, "WATCH / DEFENCE", "defence", "defence_dark"),
        (220, 50, 184, "GUILD / INDUSTRY", "industry", "industry_dark"),
        (430, 50, 150, "HEARTH / PEOPLE", "community", "community_dark"),
    )
    for x, y, w, text, tone, fill_tone in headers:
        rect = Rect(x, y, w, 17)
        c.reserve(rect, f"{text} header")
        c.draw.rounded_rectangle((x, y, x + w - 1, y + 16), radius=3,
                                 fill=COLOUR[fill_tone], outline=COLOUR[tone])
        c.text(x + w // 2, y + 5, text, tone, box=w - 10,
               align="center", label=f"{text} text")


def edge_tone(source: Node, target: Node) -> str:
    if source.state == "LEARNED" and target.state == "LEARNED":
        return "learned"
    if target.state == "AVAILABLE":
        return "available"
    if target.tone in ("defence", "industry", "community"):
        return target.tone
    return "locked"


def draw_edges(c: Canvas) -> None:
    for source_key, target_key in EDGES:
        source = NODE_BY_KEY[source_key]
        target = NODE_BY_KEY[target_key]
        tone = edge_tone(source, target)
        points = [(source.x, source.y), (target.x, target.y)]
        c.draw.line(points, fill=COLOUR["black_70"], width=8)
        c.draw.line(points, fill=COLOUR["oak_deep"], width=6)
        c.draw.line(points, fill=COLOUR[tone], width=3)


def draw_node(c: Canvas, node: Node, selected: str) -> None:
    radius = node.radius
    hit = Rect(node.x - radius - 4, node.y - radius - 4,
               (radius + 4) * 2, (radius + 4) * 2)
    c.reserve(hit, f"{node.key} node", gap=2)
    tone = COLOUR[node.tone]
    if node.key == selected:
        c.draw.ellipse((node.x - radius - 5, node.y - radius - 5,
                        node.x + radius + 5, node.y + radius + 5),
                       fill=COLOUR["focus"], outline=COLOUR["brass_light"], width=2)
    c.draw.ellipse((node.x - radius - 2, node.y - radius + 2,
                    node.x + radius + 2, node.y + radius + 6),
                   fill=COLOUR["black_70"])
    c.draw.ellipse((node.x - radius - 1, node.y - radius - 1,
                    node.x + radius + 1, node.y + radius + 1),
                   fill=COLOUR["oak_deep"], outline=tone, width=2)
    c.draw.ellipse((node.x - radius + 3, node.y - radius + 3,
                    node.x + radius - 3, node.y + radius - 3),
                   fill=COLOUR["oak"], outline=COLOUR["brass_dark"])
    icon_size = 22 if radius >= 22 else 18 if radius >= 18 else 14
    c.icon(node.icon, node.x, node.y - 1, icon_size, f"{node.key} icon")

    badge_x = node.x - radius
    badge_y = node.y - radius
    c.draw.rounded_rectangle((badge_x, badge_y, badge_x + 14, badge_y + 10),
                             radius=2, fill=COLOUR["oak_deep"], outline=tone)
    c.text(badge_x + 7, badge_y + 2, f"{node.sequence:02d}", node.tone,
           box=12, align="center", label=f"{node.key} sequence")

    state_mark = "✓" if node.state == "LEARNED" else (
        "!" if node.state == "AVAILABLE" else (
            "…" if node.state == "PLANNED" else "×"))
    mark_x = node.x + radius - 8
    mark_y = node.y + radius - 8
    c.draw.rounded_rectangle((mark_x, mark_y, mark_x + 10, mark_y + 10),
                             radius=2, fill=COLOUR["oak_deep"], outline=tone)
    c.text(mark_x + 5, mark_y + 2, state_mark, node.tone, box=8,
           align="center", label=f"{node.key} state mark")


def draw_raid_gate(c: Canvas) -> None:
    node = NODE_BY_KEY["raid"]
    x, y = node.x, node.y
    c.draw.rectangle((x - 57, y - 28, x + 57, y + 26),
                     fill=COLOUR["raid_dark"], outline=COLOUR["raid"], width=2)
    for tower_x in (x - 52, x + 43):
        c.draw.rectangle((tower_x, y - 34, tower_x + 9, y + 26),
                         fill=COLOUR["oak_deep"], outline=COLOUR["raid"])
        c.draw.polygon(((tower_x - 2, y - 34), (tower_x + 4, y - 42),
                        (tower_x + 11, y - 34)), fill=COLOUR["raid"])
    c.text(x, y - 18, "FIRST RAID", "strong", box=74, align="center",
           label="raid gate title")
    c.icon(node.icon, x, y + 4, 24, "raid gate icon")
    c.text(x, y + 31, "SURVIVE TO CHOOSE A DOCTRINE", "raid", box=176,
           align="center", label="raid consequence")


def draw_tooltip(c: Canvas, node: Node) -> None:
    # A bounded transient popup. Critical requirements still remain in the
    # pinned inspector; hover is only the fast recognition layer.
    rect = Rect(278, 304, 134, 50)
    c.panel("card_hover", rect, "hover popup")
    c.text(rect.x + 8, rect.y + 7, node.label, "strong", box=118,
           label="hover title")
    c.text(rect.x + 8, rect.y + 19, node.state, "locked", box=118,
           label="hover state")
    c.text(rect.x + 8, rect.y + 32, "House settlers  3 / 4", "available",
           box=118, label="hover requirement")
    c.draw.line((rect.x + rect.w, rect.y + 22, node.x - node.radius - 3,
                 node.y), fill=COLOUR["brass"], width=1)


def draw_requirement(c: Canvas, y: int, passed: bool, text: str) -> None:
    tone = "learned" if passed else "available"
    mark = "✓" if passed else "•"
    c.text(622, y, mark, tone, box=10, label=f"requirement {y} mark")
    c.text(636, y, text, "text" if passed else "available", box=194,
           label=f"requirement {y}")


def draw_item_line(c: Canvas, y: int, texture: str, text: str,
                   tone: str = "text") -> None:
    c.icon(texture, 630, y + 7, 16, f"{text} icon")
    c.text(644, y + 3, text, tone, box=184, label=text)


def draw_recipe(c: Canvas) -> None:
    grid_x, grid_y = 622, 318
    ingredients = {
        (0, 0): ("timber_beam.png", "8"),
        (1, 0): ("timber_beam.png", "8"),
        (2, 0): ("timber_beam.png", "8"),
        (0, 1): ("timber_beam.png", "4"),
        (1, 1): ("building_plaque.png", "1"),
        (2, 1): ("timber_beam.png", "4"),
        (0, 2): ("timber_beam.png", "2"),
        (1, 2): ("work_scepter.png", "1"),
        (2, 2): ("timber_beam.png", "2"),
    }
    for row in range(3):
        for col in range(3):
            x = grid_x + col * 24
            y = grid_y + row * 24
            c.sprites.draw(c.img, "widget/slot", x, y, 20, 20)
            texture, count = ingredients[(col, row)]
            c.icon(texture, x + 10, y + 10, 14,
                   f"recipe {row}:{col} icon")
            c.text(x + 17, y + 12, count, "strong", box=7, align="right",
                   label=f"recipe {row}:{col} count")
    c.text(703, 341, "→", "brass_light", box=14, label="recipe arrow")
    c.sprites.draw(c.img, "widget/slot", 724, 332, 36, 36)
    c.icon("build_plan.png", 742, 350, 25, "recipe output icon")
    c.text(772, 335, "Lumber", "available", box=62,
            label="recipe output title")
    c.text(772, 347, "Camp Plan", "muted", box=62,
            label="recipe output kind")


def draw_inspector(c: Canvas, learned: bool) -> None:
    c.panel("inset", Rect(604, 38, 242, 434), "inspector")
    c.draw.rectangle((609, 43, 840, 466), fill=COLOUR["map_alt"])

    c.text(620, 54, "LUMBER CAMP", "strong", box=128,
           label="inspector title")
    state = "LEARNED" if learned else "AVAILABLE"
    state_tone = "learned" if learned else "available"
    state_w = c.font.width(state) + 14
    c.draw.rounded_rectangle((831 - state_w, 50, 831, 67), radius=3,
                             fill=COLOUR["good_bg" if learned else "warn_bg"],
                             outline=COLOUR[state_tone])
    c.text(831 - state_w // 2, 55, state, state_tone, box=state_w - 8,
           align="center", label="inspector state")
    c.wrap(620, 77,
           "Learn the Lumber Camp plan and make Lumberer Emblems available from the Mayor.",
           208, "text", max_lines=3, label="inspector description")
    c.draw.line((620, 111, 830, 111), fill=COLOUR["brass_dark"], width=1)

    c.text(620, 122, "REQUIREMENTS", "strong", box=208,
           label="requirements heading")
    draw_requirement(c, 140, True, "First Fire learned")
    draw_requirement(c, 156, True, "Foundation ready  1 / 1")

    c.text(620, 181, "RESEARCH COST — FROM HEARTH", "strong", box=208,
           label="cost heading")
    draw_item_line(c, 196, "timber_beam.png", "8 Any Log", "text")
    draw_item_line(c, 216, "iron_bloom.png", "8 Cobblestone", "text")

    c.text(620, 244, "UNLOCKS", "strong", box=208, label="unlocks heading")
    draw_item_line(c, 259, "build_plan.png", "Lumber Camp Build Plan", "available")
    draw_item_line(c, 279, "lumberer_emblem.png", "Mayor stock: Lumberer Emblem", "learned")

    c.text(620, 305, "BUILD PLAN RECIPE", "strong", box=208,
           label="recipe heading")
    if learned:
        draw_recipe(c)
        c.wrap(620, 395, "Shown in your recipe book after research.", 208,
               "muted", max_lines=2, label="recipe note")
    else:
        c.panel("card", Rect(620, 319, 208, 58), "locked recipe card")
        c.icon("build_plan.png", 642, 348, 24, "locked recipe icon")
        c.text(662, 329, "RECIPE LOCKED", "available", box=154,
               label="locked recipe title")
        c.wrap(662, 342, "Learn this node to reveal the exact crafting grid.",
               154, "muted", max_lines=3, label="locked recipe body")

    button_text = "PIN BUILD PLAN" if learned else "LEARN FOR 8 LOG + 8 COBBLE"
    c.sprites.draw(c.img, "widget/button_idle", 620, 431, 208, 24)
    c.text(724, 439, button_text, "strong", box=196, align="center",
           label="primary action")


def render(*, learned: bool, hover: str | None) -> Image.Image:
    c = Canvas()
    c.panel("window", Rect(0, 0, LOGICAL_W, LOGICAL_H), "outer window")
    draw_header(c)
    draw_map(c)
    draw_edges(c)
    for node in NODES:
        if node.key != "raid":
            draw_node(c, node, "lumber")
    draw_raid_gate(c)
    if hover is not None:
        draw_tooltip(c, NODE_BY_KEY[hover])
    draw_inspector(c, learned)
    return c.finish()


def digest(image: Image.Image) -> str:
    return hashlib.sha256(image.tobytes()).hexdigest()


def save_scaled(image: Image.Image, path: Path, scale: int) -> None:
    output = image if scale == 1 else image.resize(
        (image.width * scale, image.height * scale), Image.Resampling.NEAREST)
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

    variants = {
        "overview": render(learned=False, hover=None),
        "hover_first_watch": render(learned=False, hover="first_watch"),
        "learned_recipe": render(learned=True, hover=None),
    }
    for name, image in variants.items():
        if image.tobytes() != render(
                learned=name == "learned_recipe",
                hover="first_watch" if name == "hover_first_watch" else None).tobytes():
            raise StrictLayoutError(f"{name}: non-deterministic render")
        path = args.out_dir / f"development_living_tree_v2_{name}_en.png"
        save_scaled(image, path, args.scale)
        print(path)

    manifest = {
        "concept": "Hearthstead Living Tree v2",
        "logical_size": [LOGICAL_W, LOGICAL_H],
        "clean_room": True,
        "assets": "Hearthstead originals only",
        "information_rule": "map=route+milestone+sequence+state; inspector=requirements+cost+unlocks+recipe+action",
        "runtime_claim": False,
        "variants": {name: digest(image) for name, image in variants.items()},
        "acceptance": {
            "protected_geometry_collision_free": True,
            "eighteen_nodes_visible": True,
            "first_raid_gate_prominent": True,
            "three_post_raid_branches_visible": True,
            "requirements_and_costs_outside_map": True,
            "learned_build_plan_recipe_visible": True,
            "hover_summary_demonstrated": True,
            "deterministic": True,
        },
    }
    manifest_path = args.out_dir / "development_living_tree_v2_manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n",
                             encoding="utf-8")
    print(manifest_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
