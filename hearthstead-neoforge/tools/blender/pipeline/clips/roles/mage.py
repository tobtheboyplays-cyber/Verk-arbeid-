"""Rune mage clips (plan/BATTLE-ROLES.md §6), anim overkill 2026-09-26.

    blender -b --factory-startup --python mage.py -- [--fast|--full] [--no-export] CONST [CONST ...]

Played by SettlerModel while the activity is CAST_FIREBOLT / CAST_FROST / CAST_WARD (RuneMageGoal
sets it at the start of the channel and holds it 8 ticks past the release). Empty hands: the rune
is drawn by the left hand in the air, the magic is the particles.

RUNE_CAST   1.40 s  release 1.00 s (Firebolt, 20-tick channel): settle into a wide stance, the left
                    hand traces a rune circle before the chest (eyes on it), the right palm draws back
                    to the hip as the body winds, then PUSHES out on the release with a lunge; recoil.
RUNE_FROST  1.60 s  release 1.20 s (24 ticks): both hands trace mirrored arcs, rise onto the toes with
                    the hands high, then drop into a crouch and slam the right palm to the ground.
RUNE_WARD   1.20 s  release 0.80 s (16 ticks): hands meet before the chest, sweep out and round the
                    body in a wide circle, meet again, then both palms thrust out and up.
All absolute one-shots from/to a neutral ready pose.
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
FOOT_R = np.array([-3.2, 24.0, 1.6])
FOOT_L = np.array([3.2, 24.0, -1.8])
FEET = (FOOT_R, FOOT_L)
REST = {"hip_x": 0.0, "hip_y": -0.3, "hip_z": 0.0, "hip_p": 0.0, "hip_yaw": 6.0, "hip_r": 0.0,
        "sp_x": 3.0, "sp_y": 0.0, "sp_z": 0.0, "sp_lift": 0.0, "hd_x": -2.0, "hd_y": -6.0, "hd_z": 0.0,
        "ck_x": 2.0, "ck_z": 0.0, "wr_x": 0.0, "wr_y": 0.0, "wr_z": 0.0, "wl_x": 0.0, "wl_y": 0.0, "wl_z": 0.0,
        "gl_w": 0.0, "gl_s": 0.0, "gr_s": 0.0}
R_REST = np.array([-5.4, 10.4, -2.6])
L_REST = np.array([5.4, 10.4, -2.6])


def rest_arms():
    rk.const_keys(REST, 1.0, False)
    r, _ = rk.solve_arm6(rk.body_at(0.0), "right", R_REST, None, seed=[-15, 5, 5, -25, 0, 0], w_seed=0.0)
    lft, _ = rk.solve_arm6(rk.body_at(0.0), "left", L_REST, None, seed=[-15, -5, -5, -25, 0, 0], w_seed=0.0)
    v = dict(REST)
    v.update(dict(zip(rk.ARM6["right"], r)))
    v.update(dict(zip(rk.ARM6["left"], lft)))
    return v, r, lft


def body(b, pts):
    """[(t, delta, *ease)] relative to REST[b] -> absolute keys."""
    return [(p[0], REST[b] + p[1], *p[2:]) for p in pts]


def circle(centre, radius, t0, t1, n, start_deg=90.0, turns=1.0, plane="xy"):
    out = []
    for i in range(n + 1):
        u = i / n
        a = math.radians(start_deg - 360.0 * turns * u)
        d = np.array([math.cos(a) * radius, -math.sin(a) * radius, 0.0]) if plane == "xy" else \
            np.array([math.cos(a) * radius, 0.0, -math.sin(a) * radius])
        out.append((t0 + (t1 - t0) * u, np.asarray(centre) + d))
    return out


def feet_step(K, t_lift, t_land, dz, dx=0.0, back=None):
    """Lead (left) foot: lift first, then travel (no planted slide); optional step back home."""
    K["fl_z"] = [(0.0, 0.0), (t_lift + 0.05, 0.0, *S), (t_land, dz, *DEC)]
    K["fl_x"] = [(0.0, 0.0), (t_lift + 0.05, 0.0, *S), (t_land, dx, *DEC)]
    K["fl_y"] = [(0.0, 0.0), (t_lift, 0.0, *DEC), (t_lift + 0.06, 1.9, *S), (t_land - 0.03, 1.2, *ACC), (t_land, 0.0)]
    if back:
        b0, b1, L = back
        K["fl_z"] += [(b0 + 0.06, dz, *S), (b1, 0.0, *DEC), (L, 0.0)]
        K["fl_x"] += [(b0 + 0.06, dx, *S), (b1, 0.0, *DEC), (L, 0.0)]
        K["fl_y"] += [(b0, 0.0, *DEC), (b0 + 0.06, 1.9, *S), (b1 - 0.03, 1.2, *ACC), (b1, 0.0), (L, 0.0)]


# =========================================================================== CAST (firebolt)
def cast():
    L, REL = 1.4, 1.0
    v, r0, l0 = rest_arms()
    K = {
        "hip_y": body("hip_y", [(0.0, 0.0, *S), (0.18, -0.7, *S), (0.8, -0.8, *S), (0.94, -0.5, *ACC), (REL, -1.6, *DEC),
                                (1.12, -1.4, *S), (1.4, 0.0)]),
        "hip_z": body("hip_z", [(0.0, 0.0, *S), (0.8, 0.2, *S), (0.94, 1.0, *ACC), (REL, -1.8, *DEC), (1.12, -1.6, *S),
                                (1.4, 0.0)]),
        "sp_x": body("sp_x", [(0.0, 0.0, *S), (0.18, 2.0, *S), (0.8, 3.0, *S), (0.94, -5.0, *ACC), (REL, 9.0, *DEC),
                              (1.1, 5.0, *S), (1.4, 0.0)]),
        "sp_y": body("sp_y", [(0.0, 0.0, *S), (0.2, -4.0, *S), (0.5, 3.0, *S), (0.8, 0.0, *S), (0.94, 16.0, *ACC),
                              (REL, -12.0, *DEC), (1.12, -10.0, *S), (1.4, 0.0)]),
        "hip_yaw": body("hip_yaw", [(0.0, 0.0, *S), (0.9, 8.0, *ACC), (REL, -6.0, *DEC), (1.4, 0.0)]),
        "hd_x": body("hd_x", [(0.0, 0.0, *S), (0.2, 6.0, *S), (0.8, 7.0, *S), (0.94, -2.0, *S), (REL, -6.0, *DEC),
                              (1.4, 0.0)]),
        "hd_y": body("hd_y", [(0.0, 0.0, *S), (0.2, 4.0, *S), (0.5, 8.0, *S), (0.8, 4.0, *S), (0.94, -12.0, *S),
                              (REL, 10.0, *DEC), (1.4, 0.0)]),
        "ck_x": body("ck_x", [(0.0, 0.0), (REL, 8.0, *DEC), (1.4, 0.0)]),
    }
    feet_step(K, 0.86, REL - 0.01, -2.4, back=(1.14, 1.34, L))
    rk.const_keys(v, L, False, K)
    rune = circle((1.4, 3.6, -8.2), 2.3, 0.22, 0.78, 8)
    lg = [(0.0, L_REST, None, S), (0.16, np.array([2.4, 1.6, -7.6]), None, S)]
    lg += [(t, p, None, S) for t, p in rune]
    lg += [(0.86, np.array([1.2, 3.8, -9.2]), None, ACC),                   # a stab into the rune's heart
           (REL, np.array([6.8, 8.6, -1.4]), None, DEC),                    # thrown back for balance
           (1.2, np.array([6.0, 9.4, -2.2]), None, S), (L, L_REST, None, None)]
    rk.arm_goals(lg, "left", seed=l0, length=L)
    rg = [(0.0, R_REST, None, S), (0.30, np.array([-4.2, 8.2, -5.4]), None, S),       # palm up, gathering
          (0.70, np.array([-4.6, 8.6, -4.6]), None, S),
          (0.92, np.array([-6.2, 9.0, 1.2]), None, ACC),                                # wound back at the hip
          (0.96, np.array([-5.2, 7.4, -3.4]), None, LIN),
          (REL, np.array([-2.0, 4.2, -9.8]), None, DEC),                                # PUSH
          (1.10, np.array([-2.4, 2.4, -8.8]), None, S),                                 # recoil kicks it up
          (1.24, np.array([-4.2, 7.0, -6.0]), None, S), (L, R_REST, None, None)]
    rk.arm_goals(rg, "right", seed=r0, length=L)
    solve = rk.make_solve(L, False, FEET)
    return (L, False, solve, "1.40 s one-shot (CAST_FIREBOLT): rune traced 0.22-0.78, wind 0.86-0.94, palm push "
            "release t=1.00 s = tick 20 (RuneSpell.FIREBOLT castTicks), recoil, settle", (0.22, 0.78, 0.92, REL, 1.1), None)


# =========================================================================== FROST
def frost():
    L, REL = 1.6, 1.2
    v, r0, l0 = rest_arms()
    K = {
        "hip_y": body("hip_y", [(0.0, 0.0, *S), (0.2, -0.6, *S), (0.9, -0.5, *S), (1.08, 0.5, *DEC), (1.12, 0.45, *ACC),
                                (REL, -4.2, *DEC), (1.3, -4.4, *S), (1.6, 0.0)]),
        "hip_z": body("hip_z", [(0.0, 0.0, *S), (1.1, 0.4, *ACC), (REL, 1.8, *DEC), (1.3, 1.9, *S), (1.6, 0.0)]),
        "sp_x": body("sp_x", [(0.0, 0.0, *S), (0.9, 2.0, *S), (1.08, -8.0, *DEC), (1.12, -8.0, *ACC), (REL, 30.0, *DEC),
                              (1.3, 32.0, *S), (1.6, 0.0)]),
        "sp_y": body("sp_y", [(0.0, 0.0, *S), (0.35, 5.0, *S), (0.65, -5.0, *S), (0.9, 0.0, *S), (1.6, 0.0)]),
        "sp_lift": [(0.0, 0.0, *S), (1.08, 0.4, *S), (1.14, 0.3, *ACC), (REL, -0.3, *DEC), (1.6, 0.0)],
        "hd_x": body("hd_x", [(0.0, 0.0, *S), (0.3, 5.0, *S), (0.9, 4.0, *S), (1.08, -10.0, *S), (REL, 12.0, *DEC),
                              (1.3, 14.0, *S), (1.6, 0.0)]),
        "ck_x": body("ck_x", [(0.0, 0.0), (1.1, -4.0), (REL, 10.0, *DEC), (1.6, 0.0)]),
    }
    feet_step(K, 1.1, REL - 0.01, -2.0, dx=0.4, back=(1.36, 1.54, L))
    rk.const_keys(v, L, False, K)
    arcL = circle((3.2, 3.6, -7.4), 3.4, 0.2, 0.9, 7, start_deg=180.0, turns=0.75)
    arcR = [(t, p * np.array([-1, 1, 1])) for t, p in circle((3.2, 3.6, -7.4), 3.4, 0.2, 0.9, 7, start_deg=180.0,
                                                             turns=0.75)]
    lg = [(0.0, L_REST, None, S)] + [(t, p, None, S) for t, p in arcL] + [
        (1.08, np.array([4.0, -6.0, -3.6]), None, ACC),                   # high, on the toes
        (REL, np.array([7.0, 9.6, -3.0]), None, DEC),                     # out to the side for balance
        (1.32, np.array([6.8, 10.0, -3.4]), None, S), (L, L_REST, None, None)]
    rk.arm_goals(lg, "left", seed=l0, length=L)
    rg = [(0.0, R_REST, None, S)] + [(t, p, None, S) for t, p in arcR] + [
        (1.08, np.array([-4.0, -6.0, -3.6]), None, ACC),
        (REL, np.array([-2.6, 17.4, -9.4]), None, DEC),                   # SLAM, palm to the ground
        (1.32, np.array([-2.6, 17.6, -9.2]), None, S), (L, R_REST, None, None)]
    rk.arm_goals(rg, "right", seed=r0, length=L)
    solve = rk.make_solve(L, False, FEET)
    return (L, False, solve, "1.60 s one-shot (CAST_FROST): mirrored arcs 0.20-0.90, rise 1.08, crouch + palm slam "
            "release t=1.20 s = tick 24 (RuneSpell.FROST_RUNE castTicks), rise", (0.9, 1.08, REL, 1.32), None)


# =========================================================================== WARD
def ward():
    L, REL = 1.2, 0.8
    v, r0, l0 = rest_arms()
    K = {
        "hip_y": body("hip_y", [(0.0, 0.0, *S), (0.15, -0.8, *S), (0.7, -0.9, *S), (REL, 0.3, *DEC), (0.95, 0.2, *S),
                                (1.2, 0.0)]),
        "sp_x": body("sp_x", [(0.0, 0.0, *S), (0.15, 4.0, *S), (0.45, 0.0, *S), (0.7, 3.0, *S), (REL, -6.0, *DEC),
                              (0.95, -5.0, *S), (1.2, 0.0)]),
        "sp_lift": [(0.0, 0.0, *S), (0.7, 0.0, *S), (REL, 0.5, *DEC), (1.2, 0.0)],
        "sp_y": body("sp_y", [(0.0, 0.0, *S), (0.3, -6.0, *S), (0.55, 6.0, *S), (0.7, 0.0, *S), (1.2, 0.0)]),
        "hd_x": body("hd_x", [(0.0, 0.0, *S), (0.15, 8.0, *S), (0.6, 2.0, *S), (REL, -8.0, *DEC), (1.2, 0.0)]),
        "hd_y": body("hd_y", [(0.0, 0.0, *S), (0.3, -10.0, *S), (0.55, 10.0, *S), (0.7, 0.0, *S), (1.2, 0.0)]),
        "ck_x": body("ck_x", [(0.0, 0.0), (0.45, 4.0), (REL, -3.0, *DEC), (1.2, 0.0)]),
    }
    rk.const_keys(v, L, False, K)
    for side, sgn, seed, rest in (("left", 1.0, l0, L_REST), ("right", -1.0, r0, R_REST)):
        def m(p):
            return np.array([p[0] * sgn, p[1], p[2]])
        g = [(0.0, rest, None, S),
             (0.15, m((1.2, 5.4, -6.4)), None, S),                          # hands meet before the chest
             (0.32, m((6.4, 5.8, -6.4)), None, S),                          # sweep out...
             (0.48, m((8.4, 6.2, -2.2)), None, S),                          # ...round the body
             (0.62, m((6.8, 5.6, -6.8)), None, S),
             (0.72, m((2.2, 4.4, -8.4)), None, ACC),                         # meet again, gathered
             (REL, m((6.0, 0.8, -7.8)), None, DEC),                          # palms thrust out and up
             (0.95, m((6.2, 0.4, -7.4)), None, S), (L, rest, None, None)]
        rk.arm_goals(g, side, seed=seed, length=L)
    solve = rk.make_solve(L, False, FEET)
    return (L, False, solve, "1.20 s one-shot (CAST_WARD): circle drawn round the body 0.15-0.72, both palms out "
            "release t=0.80 s = tick 16 (RuneSpell.WARD castTicks)", (0.15, 0.48, 0.72, REL), None)


CLIPS = {"RUNE_CAST": cast, "RUNE_FROST": frost, "RUNE_WARD": ward}

if __name__ == "__main__":
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    names = [a for a in argv if not a.startswith("--")] or list(CLIPS)
    for n in names:
        rk.run(n, CLIPS[n], "settler_scholar.png", None)
