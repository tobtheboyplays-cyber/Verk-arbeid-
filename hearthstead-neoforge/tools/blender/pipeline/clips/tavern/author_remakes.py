"""Tavern remakes (tavern lane): same keys, lengths, loop flags and contact beats as before, more
character. One Blender process:

    blender -b --factory-startup --python author_remakes.py -- [--fast|--full|--stills] [--no-export] [clip ...]

  INN_WELCOME        1.8 s one-shot (innkeeper 36-tick envelope; village WELCOME 32 ticks with no
                     envelope -> on rest by 1.6 s). Anticipation dip, arms open wide palms-up
                     ("come in!"), a two-beat wave, hand to heart with a small bow, settle.
  EAT                1.2 s loop, bites at 0.25 / 0.70 s (ticks 5/14; the meal is drawn at the RIGHT
                     palm). An eager lean into each bite, a tearing tug of the head after it, the
                     free hand guarding under the food, two chews with a cheek bob.
  EAT__V2            2.4 s = 2 cycles: after the second bite leans back, eyes shut, pats the belly.
  VILLAGE_CHAT       2.4 s loop, frame 0 on rest: beats at 0.6 and 1.8 s (the SocialPair listener
                     nods on them): counts a point on the fingers, then a sweeping "and then..."
  VILLAGE_CHAT__V2   2.4 s: the punchline - mimes a big swing at 1.8, laughs at it.
  VILLAGE_LISTEN     2.4 s loop: attentive, nods ON 0.6 and 1.8, a chuckle and shoulder shake at 1.8.
  BARD_PLAY          1.0 s loop, seated upper body (down-strokes 0.15 / 0.65, up 0.40 / 0.90): the
                     body rocks with the rhythm, the head bobs on the one, the strumming arm drives
                     from the shoulder, the fretting hand changes chord each beat.
  BARD_PLAY__V2      2.0 s: a flourish - a high up-stroke flung out at 1.35, head back.
  BARD_PLAY__V3      3.0 s: sings a line (head back, eyes shut), a bow to the room, plays on.
"""

import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tavernkit as tk  # noqa: E402
import numpy as np  # noqa: E402
import mcrig  # noqa: E402
import lifekit as lk  # noqa: E402

A = tk.args()
ONLY = list(A["rest"])
c = tk.c
ACC, ACC2, DEC, DEC3, INOUT, WHIP = tk.ACC, tk.ACC2, tk.DEC, tk.DEC3, tk.INOUT, tk.WHIP
G = 24.0


def vec(name, keys):
    return {f"{name}_{a}": [(k[0], k[1][i], *k[2:]) for k in keys] for i, a in enumerate("xyz")}


def cv(name, t):
    return np.array([c(name + "_x", t), c(name + "_y", t), c(name + "_z", t)])


def std_body(t, L, shake=0.0, breath=0.014):
    return {"root": {"rot": (0.0, c("root_yaw", t), 0.0), "pos": (c("root_x", t), c("root_y", t), 0.0)},
            "torso": {"rot": (c("torso_x", t) + 1.6 * shake, c("torso_y", t), c("torso_z", t)),
                      "pos": (0.0, 0.18 * abs(shake), 0.0),
                      "scale": lk.breath_scale(lk.breath(t, L / max(1, round(L / 1.2)), phase=0.1), breath)},
            "head": {"rot": (c("head_x", t) - 1.4 * shake, c("head_y", t), c("head_z", t))}}


def cloak(ch, t, L, base=1.0):
    pr = lk.lagged_rate(lambda u: c("torso_x", u % L), t)
    yr = lk.lagged_rate(lambda u: c("torso_y", u % L), t)
    ch["cloak"] = {"rot": lk.cloak(base, ch["torso"]["rot"][0], pr, yr, k_pitch=0.3, k_yaw=0.06)}


# =========================================================================== INN_WELCOME 1.8
def keys_welcome():
    L = 1.8
    K = {"root_y": [(0.0, 0.0), (0.14, -0.6), (0.4, 0.1), (1.1, -0.1), (1.32, -0.5), (1.55, -0.05), (L, 0.0)],
         "torso_x": [(0.0, 0.0), (0.14, 3.0), (0.4, -5.0, *DEC), (0.85, -3.0), (1.12, 2.0), (1.32, 12.0, *DEC),
                     (1.5, 1.0), (1.6, 0.0), (L, 0.0)],
         "torso_y": [(0.0, 0.0), (0.4, 0.0), (0.7, -5.0), (1.05, -3.0), (1.45, -0.5), (L, 0.0)],
         "torso_z": [(0.0, 0.0), (0.7, 2.0), (0.85, -1.0), (1.0, 2.0), (1.4, 0.0), (L, 0.0)],
         "head_x": [(0.0, 0.0), (0.14, 2.0), (0.4, -7.0, *DEC), (0.9, -4.0), (1.15, 0.0), (1.32, 12.0, *DEC),
                    (1.52, 1.0), (L, 0.0)],
         "head_y": [(0.0, 0.0), (L, 0.0)],
         "head_z": [(0.0, 0.0), (0.4, 5.0), (0.8, 2.0), (1.0, 5.0), (1.4, 0.0), (L, 0.0)],
         "ik": [(0.0, 0.0), (0.06, 0.0, *ACC2), (0.3, 1.0), (1.35, 1.0, *INOUT), (1.6, 0.0), (L, 0.0)]}
    # right: open wide palm-up -> two waves pivoting at the elbow -> down
    K.update(vec("rh", [(0.0, (-6.3, -1.0, -1.0)), (0.35, (-12.6, -8.6, -3.6), *DEC3), (0.55, (-13.0, -14.6, -2.6)),
                        (0.7, (-11.0, -16.6, -2.0)), (0.85, (-13.8, -14.8, -1.4)), (1.0, (-11.2, -16.8, -2.0)),
                        (1.15, (-13.2, -14.6, -1.6)), (1.4, (-7.2, -4.0, -2.4), *INOUT), (L, (-6.3, -1.0, -1.0))]))
    # left: open wide -> hand to the heart for the bow
    K.update(vec("lh", [(0.0, (6.3, -1.0, -1.0)), (0.35, (11.8, -9.0, -4.0), *DEC3), (0.8, (11.0, -8.0, -4.4)),
                        (1.05, (2.0, -8.6, -4.0), *INOUT), (1.4, (2.2, -8.2, -3.8)), (1.6, (6.0, -1.5, -1.4), *INOUT),
                        (L, (6.3, -1.0, -1.0))]))
    tk.lk.key(K, cyclic=False)


def solve_welcome():
    L = 1.8

    def solve(t):
        ch = std_body(t, L, breath=0.0)
        w = max(0.0, min(1.0, c("ik", t)))
        lk.arm_ik_local(ch, "right", cv("rh", t), (-1.0, 0.6, 0.35), solve.prev)
        lk.arm_ik_local(ch, "left", cv("lh", t), (1.0, 0.6, 0.35), solve.prev)
        for b in ("right_arm", "right_forearm", "left_arm", "left_forearm"):
            ch[b]["rot"] = tuple(v * w for v in ch[b]["rot"])
        lk.leg_ik(ch, lk.FEET0, solve.prev)
        cloak(ch, t, L, base=0.0)
        return lk.edge_rest(ch, t, L)
    solve.prev = {}
    return solve


# =========================================================================== EAT 1.2 (+V2 2.4)
BITES = (0.25, 0.70)
HOLD = np.array([-2.2, -4.4, -6.0])          # torso space: food in front of the chest
BITE_HEAD = np.array([-1.6, 0.6, -5.6])       # head space: palm just under the chin (food at the lips)


def keys_eat(var):
    K = {"root_y": [(0.0, -0.45), (0.12, -0.35), (0.25, -0.8, *DEC), (0.45, -0.5), (0.57, -0.4), (0.70, -0.82, *DEC),
                    (0.92, -0.5), (1.2, -0.45)],
         "torso_x": [(0.0, 4.0), (0.12, 3.0), (0.25, 10.0, *DEC), (0.34, 7.5), (0.45, 5.0), (0.57, 3.2),
                     (0.70, 10.0, *DEC), (0.79, 7.5), (0.92, 5.0), (1.2, 4.0)],
         "torso_y": [(0.0, 0.0), (0.25, -3.0), (0.36, 2.0), (0.5, -0.5), (0.70, 3.0), (0.81, -2.0), (0.98, 0.5), (1.2, 0.0)],
         "torso_z": [(0.0, 0.0), (0.25, 1.5), (0.70, -1.5), (1.2, 0.0)],
         "bite": [(0.0, 0.0), (0.10, 0.0, *ACC), (0.25, 1.0), (0.30, 0.96, *INOUT), (0.46, 0.0), (0.55, 0.0, *ACC),
                  (0.70, 1.0), (0.75, 0.96, *INOUT), (0.92, 0.0), (1.2, 0.0)],
         "dip": [(0.0, 0.0), (0.10, 1.0), (0.21, 0.0), (0.46, 0.0), (0.55, 1.0), (0.66, 0.0), (1.2, 0.0)],
         # the tearing tug after each bite (head yaw + pitch back), then two chews
         "head_x": [(0.0, 7.0), (0.10, 4.0), (0.25, 18.0, *DEC), (0.31, 10.0, *DEC3), (0.38, 13.0), (0.44, 9.5),
                    (0.50, 12.0), (0.57, 5.0), (0.70, 18.0, *DEC), (0.76, 10.0, *DEC3), (0.83, 13.0), (0.9, 9.5),
                    (0.97, 12.0), (1.05, 9.0), (1.12, 11.0), (1.2, 7.0)],
         "head_y": [(0.0, -5.0), (0.25, -8.0), (0.31, 4.0, *DEC3), (0.5, -4.0), (0.70, -7.0), (0.76, 6.0, *DEC3),
                    (0.98, -3.5), (1.2, -5.0)],
         "head_z": [(0.0, 0.0), (0.25, -3.0), (0.31, 3.0), (0.5, 0.0), (0.70, 3.0), (0.76, -3.0), (0.97, 0.5), (1.2, 0.0)],
         "lh_y": [(0.0, -2.8), (0.25, -3.6), (0.5, -3.0), (0.70, -3.6), (0.95, -3.0), (1.2, -2.8)]}
    tk.lk.key(K, cyclic=True)
    if var:
        # between bite 2 (0.70) and bite 3 (1.45, lift from 1.3): lean back, eyes shut, pat pat
        tk.lk.key({"sav": [(0.0, 0.0), (0.88, 0.0, *INOUT), (1.02, 1.0), (1.2, 1.0, *INOUT), (1.32, 0.0), (2.4, 0.0)],
                   "pat": [(0.0, 0.0), (1.0, 0.0), (1.07, 1.0, *DEC), (1.12, 0.0, *ACC), (1.18, 1.0, *DEC),
                           (1.25, 0.0), (2.4, 0.0)]}, cyclic=False)


def solve_eat(var):
    L = 1.2
    LT = 2.4 if var else 1.2
    BELLY = np.array([3.0, -3.6, -3.4])          # torso space: the patting hand on the belly

    def solve(t):
        tv = t % LT
        t %= L
        sav = c("sav", tv) if var else 0.0
        pat = c("pat", tv) if var else 0.0
        ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": (0.0, c("root_y", t), 0.0)},
              "torso": {"rot": (c("torso_x", t) * (1 - sav) - 6.0 * sav, c("torso_y", t) * (1 - sav), c("torso_z", t)),
                        "scale": lk.breath_scale(lk.breath(t, L / 2.0, phase=0.15), 0.010 + 0.02 * sav)}}
        ch["head"] = {"rot": (c("head_x", t) * (1 - sav) - 14.0 * sav, c("head_y", t) * (1 - sav), c("head_z", t) + 6.0 * sav)}
        w = mcrig.pose_matrices(ch)
        b = max(0.0, min(1.0, c("bite", t))) * (1 - sav)
        hold = mcrig.xform(w["torso"], HOLD + np.array([0.0, 0.9, 0.2]) * c("dip", t) + np.array([0.0, 2.0, 1.0]) * sav)
        mouth = mcrig.xform(w["head"], BITE_HEAD)
        lk.arm_ik(ch, w, "right", hold * (1 - b) + mouth * b, (-0.4, 1.0, 0.15), solve.prev)
        # free hand guards under the food; in the variant it pats the belly
        guard = np.array([1.6, c("lh_y", t), -5.4])
        lt = guard * (1 - sav) + (BELLY + np.array([0.0, 0.0, -0.9]) * pat) * sav
        lk.arm_ik_local(ch, "left", lt, (0.6, 1.0, 0.3), solve.prev)
        lk.leg_ik(ch, lk.FEET, solve.prev)
        cloak(ch, t, L, base=2.0)
        return ch
    solve.prev = {}
    return solve


def eat_checks(var):
    def run(times, samples):
        err = []
        for bt in (BITES + ((1.45, 1.90) if var else ())):
            i = int(round(bt * 60))
            w = mcrig.pose_matrices(samples[i])
            err.append(round(float(np.linalg.norm(lk.palm(w, "right") - mcrig.xform(w["head"], BITE_HEAD))), 3))
        return {"palm_to_mouth_px_at_bites": err}
    return run


# =========================================================================== VILLAGE_CHAT (+V2), LISTEN 2.4
BEATS = (0.6, 1.8)


def keys_chat(v2):
    L = 2.4
    K = {"root_x": [(0.0, 0.0), (0.9, 0.5), (1.6, 0.4), (L, 0.0)],
         "root_y": [(0.0, -0.15), (0.6, -0.4), (1.2, -0.3), (1.8, -0.45), (L, -0.15)],
         "torso_x": [(0.0, 2.0), (0.4, 1.0), (0.6, 5.0, *DEC), (1.0, 3.0), (1.5, 0.0), (1.8, 6.0, *DEC), (2.1, 3.0),
                     (L, 2.0)],
         "torso_y": [(0.0, 0.0), (0.6, -5.0), (1.2, -2.0), (1.6, 6.0), (1.8, -8.0, *DEC), (2.2, -1.0), (L, 0.0)],
         "torso_z": [(0.0, 0.0), (0.9, -1.8), (1.6, -1.2), (L, 0.0)],
         "head_x": [(0.0, 2.0), (0.4, 0.0), (0.6, 8.0, *DEC), (0.8, 2.0), (1.5, -2.0), (1.8, 9.0, *DEC), (2.05, 1.0),
                    (L, 2.0)],
         "head_y": [(0.0, 0.0), (0.6, -4.0), (1.2, 3.0), (1.8, -6.0), (2.2, 0.0), (L, 0.0)],
         "head_z": [(0.0, 0.0), (0.6, -3.0), (1.2, 2.0), (1.8, 3.0), (L, 0.0)]}
    REST_R, REST_L = (-6.1, -0.4, -1.3), (6.1, -0.4, -1.2)
    if not v2:
        # calm: hands mostly at rest; ONE small palm-up lift on the stressed beat 0.6, a short
        # little point on 1.8, weight on one hip, breathing (owner: fewer, smaller gestures)
        rh = [(0.0, REST_R), (0.32, REST_R, *ACC2), (0.6, (-4.6, -4.2, -5.2), *DEC3), (0.85, (-4.9, -3.6, -4.8)),
              (1.2, REST_R, *INOUT), (1.55, REST_R, *ACC2), (1.8, (-6.8, -4.6, -5.0), *DEC3), (2.0, (-6.6, -4.0, -4.6)),
              (2.3, REST_R, *INOUT), (L, REST_R)]
        lh = [(0.0, REST_L), (0.6, (6.0, -0.9, -1.8)), (1.2, REST_L), (1.8, (6.2, -0.8, -1.6)), (L, REST_L)]
        K["torso_x"] = [(0.0, 2.0), (0.6, 3.5, *DEC), (1.2, 2.0), (1.8, 3.2, *DEC), (L, 2.0)]
        K["torso_y"] = [(0.0, 0.0), (0.6, -2.5), (1.2, -1.0), (1.8, -3.0, *DEC), (L, 0.0)]
        K["head_x"] = [(0.0, 2.0), (0.45, 0.5), (0.6, 5.0, *DEC), (0.9, 2.0), (1.65, 1.0), (1.8, 5.5, *DEC),
                       (2.1, 2.0), (L, 2.0)]
        K["head_y"] = [(0.0, 0.0), (0.6, -2.0), (1.2, 1.5), (1.8, -3.0), (L, 0.0)]
        K["shake"] = [(0.0, 0.0), (L, 0.0)]
    else:
        # the punchline: winds up an imaginary axe, swings it on 1.8, laughs at it
        K["torso_y"] = [(0.0, 0.0), (0.6, 18.0, *INOUT), (1.5, 20.0, *ACC), (1.8, -18.0, *DEC), (2.2, -3.0), (L, 0.0)]
        K["torso_x"] = [(0.0, 2.0), (0.6, -3.0), (1.5, -4.0), (1.8, 9.0, *DEC), (2.0, -6.0), (L, 2.0)]
        K["head_x"] = [(0.0, 2.0), (0.6, -3.0), (1.5, -4.0), (1.8, 6.0), (2.0, -14.0, *DEC), (2.3, -4.0), (L, 2.0)]
        rh = [(0.0, REST_R), (0.6, (-7.4, -14.0, 2.2), *INOUT), (1.5, (-7.0, -14.6, 3.0), *ACC),
              (1.8, (1.0, -3.0, -8.8), *DEC), (2.05, (-2.4, 0.0, -5.4)), (L, REST_R)]
        lh = [(0.0, REST_L), (0.6, (-3.6, -13.0, 1.8), *INOUT), (1.5, (-3.4, -13.6, 2.6), *ACC),
              (1.8, (3.6, -3.4, -8.6), *DEC), (2.05, (3.4, -1.0, -3.4)), (L, REST_L)]
        K["shake"] = [(0.0, 0.0), (1.85, 0.0), (1.95, 1.0), (2.3, 0.6), (L, 0.0)]
    K.update(vec("rh", rh))
    K.update(vec("lh", lh))
    tk.lk.key(K, cyclic=True)


def keys_listen():
    L = 2.4
    K = {"root_x": [(0.0, 0.0), (1.2, -0.5), (L, 0.0)],
         "root_y": [(0.0, -0.15), (0.9, -0.35), (1.8, -0.3), (L, -0.15)],
         "torso_x": [(0.0, 3.0), (0.6, 5.0, *DEC), (1.0, 3.5), (1.8, 7.0, *DEC), (2.1, 2.0), (L, 3.0)],
         "torso_y": [(0.0, 0.0), (0.7, 2.0), (1.5, 0.5), (2.0, -1.5), (L, 0.0)],
         "torso_z": [(0.0, 0.0), (1.2, 1.6), (L, 0.0)],
         "head_x": [(0.0, 2.0), (0.45, 0.0), (0.6, 12.0, *DEC), (0.85, 2.0), (1.65, 1.0), (1.8, 10.0, *DEC),
                    (1.95, -6.0), (2.2, 3.0), (L, 2.0)],
         "head_y": [(0.0, 0.0), (0.6, 3.0), (1.3, 1.0), (1.9, -3.0), (L, 0.0)],
         "head_z": [(0.0, 0.0), (0.35, 6.0), (0.75, 5.0), (1.1, 2.0), (1.5, -3.0), (2.0, -4.0), (L, 0.0)],
         "shake": [(0.0, 0.0), (1.8, 0.0), (1.9, 0.7), (2.25, 0.3), (L, 0.0)]}
    K.update(vec("rh", [(0.0, (-2.6, -2.0, -4.2)), (0.8, (-2.4, -2.4, -4.4)), (1.6, (-2.8, -1.9, -4.1)),
                        (1.9, (-2.2, -3.6, -4.8)), (2.2, (-2.7, -2.1, -4.2)), (2.4, (-2.6, -2.0, -4.2))]))
    K.update(vec("lh", [(0.0, (2.0, -1.7, -4.4)), (0.9, (1.9, -2.0, -4.6)), (1.7, (2.2, -1.6, -4.3)),
                        (2.4, (2.0, -1.7, -4.4))]))
    tk.lk.key(K, cyclic=True)


def solve_talk():
    L = 2.4

    def solve(t):
        t %= L
        sh = c("shake", t) * math.sin(2 * math.pi * 6.5 * t)
        ch = std_body(t, L, shake=sh, breath=0.012)
        lk.arm_ik_local(ch, "right", cv("rh", t), (-0.6, 0.7, 0.9), solve.prev)
        lk.arm_ik_local(ch, "left", cv("lh", t), (0.6, 0.7, 0.9), solve.prev)
        lk.leg_ik(ch, lk.FEET, solve.prev)
        cloak(ch, t, L)
        return ch
    solve.prev = {}
    return solve


# =========================================================================== BARD_PLAY 1.0 (+V2 2.0, V3 3.0)
STRUM_TOP = np.array([-0.5, -5.2, -5.8])
STRUM_BOT = np.array([-1.9, -1.5, -5.3])
NECK_FRET = np.array([6.6, -5.8, -6.8])
NECK_DIR = np.array([0.72, -0.42, -0.55]) / np.linalg.norm([0.72, -0.42, -0.55])
BARD_EXPORT = ["torso", "head", "right_arm", "left_arm", "right_forearm", "left_forearm", "cloak"]


def keys_bard(var):
    K = {"strum": [(0.0, 0.12), (0.09, 0.0, *ACC), (0.15, 0.5), (0.21, 1.0, *DEC), (0.33, 0.9), (0.40, 0.5),
                   (0.46, 0.15, *DEC), (0.59, 0.0, *ACC), (0.65, 0.5), (0.71, 1.0, *DEC), (0.83, 0.9), (0.90, 0.5),
                   (0.96, 0.14), (1.0, 0.12)],
         "strum_off": [(0.0, 0.2), (0.12, 0.0), (0.24, 0.1), (0.40, 0.9), (0.50, 0.3), (0.62, 0.0), (0.74, 0.1),
                       (0.90, 0.9), (1.0, 0.2)],
         "chord": [(0.0, 0.0), (0.04, 1.1, *DEC3), (0.46, 1.1), (0.54, -0.6, *DEC3), (0.96, -0.6), (1.0, 0.0)],
         "fret_lift": [(0.0, 0.0), (0.02, 0.6), (0.06, 0.0), (0.48, 0.0), (0.52, 0.6), (0.56, 0.0), (1.0, 0.0)],
         # the whole upper body rocks with the rhythm: a lean into the one, a sway per half bar
         "torso_y": [(0.0, 0.0), (0.18, -3.0), (0.42, 0.5), (0.68, -2.6), (0.92, 0.5), (1.0, 0.0)],
         "torso_z": [(0.0, 0.0), (0.25, 2.4), (0.5, 0.0), (0.75, -2.4), (1.0, 0.0)],
         "torso_x": [(0.0, 5.0), (0.20, 8.0, *DEC), (0.45, 4.5), (0.70, 7.0, *DEC), (0.95, 4.8), (1.0, 5.0)],
         "head_x": [(0.0, 8.0), (0.08, 6.0), (0.22, 15.0, *DEC), (0.50, 6.5), (0.58, 6.0), (0.72, 11.5, *DEC), (1.0, 8.0)],
         "head_y": [(0.0, 10.0), (0.3, 13.0), (0.6, 7.0), (1.0, 10.0)],
         "head_z": [(0.0, 3.0), (0.25, 6.0), (0.75, -1.0), (1.0, 3.0)]}
    tk.lk.key(K, cyclic=True)
    LT = {"v2": 2.0, "v3": 3.0}.get(var)
    if var == "v2":
        tk.lk.key({"fl": [(0.0, 0.0), (1.21, 0.0, *ACC2), (1.36, 1.0, *DEC), (1.5, 1.0, *INOUT), (1.64, 0.0),
                          (LT, 0.0)]}, cyclic=False)
    if var == "v3":
        tk.lk.key({"sing": [(0.0, 0.0), (0.2, 0.0, *INOUT), (0.55, 1.0), (0.95, 1.0, *INOUT), (1.15, 0.0), (LT, 0.0)],
                   "stop": [(0.0, 0.0), (1.1, 0.0, *INOUT), (1.4, 1.0), (2.25, 1.0, *INOUT), (2.6, 0.0), (LT, 0.0)],
                   "bow": [(0.0, 0.0), (1.45, 0.0, *INOUT), (1.75, 1.0), (1.95, 1.0, *INOUT), (2.25, 0.0), (LT, 0.0)]},
                  cyclic=False)


def solve_bard(var):
    L = 1.0
    LT = {"v2": 2.0, "v3": 3.0}.get(var, 1.0)
    FLOURISH = np.array([-11.0, -10.0, -4.0])
    HEART = np.array([1.4, -7.4, -3.5])

    def solve(t):
        tv = t % LT
        t %= L
        fl = lk.smoothstep(max(0.0, min(1.0, c("fl", tv)))) if var == "v2" else 0.0
        st = lk.smoothstep(max(0.0, min(1.0, c("stop", tv)))) if var == "v3" else 0.0
        bw = c("bow", tv) if var == "v3" else 0.0
        sing = c("sing", tv) if var == "v3" else 0.0
        ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": tk.SEAT_ROOT},
              "torso": {"rot": (c("torso_x", t) - 3.0 * fl + 22.0 * bw - 4.0 * sing,
                                c("torso_y", t) * (1 - st) - 5.0 * fl, c("torso_z", t) * (1 - st) - 3.0 * fl),
                        "scale": lk.breath_scale(lk.breath(t, L, phase=0.3), 0.01 + 0.02 * sing)}}
        ch["head"] = {"rot": (c("head_x", t) - 9.0 * fl + 16.0 * bw - 4.0 * st - 22.0 * sing,
                              c("head_y", t) * (1 - st) * (1 - sing) - 6.0 * fl, c("head_z", t) - 4.0 * fl + 4.0 * sing)}
        s = c("strum", t)
        strum = STRUM_TOP + (STRUM_BOT - STRUM_TOP) * s + np.array([0.0, 0.0, -0.9]) * c("strum_off", t)
        strum = strum * (1 - fl) + FLOURISH * fl
        strum = strum * (1 - st) + HEART * st
        lk.arm_ik_local(ch, "right", strum, (-0.9, 0.5 + 0.3 * st, 0.45), solve.prev, twist=10.0 * (s - 0.5) * (1 - st))
        fret = NECK_FRET + NECK_DIR * c("chord", t) + np.array([0.0, -0.5, -0.3]) * c("fret_lift", t)
        fret = fret + np.array([-1.2, 2.2, 1.0]) * st
        lk.arm_ik_local(ch, "left", fret, (0.6, 0.9, 0.2), solve.prev)
        yr = lk.lagged_rate(lambda u: c("torso_y", u % L), t)
        ch["cloak"] = {"rot": lk.cloak(1.5, 0.0, 0.0, yr, k_yaw=0.1)}
        return tk.seated_legs(ch)
    solve.prev = {}
    return solve


def scene_stool():
    objs = tk.lk.scene("settler_innkeeper.png")
    tk.lk.box("stool", (-5, 16, -4), (10, 8, 8), (0.42, 0.3, 0.18, 1))
    return objs


CLIPS = {
    "inn_welcome": dict(const="INN_WELCOME", L=1.8, loop=False, keys=keys_welcome, solve=solve_welcome,
                        tex="settler_innkeeper.png", keep=(0.0, 1.6, 1.8),
                        note="INN_WELCOME 1.8 s one-shot (innkeeper envelope 36 ticks; village WELCOME 32 "
                             "ticks, no envelope -> on rest by 1.6 s): dip, arms open wide, two waves, "
                             "hand to the heart with a small bow."),
    "eat": dict(const="EAT", L=1.2, loop=True, keys=lambda: keys_eat(False), solve=lambda: solve_eat(False),
                tex="settler_farmer.png", keep=(0.0,) + BITES + (1.2,), checks=eat_checks(False),
                note="EAT 1.2 s loop; bites t=0.25/0.70 s = ticks 5/14 (EatFromHearthGoal, settler_eat); "
                     "meal drawn at the right palm; IDLE layered under via IDLE_UNDER_ACTION."),
    "eat__v2": dict(const="EAT__V2", L=2.4, loop=True, keys=lambda: keys_eat(True), solve=lambda: solve_eat(True),
                    tex="settler_farmer.png", keep=(0.0, 0.25, 0.70, 1.2, 1.45, 1.9, 2.4), checks=eat_checks(True),
                    variant_of="animation.settler.eat",
                    note="EAT variant (2 cycles, bites 0.25/0.70/1.45/1.90 by contract): between the second "
                         "and third bite leans back, eyes shut, pats the belly twice."),
    "village_chat": dict(const="VILLAGE_CHAT", L=2.4, loop=True, keys=lambda: keys_chat(False), solve=solve_talk,
                         tex="settler_farmer.png",
                         note="VILLAGE_CHAT 2.4 s loop (SocialPair speaker; 96-tick turns = 2 loops), frame 0 "
                              "on rest; emphasis beats 0.6 / 1.8 (the listener nods on them)."),
    "village_chat__v2": dict(const="VILLAGE_CHAT__V2", L=2.4, loop=True, keys=lambda: keys_chat(True),
                             solve=solve_talk, tex="settler_farmer.png", variant_of="animation.settler.village_chat",
                             note="VILLAGE_CHAT variant: mimes a big axe swing landing on 1.8, laughs at it."),
    "village_listen": dict(const="VILLAGE_LISTEN", L=2.4, loop=True, keys=keys_listen, solve=solve_talk,
                           tex="settler_courier.png",
                           note="VILLAGE_LISTEN 2.4 s loop (SocialPair listener): nods ON the speaker's beats "
                                "0.6 / 1.8, a chuckle with a shoulder shake after 1.8."),
    "bard_play": dict(const="BARD_PLAY", L=1.0, loop=True, keys=lambda: keys_bard(None), solve=lambda: solve_bard(None),
                      scene=scene_stool, bones=BARD_EXPORT, keep=(0.0, 0.15, 0.4, 0.65, 0.9, 1.0),
                      note="BARD_PLAY 1.0 s loop, seated upper body; down-strokes 0.15/0.65, up 0.40/0.90."),
    "bard_play__v2": dict(const="BARD_PLAY__V2", L=2.0, loop=True, keys=lambda: keys_bard("v2"),
                          solve=lambda: solve_bard("v2"), scene=scene_stool, bones=BARD_EXPORT,
                          variant_of="animation.settler.bard_play",
                          note="BARD_PLAY variant (2 cycles): a flung-out flourish on 1.36, head thrown back."),
    "bard_play__v3": dict(const="BARD_PLAY__V3", L=3.0, loop=True, keys=lambda: keys_bard("v3"),
                          solve=lambda: solve_bard("v3"), scene=scene_stool, bones=BARD_EXPORT,
                          variant_of="animation.settler.bard_play",
                          note="BARD_PLAY variant (3 cycles): sings a line head back, stops for a bow with the "
                               "hand on the heart, plays on."),
}


def main():
    for slug, spec in CLIPS.items():
        if ONLY and slug not in ONLY and slug.split("__")[0] not in ONLY:
            continue
        s = dict(spec)
        s.update(slug=slug, script="author_remakes.py", solve=spec["solve"](),
                 scene=spec.get("scene", lambda tex=spec.get("tex"): tk.lk.scene(tex)),
                 bones=spec.get("bones", tk.mcrig.EXPORT_BONES), mugs=[],
                 cams=tk.seated_cams if slug.startswith("bard") else None)
        tk.run_clip(s, A)


if __name__ == "__main__":
    main()
