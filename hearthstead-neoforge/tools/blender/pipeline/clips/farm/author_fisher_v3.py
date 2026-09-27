"""FISHER v3 (owner 27 Sep: "bigger rod, actually SEE him fishing"). Run headless:

    blender -b --factory-startup -t 2 --python clips/farm/author_fisher_v3.py -- [--no-export] [--reel] [--eevee]

1. Exports FISHER_CAST_V3, FISHER_WAIT (+__v2), FISHER_STRIKE_REEL and
   FISHER_LAND_FISH from fisher_v3_spec.py (keys, left-hand IK onto the reel
   crank and the landed fish) into assets/hearthstead/animations/settler/.
2. --reel: renders the whole loop as the game plays it (cast -> wait -> bite +
   reel -> land), SEATED on the shore chair, with the v3 rod built from
   models/item/fishers_rod.json, the line with its sag, the bobber, the splash
   beats and the perch coming out of the water into the left hand. Side and
   front three-quarter MP4s + contact sheets -> videos/blender/clips/fisher_v3/.
"""

import json
import math
import os
import shutil
import struct
import sys
import zlib

import bpy
import numpy as np
from mathutils import Matrix

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import farmkit as fk  # noqa: E402
import fisher_v3_spec as S  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402

ARGS = fk.argv()
REPO = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", ".."))
ASSETS = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead")
VIDEOS = os.environ.get("HS_FISHER_VIDEOS", r"C:\Users\tobia\Hearthstead-Claude\videos\blender\clips\fisher_v3")
OUT = os.path.join(fk.WORK, "out", "fisher_v3")
os.makedirs(OUT, exist_ok=True)
FPS = hsrig.FPS


# --------------------------------------------------------------------------- export
def cloak_for(clip, t):
    """Short shoulder cape: hangs with half the spine pitch, trails the spine speed."""
    dt = 1.0 / 60.0
    def tx(u):
        u = u % clip.length if clip.loop else max(0.0, min(clip.length, u))
        return clip.channels(u)["torso"]["rot"]
    a, b = tx(t - 0.05), tx(t - 0.05 - dt)
    pv, yv = (a[0] - b[0]) / dt, (a[1] - b[1]) / dt
    x = 3.0 + 0.45 * tx(t)[0] - 0.06 * pv
    return (max(-8.0, min(28.0, x)), 0.0, max(-10.0, min(10.0, -0.018 * yv)))


def samples_of(clip):
    n = int(round(clip.length * FPS))
    times, samples = [], []
    for f in range(n + 1):
        t = f / FPS
        ch = clip.channels(t)
        ch["cloak"] = {"rot": cloak_for(clip, t)}
        for b in mcrig.EXPORT_BONES:
            ch.setdefault(b, {"rot": (0.0, 0.0, 0.0)})
        times.append(t)
        samples.append(ch)
    fk.fix_wraps(samples)
    return times, samples


def export_all():
    report = {}
    for clip in S.all_clips():
        times, samples = samples_of(clip)
        keep = sorted({0.0, clip.length, *[round(v, 4) for v in clip.beats.values()]})
        meta = {"source": "tools/blender/pipeline/clips/farm/author_fisher_v3.py + fisher_v3_spec.py (Blender "
                          + bpy.app.version_string + ")",
                "contract": f"{clip.const} {clip.length:.2f} s {'loop' if clip.loop else 'one-shot'}; seated on the "
                            "fisher chair (runtime replaces the legs); SettlerModel.applyFishingPose plays it on the "
                            "FisherWorkGoal phase window",
                "beats": clip.beats}
        path, doc, rep, worst = fk.export(clip.const, clip.length, clip.loop, times, samples, keep_times=tuple(keep),
                                          meta=meta)
        report[clip.const] = {"path": path, "keys": sum(v["keys"] for v in rep.values()), "roundtrip": worst,
                              "loop_seam": fk.loop_seam(samples) if clip.loop else None}
    print("CHECKS", json.dumps(report))
    return report


# --------------------------------------------------------------------------- block-model props
def png_avg(path):
    """Average opaque colour of a small PNG (8-bit RGBA/RGB, non-interlaced)."""
    try:
        img = bpy.data.images.load(path, check_existing=False)
        px = np.array(img.pixels[:], dtype=np.float32).reshape(-1, 4)
        bpy.data.images.remove(img)
        px = px[px[:, 3] > 0.5]
        return (*[float(v) for v in px[:, :3].mean(axis=0)], 1.0) if len(px) else (0.5, 0.5, 0.5, 1.0)
    except Exception:  # noqa: BLE001
        return (0.5, 0.5, 0.5, 1.0)


def model_cubes(model_json):
    """[(colour, [(from, size)])] grouped by texture, in the item model's own px (y UP)."""
    d = json.load(open(model_json))
    colours = {}
    for key, ref in d["textures"].items():
        ns, rel = ref.split(":")
        colours[key] = png_avg(os.path.join(ASSETS, "textures", rel + ".png"))
    groups = {}
    for e in d["elements"]:
        tex = next(iter(e["faces"].values()))["texture"].lstrip("#")
        f, t = e["from"], e["to"]
        groups.setdefault(tex, []).append((tuple(f), tuple(b - a for a, b in zip(f, t))))
    return [(colours.get(k, (0.5, 0.5, 0.5, 1)), v) for k, v in groups.items()]


def item_object(name, model_json, parent, rel):
    """Item model under `parent` with the 4x4 `rel` (item px -> parent px). Item y is UP, like MC."""
    e = bpy.data.objects.new(name, None)
    bpy.context.scene.collection.objects.link(e)
    e.parent = parent
    e.matrix_basis = Matrix(rel.tolist())
    for i, (rgba, cubes) in enumerate(model_cubes(model_json)):
        fk.cubes_object(f"{name}:{i}", cubes, rgba, e)
    return e


# --------------------------------------------------------------------------- the reel
SEG = [("FISHER_CAST_V3", 0.0, 1.70), ("FISHER_WAIT__V2", 4.0, 8.0), ("FISHER_STRIKE_REEL", 0.0, 2.20),
       ("FISHER_LAND_FISH", 0.0, 2.50), ("HOLD_END", 0.0, 0.6)]
WATER_Y = 25.6                      # water surface just below the bank (model px, +Y down)
TARGET = np.array([-6.0, WATER_Y, -58.0])  # the bobber's water cell: 3.5 blocks out, a touch to the right
SHORE = np.array([-4.0, WATER_Y, -14.0])   # where the reel drags the fish to


def timeline():
    clips = {c.const: c for c in S.all_clips()}
    out, t0 = [], 0.0
    for const, a, b in SEG:
        out.append((const, clips.get(const), a, b, t0))
        t0 += b - a
    return out, t0


def sample_timeline(tl, t):
    for const, clip, a, b, t0 in tl:
        if t < t0 + (b - a) - 1e-9 or const == "HOLD_END":
            local = a + (t - t0)
            if const == "HOLD_END":
                clip = [c for n, c, *_ in tl if n == "FISHER_LAND_FISH"][0]
                local = clip.length
            ch = clip.channels(min(local, clip.length))
            ch["cloak"] = {"rot": cloak_for(clip, min(local, clip.length))}
            return const, local, clip, ch
    raise ValueError(t)


def bob(t):
    return 0.35 * math.sin(t * 2.6) + 0.18 * math.sin(t * 6.1 + 1.0)


def props_at(const, local, clip, w, rodm, t, release_tip):
    """(tip, bobber, fish or None, sag px, splash strength) in model px."""
    tip = mcrig.xform(rodm, S.ROD_TIP)
    fish = None
    splash = 0.0
    if const == "FISHER_CAST_V3":
        if local < 0.80:
            return tip, tip + np.array([0.0, 5.0, 0.0]), None, 0.0, 0.0
        u = S.smooth((local - 0.80) / 0.45) if local < 1.25 else 1.0
        start = release_tip if release_tip is not None else tip
        p = start + (TARGET - start) * u
        p[1] -= 26.0 * math.sin(math.pi * u)
        if local >= 1.25:
            p = TARGET + np.array([0.0, bob(t), 0.0])
            splash = max(0.0, 1.0 - (local - 1.25) / 0.35)
        return tip, p, None, 0.0 if local < 1.25 else 8.0 * min(1.0, (local - 1.25) / 0.3), splash
    if const == "FISHER_WAIT__V2":
        nib = 0.0
        for n0 in (5.1, 6.9):                    # two little nibbles on the float
            if n0 <= local < n0 + 0.25:
                nib = 0.9 * math.sin(math.pi * (local - n0) / 0.25)
        return tip, TARGET + np.array([0.0, bob(t) + nib, 0.0]), None, 8.0, 0.0
    if const == "FISHER_STRIKE_REEL":
        dip = 3.2 * math.sin(math.pi * min(1.0, local / 0.30)) if local < 0.30 else 0.0
        splash = max(0.0, 1.0 - local / 0.4)
        u = S.smooth((local - 0.40) / 1.70)
        p = TARGET + (SHORE - TARGET) * u
        p[0] += 2.5 * math.sin(local * 9.0) * (1 - u * 0.5)      # the fish runs side to side
        p[1] += dip + 0.6 + 0.4 * math.sin(local * 13.0)         # pulled half under
        return tip, p, None, 1.0 if local > 0.3 else 5.0, max(splash, 0.35 * (local > 0.5))
    # land + hold
    water = SHORE + np.array([0.0, 1.0, 0.0])
    lt = min(local, S.land_fish().length)
    fish = S.fish_point(lt, w, rodm, water)
    d = tip - fish
    bobber = fish + d / max(1e-6, np.linalg.norm(d)) * 5.0
    splash = max(0.0, 1.0 - lt / 0.3)
    return tip, bobber, fish, 0.6, splash


def build_reel(eevee=False):
    hsrig.reset()
    objs = hsrig.build_scene(fk.tex("fisher"), None)
    for side in ("right", "left"):
        bone = next((o for o in bpy.data.objects if o.name.startswith(f"part:{side}_item")
                     and o.parent is objs[f"{side}_forearm"]), None)
        if bone is not None:
            bone.rotation_mode = "XYZ"
            objs[f"{side}_item"] = bone
    sc = bpy.context.scene
    # bank + water + chair (fishers_chair.json, facing the water = north / -Z)
    fk.box("bank", (-72, 24, -8), (144, 20, 80), (0.33, 0.50, 0.25, 1))
    fk.box("bank_dirt", (-72, 26, -9), (144, 18, 1.2), (0.42, 0.31, 0.20, 1))
    fk.box("water", (-160, WATER_Y, -200), (320, 14, 191), (0.18, 0.36, 0.56, 1))
    fk.box("lakebed", (-160, 40, -200), (320, 2, 192), (0.35, 0.30, 0.22, 1))
    chair = os.path.join(ASSETS, "models", "block", "fishers_chair.json")
    # block px (x, y up, z) -> model px: x-8, 24-y, z-8
    item_object("chair", chair, bpy.data.objects["MC_SPACE"],
                mcrig.T(-8, 24, -8) @ mcrig.mat4(np.diag([1.0, -1.0, 1.0])))
    # the v3 rod on the wrist bone, exactly the runtime chain (fisher_v3_spec.rod_matrix)
    rod_rel = np.linalg.inv(np.eye(4)) @ (mcrig.T(0, -10, 0) @ mcrig.mat4(mcrig.rx(-90 * mcrig.DEG))
                                         @ mcrig.mat4(mcrig.ry(180 * mcrig.DEG)) @ mcrig.T(1, 2, -10))
    (rxd, ryd, rzd), tr, s = S.ROD_DISPLAY
    rod_rel = rod_rel @ mcrig.T(*tr) @ mcrig.mat4(mcrig.rx(rxd * mcrig.DEG) @ mcrig.ry(ryd * mcrig.DEG)
                                                  @ mcrig.rz(rzd * mcrig.DEG), s=s) @ mcrig.T(-8, -8, -8)
    item_object("rod", os.path.join(ASSETS, "models", "item", "fishers_rod.json"), objs["right_item"], rod_rel)
    # FisherOutfitLayer hip basket (left hip) so hands can be judged against it
    fk.cubes_object("hip_basket", [((4.7, -1.0, -1.5), (3.3, 3.8, 3.0))], (0.69, 0.60, 0.44, 1), objs["torso"])
    # bobber, fish, splash
    space = bpy.data.objects["MC_SPACE"]
    bobber = bpy.data.objects.new("bobber", None)
    sc.collection.objects.link(bobber)
    bobber.parent = space
    fk.cubes_object("bobber:red", [((-1.1, -2.2, -1.1), (2.2, 1.6, 2.2))], (0.78, 0.16, 0.12, 1), bobber)
    fk.cubes_object("bobber:white", [((-1.1, -0.6, -1.1), (2.2, 1.4, 2.2))], (0.93, 0.90, 0.82, 1), bobber)
    fk.cubes_object("bobber:stem", [((-0.3, -3.4, -0.3), (0.6, 1.3, 0.6))], (0.30, 0.22, 0.14, 1), bobber)
    fish = bpy.data.objects.new("fish", None)
    sc.collection.objects.link(fish)
    fish.parent = space
    perch = os.path.join(ASSETS, "models", "item", "river_perch.json")
    item_object("fish_model", perch, fish, mcrig.mat4(mcrig.rz(90 * mcrig.DEG), s=0.8) @ mcrig.T(-8, -8, -8))
    splash = bpy.data.objects.new("splash", None)
    sc.collection.objects.link(splash)
    splash.parent = space
    for i in range(8):
        a = i * math.pi / 4
        fk.cubes_object(f"splash:{i}", [((2.6 * math.cos(a) - 0.3, -0.8 - 0.7 * (i % 3), 2.6 * math.sin(a) - 0.3),
                                         (0.6, 0.6, 0.6))], (0.85, 0.93, 1.0, 1), splash)
    # the line: a poly curve with a round bevel, one point set per frame
    cu = bpy.data.curves.new("line", "CURVE")
    cu.dimensions = "3D"
    cu.bevel_depth = 0.12                      # px (curve lives in MC_SPACE, 1/16 scale)
    cu.bevel_resolution = 2
    spl = cu.splines.new("POLY")
    NP = 16
    spl.points.add(NP - 1)
    line = bpy.data.objects.new("line", cu)
    sc.collection.objects.link(line)
    line.parent = space
    mat = bpy.data.materials.new("line_mat")
    mat.use_nodes = True
    mat.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.92, 0.90, 0.82, 1)
    mat.diffuse_color = (0.92, 0.90, 0.82, 1)
    cu.materials.append(mat)

    tl, total = timeline()
    fps = 30
    nframes = int(round(total * fps))
    sc.frame_start, sc.frame_end = 0, nframes
    release_tip = None
    rows = []
    for f in range(nframes + 1):
        t = f / fps
        const, local, clip, ch = sample_timeline(tl, t)
        seated = S.seated_channels(ch)
        w = mcrig.pose_matrices(seated)
        rodm = S.rod_matrix(w)
        if const == "FISHER_CAST_V3" and local >= 0.80 and release_tip is None:
            release_tip = mcrig.xform(rodm, S.ROD_TIP)
        tip, bp, fp, sag, spl_k = props_at(const, local, clip, w, rodm, t, release_tip)
        for name, e in objs.items():
            if name not in mcrig.PARTS:
                continue
            pivot = mcrig.PARTS[name][1]
            c = seated.get(name, {})
            rot, pos = c.get("rot", (0, 0, 0)), c.get("pos", (0, 0, 0))
            e.location = (pivot[0] + pos[0], pivot[1] - pos[1], pivot[2] + pos[2])
            e.rotation_euler = tuple(math.radians(r) for r in rot)
            e.keyframe_insert("location", frame=f)
            e.keyframe_insert("rotation_euler", frame=f)
        bobber.location = tuple(bp)
        bobber.keyframe_insert("location", frame=f)
        if fp is None:
            fish.location = (0, 60, 0)
            fish.scale = (0.001, 0.001, 0.001)
        else:
            fish.location = tuple(fp)
            fish.scale = (1, 1, 1)
            lt = min(local, S.land_fish().length) if const != "HOLD_END" else S.land_fish().length
            yw, rl, _ = S.flap(t)
            if lt < S.LAND_SWING:          # hooked: hangs mouth-up, wriggling hard
                fish.rotation_euler = (math.radians(rl), math.radians(yw), 0.0)
            elif lt < S.LAND_GRAB:
                k = (lt - S.LAND_SWING) / (S.LAND_GRAB - S.LAND_SWING)
                fish.rotation_euler = (math.radians(rl * (1 - k)), math.radians(yw * (1 - k)), 0.0)
            else:                           # in the hand: laid across the palm, a last few kicks
                kick = 12.0 * math.exp(-3.0 * (lt - S.LAND_GRAB)) * math.sin(t * 30.0)
                fish.rotation_euler = (0.0, math.radians(kick), math.radians(90.0))
            fish.keyframe_insert("rotation_euler", frame=f)
        fish.keyframe_insert("location", frame=f)
        fish.keyframe_insert("scale", frame=f)
        sk = max(0.001, spl_k)
        splash.location = tuple(bp) if const != "FISHER_LAND_FISH" else tuple(SHORE)
        splash.scale = (0.6 + sk, sk * 1.6, 0.6 + sk)
        if const == "FISHER_LAND_FISH" and S.LAND_UP <= local < S.LAND_SWING and fp is not None:
            drip = 0.35 + 0.25 * abs(S.flap(t)[2])           # water shaken off the flapping fish
            splash.location = tuple(fp + np.array([0.0, 3.0, 0.0]))
            splash.scale = (drip, drip * 1.5, drip)
        splash.keyframe_insert("location", frame=f)
        splash.keyframe_insert("scale", frame=f)
        for i, p in enumerate(spl.points):
            u = i / (NP - 1)
            end = fp if fp is not None else bp          # landing: the line runs through the float to the fish
            q = tip + (end - tip) * u
            q[1] += sag * 4.0 * u * (1 - u)
            p.co = (q[0], q[1], q[2], 1.0)
            p.keyframe_insert("co", frame=f)
        rows.append({"t": round(t, 3), "clip": const, "local": round(local, 3),
                     "elev": round(S.elevation({"butt": mcrig.xform(rodm, S.ROD_BUTT), "tip": tip}), 1)})
    for ob in bpy.data.objects:
        ad = ob.animation_data
        if ad and ad.action:
            for fc in ad.action.fcurves:
                for kp in fc.keyframe_points:
                    kp.interpolation = "LINEAR"
    # render
    if eevee:
        hsrig.setup_render(res=(1280, 720), samples=12)
    else:
        hsrig.setup_render(res=(1280, 720))
        sc.render.engine = "BLENDER_WORKBENCH"
        sh = sc.display.shading
        sh.light = "STUDIO"
        sh.color_type = "TEXTURE"
        sh.show_shadows = True
        sh.show_cavity = True
        sc.display.render_aa = "8"
        sc.world.color = (0.55, 0.72, 0.95)
    sc.render.fps = fps
    cams = {
        "side": hsrig.camera("cam_side", (-5.3, -1.75, 1.55), (0.0, -1.8, 1.0), lens=30),
        "front34": hsrig.camera("cam_front34", (-4.2, -4.4, 1.9), (-0.3, -1.6, 0.8), lens=30),
        "close": hsrig.camera("cam_close", (-2.5, -3.0, 1.95), (-0.05, -0.8, 1.2), lens=32),
    }
    os.makedirs(VIDEOS, exist_ok=True)
    made = []
    for name, cam in cams.items():
        path = os.path.join(OUT, f"fisher_v3_loop_{name}.mp4")
        sc.camera = cam
        sc.render.image_settings.file_format = "FFMPEG"
        sc.render.ffmpeg.format = "MPEG4"
        sc.render.ffmpeg.codec = "H264"
        sc.render.ffmpeg.constant_rate_factor = "HIGH"
        sc.render.filepath = path
        bpy.ops.render.render(animation=True)
        made.append(path)
        sc.render.image_settings.file_format = "PNG"
        idx = [int(round(i * nframes / 12)) for i in range(12)]
        tmp = os.path.join(OUT, f"_sheet_{name}")
        hsrig.render_frames(cam, tmp, idx)
        sheet = os.path.join(OUT, f"fisher_v3_loop_{name}_sheet.png")
        hsrig._sheet([os.path.join(tmp, f"f{f:04d}.png") for f in idx], sheet)
        made.append(sheet)
    for p in made:
        shutil.copyfile(p, os.path.join(VIDEOS, os.path.basename(p)))
    with open(os.path.join(VIDEOS, "fisher_v3_loop_timeline.json"), "w") as fh:
        json.dump({"segments": [(c, a, b, t0) for c, _, a, b, t0 in tl], "fps": fps, "frames": rows[::5]}, fh, indent=1)
    print("REEL", json.dumps([os.path.join(VIDEOS, os.path.basename(p)) for p in made]))


if "--no-export" not in ARGS:
    export_all()
if "--reel" in ARGS:
    build_reel(eevee="--eevee" in ARGS)
