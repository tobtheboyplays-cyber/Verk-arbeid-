"""CLEAVE (butcher; hunter butchering; herder cull), authored in Blender.

    blender -b --factory-startup --python author_cleave.py -- [--fast|--full] [--no-export]

Contract kept: 0.85 s loop (17 ticks). CLEAVER_CHOP at clip tick 9
(Employment.soundContactOf(WORK_CLEAVE) = 9, HunterButchery CLEAVE_CONTACT);
the blade meets the board at t = 0.45 s and stays parked three ticks
(0.45-0.60). HerderWorkGoal counts the same beat as its tick 8 on a
0-based counter.

A light blade at a close target: shorter travel than the smith's hammer,
wind-up only to the ear, a sharp accelerating drop that is fastest at the
board, three ticks parked in the cut, then the cleaver is worked free. The
off hand pins the meat for the whole loop and flinches with each blow.
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402
import propkit  # noqa: E402

LENGTH = 0.85
CONTACT, PARK_END = 0.45, 0.60
TOP, HANG = 0.27, 0.33

V = ck.variant()
CLEAVER_PNG = os.path.join(ck.REPO, "src", "main", "resources", "assets", "hearthstead", "textures", "item",
                           "prop_cleaver.png")
clip = ck.Clip("CLEAVE" + ("__" + V.upper() if V else ""), LENGTH, True, "settler_butcher.png",
               cycles={"": 1, "v2": 2, "v3": 3}[V],
               feet=((-3.3, 24.0, 1.4), (2.9, 24.0, -1.4)), tool_png=CLEAVER_PNG)
# anim lane 2026-09-26: the butcher finally holds a cleaver (display prop, never an inventory item)
clip.hand_props = [{"hand": "mainhand", "item": "hearthstead:prop_cleaver", "from": 0.0, "to": LENGTH * {"": 1, "v2": 2, "v3": 3}[V],
                    "hide_real": True}]
clip.prop("block", (-7, 9, -19), (14, 15, 11), (0.55, 0.40, 0.25, 1))
clip.prop("meat", (-3.5, 8, -13), (7, 1.2, 4), (0.72, 0.30, 0.28, 1))

HIT = np.array([-1.8, 7.6, -11.4])
PIN = np.array([2.6, 7.2, -10.6])

K = {
    "root_y": [(0.0, -0.3), (0.25, -0.1, "inout"), (0.33, -0.12, "accel"), (0.44, -0.85, "out"),
               (0.60, -0.8, "inout"), (0.72, -0.45, "smooth")],
    "root_x": [(0.0, 0.0), (0.26, -0.35, "inout"), (0.44, 0.35, "out"), (0.70, 0.1, "smooth")],
    "root_z": [(0.0, 0.0), (0.26, 0.2, "inout"), (0.44, -0.3, "out"), (0.72, -0.05, "smooth")],
    "root_yaw": [(0.0, 0.5), (0.24, 4.5, "inout"), (0.31, 3.5, "accel"), (0.43, -2.5, "out"),
                 (0.62, -2.0, "smooth")],
    "torso_x": [(0.0, 14.0), (0.26, 7.0, "inout"), (0.33, 7.5, "accel"), (0.44, 21.0, "out"),
                (0.60, 20.0, "inout"), (0.72, 16.0, "smooth")],
    "torso_y": [(0.0, 0.0), (0.27, 8.0, "inout"), (0.33, 7.5, "accel"), (0.44, -4.0, "out"),
                (0.62, -3.5, "smooth")],
    "torso_z": [(0.0, 0.0), (0.27, 2.5, "inout"), (0.33, 2.5, "accel"), (0.44, -2.0, "out"),
                (0.65, -1.0, "smooth")],
    "head_nod": [(0.0, 2.0), (0.28, 0.0, "inout"), (0.46, 4.0, "out"), (0.70, 2.5, "smooth")],
    "head_yaw": [(0.0, 0.0)], "head_roll": [(0.0, 0.0)],
    "cloak_add": [(0.0, 0.0)],
    "lh_dy": [(0.0, 0.0), (0.45, 0.0, "linear"), (0.47, 0.35, "decel"), (0.58, 0.0, "smooth")],
}
# anim lane: a straighter back at rest (the fist was brushing the chin)
K["torso_x"] = [(k[0], k[1] - 4.0, *k[2:]) for k in K["torso_x"]]
clip.keys(K)
clip.key_vec("look", [(0.0, (0.0, 8.0, -13.5))])
# anim lane 2026-09-26: solved on the CLEAVER (blade centre + haft direction + fist, wrist bone
# free) so the blade drops edge-first into the meat instead of the fist pantomiming a chop
BLADE = (8.5, 11.0, 8.0)                    # cleaver blade centre, item-sprite px
KNOB, NECK = (6.5, 1.5, 8.0), (10.5, 8.5, 8.0)
CUT = np.array([-1.2, 6.2, -12.4])           # blade centre sunk in the meat at the contact
LOG = clip.arm_tool_poses("right", [
    (0.00, CUT + [0.3, -1.8, -0.8], (0.10, 0.55, -0.83), "inout", None),      # freed, hovering
    (0.10, CUT + [-1.6, -5.4, 1.2], (0.0, -0.1, -0.99), "smooth", None),     # lifts off
    (TOP, (-6.6, -8.4, -4.6), (-0.10, -0.95, -0.28), "inout", (-6.4, -3.2, -3.6)),        # to the ear
    (HANG, (-6.7, -8.9, -4.1), (-0.10, -0.95, -0.22), "accel", (-6.6, -3.5, -3.2)),       # hang, then drop
    (0.40, (-4.8, -2.6, -12.0), (-0.05, -0.35, -0.94), "linear", None),     # driving down
    (CONTACT, CUT, (0.05, 0.72, -0.69), "hold", None),                        # CONTACT, tick 9
    (PARK_END, CUT + [0.0, 0.1, 0.0], (0.05, 0.72, -0.69), "out", None),     # parked in the cut
    (0.68, CUT + [0.2, -1.2, -0.9], (0.08, 0.6, -0.8), "inout", None),       # worked free
], BLADE, knob=KNOB, neck=NECK, wrist=True, avoid_head=1.0, wrist_lim=(95.0, 30.0, 35.0))
print("GOALS", [(g["t"], g["cost"]) for g in LOG])
if V == "v2":
    # __v2 (2 cycles): the second blow is the heavier one, the whole back in it; after it the off
    # hand turns the joint over for the next cut
    clip.vary({
        "torso_x": [(0.0, 0.0), (0.85 + 0.20, 0.0, "inout"), (0.85 + 0.44, 5.0, "out"), (0.85 + 0.70, 0.0, "inout")],
        "root_y": [(0.0, 0.0), (0.85 + 0.30, 0.0, "inout"), (0.85 + 0.44, -0.5, "out"), (0.85 + 0.70, 0.0, "inout")],
        "lh_dz": [(0.0, 0.0), (0.85 + 0.60, 0.0, "inout"), (0.85 + 0.70, 1.2, "inout"), (0.85 + 0.84, 0.0, "inout")],
        "lh_dy": [(0.0, 0.0), (0.85 + 0.60, 0.0, "inout"), (0.85 + 0.70, -1.4, "inout"), (0.85 + 0.84, 0.0, "inout")],
    })
if V == "v3":
    # __v3 = the butcher's BREAK: after the first blow he wipes the off hand down his apron (front
    # of the thigh), flicks it, rolls the shoulder, and pins the meat again before the third blow;
    # the second blow (1.30 s) is a light tap into the same cut.
    clip.vary({
        "lh_dy": [(0.0, 0.0), (0.62, 0.0, "inout"), (0.80, 3.4, "inout"), (1.02, 7.0, "inout"), (1.12, 5.6, "inout"),
                  (1.20, 6.4, "inout"), (1.46, 0.0, "inout"), (2.55, 0.0)],
        "lh_dz": [(0.0, 0.0), (0.62, 0.0, "inout"), (0.80, 5.2, "inout"), (1.02, 7.4, "inout"), (1.20, 7.0, "inout"),
                  (1.46, 0.0, "inout"), (2.55, 0.0)],
        "torso_x": [(0.0, 0.0), (0.62, 0.0, "inout"), (0.95, -8.0, "inout"), (1.20, -6.0, "inout"),
                    (1.46, 0.0, "inout"), (2.55, 0.0)],
        "torso_z": [(0.0, 0.0), (0.95, 0.0, "inout"), (1.10, -4.0, "inout"), (1.30, 2.0, "inout"), (1.50, 0.0, "inout"),
                    (2.55, 0.0)],
        "head_nod": [(0.0, 0.0), (0.62, 0.0, "inout"), (0.90, 10.0, "inout"), (1.12, -6.0, "inout"),
                     (1.46, 0.0, "inout"), (2.55, 0.0)],
        "head_roll": [(0.0, 0.0), (1.05, 0.0, "inout"), (1.20, 8.0, "inout"), (1.40, 0.0, "inout"), (2.55, 0.0)],
    })


def pin(t, w):
    return PIN + np.array([ck.c("lh_dx", t), ck.c("lh_dy", t), ck.c("lh_dz", t)])


clip.arm_live("left", pin, (0.75, 0.45, 0.55))
clip.head_w = (0.3, 0.5)
propkit.recolour_sprite()
clip.run(contacts=[("blade_in_meat", CONTACT, "right_tool", CUT), ("meat_pinned", CONTACT, "left", PIN)],
         keep=(TOP, HANG, PARK_END), strike=("right_tool", CONTACT),
         surfaces=[("block", -7, 7, -19, -8, 8.0)],
         meta={"contract": "CLEAVE 0.85 s loop; CLEAVER_CHOP contact t=0.45 s = tick 9 of 17, "
                           "parked 0.45-0.60 s",
               "variant": V or "base",
               "held_item": "display prop hearthstead:prop_cleaver, solved on its blade (wrist bone)"})
