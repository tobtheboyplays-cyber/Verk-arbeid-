"""Shared helpers for the FOOD / TAVERN / SOCIAL / LIFE clips (life animator).

Builds only on the shared pipeline (hsrig, mcrig, export_mc_clip, motionkit);
it never edits them. One clip script per clip lives next to this file:

    blender -b --factory-startup --python author_<clip>.py -- [--fast|--full] [--no-export]
    blender -b --factory-startup --python ../../run_batch.py -- --fast clips/life/author_eat.py ...

Conventions (mcrig): Minecraft model space, pixels, +Y DOWN, the settler faces
-Z, +X is the settler's LEFT, ground at y = 24. Hip (torso pivot) at y = 12,
shoulders at torso-local (+-6, -10, 0), neck at torso-local (0, -12, 0).
Elbow flex is NEGATIVE x, knee flex POSITIVE x (bend bones, rotation only).

Every clip script:
  1. keys animator controls on CTRL (Bezier F-curves, per-key easing; cyclic
     for loops so the loop point is C1-continuous),
  2. solve(t) turns them into channels: FK body + two-bone IK hands on
     targets + planted-foot leg IK + head aim + lagged cloak,
  3. bakes at 60 Hz, exports Bedrock JSON (runtime Catmull-Rom verified),
  4. renders a Workbench preview (side MP4 + contact sheet) into
     videos/blender/clips/life/.
"""

from __future__ import annotations

import json
import math
import os
import shutil
import sys

import bpy
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
PIPE = os.path.abspath(os.path.join(HERE, "..", ".."))
if PIPE not in sys.path:
    sys.path.insert(0, PIPE)
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import hsrig  # noqa: E402
import mcrig  # noqa: E402
import export_mc_clip as ex  # noqa: E402

REPO = os.path.abspath(os.path.join(PIPE, "..", "..", ".."))
WORK = os.environ.get("HS_PIPELINE", r"C:\Users\tobia\Hearthstead-Claude\tools\blender-pipeline")
VIDEOS = os.environ.get("HS_LIFE_VIDEOS", r"C:\Users\tobia\Hearthstead-Claude\videos\blender\clips\life")
ANIM_DIR = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "animations", "settler")
TEX_DIR = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "textures",
                       "entity", "settler")

GROUND = 24.0
SHOULDER = {"right": np.array([-6.0, -10.0, 0.0]), "left": np.array([6.0, -10.0, 0.0])}
HIP = {"right": np.array([-2.6, -12.0, 0.0]), "left": np.array([2.6, -12.0, 0.0])}
# relaxed stance: soles a touch wider than the hips, left foot a little forward
FEET = (np.array([-2.9, GROUND, 0.5]), np.array([2.9, GROUND, -0.5]))

c = hsrig.ctrl
FPS = hsrig.FPS

# easing presets for key tuples (t, v, *PRESET): applied to the segment LEAVING the key
ACC = ("CUBIC", "EASE_IN")      # accelerate into the next key (strike / drop)
ACC2 = ("QUAD", "EASE_IN")
DEC = ("SINE", "EASE_OUT")      # decelerate into the next key (settle)
DEC3 = ("CUBIC", "EASE_OUT")
INOUT = ("SINE", "EASE_IN_OUT")
LIN = ("LINEAR", "AUTO")


def args():
    return hsrig.parse_args()


# --------------------------------------------------------------------------- scene
def scene(texture="settler_none.png", ground=True):
    hsrig.reset()
    objs = hsrig.build_scene(os.path.join(TEX_DIR, texture), None)
    if ground:
        hsrig.prop_box("ground", (-60, 24, -60), (120, 1, 120), (0.30, 0.45, 0.22, 1))
    return objs


def box(name, frm, size, colour):
    return hsrig.prop_box(name, frm, size, colour)


# --------------------------------------------------------------------------- keys
def key(props, cyclic):
    """props: {name: [(t, v[, interp, easing]), ...]} -> F-curves on CTRL."""
    for prop, ks in props.items():
        hsrig.key_curve(prop, ks, cyclic=cyclic)


def key_vec(name, ks, cyclic):
    """ks: [(t, (x, y, z)[, interp, easing])] -> curves name_x / _y / _z."""
    for i, a in enumerate("xyz"):
        hsrig.key_curve(f"{name}_{a}", [(k[0], k[1][i], *k[2:]) for k in ks], cyclic=cyclic)


def cv(name, t):
    return np.array([c(name + "_x", t), c(name + "_y", t), c(name + "_z", t)])


def smoothstep(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3.0 - 2.0 * x)


# --------------------------------------------------------------------------- solve pieces
def _euler_near(r, prev):
    return tuple(hsrig.euler_deg_continuous(r, prev))


def arm_ik(ch, world, side, target, pole, prev, lower=6.0, twist=0.0):
    """Two-bone arm IK: palm (arm-local y = 10) onto a MODEL-space target.

    pole: where the elbow points, in torso space (+x left, +y down, +z back).
    twist: extra forearm roll (deg) about its own axis (forearm y).
    Returns the palm error in px.
    """
    tl = mcrig.xform(np.linalg.inv(world["torso"]), np.asarray(target, float))
    r, flex, reached = mcrig.two_bone(SHOULDER[side], tl, mcrig.UPPER_ARM, lower,
                                      np.asarray(pole, float), -1)
    ch[side + "_arm"] = {"rot": _euler_near(r, prev.get(side + "_arm"))}
    ch[side + "_forearm"] = {"rot": (math.degrees(flex), twist, 0.0)}
    prev[side + "_arm"] = ch[side + "_arm"]["rot"]
    return float(np.linalg.norm(reached - tl))


def arm_ik_local(ch, side, target_torso, pole, prev, lower=6.0, twist=0.0):
    """Same, with the target already in TORSO space (hands that ride the chest)."""
    r, flex, reached = mcrig.two_bone(SHOULDER[side], np.asarray(target_torso, float),
                                      mcrig.UPPER_ARM, lower, np.asarray(pole, float), -1)
    ch[side + "_arm"] = {"rot": _euler_near(r, prev.get(side + "_arm"))}
    ch[side + "_forearm"] = {"rot": (math.degrees(flex), twist, 0.0)}
    prev[side + "_arm"] = ch[side + "_arm"]["rot"]
    return float(np.linalg.norm(reached - np.asarray(target_torso, float)))


def leg_ik(ch, feet, prev, knee=(0.0, 0.0, -1.0), knee_out=0.12, world=None):
    """Planted soles: legs + knees solved from the root pose already in ch."""
    world = world or mcrig.pose_matrices(ch)
    inv_root = np.linalg.inv(world["root"])
    for side, foot in (("right", feet[0]), ("left", feet[1])):
        hip = HIP[side]
        fl = mcrig.xform(inv_root, np.asarray(foot, float))
        pole = inv_root[:3, :3] @ (np.asarray(knee, float) + np.array([knee_out * np.sign(hip[0]), 0, 0]))
        r, flex, _ = mcrig.two_bone(hip, fl, mcrig.THIGH, mcrig.SOLE_Y - mcrig.THIGH, pole, +1)
        ch[side + "_leg"] = {"rot": _euler_near(r, prev.get(side + "_leg"))}
        ch[side + "_shin"] = {"rot": (math.degrees(flex), 0.0, 0.0)}
        prev[side + "_leg"] = ch[side + "_leg"]["rot"]


def sole(world, side):
    return mcrig.xform(world[side + "_shin"], (0, 6, 0))


def palm(world, side):
    return mcrig.xform(world[side + "_forearm"], (0, 6, 0))


def head_aim(world, target):
    """(pitch, yaw) degrees that aim the eyes (4 px above the neck) at a model point."""
    torso = world["torso"]
    neck = mcrig.xform(torso, (0, -12, 0))
    d = np.linalg.inv(torso[:3, :3]) @ (np.asarray(target, float) - (neck + torso[:3, :3] @ np.array([0, -4, 0])))
    yaw = math.degrees(math.atan2(-d[0], -d[2]))
    pitch = math.degrees(math.atan2(d[1], math.hypot(d[0], d[2])))
    return pitch, yaw


def lagged_rate(fn, t, lag=0.05, dt=1.0 / 60.0):
    """d/dt of fn at (t - lag): the drag a trailing cloth sees."""
    return (fn(t - lag) - fn(t - lag - dt)) / dt


def cloak(base_x, torso_x, pitch_rate, yaw_rate=0.0, root_vy=0.0, k_pitch=0.45, k_rate=0.05,
          k_yaw=0.02, k_vy=0.6):
    """Short shoulder cape: hangs plumb (counters part of the spine pitch), trails the
    spine's pitch speed, swings sideways against yaw speed, lifts when the body drops."""
    x = base_x + k_pitch * max(0.0, torso_x) - k_rate * pitch_rate - k_vy * root_vy
    z = -k_yaw * yaw_rate
    return (max(-16.0, min(28.0, x)), 0.0, max(-10.0, min(10.0, z)))


def breath(t, period, amp=1.0, phase=0.0):
    """0..1 inhale curve (sine, slightly longer exhale)."""
    u = ((t / period) + phase) % 1.0
    return 0.5 - 0.5 * math.cos(2 * math.pi * u)


def breath_scale(s, amp=0.018):
    """Chest expansion from the hip pivot (runtime SCALE channel on torso)."""
    return (1.0 + 0.8 * amp * s, 1.0 + amp * s, 1.0 + 0.8 * amp * s)


# --------------------------------------------------------------------------- export
def _fmt_t(t):
    return f"{t:.4f}".rstrip("0").rstrip(".") if t else "0.0"


def export(const, length, loop, times, samples, keep_times, meta, bones=None,
           rot_tol=0.2, pos_tol=0.015, scale_tol=0.0012, write=True, props=None):
    """Bedrock JSON with rotation/position (+ torso scale when solve() sets it)."""
    name = "animation.settler." + const.lower()
    bones = bones or mcrig.EXPORT_BONES
    chan = hsrig.to_export_channels(times, samples, bones)
    doc, report = ex.build_bedrock(name, length, loop, times, chan, rot_tol=rot_tol,
                                   pos_tol=pos_tol, keep_times=keep_times, meta=meta)
    anim = doc["animations"][name]
    if props:   # display-only held props (motion engine), seconds of this clip's local time
        anim["hearthstead_props"] = props
    # torso breathing scale (absolute scale, 1 = rest)
    if "torso" in bones and any("scale" in s.get("torso", {}) for s in samples):
        vecs = [list(s.get("torso", {}).get("scale", (1.0, 1.0, 1.0))) for s in samples]
        chan.setdefault("torso", {})["scale"] = vecs
        keep = [min(range(len(times)), key=lambda i: abs(times[i] - kt)) for kt in keep_times]
        idx, e = ex.reduce_vector(times, vecs, scale_tol, keep)
        anim["bones"].setdefault("torso", {})["scale"] = {
            _fmt_t(times[i]): {"post": [round(v, 4) for v in vecs[i]], "lerp_mode": "catmullrom"}
            for i in idx}
        report["torso.scale"] = {"keys": len(idx), "max_err": round(e, 5)}
    # verify: exported keys, evaluated with the runtime Catmull-Rom, match the bake
    worst = 0.0
    for b, kinds in chan.items():
        for kind, vecs in kinds.items():
            if b not in anim["bones"] or kind not in anim["bones"][b]:
                continue
            for t, v in zip(times, vecs):
                got = ex.sample(doc, name, b, kind, t)
                worst = max(worst, max(abs(a - q) for a, q in zip(got, v)))
    path = os.path.join(ANIM_DIR, const.lower() + ".animation.json")
    if write:
        ex.write(doc, path)
    nkeys = sum(v["keys"] for v in report.values())
    print("EXPORTED", path if write else "(dry run)", nkeys, "keys, roundtrip max err", round(worst, 4))
    return path, doc, report, worst


# --------------------------------------------------------------------------- checks
def loop_seam(samples, bones=None):
    """(max |value(0) - value(end)|, worst seam-accel / interior-accel ratio, channel).

    The seam is smooth when the second difference across it (a[-2], a[0]=a[-1], a[1])
    is no larger than the channel's own interior accelerations (ratio <= ~1.2)."""
    bones = bones or mcrig.EXPORT_BONES
    worst_pos, worst_ratio, where = 0.0, 0.0, ""
    for b in bones:
        for kind in ("rot", "pos"):
            a = np.array([s.get(b, {}).get(kind, (0, 0, 0)) for s in samples], float)
            worst_pos = max(worst_pos, float(np.max(np.abs(a[0] - a[-1]))))
            acc = np.abs(a[2:] - 2 * a[1:-1] + a[:-2])
            interior = float(np.max(acc)) if len(acc) else 0.0
            seam = float(np.max(np.abs(a[1] - 2 * a[0] + a[-2])))
            if interior < 1e-4:
                continue
            ratio = seam / interior
            if ratio > worst_ratio:
                worst_ratio, where = ratio, f"{b}.{kind}"
    return round(worst_pos, 4), round(worst_ratio, 3), where


def foot_slide(samples, feet):
    worst = 0.0
    for s in samples:
        w = mcrig.pose_matrices(s)
        for side, f in (("right", feet[0]), ("left", feet[1])):
            worst = max(worst, float(np.linalg.norm(sole(w, side) - np.asarray(f))))
    return round(worst, 3)


def ends_at_rest(samples, bones=None, eps=0.05):
    """For one-shots: first and last frame equal zero on every exported channel."""
    bones = bones or mcrig.EXPORT_BONES
    worst = 0.0
    for s in (samples[0], samples[-1]):
        for b in bones:
            for kind in ("rot", "pos"):
                worst = max(worst, max(abs(v) for v in s.get(b, {}).get(kind, (0, 0, 0))))
    return round(worst, 4)


def value_at(times, samples, t, bone, kind="rot"):
    i = min(range(len(times)), key=lambda k: abs(times[k] - t))
    return [round(v, 2) for v in samples[i].get(bone, {}).get(kind, (0, 0, 0))]


def extreme_time(times, series, mode="max"):
    i = int(np.argmax(series) if mode == "max" else np.argmin(series))
    return round(times[i], 3), round(float(series[i]), 3)


def save_report(slug, data):
    out = os.path.join(WORK, "out", "life", slug)
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "report.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, indent=1)


# --------------------------------------------------------------------------- preview
def cameras(side=((-3.9, -0.3, 1.1), (0.0, -0.3, 0.98)),
            front34=((-2.7, -2.9, 1.45), (0.0, -0.15, 1.0)), lens=40):
    return {"side": hsrig.camera("cam_side", side[0], side[1], lens=lens),
            "front34": hsrig.camera("cam_front34", front34[0], front34[1], lens=lens)}


def stills(slug, length, cams=None):
    """--stills: QA only, two 12-frame Workbench sheets (side, front34), no video."""
    sc = bpy.context.scene
    hsrig.setup_render(res=(480, 360))
    sc.render.engine = "BLENDER_WORKBENCH"
    sc.display.shading.light = "STUDIO"
    sc.display.shading.color_type = "TEXTURE"
    cams = cams or cameras()
    n = int(round(length * FPS))
    idx = [int(round(i * n / 12)) for i in range(12)]
    out = os.path.join(WORK, "out", "life", slug, "stills")
    for view in ("side", "front34"):
        d = os.path.join(out, view)
        hsrig.render_frames(cams[view], d, idx)
        hsrig._sheet([os.path.join(d, f"f{f:04d}.png") for f in idx], os.path.join(out, f"sheet_{view}.png"))
    print("STILLS", out)


def preview(slug, length, a, cams=None, loops=2):
    """--fast: side MP4 + side sheet. --full: + front34 and half-speed MP4s and sheets.
    Files land flat in VIDEOS as <slug>_side.mp4, <slug>_sheet_side.png, ..."""
    if "--stills" in sys.argv:
        stills(slug, length, cams)
        return []
    if not (a["fast"] or a["full"]):
        return []
    tmp = os.path.join(WORK, "out", "life", slug, "preview")
    if os.path.isdir(tmp):
        shutil.rmtree(tmp)
    cams = cams or cameras()
    hsrig.preview(tmp, length, fast=not a["full"], full=a["full"], cams=cams, loops=loops)
    if not a["full"]:
        # cheap QA extra: a front-three-quarter contact sheet (12 stills, no video)
        n = int(round(length * FPS))
        idx = [int(round(i * n / 12)) for i in range(12)]
        d = os.path.join(tmp, "_sheet_front")
        hsrig.render_frames(cams["front34"], d, idx)
        hsrig._sheet([os.path.join(d, f"f{f:04d}.png") for f in idx], os.path.join(tmp, "sheet_front34.png"))
    os.makedirs(VIDEOS, exist_ok=True)
    out = []
    for fn in os.listdir(tmp):
        src = os.path.join(tmp, fn)
        if os.path.isfile(src) and (fn.endswith(".mp4") or fn.endswith(".png")):
            dst = os.path.join(VIDEOS, f"{slug}_{fn}")
            shutil.copyfile(src, dst)
            out.append(dst)
    print("PREVIEWS", json.dumps(out))
    return out


def curve_seams(length, eps=1.0 / 120.0):
    """Per CTRL curve: slope just after 0 vs just before `length` (units/s). Loops only."""
    ob = hsrig.controls()
    out = {}
    for fc in ob.animation_data.action.fcurves:
        name = fc.data_path[2:-2]
        a = (fc.evaluate(eps * FPS) - fc.evaluate(0)) / eps
        b = (fc.evaluate(length * FPS) - fc.evaluate((length - eps) * FPS)) / eps
        if abs(a - b) > 0.5:
            out[name] = (round(a, 2), round(b, 2))
    return out


# one-shots start and end on the exact runtime rest pose (every part zero):
# soles directly under the hip pivots, legs straight.
FEET0 = (np.array([-2.6, GROUND, 0.0]), np.array([2.6, GROUND, 0.0]))


def edge_rest(ch, t, length, ramp=0.05):
    """Pull every channel to exact rest over the first/last `ramp` seconds of a one-shot
    (removes the sub-degree IK residue so the clip hands over with no pop)."""
    w = max(1.0 - smoothstep(t / ramp), smoothstep((t - (length - ramp)) / ramp))
    if w <= 0:
        return ch
    for b, kinds in ch.items():
        for k, v in list(kinds.items()):
            rest = 1.0 if k == "scale" else 0.0
            kinds[k] = tuple(x + (rest - x) * w for x in v)
    return ch
