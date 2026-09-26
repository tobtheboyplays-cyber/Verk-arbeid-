"""FARM_WATER, authored in Blender. Run headless:

    blender -b --factory-startup --python author_farm_water.py -- [--fast|--full]

Contract kept (SettlerAnimations.FARM_WATER / FarmerWorkGoal / anim_check):
  length 2.40 s looping (WATER_DURATION 48 ticks); first water leaves the spout
  onto the soil at t = 0.80 s = tick 16 (WATER_CONTACT_TICK, WATER_POUR, the
  server moisture commit). The watering can is SettlerModel's own prop on the
  LEFT forearm (visible only for WORK_WATER); the MAINHAND hoe stays in the
  right hand, low and clear.

Beats (a full can is heavy):
  0.00-0.40  gather: knees give, the trunk leans right to counter the can.
  0.40-0.66  lift the can forward, elbow bent, weight rocks onto the front foot.
  0.66-0.80  tip: the wrist rolls the spout down, decelerating onto the first pour (contact).
  0.80-1.60  pour: a slow sweep across the row led by the hips, the can tipping
             further as it empties.
  1.60-2.05  un-tip and lower; 2.05-2.40 settle back to the carry.
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

CONST, SLUG = "FARM_WATER", "farm_water"
L, CONTACT = 2.4, 0.80
OUT = os.path.join(fk.WORK, "out", "farm", SLUG)
os.makedirs(OUT, exist_ok=True)
c = hsrig.ctrl

sc, objs = fk.build(fk.tex("farmer"), item="hoe")
fk.box("field", (-8, 24, -24), (16, 0.6, 16), (0.33, 0.22, 0.13, 1))
fk.watering_can(objs)
FEET = (np.array([-3.2, 24.0, 1.2]), np.array([3.3, 24.0, -1.6]))

IN = ("CUBIC", "EASE_IN")
OUTS = ("SINE", "EASE_OUT")
K = {
    "pelvis_y":   [(0.0, -0.3), (0.30, -0.9), (0.50, -0.5), (0.80, -0.8, *OUTS), (1.60, -0.9),
                   (2.00, -0.6), (L, -0.3)],
    "pelvis_z":   [(0.0, 0.0), (0.50, -0.2), (0.80, 0.6), (1.60, 0.8), (2.0, 0.3), (L, 0.0)],
    "pelvis_x":   [(0.0, -0.3), (0.35, -0.7), (0.80, 0.1), (1.20, -0.3), (1.60, -0.6), (2.10, -0.4),
                   (L, -0.3)],
    "pelvis_yaw": [(0.0, 0.0), (0.66, -4.0), (0.80, -5.0), (1.60, 6.0), (2.10, 1.0), (L, 0.0)],
    "spine_x":    [(0.0, 5.0), (0.35, 8.0), (0.66, 20.0), (0.80, 25.0, *OUTS), (1.20, 27.0),
                   (1.60, 27.0), (2.00, 11.0), (L, 5.0)],
    "spine_yaw":  [(0.0, 0.0), (0.66, -6.0), (0.80, -7.0), (1.60, 7.0), (2.10, 1.0), (L, 0.0)],
    "spine_z":    [(0.0, -3.0), (0.35, -4.5), (0.66, -3.0), (0.80, -2.0), (1.60, -2.5), (2.10, -3.5),
                   (L, -3.0)],
    "head_nod":   [(0.0, 0.0), (0.60, 2.0), (1.60, 3.0), (2.10, 0.0), (L, 0.0)],
}
fk.key(K, cyclic=True, length=L)
fk.key_vec("water", [(0.0, (2.5, 24.0, -12.0)), (0.80, (2.8, 24.0, -12.5)), (1.60, (-2.5, 24.0, -12.0)),
                     (L, (2.5, 24.0, -12.0))], cyclic=True, length=L)
fk.key_vec("rh", [(0.0, (-7.6, 11.4, -1.0)), (0.80, (-8.3, 11.0, -1.8)), (1.60, (-8.1, 11.2, -2.2)),
                  (L, (-7.6, 11.4, -1.0))], cyclic=True, length=L)


def spout_terms(hand, tip=None, direction=None, w_tip=0.5, w_dir=120.0, w_hand=1.0):
    d = None if direction is None else np.asarray(direction, float) / np.linalg.norm(direction)

    def f(w):
        k = w_hand * float(np.sum((fk.palm(w, "left") - np.asarray(hand)) ** 2))
        # keep the pour a wrist-and-shoulder roll, not an arm thrown over the head
        up = mcrig.xform(w["left_forearm"], (0, 0, 0))
        k += 2.0 * max(0.0, 7.0 - float(up[1])) ** 2
        tipp, base = fk.spout_tip(w), fk.spout_base(w)
        if d is not None:
            a = (tipp - base) / np.linalg.norm(tipp - base)
            k += w_dir * float(np.sum((a - d) ** 2))
        if tip is not None:
            k += w_tip * float(np.sum((tipp - np.asarray(tip)) ** 2))
        return k
    return f


GOALS = [
    # t, (left palm, spout tip, spout direction)
    (0.00, spout_terms((7.4, 12.0, -2.4), direction=(-0.35, 0.25, -0.90), w_dir=40.0), None),
    (0.36, spout_terms((7.2, 11.2, -4.0), direction=(-0.40, 0.15, -0.90), w_dir=40.0), None),
    (0.62, spout_terms((5.5, 9.0, -8.5), direction=(-0.45, 0.20, -0.87)), None),
    (0.80, spout_terms((6.0, 12.0, -6.5), tip=(2.0, 18.0, -15.0), direction=(-0.30, 0.68, -0.67)), None),
    (1.20, spout_terms((5.0, 12.4, -6.8), tip=(0.3, 18.5, -15.0), direction=(-0.30, 0.74, -0.60)), None),
    (1.60, spout_terms((3.8, 12.6, -6.8), tip=(-1.6, 18.8, -14.5), direction=(-0.28, 0.78, -0.56)), None),
    (1.84, spout_terms((4.5, 9.5, -8.0), direction=(-0.45, 0.10, -0.89)), None),
    (2.10, spout_terms((7.2, 11.6, -3.0), direction=(-0.35, 0.25, -0.90), w_dir=40.0), None),
]
LOG = fk.key_fk_goals("arm_l", "left", GOALS, lambda t: fk.body(t % L), [-20.0, 0.0, -5.0, -25.0, 0.0], L,
                      probe=lambda w: {"spout_tip": fk.spout_tip(w)})
print("GOALS", json.dumps(LOG))


def solve(t):
    t = t % L
    ch = fk.body(t)
    fk.fk_arm(ch, "arm_l", "left", t)
    ch["cloak"] = {"rot": fk.cloak(t, L)}
    prev = getattr(solve, "_prev", {})
    fk.legs(ch, FEET, prev)
    fk.arm_ik(ch, "right", fk.cv("rh", t), (-0.9, 0.3, 0.6), prev)
    fk.look(ch, fk.cv("water", t), w_pitch=0.55, w_yaw=0.6, nod=c("head_nod", t))
    solve._prev = {k: ch[k]["rot"] for k in ("right_arm", "right_leg", "left_leg")}
    return ch


times, samples = hsrig.bake(solve, L, objs)
fk.fix_wraps(samples)

tips = [fk.spout_tip(mcrig.pose_matrices(s)) for s in samples]
bases = [fk.spout_base(mcrig.pose_matrices(s)) for s in samples]
cf = int(round(CONTACT * fk.FPS))
down = [float(((tp - b) / np.linalg.norm(tp - b))[1]) for tp, b in zip(tips, bases)]
first_down = next((times[i] for i, v in enumerate(down) if v > 0.6), None)
checks = {
    "contact_t": CONTACT, "contact_tick": 16,
    "foot_slide_px_max": fk.foot_slide(samples, FEET),
    "loop_seam_max": fk.loop_seam(samples),
    "spout_tip_at_contact_px": [round(float(v), 2) for v in tips[cf]],
    "spout_down_component_at_contact": round(down[cf], 3),
    "first_t_spout_points_down_(dy>0.6)": first_down,
    "spout_down_component_min_during_pour": round(min(down[cf:int(1.6 * fk.FPS)]), 3),
}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/farm/author_farm_water.py (Blender " + bpy.app.version_string + ")",
        "contract": "FARM_WATER 2.40 s loop; spout-down pour contact t=0.80 s = tick 16 of 48 (WATER_POUR); "
                    "can = SettlerModel watering_can on the left forearm",
        "checks": checks}
path, doc, report, worst = fk.export(CONST, L, True, times, samples,
                                     keep_times=(0.0, 0.62, CONTACT, 1.60, L), meta=meta)
with open(os.path.join(OUT, "export_report.json"), "w") as fh:
    json.dump({"checks": checks, "channels": report, "roundtrip_max_err": worst}, fh, indent=1)
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(OUT, SLUG + ".blend"))
fk.finish(SLUG, L, OUT)
