"""SOW_BROADCAST, authored in Blender. Run headless:

    blender -b --factory-startup --python author_sow_broadcast.py -- [--fast|--full]

Contract kept (SettlerAnimations.SOW_BROADCAST / HerderWorkGoal / FarmerWorkGoal):
  length 1.40 s looping (FEED_DURATION / REPLANT_DURATION 28 ticks); the hand
  parks open at the end of the arc 0.60-0.70 s and the seed/feed leaves it at
  t = 0.70 s = tick 14 (FEED_ACCENT_TICK; the farmer's replant press shares
  tick 14). The farmer's WORK_SOW now samples FARM_PLANT instead (SettlerModel),
  so in practice this clip is the HERDER's feeding throw; the herder's MAINHAND
  may hold shears, so the FREE LEFT hand does the throwing and the right hand
  cradles the feed pouch at the right hip.

Beats (the arc comes from the hips):
  0.00-0.30  turn right into the pouch, the left hand crosses and dips for a handful.
  0.30-0.45  wind: weight on the right foot, chest turned right, hand gathered low.
  0.45-0.60  sweep: hips unwind first, chest follows, the arm swings out wide and accelerates.
  0.60-0.70  release: the arm parks extended, the hand opens (accent 0.70), weight on the left foot.
  0.70-0.90  follow-through and drop; 0.90-1.40 drift back toward the pouch.
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

CONST, SLUG = "SOW_BROADCAST", "sow_broadcast"
L, RELEASE = 1.4, 0.70
OUT = os.path.join(fk.WORK, "out", "farm", SLUG)
os.makedirs(OUT, exist_ok=True)
c = hsrig.ctrl

sc, objs = fk.build(fk.tex("herder"), item="shears")
FEET = (np.array([-3.4, 24.0, 0.9]), np.array([3.4, 24.0, -1.1]))
POUCH = np.array([-4.2, 12.4, -4.6])
IN = ("CUBIC", "EASE_IN")
OUTS = ("SINE", "EASE_OUT")
OUTQ = ("QUAD", "EASE_OUT")
K = {
    "pelvis_y":   [(0.0, -0.3), (0.30, -0.8), (0.45, -0.9, *IN), (0.62, -0.5, *OUTS), (0.72, -0.6),
                   (1.00, -0.4), (L, -0.3)],
    "pelvis_x":   [(0.0, -0.1), (0.30, -0.6), (0.45, -0.65, *IN), (0.62, 0.6, *OUTS), (0.80, 0.55),
                   (1.10, 0.1), (L, -0.1)],
    "pelvis_z":   [(0.0, 0.0), (0.45, 0.2), (0.70, -0.2), (L, 0.0)],
    # hips lead the unwind by ~0.04 s
    "pelvis_yaw": [(0.0, 3.0), (0.28, 9.0), (0.41, 10.0, *IN), (0.56, -10.0, *OUTS), (0.72, -11.0),
                   (1.00, -3.0), (L, 3.0)],
    "spine_yaw":  [(0.0, 5.0), (0.30, 14.0), (0.45, 15.0, *IN), (0.60, -16.0, *OUTS), (0.74, -17.0),
                   (1.02, -4.0), (L, 5.0)],
    "spine_x":    [(0.0, 5.0), (0.30, 12.0), (0.45, 13.0, *IN), (0.60, 4.0, *OUTS), (0.72, 5.0),
                   (0.95, 7.0), (L, 5.0)],
    "spine_z":    [(0.0, 0.0), (0.30, -3.0), (0.45, -3.0, *IN), (0.60, 3.0, *OUTS), (1.0, 1.0), (L, 0.0)],
    "head_nod":   [(0.0, 2.0), (0.30, 8.0), (0.50, 0.0), (0.70, -3.0), (1.00, 1.0), (L, 2.0)],
}
fk.key(K, cyclic=True, length=L)
# free left hand path (model space); release parks it wide and forward-left
fk.key_vec("lh", [(0.0, (1.5, 10.5, -6.0)), (0.22, tuple(POUCH + (1.2, -0.6, -0.6))),
                  (0.32, tuple(POUCH), *OUTS), (0.45, tuple(POUCH + (0.8, 0.8, 0.2)), *IN),
                  (0.53, (3.5, 7.5, -9.5), *IN), (0.60, (10.0, 3.5, -8.0), *OUTS),
                  (RELEASE, (10.8, 3.2, -7.2)), (0.84, (10.5, 6.5, -5.0)),
                  (1.10, (6.5, 10.5, -4.8)), (L, (1.5, 10.5, -6.0))], cyclic=True, length=L)
fk.key_vec("look", [(0.0, (0.0, 20.0, -16.0)), (0.30, tuple(POUCH + (0, 2, 0))), (0.45, tuple(POUCH + (0, 2, 0))),
                    (0.62, (14.0, 18.0, -24.0)), (0.85, (12.0, 20.0, -24.0)), (L, (0.0, 20.0, -16.0))],
           cyclic=True, length=L)


def solve(t):
    t = t % L
    ch = fk.body(t)
    ch["cloak"] = {"rot": fk.cloak(t, L)}
    prev = getattr(solve, "_prev", {})
    fk.legs(ch, FEET, prev)
    fk.arm_ik(ch, "left", fk.cv("lh", t), (0.8, 0.6, 0.5), prev)
    # right hand cradles the pouch at the right hip (rides the body)
    w = mcrig.pose_matrices(ch)
    pouch_hand = mcrig.xform(w["torso"], (-4.8, -1.2, -3.2))
    fk.arm_ik(ch, "right", pouch_hand, (-0.9, 0.2, 0.6), prev)
    fk.look(ch, fk.cv("look", t), w_pitch=0.5, w_yaw=0.65, nod=c("head_nod", t))
    solve._prev = {k: ch[k]["rot"] for k in ("left_arm", "right_arm", "right_leg", "left_leg")}
    return ch


times, samples = hsrig.bake(solve, L, objs)
fk.fix_wraps(samples)
palms = [fk.palm(mcrig.pose_matrices(s), "left") for s in samples]
pk_t, pk_v, sp = fk.peak_speed_t(times, palms)
rf = int(round(RELEASE * fk.FPS))
checks = {
    "release_t": RELEASE, "release_tick": 14,
    "foot_slide_px_max": fk.foot_slide(samples, FEET),
    "loop_seam_max": fk.loop_seam(samples),
    "hand_peak_speed_t": pk_t, "hand_peak_speed_px_s": pk_v,
    "hand_speed_px_s_during_park_0.62_0.70": round(float(np.mean(sp[int(0.62 * 60):rf])), 1),
    "hand_at_release_px": [round(float(v), 2) for v in palms[rf]],
}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/farm/author_sow_broadcast.py (Blender " + bpy.app.version_string + ")",
        "contract": "SOW_BROADCAST 1.40 s loop; hand parks 0.60-0.70 s, release t=0.70 s = tick 14 of 28 "
                    "(HerderWorkGoal FEED_ACCENT_TICK)",
        "checks": checks}
path, doc, report, worst = fk.export(CONST, L, True, times, samples,
                                     keep_times=(0.0, 0.45, 0.60, RELEASE, L), meta=meta)
with open(os.path.join(OUT, "export_report.json"), "w") as fh:
    json.dump({"checks": checks, "channels": report, "roundtrip_max_err": worst}, fh, indent=1)
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(OUT, SLUG + ".blend"))
fk.finish(SLUG, L, OUT)
