"""Small authoring kit for bedrock settler clips (used by author_clips.py).

Values are the Java numbers (degreeVec degrees, posVec pixels with y up) --
identical to the bedrock/Blockbench file convention the runtime reads.

A key is (time, [x, y, z], ease) where ease names the curve of the segment
that ARRIVES at the key: "cat" (catmull-rom), "lin", "step", or a GeckoLib
easing name / short alias:
  in/out/io          -> easeIn/Out/InOutSine
  inq/outq/ioq       -> quad      inc/outc/ioc -> cubic
  inx/outx           -> expo      outb:1.2     -> easeOutBack, overshoot 1.2
"""
import json
import math
import os

ALIASES = {
    "in": "easeInSine", "out": "easeOutSine", "io": "easeInOutSine",
    "inq": "easeInQuad", "outq": "easeOutQuad", "ioq": "easeInOutQuad",
    "inc": "easeInCubic", "outc": "easeOutCubic", "ioc": "easeInOutCubic",
    "inx": "easeInExpo", "outx": "easeOutExpo",
    "inqt": "easeInQuart", "outqt": "easeOutQuart",
}

LEG = 6.0  # thigh and shin, pixels


def r(v, n=3):
    out = round(float(v), n)
    return 0.0 if out == 0 else out


def keyframe(value, ease):
    vec = [r(a) for a in value]
    if ease in (None, "lin", "linear"):
        return vec
    if ease in ("cat", "catmullrom"):
        return {"post": vec, "lerp_mode": "catmullrom"}
    if ease == "step":
        return {"post": vec, "lerp_mode": "step"}
    arg = None
    if ":" in ease:
        ease, arg = ease.split(":")
    name = ALIASES.get(ease, ease)
    if ease == "outb":
        name = "easeOutBack"
    if ease == "inb":
        name = "easeInBack"
    if ease == "iob":
        name = "easeInOutBack"
    kf = {"post": vec, "easing": name}
    if arg is not None:
        kf["easingArgs"] = [float(arg)]
    return kf


def channel(keys):
    out = {}
    for t, value, ease in keys:
        out[f"{t:.4f}".rstrip("0").rstrip(".") if t else "0.0"] = keyframe(value, ease)
    # make sure "0" is formatted like Blockbench does
    fixed = {}
    for k, v in out.items():
        fixed[k if "." in k else k + ".0"] = v
    return fixed


def leg_ik(drop, foot_z, splay_deg=0.0):
    """Planted two-bone leg: returns (thigh pitch, knee flex) in degrees.

    drop: hips lowered by this many pixels; foot_z: foot offset along +Z
    (backward) from under the hip; splay_deg: outward roll (zRot) that also
    shortens the vertical reach.
    """
    y = (12.0 - drop) / max(0.2, math.cos(math.radians(splay_deg)))
    z = foot_z
    dist = min(2 * LEG - 1e-4, math.hypot(y, z))
    cos_k = max(-1.0, min(1.0, (dist * dist - 2 * LEG * LEG) / (2 * LEG * LEG)))
    knee = math.acos(cos_k)
    thigh = math.atan2(z, y) - knee / 2.0
    return math.degrees(thigh), math.degrees(knee)


def stance_keys(times_drops, right_foot_z, left_foot_z, right_splay=0.0, left_splay=0.0,
                ease="io"):
    """Legs + shins + root position for a planted stance with a hip drop per key.

    times_drops: [(t, drop_px, ease?), ...]. Returns a dict of bone -> channel dicts.
    """
    rl, ll, rs, ls, root = [], [], [], [], []
    for item in times_drops:
        t, drop = item[0], item[1]
        e = item[2] if len(item) > 2 else ease
        rt, rk = leg_ik(drop, right_foot_z, right_splay)
        lt, lk = leg_ik(drop, left_foot_z, left_splay)
        rl.append((t, [rt, 0, right_splay], e))
        ll.append((t, [lt, 0, left_splay], e))
        rs.append((t, [rk, 0, 0], e))
        ls.append((t, [lk, 0, 0], e))
        root.append((t, [0, -drop, 0], e))
    return {
        "right_leg": {"rotation": channel(rl)},
        "left_leg": {"rotation": channel(ll)},
        "right_shin": {"rotation": channel(rs)},
        "left_shin": {"rotation": channel(ls)},
        "root": {"position": channel(root)},
    }


def merge(*dicts):
    out = {}
    for d in dicts:
        for bone, chans in d.items():
            out.setdefault(bone, {}).update(chans)
    return out


def rot(keys):
    return {"rotation": channel(keys)}


def pos(keys):
    return {"position": channel(keys)}


def scale(keys):
    return {"scale": channel(keys)}


def write_clip(folder, key, length, loop, bones, note=None):
    anim = {}
    if loop:
        anim["loop"] = True
    anim["animation_length"] = length
    anim["bones"] = bones
    data = {"format_version": "1.8.0", "animations": {f"animation.settler.{key}": anim}}
    if note:
        data["hearthstead_note"] = note
    path = os.path.join(folder, f"{key}.animation.json")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    return path


_LEGACY = None


def legacy_defs():
    """Parsed SettlerAnimations.java clips (tools/anim_check.py parser)."""
    global _LEGACY
    if _LEGACY is None:
        import importlib.util
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        spec = importlib.util.spec_from_file_location("anim_check", os.path.join(root, "anim_check.py"))
        ac = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(ac)
        src = next(s for s in ac.ANIMATION_SOURCES if s["label"] == "settler")
        _LEGACY = ac.parse_definitions(src["path"])
    return _LEGACY


def legacy_keys(const, bone, target="ROTATION"):
    """[(t, [x,y,z], ease)] for one legacy channel, ease = the legacy interpolation."""
    for b, tgt, frames in legacy_defs()[const]["channels"]:
        if b == bone and tgt == target:
            return [(t, [x, y, z], "cat" if interp == "CATMULLROM" else "lin")
                    for (t, _k, (x, y, z), interp) in frames]
    return None


def legacy_bones(const, skip=()):
    """Every legacy channel of a clip as bedrock bones (verbatim numbers and modes)."""
    out = {}
    names = {"ROTATION": "rotation", "POSITION": "position", "SCALE": "scale"}
    for b, tgt, frames in legacy_defs()[const]["channels"]:
        if b in skip:
            continue
        keys = [(t, [x, y, z], "cat" if interp == "CATMULLROM" else "lin")
                for (t, _k, (x, y, z), interp) in frames]
        out.setdefault(b, {})[names[tgt]] = channel(keys)
    return out


def legacy_meta(const):
    d = legacy_defs()[const]
    return d["length"], d["looping"]


def elbow_offset(bend_deg, upper=4.0, fore=6.0):
    """Shoulder pitch correction that keeps the HAND direction when the elbow flexes.

    Elbow flexion is negative X; returns the (positive) degrees to add to the
    upper-arm X so shoulder->hand keeps its angle.
    """
    e = math.radians(abs(bend_deg))
    return math.degrees(math.atan2(fore * math.sin(e), upper + fore * math.cos(e)))


def interp_list(points, t, length=None):
    """Piecewise-linear value at t from [(t, v), ...] (optionally wrapping for loops)."""
    pts = sorted(points)
    if t <= pts[0][0]:
        return pts[0][1]
    for (t0, v0), (t1, v1) in zip(pts, pts[1:]):
        if t0 <= t <= t1:
            return v0 + (v1 - v0) * ((t - t0) / (t1 - t0) if t1 > t0 else 0)
    return pts[-1][1]


def arm_elbow(const, arm_bone, elbows, eases=None, keep_hand=True, scale_x=1.0):
    """Legacy arm channel + an elbow channel at the SAME key times.

    elbows: [(t, flex_deg<=0), ...] interpolated to each legacy key time.
    keep_hand: shift the shoulder pitch so shoulder->hand keeps the legacy
    direction (the hand path and contact beat stay where they were).
    eases: {time: ease} overrides for both channels.
    """
    eases = eases or {}
    arm, fore = [], []
    for t, (x, y, z), ease in legacy_keys(const, arm_bone):
        e = interp_list(elbows, t)
        x2 = x * scale_x + (elbow_offset(e) if keep_hand else 0.0)
        ease = eases.get(round(t, 3), ease)
        arm.append((t, [x2, y, z], ease))
        fore.append((t, [e, 0, 0], ease))
    return {arm_bone: {"rotation": channel(arm)},
            arm_bone.replace("_arm", "_forearm"): {"rotation": channel(fore)}}


def reease(bones, bone, eases, target="rotation"):
    """Change the ease of specific keys (by time) in an already built channel."""
    ch = bones[bone][target]
    for k in list(ch):
        t = round(float(k), 3)
        if t in eases:
            v = ch[k]
            vec = v if isinstance(v, list) else v["post"]
            ch[k] = keyframe(vec, eases[t])
    return bones
