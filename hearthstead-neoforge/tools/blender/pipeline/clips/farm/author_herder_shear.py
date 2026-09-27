"""HERDER_SHEAR, authored in Blender. Run headless:

    blender -b --factory-startup --python author_herder_shear.py -- [--fast|--full]

Contract kept (SettlerAnimations.HERDER_SHEAR / HerderWorkGoal / Employment):
  length 1.00 s looping (SHEAR_DURATION 20 ticks, one loop = one shear pass);
  the snip lands at t = 0.45 s = tick 9 (SHEAR_ACCENT_TICK / WORK_SHEAR accent).
  Real MAINHAND shears on the right hand (vanilla item/generated transform).

Beats (a light tool: timing is the weight):
  0.00-0.12  blades resting in the fleece; the steadying left hand holds the flank still.
  0.12-0.34  draw back and open: the hand backs out and up a little, the back rises.
  0.34-0.45  snip: the back leans in first, the hand drives down along the flank and
             accelerates into the closing blades (contact 0.45).
  0.45-0.54  hold closed, tiny recoil through the wrist.
  0.54-0.82  peel: the hand drags the cut fleece down and out, then eases back.
  0.82-1.00  settle to the next bite. The left hand only breathes (re-grips at 0.7).
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

V = fk.variant_flag()                   # None = base, 2 = __v2 (pats the sheep)
CONST, SLUG = ("HERDER_SHEAR", "herder_shear") if V is None else (f"HERDER_SHEAR__v{V}", f"herder_shear__v{V}")
NCYC = 1 if V is None else 2
L, SNIP = 1.0, 0.45
OUT = os.path.join(fk.WORK, "out", "farm", SLUG)
os.makedirs(OUT, exist_ok=True)
c = hsrig.ctrl

sc, objs = fk.build(fk.tex("herder"), item="shears")
fk.box("sheep_body", (-10, 8, -19), (20, 10, 10), (0.90, 0.88, 0.82, 1))       # flank face at z = -9
fk.box("sheep_head", (-15, 5, -17), (6, 6, 6), (0.85, 0.72, 0.62, 1))
for x in (-8, 5):
    fk.box(f"sheep_leg{x}", (x, 18, -16), (3, 6, 3), (0.85, 0.72, 0.62, 1))
FEET = (np.array([-3.4, 24.0, 1.4]), np.array([3.2, 24.0, -1.2]))
FLANK_Z = -9.0
HOLD = np.array([4.2, 10.0, FLANK_Z + 0.2])      # left palm on the fleece
CUT = np.array([-1.6, 13.6, FLANK_Z - 0.3])      # blade tip at the snip

IN = ("CUBIC", "EASE_IN")
OUTS = ("SINE", "EASE_OUT")
INQ = ("QUAD", "EASE_IN")
K = {
    "pelvis_y":   [(0.0, -1.0), (0.30, -0.6), (0.36, -0.65, *IN), (0.45, -1.4, *OUTS), (0.54, -1.45),
                   (0.80, -1.1), (L, -1.0)],
    "pelvis_z":   [(0.0, 0.8), (0.30, 0.6), (0.45, 1.0), (L, 0.8)],
    "pelvis_x":   [(0.0, 0.1), (0.30, 0.4), (0.45, -0.2), (0.80, 0.0), (L, 0.1)],
    "pelvis_yaw": [(0.0, 2.0), (0.30, 4.0), (0.43, -1.0), (0.80, 1.0), (L, 2.0)],
    # the back leads the snip by ~2 ticks
    "spine_x":    [(0.0, 30.0), (0.14, 29.0), (0.32, 25.0, *IN), (0.42, 35.0, *OUTS), (0.54, 34.0),
                   (0.80, 30.5), (L, 30.0)],
    "spine_yaw":  [(0.0, 4.0), (0.30, 8.0), (0.36, 8.0, *IN), (0.43, 1.0, *OUTS), (0.70, 2.5), (L, 4.0)],
    "spine_z":    [(0.0, 1.0), (0.45, 2.0), (L, 1.0)],
    "head_nod":   [(0.0, 0.0), (0.34, -3.0), (0.45, 4.0), (0.60, 3.0), (L, 0.0)],
}
LH = [(0.0, tuple(HOLD)), (0.62, tuple(HOLD + (0.2, 0.2, 0))), (0.70, tuple(HOLD + (0.6, -0.8, 0.5))),
                  (0.80, tuple(HOLD + (0.5, 0.1, 0.05))), (L, tuple(HOLD))]
BASE_L = L
if V == 2:
    # __v2, two passes (2.00 s): snips at 0.45 s and 1.45 s (tick 9 of each 20).
    # Between them the steadying hand leaves the fleece, gives the sheep two
    # slow pats on the back and a stroke, and is back holding by 1.25 s; the
    # herder straightens a touch and looks toward the sheep's head.
    K = {k: fk.tile(v, L, NCYC) for k, v in K.items()}
    K["spine_x"] = fk.splice(K["spine_x"], 0.54, 1.14, [(0.70, 27.0), (0.95, 26.0)])
    K["head_nod"] = fk.splice(K["head_nod"], 0.60, 1.30, [(0.75, -4.0), (1.05, -3.0)])
    K["look_sheep"] = [(0.0, 0.0), (0.56, 0.0), (0.72, 1.0), (1.02, 1.0), (1.20, 0.0), (2 * L, 0.0)]
    PAT = np.array([3.0, 7.3, -11.2])
    LH = fk.tile(LH, L, NCYC)
    LH = fk.splice(LH, 0.50, 1.30, [(0.52, tuple(HOLD)), (0.64, tuple(PAT + (0.2, -2.2, 0.6))), (0.72, tuple(PAT), "QUAD", "EASE_IN"),
                                    (0.80, tuple(PAT + (0.1, -1.8, 0.2))), (0.88, tuple(PAT + (-0.3, 0, 0)), "QUAD", "EASE_IN"),
                                    (1.04, tuple(PAT + (-3.2, 0.2, -0.4))), (1.25, tuple(HOLD))])
    L = L * NCYC
fk.key(K, cyclic=True, length=L)
fk.key_vec("lh", LH, cyclic=True, length=L)

PIV_TIP = (fk.SHEARS_PIVOT, fk.SHEARS_TIP)


def g(hand, direction, tip=None, w_tip=2.0, w_hand=0.6):
    return fk.goal_terms("right", hand=hand, kind="generated", dir_pts=PIV_TIP, direction=direction,
                         tip_pt=fk.SHEARS_TIP, tip=tip, w_tip=w_tip, w_hand=w_hand, w_dir=80.0)


GOALS = [
    (0.00, g((-3.0, 13.0, -3.8), (0.25, 0.25, -0.93), tuple(CUT + (0.3, -1.5, 0.3)), 1.0), None),
    (0.14, g((-3.2, 12.6, -3.2), (0.25, 0.15, -0.95), tuple(CUT + (1.4, -2.2, 1.6)), 1.0), None),
    (0.34, g((-3.4, 10.4, -1.4), (0.25, -0.05, -0.97), tuple(CUT + (2.8, -3.6, 3.8)), 1.5), INQ),  # drawn back
    (SNIP, g((-2.8, 14.4, -4.2), (0.25, 0.35, -0.90), tuple(CUT), 3.0), OUTS),   # snip
    (0.54, g((-2.8, 14.2, -4.0), (0.25, 0.33, -0.91), tuple(CUT + (0.0, -0.3, 0.4)), 2.0), None),
    (0.72, g((-3.2, 15.2, -3.0), (0.25, 0.45, -0.86), tuple(CUT + (0.2, 1.2, 1.2)), 1.0), None),  # peel
]
if V == 2:
    GOALS = fk.tile_goals(GOALS, BASE_L, NCYC)
LOG = fk.key_fk_goals("arm_r", "right", GOALS, lambda t: fk.body(t % L), [-30.0, 0.0, 0.0, -30.0, 0.0], L,
                      probe=lambda w: {"tip": fk.item_point(w, fk.SHEARS_TIP, "generated")})
print("GOALS", json.dumps(LOG))


def solve(t):
    t = t % L
    ch = fk.body(t)
    fk.fk_arm(ch, "arm_r", "right", t)
    ch["cloak"] = {"rot": fk.cloak(t, L)}
    prev = getattr(solve, "_prev", {})
    fk.legs(ch, FEET, prev)
    fk.arm_ik(ch, "left", fk.cv("lh", t), (0.9, 0.5, 0.4), prev)
    ls = max(0.0, min(1.0, c("look_sheep", t)))
    fk.look(ch, (1 - ls) * (CUT + np.array([1.0, -1.0, 0.0])) + ls * np.array([-11.0, 7.0, -14.0]), w_pitch=0.6, w_yaw=0.6, nod=c("head_nod", t))
    solve._prev = {k: ch[k]["rot"] for k in ("left_arm", "right_leg", "left_leg")}
    return ch


times, samples = hsrig.bake(solve, L, objs)
fk.fix_wraps(samples)
tips = [fk.item_point(mcrig.pose_matrices(s), fk.SHEARS_TIP, "generated") for s in samples]
lpal = [fk.palm(mcrig.pose_matrices(s), "left") for s in samples]
pk_t, pk_v, sp = fk.peak_speed_t(times, tips)
sf = int(round(SNIP * fk.FPS))
checks = {
    "snip_t": SNIP, "snip_tick": 9,
    "foot_slide_px_max": fk.foot_slide(samples, FEET),
    "loop_seam_max": fk.loop_seam(samples),
    "tip_at_snip_px": [round(float(v), 2) for v in tips[sf]],
    "tip_err_to_cut_px": round(float(np.linalg.norm(tips[sf] - CUT)), 3),
    "tip_err_to_cut_every_snip_px": [round(float(np.linalg.norm(tips[int(round((SNIP + i * BASE_L) * fk.FPS))] - CUT)), 3)
                                     for i in range(NCYC)],
    "tip_peak_speed_t": pk_t, "tip_peak_speed_px_s": pk_v,
    "left_palm_dev_from_hold_px_at_snips": [round(float(np.linalg.norm(lpal[int(round((SNIP + i * BASE_L) * fk.FPS))] - HOLD)), 3)
                                            for i in range(NCYC)],
}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/farm/author_herder_shear.py (Blender " + bpy.app.version_string + ")",
        "contract": "HERDER_SHEAR 1.00 s loop; snip t=0.45 s = tick 9 of 20 (SHEAR_ACCENT_TICK)"
                    + ("" if V is None else f"; variant = {NCYC} passes, snips at 0.45/1.45 s, sheep patted 0.64-1.04 s"),
        "checks": checks}
path, doc, report, worst = fk.export(CONST, L, True, times, samples,
                                     keep_times=tuple(sorted({x + i * BASE_L for i in range(NCYC) for x in (0.0, 0.34, SNIP, 0.54)} | {L})), meta=meta)
with open(os.path.join(OUT, "export_report.json"), "w") as fh:
    json.dump({"checks": checks, "channels": report, "roundtrip_max_err": worst}, fh, indent=1)
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(OUT, SLUG + ".blend"))
fk.finish(SLUG, L, OUT)
