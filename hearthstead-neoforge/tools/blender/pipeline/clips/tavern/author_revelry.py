"""Tavern revelry + event clips (tavern lane). One Blender process, run headless:

    blender -b --factory-startup --python author_revelry.py -- [--fast|--full|--stills] [--no-export] [clip ...]

  DANCE_JIG    2.0 s loop, full body. A bard-night jig on a 0.25 s beat: kick-hop-kick with the
               right arm up and the left fist on the hip, then a crossing side-step with the arms
               swapped; the hips bounce on every landing.
  TIPSY_WALK   2.0 s loop = TWO gait cycles, the WALK slot (distance-clocked; blocks_per_cycle in
               the meta). The walk home after an ale: steps that wander across the line, one
               short stumble step caught with a flung arm, a rolling torso and a wobbling head.
  BRAWL_PUNCH  1.2 s one-shot (world-events lane): step in, wind up, a hip-driven haymaker with
               contact at 0.45 s, follow-through, guard, back to rest.
  SHOO_BIRDS   2.4 s loop (world-events lane): flapping both arms overhead at the birds, a stamp
               and a one-armed wave, a look up to see if they've gone.
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
G = 24.0


def vec(name, keys):
    return {f"{name}_{a}": [(k[0], k[1][i], *k[2:]) for k in keys] for i, a in enumerate("xyz")}


def body(t, L):
    ch = {"root": {"rot": (c("root_rx", t), c("root_yaw", t), c("root_roll", t)),
                   "pos": (c("root_x", t), c("root_y", t), c("root_z", t))},
          "torso": {"rot": (c("torso_x", t), c("torso_y", t), c("torso_z", t)),
                    "scale": lk.breath_scale(lk.breath(t, L / 2.0, phase=0.1), 0.02)},
          "head": {"rot": (c("head_x", t), c("head_y", t), c("head_z", t))}}
    return ch


def cloak(ch, t, L, base=2.0):
    pr = lk.lagged_rate(lambda u: c("torso_x", u % L), t)
    yr = lk.lagged_rate(lambda u: c("torso_y", u % L) + c("root_yaw", u % L), t)
    vy = lk.lagged_rate(lambda u: c("root_y", u % L), t, lag=0.03)
    ch["cloak"] = {"rot": lk.cloak(base, c("torso_x", t), pr, yr, root_vy=vy, k_pitch=0.4, k_yaw=0.08, k_vy=0.9)}


# =========================================================================== DANCE_JIG 2.0
B = 0.25


def keys_jig():
    L = 2.0
    RF, LF = (-2.9, G, 0.3), (2.9, G, -0.3)
    rf = [(0.0, RF), (0.10, (-2.9, G - 4.4, -3.6), *DEC), (0.19, (-2.9, G - 3.6, -6.4)), (0.25, RF, *ACC2),
          (0.34, (-2.7, G - 3.4, 2.6), *DEC), (0.44, (-2.7, G - 2.8, 3.6)), (0.5, RF, *ACC2), (1.0, RF),
          # side-step: right foot crosses in front of the left, back out
          (1.1, (-1.0, G - 2.6, -2.0), *DEC), (1.25, (1.6, G, -2.4), *ACC2), (1.5, (1.6, G, -2.4)),
          (1.6, (-1.0, G - 2.4, -1.2), *DEC), (1.75, RF, *ACC2), (L, RF)]
    lf = [(0.0, LF), (0.5, LF), (0.60, (2.9, G - 4.4, -3.6), *DEC), (0.69, (2.9, G - 3.6, -6.4)), (0.75, LF, *ACC2),
          (0.84, (2.7, G - 3.4, 2.6), *DEC), (0.94, (2.7, G - 2.8, 3.6)), (1.0, LF, *ACC2),
          (1.25, LF), (1.35, (4.6, G - 2.4, 0.6), *DEC), (1.5, (5.6, G, 0.8), *ACC2), (1.75, (5.6, G, 0.8)),
          (1.85, (4.2, G - 2.0, 0.2), *DEC), (2.0 - 0.001, LF, *ACC2), (L, LF)]
    K = {}
    K.update(vec("rf", rf))
    K.update(vec("lf", lf))
    # hips: up on each hop, down into each landing (four landings per half), weave with the step
    ry = [(0.0, -1.3)]
    for i in range(8):
        t0 = i * B
        ry += [(t0 + 0.10, 0.6, DEC[0], DEC[1]), (t0 + B, -1.3)]
    K["root_y"] = [(k[0], k[1]) for k in ry]
    K["root_x"] = [(0.0, 0.0), (0.5, 0.3), (1.0, 0.0), (1.25, 1.2), (1.5, 2.2), (1.75, 1.2), (L, 0.0)]
    K["root_yaw"] = [(0.0, 0.0), (0.5, -6.0), (1.0, 6.0), (1.5, -18.0, *INOUT), (1.75, -8.0), (L, 0.0)]
    K["root_roll"] = [(0.0, 0.0), (0.25, 2.0), (0.5, 0.0), (0.75, -2.0), (1.0, 0.0), (1.5, -3.0), (L, 0.0)]
    K["torso_x"] = [(0.0, -2.0)] + [(i * B + 0.12, -4.0 if i % 2 else 1.0) for i in range(8)] + [(L, -2.0)]
    K["torso_y"] = [(0.0, 0.0), (0.5, 10.0), (1.0, -10.0), (1.5, 16.0, *INOUT), (L, 0.0)]
    K["torso_z"] = [(0.0, 0.0), (0.25, -4.0), (0.5, 0.0), (0.75, 4.0), (1.0, 0.0), (1.5, 5.0), (L, 0.0)]
    K["head_x"] = [(0.0, -6.0)] + [(i * B + 0.14, -10.0 if i % 2 else -3.0) for i in range(8)] + [(L, -6.0)]
    K["head_y"] = [(0.0, 0.0), (0.5, -8.0), (1.0, 8.0), (1.5, -14.0), (L, 0.0)]
    K["head_z"] = [(0.0, 0.0), (0.25, 6.0), (0.5, 0.0), (0.75, -6.0), (1.0, 0.0), (1.5, -8.0), (L, 0.0)]
    # right hand waves overhead in the first half, fist on the hip in the second; left the reverse
    RH_UP, RH_HIP = (-10.2, -17.6, -3.0), (-7.4, -2.6, 1.4)
    LH_UP, LH_HIP = (10.2, -17.6, -3.0), (7.4, -2.6, 1.4)
    rh = [(0.0, RH_UP)] + [(i * B + 0.12, (-11.4 if i % 2 else -9.0, -16.6 if i % 2 else -18.4, -3.4)) for i in range(4)]
    rh += [(0.9, RH_UP, *INOUT), (1.1, RH_HIP), (1.85, RH_HIP, *INOUT), (L, RH_UP)]
    lh = [(0.0, LH_HIP), (0.85, LH_HIP, *INOUT), (1.05, LH_UP)] + \
         [(1.0 + i * B + 0.14, (11.4 if i % 2 else 9.0, -16.6 if i % 2 else -18.4, -3.4)) for i in range(1, 3)]
    lh += [(1.8, LH_UP, *INOUT), (L, LH_HIP)]
    K.update(vec("rh", rh))
    K.update(vec("lh", lh))
    K["rhs"] = [(0.0, 1.0), (L, 1.0)]    # right target in torso space when up (keeps it overhead)
    tk.lk.key(K, cyclic=True)


def solve_jig():
    L = 2.0

    def solve(t):
        t %= L
        ch = body(t, L)
        w = mcrig.pose_matrices(ch)
        # hands: hip positions in torso space, waving positions in torso space too
        for side, pole in (("right", (-1.0, 0.2, 0.6)), ("left", (1.0, 0.2, 0.6))):
            key = "rh" if side == "right" else "lh"
            tgt = np.array([c(key + "_x", t), c(key + "_y", t), c(key + "_z", t)])
            lk.arm_ik_local(ch, side, tgt, pole, solve.prev)
        feet = (np.array([c("rf_x", t), c("rf_y", t), c("rf_z", t)]),
                np.array([c("lf_x", t), c("lf_y", t), c("lf_z", t)]))
        lk.leg_ik(ch, feet, solve.prev, knee_out=0.25)
        cloak(ch, t, L, base=4.0)
        return ch
    solve.prev = {}
    return solve


# =========================================================================== TIPSY_WALK 2.0 (2 gait cycles)
EXC, DUTY, LIFT = 11.5, 0.6, 2.2
STEPS = [  # (contact time, foot, lateral x of the plant, stride scale)  -- 4 steps in 2.0 s
    (0.0, "right", -2.4, 1.0), (0.5, "left", 1.6, 1.0),        # the left lands across the line
    (1.0, "right", -3.8, 0.8), (1.5, "left", 3.6, 1.15)]      # a short stumble, a long catch-step
TW_L = 2.0
TW_BPC = 2 * EXC / DUTY / 16.0   # blocks per clip loop (two gait cycles)


def foot_track(t, foot):
    """Treadmill: stance slides back at ground speed; swing arcs forward to the next plant."""
    mine = [s for s in STEPS if s[1] == foot]
    period = TW_L
    for i, (t0, _, x0, sc) in enumerate(mine):
        nxt = mine[(i + 1) % len(mine)]
        t1 = nxt[0] if nxt[0] > t0 else nxt[0] + period
        tt = t if t >= t0 else t + period
        if not (t0 <= tt < t1):
            continue
        u = (tt - t0) / (t1 - t0)
        stance = DUTY
        e0 = e1 = EXC
        if u < stance:
            s = u / stance
            return np.array([x0, G, -e0 / 2 + e0 * s])
        s = (u - stance) / (1 - stance)
        sm = s * s * (3 - 2 * s)
        z = e0 / 2 - (e0 / 2 + e1 / 2) * sm
        x = x0 + (nxt[2] - x0) * sm
        y = G - LIFT * math.sin(math.pi * min(1.0, s ** 0.8)) * (0.55 if nxt[3] < 0.9 else 1.0)
        return np.array([x, y, z])
    return np.array([mine[0][2], G, 0.0])


def keys_tipsy():
    L = TW_L
    K = {"root_y": [(0.0, -1.0), (0.1, -1.6), (0.3, -0.4), (0.5, -1.0), (0.6, -1.6), (0.8, -0.5), (1.0, -1.1),
                    (1.08, -2.0), (1.25, -0.8), (1.5, -1.2), (1.62, -1.9), (1.8, -0.5), (L, -1.0)],
         "root_x": [(0.0, -0.8), (0.5, 0.9), (1.0, -1.6), (1.5, 1.8), (L, -0.8)],
         "root_yaw": [(0.0, -5.0), (0.5, 4.0), (1.0, -8.0), (1.5, 7.0), (L, -5.0)],
         "root_roll": [(0.0, 3.0), (0.5, -3.0), (1.0, 5.0), (1.5, -5.0), (L, 3.0)],
         "torso_x": [(0.0, 5.0), (0.6, 7.0), (1.05, 11.0), (1.3, 4.0), (1.7, 6.0), (L, 5.0)],
         "torso_y": [(0.0, 6.0), (0.5, -5.0), (1.0, 9.0), (1.5, -8.0), (L, 6.0)],
         "torso_z": [(0.0, -5.0), (0.55, 6.0), (1.05, -9.0), (1.55, 8.0), (L, -5.0)],
         "head_x": [(0.0, 2.0), (0.6, -4.0), (1.1, 8.0), (1.4, -6.0), (L, 2.0)],
         "head_y": [(0.0, -6.0), (0.6, 6.0), (1.1, -10.0), (1.6, 8.0), (L, -6.0)],
         "head_z": [(0.0, 7.0), (0.65, -8.0), (1.15, 11.0), (1.65, -10.0), (L, 7.0)],
         # arms: loose wide swing, the right flung out to catch the stumble at 1.0-1.3
         "ar_x": [(0.0, 22.0), (0.5, -18.0), (1.0, 14.0), (1.12, -40.0, *DEC), (1.35, -30.0), (1.5, -20.0), (L, 22.0)],
         "ar_z": [(0.0, 12.0), (0.5, 8.0), (1.0, 14.0), (1.15, 42.0, *DEC), (1.4, 30.0), (1.6, 12.0), (L, 12.0)],
         "al_x": [(0.0, -20.0), (0.5, 22.0), (1.0, -12.0), (1.5, 24.0), (L, -20.0)],
         "al_z": [(0.0, -10.0), (0.5, -14.0), (1.0, -12.0), (1.5, -16.0), (L, -10.0)],
         "er": [(0.0, -14.0), (0.5, -26.0), (1.15, -6.0), (1.5, -24.0), (L, -14.0)],
         "el": [(0.0, -26.0), (0.5, -12.0), (1.0, -24.0), (1.5, -10.0), (L, -26.0)]}
    tk.lk.key(K, cyclic=True)


def solve_tipsy():
    L = TW_L

    def solve(t):
        t %= L
        ch = body(t, L)
        ch["right_arm"] = {"rot": (c("ar_x", t), 0.0, c("ar_z", t))}
        ch["left_arm"] = {"rot": (c("al_x", t), 0.0, c("al_z", t))}
        ch["right_forearm"] = {"rot": (c("er", t), 0.0, 0.0)}
        ch["left_forearm"] = {"rot": (c("el", t), 0.0, 0.0)}
        lk.leg_ik(ch, (foot_track(t, "right"), foot_track(t, "left")), solve.prev, knee_out=0.15)
        cloak(ch, t, L, base=3.0)
        return ch
    solve.prev = {}
    return solve


# =========================================================================== BRAWL_PUNCH 1.2 one-shot
HIT = 0.45


def keys_punch():
    L = 1.2
    K = {"root_z": [(0.0, 0.0), (0.18, 1.2), (0.3, 1.0, *ACC), (HIT, -2.2, *DEC), (0.7, -1.6), (1.0, -0.3), (L, 0.0)],
         "root_y": [(0.0, 0.0), (0.2, -1.2), (0.3, -1.4, *ACC), (HIT, -0.8), (0.7, -1.0), (1.1, -0.1), (L, 0.0)],
         "root_yaw": [(0.0, 0.0), (0.3, 16.0, *ACC), (HIT - 0.04, -14.0, *DEC), (0.7, -10.0), (1.1, -1.0), (L, 0.0)],
         "torso_y": [(0.0, 0.0), (0.3, 22.0, *ACC), (HIT, -26.0, *DEC), (0.62, -22.0), (1.05, -2.0), (L, 0.0)],
         "torso_x": [(0.0, 0.0), (0.3, 4.0, *ACC), (HIT, 14.0, *DEC), (0.7, 8.0), (1.05, 1.0), (L, 0.0)],
         "torso_z": [(0.0, 0.0), (0.3, -5.0), (HIT, 4.0), (0.8, 2.0), (L, 0.0)],
         "head_x": [(0.0, 0.0), (0.3, 8.0), (HIT, 2.0), (0.7, 4.0), (L, 0.0)],
         "head_y": [(0.0, 0.0), (0.3, -18.0), (HIT, 20.0, *DEC), (0.7, 18.0), (1.05, 2.0), (L, 0.0)],
         "ik": [(0.0, 0.0), (0.12, 1.0, *DEC), (0.95, 1.0, *INOUT), (1.15, 0.0), (L, 0.0)]}
    K.update(vec("rh", [(0.0, (-6.0, 0.0, 0.0)), (0.12, (-5.0, -8.0, -5.0)), (0.3, (-7.6, -9.0, 3.4), *ACC),
                        (HIT, (-1.5, -10.0, -8.8), *DEC), (0.55, (-2.0, -9.0, -8.4)), (0.8, (-4.5, -7.0, -6.5)),
                        (1.0, (-6.0, -2.0, -3.0)), (L, (-6.0, 0.0, 0.0))]))
    K.update(vec("lh", [(0.0, (6.0, 0.0, 0.0)), (0.12, (2.6, -11.0, -6.2)), (0.3, (3.2, -11.5, -7.0)),
                        (HIT, (4.4, -11.8, -4.5), *DEC), (0.8, (3.4, -11.0, -6.0)), (1.0, (6.0, -2.0, -3.0)),
                        (L, (6.0, 0.0, 0.0))]))
    K.update(vec("lf", [(0.0, (2.6, G, 0.0)), (0.15, (2.6, G - 1.6, -2.0)), (0.3, (2.8, G, -3.6), *ACC2),
                        (0.9, (2.8, G, -3.6)), (1.02, (2.6, G - 1.2, -1.6)), (1.12, (2.6, G, 0.0)), (L, (2.6, G, 0.0))]))
    tk.lk.key(K, cyclic=False)


def solve_punch():
    L = 1.2

    def solve(t):
        ch = body(t, L)
        w = max(0.0, min(1.0, c("ik", t)))
        for side, key, pole in (("right", "rh", (-0.9, 0.5, 0.5)), ("left", "lh", (0.9, 0.9, 0.2))):
            tgt = np.array([c(key + "_x", t), c(key + "_y", t), c(key + "_z", t)])
            lk.arm_ik_local(ch, side, tgt, pole, solve.prev)
            for b in (side + "_arm", side + "_forearm"):
                ch[b]["rot"] = tuple(v * w for v in ch[b]["rot"])
        feet = (np.array([-2.6, G, 0.0]), np.array([c("lf_x", t), c("lf_y", t), c("lf_z", t)]))
        lk.leg_ik(ch, feet, solve.prev)
        cloak(ch, t, L, base=0.0)
        return lk.edge_rest(ch, t, L, ramp=0.06)
    solve.prev = {}
    return solve


# =========================================================================== SHOO_BIRDS 2.4
def keys_shoo():
    L = 2.4
    UPR, UPL = (-8.0, -19.2, -2.0), (8.0, -19.2, -2.0)
    OUTR, OUTL = (-13.0, -9.0, -1.0), (13.0, -9.0, -1.0)
    K = {"root_y": [(0.0, -0.6), (0.3, -1.4), (0.6, -0.4), (0.9, -1.4), (1.2, -0.4), (1.45, -2.2, *ACC),
                    (1.55, -1.0), (1.9, -0.4), (L, -0.6)],
         "root_z": [(0.0, 0.0), (0.6, -1.0), (1.2, -1.6), (1.9, -0.6), (L, 0.0)],
         "torso_x": [(0.0, -4.0), (0.3, 2.0), (0.6, -8.0), (0.9, 2.0), (1.2, -8.0), (1.5, 6.0, *DEC), (1.9, -2.0),
                     (L, -4.0)],
         "torso_y": [(0.0, 0.0), (1.4, 0.0), (1.6, -14.0), (1.9, -10.0), (L, 0.0)],
         "head_x": [(0.0, -18.0), (0.3, -10.0), (0.6, -22.0), (0.9, -10.0), (1.2, -22.0), (1.5, -4.0),
                    (1.9, -26.0, *INOUT), (2.2, -24.0), (L, -18.0)],
         "head_y": [(0.0, 0.0), (1.5, -12.0), (1.9, 18.0, *INOUT), (2.2, 10.0), (L, 0.0)],
         "head_z": [(0.0, 0.0), (0.3, 4.0), (0.6, -4.0), (0.9, 4.0), (1.2, -4.0), (L, 0.0)]}
    K.update(vec("rh", [(0.0, UPR), (0.3, OUTR, *DEC), (0.6, UPR, *DEC), (0.9, OUTR, *DEC), (1.2, UPR, *DEC),
                        (1.45, (-10.0, -12.0, -8.0)), (1.6, (-4.0, -15.0, -8.0), *DEC), (1.75, (-10.0, -12.0, -8.0)),
                        (1.9, (-6.5, -0.5, -1.0), *INOUT), (2.2, (-7.0, -2.0, -2.0)), (L, UPR)]))
    K.update(vec("lh", [(0.0, UPL), (0.3, OUTL, *DEC), (0.6, UPL, *DEC), (0.9, OUTL, *DEC), (1.2, UPL, *DEC),
                        (1.5, (6.4, -0.5, 0.5), *INOUT), (2.1, (6.6, -0.8, -1.2)), (L, UPL)]))
    K.update(vec("rf", [(0.0, (-2.9, G, 0.3)), (1.3, (-2.9, G, 0.3)), (1.4, (-2.9, G - 3.0, -1.6), *DEC),
                        (1.47, (-2.9, G, -2.2), *ACC2), (1.9, (-2.9, G, -2.2)), (2.05, (-2.9, G - 1.4, -0.8)),
                        (2.2, (-2.9, G, 0.3)), (L, (-2.9, G, 0.3))]))
    tk.lk.key(K, cyclic=True)


def solve_shoo():
    L = 2.4

    def solve(t):
        t %= L
        ch = body(t, L)
        for side, key, pole in (("right", "rh", (-1.0, 0.3, 0.4)), ("left", "lh", (1.0, 0.3, 0.4))):
            tgt = np.array([c(key + "_x", t), c(key + "_y", t), c(key + "_z", t)])
            lk.arm_ik_local(ch, side, tgt, pole, solve.prev)
        feet = (np.array([c("rf_x", t), c("rf_y", t), c("rf_z", t)]), np.array([2.9, G, -0.3]))
        lk.leg_ik(ch, feet, solve.prev)
        cloak(ch, t, L, base=3.0)
        return ch
    solve.prev = {}
    return solve


CLIPS = {
    "dance_jig": dict(const="DANCE_JIG", L=2.0, loop=True, keys=keys_jig, solve=solve_jig, tex="settler_farmer.png",
                      sounds=[{"t": t, "sound": "minecraft:block.wood.step", "volume": 0.18, "pitch_jitter": 0.1}
                              for t in (0.25, 0.5, 0.75, 1.0, 1.25, 1.75)],
                      note="DANCE_JIG 2.0 s loop (bard nights, full body): kick-hop on a 0.25 s beat, arm up "
                           "and fist on hip, crossing side-step with the arms swapped; heel cues on landings."),
    # TIPSY_WALK moved to author_drunk.py (three drunk levels, 26 Sep); keys_tipsy kept for reference.
    "brawl_punch": dict(const="BRAWL_PUNCH", L=1.2, loop=False, keys=keys_punch, solve=solve_punch,
                        tex="settler_farmer.png", keep=(0.0, HIT, 1.2),
                        note="BRAWL_PUNCH 1.2 s one-shot: step in with the left foot, wind-up 0.3, hip-driven "
                             "haymaker CONTACT at 0.45 s, follow-through, guard, rest at both ends."),
    "shoo_birds": dict(const="SHOO_BIRDS", L=2.4, loop=True, keys=keys_shoo, solve=solve_shoo,
                       tex="settler_farmer.png",
                       note="SHOO_BIRDS 2.4 s loop: two big overhead flaps, a stamp (1.47) and a one-armed "
                            "wave, a look up after the birds."),
}


def main():
    for slug, spec in CLIPS.items():
        if ONLY and slug not in ONLY:
            continue
        s = dict(spec)
        solve = spec["solve"]()
        s.update(slug=slug, script="author_revelry.py", solve=solve,
                 scene=lambda tex=spec["tex"]: tk.lk.scene(tex), bones=tk.mcrig.EXPORT_BONES,
                 keep=spec.get("keep", (0.0, spec["L"])), mugs=[])
        tk.run_clip(s, A)


if __name__ == "__main__":
    main()
