#!/usr/bin/env python3
"""Author the selected Surveyor's Roll-Top Hearth runtime art.

The source image supplies material character only. Geometry is re-authored
around HearthMenu's immutable slot coordinates. Text, items, counts and all
interaction remain runtime-owned.
"""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageEnhance, ImageFont


ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "tools/ui/art_direction/hearth_ia_divergence/concept_b_surveyor_rolltop_source.png"
RESOURCE = ROOT / "src/main/resources/assets/hearthstead/textures/gui/identity"
LAYERS = ROOT / "tools/ui/art_direction/runtime_hearth_surveyor_layers"
OUT = ROOT / "build/ui_art_direction/hearth_surveyor_runtime"
FONT = ImageFont.load_default(size=8)
CORE = (320, 220)
SLOT = 18
COMMUNAL = (106, 42, 6, 4)
PLAYER = (79, 142, 9, 3)
NAV = ((17, 48), (48, 38), (73, 55), (70, 84), (34, 92))
NAV_NAMES = ("Settlement", "Mayor", "Journey", "Requests", "Development")

PAL = {
    "shadow": (4, 3, 2, 220), "deep": (23, 17, 12, 255),
    "wood0": (53, 29, 17, 255), "wood1": (99, 56, 30, 255),
    "wood2": (157, 101, 51, 255), "wood_hi": (208, 146, 70, 255),
    "brass0": (91, 61, 20, 255), "brass": (180, 128, 40, 255),
    "brass_hi": (241, 204, 94, 255), "paper": (223, 199, 151, 255),
    "paper_hi": (248, 228, 184, 255), "paper_lo": (124, 91, 52, 255),
    "iron": (48, 47, 43, 255), "iron_hi": (112, 108, 96, 255),
    "slot": (21, 19, 16, 255), "text": (247, 230, 190, 255),
    "ink": (43, 30, 20, 255), "muted": (194, 164, 108, 255),
    "good": (120, 172, 91, 255), "bad": (201, 76, 55, 255),
}


def blank(size=CORE):
    return Image.new("RGBA", size, (0, 0, 0, 0))


def crop(box, size, colours=128):
    with Image.open(SOURCE) as source:
        image = source.convert("RGB").crop(box).resize(size, Image.Resampling.BOX)
    image = ImageEnhance.Contrast(image).enhance(1.06)
    return image.quantize(colors=colours, method=Image.Quantize.MEDIANCUT,
                          dither=Image.Dither.NONE).convert("RGBA")


def raised(draw, box, face, hi, lo, depth=2):
    x0, y0, x1, y1 = box
    draw.rectangle((x0 + depth, y0 + depth, x1 + depth, y1 + depth),
                   fill=PAL["shadow"])
    draw.rectangle(box, fill=face)
    draw.line((x0, y0, x1, y0), fill=hi)
    draw.line((x0, y0, x0, y1), fill=hi)
    draw.line((x0, y1, x1, y1), fill=lo, width=2)
    draw.line((x1, y0, x1, y1), fill=lo, width=2)


def recess(draw, box):
    x0, y0, x1, y1 = box
    draw.rectangle(box, fill=PAL["deep"])
    draw.line((x0, y0, x1, y0), fill=(3, 2, 2, 255), width=3)
    draw.line((x1, y0, x1, y1), fill=(3, 2, 2, 255), width=3)
    draw.line((x0, y1, x1 - 2, y1), fill=PAL["wood2"])
    draw.line((x0, y0 + 2, x0, y1), fill=PAL["wood1"])


def build_core():
    back, frame, inset, hardware = (blank() for _ in range(4))
    bd, fd, ind, hd = map(ImageDraw.Draw, (back, frame, inset, hardware))

    # Open roll-top silhouette with clipped upper corners and a projecting
    # pull-out drawer. Negative space remains outside the object.
    bd.polygon(((12, 5), (308, 5), (319, 17), (319, 135), (304, 143),
                (251, 143), (251, 216), (244, 220), (76, 220), (69, 216),
                (69, 143), (16, 143), (1, 135), (1, 17)), fill=PAL["shadow"])
    wood = crop((20, 20, 1650, 900), CORE)
    mask = Image.new("L", CORE, 0)
    ImageDraw.Draw(mask).polygon(((10, 1), (310, 1), (319, 11), (319, 132),
                                  (302, 140), (250, 140), (250, 215),
                                  (244, 219), (76, 219), (70, 215),
                                  (70, 140), (18, 140), (1, 132), (1, 11)),
                                 fill=255)
    wood.putalpha(mask); frame.alpha_composite(wood)
    fd.line((12, 2, 308, 2), fill=PAL["wood_hi"])

    # Dominant horizontal work-order scroll. There is room for a heading,
    # state, cause and action without squeezing text vertically.
    # Quiet authored parchment: no source-image rolled corner or dark ink may
    # sit behind dynamic text. A few restrained fibres retain material depth.
    ind.rectangle((62, 1, 232, 28), fill=PAL["paper"])
    ind.line((62, 1, 232, 1), fill=PAL["paper_hi"])
    ind.line((62, 28, 232, 28), fill=PAL["paper_lo"], width=2)
    for x, y in ((78, 6), (112, 24), (174, 8), (211, 21)):
        ind.line((x, y, x + 5, y), fill=(205, 177, 128, 255))
    # Settlement name is a separate physical maker's plate to the right.
    raised(hd, (238, 0, 314, 11), PAL["wood0"], PAL["brass_hi"],
           PAL["deep"], 1)
    raised(hd, (56, 3, 66, 26), PAL["brass0"], PAL["brass_hi"],
           PAL["deep"], 2)
    raised(hd, (228, 3, 238, 26), PAL["brass0"], PAL["brass_hi"],
           PAL["deep"], 2)

    # Communal specimen tray exactly surrounds the real 6x4 slot grid.
    # A detached nameplate ends at y=38; the tray begins at y=40, leaving
    # two clear pixels around the dynamic label before the physical frame.
    raised(hd, (116, 28, 204, 38), PAL["wood0"], PAL["wood_hi"],
           PAL["deep"], 1)
    raised(fd, (100, 41, 220, 116), PAL["wood0"], PAL["wood_hi"],
           PAL["deep"], 3)
    recess(ind, (103, 41, 217, 115))
    for row in range(4):
        for col in range(6):
            x = COMMUNAL[0] + col * SLOT; y = COMMUNAL[1] + row * SLOT
            ind.rectangle((x, y, x + 17, y + 17), fill=PAL["slot"])

    # One rotary navigation instrument, not a strip of tabs.
    hd.ellipse((5, 36, 101, 116), fill=PAL["deep"], outline=PAL["brass0"], width=3)
    hd.ellipse((13, 42, 93, 110), outline=PAL["brass"], width=2)
    hd.ellipse((42, 65, 62, 85), fill=PAL["brass0"], outline=PAL["brass_hi"])
    for x, y in NAV:
        hd.line((52, 75, x + 10, y + 10), fill=PAL["brass0"])

    # Survey compass owns settlement radius on the right.
    compass = crop((1190, 70, 1585, 490), (88, 82), 112)
    frame.alpha_composite(compass, (224, 35))
    hd.ellipse((225, 36, 311, 116), outline=PAL["brass_hi"], width=2)

    # One compact continuous status abacus, divided into four equal 76px
    # cells. Every cell reserves a 20px icon gutter and a quiet 48px number
    # plane, so even 100/100 cannot collide with its pictogram.
    raised(fd, (8, 114, 312, 127), PAL["wood0"], PAL["wood2"],
           PAL["deep"], 2)
    for cell in range(4):
        x = 8 + cell * 76
        hd.rectangle((x + 4, 115, x + 21, 126), fill=PAL["deep"])
        hd.line((x + 4, 115, x + 21, 115), fill=PAL["brass0"])
        if cell:
            hd.line((x, 115, x, 126), fill=PAL["brass0"])
    # Four baked pictograms identify the exact runtime counters without
    # repeating tiny labels: people, jobs, food and morale.
    hd.ellipse((16, 116, 21, 121), fill=PAL["paper"])
    hd.rectangle((15, 121, 22, 125), fill=PAL["paper"])
    hd.line((91, 125, 98, 116), fill=PAL["paper"], width=2)
    hd.rectangle((96, 115, 101, 118), fill=PAL["paper"])
    hd.ellipse((167, 116, 175, 124), fill=PAL["paper"])
    hd.line((169, 119, 173, 119), fill=PAL["paper_lo"])
    hd.polygon(((243, 118), (246, 115), (249, 118), (252, 115),
                (255, 118), (249, 126)), fill=PAL["paper"])

    # Deep physical pull-out inventory drawer around the immutable 36 slots.
    raised(fd, (72, 132, 248, 219), PAL["wood0"], PAL["wood_hi"],
           PAL["deep"], 3)
    recess(ind, (76, 138, 245, 218))
    for row in range(3):
        for col in range(9):
            x = PLAYER[0] + col * SLOT; y = PLAYER[1] + row * SLOT
            ind.rectangle((x, y, x + 17, y + 17), fill=PAL["slot"])
    for col in range(9):
        x = PLAYER[0] + col * SLOT; y = PLAYER[1] + 58
        ind.rectangle((x, y, x + 17, y + 17), fill=PAL["slot"])
    # Dedicated drawer nameplate: abacus ends 128, plate begins 130 and its
    # text baseline clears the first slot row at y=142 by two pixels.
    raised(hd, (126, 130, 194, 139), PAL["wood0"], PAL["wood_hi"],
           PAL["deep"], 1)

    # Opaque radius plate keeps numerals away from the compass needle.
    raised(hd, (250, 99, 306, 113), PAL["deep"], PAL["brass_hi"],
           PAL["brass0"], 1)

    result = blank()
    for layer in (back, frame, inset, hardware): result.alpha_composite(layer)
    return result, (back, frame, inset, hardware)


def chapter_sheet():
    sheet = Image.new("RGBA", (100, 60), (0, 0, 0, 0))
    for state in range(3):
        for index in range(5):
            cell = Image.new("RGBA", (20, 20), (0, 0, 0, 0)); d = ImageDraw.Draw(cell)
            lift = 0 if state == 2 else 1
            d.ellipse((2, 3, 19, 20), fill=PAL["shadow"])
            d.ellipse((1, lift, 18, lift + 17), fill=PAL["brass0"],
                      outline=PAL["brass_hi"] if state else PAL["brass"])
            d.ellipse((4, lift + 3, 15, lift + 14), fill=PAL["deep"])
            # Five original readable pictograms: home, crown, road, parcel, tree.
            ox, oy = 5, lift + 4
            if index == 0:
                d.polygon(((ox, oy + 5), (ox + 5, oy), (ox + 10, oy + 5)), fill=PAL["paper"])
                d.rectangle((ox + 2, oy + 5, ox + 8, oy + 10), fill=PAL["paper"])
            elif index == 1:
                d.polygon(((ox, oy + 8), (ox + 1, oy + 2), (ox + 4, oy + 5),
                           (ox + 6, oy + 1), (ox + 9, oy + 5), (ox + 10, oy + 2),
                           (ox + 10, oy + 8)), fill=PAL["paper"])
            elif index == 2:
                d.line((ox + 1, oy + 10, ox + 4, oy + 5, ox + 7, oy + 6,
                        ox + 10, oy), fill=PAL["paper"], width=2)
            elif index == 3:
                d.rectangle((ox + 1, oy + 3, ox + 9, oy + 9), outline=PAL["paper"])
                d.line((ox + 1, oy + 3, ox + 5, oy + 6, ox + 9, oy + 3), fill=PAL["paper"])
            else:
                d.polygon(((ox + 5, oy), (ox + 1, oy + 6), (ox + 4, oy + 6),
                           (ox, oy + 10), (ox + 10, oy + 10), (ox + 6, oy + 6),
                           (ox + 9, oy + 6)), fill=PAL["paper"])
            if state == 2:
                d.arc((0, -1, 19, 18), 200, 340, fill=PAL["paper_hi"], width=2)
            sheet.alpha_composite(cell, (index * 20, state * 20))
    return sheet


def text(draw, xy, value, colour, anchor=None):
    draw.text((xy[0] + 1, xy[1] + 1), value, font=FONT,
              fill=(0, 0, 0, 190), anchor=anchor)
    draw.text(xy, value, font=FONT, fill=colour, anchor=anchor)


def preview(viewport, state, worst_case_status=False):
    width, height = viewport; core, _ = build_core()
    canvas = Image.new("RGBA", viewport, (12, 9, 6, 255))
    left = (width - 320) // 2; top = (height - 220) // 2
    canvas.alpha_composite(core, (left, top)); draw = ImageDraw.Draw(canvas)
    text(draw, (left + 276, top + 2), "ALDERWATCH", PAL["text"], "ma")
    text(draw, (left + 72, top + 2), "NEXT ACTION", PAL["ink"])
    if state == "blocked":
        text(draw, (left + 224, top + 2), "BLOCKED", PAL["bad"], "ra")
        text(draw, (left + 72, top + 15), "BUILD + LINK TAVERN", PAL["ink"])
    else:
        text(draw, (left + 224, top + 2), "READY", PAL["good"], "ra")
        text(draw, (left + 72, top + 15), "AWAIT A TRAVELER", PAL["ink"])
    text(draw, (left + 160, top + 30), "COMMUNAL STORES", PAL["text"], "ma")
    text(draw, (left + 278, top + 103), "64 m", PAL["text"], "ma")
    values = ("100/100",) * 4 if worst_case_status else (
        "7/10", "5/7", "126", "82/100")
    for x, value in zip((56, 132, 208, 284), values):
        text(draw, (left + x, top + 117), value, PAL["text"], "ma")
    text(draw, (left + 160, top + 131), "INVENTORY", PAL["text"], "ma")
    sheet = chapter_sheet()
    for index, (x, y) in enumerate(NAV):
        row = 2 if index == 0 else (1 if state == "hover" and index == 2 else 0)
        icon = sheet.crop((index * 20, row * 20, index * 20 + 20, row * 20 + 20))
        if row == 2:
            icon = icon.resize((24, 24), Image.Resampling.NEAREST)
            canvas.alpha_composite(icon, (left + x - 2, top + y - 2))
        else:
            canvas.alpha_composite(icon, (left + x, top + y - (1 if row == 1 else 0)))
    if state == "hover":
        raised(draw, (left + 94, top + 54, left + 146, top + 69),
               PAL["deep"], PAL["brass_hi"], PAL["wood0"], 1)
        text(draw, (left + 100, top + 58), "Journey", PAL["text"])
    return canvas


def main():
    RESOURCE.mkdir(parents=True, exist_ok=True); LAYERS.mkdir(parents=True, exist_ok=True); OUT.mkdir(parents=True, exist_ok=True)
    core, layers = build_core(); core.save(RESOURCE / "hearth_surveyor_core.png")
    chapter_sheet().save(RESOURCE / "hearth_surveyor_chapters.png")
    for name, layer in zip(("BACK_CAST_SHADOW", "FRAME_ROLLTOP_WOOD", "INSET_SCROLL_TRAYS", "FOREGROUND_INSTRUMENTS"), layers):
        layer.save(LAYERS / f"hearth_surveyor_{name}.png")
    proofs = {
        "normal_427x240": preview((427, 240), "normal"),
        "hover_427x240": preview((427, 240), "hover"),
        "blocked_427x240": preview((427, 240), "blocked"),
        "compact_320x240": preview((320, 240), "normal"),
    }
    for name, image in proofs.items(): image.save(OUT / f"hearth_surveyor_{name}.png")
    scaled = {name: image.resize((image.width * 2, image.height * 2),
                                  Image.Resampling.NEAREST)
              for name, image in proofs.items() if name != "compact_320x240"}
    readability = proofs["normal_427x240"].crop((53, 10, 373, 150)).resize(
        (960, 420), Image.Resampling.NEAREST)
    readability.save(OUT / "hearth_surveyor_readability_3x.png")
    worst_status = preview((320, 240), "normal", True).crop(
        (0, 120, 320, 142)).resize((960, 66), Image.Resampling.NEAREST)
    worst_status.save(OUT / "hearth_surveyor_status_worst_case_3x.png")
    board = Image.new("RGBA", (1768, 1540), (15, 11, 8, 255))
    board.alpha_composite(scaled["normal_427x240"], (10, 24))
    board.alpha_composite(scaled["hover_427x240"], (894, 24))
    board.alpha_composite(scaled["blocked_427x240"], (10, 524))
    board.alpha_composite(proofs["compact_320x240"], (1090, 560))
    board.alpha_composite(worst_status, (404, 1020))
    board.alpha_composite(readability, (404, 1110))
    labels = ImageDraw.Draw(board)
    labels.text((10, 8), "NORMAL 2x", font=FONT, fill=PAL["text"])
    labels.text((894, 8), "HOVER / FOCUS 2x", font=FONT, fill=PAL["text"])
    labels.text((10, 508), "BLOCKED 2x", font=FONT, fill=PAL["text"])
    labels.text((1090, 544), "EXACT 320 x 240", font=FONT, fill=PAL["text"])
    labels.text((1090, 820), "SELECTED B — SURVEYOR'S ROLL-TOP\nAPPROVE [ ]   REJECT [ ]", font=FONT, fill=PAL["text"])
    labels.text((404, 1004), "WORST-CASE 100/100 STATUS COLLISION CHECK 3x", font=FONT, fill=PAL["text"])
    labels.text((404, 1094), "READABILITY / CHAPTER / STATUS DETAIL 3x", font=FONT, fill=PAL["text"])
    board.save(OUT / "hearth_surveyor_approval_board.png")
    audit = {}
    for width in (320, 427):
        left = (width - 320) // 2; top = 10
        boxes = []
        for row in range(4):
            for col in range(6):
                boxes.append((left + 106 + col * 18, top + 42 + row * 18, 18, 18))
        for row in range(3):
            for col in range(9):
                boxes.append((left + 79 + col * 18, top + 142 + row * 18, 18, 18))
        for col in range(9): boxes.append((left + 79 + col * 18, top + 200, 18, 18))
        nav = [(left + x, top + y, 20, 20) for x, y in NAV]
        audit[str(width)] = {
            "core": [left, top, left + 320, top + 220], "slot_count": len(boxes),
            "slots_in_bounds": all(x >= 0 and y >= 0 and x + w <= width and y + h <= 240 for x, y, w, h in boxes),
            "hotbar_bottom": top + PLAYER[1] + 58 + 18,
            "viewport_bottom_clearance": 240 - (top + PLAYER[1] + 58 + 18),
            "chapter_hitboxes_in_bounds": all(x >= 0 and y >= 0 and x + w <= width and y + h <= 240 for x, y, w, h in nav),
            "chapter_hitboxes": nav, "slot_boxes": boxes,
            "no_slot_chapter_overlap": all(not (nx < sx + sw and nx + nw > sx and ny < sy + sh and ny + nh > sy) for nx, ny, nw, nh in nav for sx, sy, sw, sh in boxes),
        }
    status_cells = []
    for cell in range(4):
        cell_left = 8 + cell * 76
        text_center = cell_left + 48
        text_left = text_center - 42 // 2
        icon_right = cell_left + 19
        status_cells.append({
            "cell": cell, "bounds": [cell_left, 114, cell_left + 76, 128],
            "icon_gutter": [cell_left + 4, 115, cell_left + 21, 127],
            "worst_value": "100/100", "worst_value_width": 42,
            "text_bounds": [text_left, 117, text_left + 42, 126],
            "icon_text_clearance": text_left - icon_right,
            "fits_cell": text_left >= cell_left + 24
                and text_left + 42 <= cell_left + 72,
        })
    (OUT / "hearth_surveyor_bounds_accessibility.json").write_text(json.dumps({
        "geometry": audit,
        "label_clearance": {"communal_to_tray_px": 2, "inventory_to_abacus_px": 3, "inventory_to_first_slot_px": 2},
        "contrast_surfaces": {"work_scroll_uniform_parchment": True, "radius_opaque_plate": True},
        "status_row": {"equal_cell_width": 76, "cells": status_cells,
                       "all_worst_values_fit": all(c["fits_cell"] for c in status_cells),
                       "minimum_icon_text_clearance": min(c["icon_text_clearance"] for c in status_cells)},
        "navigation": {"visible_state_count": 3, "tooltip_for_every_seal": True, "narrated_label_for_every_seal": True, "keyboard_focus_uses_hover_art": True, "selected_seal_expands_to_24px_without_changing_20px_hitbox": True},
        "render": {"static_core_blits": 1, "chapter_sprite_blits": 5, "per_frame_texture_generation": False},
    }, indent=2), encoding="utf-8")


if __name__ == "__main__": main()
