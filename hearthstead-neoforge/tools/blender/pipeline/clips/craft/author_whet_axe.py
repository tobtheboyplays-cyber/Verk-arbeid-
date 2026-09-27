"""WHET_AXE (RING-1 lane, Sharpened Axes): the Lumberer whets his axe at the
Lumber Camp grindstone. New clip.

    blender -b --factory-startup --python author_whet_axe.py -- [--fast|--full] [--no-export]

Contract: animation.settler.whet_axe, 3.50 s (70 ticks = LumberWhetting.WHET_TICKS),
looping. The edge meets the wheel at 0.60 / 1.30 / 1.90 / 2.50 s (ticks 12 / 26 /
38 / 50): LumbererWhetGoal throws the sparks and the grindstone rasp on exactly
those ticks. Deliberately uneven, a man working, not a metronome.

Beats: he steps his weight in and leans over the wheel (0-0.45), the haft in the
right fist and the left palm flat on the cheek of the axe head pressing the edge
down. Each stroke: a small lift, the edge set on the far side of the wheel, then
drawn toward him along the stone with the shoulders pushing (the sparks), then
eased off. After the fourth stroke he straightens, raises the axe to eye height
and sights along the edge with his head tipped (2.85-3.22), then settles back.
The same clip plays on the spot when a camp has no grindstone (a whetstone
stroke, no sparks).
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402
import mcrig  # noqa: E402

LENGTH = 3.5
STROKES = (0.60, 1.30, 1.90, 2.50)
AXE_PNG = os.path.join(ck.WORK, "ref", "assets", "minecraft", "textures", "item", "iron_axe.png")

clip = ck.Clip("WHET_AXE", LENGTH, True, "settler_lumberer.png",
               feet=((-3.0, 24.0, 1.4), (3.2, 24.0, -1.9)), knee_out=0.18,
               tool_png=AXE_PNG if os.path.exists(AXE_PNG) else None)
clip.cam_side = ((-3.4, -1.9, 1.35), (0.0, -0.55, 0.75), 38)

# Grindstone one block ahead (block z -24..-8): two stone legs and a 12 px wheel
# (rim y 8..20, z -22..-10) on its axle, the working face toward the settler.
STONE = (0.56, 0.55, 0.53, 1)
WOOD = (0.45, 0.32, 0.19, 1)
clip.prop("leg_r", (-7, 12, -20), (3, 12, 8), WOOD)
clip.prop("leg_l", (4, 12, -20), (3, 12, 8), WOOD)
clip.prop("wheel_a", (-3, 10, -20), (6, 8, 8), STONE)
clip.prop("wheel_b", (-3, 8, -18), (6, 12, 4), STONE)
clip.prop("wheel_c", (-3, 12, -22), (6, 4, 12), STONE)

ON0 = np.array([1.5, 9.3, -13.4])       # edge set on the wheel (far side of the stroke)
ON1 = np.array([-1.3, 10.6, -11.4])     # end of the draw toward him
UP = np.array([0.0, -0.9, 0.3])         # lifted off the stone between strokes
HAFT_ON = (0.20, 0.42, -0.88)           # knob -> neck while whetting: forward and down
SIGHT = np.array([-0.8, -3.8, -9.6])    # edge held up at eye height
HAFT_SIGHT = (0.10, -0.95, -0.30)

edge = [(0.00, tuple(ON0 + UP * 2.2 + [0.0, -0.5, 1.0]), "inout"),
        (0.34, tuple(ON0 + UP * 1.4), "inout")]
body = {"torso_x": [(0.0, 8.0), (0.45, 20.0, "inout")],
        "torso_y": [(0.0, 0.0), (0.45, 3.0, "inout")],
        "root_z": [(0.0, 0.4), (0.45, -0.3, "inout")],
        "root_y": [(0.0, 0.0), (0.45, -0.8, "inout")]}
for i, c in enumerate(STROKES):
    press = 1.0 + 0.15 * ((i * 7) % 3 - 1)          # one stroke leans harder than another
    last = i == len(STROKES) - 1
    edge += [(c - 0.13, tuple(ON0 + UP), "accel"),
             (c, tuple(ON0), "linear"),                                 # CONTACT: sparks
             (c + 0.22, tuple(ON1), "out"),
             (c + 0.33, tuple(ON1 + UP * 0.8), "inout")]
    body["torso_x"] += [(c, 20.0 + 1.0 * press, "linear"), (c + 0.2, 23.0 * press, "out")]
    body["torso_y"] += [(c, 3.0, "linear"), (c + 0.22, -2.0 * press, "out")]
    body["root_z"] += [(c, -0.3, "linear"), (c + 0.22, -1.0 * press, "out")]
    body["root_y"] += [(c, -0.8, "linear"), (c + 0.22, -1.2, "out")]
    if not last:
        body["torso_x"] += [(c + 0.4, 20.5, "inout")]
        body["torso_y"] += [(c + 0.45, 2.0, "inout")]
        body["root_z"] += [(c + 0.45, -0.4, "inout")]
        body["root_y"] += [(c + 0.45, -0.8, "inout")]
edge += [(3.05, tuple(SIGHT), "inout"),
         (3.22, tuple(SIGHT + [0.4, 0.2, 0.0]), "inout")]      # tilt it to the light
body["torso_x"] += [(2.85, 16.0, "inout"), (3.05, 5.0, "inout"), (3.22, 5.5, "inout")]
body["torso_y"] += [(3.05, -5.0, "inout"), (3.22, -4.0, "inout")]
body["root_z"] += [(3.05, 0.5, "inout")]
body["root_y"] += [(3.05, 0.0, "inout")]

K = {k: sorted(v, key=lambda x: x[0]) for k, v in body.items()}
K.update({
    "root_x": [(0.0, 0.0), (1.4, 0.3, "inout"), (2.4, -0.2, "inout")],
    "root_yaw": [(0.0, 0.0)], "torso_z": [(0.0, 0.0)],
    "head_nod": [(0.0, 6.0), (0.45, 10.0, "inout"), (2.80, 10.0, "inout"), (3.05, -6.0, "inout"),
                 (3.22, -4.0, "inout")],
    "head_yaw": [(0.0, 0.0), (2.95, 0.0, "inout"), (3.08, 8.0, "inout"), (3.24, 6.0, "inout")],
    "head_roll": [(0.0, 0.0), (2.95, 0.0, "inout"), (3.10, 7.0, "inout"), (3.34, 0.0, "inout")],
    "cloak_add": [(0.0, 0.0)],
})
clip.keys(K)
clip.key_vec("edge", edge)
clip.key_vec("look", [(0.0, (0.0, 10.0, -13.0)), (0.45, (0.0, 10.0, -12.5), "inout"),
                      (2.8, (0.0, 10.0, -12.5), "inout"), (3.05, tuple(SIGHT), "inout"),
                      (3.25, tuple(SIGHT), "inout")])

goals = []
for t, p, e in edge:
    hd = HAFT_SIGHT if t >= 3.0 else HAFT_ON
    goals.append((t, p, hd, e))
# Seeded on the forward-reaching branch so the loop never flips the shoulder.
clip.arm_tool_poses("right", goals, mcrig.AXE_EDGE, seed=(-20.0, -35.0, 0.0, -60.0, 0.0),
                    avoid_head=0.6)

LEFT_ON_HEAD = np.array([2.6, -1.8, 2.2])      # left palm flat on the axe head, pressing


def left(t, world):
    return clip.cv("edge", t) + LEFT_ON_HEAD


clip.arm_live("left", left, (0.75, 0.4, 0.55))
clip.head_w = (0.4, 0.5)
clip.run(contacts=[(f"stroke{i + 1}", c, "right_tool", ON0) for i, c in enumerate(STROKES)],
         keep=tuple(STROKES) + (0.45, 3.05),
         strike=("right_tool", list(STROKES)),
         meta={"contract": "WHET_AXE 3.50 s loop (70 ticks); edge on the wheel at ticks 12/26/38/50 "
                           "(LumbererWhetGoal sparks + grindstone rasp)",
               "held_item": "the Lumberer's real axe (MAINHAND); left hand empty"})
