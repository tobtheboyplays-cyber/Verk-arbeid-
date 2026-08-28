#!/usr/bin/env python3
"""Settler skins (128x64), one per profession, on SettlerModel's UV table.

UV table (must mirror SettlerModel.createBodyLayer):
  head (0,0) 8x8x8       hood (32,0) 8x8x8      torso (64,0) 10x12x5
  backpack (96,0) 6x7x3  belt (96,20) 10x2x5
  right_arm (0,32) / left_arm (16,32) 4x12x4
  right_leg (32,32) / left_leg (48,32) 4x12x4
  cloak (64,32) 11x4x6   hat_brim (64,44) 12x1x12
"""
import random
import sys
import os

sys.path.insert(0, os.path.dirname(__file__))
from texlib import (ramp, hx, shade, mix, new_image, fill, put, box_faces,
                    FACE_LIGHT, save, cloth)

OUT = os.path.join(os.path.dirname(__file__), "..",
                   "src/main/resources/assets/hearthstead/textures/entity/settler")

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
}

PROFILES = {
    "none": dict(
        tunic="linen_raw", trim="leather", hood_wool="leather", cloak_wool="forest",
        hair="hair_brn", iris=(62, 44, 28, 255), beard=False, skin="skin",
        headgear="hood", legs_wool="wool_gray",
    ),
    "farmer": dict(
        tunic="linen", trim="forest", hood_wool="straw", cloak_wool="forest",
        hair="hair_blnd", iris=(58, 84, 48, 255), beard=False, skin="skin_tan",
        headgear="straw_hat", legs_wool="wool_gray", apron=True,
    ),
    "lumberer": dict(
        tunic="burgundy", trim="leather", hood_wool="leather", cloak_wool="forest",
        hair="hair_blk", iris=(40, 33, 26, 255), beard=True, skin="skin",
        headgear="bare", legs_wool="wool_gray", bracers=True,
    ),
    "guard": dict(
        tunic="gambeson", trim="iron", hood_wool="iron", cloak_wool="burgundy",
        hair="hair_red", iris=(70, 84, 96, 255), beard=False, skin="skin",
        headgear="helm", legs_wool="wool_gray", gauntlets=True,
    ),
}


def lit(color, face):
    return shade(color, FACE_LIGHT[face])


def face_rect(part, face):
    u, v, w, h, d = UV[part]
    return box_faces(u, v, w, h, d)[face]


def fill_faces(img, part, painter, faces=None):
    """painter(face, x, y, w, h) paints unlit; lighting applied by painter."""
    u, v, w, h, d = UV[part]
    for face, rect in box_faces(u, v, w, h, d).items():
        if faces and face not in faces:
            continue
        painter(face, *rect)


def woven(img, x, y, w, h, colors, rng, face, base_idx=3):
    px = img.load()
    n = len(colors)
    for j in range(h):
        for i in range(w):
            idx = base_idx
            r = rng.random()
            if r < 0.16:
                idx = max(0, base_idx - 1)
            elif r < 0.22:
                idx = min(n - 1, base_idx + 1)
            px[x + i, y + j] = lit(colors[idx], face)


# ------------------------------------------------------------------ head ---

def paint_head(img, p, rng):
    skin = ramp(p["skin"])
    hair = ramp(p["hair"])
    u, v, w, h, d = UV["head"]
    faces = box_faces(u, v, w, h, d)

    # All faces: default skin with subtle tone noise.
    for face, (x, y, fw, fh) in faces.items():
        for j in range(fh):
            for i in range(fw):
                idx = 3 if rng.random() > 0.22 else 2
                put(img, x + i, y + j, lit(skin[idx], face))

    # Hair: top face fully, back fully, sides upper 3 rows, front fringe.
    if p["headgear"] == "straw_hat":
        straw = ramp("straw")
        x, y, fw, fh = faces["top"]
        for j in range(fh):
            for i in range(fw):
                idx = 3 if (i + j) % 3 else 2
                put(img, x + i, y + j, lit(straw[idx], "top"))
    else:
        x, y, fw, fh = faces["top"]
        for j in range(fh):
            for i in range(fw):
                idx = 2 if rng.random() > 0.3 else 1
                put(img, x + i, y + j, lit(hair[idx], "top"))
    straw_hat = p["headgear"] == "straw_hat"
    straw = ramp("straw")
    x, y, fw, fh = faces["back"]
    for j in range(fh - 1):
        for i in range(fw):
            if straw_hat and j < 2:
                put(img, x + i, y + j, lit(straw[3 if (i + j) % 3 else 2], "back"))
            else:
                idx = 2 if (i * 3 + j) % 4 else 1
                put(img, x + i, y + j, lit(hair[idx], "back"))
    for side in ("right", "left"):
        x, y, fw, fh = faces[side]
        for j in range(3):
            for i in range(fw):
                if straw_hat and j < 2:
                    put(img, x + i, y + j, lit(straw[3 if (i + j) % 3 else 2], side))
                else:
                    put(img, x + i, y + j, lit(hair[2 if (i + j) % 3 else 1], side))
        # sideburn
        put(img, x + (fw - 2 if side == "right" else 1), y + 3, lit(hair[2], side))

    # Front face detail.
    x, y, fw, fh = faces["front"]
    if straw_hat:
        for j in range(2):
            for i in range(fw):
                put(img, x + i, y + j, lit(straw[3 if (i + j) % 3 else 2], "front"))
    else:
        fringe_rows = 2 if p["headgear"] == "bare" else 1
        for j in range(fringe_rows):
            for i in range(fw):
                if j == fringe_rows - 1 and i in (2, 5):
                    continue  # broken fringe line
                put(img, x + i, y + j, lit(hair[2 if (i + j) % 3 else 1], "front"))
    # Brows.
    brow = shade(hair[1], 1.05)
    for i in (1, 2, 5, 6):
        put(img, x + i, y + 2, lit(brow, "front"))
    # Eyes: white + iris.
    white = (232, 226, 210, 255)
    put(img, x + 1, y + 3, lit(white, "front"))
    put(img, x + 2, y + 3, lit(p["iris"], "front"))
    put(img, x + 5, y + 3, lit(p["iris"], "front"))
    put(img, x + 6, y + 3, lit(white, "front"))
    # Nose.
    skin_d = shade(ramp(p["skin"])[2], 0.95)
    put(img, x + 3, y + 4, lit(skin_d, "front"))
    put(img, x + 4, y + 4, lit(ramp(p["skin"])[4], "front"))
    # Mouth.
    mouth = shade(ramp(p["skin"])[1], 0.9)
    put(img, x + 3, y + 5, lit(mouth, "front"))
    put(img, x + 4, y + 5, lit(mouth, "front"))
    # Beard.
    if p["beard"]:
        for j in (5, 6, 7):
            for i in range(1, 7):
                if j == 5 and i in (3, 4):
                    continue  # mouth stays visible
                if rng.random() > 0.15:
                    put(img, x + i, y + j, lit(hair[3 if (i + j) % 3 else 2], "front"))
        # jaw sides
        for side in ("right", "left"):
            sx, sy, sfw, sfh = faces[side]
            for j in (5, 6):
                for i in range(sfw - 3, sfw) if side == "right" else range(3):
                    put(img, sx + i, sy + j, lit(hair[1], side))


# ------------------------------------------------------------------ hood ---

def paint_hood(img, p, rng):
    """Hood shell. Front face is open (transparent) except a rim."""
    u, v, w, h, d = UV["hood"]
    faces = box_faces(u, v, w, h, d)
    if p["headgear"] == "helm":
        iron = ramp("iron")
        for face, (x, y, fw, fh) in faces.items():
            if face == "front":
                continue
            for j in range(fh):
                for i in range(fw):
                    idx = 3
                    if j == 0 or (face == "top" and (i in (0, fw - 1) or j in (0, fh - 1))):
                        idx = 4  # polished rim catch-light
                    elif j >= fh - 2 and face != "top" and face != "bottom":
                        idx = 2
                    if rng.random() < 0.08:
                        idx = max(1, idx - 1)
                    put(img, x + i, y + j, lit(iron[idx], face))
            # rivet dots along lower edge of side faces
            if face in ("right", "left", "back"):
                for i in range(1, fw, 3):
                    put(img, x + i, y + fh - 2, lit(iron[1], face))
        # Front: open for the face, iron rim + nasal hint at top.
        x, y, fw, fh = faces["front"]
        for i in range(fw):
            put(img, x + i, y, lit(iron[4], "front"))
            put(img, x + i, y + fh - 1, lit(iron[2], "front"))
        for j in range(fh):
            put(img, x, y + j, lit(iron[3], "front"))
            put(img, x + fw - 1, y + j, lit(iron[3], "front"))
    else:
        wool = ramp(p["hood_wool"])
        for face, (x, y, fw, fh) in faces.items():
            if face == "front":
                continue
            woven(img, x, y, fw, fh, wool, rng, face)
            # seam down the middle of the back
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


# ----------------------------------------------------------------- torso ---

def paint_torso(img, p, rng):
    tunic = ramp(p["tunic"])
    trim = ramp(p["trim"])
    u, v, w, h, d = UV["torso"]
    faces = box_faces(u, v, w, h, d)

    for face, (x, y, fw, fh) in faces.items():
        if p["tunic"] == "gambeson" and face in ("front", "back", "right", "left"):
            # Quilted vertical channels with stitch rows.
            for j in range(fh):
                for i in range(fw):
                    idx = 3 if i % 2 == 0 else 2
                    if j % 4 == 3:
                        idx = 1  # horizontal stitching
                    put(img, x + i, y + j, lit(tunic[idx], face))
        else:
            woven(img, x, y, fw, fh, tunic, rng, face)
        # Hem at the bottom of vertical faces.
        if face in ("front", "back", "right", "left"):
            for i in range(fw):
                put(img, x + i, y + fh - 1, lit(trim[2], face))

    # Front details.
    x, y, fw, fh = faces["front"]
    if p["tunic"] == "gambeson":
        # Chainmail collar: two checkered rows.
        iron = ramp("iron")
        for j in range(2):
            for i in range(fw):
                put(img, x + i, y + j, lit(iron[4 if (i + j) % 2 else 2], "front"))
    else:
        # V neckline with lacing.
        neck = shade(tunic[1], 0.9)
        put(img, x + fw // 2 - 1, y, lit(neck, "front"))
        put(img, x + fw // 2, y, lit(neck, "front"))
        put(img, x + fw // 2 - 1, y + 1, lit(neck, "front"))
        put(img, x + fw // 2, y + 1, lit(neck, "front"))
        lace = ramp("leather")[4]
        put(img, x + fw // 2 - 2, y + 1, lit(lace, "front"))
        put(img, x + fw // 2 + 1, y + 1, lit(lace, "front"))
        put(img, x + fw // 2 - 1, y + 2, lit(lace, "front"))
        put(img, x + fw // 2, y + 2, lit(lace, "front"))
    if p.get("apron"):
        leather = ramp("leather")
        for j in range(4, fh - 1):
            for i in range(2, fw - 2):
                idx = 3 if (i * 5 + j * 3) % 7 else 2
                put(img, x + i, y + j, lit(leather[idx], "front"))
        # apron stitch border
        for j in range(4, fh - 1):
            put(img, x + 2, y + j, lit(leather[1], "front"))
            put(img, x + fw - 3, y + j, lit(leather[1], "front"))
        for i in range(2, fw - 2):
            put(img, x + i, y + 4, lit(leather[1], "front"))
        # pocket line
        for i in range(4, 6):
            put(img, x + i, y + 8, lit(leather[1], "front"))

    # Backpack shoulder straps on front & back.
    strap = ramp("leather")[1]
    for face in ("front", "back"):
        fx, fy, fw2, fh2 = faces[face]
        for j in range(0, 6):
            put(img, fx + 2, fy + j, lit(strap, face))
            put(img, fx + fw2 - 3, fy + j, lit(strap, face))


# ------------------------------------------------------------------ limbs ---

def paint_arm(img, p, rng, part):
    tunic = ramp(p["tunic"])
    skin = ramp(p["skin"])
    trim = ramp(p["trim"])
    u, v, w, h, d = UV[part]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        if face == "top":
            woven(img, x, y, fw, fh, tunic, rng, face)
            continue
        if face == "bottom":
            for j in range(fh):
                for i in range(fw):
                    put(img, x + i, y + j, lit(skin[3], face))
            continue
        for j in range(fh):
            for i in range(fw):
                if j >= 9:
                    color = skin[3 if rng.random() > 0.2 else 2]     # hand
                elif j in (7, 8):
                    if p.get("gauntlets"):
                        color = ramp("leather")[3 if (i + j) % 2 else 2]
                    else:
                        color = shade(tunic[4], 1.02)                 # rolled cuff
                elif p.get("bracers") and 4 <= j <= 6:
                    color = ramp("leather")[3 if j != 5 else 1]       # strapped bracer
                elif p["tunic"] == "gambeson":
                    color = tunic[3 if i % 2 == 0 else 2]
                    if j % 4 == 3:
                        color = tunic[1]
                else:
                    color = tunic[3]
                    r = rng.random()
                    if r < 0.16:
                        color = tunic[2]
                    elif r < 0.22:
                        color = tunic[4]
                put(img, x + i, y + j, lit(color, face))
        # shoulder seam
        for i in range(fw):
            put(img, x + i, y, lit(trim[2], face))


def paint_leg(img, p, rng, part):
    wool = ramp(p["legs_wool"])
    leather = ramp("leather")
    u, v, w, h, d = UV[part]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        if face in ("top", "bottom"):
            src = wool if face == "top" else leather
            for j in range(fh):
                for i in range(fw):
                    put(img, x + i, y + j, lit(src[1 if face == "bottom" else 3], face))
            continue
        for j in range(fh):
            for i in range(fw):
                if j >= 8:
                    idx = 3 if j != 8 else 4                  # boot, lit cuff
                    if j == fh - 1:
                        idx = 1                               # sole
                    color = leather[idx]
                else:
                    color = wool[3]
                    r = rng.random()
                    if r < 0.18:
                        color = wool[2]
                    elif r < 0.24:
                        color = wool[4]
                put(img, x + i, y + j, lit(color, face))


# --------------------------------------------------------------- garnish ---

def paint_cloak(img, p, rng):
    wool = ramp(p["cloak_wool"])
    u, v, w, h, d = UV["cloak"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        woven(img, x, y, fw, fh, wool, rng, face)
        if face in ("front", "back", "right", "left"):
            for i in range(fw):
                put(img, x + i, y + fh - 1, lit(wool[1], face))  # weighted hem
    # Brooch on the front.
    x, y, fw, fh = faces["front"]
    put(img, x + fw // 2, y + 1, lit(ramp("amber")[3], "front"))


def paint_backpack(img, p, rng):
    leather = ramp("leather")
    u, v, w, h, d = UV["backpack"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        for j in range(fh):
            for i in range(fw):
                idx = 3 if (i * 3 + j * 5) % 7 else 2
                put(img, x + i, y + j, lit(leather[idx], face))
        if face in ("front", "back", "right", "left") and fh > 2:
            # Flap: top two rows lighter with a stitched edge.
            for i in range(fw):
                put(img, x + i, y, lit(leather[4], face))
                put(img, x + i, y + 1, lit(leather[3], face))
                put(img, x + i, y + 2, lit(leather[1], face))
    # Buckle on the back face (which faces away from the body).
    x, y, fw, fh = faces["back"]
    put(img, x + fw // 2, y + 2, lit(ramp("iron")[4], "back"))
    put(img, x + fw // 2, y + 3, lit(ramp("iron")[3], "back"))


def paint_belt(img, p, rng):
    leather = ramp("leather")
    u, v, w, h, d = UV["belt"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        for j in range(fh):
            for i in range(fw):
                idx = 2 if j == fh - 1 else 3
                put(img, x + i, y + j, lit(leather[idx], face))
    x, y, fw, fh = faces["front"]
    buckle = ramp("iron")
    put(img, x + fw // 2 - 1, y, lit(buckle[4], "front"))
    put(img, x + fw // 2, y, lit(buckle[3], "front"))
    put(img, x + fw // 2 - 1, y + 1, lit(buckle[3], "front"))
    put(img, x + fw // 2, y + 1, lit(buckle[2], "front"))


def paint_hat_brim(img, p, rng):
    u, v, w, h, d = UV["hat_brim"]
    faces = box_faces(u, v, w, h, d)
    if p["headgear"] != "straw_hat":
        return  # part hidden for other professions; leave transparent
    straw = ramp("straw")
    for face, (x, y, fw, fh) in faces.items():
        for j in range(fh):
            for i in range(fw):
                if face in ("top", "bottom"):
                    # Ring: skip the center where the head passes through.
                    cx = i - (fw - 1) / 2
                    cy = j - (fh - 1) / 2
                    r = max(abs(cx), abs(cy))
                    if r < 2.5:
                        continue
                    idx = 3 if (i + j * 2) % 3 else 2
                    if r > 5:
                        idx = min(4, idx + 1)  # lit outer ring
                else:
                    idx = 2
                put(img, x + i, y + j, lit(straw[idx], face))


def generate(prof_key):
    p = PROFILES[prof_key]
    rng = random.Random(hash(prof_key) & 0xFFFF | 1420)
    img = new_image(128, 64)
    paint_head(img, p, rng)
    paint_hood(img, p, rng)
    paint_torso(img, p, rng)
    paint_arm(img, p, rng, "right_arm")
    paint_arm(img, p, rng, "left_arm")
    paint_leg(img, p, rng, "right_leg")
    paint_leg(img, p, rng, "left_leg")
    paint_cloak(img, p, rng)
    paint_backpack(img, p, rng)
    paint_belt(img, p, rng)
    paint_hat_brim(img, p, rng)
    save(img, os.path.join(OUT, f"settler_{prof_key}.png"))
    return img


if __name__ == "__main__":
    for key in PROFILES:
        generate(key)
    print("settler skins done")
