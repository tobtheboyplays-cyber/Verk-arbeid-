"""Seated tavern patron clips (tavern lane). One Blender process, run headless:

    blender -b --factory-startup --python author_seated.py -- [--fast|--full] [--no-export] [clip ...]

Clips (upper body only; TavernSeatEntity owns root + seated legs):
  SEATED_DRINK       8.0 s loop  ale on the table in the right hand, lift, two gulps, "ahh", set down,
                                 then turns to the neighbour and tells a story with the free hand.
  SEATED_DRINK__V2   8.0 s loop  the toast: mug hoisted high "to the house!", pumped twice, a long
                                 draught, set down with a satisfied thump, then an ordinary sip.
  SEATED_DRINK__V3   8.0 s loop  laugh and slap the table: chuckle, head back, shoulders shaking,
                                 two open-palm slaps (wood hit cues), wipes an eye, sips.
  SLEEPY_NOD         6.0 s loop  closing time: chin on the left fist, the head sinks, the chin
                                 slips off the fist, a startled jerk awake, a look round, settles.
  TABLE_CHEER        3.0 s one-shot, sampled from the SHARED per-table clock (every patron at one
                                 table plays the same seconds): raise, clink over the table centre at
                                 t=1.0 (amethyst hit cue), a big swig, mug down at 2.55.
All start and end on the same seated rest pose (mug on the table), so the runtime can cut
between them on a loop boundary.
Mug = the real item hearthstead:ale on the prop hook (mainhand, hide_real).
"""

import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tavernkit as tk  # noqa: E402
import numpy as np  # noqa: E402
import mcrig  # noqa: E402
import lifekit as lk  # noqa: E402

A = tk.args()
ONLY = [x for x in A["rest"]]
c = tk.c
ACC, ACC2, DEC, DEC3, INOUT, WHIP = tk.ACC, tk.ACC2, tk.DEC, tk.DEC3, tk.INOUT, tk.WHIP

# rest pose values (every clip starts/ends here)
MUG_REST = (-4.6, 2.4, -9.8)        # mug body curve value (table top y=7): beside the face
LH_REST = (3.0, 4.2, -11.0)          # left palm, forearm lying on the table
T0 = dict(torso_x=7.0, torso_y=-3.0, torso_z=0.0, head_x=4.0, head_y=6.0, head_z=1.0)
MUG_PROP = [{"hand": "mainhand", "item": "hearthstead:ale", "from": 0.0, "to": 999.0, "hide_real": True}]


def rest_keys(L, **extra):
    """Rest-valued two-key curves for everything not given."""
    out = {}
    for k, v in T0.items():
        out[k] = [(0.0, v), (L, v)]
    for i, a in enumerate("xyz"):
        out["mug_" + a] = [(0.0, MUG_REST[i]), (L, MUG_REST[i])]
        out["lh_" + a] = [(0.0, LH_REST[i]), (L, LH_REST[i])]
    out.update({"mw": [(0.0, 0.0), (L, 0.0)], "tilt": [(0.0, 0.0), (L, 0.0)],
                "lhh": [(0.0, 0.0), (L, 0.0)], "roll": [(0.0, 0.0), (L, 0.0)]})
    out.update(extra)
    return out


def vec(name, keys):
    """[(t, (x,y,z), *ease)] -> three scalar curves."""
    return {f"{name}_{a}": [(k[0], k[1][i], *k[2:]) for k in keys] for i, a in enumerate("xyz")}


CORNERS = [(x, y, z) for x in (-2, 2) for y in (0, 6) for z in (-2, 2)]
LHH = np.array([1.0, 1.2, -3.2])
MUG_LIFT = -2.3  # mug body curve offset: at rest the mug bottom stands on the tabletop


def table_pen(ch, side):
    w = mcrig.pose_matrices(ch)
    worst = 0.0
    for cn in CORNERS:
        p = mcrig.xform(w[side + "_forearm"], cn)
        if p[2] < tk.TABLE_Z - 0.5 and abs(p[0]) < 7.5:
            worst = max(worst, float(p[1] - tk.TABLE_Y))
    return worst


def clear_table(ch, side, target, pole, prev, iters=4):
    """IK the palm to target; if the forearm sinks into the tabletop, lift the target by the
    penetration and solve again (a forearm resting on the table ends flush on it)."""
    t = np.asarray(target, float).copy()
    for _ in range(iters):
        lk.arm_ik(ch, mcrig.pose_matrices(ch), side, t, pole, prev)
        pen = table_pen(ch, side)
        if pen <= 0.02:
            break
        t[1] -= pen + 0.02


def make_solve(L, breath_period=None, shake=None, lhh=None):
    bp = breath_period or L / 2.0
    lhh = LHH if lhh is None else lhh

    def solve(t):
        t %= L
        prev = solve.prev
        sh = shake(t) if shake else 0.0
        ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": tk.SEAT_ROOT},
              "torso": {"rot": (c("torso_x", t) + 1.6 * sh, c("torso_y", t), c("torso_z", t)),
                        "scale": lk.breath_scale(lk.breath(t, bp, phase=0.1), 0.012)}}
        ch["head"] = {"rot": (c("head_x", t) - 2.5 * sh, c("head_y", t), c("head_z", t))}
        w = mcrig.pose_matrices(ch)
        mw = max(0.0, min(1.0, c("mw", t)))
        table_t = np.array([c("mug_x", t), c("mug_y", t) - MUG_LIFT, c("mug_z", t)])
        mouth = mcrig.xform(w["head"], tk.MOUTH + np.array([0.0, 0.3, -0.4]))
        target = table_t * (1 - mw) + mouth * mw
        point = tk.MUG_BODY * (1 - mw) + tk.MUG_RIM_NEAR * mw
        up = tk.tilt_up(c("tilt", t), c("roll", t))
        solve.err = tk.hold_mug(ch, "right", target, (-0.9, 0.7, 0.35), prev, up=up, point=point)
        for _ in range(3):                      # the mug hand never sinks into the tabletop
            pen = table_pen(ch, "right")
            if pen <= 0.02:
                break
            target = target - np.array([0.0, pen + 0.02, 0.0])
            solve.err = tk.hold_mug(ch, "right", target, (-0.9, 0.7, 0.35), prev, up=up, point=point)
        # left: forearm on the table, or the chin on the fist
        lw = max(0.0, min(1.0, c("lhh", t)))
        w = mcrig.pose_matrices(ch)
        lt = np.array([c("lh_x", t), c("lh_y", t), c("lh_z", t)])
        if lw > 0:
            lt = lt * (1 - lw) + mcrig.xform(w["head"], lhh) * lw
        # elbow out when the forearm lies on the table; elbow down/forward (planted on the
        # table) when the fist comes up to the face - keeps the shoulder clear of gimbal lock
        pole = np.array([0.8, 0.9, 0.25]) * (1 - lw) + np.array([0.25, 1.0, -0.45]) * lw
        clear_table(ch, "left", lt, pole, prev)
        yr = lk.lagged_rate(lambda u: c("torso_y", u % L), t)
        pr = lk.lagged_rate(lambda u: c("torso_x", u % L), t)
        ch["cloak"] = {"rot": lk.cloak(4.0, c("torso_x", t), pr, yr, k_pitch=0.35, k_yaw=0.06)}
        return tk.seated_legs(ch)
    solve.prev = {}
    solve.err = 0.0
    return solve


def mug_checks(solve, table_y=tk.TABLE_Y, side="right"):
    def run(times, samples):
        bottoms, mouth = [], []
        for s in samples:
            w = mcrig.pose_matrices(s)
            bottoms.append(float(tk.mug_point(w, side, tk.MUG_BOTTOM)[1]))
            mouth.append(float(np.linalg.norm(tk.mug_point(w, side, tk.MUG_RIM_NEAR)
                                              - mcrig.xform(w["head"], tk.MOUTH))))
        lowest = max(bottoms)
        return {"mug_bottom_lowest_y": round(lowest, 2), "table_y": table_y,
                "mug_clips_table_px": round(max(0.0, lowest - table_y), 2),
                "rim_to_mouth_min_px": round(min(mouth), 2),
                "arm_into_table_px": arm_clip(samples, table_y), "arm_into_table_rest": arm_clip(samples[:1], table_y),
                # the clink contact, hip-relative (x = settler's left px, up px, forward px): TavernTableMath
                "clink_mug_hip_px": clink_point(samples)}
    return run


def clink_point(samples):
    i = int(round(CLINK * 60))
    if i >= len(samples):
        return None
    w = mcrig.pose_matrices(samples[i])
    m = tk.mug_point(w, "right", tk.MUG_BODY)
    return [round(float(m[0]), 3), round(float(tk.HIP_Y - m[1]), 3), round(float(-m[2]), 3)]


def arm_clip(samples, table_y=tk.TABLE_Y, edge=tk.TABLE_Z):
    """Deepest penetration (px) of either forearm cuboid below the tabletop, over the table."""
    worst = {"right": (0.0, 0.0), "left": (0.0, 0.0)}
    for i, s in enumerate(samples):
        w = mcrig.pose_matrices(s)
        for side in ("right", "left"):
            for cn in CORNERS:
                p = mcrig.xform(w[side + "_forearm"], cn)
                if p[2] < edge - 0.5 and abs(p[0]) < 7.5 and p[1] - table_y > worst[side][0]:
                    worst[side] = (round(float(p[1] - table_y), 2), round(i / 60.0, 2))
    return worst


SCENE = lambda: tk.scene_seated("settler_farmer.png")  # noqa: E731
BASE_KEEP = lambda L: (0.0, L)  # noqa: E731


# =========================================================================== SEATED_DRINK 8.0
def keys_story():
    """SEATED_STORY 8.0 s: a drink (0-2.7 s), then the story told to the table with the free
    hand: beats at 4.45 and 5.05, the PUNCHLINE gesture at 6.0 and a laugh at his own joke.
    Head yaw stays near 0: the runtime turns the head toward the listeners."""
    L = 8.0
    K = rest_keys(L)
    K.update(vec("mug", [(0.0, MUG_REST), (0.70, MUG_REST, *ACC2), (0.86, (-3.4, 2.4, -10.0), *INOUT),
                         (1.30, (-2.6, -1.0, -7.5)), (2.20, (-2.6, -1.0, -7.5), *INOUT),
                         (2.55, (-3.3, 1.6, -9.8), *DEC3), (2.68, MUG_REST), (5.6, MUG_REST),
                         (5.9, (-3.6, 2.2, -10.4)), (6.3, MUG_REST), (8.0, MUG_REST)]))
    K["mw"] = [(0.0, 0.0), (0.86, 0.0, *INOUT), (1.30, 1.0), (2.15, 1.0, *INOUT), (2.50, 0.0), (8.0, 0.0)]
    K["tilt"] = [(0.0, 0.0), (0.95, 0.0, *INOUT), (1.35, 55.0), (1.55, 72.0), (1.70, 64.0, *INOUT),
                 (1.90, 82.0), (2.10, 60.0, *INOUT), (2.45, 4.0), (2.62, 0.0), (5.6, 0.0), (5.9, 8.0),
                 (6.3, 0.0), (8.0, 0.0)]
    K["torso_x"] = [(0.0, 7.0), (0.8, 8.5), (1.35, 3.0), (1.9, 1.0), (2.3, -1.5, *DEC), (2.7, 6.0),
                    (3.4, 7.5), (4.3, 9.5), (5.2, 11.0), (5.6, 8.0), (6.0, 2.0, *DEC), (6.5, 4.0),
                    (7.2, 7.5), (8.0, 7.0)]
    K["torso_y"] = [(0.0, -3.0), (1.2, -1.0), (2.4, -2.0), (3.1, 3.0, *INOUT), (4.45, 6.0), (5.05, -4.0),
                    (5.5, 2.0), (6.0, 0.0), (7.3, 1.0), (8.0, -3.0)]
    K["torso_z"] = [(0.0, 0.0), (4.3, 2.0), (5.5, 3.0), (6.0, -1.0), (7.0, 0.0), (8.0, 0.0)]
    K["head_x"] = [(0.0, 4.0), (0.8, 8.0), (1.35, -9.0), (1.55, -12.0), (1.72, -9.5), (1.90, -14.0),
                   (2.15, -8.0), (2.35, -3.0, *DEC), (2.55, 3.0), (2.70, 7.0, *DEC), (3.1, 3.0),
                   (3.35, 7.5, *DEC), (3.55, 3.0), (4.4, 1.0), (4.8, 6.0, *DEC), (5.1, 0.0), (5.4, 5.0, *DEC),
                   (5.7, -2.0), (6.0, -12.0, *DEC), (6.3, -6.0), (6.6, -10.0), (7.0, 0.0), (7.6, 5.0),
                   (8.0, 4.0)]
    K["head_y"] = [(0.0, 6.0), (0.8, 2.0), (1.4, 0.0), (2.3, -2.0), (3.2, 4.0, *INOUT), (4.45, -8.0),
                   (5.05, 8.0), (5.6, -4.0), (6.0, 0.0), (7.2, 3.0), (8.0, 6.0)]
    K["head_z"] = [(0.0, 1.0), (2.3, -3.0), (2.5, 2.5), (2.7, -1.0), (3.3, 3.0), (4.5, 5.0), (5.5, 4.0),
                   (6.0, -3.0), (6.4, 2.0), (7.0, 1.0), (8.0, 1.0)]
    # the free hand: a little table tap after the drink, then the story (palm up, a point, a wave-off)
    K.update(vec("lh", [(0.0, LH_REST), (2.35, LH_REST), (2.55, (3.4, 3.6, -10.6), *DEC), (2.8, LH_REST),
                        (4.1, LH_REST), (4.45, (6.8, 0.8, -9.8), *DEC3), (4.8, (7.4, -0.6, -10.2)),
                        (5.05, (6.9, 0.4, -9.6), *DEC), (5.3, (7.6, -1.0, -10.4)), (5.55, (9.0, -2.4, -9.0), *DEC3),
                        (5.95, (8.2, -4.2, -7.5), *DEC), (6.25, (8.8, -2.0, -8.5)), (6.7, (6.0, 2.6, -9.6)),
                        (7.1, LH_REST), (8.0, LH_REST)]))
    tk.lk.key(K, cyclic=True)


# =========================================================================== SEATED_DRINK (solo) 8.0
DRUMS = (5.0, 5.15, 5.3, 5.45, 6.2, 6.35, 6.5, 6.65)


def keys_drink():
    """SEATED_DRINK 8.0 s, alone at the table: drink (0-2.7 s), set down, a slow look round the
    room, the free hand drums the tabletop twice (fingers roll 5.0-5.45 and 6.2-6.65)."""
    L = 8.0
    K = rest_keys(L)
    K.update(vec("mug", [(0.0, MUG_REST), (0.70, MUG_REST, *ACC2), (0.86, (-3.4, 2.4, -10.0), *INOUT),
                         (1.30, (-2.6, -1.0, -7.5)), (2.20, (-2.6, -1.0, -7.5), *INOUT),
                         (2.55, (-3.3, 1.6, -9.8), *DEC3), (2.68, MUG_REST), (8.0, MUG_REST)]))
    K["mw"] = [(0.0, 0.0), (0.86, 0.0, *INOUT), (1.30, 1.0), (2.15, 1.0, *INOUT), (2.50, 0.0), (8.0, 0.0)]
    K["tilt"] = [(0.0, 0.0), (0.95, 0.0, *INOUT), (1.35, 55.0), (1.55, 72.0), (1.70, 64.0, *INOUT),
                 (1.90, 82.0), (2.10, 60.0, *INOUT), (2.45, 4.0), (2.62, 0.0), (8.0, 0.0)]
    K["torso_x"] = [(0.0, 7.0), (0.8, 8.5), (1.35, 3.0), (1.9, 1.0), (2.3, -1.5, *DEC), (2.7, 6.0),
                    (3.6, 5.0), (4.8, 8.0), (6.8, 8.5), (8.0, 7.0)]
    K["torso_y"] = [(0.0, -3.0), (2.4, -2.0), (3.4, 10.0, *INOUT), (4.2, 9.0), (4.9, -6.0, *INOUT), (7.2, -5.0),
                    (8.0, -3.0)]
    K["head_x"] = [(0.0, 4.0), (0.8, 8.0), (1.35, -9.0), (1.55, -12.0), (1.72, -9.5), (1.90, -14.0),
                   (2.15, -8.0), (2.35, -3.0, *DEC), (2.55, 3.0), (2.70, 7.0, *DEC), (3.4, 0.0), (4.2, -2.0),
                   (4.9, 10.0), (6.9, 12.0), (7.5, 6.0), (8.0, 4.0)]
    K["head_y"] = [(0.0, 6.0), (0.8, 2.0), (2.3, -2.0), (3.4, 30.0, *INOUT), (4.2, 26.0), (4.9, -18.0, *INOUT),
                   (5.6, -14.0), (7.2, -8.0), (8.0, 6.0)]
    K["head_z"] = [(0.0, 1.0), (2.3, -3.0), (2.5, 2.5), (3.4, 4.0), (4.9, -3.0), (7.2, 2.0), (8.0, 1.0)]
    lh = [(0.0, LH_REST), (4.6, LH_REST), (4.85, (3.2, 3.4, -10.8), *DEC)]
    for i, t in enumerate(DRUMS):
        lh += [(t - 0.07, (3.2 - 0.25 * (i % 4), 3.3, -10.8)), (t, (3.2 - 0.25 * (i % 4), 4.2, -10.9), *DEC)]
    lh += [(7.0, (3.2, 3.6, -10.8)), (7.4, LH_REST), (8.0, LH_REST)]
    K.update(vec("lh", lh))
    tk.lk.key(K, cyclic=True)


# =========================================================================== SEATED_TOAST 8.0
def keys_toast():
    L = 8.0
    K = rest_keys(L)
    HIGH = (-7.6, -1.4, -7.0)
    K.update(vec("mug", [(0.0, MUG_REST), (0.55, MUG_REST, *ACC2), (0.70, (-3.4, 2.4, -9.9)),
                         (1.10, HIGH, *DEC3), (1.28, (-7.2, -0.2, -7.4)), (1.45, (-7.9, -2.2, -6.8)),
                         (1.62, (-7.2, -0.2, -7.4)), (1.80, (-7.9, -2.2, -6.8)), (2.05, (-5.6, 1.0, -8.0)),
                         (2.45, (-2.5, -1.0, -7.4)), (3.60, (-2.5, -1.0, -7.4), *INOUT),
                         (3.95, (-3.4, 2.4, -9.9), *ACC2), (4.05, MUG_REST), (4.9, MUG_REST, *ACC2),
                         (5.05, (-3.4, 2.4, -10.0), *INOUT), (5.45, (-2.6, -1.0, -7.5)),
                         (6.05, (-2.6, -1.0, -7.5), *INOUT), (6.40, (-3.3, 1.8, -9.8), *DEC3),
                         (6.55, MUG_REST), (8.0, MUG_REST)]))
    K["mw"] = [(0.0, 0.0), (2.10, 0.0, *INOUT), (2.45, 1.0), (3.55, 1.0, *INOUT), (3.85, 0.0),
               (5.05, 0.0, *INOUT), (5.45, 1.0), (6.00, 1.0, *INOUT), (6.35, 0.0), (8.0, 0.0)]
    K["tilt"] = [(0.0, 0.0), (0.8, 0.0), (1.1, -8.0), (1.45, 6.0), (1.8, -6.0), (2.1, 0.0, *INOUT),
                 (2.5, 60.0), (2.9, 88.0), (3.3, 98.0), (3.55, 70.0, *INOUT), (3.85, 5.0), (4.0, 0.0),
                 (5.1, 0.0, *INOUT), (5.5, 58.0), (5.8, 74.0), (6.0, 60.0, *INOUT), (6.35, 4.0),
                 (6.5, 0.0), (8.0, 0.0)]
    K["torso_x"] = [(0.0, 7.0), (0.6, 8.5), (1.1, -4.0, *DEC), (1.45, -6.0), (1.8, -5.0), (2.4, 0.0),
                    (3.3, -3.0), (3.9, 7.5, *ACC2), (4.05, 9.0), (4.4, 7.0), (5.1, 8.0), (5.5, 3.0),
                    (6.0, 1.5), (6.5, 7.0), (8.0, 7.0)]
    K["torso_y"] = [(0.0, -3.0), (1.1, -9.0), (1.8, -8.0), (2.5, -2.0), (4.0, -3.0), (6.8, 8.0, *INOUT),
                    (7.4, 7.0), (8.0, -3.0)]
    K["torso_z"] = [(0.0, 0.0), (1.1, 3.0), (1.8, 2.0), (2.5, 0.0), (8.0, 0.0)]
    K["head_x"] = [(0.0, 4.0), (0.6, 7.0), (1.1, -16.0, *DEC), (1.45, -18.0), (1.8, -14.0), (2.3, -6.0),
                   (2.6, -12.0), (3.3, -20.0), (3.6, -10.0, *DEC), (3.95, 8.0), (4.1, 5.0), (5.0, 7.0),
                   (5.5, -9.0), (5.8, -12.0), (6.1, -7.0), (6.5, 5.0), (7.1, 4.0), (7.35, 8.0, *DEC),
                   (7.6, 4.0), (8.0, 4.0)]
    K["head_y"] = [(0.0, 6.0), (1.1, -10.0), (1.8, -12.0), (2.4, 0.0), (4.0, 2.0), (6.6, 18.0, *INOUT),
                   (7.5, 16.0), (8.0, 6.0)]
    K["head_z"] = [(0.0, 1.0), (1.2, -4.0), (1.8, -3.0), (2.4, 0.0), (4.0, 1.0), (8.0, 1.0)]
    # the free hand: a fist pump with the toast, a slap-down with the thump
    K.update(vec("lh", [(0.0, LH_REST), (0.7, LH_REST), (1.1, (8.8, -6.5, -6.0), *DEC3),
                        (1.45, (8.4, -8.0, -5.5)), (1.8, (8.8, -6.2, -6.0)), (2.3, LH_REST),
                        (3.8, LH_REST), (3.92, (3.4, 3.4, -10.6), *ACC2), (4.05, (3.0, 4.2, -11.0)),
                        (8.0, LH_REST)]))
    tk.lk.key(K, cyclic=True)


# =========================================================================== SEATED_LISTEN 8.0
PUNCH = 6.0                   # SEATED_STORY's punchline, same table clock
SLAPS = (6.38, 6.78)


def laugh_shake(t):
    """Shoulder-shaking laughter from the punchline: envelope * 7 Hz bounce (additive, degrees)."""
    env = lk.smoothstep((t - (PUNCH - 0.05)) / 0.3) * (1 - lk.smoothstep((t - 7.1) / 0.6))
    return env * math.sin(2 * math.pi * 7.0 * t)


def keys_listen():
    """SEATED_LISTEN 8.0 s: sips while the teller drinks (0.9-2.4 s), turns in and leans
    forward (3.0), two nods ON the teller's beats (4.45 / 5.05), bursts out laughing ON the
    punchline (6.0): head back, shoulders shaking, two open-palm slaps on the table (6.38 /
    6.78), knuckle to a watering eye, settles. Head yaw is aimed at the teller at runtime."""
    L = 8.0
    K = rest_keys(L)
    K.update(vec("mug", [(0.0, MUG_REST), (0.85, MUG_REST, *ACC2), (1.0, (-3.4, 2.4, -10.0), *INOUT),
                         (1.4, (-2.6, -1.0, -7.5)), (2.0, (-2.6, -1.0, -7.5), *INOUT),
                         (2.35, (-3.3, 1.8, -9.8), *DEC3), (2.5, MUG_REST), (8.0, MUG_REST)]))
    K["mw"] = [(0.0, 0.0), (1.0, 0.0, *INOUT), (1.4, 1.0), (1.95, 1.0, *INOUT), (2.3, 0.0), (8.0, 0.0)]
    K["tilt"] = [(0.0, 0.0), (1.05, 0.0, *INOUT), (1.45, 55.0), (1.7, 70.0), (1.95, 50.0, *INOUT),
                 (2.3, 4.0), (2.45, 0.0), (8.0, 0.0)]
    K["torso_x"] = [(0.0, 7.0), (1.4, 3.0), (2.4, 6.0), (3.0, 11.0, *INOUT), (4.45, 12.0), (5.05, 12.5),
                    (5.8, 12.0), (PUNCH, 12.0), (PUNCH + 0.35, -6.0, *DEC), (SLAPS[0], 12.0, *DEC3),
                    (6.58, 4.0), (SLAPS[1], 13.0, *DEC3), (7.0, 2.0), (7.5, 6.0), (8.0, 7.0)]
    K["torso_y"] = [(0.0, -3.0), (2.4, -2.0), (3.0, 0.0), (PUNCH, 0.0), (6.4, 4.0), (7.0, -2.0), (8.0, -3.0)]
    K["torso_z"] = [(0.0, 0.0), (PUNCH, 0.0), (6.3, -3.0), (6.7, 2.5), (7.2, 0.0), (8.0, 0.0)]
    K["head_x"] = [(0.0, 4.0), (1.0, 8.0), (1.45, -9.0), (1.7, -12.0), (2.0, -8.0), (2.4, 3.0), (3.0, 4.0),
                   (4.25, 2.0), (4.45, 10.0, *DEC), (4.7, 3.0), (4.85, 2.0), (5.05, 9.0, *DEC), (5.3, 3.0),
                   (5.9, 2.0), (PUNCH + 0.3, -22.0, *DEC), (SLAPS[0], 8.0, *DEC3), (6.6, -14.0),
                   (SLAPS[1], 10.0, *DEC3), (7.05, -8.0), (7.5, 6.0), (8.0, 4.0)]
    K["head_y"] = [(0.0, 6.0), (2.4, 2.0), (3.0, 0.0), (7.3, 0.0), (8.0, 6.0)]
    K["head_z"] = [(0.0, 1.0), (3.0, 4.0), (5.9, 4.0), (6.3, -6.0), (6.7, 4.0), (7.2, -4.0), (7.6, 2.0),
                   (8.0, 1.0)]
    UPL = (7.0, -3.0, -8.0)
    DOWN = (3.4, 4.3, -10.8)
    K.update(vec("lh", [(0.0, LH_REST), (PUNCH - 0.1, LH_REST), (PUNCH + 0.18, UPL, *DEC3),
                        (6.28, (6.8, -3.6, -8.2), *WHIP), (SLAPS[0], DOWN, *DEC), (6.5, (5.4, 1.5, -9.4)),
                        (6.66, (6.6, -2.4, -8.4), *WHIP), (SLAPS[1], DOWN, *DEC), (6.95, (3.6, 3.8, -10.6)),
                        (7.1, LH_REST), (8.0, LH_REST)]))
    K["lhh"] = [(0.0, 0.0), (7.0, 0.0, *INOUT), (7.25, 1.0), (7.45, 1.0, *INOUT), (7.75, 0.0), (8.0, 0.0)]
    tk.lk.key(K, cyclic=True)


LHH_EYE = np.array([2.6, -3.6, -5.2])
LHH_MOUTH = np.array([0.6, -1.4, -5.4])     # head space: the back of the hand across the mouth
LHH_CHIN = np.array([0.6, 0.4, -4.8])       # head space: fingertips under the chin


# =========================================================================== SLEEPY_NOD 6.0
def keys_sleepy():
    L = 6.0
    K = rest_keys(L)
    K["lhh"] = [(0.0, 1.0), (2.75, 1.0, *ACC2), (2.95, 0.15), (3.2, 0.0), (4.7, 0.0, *INOUT), (5.4, 1.0),
                (6.0, 1.0)]
    K.update(vec("lh", [(0.0, (3.4, 1.0, -8.0)), (2.8, (3.4, 1.0, -8.0)), (3.05, (3.4, 4.0, -10.6), *DEC3),
                        (3.3, LH_REST), (4.6, LH_REST), (5.4, (3.4, 1.0, -8.0)), (6.0, (3.4, 1.0, -8.0))]))
    K["torso_x"] = [(0.0, 12.0), (2.6, 18.0, *ACC2), (2.95, 21.0), (3.10, 4.0, *DEC3), (3.5, 6.0),
                    (4.6, 8.0), (5.4, 12.0), (6.0, 12.0)]
    K["torso_y"] = [(0.0, -2.0), (2.9, -1.0), (3.2, 2.0), (3.8, -8.0, *INOUT), (4.4, 10.0, *INOUT),
                    (5.0, 3.0), (6.0, -2.0)]
    K["torso_z"] = [(0.0, 3.0), (2.6, 6.0), (2.95, 7.0), (3.15, -1.0), (4.6, 1.0), (6.0, 3.0)]
    K["head_x"] = [(0.0, 10.0), (1.0, 14.0), (1.6, 9.0), (2.6, 22.0, *ACC2), (2.95, 34.0), (3.10, -10.0, *DEC3),
                   (3.35, -4.0), (4.6, 2.0), (5.4, 10.0), (6.0, 10.0)]
    K["head_y"] = [(0.0, 3.0), (2.9, 4.0), (3.2, 0.0), (3.8, -22.0, *INOUT), (4.4, 20.0, *INOUT),
                   (5.0, 6.0), (6.0, 3.0)]
    K["head_z"] = [(0.0, 8.0), (2.6, 12.0), (2.95, 14.0), (3.15, -2.0), (3.5, 1.0), (5.4, 8.0), (6.0, 8.0)]
    tk.lk.key(K, cyclic=True)


# =========================================================================== TABLE_CHEER 3.0 one-shot
CLINK = 1.0
CLINK_PT = (-0.6, -2.5, -12.0)


def keys_cheer():
    K = rest_keys(3.0)
    keys_cheer_raw(K)
    tk.lk.key(K, cyclic=False)


def keys_cheer_raw(K):
    """The plain TABLE_CHEER keys into K (not keyed yet)."""
    L = 3.0
    K.update(vec("mug", [(0.0, MUG_REST), (0.15, (-3.3, 2.4, -10.0), *ACC2), (0.50, (-4.8, -5.0, -9.5), *DEC3),
                         (0.78, (-2.6, -4.0, -11.2), *ACC), (CLINK, CLINK_PT, *DEC), (1.12, (-2.2, -3.2, -12.0)),
                         (1.35, (-3.8, -3.5, -9.5), *INOUT), (1.55, (-2.6, -1.0, -7.5)),
                         (2.2, (-2.6, -1.0, -7.5), *INOUT), (2.45, (-3.3, 1.8, -9.8), *DEC3),
                         (2.55, MUG_REST), (3.0, MUG_REST)]))
    K["mw"] = [(0.0, 0.0), (1.3, 0.0, *INOUT), (1.6, 1.0), (2.15, 1.0, *INOUT), (2.42, 0.0), (3.0, 0.0)]
    K["tilt"] = [(0.0, 0.0), (0.5, -6.0), (CLINK, -14.0), (1.15, 4.0), (1.35, 20.0, *INOUT), (1.65, 70.0),
                 (1.9, 92.0), (2.1, 70.0, *INOUT), (2.42, 4.0), (2.55, 0.0), (3.0, 0.0)]
    K["torso_x"] = [(0.0, 7.0), (0.15, 9.0), (0.5, 2.0), (0.8, 12.0, *ACC), (CLINK, 16.0, *DEC),
                    (1.15, 13.0), (1.5, 3.0), (2.0, -2.0), (2.45, 8.5, *ACC2), (2.6, 9.0), (3.0, 7.0)]
    K["torso_y"] = [(0.0, -3.0), (0.5, -8.0), (CLINK, -2.0), (1.5, -3.0), (3.0, -3.0)]
    K["head_x"] = [(0.0, 4.0), (0.15, 8.0), (0.5, -12.0, *DEC), (0.8, -4.0), (CLINK, -2.0), (1.15, 2.0),
                   (1.55, -8.0), (1.9, -16.0), (2.2, -8.0), (2.5, 6.0), (2.7, 3.0), (3.0, 4.0)]
    K["head_y"] = [(0.0, 6.0), (0.5, -4.0), (CLINK, 0.0), (1.6, -2.0), (3.0, 6.0)]
    K["head_z"] = [(0.0, 1.0), (0.5, -5.0), (0.8, 2.0), (1.2, 0.0), (3.0, 1.0)]
    # left fist pumps once with the "cheers!", slaps down with the mug
    K.update(vec("lh", [(0.0, LH_REST), (0.2, LH_REST), (0.5, (8.6, -5.5, -6.5), *DEC3), (0.8, (7.4, -2.0, -8.0)),
                        (1.3, (5.2, 3.0, -9.2)), (1.6, LH_REST), (2.4, LH_REST), (2.52, (3.4, 3.6, -10.6), *ACC2),
                        (2.62, LH_REST), (3.0, LH_REST)]))


def _cheer_head(K, after):
    """The raise and clink (0-1.3 s) shared by every cheer style; `after` keys the rest."""
    for k in ("mug_x", "mug_y", "mug_z", "mw", "tilt", "torso_x", "torso_y", "head_x", "head_y", "head_z",
              "lh_x", "lh_y", "lh_z"):
        K[k] = [kk for kk in K[k] if kk[0] <= 1.15]
    for k, keys in after.items():
        K[k] = K[k] + keys


def keys_cheer_wipe():
    """TABLE_CHEER style 2: a short swig, mug down at 2.05, then the back of the left hand wipes the
    mouth (2.3-2.75) and a satisfied exhale."""
    L = 3.0
    K = rest_keys(L)
    keys_cheer_raw(K)
    _cheer_head(K, {
        "mug_x": [(1.35, -3.8), (1.5, -2.6), (1.8, -2.6, *INOUT), (1.98, -3.3, *DEC3), (2.05, MUG_REST[0]), (L, MUG_REST[0])],
        "mug_y": [(1.35, -3.5), (1.5, -1.0), (1.8, -1.0, *INOUT), (1.98, 1.8, *DEC3), (2.05, MUG_REST[1]), (L, MUG_REST[1])],
        "mug_z": [(1.35, -9.5), (1.5, -7.5), (1.8, -7.5, *INOUT), (1.98, -9.8, *DEC3), (2.05, MUG_REST[2]), (L, MUG_REST[2])],
        "mw": [(1.3, 0.0, *INOUT), (1.52, 1.0), (1.78, 1.0, *INOUT), (1.98, 0.0), (L, 0.0)],
        "tilt": [(1.3, 12.0, *INOUT), (1.55, 70.0), (1.75, 78.0, *INOUT), (1.98, 4.0), (2.08, 0.0), (L, 0.0)],
        "torso_x": [(1.5, 3.0), (1.8, 1.0), (2.05, 7.0, *ACC2), (2.5, -3.0), (2.8, 5.0), (L, 7.0)],
        "torso_y": [(1.5, -3.0), (2.5, 4.0), (L, -3.0)],
        "head_x": [(1.52, -10.0), (1.75, -13.0), (2.0, 2.0), (2.35, 8.0), (2.6, -4.0, *DEC), (2.85, 3.0), (L, 4.0)],
        "head_y": [(1.6, -2.0), (2.5, 8.0), (L, 6.0)],
        "head_z": [(1.6, 0.0), (2.5, -4.0), (L, 1.0)],
        "lh_x": [(1.3, 5.2), (1.6, LH_REST[0]), (L, LH_REST[0])],
        "lh_y": [(1.3, 3.0), (1.6, LH_REST[1]), (L, LH_REST[1])],
        "lh_z": [(1.3, -9.2), (1.6, LH_REST[2]), (L, LH_REST[2])],
    })
    K["lhh"] = [(0.0, 0.0), (2.15, 0.0, *INOUT), (2.35, 1.0), (2.62, 1.0, *INOUT), (2.85, 0.0), (L, 0.0)]
    tk.lk.key(K, cyclic=False)


def keys_cheer_quick():
    """TABLE_CHEER style 3: one quick sip, the mug set down early (1.8), a lean back and a
    look round the table."""
    L = 3.0
    K = rest_keys(L)
    keys_cheer_raw(K)
    _cheer_head(K, {
        "mug_x": [(1.3, -3.2), (1.45, -2.6), (1.58, -2.6, *INOUT), (1.74, -3.3, *DEC3), (1.8, MUG_REST[0]), (L, MUG_REST[0])],
        "mug_y": [(1.3, -2.0), (1.45, -1.0), (1.58, -1.0, *INOUT), (1.74, 1.8, *DEC3), (1.8, MUG_REST[1]), (L, MUG_REST[1])],
        "mug_z": [(1.3, -9.0), (1.45, -7.5), (1.58, -7.5, *INOUT), (1.74, -9.8, *DEC3), (1.8, MUG_REST[2]), (L, MUG_REST[2])],
        "mw": [(1.28, 0.0, *INOUT), (1.45, 1.0), (1.56, 1.0, *INOUT), (1.72, 0.0), (L, 0.0)],
        "tilt": [(1.28, 10.0, *INOUT), (1.48, 55.0), (1.58, 50.0, *INOUT), (1.74, 4.0), (1.82, 0.0), (L, 0.0)],
        "torso_x": [(1.45, 5.0), (1.8, 8.0, *ACC2), (2.3, -6.0, *INOUT), (2.7, -4.0), (L, 7.0)],
        "torso_y": [(1.5, -3.0), (2.3, 6.0), (2.7, 8.0), (L, -3.0)],
        "head_x": [(1.45, -6.0), (1.8, 6.0), (2.3, -2.0), (L, 4.0)],
        "head_y": [(1.6, 0.0), (2.2, 18.0, *INOUT), (2.6, -10.0, *INOUT), (L, 6.0)],
        "head_z": [(1.6, 0.0), (2.4, 3.0), (L, 1.0)],
        "lh_x": [(1.3, 5.2), (1.6, LH_REST[0]), (L, LH_REST[0])],
        "lh_y": [(1.3, 3.0), (1.6, LH_REST[1]), (L, LH_REST[1])],
        "lh_z": [(1.3, -9.2), (1.6, LH_REST[2]), (L, LH_REST[2])],
    })
    tk.lk.key(K, cyclic=False)


def keys_listen_smile():
    """SEATED_LISTEN style 2: attentive, one soft nod, a sip mid-story, a SMALL smile and a slow
    head shake on the punchline (no slap), then a knowing look."""
    L = 8.0
    K = rest_keys(L)
    K.update(vec("mug", [(0.0, MUG_REST), (2.3, MUG_REST, *ACC2), (2.45, (-3.4, 2.4, -10.0), *INOUT),
                         (2.85, (-2.6, -1.0, -7.5)), (3.35, (-2.6, -1.0, -7.5), *INOUT),
                         (3.7, (-3.3, 1.8, -9.8), *DEC3), (3.85, MUG_REST), (8.0, MUG_REST)]))
    K["mw"] = [(0.0, 0.0), (2.45, 0.0, *INOUT), (2.85, 1.0), (3.3, 1.0, *INOUT), (3.65, 0.0), (8.0, 0.0)]
    K["tilt"] = [(0.0, 0.0), (2.5, 0.0, *INOUT), (2.9, 52.0), (3.2, 62.0, *INOUT), (3.65, 4.0), (3.8, 0.0), (8.0, 0.0)]
    K["torso_x"] = [(0.0, 7.0), (4.2, 9.0), (PUNCH, 8.0), (6.4, 4.0), (7.3, 6.0), (8.0, 7.0)]
    K["torso_y"] = [(0.0, -3.0), (4.2, 0.0), (7.3, 1.0), (8.0, -3.0)]
    K["head_x"] = [(0.0, 4.0), (2.9, -8.0), (3.3, -9.0), (3.8, 3.0), (4.45, 3.0), (4.65, 8.0, *DEC), (4.95, 3.0),
                   (PUNCH, 3.0), (6.3, -3.0), (7.0, 1.0), (8.0, 4.0)]
    K["head_y"] = [(0.0, 6.0), (4.0, 2.0), (PUNCH, 0.0), (6.25, -7.0, *INOUT), (6.55, 6.0, *INOUT),
                   (6.85, -5.0, *INOUT), (7.15, 2.0), (8.0, 6.0)]
    K["head_z"] = [(0.0, 1.0), (4.0, 4.0), (6.2, 5.0), (7.0, -2.0), (8.0, 1.0)]
    tk.lk.key(K, cyclic=True)


def chuckle_shake(t):
    env = lk.smoothstep((t - (PUNCH + 0.05)) / 0.25) * (1 - lk.smoothstep((t - 6.9) / 0.5))
    return 0.55 * env * math.sin(2 * math.pi * 6.0 * t)


def keys_listen_chuckle():
    """SEATED_LISTEN style 3: half listening - glances away round the room, takes a long drink
    through the build-up, a chuckle with shaking shoulders on the punchline, a wave-off."""
    L = 8.0
    K = rest_keys(L)
    K.update(vec("mug", [(0.0, MUG_REST), (4.1, MUG_REST, *ACC2), (4.25, (-3.4, 2.4, -10.0), *INOUT),
                         (4.65, (-2.6, -1.0, -7.5)), (5.5, (-2.6, -1.0, -7.5), *INOUT),
                         (5.85, (-3.3, 1.8, -9.8), *DEC3), (6.0, MUG_REST), (8.0, MUG_REST)]))
    K["mw"] = [(0.0, 0.0), (4.25, 0.0, *INOUT), (4.65, 1.0), (5.45, 1.0, *INOUT), (5.8, 0.0), (8.0, 0.0)]
    K["tilt"] = [(0.0, 0.0), (4.3, 0.0, *INOUT), (4.7, 60.0), (5.2, 84.0), (5.45, 60.0, *INOUT), (5.8, 4.0),
                 (5.95, 0.0), (8.0, 0.0)]
    K["torso_x"] = [(0.0, 7.0), (2.0, 5.0), (4.2, 6.0), (5.0, 2.0), (PUNCH, 6.0), (6.4, 9.0), (7.2, 5.0), (8.0, 7.0)]
    K["torso_y"] = [(0.0, -3.0), (1.6, 8.0, *INOUT), (3.4, 10.0), (4.2, 0.0), (8.0, -3.0)]
    K["head_x"] = [(0.0, 4.0), (1.6, 0.0), (3.4, 2.0), (4.7, -10.0), (5.2, -14.0), (5.6, -6.0), (PUNCH, 4.0),
                   (6.3, 10.0), (6.8, 5.0), (8.0, 4.0)]
    K["head_y"] = [(0.0, 6.0), (1.6, 28.0, *INOUT), (2.6, 24.0), (3.0, -8.0, *INOUT), (3.6, -12.0),
                   (4.1, 2.0), (8.0, 6.0)]
    K["head_z"] = [(0.0, 1.0), (2.0, -3.0), (PUNCH, 2.0), (6.4, -4.0), (7.0, 2.0), (8.0, 1.0)]
    K.update(vec("lh", [(0.0, LH_REST), (6.4, LH_REST), (6.7, (6.6, 0.4, -9.6), *DEC3), (6.9, (7.4, -0.6, -9.2)),
                        (7.2, LH_REST), (8.0, LH_REST)]))
    tk.lk.key(K, cyclic=True)


# =========================================================================== calm + small beats
def keys_idle():
    """SEATED_IDLE 12.0 s loop - the CALM most of an evening is made of: breathing, one small
    posture settle back into the chair, a slow look round the room and back, a slow blink.
    Hands stay put (the mug on the table, the left forearm on the tabletop)."""
    L = 12.0
    K = rest_keys(L)
    K["torso_x"] = [(0.0, 7.0), (3.0, 7.5), (4.4, 5.2, *INOUT), (6.0, 5.0), (8.6, 5.6), (10.4, 6.8, *INOUT), (L, 7.0)]
    K["torso_y"] = [(0.0, -3.0), (3.2, -1.0, *INOUT), (6.0, -1.5), (8.2, -4.5, *INOUT), (10.5, -4.0), (L, -3.0)]
    K["torso_z"] = [(0.0, 0.0), (4.4, 1.2, *INOUT), (8.2, -0.8, *INOUT), (L, 0.0)]
    K["head_x"] = [(0.0, 4.0), (2.4, 3.0), (3.4, 2.0), (5.6, 3.0), (7.0, 5.0), (8.55, 5.0), (8.7, 8.0, *DEC),
                   (8.9, 5.0), (10.2, 4.5), (L, 4.0)]
    K["head_y"] = [(0.0, 6.0), (1.6, 6.0, *INOUT), (3.2, 20.0), (5.4, 17.0, *INOUT), (7.2, -12.0), (9.6, -9.0, *INOUT),
                   (11.0, 4.0), (L, 6.0)]
    K["head_z"] = [(0.0, 1.0), (3.2, 2.0), (7.2, -1.0), (L, 1.0)]
    K.update(vec("lh", [(0.0, LH_REST), (4.4, (3.2, 4.2, -11.0)), (8.2, (2.8, 4.2, -10.8)), (L, LH_REST)]))
    tk.lk.key(K, cyclic=True)


def keys_sip():
    """SEATED_SIP 3.0 s one-shot: one unhurried sip from the ale on the table and back to rest."""
    L = 3.0
    K = rest_keys(L)
    K.update(vec("mug", [(0.0, MUG_REST), (0.2, MUG_REST, *ACC2), (0.36, (-3.4, 2.4, -10.0), *INOUT),
                         (0.8, (-2.6, -1.0, -7.5)), (1.55, (-2.6, -1.0, -7.5), *INOUT),
                         (1.9, (-3.3, 1.6, -9.8), *DEC3), (2.05, MUG_REST), (L, MUG_REST)]))
    K["mw"] = [(0.0, 0.0), (0.36, 0.0, *INOUT), (0.8, 1.0), (1.5, 1.0, *INOUT), (1.85, 0.0), (L, 0.0)]
    K["tilt"] = [(0.0, 0.0), (0.45, 0.0, *INOUT), (0.85, 52.0), (1.15, 70.0), (1.45, 58.0, *INOUT), (1.85, 4.0),
                 (2.0, 0.0), (L, 0.0)]
    K["torso_x"] = [(0.0, 7.0), (0.3, 8.0), (0.85, 3.0), (1.5, 2.0), (2.05, 6.5), (2.6, 7.2), (L, 7.0)]
    K["head_x"] = [(0.0, 4.0), (0.3, 6.0), (0.85, -9.0), (1.2, -12.0), (1.5, -8.0), (1.9, 3.0), (2.2, 5.5, *DEC),
                   (2.5, 3.5), (L, 4.0)]
    K["head_y"] = [(0.0, 6.0), (0.8, 2.0), (1.6, 0.0), (L, 6.0)]
    tk.lk.key(K, cyclic=False)


def keys_chin():
    """SEATED_FIDGET_CHIN 3.0 s one-shot: the free hand comes up and scratches the chin, a small
    thoughtful head tilt, the hand goes back to the table."""
    L = 3.0
    K = rest_keys(L)
    K["lhh"] = [(0.0, 0.0), (0.25, 0.0, *INOUT), (0.75, 1.0), (2.1, 1.0, *INOUT), (2.65, 0.0), (L, 0.0)]
    K["head_x"] = [(0.0, 4.0), (0.8, 1.0), (1.1, 2.5), (1.35, 0.5), (1.6, 2.5), (1.85, 0.5), (2.4, 3.0), (L, 4.0)]
    K["head_z"] = [(0.0, 1.0), (0.8, 5.0), (2.0, 4.0), (2.7, 1.0), (L, 1.0)]
    K["head_y"] = [(0.0, 6.0), (0.9, 12.0), (2.2, 10.0), (L, 6.0)]
    K["torso_x"] = [(0.0, 7.0), (0.8, 8.0), (2.2, 8.0), (L, 7.0)]
    tk.lk.key(K, cyclic=False)


def keys_stretch():
    """SEATED_FIDGET_STRETCH 3.4 s one-shot: leans back in the chair, rolls the neck one way and
    the other, the free shoulder rolls, settles forward again."""
    L = 3.4
    K = rest_keys(L)
    K["torso_x"] = [(0.0, 7.0), (0.4, 7.5), (1.1, -5.0, *INOUT), (2.0, -6.0), (2.9, 6.5, *INOUT), (L, 7.0)]
    K["torso_z"] = [(0.0, 0.0), (1.2, 1.5), (2.0, -1.5), (L, 0.0)]
    K["head_x"] = [(0.0, 4.0), (1.0, -8.0), (1.4, -6.0), (1.8, -4.0), (2.8, 3.0), (L, 4.0)]
    K["head_z"] = [(0.0, 1.0), (1.1, 9.0, *INOUT), (1.7, -8.0, *INOUT), (2.3, 1.0), (L, 1.0)]
    K.update(vec("lh", [(0.0, LH_REST), (0.9, LH_REST, *INOUT), (1.3, (7.6, -2.4, -6.0)), (1.8, (8.2, -3.6, -4.0)),
                        (2.3, (6.8, -0.4, -7.2), *INOUT), (2.9, LH_REST), (L, LH_REST)]))
    tk.lk.key(K, cyclic=False)


def keys_shift():
    """SEATED_FIDGET_SHIFT 2.4 s one-shot: shifts in the seat (a small twist and settle), glances
    down, re-grips the mug."""
    L = 2.4
    K = rest_keys(L)
    K["torso_y"] = [(0.0, -3.0), (0.6, 4.0, *INOUT), (1.3, -5.0, *INOUT), (2.0, -3.0), (L, -3.0)]
    K["torso_x"] = [(0.0, 7.0), (0.6, 4.5), (1.3, 8.5), (2.0, 7.0), (L, 7.0)]
    K["torso_z"] = [(0.0, 0.0), (0.6, 2.0), (1.3, -1.5), (L, 0.0)]
    K["head_x"] = [(0.0, 4.0), (0.7, 12.0), (1.4, 6.0), (L, 4.0)]
    K.update(vec("mug", [(0.0, MUG_REST), (0.9, MUG_REST), (1.1, (-4.4, 2.2, -9.9)), (1.3, MUG_REST), (L, MUG_REST)]))
    tk.lk.key(K, cyclic=False)


def shake_none(t):
    return 0.0


DRINK_SND = {"sound": "minecraft:entity.generic.drink", "volume": 0.22, "pitch_jitter": 0.1}
PUT_SND = {"sound": "minecraft:block.wood.place", "volume": 0.18, "pitch_jitter": 0.08}
CLIPS = {
    "seated_drink": dict(const="SEATED_DRINK", L=8.0, loop=True, keys=keys_drink, shake=None,
                         sounds=[dict(DRINK_SND, t=1.7), dict(PUT_SND, t=2.68)]
                         + [{"t": t, "sound": "minecraft:block.wood.hit", "volume": 0.08, "pitch_jitter": 0.15}
                            for t in DRUMS],
                         note="SEATED_DRINK 8.0 s loop, a patron alone at a table (upper body): drink "
                              "0.86-2.5, mug down 2.68, a slow look round, fingers drum the table."),
    "seated_drink_left": dict(const="SEATED_DRINK_LEFT", L=8.0, loop=True, keys=keys_drink, shake=None,
                              mirror=True, sounds=[dict(DRINK_SND, t=1.7), dict(PUT_SND, t=2.68)],
                              note="SEATED_DRINK mirrored: a left-handed drinker (the mug in the OFFHAND)."),
    "seated_story": dict(const="SEATED_STORY", L=8.0, loop=True, keys=keys_story, shake=None,
                         sounds=[dict(DRINK_SND, t=1.7), dict(PUT_SND, t=2.68)],
                         note="SEATED_STORY 8.0 s (table clock, the teller): drink, then the story; beats "
                              "4.45 / 5.05, punchline 6.0 (SEATED_LISTEN laughs on it). Head yaw aimed "
                              "at the listeners at runtime."),
    "seated_listen": dict(const="SEATED_LISTEN", L=8.0, loop=True, keys=keys_listen, shake=laugh_shake,
                          sounds=[dict(DRINK_SND, t=1.7)]
                          + [{"t": t, "sound": "minecraft:block.wood.hit", "volume": 0.4, "pitch_jitter": 0.06}
                             for t in SLAPS],
                          note="SEATED_LISTEN 8.0 s (table clock, everyone but the teller): sip, lean in, "
                               "nods on 4.45 / 5.05, laughs on the punchline 6.0, slaps the table 6.38 / "
                               "6.78 (wood cues), wipes an eye. Head yaw aimed at the teller at runtime."),
    "seated_toast": dict(const="SEATED_TOAST", L=8.0, loop=True, keys=keys_toast, shake=None,
                         sounds=[dict(DRINK_SND, t=3.0), dict(PUT_SND, t=4.05, volume=0.28), dict(PUT_SND, t=6.55)],
                         note="SEATED_TOAST 8.0 s (table clock): mug hoisted 'to the house' at 1.1, pumped "
                              "twice, a long draught, thumped down 4.05, a plain sip. Every patron of the "
                              "table plays it; the others 0.25 s behind the one who calls it."),
    "seated_listen_smile": dict(const="SEATED_LISTEN_SMILE", L=8.0, loop=True, keys=keys_listen_smile, shake=None,
                                sounds=[dict(DRINK_SND, t=3.1)],
                                note="SEATED_LISTEN style (table clock; the runtime adds a 0.1-0.6 s lag): a "
                                     "sip mid-story, a soft nod on 4.45, a small smile and head shake on the "
                                     "punchline 6.0, no slap."),
    "seated_listen_chuckle": dict(const="SEATED_LISTEN_CHUCKLE", L=8.0, loop=True, keys=keys_listen_chuckle,
                                  shake=chuckle_shake, sounds=[dict(DRINK_SND, t=5.0)],
                                  note="SEATED_LISTEN style: half listening, glances round the room, a long "
                                       "drink through the build-up, a shoulder-shaking chuckle and a wave-off "
                                       "on the punchline."),
    "table_cheer_wipe": dict(const="TABLE_CHEER_WIPE", L=3.0, loop=False, keys=keys_cheer_wipe, shake=None,
                             sounds=[{"t": CLINK, "sound": "minecraft:block.amethyst_block.hit", "volume": 0.4,
                                      "pitch_jitter": 0.15}, dict(PUT_SND, t=2.05, volume=0.22)],
                             note="TABLE_CHEER style: the same raise and contact (1.0 s), a short swig, the "
                                  "mug down at 2.05, the back of the hand wipes the mouth."),
    "table_cheer_quick": dict(const="TABLE_CHEER_QUICK", L=3.0, loop=False, keys=keys_cheer_quick, shake=None,
                              sounds=[{"t": CLINK, "sound": "minecraft:block.amethyst_block.hit", "volume": 0.4,
                                       "pitch_jitter": 0.15}, dict(PUT_SND, t=1.8, volume=0.2)],
                              note="TABLE_CHEER style: the same raise and contact (1.0 s), one quick sip, the "
                                   "mug set down early (1.8), a lean back and a look round."),
    "seated_idle": dict(const="SEATED_IDLE", L=12.0, loop=True, keys=keys_idle, shake=None,
                        note="SEATED_IDLE 12.0 s loop: the calm between everything (breathing, a settle, a "
                             "slow look round and back, a blink). Played on each patron's own phase/speed."),
    "seated_sip": dict(const="SEATED_SIP", L=3.0, loop=False, keys=keys_sip, shake=None,
                       sounds=[dict(DRINK_SND, t=1.15), dict(PUT_SND, t=2.05)],
                       note="SEATED_SIP 3.0 s one-shot: one sip (lift 0.36, drink 0.8-1.5, down 2.05); "
                            "starts and ends on the seated rest pose."),
    "seated_sip_left": dict(const="SEATED_SIP_LEFT", L=3.0, loop=False, keys=keys_sip, shake=None, mirror=True,
                            sounds=[dict(DRINK_SND, t=1.15), dict(PUT_SND, t=2.05)],
                            note="SEATED_SIP mirrored for a left-handed drinker (the mug in the OFFHAND)."),
    "seated_fidget_chin": dict(const="SEATED_FIDGET_CHIN", L=3.0, loop=False, keys=keys_chin, shake=None,
                               note="SEATED_FIDGET_CHIN 3.0 s one-shot: scratches the chin, a thoughtful tilt."),
    "seated_fidget_stretch": dict(const="SEATED_FIDGET_STRETCH", L=3.4, loop=False, keys=keys_stretch, shake=None,
                                  note="SEATED_FIDGET_STRETCH 3.4 s one-shot: leans back, rolls the neck and a "
                                       "shoulder, settles forward."),
    "seated_fidget_shift": dict(const="SEATED_FIDGET_SHIFT", L=2.4, loop=False, keys=keys_shift, shake=None,
                                note="SEATED_FIDGET_SHIFT 2.4 s one-shot: shifts in the seat, re-grips the mug."),
    "sleepy_nod": dict(const="SLEEPY_NOD", L=6.0, loop=True, keys=keys_sleepy, shake=None,
                       note="SLEEPY_NOD 6.0 s loop (seated, closing time / low energy): chin on the left "
                            "fist, the head sinks, slips off at 2.95, startled jerk awake, looks round."),
    "table_cheer": dict(const="TABLE_CHEER", L=3.0, loop=False, keys=keys_cheer, shake=None,
                        sounds=[{"t": CLINK, "sound": "minecraft:block.amethyst_block.hit", "volume": 0.45,
                                 "pitch_jitter": 0.12},
                                dict(DRINK_SND, t=1.9), dict(PUT_SND, t=2.55, volume=0.25)],
                        note="TABLE_CHEER 3.0 s one-shot on the SHARED per-table clock: mugs meet over the "
                             "table centre at t=1.0 (clink cue on the contact frame). The runtime adds a "
                             "torso yaw/lean so every seat's mug converges on the common point "
                             "(TavernTableMath). Starts/ends on the seated rest pose."),
}


def mirrored_drink(solve):
    """Mirror the right-handed pose, then re-seat the LEFT-hand mug: the left-hand item display is
    not an exact mirror of the right one, so on the sip the wrist slide puts the near rim back on
    the lips (blended by the same mouth weight)."""
    m = tk.mirrored(solve)

    def fixed(t):
        ch = m(t)
        mw = max(0.0, min(1.0, c("mw", t % solve_len[0])))
        if mw <= 0.0:
            return ch
        w = mcrig.pose_matrices(ch)
        target = mcrig.xform(w["head"], tk.MOUTH + np.array([0.0, 0.3, -0.4]))
        point = tk.MUG_BODY * (1 - mw) + tk.MUG_RIM_NEAR * mw
        pos = np.array(ch.get("left_item", {}).get("pos", (0.0, 0.0, 0.0)), float)
        for _ in range(3):
            w = mcrig.pose_matrices(ch)
            d = (target - tk.mug_point(w, "left", point)) * mw
            loc = w["left_forearm"][:3, :3].T @ d
            pos = pos + np.array([loc[0], -loc[1], loc[2]])
            n = float(np.linalg.norm(pos))
            if n > tk.MAX_SLIDE:
                pos = pos * (tk.MAX_SLIDE / n)
            ch.setdefault("left_item", {})["pos"] = tuple(pos)
        return ch
    fixed.prev = m.prev
    fixed.inner = solve
    return fixed


solve_len = [8.0]


def main():
    for slug, spec in CLIPS.items():
        if ONLY and slug not in ONLY and slug.split("__")[0] not in ONLY:
            continue
        L = spec["L"]
        solve = make_solve(L, shake=spec["shake"], lhh=LHH_MOUTH if slug == "table_cheer_wipe"
                           else LHH_EYE if slug == "seated_listen" else LHH_CHIN if slug == "seated_fidget_chin"
                           else LHH)
        solve_len[0] = L
        s = dict(spec)
        side = "left" if spec.get("mirror") else "right"
        props = [dict(MUG_PROP[0], hand="offhand")] if spec.get("mirror") else MUG_PROP
        solve = mirrored_drink(solve) if spec.get("mirror") else solve
        s.update(slug=slug, script="author_seated.py", scene=SCENE, solve=solve, bones=tk.UPPER,
                 keep=(0.0, L) if spec["loop"] else (0.0, CLINK, L), props=props,
                 mugs=[(side, [(0.0, L)])], cams=tk.seated_cams, checks=mug_checks(solve, side=side))
        tk.run_clip(s, A)


if __name__ == "__main__":
    main()
