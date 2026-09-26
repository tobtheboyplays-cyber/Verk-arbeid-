"""Hero Captain -- GREAT AXE (plan/CAPTAIN.md), battle-roles lane 2026-09-26.

    blender -b --factory-startup --python axe.py -- [--fast|--full|--three] [--no-export] CONST ...

Heavy: a two-handed double axe (weapons lane geometry). Everything is carried by the hips: long
coils, a real step, momentum through the target, a hard stop and a laboured recovery. Left hand
high on the haft (second grip), right fist low. One-shots start/end on CAPTAIN_AXE_STANCE t=0.

CAPTAIN_AXE_STANCE     2.40 s loop  port arms: haft across the body, head up by the left shoulder;
                                    heavy breathing, a weight shift.
CAPTAIN_AXE_SWING_A    0.90 s       contact 0.45 (tick 9, CaptainWeaponGoal.SWING_HIT): raised over
                                    the right shoulder, diagonal chop down right -> left.
CAPTAIN_AXE_SWING_B    0.90 s       contact 0.45: the variant -- a rising diagonal from low right.
CAPTAIN_AXE_CLEAVE     1.20 s       wind-up 0.70 (tick 14): coiled far back right, a wide flat arc.
CAPTAIN_SPINNING_CHOP  1.40 s       wind-up 0.60 (tick 12): two sweeping passes, hits 0.60 / 0.90
                                    (right->left, then carried round and back left->right).
CAPTAIN_ARMOUR_BREAKER 1.40 s       wind-up 0.80 (tick 16): vom Tag hang, overhead drop, hit-stop,
                                    the head bites low; laboured recovery.
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
FOOT_R = np.array([-3.3, 24.0, 2.4])
FOOT_L = np.array([3.1, 24.0, -2.6])
FEET = (FOOT_R, FOOT_L)
STANCE = {"hip_x": 0.0, "hip_y": -1.1, "hip_z": 0.4, "hip_p": 0.0, "hip_yaw": 16.0, "hip_r": 0.0,
          "sp_x": 7.0, "sp_y": 6.0, "sp_z": 0.0, "sp_lift": 0.0,
          "hd_x": -5.0, "hd_y": -18.0, "hd_z": 0.0, "ck_x": 5.0, "ck_z": 0.0,
          "wr_y": 0.0, "wr_z": 0.0, "wl_x": 0.0, "wl_y": 0.0, "wl_z": 0.0,
          "gl_w": 1.0, "gl_s": kit.AXE_GRIP2_S, "gr_s": 0.0}
HAND = np.array([-2.8, 11.0, -5.2])             # right fist low at the belt, forward
DIR = np.array([0.45, -0.78, -0.43])            # haft up-left-forward, head by the left shoulder
LEFT_SEED = {"al_x": -50.0, "al_y": 10.0, "al_z": -5.0, "el_l": -70.0, "tw_l": 0.0}
SEED = [-35.0, -10.0, 5.0, -60.0, 0.0, 0.0]


def rest():
    v, sols = kit.rest(STANCE, {"right": (HAND, DIR)}, {"right": SEED}, ITEM)
    v.update(LEFT_SEED)
    return v, sols["right"]


def solve(L, loop):
    return rk.make_solve(L, loop, FEET, item=ITEM)


# =========================================================================== STANCE
def stance():
    L = 2.4
    v, sol = rest()
    b = STANCE
    over = {
        "sp_lift": [(0.0, 0.0, *S), (1.2, 0.34, *S), (2.4, 0.0)],
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (1.2, -1.0, *S), (2.4, 0.0)]),
        "hip_x": [(0.0, 0.0, *S), (1.2, 0.5, *S), (2.4, 0.0)],
        "hd_y": kit.rel(b, "hd_y", [(0.0, 0.0, *S), (0.8, -4.0, *S), (1.8, 5.0, *S), (2.4, 0.0)]),
    }
    rk.const_keys(v, L, True, over)
    rk.arm_goals([(0.0, HAND, DIR, S), (1.2, HAND + np.array([0.2, 0.4, 0.1]), DIR + np.array([0.0, 0.03, 0.0]), S)],
                 "right", ITEM, seed=sol, length=L, loop=True, w_seed=0.03)
    return L, True, solve(L, True), "2.40 s loop: great-axe port arms, heavy breathing", (), None


# =========================================================================== SWINGS
def _swing(L, HIT, coil_hand, coil_dir, hit_hand, hit_dir, thru_hand, thru_dir, yaw, spy, step_dz, rising,
           c0=0.26, c1=0.32, lift=0.12):
    """lift: heavy lift-off, c0: coil reached, c1: the hang (anticipation) ends; contact HIT."""
    v, sol = rest()
    b = STANCE
    K = {
        "hip_yaw": kit.rel(b, "hip_yaw", [(0.0, 0.0, *S), (c0, yaw, *S), (c1, yaw + 1.0, *ACC), (HIT, -yaw * 0.8, *DEC),
                                          (HIT + 0.12, -yaw, *S), (HIT + 0.18, -yaw * 0.9, *S)]),
        "sp_y": kit.rel(b, "sp_y", [(0.0, 0.0, *S), (c0, spy, *S), (c1, spy + 1.0, *ACC), (HIT, -spy * 0.7, *DEC),
                                    (HIT + 0.12, -spy, *S)]),
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (c0, -6.0 if not rising else 6.0, *S), (c1, -6.0 if not rising else 6.0, *ACC),
                                    (HIT, 12.0 if not rising else -4.0, *DEC), (HIT + 0.14, 13.0 if not rising else -3.0, *S)]),
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (c0, 0.4, *S), (c1, 0.4, *ACC), (HIT, -2.0, *DEC), (HIT + 0.14, -2.2, *S)]),
        "hip_z": kit.rel(b, "hip_z", [(0.0, 0.0, *S), (c0, 1.0, *S), (c1, 1.0, *ACC), (HIT, -2.0, *DEC), (HIT + 0.14, -2.2, *S)]),
        "hd_y": kit.rel(b, "hd_y", [(0.0, 0.0, *S), (c0, -10.0, *S), (HIT, 10.0, *DEC), (HIT + 0.14, 14.0, *S)]),
        "hd_x": kit.rel(b, "hd_x", [(0.0, 0.0, *S), (c0, -3.0, *S), (HIT, 4.0, *DEC)]),
        "ck_z": [(0.0, 0.0, *S), (c0, -6.0, *S), (HIT, 8.0, *DEC), (HIT + 0.16, 10.0, *S)],
    }
    kit.close(K, v, L - 0.05, L)
    kit.step(K, "l", c0 - 0.02, HIT - 0.02, step_dz, back=(HIT + 0.22, L - 0.08, L), height=1.8)
    rk.const_keys(v, L, False, K)
    goals = [(0.0, HAND, DIR, S),
             (lift, HAND + np.array([-0.8, -0.6, 1.0]), DIR, S),               # heavy lift-off
             (c0, coil_hand, coil_dir, S),
             (c1, coil_hand + np.array([0.0, -0.2, 0.2]), coil_dir, ACC),       # the hang
             (HIT, hit_hand, hit_dir, LIN),                                     # CONTACT
             (HIT + 0.12, thru_hand, thru_dir, DEC),                            # carried through
             (HIT + 0.20, thru_hand + np.array([0.0, 0.2, 0.3]), thru_dir, S),  # hard stop, weight in it
             (L - 0.12, HAND + np.array([0.4, 0.4, 0.6]), DIR, S),
             (L, HAND, DIR, None)]
    rk.arm_goals(goals, "right", ITEM, seed=sol, length=L)
    return v, sol, (lift, c0, c1, HIT, HIT + 0.12, HIT + 0.20)


def swing_a():
    L, HIT = 0.90, 0.45
    _, _, keep = _swing(L, HIT, np.array([-6.8, 1.0, -0.6]), np.array([-0.35, -0.85, 0.40]),
                        np.array([-1.6, 8.6, -9.4]), np.array([0.35, 0.35, -0.87]),
                        np.array([2.2, 11.0, -8.0]), np.array([0.58, 0.70, -0.42]), 16.0, 18.0, -2.4, False)
    return (L, False, solve(L, False), "0.90 s one-shot from/to CAPTAIN_AXE_STANCE; raised over the right shoulder "
            "by 0.26, hang to 0.32, diagonal chop, contact t=0.45 s = tick 9 (CaptainWeaponGoal.SWING_HIT)", keep, None)


def swing_b():
    L, HIT = 0.90, 0.45
    _, _, keep = _swing(L, HIT, np.array([-6.0, 12.4, -2.0]), np.array([-0.62, 0.55, 0.55]),
                        np.array([-0.8, 6.4, -9.6]), np.array([0.45, -0.55, -0.70]),
                        np.array([2.8, 3.4, -6.6]), np.array([0.70, -0.65, -0.28]), 14.0, 16.0, -2.0, True)
    return (L, False, solve(L, False), "0.90 s one-shot (variant): low-right coil, rising diagonal, contact t=0.45 s "
            "= tick 9", keep, None)


# =========================================================================== CLEAVE (special)
def cleave():
    L, HIT = 1.20, 0.70
    v, sol = rest()
    b = STANCE
    K = {
        "hip_yaw": kit.rel(b, "hip_yaw", [(0.0, 0.0, *S), (0.45, 22.0, *S), (0.60, 24.0, *ACC), (HIT, -18.0, *DEC),
                                          (0.86, -28.0, *S), (0.94, -26.0, *S)]),
        "sp_y": kit.rel(b, "sp_y", [(0.0, 0.0, *S), (0.45, 30.0, *S), (0.60, 32.0, *ACC), (HIT, -20.0, *DEC),
                                    (0.86, -36.0, *S), (0.94, -33.0, *S)]),
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (0.45, -3.0, *S), (0.60, -3.5, *ACC), (HIT, 7.0, *DEC), (0.9, 9.0, *S)]),
        "sp_z": [(0.0, 0.0, *S), (0.45, 5.0, *S), (0.60, 5.0, *ACC), (HIT, -4.0, *DEC), (0.9, -6.0, *S)],
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (0.45, -0.6, *S), (0.60, -0.6, *ACC), (HIT, -2.2, *DEC), (0.9, -2.4, *S)]),
        "hd_y": kit.rel(b, "hd_y", [(0.0, 0.0, *S), (0.45, -18.0, *S), (HIT, 12.0, *DEC), (0.9, 22.0, *S)]),
        "ck_z": [(0.0, 0.0, *S), (0.45, -9.0, *S), (HIT, 10.0, *DEC), (0.9, 13.0, *S)],
    }
    kit.close(K, v, 1.14, L)
    kit.step(K, "l", 0.50, 0.66, -3.0, dx=0.8, back=(0.92, 1.12, L), height=1.8)
    rk.const_keys(v, L, False, K)
    goals = [(0.0, HAND, DIR, S),
             (0.20, np.array([-4.8, 9.8, -3.2]), np.array([-0.30, -0.60, 0.74]), S),
             (0.45, np.array([-7.6, 8.2, 1.2]), np.array([-0.70, -0.10, 0.70]), S),        # coiled far back
             (0.60, np.array([-7.6, 8.0, 1.0]), np.array([-0.72, -0.08, 0.68]), ACC),      # (hang: the telegraph)
             (0.66, np.array([-6.4, 7.8, -5.4]), np.array([-0.94, 0.04, -0.34]), LIN),
             (HIT, np.array([-1.0, 7.6, -9.6]), np.array([0.05, 0.06, -1.0]), LIN),        # CONTACT dead ahead
             (0.78, np.array([2.8, 7.8, -9.0]), np.array([0.88, 0.08, -0.46]), DEC),
             (0.88, np.array([3.2, 8.2, -8.2]), np.array([0.98, 0.10, -0.18]), S),         # hard stop
             (0.96, np.array([3.0, 8.4, -8.4]), np.array([0.96, 0.12, -0.24]), S),
             (1.08, HAND + np.array([0.6, 0.4, -0.4]), DIR, S),
             (L, HAND, DIR, None)]
    rk.arm_goals(goals, "right", ITEM, seed=sol, length=L)
    return (L, False, solve(L, False), "1.20 s one-shot; coil to 0.45, telegraph hang to 0.60, wide flat arc, contact "
            "t=0.70 s = tick 14 (CaptainSpecial.AXE_CLEAVE windup), hard stop 0.88", (0.20, 0.45, 0.6, HIT, 0.78, 0.88), None)


# =========================================================================== SPINNING CHOP (special)
def spinning_chop():
    L, H1, H2 = 1.40, 0.60, 0.90
    v, sol = rest()
    b = STANCE
    K = {
        "hip_yaw": kit.rel(b, "hip_yaw", [(0.0, 0.0, *S), (0.40, 24.0, *S), (0.50, 25.0, *ACC), (H1, -10.0, *LIN),
                                          (0.74, -38.0, *DEC), (0.80, -38.0, *ACC), (H2, 6.0, *LIN), (1.04, 26.0, *DEC),
                                          (1.12, 24.0, *S)]),
        "sp_y": kit.rel(b, "sp_y", [(0.0, 0.0, *S), (0.40, 28.0, *S), (0.50, 30.0, *ACC), (H1, -12.0, *LIN),
                                    (0.74, -40.0, *DEC), (0.80, -40.0, *ACC), (H2, 8.0, *LIN), (1.04, 28.0, *DEC)]),
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (0.40, 2.0, *S), (H1, 6.0, *S), (H2, 6.0, *S), (1.1, 2.0, *S)]),
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (0.40, -0.6, *S), (H1, -2.0, *S), (0.78, -1.2, *S), (H2, -2.0, *S),
                                      (1.1, -1.0, *S)]),
        "hd_y": kit.rel(b, "hd_y", [(0.0, 0.0, *S), (0.40, -16.0, *S), (H1, 6.0, *LIN), (0.76, 20.0, *S), (H2, -4.0, *LIN),
                                    (1.04, -16.0, *S)]),
        "ck_z": [(0.0, 0.0, *S), (0.40, -8.0, *S), (0.76, 12.0, *S), (1.04, -10.0, *S)],
    }
    kit.close(K, v, 1.34, L)
    kit.step(K, "r", 0.66, 0.80, -1.6, dx=1.4, back=(1.10, 1.30, L), height=1.6)
    rk.const_keys(v, L, False, K)
    goals = [(0.0, HAND, DIR, S),
             (0.40, np.array([-7.4, 8.4, 0.8]), np.array([-0.72, -0.10, 0.68]), S),        # coiled right
             (0.50, np.array([-7.4, 8.2, 0.6]), np.array([-0.73, -0.08, 0.66]), ACC),
             (H1, np.array([-1.2, 7.8, -9.6]), np.array([0.10, 0.04, -0.99]), LIN),        # HIT 1 ahead, going left
             (0.74, np.array([3.4, 8.0, -7.0]), np.array([0.95, 0.06, -0.30]), DEC),       # carried round left
             (0.80, np.array([3.6, 8.2, -6.6]), np.array([0.96, 0.06, -0.26]), ACC),       # turn-over (weight shift)
             (H2, np.array([0.4, 7.4, -9.8]), np.array([-0.20, 0.02, -0.98]), LIN),        # HIT 2 ahead, going right
             (1.04, np.array([-6.0, 8.2, -4.0]), np.array([-0.92, 0.06, -0.38]), DEC),
             (1.16, np.array([-5.0, 9.0, -4.6]), np.array([-0.70, -0.30, -0.64]), S),
             (L, HAND, DIR, None)]
    rk.arm_goals(goals, "right", ITEM, seed=sol, length=L)
    return (L, False, solve(L, False), "1.40 s one-shot; coil 0.40-0.50 (telegraph), hit 1 t=0.60 s (tick 12), the "
            "axe carried round and turned over, hit 2 t=0.90 s (tick 18, phase 6)", (0.4, 0.5, H1, 0.74, 0.8, H2, 1.04), None)


# =========================================================================== ARMOUR BREAKER (special)
def armour_breaker():
    L, HIT, HOLD = 1.40, 0.80, 0.88
    v, sol = rest()
    b = STANCE
    K = {
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (0.45, -12.0, *S), (0.70, -14.0, *ACC), (HIT, 16.0, *DEC),
                                    (HOLD, 17.0, *S), (1.10, 11.0, *S)]),
        "sp_y": kit.rel(b, "sp_y", [(0.0, 0.0, *S), (0.45, 6.0, *S), (0.70, 8.0, *ACC), (HIT, -6.0, *DEC), (1.1, -2.0, *S)]),
        "hip_yaw": kit.rel(b, "hip_yaw", [(0.0, 0.0, *S), (0.45, 6.0, *S), (0.70, 8.0, *ACC), (HIT, -8.0, *DEC)]),
        "sp_lift": [(0.0, 0.0, *S), (0.60, 0.6, *S), (0.70, 0.6, *ACC), (HIT, -0.4, *DEC), (1.1, 0.0, *S)],
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (0.45, 0.6, *S), (0.70, 0.6, *ACC), (HIT, -3.0, *DEC),
                                      (HOLD, -3.1, *S), (1.12, -2.0, *S)]),
        "hip_z": kit.rel(b, "hip_z", [(0.0, 0.0, *S), (0.60, 1.4, *S), (0.70, 1.4, *ACC), (HIT, -2.2, *DEC)]),
        "hd_x": kit.rel(b, "hd_x", [(0.0, 0.0, *S), (0.45, -4.0, *S), (0.70, 3.0, *ACC), (HIT, -4.0, *DEC), (1.1, -2.0, *S)]),
        "ck_x": kit.rel(b, "ck_x", [(0.0, 0.0, *S), (0.60, -3.0, *S), (HIT, 6.0, *DEC), (1.1, 3.0, *S)]),
    }
    kit.close(K, v, 1.36, L)
    K["fl_z"] = [(0.0, 0.0), (0.50, 0.0, *S), (0.66, -1.0, *S), (0.72, -1.4, *ACC), (HIT, -3.0, *DEC),
                 (1.14, -3.0, *S), (1.30, 0.0, *DEC), (L, 0.0)]
    K["fl_y"] = [(0.0, 0.0), (0.44, 0.0, *DEC), (0.60, 2.4, *S), (0.70, 2.6, *ACC), (HIT, 0.0), (1.08, 0.0, *DEC),
                 (1.14, 1.9, *S), (1.26, 1.2, *ACC), (1.30, 0.0), (L, 0.0)]
    rk.const_keys(v, L, False, K)
    goals = [(0.0, HAND, DIR, S),
             (0.18, HAND + np.array([0.0, 0.6, 0.4]), DIR, S),
             (0.30, np.array([-7.8, 3.2, -2.6]), np.array([-0.55, -0.70, 0.45]), S),       # out past the right shoulder
             (0.45, np.array([-6.4, -2.6, -2.8]), np.array([-0.20, -0.70, 0.68]), S),       # raised high (vom Tag)
             (0.68, np.array([-6.4, -3.0, -2.4]), np.array([-0.20, -0.64, 0.74]), S),       # the hang = telegraph
             (0.70, np.array([-6.4, -3.0, -2.5]), np.array([-0.20, -0.65, 0.73]), ACC),
             (0.75, np.array([-4.6, -1.6, -8.0]), np.array([-0.10, -0.95, -0.28]), LIN),
             (HIT, np.array([-2.0, 9.0, -10.2]), np.array([0.02, 0.40, -0.92]), None),       # DROP
             (HOLD, np.array([-2.0, 9.3, -10.0]), np.array([0.02, 0.46, -0.89]), DEC3),      # hit-stop, the bite
             (1.10, np.array([-2.2, 10.0, -8.8]), np.array([0.05, 0.52, -0.85]), S),        # committed low
             (1.24, np.array([-2.8, 10.4, -6.8]), np.array([0.30, -0.40, -0.86]), S),       # heaved out, up
             (L, HAND, DIR, None)]
    rk.arm_goals(goals, "right", ITEM, seed=sol, length=L)
    return (L, False, solve(L, False), "1.40 s one-shot; raised 0.45, hang to 0.70 (lead knee up: the telegraph), "
            "overhead drop + stomp contact t=0.80 s = tick 16 (CaptainSpecial.ARMOUR_BREAKER windup), hit-stop to 0.88, "
            "committed low, heaved out", (0.18, 0.3, 0.45, 0.68, 0.7, 0.75, HIT, HOLD, 1.1, 1.24), None)


CLIPS = {"CAPTAIN_AXE_STANCE": stance, "CAPTAIN_AXE_SWING_A": swing_a, "CAPTAIN_AXE_SWING_B": swing_b,
         "CAPTAIN_AXE_CLEAVE": cleave, "CAPTAIN_SPINNING_CHOP": spinning_chop,
         "CAPTAIN_ARMOUR_BREAKER": armour_breaker}

if __name__ == "__main__":
    kit.main(CLIPS, "axe")
