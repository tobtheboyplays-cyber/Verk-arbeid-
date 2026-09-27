"""Hero Captain -- DUAL SWORDS v2 (plan/captain/ANIM-REFERENCES.md), battle-roles lane 2026-09-26.

    blender -b --factory-startup --python dual.py -- [--keys | --three] [--no-export] CONST ...

v1 was rejected by the owner ("very bad"): arm-only waving, narrow straight-legged stance, hands
hugging the chest, blades pointing at the camera. v2 follows the reference rules:
  * wide, low stance; one blade HIGH (right, point up over the shoulder), one LOW (left, at the knees);
  * the HIPS lead, the shoulders follow 0.02-0.04 s later, then the arm, then the blade;
  * big arcs, arms extended at contact, blades well outside the body silhouette;
  * the off blade is never idle: it guards, chambers at the hip, or strikes in the main hand's recovery;
  * knee dips and front-foot steps carry the weight; a small overshoot on every stop.
Hand goals are authored in the TORSO frame where the body turns (tw()), so the arm travels with the
hips and shoulders instead of waving on its own. Contacts land on the server ticks.

CAPTAIN_DUAL_STANCE   2.00 s loop
CAPTAIN_DUAL_LIGHT_A  0.50 s   right forehand, contact 0.20 (tick 4)
CAPTAIN_DUAL_LIGHT_B  0.50 s   left backhand in the right hand's recovery, contact 0.20 (tick 4)
CAPTAIN_BLADE_WHIRL   1.20 s   telegraph crouch 0.12-0.40, a stepping 360 spin, hits 0.50/0.70/0.90
CAPTAIN_TWIN_THRUST   0.80 s   chamber at the hips, lunge; right point 0.30, left point 0.50, converging
CAPTAIN_DISARM        0.80 s   parry 0.12, bind 0.28, twist-flick contact 0.40
CAPTAIN_DODGE_STEP    0.50 s   load onto the right leg, push left, land and absorb
"""
import math
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
# wide, staggered feet: left foot leads (toward the enemy, -z), right foot back and out
FOOT_R = np.array([-5.0, 24.0, 1.0])          # less stagger than v2a: both feet plant flat (owner/main)
FOOT_L = np.array([4.6, 24.0, -3.0])
FEET = (FOOT_R, FOOT_L)
STANCE = {"hip_x": 0.0, "hip_y": -0.6, "hip_z": -0.4, "hip_p": 0.0, "hip_yaw": 8.0, "hip_r": 0.0,
          "sp_x": 7.0, "sp_y": 18.0, "sp_z": 0.0, "sp_lift": 0.0,
          "hd_x": -5.0, "hd_y": -22.0, "hd_z": 0.0, "ck_x": 6.0, "ck_z": 0.0,
          "wr_y": 0.0, "wr_z": 0.0, "wl_y": 0.0, "wl_z": 0.0,
          "gl_w": 0.0, "gl_s": 0.0, "gr_s": 0.0}
YAW0 = STANCE["hip_yaw"] + STANCE["sp_y"]       # the torso's absolute yaw in the stance
R_HAND = np.array([-7.2, -0.6, -5.2])           # right fist above the shoulder, out to the side
R_DIR = np.array([0.20, -0.90, -0.38])          # point UP, tipped toward the enemy (the high blade)
L_HAND = np.array([3.2, 10.0, -9.6])            # left fist low and well forward
L_DIR = np.array([-0.18, 0.28, -0.94])          # point at the enemy's knees (the low blade)
SEEDS = {"right": [-80.0, -20.0, 20.0, -70.0, 0.0, 0.0], "left": [-40.0, 10.0, -5.0, -30.0, 0.0, 0.0]}


def Ry(deg):
    """Model-space yaw: POSITIVE turns the front (-Z) toward the settler's right (-X)."""
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0.0, s], [0.0, 1.0, 0.0], [-s, 0.0, c]])


def tw(yaw, p):
    """A point/direction authored in the stance torso frame, carried round by an extra torso yaw."""
    return Ry(yaw) @ np.asarray(p, float)


def rest():
    return kit.rest(STANCE, {"right": (R_HAND, R_DIR), "left": (L_HAND, L_DIR)}, SEEDS, ITEM, ITEM)


def arms(goals_r, goals_l, sols, L, loop=False, w_seed=0.004):
    rk.arm_goals(goals_r, "right", ITEM, seed=sols["right"], length=L, loop=loop, w_seed=w_seed)
    rk.arm_goals(goals_l, "left", ITEM, seed=sols["left"], length=L, loop=loop, w_seed=w_seed)


def solve(L, loop):
    return rk.make_solve(L, loop, FEET, item=None)


def head_counter(b, yaw_keys, k=0.8):
    """The head keeps the enemy: counter-turns k x the torso's extra yaw."""
    return kit.rel(b, "hd_y", [(q[0], -k * q[1], *q[2:]) for q in yaw_keys])


# =========================================================================== STANCE
def stance():
    L = 2.0
    v, sols = rest()
    b = STANCE
    over = {
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (0.5, -0.45, *S), (1.0, 0.0, *S), (1.5, -0.45, *S), (2.0, 0.0)]),
        "sp_lift": [(0.0, 0.0, *S), (0.5, 0.3, *S), (1.0, 0.0, *S), (1.5, 0.3, *S), (2.0, 0.0)],
        "hip_x": [(0.0, 0.0, *S), (1.0, 0.4, *S), (2.0, 0.0)],
        "sp_y": kit.rel(b, "sp_y", [(0.0, 0.0, *S), (1.0, -3.0, *S), (2.0, 0.0)]),
        "hd_y": kit.rel(b, "hd_y", [(0.0, 0.0, *S), (1.0, 2.5, *S), (2.0, 0.0)]),
    }
    rk.const_keys(v, L, True, over)
    arms([(0.0, R_HAND, R_DIR, S), (0.8, R_HAND + np.array([0.3, -0.3, -0.2]), R_DIR + np.array([0.04, -0.03, 0.0]), S),
          (1.5, R_HAND + np.array([-0.2, 0.2, 0.1]), R_DIR, S)],
         [(0.0, L_HAND, L_DIR, S), (1.0, L_HAND + np.array([0.2, 0.4, -0.2]), L_DIR + np.array([0.0, 0.04, 0.0]), S)],
         sols, L, loop=True, w_seed=0.03)
    return (L, True, solve(L, True), "2.00 s loop: wide low guard, right blade high (point up), left blade low "
            "(point at the knees), bounce on bent knees", (), None)


# =========================================================================== LIGHT A
def light_a():
    """Right forehand. Coil right (hips + shoulders), the blade cocked behind the right shoulder; the HIPS
    unwind first, the shoulders 0.02 s later, the arm whips through a flat descending diagonal; the left
    foot steps in, the knee dips; the blade is carried low-left, and the left blade chambers at the hip
    (it is Light B's wind-up)."""
    L, HIT = 0.50, 0.20
    v, sols = rest()
    b = STANCE
    hy = [(0.0, 0.0, *S), (0.10, 22.0, *ACC), (HIT, -8.0, *LIN), (0.28, -24.0, *DEC), (0.36, -21.0, *S)]
    sy = [(0.0, 0.0, *S), (0.12, 20.0, *ACC), (HIT + 0.02, -10.0, *LIN), (0.30, -28.0, *DEC), (0.38, -24.0, *S)]
    K = {
        "hip_yaw": kit.rel(b, "hip_yaw", hy),
        "sp_y": kit.rel(b, "sp_y", sy),
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (0.10, -5.0, *S), (HIT, 9.0, *DEC), (0.30, 11.0, *S)]),
        "sp_z": [(0.0, 0.0, *S), (0.10, 7.0, *S), (HIT, -7.0, *DEC), (0.30, -8.0, *S)],
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (0.10, 0.4, *S), (HIT, -1.4, *DEC), (0.28, -1.7, *S)]),
        "hip_z": kit.rel(b, "hip_z", [(0.0, 0.0, *S), (0.10, 0.8, *S), (HIT, -1.4, *DEC), (0.28, -1.6, *S)]),
        "hd_y": head_counter(b, [(0.0, 0.0, *S), (0.11, 42.0, *S), (HIT + 0.01, -40.0, *DEC), (0.30, -50.0, *S)], 0.75),
        "ck_z": [(0.0, 0.0, *S), (0.10, -8.0, *S), (HIT, 8.0, *DEC), (0.30, 10.0, *S)],
    }
    kit.close(K, v, 0.47, L)
    kit.step(K, "l", 0.07, 0.19, -2.8, dx=-0.6, back=(0.32, 0.46, L), height=1.6)
    rk.const_keys(v, L, False, K)
    coil = 42.0                                                          # torso yaw at the coil
    arms([(0.0, R_HAND, R_DIR, S),
          (0.11, tw(coil, [-6.0, -2.4, 3.6]), tw(coil, [-0.15, -0.55, 0.82]), ACC),   # cocked behind the shoulder
          (0.16, np.array([-6.4, 2.8, -7.4]), np.array([0.40, -0.70, -0.59]), LIN),  # whipping over
          (HIT, np.array([-3.0, 5.4, -12.2]), np.array([0.36, 0.14, -0.92]), LIN),    # CONTACT square ahead, arm long
          (0.24, np.array([1.8, 8.2, -11.4]), np.array([0.86, 0.36, -0.36]), DEC),    # carried through
          (0.30, np.array([3.6, 10.4, -9.6]), np.array([0.72, 0.56, -0.40]), S),      # low-left, overshoot
          (0.36, np.array([3.2, 10.0, -9.6]), np.array([0.72, 0.52, -0.46]), S),      # settles
          (L, R_HAND, R_DIR, None)],
         [(0.0, L_HAND, L_DIR, S),
          (0.11, np.array([2.4, 7.4, -9.2]), np.array([-0.30, -0.55, -0.78]), S),     # guards forward while cocking
          (HIT, np.array([5.6, 10.6, -2.4]), np.array([0.05, -0.50, -0.86]), DEC),    # chambered at the left hip
          (0.34, np.array([5.4, 10.4, -2.8]), np.array([0.05, -0.48, -0.87]), S),
          (L, L_HAND, L_DIR, None)], sols, L)
    return (L, False, solve(L, False), "0.50 s one-shot from/to CAPTAIN_DUAL_STANCE; coil 0.00-0.11 (hips then "
            "shoulders), right forehand, contact t=0.20 s = tick 4 (GuardMove LIGHT_A); left blade chambers at the hip",
            (0.11, 0.16, HIT, 0.24, 0.30, 0.36), None)


# =========================================================================== LIGHT B (variant)
def light_b():
    """Left backhand, delivered in the right hand's recovery: the torso turned right with the left blade
    chambered at the right hip, then the hips snap left and the left arm extends in a rising, flat
    backhand; the right blade goes back up high."""
    L, HIT = 0.50, 0.20
    v, sols = rest()
    b = STANCE
    hy = [(0.0, 0.0, *S), (0.10, 18.0, *ACC), (HIT, -26.0, *DEC), (0.28, -32.0, *S), (0.36, -28.0, *S)]
    sy = [(0.0, 0.0, *S), (0.12, 16.0, *ACC), (HIT + 0.02, -26.0, *DEC), (0.30, -34.0, *S), (0.38, -30.0, *S)]
    K = {
        "hip_yaw": kit.rel(b, "hip_yaw", hy),
        "sp_y": kit.rel(b, "sp_y", sy),
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (0.10, 4.0, *S), (HIT, 2.0, *DEC), (0.30, 1.0, *S)]),
        "sp_z": [(0.0, 0.0, *S), (0.10, -4.0, *S), (HIT, 6.0, *DEC), (0.30, 7.0, *S)],
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (0.10, -0.8, *S), (HIT, -1.6, *DEC), (0.28, -1.8, *S)]),
        "hip_x": [(0.0, 0.0, *S), (0.10, -0.8, *S), (HIT, 1.2, *DEC), (0.30, 1.4, *S)],
        "hd_y": head_counter(b, [(0.0, 0.0, *S), (0.11, 34.0, *S), (HIT + 0.01, -52.0, *DEC), (0.30, -64.0, *S)], 0.7),
        "ck_z": [(0.0, 0.0, *S), (0.10, -6.0, *S), (HIT, 9.0, *DEC), (0.30, 11.0, *S)],
    }
    kit.close(K, v, 0.47, L)
    kit.step(K, "l", 0.06, 0.18, -1.6, dx=1.8, back=(0.32, 0.46, L), height=1.4)
    rk.const_keys(v, L, False, K)
    arms([(0.0, R_HAND, R_DIR, S),
          (0.11, np.array([-7.0, 3.0, -3.2]), np.array([0.10, -0.90, -0.42]), S),     # right lifts clear, high
          (0.30, np.array([-6.8, 0.2, -5.0]), np.array([0.22, -0.88, -0.42]), S),
          (L, R_HAND, R_DIR, None)],
         [(0.0, L_HAND, L_DIR, S),
          (0.11, np.array([-2.6, 10.2, -5.4]), np.array([-0.72, 0.26, 0.64]), ACC),   # chambered at the RIGHT hip
          (0.16, np.array([-1.6, 8.8, -9.6]), np.array([-0.30, 0.10, -0.95]), LIN),
          (HIT, np.array([2.4, 6.6, -11.4]), np.array([0.62, -0.30, -0.72]), LIN),    # CONTACT, rising, arm long
          (0.24, np.array([6.0, 4.6, -8.8]), np.array([0.92, -0.38, -0.08]), DEC),    # through
          (0.30, np.array([7.6, 3.6, -5.8]), np.array([0.84, -0.46, 0.28]), S),       # high-left, overshoot
          (0.36, np.array([7.2, 4.0, -6.2]), np.array([0.86, -0.44, 0.24]), S),
          (L, L_HAND, L_DIR, None)], sols, L)
    return (L, False, solve(L, False), "0.50 s one-shot (variant): left blade chambered at the right hip, hips snap "
            "left, rising flat backhand, contact t=0.20 s = tick 4 (GuardMove LIGHT_B); right blade back up high",
            (0.11, 0.16, HIT, 0.24, 0.30, 0.36), None)


# =========================================================================== BLADE WHIRL (special)
WHIRL_T = [0.0, 0.12, 0.30, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80, 0.86, 0.90, 0.96, 1.04, 1.20]
WHIRL_Y = [0.0, -24.0, -44.0, -46.0, -10.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 312.0, 348.0, 358.0, 364.0,
           361.0, 360.0]


def blade_whirl():
    """Telegraph: sink low and wind LEFT (-46), both blades crossed over the left shoulder. Then a
    committed 360 to the right on the balls of the feet, both arms extended wide: the left blade
    crosses the front at 0.50, the right at 0.70; at 0.90 he is square again and both blades cut out
    wide in an opening X. The feet travel round with the pelvis (pivot), stepping in turn."""
    L = 1.20
    H = (0.50, 0.70, 0.90)
    v, sols = rest()
    b = STANCE
    ease = [S, S, S, ACC, LIN, LIN, LIN, LIN, LIN, LIN, LIN, DEC, DEC, S, S, S]
    hy = [(t, y, *e) for t, y, e in zip(WHIRL_T[:-1], WHIRL_Y[:-1], ease)] + [(WHIRL_T[-1], WHIRL_Y[-1])]
    K = {
        # shoulders trail the hips during the spin (hips lead), then snap past at the finish
        "sp_y": kit.rel(b, "sp_y", [(0.0, 0.0, *S), (0.30, -14.0, *S), (0.40, -16.0, *ACC), (0.50, -10.0, *LIN),
                                    (0.80, -10.0, *LIN), (0.90, 8.0, *DEC), (0.98, 4.0, *S)]),
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (0.30, 10.0, *S), (0.50, 2.0, *S), (0.86, 2.0, *S), (0.92, 12.0, *DEC)]),
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (0.30, -2.2, *S), (0.40, -2.4, *ACC), (0.50, -1.0, *S),
                                      (0.80, -1.0, *S), (0.92, -3.0, *DEC), (1.04, -2.6, *S)]),
        "sp_lift": [(0.0, 0.0, *S), (0.30, -0.4, *S), (0.55, 0.3, *S), (0.80, 0.3, *S), (0.92, -0.4, *S)],
        "hd_y": head_counter(b, [(0.0, 0.0, *S), (0.30, -50.0, *S), (0.40, -54.0, *S)], 0.6),
        "ck_z": [(0.0, 0.0, *S), (0.30, 6.0, *S), (0.50, -14.0, *S), (0.86, -14.0, *S), (0.96, 6.0, *S)],
    }
    # the head turns with the body through the spin (spotting), keyed absolutely after the telegraph
    K["hd_y"] += [(0.50, b["hd_y"] + 10.0, *S), (0.86, b["hd_y"] + 10.0, *S)]
    kit.close(K, v, 1.12, L)
    K["hip_yaw"] = kit.rel(b, "hip_yaw", hy)             # not closed: ends a full turn round == the stance
    # the feet step round in turn: a foot only travels while it is lifted (no planted slide);
    # the pelvis turns continuously over them. (t0, t1, from deg, to deg) per lifted step.
    steps = {"r": [(0.44, 0.60, 0.0, 150.0), (0.70, 0.84, 150.0, 330.0), (0.90, 1.00, 330.0, 360.0)],
             "l": [(0.56, 0.72, 0.0, 200.0), (0.80, 0.94, 200.0, 360.0)]}
    for foot, f0 in (("r", FOOT_R), ("l", FOOT_L)):
        kx, kz, ky = [(0.0, 0.0)], [(0.0, 0.0)], [(0.0, 0.0)]
        for t0, t1, a0, a1 in steps[foot]:
            for i in range(5):
                q = i / 4.0
                t = t0 + (t1 - t0) * q
                a = a0 + (a1 - a0) * (q * q * (3 - 2 * q))
                p = Ry(a) @ f0 - f0
                kx.append((t, float(p[0]), *S))
                kz.append((t, float(p[2]), *S))
            ky += [(t0, 0.0, *DEC), (0.5 * (t0 + t1), 1.6, *S), (t1, 0.0, *S)]
        for k in (kx, kz, ky):
            k.append((L, 0.0))
        K[f"f{foot}_x"], K[f"f{foot}_z"], K[f"f{foot}_y"] = kx, kz, ky
    rk.const_keys(v, L, False, K)
    # arms in the TORSO frame: extended wide; the torso's absolute extra yaw = hip_yaw + sp_y (relative)
    def body(t):
        return float(np.interp(t, WHIRL_T, WHIRL_Y)) + float(np.interp(t, [0.0, 0.30, 0.40, 0.50, 0.80, 0.90, 0.98, L],
                                                                     [0.0, -14.0, -16.0, -10.0, -10.0, 8.0, 4.0, 0.0]))
    RW, RWD = np.array([-12.4, 4.6, 1.6]), np.array([-0.94, 0.06, 0.33])     # right arm out wide, blade trailing
    LW, LWD = np.array([12.0, 4.8, -2.4]), np.array([0.94, 0.06, -0.33])     # left arm out wide, blade leading
    gr = [(0.0, R_HAND, R_DIR, S),
          (0.30, tw(body(0.30), [3.0, 2.6, -5.8]), tw(body(0.30), [0.62, -0.72, 0.30]), S),   # crossed over left shoulder
          (0.40, tw(body(0.40), [3.2, 2.4, -5.6]), tw(body(0.40), [0.62, -0.74, 0.28]), ACC)]
    gl = [(0.0, L_HAND, L_DIR, S),
          (0.30, tw(body(0.30), [5.6, 1.2, -3.0]), tw(body(0.30), [0.55, -0.80, 0.24]), S),
          (0.40, tw(body(0.40), [5.8, 1.0, -2.8]), tw(body(0.40), [0.55, -0.81, 0.22]), ACC)]
    for t in (0.47, 0.54, 0.62, 0.70, 0.78):
        gr.append((t, tw(body(t), RW), tw(body(t), RWD), LIN))
        gl.append((t, tw(body(t), LW), tw(body(t), LWD), LIN))
    gr += [(0.84, np.array([-3.4, 7.0, -10.0]), np.array([0.55, 0.05, -0.83]), LIN),   # both come round in front...
           (0.90, np.array([-9.6, 6.6, -6.8]), np.array([-0.88, 0.10, -0.46]), DEC),   # ...and cut OUT wide (the X)
           (0.98, np.array([-10.0, 7.4, -5.6]), np.array([-0.92, 0.18, -0.34]), S),
           (L, R_HAND, R_DIR, None)]
    gl += [(0.84, np.array([3.2, 7.4, -10.0]), np.array([-0.55, 0.05, -0.83]), LIN),
           (0.90, np.array([9.4, 7.0, -6.8]), np.array([0.88, 0.10, -0.46]), DEC),
           (0.98, np.array([9.8, 7.6, -5.6]), np.array([0.92, 0.18, -0.34]), S),
           (L, L_HAND, L_DIR, None)]
    arms(gr, gl, sols, L)
    return (L, False, solve(L, False), "1.20 s one-shot; telegraph 0.12-0.40 (sink, wind left, blades crossed over "
            "the left shoulder), a stepping 360 to the right with both arms wide: left blade crosses the front 0.50, "
            "right 0.70, square again for the opening X cut 0.90 (ticks 10/14/18 = phases 0/4/8); lands low",
            (0.12, 0.30, 0.40, 0.47, 0.54, 0.62, 0.70, 0.78, 0.84, 0.90, 0.98), None)


# =========================================================================== TWIN THRUST (special)
def twin_thrust():
    """Chamber both points at the hips, elbows back, torso upright; a long lunge off the back foot: the
    right point drives in at 0.30 (right shoulder forward), the left point joins it at 0.50 (left shoulder
    forward) -- both converge on one spot ahead. Push back off the front foot."""
    L, H1, H2 = 0.80, 0.30, 0.50
    v, sols = rest()
    b = STANCE
    hy = [(0.0, 0.0, *S), (0.14, -8.0, *S), (0.20, -9.0, *ACC), (H1, -30.0, *DEC), (0.42, -24.0, *S),
          (H2, -8.0, *DEC), (0.60, -10.0, *S)]
    K = {
        "hip_yaw": kit.rel(b, "hip_yaw", hy),
        "sp_y": kit.rel(b, "sp_y", [(0.0, 0.0, *S), (0.14, -4.0, *S), (0.20, -4.0, *ACC), (H1 + 0.02, -10.0, *DEC),
                                    (H2 + 0.02, 12.0, *DEC), (0.60, 10.0, *S)]),
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (0.14, -8.0, *S), (0.20, -8.0, *ACC), (H1, 20.0, *DEC),
                                    (H2, 24.0, *DEC), (0.60, 22.0, *S)]),
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (0.14, 0.8, *S), (0.20, 0.8, *ACC), (H1, -3.4, *DEC),
                                      (H2, -3.8, *S), (0.60, -3.6, *S)]),
        "hip_z": kit.rel(b, "hip_z", [(0.0, 0.0, *S), (0.14, 1.8, *S), (0.20, 1.8, *ACC), (H1, -4.6, *DEC),
                                      (H2, -5.0, *S), (0.60, -4.8, *S)]),
        "hd_y": head_counter(b, [(0.0, 0.0, *S), (0.14, -12.0, *S), (H1, -40.0, *DEC), (H2, 4.0, *DEC)], 0.8),
        "hd_x": kit.rel(b, "hd_x", [(0.0, 0.0, *S), (H1, -8.0, *DEC), (0.60, -8.0, *S)]),
        "ck_x": kit.rel(b, "ck_x", [(0.0, 0.0, *S), (0.14, -4.0, *S), (H1, 10.0, *DEC), (0.60, 10.0, *S)]),
    }
    kit.close(K, v, 0.76, L)
    kit.step(K, "l", 0.17, H1 - 0.01, -6.4, dx=-0.4, back=(0.60, 0.76, L), height=2.0)   # the lunge
    rk.const_keys(v, L, False, K)
    P = np.array([0.0, 7.4, -18.5])                                        # the one spot both points meet
    rh1, lh2 = np.array([-2.6, 7.6, -13.4]), np.array([2.4, 7.8, -13.2])
    d = lambda h: (P - h) / np.linalg.norm(P - h)                          # noqa: E731
    arms([(0.0, R_HAND, R_DIR, S),
          (0.14, np.array([-6.6, 10.4, 1.0]), np.array([0.10, -0.40, -0.91]), S),     # chambered at the right hip
          (0.20, np.array([-6.6, 10.6, 1.2]), np.array([0.10, -0.40, -0.91]), ACC),
          (H1, rh1, d(rh1), DEC),                                                     # RIGHT POINT, arm long
          (H2, rh1 + np.array([0.4, 0.2, 0.6]), d(rh1), S),                           # stays in
          (0.62, np.array([-4.8, 7.4, -9.8]), R_DIR * 0.4 + d(rh1) * 0.6, S),       # clear of the ribs (audit)
          (L, R_HAND, R_DIR, None)],
         [(0.0, L_HAND, L_DIR, S),
          (0.14, np.array([6.4, 10.4, 0.8]), np.array([-0.10, -0.40, -0.91]), S),     # chambered at the left hip
          (0.34, np.array([6.2, 10.0, 0.0]), np.array([-0.10, -0.36, -0.93]), ACC),   # held for its beat
          (H2, lh2, d(lh2), DEC),                                                     # LEFT POINT converges
          (0.62, np.array([3.4, 8.8, -9.4]), L_DIR * 0.4 + d(lh2) * 0.6, S),
          (L, L_HAND, L_DIR, None)], sols, L)
    return (L, False, solve(L, False), "0.80 s one-shot; both points chambered at the hips 0.00-0.24 (telegraph), "
            "lunge, right point t=0.30 s (tick 6), left point converges t=0.50 s (tick 10 = phase 4), push back",
            (0.14, 0.24, H1, 0.42, H2, 0.62), None)


# =========================================================================== DISARM (special)
def disarm():
    """Parry (left blade vertical, high, 0.12), bind (it rolls over the enemy blade and presses it down
    across the body, 0.28), twist (the hips snap and the right blade hooks under and flicks the weapon up
    and out to the right, contact 0.40)."""
    L, HIT = 0.80, 0.40
    v, sols = rest()
    b = STANCE
    hy = [(0.0, 0.0, *S), (0.12, 14.0, *DEC), (0.28, 10.0, *S), (0.34, 12.0, *ACC), (HIT, -22.0, *DEC),
          (0.50, -26.0, *S)]
    K = {
        "hip_yaw": kit.rel(b, "hip_yaw", hy),
        "sp_y": kit.rel(b, "sp_y", [(0.0, 0.0, *S), (0.12, 12.0, *DEC), (0.30, 14.0, *ACC), (HIT + 0.02, -20.0, *DEC),
                                    (0.52, -24.0, *S)]),
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (0.12, -3.0, *S), (0.28, 10.0, *S), (HIT, 2.0, *DEC)]),
        "sp_z": [(0.0, 0.0, *S), (0.28, -5.0, *S), (HIT, 8.0, *DEC), (0.52, 6.0, *S)],
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (0.12, 0.3, *S), (0.28, -1.8, *S), (HIT, -0.8, *DEC)]),
        "hd_y": head_counter(b, [(0.0, 0.0, *S), (0.12, 26.0, *S), (0.34, 26.0, *S), (HIT, -42.0, *DEC)], 0.8),
        "hd_x": kit.rel(b, "hd_x", [(0.0, 0.0, *S), (0.28, 8.0, *S), (HIT, -6.0, *DEC)]),
    }
    kit.close(K, v, 0.76, L)
    kit.step(K, "l", 0.02, 0.12, -1.8, dx=0.4, back=(0.54, 0.74, L), height=1.2)
    rk.const_keys(v, L, False, K)
    arms([(0.0, R_HAND, R_DIR, S),
          (0.12, np.array([-6.4, 9.0, -1.6]), np.array([0.20, -0.30, -0.93]), S),     # right drops back low
          (0.28, np.array([-3.4, 11.2, -6.4]), np.array([0.30, 0.10, -0.95]), S),     # coiled under the bind
          (0.32, np.array([-3.0, 11.0, -7.0]), np.array([0.25, 0.05, -0.97]), ACC),
          (0.36, np.array([-4.2, 8.4, -9.0]), np.array([-0.12, -0.40, -0.91]), LIN),    # the flick under way (audit)
          (HIT, np.array([-5.8, 4.2, -9.4]), np.array([-0.56, -0.74, -0.37]), DEC),   # FLICK up and out
          (0.52, np.array([-7.8, 2.2, -6.4]), np.array([-0.78, -0.60, -0.16]), S),
          (L, R_HAND, R_DIR, None)],
         [(0.0, L_HAND, L_DIR, S),
          (0.12, np.array([1.6, 2.6, -9.0]), np.array([-0.10, -0.97, -0.20]), DEC),   # PARRY: vertical, high
          (0.28, np.array([-0.8, 8.6, -9.6]), np.array([-0.74, 0.34, -0.58]), S),     # BIND: pressed down across
          (0.34, np.array([-0.6, 8.8, -9.6]), np.array([-0.74, 0.36, -0.57]), S),
          (0.50, np.array([4.6, 7.6, -6.0]), np.array([-0.30, -0.40, -0.87]), S),     # back to guard
          (L, L_HAND, L_DIR, None)], sols, L)
    return (L, False, solve(L, False), "0.80 s one-shot; parry 0.12 (left blade vertical), bind 0.28 (pressed down "
            "across), the twist: hips snap, the right blade flicks the weapon up and out, contact t=0.40 s = tick 8",
            (0.12, 0.28, 0.34, HIT, 0.52), None)


# =========================================================================== DODGE STEP (special)
def dodge_step():
    """Load: a quick dip onto the right leg. Push: the left foot reaches out 4 px, the pelvis and torso lean
    into the step, blades tucked high. Land: the knee absorbs; the feet come back under him."""
    L = 0.50
    v, sols = rest()
    b = STANCE
    K = {
        "hip_x": [(0.0, 0.0, *S), (0.06, -1.8, *ACC), (0.20, 4.8, *DEC), (0.30, 4.2, *S), (0.44, 0.0, *S)],
        "hip_y": kit.rel(b, "hip_y", [(0.0, 0.0, *S), (0.06, -1.6, *ACC), (0.16, 0.4, *S), (0.24, -3.0, *DEC),
                                      (0.32, -2.2, *S)]),
        "hip_r": [(0.0, 0.0, *S), (0.06, 6.0, *S), (0.20, -11.0, *S), (0.30, -6.0, *S)],
        "sp_z": [(0.0, 0.0, *S), (0.06, 7.0, *S), (0.20, -16.0, *S), (0.30, -9.0, *S)],
        "sp_x": kit.rel(b, "sp_x", [(0.0, 0.0, *S), (0.06, 4.0, *S), (0.24, 8.0, *S)]),
        "hd_z": [(0.0, 0.0, *S), (0.20, 8.0, *S), (0.34, 0.0, *S)],
        "ck_z": [(0.0, 0.0, *S), (0.10, -10.0, *S), (0.26, 10.0, *S)],
    }
    kit.close(K, v, 0.46, L)
    K["fl_x"] = [(0.0, 0.0), (0.06, 0.0, *S), (0.18, 5.6, *DEC), (0.34, 5.6, *S), (0.46, 0.0, *S), (L, 0.0)]
    K["fl_y"] = [(0.0, 0.0), (0.06, 0.0, *DEC), (0.11, 1.6, *S), (0.16, 0.8, *ACC), (0.18, 0.0), (0.36, 0.0, *S),
                 (0.40, 1.0, *S), (0.46, 0.0), (L, 0.0)]
    K["fr_x"] = [(0.0, 0.0), (0.12, 0.0, *S), (0.24, 4.0, *DEC), (0.34, 4.0, *S), (0.46, 0.0, *S), (L, 0.0)]
    K["fr_y"] = [(0.0, 0.0), (0.12, 0.0, *DEC), (0.17, 1.2, *S), (0.22, 0.6, *ACC), (0.24, 0.0), (L, 0.0)]
    rk.const_keys(v, L, False, K)
    arms([(0.0, R_HAND, R_DIR, S), (0.16, np.array([-4.8, 2.4, -4.4]), np.array([0.10, -0.95, -0.30]), S),
          (0.34, np.array([-5.0, 2.0, -4.8]), np.array([0.14, -0.92, -0.36]), S), (L, R_HAND, R_DIR, None)],
         [(0.0, L_HAND, L_DIR, S), (0.16, np.array([3.6, 4.8, -5.4]), np.array([-0.10, -0.95, -0.30]), S),
          (0.34, np.array([3.8, 5.6, -6.0]), np.array([-0.14, -0.90, -0.40]), S), (L, L_HAND, L_DIR, None)], sols, L)
    return (L, False, solve(L, False), "0.50 s one-shot, no wind-up: load on the right leg 0.06, push left, the "
            "pelvis travels 3.6 px leaning into the step, land and absorb 0.24, feet back under him",
            (0.06, 0.16, 0.24, 0.34), None)


CLIPS = {"CAPTAIN_DUAL_STANCE": stance, "CAPTAIN_DUAL_LIGHT_A": light_a, "CAPTAIN_DUAL_LIGHT_B": light_b,
         "CAPTAIN_BLADE_WHIRL": blade_whirl, "CAPTAIN_TWIN_THRUST": twin_thrust, "CAPTAIN_DISARM": disarm,
         "CAPTAIN_DODGE_STEP": dodge_step}

# the owner's approval sheet: 3-4 key poses per move
KEYPOSES = {
    "CAPTAIN_DUAL_STANCE": [(0.0, "guard: high + low blade")],
    "CAPTAIN_DUAL_LIGHT_A": [(0.11, "coil"), (0.20, "contact"), (0.30, "follow-through")],
    "CAPTAIN_DUAL_LIGHT_B": [(0.11, "chamber"), (0.20, "contact"), (0.30, "follow-through")],
    "CAPTAIN_BLADE_WHIRL": [(0.36, "telegraph"), (0.50, "hit 1"), (0.70, "hit 2 (half turn)"), (0.90, "finish X")],
    "CAPTAIN_TWIN_THRUST": [(0.17, "chamber"), (0.30, "right point"), (0.50, "both converge")],
    "CAPTAIN_DISARM": [(0.12, "parry"), (0.28, "bind"), (0.40, "twist-flick")],
    "CAPTAIN_DODGE_STEP": [(0.06, "load"), (0.20, "push"), (0.26, "land")],
}

if __name__ == "__main__":
    kit.main(CLIPS, "dual", keyposes=KEYPOSES, left34={"CAPTAIN_TWIN_THRUST"})
