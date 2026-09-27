"""MILL_GRIND (miller), authored in Blender -- anim overkill 2026-09-26.

    HS_VARIANT=[|v2|v3] blender -b --factory-startup --python author_mill_grind.py -- [--fast|--full] [--no-export]

The miller's own motion (he used to play the baker's KNEAD): turning a hand QUERN. Kneeling-high
stance at a waist-height quern on a stump; the right hand drives the upper stone round by its
upright peg handle, the whole trunk rowing into each push; the left hand holds a grain scoop
(display prop hearthstead:prop_sack_scoop) and trickles grain into the eye of the stone.

Contract (unchanged WORK_KNEAD timing): 1.20 s loop = one turn of the stone; WORK_QUERN_GRIND at
clip tick 9 = 0.45 s, the peg at the far side where the push is heaviest (grinding).
Beats: 0.00 near side, weight back; 0.00-0.45 the push round the right and away (lean in, knees
give, a grunt of effort in the chest); 0.45 far side (contact); 0.45-1.20 pulled round the left and
back, lighter, the trunk comes up; the scoop tips a trickle in 0.80-1.05.
__v2 (2.40 s): the second turn is a hard one (stiff grain) and the scoop is refilled from the
sack at the hip.
__v3 (3.60 s) = the BREAK: after the first turn he lets go, pinches flour from the spout, rubs it
between finger and thumb at eye height, blows it off the fingers, nods; on the second contact
(1.65) he gives the stone a single hard shove to keep it turning; the third turn is normal.
"""
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402
import propkit  # noqa: E402

BASE = 1.20
CONTACT = 0.45
V = ck.variant()
N = {"": 1, "v2": 2, "v3": 3}[V]
LENGTH = BASE * N
PNG = os.path.join(ck.REPO, "src", "main", "resources", "assets", "hearthstead", "textures", "item",
                   "prop_sack_scoop.png")
clip = ck.Clip("MILL_GRIND" + ("__" + V.upper() if V else ""), LENGTH, True, "settler_miller.png",
               feet=((-3.0, 24.0, 1.8), (3.2, 24.0, -1.6)), tool_png=PNG)
clip.cam_side = ((-3.3, -2.2, 1.45), (0.0, -0.45, 0.8), 38)
STONE = (0.55, 0.54, 0.52, 1)
clip.prop("stump", (-5, 14, -14.5), (10, 10, 10), (0.42, 0.29, 0.17, 1))
clip.prop("nether_stone", (-5.5, 12, -15), (11, 2, 11), STONE)
clip.prop("runner_stone", (-5, 10, -14.5), (10, 2, 10), (0.62, 0.61, 0.58, 1))
clip.prop("peg_eye", (-1, 9.5, -10.5), (2, 0.5, 2), (0.3, 0.3, 0.3, 1))
clip.prop("flour", (-3, 13.6, -5.2), (6, 0.4, 1.5), (0.94, 0.92, 0.86, 1))
CENTRE = np.array([0.0, 9.8, -9.5])         # the eye of the upper stone
R = 3.4                                     # peg radius


def peg(theta_deg):
    a = math.radians(theta_deg)
    return CENTRE + np.array([R * math.cos(a), -2.4, -R * math.sin(a)])   # fist on top of the peg


# peg angle keys per turn: near side (-90 deg = towards the miller) -> right -> far (contact) -> left
TURN = [(0.00, -90.0, "inout"), (0.22, -180.0, "linear"), (CONTACT, -270.0, "decel"), (0.80, -360.0, "accel"),
        (BASE, -450.0, None)]
EYE = CENTRE + np.array([1.6, -3.0, 1.6])   # scoop tip over the eye
SCOOP_REST = np.array([5.8, 8.8, -6.2])
SACK = np.array([6.8, 13.0, -2.0])

K = {"root_x": [], "root_y": [], "root_z": [], "root_yaw": [(0.0, 0.0)], "torso_x": [], "torso_y": [],
     "torso_z": [(0.0, 0.0)], "head_nod": [], "head_yaw": [(0.0, 0.0)], "head_roll": [(0.0, 0.0)],
     "cloak_add": [(0.0, 0.0)]}
RH, LH, LOOK = [], [], []
turns = [i for i in range(N) if not (V == "v3" and i == 1)]
for i in turns:
    t0 = i * BASE
    hard = 1.25 if (V == "v2" and i == 1) else 1.0
    for t, th, e in TURN[:-1]:
        RH.append((t0 + t, peg(th), e))
    for u in (0.11, 0.33, 0.62, 1.0):          # in-betweens keep the fist on the circle
        th = np.interp(u, [k[0] for k in TURN], [k[1] for k in TURN])
        RH.append((t0 + u, peg(th), "inout"))
    K["torso_x"] += [(t0, 10.0, "inout"), (t0 + 0.06, 8.5, "inout"), (t0 + CONTACT, 10.0 + 5.0 * hard, "decel"),
                     (t0 + 0.8, 12.0, "inout"), (t0 + 1.05, 9.0, "inout")]
    K["torso_y"] += [(t0, 0.0, "inout"), (t0 + 0.22, 6.0 * hard, "inout"), (t0 + CONTACT, 2.0, "decel"),
                     (t0 + 0.80, -5.0, "inout")]
    K["root_z"] += [(t0, 0.5, "inout"), (t0 + CONTACT, -1.2 * hard, "decel"), (t0 + 0.9, 0.4, "inout")]
    K["root_y"] += [(t0, -0.8, "inout"), (t0 + CONTACT, -1.5 * hard, "decel"), (t0 + 0.9, -0.7, "inout")]
    K["root_x"] += [(t0, 0.0, "inout"), (t0 + 0.22, -0.5, "inout"), (t0 + 0.80, 0.4, "inout")]
    K["head_nod"] += [(t0, 4.0, "inout"), (t0 + CONTACT, 6.0, "decel"), (t0 + 0.9, 3.0, "inout")]
    trickle = not (V == "v2" and i == 1)
    if trickle:
        LH += [(t0, SCOOP_REST, "inout"), (t0 + 0.70, SCOOP_REST, "inout"), (t0 + 0.84, EYE, "inout"),
               (t0 + 0.98, EYE + np.array([-0.3, 0.3, 0.0]), "inout"), (t0 + 1.12, SCOOP_REST, "inout")]
    else:   # refill the scoop from the sack at the hip
        LH += [(t0, SCOOP_REST, "inout"), (t0 + 0.30, SACK, "inout"), (t0 + 0.44, SACK + np.array([0, 1.0, 0]), "inout"),
               (t0 + 0.60, SACK, "inout"), (t0 + 0.90, SCOOP_REST, "inout")]
    LOOK += [(t0, tuple(CENTRE + np.array([0, 0, 0])), "inout"), (t0 + 0.84, tuple(EYE), "inout")]
if V == "v3":
    b0 = BASE
    pinch = np.array([-1.0, 12.0, -5.8])       # at the spout
    eye_h = np.array([0.4, -1.0, -8.6])        # rubbing at eye height, well before the face
    RH += [(b0 + 0.00, peg(-90.0), "inout"), (b0 + 0.20, peg(-90.0) + np.array([0, -1.0, 0.6]), "inout"),
           (b0 + 0.36, pinch, "inout"), (b0 + 0.44, pinch + np.array([0, 0.3, 0]), "inout"),     # CONTACT: shove
           (b0 + 0.62, eye_h, "inout"), (b0 + 0.82, eye_h + np.array([0.4, 0.2, 0]), "inout"),
           (b0 + 0.92, eye_h + np.array([-0.2, 0.0, -0.6]), "inout"),                          # blow
           (b0 + 1.10, peg(-90.0) + np.array([0, -0.8, 0.4]), "inout")]
    LH += [(b0 + 0.00, SCOOP_REST, "inout"), (b0 + 0.62, SCOOP_REST + np.array([-1.0, -1.0, 0.0]), "inout"),
           (b0 + 1.10, SCOOP_REST, "inout")]
    K["torso_x"] += [(b0, 10.0, "inout"), (b0 + 0.40, 16.0, "inout"), (b0 + 0.70, 3.0, "inout"),
                     (b0 + 1.0, 4.0, "inout"), (b0 + 1.15, 10.0, "inout")]
    K["torso_y"] += [(b0, 0.0, "inout"), (b0 + 1.15, 0.0, "inout")]
    K["root_z"] += [(b0, 0.5, "inout"), (b0 + 1.15, 0.5, "inout")]
    K["root_y"] += [(b0, -0.8, "inout"), (b0 + 0.4, -1.2, "inout"), (b0 + 0.8, -0.4, "inout"), (b0 + 1.15, -0.8, "inout")]
    K["root_x"] += [(b0, 0.0, "inout"), (b0 + 1.15, 0.0, "inout")]
    K["head_nod"] += [(b0, 8.0, "inout"), (b0 + 0.4, 16.0, "inout"), (b0 + 0.7, -2.0, "inout"),
                      (b0 + 0.95, 2.0, "inout"), (b0 + 1.02, -4.0, "inout"), (b0 + 1.08, 2.0, "inout"),
                      (b0 + 1.15, 8.0, "inout")]
    K["head_roll"] = [(0.0, 0.0), (b0 + 0.6, 0.0, "inout"), (b0 + 0.78, 6.0, "inout"), (b0 + 0.95, 0.0, "inout"),
                      (LENGTH, 0.0)]
    LOOK += [(b0 + 0.3, tuple(pinch), "inout"), (b0 + 0.62, tuple(eye_h + np.array([0, 0, -2])), "inout"),
             (b0 + 1.0, tuple(eye_h + np.array([0, 0, -2])), "inout")]


def close(ks):
    ks = sorted(ks, key=lambda k: k[0])
    out = []
    for k in ks:
        if out and abs(out[-1][0] - k[0]) < 1e-6:
            out[-1] = k
        else:
            out.append(k)
    if out[-1][0] < LENGTH - 1e-6:
        out.append((LENGTH, out[0][1]))
    return out


clip.cycles, clip.base_length = 1, LENGTH
clip.keys({p: close(v) for p, v in K.items()})
clip.key_vec("rh", close([(t, tuple(p), e) for t, p, e in RH]))
clip.key_vec("lh", close([(t, tuple(p), e) for t, p, e in LH]))
clip.key_vec("look", close(LOOK))
clip.arm_live("right", lambda t, w: clip.cv("rh", t), (-0.8, 0.4, 0.5))
clip.arm_live("left", lambda t, w: clip.cv("lh", t), (0.8, 0.4, 0.5))
clip.head_w = (0.3, 0.5)
clip.head_limit = (28.0, 40.0)
clip.hand_props = [{"hand": "offhand", "item": "hearthstead:prop_sack_scoop", "from": 0.0, "to": LENGTH,
                    "hide_real": True}]
propkit.recolour_sprite()
contacts = [i * BASE + CONTACT for i in range(N)]
clip.run(contacts=[(f"push_far_{i + 1}", tc, "right", (lambda w, tc=tc: clip.cv("rh", tc))) for i, tc in enumerate(contacts)],
         keep=tuple(sorted({round(k[0], 3) for k in RH})),
         meta={"contract": f"MILL_GRIND{('__' + V) if V else ''} {LENGTH:.2f} s loop; WORK_QUERN_GRIND at clip tick 9 "
                           f"(0.45 s) of every 24-tick turn: the peg at the far side"
                           + ("; turn 2 is the break: flour rubbed and blown off, a shove on the contact" if V == "v3" else "")})
