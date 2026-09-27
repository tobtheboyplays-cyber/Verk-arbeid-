"""TRADER lane reel: the Trader's new clips as the game plays them (sampled from the exported
JSON with the runtime interpolation), at the Trading Post counter with a merchant stand-in.

    blender -b --factory-startup -t 2 --python trader_reel.py -- [--fps 24] [--res 640x360]

Segments (one camera each): the 10 s deal from the side, the same deal from the front
three-quarter, the ledger-check idle (idle_trader__v4) from the front. Output:
$HS_TRADER_VIDEOS/trader_reel_frames/ -> trader_reel.mp4 (captions are added by ffmpeg after).
"""
import json
import math
import os
import sys

import bpy
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import traderkit as tk  # noqa: E402

hsrig, mcrig, ex, ck = tk.hsrig, tk.mcrig, tk.ex, tk.ck
argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
FPS = int(argv[argv.index("--fps") + 1]) if "--fps" in argv else 24
RES = tuple(int(v) for v in (argv[argv.index("--res") + 1] if "--res" in argv else "640x360").split("x"))
ONLY = argv[argv.index("--only") + 1] if "--only" in argv else None


def load(key):
    path = os.path.join(ck.ANIM_DIR, key + ".animation.json")
    doc = json.load(open(path, encoding="utf-8"))
    name = "animation.settler." + key
    anim = doc["animations"][name]
    out = {}
    for bn, kinds in anim["bones"].items():
        out[bn] = {}
        for kind in ("rotation", "position"):
            if kind in kinds:
                ks = ex.load_keys(doc, name, bn, kind)
                out[bn][kind] = ([k[0] for k in ks], [k[1] for k in ks])
    return out, anim


def ev(keys, t):
    ts, vs = keys
    return [ex.evaluate([(ts[i], vs[i][c]) for i in range(len(ts))], t) for c in range(3)]


def pose(clip, t):
    ch = {}
    for bn, kinds in clip.items():
        rot = ev(kinds["rotation"], t) if "rotation" in kinds else [0.0, 0.0, 0.0]
        pos = ev(kinds["position"], t) if "position" in kinds else [0.0, 0.0, 0.0]
        ch[bn] = {"rot": tuple(rot), "pos": tuple(pos)}
    return ch


def apply(objs, ch, frame):
    for name, e in objs.items():
        if name not in mcrig.PARTS:
            continue
        pivot = mcrig.PARTS[name][1]
        c = ch.get(name, {})
        rot, pos = c.get("rot", (0, 0, 0)), c.get("pos", (0, 0, 0))
        e.location = (pivot[0] + pos[0], pivot[1] - pos[1], pivot[2] + pos[2])
        e.rotation_euler = tuple(math.radians(r) for r in rot)
        e.keyframe_insert("location", frame=frame)
        e.keyframe_insert("rotation_euler", frame=frame)


def env(t, a, b, ramp=0.25):
    if t <= a - ramp or t >= b + ramp:
        return 0.0
    if t < a:
        x = (t - (a - ramp)) / ramp
    elif t > b:
        x = 1.0 - (t - b) / ramp
    else:
        return 1.0
    return x * x * (3 - 2 * x)


def merchant_pose(base, t_deal):
    """Stand-in merchant: idle, then pays (right hand to the counter), takes the goods, nods."""
    ch = {k: dict(v) for k, v in base.items()}
    pay = env(t_deal, 3.75, 4.0)
    take = env(t_deal, 6.45, 6.8)
    ra = list(ch.get("right_arm", {"rot": (0, 0, 0)})["rot"])
    la = list(ch.get("left_arm", {"rot": (0, 0, 0)})["rot"])
    ra[0] += -62.0 * max(pay, take)
    la[0] += -58.0 * take
    ch["right_arm"] = {"rot": tuple(ra), "pos": ch.get("right_arm", {}).get("pos", (0, 0, 0))}
    ch["left_arm"] = {"rot": tuple(la), "pos": ch.get("left_arm", {}).get("pos", (0, 0, 0))}
    hd = list(ch.get("head", {"rot": (0, 0, 0)})["rot"])
    hd[0] += 10.0 * env(t_deal, 4.0, 4.1, 0.15) + 12.0 * env(t_deal, 6.6, 6.7, 0.15) - 8.0 * pay
    ch["head"] = {"rot": tuple(hd), "pos": (0, 0, 0)}
    return ch


deal, deal_anim = load("trader_deal")
idle4, idle4_anim = load("idle_trader__v4")
m_idle, _ = load("idle")

hsrig.reset()
tobjs = hsrig.build_scene(os.path.join(ck.TEX_DIR, "settler_mayor.png"), None)
tspace = tobjs["root"].parent
mobjs = hsrig.build_scene(os.path.join(ck.TEX_DIR, "settler_weaver.png"), None)
mspace = mobjs["root"].parent
sc = bpy.context.scene
ents = []
for objs, space, y, yaw in ((tobjs, tspace, 0.0, 0.0), (mobjs, mspace, -2.0, 180.0)):
    ent = bpy.data.objects.new("ENT_" + str(len(ents)), None)
    sc.collection.objects.link(ent)
    ent.location = (0.0, y, 0.0)
    ent.rotation_euler = (0.0, 0.0, math.radians(yaw))
    space.parent = ent
    ents.append(ent)
wmc = bpy.data.objects.new("WORLD_MC", None)
sc.collection.objects.link(wmc)
wmc.matrix_basis = hsrig.MC_TO_BLENDER
hsrig.prop_box("ground", (-96, 24, -96), (192, 1, 192), (0.36, 0.50, 0.26, 1), parent_name="WORLD_MC")
_orig = tk.box


def wbox(name, frm, size, colour, parent="WORLD_MC"):
    return _orig(name, frm, size, colour, parent=parent)


tk.box = wbox
stage = tk.stage(goods=True)
tk.box = _orig

SEGS = [("deal_side", 0.0, 10.0, "side"), ("deal_front", 10.0, 20.0, "front34"), ("ledger_idle", 20.0, 27.0, "idle")]
if ONLY:
    SEGS = [s for s in SEGS if s[0] == ONLY]
TOTAL = SEGS[-1][2] - SEGS[0][1]
T0 = SEGS[0][1]
n = int(round(TOTAL * FPS))

# props from the exported JSON, mapped onto the reel timeline
wins = {}
for s_name, a, b, _ in SEGS:
    anim = idle4_anim if s_name == "ledger_idle" else deal_anim
    for p in anim.get("hearthstead_props", []):
        wins.setdefault((p["item"], p["hand"]), []).append((a - T0 + p["from"], a - T0 + p["to"]))
for i, ((item, hand), w) in enumerate(wins.items()):
    tk.attach_prop(tobjs, "rp%d" % i, item, hand, w, fps=FPS)
deal_segs = [(a - T0, b - T0) for s_name, a, b, _ in SEGS if s_name.startswith("deal")]
tk.key_windows(stage["goods"], [(a, a + 6.55) for a, b in deal_segs], fps=FPS)
for coin, pick in zip(stage["coins"], (4.42, 4.87, 5.32)):
    tk.key_windows(coin, [(a + 4.05, a + pick) for a, b in deal_segs], fps=FPS)
for a, b in deal_segs:
    tk.key_loc_mc(stage["goods"], [(a + 0.0, (0, 0, 0)), (a + 6.32, (0, 0, 0)), (a + 6.5, (0, 0, -3.8)),
                                   (a + 6.6, (0, 0, 0))], fps=FPS)

for f in range(n):
    t = T0 + f / FPS
    seg = next(s for s in SEGS if s[1] <= t < s[2] + 1e-6) if t < SEGS[-1][2] else SEGS[-1]
    local = t - seg[1]
    tp = pose(idle4 if seg[0] == "ledger_idle" else deal, local)
    apply(tobjs, tp, f)
    mp = pose(m_idle, (t * 0.93) % 4.0)
    if seg[0].startswith("deal"):
        mp = merchant_pose(mp, local)
    apply(mobjs, mp, f)
for objs in (tobjs, mobjs):
    for ob in objs.values():
        ad = ob.animation_data
        if ad and ad.action:
            for fc in ad.action.fcurves:
                for kp in fc.keyframe_points:
                    kp.interpolation = "LINEAR"

import propkit  # noqa: E402
for ob in list(bpy.data.objects):
    if ob.name.startswith("mesh:rp"):
        propkit.recolour_sprite(ob.name)
hsrig.setup_render(res=RES)
sc.render.engine = "BLENDER_WORKBENCH"
sh = sc.display.shading
sh.light, sh.color_type, sh.show_shadows = "STUDIO", "TEXTURE", True
sh.shadow_intensity = 0.35
sh.background_type = "VIEWPORT"
sh.background_color = (0.62, 0.72, 0.84)
sc.display.render_aa = "8"
sc.view_settings.exposure = 0.9
cams = {
    "side": hsrig.camera("cam_side", (-5.2, -1.1, 1.6), (0.0, -1.0, 0.85), lens=32),
    "front34": hsrig.camera("cam_f34", (-3.4, -3.6, 1.95), (0.0, -0.7, 0.95), lens=32),
    "idle": hsrig.camera("cam_idle", (-3.4, -2.4, 2.0), (0.0, -0.1, 1.15), lens=34),
}
TIMES = [float(v) for v in argv[argv.index("--times") + 1].split(",")] if "--times" in argv else None
out = os.path.join(tk.VIDEOS, "trader_reel_frames" if TIMES is None else "trader_reel_review")
os.makedirs(out, exist_ok=True)
for fn in os.listdir(out):
    os.remove(os.path.join(out, fn))
sc.frame_start, sc.frame_end = 0, n - 1
for f in (range(n) if TIMES is None else [int(round((x - T0) * FPS)) for x in TIMES]):
    t = T0 + f / FPS
    seg = next((s for s in SEGS if s[1] <= t < s[2]), SEGS[-1])
    sc.camera = cams[seg[3]]
    sc.frame_set(f)
    sc.render.filepath = os.path.join(out, "f%04d.png" % f)
    bpy.ops.render.render(write_still=True)
json.dump({"fps": FPS, "frames": n, "segments": SEGS}, open(os.path.join(out, "reel.json"), "w"))
print("TRADER_REEL_FRAMES", out, n)
