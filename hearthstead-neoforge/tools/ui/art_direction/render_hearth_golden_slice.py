#!/usr/bin/env python3
"""Render the Hearth premium-approval slice from the real 320x220 menu geometry.

This is an offline, runtime-equivalent art review.  It deliberately does not
change menu authority or invent new controls.
"""
from __future__ import annotations

from pathlib import Path
import json
import random
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "build" / "ui_art_direction" / "hearth_golden_slice"
LAYERS = ROOT / "tools" / "ui" / "art_direction" / "hearth_golden_layers"
FONT = ImageFont.load_default()
NAMES = (
    ("BACK_CAST_SHADOW", "back_cast_shadow"),
    ("FRAME_WOOD", "frame_wood"),
    ("INSET_PAPER_OR_LEATHER", "inset_paper_or_leather"),
    ("FOREGROUND_HARDWARE", "foreground_hardware"),
    ("TEXT_AND_ICONS", "text_and_icons"),
    ("INTERACTION_STATE", "interaction_state"),
)

C = {
    "table0": (12, 10, 9, 255), "table1": (29, 22, 17, 255),
    "shadow": (3, 3, 4, 210), "soot": (27, 25, 23, 255),
    "stone0": (45, 44, 41, 255), "stone1": (68, 67, 60, 255),
    "iron0": (39, 41, 41, 255), "iron1": (83, 83, 73, 255),
    "iron2": (131, 127, 108, 255), "oak0": (45, 27, 17, 255),
    "oak1": (76, 44, 24, 255), "oak2": (119, 75, 37, 255),
    "oak3": (166, 111, 56, 255), "brass0": (91, 61, 23, 255),
    "brass1": (157, 112, 42, 255), "brass2": (221, 180, 82, 255),
    "paper0": (124, 96, 59, 255), "paper1": (210, 181, 126, 255),
    "paper2": (240, 219, 169, 255), "ink": (39, 29, 20, 255),
    "text": (239, 225, 191, 255), "muted": (190, 172, 136, 255),
    "good": (102, 157, 91, 255), "bad": (191, 76, 59, 255),
    "ember": (221, 111, 42, 255), "slot": (21, 20, 19, 255),
}


def layer(size):
    return Image.new("RGBA", size, (0, 0, 0, 0))


def text(draw, x, y, value, colour, *, anchor=None, shadow=True):
    if anchor == "mm":
        box = draw.textbbox((0, 0), value, font=FONT)
        x -= (box[2] - box[0]) // 2
    if shadow:
        draw.text((x + 1, y + 1), value, font=FONT, fill=(0, 0, 0, 180))
    draw.text((x, y), value, font=FONT, fill=colour)


def raised(draw, box, face, hi, lo, shadow=3):
    x0, y0, x1, y1 = box
    draw.rectangle((x0 + shadow, y0 + shadow, x1 + shadow, y1 + shadow), fill=C["shadow"])
    draw.rectangle(box, fill=face)
    draw.line((x0, y0, x1, y0), fill=hi)
    draw.line((x0, y0, x0, y1), fill=hi)
    draw.line((x0, y1, x1, y1), fill=lo)
    draw.line((x1, y0, x1, y1), fill=lo)


def recess(draw, box, face=None):
    x0, y0, x1, y1 = box
    face = face or C["slot"]
    draw.rectangle(box, fill=face)
    draw.line((x0, y0, x1, y0), fill=(5, 5, 5, 255), width=2)
    draw.line((x1 - 1, y0, x1 - 1, y1), fill=(6, 6, 6, 255), width=2)
    draw.line((x0 + 1, y1, x1 - 1, y1), fill=C["iron1"])
    draw.line((x0, y0 + 2, x0, y1), fill=C["oak2"])


def rivet(draw, x, y):
    draw.point((x + 1, y + 1), fill=(7, 7, 7, 255))
    draw.point((x, y), fill=C["iron2"])
    draw.point((x + 1, y), fill=C["iron1"])


def item(draw, kind, x, y):
    """Readable 12px approximations of real item sprites used by the menu."""
    if kind == "oak_log":
        draw.rectangle((x + 2, y + 3, x + 11, y + 10), fill=C["oak2"])
        draw.line((x + 2, y + 3, x + 11, y + 3), fill=C["oak3"])
        draw.ellipse((x + 8, y + 4, x + 12, y + 10), fill=(185, 132, 70, 255), outline=C["oak0"])
    elif kind == "wheat_seeds":
        for dx, dy in ((3, 9), (5, 5), (7, 8), (9, 3), (11, 7)):
            draw.line((x + dx, y + dy, x + dx + 2, y + dy - 3), fill=(123, 155, 72, 255))
    elif kind == "iron_ingot":
        draw.polygon([(x + 2, y + 7), (x + 5, y + 3), (x + 12, y + 3), (x + 14, y + 8), (x + 11, y + 11), (x + 4, y + 11)], fill=C["iron2"])
        draw.line((x + 5, y + 4, x + 11, y + 4), fill=(188, 186, 170, 255))
    elif kind == "iron_axe":
        draw.line((x + 3, y + 13, x + 11, y + 4), fill=C["oak3"], width=2)
        draw.polygon([(x + 8, y + 2), (x + 14, y + 2), (x + 14, y + 7), (x + 10, y + 8), (x + 8, y + 5)], fill=C["iron2"])
    elif kind == "bread":
        draw.ellipse((x + 2, y + 4, x + 13, y + 11), fill=(190, 127, 48, 255), outline=(104, 62, 27, 255))
        draw.line((x + 6, y + 5, x + 7, y + 8), fill=(239, 183, 84, 255))


def flame(draw, cx, cy):
    draw.polygon([(cx, cy - 9), (cx + 5, cy - 3), (cx + 3, cy + 2),
                  (cx + 7, cy), (cx + 5, cy + 8), (cx, cy + 11),
                  (cx - 6, cy + 7), (cx - 7, cy + 1), (cx - 2, cy + 3),
                  (cx - 3, cy - 2)], fill=C["brass1"])
    draw.polygon([(cx, cy - 3), (cx + 2, cy + 2), (cx, cy + 7),
                  (cx - 3, cy + 3)], fill=C["paper2"])


def render(size=(427, 240), state="normal"):
    layers = [layer(size) for _ in NAMES]
    back, frame, inset, hardware, labels, interaction = map(ImageDraw.Draw, layers)
    w, h = size
    x = (w - 320) // 2
    # The 220px menu and 18px bookmarks leave a real two-pixel safety edge at
    # the minimum 240px viewport instead of crowding the final hotbar row.
    y = 18

    # Plane 1: a quiet, dark table/world field. Broad grain only.
    back.rectangle((0, 0, w - 1, h - 1), fill=C["table0"])
    rng = random.Random(19081 + w)
    for yy in range(8, h, 20):
        back.line((0, yy, w - 1, yy), fill=C["table1"])
        back.line((0, yy + 1, w - 1, yy + 1), fill=(5, 5, 5, 255))
        for _ in range(3):
            sx = rng.randrange(0, w)
            back.line((sx, yy - 5, min(w - 1, sx + rng.randrange(7, 24)), yy - 5), fill=(50, 33, 22, 120))
    back.polygon([(x + 7, y + 8), (x + 316, y + 8), (x + 319, y + 216),
                  (x + 311, y + 220), (x + 10, y + 220), (x + 3, y + 214)], fill=C["shadow"])

    # Plane 2: irregular forged-stone Hearth ledger frame and oak spine.
    frame.polygon([(x + 8, y), (x + 312, y), (x + 319, y + 7),
                   (x + 319, y + 209), (x + 311, y + 217),
                   (x + 9, y + 217), (x, y + 208), (x, y + 8)], fill=C["iron0"])
    frame.line((x + 8, y, x + 312, y), fill=C["iron2"])
    frame.line((x, y + 8, x, y + 208), fill=C["iron1"])
    frame.line((x + 9, y + 217, x + 311, y + 217), fill=(17, 17, 17, 255), width=2)
    frame.rectangle((x + 5, y + 5, x + 314, y + 212), fill=C["stone0"])
    frame.line((x + 6, y + 5, x + 313, y + 5), fill=C["stone1"])
    frame.line((x + 5, y + 6, x + 5, y + 211), fill=C["stone1"])
    frame.rectangle((x + 8, y + 8, x + 311, y + 209), fill=C["soot"])
    frame.rectangle((x + 8, y + 8, x + 311, y + 25), fill=C["oak1"])
    frame.line((x + 8, y + 8, x + 311, y + 8), fill=C["oak3"])
    frame.line((x + 8, y + 25, x + 311, y + 25), fill=C["oak0"], width=2)
    for gx in range(x + 18, x + 300, 38):
        frame.line((gx, y + 12, gx + 13, y + 12), fill=C["oak2"])

    # The actual Hearth tabs (316 px total) are leather page bookmarks tucked
    # under the top iron rail. Their silhouettes are deliberately irregular;
    # they are not detached dashboard buttons.
    tabs = (("Settlement", 64), ("Mayor", 56), ("Journey", 52),
            ("Requests", 56), ("Development", 72))
    tx = x + 2
    for index, (name, width) in enumerate(tabs):
        active = index == 0
        top = 0 if active else 2
        bottom = y + 5 if active else y + 2
        tab = [(tx + 3, top), (tx + width - 4, top), (tx + width - 1, top + 3),
               (tx + width - 1, bottom - 3), (tx + width - 5, bottom),
               (tx + 4, bottom), (tx, bottom - 4), (tx, top + 3)]
        frame.polygon([(px + 2, py + 2) for px, py in tab], fill=C["shadow"])
        frame.polygon(tab, fill=C["oak1"] if active else (55, 44, 34, 255))
        frame.line(tab[:4], fill=C["oak3"] if active else C["iron1"])
        frame.line((tx + 5, top + 3, tx + width - 6, top + 3), fill=C["paper0"])
        for stitch_x in range(tx + 7, tx + width - 6, 8):
            frame.point((stitch_x, bottom - 3), fill=C["brass0"])
        text(labels, tx + width // 2, top + 6, name,
             C["paper2"] if active else C["muted"], anchor="mm")
        tx += width + 4

    # The left is a carved settlement tally; the right is a single tucked
    # parchment summary. Different materials prevent a three-card dashboard.
    bx0, bx1 = x + 8, x + 99
    inset.polygon([(bx0 + 4, y + 39), (bx1 - 5, y + 39), (bx1, y + 44),
                   (bx1 - 2, y + 112), (bx0 + 5, y + 114), (bx0, y + 109),
                   (bx0, y + 44)], fill=C["stone1"])
    inset.polygon([(bx0 + 5, y + 43), (bx1 - 5, y + 43), (bx1 - 4, y + 108),
                   (bx0 + 5, y + 110)], fill=(39, 36, 32, 255))
    inset.line((bx0 + 5, y + 43, bx1 - 5, y + 43), fill=(17, 16, 15, 255), width=2)
    inset.line((bx1 - 5, y + 44, bx1 - 4, y + 108), fill=(17, 16, 15, 255), width=2)
    if state != "blocked":
        inset.polygon([(x + 224, y + 42), (x + 230, y + 38), (x + 307, y + 40),
                       (x + 311, y + 45), (x + 309, y + 111), (x + 303, y + 114),
                       (x + 225, y + 111)], fill=C["shadow"])
        inset.polygon([(x + 221, y + 39), (x + 227, y + 35), (x + 304, y + 37),
                       (x + 308, y + 42), (x + 306, y + 108), (x + 300, y + 111),
                       (x + 222, y + 108)], fill=C["paper1"])
        inset.line((x + 228, y + 38, x + 301, y + 40), fill=C["paper2"])
        inset.line((x + 306, y + 43, x + 304, y + 106), fill=C["paper0"], width=2)

    # Actual 6x4 communal inventory at (106,42), all 18 px apart.
    recess(inset, (x + 101, y + 34, x + 218, y + 118), (34, 31, 28, 255))
    inset.line((x + 103, y + 116, x + 216, y + 116), fill=C["oak2"])
    inset.line((x + 102, y + 36, x + 102, y + 115), fill=C["stone1"])
    for row in range(4):
        for col in range(6):
            sx, sy = x + 107 + col * 18, y + 43 + row * 18
            recess(inset, (sx, sy, sx + 16, sy + 16))
    # Real item examples at real slot anchors.
    item(inset, "oak_log", x + 108, y + 44)
    item(inset, "wheat_seeds", x + 126, y + 44)
    item(inset, "iron_ingot", x + 180, y + 44)

    # An open leather folio spans the lower ledger; the actual inventory is a
    # recessed well cut into it, so the side margins remain authored material.
    inset.polygon([(x + 17, y + 132), (x + 155, y + 127), (x + 303, y + 132),
                   (x + 298, y + 215), (x + 162, y + 217), (x + 21, y + 214)], fill=C["shadow"])
    inset.polygon([(x + 14, y + 129), (x + 155, y + 125), (x + 306, y + 129),
                   (x + 301, y + 212), (x + 162, y + 214), (x + 18, y + 211)], fill=C["oak0"])
    inset.line((x + 18, y + 129, x + 155, y + 126), fill=C["oak3"])
    inset.line((x + 158, y + 126, x + 302, y + 130), fill=C["oak2"])
    inset.line((x + 160, y + 128, x + 160, y + 212), fill=C["brass0"])
    recess(inset, (x + 72, y + 130, x + 247, y + 218), (48, 39, 28, 255))
    for row in range(3):
        for col in range(9):
            sx, sy = x + 80 + col * 18, y + 143 + row * 18
            recess(inset, (sx, sy, sx + 16, sy + 16))
    for col in range(9):
        sx, sy = x + 80 + col * 18, y + 201
        recess(inset, (sx, sy, sx + 16, sy + 16))
    item(inset, "iron_axe", x + 81, y + 202)
    item(inset, "bread", x + 99, y + 202)

    # Plane 3: hardware sits visibly above frame and inset planes.
    raised(hardware, (x + 144, y - 1, x + 176, y + 10), C["iron1"], C["iron2"], C["iron0"], 3)
    flame(hardware, x + 160, y + 12)
    for rx, ry in ((x + 8, y + 8), (x + 310, y + 8), (x + 8, y + 208), (x + 310, y + 208)):
        rivet(hardware, rx, ry)
    # Two clips visibly occlude the communal well lip.
    for cx in (x + 109, x + 205):
        raised(hardware, (cx, y + 31, cx + 6, y + 39), C["brass0"], C["brass2"], C["oak0"], 2)
        rivet(hardware, cx + 2, y + 34)

    # Exact visible labels/data concepts already present in HearthScreen.
    text(labels, x + 12, y + 10, "ALDERWATCH", C["text"])
    title_box = labels.textbbox((0, 0), "HEARTH LEDGER", font=FONT)
    text(labels, x + 308 - (title_box[2] - title_box[0]), y + 10, "HEARTH LEDGER", C["text"])
    text(labels, x + 160, y + 28, "COMMUNAL STORES", C["brass2"], anchor="mm")
    text(labels, x + 12, y + 45, "POPULATION", C["muted"])
    text(labels, x + 82, y + 56, "7 / 10", C["paper2"], anchor="mm")
    text(labels, x + 12, y + 72, "EMPLOYED", C["muted"])
    text(labels, x + 82, y + 83, "5 / 7", C["paper2"], anchor="mm")
    text(labels, x + 12, y + 96, "FOOD", C["muted"])
    text(labels, x + 82, y + 96, "126", C["paper2"], anchor="mm")
    text(labels, x + 12, y + 106, "RADIUS", C["muted"])
    text(labels, x + 82, y + 106, "64 m", C["paper2"], anchor="mm")
    if state != "blocked":
        text(labels, x + 229, y + 45, "SETTLEMENT", C["paper0"], shadow=False)
        text(labels, x + 229, y + 58, "STABLE", (70, 112, 59, 255), shadow=False)
        text(labels, x + 229, y + 74, "MORALE", C["paper0"], shadow=False)
        text(labels, x + 276, y + 74, "82 / 100", (70, 112, 59, 255), shadow=False)
        text(labels, x + 229, y + 91, "NEXT", C["paper0"], shadow=False)
        text(labels, x + 229, y + 101, "Await traveler", C["ink"], shadow=False)
    text(labels, x + 79, y + 132, "INVENTORY", C["paper2"])

    # Existing recruitment notice geometry at y=114, no animation/glow.
    raised(inset, (x + 8, y + 114, x + 311, y + 129), C["oak1"], C["brass1"], C["oak0"], 2)
    if state == "blocked":
        text(labels, x + 15, y + 118, "NEXT SETTLER NEEDS A TAVERN", C["paper2"])
        # A wide pull-out parchment extends into the free world margin, giving
        # the exact next action room to remain one readable line.
        raised(interaction, (x + 219, y + 38, x + 365, y + 113), C["paper1"], C["paper2"], C["paper0"], 3)
        interaction.line((x + 225, y + 44, x + 359, y + 44), fill=C["bad"])
        text(interaction, x + 225, y + 47, "STATE", C["paper0"], shadow=False)
        text(interaction, x + 278, y + 47, "Blocked", C["bad"], shadow=False)
        text(interaction, x + 225, y + 61, "CAUSE", C["paper0"], shadow=False)
        text(interaction, x + 278, y + 61, "No active Tavern", C["ink"], shadow=False)
        text(interaction, x + 225, y + 75, "OWNER", C["paper0"], shadow=False)
        text(interaction, x + 278, y + 75, "Hearth", C["ink"], shadow=False)
        text(interaction, x + 225, y + 89, "NEXT", C["paper0"], shadow=False)
        text(interaction, x + 278, y + 89, "Build + link a Tavern", C["ink"], shadow=False)
        # Wax seal above the warning document.
        interaction.ellipse((x + 351, y + 34, x + 365, y + 48), fill=C["shadow"])
        interaction.ellipse((x + 348, y + 31, x + 362, y + 45), fill=C["bad"], outline=(110, 39, 30, 255))
    else:
        text(labels, x + 15, y + 118, "RECRUITMENT READY", C["paper2"])
        text(labels, x + 118, y + 118, "NEXT: A TRAVELER ARRIVES AUTOMATICALLY", C["muted"])

    if state == "hover":
        # One real communal slot rises exactly one logical pixel.
        sx, sy = x + 107, y + 43
        interaction.rectangle((sx + 2, sy + 2, sx + 18, sy + 18), fill=(0, 0, 0, 150))
        raised(interaction, (sx, sy - 1, sx + 16, sy + 15), C["slot"], C["brass2"], C["iron0"], 2)
        item(interaction, "oak_log", sx + 1, sy)
        # An authored leather tooltip uses the free world margin at 427px, so it
        # does not obscure stats, slots, labels or the recruitment notice.
        tx0, ty0 = x + 316, y + 70
        interaction.polygon([(tx0 + 3, ty0 + 3), (tx0 + 58, ty0 + 3), (tx0 + 58, ty0 + 20),
                             (tx0 + 5, ty0 + 20)], fill=C["shadow"])
        raised(interaction, (tx0, ty0, tx0 + 55, ty0 + 16), C["oak1"], C["brass2"], C["oak0"], 2)
        text(interaction, tx0 + 6, ty0 + 4, "Oak Log", C["paper2"])

    composed = layer(size)
    for image in layers:
        composed.alpha_composite(image)
    return composed, layers


def save_state(name, state, size=(427, 240), save_layers=True):
    image, layers = render(size, state)
    path = OUT / f"hearth_{name}_{size[0]}x{size[1]}.png"
    image.save(path)
    if save_layers:
        folder = LAYERS / f"hearth_{name}_427x240"
        folder.mkdir(parents=True, exist_ok=True)
        for (_, stem), item_layer in zip(NAMES, layers):
            item_layer.save(folder / f"{stem}.png")
    return image


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    LAYERS.mkdir(parents=True, exist_ok=True)
    normal = save_state("a_normal", "normal")
    hover = save_state("b_hover", "hover")
    blocked = save_state("c_blocked", "blocked")
    survival = save_state("survivability", "normal", (320, 240), False)

    # Value grouping check: no palette can conceal weak hierarchy here.
    value = Image.new("RGB", (427 * 3, 240), "black")
    for index, image in enumerate((normal, hover, blocked)):
        value.paste(ImageOps.grayscale(image.convert("RGB")).convert("RGB"), (index * 427, 0))
    value.save(OUT / "hearth_golden_slice_value_check.png")

    # Nearest-neighbour details at exactly 200 percent.
    crops = ((normal, (139, 48, 288, 143)), (hover, (156, 57, 278, 143)),
             (blocked, (267, 51, 371, 137)))
    detail = Image.new("RGB", (298 + 244 + 208, 190), C["table0"][:3])
    dx = 0
    for image, box in crops:
        crop = image.crop(box).resize(((box[2] - box[0]) * 2, (box[3] - box[1]) * 2), Image.Resampling.NEAREST)
        detail.paste(crop.convert("RGB"), (dx, 0)); dx += crop.width
    detail.save(OUT / "hearth_golden_slice_depth_200pct.png")

    # Review board with exactly five acceptance criteria.
    board = Image.new("RGB", (650, 850), (18, 15, 13))
    d = ImageDraw.Draw(board)
    text(d, 24, 18, "HEARTH - PREMIUM GOLDEN SLICE", C["paper2"])
    text(d, 24, 34, "Runtime-equivalent approval board - no gameplay authority changed", C["muted"])
    states = ((normal, "A - NORMAL OPERATION"), (hover, "B - REAL SLOT HOVER + TOOLTIP"),
              (blocked, "C - BLOCKED: STATE > CAUSE > OWNER > NEXT"))
    yy = 56
    for image, label in states:
        text(d, 24, yy, label, C["brass2"])
        board.paste(image.convert("RGB"), (24, yy + 14))
        yy += 258
    # 320x240 survivability proof and exactly five unscored approval criteria
    # use the side rail. Tobias, not this generator, owns the visual verdict.
    # side rail instead of leaving dead space beside the runtime viewport.
    text(d, 470, 58, "320 x 240 CHECK", C["brass2"])
    board.paste(survival.convert("RGB").resize((160, 120), Image.Resampling.NEAREST), (470, 74))
    criteria = ("DEPTH", "READABILITY", "INTUITIVENESS", "HEARTH IDENTITY", "RENDER-COST SAFETY")
    text(d, 470, 216, "APPROVAL GATE", C["brass2"])
    for index, criterion in enumerate(criteria):
        cy = 238 + index * 34
        text(d, 470, cy, criterion, C["muted"])
        text(d, 470, cy + 11, "[ ] APPROVE   [ ] REJECT", C["text"])
    text(d, 470, 432, "SELF-AUDIT", C["brass2"])
    text(d, 470, 447, "Physical at thumbnail", C["text"])
    text(d, 470, 458, "No scroll or clipping", C["text"])
    text(d, 470, 469, "Static sprite planes", C["text"])
    text(d, 24, 828, "VISUAL DIRECTION ONLY - ACTUAL ITEMS, FONT, FOCUS AND NARRATION STAY RUNTIME-OWNED", C["muted"])
    board.save(OUT / "hearth_golden_slice_approval_board.png")

    manifest = {
        "status": "OFFLINE RUNTIME-EQUIVALENT APPROVAL CANDIDATE — NOT IN-GAME VERIFIED",
        "scope": "Hearth only; Plaque, Development and Courier runtime integration paused",
        "geometry": {
            "ledger": [320, 220], "communal_anchor": [106, 42], "communal_slots": "6x4 at 18px",
            "player_inventory_anchor": [79, 142], "notice": [8, 114, 304, 16],
        },
        "actual_examples": ["Oak Log", "Wheat Seeds", "Iron Ingot", "Iron Axe", "Bread"],
        "blocked_source": "RecruitmentPolicy.Blocker.NO_TAVERN / hearthstead.gui.recruit_blocked.tavern",
        "criteria": [{"name": name.lower().replace(" ", "_"), "result": "PENDING_TOBIAS"} for name in criteria],
        "render_cost": "six static sprite planes; no generated textures, parallax, glow or per-frame layout",
        "files": [path.name for path in OUT.glob("*.png")],
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
