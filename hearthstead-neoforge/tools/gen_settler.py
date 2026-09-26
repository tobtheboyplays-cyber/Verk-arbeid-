#!/usr/bin/env python3
"""Modular settler appearance: independent layer sheets (128x64, same UV
table as SettlerModel) that compose by straight alpha-over into one look.

UV table (must mirror SettlerModel.createBodyLayer):
  head (0,0) 8x8x8       hood (32,0) 8x8x8      torso (64,0) 10x12x5
  backpack (96,0) 6x7x3  belt (96,20) 10x2x5
  right_arm (0,32) / left_arm (16,32) 4x12x4
  right_leg (32,32) / left_leg (48,32) 4x12x4
  cloak (64,32) 11x4x6   hat_brim (64,44) 12x1x12
  sack_body (0,17) 7x6x6  sack_neck (28,17) 5x3x4
  lumber_rail (0,49) 1x10x1  lumber_crossbar (7,49) 8x1x1
  lumber_shelf (26,49) 8x1x4  lumber_log (50,49) 2x8x2

Five independent axes -- skin tone, hair (style x color), face (eye color),
clothing, and profession outfit -- each a standalone 128x64 sheet, mostly
transparent outside the pixels it owns. Composite order (each opaque over
the last): base -> hair -> face -> clothing -> outfit. Cardinalities mirror
com.hearthstead.entity.SettlerAppearance exactly (4x4x4x3x4).

Explicit integer seeds only -- never Python's salted hash() (it is re-salted
per process via PYTHONHASHSEED, so two runs of this script would emit
different pixels for the same key). zlib.crc32 on a key's utf-8 bytes is
stable across processes and interpreter versions.
"""
import random
import sys
import os
import zlib

sys.path.insert(0, os.path.dirname(__file__))
from texlib import (ramp, shade, mix, new_image, put, box_faces, FACE_LIGHT,
                    save, lit, woven, hx)

SEED_BASE = 1420

OUT = os.path.join(os.path.dirname(__file__), "..",
                   "src/main/resources/assets/hearthstead/textures/entity/settler")
LAYERS_OUT = os.path.join(OUT, "layers")

UV = {
    "head":      (0, 0, 8, 8, 8),
    "nose":      (120, 32, 2, 4, 2),
    "hood":      (32, 0, 8, 8, 8),
    "torso":     (64, 0, 10, 12, 5),
    "backpack":  (96, 0, 6, 7, 3),
    "belt":      (96, 20, 10, 2, 5),
    "right_arm": (0, 32, 4, 12, 4),
    "left_arm":  (16, 32, 4, 12, 4),
    "right_leg": (32, 32, 4, 12, 4),
    "left_leg":  (48, 32, 4, 12, 4),
    "cloak":     (64, 32, 11, 4, 6),
    "hat_brim":  (64, 44, 12, 1, 12),
    # Guard-only allocation within its otherwise transparent hat band.
    # Farmer keeps its own original texture; these meshes are mutually exclusive.
    "guard_brow":     (64, 44, 11, 1, 2),
    "guard_side_rim": (90, 44, 1, 1, 8),
    # A2b: two overlapping cuboids make a visibly cinched cloth sack rather
    # than a wooden-looking rectangular crate.
    "sack_body": (0, 17, 7, 6, 6),
    "sack_neck": (28, 17, 5, 3, 4),
    # Original Hearthstead timber frame. These four islands occupy the free
    # lower-left atlas band and are shared by the attached/root-owned meshes.
    "lumber_rail":     (0, 49, 1, 10, 1),
    "lumber_crossbar": (7, 49, 8, 1, 1),
    "lumber_shelf":    (26, 49, 8, 1, 4),
    "lumber_log":      (50, 49, 2, 8, 2),
}

# -- modular axes (cardinalities mirror SettlerAppearance in Java) ----------

SKIN_KEYS = ["skin", "skin_tan", "skin_deep", "skin_pale"]

HAIR_COLOR_KEYS = ["hair_brn", "hair_blnd", "hair_blk", "hair_red"]

HAIR_STYLES = [
    dict(back_rows=7, side_rows=3, fringe_rows=1, beard=False),  # short crop
    dict(back_rows=9, side_rows=5, fringe_rows=2, beard=False),  # long
    dict(back_rows=3, side_rows=1, fringe_rows=0, beard=False),  # buzzed
    dict(back_rows=7, side_rows=3, fringe_rows=1, beard=True),   # short + beard
]

FACE_VARIANTS = [
    dict(iris=(62, 44, 28, 255)),  # brown
    dict(iris=(58, 84, 48, 255)),  # green
    dict(iris=(70, 84, 96, 255)),  # blue-gray
]

CLOTHING_PALETTES = [
    dict(tunic="linen_raw", trim="leather", cloak_wool="forest", legs_wool="wool_gray"),
    dict(tunic="burgundy", trim="leather", cloak_wool="forest", legs_wool="wool_gray"),
    dict(tunic="linen", trim="forest", cloak_wool="burgundy", legs_wool="wool_gray"),
    dict(tunic="wheat", trim="leather", cloak_wool="forest", legs_wool="wool_gray"),
]

# PENDING TEXLIB HOIST (coordinator): the scholar's ink-blue. texlib.py is
# outside this cycle's polermester file ownership, so the ramp lives here
# until the coordinator moves it into texlib.PALETTES verbatim as
# "ink_blue" and this constant + ramp_of() collapse into ramp("ink_blue").
# Passes palette law 1-3: V 32/43/54/64/74 (steps 10-11, span 42), hue
# 226 -> 207 (cool shadows, warm-ward highlights), chroma peaking at stops
# 1-2 (S 57/55) and falling to 32 at the top. Stop 2 is Profession.SCHOLAR's
# identity colour 0x3E5C8A exactly.
INK_BLUE = ["#2c3552", "#2f426e", "#3e5c8a", "#5a7da3", "#80a2bd"]

# Desaturated woven jute. The old wheat ramp was bright yellow enough that a
# square sack read as a buckled wooden chest in motion previews.
SACK_CLOTH = ["#49372a", "#66503c", "#80684d", "#a38862", "#c4a77b"]


def ramp_of(name):
    """ramp() plus this file's pending-hoist local ramps (see INK_BLUE)."""
    if name == "ink_blue":
        return [hx(c) for c in INK_BLUE]
    if name == "sack_cloth":
        return [hx(c) for c in SACK_CLOTH]
    return ramp(name)


# Profession outfits: the ONLY axis still tied to profession. Everything
# else (skin/hair/face/clothing) is rolled independently per settler.
PROFESSION_OUTFITS = {
    "none":     dict(headgear="hood", hood_wool="leather"),
    "farmer":   dict(headgear="straw_hat", apron=True),
    "lumberer": dict(headgear="bare", bracers=True, log_frame=True),
    "guard":    dict(headgear="helm", gambeson=True, gauntlets=True),
    # A2a: a courier reads by the carrying rig, not headgear -- hands and
    # head stay free so the carry animations own the silhouette.
    "courier":  dict(headgear="bare", satchel_rig=True),
    # CHAINS-1 crafts. Built from the same parts as everything else, but each
    # given its own apron or bracer colour, because eleven trades in one brown
    # apron is eleven settlers you cannot tell apart across a square -- which
    # is the whole reason the outfit layer exists.
    "baker":     dict(headgear="hood", hood_wool="linen", apron=True,
                      apron_wool="linen"),
    "cook":      dict(headgear="hood", hood_wool="linen_raw", apron=True,
                      apron_wool="wool_gray"),
    "butcher":   dict(headgear="bare", apron=True, apron_wool="burgundy"),
    "smelter":   dict(headgear="hood", hood_wool="iron", bracers=True,
                      bracer_wool="ember"),
    "smith":     dict(headgear="bare", apron=True, apron_wool="iron",
                      bracers=True, bracer_wool="leather"),
    "sawyer":    dict(headgear="bare", bracers=True, bracer_wool="oak"),
    "carpenter": dict(headgear="bare", apron=True, apron_wool="oak_light"),
    "mason":     dict(headgear="hood", hood_wool="stone", bracers=True,
                      bracer_wool="stone"),
    "fletcher":  dict(headgear="bare", apron=True, apron_wool="forest"),
    # RECRUIT-1: the innkeeper reads as the warm, unarmoured host -- a linen
    # hood and a amber-toned apron, nothing martial, nothing workshop-worn.
    "innkeeper": dict(headgear="hood", hood_wool="linen", apron=True,
                      apron_wool="amber"),
    "weaver":    dict(headgear="hood", hood_wool="wheat", apron=True,
                      apron_wool="straw"),
    "tanner":    dict(headgear="bare", apron=True, apron_wool="leather",
                      bracers=True, bracer_wool="burgundy"),
    # A starter trade: hood against falling grit, bracers against the rock.
    "miner":     dict(headgear="hood", hood_wool="stone", bracers=True,
                      bracer_wool="iron"),
    # RESEARCH-1 + the archer (Profession ids 18-21). Trade-telling: each
    # differs from every existing trade by >= 2 of {ramp/hue family,
    # headgear silhouette, part combination} -- checked pairwise. The
    # scholar is the skill's own worked example: hood + satchel reads
    # "scholar" before any colour does; the ink-blue hood makes it read at
    # night too. The miller is the flour that never washes out: a pale
    # linen cap crown (hood cube hidden, like the farmer) over an amber
    # apron with dusted rolled sleeves. The brewer is warm brown through:
    # oak-toned hood, leather apron, one amber barrel-hoop band. The
    # archer is a woodland silhouette against the guard's iron wall: a
    # forest hood, a fletched quiver on the back, leather arm guards.
    "mayor":     dict(headgear="hood", hood_wool="ink_blue", book_satchel=True),
    "scholar":   dict(headgear="hood", hood_wool="ink_blue",
                      book_satchel=True),
    "miller":    dict(headgear="miller_cap", apron=True, apron_wool="amber",
                      bracers=True, bracer_wool="linen", flour_dust=True),
    "brewer":    dict(headgear="hood", hood_wool="oak_light", apron=True,
                      apron_wool="leather", hoop_band=True),
    "archer":    dict(headgear="hood", hood_wool="forest", quiver=True,
                      bracers=True, bracer_wool="leather"),
    # ARMOURY-3 (Profession id 22): the armoury's own trade, the same
    # follow-up shape as MILLER/BREWER above. Deliberately headgear="bare",
    # like the smith right next to it in this table -- a hood or helm shell
    # would need SettlerModel's hood.visible switch (client/model, outside
    # this slice's file ownership) extended to show it, the exact bug this
    # table's own header comment already documents seven trades once hit.
    # Distinct from the smith by part combination (gauntlets, not bracers --
    # an armourer's own hands are gripping plate all day, not just a hot
    # tang) and by ramp family (ember's forge-glow orange, not the smith's
    # cool iron), and distinct from every other bare-headed apron trade
    # (tanner, butcher, carpenter, fletcher, sawyer, lumberer) the same two
    # ways -- checked pairwise, the archer/scholar doctrine above.
    "armourer":  dict(headgear="bare", apron=True, apron_wool="ember",
                      gauntlets=True),
    # TRADES-1 (Profession ids 23-25): the three Ring-1 gathering trades.
    # Same pairwise-distinctness doctrine as RESEARCH-1/archer above (>= 2 of
    # {ramp family, headgear silhouette, part combination} differ from every
    # other entry, checked by hand against the whole table). All three are
    # outdoor, weather-facing trades, so all three keep the hood shell --
    # the difference is entirely in ramp family and part combination, the
    # same axis MINER/MASON/ARCHER's shared hood+bracers silhouette already
    # separates on.
    #
    # HERDER: a shepherd's own wool-gray hood (unused as a hood tone
    # anywhere else in this table -- it only ever appears as COOK's apron)
    # over a plain wheat-toned cross-strap, reading as rough homespun rather
    # than any workshop's leather or iron.
    "herder":    dict(headgear="hood", hood_wool="wool_gray", bracers=True,
                      bracer_wool="wheat"),
    # FISHER: sea-worn oilskin -- an emerald hood (a colder, more saturated
    # green than ARCHER's forest) over a stone-gray apron, the two colours
    # nobody else in the table pairs.
    "fisher":    dict(headgear="hood", hood_wool="emerald", apron=True,
                      apron_wool="stone"),
    # HUNTER: the darker "oak" wood-tone (distinct from CARPENTER/BREWER's
    # lighter oak_light) with forest-toned arm guards and the same quiver
    # ARCHER already carries -- both draw a bow, so the prop is an honest
    # reuse, and the hood/bracer ramp pairing is still nobody else's.
    "hunter":    dict(headgear="hood", hood_wool="oak", quiver=True,
                      bracers=True, bracer_wool="forest"),
    # BUILDER lane (Profession id 32): a pale work cap (the miller's crown
    # mechanism, no hood cube, so SettlerModel needs no change), a dark oak
    # leather apron, amber arm guards and the tool-belt rig for the hammer
    # and plumb line. Distinct from the miller by ramp family (oak/amber vs
    # amber/linen) and part combination (rig, no flour); from every hooded
    # or bare trade by headgear silhouette plus parts -- checked pairwise.
    "builder":   dict(headgear="miller_cap", apron=True, apron_wool="oak",
                      bracers=True, bracer_wool="amber", satchel_rig=True),
}

# Legacy full-body fallback sheets (settler_<profession>.png) pick one fixed
# clothing variant each, mostly for a bit of visual variety; skin/hair/face
# default to index 0. Superseded by runtime layer compositing (VISUAL-1 V2c).
LEGACY_CLOTHING_FOR = {"none": 0, "farmer": 2, "lumberer": 1, "guard": 3}


def seed_for(*parts):
    return zlib.crc32(":".join(str(p) for p in parts).encode("utf-8")) & 0xFFFF | SEED_BASE


def compose(*layers):
    merged = layers[0].copy()
    for layer in layers[1:]:
        merged.alpha_composite(layer)
    return merged


# ------------------------------------------------------------- base (skin) --

def build_base(skin_idx):
    """Bare skin: head (all faces incl. nose/mouth shading) + hands. Every
    other UV region stays transparent -- clothing paints over it opaquely.

    hearthstead-art v2 16px-face construction: clean vertical modelling
    only -- forehead rows catch the light, the jaw falls off, sockets sit
    one step darker -- and NO per-pixel noise anywhere on skin (the old
    22% speckle read as a mask; noise/dither on skin is a named sin).
    Shadows step the warm skin ramp, never gray."""
    img = new_image(128, 64)
    skin = ramp(SKIN_KEYS[skin_idx])

    # Reserved8x6 island: all appearances and legacy fallback sheets inherit
    # the same skin tone, independent of hair/face/outfit overlays.
    for face, (x, y, width, height) in box_faces(*UV["nose"]).items():
        for row in range(height):
            for col in range(width):
                put(img, x + col, y + row, lit(skin[3 if row < 3 else 2], face))

    u, v, w, h, d = UV["head"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        for j in range(fh):
            if face == "front":
                base = 4 if j <= 1 else (3 if j <= 5 else 2)
            elif face == "top":
                base = 3  # FACE_LIGHT's 1.14 supplies the crown light
            else:
                base = 3 if j <= 5 else 2  # vertical falloff only
            for i in range(fw):
                put(img, x + i, y + j, lit(skin[base], face))
        if face == "front":
            # Temple/cheekbone highlight at the outermost columns, joined
            # to the forehead rows above (not orphans).
            put(img, x, y + 2, lit(skin[4], "front"))
            put(img, x + fw - 1, y + 2, lit(skin[4], "front"))
            # Socket row: outer columns one step darker so the face layer's
            # eyes sit IN the head; the eye row stays the highest-contrast
            # row of the face.
            put(img, x, y + 3, lit(skin[2], "front"))
            put(img, x + fw - 1, y + 3, lit(skin[2], "front"))

    x, y, fw, fh = faces["front"]
    # Nose: 1px, skin stop 1, centre column, row y+4 -- the shadow side of
    # the nose sits down-right of the top-left key. No nostrils.
    put(img, x + 4, y + 4, lit(skin[1], "front"))
    # Mouth: 2px, one value darker than its ground, never red.
    mouth = shade(skin[1], 0.9)
    put(img, x + 3, y + 5, lit(mouth, "front"))
    put(img, x + 4, y + 5, lit(mouth, "front"))
    # Jaw/chin AO -- one dark row grounding the chin against the neck.
    for i in range(fw):
        put(img, x + i, y + fh - 1, lit(skin[1], "front"))

    # Hands (rows 9-11 of the arm cubes): stop 3 with a stop-1 knuckle row
    # on the forward face (art v2: "hands from stop 3 with a stop-1
    # knuckle row"); clean ramp steps, no speckle.
    for part in ("right_arm", "left_arm"):
        u, v, w, h, d = UV[part]
        pfaces = box_faces(u, v, w, h, d)
        for face, (x, y, fw, fh) in pfaces.items():
            if face == "bottom":
                for j in range(fh):
                    for i in range(fw):
                        put(img, x + i, y + j, lit(skin[3], face))
                continue
            if face == "top":
                continue
            for j in range(9, fh):
                for i in range(fw):
                    idx = 1 if (face == "front" and j == 10) else 3
                    put(img, x + i, y + j, lit(skin[idx], face))
    return img


# -------------------------------------------------------------------- hair --

def build_hair(style_idx, color_idx):
    """Hair as RIBBON CLUMPS, not strands (hearthstead-art v2 16px rule):
    per-column vertical runs of stops 1-2-3 following the flow -- down from
    the crown, forward at the fringe -- each clump jogging its tone every
    2-4 rows so no streak runs full length and no two columns band. Glints
    are stop-3 pixels on a modulus of 7-9 so they never align into rows;
    the darkened (x0.82) part line still breaks the top-face mass. Every
    face draws its own fresh rng sequence -- never mirrored noise."""
    img = new_image(128, 64)
    style = HAIR_STYLES[style_idx]
    hair = ramp(HAIR_COLOR_KEYS[color_idx])
    rng = random.Random(seed_for("hair", style_idx, color_idx))

    u, v, w, h, d = UV["head"]
    faces = box_faces(u, v, w, h, d)

    def ribbon_cols(fw, fh):
        cols = []
        for _ in range(fw):
            tones = []
            tone = rng.choice((1, 2, 2, 2, 3))
            while len(tones) < fh:
                tones.extend([tone] * rng.randint(2, 4))
                tone = min(3, max(1, tone + rng.choice((-1, 1))))
            cols.append(tones[:fh])
        return cols

    def paint_ribbons(face, x, y, fw, rows, glint_mod, skip=None):
        cols = ribbon_cols(fw, rows)
        for j in range(rows):
            for i in range(fw):
                if skip and skip(i, j):
                    continue
                idx = cols[i][j]
                if (i * 5 + j * 3) % glint_mod == 0:
                    idx = 3
                put(img, x + i, y + j, lit(hair[idx], face))

    x, y, fw, fh = faces["top"]
    paint_ribbons("top", x, y, fw, fh, glint_mod=7)
    # Center parting groove: a single darker column breaking the crown so
    # the top face isn't one uniform mass.
    part_col = x + fw // 2
    for j in range(fh):
        put(img, part_col, y + j, lit(shade(hair[1], 0.82), "top"))

    x, y, fw, fh = faces["back"]
    paint_ribbons("back", x, y, fw, min(style["back_rows"], fh), glint_mod=9)

    for side in ("right", "left"):
        x, y, fw, fh = faces[side]
        paint_ribbons(side, x, y, fw, min(style["side_rows"], fh), glint_mod=8)
        # Contiguous only if the side hair itself reaches row 3 (or ends
        # right at it); a short style like "buzzed" (side_rows=1) would
        # otherwise leave this dot floating on bare skin two rows below
        # where the hair actually stops.
        if style["side_rows"] >= 3:
            put(img, x + (fw - 2 if side == "right" else 1), y + 3, lit(hair[2], side))

    x, y, fw, fh = faces["front"]
    if style["fringe_rows"]:
        last = style["fringe_rows"] - 1
        paint_ribbons("front", x, y, fw, style["fringe_rows"], glint_mod=8,
                      skip=lambda i, j: j == last and i in (2, 5))  # broken fringe

    brow = shade(hair[1], 1.05)
    for i in (1, 2, 5, 6):
        put(img, x + i, y + 2, lit(brow, "front"))

    if style["beard"]:
        for j in (5, 6, 7):
            for i in range(1, 7):
                if j == 5 and i in (3, 4):
                    continue  # base layer's mouth stays visible
                if rng.random() > 0.15:
                    put(img, x + i, y + j, lit(hair[3 if (i + j) % 3 else 2], "front"))
        for side in ("right", "left"):
            sx, sy, sfw, sfh = faces[side]
            for j in (5, 6):
                for i in range(sfw - 3, sfw) if side == "right" else range(3):
                    put(img, sx + i, sy + j, lit(hair[1], side))

    return img


# -------------------------------------------------------- face (eye color) --

def build_face(variant_idx):
    img = new_image(128, 64)
    variant = FACE_VARIANTS[variant_idx]
    u, v, w, h, d = UV["head"]
    x, y, fw, fh = box_faces(u, v, w, h, d)["front"]
    white = (232, 226, 210, 255)
    put(img, x + 1, y + 3, lit(white, "front"))
    put(img, x + 2, y + 3, lit(variant["iris"], "front"))
    put(img, x + 5, y + 3, lit(variant["iris"], "front"))
    put(img, x + 6, y + 3, lit(white, "front"))
    return img


# ---------------------------------------------------------------- clothing --

def build_clothing(variant_idx):
    """Four independent everyday garments with connected cloth construction.

    Variant palettes remain unchanged. Seams, knee reinforcements, cuffs and
    soles replace stochastic weave; no profession is encoded in this layer.
    """
    img = new_image(128, 64)
    palette = CLOTHING_PALETTES[variant_idx]
    tunic = ramp(palette["tunic"])
    trim = ramp(palette["trim"])
    wool = ramp(palette["legs_wool"])
    leather = ramp("leather")
    iron = ramp("iron")
    for face, (x, y, w, h) in box_faces(*UV["torso"]).items():
        _work_fill(img, face, x, y, w, h, tunic)
        if face in ("front", "back", "right", "left"):
            for j in range(3, h - 2):
                put(img, x, y + j, lit(tunic[2], face))
            for i in range(w):
                put(img, x + i, y + h - 1, lit(tunic[2], face))
        if face == "front":
            # Short split-neck shirt under the open outer collar. The face
            # and hands still come exclusively from the independent skin.
            for j in range(3):
                for i in (w // 2 - 1, w // 2):
                    put(img, x + i, y + j, lit(tunic[1], face))
            put(img, x + w // 2 - 1, y + 2, lit(trim[3], face))
            put(img, x + w // 2, y + 2, lit(trim[3], face))
            # An offset seam is a sewn panel, not a repeating texture pattern.
            seam = 2 if variant_idx in (0, 2) else w - 3
            for j in range(4, 7):
                put(img, x + seam, y + j, lit(tunic[2], face))

    for part in ("right_arm", "left_arm"):
        for face, (x, y, w, h) in box_faces(*UV[part]).items():
            if face == "bottom":
                continue
            if face == "top":
                _work_fill(img, face, x, y, w, h, tunic)
                continue
            _work_fill(img, face, x, y, w, 9, tunic)
            for i in range(w):
                put(img, x + i, y + 6, lit(tunic[2], face))
                put(img, x + i, y + 7, lit(tunic[4], face))
                put(img, x + i, y + 8, lit(tunic[3], face))

    for part in ("right_leg", "left_leg"):
        for face, (x, y, w, h) in box_faces(*UV[part]).items():
            if face in ("top", "bottom"):
                _work_fill(img, face, x, y, w, h,
                           wool if face == "top" else leather, base=2)
                continue
            _work_fill(img, face, x, y, w, 8, wool,
                       base=2 if variant_idx in (0, 2) else 3)
            if face == "front":
                # A connected two-by-three sewn knee patch. Opposite side
                # faces remain calm; this is not camouflage or random wear.
                for j in range(4, 7):
                    for i in range(1, w - 1):
                        put(img, x + i, y + j,
                            lit(wool[3 if j == 4 else 2], face))
            for i in range(w):
                for j, tone in ((8, 1), (9, 3), (10, 2), (11, 1)):
                    put(img, x + i, y + j, lit(leather[tone], face))
            if face == "front":
                for i in range(1, w - 1):
                    put(img, x + i, y + 9, lit(leather[4], face))

    # This short shoulder mesh now matches the shirt, with a constructed
    # open collar. It no longer forces every profession into a green band.
    for face, (x, y, w, h) in box_faces(*UV["cloak"]).items():
        _work_fill(img, face, x, y, w, h, tunic)
        if face == "front":
            for j in range(h):
                half = max(0, 2 - j)
                for i in range(w // 2 - half, w // 2 + half + 1):
                    put(img, x + i, y + j, (0, 0, 0, 0))
                for i in (w // 2 - half - 1, w // 2 + half + 1):
                    if 0 <= i < w:
                        put(img, x + i, y + j, lit(trim[2], face))
        elif face == "back":
            for j in range(h):
                put(img, x + w // 2, y + j, lit(tunic[2], face))

    for face, (x, y, w, h) in box_faces(*UV["backpack"]).items():
        _work_fill(img, face, x, y, w, h, leather, base=2)
        if face in ("front", "back", "right", "left"):
            for i in range(w):
                put(img, x + i, y, lit(leather[4], face))
                put(img, x + i, y + 1, lit(leather[3], face))
                put(img, x + i, y + 2, lit(leather[1], face))
        if face == "back":
            for j in (2, 3):
                put(img, x + w // 2, y + j, lit(iron[2], face))

    for face, (x, y, w, h) in box_faces(*UV["belt"]).items():
        for j in range(h):
            for i in range(w):
                put(img, x + i, y + j, lit(leather[2 if j == 0 else 1], face))
        if face == "front":
            for i in (w // 2 - 1, w // 2):
                put(img, x + i, y, lit(iron[3], face))
                put(img, x + i, y + 1, lit(iron[2], face))

    canvas = ramp_of("sack_cloth")
    for part in ("sack_body", "sack_neck"):
        for face, (x, y, w, h) in box_faces(*UV[part]).items():
            _work_fill(img, face, x, y, w, h, canvas)
            if face in ("front", "back", "right", "left"):
                for j in range(h):
                    for i in (0, w - 1):
                        put(img, x + i, y + j, lit(canvas[1], face))
                if part == "sack_neck":
                    for i in range(w):
                        put(img, x + i, y, lit(leather[2], face))
                elif w >= 5:
                    for j in range(1, h - 1):
                        put(img, x + w // 2, y + j, lit(canvas[2], face))
    return img


# ------------------------------------------------------------------ outfit --

def _paint_headgear_shell(img, o, rng):
    u, v, w, h, d = UV["hood"]
    faces = box_faces(u, v, w, h, d)
    if o.get("headgear") == "miller_cap":
        # The miller's cap is painted on the head crown (the hood cube is
        # hidden for the miller, like the farmer) -- no shell here.
        return
    if o.get("headgear") == "helm":
        iron = ramp("iron")
        for face, (x, y, fw, fh) in faces.items():
            if face == "front":
                continue
            for j in range(fh):
                for i in range(fw):
                    idx = 3
                    if j == 0 or (face == "top" and (i in (0, fw - 1) or j in (0, fh - 1))):
                        idx = 4
                    elif j >= fh - 2 and face != "top" and face != "bottom":
                        idx = 2
                    if rng.random() < 0.08:
                        idx = max(1, idx - 1)
                    put(img, x + i, y + j, lit(iron[idx], face))
            if face in ("right", "left", "back"):
                for i in range(1, fw, 3):
                    put(img, x + i, y + fh - 2, lit(iron[1], face))
        x, y, fw, fh = faces["front"]
        for i in range(fw):
            put(img, x + i, y, lit(iron[4], "front"))
            put(img, x + i, y + fh - 1, lit(iron[2], "front"))
        for j in range(fh):
            put(img, x, y + j, lit(iron[3], "front"))
            put(img, x + fw - 1, y + j, lit(iron[3], "front"))
    else:
        wool = ramp_of(o.get("hood_wool", "leather"))
        for face, (x, y, fw, fh) in faces.items():
            if face == "front":
                continue
            woven(img, x, y, fw, fh, wool, rng, face)
            if face == "back":
                for j in range(fh):
                    put(img, x + fw // 2, y + j, lit(wool[1], face))
        x, y, fw, fh = faces["front"]
        for i in range(fw):
            put(img, x + i, y, lit(wool[3], "front"))
            put(img, x + i, y + fh - 1, lit(wool[2], "front"))
        for j in range(fh):
            put(img, x, y + j, lit(wool[2], "front"))
            put(img, x + fw - 1, y + j, lit(wool[2], "front"))

    if o.get("headgear") == "straw_hat":
        u, v, w, h, d = UV["hat_brim"]
        straw = ramp("straw")
        leather = ramp("leather")
        for face, (x, y, fw, fh) in box_faces(u, v, w, h, d).items():
            for j in range(fh):
                for i in range(fw):
                    if face in ("top", "bottom"):
                        cx = i - (fw - 1) / 2
                        cy = j - (fh - 1) / 2
                        r = max(abs(cx), abs(cy))
                        if r < 2.5:
                            continue
                        if r < 3.3:
                            # Leather hatband ring at the crown base --
                            # sharp contrast against the straw brim so the
                            # crown reads as a distinct piece, not a blur.
                            put(img, x + i, y + j, lit(leather[2], face))
                            continue
                        idx = 3 if (i + j * 2) % 3 else 2
                        if r > 5:
                            idx = min(4, idx + 1)
                    else:
                        idx = 2
                    put(img, x + i, y + j, lit(straw[idx], face))


def _paint_straw_crown(img):
    """Straw-hat crown replaces hair on the head's top/upper rows -- the
    hood cube is invisible for FARMER, so this fakes the hat covering hair."""
    straw = ramp("straw")
    u, v, w, h, d = UV["head"]
    faces = box_faces(u, v, w, h, d)
    x, y, fw, fh = faces["top"]
    for j in range(fh):
        for i in range(fw):
            idx = 3 if (i + j) % 3 else 2
            put(img, x + i, y + j, lit(straw[idx], "top"))
    for face in ("back", "right", "left", "front"):
        x, y, fw, fh = faces[face]
        for j in range(2):
            for i in range(fw):
                put(img, x + i, y + j, lit(straw[3 if (i + j) % 3 else 2], face))


def _paint_apron(img, palette="leather"):
    u, v, w, h, d = UV["torso"]
    x, y, fw, fh = box_faces(u, v, w, h, d)["front"]
    leather = ramp(palette)
    for j in range(4, fh - 1):
        for i in range(2, fw - 2):
            idx = 3 if (i * 5 + j * 3) % 7 else 2
            put(img, x + i, y + j, lit(leather[idx], "front"))
    for j in range(4, fh - 1):
        put(img, x + 2, y + j, lit(leather[1], "front"))
        put(img, x + fw - 3, y + j, lit(leather[1], "front"))
    for i in range(2, fw - 2):
        put(img, x + i, y + 4, lit(leather[1], "front"))
    for i in range(4, 6):
        put(img, x + i, y + 8, lit(leather[1], "front"))


def _paint_log_frame(img):
    """Lumberer: oak carrying rails plus upright bark-covered logs.

    The mesh, not painted perspective, owns the silhouette. Texture work only
    separates wood grain, iron pegs and pale cut rings, so the prop stays
    readable from both rear three-quarter and profile views.
    """
    oak = ramp("oak")
    iron = ramp("iron")
    rings = ramp("wheat")

    for key in ("lumber_rail", "lumber_crossbar", "lumber_shelf"):
        u, v, w, h, d = UV[key]
        faces = box_faces(u, v, w, h, d)
        for face, (x, y, fw, fh) in faces.items():
            for j in range(fh):
                for i in range(fw):
                    # Sparse dark grain follows the part's long axis without
                    # turning the one-pixel rails into noisy checkerboard.
                    along = j if h >= w else i
                    idx = 2 if (along + i * 3 + j * 5) % 5 == 0 else 3
                    put(img, x + i, y + j, lit(oak[idx], face))
            if face in ("front", "back") and fw >= 4:
                put(img, x + 1, y + fh // 2, lit(iron[2], face))
                put(img, x + fw - 2, y + fh // 2, lit(iron[3], face))

    u, v, w, h, d = UV["lumber_log"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        if face in ("top", "bottom"):
            if fw == 2 and fh == 2:
                # A 2x2 cut end has no interior: reserve three pale pixels
                # and one grain mark instead of painting an all-bark rim.
                # Both rotated ends share material; runtime supplies lighting.
                for j in range(2):
                    for i in range(2):
                        put(img, x + i, y + j,
                            rings[3] if i == 1 and j == 1 else rings[4])
                continue
            for j in range(fh):
                for i in range(fw):
                    edge = i in (0, fw - 1) or j in (0, fh - 1)
                    put(img, x + i, y + j,
                        lit(oak[2] if edge else rings[3], face))
            if fw > 1 and fh > 1:
                put(img, x + fw // 2, y + fh // 2, lit(rings[1], face))
            continue
        for j in range(fh):
            for i in range(fw):
                idx = 1 if (j + i * 2) % 5 == 0 else 2
                put(img, x + i, y + j, lit(oak[idx], face))
        if fh >= 4:
            put(img, x, y + fh // 2, lit(oak[0], face))


def _paint_satchel_rig(img):
    """Courier: a cross-body strap over the torso plus a shoulder pad, so
    the load-bearing read is on the body rather than in the hands."""
    u, v, w, h, d = UV["torso"]
    faces = box_faces(u, v, w, h, d)
    leather = ramp("leather")
    iron = ramp("iron")

    x, y, fw, fh = faces["front"]
    # Diagonal strap, right shoulder down to left hip.
    for j in range(1, fh - 1):
        i = 2 + (j * (fw - 5)) // max(fh - 2, 1)
        put(img, x + i, y + j, lit(leather[3], "front"))
        put(img, x + i + 1, y + j, lit(leather[2], "front"))
        if j % 3 == 0:  # stitch highlights along the strap
            put(img, x + i, y + j, lit(leather[4], "front"))
    # Buckle where the strap crosses the belt line.
    bj = fh - 3
    bi = 2 + (bj * (fw - 5)) // max(fh - 2, 1)
    put(img, x + bi, y + bj, lit(iron[3], "front"))
    put(img, x + bi + 1, y + bj, lit(iron[2], "front"))

    # Matching strap on the back, mirrored, plus a shoulder pad.
    bx, by, bw, bh = faces["back"]
    for j in range(1, bh - 1):
        i = bw - 3 - (j * (bw - 5)) // max(bh - 2, 1)
        put(img, bx + i, by + j, lit(leather[3], "back"))
        put(img, bx + i - 1, by + j, lit(leather[2], "back"))

    tx, ty, tw, th = faces["top"]
    for i in range(1, tw - 1):
        put(img, tx + i, ty + th // 2, lit(leather[3], "top"))

    # The actual runtime courier parcel uses the sack islands (the decorative
    # generic backpack is hidden for couriers). Repaint both cuboids as a
    # reinforced canvas bundle with leather lashing, keeping it unmistakably
    # different from the lumberer's open wooden frame and visible logs.
    canvas = ramp("parchment")
    for key in ("sack_body", "sack_neck"):
        u2, v2, w2, h2, d2 = UV[key]
        pack = box_faces(u2, v2, w2, h2, d2)
        for face, (px, py, pw, ph) in pack.items():
            for j in range(ph):
                for i in range(pw):
                    idx = 3 if (i * 7 + j * 3) % 5 else 2
                    put(img, px + i, py + j, lit(canvas[idx], face))
            if face in ("front", "back", "right", "left") and ph > 1:
                for i in range(pw):
                    put(img, px + i, py + ph - 1, lit(leather[1], face))

    body = box_faces(*UV["sack_body"])
    for face in ("back", "front"):
        px, py, pw, ph = body[face]
        for i in range(pw):
            put(img, px + i, py, lit(leather[2], face))
        for j in range(ph):
            put(img, px + 1, py + j, lit(leather[1], face))
            put(img, px + pw - 2, py + j, lit(leather[1], face))
        put(img, px + pw // 2, py + 1, lit(iron[4], face))


def _paint_gambeson(img):
    """Quilted gambeson fully replaces the clothing layer's torso weave
    (opaque over every pixel) and adds a mail collar on the front."""
    u, v, w, h, d = UV["torso"]
    faces = box_faces(u, v, w, h, d)
    tunic = ramp("gambeson")
    trim = ramp("iron")
    for face, (x, y, fw, fh) in faces.items():
        if face not in ("front", "back", "right", "left"):
            continue
        for j in range(fh):
            for i in range(fw):
                idx = 3 if i % 2 == 0 else 2
                if j % 4 == 3:
                    idx = 1
                put(img, x + i, y + j, lit(tunic[idx], face))
        for i in range(fw):
            put(img, x + i, y + fh - 1, lit(trim[2], face))
    x, y, fw, fh = faces["front"]
    for j in range(2):
        for i in range(fw):
            put(img, x + i, y + j, lit(trim[4 if (i + j) % 2 else 2], "front"))


def _paint_bracers(img, palette="leather"):
    leather = ramp(palette)
    for part in ("right_arm", "left_arm"):
        u, v, w, h, d = UV[part]
        for face, (x, y, fw, fh) in box_faces(u, v, w, h, d).items():
            if face in ("top", "bottom"):
                continue
            for j in range(4, 7):
                for i in range(fw):
                    idx = 3 if j != 5 else 1
                    if j == 5 and i % 2 == 0:
                        idx = 4  # lace stitch catching the light
                    put(img, x + i, y + j, lit(leather[idx], face))


def _paint_gauntlets(img):
    leather = ramp("leather")
    for part in ("right_arm", "left_arm"):
        u, v, w, h, d = UV[part]
        for face, (x, y, fw, fh) in box_faces(u, v, w, h, d).items():
            if face in ("top", "bottom"):
                continue
            for j in (7, 8):
                for i in range(fw):
                    put(img, x + i, y + j, lit(leather[3 if (i + j) % 2 else 2], face))


def _paint_miller_cap(img, rng):
    """Miller: a flour-pale linen cap crown (the hood cube is hidden for
    the miller, so this paints the head's own crown rows, the farmer's
    straw-crown mechanism) plus the flour that never washes out -- sparse
    2px runs of the lightest stop, ~20% coverage, the doctrine's
    sanctioned dust case (never single-pixel static)."""
    linen = ramp("linen")
    u, v, w, h, d = UV["head"]
    faces = box_faces(u, v, w, h, d)
    x, y, fw, fh = faces["top"]
    for j in range(fh):
        for i in range(fw):
            put(img, x + i, y + j, lit(linen[3], "top"))
    for _ in range(6):
        dx, dy = rng.randrange(fw - 1), rng.randrange(fh)
        put(img, x + dx, y + dy, lit(linen[4], "top"))
        put(img, x + dx + 1, y + dy, lit(linen[4], "top"))
    for face in ("back", "right", "left", "front"):
        x, y, fw, fh = faces[face]
        for i in range(fw):
            put(img, x + i, y, lit(linen[4], face))      # lit upper band
            put(img, x + i, y + 1, lit(linen[3], face))  # rolled brim
        # one dust run per side band
        dx = rng.randrange(fw - 1)
        put(img, x + dx, y + 1, lit(linen[4], face))
        put(img, x + dx + 1, y + 1, lit(linen[4], face))


def _paint_flour_dust(img, rng):
    """Flour settled where flour settles: the apron's upper front and the
    shoulder tops -- sparse 2px runs, clusters not static."""
    linen = ramp("linen")
    u, v, w, h, d = UV["torso"]
    faces = box_faces(u, v, w, h, d)
    x, y, fw, fh = faces["front"]
    for _ in range(4):
        dx, dy = 2 + rng.randrange(fw - 5), 4 + rng.randrange(3)
        put(img, x + dx, y + dy, lit(linen[4], "front"))
        put(img, x + dx + 1, y + dy, lit(linen[4], "front"))
    tx, ty, tw, th = faces["top"]
    for _ in range(3):
        dx, dy = rng.randrange(tw - 1), rng.randrange(th)
        put(img, tx + dx, ty + dy, lit(linen[3], "top"))
        put(img, tx + dx + 1, ty + dy, lit(linen[3], "top"))


def _paint_book_satchel(img):
    """Scholar: the backpack cube becomes a flat leather book-satchel with
    an ink-blue flap and a parchment page-edge peeking out beneath it, plus
    a matching cross-body strap (opposite diagonal to the courier's).
    Silhouette first: hood + satchel reads 'scholar' before colour does."""
    leather = ramp("leather")
    blue = ramp_of("ink_blue")
    parch = ramp("parchment")
    u, v, w, h, d = UV["backpack"]
    pack = box_faces(u, v, w, h, d)
    for face, (px, py, pw, ph) in pack.items():
        for j in range(ph):
            for i in range(pw):
                put(img, px + i, py + j, lit(leather[3 if (i + 2 * j) % 5 else 2], face))
    for face in ("back", "front"):
        px, py, pw, ph = pack[face]
        for j in range(ph // 2):
            for i in range(pw):
                put(img, px + i, py + j, lit(blue[3 if j == 0 else 2], face))
        for i in range(pw):
            put(img, px + i, py + ph // 2, lit(blue[0], face))  # flap hem shadow
        for i in range(1, pw - 1):  # page edge under the flap
            put(img, px + i, py + ph // 2 + 1, lit(parch[4 if i % 2 else 3], face))
    u, v, w, h, d = UV["torso"]
    tf = box_faces(u, v, w, h, d)
    x, y, fw, fh = tf["front"]
    for j in range(1, fh - 1):
        i = (fw - 3) - (j * (fw - 5)) // max(fh - 2, 1)
        put(img, x + i, y + j, lit(blue[2], "front"))
        put(img, x + i + 1, y + j, lit(blue[1], "front"))
    bx, by, bw, bh = tf["back"]
    for j in range(1, bh - 1):
        i = 2 + (j * (bw - 5)) // max(bh - 2, 1)
        put(img, bx + i, by + j, lit(blue[2], "back"))
        put(img, bx + i - 1, by + j, lit(blue[1], "back"))


def _paint_hoop_band(img):
    """Brewer: a brass-amber barrel-hoop band across the leather apron --
    the cooper's mark, one warm accent on the dark ground, lit stops
    up-left per the light law."""
    amber = ramp("amber")
    u, v, w, h, d = UV["torso"]
    x, y, fw, fh = box_faces(u, v, w, h, d)["front"]
    for i in range(2, fw - 2):
        put(img, x + i, y + 6, lit(amber[3 if i <= fw // 2 else 2], "front"))


def _paint_quiver(img, rng):
    """Archer: the backpack cube becomes a fletched quiver -- leather body
    lashed with forest cord, arrow shafts ending in pale wheat fletching at
    the top. The fletching cluster is the read at distance."""
    leather = ramp("leather")
    forest = ramp("forest")
    wheat = ramp("wheat")
    u, v, w, h, d = UV["backpack"]
    pack = box_faces(u, v, w, h, d)
    for face, (px, py, pw, ph) in pack.items():
        for j in range(ph):
            for i in range(pw):
                put(img, px + i, py + j, lit(leather[3 if (i * 2 + j) % 4 else 2], face))
    for face in ("back", "front"):
        px, py, pw, ph = pack[face]
        for i in range(pw):  # two lashing cords
            put(img, px + i, py + 2, lit(forest[2], face))
            put(img, px + i, py + ph - 2, lit(forest[1], face))
        # fletched arrow ends above the quiver mouth: shaft + pale vanes
        for k, ai in enumerate((1, pw // 2, pw - 2)):
            put(img, px + ai, py, lit(wheat[4 if k != 1 else 3], face))
            put(img, px + ai, py + 1, lit(wheat[2], face))
    # arrow tips seen from above on the top face
    tx, ty, tw, th = pack["top"]
    for k, ai in enumerate((1, tw // 2, tw - 2)):
        put(img, tx + ai, ty + th // 2, lit(wheat[4 if k != 1 else 3], "top"))


# First coherent workwear set. These are profession overlays only: the four
# clothing variants still supply sleeves and exposed shirt panels, while skin,
# hair and face remain independent. Other profession OUTFIT pixels stay exact;
# the refreshed shared clothing intentionally improves their composites too.
WORKWEAR_SET = {
    "farmer":  dict(yoke="forest", body="wheat", trim="leather", shape="apron"),
    "lumberer": dict(yoke="burgundy", body="burgundy", trim="leather", shape="vest"),
    "courier": dict(yoke="amber", body="leather", trim="linen_raw", shape="harness"),
    "guard":   dict(yoke="wool_gray", body="wool_gray", trim="linen_raw", shape="padded"),
    "archer":  dict(yoke="forest", body="leather", trim="forest", shape="harness"),
    "miner":   dict(yoke="stone", body="iron", trim="amber", shape="apron"),
    "smith":   dict(yoke="iron", body="leather", trim="iron", shape="apron"),
    "baker":   dict(yoke="linen", body="linen", trim="wheat", shape="apron"),
}


def _work_fill(img, face, x, y, w, h, colors, base=3):
    """Connected material planes: one lit edge and one structural shadow.

    No stochastic weave, checkerboard or isolated highlight dots. Coordinates
    are exact face rectangles on the existing 128x64 model, never resampled.
    """
    for j in range(h):
        for i in range(w):
            tone = base
            if h >= 3 and j == 0:
                tone = min(4, base + 1)
            elif (w >= 3 and i == 0) or (h >= 4 and j == h - 1):
                tone = max(1, base - 1)
            put(img, x + i, y + j, lit(colors[tone], face))


def _work_clear(img, part):
    # Clear this outfit's old motif, not the composed layers underneath it.
    for x, y, w, h in box_faces(*UV[part]).values():
        for j in range(h):
            for i in range(w):
                put(img, x + i, y + j, (0, 0, 0, 0))


def _work_headgear(img, prof_key):
    outfit = PROFESSION_OUTFITS[prof_key]
    kind = outfit["headgear"]
    _work_clear(img, "hood")
    if kind in ("hood", "helm"):
        colors = ramp_of("iron" if kind == "helm" else outfit["hood_wool"])
        for face, (x, y, w, h) in box_faces(*UV["hood"]).items():
            # Baker retains the existing visible hood mesh, but its lower
            # shell is transparent: a short clean linen cap exposes the hair.
            cap = prof_key == "baker"
            if face == "bottom" and cap:
                continue
            if face == "front":
                for j in range(h):
                    for i in range(w):
                        covered = j < (2 if cap else 1)
                        if not cap:
                            covered |= i in (0, w - 1) or j == h - 1
                        if covered:
                            put(img, x + i, y + j,
                                lit(colors[3 if j < 2 else 2], face))
            else:
                _work_fill(img, face, x, y, w,
                           min(2, h) if cap and face != "top" else h, colors)
            if prof_key == "guard" and face in ("back", "right", "left"):
                for i in range(w):
                    put(img, x + i, y + h - 2, lit(colors[1], face))
                    put(img, x + i, y + h - 1, lit(colors[3], face))
                for j in range(1, h - 2):
                    put(img, x + w // 2, y + j, lit(colors[2], face))
            if prof_key == "miner" and face not in ("top", "bottom"):
                # A copper-colored stitched band, not a painted/emissive lamp.
                band = ramp("amber")
                for i in range(w):
                    put(img, x + i, y + 1, lit(band[2], face))
    if prof_key == "guard":
        # Three rigid planes, not cloth noise. Exact integer box UVs retain
        # one texel per model unit; nothing is borrowed from skin or clothing.
        iron = ramp_of("iron")
        tone = {"top": 4, "bottom": 1, "front": 3,
                "back": 2, "right": 3, "left": 2}
        for part in ("guard_brow", "guard_side_rim"):
            for face, (x, y, w, h) in box_faces(*UV[part]).items():
                for j in range(h):
                    for i in range(w):
                        put(img, x + i, y + j, lit(iron[tone[face]], face))
    if kind == "straw_hat":
        straw = ramp("straw")
        leather = ramp("leather")
        for face, (x, y, w, h) in box_faces(*UV["head"]).items():
            if face == "bottom":
                continue
            _work_fill(img, face, x, y, w, h if face == "top" else 2, straw)
        _work_clear(img, "hat_brim")
        for face, (x, y, w, h) in box_faces(*UV["hat_brim"]).items():
            for j in range(h):
                for i in range(w):
                    if face in ("top", "bottom"):
                        radius = max(abs(i - (w - 1) / 2), abs(j - (h - 1) / 2))
                        if radius < 2.5:
                            continue
                        color = leather[2] if radius < 3.3 else straw[3 if radius < 5 else 4]
                    else:
                        color = straw[2]
                    put(img, x + i, y + j, lit(color, face))


def _work_cargo(img, prof_key):
    if prof_key == "courier":
        canvas = ramp_of("sack_cloth")
        leather = ramp("leather")
        for part in ("sack_body", "sack_neck"):
            _work_clear(img, part)
            for face, (x, y, w, h) in box_faces(*UV[part]).items():
                _work_fill(img, face, x, y, w, h, canvas)
                if face not in ("top", "bottom"):
                    for i in range(w):
                        put(img, x + i, y, lit(leather[2], face))
                    if part == "sack_body":
                        # Broad lashing follows the cloth bag's actual faces;
                        # its curved/tapered silhouette is still model-owned.
                        for j in range(h):
                            put(img, x + 1, y + j, lit(leather[2], face))
                            put(img, x + w - 2, y + j, lit(leather[2], face))
    elif prof_key == "archer":
        leather = ramp("leather")
        cord = ramp("forest")
        _work_clear(img, "backpack")
        for face, (x, y, w, h) in box_faces(*UV["backpack"]).items():
            _work_fill(img, face, x, y, w, h, leather, base=2)
            if face in ("front", "back", "right", "left"):
                for i in range(w):
                    put(img, x + i, y, lit(leather[3], face))
                    put(img, x + i, y + h - 2, lit(cord[2], face))
                for i in range(1, w - 1):
                    put(img, x + i, y + 1, lit(leather[0], face))
            elif face == "top":
                for i in range(1, w - 1):
                    for j in range(1, h - 1):
                        put(img, x + i, y + j, lit(leather[0], face))
        # This backpack is not ammo-aware: its dark mouth and leather rim must
        # never paint permanent arrow shafts or feathers into an empty quiver.
    # Lumber rails/logs keep their UV, fill thresholds and authored bark.



def _work_strap(img, prof_key):
    leather = ramp("leather")
    # One continuous physical strap crosses shoulder, chest and the separate
    # belt mesh. UV islands use their own local rows but one body-space line.
    for part, first_row, body_offset in (("cloak", 0, 0), ("torso", 4, 0),
                                          ("belt", 0, 7)):
        for face in ("front", "back"):
            x, y, w, h = box_faces(*UV[part])[face]
            for j in range(first_row, h):
                body_row = j + body_offset
                i = 1 + body_row * 6 // 11
                if face == "back":
                    i = w - 3 - i
                for stripe in range(2):
                    if 0 <= i + stripe < w:
                        put(img, x + i + stripe, y + j,
                            lit(leather[3 - stripe], face))
    # A broad shoulder fastening, below the neck, distinguishes the carrying
    # harness without printing an icon or a fake carried item on the chest.
    face = "front"
    x, y, w, h = box_faces(*UV["cloak"])[face]
    color = ramp("amber" if prof_key == "courier" else "leather")
    for j in (1, 2):
        put(img, x + 2, y + j, lit(color[2], face))


def _work_flour_marks(img):
    """Connected flour smears where hands wipe the apron and rolled cuffs.

    Keep the clean garment construction: no stochastic dust or stray pixels.
    These live below the cloak and above the belt so actual geometry shows them.
    """
    flour = ramp("linen")
    x, y, w, h = box_faces(*UV["torso"])["front"]
    for i, j in ((3, 4), (4, 4), (5, 4), (3, 5), (4, 5)):
        put(img, x + i, y + j, lit(flour[4], "front"))
    for part, face in (("right_arm", "front"), ("left_arm", "left")):
        x, y, w, h = box_faces(*UV[part])[face]
        for i in (1, 2):
            put(img, x + i, y + 7, lit(flour[4], face))


def _paint_readable_workwear(img, prof_key):
    style = WORKWEAR_SET[prof_key]
    yoke = ramp(style["yoke"])
    cloth = ramp(style["body"])
    trim = ramp(style["trim"])
    shape = style["shape"]
    for part in ("torso", "cloak", "belt", "right_arm", "left_arm"):
        _work_clear(img, part)
    # Every shoulder face has garment construction, not a flat color stripe.
    # Transparent collar openings reveal the independent shirt beneath.
    for face, (x, y, w, h) in box_faces(*UV["cloak"]).items():
        _work_fill(img, face, x, y, w, h, yoke,
                   base=2 if prof_key in ("guard", "smith") else 3)
        if face == "front":
            for j in range(h):
                half = max(0, 2 - j)
                if shape == "vest":
                    half = 1
                for i in range(w // 2 - half, w // 2 + half + 1):
                    put(img, x + i, y + j, (0, 0, 0, 0))
                # Two lapel edges form a V or the vest's open center.
                for i in (w // 2 - half - 1, w // 2 + half + 1):
                    if 0 <= i < w:
                        put(img, x + i, y + j, lit(yoke[1], face))
            if shape == "apron":
                # Shoulder straps join a visible bib on this outer mesh;
                # painting these only on torso rows0..3 would hide them.
                for strap_x in (2, w - 3):
                    for j in range(h):
                        put(img, x + strap_x, y + j, lit(cloth[2], face))
                for j in (2, 3):
                    for i in range(3, w - 3):
                        put(img, x + i, y + j, lit(cloth[3], face))
            elif shape == "padded":
                for i in range(1, w - 1):
                    if i not in (w // 2 - 1, w // 2, w // 2 + 1):
                        put(img, x + i, y + h - 1, lit(yoke[1], face))
        elif face == "back":
            for j in range(h):
                put(img, x + w // 2, y + j, lit(yoke[2], face))
        elif face in ("right", "left"):
            for j in range(h):
                put(img, x, y + j, lit(yoke[2], face))
    torso = box_faces(*UV["torso"])
    for face in ("front", "back", "right", "left"):
        x, y, w, h = torso[face]
        if shape == "padded":
            _work_fill(img, face, x, y + 4, w, h - 4, cloth, base=2)
            for seam in range(2, w, 3):
                for j in range(4, h - 1):
                    put(img, x + seam, y + j, lit(cloth[1], face))
            if face in ("front", "back"):
                for i in range(1, w - 1):
                    put(img, x + i, y + h - 1, lit(trim[2], face))
        elif shape == "apron" and face == "front":
            _work_fill(img, face, x + 1, y + 4, w - 2, 7, cloth,
                       base=2 if prof_key == "smith" else 3)
            for i in range(1, w - 1):
                put(img, x + i, y + 10, lit(cloth[2], face))
            # Connected pocket opening stays below the separate belt.
            for i in range(3, w - 3):
                put(img, x + i, y + 9, lit(cloth[1], face))
            if prof_key == "miner":
                for i in (1, w - 2):
                    for j in (4, 5):
                        put(img, x + i, y + j, lit(trim[2], face))
        elif shape == "vest" and face in ("front", "back"):
            if face == "back":
                _work_fill(img, face, x, y + 4, w, h - 4, cloth, base=2)
            else:
                for offset in (0, w - 3):
                    _work_fill(img, face, x + offset, y + 4, 3, h - 4, cloth)
                for j in range(4, h):
                    for seam in (2, w - 3):
                        put(img, x + seam, y + j, lit(cloth[1], face))
    # Cuff construction differs by work: a long bow-arm leather guard,
    # reinforced forge/wood cuffs, or light rolled apron-worker sleeves.
    for part in ("right_arm", "left_arm"):
        for face, (x, y, w, h) in box_faces(*UV[part]).items():
            if face in ("top", "bottom"):
                continue
            start = 4 if prof_key == "archer" and part == "left_arm" else 6
            cuff = cloth if prof_key in ("smith", "lumberer", "archer", "guard") else trim
            _work_fill(img, face, x, y + start, w, 9 - start, cuff,
                       base=2 if prof_key in ("guard", "smith") else 3)
            for i in range(w):
                put(img, x + i, y + start, lit(cuff[1], face))
                put(img, x + i, y + 8, lit(cuff[2], face))
    if shape == "harness":
        _work_strap(img, prof_key)
    if prof_key == "baker":
        _work_flour_marks(img)
    _work_headgear(img, prof_key)
    _work_cargo(img, prof_key)


def build_outfit(prof_key):
    # Reuse the existing formal civic outfit without regenerating different art.
    if prof_key == "mayor":
        return build_outfit("scholar")
    img = new_image(128, 64)
    o = PROFESSION_OUTFITS[prof_key]
    # The refreshed set owns its garment islands from the start. Preserve
    # the existing frame painter explicitly: its separate UV islands and
    # runtime log-fill geometry are not replaced by the workwear layer.
    if prof_key in WORKWEAR_SET:
        if o.get("log_frame"):
            _paint_log_frame(img)
        _paint_readable_workwear(img, prof_key)
        return img
    rng = random.Random(seed_for("outfit", prof_key))

    _paint_headgear_shell(img, o, rng)
    if o.get("headgear") == "straw_hat":
        _paint_straw_crown(img)
    if o.get("apron"):
        _paint_apron(img, o.get("apron_wool", "leather"))
    if o.get("gambeson"):
        _paint_gambeson(img)
    if o.get("bracers"):
        _paint_bracers(img, o.get("bracer_wool", "leather"))
    if o.get("log_frame"):
        _paint_log_frame(img)
    if o.get("gauntlets"):
        _paint_gauntlets(img)
    if o.get("satchel_rig"):
        _paint_satchel_rig(img)
    if o.get("flour_dust"):
        _paint_flour_dust(img, rng)
    if o.get("book_satchel"):
        _paint_book_satchel(img)
    if o.get("hoop_band"):
        _paint_hoop_band(img)
    if o.get("quiver"):
        _paint_quiver(img, rng)
    if o.get("headgear") == "miller_cap":
        _paint_miller_cap(img, rng)
    return img


# --------------------------------------------------------------- legacy ----

def build(prof_key):
    """Composed legacy full-body skin for prof_key. Pure -- no I/O. Default
    skin/hair/face (index 0) with a per-profession clothing pick and that
    profession's outfit. Superseded at runtime by layer compositing (V2c);
    kept as the renderer's fallback texture."""
    clothing_idx = LEGACY_CLOTHING_FOR.get(prof_key, 0)
    return compose(
        build_base(0),
        build_hair(0, 0),
        build_face(0),
        build_clothing(clothing_idx),
        build_outfit(prof_key),
    )


def generate(prof_key):
    img = build(prof_key)
    save(img, os.path.join(OUT, f"settler_{prof_key}.png"))
    return img


def save_all_layers():
    for i, key in enumerate(SKIN_KEYS):
        save(build_base(i), os.path.join(LAYERS_OUT, f"base_{key}.png"))
    for si in range(len(HAIR_STYLES)):
        for ci, ckey in enumerate(HAIR_COLOR_KEYS):
            save(build_hair(si, ci), os.path.join(LAYERS_OUT, f"hair_{si}_{ckey}.png"))
    for i in range(len(FACE_VARIANTS)):
        save(build_face(i), os.path.join(LAYERS_OUT, f"face_{i}.png"))
    for i in range(len(CLOTHING_PALETTES)):
        save(build_clothing(i), os.path.join(LAYERS_OUT, f"clothing_{i}.png"))
    for prof in PROFESSION_OUTFITS:
        save(build_outfit(prof), os.path.join(LAYERS_OUT, f"outfit_{prof}.png"))


if __name__ == "__main__":
    for key in PROFESSION_OUTFITS:
        generate(key)
    save_all_layers()
    print("settler skins + layers done")
