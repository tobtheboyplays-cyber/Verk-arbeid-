"""Hero Captain -- DOUBLE AXE v2 (plan/captain/ANIM-REFERENCES.md), battle-roles lane 2026-09-26.

    blender -b --factory-startup --python axe.py -- [--keys | --three] [--no-export] CONST ...

v1 had the dual-swords faults (arm-only, narrow, small arcs). v2 applies the heavy-weapon rules:
the weight goes UP and BACK first (the torso arches, the hips coil), a readable hang at the top (the
telegraph), then the hips fire, the shoulders follow, the arms and the axe come last; the head drops
through the target and BITES low with the knees deep; the recovery heaves the weight back up.
Two hands: right fist high on the haft, left fist at the butt (IK, gl_w). Hands stay well in front
of the body so the forearms clear the torso. Contacts land on the server ticks.

CAPTAIN_AXE_STANCE     2.40 s loop  ready: haft across the front, head up by the left shoulder
CAPTAIN_AXE_SWING_A    0.90 s       over the right shoulder, diagonal chop, contact 0.45 (tick 9)
CAPTAIN_AXE_SWING_B    0.90 s       drawn back low right, flat sweep, contact 0.45 (tick 9)
CAPTAIN_AXE_CLEAVE     1.20 s       coiled far back right, hang, a wide flat arc, contact 0.70 (tick 14)
CAPTAIN_SPINNING_CHOP  1.40 s       sweep right->left 0.60, turned over the head, back left->right 0.90
CAPTAIN_ARMOUR_BREAKER 1.40 s       straight up over the head, knee up (hang), the drop 0.80 (tick 16)
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import numpy as np  # noqa: E402

import captainkit as kit  # noqa: E402

rk = kit.rk
S, ACC, ACC4, DEC, DEC3, LIN = kit.S, kit.ACC, kit.ACC4, kit.DEC, kit.DEC3, kit.LIN
ITEM = "great_axe"
FOOT_R = np.array([-5.0, 24.0, 1.4])
FOOT_L = np.array([4.6, 24.0, -3.2])
FEET = (FOOT_R, FOOT_L)
STANCE = {"hip_x": 0.0, "hip_y": -0.7, "hip_z": -0.3, "hip_p": 0.0, "hip_yaw": 8.0, "hip_r": 0.0,
          "sp_x": 8.0, "sp_y": 12.0, "sp_z": 0.0, "sp_lift": 0.0,
          "hd_x": -6.0, "hd_y": -18.0, "hd_z": 0.0, "ck_x": 6.0, "ck_z": 0.0,
          "wr_y": 0.0, "wr_z": 0.0, "wl_x": 0.0, "wl_y": 0.0, "wl_z": 0.0,
          "gl_w": 1.0, "gl_s": kit.AXE_GRIP2_S, "gr_s": 0.0}
HAND = np.array([-2.4, 9.4, -8.6])             # right fist forward of the belt
DIR = np.array([0.44, -0.76, -0.48])           # haft up-left-forward: the head by the left shoulder, out front
LEFT_SEED = {"al_x": -50.0, "al_y": 10.0, "al_z": -5.0, "el_l": -70.0, "tw_l": 0.0}
SEED = [-45.0, -10.0, 5.0, -50.0, 0.0, 0.0]


def rest():
    v, sols = kit.rest(STANCE, {"right": (HAND, DIR)}, {"right": SEED}, ITEM)
    v.update(LEFT_SEED)
    return v, sols["right"]


def solve(L, loop):
    return rk.make_solve(L, loop, FEET, item=ITEM)


def u(*v):
    a = np.array(v, float)
    return a / np.linalg.norm(a)


def heavy(L, HIT, lift, hang, pose_lift, pose_mid, pose_hit, pose_bite, yaw, lean, drop, step, bite=None,
          extra=None, mid=None):
    """The heavy-weapon grammar. pose_* = (hand, dir). yaw/lean/drop = (coil, hit, bite) values relative to
    the stance; the hips lead the shoulders by 0.03 s. step = (foot, dz, dx, height)."""
    bite = bite or HIT + 0.10
    mid = mid or HIT - 0.07
    hold = bite + 0.08
    v, sol = rest()
    b = STANCE
    yc, yh, yb = yaw
    lc, lh, lb = lean
    dc, dh, db = drop
    K = {
        "hip_yaw": kit.rel(b, "hip_yaw", [(0.0, 0.0, *S), (lift, yc, *S), (hang, yc + 2.0, *ACC), (HIT, yh, *LIN),
                                          (bite, yb, *DEC), (hold, yb * 0.95, *S)]),
        "sp_y": kit.rel(b, "sp_y", [(0.0, 0.0, *S), (lift + 0.03, yc * 0.8, *S), (hang + 0.03, yc * 0.85, *ACC),
                                    (HIT + 0.02, yh * 1.1, *LIN), (bite + 0.03, yb * 1.1, *DEC), (hold + 0.03, yb, *S)]),
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (lift, -lc, *S), (hang, -lc - 1.0, *ACC), (HIT, lh, *DEC),
                                    (bite, lb, *S), (hold, lb, *S)]),
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (lift, dc, *S), (hang, dc, *ACC), (HIT, dh, *DEC),
                                      (bite, db, *S), (hold, db * 0.95, *S)]),
        "hip_z": kit.rel(b, "hip_z", [(0.0, 0.0, *S), (lift, 1.2, *S), (hang, 1.2, *ACC), (HIT, -1.6, *DEC),
                                      (bite, -2.0, *S)]),
        "hd_x": kit.rel(b, "hd_x", [(0.0, 0.0, *S), (lift, lc * 0.6, *S), (HIT, -lh * 0.6, *DEC), (bite, -lb * 0.6, *S)]),
        "hd_y": kit.rel(b, "hd_y", [(0.0, 0.0, *S), (lift, -yc * 0.7, *S), (HIT, -yh * 0.7, *DEC), (bite, -yb * 0.7, *S)]),
        "ck_x": kit.rel(b, "ck_x", [(0.0, 0.0, *S), (lift, -6.0, *S), (HIT, 12.0, *DEC), (bite, 14.0, *S)]),
        "ck_z": [(0.0, 0.0, *S), (lift, -yc * 0.3, *S), (HIT, 6.0, *DEC), (bite, -yb * 0.3, *S)],
    }
    if extra:
        extra(K, b)
    kit.close(K, v, L - 0.06, L)
    foot, dz, dx, height = step
    kit.step(K, foot, lift - 0.02, HIT - 0.02, dz, dx=dx, back=(hold + 0.06, L - 0.08, L), height=height)
    rk.const_keys(v, L, False, K)
    goals = [(0.0, HAND, DIR, S),
             (lift, pose_lift[0], pose_lift[1], S),                                   # the weight goes up
             (hang, pose_lift[0] + np.array([0.0, -0.3, 0.3]), pose_lift[1], ACC),     # the hang (telegraph)
             (mid, pose_mid[0], pose_mid[1], LIN),                                    # coming over
             (HIT, pose_hit[0], pose_hit[1], LIN),                                    # CONTACT
             (bite, pose_bite[0], pose_bite[1], DEC),                                 # bites through, low
             (hold, pose_bite[0] + np.array([0.0, 0.3, 0.2]), pose_bite[1], S),        # the weight settles
             (L - 0.16, HAND + np.array([0.3, 0.8, 0.8]), DIR, S),                    # heaved back up
             (L, HAND, DIR, None)]
    rk.arm_goals(goals, "right", ITEM, seed=sol, length=L)
    return v, sol, (lift, hang, mid, HIT, bite, hold, L - 0.16)


# =========================================================================== STANCE
def stance():
    L = 2.4
    v, sol = rest()
    b = STANCE
    over = {
        "sp_lift": [(0.0, 0.0, *S), (1.2, 0.34, *S), (2.4, 0.0)],
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (1.2, -1.2, *S), (2.4, 0.0)]),
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (1.2, -0.3, *S), (2.4, 0.0)]),
        "hip_x": [(0.0, 0.0, *S), (1.2, 0.5, *S), (2.4, 0.0)],
        "hd_y": kit.rel(b, "hd_y", [(0.0, 0.0, *S), (0.8, -4.0, *S), (1.8, 4.0, *S), (2.4, 0.0)]),
    }
    rk.const_keys(v, L, True, over)
    rk.arm_goals([(0.0, HAND, DIR, S), (1.2, HAND + np.array([0.2, 0.4, 0.1]), DIR + np.array([0.0, 0.03, 0.0]), S)],
                 "right", ITEM, seed=sol, length=L, loop=True, w_seed=0.03)
    return L, True, solve(L, True), "2.40 s loop: double axe at the ready, head up by the left shoulder, breathing", (), None


# =========================================================================== SWINGS
def swing_a():
    L, HIT = 0.90, 0.45
    _, _, keep = heavy(L, HIT, 0.22, 0.30,
                       (np.array([-6.4, -2.6, 1.4]), u(-0.20, -0.40, 0.89)),   # over the right shoulder, head behind
                       (np.array([-4.8, 0.4, -7.4]), u(0.10, -0.80, -0.59)),   # coming over the top
                       (np.array([-1.8, 7.8, -11.6]), u(0.32, 0.46, -0.83)),   # CONTACT: head down on the target
                       (np.array([0.2, 11.6, -10.2]), u(0.40, 0.80, -0.44)),   # bites low-left
                       (26.0, -10.0, -18.0), (10.0, 20.0, 26.0), (0.5, -2.2, -3.0), ("l", -3.2, -0.4, 1.8))
    return (L, False, solve(L, False), "0.90 s one-shot from/to CAPTAIN_AXE_STANCE; the axe over the right shoulder "
            "by 0.22 (torso arched, hips coiled), hang to 0.30, the hips fire, diagonal chop, contact t=0.45 s = tick 9 "
            "(WeaponClass.GREAT_AXE), bites low-left, heaved back up", keep, None)


def swing_b():
    L, HIT = 0.90, 0.45
    _, _, keep = heavy(L, HIT, 0.22, 0.30,
                       (np.array([-7.6, 7.6, 2.2]), u(-0.78, -0.22, 0.58)),    # drawn back low right, head behind
                       (np.array([-6.6, 7.8, -6.8]), u(-0.72, -0.10, -0.69)),
                       (np.array([-1.6, 7.8, -12.0]), u(0.18, 0.05, -0.98)),   # CONTACT: the head square ahead
                       (np.array([3.8, 8.4, -10.0]), u(0.86, 0.12, -0.49)),    # carried round left
                       (32.0, -8.0, -30.0), (2.0, 10.0, 12.0), (-0.4, -1.8, -2.2), ("l", -2.2, 1.0, 1.4))
    return (L, False, solve(L, False), "0.90 s one-shot (variant): drawn back low right by 0.22, hang to 0.30, "
            "hips-first flat sweep, contact t=0.45 s = tick 9, carried round left", keep, None)


def cleave():
    L, HIT = 1.20, 0.70
    _, _, keep = heavy(L, HIT, 0.40, 0.58,
                       (np.array([-8.0, 5.4, 3.6]), u(-0.82, -0.30, 0.49)),    # coiled far back right
                       (np.array([-6.6, 7.0, -6.8]), u(-0.74, -0.06, -0.67)),
                       (np.array([-1.4, 7.4, -12.2]), u(0.10, 0.05, -0.99)),   # CONTACT dead ahead
                       (np.array([4.6, 7.8, -9.0]), u(0.93, 0.08, -0.36)),     # the wide arc carries on
                       (42.0, -10.0, -44.0), (4.0, 12.0, 16.0), (-0.8, -2.6, -3.0), ("l", -3.0, 0.8, 1.8),
                       bite=0.82)
    return (L, False, solve(L, False), "1.20 s one-shot; coiled far back right by 0.40, hang to 0.58 (telegraph), "
            "a wide flat arc from the hips, contact t=0.70 s = tick 14 (CaptainSpecial.AXE_CLEAVE)", keep, None)


def armour_breaker():
    L, HIT = 1.40, 0.80

    def knee(K, b):   # lead knee up during the hang (the telegraph), then the stomp lands the blow
        pass
    _, _, keep = heavy(L, HIT, 0.45, 0.70,
                       (np.array([-3.0, -5.2, 2.2]), u(0.04, -0.28, 0.96)),    # straight up over the head
                       (np.array([-2.6, -1.4, -8.0]), u(0.04, -0.86, -0.51)),
                       (np.array([-1.8, 8.8, -11.8]), u(0.04, 0.58, -0.81)),   # CONTACT: the drop
                       (np.array([-1.6, 12.8, -9.6]), u(0.04, 0.92, -0.39)),   # bites deep, low
                       (10.0, -4.0, -4.0), (16.0, 24.0, 32.0), (0.8, -3.0, -3.8), ("l", -3.6, -0.2, 2.8),
                       extra=knee, bite=0.90)
    return (L, False, solve(L, False), "1.40 s one-shot; the axe straight up over the head by 0.45, the lead knee "
            "lifts, hang to 0.70 (telegraph), the drop with a stomp, contact t=0.80 s = tick 16 "
            "(CaptainSpecial.ARMOUR_BREAKER), bites deep, heaved out", keep, None)


# =========================================================================== SPINNING CHOP (special)
def spinning_chop():
    """Two passes: coil right, sweep right->left (hit 0.60), the axe carried round and turned over the
    head with a step (0.74-0.80), back left->right (hit 0.90)."""
    L, H1, H2 = 1.40, 0.60, 0.90
    v, sol = rest()
    b = STANCE
    K = {
        "hip_yaw": kit.rel(b, "hip_yaw", [(0.0, 0.0, *S), (0.40, 34.0, *S), (0.50, 36.0, *ACC), (H1, -6.0, *LIN),
                                          (0.74, -42.0, *DEC), (0.80, -44.0, *ACC), (H2, 6.0, *LIN), (1.04, 30.0, *DEC),
                                          (1.14, 26.0, *S)]),
        "sp_y": kit.rel(b, "sp_y", [(0.0, 0.0, *S), (0.43, 28.0, *S), (0.53, 30.0, *ACC), (H1 + 0.02, -8.0, *LIN),
                                    (0.77, -44.0, *DEC), (0.83, -46.0, *ACC), (H2 + 0.02, 8.0, *LIN), (1.07, 30.0, *DEC)]),
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (0.40, 2.0, *S), (H1, 12.0, *S), (0.78, 0.0, *S), (H2, 12.0, *S),
                                    (1.1, 6.0, *S)]),
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (0.40, -1.0, *S), (H1, -2.6, *S), (0.78, -1.2, *S), (H2, -2.8, *S),
                                      (1.1, -1.6, *S)]),
        "hd_y": kit.rel(b, "hd_y", [(0.0, 0.0, *S), (0.40, -24.0, *S), (0.76, 30.0, *S), (1.04, -20.0, *S)]),
        "ck_z": [(0.0, 0.0, *S), (0.40, -10.0, *S), (0.76, 14.0, *S), (1.04, -12.0, *S)],
    }
    kit.close(K, v, 1.34, L)
    kit.step(K, "r", 0.66, 0.80, -2.0, dx=1.6, back=(1.10, 1.30, L), height=1.8)
    rk.const_keys(v, L, False, K)
    goals = [(0.0, HAND, DIR, S),
             (0.40, np.array([-7.8, 7.8, 2.4]), u(-0.76, -0.14, 0.64), S),        # coiled right
             (0.50, np.array([-7.8, 7.6, 2.2]), u(-0.77, -0.12, 0.63), ACC),
             (H1, np.array([-1.4, 7.6, -12.0]), u(0.12, 0.04, -0.99), LIN),       # HIT 1 ahead, going left
             (0.70, np.array([4.8, 7.4, -8.6]), u(0.94, 0.02, -0.34), DEC),       # carried round left
             (0.78, np.array([3.0, -1.6, -5.6]), u(0.60, -0.70, 0.38), S),        # turned over the head
             (0.84, np.array([4.4, 5.8, -8.4]), u(0.80, -0.20, -0.56), ACC),
             (H2, np.array([0.8, 7.6, -12.0]), u(-0.18, 0.04, -0.98), LIN),       # HIT 2 ahead, going right
             (1.02, np.array([-6.4, 8.0, -7.4]), u(-0.92, 0.08, -0.38), DEC),
             (1.16, np.array([-4.4, 9.0, -8.2]), u(-0.50, -0.50, -0.71), S),
             (L, HAND, DIR, None)]
    rk.arm_goals(goals, "right", ITEM, seed=sol, length=L)
    return (L, False, solve(L, False), "1.40 s one-shot; coil 0.40-0.50 (telegraph), hit 1 t=0.60 s (tick 12), the "
            "axe carried round and turned over the head with a step, hit 2 t=0.90 s (tick 18, phase 6)",
            (0.40, 0.50, H1, 0.70, 0.78, 0.84, H2, 1.02, 1.16), None)


CLIPS = {"CAPTAIN_AXE_STANCE": stance, "CAPTAIN_AXE_SWING_A": swing_a, "CAPTAIN_AXE_SWING_B": swing_b,
         "CAPTAIN_AXE_CLEAVE": cleave, "CAPTAIN_SPINNING_CHOP": spinning_chop,
         "CAPTAIN_ARMOUR_BREAKER": armour_breaker}

KEYPOSES = {
    "CAPTAIN_AXE_STANCE": [(0.0, "ready")],
    "CAPTAIN_AXE_SWING_A": [(0.26, "wind-up (hang)"), (0.45, "contact"), (0.55, "bite")],
    "CAPTAIN_AXE_SWING_B": [(0.26, "wind-up (hang)"), (0.45, "contact"), (0.55, "carried round")],
    "CAPTAIN_AXE_CLEAVE": [(0.50, "coil (hang)"), (0.70, "contact"), (0.82, "follow-through")],
    "CAPTAIN_SPINNING_CHOP": [(0.45, "coil"), (0.60, "hit 1"), (0.78, "turned over"), (0.90, "hit 2")],
    "CAPTAIN_ARMOUR_BREAKER": [(0.60, "raised, knee up (hang)"), (0.80, "the drop"), (0.90, "bite")],
}

if __name__ == "__main__":
    kit.main(CLIPS, "axe", keyposes=KEYPOSES)
