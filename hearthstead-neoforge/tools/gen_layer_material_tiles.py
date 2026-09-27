"""Original 16x16 material tiles for render layers that used vanilla block textures
(owner rule: everything ours). Bright, neutral-ish bases so the layers' existing
multiplicative tints keep their look. Deterministic (seeded).
    python tools/gen_layer_material_tiles.py <out_dir>"""
import random, sys, os
from PIL import Image
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
def clamp(v): return max(0, min(255, int(v)))
def save(name, px):
    im = Image.new("RGBA", (16, 16))
    for (x, y), c in px.items(): im.putpixel((x, y), (*[clamp(v) for v in c], 255))
    im.save(os.path.join(out, name + ".png")); print("wrote", name)

r = random.Random(1709)
# wicker: over-under straw weave, 4 px bands
px = {}
for y in range(16):
    for x in range(16):
        cell = ((x // 4) + (y // 4)) % 2
        along = (y % 4) if cell else (x % 4)
        base = (212, 178, 92) if cell else (196, 160, 76)
        shade = [-26, 6, 10, -14][along]
        n = r.randint(-8, 8)
        px[(x, y)] = tuple(c + shade + n for c in base)
save("wicker", px)

# stripped_log: pale wood, long vertical grain streaks
px = {}
streak = [r.randint(-14, 10) for _ in range(16)]
for y in range(16):
    for x in range(16):
        n = streak[x] + r.randint(-5, 5) + (-10 if (x + y * 3) % 11 == 0 else 0)
        px[(x, y)] = (196 + n, 160 + n, 104 + n)
save("stripped_log", px)

# barrel_stave: four staves with dark seams, two iron-grey hoops
px = {}
for y in range(16):
    for x in range(16):
        n = r.randint(-7, 7)
        c = (122 + n, 90 + n, 54 + n)
        if x % 4 == 0: c = (84 + n, 60 + n, 36 + n)
        if y in (2, 3, 12, 13): c = (92 + n, 92 + n, 96 + n) if y in (2, 12) else (70 + n, 70 + n, 74 + n)
        px[(x, y)] = c
save("barrel_stave", px)

# brass: warm polished metal with a light bevel
px = {}
for y in range(16):
    for x in range(16):
        n = r.randint(-9, 9)
        edge = 22 if (x == 0 or y == 0) else (-28 if (x == 15 or y == 15) else 0)
        glint = 18 if (x + y) in (5, 6, 17) else 0
        px[(x, y)] = (238 + n + edge + glint, 204 + n + edge + glint, 96 + n + edge // 2)
save("brass", px)
