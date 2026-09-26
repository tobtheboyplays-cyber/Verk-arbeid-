"""Longswordsman clips, REDONE for the owner (2026-09-26): "the sword lay inside the person".

    blender -b --factory-startup --python longsword.py -- [--fast|--three] [--no-export] CONST [CONST ...]

Rules: the held longsword is the real item model at its (new) 0.65 third-person scale = 22 px; its
blade/cross-guard is solved OUT of every body box (rolekit.blade_depth in the arm solve) and the
exported clip must pass anim-overkill/aud.py's hard BLADE check (0 px into any body part). Weight:
slow coils, hips first, a real step, momentum through, a hard stop, a laboured recovery.

IDLE_LONGSWORDSMAN   4.00 s loop  the vigil: point planted on the ground before the feet, both
                                  hands stacked on the hilt; breathing, a weight shift, the sword
                                  lifted an inch and planted again (2.0-2.6), glances.
LONGSWORD_STANCE     2.00 s loop  combat guard "Pflug": both hands at the right hip, the point up at
                                  the enemy's face; breathing, a point wobble. Strikes start/end here.
LONGSWORD_CLEAVE     0.80 s       contact 0.40 (tick 8): a slow heavy coil far round to the right-back,
                                  hips uncoil first, step in, a WIDE flat arc right -> left across
                                  several enemies, the off hand lets go, hard stop, recovery.
LONGSWORD_HEAVY      1.30 s       contact 0.70 (tick 14): vom Tag (hilt above the right shoulder, blade
                                  up and out), telegraph hang with the lead knee up, a downswing keyed
                                  on its arc, STOMP, committed low, laboured recovery.
LONGSWORD_HALF_SWORD 0.70 s       contact 0.30 (tick 6): the left hand takes the blade, the sword turns
                                  point-back-out, the pommel punches forward behind a step.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import numpy as np  # noqa: E402

import rolekit as rk  # noqa: E402

S, ACC, ACC4, DEC, DEC3, LIN = rk.SMO, rk.ACC, rk.ACC4, rk.DEC, rk.DEC3, rk.LIN
ITEM = "longsword"
FOOT_R = np.array([-3.1, 24.0, 2.0])
FOOT_L = np.array([3.0, 24.0, -2.4])
FEET = (FOOT_R, FOOT_L)
VIGIL_FEET = (np.array([-2.6, 24.0, 0.4]), np.array([2.6, 24.0, 0.0]))
# ---- combat guard (Pflug)
STANCE = {"hip_x": 0.0, "hip_y": -1.0, "hip_z": 0.4, "hip_p": 0.0, "hip_yaw": 12.0, "hip_r": 0.0,
          "sp_x": 9.0, "sp_y": 4.0, "sp_z": 0.0, "sp_lift": 0.0,
          "hd_x": -7.0, "hd_y": -14.0, "hd_z": 0.0, "ck_x": 5.0, "ck_z": 0.0,
          "wr_y": 0.0, "wr_z": 0.0, "wl_x": 0.0, "wl_y": 0.0, "wl_z": 0.0,
          "gl_w": 1.0, "gl_s": -2.4, "gr_s": 0.0}
HAND = np.array([-3.4, 9.0, -7.2])
DIR = np.array([0.12, -0.45, -0.88])
# ---- vigil
VIGIL = {"hip_x": 0.0, "hip_y": -0.2, "hip_z": 0.0, "hip_p": 0.0, "hip_yaw": 0.0, "hip_r": 0.0,
         "sp_x": 2.0, "sp_y": 0.0, "sp_z": 0.0, "sp_lift": 0.0,
         "hd_x": -2.0, "hd_y": 0.0, "hd_z": 0.0, "ck_x": 2.0, "ck_z": 0.0,
         "wr_y": 0.0, "wr_z": 0.0, "wl_x": 0.0, "wl_y": 0.0, "wl_z": 0.0,
         "gl_w": 1.0, "gl_s": -2.8, "gr_s": 0.0}
V_HAND = np.array([-0.4, 6.6, -9.0])
V_DIR = np.array([0.0, 1.0, -0.04])            # point down to the ground before the feet
LEFT_SEED = {"al_x": -45.0, "al_y": 10.0, "al_z": -5.0, "el_l": -60.0, "tw_l": 0.0}
SEED = [-50.0, -10.0, 5.0, -60.0, 0.0, 0.0]


def rest_vals(base, hand, d, seed=SEED):
    rk.const_keys(base, 1.0, False)
    sol, err = rk.solve_arm6(rk.body_at(0.0), "right", hand, d, item=ITEM, seed=seed, w_seed=0.0)
    print("REST", round(err, 2), [round(v, 1) for v in sol])
    v = dict(base)
    v.update(dict(zip(rk.ARM6["right"], sol)))
    v.update(LEFT_SEED)
    return v, sol


def close(K, v, t_end, L, ease=S):
    for p, ks in K.items():
        if p in ("fl_z", "fr_z", "fl_x", "fr_x", "fl_y", "fr_y"):
            continue
        ks += [(t_end, v.get(p, 0.0), *ease), (L, v.get(p, 0.0))]


def rel(base, p, pts):
    return [(q[0], base[p] + q[1], *q[2:]) for q in pts]


def step_keys(K, lift, land, dz, dx=0.0, back=None, height=1.9):
    """Lead (left) foot: lifts BEFORE it travels, lands, and (back) returns home the same way."""
    K["fl_z"] = [(0.0, 0.0), (lift + 0.05, 0.0, *S), (land, dz, *DEC)]
    K["fl_x"] = [(0.0, 0.0), (lift + 0.05, 0.0, *S), (land, dx, *DEC)]
    K["fl_y"] = [(0.0, 0.0), (lift, 0.0, *DEC), (lift + 0.06, height, *S), (land - 0.03, height * 0.6, *ACC),
                 (land, 0.0)]
    if back:
        b0, b1, L = back
        K["fl_z"] += [(b0 + 0.06, dz, *S), (b1, 0.0, *DEC), (L, 0.0)]
        K["fl_x"] += [(b0 + 0.06, dx, *S), (b1, 0.0, *DEC), (L, 0.0)]
        K["fl_y"] += [(b0, 0.0, *DEC), (b0 + 0.06, height, *S), (b1 - 0.03, height * 0.6, *ACC), (b1, 0.0), (L, 0.0)]


# =========================================================================== IDLE (vigil)
def idle():
    L = 4.0
    v, sol = rest_vals(VIGIL, V_HAND, V_DIR, seed=[-40.0, 10.0, 10.0, -50.0, 0.0, 60.0])
    b = VIGIL
    over = {
        "sp_lift": [(0.0, 0.0, *S), (1.0, 0.26, *S), (2.0, 0.0, *S), (3.0, 0.28, *S), (4.0, 0.0)],
        "sp_x": rel(b, "sp_x", [(0.0, 0.0, *S), (1.0, -0.8, *S), (1.9, 0.4, *S), (2.15, -1.5, *DEC), (2.45, 1.2, *S),
                                (2.65, 0.0, *S), (3.2, -0.6, *S), (4.0, 0.0)]),
        "hip_x": [(0.0, 0.0, *S), (1.3, 0.45, *S), (2.8, -0.35, *S), (4.0, 0.0)],
        "sp_z": [(0.0, 0.0, *S), (1.3, -1.2, *S), (2.8, 1.0, *S), (4.0, 0.0)],
        "hip_y": rel(b, "hip_y", [(0.0, 0.0, *S), (2.1, 0.1, *S), (2.45, -0.3, *DEC), (2.7, 0.0, *S), (4.0, 0.0)]),
        "hd_y": rel(b, "hd_y", [(0.0, 0.0, *S), (0.35, 0.0, *S), (0.5, 1.5, *S), (0.85, -22.0, *DEC), (1.3, -20.0, *S),
                                (1.6, 0.8, *DEC), (1.8, 0.0, *S), (3.1, 0.0, *S), (3.3, 18.0, *DEC), (3.6, 16.0, *S),
                                (3.85, -0.6, *DEC), (4.0, 0.0)]),
        "hd_x": rel(b, "hd_x", [(0.0, 0.0, *S), (0.85, 2.0, *S), (1.8, 0.0, *S), (2.15, 6.0, *S), (2.5, -1.0, *S),
                                (2.8, 0.0, *S), (4.0, 0.0)]),
        "ck_z": [(0.0, 0.0), (1.3, 2.0, *S), (2.8, -2.0, *S), (4.0, 0.0)],
    }
    rk.const_keys(v, L, True, over)
    lift = np.array([0.0, -1.2, 0.0])
    goals = [(0.0, V_HAND, V_DIR, S), (1.9, V_HAND, V_DIR, S),
             (2.15, V_HAND + lift, V_DIR + np.array([0.0, 0.0, -0.08]), DEC),   # lifted an inch...
             (2.4, V_HAND + np.array([0.0, 0.25, 0.0]), V_DIR, S),              # ...planted again (tap)
             (2.6, V_HAND, V_DIR, S)]
    rk.arm_goals(goals, "right", ITEM, seed=sol, length=L, loop=True, w_seed=0.03)
    solve = rk.make_solve(L, True, VIGIL_FEET, item=ITEM)
    return L, True, solve, "4.00 s loop: vigil, point planted before the feet, hands on the hilt; re-plant 2.0-2.6", (), None


# =========================================================================== STANCE (Pflug)
def stance():
    L = 2.0
    v, sol = rest_vals(STANCE, HAND, DIR)
    b = STANCE
    over = {
        "sp_lift": [(0.0, 0.0, *S), (1.0, 0.22, *S), (2.0, 0.0)],
        "sp_x": rel(b, "sp_x", [(0.0, 0.0, *S), (1.0, -0.8, *S), (2.0, 0.0)]),
        "hip_y": rel(b, "hip_y", [(0.0, 0.0, *S), (1.0, -0.15, *S), (2.0, 0.0)]),
        "hd_y": rel(b, "hd_y", [(0.0, 0.0, *S), (0.7, -3.0, *S), (1.4, 2.0, *S), (2.0, 0.0)]),
    }
    rk.const_keys(v, L, True, over)
    goals = [(0.0, HAND, DIR, S), (0.6, HAND + np.array([0.2, -0.2, -0.2]), DIR + np.array([0.02, -0.03, 0]), S),
             (1.3, HAND + np.array([-0.2, 0.2, 0.1]), DIR + np.array([-0.02, 0.03, 0]), S)]
    rk.arm_goals(goals, "right", ITEM, seed=sol, length=L, loop=True, w_seed=0.03)
    solve = rk.make_solve(L, True, FEET, item=ITEM)
    return L, True, solve, "2.00 s loop: Pflug guard, point at the enemy's face, breathing", (), None


# =========================================================================== CLEAVE
def cleave():
    L, HIT = 0.80, 0.40
    v, sol = rest_vals(STANCE, HAND, DIR)
    b = STANCE
    K = {
        "hip_yaw": rel(b, "hip_yaw", [(0.0, 0.0, *S), (0.24, 14.0, *S), (0.28, 15.0, *ACC), (0.36, -18.0, *DEC),
                                      (0.52, -24.0, *S), (0.58, -22.0, *S)]),
        "sp_y": rel(b, "sp_y", [(0.0, 0.0, *S), (0.26, 24.0, *S), (0.29, 25.0, *ACC), (HIT, -18.0, *DEC),
                                (0.52, -36.0, *S), (0.58, -32.0, *S)]),
        "sp_x": rel(b, "sp_x", [(0.0, 0.0, *S), (0.26, -3.0, *S), (0.30, -3.0, *ACC), (HIT, 6.0, *DEC),
                                (0.55, 9.0, *S), (0.62, 7.0, *S)]),
        "sp_z": [(0.0, 0.0, *S), (0.26, 5.0, *S), (0.30, 5.0, *ACC), (HIT, -4.0, *DEC), (0.56, -6.0, *S)],
        "hip_x": [(0.0, 0.0, *S), (0.26, -0.9, *S), (0.30, -0.9, *ACC), (HIT, 0.9, *DEC), (0.56, 1.2, *S)],
        "hip_y": rel(b, "hip_y", [(0.0, 0.0, *S), (0.10, -0.3, *S), (0.26, -1.0, *S), (0.30, -0.9, *ACC),
                                  (HIT, -1.8, *DEC), (0.56, -2.0, *S)]),
        "hip_z": rel(b, "hip_z", [(0.0, 0.0, *S), (0.26, 1.2, *S), (0.30, 1.2, *ACC), (HIT, -1.8, *DEC),
                                  (0.56, -2.2, *S)]),
        "hd_y": rel(b, "hd_y", [(0.0, 0.0, *S), (0.26, -16.0, *S), (HIT, 12.0, *DEC), (0.56, 22.0, *S)]),
        "hd_x": rel(b, "hd_x", [(0.0, 0.0, *S), (0.26, 3.0, *S), (HIT, -3.0, *DEC), (0.56, -2.0, *S)]),
        "ck_z": [(0.0, 0.0, *S), (0.26, -8.0, *S), (HIT, 9.0, *DEC), (0.56, 12.0, *S)],
        # the off hand lets go for the wide finish, re-grips on the way back
        "gl_w": [(0.0, 1.0), (L, 1.0)],   # both hands stay on the grip: the arc is carried by the body
    }
    close(K, v, 0.76, L)
    step_keys(K, 0.22, 0.34, -2.6, dx=0.6, back=(0.60, 0.76, L))
    rk.const_keys(v, L, False, K)
    goals = [(0.0, HAND, DIR, S),
             (0.12, np.array([-5.0, 8.4, -5.6]), np.array([-0.30, -0.40, -0.86]), S),       # heavy lift off
             (0.26, np.array([-7.4, 8.2, 0.6]), np.array([-0.58, -0.08, 0.81]), S),         # coiled far back
             (0.29, np.array([-7.4, 8.2, 0.3]), np.array([-0.62, -0.06, 0.78]), ACC),       # (hang)
             (0.35, np.array([-6.2, 7.6, -5.8]), np.array([-0.93, 0.02, -0.36]), LIN),      # sweeping, point out right
             (HIT, np.array([-1.0, 7.4, -9.4]), np.array([0.06, 0.05, -1.0]), LIN),         # CONTACT dead ahead
             (0.46, np.array([2.6, 7.6, -9.0]), np.array([0.86, 0.06, -0.50]), DEC),        # through, left
             (0.55, np.array([2.8, 8.0, -8.4]), np.array([0.97, 0.08, -0.22]), S),          # carried round, HARD stop
             (0.60, np.array([2.6, 7.9, -8.6]), np.array([0.96, 0.06, -0.26]), S),
             (0.68, np.array([0.6, 6.4, -8.8]), np.array([0.25, -0.60, -0.76]), S),         # back up in front
             (L, HAND, DIR, None)]
    rk.arm_goals(goals, "right", ITEM, seed=sol, length=L)
    solve = rk.make_solve(L, False, FEET, item=ITEM)
    return (L, False, solve, "0.80 s one-shot from/to LONGSWORD_STANCE; coil 0.00-0.29 (hang), hips lead, step "
            "0.22-0.34, wide flat arc right->left, contact t=0.40 s = tick 8 (RoleMove.LONGSWORD_CLEAVE), "
            "carried round to a hard stop 0.55-0.60, recovery", (0.12, 0.26, 0.29, 0.35, HIT, 0.46, 0.55, 0.6, 0.68), None)


# =========================================================================== HEAVY
def heavy():
    L, HIT, HOLD = 1.30, 0.70, 0.78
    v, sol = rest_vals(STANCE, HAND, DIR)
    b = STANCE
    K = {
        "hip_yaw": rel(b, "hip_yaw", [(0.0, 0.0, *S), (0.40, 8.0, *S), (0.60, 10.0, *ACC), (HIT, -10.0, *DEC),
                                      (HOLD, -10.5, *S), (1.0, -6.0, *S)]),
        "sp_x": rel(b, "sp_x", [(0.0, 0.0, *S), (0.15, 2.0, *S), (0.40, -12.0, *S), (0.60, -14.0, *ACC),
                                (HIT, 22.0, *DEC), (HOLD, 23.0, *S), (1.0, 14.0, *S), (1.12, 16.0, *S)]),
        "sp_y": rel(b, "sp_y", [(0.0, 0.0, *S), (0.40, 8.0, *S), (0.60, 10.0, *ACC), (HIT, -8.0, *DEC),
                                (HOLD, -8.5, *S), (1.0, -3.0, *S)]),
        "sp_z": [(0.0, 0.0, *S), (0.55, 3.0, *S), (0.60, 3.2, *ACC), (HIT, -1.5, *DEC), (1.0, 0.0, *S)],
        "sp_lift": [(0.0, 0.0, *S), (0.55, 0.5, *S), (0.60, 0.5, *ACC), (HIT, -0.3, *DEC), (1.0, 0.0, *S),
                    (1.12, 0.35, *S), (1.2, 0.0, *S)],
        "hip_y": rel(b, "hip_y", [(0.0, 0.0, *S), (0.15, -0.5, *S), (0.55, 0.6, *S), (0.60, 0.55, *ACC),
                                  (HIT, -2.6, *DEC), (HOLD, -2.7, *S), (1.0, -1.8, *S)]),
        "hip_z": rel(b, "hip_z", [(0.0, 0.0, *S), (0.55, 1.2, *S), (0.60, 1.3, *ACC), (HIT, -2.0, *DEC),
                                  (HOLD, -2.1, *S), (1.0, -1.0, *S)]),
        "hip_x": [(0.0, 0.0, *S), (0.55, -0.8, *S), (0.60, -0.8, *ACC), (HIT, 0.5, *DEC), (1.0, 0.3, *S)],
        "hd_x": rel(b, "hd_x", [(0.0, 0.0, *S), (0.40, -2.0, *S), (0.60, 4.0, *ACC), (HIT, -8.0, *DEC),
                                (HOLD, -9.0, *S), (1.0, -4.0, *S)]),
        "hd_y": rel(b, "hd_y", [(0.0, 0.0, *S), (0.55, -8.0, *S), (0.60, -8.0, *ACC), (HIT, 8.0, *DEC),
                                (1.0, 4.0, *S)]),
        "ck_x": rel(b, "ck_x", [(0.0, 0.0, *S), (0.55, -2.0, *S), (HIT, 8.0, *DEC), (1.0, 4.0, *S)]),
    }
    close(K, v, 1.26, L)
    # lead knee lifts in the telegraph, STOMPS down with the blade; heavy drag home (lift first)
    K["fl_z"] = [(0.0, 0.0), (0.44, 0.0, *S), (0.56, -0.8, *S), (0.62, -1.2, *ACC), (HIT, -2.8, *DEC),
                 (1.08, -2.8, *S), (1.24, 0.0, *DEC), (L, 0.0)]
    K["fl_y"] = [(0.0, 0.0), (0.38, 0.0, *DEC), (0.52, 2.2, *S), (0.60, 2.4, *ACC), (HIT, 0.0), (1.00, 0.0, *DEC),
                 (1.06, 1.9, *S), (1.20, 1.2, *ACC), (1.24, 0.0), (L, 0.0)]
    rk.const_keys(v, L, False, K)
    goals = [(0.0, HAND, DIR, S),
             (0.15, HAND + np.array([0.0, 0.6, 0.4]), DIR, S),                                # settle, gather
             (0.40, np.array([-6.8, -3.2, -3.4]), np.array([-0.26, -0.62, 0.74]), S),       # vom Tag, blade out
             (0.58, np.array([-6.8, -3.6, -3.0]), np.array([-0.26, -0.56, 0.79]), S),       # the hang
             (0.60, np.array([-6.8, -3.6, -3.1]), np.array([-0.26, -0.57, 0.78]), ACC),
             (0.64, np.array([-5.0, -3.0, -7.8]), np.array([-0.12, -0.95, -0.28]), LIN),    # blade up
             (0.67, np.array([-3.2, 1.6, -9.4]), np.array([-0.02, -0.40, -0.92]), LIN),     # blade forward
             (HIT, np.array([-1.8, 7.4, -9.2]), np.array([0.02, 0.36, -0.93]), None),       # CHOP
             (HOLD, np.array([-1.8, 7.8, -9.1]), np.array([0.02, 0.40, -0.92]), DEC3),      # hit-stop
             (1.00, np.array([-2.2, 9.6, -8.8]), np.array([0.05, 0.48, -0.87]), S),         # committed low
             (1.14, np.array([-3.0, 8.6, -8.2]), np.array([0.08, -0.10, -0.99]), S),        # point climbs
             (L, HAND, DIR, None)]
    rk.arm_goals(goals, "right", ITEM, seed=sol, length=L, w_seed=0.004)
    solve = rk.make_solve(L, False, FEET, item=ITEM)
    return (L, False, solve, "1.30 s one-shot from/to LONGSWORD_STANCE; vom Tag by 0.40; telegraph hang to 0.60 "
            "(lead knee up); downswing 0.60-0.70; chop + stomp contact t=0.70 s = tick 14 (RoleMove.LONGSWORD_HEAVY); "
            "hit-stop to 0.78; committed low to 1.00; recovery", (0.15, 0.40, 0.58, 0.60, 0.64, 0.67, HIT, HOLD, 1.0, 1.14),
            None)


# =========================================================================== HALF-SWORD
def half_sword():
    L, HIT, HOLD = 0.70, 0.30, 0.36
    v, sol = rest_vals(STANCE, HAND, DIR)
    b = STANCE
    K = {
        "gl_s": rel(b, "gl_s", [(0.0, 0.0, *S), (0.12, 8.4, *DEC), (0.46, 8.4, *S), (0.60, 0.0, *S)]),
        "sp_y": rel(b, "sp_y", [(0.0, 0.0, *S), (0.18, 12.0, *S), (0.21, 12.5, *ACC), (HIT, -10.0, *DEC),
                                (HOLD, -10.5, *S)]),
        "sp_x": rel(b, "sp_x", [(0.0, 0.0, *S), (0.18, -2.0, *S), (0.21, -2.0, *ACC), (HIT, 9.0, *DEC),
                                (HOLD, 9.5, *S)]),
        "hip_yaw": rel(b, "hip_yaw", [(0.0, 0.0, *S), (0.16, 8.0, *S), (0.20, 8.0, *ACC), (HIT - 0.03, -8.0, *DEC),
                                      (HOLD, -8.5, *S)]),
        "hip_z": rel(b, "hip_z", [(0.0, 0.0, *S), (0.18, 1.0, *S), (0.21, 1.0, *ACC), (HIT, -2.6, *DEC),
                                  (HOLD, -2.7, *S)]),
        "hip_y": rel(b, "hip_y", [(0.0, 0.0, *S), (0.18, -0.3, *S), (0.21, -0.3, *ACC), (HIT, -1.6, *DEC),
                                  (HOLD, -1.65, *S)]),
        "hd_x": rel(b, "hd_x", [(0.0, 0.0, *S), (0.18, 3.0, *S), (HIT, -8.0, *DEC), (HOLD, -8.0, *S)]),
        "hd_y": rel(b, "hd_y", [(0.0, 0.0, *S), (0.18, -8.0, *S), (HIT, 10.0, *DEC), (HOLD, 10.0, *S)]),
    }
    close(K, v, 0.66, L)
    step_keys(K, 0.14, HIT - 0.01, -2.8, back=(0.46, 0.64, L))
    rk.const_keys(v, L, False, K)
    back = np.array([-0.78, 0.22, 0.58])          # point back and OUT to the right, off the body
    goals = [(0.0, HAND, DIR, S),
             (0.12, np.array([-3.6, 8.0, -8.0]), np.array([-0.60, -0.10, -0.79]), S),       # blade taken, turning
             (0.18, np.array([-5.0, 7.4, -5.2]), back, S),                                  # turned over, cocked
             (0.21, np.array([-5.0, 7.4, -5.0]), back, ACC),
             (HIT, np.array([-2.4, 5.8, -10.2]), back, DEC),                                # POMMEL punch
             (HOLD, np.array([-2.4, 5.9, -10.3]), back, S),
             (0.48, np.array([-3.6, 7.4, -8.0]), np.array([-0.60, -0.10, -0.79]), S),       # turns back
             (0.60, HAND + np.array([0.0, -0.2, -0.4]), DIR, S),
             (L, HAND, DIR, None)]
    rk.arm_goals(goals, "right", ITEM, seed=sol, length=L)
    solve = rk.make_solve(L, False, FEET, item=ITEM)
    return (L, False, solve, "0.70 s one-shot from/to LONGSWORD_STANCE; left hand takes the blade 0.00-0.12; "
            "pommel punch contact t=0.30 s = tick 6 (RoleMove.LONGSWORD_HALF_SWORD); hit-stop to 0.36",
            (0.12, 0.18, 0.21, HIT, HOLD, 0.48, 0.6), None)


CLIPS = {"IDLE_LONGSWORDSMAN": idle, "LONGSWORD_STANCE": stance, "LONGSWORD_CLEAVE": cleave,
         "LONGSWORD_HEAVY": heavy, "LONGSWORD_HALF_SWORD": half_sword}

if __name__ == "__main__":
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    names = [a for a in argv if not a.startswith("--")] or list(CLIPS)
    for n in names:
        rk.run(n, CLIPS[n], "settler_guard.png", ITEM)
