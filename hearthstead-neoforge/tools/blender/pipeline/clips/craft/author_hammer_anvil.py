"""HAMMER_ANVIL (smith, armourer), authored in Blender. Run headless:

    blender -b --factory-startup --python author_hammer_anvil.py -- [--fast|--full] [--no-export]

Contract kept (SettlerAnimations.HAMMER_ANVIL / Employment / CrafterWorkGoal):
  length 1.00 s, looping; ANVIL_RING at clip tick 9 of the 20-tick period
  (Employment.soundContactOf(WORK_HAMMER) = 9) -> the hammer lands at
  t = 0.45 s exactly.

Beats (the heaviest clip in the workshop set):
  0.00-0.08  hammer resting on the work, breath, eyes on the bar
  0.08-0.30  wind-up: hand rises up and back on a joint arc, elbow high and
             out; weight rocks onto the back (right) foot, hips turn right
  0.26       hips reverse first (they lead the arm by ~0.05 s)
  0.30-0.36  hang at the top (anticipation)
  0.36-0.45  downswing: accelerating all the way, fastest AT the contact;
             weight drives onto the front foot, knees give, spine folds
  0.45-0.49  tiny rebound off the iron, the tong hand gets knocked down
  0.49-0.56  hammer settles back onto the work
  0.56-1.00  follow-through settle, hips and spine back to ready
The off hand holds the tongs on the work for the whole loop (live IK), so a
still hand sits next to the violent one. The smith's MAINHAND is empty in
game, so the clip declares a display prop (minecraft:mace as the hammer,
"hearthstead_props") and the arm is solved on the held item: the hammer
HEAD lands on the bar at 0.45 s. __v2 (2 cycles): inspect-and-blow between
the two blows.
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

# anim lane 2026-09-26: the real smith's hammer prop (hearthstead:prop_smith_hammer) replaces the mace
MACE_PNG = os.path.join(ck.REPO, "src", "main", "resources", "assets", "hearthstead", "textures", "item",
                        "prop_smith_hammer.png")
HEAD = (10.5, 13.0, 8.0)           # hammer head centre, item-sprite px
V = ck.variant()                    # "" base, "v2" = inspect-and-blow flavour (2 cycles)
clip = ck.Clip("HAMMER_ANVIL" + ("__V2" if V == "v2" else ""), LENGTH, True, "settler_smith.png",
               cycles=2 if V == "v2" else 1,
               feet=((-3.6, 24.0, 1.8), (3.0, 24.0, -1.8)),
               tool_png=MACE_PNG if os.path.exists(MACE_PNG) else None)
# the smith's MAINHAND is empty in game: the runtime draws a mace as the hammer
# (the window spans the WHOLE clip: __v2 is 2 cycles -- the old 0..LENGTH window lost the hammer
# for the second blow)
clip.hand_props = [{"hand": "mainhand", "item": "hearthstead:prop_smith_hammer", "from": 0.0, "to": clip.length}]
# anvil: horn/top face at y = 8 (a full block), work bar on it
IRON = (0.33, 0.33, 0.36, 1)
clip.prop("anvil_base", (-4.5, 20, -20), (9, 4, 9), IRON)
clip.prop("anvil_waist", (-2.5, 12, -18.5), (5, 8, 6), IRON)
clip.prop("anvil_top", (-6.5, 8, -20), (13, 4, 10), IRON)
clip.prop("work_bar", (-3.5, 7, -14.6), (6, 1, 2), (0.85, 0.35, 0.12, 1))

HIT = np.array([-1.5, 6.9, -13.6])          # hammer fist on the bar
TONGS = np.array([2.6, 6.0, -9.6])           # off hand on the tong handles

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
clip.keys(K)
clip.key_vec("look", [(0.0, (0.0, 7.5, -13.5))])

HEAD_HIT = HIT + np.array([0.0, -2.0, 0.0])     # head centre just over the bar at the blow
# QA 2026-09-26: fist goals added (5th element) and the wrist bone solved, so the
# hammer tips in the hand: the fist works at the right of the chest instead of
# under the chin, and the drive passes beside the cheek, not through the face.
LOG = clip.arm_tool_poses("right", [
    (0.00, HEAD_HIT + [0.0, -1.0, 0.3], (0.1, 0.15, -0.98), "inout", (-3.2, 3.0, -8.0)),     # resting over the work
    (0.08, HEAD_HIT + [-0.4, -2.8, 0.9], (0.1, -0.05, -0.99), "smooth", (-2.6, 1.0, -6.4)),  # breaks off the work
    (0.20, (-8.0, -8.5, -8.5), (-0.1, -0.7, -0.7), "decel", (-6.0, -2.5, -6.0)),            # rising, head up in front
    (TOP, (-10.0, -12.0, 0.5), (-0.25, -0.96, 0.1), "inout", (-8.5, -5.5, -2.0)),             # cocked: head over the shoulder
    (HANG, (-10.0, -12.1, 1.3), (-0.25, -0.94, 0.22), "accel", (-8.5, -5.7, -1.6)),           # hang, then drive down
    (0.405, (-8.5, -7.5, -11.0), (-0.12, -0.55, -0.83), "linear", (-6.5, -3.0, -5.6)),        # beside the cheek
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
    # __v2: after the first blow the smith lifts the piece in the tongs to his
    # face, turns it to the light, blows the scale off, and lays it back on the
    # horn in time for the next wind-up. Both blows (t 0.45 and 1.45) and the
    # ring ticks are untouched; the hammer rests on the anvil meanwhile.
    clip.vary({
        "lh_dy": [(0.0, 0.0), (0.56, 0.0, "inout"), (0.80, -7.0, "inout"), (0.99, -7.2, "inout"),
                  (1.22, 0.0)],
        "lh_dz": [(0.0, 0.0), (0.56, 0.0, "inout"), (0.80, 4.0, "inout"), (0.99, 4.2, "inout"),
                  (1.22, 0.0)],
        "lh_dx": [(0.0, 0.0), (0.56, 0.0, "inout"), (0.80, -1.6, "inout"), (0.99, -1.4, "inout"),
                  (1.22, 0.0)],
        "torso_x": [(0.0, 0.0), (0.58, 0.0, "inout"), (0.82, -9.0, "inout"), (0.90, -6.0, "inout"),
                    (0.97, -7.0, "inout"), (1.20, 0.0)],
        "head_nod": [(0.0, 0.0), (0.58, 0.0, "inout"), (0.82, -16.0, "inout"), (0.90, -11.0, "out"),
                     (0.97, -13.0, "inout"), (1.20, 0.0)],
        "head_yaw": [(0.0, 0.0), (0.58, 0.0, "inout"), (0.84, -10.0, "inout"), (1.0, -7.0, "inout"),
                     (1.20, 0.0)],
        "head_roll": [(0.0, 0.0), (0.66, 0.0, "inout"), (0.84, 5.0, "inout"), (0.96, -3.0, "inout"),
                      (1.18, 0.0)],
        "root_y": [(0.0, 0.0), (0.58, 0.0, "inout"), (0.82, 0.1, "inout"), (1.18, 0.0)],
    })


clip.arm_live("left", tongs, (0.75, 0.45, 0.55))
clip.head_w = (0.3, 0.5)   # in game the look control adds its own pitch on top

propkit.recolour_sprite()
clip.run(contacts=[("hammer_on_work", CONTACT, "right_tool", HEAD_HIT),
                   ("tongs_hold", 0.0, "left", TONGS), ("tongs_hold_mid", 0.8, "left", TONGS)],
         keep=(TOP, HANG, 0.405, 0.49),
         
         strike=("right_tool", CONTACT),
         meta={"variant": V or "base",
               "contract": "HAMMER_ANVIL 1.00 s loop (variants: whole cycles); ANVIL_RING contact t=0.45 s = tick 9 of 20 "
                           "(Employment.soundContactOf WORK_HAMMER)",
               "held_item": "display prop hearthstead:prop_smith_hammer (vanilla handheld transform); "
                            "head centre item px (10.5, 13.0) lands on the bar"})
