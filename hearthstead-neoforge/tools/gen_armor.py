#!/usr/bin/env python3
"""Guard-rank armor overlays (128x64, settler UV table).

GuardRank.applyEquipment dresses a guard server-side, but the settler rig is
custom -- vanilla's HumanoidArmorLayer cannot pose it. So armor is a TEXTURE
OVERLAY: SettlerArmorLayer re-renders the settler's own model with one of
these sheets (entityCutoutNoCull -- transparent texels drop out, opaque ones
overwrite the skin at identical depth). One sheet per visible rank; RECRUIT
wears nothing and has no sheet.

    armor_leather.png    SPEARMAN  leather chestplate only (a vest)
    armor_iron_trim.png  VETERAN   full leather, iron-studded
    armor_iron.png       SERGEANT  iron chest+legs over leather cap/boots
    armor_captain.png    CAPTAIN   full iron, brass trim, crest

The rank ramp must read at 25% downscale across a village square: brown
chest -> brown all over -> gray core on brown -> gray head to toe with a
brass crest. Craft rules from .claude/skills/hearthstead-art/SKILL.md v2:
iron is 60-70% stops 0-1 with a single 1px stop-4 specular 1px inside the
top-left edge and dark-light-dark reflection bands (never parallel on
differently-oriented faces, never dithered); leather wears bright at rubbed
edges; brass stays restrained with patina pooled in recesses; every colour
comes off a texlib ramp under FACE_LIGHT.

Deterministic: zlib.crc32 seeds via gen_settler.seed_for (read-only import;
UV likewise, so the sheet can never drift from SettlerModel).
"""
import os
import random
import sys

sys.path.insert(0, os.path.dirname(__file__))
from texlib import ramp, new_image, put, box_faces, lit, save, scale_check
from gen_settler import UV, seed_for, OUT  # read-only: single source of truth

LEATHER = ramp("leather")
IRON = ramp("iron")
BRASS = ramp("brass")
BONE = ramp("bone")

SIDE_FACES = ("front", "back", "right", "left")


# ------------------------------------------------------------- leather ------

def _leather_rows(img, x, y, fw, fh, face, rng, rows, base=3):
    """Leather body fill: mid contrast, horizontal cluster runs of 2-4px in
    stops base-1/base, darkening one stop over the last two rows (weight
    hangs low; light stays up)."""
    rows = list(rows)
    for j in rows:
        i = 0
        while i < fw:
            run = rng.randint(2, 4)
            if fw - i - run == 1:
                run += 1               # never strand a 1px tail cluster
            r = rng.random()
            idx = base if r < 0.55 else base - 1
            if rows and j >= rows[-1] - 1:
                idx = max(1, idx - 1)      # lower rows sit darker
            for k in range(i, min(fw, i + run)):
                put(img, x + k, y + j, lit(LEATHER[idx], face))
            i += run


def _worn_edge(img, x, y, fw, face, rng, row=0):
    """Rubbed top edge: broken stop-4 runs (2-3px, gapped) -- never a full
    outline-hugging line."""
    i = rng.randint(0, 1)
    while i < fw - 1:
        run = rng.randint(2, 3)
        for k in range(i, min(fw, i + run)):
            put(img, x + k, y + row, lit(LEATHER[4], face))
        i += run + 2                       # 2px gaps: no stranded singles


def _scuff(img, x, y, fw, fh, face, rng, count=2):
    """1-2 sparse stop-1 scuff nicks, 2px each (8-connected, no orphans)."""
    for _ in range(count):
        i = rng.randint(0, max(0, fw - 3))
        j = rng.randint(1, max(1, fh - 2))
        put(img, x + i, y + j, lit(LEATHER[1], face))
        put(img, x + i + 1, y + j, lit(LEATHER[1], face))


def _leather_panel(img, x, y, fw, fh, face, rng, rows, sheen=False,
                   seams=(), worn=True):
    """A leather armor face: body runs + one sheen band + worn edge + seams
    + scuffs. `sheen` only on the garment's highest curve (chest front)."""
    _leather_rows(img, x, y, fw, fh, face, rng, rows)
    r0 = rows[0] if rows else 0
    if worn:
        _worn_edge(img, x, y, fw, face, rng, row=r0)
    if sheen:
        # Single 2-3px stop-4 band on the upper-left curve; no white glint.
        for k in range(3):
            put(img, x + 2 + k, y + r0 + 2, lit(LEATHER[4], face))
    for si in seams:
        for j in rows:
            put(img, x + si, y + j, lit(LEATHER[0], face))
    if fh >= 4:
        _scuff(img, x, y, fw, rows[-1] + 1 if rows else fh, face, rng)


# ---------------------------------------------------------------- iron ------

def _iron_face(img, x, y, fw, fh, face, rng, bands="v", specular=True,
               rows=None):
    """Forged iron plate. Reflection reads as dark-light-dark bands across
    the band axis (vertical on front/back, horizontal on sides -- never
    parallel on differently-oriented faces); the light band sits up-left of
    centre. 60-70% of the surface stays in stops 0-1; the specular is a
    single 1px stop-4 line 1px inside the top-left edge. No dither, no
    noise -- forge texture is sparse peen dashes only."""
    rows = list(rows) if rows is not None else list(range(fh))
    r0, rN = rows[0], rows[-1]
    for j in rows:
        for i in range(fw):
            if bands == "v":
                t = i / max(1, fw - 1)
            else:
                t = (j - r0) / max(1, rN - r0)
            if 0.20 <= t <= 0.45:          # light band, biased up-left
                idx = 2
            elif t < 0.20:
                idx = 1
            elif t <= 0.60:
                idx = 1
            else:
                idx = 0
            ci0 = max(0, round(0.2 * (fw - 1)))
            ci1 = max(ci0 + 1, round(0.45 * (fw - 1)))
            if j == r0 and idx < 3 and ci0 <= i <= ci1:
                idx = 3                    # top edge catch-light, >=2px run
            if j >= rN - 1:
                idx = min(idx, 1)          # weight pools at the bottom
            put(img, x + i, y + j, lit(IRON[idx], face))
    if specular and fw >= 3 and len(rows) >= 3:
        # The single stop-4 specular: 1px vertical line, 1px inside the
        # top-left edge, <=10% of the face.
        length = min(3, len(rows) - 1)
        for j in range(r0 + 1, r0 + 1 + length):
            put(img, x + 1, y + j, lit(IRON[4], face))
    # Sparse peen dashes on large faces: 2px stop-0 dash, stop-3 lit edge
    # up-left of it -- one per 8x8 region at most.
    if fw >= 6 and len(rows) >= 6:
        for _ in range(2):
            i = rng.randint(2, fw - 3)
            j = rng.choice(rows[2:-2])
            put(img, x + i, y + j, lit(IRON[0], face))
            put(img, x + i + 1, y + j, lit(IRON[0], face))
            put(img, x + i - 1, y + j - 1, lit(IRON[3], face))


def _rivet(img, x, y, face, cap_ramp=IRON, glint=False):
    """2px rivet: lit crown up-left, shadow down-right. A polished cap may
    take one bone[4] glint -- the pixel that keeps iron readable at range."""
    put(img, x, y, lit(BONE[4] if glint else cap_ramp[3], face))
    put(img, x + 1, y + 1, lit(cap_ramp[1], face))


# --------------------------------------------------------------- brass ------

def _brass_trim_row(img, x, y, fw, face, rng, row):
    """Restrained brass edging: base stop 2, catch-light on the upper-left
    end only, patina (stop 0) pooled at the down-right end."""
    dent = rng.randint(2, max(2, fw - 4)) if fw >= 7 else None
    for i in range(fw):
        idx = 2
        if i <= 1:
            idx = 3                        # up-left catch
        elif i >= fw - 2:
            idx = 0                        # patina in the recess
        elif dent is not None and dent <= i <= dent + 1:
            idx = 1                        # one 2px planished dent, clustered
        put(img, x + i, y + row, lit(BRASS[idx], face))


# ----------------------------------------------------------------- parts ----

def _paint_cap(img, tier, material):
    """Skull cap on the hood cube (inflated 0.6 over head+hair). The front
    face keeps the settler's face open: a 2-row brow band, plus cheek frame
    and a 2px nasal on the captain's iron helm (eyes at head row 3 stay
    clear of columns 3-4)."""
    faces = box_faces(*UV["hood"])
    for face, (x, y, fw, fh) in faces.items():
        rng = random.Random(seed_for("armor", tier, "cap", face))
        if face == "bottom":
            continue
        if face == "front":
            if material == "iron":
                for j in (0, 1):
                    for i in range(fw):
                        put(img, x + i, y + j, lit(IRON[1 if j else 2], face))
                for k in range(2, 4):      # specular inside top-left edge
                    put(img, x + k, y, lit(IRON[4], face))
                _brass_trim_row(img, x, y, fw, face, rng, 1)
                for j in range(2, fh):     # cheek frame
                    put(img, x, y + j, lit(IRON[1], face))
                    put(img, x + fw - 1, y + j, lit(IRON[0], face))
                for j in range(2, 5):      # nasal
                    put(img, x + 3, y + j, lit(IRON[2], face))
                    put(img, x + 4, y + j, lit(IRON[1], face))
            else:
                for j in (0, 1):
                    for i in range(fw):
                        idx = 3 if j == 0 and 1 <= i <= 3 else 2
                        put(img, x + i, y + j, lit(LEATHER[idx], face))
                _worn_edge(img, x, y, fw, face, rng, row=0)
            continue
        if material == "iron":
            # No specular on the top face: the only iron cap is the
            # captain's, and the crest is that face's single accent.
            _iron_face(img, x, y, fw, fh, face, rng,
                       bands="h" if face in ("right", "left") else "v",
                       specular=False)
        else:
            _leather_panel(img, x, y, fw, fh, face, rng, range(fh),
                           worn=(face == "top"))
            if face in ("right", "left"):  # seam line down the side
                for j in range(fh):
                    put(img, x + fw // 2, y + j, lit(LEATHER[0], face))


def _paint_crest(img, tier):
    """Captain's crest: a 2px brass ridge front-to-back over the helm top,
    spilling 2 rows down the back. Brightest at the front-left (top face
    row d-1 adjoins the front face), patina toward the rear."""
    x, y, fw, fh = box_faces(*UV["hood"])["top"]
    rng = random.Random(seed_for("armor", tier, "crest"))
    for j in range(fh):
        back = j <= 1                      # top-face row 0 = back edge
        idx = 0 if back else (3 if j >= fh - 3 else 2)
        put(img, x + 3, y + j, lit(BRASS[idx], "top"))
        put(img, x + 4, y + j, lit(BRASS[max(0, idx - 1)], "top"))
    put(img, x + 3, y + fh - 1, lit(BRASS[4], "top"))  # front catch-light
    for j in range(fh):
        put(img, x + 2, y + j, lit(IRON[3], "top"))    # lit groove edge (up-left)
        put(img, x + 5, y + j, lit(IRON[0], "top"))    # shadow groove (down-right)
    bx, by, bw, bh = box_faces(*UV["hood"])["back"]
    for j in range(2):
        put(img, bx + bw // 2 - 2, by + j, lit(IRON[1], "back"))  # ridge shadow
        put(img, bx + bw // 2 - 1, by + j, lit(BRASS[1 if j else 2], "back"))
        put(img, bx + bw // 2, by + j, lit(BRASS[0 if j else 1], "back"))


def _paint_torso(img, tier, material, rows, brass=False):
    """Chest piece over the torso cube. `rows` short of 12 leaves the
    gambeson skirt showing below (the SPEARMAN vest)."""
    faces = box_faces(*UV["torso"])
    for face, (x, y, fw, fh) in faces.items():
        rng = random.Random(seed_for("armor", tier, "torso", face))
        if face == "bottom":
            continue
        if face == "top":
            body_rows = range(fh)
        else:
            body_rows = rows
        if material == "iron":
            _iron_face(img, x, y, fw, fh, face, rng,
                       bands="h" if face in ("right", "left") else "v",
                       specular=(face == "front"), rows=body_rows)
            if brass and face in ("front", "back"):
                _brass_trim_row(img, x, y, fw, face, rng, 0)
                _brass_trim_row(img, x, y, fw, face, rng, list(body_rows)[-1])
            if face == "front":
                _rivet(img, x + 1, y + list(body_rows)[0] + 1, face,
                       cap_ramp=BRASS if brass else IRON, glint=not brass)
                _rivet(img, x + fw - 3, y + list(body_rows)[0] + 1, face,
                       cap_ramp=BRASS if brass else IRON)
        else:
            seams = (0, fw - 1) if face in ("front", "back") else ()
            _leather_panel(img, x, y, fw, fh, face, rng, list(body_rows),
                           sheen=(face == "front"), seams=seams)
            if tier == "iron_trim" and face in ("front", "back"):
                # The VETERAN's iron studding: two pairs, >=3px apart, one
                # glinted crown per face at most. Rows 5 and 10 -- the only
                # torso rows the cloak (0-4), belt (7-9) and backpack leave
                # visible in-game.
                _rivet(img, x + 2, y + 5, face, glint=(face == "front"))
                _rivet(img, x + fw - 4, y + 5, face)
                _rivet(img, x + 2, y + 10, face)
                _rivet(img, x + fw - 4, y + 10, face)


def _paint_shoulders(img, tier, material, gauntlets=None):
    """Chestplate shoulder caps: arm rows 0-2 on all side faces + the arm
    top face. CAPTAIN adds iron gauntlet rows 7-8 (over the outfit's
    leather ones)."""
    for part in ("right_arm", "left_arm"):
        faces = box_faces(*UV[part])
        for face, (x, y, fw, fh) in faces.items():
            rng = random.Random(seed_for("armor", tier, part, face))
            if face == "bottom":
                continue
            if face == "top":
                if material == "iron":
                    _iron_face(img, x, y, fw, fh, face, rng, bands="v",
                               specular=False)
                    put(img, x + 1, y + 1, lit(IRON[4], face))
                else:
                    _leather_rows(img, x, y, fw, fh, face, rng, range(fh))
                continue
            cap_rows = range(0, 3)
            if material == "iron":
                _iron_face(img, x, y, fw, fh, face, rng,
                           bands="h" if face in ("right", "left") else "v",
                           specular=False, rows=cap_rows)
            else:
                _leather_panel(img, x, y, fw, fh, face, rng, list(cap_rows),
                               worn=(face == "front"))
            if gauntlets == "iron":
                _iron_face(img, x, y, fw, fh, face, rng, bands="h",
                           specular=False, rows=range(7, 9))


def _paint_legs(img, tier, leg_material, boot_material):
    """Leggings rows 0-8, boots rows 9-11 + the sole. The boot cuff (row 9)
    always reads as its own broken worn-edge line so the two pieces
    separate even when both are leather."""
    for part in ("right_leg", "left_leg"):
        faces = box_faces(*UV[part])
        for face, (x, y, fw, fh) in faces.items():
            rng = random.Random(seed_for("armor", tier, part, face))
            if face == "top":
                if leg_material == "iron":
                    _iron_face(img, x, y, fw, fh, face, rng, bands="v",
                               specular=False)
                elif leg_material:
                    _leather_rows(img, x, y, fw, fh, face, rng, range(fh))
                continue
            if face == "bottom":
                for j in range(fh):        # sole: darkest stop, no detail
                    for i in range(fw):
                        c = IRON[0] if boot_material == "iron" else LEATHER[0]
                        put(img, x + i, y + j, lit(c, face))
                continue
            if leg_material == "iron":
                _iron_face(img, x, y, fw, fh, face, rng,
                           bands="h" if face in ("right", "left") else "v",
                           specular=(face == "front"), rows=range(0, 9))
            elif leg_material:
                _leather_panel(img, x, y, fw, fh, face, rng,
                               list(range(0, 9)), worn=False)
            if boot_material == "iron":
                _iron_face(img, x, y, fw, fh, face, rng, bands="h",
                           specular=False, rows=range(9, 12))
                if face == "front":
                    put(img, x, y + 9, lit(IRON[3], face))  # cuff catch
            elif boot_material:
                for j in (10, 11):
                    i = 0
                    while i < fw:
                        run = rng.randint(2, 4)
                        if fw - i - run == 1:
                            run += 1
                        idx = 2 if rng.random() < 0.6 else 1
                        if j == 11:
                            idx = min(idx, 1)   # sole edge sits darkest
                        for k in range(i, min(fw, i + run)):
                            put(img, x + k, y + j, lit(LEATHER[idx], face))
                        i += run
                _worn_edge(img, x, y, fw, face, rng, row=9)


# ----------------------------------------------------------------- tiers ----

def build_leather():
    """SPEARMAN: a leather chestplate and nothing else. It covers the whole
    torso box: the cloak collar (rows 0-4) and belt (rows 7-9) render over
    the torso anyway, so a shorter vest would leave only hidden rows
    changed and a spearman would read as a recruit across the square."""
    img = new_image(128, 64)
    _paint_torso(img, "leather", "leather", range(0, 12))
    _paint_shoulders(img, "leather", "leather")
    return img


def build_iron_trim():
    """VETERAN: full leather (cap, cuirass, leggings, boots), iron-studded
    -- still brown at range, but armored head to toe."""
    img = new_image(128, 64)
    _paint_cap(img, "iron_trim", "leather")
    _paint_torso(img, "iron_trim", "leather", range(0, 12))
    _paint_shoulders(img, "iron_trim", "leather")
    _paint_legs(img, "iron_trim", "leather", "leather")
    return img


def build_iron():
    """SERGEANT: iron creeping in at the core -- breastplate and legplates
    over the veteran's leather cap and boots."""
    img = new_image(128, 64)
    _paint_cap(img, "iron", "leather")
    _paint_torso(img, "iron", "iron", range(0, 12))
    _paint_shoulders(img, "iron", "iron")
    _paint_legs(img, "iron", "iron", "leather")
    return img


def build_captain():
    """CAPTAIN: head-to-toe iron, brass trim, and the crest that names the
    Vaktkaptein across the square."""
    img = new_image(128, 64)
    _paint_cap(img, "captain", "iron")
    _paint_crest(img, "captain")
    _paint_torso(img, "captain", "iron", range(0, 12), brass=True)
    _paint_shoulders(img, "captain", "iron", gauntlets="iron")
    _paint_legs(img, "captain", "iron", "iron")
    return img


SHEETS = {
    "armor_leather": build_leather,      # SPEARMAN
    "armor_iron_trim": build_iron_trim,  # VETERAN
    "armor_iron": build_iron,            # SERGEANT
    "armor_captain": build_captain,      # CAPTAIN
}


def _audit_iron_share(img, name):
    """Recipe self-check: on the sergeant/captain breastplate front, 60-70%
    of opaque pixels must sit in iron stops 0-1 (lit variants included)."""
    x, y, fw, fh = box_faces(*UV["torso"])["front"]
    dark = {lit(IRON[i], "front")[:3] for i in (0, 1)}
    dark |= {IRON[i][:3] for i in (0, 1)}
    total = hits = 0
    for j in range(fh):
        for i in range(fw):
            p = img.load()[x + i, y + j]
            if p[3] == 0:
                continue
            total += 1
            if p[:3] in dark:
                hits += 1
    share = hits / max(1, total)
    assert 0.55 <= share <= 0.75, \
        f"{name}: breastplate stops 0-1 share {share:.2f} outside 0.55-0.75"


if __name__ == "__main__":
    for name, builder in SHEETS.items():
        img = builder()
        scale_check(img, 128, 64, name)
        if name in ("armor_iron", "armor_captain"):
            _audit_iron_share(img, name)
        save(img, os.path.join(OUT, f"{name}.png"))
