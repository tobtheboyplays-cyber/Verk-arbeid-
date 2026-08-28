#!/usr/bin/env python3
"""Raider skins: 64x64 sheets mirroring RaiderModel's UV table, one per
combination RaiderRenderer's exhaustive switch can select --
Variant (SKIRMISHER / BRUTE) x captain x saga-marked.

Deliberately NOT a recoloured settler, and deliberately NOT one silhouette
palette-swapped six ways. The research on both reference mods says their
raiders read as undifferentiated HP sponges -- MineColonies players report
that "chief raiders don't even stand out from regular raiders" -- so this
file paints two builds that read apart on sight (the pack vs. the
door-breaker) while sharing one faction's material culture (charcoal wraps,
iron, leather, ember eyes) so both still read as the same enemy against the
settlers' warm hearth colours.

Design register (see RaiderEntity.Variant's own javadoc for the geometry
side of this contract -- RaiderModel/RAIDER-ANIM owns silhouette and scale;
this file owns paint only):

  SKIRMISHER -- wiry, wrapped in scavenged charcoal layers, a deep hood that
  leaves only a narrow eye-slit lit. Fear here is the unknown: the face is
  mostly darkness with two catchlight eyes.

  BRUTE -- no hood (the texture leaves that UV region fully transparent, so
  the hood cube -- present in the shared rig -- simply doesn't render, the
  same trick the skirmisher's hood-cutout already relies on). Bare, scarred
  skin_deep hide instead of cloth coverage, a crude iron plate lashed on
  with leather lacing across one shoulder/flank only and a matching armoured
  forearm -- asymmetric, because the rig has one shared (captain-only)
  pauldron mesh, not two. Fear here is mass.

  Both builds: a captain is the same faction dressed by success -- a
  crimson identifying cord (rank-and-file wear a plain, uncoloured one),
  a single bone trophy bead at the collar, and (from RaiderModel) the helm
  + pauldron mesh. A saga-marked captain is a proven monster: the plain
  captain's crimson becomes brass throughout, AND war-paint streaks appear
  on the face that no plain captain has -- an escalation the player learns
  to dread on sight, not a recolour.

UV table (must mirror RaiderModel.createBodyLayer):
  head (0,0) 8x8x8      hood (32,0) 8x8x8     torso (0,16) 8x12x4
  right_arm (32,16) / left_arm (48,16) 3x12x3
  right_leg (0,32) / left_leg (16,32) 4x12x4
  pauldron (32,32) 10x3x5   helm (0,48) 9x3x9

Explicit integer seeds only -- never Python's salted hash() -- so two runs
emit identical bytes (KF-007's rule).
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from texlib import box_faces, lit, new_image, put, ramp, shade, woven  # noqa: E402
import random  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "src", "main", "resources", "assets", "hearthstead",
                   "textures", "entity", "raider")

UV = {
    "head":      (0, 0, 8, 8, 8),
    "hood":      (32, 0, 8, 8, 8),
    "torso":     (0, 16, 8, 12, 4),
    "right_arm": (32, 16, 3, 12, 3),
    "left_arm":  (48, 16, 3, 12, 3),
    "right_leg": (0, 32, 4, 12, 4),
    "left_leg":  (16, 32, 4, 12, 4),
    "pauldron":  (32, 32, 10, 3, 5),
    "helm":      (0, 48, 9, 3, 9),
}

SEED = 0x5241_4944  # "RAID"


def seed_for(*parts):
    value = SEED
    for p in parts:
        for b in str(p).encode("utf-8"):
            value = (value * 131 + b) & 0xFFFFFFFF
    return value


def build(variant, captain, marked=False):
    """variant: "SKIRMISHER" or "BRUTE" -- mirrors RaiderEntity.Variant."""
    img = new_image(64, 64)
    brute = variant == "BRUTE"
    # marked reuses its own build's plain-captain noise verbatim (same rng
    # seed) and only overlays fixed, non-random marks on top, so a marked
    # captain reads as "the same captain, proven" rather than a different
    # roll of the dice.
    rng = random.Random(seed_for("raider", variant, captain))

    if brute:
        # The exposed hide is the point -- one deep, weathered tone shared
        # by grunt and captain alike, distinct from the skirmisher's family
        # so the builds never blur into each other even in a thumbnail.
        skin = ramp("skin_deep")
    else:
        # The captain's helm brim shades the brow, so his face needs a
        # lighter base than the hooded grunt or it goes muddy.
        skin = ramp("skin_tan" if captain else "skin")
    cloth = ramp("charcoal")
    leather = ramp("leather")
    iron = ramp("iron")
    # SAGA v1: a captain the settlement's roster has actually seen earn an
    # epithet wears brass throughout, not the plain captain's crimson -- a
    # mark earned in the field, not handed out at generation
    # (Captain#earnEpithetFrom). Rank-and-file get neither: the accent is
    # reserved for the one colour the rank-and-file lack.
    accent = ramp("brass" if marked else "crimson")
    bone = ramp("bone")
    # Eyes are always amber, never the faction accent: crimson eyes on tan
    # skin have no contrast and simply vanish, while the same amber that
    # carries in a hood slit also carries on an unhooded brute.
    eye = ramp("ember")

    # ------------------------------------------------------------- head ---
    # Deliberately NOT an alternating two-tone -- a strict checkerboard
    # reads as pixel noise at entity scale rather than as skin, which is
    # exactly how the first version looked in game. Sparse variation over
    # one base tone instead.
    u, v, w, h, d = UV["head"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        woven(img, x, y, fw, fh, skin, rng, face, base_idx=2)
    x, y, fw, fh = faces["front"]
    brow_rows = (1, 2) if brute else (2,)
    for row in brow_rows:
        weight = 0.62 if row == 2 else 0.8
        for i in range(fw):
            put(img, x + i, y + row, lit(shade(skin[1], weight), "front"))
    # Socket shadows flank each eye; the eyes themselves sit inside them.
    for i in (1, 2, fw - 3, fw - 2):
        put(img, x + i, y + 3, lit(shade(skin[0], 0.7), "front"))
    put(img, x + 2, y + 3, lit(eye[4], "front"))
    put(img, x + fw - 3, y + 3, lit(eye[4], "front"))
    # Cheek hollows and a set mouth.
    put(img, x + 1, y + 4, lit(shade(skin[1], 0.8), "front"))
    put(img, x + fw - 2, y + 4, lit(shade(skin[1], 0.8), "front"))
    jaw_shade = 0.8 if brute else 0.85
    for i in range(3, fw - 3):
        put(img, x + i, y + 6, lit(shade(skin[0], jaw_shade), "front"))
    if brute:
        # Scarred hide is a faction-wide brute trait, not a captain
        # perquisite -- every brute wears one, off-column from the eyes and
        # from the captain's own identity scar below.
        for j in (4, 5, 6):
            put(img, x + fw - 2, y + j, lit(shade(skin[0], 0.6), "front"))
    if captain:
        # An identity you can see before the fight: the scar runs straight
        # through where a second eye would be -- read it as the eye that
        # scar cost him.
        for j in range(1, 6):
            put(img, x + 5, y + j, lit(shade(skin[0], 0.72), "front"))
        put(img, x + 5, y + 3, lit(accent[1], "front"))
        if marked:
            # War-paint the plain captain does not wear: the epithet made
            # visible, not just a recoloured scar. Bright accent streaks
            # bracketing the brow -- a mark the player learns to dread.
            put(img, x + 1, y + 1, lit(accent[4], "front"))
            put(img, x + fw - 2, y + 1, lit(accent[4], "front"))

    # ------------------------------------------------------------- hood ---
    if brute:
        # No hood at all: leave the whole UV region transparent so the
        # hood cube (present in the shared rig, hidden only for captains)
        # simply doesn't render for a brute -- the same alpha-cutout trick
        # the skirmisher hood below uses to expose the face, taken all the
        # way to "the whole part is invisible".
        pass
    else:
        u, v, w, h, d = UV["hood"]
        faces = box_faces(u, v, w, h, d)
        for face, (x, y, fw, fh) in faces.items():
            woven(img, x, y, fw, fh, cloth, rng, face, base_idx=1)
            if face in ("front", "back", "right", "left"):
                for i in range(fw):
                    put(img, x + i, y + fh - 1, lit(cloth[0], face))
        # Cut only a narrow eye-slit -- the face stays mostly darkness,
        # with the catchlight eyes as the one thing that carries. A wider
        # cutout (the old behaviour) showed the whole face and lost that.
        x, y, fw, fh = faces["front"]
        for i in range(1, fw - 1):
            img.putpixel((x + i, y + 3), (0, 0, 0, 0))

    # ------------------------------------------------------------ torso ---
    u, v, w, h, d = UV["torso"]
    faces = box_faces(u, v, w, h, d)
    if brute:
        for face, (x, y, fw, fh) in faces.items():
            woven(img, x, y, fw, fh, skin, rng, face, base_idx=2)
    else:
        for face, (x, y, fw, fh) in faces.items():
            woven(img, x, y, fw, fh, cloth, rng, face, base_idx=2)
    # A belt of cord, shared material culture across both builds.
    for face, (x, y, fw, fh) in faces.items():
        if face in ("front", "back", "right", "left"):
            for i in range(fw):
                put(img, x + i, y + fh - 4, lit(leather[1], face))
                put(img, x + i, y + fh - 3, lit(leather[3], face))
    x, y, fw, fh = faces["front"]
    # The lacing cord down the front: plain leather for the rank-and-file,
    # the one strong accent colour for a captain -- so the accent is
    # something the rank-and-file visibly lack, not just a recolour.
    cord_color = accent[2] if captain else leather[2]
    for j in range(1, fh - 5):
        put(img, x + fw // 2, y + j, lit(cord_color, "front"))
    if captain:
        # A single strung trophy bead at the collar -- dressed by success.
        put(img, x + fw // 2 - 2, y + 1, lit(bone[3], "front"))
    if brute and not captain:
        # The crude iron plate a grunt has no pauldron mesh to carry: lashed
        # on with leather lacing, on the same side as the armoured forearm
        # below only -- never both sides, or "asymmetric" stops meaning
        # anything.
        for face_name in ("front", "back"):
            fx, fy, ffw, ffh = faces[face_name]
            for j in range(1, ffh - 5):
                for i in range(0, 3):
                    idx = 1 if (i + j) % 3 else 0
                    put(img, fx + i, fy + j, lit(iron[idx], face_name))
            for j in (2, 6, 10):
                put(img, fx + 2, fy + j, lit(leather[3], face_name))
        rx, ry, rfw, rfh = faces["right"]
        for j in range(1, rfh - 5):
            for i in range(rfw):
                idx = 1 if (i + j) % 3 else 0
                put(img, rx + i, ry + j, lit(iron[idx], "right"))
        put(img, rx, ry + 3, lit(leather[3], "right"))

    # -------------------------------------------------------------- arms --
    for key in ("right_arm", "left_arm"):
        u, v, w, h, d = UV[key]
        faces = box_faces(u, v, w, h, d)
        if brute:
            for face, (x, y, fw, fh) in faces.items():
                woven(img, x, y, fw, fh, skin, rng, face, base_idx=2)
            if key == "right_arm":
                # The one armoured limb: a lashed iron vambrace, matching
                # the torso plate's side. Never on both arms.
                x, y, fw, fh = faces["front"]
                for j in range(fh - 6, fh - 1):
                    for i in range(fw):
                        idx = 1 if (i + j) % 2 else 0
                        put(img, x + i, y + j, lit(iron[idx], "front"))
                put(img, x, y + fh - 6, lit(leather[3], "front"))
            else:
                # Bare, just a cord wrap at the wrist -- the shared
                # material culture, not armour.
                x, y, fw, fh = faces["front"]
                for i in range(fw):
                    put(img, x + i, y + fh - 1, lit(leather[2], "front"))
        else:
            for face, (x, y, fw, fh) in faces.items():
                woven(img, x, y, fw, fh, cloth, rng, face, base_idx=2)
                # Bare forearms: raiders are not armoured, they are quick.
                for i in range(fw):
                    for j in range(fh - 4, fh):
                        put(img, x + i, y + j, lit(skin[2], face))

    # -------------------------------------------------------------- legs --
    for key in ("right_leg", "left_leg"):
        u, v, w, h, d = UV[key]
        faces = box_faces(u, v, w, h, d)
        base = skin if brute else cloth
        base_idx = 2 if brute else 1
        for face, (x, y, fw, fh) in faces.items():
            woven(img, x, y, fw, fh, base, rng, face, base_idx=base_idx)
            for i in range(fw):
                put(img, x + i, y + fh - 1, lit(leather[0], face))
                put(img, x + i, y + fh - 2, lit(leather[2], face))

    # ------------------------------------------------- pauldron and helm --
    # Captain-only mesh (RaiderModel: pauldron.visible = helm.visible =
    # captain, both variants share the shape) -- the captain's read at a
    # glance. Painted differently per build even though the box is shared:
    # a skirmisher captain's kit is fitted and clean, a brute captain's is
    # crude and dark, because it was taken and lashed on, not forged for him.
    u, v, w, h, d = UV["pauldron"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        for j in range(fh):
            for i in range(fw):
                if brute:
                    idx = 0 if j == fh - 1 else (3 if (i + j) % 4 == 0 else 1)
                else:
                    idx = 4 if j == 0 else (1 if j == fh - 1 else 3)
                put(img, x + i, y + j, lit(iron[idx], face))
        if brute and face in ("front", "back", "right", "left"):
            # Rope lashing along the bottom rim -- crude, not fitted.
            for i in range(0, fw, 3):
                put(img, x + i, y + fh - 1, lit(leather[3], face))
    if brute:
        # Iron still needs its one stop-4 catchlight (top-left-ish) or the
        # whole crude plate reads as a dark smudge at distance -- crude
        # does not mean unlit, it means the ONLY lit pixel instead of a
        # whole polished row.
        fx, fy, _, _ = box_faces(*UV["pauldron"])["front"]
        put(img, fx + 1, fy, lit(iron[4], "front"))
    u, v, w, h, d = UV["helm"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        for j in range(fh):
            for i in range(fw):
                if brute:
                    idx = 3 if (i * 3 + j) % 5 == 0 else (1 if j == fh - 1 else 2)
                else:
                    idx = 4 if j == 0 else 2
                put(img, x + i, y + j, lit(iron[idx], face))
    x, y, fw, fh = faces["front"]
    for i in range(fw):
        put(img, x + i, y + fh - 1, lit(accent[2], "front"))
    if brute:
        # A trophy fang mounted on the brow -- crude, taken, not forged in.
        put(img, x + fw // 2, y, lit(bone[4], "front"))
        # And iron's own stop-4 catchlight, same rule as the pauldron above.
        put(img, x + 1, y, lit(iron[4], "front"))

    if marked:
        # A proven leader's mark: a bright band across the pauldron rim, on
        # every face so it reads from any angle -- distinct from the plain
        # captain's iron trim without changing the silhouette RaiderModel
        # already reads at a distance.
        u, v, w, h, d = UV["pauldron"]
        faces = box_faces(u, v, w, h, d)
        for face, (x, y, fw, fh) in faces.items():
            for i in range(fw):
                put(img, x + i, y, lit(accent[4], face))
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    variants = (
        ("SKIRMISHER", False, False, "raider.png"),
        ("SKIRMISHER", True, False, "raider_captain.png"),
        ("SKIRMISHER", True, True, "raider_captain_marked.png"),
        ("BRUTE", False, False, "raider_brute.png"),
        ("BRUTE", True, False, "raider_brute_captain.png"),
        ("BRUTE", True, True, "raider_brute_captain_marked.png"),
    )
    for variant, captain, marked, name in variants:
        img = build(variant, captain, marked)
        path = os.path.normpath(os.path.join(OUT, name))
        img.save(path)
        print("  wrote %s (%dx%d)" % (path, img.width, img.height))
    print("raider skins done")


if __name__ == "__main__":
    main()
