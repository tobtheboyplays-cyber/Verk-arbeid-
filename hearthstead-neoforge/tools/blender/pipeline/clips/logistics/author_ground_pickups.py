"""GATHER_LOG and PICKUP_STOW (one-shots that return to their start pose). Run headless:

    blender -b --factory-startup --python author_ground_pickups.py -- <gather|stow> [--fast|--full]

Contracts kept from SettlerAnimations / SettlerEntity / AcquireRequestedEquipmentGoal:
  GATHER_LOG   1.10 s one-shot (gatherState stopped at 1150 ms); full reach at
               t = 0.35 s; fast drop (0.35 s), slow rise (the log is heavy);
               the free LEFT hand does the pickup, the MAINHAND axe stays low.
               Returns exactly to its first frame.
  PICKUP_STOW  1.40 s one-shot (PICKUP_DURATION_TICKS 28, state expires 1450 ms);
               the snatch lands at t = 0.55 s = CONTACT_TICK 11; the item is
               tucked into the left-hip bag at 0.95 (held to 1.10); returns
               exactly to its first frame.
  Both are full-body; SettlerModel resets every bone before playing them.

GATHER beats: hips lead the stoop (back bends, knees give), the left hand
reaches the log at 0.35; a short hold while the weight comes on; the legs drive
the slow rise, the log comes up against the left hip, the torso ends a hair
past upright and settles.
STOW beats: a counter-move (head drops, chest lifts), the deep stoop with the
right hand hovering, the SNATCH (fast, straight in) at 0.55, a hold, the rise
with a small overshoot, the fast roll of the wrist into the left-hip bag at
0.90-0.95, a hold on the bag, then a decelerating release back to rest.
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
GATHER = "stow" not in A["rest"]
CONST = "GATHER_LOG" if GATHER else "PICKUP_STOW"
SLUG = CONST.lower()
L = 1.10 if GATHER else 1.40
CONTACT = 0.35 if GATHER else 0.55
ALL = mcrig.EXPORT_BONES

if GATHER:
    REST = {"right_arm": {"rot": (-12.0, -8.0, -3.0)}, "left_arm": {"rot": (-8.0, 8.0, 4.0)},
            "torso": {"rot": (2.0, 0.0, 0.0)}, "head": {"rot": (4.0, 0.0, 0.0)},
            "right_leg": {"rot": (-3.0, 0.0, -2.0)}, "left_leg": {"rot": (3.0, 0.0, 2.0)},
            "cloak": {"rot": (2.0, 0.0, 0.0)}}
else:
    REST = {"right_arm": {"rot": (-6.0, -4.0, -2.0)}, "left_arm": {"rot": (-4.0, 4.0, 2.0)},
            "torso": {"rot": (5.0, 0.0, 0.0)}, "head": {"rot": (5.0, 0.0, 0.0)},
            "right_leg": {"rot": (-4.0, 0.0, -3.0)}, "left_leg": {"rot": (4.0, 0.0, 3.0)},
            "cloak": {"rot": (2.0, 0.0, 0.0)}}
_w = mcrig.pose_matrices(REST)
FEET = tuple(np.array([mcrig.xform(_w[s + "_shin"], (0, 6, 0))[0], 24.0, mcrig.xform(_w[s + "_shin"], (0, 6, 0))[2]])
             for s in ("right", "left"))

ITEM = np.array([3.4, 20.8, -5.2]) if GATHER else np.array([-1.5, 22.6, -8.0])
BAG = np.array([0.8, 8.9, -3.6])           # belt pouch, front-left of the buckle (PICKUP_STOW)

objs = lkit.scene("settler_lumberer.png", os.path.join(lkit.REF, "iron_axe.png"))
item = lkit.box_object("prop:item", [((-1.8, -1.8, -1.8), (3.6, 3.6, 3.6))] if not GATHER else
                       [((-4.0, -1.4, -1.4), (8.0, 2.8, 2.8))], lkit.BARK if GATHER else (0.8, 0.8, 0.8, 1), None)
if not GATHER:
    lkit.box_object("prop:bag", [((BAG[0] - 1.0, BAG[1] - 2.0, BAG[2] - 2.0), (2.5, 4.5, 4.0))], lkit.BURLAP, None)

S, I, O, LIN = "smooth", "in2", "out2", "linear"
if GATHER:
    BODY = {"pelvis_y": [(0.0, 0.0), (0.28, -4.2, O), (CONTACT, -4.5, S), (0.45, -4.6, S), (0.80, -1.4, S),
                         (0.98, 0.15, O), (L, 0.0, S)],
            "spine_x": [(0.0, 2.0), (0.30, 47.0, O), (CONTACT, 48.0, S), (0.45, 46.0, S), (0.80, 14.0, S),
                        (0.98, -1.5, O), (L, 2.0, S)],
            "spine_z": [(0.0, 0.0), (CONTACT, -4.0, S), (0.8, -2.0, S), (L, 0.0, S)],
            "nod": [(0.0, 0.0), (CONTACT, 8.0, O), (0.8, 2.0, S), (L, 0.0, S)]}
    HAND = [(0.0, (6.4, 12.0, -1.0)), (0.22, (5.5, 18.0, -5.0), S), (CONTACT, tuple(ITEM), O),
            (0.45, tuple(ITEM + np.array([0, -0.3, 0])), S), (0.80, (6.2, 16.0, -4.0), S),
            (0.98, (6.6, 13.0, -2.5), S), (L, (6.4, 12.0, -1.0), S)]
    ACTIVE, OTHER = "left", "right"
else:
    BODY = {"pelvis_y": [(0.0, 0.0), (0.10, 0.3, S), (0.50, -5.4, S), (CONTACT, -5.7, LIN), (0.70, -5.6, LIN),
                         (0.90, -0.5, S), (1.10, -0.2, S), (1.28, 0.1, S), (L, 0.0, S)],
            "spine_x": [(0.0, 5.0), (0.10, 1.0, S), (0.50, 52.0, S), (CONTACT, 55.0, LIN), (0.70, 54.0, LIN),
                        (0.90, -2.0, S), (0.95, 0.0, S), (1.10, 1.0, S), (1.28, 6.5, S), (L, 5.0, S)],
            "spine_yaw": [(0.0, 0.0), (0.50, -4.0, S), (0.70, -3.0, S), (0.90, -10.0, S), (0.95, -13.0, S), (1.10, -11.0, S), (L, 0.0, S)],
            "nod": [(0.0, 0.0), (0.10, 10.0, S), (CONTACT, 14.0, LIN), (0.9, 0.0, S), (0.95, 8.0, S), (L, 0.0, S)]}
    HAND = [(0.0, (-6.3, 12.0, -1.0)), (0.10, (-6.0, 12.5, -2.0), S), (0.50, (-1.8, 19.2, -8.2), S),
            (CONTACT, tuple(ITEM), LIN), (0.70, tuple(ITEM + np.array([0, -0.2, 0])), LIN),
            (0.90, (0.5, 11.5, -5.0), S), (0.95, tuple(BAG), LIN), (1.10, tuple(BAG + np.array([0, 0.2, 0])), LIN),
            (1.28, (-5.8, 12.8, -1.8), O), (L, (-6.3, 12.0, -1.0), S)]
    ACTIVE, OTHER = "right", "left"
prev = {}


def val(name, t):
    if name not in BODY:
        return 0.0
    return mk.track([(p[0], (p[1], 0, 0)) + tuple(p[2:]) for p in BODY[name]], t)[0]


def smooth(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


def blend(ch, pose, w):
    if w <= 1e-5:
        return
    for b in ALL:
        want = pose.get(b, {})
        have = ch.setdefault(b, {})
        for k in ("rot", "pos"):
            if b in mcrig.ROTATION_ONLY and k == "pos":
                continue
            a = have.get(k, (0.0, 0.0, 0.0))
            z = want.get(k, (0.0, 0.0, 0.0))
            have[k] = tuple(x + (y - x) * w for x, y in zip(a, z))


_WK = [(0.0, 0.0), (0.30, 5.0), (0.45, 20.0), (0.55, 12.0), (0.75, 5.0), (1.00, 0.0), (L, 0.0)]


def _wrist(t):
    for (a, va), (b, vb) in zip(_WK, _WK[1:]):
        if a <= t <= b:
            x = (t - a) / (b - a)
            return va + (vb - va) * x * x * (3 - 2 * x)
    return 0.0


def solve(t):
    t = min(max(t, 0.0), L)
    ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": (0.0, val("pelvis_y", t), 0.0)},
          "torso": {"rot": (val("spine_x", t), val("spine_yaw", t), val("spine_z", t))}}
    ch["cloak"] = {"rot": (max(-10, min(24, 2.0 + 0.45 * (val("spine_x", t) - REST["torso"]["rot"][0]))), 0.0, 0.0)}
    s = 1 if ACTIVE == "left" else -1
    hand = np.array(mk.track([(p[0], tuple(p[1])) + tuple(p[2:]) for p in HAND], t))
    lkit.arm_ik_world(ch, ACTIVE, hand, (s * 0.8, 0.7, 0.5), prev, lower=5.5)
    # the other arm: GATHER keeps the axe low and a touch back; STOW braces toward the left knee
    bend = smooth(val("spine_x", t) / 45.0)
    if GATHER:
        ch["right_arm"] = {"rot": (-12.0 - 10.0 * bend, -8.0 - 4.0 * bend, -3.0 - 3.0 * bend)}
        ch["right_forearm"] = {"rot": (-18.0 * bend, 0.0, 0.0)}
    else:
        w = mcrig.pose_matrices(ch)
        knee = mcrig.xform(w["left_leg"], (0.3, 4.5, -2.6))
        rest_hand = mcrig.xform(w["torso"], (6.4, -2.0, -1.0))
        tgt = rest_hand + (knee - rest_hand) * smooth((val("spine_x", t) - 10.0) / 35.0)
        lkit.arm_ik_world(ch, "left", tgt, (0.9, 0.4, 0.5), prev, lower=5.5)
    # QA 2026-09-26: eased look (was a one-frame 61 deg head snap at CONTACT + 0.2)
    far = np.array([0.0, 4.0, -40.0])
    steps = [(0.0, ITEM), (CONTACT + 0.12, far)] if GATHER else \
        [(0.0, ITEM), (CONTACT + 0.12, BAG + np.array([-2.0, 0, -6.0])), (1.08, far)]
    look = lkit.eased_target(steps, t, 0.2)
    lkit.head_aim(ch, look, w_pitch=0.55, w_yaw=0.4, nod=val("nod", t))
    if not GATHER:
        # QA 2026-09-26: the MAINHAND axe swept through the face (2.3 px) as the body
        # folded over the snatch; tip it forward in the fist through the stoop, but
        # not so far that its head digs into the ground at the lowest point.
        ch["right_item"] = {"rot": (_wrist(t), 0.0, 0.0)}
    lkit.legs_ik(ch, FEET, prev, knee=(0.0, 0.0, -1.0), knee_out=0.35)
    w0 = 1.0 - smooth(t / 0.10)
    w1 = smooth((t - (L - 0.16)) / 0.16)
    blend(ch, REST, max(w0, w1))
    return ch


times, samples = hsrig.bake(solve, L, objs)
for f, t in enumerate(times):
    w = mcrig.pose_matrices(samples[f])
    if t >= CONTACT and (GATHER or t < 0.95):
        p = lkit.palm(w, ACTIVE, 7.5)
        lkit.key_world(item, f, loc=tuple(p), visible=True)
    else:
        lkit.key_world(item, f, loc=tuple(ITEM), visible=t < CONTACT)
wc = mcrig.pose_matrices(samples[int(round(CONTACT * 60))])
checks = {"length_s": L, "loop": False, "contact_t": CONTACT,
          "contact_tick": 7 if GATHER else 11,
          "palm_err_at_contact_px": round(float(np.linalg.norm(lkit.palm(wc, ACTIVE, 5.5) - ITEM)), 3),
          "returns_to_start_max_deg": round(max(abs(a - b) for bone in ALL for k in ("rot",)
                                              for a, b in zip(samples[0].get(bone, {}).get(k, (0, 0, 0)),
                                                              samples[-1].get(bone, {}).get(k, (0, 0, 0)))), 4)}
if not GATHER:
    wb = mcrig.pose_matrices(samples[int(round(0.95 * 60))])
    checks["palm_err_at_bag_px"] = round(float(np.linalg.norm(lkit.palm(wb, "right", 5.5) - BAG)), 3)
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/logistics/author_ground_pickups.py (Blender "
                  + bpy.app.version_string + ")",
        "contract": ("GATHER_LOG 1.10 s one-shot; full reach t=0.35 s; returns to its first frame" if GATHER else
                     "PICKUP_STOW 1.40 s one-shot; snatch t=0.55 s = CONTACT_TICK 11; bag tuck 0.95-1.10 s; "
                     "returns to its first frame"),
        "checks": checks}
if A["export"]:
    lkit.export(CONST, L, False, times, samples, keep_times=(CONTACT,) if GATHER else (CONTACT, 0.95), meta=meta)
if A["fast"] or A["full"]:
    lkit.preview(SLUG, L, cams={"side": hsrig.camera("cam_side", (-3.8, -0.3, 1.0), (0.0, -0.3, 0.8), lens=40),
                                "front34": hsrig.camera("cam_front34", (-2.4, -2.8, 1.5), (0.0, -0.2, 0.8), lens=40)},
                 full=A["full"])
    if "review" in A["rest"]:
        lkit.review_sheet(SLUG, L, (-1.4, -3.2, 1.4), (0.0, -0.2, 0.75), lens=42, n=6)
