"""LOOM_WEAVE (weaver), authored in Blender -- anim overkill 2026-09-26.

    HS_VARIANT=[|v2|v3] blender -b --factory-startup --python author_loom_weave.py -- [--fast|--full] [--no-export]

The weaver's own motion (it used to play the scholar's generic FINE_WORK): at an upright loom,
the shuttle (display prop hearthstead:prop_shuttle) is THROWN through the open shed from one hand
and CAUGHT by the other, both hands pull the beater home, the shed changes on the treadle.

Contract: 1.60 s loop = two passes of 0.80 s (period 16 ticks, Employment own-clip); LOOM_CLACK at
clip tick 12 of each pass (0.60 s and 1.40 s) = the beater slammed home.
Per pass: 0.00-0.08 wrist cocks (anticipation), 0.08-0.26 the throw (body turns with it, eyes
follow the shuttle across), 0.26 caught, 0.30-0.46 both hands onto the beater, 0.46-0.60 PULL
(lean back a touch, contact), 0.60-0.72 push the beater back, 0.72-0.80 treadle (hip dip).
__v2 (3.20 s): the same with a hummed head-sway and a slower, prouder second beat.
__v3 (4.80 s) = the BREAK: after pass 2 the weaver interlaces the fingers and stretches them out
(knuckles), then lifts the cloth edge to the eye to check the weave, taps the beater home on the
2.20 s contact, looks again, beats at 3.00 s, and weaves on.
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402
import propkit  # noqa: E402

PASS = 0.80
BASE = 2 * PASS
V = ck.variant()
N = {"": 1, "v2": 2, "v3": 3}[V]
LENGTH = BASE * N
PNG = os.path.join(ck.REPO, "src", "main", "resources", "assets", "hearthstead", "textures", "item",
                   "prop_shuttle.png")
clip = ck.Clip("LOOM_WEAVE" + ("__" + V.upper() if V else ""), LENGTH, True, "settler_weaver.png",
               feet=((-2.8, 24.0, 0.6), (2.8, 24.0, -0.4)), tool_png=PNG)
WOOD = (0.44, 0.30, 0.17, 1)
clip.cam_side = ((3.4, -1.4, 1.5), (0.0, -0.45, 0.9), 36)   # from the left, beside the loom
clip.prop("loom_post_r", (-11, -6, -15), (2, 30, 2), WOOD)
clip.prop("loom_post_l", (9, -6, -15), (2, 30, 2), WOOD)
clip.prop("loom_top", (-11, -7, -15), (22, 2, 2), WOOD)
clip.prop("warp", (-8, -4, -13.4), (16, 18, 0.3), (0.86, 0.82, 0.72, 1))
clip.prop("cloth", (-8, 9, -13.2), (16, 5, 0.6), (0.62, 0.18, 0.16, 1))
clip.prop("breast_beam", (-10, 13, -12.5), (20, 2, 2), WOOD)

BEAT_FAR = -11.8      # beater bar z at rest (against the shed) ...
BEAT_NEAR = -9.4      # ... and pulled home
Y_BEAT = 8.4
SHED_R = np.array([-7.0, 6.2, -11.2])      # shuttle mouth on the right / left of the shed
SHED_L = np.array([7.0, 6.2, -11.2])

# hand paths per pass (dir +1 = right hand throws to the left)
def pass_keys(t0, d, beat_scale=1.0, tail=None):
    """(rh, lh) keyframes for one pass starting at t0."""
    thrower, catcher = (SHED_R, SHED_L) if d > 0 else (SHED_L, SHED_R)
    bar_r = np.array([-4.2, Y_BEAT, BEAT_FAR])
    bar_l = np.array([4.2, Y_BEAT, BEAT_FAR])
    pull = np.array([0.0, 0.2, BEAT_NEAR - BEAT_FAR]) * beat_scale
    th = [(t0 + 0.00, thrower + np.array([0.0, 0.3, 0.8]), "inout"),
          (t0 + 0.08, thrower + np.array([-0.8 * d, 0.2, 1.2]), "accel"),       # cocks the wrist back
          (t0 + 0.20, thrower + np.array([3.5 * d, 0.0, -0.4]), "decel"),       # flick, follow-through
          (t0 + 0.30, (bar_r if d > 0 else bar_l) + np.array([0, -0.6, 0.6]), "inout"),
          (t0 + 0.46, bar_r if d > 0 else bar_l, "accel"),
          (t0 + 0.60, (bar_r if d > 0 else bar_l) + pull, "out"),               # BEAT (contact)
          (t0 + 0.72, bar_r if d > 0 else bar_l, "inout")]
    ca = [(t0 + 0.00, catcher + np.array([0.0, 1.2, 1.4]), "inout"),
          (t0 + 0.18, catcher + np.array([0.6 * d, 0.2, 0.4]), "decel"),        # waits at the shed
          (t0 + 0.26, catcher + np.array([1.2 * d, 0.0, 0.8]), "inout"),        # CAUGHT
          (t0 + 0.34, (bar_l if d > 0 else bar_r) + np.array([0, -0.6, 0.6]), "inout"),
          (t0 + 0.46, bar_l if d > 0 else bar_r, "accel"),
          (t0 + 0.60, (bar_l if d > 0 else bar_r) + pull, "out"),
          (t0 + 0.72, bar_l if d > 0 else bar_r, "inout")]
    # the catcher becomes the next thrower: it ends at its shed mouth
    ca.append((t0 + PASS - 0.001, catcher + np.array([0.0, 0.3, 0.8]), "inout"))
    th.append((t0 + PASS - 0.001, thrower + np.array([0.0, 1.2, 1.4]), "inout"))
    return (th, ca) if d > 0 else (ca, th)


RH, LH, LOOK = [], [], []
K = {"root_x": [], "root_y": [], "root_z": [(0.0, 0.0)], "root_yaw": [(0.0, 0.0)], "torso_x": [],
     "torso_y": [], "torso_z": [(0.0, 0.0)], "head_nod": [], "head_yaw": [(0.0, 0.0)], "head_roll": [(0.0, 0.0)],
     "cloak_add": [(0.0, 0.0)]}
passes = []
for c_ in range(N):
    if V == "v3" and c_ == 1:
        continue
    passes += [(c_ * BASE, +1), (c_ * BASE + PASS, -1)]
for t0, d in passes:
    r, l = pass_keys(t0, d, beat_scale=1.15 if (V == "v2" and t0 >= BASE) else 1.0)
    RH += r
    LH += l
    K["torso_y"] += [(t0 + 0.0, 4.0 * d, "inout"), (t0 + 0.20, -6.0 * d, "decel"), (t0 + 0.46, 0.0, "inout"),
                     (t0 + 0.72, 0.0, "inout")]
    K["torso_x"] += [(t0 + 0.0, 10.0, "inout"), (t0 + 0.46, 12.0, "accel"), (t0 + 0.60, 5.0, "out"),
                     (t0 + 0.72, 9.0, "inout")]
    K["root_y"] += [(t0 + 0.0, -0.4, "inout"), (t0 + 0.60, -0.6, "out"), (t0 + 0.72, -0.3, "inout"),
                    (t0 + 0.76, -0.9, "inout")]           # treadle dip
    K["root_x"] += [(t0 + 0.0, 0.3 * d, "inout"), (t0 + 0.72, -0.3 * d, "inout")]
    K["head_nod"] += [(t0 + 0.0, 8.0, "inout"), (t0 + 0.26, 6.0, "inout"), (t0 + 0.60, 12.0, "out"),
                      (t0 + 0.72, 8.0, "inout")]
    LOOK += [(t0 + 0.0, tuple(np.array([6.0 * d, 6.0, -10.0])), "inout"),
             (t0 + 0.24, tuple(np.array([-6.0 * d, 6.0, -10.0])), "decel"),
             (t0 + 0.50, (0.0, 9.0, -10.0), "inout")]
if V == "v2":
    K["head_roll"] = [(0.0, 0.0), (BASE, 0.0, "inout"), (BASE + 0.4, 5.0, "inout"), (BASE + 0.8, -5.0, "inout"),
                      (BASE + 1.2, 4.0, "inout"), (2 * BASE, 0.0)]
if V == "v3":
    b0 = BASE
    bar_r, bar_l = np.array([-4.2, Y_BEAT, BEAT_FAR]), np.array([4.2, Y_BEAT, BEAT_FAR])
    pull = np.array([0.0, 0.2, BEAT_NEAR - BEAT_FAR])
    # interlace + stretch (0.0-0.45), cloth to the eye (0.5-0.55), BEAT (0.60), look (0.75-1.25), BEAT (1.40)
    RH += [(b0 + 0.00, SHED_R + np.array([0.0, 0.3, 0.8]), "inout"),
           (b0 + 0.18, np.array([-0.6, 4.0, -7.2]), "inout"), (b0 + 0.36, np.array([-1.4, 3.0, -10.2]), "decel"),
           (b0 + 0.46, bar_r, "accel"), (b0 + 0.60, bar_r + pull, "out"), (b0 + 0.70, bar_r, "inout"),
           (b0 + 0.80, np.array([-2.8, 10.0, -12.4]), "inout"), (b0 + 1.00, np.array([-2.0, 1.4, -8.8]), "inout"),
           (b0 + 1.18, np.array([-2.0, 1.2, -8.6]), "inout"), (b0 + 1.30, bar_r, "accel"),
           (b0 + 1.40, bar_r + pull, "out"), (b0 + 1.52, bar_r, "inout"),
           (b0 + BASE - 0.001, SHED_R + np.array([0.0, 0.3, 0.8]), "inout")]
    LH += [(b0 + 0.00, SHED_L + np.array([0.0, 1.2, 1.4]), "inout"),
           (b0 + 0.18, np.array([0.6, 4.0, -7.2]), "inout"), (b0 + 0.36, np.array([1.4, 3.0, -10.2]), "decel"),
           (b0 + 0.46, bar_l, "accel"), (b0 + 0.60, bar_l + pull, "out"), (b0 + 0.70, bar_l, "inout"),
           (b0 + 0.80, np.array([2.8, 10.0, -12.4]), "inout"), (b0 + 1.00, np.array([2.0, 1.4, -8.8]), "inout"),
           (b0 + 1.18, np.array([2.0, 1.2, -8.6]), "inout"), (b0 + 1.30, bar_l, "accel"),
           (b0 + 1.40, bar_l + pull, "out"), (b0 + 1.52, bar_l, "inout"),
           (b0 + BASE - 0.001, SHED_L + np.array([0.0, 1.2, 1.4]), "inout")]
    K["torso_x"] += [(b0 + 0.0, 8.0, "inout"), (b0 + 0.36, 2.0, "decel"), (b0 + 0.46, 9.0, "accel"),
                     (b0 + 0.60, 3.0, "out"), (b0 + 1.00, 4.0, "inout"), (b0 + 1.30, 9.0, "accel"),
                     (b0 + 1.40, 3.0, "out"), (b0 + 1.52, 7.0, "inout")]
    K["torso_y"] += [(b0 + 0.0, 0.0, "inout"), (b0 + 1.52, 0.0, "inout")]
    K["root_y"] += [(b0 + 0.0, -0.4, "inout"), (b0 + 0.36, -0.1, "inout"), (b0 + 0.60, -0.6, "out"),
                    (b0 + 1.40, -0.6, "out"), (b0 + 1.52, -0.3, "inout")]
    K["root_x"] += [(b0 + 0.0, 0.0, "inout"), (b0 + 1.52, 0.0, "inout")]
    K["head_nod"] += [(b0 + 0.0, 4.0, "inout"), (b0 + 0.36, -4.0, "inout"), (b0 + 0.60, 10.0, "out"),
                      (b0 + 1.00, -6.0, "inout"), (b0 + 1.18, -4.0, "inout"), (b0 + 1.40, 10.0, "out"),
                      (b0 + 1.52, 8.0, "inout")]
    K["head_roll"] = [(0.0, 0.0), (b0 + 0.9, 0.0, "inout"), (b0 + 1.02, 7.0, "inout"), (b0 + 1.2, 0.0, "inout"),
                      (LENGTH, 0.0)]
    LOOK += [(b0 + 0.0, (0.0, 6.0, -10.0), "inout"), (b0 + 0.95, (0.0, 1.0, -9.0), "inout"),
             (b0 + 1.25, (0.0, 1.0, -9.0), "inout"), (b0 + 1.40, (0.0, 9.0, -10.0), "inout")]


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
clip.arm_live("right", lambda t, w: clip.cv("rh", t), (-0.8, 0.5, 0.4))
clip.arm_live("left", lambda t, w: clip.cv("lh", t), (0.8, 0.5, 0.4))
clip.head_w = (0.35, 0.55)
clip.head_limit = (30.0, 40.0)
# the shuttle: in the throwing hand until the catch, then in the other hand
props = []
for t0, d in passes:
    props.append({"hand": "mainhand" if d > 0 else "offhand", "item": "hearthstead:prop_shuttle",
                  "from": round(t0, 3), "to": round(t0 + 0.26, 3), "hide_real": True})
    props.append({"hand": "offhand" if d > 0 else "mainhand", "item": "hearthstead:prop_shuttle",
                  "from": round(t0 + 0.26, 3), "to": round(t0 + 0.30, 3), "hide_real": True})
clip.hand_props = props
propkit.recolour_sprite()
beats = sorted({round(t0 + 0.60, 3) for t0, _ in passes} | ({round(BASE + 0.60, 3), round(BASE + 1.40, 3)} if V == "v3" else set()))
clip.run(contacts=[(f"beat_{i + 1}", tb, "right", (lambda w, tb=tb: clip.cv("rh", tb))) for i, tb in enumerate(beats)],
         keep=tuple(sorted({round(k[0], 3) for k in RH + LH if 0 <= k[0] <= LENGTH})),
         meta={"contract": f"LOOM_WEAVE{('__' + V) if V else ''} {LENGTH:.2f} s loop; LOOM_CLACK at clip tick 12 of "
                           f"every 16-tick pass (0.60 s, 1.40 s ...): the beater pulled home"
                           + ("; pass 3-4 is the break: finger stretch, cloth checked at the eye, beats on time" if V == "v3" else "")})
