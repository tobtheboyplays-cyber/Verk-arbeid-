#!/usr/bin/env python3
"""Original 64x64 raider atlas family for the shared RaiderModel.

Owner-supplied Brute, Skirmisher and captain sheets guide materials/silhouette,
not extracted pixels. Uses the existing texlib and fixed seed convention.
Skirmisher wears red hood, cream sleeves and leather; Brute has exposed bearded
face, fur and iron; captains retain distinct iron/brass rank islands. The six
renderer paths and original UV islands are stable. Supplemental material patches
are fully opaque because several smaller geometry boxes reuse each rectangle.
"""
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from texlib import box_faces, hx, lit, new_image, put, ramp

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main",
                   "resources", "assets", "hearthstead", "textures", "entity", "raider")
UV = {
    "head": (0, 0, 8, 8, 8), "hood": (32, 0, 8, 8, 8),
    "torso": (0, 16, 8, 12, 4),
    "right_arm": (32, 16, 3, 12, 3), "left_arm": (48, 16, 3, 12, 3),
    "right_leg": (0, 32, 4, 12, 4), "left_leg": (16, 32, 4, 12, 4),
    "pauldron": (32, 32, 10, 3, 5), "helm": (0, 48, 9, 3, 9),
}
PATCHES = {
    "fur": (32, 40, 14, 6), "cloth": (46, 40, 12, 5),
    "wood": (36, 48, 4, 11), "iron": (40, 48, 16, 8),
    "blade": (56, 48, 4, 7), "guard": (56, 55, 8, 2),
    "beard": (0, 60, 12, 4), "boot": (40, 57, 14, 5),
}
VARIANTS = (
    ("SKIRMISHER", False, False, "raider.png"),
    ("SKIRMISHER", True, False, "raider_captain.png"),
    ("SKIRMISHER", True, True, "raider_captain_marked.png"),
    ("BRUTE", False, False, "raider_brute.png"),
    ("BRUTE", True, False, "raider_brute_captain.png"),
    ("BRUTE", True, True, "raider_brute_captain_marked.png"),
)
SEED = 0x5241_4944
RED = ("#332021", "#4b2928", "#653631", "#814b40", "#a16952")
FUR = ("#3c3730", "#585044", "#776c59", "#9a8a70", "#baaa8d")
IRON = ("#1d2023", "#353b3d", "#535b5a", "#818b87", "#b5bcb3")
LEATHER = ("#29221b", "#413326", "#5c4833", "#7c6143", "#a38359")
BRUTE_LEATHER = ("#191817", "#292421", "#39312c", "#51453c", "#705f50")
BRUTE_OXBLOOD = ("#2b1518", "#432023", "#612b2d", "#803b3a", "#a5534d")
CREAM = ("#635c50", "#817766", "#a79b83", "#c3b69b", "#ded3b7")
RED, FUR, IRON, LEATHER, BRUTE_LEATHER, BRUTE_OXBLOOD, CREAM = tuple(
    tuple(hx(color) for color in palette)
    for palette in (RED, FUR, IRON, LEATHER, BRUTE_LEATHER, BRUTE_OXBLOOD, CREAM))


def seed_for(*parts):
    value = SEED
    for part in parts:
        for byte in str(part).encode("utf-8"):
            value = (value * 131 + byte) & 0xFFFFFFFF
    return value


def paint(img, rect, colors, rng, face="front", base=2):
    """Large calm colour clusters, with sparse wear rather than checker noise."""
    x, y, width, height = rect
    for cy in range(0, height, 2):
        for cx in range(0, width, 2):
            index = max(0, min(4, base + rng.choice((0, 0, 0, 0, -1, 1))))
            for j in range(cy, min(cy + 2, height)):
                for i in range(cx, min(cx + 2, width)):
                    put(img, x + i, y + j, lit(colors[index], face))


def overlay_brute_textile(img):
    """V2 material structure for the ordinary Brute's fixed body UV islands.

    Every coordinate is derived from the existing `box_faces` mapping. This
    does not move UV islands, add geometry, or touch captain identity maps.
    """
    torso_faces = box_faces(*UV["torso"])
    for face in ("front", "back"):
        x, y, width, height = torso_faces[face]
        for j in range(0, 9):
            for i in range(width):
                # A narrow inset vest panel and dark outer edges prevent a
                # broad, padded torso read on the enlarged model.
                if i in (0, width - 1):
                    put(img, x + i, y + j, lit(BRUTE_LEATHER[0], face))
                elif i in (1, width - 2):
                    put(img, x + i, y + j, lit(BRUTE_LEATHER[3], face))
        if face == "front":
            # Fur collar, a diagonal strap with sparse stitch highlights,
            # and a small iron buckle: material direction, not a wide band.
            for i in range(2, 6):
                put(img, x + i, y, lit(FUR[3 if i % 2 else 2], face))
            for j in range(1, 8):
                strap_x = min(5, 1 + j // 2)
                put(img, x + strap_x, y + j, lit(BRUTE_LEATHER[4], face))
                if j % 2 == 0 and strap_x + 1 < width - 1:
                    put(img, x + strap_x + 1, y + j, lit(FUR[2], face))
            for i in (3, 4):
                put(img, x + i, y + 8, lit(IRON[3], face))
        else:
            # A short fur edge at the shoulders and a central rear strap.
            for j in range(0, 2):
                for i in range(1, width - 1):
                    if (i + j) % 3:
                        put(img, x + i, y + j, lit(FUR[2 + (i % 2)], face))
            for j in range(2, 8):
                put(img, x + 3, y + j, lit(BRUTE_LEATHER[4], face))
                put(img, x + 4, y + j, lit(BRUTE_LEATHER[2], face))
        # One-pixel sash plus a partial lower fold keeps red readable without
        # turning it into a three-pixel horizontal padded band.
        for i in range(1, width - 1):
            put(img, x + i, y + 9, lit(BRUTE_OXBLOOD[2], face))
        for i in range(2, width - 2):
            put(img, x + i, y + 10, lit(BRUTE_OXBLOOD[1], face))

    for key in ("right_arm", "left_arm"):
        for face, (x, y, width, height) in box_faces(*UV[key]).items():
            if face in ("top", "bottom"):
                continue
            # Narrow leather harness seam and fur cuff; existing lower iron
            # bracer remains authoritative and readable.
            for j in range(1, 5):
                seam_x = 0 if (j % 2 == 0) else width - 1
                put(img, x + seam_x, y + j, lit(BRUTE_LEATHER[3], face))
            for i in range(width):
                if i != width // 2:
                    put(img, x + i, y + 5, lit(FUR[2 + (i % 2)], face))

    for key in ("right_leg", "left_leg"):
        for face, (x, y, width, height) in box_faces(*UV[key]).items():
            if face in ("top", "bottom"):
                continue
            # Break the old broad red upper-leg wrap into a single sash edge,
            # stitched leather seam, and dark panel below it.
            for i in range(width):
                put(img, x + i, y, lit(BRUTE_OXBLOOD[2 if i in (1, 2) else 1], face))
            for j in (1, 2):
                for i in range(width):
                    put(img, x + i, y + j, lit(BRUTE_LEATHER[1 + ((i + j) % 2)], face))
            for j in range(3, 8):
                put(img, x + (0 if j % 2 else width - 1), y + j, lit(BRUTE_LEATHER[3], face))


def build(variant, captain, marked=False):
    img = new_image(64, 64)
    brute = variant == "BRUTE"
    # New material structure is intentionally grunt-only until a game view
    # proves the Brute captain needs a coordinated treatment.
    brute_grunt = brute and not captain
    # Marked captain keeps identical underlying material clusters.
    rng = random.Random(seed_for("raider", variant, captain))
    skin = ramp("skin_tan" if brute else "skin")
    hair = FUR if captain else ramp("hair_brn")
    rank = ramp("brass") if marked else RED
    pants = ramp("charcoal")

    for face, rect in box_faces(*UV["head"]).items():
        paint(img, rect, skin, rng, face)
        x, y, width, height = rect
        if face in ("top", "back"):
            paint(img, rect, hair, rng, face, 1)
        elif face in ("left", "right"):
            for j in range(height):
                for i in range(width):
                    if j < 2 or i >= width - 2 or j >= 5:
                        put(img, x + i, y + j, lit(hair[1 if j < 2 else 2], face))
    x, y, width, height = box_faces(*UV["head"])["front"]
    for i in range(width):
        put(img, x + i, y, hair[1])
        if i in (0, 7):
            put(img, x + i, y + 1, hair[1])
    for j in range(5, 8):
        for i in range(1 if j == 7 else 0, 7 if j == 7 else 8):
            put(img, x + i, y + j, hair[2 if (i // 2 + j) % 3 else 1])
    for i in (1, 2, 5, 6):
        put(img, x + i, y + (1 if i in (1, 6) else 2), hx("#201c19"))
        put(img, x + i, y + 3, hx("#eee4cd" if i in (1, 6) else "#302c22"))
    for i in (3, 4):
        put(img, x + i, y + 2, hx("#30251f"))
    put(img, x + 3, y + 4, skin[3])
    put(img, x + 4, y + 4, skin[2])
    put(img, x + 3, y + 6, hx("#342522"))
    put(img, x + 4, y + 6, hx("#342522"))
    if brute or captain:
        for j in (3, 4, 5):
            put(img, x + 7, y + j, hx("#985d49"))
    if marked:
        for i in (1, 6):
            put(img, x + i, y + 1, rank[4])
            put(img, x + i, y + 4, rank[3])

    if not brute:
        for face, rect in box_faces(*UV["hood"]).items():
            paint(img, rect, RED, rng, face)
            x, y, width, height = rect
            if face == "front":
                for j in range(4, height):
                    for i in range(1, width - 1):
                        put(img, x + i, y + j, lit(LEATHER[1 if j == 4 else 2], face))
                for i in range(1, width - 1):
                    img.putpixel((x + i, y + 3), (0, 0, 0, 0))

    torso_leather = BRUTE_LEATHER if brute_grunt else LEATHER
    sash = BRUTE_OXBLOOD if brute_grunt else RED
    for face, rect in box_faces(*UV["torso"]).items():
        paint(img, rect, torso_leather, rng, face, 1)
        x, y, width, height = rect
        for j in range(height):
            for i in range(width):
                color = None
                if face in ("front", "back", "left", "right"):
                    if j >= 9:
                        color = sash[1 if i in (0, width - 1) else 2]
                    if j in (7, 8):
                        color = torso_leather[1 if j == 7 else 3]
                    if not brute and not captain and j < 6 and i in (0, width - 1):
                        color = CREAM[2]
                    if brute and face == "back" and j < 5:
                        color = FUR[2 + ((i // 2 + j // 2) % 2)]
                    if face in ("front", "back") and j < 7 and i == (j // 2 + (1 if face == "front" else 2)):
                        color = torso_leather[4]
                    if face == "front" and j == 0 and 2 <= i <= 5:
                        color = RED[2] if captain else skin[2]
                    if face == "front" and j in (7, 8) and i in (3, 4):
                        color = rank[4] if marked else IRON[3]
                if color is not None:
                    put(img, x + i, y + j, lit(color, face))
        if captain and face == "front":
            put(img, x + width // 2, y + 2, lit(rank[3], face))
            put(img, x + width // 2, y + 3, lit(ramp("bone")[3], face))

    for key in ("right_arm", "left_arm"):
        for face, rect in box_faces(*UV[key]).items():
            paint(img, rect, skin if brute else RED if captain else CREAM, rng, face)
            x, y, width, height = rect
            if face in ("top", "bottom"):
                continue
            for j in range(height):
                for i in range(width):
                    color = None
                    if j in (6, 7) and not captain:
                        color = skin[2]
                    if 8 <= j <= 10:
                        color = IRON[2] if brute or captain else LEATHER[1 + (j % 2)]
                    if j == 11:
                        color = skin[2]
                    if j in (5, 10):
                        color = LEATHER[3]
                    if color is not None:
                        put(img, x + i, y + j, lit(color, face))

    for key in ("right_leg", "left_leg"):
        for face, rect in box_faces(*UV[key]).items():
            paint(img, rect, pants, rng, face, 1)
            x, y, width, height = rect
            if face in ("top", "bottom"):
                continue
            for j in range(height):
                for i in range(width):
                    color = None
                    if j < 3:
                        color = (BRUTE_OXBLOOD[1 if i in (0, width - 1) else 2]
                            if brute_grunt else RED[1 if i in (0, width - 1) else 2])
                    if j >= 8:
                        color = LEATHER[1 if j == 11 else 2]
                    if j in (5, 9):
                        color = LEATHER[3]
                    if brute and j in (6, 7) and face == "front":
                        color = IRON[2]
                    if color is not None:
                        put(img, x + i, y + j, lit(color, face))

    if brute_grunt:
        overlay_brute_textile(img)

    for key in ("pauldron", "helm"):
        for face, rect in box_faces(*UV[key]).items():
            paint(img, rect, IRON, rng, face, 2)
            x, y, width, height = rect
            for i in range(width):
                put(img, x + i, y, lit(IRON[3], face))
                if face not in ("top", "bottom"):
                    put(img, x + i, y + height - 1, lit(rank[3] if marked else IRON[1], face))
            if width > 3:
                put(img, x + 1, y + 1, lit(IRON[4], face))
            if marked:
                for i in range(width):
                    put(img, x + i, y, lit(rank[4], face))

    materials = {"fur": FUR, "cloth": RED, "wood": LEATHER,
                 "iron": IRON, "blade": IRON, "guard": IRON,
                 "beard": hair, "boot": LEATHER}
    for name, rect in PATCHES.items():
        paint(img, rect, materials[name], rng, base=2)
        x, y, width, height = rect
        for j in range(height):
            for i in range(width):
                if name == "fur" and (i + j // 2) % 4 == 0:
                    put(img, x + i, y + j, FUR[3 if j % 3 else 1])
                elif name == "wood" and i % 2 == 0:
                    put(img, x + i, y + j, LEATHER[1 if j % 5 else 3])
                elif name in ("iron", "blade", "guard") and j == 0:
                    put(img, x + i, y + j, IRON[4 if name == "blade" else 3])
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    for variant, captain, marked, name in VARIANTS:
        path = os.path.normpath(os.path.join(OUT, name))
        build(variant, captain, marked).save(path)
        print("  wrote %s (64x64)" % path)


if __name__ == "__main__":
    main()
