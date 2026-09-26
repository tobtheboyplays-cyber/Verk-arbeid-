"""The ARCHER shot cycle (owner request 2026-09-26), anim overkill lane -- a RIGHT-HANDED archer.

    blender -b --factory-startup --python archer.py -- [--fast|--three] [--no-export] CONST ... | LOWSTILL

The vanilla bow (MAINHAND item) is RENDERED IN THE LEFT HAND in LOW_READY and DRAWING by the weapons
lane's SettlerBowHold, with displays solved HERE against these arm keys and published to
anim-overkill/archer_low_ready.json and archer_draw_left.json. Convention (agreed, no mirroring):
    translateToHand(LEFT) (incl. the left_item wrist) . Rx(-90) Ry(180) T(-1,2,-10) px .
    T(tr) Rx(a) Ry(b) Rz(c) (JOML rotationXYZ) . S(scale) . T(-8,-8,-8)
The RIGHT hand draws the string to the right jaw and takes arrows from the quiver on the back (right
shoulder). Where a clip crosses the LOW <-> DRAW display switch, the left_item wrist (rot + pos) carries
the difference and eases it out, so the bow never pops.

ARCHER_STANCE   3.20 s loop  LOW READY (owner reference): hunched a little, shoulders rolled forward, head
                             up; both arms hang down and forward, the bow fist at the belt line well in front
                             of the left hip, the bow across the front of the hips tilted down, the arrow
                             nocked, the right hand on the string by the right hip / belly, the arrow pointing
                             forward and DOWN ~35 deg. Breathing, a slow scan.
ARCHER_DRAW     1.00 s       low ready -> the archer OPENS into the shot (the LEFT shoulder to the target, head
                             round over it) -> bow up 0.00-0.35 -> string drawn 0.35-0.90 to the right-jaw anchor
                             (elbow high) -> breath settle; pull frames on the server draw clock.
ARCHER_DRAW__v2 1.00 s       quick, under pressure: snap raise, anchor by 0.70, tense hold.
ARCHER_RELOAD   0.75 s       release follow-through + bow recoil, the right hand over the shoulder into the
                             quiver, one arrow drawn (prop), brought round and NOCKED in low ready.
"""
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import bpy  # noqa: E402
import numpy as np  # noqa: E402
from mathutils import Matrix  # noqa: E402

import rolekit as rk  # noqa: E402

ck = rk.ck
hsrig, mcrig = rk.hsrig, rk.mcrig
c = hsrig.ctrl
S, ACC, DEC, DEC3, LIN = rk.SMO, rk.ACC, rk.DEC, rk.DEC3, rk.LIN
FPS = hsrig.FPS
OUT = r"C:\Users\tobia\Hearthstead-Claude\anim-overkill"
HOLD = json.load(open(r"C:\Users\tobia\Hearthstead-Claude\tools\weapons\settler_bow_hold.json"))
LM = HOLD["model_landmarks_item_px"]
GRIP = np.array(LM["grip"], float)
UPPER, LOWER = np.array(LM["upper_tip"], float), np.array(LM["lower_tip"], float)
NOCK = {k: np.array(v, float) for k, v in LM["string_nock"].items()}
ARROW_ITEM = np.array([-1.0, 1.0, 0.0]) / math.sqrt(2.0)      # nock -> point, item px
SCALE = 1.098
TEX = os.path.join(ck.REF, "item")
BOW_PNG = {"rest": os.path.join(TEX, "bow.png"), "p0": os.path.join(TEX, "bow_pulling_0.png"),
           "p1": os.path.join(TEX, "bow_pulling_1.png"), "p2": os.path.join(TEX, "bow_pulling_2.png")}
ARROW_PNG = os.path.join(OUT, "props", "arrow_preview.png")
BA = ("al_x", "al_y", "al_z", "el_l", "tw_l")          # bow arm FK props

# ------------------------------------------------------------------ stances
LOW_FEET = (np.array([-2.9, 24.0, 1.0]), np.array([2.9, 24.0, -0.8]))
LOW = {"hip_x": 0.0, "hip_y": -1.2, "hip_z": 0.6, "hip_p": 0.0, "hip_yaw": 5.0, "hip_r": 0.0,
       "sp_x": 12.0, "sp_y": 13.0, "sp_z": 0.0, "sp_lift": 0.0, "hd_x": -12.0, "hd_y": -16.0, "hd_z": 0.0,
       "ck_x": 4.0, "ck_z": 0.0, "wr_x": 0.0, "wr_y": 0.0, "wr_z": 0.0, "wl_x": 0.0, "wl_y": 0.0, "wl_z": 0.0,
       "gl_w": 0.0, "gl_s": 0.0, "gr_s": 0.0}
LOW_GRIP = np.array([2.2, 11.0, -8.4])      # v5: fists 5-6 px in front of the (turned) torso, hip height        # bow fist: belt line, well in front of the left hip
LOW_ARROW = np.array([0.25, 0.60, -0.76])     # nocked arrow: forward, a little left, DOWN ~34 deg
LOW_AXIS = np.array([-0.97, 0.12, -0.20])     # lower -> upper tip: across the hips, tilted down
DRAW_BODY = {"hip_yaw": 52.0, "sp_y": 28.0, "sp_x": 2.0, "hip_y": -0.6, "hip_z": 0.0, "hd_y": -80.0,
             "hd_x": 2.0, "hd_z": 4.0}
DRAW_FEET_D = {"fl_x": -1.4, "fl_z": -2.2, "fr_x": 0.6, "fr_z": 2.4}   # left foot toward the target
ANCHOR_HEAD = np.array([-4.9, -2.4, -1.0])   # right jaw / cheek (head-local)
DRAW_AXIS = np.array([-0.16, -0.98, 0.06])   # bow upright, a little cant
DRAW_ARROW = np.array([0.0, -0.03, -1.0])
QUIVER_TORSO = np.array([-2.0, -11.2, 5.0])
LOW_NOCK_LIFT = np.array([0.0, -1.5, 0.0])     # the right hand pinches arrow + string, 1.5 px above the string centre  # quiver mouth behind the RIGHT shoulder (torso-local)


# ------------------------------------------------------------------ display maths (agreed convention)
def pre_chain(base):
    return base @ mcrig.T(0, -4, 0) @ mcrig.mat4(mcrig.rx(-math.pi / 2)) @ mcrig.mat4(mcrig.ry(math.pi)) \
        @ mcrig.T(-1, 2, -10)


def bow_matrix(base, D):
    if isinstance(D, np.ndarray):                  # weapons lane v5 longbow: stretched, not a plain T.R.S
        return pre_chain(base) @ D
    (a, b, cc), (tx, ty, tz), s = D
    R = mcrig.rx(math.radians(a)) @ mcrig.ry(math.radians(b)) @ mcrig.rz(math.radians(cc))
    return pre_chain(base) @ mcrig.T(tx, ty, tz) @ mcrig.mat4(R, s=s) @ mcrig.T(-8, -8, -8)


def rot_xyz_deg(R):
    b = math.asin(max(-1.0, min(1.0, R[0, 2])))
    a = math.atan2(-R[1, 2], R[2, 2])
    cc = math.atan2(-R[0, 1], R[0, 0])
    return tuple(math.degrees(v) for v in (a, b, cc))


def solve_display(base_world, grip_world, axis_world, arrow_world, scale=SCALE):
    u_i = (UPPER - LOWER) / np.linalg.norm(UPPER - LOWER)
    a_i = ARROW_ITEM - u_i * float(ARROW_ITEM @ u_i)
    a_i /= np.linalg.norm(a_i)
    Bi = np.stack([u_i, a_i, np.cross(u_i, a_i)], axis=1)
    u_w = axis_world / np.linalg.norm(axis_world)
    a_w = arrow_world - u_w * float(arrow_world @ u_w)
    a_w /= np.linalg.norm(a_w)
    Bw = np.stack([u_w, a_w, np.cross(u_w, a_w)], axis=1)
    R_world = Bw @ Bi.T
    P = pre_chain(base_world)
    Rd = np.linalg.inv(P[:3, :3]) @ R_world
    loc = np.linalg.inv(P) @ np.array([*grip_world, 1.0])
    tr = loc[:3] - Rd @ (scale * (GRIP - 8.0))
    return (rot_xyz_deg(Rd), tuple(float(v) for v in tr), scale)


def bow_rel(D):
    return bow_matrix(np.eye(4), D)


def wrist_comp(show_D, look_D):
    """left_item (rot deg, posVec) so display show_D looks like look_D (unkeyed wrist). The displays may
    differ by a stretch (v5 longbow), so the wrist takes the nearest ROTATION (polar) and the translation
    that keeps the bow's centre (mid of the tips) where it was."""
    Q = bow_rel(look_D) @ np.linalg.inv(bow_rel(show_D))
    U, _, Vt = np.linalg.svd(Q[:3, :3])
    R = U @ Vt
    mid = np.array([*((UPPER + LOWER) * 0.5), 1.0])
    Q = Q.copy()
    Q[:3, 3] = (bow_rel(look_D) @ mid)[:3] - R @ (bow_rel(show_D) @ mid)[:3]
    rot = hsrig.euler_deg_continuous(R, None)
    pos = Q[:3, 3] - np.array([0.0, 6.0, 0.0]) + R @ np.array([0.0, 6.0, 0.0])
    return rot, (float(pos[0]), float(-pos[1]), float(pos[2]))


# ------------------------------------------------------------------ controls + solve
STATE = {}


def body_keys(L, vals, over=None, loop=False):
    K = {p: [(0.0, v), (L, v)] for p, v in vals.items()}
    for p in ck.FEET_PROPS + ("wlp_x", "wlp_y", "wlp_z", "nk", "pull", "dsp", "rh_x", "rh_y", "rh_z"):
        K.setdefault(p, [(0.0, 0.0), (L, 0.0)])
    K.update(over or {})
    ck.key_all(K, L, loop)


def fk_channels(t):
    g = lambda p: c(p, t)  # noqa: E731
    ch = ck.body_channels(g)
    ch["left_arm"] = {"rot": (g("al_x"), g("al_y"), g("al_z"))}
    ch["left_forearm"] = {"rot": (g("el_l"), g("tw_l"), 0.0)}
    ch["left_item"] = {"rot": (g("wl_x"), g("wl_y"), g("wl_z")), "pos": (g("wlp_x"), g("wlp_y"), g("wlp_z"))}
    ch["right_arm"] = {"rot": (g("ar_x"), g("ar_y"), g("ar_z"))}
    ch["right_forearm"] = {"rot": (g("el_r"), 0.0, 0.0)}
    ch["cloak"] = {"rot": (g("ck_x"), 0.0, g("ck_z"))}
    return ch


def bow_frame(world, draw_display):
    return bow_matrix(ck.item_base(world, "left"), STATE["D_DRAW"] if draw_display else STATE["D_LOW"])


def nock_point(world, draw_display, pull):
    keys = [(0.0, NOCK["pulling_0"]), (0.65, NOCK["pulling_1"]), (0.90, NOCK["pulling_2"])] if draw_display \
        else [(0.0, NOCK["rest"])]
    p = keys[-1][1]
    for (ta, a), (tb, b) in zip(keys, keys[1:]):
        if pull <= tb:
            p = a + (b - a) * max(0.0, min(1.0, (pull - ta) / (tb - ta)))
            break
    return mcrig.xform(bow_frame(world, draw_display), p) + (0.0 if draw_display else LOW_NOCK_LIFT)


def make_solve(L, loop, feet):
    prev = {}

    def w(t):
        return t % L if loop else min(max(t, 0.0), L)

    def solve(t):
        t = w(t)
        ch = fk_channels(t)
        world = mcrig.pose_matrices(ch)
        drawd = c("dsp", t) > 0.5
        nk = max(0.0, min(1.0, c("nk", t)))
        tgt = np.array([c("rh_x", t), c("rh_y", t), c("rh_z", t)])
        if nk > 1e-4:
            tgt = tgt * (1 - nk) + nock_point(world, drawd, max(0.0, min(1.0, c("pull", t)))) * nk
        tl = mcrig.xform(np.linalg.inv(world["torso"]), tgt)
        pole = np.array([-0.55, -0.55, 0.62]) if drawd else np.array([-0.9, 0.3, 0.5])
        r, flex, _ = mcrig.two_bone(np.array([-6.0, -10.0, 0.0]), tl, mcrig.UPPER_ARM, 6.0, pole, -1)
        rot = hsrig.euler_deg_continuous(r, prev.get("r"))
        prev["r"] = rot
        ch["right_arm"] = {"rot": tuple(rot)}
        ch["right_forearm"] = {"rot": (math.degrees(flex), 0.0, 0.0)}
        fr = feet[0] + np.array([c("fr_x", t), -c("fr_y", t), c("fr_z", t)])
        fl = feet[1] + np.array([c("fl_x", t), -c("fl_y", t), c("fl_z", t)])
        ck.legs_ik(ch, fr, fl, key=id(solve))
        return ch
    return solve


def bow_arm_goal(t, hand, seed):
    sol, err = rk.solve_arm6(rk.body_at(t), "left", hand, None, seed=seed, wrist=False, w_seed=0.001, pole_out=2.0)
    return sol, err


# ------------------------------------------------------------------ the two displays
def solve_low():
    body_keys(1.0, LOW)
    sol, err = bow_arm_goal(0.0, LOW_GRIP, [-40.0, 10.0, 10.0, -20.0, 0.0, 0.0])
    ch = rk.body_at(0.0)
    ch["left_arm"] = {"rot": tuple(sol[:3])}
    ch["left_forearm"] = {"rot": (sol[3], sol[4], 0.0)}
    world = mcrig.pose_matrices(ch)
    palm = mcrig.xform(world["left_forearm"], (0, 6, 0))
    D_mine = solve_display(ck.item_base(world, "left"), palm, LOW_AXIS, LOW_ARROW)
    # weapons lane final (their clearance audit, tools/weapons/low_ready_final.json): my display turned 4 deg
    # yaw / 2 deg roll about the grip, scale 0.95, the REST string frame + a separate nocked arrow sprite
    fin = json.load(open(r"C:\Users\tobia\Hearthstead-Claude\tools\weapons\low_ready_final.json"))
    raw = fin["display_raw_left"]
    D = (tuple(raw["rotation"]), tuple(raw["translation"]), float(raw["scale"][0]))
    v5 = r"C:\Users\tobia\Hearthstead-Claude\tools\weapons\low_ready_v5_anim.json"
    if os.path.exists(v5):                         # weapons lane v5: the LONGBOW display on the v5 arm pose
        v5d = json.load(open(v5))
        D = np.array(v5d["px_local"]["bow"], float)
        STATE["A_LOW"] = np.array(v5d["px_local"]["arrow"], float)
    if os.environ.get("ARCHER_LOW_MINE"):          # before the weapons lane re-solves for a new arm pose
        D = D_mine
    STATE["D_LOW"], STATE["low_sol"], STATE["D_LOW_MINE"] = D, sol, D_mine
    STATE.setdefault("D_DRAW", D)
    nock = mcrig.xform(bow_matrix(ck.item_base(world, "left"), D), NOCK["rest"]) + LOW_NOCK_LIFT
    json.dump({"hand": "LEFT", "left_arm": [round(v, 3) for v in sol[:3]],
               "left_forearm": [round(sol[3], 3), round(sol[4], 3), 0.0], "left_item": [0.0, 0.0, 0.0],
               "fist_world_px": [round(float(v), 3) for v in palm],
               "nock_pulling_0_world_px": [round(float(v), 3) for v in nock],
               "right_hand": "IK onto the nock (clip keys, see archer_stance.animation.json)",
               "low_ready_display": "tools/weapons/low_ready_v5_anim.json (px_local.bow / arrow)"
               if isinstance(D, np.ndarray) else {"rotation": [round(v, 3) for v in D[0]],
                                                  "translation": [round(v, 4) for v in D[1]], "scale": [SCALE] * 3},
               "convention": "translateToHand(LEFT) . Rx(-90) Ry(180) T(-1,2,-10) . T(tr) rotationXYZ S T(-8)",
               "body_ctrl": LOW}, open(os.path.join(OUT, "archer_low_ready.json"), "w"), indent=1)
    print("LOW_READY fist", np.round(palm, 2), "nock", np.round(nock, 2), "arm", [round(v, 1) for v in sol[:5]],
          "cost", round(err, 2))
    return sol, D


def solve_draw(t_anchor, fixed=True):
    """Left arm at full draw so the pulling_2 nock sits at the right-jaw anchor. The DRAWING display is
    solved ONCE (prepare_displays, fixed=False: canonical full-draw body) and then held fixed -- the game
    renders one drawing display; every clip only moves the arm to it."""
    ch = rk.body_at(t_anchor)
    head = mcrig.pose_matrices(ch)["head"]
    anchor = mcrig.xform(head, ANCHOR_HEAD)
    grip = anchor + np.array([0.0, 0.0, -10.0])
    sol = list(STATE.get("draw_sol", [-85.0, 0.0, 0.0, -5.0, 0.0, 0.0]))
    for _ in range(6):
        sol, err = bow_arm_goal(t_anchor, grip, sol)
        ch2 = dict(ch)
        ch2["left_arm"] = {"rot": tuple(sol[:3])}
        ch2["left_forearm"] = {"rot": (sol[3], sol[4], 0.0)}
        wd = mcrig.pose_matrices(ch2)
        palm = mcrig.xform(wd["left_forearm"], (0, 6, 0))
        D = STATE["D_DRAW"] if fixed else solve_display(ck.item_base(wd, "left"), palm, DRAW_AXIS, DRAW_ARROW)
        nock = mcrig.xform(bow_matrix(ck.item_base(wd, "left"), D), NOCK["pulling_2"])
        grip = grip + (anchor - nock) * 0.9
    if not fixed:
        STATE["D_DRAW"], STATE["draw_sol"] = D, sol
        json.dump({"hand": "LEFT", "left_arm": [round(v, 3) for v in sol[:3]],
                   "left_forearm": [round(sol[3], 3), round(sol[4], 3), 0.0], "left_item": [0.0, 0.0, 0.0],
                   "fist_world_px": [round(float(v), 3) for v in palm],
                   "anchor_world_px": [round(float(v), 3) for v in anchor],
                   "body_ctrl": {k: round(float(c(k, t_anchor)), 4) for k in ("hip_x", "hip_y", "hip_z", "hip_p",
                                 "hip_yaw", "hip_r", "sp_x", "sp_y", "sp_z", "sp_lift", "hd_x", "hd_y", "hd_z")},
                   "note": "body at full draw = LOW body_ctrl with DRAW_BODY on top (hips/torso yawed side-on)",
                   "drawing_display_px_deg": {"rotation": [round(v, 3) for v in D[0]],
                                              "translation": [round(v, 4) for v in D[1]], "scale": [SCALE] * 3},
                   "convention": "translateToHand(LEFT) . Rx(-90) Ry(180) T(-1,2,-10) . T(tr) rotationXYZ S T(-8)"},
                  open(os.path.join(OUT, "archer_draw_left.json"), "w"), indent=1)
    print("DRAW nock-anchor", np.round(anchor - nock, 2), "arm", [round(v, 1) for v in sol[:5]], "cost", round(err, 2),
          "fixed" if fixed else "SOLVED")
    return sol, anchor


def key_bow_arm(rows, L, loop=False):
    K = {n: [] for n in BA}
    for t, sol, e in rows:
        for n, v in zip(BA, sol[:5]):
            K[n].append((t, v, *e) if e else (t, v))
    ck.key_all(K, L, loop)


def key_wrist(rows, L):
    K = {n: [] for n in ("wl_x", "wl_y", "wl_z", "wlp_x", "wlp_y", "wlp_z")}
    for t, rot, pos, e in rows:
        for n, v in zip(("wl_x", "wl_y", "wl_z"), rot):
            K[n].append((t, v, *e) if e else (t, v))
        for n, v in zip(("wlp_x", "wlp_y", "wlp_z"), pos):
            K[n].append((t, v, *e) if e else (t, v))
    ck.key_all(K, L, False)


# =========================================================================== clips
def stance():
    L = 3.2
    sol = STATE["low_sol"]
    v = dict(LOW)
    v.update(dict(zip(BA, sol[:5])))
    b = LOW
    over = {
        "sp_lift": [(0.0, 0.0, *S), (0.8, 0.22, *S), (1.6, 0.0, *S), (2.4, 0.24, *S), (3.2, 0.0)],
        "sp_x": [(0.0, b["sp_x"], *S), (0.8, b["sp_x"] - 1.0, *S), (1.6, b["sp_x"] + 0.4, *S), (2.4, b["sp_x"] - 0.8, *S),
                 (3.2, b["sp_x"])],
        "hd_y": [(0.0, b["hd_y"], *S), (0.6, b["hd_y"], *S), (1.1, b["hd_y"] + 14.0, *DEC), (1.6, b["hd_y"] + 12.0, *S),
                 (2.1, b["hd_y"] - 8.0, *DEC), (2.6, b["hd_y"] - 6.0, *S), (3.0, b["hd_y"], *S), (3.2, b["hd_y"])],
        "hd_x": [(0.0, b["hd_x"], *S), (1.1, b["hd_x"] + 2.0, *S), (2.1, b["hd_x"] - 1.0, *S), (3.2, b["hd_x"])],
        "hip_y": [(0.0, b["hip_y"], *S), (1.6, b["hip_y"] - 0.15, *S), (3.2, b["hip_y"])],
        "nk": [(0.0, 1.0), (3.2, 1.0)],
        "al_x": [(0.0, sol[0], *S), (0.8, sol[0] - 1.2, *S), (1.6, sol[0], *S), (2.4, sol[0] - 1.0, *S), (3.2, sol[0])],
    }
    body_keys(L, v, over, loop=True)
    return L, True, make_solve(L, True, LOW_FEET), "3.20 s loop: LOW READY (owner reference), arrow nocked", (), None


def draw(quick=False):
    L = 1.0
    sol0 = STATE["low_sol"]
    up = 0.26 if quick else 0.35
    anc = 0.70 if quick else 0.90
    v = dict(LOW)
    v.update(dict(zip(BA, sol0[:5])))
    over = {}
    for p, target in DRAW_BODY.items():
        a = LOW.get(p, 0.0)
        over[p] = [(0.0, a, *S), (0.06, a + (0.4 if p == "hip_y" else 0.0), *ACC), (up, target, *DEC),
                   (anc, target + (1.0 if p == "sp_x" else 0.0), *S), (L, target)]
    over["sp_lift"] = [(0.0, 0.0, *S), (up, 0.15, *S), (anc - 0.1, 0.45, *S), (anc, 0.4, *S), (L, 0.28)]
    over["sp_z"] = [(0.0, 0.0, *S), (anc, 3.0, *S), (L, 3.0)]
    for p, d in DRAW_FEET_D.items():
        over[p] = [(0.0, 0.0), (0.08, 0.0, *S), (up - 0.02, d, *DEC), (L, d)]
    over["fl_y"] = [(0.0, 0.0), (0.04, 0.0, *DEC), (0.10, 1.6, *S), (up - 0.06, 1.0, *ACC), (up - 0.02, 0.0), (L, 0.0)]
    over["fr_y"] = [(0.0, 0.0), (0.06, 0.0, *DEC), (0.12, 1.2, *S), (up - 0.04, 0.8, *ACC), (up, 0.0), (L, 0.0)]
    over["dsp"] = [(0.0, 1.0), (L, 1.0)]
    over["nk"] = [(0.0, 1.0), (L, 1.0)]
    over["pull"] = [(0.0, 0.0), (L, 1.0)]
    body_keys(L, v, over)
    full, anchor = solve_draw(anc)
    mid, _ = bow_arm_goal(up * 0.5, LOW_GRIP + np.array([1.0, -4.0, -2.4]), sol0)
    key_bow_arm([(0.0, sol0, S), (up * 0.5, mid, S), (up, full, DEC), (anc, full, S),
                 (L, [full[0] - 0.8, full[1], full[2], full[3], full[4], 0.0], None)], L)
    rot0, pos0 = wrist_comp(STATE["D_DRAW"], STATE["D_LOW"])
    key_wrist([(0.0, rot0, pos0, S), (up * 0.9, (0, 0, 0), (0, 0, 0), DEC), (L, (0, 0, 0), (0, 0, 0), None)], L)
    return (L, False, make_solve(L, False, LOW_FEET), ("1.00 s one-shot (%s): low ready -> bow up by %.2f -> string at the "
            "right-jaw anchor by %.2f (pulling_1 0.65, pulling_2 0.90 = server pull) -> settle; release = server tick"
            % ("quick" if quick else "calm", up, anc)), (up, anc), None)


def reload_():
    L = 0.75
    sol0 = STATE["low_sol"]
    v = dict(LOW)
    v.update(dict(zip(BA, sol0[:5])))
    over = {}
    for p, target in DRAW_BODY.items():
        a = LOW.get(p, 0.0)
        over[p] = [(0.0, target, *S), (0.10, target + (3.0 if p == "sp_x" else 0.0), *DEC), (0.40, a, *S), (L, a)]
    over["sp_lift"] = [(0.0, 0.28, *S), (0.40, 0.0, *S), (L, 0.0)]
    over["sp_z"] = [(0.0, 3.0, *S), (0.35, -2.0, *S), (0.55, 0.0, *S), (L, 0.0)]
    for p, d in DRAW_FEET_D.items():
        over[p] = [(0.0, d), (0.30, d, *S), (0.52, 0.0, *DEC), (L, 0.0)]
    over["fl_y"] = [(0.0, 0.0), (0.26, 0.0, *DEC), (0.34, 1.4, *S), (0.48, 0.8, *ACC), (0.52, 0.0), (L, 0.0)]
    over["fr_y"] = [(0.0, 0.0), (0.30, 0.0, *DEC), (0.37, 1.0, *S), (0.48, 0.6, *ACC), (0.52, 0.0), (L, 0.0)]
    over["nk"] = [(0.0, 0.0), (0.58, 0.0, *S), (0.70, 1.0, *DEC), (L, 1.0)]
    body_keys(L, v, over)
    full, anchor = solve_draw(0.0)
    head0 = mcrig.pose_matrices(rk.body_at(0.0))["head"]
    fthru = mcrig.xform(head0, ANCHOR_HEAD + np.array([-1.8, 0.6, 3.6]))
    quiver = mcrig.xform(mcrig.pose_matrices(rk.body_at(0.30))["torso"], QUIVER_TORSO)
    pulled = mcrig.xform(mcrig.pose_matrices(rk.body_at(0.40))["torso"], QUIVER_TORSO + np.array([-0.6, -3.8, -0.8]))
    near = np.array([-2.2, 8.6, -6.4])
    ck.key_all({"rh_x": [(0.0, anchor[0]), (0.08, fthru[0], *DEC), (0.18, fthru[0] - 0.6, *S), (0.30, quiver[0], *DEC),
                         (0.36, quiver[0], *S), (0.44, pulled[0], *S), (0.58, near[0], *S), (L, near[0])],
                "rh_y": [(0.0, anchor[1]), (0.08, fthru[1], *DEC), (0.18, fthru[1] + 0.4, *S), (0.30, quiver[1], *DEC),
                         (0.36, quiver[1] + 0.4, *S), (0.44, pulled[1], *S), (0.58, near[1], *S), (L, near[1])],
                "rh_z": [(0.0, anchor[2]), (0.08, fthru[2], *DEC), (0.18, fthru[2] - 0.4, *S), (0.30, quiver[2], *DEC),
                         (0.36, quiver[2], *S), (0.44, pulled[2], *S), (0.58, near[2], *S), (L, near[2])]}, L, False)
    rec = [full[0] + 6.0, full[1], full[2] - 3.0, full[3] - 4.0, full[4], 0.0]
    key_bow_arm([(0.0, full, DEC), (0.10, rec, S), (0.22, full, S), (0.48, sol0, S), (L, sol0, None)], L)
    rotA, posA = wrist_comp(STATE["D_LOW"], STATE["D_DRAW"])
    key_wrist([(0.0, rotA, posA, S), (0.22, rotA, posA, S), (0.48, (0, 0, 0), (0, 0, 0), DEC),
               (L, (0, 0, 0), (0, 0, 0), None)], L)
    props = [{"hand": "mainhand", "item": "minecraft:arrow", "from": 0.34, "to": 0.66, "over_real": True}]
    return (L, False, make_solve(L, False, LOW_FEET), "0.75 s one-shot = VOLLEY_RECOVERY_TICKS 15: release follow-"
            "through + bow recoil 0.00-0.22, quiver 0.30-0.36, arrow drawn 0.44, nocked by 0.70", (0.08, 0.30, 0.44, 0.58, 0.70),
            props)


def patrol():
    """ARCHER_PATROL: the low-ready carry as the upper-body overlay over WALK / run (head, torso, arms)."""
    L = 3.6
    sol = STATE["low_sol"]
    v = dict(LOW)
    v.update(dict(zip(BA, sol[:5])))
    v["sp_x"] = 16.0
    bob, ax = [], []
    n = 16                                   # a light bounce, 0.225 s per half step
    for i in range(n + 1):
        t = L * i / n
        up = i % 2 == 0
        bob.append((t, 0.0 if up else -0.35, *S))
        ax.append((t, sol[0] + (0.0 if up else 2.2), *S))
    over = {
        "sp_lift": bob, "al_x": ax,
        "sp_x": [(0.0, 16.0, *S), (1.8, 17.0, *S), (3.6, 16.0)],
        "hd_x": [(0.0, -16.0, *S), (1.8, -17.0, *S), (3.6, -16.0)],
        "hd_y": [(0.0, -6.0, *S), (0.9, -6.0, *S), (1.4, 12.0, *DEC), (2.0, 10.0, *S), (2.5, -14.0, *DEC),
                 (3.0, -12.0, *S), (3.4, -6.0, *S), (3.6, -6.0)],
        "nk": [(0.0, 1.0), (L, 1.0)],
    }
    body_keys(L, v, over, loop=True)
    contract = "3.60 s loop overlay (head/torso/arms): the low-ready carry over WALK / run, a light bounce"
    return L, True, make_solve(L, True, LOW_FEET), contract, (), None


CLIPS = {"ARCHER_STANCE": stance, "ARCHER_DRAW": lambda: draw(False), "ARCHER_DRAW__V2": lambda: draw(True),
         "ARCHER_RELOAD": reload_, "ARCHER_PATROL": patrol}
OVERLAY_BONES = ["head", "torso", "left_arm", "left_forearm", "right_arm", "right_forearm", "left_item"]


# =========================================================================== bake / preview
def build_scene():
    objs = ck.build("settler_archer.png", right=None)
    STATE["meshes"] = {}
    for key in ("draw", "low"):
        D = STATE["D_DRAW"] if key == "draw" else STATE["D_LOW"]
        e = bpy.data.objects.new("bow:" + key, None)
        bpy.context.scene.collection.objects.link(e)
        e.parent = objs["left_item"]
        e.matrix_basis = Matrix(bow_matrix(mcrig.T(0, -6, 0), D).tolist())
        for fr, png in BOW_PNG.items():
            STATE["meshes"][(key, fr)] = hsrig.sprite_mesh(f"mesh:bow_{key}_{fr}", png, e)
    if "A_LOW" in STATE:
        al = bpy.data.objects.new("arrow:low", None)
        bpy.context.scene.collection.objects.link(al)
        al.parent = objs["left_item"]
        al.matrix_basis = Matrix((pre_chain(mcrig.T(0, -6, 0)) @ STATE["A_LOW"]).tolist())
        STATE["meshes"]["arrow_low"] = hsrig.sprite_mesh("mesh:arrow_low", ARROW_PNG, al)
    a = bpy.data.objects.new("arrow:right", None)
    bpy.context.scene.collection.objects.link(a)
    a.parent = objs["right_item"]
    a.matrix_basis = Matrix(ck.display_matrix(mcrig.T(0, -6, 0), ck.HANDHELD, True).tolist())
    STATE["meshes"]["arrow"] = hsrig.sprite_mesh("mesh:arrow", ARROW_PNG, a)
    q = hsrig.cuboid_mesh("mesh:quiver", [((-3.5, -8.0, 3.5), (3, 6, 3), (96, 0), False, 0.0),
                                          ((-3.5, -11.0, 3.5), (3, 3, 0.5), (99, 3), False, 0.0),
                                          ((-3.5, -11.0, 6.0), (3, 3, 0.5), (99, 3), False, 0.0)])
    q.materials.append(hsrig._material("quiver", colour=(0.45, 0.28, 0.14, 1)))
    qo = bpy.data.objects.new("mesh:quiver", q)
    bpy.context.scene.collection.objects.link(qo)
    qo.parent = objs["torso"]
    craft = os.path.abspath(os.path.join(HERE, "..", "craft"))
    if craft not in sys.path:
        sys.path.insert(0, craft)
    import propkit  # noqa: E402
    for m in list(STATE["meshes"].values()):
        propkit.recolour_sprite(m.name)
    return objs


def key_visibility(n, state_fn):
    for f in range(n):
        st = state_fn(f)
        dk, fk, arrow = st[:3]
        nocked = st[3] if len(st) > 3 else False
        for key, m in STATE["meshes"].items():
            vis = arrow if key == "arrow" else (nocked and dk == "low") if key == "arrow_low" else key == (dk, fk)
            m.hide_render = not vis
            m.keyframe_insert("hide_render", frame=f)


def pull_frame(pull):
    return "p2" if pull >= 0.9 else ("p1" if pull >= 0.65 else "p0")


def prepare_displays():
    if STATE.get("prepared"):
        body_keys(1.0, LOW)
        return
    STATE["prepared"] = True
    solve_low()
    body_keys(1.0, dict(LOW, **DRAW_BODY))
    solve_draw(0.0, fixed=False)


def run(const, args):
    prepare_displays()
    fn = CLIPS[const]
    objs = build_scene()
    L, loop, solve, contract, keep, props = fn()
    times, samples = hsrig.bake(solve, L, objs)
    draw_disp = const.startswith("ARCHER_DRAW")
    n = len(times)

    def state(f):
        t = times[min(f, n - 1)]
        if draw_disp:
            return "draw", pull_frame(t / L), False
        arrow = bool(props) and props[0]["from"] <= t <= props[0]["to"]
        nocked = const != "ARCHER_RELOAD" or t > 0.66
        return "low", "rest", arrow, nocked
    key_visibility(n, state)
    checks = {"clip": const, "length": L, "loop": loop, "contract": contract,
              "low_display": "v5 matrix" if isinstance(STATE["D_LOW"], np.ndarray) else STATE["D_LOW"],
              "draw_display": "matrix" if isinstance(STATE["D_DRAW"], np.ndarray) else STATE["D_DRAW"]}
    checks.update(ck.ground_report(samples))
    print("CHECKS", json.dumps(checks)[:300])
    if args["export"]:
        doc, e = ck.export(const, L, loop, times, samples, keep_times=keep, write=False,
                           bones=OVERLAY_BONES if const == "ARCHER_PATROL" else None,
                           meta={"source": "tools/blender/pipeline/clips/roles/archer.py (Blender "
                                           + bpy.app.version_string + ")", "contract": contract,
                                 "lane": "anim overkill 2026-09-26 (archer shot cycle, right-handed archer)"},
                           out_dir=None)
        if props:
            doc["animations"]["animation.settler." + const.lower()]["hearthstead_props"] = props
        ck.ex.write(doc, os.path.join(ck.ANIM_DIR, const.lower() + ".animation.json"))
        print("EXPORTED", const.lower(), "roundtrip", round(e, 4))
    ck.write_report(const.lower(), checks)
    if args.get("three"):
        rk.preview3(const.lower(), L, loop)
    elif args["fast"] or args["full"]:
        ck.preview(const.lower(), L, args, loop=loop)
    return samples


def low_still():
    prepare_displays()
    objs = build_scene()
    L, loop, solve, *_ = stance()
    hsrig.bake(solve, 0.05, objs)
    key_visibility(4, lambda f: ("low", "rest", False, True))
    d = ck.out_dir("archer_low_ready_still")
    ck._setup_workbench((520, 640))
    cams = {"front": hsrig.camera("s_front", (0.0, -3.4, 1.25), (0.0, -0.2, 0.95), lens=40),
            "front34": hsrig.camera("s_f34", (-2.3, -2.6, 1.35), (0.0, -0.2, 0.95), lens=40),
            "side": hsrig.camera("s_side", (-3.4, -0.3, 1.2), (0.0, -0.2, 0.95), lens=40)}
    sc = bpy.context.scene
    paths = []
    for name, cam in cams.items():
        sc.camera = cam
        sc.frame_set(0)
        sc.render.filepath = os.path.join(d, f"still_{name}.png")
        bpy.ops.render.render(write_still=True)
        paths.append(sc.render.filepath)
    print("STILLS", paths)


def sequence(args):
    """One reel: low ready -> draw -> release -> reload -> quick draw -> reload -> low ready -> walk and
    run in low ready (the patrol overlay over the real WALK / WALK_HURRIED legs)."""
    import export_mc_clip as ex
    prepare_displays()
    plan = [("ARCHER_STANCE", 0.8), ("ARCHER_DRAW", 1.1), ("ARCHER_RELOAD", 0.75), ("ARCHER_DRAW__V2", 1.1),
            ("ARCHER_RELOAD", 0.75), ("ARCHER_STANCE", 0.8), ("WALK", 2.2), ("RUN", 2.0)]
    parts = {}
    for const in sorted({p[0] for p in plan if p[0] not in ("WALK", "RUN")} | {"ARCHER_PATROL"}):
        L, loop, solve, contract, keep, props = CLIPS[const]()
        parts[const] = (L, loop, [solve(i / FPS) for i in range(int(round(L * FPS)) + 1)], props)

    def legs(name):
        path = os.path.join(ck.ANIM_DIR, name + ".animation.json")
        doc = json.load(open(path))
        anim = "animation.settler." + name
        La = float(doc["animations"][anim]["animation_length"])
        bones = doc["animations"][anim]["bones"]

        def at(t):
            out = {}
            for bn in ("root", "right_leg", "left_leg", "right_shin", "left_shin"):
                if bn in bones:
                    out[bn] = {}
                    if "rotation" in bones[bn]:
                        out[bn]["rot"] = tuple(ex.sample(doc, anim, bn, "rotation", t % La))
                    if "position" in bones[bn]:
                        out[bn]["pos"] = tuple(ex.sample(doc, anim, bn, "position", t % La))
            return out
        return at
    walk, run_ = legs("walk"), legs("walk_hurried")
    frames, states = [], []
    for const, dur in plan:
        n = int(round(dur * FPS))
        for i in range(n):
            t = i / FPS
            if const in ("WALK", "RUN"):
                L, loop, smp, props = parts["ARCHER_PATROL"]
                up = smp[i % (len(smp) - 1)]
                ch = {k: v for k, v in up.items() if k in OVERLAY_BONES + ["cloak"]}
                ch.update((walk if const == "WALK" else run_)(t))
                frames.append(ch)
                states.append(("low", "rest", False, True))
                continue
            L, loop, smp, props = parts[const]
            k = min(i, len(smp) - 1)
            frames.append(smp[k])
            tt = k / FPS
            if const.startswith("ARCHER_DRAW"):
                states.append(("draw", pull_frame(tt / L), False))
            elif const == "ARCHER_RELOAD":
                arrow = bool(props) and props[0]["from"] <= tt <= props[0]["to"]
                states.append(("low", "rest", arrow, tt > 0.66))
            else:
                states.append(("low", "rest", False, True))
    objs = build_scene()
    sc = bpy.context.scene
    sc.frame_start, sc.frame_end = 0, len(frames) - 1
    for f, ch in enumerate(frames):
        for name, e in objs.items():
            if name not in mcrig.PARTS:
                continue
            pivot = mcrig.PARTS[name][1]
            cc = ch.get(name, {})
            rot, pos = cc.get("rot", (0, 0, 0)), cc.get("pos", (0, 0, 0))
            e.location = (pivot[0] + pos[0], pivot[1] - pos[1], pivot[2] + pos[2])
            e.rotation_euler = tuple(math.radians(r) for r in rot)
            e.keyframe_insert("location", frame=f)
            e.keyframe_insert("rotation_euler", frame=f)
    key_visibility(len(frames), lambda f: states[f])
    for ob in objs.values():
        ad = ob.animation_data
        if ad and ad.action:
            for fc in ad.action.fcurves:
                for kp in fc.keyframe_points:
                    kp.interpolation = "LINEAR"
    d = ck.out_dir("archer_cycle_reel")
    ck._setup_workbench((720, 540))
    cams = {"side": hsrig.camera("r_side", (-3.9, -0.4, 1.1), (0.0, -0.4, 0.95), lens=36),
            "front34": hsrig.camera("r_f34", (2.4, -3.0, 1.5), (0.0, -0.4, 0.95), lens=36)}
    step = 2
    fr = list(range(0, len(frames), step))
    for name, cam in cams.items():
        fd = os.path.join(d, "_frames_" + name)
        os.makedirs(fd, exist_ok=True)
        for old in os.listdir(fd):
            os.remove(os.path.join(fd, old))
        sc.camera = cam
        for i, f in enumerate(fr):
            sc.frame_set(f)
            sc.render.filepath = os.path.join(fd, f"f{i:04d}.png")
            bpy.ops.render.render(write_still=True)
        ck._encode(fd, len(fr), FPS // step, os.path.join(d, f"archer_cycle_{name}.mp4"))
        ck._encode(fd, len(fr), FPS // step // 2, os.path.join(d, f"archer_cycle_{name}_half.mp4"))
        k = 12
        idx = [int(round(i * (len(fr) - 1) / (k - 1))) for i in range(k)]
        hsrig._sheet([os.path.join(fd, f"f{i:04d}.png") for i in idx], os.path.join(d, f"sheet_{name}.png"))
    print("REEL", d, round(len(frames) / FPS, 2), "s")


if __name__ == "__main__":
    args = hsrig.parse_args()
    args["three"] = "--three" in sys.argv
    names = list(args["rest"]) or list(CLIPS)
    if names == ["LOWSTILL"]:
        low_still()
    elif names == ["SEQUENCE"]:
        sequence(args)
    else:
        for nme in names:
            run(nme, args)
