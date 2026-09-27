"""HUNTER v2 (owner 2026-09-26: "full rework"), anim overkill lane -- a right-handed hunter with a short hunting bow.

    blender -b --factory-startup --python hunter.py -- [--fast|--three] [--no-export] CONST ... | LOWSOLVE | REEL

Shares the archer's display maths (archer.py: the agreed hand-frame convention, wrist compensation at a display
switch, the right-hand IK onto the nock). The hunter's bow is the vanilla bow at 0.95 (shorter than the archer's
longbow), LEFT hand, arrow nocked, the right fingers on the string; displays solved on THESE clips' arm poses
(anim-overkill/hunter_low.json -> lr_v5_reach.py (weapons-lane clearance audit + right-arm reach) ->
hunter_low_disp.json). Clips are exported to anim-overkill/stage/hunter first (reel to main before landing).

HUNTER_READY   4.00 s loop  stalk-ready, stationary (TRACKING_GAME, not moving): crouched, bow low in the left
                            hand with the arrow nocked, a slow look-and-listen (head turns, a held breath).
HUNTER_DRAW    1.20 s       WORK_HUNT (= HUNT_ANIMATION_TICKS 24): opens to the target and raises the bow 0-0.28,
                            draws 0.28-0.63 (pull frames on the hunter's own 14-tick draw), aims 0.63-0.70,
                            RELEASE 0.70 (tick 14), follow-through, lowers; the right hand takes a new arrow from
                            the belt 0.92-1.00 and nocks it at 1.12.
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

import archer as A  # noqa: E402

rk, ck, hsrig, mcrig = A.rk, A.ck, A.hsrig, A.mcrig
c = hsrig.ctrl
S, ACC, DEC, LIN = A.S, A.ACC, A.DEC, A.LIN
FPS = hsrig.FPS
OUT = A.OUT
STAGE = os.path.join(OUT, "stage", "hunter")
SKIN = os.path.join(OUT, "props", "skin_hunter.png")
SCALE_H = 0.95                     # the hunting bow: vanilla size, shorter than the archer's longbow
BA = A.BA
HUNT_RELEASE = 0.70                # HunterWorkGoal.HUNT_RELEASE_TICK 14
DRAW_TICKS = 14.0                  # the hunter's pull frames run on its own 14-tick draw (SettlerBowHold)

# ------------------------------------------------------------------ poses
H_FEET = (np.array([-3.2, 24.0, 1.6]), np.array([3.0, 24.0, -1.8]))       # left foot forward, wide
H_LOW = {"hip_x": 0.0, "hip_y": -2.4, "hip_z": 0.8, "hip_p": 0.0, "hip_yaw": 6.0, "hip_r": 0.0,
         "sp_x": 22.0, "sp_y": 8.0, "sp_z": 0.0, "sp_lift": 0.0, "hd_x": -20.0, "hd_y": -10.0, "hd_z": 0.0,
         "ck_x": 8.0, "ck_z": 0.0, "wr_x": 0.0, "wr_y": 0.0, "wr_z": 0.0, "wl_x": 0.0, "wl_y": 0.0, "wl_z": 0.0,
         "gl_w": 0.0, "gl_s": 0.0, "gr_s": 0.0}
H_GRIP = np.array([2.4, 12.8, -8.2])          # bow fist: low, in front of the left hip, arm soft
H_DRAW_BODY = {"hip_yaw": 46.0, "sp_y": 24.0, "sp_x": 10.0, "hip_y": -1.8, "hip_z": 0.2, "hd_y": float(os.environ.get("H_HDY", "-72")),
               "hd_x": 0.0, "hd_z": 4.0}
H_DRAW_FEET = {"fl_x": -1.0, "fl_z": -1.6, "fr_x": 0.4, "fr_z": 1.6}
H_ANCHOR = np.array([float(v) for v in os.environ.get("H_ANCHOR", "-5.6,-0.6,-1.4").split(",")])  # a low, hunter's anchor under the cheekbone: the forearm passes UNDER the head
BELT = np.array([-4.6, -1.6, -1.2])           # the arrow is taken from the right hip / belt (torso-local)
STATE = A.STATE
A.DRAW_POLE_K = float(os.environ.get("H_POLE_K", "0.3"))
A.DRAW_POLE = np.array([float(v) for v in os.environ.get("H_DRAW_POLE", "-1.0,-0.45,0.25").split(",")])   # string elbow OUT to the right side   # the hunter raises from a crouch: the elbow swings late


# ------------------------------------------------------------------ displays
def low_solve():
    """Solve the bow fist on the crouched stalk pose and write anim-overkill/hunter_low.json for the reach search."""
    A.body_keys(1.0, H_LOW)
    sol, err = A.bow_arm_goal(0.0, H_GRIP, [-35.0, 10.0, 0.0, -30.0, 0.0, 0.0])
    ch = rk.body_at(0.0)
    ch["left_arm"] = {"rot": tuple(sol[:3])}
    ch["left_forearm"] = {"rot": (sol[3], sol[4], 0.0)}
    world = mcrig.pose_matrices(ch)
    palm = mcrig.xform(world["left_forearm"], (0, 6, 0))
    json.dump({"hand": "LEFT", "left_arm": [round(v, 3) for v in sol[:3]],
               "left_forearm": [round(sol[3], 3), round(sol[4], 3), 0.0], "left_item": [0.0, 0.0, 0.0],
               "fist_world_px": [round(float(v), 3) for v in palm],
               "body_ctrl": {k: H_LOW[k] for k in ("hip_x", "hip_y", "hip_z", "hip_p", "hip_yaw", "hip_r", "sp_x",
                                                     "sp_y", "sp_z", "sp_lift", "hd_x", "hd_y", "hd_z")}},
              open(os.path.join(OUT, "hunter_low.json"), "w"), indent=1)
    print("HUNTER_LOW fist", np.round(palm, 2), "arm", [round(v, 1) for v in sol[:5]], "cost", round(err, 2))


def solve_draw(t_anchor, fixed=True):
    """Left arm at full draw so the pulling_2 nock sits at the right-jaw anchor; the drawing display is solved once."""
    ch = rk.body_at(t_anchor)
    head = mcrig.pose_matrices(ch)["head"]
    anchor = mcrig.xform(head, H_ANCHOR)
    grip = anchor + np.array([0.0, 0.0, -9.0])
    sol = list(STATE.get("h_draw_sol", [-85.0, 0.0, 0.0, -5.0, 0.0, 0.0]))
    D = STATE.get("D_DRAW")
    for _ in range(6):
        sol, err = A.bow_arm_goal(t_anchor, grip, sol)
        ch2 = dict(ch)
        ch2["left_arm"] = {"rot": tuple(sol[:3])}
        ch2["left_forearm"] = {"rot": (sol[3], sol[4], 0.0)}
        wd = mcrig.pose_matrices(ch2)
        palm = mcrig.xform(wd["left_forearm"], (0, 6, 0))
        if not fixed:
            D = A.solve_display(ck.item_base(wd, "left"), palm, A.DRAW_AXIS, A.DRAW_ARROW, scale=SCALE_H)
        nock = mcrig.xform(A.bow_matrix(ck.item_base(wd, "left"), D), A.NOCK["pulling_2"])
        grip = grip + (anchor - nock) * 0.9
    if not fixed:
        STATE["D_DRAW"], STATE["h_draw_sol"] = D, sol
        json.dump({"hand": "LEFT", "left_arm": [round(v, 3) for v in sol[:3]],
                   "left_forearm": [round(sol[3], 3), round(sol[4], 3), 0.0],
                   "fist_world_px": [round(float(v), 3) for v in palm],
                   "anchor_world_px": [round(float(v), 3) for v in anchor],
                   "drawing_display_px_deg": {"rotation": [round(v, 3) for v in D[0]],
                                              "translation": [round(v, 4) for v in D[1]], "scale": [SCALE_H] * 3},
                   "convention": "translateToHand(LEFT) . Rx(-90) Ry(180) T(-1,2,-10) . T(tr) rotationXYZ S T(-8)"},
                  open(os.path.join(OUT, "hunter_draw_left.json"), "w"), indent=1)
    print("H_DRAW nock-anchor", np.round(anchor - nock, 2), "arm", [round(v, 1) for v in sol[:5]], "cost", round(err, 2),
          "fixed" if fixed else "SOLVED")
    return sol, anchor


def prepare():
    if STATE.get("h_prepared"):
        A.body_keys(1.0, H_LOW)
        return
    STATE["h_prepared"] = True
    disp = json.load(open(os.path.join(OUT, "hunter_low_disp.json")))
    STATE["D_LOW"] = np.array(disp["px_local"]["bow"], float)
    STATE["A_LOW"] = np.array(disp["px_local"]["arrow"], float)
    STATE["low_sol"] = list(disp["left_arm"]) + [disp["left_forearm"][0], 0.0, 0.0]
    STATE["low_nock"] = disp["arrow"]["nock_world"]
    STATE["D_DRAW"] = STATE["D_LOW"]
    A.body_keys(1.0, dict(H_LOW, **H_DRAW_BODY))
    solve_draw(0.0, fixed=False)
    A.body_keys(1.0, H_LOW)


# ------------------------------------------------------------------ clips
def ready():
    """HUNTER_READY: crouched stalk-ready, a slow look-and-listen."""
    L = 4.0
    sol = STATE["low_sol"]
    v = dict(H_LOW)
    v.update(dict(zip(BA, sol[:5])))
    b = H_LOW
    over = {
        # breathing, then a held breath while he listens (1.9-2.6)
        "sp_lift": [(0.0, 0.0, *S), (0.9, 0.2, *S), (1.8, 0.0, *S), (1.9, 0.06, *S), (2.6, 0.06, *S), (3.3, 0.22, *S),
                    (4.0, 0.0)],
        # look left, then a quick listen to the right, back to the front
        "hd_y": [(0.0, b["hd_y"], *S), (0.5, b["hd_y"], *S), (1.1, b["hd_y"] + 26.0, *DEC), (1.7, b["hd_y"] + 24.0, *S),
                 (2.0, b["hd_y"] - 22.0, *DEC), (2.7, b["hd_y"] - 20.0, *S), (3.3, b["hd_y"], *S), (4.0, b["hd_y"])],
        "hd_z": [(0.0, 0.0, *S), (2.0, 0.0, *S), (2.2, -7.0, *DEC), (2.6, -6.0, *S), (3.0, 0.0, *S), (4.0, 0.0)],
        "hd_x": [(0.0, b["hd_x"], *S), (1.1, b["hd_x"] - 3.0, *S), (2.2, b["hd_x"] + 2.0, *S), (4.0, b["hd_x"])],
        "sp_y": [(0.0, b["sp_y"], *S), (1.1, b["sp_y"] + 5.0, *S), (2.2, b["sp_y"] - 3.0, *S), (3.3, b["sp_y"], *S),
                 (4.0, b["sp_y"])],
        "hip_y": [(0.0, b["hip_y"], *S), (2.0, b["hip_y"] - 0.25, *S), (4.0, b["hip_y"])],
        "al_x": [(0.0, sol[0], *S), (1.0, sol[0] - 1.0, *S), (2.0, sol[0], *S), (3.0, sol[0] - 0.8, *S), (4.0, sol[0])],
        "nk": [(0.0, 1.0), (4.0, 1.0)],
    }
    A.body_keys(L, v, over, loop=True)
    return L, True, A.make_solve(L, True, H_FEET), "4.00 s loop: stalk-ready, stationary; look-and-listen", (), None


def draw_():
    """HUNTER_DRAW 1.20 s = HUNT_ANIMATION_TICKS 24; release 0.70 = tick 14."""
    L = 1.2
    sol0 = STATE["low_sol"]
    up, anc, rel = 0.28, 0.63, HUNT_RELEASE
    v = dict(H_LOW)
    v.update(dict(zip(BA, sol0[:5])))
    over = {}
    for p, target in H_DRAW_BODY.items():
        a = H_LOW.get(p, 0.0)
        extra = {"sp_x": 1.0}.get(p, 0.0)
        t_up = float(os.environ.get("H_HEAD_UP", "0.15")) if p in ("hd_y", "hd_x", "hd_z") else up   # eyes lead: the head is round before the bow crosses the face
        over[p] = [(0.0, a, *S), (0.03 if p.startswith("hd") else 0.05, a, *ACC), (t_up, target, *DEC), (anc, target + extra, *S), (rel, target + extra, *S),
                   (rel + 0.04, target + extra + (2.0 if p == "sp_x" else 0.0), *DEC), (0.86, target, *S), (1.08, a, *S),
                   (L, a)]
    over["hd_y"][6:] = [(0.86, H_DRAW_BODY["hd_y"] + 6.0, *S), (1.0, H_LOW["hd_y"] + 6.0, *S), (1.08, H_LOW["hd_y"] + 8.0, *S),
                        (L, H_LOW["hd_y"])]
    over["sp_lift"] = [(0.0, 0.0, *S), (up, 0.12, *S), (anc - 0.06, 0.4, *S), (rel, 0.36, *S), (0.86, 0.1, *S), (L, 0.0)]
    over["sp_z"] = [(0.0, 0.0, *S), (anc, 2.5, *S), (rel, 2.5, *S), (0.86, 1.0, *S), (L, 0.0)]
    for p, d in H_DRAW_FEET.items():   # a small plant of the front foot, only while lifted
        a0, a1 = (0.04, up - 0.05) if p.startswith("fl") else (0.07, up - 0.03)
        over[p] = [(0.0, 0.0), (a0, 0.0, *S), (a1, d, *DEC), (0.90, d, *S), (1.10, 0.0, *S), (L, 0.0)]
    over["fl_y"] = [(0.0, 0.0), (0.03, 0.0, *DEC), (0.10, 1.2, *S), (up - 0.08, 0.8, *ACC), (up - 0.04, 0.0), (0.88, 0.0),
                    (0.93, 1.0, *S), (1.06, 0.6, *ACC), (1.10, 0.0), (L, 0.0)]
    over["fr_y"] = [(0.0, 0.0), (0.05, 0.0, *DEC), (0.11, 1.0, *S), (up - 0.06, 0.6, *ACC), (up - 0.03, 0.0), (0.90, 0.0),
                    (0.96, 0.9, *S), (1.07, 0.5, *ACC), (1.10, 0.0), (L, 0.0)]
    over["dsp"] = [(0.0, 1.0), (rel - 0.001, 1.0), (rel, 0.0), (L, 0.0)]
    over["dpw"] = [(0.0, 0.0), (rel, 0.0), (rel + 0.001, 1.0), (0.86, 1.0, *S), (1.0, 0.0, *S), (L, 0.0)]
    # the nock follows the string through the hunter's pull frames: p1 at 0.455 s (tick 9.1), p2 at 0.63 (12.6)
    over["pull"] = [(0.0, 0.0, *LIN), (anc, 0.9), (rel, 0.9), (L, 0.9)]       # = useTicks / 14 (the displayed frame)
    over["nk"] = [(0.0, 1.0), (rel - 0.001, 1.0), (rel, 0.0), (1.04, 0.0, *S), (1.12, 1.0, *DEC), (L, 1.0)]
    A.body_keys(L, v, over)
    full, anchor = solve_draw(anc)
    mid, _ = A.bow_arm_goal(up * 0.5, H_GRIP + np.array([float(x) for x in os.environ.get("H_MID", "2.0,-4.6,-4.6").split(",")]), sol0)
    rec = [full[0] + 5.0, full[1], full[2] - 2.5, full[3] - 3.0, full[4], 0.0]
    click = [sol0[0] - 2.5, sol0[1], sol0[2], sol0[3] - 3.0, sol0[4], 0.0]
    A.key_bow_arm([(0.0, sol0, S), (up * 0.5, mid, S), (up, full, DEC), (anc, full, S), (rel, full, S),
                   (rel + 0.06, rec, S), (0.86, full, S), (1.02, sol0, S), (1.10, sol0, S), (1.125, click, DEC),
                   (1.16, sol0, S), (L, sol0, None)], L)
    rot0, pos0 = A.wrist_comp(STATE["D_DRAW"], STATE["D_LOW"])
    rotA, posA = A.wrist_comp(STATE["D_LOW"], STATE["D_DRAW"])
    A.key_wrist([(0.0, rot0, pos0, S), (up * 0.9, (0, 0, 0), (0, 0, 0), DEC), (rel - 0.001, (0, 0, 0), (0, 0, 0), None),
                 (rel, rotA, posA, S), (0.86, rotA, posA, S), (1.02, (0, 0, 0), (0, 0, 0), DEC), (L, (0, 0, 0), (0, 0, 0), None)], L)
    # the right hand: on the string (nk) until the release, flies back past the jaw, then the belt, then nocks
    head_rel = mcrig.pose_matrices(rk.body_at(rel))["head"]
    fthru = mcrig.xform(head_rel, H_ANCHOR + np.array([-1.6, 0.4, 3.4]))

    def tw(t, p):
        return mcrig.xform(mcrig.pose_matrices(rk.body_at(t))["torso"], np.array(p, float))
    near = np.array(STATE["low_nock"], float)
    WP = [(0.0, fthru, ()), (rel, anchor, ()), (rel + 0.06, fthru, DEC), (0.86, fthru + np.array([0.0, 0.3, 0.2]), S),
          (0.95, tw(0.95, BELT + (0.0, -1.0, -0.6)), DEC), (0.99, tw(0.99, BELT), DEC), (1.03, tw(1.03, BELT + (0.2, -2.6, -1.4)), ()),
          (1.09, near + np.array([-0.4, -0.8, -0.6]), ()), (1.12, near, DEC), (L, near, None)]
    K = {"rh_x": [], "rh_y": [], "rh_z": []}
    for t, p, e in WP:
        for i, n in enumerate(("rh_x", "rh_y", "rh_z")):
            K[n].append((t, float(p[i]), *e) if e else (t, float(p[i])))
    ck.key_all(K, L, False)
    ck.key_all({"aw": [(0.0, 0.0), (0.97, 0.0, *S), (0.99, 1.0, *S), (L, 1.0)],
                "ad_x": [(0.0, 0.1), (1.03, 0.1, *S), (1.10, 0.3), (L, 0.3)],
                "ad_y": [(0.0, -1.0), (1.03, -1.0, *S), (1.10, 0.6), (L, 0.6)],
                "ad_z": [(0.0, 0.2), (1.03, 0.2, *S), (1.10, -0.75), (L, -0.75)]}, L, False)
    props = [{"hand": "mainhand", "item": "hearthstead:prop_arrow", "from": 0.99, "to": 1.12, "over_real": True}]
    contract = ("1.20 s one-shot = HUNT_ANIMATION_TICKS 24: raise 0.00-0.28, draw 0.28-0.63 (hunter pull frames p1 tick 9, "
                "p2 tick 13), aim 0.63-0.70, RELEASE 0.70 = HUNT_RELEASE_TICK 14, follow-through, lower; new arrow from "
                "the belt 0.95-1.03, NOCK 1.12")
    return L, False, A.make_solve(L, False, H_FEET), contract, (up, anc, rel, 0.86, 0.99, 1.12), props


def _walk_feet():
    """The WALK clip's sole centres and root bob per time (world px): the stalk keeps WALK's stride and ground
    contact timing exactly (the locomotion slot is sampled by distance), only lower, wider and more careful."""
    import export_mc_clip as ex
    doc = json.load(open(os.path.join(ck.ANIM_DIR, "walk.animation.json")))
    anim = "animation.settler.walk"
    La = float(doc["animations"][anim]["animation_length"])
    bones = doc["animations"][anim]["bones"]

    def at(t):
        ch = {}
        for bn in ("root", "right_leg", "left_leg", "right_shin", "left_shin"):
            if bn in bones:
                ch[bn] = {}
                if "rotation" in bones[bn]:
                    ch[bn]["rot"] = tuple(ex.sample(doc, anim, bn, "rotation", t % La))
                if "position" in bones[bn]:
                    ch[bn]["pos"] = tuple(ex.sample(doc, anim, bn, "position", t % La))
        w = mcrig.pose_matrices(ch)
        return (mcrig.xform(w["right_shin"], (0, 6, 0)), mcrig.xform(w["left_shin"], (0, 6, 0)),
                ch.get("root", {}).get("pos", (0, 0, 0)), ch.get("root", {}).get("rot", (0, 0, 0)))
    return at, La


def stalk(v2=False):
    """HUNTER_STALK: the crouched, careful stalk (locomotion slot while TRACKING_GAME). 1.00 s = WALK's cycle and
    stride (distance-sampled); __v2 (2 cycles): a look round over the right shoulder and back mid-stride."""
    walk, La = _walk_feet()
    L = 2.0 if v2 else 1.0
    sol = STATE["low_sol"]
    v = dict(H_LOW)
    v.update(dict(zip(BA, sol[:5])))
    v["sp_x"] = 18.0
    n = int(round(L * 30))
    K = {k: [] for k in ("fr_x", "fr_y", "fr_z", "fl_x", "fl_y", "fl_z", "hip_y", "hip_yaw", "hip_r", "sp_y", "sp_z",
                         "al_x", "hd_x")}
    for i in range(n + 1):
        t = L * i / n
        fr, fl, rp, rr = walk(t)
        for side, f, base, spread in (("fr", fr, H_FEET[0], -0.6), ("fl", fl, H_FEET[1], 0.6)):
            lift = max(0.0, 24.0 - f[1])
            K[side + "_x"].append((t, float(f[0] + spread - base[0]), *LIN))
            K[side + "_y"].append((t, float(lift * 1.35), *LIN))      # a higher, careful foot
            K[side + "_z"].append((t, float(f[2] - base[2]), *LIN))
        K["hip_y"].append((t, H_LOW["hip_y"] + 0.7 * float(rp[1]), *LIN))   # the crouch absorbs half the bob
        K["hip_yaw"].append((t, H_LOW["hip_yaw"] + 0.6 * float(rr[1]), *LIN))
        K["hip_r"].append((t, 0.6 * float(rr[2]), *LIN))
        ph = 2 * math.pi * t / 1.0
        K["sp_y"].append((t, H_LOW["sp_y"] - 2.5 * math.sin(ph), *LIN))    # the shoulders counter the hips a little
        K["sp_z"].append((t, 0.8 * math.sin(ph), *LIN))
        K["al_x"].append((t, sol[0] + 1.2 * math.sin(ph * 2), *LIN))
        K["hd_x"].append((t, H_LOW["hd_x"] + 1.0 * math.sin(ph * 2 + 1.0), *LIN))
    over = dict(K)
    if v2:
        over["hd_y"] = [(0.0, H_LOW["hd_y"], *S), (0.35, H_LOW["hd_y"], *S), (0.75, H_LOW["hd_y"] - 38.0, *DEC),
                        (1.1, H_LOW["hd_y"] - 36.0, *S), (1.45, H_LOW["hd_y"] + 10.0, *S), (1.75, H_LOW["hd_y"], *S),
                        (2.0, H_LOW["hd_y"])]
        over["sp_y"] = [(t, val - (6.0 if 0.6 <= t <= 1.2 else 0.0), *e) for t, val, *e in over["sp_y"]]
    else:
        over["hd_y"] = [(0.0, H_LOW["hd_y"], *S), (0.5, H_LOW["hd_y"] + 4.0, *S), (1.0, H_LOW["hd_y"])]
    over["nk"] = [(0.0, 1.0), (L, 1.0)]
    A.body_keys(L, v, over, loop=True)
    contract = ("%.2f s loop (locomotion, distance-sampled like WALK: same stride and contacts): crouched careful stalk, "
                "bow low in the left hand, arrow nocked%s" % (L, "; a look round over the right shoulder" if v2 else ""))
    return L, True, A.make_solve(L, True, H_FEET), contract, (), None


# ======================================================================= part 2: hands free of the bow
# (inserted into hunter.py before CLIPS)  Both hands IK onto world or torso-local targets, legs IK.
CARCASS_REST = np.array([0.0, -13.3, 3.2])       # torso-local: across the shoulders, behind the neck (CarcassCarryLayer)
GRIP_R = np.array([-4.0, -12.4, -1.8])            # torso-local: the right hand on the front legs at the shoulder
GRIP_L = np.array([4.0, -12.4, -1.8])             # the left hand on the hind legs
FLOOR_CARCASS = np.array([0.0, 22.7, -10.4])      # model px: the carcass on the lodge floor (CarcassCarryLayer, working)
KNEEL_FEET = (np.array([-3.0, 23.6, 6.0]), np.array([3.2, 24.0, -4.2]))   # right knee down (foot behind), left foot forward
H_KNEEL = {"hip_x": 0.0, "hip_y": -6.2, "hip_z": 1.0, "hip_p": 0.0, "hip_yaw": 0.0, "hip_r": 0.0,
           "sp_x": 30.0, "sp_y": 0.0, "sp_z": 0.0, "sp_lift": 0.0, "hd_x": 6.0, "hd_y": 0.0, "hd_z": 0.0,
           "ck_x": 10.0, "ck_z": 0.0}
POLE_R = np.array([-0.9, 0.2, 0.4])
POLE_L = np.array([0.9, 0.2, 0.4])
HAND_KEYS = ("rh_x", "rh_y", "rh_z", "lh_x", "lh_y", "lh_z", "rtl", "ltl", "rpu", "lpu")


def body_keys2(L, vals, over=None, loop=False):
    K = {p: [(0.0, v), (L, v)] for p, v in vals.items()}
    for p in ck.FEET_PROPS + HAND_KEYS + ("wr_x", "wr_y", "wr_z", "wl_x", "wl_y", "wl_z"):
        K.setdefault(p, [(0.0, 0.0), (L, 0.0)])
    K.update(over or {})
    ck.key_all(K, L, loop)
    for side, rows, L2, loop2 in _HAND_LOG:        # hand keys made before this call win over the defaults
        key_hands(side, rows, L2, loop2, log=False)
    _HAND_LOG.clear()


def make_solve2(L, loop, feet):
    prev = {}

    def w(t):
        return t % L if loop else min(max(t, 0.0), L)

    def solve(t):
        t = w(t)
        g = lambda p: c(p, t)  # noqa: E731
        ch = ck.body_channels(g)
        ch["cloak"] = {"rot": (g("ck_x"), 0.0, g("ck_z"))}
        world = mcrig.pose_matrices(ch)
        Tinv = np.linalg.inv(world["torso"])
        for side, sh, pole, sgn in (("r", (-6.0, -10.0, 0.0), POLE_R, 1.0), ("l", (6.0, -10.0, 0.0), POLE_L, -1.0)):
            tgt = np.array([g(side + "h_x"), g(side + "h_y"), g(side + "h_z")])
            tlw = max(0.0, min(1.0, g(side + "tl")))
            loc = tgt if tlw >= 1.0 else (mcrig.xform(Tinv, tgt) * (1 - tlw) + tgt * tlw)
            pu = max(-1.0, min(1.0, g(side + "pu")))          # pole up: elbows out and up (carry, heave)
            p = pole + np.array([0.0, -0.9, -0.1]) * pu
            r, flex, _ = mcrig.two_bone(np.array(sh), loc, mcrig.UPPER_ARM, 6.0, p, -1)
            rot = hsrig.euler_deg_continuous(r, prev.get(side))
            prev[side] = rot
            nm = "right" if side == "r" else "left"
            ch[nm + "_arm"] = {"rot": tuple(rot)}
            ch[nm + "_forearm"] = {"rot": (math.degrees(flex), 0.0, 0.0)}
        fr = feet[0] + np.array([g("fr_x"), -g("fr_y"), g("fr_z")])
        fl = feet[1] + np.array([g("fl_x"), -g("fl_y"), g("fl_z")])
        ck.legs_ik(ch, fr, fl, key=id(solve))
        return ch
    return solve


def tw_at(t, p):
    return mcrig.xform(mcrig.pose_matrices(rk.body_at(t))["torso"], np.array(p, float))


_HAND_LOG = []


def key_hands(side, rows, L, loop=False, log=True):
    """rows: [(t, xyz, ease)] -> rh_/lh_ keys (logged, so a later body_keys2 can replay them)."""
    if log:
        _HAND_LOG.append((side, rows, L, loop))
    K = {side + "h_x": [], side + "h_y": [], side + "h_z": []}
    for t, p, e in rows:
        for i, ax in enumerate("xyz"):
            K[side + "h_" + ax].append((t, float(p[i]), *e) if e else (t, float(p[i])))
    ck.key_all(K, L, loop)


def take_kill():
    """HUNTER_TAKE_KILL 1.40 s = PICKUP_DURATION_TICKS 28, contact 0.55 s = PICKUP_CONTACT_TICK 11: kneel at the
    kill, a hand on it (a short respectful beat), both hands under it, lift to the chest, heave it over the head
    onto the shoulders, hands onto the legs."""
    L = 1.4
    v = dict(H_LOW)
    v.update({"hd_x": 0.0, "hd_y": 0.0, "sp_y": 0.0, "hip_yaw": 0.0})
    over = {}
    stand = {"hip_y": -0.6, "sp_x": 10.0, "hd_x": 4.0, "hip_z": 0.4}
    for p, kv in H_KNEEL.items():
        a = stand.get(p, 0.0) if p in stand else v.get(p, 0.0)
        over[p] = [(0.0, a, *S), (0.30, kv, *DEC), (0.55, kv + (2.0 if p == "sp_x" else 0.0), *S), (0.78, kv * 0.4 if p != "hd_x" else -4.0, *S),
                   (1.02, stand.get(p, 0.0) if p != "hd_x" else 8.0, *S), (1.2, stand.get(p, 0.0), *S), (L, stand.get(p, 0.0))]
    over["hd_x"] = [(0.0, 4.0, *S), (0.3, 18.0, *DEC), (0.46, 24.0, *S), (0.55, 16.0, *S), (0.8, -6.0, *S), (1.02, 10.0, *S),
                    (1.2, 8.0, *S), (L, 8.0)]
    over["sp_lift"] = [(0.0, 0.0, *S), (1.02, 0.0, *S), (1.14, -0.5, *DEC), (1.28, 0.0, *S), (L, 0.0)]   # the weight seats
    # feet: the right knee goes down and comes back up (foot travels only while lifted)
    for p, d in (("fr_x", KNEEL_FEET[0][0] - H_FEET[0][0]), ("fr_z", KNEEL_FEET[0][2] - H_FEET[0][2]),
                 ("fl_x", KNEEL_FEET[1][0] - H_FEET[1][0]), ("fl_z", KNEEL_FEET[1][2] - H_FEET[1][2])):
        over[p] = [(0.0, 0.0), (0.10, 0.0, *S), (0.28, d, *DEC), (0.84, d, *S), (1.0, 0.0, *S), (L, 0.0)]
    over["fr_y"] = [(0.0, 0.0), (0.05, 0.0, *S), (0.14, 1.6, *S), (0.28, KNEEL_FEET[0][1] * 0 + 0.4, *DEC), (0.80, 0.4, *S),
                    (0.90, 1.4, *S), (1.02, 0.0, *S), (L, 0.0)]
    over["fl_y"] = [(0.0, 0.0), (0.08, 0.0, *S), (0.16, 1.2, *S), (0.26, 0.0, *DEC), (0.84, 0.0), (0.92, 1.0, *S), (1.0, 0.0, *DEC),
                    (L, 0.0)]
    over["rpu"] = [(0.0, 0.0), (0.7, 0.0, *S), (0.95, 1.0, *S), (L, 1.0)]
    over["lpu"] = over["rpu"]
    over["rtl"] = [(0.0, 1.0), (L, 1.0)]     # every hand row is torso-local (converted per key time)
    over["ltl"] = over["rtl"]
    body_keys2(L, v, over)
    # the carcass path (world at each key, stored torso-local for the Java layer)
    C = [(0.55, FLOOR_CARCASS + np.array([0.0, -2.2, 0.0])), (0.78, np.array([0.0, 8.0, -8.0])), (0.95, np.array([0.0, -9.5, -3.0])),
         (1.10, None)]
    path = []
    for t, pw in C:
        Tt = mcrig.pose_matrices(rk.body_at(t))["torso"]
        pl = CARCASS_REST if pw is None else mcrig.xform(np.linalg.inv(Tt), pw)
        path.append((t, pl, Tt))
    STATE["carcass_path"] = [(t, [round(float(x), 2) for x in pl]) for t, pl, _ in path]
    rest_r, rest_l = tw_at(1.02, GRIP_R), tw_at(1.02, GRIP_L)

    def under(t, pl, Tt, dx):
        return mcrig.xform(Tt, pl + np.array([dx, 2.2, -1.2]))
    k = path
    R = [(0.0, tw_at(0.0, (-6.5, -1.0, -2.0)), ()), (0.30, FLOOR_CARCASS + np.array([-1.5, -2.6, -1.0]), DEC),   # a hand on the kill
         (0.44, FLOOR_CARCASS + np.array([-1.3, -2.8, -0.6]), S),
         (0.55, under(*k[0], -3.6), DEC), (0.78, under(*k[1], -3.8), ()), (0.95, under(*k[2], -4.0), ()),
         (1.02, GRIP_R, DEC), (L, GRIP_R, None)]
    Lh = [(0.0, tw_at(0.0, (6.5, -1.0, -2.0)), ()), (0.30, tw_at(0.30, (6.0, 0.0, -4.0)), S),                 # on the knee
          (0.44, tw_at(0.44, (6.0, 0.0, -4.0)), S),
          (0.55, under(*k[0], 3.6), DEC), (0.78, under(*k[1], 3.8), ()), (0.95, under(*k[2], 4.0), ()),
          (1.02, GRIP_L, DEC), (L, GRIP_L, None)]
    def to_local(rows):
        out = []
        for t, p, e in rows:
            if t >= 1.02:
                out.append((t, np.array(p, float), e))
            else:
                Tt = mcrig.pose_matrices(rk.body_at(t))["torso"]
                out.append((t, mcrig.xform(np.linalg.inv(Tt), np.array(p, float)), e))
        return out
    key_hands("r", to_local(R), L)
    key_hands("l", to_local(Lh), L)
    contract = ("1.40 s one-shot = PICKUP_DURATION_TICKS 28: kneel 0-0.30, a hand on the kill 0.30-0.46, grip + CONTACT "
                "0.55 = PICKUP_CONTACT_TICK 11, lift 0.55-0.78, heave over the head 0.78-0.95, on the shoulders 1.10, "
                "hands on the legs; carcass path (torso-local px) " + json.dumps(STATE["carcass_path"]))
    return L, False, make_solve2(L, False, H_FEET), contract, (0.30, 0.46, 0.55, 0.78, 0.95, 1.02, 1.10), None


def _laden_feet():
    import export_mc_clip as ex
    doc = json.load(open(os.path.join(ck.ANIM_DIR, "walk_laden.animation.json")))
    anim = "animation.settler.walk_laden"
    La = float(doc["animations"][anim]["animation_length"])
    bones = doc["animations"][anim]["bones"]

    def at(t):
        ch = {}
        for bn in ("root", "right_leg", "left_leg", "right_shin", "left_shin"):
            if bn in bones:
                ch[bn] = {}
                for k2, kk in (("rotation", "rot"), ("position", "pos")):
                    if k2 in bones[bn]:
                        ch[bn][kk] = tuple(ex.sample(doc, anim, bn, k2, t % La))
        wd = mcrig.pose_matrices(ch)
        return (mcrig.xform(wd["right_shin"], (0, 6, 0)), mcrig.xform(wd["left_shin"], (0, 6, 0)),
                ch.get("root", {}).get("pos", (0, 0, 0)), ch.get("root", {}).get("rot", (0, 0, 0)))
    return at, La


def haul():
    """HUNTER_HAUL 1.20 s loop = WALK_LADEN's cycle and stride (distance-sampled): the kill across the shoulders,
    both hands on its legs, a heavier sink on every step, the head pushed forward by the weight."""
    walk, La = _laden_feet()
    L = La
    v = {"hip_x": 0.0, "hip_y": -1.4, "hip_z": 0.4, "hip_p": 0.0, "hip_yaw": 0.0, "hip_r": 0.0, "sp_x": 14.0, "sp_y": 0.0,
         "sp_z": 0.0, "sp_lift": 0.0, "hd_x": 8.0, "hd_y": 0.0, "hd_z": 0.0, "ck_x": 12.0, "ck_z": 0.0}
    n = int(round(L * 30))
    K = {k: [] for k in ("fr_x", "fr_y", "fr_z", "fl_x", "fl_y", "fl_z", "hip_y", "hip_yaw", "hip_r", "sp_z", "sp_x",
                         "hd_z", "rh_y", "lh_y")}
    for i in range(n + 1):
        t = L * i / n
        fr, fl, rp, rr = walk(t)
        for side, f, base in (("fr", fr, H_FEET[0]), ("fl", fl, H_FEET[1])):
            K[side + "_x"].append((t, float(f[0] - base[0]), *LIN))
            K[side + "_y"].append((t, float(max(0.0, 24.0 - f[1])), *LIN))
            K[side + "_z"].append((t, float(f[2] - base[2]), *LIN))
        K["hip_y"].append((t, v["hip_y"] + 1.3 * float(rp[1]), *LIN))          # a heavier sink on each step
        K["hip_yaw"].append((t, 0.8 * float(rr[1]), *LIN))
        K["hip_r"].append((t, 1.2 * float(rr[2]), *LIN))                    # the load rolls with the stride
        ph = 2 * math.pi * t / L
        K["sp_z"].append((t, -1.6 * math.sin(ph), *LIN))
        K["sp_x"].append((t, v["sp_x"] + 1.2 * math.cos(2 * ph), *LIN))
        K["hd_z"].append((t, 1.2 * math.sin(ph), *LIN))
        lag = 0.35 * math.sin(2 * ph - 0.6)                                   # the hands ride the weight, a touch late
        K["rh_y"].append((t, GRIP_R[1] + lag, *LIN))
        K["lh_y"].append((t, GRIP_L[1] + lag, *LIN))
    over = dict(K)
    for side, g in (("r", GRIP_R), ("l", GRIP_L)):
        over[side + "h_x"] = [(0.0, g[0]), (L, g[0])]
        over[side + "h_z"] = [(0.0, g[2]), (L, g[2])]
        over[side + "tl"] = [(0.0, 1.0), (L, 1.0)]
        over[side + "pu"] = [(0.0, 1.0), (L, 1.0)]
    body_keys2(L, v, over, loop=True)
    contract = "%.2f s loop (locomotion, distance-sampled like WALK_LADEN: same stride and contacts): the kill across the shoulders, hands on its legs" % L
    return L, True, make_solve2(L, True, H_FEET), contract, (), None


def _kneel_base():
    v = dict(H_KNEEL)
    over = {}
    for p, d in (("fr_x", KNEEL_FEET[0][0] - H_FEET[0][0]), ("fr_z", KNEEL_FEET[0][2] - H_FEET[0][2]),
                 ("fr_y", 0.4), ("fl_x", KNEEL_FEET[1][0] - H_FEET[1][0]), ("fl_z", KNEEL_FEET[1][2] - H_FEET[1][2])):
        v[p] = d
    return v, over


def skin_kneel(v2=False):
    """HUNTER_SKIN_KNEEL 1.20 s loop (HunterButchery SCRAPE_PERIOD 24, HIDE_SCRAPE at tick 4 = 0.20 s): kneeling at the
    kill on the floor, the left hand holds the hide back, the knife draws along it toward him 0.20-0.50, lifts,
    a look, back. __v2 (2 cycles): the second stroke is a careful cut along the leg, a wipe of the knife on the grass."""
    L = 2.4 if v2 else 1.2
    v, over = _kneel_base()
    top = FLOOR_CARCASS + np.array([0.0, -2.6, 0.0])
    rows = []
    for c0 in ((0.0, 1.2) if v2 else (0.0,)):
        rows += [(c0 + 0.0, top + (-1.2, -1.2, -3.4), S), (c0 + 0.16, top + (-1.2, 0.0, -3.4), DEC), (c0 + 0.20, top + (-1.2, 0.2, -3.2), LIN),
                 (c0 + 0.50, top + (-1.0, 0.1, 0.4), DEC), (c0 + 0.62, top + (-1.0, -1.4, 0.8), S), (c0 + 0.90, top + (-1.4, -2.0, -1.6), S)]
    if v2:
        rows[6:] = [(1.2, top + (-1.2, -1.2, -3.4), S), (1.36, top + (-2.4, 0.0, -2.8), DEC), (1.40, top + (-2.4, 0.2, -2.6), LIN),
                    (1.70, top + (-3.6, 0.2, 0.6), DEC), (1.86, top + (-5.0, 0.6, 0.8), S), (2.0, top + (-5.4, 0.8, -0.4), S),
                    (2.14, top + (-4.8, -0.6, -1.4), S)]
    rows.append((L, rows[0][1], None))
    key_hands("r", rows, L, loop=True)
    hold = top + (3.0, 0.6, -1.0)
    key_hands("l", [(0.0, hold, S), (0.2, hold + (0.2, -0.4, 0.4), S), (0.5, hold + (0.1, -0.2, 0.9), S), (L, hold, None)], L, loop=True)
    over["sp_x"] = [(0.0, 30.0, *S), (0.35, 33.0, *S), (0.8, 29.0, *S), (L, 30.0)]
    over["hd_x"] = [(0.0, 8.0, *S), (0.3, 12.0, *S), (0.75, 4.0, *S), (0.95, 2.0, *S), (L, 8.0)]
    over["hd_y"] = [(0.0, 0.0, *S), (0.75, 0.0, *S), (0.95, -14.0 if not v2 else 0.0, *S), (1.1, 0.0, *S), (L, 0.0)]
    over["sp_y"] = [(0.0, 0.0, *S), (0.3, -5.0, *S), (0.6, 4.0, *S), (L, 0.0)]
    body_keys2(L, v, over, loop=True)
    for p in ("fr_x", "fr_z", "fr_y", "fl_x", "fl_z"):
        ck.key_all({p: [(0.0, v[p]), (L, v[p])]}, L, True)
    contract = ("%.2f s loop (SCRAPE_PERIOD 24 per cycle): kneeling at the kill on the floor, knife draw 0.20-0.50 "
                "(HIDE_SCRAPE tick 4)" % L)
    keep = (0.16, 0.20, 0.50) + ((1.36, 1.40, 1.70) if v2 else ())
    return L, True, make_solve2(L, True, H_FEET), contract, keep, None


def butcher_kneel(v2=False):
    """HUNTER_BUTCHER_KNEEL 0.85 s loop (CLEAVE_PERIOD 17, CLEAVER_CHOP at tick 9 = 0.45 s): kneeling, the cleaver
    up by 0.32, the chop lands 0.45 and parks to 0.60, the left hand steadies the joint. __v2 (2 cycles): the second
    chop is a short twist to part the joint."""
    L = 1.7 if v2 else 0.85
    v, over = _kneel_base()
    top = FLOOR_CARCASS + np.array([0.0, -2.6, 0.0])
    hit = top + (-1.6, 0.2, -1.0)
    rows = []
    for c0 in ((0.0, 0.85) if v2 else (0.0,)):
        rows += [(c0 + 0.0, hit + (0.0, -2.0, 0.2), S), (c0 + 0.32, hit + (-1.2, -10.0, 3.2), DEC), (c0 + 0.37, hit + (-1.2, -10.4, 3.4), ACC),
                 (c0 + 0.45, hit, LIN), (c0 + 0.60, hit + (0.0, 0.2, 0.0), S)]
    if v2:
        rows += [(1.60, hit + (0.6, -0.6, 0.4), S)]
    rows.append((L, rows[0][1], None))
    key_hands("r", rows, L, loop=True)
    hold = top + (3.2, 0.6, -0.6)
    key_hands("l", [(0.0, hold, S), (0.45, hold, LIN), (0.5, hold + (0.0, 0.5, 0.0), S), (0.7, hold, S), (L, hold, None)], L, loop=True)
    over["sp_x"] = [(0.0, 30.0, *S), (0.32, 24.0, *DEC), (0.37, 24.5, *ACC), (0.45, 34.0, *LIN), (0.6, 33.0, *S), (L, 30.0)]
    over["sp_y"] = [(0.0, 0.0, *S), (0.32, 8.0, *DEC), (0.45, -4.0, *LIN), (0.7, -2.0, *S), (L, 0.0)]
    over["hd_x"] = [(0.0, 10.0, *S), (0.32, 6.0, *S), (0.45, 12.0, *S), (L, 10.0)]
    over["wr_x"] = [(0.0, 0.0, *S), (0.32, -30.0, *DEC), (0.45, 10.0, *LIN), (0.6, 8.0, *S), (L, 0.0)]
    body_keys2(L, v, over, loop=True)
    for p in ("fr_x", "fr_z", "fr_y", "fl_x", "fl_z"):
        ck.key_all({p: [(0.0, v[p]), (L, v[p])]}, L, True)
    props = [{"hand": "mainhand", "item": "hearthstead:prop_cleaver", "from": 0.0, "to": L, "hide_real": True}]   # hide_real also stows the carcass layer's knife
    contract = "%.2f s loop (CLEAVE_PERIOD 17 per cycle): kneeling chop, CLEAVER_CHOP contact 0.45 (tick 9), parked 0.45-0.60" % L
    keep = (0.32, 0.37, 0.45, 0.60) + ((1.17, 1.22, 1.30, 1.45) if v2 else ())
    return L, True, make_solve2(L, True, H_FEET), contract, keep, props


def idle_(variant):
    """HUNTER_IDLE at the lodge (the bow in the right hand, SettlerBowHold IDLE hold, turned by the wrist):
    v1 5.0 s  checks the bow: lifts it, sights along the limb, plucks the string twice (left hand), lowers it;
    v2 6.0 s  squats on his heels, the bow across his knees, rests, rolls the neck;
    v3 5.5 s  a stretch (shoulders back), a look at the sky and round the camp."""
    base = {"hip_x": 0.0, "hip_y": -0.3, "hip_z": 0.0, "hip_p": 0.0, "hip_yaw": 3.0, "hip_r": 0.0, "sp_x": 4.0, "sp_y": 0.0,
            "sp_z": 0.0, "sp_lift": 0.0, "hd_x": -2.0, "hd_y": 0.0, "hd_z": 0.0, "ck_x": 2.0, "ck_z": 0.0}
    feet = (np.array([-2.6, 24.0, 0.6]), np.array([2.8, 24.0, -0.4]))
    side_r = (-7.0, 1.6, 0.4)
    side_l = (7.0, 1.6, 0.4)
    if variant == 1:
        L = 5.0
        over = {"sp_lift": [(0.0, 0.0, *S), (1.2, 0.2, *S), (2.5, 0.0, *S), (3.8, 0.22, *S), (L, 0.0)],
                "hd_x": [(0.0, -2.0, *S), (0.8, 8.0, *S), (1.2, 6.0, *S), (2.6, 10.0, *S), (3.6, 6.0, *S), (4.3, -2.0, *S), (L, -2.0)],
                "hd_y": [(0.0, 0.0, *S), (0.8, -10.0, *S), (1.4, -14.0, *S), (2.2, -6.0, *S), (3.6, -8.0, *S), (4.3, 0.0, *S), (L, 0.0)],
                "hd_z": [(0.0, 0.0, *S), (1.1, 6.0, *S), (1.6, 6.0, *S), (2.0, 0.0, *S), (L, 0.0)],
                "wr_x": [(0.0, 0.0, *S), (0.8, 70.0, *S), (3.6, 70.0, *S), (4.3, 0.0, *S), (L, 0.0)],
                "wr_z": [(0.0, 0.0, *S), (0.8, -20.0, *S), (1.2, -34.0, *S), (1.8, -20.0, *S), (3.6, -20.0, *S), (4.3, 0.0, *S), (L, 0.0)]}
        body_keys2(L, base, over, loop=True)
        up_r = (-3.4, -6.0, -7.0)
        key_hands("r", [(0.0, side_r, S), (0.8, up_r, S), (1.2, (-3.0, -7.4, -7.4), S), (1.8, up_r, S), (3.6, up_r, S), (4.3, side_r, S),
                        (L, side_r, None)], L, loop=True)
        pl = (-0.6, -6.4, -6.2)
        key_hands("l", [(0.0, side_l, S), (1.8, side_l, S), (2.2, pl, S), (2.35, (pl[0] - 0.6, pl[1], pl[2] + 0.4), ACC), (2.4, pl, DEC),
                        (2.75, pl, S), (2.9, (pl[0] - 0.6, pl[1], pl[2] + 0.4), ACC), (2.95, pl, DEC), (3.3, pl, S), (3.8, side_l, S),
                        (L, side_l, None)], L, loop=True)
        for s2 in ("rtl", "ltl"):
            ck.key_all({s2: [(0.0, 1.0), (L, 1.0)]}, L, True)
        contract = "5.00 s loop: lifts the bow, sights along the limb, plucks the string twice (2.35, 2.90), lowers it"
    elif variant == 2:
        L = 6.0
        sq = {"hip_y": -7.0, "hip_z": 2.0, "sp_x": 18.0, "hd_x": -6.0, "hip_yaw": 0.0}
        over = {}
        for p, val in sq.items():
            a = base[p]
            over[p] = [(0.0, a, *S), (0.9, val, *DEC), (4.8, val, *S), (5.6, a, *S), (L, a)]
        over["hd_y"] = [(0.0, 0.0, *S), (1.6, 0.0, *S), (2.4, 18.0, *S), (3.2, -16.0, *S), (4.0, 0.0, *S), (L, 0.0)]
        over["hd_z"] = [(0.0, 0.0, *S), (2.4, 10.0, *S), (3.2, -10.0, *S), (4.0, 0.0, *S), (L, 0.0)]
        over["sp_lift"] = [(0.0, 0.0, *S), (1.8, 0.25, *S), (3.0, 0.0, *S), (4.2, 0.25, *S), (L, 0.0)]
        over["wr_x"] = [(0.0, 0.0, *S), (0.9, 80.0, *S), (4.8, 80.0, *S), (5.6, 0.0, *S), (L, 0.0)]
        over["wr_y"] = [(0.0, 0.0, *S), (0.9, 40.0, *S), (4.8, 40.0, *S), (5.6, 0.0, *S), (L, 0.0)]
        for p, d in (("fr_x", -0.6), ("fr_z", 0.8), ("fl_x", 0.6), ("fl_z", 0.2)):
            over[p] = [(0.0, 0.0), (0.2, 0.0, *S), (0.8, d, *S), (4.9, d, *S), (5.5, 0.0, *S), (L, 0.0)]
        body_keys2(L, base, over, loop=True)
        knee_r, knee_l = (-3.6, -6.2, -5.2), (3.6, -6.8, -4.6)     # forearms resting on top of the knees
        key_hands("r", [(0.0, side_r, S), (0.9, knee_r, DEC), (4.8, knee_r, S), (5.6, side_r, S), (L, side_r, None)], L, loop=True)
        key_hands("l", [(0.0, side_l, S), (0.9, knee_l, DEC), (4.8, knee_l, S), (5.6, side_l, S), (L, side_l, None)], L, loop=True)
        for s2 in ("rtl", "ltl"):
            ck.key_all({s2: [(0.0, 1.0), (L, 1.0)]}, L, True)
        contract = "6.00 s loop: squats on his heels, the bow across his knees, a slow look round, stands"
    else:
        L = 5.5
        over = {"sp_x": [(0.0, 4.0, *S), (0.9, -10.0, *S), (1.6, -12.0, *S), (2.3, 4.0, *S), (L, 4.0)],
                "hd_x": [(0.0, -2.0, *S), (0.9, -22.0, *S), (1.6, -26.0, *S), (2.3, -4.0, *S), (3.0, -14.0, *S), (3.8, -12.0, *S),
                         (4.6, -2.0, *S), (L, -2.0)],
                "hd_y": [(0.0, 0.0, *S), (3.0, 0.0, *S), (3.6, 30.0, *S), (4.2, 26.0, *S), (4.8, 0.0, *S), (L, 0.0)],
                "sp_lift": [(0.0, 0.0, *S), (1.2, 0.5, *S), (2.3, 0.0, *S), (L, 0.0)],
                "sp_y": [(0.0, 0.0, *S), (3.0, 0.0, *S), (3.6, 8.0, *S), (4.8, 0.0, *S), (L, 0.0)]}
        body_keys2(L, base, over, loop=True)
        back_r, back_l = (-7.4, -3.0, 3.6), (7.4, -3.0, 3.6)
        key_hands("r", [(0.0, side_r, S), (0.9, back_r, S), (1.6, back_r, S), (2.4, side_r, S), (L, side_r, None)], L, loop=True)
        key_hands("l", [(0.0, side_l, S), (0.9, back_l, S), (1.6, back_l, S), (2.4, side_l, S), (L, side_l, None)], L, loop=True)
        for s2 in ("rtl", "ltl"):
            ck.key_all({s2: [(0.0, 1.0), (L, 1.0)]}, L, True)
        contract = "5.50 s loop: shoulders back in a stretch, a look at the sky, a look round the camp"
    return L, True, make_solve2(L, True, feet), contract, (), None


# ------------------------------------------------------------------ part-2 preview scene (carcass, knife, cleaver, idle bow)
CARCASS_SIZE = (10.0, 5.0, 6.0)           # a pig at CarcassDisplay 0.6, lying on its side, body along X
IDLE_HOLD = ((0.0, -5.7806, 0.472), (0.0, -88.647, -75.9), 1.098)     # SettlerBowHold IDLE (right hand, mirrored)


def _box(name, parent, centre, colour=(0.62, 0.42, 0.36, 1)):
    sx, sy, sz = CARCASS_SIZE
    ob = hsrig.prop_box(name, (-sx / 2, -sy / 2, -sz / 2), (sx, sy, sz), colour, parent_name=parent)
    ob.location = tuple(float(v) for v in centre)
    return ob


def build_scene2(const):
    objs = ck.build(SKIN, right=None)
    M = {}
    if const in ("HUNTER_TAKE_KILL", "HUNTER_HAUL"):
        M["carcass_back"] = _box("carcass_back", objs["torso"].name, CARCASS_REST)
    if const == "HUNTER_TAKE_KILL":
        M["carcass_floor"] = _box("carcass_floor", "MC_SPACE", FLOOR_CARCASS)
    if const.startswith(("HUNTER_SKIN", "HUNTER_BUTCHER")):
        M["carcass_floor"] = _box("carcass_floor", "MC_SPACE", FLOOR_CARCASS)
        kn = ck.display_matrix(mcrig.T(0, -6, 0), ck.HANDHELD, True)
        e = bpy.data.objects.new("item:knife", None)
        bpy.context.scene.collection.objects.link(e)
        e.parent = objs["right_item"]
        # CarcassCarryLayer.drawTool: hand frame . S(0.7) . vanilla handheld
        pre = mcrig.T(0, -6, 0) @ mcrig.T(0, -4, 0) @ mcrig.mat4(mcrig.rx(-math.pi / 2)) @ mcrig.mat4(mcrig.ry(math.pi)) @ mcrig.T(1, 2, -10)
        (rx, ry, rz), (tx, ty, tz), s = ck.HANDHELD
        R = mcrig.rx(math.radians(rx)) @ mcrig.ry(math.radians(ry)) @ mcrig.rz(math.radians(rz))
        e.matrix_basis = Matrix((pre @ mcrig.mat4(np.eye(3), s=0.7) @ mcrig.T(tx, ty, tz) @ mcrig.mat4(R, s=s) @ mcrig.T(-8, -8, -8)).tolist())
        if const.startswith("HUNTER_SKIN"):
            M["knife"] = hsrig.sprite_mesh("mesh:knife", os.path.join(OUT, "props", "iron_sword.png"), e)
        else:
            e2 = bpy.data.objects.new("item:cleaver", None)
            bpy.context.scene.collection.objects.link(e2)
            e2.parent = objs["right_item"]
            e2.matrix_basis = Matrix(kn.tolist())
            M["cleaver"] = hsrig.sprite_mesh("mesh:cleaver", os.path.join(ck.REPO, "src", "main", "resources", "assets",
                                                                          "hearthstead", "textures", "item", "prop_cleaver.png"), e2)
    if const.startswith("IDLE_HUNTER"):
        (t, r, s) = IDLE_HOLD
        pre = mcrig.T(0, -6, 0) @ mcrig.T(0, -4, 0) @ mcrig.mat4(mcrig.rx(-math.pi / 2)) @ mcrig.mat4(mcrig.ry(math.pi)) @ mcrig.T(1, 2, -10)
        R = mcrig.rx(math.radians(r[0])) @ mcrig.ry(math.radians(r[1])) @ mcrig.rz(math.radians(r[2]))
        e = bpy.data.objects.new("item:bow_idle", None)
        bpy.context.scene.collection.objects.link(e)
        e.parent = objs["right_item"]
        e.matrix_basis = Matrix((pre @ mcrig.T(*t) @ mcrig.mat4(R, s=s) @ mcrig.T(-8, -8, -8)).tolist())
        M["bow_idle"] = hsrig.sprite_mesh("mesh:bow_idle", A.BOW_PNG["rest"], e)
    craft = os.path.abspath(os.path.join(HERE, "..", "craft"))
    if craft not in sys.path:
        sys.path.insert(0, craft)
    import propkit  # noqa: E402
    for k in ("knife", "cleaver", "bow_idle"):
        if k in M:
            propkit.recolour_sprite(M[k].name)
    return objs, M


def key_part2(const, times, M):
    """Carcass visibility / the lift path (the same path the Java layer uses)."""
    path = STATE.get("carcass_path")
    for f, t in enumerate(times):
        if "carcass_back" in M:
            ob = M["carcass_back"]
            if const == "HUNTER_TAKE_KILL":
                on = t >= 0.55
                ob.hide_render = not on
                if on and path:
                    pts = [(pt, np.array(pl)) for pt, pl in path]
                    pos = pts[-1][1]
                    for (ta, pa), (tb, pb) in zip(pts, pts[1:]):
                        if t <= tb:
                            u = (t - ta) / (tb - ta)
                            u = u * u * (3 - 2 * u)
                            pos = pa + (pb - pa) * u
                            break
                    ob.location = tuple(float(v) for v in pos)
                    ob.keyframe_insert("location", frame=f)
                M["carcass_floor"].hide_render = on
                M["carcass_floor"].keyframe_insert("hide_render", frame=f)
            else:
                ob.hide_render = False
            ob.keyframe_insert("hide_render", frame=f)


CLIPS = {"HUNTER_READY": ready, "HUNTER_DRAW": draw_, "HUNTER_STALK": lambda: stalk(False),
         "HUNTER_STALK__V2": lambda: stalk(True),
         "HUNTER_TAKE_KILL": take_kill, "HUNTER_HAUL": haul,
         "HUNTER_SKIN_KNEEL": lambda: skin_kneel(False), "HUNTER_SKIN_KNEEL__V2": lambda: skin_kneel(True),
         "HUNTER_BUTCHER_KNEEL": lambda: butcher_kneel(False), "HUNTER_BUTCHER_KNEEL__V2": lambda: butcher_kneel(True),
         "IDLE_HUNTER": lambda: idle_(1), "IDLE_HUNTER__V2": lambda: idle_(2), "IDLE_HUNTER__V3": lambda: idle_(3)}
# locomotion clips keep their source gait's feet exactly, so they keep its ground distance per cycle (engine stride)
STRIDE = {"HUNTER_STALK": (1.4509, 1.0), "HUNTER_HAUL": (0.8789, 1.2)}   # (blocks, source cycle s)
PART2 = {"HUNTER_TAKE_KILL", "HUNTER_HAUL", "HUNTER_SKIN_KNEEL", "HUNTER_SKIN_KNEEL__V2", "HUNTER_BUTCHER_KNEEL",
         "HUNTER_BUTCHER_KNEEL__V2", "IDLE_HUNTER", "IDLE_HUNTER__V2", "IDLE_HUNTER__V3"}


# ------------------------------------------------------------------ scene
def build_scene():
    objs = ck.build(SKIN, right=None)
    STATE["meshes"] = {}
    for key in ("draw", "low"):
        D = STATE["D_DRAW"] if key == "draw" else STATE["D_LOW"]
        e = bpy.data.objects.new("bow:" + key, None)
        bpy.context.scene.collection.objects.link(e)
        e.parent = objs["left_item"]
        e.matrix_basis = Matrix(A.bow_matrix(mcrig.T(0, -6, 0), D).tolist())
        for fr, png in A.BOW_PNG.items():
            STATE["meshes"][(key, fr)] = hsrig.sprite_mesh(f"mesh:bow_{key}_{fr}", png, e)
    al = bpy.data.objects.new("arrow:low", None)
    bpy.context.scene.collection.objects.link(al)
    al.parent = objs["left_item"]
    al.matrix_basis = Matrix((A.pre_chain(mcrig.T(0, -6, 0)) @ STATE["A_LOW"]).tolist())
    STATE["meshes"]["arrow_low"] = hsrig.sprite_mesh("mesh:arrow_low", A.ARROW_VANILLA_PNG, al)
    a = bpy.data.objects.new("arrow:right", None)
    bpy.context.scene.collection.objects.link(a)
    a.parent = objs["right_item"]
    a.matrix_basis = Matrix(ck.display_matrix(mcrig.T(0, -6, 0), ck.HANDHELD, True).tolist())
    STATE["meshes"]["arrow"] = hsrig.sprite_mesh("mesh:arrow", A.ARROW_VANILLA_PNG, a)
    craft = os.path.abspath(os.path.join(HERE, "..", "craft"))
    if craft not in sys.path:
        sys.path.insert(0, craft)
    import propkit  # noqa: E402
    for m in list(STATE["meshes"].values()):
        propkit.recolour_sprite(m.name)
    return objs


def hunter_pull_frame(t):
    """SettlerBowHold (hunter): pull = useTicks / 14 -> p0 / p1 (>= 0.65) / p2 (>= 0.9)."""
    return A.pull_frame(t / HUNT_RELEASE)


def state_for(const, t, props):
    if const == "HUNTER_DRAW":
        if t < HUNT_RELEASE:
            return "draw", hunter_pull_frame(t), False, False
        arrow = bool(props) and props[0]["from"] <= t <= props[0]["to"]
        return "low", "rest", arrow, t >= 1.12
    return "low", "rest", False, True


def run(const, args):
    prepare()
    if const in PART2:
        _HAND_LOG.clear()
        objs, M = build_scene2(const)
        L, loop, solve, contract, keep, props = CLIPS[const]()
        times, samples = hsrig.bake(solve, L, objs)
        key_part2(const, times, M)
    else:
        objs = build_scene()
        L, loop, solve, contract, keep, props = CLIPS[const]()
        times, samples = hsrig.bake(solve, L, objs)
        n = len(times)
        A.key_visibility(n, lambda f: state_for(const, times[min(f, n - 1)], props))
    checks = {"clip": const, "length": L, "loop": loop, "contract": contract}
    checks.update(ck.ground_report(samples))
    print("CHECKS", json.dumps(checks)[:300])
    if args["export"]:
        doc, e = ck.export(const, L, loop, times, samples, keep_times=keep, write=False,
                           meta={"source": "tools/blender/pipeline/clips/roles/hunter.py (Blender "
                                           + bpy.app.version_string + ")", "contract": contract,
                                 "lane": "anim overkill 2026-09-26 (hunter v2, owner: full rework)",
                                 **({"blocks_per_cycle": round(STRIDE[const.split("__")[0]][0] * L / STRIDE[const.split("__")[0]][1], 4)}
                                    if const.split("__")[0] in STRIDE else {})},
                           out_dir=None)
        if props:
            doc["animations"]["animation.settler." + const.lower()]["hearthstead_props"] = props
        os.makedirs(STAGE, exist_ok=True)
        ck.ex.write(doc, os.path.join(STAGE, const.lower() + ".animation.json"))
        print("EXPORTED", const.lower(), "roundtrip", round(e, 4), "-> stage")
    ck.write_report(const.lower(), checks)
    if args.get("three"):
        rk.preview3(const.lower(), L, loop)
    elif args["fast"] or args["full"]:
        ck.preview(const.lower(), L, args, loop=loop)
    return samples


if __name__ == "__main__":
    args = hsrig.parse_args()
    args["three"] = "--three" in sys.argv
    names = list(args["rest"]) or list(CLIPS)
    if names == ["LOWSOLVE"]:
        low_solve()
    else:
        for nm in names:
            run(nm, args)
