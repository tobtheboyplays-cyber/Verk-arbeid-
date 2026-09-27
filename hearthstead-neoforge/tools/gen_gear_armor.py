#!/usr/bin/env python3
"""Gear-tier armour overlays for settlers (128x64, settler UV table).

Vanilla armour ITEMS stay vanilla; only how they look on a settler changes
(players keep the vanilla look). SettlerArmorLayer re-renders the posed
settler model once per worn slot with one of these sheets (cutout: clear
texels drop out, opaque ones overwrite the skin at identical depth), so the
armour bends with the motion engine's limb meshes for free.

    gear/<material>_<slot>.png
        material: leather | chain | iron | gold | diamond | netherite
        slot:     head (head + hood + helm rim) | chest (torso + arms)
                  | legs (legs rows 0-8) | feet (legs rows 9-11 + soles)
    gear/tabard_field.png   greyscale surcoat, tinted with the Banner's field colour
    gear/tabard_trim.png    greyscale border and charge, tinted with its trim colour

Look, per Gear Tier (plan: settlement/gear/GearTier.java):
    leather    quilted gambeson with a laced leather jerkin, arming cap, boots
    chain      mail hauberk and coif, mail chausses, leather-soled feet
    iron       plate breastplate and fauld over mail, nasal kettle helm,
               pauldrons, knee cops, gauntlets
    gold       the iron cut, gilded (ceremonial; gold is Common tier)
    diamond    fine blued plate with gilt edges, open bascinet with aventail
    netherite  blackened knightly plate, closed great helm with a gilt cross

Seams sit ON the joint rows the motion engine bends (arm v4, leg v6/v10) or
well clear of them, never a detail that would smear when stretched.
Every colour comes off a texlib ramp; lighting is FACE_LIGHT per face.
Deterministic: fixed crc32 seeds.

Run:  python tools/gen_gear_armor.py [--preview OUT_DIR]
"""
import os
import random
import sys
import zlib

sys.path.insert(0, os.path.dirname(__file__))
from PIL import Image
from texlib import (ramp, make_ramp, new_image, put, box_faces, FACE_LIGHT,
                    shade, save, scale_check, hx)
from gen_settler import UV, OUT  # read-only: single source of truth

GEAR_OUT = os.path.join(OUT, "gear")

LEATHER = ramp("leather")
GAMB = ramp("gambeson")
IRON = ramp("iron")
FORGED = ramp("iron_forged")
BRASS = ramp("brass")
CHAR = ramp("charcoal")
BLUED = [hx(c) for c in make_ramp(hue=214, saturation=40, value=20, hue_drift=-14,
                                   family="cool", value_steps=(10, 10, 11, 11))]
GILT = [hx(c) for c in make_ramp(hue=40, saturation=82, value=40, hue_drift=12,
                                  family="warm", value_steps=(10, 11, 11, 11))]
BLACK = [hx(c) for c in make_ramp(hue=262, saturation=16, value=12, hue_drift=-14,
                                   family="cool", value_steps=(8, 8, 9, 9))]

MATERIALS = ("leather", "chain", "iron", "gold", "diamond", "netherite")
SLOTS = ("head", "chest", "legs", "feet")
SIDES = ("front", "back", "right", "left")


def seed(*parts):
    return zlib.crc32("|".join(parts).encode("utf-8"))


def faces(part):
    u, v, w, h, d = UV[part]
    return box_faces(u, v, w, h, d)


# --------------------------------------------------------------- textures ---

def plate_ramp(material):
    return {"iron": IRON, "gold": GILT, "diamond": BLUED, "netherite": BLACK}[material]


def trim_ramp(material):
    return {"iron": FORGED, "gold": BRASS, "diamond": GILT, "netherite": BRASS}[material]


def quilt(img, x, y, w, h, rng, rows=None, cols=None, pitch=3):
    """Quilted linen: fill stops 2-3, stitched channels every `pitch` px."""
    for j in range(h):
        if rows is not None and j not in rows:
            continue
        for i in range(w):
            if cols is not None and i not in cols:
                continue
            idx = 3 if rng.random() < 0.7 else 2
            if (i % pitch) == pitch - 1:
                idx = 2  # stitched channel
                if j % 3 == 1:
                    idx = 1  # stitch mark
            if j == h - 1 and h > 3:
                idx = max(1, idx - 1)
            put(img, x + i, y + j, GAMB[idx])


def leather(img, x, y, w, h, rng, rows=None, cols=None, base=3):
    for j in range(h):
        if rows is not None and j not in rows:
            continue
        i = 0
        while i < w:
            run = rng.randint(2, 4)
            tone = base if rng.random() < 0.6 else base - 1
            for k in range(i, min(w, i + run)):
                if cols is None or k in cols:
                    put(img, x + k, y + j, LEATHER[tone])
            i += run


def mail(img, x, y, w, h, rows=None, cols=None, r=IRON, phase=0):
    """Riveted rings read at 16px as a 2x2 rhythm: lit ring tops over dark
    gaps, alternate rows offset by one (a brick of rings, not a dither)."""
    for j in range(h):
        if rows is not None and j not in rows:
            continue
        for i in range(w):
            if cols is not None and i not in cols:
                continue
            odd = (j + phase) % 2
            if odd == 0:
                idx = 3 if (i + j // 2) % 2 == 0 else 2
            else:
                idx = 2 if (i + j // 2) % 2 == 0 else 1
            put(img, x + i, y + j, r[idx])


def plate(img, x, y, w, h, r, rows=None, cols=None, axis="v", spec=True):
    """Plate: body in stops 1-2, a crisp stop-3 band one texel in from the
    lit edge, stop-4 specular only at the top-left corner, dark lower edge."""
    for j in range(h):
        if rows is not None and j not in rows:
            continue
        for i in range(w):
            if cols is not None and i not in cols:
                continue
            across = i if axis == "v" else j
            span = w if axis == "v" else h
            idx = 2
            if across == 1 and span > 3:
                idx = 3
            elif across == 0 or across >= span - 1:
                idx = 1
            elif across == span - 2 and span > 4:
                idx = 1
            put(img, x + i, y + j, r[idx])
    if spec and w > 2 and h > 2:
        top = min(rows) if rows else 0
        left = min(cols) if cols else 0
        put(img, x + left + 1, y + top, r[4])


def band(img, x, y, w, row, r, idx=3, cols=None):
    for i in range(w):
        if cols is None or i in cols:
            put(img, x + i, y + row, r[idx])


def edge_rows(img, x, y, w, h, r, top=True, bottom=True):
    if top:
        band(img, x, y, w, 0, r, 3)
    if bottom:
        band(img, x, y, w, h - 1, r, 1)


def rivet(img, x, y, r=IRON):
    put(img, x, y, r[4])


# ------------------------------------------------------------------ head ---

def paint_head(img, material, rng):
    for part in ("head", "hood"):
        f = faces(part)
        x, y, w, h = f["top"]
        if material == "leather":
            quilt(img, x, y, w, h, rng)
            band(img, x, y, w, h - 1, LEATHER, 2)
        elif material == "chain":
            mail(img, x, y, w, h)
        else:
            plate(img, x, y, w, h, plate_ramp(material), axis="h")
            band(img, x + 3, y, 2, 0, trim_ramp(material), 3)
            for j in range(h):  # ridge / comb
                put(img, x + 3, y + j, plate_ramp(material)[3])
                if material in ("diamond", "netherite"):
                    put(img, x + 4, y + j, trim_ramp(material)[2])
        for side in SIDES:
            x, y, w, h = f[side]
            paint_head_side(img, material, side, x, y, w, h, rng)
    paint_rim(img, material)


def paint_head_side(img, m, side, x, y, w, h, rng):
    P = plate_ramp(m) if m not in ("leather", "chain") else None
    T = trim_ramp(m) if P else None
    if side == "front":
        cheeks = {0, w - 1}
        if m == "leather":
            quilt(img, x, y, w, h, rng, rows={0, 1})
            quilt(img, x, y, w, h, rng, rows=set(range(2, h)), cols=cheeks)
            band(img, x, y, w, 1, LEATHER, 2)
        elif m == "chain":
            mail(img, x, y, w, h, rows={0, 1})
            mail(img, x, y, w, h, rows=set(range(2, h)), cols=cheeks)
            mail(img, x, y, w, h, rows={h - 1}, cols={0, 1, w - 2, w - 1})
        elif m == "netherite":
            plate(img, x, y, w, h, P, axis="h")
            band(img, x, y, w, 3, P, 0, cols=set(range(1, w - 1)))       # sight slit
            for i in (1, 2, w - 3, w - 2):
                put(img, x + i, y + 5, P[0])                               # breaths
            for j in range(1, h):                                          # gilt cross
                put(img, x + w // 2 - 1, y + j, T[3] if j != 3 else P[0])
            band(img, x, y, w, 1, T, 2, cols=set(range(2, w - 2)))
        else:
            # iron / gold: nasal kettle helm; diamond: open bascinet
            plate(img, x, y, w, h, P, rows={0, 1, 2}, axis="h")
            band(img, x, y, w, 2, T, 3 if m != "iron" else 1)
            mail(img, x, y, w, h, rows=set(range(3, h)), cols=cheeks,
                 r=IRON if m != "gold" else BRASS)
            if m in ("iron", "gold"):
                for j in range(3, 6):
                    put(img, x + w // 2 - 1, y + j, P[2])
                    put(img, x + w // 2, y + j, P[1])
                rivet(img, x + 1, y + 1, T)
                rivet(img, x + w - 2, y + 1, T)
            else:
                plate(img, x, y, w, h, P, rows=set(range(3, h - 1)), cols=cheeks)
                mail(img, x, y, w, h, rows={h - 1}, cols={0, 1, w - 2, w - 1})
        return
    # sides and back
    if m == "leather":
        quilt(img, x, y, w, h, rng)
        band(img, x, y, w, 1, LEATHER, 2)
    elif m == "chain":
        mail(img, x, y, w, h)
    elif m == "netherite":
        plate(img, x, y, w, h, P)
        band(img, x, y, w, 1, T, 2)
        band(img, x, y, w, h - 1, P, 0)
    else:
        cap_rows = set(range(0, 4 if m != "diamond" else 6))
        plate(img, x, y, w, h, P, rows=cap_rows)
        band(img, x, y, w, max(cap_rows), T, 3 if m != "iron" else 1)
        mail(img, x, y, w, h, rows=set(range(max(cap_rows) + 1, h)),
             r=IRON if m != "gold" else BRASS)
        if side in ("right", "left"):
            rivet(img, x + w // 2, y + 1, T)


def paint_rim(img, m):
    """Helm brim on the guard rim cubes (kettle hat): iron and gold only."""
    if m not in ("iron", "gold"):
        return
    P = plate_ramp(m)
    for part in ("guard_brow", "guard_side_rim"):
        u, v, w, h, d = UV[part]
        for face, (x, y, fw, fh) in box_faces(u, v, w, h, d).items():
            for j in range(fh):
                for i in range(fw):
                    put(img, x + i, y + j, P[3] if face == "top" and j == 0 else P[1])


# ----------------------------------------------------------------- chest ---

def paint_chest(img, m, rng):
    f = faces("torso")
    P = plate_ramp(m) if m not in ("leather", "chain") else None
    T = trim_ramp(m) if P else None
    for face, (x, y, w, h) in f.items():
        if face == "bottom":
            continue
        if m == "leather":
            quilt(img, x, y, w, h, rng)
            if face in ("front", "back"):
                cols = set(range(1, w - 1))
                leather(img, x, y, w, h, rng, rows=set(range(0, 9)), cols=cols)
                if face == "front":
                    for j in range(1, 8, 2):   # lacing
                        put(img, x + w // 2 - 1, y + j, LEATHER[1])
                        put(img, x + w // 2, y + j + 1, LEATHER[1])
                band(img, x, y, w, 8, LEATHER, 1, cols=cols)
            elif face == "top":
                leather(img, x, y, w, h, rng, cols=set(range(1, w - 1)))
        elif m == "chain":
            mail(img, x, y, w, h)
            if face in ("front", "back"):
                band(img, x, y, w, h - 1, LEATHER, 2)   # hem binding
        elif m == "netherite":
            if face == "top":
                plate(img, x, y, w, h, P, axis="h")
                continue
            plate(img, x, y, w, h, P, axis="v" if face in ("front", "back") else "h")
            band(img, x, y, w, 0, T, 3)
            for j in (8, 10):   # fauld lames
                band(img, x, y, w, j, P, 0)
                band(img, x, y, w, j + 1, P, 3)
            if face == "front":
                for j in range(1, 8):
                    put(img, x + w // 2 - 1, y + j, T[2])
                    put(img, x + w // 2, y + j, P[3])
        else:
            # breastplate + fauld over a mail hauberk (sides stay mail)
            mail(img, x, y, w, h)
            if face in ("front", "back"):
                plate(img, x, y, w, h, P, rows=set(range(0, 8)))
                band(img, x, y, w, 0, T, 3)
                if m in ("diamond", "gold"):
                    for j in range(0, 8):
                        put(img, x, y + j, T[2])
                        put(img, x + w - 1, y + j, T[2])
                if face == "front":
                    for j in range(1, 7):   # medial ridge
                        put(img, x + w // 2 - 1, y + j, P[3])
                        put(img, x + w // 2, y + j, P[1])
                band(img, x, y, w, 7, P, 0)
                for j in (8, 10):   # fauld lames
                    plate(img, x, y, w, h, P, rows={j, j + 1}, axis="h", spec=False)
                    band(img, x, y, w, j + 1, P, 0)
                rivet(img, x + 1, y + 2, T)
                rivet(img, x + w - 2, y + 2, T)
            elif face == "top":
                plate(img, x, y, w, h, P, axis="h")
    for arm in ("right_arm", "left_arm"):
        paint_arm(img, m, arm, rng)


def paint_arm(img, m, arm, rng):
    f = faces(arm)
    P = plate_ramp(m) if m not in ("leather", "chain") else None
    T = trim_ramp(m) if P else None
    for face, (x, y, w, h) in f.items():
        if face == "bottom":
            continue
        if face == "top":
            if m == "leather":
                quilt(img, x, y, w, h, rng)
            elif m == "chain":
                mail(img, x, y, w, h)
            else:
                plate(img, x, y, w, h, P, axis="h")
            continue
        if m == "leather":
            quilt(img, x, y, w, h, rng, rows=set(range(0, 9)), pitch=2)
            leather(img, x, y, w, h, rng, rows={9, 10, 11})   # bracer
            band(img, x, y, w, 9, LEATHER, 4 if face == "front" else 3)
        elif m == "chain":
            mail(img, x, y, w, h, rows=set(range(0, 10)))
            leather(img, x, y, w, h, rng, rows={10, 11})
        elif m == "netherite":
            plate(img, x, y, w, h, P, rows=set(range(0, 12)), axis="h", spec=False)
            for j in (0, 3, 9):
                band(img, x, y, w, j, T if j == 0 else P, 3)
            band(img, x, y, w, 4, P, 0)       # couter seam on the elbow row
            band(img, x, y, w, 8, P, 0)
        else:
            mail(img, x, y, w, h, rows=set(range(4, 9)))
            plate(img, x, y, w, h, P, rows={0, 1, 2, 3}, axis="h", spec=False)  # pauldron
            band(img, x, y, w, 0, T if m != "iron" else P, 3)
            band(img, x, y, w, 2, P, 3)
            band(img, x, y, w, 3, P, 0)
            plate(img, x, y, w, h, P, rows={9, 10, 11}, axis="h", spec=False)  # gauntlet
            band(img, x, y, w, 9, T if m != "iron" else P, 3)
            band(img, x, y, w, 11, P, 0)


# ------------------------------------------------------------- legs, feet ---

def paint_legs(img, m, rng):
    for leg in ("right_leg", "left_leg"):
        f = faces(leg)
        P = plate_ramp(m) if m not in ("leather", "chain") else None
        T = trim_ramp(m) if P else None
        for face, (x, y, w, h) in f.items():
            if face in ("bottom",):
                continue
            if face == "top":
                if m == "leather":
                    quilt(img, x, y, w, h, rng)
                elif m == "chain":
                    mail(img, x, y, w, h)
                else:
                    plate(img, x, y, w, h, P, axis="h")
                continue
            rows = set(range(0, 9))
            if m == "leather":
                quilt(img, x, y, w, h, rng, rows=rows, pitch=2)
                if face == "front":
                    leather(img, x, y, w, h, rng, rows={5, 6, 7})   # knee patch
            elif m == "chain":
                mail(img, x, y, w, h, rows=rows)
            elif m == "netherite":
                plate(img, x, y, w, h, P, rows=rows, axis="h", spec=False)
                band(img, x, y, w, 0, T, 3)
                band(img, x, y, w, 5, P, 0)
                band(img, x, y, w, 6, P, 3)   # knee seam on the bend row
            else:
                mail(img, x, y, w, h, rows=rows)
                if face == "front" or m == "diamond":
                    plate(img, x, y, w, h, P, rows={0, 1, 2, 3, 4}, spec=face == "front")
                    band(img, x, y, w, 0, T if m != "iron" else P, 3)
                if face in ("front", "right", "left"):
                    plate(img, x, y, w, h, P, rows={5, 6, 7}, axis="h", spec=False)  # poleyn
                    band(img, x, y, w, 6, T if m != "iron" else P, 3)
                    band(img, x, y, w, 7, P, 0)


def paint_feet(img, m, rng):
    for leg in ("right_leg", "left_leg"):
        f = faces(leg)
        P = plate_ramp(m) if m not in ("leather", "chain") else None
        T = trim_ramp(m) if P else None
        for face, (x, y, w, h) in f.items():
            if face == "top":
                continue
            if face == "bottom":
                for j in range(h):
                    for i in range(w):
                        put(img, x + i, y + j, LEATHER[0] if m != "netherite" else BLACK[0])
                continue
            rows = {9, 10, 11}
            if m in ("leather", "chain"):
                leather(img, x, y, w, h, rng, rows=rows, base=2)
                band(img, x, y, w, 9, LEATHER, 3)
                band(img, x, y, w, 11, LEATHER, 0)
                if m == "chain" and face == "front":
                    mail(img, x, y, w, h, rows={9}, phase=1)
            else:
                plate(img, x, y, w, h, P, rows=rows, axis="h", spec=False)
                band(img, x, y, w, 9, T if m != "iron" else P, 3)
                band(img, x, y, w, 10, P, 1)
                band(img, x, y, w, 11, P, 0)


# ---------------------------------------------------------------- tabard ---

FIELD = [(96, 96, 96, 255), (150, 150, 150, 255), (196, 196, 196, 255),
         (226, 226, 226, 255), (246, 246, 246, 255)]


def paint_tabard(field, trim, rng):
    """Greyscale surcoat. The field tints to the Banner's base colour and the
    trim sheet to its first charge colour, so every guard wears the realm's
    arms. Front/back panels over the torso, shoulders on top, a split skirt
    over the thighs. The torso sides stay open so the armour reads through."""
    f = faces("torso")
    # Banner designer lane (26 Sep): 8 of the 10 torso columns so the village
    # field colour reads over any armour; the outer columns and sides stay open.
    lo, hi = 1, None
    for face in ("front", "back"):
        x, y, w, h = f[face]
        hi = w - 2
        for j in range(h):
            for i in range(lo, hi + 1):
                idx = 3
                if i in (lo + 1, hi - 1) and j > 8:
                    idx = 2           # skirt folds
                if j == 7:
                    idx = 2           # belt shadow
                if rng.random() < 0.12:
                    idx = 2
                put(field, x + i, y + j, FIELD[idx])
        # trim: border on the hem and side edges, charge on the chest
        for j in range(h):
            put(trim, x + lo, y + j, FIELD[3])
            put(trim, x + hi, y + j, FIELD[3])
        for i in range(lo, hi + 1):
            put(trim, x + i, y + h - 1, FIELD[4])
        # the charge sits in the rows the mantle (0-3) and belt (7-8) leave
        # visible: a 4x3 cross, two texels wide so it reads across a square
        cx = x + w // 2 - 1
        for j in (4, 5, 6):
            put(trim, cx, y + j, FIELD[4] if j == 4 else FIELD[3])
            put(trim, cx + 1, y + j, FIELD[3] if j == 4 else FIELD[2])
        put(trim, cx - 1, y + 5, FIELD[3])
        put(trim, cx + 2, y + 5, FIELD[2])
    x, y, w, h = f["top"]
    for j in range(h):
        for i in range(lo, hi + 1):
            put(field, x + i, y + j, FIELD[3 if rng.random() > 0.1 else 2])
    for leg in ("right_leg", "left_leg"):
        lf = faces(leg)
        for face in ("front", "back"):
            x, y, w, h = lf[face]
            for j in range(0, 4):
                for i in range(w):
                    put(field, x + i, y + j, FIELD[2 if j == 3 else 3])
            for i in range(w):
                put(trim, x + i, y + 3, FIELD[4])


# ---------------------------------------------------------------- lighting ---

LIT_PARTS = ("head", "hood", "torso", "right_arm", "left_arm", "right_leg",
             "left_leg", "guard_brow", "guard_side_rim")


def light(img):
    px = img.load()
    for part in LIT_PARTS:
        for face, (x, y, w, h) in faces(part).items():
            f = FACE_LIGHT[face]
            for j in range(h):
                for i in range(w):
                    c = px[x + i, y + j]
                    if c[3]:
                        px[x + i, y + j] = shade(c, f)
    return img


def build(material, slot):
    img = new_image(128, 64)
    rng = random.Random(seed("gear", material, slot))
    {"head": paint_head, "chest": paint_chest, "legs": paint_legs,
     "feet": paint_feet}[slot](img, material, rng)
    scale_check(img, 128, 64, f"{material}_{slot}")
    return light(img)


def build_tabard():
    field = new_image(128, 64)
    trim = new_image(128, 64)
    paint_tabard(field, trim, random.Random(seed("gear", "tabard")))
    return light(field), light(trim)


def main(argv):
    sheets = {}
    for m in MATERIALS:
        for s in SLOTS:
            img = build(m, s)
            sheets[(m, s)] = img
            save(img, os.path.join(GEAR_OUT, f"{m}_{s}.png"))
    field, trim = build_tabard()
    save(field, os.path.join(GEAR_OUT, "tabard_field.png"))
    save(trim, os.path.join(GEAR_OUT, "tabard_trim.png"))
    if "--preview" in argv:
        out = argv[argv.index("--preview") + 1]
        import gen_gear_preview
        gen_gear_preview.render(sheets, field, trim, out)


if __name__ == "__main__":
    main(sys.argv[1:])
