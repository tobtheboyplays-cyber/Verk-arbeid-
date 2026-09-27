"""Hero Captain clip kit (battle-roles lane, 2026-09-26; plan/CAPTAIN.md).

Builds on rolekit (6-dof arm solve, two-handed grip, blade clearance) WITHOUT modifying it:
  * registers the Captain's held items with rolekit: the vanilla handheld SWORD (one in each hand
    for Dual Swords, a 2D sprite exactly as the game draws it), and the GREAT AXE block model
    (placeholder geometry until the weapons lane's model lands -- same display transform style
    as the longsword so the grips carry over);
  * a scene builder for the three loadouts (dual swords / great axe / bow);
  * blade clearance for BOTH hands (rolekit only checks the right-hand item);
  * run(): bake, checks (+ left-blade depth, tip speed), export, preview (side + front34 + top).

Timing contract: CaptainSpecial windup ticks = the telegraph; the impact lands on its last tick.
Plain swings: contact on tick 9 (CaptainWeaponGoal.SWING_HIT) for the great axe; the guard
moveset's tick 4 for the dual-sword lights.
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROLES = os.path.abspath(os.path.join(HERE, "..", "roles"))
COMBAT = os.path.abspath(os.path.join(HERE, "..", "combat"))
for p in (HERE, ROLES, COMBAT):
    if p not in sys.path:
        sys.path.insert(0, p)
import bpy  # noqa: E402
import numpy as np  # noqa: E402

import combatkit as ck  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402
import rolekit as rk  # noqa: E402

ck.VIDEO_ROOT = os.environ.get("HS_CAPTAIN_VIDEOS", r"C:\Users\tobia\Hearthstead-Claude\videos\blender\clips\captain")
S, ACC, ACC4, DEC, DEC3, LIN = rk.SMO, rk.ACC, rk.ACC4, rk.DEC, rk.DEC3, rk.LIN
FPS = hsrig.FPS

# ---------------------------------------------------------------- items
# Captain's short sword (weapons lane, tools/weapons/weapon_geometry.json): grip (8,8,8), axis +Y,
# blade y 10.24..17.52 (half-width 0.92), guard x 5.28..10.72; one in EACH hand for Dual Swords.
STEEL = (0.78, 0.79, 0.82, 1.0)
DARKIRON = (0.30, 0.30, 0.33, 1.0)
GRIP = (0.36, 0.22, 0.12, 1.0)
SHORT_DISPLAY = ((-10.0, -90.0, 0.0), (0.0, -1.919, 1.544), 1.2)
rk.ITEMS["sword"] = dict(display=SHORT_DISPLAY, a=(8.0, 5.6, 8.0), b=(8.0, 17.52, 8.0),
                         boxes=[((7.08, 10.24, 7.7), (1.84, 7.28, 0.6), STEEL),
                                ((5.28, 9.6, 7.5), (5.44, 0.64, 1.0), DARKIRON),
                                ((7.6, 6.2, 7.6), (0.8, 3.4, 0.8), GRIP),
                                ((7.4, 5.6, 7.4), (1.2, 0.8, 1.2), DARKIRON)])
rk.BLADE["sword"] = ((8.0, 10.24), (8.0, 17.52), ((5.3, 9.9), (10.7, 9.9)))
# Captain's double axe (weapons lane): grip (8,8,8), second grip (8,13.2,8), axis +Y, symmetric head
# y 17.12..25.36, x 2.4..13.6; third-person display solved by the weapons lane for CROSSBOW_HOLD.
AXE_DISPLAY = ((-125.42, -44.34, -105.48), (-3.639, -5.634, 2.456), 1.2)
AXE_WOOD = (0.42, 0.29, 0.17, 1.0)
rk.ITEMS["great_axe"] = dict(display=AXE_DISPLAY, a=(8.0, 4.4, 8.0), b=(8.0, 25.92, 8.0),
                             boxes=[((7.4, 4.4, 7.4), (1.2, 21.5, 1.2), AXE_WOOD),
                                    ((2.4, 17.12, 7.7), (11.2, 8.24, 0.6), STEEL),
                                    ((7.0, 16.4, 7.0), (2.0, 1.2, 2.0), DARKIRON)])
rk.BLADE["great_axe"] = ((8.0, 4.4), (8.0, 25.92), ((2.6, 21.2), (13.4, 21.2)))
AXE_GRIP2_S = (4.8 - 8.0) * 1.2     # off hand at the butt: v2 put it 0.95 px under the fist and the forearms interpenetrated (audit 3-4 px)

# halberd (weapons lane v2): shaft y -0.2..31.8, head y 18.92..31.8 (axe blade -x to 3.52, hook +x to 12.96),
# second grip (8, 5.974, 8) below the right fist; display at scale 1.0.
HAL_DISPLAY = ((-125.42, -44.34, -105.48), (-2.895, -4.874, 2.27), 1.0)
rk.ITEMS["halberd"] = dict(display=HAL_DISPLAY, a=(8.0, -0.2, 8.0), b=(8.0, 31.8, 8.0),
                           boxes=[((7.4, -0.2, 7.4), (1.2, 26.0, 1.2), AXE_WOOD),
                                  ((3.52, 19.5, 7.7), (4.3, 7.0, 0.6), STEEL),
                                  ((8.6, 21.0, 7.75), (4.36, 1.2, 0.5), STEEL),
                                  ((7.5, 25.8, 7.6), (1.0, 6.0, 0.8), STEEL)])
rk.BLADE["halberd"] = ((8.0, 18.9), (8.0, 31.8), ((3.6, 23.0), (12.9, 23.0)))
HAL_GRIP2_S = (3.0 - 8.0) * 1.0      # hands a forearm apart on the pole (audit)
# warhammer (weapons lane v2): shaft y 4.4..24.8, head y 19.28..24.8, x 4.08..12.24; second grip (8, 6.612, 8)
HAM_DISPLAY = ((-125.42, -44.34, -105.48), (-3.143, -5.128, 2.332), 1.2)
rk.ITEMS["warhammer"] = dict(display=HAM_DISPLAY, a=(8.0, 4.4, 8.0), b=(8.0, 24.8, 8.0),
                             boxes=[((7.4, 4.4, 7.4), (1.2, 15.0, 1.2), AXE_WOOD),
                                    ((4.08, 19.28, 6.4), (8.16, 5.52, 3.2), DARKIRON)])
rk.BLADE["warhammer"] = ((8.0, 19.3), (8.0, 24.8), ((4.1, 22.0), (12.2, 22.0)))
HAM_GRIP2_S = (4.8 - 8.0) * 1.2      # off hand at the butt (audit)


def build(loadout, tex="settler_guard.png"):
    """Scene: settler rig + ground + the loadout's held items."""
    if loadout == "dual":
        objs = rk.build(tex, "sword")
        eye = mcrig.T(0, -6, 0) if "left_item" in objs else np.eye(4)
        lpar = objs.get("left_item", objs["left_forearm"])
        e = bpy.data.objects.new("item:sword_l", None)
        bpy.context.scene.collection.objects.link(e)
        e.parent = lpar
        from mathutils import Matrix
        e.matrix_basis = Matrix(ck.display_matrix(eye, SHORT_DISPLAY, False).tolist())
        for i, (frm, size, col) in enumerate(rk.ITEMS["sword"]["boxes"]):
            rk._box_mesh(f"mesh:sword_l{i}", frm, size, col, e)
        return objs
    if loadout == "axe":
        return rk.build(tex, "great_axe")
    if loadout == "halberd":
        return rk.build(tex, "halberd")
    if loadout == "hammer":
        return rk.build(tex, "warhammer")
    if loadout == "bow":
        return ck.build(tex, right="bow")
    raise ValueError(loadout)


def left_blade_depth(world, item="sword", margin=0.8):
    """rolekit.blade_depth for the OFF-hand blade (mirror frame)."""
    return rk.blade_depth(world, item, margin=margin, side="left")


def rest(base, side_goals, seeds, item_right, item_left=None):
    """Solve the stance arms once. side_goals: {'right': (hand, dir), 'left': (hand, dir)|None}."""
    rk.const_keys(base, 1.0, False)
    v = dict(base)
    sols = {}
    for side, goal in side_goals.items():
        if goal is None:
            continue
        item = item_right if side == "right" else item_left
        sol, err = rk.solve_arm6(rk.body_at(0.0), side, goal[0], goal[1], item=item, seed=seeds[side],
                                 w_seed=0.004, wrist=item is not None)
        print("REST", side, round(err, 2), [round(x, 1) for x in sol], flush=True)
        v.update(dict(zip(rk.ARM6[side], sol)))
        sols[side] = sol
    return v, sols


def rel(base, p, pts):
    return [(q[0], base[p] + q[1], *q[2:]) for q in pts]


def close(K, v, t_end, L, ease=S):
    for p, ks in K.items():
        if p in ("fl_z", "fr_z", "fl_x", "fr_x", "fl_y", "fr_y"):
            continue
        ks += [(t_end, v.get(p, 0.0), *ease), (L, v.get(p, 0.0))]


def step(K, foot, lift, land, dz, dx=0.0, back=None, height=1.9):
    """One foot ('l'/'r') lifts BEFORE it travels, lands, (back) returns the same way."""
    z, x, y = f"f{foot}_z", f"f{foot}_x", f"f{foot}_y"
    K[z] = [(0.0, 0.0), (lift + 0.05, 0.0, *S), (land, dz, *DEC)]
    K[x] = [(0.0, 0.0), (lift + 0.05, 0.0, *S), (land, dx, *DEC)]
    K[y] = [(0.0, 0.0), (lift, 0.0, *DEC), (lift + 0.06, height, *S), (land - 0.03, height * 0.6, *ACC),
            (land, 0.0)]
    if back:
        b0, b1, L = back
        K[z] += [(b0 + 0.06, dz, *S), (b1, 0.0, *DEC), (L, 0.0)]
        K[x] += [(b0 + 0.06, dx, *S), (b1, 0.0, *DEC), (L, 0.0)]
        K[y] += [(b0, 0.0, *DEC), (b0 + 0.06, height, *S), (b1 - 0.03, height * 0.6, *ACC), (b1, 0.0), (L, 0.0)]


def run(const, fn, loadout, tex="settler_guard.png", args=None, keyposes=None, left34=False):
    """fn() keys the CTRL curves and returns (L, loop, solve, contract, keep_times, extra_checks)."""
    args = args or hsrig.parse_args()
    objs = build(loadout, tex)
    L, loop, solve, contract, keep, more = fn()
    times, samples = hsrig.bake(solve, L, objs)
    checks = {"clip": const, "length": L, "loop": loop, "contract": contract}
    checks.update(ck.ground_report(samples))
    item = {"dual": "sword", "axe": "great_axe", "halberd": "halberd", "hammer": "warhammer", "bow": None}[loadout]
    if item:
        worst_r, worst_l = 0.0, 0.0
        tips = []
        for s in samples:
            w = mcrig.pose_matrices(s)
            worst_r = max(worst_r, rk.blade_depth(w, item)[1])
            if loadout == "dual":
                worst_l = max(worst_l, left_blade_depth(w)[1])
            tips.append(rk.item_axis(w, item)[1])
        sp = ck.world_speed(tips)
        checks["blade_depth_right_px"] = round(worst_r, 2)
        if loadout == "dual":
            checks["blade_depth_left_px"] = round(worst_l, 2)
        checks["tip_speed_peak_t"] = round(float(np.argmax(sp)) / FPS, 3)
        checks["tip_speed_peak_px_s"] = round(float(sp.max()), 1)
    if more:
        checks.update(more(times, samples))
    print("CHECKS", json.dumps(checks), flush=True)
    if args["export"] and keyposes is None:
        doc, e = ck.export(const, L, loop, times, samples, keep_times=keep,
                           meta={"source": "tools/blender/pipeline/clips/captain (captainkit, Blender "
                                           + bpy.app.version_string + ")", "contract": contract,
                                 "lane": "battle roles / hero Captain 2026-09-26"},
                           out_dir=ck.out_dir(const.lower()))
        checks["roundtrip_max_err"] = round(e, 4)
    ck.write_report(const.lower(), checks)
    if keyposes is not None:
        render_keys(const.lower(), keyposes)
    elif "--three" in sys.argv:
        rk.preview3(const.lower(), L, loop)
        if left34:
            preview_left34(const.lower(), L, loop)
    else:
        ck.preview(const.lower(), L, args, loop=loop, step=2 if (loop and L >= 2.0) else 1)
    return checks


def render_keys(clip, keyposes):
    """Key-pose stills (side + three-quarter) at the authored key times, for the owner's approval sheet.
    Writes <clip>/_keys/k<i>_<view>.png and keys.txt (t, label); the sheet is composed by make_keysheet.sh."""
    d = os.path.join(ck.out_dir(clip), "_keys")
    os.makedirs(d, exist_ok=True)
    for f in os.listdir(d):
        os.remove(os.path.join(d, f))
    ck._setup_workbench((560, 560))
    cams = {"side": hsrig.camera("k_side", (-3.9, -0.35, 1.05), (0.0, -0.35, 0.95), lens=30),
            "f34": hsrig.camera("k_f34", (-2.4, -3.0, 1.6), (0.0, -0.3, 0.95), lens=30)}
    for cam in cams.values():
        cam.data.lens = 44.0          # framed bigger for the approval sheet
    sc = bpy.context.scene
    with open(os.path.join(d, "keys.txt"), "w", encoding="utf-8") as fh:
        for i, (t, label) in enumerate(keyposes):
            fh.write(f"{i}|{t:.2f}|{label}" + chr(10))
            sc.frame_set(int(round(t * FPS)))
            for name, cam in cams.items():
                sc.camera = cam
                sc.render.filepath = os.path.join(d, f"k{i}_{name}.png")
                bpy.ops.render.render(write_still=True)
    print("KEYS", d, flush=True)


def preview_left34(clip, L, loop):
    """An extra LEFT three-quarter preview (the off-hand side), for moves whose left blade matters."""
    d = ck.out_dir(clip)
    ck._setup_workbench((640, 480))
    cam = hsrig.camera("c_f34l", (2.4, -3.0, 1.6), (0.0, -0.3, 0.95), lens=34)
    n = int(round(L * FPS))
    step = 2 if (loop and L >= 2.0) else 1
    frames = list(range(0, n + (0 if loop else 1), step))
    sc = bpy.context.scene
    fd = os.path.join(d, "_frames_front34l")
    os.makedirs(fd, exist_ok=True)
    for f in os.listdir(fd):
        os.remove(os.path.join(fd, f))
    sc.camera = cam
    for i, f in enumerate(frames):
        sc.frame_set(f)
        sc.render.filepath = os.path.join(fd, f"f{i:04d}.png")
        bpy.ops.render.render(write_still=True)
    pad = 0.0 if loop else 0.3
    ck._encode(fd, len(frames), max(1, FPS // step // 2), os.path.join(d, "front34l_half.mp4"), pad)
    print("PREVIEW_L34", d, flush=True)


def main(clips, loadout, tex="settler_guard.png", keyposes=None, left34=()):
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    names = [a for a in argv if not a.startswith("--")] or list(clips)
    keys_only = "--keys" in argv
    for n in names:
        run(n, clips[n], loadout, tex, keyposes=((keyposes or {}).get(n) or [(0.0, "start")]) if keys_only else None,
            left34=n in left34)
