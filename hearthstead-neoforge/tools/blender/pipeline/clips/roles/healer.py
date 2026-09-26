"""Healer clips (plan/BATTLE-ROLES.md §6), anim overkill 2026-09-26.

    blender -b --factory-startup --python healer.py -- [--fast|--full] [--no-export] CONST [CONST ...]

HEALER_BANDAGE  1.50 s loop (WORK_BANDAGE; HealerMedicGoal CHANNEL_TICKS = 30): down on the right
                knee before the patient, the left hand holds the wound, the right hand passes the roll
                round it and PULLS it tight at 0.50 and 1.00 s (ticks 10, 20), then ties it off with a
                little tug, head bobbing to check the wrap.
HEALER_REVIVE   2.00 s loop (WORK_REVIVE): on the right knee over a downed ally, hands stacked,
                three chest presses (0.00 / 0.40 / 0.80) with the shoulders over the hands, then a
                lean in to look and listen (1.10-1.80), a hand on the shoulder, and again.
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import numpy as np  # noqa: E402

import rolekit as rk  # noqa: E402

S, ACC, DEC, DEC3, LIN = rk.SMO, rk.ACC, rk.DEC, rk.DEC3, rk.LIN
# kneeling on the right knee (the NAIL_HAMMER kneel): right foot back on its toes
FOOT_R = np.array([-2.8, 23.3, 8.6])
FOOT_L = np.array([3.0, 24.0, -0.6])
FEET = (FOOT_R, FOOT_L)
KNEEL = {"hip_x": 0.0, "hip_y": -5.6, "hip_z": 3.5, "hip_p": 0.0, "hip_yaw": 0.0, "hip_r": 0.0,
         "sp_x": 16.0, "sp_y": 0.0, "sp_z": 0.0, "sp_lift": 0.0, "hd_x": 16.0, "hd_y": 0.0, "hd_z": 0.0,
         "ck_x": 4.0, "ck_z": 0.0, "wr_x": 0.0, "wr_y": 0.0, "wr_z": 0.0, "wl_x": 0.0, "wl_y": 0.0, "wl_z": 0.0,
         "gl_w": 0.0, "gl_s": 0.0, "gr_s": 0.0}


def base_vals(over=None):
    v = dict(KNEEL)
    v.update(over or {})
    v.update({"ar_x": -40.0, "ar_y": 0.0, "ar_z": 0.0, "el_r": -40.0, "tw_r": 0.0,
              "al_x": -40.0, "al_y": 0.0, "al_z": 0.0, "el_l": -40.0, "tw_l": 0.0})
    return v


def rel(b, pts):
    return [(p[0], KNEEL[b] + p[1], *p[2:]) for p in pts]


# =========================================================================== BANDAGE
def bandage():
    L = 1.5
    K = {
        "sp_x": rel("sp_x", [(0.0, 0.0, *S), (0.40, 2.0, *ACC), (0.50, -1.5, *DEC), (0.90, 2.0, *ACC), (1.00, -1.5, *DEC),
                             (1.20, 3.0, *S), (1.32, 1.0, *S), (1.5, 0.0)]),
        "sp_y": rel("sp_y", [(0.0, 0.0, *S), (0.25, 5.0, *S), (0.5, -2.0, *S), (0.75, 5.0, *S), (1.0, -2.0, *S),
                             (1.5, 0.0)]),
        "hd_x": rel("hd_x", [(0.0, 0.0, *S), (0.5, 4.0, *S), (1.0, 3.0, *S), (1.25, -4.0, *S), (1.5, 0.0)]),
        "hd_y": rel("hd_y", [(0.0, 0.0, *S), (0.25, 8.0, *S), (0.5, -2.0, *S), (0.75, 8.0, *S), (1.0, -2.0, *S),
                             (1.3, -6.0, *S), (1.5, 0.0)]),
        "hip_y": rel("hip_y", [(0.0, 0.0, *S), (0.5, -0.25, *DEC), (0.7, 0.0, *S), (1.0, -0.25, *DEC), (1.2, 0.0, *S),
                               (1.5, 0.0)]),
    }
    rk.const_keys(base_vals(), L, True, K)
    W = np.array([0.6, 13.6, -9.6])          # the wound on the patient's forearm/leg
    lg = [(0.0, W + np.array([2.4, 0.2, 0.6]), None, S), (0.5, W + np.array([2.6, 0.4, 0.8]), None, S),
          (1.0, W + np.array([2.4, 0.2, 0.6]), None, S), (1.18, W + np.array([1.2, -0.2, 0.2]), None, S),   # pinch
          (1.32, W + np.array([1.6, 0.2, 0.8]), None, S), (L, W + np.array([2.4, 0.2, 0.6]), None, None)]
    rk.arm_goals(lg, "left", seed=[-50, 0, 0, -50, 0, 0], length=L, loop=True, w_seed=0.01)
    rg = []
    for k, (t0, t1) in enumerate(((0.0, 0.5), (0.5, 1.0))):
        # round the limb: over, under, then the PULL (back and out) at t1
        for u, off in ((0.0, (-2.6, 0.4, 0.4)), (0.15, (-0.6, -2.0, -1.2)), (0.32, (1.8, 0.2, -2.2)),
                       (0.46, (0.2, 2.2, -0.6)), (0.80, (-2.4, 1.0, 1.4))):
            ease = DEC if u == 0.80 else S
            rg.append((t0 + (t1 - t0) * u, W + np.array(off), None, ease))
    rg += [(1.0, W + np.array([-3.4, 0.8, 1.8]), None, S),                 # pulled tight
           (1.18, W + np.array([-1.0, -0.2, 0.2]), None, S),               # tie off
           (1.26, W + np.array([-1.6, 0.4, 0.8]), None, DEC),              # tug
           (1.36, W + np.array([-1.2, 0.0, 0.4]), None, S), (L, W + np.array([-2.6, 0.4, 0.4]), None, None)]
    rk.arm_goals(rg, "right", seed=[-50, 0, 0, -50, 0, 0], length=L, loop=True, w_seed=0.01)
    solve = rk.make_solve(L, True, FEET, cloak_drag=False)
    return (L, True, solve, "1.50 s loop (WORK_BANDAGE): kneeling; wrap pulled tight at 0.50 / 1.00 s (ticks 10, 20), "
            "tie-off tug 1.18-1.36", (0.5, 1.0, 1.18), None)


# =========================================================================== REVIVE
def revive():
    L = 2.0
    presses = (0.0, 0.4, 0.8)
    sp = [(0.0, 10.0, *DEC)]
    hy = [(0.0, -0.6, *DEC)]
    for i, t in enumerate(presses):
        sp += [(t + 0.14, 4.0, *S), (t + 0.36 if i < 2 else t + 0.30, 9.5, *ACC)]
        hy += [(t + 0.14, 0.0, *S), (t + 0.36 if i < 2 else t + 0.30, -0.5, *ACC)]
    sp[-1] = (1.10, 2.0, *S)
    hy[-1] = (1.10, 0.1, *S)
    sp += [(1.40, 12.0, *S), (1.75, 12.0, *S), (2.0, 10.0)]
    hy += [(1.40, -0.4, *S), (1.75, -0.4, *S), (2.0, -0.6)]
    K = {
        "sp_x": rel("sp_x", sp),
        "hip_y": rel("hip_y", hy),
        "hd_x": rel("hd_x", [(0.0, 10.0, *S), (1.1, 8.0, *S), (1.40, 18.0, *S), (1.75, 16.0, *S), (2.0, 10.0)]),
        "hd_y": rel("hd_y", [(0.0, 0.0, *S), (1.1, 0.0, *S), (1.40, -14.0, *S), (1.75, -12.0, *S), (2.0, 0.0)]),
        "hd_z": rel("hd_z", [(0.0, 0.0, *S), (1.40, -8.0, *S), (1.75, -8.0, *S), (2.0, 0.0)]),
    }
    rk.const_keys(base_vals(), L, True, K)
    C = np.array([0.0, 19.6, -9.4])            # the downed ally's chest on the ground before him
    rg, lg = [], []
    for t in presses:
        rg += [(t, C + np.array([-0.8, 0.0, 0.0]), None, DEC), (t + 0.18, C + np.array([-0.8, -1.4, 0.2]), None, ACC)]
        lg += [(t, C + np.array([0.8, -0.4, 0.2]), None, DEC), (t + 0.18, C + np.array([0.8, -1.8, 0.4]), None, ACC)]
    rg += [(1.10, C + np.array([-0.8, -1.8, 0.4]), None, S), (1.45, C + np.array([-1.0, -1.2, 0.4]), None, S),
           (1.80, C + np.array([-0.8, -1.6, 0.2]), None, S)]
    lg += [(1.10, C + np.array([0.8, -2.0, 0.6]), None, S),
           (1.45, C + np.array([4.2, -1.0, -1.0]), None, S),                  # a hand on his shoulder
           (1.80, C + np.array([4.0, -1.0, -0.8]), None, S)]
    rk.arm_goals(rg, "right", seed=[-60, 0, 0, -20, 0, 0], length=L, loop=True, w_seed=0.01)
    rk.arm_goals(lg, "left", seed=[-60, 0, 0, -20, 0, 0], length=L, loop=True, w_seed=0.01)
    solve = rk.make_solve(L, True, FEET, cloak_drag=False)
    return (L, True, solve, "2.00 s loop (WORK_REVIVE): kneeling; chest presses at 0.00 / 0.40 / 0.80 s, lean in to "
            "look and listen 1.10-1.80 with a hand on the shoulder", (0.0, 0.4, 0.8, 1.4), None)


CLIPS = {"HEALER_BANDAGE": bandage, "HEALER_REVIVE": revive}

if __name__ == "__main__":
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    names = [a for a in argv if not a.startswith("--")] or list(CLIPS)
    for n in names:
        rk.run(n, CLIPS[n], "settler_weaver.png", None)
