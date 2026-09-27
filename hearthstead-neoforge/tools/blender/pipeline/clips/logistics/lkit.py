"""Logistics / mining / hauling clip kit (third animator). Builds only on the
shared pipeline modules (hsrig, mcrig, export_mc_clip, motionkit) and never
edits them.

Conventions (mcrig): Minecraft model space, pixels, +Y DOWN, the settler faces
-Z, +X is the settler's LEFT, ground y = 24. Positive torso x = lean forward,
positive head x = look down, positive yaw turns the face toward the settler's
RIGHT (ry(+a) maps -Z to -X). Elbow flex is NEGATIVE x (forearm pivot arm-local
0,4,0), knee flex is POSITIVE x (shin pivot leg-local 0,6,0).
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
if PIPE not in sys.path:
    sys.path.insert(0, PIPE)
import hsrig  # noqa: E402
import mcrig  # noqa: E402
import export_mc_clip as ex  # noqa: E402

REPO = os.path.abspath(os.path.join(PIPE, "..", "..", ".."))
WORK = os.environ.get("HS_PIPELINE", r"C:\Users\tobia\Hearthstead-Claude\tools\blender-pipeline")
REF = os.path.join(WORK, "ref_logistics")          # vanilla sprites unzipped from the 1.21.1 client jar
VIDEOS = os.environ.get("HS_CLIP_VIDEOS", r"C:\Users\tobia\Hearthstead-Claude\videos\blender\clips\logistics")
ANIM_DIR = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "animations", "settler")
TEX_DIR = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "textures",
                       "entity", "settler")

SHOULDER = {"right": np.array([-6.0, -10.0, 0.0]), "left": np.array([6.0, -10.0, 0.0])}   # torso-local
HIP = {"right": np.array([-2.6, -12.0, 0.0]), "left": np.array([2.6, -12.0, 0.0])}         # root-local
ARM_BONES = ["right_arm", "left_arm", "right_forearm", "left_forearm"]
c = hsrig.ctrl

# Held-pickaxe landmarks, item-sprite pixel centres (vanilla iron_pickaxe.png, y up).
PICK_KNOB = (2.5, 1.5, 8.0)
PICK_NECK = (10.5, 10.5, 8.0)
PICK_POINT = (5.5, 12.5, 8.0)     # the point that leads a downward swing and bites the rock
PICK_REAR = (13.5, 5.5, 8.0)      # the other point (faces the settler on a downswing)


def args():
    return hsrig.parse_args()


# --------------------------------------------------------------------------- scene
def scene(texture="settler_courier.png", item_png=None):
    hsrig.reset()
    objs = hsrig.build_scene(os.path.join(TEX_DIR, texture), item_png)
    if "right_item" in mcrig.PARTS and "part:right_item.001" in bpy.data.objects:
        # mcrig now has a right_item WRIST bone; build_scene also makes a vanilla
        # held-item frame with the same name and stores it under objs["right_item"],
        # so bake() would key the frame to the wrist pivot. Keep the wrist bone in
        # objs and hang the frame under it (frame = wrist @ T(0,-10,0) @ item chain).
        wrist = bpy.data.objects["part:right_item"]
        frame = bpy.data.objects["part:right_item.001"]
        frame.parent = wrist
        frame.matrix_basis = Matrix(mcrig.item_in_hand_matrix(np.eye(4), True, item_world=np.eye(4)).tolist())
        objs["right_item"] = wrist
        objs["item_frame"] = frame
    hsrig.prop_box("ground", (-60, 24, -60), (120, 1, 120), (0.30, 0.45, 0.22, 1))
    return objs


def box_object(name, cubes, colour, parent):
    """cubes: [(from xyz, size xyz)] in the parent's local pixel space."""
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
    mat.diffuse_color = colour
    me.materials.append(mat)
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    ob.parent = parent if parent is not None else bpy.data.objects["MC_SPACE"]
    ob.rotation_mode = "XYZ"
    return ob


# SettlerModel geometry (torso-local), for previews only.
SACK_CUBES = [((-2.5, 0.0, 1.0), (5.0, 3.0, 4.0)), ((-3.5, 2.0, 0.0), (7.0, 6.0, 6.0))]
FRAME_CUBES = [((-4.5, 0, 0), (1, 10, 1)), ((3.5, 0, 0), (1, 10, 1)), ((-4, 1, 0), (8, 1, 1)),
               ((-4, 8, 0), (8, 1, 1)), ((-4.5, 9, 3), (0.5, 1, 1)), ((4, 9, 3), (0.5, 1, 1)),
               ((-4, 9, 0), (8, 1, 4)), ((-0.5, 2, 6), (1, 7, 1))]
LOGS_CUBES = [((-4.0, 3.0, 1.5), (8.0, 2.2, 2.2)), ((-4.0, 5.5, 1.5), (8.0, 2.2, 2.2)),
              ((-4.0, 6.5, 3.9), (8.0, 2.2, 2.2))]
BURLAP = (0.62, 0.50, 0.33, 1.0)
OAK = (0.45, 0.33, 0.19, 1.0)
BARK = (0.33, 0.25, 0.15, 1.0)
STONE = (0.47, 0.47, 0.48, 1.0)
IRON = (0.75, 0.75, 0.78, 1.0)


def attach_sack(objs, scale=1.0):
    ob = box_object("prop:sack", SACK_CUBES, BURLAP, objs["torso"])
    ob.location = (0.0, -10.5, 2.5)
    ob.scale = (scale, scale, scale)
    # shoulder straps over the chest (read where the hands grip)
    for sx in (-1, 1):
        st = box_object(f"prop:strap_{sx}", [((sx * 3.4 - 0.6, -12.05, -2.9), (1.2, 8.5, 0.4)),
                                            ((sx * 3.4 - 0.6, -12.3, -2.9), (1.2, 0.4, 5.6))],
                        (0.36, 0.24, 0.14, 1.0), objs["torso"])
    return ob


def attach_frame(objs, logs=3):
    ob = box_object("prop:frame", FRAME_CUBES, OAK, objs["torso"])
    ob.location = (0.0, -10.5, 4.0)
    if logs:
        lg = box_object("prop:logs", LOGS_CUBES[:logs], BARK, objs["torso"])
        lg.location = (0.0, -10.5, 4.0)
    for sx in (-1, 1):
        box_object(f"prop:strap_{sx}", [((sx * 3.4 - 0.6, -12.05, -2.9), (1.2, 8.5, 0.4)),
                                       ((sx * 3.4 - 0.6, -12.3, -2.9), (1.2, 0.4, 7.0))],
                   (0.36, 0.24, 0.14, 1.0), objs["torso"])
    return ob


def block_in_hand_matrix(forearm_world, right=False):
    """Vanilla block item (a log) held third person: 16-px cube frame, scale .375."""
    side = 1 if right else -1
    m = forearm_world @ mcrig.T(0, -4, 0)
    m = m @ mcrig.mat4(mcrig.rx(-90 * mcrig.DEG)) @ mcrig.mat4(mcrig.ry(180 * mcrig.DEG)) \
        @ mcrig.T(side * 1, 2, -10)
    r = mcrig.rx(75 * mcrig.DEG) @ mcrig.ry(45 * side * mcrig.DEG)
    return m @ mcrig.T(0, 2.5, 0) @ mcrig.mat4(r, s=0.375) @ mcrig.T(-8, -8, -8)


def attach_block_in_hand(objs, side="left", colour=BARK, name="prop:held_block"):
    ob = box_object(name, [((0, 0, 0), (16, 16, 16))], colour, objs[side + "_forearm"])
    ob.matrix_basis = Matrix(block_in_hand_matrix(np.eye(4), side == "right").tolist())
    return ob


def key_world(ob, frame, m4=None, loc=None, visible=True):
    if m4 is not None:
        ob.matrix_basis = Matrix(np.asarray(m4).tolist())
    if loc is not None:
        ob.location = loc
    ob.keyframe_insert("location", frame=frame)
    ob.keyframe_insert("rotation_euler", frame=frame)
    ob.keyframe_insert("scale", frame=frame)
    ob.hide_render = not visible
    ob.keyframe_insert("hide_render", frame=frame)


# --------------------------------------------------------------------------- solve pieces
def arm_ik_local(ch, side, target_local, pole, prev, lower=6.0):
    """Two-bone arm with the target in TORSO space: palm (arm-local y = 4 + lower) on the target."""
    r, flex, reached = mcrig.two_bone(SHOULDER[side], np.asarray(target_local, float), mcrig.UPPER_ARM,
                                      lower, np.asarray(pole, float), -1)
    rot = hsrig.euler_deg_continuous(r, prev.get(side + "_arm"))
    prev[side + "_arm"] = rot
    ch[side + "_arm"] = {"rot": tuple(rot)}
    ch[side + "_forearm"] = {"rot": (math.degrees(flex), 0.0, 0.0)}
    return float(np.linalg.norm(reached - np.asarray(target_local)))


def arm_ik_world(ch, side, target, pole_local, prev, lower=6.0):
    world = mcrig.pose_matrices(ch)
    tl = mcrig.xform(np.linalg.inv(world["torso"]), target)
    return arm_ik_local(ch, side, tl, pole_local, prev, lower)


def legs_ik(ch, feet, prev, knee=(0.0, 0.0, -1.0), knee_out=0.12):
    """feet = (right_sole, left_sole) model px; knees point along `knee` (model space)."""
    world = mcrig.pose_matrices(ch)
    inv_root = np.linalg.inv(world["root"])
    for side, foot in (("right", feet[0]), ("left", feet[1])):
        hip = HIP[side]
        fl = mcrig.xform(inv_root, np.asarray(foot, float))
        pole = inv_root[:3, :3] @ (np.asarray(knee, float) + np.array([knee_out * np.sign(hip[0]), 0, 0]))
        r, flex, _ = mcrig.two_bone(hip, fl, mcrig.THIGH, mcrig.SOLE_Y - mcrig.THIGH, pole, +1)
        rot = hsrig.euler_deg_continuous(r, prev.get(side + "_leg"))
        prev[side + "_leg"] = rot
        ch[side + "_leg"] = {"rot": tuple(rot)}
        ch[side + "_shin"] = {"rot": (math.degrees(flex), 0.0, 0.0)}


def head_aim(ch, target, w_pitch=0.6, w_yaw=0.75, nod=0.0, yaw_extra=0.0):
    world = mcrig.pose_matrices(ch)
    torso = world["torso"]
    eye = mcrig.xform(torso, (0, -16, -2))
    d = np.linalg.inv(torso[:3, :3]) @ (np.asarray(target, float) - eye)
    yaw = math.degrees(math.atan2(-d[0], -d[2]))
    pitch = math.degrees(math.atan2(d[1], math.hypot(d[0], d[2])))
    ch["head"] = {"rot": (max(-40, min(55, w_pitch * pitch + nod)), max(-50, min(50, w_yaw * yaw + yaw_extra)), 0.0)}


def eased_target(steps, t, ease=0.2):
    """Look/aim target that EASES between waypoints instead of jumping.

    steps: [(t_switch, xyz), ...] sorted; before the first switch the first xyz holds.
    Each change starts at its t_switch and takes `ease` seconds (smoothstep), so a
    head aimed with it never snaps (QA 2026-09-26: 40-100 deg one-frame head pops)."""
    cur = np.asarray(steps[0][1], float)
    for ts, xyz in steps[1:]:
        x = max(0.0, min(1.0, (t - ts) / ease))
        x = x * x * (3 - 2 * x)
        cur = cur + (np.asarray(xyz, float) - cur) * x
    return cur


def palm(world, side, y=6.0):
    return mcrig.xform(world[side + "_forearm"], (0, y, 0))


def vel(fn, t, length, dt=1.0 / 60.0):
    return (fn(t % length) - fn((t - dt) % length)) / dt


# --------------------------------------------------------------------------- export + checks
def export(const, length, loop, times, samples, bones=None, keep_times=(), meta=None,
           rot_tol=0.25, pos_tol=0.02, write=True):
    name = "animation.settler." + const.lower()
    chan = hsrig.to_export_channels(times, samples, bones)
    doc, report = ex.build_bedrock(name, length, loop, times, chan, rot_tol=rot_tol, pos_tol=pos_tol,
                                   keep_times=tuple(keep_times) + (0.0, length), meta=meta)
    path = os.path.join(ANIM_DIR, const.lower() + ".animation.json")
    if write:
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
    print("EXPORTED", path if write else "(dry)", nkeys, "keys, roundtrip max err", round(worst, 4))
    return path, doc, report, worst


def travel(samples, bones):
    """Per-bone per-axis rotation span (deg) over the clip."""
    out = {}
    for b in bones:
        v = np.array([s.get(b, {}).get("rot", (0, 0, 0)) for s in samples])
        out[b] = [round(float(x), 2) for x in (v.max(0) - v.min(0))]
    return out


def seam(samples):
    """Largest |value(0) - value(L)| over every channel (loops must close)."""
    a, b = samples[0], samples[-1]
    worst = 0.0
    for bone in set(a) | set(b):
        for k in ("rot", "pos"):
            va = a.get(bone, {}).get(k, (0, 0, 0))
            vb = b.get(bone, {}).get(k, (0, 0, 0))
            worst = max(worst, max(abs(x - y) for x, y in zip(va, vb)))
    return round(worst, 4)


def ranges(samples, bones):
    out = {}
    for b in bones:
        v = np.array([s.get(b, {}).get("rot", (0, 0, 0)) for s in samples])
        out[b] = {"min": [round(float(x), 1) for x in v.min(0)], "max": [round(float(x), 1) for x in v.max(0)]}
    return out


# --------------------------------------------------------------------------- walk_laden underlay (previews of arm layers)
def walk_underlay(clip="walk_laden", period=None, lean_deg=0.0):
    """Channel sampler for the lead's exported gait, for previewing an arm-only layer
    in context. `period` rescales the gait cycle; `lean_deg` adds SettlerModel.applySack's
    load lean (torso +lean, head -0.6 lean)."""
    path = os.path.join(ANIM_DIR, clip + ".animation.json")
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    name = "animation.settler." + clip
    anim = doc["animations"][name]
    L = float(anim["animation_length"])
    bones = anim["bones"]
    period = period or L

    def at(t):
        tt = (t % period) / period * L
        ch = {}
        for b, kinds in bones.items():
            ch[b] = {}
            for kind in kinds:
                v = ex.sample(doc, name, b, kind, tt)
                ch[b]["rot" if kind == "rotation" else "pos"] = tuple(v)
        if lean_deg:
            tr = ch.setdefault("torso", {}).get("rot", (0, 0, 0))
            ch["torso"]["rot"] = (tr[0] + lean_deg, tr[1], tr[2])
            hd = ch.setdefault("head", {}).get("rot", (0, 0, 0))
            ch["head"]["rot"] = (hd[0] - 0.6 * lean_deg, hd[1], hd[2])
        return ch
    return at


# --------------------------------------------------------------------------- preview
def preview(slug, length, cams=None, full=False):
    """One contact sheet + one side MP4 (Workbench) into videos/.../logistics."""
    tmp = os.path.join(WORK, "out", "logistics", slug)
    if os.path.isdir(tmp):
        shutil.rmtree(tmp)
    hsrig.preview(tmp, length, fast=not full, full=full, cams=cams)
    os.makedirs(VIDEOS, exist_ok=True)
    out = {}
    for src, dst in (("side.mp4", f"{slug}_side.mp4"), ("sheet_side.png", f"{slug}_sheet.png"),
                     ("front34.mp4", f"{slug}_front34.mp4"), ("side_half.mp4", f"{slug}_side_half_speed.mp4"),
                     ("front34_half.mp4", f"{slug}_front34_half_speed.mp4"),
                     ("sheet_front34.png", f"{slug}_front34_sheet.png")):
        p = os.path.join(tmp, src)
        if os.path.exists(p):
            shutil.copyfile(p, os.path.join(VIDEOS, dst))
            out[src] = os.path.join(VIDEOS, dst)
    print("PREVIEW_FILES", json.dumps(out))
    return out


def cams_side(dist=3.8, height=1.05, target_y=-0.2, target_z=0.9, lens=40, front=True):
    """Side camera from the settler's right (-X) + a front three-quarter camera."""
    cams = {"side": hsrig.camera("cam_side", (-dist, target_y, height), (0.0, target_y, target_z), lens=lens)}
    if front:
        cams["front34"] = hsrig.camera("cam_front34", (-2.5, -2.6, 1.45), (0.0, -0.1, 0.95), lens=40)
    return cams


def review_sheet(slug, length, cam_loc, cam_tgt, n=12, lens=45, tag="review", hide=()):
    """Extra Workbench contact sheet from any angle, for the animator's own review
    (written next to the scratch frames, not to the delivered previews)."""
    sc = bpy.context.scene
    if sc.render.engine != "BLENDER_WORKBENCH":
        hsrig.setup_render(res=(480, 360))
        sc.render.engine = "BLENDER_WORKBENCH"
        sh = sc.display.shading
        sh.light = "STUDIO"
        sh.color_type = "TEXTURE"
        sh.show_shadows = True
    for name in hide:
        ob = bpy.data.objects.get(name)
        if ob is not None:
            ob.hide_render = True
    cam = hsrig.camera("cam_" + tag, cam_loc, cam_tgt, lens=lens)
    frames = [int(round(i * length * hsrig.FPS / n)) for i in range(n)]
    d = os.path.join(WORK, "out", "logistics", slug + "_" + tag)
    hsrig.render_frames(cam, d, frames)
    out = os.path.join(WORK, "out", "logistics", f"{slug}_{tag}.png")
    hsrig._sheet([os.path.join(d, f"f{f:04d}.png") for f in frames], out)
    print("REVIEW", out)
    return out
