"""BREW_MASH (brewer), authored in Blender -- anim overkill 2026-09-26.

    HS_VARIANT=[|v2|v3] blender -b --factory-startup --python author_brew_mash.py -- [--fast|--full] [--no-export]

The brewer's own motion (it used to borrow the smelter's bellows): working a heavy mash in a
tun with a long mash paddle (display prop hearthstead:prop_mash_paddle, both hands on the shaft).

Contract: 1.60 s loop (32 ticks). Sound (Employment WORK_BREW): POT_STIR at clip tick 16 = 0.80 s,
the paddle blade at the FAR side of the tun, dragging through the thick mash.
Beats: 0.00 near side (weight back, a small gather), 0.00-0.80 the blade is PUSHED round through
the mash (slow, resisted: the torso leans in, knees give, the whole body works the lever),
0.80 far side + slosh (contact), 0.80-1.60 it comes back round lighter and quicker, the torso
straightens, a breath.
__v2 (3.20 s, 2 cycles): the second round goes the other way (contact still at 0.80 / 2.40).
__v3 (4.80 s, 3 cycles) = the brewer's BREAK: after the first round he lifts the paddle out, knocks
it on the rim at 2.40 s (the contact tick: the sound is the knock), runs a finger down the blade,
tastes, thinks (head tilt), nods, and is back stirring for the third round (contact 4.00).
"""
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402
import mcrig  # noqa: E402

BASE = 1.60
CONTACT = 0.80
V = ck.variant()
N = {"": 1, "v2": 2, "v3": 3}[V]
LENGTH = BASE * N
PNG = os.path.join(ck.REPO, "src", "main", "resources", "assets", "hearthstead", "textures", "item",
                   "prop_mash_paddle.png")
BLADE = (12.5, 13.0, 8.0)          # paddle blade centre, item-sprite px
KNOB, NECK = (3.5, 0.5, 8.0), (10.5, 9.5, 8.0)
clip = ck.Clip("BREW_MASH" + ("__" + V.upper() if V else ""), LENGTH, True, "settler_brewer.png",
               feet=((-3.2, 24.0, 2.2), (3.0, 24.0, -1.8)), tool_png=PNG)
clip.hand_props = [{"hand": "mainhand", "item": "hearthstead:prop_mash_paddle", "from": 0.0, "to": LENGTH,
                    "hide_real": True}]
WOOD = (0.42, 0.29, 0.16, 1)
# an open-topped tub: four staved walls, iron hoops, the mash inside
clip.prop("tun_front", (-9, 13, -9), (18, 11, 1), WOOD)
clip.prop("tun_back", (-9, 13, -24), (18, 11, 1), WOOD)
clip.prop("tun_left", (8, 13, -24), (1, 11, 16), WOOD)
clip.prop("tun_right", (-9, 13, -24), (1, 11, 16), WOOD)
clip.prop("hoop_front", (-9.3, 15, -9.3), (18.6, 1, 0.6), (0.3, 0.3, 0.32, 1))
clip.prop("hoop_front2", (-9.3, 21, -9.3), (18.6, 1, 0.6), (0.3, 0.3, 0.32, 1))
clip.prop("mash", (-8, 15.5, -23), (16, 8.5, 14), (0.74, 0.56, 0.26, 1))
C = np.array([0.0, 15.0, -15.5])             # the stirring point in the mash
RIM = np.array([-1.0, 12.4, -8.8])          # near rim top (the knock)


def tip_at(theta_deg, depth=0.0):
    a = math.radians(theta_deg)
    return C + np.array([3.8 * math.cos(a), depth, -2.8 * math.sin(a)])


def palm_for(tip):
    """The fist sits up and back from the blade, levering about the rim (moves against it)."""
    return np.array([-2.0, 4.6, -6.6]) - 0.30 * np.array([tip[0] - C[0], 0.0, tip[2] - C[2]])


def haft(tip, palm):
    """The shaft leans down into the tun, following the blade round a little."""
    d = np.array([0.35 * (tip[0] - C[0]) / 3.8, 0.80, -0.58 - 0.08 * (tip[2] - C[2]) / 2.8])
    return d / np.linalg.norm(d)


# ---- one stir round: theta keys (deg) over a cycle; the far side (-90) is the contact
ROUND_CW = [(0.00, 90.0, "inout"), (0.40, 0.0, "linear"), (CONTACT, -90.0, "decel"), (1.20, -180.0, "accel"),
            (BASE, -270.0, None)]
ROUND_CCW = ROUND_CW   # (v2 keeps the direction: counter-rounds crossed the forearms)


def body_round(t0, K, strong=1.0):
    """Body keys for one round starting at t0: lean in on the push, give at the contact."""
    def add(p, pts):
        K.setdefault(p, [])
        K[p] += [(t0 + q[0], q[1], *q[2:]) for q in pts]
    add("torso_x", [(0.0, 9.0, "inout"), (0.08, 7.0, "inout"), (0.55, 15.0 * strong, "inout"),
                    (CONTACT, 18.0 * strong, "decel"), (1.05, 12.0, "inout"), (1.40, 8.0, "inout")])
    add("root_y", [(0.0, -0.6, "inout"), (0.30, -0.9, "inout"), (CONTACT, -1.6 * strong, "decel"),
                   (1.10, -0.7, "inout"), (1.45, -0.5, "inout")])
    add("root_z", [(0.0, 0.6, "inout"), (0.10, 0.9, "inout"), (CONTACT, -1.5 * strong, "decel"),
                   (1.30, 0.8, "inout")])
    add("sp_breath", [(0.0, 0.0, "inout"), (1.25, 0.0, "inout"), (1.45, 1.0, "inout")])
    add("head_nod", [(0.0, -1.0, "inout"), (CONTACT, 4.0, "decel"), (1.30, -2.0, "inout")])


K = {"root_x": [(0.0, 0.0)], "root_yaw": [(0.0, 0.0)], "torso_z": [(0.0, 0.0)], "head_roll": [(0.0, 0.0)],
     "cloak_add": [(0.0, 0.0)], "head_yaw": [(0.0, 0.0)]}
rounds = {"": [ROUND_CW], "v2": [ROUND_CW, ROUND_CCW], "v3": [ROUND_CW, None, ROUND_CW]}[V]
theta = []
for i, r in enumerate(rounds):
    t0 = i * BASE
    if r is None:
        continue
    body_round(t0, K, strong=1.0 if i != 1 else 1.12)
    theta += [(t0 + k[0], k[1], k[2]) for k in r[:-1]]
theta.append((LENGTH, [r for r in rounds if r][-1][-1][1], None))

# the stir follows the blade round the tun: the torso twists after it, the hips sway
tw, sw = [], []
for t, th, e in theta:
    a = math.radians(th)
    tw.append((t, 7.0 * math.cos(a), "inout"))
    sw.append((t, 0.5 * math.cos(a), "inout"))
K["torso_y"] = tw
K["root_x"] = sw
if V == "v2":
    K["head_yaw"] = [(0.0, 0.0), (BASE + 0.95, 0.0, "inout"), (BASE + 1.15, -26.0, "inout"),
                     (BASE + 1.40, -22.0, "inout"), (2 * BASE - 0.02, 0.0, "inout")]
    K["head_nod"] = [k for k in K["head_nod"]]
if V == "v3":
    # ---- the BREAK (1.60 - 3.20): lift out, knock on the rim at 2.40 (contact), taste, nod
    b0 = BASE
    K["torso_x"] += [(b0 + 0.0, 13.0, "inout"), (b0 + 0.35, 6.0, "inout"), (b0 + 0.72, 14.0, "decel"),
                     (b0 + 0.80, 15.0, "inout"), (b0 + 1.02, 4.0, "inout"), (b0 + 1.30, 2.0, "inout"),
                     (b0 + 1.45, 11.0, "inout")]
    K["root_y"] += [(b0 + 0.0, -0.6, "inout"), (b0 + 0.78, -1.1, "decel"), (b0 + 1.05, -0.3, "inout"),
                    (b0 + 1.45, -0.6, "inout")]
    K["root_z"] += [(b0 + 0.0, 0.2, "inout"), (b0 + 1.45, 0.2, "inout")]
    K["sp_breath"] += [(b0 + 0.0, 0.0, "inout"), (b0 + 1.1, 0.0, "inout"), (b0 + 1.3, 1.0, "inout"),
                       (b0 + 1.5, 0.0, "inout")]
    K["head_nod"] += [(b0 + 0.0, 0.0, "inout"), (b0 + 0.78, 4.0, "decel"), (b0 + 1.0, -4.0, "inout"),
                      (b0 + 1.12, -2.0, "inout"), (b0 + 1.22, 6.0, "inout"), (b0 + 1.30, -1.0, "inout"),
                      (b0 + 1.38, 5.0, "inout"), (b0 + 1.55, 4.0, "inout")]
    K["head_roll"] = [(0.0, 0.0), (b0 + 0.95, 0.0, "inout"), (b0 + 1.08, 8.0, "inout"), (b0 + 1.2, 0.0, "inout"),
                      (LENGTH, 0.0)]
    K["head_yaw"] = [(0.0, 0.0), (b0 + 0.9, 0.0, "inout"), (b0 + 1.05, -6.0, "inout"), (b0 + 1.25, 0.0, "inout"),
                     (LENGTH, 0.0)]
# sort + close every curve
for p, ks in K.items():
    ks.sort(key=lambda k: k[0])
    ded = []
    for k in ks:
        if ded and abs(ded[-1][0] - k[0]) < 1e-6:
            ded[-1] = k
        else:
            ded.append(k)
    if ded[-1][0] < LENGTH - 1e-6:
        ded.append((LENGTH, ded[0][1]))
    K[p] = ded
clip.cycles, clip.base_length = 1, LENGTH       # keys are authored over the whole clip
clip.keys(K)
clip.key_vec("look", [(0.0, tuple(C + np.array([0.0, -6.0, -6.0])))])

# ---- the paddle: tool goals (blade point + haft direction + fist)
goals = []
for t, th, e in theta[:-1]:
    tip = tip_at(th)
    palm = palm_for(tip)
    goals.append((t, tip, haft(tip, palm), e, None))
    # in-between keys every 0.2 s so the blade stays IN the mash round the circle
seg = []
for (t0, th0, e0), (t1, th1, _) in zip(theta[:-1], theta[1:]):
    tm, thm = 0.5 * (t0 + t1), 0.5 * (th0 + th1)
    tip = tip_at(thm)
    palm = palm_for(tip)
    seg.append((tm, tip, haft(tip, palm), "inout", None))
if V == "v3":
    b0 = BASE
    goals = [g for g in goals if not (b0 - 1e-6 <= g[0] <= 2 * BASE + 1e-6)]
    seg = [g for g in seg if not (b0 - 1e-6 <= g[0] <= 2 * BASE + 1e-6)]
    out = np.array([-1.4, 3.0, -15.0])
    brk = [
        (b0 + 0.00, tip_at(90.0), haft(tip_at(90.0), palm_for(tip_at(90.0))), "inout", palm_for(tip_at(90.0))),
        (b0 + 0.35, out + np.array([0.0, 1.5, -0.5]), (0.05, 0.75, -0.66), "inout", np.array([-2.4, 3.4, -8.2])),
        (b0 + 0.66, RIM + np.array([0.0, -2.2, 0.0]), (0.0, 0.6, -0.8), "accel", np.array([-2.6, 3.8, -8.0])),
        (b0 + 0.80, RIM, (0.0, 0.7, -0.72), "out", np.array([-2.6, 4.4, -7.8])),               # KNOCK (contact)
        (b0 + 0.90, RIM + np.array([0.0, -0.8, 0.0]), (0.0, 0.66, -0.75), "inout", np.array([-2.6, 4.0, -7.9])),
        (b0 + 1.35, RIM + np.array([0.0, -0.6, 0.0]), (0.0, 0.66, -0.75), "inout", np.array([-2.6, 4.0, -7.9])),
        (b0 + 1.48, tip_at(90.0) + np.array([0.0, -3.0, 0.0]), (0.05, 0.8, -0.6), "inout", None),
        (2 * BASE, tip_at(90.0), haft(tip_at(90.0), palm_for(tip_at(90.0))), "inout", palm_for(tip_at(90.0))),
    ]
    goals = sorted(goals + brk, key=lambda g: g[0])
allg = sorted(goals + seg, key=lambda g: g[0])
LOG = clip.arm_tool_poses("right", allg, BLADE, knob=KNOB, neck=NECK, wrist=True, avoid_head=1.0,
                          wrist_lim=(110.0, 35.0, 40.0), seed=(-50.0, 5.0, 5.0, -50.0, 0.0))
print("GOALS", [(g["t"], g["cost"]) for g in LOG])


# ---- left hand: on the shaft below the right fist (live IK); in the v3 break it runs a finger
# down the blade (2.00), tastes it at the mouth (2.20-2.55) and comes back to the shaft
def item_world(t):
    ch = clip.body(t)
    ch["right_arm"] = {"rot": (ck.c("right_ax", t), ck.c("right_ay", t), ck.c("right_az", t))}
    ch["right_forearm"] = {"rot": (ck.c("right_el", t), ck.c("right_tw", t), 0.0)}
    ch["right_item"] = {"rot": (ck.c("right_wx", t), ck.c("right_wy", t), ck.c("right_wz", t))}
    w = mcrig.pose_matrices(ch)
    return w, mcrig.item_in_hand_matrix(w["right_forearm"], True, w["right_item"])


SHAFT = (5.2, 3.0, 8.0)          # up the shaft, just behind the right fist


def left_target(t, world):
    w, it = item_world(t)
    shaft = mcrig.xform(it, SHAFT)
    if V != "v3":
        return shaft
    b0 = BASE
    blade = mcrig.xform(it, BLADE)
    mouth = mcrig.xform(w["torso"], (-1.5, -10.5, -9.5))
    via = mcrig.xform(w["torso"], (5.0, -6.0, -10.0))         # out to the side, below the chin
    keys = [(b0 + 0.84, 0.0), (b0 + 0.98, 1.0), (b0 + 1.04, 1.0), (b0 + 1.12, 3.0), (b0 + 1.22, 2.0),
            (b0 + 1.34, 2.0), (b0 + 1.43, 3.0), (b0 + 1.52, 0.0)]
    pos = [shaft, blade + np.array([1.4, -1.0, 1.0]), mouth, via]
    if t <= keys[0][0] or t >= keys[-1][0]:
        return shaft
    for (ta, a), (tb, b) in zip(keys, keys[1:]):
        if ta <= t <= tb:
            u = ck.smoothstep((t - ta) / max(tb - ta, 1e-6))
            return pos[int(a)] * (1 - u) + pos[int(b)] * u
    return shaft


clip.arm_live("left", left_target, (0.75, 0.6, 0.25))
clip.head_w = (0.25, 0.4)
clip.head_limit = (28.0, 40.0)


def breath(ch, t, world):
    b = ck.c("sp_breath", t)
    if abs(b) > 1e-4:
        tr = ch["torso"]["rot"]
        ch["torso"]["rot"] = (tr[0] - 3.0 * b, tr[1], tr[2])


clip.extra = breath


def recolour_sprite():
    import bpy
    ob = bpy.data.objects.get("mesh:axe")
    if ob is None:
        return
    me = ob.data
    col = me.color_attributes.get("Col")
    mats = {}
    me.materials.clear()
    for poly in me.polygons:
        cc = tuple(round(v, 2) for v in col.data[poly.loop_indices[0]].color)
        if cc not in mats:
            m = bpy.data.materials.new(f"sprite_{len(mats)}")
            m.diffuse_color = cc
            m.use_nodes = False
            me.materials.append(m)
            mats[cc] = len(mats)
        poly.material_index = mats[cc]


recolour_sprite()
contacts_t = [CONTACT + i * BASE for i in range(N)]
clip.run(contacts=[(f"blade_far_{i + 1}", tc, "right_tool", (tip_at(-90.0) if not (V == "v3" and i == 1) else RIM))
                   for i, tc in enumerate(contacts_t)],
         keep=tuple(sorted({g[0] for g in allg})),
         meta={"contract": f"BREW_MASH{('__' + V) if V else ''} {LENGTH:.2f} s loop; POT_STIR at clip tick 16 "
                           f"(t=0.80 s) of every 32-tick round: the paddle blade at the far side of the tun"
                           + ("; round 2 is the break: knock on the rim at 2.40 s, finger-taste, nod" if V == "v3" else "")})
