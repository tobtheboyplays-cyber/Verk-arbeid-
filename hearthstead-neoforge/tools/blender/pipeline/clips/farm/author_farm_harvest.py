"""FARM_HARVEST, authored in Blender. Run headless:

    blender -b --factory-startup --python author_farm_harvest.py -- [--fast|--full]

Contract kept (SettlerAnimations.FARM_HARVEST / FarmerWorkGoal / anim_check):
  length 1.80 s looping (HARVEST_DURATION 36 ticks); the free LEFT hand closes on
  the crop at t = 0.45 s = tick 9 (HARVEST_CONTACT_TICK, CROP_PULL, the server
  crop pull) and stows it over the left shoulder into the pack at t = 0.90 s =
  tick 18 (HARVEST_STOW_TICK). The right hand keeps the real MAINHAND hoe.

Beats (weight first):
  0.00-0.30  turn back to the row and hinge down, hips sink, left hand reaches.
  0.30-0.45  the hand slows onto the stalk and closes (contact 0.45).
  0.45-0.52  load: hips sink a touch more, right hand braces on the right knee.
  0.52-0.64  the pull: legs drive first, then the hand snaps up as the root lets go.
  0.64-0.90  rise and open the chest to the left; the hand carries the crop up
             past the ear and tosses it back over the shoulder (stow 0.90).
  0.90-1.02  release/flick, eyes follow; 1.02-1.80 turn back to the next stalk.
"""

import json
import os
import sys

import bpy
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import farmkit as fk  # noqa: E402
import bagclip_rig as br  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402

CONST, SLUG = "FARM_HARVEST", "farm_harvest"
L, GRAB, STOW = 1.8, 0.45, 0.90
OUT = os.path.join(fk.WORK, "out", "farm", SLUG)
os.makedirs(OUT, exist_ok=True)
c = hsrig.ctrl

sc, objs = fk.build(fk.tex("farmer"), item="hoe")
fk.box("field", (-8, 24, -24), (16, 0.6, 16), (0.33, 0.22, 0.13, 1))
fk.box("wheat_next", (-4.5, 13, -14), (3, 11, 3), (0.80, 0.70, 0.30, 1))
crop = br.box_mesh_object("crop", [((-1.5, -5.0, -1.5), (3.0, 10.0, 3.0))], (0.85, 0.74, 0.32, 1))
FEET = (np.array([-3.2, 24.0, 1.6]), np.array([3.4, 24.0, -1.4]))
CROP = np.array([3.2, 18.2, -9.6])            # palm on the stalk
SHOULDER_DROP = np.array([7.2, -2.6, 1.8])     # over the left shoulder, into the pack

IN = ("CUBIC", "EASE_IN")
OUTS = ("SINE", "EASE_OUT")
OUTQ = ("QUAD", "EASE_OUT")
K = {
    "pelvis_y":   [(0.0, -1.6), (0.30, -4.2), (0.45, -4.6, *OUTS), (0.52, -5.0, *IN), (0.64, -1.4, *OUTS),
                   (0.90, -0.3), (1.05, -0.4), (1.35, -0.8), (L, -1.6)],
    "pelvis_z":   [(0.0, 0.6), (0.30, 1.3), (0.52, 1.5), (0.64, 0.6), (0.90, 0.0), (1.35, 0.1), (L, 0.6)],
    "pelvis_x":   [(0.0, 0.5), (0.45, 0.8), (0.64, 0.3), (0.90, -0.4), (1.20, -0.2), (L, 0.5)],
    "pelvis_yaw": [(0.0, -3.0), (0.45, -5.0), (0.64, -2.0), (0.90, 4.0), (1.10, 4.0), (L, -3.0)],
    "spine_x":    [(0.0, 20.0), (0.30, 42.0), (0.45, 46.0, *OUTS), (0.52, 47.0, *IN), (0.66, 26.0, *OUTS),
                   (0.90, 3.0), (1.02, 1.0), (1.30, 6.0), (L, 20.0)],
    # the chest opens to the left for the toss (negative), back to the row after
    "spine_yaw":  [(0.0, -6.0), (0.45, -9.0), (0.64, -4.0), (0.90, 12.0), (1.05, 13.0), (1.40, 2.0),
                   (L, -6.0)],
    "spine_z":    [(0.0, 4.0), (0.45, 7.0), (0.64, 3.0), (0.90, -6.0), (1.05, -6.0), (1.40, 0.0), (L, 4.0)],
    "head_nod":   [(0.0, 0.0), (0.45, 4.0), (0.64, -2.0), (0.90, -10.0), (1.05, -8.0), (1.40, 0.0), (L, 0.0)],
    "look_w":     [(0.0, 0.0), (0.58, 0.0), (0.80, 0.55), (1.00, 0.55), (1.25, 0.0), (L, 0.0)],
    "rh_knee":    [(0.0, 0.0), (0.26, 0.0), (0.42, 1.0), (0.60, 1.0), (0.74, 0.0), (L, 0.0)],
}
fk.key(K, cyclic=True, length=L)
fk.key_vec("lh", [(0.0, (6.5, 9.5, -7.0)), (0.30, (3.4, 16.8, -10.3), *OUTS), (GRAB, tuple(CROP)),
                  (0.52, tuple(CROP + (0.1, 0.3, 0.1)), *IN), (0.64, (3.8, 10.5, -8.5), *OUTS),
                  (0.78, (5.6, 2.5, -5.5)), (STOW, tuple(SHOULDER_DROP), *OUTS),
                  (1.00, tuple(SHOULDER_DROP + (0.2, 0.6, 1.0))), (1.20, (7.2, 6.0, -3.5)),
                  (1.50, (6.8, 9.5, -6.0)), (L, (6.5, 9.5, -7.0))], cyclic=True, length=L)
fk.key_vec("rh_free", [(0.0, (-7.4, 11.0, -2.2)), (0.9, (-7.6, 11.6, -0.5)), (L, (-7.4, 11.0, -2.2))],
           cyclic=True, length=L)


def solve(t):
    t = t % L
    ch = fk.body(t)
    ch["cloak"] = {"rot": fk.cloak(t, L)}
    prev = getattr(solve, "_prev", {})
    fk.legs(ch, FEET, prev, knee_out=0.25)
    w = mcrig.pose_matrices(ch)
    # QA 2026-09-26: with the elbow pole BACK (z +0.6) the over-the-shoulder toss
    # folded the arm through its pole at 1.13 s and the elbow flipped 7.8 px up/back
    # in one frame. Swing the pole forward for the toss window (elbow leads forward).
    u = max(0.0, min(1.0, (t - 0.70) / 0.15))
    v = max(0.0, min(1.0, (t - 1.30) / 0.20))
    fw = (u * u * (3 - 2 * u)) * (1 - v * v * (3 - 2 * v))
    fk.arm_ik(ch, "left", fk.cv("lh", t), (0.8, 0.4, 0.6 - 1.2 * fw), prev)
    knee = mcrig.xform(w["right_leg"], (-0.4, 4.6, -2.6))
    wk = max(0.0, min(1.0, c("rh_knee", t)))
    wk = wk * wk * (3 - 2 * wk)
    fk.arm_ik(ch, "right", (1 - wk) * fk.cv("rh_free", t) + wk * knee, (-0.9, 0.3, 0.5), prev)
    lw = max(0.0, min(1.0, c("look_w", t)))
    target = (1 - lw) * (CROP + np.array([0.0, 2.0, 0.0])) + lw * fk.palm(mcrig.pose_matrices(ch), "left")
    fk.look(ch, target, w_pitch=0.55, w_yaw=0.6, nod=c("head_nod", t))
    solve._prev = {k: ch[k]["rot"] for k in ("left_arm", "right_arm", "right_leg", "left_leg")}
    return ch


times, samples = hsrig.bake(solve, L, objs)
fk.fix_wraps(samples)

palms = [fk.palm(mcrig.pose_matrices(s), "left") for s in samples]
for f, t in enumerate(times):          # preview crop: stands, rides the hand, gone after the toss
    if t < GRAB:
        m, vis = mcrig.T(*(CROP + (0, 0.5, 0))), True
    elif t <= STOW + 0.04:
        m, vis = mcrig.T(*(palms[f] + (0, -1.0, 0))), True
    else:
        m, vis = mcrig.T(*(CROP + (0, 0.5, 0))), t > 1.5
    br.key_prop(crop, m, f, vis)
gf, sf = int(round(GRAB * fk.FPS)), int(round(STOW * fk.FPS))
checks = {
    "grab_t": GRAB, "grab_tick": 9, "stow_t": STOW, "stow_tick": 18,
    "foot_slide_px_max": fk.foot_slide(samples, FEET),
    "loop_seam_max": fk.loop_seam(samples),
    "left_palm_err_at_grab_px": round(float(np.linalg.norm(palms[gf] - CROP)), 3),
    "left_palm_err_at_stow_px": round(float(np.linalg.norm(palms[sf] - SHOULDER_DROP)), 3),
    "left_palm_speed_px_s_at_grab": round(float(np.linalg.norm(palms[gf] - palms[gf - 1]) * fk.FPS), 1),
    "knee_flex_max_deg": round(max(max(s["right_shin"]["rot"][0], s["left_shin"]["rot"][0]) for s in samples), 1),
}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/farm/author_farm_harvest.py (Blender " + bpy.app.version_string + ")",
        "contract": "FARM_HARVEST 1.80 s loop; crop grab t=0.45 s = tick 9 (CROP_PULL), stow t=0.90 s = tick 18",
        "checks": checks}
path, doc, report, worst = fk.export(CONST, L, True, times, samples,
                                     keep_times=(0.0, GRAB, 0.52, 0.64, STOW, L), meta=meta)
with open(os.path.join(OUT, "export_report.json"), "w") as fh:
    json.dump({"checks": checks, "channels": report, "roundtrip_max_err": worst}, fh, indent=1)
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(OUT, SLUG + ".blend"))
fk.finish(SLUG, L, OUT)
