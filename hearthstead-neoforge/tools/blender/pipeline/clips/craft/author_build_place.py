"""BUILD_PLACE (Builder lane): set a block and tap it home. New clip.

    blender -b --factory-startup --python author_build_place.py -- [--fast|--full] [--no-export]

Contract (plan/BUILDER.md section 8; wired in SettlerModel via activityClock):
  animation.settler.build_place, 1.60 s (32 ticks), looping.
  Reach forward-down and set the block (0.00-0.45 s), steady it with the off
  hand, then two seating hammer taps that land EXACTLY at t = 0.90 s and
  1.20 s (ticks 18 and 24; BuilderWorkGoal#beat plays nail_tap/chisel_tap on
  those ticks), then recover into the next reach.

Weight: the body dips into the reach (knees give, hips drop 1 px), the torso
leans in to 24 deg and comes back up as the block seats; each tap is a short
cock of the forearm, an accelerating drop, and a small rebound.
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402

LENGTH = 1.6
TAPS = (0.90, 1.20)
TEX = "settler_builder.png" if os.path.exists(os.path.join(ck.TEX_DIR, "settler_builder.png")) \
    else "settler_carpenter.png"

clip = ck.Clip("BUILD_PLACE", LENGTH, True, TEX,
               feet=((-2.8, 24.0, 1.2), (2.8, 24.0, -1.4)), knee_out=0.15)
clip.prop("course", (-8, 16, -18), (16, 8, 8), (0.55, 0.40, 0.24, 1))   # the course below
BLOCK = np.array([0.0, 12.0, -12.0])                                     # top face of the new block
clip.prop("block", (-4, 12, -16), (8, 4, 8), (0.62, 0.46, 0.28, 1))

K = {
    "root_y": [(0.0, 0.0), (0.30, 0.9, "inout"), (0.45, 1.1, "out"), (0.70, 0.7, "inout"),
               (0.85, 0.8, "accel"), (0.90, 1.0, "out"), (1.12, 0.8, "accel"), (1.20, 1.05, "out"),
               (1.40, 0.3, "inout")],
    "root_x": [(0.0, 0.0)],
    "root_z": [(0.0, 0.0), (0.40, -0.6, "inout"), (1.40, 0.0, "inout")],
    "root_yaw": [(0.0, 0.0)],
    "torso_x": [(0.0, 8.0), (0.35, 24.0, "inout"), (0.50, 22.0, "inout"), (0.85, 19.0, "accel"),
                (0.90, 22.0, "out"), (1.12, 19.0, "accel"), (1.20, 22.5, "out"), (1.45, 9.0, "inout")],
    "torso_y": [(0.0, 0.0), (0.80, 4.0, "inout"), (0.90, 1.0, "out"), (1.10, 4.0, "inout"),
                (1.20, 1.0, "out"), (1.45, 0.0, "inout")],
    "torso_z": [(0.0, 0.0)],
    "head_nod": [(0.0, 4.0), (0.40, 10.0, "inout"), (1.25, 8.0, "inout"), (1.50, 3.0, "inout")],
    "head_yaw": [(0.0, 0.0), (1.30, -6.0, "inout"), (1.55, 0.0, "inout")],
    "head_roll": [(0.0, 0.0)],
    "cloak_add": [(0.0, 0.0), (0.40, 4.0, "inout"), (1.45, 0.0, "inout")],
}
clip.keys(K)
clip.key_vec("look", [(0.0, tuple(BLOCK + [0, 0, 2])), (1.3, tuple(BLOCK)),
                      (1.5, tuple(BLOCK + [3, -1, 2]))])

UP = np.array([-1.0, -5.5, 3.0])
clip.arm_goals("right", [
    (0.00, (-5.0, 12.5, -4.0), "inout"),
    (0.40, tuple(BLOCK + [-3.0, 0.5, 1.0]), "inout"),        # hand on the block's edge
    (0.55, tuple(BLOCK + [-3.2, 0.2, 1.2]), "inout"),
    (0.80, tuple(BLOCK + [-2.0, 0.0, 1.0] + UP), "accel"),   # cock
    (TAPS[0], tuple(BLOCK + [-2.0, -0.4, 1.0]), "out"),       # TAP 1, tick 18
    (0.98, tuple(BLOCK + [-2.0, -1.4, 1.2]), "inout"),       # rebound
    (1.10, tuple(BLOCK + [-2.0, 0.0, 1.0] + UP * 1.1), "accel"),
    (TAPS[1], tuple(BLOCK + [-2.0, -0.4, 1.0]), "out"),       # TAP 2, tick 24
    (1.30, tuple(BLOCK + [-2.2, -1.2, 1.5]), "inout"),
    (1.50, (-5.0, 12.8, -4.5), "inout"),
], (-0.6, 0.4, 0.7))
clip.arm_goals("left", [
    (0.00, (5.0, 12.5, -4.0), "inout"),
    (0.40, tuple(BLOCK + [3.0, 0.5, 1.0]), "inout"),         # both hands set it down
    (0.60, tuple(BLOCK + [3.2, 0.3, 1.5]), "hold"),          # then steady it
    (1.25, tuple(BLOCK + [3.2, 0.3, 1.5]), "inout"),
    (1.50, (5.2, 12.8, -4.5), "inout"),
], (0.6, 0.4, 0.7))
clip.head_w = (0.4, 0.55)
clip.hand_props = [{"hand": "mainhand", "item": "minecraft:mace", "from": 0.55, "to": 1.40},
                   {"hand": "offhand", "item": "minecraft:oak_planks", "from": 0.0, "to": 0.40}]
clip.run(contacts=[("set_r", 0.40, "right", BLOCK + [-3.0, 0.5, 1.0]),
                   ("set_l", 0.40, "left", BLOCK + [3.0, 0.5, 1.0])],
         keep=(0.40, 0.80, 1.10) + TAPS,
         strike=("right", list(TAPS)),
         meta={"contract": "BUILD_PLACE 1.60 s loop (32 ticks); seating taps at ticks 18/24 "
                           "(t=0.90/1.20 s); reach-and-set 0.00-0.45 s"})
