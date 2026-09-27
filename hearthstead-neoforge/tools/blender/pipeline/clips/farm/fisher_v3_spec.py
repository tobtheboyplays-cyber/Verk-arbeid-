"""FISHER v3 clip spec (pure numpy; imported by author_fisher_v3.py inside Blender
and runnable in plain Python for quick checks).

The Fisher fishes SEATED on his shore chair (SettlerModel: legs replaced by the
seated legs, root lowered 4 px), so these clips own the upper body: root (weight),
torso, head, both arms + elbows, the rod wrist (right_item) and the cloak.

Runtime (SettlerModel.applyFishingPose, fisher v3, 27 Sep): the server-synced
cycle phase 0..299 (FisherWorkGoal) is split into
  cast   phase   0..30  (FisherWorkGoal plays it in CAST_TICKS=34 real ticks)  FISHER_CAST_V3  1.70 s
  wait   phase  30..240 (the rest of the cycle, real time)                     FISHER_WAIT     4.00 s loop (+__v2 8 s)
  strike phase 240..282 (REEL_TICKS=44)                                        FISHER_STRIKE_REEL 2.20 s
  land   phase 282..300 (LAND_TICKS=50)                                        FISHER_LAND_FISH   2.50 s
so each clip plays at its authored speed whatever the fisher's dexterity.
Beats: bobber released 0.80 s into the cast and lands 1.25 s (phase 22); bite =
strike clip 0.00; the fish dangles and flaps 0.45-1.75 s, grab = land clip 2.05 s.

Rod (models/item/fishers_rod.json v3): butt item y=-4, grip y=4, tip y=30,
display R(-30,0,0) T(-1,.94,-1.7) S .85 -> held 60 deg above the forearm.
"""
from __future__ import annotations

import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
PIPE = os.path.abspath(os.path.join(HERE, "..", ".."))
if PIPE not in sys.path:
    sys.path.insert(0, PIPE)
import mcrig  # noqa: E402

D = mcrig.DEG
ROD_DISPLAY = ((-30.0, 0.0, 0.0), (-1.0, 0.94, -1.7), 0.85)
ROD_BUTT, ROD_GRIP, ROD_TIP = (8.0, -4.0, 8.0), (8.0, 4.0, 8.0), (8.0, 30.0, 8.0)
SEAT_DROP = 4.0          # SettlerModel fisherSeated: root.y += 4

# rest pose shared by the end of the cast, the wait loop and the strike start
HOLD = {
    "torso": (7.0, 0.0, 0.0),
    "head": (9.0, 0.0, 0.0),
    "right_arm": (-36.0, -8.0, 6.0),
    "right_forearm": (-34.0, 0.0, 0.0),
    "right_item": (0.0, 0.0, 0.0),
    "left_arm": (-20.0, 14.0, -4.0),
    "left_forearm": (-52.0, 0.0, 0.0),
}


# --------------------------------------------------------------------------- interpolation
def _tangents(ts, vs):
    n = len(ts)
    m = [0.0] * n
    for i in range(n):
        if i == 0 or i == n - 1:
            m[i] = 0.0
            continue
        a, b = vs[i] - vs[i - 1], vs[i + 1] - vs[i]
        if a * b <= 0:                 # extremum / flat: clamp (Blender AUTO_CLAMPED)
            m[i] = 0.0
        else:
            m[i] = (vs[i + 1] - vs[i - 1]) / (ts[i + 1] - ts[i - 1])
    return m


def sample_keys(keys, t):
    """keys [(t, v)] sorted; clamped cubic Hermite (hold outside the range)."""
    ts = [k[0] for k in keys]
    vs = [k[1] for k in keys]
    if t <= ts[0]:
        return vs[0]
    if t >= ts[-1]:
        return vs[-1]
    ms = _tangents(ts, vs)
    for i in range(len(ts) - 1):
        if ts[i] <= t <= ts[i + 1]:
            h = ts[i + 1] - ts[i]
            u = (t - ts[i]) / h
            h00, h10 = 2 * u ** 3 - 3 * u ** 2 + 1, u ** 3 - 2 * u ** 2 + u
            h01, h11 = -2 * u ** 3 + 3 * u ** 2, u ** 3 - u ** 2
            return h00 * vs[i] + h10 * h * ms[i] + h01 * vs[i + 1] + h11 * h * ms[i + 1]
    return vs[-1]


def vkeys(pairs):
    """[(t, (x,y,z))] -> three scalar key lists."""
    return [[(t, v[i]) for t, v in pairs] for i in range(3)]


class Clip:
    def __init__(self, const, length, loop, keys, beats=None, left_ik=None):
        self.const, self.length, self.loop, self.keys = const, length, loop, keys
        self.beats = beats or {}
        self.left_ik = left_ik
        self._cache = {b: vkeys(v) for b, v in keys.items()}
        self._ik_prev = None

    def channels(self, t):
        ch = {}
        for bone, (kx, ky, kz) in self._cache.items():
            if bone == "root_pos":
                continue
            ch[bone] = {"rot": (sample_keys(kx, t), sample_keys(ky, t), sample_keys(kz, t))}
        ch.setdefault("root", {"rot": (0.0, 0.0, 0.0)})
        if "root_pos" in self._cache:
            kx, ky, kz = self._cache["root_pos"]
            ch["root"]["pos"] = (sample_keys(kx, t), sample_keys(ky, t), sample_keys(kz, t))
        if self.left_ik is not None:
            w = mcrig.pose_matrices(seated_channels(ch))
            target, weight = self.left_ik(t, w, rod_matrix(w))
            if target is not None and weight > 1e-4:
                solved = solve_left_hand(ch, target, self._ik_prev)
                self._ik_prev = solved
                a0, f0 = ch["left_arm"]["rot"], ch["left_forearm"]["rot"]
                ch["left_arm"] = {"rot": tuple(a + (b - a) * weight for a, b in zip(a0, solved[:3]))}
                ch["left_forearm"] = {"rot": (f0[0] + (solved[3] - f0[0]) * weight, 0.0, 0.0)}
        return ch


def H(bone):
    return HOLD[bone]


def add(a, b):
    return tuple(x + y for x, y in zip(a, b))


# --------------------------------------------------------------------------- the clips
def cast_v3():
    """1.70 s one-shot: gather, rod up and back over the right shoulder, a beat,
    the forward whip (release 0.80 s), follow-through low, settle to HOLD."""
    L = 1.70
    k = {
        "torso": [(0.0, H("torso")), (0.18, (4.0, -3.0, 0.0)), (0.50, (-3.0, -12.0, -2.0)),
                  (0.62, (-4.0, -13.0, -2.0)), (0.80, (15.0, 5.0, 1.0)), (0.98, (17.0, 7.0, 1.0)),
                  (1.30, (9.0, 1.0, 0.0)), (L, H("torso"))],
        "head": [(0.0, H("head")), (0.20, (4.0, 6.0, 0.0)), (0.55, (-6.0, 10.0, 2.0)),
                 (0.80, (4.0, -4.0, 0.0)), (1.05, (12.0, -2.0, 0.0)), (1.40, (10.0, 0.0, 0.0)), (L, H("head"))],
        "right_arm": [(0.0, H("right_arm")), (0.18, (-70.0, -10.0, 10.0)), (0.50, (-108.0, 0.0, 6.0)),
                      (0.62, (-111.0, 0.0, 7.0)), (0.76, (-102.0, -10.0, 8.0)), (0.86, (-62.0, -8.0, 6.0)),
                      (1.00, (-46.0, -8.0, 6.0)), (1.35, (-38.0, -8.0, 6.0)), (L, H("right_arm"))],
        "right_forearm": [(0.0, H("right_forearm")), (0.18, (-40.0, 0, 0)), (0.50, (-36.0, 0, 0)),
                          (0.62, (-40.0, 0, 0)), (0.76, (-14.0, 0, 0)), (0.86, (-10.0, 0, 0)),
                          (1.00, (-22.0, 0, 0)), (1.35, (-32.0, 0, 0)), (L, H("right_forearm"))],
        # the wrist cocks back on the load and snaps through on the whip
        "right_item": [(0.0, H("right_item")), (0.18, (0.0, 0, 0)), (0.50, (-4.0, 12.0, 0)), (0.62, (-6.0, 15.0, 0)),
                       (0.78, (14.0, 0, 0)), (0.90, (18.0, 0, 0)), (1.20, (4.0, 0, 0)), (L, H("right_item"))],
        "left_arm": [(0.0, H("left_arm")), (0.18, (-40.0, 20.0, -6.0)), (0.50, (-70.0, 30.0, -8.0)),
                     (0.80, (-52.0, 22.0, -6.0)), (1.10, (-30.0, 16.0, -4.0)), (L, H("left_arm"))],
        "left_forearm": [(0.0, H("left_forearm")), (0.18, (-60.0, 0, 0)), (0.50, (-80.0, 0, 0)),
                         (0.80, (-62.0, 0, 0)), (1.10, (-55.0, 0, 0)), (L, H("left_forearm"))],
        "root_pos": [(0.0, (0, 0, 0)), (0.50, (0.2, 0.25, 0.4)), (0.80, (0, -0.1, -0.5)), (1.2, (0, 0, -0.1)),
                     (L, (0, 0, 0))],
    }
    return Clip("FISHER_CAST_V3", L, False, k, {"release": 0.80, "lands": 1.25})


def wait(variant=None):
    """4.00 s loop of patient watching: breath, a slow rod-tip drift, a wrist
    twitch at 1.3 s, a small settle of the weight. __v2 (8 s): second cycle has
    a look round up the bank over the left shoulder and back, and a shoulder roll."""
    L = 4.0
    ra, rf, ri = H("right_arm"), H("right_forearm"), H("right_item")
    k = {
        "torso": [(0.0, H("torso")), (1.0, (7.8, 0.8, 0.5)), (2.0, (6.4, 0.4, 0.0)), (3.0, (7.6, -0.6, -0.4)),
                  (L, H("torso"))],
        "head": [(0.0, H("head")), (0.9, (10.0, 3.0, 0.0)), (1.30, (11.0, 0.5, 0.0)), (2.2, (8.0, -4.0, -1.0)),
                 (3.1, (9.5, -1.5, 0.0)), (L, H("head"))],
        "right_arm": [(0.0, ra), (1.22, add(ra, (-1.0, 0, 0))), (1.30, add(ra, (-4.5, 0, 0))),
                      (1.45, add(ra, (-1.0, 0, 0))), (2.4, add(ra, (1.5, 1.0, 0))), (L, ra)],
        "right_forearm": [(0.0, rf), (1.30, add(rf, (-2.0, 0, 0))), (1.5, rf), (2.6, add(rf, (1.5, 0, 0))), (L, rf)],
        "right_item": [(0.0, ri), (1.26, ri), (1.32, add(ri, (-5.0, 0, 0))), (1.50, add(ri, (-1.0, 0, 0))),
                       (1.8, ri), (L, ri)],
        "left_arm": [(0.0, H("left_arm")), (1.6, add(H("left_arm"), (-3.0, 0, 0))),
                     (3.0, add(H("left_arm"), (1.0, 0, 0))), (L, H("left_arm"))],
        "left_forearm": [(0.0, H("left_forearm")), (1.6, add(H("left_forearm"), (4.0, 0, 0))), (L, H("left_forearm"))],
        "root_pos": [(0.0, (0, 0, 0)), (2.0, (0.15, 0.1, 0.0)), (L, (0, 0, 0))],
    }
    if variant == 2:
        L2 = 8.0
        k2 = {}
        for b, keys in k.items():
            k2[b] = keys[:-1] + [(t + L, v) for t, v in keys]
        # cycle 2: look round up the bank (left shoulder), a shoulder roll, back to the float
        k2["head"] = [kk for kk in k2["head"] if not (4.3 < kk[0] < 7.6)] + [
            (4.6, (6.0, -8.0, 0.0)), (5.1, (0.0, -38.0, 3.0)), (5.9, (-2.0, -42.0, 4.0)),
            (6.4, (2.0, -20.0, 1.0)), (6.9, (9.0, -2.0, 0.0))]
        k2["torso"] = [kk for kk in k2["torso"] if not (4.3 < kk[0] < 7.6)] + [
            (4.8, (6.0, -3.0, 0.0)), (5.4, (4.5, -8.0, -1.5)), (6.0, (5.0, -8.0, -1.0)),
            (6.7, (7.0, -1.0, 0.0)), (7.3, (8.0, 0.5, 1.2))]
        k2["left_arm"] = [kk for kk in k2["left_arm"] if not (4.3 < kk[0] < 7.6)] + [
            (5.3, add(H("left_arm"), (-6.0, 2.0, -3.0))), (6.2, add(H("left_arm"), (-4.0, 1.0, -2.0))),
            (7.0, H("left_arm"))]
        for b in k2:
            k2[b] = sorted(k2[b], key=lambda kv: kv[0])
        return Clip("FISHER_WAIT__V2", L2, True, k2)
    return Clip("FISHER_WAIT", L, True, k)


FISH_GRAB = (2.5, 5.0, -9.5)        # model px (seated rig): where the landed fish swings in to the left hand
REEL_ARM = (-35.0, -30.0, -10.0)    # rod butt tucked across the belly so the left hand reaches the crank
REEL_FORE = (-50.0, 0.0, 0.0)
REEL_ITEM = (-20.0, 0.0, -25.0)


def strike_reel():
    """2.20 s one-shot: the hook-set (rod snaps up 0.00-0.22 s, body rocks back),
    the rod butt comes in across the belly and the left hand goes to the crank
    (IK onto the reel knob) and cranks 5 uneven turns while the rod bucks
    against the fish; ends with the rod high and the fish at the bank."""
    L = 2.20
    ra = H("right_arm")
    k = {
        "torso": [(0.0, H("torso")), (0.16, (-6.0, -3.0, 0.0)), (0.40, (-4.0, -2.0, 0.0)), (1.0, (-1.0, 1.0, 0.0)),
                  (1.6, (-3.0, -1.0, 0.5)), (L, (-2.0, 0.0, 0.0))],
        "head": [(0.0, H("head")), (0.10, (14.0, 0.0, 0.0)), (0.30, (13.0, 2.0, 0.0)), (1.2, (15.0, -2.0, 0.0)),
                 (L, (16.0, 0.0, 0.0))],
        "right_arm": [(0.0, ra), (0.14, (-58.0, -20.0, -4.0)), (0.24, (-60.0, -24.0, -6.0)), (0.50, add(REEL_ARM, (2, 0, 0))),
                      (0.90, add(REEL_ARM, (-6, 0, 0))), (1.25, add(REEL_ARM, (3, 0, 0))), (1.60, add(REEL_ARM, (-7, 0, 0))),
                      (1.95, add(REEL_ARM, (1, 0, 0))), (L, add(REEL_ARM, (-5, 0, 0)))],
        "right_forearm": [(0.0, H("right_forearm")), (0.14, (-40.0, 0, 0)), (0.50, REEL_FORE),
                          (1.25, add(REEL_FORE, (-3, 0, 0))), (L, REEL_FORE)],
        "right_item": [(0.0, H("right_item")), (0.10, (-4.0, 0, -12.0)), (0.30, (-22.0, 0, -22.0)), (0.50, REEL_ITEM),
                       (0.9, add(REEL_ITEM, (3, 0, 0))), (1.6, add(REEL_ITEM, (-3, 0, 0))), (L, REEL_ITEM)],
        "left_arm": [(0.0, H("left_arm")), (0.20, (-40.0, 14.0, -2.0)), (0.42, (-70.0, 30.0, 10.0)), (L, (-70.0, 30.0, 10.0))],
        "left_forearm": [(0.0, H("left_forearm")), (0.20, (-60.0, 0, 0)), (0.42, (-40.0, 0, 0)), (L, (-40.0, 0, 0))],
        "root_pos": [(0.0, (0, 0, 0)), (0.16, (0, 0.2, 0.6)), (0.6, (0, 0, 0.3)), (L, (0, 0, 0.2))],
    }
    turns = [(0.42, 0.0), (0.72, 1.0), (0.99, 2.0), (1.32, 3.0), (1.61, 4.0), (1.95, 5.0), (L, 5.2)]

    def ik(t, w, rodm):
        if t < 0.18:
            return None, 0.0
        turn = sample_keys(turns, t) if t > 0.42 else 0.0
        return crank_point(rodm, turn), smooth((t - 0.18) / 0.26)
    return Clip("FISHER_STRIKE_REEL", L, False, k, {"strike": 0.14, "reel_end": 1.95}, left_ik=ik)


FISH_HANG = 14.0                    # px of line from the rod tip down to the hooked fish while it dangles
LAND_UP, LAND_SWING, LAND_GRAB = 0.45, 1.75, 2.05


def land_fish():
    """2.50 s one-shot (owner 27 Sep: "the fish must actually hang under and flap"):
    the rod lifts the fish out of the water (0-0.45 s) and it DANGLES on the line
    under the rod tip, flapping and swinging (0.45-1.75 s) while he holds the rod
    high and watches it; then he swings it in (1.75 s), grabs it with the left
    hand at 2.05 s, draws it to the chest and holds it up."""
    L = 2.50
    ra = add(REEL_ARM, (-5, 0, 0))
    k = {
        "torso": [(0.0, (-2.0, 0.0, 0.0)), (0.40, (-7.0, 2.0, 0.0)), (1.0, (-5.0, 3.0, 0.0)), (1.6, (-6.0, 2.0, 0.0)),
                  (2.05, (5.0, 6.0, 1.0)), (2.3, (2.0, 4.0, 0.0)), (L, (1.0, 3.0, 0.0))],
        "head": [(0.0, (16.0, 0.0, 0.0)), (0.40, (2.0, 2.0, 0.0)), (0.8, (0.0, 5.0, 0.0)), (1.2, (1.0, 1.0, 1.0)),
                 (1.6, (0.0, 4.0, 0.0)), (1.95, (8.0, 8.0, 0.0)), (2.3, (14.0, 6.0, 0.0)), (L, (12.0, 4.0, 0.0))],
        "right_arm": [(0.0, ra), (0.40, (-70.0, -15.0, -10.0)), (0.75, (-67.0, -15.0, -10.0)),
                      (1.10, (-71.0, -15.0, -10.0)), (1.45, (-68.0, -15.0, -10.0)), (1.75, (-69.0, -16.0, -10.0)),
                      (2.05, (-47.0, -24.0, -6.0)), (L, (-44.0, -26.0, -8.0))],
        "right_forearm": [(0.0, REEL_FORE), (0.40, (-30.0, 0, 0)), (1.75, (-31.0, 0, 0)), (2.05, (-52.0, 0, 0)),
                          (L, (-50.0, 0, 0))],
        "right_item": [(0.0, REEL_ITEM), (0.40, (10.0, 0, -10.0)), (1.75, (9.0, 0, -10.0)), (2.05, (-20.0, 0, -22.0)),
                       (L, (-22.0, 0, -25.0))],
        "left_arm": [(0.0, (-70.0, 30.0, 10.0)), (0.30, (-70.0, 26.0, 6.0)), (0.65, (-52.0, 22.0, 0.0)),
                     (1.60, (-54.0, 22.0, 0.0)), (2.30, (-66.0, 16.0, -2.0)), (L, (-62.0, 12.0, -2.0))],
        "left_forearm": [(0.0, (-40.0, 0, 0)), (0.30, (-36.0, 0, 0)), (0.65, (-70.0, 0, 0)), (1.60, (-66.0, 0, 0)),
                         (2.30, (-78.0, 0, 0)), (L, (-84.0, 0, 0))],
        "root_pos": [(0.0, (0, 0, 0.2)), (0.4, (0, 0.2, 0.6)), (1.7, (0, 0.1, 0.4)), (2.05, (-0.2, 0, -0.2)), (L, (0, 0, 0))],
    }

    def ik(t, w, rodm):
        if t < 0.62:
            return crank_point(rodm, 5.2), 1.0 - smooth((t - 0.30) / 0.30)
        if 1.62 <= t < 2.26:
            return np.array(FISH_GRAB), smooth((t - 1.62) / 0.40) * (1.0 - smooth((t - 2.12) / 0.14))
        return None, 0.0
    return Clip("FISHER_LAND_FISH", L, False, k, {"up": LAND_UP, "swing": LAND_SWING, "grab": LAND_GRAB}, left_ik=ik)


def flap(t):
    """Hooked-fish wriggle (deg): (yaw, roll, swing kick) -- fast bursts that die down, never regular."""
    burst = 0.55 + 0.45 * math.sin(t * 2.3) * math.sin(t * 1.1 + 0.7)
    yaw = 38.0 * burst * math.sin(t * 34.0) + 12.0 * math.sin(t * 13.0)
    roll = 20.0 * burst * math.sin(t * 27.0 + 1.3)
    kick = 0.9 * burst * math.sin(t * 17.0)
    return yaw, roll, kick


def dangle(t, tip):
    """Fish under the rod tip on FISH_HANG px of line: damped pendulum from the lift + flapping kicks."""
    u = max(0.0, t - LAND_UP)
    ax = 22.0 * math.exp(-1.6 * u) * math.sin(5.2 * u + 0.4) + 5.0 * flap(t)[2]
    az = 14.0 * math.exp(-1.2 * u) * math.cos(4.1 * u) + 3.0 * flap(t + 0.37)[2]
    ax, az = math.radians(ax), math.radians(az)
    return np.asarray(tip, float) + FISH_HANG * np.array([math.sin(ax), math.cos(ax) * math.cos(az), -math.sin(az)])


def fish_point(t, w, rodm, water):
    """Preview/runtime twin: where the hooked fish is during FISHER_LAND_FISH (model px, +Y down).
    Out of the water on the line (0-0.45 s), dangling and flapping under the tip
    (0.45-1.75 s), swung in to FISH_GRAB (1.75-2.05 s), then in the left palm."""
    tip = mcrig.xform(rodm, ROD_TIP)
    if t >= LAND_GRAB:
        return mcrig.xform(w["left_forearm"], (0, 7, 0))
    if t < LAND_UP:
        s = smooth(t / LAND_UP)
        a = np.asarray(water, float)
        b = dangle(LAND_UP, tip)
        p = a + (b - a) * s
        p[1] -= 6.0 * math.sin(math.pi * s)
        return p
    d = dangle(t, tip)
    if t < LAND_SWING:
        return d
    s = smooth((t - LAND_SWING) / (LAND_GRAB - LAND_SWING))
    return d + (np.array(FISH_GRAB) - d) * s


def all_clips():
    return [cast_v3(), wait(), wait(2), strike_reel(), land_fish()]


# --------------------------------------------------------------------------- props on the line
CRANK_AXLE = (5.0, 9.5, 4.9)       # item px: the knob orbits the spool axle on the -X (body) side
CRANK_R = 1.3


def crank_point(rodm, turn):
    a = 2 * math.pi * turn
    return mcrig.xform(rodm, (CRANK_AXLE[0] - 0.6, CRANK_AXLE[1] + CRANK_R * math.sin(a),
                              CRANK_AXLE[2] + CRANK_R * math.cos(a)))


def smooth(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


def solve_left_hand(ch, target, prev=None):
    """left_arm (x,y,z) + elbow so the palm (forearm-local (0,6,0)) reaches `target`."""
    base = dict(ch)
    keyed = [*ch["left_arm"]["rot"], ch["left_forearm"]["rot"][0]]
    seed = list(prev) if prev is not None else keyed
    tgt = np.asarray(target, float)

    def f(p):
        c = dict(base)
        c["left_arm"] = {"rot": (p[0], p[1], p[2])}
        c["left_forearm"] = {"rot": (min(-2.0, p[3]), 0.0, 0.0)}
        w = mcrig.pose_matrices(seated_channels(c))
        d = mcrig.xform(w["left_forearm"], (0, 6, 0)) - tgt
        reg = 0.002 * sum((p[i] - keyed[i]) ** 2 for i in range(4)) + 0.004 * sum((p[i] - seed[i]) ** 2 for i in range(4))
        bend = 0.02 * max(0.0, p[3] + 10.0) ** 2 + 0.02 * max(0.0, -135.0 - p[3]) ** 2
        return float(d @ d) + reg + bend
    best, _ = mcrig.nelder_mead(f, seed, [6.0, 6.0, 6.0, 6.0], iters=500, tol=1e-6)
    best[3] = min(-2.0, best[3])
    return tuple(float(v) for v in best)


# --------------------------------------------------------------------------- rod frames
def seated_channels(ch):
    """Add the seated legs + the 4 px seat drop the runtime applies (preview/checks only)."""
    out = dict(ch)
    r = out.get("root", {"rot": (0, 0, 0)})
    pos = r.get("pos", (0, 0, 0))
    out["root"] = {"rot": r.get("rot", (0, 0, 0)), "pos": (pos[0], pos[1] - SEAT_DROP, pos[2])}
    out["right_leg"] = {"rot": (-90.0, 4.0, 0.0)}
    out["left_leg"] = {"rot": (-90.0, -4.0, 0.0)}
    out["right_shin"] = {"rot": (90.0, 0.0, 0.0)}
    out["left_shin"] = {"rot": (90.0, 0.0, 0.0)}
    return out


def rod_matrix(world):
    """Item frame of the held rod: runtime ItemInHandLayer + our display transform."""
    (rxd, ryd, rzd), (tx, ty, tz), s = ROD_DISPLAY
    fw = world["right_item"] @ mcrig.T(0, -10, 0) if "right_item" in world else world["right_forearm"] @ mcrig.T(0, -4, 0)
    m = fw @ mcrig.mat4(mcrig.rx(-90 * D)) @ mcrig.mat4(mcrig.ry(180 * D)) @ mcrig.T(1, 2, -10)
    r = mcrig.rx(rxd * D) @ mcrig.ry(ryd * D) @ mcrig.rz(rzd * D)
    return m @ mcrig.T(tx, ty, tz) @ mcrig.mat4(r, s=s) @ mcrig.T(-8, -8, -8)


def rod_points(ch):
    w = mcrig.pose_matrices(seated_channels(ch))
    m = rod_matrix(w)
    return {n: mcrig.xform(m, p) for n, p in (("butt", ROD_BUTT), ("grip", ROD_GRIP), ("tip", ROD_TIP))}, w


def elevation(pts):
    d = pts["tip"] - pts["butt"]
    return math.degrees(math.atan2(-d[1], -d[2]))   # 0 = level forward, 90 = straight up, >90 = leaning back


if __name__ == "__main__":
    for clip in all_clips():
        print(clip.const, clip.length)
        n = 12
        for i in range(n + 1):
            t = clip.length * i / n
            pts, w = rod_points(clip.channels(t))
            tip = pts["tip"]
            lh = mcrig.xform(w["left_forearm"], (0, 6, 0))
            print(f"  t={t:4.2f} elev={elevation(pts):6.1f} tip=({tip[0]:5.1f},{tip[1]:6.1f},{tip[2]:6.1f}) "
                  f"grip=({pts['grip'][0]:5.1f},{pts['grip'][1]:5.1f},{pts['grip'][2]:5.1f}) "
                  f"lhand=({lh[0]:5.1f},{lh[1]:5.1f},{lh[2]:5.1f})")
