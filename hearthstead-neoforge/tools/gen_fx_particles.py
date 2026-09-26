"""Bannerhold FX particle sprites (particles & juice lane, 26 Sep).

Draws the small pixel-art sprite sheets for the mod's own particle types into
assets/hearthstead/textures/particle/ and writes the matching particle
definitions into assets/hearthstead/particles/. 8x8 frames, vanilla particle
scale. Tintable sprites (sparkle, mote, firefly, spark, puff, confetti) are
drawn in warm greys/whites so the client can colour them per effect; sprites
with their own material (ember, wood chip, coin) are drawn in the mod
palette: aged gold, brass, linen, oak.

Run: python tools/gen_fx_particles.py
"""
import json
import math
import os

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "hearthstead")
TEX = os.path.join(ROOT, "textures", "particle")
DEF = os.path.join(ROOT, "particles")

# Palette (UI brief: muted naturals, brass/aged gold sparingly).
WHITE = (255, 255, 255)
CREAM = (250, 244, 228)
LINEN = (232, 220, 196)
WARM_GREY = (200, 192, 176)
GOLD_HI = (255, 236, 160)
GOLD = (242, 200, 92)
BRASS = (201, 162, 39)
BRASS_DK = (150, 112, 30)
OAK_HI = (214, 176, 122)
OAK = (176, 136, 84)
OAK_DK = (122, 88, 50)
EMBER_CORE = (255, 246, 196)
EMBER_MID = (255, 190, 80)
EMBER_EDGE = (226, 96, 32)


def img():
    return Image.new("RGBA", (8, 8), (0, 0, 0, 0))


def put(im, x, y, rgb, a=255):
    if 0 <= x < 8 and 0 <= y < 8:
        im.putpixel((x, y), (rgb[0], rgb[1], rgb[2], a))


def save(im, name):
    im.save(os.path.join(TEX, name + ".png"))


def sparkle():
    # Four-point star that twinkles down over its life: big, mid, small, pin.
    frames = []
    # frame 0: long arms + diagonal glints
    im = img()
    for d in range(1, 4):
        a = [230, 170, 90][d - 1]
        c = [CREAM, LINEN, WARM_GREY][d - 1]
        for dx, dy in ((d, 0), (-d, 0), (0, d), (0, -d)):
            put(im, 3 + dx, 3 + dy, c, a)
    for dx, dy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
        put(im, 3 + dx, 3 + dy, WARM_GREY, 110)
    put(im, 3, 3, WHITE)
    frames.append(im)
    # frame 1
    im = img()
    for d in range(1, 3):
        a = [220, 130][d - 1]
        c = [CREAM, LINEN][d - 1]
        for dx, dy in ((d, 0), (-d, 0), (0, d), (0, -d)):
            put(im, 3 + dx, 3 + dy, c, a)
    put(im, 3, 3, WHITE)
    frames.append(im)
    # frame 2: small plus
    im = img()
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        put(im, 3 + dx, 3 + dy, LINEN, 150)
    put(im, 3, 3, WHITE)
    frames.append(im)
    # frame 3: pin point
    im = img()
    put(im, 3, 3, WHITE, 230)
    frames.append(im)
    for i, f in enumerate(frames):
        save(f, "sparkle_%d" % i)
    return ["sparkle_%d" % i for i in range(len(frames))]


def soft_dot(radius, core=WHITE, edge=LINEN):
    im = img()
    cx = cy = 3.5
    for y in range(8):
        for x in range(8):
            d = math.hypot(x - cx, y - cy)
            if d <= radius:
                t = d / max(radius, 0.01)
                a = int(255 * (1.0 - t * t) ** 1.2)
                c = core if t < 0.45 else edge
                if a > 8:
                    put(im, x, y, c, a)
    return im


def motes():
    names = []
    for i, r in enumerate((3.2, 2.4, 1.6)):
        save(soft_dot(r), "mote_%d" % i)
        names.append("mote_%d" % i)
    return names


def firefly():
    names = []
    # a bright one-pixel-ish core with a soft halo; two halo sizes for the pulse
    for i, r in enumerate((3.4, 2.4)):
        im = soft_dot(r, core=WHITE, edge=WARM_GREY)
        # dim the halo so the core reads as the insect
        px = im.load()
        for y in range(8):
            for x in range(8):
                p = px[x, y]
                if p[3] and math.hypot(x - 3.5, y - 3.5) > 1.2:
                    px[x, y] = (p[0], p[1], p[2], int(p[3] * 0.45))
        for x, y in ((3, 3), (4, 3), (3, 4), (4, 4)):
            put(im, x, y, WHITE, 255)
        save(im, "firefly_%d" % i)
        names.append("firefly_%d" % i)
    return names


def ember():
    names = []
    specs = [
        [((3, 3), EMBER_CORE), ((4, 3), EMBER_MID), ((3, 4), EMBER_MID), ((4, 4), EMBER_EDGE),
         ((2, 3), EMBER_EDGE), ((3, 2), EMBER_EDGE)],
        [((3, 3), EMBER_MID), ((4, 3), EMBER_EDGE), ((3, 4), EMBER_EDGE), ((4, 4), EMBER_EDGE)],
        [((3, 3), EMBER_MID), ((4, 4), EMBER_EDGE)],
    ]
    for i, spec in enumerate(specs):
        im = img()
        for (x, y), c in spec:
            put(im, x, y, c)
        save(im, "ember_%d" % i)
        names.append("ember_%d" % i)
    return names


def spark():
    names = []
    im = img()
    put(im, 3, 3, WHITE)
    put(im, 4, 3, CREAM, 220)
    put(im, 2, 3, LINEN, 150)
    put(im, 3, 4, LINEN, 120)
    save(im, "anvil_spark_0")
    im = img()
    put(im, 3, 3, WHITE)
    put(im, 4, 4, LINEN, 120)
    save(im, "anvil_spark_1")
    names += ["anvil_spark_0", "anvil_spark_1"]
    return names


def puffs():
    names = []
    blobs = [
        [(3.5, 3.5, 3.4)],
        [(3.0, 3.8, 2.6), (4.8, 3.0, 2.0)],
        [(3.2, 3.2, 2.2), (4.6, 4.4, 2.0), (2.6, 4.8, 1.4)],
        [(3.5, 3.5, 2.0)],
    ]
    for i, blob in enumerate(blobs):
        im = img()
        for y in range(8):
            for x in range(8):
                a = 0.0
                for bx, by, r in blob:
                    d = math.hypot(x - bx, y - by)
                    if d < r:
                        a = max(a, (1.0 - d / r) ** 0.9)
                if a > 0.05:
                    c = WHITE if a > 0.6 else CREAM
                    put(im, x, y, c, int(210 * a))
        save(im, "puff_%d" % i)
        names.append("puff_%d" % i)
    return names


def chips():
    shapes = [
        [((2, 3), OAK_HI), ((3, 3), OAK), ((4, 3), OAK), ((5, 4), OAK_DK), ((3, 4), OAK_DK)],
        [((3, 2), OAK_HI), ((3, 3), OAK), ((4, 4), OAK), ((4, 5), OAK_DK)],
        [((2, 4), OAK), ((3, 4), OAK_HI), ((4, 4), OAK_HI), ((5, 4), OAK), ((3, 5), OAK_DK), ((4, 5), OAK_DK)],
        # a curled shaving
        [((2, 3), OAK_HI), ((3, 2), OAK_HI), ((4, 2), OAK), ((5, 3), OAK), ((5, 4), OAK_DK), ((4, 5), OAK_DK)],
    ]
    names = []
    for i, s in enumerate(shapes):
        im = img()
        for (x, y), c in s:
            put(im, x, y, c)
        save(im, "wood_chip_%d" % i)
        names.append("wood_chip_%d" % i)
    return names


def coins():
    names = []
    # full face, three-quarter, edge, three-quarter (mirrored highlight)
    widths = [(2.9, False), (1.9, False), (0.6, False), (1.9, True)]
    for i, (hw, mirror) in enumerate(widths):
        im = img()
        cx, cy, hh = 3.5, 3.5, 2.9
        for y in range(8):
            for x in range(8):
                nx = (x - cx) / max(hw, 0.5)
                ny = (y - cy) / hh
                d = nx * nx + ny * ny
                if d <= 1.0:
                    if d > 0.62:
                        c = BRASS_DK
                    elif (x - cx) * (-1 if mirror else 1) < 0 and y < cy:
                        c = GOLD_HI
                    else:
                        c = GOLD
                    put(im, x, y, c)
        if hw < 1.0:
            for y in range(1, 7):
                put(im, 3, y, BRASS)
                put(im, 4, y, BRASS_DK)
            put(im, 3, 1, GOLD_HI)
        save(im, "coin_%d" % i)
        names.append("coin_%d" % i)
    return names


def confetti():
    shapes = [
        [(3, 3), (4, 3), (3, 4), (4, 4)],
        [(2, 3), (3, 3), (4, 3), (5, 3)],
        [(3, 2), (3, 3), (4, 4), (4, 5)],
        [(3, 3), (4, 3), (5, 4)],
    ]
    names = []
    for i, s in enumerate(shapes):
        im = img()
        for j, (x, y) in enumerate(s):
            put(im, x, y, WHITE if j % 2 == 0 else LINEN)
        save(im, "confetti_%d" % i)
        names.append("confetti_%d" % i)
    return names


def write_def(name, textures):
    with open(os.path.join(DEF, name + ".json"), "w", newline="\n") as f:
        json.dump({"textures": ["hearthstead:" + t for t in textures]}, f, indent=2)
        f.write("\n")


def main():
    os.makedirs(TEX, exist_ok=True)
    os.makedirs(DEF, exist_ok=True)
    sp = sparkle()
    mo = motes()
    ff = firefly()
    em = ember()
    sk = spark()
    pf = puffs()
    ch = chips()
    co = coins()
    cf = confetti()
    # One definition per registered particle type (registry/ModParticles).
    write_def("sparkle", sp)
    write_def("mote", mo)
    write_def("ember", em)
    write_def("firefly", ff)
    write_def("anvil_spark", sk)
    write_def("flour_puff", pf)
    write_def("steam", pf)
    write_def("wood_chip", ch)
    write_def("coin", co)
    write_def("confetti", cf)
    write_def("dust_mote", mo)
    print("wrote", len(sp + mo + ff + em + sk + pf + ch + co + cf), "sprites")


if __name__ == "__main__":
    main()
