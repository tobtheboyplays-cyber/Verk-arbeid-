"""CLIMB_LADDER, authored in Blender. Run headless:

    blender -b --factory-startup --python author_climb_ladder.py -- [--fast|--full] [v2]

Contract kept from SettlerAnimations.CLIMB_LADDER / SettlerModel / SettlerEntity:
  length 1.0 s loop, full-body (SettlerModel: climbState replaces locomotion
  while onClimbable()). LADDER_CREAK plays at ticks 5 and 15 of a 20-tick
  period (frequency-only; climbState's phase is not server-locked). The two
  hand-and-foot catches are authored exactly at t = 0.25 and t = 0.75 so they
  line up with the creak rate.

Motion (treadmill, like the gait clips: the body stays at the origin and the
ladder slides down past it at the climb speed, 16 px/s = 1 block/s, rungs 4 px
apart): diagonal hand/foot pairs. At 0.25 the RIGHT hand catches a rung above
the head as the LEFT foot lands on a rung at knee height; at 0.75 the left
hand and right foot do the same. A gripping hand rides down with its rung
(the arms pull, the elbow flexes), a planted foot rides down with its rung
(the leg pushes, the knee straightens); the free hand/foot reach up on an
eased arc that clears the rungs. Torso and head lean toward the ladder, turn a
little toward the reaching hand; the hips shift over the pushing leg.

Variant v2 (2.0 s = two base cycles, catches at 0.25/0.75/1.25/1.75): in the
second cycle the climber glances down over his right shoulder, then back up.
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

A = lkit.args()
VARIANT = "v2" if "v2" in A["rest"] else None
CONST = "CLIMB_LADDER" + ("__V2" if VARIANT else "")
SLUG = CONST.lower()
BASE_L = 1.0
L = 2.0 if VARIANT else BASE_L

V = 16.0            # px/s ladder speed past the body (1 block/s)
RUNG = 4.0
PLANE = -6.0        # ladder plane (rung centres), model z
HAND_X, FOOT_X = 3.3, 2.7
HAND_HI = -4.0      # catch height (model y); a rung is there at every catch
HAND_GRIP = 0.55    # s the hand holds before letting go
FOOT_HI = 16.0      # foot lands 8 px below the hip... on a rung (-4 + 5 * 4)
FOOT_GRIP = 0.5
CATCH = {"right_hand": 0.25, "left_foot": 0.25, "left_hand": 0.75, "right_foot": 0.75}

objs = lkit.scene("settler_courier.png")
# preview ladder: two rails + rungs every 4 px, scrolled down at V (period = one rung)
rungs = [((-HAND_X - 3.0, y - 0.5, PLANE - 0.5), (2 * HAND_X + 6.0, 1.0, 1.0)) for y in np.arange(-44, 44, RUNG)]
ladder = lkit.box_object("prop:ladder", [((-HAND_X - 3.5, -48, PLANE - 0.5), (1.0, 96, 1.5)),
                                         ((HAND_X + 2.5, -48, PLANE - 0.5), (1.0, 96, 1.5))] + rungs,
                         (0.55, 0.40, 0.24, 1.0), None)
bpy.data.objects["ground"].hide_render = True


def smooth(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


def limb_track(t, catch, grip, hi, reach_lift, back):
    """(y, z_offset) of a hand/foot: rides its rung down for `grip` s, then an eased reach up."""
    u = (t - catch) % BASE_L
    if u < grip:
        return hi + V * u, 0.0
    s = (u - grip) / (BASE_L - grip)
    lo = hi + V * grip
    e = smooth(s)
    # leave the rung slowly, travel fast, settle onto the next one (overshoot lift then drop)
    y = lo + (hi - lo) * e - reach_lift * math.sin(math.pi * s) ** 2
    z = back * math.sin(math.pi * s)          # pull in toward the body to clear the rungs
    return y, z


def key(prop, pts):
    hsrig.key_curve(prop, pts, cyclic=True, length=L)


# body: hips over the pushing leg, torso turns toward the reaching hand
cyc = [(0.0, 0.0), (0.25, 0.35), (0.5, 0.0), (0.75, -0.35), (1.0, 0.0)]
key("pelvis_x", cyc if not VARIANT else cyc[:-1] + [(1.0 + t, v) for t, v in cyc])
yaw = [(0.0, 0.0), (0.12, -3.5), (0.5, 0.0), (0.62, 3.5), (1.0, 0.0)]
key("spine_yaw", yaw if not VARIANT else yaw[:-1] + [(1.0 + t, v) for t, v in yaw])
bob = [(0.0, 0.25), (0.25, -0.2), (0.5, 0.25), (0.75, -0.2), (1.0, 0.25)]
key("pelvis_y", bob if not VARIANT else bob[:-1] + [(1.0 + t, v) for t, v in bob])
look = [(0.0, 0.0), (L, 0.0)]
lookp = [(0.0, 0.0), (L, 0.0)]
if VARIANT:
    look = [(0.0, 0.0), (1.05, 0.0), (1.35, 34.0), (1.6, 36.0), (1.9, 0.0), (2.0, 0.0)]
    lookp = [(0.0, 0.0), (1.05, 0.0), (1.35, 48.0), (1.6, 50.0), (1.9, 0.0), (2.0, 0.0)]
key("look_yaw", look)
key("look_pitch", lookp)
c = hsrig.ctrl
prev = {}


def solve(t):
    t %= L
    ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": (c("pelvis_x", t), c("pelvis_y", t), 0.0)},
          "torso": {"rot": (7.0, c("spine_yaw", t), -0.8 * c("pelvis_x", t))}}
    # head: eyes up the ladder toward the next catch; v2 glances down over the right shoulder
    ch["head"] = {"rot": (-18.0 + c("look_pitch", t), 0.6 * c("spine_yaw", t) + c("look_yaw", t),
                          0.0)}
    ch["cloak"] = {"rot": (-4.0 + 1.5 * math.sin(2 * math.pi * (t - 0.1) * 2), 0.0,
                           -1.2 * math.sin(2 * math.pi * (t - 0.1)))}
    for side, sx in (("right", -1), ("left", 1)):
        y, dz = limb_track(t, CATCH[side + "_hand"], HAND_GRIP, HAND_HI, 1.2, 2.2)
        tgt = np.array([sx * HAND_X, y, PLANE + dz])
        lkit.arm_ik_world(ch, side, tgt, (sx * 1.0, 0.9, 0.6), prev, lower=5.5)
    feet = []
    for side, sx in (("right", -1), ("left", 1)):
        y, dz = limb_track(t, CATCH[side + "_foot"], FOOT_GRIP, FOOT_HI, 1.5, 1.8)
        feet.append(np.array([sx * FOOT_X, y, PLANE + 2.5 + dz]))
    lkit.legs_ik(ch, feet, prev, knee=(0.0, 0.0, -1.0), knee_out=0.9)
    return ch


times, samples = hsrig.bake(solve, L, objs)
# scroll the preview ladder (a rung sits at HAND_HI at every catch)
for f, t in enumerate(times):
    off = (V * (t - 0.25)) % RUNG
    lkit.key_world(ladder, f, loc=(0.0, off + HAND_HI - (-44 + 4 * 10) , 0.0))

# checks: hands/feet on their targets during grip
err = {"hand": 0.0, "foot": 0.0}
for t, s in zip(times, samples):
    w = mcrig.pose_matrices(s)
    for side, sx in (("right", -1), ("left", 1)):
        u = (t - CATCH[side + "_hand"]) % BASE_L
        if u < HAND_GRIP:
            y, _ = limb_track(t, CATCH[side + "_hand"], HAND_GRIP, HAND_HI, 0, 0)
            err["hand"] = max(err["hand"], float(np.linalg.norm(lkit.palm(w, side, 5.5) - np.array([sx * HAND_X, y, PLANE]))))
        u = (t - CATCH[side + "_foot"]) % BASE_L
        if u < FOOT_GRIP:
            y, _ = limb_track(t, CATCH[side + "_foot"], FOOT_GRIP, FOOT_HI, 0, 0)
            err["foot"] = max(err["foot"], float(np.linalg.norm(mcrig.xform(w[side + "_shin"], (0, 6, 0))
                                                              - np.array([sx * FOOT_X, y, PLANE + 2.5]))))
knee_z = min(float(mcrig.xform(mcrig.pose_matrices(s)[side + "_shin"], (0, 0, -2))[2])
             for s in samples for side in ("right", "left"))
checks = {"length_s": L, "loop": True, "catches_t": sorted(set(CATCH.values())) if not VARIANT else [0.25, 0.75, 1.25, 1.75],
          "creak_ticks": "5 and 15 of 20 (frequency-only)", "climb_speed_px_s": V,
          "hand_on_rung_err_px_max": round(err["hand"], 3), "foot_on_rung_err_px_max": round(err["foot"], 3),
          "knee_front_min_z (ladder plane %.1f)" % PLANE: round(knee_z, 2), "seam_max": lkit.seam(samples)}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/logistics/author_climb_ladder.py" + (" v2" if VARIANT else "")
                  + " (Blender " + bpy.app.version_string + ")",
        "contract": "CLIMB_LADDER 1.0 s loop; hand+foot catches at 0.25/0.75 s (LADDER_CREAK ticks 5/15 of 20)",
        "checks": checks}
if A["export"]:
    keep = (0.25, 0.75) if not VARIANT else (0.25, 0.75, 1.0, 1.25, 1.75)
    lkit.export(CONST, L, True, times, samples, keep_times=keep, meta=meta)
if A["fast"] or A["full"]:
    lkit.preview(SLUG, L, cams={"side": hsrig.camera("cam_side", (-3.9, 0.3, 1.0), (0.0, -0.15, 0.85), lens=40),
                                "front34": hsrig.camera("cam_front34", (-1.6, 2.4, 1.6), (0.0, 0.0, 1.0), lens=42)},
                 full=A["full"])
    if "review" in A["rest"]:
        lkit.review_sheet(SLUG, L, (-1.8, 2.9, 1.3), (0.0, 0.0, 0.85), lens=40, n=6)
