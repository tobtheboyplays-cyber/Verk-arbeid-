"""Battle-role clip kit (anim overkill lane, 2026-09-26): spearman, longswordsman, rune mage,
healer. Builds on combatkit (rig, legs IK, CTRL curves, export, previews) WITHOUT modifying it.

What it adds:
  * the role items as they are really drawn: 3D block-model spear / longsword with their own
    thirdperson display transforms (models/item/*_held.json), as box meshes in the preview and as
    axis points for solving and QA;
  * a 6-dof arm solve (shoulder xyz, elbow, forearm twist + WRIST pitch on right_item) so a
    polearm/longsword can stay level while the arm swings (the vanilla hold pins the shaft at
    right angles to the forearm);
  * a two-handed grip: the left hand IK'd onto the shaft line `gl_s` px from the right fist
    (+ toward the tip), weight `gl_w` -- the same left_ik hook combatkit.make_solve exposes;
  * a grip slide on right_item.pos along the shaft (`gr_s` px, + toward the tip);
  * a continuity term in every solve (no shoulder-branch switches between keys).

All role clips are ABSOLUTE full-body clips (SettlerModel resets first). One-shots start and
end exactly on their role idle's t=0 pose.
"""
from __future__ import annotations

import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
COMBAT = os.path.abspath(os.path.join(HERE, "..", "combat"))
for p in (HERE, COMBAT):
    if p not in sys.path:
        sys.path.insert(0, p)
import bpy  # noqa: E402
import numpy as np  # noqa: E402
from mathutils import Matrix  # noqa: E402

import combatkit as ck  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402

ck.VIDEO_ROOT = os.environ.get("HS_ROLE_VIDEOS", r"C:\Users\tobia\Hearthstead-Claude\videos\blender\clips\roles")
c = hsrig.ctrl
FPS = hsrig.FPS
SMO, ACC, ACC4, DEC, DEC3, LIN = ck.SMO, ck.ACC, ck.ACC4, ck.DEC, ck.DEC3, ck.LIN
ARM6 = {"right": ("ar_x", "ar_y", "ar_z", "el_r", "tw_r", "wr_x"),
        "left": ("al_x", "al_y", "al_z", "el_l", "tw_l", "wl_x")}

WOOD = (0.45, 0.31, 0.18, 1.0)
IRON = (0.74, 0.75, 0.78, 1.0)
DARK = (0.30, 0.30, 0.33, 1.0)
LEATHER = (0.36, 0.22, 0.12, 1.0)
# item-native block-model coordinates (0..16 cube, y UP), from models/item/iron_*_held.json
ITEMS = {
    "spear": dict(display=((10.0, 0.0, 0.0), (0.0, 2.0, 1.5), 0.85),
                  a=(8.0, -10.0, 8.0), b=(8.0, 29.0, 8.0),
                  boxes=[((7.5, -9, 7.5), (1, 31, 1), WOOD), ((7.25, 20, 7.25), (1.5, 2.5, 1.5), DARK),
                         ((6.75, 22.5, 7.6), (2.5, 4, 0.8), IRON), ((7.4, 26.5, 7.75), (1.2, 2.5, 0.5), IRON),
                         ((7.25, -10, 7.25), (1.5, 1, 1.5), DARK)]),
    "longsword": dict(display=((-20.0, 0.0, 0.0), (0.0, 3.0, 1.0), 0.65),   # held model: 22 px long
                      a=(8.0, -5.5, 8.0), b=(8.0, 28.5, 8.0),
                      boxes=[((7.5, 4, 7.75), (1, 23, 0.5), IRON), ((7.75, 27, 7.85), (0.5, 1.5, 0.3), IRON),
                             ((4.5, 3, 7.5), (7, 1, 1), DARK), ((7.6, -4, 7.6), (0.8, 7, 0.8), LEATHER),
                             ((7.25, -5.5, 7.25), (1.5, 1.5, 1.5), DARK)]),
}


# --------------------------------------------------------------------------- item geometry
def item_frame(world, item, side="right"):
    return ck.display_matrix(ck.item_base(world, side), ITEMS[item]["display"], side == "right")


def item_axis(world, item, side="right"):
    """World (model px) butt/pommel point a, tip b of the held item."""
    f = item_frame(world, item, side)
    return mcrig.xform(f, ITEMS[item]["a"]), mcrig.xform(f, ITEMS[item]["b"])


# blade / shaft segments checked against the body (item-native coordinates): from, to, crossguard
BLADE = {"spear": ((8.0, -9.0), (8.0, 29.0), None),
         "longsword": ((8.0, 4.0), (8.0, 28.5), ((4.6, 3.5), (11.4, 3.5)))}
BODY_BOXES = ("torso", "head", "right_arm", "left_arm", "left_forearm", "right_leg", "left_leg", "right_shin",
              "left_shin")


def blade_points(world, item, side="right", step=1.5):
    (x0, y0), (x1, y1), guard = BLADE[item]
    n = max(2, int(abs(y1 - y0) / step) + 1)
    pts = [(x0 + (x1 - x0) * i / (n - 1), y0 + (y1 - y0) * i / (n - 1), 8.0) for i in range(n)]
    if guard:
        pts += [(guard[0][0] + (guard[1][0] - guard[0][0]) * i / 4.0, guard[0][1], 8.0) for i in range(5)]
    f = item_frame(world, item, side)
    P = np.array(pts)
    return (np.c_[P, np.ones(len(P))] @ f.T)[:, :3]


def blade_depth(world, item, margin=0.8, side="right"):
    """Sum of squared depths (plus margin) of the blade/shaft inside the body boxes; points within
    3 px of either hand (the grip) are ignored for the arms."""
    pts = blade_points(world, item, side)
    hands = [mcrig.xform(world["right_forearm"], (0, 6, 0)), mcrig.xform(world["left_forearm"], (0, 6, 0))]
    near = np.min([np.linalg.norm(pts - h, axis=1) for h in hands], axis=0)
    total, worst = 0.0, 0.0
    for part in BODY_BOXES:
        (fx, fy, fz), (sx, sy, sz) = mcrig.PARTS[part][2][0][0], mcrig.PARTS[part][2][0][1]
        g = mcrig.LEG_GIRTH if part.endswith("_leg") or part.endswith("_shin") else (1.0, 1.0, 1.0)
        use = pts[near > 3.0] if part in ("right_arm", "left_arm", "left_forearm") else pts
        if not len(use):
            continue
        inv = np.linalg.inv(world[part])
        L = (np.c_[use, np.ones(len(use))] @ inv.T)[:, :3]
        lo = np.array([fx * g[0], fy, fz * g[2]]) - margin
        hi = np.array([(fx + sx) * g[0], fy + sy, (fz + sz) * g[2]]) + margin
        d = np.minimum(L - lo, hi - L).min(axis=1)
        d = d[d > 0]
        if len(d):
            total += float(np.sum(d ** 2))
            worst = max(worst, float(d.max()) - margin)
    return total, worst


def item_dir(world, item, side="right"):
    a, b = item_axis(world, item, side)
    d = b - a
    return d / np.linalg.norm(d)


def _box_mesh(name, frm, size, colour, parent):
    me = bpy.data.meshes.new(name)
    x0, y0, z0 = frm
    x1, y1, z1 = x0 + size[0], y0 + size[1], z0 + size[2]
    v = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
         (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    f = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (2, 3, 7, 6), (1, 2, 6, 5), (0, 4, 7, 3)]
    me.from_pydata(v, [], f)
    me.materials.append(hsrig._material(name + "_mat", colour=colour))
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    ob.parent = parent
    return ob


def build(tex="settler_guard.png", item=None, props=()):
    """Scene: settler rig (+ ground) + the held role item as box meshes on the wrist bone."""
    objs = ck.build(tex, right=None)
    if item:
        eye = mcrig.T(0, -6, 0) if "right_item" in objs else np.eye(4)
        par = objs.get("right_item", objs["right_forearm"])
        e = bpy.data.objects.new("item:" + item, None)
        bpy.context.scene.collection.objects.link(e)
        e.parent = par
        e.matrix_basis = Matrix(ck.display_matrix(eye, ITEMS[item]["display"], True).tolist())
        for i, (frm, size, col) in enumerate(ITEMS[item]["boxes"]):
            _box_mesh(f"mesh:{item}{i}", frm, size, col, e)
    for p in props:
        hsrig.prop_box(*p)
    return objs


# --------------------------------------------------------------------------- 6-dof arm solve
def _reach_clamp(channels, side, goal, lim=9.6):
    wt = mcrig.pose_matrices(channels)["torso"]
    sh = mcrig.xform(wt, (-6.0 if side == "right" else 6.0, -10.0, 0.0))
    v = goal - sh
    d = float(np.linalg.norm(v))
    return sh + v * (lim / d) if d > lim else goal


def solve_arm6(channels, side, hand_goal, dir_goal=None, item=None, seed=(-30, 0, 0, -40, 0, 0),
               w_dir=8.0, w_seed=0.004, pole_out=2.0, wrist_lim=75.0, wrist=True, elbow_pref=None,
               avoid_head=1.0, w_blade=6.0):
    """Hand point (+ held item direction) -> (arm x,y,z, elbow, twist, wrist x). The wrist is only
    a free dof when `wrist` and an item direction is asked for."""
    arm, fore, itb = side + "_arm", side + "_forearm", side + "_item"
    hand_goal = _reach_clamp(channels, side, np.asarray(hand_goal, float))
    hd = None if dir_goal is None else np.asarray(dir_goal, float) / np.linalg.norm(dir_goal)
    use_w = wrist and hd is not None and item is not None
    base_it = channels.get(itb, {}).get("rot", (0.0, 0.0, 0.0))
    seed = [float(v) for v in seed]
    if not use_w:
        seed[5] = float(base_it[0])
    sgn = -1 if side == "right" else 1

    def pose(p):
        ch = dict(channels)
        ch[arm] = {"rot": (p[0], p[1], p[2])}
        ch[fore] = {"rot": (p[3], p[4], 0.0)}
        if use_w:
            ch[itb] = {"rot": (p[5], base_it[1], base_it[2])}
        return mcrig.pose_matrices(ch)

    def cost(p):
        w = pose(p)
        hand = mcrig.xform(w[fore], (0, 6, 0))
        cst = float(np.sum((hand - hand_goal) ** 2))
        if hd is not None and item is not None:
            cst += w_dir * 25.0 * float(np.sum((item_dir(w, item, side) - hd) ** 2))
        cst += 0.0002 * p[4] ** 2
        if p[3] > 0:
            cst += p[3] ** 2
        if p[3] < -140:
            cst += (p[3] + 140) ** 2
        if elbow_pref is not None:
            cst += 0.02 * (p[3] - elbow_pref) ** 2
        if use_w and abs(p[5]) > wrist_lim:
            cst += (abs(p[5]) - wrist_lim) ** 2
        n = 6 if use_w else 5
        cst += w_seed * sum((float(p[i]) - seed[i]) ** 2 for i in range(n))
        if pole_out:
            elbow = mcrig.xform(w[arm], (0, 4, 0))
            sh = mcrig.xform(w[arm], (0, 0, 0))
            cst += pole_out * max(0.0, -(elbow[0] - sh[0]) * sgn) ** 2
        if w_blade and item in BLADE:
            cst += w_blade * blade_depth(w, item)[0]
        if avoid_head:
            # keep the forearm out of the head box (head-local, 8 px cube + 0.6 margin)
            ih = np.linalg.inv(w["head"])
            for q in ((0, 0, 0), (0, 3, 0), (0, 6, 0)):
                hp = mcrig.xform(ih, mcrig.xform(w[fore], q))
                inside = min(4.6 - abs(hp[0]), 4.6 - abs(hp[2]), min(hp[1] + 8.6, 0.6 - hp[1]))
                if inside > 0:
                    cst += avoid_head * 4.0 * inside ** 2
        return cst

    def run(x0):
        b, v = mcrig.nelder_mead(cost, list(x0), [15, 15, 15, 15, 10, 12], iters=1000)
        b, v = mcrig.nelder_mead(cost, b, [4, 4, 4, 4, 3, 4], iters=900)
        return b, v

    best, val = run(seed)
    if val > 25.0:
        rng = np.random.default_rng(4321)
        cands = [(val, best)]
        for _ in range(8):
            x0 = np.array(seed) + rng.normal(0, [35, 35, 30, 30, 40, 25])
            x0[3] = min(-5.0, x0[3])
            b, v = run(x0)
            cands.append((v, b))
        val, best = min(cands, key=lambda vb: vb[0])
    best, val = mcrig.nelder_mead(cost, best, [1, 1, 1, 1, 1, 1], iters=600)
    if not use_w:
        best[5] = float(base_it[0])
    return [float(v) for v in best], float(val)


def body_at(t):
    return ck.body_channels(lambda p: c(p, t))


def arm_goals(goals, side="right", item=None, seed=None, length=None, loop=False, w_dir=8.0, w_seed=0.004,
              wrist=True, pole_out=2.0, elbow_pref=None, close=True):
    """goals: [(t, hand|None, dir|None, ease|None)] (hand None = hold the previous solution)
    -> keys on the 6 arm props (wrist key only when solved). Returns the solve log."""
    props = ARM6[side]
    keys = {p: [] for p in props}
    log = []
    prev = list(seed) if seed is not None else [-20, 0, 0, -40, 0, 0]
    for g in goals:
        t, hand, d, e = g[0], g[1], g[2], g[3]
        if hand is None:
            sol, err = list(prev), 0.0
        else:
            sol, err = solve_arm6(body_at(t), side, hand, d, item=item, seed=prev, w_dir=w_dir, w_seed=w_seed,
                                  wrist=wrist, pole_out=pole_out, elbow_pref=elbow_pref)
        prev = sol
        log.append({"t": t, "sol": [round(v, 1) for v in sol], "cost": round(err, 2)})
        print("GOAL", side, t, round(err, 2), [round(v, 1) for v in sol], flush=True)
        for p, v in zip(props, sol):
            if p in ("wr_x", "wl_x") and not (wrist and d is not None and item is not None):
                continue
            keys[p].append((t, v, *e) if e else (t, v))
    L = length if length is not None else goals[-1][0]
    for p, ks in keys.items():
        if not ks:
            continue
        if loop and close and abs(ks[-1][0] - L) > 1e-6:
            ks.append((L, ks[0][1]))
        hsrig.key_curve(p, ks, cyclic=loop, length=L)
    return log


def key(K, L, loop):
    ck.key_all(K, L, loop)


def const_keys(vals, L, loop, over=None):
    K = {p: [(0.0, v), (L, v)] for p, v in vals.items()}
    for p in ck.FEET_PROPS + ("gl_w", "gl_s", "gr_s"):
        K.setdefault(p, [(0.0, 0.0), (L, 0.0)])
    K.update(over or {})
    ck.key_all(K, L, loop)


# --------------------------------------------------------------------------- solve
def make_solve(L, loop, feet, item=None, cloak_drag=True, extra=None):
    """combatkit.make_solve + two-handed grip onto the item shaft + grip slide (gr_s)."""

    def left_ik(t, world):
        w = max(0.0, min(1.0, c("gl_w", t)))
        if item is None or w <= 1e-3:
            return None, 0.0
        a, b = item_axis(world, item)
        ax = (b - a) / np.linalg.norm(b - a)
        hand = mcrig.xform(world["right_forearm"], (0, 6, 0))
        base = a + ax * float((hand - a) @ ax)
        return base + ax * c("gl_s", t), w

    def slide(t, ch):
        s = c("gr_s", t)
        if item is not None and abs(s) > 1e-4:
            w = mcrig.pose_matrices(ch)
            ax = item_dir(w, item)
            loc = np.linalg.inv(w["right_forearm"][:3, :3]) @ ax * s
            rot = ch.get("right_item", {}).get("rot", (0.0, 0.0, 0.0))
            ch["right_item"] = {"rot": rot, "pos": (float(loc[0]), float(-loc[1]), float(loc[2]))}
        if extra is not None:
            extra(t, ch)

    return ck.make_solve(L, loop, feet=feet, cloak_drag=cloak_drag,
                         left_ik=left_ik if item is not None else None, extra=slide)


# --------------------------------------------------------------------------- run
def run(const, fn, tex, item, args=None):
    """fn() keys the CTRL curves and returns (L, loop, solve, contract, keep_times, extra_checks)."""
    args = args or hsrig.parse_args()
    args["three"] = "--three" in sys.argv
    objs = build(tex, item)
    L, loop, solve, contract, keep, more = fn()
    times, samples = hsrig.bake(solve, L, objs)
    checks = {"clip": const, "length": L, "loop": loop, "contract": contract}
    checks.update(ck.ground_report(samples))
    if item:
        tips = [item_axis(mcrig.pose_matrices(s), item)[1] for s in samples]
        sp = ck.world_speed(tips)
        checks["tip_speed_peak_t"] = round(float(np.argmax(sp)) / FPS, 3)
        checks["tip_speed_peak_px_s"] = round(float(sp.max()), 1)
    if loop:
        a, b = samples[0], samples[-1]
        checks["seam_note"] = "bake is cyclic (CTRL curves CYCLES)"
    if more:
        checks.update(more(times, samples))
    print("CHECKS", json.dumps(checks))
    if args["export"]:
        doc, e = ck.export(const, L, loop, times, samples, keep_times=keep,
                           meta={"source": "tools/blender/pipeline/clips/roles (rolekit, Blender "
                                           + bpy.app.version_string + ")", "contract": contract,
                                 "lane": "anim overkill 2026-09-26"},
                           out_dir=ck.out_dir(const.lower()))
        checks["roundtrip_max_err"] = round(e, 4)
    ck.write_report(const.lower(), checks)
    if args.get("three"):
        preview3(const.lower(), L, loop)
    else:
        ck.preview(const.lower(), L, args, loop=loop, step=2 if (loop and L >= 2.0) else 1)
    return checks


def preview3(clip, L, loop):
    """Side, front three-quarter and TOP-DOWN previews (the blade path must be readable)."""
    d = ck.out_dir(clip)
    ck._setup_workbench((640, 480))
    cams = {"side": hsrig.camera("c_side", (-3.9, -0.35, 1.05), (0.0, -0.35, 0.95), lens=34),
            "front34": hsrig.camera("c_f34", (-2.4, -3.0, 1.6), (0.0, -0.3, 0.95), lens=34),
            "top": hsrig.camera("c_top", (0.0, -0.6, 5.2), (0.0, -0.6, 0.0), lens=30)}
    n = int(round(L * FPS))
    step = 2 if (loop and L >= 2.0) else 1
    frames = list(range(0, n + (0 if loop else 1), step))
    sc = bpy.context.scene
    for name, cam in cams.items():
        fd = os.path.join(d, "_frames_" + name)
        os.makedirs(fd, exist_ok=True)
        for f in os.listdir(fd):
            os.remove(os.path.join(fd, f))
        sc.camera = cam
        for i, f in enumerate(frames):
            sc.frame_set(f)
            sc.render.filepath = os.path.join(fd, f"f{i:04d}.png")
            bpy.ops.render.render(write_still=True)
        pad = 0.0 if loop else 0.3
        ck._encode(fd, len(frames), FPS // step, os.path.join(d, name + ".mp4"), pad)
        ck._encode(fd, len(frames), max(1, FPS // step // 2), os.path.join(d, name + "_half.mp4"), pad)
        k = min(12, len(frames))
        idx = [int(round(i * (len(frames) - 1) / (k - 1))) for i in range(k)]
        hsrig._sheet([os.path.join(fd, f"f{i:04d}.png") for i in idx], os.path.join(d, f"sheet_{name}.png"))
    print("PREVIEW3", d)
