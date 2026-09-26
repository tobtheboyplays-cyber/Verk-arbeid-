"""Goblin thief clips (GoblinThiefModel rig, enemyrig.GOBLIN_PARTS). Quick, sneaky, twitchy.

Bend bones (engine, GoblinThiefModel): forearm at arm-local y 4.5 of the 0..9 arm, shin at
leg-local y 5 of the 0..10 leg. Timings are taken from the server code that drives each stage:

  GOBLIN_LURK       3.00 s loop  stage 0, stationary (twitchy head snaps, wringing hands)
  GOBLIN_SNEAK      1.00 s loop  stage 0 travel, crouched tiptoe (blocks_per_cycle in meta)
  GOBLIN_STEAL      3.00 s       stage 1, the 60-tick theft window: anticipate 0-0.6 (ticks
                                 0-12), latch/work 0.6-2.4 (12-48), grip + recover 2.4-3.0 (48-60)
  GOBLIN_FLEE       0.50 s loop  stage 2, upright carry run, left hand clamped on the pouch
  GOBLIN_PICK_LOCK  2.00 s       stage 3 door pick (40 ticks); pick twists on the TRIPWIRE
                                 clicks GoblinThiefDemo plays every 10 ticks (0.5/1.0/1.5 s)
  GOBLIN_POKE       0.30 s       DefensivePokeGoal swing, contact 0.15 s = tick 3
                                 (DEFENSIVE_POKE_CONTACT_TICKS). ADDITIVE over the stage pose
                                 (engine samples it at attackTime*0.3 s): exported as the delta
                                 from READY, so it starts and ends at exact zero.
Stage clips have absolute root channels; the crouch is baked into LURK/SNEAK (the vanilla
crouch flag no longer alters authored poses). Routed by the motion engine through
client/model/GoblinAnimations (rig "goblin").
"""

from __future__ import annotations

import math

import numpy as np

import enemyrig as er
from enemyrig import Curve, LegIK, ArmIK, sin01, bump
from raider_clips import keyposes, to_ch, locomotion

FEET = {"right": np.array([-2.5, 24.0, 1.0]), "left": np.array([2.5, 24.0, -1.3])}
READY = {
    "root.pos": (0, -1.6, 0), "root": (0, 0, 0),
    "torso": (20, 0, 0), "torso.pos": (0, 0, 0),
    "head": (-14, 0, 0),
    "right_arm": (-32, -6, 6), "right_forearm": (-58, 0, 0),
    "left_arm": (-28, 6, -6), "left_forearm": (-56, 0, 0),
}
DOOR = ("door", (-8, -8, -12), (16, 32, 3), (0.45, 0.30, 0.16, 1))
VICTIM = ("victim", (-4, -8, -18), (8, 32, 4), (0.25, 0.35, 0.60, 1))
HAND = (0, 4.5, 0)          # forearm-local end of the goblin hand


def _solve(kp, feet=FEET, extra=None):
    legs = LegIK()

    def solve(t):
        ch = to_ch(kp(t))
        if extra:
            extra(t, ch)
        legs(ch, feet, splay=0.18)
        return ch
    return solve


def _checks(contact=None, feet=FEET):
    def checks(times, samples):
        slip = 0.0
        hands = []
        for s in samples:
            for side in ("right", "left"):
                slip = max(slip, float(np.linalg.norm(er.sole(s, side) - feet[side])))
            hands.append(er.mcrig.xform(er.world(s)["right_forearm"], HAND))
        out = {"foot_slip_px_max": round(slip, 3)}
        if contact is not None:
            hands = np.array(hands)
            sp = np.linalg.norm(np.diff(hands, axis=0), axis=1) * er.FPS
            cf = int(round(contact * er.FPS))
            out.update({"contact_t": contact, "hand_speed_peak_t": round((int(np.argmax(sp)) + 1) / er.FPS, 3),
                        "hand_at_contact_px": [round(float(v), 2) for v in hands[cf]]})
        return out
    return checks


def clip_lurk():
    L = 3.0
    head = Curve([
        (0.00, (0, -10, 0)), (0.50, (1, -13, 2)), (0.58, (-2, 28, -5), "snap"), (1.08, (0, 31, -7)),
        (1.15, (0, -4, 0), "snap"), (1.50, (1, -2, 1)), (1.57, (-6, -36, 7), "snap"), (2.10, (-5, -33, 6)),
        (2.18, (2, 6, -2), "snap"), (2.55, (0, 2, 0)), (2.63, (0, -12, 1), "smooth"), (3.00, (0, -10, 0))], L)
    legs = LegIK()

    def solve(t):
        br = 0.5 - 0.5 * math.cos(2 * math.pi * t / 0.75)            # quick shallow breaths
        bob = 0.5 - 0.5 * math.cos(2 * math.pi * t / 1.5)
        h = head(t)
        hl = head(t - 0.07)                                           # torso follows the snap late
        ch = {"root": {"pos": (0.4 * sin01(t, L), -1.6 - 0.25 * bob, 0), "rot": (0, 0.15 * hl[1], 0)}}
        ch["torso"] = {"rot": (20 - 1.2 * br, 0.2 * hl[1], 1.5 * sin01(t, L, 0.2)), "pos": (0, 0.25 * br, 0)}
        ch["head"] = {"rot": (-14 + h[0] + 0.8 * br, h[1] * 0.65, h[2])}
        wr = sin01(t, 0.5)                                            # hand wringing
        ch["right_arm"] = {"rot": (-32 + 3 * wr, -8 - 2 * wr, 6)}
        ch["right_forearm"] = {"rot": (-60 - 6 * sin01(t, 0.5, 0.25), 0, 0)}
        ch["left_arm"] = {"rot": (-28 - 3 * wr, 8 - 2 * wr, -6)}
        ch["left_forearm"] = {"rot": (-58 + 6 * sin01(t, 0.5, 0.25), 0, 0)}
        legs(ch, FEET, splay=0.18)
        return ch
    return dict(rig="goblin", const="GOBLIN_LURK", length=L, loop=True, solve=solve, 
                checks=_checks(), loops=1, contract="3.00 s loop; stage 0 stationary")


def clip_sneak():
    L = 1.0
    st = dict(excursion=7.0, duty=0.60, lift=2.4, foot_x=2.3, sway=0.5, pelvis_yaw=5.0,
              pelvis_y=[(0.0, -1.9), (0.10, -2.2), (0.30, -1.6), (0.5, -1.9), (0.60, -2.2),
                        (0.80, -1.6), (1.0, -1.9)])

    def upper(u, ch, pel):
        sw = math.cos(2 * math.pi * (u - 0.06))
        lean = 22 + 1.5 * (bump(u, 0.12, 0.3, 1.0) + bump(u, 0.62, 0.3, 1.0))
        spine_yaw = 6 * math.cos(2 * math.pi * u)
        ch["torso"] = {"rot": (lean, spine_yaw, 2 * math.sin(2 * math.pi * u + 0.35))}
        glance = Curve([(0.0, (0, 0, 0)), (0.35, (0, 0, 0)), (0.40, (0, 12, 0), "snap"), (0.62, (0, 10, 0)),
                        (0.68, (0, 0, 0), "snap"), (1.0, (0, 0, 0))], 1.0)(u)
        ch["head"] = {"rot": (-lean + 8, -0.8 * (pel["yaw"] + spine_yaw) + glance[1], 0)}
        ch["right_arm"] = {"rot": (-34 + 5 * sw, -6, 6)}
        ch["right_forearm"] = {"rot": (-62 - 4 * max(0, -sw), 0, 0)}
        ch["left_arm"] = {"rot": (-30 - 5 * sw, 6, -6)}
        ch["left_forearm"] = {"rot": (-58 - 4 * max(0, sw), 0, 0)}

    solve, checks = locomotion(L, st, upper)
    return dict(rig="goblin", const="GOBLIN_SNEAK", length=L, loop=True, solve=solve, 
                checks=checks, blocks_per_cycle=st["excursion"] / st["duty"] / 16.0,
                contract="1.00 s loop; stage 0 crouched travel, distance-clocked")


def clip_flee():
    L = 0.5
    st = dict(excursion=11.0, duty=0.42, lift=3.4, foot_x=2.0, sway=0.3, pelvis_yaw=7.0,
              pelvis_y=[(0.0, -2.0), (0.21, -2.4), (0.46, -1.0), (0.5, -2.0), (0.71, -2.4),
                        (0.96, -1.0), (1.0, -2.0)])
    larm = ArmIK("left")

    def upper(u, ch, pel):
        sw = math.cos(2 * math.pi * (u - 0.05))
        spine_yaw = 10 * math.cos(2 * math.pi * u)
        lean = 12 + 2 * (bump(u, 0.2, 0.3, 1.0) + bump(u, 0.7, 0.3, 1.0))
        ch["torso"] = {"rot": (lean, spine_yaw, 0)}
        ch["head"] = {"rot": (-lean + 4, -0.8 * (pel["yaw"] + spine_yaw), 0)}
        ch["right_arm"] = {"rot": (-10 + 38 * sw, -6, 10)}
        ch["right_forearm"] = {"rot": (-70 - 20 * max(0, -sw), 0, 0)}
        ch["left_arm"] = {"rot": (-20, 0, -14)}
        ch["left_forearm"] = {"rot": (-70, 0, 0)}
        pouch = er.mcrig.xform(er.world(ch)["torso"], (3.0, 0.8, -5.2))       # front of the pouch
        larm(ch, pouch, [-20, 0, -14, -70])

    solve, checks = locomotion(L, st, upper)
    return dict(rig="goblin", const="GOBLIN_FLEE", length=L, loop=True, solve=solve, 
                checks=checks, blocks_per_cycle=st["excursion"] / st["duty"] / 16.0,
                contract="0.50 s loop; stage 2 carry run, left hand on the pouch")


def clip_steal():
    L = 3.0
    reach = {"root.pos": (0, -2.4, -0.6), "torso": (32, 0, 0), "head": (-20, 0, 0),
             "right_arm": (-76, -8, 4), "right_forearm": -26, "left_arm": (-70, 8, -4), "left_forearm": -30}
    kp = keyposes([
        (0.00, READY, "auto"),
        (0.18, {"root.pos": (0, -2.0, 0.3), "torso": (24, 0, 0), "right_arm": (-26, -6, 6),
                "right_forearm": -80, "left_arm": (-24, 6, -6), "left_forearm": -78}, "smooth"),
        (0.60, reach, "smooth"),
        (1.05, {"head": (-20, 2, 0)}, "auto"),
        (1.12, {"head": (-6, 42, -6), "torso": (30, 6, 0)}, "snap"),
        (1.40, {"head": (-6, 40, -5)}, "auto"),
        (1.47, {"head": (-20, 0, 0), "torso": (32, 0, 0)}, "snap"),
        (1.85, {"head": (-19, -2, 0)}, "auto"),
        (1.92, {"head": (-8, -38, 6), "torso": (30, -6, 0)}, "snap"),
        (2.15, {"head": (-8, -36, 6)}, "auto"),
        (2.22, {"head": (-20, 0, 0), "torso": (32, 0, 0)}, "snap"),
        (2.40, {"right_forearm": -22, "left_forearm": -26}, "auto"),
        (2.52, {"root.pos": (0, -1.2, 1.0), "torso": (8, 14, 0), "head": (-4, -18, 0),
                "right_arm": (10, 10, 10), "right_forearm": -110, "left_arm": (-10, 0, -16),
                "left_forearm": -90}, "smooth"),
        (2.70, {"root.pos": (0, -1.5, 0.6), "torso": (14, 8, 0), "head": (-10, -10, 0)}, "out"),
        (L, READY, "smooth"),
    ])

    def fidget(t, ch):                                            # latch work: busy fingers
        w = max(0.0, min(1.0, (t - 0.6) / 0.2)) * max(0.0, min(1.0, (2.4 - t) / 0.1))
        er.add(ch, "right_forearm", rot=(w * 5 * sin01(t, 0.2), 0, 0))
        er.add(ch, "left_forearm", rot=(w * 4 * sin01(t, 0.25, 0.3), 0, 0))
        er.add(ch, "right_arm", rot=(0, 0, w * 3 * sin01(t, 0.4)))
    return dict(rig="goblin", const="GOBLIN_STEAL", length=L, loop=False, solve=_solve(kp, extra=fidget),
                 keep=(0.6, 2.4, 2.52), checks=_checks(), scenery=[VICTIM], loops=1,
                contract="3.00 s one-shot = the 60-tick theft window: anticipate 0-0.6, latch 0.6-2.4, "
                         "grip/recover 2.4-3.0")


def clip_pick_lock():
    L = 2.0
    work = {"root.pos": (0, -2.2, -0.2), "torso": (28, 0, 0), "head": (-16, 0, 8),
            "right_arm": (-72, -10, 6), "right_forearm": -40, "left_arm": (-64, 10, -6), "left_forearm": -46}
    kp = keyposes([
        (0.00, READY, "auto"),
        (0.22, work, "smooth"),
        (1.10, {"head": (-16, 2, 8)}, "auto"),
        (1.17, {"head": (-4, 44, -4), "torso": (26, 8, 0)}, "snap"),
        (1.38, {"head": (-4, 42, -4)}, "auto"),
        (1.45, {"head": (-16, 0, 8), "torso": (28, 0, 0)}, "snap"),
        (1.80, {}, "auto"),
        (L, READY, "smooth"),
    ])

    def pick(t, ch):
        w = max(0.0, min(1.0, (t - 0.15) / 0.1)) * max(0.0, min(1.0, (1.9 - t) / 0.1))
        tw = sum(bump(t, c, 0.12) for c in (0.5, 1.0, 1.5)) + 0.5 * bump(t, 0.25, 0.12)
        er.add(ch, "right_forearm", rot=(-10 * tw * w + 2 * w * sin01(t, 0.16), 0, 0))
        er.add(ch, "right_arm", rot=(0, -6 * tw * w, 4 * tw * w))
        er.add(ch, "head", rot=(0, 0, 3 * tw * w))
    return dict(rig="goblin", const="GOBLIN_PICK_LOCK", length=L, loop=False, solve=_solve(kp, extra=pick),
                 keep=(0.5, 1.0, 1.5), checks=_checks(), scenery=[DOOR], loops=1,
                contract="2.00 s one-shot = 40-tick door pick; twists on the 10-tick lock clicks")


def clip_poke():
    L, HIT = 0.30, 0.15
    kp = keyposes([
        (0.00, READY, "auto"),
        (0.07, {"torso": (16, 14, 0), "right_arm": (-12, 10, 14), "right_forearm": -100,
                "head": (-14, -10, 0)}, "smooth"),
        (HIT, {"root.pos": (0, -1.9, -1.2), "torso": (26, -16, 0), "head": (-22, 14, 0),
               "right_arm": (-86, -6, 4), "right_forearm": -5, "left_arm": (-10, 6, -12)}, "in"),
        (0.19, {"right_arm": (-84, -6, 4)}, "lin"),
        (L, READY, "smooth"),
    ])
    solve = _solve(kp)
    base = lambda: _solve(lambda t: dict(READY))(0.0)  # stage pose it layers over; solved after use()
    return dict(rig="goblin", const="GOBLIN_POKE", length=L, loop=False, solve=solve, additive=base,
                keep=(0.07, HIT, 0.19), checks=_checks(HIT), scenery=[VICTIM],
                contract="0.30 s one-shot (6 ticks); contact 0.15 s = DEFENSIVE_POKE_CONTACT_TICKS 3")


CLIPS = {"GOBLIN_LURK": clip_lurk, "GOBLIN_SNEAK": clip_sneak, "GOBLIN_FLEE": clip_flee,
         "GOBLIN_STEAL": clip_steal, "GOBLIN_PICK_LOCK": clip_pick_lock, "GOBLIN_POKE": clip_poke}
