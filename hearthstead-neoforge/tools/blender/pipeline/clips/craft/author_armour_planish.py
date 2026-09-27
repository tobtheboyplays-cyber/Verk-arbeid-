"""ARMOUR_PLANISH (armourer), authored in Blender -- anim overkill 2026-09-26, derived from
author_hammer_anvil.py (same rig, same contact contract as the smith: 1.00 s loop, the blow at
0.45 s = tick 9, Employment WORK_HAMMER).

The armourer's own motion (he used to play the smith's clip): PLANISHING a breastplate on a
rounded stake -- a shorter, elbow-led, controlled blow (the hammer rises only to the shoulder, no
overhead hang), the off hand holds the curved plate bare-handed and TURNS it a notch after every
blow (0.56-0.86), eyes on the dome, a little blow-and-check rhythm.
__v2 (2 cycles): the second blow is a rivet: set the hammer down after it and press the plate
edge flat with the heel of the hand.
__v3 (3 cycles) = the BREAK: after the first blow he lifts the plate and holds it against his own
chest to try the fit (a proud little look down, a pat on it), the second blow (1.45) is a light
tap back on the stake, and he planishes on.
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402
import propkit  # noqa: E402

LENGTH = 1.0
CONTACT = 0.45                     # tick 9 of 20
TOP, HANG = 0.30, 0.36

# anim lane: the smith's hammer prop (the armourer uses the same hammer) (hearthstead:prop_smith_hammer) replaces the mace
MACE_PNG = os.path.join(ck.REPO, "src", "main", "resources", "assets", "hearthstead", "textures", "item",
                        "prop_smith_hammer.png")
HEAD = (10.5, 13.0, 8.0)           # hammer head centre, item-sprite px
V = ck.variant()                    # "" base, "v2" = inspect-and-blow flavour (2 cycles)
clip = ck.Clip("ARMOUR_PLANISH" + ("__" + V.upper() if V else ""), LENGTH, True, "settler_armourer.png",
               cycles={"": 1, "v2": 2, "v3": 3}[V],
               feet=((-3.6, 24.0, 1.8), (3.0, 24.0, -1.8)),
               tool_png=MACE_PNG if os.path.exists(MACE_PNG) else None)
# the smith's MAINHAND is empty in game: the runtime draws a mace as the hammer
# (the window spans the WHOLE clip: __v2 is 2 cycles -- the old 0..LENGTH window lost the hammer
# for the second blow)
clip.hand_props = [{"hand": "mainhand", "item": "hearthstead:prop_smith_hammer", "from": 0.0, "to": clip.length}]
# anvil: horn/top face at y = 8 (a full block), work bar on it
IRON = (0.33, 0.33, 0.36, 1)
# a stake (tree stump + rounded iron head) and the curved plate on it
clip.prop("stump", (-4.5, 12, -20), (9, 12, 9), (0.40, 0.28, 0.16, 1))
clip.prop("stake", (-2.5, 8, -18), (5, 4, 5), IRON)
clip.prop("plate", (-4.0, 6.5, -17.0), (8, 1.5, 6), (0.78, 0.78, 0.82, 1))

HIT = np.array([-0.8, 6.3, -14.2])          # hammer face on the dome of the plate
TONGS = np.array([3.4, 6.4, -12.6])          # off hand holding the plate edge

K = {
    # root: posVec (y up). Knees soft at rest, a lift in the wind-up, the
    # weight dropping through the knees into the blow (hips lead the arm).
    "root_y": [(0.0, -0.3), (0.10, -0.28, "inout"), (0.27, -0.14, "decel"), (0.33, -0.18, "accel"),
               (0.43, -1.05, "out"), (0.50, -0.95, "inout"), (0.70, -0.5, "smooth"), (0.90, -0.3)],
    "root_x": [(0.0, 0.0), (0.26, -0.55, "inout"), (0.43, 0.55, "out"), (0.60, 0.45, "smooth"),
               (0.90, 0.05)],
    "root_z": [(0.0, 0.0), (0.26, 0.35, "inout"), (0.43, -0.45, "out"), (0.70, -0.2, "smooth"),
               (0.95, 0.0)],
    "root_yaw": [(0.0, 1.0), (0.10, 1.5, "inout"), (0.26, 8.5, "inout"), (0.32, 7.0, "accel"),
                 (0.42, -4.5, "out"), (0.55, -3.5, "smooth"), (0.85, 1.0)],
    # spine: + x = lean forward, + y = right shoulder back, + z = shoulders to the left
    # QA 2026-09-26: shallower forward fold (the chest used to fold onto the fist)
    "torso_x": [(0.0, 9.0), (0.10, 8.0, "inout"), (0.28, 0.0, "decel"), (0.35, 0.5, "accel"),
                (0.44, 15.0, "out"), (0.50, 14.0, "inout"), (0.62, 12.0, "smooth"), (0.88, 9.0)],
    "torso_y": [(0.0, 0.0), (0.12, 2.0, "inout"), (0.29, 12.0, "decel"), (0.34, 11.0, "accel"),
                (0.44, -6.0, "out"), (0.56, -5.0, "smooth"), (0.88, 0.0)],
    "torso_z": [(0.0, 0.0), (0.29, 4.0, "decel"), (0.34, 4.0, "accel"), (0.44, -3.0, "out"),
                (0.60, -2.0, "smooth"), (0.90, 0.0)],
    "head_nod": [(0.0, 2.0), (0.30, -2.0, "inout"), (0.36, -2.0, "accel"), (0.46, 5.0, "out"),
                 (0.70, 3.0, "smooth")],
    "head_yaw": [(0.0, 0.0)],
    "head_roll": [(0.0, 0.0)],
    "cloak_add": [(0.0, 0.0), (0.44, -2.0, "out"), (0.52, 4.0, "decel"), (0.80, 0.0)],
    # tong hand knocked down by the blow, then recovers
    "lh_dy": [(0.0, 0.0), (0.45, 0.0, "linear"), (0.48, 0.55, "decel"), (0.62, 0.0, "smooth")],
}
# a shorter, elbow-led blow than the smith: less trunk wind-up
for _p in ("torso_y", "root_yaw", "root_x", "root_z"):
    K[_p] = [(k[0], k[1] * 0.55, *k[2:]) for k in K[_p]]
# the plate is turned a notch after every blow
K["lh_dx"] = [(0.0, 0.0), (0.56, 0.0, "inout"), (0.70, -1.3, "inout"), (0.86, 0.0, "inout")]
K["lh_dz"] = [(0.0, 0.0), (0.56, 0.0, "inout"), (0.70, 0.8, "inout"), (0.86, 0.0, "inout")]
clip.keys(K)
clip.key_vec("look", [(0.0, (0.0, 7.5, -13.5))])

HEAD_HIT = HIT + np.array([0.0, -2.0, 0.0])     # head centre just over the bar at the blow
# QA 2026-09-26: fist goals added (5th element) and the wrist bone solved, so the
# hammer tips in the hand: the fist works at the right of the chest instead of
# under the chin, and the drive passes beside the cheek, not through the face.
LOG = clip.arm_tool_poses("right", [
    (0.00, HEAD_HIT + [0.0, -1.0, 0.3], (0.1, 0.15, -0.98), "inout", (-3.2, 3.0, -8.0)),     # resting over the work
    (0.08, HEAD_HIT + [-0.4, -2.8, 0.9], (0.1, -0.05, -0.99), "smooth", (-2.6, 1.0, -6.4)),  # breaks off the work
    (0.20, (-6.8, -3.6, -11.0), (-0.1, -0.55, -0.83), "decel", (-5.6, 0.6, -7.0)),           # rising, elbow leads
    (TOP, (-8.2, -6.2, -7.4), (-0.2, -0.85, -0.5), "inout", (-7.2, -0.8, -5.2)),              # cocked at the shoulder
    (HANG, (-8.2, -6.4, -7.0), (-0.2, -0.86, -0.46), "accel", (-7.2, -1.0, -5.0)),            # a brief set
    (0.405, (-4.8, 0.4, -14.0), (-0.05, -0.2, -0.98), "linear", (-4.6, 2.6, -7.6)),           # driving down
    (CONTACT, HEAD_HIT, (0.1, 0.25, -0.96), "out", (-3.2, 3.8, -7.9)),                      # CONTACT, tick 9
    (0.49, HEAD_HIT + [0.0, -1.6, 0.3], (0.1, 0.1, -0.99), "inout", (-3.2, 2.8, -7.9)),     # rebound off the iron
    (0.56, HEAD_HIT + [0.0, -0.3, 0.1], (0.1, 0.22, -0.97), "smooth", (-3.2, 3.6, -7.9)),   # settles on the work
    (0.80, HEAD_HIT + [0.0, -1.2, 0.3], (0.1, 0.12, -0.98), "smooth", (-3.2, 3.0, -8.0)),
], HEAD, knob=(2.5, 2.5, 8.0), neck=(9.5, 9.5, 8.0), wrist=True, avoid_head=1.0, choke=4.0,
    wrist_lim=(95.0, 30.0, 35.0))
print("GOALS", LOG)


def tongs(t, world):
    return TONGS + np.array([ck.c("lh_dx", t), ck.c("lh_dy", t), ck.c("lh_dz", t)])


if V == "v2":
    # __v2: the second blow is a rivet: after it the heel of the off hand presses the plate edge flat
    clip.vary({
        "lh_dy": [(0.0, 0.0), (1.50, 0.0, "inout"), (1.62, 0.9, "decel"), (1.74, 1.1, "inout"), (1.90, 0.0)],
        "torso_x": [(0.0, 0.0), (1.50, 0.0, "inout"), (1.66, 4.0, "decel"), (1.80, 4.0, "inout"), (1.95, 0.0)],
        "head_nod": [(0.0, 0.0), (1.50, 0.0, "inout"), (1.66, 6.0, "inout"), (1.95, 0.0)],
    })
if V == "v3":
    # __v3 BREAK: the plate is lifted to his own chest to try the fit, a look down, a pat, back
    clip.vary({
        "lh_dy": [(0.0, 0.0), (0.58, 0.0, "inout"), (0.84, -2.6, "inout"), (1.30, -2.4, "inout"), (1.62, 0.0)],
        "lh_dz": [(0.0, 0.0), (0.58, 0.0, "inout"), (0.84, 7.6, "inout"), (1.30, 7.4, "inout"), (1.62, 0.0)],
        "lh_dx": [(0.0, 0.0), (0.58, 0.0, "inout"), (0.84, -2.4, "inout"), (1.30, -2.4, "inout"), (1.62, 0.0)],
        "torso_x": [(0.0, 0.0), (0.58, 0.0, "inout"), (0.84, -9.0, "inout"), (1.25, -8.0, "inout"), (1.60, 0.0)],
        "head_nod": [(0.0, 0.0), (0.60, 0.0, "inout"), (0.86, 24.0, "inout"), (1.02, 20.0, "inout"),
                     (1.12, 26.0, "inout"), (1.24, 18.0, "inout"), (1.55, 0.0)],
        "head_roll": [(0.0, 0.0), (0.9, 0.0, "inout"), (1.05, 6.0, "inout"), (1.25, 0.0, "inout"), (1.6, 0.0)],
    })
    clip.key_vec("look", [(0.0, (0.0, 6.5, -14.0))])


clip.arm_live("left", tongs, (0.75, 0.45, 0.55))
clip.head_w = (0.3, 0.5)   # in game the look control adds its own pitch on top

propkit.recolour_sprite()
clip.run(contacts=[("hammer_on_work", CONTACT, "right_tool", HEAD_HIT),
                   ("tongs_hold", 0.0, "left", TONGS), ("tongs_hold_mid", 0.8, "left", TONGS)],
         keep=(TOP, HANG, 0.405, 0.49),
         
         strike=("right_tool", CONTACT),
         meta={"variant": V or "base",
               "contract": "ARMOUR_PLANISH 1.00 s loop (variants: whole cycles); plate-hammer contact t=0.45 s = tick 9 of 20 "
                           "(Employment WORK_HAMMER)",
               "held_item": "display prop hearthstead:prop_smith_hammer (vanilla handheld transform); "
                            "head centre item px (10.5, 13.0) lands on the bar"})
