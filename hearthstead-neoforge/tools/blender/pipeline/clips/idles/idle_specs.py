"""Per-job idle overlays for idlekit: posture numbers + a few keyed gesture curves.

Curves (cyclic, period = clip length; gesture curves are OFFSETS from the rest pose):
  weight (-1 = on right foot .. +1 = on left foot), gaze_yaw / gaze_pitch (WORLD deg,
  +yaw = toward the settler's right, +pitch = looking down), torso_x/y/z, head_x/y/z,
  root_yaw, root_roll, root_px, root_pz, dip (px down), sigh (extra breath 0..1),
  arm_r_x/y/z, elbow_r, arm_l_x/y/z, elbow_l (flex negative), cloak_x,
  foot_r / foot_l (vector offsets px, y up).
  reach[side] = two-bone IK hand target: space torso|head|root|model|hand_r|item_r,
  p = [(t, (x,y,z))], w = [(t, 0..1)] blend from FK.
Key tuples: (t, value[, ease]); ease shapes the segment LEAVING the key:
  s bezier, a accelerate, d/D decelerate, l linear, w whip, h hold, io sine in-out.

Local reference points (px). Torso: waist pivot, y -12 (top) .. 0, front face z -2.5.
Head: neck pivot, y -8..0, face z -4. Limb blocks are 4 px thick, so a hand touching a
surface has its limb-end target ~2 px off that surface. (QA 2026-09-26: pushing these
targets further out was tried and reverted -- many sit at the edge of the 9.5 px reach
and the arm locks straight; face-touch fidgets overlap the face by ~3 px by design.)

Every variant starts and ends in its base clip's rest pose (same weight/gaze/fidget at
t = 0), so the engine can cut base <-> variant on a loop boundary.
"""

import copy

S = {}


# --------------------------------------------------------------------------- helpers
def merge(*parts):
    """Combine gesture parts {'g': {curve: keys}, 'reach': {side: {...}}} (time-disjoint)."""
    out = {"g": {}, "reach": {}}
    for p in parts:
        for k, ks in p.get("g", {}).items():
            out["g"].setdefault(k, []).extend(ks)
        for side, r in p.get("reach", {}).items():
            # one IK slot per (arm, space); later slots blend on top of earlier ones
            slot = side
            while slot in out["reach"] and out["reach"][slot]["space"] != r["space"]:
                slot += "+"
            d = out["reach"].setdefault(slot, {"space": r["space"], "p": [], "w": []})
            d["p"].extend(r["p"])
            d["w"].extend(r["w"])
            if "pole" in r:
                d["pole"] = r["pole"]
            if "grip" in r:
                d["grip"] = r["grip"]
    for k in out["g"]:
        out["g"][k] = _dedupe(out["g"][k])
    for r in out["reach"].values():
        r["p"], r["w"] = _dedupe(r["p"]), _dedupe(r["w"])
    return out


def _dedupe(keys):
    ks = sorted(keys, key=lambda k: k[0])
    out = []
    for k in ks:
        if out and abs(out[-1][0] - k[0]) < 1e-4:
            out[-1] = k
        else:
            out.append(k)
    return out


def pulse(name, t0, t1, t2, t3, v, ant=0.0, over=0.0, e_in="s", e_out="s"):
    """0 -> (optional anticipation) -> v by t1, hold to t2, back to 0 by t3 (optional overshoot)."""
    k = [(t0, 0.0, e_in if not ant else "s")]
    if ant:
        k.append((t0 + 0.35 * (t1 - t0), -ant * v, e_in))
    k += [(t1, v, "s"), (t2, v, e_out)]
    if over:
        k.append((t3 - 0.3 * (t3 - t2), -over * v, "s"))
    k.append((t3, 0.0))
    return {"g": {name: k}}


def pulses(t0, t1, t2, t3, ant=0.0, over=0.0, **vals):
    return merge(*[pulse(n, t0, t1, t2, t3, v, ant, over) for n, v in vals.items()])


def keys(**curves):
    """Raw offset curves: keys(arm_r_x=[(t, v), ...])."""
    return {"g": {k: list(v) for k, v in curves.items()}}


def reach(side, space, pts, t_in, t_out, ramp=0.35, ramp_out=None, pole=None, grip=None):
    """IK the hand to `pts` [(t, (x,y,z))] in `space`; blend in over `ramp` s before t_in,
    out over `ramp_out` s after t_out. The first/last point is held across the ramps."""
    ro = ramp if ramp_out is None else ramp_out
    p = [(t_in - ramp, pts[0][1])] + list(pts) + [(t_out + ro, pts[-1][1])]
    w = [(t_in - ramp, 0.0, "s"), (t_in, 1.0, "s"), (t_out, 1.0, "s"), (t_out + ro, 0.0, "s")]
    r = {"space": space, "p": p, "w": w}
    if pole:
        r["pole"] = pole
    if grip:
        r["grip"] = grip
    return {"reach": {side: r}}


def hold_reach(side, space, pts, L, pole=None, grip=None):
    """Hand kept on an IK target the whole clip (pts may move)."""
    r = {"space": space, "p": list(pts), "w": [(0, 1.0), (L, 1.0)]}
    if pole:
        r["pole"] = pole
    if grip:
        r["grip"] = grip
    return {"reach": {side: r}}


def taps(name, t0, n, period, v, e="s"):
    """n quick out-and-back taps on an offset curve."""
    k = [(t0, 0.0)]
    for i in range(n):
        k += [(t0 + (i + 0.4) * period, v, "D"), (t0 + (i + 1) * period, 0.0, e)]
    return {"g": {name: k}}


def osc_pts(p0, amp, t0, n, period):
    """Points oscillating around p0 by +/- amp (vector), n cycles (rubbing, polishing, strokes)."""
    out = []
    for i in range(n):
        out += [(t0 + (i + 0.25) * period, tuple(a + d for a, d in zip(p0, amp))),
                (t0 + (i + 0.75) * period, tuple(a - d for a, d in zip(p0, amp)))]
    out.append((t0 + n * period, tuple(p0)))
    return out


def circle_pts(c, r, t0, n, period, steps=6, plane="xy"):
    import math
    out = []
    for i in range(n * steps + 1):
        a = 2 * math.pi * i / steps
        dx, dy = r * math.cos(a), r * math.sin(a)
        if plane == "xy":
            p = (c[0] + dx, c[1] + dy, c[2])
        else:   # xz
            p = (c[0] + dx, c[1], c[2] + dy)
        out.append((t0 + i * period / steps, p))
    return out


def gaze(L, rest, pts, which):
    """Absolute gaze keys that start/end on the rest value. rest = (yaw, pitch)."""
    i = 0 if which == "yaw" else 1
    k = [(0, rest[i])] + [(p[0], p[1 + i], *p[3:]) for p in pts] + [(L, rest[i])]
    return _dedupe(k)


class Job:
    """A job's posture (shared by its base clip and variants) and its rest values."""

    def __init__(self, **posture):
        self.p = posture
        self.w0 = posture.pop("w0", -0.5)
        self.g0 = posture.pop("g0", (0.0, 3.0))      # rest gaze (yaw, pitch)
        self.always = posture.pop("always", {})      # parts present in every clip

    def clip(self, stem, length, *parts, weight=None, looks=(), note="", **over):
        L = length
        s = copy.deepcopy(self.p)
        s.update(over)
        m = merge(copy.deepcopy(self.always) if not callable(self.always) else self.always(L),
                  *parts)
        s["length"] = L
        s["g"] = m["g"]
        s["reach"] = m["reach"]
        wk = weight or [(0.45 * L, -self.w0 * 0.9)]
        s["weight"] = _dedupe([(0, self.w0)] + list(wk) + [(L, self.w0)])
        s["gaze_yaw"] = gaze(L, self.g0, looks, "yaw")
        s["gaze_pitch"] = gaze(L, self.g0, looks, "pitch")
        s["note"] = note
        S[stem] = s
        return s


# =========================================================================== base IDLE
# Layered under EAT (head/arms masked there) - kept subtle: glance <= 8 deg.
BASE = Job(texture="settler_none.png", seed=3, feet=((-2.9, 0.5), (2.9, -0.3)), knee=5.0,
           lean=1.0, w0=-0.55, g0=(0.0, 2.0))
BASE.clip("idle", 4.0,
          keys(elbow_r=[(2.5, 0), (2.8, -4, "d"), (3.2, -3.5), (3.7, 0)],
               arm_l_z=[(1.0, 0), (1.4, -1.0), (2.2, -0.8), (2.6, 0)]),
          weight=[(0.55, -0.6), (1.45, 0.62), (2.55, 0.58), (3.45, -0.5)],
          looks=[(0.95, 0.5, 2.0), (1.35, -7.5, 1.0), (1.95, -7.0, 1.2), (2.35, -0.5, 2.0),
                 (2.85, 4.5, 3.0), (3.35, 4.0, 3.0), (3.8, 0.0, 2.0)],
          note="Base idle: breath, slow weight shift over planted feet, one small glance each "
               "way, hanging arms with pendulum lag. Subtle: IDLE is layered under EAT.")
# v2: rubs the back of the neck and rolls the head.
BASE.clip("idle__v2", 6.0,
          reach("r", "head", [(1.4, (-2.5, -1.5, 6.2)), (1.8, (-1.0, -2.2, 6.2)),
                              (2.2, (-2.8, -1.4, 6.2)), (2.6, (-1.0, -2.2, 6.2)),
                              (3.0, (-2.5, -1.6, 6.2))], 1.4, 3.0, ramp=0.55, ramp_out=0.6,
                pole=(1.0, 0.1, -0.2)),
          keys(head_x=[(1.2, 0), (1.7, 7, "s"), (2.6, 8), (3.0, -6, "s"), (3.6, -5), (4.2, 0)],
               head_z=[(2.6, 0), (3.0, 5), (3.5, -4), (4.0, 0)],
               sigh=[(2.9, 0), (3.3, 0.8), (4.3, 0)],
               torso_x=[(1.2, 0), (1.7, 3), (3.0, 2), (3.6, -1.5), (4.3, 0)]),
          weight=[(1.0, -0.6), (2.0, 0.55), (4.6, 0.55)],
          looks=[(1.2, 0.0, 2.0), (3.6, -6.0, -4.0), (4.6, -3.0, 1.0)],
          note="Variant: rubs the back of the neck, stretches the neck back with a sigh.")
# v3: the long lazy stretch with a yawn.
BASE.clip("idle__v3", 6.5,
          pulses(1.0, 2.1, 3.1, 4.2, ant=0.12, over=0.06, arm_r_x=-150, arm_l_x=-150),
          pulses(1.0, 2.1, 3.1, 4.2, arm_r_z=-12, arm_l_z=12, elbow_r=-8, elbow_l=-8),
          pulses(1.0, 2.1, 3.1, 4.2, ant=0.2, torso_x=-9, dip=-0.15),
          reach("l", "head", [(4.4, (1.5, -1.5, -7.0)), (5.1, (1.2, -1.8, -7.0))], 4.4, 5.1,
                ramp=0.35, ramp_out=0.5),
          keys(sigh=[(1.0, 0), (2.3, 1.2), (3.3, 1.1), (4.4, 0.2), (5.2, 0)],
               head_x=[(4.3, 0), (4.8, -4), (5.3, 2), (5.8, 0)]),
          weight=[(0.8, -0.6), (2.0, 0.0), (3.6, 0.0), (5.2, 0.5)],
          looks=[(1.2, 0.0, 2.0), (2.1, 0.0, -14.0), (3.1, 0.0, -12.0), (4.4, 2.0, 1.0)],
          note="Variant: slow overhead stretch on a big inhale, then a yawn behind the hand.")


# =========================================================================== FARMER / HERDER (hoe)
FARM = Job(texture="settler_farmer.png", item="hoe", seed=11,
           feet=((-3.0, -0.9), (3.0, 0.9)), knee=6.0, lean=5.0, w0=-0.4, g0=(-4.0, 8.0),
           arm_r=(-20.0, 0.0, 8.0, -115.0), hang_r=0.0, arm_l=(-2.0, 0.0, -4.0, -12.0))
FARM.clip("idle_farmer", 5.5,
          pulses(1.9, 2.8, 3.7, 4.6, ant=0.15, over=0.08, torso_x=-7.0),
          reach("l", "head", [(2.6, (1.6, -4.2, -6.3)), (3.8, (1.6, -4.4, -6.3))], 2.6, 3.8,
                ramp=0.55, ramp_out=0.6),
          keys(head_z=[(2.8, 0), (3.1, 3.5), (3.7, 3.0), (4.3, 0)],
               dip=[(1.9, 0), (2.25, 0.25), (2.8, -0.1), (4.6, 0)]),
          weight=[(1.2, -0.5), (2.8, 0.45), (3.9, 0.4), (4.8, -0.35)],
          looks=[(1.2, -2.0, 9.0), (2.8, 5.0, -18.0), (3.2, 4.0, -20.0), (3.7, 6.0, -19.0),
                 (4.4, -3.0, 5.0)],
          note="FARMER/HERDER: hoe on the shoulder; straightens, shades the eyes and squints at the "
               "sky, eases back.")
# v2: kicks a clod.
FARM.clip("idle_farmer__v2", 5.0,
          keys(foot_r=[(1.4, (0, 0, 0)), (1.75, (0.2, 1.2, 0.9), "a"), (2.0, (-0.1, 0.9, -2.6), "D"),
                       (2.25, (0, 0.5, -1.8)), (2.55, (0, 0, -0.4)), (3.0, (0, 0, 0))],
               arm_r_x=[(1.5, 0), (2.0, 3), (2.6, 0)], torso_x=[(1.2, 0), (1.9, 3), (2.8, 1), (3.4, 0)]),
          weight=[(1.0, 0.8), (2.6, 0.75), (3.2, -0.2)],
          looks=[(1.1, -4.0, 20.0), (2.0, -2.0, 26.0), (2.6, 3.0, 22.0), (3.3, -6.0, 9.0)],
          note="FARMER variant: weight onto the left foot, scuffs a clod off with the right boot "
               "and watches it roll.")
# v3 (break): a drink from the waterskin.
FARM.clip("idle_farmer__v3", 7.0,
          reach("l", "head", [(1.6, (1.8, -1.5, -7.2)), (2.4, (1.5, -1.8, -6.8)),
                              (3.4, (1.5, -1.9, -6.8)), (3.8, (1.6, -1.6, -7.2)),
                              (4.3, (2.6, -1.2, -7.2)), (4.65, (-0.6, -1.3, -7.0))],
                1.6, 4.65, ramp=0.7, ramp_out=0.8),
          keys(head_x=[(1.6, 0), (2.2, -14), (3.4, -16), (3.9, -2), (4.3, 0)],
               head_z=[(2.4, 0), (2.7, 1.0), (2.95, -0.5), (3.2, 1.0), (3.4, 0)],
               torso_x=[(1.0, 0), (2.0, -7), (3.5, -8), (4.2, -6), (5.5, 0)],
               sigh=[(3.8, 0), (4.3, 0.9), (5.4, 0)]),
          weight=[(1.0, 0.0), (5.6, 0.0)],
          looks=[(1.5, -2.0, 4.0), (4.9, 6.0, 0.0), (6.0, -3.0, 6.0)],
          offhand="potion", hang_l=0.4,
          hs_props=[{"hand": "offhand", "item": "minecraft:potion", "from": 1.0, "to": 4.4}],
          note="FARMER break: straightens, lifts the waterskin (offhand) and drinks, wipes the "
               "mouth with the back of the hand, satisfied sigh. Offhand prop = stand-in.")


# =========================================================================== LUMBERER (axe)
LUMB = Job(texture="settler_lumberer.png", item="axe", seed=17,
           feet=((-3.1, -0.4), (3.1, 0.5)), knee=6.0, lean=4.0, w0=-0.45, g0=(-5.0, 6.0),
           arm_r=(18.0, -5.0, 6.0, -4.0), hang_r=0.3)
LUMB.clip("idle_lumberer", 5.0,
          pulses(1.3, 2.0, 3.3, 3.9, ant=0.1, over=0.06, arm_r_x=-32, elbow_r=-38, arm_r_y=-18),
          reach("l", "item_r", [(2.05, (12.5, 7.0, 10.6)), (2.45, (13.2, 8.2, 10.6)),
                                (2.8, (12.4, 7.2, 10.6)), (3.15, (13.0, 8.4, 10.6))],
                2.05, 3.15, ramp=0.45, ramp_out=0.45),
          keys(head_x=[(3.1, 0), (3.3, 5), (3.55, 0)]),
          looks=[(1.4, -4.0, 8.0), (2.0, -8.0, 26.0), (3.2, -9.0, 25.0), (3.9, -5.0, 6.0)],
          note="LUMBERER: lifts the axe to the waist, runs the left thumb along the edge twice, "
               "small approving nod, lowers it.")
# v3: neck and shoulder roll (v2 is the sharpening break below).
LUMB.clip("idle_lumberer__v3", 5.0,
          keys(head_z=[(1.0, 0), (1.5, 10), (2.1, 3), (2.5, -10), (3.0, 0)],
               head_x=[(1.0, 0), (1.5, 5), (2.0, 12), (2.5, 5), (3.0, -5), (3.4, 0)],
               arm_l_x=[(2.9, 0), (3.2, -5), (3.5, 4), (3.8, 0)],
               arm_l_z=[(2.9, 0), (3.15, -6), (3.45, -2), (3.8, 0)],
               torso_x=[(2.9, 0), (3.2, -2.5), (3.5, 2), (3.9, 0)],
               sigh=[(2.8, 0), (3.2, 0.6), (4.0, 0)]),
          note="LUMBERER variant: rolls the stiff neck, then a left shoulder roll.")


# ------------------------------------------------------------------ LUMBERER v2: whetstone break
def _sharpen(L=10.0):
    """Kneels on the right knee, axe head across the left thigh, whetstone (offhand flint) drawn
    along the edge in rhythmic strokes; thumb test; satisfied nod; stands."""
    down0, down1, up0, up1 = 0.6, 1.9, 8.3, 9.5
    parts = []
    # left foot steps forward (lift -> place), right knee goes down behind
    parts.append(keys(
        foot_l=[(down0, (0, 0, 0)), (down0 + 0.3, (0.1, 1.4, -2.2), "s"),
                (down0 + 0.65, (0.2, 0.0, -4.4)), (up0 + 0.25, (0.2, 0.0, -4.4)),
                (up0 + 0.6, (0.1, 1.3, -2.0)), (up0 + 0.9, (0, 0, 0))],
        foot_r=[(down0 + 0.5, (0, 0, 0)), (down1, (0.3, 0.9, 3.2)), (up0, (0.3, 0.9, 3.2)),
                (up1 - 0.2, (0, 0, 0))],
        dip=[(down0 + 0.4, 0), (down1, 5.6, "d"), (up0, 5.6, "s"), (up1, 0.0)],
        torso_x=[(down0, 0), (down1, 14.0), (up0, 14.0), (up1, 0)],
        root_roll=[(down0 + 0.4, 0), (down1, -2.0), (up0, -2.0), (up1, 0)],
    ))
    # axe: right arm brings the head across the left thigh
    parts.append(pulses(down0 + 0.3, down1 + 0.3, up0 - 0.2, up1 - 0.3,
                        arm_r_x=-43, arm_r_y=-60, arm_r_z=-6, elbow_r=-8))
    # strokes: left hand (flint) along the edge in item space, blade tip ~ (13.5, 9.5)
    st0, per, n = 2.6, 0.60, 7          # 12-tick strokes: scrape starts on whole ticks
    strokes = []
    for i in range(n):
        t = st0 + i * per
        strokes += [(t, (10.8, 6.6, 10.4)), (t + 0.42 * per, (14.3, 10.3, 10.4)),
                    (t + 0.62 * per, (14.5, 10.8, 11.6)), (t + 0.9 * per, (11.0, 6.9, 11.4))]
    t_end = st0 + n * per
    thumb = [(t_end + 0.5, (13.2, 8.6, 10.6)), (t_end + 0.85, (13.9, 9.4, 10.6)),
             (t_end + 1.2, (13.3, 8.7, 10.6)), (t_end + 1.5, (13.9, 9.4, 10.6))]
    parts.append(reach("l", "item_r", strokes + thumb, st0, t_end + 1.5, ramp=0.6,
                       ramp_out=0.7, pole=(0.9, 0.6, 0.2)))
    parts.append(keys(head_x=[(t_end + 1.6, 0), (t_end + 1.85, 7), (t_end + 2.1, 1),
                              (t_end + 2.3, 5), (t_end + 2.6, 0)],
                      sigh=[(t_end + 1.5, 0), (t_end + 2.0, 0.7), (t_end + 2.8, 0)]))
    # body rhythm with the strokes: tiny shoulder push each forward stroke
    push = [(st0, 0)]
    for i in range(n):
        t = st0 + i * per
        push += [(t + 0.42 * per, 1.6, "d"), (t + per, 0.0)]
    parts.append(keys(torso_y=push))
    return parts, [round(st0 + i * per, 3) for i in range(n)]   # scrape (forward stroke) starts


_parts, SHARPEN_STROKE_T = _sharpen()
LUMB.clip("idle_lumberer__v2", 10.0, *_parts,
          weight=[(0.4, 0.5), (1.9, 0.1), (8.3, 0.1), (9.6, -0.4)],
          looks=[(0.5, -3.0, 10.0), (2.0, 10.0, 40.0), (7.0, 12.0, 42.0), (7.7, 14.0, 36.0),
                 (8.1, 8.0, 34.0), (8.6, -4.0, 12.0)],
          offhand="flint", low_cam=True, knee=6.0,
          hs_sounds=[{"t": t, "sound": "hearthstead:whetstone_scrape", "volume": 0.4,
                      "pitch_jitter": 0.06} for t in SHARPEN_STROKE_T],
          hs_props=[{"hand": "offhand", "item": "minecraft:flint", "from": 2.0, "to": 8.0}],
          note="LUMBERER break (owner request): kneels on the right knee, axe head across the "
               "left thigh, draws the whetstone (offhand flint stand-in) along the edge in 7 "
               "rhythmic strokes, thumb-tests the edge twice, satisfied nod, stands. "
               "Stroke mid-points (s): " + ", ".join(f"{t:.2f}" for t in []))
S["idle_lumberer__v2"]["note"] = S["idle_lumberer__v2"]["note"].rstrip(": ") + ": " + \
    ", ".join(f"{t:.2f}" for t in SHARPEN_STROKE_T)


# =========================================================================== SENTRY (GUARD, sword)
SENT = Job(texture="settler_guard.png", item="sword", seed=23,
           feet=((-3.5, -1.3), (3.5, 1.1)), knee=7.0, lean=-3.0, w0=0.35, g0=(0.0, -2.0),
           arm_r=(-15.0, 0.0, 8.0, -100.0), hang_r=0.0,
           always=lambda L: hold_reach("l", "torso", [(0, (4.8, -3.2, -2.2)), (L, (4.8, -3.2, -2.2))],
                                       L, pole=(1.0, 0.2, 0.3)))
SENT.clip("idle_sentry", 5.5,
          keys(torso_x=[(1.0, 0), (1.6, -1.0), (4.6, -1.0), (5.2, 0)]),
          weight=[(1.2, 0.5), (2.6, -0.3), (3.8, -0.3), (4.8, 0.35)],
          looks=[(0.8, 2.0, -2.0), (1.5, 26.0, -4.0, "io"), (2.3, 24.0, -3.0), (3.1, -28.0, -4.0, "io"),
                 (3.9, -26.0, -3.0), (4.6, -2.0, -2.0)],
          note="GUARD: proud, broad staggered stance, sword lowered, left hand on the belt; one "
               "slow deliberate horizon scan with holds.")
SENT.clip("idle_sentry__v2", 6.0,
          reach("l", "head", [(1.6, (1.5, -4.2, -6.3)), (3.8, (1.5, -4.4, -6.3))], 1.6, 3.8,
                ramp=0.6, ramp_out=0.7),
          keys(torso_x=[(1.2, 0), (1.9, 4), (3.8, 4), (4.6, 0)], dip=[(1.4, 0), (2.0, 0.3), (4.2, 0.3), (4.8, 0)]),
          weight=[(1.2, 0.8), (4.4, 0.8)],
          looks=[(1.4, 0.0, -2.0), (2.2, 10.0, -1.0), (2.9, -8.0, -1.0, "io"), (3.6, -6.0, -1.0), (4.4, 0.0, -2.0)],
          note="GUARD variant: the belt hand comes up to shade the eyes; leans into the front "
               "foot and scans the far horizon.")
SENT.clip("idle_sentry__v3", 5.0,
          reach("l", "head", [(1.0, (3.0, -8.4, -1.5)), (1.3, (2.5, -8.8, -2.3)),
                              (1.6, (3.0, -8.4, -1.0))], 1.0, 1.6, ramp=0.5, ramp_out=0.55),
          keys(head_z=[(0.9, 0), (1.2, -4), (1.45, 2), (1.8, 0)],
               head_x=[(0.9, 0), (1.25, 3), (1.7, 0)],
               arm_r_x=[(2.5, 0), (2.8, -14, "d"), (3.0, -16), (3.25, 3, "a"), (3.6, 0)],
               elbow_r=[(2.5, 0), (2.8, -20), (3.05, -22), (3.3, 2), (3.7, 0)],
               arm_r_y=[(2.6, 0), (2.9, 12), (3.2, 0)],
               dip=[(3.1, 0), (3.3, 0.25), (3.7, 0)]),
          looks=[(2.4, 0.0, -2.0), (2.8, -4.0, 16.0), (3.3, -2.0, 10.0), (3.9, 0.0, -2.0)],
          note="GUARD variant: settles the helmet with a two-finger nudge, then shifts the "
               "sword grip (lift, turn, re-seat) and glances at the hilt.")
SENT.clip("idle_sentry__v4", 8.0,
          pulses(1.0, 2.0, 5.8, 6.8, ant=0.08, arm_r_x=-20, arm_r_y=-75, arm_r_z=-8, elbow_r=90),
          reach("l", "item_r", osc_pts((9.0, 9.0, 10.4), (2.6, 2.6, 0.0), 2.3, 4, 0.7) +
                [(5.1, (9.0, 9.0, 10.4)), (5.4, (14.0, 14.0, 10.4))], 2.3, 5.4,
                ramp=0.5, ramp_out=0.6, pole=(0.9, 0.5, 0.4)),
          keys(torso_x=[(1.0, 0), (2.0, 5), (5.8, 5), (6.8, 0)]),
          offhand="paper", hs_props=[{"hand": "offhand", "item": "minecraft:paper", "from": 1.8, "to": 5.8}],
          looks=[(1.0, 0.0, 4.0), (2.0, 14.0, 26.0), (5.0, 14.0, 26.0), (5.5, 16.0, 18.0),
                 (6.0, 18.0, 16.0), (6.8, 2.0, -1.0)],
          note="GUARD break: raises the blade across the body and works the oil rag along it "
               "(left hand), sights down the edge, lowers it.")


# =========================================================================== ARCHER (bow)
ARCH = Job(texture="settler_archer.png", item="bow", seed=29,
           feet=((-3.2, -1.0), (3.2, 0.9)), knee=6.0, lean=-1.5, w0=0.3, g0=(0.0, 0.0),
           arm_r=(10.0, 15.0, 0.0, -80.0), hang_r=0.0)
ARCH.clip("idle_archer", 5.2,
          reach("l", "item_r", [(1.4, (7.5, 6.0, 9.8)), (1.9, (7.8, 10.5, 9.8)), (2.4, (7.5, 5.5, 9.8)),
                                (2.8, (7.8, 9.5, 9.8))], 1.4, 2.8, ramp=0.5, ramp_out=0.55),
          pulses(1.0, 1.6, 2.8, 3.4, arm_r_x=-8, elbow_r=-10, arm_r_y=-10),
          weight=[(1.2, 0.5), (3.0, -0.4), (4.4, 0.2)],
          looks=[(1.0, 0.0, 6.0), (1.6, -10.0, 26.0), (2.8, -8.0, 24.0), (3.5, 18.0, -2.0, "io"),
                 (4.2, 16.0, -2.0), (4.8, 0.0, 0.0)],
          note="ARCHER: bow hangs outside the right knee; lifts it a little and checks the "
               "string with the free hand, then an easy look downrange.")
ARCH.clip("idle_archer__v2", 6.0,
          reach("l", "head", [(1.4, (1.5, -4.2, -6.3)), (3.8, (1.5, -4.4, -6.3))], 1.4, 3.8,
                ramp=0.6, ramp_out=0.7),
          weight=[(1.2, 0.8), (4.3, 0.8)],
          looks=[(1.2, 0.0, 0.0), (2.0, -12.0, -1.0), (2.9, 10.0, -1.0, "io"), (3.6, 8.0, -1.0), (4.4, 0.0, 0.0)],
          note="ARCHER variant: shades the eyes with the bow-free hand and scans the treeline.")


# =========================================================================== COURIER
COUR = Job(texture="settler_courier.png", seed=31, feet=((-2.8, 0.3), (2.8, -0.3)), knee=5.0,
           lean=2.0, w0=-0.5, g0=(3.0, 3.0), arm_r=(0.0, 0.0, 3.0, -10.0), arm_l=(-2.0, 0.0, -3.0, -12.0))
COUR.clip("idle_courier", 4.5,
          reach("r", "torso", [(1.3, (-2.4, -10.0, -4.5)), (1.6, (-2.4, -8.6, -4.6)),
                               (1.85, (-2.4, -10.0, -4.5)), (2.1, (-2.4, -8.6, -4.6)),
                               (2.6, (-2.3, -9.6, -4.5))], 1.3, 2.6, ramp=0.45, ramp_out=0.5),
          reach("l", "torso", [(1.4, (2.6, -3.5, -3.8)), (2.5, (2.6, -3.3, -3.8))], 1.4, 2.5,
                ramp=0.45, ramp_out=0.5),
          keys(dip=[(1.5, 0), (1.62, 0.15), (1.85, 0), (2.12, 0.15), (2.4, 0)]),
          looks=[(1.2, 2.0, 16.0), (2.6, 3.0, 12.0), (3.2, -14.0, 2.0, "io"), (3.8, -12.0, 2.0)],
          note="COURIER: checks the sack straps - thumb under the strap, two firm tugs - then "
               "a glance down the road.")
COUR.clip("idle_courier__v2", 5.0,
          reach("l", "torso", [(1.3, (1.5, -3.5, -6.6)), (3.3, (1.5, -3.6, -6.6))], 1.3, 3.3,
                ramp=0.5, ramp_out=0.5),
          reach("r", "torso", [(1.4, (-1.0, -3.0, -7.0)), (1.6, (1.0, -2.2, -7.2)), (1.8, (-1.0, -3.0, -7.0)),
                               (2.1, (1.0, -3.0, -7.2)), (2.3, (-1.0, -3.0, -7.0)), (2.6, (1.0, -3.8, -7.2)),
                               (2.8, (-1.0, -3.0, -7.0)), (3.1, (1.0, -4.6, -7.2)), (3.3, (-1.0, -3.0, -7.0))],
                1.4, 3.3, ramp=0.5, ramp_out=0.5),
          keys(head_x=[(3.3, 0), (3.5, 5), (3.75, 0)]),
          looks=[(1.2, 3.0, 20.0), (1.8, -3.0, 22.0), (2.6, -4.0, 18.0), (3.0, -14.0, -4.0), (3.4, -10.0, 4.0), (4.0, 3.0, 3.0)],
          note="COURIER variant: counts the deliveries off on the fingers (four taps), glances "
               "up remembering the last one, nods.")
COUR.clip("idle_courier__v3", 5.5,
          keys(dip=[(1.0, 0), (1.3, 0.45, "a"), (1.5, -0.15, "D"), (1.8, 0.1), (2.2, 0)],
               torso_x=[(1.0, 0), (1.3, 3), (1.55, -2), (2.1, 0)],
               arm_r_z=[(1.0, 0), (1.35, 4), (1.6, -3), (2.0, 0)],
               arm_l_z=[(1.0, 0), (1.35, -4), (1.6, 3), (2.0, 0)],
               torso_z=[(2.2, 0), (2.6, -3.5), (3.4, -3.0), (4.0, 0)],
               sigh=[(1.1, 0), (1.5, 0.6), (2.3, 0)]),
          reach("r", "torso", [(2.4, (-2.4, -10.5, -4.3)), (2.9, (-2.6, -8.5, -4.6)),
                               (3.3, (-2.4, -9.5, -4.4))], 2.4, 3.3, ramp=0.4, ramp_out=0.5),
          weight=[(1.0, -0.6), (2.4, 0.7), (4.2, 0.6)],
          looks=[(1.0, 3.0, 3.0), (2.4, 6.0, 12.0), (3.4, 2.0, 4.0)],
          note="COURIER variant: bounces the heavy load higher onto the back, shifts weight and "
               "hitches the strap.")


# =========================================================================== TRADER
TRAD = Job(texture="settler_mayor.png", seed=37, feet=((-3.0, 0.2), (3.0, -0.2)), knee=4.0,
           lean=-1.0, w0=0.4, g0=(0.0, 1.0),
           always=lambda L: merge(
               hold_reach("r", "torso", [(0, (-2.0, -2.2, -5.4)), (L, (-2.0, -2.2, -5.4))], L),
               hold_reach("l", "torso", [(0, (2.0, -2.6, -5.2)), (L, (2.0, -2.6, -5.2))], L)))


def _trader_quote(L):
    # right hand leaves the clasp, open-palm quote out front, returns
    return merge(
        hold_reach("l", "torso", [(0, (2.0, -2.6, -5.2)), (L, (2.0, -2.6, -5.2))], L),
        {"reach": {"r": {"space": "torso", "p": [(0, (-2.0, -2.2, -5.4)), (1.1, (-2.0, -2.2, -5.4)),
                                                (1.55, (-4.2, -5.5, -8.0)), (2.2, (-4.5, -5.3, -8.2)),
                                                (2.6, (-2.0, -2.2, -5.4)), (L, (-2.0, -2.2, -5.4))],
                         "w": [(0, 1.0), (L, 1.0)]}}})


TRAD.always = {}
TRAD.clip("idle_trader", 4.5, _trader_quote(4.5),
          keys(head_x=[(2.0, 0), (2.2, 6), (2.45, 0)], head_z=[(1.4, 0), (1.8, 3), (2.4, 0)]),
          looks=[(1.2, 0.0, 1.0), (1.6, -4.0, 2.0), (2.6, 3.0, 1.0)],
          note="TRADE STEWARD: hands clasped, a small open-palm quote, confirming nod.")
TRAD.clip("idle_trader__v2", 5.0,
          hold_reach("r", "torso", [(0, (-2.0, -2.2, -5.4)), (5.0, (-2.0, -2.2, -5.4))], 5.0),
          {"reach": {"l": {"space": "torso", "p": [(0, (2.0, -2.6, -5.2)), (0.9, (2.0, -2.6, -5.2)),
                                                  (1.4, (5.6, -1.2, -3.2))] + osc_pts((5.6, -1.2, -3.2), (0, 0.9, 0), 1.5, 3, 0.36) +
                          [(3.3, (5.6, -1.2, -3.2)), (3.8, (2.0, -2.6, -5.2)), (5.0, (2.0, -2.6, -5.2))],
                          "w": [(0, 1.0), (5.0, 1.0)]}}},
          keys(head_x=[(3.0, 0), (3.25, 4), (3.5, 0)]),
          looks=[(1.2, 0.0, 1.0), (1.6, -12.0, 28.0), (2.6, -10.0, 26.0), (3.2, 2.0, 2.0)],
          note="TRADER variant: weighs the purse at the hip - three jingling bounces - pleased nod.")
TRAD.clip("idle_trader__v3", 6.0, TRAD_clasp := merge(
              hold_reach("r", "torso", [(0, (-2.0, -2.2, -5.4)), (6.0, (-2.0, -2.2, -5.4))], 6.0),
              hold_reach("l", "torso", [(0, (2.0, -2.6, -5.2)), (6.0, (2.0, -2.6, -5.2))], 6.0)),
          keys(head_x=[(2.9, 0), (3.1, 5), (3.35, 0)]),
          weight=[(1.4, -0.3), (4.6, 0.3)],
          looks=[(0.8, 0.0, 1.0), (1.4, -30.0, 3.0, "io"), (2.2, -22.0, 3.0), (3.1, -2.0, 3.0),
                 (3.9, 18.0, 3.0), (4.6, 26.0, 2.0), (5.3, 0.0, 1.0)],
          note="TRADER variant: eyes a passer-by across the square - tracks them, a small "
               "welcoming nod as they pass.")


# =========================================================================== FORGE (SMITH/SMELTER/ARMOURER)
FORG = Job(texture="settler_smith.png", seed=41, feet=((-3.7, 0.3), (3.7, -0.5)), knee=7.0,
           lean=2.0, w0=-0.4, g0=(0.0, 4.0), weight_px=0.7,
           arm_r=(0.0, 0.0, 7.0, -14.0), arm_l=(0.0, 0.0, -7.0, -14.0))
FORG.clip("idle_forge", 5.0,
          reach("r", "head", [(1.3, (2.5, -5.6, -6.6)), (1.75, (-3.5, -6.0, -6.4)),
                              (2.0, (-4.8, -5.5, -5.0))], 1.3, 2.0, ramp=0.55, ramp_out=0.45,
                pole=(1.0, 0.3, 0.2)),
          keys(head_x=[(1.0, 0), (1.35, 8), (1.9, 6), (2.3, -3), (2.8, 0)],
               arm_r_x=[(2.45, 0), (2.6, -6), (2.72, 4), (2.85, -3), (3.0, 0)],
               elbow_r=[(2.45, 0), (2.6, -10), (2.72, 2), (2.85, -6), (3.0, 0)],
               sigh=[(2.0, 0), (2.4, 0.9), (3.4, 0)]),
          weight=[(1.0, -0.6), (2.4, 0.5), (4.0, 0.4)],
          looks=[(1.0, 0.0, 6.0), (2.3, 0.0, -2.0), (3.0, 8.0, 5.0)],
          note="SMITH/SMELTER/ARMOURER: broad stance, heavy arms; wipes the brow with the "
               "forearm, flicks the sweat off, blows out.")
FORG.clip("idle_forge__v2", 5.5,
          keys(arm_r_x=[(1.0, 0), (1.3, -6), (1.6, 0), (1.9, 6), (2.2, 0)],
               arm_l_x=[(1.15, 0), (1.45, -6), (1.75, 0), (2.05, 6), (2.35, 0)],
               arm_r_z=[(1.0, 0), (1.3, 3), (1.6, 6), (1.9, 3), (2.2, 0)],
               arm_l_z=[(1.15, 0), (1.45, -3), (1.75, -6), (2.05, -3), (2.35, 0)],
               torso_x=[(1.0, 0), (1.5, -3), (2.0, 2), (2.4, 0)],
               dip=[(1.0, 0), (1.5, -0.2), (2.0, 0.2), (2.4, 0)],
               head_z=[(2.6, 0), (3.0, -11), (3.4, -3), (3.8, 11), (4.3, 0)],
               head_x=[(2.6, 0), (3.2, 4), (3.6, 9), (4.3, 0)],
               sigh=[(1.0, 0), (1.6, 0.8), (2.6, 0)]),
          weight=[(1.0, -0.2), (4.4, -0.2)],
          looks=[(1.0, 0.0, 2.0), (4.5, 0.0, 4.0)],
          note="FORGE variant: two heavy shoulder rolls, then cracks the neck side to side.")


def _forge_quench(L=6.5):
    both_down = pulses(1.0, 1.8, 3.2, 3.8, ant=0.08, arm_r_x=-38, arm_l_x=-40, elbow_r=-30,
                       elbow_l=-28, arm_r_z=-8, arm_l_z=8)
    lean = keys(torso_x=[(1.0, 0), (1.8, 16), (2.2, 18), (3.2, 17), (3.8, 3), (5.6, 0)],
                dip=[(1.0, 0), (1.8, 0.6), (3.2, 0.6), (3.8, 0.0)],
                arm_r_y=[(1.8, 0), (2.1, 5), (2.5, -5), (2.9, 5), (3.2, 0)])
    wipe = reach("l", "hand_r", [(4.0, (1.6, -1.0, -2.2)), (4.35, (1.6, 5.0, -2.2)),
                                 (4.6, (1.6, -1.0, -2.2)), (4.95, (1.6, 5.0, -2.2))], 4.0, 4.95,
                 ramp=0.3, ramp_out=0.45)
    rarm = pulses(3.6, 4.0, 4.95, 5.4, arm_r_x=-40, elbow_r=-50, arm_r_z=-15)
    return [both_down, lean, wipe, rarm]


FORG.clip("idle_forge__v3", 6.5, *_forge_quench(),
          keys(sigh=[(3.3, 0), (3.6, 0.5), (4.3, 0)]),
          weight=[(1.0, 0.1), (5.6, 0.1)],
          looks=[(1.0, 0.0, 10.0), (1.8, 0.0, 44.0), (3.2, 0.0, 42.0), (4.0, 0.0, 28.0),
                 (5.0, 0.0, 26.0), (5.8, 0.0, 4.0)],
          note="FORGE break: tongs down into the quench bucket (hold for the hiss, a little "
               "swirl), lifts out, wipes the forearm down.")


# =========================================================================== BAKER / MILLER
BAKE = Job(texture="settler_baker.png", seed=43, feet=((-3.0, 0.2), (3.0, -0.4)), knee=5.0,
           lean=3.0, w0=-0.4, g0=(0.0, 6.0), arm_r=(-4.0, -6.0, -2.0, -22.0),
           arm_l=(-4.0, 6.0, 2.0, -22.0), hang_r=0.6, hang_l=0.6)
BAKE.clip("idle_baker", 4.5,
          reach("r", "torso", [(1.5, (-4.0, -4.5, -6.0)), (1.72, (-2.1, -4.8, -6.2)),
                               (1.9, (-4.2, -4.6, -6.0)), (2.1, (-2.1, -4.8, -6.2)),
                               (2.35, (-3.0, -4.0, -4.6)), (3.0, (-2.8, 1.6, -4.4))],
                1.5, 3.0, ramp=0.5, ramp_out=0.5),
          reach("l", "torso", [(1.5, (4.0, -4.5, -6.0)), (1.72, (2.1, -4.8, -6.2)),
                               (1.9, (4.2, -4.6, -6.0)), (2.1, (2.1, -4.8, -6.2)),
                               (2.45, (3.0, -4.0, -4.6)), (3.1, (2.8, 1.6, -4.4))],
                1.5, 3.1, ramp=0.5, ramp_out=0.5),
          keys(head_z=[(2.2, 0), (2.45, 2), (2.7, -2), (2.95, 0)]),
          looks=[(1.4, 0.0, 22.0), (2.2, 0.0, 26.0), (3.1, 0.0, 30.0), (3.6, 0.0, 6.0)],
          note="BAKER/MILLER: two sharp claps knock the flour off, then the palms brush down the "
               "apron.")
BAKE.clip("idle_baker__v2", 6.0,
          keys(torso_y=[(1.0, 0), (1.8, -6), (3.9, -6), (4.6, 0)],
               torso_x=[(1.4, 0), (2.0, 5, "d"), (3.6, 4), (4.3, -1), (4.8, 0)],
               sigh=[(1.8, 0), (2.6, 1.3, "d"), (3.2, 1.2), (4.0, 0)],
               head_x=[(2.0, 0), (2.6, -6), (3.3, -5), (3.9, 3), (4.2, 0)]),
          reach("r", "torso", [(4.2, (-1.5, -4.5, -4.2)), (4.4, (-1.5, -4.5, -5.2)),
                               (4.6, (-1.5, -4.5, -4.2)), (4.8, (-1.5, -4.5, -5.2))], 4.2, 4.8,
                ramp=0.35, ramp_out=0.5),
          weight=[(1.0, -0.4), (2.0, -0.85), (3.9, -0.8), (4.7, -0.3)],
          looks=[(1.0, 0.0, 6.0), (1.9, -40.0, 4.0, "io"), (3.7, -38.0, 2.0), (4.4, -2.0, 8.0)],
          note="BAKER variant: sniffs toward the oven - turns, leans in on a long slow inhale - "
               "then pats the belly, satisfied.")
BAKE.clip("idle_baker__v3", 6.0,
          reach("r", "torso", [(1.2, (-3.6, -2.8, 4.8)), (3.8, (-3.6, -2.6, 4.8))], 1.2, 3.8,
                ramp=0.55, ramp_out=0.6, pole=(1.0, 0.1, 0.4)),
          reach("l", "torso", [(1.3, (3.6, -2.8, 4.8)), (3.8, (3.6, -2.6, 4.8))], 1.3, 3.8,
                ramp=0.55, ramp_out=0.6, pole=(1.0, 0.1, 0.4)),
          keys(torso_x=[(1.4, 0), (2.2, -13, "d"), (3.0, -14), (3.7, -3), (4.2, 1), (4.7, 0)],
               root_pz=[(1.4, 0), (2.2, -0.7), (3.0, -0.7), (3.8, 0)],
               torso_y=[(3.0, 0), (3.3, 5), (3.6, -4), (3.9, 0)],
               sigh=[(1.5, 0), (2.3, 1.1), (3.3, 0.8), (4.0, 0)]),
          weight=[(1.0, 0.0), (4.4, 0.0)],
          looks=[(1.2, 0.0, 6.0), (2.3, 0.0, -18.0), (3.0, 0.0, -18.0), (4.0, 0.0, 6.0)],
          note="BAKER variant: hands to the small of the back, a long arch on the inhale, a "
               "little twist, then eases forward.")
BAKE.clip("idle_baker__v4", 5.5,
          reach("l", "torso", [(1.0, (1.8, -4.2, -7.0)), (3.0, (1.8, -4.4, -7.0))], 1.0, 3.0,
                ramp=0.45, ramp_out=0.5),
          reach("r", "head", [(1.3, (-0.4, 3.2, -9.0)), (1.5, (-0.4, 3.6, -9.2)), (2.05, (-1.3, -1.8, -6.8)),
                              (2.3, (-1.3, -1.8, -6.8))], 1.3, 2.3, ramp=0.35, ramp_out=0.5),
          keys(head_x=[(2.3, 0), (2.5, 3), (2.7, 0), (2.9, 3), (3.1, 0), (3.3, 3), (3.5, 0),
                       (3.9, 6), (4.2, 0)],
               head_z=[(3.6, 0), (3.9, 4), (4.4, 0)]),
          looks=[(1.0, 0.0, 22.0), (1.6, 0.0, 26.0), (2.2, 0.0, 8.0), (3.6, -6.0, 4.0), (4.4, 0.0, 6.0)],
          offhand="bread", hs_props=[{"hand": "offhand", "item": "minecraft:bread", "from": 0.6, "to": 3.4}],
          note="BAKER break: picks a crumb off the loaf in the left hand, tastes it, chews, "
               "approving nod.")


# =========================================================================== COOK / BREWER
COOK = Job(texture="settler_cook.png", seed=47, feet=((-3.0, 0.3), (3.0, -0.3)), knee=5.0,
           lean=3.0, w0=-0.4, g0=(-3.0, 8.0), arm_r=(-6.0, -10.0, -4.0, -18.0),
           arm_l=(-10.0, 10.0, 3.0, -34.0), hang_r=0.5, hang_l=0.2)
COOK.clip("idle_cook", 4.5,
          reach("r", "head", [(1.9, (-1.4, -1.6, -7.0)), (2.5, (-1.3, -1.8, -6.8))], 1.9, 2.5,
                ramp=0.6, ramp_out=0.65),
          keys(torso_x=[(1.4, 0), (2.0, 5), (2.6, 5), (3.2, 0)],
               head_x=[(2.5, 0), (2.8, 7), (3.05, 2), (3.3, 0)],
               head_z=[(2.8, 0), (3.1, -4), (3.5, 0)]),
          looks=[(1.2, -3.0, 14.0), (1.9, -1.0, 6.0), (2.6, 2.0, 4.0), (3.4, -3.0, 8.0)],
          note="COOK/BREWER: taste-test - spoon hand to the mouth, a pause, a judging nod and "
               "a head tilt.")
COOK.clip("idle_cook__v2", 5.0,
          reach("r", "head", [(1.3, (2.0, -5.0, -6.6)), (1.8, (-3.5, -5.8, -6.4))], 1.3, 1.8,
                ramp=0.5, ramp_out=0.5, pole=(1.0, 0.3, 0.2)),
          keys(arm_l_x=[(2.2, 0), (2.4, -20), (2.6, -12), (2.8, -22), (3.0, -12), (3.2, -20), (3.6, 0)],
               elbow_l=[(2.2, 0), (2.4, -25), (2.6, -15), (2.8, -28), (3.0, -15), (3.2, -25), (3.6, 0)],
               sigh=[(1.6, 0), (2.1, 1.0), (3.0, 0)], head_x=[(1.2, 0), (1.5, 6), (2.0, -4), (2.6, 0)]),
          looks=[(1.2, -3.0, 8.0), (2.2, 0.0, -2.0), (3.4, -3.0, 8.0)],
          note="COOK variant: the heat - wipes the brow with the back of the wrist, fans "
               "the face with the free hand, puffs out.")
COOK.clip("idle_cook__v3", 5.5,
          reach("r", "torso", [(1.0, (-5.8, -2.0, -1.0)), (3.8, (-5.8, -2.0, -1.0))], 1.0, 3.8,
                ramp=0.5, ramp_out=0.6, pole=(1.0, 0.2, 0.0)),
          reach("l", "torso", [(1.1, (5.8, -2.0, -1.0)), (3.8, (5.8, -2.0, -1.0))], 1.1, 3.8,
                ramp=0.5, ramp_out=0.6, pole=(1.0, 0.2, 0.0)),
          keys(torso_x=[(1.0, 0), (1.6, -4), (3.8, -4), (4.4, 0)], head_x=[(2.6, 0), (2.85, 6), (3.1, 0)]),
          weight=[(1.0, 0.6), (4.2, 0.6)],
          looks=[(1.0, -3.0, 8.0), (1.7, -24.0, 6.0, "io"), (2.4, 20.0, 6.0, "io"), (3.0, 0.0, 4.0), (4.4, -3.0, 8.0)],
          note="COOK variant: fists on the hips, surveys the kitchen, one satisfied nod.")


# =========================================================================== BLADE BENCH (BUTCHER/TANNER)
BLAD = Job(texture="settler_butcher.png", seed=53, feet=((-3.2, 0.4), (3.2, -0.4)), knee=6.0,
           lean=6.0, w0=-0.45, g0=(-2.0, 12.0), arm_r=(-14.0, -10.0, -6.0, -24.0),
           arm_l=(-8.0, 8.0, 5.0, -20.0), hang_r=0.2, hang_l=0.7)
BLAD.clip("idle_blade_bench", 5.0,
          reach("r", "torso", [(1.6, (-2.5, -8.0, -6.0)), (2.3, (-3.4, 1.8, -4.6)),
                               (2.6, (-3.0, -3.0, -8.4)), (2.85, (-3.0, -1.6, -8.6)),
                               (3.1, (-3.0, -3.0, -8.4)), (3.35, (-3.0, -1.6, -8.6))],
                1.6, 3.35, ramp=0.5, ramp_out=0.55),
          keys(torso_x=[(1.4, 0), (2.3, 4), (3.4, 3), (4.0, 0)]),
          looks=[(1.4, -2.0, 18.0), (2.5, -3.0, 30.0), (3.5, -2.0, 24.0), (4.2, -2.0, 12.0)],
          note="BUTCHER/TANNER: wipes the knife down the apron in one long stroke, then taps "
               "the flat twice on the bench to test it's true.")
BLAD.clip("idle_blade_bench__v2", 5.0,
          reach("r", "torso", [(1.2, (-1.5, -6.5, -7.5)), (3.4, (-1.5, -6.6, -7.6))], 1.2, 3.4,
                ramp=0.5, ramp_out=0.55),
          reach("l", "torso", [(1.6, (0.8, -6.0, -8.4)), (1.95, (-0.6, -6.6, -8.4)),
                               (2.3, (0.8, -6.0, -8.4)), (2.65, (-0.6, -6.6, -8.4))], 1.6, 2.65,
                ramp=0.4, ramp_out=0.45),
          keys(head_x=[(2.9, 0), (3.1, 5), (3.35, 0)], head_z=[(1.6, 0), (2.0, 4), (2.8, 3), (3.2, 0)]),
          looks=[(1.2, 0.0, 18.0), (1.7, 3.0, 30.0), (2.8, 3.0, 30.0), (3.6, -2.0, 12.0)],
          note="BUTCHER/TANNER variant: holds the blade up and draws the thumb across the edge "
               "twice, nods.")
BLAD.clip("idle_blade_bench__v3", 5.0,
          reach("r", "torso", [(1.2, (-2.6, -3.0, -4.6)), (1.55, (-2.8, 2.0, -4.4)),
                               (1.9, (-2.6, -3.0, -4.6)), (2.25, (-2.8, 2.0, -4.4))], 1.2, 2.25,
                ramp=0.45, ramp_out=0.5),
          reach("l", "torso", [(1.3, (2.6, -3.0, -4.6)), (1.65, (2.8, 2.0, -4.4)),
                               (2.0, (2.6, -3.0, -4.6)), (2.35, (2.8, 2.0, -4.4))], 1.3, 2.35,
                ramp=0.45, ramp_out=0.5),
          keys(torso_x=[(2.8, 0), (3.2, -5), (3.6, 1), (4.0, 0)],
               arm_r_z=[(2.9, 0), (3.2, 6), (3.6, 0)], arm_l_z=[(2.9, 0), (3.2, -6), (3.6, 0)],
               sigh=[(2.8, 0), (3.2, 0.8), (4.0, 0)]),
          looks=[(1.1, -2.0, 30.0), (2.4, -2.0, 28.0), (3.1, 0.0, -2.0), (3.9, -2.0, 12.0)],
          note="BUTCHER/TANNER variant: wipes both hands on the apron, rolls the shoulders back.")


# =========================================================================== SIGHT EDGE (MASON/CARPENTER/SAWYER)
SIGH = Job(texture="settler_mason.png", seed=59, feet=((-3.0, 0.3), (3.0, -0.3)), knee=5.0,
           lean=4.0, w0=-0.45, g0=(-4.0, 6.0))
SIGH.clip("idle_sight_edge", 5.0,
          pulses(1.1, 2.1, 3.3, 3.9, ant=0.08, over=0.05, arm_r_x=-72, arm_r_y=-24, elbow_r=4),
          keys(head_z=[(1.6, 0), (2.2, 14), (3.3, 13), (3.9, 0)],
               head_x=[(1.8, 0), (2.2, 3), (3.3, 3), (3.8, 0)],
               torso_y=[(1.4, 0), (2.2, 8), (3.3, 8), (4.0, 0)]),
          weight=[(1.1, 0.5), (3.5, 0.5)],
          looks=[(1.2, -4.0, 6.0), (2.2, 20.0, 3.0), (3.3, 22.0, 3.0), (4.0, -4.0, 6.0)],
          note="MASON/CARPENTER/SAWYER: extends the arm as a level reference line and cants the "
               "head to sight along it, one eye shut.")
SIGH.clip("idle_sight_edge__v2", 5.0,
          reach("r", "torso", osc_pts((-0.8, -5.0, -7.0), (0, 0.9, 0), 1.2, 3, 0.3), 1.2, 2.1,
                ramp=0.45, ramp_out=0.3),
          reach("l", "torso", osc_pts((0.8, -5.0, -7.0), (0, -0.9, 0), 1.2, 3, 0.3) +
                [(2.6, (0.8, -5.2, -7.8))], 1.2, 2.6, ramp=0.45, ramp_out=0.5),
          keys(head_x=[(2.1, 0), (2.5, 8), (2.9, 8), (3.3, 0)],
               arm_r_x=[(2.2, 0), (2.5, -6), (2.8, 4), (3.1, 0)], elbow_r=[(2.2, 0), (2.45, -12), (2.8, 3), (3.1, 0)],
               sigh=[(2.3, 0), (2.5, 0.6), (2.8, -0.1), (3.2, 0)]),
          looks=[(1.1, -4.0, 22.0), (2.9, -2.0, 30.0), (3.6, -4.0, 6.0)],
          note="MASON/CARPENTER/SAWYER variant: rubs the palms together, blows the dust off them, "
               "slaps the forearm clean.")
SIGH.clip("idle_sight_edge__v3", 5.5,
          reach("r", "torso", [(1.0, (-5.8, -2.0, -1.0)), (3.9, (-5.8, -2.0, -1.0))], 1.0, 3.9,
                ramp=0.5, ramp_out=0.6, pole=(1.0, 0.2, 0.0)),
          reach("l", "torso", [(1.1, (5.8, -2.0, -1.0)), (3.9, (5.8, -2.0, -1.0))], 1.1, 3.9,
                ramp=0.5, ramp_out=0.6, pole=(1.0, 0.2, 0.0)),
          keys(torso_y=[(1.6, 0), (2.2, 14), (2.6, 13), (3.1, -14), (3.5, -13), (4.1, 0)],
               torso_x=[(1.4, 0), (1.9, -5), (3.6, -4), (4.2, 0)],
               sigh=[(1.5, 0), (2.0, 0.9), (3.2, 0.3), (4.0, 0)]),
          weight=[(1.0, 0.0), (4.4, 0.0)],
          looks=[(1.2, -4.0, 6.0), (2.3, -12.0, -2.0), (3.3, 12.0, -2.0), (4.2, -4.0, 6.0)],
          note="MASON/CARPENTER/SAWYER variant: fists on the hips and a slow spine twist each "
               "way to work the stiffness out.")


# =========================================================================== FLETCHER
FLET = Job(texture="settler_fletcher.png", seed=61, feet=((-2.7, 0.2), (2.7, -0.2)), knee=5.0,
           lean=6.0, w0=-0.4, g0=(4.0, 16.0),
           always=lambda L: merge(
               hold_reach("r", "torso", [(0, (-1.4, -6.2, -6.6)), (L, (-1.4, -6.2, -6.6))], L),
               hold_reach("l", "torso", [(0, (1.6, -5.8, -6.8)), (L, (1.6, -5.8, -6.8))], L)))
FLET.always_fn = FLET.always


def _flet_hold(L, r_pts=None, l_pts=None):
    r = r_pts or []
    lp = l_pts or []
    return merge(hold_reach("r", "torso", [(0, (-1.4, -6.2, -6.6))] + r + [(L, (-1.4, -6.2, -6.6))], L),
                 hold_reach("l", "torso", [(0, (1.6, -5.8, -6.8))] + lp + [(L, (1.6, -5.8, -6.8))], L))


FLET.always = {}
FLET.clip("idle_fletcher", 4.0,
          _flet_hold(4.0, r_pts=[(1.5, (-1.4, -6.2, -6.6)), (1.7, (-0.6, -6.9, -6.8)), (1.9, (-1.9, -5.7, -6.5)),
                                 (2.1, (-0.6, -6.9, -6.8)), (2.3, (-1.9, -5.7, -6.5)), (2.5, (-1.4, -6.2, -6.6))]),
          keys(arm_r_y=[(1.5, 0), (1.7, 8), (1.9, -8), (2.1, 8), (2.3, -8), (2.5, 0)],
               head_z=[(1.4, 0), (1.8, 5), (2.6, 5), (3.0, 0)]),
          looks=[(1.4, 4.0, 20.0), (2.6, 3.0, 20.0)],
          note="FLETCHER: twirls the arrow shaft between the fingers, twice each way, and "
               "watches it run true.")
FLET.clip("idle_fletcher__v2", 5.0,
          _flet_hold(5.0, r_pts=[(1.0, (-1.4, -6.2, -6.6)), (1.8, (-0.8, -14.0, -5.4)), (3.4, (-0.8, -14.2, -5.4)),
                                 (4.2, (-1.4, -6.2, -6.6))],
                     l_pts=[(1.0, (1.6, -5.8, -6.8)), (1.8, (0.9, -12.8, -9.0)), (3.4, (0.9, -12.9, -9.0)),
                            (4.2, (1.6, -5.8, -6.8))]),
          keys(head_z=[(1.6, 0), (2.1, -12), (3.2, -11), (3.9, 0)],
               arm_r_y=[(2.3, 0), (2.5, 10), (2.75, 0), (2.95, 10), (3.2, 0)],
               torso_x=[(1.0, 0), (1.8, -5), (3.4, -5), (4.2, 0)]),
          looks=[(1.2, 4.0, 14.0), (2.0, -1.0, -2.0), (3.3, -1.0, -2.0), (4.1, 4.0, 16.0)],
          note="FLETCHER variant: lifts the arrow to the eye and sights down the shaft, turning "
               "it to check for warp.")
FLET.clip("idle_fletcher__v3", 4.5,
          _flet_hold(4.5, r_pts=[(1.1, (-1.4, -6.2, -6.6)), (1.4, (1.4, -6.8, -7.2)), (1.7, (2.4, -5.2, -7.0)),
                                 (2.0, (1.4, -6.8, -7.2)), (2.3, (2.4, -5.2, -7.0)), (2.7, (-1.4, -6.2, -6.6))],
                     l_pts=[(1.2, (1.6, -5.8, -6.8)), (2.7, (1.6, -9.0, -7.8)), (3.4, (1.6, -9.0, -7.8)),
                            (3.9, (1.6, -5.8, -6.8))]),
          keys(sigh=[(2.8, 0), (3.0, -0.2), (3.3, 0.6), (3.7, 0)], head_x=[(2.7, 0), (3.1, 6), (3.6, 0)]),
          looks=[(1.1, 6.0, 24.0), (2.7, 8.0, 12.0), (3.6, 4.0, 16.0)],
          note="FLETCHER variant: smooths the fletching with a thumb, raises it and blows the "
               "down off the feathers.")


# =========================================================================== MINER (pickaxe)
MINE = Job(texture="settler_miner.png", item="pickaxe", seed=67,
           feet=((-3.3, 0.4), (3.3, -0.6)), knee=10.0, lean=8.0, w0=-0.5, g0=(0.0, 12.0),
           arm_r=(-18.0, 0.0, 10.0, -110.0), hang_r=0.0, arm_l=(-3.0, 0.0, -4.0, -12.0),
           breath=dict(amp=1.2))
MINE.clip("idle_miner", 5.0,
          reach("l", "torso", [(1.6, (5.0, -3.4, -0.2)), (3.4, (5.0, -3.1, -0.2))], 1.6, 3.4,   # hand on the hip, clear of the job pack
                ramp=0.6, ramp_out=0.6, pole=(1.0, 0.1, 0.4)),
          keys(torso_x=[(1.8, 0), (2.4, 2, "a"), (2.8, -14, "d"), (3.3, -15), (3.9, 0)],
               sigh=[(2.2, 0), (2.9, 1.2), (3.4, 1.0), (4.2, 0)],
               head_x=[(2.5, 0), (2.9, -6), (3.3, -7), (3.9, 0)]),
          weight=[(1.2, -0.5), (2.6, 0.2), (3.9, 0.1)],
          looks=[(1.2, 0.0, 12.0), (2.8, 0.0, -14.0), (3.3, 0.0, -14.0), (4.0, 0.0, 12.0)],
          note="MINER: tired, knees soft, pick on the shoulder; hand on the hip (clear of the "
               "pack), one long cracking arch, slumps back.")
MINE.clip("idle_miner__v2", 5.5,
          reach("l", "torso", [(1.2, (-4.0, -11.6, -2.8)), (1.4, (-4.2, -11.0, -3.4)),
                               (1.6, (-4.0, -11.6, -2.8)), (1.8, (-4.2, -11.0, -3.4)),
                               (2.2, (-1.5, -8.0, -4.8)), (2.6, (1.5, -5.0, -4.8))], 1.2, 2.6,
                ramp=0.5, ramp_out=0.5),
          keys(torso_x=[(2.9, 0), (3.05, 6, "D"), (3.25, 1), (3.4, 7, "D"), (3.65, 0)],
               head_x=[(2.9, 0), (3.05, 6), (3.25, 0), (3.4, 6), (3.7, 0)],
               sigh=[(2.8, 0), (2.95, -0.3), (3.05, 0.5), (3.3, -0.3), (3.4, 0.5), (3.9, 0)],
               dust=[(0, 0)]),
          looks=[(1.1, -18.0, 16.0), (2.0, -10.0, 26.0), (2.8, 0.0, 14.0), (3.6, 0.0, 20.0), (4.3, 0.0, 12.0)],
          note="MINER variant: knocks the dust off the shoulder (two pats) and chest, then two "
               "dry coughs.")
MINE.clip("idle_miner__v3", 6.0,
          reach("l", "torso", [(1.3, (1.2, -13.2, -6.6)), (1.6, (1.2, -13.2, -6.6))], 1.3, 1.6,
                ramp=0.5, ramp_out=0.45),
          reach("r", "torso", [(2.0, (-1.2, -4.0, -7.0))] + osc_pts((-1.2, -4.0, -7.0), (0, 0.9, 0), 2.1, 2, 0.34) +
                [(2.9, (-1.2, -4.0, -7.0))], 2.0, 2.9, ramp=0.45, ramp_out=0.5),
          reach("l", "torso", [(2.0, (1.2, -4.0, -7.0))] + osc_pts((1.2, -4.0, -7.0), (0, -0.9, 0), 2.1, 2, 0.34) +
                [(2.9, (1.2, -4.0, -7.0))], 2.0, 2.9, ramp=0.4, ramp_out=0.5),
          keys(head_x=[(1.4, 0), (1.55, -4), (1.65, 5, "D"), (1.9, 0)],
               dip=[(3.2, 0), (3.5, 0.5, "a"), (3.75, -0.2, "D"), (4.2, 0)],
               arm_r_x=[(3.3, 0), (3.6, -8), (3.9, 0)],
               torso_x=[(3.2, 0), (3.5, 4), (3.8, -2), (4.3, 0)]),
          looks=[(1.2, 0.0, 6.0), (2.0, 0.0, 26.0), (3.0, 0.0, 20.0), (3.6, 0.0, 10.0), (4.4, 0.0, 12.0)],
          note="MINER variant: spits in the palm, rubs the hands together, re-grips and hefts "
               "the pick.")
MINE.clip("idle_miner__v4", 7.0,
          reach("l", "head", [(1.4, (1.3, -1.5, -7.0)), (1.8, (1.3, -1.7, -6.6)), (2.0, (1.4, -1.2, -7.6)),
                              (2.6, (1.4, -1.4, -7.4)), (3.1, (1.3, -1.7, -6.6)), (3.3, (1.4, -1.2, -7.6)),
                              (4.4, (1.4, -1.2, -7.6)), (4.8, (1.3, -1.7, -6.6)), (5.0, (1.4, -1.2, -7.6))],
                1.4, 5.0, ramp=0.6, ramp_out=0.7),
          keys(head_x=[(2.0, 0), (2.2, 3), (2.4, 0), (2.6, 3), (2.8, 0), (3.3, 0), (3.5, 3), (3.7, 0),
                       (3.9, 3), (4.1, 0), (5.1, 0), (5.3, 3), (5.5, 0), (5.7, 3), (5.9, 0)],
               sigh=[(5.6, 0), (6.0, 0.6), (6.6, 0)]),
          looks=[(1.2, 0.0, 12.0), (1.8, 0.0, 8.0), (3.6, 8.0, 6.0), (5.3, -6.0, 10.0), (6.4, 0.0, 12.0)],
          offhand="bread",
          hs_props=[{"hand": "offhand", "item": "minecraft:bread", "from": 0.8, "to": 5.6}],
          note="MINER break: eats a bread crust (offhand stand-in) - three bites, chewing, a "
               "look around between bites.")


# =========================================================================== SCHOLAR
SCHO = Job(texture="settler_scholar.png", seed=71, feet=((-2.6, 0.1), (2.6, -0.2)), knee=5.0,
           lean=9.0, w0=-0.3, g0=(4.0, 26.0), head_z=2.0,
           arm_r=(-42.0, -20.0, 10.0, -37.0), hang_r=0.0, arm_l=(-38.0, 20.0, -9.0, -36.0), hang_l=0.0)
SCHO.clip("idle_scholar", 4.5,
          reach("l", "hand_r", [(1.6, (3.2, 4.0, -3.4)), (1.9, (1.4, 3.6, -4.2)), (2.1, (-0.8, 4.0, -3.6)),
                                (2.25, (-1.2, 4.2, -3.2))], 1.6, 2.25, ramp=0.4, ramp_out=0.45),
          keys(head_x=[(2.3, 0), (2.5, 5), (2.8, 0)]),
          looks=[(1.6, 6.0, 26.0), (2.2, 1.0, 26.0), (3.0, 4.0, 26.0)],
          note="SCHOLAR: hunched over the book in the right hand; the left thumbs a page over, a "
               "small nod at what it says.")
SCHO.clip("idle_scholar__v2", 5.5,
          reach("l", "head", [(1.3, (1.0, 0.2, -6.4)), (1.5, (1.0, 0.6, -6.0)), (1.7, (1.0, 0.2, -6.4)),
                              (1.9, (1.0, 0.6, -6.0)), (2.1, (1.0, 0.2, -6.4)), (3.0, (1.0, 0.3, -6.4))],
                1.3, 3.0, ramp=0.5, ramp_out=0.55),
          keys(head_x=[(3.2, 0), (3.4, 6), (3.6, 0), (3.8, 4), (4.0, 0)], torso_x=[(1.0, 0), (1.6, -5), (3.2, -5), (3.9, 0)]),
          looks=[(1.1, 4.0, 20.0), (1.6, -12.0, -8.0), (2.8, -14.0, -9.0), (3.3, 2.0, 18.0), (4.0, 4.0, 26.0)],
          note="SCHOLAR variant: looks up from the page and taps the chin in thought, the "
               "answer lands - two quick nods - back to the book.")
SCHO.clip("idle_scholar__v3", 5.0,
          reach("l", "head", [(1.3, (0.2, -3.6, -7.0)), (1.55, (0.2, -4.4, -6.8)), (1.8, (0.2, -4.2, -6.8))],
                1.3, 1.8, ramp=0.5, ramp_out=0.55),
          keys(head_x=[(1.2, 0), (1.5, -4), (1.9, -3), (2.3, 0)], head_z=[(2.0, 0), (2.3, -3), (2.8, 0)],
               sigh=[(2.2, 0), (2.6, 0.6), (3.2, 0)]),
          looks=[(1.1, 4.0, 22.0), (1.7, 3.0, 12.0), (2.6, 4.0, 24.0)],
          note="SCHOLAR variant: pushes the spectacles up the nose, blinks, settles back in.")


# =========================================================================== INNKEEPER
INNK = Job(texture="settler_innkeeper.png", seed=73, feet=((-3.1, 0.2), (3.1, -0.2)), knee=4.0,
           lean=-2.0, w0=-0.4, g0=(0.0, 3.0), head_z=2.0)
INNK.clip("idle_innkeeper", 4.5,
          reach("r", "torso", [(0.9, (-1.8, -3.8, -7.0)), (3.4, (-1.8, -4.0, -7.0))], 0.9, 3.4,
                ramp=0.6, ramp_out=0.7),
          reach("l", "hand_r", circle_pts((2.6, 5.4, -1.2), 1.1, 1.2, 3, 0.5, plane="xz"), 1.2, 2.7,
                ramp=0.45, ramp_out=0.45),
          pulses(2.7, 3.0, 3.3, 3.55, arm_r_x=-22, elbow_r=-18),
          looks=[(1.0, 0.0, 22.0), (2.7, 0.0, 22.0), (3.0, -3.0, 12.0), (3.5, 0.0, 16.0), (3.9, 0.0, 3.0)],
          note="INNKEEPER: polishes a mug with the apron hem in slow circles, lifts it to check "
               "the shine.")
INNK.clip("idle_innkeeper__v2", 4.5,
          reach("r", "torso", [(1.1, (-2.6, -3.0, -4.6)), (1.45, (-2.8, 2.2, -4.4)),
                               (1.8, (-2.6, -3.0, -4.6)), (2.15, (-2.8, 2.2, -4.4))], 1.1, 2.15,
                ramp=0.45, ramp_out=0.5),
          reach("l", "torso", [(1.2, (2.6, -3.0, -4.6)), (1.55, (2.8, 2.2, -4.4)),
                               (1.9, (2.6, -3.0, -4.6)), (2.25, (2.8, 2.2, -4.4))], 1.2, 2.25,
                ramp=0.45, ramp_out=0.5),
          keys(torso_x=[(1.1, 0), (1.5, 4), (2.2, 4), (2.7, 0)]),
          looks=[(1.0, 0.0, 22.0), (2.4, 0.0, 20.0), (3.0, 0.0, 3.0)],
          note="INNKEEPER variant: wipes both hands down the apron, twice.")
INNK.clip("idle_innkeeper__v3", 6.0,
          keys(head_x=[(3.6, 0), (3.85, 7), (4.15, 0)], head_z=[(3.4, 0), (3.8, 4), (4.4, 0)],
               arm_l_x=[(3.7, 0), (4.0, -30), (4.35, -28), (4.7, 0)], elbow_l=[(3.7, 0), (4.0, -40), (4.35, -38), (4.7, 0)],
               arm_l_z=[(3.7, 0), (4.0, -10), (4.35, -8), (4.7, 0)]),
          weight=[(1.0, -0.4), (2.2, 0.5), (4.8, 0.5)],
          looks=[(0.9, 0.0, 3.0), (1.6, -30.0, 4.0, "io"), (2.3, -26.0, 4.0), (3.1, 16.0, 3.0, "io"),
                 (3.7, 14.0, 2.0), (4.8, 14.0, 2.0), (5.4, 0.0, 3.0)],
          note="INNKEEPER variant: surveys the common room, catches a guest's eye - friendly nod "
               "and a small lift of the hand.")


# =========================================================================== WEAVER
WEAV = Job(texture="settler_weaver.png", seed=79, feet=((-2.7, 0.2), (2.7, -0.2)), knee=5.0,
           lean=6.0, w0=-0.4, g0=(5.0, 18.0))


def _weave_hold(L, r_pts=(), l_pts=()):
    r0, l0 = (-1.2, -6.0, -6.8), (1.4, -6.2, -6.8)
    return merge(hold_reach("r", "torso", [(0, r0)] + list(r_pts) + [(L, r0)], L),
                 hold_reach("l", "torso", [(0, l0)] + list(l_pts) + [(L, l0)], L))


WEAV.clip("idle_weaver", 4.0,
          _weave_hold(4.0, r_pts=[(1.3, (-1.2, -6.0, -6.8)), (1.5, (-0.4, -6.4, -6.9)), (1.7, (-1.6, -5.7, -6.8)),
                                  (1.9, (-0.4, -6.4, -6.9)), (2.1, (-1.2, -6.0, -6.8)), (2.6, (-0.6, -11.8, -7.4)),
                                  (3.1, (-0.6, -11.8, -7.4)), (3.5, (-1.2, -6.0, -6.8))],
                      l_pts=[(2.1, (1.4, -6.2, -6.8)), (2.6, (0.9, -11.2, -7.8)), (3.1, (0.9, -11.2, -7.8)),
                             (3.5, (1.4, -6.2, -6.8))]),
          keys(head_z=[(2.3, 0), (2.7, 6), (3.1, 6), (3.5, 0)]),
          looks=[(1.2, 5.0, 20.0), (2.2, 4.0, 18.0), (2.7, 2.0, -6.0), (3.1, 2.0, -6.0), (3.6, 5.0, 18.0)],
          note="WEAVER: rolls the thread between finger and thumb, lifts it to the light to "
               "sight the twist.")
WEAV.clip("idle_weaver__v2", 5.0,
          _weave_hold(5.0, r_pts=[(1.0, (-1.2, -6.0, -6.8)), (1.4, (-0.4, -7.0, -7.4)), (1.9, (-0.6, -7.4, -9.6)),
                                  (2.6, (-0.6, -7.4, -9.7)), (3.0, (-4.2, -3.0, -5.2)), (3.2, (-4.4, -2.4, -5.4)),
                                  (3.4, (-4.2, -3.0, -5.2)), (3.6, (-4.4, -2.4, -5.4)), (4.0, (-1.2, -6.0, -6.8))],
                      l_pts=[(1.0, (1.4, -6.2, -6.8)), (1.4, (0.4, -7.0, -7.4)), (1.9, (0.6, -7.4, -9.6)),
                             (2.6, (0.6, -7.4, -9.7)), (3.0, (4.2, -3.0, -5.2)), (3.2, (4.4, -2.4, -5.4)),
                             (3.4, (4.2, -3.0, -5.2)), (3.6, (4.4, -2.4, -5.4)), (4.0, (1.4, -6.2, -6.8))]),
          keys(torso_x=[(1.2, 0), (1.9, -3), (2.6, -3), (3.0, 0)], sigh=[(1.3, 0), (1.9, 0.8), (2.8, 0)]),
          looks=[(1.2, 5.0, 14.0), (1.9, 0.0, 6.0), (2.8, 3.0, 22.0), (3.8, 5.0, 18.0)],
          note="WEAVER variant: laces the fingers and pushes the palms out to stretch them, then "
               "shakes the hands loose.")
WEAV.clip("idle_weaver__v3", 5.0,
          _weave_hold(5.0),
          reach("r", "head", [(1.4, (-1.6, -1.8, 6.0)), (1.8, (-0.6, -2.4, 6.0)), (2.2, (-1.8, -1.6, 6.0)),
                              (2.6, (-0.6, -2.4, 6.0)), (3.0, (-1.6, -1.8, 6.0))], 1.4, 3.0, ramp=0.55,
                ramp_out=0.6, pole=(1.0, 0.1, -0.2)),
          keys(head_x=[(1.3, 0), (1.8, 8), (2.8, 9), (3.2, -5), (3.7, 0)], sigh=[(2.9, 0), (3.3, 0.8), (4.0, 0)]),
          looks=[(1.2, 5.0, 18.0), (3.3, 2.0, 0.0), (4.0, 5.0, 18.0)],
          note="WEAVER variant: kneads the stiff back of the neck after close work, tips the head "
               "back with a breath.")


# =========================================================================== FISHER (rod)
FISH = Job(texture="settler_fisher.png", item="rod", seed=83, feet=((-2.8, 0.3), (2.8, -0.4)),
           knee=6.0, lean=2.0, w0=-0.4, g0=(-8.0, 10.0),
           arm_r=(-12.0, -10.0, 4.0, -14.0), hang_r=0.0, arm_l=(-2.0, 0.0, -3.0, -12.0))
FISH.clip("idle_fisher", 4.5,
          reach("l", "item_r", [(1.4, (9.9, 7.0, 8.0)), (1.65, (9.9, 5.6, 8.0)), (1.8, (9.9, 7.0, 8.0)),
                                (2.05, (9.9, 5.6, 8.0)), (2.5, (9.9, 7.0, 8.0))], 1.4, 2.5,
                ramp=0.55, ramp_out=0.6),
          keys(arm_r_x=[(1.2, 0), (1.5, -10), (2.6, -10), (3.0, 0)]),
          looks=[(1.2, -8.0, 10.0), (1.6, -16.0, -24.0), (2.5, -16.0, -22.0), (3.0, -8.0, 10.0)],
          note="FISHER: rod held out, tip up, patient; lifts the tip, the free hand finds the line "
               "and gives two feeling tugs, eyes up the rod.")
FISH.clip("idle_fisher__v2", 5.5,
          reach("l", "head", [(1.3, (1.5, -4.2, -6.3)), (3.8, (1.5, -4.4, -6.3))], 1.3, 3.8,
                ramp=0.6, ramp_out=0.7),
          keys(torso_x=[(1.2, 0), (1.9, 4), (3.8, 4), (4.5, 0)]),
          weight=[(1.1, 0.6), (4.2, 0.6)],
          looks=[(1.2, -8.0, 10.0), (2.0, 8.0, 14.0), (2.8, -14.0, 16.0, "io"), (3.6, -12.0, 15.0),
                 (4.4, -8.0, 10.0)],
          note="FISHER variant: shades the eyes and scans the water, slowly, side to side.")
FISH.clip("idle_fisher__v3", 5.5,
          reach("l", "head", [(1.9, (1.3, -1.6, -7.0)), (3.0, (1.2, -1.8, -6.8))], 1.9, 3.0,
                ramp=0.5, ramp_out=0.55),
          keys(head_x=[(1.2, 0), (1.9, -10), (2.8, -14), (3.3, 2), (3.8, 0)],
               torso_x=[(1.2, 0), (2.0, -5), (2.9, -6), (3.6, 1), (4.1, 0)],
               sigh=[(1.2, 0), (2.2, 1.4, "d"), (3.0, 1.2), (3.8, 0)],
               arm_r_z=[(1.4, 0), (2.2, 5), (3.0, 4), (3.6, 0)],
               dip=[(3.4, 0), (3.6, 0.2), (3.9, 0)]),
          looks=[(1.2, -8.0, 10.0), (2.3, -6.0, -8.0), (3.6, -8.0, 12.0)],
          note="FISHER variant: a long patient yawn behind the free hand, shoulders rising, and "
               "a smack-lipped settle.")


# The innkeeper idles (idle_innkeeper, __v2..__v4) are authored by the tavern lane in
# clips/tavern/author_innkeeper.py (real ale-mug prop, wrist-bone solved). Skip them here so an
# all-idles run never overwrites those JSONs with the older specs above.
for _stem in [k for k in S if k.startswith("idle_innkeeper")]:
    del S[_stem]

SPECS = S
