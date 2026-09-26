"""Timing/arc reference from a CMU BVH clip (run inside headless Blender).

    blender -b --factory-startup --python analyze_mocap.py -- <clip.bvh> <out.json>

Imports the BVH with Blender's bundled importer, then measures, per frame:
  * pelvis yaw (hip line) and shoulder yaw (shoulder line) -> who leads;
  * right/left wrist height and speed -> wind-up peak, strike, recoil;
  * foot drift -> are the feet planted.
The numbers are printed and written as JSON; author_lumberer_chop.py uses the
phase ratios (not the raw pose) because the CMU skeleton is human-proportioned
and the settler is a 12px-limb block rig.
"""

import json
import math
import sys

import bpy

argv = sys.argv[sys.argv.index("--") + 1:]
src, out = argv[0], argv[1]

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_anim.bvh(filepath=src, global_scale=1.0, frame_start=1,
                        update_scene_fps=True, update_scene_duration=True,
                        axis_forward="-Z", axis_up="Y")
arm = next(o for o in bpy.data.objects if o.type == "ARMATURE")
scene = bpy.context.scene
f0, f1 = scene.frame_start, scene.frame_end
fps = scene.render.fps / scene.render.fps_base


def head(name):
    pb = arm.pose.bones[name]
    return arm.matrix_world @ pb.head


def yaw(a, b):
    d = b - a
    return math.degrees(math.atan2(d.y, d.x))


rows = []
for f in range(f0 + 2, f1 + 1):  # frame 1 of the cgspeed release is a T-pose
    scene.frame_set(f)
    rows.append({
        "f": f,
        "hip_yaw": yaw(head("RightUpLeg"), head("LeftUpLeg")),
        "sh_yaw": yaw(head("RightArm"), head("LeftArm")),
        "rh": list(head("RightHand")),
        "lh": list(head("LeftHand")),
        "hips": list(head("Hips")),
        "lf": list(head("LeftFoot")),
        "rf": list(head("RightFoot")),
    })

# unwrap yaw series
for key in ("hip_yaw", "sh_yaw"):
    prev = None
    off = 0.0
    for r in rows:
        v = r[key] + off
        if prev is not None:
            while v - prev > 180:
                off -= 360; v -= 360
            while v - prev < -180:
                off += 360; v += 360
        r[key] = v
        prev = v

# wrist speed (units/s) of the midpoint of both hands (two-handed grip)
for i, r in enumerate(rows):
    j = min(len(rows) - 1, i + 1)
    k = max(0, i - 1)
    a = [(p + q) / 2 for p, q in zip(rows[k]["rh"], rows[k]["lh"])]
    b = [(p + q) / 2 for p, q in zip(rows[j]["rh"], rows[j]["lh"])]
    dt = (j - k) / fps
    r["hand_speed"] = math.dist(a, b) / dt if dt else 0.0
    r["hand_z"] = (r["rh"][2] + r["lh"][2]) / 2

# strikes: local maxima of hand speed followed by a sharp drop
speeds = [r["hand_speed"] for r in rows]
peak = max(speeds)
strikes = []
i = 1
while i < len(rows) - 1:
    if speeds[i] > 0.55 * peak and speeds[i] >= speeds[i - 1] and speeds[i] >= speeds[i + 1]:
        # impact = first frame after the peak where speed fell below 25% of it
        j = i
        while j < len(rows) - 1 and speeds[j] > 0.25 * speeds[i]:
            j += 1
        # wind-up top = highest hand point in the 1.2 s before the peak
        lo = max(0, i - int(1.2 * fps))
        top = max(range(lo, i), key=lambda n: rows[n]["hand_z"]) if i > lo else i
        # swing start = last frame before the peak with speed < 15% of peak
        s = i
        while s > lo and speeds[s] > 0.15 * speeds[i]:
            s -= 1
        # recoil/settle = first frame after impact where speed < 8% of the peak
        e = j
        while e < len(rows) - 1 and speeds[e] > 0.08 * speeds[i]:
            e += 1
        strikes.append({"swing_start": s, "top": top, "peak_speed": i,
                        "impact": j, "settled": e})
        i = e + 1
    else:
        i += 1

# hips-vs-shoulders lead for each downswing: frames where the yaw velocity peaks
lead = []
for s in strikes:
    rng = range(s["top"], s["impact"] + 1)
    if len(rng) < 3:
        continue
    hv = max(rng[1:-1], key=lambda n: abs(rows[n + 1]["hip_yaw"] - rows[n - 1]["hip_yaw"]))
    sv = max(rng[1:-1], key=lambda n: abs(rows[n + 1]["sh_yaw"] - rows[n - 1]["sh_yaw"]))
    hp = max(rng[1:-1], key=lambda n: abs(rows[n + 1]["hand_z"] - rows[n - 1]["hand_z"]))
    lead.append({"hip_vel_peak": hv, "shoulder_vel_peak": sv, "hand_vel_peak": hp,
                 "hip_lead_s": (sv - hv) / fps, "shoulder_lead_s": (hp - sv) / fps,
                 "hip_yaw_range": max(rows[n]["hip_yaw"] for n in rng) - min(rows[n]["hip_yaw"] for n in rng),
                 "sh_yaw_range": max(rows[n]["sh_yaw"] for n in rng) - min(rows[n]["sh_yaw"] for n in rng)})

foot_drift = max(math.dist(rows[0]["lf"], r["lf"]) for r in rows)
hip_bob = max(r["hips"][2] for r in rows) - min(r["hips"][2] for r in rows)

summary = {"source": src, "fps": fps, "frames": len(rows), "strikes": strikes,
           "lead": lead, "left_foot_max_drift": foot_drift, "hip_vertical_range": hip_bob}
# phase ratios per strike cycle (top -> impact is the swing, swing_start -> top the wind-up)
ratios = []
for s in strikes:
    wind = (s["top"] - s["swing_start"]) / fps
    down = (s["impact"] - s["top"]) / fps
    rec = (s["settled"] - s["impact"]) / fps
    ratios.append({"windup_s": wind, "downswing_s": down, "recoil_s": rec})
summary["phases"] = ratios

print(json.dumps({k: v for k, v in summary.items()}, indent=1))
with open(out, "w") as fh:
    json.dump({"summary": summary, "rows": rows}, fh)
