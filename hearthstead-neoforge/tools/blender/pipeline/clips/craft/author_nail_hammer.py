"""NAIL_HAMMER ("spikking"): nailing a board over wooden raid damage. New clip.

    blender -b --factory-startup --python author_nail_hammer.py -- [--fast|--full] [--no-export]

Contract (owner request via the coordinator; a code agent wires activity + sound later):
  animation.settler.nail_hammer, 2.00 s (40 ticks), looping.
  Hammer taps land EXACTLY at ticks 8, 16, 24 (t = 0.40, 0.80, 1.20 s).
  Ticks 28-40 (1.40-2.00 s): the off hand reaches to the hip pouch for the
  next nail and brings it back to the board (placed at ~0.12 s of the next loop).
  Main hand holds a hammer (preview uses a stand-in hammer sprite through the
  vanilla held-item transform); the off hand pins the nail for the first tap,
  then lets go and steadies the board for taps two and three.

Precise and careful, not heavy: kneeling on the right knee close to the wall,
small wrist/elbow arcs (the head travels 3-4 px), each tap a short
anticipation, a quick accelerating drop that is fastest at the nail, a tiny
rebound, and the third tap a touch fuller to set the nail flush.
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402
import mcrig  # noqa: E402

LENGTH = 2.0
TAPS = (0.40, 0.80, 1.20)          # ticks 8, 16, 24
POUCH_START, POUCH_END = 1.40, 2.00  # ticks 28-40

HAMMER_PNG = os.path.join(ck.WORK, "out", "craft", "hammer_sprite.png")
FACE = (13.5, 8.5, 8.0)            # hammer striking face, item-sprite px (lower head end)

# The held hammer's face sits ~10.8 px from the palm, so a kneeling settler
# works with the body sat back from the wall (root z +3.5) and the fist at the
# right hip, the haft reaching forward-left to the nail.
clip = ck.Clip("NAIL_HAMMER", LENGTH, True, "settler_carpenter.png",
               feet=((-2.8, 23.3, 8.6), (3.0, 24.0, -0.6)), knee_out=0.2,
               tool_png=HAMMER_PNG if os.path.exists(HAMMER_PNG) else None)
WOOD = (0.52, 0.38, 0.22, 1)
clip.prop("wall", (-8, 8, -24), (16, 16, 16), (0.42, 0.30, 0.18, 1))
clip.prop("board", (-6.5, 11.0, -8.0), (13, 3, 0.9), WOOD)

NX, NY = -0.5, 12.4                # nail on the board face (board front z = -7.1)
HEAD_Z = {0: -5.9, 1: -6.3, 2: -6.7, 3: -7.1}   # nail head proud of the board, tap by tap
REACH = 10.8                       # palm -> hammer face


def nail(n):
    return np.array([NX, NY, HEAD_Z[n]])


def hand_for(tip, d):
    d = np.asarray(d, float)
    return tuple(np.asarray(tip, float) - REACH * d / np.linalg.norm(d))


D_TAP = (0.55, -0.2, -0.81)        # haft direction at the nail
D_UP = (0.55, -0.62, -0.56)        # head cocked up for the next tap


K = {
    # kneeling on the right knee; tiny settle into each tap, a lean and a
    # shift toward the left hip while the off hand fetches a nail
    "root_y": [(0.0, -5.6), (0.30, -5.55, "accel"), (0.40, -5.72, "out"), (0.52, -5.6, "inout"),
               (0.70, -5.55, "accel"), (0.80, -5.72, "out"), (0.92, -5.6, "inout"),
               (1.08, -5.5, "accel"), (1.20, -5.78, "out"), (1.34, -5.6, "inout"),
               (1.62, -5.45, "inout"), (1.85, -5.6, "smooth")],
    "root_x": [(0.0, 0.0), (1.40, 0.0, "inout"), (1.62, 0.45, "inout"), (1.85, 0.1, "smooth")],
    "root_z": [(0.0, 3.5)],
    "root_yaw": [(0.0, 0.0), (1.40, 0.0, "inout"), (1.62, -4.0, "inout"), (1.90, -0.5, "smooth")],
    "torso_x": [(0.0, 15.0), (0.30, 14.0, "accel"), (0.40, 16.5, "out"), (0.52, 15.5, "inout"),
                (0.70, 14.2, "accel"), (0.80, 16.5, "out"), (0.92, 15.5, "inout"),
                (1.08, 13.8, "accel"), (1.20, 17.0, "out"), (1.34, 15.5, "inout"),
                (1.62, 10.0, "inout"), (1.80, 11.0, "smooth")],
    "torso_y": [(0.0, 0.0), (0.30, 2.0, "accel"), (0.40, -0.5, "out"), (0.60, 0.5, "inout"),
                (0.70, 2.0, "accel"), (0.80, -0.5, "out"), (1.00, 0.5, "inout"),
                (1.08, 3.0, "accel"), (1.20, -1.0, "out"), (1.40, 0.0, "inout"),
                (1.62, -9.0, "inout"), (1.80, -8.0, "smooth")],
    "torso_z": [(0.0, 0.0), (1.40, 0.0, "inout"), (1.62, -3.5, "inout"), (1.85, -1.0, "smooth")],
    "head_nod": [(0.0, 0.0), (1.20, 2.0, "out"), (1.40, 0.0, "inout")],
    "head_yaw": [(0.0, 0.0)],
    "head_roll": [(0.0, 0.0)],
    "cloak_add": [(0.0, 0.0)],
    # off hand: 0 = on the board path, 1 = at the hip pouch
    "lh_pouch": [(0.0, 0.0), (POUCH_START, 0.0, "inout"), (1.62, 1.0, "hold"), (1.78, 1.0, "inout"),
                 (POUCH_END, 0.0)],
    "lh_dig": [(0.0, 0.0), (1.62, 0.0, "inout"), (1.68, 0.9, "inout"), (1.74, 0.2, "inout"),
               (1.78, 0.0)],
}
clip.keys(K)
# eyes: on the nail, down to the pouch while fetching, back to the board
clip.key_vec("look", [(0.0, (NX, NY, -6.5)), (1.42, (NX, NY, -6.5), "inout"),
                      (1.60, (5.0, 16.0, 1.0), "inout"), (1.78, (4.0, 15.0, 0.0), "inout"),
                      (1.95, (NX + 1, NY, -6.5))])
# off hand on the board side: brings the nail in, pins it, lets go after tap 1
PIN = (1.35, NY + 0.2, -5.6)
clip.key_vec("lh", [(0.0, (2.4, 11.4, -3.8), "out"), (0.12, PIN, "hold"),
                    (0.42, PIN, "inout"), (0.50, (2.8, NY + 1.8, -6.4), "hold"),
                    (1.36, (2.8, NY + 1.8, -6.4), "inout"), (POUCH_START, (3.0, NY + 2.2, -5.6), "inout"),
                    (1.80, (3.2, 12.4, -2.0), "inout")])
POUCH_LOCAL = np.array([6.9, -3.2, -1.6])     # torso-local, left hip, front of the belt


def left_target(t, world):
    w = ck.smoothstep(ck.c("lh_pouch", t))
    pouch = mcrig.xform(world["torso"], POUCH_LOCAL + np.array([0.0, ck.c("lh_dig", t), 0.0]))
    return (1 - w) * clip.cv("lh", t) + w * pouch


clip.arm_live("left", left_target, (0.75, 0.35, 0.6))

# hammer arm: key poses from the hammer FACE on the nail head (IK on the held item)
UP = np.array([0.0, -2.4, 2.2])     # tap wind-up: face lifted up and back off the nail
DOWN = (0.2, 0.55, -0.81)           # haft pointing forward-down when lowered


def g(t, tip, d, e):
    return (t, hand_for(tip, d), tip, None, e)


LOG = clip.arm_goals_tool("right", [
    (0.00, (-6.8, 17.6, 3.0), None, DOWN, "inout"),
    g(0.14, nail(0) + np.array([0, 0, 0.3]), D_TAP, "inout"),          # sighting touch
    g(0.28, nail(0) + UP, D_UP, "inout"),                              # lift
    g(0.32, nail(0) + UP * 1.08, D_UP, "accel"),                       # hang
    g(TAPS[0], nail(1), D_TAP, "out"),                                 # TAP 1, tick 8
    g(0.45, nail(1) + UP * 0.25, D_TAP, "inout"),                      # rebound
    g(0.64, nail(1) + UP, D_UP, "inout"),
    g(0.71, nail(1) + UP * 1.08, D_UP, "accel"),
    g(TAPS[1], nail(2), D_TAP, "out"),                                 # TAP 2, tick 16
    g(0.85, nail(2) + UP * 0.25, D_TAP, "inout"),
    g(1.03, nail(2) + UP * 1.35, D_UP, "inout"),                       # fuller lift
    g(1.10, nail(2) + UP * 1.45, D_UP, "accel"),
    g(TAPS[2], nail(3), D_TAP, "out"),                                 # TAP 3, tick 24
    g(1.26, nail(3) + UP * 0.2, D_TAP, "inout"),                       # checks it is flush
    (1.42, (-6.2, 16.4, 1.8), None, (0.35, 0.2, -0.9), "inout"),
    (1.65, (-7.0, 17.8, 3.2), None, DOWN, "smooth"),
    (1.92, (-6.9, 17.7, 3.1), None, DOWN, "inout"),
], FACE, seed=[-30.0, 15.0, 5.0, -70.0, 0.0], w_tip=10.0)
print("GOALS", LOG)
clip.head_w = (0.35, 0.55)
# the runtime has no vanilla hammer: the mace is the stand-in (drawn only if the hand is empty)
clip.hand_props = [{"hand": "mainhand", "item": "minecraft:mace", "from": 0.0, "to": LENGTH}]
clip.cam_side = ((-4.3, 0.6, 1.0), (0.0, -0.25, 0.7), 40)   # true side: the wall hides a front view

clip.run(contacts=[("tap1", TAPS[0], "right_tool", nail(1)),
                   ("tap2", TAPS[1], "right_tool", nail(2)),
                   ("tap3", TAPS[2], "right_tool", nail(3)),
                   ("nail_pinched", 0.40, "left", np.array(PIN))],
         keep=(0.14, 0.32, 0.71, 1.10, POUCH_START, 1.62, 1.78),
         strike=("right_tool", list(TAPS)),
         meta={"contract": "NAIL_HAMMER 2.00 s loop (40 ticks); hammer taps at ticks 8/16/24 "
                           "(t=0.40/0.80/1.20 s); hip-pouch reach ticks 28-40",
               "held_item": "MAINHAND hammer via the vanilla handheld transform (preview uses a "
                            "stand-in sprite; face landmark item px (13.5, 8.5))"})
