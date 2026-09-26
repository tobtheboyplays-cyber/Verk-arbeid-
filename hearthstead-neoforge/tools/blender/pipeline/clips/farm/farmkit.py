"""Shared helpers for the FARM / HERD / FISH clips (farm animator).

Builds only on the shared pipeline (mcrig, hsrig, export_mc_clip, bagclip_rig,
motionkit) and never edits it.

Conventions (mcrig): Minecraft model space, pixels, +Y DOWN, the settler faces
-Z, +X is the settler's LEFT, ground at y = 24. Positive torso x leans
forward, positive head x looks down, elbow flex is negative x, knee flex is
positive x. Positive yaw (rot y) turns the front toward the settler's RIGHT.

Pattern every clip here follows (same as the lead's chop):
  * pelvis (root) / spine (torso) / head / free-hand paths are CTRL F-curves
    (Bezier with per-key easing, cyclic for loops);
  * the TOOL arm is keyed as world-space goals (hand point + tool direction
    [+ tool-tip point]) solved to FK shoulder/elbow/twist angles per key, which
    Blender then interpolates, so in-betweens travel on joint arcs;
  * the free hand is two-bone IK onto props (soil, crop, fleece, strap);
  * both legs are two-bone IK onto planted soles, so the pelvis bends knees
    instead of sliding feet.
"""

from __future__ import annotations

import json
import math
import os
import shutil
import sys

import bpy
import numpy as np
from mathutils import Matrix

HERE = os.path.dirname(os.path.abspath(__file__))
PIPE = os.path.abspath(os.path.join(HERE, "..", ".."))
for p in (PIPE, HERE):
    if p not in sys.path:
        sys.path.insert(0, p)
import bagclip_rig as br  # noqa: E402
import export_mc_clip as ex  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402

WORK = br.WORK
TEX_DIR = br.TEX_DIR
ANIM_DIR = br.ANIM_DIR
REF = os.path.join(WORK, "ref_farm")     # vanilla item sprites (extracted from the client jar)
VIDEOS = os.environ.get("HS_FARM_VIDEOS", r"C:\Users\tobia\Hearthstead-Claude\videos\blender\clips\farm")
FPS = hsrig.FPS
GROUND = 24.0
c = hsrig.ctrl

# vanilla third-person display transforms (right hand values; left mirrors y/z rot, x shift)
DISPLAY = {
    "handheld": ((0.0, -90.0, 55.0), (0.0, 4.0, 0.5), 0.85),       # item/handheld (hoe)
    "rod": ((0.0, 90.0, 55.0), (0.0, 4.0, 2.5), 0.85),             # item/handheld_rod
    "generated": ((0.0, 0.0, 0.0), (0.0, 3.0, 1.0), 0.55),         # item/generated (shears)
}
ITEMS = {"hoe": ("iron_hoe.png", "handheld"), "shears": ("shears.png", "generated"),
         "rod": ("fishing_rod.png", "rod")}
# sprite landmarks, item px (u + 0.5, 16 - (v + 0.5), 8)
HOE_KNOB = (2.5, 1.5, 8.0)
HOE_NECK = (11.5, 11.5, 8.0)
HOE_BLADE = (7.5, 14.5, 8.0)      # the blade's cutting corner
SHEARS_PIVOT = (5.5, 5.5, 8.0)
SHEARS_TIP = (12.5, 13.5, 8.0)
ROD_BUTT = (2.5, 1.5, 8.0)
ROD_TIP = (14.5, 14.5, 8.0)


def argv():
    return sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []


def tex(job):
    return os.path.join(TEX_DIR, f"settler_{job}.png")


# --------------------------------------------------------------------------- item frames
def item_matrix(forearm_world, kind="handheld", right=True):
    side = 1 if right else -1
    (rxd, ryd, rzd), (tx, ty, tz), s = DISPLAY[kind]
    m = forearm_world @ mcrig.T(0, -4, 0)
    m = m @ mcrig.mat4(mcrig.rx(-90 * mcrig.DEG)) @ mcrig.mat4(mcrig.ry(180 * mcrig.DEG)) \
        @ mcrig.T(side * 1, 2, -10)
    r = mcrig.rx(rxd * mcrig.DEG) @ mcrig.ry(side * ryd * mcrig.DEG) @ mcrig.rz(side * rzd * mcrig.DEG)
    return m @ mcrig.T(side * tx, ty, tz) @ mcrig.mat4(r, s=s) @ mcrig.T(-8, -8, -8)


def hand_frame(world, side):
    """Effective forearm frame the held item hangs from: the wrist bone (right_item /
    left_item, pivot at the palm) folded back into forearm space. Identity wrist ==
    the plain forearm, so clips that never key the wrist are unchanged."""
    b = side + "_item"
    return world[b] @ mcrig.T(0, -6, 0) if b in world else world[side + "_forearm"]


def item_point(world, local_pt, kind="handheld", side="right"):
    return mcrig.xform(item_matrix(hand_frame(world, side), kind, side == "right"), local_pt)


def palm(world, side):
    return mcrig.xform(world[side + "_forearm"], (0, 6, 0))


# watering can (SettlerModel: left_arm child at (0,12,-3), rides the forearm)
CAN_OFFSET = (0.0, 8.0, -3.0)                 # forearm-local
SPOUT_PIVOT = (-3.0, 0.0, 0.0)                # can-local
SPOUT_ROT = (0.75, 0.42, 0.0)                 # radians (x, y, z)


def can_matrix(world):
    return world["left_forearm"] @ mcrig.T(*CAN_OFFSET)


def spout_matrix(world):
    return can_matrix(world) @ mcrig.T(*SPOUT_PIVOT) @ mcrig.mat4(mcrig.rot_zyx(*SPOUT_ROT))


def spout_tip(world):
    return mcrig.xform(spout_matrix(world), (0, 0, -11.0))


def spout_base(world):
    return mcrig.xform(spout_matrix(world), (0, 0, 0.0))


# --------------------------------------------------------------------------- FK goal solve
def solve_arm_fk(channels, side, cost_terms, x0, twist_w=0.03, elbow_range=(-135.0, -2.0),
                 wrist=False, wrist_w=0.004, wrist_lim=(40.0, 25.0, 25.0)):
    """FK (arm x, y, z, elbow, twist[, wrist x, y, z]) minimising sum(cost_terms(world)).

    cost_terms(world) -> float, evaluated on the full pose with this arm set.
    wrist=True also solves the held-item wrist bone (<side>_item), softly
    regularised toward neutral and limited to a believable fist rotation.
    """
    arm, fore, itm = side + "_arm", side + "_forearm", side + "_item"
    x0 = list(x0) + ([0.0, 0.0, 0.0] if wrist and len(x0) < 8 else [])

    def cost(p):
        ch = dict(channels)
        ch[arm] = {"rot": (p[0], p[1], p[2])}
        ch[fore] = {"rot": (p[3], p[4], 0.0)}
        if wrist:
            ch[itm] = {"rot": (p[5], p[6], p[7])}
        w = mcrig.pose_matrices(ch)
        k = cost_terms(w)
        k += twist_w * p[4] ** 2 * 0.01
        if wrist:
            for v, lim in zip(p[5:8], wrist_lim):
                k += wrist_w * v * v + (0.5 * (abs(v) - lim) ** 2 if abs(v) > lim else 0.0)
        if p[3] > elbow_range[1]:
            k += (p[3] - elbow_range[1]) ** 2
        if p[3] < elbow_range[0]:
            k += (p[3] - elbow_range[0]) ** 2
        return k

    n = len(x0)
    best, val = mcrig.nelder_mead(cost, x0, ([15, 15, 15, 15, 10] + [10, 10, 10])[:n],
                                  iters=600 if n == 5 else 1500)
    best, val = mcrig.nelder_mead(cost, best, ([4, 4, 4, 4, 3] + [3, 3, 3])[:n], iters=600 if n == 5 else 1500)
    best, val = mcrig.nelder_mead(cost, best, [1] * n, iters=600 if n == 5 else 1500)
    return [float(v) for v in best], float(val)


def goal_terms(side, hand=None, kind="handheld", dir_pts=None, direction=None, tip_pt=None,
               tip=None, w_hand=1.0, w_dir=250.0, w_tip=1.0, extra=None):
    """Cost builder: palm onto `hand`, item axis dir_pts[0]->dir_pts[1] along `direction`,
    item point `tip_pt` onto `tip`."""
    d = None if direction is None else np.asarray(direction, float) / np.linalg.norm(direction)

    def f(w):
        k = 0.0
        if hand is not None:
            k += w_hand * float(np.sum((palm(w, side) - np.asarray(hand)) ** 2))
        if d is not None or tip is not None:
            im = item_matrix(hand_frame(w, side), kind, side == "right")
            if d is not None:
                a = mcrig.xform(im, dir_pts[1]) - mcrig.xform(im, dir_pts[0])
                a /= np.linalg.norm(a)
                k += w_dir * float(np.sum((a - d) ** 2))
            if tip is not None:
                k += w_tip * float(np.sum((mcrig.xform(im, tip_pt) - np.asarray(tip)) ** 2))
        if extra is not None:
            k += extra(w)
        return k
    return f


def key_fk_goals(prefix, side, goals, body_fn, seed, length, cyclic=True, twist_w=0.03,
                 elbow_range=(-135.0, -2.0), probe=None, wrist=False, wrist_lim=(40.0, 25.0, 25.0)):
    """goals: [(t, terms_fn, ease or None)] -> keyed CTRL curves prefix_x/_y/_z/_elbow/_twist.

    The last key is closed onto the first for loops. Returns a solve log.
    probe(world) -> dict of extra diagnostics logged per key."""
    names = [prefix + s for s in ("_x", "_y", "_z", "_elbow", "_twist")
             + (("_wx", "_wy", "_wz") if wrist else ())]
    keys = {n: [] for n in names}
    log = []
    for t, terms, e in goals:
        ch0 = body_fn(t)
        sol, err = solve_arm_fk(ch0, side, terms, seed, twist_w, elbow_range, wrist=wrist,
                                wrist_lim=wrist_lim)
        seed = sol
        ent = {"t": t, "deg": [round(v, 1) for v in sol], "cost": round(err, 3)}
        ch = dict(ch0)
        ch[side + "_arm"] = {"rot": tuple(sol[:3])}
        ch[side + "_forearm"] = {"rot": (sol[3], sol[4], 0.0)}
        if wrist:
            ch[side + "_item"] = {"rot": tuple(sol[5:8])}
        w = mcrig.pose_matrices(ch)
        ent["shoulder"] = [round(float(v), 1) for v in mcrig.xform(w[side + "_arm"], (0, 0, 0))]
        ent["palm"] = [round(float(v), 1) for v in palm(w, side)]
        if probe:
            ent.update({k: [round(float(x), 1) for x in v] for k, v in probe(w).items()})
        log.append(ent)
        for n, v in zip(names, sol):
            keys[n].append((t, v, *e) if e else (t, v))
    for n in names:
        if cyclic and keys[n][-1][0] < length - 1e-6:
            keys[n].append((length, keys[n][0][1]))
        hsrig.key_curve(n, keys[n], cyclic=cyclic, length=length)
    return log


def fk_arm(ch, prefix, side, t):
    ch[side + "_arm"] = {"rot": (c(prefix + "_x", t), c(prefix + "_y", t), c(prefix + "_z", t))}
    ch[side + "_forearm"] = {"rot": (min(-1.0, c(prefix + "_elbow", t)), c(prefix + "_twist", t), 0.0)}
    ob = hsrig.controls()
    if ob.animation_data.action.fcurves.find(f'["{prefix}_wx"]') is not None:
        ch[side + "_item"] = {"rot": (c(prefix + "_wx", t), c(prefix + "_wy", t), c(prefix + "_wz", t))}


# --------------------------------------------------------------------------- variants
# Runtime rule (motion engine, 2026-09-26): a work-clip variant "<base>__vN" is a
# WHOLE number of base cycles with the same contact ticks in every cycle, and it
# starts/ends on the base clip's frame-0 pose (it is cut in on a loop boundary).
def variant_flag():
    """--v2 / --v3 ... on the command line -> 2 / 3 ..., else None (base clip)."""
    for a in argv():
        if a.startswith("--v") and a[3:].isdigit():
            return int(a[3:])
    return None


def tile(keys, length, n):
    """Repeat one cycle of CTRL keys n times (drops the duplicate cycle-end keys)."""
    out = []
    for i in range(n):
        for k in keys:
            if i < n - 1 and abs(k[0] - length) < 1e-9:
                continue
            out.append((k[0] + i * length, *k[1:]))
    return out


def splice(keys, t0, t1, new):
    """Replace the keys strictly inside (t0, t1) with `new` (same tuple shape)."""
    kept = [k for k in keys if not (t0 < k[0] < t1)]
    return sorted(kept + list(new), key=lambda k: k[0])


def tile_goals(goals, length, n):
    return [(g[0] + i * length, *g[1:]) for i in range(n) for g in goals]


# --------------------------------------------------------------------------- body helpers
def key(props, cyclic=True, length=1.0):
    for prop, ks in props.items():
        hsrig.key_curve(prop, ks, cyclic=cyclic, length=length)


def key_vec(name, ks, cyclic=True, length=1.0):
    for i, a in enumerate("xyz"):
        hsrig.key_curve(f"{name}_{a}", [(k[0], k[1][i], *k[2:]) for k in ks], cyclic=cyclic,
                        length=length)


def cv(name, t):
    return np.array([c(name + "_x", t), c(name + "_y", t), c(name + "_z", t)])


def body(t, yaw_on_root=True):
    """Pelvis + spine channels from the standard CTRL names."""
    return {
        "root": {"rot": (0.0, c("pelvis_yaw", t) if yaw_on_root else 0.0, c("pelvis_roll", t)),
                 "pos": (c("pelvis_x", t), c("pelvis_y", t), c("pelvis_z", t))},
        "torso": {"rot": (c("spine_x", t), c("spine_yaw", t), c("spine_z", t))},
    }


def cloak(t, length, gain_pitch=0.06, gain_yaw=0.018, base=2.0, lag=0.05):
    """Cape drag from the spine's pitch/yaw velocity (cyclic)."""
    dt = 1.0 / 60.0

    def w(u):
        return u % length

    def pitch(u):
        return c("spine_x", w(u))

    def twist(u):
        return c("spine_yaw", w(u)) + c("pelvis_yaw", w(u))
    pv = (pitch(t - lag) - pitch(t - lag - dt)) / dt
    yv = (twist(t - lag) - twist(t - lag - dt)) / dt
    # the short shoulder cape hangs with gravity: half the spine pitch, trails its speed
    x = base + 0.45 * c("spine_x", w(t)) - gain_pitch * pv
    return (max(-8.0, min(28.0, x)), 0.0, max(-10.0, min(10.0, -gain_yaw * yv)))


def legs(ch, feet, prev, knee_out=0.15):
    world = mcrig.pose_matrices(ch)
    br.leg_ik(ch, world, feet, prev, knee_out)


def look(ch, target, w_pitch=0.6, w_yaw=0.7, nod=0.0, roll=0.0):
    world = mcrig.pose_matrices(ch)
    br.head_look(ch, world, target, w_pitch=w_pitch, w_yaw=w_yaw, nod=nod)
    x, y, _ = ch["head"]["rot"]
    ch["head"] = {"rot": (x, y, roll)}


def arm_ik(ch, side, target, pole, prev, lower=6.0):
    world = mcrig.pose_matrices(ch)
    br.arm_ik(ch, world, side, np.asarray(target, float), pole, prev, lower=lower)


# --------------------------------------------------------------------------- scene
def _viewport_colour(ob, rgba):
    for slot in ob.material_slots:
        if slot.material:
            slot.material.diffuse_color = rgba


def build(texture, item=None, ground=True):
    sc = hsrig.reset()
    objs = hsrig.build_scene(texture, None)
    # hsrig still adds its own held-item frame empty under the name the new
    # wrist BONE uses; point objs at the real bones so bake() keys the wrists.
    for side in ("right", "left"):
        bone = next((o for o in bpy.data.objects if o.name.startswith(f"part:{side}_item")
                     and o.parent is objs[f"{side}_forearm"]
                     and tuple(round(v, 3) for v in o.location) == (0.0, 6.0, 0.0)), None)
        if bone is not None:
            bone.rotation_mode = "XYZ"
            objs[f"{side}_item"] = bone
    if ground:
        hsrig.prop_box("ground", (-60, 24, -60), (120, 1, 120), (0.30, 0.45, 0.22, 1))
        _viewport_colour(bpy.data.objects["ground"], (0.36, 0.52, 0.27, 1))
    if item:
        png, kind = ITEMS[item]
        e = bpy.data.objects.new("part:tool", None)
        sc.collection.objects.link(e)
        e.parent = objs.get("right_item", objs["right_forearm"])
        rel = item_matrix(np.eye(4), kind, True)
        if e.parent is not objs["right_forearm"]:
            rel = mcrig.T(0, -6, 0) @ rel          # relative to the wrist bone at the palm
        e.matrix_basis = Matrix(rel.tolist())
        m = hsrig.sprite_mesh("mesh:tool", os.path.join(REF, png), e)
        _viewport_colour(m, (0.55, 0.55, 0.58, 1) if item != "rod" else (0.45, 0.32, 0.2, 1))
        objs["tool_mesh"] = m
    return sc, objs


def box(name, frm, size, rgba):
    ob = hsrig.prop_box(name, frm, size, rgba)
    _viewport_colour(ob, rgba)
    return ob


def cubes_object(name, cubes, rgba, parent):
    """cubes [(from, size)] in parent-local px; parent is a Blender object."""
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
    mat.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = rgba
    mat.diffuse_color = rgba
    me.materials.append(mat)
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    ob.parent = parent
    return ob


def watering_can(objs):
    """SettlerModel's watering_can + spout, riding the left forearm (preview only)."""
    sc = bpy.context.scene
    e = bpy.data.objects.new("part:can", None)
    sc.collection.objects.link(e)
    e.parent = objs["left_forearm"]
    e.location = CAN_OFFSET
    wood = (0.55, 0.40, 0.24, 1)
    cubes_object("mesh:can", [((-3.5, -1, -3), (7, 5, 6)), ((-4, -2, -3.5), (8, 1, 7)),
                              ((-3.5, -6, -0.75), (1.5, 5, 1.5)), ((2, -6, -0.75), (1.5, 5, 1.5)),
                              ((-2, -6, -0.75), (4, 1.5, 1.5))], wood, e)
    s = bpy.data.objects.new("part:spout", None)
    sc.collection.objects.link(s)
    s.parent = e
    s.rotation_mode = "XYZ"
    s.location = SPOUT_PIVOT
    s.rotation_euler = SPOUT_ROT
    cubes_object("mesh:spout", [((-0.75, -0.75, -9), (1.5, 1.5, 9)), ((-1.5, -1.5, -11), (3, 3, 2))],
                 wood, s)
    return e


# --------------------------------------------------------------------------- checks / export
def foot_slide(samples, feet):
    worst = 0.0
    for s in samples:
        w = mcrig.pose_matrices(s)
        for side, foot in (("right", feet[0]), ("left", feet[1])):
            worst = max(worst, float(np.linalg.norm(mcrig.xform(w[side + "_shin"], (0, 6, 0)) - foot)))
    return round(worst, 3)


def loop_seam(samples, bones=None):
    """Max |first - last| over exported channels (deg / px): 0 == seamless loop."""
    a, b = samples[0], samples[-1]
    worst = 0.0
    for bone in (bones or mcrig.EXPORT_BONES):
        for kind in ("rot", "pos"):
            va = a.get(bone, {}).get(kind, (0, 0, 0))
            vb = b.get(bone, {}).get(kind, (0, 0, 0))
            worst = max(worst, max(abs(x - y) for x, y in zip(va, vb)))
    return round(worst, 4)


def fix_wraps(samples):
    """Unwrap each rotation channel over time so no frame jumps by ~360 (IK euler flips)."""
    for bone in mcrig.EXPORT_BONES:
        prev = None
        for s in samples:
            if bone not in s or "rot" not in s[bone]:
                continue
            r = list(s[bone]["rot"])
            if prev is not None:
                r = [v + 360.0 * round((p - v) / 360.0) for p, v in zip(prev, r)]
            s[bone] = dict(s[bone])
            s[bone]["rot"] = tuple(r)
            prev = r


def peak_speed_t(times, pts):
    pts = np.asarray(pts)
    sp = np.linalg.norm(np.diff(pts, axis=0), axis=1) * FPS
    i = int(np.argmax(sp))
    return round(times[i + 1], 3), round(float(sp[i]), 1), sp


def export(const, length, loop, times, samples, keep_times, meta, bones=None, rot_tol=0.25,
           pos_tol=0.02):
    return br.export(const, length, loop, times, samples, keep_times, meta, bones=bones,
                     rot_tol=rot_tol, pos_tol=pos_tol)


def finish(slug, length, out_dir):
    """Fast/full preview per the lead's flags, copied into videos/blender/clips/farm/."""
    a = hsrig.parse_args()
    if not (a["fast"] or a["full"]):
        return
    hsrig.preview(out_dir, length, fast=not a["full"], full=a["full"])
    os.makedirs(VIDEOS, exist_ok=True)
    if not a["full"]:
        # cheap extra: a front-LEFT three-quarter sheet (the side camera hides the left hand)
        bpy.context.scene.render.image_settings.file_format = "PNG"
        cam = hsrig.camera("cam_front_left", (2.7, -2.9, 1.5), (0.0, -0.2, 0.85), lens=40)
        n = int(round(length * FPS))
        idx = [int(round(i * n / 12)) for i in range(12)]
        tmp = os.path.join(out_dir, "_sheet_frames_fl")
        hsrig.render_frames(cam, tmp, idx)
        hsrig._sheet([os.path.join(tmp, f"f{f:04d}.png") for f in idx],
                     os.path.join(out_dir, "sheet_front_left.png"))
    copies = {"side.mp4": f"{slug}_side.mp4", "sheet_side.png": f"{slug}_sheet.png",
              "front34.mp4": f"{slug}_front34.mp4", "side_half.mp4": f"{slug}_side_half_speed.mp4",
              "front34_half.mp4": f"{slug}_front34_half_speed.mp4",
              "sheet_front34.png": f"{slug}_front34_sheet.png",
              "sheet_front_left.png": f"{slug}_front_left_sheet.png"}
    done = []
    for src, dst in copies.items():
        p = os.path.join(out_dir, src)
        if os.path.exists(p):
            shutil.copyfile(p, os.path.join(VIDEOS, dst))
            done.append(os.path.join(VIDEOS, dst))
    print("FARM_PREVIEW", json.dumps(done))
