"""Planted-foot gait generator for the combat locomotion clips (GUARD_WALK,
WALK_HURRIED, RUN_PANIC). Same treadmill model as the lead's author_walk.py
(stance foot slides back at exactly ground speed, eased swing arc, two-bone leg
IK so knees bend), extended with a flight phase (duty < 0.5 = running) and
arm styles. Writes hearthstead_meta.blocks_per_cycle for the engine's stride
matching.
"""
import math

import numpy as np

import combatkit as ck
import hsrig
import mcrig

c = hsrig.ctrl
FOOT_X = 2.9


def smooth(x):
    return x * x * (3 - 2 * x)


def foot_target(u, st):
    e, d = st["excursion"], st["duty"]
    u %= 1.0
    if u < d:
        s = u / d
        return -e / 2 + e * s, 24.0
    s = (u - d) / (1 - d)
    z = e / 2 - e * smooth(s)
    y = 24.0 - st["lift"] * math.sin(math.pi * min(1.0, s ** st.get("lift_bias", 0.8)))
    return z, y


def key_gait(st):
    L = st["length"]

    def cyc(prop, pts):
        hsrig.key_curve(prop, [(t * L, v) for t, v in pts], cyclic=True, length=L)

    lo, hi = st["base"] - st["drop"], st["base"]
    d = st["duty"]
    if d >= 0.5:
        # walk: lowest just after each contact (double support), highest at mid-stance
        cyc("pelvis_y", [(0.0, lo + 0.25 * st["drop"]), (0.06, lo), (0.28, hi), (0.5, lo + 0.25 * st["drop"]),
                         (0.56, lo), (0.78, hi), (1.0, lo + 0.25 * st["drop"])])
    else:
        # run: lowest at mid-stance (loading), highest in flight
        ms = d / 2
        fl = d + (0.5 - d) / 2
        cyc("pelvis_y", [(0.0, (lo + hi) / 2), (ms, lo), (fl, hi + st.get("flight", 0.8)), (0.5, (lo + hi) / 2),
                         (0.5 + ms, lo), (0.5 + fl, hi + st.get("flight", 0.8)), (1.0, (lo + hi) / 2)])
    cyc("pelvis_x", [(0.0, 0.0), (0.25, -st["sway"]), (0.5, 0.0), (0.75, st["sway"]), (1.0, 0.0)])
    cyc("pelvis_yaw", [(0.0, -st["pelvis_yaw"]), (0.25, 0.0), (0.5, st["pelvis_yaw"]), (0.75, 0.0),
                       (1.0, -st["pelvis_yaw"])])
    cyc("pelvis_roll", [(0.0, 0.0), (0.25, -st.get("roll", 0.0)), (0.5, 0.0), (0.75, st.get("roll", 0.0)), (1.0, 0.0)])
    cyc("spine_yaw", [(0.0, st["spine_yaw"]), (0.25, 0.0), (0.5, -st["spine_yaw"]), (0.75, 0.0),
                      (1.0, st["spine_yaw"])])
    ld = st["lean_dip"]
    cyc("spine_x", [(0.0, st["lean"]), (0.1, st["lean"] + ld), (0.3, st["lean"] - 0.2 * ld),
                    (0.5, st["lean"]), (0.6, st["lean"] + ld), (0.8, st["lean"] - 0.2 * ld), (1.0, st["lean"])])
    a = st["arm"]
    lag = st.get("arm_lag", 0.05)
    cyc("arm_r_x", [(0.0, a * 0.8), (lag, a), (0.25 + lag, 0.0), (0.5 + lag, -a), (0.75 + lag, 0.0), (1.0, a * 0.8)])
    e0, e1 = st["elbow"]
    cyc("elbow_r", [(0.0, e0), (0.3, e0 * 0.8 + e1 * 0.2), (0.55, e1), (0.8, e0 * 0.5 + e1 * 0.5), (1.0, e0)])


def make_solve(st, upper=None):
    """upper(t, ch) may overwrite torso/arms/head (e.g. the guard's patrol hold)."""
    L = st["length"]
    prev = {}

    def solve(t):
        t %= L
        u = t / L
        ch = {"root": {"rot": (0.0, c("pelvis_yaw", t), c("pelvis_roll", t)),
                       "pos": (c("pelvis_x", t), c("pelvis_y", t), 0.0)},
              "torso": {"rot": (c("spine_x", t), c("spine_yaw", t), 0.0)}}
        ao = st["arm_out"]
        ch["right_arm"] = {"rot": (c("arm_r_x", t) + st.get("arm_base", 0.0), 0.0, ao)}
        ch["right_forearm"] = {"rot": (c("elbow_r", t), 0.0, 0.0)}
        tl = (t + L / 2) % L
        ch["left_arm"] = {"rot": (c("arm_r_x", tl) + st.get("arm_base", 0.0), 0.0, -ao)}
        ch["left_forearm"] = {"rot": (c("elbow_r", tl), 0.0, 0.0)}
        yaw_net = c("pelvis_yaw", t) + c("spine_yaw", t)
        ch["head"] = {"rot": (-0.75 * c("spine_x", t) + st["head_up"], -0.85 * yaw_net, 0.0)}
        dt = 1.0 / 60
        vy = (c("pelvis_y", (t - 0.05) % L) - c("pelvis_y", (t - 0.05 - dt) % L)) / dt
        vyaw = (yaw_net - (c("pelvis_yaw", (t - dt) % L) + c("spine_yaw", (t - dt) % L))) / dt
        ch["cloak"] = {"rot": (st["cloak"] + max(-10, min(10, 1.6 * vy)), 0.0, max(-6, min(6, -0.05 * vyaw)))}
        if st.get("flail"):
            st["flail"](t, ch)
        if upper is not None:
            upper(t, ch)
        world = mcrig.pose_matrices(ch)
        inv_root = np.linalg.inv(world["root"])
        for side, sign, phase in (("right", -1, 0.0), ("left", 1, 0.5)):
            z, y = foot_target(u - phase, st)
            foot = np.array([sign * FOOT_X, y, z])
            fl = mcrig.xform(inv_root, foot)
            pole = inv_root[:3, :3] @ np.array([0.1 * sign, 0.0, -1.0])
            r, flex, _ = mcrig.two_bone(np.array([sign * ck.HIP_X, -12.0, 0.0]), fl, mcrig.THIGH,
                                        mcrig.SOLE_Y - mcrig.THIGH, pole, +1)
            ch[side + "_leg"] = {"rot": tuple(hsrig.euler_deg_continuous(r, prev.get(side)))}
            prev[side] = ch[side + "_leg"]["rot"]
            ch[side + "_shin"] = {"rot": (math.degrees(flex), 0.0, 0.0)}
        return ch
    return solve


def check(st, times, samples):
    L = st["length"]
    err = 0.0
    for t, s in zip(times, samples):
        w = mcrig.pose_matrices(s)
        for side, sign, phase in (("right", -1, 0.0), ("left", 1, 0.5)):
            sole = mcrig.xform(w[side + "_shin"], (0, 6, 0))
            z, y = foot_target(t / L - phase, st)
            err = max(err, float(np.linalg.norm(sole - np.array([sign * FOOT_X, y, z]))))
    bpc = st["excursion"] / st["duty"] / 16.0
    return {"sole_max_error_px": round(err, 3), "blocks_per_cycle": round(bpc, 4)}
