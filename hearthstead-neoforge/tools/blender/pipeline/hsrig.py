"""Hearthstead settler rig for Blender (headless-safe). Shared by every clip script.

Layout of a scene built by `build_scene()`:
  MC_SPACE            fixed conversion object: Minecraft model space -> Blender
                      (x -> x, y(down) -> -z, z -> y), scaled 1/16 so 1 BU = 1 block.
    part:<name>       one Empty per ModelPart, rotation_mode 'XYZ' (== ModelPart
                      rotationZYX), location = pivot + posVec offset in PIXELS.
      mesh:<name>     textured cuboids with Minecraft box-UV on the settler atlas.
    item:right_frame  the vanilla held-item frame (see mcrig.item_in_hand_matrix),
                      child of the part:right_item wrist bone when the rig has one.
  CTRL                animator controls: custom float properties with F-curves
                      (Bezier, per-key easing, cyclic). Clip scripts read them
                      through `ctrl(name, t)` and turn them into channels with
                      FK + analytic IK (mcrig.two_bone).

`bake()` evaluates a clip's solve(t) at every frame, keys the part Empties (so
the .blend plays back in the Blender UI without running any script) and
returns dense channel samples for export_mc_clip.
"""

from __future__ import annotations

import math
import os
import sys

import bpy
import numpy as np
from mathutils import Matrix

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import mcrig  # noqa: E402

FPS = 60
# model y=24 is the ground (the renderer's translate(0,-1.501,0)): lift 1.5 blocks
MC_TO_BLENDER = Matrix.Translation((0, 0, 1.5)) \
    @ Matrix(((1, 0, 0, 0), (0, 0, 1, 0), (0, -1, 0, 0), (0, 0, 0, 1))) \
    @ Matrix.Scale(1.0 / 16.0, 4)


# --------------------------------------------------------------------------- scene
def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.render.fps = FPS
    sc.render.fps_base = 1.0
    return sc


def _material(name, image_path=None, colour=(0.5, 0.5, 0.5, 1.0), rough=0.9):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    bsdf.inputs["Roughness"].default_value = rough
    if image_path:
        img = bpy.data.images.load(image_path, check_existing=True)
        tex = nt.nodes.new("ShaderNodeTexImage")
        tex.image = img
        tex.interpolation = "Closest"
        nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
        nt.links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
        mat.blend_method = "CLIP" if hasattr(mat, "blend_method") else None
    else:
        bsdf.inputs["Base Color"].default_value = colour
    mat.diffuse_color = colour
    return mat


def _box_uv_faces(u, v, w, h, d, mirror):
    """Minecraft ModelPart.Cube UV rectangles (pixels) for each face."""
    f7, f8, f9 = u, u + d, u + d + w
    f10, f11, f12 = u + d + w + w, u + d + w + d, u + d + w + d + w
    f13, f14, f15 = v, v + d, v + d + h
    faces = {
        "top": (f8, f13, f9, f14),      # model -Y (world up)
        "bottom": (f9, f13, f10, f14),  # model +Y
        "east": (f7, f14, f8, f15),     # model -X side (settler's right)
        "north": (f8, f14, f9, f15),    # model -Z (front)
        "west": (f9, f14, f11, f15),    # model +X side (settler's left)
        "south": (f11, f14, f12, f15),  # model +Z (back)
    }
    if mirror:
        faces["east"], faces["west"] = faces["west"], faces["east"]
        faces = {k: (r[2], r[1], r[0], r[3]) for k, r in faces.items()}
    return faces


def cuboid_mesh(name, cubes, tex_w=128, tex_h=64):
    """cubes: [(from, size, (u,v), mirror, inflate)] in the part's local pixel space."""
    verts, faces, uvs = [], [], []
    for (fx, fy, fz), (w, h, d), (u, v), mirror, infl in cubes:
        x0, y0, z0 = fx - infl, fy - infl, fz - infl
        x1, y1, z1 = fx + w + infl, fy + h + infl, fz + d + infl
        rect = _box_uv_faces(u, v, w, h, d, mirror)
        quads = {
            # vertices listed so the first vertex maps to the UV rect's (u0, v0) corner
            "north": [(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)],
            "south": [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)],
            "east": [(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)],
            "west": [(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)],
            "top": [(x1, y0, z1), (x0, y0, z1), (x0, y0, z0), (x1, y0, z0)],
            "bottom": [(x1, y1, z0), (x0, y1, z0), (x0, y1, z1), (x1, y1, z1)],
        }
        for face, q in quads.items():
            base = len(verts)
            verts.extend(q)
            faces.append((base, base + 3, base + 2, base + 1))  # outward in model space
            a0, b0, a1, b1 = rect[face]
            corner = [(a0, b0), (a1, b0), (a1, b1), (a0, b1)]
            uvs.append([corner[0], corner[3], corner[2], corner[1]])
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    uv = me.uv_layers.new(name="UVMap")
    li = 0
    for poly, puv in zip(me.polygons, uvs):
        for k, loop in enumerate(poly.loop_indices):
            uu, vv = puv[k]
            uv.data[loop].uv = (uu / tex_w, 1.0 - vv / tex_h)
            li += 1
    me.update()
    return me


def _empty(name, parent=None):
    ob = bpy.data.objects.new(name, None)
    bpy.context.scene.collection.objects.link(ob)
    ob.empty_display_size = 2.0
    ob.rotation_mode = "XYZ"
    if parent is not None:
        ob.parent = parent
    return ob


def build_scene(texture, axe_png=None, parts=None, hidden=("nose",)):
    """Creates MC_SPACE + part Empties + meshes. Returns dict of part Empties."""
    sc = bpy.context.scene
    space = _empty("MC_SPACE")
    space.matrix_basis = MC_TO_BLENDER
    mat = _material("settler", texture)
    objs = {}
    for name in mcrig.order():
        parent, pivot, cubes = mcrig.PARTS[name]
        e = _empty("part:" + name, objs[parent] if parent else space)
        e.location = pivot
        objs[name] = e
        if cubes:
            me = cuboid_mesh("mesh:" + name, cubes)
            me.materials.append(mat)
            mo = bpy.data.objects.new("mesh:" + name, me)
            sc.collection.objects.link(mo)
            mo.parent = e
            if name.endswith("_leg") or name.endswith("_shin"):
                mo.scale = (mcrig.LEG_GIRTH[0], 1.0, mcrig.LEG_GIRTH[2])
    # Vanilla held-item frame. If the rig has a wrist bone (mcrig.PARTS "right_item",
    # pivot at the palm) the frame hangs off it, so keyed item rotation/slide shows
    # in previews; otherwise it hangs off the forearm (legacy rigs). Stored under
    # "right_item_frame" so bake() never keys it as a part.
    wrist = objs.get("right_item")
    if wrist is not None and "right_item" in mcrig.PARTS:
        rel = mcrig.item_in_hand_matrix(None, True, item_world=np.eye(4))
        frame = _empty("item:right_frame", wrist)
    else:
        rel = mcrig.item_in_hand_matrix(np.eye(4))   # == T(0,-4,0) @ item chain
        frame = _empty("item:right_frame", objs["right_forearm"])
    frame.matrix_basis = Matrix(rel.tolist())
    objs["right_item_frame"] = frame
    if axe_png:
        objs["axe_mesh"] = sprite_mesh("mesh:axe", axe_png, frame)
    return objs


def sprite_mesh(name, png, parent):
    """Extruded 1-px-deep voxel mesh of an item sprite (like item/generated)."""
    img = bpy.data.images.load(png, check_existing=True)
    w, h = img.size
    px = np.array(img.pixels[:]).reshape(h, w, 4)[::-1]   # row 0 = top
    verts, faces, cols = [], [], []
    for v in range(h):
        for u in range(w):
            if px[v, u, 3] <= 0.0:
                continue
            x0, x1 = u, u + 1
            y1, y0 = 16 - v, 16 - v - 1
            z0, z1 = 7.5, 8.5
            b = len(verts)
            verts += [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
                      (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
            for f in ((0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (2, 3, 7, 6), (1, 2, 6, 5), (0, 4, 7, 3)):
                faces.append(tuple(b + i for i in f))
                cols.append(tuple(px[v, u]))
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    attr = me.color_attributes.new("Col", "FLOAT_COLOR", "CORNER")
    for poly, c in zip(me.polygons, cols):
        for li in poly.loop_indices:
            attr.data[li].color = c
    mat = bpy.data.materials.new("sprite")
    mat.use_nodes = True
    nt = mat.node_tree
    vc = nt.nodes.new("ShaderNodeVertexColor")
    vc.layer_name = "Col"
    nt.links.new(vc.outputs["Color"], nt.nodes["Principled BSDF"].inputs["Base Color"])
    nt.nodes["Principled BSDF"].inputs["Roughness"].default_value = 0.5
    me.materials.append(mat)
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    ob.parent = parent
    return ob


def prop_box(name, frm, size, colour, parent_name="MC_SPACE"):
    """Static cuboid in MC model space (pixels), e.g. a tree trunk or the ground."""
    me = bpy.data.meshes.new(name)
    x0, y0, z0 = frm
    x1, y1, z1 = x0 + size[0], y0 + size[1], z0 + size[2]
    v = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
         (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    f = [(0, 1, 2, 3), (4, 7, 6, 5), (0, 4, 5, 1), (2, 6, 7, 3), (1, 5, 6, 2), (0, 3, 7, 4)]
    me.from_pydata(v, [], f)
    me.materials.append(_material(name + "_mat", colour=colour))
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    ob.parent = bpy.data.objects[parent_name]
    return ob


# --------------------------------------------------------------------------- controls
def controls():
    ob = bpy.data.objects.get("CTRL")
    if ob is None:
        ob = _empty("CTRL")
        ob.animation_data_create()
        ob.animation_data.action = bpy.data.actions.new("CTRL_action")
    return ob


def _fcurve(prop):
    ob = controls()
    if prop not in ob.keys():
        ob[prop] = 0.0
    path = f'["{prop}"]'
    act = ob.animation_data.action
    fc = act.fcurves.find(path)
    if fc is None:
        fc = act.fcurves.new(path)
    return fc


def key_curve(prop, keys, cyclic=True, length=1.0):
    """keys: [(t_seconds, value, interp='BEZIER', easing='AUTO')]. Replaces the curve."""
    fc = _fcurve(prop)
    fc.keyframe_points.clear()
    for k in keys:
        t, val = k[0], k[1]
        interp = k[2] if len(k) > 2 else "BEZIER"
        easing = k[3] if len(k) > 3 else "AUTO"
        kp = fc.keyframe_points.insert(t * FPS, val, options={"FAST"})
        kp.interpolation = interp
        kp.easing = easing
        kp.handle_left_type = kp.handle_right_type = "AUTO_CLAMPED"
    if cyclic and not any(m.type == "CYCLES" for m in fc.modifiers):
        fc.modifiers.new("CYCLES")
    fc.update()
    return fc


_curves_cache = {}


def ctrl(prop, t):
    """Value of control `prop` at time t (seconds). Missing props read as 0."""
    ob = controls()
    fc = ob.animation_data.action.fcurves.find(f'["{prop}"]')
    if fc is None:
        return 0.0
    return fc.evaluate(t * FPS)


# --------------------------------------------------------------------------- bake/export
def _unwrap(prev, cur):
    if prev is None:
        return cur
    return [c + 360.0 * round((p - c) / 360.0) for p, c in zip(prev, cur)]


def euler_deg_continuous(m3, prev):
    """ZYX euler (deg) closest to `prev` among the two equivalent solutions."""
    x, y, z = mcrig.euler_from_matrix(m3)
    a = [math.degrees(x), math.degrees(y), math.degrees(z)]
    b = [a[0] + 180.0, 180.0 - a[1], a[2] + 180.0]
    a, b = _unwrap(prev, a), _unwrap(prev, b)
    if prev is None:
        return a
    da = sum((p - c) ** 2 for p, c in zip(prev, a))
    db = sum((p - c) ** 2 for p, c in zip(prev, b))
    return a if da <= db else b


def bake(solve, length, objs, fps=FPS):
    """Evaluate solve(t)->channels for every frame, key the part Empties, return samples."""
    sc = bpy.context.scene
    sc.frame_start, sc.frame_end = 0, int(round(length * fps))
    times, samples = [], []
    for f in range(sc.frame_start, sc.frame_end + 1):
        t = f / fps
        ch = solve(t)
        times.append(t)
        samples.append(ch)
        for name, e in objs.items():
            if name not in mcrig.PARTS:
                continue
            pivot = mcrig.PARTS[name][1]
            c = ch.get(name, {})
            rot = c.get("rot", (0, 0, 0))
            pos = c.get("pos", (0, 0, 0))
            e.location = (pivot[0] + pos[0], pivot[1] - pos[1], pivot[2] + pos[2])
            e.rotation_euler = tuple(math.radians(r) for r in rot)
            e.keyframe_insert("location", frame=f)
            e.keyframe_insert("rotation_euler", frame=f)
    for e in objs.values():
        ad = e.animation_data
        if ad and ad.action:
            for fc in ad.action.fcurves:
                for kp in fc.keyframe_points:
                    kp.interpolation = "LINEAR"
    return times, samples


def to_export_channels(times, samples, bones=None):
    bones = bones or mcrig.EXPORT_BONES
    out = {}
    for b in bones:
        rot = [list(s.get(b, {}).get("rot", (0, 0, 0))) for s in samples]
        out[b] = {"rotation": rot}
        if b not in mcrig.ROTATION_ONLY:
            out[b]["position"] = [list(s.get(b, {}).get("pos", (0, 0, 0))) for s in samples]
    return out


# --------------------------------------------------------------------------- render
def setup_render(res=(960, 720), engine="BLENDER_EEVEE_NEXT", samples=16):
    sc = bpy.context.scene
    try:
        sc.render.engine = engine
    except TypeError:
        sc.render.engine = "BLENDER_EEVEE"
    if hasattr(sc, "eevee"):
        sc.eevee.taa_render_samples = samples
    sc.render.resolution_x, sc.render.resolution_y = res
    sc.render.film_transparent = False
    world = bpy.data.worlds.new("sky")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.55, 0.72, 0.95, 1)
    world.node_tree.nodes["Background"].inputs["Strength"].default_value = 0.8
    sc.world = world
    sun = bpy.data.lights.new("sun", "SUN")
    sun.energy = 3.5
    so = bpy.data.objects.new("sun", sun)
    sc.collection.objects.link(so)
    so.rotation_euler = (math.radians(50), math.radians(10), math.radians(35))
    sc.view_settings.view_transform = "Standard"
    return sc


def camera(name, loc_blocks, target_blocks, lens=50):
    cam = bpy.data.cameras.new(name)
    cam.lens = lens
    ob = bpy.data.objects.new(name, cam)
    bpy.context.scene.collection.objects.link(ob)
    ob.location = loc_blocks
    tgt = _empty(name + "_target")
    tgt.location = target_blocks
    c = ob.constraints.new("TRACK_TO")
    c.target = tgt
    c.track_axis = "TRACK_NEGATIVE_Z"
    c.up_axis = "UP_Y"
    return ob


def render_frames(cam, out_dir, frames):
    sc = bpy.context.scene
    sc.camera = cam
    os.makedirs(out_dir, exist_ok=True)
    for f in frames:
        sc.frame_set(f)
        sc.render.filepath = os.path.join(out_dir, f"f{f:04d}.png")
        bpy.ops.render.render(write_still=True)


# --------------------------------------------------------------------------- CLI + previews
def parse_args(argv=None):
    """Common clip-script flags (after Blender's `--`).

    --fast   Workbench 480x360: ONE contact sheet PNG + ONE normal-speed side MP4.
    --full   Eevee: side + front34 MP4s (normal and half speed) + contact sheets.
    --render legacy alias of --full (kept for old scripts).
    --out D  output folder; --no-export skips writing into the mod's assets.
    """
    argv = sys.argv[sys.argv.index("--") + 1:] if argv is None and "--" in sys.argv else (argv or [])
    a = {"fast": "--fast" in argv, "full": "--full" in argv or "--render" in argv,
         "export": "--no-export" not in argv, "out": None, "rest": []}
    if "--out" in argv:
        a["out"] = argv[argv.index("--out") + 1]
    a["rest"] = [x for x in argv if not x.startswith("--") and x != a["out"]]
    return a


def default_cameras():
    """Side (settler's right) and front three-quarter cameras, in blocks."""
    return {
        "side": camera("cam_side", (-3.8, -0.2, 1.05), (0.0, -0.2, 0.9), lens=40),
        "front34": camera("cam_front34", (-2.5, -2.6, 1.45), (0.0, -0.1, 0.95), lens=40),
    }


def _sheet(pngs, out_png, cols=6):
    """Contact sheet with numpy + bpy.images (no PIL/ffmpeg dependency)."""
    imgs = []
    for p in pngs:
        im = bpy.data.images.load(p, check_existing=False)
        w, h = im.size
        imgs.append(np.array(im.pixels[:], dtype=np.float32).reshape(h, w, 4))
        bpy.data.images.remove(im)
    h, w = imgs[0].shape[:2]
    rows = (len(imgs) + cols - 1) // cols
    sheet = np.full((rows * (h + 4), cols * (w + 4), 4), 0.13, np.float32)
    sheet[..., 3] = 1.0
    for i, im in enumerate(imgs):
        r, c = divmod(i, cols)
        y0 = (rows - 1 - r) * (h + 4)          # bpy pixel rows start at the bottom
        sheet[y0:y0 + h, c * (w + 4):c * (w + 4) + w] = im
    H, W = sheet.shape[:2]
    out = bpy.data.images.new("sheet", W, H, alpha=True)
    out.pixels[:] = sheet.ravel()
    out.filepath_raw = out_png
    out.file_format = "PNG"
    out.save()
    bpy.data.images.remove(out)


def _mp4(cam, path, frames, fps):
    sc = bpy.context.scene
    sc.camera = cam
    sc.frame_start, sc.frame_end = frames[0], frames[-1]
    sc.render.fps = fps
    sc.render.image_settings.file_format = "FFMPEG"
    sc.render.ffmpeg.format = "MPEG4"
    sc.render.ffmpeg.codec = "H264"
    sc.render.ffmpeg.constant_rate_factor = "HIGH"
    sc.render.filepath = path
    bpy.ops.render.render(animation=True)
    sc.render.fps = FPS
    sc.render.image_settings.file_format = "PNG"


def preview(out_dir, length, fast=True, full=False, loops=2, cams=None, sheet_frames=12):
    """Render the baked clip. fast: Workbench sheet + side MP4. full: Eevee, both cams, +half speed."""
    sc = bpy.context.scene
    os.makedirs(out_dir, exist_ok=True)
    if full:
        setup_render(res=(960, 720))
    else:
        setup_render(res=(480, 360))
        sc.render.engine = "BLENDER_WORKBENCH"
        sh = sc.display.shading
        sh.light = "STUDIO"
        sh.color_type = "TEXTURE"
        sh.show_shadows = True
        sc.display.render_aa = "8"
    cams = cams or default_cameras()
    n = int(round(length * FPS))
    # contact sheet from the side camera
    idx = [int(round(i * n / sheet_frames)) for i in range(sheet_frames)]
    tmp = os.path.join(out_dir, "_sheet_frames")
    render_frames(cams["side"], tmp, idx)
    _sheet([os.path.join(tmp, f"f{f:04d}.png") for f in idx], os.path.join(out_dir, "sheet_side.png"))
    # loop the baked cycle by repeating frames through a cycles modifier on part actions
    for ob in bpy.data.objects:
        ad = ob.animation_data
        if ob.name.startswith("part:") and ad and ad.action:
            for fc in ad.action.fcurves:
                if not any(m.type == "CYCLES" for m in fc.modifiers):
                    fc.modifiers.new("CYCLES")
    frames = list(range(0, n * loops))
    _mp4(cams["side"], os.path.join(out_dir, "side.mp4"), frames, FPS)
    if full:
        _mp4(cams["front34"], os.path.join(out_dir, "front34.mp4"), frames, FPS)
        _mp4(cams["side"], os.path.join(out_dir, "side_half.mp4"), frames, FPS // 2)
        _mp4(cams["front34"], os.path.join(out_dir, "front34_half.mp4"), frames, FPS // 2)
        render_frames(cams["front34"], tmp + "_f", idx)
        _sheet([os.path.join(tmp + "_f", f"f{f:04d}.png") for f in idx],
               os.path.join(out_dir, "sheet_front34.png"))
    print("PREVIEW", out_dir)
