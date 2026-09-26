"""COURIER_SORT, authored in Blender. Run headless:

    blender -b --factory-startup --python author_courier_sort.py -- [--fast|--full] [v2]

Contract kept from SettlerAnimations.COURIER_SORT / CourierWorkGoal:
  length 1.6 s loop (= SORT_PERIOD 32 ticks); the item goes into the chest at
  t = 0.80 s = SORT_MOVE_TICK 16 (CHEST_STOW sound, anim_check ENTITY_SOUND_CONTRACTS).
  Stationary full-body work loop (SettlerModel: animate(sortState, COURIER_SORT);
  sortState only runs while SORTING and not moving).

Beats: the load (sack/crate) sits at the right foot, the open chest in front.
0.00-0.28 turn right and fold down through hips and knees, the right hand reaching
into the sack; 0.28 grab (a small settle into the knees); 0.28-0.62 lift with the
legs, turning back to the chest, the item carried close; 0.62-0.80 reach in and
set it down: the place lands at 0.80 with a small dip; 0.80-0.95 let go; 0.95-1.6
the hand comes back, a glance at the chest contents, the body eases round to the
load again. The left hand stays braced on the chest's front rim the whole time
(weight on it as the body folds); feet planted (IK), knees take the dips.

Variant v2 (same 1.6 s, same 0.80 place): the courier turns the item over in
his hand and looks at it on the way up (checking the mark), then places it.
"""

import json
import math
import os
import sys

import bpy
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lkit  # noqa: E402
from lkit import hsrig, mcrig  # noqa: E402
import motionkit as mk  # noqa: E402

A = lkit.args()
VARIANT = "v2" if "v2" in A["rest"] else None
CONST = "COURIER_SORT" + ("__V2" if VARIANT else "")
SLUG = CONST.lower()
L = 1.6
GRAB, PLACE = 0.28, 0.80

FOOT_R = np.array([-3.2, 24.0, 0.9])
FOOT_L = np.array([3.0, 24.0, -0.7])
CHEST_Z0, CHEST_TOP = -9.0, 10.0
PICK = np.array([-4.8, 17.6, -5.4])      # into the sack at the right foot
DROP = np.array([-0.8, 11.2, -12.5])      # inside the open chest
RIM = np.array([3.6, CHEST_TOP - 0.2, CHEST_Z0 - 0.4])   # left palm on the front rim

objs = lkit.scene("settler_courier.png")
lkit.box_object("prop:chest", [((-8, CHEST_TOP, -25), (16, 24 - CHEST_TOP, 16))], (0.50, 0.34, 0.17, 1), None)
lkit.box_object("prop:lid", [((-8, CHEST_TOP - 13, -25.5), (16, 13, 1.5))], (0.45, 0.30, 0.15, 1), None)
sack = lkit.box_object("prop:sack", [((-9.0, 18.0, -8.5), (7.5, 6.0, 6.5))], lkit.BURLAP, None)
item = lkit.attach_block_in_hand(objs, "right", (0.72, 0.62, 0.40, 1), "prop:item")

S, I, O = "smooth", "in2", "out2"
# right-hand path (model space): (t, xyz, ease arriving)
HAND = [(0.0, (-2.0, 10.5, -9.5)), (0.12, (-4.0, 13.0, -7.5), S), (GRAB, PICK, O),
        (0.34, PICK + np.array([0, -0.4, 0]), S), (0.50, (-3.2, 9.0, -6.5), S),
        (0.66, (-1.4, 9.0, -11.0), S), (PLACE, DROP, O), (0.92, DROP + np.array([0, -0.8, 1.0]), S),
        (1.15, (-1.8, 9.8, -9.5), S), (1.40, (-2.4, 10.8, -9.2), S), (L, (-2.0, 10.5, -9.5), S)]
if VARIANT:
    HAND = HAND[:4] + [(0.44, (-2.4, 6.8, -6.8), S), (0.56, (-2.0, 6.5, -7.2), S),
                       (0.68, (-1.2, 9.2, -11.2), S)] + HAND[6:]
BODY = {   # (t, value[, ease arriving])
    "pelvis_y": [(0.0, -0.9), (0.10, -1.4, S), (GRAB, -3.3, O), (0.34, -3.5, S), (0.55, -1.2, S),
                 (PLACE, -1.6, O), (0.90, -1.4, S), (1.2, -0.7, S), (L, -0.9, S)],
    "pelvis_x": [(0.0, 0.1), (GRAB, -0.6, S), (0.55, 0.0, S), (PLACE, 0.4, S), (L, 0.1, S)],
    "pelvis_yaw": [(0.0, 3.0), (GRAB, 12.0, O), (0.40, 10.0, S), (0.62, 0.0, S), (PLACE, -2.0, S),
                   (1.25, 0.0, S), (L, 3.0, S)],
    "spine_x": [(0.0, 12.0), (GRAB, 36.0, O), (0.34, 37.0, S), (0.56, 16.0, S), (PLACE, 24.0, O),
                (0.90, 22.0, S), (1.2, 11.0, S), (L, 12.0, S)],
    "spine_yaw": [(0.0, 4.0), (GRAB, 16.0, O), (0.40, 12.0, S), (0.64, -2.0, S), (PLACE, -4.0, S),
                  (1.3, 0.0, S), (L, 4.0, S)],
    "nod": [(0.0, 0.0), (GRAB, 5.0, O), (0.36, 0.0, S), (PLACE, 6.0, O), (0.92, 2.0, S), (1.08, 9.0, S),
            (1.3, 0.0, S), (L, 0.0, S)],
}
TWIST = [(0.0, 0.0), (L, 0.0)]
if VARIANT:
    BODY["spine_x"] = [(0.0, 12.0), (GRAB, 36.0, O), (0.34, 37.0, S), (0.50, 14.0, S), (0.58, 13.0, S),
                       (PLACE, 24.0, O), (0.90, 22.0, S), (1.2, 11.0, S), (L, 12.0, S)]
    TWIST = [(0.0, 0.0), (0.38, 0.0), (0.47, -55.0, S), (0.56, -50.0, S), (0.68, 0.0, S), (L, 0.0)]
prev = {}


def look_target(t, palm_now=None):
    """Eased between the sort beats (QA 2026-09-26: was a one-frame 37-42 deg head snap)."""
    if VARIANT:
        steps = [(0.0, PICK), (0.36, palm_now), (0.60, DROP)]
    else:
        steps = [(0.0, PICK), (0.34, DROP)]
    steps += [(1.02, DROP + np.array([1.5, 0.5, -2.0])),    # glance at what is already in the chest
              (1.28, PICK + np.array([0, -1, 0]))]
    return lkit.eased_target(steps, t, 0.2)


def solve(t):
    t %= L
    val = {k: mk.track([(p[0], (p[1], 0, 0)) + tuple(p[2:]) for p in v], t)[0] for k, v in BODY.items()}
    ch = {"root": {"rot": (0.0, val["pelvis_yaw"], 0.0), "pos": (val["pelvis_x"], val["pelvis_y"], 0.0)},
          "torso": {"rot": (val["spine_x"], val["spine_yaw"], 0.0)}}
    pv = lkit.vel(lambda u: mk.track([(p[0], (p[1], 0, 0)) + tuple(p[2:]) for p in BODY["spine_x"]], u)[0],
                  t - 0.05, L)
    ch["cloak"] = {"rot": (max(-10, min(22, 2.0 + 0.45 * val["spine_x"] - 0.04 * pv)), 0.0, 0.0)}
    hand = np.array(mk.track([(p[0], tuple(p[1])) + tuple(p[2:]) for p in HAND], t))
    lkit.arm_ik_world(ch, "right", hand, (-0.8, 0.7, 0.35), prev, lower=5.5)
    tw = mk.track([(p[0], (p[1], 0, 0)) + tuple(p[2:]) for p in TWIST], t)[0]
    if tw:
        x, y, z = ch["right_forearm"]["rot"]
        ch["right_forearm"]["rot"] = (x, y + tw, z)
    lkit.arm_ik_world(ch, "left", RIM, (0.9, 0.6, 0.2), prev, lower=5.5)
    tgt = look_target(t, lkit.palm(mcrig.pose_matrices(ch), "right", 7.0))   # v2: eyes on the item in hand
    lkit.head_aim(ch, tgt, w_pitch=0.6, w_yaw=0.7, nod=val["nod"])
    lkit.legs_ik(ch, (FOOT_R, FOOT_L), prev, knee=(0.0, 0.0, -1.0), knee_out=0.25)
    return ch


times, samples = hsrig.bake(solve, L, objs)
for f, t in enumerate(times):
    lkit.key_world(item, f, visible=GRAB <= (t % L) < PLACE + 0.02)

herr, lerr, foot = 0.0, 0.0, 0.0
for t, s in zip(times, samples):
    w = mcrig.pose_matrices(s)
    lerr = max(lerr, float(np.linalg.norm(lkit.palm(w, "left", 5.5) - RIM)))
    for side, fz in (("right", FOOT_R), ("left", FOOT_L)):
        foot = max(foot, float(np.linalg.norm(mcrig.xform(w[side + "_shin"], (0, 6, 0)) - fz)))
wg = mcrig.pose_matrices(samples[int(round(GRAB * 60))])
wp = mcrig.pose_matrices(samples[int(round(PLACE * 60))])
checks = {"length_s": L, "loop": True, "grab_t": GRAB, "place_t": PLACE, "place_tick": 16,
          "hand_err_at_grab_px": round(float(np.linalg.norm(lkit.palm(wg, "right", 5.5) - PICK)), 3),
          "hand_err_at_place_px": round(float(np.linalg.norm(lkit.palm(wp, "right", 5.5) - DROP)), 3),
          "left_hand_rim_err_px_max": round(lerr, 3), "foot_slide_px_max": round(foot, 3),
          "seam_max": lkit.seam(samples)}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/logistics/author_courier_sort.py" + (" v2" if VARIANT else "")
                  + " (Blender " + bpy.app.version_string + ")",
        "contract": "COURIER_SORT 1.6 s loop (SORT_PERIOD 32); item placed at t=0.80 s = SORT_MOVE_TICK 16 (CHEST_STOW)",
        "checks": checks}
if A["export"]:
    lkit.export(CONST, L, True, times, samples, keep_times=(GRAB, PLACE), meta=meta)
if A["fast"] or A["full"]:
    lkit.preview(SLUG, L, cams={"side": hsrig.camera("cam_side", (-3.9, -0.4, 1.0), (0.0, -0.4, 0.75), lens=40),
                                "front34": hsrig.camera("cam_front34", (-2.6, -2.9, 1.5), (0.0, -0.3, 0.8), lens=40)},
                 full=A["full"])
    if "review" in A["rest"]:
        lkit.review_sheet(SLUG, L, (-2.3, 1.8, 2.0), (0.0, -0.4, 0.7), lens=40, n=6)
