"""Innkeeper clips (tavern lane). One Blender process, run headless:

    blender -b --factory-startup --python author_innkeeper.py -- [--fast|--full|--stills] [--no-export] [clip ...]

Remakes (same keys and lengths as before):
  IDLE_INNKEEPER      4.5 s  polishes an ale mug (the real hearthstead:ale item, offhand) with the
                             free hand, holds it up to the light, huffs on it, polishes on.
  IDLE_INNKEEPER__V2  4.5 s  hums while polishing: sways on the beat, taps the right heel.
  IDLE_INNKEEPER__V3  6.0 s  surveys the common room, spots a regular, raises the mug and nods.
New:
  IDLE_INNKEEPER__V4  6.0 s  a long day: rolls the neck, hand to the small of the back, leans
                             back with a sigh, straightens.
  COUNTER_WIPE        4.0 s  leaning over a full-block bar counter (edge 8 px ahead, top 16 px up):
                             left hand braced, right palm wipes two circles and one long sweep.
  ALE_POUR            4.0 s  at an ale tap on a barrel/counter one block up and one block ahead:
                             mug under the spout (offhand), pulls the handle, watches it fill,
                             closes it, lifts the full mug to eye level, sets it on the counter.
  SERVE_CARRY         2.0 s  TORSO/HEAD/CLOAK only, layered over the walk while the host carries
                             real service cargo (hands stay on the physical plate/glass IK).
Every innkeeper idle variant starts and ends on the base rest pose (mug at the belt).
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
ONLY = list(A["rest"])
c = tk.c
ACC, ACC2, DEC, DEC3, INOUT, WHIP = tk.ACC, tk.ACC2, tk.DEC, tk.DEC3, tk.INOUT, tk.WHIP
TEX = "settler_innkeeper.png"
FEET = (np.array([-3.0, 24.0, 0.4]), np.array([3.0, 24.0, -0.4]))

# rest pose (torso/head degrees, mug body model px, right palm model px)
MUG0 = (2.0, 6.2, -7.2)
POLISH = (2.0, 4.3, -7.2)     # the rag hand turning inside the mug's rim
RH0 = (3.1, 4.3, -7.2)
R0 = dict(root_x=0.4, root_y=-0.35, torso_x=3.0, torso_y=-4.0, torso_z=0.0, head_x=14.0, head_y=-6.0,
          head_z=2.0, rhw=1.0, tilt=0.0, roll=0.0, foot_r=0.0)
MUG_PROP = [{"hand": "offhand", "item": "hearthstead:ale", "from": 0.0, "to": 999.0}]


def vec(name, keys):
    return {f"{name}_{a}": [(k[0], k[1][i], *k[2:]) for k in keys] for i, a in enumerate("xyz")}


def rest_keys(L, mug=MUG0, rh=RH0, r0=R0):
    out = {k: [(0.0, v), (L, v)] for k, v in r0.items()}
    out.update(vec("mug", [(0.0, mug), (L, mug)]))
    out.update(vec("rh", [(0.0, rh), (L, rh)]))
    return out


def circles(t0, n, period, centre, r, plane="xy", steps=6, ease=None):
    """Palm points on n circles (model space)."""
    out = []
    for i in range(n * steps):
        a = 2 * math.pi * i / steps
        dx, dy = r * math.cos(a), r * math.sin(a)
        p = (centre[0] + dx, centre[1] + dy, centre[2]) if plane == "xy" else \
            (centre[0] + dx, centre[1], centre[2] + dy)
        out.append((t0 + i * period / steps, p))
    return out


def make_stand(L, left="mug", right="ik", feet=FEET, knee_out=0.12, cloak0=2.0, pole_r=(-0.9, 0.8, 0.3),
               pole_l=(0.9, 0.8, 0.3), heel=False, hand_space_rh=False):
    """Standing solver: planted feet (IK), hips from root_x/root_y, torso/head, breathing scale,
    left hand = the mug (wrist bone keeps it upright) or IK, right hand IK blended by rhw
    (0 = hanging rest arm)."""

    def solve(t):
        t %= L
        prev = solve.prev
        ch = {"root": {"rot": (0.0, c("root_yaw", t), c("root_roll", t)),
                       "pos": (c("root_x", t), c("root_y", t), c("root_z", t))},
              "torso": {"rot": (c("torso_x", t), c("torso_y", t), c("torso_z", t)),
                        "scale": lk.breath_scale(lk.breath(t, L / max(1, round(L / 3.0)), phase=0.2), 0.014)}}
        ch["head"] = {"rot": (c("head_x", t), c("head_y", t), c("head_z", t))}
        w = mcrig.pose_matrices(ch)
        if left == "mug":
            up = tk.tilt_up(c("tilt", t), c("roll", t))
            tk.hold_mug(ch, "left", (c("mug_x", t), c("mug_y", t), c("mug_z", t)), pole_l, prev, up=up)
        else:
            lk.arm_ik(ch, w, "left", (c("lh_x", t), c("lh_y", t), c("lh_z", t)), pole_l, prev)
        rw = max(0.0, min(1.0, c("rhw", t)))
        w = mcrig.pose_matrices(ch)
        lk.arm_ik(ch, w, "right", (c("rh_x", t), c("rh_y", t), c("rh_z", t)), pole_r, prev)
        if rw < 1.0:
            hang = {"right_arm": (-4.0 + c("rarm_x", t), 0.0, 4.0), "right_forearm": (-10.0, 0.0, 0.0)}
            for b, v in hang.items():
                cur = ch[b]["rot"]
                ch[b]["rot"] = tuple(a * rw + h * (1 - rw) for a, h in zip(cur, v))
        f_r = np.array(feet[0]) + np.array([0.0, -c("foot_r", t), -0.6 * c("foot_r", t)])
        lk.leg_ik(ch, (f_r, feet[1]), prev, knee_out=knee_out)
        pr = lk.lagged_rate(lambda u: c("torso_x", u % L), t)
        yr = lk.lagged_rate(lambda u: c("torso_y", u % L), t)
        vy = lk.lagged_rate(lambda u: c("root_y", u % L), t, lag=0.03)
        ch["cloak"] = {"rot": lk.cloak(cloak0, c("torso_x", t), pr, yr, root_vy=vy, k_pitch=0.4, k_yaw=0.06)}
        return ch
    solve.prev = {}
    return solve


def feet_check(feet=FEET):
    def run(times, samples):
        slip = 0.0
        for s in samples:
            w = mcrig.pose_matrices(s)
            slip = max(slip, float(np.linalg.norm(lk.sole(w, "left") - feet[1])))
        return {"left_sole_slip_px": round(slip, 3)}
    return run


# =========================================================================== IDLE_INNKEEPER 4.5
def keys_base():
    L = 4.5
    K = rest_keys(L)
    pol = circles(0.0, 2, 0.9, POLISH, 1.1, plane="xz")
    K.update(vec("rh", pol + [(1.85, (-0.6, 2.2, -8.6)), (2.05, (-4.0, 5.5, -4.5), *INOUT),
                              (3.35, (-4.0, 5.5, -4.5)), (3.75, (-1.2, 3.4, -8.2), *DEC), (4.1, RH0), (L, RH0)]))
    K["rhw"] = [(0.0, 1.0), (1.9, 1.0, *INOUT), (2.3, 0.0), (3.3, 0.0, *INOUT), (3.8, 1.0), (L, 1.0)]
    K.update(vec("mug", [(0.0, MUG0), (1.8, MUG0, *INOUT), (2.35, (2.4, -3.2, -9.8), *DEC3), (2.9, (2.0, -3.6, -10.0)),
                         (3.1, (1.4, -2.4, -7.6), *DEC), (3.35, (1.6, -2.6, -8.2)), (3.9, MUG0, *INOUT), (L, MUG0)]))
    K["roll"] = [(0.0, 0.0), (2.35, 0.0), (2.6, 14.0), (2.85, -12.0), (3.05, 0.0), (L, 0.0)]
    K["tilt"] = [(0.0, 0.0), (2.4, -6.0), (2.9, -10.0), (3.3, 0.0), (L, 0.0)]
    K["head_x"] = [(0.0, 14.0), (0.45, 16.0), (0.9, 13.0), (1.35, 16.0), (1.8, 14.0), (2.35, -4.0, *DEC),
                   (2.9, -6.0), (3.1, 2.0), (3.25, 6.0, *DEC), (3.4, 2.0), (3.9, 14.0), (L, 14.0)]
    K["head_y"] = [(0.0, -6.0), (2.3, -8.0), (2.9, -12.0), (3.4, -6.0), (L, -6.0)]
    K["head_z"] = [(0.0, 2.0), (2.4, 6.0), (2.85, -4.0), (3.2, 2.0), (L, 2.0)]
    K["torso_x"] = [(0.0, 3.0), (2.3, -2.5), (2.9, -3.0), (3.15, 4.0, *DEC), (3.4, 1.5), (3.9, 3.0), (L, 3.0)]
    K["torso_y"] = [(0.0, -4.0), (0.9, -3.0), (1.8, -4.5), (2.6, -9.0), (3.4, -5.0), (L, -4.0)]
    K["root_x"] = [(0.0, 0.4), (1.2, 0.7), (2.6, -0.3), (3.6, -0.4), (L, 0.4)]
    K["root_y"] = [(0.0, -0.35), (2.4, -0.2), (3.15, -0.55), (3.6, -0.35), (L, -0.35)]
    tk.lk.key(K, cyclic=True)


# =========================================================================== __V2 humming 4.5
BEAT = 0.75


def keys_hum():
    L = 4.5
    K = rest_keys(L)
    pol = circles(0.0, 5, BEAT, POLISH, 1.1, plane="xz")
    K.update(vec("rh", pol + [(L, RH0)]))
    K["root_x"] = [(i * BEAT, 0.4 + (0.9 if i % 2 else -0.3), INOUT[0], INOUT[1]) for i in range(6)] + [(L, 0.4)]
    K["root_x"][0] = (0.0, 0.4)
    K["root_y"] = [(0.0, -0.35)] + [(i * BEAT + 0.36, -0.75 if i % 2 else -0.5) for i in range(6)] + [(L, -0.35)]
    K["torso_z"] = [(0.0, 0.0)] + [(i * BEAT + 0.4, 3.0 if i % 2 else -3.0) for i in range(6)] + [(L, 0.0)]
    K["head_z"] = [(0.0, 2.0)] + [(i * BEAT + 0.45, 8.0 if i % 2 else -4.0) for i in range(6)] + [(L, 2.0)]
    K["head_x"] = [(0.0, 14.0)] + [(i * BEAT + 0.3, 10.0 if i % 2 else 16.0) for i in range(6)] + [(L, 14.0)]
    K["head_y"] = [(0.0, -6.0), (1.5, -14.0), (3.0, 4.0), (L, -6.0)]
    K["foot_r"] = [(0.0, 0.0)]
    for tb in (1.5, 2.25, 3.0, 3.75):
        K["foot_r"] += [(tb - 0.22, 0.0, *ACC2), (tb - 0.1, 1.1, *DEC), (tb, 0.0)]
    K["foot_r"] += [(L, 0.0)]
    tk.lk.key(K, cyclic=True)


# =========================================================================== __V3 survey + greet 6.0
def keys_survey():
    L = 6.0
    K = rest_keys(L)
    pol = circles(0.0, 1, 0.9, POLISH, 1.1, plane="xz")
    K.update(vec("rh", pol + [(1.0, RH0), (1.2, (-4.0, 5.5, -4.5), *INOUT), (4.9, (-4.0, 5.5, -4.5)),
                              (5.4, RH0, *DEC), (L, RH0)]))
    K["rhw"] = [(0.0, 1.0), (0.95, 1.0, *INOUT), (1.4, 0.0), (4.8, 0.0, *INOUT), (5.3, 1.0), (L, 1.0)]
    RAISE = (6.0, -7.6, -6.0)
    K.update(vec("mug", [(0.0, MUG0), (3.35, MUG0, *ACC2), (3.75, RAISE, *DEC3), (4.25, (5.8, -7.0, -6.2)),
                         (4.75, MUG0, *INOUT), (L, MUG0)]))
    K["tilt"] = [(0.0, 0.0), (3.4, 0.0), (3.75, -10.0), (4.2, -6.0), (4.7, 0.0), (L, 0.0)]
    K["head_y"] = [(0.0, -6.0), (1.1, -6.0, *INOUT), (1.8, 38.0), (2.4, 34.0, *INOUT), (3.1, -30.0),
                   (3.45, -26.0), (4.6, -24.0, *INOUT), (5.4, -6.0), (L, -6.0)]
    K["head_x"] = [(0.0, 14.0), (1.1, 12.0), (1.8, 2.0), (2.4, 4.0), (3.1, 0.0), (3.6, -4.0), (3.85, 8.0, *DEC),
                   (4.1, 0.0), (4.3, 5.0, *DEC), (4.6, 2.0), (5.4, 14.0), (L, 14.0)]
    K["head_z"] = [(0.0, 2.0), (1.8, -3.0), (3.1, 4.0), (3.9, 6.0), (4.6, 3.0), (L, 2.0)]
    K["torso_y"] = [(0.0, -4.0), (1.2, -4.0, *INOUT), (1.9, 14.0), (2.4, 12.0, *INOUT), (3.2, -16.0),
                    (4.7, -14.0, *INOUT), (5.5, -4.0), (L, -4.0)]
    K["torso_x"] = [(0.0, 3.0), (1.8, 0.0), (3.2, -1.0), (3.8, -3.0), (4.1, 2.0, *DEC), (4.6, 1.0), (5.5, 3.0), (L, 3.0)]
    K["root_x"] = [(0.0, 0.4), (1.9, -0.5), (3.3, 0.8), (4.8, 0.7), (L, 0.4)]
    K["root_yaw"] = [(0.0, 0.0), (1.9, 6.0), (3.3, -7.0), (4.8, -6.0), (L, 0.0)]
    tk.lk.key(K, cyclic=True)


# =========================================================================== __V4 a long day 6.0
def keys_longday():
    L = 6.0
    K = rest_keys(L)
    BACK = (-3.4, 9.6, 3.2)          # right palm at the small of the back
    K.update(vec("rh", [(0.0, RH0), (1.9, RH0, *INOUT), (2.3, (-6.0, 7.6, 0.2)), (2.6, BACK, *DEC),
                        (3.3, (-3.2, 9.0, 3.3)), (3.6, BACK), (4.3, BACK, *INOUT), (4.75, (-5.2, 6.5, -2.8)),
                        (5.2, RH0, *DEC), (L, RH0)]))
    K["head_z"] = [(0.0, 2.0), (0.5, 2.0, *INOUT), (0.9, 16.0), (1.2, 4.0), (1.5, -14.0, *INOUT), (1.9, 2.0),
                   (3.0, 3.0), (L, 2.0)]
    K["head_x"] = [(0.0, 14.0), (0.5, 12.0), (0.9, 8.0), (1.2, 18.0), (1.5, 8.0), (1.9, 12.0), (2.8, -12.0, *INOUT),
                   (3.4, -16.0), (4.2, -6.0), (4.8, 10.0), (5.4, 14.0), (L, 14.0)]
    K["torso_x"] = [(0.0, 3.0), (2.4, 4.0, *INOUT), (3.0, -10.0), (3.5, -12.0), (4.1, -4.0), (4.6, 5.0, *DEC),
                    (5.2, 3.0), (L, 3.0)]
    K["torso_z"] = [(0.0, 0.0), (2.6, 2.0), (3.4, -3.0), (4.2, 0.0), (L, 0.0)]
    K["root_z"] = [(0.0, 0.0), (2.6, 0.0), (3.4, -0.9), (4.2, 0.0), (L, 0.0)]
    K["root_y"] = [(0.0, -0.35), (2.6, -0.3), (3.4, -0.6), (4.4, -0.3), (L, -0.35)]
    K.update(vec("mug", [(0.0, MUG0), (2.4, MUG0, *INOUT), (3.2, (2.8, 1.2, -6.6)), (4.2, (2.8, 1.4, -6.6)),
                         (4.9, MUG0, *INOUT), (L, MUG0)]))
    tk.lk.key(K, cyclic=True)


# =========================================================================== COUNTER_WIPE 4.0
CTOP, CEDGE = 8.0, -8.0


def keys_wipe():
    L = 4.0
    K = {"root_x": [(0.0, 0.0), (1.0, -0.4), (2.0, 0.2), (3.0, -0.9), (L, 0.0)],
         "root_y": [(0.0, -1.3), (1.0, -1.5), (2.0, -1.3), (3.0, -1.6), (L, -1.3)],
         "root_z": [(0.0, -0.8), (L, -0.8)],
         "torso_x": [(0.0, 17.0), (0.45, 19.0), (0.9, 17.0), (1.35, 19.0), (1.8, 17.0), (2.4, 16.0), (3.0, 21.0),
                     (3.5, 18.0), (L, 17.0)],
         "torso_y": [(0.0, 6.0), (0.45, 2.0), (0.9, 6.0), (1.35, 2.0), (1.8, 6.0), (2.4, 12.0), (3.0, -8.0, *INOUT),
                     (3.5, 0.0), (L, 6.0)],
         "torso_z": [(0.0, 0.0), (0.45, 2.0), (0.9, 0.0), (1.35, 2.0), (1.8, 0.0), (3.0, -3.0), (L, 0.0)],
         "head_x": [(0.0, 12.0), (1.8, 14.0), (2.4, 12.0), (3.0, 9.0), (3.25, -12.0, *INOUT), (3.55, -10.0),
                    (3.8, 10.0), (L, 12.0)],
         "head_y": [(0.0, -8.0), (1.8, -6.0), (2.4, -18.0), (3.0, 12.0, *INOUT), (3.3, 22.0), (3.6, 18.0), (L, -8.0)],
         "head_z": [(0.0, 0.0), (0.9, 3.0), (1.8, 0.0), (L, 0.0)],
         "rhw": [(0.0, 1.0), (L, 1.0)]}
    wipe = circles(0.0, 2, 0.9, (-2.2, CTOP - 1.0, -10.2), 1.6, plane="xz")
    sweep = [(1.8, (-2.2 + 1.6, CTOP - 1.0, -10.2)), (2.35, (-6.4, CTOP - 1.0, -9.6), *INOUT),
             (3.05, (3.6, CTOP - 1.0, -10.4), *DEC), (3.4, (3.2, CTOP - 2.6, -9.8)),
             (3.75, (-0.4, CTOP - 1.8, -9.8), *INOUT), (L, (-2.2 + 1.6, CTOP - 1.0, -10.2))]
    K.update(vec("rh", wipe + sweep))
    K.update(vec("lh", [(0.0, (4.6, CTOP - 1.0, -9.2)), (2.9, (4.6, CTOP - 1.0, -9.2)), (3.1, (4.4, CTOP - 1.6, -9.0)),
                        (3.3, (4.6, CTOP - 1.0, -9.2)), (L, (4.6, CTOP - 1.0, -9.2))]))
    tk.lk.key(K, cyclic=True)


# =========================================================================== COUNTER_LEAN 8.0
def keys_lean():
    """COUNTER_LEAN 8.0 s loop: between bouts of wiping, leaning on the bar on both hands,
    watching the room - a slow look one way, a pause, the other way, a weight shift."""
    L = 8.0
    K = {"root_x": [(0.0, 0.0), (3.0, -0.6, *INOUT), (6.0, 0.5, *INOUT), (L, 0.0)],
         "root_y": [(0.0, -1.0), (L, -1.0)],
         "root_z": [(0.0, -0.6), (L, -0.6)],
         "torso_x": [(0.0, 13.0), (2.5, 14.0), (5.0, 12.0), (L, 13.0)],
         "torso_y": [(0.0, 2.0), (2.6, 8.0, *INOUT), (5.4, -6.0, *INOUT), (L, 2.0)],
         "torso_z": [(0.0, 0.0), (3.0, 1.5), (6.0, -1.5), (L, 0.0)],
         "head_x": [(0.0, -6.0), (2.0, -8.0), (4.2, -5.0), (4.35, -2.0, *DEC), (4.55, -5.0), (L, -6.0)],
         "head_y": [(0.0, 10.0), (1.4, 10.0, *INOUT), (2.8, 26.0), (4.2, 24.0, *INOUT), (5.8, -18.0), (7.0, -14.0, *INOUT),
                    (L, 10.0)],
         "head_z": [(0.0, 0.0), (2.8, 2.0), (5.8, -2.0), (L, 0.0)],
         "rhw": [(0.0, 1.0), (L, 1.0)]}
    K.update(vec("rh", [(0.0, (-4.8, CTOP - 1.0, -9.0)), (4.0, (-4.9, CTOP - 1.0, -9.2)), (L, (-4.8, CTOP - 1.0, -9.0))]))
    K.update(vec("lh", [(0.0, (4.8, CTOP - 1.0, -9.0)), (4.0, (4.9, CTOP - 1.0, -9.2)), (L, (4.8, CTOP - 1.0, -9.0))]))
    tk.lk.key(K, cyclic=True)


# =========================================================================== ALE_POUR 4.0
SPOUT = np.array([-0.4, 0.8, -13.6])      # nozzle opening (tap one block up, one ahead)
HANDLE = np.array([-0.4, -6.0, -17.5])


def keys_pour():
    L = 4.0
    UNDER = tuple(SPOUT + np.array([0.6, 4.4, 0.6]))     # mug body under the spout
    K = {"root_x": [(0.0, 0.4), (0.6, 0.2), (2.4, 0.1), (3.2, 0.6), (L, 0.4)],
         "root_y": [(0.0, -0.35), (0.6, -1.2), (2.4, -1.3), (3.0, -0.4), (L, -0.35)],
         "root_z": [(0.0, 0.0), (0.6, -1.6), (2.4, -1.6), (3.0, -0.4), (L, 0.0)],
         "torso_x": [(0.0, 3.0), (0.6, 20.0, *DEC), (1.2, 22.0), (2.4, 21.0), (2.9, 2.0, *INOUT), (3.2, -4.0),
                     (3.6, 2.0), (L, 3.0)],
         "torso_y": [(0.0, -4.0), (0.6, 8.0), (2.4, 9.0), (3.1, -10.0, *INOUT), (3.6, -8.0), (L, -4.0)],
         "head_x": [(0.0, 14.0), (0.6, 14.0), (1.2, 18.0), (1.6, 21.0), (2.2, 19.0), (2.8, 0.0), (3.05, -14.0),
                    (3.4, -12.0), (3.75, 10.0), (L, 14.0)],
         "head_y": [(0.0, -6.0), (0.6, -4.0), (2.4, -6.0), (3.0, -14.0), (3.5, -10.0), (L, -6.0)],
         "head_z": [(0.0, 2.0), (1.4, -4.0), (2.2, -2.0), (3.1, 8.0), (3.4, 6.0), (L, 2.0)],
         "rhw": [(0.0, 0.0), (0.35, 0.0, *INOUT), (0.75, 1.0), (2.3, 1.0, *INOUT), (2.7, 0.0), (L, 0.0)],
         "tilt": [(0.0, 0.0), (0.5, 0.0, *INOUT), (0.9, -18.0), (1.9, -18.0, *INOUT), (2.3, 0.0), (L, 0.0)],
         "roll": [(0.0, 0.0), (3.05, 0.0), (3.25, 8.0), (3.45, -6.0), (3.6, 0.0), (L, 0.0)]}
    K.update(vec("mug", [(0.0, MUG0), (0.3, MUG0, *INOUT), (0.85, UNDER, *DEC3), (1.9, UNDER),
                         (2.2, tuple(np.array(UNDER) + np.array([0.0, 0.8, 0.0]))), (2.45, (5.6, 0.4, -13.2)), (2.75, (5.4, -3.6, -11.4), *DEC3),
                         (3.4, (5.2, -4.0, -11.6)), (3.8, MUG0, *INOUT), (L, MUG0)]))
    H = tuple(HANDLE)
    PULLED = tuple(HANDLE + np.array([0.0, 1.2, 2.4]))
    K.update(vec("rh", [(0.0, RH0), (0.35, RH0), (0.75, H, *DEC3), (0.95, H, *ACC2), (1.15, PULLED, *DEC),
                        (1.95, PULLED, *INOUT), (2.15, H, *DEC), (2.3, H), (2.7, RH0), (L, RH0)]))
    K["rarm_x"] = [(0.0, 0.0), (L, 0.0)]
    tk.lk.key(K, cyclic=True)


# =========================================================================== SERVE_CARRY 2.0 (overlay)
def keys_carry():
    L = 2.0
    K = {"torso_x": [(0.0, -4.0), (0.25, -3.2), (0.5, -4.0), (0.75, -3.2), (1.0, -4.0), (1.25, -3.2), (1.5, -4.0),
                     (1.75, -3.2), (L, -4.0)],
         "torso_y": [(0.0, 0.0), (0.5, -2.0), (1.0, 0.0), (1.5, 2.0), (L, 0.0)],
         "torso_z": [(0.0, 1.2), (0.5, 0.0), (1.0, -1.2), (1.5, 0.0), (L, 1.2)],
         "head_x": [(0.0, 7.0), (0.5, 6.0), (1.0, 7.0), (1.5, 6.0), (L, 7.0)],
         "head_y": [(0.0, 0.0), (0.5, 2.2), (1.0, 0.0), (1.5, -2.2), (L, 0.0)],
         "head_z": [(0.0, -1.5), (0.5, 0.0), (1.0, 1.5), (1.5, 0.0), (L, -1.5)]}
    tk.lk.key(K, cyclic=True)


def solve_carry():
    L = 2.0
    TRAY = np.array([0.0, 2.6, -9.0])

    def solve(t):
        t %= L
        ch = {"torso": {"rot": (c("torso_x", t), c("torso_y", t), c("torso_z", t))},
              "head": {"rot": (c("head_x", t), c("head_y", t), c("head_z", t))}}
        pr = lk.lagged_rate(lambda u: c("torso_x", u % L), t)
        yr = lk.lagged_rate(lambda u: c("torso_y", u % L), t)
        ch["cloak"] = {"rot": lk.cloak(0.0, 0.0, pr, yr, k_rate=0.12, k_yaw=0.1)}
        # preview only (not exported): palms under a tray, legs neutral
        w = mcrig.pose_matrices(ch)
        lk.arm_ik(ch, w, "right", TRAY + np.array([-3.6, 0.6, 0.0]), (-0.9, 0.9, 0.3), solve.prev)
        lk.arm_ik(ch, w, "left", TRAY + np.array([3.6, 0.6, 0.0]), (0.9, 0.9, 0.3), solve.prev)
        return ch
    solve.prev = {}
    return solve


def scene_carry():
    objs = tk.scene_standing(TEX)
    tk.lk.box("tray", (-6.5, 1.0, -13.0), (13, 1, 8), (0.36, 0.24, 0.14, 1))
    tk.mug_box("tray_mug1", (-2.5, 1.0, -9.5))
    tk.mug_box("tray_mug2", (2.5, 1.0, -9.5))
    return objs


def scene_bar():
    objs = tk.lk.scene(TEX)
    tk.lk.box("counter", (-14, 8, -24), (28, 16, 16), (0.45, 0.30, 0.18, 1))
    return objs


def scene_tap():
    objs = tk.lk.scene(TEX)
    # barrel on the block ahead (tap face), a counter-high stand under it
    tk.lk.box("stand", (-8, 8, -24), (16, 16, 16), (0.40, 0.27, 0.16, 1))
    tk.lk.box("barrel", (-8, -8, -30), (16, 16, 16), (0.46, 0.30, 0.17, 1))
    tk.lk.box("tap_board", (-4, -3, -15), (8, 11, 1), (0.55, 0.38, 0.22, 1))
    tk.lk.box("tap_pipe", (-1.4, -1.5, -14.5), (2, 2, 1.2), (0.78, 0.62, 0.22, 1))
    tk.lk.box("tap_nozzle", (-1.4, -1.5, -14.2), (2, 4.0, 1.2), (0.78, 0.62, 0.22, 1))
    tk.lk.box("tap_handle", (-1.2, -7.5, -18.0), (1.6, 5, 1), (0.5, 0.34, 0.2, 1))
    return objs


CAMS_BAR = lambda: tk.lk.cameras(side=((-3.9, -0.6, 1.1), (0.0, -0.4, 0.95)),  # noqa: E731
                                 front34=((-2.4, -3.0, 1.5), (0.0, -0.3, 1.0)))

CLIPS = {
    "idle_innkeeper": dict(const="IDLE_INNKEEPER", L=4.5, keys=keys_base, solve=lambda: make_stand(4.5),
                           note="IDLE_INNKEEPER 4.5 s loop: polishes the ale mug (offhand prop), holds it to "
                                "the light at 2.35-2.9, huffs on it at 3.25, polishes on."),
    "idle_innkeeper__v2": dict(const="IDLE_INNKEEPER__V2", L=4.5, keys=keys_hum, solve=lambda: make_stand(4.5),
                               variant_of="animation.settler.idle_innkeeper",
                               note="IDLE_INNKEEPER variant: hums while polishing, sways on a 0.75 s beat, "
                                    "taps the right heel on 1.5/2.25/3.0/3.75."),
    "idle_innkeeper__v3": dict(const="IDLE_INNKEEPER__V3", L=6.0, keys=keys_survey, solve=lambda: make_stand(6.0),
                               variant_of="animation.settler.idle_innkeeper",
                               note="IDLE_INNKEEPER variant: surveys the room (left, then right), spots a "
                                    "regular, raises the mug at 3.75 and nods twice."),
    "idle_innkeeper__v4": dict(const="IDLE_INNKEEPER__V4", L=6.0, keys=keys_longday, solve=lambda: make_stand(6.0),
                               variant_of="animation.settler.idle_innkeeper",
                               note="IDLE_INNKEEPER variant: a long day - neck roll, hand to the small of the "
                                    "back, leans back with a sigh at 3.0-3.5, straightens."),
    "counter_wipe": dict(const="COUNTER_WIPE", L=4.0, keys=keys_wipe, scene=scene_bar, cams=CAMS_BAR,
                         solve=lambda: make_stand(4.0, left="ik", pole_r=(-0.9, 0.3, 0.6), pole_l=(0.9, 0.4, 0.5)),
                         props=None, mugs=[],
                         note="COUNTER_WIPE 4.0 s loop: leaning over a full-block counter (edge 8 px ahead, "
                              "top 16 px up), left hand braced, two rag circles then a long sweep, glance up."),
    "counter_lean": dict(const="COUNTER_LEAN", L=8.0, keys=keys_lean, scene=scene_bar, cams=CAMS_BAR,
                         solve=lambda: make_stand(8.0, left="ik", pole_r=(-0.9, 0.3, 0.6), pole_l=(0.9, 0.4, 0.5)),
                         props=None, mugs=[],
                         note="COUNTER_LEAN 8.0 s loop: the calm between wiping bouts - leaning on the bar, "
                              "watching the room."),
    "ale_pour": dict(const="ALE_POUR", L=4.0, keys=keys_pour, scene=scene_tap, cams=CAMS_BAR,
                     solve=lambda: make_stand(4.0, pole_r=(-0.9, 0.2, 0.6)),
                     sounds=[{"t": 1.15, "sound": "minecraft:block.wooden_trapdoor.open", "volume": 0.2,
                              "pitch_jitter": 0.1},
                             {"t": 1.3, "sound": "minecraft:item.bucket.fill", "volume": 0.25, "pitch_jitter": 0.1},
                             {"t": 2.15, "sound": "minecraft:block.wooden_trapdoor.close", "volume": 0.2,
                              "pitch_jitter": 0.1}],
                     note="ALE_POUR 4.0 s loop at an ale tap one block up and one ahead: mug under the "
                          "spout 0.85, handle pulled 1.15 (cue), filling to 1.95, closed 2.15, full mug "
                          "raised to eye level 2.75-3.4, back to the belt."),
    "serve_carry": dict(const="SERVE_CARRY", L=2.0, keys=keys_carry, solve=solve_carry, scene=scene_carry,
                        bones=["torso", "head", "cloak"], props=None, mugs=[],
                        note="SERVE_CARRY 2.0 s loop, torso/head/cloak ONLY, layered over the walk while "
                             "the host carries real service cargo: upright back, level careful gaze, a "
                             "small counter-sway. The arms stay on TavernServingHandPose's real-cargo IK."),
}


def main():
    for slug, spec in CLIPS.items():
        if ONLY and slug not in ONLY and slug.split("__")[0] not in ONLY:
            continue
        s = dict(spec)
        solve = spec["solve"]()
        s.update(slug=slug, script="author_innkeeper.py", solve=solve, loop=True,
                 scene=spec.get("scene", lambda: tk.scene_standing(TEX)),
                 bones=spec.get("bones", tk.mcrig.EXPORT_BONES),
                 props=spec.get("props", MUG_PROP), mugs=spec.get("mugs", [("left", [(0.0, spec["L"])])]),
                 cams=spec.get("cams"), checks=None if slug == "serve_carry" else feet_check())
        tk.run_clip(s, A)


if __name__ == "__main__":
    main()
