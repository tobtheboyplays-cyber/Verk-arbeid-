"""FINE_WORK (weaver; scholar reuses it), authored in Blender.

    blender -b --factory-startup --python author_fine_work.py -- [--fast|--full] [--no-export]

Contract kept: 0.90 s loop (18 ticks). LOOM_CLACK (and the scholar's quill
scratch) at clip tick 9 (Employment.soundContactOf(WORK_WEAVE) = 9): the
deeper second pass lands at t = 0.45 s.

Close, quick hand work -- deliberately the opposite of the heavy clips:
small amplitude, head down, torso almost still, weight quiet over both feet.
Two passes per loop so the hands look busy, not metronomic: a shallow first
pass (0.20 s) and a deeper second one (0.45 s), the off hand tensioning the
thread against each pass, then a small draw-up of the thread and reset.
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402

LENGTH = 0.90
PASS1, PASS2 = 0.20, 0.45

clip = ck.Clip("FINE_WORK", LENGTH, True, "settler_weaver.png",
               feet=((-2.9, 24.0, 0.8), (2.9, 24.0, -0.8)))
clip.prop("loom", (-7, 9, -16), (14, 15, 6), (0.60, 0.48, 0.30, 1))
clip.prop("cloth", (-5, 8.2, -15), (10, 0.8, 7), (0.74, 0.70, 0.62, 1))

DEEP = np.array([-0.4, 7.6, -10.4])
K = {
    "root_y": [(0.0, -0.3), (PASS2, -0.42, "inout")],
    "root_x": [(0.0, 0.0), (0.25, 0.08, "inout"), (0.60, -0.08, "inout")],
    "root_z": [(0.0, 0.0)], "root_yaw": [(0.0, 0.0)],
    "torso_x": [(0.0, 15.0), (PASS1, 15.8, "inout"), (0.30, 15.2, "inout"), (PASS2, 16.8, "out"),
                (0.66, 14.6, "inout")],
    "torso_y": [(0.0, 0.0), (PASS1, -1.2, "inout"), (0.32, 0.6, "inout"), (PASS2, -2.0, "out"),
                (0.70, 1.0, "inout")],
    "torso_z": [(0.0, 0.0)],
    "head_nod": [(0.0, 4.0), (PASS1, 5.0, "inout"), (PASS2, 6.0, "out"), (0.70, 3.5, "inout")],
    "head_yaw": [(0.0, 0.0)], "head_roll": [(0.0, 0.0), (0.45, 2.0, "inout"), (0.8, -1.0, "inout")],
    "cloak_add": [(0.0, 0.0)],
}
clip.keys(K)
clip.key_vec("look", [(0.0, (-0.5, 8.0, -11.0)), (PASS2, (0.3, 8.0, -11.5), "inout")])
# shuttle hand: shallow pass, back out, deeper pass, out and a little thread draw-up
clip.key_vec("rh", [(0.0, (-2.3, 6.6, -8.6), "inout"), (0.13, (-1.2, 7.1, -9.7), "out"),
                    (PASS1, (-0.6, 7.4, -10.1), "inout"), (0.29, (-2.0, 6.7, -8.8), "inout"),
                    (0.38, (-1.0, 7.2, -9.9), "out"), (PASS2, tuple(DEEP), "inout"),
                    (0.56, (-1.6, 6.4, -8.8), "inout"), (0.68, (-2.4, 5.2, -8.2), "inout"),
                    (0.80, (-2.5, 6.2, -8.4), "inout")])
# off hand tensions against each pass
clip.key_vec("lh", [(0.0, (2.6, 7.1, -9.2), "inout"), (PASS1, (2.9, 6.8, -8.8), "inout"),
                    (0.30, (2.5, 7.2, -9.4), "inout"), (PASS2, (3.1, 6.5, -8.4), "out"),
                    (0.62, (2.4, 7.3, -9.6), "inout")])
clip.arm_live("right", lambda t, w: clip.cv("rh", t), (-0.75, 0.4, 0.55))
clip.arm_live("left", lambda t, w: clip.cv("lh", t), (0.75, 0.4, 0.55))
clip.head_w = (0.35, 0.5)
clip.run(contacts=[("pass1", PASS1, "right", np.array([-0.6, 7.4, -10.1])), ("pass2_deep", PASS2, "right", DEEP)],
         surfaces=[("loom", -7, 7, -16, -9, 8.2)],
         meta={"contract": "FINE_WORK 0.90 s loop; LOOM_CLACK at t=0.45 s = tick 9 of 18 (deeper "
                           "second pass); shared by the scholar (quill, period 40)"})
