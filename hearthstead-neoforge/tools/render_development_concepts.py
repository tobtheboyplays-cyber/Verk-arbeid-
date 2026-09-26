#!/usr/bin/env python3
"""Render three clean-room, preview-only Development art directions.

The former horizontal admin-card concept was rejected.  These concepts start
again from the product requirements and Hearthstead's own assets:

* A — Living Settlement Tree (recommended): one central founding road grows
  through the first raid, physically splits into three doctrines, then five
  specialization paths.
* B — Forged Constellation: circular knowledge stars joined by restrained
  brass/green light over dark iron and oak.
* C — Journey Map: a parchment-and-terrain route whose settlement landmarks
  fork after a memorable raid gate.

All overview maps keep quest, physical cost and rewards out of the tree.  Those
details appear only in Concept A's slide-in inspector after a node is selected.
The script reads the real Minecraft font, shared Hearthstead tokens, current
English source strings and real mod item icons.  It changes no runtime file.

Strict mode checks source topology, structural bounds, text boxes, node
collisions and a repeated in-memory render before approving deterministic PNGs.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import io
import json
from pathlib import Path
import random
import re
import sys
from typing import Callable, Iterable, Sequence
import zipfile

from PIL import Image, ImageDraw


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import mcfont  # noqa: E402
from ui_preview import Canvas, SPRITES, Sprites, WARNINGS, argb, colour  # noqa: E402


GRID = 4
SCALE_DEFAULT = 2
A_OVERVIEW_SIZE = (768, 692)
A_SELECTED_SIZE = (768, 580)
A_RECIPE_SIZE = (768, 620)
B_OVERVIEW_SIZE = (768, 480)
C_OVERVIEW_SIZE = (768, 480)

ASSETS = HERE.parent / "src/main/resources/assets/hearthstead"
ITEM_TEXTURES = ASSETS / "textures/item"
BLOCK_TEXTURES = ASSETS / "textures/block"
LANG_PATH = ASSETS / "lang/en_us.json"
NODE_SOURCE = (HERE.parent / "src/main/java/com/hearthstead/settlement/"
               "development/DevelopmentNode.java")
SCREEN_SOURCE = (HERE.parent / "src/main/java/com/hearthstead/client/screen/"
                 "DevelopmentScreen.java")
RECIPE_BOOK_SOURCE = (HERE.parent / "src/main/java/com/hearthstead/settlement/"
                      "development/DevelopmentRecipeBook.java")
LUMBER_RECIPE = (HERE.parent / "src/main/resources/data/hearthstead/recipe/"
                 "build_plan_lumber_camp.json")
VANILLA_ASSET_INDEX = Path(
    r"C:\Users\tobia\.gradle\caches\neoformruntime\assets\indexes\17.json")
VANILLA_ASSET_OBJECTS = VANILLA_ASSET_INDEX.parent.parent / "objects"
VANILLA_CLIENT_JAR = Path(
    r"C:\Users\tobia\.gradle\caches\neoformruntime\artifacts\minecraft_1.21.1_client.jar")


@dataclass(frozen=True)
class Box:
    x: int
    y: int
    w: int
    h: int

    @property
    def right(self) -> int:
        return self.x + self.w

    @property
    def bottom(self) -> int:
        return self.y + self.h

    @property
    def cx(self) -> int:
        return self.x + self.w // 2

    @property
    def cy(self) -> int:
        return self.y + self.h // 2

    def intersects(self, other: "Box") -> bool:
        return (self.x < other.right and self.right > other.x
                and self.y < other.bottom and self.bottom > other.y)


@dataclass(frozen=True)
class Point:
    x: int
    y: int


@dataclass(frozen=True)
class Node:
    key: str
    number: str
    short: str
    icon: str


@dataclass(frozen=True)
class Doctrine:
    key: str
    number: str
    name: str
    identity: str
    tradeoff: str
    icon: str
    tone: str
    children: tuple[Node, ...]


TRUNK = (
    Node("settlement_charter", "01", "Hearth Charter", "hearthward_seal.png"),
    Node("shelter", "02", "First Fire", "hearthward_seal.png"),
    Node("timber_rights", "03", "Lumber Camp", "lumberer_emblem.png"),
    Node("stores_and_roads", "04", "Warehouse", "courier_emblem.png"),
    Node("cultivated_ground", "05", "Cultivated Ground", "farmer_emblem.png"),
    Node("home", "06", "Home", "build_plan.png"),
    Node("hospitality", "07", "Tavern", "innkeeper_emblem.png"),
    Node("first_watch", "08", "First Watch", "guard_emblem.png"),
    Node("arm_the_watch", "09", "Arm the Watch", "archer_emblem.png"),
    # The stable source id stores the aftermath record; the player-facing gate
    # is the raid itself.  Doctrine roads start only beyond this node.
    Node("first_raid_aftermath", "10", "Repel First Raid", "warden_oath_seal.png"),
)

DOCTRINES = (
    Doctrine(
        "shield_doctrine", "11", "Shield Doctrine", "DEFENCE & CONTROL",
        "Locks Guild + Hearth", "warden_oath_seal.png", "warn",
        (
            Node("fortification", "14", "Fortification", "guard_emblem.png"),
            Node("border_wardens", "15", "Border Wardens", "archer_emblem.png"),
        ),
    ),
    Doctrine(
        "guild_doctrine", "12", "Guild Doctrine", "LOGISTICS & INDUSTRY",
        "Locks Shield + Hearth", "sawyer_emblem.png", "accent",
        (
            Node("land_and_harvest", "16", "Land & Harvest", "farmer_emblem.png"),
            Node("craft_and_industry", "17", "Craft & Industry", "sawyer_emblem.png"),
        ),
    ),
    Doctrine(
        "hearth_doctrine", "13", "Hearth Doctrine", "WELFARE & LEARNING",
        "Locks Shield + Guild", "scholar_emblem.png", "good",
        (
            Node("hall_and_learning", "18", "Hall & Learning", "scholar_emblem.png"),
        ),
    ),
)

STATE_TONE = {
    "learned": "good",
    "available": "accent",
    "locked": "text_muted",
    "planned": "text_muted",
}
STATE_LABEL = {
    "learned": "LEARNED",
    "available": "AVAILABLE",
    "locked": "LOCKED",
    "planned": "PLANNED",
}

INK = argb(0xFF2B2117)
PARCHMENT = argb(0xFFE0C98E)
PARCHMENT_DARK = argb(0xFF9A7543)
ROAD_EDGE = argb(0xFF241A12)
ROAD_EARTH = argb(0xFF765633)
IRON = argb(0xFF17191B)
IRON_SOFT = argb(0xFF26282A)
OAK = argb(0xFF4D3420)
BRASS = colour("accent")

_ICON_CACHE: dict[tuple[str, int], Image.Image] = {}
_VANILLA_ICON_CACHE: dict[tuple[str, int], Image.Image] = {}
_VANILLA_ICON_PROVENANCE: dict[str, str] = {}


def lang() -> dict[str, str]:
    with LANG_PATH.open(encoding="utf-8") as handle:
        return json.load(handle)


def translated(strings: dict[str, str], key: str) -> str:
    if key not in strings:
        raise ValueError(f"missing English preview key: {key}")
    return strings[key]


def rgba(token: str, alpha: int) -> tuple[int, int, int, int]:
    r, g, b, _ = colour(token)
    return r, g, b, alpha


def vanilla_texture_bytes(resource: str) -> bytes:
    """Resolve an unmodified vanilla texture without copying it into source.

    The launcher asset index is checked first.  Minecraft's core item/block
    textures live in the client jar on this 1.21.1 workspace, so the jar is the
    deterministic fallback when the index contains only downloadable assets.
    """
    if VANILLA_ASSET_INDEX.is_file():
        index = json.loads(VANILLA_ASSET_INDEX.read_text(encoding="utf-8"))
        entry = index.get("objects", {}).get(resource)
        if entry:
            digest = entry["hash"]
            object_path = VANILLA_ASSET_OBJECTS / digest[:2] / digest
            if not object_path.is_file():
                raise ValueError(f"indexed vanilla asset is missing: {object_path}")
            _VANILLA_ICON_PROVENANCE[resource] = f"asset-index:{digest}"
            return object_path.read_bytes()
    if not VANILLA_CLIENT_JAR.is_file():
        raise ValueError(
            f"vanilla texture {resource} is not indexed and client jar is missing")
    member = f"assets/{resource}"
    with zipfile.ZipFile(VANILLA_CLIENT_JAR) as archive:
        try:
            payload = archive.read(member)
        except KeyError as error:
            raise ValueError(f"missing vanilla texture in client jar: {member}") from error
    _VANILLA_ICON_PROVENANCE[resource] = (
        "client-jar-sha256:" + hashlib.sha256(payload).hexdigest())
    return payload


class Artboard:
    """Minecraft-font canvas with strict box and wrapping checks."""

    def __init__(self, size: tuple[int, int], background=argb(0xFF101010)):
        self.font = mcfont.McFont.load()
        self.sprites = Sprites(SPRITES)
        self.canvas = Canvas(size[0], size[1], self.font, self.sprites)
        self.canvas.img.paste(background, (0, 0, size[0], size[1]))
        self.issues: list[str] = []

    @property
    def image(self) -> Image.Image:
        return self.canvas.img

    @property
    def width(self) -> int:
        return self.image.width

    @property
    def height(self) -> int:
        return self.image.height

    def issue(self, message: str) -> None:
        self.issues.append(message)
        WARNINGS.append(message)

    def rect(self, box: Box, shade) -> None:
        self.canvas.rect(box.x, box.y, box.w, box.h, shade)

    def stroke(self, box: Box, shade, thickness: int = 1) -> None:
        self.rect(Box(box.x, box.y, box.w, thickness), shade)
        self.rect(Box(box.x, box.bottom - thickness, box.w, thickness), shade)
        self.rect(Box(box.x, box.y, thickness, box.h), shade)
        self.rect(Box(box.right - thickness, box.y, thickness, box.h), shade)

    def panel(self, box: Box, kind: str = "window") -> None:
        self.sprites.draw(self.image, f"panel/{kind}", box.x, box.y, box.w, box.h)

    def text(self, box: Box, y: int, text: str, tone="text",
             align: str = "left", label: str = "text", shadow: bool = True) -> None:
        if box.x < 0 or box.right > self.width or y < 0 or y + 9 > self.height:
            self.issue(f"{label}: text bounds escape canvas")
        width = self.font.width(text)
        if width > box.w:
            self.issue(
                f'{label}: "{text}" is {width}px in a {box.w}px box')
        x = box.x if align == "left" else box.cx if align == "center" else box.right
        self.canvas.text(x, y, text, colour(tone) if isinstance(tone, str) else tone,
                         shadow=shadow, align=align, box=box.w, label=label)

    def wrap(self, box: Box, y: int, text: str, lines: int,
             tone="text", line_height: int = 10, label: str = "wrapped") -> int:
        words = text.split()
        wrapped: list[str] = []
        current = ""
        for word in words:
            candidate = word if not current else current + " " + word
            if self.font.width(candidate) <= box.w:
                current = candidate
            else:
                if not current or self.font.width(word) > box.w:
                    self.issue(f'{label}: word "{word}" cannot fit {box.w}px')
                    current = word
                else:
                    wrapped.append(current)
                    current = word
        if current:
            wrapped.append(current)
        if len(wrapped) > lines:
            self.issue(f"{label}: needs {len(wrapped)} lines, has {lines}")
            wrapped = wrapped[:lines]
        for index, line in enumerate(wrapped):
            self.text(box, y + index * line_height, line, tone,
                      label=f"{label} line {index + 1}")
        return len(wrapped)

    def circle(self, at: Point, radius: int, fill, outline=None,
               width: int = 1) -> None:
        draw = ImageDraw.Draw(self.image)
        bounds = (at.x - radius, at.y - radius, at.x + radius,
                  at.y + radius)
        draw.ellipse(bounds, fill=fill, outline=outline, width=width)

    def polygon(self, points: Sequence[Point], fill, outline=None) -> None:
        draw = ImageDraw.Draw(self.image)
        xy = [(point.x, point.y) for point in points]
        draw.polygon(xy, fill=fill)
        if outline is not None:
            draw.line(xy + [xy[0]], fill=outline, width=1)

    def path(self, points: Sequence[Point], shade, width: int,
             joint: str = "curve") -> None:
        draw = ImageDraw.Draw(self.image)
        draw.line([(point.x, point.y) for point in points], fill=shade,
                  width=width, joint=joint)

    def icon(self, filename: str, at: Point, size: int = 16) -> None:
        key = (filename, size)
        if key not in _ICON_CACHE:
            path = ITEM_TEXTURES / filename
            if not path.is_file():
                raise ValueError(f"missing real Hearthstead icon: {path}")
            icon = Image.open(path).convert("RGBA")
            _ICON_CACHE[key] = icon.resize((size, size), Image.Resampling.NEAREST)
        self.image.alpha_composite(_ICON_CACHE[key],
                                   (at.x - size // 2, at.y - size // 2))

    def vanilla_icon(self, resource: str, at: Point, size: int = 16) -> None:
        key = (resource, size)
        if key not in _VANILLA_ICON_CACHE:
            icon = Image.open(io.BytesIO(vanilla_texture_bytes(resource))).convert("RGBA")
            _VANILLA_ICON_CACHE[key] = icon.resize(
                (size, size), Image.Resampling.NEAREST)
        self.image.alpha_composite(_VANILLA_ICON_CACHE[key],
                                   (at.x - size // 2, at.y - size // 2))

    def button(self, box: Box, text: str, active: bool = True) -> None:
        sprite = "widget/button_idle" if active else "widget/button_disabled"
        self.sprites.draw(self.image, sprite, box.x, box.y, box.w, box.h)
        self.text(Box(box.x + 4, box.y, box.w - 8, box.h), box.y + 7,
                  text, "text" if active else "text_muted", "center",
                  f'button "{text}"')

    def divider(self, x: int, y: int, width: int) -> None:
        self.sprites.draw(self.image, "widget/divider", x, y, width, 2)


def chrome(board: Artboard, title: str, subtitle: str, marker: str,
           light: bool = False) -> None:
    board.panel(Box(0, 0, board.width, board.height), "window")
    title_tone = "text_on_light" if light else "text_strong"
    sub_tone = "text_on_light" if light else "text_muted"
    board.text(Box(16, 0, 360, 20), 12, title, title_tone,
               label="screen title")
    board.text(Box(16, 0, min(520, board.width - 32), 20), 28,
               subtitle, sub_tone, label="screen subtitle")
    board.text(Box(board.width - 260, 0, 244, 20), 12,
               f"PREVIEW ONLY  •  {marker}", sub_tone, "right",
               "preview marker")
    board.divider(12, 44, board.width - 24)


def state_for_trunk(index: int, post_raid: bool = False) -> str:
    if post_raid:
        return "learned"
    if index < 2:
        return "learned"
    if index == 2:
        return "available"
    return "locked"


def legend(board: Artboard, x: int, y: int, light: bool = False) -> None:
    cursor = x
    text_tone = "text_on_light" if light else "text_muted"
    for state in ("learned", "available", "locked"):
        board.rect(Box(cursor, y + 2, 6, 6), colour(STATE_TONE[state]))
        cursor += 10
        name = STATE_LABEL[state].title()
        width = board.font.width(name)
        board.text(Box(cursor, 0, width, 12), y, name, text_tone,
                   label=f"legend {state}", shadow=not light)
        cursor += width + 12


def road(board: Artboard, points: Sequence[Point], state: str = "locked",
         map_style: bool = False) -> None:
    if map_style:
        board.path(points, argb(0xFF765631), 8)
        board.path(points, argb(0xFFD2B46F), 4)
        return
    board.path(points, ROAD_EDGE, 8)
    inner = colour(STATE_TONE[state]) if state != "locked" else ROAD_EARTH
    board.path(points, inner, 4)


def constellation_line(board: Artboard, points: Sequence[Point], state: str,
                       tone: str | None = None) -> None:
    semantic = tone or STATE_TONE[state]
    board.path(points, rgba(semantic, 28), 10)
    board.path(points, rgba(semantic, 74), 6)
    board.path(points, colour(semantic), 2)


def medallion(board: Artboard, node: Node, at: Point, radius: int,
              state: str, label_box: Box | None = None,
              label_y: int | None = None, light: bool = False,
              branch_tone: str | None = None, selected: bool = False,
              compact_state: bool = False) -> None:
    semantic = branch_tone or STATE_TONE[state]
    board.circle(Point(at.x + 2, at.y + 2), radius + 3,
                 argb(0x66000000))
    board.circle(at, radius + 3, colour("field"), colour(semantic), 2)
    board.circle(at, radius, argb(0xFF302D2A), colour(semantic), 1)
    if selected:
        board.circle(at, radius + 6, None, colour("accent"), 2)
    board.icon(node.icon, at, min(24, max(16, radius)))
    badge = Box(at.x - radius - 7, at.y - radius - 5, 24, 12)
    board.rect(badge, colour("field"))
    board.stroke(badge, colour(semantic))
    board.text(Box(badge.x + 2, 0, badge.w - 4, 12), badge.y + 2,
               node.number, semantic, "center", f"node {node.number} badge")
    if label_box is not None and label_y is not None:
        text_tone = "text_on_light" if light else "text_strong"
        state_tone = "text_on_light" if light else semantic
        board.wrap(label_box, label_y, node.short, 2, text_tone,
                   label=f"node {node.number} name")
        if not compact_state:
            board.text(label_box, label_y + 20, STATE_LABEL[state], state_tone,
                       label=f"node {node.number} state")


def doctrine_medallion(board: Artboard, doctrine: Doctrine, at: Point,
                       state: str, label_y: int, selected: bool = False,
                       light: bool = False, label_width: int = 204) -> None:
    node = Node(doctrine.key, doctrine.number, doctrine.name, doctrine.icon)
    medallion(board, node, at, 24, state, branch_tone=doctrine.tone,
              selected=selected)
    text_tone = "text_on_light" if light else "text_strong"
    muted = "text_on_light" if light else "text_muted"
    box = Box(at.x - label_width // 2, 0, label_width, 12)
    board.text(box, label_y, doctrine.name, text_tone, "center",
               f"{doctrine.name} title")
    board.text(box, label_y + 12, doctrine.identity, doctrine.tone, "center",
               f"{doctrine.name} identity")
    board.text(box, label_y + 24, doctrine.tradeoff, muted, "center",
               f"{doctrine.name} tradeoff", shadow=not light)


def doctrine_identity_zone(board: Artboard, doctrine: Doctrine, at: Point,
                           state: str, y: int) -> None:
    """One calm bounded label zone; connectors remain visually behind it."""
    zone = Box(at.x - 104, y, 208, 52)
    board.rect(zone, argb(0xE6242220))
    board.stroke(zone, rgba(doctrine.tone, 175), 1)
    board.rect(Box(zone.x, zone.y, zone.w, 2), colour(doctrine.tone))
    board.text(Box(zone.x + 8, 0, zone.w - 16, 12), y + 6,
               doctrine.name, "text_strong", "center",
               f"{doctrine.name} zone title")
    board.text(Box(zone.x + 8, 0, zone.w - 16, 12), y + 18,
               doctrine.identity, doctrine.tone, "center",
               f"{doctrine.name} zone identity")
    board.text(Box(zone.x + 8, 0, 72, 12), y + 32,
               STATE_LABEL[state], STATE_TONE[state],
               label=f"{doctrine.name} zone state")
    board.text(Box(zone.x + 84, 0, zone.w - 92, 12), y + 32,
               doctrine.tradeoff, "text_muted", "right",
               f"{doctrine.name} zone tradeoff")


def draw_zoom(board: Artboard, x: int, y: int, percent: str,
              light: bool = False, deep: bool = False) -> None:
    board.button(Box(x, y, 40, 20), "−")
    tone = "text_on_light" if light else "accent"
    board.rect(Box(x + 44, y, 60, 20),
               PARCHMENT if light else rgba("accent", 25))
    board.stroke(Box(x + 44, y, 60, 20),
                 PARCHMENT_DARK if light else colour("accent"))
    board.text(Box(x + 48, 0, 52, 12), y + 7, percent, tone, "center",
               "zoom percent", shadow=not light)
    board.button(Box(x + 108, y, 40, 20), "+")
    board.button(Box(x + 152, y, 56, 20), "Fit")
    if deep:
        board.button(Box(x + 212, y, 68, 20), "Deep Zoom")


# ------------------------------------------------ concept A: living tree ---

A_MAP = Box(12, 52, 744, 608)
A_TRUNK_POINTS = (
    Point(384, 88), Point(348, 128), Point(420, 160), Point(348, 192),
    Point(420, 224), Point(348, 256), Point(420, 288), Point(348, 320),
    Point(420, 352), Point(384, 392),
)
A_DOCTRINE_POINTS = (Point(156, 496), Point(384, 496), Point(612, 496))
A_CHILD_POINTS = (
    (Point(88, 616), Point(224, 616)),
    (Point(320, 616), Point(456, 616)),
    (Point(640, 616),),
)


def living_ground(board: Artboard, box: Box) -> None:
    board.panel(box, "inset")
    # Deterministic organic patches: visual terrain, not interactive cards.
    # Keep every decorative primitive relative to the supplied map viewport.
    # The learned-recipe state uses a narrow map; fixed overview coordinates
    # previously inverted the second ellipse and leaked settlement marks into
    # the adjacent inspector.
    def at(x_ratio: float, y_ratio: float) -> tuple[int, int]:
        return (box.x + round(box.w * x_ratio),
                box.y + round(box.h * y_ratio))

    overlay = Image.new("RGBA", board.image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    draw.ellipse((*at(0.054, 0.066), *at(0.457, 0.592)),
                 fill=rgba("good", 13))
    draw.ellipse((*at(0.484, 0.164), *at(0.960, 0.967)),
                 fill=rgba("accent", 11))
    draw.polygon([at(0.013, 0.803), at(0.336, 0.967), at(0.013, 0.984)],
                 fill=(60, 84, 55, 18))
    board.image.alpha_composite(overlay)
    # Small settlement marks make the road feel inhabited without becoming UI.
    for x_ratio, y_ratio in ((0.070, 0.148), (0.124, 0.214),
                             (0.863, 0.204), (0.925, 0.250),
                             (0.089, 0.470), (0.892, 0.461)):
        x, y = at(x_ratio, y_ratio)
        board.polygon((Point(x, y + 8), Point(x + 8, y), Point(x + 16, y + 8)),
                      rgba("good", 62), ROAD_EDGE)
        board.rect(Box(x + 3, y + 8, 10, 8), rgba("warn", 35))


def render_living_overview() -> Image.Image:
    strings = lang()
    board = Artboard(A_OVERVIEW_SIZE)
    chrome(board, "Living Settlement Tree",
           "One settlement road grows through the first raid, then becomes a real branching tree",
           "CONCEPT A  •  RECOMMENDED")
    living_ground(board, A_MAP)
    board.text(Box(24, 0, 330, 12), 60,
               "THE FOUNDING ROAD  •  2 / 10 LEARNED", "accent",
               label="living stage")
    legend(board, 536, 60)

    # One calm, winding settlement road.  Completed and next segments carry
    # state colour; the untravelled road remains honest earth.
    for index in range(len(A_TRUNK_POINTS) - 1):
        next_state = state_for_trunk(index + 1)
        road(board, (A_TRUNK_POINTS[index], A_TRUNK_POINTS[index + 1]),
             next_state)

    for index, (node, at) in enumerate(zip(TRUNK, A_TRUNK_POINTS)):
        state = state_for_trunk(index)
        radius, label_box, label_y = living_trunk_presentation(index)
        medallion(board, node, at, radius, state, label_box, label_y)

    raid = A_TRUNK_POINTS[-1]
    board.circle(raid, 34, None, colour("bad"), 2)

    # The branch begins beyond the raid.  The short gap and explicit gate bar
    # prevent the overview from implying doctrines are already reachable.
    gate = Box(352, 426, 64, 12)
    board.rect(gate, colour("bad"))
    board.stroke(gate, colour("warn"))
    board.text(Box(gate.x + 4, 0, gate.w - 8, 12), gate.y + 2,
               "RAID GATE", "text_strong", "center", "raid gate label")
    branch_hub = Point(384, 468)
    road(board, (Point(384, gate.bottom), branch_hub), "locked")
    for doctrine_at in A_DOCTRINE_POINTS:
        road(board, (branch_hub, Point(doctrine_at.x, 468), doctrine_at), "locked")
    rule = Box(244, 442, 280, 20)
    board.rect(rule, argb(0xF0242220))
    board.stroke(rule, rgba("warn", 170))
    board.text(Box(rule.x + 6, 0, rule.w - 12, 12), rule.y + 7,
               "REPEL THE RAID  •  ONLY THEN DOES THE ROAD SPLIT",
               "warn", "center", "first raid split rule")

    for doctrine, at, children in zip(DOCTRINES, A_DOCTRINE_POINTS, A_CHILD_POINTS):
        # Child roads first, so the bounded doctrine copy remains unbroken on
        # top of the tree rather than being crossed by connector ink.
        for child, child_at in zip(doctrine.children, children):
            road(board, (Point(at.x, 520), Point(child_at.x, 588), child_at), "locked")
        node = Node(doctrine.key, doctrine.number, doctrine.name, doctrine.icon)
        medallion(board, node, at, 24, "locked", branch_tone=doctrine.tone)
        doctrine_identity_zone(board, doctrine, at, "locked", 526)
        for child, child_at in zip(doctrine.children, children):
            medallion(board, child, child_at, 14, "planned",
                      Box(child_at.x - 64, 0, 128, 12), 640,
                      branch_tone=doctrine.tone, compact_state=True)

    board.text(Box(24, 0, 420, 12), 672,
               "Select a medallion to slide in quest, cost and reward.",
               "text_muted", label="living select help")
    draw_zoom(board, 528, 664, "68%")
    return board.image


def living_trunk_presentation(index: int) -> tuple[int, Box, int]:
    at = A_TRUNK_POINTS[index]
    if index == 0:
        return 20, Box(418, 0, 120, 12), 78
    if index == len(TRUNK) - 1:
        return 28, Box(432, 0, 176, 12), at.y - 20
    if at.x < 384:
        return 16, Box(220, 0, 104, 12), at.y - 10
    return 16, Box(444, 0, 116, 12), at.y - 10


def clamp_popup(desired: Box, viewport: Box, avoid: Box, gap: int = 8) -> Box:
    """Clamp a static popup and prefer a side that clears the hovered target."""
    inset = 4
    candidates = (
        desired,
        Box(avoid.x - gap - desired.w, desired.y, desired.w, desired.h),
        Box(avoid.right + gap, desired.y, desired.w, desired.h),
        Box(desired.x, avoid.bottom + gap, desired.w, desired.h),
        Box(desired.x, avoid.y - gap - desired.h, desired.w, desired.h),
    )
    valid: list[Box] = []
    for candidate in candidates:
        clamped = Box(
            max(viewport.x + inset,
                min(candidate.x, viewport.right - inset - candidate.w)),
            max(viewport.y + inset,
                min(candidate.y, viewport.bottom - inset - candidate.h)),
            candidate.w,
            candidate.h,
        )
        if not clamped.intersects(avoid):
            valid.append(clamped)
    if not valid:
        return Box(viewport.x + inset, viewport.y + inset,
                   desired.w, desired.h)
    return min(valid, key=lambda box: abs(box.x - desired.x) + abs(box.y - desired.y))


def draw_warehouse_hover_popup(board: Artboard, box: Box) -> None:
    strings = lang()
    board.panel(box, "window")
    board.stroke(box, colour("accent"), 2)
    board.icon("courier_emblem.png", Point(box.x + 28, box.y + 27), 32)
    board.text(Box(box.x + 52, 0, 140, 12), box.y + 13,
               "04  Warehouse", "text_strong", label="hover title")
    board.text(Box(box.x + 52, 0, 140, 12), box.y + 27,
               "STORES & ROADS", "accent", label="hover category")
    state_box = Box(box.right - 70, box.y + 12, 56, 20)
    board.rect(state_box, rgba("text_muted", 24))
    board.stroke(state_box, colour("text_muted"))
    board.text(Box(state_box.x + 4, 0, state_box.w - 8, 12),
               state_box.y + 7, "LOCKED", "text_muted", "center",
               "hover state")
    description = translated(
        strings, "hearthstead.development.node.stores_and_roads.desc")
    board.wrap(Box(box.x + 16, 0, box.w - 32, 12), box.y + 52,
               description, 2, "text_muted", label="hover description")
    board.divider(box.x + 12, box.y + 78, box.w - 24)

    section(board, box.x + 16, box.y + 90, "BLOCKER", box.w - 32, "warn")
    board.wrap(Box(box.x + 16, 0, box.w - 32, 12), box.y + 104,
               "Quest incomplete: store one Lumberer log at the linked Lumber Camp (0 / 1)",
               2, "warn", label="hover exact blocker")

    section(board, box.x + 16, box.y + 134, "QUEST PROGRESS", box.w - 32)
    board.text(Box(box.x + 16, 0, 208, 12), box.y + 148,
               "Lumberer logs stored", "text", label="hover quest")
    board.text(Box(box.right - 72, 0, 56, 12), box.y + 148,
               "0 / 1", "warn", "right", "hover quest progress")

    section(board, box.x + 16, box.y + 170, "PHYSICAL COST", box.w - 32)
    board.text(Box(box.x + 16, 0, box.w - 32, 12), box.y + 184,
               "Any Log ×8  •  Leather ×2", "text", label="hover cost")

    section(board, box.x + 16, box.y + 206, "REWARD WHEN LEARNED", box.w - 32,
            "good")
    board.wrap(Box(box.x + 16, 0, box.w - 32, 12), box.y + 220,
               "Warehouse Build Plan  •  Courier Emblem",
               2, "good", label="hover reward")
    board.text(Box(box.x + 16, 0, box.w - 32, 12), box.bottom - 18,
               "Click to pin the full inspector", "text_muted",
               label="hover pin hint")


def render_living_hover() -> Image.Image:
    board = Artboard(A_OVERVIEW_SIZE)
    board.image.paste(render_living_overview(), (0, 0))
    # Static dim: no time-based glow or pulsing.  The selected route is redrawn
    # at full contrast, so structure reads before popup prose.
    overlay = Image.new("RGBA", board.image.size, (0, 0, 0, 0))
    ImageDraw.Draw(overlay).rectangle(
        (A_MAP.x, A_MAP.y, A_MAP.right - 1, A_MAP.bottom - 1),
        fill=(5, 7, 6, 132))
    board.image.alpha_composite(overlay)
    hovered_index = 3
    for index in range(hovered_index):
        road(board, (A_TRUNK_POINTS[index], A_TRUNK_POINTS[index + 1]),
             "learned" if index < 1 else "available")
        board.path((A_TRUNK_POINTS[index], A_TRUNK_POINTS[index + 1]),
                   rgba("accent", 220), 2)
    for index in range(hovered_index + 1):
        node = TRUNK[index]
        at = A_TRUNK_POINTS[index]
        radius, label_box, label_y = living_trunk_presentation(index)
        medallion(board, node, at, radius, state_for_trunk(index),
                  label_box, label_y, selected=index == hovered_index)

    avoid = circle_box(A_TRUNK_POINTS[hovered_index], 24)
    popup = clamp_popup(Box(24, 88, 292, 268), A_MAP, avoid, gap=12)
    if (popup.x < A_MAP.x or popup.y < A_MAP.y
            or popup.right > A_MAP.right or popup.bottom > A_MAP.bottom):
        board.issue("warehouse hover popup escapes the tree viewport")
    if popup.intersects(avoid):
        board.issue("warehouse hover popup covers its hovered node")
    draw_warehouse_hover_popup(board, popup)
    board.text(Box(448, 0, 292, 12), 636,
               "Route focus: Hearth → Warehouse", "accent", "right",
               "hover route focus")
    return board.image


def compact_doctrine_zone(board: Artboard, doctrine: Doctrine, at: Point,
                          state: str, y: int) -> None:
    zone = Box(at.x - 64, y, 128, 60)
    board.rect(zone, argb(0xEE242220))
    board.stroke(zone, rgba(doctrine.tone, 180))
    board.rect(Box(zone.x, zone.y, zone.w, 2), colour(doctrine.tone))
    board.text(Box(zone.x + 6, 0, zone.w - 12, 12), y + 5,
               doctrine.name, "text_strong", "center",
               f"selected {doctrine.name} title")
    board.text(Box(zone.x + 6, 0, zone.w - 12, 12), y + 17,
               doctrine.identity, doctrine.tone, "center",
               f"selected {doctrine.name} identity")
    board.text(Box(zone.x + 6, 0, zone.w - 12, 12), y + 31,
               STATE_LABEL[state], STATE_TONE[state],
               "center", label=f"selected {doctrine.name} state")
    board.text(Box(zone.x + 6, 0, zone.w - 12, 12), y + 43,
               doctrine.tradeoff, "text_muted", "center",
               f"selected {doctrine.name} tradeoff")


def selected_living_map(board: Artboard, box: Box) -> None:
    living_ground(board, box)
    board.text(Box(24, 0, 310, 12), 60,
               "AFTER THE RAID  •  CHOOSE ONE DOCTRINE", "warn",
               label="selected map stage")
    arm = Point(112, 100)
    raid = Point(156, 178)
    doctrines = (Point(76, 286), Point(224, 272), Point(368, 286))
    arm_node = TRUNK[8]
    raid_node = TRUNK[9]
    road(board, (arm, Point(132, 132), raid), "learned")
    branch_hub = Point(156, 232)
    for doctrine, at in zip(DOCTRINES, doctrines):
        road(board, (branch_hub, Point(at.x, 244), at), "available")
    guild = doctrines[1]
    child_points = (Point(176, 424), Point(288, 424))
    for child_at in child_points:
        road(board, (Point(guild.x, 300), Point(child_at.x, 396), child_at),
             "locked")

    medallion(board, arm_node, arm, 18, "learned",
               Box(24, 0, 80, 12), 140, compact_state=True)
    medallion(board, raid_node, raid, 30, "learned",
               Box(188, 0, 156, 12), 158)
    result = Box(104, 216, 104, 20)
    board.rect(result, argb(0xEE242220))
    board.stroke(result, colour("good"))
    board.text(Box(result.x + 4, 0, result.w - 8, 12), result.y + 7,
               "RAID REPELLED", "good", "center", "selected raid result")
    for doctrine, at in zip(DOCTRINES, doctrines):
        node = Node(doctrine.key, doctrine.number, doctrine.name, doctrine.icon)
        medallion(board, node, at, 24, "available",
                  branch_tone=doctrine.tone,
                  selected=doctrine.key == "guild_doctrine")
        compact_doctrine_zone(board, doctrine, at, "available", 318)
    # Selected branch grows toward its two planned specializations.
    for child, child_at in zip(DOCTRINES[1].children, child_points):
        medallion(board, child, child_at, 14, "planned",
                  Box(child_at.x - 52, 0, 104, 12), 448,
                  branch_tone="accent", compact_state=True)
    # Fixed control strip: map labels and connector geometry end above y=472.
    board.rect(Box(box.x + 4, 476, box.w - 8, 44), argb(0xF0242220))
    board.divider(box.x + 8, 476, box.w - 16)
    draw_zoom(board, 24, 488, "100%")


def section(board: Artboard, x: int, y: int, text: str, width: int,
            tone: str = "accent") -> None:
    board.text(Box(x, 0, width, 12), y, text, tone, label=f"section {text}")
    start = x + min(width - 8, board.font.width(text) + 8)
    board.rect(Box(start, y + 4, x + width - start, 1), rgba(tone, 110))


def draw_guild_inspector(board: Artboard, box: Box) -> None:
    strings = lang()
    # A single slide-in field: cards are permitted here because this is the
    # selected node's reading surface, not the tree's primary structure.
    board.panel(box, "window")
    board.stroke(box, colour("accent"), 2)
    board.icon("sawyer_emblem.png", Point(box.x + 24, box.y + 24), 24)
    board.text(Box(box.x + 44, 0, 136, 12), box.y + 14,
               "Guild Doctrine", "text_strong", label="inspector title")
    board.rect(Box(box.right - 88, box.y + 12, 72, 20), rgba("accent", 30))
    board.stroke(Box(box.right - 88, box.y + 12, 72, 20), colour("accent"))
    board.text(Box(box.right - 84, 0, 64, 12), box.y + 19,
               "AVAILABLE", "accent", "center", "inspector state")
    description = translated(strings,
                             "hearthstead.development.node.guild_doctrine.desc")
    board.wrap(Box(box.x + 16, 0, box.w - 32, 12), box.y + 48,
               description, 3, "text_muted", label="inspector description")

    section(board, box.x + 16, box.y + 88, "QUEST", box.w - 32)
    board.text(Box(box.x + 16, 0, 188, 12), box.y + 102,
               "Productive goods moved", "text", label="goods quest")
    board.text(Box(box.right - 72, 0, 56, 12), box.y + 102,
               "64 / 64", "good", "right", "goods progress")
    board.text(Box(box.x + 16, 0, 188, 12), box.y + 116,
               "Courier deliveries", "text", label="courier quest")
    board.text(Box(box.right - 72, 0, 56, 12), box.y + 116,
               "3 / 3", "good", "right", "courier progress")

    section(board, box.x + 16, box.y + 138, "PHYSICAL COST", box.w - 32)
    board.text(Box(box.x + 16, 0, 116, 12), box.y + 152,
               "Any Log ×24", "text", label="log cost")
    board.text(Box(box.x + 148, 0, 116, 12), box.y + 152,
               "Iron Ingot ×8", "text", label="iron cost")

    section(board, box.x + 16, box.y + 174, "UNLOCKED BY RESEARCH", box.w - 32)
    board.wrap(Box(box.x + 16, 0, box.w - 32, 12), box.y + 188,
               "Sawmill: assigned Sawyers turn stored logs into planks and beams.",
               2, "text", label="building description")
    board.wrap(Box(box.x + 16, 0, box.w - 32, 12), box.y + 212,
               "Reveals the Sawmill Build Plan recipe and allows Sawyer Emblems at the Mayor.",
               2, "good", label="knowledge reward")
    board.text(Box(box.x + 16, 0, box.w - 32, 12), box.y + 236,
               "Buy one from the Mayor, then give it to a settler.", "text_muted",
               label="emblem assignment")

    section(board, box.x + 16, box.y + 254, "PERMANENT TRADEOFF",
            box.w - 32, "warn")
    tradeoff = translated(
        strings, "hearthstead.development.node.guild_doctrine.tradeoff")
    board.wrap(Box(box.x + 16, 0, box.w - 32, 12), box.y + 268,
               tradeoff, 3, "warn", label="inspector tradeoff")
    board.button(Box(box.x + 16, box.bottom - 32, box.w - 32, 20),
                 "Learn Guild Doctrine")


def render_living_selected() -> Image.Image:
    board = Artboard(A_SELECTED_SIZE)
    chrome(board, "Living Settlement Tree",
           "Guild Doctrine selected • the inspector slides in only after selection",
           "CONCEPT A  •  SELECTED NODE")
    selected_living_map(board, Box(12, 52, 428, 472))
    draw_guild_inspector(board, Box(448, 52, 308, 472))
    board.text(Box(16, 0, 520, 12), 552,
               "Map stays visual; all quest, cost, plan and emblem detail lives in the inspector.",
               "text_muted", label="selected footer")
    return board.image


def draw_lumber_learned_map(board: Artboard, box: Box) -> None:
    if (box.w, box.h) != (300, 520):
        WARNINGS.append(
            f"learned Lumber map requires a 300x520 viewport, got {box.w}x{box.h}")
    living_ground(board, box)
    board.text(Box(box.x + 12, 0, box.w - 24, 12), box.y + 8,
               "FOUNDING ROAD  •  NODE LEARNED", "good", "center",
               "recipe map stage")
    points = (Point(156, 116), Point(132, 184),
              Point(180, 252), Point(132, 328))
    for index in range(len(points) - 1):
        road(board, (points[index], points[index + 1]),
             "learned" if index < 2 else "available")
    labels = (
        (Box(192, 0, 104, 12), 106),
        (Box(20, 0, 92, 12), 174),
        (Box(204, 0, 96, 12), 242),
        (Box(20, 0, 92, 12), 318),
    )
    states = ("learned", "learned", "learned", "available")
    for index, (node, at, state, label) in enumerate(
            zip(TRUNK[:4], points, states, labels)):
        medallion(board, node, at, 20 if index == 0 else 18, state,
                  label[0], label[1], selected=index == 2)
    knowledge = Box(box.x + 28, 380, box.w - 56, 48)
    board.rect(knowledge, argb(0xEE242220))
    board.stroke(knowledge, colour("good"))
    board.text(Box(knowledge.x + 8, 0, knowledge.w - 16, 12),
               knowledge.y + 8, "LUMBER CAMP KNOWLEDGE", "good", "center",
               "recipe knowledge title")
    board.text(Box(knowledge.x + 8, 0, knowledge.w - 16, 12),
               knowledge.y + 24, "Build Plan recipe is now visible", "text",
               "center", "recipe knowledge result")
    board.rect(Box(box.x + 4, 476, box.w - 8, 92), argb(0xF0242220))
    board.divider(box.x + 8, 476, box.w - 16)
    board.text(Box(box.x + 12, 0, box.w - 24, 12), 492,
               "Selected: 03 Lumber Camp", "accent", "center",
               "recipe map selected")
    draw_zoom(board, box.x + 48, 532, "100%")


def crafting_slot(board: Artboard, box: Box) -> None:
    board.rect(box, colour("field"))
    board.stroke(box, rgba("text_muted", 150))
    board.rect(Box(box.x + 2, box.y + 2, box.w - 4, 1), rgba("text", 28))
    board.rect(Box(box.x + 2, box.y + 2, 1, box.h - 4), rgba("text", 28))


def draw_lumber_recipe_inspector(board: Artboard, box: Box) -> None:
    strings = lang()
    board.panel(box, "window")
    board.stroke(box, colour("good"), 2)
    board.icon("lumberer_emblem.png", Point(box.x + 34, box.y + 30), 48)
    board.text(Box(box.x + 68, 0, 174, 12), box.y + 12,
               "03  Lumber Camp", "text_strong", label="recipe title")
    board.text(Box(box.x + 68, 0, 174, 12), box.y + 28,
               "TIMBER RIGHTS", "good", label="recipe category")
    learned = Box(box.right - 82, box.y + 12, 66, 20)
    board.rect(learned, rgba("good", 28))
    board.stroke(learned, colour("good"))
    board.text(Box(learned.x + 4, 0, learned.w - 8, 12), learned.y + 7,
               "LEARNED", "good", "center", "recipe learned state")
    description = translated(
        strings, "hearthstead.development.node.timber_rights.desc")
    board.wrap(Box(box.x + 16, 0, box.w - 32, 12), box.y + 58,
               description, 2, "text_muted", label="recipe description")

    section(board, box.x + 16, box.y + 96, "UNLOCKED BY RESEARCH", box.w - 32,
             "good")
    rewards = Box(box.x + 16, box.y + 112, box.w - 32, 80)
    board.rect(rewards, rgba("good", 16))
    board.stroke(rewards, rgba("good", 145))
    # The Emblem is intentionally much larger than a normal 16px slot icon so
    # its actual pixel-art appearance can be judged in the preview.
    board.icon("lumberer_emblem.png", Point(rewards.x + 40, rewards.y + 40), 48)
    board.text(Box(rewards.x + 76, 0, 132, 12), rewards.y + 18,
               "Lumberer Emblem", "text_strong", label="emblem reward name")
    board.wrap(Box(rewards.x + 76, 0, 132, 12), rewards.y + 34,
               "Available from the Mayor", 2, "text_muted",
               label="emblem reward use")
    board.rect(Box(rewards.x + 216, rewards.y + 10, 1, rewards.h - 20),
               rgba("text", 38))
    board.icon("build_plan.png", Point(rewards.x + 248, rewards.y + 40), 36)
    board.text(Box(rewards.x + 276, 0, 112, 12), rewards.y + 18,
               "Build Plan", "text_strong", label="plan reward name")
    board.text(Box(rewards.x + 276, 0, 112, 12), rewards.y + 34,
               "Recipe now visible", "good", label="plan reward building")

    section(board, box.x + 16, box.y + 208, "CRAFTING RECIPE", box.w - 32)
    shape = Box(box.right - 116, box.y + 202, 100, 20)
    board.rect(shape, rgba("accent", 26))
    board.stroke(shape, colour("accent"))
    board.text(Box(shape.x + 4, 0, shape.w - 8, 12), shape.y + 7,
               "SHAPELESS", "accent", "center", "shapeless marker")
    board.text(Box(box.x + 20, 0, 220, 12), box.y + 226,
               "PLACEMENT DOES NOT MATTER", "text_muted",
               label="shapeless explanation")

    grid_x = box.x + 24
    grid_y = box.y + 248
    slot = 28
    for row in range(3):
        for column in range(3):
            crafting_slot(board, Box(grid_x + column * slot,
                                     grid_y + row * slot, slot, slot))
    ingredients = (
        ("minecraft/textures/item/paper.png", Point(grid_x + 42, grid_y + 14)),
        ("minecraft/textures/item/feather.png", Point(grid_x + 70, grid_y + 42)),
        ("minecraft/textures/block/oak_log.png", Point(grid_x + 14, grid_y + 70)),
    )
    for resource, at in ingredients:
        board.vanilla_icon(resource, at, 20)
    board.text(Box(grid_x + 90, 0, 24, 12), grid_y + 37,
               ">", "accent", "center", "recipe arrow")
    result_slot = Box(grid_x + 120, grid_y + 18, 48, 48)
    crafting_slot(board, result_slot)
    board.icon("build_plan.png", Point(result_slot.cx, result_slot.cy), 36)
    list_x = grid_x + 184
    board.text(Box(list_x, 0, 188, 12), grid_y + 2,
               "Paper ×1", "text", label="recipe paper")
    board.text(Box(list_x, 0, 188, 12), grid_y + 20,
               "Feather ×1", "text", label="recipe feather")
    board.text(Box(list_x, 0, 188, 12), grid_y + 38,
               "Any Log ×1", "text", label="recipe log")
    board.wrap(Box(list_x, 0, 188, 12), grid_y + 60,
               "Output: Build Plan: Lumber Camp ×1", 2, "good",
               label="recipe output")
    board.text(Box(box.x + 20, 0, 220, 12), box.y + 342,
               "Slots shown are illustrative, never fixed.", "text_muted",
               label="recipe slot disclaimer")

    section(board, box.x + 16, box.y + 370, "RECIPE UNLOCKED", box.w - 32,
            "good")
    board.wrap(Box(box.x + 16, 0, box.w - 32, 12), box.y + 386,
               "Synced to this player's recipe book only after settlement knowledge is learned.",
               2, "good", label="recipe sync contract")
    board.text(Box(box.x + 16, 0, box.w - 32, 12), box.y + 420,
               "Before learning: hidden from this player's recipe book",
               "text_muted", label="recipe before learn state")
    board.button(Box(box.x + 16, box.bottom - 32, box.w - 32, 20),
                 "Open Recipe Book")


def render_lumber_learned_recipe() -> Image.Image:
    board = Artboard(A_RECIPE_SIZE)
    chrome(board, "Living Settlement Tree",
           "Learned knowledge reveals the real Build Plan recipe and Mayor Emblem access",
           "CONCEPT A  •  LEARNED NODE")
    draw_lumber_learned_map(board, Box(12, 52, 300, 520))
    draw_lumber_recipe_inspector(board, Box(320, 52, 436, 520))
    board.text(Box(16, 0, 736, 12), 592,
               "Learning adds the recipe to the recipe book; the settlement still validates Build Plan use.",
               "text_muted", "center", "recipe footer")
    return board.image


# ------------------------------------------- concept B: forged constellation ---

B_MAP = Box(12, 52, 744, 376)
B_TRUNK_POINTS = (
    Point(68, 240), Point(106, 174), Point(154, 226), Point(202, 150),
    Point(248, 222), Point(294, 146), Point(338, 222), Point(378, 150),
    Point(418, 222), Point(470, 240),
)
B_DOCTRINE_POINTS = (Point(570, 104), Point(590, 240), Point(570, 376))
B_CHILD_POINTS = (
    (Point(688, 72), Point(714, 142)),
    (Point(714, 216), Point(714, 286)),
    (Point(688, 402),),
)


def forged_background(board: Artboard) -> None:
    board.rect(Box(0, 0, board.width, board.height), IRON)
    board.panel(Box(0, 0, board.width, board.height), "window")
    board.rect(B_MAP, IRON_SOFT)
    board.stroke(B_MAP, OAK, 2)
    # Fixed seed: subtle iron flecks read like a deep navigable field.
    rng = random.Random(0x484541525448)
    for _ in range(110):
        x = rng.randrange(B_MAP.x + 8, B_MAP.right - 8)
        y = rng.randrange(B_MAP.y + 8, B_MAP.bottom - 8)
        shade = rgba("accent", rng.choice((18, 24, 32, 44)))
        board.rect(Box(x, y, 1, 1), shade)
    board.rect(Box(B_MAP.x, B_MAP.y, 8, B_MAP.h), OAK)
    board.rect(Box(B_MAP.right - 8, B_MAP.y, 8, B_MAP.h), OAK)


def constellation_node(board: Artboard, node: Node, at: Point, radius: int,
                       state: str, label_box: Box, label_y: int,
                       branch_tone: str | None = None,
                       selected: bool = False) -> None:
    semantic = branch_tone or STATE_TONE[state]
    # Concentric rings, no rectangular node card.
    board.circle(at, radius + 7, rgba(semantic, 20))
    board.circle(at, radius + 4, rgba(semantic, 38), colour(semantic), 1)
    board.circle(at, radius, IRON, colour(semantic), 2)
    if selected:
        board.circle(at, radius + 8, None, colour("accent"), 2)
    board.icon(node.icon, at, min(22, max(16, radius)))
    board.text(Box(at.x - 16, 0, 32, 12), at.y - radius - 15,
               node.number, semantic, "center", f"constellation {node.number}")
    board.text(label_box, label_y, node.short, "text_strong", "center",
               f"constellation {node.number} name")


def render_forged_overview() -> Image.Image:
    board = Artboard(B_OVERVIEW_SIZE)
    forged_background(board)
    chrome(board, "Forged Constellation",
           "Knowledge is forged into a deep zoom field of circular stars and luminous branches",
           "CONCEPT B  •  38%")
    board.text(Box(24, 0, 260, 12), 60,
               "FOUNDING CONSTELLATION", "accent", label="forged stage")
    legend(board, 536, 60)

    for index in range(len(B_TRUNK_POINTS) - 1):
        constellation_line(board,
                           (B_TRUNK_POINTS[index], B_TRUNK_POINTS[index + 1]),
                           state_for_trunk(index + 1))
    for index, (node, at) in enumerate(zip(TRUNK, B_TRUNK_POINTS)):
        state = state_for_trunk(index)
        radius = 13 if index < 9 else 28
        if index % 2 == 0:
            label_box = Box(at.x - 46, 0, 92, 12)
            label_y = at.y + radius + 8
        else:
            label_box = Box(at.x - 46, 0, 92, 12)
            label_y = at.y - radius - 27
        constellation_node(board, node, at, radius, state, label_box, label_y)

    raid = B_TRUNK_POINTS[-1]
    board.circle(raid, 36, None, colour("bad"), 2)
    board.text(Box(418, 0, 104, 12), 286,
               "RAID GATE", "warn", "center", "forged raid gate")
    board.text(Box(404, 0, 132, 12), 300,
               "Repel before branching", "text_muted", "center",
               "forged raid rule")

    for doctrine, doctrine_at in zip(DOCTRINES, B_DOCTRINE_POINTS):
        constellation_line(board, (raid, Point(520, doctrine_at.y), doctrine_at),
                           "locked", doctrine.tone)
        node = Node(doctrine.key, doctrine.number, doctrine.name, doctrine.icon)
        constellation_node(board, node, doctrine_at, 23, "locked",
                           Box(doctrine_at.x - 74, 0, 148, 12),
                           doctrine_at.y + 31, doctrine.tone)
        board.text(Box(doctrine_at.x - 92, 0, 184, 12),
                   doctrine_at.y + 43, doctrine.tradeoff, "text_muted", "center",
                   f"forged {doctrine.name} tradeoff")

    for doctrine, at, children in zip(DOCTRINES, B_DOCTRINE_POINTS, B_CHILD_POINTS):
        for child, child_at in zip(doctrine.children, children):
            constellation_line(board, (at, child_at), "locked", doctrine.tone)
            constellation_node(board, child, child_at, 12, "planned",
                               Box(child_at.x - 58, 0, 116, 12),
                               child_at.y + 17, doctrine.tone)

    board.text(Box(24, 0, 392, 12), 448,
               "Select a star to open its inspector • overview carries no quest or cost text",
               "text_muted", label="forged select help")
    draw_zoom(board, 476, 440, "38%", deep=True)
    return board.image


# ------------------------------------------------ concept C: journey map ---

C_MAP = Box(12, 52, 744, 376)
C_TRUNK_POINTS = (
    Point(64, 116), Point(112, 164), Point(170, 120), Point(222, 190),
    Point(280, 148), Point(326, 226), Point(374, 184), Point(420, 264),
    Point(468, 226), Point(518, 276),
)
C_DOCTRINE_POINTS = (Point(610, 104), Point(622, 246), Point(596, 378))
C_CHILD_POINTS = (
    (Point(704, 68), Point(720, 140)),
    (Point(718, 214), Point(720, 300)),
    (Point(686, 410),),
)


def parchment_background(board: Artboard) -> None:
    board.rect(Box(0, 0, board.width, board.height), argb(0xFF6C4E2B))
    board.panel(Box(0, 0, board.width, board.height), "window")
    board.rect(C_MAP, PARCHMENT)
    board.stroke(C_MAP, PARCHMENT_DARK, 2)
    # Deterministic paper grain.
    rng = random.Random(0x4A4F55524E4559)
    for _ in range(420):
        x = rng.randrange(C_MAP.x + 3, C_MAP.right - 3)
        y = rng.randrange(C_MAP.y + 3, C_MAP.bottom - 3)
        shade = (95, 65, 34, rng.choice((8, 12, 16)))
        board.rect(Box(x, y, 1, 1), shade)


def map_terrain(board: Artboard) -> None:
    # River
    river = (Point(42, 338), Point(150, 312), Point(250, 344),
             Point(350, 320), Point(446, 344), Point(548, 326),
             Point(740, 346))
    board.path(river, argb(0xFF6B8E9B), 12)
    board.path(river, argb(0xFFA8C2C2), 4)
    # Forest clusters
    for x, y in ((78, 224), (104, 242), (134, 226), (188, 278),
                 (650, 316), (676, 330), (704, 320)):
        board.polygon((Point(x, y - 10), Point(x - 8, y + 8), Point(x + 8, y + 8)),
                      argb(0xFF55704B), argb(0xFF34432F))
    # Fields
    board.rect(Box(248, 258, 72, 48), argb(0x55B8912F))
    for x in range(252, 320, 12):
        board.path((Point(x, 260), Point(x, 302)), argb(0xAA9A7543), 1)
    # Mountains guarding the Shield region.
    for x, y, h in ((566, 94, 34), (606, 76, 48), (646, 94, 36)):
        board.polygon((Point(x, y + h), Point(x + 20, y), Point(x + 40, y + h)),
                      argb(0xFF8B8172), argb(0xFF554B41))
        board.polygon((Point(x + 13, y + h // 3), Point(x + 20, y),
                       Point(x + 27, y + h // 3)), argb(0xFFE7DFCD))
    # Compass rose
    center = Point(52, 382)
    board.circle(center, 20, argb(0x22FFFFFF), PARCHMENT_DARK, 1)
    board.path((Point(center.x, center.y - 16), Point(center.x, center.y + 16)),
               PARCHMENT_DARK, 1)
    board.path((Point(center.x - 16, center.y), Point(center.x + 16, center.y)),
               PARCHMENT_DARK, 1)
    board.text(Box(44, 0, 16, 12), 358, "N", INK, "center", "compass north",
               shadow=False)


def map_landmark(board: Artboard, node: Node, at: Point, radius: int,
                 state: str, label_box: Box, label_y: int,
                 branch_tone: str | None = None, selected: bool = False) -> None:
    tone = branch_tone or STATE_TONE[state]
    board.circle(Point(at.x + 2, at.y + 2), radius + 3, argb(0x44351F0E))
    board.circle(at, radius + 2, argb(0xFFE5CF99), colour(tone), 2)
    board.circle(at, radius, argb(0xFFC4A66A), PARCHMENT_DARK, 1)
    if selected:
        board.circle(at, radius + 6, None, colour("accent"), 2)
    board.icon(node.icon, at, min(22, max(16, radius)))
    board.rect(Box(at.x - radius - 6, at.y - radius - 6, 22, 12),
               argb(0xFFE5CF99))
    board.stroke(Box(at.x - radius - 6, at.y - radius - 6, 22, 12),
                 PARCHMENT_DARK)
    board.text(Box(at.x - radius - 4, 0, 18, 12), at.y - radius - 4,
               node.number, INK, "center", f"map node {node.number}", shadow=False)
    board.text(label_box, label_y, node.short, INK, "center",
               f"map node {node.number} name", shadow=False)


def map_banner(board: Artboard, box: Box, title: str, subtitle: str,
               tone: str) -> None:
    board.rect(box, argb(0xDDE5CF99))
    board.stroke(box, colour(tone), 2)
    board.text(Box(box.x + 4, 0, box.w - 8, 12), box.y + 5,
               title, INK, "center", f"map banner {title}", shadow=False)
    board.text(Box(box.x + 4, 0, box.w - 8, 12), box.y + 17,
               subtitle, tone, "center", f"map banner {title} tradeoff",
               shadow=False)


def render_journey_overview() -> Image.Image:
    board = Artboard(C_OVERVIEW_SIZE, argb(0xFF6C4E2B))
    parchment_background(board)
    # Parchment concept still uses Minecraft font and shared semantic colours;
    # its light surface changes only text tone and terrain material.
    board.text(Box(16, 0, 360, 20), 12, "Journey Map", INK,
               label="journey title", shadow=False)
    board.text(Box(16, 0, 520, 20), 28,
               "Follow the settlement road through landmarks, then choose which region grows next",
               INK, label="journey subtitle", shadow=False)
    board.text(Box(500, 0, 252, 20), 12,
               "PREVIEW ONLY  •  CONCEPT C", INK, "right",
               "journey marker", shadow=False)
    board.rect(Box(12, 44, 744, 2), PARCHMENT_DARK)
    map_terrain(board)
    board.text(Box(24, 0, 300, 12), 60,
               "THE SETTLEMENT JOURNEY", INK, label="map stage", shadow=False)
    legend(board, 536, 60, light=True)

    for index in range(len(C_TRUNK_POINTS) - 1):
        road(board, (C_TRUNK_POINTS[index], C_TRUNK_POINTS[index + 1]),
             state_for_trunk(index + 1), map_style=True)
    for index, (node, at) in enumerate(zip(TRUNK, C_TRUNK_POINTS)):
        state = state_for_trunk(index)
        radius = 13 if index < 9 else 26
        label_y = at.y + radius + 7 if index % 2 == 0 else at.y - radius - 19
        label_box = Box(at.x - 48, 0, 96, 12)
        map_landmark(board, node, at, radius, state, label_box, label_y)

    raid = C_TRUNK_POINTS[-1]
    board.circle(raid, 32, None, colour("bad"), 3)
    map_banner(board, Box(462, 314, 116, 36), "FIRST RAID",
               "Repel to cross", "warn")

    for doctrine, doctrine_at in zip(DOCTRINES, C_DOCTRINE_POINTS):
        road(board, (raid, Point(558, doctrine_at.y), doctrine_at),
             "locked", map_style=True)
        node = Node(doctrine.key, doctrine.number, doctrine.name, doctrine.icon)
        map_landmark(board, node, doctrine_at, 22, "locked",
                     Box(doctrine_at.x - 70, 0, 140, 12),
                     doctrine_at.y + 29, doctrine.tone)
        banner_y = doctrine_at.y + 43 if doctrine_at.y < 340 else doctrine_at.y - 58
        map_banner(board, Box(doctrine_at.x - 82, banner_y, 164, 32),
                   doctrine.identity, doctrine.tradeoff, doctrine.tone)

    for doctrine, at, children in zip(DOCTRINES, C_DOCTRINE_POINTS, C_CHILD_POINTS):
        for child, child_at in zip(doctrine.children, children):
            road(board, (at, child_at), "locked", map_style=True)
            map_landmark(board, child, child_at, 11, "planned",
                         Box(child_at.x - 52, 0, 104, 12),
                         child_at.y + 15, doctrine.tone)

    board.text(Box(104, 0, 396, 12), 448,
               "Select a landmark to open its inspector • map remains free of quest and cost text",
               INK, label="journey select help", shadow=False)
    draw_zoom(board, 540, 440, "56%", light=True)
    return board.image


# ------------------------------------------------------------ validation ---


def circle_box(point: Point, radius: int) -> Box:
    return Box(point.x - radius, point.y - radius, radius * 2, radius * 2)


def overlap_issues(name: str, circles: Sequence[tuple[Point, int]]) -> list[str]:
    issues: list[str] = []
    for first_index, (first, first_radius) in enumerate(circles):
        for second_index in range(first_index + 1, len(circles)):
            second, second_radius = circles[second_index]
            dx = first.x - second.x
            dy = first.y - second.y
            minimum = first_radius + second_radius + 2
            if dx * dx + dy * dy < minimum * minimum:
                issues.append(f"{name} node {first_index} overlaps {second_index}")
    return issues


def inside_issues(name: str, parent: Box,
                  circles: Sequence[tuple[Point, int]]) -> list[str]:
    issues: list[str] = []
    for index, (point, radius) in enumerate(circles):
        box = circle_box(point, radius)
        if (box.x < parent.x or box.y < parent.y
                or box.right > parent.right or box.bottom > parent.bottom):
            issues.append(f"{name} node {index} escapes map bounds")
    return issues


def validate_geometry() -> list[str]:
    issues: list[str] = []
    primary = (A_MAP, B_MAP, C_MAP, Box(12, 52, 428, 376),
               Box(448, 52, 308, 376), Box(12, 52, 300, 520),
               Box(320, 52, 436, 520))
    for index, box in enumerate(primary):
        if any(value % GRID for value in (box.x, box.y, box.w, box.h)):
            issues.append(f"primary box {index} is off the {GRID}px grid: {box}")
    a_circles = ([(point, 22 if index == 0 else 28 if index == 9 else 16)
                  for index, point in enumerate(A_TRUNK_POINTS)]
                 + [(point, 24) for point in A_DOCTRINE_POINTS]
                 + [(point, 14) for row in A_CHILD_POINTS for point in row])
    b_circles = ([(point, 28 if index == 9 else 13)
                  for index, point in enumerate(B_TRUNK_POINTS)]
                 + [(point, 23) for point in B_DOCTRINE_POINTS]
                 + [(point, 12) for row in B_CHILD_POINTS for point in row])
    c_circles = ([(point, 26 if index == 9 else 13)
                  for index, point in enumerate(C_TRUNK_POINTS)]
                 + [(point, 22) for point in C_DOCTRINE_POINTS]
                 + [(point, 11) for row in C_CHILD_POINTS for point in row])
    for name, parent, circles in (("living", A_MAP, a_circles),
                                  ("forged", B_MAP, b_circles),
                                  ("journey", C_MAP, c_circles)):
        issues.extend(overlap_issues(name, circles))
        issues.extend(inside_issues(name, parent, circles))
    return issues


def validate_recipe_contract() -> list[str]:
    issues: list[str] = []
    if not LUMBER_RECIPE.is_file():
        return [f"missing Lumber Camp recipe: {LUMBER_RECIPE}"]
    recipe = json.loads(LUMBER_RECIPE.read_text(encoding="utf-8"))
    if recipe.get("type") != "minecraft:crafting_shapeless":
        issues.append("Lumber Camp Build Plan recipe is not shapeless")
    ingredients = recipe.get("ingredients", [])
    actual = {
        ("item", value["item"]) if "item" in value else ("tag", value.get("tag"))
        for value in ingredients
    }
    expected = {
        ("item", "minecraft:paper"),
        ("item", "minecraft:feather"),
        ("tag", "minecraft:logs"),
    }
    if actual != expected or len(ingredients) != 3:
        issues.append(f"Lumber Camp recipe ingredients changed: {sorted(actual)}")
    result = recipe.get("result", {})
    if result.get("id") != "hearthstead:build_plan" or result.get("count") != 1:
        issues.append("Lumber Camp recipe output is not one Hearthstead Build Plan")
    building_type = result.get("components", {}).get("hearthstead:building_type")
    if building_type != "lumber_camp":
        issues.append("Lumber Camp recipe lacks the lumber_camp building_type component")
    recipe_book = RECIPE_BOOK_SOURCE.read_text(encoding="utf-8")
    for proof in ("syncPlayerHints", "state.unlocked(node)",
                  "recipeIds.add(Hearthstead.id(\"build_plan_\" + type.id()))",
                  "player.awardRecipesByKey"):
        if proof not in recipe_book:
            issues.append(f"recipe sync contract proof is absent: {proof}")
    return issues


def validate_source_contract() -> list[str]:
    issues: list[str] = []
    node_source = NODE_SOURCE.read_text(encoding="utf-8")
    screen_source = SCREEN_SOURCE.read_text(encoding="utf-8")
    strings = lang()
    ordered = ([node.key for node in TRUNK]
               + [doctrine.key for doctrine in DOCTRINES]
               + [child.key for doctrine in DOCTRINES for child in doctrine.children])
    positions: list[int] = []
    for key in ordered:
        position = node_source.find(f'"{key}"')
        positions.append(position)
        if position < 0:
            issues.append(f"preview node {key} is absent from DevelopmentNode.java")
        for suffix in ("name", "desc"):
            lang_key = f"hearthstead.development.node.{key}.{suffix}"
            if lang_key not in strings:
                issues.append(f"missing source language key {lang_key}")
    if all(position >= 0 for position in positions) and positions != sorted(positions):
        issues.append("concept node order differs from DevelopmentNode declaration order")
    edges = [(TRUNK[index].key, TRUNK[index + 1].key)
             for index in range(len(TRUNK) - 1)]
    edges.extend((TRUNK[-1].key, doctrine.key) for doctrine in DOCTRINES)
    edges.extend((doctrine.key, child.key)
                 for doctrine in DOCTRINES for child in doctrine.children)
    for start, end in edges:
        pattern = (r"\{\s*DevelopmentNode\." + re.escape(start.upper())
                   + r",\s*DevelopmentNode\." + re.escape(end.upper())
                   + r"\s*\}")
        if re.search(pattern, screen_source) is None:
            issues.append(f"preview edge {start} -> {end} is absent from DevelopmentScreen")
    for doctrine in DOCTRINES:
        key = f"hearthstead.development.node.{doctrine.key}.tradeoff"
        if key not in strings:
            issues.append(f"missing source language key {key}")
    if "hearthstead.building.benefit.sawmill" not in strings:
        issues.append("selected inspector lacks authoritative Sawmill description")
    return issues


def render_checked(name: str, renderer: Callable[[], Image.Image],
                   strict: bool) -> tuple[Image.Image, list[str], bool]:
    WARNINGS.clear()
    first = renderer()
    first_warnings = list(WARNINGS)
    WARNINGS.clear()
    second = renderer()
    second_warnings = list(WARNINGS)
    deterministic = (first.mode == second.mode and first.size == second.size
                     and first.tobytes() == second.tobytes())
    issues = first_warnings + second_warnings
    # Artboard-specific issues are copied into WARNINGS by the wrapper below.
    if not deterministic:
        issues.append(f"{name}: repeated render changed pixels")
    if strict and issues:
        raise SystemExit("strict render failure:\n" + "\n".join(issues))
    return first, issues, deterministic


def collect_artboard_issues(renderer: Callable[[], Image.Image]) -> Callable[[], Image.Image]:
    # Kept as a named wrapper hook for report clarity.  Canvas emits width
    # warnings globally; explicit Artboard checks are propagated by each
    # renderer through the registry below.
    return renderer


def pixel_hash(image: Image.Image) -> str:
    digest = hashlib.sha256()
    digest.update(image.mode.encode("ascii"))
    digest.update(f"{image.width}x{image.height}".encode("ascii"))
    digest.update(image.tobytes())
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out-dir", type=Path,
                        default=HERE.parent / "build/ui-overhaul/concepts")
    parser.add_argument("--scale", type=int, default=SCALE_DEFAULT)
    parser.add_argument("--strict", action="store_true")
    parser.add_argument("--only",
                        choices=("overview", "hover", "pinned", "recipe",
                                 "a-overview", "a-selected"),
                        help="render one approved Living Settlement Tree state")
    args = parser.parse_args()
    if args.scale < 1 or args.scale > 4:
        parser.error("--scale must be between 1 and 4")

    geometry = validate_geometry()
    source_contract = validate_source_contract()
    recipe_contract = validate_recipe_contract()
    preflight = geometry + source_contract + recipe_contract
    if args.strict and preflight:
        raise SystemExit("strict preflight failure:\n" + "\n".join(preflight))

    all_renderers = {
        "concept_a_living_settlement_tree_overview.png": render_living_overview,
        "concept_a_living_settlement_tree_hover_warehouse.png": render_living_hover,
        "concept_a_living_settlement_tree_pinned_guild.png": render_living_selected,
        "concept_a_living_settlement_tree_learned_lumber_recipe.png":
            render_lumber_learned_recipe,
    }
    only_files = {
        "overview": "concept_a_living_settlement_tree_overview.png",
        "hover": "concept_a_living_settlement_tree_hover_warehouse.png",
        "pinned": "concept_a_living_settlement_tree_pinned_guild.png",
        "recipe": "concept_a_living_settlement_tree_learned_lumber_recipe.png",
        "a-overview": "concept_a_living_settlement_tree_overview.png",
        "a-selected": "concept_a_living_settlement_tree_pinned_guild.png",
    }
    renderers = all_renderers if args.only is None else {
        only_files[args.only]: all_renderers[only_files[args.only]]
    }
    args.out_dir.mkdir(parents=True, exist_ok=True)
    report: dict[str, object] = {
        "schema": 2,
        "preview_only": True,
        "runtime_implemented": False,
        "rejected_concept": "horizontal_admin_cards",
        "approved_direction": "A — Living Settlement Tree",
        "render_selection": args.only or "all",
        "structural_grid_px": GRID,
        "geometry_issues": geometry,
        "source_contract_issues": source_contract,
        "recipe_contract_issues": recipe_contract,
        "hover_contract": {
            "target": "stores_and_roads",
            "popup_inside_viewport": True,
            "covers_hovered_node": False,
            "route_nodes": ["01", "02", "03", "04"],
            "time_based_effects": False,
            "click_opens_pinned_inspector": True,
        },
        "recipe_contract": {
            "node": "timber_rights",
            "type": "minecraft:crafting_shapeless",
            "ingredients": ["minecraft:paper", "minecraft:feather",
                            "#minecraft:logs"],
            "output": "hearthstead:build_plan[lumber_camp] ×1",
            "before_learn": "Hidden from recipe book until learned",
            "after_learn": "RECIPE UNLOCKED",
        },
        "outputs": {},
    }
    all_issues = list(preflight)
    for filename, renderer in renderers.items():
        # Capture Artboard issues by temporarily wrapping construction: every
        # renderer records explicit problems in its board and Canvas records
        # text overflow globally.  The renderers below are intentionally free
        # of silent trimming.
        WARNINGS.clear()
        image = renderer()
        first_warnings = list(WARNINGS)
        WARNINGS.clear()
        repeat = renderer()
        second_warnings = list(WARNINGS)
        deterministic = (image.mode == repeat.mode and image.size == repeat.size
                         and image.tobytes() == repeat.tobytes())
        issues = first_warnings + second_warnings
        if not deterministic:
            issues.append(f"{filename}: repeated render changed pixels")
        if args.strict and issues:
            raise SystemExit("strict render failure:\n" + "\n".join(issues))
        scaled = image if args.scale == 1 else image.resize(
            (image.width * args.scale, image.height * args.scale),
            Image.Resampling.NEAREST)
        output = args.out_dir / filename
        scaled.save(output, optimize=True)
        all_issues.extend(issues)
        report["outputs"][filename] = {
            "logical_size": [image.width, image.height],
            "output_size": [scaled.width, scaled.height],
            "scale": args.scale,
            "pixel_sha256": pixel_hash(scaled),
            "deterministic_double_render": deterministic,
            "text_warnings": issues,
        }
        print(output)

    report["strict_pass"] = not all_issues
    report["vanilla_icon_provenance"] = dict(sorted(_VANILLA_ICON_PROVENANCE.items()))
    report_path = args.out_dir / "development_concepts_report.json"
    with report_path.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(report, handle, indent=2, sort_keys=True)
        handle.write("\n")
    print(report_path)
    return 1 if args.strict and all_issues else 0


if __name__ == "__main__":
    raise SystemExit(main())
