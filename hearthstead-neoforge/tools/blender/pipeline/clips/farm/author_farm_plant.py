"""FARM_PLANT, authored in Blender. Run headless:

    blender -b --factory-startup --python author_farm_plant.py -- [--fast|--full]

Contract kept (SettlerAnimations.FARM_PLANT / FarmerWorkGoal / anim_check):
  length 2.00 s looping (FIRST_PLANT_DURATION 40 ticks); the free LEFT hand
  presses the seed into the soil at t = 0.70 s = tick 14 of 40 (SEED_PRESS).
  SettlerModel also samples this clip for the farmer's REPLANT (WORK_SOW): the
  first 0.70 s play unchanged and 0.70-2.00 s is compressed into 0.70 s, so the
  whole descent + press lives before 0.70 and the recovery reads at 1.9x too.
  The real MAINHAND hoe stays in the right hand (vanilla handheld transform).

Beats (weight first):
  0.00-0.20  upright; the left hand dips to the belt pouch for a seed, head glances at it.
  0.20-0.58  hips sink down and back into a deep squat over planted heels, the
             back hinges, the right hand finds the right knee to take the weight,
             the left hand carries the seed out over the spot.
  0.58-0.70  press: the palm decelerates down into the soil, shoulders follow (contact 0.70).
  0.70-0.86  hold, a small second tamp with the fingertips.
  0.86-1.55  rise: legs drive first (right hand pushes off the knee), the back
             unrolls after the hips, the left hand brushes back to the side.
  1.55-2.00  settle, eyes lift to the next spot.
"""

import json
import os
import sys

import bpy
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import farmkit as fk  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402

V = fk.variant_flag()                   # None = base, 2 = __v2 (checks the soil in his fingers)
CONST, SLUG = ("FARM_PLANT", "farm_plant") if V is None else (f"FARM_PLANT__v{V}", f"farm_plant__v{V}")
L, CONTACT = 2.0, 0.70
OUT = os.path.join(fk.WORK, "out", "farm", SLUG)
os.makedirs(OUT, exist_ok=True)
c = hsrig.ctrl

sc, objs = fk.build(fk.tex("farmer"), item="hoe")
fk.box("tilled", (-8, 24, -24), (16, 0.6, 16), (0.33, 0.22, 0.13, 1))
FEET = (np.array([-3.5, 24.0, 0.8]), np.array([3.5, 24.0, -0.6]))
SEED = np.array([3.6, 23.7, -8.6])           # palm (limb end) on the soil
POUCH = np.array([5.3, 11.8, -3.4])          # belt pouch at the left hip

IN = ("CUBIC", "EASE_IN")
OUTS = ("SINE", "EASE_OUT")
OUTQ = ("QUAD", "EASE_OUT")
K = {
    "pelvis_y":   [(0.0, 0.0), (0.18, 0.15), (0.30, -1.2), (0.50, -6.0), (0.62, -7.0, *OUTS),
                   (0.70, -7.3), (0.86, -7.2), (1.02, -6.0), (1.30, -1.8), (1.55, -0.2), (1.75, 0.1),
                   (L, 0.0)],
    "pelvis_z":   [(0.0, 0.0), (0.20, -0.1), (0.50, 1.3), (0.70, 1.6), (0.86, 1.6), (1.10, 1.2),
                   (1.45, 0.2), (L, 0.0)],
    "pelvis_x":   [(0.0, 0.0), (0.16, 0.35), (0.40, 0.2), (0.70, 0.45), (1.10, 0.2), (1.60, -0.1),
                   (L, 0.0)],
    "pelvis_yaw": [(0.0, 0.0), (0.18, -2.0), (0.70, -3.0), (1.30, -1.0), (L, 0.0)],
    # back hinges after the hips start down; on the way up the hips lead and the
    # back unrolls late (legs first, then spine).
    "spine_x":    [(0.0, 3.0), (0.16, 7.0), (0.30, 12.0), (0.55, 50.0), (0.66, 55.0, *OUTS),
                   (0.70, 56.0), (0.86, 55.0), (1.08, 49.0), (1.36, 22.0), (1.60, 6.0), (1.78, 1.5),
                   (L, 3.0)],
    "spine_yaw":  [(0.0, 0.0), (0.16, -5.0), (0.40, -4.0), (0.70, -6.0), (1.20, -3.0), (L, 0.0)],
    "spine_z":    [(0.0, 0.0), (0.16, 2.0), (0.50, 10.0), (0.70, 13.0), (0.90, 12.0), (1.35, 4.0),
                   (L, 0.0)],
    "head_nod":   [(0.0, -12.0), (0.12, 0.0), (0.30, -2.0), (0.70, 4.0), (1.30, -4.0), (1.62, -16.0),
                   (1.80, -14.0), (L, -12.0)],
    # left palm path: side -> pouch -> over the spot -> PRESS -> tamp -> brush back
    "lh_w_pouch": [(0.0, 0.0), (0.08, 0.0), (0.17, 1.0), (0.24, 1.0), (0.40, 0.0), (L, 0.0)],
    # right hand: free at the side (0) / on the right knee (1)
    "rh_knee":    [(0.0, 0.0), (0.28, 0.0), (0.46, 1.0), (1.22, 1.0), (1.44, 0.0), (L, 0.0)],
}
fk.key(K, cyclic=True, length=L)
LH = [(0.0, (7.4, 11.5, -1.6)), (0.30, (5.5, 12.5, -5.0)), (0.46, (4.2, 17.5, -8.6)),
      (0.58, (3.4, 21.2, -9.3), *OUTQ), (CONTACT, tuple(SEED)),
      (0.76, tuple(SEED + (0.1, -1.2, 0.2))), (0.82, tuple(SEED + (0.2, -0.05, 0.1))),
      (0.90, tuple(SEED + (0.3, -1.4, 0.8))), (1.10, (4.6, 19.0, -5.8)),
      (1.40, (7.0, 14.0, -2.8)), (1.70, (7.4, 11.4, -1.4)), (L, (7.4, 11.5, -1.6))]
if V == 2:
    # __v2 (same 2.00 s, press still at 0.70 s): after the tamp he stays down,
    # pinches up a little soil, rubs it between his fingers in front of his face
    # (1.02-1.38), lets it fall with a small nod, then rises -- legs first.
    W0, W1 = 0.86, 1.99
    K["pelvis_y"] = fk.splice(K["pelvis_y"], W0, W1, [(1.00, -6.9), (1.40, -6.6), (1.52, -6.2),
                                                      (1.74, -1.4), (1.88, -0.1)])
    K["pelvis_z"] = fk.splice(K["pelvis_z"], W0, W1, [(1.00, 1.3), (1.45, 1.1), (1.80, 0.2)])
    K["spine_x"] = fk.splice(K["spine_x"], W0, W1, [(1.02, 34.0), (1.40, 31.0), (1.54, 36.0),
                                                    (1.78, 10.0), (1.90, 3.5)])
    K["spine_z"] = fk.splice(K["spine_z"], W0, W1, [(1.02, 6.0), (1.50, 5.0), (1.80, 1.0)])
    K["head_nod"] = fk.splice(K["head_nod"], W0, W1, [(1.05, 2.0), (1.30, 4.0), (1.44, 9.0),
                                                      (1.52, 3.0), (1.84, -12.0)])
    K["rh_knee"] = fk.splice(K["rh_knee"], 0.47, W1, [(1.60, 1.0), (1.80, 0.0)])
    K["look_hand"] = [(0.0, 0.0), (0.92, 0.0), (1.04, 1.0), (1.40, 1.0), (1.56, 0.0), (L, 0.0)]
    LH = fk.splice(LH, 0.82, W1, [(0.90, tuple(SEED + (0.2, -0.6, 0.3))),        # fingertips close on soil
                                   (1.04, (2.6, 12.0, -9.0), *OUTQ),              # up in front of the face
                                   (1.14, (3.3, 12.2, -9.0)), (1.22, (2.4, 12.0, -9.2)),
                                   (1.30, (3.2, 12.3, -9.0)), (1.38, (2.6, 12.1, -9.1)),  # rub
                                   (1.48, (4.0, 13.8, -8.2)),                      # let it fall
                                   (1.70, (6.8, 12.5, -3.5)), (1.88, (7.4, 11.5, -1.8))])
fk.key_vec("lh", LH, cyclic=True, length=L)
fk.key_vec("rh_free", [(0.0, (-7.3, 11.6, -1.2)), (0.6, (-7.0, 12.0, -2.2)), (1.6, (-7.4, 11.4, -0.8)),
                       (L, (-7.3, 11.6, -1.2))], cyclic=True, length=L)


def solve(t):
    t = t % L
    ch = fk.body(t)
    ch["cloak"] = {"rot": fk.cloak(t, L)}
    prev = getattr(solve, "_prev", {})
    fk.legs(ch, FEET, prev, knee_out=0.35)
    w = mcrig.pose_matrices(ch)
    # left: path, pulled to the pouch for the seed grab
    wp = max(0.0, min(1.0, c("lh_w_pouch", t)))
    lh = (1 - wp) * fk.cv("lh", t) + wp * POUCH
    fk.arm_ik(ch, "left", lh, (0.8, 0.3, 0.6), prev)
    # right: the hand settles on top of the right knee while squatting
    knee = mcrig.xform(w["right_leg"], (-0.4, 5.0, -2.6))
    wk = max(0.0, min(1.0, c("rh_knee", t)))
    wk = wk * wk * (3 - 2 * wk)
    rh = (1 - wk) * fk.cv("rh_free", t) + wk * knee
    fk.arm_ik(ch, "right", rh, (-0.9, 0.3, 0.5), prev)
    look_at = (1 - wp) * (SEED + np.array([0.0, 0.0, -1.0])) + wp * POUCH
    lw = max(0.0, min(1.0, c("look_hand", t)))
    if lw > 0.0:
        look_at = (1 - lw) * look_at + lw * fk.palm(mcrig.pose_matrices(ch), "left")
    fk.look(ch, look_at, w_pitch=0.55, w_yaw=0.55, nod=c("head_nod", t))
    solve._prev = {k: ch[k]["rot"] for k in ("left_arm", "right_arm", "right_leg", "left_leg")}
    return ch


times, samples = hsrig.bake(solve, L, objs)
fk.fix_wraps(samples)

palms = [fk.palm(mcrig.pose_matrices(s), "left") for s in samples]
cf = int(round(CONTACT * fk.FPS))
vy = [float(palms[i + 1][1] - palms[i][1]) * fk.FPS for i in range(len(palms) - 1)]
checks = {
    "contact_t": CONTACT, "contact_tick": 14,
    "foot_slide_px_max": fk.foot_slide(samples, FEET),
    "loop_seam_max": fk.loop_seam(samples),
    "left_palm_at_contact_px": [round(float(v), 2) for v in palms[cf]],
    "left_palm_err_to_seed_px": round(float(np.linalg.norm(palms[cf] - SEED)), 3),
    "left_palm_down_speed_px_s_2f_before_contact": round(vy[cf - 2], 1),
    "left_palm_down_speed_px_s_at_contact": round(vy[cf - 1], 1),
    "lowest_pelvis_y": round(min(s["root"]["pos"][1] for s in samples), 2),
    "knee_flex_max_deg": round(max(max(s["right_shin"]["rot"][0], s["left_shin"]["rot"][0]) for s in samples), 1),
}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/farm/author_farm_plant.py (Blender " + bpy.app.version_string + ")",
        "contract": "FARM_PLANT 2.00 s loop; left palm presses seed t=0.70 s = tick 14 of 40 (SEED_PRESS); "
                    "replant samples 0-0.70 s unchanged then 0.70-2.00 s at 13/7 speed"
                    + ("" if V is None else "; variant: same single cycle, soil rubbed between the fingers 1.02-1.40 s"),
        "checks": checks}
path, doc, report, worst = fk.export(CONST, L, True, times, samples,
                                     keep_times=(0.0, 0.58, CONTACT, 0.86, L), meta=meta)
with open(os.path.join(OUT, "export_report.json"), "w") as fh:
    json.dump({"checks": checks, "channels": report, "roundtrip_max_err": worst}, fh, indent=1)
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(OUT, SLUG + ".blend"))
fk.finish(SLUG, L, OUT)
