"""FARMER_CARRY (arms-only overlay), authored in Blender. Run headless:

    blender -b --factory-startup --python author_farmer_carry.py -- [--fast|--full]

Contract kept (SettlerAnimations.FARMER_CARRY / SettlerModel carry branch):
  length 1.80 s looping, wall-clock; ARMS ONLY (right_arm, left_arm and their
  forearms). WALK_LADEN owns the legs, root, spine, cloak and every footstep,
  and applySack projects the real sack, so this clip exports nothing else.
  No sound contract. The MAINHAND hoe rides over the right shoulder (haft outside the head); the
  free left hand closes across the chest on the front strap.

Life in the loop (subtle, it rides on a walk): the hoe rides on the right shoulder
and settles a little with each half-cycle; at ~0.8 s the left hand hitches
the strap (a short tug down and a re-grip) and eases back; the elbow breathes.
Authored in torso space (body at identity), since the overlay rides the walk's torso.
"""

import json
import math
import os
import sys

import bpy
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import farmkit as fk  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402

CONST, SLUG = "FARMER_CARRY", "farmer_carry"
L = 1.8
BONES = ["right_arm", "left_arm", "right_forearm", "left_forearm"]
OUT = os.path.join(fk.WORK, "out", "farm", SLUG)
os.makedirs(OUT, exist_ok=True)
c = hsrig.ctrl

sc, objs = fk.build(fk.tex("farmer"), item="hoe")
# preview only: the sack SettlerModel projects onto the back (torso child)
sack = fk.cubes_object("sack", [((-3.5, -10.5, 2.5), (7, 8, 4.5))], (0.62, 0.50, 0.33, 1), objs["torso"])
STRAP = np.array([2.6, 4.4, -4.7])       # left palm on the front strap (chest, model space at rest)
OUTS = ("SINE", "EASE_OUT")
IN = ("QUAD", "EASE_IN")
fk.key({
    # strap hitch: tug down + in at 0.80, re-grip, settle
    "hitch":   [(0.0, 0.0), (0.62, 0.0), (0.74, 1.0, *OUTS), (0.82, 1.0, *IN), (1.05, 0.0), (L, 0.0)],
}, cyclic=True, length=L)

# Hoe over the right shoulder, haft sloping back, blade behind: the classic field
# carry, clear of the face (the haft runs outside the head at x ~ -7). MC holds
# tools square to the forearm, so the forearm stands up and the haft lies back.
HAFT = (fk.HOE_KNOB, fk.HOE_NECK)


def g(hand, direction):
    return fk.goal_terms("right", hand=hand, dir_pts=HAFT, direction=direction, w_dir=150.0)


GOALS = [
    (0.00, g((-7.0, 2.2, -3.4), (-0.06, -0.30, 0.95)), None),
    (0.45, g((-7.0, 2.6, -3.2), (-0.06, -0.26, 0.96)), None),     # the tool settles on the shoulder
    (0.90, g((-7.0, 2.1, -3.5), (-0.06, -0.31, 0.95)), None),
    (1.35, g((-7.0, 2.5, -3.3), (-0.06, -0.27, 0.96)), None),
]
LOG = fk.key_fk_goals("arm_r", "right", GOALS, lambda t: {}, [-40.0, 0.0, 0.0, -120.0, 0.0], L,
                      probe=lambda w: {"blade": fk.item_point(w, fk.HOE_BLADE),
                                       "neck": fk.item_point(w, fk.HOE_NECK)})
print("GOALS", json.dumps(LOG))


def solve(t):
    t = t % L
    ch = {}
    fk.fk_arm(ch, "arm_r", "right", t)
    h = c("hitch", t)
    breathe = 0.25 * math.sin(2 * math.pi * t / (L / 2))
    target = STRAP + np.array([-0.3 * h, 1.3 * h + breathe, -0.4 * h])
    prev = getattr(solve, "_prev", {})
    fk.arm_ik(ch, "left", target, (1.0, 0.7, 0.3), prev)
    solve._prev = {"left_arm": ch["left_arm"]["rot"]}
    return ch


times, samples = hsrig.bake(solve, L, objs)
fk.fix_wraps(samples)
lp = [fk.palm(mcrig.pose_matrices(s), "left") for s in samples]
checks = {
    "loop_seam_max": fk.loop_seam(samples, BONES),
    "left_palm_to_strap_px_max": round(max(float(np.linalg.norm(p - STRAP)) for p in lp), 3),
    "left_elbow_range_deg": [round(min(s["left_forearm"]["rot"][0] for s in samples), 1),
                             round(max(s["left_forearm"]["rot"][0] for s in samples), 1)],
    "bones": BONES,
}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/farm/author_farmer_carry.py (Blender " + bpy.app.version_string + ")",
        "contract": "FARMER_CARRY 1.80 s loop, arms-only overlay over WALK_LADEN (no sound contract)",
        "checks": checks}
path, doc, report, worst = fk.export(CONST, L, True, times, samples, keep_times=(0.0, L), meta=meta,
                                     bones=BONES)
with open(os.path.join(OUT, "export_report.json"), "w") as fh:
    json.dump({"checks": checks, "channels": report, "roundtrip_max_err": worst}, fh, indent=1)
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(OUT, SLUG + ".blend"))
fk.finish(SLUG, L, OUT)
