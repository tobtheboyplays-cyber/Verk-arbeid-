"""BUILD_HAMMER (Builder lane): the overhead beat on roofs and frames. New clip.

    blender -b --factory-startup --python author_build_hammer.py -- [--fast|--full] [--no-export]

Contract: animation.settler.build_hammer, 1.00 s (20 ticks), looping. The
strike lands EXACTLY at t = 0.45 s (tick 9; BuilderWorkGoal#beat). The off
hand braces the beam above the head; the body rises into the wind-up and
drops its weight into the blow.
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402

LENGTH = 1.0
STRIKE = 0.45
TEX = "settler_builder.png" if os.path.exists(os.path.join(ck.TEX_DIR, "settler_builder.png")) \
    else "settler_carpenter.png"

clip = ck.Clip("BUILD_HAMMER", LENGTH, True, TEX,
               feet=((-3.0, 24.0, 1.0), (3.0, 24.0, -1.2)), knee_out=0.15)
BEAM = np.array([0.0, -8.0, -9.0])       # underside of the beam overhead, in front
clip.prop("beam", (-12, -12, -13), (24, 4, 8), (0.55, 0.40, 0.24, 1))

clip.keys({
    "root_y": [(0.0, 0.0), (0.30, -0.8, "inout"), (STRIKE, 0.6, "out"), (0.60, 0.3, "inout"),
               (0.90, 0.0, "inout")],
    "root_x": [(0.0, 0.0)], "root_z": [(0.0, 0.0)], "root_yaw": [(0.0, 0.0)],
    "torso_x": [(0.0, -6.0), (0.30, -11.0, "accel"), (STRIKE, 2.0, "out"), (0.65, -3.0, "inout"),
                (0.95, -6.0, "inout")],
    "torso_y": [(0.0, 0.0), (0.30, 5.0, "accel"), (STRIKE, -2.0, "out"), (0.80, 0.0, "inout")],
    "torso_z": [(0.0, 0.0)],
    "head_nod": [(0.0, -22.0), (STRIKE, -16.0, "out"), (0.80, -22.0, "inout")],
    "head_yaw": [(0.0, 0.0)], "head_roll": [(0.0, 0.0)],
    "cloak_add": [(0.0, 0.0), (STRIKE, 5.0, "out"), (0.85, 0.0, "inout")],
})
clip.key_vec("look", [(0.0, tuple(BEAM)), (0.9, tuple(BEAM))])
clip.arm_goals("right", [
    (0.00, (-5.0, -9.0, -2.0), "inout"),
    (0.30, (-5.5, -13.5, 1.5), "accel"),                   # wind-up behind the head
    (STRIKE, tuple(BEAM + [-2.5, 2.0, 1.0]), "out"),        # STRIKE, tick 9
    (0.55, tuple(BEAM + [-2.8, 3.2, 1.6]), "inout"),        # rebound
    (0.85, (-5.0, -9.0, -2.0), "inout"),
], (-0.7, 0.2, 0.6))
clip.arm_goals("left", [
    (0.00, tuple(BEAM + [3.0, 2.5, 1.0]), "hold"),         # bracing the beam
    (0.40, tuple(BEAM + [3.0, 2.5, 1.0]), "inout"),
    (0.50, tuple(BEAM + [3.1, 2.8, 1.1]), "inout"),
    (0.90, tuple(BEAM + [3.0, 2.5, 1.0]), "inout"),
], (0.7, 0.2, 0.6))
clip.head_w = (0.45, 0.6)
clip.hand_props = [{"hand": "mainhand", "item": "minecraft:mace", "from": 0.0, "to": LENGTH}]
clip.run(contacts=[("strike", STRIKE, "right", BEAM + [-2.5, 2.0, 1.0]),
                   ("brace", 0.2, "left", BEAM + [3.0, 2.5, 1.0])],
         keep=(0.30, STRIKE),
         strike=("right", [STRIKE]),
         meta={"contract": "BUILD_HAMMER 1.00 s loop (20 ticks); strike at tick 9 (t=0.45 s)"})
