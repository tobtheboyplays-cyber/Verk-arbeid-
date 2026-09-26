#!/usr/bin/env python3
"""Author the runtime Hearth carpenter desk around the immutable menu slots.

The output is deliberately art-only: no item sprites, values, translated text,
or fake controls are baked into the texture. Minecraft remains authoritative
for all 60 slots and all labels. The clean selected concept supplies the wood,
metal and paper material character; explicit pixel planes make the runtime
composition editable and cheap to render.
"""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageEnhance


ROOT = Path(__file__).resolve().parents[3]
SELECTED = ROOT / "tools/ui/art_direction/selected_concepts/hearth_master_carpenter_desk_clean_base.png"
RESOURCE = ROOT / "src/main/resources/assets/hearthstead/textures/gui/identity"
LAYERS = ROOT / "tools/ui/art_direction/runtime_hearth_layers"
PREVIEW = ROOT / "build/ui_art_direction/hearth_runtime_integration"

CORE = (320, 220)
WING = (48, 180)
SLOT = 18
COMMUNAL = (106, 42, 6, 4)
PLAYER = (79, 142, 9, 3)

PAL = {
    "shadow": (5, 3, 2, 210),
    "deep": (24, 16, 11, 255),
    "wood0": (54, 28, 14, 255),
    "wood1": (99, 52, 25, 255),
    "wood2": (158, 96, 42, 255),
    "wood_hi": (201, 132, 58, 255),
    "iron": (43, 39, 34, 255),
    "iron_hi": (99, 91, 76, 255),
    "brass": (178, 126, 39, 255),
    "brass_hi": (237, 195, 82, 255),
    "paper": (219, 191, 137, 255),
    "paper_hi": (244, 221, 174, 255),
    "paper_lo": (124, 88, 46, 255),
    "slot": (20, 17, 14, 255),
}


def blank(size):
    return Image.new("RGBA", size, (0, 0, 0, 0))


def cleaned_crop(box, size, colours=96):
    with Image.open(SELECTED) as src:
        crop = src.convert("RGB").crop(box).resize(size, Image.Resampling.BOX)
    crop = ImageEnhance.Contrast(crop).enhance(1.08)
    return crop.quantize(colors=colours, method=Image.Quantize.MEDIANCUT,
                         dither=Image.Dither.NONE).convert("RGBA")


def masked_material(target, box, source_box, mask_points=None):
    x0, y0, x1, y1 = box
    material = cleaned_crop(source_box, (x1 - x0, y1 - y0))
    if mask_points is None:
        target.alpha_composite(material, (x0, y0))
        return
    mask = Image.new("L", material.size, 0)
    ImageDraw.Draw(mask).polygon(mask_points, fill=255)
    material.putalpha(mask)
    target.alpha_composite(material, (x0, y0))


def raised(draw, box, face, hi, lo, depth=3):
    x0, y0, x1, y1 = box
    draw.rectangle((x0 + depth, y0 + depth, x1 + depth, y1 + depth),
                   fill=PAL["shadow"])
    draw.rectangle(box, fill=face)
    draw.line((x0, y0, x1, y0), fill=hi)
    draw.line((x0, y0, x0, y1), fill=hi)
    draw.line((x0, y1, x1, y1), fill=lo, width=2)
    draw.line((x1, y0, x1, y1), fill=lo, width=2)


def recessed(draw, box):
    x0, y0, x1, y1 = box
    draw.rectangle(box, fill=PAL["deep"])
    draw.line((x0, y0, x1, y0), fill=(4, 3, 2, 255), width=3)
    draw.line((x1, y0, x1, y1), fill=(4, 3, 2, 255), width=3)
    draw.line((x0 + 1, y1, x1 - 2, y1), fill=PAL["wood2"])
    draw.line((x0, y0 + 2, x0, y1), fill=PAL["wood1"])


def fastener(draw, x, y):
    draw.rectangle((x + 1, y + 2, x + 5, y + 6), fill=PAL["shadow"])
    draw.rectangle((x, y, x + 4, y + 4), fill=PAL["iron"])
    draw.point((x + 1, y + 1), fill=PAL["iron_hi"])
    draw.point((x + 3, y + 3), fill=(15, 13, 11, 255))


def build_core():
    back, wood, inset, hardware = (blank(CORE) for _ in range(4))
    bd, wd, ind, hd = map(ImageDraw.Draw, (back, wood, inset, hardware))

    # Broad cast shadow, then a jointed fold-out desk silhouette. The notched
    # lower corners and projecting header keep it from reading as a window.
    bd.polygon(((5, 7), (315, 7), (319, 13), (319, 211), (311, 219),
                (69, 219), (62, 214), (8, 214), (1, 207), (1, 13)),
               fill=PAL["shadow"])
    masked_material(wood, (0, 0, 320, 220), (45, 20, 1620, 925),
                    ((5, 1), (315, 1), (319, 7), (319, 207), (311, 215),
                     (69, 215), (62, 210), (8, 210), (1, 203), (1, 7)))
    wd.line((6, 2, 314, 2), fill=PAL["wood_hi"])
    wd.line((2, 8, 2, 201), fill=PAL["wood2"])
    wd.line((8, 210, 61, 210), fill=PAL["wood0"], width=3)

    # Full-width carpenter beam and the dark hero nameplate. Runtime text is
    # centred over the quiet plate; no words are baked into this asset.
    raised(wd, (5, 5, 315, 28), PAL["wood0"], PAL["wood_hi"], PAL["deep"], 3)
    masked_material(wood, (8, 7, 312, 26), (110, 18, 1560, 190))
    raised(ind, (72, 7, 248, 25), PAL["iron"], PAL["iron_hi"], PAL["deep"], 2)
    ind.rectangle((83, 11, 237, 22), fill=PAL["deep"])

    # Four physical tally plates flank the immutable communal drawer.
    for x, y in ((8, 37), (8, 66), (224, 37), (224, 66)):
        raised(ind, (x, y, x + 88, y + 24), PAL["deep"], PAL["wood2"],
               PAL["wood0"], 2)
        ind.line((x + 7, y + 19, x + 81, y + 19), fill=PAL["brass"], width=2)
        ind.line((x + 7, y + 21, x + 81, y + 21), fill=(82, 54, 18, 255))

    # Central open drawer: exact frame bounds around COMMUNAL_X/Y. The
    # truthful HsUi slot sprites and live items are rendered above it.
    raised(wd, (100, 32, 220, 117), PAL["wood0"], PAL["wood_hi"],
           PAL["deep"], 3)
    recessed(ind, (103, 36, 217, 116))
    for row in range(COMMUNAL[3]):
        for col in range(COMMUNAL[2]):
            x = COMMUNAL[0] + col * SLOT
            y = COMMUNAL[1] + row * SLOT
            ind.rectangle((x, y, x + 17, y + 17), fill=PAL["slot"])

    # Fold hinge between the communal well and the player drawer. Wide mode's
    # real work order lives on the left clipboard; compact mode adds its own
    # static parchment overlay here without changing any slot geometry.
    raised(ind, (8, 115, 312, 131), PAL["wood0"], PAL["wood2"],
           PAL["deep"], 2)
    hd.rectangle((151, 114, 169, 120), fill=PAL["brass"])
    hd.line((151, 114, 169, 114), fill=PAL["brass_hi"])

    # Deep player drawer, aligned exactly around all 27 inventory slots and
    # nine hotbar slots. The full bottom row ends at y=218 inside the art.
    raised(wd, (72, 131, 248, 219), PAL["wood0"], PAL["wood_hi"],
           PAL["deep"], 3)
    recessed(ind, (76, 137, 245, 218))
    for row in range(PLAYER[3]):
        for col in range(PLAYER[2]):
            x = PLAYER[0] + col * SLOT
            y = PLAYER[1] + row * SLOT
            ind.rectangle((x, y, x + 17, y + 17), fill=PAL["slot"])
    for col in range(PLAYER[2]):
        x = PLAYER[0] + col * SLOT
        y = PLAYER[1] + 58
        ind.rectangle((x, y, x + 17, y + 17), fill=PAL["slot"])
    # Drawer lip and handle sit visibly above the inset plane.
    hd.rectangle((104, 132, 216, 139), fill=PAL["wood0"])
    hd.line((104, 132, 216, 132), fill=PAL["wood2"])
    hd.rectangle((145, 133, 175, 137), fill=PAL["brass"])
    hd.line((146, 133, 174, 133), fill=PAL["brass_hi"])

    for x, y in ((7, 8), (308, 8), (5, 202), (310, 202),
                 (101, 34), (214, 34)):
        fastener(hd, x, y)

    result = blank(CORE)
    for layer in (back, wood, inset, hardware):
        result.alpha_composite(layer)
    return result, (back, wood, inset, hardware)


def build_wings():
    left = blank(WING)
    right = blank(WING)
    # The wings are optional non-interactive furniture. They enrich 427-wide
    # viewports but can vanish without changing one slot or hitbox.
    lmat = cleaned_crop((70, 470, 540, 930), (46, 178))
    rmat = cleaned_crop((1115, 450, 1630, 930), (46, 178))
    lmask = Image.new("L", (46, 178), 0)
    ImageDraw.Draw(lmask).polygon(((7, 0), (45, 0), (45, 170), (38, 177),
                                  (1, 177), (1, 12)), fill=255)
    rmask = Image.new("L", (46, 178), 0)
    ImageDraw.Draw(rmask).polygon(((0, 0), (38, 0), (45, 12), (45, 177),
                                  (7, 177), (0, 170)), fill=255)
    lmat.putalpha(lmask); rmat.putalpha(rmask)
    left.alpha_composite(lmat, (2, 0)); right.alpha_composite(rmat, (0, 0))
    return left, right


def build_tab(state):
    image = blank((12, 20)); draw = ImageDraw.Draw(image)
    lift = 0 if state == "selected" else 2
    draw.rectangle((1, lift + 2, 11, 19), fill=PAL["shadow"])
    draw.rectangle((0, lift, 10, 18), fill=PAL["wood0"])
    draw.line((1, lift, 9, lift), fill=PAL["wood_hi"])
    draw.line((0, lift + 1, 0, 17), fill=PAL["wood2"])
    draw.line((1, 18, 9, 18), fill=PAL["deep"], width=2)
    draw.line((10, lift + 1, 10, 17), fill=PAL["deep"], width=2)
    if state == "selected":
        draw.line((3, 16, 7, 16), fill=PAL["brass_hi"], width=2)
    elif state == "hover":
        draw.line((2, lift + 2, 8, lift + 2), fill=PAL["brass"])
    return image


def build_compact_work_order():
    image = blank((304, 16)); draw = ImageDraw.Draw(image)
    raised(draw, (0, 0, 303, 14), PAL["paper"], PAL["paper_hi"],
           PAL["paper_lo"], 1)
    draw.rectangle((143, 0, 161, 4), fill=PAL["brass"])
    draw.line((143, 0, 161, 0), fill=PAL["brass_hi"])
    return image


def build_compact_info():
    image = blank((88, 20)); draw = ImageDraw.Draw(image)
    raised(draw, (0, 0, 87, 18), PAL["deep"], PAL["wood2"],
           PAL["wood0"], 1)
    return image


def draw_preview_text(draw, xy, value, fill=(244, 221, 174, 255), anchor=None):
    draw.text(xy, value, fill=fill, anchor=anchor, stroke_width=1,
              stroke_fill=(15, 10, 7, 230))


def preview(viewport, state="normal"):
    core, _ = build_core(); left, right = build_wings()
    width, height = viewport
    canvas = Image.new("RGBA", viewport, (12, 10, 8, 255))
    lx = (width - 320) // 2; ty = 20
    if width >= 400:
        canvas.alpha_composite(left, (lx - 48, ty + 24))
        canvas.alpha_composite(right, (lx + 320, ty + 24))
    canvas.alpha_composite(core, (lx, ty))
    draw = ImageDraw.Draw(canvas)
    # Runtime-equivalent real labels and representative live values.
    draw_preview_text(draw, (width // 2, ty + 11), "ALDERWATCH HEARTH", anchor="mm")
    draw_preview_text(draw, (lx + 160, ty + 31), "COMMUNAL STORES", anchor="mm")
    for x, y, label, value in (
        (lx + 13, ty + 40, "POPULATION", "7 / 10"),
        (lx + 13, ty + 69, "EMPLOYED", "5 / 7"),
        (lx + 229, ty + 40, "FOOD", "126"),
        (lx + 229, ty + 69, "MORALE", "82 / 100")):
        draw_preview_text(draw, (x, y), label, (196, 164, 102, 255))
        draw_preview_text(draw, (x + 78, y + 10), value, anchor="ra")
    blocked = state == "blocked"
    if width >= 416:
        ink = (44, 28, 17, 255)
        work_lines = (("NEXT", ink), ("ACTION", ink),
                      ("BLOCKED" if blocked else "READY",
                       (208, 84, 57, 255) if blocked else (130, 181, 91, 255)),
                      ("CAUSE", PAL["paper_lo"]),
                      ("No linked" if blocked else "All gates", ink),
                      ("Tavern" if blocked else "clear", ink),
                      ("NEXT", PAL["paper_lo"]),
                      ("Build + link" if blocked else "Await a", ink),
                      ("Tavern" if blocked else "traveler", ink))
        wy = ty + 52
        for line, colour in work_lines:
            draw_preview_text(draw, (lx - 43, wy), line, fill=colour)
            wy += 10
        draw_preview_text(draw, (lx + 324, ty + 160), "RADIUS")
        draw_preview_text(draw, (lx + 324, ty + 172), "64 m")
    else:
        canvas.alpha_composite(build_compact_work_order(), (lx + 8, ty + 115))
        canvas.alpha_composite(build_compact_info(), (lx + 224, ty + 92))
        draw_preview_text(draw, (lx + 160, ty + 121),
                          "Blocked: Build + link a Tavern" if blocked
                          else "Ready: Await the next traveler", anchor="mm",
                          fill=(208, 84, 57, 255) if blocked else (130, 181, 91, 255))
        draw_preview_text(draw, (lx + 229, ty + 96), "RADIUS  64 m")
    draw_preview_text(draw, (lx + 160, ty + 135), "INVENTORY", anchor="mm")
    # Draw crisp truthful slot frames; items remain representative preview only.
    for row in range(4):
        for col in range(6):
            x = lx + COMMUNAL[0] + col * SLOT; y = ty + COMMUNAL[1] + row * SLOT
            draw.rectangle((x, y, x + 17, y + 17), outline=(116, 78, 38, 255))
    for row in range(3):
        for col in range(9):
            x = lx + PLAYER[0] + col * SLOT; y = ty + PLAYER[1] + row * SLOT
            draw.rectangle((x, y, x + 17, y + 17), outline=(116, 78, 38, 255))
    for col in range(9):
        x = lx + PLAYER[0] + col * SLOT; y = ty + PLAYER[1] + 58
        draw.rectangle((x, y, x + 17, y + 17), outline=(116, 78, 38, 255))
    if state == "hover":
        x = lx + COMMUNAL[0]; y = ty + COMMUNAL[1]
        draw.rectangle((x - 1, y - 2, x + 18, y + 17), outline=PAL["brass_hi"], width=2)
        draw.rectangle((x + 19, y - 3, x + 79, y + 11), fill=PAL["deep"], outline=PAL["brass"])
        draw_preview_text(draw, (x + 24, y), "Oak Log")
    # Five real tabs use the same 3-slice texture as runtime.
    widths = (64, 56, 52, 56, 72); labels = ("Settlement", "Mayor", "Journey", "Requests", "Development")
    tx = lx + 2
    for i, (tw, label) in enumerate(zip(widths, labels)):
        tab = build_tab("selected" if i == 0 else "idle").resize((tw, 20), Image.Resampling.NEAREST)
        canvas.alpha_composite(tab, (tx, 0)); draw_preview_text(draw, (tx + tw // 2, 8), label, anchor="mm")
        tx += tw + 4
    return canvas


def main():
    RESOURCE.mkdir(parents=True, exist_ok=True)
    LAYERS.mkdir(parents=True, exist_ok=True)
    PREVIEW.mkdir(parents=True, exist_ok=True)
    core, layers = build_core(); left, right = build_wings()
    core.save(RESOURCE / "hearth_carpenter_core.png")
    left.save(RESOURCE / "hearth_carpenter_wing_left.png")
    right.save(RESOURCE / "hearth_carpenter_wing_right.png")
    for state in ("idle", "selected", "hover"):
        build_tab(state).save(RESOURCE / f"hearth_tab_{state}.png")
    build_compact_work_order().save(RESOURCE / "hearth_compact_work_order.png")
    build_compact_info().save(RESOURCE / "hearth_compact_info.png")
    for name, layer in zip(("BACK_CAST_SHADOW", "FRAME_WOOD", "INSET_PAPER_AND_DRAWERS", "FOREGROUND_HARDWARE"), layers):
        layer.save(LAYERS / f"hearth_runtime_{name}.png")
    for size in ((427, 240), (320, 240)):
        for state in ("normal", "hover", "blocked"):
            preview(size, state).save(PREVIEW / f"hearth_runtime_{state}_{size[0]}x{size[1]}.png")
    # One visual board, deliberately without a self-awarded PASS badge.
    board = Image.new("RGBA", (874, 500), (18, 14, 10, 255))
    board.alpha_composite(preview((427, 240), "normal"), (10, 10))
    board.alpha_composite(preview((427, 240), "blocked"), (447, 10))
    compact = preview((320, 240), "hover")
    board.alpha_composite(compact, (10, 255))
    ImageDraw.Draw(board).text((344, 270),
        "FIXED CORE: 24 COMMUNAL + 36 PLAYER SLOTS\n"
        "WIDE WINGS: DECOR ONLY\nAPPROVE [ ]    REJECT [ ]",
        fill=(236, 211, 162, 255), spacing=8)
    board.save(PREVIEW / "hearth_runtime_approval_board.png")
    # Literal geometry audit shared with review. All values are destination
    # pixels in the logical GUI viewport, not source-image estimates.
    audits = {}
    for width in (320, 427):
        left_pos = (width - CORE[0]) // 2
        wide = width >= CORE[0] + 2 * WING[0]
        slot_boxes = []
        for row in range(COMMUNAL[3]):
            for col in range(COMMUNAL[2]):
                x = left_pos + COMMUNAL[0] + col * SLOT
                y = 20 + COMMUNAL[1] + row * SLOT
                slot_boxes.append((x, y, x + SLOT, y + SLOT))
        for row in range(PLAYER[3]):
            for col in range(PLAYER[2]):
                x = left_pos + PLAYER[0] + col * SLOT
                y = 20 + PLAYER[1] + row * SLOT
                slot_boxes.append((x, y, x + SLOT, y + SLOT))
        for col in range(PLAYER[2]):
            x = left_pos + PLAYER[0] + col * SLOT
            y = 20 + PLAYER[1] + 58
            slot_boxes.append((x, y, x + SLOT, y + SLOT))
        audits[str(width)] = {
            "core": [left_pos, 20, left_pos + 320, 240],
            "tabs": [left_pos + 2, 0, left_pos + 318, 20],
            "wide_wings": wide,
            "wing_bounds": ([left_pos - 48, 44, left_pos, 224],
                            [left_pos + 320, 44, left_pos + 368, 224])
                            if wide else None,
            "slot_count": len(slot_boxes),
            "all_slots_in_viewport": all(
                x0 >= 0 and y0 >= 0 and x1 <= width and y1 <= 240
                for x0, y0, x1, y1 in slot_boxes),
            "slot_bounds": slot_boxes,
        }
    (PREVIEW / "hearth_runtime_bounds_audit.json").write_text(
        json.dumps(audits, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
