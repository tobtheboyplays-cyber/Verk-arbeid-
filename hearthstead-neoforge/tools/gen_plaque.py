#!/usr/bin/env python3
"""Building Plaque art: block faces, held items and the two dark GUI sheets.

Signature-piece resolution: block and item textures are 64x64, GUI sheets are
512x512 (2x the logical GUI grid).  The extra pixels buy real material detail
-- long oak grain and carved beads, hammer peen and domed rivets on the iron,
patina in the recesses of the aged brass, fibre and a deckle edge on the
parchment, a genuinely deep plan socket.  Everything stays hard-edged pixel
art: banded tones, no antialiasing, no smooth gradients.

Everything is generated deterministically (explicit integer seeds only -- never
Python's salted hash()), in the house light convention from texlib: a warm
top-left key light, so top faces are brightest, fronts neutral, sides dimmed,
and backs/bottoms darkest.

Material language (matches the owner's reference plaque):
    dark carved oak backboard -> hand-forged iron corner brackets with rivets
    -> restrained aged-brass inner frame -> parchment plan inside it
    -> a dark recessed socket when no plan is fitted.  Charcoal near-black UI
    insets, off-white caps, muted grey secondary text, emerald / heart-red
    status.

===========================================================================
 MATERIAL TEXTURES for the multi-element 3D block model  (all block/, 64x64)
===========================================================================
The composed faces (plaque_front_empty / _filled / _side / _back) remain for
the inventory item model and as a fallback. The six below are MATERIALS for
the real-depth block model, where each element shows several faces at
different depths, so a pre-composed front face cannot serve.

They carry MICRO-relief lit from the top left -- every grain line, dimple and
peen mark has its own lit and shadowed edge -- and deliberately NO macro
gradient, so any UV window the model takes reads correctly and Minecraft's own
per-face shading supplies the large-scale lighting.

  plaque_board.png       backboard front + back. Vertical long grain, ray
                         flecks, two age marks, faint chamfer at the edge.
  plaque_board_edge.png  backboard narrow edges. END grain: short strokes
                         ACROSS the thickness, jogging every 6 rows so no
                         line ever runs the full height. Grain runs vertical
                         here -- set "rotation": 90 on the left/right edges.
  plaque_brass.png       raised inner frame. 8px staggered planished dimples,
                         patina in the hollows, catch-light on upper-left rims
                         only. Seamless (16-periodic), so a 3px-wide lip still
                         carries a lit rim and a shaded pool.
  plaque_iron.png        corner brackets. Peen dashes along +X (set
                         "rotation": 90 on arms that run vertically), forge
                         scale, rivet crowns on a 32px pitch. Seamless.
  plaque_socket.png      recessed well, EMPTY. Near-black floor, banded cast
                         shadow from the top/left, four brass clip tongues.
  plaque_plan.png        recessed well, plan FITTED. Parchment over the whole
                         face, no frame -- the frame is real geometry now.

===========================================================================
 GUI COORDINATE CONTRACT  --  the Java screen code reads these rectangles
===========================================================================
Sheets are 512x512 = 2x the logical GUI grid, so every rectangle is listed
twice: RAW (pixels on the sheet, for the blit source) and LOGICAL (GUI units,
i.e. RAW / 2 -- what the screen layout code works in).  Blit at RAW and draw
at LOGICAL size, or set a 0.5 scale.

--- textures/gui/plaque_screen.png  (512x512) ------------------------------
                             RAW (u,v,w,h)          LOGICAL (u,v,w,h)
  PANEL                  (  0,   0, 352, 416)    (  0,   0, 176, 208)
        nine-slice border RAW 16 / LOGICAL 8; flat centre sample RAW (176,208)
        content area      RAW ( 16,  16, 320, 384) LOGICAL ( 8,  8, 160, 192)
  SLOT_FRAME             (356,   4,  40,  40)    (178,   2,  20,  20)
        Build Plan slot; the item sits at RAW +4,+4 (LOGICAL +2,+2)
  PORTRAIT_FRAME         (404,   4,  32,  32)    (202,   2,  16,  16)
        face drawn into inner RAW (2,2,28,28) / LOGICAL (1,1,14,14)
  X_NORMAL               (356,  48,  28,  28)    (178,  24,  14,  14)
  X_HOVER                (392,  48,  28,  28)    (196,  24,  14,  14)
  X_DISABLED             (428,  48,  28,  28)    (214,  24,  14,  14)
  ICON_HEART             (356,  84,  18,  18)    (178,  42,   9,   9)
  ICON_MOOD              (380,  84,  18,  18)    (190,  42,   9,   9)
  ICON_WARNING           (404,  84,  18,  18)    (202,  42,   9,   9)
  ICON_ACTIVE            (428,  84,  18,  18)    (214,  42,   9,   9)
  FOOTER_NORMAL          (356, 108, 144,  36)    (178,  54,  72,  18)
  FOOTER_HOVER           (356, 148, 144,  36)    (178,  74,  72,  18)
  DIVIDER_RULE           (356, 188, 152,   6)    (178,  94,  76,   3)
        tile or stretch horizontally to any width
  DIVIDER_DIAMOND        (356, 198,  18,  14)    (178,  99,   9,   7)
        blit centred over the rule
  ASSIGN_NORMAL          (356, 216, 152,  36)    (178, 108,  76,  18)
  ASSIGN_HOVER           (356, 256, 152,  36)    (178, 128,  76,  18)
  ASSIGN_PRESSED         (356, 296, 152,  36)    (178, 148,  76,  18)
  ASSIGN_DISABLED        (356, 336, 152,  36)    (178, 168,  76,  18)
        three-slice horizontally, cap RAW 20 / LOGICAL 10:
            left  [u,      u+20)   right [u+132, u+152)
            mid   [u+20,   u+132)  -- column-uniform, tiles seamlessly
        composes to the reference 150x18 LOGICAL button (300x36 RAW), or any
        width the layout needs
  ROW_NORMAL             (  0, 416, 300,  48)    (  0, 208, 150,  24)
  ROW_HOVER              (  0, 464, 300,  48)    (  0, 232, 150,  24)
        row content offsets, LOGICAL: portrait (3,4) 16x16 | name x=23 y=3
        | role x=23 y=13 | heart x=88 | mood x=112 | X button x=133

--- textures/gui/plaque_assign.png  (512x512) ------------------------------
                             RAW (u,v,w,h)          LOGICAL (u,v,w,h)
  PANEL                  (  0,   0, 300, 480)    (  0,   0, 150, 240)
        nine-slice border RAW 16 / LOGICAL 8; flat centre sample RAW (150,240)
  LIST_WELL              ( 20,  80, 260, 356)    ( 10,  40, 130, 178)
        recessed list area, inside PANEL
  CAND_NORMAL            (304,   4, 204,  40)    (152,   2, 102,  20)
  CAND_HOVER             (304,  48, 204,  40)    (152,  24, 102,  20)
  CAND_DISABLED          (304,  92, 204,  40)    (152,  46, 102,  20)
  SEARCH_FIELD           (304, 136, 204,  28)    (152,  68, 102,  14)
        text starts at RAW +8 / LOGICAL +4; brass ledger line near the base
  SCROLL_TRACK           (304, 168,  12, 280)    (152,  84,   6, 140)
        every row identical -- tile vertically to any height
  SCROLL_THUMB           (324, 168,  12,  48)    (162,  84,   6,  24)
        three-slice vertically, cap RAW 12 / LOGICAL 6
===========================================================================
"""
import math
import os
import random
import sys

sys.path.insert(0, os.path.dirname(__file__))
from texlib import ramp, shade, mix, new_image, fill, put, save

ASSETS = os.path.join(os.path.dirname(__file__), "..",
                      "src/main/resources/assets/hearthstead")

OAK = ramp("oak_carved")
IRON = ramp("iron_forged")
BRASS = ramp("brass")
COAL = ramp("charcoal")
BONE = ramp("bone")
EMER = ramp("emerald")
CRIM = ramp("crimson")
AMBER = ramp("amber")
PARCH = ramp("parchment")
INK = ramp("ink")
LEATHER = ramp("leather")

NEAR_BLACK = shade(COAL[0], 0.55)
# Warm lit lip for the lower/right wall of a recess. The charcoal ramp alone is
# too tight a range for a bevel to read against a charcoal ground.
LIP = (62, 59, 54, 255)
# Verdigris/soot that settles in the recesses of aged brass.
PATINA = (74, 72, 44, 255)

# Deterministic seeds -- explicit integer constants, one per texture.
SEED_FRONT_EMPTY = 4101
SEED_FRONT_FILLED = 4102
SEED_SIDE = 4103
SEED_BACK = 4104
SEED_ITEM_PLAQUE = 4105
SEED_ITEM_PLAN = 4106
SEED_SCREEN = 4107
SEED_ASSIGN = 4108
SEED_MAT_BOARD = 4109
SEED_MAT_EDGE = 4110
SEED_MAT_BRASS = 4111
SEED_MAT_IRON = 4112
SEED_MAT_SOCKET = 4113
SEED_MAT_PLAN = 4114


def cell_noise(i, j, m=31):
    """Deterministic 16x16-periodic hash. Periodic so material textures tile
    seamlessly at 64 (16 divides 64) whichever UV window the model takes."""
    a, b = i & 15, j & 15
    return (a * 13 + b * 29 + a * b * 7 + ((a ^ b) * 5)) % m


# ------------------------------------------------------------- primitives ---

def line_h(img, x0, x1, y, c):
    for x in range(x0, x1 + 1):
        put(img, x, y, c)


def line_v(img, x, y0, y1, c):
    for y in range(y0, y1 + 1):
        put(img, x, y, c)


def box(img, x0, y0, x1, y1, c):
    line_h(img, x0, x1, y0, c)
    line_h(img, x0, x1, y1, c)
    line_v(img, x0, y0, y1, c)
    line_v(img, x1, y0, y1, c)


def band(j, h, tones):
    """Quantise a vertical ramp into discrete pixel-art bands."""
    return tones[min(len(tones) - 1, j * len(tones) // max(1, h))]


def disc(cx, cy, r):
    """Integer pixel disc, returned as a set of (x, y)."""
    out = set()
    lo, hi = int(math.floor(cx - r)) - 1, int(math.ceil(cx + r)) + 1
    for y in range(lo, hi + 1):
        for x in range(lo, hi + 1):
            if (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= r * r:
                out.add((x, y))
    return out


# ------------------------------------------------------------------- wood ---

def oak_grain(img, x, y, w, h, rng, light=1.0, vertical=True, knots=0):
    """Carved oak: long grain streaks, ray flecks, open pores, optional knots."""
    streak = {}
    span = w if vertical else h
    for a in range(span):
        streak[a] = rng.random()
    for j in range(h):
        for i in range(w):
            along, across = (j, i) if vertical else (i, j)
            s = streak[across]
            idx = 2
            if s < 0.22:
                idx = 1
            elif s > 0.84:
                idx = 3
            r = rng.random()
            if r < 0.10:
                idx = max(0, idx - 1)
            elif r < 0.16:
                idx = min(4, idx + 1)
            if (along * 7 + across * 5) % 23 == 0:
                idx = max(0, idx - 1)          # open pore
            if (along + across * 3) % 37 == 0:
                idx = min(4, idx + 1)          # ray fleck
            put(img, x + i, y + j, shade(OAK[idx], light))
    for _ in range(knots):
        kx = x + rng.randrange(3, max(4, w - 4))
        ky = y + rng.randrange(3, max(4, h - 4))
        for (px, py) in disc(kx + 0.5, ky + 0.5, 2.4):
            put(img, px, py, shade(OAK[0], light))
        for (px, py) in disc(kx + 0.5, ky + 0.5, 1.2):
            put(img, px, py, shade(OAK[1], light * 0.9))


def endgrain_oak(img, x, y, w, h, rng, light_top=1.14, light_bot=0.78):
    """Sawn board edge: tight growth rings running along the edge."""
    for j in range(h):
        light = light_top + (light_bot - light_top) * (j / max(1, h - 1))
        for i in range(w):
            ring = (i * 3 + (j * 2) // 5) % 11
            if ring == 0:
                idx = 0
            elif ring in (1, 2):
                idx = 1
            elif ring in (5, 6):
                idx = 3
            elif ring == 7:
                idx = 4
            else:
                idx = 2
            if rng.random() < 0.08:
                idx = max(0, idx - 1)
            put(img, x + i, y + j, shade(OAK[idx], light))


def chamfer(img, x, y, w, h, t):
    """Carved outer arris: lit on top/left, shadowed on bottom/right."""
    for e in range(t):
        f_top = 1.20 - 0.06 * e
        f_bot = 0.62 + 0.06 * e
        for i in range(x + e, x + w - e):
            put(img, i, y + e, shade(OAK[3], f_top))
            put(img, i, y + h - 1 - e, shade(OAK[1], f_bot))
        for j in range(y + e, y + h - e):
            put(img, x + e, j, shade(OAK[3], f_top * 0.95))
            put(img, x + w - 1 - e, j, shade(OAK[1], f_bot * 1.05))


def carved_bead(img, x0, y0, x1, y1):
    """A 2px groove cut into the board -- shadow line then catch-light line."""
    box(img, x0, y0, x1, y1, OAK[0])
    box(img, x0 + 1, y0 + 1, x1 - 1, y1 - 1, shade(OAK[4], 1.05))
    line_h(img, x0 + 1, x1 - 1, y1 - 1, OAK[0])
    line_v(img, x1 - 1, y0 + 1, y1 - 1, OAK[0])


# ------------------------------------------------------------------- iron ---

def peen(k, t, idx):
    """Hammer marks: short dashes running ALONG the strap, staggered by row.

    Isolated random pixels read as static; a smith's planishing leaves tracks.
    """
    if (k % 3) < 2:
        phase = (k // 3 + t) % 5
        if phase == 0:
            return min(4, idx + 1)
        if phase == 2:
            return max(0, idx - 1)
    return idx


def rivet(img, cx, cy, r, light, mat=None, crown=None):
    """Domed stud: a lit crown with a shadow crescent only on the far side.

    A full seating ring reads as a hole at this scale, so the shadow is a
    crescent under the down-right rim and nothing more. `mat` defaults to the
    forged-iron ramp; pass BRASS for the plan clips.
    """
    mat = IRON if mat is None else mat
    crown = BONE[0] if crown is None else crown
    core = disc(cx + 0.5, cy + 0.5, r)
    for (px, py) in disc(cx + 0.5, cy + 0.5, r + 0.7) - core:
        if (px - cx) + (py - cy) > 0:
            put(img, px, py, shade(mat[0], light * 0.9))
    for (px, py) in core:
        k = (px - cx) + (py - cy)
        c = mat[4] if k <= -r * 0.3 else (mat[3] if k <= r * 0.4 else mat[2])
        put(img, px, py, shade(c, light))
    put(img, cx - 1, cy - 1, shade(crown, light))


def corner_bracket(img, cx, cy, sx, sy, arm, thick, light, rivets=2,
                   rivet_r=1.4):
    """Hand-forged L bracket anchored at outer corner (cx, cy).

    (sx, sy) point inward along each arm. Arms taper at the far end, the outer
    arris takes the key light, and domed rivets are set along both arms.
    """
    cells = {}
    for k in range(arm):
        # Taper: the last few pixels of each arm narrow to a forged tip.
        tip = max(0, k - (arm - 4))
        for t in range(thick - tip):
            cells[(cx + sx * k, cy + sy * t)] = (t, k)
            cells[(cx + sx * t, cy + sy * k)] = (t, k)
    for (px, py), (t, k) in cells.items():
        idx = 2
        if t == 0:
            idx = 4                            # outer arris, lit
        elif t == 1:
            idx = 3
        elif t >= thick - 2:
            idx = 0                            # inner edge, in shadow
        put(img, px, py, shade(IRON[peen(k, t, idx)], light))
    step = max(3, (arm - 4) // max(1, rivets))
    for n in range(rivets):
        k = 3 + n * step
        if k >= arm - 3:
            break
        rivet(img, cx + sx * k, cy + sy * (thick // 2), rivet_r, light)
        rivet(img, cx + sx * (thick // 2), cy + sy * k, rivet_r, light)


# ------------------------------------------------------------------ brass ---

def brass_molding(img, x, y, w, h, t, light=1.0):
    """Aged-brass frame with an ogee profile and patina in the hollows."""
    for j in range(h):
        for i in range(w):
            e = min(i, j, w - 1 - i, h - 1 - j)
            if e >= t:
                continue
            top = j < h - 1 - j
            left = i < w - 1 - i
            vert = min(j, h - 1 - j) <= min(i, w - 1 - i)
            lit = (vert and top) or (not vert and left)
            side_f = (1.10 if top else 0.80) if vert else (1.03 if left else 0.88)
            if e == 0:
                idx = 1 if lit else 0          # outer fillet
            elif e == 1:
                idx = 4 if lit else 1          # the one bright arris
            elif e == t - 1:
                idx = 0                        # rolls into the recess
            else:
                idx = 3 if lit else 2
            c = shade(BRASS[idx], light * side_f)
            # Patina settles in the shadowed hollow of the profile.
            if not lit and e in (0, t - 1) and (i * 5 + j * 3) % 7 == 0:
                c = mix(c, PATINA, 0.55)
            elif e == t - 2 and (i * 3 + j * 5) % 11 == 0:
                c = mix(c, PATINA, 0.35)
            put(img, x + i, y + j, c)


# --------------------------------------------------------------- parchment ---

def parchment_sheet(img, x, y, w, h, rng):
    """Laid parchment: fibre, a warm top-left bias, and a deckle edge."""
    for j in range(h):
        for i in range(w):
            idx = 3
            r = rng.random()
            if r < 0.16:
                idx = 2
            elif r < 0.30:
                idx = 4
            if (i * 3 + j * 11) % 29 == 0:
                idx = min(4, idx + 1)          # laid line
            if (i + j) < (w + h) // 4:
                idx = min(4, idx + 1)          # key light on the near corner
            elif (i + j) > (w + h) * 3 // 4:
                idx = max(1, idx - 1)
            put(img, x + i, y + j, PARCH[idx])
    for _ in range(max(4, (w * h) // 90)):     # fibres in the pulp
        fx, fy = x + rng.randrange(1, w - 1), y + rng.randrange(1, h - 1)
        put(img, fx, fy, PARCH[1])
        if rng.random() < 0.5:
            put(img, fx + 1, fy, PARCH[2])
    # Deckle edge: torn, never a ruler-straight line.
    for i in range(w):
        if (i * 7) % 5 < 2:
            put(img, x + i, y, PARCH[4])
            put(img, x + i, y + h - 1, PARCH[1])
        else:
            put(img, x + i, y, PARCH[2])
            put(img, x + i, y + h - 1, PARCH[0])
    for j in range(h):
        if (j * 5) % 4 < 2:
            put(img, x, y + j, PARCH[4])
            put(img, x + w - 1, y + j, PARCH[1])
        else:
            put(img, x, y + j, PARCH[2])
            put(img, x + w - 1, y + j, PARCH[0])


def blueprint_full(img, ox, oy):
    """30x30 plan: gabled timber elevation over a small floor plan."""
    heavy, light, faint = INK[1], INK[2], INK[3]

    def P(i, j, c):
        put(img, ox + i, oy + j, c)

    for r in range(11):                        # gable roof, solid mass
        for i in range(15 - (r + 1), 15 + (r + 1)):
            P(i, r, heavy)
    for i in range(2, 28):                     # eaves, overhanging
        P(i, 11, heavy)
    for i in range(4, 26):                     # wall box
        P(i, 21, heavy)
    for j in range(12, 22):
        P(4, j, heavy)
        P(25, j, heavy)
    for (wx) in (7, 18):                       # leaded windows
        for j in range(14, 19):
            P(wx, j, light)
            P(wx + 4, j, light)
        for i in range(wx, wx + 5):
            P(i, 14, light)
            P(i, 18, light)
        for j in range(14, 19):
            P(wx + 2, j, light)
    for j in range(16, 22):                    # door
        P(13, j, heavy)
        P(16, j, heavy)
    for i in range(13, 17):
        P(i, 16, heavy)
    P(15, 19, light)                           # latch
    for k in range(3):                         # half-timber corner braces
        P(5 + k, 13 + k, light)
        P(24 - k, 13 + k, light)

    for i in range(5, 25):                     # floor plan below
        P(i, 23, faint)
        P(i, 29, faint)
    for j in range(23, 30):
        P(5, j, faint)
        P(24, j, faint)
        P(14, j, faint)
    P(14, 26, PARCH[3])                        # inner doorway
    P(14, 27, PARCH[3])
    P(9, 29, PARCH[3])                         # entry
    P(10, 29, PARCH[3])
    for i in range(6, 9):                      # hearth
        for j in range(24, 26):
            P(i, j, light)


def blueprint_compact(img, ox, oy):
    """22x22 plan for the held item: elevation plus a single plan band."""
    heavy, light, faint = INK[1], INK[2], INK[3]

    def P(i, j, c):
        put(img, ox + i, oy + j, c)

    for r in range(8):
        for i in range(11 - (r + 1), 11 + (r + 1)):
            P(i, r, heavy)
    for i in range(1, 21):
        P(i, 8, heavy)
    for j in range(9, 17):
        P(3, j, heavy)
        P(18, j, heavy)
    for i in range(3, 19):
        P(i, 16, heavy)
    for (wx) in (5, 13):
        for j in range(10, 13):
            P(wx, j, light)
            P(wx + 3, j, light)
        for i in range(wx, wx + 4):
            P(i, 10, light)
            P(i, 12, light)
    for j in range(12, 17):
        P(9, j, heavy)
        P(12, j, heavy)
    for i in range(9, 13):
        P(i, 12, heavy)
    for i in range(5, 17):
        P(i, 18, faint)
        P(i, 21, faint)
    for j in range(18, 22):
        P(5, j, faint)
        P(16, j, faint)
        P(10, j, faint)


# ------------------------------------------------------------- deep recess ---

def deep_recess(img, x, y, w, h, rng, wall=3):
    """The empty plan socket: a genuinely deep, near-black well."""
    floor = shade(COAL[0], 0.72)
    for j in range(h):
        for i in range(w):
            c = floor
            if rng.random() < 0.07:
                c = shade(COAL[0], 0.95)       # grit on the floor
            if (i + j) > (w + h) * 7 // 8:
                c = shade(COAL[0], 1.06)       # faint pool of bounced light
            put(img, x + i, y + j, c)
    for e in range(wall):                      # sloped inner walls
        f_dark = 0.30 + 0.10 * e
        f_lit = 1.9 - 0.30 * e
        for i in range(x + e, x + w - e):
            put(img, i, y + e, shade(COAL[0], f_dark))
            put(img, i, y + h - 1 - e, shade(COAL[1], f_lit))
        for j in range(y + e, y + h - e):
            put(img, x + e, j, shade(COAL[0], f_dark * 1.1))
            put(img, x + w - 1 - e, j, shade(COAL[1], f_lit * 0.85))


def plan_clips(img, x, y, w, h, seated):
    """Four brass tongues that hold a fitted plan sheet."""
    lit = BRASS[3] if seated else BRASS[2]
    dim = BRASS[1] if seated else BRASS[0]
    span = max(3, h // 5)
    for j in range(y + h // 2 - span, y + h // 2 + span):
        put(img, x, j, lit)
        put(img, x + 1, j, dim)
        put(img, x + w - 1, j, dim)
        put(img, x + w - 2, j, mix(lit, dim, 0.5))
    span = max(3, w // 5)
    for i in range(x + w // 2 - span, x + w // 2 + span):
        put(img, i, y, lit)
        put(img, i, y + 1, mix(lit, dim, 0.4))
        put(img, i, y + h - 1, dim)
        put(img, i, y + h - 2, dim)


# ============================================================ block faces ===

def _plaque_face(filled):
    """64x64 front. Carved oak, iron brackets, brass molding, plan socket."""
    seed = SEED_FRONT_FILLED if filled else SEED_FRONT_EMPTY
    rng = random.Random(seed)
    img = new_image(64, 64)

    oak_grain(img, 0, 0, 64, 64, rng, light=1.0, vertical=True, knots=2)
    chamfer(img, 0, 0, 64, 64, 3)
    carved_bead(img, 8, 8, 55, 55)

    brass_molding(img, 12, 12, 40, 40, 3)
    inner = (15, 15, 34, 34)

    if filled:
        parchment_sheet(img, *inner, rng=random.Random(seed + 11))
        blueprint_full(img, 17, 17)
        plan_clips(img, *inner, seated=True)
    else:
        deep_recess(img, *inner, rng=random.Random(seed + 11), wall=3)
        plan_clips(img, *inner, seated=False)

    # Brackets last: they sit proud of everything else.
    corner_bracket(img, 0, 0, 1, 1, 22, 7, 1.16)
    corner_bracket(img, 63, 0, -1, 1, 22, 7, 1.02)
    corner_bracket(img, 0, 63, 1, -1, 22, 7, 0.92)
    corner_bracket(img, 63, 63, -1, -1, 22, 7, 0.80)
    return img


def gen_block_faces():
    save(_plaque_face(False), f"{ASSETS}/textures/block/plaque_front_empty.png")
    save(_plaque_face(True), f"{ASSETS}/textures/block/plaque_front_filled.png")

    # --- side: sawn oak edge between the wrapped ends of the brackets -------
    # Any horizontal crop reads iron | end-grain oak | iron, so a thin panel
    # model shows the brackets wrapping round the board's edge.
    rng = random.Random(SEED_SIDE)
    img = new_image(64, 64)
    endgrain_oak(img, 12, 0, 40, 64, rng)
    for j in range(64):
        light = 1.16 - 0.34 * (j / 63.0)
        for i in range(12):
            idx = 4 if i == 0 else (3 if i < 3 else (2 if i < 9 else 1))
            put(img, i, j, shade(IRON[peen(j, i, idx)], light))
            put(img, 63 - i, j, shade(IRON[peen(j, i, idx)], light * 0.86))
    for jy in (8, 24, 40, 55):                 # domed rivets down each strap
        rivet(img, 5, jy, 1.8, 1.05)
        rivet(img, 58, jy, 1.8, 0.88)
    for j in range(64):                        # seam where iron meets wood
        put(img, 11, j, shade(OAK[0], 0.9))
        put(img, 12, j, shade(OAK[4], 1.05))
        put(img, 51, j, shade(OAK[0], 0.75))
        put(img, 52, j, shade(IRON[0], 0.85))
    save(img, f"{ASSETS}/textures/block/plaque_side.png")

    # --- back: plain oak, the darkest face, four rivet tips coming through --
    rng = random.Random(SEED_BACK)
    img = new_image(64, 64)
    oak_grain(img, 0, 0, 64, 64, rng, light=0.76, vertical=True, knots=3)
    for sx in (16, 32, 48):                    # plank seams
        for j in range(64):
            put(img, sx - 1, j, shade(OAK[0], 0.70))
            put(img, sx, j, shade(OAK[0], 0.80))
            put(img, sx + 1, j, shade(OAK[3], 0.84))
    chamfer(img, 0, 0, 64, 64, 2)
    # Peened-over tips of the bracket rivets, punched through from the front.
    for (rx, ry) in ((8, 8), (55, 8), (8, 55), (55, 55)):
        rivet(img, rx, ry, 1.0, 0.66)
    save(img, f"{ASSETS}/textures/block/plaque_back.png")


# ================================================================= items ===

def gen_item_plaque():
    """Held plaque, three-quarter: front plate with the board's real thickness."""
    rng = random.Random(SEED_ITEM_PLAQUE)
    img = new_image(64, 64)
    fx, fy, fw, fh = 3, 5, 48, 46
    depth = 6

    # Right and bottom faces, sheared to read as an isometric thickness.
    for k in range(depth):
        col = fx + fw + k
        for j in range(fy + 1 + k, fy + fh + 1 + k):
            idx = 2 if k < 2 else (1 if k < 4 else 0)
            put(img, col, j, shade(OAK[idx], 0.70 - 0.03 * k))
        row = fy + fh + k
        for i in range(fx + 1 + k, fx + fw + 1 + k):
            idx = 1 if k < 2 else 0
            put(img, i, row, shade(OAK[idx], 0.58 - 0.02 * k))
    for k in range(depth):                     # lit arris along the thickness
        put(img, fx + fw + k, fy + 1 + k, shade(OAK[3], 0.86))

    oak_grain(img, fx, fy, fw, fh, rng, light=1.06, vertical=True, knots=1)
    chamfer(img, fx, fy, fw, fh, 2)
    carved_bead(img, fx + 5, fy + 5, fx + fw - 6, fy + fh - 6)

    brass_molding(img, fx + 9, fy + 9, 30, 28, 3)
    parchment_sheet(img, fx + 12, fy + 12, 24, 22,
                    random.Random(SEED_ITEM_PLAQUE + 11))
    blueprint_compact(img, fx + 13, fy + 12)
    plan_clips(img, fx + 12, fy + 12, 24, 22, seated=True)

    for (bx, by, sx, sy, lt) in ((fx, fy, 1, 1, 1.18),
                                 (fx + fw - 1, fy, -1, 1, 1.02),
                                 (fx, fy + fh - 1, 1, -1, 0.92),
                                 (fx + fw - 1, fy + fh - 1, -1, -1, 0.80)):
        corner_bracket(img, bx, by, sx, sy, 14, 4, lt, rivets=1, rivet_r=1.0)
    save(img, f"{ASSETS}/textures/item/building_plaque.png")


def gen_item_build_plan():
    """Rolled parchment on the diagonal, tied with a leather cord."""
    img = new_image(64, 64)
    rng = random.Random(SEED_ITEM_PLAN)

    x0, y0 = 12.0, 51.0
    dx, dy = 0.7071, -0.7071          # up and to the right
    length = 40.0
    half = 8.5
    cap = 5.0

    cells = {}
    for py in range(64):
        for px in range(64):
            vx, vy = px + 0.5 - x0, py + 0.5 - y0
            t = vx * dx + vy * dy               # along the roll
            s = vx * (-dy) + vy * dx            # across it; +s = down-right
            if t < 0.0 or t > length or abs(s) > half:
                continue
            cells[(px, py)] = (t, s)

    for (px, py), (t, s) in cells.items():
        # Cylinder shading, banded: the up-left flank takes the key light.
        f = s / half
        if f < -0.80:
            idx = 2                             # the sheet turns away again
        elif f < -0.50:
            idx = 4                             # specular band, key light
        elif f < -0.18:
            idx = 4
        elif f < 0.14:
            idx = 3
        elif f < 0.44:
            idx = 3
        elif f < 0.70:
            idx = 2
        else:
            idx = 1                             # terminator
        c = PARCH[idx]
        if -0.52 < f < -0.44:
            c = PARCH[4]
        if rng.random() < 0.10:
            c = PARCH[max(0, idx - 1)]
        end = min(t, length - t)
        if end < cap:
            # Rolled end: concentric rings of the coiled sheet.
            ring = int(abs(s) * 2.0 / 1.7)
            c = PARCH[0] if ring % 2 else PARCH[2]
            if abs(s) > half - 1.4:
                c = PARCH[0]
            if end < 1.2:
                c = shade(PARCH[0], 0.85)
        elif end < cap + 2.5 and (int(t) + int(s)) % 3 == 0:
            c = PARCH[max(0, idx - 1)]          # curl creases behind the ends
        cells[(px, py)] = (t, s, c)
        put(img, px, py, c)

    # Leather cord, bound about two fifths along the roll.
    knot_t = length * 0.40
    for (px, py), val in cells.items():
        t, s = val[0], val[1]
        if not (knot_t <= t < knot_t + 5.0):
            continue
        edge = min(t - knot_t, knot_t + 5.0 - t)
        f = s / half
        idx = 4 if f < -0.5 else (3 if f < 0.0 else (2 if f < 0.5 else 1))
        if edge < 0.8:
            idx = max(0, idx - 2)               # cord shadow on the parchment
        put(img, px, py, LEATHER[idx])

    # Knot and two frayed tails, thrown to the shadow side.
    kt, ks = knot_t + 2.5, half * 0.55
    kx = int(x0 + dx * kt + (-dy) * ks)
    ky = int(y0 + dy * kt + dx * ks)
    for (px, py) in disc(kx + 0.5, ky + 0.5, 3.0):
        put(img, px, py, LEATHER[2])
    for (px, py) in disc(kx + 0.5, ky + 0.5, 1.8):
        put(img, px, py, LEATHER[4])
    put(img, kx + 2, ky + 2, LEATHER[0])
    for n in range(7):
        put(img, kx + 2 + n, ky + 3 + n // 2, LEATHER[2 if n % 2 else 3])
        put(img, kx + 1 + n // 2, ky + 3 + n, LEATHER[1 if n % 2 else 2])
    save(img, f"{ASSETS}/textures/item/build_plan.png")


# ======================================================= material textures ===
# These six feed the multi-element 3D block model, where every element shows
# several faces at different depths. They therefore carry MICRO-relief lit
# from the top left (each grain line, dimple and peen mark has its own lit and
# shadowed edge) and deliberately NO macro gradient -- so any UV window the
# model takes reads correctly, and Minecraft's own per-face shading supplies
# the large-scale lighting. The brass and iron are 16-periodic, hence seamless
# at 64, so neighbouring elements match wherever they are cut.

def blueprint_large(img, ox, oy):
    """54x54 plan: gabled timber elevation with chimney, over a floor plan."""
    heavy, light, faint = INK[1], INK[2], INK[3]

    def P(i, j, c):
        put(img, ox + i, oy + j, c)

    def rect(x0, y0, x1, y1, c):
        for i in range(x0, x1 + 1):
            P(i, y0, c)
            P(i, y1, c)
        for j in range(y0, y1 + 1):
            P(x0, j, c)
            P(x1, j, c)

    for j in range(1, 8):                      # chimney behind the roofline
        for i in range(34, 40):
            P(i, j, heavy if (i in (34, 39) or j == 1) else INK[2])
    for i in range(33, 41):
        P(i, 0, heavy)
    for r in range(18):                        # gable roof
        for i in range(27 - (r + 1), 27 + (r + 1)):
            P(i, r, heavy)
    for i in range(5, 49):                     # overhanging eaves
        P(i, 18, heavy)
        P(i, 19, heavy)
    rect(8, 20, 45, 33, heavy)                 # wall box
    for j in range(20, 34):                    # corner studs
        P(16, j, faint)
        P(37, j, faint)
    for k in range(4):                         # half-timber braces
        P(10 + k, 22 + k, light)
        P(43 - k, 22 + k, light)
    for wx in (13, 32):                        # leaded windows, four panes
        rect(wx, 22, wx + 8, 30, light)
        for j in range(22, 31):
            P(wx + 4, j, light)
        for i in range(wx, wx + 9):
            P(i, 26, light)
    rect(24, 25, 30, 33, heavy)                # door
    for j in range(26, 33):
        P(26, j, faint)
        P(28, j, faint)
    P(29, 29, light)                           # latch
    P(29, 30, light)

    for i in range(33, 48):                    # caption stroke under the plan
        if (i - 33) % 5 != 4:
            P(i, 35, faint)

    rect(5, 37, 48, 53, faint)                 # floor plan
    for j in range(37, 54):                    # partition wall
        P(27, j, faint)
    for j in (44, 45, 46):                     # inner doorway
        P(27, j, PARCH[3])
    for i in range(28, 49):                    # second partition
        P(i, 45, faint)
    for i in (36, 37, 38):
        P(i, 45, PARCH[3])
    for i in range(24, 29):                    # entry gap in the outer wall
        P(i, 53, PARCH[3])
    for j in range(39, 43):                    # hearth block
        for i in range(8, 14):
            P(i, j, light if (i in (8, 13) or j in (39, 42)) else INK[3])


def gen_materials():
    # --- 1. board face: sawn oak, vertical long grain -----------------------
    rng = random.Random(SEED_MAT_BOARD)
    img = new_image(64, 64)
    oak_grain(img, 0, 0, 64, 64, rng, light=1.0, vertical=True, knots=0)
    for gx in (4, 11, 19, 26, 34, 41, 49, 57):  # grain lines, each with relief
        drift = 0
        for j in range(64):
            if j % 9 == 0:
                drift += (cell_noise(gx, j, 3) - 1)
            x = gx + drift
            put(img, x, j, OAK[0])
            put(img, x - 1, j, shade(OAK[3], 1.06))   # lit side of the groove
            put(img, x + 1, j, shade(OAK[1], 0.94))   # shadowed side
    for _ in range(26):                        # ray flecks
        fx, fy = rng.randrange(2, 61), rng.randrange(1, 63)
        for k in range(rng.randint(2, 3)):
            put(img, fx + k, fy, shade(OAK[4], 1.02))
    # A couple of age marks: a shallow gouge and a small knot. The gouge is a
    # clean straight scratch -- zig-zagging it just reads as dither noise.
    for j in range(21, 31):
        put(img, 46, j, shade(OAK[0], 0.85))
        put(img, 45, j, shade(OAK[4], 1.04))
    put(img, 46, 20, shade(OAK[1], 0.95))
    put(img, 46, 31, shade(OAK[1], 0.95))
    for (px, py) in disc(17.5, 45.5, 3.1):
        put(img, px, py, shade(OAK[1], 0.9))
    for (px, py) in disc(17.5, 45.5, 1.7):
        put(img, px, py, shade(OAK[0], 0.9))
    put(img, 16, 44, shade(OAK[3], 1.05))
    # Faint chamfer implied at the very edge of the board.
    for i in range(64):
        put(img, i, 0, shade(OAK[3], 1.12))
        put(img, i, 63, shade(OAK[0], 0.88))
        put(img, 0, i, shade(OAK[3], 1.06))
        put(img, 63, i, shade(OAK[0], 0.90))
    save(img, f"{ASSETS}/textures/block/plaque_board.png")

    # --- 2. board edge: END grain, short strokes across the thickness -------
    # Grain runs vertically here (across a horizontally-running edge). For the
    # left/right edges of the board set "rotation": 90 on the face UV.
    rng = random.Random(SEED_MAT_EDGE)
    img = new_image(64, 64)
    # Gentle arcs struck from a centre far off to the left read as growth
    # rings; the jog every 6 rows stops any ring running the full height
    # (that was the "corduroy" failure on the old side face).
    for j in range(64):
        seg = j // 6
        jog = ((seg * 7) % 5) - 2
        for i in range(64):
            arc = int(math.hypot(i + 46 + jog, (j - 32) * 0.55))
            idx = (1, 2, 3, 3, 2, 2)[arc % 6]
            if cell_noise(i, j, 17) == 0:
                idx = max(0, idx - 1)
            elif cell_noise(i, j, 23) == 7:
                idx = min(4, idx + 1)
            put(img, i, j, OAK[idx])
    for _ in range(30):                        # medullary rays, crossways
        fx, fy = rng.randrange(1, 60), rng.randrange(1, 63)
        for k in range(rng.randint(2, 4)):
            put(img, fx + k, fy, shade(OAK[4], 1.04))
    for _ in range(5):                         # seasoning checks: straight
        cx, cy = rng.randrange(3, 60), rng.randrange(2, 56)
        for k in range(rng.randint(4, 7)):     # splits, not dithered zig-zags
            put(img, cx, cy + k, shade(OAK[0], 0.8))
            put(img, cx - 1, cy + k, shade(OAK[3], 1.05))
    save(img, f"{ASSETS}/textures/block/plaque_board_edge.png")

    # --- 3. aged brass: planished dimples, patina in the low spots ----------
    # Deliberately fine-grained: the dimple pitch is 8px, so even a 3px-wide
    # crop on a raised lip still carries a lit rim and a shaded pool.
    img = new_image(64, 64)
    # BRASS[2] is the anchor tone. Dimples are shallow and jittered off the
    # lattice, and the bright stop appears on only about a third of them as a
    # single pixel -- a full rim arc on every dimple reads as wallpaper, not
    # planished metal.
    for j in range(64):
        row = j // 8
        off = 4 if row % 2 else 0               # staggered courses
        for i in range(64):
            cellx = (i - off) // 8
            # Jitter is 2-periodic per axis, so the sheet still tiles at 64.
            jx = ((cellx % 2) + (row % 2) * 2) % 3 - 1
            jy = ((row % 2) + (cellx % 2) * 2) % 3 - 1
            dx = (i - off) % 8 - 4 - jx
            dy = (j % 8) - 4 - jy
            d = math.hypot(dx, dy)
            k = dx + dy
            if d <= 2.4:                        # shallow hammer dimple
                idx = 3 if k <= -1.6 else (2 if k < 1.2 else 1)
            else:                               # ridge between dimples
                idx = 3 if k < -3 else 2
            c = BRASS[idx]
            n = cell_noise(i, j)
            if idx == 1 and n % 3 == 0:
                c = mix(c, PATINA, 0.55)        # patina pools in the hollows
            elif idx == 2 and n % 13 == 0:
                c = mix(c, PATINA, 0.28)
            elif n == 17:
                c = shade(c, 0.88)              # tarnish speck
            # One restrained catch-light: a single pixel on the upper-left rim
            # of roughly every third dimple.
            if d <= 2.4 and k <= -1.6 and (cellx + row) % 3 == 0 and dx <= -1:
                c = BRASS[4]
            put(img, i, j, c)
    save(img, f"{ASSETS}/textures/block/plaque_brass.png")

    # --- 4. hand-forged iron: peen dashes along +X, studs on a 32px pitch ---
    # Dashes run along +X. On bracket arms that run vertically set
    # "rotation": 90 so the planishing follows the strap.
    img = new_image(64, 64)
    for j in range(64):
        for i in range(64):
            idx = 2
            if (i % 8) < 5:                     # 5px dashes, held for 3 rows
                phase = (i // 8 + j // 3) % 5
                if phase == 0:
                    idx = 3
                elif phase == 2:
                    idx = 1
            if cell_noise(i, j, 13) == 0:
                idx = max(0, idx - 1)
            elif cell_noise(i, j, 19) == 5:
                idx = min(4, idx + 1)
            put(img, i, j, IRON[idx])
    for j in range(64):                         # forge scale, dark and matte
        for i in range(64):
            if cell_noise(i, j, 29) == 3:
                put(img, i, j, shade(IRON[0], 1.05))
    for cy in (16, 48):                         # rivet crowns + shadow crescents
        for cx in (16, 48):
            rivet(img, cx, cy, 2.6, 1.0)
    save(img, f"{ASSETS}/textures/block/plaque_iron.png")

    # --- 5. empty socket face: a hole with depth, four brass clips waiting --
    rng = random.Random(SEED_MAT_SOCKET)
    img = new_image(64, 64)
    for j in range(64):
        for i in range(64):
            f = 0.74
            # Banded cast shadow off the frame above and to the left, and a
            # faint bounce pooling at the lower right. Steps, never a blur.
            near = min(i, j)
            far = min(63 - i, 63 - j)
            if near < 3:
                f = 0.34
            elif near < 7:
                f = 0.48
            elif near < 12:
                f = 0.62
            if far < 4:
                f = 0.92
            elif far < 9:
                f = 0.82
            c = shade(COAL[0], f)
            if rng.random() < 0.055:
                c = shade(COAL[0], f * 1.28)    # grit on the floor
            put(img, i, j, c)
    for _ in range(7):                          # fine scratches
        sx, sy = rng.randrange(8, 52), rng.randrange(8, 56)
        for k in range(rng.randint(3, 7)):
            put(img, sx + k, sy + (k // 3), shade(COAL[0], 1.35))
    tabs = ((0, 25, 10, 14), (54, 25, 10, 14),   # left, right
            (25, 0, 14, 10), (25, 54, 14, 10))   # top, bottom
    for n, (tx, ty, tw, th) in enumerate(tabs):
        light = (1.10, 0.88, 1.14, 0.84)[n]
        for j in range(th):
            for i in range(tw):
                e = min(i, j, tw - 1 - i, th - 1 - j)
                k = (i / tw) + (j / th)
                if e == 0:
                    idx = 2 if k < 0.9 else 0
                elif e == 1:
                    idx = 3 if k < 0.7 else 1
                else:
                    idx = 1
                c = shade(BRASS[idx], light)
                if e >= 2 and cell_noise(tx + i, ty + j, 17) == 0:
                    c = mix(c, PATINA, 0.45)
                put(img, tx + i, ty + j, c)
        for (px, py) in ((tx, ty), (tx + tw - 1, ty),
                         (tx, ty + th - 1), (tx + tw - 1, ty + th - 1)):
            put(img, px, py, shade(COAL[0], 0.5))   # rounded tab corners
        rivet(img, tx + tw // 2, ty + th // 2, 1.7, light,
              mat=BRASS, crown=BRASS[3])
    save(img, f"{ASSETS}/textures/block/plaque_socket.png")

    # --- 6. plan face: parchment filling the whole face, no frame ----------
    img = new_image(64, 64)
    parchment_sheet(img, 0, 0, 64, 64, random.Random(SEED_MAT_PLAN))
    blueprint_large(img, 5, 5)
    save(img, f"{ASSETS}/textures/block/plaque_plan.png")


# ============================================================== gui sheets ===

def oak_panel(img, x, y, w, h, rng, border=16, centre_sample=None):
    """Carved-oak frame around a charcoal inset, with brass corner ornaments."""
    for j in range(h):
        for i in range(w):
            e = min(i, j, w - 1 - i, h - 1 - j)
            if e >= border:
                idx = 3
                r = rng.random()
                if r < 0.05:
                    idx = 2
                elif r < 0.09:
                    idx = 4
                put(img, x + i, y + j, COAL[idx])
                continue
            if e < 2:
                put(img, x + i, y + j, NEAR_BLACK if e == 0 else OAK[0])
                continue
            top, left = j < h - 1 - j, i < w - 1 - i
            vert = min(j, h - 1 - j) <= min(i, w - 1 - i)
            lit = (vert and top) or (not vert and left)
            light = (1.16 if top else 0.76) if vert else (1.04 if left else 0.86)
            along = i if vert else j
            if e in (2, 3):                    # outer arris of the frame
                c = shade(OAK[3 if lit else 1], light)
            elif e in (border - 5, border - 4):  # carved bead ring
                c = shade(OAK[0] if e == border - 5 else OAK[4], light)
            elif e >= border - 3:              # wall of the sunken interior
                c = shade(COAL[0], 0.8) if lit else LIP
                if e == border - 1 and not lit:
                    c = mix(LIP, COAL[4], 0.4)
            else:
                idx = 2
                r = rng.random()
                if r < 0.24:
                    idx = 1
                elif r < 0.34:
                    idx = 3
                if (along * 7 + e * 5) % 23 == 0:
                    idx = max(0, idx - 1)
                elif (along * 3 + e) % 37 == 0:
                    idx = min(4, idx + 1)
                c = shade(OAK[idx], light)
            put(img, x + i, y + j, c)

    # Brass corner ornaments: an L strap with a stud, mirrored per corner.
    arm, thick = 16, 4
    for (cx, cy, sx, sy, light) in (
            (x + 3, y + 3, 1, 1, 1.10),
            (x + w - 4, y + 3, -1, 1, 0.98),
            (x + 3, y + h - 4, 1, -1, 0.94),
            (x + w - 4, y + h - 4, -1, -1, 0.82)):
        for k in range(arm):
            tip = max(0, k - (arm - 3))
            for t in range(thick - tip):
                idx = 4 if t == 0 else (2 if t < thick - 1 else 0)
                if (k * 3 + t * 5) % 9 == 0:
                    idx = max(0, idx - 1)
                for (px, py) in ((cx + sx * k, cy + sy * t),
                                 (cx + sx * t, cy + sy * k)):
                    put(img, px, py, shade(BRASS[idx], light))
        for (px, py) in disc(cx + sx * 7 + 0.5, cy + sy * 7 + 0.5, 2.2):
            put(img, px, py, shade(BRASS[1], light))
        for (px, py) in disc(cx + sx * 7 + 0.5, cy + sy * 7 + 0.5, 1.2):
            put(img, px, py, shade(BRASS[3], light))

    if centre_sample:
        csx, csy = centre_sample
        fill(img, csx - 4, csy - 4, 9, 9, COAL[3])


def inset_field(img, x, y, w, h, base_idx=1, accent=None, hatch=False):
    """Charcoal inset: sunken 2px bevel, optional brass accent / dead hatch."""
    for j in range(h):
        for i in range(w):
            c = COAL[base_idx]
            if hatch and (i + j) % 6 == 0:
                c = COAL[max(0, base_idx - 1)]
            put(img, x + i, y + j, c)
    for i in range(w):
        put(img, x + i, y, NEAR_BLACK)
        put(img, x + i, y + 1, COAL[0])
        put(img, x + i, y + h - 2, mix(LIP, COAL[base_idx], 0.45))
        put(img, x + i, y + h - 1, LIP)
    for j in range(h):
        put(img, x, y + j, NEAR_BLACK)
        put(img, x + 1, y + j, COAL[0])
        put(img, x + w - 2, y + j, mix(LIP, COAL[base_idx], 0.55))
        put(img, x + w - 1, y + j, mix(LIP, COAL[4], 0.5))
    put(img, x + w - 1, y, COAL[0])
    put(img, x, y + h - 1, COAL[0])
    if accent is not None:
        for j in range(2, h - 2):
            put(img, x + 2, y + j, accent)
            put(img, x + 3, y + j, shade(accent, 0.62))


def wide_button(img, x, y, w, h, state, oak_body=False, cap=20):
    """Brass-edged button, banded (never a smooth gradient).

    Columns between `cap` and `w-cap` are identical, so the sprite three-slices
    to any width without a seam. Oak-bodied variants also carry grain, which is
    column-varying, so those are stored at their final size.
    """
    if state == "disabled":
        # Must recede, not shout: flat, dead, darker than normal, no brass.
        tones = [IRON[0], IRON[0], shade(IRON[0], 0.82), shade(IRON[0], 0.82)]
        edge_lit, edge_side, edge_dark = IRON[1], shade(IRON[0], 0.9), NEAR_BLACK
        sunken = False
    elif oak_body:
        lift = {"normal": 1.0, "hover": 1.18, "pressed": 0.82}[state]
        tones = [shade(OAK[4], lift), shade(OAK[3], lift),
                 shade(OAK[2], lift), shade(OAK[1], lift)]
        edge_lit = BRASS[3] if state == "hover" else BRASS[2]
        edge_side = BRASS[1]
        edge_dark = BRASS[0]
        sunken = state == "pressed"
    else:
        lift = {"normal": 1.0, "hover": 1.40, "pressed": 0.70}[state]
        tones = [shade(COAL[4], lift), shade(COAL[3], lift),
                 shade(COAL[2], lift), shade(COAL[1], lift)]
        edge_lit = BRASS[3] if state == "hover" else BRASS[1]
        edge_side = BRASS[2] if state == "hover" else BRASS[0]
        edge_dark = shade(BRASS[0], 0.7)
        sunken = state == "pressed"
    if sunken:
        tones = list(reversed(tones))

    for j in range(h):
        c = band(j, h, tones)
        for i in range(w):
            cc = c
            if oak_body:
                # Grain runs ALONG the board: long horizontal streaks that
                # drift, not a diagonal lattice (which reads as tweed).
                if (j * 5 + (i // 13)) % 11 == 0:
                    cc = shade(c, 0.86)
                elif (j * 5 + (i // 17)) % 11 == 6:
                    cc = shade(c, 1.09)
                elif (i * 3 + j * 29) % 53 == 0:
                    cc = shade(c, 0.92)         # open pore
            put(img, x + i, y + j, cc)
    for i in range(w):
        put(img, x + i, y, edge_dark if sunken else edge_lit)
        put(img, x + i, y + 1, shade(tones[0], 0.55 if sunken else 1.25))
        put(img, x + i, y + h - 2, shade(tones[-1], 1.2 if sunken else 0.72))
        put(img, x + i, y + h - 1, edge_lit if sunken else edge_dark)
    for j in range(h):
        put(img, x, y + j, edge_dark if sunken else edge_side)
        put(img, x + 1, y + j, shade(tones[min(3, j * 4 // h)],
                                     0.7 if sunken else 1.12))
        put(img, x + w - 2, y + j, shade(tones[min(3, j * 4 // h)],
                                         1.1 if sunken else 0.8))
        put(img, x + w - 1, y + j, edge_side if sunken else edge_dark)
    for (px, py) in ((x, y), (x + w - 1, y), (x, y + h - 1),
                     (x + w - 1, y + h - 1)):
        put(img, px, py, NEAR_BLACK)           # nicked corners
    if state != "disabled":
        put(img, x + 3, y + 2, shade(edge_lit, 1.15))
        put(img, x + 4, y + 2, shade(edge_lit, 1.05))
        put(img, x + w - 4, y + h - 3, edge_dark)


# ------------------------------------------------------------------ icons ---

HEART = [
    "..####..####..",
    ".############.",
    "##############",
    "##############",
    "##############",
    "##############",
    ".############.",
    ".############.",
    "..##########..",
    "...########...",
    "....######....",
    ".....####.....",
    "......##......",
]


def draw_heart(img, x, y):
    for j, row in enumerate(HEART):
        for i, ch in enumerate(row):
            if ch != "#":
                continue
            k = i + j
            c = CRIM[3]
            if k < 7:
                c = CRIM[4]
            elif k > 20:
                c = CRIM[1]
            elif k > 15:
                c = CRIM[2]
            put(img, x + 2 + i, y + 2 + j, c)
    for (px, py) in ((5, 4), (6, 4), (5, 5)):  # specular on the near lobe
        put(img, x + px, y + py, mix(CRIM[4], BONE[4], 0.45))


def draw_face(img, x, y):
    for (px, py) in disc(9.0, 9.0, 8.0):
        k = px + py
        c = EMER[3]
        if k < 10:
            c = EMER[4]
        elif k > 22:
            c = EMER[1]
        put(img, x + px, y + py, c)
    for (px, py) in disc(9.0, 9.0, 8.0):       # rim
        if not disc(9.0, 9.0, 7.0).__contains__((px, py)):
            put(img, x + px, y + py, EMER[0] if px + py > 17 else EMER[2])
    for ex in (5, 11):                         # eyes
        for ey in (6, 7):
            put(img, x + ex, y + ey, shade(COAL[0], 0.8))
            put(img, x + ex + 1, y + ey, shade(COAL[0], 0.8))
    for (mx, my) in ((5, 11), (6, 12), (7, 13), (8, 13),
                     (9, 13), (10, 13), (11, 12), (12, 11)):
        put(img, x + mx, y + my, shade(COAL[0], 0.8))


def draw_warning(img, x, y):
    for j in range(1, 17):
        span = round(j * 8.0 / 15.0)
        for i in range(9 - span, 9 + span + 1):
            edge = i in (9 - span, 9 + span) or j == 16
            idx = 4 if (i - (9 - span)) < 2 and not edge else 3
            if j > 11:
                idx = 2 if not edge else 1
            if edge:
                idx = 1 if i > 9 or j == 16 else 4
            put(img, x + i, y + j, AMBER[idx])
    for py in range(5, 11):                    # exclamation
        put(img, x + 9, y + py, shade(COAL[0], 0.85))
    put(img, x + 9, y + 12, shade(COAL[0], 0.85))
    put(img, x + 9, y + 13, shade(COAL[0], 0.85))


def draw_dot(img, x, y):
    for (px, py) in disc(9.0, 9.0, 5.6):
        put(img, x + px, y + py, EMER[0])
    for (px, py) in disc(9.0, 9.0, 4.6):
        k = px + py
        put(img, x + px, y + py, EMER[4] if k < 15 else (EMER[1] if k > 21
                                                         else EMER[3]))
    for (px, py) in ((7, 6), (8, 6), (7, 7)):
        put(img, x + px, y + py, mix(EMER[4], BONE[4], 0.5))


def x_button(img, x, y, state, size=28):
    if state == "disabled":
        tones = [IRON[2], IRON[2], IRON[1], IRON[1]]
        edge, mark = IRON[3], IRON[4]
    else:
        lift = 1.0 if state == "normal" else 1.28
        tones = [shade(CRIM[2], lift * 1.08), shade(CRIM[2], lift),
                 shade(CRIM[2], lift * 0.90), shade(CRIM[1], lift * 0.96)]
        edge = BRASS[3] if state == "hover" else BRASS[1]
        mark = BONE[4] if state == "hover" else BONE[3]
    for j in range(size):
        c = band(j, size, tones)
        for i in range(size):
            put(img, x + i, y + j, c)
    for i in range(size):
        put(img, x + i, y, edge)
        put(img, x + i, y + 1, shade(tones[0], 1.25))
        put(img, x + i, y + size - 2, shade(tones[-1], 0.75))
        put(img, x + i, y + size - 1, shade(edge, 0.5))
    for j in range(size):
        put(img, x, y + j, mix(edge, shade(edge, 0.5), 0.35))
        put(img, x + size - 1, y + j, shade(edge, 0.5))
    for (px, py) in ((0, 0), (size - 1, 0), (0, size - 1),
                     (size - 1, size - 1)):
        put(img, x + px, y + py, NEAR_BLACK)
    # Clean symmetric 2px saltire: cast shadow first, then the mark on top.
    g = size - 16
    for pass_no, (ox, oy, col) in enumerate(
            ((1, 1, shade(mark, 0.42)), (0, 0, mark))):
        for k in range(g):
            for dd in (0, 1):
                put(img, x + 8 + k + dd + ox, y + 8 + k + oy, col)
                put(img, x + 8 + (g - 1 - k) + dd + ox, y + 8 + k + oy, col)


def slot_frame(img, x, y, size=40):
    """Build Plan slot: brass-lipped well; the item sits at +4,+4 (32x32)."""
    fill(img, x, y, size, size, COAL[1])
    for i in range(size):
        put(img, x + i, y, NEAR_BLACK)
        put(img, x + i, y + size - 1, NEAR_BLACK)
        put(img, x, y + i, NEAR_BLACK)
        put(img, x + size - 1, y + i, NEAR_BLACK)
    for e in (1, 2):
        for i in range(e, size - e):
            put(img, x + i, y + e, BRASS[1] if e == 1 else BRASS[0])
            put(img, x + i, y + size - 1 - e, BRASS[2] if e == 1 else BRASS[1])
            put(img, x + e, y + i, BRASS[1] if e == 1 else BRASS[0])
            put(img, x + size - 1 - e, y + i, BRASS[1] if e == 1 else BRASS[0])
    put(img, x + 2, y + 2, BRASS[3])           # single catch-light
    deep_recess(img, x + 4, y + 4, size - 8, size - 8,
                random.Random(4201), wall=2)


def portrait_frame(img, x, y, size=32):
    fill(img, x, y, size, size, COAL[1])
    for i in range(size):
        put(img, x + i, y, shade(IRON[4], 1.08))
        put(img, x + i, y + 1, IRON[3])
        put(img, x + i, y + size - 2, IRON[1])
        put(img, x + i, y + size - 1, IRON[0])
        put(img, x, y + i, IRON[3])
        put(img, x + 1, y + i, IRON[2])
        put(img, x + size - 2, y + i, IRON[1])
        put(img, x + size - 1, y + i, IRON[0])
    for (px, py) in ((0, 0), (size - 1, 0), (0, size - 1),
                     (size - 1, size - 1)):
        put(img, x + px, y + py, BRASS[2])
        put(img, x + px, y + py, BRASS[2])
    put(img, x + 1, y + 1, BRASS[3])
    put(img, x + size - 2, y + size - 2, BRASS[0])
    for i in range(2, size - 2):               # inner shadow of the rebate
        put(img, x + i, y + 2, shade(COAL[0], 0.8))
        put(img, x + 2, y + i, shade(COAL[0], 0.8))
        put(img, x + i, y + size - 3, COAL[4])
        put(img, x + size - 3, y + i, COAL[4])


DIAMOND = [
    ".......##.......",
    "......####......",
    ".....######.....",
    "....########....",
    "...##########...",
    "....########....",
    ".....######.....",
    "......####......",
    ".......##.......",
]


def divider(img, x, y):
    """6px engraved rule (tileable) plus a separate 18x14 brass diamond."""
    for i in range(152):
        put(img, x + i, y, NEAR_BLACK)
        put(img, x + i, y + 1, shade(COAL[0], 0.8))
        put(img, x + i, y + 2, BRASS[1])
        put(img, x + i, y + 3, BRASS[0])
        put(img, x + i, y + 4, mix(LIP, COAL[4], 0.5))
        put(img, x + i, y + 5, COAL[4])
    dy = y + 10
    for j, row in enumerate(DIAMOND):
        for i, ch in enumerate(row):
            if ch != "#":
                continue
            k = i + j
            c = BRASS[2]
            if k < 12:
                c = BRASS[4]
            elif k > 18:
                c = BRASS[0]
            elif k > 15:
                c = BRASS[1]
            put(img, x + 1 + i, dy + 2 + j, c)
    for i in range(18):                        # the rule passes behind it
        if i < 2 or i > 15:
            put(img, x + i, dy + 6, BRASS[1])
            put(img, x + i, dy + 7, BRASS[0])
    put(img, x + 8, dy + 6, BRASS[0])          # centre punch


def gen_plaque_screen():
    img = new_image(512, 512)
    rng = random.Random(SEED_SCREEN)

    oak_panel(img, 0, 0, 352, 416, rng, border=16, centre_sample=(176, 208))

    slot_frame(img, 356, 4)
    portrait_frame(img, 404, 4)
    for i, st in enumerate(("normal", "hover", "disabled")):
        x_button(img, 356 + i * 36, 48, st)
    draw_heart(img, 356, 84)
    draw_face(img, 380, 84)
    draw_warning(img, 404, 84)
    draw_dot(img, 428, 84)

    wide_button(img, 356, 108, 144, 36, "normal", oak_body=True)
    wide_button(img, 356, 148, 144, 36, "hover", oak_body=True)

    divider(img, 356, 188)

    for i, st in enumerate(("normal", "hover", "pressed", "disabled")):
        wide_button(img, 356, 216 + i * 40, 152, 36, st, cap=20)

    inset_field(img, 0, 416, 300, 48, base_idx=1)
    inset_field(img, 0, 464, 300, 48, base_idx=2, accent=BRASS[2])
    save(img, f"{ASSETS}/textures/gui/plaque_screen.png")


def gen_plaque_assign():
    img = new_image(512, 512)
    rng = random.Random(SEED_ASSIGN)

    oak_panel(img, 0, 0, 300, 480, rng, border=16, centre_sample=(150, 240))
    for i in range(24, 277):                   # header rule under the title
        put(img, i, 64, NEAR_BLACK)
        put(img, i, 65, BRASS[1])
        put(img, i, 66, BRASS[0])
        put(img, i, 67, COAL[4])
    inset_field(img, 20, 80, 260, 356, base_idx=0)

    inset_field(img, 304, 4, 204, 40, base_idx=1)
    inset_field(img, 304, 48, 204, 40, base_idx=2, accent=BRASS[2])
    inset_field(img, 304, 92, 204, 40, base_idx=0, hatch=True)

    inset_field(img, 304, 136, 204, 28, base_idx=0)
    for i in range(312, 500):                  # brass ledger line to write on
        put(img, i, 158, shade(BRASS[0], 0.85))
        put(img, i, 159, shade(BRASS[1], 0.6))

    # Scrollbar track -- every row identical, so it tiles vertically.
    track = [NEAR_BLACK, shade(COAL[0], 0.8), COAL[0], COAL[0],
             COAL[1], COAL[1], COAL[1], COAL[1],
             COAL[0], COAL[0], mix(LIP, COAL[4], 0.5), COAL[4]]
    for j in range(280):
        for i, c in enumerate(track):
            put(img, 304 + i, 168 + j, c)

    # Scrollbar thumb -- 12px caps, uniform middle, so it three-slices.
    cols = [IRON[3], IRON[4], IRON[3], IRON[2], IRON[2], IRON[2],
            IRON[2], IRON[1], IRON[1], IRON[1], IRON[0], NEAR_BLACK]
    for j in range(48):
        for i, c in enumerate(cols):
            put(img, 324 + i, 168 + j, c)
    for i in range(12):
        put(img, 324 + i, 168, BRASS[2])
        put(img, 324 + i, 169, shade(IRON[4], 1.1))
        put(img, 324 + i, 215, BRASS[0])
        put(img, 324 + i, 214, IRON[0])
    for (px, py) in ((324, 168), (335, 168), (324, 215), (335, 215)):
        put(img, px, py, NEAR_BLACK)
    for j in (22, 23, 25, 26):                 # grip notch, inside the middle
        for i in range(2, 10):
            put(img, 324 + i, 168 + j, BRASS[1] if j in (22, 25) else COAL[0])
    save(img, f"{ASSETS}/textures/gui/plaque_assign.png")


# ============================================================== reporting ===

SCREEN_REGIONS = [
    ("PANEL",           0,   0, 352, 416, "nine-slice 16 raw / 8 logical"),
    ("SLOT_FRAME",    356,   4,  40,  40, "item at +4 raw / +2 logical"),
    ("PORTRAIT_FRAME", 404,   4,  32,  32, "face into inner 2,2,28,28 raw"),
    ("X_NORMAL",      356,  48,  28,  28, ""),
    ("X_HOVER",       392,  48,  28,  28, ""),
    ("X_DISABLED",    428,  48,  28,  28, ""),
    ("ICON_HEART",    356,  84,  18,  18, "precedes '20/20'"),
    ("ICON_MOOD",     380,  84,  18,  18, "green mood face"),
    ("ICON_WARNING",  404,  84,  18,  18, "amber caution"),
    ("ICON_ACTIVE",   428,  84,  18,  18, "emerald active bead"),
    ("FOOTER_NORMAL", 356, 108, 144,  36, "oak body, brass edging"),
    ("FOOTER_HOVER",  356, 148, 144,  36, ""),
    ("DIVIDER_RULE",  356, 188, 152,   6, "tile/stretch to any width"),
    ("DIVIDER_DIAMOND", 356, 198, 18,  14, "blit centred over the rule"),
    ("ASSIGN_NORMAL", 356, 216, 152,  36, "3-slice cap 20 raw / 10 logical"),
    ("ASSIGN_HOVER",  356, 256, 152,  36, ""),
    ("ASSIGN_PRESSED", 356, 296, 152, 36, ""),
    ("ASSIGN_DISABLED", 356, 336, 152, 36, ""),
    ("ROW_NORMAL",      0, 416, 300,  48, "portrait +3,+4 logical | X x133"),
    ("ROW_HOVER",       0, 464, 300,  48, "brass accent bar at x+2 raw"),
]

ASSIGN_REGIONS = [
    ("PANEL",          0,   0, 300, 480, "nine-slice 16 raw / 8 logical"),
    ("LIST_WELL",     20,  80, 260, 356, "recessed list area inside PANEL"),
    ("CAND_NORMAL",  304,   4, 204,  40, ""),
    ("CAND_HOVER",   304,  48, 204,  40, "brass accent bar at x+2 raw"),
    ("CAND_DISABLED", 304,  92, 204, 40, "hatched, no accent"),
    ("SEARCH_FIELD", 304, 136, 204,  28, "text at +8 raw; brass ledger line"),
    ("SCROLL_TRACK", 304, 168,  12, 280, "row-uniform: tile vertically"),
    ("SCROLL_THUMB", 324, 168,  12,  48, "3-slice vertically, cap 12 raw"),
]


def print_contract():
    for sheet, regions in (("gui/plaque_screen.png", SCREEN_REGIONS),
                           ("gui/plaque_assign.png", ASSIGN_REGIONS)):
        print(f"\n  {sheet}   512x512  (2x the logical GUI grid)")
        print(f"    {'REGION':<18}{'RAW u  v  w  h':>22}"
              f"{'LOGICAL u  v  w  h':>24}   notes")
        for name, u, v, w, h, note in regions:
            raw = f"{u:>4}{v:>5}{w:>5}{h:>5}"
            log = f"{u // 2:>6}{v // 2:>5}{w // 2:>5}{h // 2:>5}"
            print(f"    {name:<18}{raw:>22}{log:>24}   {note}")


MATERIALS = [
    ("plaque_board", "backboard front + back", "full-face; faint chamfer at "
     "the edge, vertical long grain"),
    ("plaque_board_edge", "backboard narrow edges", "END grain runs vertical; "
     'use "rotation": 90 on the left/right edge faces'),
    ("plaque_brass", "raised inner frame", "seamless (16-periodic); 8px "
     "planished dimples so a 3px lip still reads"),
    ("plaque_iron", "corner brackets", "seamless; peen dashes along +X, "
     'use "rotation": 90 on vertical arms; studs on a 32px pitch'),
    ("plaque_socket", "recessed well, empty", "full-face; four brass clips, "
     "banded cast shadow top/left"),
    ("plaque_plan", "recessed well, plan fitted", "full-face; no frame — the "
     "frame is real geometry now"),
]


def print_materials():
    print("\n  block/ material textures for the 3D model   64x64 each")
    print(f"    {'TEXTURE':<20}{'ELEMENT':<28}notes")
    for name, element, note in MATERIALS:
        print(f"    {name:<20}{element:<28}{note}")


if __name__ == "__main__":
    gen_block_faces()
    gen_item_plaque()
    gen_item_build_plan()
    gen_materials()
    gen_plaque_screen()
    gen_plaque_assign()
    print("\nbuilding plaque art done  "
          "(block/item 64x64 signature resolution, GUI 512x512)")
    print_materials()
    print_contract()
