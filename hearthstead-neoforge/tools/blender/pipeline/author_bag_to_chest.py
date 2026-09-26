"""BAG_TO_CHEST (owner name) == SettlerAnimations.BAG_TO_CHEST_UNLOAD, authored in Blender.

    blender -b --factory-startup --python author_bag_to_chest.py -- [--render]

Contract kept (BagToChestAnimationContract / GroundedBagUnload / SettlerModel):
  length 4.00 s = 80 ticks, one-shot sampled on the SERVER clock;
  12 bag/world contact (BAG_DOWN sound), 30 item in hand, 31 lid contact,
  36 lid open, 37 lid release, 48 authoritative chest deposit, 49 below-rim,
  64 lid closed, 80 next-cycle handoff. Grounded sessions jump 64 -> 24 for
  every further item, so the pose at 1.20 s equals the pose at 3.20 s exactly.

Runtime facts this clip is built around:
  * the split bag_* arms are what is visible (right_arm/left_arm are hidden);
    the solve uses right_arm/right_forearm (identical pivots/lengths) and the
    export renames them to bag_right_upper_arm/bag_right_forearm/... ;
  * the sack path is SettlerModel's own: smoothstep over ticks 0-12 from the
    torso shoulder point (0,-10.5,2.5) to the forward-left ground spot, and the
    reverse over 64-80; the item flight is SettlerRenderer's smoothstep from the
    sack mouth (+0.72 block) to chest centre (+1.05 block) over ticks 30-48.
    The palms are solved onto those exact paths;
  * root is translation only: the grounded sack is a root child, so every turn
    toward the sack or chest is a torso twist over planted feet.
Nominal layout for the solve: chest one block ahead (centre z -16 px).
"""

import json
import math
import os
import sys

import bpy
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import bagclip_rig as br  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402

ARGS = br.argv()
CONST = "BAG_TO_CHEST_UNLOAD"
SLUG = "bag_to_chest"
LENGTH = 4.00
TPS = 20.0
OUT = os.path.join(br.WORK, "out", SLUG)
os.makedirs(OUT, exist_ok=True)

sc = hsrig.reset()
objs = hsrig.build_scene(os.path.join(br.TEX_DIR, "settler_courier.png"), None)
hsrig.prop_box("ground", (-60, 24, -60), (120, 1, 120), (0.30, 0.45, 0.22, 1))
S = 1.0                                           # courier pack scale (0.80 .. 1.05 by fill)
sack = br.box_mesh_object("sack", br.SACK_CUBES, br.BURLAP)
item = br.box_mesh_object("item", [((-2, -2, -2), (4, 4, 4))], (0.85, 0.72, 0.30, 1.0))
chest_body = br.box_mesh_object("chest_body", [((-7, 14, -23), (14, 10, 14))], (0.55, 0.36, 0.17, 1.0))
chest_lid = br.box_mesh_object("chest_lid", [((-7, -4, 0), (14, 5, 14)), ((-1, -2, 13.5), (2, 4, 1))],
                               (0.60, 0.40, 0.19, 1.0))
LID_HINGE = np.array([0.0, 14.0, -23.0])

# BagTransferPresentation.visualSackPoint(): forward 0.28, left 0.58 block.
SACK_GROUND = np.array([0.58 * 16, br.GROUND - 8.0 * S, -0.28 * 16])
ITEM_FROM = np.array([0.58 * 16, br.GROUND - 0.72 * 16, -0.28 * 16])
ITEM_TO = np.array([0.0, br.GROUND - 1.05 * 16, -16.0])
SHOULDER_PT = (0.0, -10.5, 2.5)
LEFT_GRIP = np.array([0.0, -0.3, 3.0])       # left fist round the sack's neck (pivot-relative)
RIGHT_GRIP = np.array([-3.8, 1.8, 2.2])      # right palm on the sack's near shoulder
STRAP_L = np.array([4.5, -6.5, -4.0])        # torso-local fist on the left strap front
STRAP_R = np.array([-4.5, -6.5, -4.0])
OUT_L = np.array([10.0, -7.5, -1.5])          # torso-local waypoint out in front of the left shoulder
FEET = br.stance_feet()

# WALK_LADEN frame 0 arms (Blender, 2026-09-26): the laden gait this clip starts from / ends in
REST = {"right_arm": {"rot": (4.8, 0.0, 4.0)}, "left_arm": {"rot": (-5.66, 0.0, -4.0)},
        "right_forearm": {"rot": (-18.0, 0.0, 0.0)}, "left_forearm": {"rot": (-23.6, 0.0, 0.0)}}
wr = mcrig.pose_matrices({"torso": {"rot": (8, 0, 0)}, **REST})
R_HANG, L_HANG = br.palm(wr, "right"), br.palm(wr, "left")


def tk(n):
    return n / TPS


def _openness(k):
    o = 0.0
    for i in range(k + 1):
        target = 1.0 if 31 <= i < 64 else 0.0
        o = min(1.0, o + 0.1) if target > o else max(0.0, o - 0.1)
    return o


def lid_angle(t):
    """Vanilla ChestLidController: openness +/-0.1 per tick, rendered with the
    partial-tick lerp (getOpenNess(partial)), angle = (1-(1-o)^3)*90."""
    tick = t * TPS
    k = int(math.floor(tick))
    o = _openness(k - 1) + (_openness(k) - _openness(k - 1)) * (tick - k) if k >= 1 else 0.0
    return (1.0 - (1.0 - o) ** 3) * 90.0


def lid_point(t, local):
    a = math.radians(lid_angle(t))
    return LID_HINGE + mcrig.rx(a) @ np.asarray(local, float)


def item_pos(t):
    p = br.smoothstep((t * TPS - 30.0) / 18.0)
    return ITEM_FROM + (ITEM_TO - ITEM_FROM) * p


def sack_attach(t):
    """1 = the sack rides the authored hand path, 0 = strapped on the back."""
    clock = t * TPS
    if clock < 12.0:
        return br.smoothstep((clock - 2.5) / 2.5)
    if clock >= 64.0:
        return 1.0 - br.smoothstep((clock - 75.0) / 4.5)   # shrugged onto the back
    return 1.0


# pose at tick 24 == pose at tick 64 (grounded repeat). One dict, used twice.
P24 = {"torso_x": 17.0, "torso_y": -9.0, "torso_z": 0.0, "root_x": 0.3, "root_y": -1.3, "root_z": 0.3,
       "head_nod": 2.0}
LH24 = SACK_GROUND + np.array([-0.5, -3.5, -1.0])          # hovering over the sack mouth
RH24 = np.array([-2.2, 12.2, -9.8])                         # fingers on the latch / lid lip

K = {
    # spine pitch: laden lean, brace, lower the sack, planted ready, dip into the
    # sack, the long reach-and-dip into the chest, recover, heft, laden lean
    "torso_x": [(0.0, 8.0), (tk(2), 10.0), (tk(8), 20.0), (tk(12), 23.0), (tk(14), 22.0),
                (tk(24), P24["torso_x"]), (tk(29), 23.0), (tk(33), 25.0), (tk(40), 31.0),
                (tk(47), 37.0), (tk(49), 38.5), (tk(54), 30.0), (tk(64), P24["torso_x"]),
                (tk(69), 25.0), (tk(74), 20.0), (tk(80), 8.0)],
    # twist toward the sack (-, settler's left) and back to the chest (+)
    "torso_y": [(0.0, 0.0), (tk(6), -14.0), (tk(12), -22.0), (tk(15), -20.0), (tk(24), P24["torso_y"]),
                (tk(29), -16.0), (tk(34), -12.0), (tk(42), -2.0), (tk(48), 3.0), (tk(56), -2.0),
                (tk(64), P24["torso_y"]), (tk(68), -27.0), (tk(71), -24.0), (tk(75), -12.0), (tk(80), 0.0)],
    "torso_z": [(0.0, 0.0), (tk(12), -3.0), (tk(24), 0.0), (tk(29), -4.0), (tk(40), 0.0),
                (tk(64), 0.0), (tk(68), -3.0), (tk(80), 0.0)],
    # hips: soften, sit into the set-down, planted ready, weight forward for the dip,
    # sit for the heft (slow start = weight), rise under the load
    "root_y": [(0.0, 0.0), (tk(2), -0.5), (tk(10), -3.0), (tk(12), -3.2), (tk(15), -2.8),
               (tk(24), P24["root_y"]), (tk(29), -2.2), (tk(40), -2.8), (tk(48), -3.5), (tk(50), -3.6),
               (tk(56), -2.2), (tk(64), P24["root_y"]), (tk(68), -3.4), (tk(71), -3.5), (tk(76), -1.2),
               (tk(80), 0.0)],
    "root_z": [(0.0, 0.0), (tk(12), 0.8), (tk(24), P24["root_z"]), (tk(40), -0.6), (tk(48), -1.1),
               (tk(56), -0.2), (tk(64), P24["root_z"]), (tk(70), 0.8), (tk(80), 0.0)],
    "root_x": [(0.0, 0.0), (tk(12), 0.8), (tk(24), P24["root_x"]), (tk(29), 0.9), (tk(44), -0.2),
               (tk(64), P24["root_x"]), (tk(70), 0.8), (tk(80), 0.0)],
    # ownership weights
    "l_sack": [(0.0, 0.0), (tk(0.5), 0.0), (tk(4), 1.0), (tk(13), 1.0), (tk(16), 0.0), (tk(64), 0.0),
               (tk(64.5), 0.0), (tk(66), 1.0), (tk(73.5), 1.0), (tk(76), 0.0), (tk(80), 0.0)],
    "r_sack": [(0.0, 0.0), (tk(80), 0.0)],     # the sack is a one-hand (left) load both ways
    "l_item": [(0.0, 0.0), (tk(29), 0.0), (tk(30.5), 1.0), (tk(48), 1.0), (tk(50.5), 0.0), (tk(80), 0.0)],
    # fingers under the lid lip at the contact tick; the lid then flies up on its own
    "r_lid": [(0.0, 0.0), (tk(29.5), 0.0), (tk(31), 1.0), (tk(31.2), 1.0), (tk(33.5), 0.0), (tk(80), 0.0)],
    # fists on the strap fronts (torso-local), and a waypoint out in front of the
    # left shoulder so the hand never sweeps through the shoulder joint
    "r_strap": [(0.0, 0.0), (tk(2.5), 1.0), (tk(6), 1.0), (tk(9), 0.0), (tk(80), 0.0)],
    "l_strap": [(0.0, 0.0), (tk(80), 0.0)],
    "l_out": [(0.0, 0.0), (tk(73), 0.0), (tk(75.5), 1.0), (tk(80), 0.0)],
    # the heft is one-handed on the neck; the free right hand braces on the right
    # knee from the squat until the legs have done the work
    "r_knee": [(0.0, 0.0), (tk(6.5), 0.0), (tk(10), 1.0), (tk(14), 1.0), (tk(18), 0.0), (tk(64.5), 0.0), (tk(67.5), 1.0), (tk(75), 1.0), (tk(78.5), 0.0), (tk(80), 0.0)],
    "head_nod": [(0.0, 0.0), (tk(12), 4.0), (tk(24), P24["head_nod"]), (tk(48), 6.0),
                 (tk(64), P24["head_nod"]), (tk(80), 0.0)],
}
br.key(K)
LIFT_OFF = np.array([3.5, -1.5, 3.0])        # just lifted off the back, over the left shoulder
WIDE_HIGH = np.array([11.5, -0.5, 1.0])      # out past the left arm at shoulder height
WIDE_LOW = np.array([12.5, 7.5, -2.8])       # swinging down outside the left hip
br.key_vec("sackA", [(tk(2.5), LIFT_OFF), (tk(4), LIFT_OFF), (tk(6.5), WIDE_HIGH), (tk(9), WIDE_LOW),
                     (tk(11), SACK_GROUND + np.array([0.3, -0.8, 0.2])), (tk(12), SACK_GROUND)])
br.key_vec("sackB", [(tk(64), SACK_GROUND), (tk(66.5), SACK_GROUND + np.array([0.3, -0.9, 0.2])),
                     (tk(71), WIDE_LOW), (tk(75), WIDE_HIGH), (tk(80), WIDE_HIGH)])
# free palm paths (model space). Where an ownership weight is 1 these are ignored.
br.key_vec("lh", [(0.0, L_HANG), (tk(15), SACK_GROUND + np.array([1.0, -3.0, 0.5])),
                  (tk(20), LH24 + np.array([0.8, -1.2, 0.0])), (tk(24), LH24),
                  (tk(28), ITEM_FROM + np.array([0.0, 2.2, 0.2])),          # hand inside the sack
                  (tk(30), ITEM_FROM + np.array([0.0, -0.9, 0.0])),
                  (tk(48), ITEM_TO + np.array([0.0, -1.3, 0.3])),
                  (tk(51), np.array([1.2, 13.8, -12.6])),                   # dip below the rim
                  (tk(54), np.array([2.5, 11.0, -11.0])), (tk(58), LH24 + np.array([-1.5, -2.0, -2.0])),
                  (tk(64), LH24), (tk(66), LH24 + np.array([0.3, 0.5, 0.0])),
                  (tk(80), L_HANG)])
br.key_vec("rh", [(0.0, R_HANG), (tk(6), (-3.0, 9.0, -5.5)),
                  (tk(13.5), SACK_GROUND + RIGHT_GRIP + np.array([-1.5, -1.5, 0.0])), (tk(20), RH24 + np.array([0.0, -1.5, 1.0])), (tk(24), RH24),
                  (tk(30), RH24 + np.array([0.0, 0.5, 0.0])),
                  (tk(33), (-2.6, 8.5, -12.2)),                             # push the lid on up
                  (tk(35.5), (-3.2, 4.5, -12.8)),
                  (tk(38), (-4.2, 5.0, -11.5)),
                  (tk(43), (-5.0, 13.3, -9.6)),                             # brace on the rim
                  (tk(55), (-5.0, 13.3, -9.6)), (tk(60), RH24 + np.array([0.0, -1.5, 0.3])),
                  (tk(64), RH24), (tk(66), RH24 + np.array([-0.5, -1.0, 1.0])),
                  (tk(72), (-6.5, 13.0, -3.0)), (tk(77), (-6.0, 10.0, -3.0)), (tk(80), R_HANG)])
br.key_vec("look", [(0.0, (3.0, 14.0, -10.0)), (tk(6), SACK_GROUND + np.array([0, 2, 0])),
                    (tk(14), SACK_GROUND + np.array([0, 2, 0])), (tk(20), (0.0, 12.0, -14.0)),
                    (tk(24), (4.0, 13.0, -8.0)), (tk(28), ITEM_FROM), (tk(31), ITEM_FROM),
                    (tk(40), (4.0, 9.0, -11.0)), (tk(48), ITEM_TO + np.array([0, 5, 0])),
                    (tk(56), (0.0, 12.0, -14.0)), (tk(64), (4.0, 13.0, -8.0)),
                    (tk(68), SACK_GROUND + np.array([0, 2, 0])), (tk(80), (0.0, 12.0, -40.0))])
c = hsrig.ctrl


def torso_x(t):
    return c("torso_x", t)


def sack_pivot(t, world):
    """Off the back, up over the LEFT shoulder, out wide of the arm and down to the
    forward-left spot (and the reverse for the heft). The engine places the prop
    from the left palm in these windows (see LEFT_GRIP), so hand and sack agree;
    the old straight shoulder->ground line ran through the left shoulder."""
    clock = t * TPS
    if 12.0 <= clock < 64.0:
        return SACK_GROUND.copy()
    w = sack_attach(t)
    path = br.cv("sackA" if clock < 12.0 else "sackB", t)
    return (1 - w) * mcrig.xform(world["torso"], SHOULDER_PT) + w * path


def solve(t):
    import os as _os
    rng = _os.environ.get("IKDBG")
    if rng:
        a, b, sd = rng.split(",")
        br.DEBUG = (lambda side: side == sd) if float(a) <= t <= float(b) else None
        if br.DEBUG:
            print("T", round(t, 3), "ls", round(c("l_sack", t), 2), "rs", round(c("r_sack", t), 2))
    ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": (c("root_x", t), c("root_y", t), c("root_z", t))},
          "torso": {"rot": (torso_x(t), c("torso_y", t), c("torso_z", t))}}
    ch["cloak"] = {"rot": br.cloak(t, torso_x(t), torso_x)}
    world = mcrig.pose_matrices(ch)
    prev = getattr(solve, "_prev", {})
    br.begin_frame(not prev)
    br.leg_ik(ch, world, FEET, prev)
    piv = sack_pivot(t, world)
    ew = br.edge_weight(t, LENGTH, ramp=0.10)
    # left: free -> sack neck -> item
    ls, li = c("l_sack", t), c("l_item", t)
    tw = world["torso"]
    tgt = br.cv("lh", t)
    # straps and the out-front waypoint live on the torso (they move with the lean)
    k = c("l_strap", t)
    tgt = (1 - k) * tgt + k * mcrig.xform(tw, STRAP_L)
    k = c("l_out", t)
    tgt = (1 - k) * tgt + k * mcrig.xform(tw, OUT_L)
    tgt = (1 - ls) * tgt + ls * (piv + S * LEFT_GRIP)
    tgt = (1 - li) * tgt + li * (item_pos(t) + np.array([0.0, -1.3, 0.3]))
    # elbow flares out and forward while the hand carries the sack over the shoulder
    lpole = (1 - ls) * np.array([0.9, 0.4, 0.5]) + ls * np.array([1.0, -0.1, -0.5])
    br.arm_ik(ch, world, "left", tgt, lpole, prev, match=REST["left_arm"]["rot"], match_w=ew)
    # right: free -> sack side/bottom -> lid lip
    rs, rl = c("r_sack", t), c("r_lid", t)
    rt = br.cv("rh", t)
    k = c("r_strap", t)
    rt = (1 - k) * rt + k * mcrig.xform(world["torso"], STRAP_R)
    rt = (1 - rs) * rt + rs * (piv + S * RIGHT_GRIP)
    # the hand only unlatches: after tick 31 the lid snaps up faster than a hand
    # should follow, so the fade-out blends from the lip where the hand left it
    rt = (1 - rl) * rt + rl * (lid_point(min(t, tk(31.0)), (-2.2, 1.2, 13.4)))
    # heft: the free right hand braces on the right knee while the legs drive up
    rk = c("r_knee", t)
    rt = (1 - rk) * rt + rk * (br.knee_point(mcrig.pose_matrices(ch), "right") + np.array([0.0, -1.0, 0.0]))
    br.arm_ik(ch, world, "right", rt, (-0.9, 0.4, 0.5), prev, match=REST["right_arm"]["rot"], match_w=ew)
    br.blend_to_pose(ch, REST, br.edge_weight(t, LENGTH, ramp=0.15))
    br.head_look(ch, world, br.cv("look", t), w_pitch=0.5, w_yaw=0.75, nod=c("head_nod", t))
    solve._prev = {k: ch[k]["rot"] for k in ("left_arm", "right_arm", "right_leg", "left_leg")}
    solve._sack = piv
    solve._targets = (tgt, rt)
    return ch


sack_path, targets = [], []


def solve_rec(t):
    ch = solve(t)
    sack_path.append(solve._sack.copy())
    targets.append(solve._targets)
    return ch


times, samples = hsrig.bake(solve_rec, LENGTH, objs)

for f, t in enumerate(times):
    br.key_prop(sack, mcrig.T(*sack_path[f]) @ mcrig.mat4(s=S), f)
    clock = t * TPS
    br.key_prop(item, mcrig.T(*item_pos(t)), f, visible=30 <= clock <= 48)
    a = math.radians(lid_angle(t))
    br.key_prop(chest_lid, mcrig.T(*LID_HINGE) @ mcrig.mat4(mcrig.rx(a)), f)
    br.key_prop(chest_body, np.eye(4), f)

# ---------------------------------------------------------------- checks
f24, f64 = int(round(1.2 * 60)), int(round(3.2 * 60))
rep = max(abs(a - b) for bone in samples[f24] for kind in ("rot", "pos")
          for a, b in zip(samples[f24][bone].get(kind, (0, 0, 0)), samples[f64][bone].get(kind, (0, 0, 0))))
checks = br.contact_report(times, samples, FEET, {
    "left_on_item_30_48": ("left", lambda t: item_pos(t) + np.array([0.0, -1.3, 0.3]) if 1.53 <= t <= 2.40 else None),
    "left_on_sack_3_12": ("left", lambda t: sack_path[int(round(t * 60))] + S * LEFT_GRIP
                          if 0.15 <= t <= 0.65 else None),
    "right_on_lid_31_33": ("right", lambda t: lid_point(t, (-2.2, 1.2, 13.4)) if 1.55 <= t <= 1.565 else None),
    "left_on_sack_68_75": ("left", lambda t: sack_path[int(round(t * 60))] + S * LEFT_GRIP
                           if 3.30 <= t <= 3.67 else None),
})
checks["pose_24_vs_64_max_diff"] = round(rep, 4)
checks["ticks"] = {"bag_world": 12, "item_hand": 30, "lid_contact": 31, "lid_open": 36, "lid_release": 37,
                   "deposit": 48, "below_rim": 49, "lid_closed": 64, "repeat_to": 24, "handoff": 80}
checks["root_rotation_max_deg"] = round(max(abs(v) for s in samples for v in s["root"]["rot"]), 3)
checks["snaps_deg_per_frame_top"] = br.snap_report(times, samples)
print("CHECKS", json.dumps(checks))

RENAME = {"right_arm": "bag_right_upper_arm", "right_forearm": "bag_right_forearm",
          "left_arm": "bag_left_upper_arm", "left_forearm": "bag_left_forearm"}
meta = {"source": "tools/blender/pipeline/author_bag_to_chest.py (Blender " + bpy.app.version_string + ")",
        "owner_action": "BAG_TO_CHEST",
        "contract": "BAG_TO_CHEST_UNLOAD 4.00 s, server-clocked; ticks 12/30/31/36/37/48/49/64/80; "
                    "pose(1.20 s) == pose(3.20 s) for the grounded 64->24 repeat; root translation only; "
                    "arms authored on the split bag_* bones",
        "checks": checks,
        # The sack path the palms are solved on (model px, +y down; pivot = the sack mesh's
        # top-front-centre). SettlerModel should place the prop from the LEFT palm in the
        # hand-owned windows: pivot = leftPalm - left_grip * scale.
        "prop_path_model_px": {f"{f / 60 * TPS:.1f}": [round(float(v), 2) for v in sack_path[f]]
                               for f in range(len(times)) if (f % 3 == 0) and not (12 < f / 60 * TPS < 64)},
        "prop_rule": {"left_grip": [float(v) for v in LEFT_GRIP],
                      "attach_ticks_0_12": "shoulder->hand smoothstep((clock-2.5)/2.5); hand-owned 4..11; "
                                           "blend hand->anchor over 10..12",
                      "attach_ticks_64_80": "anchor->hand over 64..66; hand-owned 66..73.5; then table "
                                            "(hand lets go, sack held at WIDE_HIGH) and shoulder attach "
                                            "1-smoothstep((clock-75)/4.5)",
                      "wide_high_model_px": [float(v) for v in WIDE_HIGH]}}
keep = tuple(tk(n) for n in (0, 12, 24, 30, 31, 36, 37, 48, 49, 64, 80))
path, doc, report, worst = br.export(CONST, LENGTH, False, times, samples, keep_times=keep, meta=meta,
                                     rename=RENAME)
# the repeat seam must also hold AFTER key reduction
name = "animation.settler.bag_to_chest_unload"
seam = 0.0
import export_mc_clip as ex  # noqa: E402
for b, kinds in doc["animations"][name]["bones"].items():
    for kind in kinds:
        a1 = ex.sample(doc, name, b, kind, 1.2)
        a2 = ex.sample(doc, name, b, kind, 3.2)
        seam = max(seam, max(abs(x - y) for x, y in zip(a1, a2)))
print("SEAM_24_64_EXPORTED", round(seam, 4))
with open(os.path.join(OUT, "export_report.json"), "w") as fh:
    json.dump({"checks": checks, "channels": report, "roundtrip_max_err": worst, "seam_exported": seam},
              fh, indent=1)
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(br.WORK, SLUG + ".blend"))

if "--render" in ARGS:
    cams = br.cameras(side_loc=(4.6, -0.7, 1.25), side_tgt=(0.0, -0.55, 0.75),
                      f34_loc=(3.0, -3.2, 1.8), f34_tgt=(0.2, -0.5, 0.75))
    br.render_clip(SLUG, LENGTH, cams, step=6 if "--quick" in ARGS else 2)
