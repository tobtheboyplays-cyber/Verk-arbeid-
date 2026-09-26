"""Raider clips (RaiderAnimations + the RAIDER_LIGHT / RAIDER_HEAVY moveset), authored as
procedural Blender solves on the exact RaiderModel rig (enemyrig.py).

Contracts kept EXACTLY (lengths, loop flags, contact/sound beats):
  STALK        0.80 s loop   SKIRMISHER walk (distance-clocked; blocks_per_cycle in meta)
  BRUTE_MARCH  1.20 s loop   BRUTE walk
  SPRINT       0.60 s loop   SKIRMISHER charge
  MENACE_IDLE  4.20 s loop   every raider while stopped (additive over near-zero locomotion)
  BREACH_SLAM  1.50 s        BRUTE door break, impact keyframe 0.55 s (reaction-first)
  RAIDER_STRIKE 0.55 s       contact pose 0.25 s, held to 0.30 s
  BRUTE_CLUB_STRIKE 0.75 s   wind-up peak 0.25, club contact 0.35 s (= RaiderMeleeGoal tick 7),
                             overshoot 0.40, follow-through 0.50
  LOOT_SNATCH  0.70 s        grip 0.20-0.30 s (RaiderLootGoal GRAB_PERIOD trigger at t=0)
  CINEMATIC_EXPOSED 0.80 s   arms/torso/head only (RaiderModel clears only those)
  CINEMATIC_STAGGER 0.40 s   arms/torso/head only (state expires at 420 ms); also the raider
                             stagger when a guard interrupts RAIDER_HEAVY
  RAIDER_LIGHT 0.55 s        combat spec (a18c40b89e2b1229b): hit tick 5 = 0.25 s, hit-stop
                             0.25-0.30, recovery to 0.55
  RAIDER_HEAVY 1.20 s        windup 0-0.60 (hang ~0.45-0.52), HIT tick 12 = 0.60 s,
                             hit-stop 0.60-0.70, overbalanced recovery 0.70-1.20

Sign conventions (Minecraft-exact, see mcrig): torso X positive = hinge FORWARD (the torso
extends above its hip pivot); an arm hanging down with X negative swings FORWARD; torso Y
positive turns the left shoulder forward (= right shoulder back, the coil for a right-hand
blow); elbow flex negative X, knee flex positive X.

One-shots start and end on READY, the mean pose of MENACE_IDLE, so there is no pop into or
out of them. RaiderModel clears arm/torso/head/leg ROTATION for the combat one-shots but not
`root`, which still carries MENACE_IDLE's -1.2 px crouch: one-shot root channels are therefore
authored as a delta on top of that base (ROOT_BASE is removed at export, legs are solved
against the summed height).
"""

from __future__ import annotations

import math

import numpy as np

import enemyrig as er
from enemyrig import Curve, LegIK, ArmIK, sin01, bump, gait_foot

ROOT_BASE = (0.0, -1.2, 0.0)
FEET = {"right": np.array([-3.2, 24.0, 1.4]), "left": np.array([3.2, 24.0, -1.8])}
READY = {
    "root.pos": (0, 0, 0), "root": (0, 0, 0),
    "torso": (14, 0, 0), "torso.pos": (0, 0, 0),
    "head": (-8, 0, 0),
    "right_arm": (-14, -4, 8), "right_forearm": (-38, 0, 0),
    "left_arm": (-6, 4, -10), "left_forearm": (-28, 0, 0),
}
UPPER = ["torso", "head", "right_arm", "left_arm", "right_forearm", "left_forearm"]
TIP = {er.BRUTE: (0, 3.5, -10.5), er.SKIRM: (0, 3.5, -8.0), er.CAPTAIN: (0, 3.5, -8.0)}
DOOR = ("door", (-8, -8, -20), (16, 32, 3), (0.45, 0.30, 0.16, 1))
DUMMY = ("target", (-4, -8, -24), (8, 32, 6), (0.55, 0.20, 0.18, 1))
OVERHEAD = {"dist": 5.4, "z": 1.35}
CHEST = ("chest", (-7, 12, -24), (14, 12, 14), (0.50, 0.34, 0.18, 1))


# --------------------------------------------------------------------------- helpers
def _vec(v):
    return (float(v), 0.0, 0.0) if np.isscalar(v) else tuple(float(x) for x in v)


def keyposes(keys, base=READY, period=None):
    """keys: [(t, {channel: value}, mode)]; a channel missing from a key holds its last value."""
    chans = set(base)
    for _, d, *_ in keys:
        chans |= set(d)
    cur = {c: _vec(base.get(c, (0, 0, 0))) for c in chans}
    rows = {c: [] for c in chans}
    for t, d, *m in keys:
        for c, v in d.items():
            cur[c] = _vec(v)
        for c in chans:
            rows[c].append((t, cur[c], m[0] if m else "auto"))
    curves = {c: Curve(r, period) for c, r in rows.items()}
    return lambda t: {c: cv(t) for c, cv in curves.items()}


def to_ch(p):
    ch = {}
    for c, v in p.items():
        if c.endswith(".pos"):
            er.add(ch, c[:-4], pos=v)
        else:
            er.add(ch, c, rot=v)
    return ch


def ramp(t, a0, a1, b0, b1):
    """0 before a0, eases to 1 by a1, holds, eases back to 0 over b0..b1."""
    def s(u):
        u = min(1.0, max(0.0, u))
        return u * u * (3 - 2 * u)
    if t < a1:
        return s((t - a0) / max(a1 - a0, 1e-9))
    if t < b0:
        return 1.0
    return 1.0 - s((t - b0) / max(b1 - b0, 1e-9))


def oneshot(kp, left_grip=None, root_base=ROOT_BASE, feet=FEET, pin_tip=None):
    """pin_tip=(t_hit, t_hold_end, t_release): the weapon tip is recorded at t_hit and the right
    arm is IK-solved to keep it there (buried club), fading back to the keyed arc by t_release."""
    legs = LegIK()
    larm = ArmIK("left")
    rarm = ArmIK("right", local=TIP[er.BRUTE], w_head=0.0)
    pin = {}

    def solve(t):
        ch = to_ch(kp(t))
        er.add(ch, "root", pos=root_base)
        if pin_tip is not None:
            t0, t1, t2 = pin_tip
            if t >= t0 - 1e-6 and "p" not in pin:
                pin["p"] = er.mcrig.xform(er.world(ch)["right_forearm"], TIP[er.BRUTE])
            w = 0.0 if not (t0 < t < t2) else ramp(t, t0, t0 + 1e-3, t1, t2)
            if w > 1e-3:
                fk = (ch["right_arm"]["rot"], ch["right_forearm"]["rot"][0])
                rarm(ch, pin["p"], list(fk[0]) + [fk[1]], w_guess=0.0005)
                ik = (ch["right_arm"]["rot"], ch["right_forearm"]["rot"][0])
                ch["right_arm"] = {"rot": tuple(a + (b - a) * w for a, b in zip(fk[0], ik[0]))}
                ch["right_forearm"] = {"rot": (fk[1] + (ik[1] - fk[1]) * w, 0.0, 0.0)}
            else:
                rarm.prev = None
        if left_grip is not None:
            w = left_grip(t)
            if w > 1e-3:
                fk = (ch["left_arm"]["rot"], ch["left_forearm"]["rot"][0])
                guess = list(fk[0]) + [fk[1]]
                larm(ch, er.grip_point(ch, "right", 5.0), guess)
                ik = (ch["left_arm"]["rot"], ch["left_forearm"]["rot"][0])
                ch["left_arm"] = {"rot": tuple(a + (b - a) * w for a, b in zip(fk[0], ik[0]))}
                ch["left_forearm"] = {"rot": (fk[1] + (ik[1] - fk[1]) * w, 0.0, 0.0)}
            else:
                larm.prev = None
        legs(ch, feet(t) if callable(feet) else feet)
        return ch
    return solve


def strike_checks(contact, variant, feet=FEET, stuck=None):
    tip_local = TIP.get(variant, TIP[er.SKIRM])

    def checks(times, samples):
        tips, slip = [], 0.0
        for t, s in zip(times, samples):
            w = er.world(s)
            tips.append(er.mcrig.xform(w["right_forearm"], tip_local))
            f = feet(t) if callable(feet) else feet
            for side in ("right", "left"):
                slip = max(slip, float(np.linalg.norm(er.sole(s, side) - f[side])))
        tips = np.array(tips)
        speed = np.linalg.norm(np.diff(tips, axis=0), axis=1) * er.FPS
        cf = int(round(contact * er.FPS))
        pk = int(np.argmax(speed))
        return {"contact_t": contact, "tip_speed_peak_t": round((pk + 1) / er.FPS, 3),
                "tip_speed_px_s_into_contact": round(float(speed[cf - 1]), 1),
                "tip_speed_px_s_after_contact": round(float(speed[min(cf + 1, len(speed) - 1)]), 1),
                "tip_at_contact_px": [round(float(v), 2) for v in tips[cf]],
                "tip_height_above_ground_px_at_contact": round(24.0 - float(tips[cf][1]), 2),
                "tip_lowest_height_px": round(24.0 - float(tips[:, 1].max()), 2),
                **({"stuck_tip_drift_px": round(float(np.max(np.linalg.norm(
                    tips[int(round(stuck[0] * er.FPS)):int(round(stuck[1] * er.FPS)) + 1] - tips[cf], axis=1))), 2)}
                   if stuck else {}),
                "foot_slip_px_max": round(slip, 3)}
    return checks


def plant_checks(feet=FEET):
    def checks(times, samples):
        slip = 0.0
        for t, s in zip(times, samples):
            f = feet(t) if callable(feet) else feet
            for side in ("right", "left"):
                slip = max(slip, float(np.linalg.norm(er.sole(s, side) - f[side])))
        return {"foot_slip_px_max": round(slip, 3)}
    return checks


# --------------------------------------------------------------------------- locomotion
def locomotion(L, st, upper):
    """Treadmill gait: planted stance feet slide back at ground speed, IK legs.
    st: excursion, duty, lift, foot_x, pelvis curves; upper(u, ch, pel) adds torso/arms/head."""
    legs = LegIK()
    y_curve = Curve([(f * L, (0, v, 0)) for f, v in st["pelvis_y"]], L)

    def solve(t):
        u = (t % L) / L
        # right stance (u 0..duty) puts the pelvis over the right (-x) foot
        pel_x = -st["sway"] * math.sin(2 * math.pi * u + 0.35)
        pel_yaw = -st["pelvis_yaw"] * math.cos(2 * math.pi * u)
        ch = {"root": {"pos": (pel_x, y_curve(t)[1], 0.0), "rot": (0.0, pel_yaw, 0.0)}}
        upper(u, ch, {"yaw": pel_yaw})
        feet = {}
        for side, sign, ph in (("right", -1, 0.0), ("left", 1, 0.5)):
            z, y = gait_foot(u - ph, st["excursion"], st["duty"], st["lift"], st.get("lift_shape", 0.8))
            feet[side] = np.array([sign * st["foot_x"], y, z])
        legs(ch, feet, knee_dir=(0, 0, -1), splay=0.1)
        return ch

    def checks(times, samples):
        err = 0.0
        for t, s in zip(times, samples):
            u = (t % L) / L
            for side, sign, ph in (("right", -1, 0.0), ("left", 1, 0.5)):
                z, y = gait_foot(u - ph, st["excursion"], st["duty"], st["lift"], st.get("lift_shape", 0.8))
                err = max(err, float(np.linalg.norm(er.sole(s, side) - np.array([sign * st["foot_x"], y, z]))))
        return {"sole_max_error_px": round(err, 3),
                "blocks_per_cycle": round(st["excursion"] / st["duty"] / 16.0, 4)}
    return solve, checks


_JERK = Curve([(0.0, (0, 0, 0)), (0.26, (0, 0, 0)), (0.30, (3, 11, -4), "snap"), (0.42, (2, 9, -3)),
               (0.46, (0, 0, 0), "snap"), (0.70, (0, 0, 0)), (0.74, (-2, -9, 3), "snap"), (0.84, (-1, -8, 2)),
               (0.88, (0, 0, 0), "snap"), (1.0, (0, 0, 0))], 1.0)


def clip_stalk():
    """Owner 2026-09-26: skirmishers are jittery pests -- low, quick, twitchy. Short stride (high
    cadence on the distance clock), deep crouch, head jerking between targets, knife hand flicking."""
    L = 0.80
    st = dict(excursion=9.0, duty=0.60, lift=2.2, foot_x=2.6, sway=0.35, pelvis_yaw=6.0,
              pelvis_y=[(0.0, -2.9), (0.08, -3.2), (0.30, -2.4), (0.5, -2.9), (0.58, -3.2),
                        (0.80, -2.4), (1.0, -2.9)])

    def upper(u, ch, pel):
        sw = math.cos(2 * math.pi * (u - 0.06))
        dip = bump(u, 0.12, 0.3, 1.0) + bump(u, 0.62, 0.3, 1.0)
        twitch = bump(u, 0.10, 0.10, 1.0) + bump(u, 0.60, 0.10, 1.0)
        spine_yaw = 8.0 * math.cos(2 * math.pi * u)
        lean = 26.0 + 2.0 * dip
        j = _JERK(u)
        ch["torso"] = {"rot": (lean, spine_yaw + 0.3 * j[1], 1.5 * math.sin(2 * math.pi * u + 0.35)),
                       "pos": (0, -0.25 * dip, 0)}
        ch["head"] = {"rot": (-lean + 6.0 - 1.5 * dip + j[0], -0.85 * (pel["yaw"] + spine_yaw) + j[1], j[2])}
        ch["right_arm"] = {"rot": (-24.0 + 6.0 * sw, -6.0, 9.0)}
        ch["right_forearm"] = {"rot": (-48.0 - 4.0 * max(0.0, -sw) - 16.0 * twitch, 0, 0)}
        ch["left_arm"] = {"rot": (-14.0 - 6.0 * sw, 6.0, -12.0)}
        ch["left_forearm"] = {"rot": (-40.0 - 5.0 * max(0.0, sw) - 10.0 * twitch, 0, 0)}

    solve, checks = locomotion(L, st, upper)
    return dict(rig="raider", variant=er.SKIRM, const="STALK", length=L, loop=True, solve=solve,
                checks=checks, blocks_per_cycle=st["excursion"] / st["duty"] / 16.0,
                contract="0.80 s loop, SKIRMISHER walk; distance-clocked by the motion engine")


def clip_brute_march():
    """Owner 2026-09-26: slow and SUPER heavy. Long stride (fewer, slower steps on the distance
    clock), the swing foot peaks early and STOMPS down, the pelvis drops hard on every footfall,
    the shoulders sway over the stance leg and the head/arms lag the impact."""
    L = 1.20
    st = dict(excursion=17.0, duty=0.66, lift=3.2, lift_shape=0.5, foot_x=3.4, sway=1.1, pelvis_yaw=9.0,
              pelvis_y=[(0.0, -3.9), (0.06, -4.9), (0.14, -4.3), (0.32, -3.0), (0.5, -3.9), (0.56, -4.9),
                        (0.64, -4.3), (0.82, -3.0), (1.0, -3.9)])

    def upper(u, ch, pel):
        land = bump(u, 0.07, 0.18, 1.0) + bump(u, 0.57, 0.18, 1.0)        # footfall shock
        nod = bump(u, 0.12, 0.22, 1.0) + bump(u, 0.62, 0.22, 1.0)         # head lags the shock
        spine_yaw = 15.0 * math.cos(2 * math.pi * (u - 0.03))
        roll = -8.0 * math.sin(2 * math.pi * (u + 0.1))                   # shoulder sway over stance
        lean = 18.0 + 4.0 * land
        ch["torso"] = {"rot": (lean, spine_yaw, roll), "pos": (0, -0.8 * land, 0)}
        yaw_net = pel["yaw"] + spine_yaw
        ch["head"] = {"rot": (-lean + 9.0 + 5.0 * nod, -0.7 * yaw_net, -0.5 * roll)}
        swl = math.cos(2 * math.pi * (u - 0.1))
        ch["left_arm"] = {"rot": (-4.0 - 24.0 * swl + 4.0 * nod, 4.0, -13.0 - 3.0 * land)}
        ch["left_forearm"] = {"rot": (-16.0 - 12.0 * max(0.0, swl), 0, 0)}
        ch["right_arm"] = {"rot": (-12.0 + 14.0 * swl + 4.0 * nod, -6.0, 11.0 + 3.0 * land)}
        ch["right_forearm"] = {"rot": (-34.0 - 6.0 * max(0.0, -swl), 0, 0)}

    solve, checks = locomotion(L, st, upper)
    return dict(rig="raider", variant=er.BRUTE, const="BRUTE_MARCH", length=L, loop=True, solve=solve,
                checks=checks, blocks_per_cycle=st["excursion"] / st["duty"] / 16.0,
                contract="1.20 s loop, BRUTE walk; distance-clocked by the motion engine")


def clip_sprint():
    """Skirmisher charge: low, all-out scuttle with arms trailing, short fast strides."""
    L = 0.60
    st = dict(excursion=13.0, duty=0.40, lift=4.6, foot_x=2.3, sway=0.3, pelvis_yaw=8.0,
              pelvis_y=[(0.0, -3.1), (0.20, -3.5), (0.45, -2.1), (0.5, -3.1), (0.70, -3.5),
                        (0.95, -2.1), (1.0, -3.1)])

    def upper(u, ch, pel):
        sw = math.cos(2 * math.pi * (u - 0.05))
        push = bump(u, 0.2, 0.3, 1.0) + bump(u, 0.7, 0.3, 1.0)
        spine_yaw = 12.0 * math.cos(2 * math.pi * u)
        lean = 34.0 + 2.5 * push
        j = _JERK(u)
        ch["torso"] = {"rot": (lean, spine_yaw, 2.0 * math.sin(2 * math.pi * u + 0.35)),
                       "pos": (0, -0.3 * push, 0)}
        ch["head"] = {"rot": (-lean + 10.0 + 2.0 * push, -0.8 * (pel["yaw"] + spine_yaw) + 0.5 * j[1], 0.5 * j[2])}
        ch["right_arm"] = {"rot": (44.0 + 14.0 * sw, -8.0, 14.0)}
        ch["right_forearm"] = {"rot": (-26.0 - 12.0 * max(0.0, -sw), 0, 0)}
        ch["left_arm"] = {"rot": (44.0 - 14.0 * sw, 8.0, -14.0)}
        ch["left_forearm"] = {"rot": (-26.0 - 12.0 * max(0.0, sw), 0, 0)}

    solve, checks = locomotion(L, st, upper)
    return dict(rig="raider", variant=er.SKIRM, const="SPRINT", length=L, loop=True, solve=solve,
                checks=checks, blocks_per_cycle=st["excursion"] / st["duty"] / 16.0,
                contract="0.60 s loop, SKIRMISHER charge; distance-clocked by the motion engine")


# --------------------------------------------------------------------------- idle
def clip_menace_idle():
    L = 4.20
    legs = LegIK()
    head = Curve([
        (0.00, (0, -22, 0)), (0.55, (1, -20, 1)), (0.85, (0, -4, 0), "smooth"),
        (1.25, (-1, -2, 0)), (1.55, (1, 18, -2), "smooth"), (2.20, (0, 21, -5)),
        (2.55, (0, -6, 0), "smooth"), (3.20, (1, -9, 1)), (3.55, (0, -20, 2), "smooth"),
        (4.20, (0, -22, 0))], L)

    def solve(t):
        br = 0.5 - 0.5 * math.cos(2 * math.pi * t / 1.4)          # three heavy breaths
        shift = math.sin(2 * math.pi * t / L)                      # weight foot to foot
        ch = {"root": {"pos": (0.8 * shift, -1.2 - 0.15 * br, 0.0), "rot": (0, 2.0 * shift, 0)}}
        roll_sh = 5.0 * sin01(t, 2.1)
        lean = 14.0 + 1.5 * sin01(t, L, 0.15) - 1.6 * br
        ch["torso"] = {"rot": (lean, roll_sh, -2.5 * shift), "pos": (0, 0.35 * br, 0)}
        h = head(t)
        ch["head"] = {"rot": (-lean + 6.0 + h[0] + 1.0 * br, h[1] - 0.6 * roll_sh, h[2] + 1.5 * shift)}
        grip = bump(t, 1.3, 0.35, L) + bump(t, 3.35, 0.3, L)
        ch["right_arm"] = {"rot": (-14.0 + 4.0 * sin01(t, 2.1, 0.08), -4.0, 8.0 + 2.5 * br + 1.5 * sin01(t, 4.2))}
        ch["right_forearm"] = {"rot": (-38.0 - 12.0 * grip - 3.0 * br, 0, 0)}
        ch["left_arm"] = {"rot": (-6.0 + 4.0 * sin01(t, 2.1, 0.43), 4.0, -10.0 - 2.5 * br)}
        ch["left_forearm"] = {"rot": (-28.0 - 9.0 * bump(t, 2.4, 0.4, L) - 2.0 * br, 0, 0)}
        legs(ch, FEET)
        return ch

    return dict(rig="raider", variant=er.SKIRM, const="MENACE_IDLE", length=L, loop=True, solve=solve,
                checks=plant_checks(), loops=1,
                contract="4.20 s loop, every raider while stopped; additive over near-zero locomotion")


# --------------------------------------------------------------------------- one-shots
def clip_breach_slam():
    L, HIT = 1.50, 0.55
    kp = keyposes([
        (0.00, {}, "auto"),
        (0.12, {"root.pos": (0, -0.8, 0.3), "torso": (20, 8, 0), "head": (-12, -6, 0),
                "right_arm": (0, 8, 22), "right_forearm": -40, "left_arm": (-16, -6, -22),
                "left_forearm": -40}, "smooth"),
        (0.36, {"root.pos": (0, 0.0, 1.1), "root": (0, 10, 0), "torso": (-14, 20, -4), "head": (4, -16, 0),
                "right_arm": (-165, 12, -26), "right_forearm": -35, "left_arm": (-160, -10, 28),
                "left_forearm": -35}, "smooth"),
        (0.45, {"root.pos": (0, 0.1, 1.2), "torso": (-16, 22, -5), "head": (5, -18, 0),
                "right_arm": (-172, 12, -26), "right_forearm": -38}, "auto"),
        (HIT, {"root.pos": (0, -3.0, -1.6), "root": (0, -8, 0), "torso": (40, -14, 6), "head": (-26, 12, 0),
               "right_arm": (-44, -8, 10), "right_forearm": -6, "left_arm": (-40, 10, -14),
               "left_forearm": -12}, "in2"),
        (0.61, {"root.pos": (0, -3.2, -1.7), "torso": (42, -15, 6), "right_arm": (-41, -8, 10)}, "out2"),
        (0.72, {"root.pos": (0, -2.8, -1.5), "torso": (36, -10, 5), "right_arm": (-52, -6, 12),
                "right_forearm": -18}, "out"),
        (1.02, {"root.pos": (0, -3.6, -2.0), "root": (0, -12, 0), "torso": (40, 8, 12), "head": (-26, -10, -6),
                "right_arm": (4, 16, 22), "right_forearm": -8, "left_arm": (8, 0, -24),
                "left_forearm": -30}, "smooth"),
        (1.24, {"root.pos": (0, -0.6, -0.3), "root": (0, 2, 0), "torso": (8, -4, -2), "head": (-4, 4, 0),
                "right_arm": (-8, 0, 10), "right_forearm": -40, "left_arm": (-8, 4, -10),
                "left_forearm": -30}, "smooth"),
        (L, READY, "smooth"),
    ])
    solve = oneshot(kp, left_grip=lambda t: ramp(t, 0.02, 0.24, 0.70, 0.92))
    return dict(rig="raider", variant=er.BRUTE, const="BREACH_SLAM", length=L, loop=False, solve=solve,
                keep=(0.36, 0.45, HIT, 0.61), root_base=ROOT_BASE, checks=strike_checks(HIT, er.BRUTE),
                scenery=[DOOR], cam=OVERHEAD,
                contract="1.50 s one-shot, BRUTE door break; impact keyframe 0.55 s (reaction-first)")


def clip_raider_strike():
    L, HIT = 0.55, 0.25
    kp = keyposes([
        (0.00, {}, "auto"),
        (0.09, {"root.pos": (0, -0.5, 0.6), "root": (0, 8, 0), "torso": (4, 26, -6), "head": (-4, -24, 0),
                "right_arm": (-135, 28, -34), "right_forearm": -45, "left_arm": (-40, -8, -14),
                "left_forearm": -35}, "smooth"),
        (HIT, {"root.pos": (0, -2.0, -2.0), "root": (0, -10, 0), "torso": (32, -22, 6), "head": (-22, 20, 0),
               "right_arm": (-30, -14, 4), "right_forearm": -15, "left_arm": (30, 6, -16),
               "left_forearm": -40}, "in"),
        (0.30, {"root.pos": (0, -2.1, -2.05), "torso": (33, -23, 6), "right_arm": (-28, -14, 4)}, "lin"),
        (0.40, {"root.pos": (0, -1.0, -0.6), "root": (0, -3, 0), "torso": (20, -4, 2), "head": (-12, 4, 0),
                "right_arm": (-30, -4, 10), "right_forearm": -55, "left_arm": (-10, 4, -12),
                "left_forearm": -30}, "out"),
        (L, READY, "smooth"),
    ])
    return dict(rig="raider", variant=er.SKIRM, const="RAIDER_STRIKE", length=L, loop=False,
                solve=oneshot(kp), keep=(0.09, HIT, 0.30), root_base=ROOT_BASE,
                checks=strike_checks(HIT, er.SKIRM), scenery=[DUMMY], cam=OVERHEAD,
                contract="0.55 s one-shot; shoulder-led hacking blow, contact pose 0.25 s held to 0.30 s")


def clip_raider_light():
    L, HIT = 0.55, 0.25
    kp = keyposes([
        (0.00, {}, "auto"),
        (0.10, {"root.pos": (0, -0.6, 0.4), "root": (0, 12, 0), "torso": (8, 34, -10), "head": (-2, -30, -4),
                "right_arm": (-95, 55, 40), "right_forearm": -60, "left_arm": (-50, -20, -24),
                "left_forearm": -30}, "smooth"),
        (HIT, {"root.pos": (0, -1.6, -1.6), "root": (0, -14, 0), "torso": (24, -30, 14), "head": (-18, 26, -8),
               "right_arm": (-70, -40, -10), "right_forearm": -12, "left_arm": (25, 20, -30),
               "left_forearm": -45}, "in"),
        (0.30, {"root.pos": (0, -1.7, -1.65), "torso": (25, -32, 15), "right_arm": (-68, -44, -12)}, "lin"),
        (0.40, {"root.pos": (0, -1.3, -1.0), "root": (0, -16, 0), "torso": (22, -36, 16), "head": (-14, 30, -6),
                "right_arm": (-40, -50, -16), "right_forearm": -40}, "out"),
        (L, READY, "smooth"),
    ])
    return dict(rig="raider", variant=er.SKIRM, const="RAIDER_LIGHT", length=L, loop=False,
                solve=oneshot(kp), keep=(0.10, HIT, 0.30), root_base=ROOT_BASE,
                checks=strike_checks(HIT, er.SKIRM), scenery=[DUMMY], cam=OVERHEAD,
                contract="0.55 s one-shot (11 ticks); HIT tick 5 = 0.25 s; hit-stop 0.25-0.30; "
                         "recovery to 0.55 (combat spec a18c40b89e2b1229b)")


def clip_brute_club_strike():
    L, HIT = 0.75, 0.35
    kp = keyposes([
        (0.00, {}, "auto"),
        (0.25, {"root.pos": (0, -0.1, 0.9), "root": (0, 10, 0), "torso": (-8, 26, -6), "head": (4, -24, 0),
                "right_arm": (-160, 24, 22), "right_forearm": -45, "left_arm": (-55, -12, -18),
                "left_forearm": -40}, "smooth"),
        (HIT, {"root.pos": (0, -2.4, -1.6), "root": (0, -10, 0), "torso": (32, -22, 8), "head": (-22, 18, 0),
               "right_arm": (-42, -16, 12), "right_forearm": -8, "left_arm": (30, 10, -18),
               "left_forearm": -25}, "in"),
        (0.40, {"root.pos": (0, -2.7, -1.8), "torso": (36, -26, 9), "right_arm": (-26, -20, 14),
                "right_forearm": -6}, "out2"),
        (0.50, {"root.pos": (0, -2.0, -1.2), "torso": (26, -14, 5), "head": (-16, 10, 0),
                "right_arm": (-14, -10, 12), "right_forearm": -22}, "auto"),
        (L, READY, "smooth"),
    ])
    return dict(rig="raider", variant=er.BRUTE, const="BRUTE_CLUB_STRIKE", length=L, loop=False,
                solve=oneshot(kp), keep=(0.25, HIT, 0.40, 0.50), root_base=ROOT_BASE,
                checks=strike_checks(HIT, er.BRUTE), scenery=[DUMMY], cam=OVERHEAD,
                contract="0.75 s one-shot; wind-up peak 0.25, club contact 0.35 s = RaiderMeleeGoal "
                         "BRUTE_CLUB_CONTACT_TICK 7, overshoot 0.40, follow-through 0.50")


def clip_raider_heavy():
    L, HIT = 1.20, 0.60
    kp = keyposes([
        (0.00, {}, "auto"),
        (0.12, {"root.pos": (0, -0.9, 0.2), "torso": (22, 4, 0), "head": (-14, -4, 0),
                "right_arm": (0, 4, 10), "right_forearm": -60, "left_arm": (-25, 8, -10),
                "left_forearm": -55}, "smooth"),
        (0.45, {"root.pos": (0, 0.0, 1.3), "root": (0, 4, 0), "torso": (-22, 10, -2), "head": (6, -8, 0),
                "right_arm": (-178, 8, 4), "right_forearm": -60, "left_arm": (-172, -8, -4),
                "left_forearm": -55}, "smooth"),
        (0.51, {"root.pos": (0, 0.1, 1.4), "torso": (-25, 11, -2), "head": (7, -9, 0),
                "right_arm": (-182, 8, 4), "right_forearm": -64}, "auto"),
        (HIT, {"root.pos": (0, -3.6, -2.2), "root": (0, -4, 0), "torso": (42, -6, 4), "head": (-28, 4, 0),
               "right_arm": (-54, -4, 6), "right_forearm": -6, "left_arm": (-50, 6, -8),
               "left_forearm": -10}, "in"),
        (0.70, {"root.pos": (0, -3.8, -2.3), "torso": (44, -6, 4), "right_arm": (-51, -4, 6)}, "lin"),
        (0.80, {"root.pos": (0, -3.3, -2.0), "torso": (40, -2, 6), "right_arm": (-58, -4, 10),
                "right_forearm": -16}, "out"),
        (1.00, {"root.pos": (0, -3.9, -2.6), "root": (0, -10, 0), "torso": (50, 10, 12), "head": (-34, -8, -6),
                "right_arm": (8, 14, 22), "right_forearm": -6, "left_arm": (6, -4, -26),
                "left_forearm": -30}, "smooth"),
        (L, READY, "smooth"),
    ])
    solve = oneshot(kp, left_grip=lambda t: ramp(t, 0.02, 0.25, 0.74, 0.90))
    return dict(rig="raider", variant=er.BRUTE, const="RAIDER_HEAVY", length=L, loop=False, solve=solve,
                keep=(0.45, 0.51, HIT, 0.70), root_base=ROOT_BASE,
                checks=strike_checks(HIT, er.BRUTE), scenery=[DUMMY], cam=OVERHEAD,
                contract="1.20 s one-shot (24 ticks); windup 0-0.60 with hang ~0.45-0.52; HIT tick 12 = "
                         "0.60 s; hit-stop 0.60-0.70; overbalanced recovery 0.70-1.20 (combat spec)")


def clip_loot_snatch():
    L = 0.70
    kp = keyposes([
        (0.00, {}, "auto"),
        (0.05, {"torso": (12, 6, 0), "right_arm": (-6, 0, 10), "right_forearm": -60}, "smooth"),
        (0.20, {"root.pos": (0, -2.2, -0.8), "torso": (40, -8, 0), "head": (-22, 6, 0),
                "right_arm": (-62, 10, 6), "right_forearm": -10, "left_arm": (-22, 0, -14),
                "left_forearm": -40}, "in2"),
        (0.30, {"root.pos": (0, -2.3, -0.85), "torso": (41, -8, 0), "right_arm": (-58, 10, 6),
                "right_forearm": -35}, "out2"),
        (0.45, {"root.pos": (0, -0.8, 0.2), "root": (0, -8, 0), "torso": (6, -30, -8), "head": (-4, -45, 4),
                "right_arm": (15, 20, 10), "right_forearm": -120, "left_arm": (0, 0, -10),
                "left_forearm": -30}, "out"),
        (0.60, {"head": (-4, -50, 5), "torso": (6, -31, -8)}, "auto"),
        (L, READY, "smooth"),
    ])
    return dict(rig="raider", variant=er.SKIRM, const="LOOT_SNATCH", length=L, loop=False,
                solve=oneshot(kp), keep=(0.20, 0.30, 0.45), root_base=ROOT_BASE,
                checks=plant_checks(), scenery=[CHEST],
                contract="0.70 s one-shot; grip 0.20-0.30 s, look-over-shoulder 0.45-0.60 s")


def _upper_only(kp):
    """Cinematic clips own arms/torso/head only; the preview adds the idle root + legs."""
    legs = LegIK()

    def solve(t):
        ch = to_ch({k: v for k, v in kp(t).items() if k.split(".")[0] in UPPER})
        er.add(ch, "root", pos=ROOT_BASE)
        legs(ch, FEET)
        return ch
    return solve


def clip_cinematic_exposed():
    L = 0.80
    kp = keyposes([
        (0.00, {}, "auto"),
        (0.15, {"torso": (-16, 0, -6), "head": (-14, 0, 8), "right_arm": (-12, 10, 68), "right_forearm": -20,
                "left_arm": (-10, -10, -64), "left_forearm": -15}, "out"),
        (0.30, {"torso": (-18, 2, -7), "head": (-15, 3, 9), "right_arm": (-8, 12, 74),
                "left_arm": (-6, -12, -70)}, "auto"),
        (0.45, {"torso": (-19, -1, -8), "head": (-16, -2, 10), "right_arm": (-6, 12, 76), "right_forearm": -24,
                "left_arm": (-4, -12, -72), "left_forearm": -18}, "auto"),
        (L, READY, "smooth"),
    ])
    return dict(rig="raider", variant=er.CAPTAIN, const="CINEMATIC_EXPOSED", length=L, loop=False,
                solve=_upper_only(kp), bones=UPPER, keep=(0.15, 0.45),
                contract="0.80 s one-shot; arms/torso/head only (legs stay on the idle)")


def clip_cinematic_stagger():
    L = 0.40
    kp = keyposes([
        (0.00, {}, "auto"),
        (0.10, {"torso": (-20, 12, 12), "head": (22, 10, -14), "right_arm": (30, 26, 70), "right_forearm": -10,
                "left_arm": (24, -24, -68), "left_forearm": -12}, "out"),
        (0.25, {"torso": (-2, 4, 5), "head": (4, 3, -5), "right_arm": (10, 10, 36), "right_forearm": -25,
                "left_arm": (8, -10, -36), "left_forearm": -20}, "auto"),
        (L, READY, "smooth"),
    ])
    return dict(rig="raider", variant=er.CAPTAIN, const="CINEMATIC_STAGGER", length=L, loop=False,
                solve=_upper_only(kp), bones=UPPER, keep=(0.10, 0.25),
                contract="0.40 s one-shot (state expires 420 ms); arms/torso/head only; also the "
                         "raider stagger that interrupts RAIDER_HEAVY")


# --------------------------------------------------------------------------- crushing brute blows
def _stuck_keys(b, k, lift):
    """Whiffed-slam over-commitment (combat spec): the club head stays buried at the impact point
    while the brute strains back twice (the arm pitches forward as the torso rises so the head
    does not move), rips it free with a jolt that throws the club up, staggers, then heaves."""
    a = -53.0 - lift
    return [
        (b["tug1"], {"root.pos": k((0.4, -5.1, -3.35)), "root": k((0, -12, 0)), "torso": k((54, -14, 6)),
                     "head": k((-40, 10, 0)), "right_arm": (a + 5, -6, 6), "right_forearm": -3}, "smooth"),
        (b["tug1"] + 0.05, {"root.pos": k((0.5, -5.2, -3.45)), "torso": k((57, -16, 6)),
                            "right_arm": (a + 1, -6, 6)}, "smooth"),
        (b["tug2"], {"root.pos": k((0.3, -4.9, -3.2)), "torso": k((52, -12, 5)), "head": k((-34, 8, 0)),
                     "right_arm": (a + 9, -6, 6), "right_forearm": -3}, "smooth"),
        (b["free"], {"root.pos": k((-0.2, -3.4, -1.6)), "root": k((0, -4, 0)), "torso": k((30, -4, 2)),
                     "head": k((-20, 0, 0)), "right_arm": (-100, -4, 10), "right_forearm": -34,
                     "left_arm": (-60, 6, -20), "left_forearm": -30}, "out"),
        (b["stagger"], {"root.pos": k((0.2, -4.6, -2.3)), "root": k((0, -14, 0)), "torso": k((46, -8, 9)),
                        "head": k((-36, -6, -4)), "right_arm": (-44, 8, 18), "right_forearm": -12,
                        "left_arm": (-10, -4, -40), "left_forearm": -30}, "smooth"),
    ]


def brute_crush(L, b, amp=1.0, step=4.2, lift=0.0):
    """Owner brief 2026-09-26: crushing, readable-at-distance brute blows.
    b: beat times {sink, load, top, hang, hit, hold, bounce, crouch, heave, sag, rise, lift, land,
    drag0, drag1}. Windup loads the BACK (right) leg with the torso twisted away and the club high
    behind the head in both hands, hangs, then steps the LEFT foot `step` px forward while the
    hips drive and the torso crunches; the club lands near the ground (hard stop + hit-stop hold),
    bounces, follows through into a crouch, and recovers with laboured shoulder heaves.
    amp scales the depth (1.0 = RAIDER_HEAVY)."""
    # lift: extra forward arm pitch at contact so the club HEAD rests on the ground, not in it

    def k(v, s=amp):
        return tuple(x * s for x in v)
    kp = keyposes([
        (0.00, {}, "auto"),
        (b["sink"], {"root.pos": k((0, -1.2, -0.2)), "torso": (24, -6, 0), "head": (-16, 4, 0),
                     "right_arm": (4, 10, 22), "right_forearm": -40, "left_arm": (-16, -6, -22),
                     "left_forearm": -40}, "smooth"),
        (b["load"], {"root.pos": k((-0.7, -0.6, 1.8)), "root": k((0, 18, 0)), "torso": (-8, 22, -6),
                     "head": (0, -30, 0), "right_arm": (-125, 20, -30), "right_forearm": -40,
                     "left_arm": (-110, -10, 40), "left_forearm": -30}, "auto"),
        (b["top"], {"root.pos": k((-0.9, 0.2, 2.2)), "root": k((0, 22, 0)), "torso": k((-26, 26, -8)),
                    "head": (4, -30, 0), "right_arm": (-172, 12, -26), "right_forearm": -35}, "smooth"),
        (b["hang"], {"root.pos": k((-1.0, 0.3, 2.3)), "torso": k((-28, 27, -8)), "head": (5, -31, 0),
                     "right_arm": (-178, 12, -26), "right_forearm": -38}, "auto"),
        (b["hit"], {"root.pos": k((0.6, -5.0, -3.4)), "root": k((0, -14, 0)), "torso": k((58, -18, 6)),
                    "head": k((-44, 14, 0)), "right_arm": (-53 - 20 * (1 - amp) - lift, -6, 6), "right_forearm": -4,
                    "left_arm": (-40, 6, -8), "left_forearm": -10}, "in2"),
        (b["hold"], {"root.pos": k((0.6, -5.2, -3.5)), "torso": k((60, -18, 6)),
                     "right_arm": (-52 - 20 * (1 - amp) - lift, -6, 6)}, "lin"),
    ] + (_stuck_keys(b, k, lift) if "tug1" in b else [
        (b["bounce"], {"root.pos": k((0.5, -4.9, -3.3)), "torso": k((56, -16, 6)),
                       "right_arm": (-68 - 20 * (1 - amp), -6, 8), "right_forearm": -18}, "out"),
        (b["crouch"], {"root.pos": k((0.3, -6.0, -3.6)), "root": k((0, -18, 0)), "torso": k((56, -6, 10)),
                       "head": k((-46, -6, -4)), "right_arm": (-56 - lift, 10, 18), "right_forearm": -10,
                       "left_arm": (-10, -4, -40), "left_forearm": -30}, "smooth"),
    ]) + [
        (b["heave"], {"root.pos": k((0.2, -4.4, -2.6)), "root": k((0, -12, 0)), "torso": k((46, -4, 6)),
                      "head": k((-32, 0, 0)), "right_arm": (-44, 6, 16), "right_forearm": -16}, "smooth"),
        (b["sag"], {"root.pos": k((0.1, -5.0, -2.4)), "torso": k((54, -2, 7)), "head": k((-38, 0, 0)),
                    "right_arm": (-48 - lift, 8, 18)}, "smooth"),
        (b["rise"], {"root.pos": k((0, -2.4, -1.0)), "root": (0, -3, 0), "torso": (30, 0, 3),
                     "head": (-18, 0, 0), "right_arm": (-14, 0, 10), "right_forearm": -30,
                     "left_arm": (-6, 4, -10), "left_forearm": -28}, "smooth"),
        (L, READY, "smooth"),
    ])
    home = FEET["left"].copy()
    fwd = home + np.array([0.3, 0.0, -step * amp])

    def feet(t):
        f = dict(FEET)
        if t <= b["lift"] or t >= b["drag1"]:
            return f
        if t < b["land"]:                               # step into the blow
            u = (t - b["lift"]) / (b["land"] - b["lift"])
            e = u * u * (3 - 2 * u)
            p = home + (fwd - home) * e
            p[1] = 24.0 - 2.2 * math.sin(math.pi * u)
        elif t < b["drag0"]:
            p = fwd.copy()
        else:                                           # heavy drag back home
            u = (t - b["drag0"]) / (b["drag1"] - b["drag0"])
            e = u * u * (3 - 2 * u)
            p = fwd + (home - fwd) * e
            p[1] = 24.0 - 0.8 * math.sin(math.pi * u)
        f["left"] = p
        return f

    if "tug1" in b:          # both hands stay on the stuck haft until it rips free
        grip = lambda t: ramp(t, 0.02, b["load"], b["free"] - 0.04, b["free"] + 0.08)  # noqa: E731
    else:
        grip = lambda t: ramp(t, 0.02, b["load"], b["bounce"], b["crouch"] - 0.04)  # noqa: E731
    pin = (b["hit"], b["tug2"], b["free"] - 0.02) if "tug1" in b else None
    return oneshot(kp, left_grip=grip, feet=feet, pin_tip=pin), feet


# --------------------------------------------------------------------------- skirmisher pests
def mirror(d):
    """Mirror a keypose dict across the body's X axis (swap sides, negate x-pos / y,z-rot)."""
    out = {}
    for c, v in d.items():
        bone = c.replace("right_", "@").replace("left_", "right_").replace("@", "left_")
        v = _vec(v)
        if c.endswith(".pos"):
            out[bone] = (-v[0], v[1], v[2])
        else:
            out[bone] = (v[0], -v[1], -v[2])
    return out


def clip_raider_light_jab():
    L, HIT = 0.40, 0.15
    kp = keyposes([
        (0.00, {}, "auto"),
        (0.06, {"root.pos": (0, -0.6, 0.3), "root": (0, 6, 0), "torso": (12, 14, -2), "head": (-6, -12, 0),
                "right_arm": (8, 0, 12), "right_forearm": -90, "left_arm": (-30, -6, -14),
                "left_forearm": -40}, "smooth"),
        (HIT, {"root.pos": (0, -1.6, -1.8), "root": (0, -10, 0), "torso": (26, -18, 4), "head": (-20, 16, 0),
               "right_arm": (-86, -8, 4), "right_forearm": -4, "left_arm": (24, 6, -16),
               "left_forearm": -40}, "in"),
        (0.20, {"root.pos": (0, -1.7, -1.85), "right_arm": (-84, -8, 4)}, "lin"),
        (0.30, {"root.pos": (0, -1.0, -0.4), "root": (0, -2, 0), "torso": (18, 2, 0), "head": (-10, 2, 0),
                "right_arm": (-24, -2, 10), "right_forearm": -60, "left_arm": (-8, 4, -10),
                "left_forearm": -30}, "out"),
        (L, READY, "smooth"),
    ])
    return dict(rig="raider", variant=er.SKIRM, const="RAIDER_LIGHT", length=L, loop=False,
                solve=oneshot(kp), keep=(0.06, HIT, 0.20), root_base=ROOT_BASE,
                checks=strike_checks(HIT, er.SKIRM), scenery=[DUMMY], cam=OVERHEAD,
                contract="0.40 s one-shot (8 ticks); snappy jab, HIT tick 3 = 0.15 s, hit-stop 0.15-0.20")


def clip_hop_back():
    L = 0.40
    kp = keyposes([
        (0.00, {}, "auto"),
        (0.10, {"root.pos": (0, -3.2, -0.4), "torso": (30, 0, 0), "head": (-22, 0, 0),
                "right_arm": (22, 0, 16), "right_forearm": -50, "left_arm": (20, 0, -16),
                "left_forearm": -40}, "smooth"),
        (0.15, {"root.pos": (0, 0.6, 0.6), "torso": (10, 0, 0), "head": (-8, 0, 0)}, "in2"),
        (0.22, {"root.pos": (0, 1.8, 0.8), "torso": (18, 0, 0), "head": (-14, 0, 0),
                "right_arm": (-40, -6, 18), "right_forearm": -70, "left_arm": (-50, 6, -20),
                "left_forearm": -50}, "auto"),
        (0.30, {"root.pos": (0, -3.4, 0.2), "torso": (32, 0, 0), "head": (-24, 0, 0),
                "right_arm": (-20, -6, 16), "right_forearm": -70, "left_arm": (-26, 6, -18),
                "left_forearm": -44}, "in2"),
        (0.35, {"root.pos": (0, -2.6, 0.1), "torso": (28, 0, 0)}, "out"),
        (L, READY, "smooth"),
    ])

    def feet(t):
        u = (t - 0.14) / (0.30 - 0.14)
        if u <= 0 or u >= 1:
            return FEET
        up = 4.0 * math.sin(math.pi * u)
        f = {}
        for side in ("right", "left"):
            q = FEET[side].copy()
            q[1] -= up
            q[2] -= 1.6 * math.sin(math.pi * u)        # legs trail forward as the body goes back
            f[side] = q
        return f
    return dict(rig="raider", variant=er.SKIRM, const="RAIDER_HOP_BACK", length=L, loop=False,
                solve=oneshot(kp, feet=feet), keep=(0.10, 0.30), root_base=ROOT_BASE,
                checks=plant_checks(feet), contract="0.40 s one-shot (8 ticks); push-off tick 2 = 0.10 s "
                "(server velocity), airborne tuck, land tick 6 = 0.30 s in a crouch")


_DODGE = [
    (0.05, {"root.pos": (-0.6, -1.4, 0), "torso": (18, -4, -6), "head": (-10, 4, 4)}, "smooth"),
    (0.20, {"root.pos": (1.6, -3.3, 0.4), "root": (0, 10, 0), "torso": (32, 16, 18), "head": (-20, -12, -12),
            "right_arm": (-40, -10, 20), "right_forearm": -85, "left_arm": (-10, 10, -42),
            "left_forearm": -28}, "out"),
    (0.30, {"root.pos": (1.4, -3.0, 0.3), "torso": (29, 13, 15), "head": (-18, -8, -10)}, "auto"),
]


def _dodge(const, side):
    L = 0.45
    keys = [(0.0, {}, "auto")] + [(t, d if side == "left" else mirror(d), m) for t, d, m in _DODGE] \
        + [(L, READY, "smooth")]

    def feet(t):
        u = (t - 0.05) / 0.12
        if u <= 0 or u >= 1:
            return FEET
        return {k: v - np.array([0, 1.8 * math.sin(math.pi * u), 0]) for k, v in FEET.items()}
    return dict(rig="raider", variant=er.SKIRM, const=const, length=L, loop=False,
                solve=oneshot(keyposes(keys), feet=feet), keep=(0.05, 0.20), root_base=ROOT_BASE,
                checks=plant_checks(feet), contract=f"0.45 s one-shot (9 ticks); lateral hop to the {side} "
                f"(server velocity tick 1), ducked off-line by tick 4 = 0.20 s")


def clip_taunt():
    L = 2.00
    legs = LegIK()

    def solve(t):
        w = ramp(t, 0.0, 0.25, 1.70, 2.0)
        ch = to_ch(dict(READY))
        er.add(ch, "root", pos=ROOT_BASE)
        bounce = 0.5 - 0.5 * math.cos(2 * math.pi * t / 0.25)              # toe bounce, 8 per clip
        er.add(ch, "root", pos=(0.3 * sin01(t, 1.0) * w, (0.9 * bounce - 0.3) * w, 0), rot=(0, 8 * w, 0))
        er.add(ch, "torso", rot=((-6 + 2 * bounce) * w, (6 + 4 * sin01(t, 1.0)) * w,
                                 4 * sin01(t, 1.0, 0.25) * w))
        er.add(ch, "head", rot=((-4 + 5 * sin01(t, 0.5)) * w, -10 * w, 12 * sin01(t, 1.0) * w))
        curl = 0.5 - 0.5 * math.cos(2 * math.pi * max(0.0, t - 0.3) / 0.4)   # beckoning fingers x3
        cw = ramp(t, 0.25, 0.35, 1.45, 1.6)
        er.add(ch, "left_arm", rot=(-60 * w, -10 * w, 6 * w))
        er.add(ch, "left_forearm", rot=((20 - 70 * curl) * cw, 0, 0))
        er.add(ch, "right_arm", rot=(-10 * w, 0, (6 + 10 * sin01(t, 0.3)) * w))    # dagger waggle
        er.add(ch, "right_forearm", rot=((-20 + 12 * sin01(t, 0.3, 0.2)) * w, 0, 0))
        legs(ch, FEET)
        return ch
    return dict(rig="raider", variant=er.SKIRM, const="RAIDER_TAUNT", length=L, loop=False, solve=solve,
                root_base=ROOT_BASE, checks=plant_checks(), loops=1,
                contract="2.00 s one-shot (40 ticks); cocky jeer: toe bounce, beckon, head bob, weapon waggle")


# Beat sheets agreed with the combat agent (a18c40b89e2b1229b). HIT is the server contact tick.
# Retimed 2026-09-26 for a fairer telegraph (combat agent): CLUB 34 ticks / HIT 18,
# HEAVY 44 ticks / HIT 24 with a stuck-club over-commit recovery.
HEAVY_T = dict(L=2.20, sink=0.18, load=0.50, top=0.75, hang=1.10, hit=1.20, hold=1.30,
               tug1=1.40, tug2=1.52, free=1.62, stagger=1.76, heave=1.90, sag=2.00, rise=2.10,
               lift=1.07, land=1.19, drag0=1.84, drag1=2.14)
CLUB_T = dict(L=1.70, sink=0.14, load=0.40, top=0.60, hang=0.80, hit=0.90, hold=1.00, bounce=1.05,
              crouch=1.25, heave=1.40, sag=1.50, rise=1.60, lift=0.78, land=0.89, drag0=1.30, drag1=1.62)


def clip_raider_heavy_crush():
    b = HEAVY_T
    solve, feet = brute_crush(b["L"], b, amp=1.0, step=4.2, lift=8.0)
    return dict(rig="raider", variant=er.BRUTE, const="RAIDER_HEAVY", length=b["L"], loop=False, solve=solve,
                keep=(b["top"], b["hang"], b["hit"], b["hold"], b["tug1"], b["free"]), root_base=ROOT_BASE,
                checks=strike_checks(b["hit"], er.BRUTE, feet, stuck=(b["hit"], b["tug2"])), scenery=[DUMMY],
                cam=OVERHEAD, loops=1,
                contract=f"{b['L']:.2f} s one-shot; windup to top {b['top']}, hang to {b['hang']}, HIT "
                         f"{b['hit']} s (server tick {round(b['hit'] * 20)}), hit-stop to {b['hold']}, bounce, "
                         f"club stuck in the ground {b['hold']}-{b['free']}, rip free, stagger, heave to {b['L']}")


def clip_brute_club_crush():
    b = CLUB_T
    solve, feet = brute_crush(b["L"], b, amp=0.93, step=3.8, lift=1.8)
    return dict(rig="raider", variant=er.BRUTE, const="BRUTE_CLUB_STRIKE", length=b["L"], loop=False,
                solve=solve, keep=(b["top"], b["hang"], b["hit"], b["hold"], b["bounce"]), root_base=ROOT_BASE,
                checks=strike_checks(b["hit"], er.BRUTE, feet), scenery=[DUMMY], cam=OVERHEAD,
                contract=f"{b['L']:.2f} s one-shot; club contact {b['hit']} s = RaiderMeleeGoal "
                         f"BRUTE_CLUB_CONTACT_TICK {round(b['hit'] * 20)}; hit-stop to {b['hold']}, crouch, recovery")


CLIPS = {
    "STALK": clip_stalk, "BRUTE_MARCH": clip_brute_march, "SPRINT": clip_sprint,
    "MENACE_IDLE": clip_menace_idle, "BREACH_SLAM": clip_breach_slam,
    "RAIDER_STRIKE": clip_raider_strike, "BRUTE_CLUB_STRIKE": clip_brute_club_crush,
    "LOOT_SNATCH": clip_loot_snatch, "CINEMATIC_EXPOSED": clip_cinematic_exposed,
    "CINEMATIC_STAGGER": clip_cinematic_stagger,
    "RAIDER_LIGHT": clip_raider_light_jab, "RAIDER_HEAVY": clip_raider_heavy_crush,
    "RAIDER_HOP_BACK": clip_hop_back, "RAIDER_DODGE_LEFT": lambda: _dodge("RAIDER_DODGE_LEFT", "left"),
    "RAIDER_DODGE_RIGHT": lambda: _dodge("RAIDER_DODGE_RIGHT", "right"), "RAIDER_TAUNT": clip_taunt,
}

# Clips whose Java constant/length is being changed by the engine agent: exported to
# clips/enemies/staged/raider/ so AuthoredClipAssetsTest never sees a length mismatch; the engine
# moves them into assets together with its Java update. Empty this set once they have landed.
STAGE_UNTIL_JAVA = set()   # all landed 2026-09-26 (RaiderAnimations / RaiderMovesetAnimations)
