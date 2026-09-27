"""Motion kit: small parametric building blocks for settler clips (numpy only).

Everything returns or edits CHANNEL dicts in the export convention
    ch = {bone: {"rot": (x, y, z) degrees, "pos": (x, y, z) posVec px (y up)}}
so layers can be summed inside a clip script's solve(t):

    import motionkit as mk
    L = 1.0
    breathe = mk.breathing(amp=0.3, period=L)
    swing = mk.strike(windup_t=0.37, hit_t=0.55, recover_t=0.9, length=L,
                      arc={"right_arm": ((-40, 8, 6), (-95, 70, 15), (-85, -60, -8))},
                      overshoot=0.08)
    def solve(t):
        ch = mk.add({}, breathe(t), swing(t))
        mk.planted_feet_ik(ch)                   # legs + knees from the root pose
        return ch
    solve = mk.loop_blend(solve, L)             # seamless loop closure

Layers are plain functions t -> channels; `add` sums them. Nothing here
touches bpy, so the same code runs in Blender (hsrig.bake) and in tests.
Time is seconds; a looping layer wraps t by its own `length`.
"""

from __future__ import annotations

import math

import numpy as np

import mcrig

# --------------------------------------------------------------------------- easing
def _clamp(u):
    return 0.0 if u < 0 else 1.0 if u > 1 else u


EASE = {
    "linear": lambda u: u,
    "smooth": lambda u: u * u * (3 - 2 * u),                 # ease in-out
    "in": lambda u: u ** 3,                                   # accelerate (strike)
    "in2": lambda u: u * u,
    "out": lambda u: 1 - (1 - u) ** 3,                        # decelerate (settle)
    "out2": lambda u: 1 - (1 - u) ** 2,
    "sine": lambda u: 0.5 - 0.5 * math.cos(math.pi * u),
    "snap": lambda u: 1 - (1 - u) ** 5,                       # fast out of a hold
    "hold": lambda u: 0.0,
}
# Blender key presets for hsrig.key_curve: (interp, easing) applied to the segment LEAVING a key
KEY = {"accel": ("CUBIC", "EASE_IN"), "decel": ("SINE", "EASE_OUT"),
       "smooth": ("BEZIER", "AUTO"), "linear": ("LINEAR", "AUTO"),
       "whip": ("QUART", "EASE_IN"), "hold": ("CONSTANT", "AUTO")}


def ease(u, kind="smooth"):
    return EASE[kind](_clamp(u))


def lerp(a, b, u):
    return tuple(x + (y - x) * u for x, y in zip(a, b))


# --------------------------------------------------------------------------- channels
def add(ch, *layers):
    """Sum layer channel dicts into ch (in place) and return it."""
    for layer in layers:
        for bone, kinds in layer.items():
            dst = ch.setdefault(bone, {})
            for k, v in kinds.items():
                cur = dst.get(k, (0.0, 0.0, 0.0))
                dst[k] = tuple(a + b for a, b in zip(cur, v))
    return ch


def track(keys, t, length=None, default="smooth"):
    """Piecewise pose track. keys = [(t, (x,y,z)[, ease])]; ease shapes the segment ARRIVING at the key."""
    if length:
        t %= length
    if t <= keys[0][0]:
        return tuple(keys[0][1])
    for (t0, v0, *_), (t1, v1, *e) in zip(keys, keys[1:]):
        if t <= t1:
            u = (t - t0) / max(t1 - t0, 1e-9)
            return lerp(v0, v1, ease(u, e[0] if e else default))
    return tuple(keys[-1][1])


# --------------------------------------------------------------------------- layers
def breathing(amp=0.3, period=1.0, phase=0.0):
    """Chest rise: torso lifts `amp` px and pitches back slightly; shoulders ride it."""
    def layer(t):
        s = 0.5 - 0.5 * math.cos(2 * math.pi * (t / period + phase))
        return {"torso": {"pos": (0, amp * s, 0), "rot": (-1.2 * amp * s, 0, 0)},
                "head": {"rot": (0.8 * amp * s, 0, 0)},
                "right_arm": {"rot": (0, 0, 1.5 * amp * s)},
                "left_arm": {"rot": (0, 0, -1.5 * amp * s)}}
    return layer


def weight_shift(side=1, amount=0.6, t_in=0.0, t_hold=0.3, t_out=0.7, t_end=1.0, length=None):
    """Hips move `amount` px toward `side` (+1 = settler's left), spine tilts back over them.

    Eases in over [t_in, t_hold], holds, eases back over [t_out, t_end].
    Pair with planted_feet_ik() so the feet stay put while the pelvis travels."""
    def layer(t):
        if length:
            t %= length
        if t < t_hold:
            w = ease((t - t_in) / max(t_hold - t_in, 1e-9), "smooth")
        elif t < t_out:
            w = 1.0
        else:
            w = 1 - ease((t - t_out) / max(t_end - t_out, 1e-9), "smooth")
        x = side * amount * w
        return {"root": {"pos": (x, -0.25 * abs(x), 0)},
                "torso": {"rot": (0, 0, -side * 3.0 * amount * w)},
                "head": {"rot": (0, 0, side * 2.0 * amount * w)}}
    return layer


def look_around(timing, length=None, neck_share=0.75):
    """Head (and a little spine) glance track. timing = [(t, yaw, pitch)], smooth holds between.
    Positive yaw turns toward the settler's left; positive pitch looks down."""
    keys = [(t, (p, y, 0.0)) for t, y, p in timing]

    def layer(t):
        x, y, _ = track(keys, t, length, "sine")
        return {"head": {"rot": (x * neck_share, y * neck_share, 0)},
                "torso": {"rot": (x * (1 - neck_share) * 0.5, y * (1 - neck_share), 0)}}
    return layer


def strike(windup_t, hit_t, recover_t, arc, length=1.0, overshoot=0.08, hip_lead=0.06,
           hold=0.03, start_t=0.0, hips=(4.0, 16.0, -14.0), spine=(0.0, 22.0, -18.0)):
    """Anticipation -> accelerate -> contact hold -> recoil -> settle, hips leading.

    arc: {bone: (rest_rot, windup_rot, hit_rot)} degrees (any arm/forearm/head bone).
    The hit pose is reached EXACTLY at hit_t (keep your contact/sound tick there).
    The swing into it accelerates (cubic ease-in: fastest at contact); the pose is
    held `hold` s, recoils back toward the wind-up by `overshoot` of the swing, then
    settles to rest by recover_t. Root yaw (`hips` = rest, windup, hit) and torso
    yaw (`spine`) run the same beats but `hip_lead` s EARLIER (hips) and
    hip_lead/2 earlier (spine): the kinetic chain."""
    def beats(rest, wind, hit, lead):
        rec = tuple(h + (w - h) * overshoot for w, h in zip(wind, hit))
        return [(start_t, rest), (windup_t - lead, wind, "smooth"), (hit_t - lead, hit, "in"),
                (hit_t - lead + hold, hit, "hold"), (hit_t - lead + hold + 0.05, rec, "out2"),
                (recover_t - lead * 0.5, rest, "smooth"), (length, rest, "smooth")]

    tracks = {b: beats(*v, 0.0) for b, v in arc.items()}
    root = beats((0, hips[0], 0), (0, hips[1], 0), (0, hips[2], 0), hip_lead)
    torso = beats((0, spine[0], 0), (0, spine[1], 0), (0, spine[2], 0), hip_lead * 0.5)

    def layer(t):
        t %= length
        out = {b: {"rot": track(k, t)} for b, k in tracks.items()}
        add(out, {"root": {"rot": track(root, t)}, "torso": {"rot": track(torso, t)}})
        return out
    return layer


def carry_layer(load_kg, gait=True):
    """Load posture for `load_kg` (0 = nothing, ~25 = full sack): forward wedge, lowered
    hips, stiffer arms. Saturates smoothly; use as an additive layer over a walk/idle."""
    k = 1 - math.exp(-max(load_kg, 0.0) / 15.0)
    return lambda t: {"torso": {"rot": (10.0 * k, 0, 0), "pos": (0, -0.6 * k, 0)},
                      "root": {"pos": (0, -0.5 * k if gait else 0, 0)},
                      "head": {"rot": (-7.0 * k, 0, 0)},
                      "cloak": {"rot": (4.0 * k, 0, 0)}}


# --------------------------------------------------------------------------- IK helpers
FOOT_R = (-2.9, 24.0, 0.8)      # default relaxed stance, model px (ground y = 24)
FOOT_L = (2.9, 24.0, -0.8)


def planted_feet_ik(ch, feet=(FOOT_R, FOOT_L), knee_dir=(0.0, 0.0, -1.0), _prev={}):
    """Solve right/left leg + shin so the soles stay on `feet` (model px) for the
    root pose already in ch. Knees bend instead of the feet sliding."""
    world = mcrig.pose_matrices(ch)
    inv_root = np.linalg.inv(world["root"])
    for side, foot, hip_x in (("right", feet[0], -2.6), ("left", feet[1], 2.6)):
        fl = mcrig.xform(inv_root, foot)
        pole = inv_root[:3, :3] @ (np.array(knee_dir) + np.array([0.12 * np.sign(hip_x), 0, 0]))
        r, flex, _ = mcrig.two_bone(np.array([hip_x, -12.0, 0.0]), fl, mcrig.THIGH,
                                    mcrig.SOLE_Y - mcrig.THIGH, pole, +1)
        rot = _euler_near(r, _prev.get(side))
        _prev[side] = rot
        ch[side + "_leg"] = {"rot": rot}
        ch[side + "_shin"] = {"rot": (math.degrees(flex), 0.0, 0.0)}
    return ch


def arm_ik(ch, side, hand_target, pole=(0.7, 0.6, 0.5), grip_len=6.0, _prev={}):
    """Two-bone arm to put the hand (default: limb end, arm-local y=10) on a model-space point.
    pole = where the elbow points, in torso space (x toward the arm's own side is mirrored)."""
    world = mcrig.pose_matrices(ch)
    sign = -1 if side == "right" else 1
    tl = mcrig.xform(np.linalg.inv(world["torso"]), hand_target)
    p = np.array(pole, float) * np.array([sign, 1, 1])
    r, flex, _ = mcrig.two_bone(np.array([6.0 * sign, -10.0, 0.0]), tl, mcrig.UPPER_ARM,
                                grip_len, p, -1)
    ch[side + "_arm"] = {"rot": _euler_near(r, _prev.get(side))}
    _prev[side] = ch[side + "_arm"]["rot"]
    ch[side + "_forearm"] = {"rot": (math.degrees(flex), 0.0, 0.0)}
    return ch


def _euler_near(m3, prev):
    x, y, z = mcrig.euler_from_matrix(m3)
    a = [math.degrees(x), math.degrees(y), math.degrees(z)]
    b = [a[0] + 180.0, 180.0 - a[1], a[2] + 180.0]
    if prev is None:
        return tuple(a)
    def near(v):
        return [c + 360.0 * round((p - c) / 360.0) for p, c in zip(prev, v)]
    a, b = near(a), near(b)
    da = sum((p - c) ** 2 for p, c in zip(prev, a))
    db = sum((p - c) ** 2 for p, c in zip(prev, b))
    return tuple(a if da <= db else b)


# --------------------------------------------------------------------------- loops
def loop_blend(solve, length, blend=0.12):
    """Seamless loop closure: over the last `blend` seconds, distribute the
    end-to-start mismatch so solve(length) == solve(0) with no pop."""
    start = solve(0.0)
    end = solve(length)

    def blended(t):
        t %= length
        ch = solve(t)
        w = ease((t - (length - blend)) / blend, "smooth") if t > length - blend else 0.0
        if w <= 0:
            return ch
        for bone in set(start) | set(end):
            for kind in ("rot", "pos"):
                s = start.get(bone, {}).get(kind)
                e = end.get(bone, {}).get(kind)
                if s is None or e is None:
                    continue
                cur = ch.setdefault(bone, {}).get(kind, (0, 0, 0))
                ch[bone][kind] = tuple(c + (a - b) * w for c, a, b in zip(cur, s, e))
        return ch
    return blended
