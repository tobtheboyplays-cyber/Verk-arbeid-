"""Hero Captain -- DUAL SWORDS (plan/CAPTAIN.md), battle-roles lane 2026-09-26.

    blender -b --factory-startup --python dual.py -- [--fast|--full|--three] [--no-export] CONST ...

Fast, light-footed, agile: a blade in each hand, weight on the balls of the feet, the off blade
always covering while the lead blade works. Every one-shot starts and ends on CAPTAIN_DUAL_STANCE
t=0. Contacts land on the server contact tick; paired hits meet the enemy ahead (-z) naturally.

CAPTAIN_DUAL_STANCE   2.00 s loop  guard: right blade high-forward, left blade low across the belly,
                                   a light bounce, blades breathing.
CAPTAIN_DUAL_LIGHT_A  0.50 s       contact 0.20 (tick 4, GuardMove LIGHT_A): right forehand diagonal,
                                   hips lead, the left blade pulls back to guard.
CAPTAIN_DUAL_LIGHT_B  0.50 s       contact 0.20 (tick 4, LIGHT_B): the variant -- left backhand rising
                                   cut while the right blade recovers high.
CAPTAIN_BLADE_WHIRL   1.20 s       wind-up 0.50 (tick 10): coil, both blades cocked over the left
                                   shoulder; a wide unwinding sweep, hits 0.50 / 0.70 / 0.90.
CAPTAIN_TWIN_THRUST   0.80 s       wind-up 0.30 (tick 6): both points drawn back to the hips, a lunge;
                                   right thrust 0.30, left thrust 0.50 (off-sync, not mirrored).
CAPTAIN_DISARM        0.80 s       wind-up 0.40 (tick 8): the left blade binds the enemy's weapon,
                                   the right flicks it out and away.
CAPTAIN_DODGE_STEP    0.50 s       no wind-up: a quick side-step left off the line, blades tucked.
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
ITEM = "sword"
FOOT_R = np.array([-3.2, 24.0, 2.2])
FOOT_L = np.array([3.0, 24.0, -2.6])
FEET = (FOOT_R, FOOT_L)
STANCE = {"hip_x": 0.0, "hip_y": -0.9, "hip_z": 0.2, "hip_p": 0.0, "hip_yaw": 10.0, "hip_r": 0.0,
          "sp_x": 9.0, "sp_y": 2.0, "sp_z": 0.0, "sp_lift": 0.0,
          "hd_x": -7.0, "hd_y": -10.0, "hd_z": 0.0, "ck_x": 5.0, "ck_z": 0.0,
          "wr_y": 0.0, "wr_z": 0.0, "wl_y": 0.0, "wl_z": 0.0,
          "gl_w": 0.0, "gl_s": 0.0, "gr_s": 0.0}
R_HAND = np.array([-3.4, 6.6, -6.6])
R_DIR = np.array([0.22, -0.62, -0.75])          # right point up at the enemy's face
L_HAND = np.array([2.8, 9.2, -5.4])
L_DIR = np.array([-0.62, -0.18, -0.76])         # left blade low, angled across
SEEDS = {"right": [-55.0, -10.0, 5.0, -55.0, 0.0, 0.0], "left": [-45.0, 10.0, -5.0, -60.0, 0.0, 0.0]}


def rest():
    return kit.rest(STANCE, {"right": (R_HAND, R_DIR), "left": (L_HAND, L_DIR)}, SEEDS, ITEM, ITEM)


def arms(goals_r, goals_l, sols, L, loop=False, w_seed=0.004):
    rk.arm_goals(goals_r, "right", ITEM, seed=sols["right"], length=L, loop=loop, w_seed=w_seed)
    rk.arm_goals(goals_l, "left", ITEM, seed=sols["left"], length=L, loop=loop, w_seed=w_seed)


def solve(L, loop):
    return rk.make_solve(L, loop, FEET, item=None)


# =========================================================================== STANCE
def stance():
    L = 2.0
    v, sols = rest()
    b = STANCE
    over = {
        "sp_lift": [(0.0, 0.0, *S), (0.5, 0.35, *S), (1.0, 0.0, *S), (1.5, 0.35, *S), (2.0, 0.0)],   # light bounce
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (0.5, -0.25, *S), (1.0, 0.0, *S), (1.5, -0.25, *S), (2.0, 0.0)]),
        "hip_x": [(0.0, 0.0, *S), (1.0, 0.35, *S), (2.0, 0.0)],
        "hd_y": kit.rel(b, "hd_y", [(0.0, 0.0, *S), (0.6, -5.0, *S), (1.4, 4.0, *S), (2.0, 0.0)]),
        "sp_y": kit.rel(b, "sp_y", [(0.0, 0.0, *S), (1.0, -2.0, *S), (2.0, 0.0)]),
    }
    rk.const_keys(v, L, True, over)
    wob = np.array([0.25, -0.2, -0.15])
    arms([(0.0, R_HAND, R_DIR, S), (0.7, R_HAND + wob, R_DIR + np.array([0.03, -0.03, 0.0]), S),
          (1.4, R_HAND - wob, R_DIR, S)],
         [(0.0, L_HAND, L_DIR, S), (1.0, L_HAND + np.array([0.0, 0.3, 0.2]), L_DIR, S)], sols, L, loop=True,
         w_seed=0.03)
    return L, True, solve(L, True), "2.00 s loop: dual guard, light bounce, blades breathing", (), None


# =========================================================================== LIGHT A
def light_a():
    L, HIT = 0.50, 0.20
    v, sols = rest()
    b = STANCE
    K = {
        "hip_yaw": kit.rel(b, "hip_yaw", [(0.0, 0.0, *S), (0.10, 12.0, *ACC), (HIT, -5.0, *DEC), (0.30, -7.0, *S)]),
        "sp_y": kit.rel(b, "sp_y", [(0.0, 0.0, *S), (0.10, 10.0, *ACC), (HIT, -6.0, *DEC), (0.30, -8.0, *S)]),
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (0.10, -2.0, *S), (HIT, 5.0, *DEC), (0.30, 6.0, *S)]),
        "sp_z": [(0.0, 0.0, *S), (0.10, 3.0, *S), (HIT, -3.0, *DEC), (0.30, -3.5, *S)],
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (0.10, -0.2, *S), (HIT, -0.9, *DEC), (0.30, -1.0, *S)]),
        "hd_y": kit.rel(b, "hd_y", [(0.0, 0.0, *S), (0.10, -8.0, *S), (HIT, 6.0, *DEC), (0.30, 8.0, *S)]),
    }
    kit.close(K, v, 0.46, L)
    kit.step(K, "l", 0.06, 0.18, -1.6, back=(0.30, 0.44, L), height=1.2)
    rk.const_keys(v, L, False, K)
    arms([(0.0, R_HAND, R_DIR, S),
          (0.10, np.array([-6.2, 3.8, -3.2]), np.array([-0.45, -0.80, 0.40]), ACC),     # cocked high-right
          (HIT, np.array([-1.2, 7.6, -9.6]), np.array([0.55, 0.10, -0.83]), LIN),       # CONTACT ahead
          (0.28, np.array([1.8, 9.8, -7.4]), np.array([0.85, 0.40, -0.35]), DEC),       # through, low-left
          (0.36, np.array([1.2, 9.4, -7.8]), np.array([0.80, 0.30, -0.50]), S),         # stop
          (L, R_HAND, R_DIR, None)],
         [(0.0, L_HAND, L_DIR, S),
          (0.10, L_HAND + np.array([0.6, -0.8, 1.2]), L_DIR, S),                       # off blade pulls to guard
          (0.30, L_HAND + np.array([0.4, -0.4, 1.4]), L_DIR + np.array([0.1, -0.2, 0.0]), S),
          (L, L_HAND, L_DIR, None)], sols, L)
    return (L, False, solve(L, False), "0.50 s one-shot from/to CAPTAIN_DUAL_STANCE; cock 0.00-0.10, right forehand "
            "diagonal, contact t=0.20 s = tick 4 (GuardMove LIGHT_A); off blade covers", (0.10, HIT, 0.28, 0.36), None)


# =========================================================================== LIGHT B (variant)
def light_b():
    L, HIT = 0.50, 0.20
    v, sols = rest()
    b = STANCE
    K = {
        "hip_yaw": kit.rel(b, "hip_yaw", [(0.0, 0.0, *S), (0.10, -8.0, *ACC), (HIT, 8.0, *DEC), (0.30, 10.0, *S)]),
        "sp_y": kit.rel(b, "sp_y", [(0.0, 0.0, *S), (0.10, -10.0, *ACC), (HIT, 8.0, *DEC), (0.30, 10.0, *S)]),
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (0.10, 3.0, *S), (HIT, -2.0, *DEC), (0.30, -1.0, *S)]),
        "sp_z": [(0.0, 0.0, *S), (0.10, -3.0, *S), (HIT, 3.0, *DEC), (0.30, 3.5, *S)],
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (0.10, -0.4, *S), (HIT, -0.1, *DEC)]),
        "hd_y": kit.rel(b, "hd_y", [(0.0, 0.0, *S), (0.10, 6.0, *S), (HIT, -4.0, *DEC)]),
    }
    kit.close(K, v, 0.46, L)
    kit.step(K, "r", 0.05, 0.17, -1.2, dx=0.8, back=(0.30, 0.44, L), height=1.1)
    rk.const_keys(v, L, False, K)
    arms([(0.0, R_HAND, R_DIR, S),
          (0.10, R_HAND + np.array([-0.6, -1.2, 1.4]), R_DIR, S),                        # right recovers high
          (0.32, R_HAND + np.array([-0.4, -0.8, 1.0]), R_DIR, S),
          (L, R_HAND, R_DIR, None)],
         [(0.0, L_HAND, L_DIR, S),
          (0.10, np.array([0.8, 10.4, -4.8]), np.array([-0.62, 0.42, -0.66]), ACC),    # cocked low across (backhand)
          (HIT, np.array([1.6, 6.4, -9.6]), np.array([0.45, -0.45, -0.77]), LIN),        # CONTACT rising, ahead
          (0.28, np.array([4.2, 3.6, -6.8]), np.array([0.80, -0.55, -0.23]), DEC),       # through, high-left
          (0.36, np.array([4.0, 4.2, -7.0]), np.array([0.78, -0.50, -0.36]), S),
          (L, L_HAND, L_DIR, None)], sols, L)
    return (L, False, solve(L, False), "0.50 s one-shot (variant): left backhand rising cut, contact t=0.20 s = tick 4 "
            "(GuardMove LIGHT_B); right blade recovers high", (0.10, HIT, 0.28, 0.36), None)


# =========================================================================== BLADE WHIRL (special)
def blade_whirl():
    L = 1.20
    H = (0.50, 0.70, 0.90)
    v, sols = rest()
    b = STANCE
    K = {
        # coil left (blades cocked over the left shoulder), then a wide unwinding sweep to the right
        "hip_yaw": kit.rel(b, "hip_yaw", [(0.0, 0.0, *S), (0.40, -30.0, *S), (0.48, -31.0, *ACC), (H[0], -18.0, *LIN),
                                          (H[1], 14.0, *LIN), (H[2], 40.0, *DEC), (1.00, 44.0, *S), (1.08, 40.0, *S)]),
        "sp_y": kit.rel(b, "sp_y", [(0.0, 0.0, *S), (0.40, -34.0, *S), (0.48, -35.0, *ACC), (H[0], -20.0, *LIN),
                                    (H[1], 18.0, *LIN), (H[2], 42.0, *DEC), (1.00, 46.0, *S)]),
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (0.40, 6.0, *S), (H[1], 10.0, *S), (1.0, 4.0, *S)]),
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (0.40, -1.4, *S), (H[1], -2.0, *S), (1.0, -1.2, *S)]),
        "sp_lift": [(0.0, 0.0, *S), (0.40, -0.3, *S), (H[1], -0.6, *S), (1.0, 0.0, *S)],
        "hd_y": kit.rel(b, "hd_y", [(0.0, 0.0, *S), (0.40, 20.0, *S), (H[1], 0.0, *S), (1.0, -26.0, *S)]),
        "ck_z": [(0.0, 0.0, *S), (0.40, 8.0, *S), (H[1], -6.0, *S), (1.0, -12.0, *S)],
    }
    kit.close(K, v, 1.16, L)
    kit.step(K, "r", 0.52, 0.72, -2.0, dx=-1.6, back=(0.96, 1.14, L), height=1.5)    # pivot: rear foot swings round
    rk.const_keys(v, L, False, K)
    arms([(0.0, R_HAND, R_DIR, S),
          (0.40, np.array([1.8, 4.2, -4.4]), np.array([0.70, -0.68, 0.20]), S),          # right blade over left shoulder
          (0.48, np.array([1.8, 4.4, -4.6]), np.array([0.72, -0.66, 0.18]), ACC),
          (H[0], np.array([0.4, 7.2, -9.4]), np.array([0.55, -0.05, -0.83]), LIN),       # hit 1 (right blade)
          (H[1], np.array([-4.6, 7.8, -8.2]), np.array([-0.40, 0.05, -0.92]), LIN),      # hit 2 sweeping right
          (H[2], np.array([-8.0, 8.2, -2.6]), np.array([-0.95, 0.10, -0.30]), DEC),      # hit 3 out wide right
          (1.02, np.array([-7.4, 8.0, -3.4]), np.array([-0.90, 0.05, -0.42]), S),
          (L, R_HAND, R_DIR, None)],
         [(0.0, L_HAND, L_DIR, S),
          (0.40, np.array([5.6, 5.4, -2.6]), np.array([0.76, -0.48, 0.44]), S),          # left blade cocked high-left
          (0.48, np.array([5.6, 5.6, -2.8]), np.array([0.77, -0.47, 0.43]), ACC),
          (H[0] + 0.06, np.array([3.8, 7.6, -8.4]), np.array([0.40, 0.02, -0.92]), LIN), # trails the right blade
          (H[1] + 0.06, np.array([0.2, 8.4, -9.6]), np.array([-0.30, 0.10, -0.95]), LIN),
          (H[2] + 0.04, np.array([-3.4, 9.2, -7.2]), np.array([-0.80, 0.20, -0.56]), DEC),
          (1.04, np.array([-2.0, 9.4, -7.6]), np.array([-0.70, 0.10, -0.70]), S),
          (L, L_HAND, L_DIR, None)], sols, L)
    return (L, False, solve(L, False), "1.20 s one-shot; coil left 0.00-0.48 (telegraph: both blades over the left "
            "shoulder), wide unwinding sweep, hits t=0.50/0.70/0.90 (ticks 10/14/18 = phase 0/4/8), the off blade trails "
            "the lead by 0.04-0.06 s (never synced)", (0.40, 0.48) + H + (1.02,), None)


# =========================================================================== TWIN THRUST (special)
def twin_thrust():
    L, H1, H2 = 0.80, 0.30, 0.50
    v, sols = rest()
    b = STANCE
    K = {
        "hip_yaw": kit.rel(b, "hip_yaw", [(0.0, 0.0, *S), (0.24, 6.0, *ACC), (H1, -4.0, *DEC), (0.42, 4.0, *ACC),
                                          (H2, -5.0, *DEC), (0.60, -4.0, *S)]),
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (0.24, -4.0, *ACC), (H1, 8.0, *DEC), (H2, 12.0, *DEC), (0.62, 10.0, *S)]),
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (0.24, -0.2, *S), (H1, -1.4, *DEC), (H2, -1.8, *DEC), (0.62, -1.6, *S)]),
        "hip_z": kit.rel(b, "hip_z", [(0.0, 0.0, *S), (0.24, 1.0, *S), (H1, -2.2, *DEC), (H2, -2.6, *DEC), (0.62, -2.4, *S)]),
        "hd_x": kit.rel(b, "hd_x", [(0.0, 0.0, *S), (0.24, -3.0, *S), (H1, 3.0, *DEC), (H2, 4.0, *S)]),
    }
    kit.close(K, v, 0.76, L)
    kit.step(K, "l", 0.16, H1 - 0.01, -3.2, back=(0.58, 0.76, L), height=1.6)                  # the lunge
    rk.const_keys(v, L, False, K)
    arms([(0.0, R_HAND, R_DIR, S),
          (0.24, np.array([-3.6, 10.2, -1.8]), np.array([0.04, -0.08, -0.99]), ACC),     # point drawn back to the hip
          (H1, np.array([-1.4, 8.0, -11.2]), np.array([0.06, -0.04, -0.99]), DEC),       # THRUST 1
          (0.40, np.array([-2.6, 8.6, -7.4]), np.array([0.10, -0.10, -0.99]), S),        # retract (covers)
          (0.62, np.array([-3.0, 8.0, -7.0]), R_DIR, S),
          (L, R_HAND, R_DIR, None)],
         [(0.0, L_HAND, L_DIR, S),
          (0.24, np.array([4.6, 10.0, -3.6]), np.array([0.10, -0.08, -0.99]), S),
          (0.42, np.array([4.4, 9.8, -3.8]), np.array([0.10, -0.08, -0.99]), ACC),    # held back until its beat
          (H2, np.array([2.6, 8.2, -11.0]), np.array([-0.02, -0.04, -0.99]), DEC),       # THRUST 2
          (0.62, np.array([3.8, 8.8, -8.6]), np.array([-0.15, -0.10, -0.98]), S),     # out from the ribs (audit)
          (L, L_HAND, L_DIR, None)], sols, L)
    return (L, False, solve(L, False), "0.80 s one-shot; points drawn to the hips 0.00-0.24 (telegraph), lunge, right "
            "thrust t=0.30 s (tick 6), left thrust t=0.50 s (tick 10 = phase 4)", (0.24, H1, 0.40, 0.42, H2, 0.62), None)


# =========================================================================== DISARM (special)
def disarm():
    L, HIT = 0.80, 0.40
    v, sols = rest()
    b = STANCE
    K = {
        "hip_yaw": kit.rel(b, "hip_yaw", [(0.0, 0.0, *S), (0.30, -8.0, *S), (0.36, -9.0, *ACC), (HIT, 10.0, *DEC),
                                          (0.52, 12.0, *S)]),
        "sp_y": kit.rel(b, "sp_y", [(0.0, 0.0, *S), (0.30, -10.0, *S), (0.36, -10.0, *ACC), (HIT, 12.0, *DEC)]),
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (0.30, 4.0, *S), (HIT, 2.0, *S)]),
        "hd_x": kit.rel(b, "hd_x", [(0.0, 0.0, *S), (0.30, 4.0, *S), (HIT, 0.0, *S)]),
    }
    kit.close(K, v, 0.76, L)
    kit.step(K, "l", 0.10, 0.26, -1.8, back=(0.56, 0.74, L), height=1.2)
    rk.const_keys(v, L, False, K)
    arms([(0.0, R_HAND, R_DIR, S),
          (0.26, np.array([-1.4, 9.8, -8.4]), np.array([0.30, 0.25, -0.92]), S),         # right blade low, under the bind
          (0.32, np.array([-1.2, 10.0, -8.6]), np.array([0.28, 0.28, -0.92]), ACC),
          (0.36, np.array([-2.8, 8.4, -8.6]), np.array([-0.22, -0.02, -0.97]), LIN),     # flick under way (audit: tip pop)
          (HIT, np.array([-5.0, 5.8, -7.6]), np.array([-0.70, -0.40, -0.59]), DEC),      # FLICK up and out right
          (0.54, np.array([-6.4, 4.8, -6.4]), np.array([-0.85, -0.45, -0.28]), S),
          (L, R_HAND, R_DIR, None)],
         [(0.0, L_HAND, L_DIR, S),
          (0.20, np.array([0.6, 6.6, -9.4]), np.array([-0.50, -0.45, -0.74]), S),        # left blade binds across
          (HIT, np.array([1.2, 6.8, -9.0]), np.array([-0.45, -0.40, -0.80]), S),         # holds the bind
          (0.56, np.array([2.2, 8.0, -7.2]), L_DIR, S),
          (L, L_HAND, L_DIR, None)], sols, L)
    return (L, False, solve(L, False), "0.80 s one-shot; the left blade binds the weapon 0.00-0.36 (telegraph), right "
            "blade flicks it out and away, contact t=0.40 s = tick 8", (0.20, 0.30, 0.36, HIT, 0.54), None)


# =========================================================================== DODGE STEP (special)
def dodge_step():
    L = 0.50
    v, sols = rest()
    b = STANCE
    K = {
        "hip_x": [(0.0, 0.0, *S), (0.08, -0.4, *ACC), (0.22, 2.6, *DEC), (0.32, 2.4, *S)],
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (0.08, -0.6, *ACC), (0.20, -1.6, *DEC), (0.32, -1.2, *S)]),
        "hip_r": [(0.0, 0.0, *S), (0.08, 3.0, *ACC), (0.22, -6.0, *DEC), (0.32, -3.0, *S)],
        "sp_z": [(0.0, 0.0, *S), (0.10, 4.0, *S), (0.22, -7.0, *DEC), (0.32, -4.0, *S)],
        "hd_z": [(0.0, 0.0, *S), (0.10, -3.0, *S), (0.22, 5.0, *DEC), (0.32, 2.0, *S)],
    }
    kit.close(K, v, 0.48, L)
    # left foot pushes off first, the right follows: the whole body slides off the line (left)
    K["fl_x"] = [(0.0, 0.0), (0.10, 0.0, *S), (0.19, 3.2, *DEC), (0.34, 3.2, *S), (0.46, 0.0, *S), (L, 0.0)]
    K["fl_y"] = [(0.0, 0.0), (0.05, 0.0, *DEC), (0.10, 1.4, *S), (0.17, 0.8, *ACC), (0.19, 0.0), (0.34, 0.0, *DEC),
                 (0.38, 1.0, *S), (0.44, 0.5, *ACC), (0.46, 0.0), (L, 0.0)]
    K["fr_x"] = [(0.0, 0.0), (0.12, 0.0, *S), (0.24, 2.4, *DEC), (0.36, 2.4, *S), (0.48, 0.0, *S), (L, 0.0)]
    K["fr_y"] = [(0.0, 0.0), (0.12, 0.0, *DEC), (0.17, 1.2, *S), (0.22, 0.6, *ACC), (0.24, 0.0), (L, 0.0)]
    rk.const_keys(v, L, False, K)
    arms([(0.0, R_HAND, R_DIR, S), (0.20, R_HAND + np.array([1.0, 0.8, 1.6]), R_DIR, S), (L, R_HAND, R_DIR, None)],
         [(0.0, L_HAND, L_DIR, S), (0.20, L_HAND + np.array([0.6, -0.8, 1.6]), L_DIR, S), (L, L_HAND, L_DIR, None)],
         sols, L)
    return (L, False, solve(L, False), "0.50 s one-shot, no wind-up: push off the left foot, slide left off the line "
            "0.06-0.24, blades tucked, back to the stance", (0.08, 0.20, 0.32), None)


CLIPS = {"CAPTAIN_DUAL_STANCE": stance, "CAPTAIN_DUAL_LIGHT_A": light_a, "CAPTAIN_DUAL_LIGHT_B": light_b,
         "CAPTAIN_BLADE_WHIRL": blade_whirl, "CAPTAIN_TWIN_THRUST": twin_thrust, "CAPTAIN_DISARM": disarm,
         "CAPTAIN_DODGE_STEP": dodge_step}

if __name__ == "__main__":
    kit.main(CLIPS, "dual")
