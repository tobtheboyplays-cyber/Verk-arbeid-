"""Fisher's Nets float texture (original art, PIL). Writes
src/main/resources/assets/hearthstead/textures/block/net_buoy.png (32x32).

Layout (px): cork side 0-8 x 0-8, cork top 8-16 x 0-8, stick 16-18 x 0-14,
pennant 20-28 x 0-6, net mesh 0-16 x 16-32. Model: models/block/net_buoy.json.
"""
import os
import random
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "main", "resources", "assets", "hearthstead", "textures", "block",
                   "net_buoy.png")
rnd = random.Random(7)
img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
px = img.load()


def jitter(c, n=10):
    return tuple(max(0, min(255, v + rnd.randint(-n, n))) for v in c[:3]) + (255,)


CORK = (196, 160, 112)
CORK_DARK = (150, 116, 76)
RED = (158, 52, 40)
RED_DARK = (118, 36, 30)
WOOD = (104, 74, 46)
CLOTH = (214, 170, 72)
CLOTH_DARK = (170, 118, 44)
TWINE = (128, 104, 70)

# cork side: red paint on the upper half, pitted cork below, a dark waterline
for y in range(8):
    for x in range(8):
        if y < 3:
            c = RED if (x + y) % 5 else RED_DARK
        elif y == 3:
            c = (228, 214, 186)          # pale painted band
        else:
            c = CORK if rnd.random() > 0.18 else CORK_DARK
        if y == 7:
            c = CORK_DARK
        px[x, y] = jitter(c, 8)
# cork top: red disc with the stick socket
for y in range(8):
    for x in range(8, 16):
        d = ((x - 11.5) ** 2 + (y - 3.5) ** 2) ** 0.5
        c = (60, 40, 28) if d < 1.2 else RED if d < 3.2 else RED_DARK
        px[x, y] = jitter(c, 6)
# stick: wood with a darker grain line
for y in range(14):
    for x in range(16, 18):
        px[x, y] = jitter(WOOD if (x + y // 3) % 2 else (86, 60, 38), 6)
# pennant: ochre cloth, one dark stripe, swallowtail notch at the fly end
for y in range(6):
    for x in range(20, 28):
        if x >= 26 and 2 <= y <= 3:
            continue
        c = CLOTH_DARK if y in (2, 3) and x < 24 else CLOTH
        px[x, y] = jitter(c, 10)
# net mesh: knotted twine every 4 px, open cells transparent
for y in range(16, 32):
    for x in range(16):
        on = (x % 4 == 0) or ((y - 16) % 4 == 0)
        if on:
            knot = (x % 4 == 0) and ((y - 16) % 4 == 0)
            px[x, y] = jitter((96, 76, 50) if knot else TWINE, 8)
img.save(os.path.abspath(OUT))
print("wrote", os.path.abspath(OUT))
