"""SAW (sawyer / sawmill), authored in Blender.

    blender -b --factory-startup --python author_saw.py -- [--fast|--full] [--no-export]

Contract kept: 1.10 s loop (22 ticks). SAW_STROKE at clip tick 9
(Employment.soundContactOf(WORK_SAW) = 9): the reversal hold at the back of
the stroke ends at t = 0.45 s and the fast cutting push runs 0.45-0.80 s.

No impact, so the weight lives in the reversals: the saw hand parks at each
end of the stroke while the teeth bite and the body changes direction. The
push is the cut: it drives from the back foot, the hips go first, the torso
folds over the log and the hand runs straight along the kerf (live IK on a
straight line, descending a little as the cut deepens). The return pull is
lighter and slower, the hand riding a touch higher. The off hand braces the
log the whole time.
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402

LENGTH = 1.10
PUSH0, PUSH1 = 0.45, 0.80          # tick 9 = start of the cutting push
BACK_HOLD0, FRONT_HOLD1 = 0.35, 0.90

clip = ck.Clip("SAW", LENGTH, True, "settler_sawyer.png",
               feet=((-3.8, 24.0, 2.4), (2.8, 24.0, -2.2)))
clip.prop("trestle_a", (-7, 14, -7), (14, 10, 2), (0.45, 0.33, 0.20, 1))
clip.prop("trestle_b", (-7, 14, -17), (14, 10, 2), (0.45, 0.33, 0.20, 1))
clip.prop("log", (-1, 10, -20), (8, 4, 16), (0.60, 0.46, 0.30, 1))

BACK = np.array([-2.8, 8.4, -5.0])      # saw hand at the back of the stroke
FRONT = np.array([-2.5, 9.2, -12.2])    # end of the push, cut a little deeper
BRACE = np.array([3.3, 9.4, -10.0])

K = {
    # 0 -> 1 along the stroke (0 = back, 1 = front)
    "stroke": [(0.0, 0.42, "inout"), (BACK_HOLD0, 0.0, "linear"), (PUSH0, 0.0, "inout"),
               (PUSH1, 1.0, "linear"), (FRONT_HOLD1, 1.0, "inout")],
    "lift": [(0.0, -0.55, "inout"), (BACK_HOLD0, 0.0, "linear"), (PUSH1, 0.0, "linear"),
             (FRONT_HOLD1, 0.0, "inout")],
    "root_y": [(0.0, -0.5), (0.30, -0.35, "inout"), (0.46, -0.4, "inout"), (0.74, -0.95, "inout"),
               (0.92, -0.9, "inout")],
    "root_z": [(0.0, 0.2), (0.28, 0.55, "inout"), (0.44, 0.5, "inout"), (0.74, -0.55, "inout"),
               (0.90, -0.5, "inout")],
    "root_x": [(0.0, -0.1), (0.30, -0.35, "inout"), (0.44, -0.3, "inout"), (0.74, 0.3, "inout"),
               (0.90, 0.25, "inout")],
    "root_yaw": [(0.0, 3.0), (0.30, 6.0, "inout"), (0.42, 5.5, "inout"), (0.72, -2.0, "inout"),
                 (0.88, -1.5, "inout")],
    "torso_x": [(0.0, 15.0), (0.33, 11.0, "inout"), (0.47, 11.5, "inout"), (0.78, 24.0, "inout"),
                (0.92, 23.0, "inout")],
    "torso_y": [(0.0, 3.0), (0.33, 7.0, "inout"), (0.47, 6.5, "inout"), (0.78, -3.0, "inout"),
                (0.92, -2.5, "inout")],
    "torso_z": [(0.0, 0.0), (0.35, 1.5, "inout"), (0.78, -1.5, "inout"), (0.95, -1.0, "inout")],
    "head_nod": [(0.0, 2.0), (0.40, 0.0, "inout"), (0.80, 4.0, "inout")],
    "head_yaw": [(0.0, 0.0)], "head_roll": [(0.0, 0.0)], "cloak_add": [(0.0, 0.0)],
}
clip.keys(K)
clip.key_vec("look", [(0.0, (-1.0, 10.0, -10.0))])


def saw_hand(t, world):
    s = ck.c("stroke", t)
    return BACK + (FRONT - BACK) * s + np.array([0.0, ck.c("lift", t), 0.0])


clip.arm_live("right", saw_hand, (-0.55, 0.3, 0.8))
clip.arm_live("left", lambda t, w: BRACE, (0.75, 0.45, 0.5))
clip.head_w = (0.3, 0.5)
clip.run(contacts=[("stroke_back", PUSH0, "right", BACK), ("stroke_front", PUSH1, "right", FRONT),
                   ("brace", PUSH1, "left", BRACE)],
         keep=(BACK_HOLD0, FRONT_HOLD1),
         meta={"contract": "SAW 1.10 s loop; SAW_STROKE at t=0.45 s = tick 9 of 22 (end of the back "
                           "reversal hold); cutting push 0.45-0.80 s",
               "held_item": "none in game (empty MAINHAND); fist authored on a handsaw grip"})
