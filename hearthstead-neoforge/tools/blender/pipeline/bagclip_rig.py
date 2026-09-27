"""Shared solver + preview helpers for the sack/bag clips (second animator).

Used by author_bag_down.py, author_pickup_to_bag.py and author_bag_to_chest.py.
It only builds on hsrig/mcrig/export_mc_clip and never edits them.

Conventions (same as mcrig): Minecraft model space, pixels, +Y DOWN, the
settler faces -Z, +X is the settler's LEFT, ground at y = 24.

Hard runtime rules these clips obey:
  * root is TRANSLATION ONLY. The grounded sack / lumber frame is a root child
    that SettlerModel re-projects from the world anchor, cancelling root
    offsets but not root rotation, so any root yaw would swing the prop.
    Turns go into the torso; the feet stay planted by IK.
  * elbow flex is negative x, knee flex positive x (bend bones, x only).
  * lengths and contact ticks are the Java clips' (gameplay depends on them).
"""

from __future__ import annotations

import json
import math
import os
import shutil
import subprocess
import sys

import bpy
import numpy as np
from mathutils import Matrix

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import hsrig  # noqa: E402
import mcrig  # noqa: E402
import export_mc_clip as ex  # noqa: E402

REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
WORK = os.environ.get("HS_PIPELINE", r"C:\Users\tobia\Hearthstead-Claude\tools\blender-pipeline")
VIDEOS = os.environ.get("HS_CLIP_VIDEOS", r"C:\Users\tobia\Hearthstead-Claude\videos\blender\clips")
ANIM_DIR = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "animations", "settler")
TEX_DIR = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "textures",
                       "entity", "settler")
AXE = os.path.join(WORK, "ref", "assets", "minecraft", "textures", "item", "iron_axe.png")

GROUND = 24.0
SHOULDER = {"right": np.array([-6.0, -10.0, 0.0]), "left": np.array([6.0, -10.0, 0.0])}
HIP = {"right": np.array([-2.6, -12.0, 0.0]), "left": np.array([2.6, -12.0, 0.0])}

c = hsrig.ctrl


def argv():
    return sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []


# --------------------------------------------------------------------------- keys
def key(props):
    """props: {name: [(t, v[, interp, easing]), ...]} -> non-cyclic Bezier F-curves on CTRL."""
    for prop, ks in props.items():
        hsrig.key_curve(prop, ks, cyclic=False)


def key_vec(name, ks):
    """ks: [(t, (x, y, z)[, interp, easing])] -> three curves name_x/_y/_z."""
    for i, a in enumerate("xyz"):
        hsrig.key_curve(f"{name}_{a}", [(k[0], k[1][i], *k[2:]) for k in ks], cyclic=False)


def cv(name, t):
    return np.array([c(name + "_x", t), c(name + "_y", t), c(name + "_z", t)])


def smoothstep(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3.0 - 2.0 * x)


# --------------------------------------------------------------------------- stance
def stance_feet(right_rot=(-3.0, 0.0, -2.0), left_rot=(3.0, 0.0, 2.0)):
    """Planted soles taken from the Java neutral stance so the clip starts on it."""
    w = mcrig.pose_matrices({"right_leg": {"rot": right_rot}, "left_leg": {"rot": left_rot}})
    r = mcrig.xform(w["right_shin"], (0, 6, 0))
    l = mcrig.xform(w["left_shin"], (0, 6, 0))
    r[1] = l[1] = GROUND
    return r, l


# --------------------------------------------------------------------------- solve pieces
def two_bone_safe(root_pt, target, l1, l2, pole, bend_sign, soft=None, prev_p=None, max_step_deg=None):
    """mcrig.two_bone with (a) a bend plane taken from the pole alone, so a straight
    or nearly straight limb cannot flip its elbow plane between frames (same fix as
    clips/craft/craftkit.two_bone), and (b) optional soft reach: past soft*(l1+l2)
    the effective distance approaches full extension exponentially, which removes
    the infinite d(flex)/d(dist) at full reach (the one-frame -90 -> 0 elbow snap).
    Returns (upper rot matrix, flex radians)."""
    root_pt = np.asarray(root_pt, float)
    d = np.asarray(target, float) - root_pt
    n = float(np.linalg.norm(d))
    dn = d / max(n, 1e-9)
    full = l1 + l2
    if soft is not None:
        ds = soft * full
        if n > ds:
            n = ds + (full - ds) * (1.0 - math.exp(-(n - ds) / (full - ds)))
    dist = min(max(n, abs(l1 - l2) + 1e-4), full)
    cos_a = (l1 * l1 + dist * dist - l2 * l2) / (2 * l1 * dist)
    a = math.acos(max(-1.0, min(1.0, cos_a)))
    cos_k = (l1 * l1 + l2 * l2 - dist * dist) / (2 * l1 * l2)
    interior = math.pi - math.acos(max(-1.0, min(1.0, cos_k)))
    p = np.asarray(pole, float)
    p = p - dn * float(p @ dn)
    if np.linalg.norm(p) < 1e-6:
        p = np.array([0.0, 0.0, 1.0 if bend_sign < 0 else -1.0])
        p = p - dn * float(p @ dn)
    p /= np.linalg.norm(p)
    if prev_p is not None and max_step_deg is not None:
        # rate-limit the elbow plane: turn last frame's joint direction toward the
        # pole's, about the limb axis, by at most max_step_deg. A target sweeping
        # past the pole axis then rolls the elbow round instead of flipping it.
        q = np.asarray(prev_p, float) - dn * float(np.asarray(prev_p, float) @ dn)
        if np.linalg.norm(q) > 1e-6:
            q /= np.linalg.norm(q)
            ang = math.atan2(float(np.cross(q, p) @ dn), float(q @ p))
            step = max(-math.radians(max_step_deg), min(math.radians(max_step_deg), ang))
            p = q * math.cos(step) + np.cross(dn, q) * math.sin(step)
            p /= np.linalg.norm(p)
    two_bone_safe.last_p = p
    y = dn * math.cos(a) + p * math.sin(a)
    # in-plane unit perpendicular to the upper bone, on the target's side. Written
    # analytically from (dn, p) so it stays defined both when the limb is straight
    # (y ~ dn) and when it is deeply folded (y ~ p), where projecting p onto the
    # plane normal to y collapses to zero and the frame used to spin 180 deg.
    toward = dn * math.sin(a) - p * math.cos(a)
    z = -toward if bend_sign < 0 else toward
    x = np.cross(y, z)
    return np.column_stack([x, y, z]), bend_sign * interior


ARM_SOFT = 0.92
MIN_REACH = 3.5
POLE_STEP_DEG = 6.0     # max elbow-plane roll per 60 fps frame (360 deg/s)
_POLE_REF, _POLE_CAND = {}, {}


def begin_frame(first):
    """Call once at the top of every solve(t): the elbow-plane memory advances one
    frame (every IK call inside a frame, Newton probes included, sees the same ref)."""
    if first:
        _POLE_REF.clear()
        _POLE_CAND.clear()
    else:
        _POLE_REF.update(_POLE_CAND)
DEBUG = None      # set to a predicate(side) to trace arm IK inputs


def euler_principal(m3, prev):
    """ZYX Euler (deg) on the principal branch (|y| <= 90), 360-unwrapped toward prev.

    hsrig.euler_deg_continuous may hop to the equivalent (x+180, 180-y, z+180)
    branch after a near-gimbal frame and then never come back, which leaves
    boundary poses like (154,147,146) that cross-fade badly into Java's
    (-8,8,4). These clips never need the arm past |y| = 90, so stay principal.
    """
    x, y, z = mcrig.euler_from_matrix(m3)
    a = [math.degrees(x), math.degrees(y), math.degrees(z)]
    if prev is None:
        return a
    return [c + 360.0 * round((p - c) / 360.0) for p, c in zip(prev, a)]


def arm_ik(ch, world, side, target, pole, prev, lower=6.0, match=None, match_w=0.0):
    """Two-bone arm IK: palm (arm-local y 10) onto a model-space target.

    match/match_w: a Java boundary rotation (deg). The IK frame is twisted about
    the upper arm's own axis toward it, so the first/last frame carries the
    same Euler triple as the neighbouring clip instead of an equivalent one
    (a cross-fade between two equivalent triples swings the arm).
    """
    tl = mcrig.xform(np.linalg.inv(world["torso"]), target)
    # safety net: a target inside the folded-arm radius has no stable direction
    d0 = tl - SHOULDER[side]
    n0 = float(np.linalg.norm(d0))
    if n0 < MIN_REACH:
        tl = SHOULDER[side] + (d0 / max(n0, 1e-6)) * MIN_REACH
    if DEBUG and DEBUG(side):
        d = tl - SHOULDER[side]
        dn = d / np.linalg.norm(d)
        pp = np.asarray(pole, float) / np.linalg.norm(pole)
        print("IKDBG", side, np.round(d, 2), round(float(np.linalg.norm(d)), 2), round(float(pp @ dn), 3))
    r, flex = two_bone_safe(SHOULDER[side], tl, mcrig.UPPER_ARM, lower,
                            np.asarray(pole, float), -1, soft=ARM_SOFT,
                            prev_p=_POLE_REF.get(side), max_step_deg=POLE_STEP_DEG)
    _POLE_CAND[side] = two_bone_safe.last_p
    if DEBUG and DEBUG(side):
        print("IKR", side, "y", np.round(r[:, 1], 2), "x", np.round(r[:, 0], 2), "p", np.round(two_bone_safe.last_p, 2),
              "ref", None if _POLE_REF.get(side) is None else np.round(_POLE_REF[side], 2), "flex", round(math.degrees(flex)))
    if match is not None and match_w > 1e-4:
        rb = mcrig.rot_zyx(*[math.radians(v) for v in match])
        y = r[:, 1]
        xb = rb[:, 0] - y * float(rb[:, 0] @ y)
        # only twist once the limb already points where the boundary pose points;
        # twisting a limb aimed elsewhere toward a foreign frame can spin it 180
        match_w *= smoothstep((float(y @ rb[:, 1]) - 0.85) / 0.13)
        if np.linalg.norm(xb) > 1e-6 and match_w > 1e-4:
            xb /= np.linalg.norm(xb)
            ang = math.atan2(float(np.cross(r[:, 0], xb) @ y), float(r[:, 0] @ xb))
            r = r @ mcrig.ry(match_w * ang)
    ch[side + "_arm"] = {"rot": tuple(euler_principal(r, prev.get(side + "_arm")))}
    ch[side + "_forearm"] = {"rot": (math.degrees(flex), 0.0, 0.0)}


def leg_ik(ch, world, feet, prev, knee_out=0.15):
    inv_root = np.linalg.inv(world["root"])
    for side, foot in (("right", feet[0]), ("left", feet[1])):
        hip = HIP[side]
        fl = mcrig.xform(inv_root, foot)
        pole = np.array([knee_out * np.sign(hip[0]), 0.0, -1.0])
        r, flex = two_bone_safe(hip, fl, mcrig.THIGH, mcrig.SOLE_Y - mcrig.THIGH, pole, +1)
        ch[side + "_leg"] = {"rot": tuple(euler_principal(r, prev.get(side + "_leg")))}
        ch[side + "_shin"] = {"rot": (math.degrees(flex), 0.0, 0.0)}


def head_look(ch, world, target, w_pitch=0.6, w_yaw=0.7, nod=0.0, limit=(35.0, 50.0)):
    """Head aims at a model-space point (eyes ~4 px above the neck pivot)."""
    torso = world["torso"]
    neck = mcrig.xform(torso, (0, -12, 0))
    d = np.linalg.inv(torso[:3, :3]) @ (np.asarray(target, float) - (neck + torso[:3, :3] @ np.array([0, -4, 0])))
    yaw = math.degrees(math.atan2(-d[0], -d[2]))
    pitch = math.degrees(math.atan2(d[1], math.hypot(d[0], d[2])))
    pitch = max(-limit[0], min(limit[0] + 20, w_pitch * pitch + nod))
    yaw = max(-limit[1], min(limit[1], w_yaw * yaw))
    ch["head"] = {"rot": (pitch, yaw, 0.0)}


def cloak(t, torso_x, torso_x_fn, lag=0.05, dt=1.0 / 60.0):
    """Short shoulder cape: hangs with gravity (half the spine pitch) and trails pitch speed."""
    v = (torso_x_fn(max(0.0, t - lag)) - torso_x_fn(max(0.0, t - lag - dt))) / dt
    return (max(-6.0, min(26.0, 2.0 + 0.5 * torso_x - 0.05 * v)), 0.0, 0.0)


def arm_ik_point(ch, world, side, target, local_pt, pole, prev, match=None, match_w=0.0, p0=None):
    """Arm IK that puts a FOREARM-LOCAL point (e.g. the centre of the drawn held
    block) on a model-space target: Newton iterations on the palm goal of the
    ordinary two-bone solve, warm-started from the previous frame's goal, so the
    result is smooth frame to frame. Returns (error px, palm goal used).
    """
    target = np.asarray(target, float)

    def held_for(p):
        arm_ik(ch, world, side, p, pole, prev, match=match, match_w=match_w)
        return mcrig.xform(mcrig.pose_matrices(ch)[side + "_forearm"], local_pt)

    p = np.asarray(p0, float).copy() if p0 is not None else target.copy()
    h = held_for(p)
    for _ in range(16):
        e = target - h
        if float(np.linalg.norm(e)) < 0.01:
            break
        J = np.zeros((3, 3))
        for k in range(3):
            dp = np.zeros(3)
            dp[k] = 0.05
            J[:, k] = (held_for(p + dp) - h) / 0.05
        step = np.linalg.solve(J.T @ J + 0.02 * np.eye(3), J.T @ e)   # damped LS
        n = float(np.linalg.norm(step))
        if n > 3.0:
            step *= 3.0 / n
        p = p + step
        h = held_for(p)
    return float(np.linalg.norm(target - h)), p


def held_block_local(right=False):
    """Centre of the drawn block item in forearm-local pixels (constant)."""
    return mcrig.xform(block_in_hand_matrix(np.eye(4), right), (8, 8, 8))


def _quat(m):
    t = np.trace(m)
    if t > 0:
        r = math.sqrt(1 + t) * 2
        q = np.array([0.25 * r, (m[2, 1] - m[1, 2]) / r, (m[0, 2] - m[2, 0]) / r, (m[1, 0] - m[0, 1]) / r])
    else:
        i = int(np.argmax([m[0, 0], m[1, 1], m[2, 2]]))
        j, k = (i + 1) % 3, (i + 2) % 3
        r = math.sqrt(1 + m[i, i] - m[j, j] - m[k, k]) * 2
        q = np.zeros(4)
        q[0] = (m[k, j] - m[j, k]) / r
        q[1 + i] = 0.25 * r
        q[1 + j] = (m[j, i] + m[i, j]) / r
        q[1 + k] = (m[k, i] + m[i, k]) / r
    return q / np.linalg.norm(q)


def _mat(q):
    w, x, y, z = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def slerp_rot(a_deg, b_deg, w, prev=None):
    """Shortest-arc blend of two ZYX Euler rotations; result Euler near `prev`."""
    qa = _quat(mcrig.rot_zyx(*[math.radians(v) for v in a_deg]))
    qb = _quat(mcrig.rot_zyx(*[math.radians(v) for v in b_deg]))
    if float(qa @ qb) < 0:
        qb = -qb
    d = max(-1.0, min(1.0, float(qa @ qb)))
    th = math.acos(d)
    if th < 1e-6:
        q = qa
    else:
        q = (math.sin((1 - w) * th) * qa + math.sin(w * th) * qb) / math.sin(th)
    return euler_principal(_mat(q / np.linalg.norm(q)), prev if prev is not None else list(b_deg))


def blend_to_pose(ch, pose, w, bones=("right_arm", "left_arm", "right_forearm", "left_forearm")):
    """Pull toward a boundary pose: shortest-arc (slerp) for the limbs, plain lerp
    for the x-only bend bones. At w = 1 the channel equals the boundary triple."""
    if w <= 1e-4:
        return
    for b in bones:
        want = pose.get(b, {}).get("rot", (0.0, 0.0, 0.0))
        have = ch[b]["rot"]
        if b in mcrig.ROTATION_ONLY:
            ch[b] = {"rot": tuple(h + (x - h) * w for h, x in zip(have, want))}
        elif w >= 1.0 - 1e-6:
            ch[b] = {"rot": tuple(want)}
        else:
            ch[b] = {"rot": tuple(slerp_rot(have, want, w, prev=have))}


def edge_weight(t, length, ramp=0.10, start=True, end=True):
    """1 on the first/last frame, 0 inside: how strongly to match the Java boundary."""
    a = (1.0 - smoothstep(t / ramp)) if start else 0.0
    b = smoothstep((t - (length - ramp)) / ramp) if end else 0.0
    return max(a, b)


def knee_point(world, side):
    """Front-top of the knee cap (for a hand pushing off the knee)."""
    return mcrig.xform(world[side + "_leg"], (0.0, 6.0, -2.4))


def palm(world, side):
    return mcrig.xform(world[side + "_forearm"], (0, 6, 0))


# --------------------------------------------------------------------------- checks
def contact_report(times, samples, feet, probes):
    """Foot slide + per-probe palm error. probes: {label: (side, fn(t)->target|None)}."""
    foot_err = 0.0
    errs = {k: 0.0 for k in probes}
    for t, s in zip(times, samples):
        w = mcrig.pose_matrices(s)
        for side, foot in (("right", feet[0]), ("left", feet[1])):
            foot_err = max(foot_err, float(np.linalg.norm(mcrig.xform(w[side + "_shin"], (0, 6, 0)) - foot)))
        for k, (side, fn) in probes.items():
            tgt = fn(t)
            if tgt is not None:
                errs[k] = max(errs[k], float(np.linalg.norm(palm(w, side) - tgt)))
    out = {"foot_slide_px_max": round(foot_err, 3)}
    out.update({f"palm_err_px_{k}": round(v, 3) for k, v in errs.items()})
    return out


def snap_report(times, samples, bones=None, top=4):
    """Largest true per-frame rotation (deg, angle of R_prev^T R_cur) per bone.
    This is what pops in game; Euler deltas can lie either way."""
    bones = bones or [b for b in mcrig.EXPORT_BONES if b != "root"] + ["root"]
    out = []
    for b in bones:
        best = (0.0, 0.0)
        prev = None
        for t, s_ in zip(times, samples):
            r = mcrig.rot_zyx(*[math.radians(v) for v in s_.get(b, {}).get("rot", (0, 0, 0))])
            if prev is not None:
                c = (np.trace(prev.T @ r) - 1.0) / 2.0
                ang = math.degrees(math.acos(max(-1.0, min(1.0, c))))
                if ang > best[0]:
                    best = (ang, t)
            prev = r
        out.append((round(best[0], 1), round(best[1], 3), b))
    out.sort(reverse=True)
    return out[:top]


def peak_speed(times, samples, bone, comp=0):
    vals = [s.get(bone, {}).get("rot", (0, 0, 0))[comp] for s in samples]
    sp = [abs(b - a) * hsrig.FPS for a, b in zip(vals, vals[1:])]
    i = int(np.argmax(sp))
    return round(times[i], 3), round(sp[i], 1)


# --------------------------------------------------------------------------- export
def _reduce_fast(times, vecs, tol, keep):
    """Same criterion as export_mc_clip.reduce_vector (runtime Catmull-Rom over key
    indices, every dense sample within tol) but only re-checks the span a removal
    can affect (+-2 keys), so long clips reduce in seconds instead of hours."""
    n = len(times)
    keep = set(keep) | {0, n - 1}
    idx = list(range(n))
    arr = np.asarray(vecs, float)

    def span_err(cand, lo, hi):
        worst = 0.0
        a, b = cand[max(0, lo)], cand[min(len(cand) - 1, hi)]
        for comp in range(3):
            ks = [(times[i], arr[i][comp]) for i in cand]
            for sidx in range(a, b + 1):
                worst = max(worst, abs(ex.evaluate(ks, times[sidx]) - arr[sidx][comp]))
        return worst

    changed = True
    while changed:
        changed = False
        pos = 1
        while pos < len(idx) - 1:
            if idx[pos] in keep:
                pos += 1
                continue
            trial = idx[:pos] + idx[pos + 1:]
            if span_err(trial, pos - 3, pos + 2) <= tol:
                idx = trial
                changed = True
            else:
                pos += 1
    ks_err = 0.0
    for comp in range(3):
        ks = [(times[i], arr[i][comp]) for i in idx]
        for sidx in range(n):
            ks_err = max(ks_err, abs(ex.evaluate(ks, times[sidx]) - arr[sidx][comp]))
    return idx, ks_err


def build_bedrock_fast(name, length, loop, times, channels, rot_tol=0.25, pos_tol=0.02,
                       keep_times=(), meta=None):
    """export_mc_clip.build_bedrock with the windowed reducer; identical JSON shape."""
    keep = [min(range(len(times)), key=lambda i: abs(times[i] - kt)) for kt in keep_times]
    bones, report = {}, {}
    for bone, chans in channels.items():
        out = {}
        for kind, vecs in chans.items():
            tol = rot_tol if kind == "rotation" else pos_tol
            if ex.is_constant_zero(vecs, tol * 0.5):
                continue
            idx, e = _reduce_fast(times, vecs, tol, keep)
            out[kind] = {
                f"{times[i]:.4f}".rstrip("0").rstrip(".") if times[i] else "0.0": {
                    "post": [round(v, 3) for v in vecs[i]], "lerp_mode": "catmullrom"}
                for i in idx
            }
            report[f"{bone}.{kind}"] = {"keys": len(idx), "max_err": round(e, 4)}
        if out:
            bones[bone] = out
    doc = {"format_version": "1.8.0",
           "animations": {name: {"loop": bool(loop), "animation_length": length, "bones": bones}}}
    if meta:
        doc["hearthstead_meta"] = meta
    return doc, report


def export(const, length, loop, times, samples, keep_times, meta, rename=None, bones=None,
           rot_tol=0.25, pos_tol=0.02):
    name = "animation.settler." + const.lower()
    chan = hsrig.to_export_channels(times, samples, bones)
    if rename:
        chan = {rename.get(b, b): v for b, v in chan.items()}
    doc, report = build_bedrock_fast(name, length, loop, times, chan, rot_tol=rot_tol,
                                     pos_tol=pos_tol, keep_times=keep_times, meta=meta)
    path = os.path.join(ANIM_DIR, const.lower() + ".animation.json")
    ex.write(doc, path)
    worst = 0.0
    bones_doc = doc["animations"][name]["bones"]
    for b, kinds in chan.items():
        for kind, vecs in kinds.items():
            if b not in bones_doc or kind not in bones_doc[b]:
                continue
            for t, v in zip(times, vecs):
                got = ex.sample(doc, name, b, kind, t)
                worst = max(worst, max(abs(a - q) for a, q in zip(got, v)))
    nkeys = sum(v["keys"] for v in report.values())
    print("EXPORTED", path, nkeys, "keys, roundtrip max err", round(worst, 4))
    return path, doc, report, worst


# --------------------------------------------------------------------------- props
def box_mesh_object(name, cubes, colour):
    """cubes: [(from xyz, size xyz)] in the object's local pixel space; parented to MC_SPACE."""
    verts, faces = [], []
    for (x0, y0, z0), (sx, sy, sz) in cubes:
        x1, y1, z1 = x0 + sx, y0 + sy, z0 + sz
        b = len(verts)
        verts += [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
                  (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
        faces += [tuple(b + i for i in f) for f in
                  ((0, 1, 2, 3), (4, 7, 6, 5), (0, 4, 5, 1), (2, 6, 7, 3), (1, 5, 6, 2), (0, 3, 7, 4))]
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    mat = bpy.data.materials.new(name + "_mat")
    mat.use_nodes = True
    mat.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = colour
    mat.node_tree.nodes["Principled BSDF"].inputs["Roughness"].default_value = 0.9
    # a 1x1 image of the same colour so Workbench's TEXTURE colour mode shows it too
    img = bpy.data.images.new(name + "_px", 1, 1)
    img.pixels = list(colour)
    tex = mat.node_tree.nodes.new("ShaderNodeTexImage")
    tex.image = img
    mat.node_tree.links.new(tex.outputs["Color"], mat.node_tree.nodes["Principled BSDF"].inputs["Base Color"])
    mat.diffuse_color = colour
    me.materials.append(mat)
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    ob.parent = bpy.data.objects["MC_SPACE"]
    ob.rotation_mode = "XYZ"
    return ob


SACK_CUBES = [((-2.5, 0.0, 1.0), (5.0, 3.0, 4.0)), ((-3.5, 2.0, 0.0), (7.0, 6.0, 6.0))]
BURLAP = (0.62, 0.50, 0.33, 1.0)


def key_prop(ob, m4, frame, visible=True):
    ob.matrix_basis = Matrix(np.asarray(m4).tolist())
    ob.keyframe_insert("location", frame=frame)
    ob.keyframe_insert("rotation_euler", frame=frame)
    ob.keyframe_insert("scale", frame=frame)
    ob.hide_render = not visible
    ob.keyframe_insert("hide_render", frame=frame)


def block_in_hand_matrix(forearm_world, right=False):
    """Vanilla block item (e.g. a log) held third person: 6 px cube frame (0..16 local)."""
    side = 1 if right else -1
    m = forearm_world @ mcrig.T(0, -4, 0)
    m = m @ mcrig.mat4(mcrig.rx(-90 * mcrig.DEG)) @ mcrig.mat4(mcrig.ry(180 * mcrig.DEG)) \
        @ mcrig.T(side * 1, 2, -10)
    r = mcrig.rx(75 * mcrig.DEG) @ mcrig.ry(45 * side * mcrig.DEG)
    m = m @ mcrig.T(0, 2.5, 0) @ mcrig.mat4(r, s=0.375) @ mcrig.T(-8, -8, -8)
    return m


def block_centre_in_hand(forearm_world, right=False):
    return mcrig.xform(block_in_hand_matrix(forearm_world, right), (8, 8, 8))


# --------------------------------------------------------------------------- render
def cameras(side_loc=(3.9, -0.5, 1.15), side_tgt=(0.0, -0.4, 0.8),
            f34_loc=(2.5, -3.0, 1.6), f34_tgt=(0.1, -0.35, 0.8), lens=42):
    return {
        "side": hsrig.camera("cam_side", side_loc, side_tgt, lens=lens),
        "front34": hsrig.camera("cam_front34", f34_loc, f34_tgt, lens=lens),
    }


def render_clip(slug, length, cams, step=2, res=(960, 720), workbench=True):
    """PNG frames at 60/step fps per camera, then MP4s + a contact sheet via ffmpeg."""
    hsrig.setup_render(res=res)
    sc = bpy.context.scene
    if workbench:
        # textured Workbench: seconds per clip instead of minutes (Eevee is CPU-bound here)
        sc.render.engine = "BLENDER_WORKBENCH"
        sh = sc.display.shading
        sh.light = "STUDIO"
        sh.color_type = "TEXTURE"
        sh.show_shadows = True
        sc.display.render_aa = "8"
        sh.background_type = "VIEWPORT"
        sh.background_color = (0.55, 0.72, 0.95)
    sc.render.fps = 60
    frames = list(range(0, int(round(length * 60)) + 1, step))
    os.makedirs(VIDEOS, exist_ok=True)
    out = {}
    ffmpeg = shutil.which("ffmpeg")
    for view, cam in cams.items():
        d = os.path.join(WORK, "out", slug, "frames_" + view)
        if os.path.isdir(d):
            shutil.rmtree(d)
        hsrig.render_frames(cam, d, frames)
        # sequential names for ffmpeg
        for i, f in enumerate(frames):
            os.replace(os.path.join(d, f"f{f:04d}.png"), os.path.join(d, f"s{i:04d}.png"))
        if ffmpeg:
            mp4 = os.path.join(VIDEOS, f"{slug}_{view}.mp4")
            fps = 60 // step
            subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-framerate", str(fps), "-i",
                            os.path.join(d, "s%04d.png"), "-vf", "tpad=stop_mode=clone:stop_duration=0.5",
                            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", mp4], check=True)
            # half-speed copy: weight reads better slowed down
            slow = os.path.join(VIDEOS, f"{slug}_{view}_half_speed.mp4")
            subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-framerate", str(fps // 2), "-i",
                            os.path.join(d, "s%04d.png"), "-c:v", "libx264", "-pix_fmt", "yuv420p",
                            "-crf", "18", slow], check=True)
            sheet = os.path.join(VIDEOS, f"{slug}_{view}_sheet.png")
            n = len(frames)
            pick = max(1, -(-n // 16))    # 16 evenly spaced tiles over the whole clip
            subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-framerate", str(fps), "-i",
                            os.path.join(d, "s%04d.png"), "-vf",
                            f"select='not(mod(n\\,{pick}))',scale=240:-1,tile=8x2", "-frames:v", "1",
                            sheet], check=True)
            out[view] = [mp4, slow, sheet]
    print("RENDERED", json.dumps(out))
    return out
