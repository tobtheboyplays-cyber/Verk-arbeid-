"""Spearman clips (plan/BATTLE-ROLES.md §6), anim overkill 2026-09-26.

    blender -b --factory-startup --python spear.py -- [--fast|--full] [--no-export] CONST [CONST ...]

IDLE_SPEARMAN          4.00 s loop   low brace: lead-left stance, knees loaded, spear two-handed at
                                     the hip, butt by the back heel, point at an enemy's chest;
                                     breathing, weight rocking onto the front foot, a grip reset
                                     (front hand slides back and re-seats) and a scan left-right.
SPEAR_THRUST           0.60 s        contact 0.25 s (tick 5): coil back onto the rear foot, the lead
                                     foot steps 3-5, the rear hand DRIVES the shaft through the front
                                     fist (grip slide), hips square behind it; recover 6-12.
SPEAR_DOUBLE_THRUST    0.80 s        contacts 0.25 s / 0.55 s (ticks 5, 11): short retract between.
SPEAR_BRACE_STRIKE     0.50 s        contact 0.15 s (tick 3): from the brace, a short brutal shove -
                                     the whole body weight lurches through the front knee.
All absolute; one-shots start and end on IDLE_SPEARMAN t=0.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import numpy as np  # noqa: E402

import rolekit as rk  # noqa: E402

S, ACC, ACC4, DEC, DEC3, LIN = rk.SMO, rk.ACC, rk.ACC4, rk.DEC, rk.DEC3, rk.LIN
ITEM = "spear"
FOOT_R = np.array([-3.4, 24.0, 2.8])
FOOT_L = np.array([3.0, 24.0, -3.2])
FEET = (FOOT_R, FOOT_L)
BRACE = {"hip_x": 0.3, "hip_y": -1.3, "hip_z": 0.5, "hip_p": 0.0, "hip_yaw": 16.0, "hip_r": 0.0,
         "sp_x": 11.0, "sp_y": 4.0, "sp_z": 1.0, "sp_lift": 0.0,
         "hd_x": -9.0, "hd_y": -17.0, "hd_z": 0.0, "ck_x": 6.0, "ck_z": 0.0,
         "wr_y": 0.0, "wr_z": 0.0, "wl_x": 0.0, "wl_y": 0.0, "wl_z": 0.0,
         "gl_w": 1.0, "gl_s": 9.0, "gr_s": 5.0}
HAND = np.array([-4.6, 11.4, -1.0])            # rear fist at the right hip
DIR = np.array([0.10, -0.24, -0.965])          # point at an enemy's chest ~1.5 blocks out
IDLE_SEED = [-5.0, 0.0, 0.0, -35.0, 0.0, 0.0]


def rest_arm():
    """The brace solution (exact, re-used as every one-shot's first/last key)."""
    rk.const_keys(BRACE, 1.0, False)
    sol, err = rk.solve_arm6(rk.body_at(0.0), "right", HAND, DIR, item=ITEM, seed=IDLE_SEED)
    print("REST", round(err, 2), [round(v, 1) for v in sol])
    return sol


def left_free():
    """Left arm FK seed (the grip IK owns it while gl_w=1)."""
    return {"al_x": -40.0, "al_y": 0.0, "al_z": -10.0, "el_l": -30.0, "tw_l": 0.0}


def rest_vals():
    sol = rest_arm()
    v = dict(BRACE)
    v.update(dict(zip(rk.ARM6["right"], sol)))
    v.update(left_free())
    return v, sol


def keyed_arm(sol, times):
    return {p: [(t, v, *S) for t in times] for p, v in zip(rk.ARM6["right"], sol)}


# =========================================================================== IDLE
def idle():
    L = 4.0
    v, sol = rest_vals()
    b = BRACE
    over = {
        # breathing (0.0 / 2.0 in, out) + a slow rock onto the front foot and back
        "sp_lift": [(0.0, 0.0, *S), (1.0, 0.25, *S), (2.0, 0.0, *S), (3.0, 0.28, *S), (4.0, 0.0)],
        "sp_x": [(0.0, b["sp_x"], *S), (1.0, b["sp_x"] - 1.2, *S), (2.0, b["sp_x"] + 0.6, *S),
                 (3.0, b["sp_x"] - 1.0, *S), (4.0, b["sp_x"])],
        "hip_z": [(0.0, b["hip_z"], *S), (1.3, b["hip_z"] - 0.6, *S), (2.6, b["hip_z"] + 0.2, *S), (4.0, b["hip_z"])],
        "hip_y": [(0.0, b["hip_y"], *S), (1.3, b["hip_y"] - 0.25, *S), (2.6, b["hip_y"] + 0.1, *S), (4.0, b["hip_y"])],
        "hip_x": [(0.0, b["hip_x"], *S), (1.3, b["hip_x"] + 0.35, *S), (2.6, b["hip_x"] - 0.1, *S), (4.0, b["hip_x"])],
        # the scan: eyes off the point to the right, back, a flick left, back (anticipation dip each turn)
        "hd_y": [(0.0, b["hd_y"], *S), (0.5, b["hd_y"], *S), (0.62, b["hd_y"] + 1.5, *S), (0.95, b["hd_y"] - 16.0, *DEC),
                 (1.35, b["hd_y"] - 15.0, *S), (1.7, b["hd_y"] + 1.0, *DEC), (1.9, b["hd_y"], *S),
                 (3.15, b["hd_y"], *S), (3.35, b["hd_y"] + 12.0, *DEC), (3.6, b["hd_y"] + 11.0, *S),
                 (3.85, b["hd_y"] - 0.8, *DEC), (4.0, b["hd_y"])],
        "hd_x": [(0.0, b["hd_x"], *S), (0.95, b["hd_x"] + 2.0, *S), (1.7, b["hd_x"], *S), (2.2, b["hd_x"] + 5.0, *S),
                 (2.9, b["hd_x"] + 4.0, *S), (3.3, b["hd_x"] - 1.0, *S), (4.0, b["hd_x"])],
        "hd_z": [(0.0, 0.0), (0.95, -2.0, *S), (1.7, 0.0, *S), (3.35, 1.5, *S), (4.0, 0.0)],
        # grip reset 2.0-3.0: the front hand slides back along the shaft, re-seats forward
        "gl_s": [(0.0, b["gl_s"], *S), (2.05, b["gl_s"], *S), (2.35, b["gl_s"] - 3.5, *DEC), (2.55, b["gl_s"] - 3.3, *S),
                 (2.85, b["gl_s"] + 0.5, *DEC), (3.0, b["gl_s"], *S), (4.0, b["gl_s"])],
        "sp_y": [(0.0, b["sp_y"], *S), (2.1, b["sp_y"], *S), (2.4, b["sp_y"] - 3.0, *S), (2.9, b["sp_y"] + 0.5, *S),
                 (3.2, b["sp_y"], *S), (4.0, b["sp_y"])],
    }
    rk.const_keys(v, L, True, over)
    # the point dips as the grip loosens and comes back up to the chest line (overshoot)
    goals = [(0.0, HAND, DIR, S), (1.3, HAND + np.array([0.0, 0.3, -0.3]), DIR, S),
             (2.05, HAND, DIR, S), (2.4, HAND + np.array([0.2, 0.8, 0.4]), DIR + np.array([0, 0.14, 0]), DEC),
             (2.75, HAND + np.array([0.0, -0.3, -0.2]), DIR + np.array([0, -0.05, 0]), S),
             (3.0, HAND, DIR, S)]
    log = rk.arm_goals(goals, "right", ITEM, seed=sol, length=L, loop=True, w_seed=0.03)
    solve = rk.make_solve(L, True, FEET, item=ITEM)
    return L, True, solve, "4.00 s loop; low spear brace; grip reset 2.0-3.0 s; scan 0.5-1.9 / 3.15-4.0 s", (), None


# =========================================================================== THRUST
def _thrust_body(b, t0, hit, hold, rec_end, L, step=3.2, depth=1.0):
    """Body keys for one thrust starting at t0 (coil), contact `hit`, hit-stop to `hold`."""
    k = depth
    return {
        "hip_z": [(t0, b["hip_z"], *ACC), (t0 + 0.08, b["hip_z"] + 1.0 * k, *ACC), (hit, b["hip_z"] - 2.6 * k, *DEC),
                  (hold, b["hip_z"] - 2.7 * k, *S)],
        "hip_y": [(t0, b["hip_y"], *S), (t0 + 0.08, b["hip_y"] + 0.2, *ACC), (hit, b["hip_y"] - 1.0 * k, *DEC),
                  (hold, b["hip_y"] - 1.05 * k, *S)],
        "hip_yaw": [(t0, b["hip_yaw"], *S), (t0 + 0.08, b["hip_yaw"] + 7.0 * k, *ACC), (hit, b["hip_yaw"] - 6.0 * k, *DEC),
                    (hold, b["hip_yaw"] - 6.5 * k, *S)],
        "sp_y": [(t0, b["sp_y"], *S), (t0 + 0.09, b["sp_y"] + 6.0 * k, *ACC), (hit, b["sp_y"] - 9.0 * k, *DEC),
                 (hold, b["sp_y"] - 9.5 * k, *S)],
        "sp_x": [(t0, b["sp_x"], *S), (t0 + 0.09, b["sp_x"] - 3.0 * k, *ACC), (hit, b["sp_x"] + 9.0 * k, *DEC),
                 (hold, b["sp_x"] + 9.5 * k, *S)],
        "hd_x": [(t0, b["hd_x"], *S), (t0 + 0.09, b["hd_x"] + 2.0, *ACC), (hit, b["hd_x"] - 8.0 * k, *DEC),
                 (hold, b["hd_x"] - 8.0 * k, *S)],
        "hd_y": [(t0, b["hd_y"], *S), (t0 + 0.09, b["hd_y"] - 6.0 * k, *ACC), (hit, b["hd_y"] + 8.0 * k, *DEC),
                 (hold, b["hd_y"] + 8.0 * k, *S)],
        # the rear hand drives the shaft THROUGH the front fist: the front grip slides back to the butt
        "gl_s": [(t0, b["gl_s"], *S), (t0 + 0.08, b["gl_s"] + 1.0, *ACC), (hit, b["gl_s"] - 5.5 * k, *DEC),
                 (hold, b["gl_s"] - 5.6 * k, *S)],
        "gr_s": [(t0, b["gr_s"], *S), (t0 + 0.08, b["gr_s"] - 0.6, *ACC), (hit, b["gr_s"] + 1.5 * k, *DEC),
                 (hold, b["gr_s"] + 1.5 * k, *S)],
        "fl_z": [(t0, 0.0, *S), (t0 + 0.10, 0.0, *S), (hit - 0.01, -step * k, *DEC), (hold, -step * k, *S)],
        "fl_y": [(t0, 0.0), (t0 + 0.07, 0.0, *DEC), (t0 + 0.14, 1.9 * k, *S), (hit - 0.04, 1.2 * k, *ACC),
                 (hit - 0.01, 0.0), (hold, 0.0)],
    }


def _recover(K, b, t, L):
    """Close every thrust curve back onto the brace by L (feet: lift then settle)."""
    for p, ks in K.items():
        if p in ("fl_z",):
            ks += [(t + 0.08, ks[-1][1], *S), (L - 0.06, 0.0, *DEC), (L, 0.0)]
        elif p in ("fl_y",):
            ks += [(t + 0.02, 0.0, *DEC), (t + 0.08, 1.9, *S), (L - 0.10, 1.2, *ACC), (L - 0.06, 0.0), (L, 0.0)]
        else:
            ks += [(L - 0.02, b[p], *S), (L, b[p])]


def thrust():
    L, HIT, HOLD = 0.60, 0.25, 0.32
    v, sol = rest_vals()
    b = dict(BRACE)
    K = _thrust_body(b, 0.0, HIT, HOLD, L, L)
    _recover(K, b, HOLD, L)
    rk.const_keys(v, L, False, K)
    goals = [(0.0, HAND, DIR, S),
             (0.09, HAND + np.array([-0.4, 0.2, 2.6]), DIR + np.array([0, 0.03, 0]), ACC),     # coil back
             (HIT, np.array([-2.2, 8.6, -9.8]), np.array([0.04, -0.14, -0.99]), DEC),           # drive, locked out
             (HOLD, np.array([-2.2, 8.7, -9.9]), np.array([0.04, -0.13, -0.99]), S),            # hit-stop
             (0.46, HAND + np.array([0.3, -0.4, -1.6]), DIR, S),                                # pull back, overshoot
             (L, HAND, DIR, None)]
    log = rk.arm_goals(goals, "right", ITEM, seed=sol, length=L)
    solve = rk.make_solve(L, False, FEET, item=ITEM)
    return (L, False, solve, "0.60 s one-shot from/to IDLE_SPEARMAN; lunge step 0.15-0.24; contact t=0.25 s = "
            "tick 5 (RoleMove.SPEAR_THRUST); hit-stop to 0.32; recover to 0.60", (0.09, HIT, HOLD, 0.46), None)


def double_thrust():
    L, H1, H2 = 0.80, 0.25, 0.55
    v, sol = rest_vals()
    b = dict(BRACE)
    K = _thrust_body(b, 0.0, H1, 0.29, L, L, step=2.6, depth=0.8)
    # short retract (0.29-0.44) then the second, deeper drive
    b2 = dict(b)
    retract = {"hip_z": b["hip_z"] - 1.2, "hip_y": b["hip_y"] - 0.6, "hip_yaw": b["hip_yaw"] + 2.0,
               "sp_y": b["sp_y"] + 1.0, "sp_x": b["sp_x"] + 3.0, "hd_x": b["hd_x"] - 3.0, "hd_y": b["hd_y"] + 2.0,
               "gl_s": b["gl_s"] - 1.0, "gr_s": b["gr_s"]}
    second = {"hip_z": b["hip_z"] - 3.2, "hip_y": b["hip_y"] - 1.2, "hip_yaw": b["hip_yaw"] - 8.0,
              "sp_y": b["sp_y"] - 11.0, "sp_x": b["sp_x"] + 11.0, "hd_x": b["hd_x"] - 9.0, "hd_y": b["hd_y"] + 9.0,
              "gl_s": b["gl_s"] - 6.0, "gr_s": b["gr_s"] + 2.0}
    for p in retract:
        K[p] += [(0.42, retract[p], *ACC), (H2, second[p], *DEC), (0.61, second[p], *S)]
    K["fl_z"] += [(0.42, -2.6, *S), (H2 - 0.01, -3.6, *DEC), (0.61, -3.6, *S)]
    K["fl_y"] += [(0.40, 0.0, *DEC), (0.45, 0.9, *ACC), (H2 - 0.02, 0.0), (0.61, 0.0)]
    _recover(K, b2, 0.61, L)
    rk.const_keys(v, L, False, K)
    goals = [(0.0, HAND, DIR, S),
             (0.09, HAND + np.array([-0.3, 0.2, 2.2]), DIR, ACC),
             (H1, np.array([-2.4, 8.8, -9.0]), np.array([0.05, -0.15, -0.99]), DEC),
             (0.29, np.array([-2.4, 8.9, -9.1]), np.array([0.05, -0.15, -0.99]), S),
             (0.42, HAND + np.array([0.0, 0.0, 1.0]), DIR, ACC),                           # short retract
             (H2, np.array([-2.0, 8.4, -10.2]), np.array([0.03, -0.12, -0.99]), DEC),    # second, deeper
             (0.61, np.array([-2.0, 8.5, -10.3]), np.array([0.03, -0.12, -0.99]), S),
             (0.72, HAND + np.array([0.2, -0.3, -1.2]), DIR, S),
             (L, HAND, DIR, None)]
    rk.arm_goals(goals, "right", ITEM, seed=sol, length=L)
    solve = rk.make_solve(L, False, FEET, item=ITEM)
    return (L, False, solve, "0.80 s one-shot from/to IDLE_SPEARMAN; contacts t=0.25 s (tick 5) and t=0.55 s "
            "(tick 11, RoleMove.DOUBLE_THRUST_SECOND_HIT); short retract 0.29-0.42", (0.09, H1, 0.29, 0.42, H2, 0.61),
            None)


def brace_strike():
    L, HIT, HOLD = 0.50, 0.15, 0.21
    v, sol = rest_vals()
    b = dict(BRACE)
    K = {
        "hip_z": [(0.0, b["hip_z"], *S), (0.05, b["hip_z"] + 0.6, *ACC), (HIT, b["hip_z"] - 3.2, *DEC),
                  (HOLD, b["hip_z"] - 3.3, *S)],
        "hip_y": [(0.0, b["hip_y"], *S), (0.05, b["hip_y"] - 0.5, *ACC), (HIT, b["hip_y"] - 2.0, *DEC),
                  (HOLD, b["hip_y"] - 2.1, *S)],
        "sp_x": [(0.0, b["sp_x"], *S), (0.05, b["sp_x"] - 2.0, *ACC), (HIT, b["sp_x"] + 14.0, *DEC),
                 (HOLD, b["sp_x"] + 15.0, *S)],
        "sp_y": [(0.0, b["sp_y"], *S), (0.05, b["sp_y"] + 3.0, *ACC), (HIT, b["sp_y"] - 6.0, *DEC),
                 (HOLD, b["sp_y"] - 6.0, *S)],
        "hip_yaw": [(0.0, b["hip_yaw"], *S), (0.05, b["hip_yaw"] + 3.0, *ACC), (HIT, b["hip_yaw"] - 4.0, *DEC),
                    (HOLD, b["hip_yaw"] - 4.0, *S)],
        "hd_x": [(0.0, b["hd_x"], *S), (0.05, b["hd_x"] + 3.0, *ACC), (HIT, b["hd_x"] - 12.0, *DEC),
                 (HOLD, b["hd_x"] - 12.0, *S)],
        "hd_y": [(0.0, b["hd_y"], *S), (HIT, b["hd_y"] + 5.0, *DEC), (HOLD, b["hd_y"] + 5.0, *S)],
        "ck_x": [(0.0, b["ck_x"], *S), (HIT, b["ck_x"] + 6.0, *DEC), (HOLD, b["ck_x"] + 6.0, *S)],
        "gl_s": [(0.0, b["gl_s"], *S), (HIT, b["gl_s"] - 2.0, *DEC), (HOLD, b["gl_s"] - 2.0, *S)],
        # the front foot stamps IN (small, heavy), the rear foot shoves
        "fl_z": [(0.0, 0.0, *S), (0.03, 0.0, *S), (HIT - 0.01, -2.4, *DEC), (HOLD, -2.4, *S)],
        "fl_y": [(0.0, 0.0), (0.01, 0.0, *DEC), (0.06, 1.8, *S), (HIT - 0.04, 1.0, *ACC), (HIT - 0.01, 0.0), (HOLD, 0.0)],
    }
    _recover(K, b, HOLD, L)
    rk.const_keys(v, L, False, K)
    goals = [(0.0, HAND, DIR, S),
             (0.05, HAND + np.array([0.0, 0.5, 1.0]), DIR + np.array([0, 0.06, 0]), ACC),
             (HIT, HAND + np.array([-0.4, -1.4, -6.8]), DIR + np.array([0, -0.10, 0]), DEC),     # shove
             (HOLD, HAND + np.array([-0.4, -1.3, -6.9]), DIR + np.array([0, -0.10, 0]), S),
             (0.27, HAND + np.array([-0.5, -0.6, -4.4]), DIR, S),
             (0.36, HAND + np.array([-0.2, 0.2, -1.4]), DIR, S),
             (L, HAND, DIR, None)]
    rk.arm_goals(goals, "right", ITEM, seed=sol, length=L)
    solve = rk.make_solve(L, False, FEET, item=ITEM)
    return (L, False, solve, "0.50 s one-shot from/to IDLE_SPEARMAN; brace shove contact t=0.15 s = tick 3 "
            "(RoleMove.SPEAR_BRACE_STRIKE); hit-stop to 0.21", (0.05, HIT, HOLD, 0.34), None)


CLIPS = {"IDLE_SPEARMAN": idle, "SPEAR_THRUST": thrust, "SPEAR_DOUBLE_THRUST": double_thrust,
         "SPEAR_BRACE_STRIKE": brace_strike}

if __name__ == "__main__":
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    names = [a for a in argv if not a.startswith("--")] or list(CLIPS)
    for n in names:
        rk.run(n, CLIPS[n], "settler_guard.png", ITEM)
