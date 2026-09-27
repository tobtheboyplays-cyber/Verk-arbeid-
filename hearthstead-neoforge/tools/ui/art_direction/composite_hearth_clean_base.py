#!/usr/bin/env python3
"""Composite real Hearth data onto the selected clean carpenter-desk source.

This produces visual proof only. It deliberately writes no runtime resource or
Java file until the composite is approved.
"""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont, ImageOps, ImageEnhance, ImageStat

ROOT = Path(__file__).resolve().parents[3]
SELECTED = ROOT / "tools/ui/art_direction/selected_concepts/hearth_master_carpenter_desk_clean_base.png"
REFERENCE = ROOT / "tools/ui/art_direction/selected_concepts/hearth_master_carpenter_desk_selected.png"
OUT = ROOT / "build/ui_art_direction/hearth_clean_composite"
LAYERS = ROOT / "tools/ui/art_direction/hearth_clean_composite_layers"
SOURCES = ROOT / "tools/ui/art_direction/sources"
FONT = ImageFont.load_default()
SMALL_FONT = ImageFont.load_default(size=8)

PAL = {
    "shadow": (5, 3, 2, 220), "deep": (24, 17, 12, 255),
    "wood0": (55, 29, 15, 255), "wood1": (100, 53, 25, 255),
    "wood2": (167, 103, 45, 255), "brass0": (103, 68, 19, 255),
    "brass1": (181, 129, 39, 255), "brass2": (238, 198, 87, 255),
    "paper": (224, 198, 148, 255), "paper_hi": (245, 225, 181, 255),
    "ink": (44, 28, 17, 255), "text": (245, 224, 177, 255),
    "muted": (197, 164, 102, 255), "slot": (25, 21, 18, 255),
    "good": (126, 178, 85, 255), "bad": (200, 75, 54, 255),
}

LAYER_NAMES = (
    ("CLEAN_DESK_BASE", "clean_desk_base"),
    ("RESPONSIVE_DRAWER_ADAPTATION", "responsive_drawer_adaptation"),
    ("SLOTS_AND_ITEMS", "slots_and_items"),
    ("TEXT_AND_VALUES", "text_and_values"),
    ("FOREGROUND_HARDWARE", "foreground_hardware"),
    ("INTERACTION_STATE", "interaction_state"),
)


def blank(size): return Image.new("RGBA", size, (0, 0, 0, 0))


def crisp_base(size):
    """Deliberate cleanup: BOX downsample, restrained contrast, no dither."""
    with Image.open(SELECTED) as source:
        rgb = source.convert("RGB").resize(size, Image.Resampling.BOX)
    rgb = ImageEnhance.Contrast(rgb).enhance(1.08)
    # Adaptive quantisation removes subpixel mush while keeping authored depth.
    rgb = rgb.quantize(colors=160, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).convert("RGB")
    return rgb.convert("RGBA")


def text(draw, x, y, value, colour, align="left", shadow=True):
    box = draw.textbbox((0, 0), value, font=FONT); width = box[2] - box[0]
    if align == "center": x -= width // 2
    elif align == "right": x -= width
    if shadow: draw.text((x + 1, y + 1), value, font=FONT, fill=(0, 0, 0, 185))
    draw.text((x, y), value, font=FONT, fill=colour)


def small_text(draw, x, y, value, colour, align="left"):
    box = draw.textbbox((0, 0), value, font=SMALL_FONT); width = box[2] - box[0]
    if align == "center": x -= width // 2
    elif align == "right": x -= width
    draw.text((x, y), value, font=SMALL_FONT, fill=colour)


def recess(draw, x, y):
    draw.rectangle((x, y, x + 16, y + 16), fill=PAL["slot"])
    draw.line((x, y, x + 16, y), fill=(3, 2, 2, 255), width=2)
    draw.line((x + 15, y, x + 15, y + 16), fill=(4, 3, 2, 255), width=2)
    draw.line((x + 1, y + 16, x + 15, y + 16), fill=PAL["wood1"])
    draw.line((x, y + 2, x, y + 16), fill=PAL["wood2"])


def raised(draw, box, face, hi, lo, depth=2):
    x0, y0, x1, y1 = box
    draw.rectangle((x0 + depth, y0 + depth, x1 + depth, y1 + depth), fill=PAL["shadow"])
    draw.rectangle(box, fill=face)
    draw.line((x0, y0, x1, y0), fill=hi); draw.line((x0, y0, x0, y1), fill=hi)
    draw.line((x0, y1, x1, y1), fill=lo); draw.line((x1, y0, x1, y1), fill=lo)


def item(draw, kind, x, y):
    if kind == "log":
        draw.rectangle((x + 2, y + 3, x + 11, y + 10), fill=(145, 84, 37, 255))
        draw.ellipse((x + 8, y + 4, x + 12, y + 10), fill=(203, 147, 76, 255), outline=(65, 35, 18, 255))
    elif kind == "seed":
        for dx, dy in ((3, 10), (5, 6), (8, 9), (11, 4), (13, 8)):
            draw.line((x + dx, y + dy, x + dx + 2, y + dy - 3), fill=(129, 164, 70, 255))
    elif kind == "iron":
        draw.polygon([(x + 2, y + 7), (x + 5, y + 3), (x + 12, y + 3),
                      (x + 14, y + 8), (x + 11, y + 11), (x + 4, y + 11)], fill=(151, 150, 141, 255))
    elif kind == "axe":
        draw.line((x + 3, y + 13, x + 11, y + 4), fill=(170, 107, 50, 255), width=2)
        draw.polygon([(x + 8, y + 2), (x + 14, y + 2), (x + 14, y + 7),
                      (x + 10, y + 8), (x + 8, y + 5)], fill=(151, 150, 141, 255))
    elif kind == "bread":
        draw.ellipse((x + 2, y + 4, x + 13, y + 11), fill=(205, 137, 50, 255), outline=(83, 43, 19, 255))


def metric_plate(draw, labels, x, y, width, name, value, colour, beads):
    """Quiet two-line value plate physically mounted to its brass tally rail."""
    raised(draw, (x, y, x + width, y + 21), PAL["deep"], PAL["wood2"], PAL["wood0"], 2)
    text(labels, x + 5, y + 2, name, PAL["muted"])
    text(labels, x + width - 5, y + 2, value, colour, "right")
    draw.line((x + 7, y + 16, x + width - 7, y + 16), fill=PAL["brass2"], width=2)
    draw.line((x + 7, y + 18, x + width - 7, y + 18), fill=PAL["brass0"])
    for i in range(beads):
        bx = x + 10 + i * 7
        draw.rectangle((bx, y + 13, bx + 4, y + 20), fill=PAL["brass1"])
        draw.line((bx, y + 13, bx + 4, y + 13), fill=PAL["brass2"])


def compact_metric(draw, labels, x, y, name, value, colour):
    raised(draw, (x, y, x + 91, y + 20), PAL["deep"], PAL["wood2"], PAL["wood0"], 2)
    text(labels, x + 5, y + 2, name, PAL["muted"])
    text(labels, x + 86, y + 11, value, colour, "right")
    draw.line((x + 5, y + 18, x + 86, y + 18), fill=PAL["brass1"])


def vignette(layer, x, y, w, h):
    """Use the selected concept's authored building as the identity anchor."""
    with Image.open(REFERENCE) as ref:
        crop = ref.convert("RGBA").crop((118, 72, 510, 350)).resize((w, h), Image.Resampling.BOX)
    # Clean outer margins so it reads as a miniature set into the base's field.
    layer.alpha_composite(crop, (x, y))


def wood_patch(layer, box, source_crop=(520, 760, 1135, 930)):
    """Reuse the clean source's authored wood, never a synthetic flat fill."""
    x0, y0, x1, y1 = box
    with Image.open(SELECTED) as source:
        patch = source.convert("RGBA").crop(source_crop).resize((x1 - x0 + 1, y1 - y0 + 1), Image.Resampling.BOX)
    patch = ImageEnhance.Contrast(patch).enhance(1.06)
    layer.alpha_composite(patch, (x0, y0))


def paper_patch(layer, box):
    """Expand the source clipboard with its own paper/edge material."""
    x0, y0, x1, y1 = box
    with Image.open(SELECTED) as source:
        patch = source.convert("RGBA").crop((145, 525, 500, 890)).resize((x1 - x0 + 1, y1 - y0 + 1), Image.Resampling.BOX)
    layer.alpha_composite(patch, (x0, y0))


def desk_base_427():
    desk = crisp_base((405, 228))
    canvas = Image.new("RGBA", (427, 240), (10, 8, 6, 255))
    canvas.alpha_composite(desk, (11, 6))
    return canvas


def desk_base_320():
    # Separate responsive assembly from three source regions; never a shrunk
    # 427 screenshot. Decoration is minimized so 18px slots remain full size.
    canvas = Image.new("RGBA", (320, 240), (10, 8, 6, 255))
    with Image.open(SELECTED) as src:
        source = src.convert("RGB")
        beam = source.crop((105, 0, 1580, 235)).resize((320, 42), Image.Resampling.BOX)
        body = source.crop((50, 275, 1625, 610)).resize((320, 90), Image.Resampling.BOX)
        lower = source.crop((20, 540, 1655, 935)).resize((320, 108), Image.Resampling.BOX)
    canvas.alpha_composite(beam.convert("RGBA"), (0, 0))
    canvas.alpha_composite(body.convert("RGBA"), (0, 42))
    canvas.alpha_composite(lower.convert("RGBA"), (0, 132))
    return canvas


def render_427(state="normal"):
    size = (427, 240); planes = [blank(size) for _ in LAYER_NAMES]
    planes[0].alpha_composite(desk_base_427())
    adapt, slots, labels, hardware, interaction = map(ImageDraw.Draw, planes[1:])
    ox, oy = 11, 6

    # Authored hero building and title plaque remain the primary identity.
    vignette(planes[4], ox + 28, oy + 32, 104, 46)
    text(labels, ox + 202, oy + 24, "ALDERWATCH HEARTH", PAL["text"], "center")

    # Five real controls on the clean base's existing bookmark plates.
    tab_centres = (87, 164, 222, 282, 350)
    for cx, label in zip(tab_centres, ("Settlement", "Mayor", "Journey", "Requests", "Development")):
        text(labels, ox + cx, oy + 8, label, PAL["ink"] if label == "Settlement" else PAL["text"], "center", label != "Settlement")

    # The clean source already owns four brass mechanisms; only real values are
    # placed on their quiet upper faces.
    metric_plate(adapt, labels, ox + 17, oy + 78, 110, "POPULATION", "7 / 10", PAL["good"], 7)
    metric_plate(adapt, labels, ox + 17, oy + 101, 110, "EMPLOYED", "5 / 7", PAL["text"], 5)
    metric_plate(adapt, labels, ox + 277, oy + 78, 110, "FOOD", "126", PAL["text"], 7)
    metric_plate(adapt, labels, ox + 277, oy + 101, 110, "MORALE", "82 / 100", PAL["good"], 7)

    # Adapt the hero shelf into the actual 6x4 communal drawer.
    raised(adapt, (ox + 143, oy + 43, ox + 261, oy + 130), PAL["wood0"], PAL["wood2"], PAL["deep"], 3)
    wood_patch(planes[1], (ox + 145, oy + 45, ox + 259, oy + 128), (535, 500, 1110, 760))
    adapt.rectangle((ox + 148, oy + 49, ox + 256, oy + 125), fill=PAL["deep"])
    for row in range(4):
        for col in range(6): recess(slots, ox + 149 + col * 18, oy + 58 + row * 18)
    item(slots, "log", ox + 150, oy + 59); item(slots, "seed", ox + 168, oy + 59); item(slots, "iron", ox + 222, oy + 59)
    raised(hardware, (ox + 151, oy + 43, ox + 253, oy + 54), PAL["brass1"], PAL["brass2"], PAL["brass0"], 2)
    text(hardware, ox + 202, oy + 45, "COMMUNAL STORES", PAL["ink"], "center", False)

    # Clipboard: exact current recruitment status and next action, no fantasy task.
    paper_patch(planes[1], (ox + 30, oy + 125, ox + 119, oy + 226))
    raised(hardware, (ox + 58, oy + 122, ox + 91, oy + 131), PAL["brass1"], PAL["brass2"], PAL["brass0"], 2)
    small_text(labels, ox + 72, oy + 134, "NEXT ACTION", PAL["ink"], "center")
    if state == "blocked":
        lines = (("STATE   BLOCKED", PAL["bad"]), ("CAUSE   No Tavern", PAL["ink"]),
                 ("OWNER   Hearth", PAL["ink"]), ("NEXT", PAL["wood1"]),
                 ("Build + link a Tavern", PAL["ink"]))
    else:
        lines = (("STATE   READY", PAL["good"]), ("NEXT", PAL["wood1"]),
                 ("Await traveler", PAL["ink"]))
    ly = oy + 145
    for value, colour in lines:
        small_text(labels, ox + 37, ly, value, colour); ly += 12

    # Actual player inventory/hotbar: 9x3 + 9. The base drawer is deepened,
    # never replaced by a flat full-screen panel.
    raised(adapt, (ox + 109, oy + 133, ox + 296, oy + 226), PAL["wood0"], PAL["wood2"], PAL["deep"], 3)
    wood_patch(planes[1], (ox + 111, oy + 135, ox + 294, oy + 224))
    adapt.rectangle((ox + 116, oy + 141, ox + 289, oy + 222), fill=PAL["deep"])
    raised(hardware, (ox + 165, oy + 134, ox + 239, oy + 144), PAL["wood0"], PAL["wood2"], PAL["deep"], 2)
    text(hardware, ox + 202, oy + 136, "INVENTORY", PAL["text"], "center")
    for row in range(3):
        for col in range(9): recess(slots, ox + 122 + col * 18, oy + 146 + row * 18)
    for col in range(9): recess(slots, ox + 122 + col * 18, oy + 202)
    item(slots, "axe", ox + 123, oy + 203); item(slots, "bread", ox + 141, oy + 203)
    raised(hardware, (ox + 191, oy + 220, ox + 214, oy + 228), PAL["brass0"], PAL["brass2"], PAL["wood0"], 2)

    # The clean source's right map is preserved; overlay only real radius/status.
    text(labels, ox + 315, oy + 205, "RADIUS", PAL["muted"])
    text(labels, ox + 381, oy + 205, "64 m", PAL["text"], "right")
    text(labels, ox + 348, oy + 218, "STABLE", PAL["good"], "center")

    if state == "hover":
        sx, sy = ox + 149, oy + 58
        interaction.rectangle((sx + 2, sy + 2, sx + 18, sy + 18), fill=PAL["shadow"])
        raised(interaction, (sx, sy - 1, sx + 16, sy + 15), PAL["slot"], PAL["brass2"], PAL["wood0"], 2)
        item(interaction, "log", sx + 1, sy)
        raised(interaction, (ox + 345, oy + 56, ox + 400, oy + 72), PAL["wood0"], PAL["wood2"], PAL["deep"], 2)
        text(interaction, ox + 351, oy + 60, "Oak Log", PAL["text"])

    result = blank(size)
    for plane in planes: result.alpha_composite(plane)
    return result, planes


def render_320():
    size = (320, 240); planes = [blank(size) for _ in LAYER_NAMES]
    planes[0].alpha_composite(desk_base_320())
    adapt, slots, labels, hardware, interaction = map(ImageDraw.Draw, planes[1:])
    # Compact controls use the source beam, but every content region below is
    # independently composed rather than shrinking the 427 arrangement.
    for cx, label in zip((54, 115, 164, 219, 280), ("Settlement", "Mayor", "Journey", "Requests", "Development")):
        text(labels, cx, 4, label, PAL["ink"] if label == "Settlement" else PAL["text"], "center", label != "Settlement")
    raised(adapt, (4, 21, 315, 38), PAL["deep"], PAL["wood2"], PAL["wood0"], 2)
    text(labels, 10, 25, "ALDERWATCH HEARTH", PAL["text"])
    text(labels, 306, 25, "RADIUS 64 m", PAL["muted"], "right")

    # Compact 2x2 metric plates: names and exact values occupy different lines.
    compact_metric(adapt, labels, 4, 43, "POPULATION", "7 / 10", PAL["good"])
    compact_metric(adapt, labels, 4, 68, "EMPLOYED", "5 / 7", PAL["text"])
    compact_metric(adapt, labels, 224, 43, "FOOD", "126", PAL["text"])
    compact_metric(adapt, labels, 224, 68, "MORALE", "82 / 100", PAL["good"])

    # 6x4 communal drawer stays full-size between the metric stacks.
    raised(adapt, (100, 42, 219, 130), PAL["wood0"], PAL["wood2"], PAL["deep"], 2)
    wood_patch(planes[1], (102, 44, 217, 128), (535, 500, 1110, 760))
    for row in range(4):
        for col in range(6): recess(slots, 106 + col * 18, 51 + row * 18)
    item(slots, "log", 107, 52); item(slots, "seed", 125, 52); item(slots, "iron", 179, 52)
    raised(hardware, (119, 39, 200, 49), PAL["brass1"], PAL["brass2"], PAL["brass0"], 2)
    small_text(hardware, 160, 41, "COMMUNAL STORES", PAL["ink"], "center")

    # Full-width work-order slip replaces the map and cannot collide with metrics.
    raised(adapt, (4, 133, 315, 148), PAL["paper"], PAL["paper_hi"], PAL["wood1"], 2)
    small_text(labels, 11, 137, "RECRUITMENT READY", PAL["good"])
    small_text(labels, 129, 137, "NEXT: AWAIT TRAVELER", PAL["ink"])
    small_text(labels, 307, 137, "STABLE", PAL["good"], "right")

    # Full-size 9x3 + hotbar drawer, with its label centred on a physical lip.
    # The source map is intentionally covered in compact mode; Radius already
    # lives in the header and the map would collide with the full-size drawer.
    wood_patch(planes[1], (249, 150, 319, 239))
    raised(adapt, (72, 150, 248, 237), PAL["wood0"], PAL["wood2"], PAL["deep"], 2)
    wood_patch(planes[1], (74, 152, 246, 235))
    raised(hardware, (122, 148, 198, 158), PAL["wood0"], PAL["wood2"], PAL["deep"], 2)
    text(hardware, 160, 150, "INVENTORY", PAL["text"], "center")
    for row in range(3):
        for col in range(9): recess(slots, 79 + col * 18, 160 + row * 18)
    for col in range(9): recess(slots, 79 + col * 18, 216)
    item(slots, "axe", 80, 217); item(slots, "bread", 98, 217)
    result = blank(size)
    for plane in planes: result.alpha_composite(plane)
    return result, planes


def save_layers(stem, planes):
    folder = LAYERS / stem; folder.mkdir(parents=True, exist_ok=True)
    for (_, filename), plane in zip(LAYER_NAMES, planes): plane.save(folder / f"{filename}.png")


def bounds_audit():
    """Literal half-open content bounds; any intersection is a hard failure."""
    wide = {
        "title": (169, 30, 281, 41), "communal_label": (162, 49, 264, 60),
        "population_plate": (28, 84, 139, 106), "employed_plate": (28, 107, 139, 129),
        "food_plate": (288, 84, 399, 106), "morale_plate": (288, 107, 399, 129),
        "communal_slots": (160, 64, 269, 135), "work_order_text": (48, 140, 120, 220),
        "player_slots": (133, 152, 296, 225), "radius_text": (326, 211, 392, 225),
    }
    compact = {
        "header_text": (10, 25, 307, 36), "population_plate": (4, 43, 96, 64),
        "employed_plate": (4, 68, 96, 89), "food_plate": (224, 43, 316, 64),
        "morale_plate": (224, 68, 316, 89), "communal_slots": (106, 51, 215, 122),
        "work_order_text": (11, 137, 308, 146), "player_slots": (79, 160, 242, 233),
    }
    def intersections(mapping):
        names = list(mapping); hits = []
        for i, left in enumerate(names):
            ax0, ay0, ax1, ay1 = mapping[left]
            for right in names[i + 1:]:
                bx0, by0, bx1, by1 = mapping[right]
                if ax0 < bx1 and bx0 < ax1 and ay0 < by1 and by0 < ay1:
                    hits.append([left, right])
        return hits
    report = {"coordinate_convention": "half-open [x0,y0,x1,y1]",
              "wide": wide, "compact": compact,
              "wide_intersections": intersections(wide),
              "compact_intersections": intersections(compact)}
    if report["wide_intersections"] or report["compact_intersections"]:
        raise RuntimeError(f"literal bounds overlap: {report}")
    (OUT / "pixel_bounds_audit.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return report


def bounds_visual(wide_image, compact_image, report):
    proof = Image.new("RGB", (760, 300), (13, 10, 8)); proof.paste(wide_image.convert("RGB"), (10, 28)); proof.paste(compact_image.convert("RGB"), (440, 28))
    draw = ImageDraw.Draw(proof); text(draw, 10, 8, "LITERAL HALF-OPEN CONTENT BOUNDS - ZERO INTERSECTIONS", PAL["text"])
    colours = ((238, 198, 87), (105, 180, 224), (207, 112, 92), (126, 178, 85))
    for index, (name, box) in enumerate(report["wide"].items()):
        x0,y0,x1,y1=box; draw.rectangle((10+x0,28+y0,10+x1-1,28+y1-1), outline=colours[index%4])
    for index, (name, box) in enumerate(report["compact"].items()):
        x0,y0,x1,y1=box; draw.rectangle((440+x0,28+y0,440+x1-1,28+y1-1), outline=colours[index%4])
    text(draw, 10, 275, "427 content regions", PAL["muted"]); text(draw, 440, 275, "320 responsive content regions", PAL["muted"])
    proof.save(OUT / "hearth_clean_bounds_audit.png")


def main():
    OUT.mkdir(parents=True, exist_ok=True); LAYERS.mkdir(parents=True, exist_ok=True)
    rendered = {}
    for key, state in (("a_normal", "normal"), ("b_hover", "hover"), ("c_blocked", "blocked")):
        image, planes = render_427(state); rendered[key] = image
        image.save(OUT / f"hearth_clean_{key}_427x240.png"); save_layers(f"hearth_clean_{key}_427x240", planes)
    compact, compact_planes = render_320(); compact.save(OUT / "hearth_clean_compact_320x240.png")
    save_layers("hearth_clean_compact_320x240", compact_planes)
    bounds = bounds_audit()
    bounds_visual(rendered["a_normal"], compact, bounds)

    value = Image.new("RGB", (427 * 3, 240), "black")
    for i, key in enumerate(("a_normal", "b_hover", "c_blocked")):
        value.paste(ImageOps.grayscale(rendered[key].convert("RGB")).convert("RGB"), (i * 427, 0))
    value.save(OUT / "hearth_clean_value_check.png")
    details = ((rendered["a_normal"], (126, 41, 285, 141)),
               (rendered["b_hover"], (143, 49, 242, 127)),
               (rendered["c_blocked"], (17, 126, 120, 230)))
    widths = [(b[2] - b[0]) * 2 for _, b in details]
    detail = Image.new("RGB", (sum(widths), 208), (12, 9, 7)); dx = 0
    for image, box in details:
        crop = image.crop(box).resize(((box[2] - box[0]) * 2, (box[3] - box[1]) * 2), Image.Resampling.NEAREST)
        detail.paste(crop.convert("RGB"), (dx, 0)); dx += crop.width
    detail.save(OUT / "hearth_clean_depth_200pct.png")

    board = Image.new("RGB", (900, 800), (14, 10, 8)); d = ImageDraw.Draw(board)
    text(d, 20, 14, "SELECTED CLEAN BASE > RUNTIME COMPOSITE PROOF", PAL["text"])
    text(d, 20, 29, "Source art remains dominant; actual Hearth data and 60 real slots overlaid", PAL["muted"])
    for i, (key, title) in enumerate((("a_normal", "A NORMAL"), ("b_hover", "B HOVER"), ("c_blocked", "C BLOCKED"))):
        yy = 50 + i * 247; text(d, 20, yy, title, PAL["brass2"]); board.paste(rendered[key].convert("RGB"), (20, yy + 13))
    with Image.open(SELECTED) as source:
        source_thumb = source.convert("RGB").resize((405, 228), Image.Resampling.BOX)
    text(d, 465, 50, "CLEAN SOURCE", PAL["brass2"]); board.paste(source_thumb, (465, 63))
    text(d, 465, 310, "320 RESPONSIVE", PAL["brass2"]); board.paste(compact.convert("RGB"), (465, 326))
    text(d, 465, 590, "ROOT VISUAL GATE", PAL["brass2"])
    for i, line in enumerate(("[ ] source silhouette preserved", "[ ] 60 slots fit without clipping",
                              "[ ] no tiny/overlapping text", "[ ] approve runtime asset prep")):
        text(d, 465, 608 + i * 17, line, PAL["text"])
    board.save(OUT / "hearth_clean_composite_board.png")

    manifest = {
        "status": "COMPOSITE PROOF - ROOT REVIEW REQUIRED - NO JAVA",
        "source": str(SELECTED.relative_to(ROOT)),
        "wide": {"desk": [11, 6, 405, 228], "communal": "6x4 @ 18px", "player": "9x3 + hotbar @ 18px"},
        "compact": {"separate_composition": True, "viewport": [320, 240]},
        "outputs": [p.name for p in OUT.glob("*.png")],
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")


if __name__ == "__main__": main()
