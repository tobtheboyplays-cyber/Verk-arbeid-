#!/usr/bin/env python3
"""Selected Concept #2: Hearth as a master carpenter's fold-out desk.

The 320x220 object uses the real Hearth slot anchors and fields. Outputs are
offline visual-approval assets only; Java/menu authority is intentionally not
touched.
"""
from pathlib import Path
import json
import random
from PIL import Image, ImageDraw, ImageFont, ImageOps, ImageStat

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "build" / "ui_art_direction" / "hearth_carpenter_desk"
LAYER_ROOT = ROOT / "tools" / "ui" / "art_direction" / "carpenter_desk_layers"
SOURCE_ROOT = ROOT / "tools" / "ui" / "art_direction" / "sources"
RUNTIME = ROOT / "tools" / "ui" / "art_direction" / "runtime_candidates" / "hearth_carpenter_desk"
FONT = ImageFont.load_default()

LAYERS = (
    ("BACK/CAST_SHADOW", "back_cast_shadow"),
    ("FRAME_WOOD", "frame_wood"),
    ("INSET_PAPER_OR_LEATHER", "inset_paper_or_leather"),
    ("FOREGROUND_HARDWARE", "foreground_hardware"),
    ("TEXT_AND_ICONS", "text_and_icons"),
    ("INTERACTION_STATE", "interaction_state"),
)

C = {
    "table": (15, 11, 8, 255), "tablegrain": (47, 28, 17, 255),
    "shadow": (3, 2, 2, 205), "oak0": (43, 24, 13, 255),
    "oak1": (72, 39, 20, 255), "oak2": (113, 68, 33, 255),
    "oak3": (174, 112, 54, 255), "oak4": (207, 147, 74, 255),
    "iron0": (35, 36, 35, 255), "iron1": (71, 72, 66, 255),
    "iron2": (126, 123, 105, 255), "brass0": (82, 54, 18, 255),
    "brass1": (151, 106, 34, 255), "brass2": (222, 177, 70, 255),
    "leather0": (48, 30, 21, 255), "leather1": (82, 51, 31, 255),
    "leather2": (135, 87, 44, 255), "paper0": (120, 91, 55, 255),
    "paper1": (207, 177, 121, 255), "paper2": (240, 219, 168, 255),
    "ink": (43, 31, 20, 255), "text": (239, 224, 188, 255),
    "muted": (188, 165, 119, 255), "slot": (22, 19, 16, 255),
    "good": (105, 157, 83, 255), "bad": (187, 69, 52, 255),
    "grass": (83, 112, 57, 255), "water": (61, 92, 101, 255),
}


def blank(size): return Image.new("RGBA", size, (0, 0, 0, 0))


def txt(draw, x, y, value, colour, anchor=None, shadow=True):
    box = draw.textbbox((0, 0), value, font=FONT)
    width = box[2] - box[0]
    if anchor == "center": x -= width // 2
    elif anchor == "right": x -= width
    if shadow: draw.text((x + 1, y + 1), value, font=FONT, fill=(0, 0, 0, 170))
    draw.text((x, y), value, font=FONT, fill=colour)


def raised(draw, box, face, hi, lo, depth=3):
    x0, y0, x1, y1 = box
    draw.rectangle((x0 + depth, y0 + depth, x1 + depth, y1 + depth), fill=C["shadow"])
    draw.rectangle(box, fill=face)
    draw.line((x0, y0, x1, y0), fill=hi)
    draw.line((x0, y0, x0, y1), fill=hi)
    draw.line((x0, y1, x1, y1), fill=lo)
    draw.line((x1, y0, x1, y1), fill=lo)


def recess(draw, box, face=None):
    x0, y0, x1, y1 = box
    draw.rectangle(box, fill=face or C["slot"])
    draw.line((x0, y0, x1, y0), fill=(4, 3, 3, 255), width=2)
    draw.line((x1 - 1, y0, x1 - 1, y1), fill=(5, 4, 3, 255), width=2)
    draw.line((x0 + 1, y1, x1 - 1, y1), fill=C["oak2"])
    draw.line((x0, y0 + 2, x0, y1), fill=C["oak3"])


def rivet(draw, x, y):
    draw.point((x + 1, y + 1), fill=C["shadow"])
    draw.point((x, y), fill=C["brass2"])
    draw.point((x + 1, y), fill=C["brass1"])


def item(draw, kind, x, y):
    if kind == "log":
        draw.rectangle((x + 2, y + 3, x + 11, y + 10), fill=C["oak2"])
        draw.line((x + 2, y + 3, x + 11, y + 3), fill=C["oak4"])
        draw.ellipse((x + 8, y + 4, x + 12, y + 10), fill=(191, 134, 69, 255), outline=C["oak0"])
    elif kind == "seed":
        for dx, dy in ((3, 10), (5, 6), (7, 9), (10, 4), (12, 8)):
            draw.line((x + dx, y + dy, x + dx + 2, y + dy - 3), fill=(118, 154, 70, 255))
    elif kind == "iron":
        draw.polygon([(x + 2, y + 7), (x + 5, y + 3), (x + 12, y + 3),
                      (x + 14, y + 8), (x + 11, y + 11), (x + 4, y + 11)], fill=C["iron2"])
        draw.line((x + 5, y + 4, x + 11, y + 4), fill=(190, 187, 169, 255))
    elif kind == "axe":
        draw.line((x + 3, y + 13, x + 11, y + 4), fill=C["oak3"], width=2)
        draw.polygon([(x + 8, y + 2), (x + 14, y + 2), (x + 14, y + 7),
                      (x + 10, y + 8), (x + 8, y + 5)], fill=C["iron2"])
    elif kind == "bread":
        draw.ellipse((x + 2, y + 4, x + 13, y + 11), fill=(190, 126, 45, 255), outline=C["oak0"])


def building_vignette(draw, ox, oy):
    """Tiny but recognizable Hearth hall; the identity anchor, not a fake control."""
    draw.rectangle((ox + 4, oy + 13, ox + 31, oy + 27), fill=C["oak1"])
    draw.polygon([(ox, oy + 14), (ox + 17, oy + 2), (ox + 35, oy + 14)], fill=C["oak0"])
    draw.line((ox + 3, oy + 13, ox + 17, oy + 4, ox + 32, oy + 13), fill=C["oak4"], width=2)
    draw.rectangle((ox + 15, oy + 18, ox + 21, oy + 27), fill=(26, 20, 16, 255))
    draw.rectangle((ox + 7, oy + 17, ox + 11, oy + 21), fill=C["brass2"])
    draw.rectangle((ox + 26, oy + 17, ox + 30, oy + 21), fill=C["brass2"])
    draw.rectangle((ox + 2, oy + 27, ox + 34, oy + 29), fill=C["grass"])
    # Hearth chimney and warm ember identify the building, without animation.
    draw.rectangle((ox + 25, oy + 3, ox + 29, oy + 10), fill=C["iron1"])
    draw.point((ox + 18, oy + 22), fill=(242, 127, 39, 255))


def tally(draw, labels, x, y, width, name, value, beads, value_colour):
    raised(draw, (x, y, x + width, y + 22), C["leather0"], C["leather2"], C["oak0"], 2)
    txt(labels, x + 4, y + 3, name, C["muted"])
    txt(labels, x + width - 4, y + 3, value, value_colour, anchor="right")
    draw.line((x + 7, y + 17, x + width - 7, y + 17), fill=C["brass0"], width=2)
    draw.line((x + 7, y + 16, x + width - 7, y + 16), fill=C["brass2"])
    for index in range(beads):
        bx = x + 11 + index * 7
        draw.rectangle((bx, y + 13, bx + 4, y + 20), fill=C["brass1"])
        draw.line((bx, y + 13, bx + 4, y + 13), fill=C["brass2"])


def leather_tab(draw, labels, x, width, name, state="idle"):
    top = 0 if state == "selected" else 2
    bottom = 22 if state == "selected" else 19
    pts = [(x + 3, top), (x + width - 4, top), (x + width - 1, top + 3),
           (x + width - 1, bottom - 4), (x + width - 5, bottom),
           (x + 4, bottom), (x, bottom - 4), (x, top + 3)]
    lift = -1 if state == "hover" else 0
    pts = [(px, py + lift) for px, py in pts]
    draw.polygon([(px + 2, py + 3) for px, py in pts], fill=C["shadow"])
    draw.polygon(pts, fill=C["leather1"] if state != "selected" else C["paper0"])
    draw.line(pts[:4], fill=C["leather2"] if state != "selected" else C["paper2"])
    draw.line((x + 5, top + 3 + lift, x + width - 6, top + 3 + lift), fill=C["brass0"])
    txt(labels, x + width // 2, top + 6 + lift, name,
        C["text"] if state != "selected" else C["ink"], anchor="center", shadow=state != "selected")
    rivet(draw, x + 4, top + 4 + lift); rivet(draw, x + width - 6, top + 4 + lift)


def render_wide(view, state):
    """405x228 authored desktop for a 427x240 viewport; never a scaled 320 UI."""
    planes = [blank(view) for _ in LAYERS]
    back, frame, inset, hardware, labels, interaction = map(ImageDraw.Draw, planes)
    w, h = view; x, y = 11, 11

    back.rectangle((0, 0, w - 1, h - 1), fill=C["table"])
    rng = random.Random(4272082)
    for yy in range(9, h, 21):
        back.line((0, yy, w - 1, yy), fill=(36, 22, 14, 255))
        for _ in range(4):
            gx = rng.randrange(w)
            back.line((gx, yy - 4, min(w - 1, gx + rng.randrange(8, 28)), yy - 4), fill=C["tablegrain"])

    # One unmistakable fold-out furniture silhouette: projecting beam, narrow
    # waist, splayed paper/map leaves, and a central drawer front.
    back.polygon([(x + 4, y + 11), (x + 400, y + 11), (x + 405, y + 219),
                  (x + 397, y + 228), (x + 8, y + 228), (x, y + 219)], fill=C["shadow"])
    frame.polygon([(x + 8, y + 8), (x + 397, y + 8), (x + 404, y + 15),
                   (x + 399, y + 213), (x + 391, y + 224), (x + 14, y + 224),
                   (x + 5, y + 215), (x, y + 16)], fill=C["oak0"])
    frame.rectangle((x + 3, y + 12, x + 401, y + 31), fill=C["oak1"])
    frame.line((x + 4, y + 12, x + 400, y + 12), fill=C["oak4"], width=2)
    frame.line((x + 4, y + 31, x + 400, y + 31), fill=C["oak0"], width=3)
    # Narrow hero shelf / waist keeps the lower leaves visibly separate.
    frame.polygon([(x + 8, y + 32), (x + 397, y + 32), (x + 394, y + 112),
                   (x + 11, y + 112)], fill=C["oak1"])
    frame.line((x + 10, y + 34, x + 395, y + 34), fill=C["oak3"])
    frame.rectangle((x + 8, y + 109, x + 397, y + 119), fill=C["oak0"])
    frame.line((x + 9, y + 109, x + 396, y + 109), fill=C["oak4"])
    # Splayed bottom leaves and deeper center drawer.
    frame.polygon([(x + 8, y + 117), (x + 105, y + 121), (x + 99, y + 216),
                   (x + 14, y + 222), (x + 4, y + 213)], fill=C["leather0"])
    frame.polygon([(x + 300, y + 121), (x + 397, y + 117), (x + 401, y + 213),
                   (x + 391, y + 222), (x + 306, y + 216)], fill=C["leather0"])
    frame.polygon([(x + 104, y + 117), (x + 301, y + 117), (x + 296, y + 221),
                   (x + 109, y + 221)], fill=C["oak1"])
    frame.line((x + 106, y + 118, x + 299, y + 118), fill=C["oak4"], width=2)
    # Pegged joinery visibly bridges shelves and leaves.
    for jx in (x + 7, x + 101, x + 298, x + 395):
        frame.rectangle((jx, y + 31, jx + 6, y + 214), fill=C["oak0"])
        frame.line((jx, y + 31, jx, y + 213), fill=C["oak3"])

    # Full-width top beam/bookmark controls.
    tabs = (("Settlement", 78), ("Mayor", 68), ("Journey", 64),
            ("Requests", 78), ("Development", 96))
    tx = x + 3
    for index, (name, width) in enumerate(tabs):
        leather_tab(frame, labels, tx, width, name, "selected" if index == 0 else "idle")
        tx += width + 4

    # Upper hero shelf: a substantially larger, readable Hearth hall and one
    # forged title plate. A stone hearth-relief balances the opposite end.
    raised(inset, (x + 17, y + 37, x + 126, y + 78), C["iron0"], C["iron2"], C["iron0"], 3)
    building_vignette(hardware, x + 78, y + 43)
    txt(labels, x + 24, y + 45, "ALDERWATCH", C["text"])
    txt(labels, x + 24, y + 58, "HEARTH", C["brass2"])
    raised(inset, (x + 139, y + 36, x + 265, y + 50), C["iron0"], C["iron2"], C["iron0"], 3)
    txt(labels, x + 202, y + 39, "COMMUNAL STORES", C["text"], anchor="center")
    raised(inset, (x + 318, y + 38, x + 388, y + 77), C["iron0"], C["iron2"], C["iron0"], 3)
    inset.rectangle((x + 337, y + 50, x + 369, y + 70), fill=C["oak0"])
    inset.polygon([(x + 333, y + 51), (x + 353, y + 38), (x + 373, y + 51)], fill=C["iron1"])
    inset.rectangle((x + 349, y + 57, x + 357, y + 70), fill=C["slot"])
    inset.point((x + 353, y + 63), fill=(241, 119, 37, 255))

    # Communal drawer uses the actual 6x4 / 18px rhythm; it overlaps the hero
    # shelf and tally deck exactly like the selected furniture reference.
    recess(inset, (x + 143, y + 48, x + 261, y + 133), (29, 23, 18, 255))
    for row in range(4):
        for col in range(6):
            sx, sy = x + 149 + col * 18, y + 57 + row * 18
            recess(inset, (sx, sy, sx + 16, sy + 16))
    item(inset, "log", x + 150, y + 58); item(inset, "seed", x + 168, y + 58); item(inset, "iron", x + 222, y + 58)
    raised(hardware, (x + 190, y + 128, x + 214, y + 137), C["brass0"], C["brass2"], C["oak0"], 2)

    # Four broad tally rails sit on the physical middle deck, not tiny cards.
    tally(inset, labels, x + 16, y + 81, 112, "POPULATION", "7 / 10", 7, C["good"])
    tally(inset, labels, x + 16, y + 104, 112, "EMPLOYED", "5 / 7", 5, C["text"])
    tally(inset, labels, x + 276, y + 81, 112, "FOOD", "126", 7, C["text"])
    tally(inset, labels, x + 276, y + 104, 112, "MORALE", "82 / 100", 7, C["good"])

    # Left clipped work order. State, reason and action remain large enough to
    # read without wrapping a phrase into unusable fragments.
    inset.polygon([(x + 16, y + 125), (x + 96, y + 121), (x + 105, y + 131),
                   (x + 99, y + 220), (x + 18, y + 224), (x + 10, y + 215)], fill=C["shadow"])
    inset.polygon([(x + 13, y + 122), (x + 93, y + 118), (x + 102, y + 128),
                   (x + 96, y + 217), (x + 15, y + 221), (x + 7, y + 212)], fill=C["paper1"])
    inset.line((x + 18, y + 125, x + 91, y + 121), fill=C["paper2"])
    raised(hardware, (x + 39, y + 117, x + 72, y + 126), C["brass1"], C["brass2"], C["brass0"], 2)
    txt(labels, x + 54, y + 132, "NEXT ACTION", C["ink"], anchor="center", shadow=False)
    if state == "blocked":
        txt(labels, x + 18, y + 144, "STATE", C["paper0"], shadow=False)
        txt(labels, x + 54, y + 144, "BLOCKED", C["bad"], shadow=False)
        txt(labels, x + 18, y + 155, "CAUSE", C["paper0"], shadow=False)
        txt(labels, x + 54, y + 155, "No active", C["ink"], shadow=False)
        txt(labels, x + 54, y + 165, "Tavern", C["ink"], shadow=False)
        txt(labels, x + 18, y + 176, "OWNER", C["paper0"], shadow=False)
        txt(labels, x + 54, y + 176, "Hearth", C["ink"], shadow=False)
        txt(labels, x + 18, y + 187, "NEXT", C["paper0"], shadow=False)
        txt(labels, x + 18, y + 198, "Build + link", C["ink"], shadow=False)
        txt(labels, x + 18, y + 208, "a Tavern", C["ink"], shadow=False)
    else:
        txt(labels, x + 18, y + 151, "STATE", C["paper0"], shadow=False)
        txt(labels, x + 54, y + 151, "READY", (63, 106, 51, 255), shadow=False)
        txt(labels, x + 18, y + 168, "NEXT", C["paper0"], shadow=False)
        txt(labels, x + 18, y + 180, "Await traveler", C["ink"], shadow=False)

    # Actual 9x3 + hotbar player inventory in the open central lower drawer.
    recess(inset, (x + 111, y + 139, x + 294, y + 222), (38, 29, 21, 255))
    raised(hardware, (x + 164, y + 136, x + 241, y + 146), C["brass1"], C["brass2"], C["brass0"], 2)
    txt(labels, x + 202, y + 138, "INVENTORY", C["ink"], anchor="center", shadow=False)
    for row in range(3):
        for col in range(9):
            sx, sy = x + 122 + col * 18, y + 148 + row * 18
            recess(inset, (sx, sy, sx + 16, sy + 16))
    for col in range(9):
        sx, sy = x + 122 + col * 18, y + 204
        recess(inset, (sx, sy, sx + 16, sy + 16))
    item(inset, "axe", x + 123, y + 205); item(inset, "bread", x + 141, y + 205)

    # Right survey/map leaf owns radius and stable settlement state.
    recess(inset, (x + 302, y + 124, x + 395, y + 213), C["paper0"])
    inset.rectangle((x + 309, y + 131, x + 388, y + 183), fill=(101, 113, 69, 255))
    inset.line((x + 353, y + 132, x + 360, y + 181), fill=C["water"], width=4)
    inset.ellipse((x + 321, y + 133, x + 376, y + 181), outline=C["paper2"], width=2)
    inset.rectangle((x + 345, y + 153, x + 354, y + 162), fill=C["oak0"])
    inset.point((x + 349, y + 157), fill=(244, 123, 36, 255))
    # Brass ruler and slider make the instrument physically legible.
    inset.line((x + 312, y + 188, x + 385, y + 188), fill=C["brass2"], width=2)
    for mark in range(x + 314, x + 384, 8): inset.line((mark, y + 186, mark, y + 191), fill=C["brass0"])
    raised(hardware, (x + 358, y + 184, x + 365, y + 194), C["brass1"], C["brass2"], C["brass0"], 2)
    txt(labels, x + 311, y + 196, "RADIUS", C["ink"], shadow=False)
    txt(labels, x + 386, y + 196, "64 m", C["ink"], anchor="right", shadow=False)
    raised(hardware, (x + 321, y + 211, x + 378, y + 220), C["leather0"], C["leather2"], C["oak0"], 2)
    txt(labels, x + 349, y + 212, "STABLE", C["good"], anchor="center")

    # Thick joinery and fasteners sit on the foreground plane.
    for rx, ry in ((x + 6, y + 31), (x + 393, y + 31), (x + 9, y + 210), (x + 390, y + 210)):
        hardware.rectangle((rx, ry, rx + 7, ry + 7), fill=C["iron1"])
        hardware.line((rx, ry, rx + 7, ry), fill=C["iron2"])
        rivet(hardware, rx + 3, ry + 3)

    if state == "hover":
        sx, sy = x + 149, y + 57
        interaction.rectangle((sx + 2, sy + 2, sx + 18, sy + 18), fill=C["shadow"])
        raised(interaction, (sx, sy - 1, sx + 16, sy + 15), C["slot"], C["brass2"], C["oak0"], 2)
        item(interaction, "log", sx + 1, sy)
        raised(interaction, (x + 353, y + 57, x + 408, y + 73), C["leather0"], C["leather2"], C["oak0"], 2)
        txt(interaction, x + 359, y + 61, "Oak Log", C["paper2"])

    result = blank(view)
    for plane in planes: result.alpha_composite(plane)
    return result, planes


def render(view=(427, 240), state="normal"):
    if view[0] >= 400:
        return render_wide(view, state)
    planes = [blank(view) for _ in LAYERS]
    back, frame, inset, hardware, labels, interaction = map(ImageDraw.Draw, planes)
    w, h = view; x = (w - 320) // 2; y = 18

    # Plane 1: tabletop, only broad quiet grain.
    back.rectangle((0, 0, w - 1, h - 1), fill=C["table"])
    rng = random.Random(2082 + w)
    for yy in range(10, h, 21):
        back.line((0, yy, w - 1, yy), fill=(34, 22, 15, 255))
        for _ in range(3):
            gx = rng.randrange(w)
            back.line((gx, yy - 4, min(w - 1, gx + rng.randrange(8, 25)), yy - 4), fill=C["tablegrain"])

    # Fold-out desk silhouette: top beam, side joinery, lower wings and drawer.
    back.polygon([(x + 7, y + 8), (x + 317, y + 8), (x + 320, y + 214),
                  (x + 313, y + 220), (x + 8, y + 220), (x + 1, y + 213)], fill=C["shadow"])
    frame.polygon([(x + 7, y), (x + 313, y), (x + 319, y + 7),
                   (x + 319, y + 211), (x + 311, y + 219),
                   (x + 8, y + 219), (x, y + 211), (x, y + 8)], fill=C["oak0"])
    frame.line((x + 8, y, x + 312, y), fill=C["oak4"], width=2)
    frame.line((x, y + 8, x, y + 210), fill=C["oak3"])
    frame.rectangle((x + 5, y + 5, x + 314, y + 214), fill=C["oak1"])
    frame.line((x + 6, y + 5, x + 313, y + 5), fill=C["oak3"])
    # Joinery rails intentionally overlap the body instead of outlining panels.
    frame.rectangle((x + 7, y + 25, x + 312, y + 31), fill=C["oak0"])
    frame.line((x + 8, y + 25, x + 311, y + 25), fill=C["oak4"])
    frame.rectangle((x + 7, y + 111, x + 312, y + 119), fill=C["oak0"])
    frame.line((x + 8, y + 111, x + 311, y + 111), fill=C["oak3"])
    for jx in (x + 5, x + 98, x + 219, x + 314):
        frame.rectangle((jx, y + 30, jx + 4, y + 211), fill=C["oak0"])
        frame.line((jx, y + 30, jx, y + 210), fill=C["oak3"])
    # Side fold-out wings read as supported desk leaves.
    frame.polygon([(x + 7, y + 121), (x + 72, y + 126), (x + 70, y + 211),
                   (x + 10, y + 215), (x + 4, y + 208)], fill=C["leather0"])
    frame.polygon([(x + 248, y + 126), (x + 313, y + 121), (x + 317, y + 208),
                   (x + 310, y + 215), (x + 250, y + 211)], fill=C["leather0"])

    # Five real controls, now integrated into the beam as leather bookmarks.
    tabs = (("Settlement", 64), ("Mayor", 56), ("Journey", 52),
            ("Requests", 56), ("Development", 72))
    tx = x + 2
    for index, (name, width) in enumerate(tabs):
        tab_state = "selected" if index == 0 else ("hover" if state == "hover" and index == 3 else "idle")
        leather_tab(frame if tab_state != "hover" else interaction,
                    labels if tab_state != "hover" else interaction,
                    tx, width, name, tab_state)
        tx += width + 4

    # Identity/header: settlement name and miniature Hearth hall share one
    # carved maker's plate, separate from the communal drawer label.
    raised(inset, (x + 8, y + 29, x + 98, y + 57), C["iron0"], C["iron2"], C["iron0"], 3)
    txt(labels, x + 13, y + 34, "ALDERWATCH", C["text"])
    txt(labels, x + 13, y + 45, "HEARTH", C["brass2"])
    building_vignette(hardware, x + 63, y + 27)

    # Physical tally rails use the real population/employed/food fields.
    tally(inset, labels, x + 8, y + 60, 87, "POPULATION", "7 / 10", 7, C["good"])
    tally(inset, labels, x + 8, y + 85, 87, "EMPLOYED", "5 / 7", 5, C["text"])
    tally(inset, labels, x + 223, y + 60, 88, "FOOD", "126", 6, C["text"])
    tally(inset, labels, x + 223, y + 85, 88, "MORALE", "82 / 100", 6, C["good"])

    # Central communal stores: actual 6x4 slots in one open recessed drawer.
    recess(inset, (x + 101, y + 34, x + 218, y + 118), (31, 25, 20, 255))
    inset.line((x + 103, y + 116, x + 216, y + 116), fill=C["oak3"])
    for row in range(4):
        for col in range(6):
            sx, sy = x + 107 + col * 18, y + 43 + row * 18
            recess(inset, (sx, sy, sx + 16, sy + 16))
    item(inset, "log", x + 108, y + 44)
    item(inset, "seed", x + 126, y + 44)
    item(inset, "iron", x + 180, y + 44)
    # Brass drawer label and pull are foreground occlusions.
    raised(hardware, (x + 105, y + 31, x + 214, y + 41), C["brass1"], C["brass2"], C["brass0"], 2)
    txt(labels, x + 160, y + 33, "COMMUNAL STORES", C["ink"], anchor="center", shadow=False)
    raised(hardware, (x + 151, y + 113, x + 169, y + 121), C["brass0"], C["brass2"], C["oak0"], 2)

    # Left clipped work order is the real recruitment state/next action plane.
    inset.polygon([(x + 10, y + 126), (x + 62, y + 123), (x + 71, y + 132),
                   (x + 68, y + 207), (x + 12, y + 211), (x + 7, y + 205)], fill=C["shadow"])
    inset.polygon([(x + 8, y + 123), (x + 60, y + 120), (x + 69, y + 129),
                   (x + 66, y + 204), (x + 10, y + 208), (x + 5, y + 202)], fill=C["paper1"])
    inset.line((x + 12, y + 125, x + 58, y + 123), fill=C["paper2"])
    raised(hardware, (x + 22, y + 118, x + 51, y + 126), C["brass1"], C["brass2"], C["brass0"], 2)
    txt(labels, x + 37, y + 132, "NEXT ACTION", C["ink"], anchor="center", shadow=False)
    if state == "blocked":
        txt(labels, x + 13, y + 150, "BLOCKED", C["bad"], shadow=False)
        txt(labels, x + 13, y + 163, "No Tavern", C["ink"], shadow=False)
        txt(labels, x + 13, y + 180, "Build + link", C["ink"], shadow=False)
        txt(labels, x + 13, y + 191, "a Tavern", C["ink"], shadow=False)
    else:
        txt(labels, x + 13, y + 150, "READY", (65, 108, 53, 255), shadow=False)
        txt(labels, x + 13, y + 164, "Await the", C["ink"], shadow=False)
        txt(labels, x + 13, y + 175, "next traveler", C["ink"], shadow=False)

    # Actual player inventory geometry in the desk's open lower drawer.
    recess(inset, (x + 72, y + 130, x + 247, y + 218), (42, 31, 22, 255))
    txt(labels, x + 84, y + 132, "INVENTORY", C["muted"])
    for row in range(3):
        for col in range(9):
            sx, sy = x + 80 + col * 18, y + 143 + row * 18
            recess(inset, (sx, sy, sx + 16, sy + 16))
    for col in range(9):
        sx, sy = x + 80 + col * 18, y + 201
        recess(inset, (sx, sy, sx + 16, sy + 16))
    item(inset, "axe", x + 81, y + 202); item(inset, "bread", x + 99, y + 202)

    # Right map/radius instrument; decorative geography, real radius/status.
    recess(inset, (x + 250, y + 127, x + 312, y + 207), C["paper0"])
    inset.rectangle((x + 255, y + 133, x + 307, y + 181), fill=(101, 112, 69, 255))
    inset.line((x + 282, y + 135, x + 290, y + 179), fill=C["water"], width=3)
    inset.ellipse((x + 261, y + 137, x + 302, y + 178), outline=C["paper2"], width=2)
    inset.rectangle((x + 278, y + 154, x + 285, y + 161), fill=C["oak0"])
    inset.point((x + 281, y + 157), fill=(242, 125, 37, 255))
    raised(hardware, (x + 256, y + 184, x + 306, y + 202), C["iron0"], C["iron2"], C["iron0"], 2)
    txt(labels, x + 281, y + 186, "RADIUS", C["muted"], anchor="center")
    txt(labels, x + 281, y + 195, "64 m", C["text"], anchor="center")
    # Stable state seal is a tiny physical tag, not a glowing status panel.
    raised(hardware, (x + 253, y + 207, x + 309, y + 216), C["leather0"], C["leather2"], C["oak0"], 2)
    txt(labels, x + 281, y + 208, "STABLE", C["good"], anchor="center")

    # Thick metal corner joinery visibly sits above every leaf.
    for rx, ry in ((x + 6, y + 29), (x + 309, y + 29), (x + 6, y + 207), (x + 309, y + 207)):
        hardware.rectangle((rx, ry, rx + 5, ry + 5), fill=C["iron1"])
        hardware.line((rx, ry, rx + 5, ry), fill=C["iron2"])
        rivet(hardware, rx + 2, ry + 2)

    if state == "hover":
        # One real Oak Log slot lifts one pixel; tooltip is authored leather.
        sx, sy = x + 107, y + 43
        interaction.rectangle((sx + 2, sy + 2, sx + 18, sy + 18), fill=C["shadow"])
        raised(interaction, (sx, sy - 1, sx + 16, sy + 15), C["slot"], C["brass2"], C["oak0"], 2)
        item(interaction, "log", sx + 1, sy)
        tx0, ty0 = x + 316, y + 55
        raised(interaction, (tx0, ty0, tx0 + 55, ty0 + 16), C["leather0"], C["leather2"], C["oak0"], 2)
        txt(interaction, tx0 + 6, ty0 + 4, "Oak Log", C["paper2"])

    result = blank(view)
    for plane in planes: result.alpha_composite(plane)
    return result, planes


def save(name, state, view=(427, 240), layer_output=True):
    image, planes = render(view, state)
    image.save(OUT / f"hearth_carpenter_{name}_{view[0]}x{view[1]}.png")
    if layer_output:
        folder = LAYER_ROOT / f"hearth_carpenter_{name}_427x240"
        folder.mkdir(parents=True, exist_ok=True)
        for (_, stem), plane in zip(LAYERS, planes): plane.save(folder / f"{stem}.png")
    return image


def runtime_fragments():
    # Static, runtime-feasible parts. Insets are authored with quiet centres so
    # they are safe to nine-slice without stretching grain or hardware.
    RUNTIME.mkdir(parents=True, exist_ok=True)
    _, planes = render((320, 240), "normal")
    planes[1].crop((0, 18, 320, 238)).save(RUNTIME / "desk_frame_320x220.png")
    planes[2].crop((0, 18, 320, 238)).save(RUNTIME / "desk_insets_320x220.png")
    planes[3].crop((0, 18, 320, 238)).save(RUNTIME / "desk_hardware_320x220.png")
    # Canonical small components with four-pixel protected corners.
    parts = Image.new("RGBA", (192, 48), (0, 0, 0, 0)); d = ImageDraw.Draw(parts)
    raised(d, (0, 0, 63, 19), C["leather0"], C["leather2"], C["oak0"], 2)
    raised(d, (68, 0, 131, 19), C["paper0"], C["paper2"], C["paper0"], 2)
    recess(d, (136, 0, 153, 17)); raised(d, (158, 0, 190, 12), C["brass1"], C["brass2"], C["brass0"], 2)
    parts.save(RUNTIME / "nine_slice_parts.png")
    (RUNTIME / "manifest.json").write_text(json.dumps({
        "status": "runtime candidate only; no Java integration",
        "light": "top-left", "protected_corner": 4,
        "parts": {"desk_frame_320x220.png": "static main plane",
                  "desk_insets_320x220.png": "static recess plane",
                  "desk_hardware_320x220.png": "static foreground plane",
                  "nine_slice_parts.png": "idle leather, paper, slot, brass"}}, indent=2), encoding="utf-8")


def main():
    OUT.mkdir(parents=True, exist_ok=True); LAYER_ROOT.mkdir(parents=True, exist_ok=True)
    normal = save("a_normal", "normal")
    hover = save("b_hover", "hover")
    blocked = save("c_blocked", "blocked")
    small = save("survivability", "normal", (320, 240), False)
    runtime_fragments()

    value = Image.new("RGB", (427 * 3, 240), "black")
    for i, image in enumerate((normal, hover, blocked)):
        value.paste(ImageOps.grayscale(image.convert("RGB")).convert("RGB"), (i * 427, 0))
    value.save(OUT / "hearth_carpenter_value_check.png")

    boxes = ((normal, (105, 42, 273, 141)), (hover, (150, 45, 245, 120)),
             (blocked, (54, 132, 132, 228)))
    widths = [(b[2] - b[0]) * 2 for _, b in boxes]
    detail = Image.new("RGB", (sum(widths), 198), C["table"][:3]); dx = 0
    for image, box in boxes:
        crop = image.crop(box).resize(((box[2] - box[0]) * 2, (box[3] - box[1]) * 2), Image.Resampling.NEAREST)
        detail.paste(crop.convert("RGB"), (dx, 0)); dx += crop.width
    detail.save(OUT / "hearth_carpenter_depth_200pct.png")

    board = Image.new("RGB", (650, 850), (17, 13, 10)); d = ImageDraw.Draw(board)
    txt(d, 24, 16, "SELECTED #2 - MASTER CARPENTER'S FOLD-OUT DESK", C["paper2"])
    txt(d, 24, 31, "Actual Hearth fields and slot geometry - visual approval only", C["muted"])
    yy = 52
    for image, label in ((normal, "A - NORMAL"), (hover, "B - REAL SLOT HOVER"), (blocked, "C - BLOCKED WORK ORDER")):
        txt(d, 24, yy, label, C["brass2"]); board.paste(image.convert("RGB"), (24, yy + 14)); yy += 258
    txt(d, 470, 54, "320 x 240", C["brass2"])
    board.paste(small.convert("RGB").resize((160, 120), Image.Resampling.NEAREST), (470, 70))
    criteria = ("DEPTH", "READABILITY", "INTUITIVENESS", "HEARTH IDENTITY", "RENDER-COST SAFETY")
    txt(d, 470, 210, "TOBIAS APPROVAL", C["brass2"])
    for i, criterion in enumerate(criteria):
        cy = 232 + i * 38
        txt(d, 470, cy, criterion, C["muted"])
        txt(d, 470, cy + 12, "[ ] APPROVE  [ ] REJECT", C["text"])
    txt(d, 470, 442, "BOUNDARIES", C["brass2"])
    for i, line in enumerate(("No fake slots", "No gameplay changes", "No Plaque/Dev/Courier", "No glow or per-frame art")):
        txt(d, 470, 458 + i * 12, line, C["text"])
    txt(d, 24, 829, "PENDING TOBIAS - NO SELF-AWARDED VISUAL PASS", C["muted"])
    board.save(OUT / "hearth_carpenter_approval_board.png")

    manifest = {
        "status": "SELECTED CONCEPT #2 - OFFLINE MOCKUP - PENDING TOBIAS",
        "source_reference": "tools/ui/art_direction/selected_concepts/hearth_master_carpenter_desk_selected.png",
        "geometry": {
            "wide": {"object": [405, 228], "communal": [149, 57, "6x4 @ 18px"],
                     "player": [122, 148, "9x3 + hotbar @ 18px"]},
            "compact": {"object": [320, 220], "communal": [106, 42, "6x4 @ 18px"],
                        "player": [79, 142, "9x3 + hotbar @ 18px"]}},
        "criteria": [{"name": c.lower().replace(" ", "_"), "result": "PENDING_TOBIAS"} for c in criteria],
        "runtime_fragments": [p.name for p in RUNTIME.glob("*.png")],
        "value_stddev": round(ImageStat.Stat(normal.crop((53, 18, 373, 238)).convert("L")).stddev[0], 2),
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")


if __name__ == "__main__": main()
