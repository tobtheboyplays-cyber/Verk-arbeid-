"""Shared helpers for the TAVERN clips (tavern animation lane, 26 Sep).

Builds on lifekit / hsrig / mcrig / export_mc_clip and never edits them.

    blender -b --factory-startup --python ../../run_batch.py -- --fast clips/tavern/author_seated.py ...

Geometry contract (measured from the mod + Another Furniture 4.0.2 jar):
  * Seated (TavernSeatEntity): the runtime resets root and draws the seated legs
    itself, so seated clips export the UPPER body only (torso, head, arms,
    forearms, wrist bones, cloak). The hip sits ON the seat top: vanilla stair
    0.5, AF chair/bench/sofa 7/16, stool 8/16, tall stool 1.0 block.
  * Table top is 9 px above the hip for both fixtures (vanilla fence + plate
    top 17/16 over a 8/16 stair; AF table top 16/16 over a 7/16 chair) and its
    near edge is ~5 px in front of the hip. Seated clips are authored against
    exactly that (TABLE_Y / TABLE_Z below, model space with the hip at y=16).
  * The mug is the real item hearthstead:ale drawn by the motion prop hook
    (MotionPropLayer: item/generated third-person display). The wrist bones
    (right_item / left_item) are solved every frame so the mug stays upright
    and tilts only when it is drunk from.
"""

from __future__ import annotations

import json
import math
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PIPE = os.path.abspath(os.path.join(HERE, "..", ".."))
LIFE = os.path.join(PIPE, "clips", "life")
IDLES = os.path.join(PIPE, "clips", "idles")
for p in (PIPE, LIFE, IDLES, HERE):
    if p not in sys.path:
        sys.path.insert(0, p)
VIDEOS = r"C:\Users\tobia\Hearthstead-Claude\videos\blender\clips\tavern"
os.environ["HS_LIFE_VIDEOS"] = VIDEOS

import bpy  # noqa: E402
import numpy as np  # noqa: E402
from mathutils import Matrix  # noqa: E402

import hsrig  # noqa: E402
import mcrig  # noqa: E402
import export_mc_clip as ex  # noqa: E402
import lifekit as lk  # noqa: E402

lk.VIDEOS = VIDEOS
REPO = lk.REPO
ALE_PNG = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "textures", "item", "ale.png")
c = hsrig.ctrl
FPS = hsrig.FPS
D = math.radians
ACC, ACC2, DEC, DEC3, INOUT, LIN = lk.ACC, lk.ACC2, lk.DEC, lk.DEC3, lk.INOUT, lk.LIN
WHIP = ("QUART", "EASE_IN")
HOLD = ("CONSTANT", "AUTO")

# --------------------------------------------------------------------------- seated frame
SEAT_ROOT = (0.0, -4.0, 0.0)          # posVec: hip 4 px lower = 8 px above the ground
HIP_Y = 16.0
TABLE_Y = HIP_Y - 9.0                 # tabletop (model y, +down)
TABLE_Z = -5.0                        # near edge of the tabletop
MOUTH = np.array([0.0, -1.9, -4.6])   # head space: lips (face plane z=-4)
UPPER = ["torso", "head", "right_arm", "left_arm", "right_forearm", "left_forearm",
         "right_item", "left_item", "cloak"]
NO_LEGS = ["root", "torso", "head", "right_arm", "left_arm", "right_forearm", "left_forearm",
           "right_item", "left_item", "cloak"]


def scene_seated(texture, fixture="vanilla", table=True, mug_on_table=None):
    """Stair chair + fence/plate table (vanilla) or AF chair + table, at the runtime heights."""
    objs = lk.scene(texture)
    wood, dark = (0.55, 0.38, 0.22, 1), (0.36, 0.24, 0.14, 1)
    if fixture == "vanilla":
        # stair: bottom slab 0..8 px over the whole cell, back half up to 16 (cell z -4..12)
        lk.box("stair_low", (-8, 16, -4), (16, 8, 16), wood)
        lk.box("stair_back", (-8, 8, 4), (16, 8, 8), wood)
        if table:
            lk.box("fence_post", (-2, 8, -14), (4, 16, 4), dark)
            lk.box("plate", (-7, 7, -19), (14, 1, 14), wood)
    else:
        # AF chair (seat top 7/16) + AF table (top 13..16 of its cell); hip 2 px ahead of centre
        cz = 2.0
        lk.box("af_seat", (-6, 17, cz - 6), (12, 2, 12), wood)
        lk.box("af_back", (-6, 8, cz + 4), (12, 9, 2), wood)
        for x in (-6, 4):
            for z in (cz - 6, cz + 4):
                lk.box(f"af_leg{x}{z}", (x, 19, z), (2, 5, 2), dark)
        if table:
            tz = cz - 8
            lk.box("af_top", (-8, 8, tz - 16), (16, 3, 16), wood)
            for x in (-8, 6):
                for z in (tz - 16, tz - 2):
                    lk.box(f"af_tleg{x}{z}", (x, 11, z), (2, 13, 2), dark)
    if mug_on_table is not None:
        mug_box("table_mug", mug_on_table)
    return objs


def seated_legs(ch):
    """Preview-only seated legs (the runtime draws its own seated legs; never exported)."""
    ch["right_leg"] = {"rot": (-90.0, 0.0, 3.0)}
    ch["left_leg"] = {"rot": (-90.0, 0.0, -3.0)}
    ch["right_shin"] = {"rot": (90.0, 0.0, 0.0)}
    ch["left_shin"] = {"rot": (90.0, 0.0, 0.0)}
    return ch


def scene_standing(texture, counter=False, tap=False):
    objs = lk.scene(texture)
    if counter:
        # full-block bar counter, its face 5 px in front of the settler
        lk.box("counter", (-14, 8, -21), (28, 16, 16), (0.45, 0.30, 0.18, 1))
        lk.box("counter_top", (-14, 7, -22), (28, 1, 17), (0.58, 0.40, 0.24, 1))
    if tap:
        # barrel + ale tap on its face, spout at ~0.5 block, 8 px ahead, a little right
        lk.box("barrel", (-10, 4, -30), (16, 20, 16), (0.42, 0.28, 0.16, 1))
        lk.box("tap_board", (-4, 11, -14.5), (6, 7, 1), (0.5, 0.35, 0.2, 1))
        lk.box("tap_pipe", (-2, 14, -13.5), (2, 2, 3), (0.75, 0.6, 0.2, 1))
        lk.box("tap_nozzle", (-2, 14, -11), (2, 4, 2), (0.75, 0.6, 0.2, 1))
        lk.box("tap_handle", (-1.6, 9, -12.8), (1.2, 5, 1), (0.55, 0.38, 0.22, 1))
        lk.box("drip_shelf", (-8, 19, -18), (12, 1, 6), (0.45, 0.30, 0.18, 1))
    return objs


def mug_box(name, bottom_centre):
    x, y, z = bottom_centre
    lk.box(name, (x - 2.2, y - 5.0, z - 2.2), (4.4, 5.0, 4.4), (0.55, 0.36, 0.18, 1))


# --------------------------------------------------------------------------- the ale mug
def _mug_bbox():
    try:
        img = bpy.data.images.load(ALE_PNG, check_existing=True)
        w, h = img.size
        px = np.array(img.pixels[:]).reshape(h, w, 4)       # row 0 = bottom
        ys, xs = np.nonzero(px[..., 3] > 0)
        return float(xs.min()), float(xs.max() + 1), float(ys.min()), float(ys.max() + 1)
    except Exception:
        return 2.0, 14.0, 2.0, 13.0


MUG_X0, MUG_X1, MUG_Y0, MUG_Y1 = _mug_bbox()
MUG_CX = 0.5 * (MUG_X0 + MUG_X1)
# item-px landmarks (item space: x right, y up, sprite plane z = 8)
MUG_BOTTOM = np.array([MUG_CX - 1.0, MUG_Y0, 8.0])
MUG_RIM = np.array([MUG_CX - 1.0, MUG_Y1, 8.0])
MUG_RIM_NEAR = np.array([MUG_X0 + 1.0, MUG_Y1, 8.0])
MUG_BODY = np.array([MUG_CX - 1.0, 0.5 * (MUG_Y0 + MUG_Y1), 8.0])
C_ROT = mcrig.rx(D(-90)) @ mcrig.ry(D(180))
# hearthstead:ale third-person display (models/item/ale.json, tavern lane): a tankard about 0.6 of
# the head's width instead of item/generated's 0.55 (which drew it as big as the head)
DISPLAY_T = (0.0, 2.5, 0.5)
DISPLAY_S = 0.36
MAX_SLIDE = 7.0   # px the mug may slide in the fist (wrist bone position): the fist stays on the handle


def item_frame(world, side):
    """World frame of the held item/generated model (0..16 px) for this hand (wrist bone aware)."""
    s = 1 if side == "right" else -1
    m = world[side + "_item"] @ mcrig.T(0, -10, 0)
    m = m @ mcrig.mat4(mcrig.rx(D(-90))) @ mcrig.mat4(mcrig.ry(D(180))) @ mcrig.T(s * 1, 2, -10)
    return m @ mcrig.T(*DISPLAY_T) @ mcrig.mat4(np.eye(3), s=DISPLAY_S) @ mcrig.T(-8, -8, -8)


def mug_point(world, side, p):
    return mcrig.xform(item_frame(world, side), p)


def item_rot_for(world, side, up, face, prev=None):
    """Wrist-bone euler (deg) so the mug's up = `up` and its sprite normal ~ `face` (model dirs)."""
    u = np.asarray(up, float)
    u /= np.linalg.norm(u)
    n = np.asarray(face, float)
    n = n - u * n.dot(u)
    n /= np.linalg.norm(n)
    x = np.cross(u, n)
    Dm = np.column_stack([x, u, n])
    F = world[side + "_forearm"][:3, :3]
    R = F.T @ Dm @ C_ROT.T
    key = "_item_" + side
    rot = tuple(hsrig.euler_deg_continuous(R, prev.get(key) if prev is not None else None))
    if prev is not None:
        prev[key] = rot
    return rot


def tilt_up(pitch_deg, roll_deg=0.0, yaw_deg=0.0):
    """Model-space mug up vector: upright (0,-1,0) pitched toward the face (+pitch tips the rim
    back toward the drinker, i.e. rotates about +x) and rolled sideways."""
    v = mcrig.ry(D(yaw_deg)) @ mcrig.rz(D(roll_deg)) @ mcrig.rx(D(-pitch_deg)) @ np.array([0.0, -1.0, 0.0])
    return v


def face_dir(side, yaw_deg=0.0):
    """Sprite normal: out to the hand's own side, turned `yaw_deg` toward the front."""
    s = -1.0 if side == "right" else 1.0
    return mcrig.ry(D(-s * yaw_deg)) @ np.array([s, 0.0, 0.0])


def hold_mug(ch, side, target, pole, prev, up=(0, -1, 0), face=None, point=None, iters=2, grip=None,
             **_):
    """The hand closes round the mug's side (its handle) at mid-height and the mug landmark
    `point` (item px, default the mug body) lands exactly on `target` (model space):
      1. two-bone arm IK puts the palm at target + grip (grip = beside the mug, model space),
      2. the wrist bone turns the mug to `up` (sprite plane facing `face`),
      3. the wrist bone's position slides the item in the fist so the landmark hits the target
         (runtime: arm_bent @ T(palm + pos) R(item) T(-palm), posVec y up).
    Returns the residual in px."""
    point = MUG_BODY if point is None else point
    face = face_dir(side) if face is None else face
    s = -1.0 if side == "right" else 1.0
    tgt = np.asarray(target, float)
    g = np.array([1.9 * s, 0.3, 0.4]) if grip is None else np.asarray(grip, float)
    upv = np.asarray(up, float) / np.linalg.norm(up)
    # keep the grip beside the mug's own axis when it tilts
    g = g - upv * g.dot(upv) + upv * 0.4
    palm_t = tgt + g
    w = mcrig.pose_matrices(ch)
    err = 0.0
    for it in range(iters + 1):
        w = mcrig.pose_matrices(ch)
        lk.arm_ik(ch, w, side, palm_t, pole, prev)
        ch[side + "_item"] = {"rot": (0.0, 0.0, 0.0), "pos": (0.0, 0.0, 0.0)}
        w = mcrig.pose_matrices(ch)
        ch[side + "_item"]["rot"] = item_rot_for(w, side, up, face, prev)
        w = mcrig.pose_matrices(ch)
        d = tgt - mug_point(w, side, point)
        if it < iters:
            # the fist stays at the mug's side (palm_t); only an UNREACHABLE palm target
            # (arm at full stretch) is pulled toward the mug so the mug never floats off
            got = mcrig.xform(w[side + "_forearm"], (0, 6, 0))
            miss = palm_t - got
            if float(np.linalg.norm(miss)) > 0.4:
                palm_t = palm_t - miss * 0.5 + d * 0.5
            continue
        # the small residual (or an unreachable target) slides the mug in the fist, clamped
        F = w[side + "_forearm"][:3, :3]
        loc = F.T @ d
        n = float(np.linalg.norm(loc))
        if n > MAX_SLIDE:
            loc = loc * (MAX_SLIDE / n)
        ch[side + "_item"]["pos"] = (loc[0], -loc[1], loc[2])
        w = mcrig.pose_matrices(ch)
        err = float(np.linalg.norm(tgt - mug_point(w, side, point)))
    return err


def free_item(ch, side, prev, up=(0, -1, 0), face=None):
    """Keep the mug upright on an FK/IK arm without moving the hand."""
    w = mcrig.pose_matrices(ch)
    ch[side + "_item"] = {"rot": item_rot_for(w, side, up, face_dir(side) if face is None else face, prev)}


# --------------------------------------------------------------------------- preview props
def attach_mug(objs, side):
    """Ale sprite on the wrist bone (same item/generated chain as the game)."""
    wrist = objs[side + "_item"]
    frame = hsrig._empty(f"item:{side}_mug_frame", wrist)
    rel = mcrig.T(0, -10, 0) @ mcrig.mat4(mcrig.rx(D(-90))) @ mcrig.mat4(mcrig.ry(D(180))) \
        @ mcrig.T((1 if side == "right" else -1), 2, -10) @ mcrig.T(*DISPLAY_T) \
        @ mcrig.mat4(np.eye(3), s=DISPLAY_S) @ mcrig.T(-8, -8, -8)
    frame.matrix_basis = Matrix(rel.tolist())
    palette_sprite(f"mesh:{side}_mug", ALE_PNG, frame)
    return frame


def palette_sprite(name, png, parent):
    """Extruded 1-px voxel sprite with one flat material per colour (Workbench TEXTURE mode
    falls back to material colours, so the preview shows the real item pixels)."""
    img = bpy.data.images.load(png, check_existing=True)
    w, h = img.size
    px = np.array(img.pixels[:]).reshape(h, w, 4)[::-1]
    me = bpy.data.meshes.new(name)
    verts, faces, mats = [], [], []
    colours = {}
    for v in range(h):
        for u in range(w):
            if px[v, u, 3] <= 0.0:
                continue
            key = tuple(round(float(x), 2) for x in px[v, u, :3])
            if key not in colours:
                colours[key] = len(colours)
            x0, x1, y1, y0 = u, u + 1, 16 - v, 16 - v - 1
            z0, z1 = 7.5, 8.5
            b = len(verts)
            verts += [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
                      (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
            for f in ((0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (2, 3, 7, 6), (1, 2, 6, 5), (0, 4, 7, 3)):
                faces.append(tuple(b + i for i in f))
                mats.append(colours[key])
    me.from_pydata(verts, [], faces)
    for key in colours:
        m = bpy.data.materials.new(name + "_c%d" % colours[key])
        lin = [c ** 2.2 for c in key]      # sRGB texel -> linear viewport colour
        m.diffuse_color = (lin[0], lin[1], lin[2], 1.0)
        me.materials.append(m)
    for poly, mi in zip(me.polygons, mats):
        poly.material_index = mi
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    ob.parent = parent
    return ob


def key_visible(frame, windows, length):
    """Scale the prop frame to 0 outside [from, to] windows (clip seconds)."""
    base = frame.matrix_basis.copy()
    n = int(round(length * FPS))
    for f in range(n + 1):
        t = f / FPS
        on = any(a - 1e-6 <= t <= b + 1e-6 for a, b in windows)
        frame.matrix_basis = base
        if not on:
            frame.scale = (1e-4, 1e-4, 1e-4)
        frame.keyframe_insert("scale", frame=f)
        frame.keyframe_insert("location", frame=f)
    for fc in frame.animation_data.action.fcurves:
        for kp in fc.keyframe_points:
            kp.interpolation = "CONSTANT"
        fc.modifiers.new("CYCLES")


def seated_cams():
    return lk.cameras(side=((-3.5, -0.7, 0.95), (0.0, -0.35, 0.78)),
                      front34=((-2.2, -3.0, 1.35), (0.0, -0.3, 0.8)))


# --------------------------------------------------------------------------- bake/export
def warm(solve, length, rounds=2):
    for _ in range(rounds):
        for f in range(int(length * FPS) + 1):
            solve(f / FPS)


def export(const, length, loop, times, samples, keep, meta, bones=None, props=None, sounds=None,
           write=True):
    """Bedrock JSON with the vectorised key reducer of idlekit (the life exporter is cubic in
    the frame count and takes minutes on 6-8 s clips). Contact/keep times are always keys.
    Rotation tol 0.2 deg, position 0.015 px, torso scale 0.0015; round trip verified with the
    runtime Catmull-Rom."""
    import idlekit
    name = "animation.settler." + const.lower()
    bones = bones or mcrig.EXPORT_BONES
    ts = np.asarray(times)
    keep_idx = sorted({int(np.argmin(np.abs(ts - kt))) for kt in keep})
    out_bones, rep, worst = {}, {}, 0.0
    for b in bones:
        out = {}
        for kind, k, tol, rest in (("rotation", "rot", 0.2, (0, 0, 0)), ("position", "pos", 0.015, (0, 0, 0)),
                                   ("scale", "scale", 0.0015, (1, 1, 1))):
            if kind == "position" and b in mcrig.ROTATION_ONLY:
                continue
            if kind == "scale" and b != "torso":
                continue
            vecs = [list(s.get(b, {}).get(k, rest)) for s in samples]
            if all(abs(v - r) < tol * 0.5 for vec in vecs for v, r in zip(vec, rest)):
                continue
            idx, e = idlekit.reduce_keys(times, vecs, tol)
            idx = sorted(set(idx) | set(keep_idx))
            kt = ts[idx]
            kv = np.asarray(vecs, float)[idx]
            got = idlekit._eval_many(kt, kv, ts)
            err = float(np.abs(got - np.asarray(vecs, float)).max())
            worst = max(worst, err * 100 if kind == "scale" else err)
            out[kind] = {idlekit.tkey(times[i]): {"post": [round(v, 4 if kind == "scale" else 3) for v in vecs[i]],
                                                 "lerp_mode": "catmullrom"} for i in idx}
            rep[f"{b}.{kind}"] = {"keys": len(idx), "max_err": round(err, 4)}
        if out:
            out_bones[b] = out
    anim = {"loop": bool(loop), "animation_length": length, "bones": out_bones}
    if props:
        anim["hearthstead_props"] = props
    if sounds:
        anim["hearthstead_sounds"] = sounds
    meta = dict(meta)
    meta["roundtrip_max_err"] = round(worst, 4)
    meta["keys"] = sum(r["keys"] for r in rep.values())
    doc = {"format_version": "1.8.0", "animations": {name: anim}, "hearthstead_meta": meta}
    path = os.path.join(lk.ANIM_DIR, const.lower() + ".animation.json")
    if write:
        with open(path, "w", encoding="utf-8", newline=chr(10)) as fh:
            json.dump(doc, fh, indent=1)
            fh.write(chr(10))
        print("WROTE", path, meta["keys"], "keys, roundtrip", meta["roundtrip_max_err"], flush=True)
    return path, doc, rep, worst


def report(slug, data):
    out = os.path.join(lk.WORK, "out", "tavern", slug)
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "report.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, indent=1)


def preview(slug, length, a, cams=None, loops=2):
    return lk.preview(slug, length, a, cams=cams, loops=loops)


def pose_seam(samples, bones=None):
    """Largest rotation (deg) between the first and last frame's part orientations: Euler values
    may differ by an equivalent representation (x+180, 180-y, z+180 or 360 turns) at the wrap,
    which the runtime shows as the same pose."""
    bones = bones or mcrig.EXPORT_BONES
    worst = 0.0
    for b in bones:
        r0 = samples[0].get(b, {}).get("rot", (0, 0, 0))
        r1 = samples[-1].get(b, {}).get("rot", (0, 0, 0))
        m0 = mcrig.rot_zyx(*[math.radians(v) for v in r0])
        m1 = mcrig.rot_zyx(*[math.radians(v) for v in r1])
        cosang = max(-1.0, min(1.0, (np.trace(m0.T @ m1) - 1.0) / 2.0))
        worst = max(worst, math.degrees(math.acos(cosang)))
    return round(worst, 3)


def _mug_pixels():
    try:
        img = bpy.data.images.load(ALE_PNG, check_existing=True)
        w, h = img.size
        px = np.array(img.pixels[:]).reshape(h, w, 4)
        ys, xs = np.nonzero(px[..., 3] > 0)
        return [np.array([x + 0.5, y + 0.5, 8.0]) for x, y in zip(xs, ys)]
    except Exception:
        return []


MUG_PIXELS = _mug_pixels()


def mug_head_audit(samples, mug_sides, drink_px=3.0):
    """Frames where the mug's opaque pixels enter the head cube (x -4..4, y -8..0, z -4..4, head
    space) while its rim is NOT at the lips (a drink beat). Must be 0."""
    bad, drink = [], 0
    for i, s in enumerate(samples):
        w = mcrig.pose_matrices(s)
        inv = np.linalg.inv(w["head"])
        for side in mug_sides:
            rim = mug_point(w, side, MUG_RIM_NEAR)
            drinking = float(np.linalg.norm(rim - mcrig.xform(w["head"], MOUTH))) < drink_px
            m = item_frame(w, side)
            inside = 0
            for p in MUG_PIXELS[::2]:
                q = mcrig.xform(inv, mcrig.xform(m, p))
                if -4.0 < q[0] < 4.0 and -8.0 < q[1] < 0.0 and -4.0 < q[2] < 4.0:
                    inside += 1
            if inside:
                if drinking:
                    drink += 1
                else:
                    bad.append(round(i / FPS, 2))
    return {"mug_in_head_frames": len(bad), "first_bad_t": bad[:6], "drink_contact_frames": drink}


def mug_hand_audit(samples, mug_sides):
    """Largest slide of the mug in the fist (px, wrist bone position): bounded by MAX_SLIDE."""
    worst = 0.0
    for s in samples:
        for side in mug_sides:
            p = s.get(side + "_item", {}).get("pos", (0, 0, 0))
            worst = max(worst, float(np.linalg.norm(p)))
    return round(worst, 3)


def mirror_channels(ch):
    """Left-right mirror of a pose (model x flip): swap right_*/left_*, rot (x,-y,-z), pos (-x,y,z)."""
    out = {}
    for bone, kinds in ch.items():
        nb = bone.replace("right_", "#SWAP#").replace("left_", "right_").replace("#SWAP#", "left_")
        d = {}
        for k, v in kinds.items():
            if k == "rot":
                d[k] = (v[0], -v[1], -v[2])
            elif k == "pos":
                d[k] = (-v[0], v[1], v[2])
            else:
                d[k] = v
        out[nb] = d
    return out


def mirrored(solve):
    def m(t):
        return mirror_channels(solve(t))
    m.prev = solve.prev
    m.inner = solve
    return m


def run_clip(spec, a):
    """spec: dict(const, slug, L, loop, texture, scene(fn -> objs), keys(fn), solve(fn t->ch),
    bones, keep, props, sounds, mugs[(side, windows)], cams, checks(fn times, samples -> dict), note)."""
    import time as _time
    _t0 = _time.time()
    def stage(name):
        print("STAGE", spec["const"], name, round(_time.time() - _t0, 1), flush=True)
    hsrig.reset()
    objs = spec["scene"]()
    spec["keys"]()
    solve = spec["solve"]
    if hasattr(solve, "inner"):
        solve.inner.prev = {}
        solve.prev = solve.inner.prev
    else:
        solve.prev = {}
    stage("scene")
    warm(solve, spec["L"])
    stage("warm")
    times, samples = hsrig.bake(solve, spec["L"], objs)
    stage("bake")
    for side, windows in spec.get("mugs", []):
        fr = attach_mug(objs, side)
        key_visible(fr, windows, spec["L"])
    checks = {"length": spec["L"], "loop": spec["loop"]}
    if spec["loop"]:
        checks["loop_seam"] = lk.loop_seam(samples, spec.get("bones"))
        checks["loop_seam_pose_deg"] = pose_seam(samples, spec.get("bones"))
    else:
        checks["ends_at_rest"] = lk.ends_at_rest(samples, spec.get("bones"))
    if spec.get("checks"):
        checks.update(spec["checks"](times, samples))
    if spec.get("mugs"):
        checks.update(mug_head_audit(samples, [m[0] for m in spec["mugs"]]))
        checks["mug_slide_in_fist_px"] = mug_hand_audit(samples, [m[0] for m in spec["mugs"]])
    print("CHECKS", spec["const"], json.dumps(checks), flush=True)
    meta = {"source": "tools/blender/pipeline/clips/tavern/" + spec["script"] + " (Blender "
                      + bpy.app.version_string + ")",
            "lane": "tavern animation (26 Sep)", "contract": spec.get("note", ""), "checks": checks}
    if spec.get("variant_of"):
        meta["variant_of"] = spec["variant_of"]
    meta.update(spec.get("extra_meta", {}))
    path, doc, rep, worst = export(spec["const"], spec["L"], spec["loop"], times, samples,
                                   spec.get("keep", (0.0, spec["L"])), meta, bones=spec.get("bones"),
                                   props=spec.get("props"), sounds=spec.get("sounds"), write=a["export"])
    stage("export")
    report(spec["slug"], {"checks": checks, "channels": rep, "roundtrip_max_err": worst})
    os.makedirs(os.path.join(lk.WORK, "out", "tavern"), exist_ok=True)
    try:
        bpy.ops.wm.save_as_mainfile(filepath=os.path.join(lk.WORK, "out", "tavern", spec["slug"] + ".blend"))
    except Exception as e:  # a failed .blend save never blocks the export
        print("blend save failed", e)
    cams = spec.get("cams")
    cams = cams() if callable(cams) else cams
    preview(spec["slug"], spec["L"], a, cams=cams, loops=spec.get("loops", 2 if spec["loop"] else 1))
    return checks


def args():
    return hsrig.parse_args()
