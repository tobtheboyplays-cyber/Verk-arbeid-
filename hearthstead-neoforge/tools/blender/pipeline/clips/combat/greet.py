"""Guard greeting (owner spec 2026-09-26), anim overkill lane.

    blender -b --factory-startup --python greet.py -- [--fast|--full] [--no-export] [CONST ...|SEQUENCE]

The guard sees a player / the captain coming (8-10 blocks), stops, faces them and:
  GUARD_ATTENTION_SNAP   0.40 s  (ceremony.py, unchanged) stance -> attention, sword upright
  GUARD_SHEATHE_SWORD    1.00 s  sword swung point-down, slid home into the scabbard at the right
                                 hip (seats with a small overshoot at 0.62), the sword SWAPS from the
                                 hand to the scabbard at SWAP_SHEATHE = 0.70 s, hand rests on the hilt
  GUARD_SALUTE_RAISE     0.30 s  crisp hand salute: fist up the body line to the right brow, elbow
                                 out, a 0.4 px overshoot and dead stop
  GUARD_SALUTE_HOLD_BROW 2.00 s  loop: the held salute, rock still, subtle breathing only
                                 (the runtime adds head tracking of the passing player)
  GUARD_SALUTE_RELEASE   0.35 s  the cut-away: hand straight down onto the hilt
  GUARD_DRAW_SWORD       0.90 s  grip, the sword SWAPS back to the hand at SWAP_DRAW = 0.05 s, drawn
                                 forward-up out of the scabbard, swung upright: ends exactly on
                                 GUARD_ATTENTION (then the runtime cross-fades to stance / patrol)
All absolute full-body clips on the attention feet. SEQUENCE bakes them all in ONE process and
renders the whole greeting (with a stand-in player walking past) to
videos/blender/clips/combat/guard_greeting_sequence/.
"""
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
for p in (HERE, os.path.join(HERE, "..", "roles")):
    p = os.path.abspath(p)
    if p not in sys.path:
        sys.path.insert(0, p)
import bpy  # noqa: E402
import numpy as np  # noqa: E402
from mathutils import Matrix  # noqa: E402

import ceremony as cer  # noqa: E402
import combatkit as ck  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402
import rolekit as rk  # noqa: E402

rk.ck.VIDEO_ROOT = ck.VIDEO_ROOT = os.environ.get(
    "HS_COMBAT_VIDEOS", r"C:\Users\tobia\Hearthstead-Claude\videos\blender\clips\combat")
S, ACC, DEC, DEC3, LIN = ck.SMO, ck.ACC, ck.DEC, ck.DEC3, ck.LIN
c = hsrig.ctrl
FEET = (cer.ATT_FOOT_R, cer.ATT_FOOT_L)
# the vanilla sword in rolekit's item table (sprite: pommel / tip landmarks)
rk.ITEMS.setdefault("sword", dict(display=ck.HANDHELD, a=ck.SWORD_POMMEL, b=ck.SWORD_TIP, boxes=[]))
ARM = rk.ARM6["right"]
SWAP_SHEATHE = 0.70
SWAP_DRAW = 0.05
FRAME_JSON = os.path.join(r"C:\Users\tobia\Hearthstead-Claude\anim-overkill", "scabbard_frame.json")

# scabbard at the RIGHT hip (a cross-draw to the left hip is out of reach of a 10 px Minecraft
# arm): grip just in front of the hanging arm, blade down and back past the outside of the thigh
HILT = np.array([-7.0, 8.8, -3.4])
SHEATH_DIR = np.array([-0.14, 0.70, 0.70])
SHEATH_DIR = SHEATH_DIR / np.linalg.norm(SHEATH_DIR)
BROW = np.array([-5.4, -4.9, -4.6])          # fist at the right brow corner (outer eyebrow)
ELBOW = np.array([-8.2, -1.4, -0.6])         # elbow OUT to the side and up: a proper hand salute
LEFT_SIDE = {"al_x": 1.0, "al_y": 0.0, "al_z": -2.5, "el_l": -6.0, "tw_l": 0.0}


def att_arm():
    arms, _ = cer.attention_arms()
    return [arms[p] for p in ck.ARM_PROPS["right"]] + [0.0]


def body_keys(L, over=None, loop=False):
    vals = dict(cer.ATT)
    vals.update(LEFT_SIDE)
    for p, v in zip(ARM, att_arm()):
        vals[p] = v
    rk.const_keys(vals, L, loop, over)


def solve6(t, hand, d, seed, wrist=True, elbow_pref=None, w_seed=0.004):
    sol, err = rk.solve_arm6(rk.body_at(t), "right", hand, d, item="sword", seed=seed, wrist=wrist,
                             wrist_lim=150.0, w_seed=w_seed, elbow_pref=elbow_pref, w_dir=8.0)
    print("GOAL", t, round(err, 2), [round(v, 1) for v in sol], flush=True)
    return sol


def key_arm(rows, L, loop=False):
    """rows: [(t, sol6, ease|None)] -> the 6 right-arm curves."""
    K = {p: [] for p in ARM}
    for t, sol, e in rows:
        for p, v in zip(ARM, sol):
            K[p].append((t, v, *e) if e else (t, v))
    ck.key_all(K, L, loop)


_cache = {}


def hilt_pose():
    """Arm on the sheathed hilt (the sheathe end / release end / draw start pose)."""
    if "hilt" not in _cache:
        body_keys(1.0)
        _cache["hilt"] = solve6(0.0, HILT, SHEATH_DIR, [-4.0, 8.0, 6.0, -35.0, 0.0, 110.0], w_seed=0.0005)
    return list(_cache["hilt"])


def elbow_solve(channels, hand_goal, elbow_goal, seed, w_elbow=0.6):
    """Right arm FK from a hand point AND an elbow point (the salute's elbow-out shape)."""
    def cost(p):
        ch = dict(channels)
        ch["right_arm"] = {"rot": (p[0], p[1], p[2])}
        ch["right_forearm"] = {"rot": (p[3], p[4], 0.0)}
        w = mcrig.pose_matrices(ch)
        hand = mcrig.xform(w["right_forearm"], (0, 6, 0))
        elbow = mcrig.xform(w["right_arm"], (0, 4, 0))
        c_ = float(np.sum((hand - hand_goal) ** 2)) + w_elbow * float(np.sum((elbow - elbow_goal) ** 2))
        c_ += 0.0002 * p[4] ** 2 + (p[3] ** 2 if p[3] > 0 else 0.0)
        return c_
    b, v = mcrig.nelder_mead(cost, list(seed), [20, 20, 20, 20, 10], iters=1500)
    b, v = mcrig.nelder_mead(cost, b, [3, 3, 3, 3, 3], iters=1000)
    return [float(x) for x in b] + [0.0], float(v)


def brow_pose():
    if "brow" not in _cache:
        body_keys(1.0, {"sp_x": [(0.0, -2.6), (1.0, -2.6)], "hd_x": [(0.0, -4.5), (1.0, -4.5)]})
        sol, err = elbow_solve(rk.body_at(0.0), BROW, ELBOW, [-150.0, 20.0, 60.0, -70.0, 0.0])
        print("BROW", round(err, 2), [round(v, 1) for v in sol])
        _cache["brow"] = sol
    return list(_cache["brow"])


# =========================================================================== SHEATHE
def sheathe():
    L = 1.0
    att, hilt = att_arm(), hilt_pose()
    over = {
        "sp_y": [(0.0, 0.0), (0.12, 2.0, *S), (0.40, -8.0, *S), (0.62, -5.0, *S), (0.80, 0.0, *DEC), (1.0, 0.0)],
        "hd_x": [(0.0, -4.0), (0.12, -2.0, *S), (0.40, 10.0, *S), (0.62, 13.0, *S), (0.82, -5.0, *DEC),
                 (0.92, -4.0, *S), (1.0, -4.0)],
        "hd_y": [(0.0, 0.0), (0.30, 0.0, *S), (0.55, -8.0, *S), (0.82, 0.0, *DEC), (1.0, 0.0)],
        "hip_y": [(0.0, -0.1), (0.10, -0.25, *S), (0.30, -0.1, *S), (0.62, -0.45, *DEC), (0.78, -0.05, *S),
                  (1.0, -0.1)],
        "sp_x": [(0.0, -1.5), (0.30, 2.0, *S), (0.62, 4.0, *S), (0.84, -2.2, *DEC), (1.0, -1.8)],
        "ck_x": [(0.0, 2.0), (0.6, 3.5), (1.0, 2.0)],
        "al_x": [(0.0, 1.0), (0.40, -3.0, *S), (0.70, 2.0, *S), (1.0, 1.0)],
    }
    body_keys(L, over)
    far = HILT - 8.0 * SHEATH_DIR       # tip already in the mouth: the slide home
    rows = [(0.0, att, S)]
    s = solve6(0.08, cer.ATT_HAND + np.array([0.0, -0.5, 0.3]), cer.ATT_DIR + np.array([0, 0, 0.1]), att,
               wrist=False)
    s[5] = 0.0
    rows.append((0.08, s, ACC))                                              # anticipation: lift
    s = solve6(0.20, np.array([-5.2, 6.0, -8.2]), np.array([-0.05, -0.10, -0.99]), s)
    rows.append((0.20, s, LIN))                                              # blade tips forward
    s = solve6(0.30, np.array([-5.6, 4.6, -8.8]), np.array([-0.14, 0.62, -0.77]), s)
    rows.append((0.30, s, S))                                                # point swings down
    s = solve6(0.40, far, SHEATH_DIR, s)
    rows.append((0.40, s, DEC))                                              # point in the mouth
    s = solve6(0.62, HILT + 0.45 * SHEATH_DIR, SHEATH_DIR, s)
    rows.append((0.62, s, S))                                                # slid home, seats
    rows.append((SWAP_SHEATHE, hilt, S))                                     # SWAP to the scabbard
    rows.append((L, hilt, None))
    key_arm(rows, L)
    solve = rk.make_solve(L, False, FEET, item=None)
    return L, False, solve, ("1.00 s one-shot from GUARD_ATTENTION: sword point-down 0.30, slid home 0.40-0.62, "
                             "seated / swapped to the scabbard at 0.70, hand rests on the hilt"), (0.08, 0.2, 0.3, 0.4, 0.62, 0.7)


# =========================================================================== RAISE / HOLD / RELEASE
PROUD = {"sp_x": -2.6, "hd_x": -4.5, "hip_y": 0.0}


def raise_():
    L = 0.30
    hilt, brow = hilt_pose(), brow_pose()
    over = {
        "sp_x": [(0.0, -1.8), (0.05, -1.4, *S), (0.20, -3.0, *DEC), (0.30, PROUD["sp_x"])],
        "hd_x": [(0.0, -4.0), (0.18, -5.2, *DEC), (0.30, PROUD["hd_x"])],
        "hip_y": [(0.0, -0.1), (0.05, -0.2, *S), (0.20, 0.08, *DEC), (0.30, PROUD["hip_y"])],
        "sp_lift": [(0.0, 0.1), (0.20, 0.25, *DEC), (0.30, 0.2)],
    }
    body_keys(L, over)
    mid = rk.solve_arm6(rk.body_at(0.10), "right", np.array([-6.0, 3.0, -6.2]), None, seed=hilt, wrist=False,
                        pole_out=4.0, w_seed=0.0005)[0]
    over_b = elbow_solve(rk.body_at(0.22), BROW + np.array([0.0, -0.45, -0.15]), ELBOW + np.array([0, -0.3, 0]),
                         brow[:5])[0]
    mid[5] = over_b[5] = 0.0
    key_arm([(0.0, hilt, ACC), (0.10, mid, LIN), (0.22, over_b, S), (L, brow, None)], L)
    solve = rk.make_solve(L, False, FEET, item=None)
    return L, False, solve, "0.30 s one-shot: hilt -> hand salute at the right brow, overshoot 0.22, dead stop 0.30", (0.1, 0.22)


def hold():
    L = 2.0
    brow = brow_pose()
    over = {
        "sp_lift": [(0.0, 0.2, *S), (1.0, 0.38, *S), (2.0, 0.2)],
        "sp_x": [(0.0, PROUD["sp_x"], *S), (1.0, PROUD["sp_x"] - 0.5, *S), (2.0, PROUD["sp_x"])],
        "hd_x": [(0.0, PROUD["hd_x"], *S), (1.0, PROUD["hd_x"] + 0.4, *S), (2.0, PROUD["hd_x"])],
        "hip_y": [(0.0, 0.0, *S), (1.0, 0.05, *S), (2.0, 0.0)],
        "ck_x": [(0.0, 2.0, *S), (1.0, 2.5, *S), (2.0, 2.0)],
    }
    body_keys(L, over, loop=True)
    key_arm([(0.0, brow, S), (L, brow, None)], L, loop=True)
    solve = rk.make_solve(L, True, FEET, item=None, cloak_drag=False)
    return L, True, solve, "2.00 s loop: the held hand salute, breathing only (runtime adds head tracking)", ()


def release():
    L = 0.35
    hilt, brow = hilt_pose(), brow_pose()
    over = {
        "sp_x": [(0.0, PROUD["sp_x"]), (0.20, -1.0, *DEC), (0.35, -1.8)],
        "hd_x": [(0.0, PROUD["hd_x"]), (0.35, -4.0)],
        "hip_y": [(0.0, 0.0), (0.18, -0.2, *DEC), (0.35, -0.1)],
        "sp_lift": [(0.0, 0.2), (0.35, 0.1)],
    }
    body_keys(L, over)
    up = elbow_solve(rk.body_at(0.04), BROW + np.array([-0.2, -0.3, -0.3]), ELBOW, brow[:5])[0]
    mid = rk.solve_arm6(rk.body_at(0.14), "right", np.array([-6.8, 2.4, -5.6]), None, seed=up, wrist=False,
                        pole_out=4.0, w_seed=0.0005)[0]
    up[5] = mid[5] = 0.0
    near = list(hilt)
    near[0] -= 3.0
    key_arm([(0.0, brow, S), (0.04, up, ACC), (0.14, mid, LIN), (0.26, near, DEC), (L, hilt, None)], L)
    solve = rk.make_solve(L, False, FEET, item=None)
    return L, False, solve, "0.35 s one-shot: the cut-away, brow -> hand on the hilt by 0.26", (0.04, 0.14, 0.26)


# =========================================================================== DRAW
def draw():
    L = 0.90
    hilt, att = hilt_pose(), att_arm()
    over = {
        "sp_y": [(0.0, 0.0), (SWAP_DRAW, 0.0, *S), (0.30, -9.0, *DEC), (0.50, -4.0, *S), (0.72, 1.0, *S), (0.90, 0.0)],
        "hd_x": [(0.0, -4.0), (0.05, -3.0, *S), (0.22, 6.0, *S), (0.45, -5.5, *DEC), (0.9, -4.0)],
        "sp_x": [(0.0, -1.8), (0.30, 3.0, *S), (0.62, -2.6, *DEC), (0.90, -1.5)],
        "hip_y": [(0.0, -0.1), (0.25, -0.35, *S), (0.55, 0.05, *S), (0.72, -0.18, *S), (0.9, -0.1)],
        "ck_x": [(0.0, 2.0), (0.4, 4.0), (0.9, 2.0)],
        "al_x": [(0.0, 1.0), (0.30, 4.0, *S), (0.60, -2.0, *S), (0.9, 1.0)],
    }
    body_keys(L, over)
    out = HILT - 8.0 * SHEATH_DIR
    s1 = solve6(0.30, out, SHEATH_DIR, hilt)                                           # drawn along the axis
    s2 = solve6(0.44, np.array([-5.0, 3.4, -9.0]), np.array([-0.08, -0.30, -0.95]), s1)   # point swings forward-up
    s3 = solve6(0.62, cer.ATT_HAND + np.array([0.1, -0.5, -0.6]), np.array([0.02, -0.99, -0.12]), s2,
                wrist=True)                                                             # up, overshoot
    key_arm([(0.0, hilt, S), (SWAP_DRAW, hilt, ACC), (0.30, s1, LIN), (0.44, s2, DEC), (0.62, s3, S),
             (0.80, att, S), (L, att, None)], L)
    solve = rk.make_solve(L, False, FEET, item=None)
    return L, False, solve, ("0.90 s one-shot: grip, swap to the hand at 0.05, drawn forward-up 0.05-0.30, "
                             "blade upright by 0.62 (overshoot), ends on GUARD_ATTENTION"), (SWAP_DRAW, 0.3, 0.44, 0.62, 0.8)


CLIPS = {"GUARD_SHEATHE_SWORD": sheathe, "GUARD_SALUTE_RAISE": raise_, "GUARD_SALUTE_HOLD_BROW": hold,
         "GUARD_SALUTE_RELEASE": release, "GUARD_DRAW_SWORD": draw}


# =========================================================================== scabbard frame
def scabbard_frame(sample):
    """Torso-relative 'translateToHand' frame (right_item @ T(0,-10,0)) at the seated hilt."""
    w = mcrig.pose_matrices(sample)
    arm_eq = w["right_item"] @ mcrig.T(0, -10, 0)
    return np.linalg.inv(w["torso"]) @ arm_eq


SCABBARD_BOXES = [  # item-sprite space (x right, y up), along the sword's diagonal
    ((4.6, 4.6), (16.0, 16.0), 1.55, 1.1, (0.28, 0.17, 0.09, 1.0)),    # leather body
    ((4.4, 4.4), (6.2, 6.2), 1.85, 1.35, (0.55, 0.47, 0.30, 1.0)),     # brass locket (mouth)
    ((14.6, 14.6), (16.2, 16.2), 1.8, 1.3, (0.55, 0.47, 0.30, 1.0)),   # chape
]


def scabbard_mesh(parent):
    for i, (a, b, half_w, half_d, col) in enumerate(SCABBARD_BOXES):
        a, b = np.array(a), np.array(b)
        ax = (b - a) / np.linalg.norm(b - a)
        nx = np.array([-ax[1], ax[0]])
        vs = []
        for p in (a, b):
            for sw in (-1, 1):
                for sd in (-1, 1):
                    q = p + nx * half_w * sw
                    vs.append((q[0], q[1], 8.0 + sd * half_d))
        f = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
        me = bpy.data.meshes.new(f"scabbard{i}")
        me.from_pydata(vs, [], f)
        me.materials.append(hsrig._material(f"scabbard{i}_mat", colour=col))
        ob = bpy.data.objects.new(f"mesh:scabbard{i}", me)
        bpy.context.scene.collection.objects.link(ob)
        ob.parent = parent


# =========================================================================== run
def bake_one(const, args, export=True, preview=True):
    objs = ck.build("settler_guard.png", right="sword")
    L, loop, solve, contract, keep = CLIPS[const]()
    times, samples = hsrig.bake(solve, L, objs)
    checks = {"clip": const, "length": L, "loop": loop, "contract": contract}
    checks.update(ck.ground_report(samples))
    if const == "GUARD_SHEATHE_SWORD":
        i = int(round(SWAP_SHEATHE * ck.FPS))
        F = scabbard_frame(samples[i])
        drift = max(float(np.abs(scabbard_frame(samples[j]) - F).max()) for j in range(i, len(samples)))
        checks["scabbard_hold_drift"] = round(drift, 4)
        os.makedirs(os.path.dirname(FRAME_JSON), exist_ok=True)
        json.dump({"torso_to_arm_frame": F.tolist(), "note": "px; Java: translate(t/16) then rotation"},
                  open(FRAME_JSON, "w"), indent=1)
        print("SCABBARD_FRAME", json.dumps([[round(v, 5) for v in r] for r in F.tolist()]))
    print("CHECKS", json.dumps(checks))
    if export and args["export"]:
        doc, e = ck.export(const, L, loop, times, samples, keep_times=keep,
                           meta={"source": "tools/blender/pipeline/clips/combat/greet.py (Blender "
                                           + bpy.app.version_string + ")", "contract": contract,
                                 "lane": "anim overkill 2026-09-26 (guard greeting)"},
                           out_dir=ck.out_dir(const.lower()))
        checks["roundtrip_max_err"] = round(e, 4)
    ck.write_report(const.lower(), checks)
    if preview:
        ck.preview(const.lower(), L, args, loop=loop, step=2 if (loop and L >= 2.0) else 1)
    return L, loop, samples


def sequence(args):
    """Every greeting clip back to back with a stand-in player walking past; one reel."""
    base, B = ck.stance_base()
    # SNAP from the shipped JSON (ceremony.py owns it), sampled with the runtime interpolation
    import export_mc_clip as ex
    snap_doc = json.load(open(os.path.join(ck.ANIM_DIR, "guard_attention_snap.animation.json")))
    snap_anim = "animation.settler.guard_attention_snap"
    snap_len = float(snap_doc["animations"][snap_anim]["animation_length"])
    snap_bones = snap_doc["animations"][snap_anim]["bones"]

    def snap_at(t):
        out = {}
        for bn, kinds in snap_bones.items():
            out[bn] = {}
            if "rotation" in kinds:
                out[bn]["rot"] = tuple(ex.sample(snap_doc, snap_anim, bn, "rotation", t))
            if "position" in kinds:
                out[bn]["pos"] = tuple(ex.sample(snap_doc, snap_anim, bn, "position", t))
        return out
    parts = []
    for const in ("GUARD_SHEATHE_SWORD", "GUARD_SALUTE_RAISE", "GUARD_SALUTE_HOLD_BROW", "GUARD_SALUTE_RELEASE",
                  "GUARD_DRAW_SWORD"):
        L, loop, samples = bake_one(const, args, export=args["export"], preview=False)
        parts.append((const, L, loop, samples))

    FPS = ck.FPS
    seq, marks = [], {}
    seq += [base] * int(0.6 * FPS)                                              # stance, sees the player
    marks["snap"] = len(seq)
    seq += [snap_at(i / FPS) for i in range(int(round(snap_len * FPS)))]
    for const, L, loop, samples in parts:
        marks[const] = len(seq)
        if const == "GUARD_SALUTE_HOLD_BROW":
            # held while the player walks past; released ~4 blocks past him (runtime rule)
            cyc = samples[:-1]
            seq += [cyc[i % len(cyc)] for i in range(int(2.3 * FPS))]
        else:
            seq += samples[:-1]
    seq += [parts[-1][3][-1]] * int(0.5 * FPS)
    T = len(seq)
    total = (T - 1) / FPS
    sheathed = set(range(marks["GUARD_SHEATHE_SWORD"] + int(round(SWAP_SHEATHE * FPS)),
                         marks["GUARD_DRAW_SWORD"] + int(round(SWAP_DRAW * FPS))))
    hold0, rel0 = marks["GUARD_SALUTE_HOLD_BROW"], marks["GUARD_SALUTE_RELEASE"]

    # ---- scene
    objs = ck.build("settler_guard.png", right="sword")
    F = np.array(json.load(open(FRAME_JSON))["torso_to_arm_frame"])
    hip = bpy.data.objects.new("item:hip_sword", None)
    bpy.context.scene.collection.objects.link(hip)
    hip.parent = objs["torso"]
    rel = F @ mcrig.T(0, 4, 0)   # display_matrix expects the forearm-equivalent frame
    hip.matrix_basis = Matrix(ck.display_matrix(rel, ck.HANDHELD, True).tolist())
    hip_mesh = hsrig.sprite_mesh("mesh:hip_sword", ck.SWORD_PNG, hip)
    scabbard_mesh(hip)
    hand_mesh = bpy.data.objects["mesh:sword"]
    # entity yaw above MC_SPACE; the walking stand-in lives in an un-rotated MC space
    space = bpy.data.objects["MC_SPACE"]
    ent = bpy.data.objects.new("ENTITY", None)
    bpy.context.scene.collection.objects.link(ent)
    space.parent = ent
    world = bpy.data.objects.new("WORLD_MC", None)
    bpy.context.scene.collection.objects.link(world)
    world.matrix_basis = hsrig.MC_TO_BLENDER
    player = []
    for nm, frm, size, col in (("p_legs", (-4, 12, -2), (8, 12, 4), (0.22, 0.25, 0.45, 1)),
                               ("p_body", (-4, 0, -2), (8, 12, 4), (0.15, 0.55, 0.60, 1)),
                               ("p_head", (-4, -8, -4), (8, 8, 8), (0.80, 0.62, 0.48, 1)),
                               ("p_arm_r", (-8, 0, -2), (4, 12, 4), (0.80, 0.62, 0.48, 1)),
                               ("p_arm_l", (4, 0, -2), (4, 12, 4), (0.80, 0.62, 0.48, 1))):
        player.append(hsrig.prop_box(nm, frm, size, col, parent_name="WORLD_MC"))
    # the player walks from ahead-right to behind the guard (3 blocks/s), passing 1.9 blocks to his right
    speed = 3.0 * 16.0
    t_snap = marks["snap"] / FPS
    z0 = -9.0 * 16.0 + speed * 0.0
    px = -38.0

    def ppos(t):
        return np.array([px, 0.0, -9.0 * 16.0 + speed * (t - t_snap + 0.35)])
    body_prev = 0.0
    sc_ = bpy.context.scene
    sc_.frame_start, sc_.frame_end = 0, T - 1
    for f in range(T):
        t = f / FPS
        ch = {b: dict(v) for b, v in seq[f].items()}
        p = ppos(t)
        a = math.degrees(math.atan2(-p[0], -p[2]))            # + = toward the guard's right
        tracking = f >= marks["snap"] and f < rel0 + int(0.2 * FPS)
        body_goal = max(-70.0, min(70.0, a - 15.0)) if (hold0 <= f < rel0) else 0.0
        body = body_prev + max(-2.0, min(2.0, body_goal - body_prev))   # <= 120 deg/s turn
        body_prev = body
        head = max(-15.0, min(15.0, a - body)) if tracking else 0.0
        hd = ch.get("head", {}).get("rot", (0, 0, 0))
        ch["head"] = {"rot": (hd[0], hd[1] + head, hd[2])}
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
        ent.rotation_euler = (0.0, 0.0, math.radians(body))
        ent.keyframe_insert("rotation_euler", frame=f)
        for ob in player:
            ob.location = (float(p[0]), 0.0, float(p[2]))
            ob.keyframe_insert("location", frame=f)
        sh = f in sheathed
        for ob, vis in ((hip_mesh, sh), (hand_mesh, not sh)):
            ob.hide_render = not vis
            ob.keyframe_insert("hide_render", frame=f)
    for ob in list(objs.values()) + [ent] + player:
        ad = ob.animation_data
        if ad and ad.action:
            for fc in ad.action.fcurves:
                for kp in fc.keyframe_points:
                    kp.interpolation = "LINEAR"
    # ---- render: wide front-left three-quarter (player passes on the far side) + a close side
    d = ck.out_dir("guard_greeting_sequence")
    ck._setup_workbench((960, 720) if args["full"] else (640, 480))
    cams = {"wide": hsrig.camera("cam_wide", (0.9, -5.2, 1.9), (-0.7, -0.2, 0.9), lens=28),
            "close": hsrig.camera("cam_close", (-1.1, -2.8, 1.6), (0.0, -0.1, 1.15), lens=40)}
    step = 2
    frames = list(range(0, T, step))
    for name, cam in cams.items():
        fd = os.path.join(d, "_frames_" + name)
        os.makedirs(fd, exist_ok=True)
        for fn in os.listdir(fd):
            os.remove(os.path.join(fd, fn))
        sc_.camera = cam
        for i, f in enumerate(frames):
            sc_.frame_set(f)
            sc_.render.filepath = os.path.join(fd, f"f{i:04d}.png")
            bpy.ops.render.render(write_still=True)
        ck._encode(fd, len(frames), FPS // step, os.path.join(d, f"greeting_{name}.mp4"))
        ck._encode(fd, len(frames), FPS // step // 2, os.path.join(d, f"greeting_{name}_half.mp4"))
        k = 12
        idx = [int(round(i * (len(frames) - 1) / (k - 1))) for i in range(k)]
        hsrig._sheet([os.path.join(fd, f"f{i:04d}.png") for i in idx], os.path.join(d, f"sheet_{name}.png"))
    json.dump({"marks_s": {k: round(v / FPS, 3) for k, v in marks.items()}, "total_s": round(total, 2)},
              open(os.path.join(d, "sequence.json"), "w"), indent=1)
    print("SEQUENCE", d, round(total, 2), "s")


if __name__ == "__main__":
    args = hsrig.parse_args()
    names = args["rest"] or list(CLIPS)
    if names == ["SEQUENCE"]:
        sequence(args)
    else:
        for n in names:
            bake_one(n, args)
