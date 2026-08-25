#!/usr/bin/env python3
"""Raider skin: a 64x64 sheet mirroring RaiderModel's UV table.

Deliberately NOT a recoloured settler. The research on both reference mods
says their raiders read as undifferentiated HP sponges -- MineColonies
players report that "chief raiders don't even stand out from regular
raiders" -- so the raider silhouette is lean and hooded where the settler is
broad and cloaked, and the captain is a distinct read at a glance rather
than a health bar you have to hit to discover.

UV table (must mirror RaiderModel.createBodyLayer):
  head (0,0) 8x8x8      hood (32,0) 8x8x8     torso (0,16) 8x12x4
  right_arm (32,16) / left_arm (48,16) 3x12x3
  right_leg (0,32) / left_leg (16,32) 4x12x4
  pauldron (32,32) 10x3x5   helm (0,48) 9x3x9

Explicit integer seeds only -- never Python's salted hash() -- so two runs
emit identical bytes (KF-007's rule).
"""
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from texlib import (box_faces, lit, new_image, put, ramp, shade, woven)  # noqa: E402

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


def build(captain):
    img = new_image(64, 64)
    rng = random.Random(seed_for("raider", captain))
    skin = ramp("skin_deep" if captain else "skin_tan")
    cloth = ramp("charcoal")
    leather = ramp("leather")
    iron = ramp("iron_forged")
    accent = ramp("crimson" if captain else "ember")

    # head: gaunt, with a shadowed brow so the face reads from a distance
    u, v, w, h, d = UV["head"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        for j in range(fh):
            for i in range(fw):
                put(img, x + i, y + j, lit(skin[2 + (i + j) % 2], face))
    x, y, fw, fh = faces["front"]
    for i in range(fw):
        put(img, x + i, y + 2, lit(shade(skin[1], 0.65), "front"))
    put(img, x + 2, y + 3, lit(accent[3], "front"))
    put(img, x + fw - 3, y + 3, lit(accent[3], "front"))
    # a scar, on the captain only: an identity you can see before the fight
    if captain:
        for j in range(1, 5):
            put(img, x + 5, y + j, lit(shade(skin[0], 0.8), "front"))

    # hood: deep, sits low over the brow
    u, v, w, h, d = UV["hood"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        woven(img, x, y, fw, fh, cloth, rng, face, base_idx=1)
        if face in ("front", "back", "right", "left"):
            for i in range(fw):
                put(img, x + i, y + fh - 1, lit(cloth[0], face))
    # cut the hood open over the face so the head shows through
    x, y, fw, fh = faces["front"]
    for j in range(2, 6):
        for i in range(1, fw - 1):
            img.putpixel((x + i, y + j), (0, 0, 0, 0))

    # torso: narrow, wrapped, with a belt of cord rather than a buckle
    u, v, w, h, d = UV["torso"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        woven(img, x, y, fw, fh, cloth, rng, face, base_idx=2)
        if face in ("front", "back", "right", "left"):
            for i in range(fw):
                put(img, x + i, y + fh - 4, lit(leather[1], face))
                put(img, x + i, y + fh - 3, lit(leather[3], face))
    x, y, fw, fh = faces["front"]
    for j in range(1, fh - 5):
        put(img, x + fw // 2, y + j, lit(accent[2], "front"))

    for key in ("right_arm", "left_arm"):
        u, v, w, h, d = UV[key]
        faces = box_faces(u, v, w, h, d)
        for face, (x, y, fw, fh) in faces.items():
            woven(img, x, y, fw, fh, cloth, rng, face, base_idx=2)
            for i in range(fw):
                # bare forearms: raiders are not armoured, they are quick
                for j in range(fh - 4, fh):
                    put(img, x + i, y + j, lit(skin[2], face))

    for key in ("right_leg", "left_leg"):
        u, v, w, h, d = UV[key]
        faces = box_faces(u, v, w, h, d)
        for face, (x, y, fw, fh) in faces.items():
            woven(img, x, y, fw, fh, cloth, rng, face, base_idx=1)
            for i in range(fw):
                put(img, x + i, y + fh - 1, lit(leather[0], face))
                put(img, x + i, y + fh - 2, lit(leather[2], face))

    # pauldron and helm: the captain's read at a glance
    u, v, w, h, d = UV["pauldron"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        for j in range(fh):
            for i in range(fw):
                idx = 4 if j == 0 else (1 if j == fh - 1 else 3)
                put(img, x + i, y + j, lit(iron[idx], face))
    u, v, w, h, d = UV["helm"]
    faces = box_faces(u, v, w, h, d)
    for face, (x, y, fw, fh) in faces.items():
        for j in range(fh):
            for i in range(fw):
                idx = 4 if j == 0 else 2
                put(img, x + i, y + j, lit(iron[idx], face))
    x, y, fw, fh = faces["front"]
    for i in range(fw):
        put(img, x + i, y + fh - 1, lit(accent[2], "front"))
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    for captain in (False, True):
        img = build(captain)
        name = "raider_captain.png" if captain else "raider.png"
        path = os.path.normpath(os.path.join(OUT, name))
        img.save(path)
        print("  wrote %s (%dx%d)" % (path, img.width, img.height))
    print("raider skins done")


if __name__ == "__main__":
    main()
