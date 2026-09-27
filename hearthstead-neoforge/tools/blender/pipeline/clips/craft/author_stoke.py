"""STOKE (smelter; brewer), authored in Blender.

    blender -b --factory-startup --python author_stoke.py -- [--fast|--full] [--no-export]

Contract kept: 1.40 s loop (28 ticks). BELLOWS_PUFF at clip tick 7
(Employment.soundContactOf(WORK_STOKE) = 7): the push begins at t = 0.35 s
and the bellows reach full compression at t = 0.60 s, where the puff peaks.

Working a floor bellows: both fists on the handle bar (live IK, the bar is
the one thing both hands share). Anticipation: the shoulders rise and the
hips lift a touch; then a slow, resisted two-handed push down, the body
weight going through straight-ish arms, knees giving at the end; the arms
stay locked on the compressed bellows while the fire answers (0.60-0.78);
then the recoil: the handle rides back up and the torso pulls back AND
away, head turned off the heat, before settling in for the next stroke.
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402

LENGTH = 1.40
PUSH0, FULL, HOLD1 = 0.35, 0.60, 0.78

clip = ck.Clip("STOKE", LENGTH, True, "settler_smelter.png",
               feet=((-3.0, 24.0, 1.6), (3.0, 24.0, -1.0)))
clip.prop("bellows", (-5, 14, -15), (10, 10, 7), (0.40, 0.28, 0.18, 1))
clip.prop("forge", (-8, 6, -24), (16, 18, 8), (0.35, 0.30, 0.30, 1))

OPEN = np.array([0.0, 7.4, -9.6])        # handle bar centre, bellows open
SHUT = np.array([0.0, 11.4, -9.9])       # fully compressed
GRIP = np.array([2.5, 0.0, 0.0])

K = {
    "s": [(0.0, 0.0, "inout"), (0.20, -0.08, "inout"), (PUSH0, 0.0, "inout"), (FULL, 1.0, "hold"),
          (HOLD1, 1.0, "out"), (1.02, 0.02, "inout"), (1.25, 0.0, "smooth")],
    "root_y": [(0.0, -0.4), (0.24, -0.15, "inout"), (PUSH0, -0.2, "inout"), (0.58, -1.3, "out"),
               (0.76, -1.25, "inout"), (0.98, -0.3, "inout"), (1.25, -0.45, "smooth")],
    "root_z": [(0.0, 0.0), (0.58, -0.4, "out"), (0.78, -0.35, "inout"), (0.98, 1.0, "out3"),
               (1.30, 0.1, "smooth")],
    "root_x": [(0.0, 0.0)],
    "root_yaw": [(0.0, 0.0), (0.80, 0.0, "inout"), (1.00, -5.0, "inout"), (1.30, -0.5, "smooth")],
    "torso_x": [(0.0, 16.0), (0.22, 12.5, "inout"), (PUSH0, 13.0, "inout"), (0.58, 29.0, "out"),
                (HOLD1, 28.0, "inout"), (0.98, 3.0, "out3"), (1.28, 15.0, "smooth")],
    "torso_y": [(0.0, 0.0), (0.80, 0.0, "inout"), (1.00, -7.0, "inout"), (1.30, -0.5, "smooth")],
    "torso_z": [(0.0, 0.0), (0.80, 0.0, "inout"), (1.00, 2.5, "inout"), (1.30, 0.0, "smooth")],
    "head_nod": [(0.0, 2.0), (0.60, 6.0, "out"), (0.80, 5.0, "inout"), (0.98, -8.0, "inout"),
                 (1.28, 1.5, "smooth")],
    # turn the face off the heat as the fire answers
    "head_yaw": [(0.0, 0.0), (0.78, 0.0, "inout"), (0.96, -22.0, "inout"), (1.15, -14.0, "inout"),
                 (1.34, 0.0, "smooth")],
    "head_roll": [(0.0, 0.0)], "cloak_add": [(0.0, 0.0)],
}
clip.keys(K)
clip.key_vec("look", [(0.0, (0.0, 12.0, -14.0))])


def bar(t):
    return OPEN + (SHUT - OPEN) * ck.c("s", t)


clip.arm_live("right", lambda t, w: bar(t) - GRIP, (-0.8, 0.2, 0.6))
clip.arm_live("left", lambda t, w: bar(t) + GRIP, (0.8, 0.2, 0.6))
clip.head_w = (0.3, 0.5)
clip.run(contacts=[("push_onset_r", PUSH0, "right", OPEN - GRIP), ("full_compression_r", FULL, "right", SHUT - GRIP),
                   ("full_compression_l", FULL, "left", SHUT + GRIP)],
         keep=(HOLD1,),
         meta={"contract": "STOKE 1.40 s loop; BELLOWS_PUFF onset t=0.35 s = tick 7 of 28, full "
                           "compression t=0.60 s, held to 0.78 s"})
