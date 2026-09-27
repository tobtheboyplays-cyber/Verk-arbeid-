"""TRADER lane clip kit: the Trader's clips on top of craftkit (never edits it).

Adds what the trader clips need and craftkit does not have:
  * several display props per clip, in either hand, each with its own time window
    and its own item display transform -- drawn in the preview exactly like the
    runtime MotionPropLayer (vanilla ItemInHandLayer + ItemTransform.apply, the
    left hand mirroring the right-hand transform), and exported as
    "hearthstead_props";
  * timeline sounds exported as "hearthstead_sounds" (idle variants);
  * a shop stage for the preview: the counter (cartography table) one block in
    front of the Trader, the goods on it, the visiting merchant one block beyond.

Conventions are craftkit's: Minecraft model px, +Y down, the settler faces -Z,
ground at y = 24. The counter is the block at z -24..-8 (top y = 8); the
merchant stands on the cell centred at z = -32, facing +Z.
"""
from __future__ import annotations

import json
import math
import os
import sys
import zipfile

import bpy
import numpy as np
from mathutils import Matrix

HERE = os.path.dirname(os.path.abspath(__file__))
CRAFT = os.path.join(HERE, "..", "craft")
for p in (CRAFT, os.path.join(HERE, "..", "..")):
    p = os.path.abspath(p)
    if p not in sys.path:
        sys.path.insert(0, p)
import craftkit as ck  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402
import export_mc_clip as ex  # noqa: E402

REPO = ck.REPO
ITEM_TEX = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "textures", "item")
LAYERS = os.path.join(ck.TEX_DIR, "layers")
WORK = os.environ.get("HS_TRADER_WORK", r"C:\Users\tobia\Hearthstead-Claude\trader\blender")
VIDEOS = os.environ.get("HS_TRADER_VIDEOS", r"C:\Users\tobia\Hearthstead-Claude\videos\blender\clips\trader_v2")
REF = os.path.join(WORK, "ref")

# Item display transforms (thirdperson_righthand): ((rx, ry, rz), (tx, ty, tz), scale).
GENERATED = ((0.0, 0.0, 0.0), (0.0, 3.0, 1.0), 0.55)          # vanilla item/generated
# models/item/prop_ledger.json: plain item/generated (the vanilla held-book hold).
LEDGER_DISPLAY = GENERATED   # held like a vanilla book: always reads, never edge-on
# models/item/prop_coin_purse.json: upright in the fist, a touch smaller than a generated item.
PURSE_DISPLAY = ((0.0, 0.0, 0.0), (0.0, 2.5, 1.0), 0.5)
DISPLAYS = {
    "hearthstead:prop_ledger": LEDGER_DISPLAY,
    "hearthstead:prop_coin_purse": PURSE_DISPLAY,
}


def vanilla_png(name):
    """A vanilla texture (e.g. 'item/feather.png'), extracted once from the moddev resources jar."""
    out = os.path.join(REF, name.replace("/", os.sep))
    if os.path.exists(out):
        return out
    jar = None
    for base in (os.path.join(REPO, "build"), r"C:\Users\tobia\Hearthstead-Claude\trader\pbuild",
                 r"C:\Users\tobia\Hearthstead-Claude\Verk-arbeid-\hearthstead-neoforge\build"):
        cand = os.path.join(base, "moddev", "artifacts",
                            "neoforge-21.1.248-client-extra-aka-minecraft-resources.jar")
        if os.path.exists(cand):
            jar = cand
            break
    if jar is None:
        raise FileNotFoundError("minecraft resources jar not found for " + name)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with zipfile.ZipFile(jar) as z:
        with z.open("assets/minecraft/textures/" + name) as src, open(out, "wb") as dst:
            dst.write(src.read())
    return out


def item_png(item):
    ns, path = item.split(":")
    if ns == "hearthstead":
        name = {"gold_coin": "coins"}.get(path, path)
        return os.path.join(ITEM_TEX, name + ".png")
    return vanilla_png("item/" + path + ".png")


def composite_skin(parts, name):
    """Layered settler skin (base, face, outfit, hair) for the preview rig."""
    out = os.path.join(WORK, name)
    if os.path.exists(out):
        return out
    os.makedirs(WORK, exist_ok=True)
    first = bpy.data.images.load(os.path.join(LAYERS, parts[0]))
    w, h = first.size
    acc = np.array(first.pixels[:]).reshape(h, w, 4)
    for p in parts[1:]:
        img = bpy.data.images.load(os.path.join(LAYERS, p))
        px = np.array(img.pixels[:]).reshape(h, w, 4)
        a = px[..., 3:4]
        acc[..., :3] = px[..., :3] * a + acc[..., :3] * (1.0 - a)
        acc[..., 3:4] = np.maximum(acc[..., 3:4], a)
    out_img = bpy.data.images.new(name, w, h, alpha=True)
    out_img.pixels[:] = acc.reshape(-1).tolist()
    out_img.filepath_raw = out
    out_img.file_format = "PNG"
    out_img.save()
    return out


TRADER_SKIN = ["base_skin.png", "face_0.png", "outfit_trader.png", "hair_1_hair_brn.png"]
MERCHANT_SKIN = ["base_skin_tan.png", "face_2.png", "outfit_weaver.png", "hair_3_hair_blk.png"]


def display_rel(display, right):
    """Wrist-bone-relative frame of a held item (item px cube 0..16), like MotionPropLayer:
    translateToHand -> Rx(-90) Ry(180) T(+-1, 2, -10) -> ItemTransform.apply(leftHand) -> centre."""
    (rx, ry, rz), (tx, ty, tz), s = display
    side = 1 if right else -1
    m = mcrig.T(0, -10, 0)                       # wrist pivot (palm) -> forearm @ T(0,-4,0)
    m = m @ mcrig.mat4(mcrig.rx(-math.pi / 2)) @ mcrig.mat4(mcrig.ry(math.pi)) @ mcrig.T(side * 1, 2, -10)
    r = mcrig.rx(math.radians(rx)) @ mcrig.ry(math.radians(side * ry)) @ mcrig.rz(math.radians(side * rz))
    return m @ mcrig.T(side * tx, ty, tz) @ mcrig.mat4(r, s=s) @ mcrig.T(-8, -8, -8)


def attach_prop(objs, key, item, hand, windows, fps=hsrig.FPS, display=None):
    """Sprite of `item` in 'mainhand' (right) / 'offhand' (left), visible only inside windows [(from, to)]."""
    right = hand == "mainhand"
    parent = objs.get("right_item" if right else "left_item")
    e = bpy.data.objects.new("item:" + key, None)
    bpy.context.scene.collection.objects.link(e)
    e.parent = parent
    e.matrix_basis = Matrix(display_rel(display or DISPLAYS.get(item, GENERATED), right).tolist())
    ob = hsrig.sprite_mesh("mesh:" + key, item_png(item), e)
    key_windows(ob, windows, fps)
    return ob


def key_windows(ob, windows, fps=hsrig.FPS):
    """Keys hide_render/hide_viewport so `ob` shows only inside the given second windows."""
    def put(frame, hidden):
        ob.hide_render = hidden
        ob.hide_viewport = hidden
        ob.keyframe_insert("hide_render", frame=frame)
        ob.keyframe_insert("hide_viewport", frame=frame)
    put(0, True)
    for a, b in windows:
        put(int(round(a * fps)), False)
        put(int(round(b * fps)) + 1, True)


def box(name, frm, size, colour, parent="MC_SPACE"):
    return hsrig.prop_box(name, frm, size, colour, parent_name=parent)


def stage(goods=True, walls=False):
    """Counter + merchant stand-in pieces that do not move. Returns dict of animated pieces."""
    wood = (0.36, 0.25, 0.15, 1)
    box("counter_body", (-8, 9, -24), (16, 15, 16), wood)
    box("counter_top", (-8, 8, -24), (16, 1, 16), (0.72, 0.64, 0.48, 1))
    box("counter_map", (-5, 7.9, -20), (10, 0.2, 9), (0.86, 0.80, 0.62, 1))
    box("floor_in", (-8, 23.9, -8), (16, 0.2, 16), (0.52, 0.38, 0.22, 1))
    box("floor_out", (-8, 23.9, -40), (16, 0.2, 16), (0.46, 0.46, 0.46, 1))
    if walls:   # the shop front either side of the hatch (blocks the side camera: reel only from the front)
        for i, x in enumerate((-24, 8)):
            box("wall%d" % i, (x, -8, -24), (16, 32, 16), (0.60, 0.52, 0.40, 1))
        box("lintel", (-8, -24, -24), (16, 16, 16), (0.45, 0.33, 0.20, 1))
    out = {}
    if goods:
        out["goods"] = box("goods", (-3.5, 5.0, -15.5), (7, 3, 5), (0.62, 0.44, 0.24, 1))
        out["coins"] = [box("coin%d" % i, (-4.0 - 0.6 * i, 7.4, -13.0 - 0.8 * i), (1.6, 0.6, 1.6),
                            (0.93, 0.76, 0.25, 1)) for i in range(3)]
    return out


def key_loc_mc(ob, keys, fps=hsrig.FPS):
    """keys: [(t, (dx, dy, dz) px offset in MC space)] -> object location in MC_SPACE (child) units."""
    for t, (dx, dy, dz) in keys:
        ob.location = (dx, dy, dz)
        ob.keyframe_insert("location", frame=int(round(t * fps)))


def add_sounds(path, anim_name, sounds):
    doc = json.load(open(path, encoding="utf-8"))
    doc["animations"][anim_name]["hearthstead_sounds"] = sounds
    ex.write(doc, path)


def review_sheet(cams, times, out_png, res=(360, 270), cols=6, fps=hsrig.FPS):
    """Quick Workbench contact sheet: every camera x every time (seconds), one PNG."""
    import propkit
    for ob in list(bpy.data.objects):
        if ob.name.startswith("mesh:") and ob.data is not None and ob.data.color_attributes.get("Col"):
            propkit.recolour_sprite(ob.name)
    sc = bpy.context.scene
    hsrig.setup_render(res=res)
    sc.render.engine = "BLENDER_WORKBENCH"
    sh = sc.display.shading
    sh.light, sh.color_type, sh.show_shadows = "STUDIO", "TEXTURE", True
    sc.display.render_aa = "FXAA"
    tmp = os.path.join(os.path.dirname(out_png), "_review")
    os.makedirs(tmp, exist_ok=True)
    pngs = []
    for name, cam in cams.items():
        sc.camera = cam
        for t in times:
            f = int(round(t * fps))
            sc.frame_set(f)
            p = os.path.join(tmp, "%s_%04d.png" % (name, f))
            sc.render.filepath = p
            bpy.ops.render.render(write_still=True)
            pngs.append(p)
    hsrig._sheet(pngs, out_png, cols=cols)
    print("REVIEW_SHEET", out_png)
