"""FLETCHER_FLETCH (fletcher), authored in Blender.

    blender -b --factory-startup --python author_fletcher_fletch.py -- [--fast|--full] [--no-export]

Contract kept: 1.60 s loop (32 ticks). FEATHER_PINCH at clip tick 15
(Employment.soundContactOf(WORK_FLETCH) = 15): the middle of three pinches
lands at t = 0.75 s. The other two pinches sit at 0.35 s and 1.15 s.

The left fist holds the arrow shaft level in front of the chest and turns
it a third between feathers (a small roll of the hand plus a shift). The
right hand dips to the feather pile on the bench, comes in, and pinches the
vane on: a careful approach that slows into the pinch, a two-tick press,
release. After the third feather the shaft comes up to the eye and is
sighted along, then lowered to the work position again. Torso nearly still.
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402

LENGTH = 1.60
PINCHES = (0.35, 0.75, 1.15)        # middle one = tick 15

V = ck.variant()
clip = ck.Clip("FLETCHER_FLETCH" + ("__V2" if V == "v2" else ""), LENGTH, True, "settler_fletcher.png",
               cycles=1 if V == "v2" else 1,
               feet=((-2.9, 24.0, 0.9), (2.9, 24.0, -0.9)))
clip.prop("bench", (-8, 10, -18), (16, 14, 8), (0.55, 0.42, 0.26, 1))
clip.prop("feathers", (-7, 9.2, -13), (4, 0.8, 3), (0.92, 0.92, 0.90, 1))

SHAFT = np.array([2.2, 4.6, -8.2])          # left fist on the shaft
P = [np.array([-0.4, 4.2, -8.6]), np.array([-0.2, 3.7, -9.0]), np.array([-0.4, 4.7, -9.0])]
PILE = np.array([-4.2, 8.3, -10.8])

K = {
    "root_y": [(0.0, -0.3)], "root_x": [(0.0, 0.0), (0.8, 0.12, "inout")], "root_z": [(0.0, 0.0)],
    "root_yaw": [(0.0, 0.0)],
    "torso_x": [(0.0, 9.0), (0.20, 11.0, "inout"), (0.35, 9.5, "inout"), (0.60, 11.0, "inout"),
                (0.75, 9.5, "inout"), (1.00, 11.0, "inout"), (1.15, 9.5, "inout"), (1.40, 3.0, "inout")],
    "torso_y": [(0.0, 0.0), (0.20, 4.0, "inout"), (0.35, 0.5, "inout"), (0.60, 4.0, "inout"),
                (0.75, 0.5, "inout"), (1.00, 4.0, "inout"), (1.15, 0.5, "inout"), (1.40, -2.0, "inout")],
    "torso_z": [(0.0, 0.0)],
    "head_nod": [(0.0, 0.0)], "head_yaw": [(0.0, 0.0)],
    "head_roll": [(0.0, 0.0), (1.30, 0.0, "inout"), (1.42, -6.0, "inout"), (1.55, 0.0, "inout")],
    "cloak_add": [(0.0, 0.0)],
}
clip.keys(K)
# shaft hand: small turn between feathers, then up to the eye to sight along it
clip.key_vec("lh", [(0.0, tuple(SHAFT), "inout"), (0.42, tuple(SHAFT), "inout"),
                    (0.55, tuple(SHAFT + [0.2, -0.3, -0.1]), "inout"), (0.82, tuple(SHAFT + [0.2, -0.3, -0.1]), "inout"),
                    (0.95, tuple(SHAFT + [0.0, 0.3, -0.2]), "inout"), (1.22, tuple(SHAFT + [0.0, 0.3, -0.2]), "inout"),
                    (1.40, (1.6, -1.2, -7.6), "inout"), (1.48, (1.6, -1.3, -7.7), "inout")])
# feather hand: pile -> pinch (slowing in) -> press -> release, three times; rest while sighting
rh = []
for i, (tp, pp) in enumerate(zip(PINCHES, P)):
    rh += [(tp - 0.22, tuple(PILE + [0.3 * i, 0, 0]), "inout"),     # pick a feather
           (tp - 0.08, tuple(pp + [-1.2, 0.8, 0.9]), "out"),          # bring it in, slowing
           (tp, tuple(pp), "hold"),                                   # PINCH
           (tp + 0.07, tuple(pp), "inout"),                           # two-tick press
           ]
rh += [(1.30, (-3.2, 6.8, -7.0), "inout"), (1.52, (-3.6, 7.4, -7.4), "inout")]
rh = [(0.0, (-3.5, 7.2, -8.0), "inout")] + rh
clip.key_vec("rh", rh)
clip.key_vec("look", [(0.0, (0.8, 4.8, -8.8)), (1.20, (0.8, 4.8, -8.8), "inout"),
                      (1.40, (-6.0, 0.0, -14.0), "inout"), (1.52, (-6.0, 0.0, -14.0), "inout")])
clip.arm_live("right", lambda t, w: clip.cv("rh", t), (-0.75, 0.45, 0.5))
clip.arm_live("left", lambda t, w: clip.cv("lh", t), (0.8, 0.45, 0.4))
if V == "v2":
    # __v2 (one cycle): instead of sighting the shaft, the fletcher keeps it at
    # the chest and tests the fresh vanes with two quick flicks of the right
    # fingers, head cocked to listen/see them spring back. Pinches unchanged.
    d_l = SHAFT + np.array([0.0, 0.1, -0.4]) - np.array([1.6, -1.2, -7.6])
    clip.vary({
        "lh_x": [(0.0, 0.0), (1.22, 0.0, "inout"), (1.40, d_l[0], "inout"), (1.48, d_l[0], "inout"), (1.6, 0.0)],
        "lh_y": [(0.0, 0.0), (1.22, 0.0, "inout"), (1.40, d_l[1], "inout"), (1.48, d_l[1], "inout"), (1.6, 0.0)],
        "lh_z": [(0.0, 0.0), (1.22, 0.0, "inout"), (1.40, d_l[2], "inout"), (1.48, d_l[2], "inout"), (1.6, 0.0)],
        # right hand (base: resting ~(-3.4, 7.1, -7.2) here): to the vanes, flick, flick
        "rh_x": [(0.0, 0.0), (1.22, 0.0, "inout"), (1.32, 2.6, "out"), (1.37, 0.9, "inout"),
                 (1.42, 2.6, "out"), (1.46, 1.2, "inout"), (1.6, 0.0)],
        "rh_y": [(0.0, 0.0), (1.22, 0.0, "inout"), (1.32, -2.9, "out"), (1.37, -3.8, "inout"),
                 (1.42, -2.9, "out"), (1.46, -3.6, "inout"), (1.6, 0.0)],
        "rh_z": [(0.0, 0.0), (1.22, 0.0, "inout"), (1.32, -1.4, "out"), (1.37, -0.8, "inout"),
                 (1.42, -1.4, "out"), (1.46, -0.9, "inout"), (1.6, 0.0)],
        "look_x": [(0.0, 0.0), (1.20, 0.0, "inout"), (1.40, 6.4, "inout"), (1.52, 6.4, "inout"), (1.6, 0.0)],
        "look_y": [(0.0, 0.0), (1.20, 0.0, "inout"), (1.40, 4.6, "inout"), (1.52, 4.6, "inout"), (1.6, 0.0)],
        "look_z": [(0.0, 0.0), (1.20, 0.0, "inout"), (1.40, 5.4, "inout"), (1.52, 5.4, "inout"), (1.6, 0.0)],
        "head_roll": [(0.0, 0.0), (1.30, 0.0, "inout"), (1.42, 10.0, "inout"), (1.55, 0.0)],
        "torso_x": [(0.0, 0.0), (1.20, 0.0, "inout"), (1.40, 6.0, "inout"), (1.6, 0.0)],
    })
clip.head_w = (0.45, 0.55)
clip.run(contacts=[(f"pinch{i + 1}", tp, "right", pp) for i, (tp, pp) in enumerate(zip(PINCHES, P))]
         + [("shaft_held", PINCHES[1], "left", SHAFT + np.array([0.2, -0.3, -0.1]))],
         keep=tuple(tp + 0.07 for tp in PINCHES),
         meta={"variant": V or "base", "contract": "FLETCHER_FLETCH 1.60 s loop; FEATHER_PINCH at t=0.75 s = tick 15 of 32 "
                           "(middle of three pinches at 0.35/0.75/1.15 s)"})
