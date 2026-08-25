#!/usr/bin/env python3
"""Modular settler appearance: independent layer sheets (128x64, same UV
table as SettlerModel) that compose by straight alpha-over into one look.

UV table (must mirror SettlerModel.createBodyLayer):
  head (0,0) 8x8x8       hood (32,0) 8x8x8      torso (64,0) 10x12x5
  backpack (96,0) 6x7x3  belt (96,20) 10x2x5
  right_arm (0,32) / left_arm (16,32) 4x12x4
  right_leg (32,32) / left_leg (48,32) 4x12x4
  cloak (64,32) 11x4x6   hat_brim (64,44) 12x1x12
  sack (0,17) 7x8x6

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
                    save, lit, woven)

SEED_BASE = 1420

OUT = os.path.join(os.path.dirname(__file__), "..",
                   "src/main/resources/assets/hearthstead/textures/entity/settler")
LAYERS_OUT = os.path.join(OUT, "layers")

UV = {
    "head":      (0, 0, 8, 8, 8),
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
    # A2b: the carried sack. Only drawn when the settler has a load, so it
    # is painted on the clothing layer like any other garment but reads as
    # rough sackcloth rather than the tailored coat.
    "sack":      (0, 17, 7, 8, 6),
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

# Profession outfits: the ONLY axis still tied to profession. Everything
# else (skin/hair/face/clothing) is rolled independently per settler.
PROFESSION_OUTFITS = {
    "none":     dict(headgear="hood", hood_wool="leather"),
    "farmer":   dict(headgear="straw_hat", apron=True),
    "lumberer": dict(headgear="bare", bracers=True),
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
    "weaver":    dict(headgear="hood", hood_wool="wheat", apron=True,
                      apron_wool="straw"),
    "tanner":    dict(headgear="bare", apron=True, apron_wool="leather",
                      bracers=True, bracer_wool="burgundy"),
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
    other UV region stays transparent -- clothing paints over it opaquely."""
    img = new_image(128, 64)
    skin = ramp(SKIN_KEYS[skin_idx])
    rng = random.Random(seed_for("base", skin_idx))

    u, v, w, h, d = UV["head"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        for j in range(fh):
            # Subtle vertical modelling: brow/forehead catches light, the
            # jaw falls into shadow -- a flat speckle read as a mask before.
            if face == "front":
                base = 4 if j <= 1 else (3 if j <= 5 else 2)
            else:
                base = 3
            for i in range(fw):
                idx = base if rng.random() > 0.22 else max(0, base - 1)
                put(img, x + i, y + j, lit(skin[idx], face))
        if face == "front":
            # Temple/cheekbone highlight at the outermost columns -- clear
            # of the eye whites (cols 1,6), brow (cols 1,2,5,6) and
            # nose/mouth (cols 3,4) painted by later layers.
            put(img, x, y + 2, lit(skin[4], "front"))
            put(img, x + fw - 1, y + 2, lit(skin[4], "front"))

    # Nose + mouth shading: skin-tone dependent, so it lives here, not in
    # the face layer (which only owns eye color).
    x, y, fw, fh = faces["front"]
    skin_d = shade(skin[2], 0.95)
    put(img, x + 3, y + 4, lit(skin_d, "front"))
    put(img, x + 4, y + 4, lit(skin[4], "front"))
    mouth = shade(skin[1], 0.9)
    put(img, x + 3, y + 5, lit(mouth, "front"))
    put(img, x + 4, y + 5, lit(mouth, "front"))
    # Jaw/chin AO -- one dark row grounding the chin against the neck.
    jaw = shade(skin[1], 0.92)
    for i in range(fw):
        put(img, x + i, y + fh - 1, lit(jaw, "front"))

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
                    idx = 3 if rng.random() > 0.2 else 2
                    put(img, x + i, y + j, lit(skin[idx], face))
    return img


# -------------------------------------------------------------------- hair --

def build_hair(style_idx, color_idx):
    img = new_image(128, 64)
    style = HAIR_STYLES[style_idx]
    hair = ramp(HAIR_COLOR_KEYS[color_idx])
    rng = random.Random(seed_for("hair", style_idx, color_idx))

    u, v, w, h, d = UV["head"]
    faces = box_faces(u, v, w, h, d)

    # Strand suggestion: 2px-wide combed bands (not per-pixel noise) with a
    # sparse brighter glint where a strand catches the key light, so hair
    # reads as directional locks instead of a flat speckled blob.
    def strand_idx(i, j, dark=1, light=2, glint=3, glint_mod=7):
        idx = dark if ((i // 2) + (j // 2)) % 2 == 0 else light
        if (i * 5 + j * 3) % glint_mod == 0:
            idx = glint
        return idx

    x, y, fw, fh = faces["top"]
    for j in range(fh):
        for i in range(fw):
            put(img, x + i, y + j, lit(hair[strand_idx(i, j)], "top"))
    # Center parting groove: a single darker column breaking the crown so
    # the top face isn't one uniform mass.
    part_col = x + fw // 2
    for j in range(fh):
        put(img, part_col, y + j, lit(shade(hair[1], 0.82), "top"))

    x, y, fw, fh = faces["back"]
    for j in range(min(style["back_rows"], fh)):
        for i in range(fw):
            put(img, x + i, y + j, lit(hair[strand_idx(i, j, glint_mod=9)], "back"))

    for side in ("right", "left"):
        x, y, fw, fh = faces[side]
        for j in range(min(style["side_rows"], fh)):
            for i in range(fw):
                put(img, x + i, y + j, lit(hair[strand_idx(i, j, glint_mod=8)], side))
        # Contiguous only if the side hair itself reaches row 3 (or ends
        # right at it); a short style like "buzzed" (side_rows=1) would
        # otherwise leave this dot floating on bare skin two rows below
        # where the hair actually stops.
        if style["side_rows"] >= 3:
            put(img, x + (fw - 2 if side == "right" else 1), y + 3, lit(hair[2], side))

    x, y, fw, fh = faces["front"]
    for j in range(style["fringe_rows"]):
        for i in range(fw):
            if j == style["fringe_rows"] - 1 and i in (2, 5):
                continue  # broken fringe line
            put(img, x + i, y + j, lit(hair[strand_idx(i, j, glint_mod=8)], "front"))

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
    """Default garment: torso, arm sleeves, legs, cloak, backpack, belt, sack.
    No profession-specific extras -- those are the outfit layer's job."""
    img = new_image(128, 64)
    palette = CLOTHING_PALETTES[variant_idx]
    tunic = ramp(palette["tunic"])
    trim = ramp(palette["trim"])
    cloak_wool = ramp(palette["cloak_wool"])
    legs_wool = ramp(palette["legs_wool"])
    leather = ramp("leather")
    rng = random.Random(seed_for("clothing", variant_idx))

    # torso
    u, v, w, h, d = UV["torso"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        woven(img, x, y, fw, fh, tunic, rng, face)
        if face in ("front", "back", "right", "left"):
            # Shoulder catch-light, then a fold crease where the tunic
            # bunches above the belt line, then the hem trim -- three
            # dedicated tones instead of one flat woven block.
            for i in range(fw):
                put(img, x + i, y, lit(shade(tunic[4], 1.05), face))
            crease = shade(tunic[1], 0.88)
            for i in range(fw):
                put(img, x + i, y + fh - 2, lit(crease, face))
                put(img, x + i, y + fh - 1, lit(trim[2], face))
    x, y, fw, fh = faces["front"]
    neck = shade(tunic[1], 0.9)
    put(img, x + fw // 2 - 1, y, lit(neck, "front"))
    put(img, x + fw // 2, y, lit(neck, "front"))
    put(img, x + fw // 2 - 1, y + 1, lit(neck, "front"))
    put(img, x + fw // 2, y + 1, lit(neck, "front"))
    lace = leather[4]
    put(img, x + fw // 2 - 2, y + 1, lit(lace, "front"))
    put(img, x + fw // 2 + 1, y + 1, lit(lace, "front"))
    put(img, x + fw // 2 - 1, y + 2, lit(lace, "front"))
    put(img, x + fw // 2, y + 2, lit(lace, "front"))
    strap = leather[1]
    for face in ("front", "back"):
        fx, fy, fw2, fh2 = faces[face]
        for j in range(0, 6):
            put(img, fx + 2, fy + j, lit(strap, face))
            put(img, fx + fw2 - 3, fy + j, lit(strap, face))

    # arms: sleeve top, woven body, rolled cuff, shoulder seam. Hands and
    # the very bottom face are skin (base layer); rows 4-8 may be replaced
    # by a bracer/gauntlet outfit overlay.
    for part in ("right_arm", "left_arm"):
        u, v, w, h, d = UV[part]
        faces = box_faces(u, v, w, h, d)
        for face, (x, y, fw, fh) in faces.items():
            if face == "top":
                woven(img, x, y, fw, fh, tunic, rng, face)
                continue
            if face == "bottom":
                continue
            for j in range(0, 9):
                for i in range(fw):
                    if j in (7, 8):
                        color = shade(tunic[4], 1.02)  # rolled cuff
                    elif j == 6:
                        color = shade(tunic[1], 0.85)  # crease before the cuff
                    else:
                        color = tunic[3]
                        r = rng.random()
                        if r < 0.16:
                            color = tunic[2]
                        elif r < 0.22:
                            color = tunic[4]
                    put(img, x + i, y + j, lit(color, face))
            for i in range(fw):
                put(img, x + i, y, lit(trim[2], face))  # shoulder seam

    # legs
    for part in ("right_leg", "left_leg"):
        u, v, w, h, d = UV[part]
        faces = box_faces(u, v, w, h, d)
        for face, (x, y, fw, fh) in faces.items():
            if face in ("top", "bottom"):
                src = legs_wool if face == "top" else leather
                for j in range(fh):
                    for i in range(fw):
                        put(img, x + i, y + j, lit(src[1 if face == "bottom" else 3], face))
                continue
            for j in range(fh):
                for i in range(fw):
                    if j >= 8:
                        # boot: cuff highlight -> body -> welt line -> sole,
                        # four dedicated tones instead of a flat block.
                        if j == 8:
                            idx = 4
                        elif j == fh - 1:
                            idx = 1
                        elif j == fh - 2:
                            idx = 2
                        else:
                            idx = 3
                        color = leather[idx]
                    else:
                        color = legs_wool[3]
                        r = rng.random()
                        if r < 0.18:
                            color = legs_wool[2]
                        elif r < 0.24:
                            color = legs_wool[4]
                        if j == 4:
                            color = shade(color, 0.85)  # knee crease shadow
                    put(img, x + i, y + j, lit(color, face))

    # cloak
    u, v, w, h, d = UV["cloak"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        woven(img, x, y, fw, fh, cloak_wool, rng, face)
        if face in ("front", "back", "right", "left"):
            # Draped fold lines: a structured vertical crease every 3rd
            # column reads as fabric hanging in folds, distinct from the
            # random weave noise underneath.
            fold = shade(cloak_wool[1], 0.9)
            for i in range(1, fw, 3):
                for j in range(fh - 1):
                    put(img, x + i, y + j, lit(fold, face))
            for i in range(fw):
                put(img, x + i, y + fh - 1, lit(cloak_wool[1], face))
    x, y, fw, fh = faces["front"]
    put(img, x + fw // 2, y + 1, lit(ramp("amber")[3], "front"))

    # backpack
    u, v, w, h, d = UV["backpack"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        for j in range(fh):
            for i in range(fw):
                idx = 3 if (i * 3 + j * 5) % 7 else 2
                put(img, x + i, y + j, lit(leather[idx], face))
        if face in ("front", "back", "right", "left") and fh > 2:
            for i in range(fw):
                put(img, x + i, y, lit(leather[4], face))
                put(img, x + i, y + 1, lit(leather[3], face))
                put(img, x + i, y + 2, lit(leather[1], face))
    x, y, fw, fh = faces["back"]
    put(img, x + fw // 2, y + 2, lit(ramp("iron")[4], "back"))
    put(img, x + fw // 2, y + 3, lit(ramp("iron")[3], "back"))

    # belt
    u, v, w, h, d = UV["belt"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        for j in range(fh):
            for i in range(fw):
                idx = 2 if j == fh - 1 else 3
                put(img, x + i, y + j, lit(leather[idx], face))
    x, y, fw, fh = faces["front"]
    buckle = ramp("iron")
    # Dark frame pixels either side of the buckle so its square reads as
    # a distinct fitting against the leather, not a soft smear.
    put(img, x + fw // 2 - 2, y, lit(buckle[0], "front"))
    put(img, x + fw // 2 + 1, y, lit(buckle[0], "front"))
    put(img, x + fw // 2 - 2, y + 1, lit(buckle[0], "front"))
    put(img, x + fw // 2 + 1, y + 1, lit(buckle[0], "front"))
    put(img, x + fw // 2 - 1, y, lit(buckle[4], "front"))
    put(img, x + fw // 2, y, lit(buckle[3], "front"))
    put(img, x + fw // 2 - 1, y + 1, lit(buckle[3], "front"))
    put(img, x + fw // 2, y + 1, lit(buckle[2], "front"))

    # sack (A2b) -- rough sackcloth, deliberately coarser and darker than
    # the tailored tunic so a laden settler reads as carrying CARGO, not
    # wearing more clothes. Hidden by the model unless there is a real load.
    # First pass was too pale and flat and read as a crate; this one is
    # built around contrast: dark creases, a lit crown, a heavy base.
    sack_cloth = ramp("wheat")
    u, v, w, h, d = UV["sack"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        woven(img, x, y, fw, fh, sack_cloth, rng, face, base_idx=1)
        if face in ("front", "back", "right", "left"):
            # Drawstring: leather cord cinching the neck, then the gathered
            # cloth just below it.
            for i in range(fw):
                put(img, x + i, y, lit(leather[0], face))
                put(img, x + i, y + 1, lit(leather[3], face))
                put(img, x + i, y + 2, lit(sack_cloth[3], face))
            # A stuffed sack is lit across its crown and falls into vertical
            # creases; the weight gathers into a dark base.
            for j in range(3, fh - 2):
                t = (j - 3) / max(1, fh - 6)
                for i in range(fw):
                    # Distance from the middle drives the shading, so the
                    # cube reads as round rather than as a flat panel.
                    edge = abs(i - (fw - 1) / 2.0) / max(0.5, (fw - 1) / 2.0)
                    if edge > 0.72:
                        idx = 0
                    elif i % 3 == 1 and j % 2 == 0:
                        idx = 1
                    elif edge < 0.28 and t < 0.55:
                        idx = 4
                    else:
                        idx = 2 if t > 0.6 else 3
                    put(img, x + i, y + j, lit(sack_cloth[idx], face))
            for i in range(fw):
                put(img, x + i, y + fh - 2, lit(sack_cloth[1], face))
                put(img, x + i, y + fh - 1, lit(sack_cloth[0], face))
    # Knot on the back face, where the cord is tied off.
    x, y, fw, fh = faces["back"]
    put(img, x + fw // 2 - 1, y, lit(leather[4], "back"))
    put(img, x + fw // 2, y + 1, lit(leather[4], "back"))
    put(img, x + fw // 2 + 1, y, lit(leather[2], "back"))

    return img


# ------------------------------------------------------------------ outfit --

def _paint_headgear_shell(img, o, rng):
    u, v, w, h, d = UV["hood"]
    faces = box_faces(u, v, w, h, d)
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
        wool = ramp(o.get("hood_wool", "leather"))
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

    # The load sack itself: a courier is read by what they are carrying, so
    # the pack gets canvas over the default leather, lashing cord and a
    # buckled flap. Capacity is a real mechanic (D-A2a-6) -- this is its
    # silhouette.
    u2, v2, w2, h2, d2 = UV["backpack"]
    pack = box_faces(u2, v2, w2, h2, d2)
    canvas = ramp("parchment")
    for face, (px, py, pw, ph) in pack.items():
        for j in range(ph):
            for i in range(pw):
                idx = 3 if (i * 7 + j * 3) % 5 else 2
                put(img, px + i, py + j, lit(canvas[idx], face))
    for face in ("back", "front"):
        px, py, pw, ph = pack[face]
        for i in range(pw):  # flap edge across the top
            put(img, px + i, py + 1, lit(leather[2], face))
            put(img, px + i, py + 2, lit(leather[3], face))
        for j in range(3, ph):  # vertical lashing cords
            put(img, px + 1, py + j, lit(leather[1], face))
            put(img, px + pw - 2, py + j, lit(leather[1], face))
        put(img, px + pw // 2, py + 2, lit(iron[4], face))  # flap buckle


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


def build_outfit(prof_key):
    img = new_image(128, 64)
    o = PROFESSION_OUTFITS[prof_key]
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
    if o.get("gauntlets"):
        _paint_gauntlets(img)
    if o.get("satchel_rig"):
        _paint_satchel_rig(img)
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
