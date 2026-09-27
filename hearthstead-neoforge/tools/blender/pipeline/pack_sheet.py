"""Carry-pack contact sheets and reel (Blender, Workbench). Carry pack lane, 26 Sep.

    blender -b --factory-startup --python pack_sheet.py -- --out DIR [--geometry fixed|legacy]
        [--profs FARMER,MINER] [--reel]

Sheets: one PNG per profession (rows = key poses, columns = side / back / three-quarter
back), the pack drawn from the same numbers as CarryPackLayer / SettlerModel
(pack_audit.py holds them for both geometries). Reel mode renders short
walk / work / sit loops per job from the back three-quarter camera into PNG
sequences that tools stitch with ffmpeg.
"""
from __future__ import annotations

import json
import math
import os
import sys

import bpy
import numpy as np
from mathutils import Matrix

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import hsrig  # noqa: E402
import mcrig  # noqa: E402
import pack_audit as pa  # noqa: E402

REPO = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
TEX_DIR = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "textures", "entity", "settler")

MATERIAL_RGB = {"WOOL": (0.92, 0.90, 0.86), "LEATHER": (0.92, 0.90, 0.86), "WICKER": (0.80, 0.66, 0.30),
                "STRAW": (0.80, 0.66, 0.30), "PLANKS": (0.45, 0.33, 0.21), "BARREL": (0.52, 0.38, 0.22),
                "LOG": (0.74, 0.60, 0.40)}
ITEM_RGB = {"wheat": (0.86, 0.74, 0.30), "carrot": (0.95, 0.55, 0.12), "potato": (0.80, 0.66, 0.36),
            "cod": (0.72, 0.62, 0.48), "salmon": (0.78, 0.36, 0.30), "cobblestone": (0.50, 0.50, 0.50),
            "raw_iron": (0.75, 0.62, 0.52), "coal": (0.15, 0.15, 0.15), "rabbit_hide": (0.72, 0.58, 0.44),
            "feather": (0.95, 0.95, 0.95), "leather": (0.60, 0.36, 0.20), "white_wool": (0.95, 0.95, 0.95),
            "milk_bucket": (0.85, 0.85, 0.88), "oak_planks": (0.70, 0.56, 0.34),
            "stone_bricks": (0.55, 0.55, 0.55), "sugar": (0.98, 0.98, 0.98), "bread": (0.72, 0.50, 0.24),
            "iron_ingot": (0.85, 0.85, 0.85), "string": (0.95, 0.95, 0.95), "red_wool": (0.70, 0.18, 0.16),
            "honey_bottle": (0.95, 0.70, 0.20), "glass_bottle": (0.75, 0.88, 0.95), "beef": (0.75, 0.28, 0.24),
            "porkchop": (0.90, 0.55, 0.55), "stick": (0.55, 0.40, 0.22), "stone": (0.60, 0.60, 0.60),
            "book": (0.55, 0.30, 0.18), "paper": (0.95, 0.95, 0.92), "writable_book": (0.50, 0.30, 0.20),
            "emerald": (0.20, 0.80, 0.40), "fern": (0.30, 0.55, 0.22), "sweet_berries": (0.75, 0.15, 0.20),
            "flint": (0.30, 0.30, 0.32), "amethyst_shard": (0.62, 0.42, 0.85), "apple": (0.85, 0.15, 0.15)}

# Profession -> (kind, shape, material, tint, contents, texture, work clip)
# Mirrors CarryPackRules.kind()/styleFor() (fixed). Legacy differences are applied in legacy_rule().
P = {
    "NONE": ("JOB", "SACK", "WOOL", 0xB8A488, ["bread", "apple", "stick"], "none", "village_chat"),
    "FARMER": ("JOB", "BASKET", "WICKER", 0xFFFFFF, ["wheat", "carrot", "potato"], "farmer", "farm_harvest"),
    "LUMBERER": ("AUTHORED", "LUMBER", "LOG", 0xFFFFFF, [], "lumberer", "chop"),
    "GUARD": ("KIT", "NONE", "WOOL", 0xFFFFFF, [], "guard", "guard_stance"),
    "COURIER": ("AUTHORED", "COURIER", "WOOL", 0xC8B89A, [], "courier", "courier_sort"),
    "BAKER": ("JOB", "SACK", "WOOL", 0xF4F0E6, ["wheat", "sugar", "bread"], "baker", "knead"),
    "COOK": ("JOB", "BASKET", "WICKER", 0xE0C8A0, ["beef", "carrot", "porkchop"], "cook", "cook_stir"),
    "BUTCHER": ("JOB", "BASKET", "WICKER", 0xE0C8A0, ["beef", "carrot", "porkchop"], "butcher", "cleave"),
    "SMELTER": ("JOB", "CRATE", "PLANKS", 0x9A8A7A, ["iron_ingot", "coal", "raw_iron"], "smelter", "stoke"),
    "SMITH": ("JOB", "CRATE", "PLANKS", 0x9A8A7A, ["iron_ingot", "coal", "raw_iron"], "smith", "hammer_anvil"),
    "SAWYER": ("JOB", "FRAME", "LOG", 0xFFFFFF, ["oak_planks", "stick", "oak_planks"], "sawyer", "saw"),
    "CARPENTER": ("JOB", "FRAME", "LOG", 0xFFFFFF, ["oak_planks", "stick", "oak_planks"], "carpenter",
                  "carpenter_plane"),
    "MASON": ("JOB", "FRAME", "LOG", 0xFFFFFF, ["stone_bricks", "cobblestone", "stone"], "mason", "mason_chisel"),
    "FLETCHER": ("JOB", "SACK", "LEATHER", 0x8A7050, ["stick", "feather", "flint"], "fletcher", "fletcher_fletch"),
    "WEAVER": ("JOB", "BUNDLE", "WOOL", 0xC8B8D8, ["white_wool", "string", "red_wool"], "weaver", "loom_weave"),
    "TANNER": ("JOB", "BUNDLE", "LEATHER", 0x9A6A42, ["leather", "rabbit_hide", "leather"], "tanner",
               "tanner_scrape"),
    "MINER": ("JOB", "BASKET", "PLANKS", 0xB09070, ["cobblestone", "raw_iron", "coal"], "miner", "mine_pick"),
    "INNKEEPER": ("JOB", "CRATE", "BARREL", 0xFFFFFF, ["honey_bottle", "glass_bottle", "wheat"], "innkeeper",
                  "counter_wipe"),
    "SCHOLAR": ("JOB", "SACK", "LEATHER", 0x6A4A3A, ["book", "paper", "writable_book"], "scholar", "fine_work"),
    "MILLER": ("JOB", "SACK", "WOOL", 0xF4F0E6, ["wheat", "sugar", "bread"], "miller", "mill_grind"),
    "BREWER": ("JOB", "CRATE", "BARREL", 0xFFFFFF, ["honey_bottle", "glass_bottle", "wheat"], "brewer",
               "brew_mash"),
    "ARCHER": ("KIT", "QUIVER", "LEATHER", 0x6A4A32, [], "archer", "archer_draw"),
    "ARMOURER": ("JOB", "CRATE", "PLANKS", 0x9A8A7A, ["iron_ingot", "coal", "raw_iron"], "smith", "armour_planish"),
    "HERDER": ("JOB", "SACK", "WOOL", 0xF2EEE4, ["white_wool", "milk_bucket", "white_wool"], "herder",
               "herder_shear"),
    "FISHER": ("JOB", "BASKET", "WICKER", 0xD8C8A0, ["cod", "salmon", "cod"], "fisher", "fisher_net"),
    "HUNTER": ("JOB", "SACK", "LEATHER", 0x8A6A4A, ["rabbit_hide", "feather", "leather"], "hunter", "hunter_loose"),
    "MAYOR": ("SATCHEL_ONLY", "SATCHEL", "LEATHER", 0x6A4A32, [], "mayor", "village_chat"),
    "TRADER": ("JOB", "SACK", "LEATHER", 0xB08850, ["paper", "book", "emerald"], "none", "courier_sort"),
    "SPEARMAN": ("KIT", "NONE", "WOOL", 0xFFFFFF, [], "guard", "spear_thrust"),
    "LONGSWORDSMAN": ("KIT", "NONE", "WOOL", 0xFFFFFF, [], "guard", "longsword_strike"),
    "HEALER": ("JOB", "SACK", "WOOL", 0xA8B890, ["fern", "sweet_berries", "paper"], "scholar", "healer_bandage"),
    "RUNE_MAGE": ("JOB", "SACK", "WOOL", 0x5A6AA8, ["amethyst_shard", "paper", "amethyst_shard"], "scholar",
                  "rune_cast"),
    "BUILDER": ("JOB", "FRAME", "LOG", 0xFFFFFF, ["oak_planks", "oak_planks", "stone_bricks"], "builder",
                "build_place"),
}
RIG_WHEN_EMPTY = {"FISHER"}
# CarryPackRules.look(... swinging): rigid shapes stow during these clips' states.
SWING_CLIPS = {"mason_chisel"}  # was: chop, limb, mine, hammer, saw, cleave, plane, scrape, till
LEGACY_SWING = {"chop", "limb_branches", "mine_pick", "hammer_anvil", "saw", "mason_chisel", "cleave",
               "carpenter_plane", "tanner_scrape", "farm_till"}
QUIVER = [(-3.5, -8.0, 3.5, -0.5, -2.0, 6.5), (-3.5, -11.0, 3.5, -0.5, -8.0, 6.5)]
LEGACY = ["fixed"]

# Exact CarryPackLayer geometry for the pictures (the audit uses conservative hulls).
def _basket(straps_y0, straps_z0):
    return [((-3.0, 7.4, 0.0, 3.0, 8.0, 6.0), "body"), ((-3.0, 1.0, 0.0, 3.0, 8.0, 0.6), "body"),
            ((-3.0, 1.0, 5.4, 3.0, 8.0, 6.0), "body"), ((-3.0, 1.0, 0.6, -2.4, 8.0, 5.4), "body"),
            ((2.4, 1.0, 0.6, 3.0, 8.0, 5.4), "body"),
            ((-2.2, straps_y0, straps_z0, -1.4, 6.0, 0.1), "strap"), ((1.4, straps_y0, straps_z0, 2.2, 6.0, 0.1), "strap")]


LAYER = {
    "legacy": {
        "SATCHEL": [((-2.5, 2.5, 0.0, 2.5, 7.0, 1.5), "strap"), ((-2.6, 2.3, 1.3, 2.6, 4.6, 1.9), "flap")],
        "SACK": [((-2.5, 0.0, 1.0, 2.5, 3.0, 5.0), "body"), ((-3.5, 2.0, 0.0, 3.5, 8.0, 6.0), "body"),
                 ((-2.6, 1.0, 0.9, 2.6, 1.6, 5.1), "strap")],
        "BASKET": _basket(-1.0, -0.3),
        "BUNDLE": [((-3.5, 5.0 - 3.0 * r, 0.0, 3.5, 7.8 - 3.0 * r, 3.2), "body" if r % 2 == 0 else "dark")
                   for r in range(3)] + [((-2.4, -1.2, -0.1, -1.8, 8.0, 3.4), "strap"),
                                         ((1.8, -1.2, -0.1, 2.4, 8.0, 3.4), "strap")],
        "FRAME": [((-3.5, -1.0, 0.0, -2.5, 9.0, 1.0), "body"), ((2.5, -1.0, 0.0, 3.5, 9.0, 1.0), "body"),
                  ((-3.0, 1.0, 0.0, 3.0, 2.0, 1.0), "body"), ((-3.0, 8.2, 0.0, 3.0, 9.0, 4.6), "body")],
    },
    "fixed": {
        "SATCHEL": [((-2.5, 2.5, 0.0, 2.5, 7.0, 1.5), "strap"), ((-2.6, 2.3, 1.3, 2.6, 4.6, 1.9), "flap")],
        "SACK": [((-2.5, 0.0, 1.0, 2.5, 3.0, 5.0), "body"), ((-3.5, 2.0, 0.0, 3.5, 8.0, 6.0), "body"),
                 ((-2.6, 1.0, 0.9, 2.6, 1.6, 5.1), "strap")],
        "BASKET": _basket(0.0, 0.0),
        "BUNDLE": [((-3.5, 5.4 - 2.5 * r, 0.0, 3.5, 7.8 - 2.5 * r, 3.2), "body" if r % 2 == 0 else "dark")
                   for r in range(3)] + [((-2.4, 0.3, 0.0, -1.8, 7.9, 3.4), "strap"),
                                         ((1.8, 0.3, 0.0, 2.4, 7.9, 3.4), "strap")],
        "FRAME": [((-3.5, 0.6, 0.0, -2.5, 9.0, 1.0), "body"), ((2.5, 0.6, 0.0, 3.5, 9.0, 1.0), "body"),
                  ((-3.0, 1.4, 0.0, 3.0, 2.4, 1.0), "body"), ((-3.0, 8.2, 0.0, 3.0, 9.0, 4.6), "body")],
    },
}
LAYER["legacy"]["CRATE"] = LAYER["legacy"]["BASKET"]
LAYER["fixed"]["CRATE"] = LAYER["fixed"]["BASKET"]
LEGACY_SLOTS = {"SACK": [(-1.2, -0.4, 2.8, 20), (1.3, -0.7, 3.4, -35), (0.0, -1.0, 2.2, 80)],
                "BASKET": [(-1.3, 1.0, 2.4, 20), (1.3, 0.7, 3.6, -35), (0.0, 0.2, 3.0, 80)],
                "FRAME": [(0.0, 6.2, 2.6, 0), (0.0, 3.4, 2.6, 8), (0.0, 0.6, 2.6, -6)]}
LEGACY_SLOTS["CRATE"] = LEGACY_SLOTS["BASKET"]


def legacy_items(shape):
    out = []
    for i, (x, y, z, yaw) in enumerate(LEGACY_SLOTS.get(shape, [])):
        m = mcrig.T(x, y, z) @ mcrig.mat4(mcrig.ry(yaw * mcrig.DEG))
        if shape == "FRAME":
            b = (-4.4, -2.2, -2.8, 4.4, 2.2, 2.8)
        else:
            m = m @ mcrig.mat4(mcrig.rz((25 if i == 0 else -15) * mcrig.DEG))
            h = 16 * 0.34 / 2
            b = (-h, -h, -0.25, h, h, 0.25)
        out.append((m, b))
    return out


def rgb(hexv, base=(1, 1, 1)):
    return (base[0] * ((hexv >> 16) & 255) / 255.0, base[1] * ((hexv >> 8) & 255) / 255.0,
            base[2] * (hexv & 255) / 255.0, 1.0)


def box_mesh(name, boxes, colour, parent):
    verts, faces = [], []
    for x0, y0, z0, x1, y1, z1 in boxes:
        b = len(verts)
        verts += [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
                  (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
        faces += [tuple(b + i for i in f) for f in
                  ((0, 1, 2, 3), (4, 7, 6, 5), (0, 4, 5, 1), (2, 6, 7, 3), (1, 5, 6, 2), (0, 3, 7, 4))]
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    mat = bpy.data.materials.new(name + "_m")
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes["Principled BSDF"]
    bsdf.inputs["Base Color"].default_value = colour
    img = bpy.data.images.new(name + "_px", 1, 1)
    img.pixels = list(colour)
    tex = mat.node_tree.nodes.new("ShaderNodeTexImage")
    tex.image = img
    mat.node_tree.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
    mat.diffuse_color = colour
    me.materials.append(mat)
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    ob.parent = parent
    ob.matrix_parent_inverse = Matrix.Identity(4)
    return ob


def M(np4):
    return Matrix(np.asarray(np4).tolist())


class Rig:
    def __init__(self, prof, offset=(0.0, 0.0)):
        kind, shape, material, tint, contents, tex, work = P[prof]
        tex_path = os.path.join(TEX_DIR, f"settler_{tex}.png")
        if not os.path.exists(tex_path):
            tex_path = os.path.join(TEX_DIR, "settler_none.png")
        before = set(bpy.data.objects)
        self.parts = hsrig.build_scene(tex_path)
        self.space = [o for o in bpy.data.objects if o not in before and o.name.startswith("MC_SPACE")][0]
        self.space.matrix_basis = Matrix.Translation((offset[0], offset[1], 0)) @ hsrig.MC_TO_BLENDER
        for name in ("nose",):
            for o in bpy.data.objects:
                if o not in before and o.name.startswith("mesh:" + name):
                    o.hide_render = True
        self.prof = prof
        self.kind, self.shape, self.material, self.tint, self.contents = kind, shape, material, tint, contents
        torso = self.parts["torso"]
        self.pack = bpy.data.objects.new(f"pack:{prof}", None)
        bpy.context.scene.collection.objects.link(self.pack)
        self.pack.parent = torso
        self.variants = {}
        # Fisher's chair (block model: seat y 6..8, backrest z 12..14), in model px around the hips.
        self.chair = box_mesh(f"chair:{prof}", [(-6.0, 18.0, -6.0, 6.0, 20.0, 6.0), (-6.0, 4.0, 4.0, 6.0, 18.0, 6.0)],
                              (0.50, 0.36, 0.22, 1.0), self.space)
        self.chair.hide_render = True
        base = MATERIAL_RGB[material]
        col = rgb(tint, base)
        strap = (0.42, 0.29, 0.20, 1.0)
        if shape == "LUMBER":
            self.lumber = box_mesh(f"lumber:{prof}", pa.AUTHORED["LUMBER"][1][:3], (0.55, 0.40, 0.24, 1), torso)
            self.logs = box_mesh(f"logs:{prof}", [pa.AUTHORED["LUMBER"][1][3]], (0.45, 0.32, 0.18, 1), torso)
            for o in (self.lumber, self.logs):
                o.matrix_basis = M(mcrig.T(*pa.AUTHORED["LUMBER"][0]))
        elif shape == "QUIVER":
            self.quiver = box_mesh(f"quiver:{prof}", QUIVER, (0.42, 0.29, 0.20, 1), torso)
        for sh in ("SATCHEL", "SACK", "BASKET", "CRATE", "BUNDLE", "FRAME", "COURIER"):
            for geo in ("fixed", "legacy"):
                layer = LAYER[geo]["SACK" if sh == "COURIER" else sh]
                roles = {"body": (0.62, 0.50, 0.33, 1.0) if sh == "COURIER" else col, "strap": strap,
                         "flap": (0.48, 0.35, 0.24, 1.0), "dark": tuple(v * 0.8 for v in col[:3]) + (1.0,)}
                parts = []
                for role in ("body", "strap", "flap", "dark"):
                    boxes = [b for b, r in layer if r == role]
                    if boxes:
                        parts.append(box_mesh(f"{sh}:{geo}:{role}:{prof}", boxes, roles[role], self.pack))
                items = []
                if sh in ("SACK", "BASKET", "CRATE", "FRAME") and contents:
                    objs = pa.content_obbs(np.eye(4), sh) if geo == "fixed" else legacy_items(sh)
                    for i, cid in enumerate(contents):
                        ic = ITEM_RGB.get(cid, (0.6, 0.6, 0.6)) + (1.0,)
                        m, b = objs[i]
                        it = box_mesh(f"item{i}:{sh}:{geo}:{prof}", [b], ic, self.pack)
                        it.matrix_basis = M(m)
                        items.append(it)
                self.variants[(sh, geo)] = (parts, items)
        self.hide_all()

    def hide_all(self):
        for obs, items in self.variants.values():
            for ob in obs:
                ob.hide_render = True
            for it in items:
                it.hide_render = True

    def pose(self, ch, fill, tier, off_back, work_clip, geo):
        """Pose the rig and pick/scale the pack like SettlerModel.applyWorkContainer."""
        self.hide_all()
        shape = self.shape
        show = None
        scale = 1.0
        lean = 0.0
        if self.kind == "AUTHORED":
            if not off_back or geo == "legacy":
                show = shape
            scale = (0.80 + 0.25 * fill) * tier
            lean = (0.08 if shape == "COURIER" else 0.22) * fill
        elif self.kind == "KIT":
            if geo == "legacy" and fill > 0.001:
                show, scale, lean = "SACK", (0.80 + 0.25 * fill) * tier, 0.08 * fill   # plain sack fallback
        elif self.kind == "SATCHEL_ONLY" or fill <= 0.001:
            if self.prof not in RIG_WHEN_EMPTY:
                show = "SATCHEL"
        else:
            rigid = shape != "SACK"
            if not (rigid and work_clip in (SWING_CLIPS if geo == "fixed" else LEGACY_SWING)):
                show = shape
                scale = (0.80 + 0.25 * fill) * tier
                lean = (0.14 if rigid else 0.08) * fill
        if off_back and geo == "fixed":
            show = None
            lean = 0.0 if self.kind != "AUTHORED" else lean
        if work_clip.startswith("seated"):
            # Runtime draws separate seated legs (thigh -90, shin +90) on a chair; mirror that.
            for leg in ("right_leg", "left_leg"):
                ch.setdefault(leg, {})["rot"] = (-90.0, 0.0, 0.0)
            for shin in ("right_shin", "left_shin"):
                ch.setdefault(shin, {})["rot"] = (90.0, 0.0, 0.0)
            ch.setdefault("root", {})["pos"] = (0.0, -6.0, 0.0)
        self.chair.hide_render = not work_clip.startswith("seated")
        if lean:
            tr = ch.setdefault("torso", {}).get("rot", (0, 0, 0))
            ch["torso"]["rot"] = (tr[0] + math.degrees(lean), tr[1], tr[2])
            hd = ch.setdefault("head", {}).get("rot", (0, 0, 0))
            ch["head"]["rot"] = (hd[0] - 0.6 * math.degrees(lean), hd[1], hd[2])
        for name, e in self.parts.items():
            if name not in mcrig.PARTS:
                continue
            pivot = mcrig.PARTS[name][1]
            c = ch.get(name, {})
            pos = c.get("pos", (0, 0, 0))
            rot = c.get("rot", (0, 0, 0))
            e.location = (pivot[0] + pos[0], pivot[1] - pos[1], pivot[2] + pos[2])
            e.rotation_euler = (math.radians(rot[0]), math.radians(rot[1]), math.radians(rot[2]))
        if shape == "LUMBER":
            vis = show == "LUMBER"
            self.lumber.hide_render = not vis
            self.logs.hide_render = not (vis and fill > 0.001)
            return show
        if shape == "QUIVER":
            # Legacy: a loaded archer's plain sack replaced the quiver (SettlerModel quiver rule).
            self.quiver.hide_render = (off_back and geo == "fixed") or show is not None
            if show is None:
                return "QUIVER" if not self.quiver.hide_render else None
        if show is None or show in ("NONE", "QUIVER"):
            return None
        obs, items = self.variants[(show, geo)]
        for ob in obs:
            ob.hide_render = False
        n = 0 if show in ("SATCHEL", "BUNDLE", "COURIER") else (3 if fill >= 0.67 else 2 if fill >= 0.34 else 1)
        for i, it in enumerate(items):
            it.hide_render = i >= n
        half = pa.HALF_WIDTH[show]
        if show == "SATCHEL":
            sx = sy = sz = 1.0
        elif geo == "legacy":
            sx = scale if show == "COURIER" else min(scale, 4.6 / half)
            sy = sz = scale
        else:
            sx, sy, sz = min(scale, 4.2 / half), min(scale, 1.10), scale
        self.pack.matrix_basis = M(mcrig.T(*pa.PACK_PIVOT) @ np.diag([sx, sy, sz, 1.0]))
        return show


def clip_channels(stem, t_frac):
    clips = pa.load_clips({stem})
    if stem not in clips:
        clips = pa.load_clips({"idle"})
        stem = "idle"
    doc, name, anim = clips[stem]
    L = float(anim.get("animation_length", 1.0))
    return pa.channels_at(doc, name, anim, (t_frac % 1.0) * L if L else 0.0)


def setup(res):
    hsrig.setup_render(res=res)
    sc = bpy.context.scene
    sc.render.engine = "BLENDER_WORKBENCH"
    sh = sc.display.shading
    sh.light = "STUDIO"
    sh.color_type = "TEXTURE"
    sh.show_shadows = True
    sh.background_type = "VIEWPORT"
    sh.background_color = (0.55, 0.72, 0.95)
    sc.display.render_aa = "8"
    ground = bpy.data.meshes.new("ground")
    ground.from_pydata([(-30, -30, 0), (30, -30, 0), (30, 30, 0), (-30, 30, 0)], [], [(0, 1, 2, 3)])
    mat = bpy.data.materials.new("grass")
    mat.diffuse_color = (0.36, 0.55, 0.28, 1)
    ground.materials.append(mat)
    g = bpy.data.objects.new("ground", ground)
    sc.collection.objects.link(g)
    return sc


def cams(dx=0.0, dist=3.3):
    return {
        "side": hsrig.camera("cam_side", (dx - dist, 0.0, 1.0), (dx, 0.0, 0.85), lens=50),
        "back": hsrig.camera("cam_back", (dx, dist, 1.1), (dx, 0.0, 0.88), lens=50),
        "back34": hsrig.camera("cam_back34", (dx - dist * 0.72, dist * 0.72, 1.45), (dx, 0.0, 0.85), lens=50),
    }


# Key poses: (label, clip, t fraction, fill, tier, off_back)
def poses(prof):
    work = P[prof][6]
    return [
        ("idle, empty", "idle", 0.3, 0.0, 1.0, False),
        ("walk, loaded", "walk_laden", 0.1, 1.0, 1.0, False),
        ("run, loaded", "run_panic", 0.25, 1.0, 1.0, False),
        ("work: " + work, work, 0.45, 1.0, 1.0, False),
        ("walk, tier 3 full", "walk", 0.05, 1.0, 1.45, False),
        ("seated", "seated_idle", 0.3, 1.0, 1.0, True),
    ]


def render_sheets(out, profs, geo):
    sc = setup((300, 360))
    cam = cams()
    tiles = {}
    for prof in profs:
        rig = Rig(prof)
        for r, (label, clip, tf, fill, tier, off) in enumerate(poses(prof)):
            ch = clip_channels(clip, tf)
            rig.pose(ch, fill, tier, off, clip, geo)
            for view, c in cam.items():
                sc.camera = c
                path = os.path.join(out, "_tiles", geo, prof, f"{r}_{view}.png")
                os.makedirs(os.path.dirname(path), exist_ok=True)
                sc.render.filepath = path
                bpy.ops.render.render(write_still=True)
                tiles.setdefault(prof, []).append((r, view, label, path))
        # drop this rig before the next profession
        for o in list(bpy.data.objects):
            if o.name.startswith(("part:", "mesh:", "item:", "MC_SPACE", "pack:", "lumber:", "logs:",
                                  "quiver:")) or ":" + prof in o.name:
                bpy.data.objects.remove(o, do_unlink=True)
    with open(os.path.join(out, "_tiles", geo, "index.json"), "w") as fh:
        json.dump(tiles, fh)


_CLIPS = {}


def clip_at(stem, seconds):
    """Channels of a clip at real time `seconds` (looped), clips cached."""
    if stem not in _CLIPS:
        got = pa.load_clips({stem})
        _CLIPS[stem] = got.get(stem) or pa.load_clips({"idle"})["idle"]
    doc, name, anim = _CLIPS[stem]
    L = float(anim.get("animation_length", 1.0)) or 1.0
    return pa.channels_at(doc, name, anim, seconds % L)


def clear_scene():
    for o in list(bpy.data.objects):
        if o.name not in ("ground", "sun"):
            bpy.data.objects.remove(o, do_unlink=True)


def render_plan(out, plan, fps=24, res=(1280, 540)):
    """plan: list of {name, rigs: [[prof, geo, x]], cam: [loc, target, lens],
    segments: [[clip|null(work), seconds, fill, tier, off_back]]} -> out/_reel/<name>/r#####.png"""
    sc = setup(res)
    for seq in plan:
        clear_scene()
        rigs = [(Rig(p, offset=(x, 0.0)), g) for p, g, x in seq["rigs"]]
        loc, tgt, lens = seq["cam"]
        sc.camera = hsrig.camera("cam_reel", tuple(loc), tuple(tgt), lens=lens)
        d = os.path.join(out, "_reel", seq["name"])
        os.makedirs(d, exist_ok=True)
        k = 0
        for clip, secs, fill, tier, off in seq["segments"]:
            for f in range(int(round(secs * fps))):
                for rig, geo in rigs:
                    c = clip or P[rig.prof][6]
                    rig.pose(clip_at(c, f / fps), fill, tier, off, c, geo)
                sc.render.filepath = os.path.join(d, f"r{k:05d}.png")
                bpy.ops.render.render(write_still=True)
                k += 1
        print("SEQ", seq["name"], k)


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    out = argv[argv.index("--out") + 1] if "--out" in argv else os.path.join(REPO, "build", "packs")
    geo = argv[argv.index("--geometry") + 1] if "--geometry" in argv else "fixed"
    profs = argv[argv.index("--profs") + 1].split(",") if "--profs" in argv else list(P)
    hsrig.reset()
    if "--plan" in argv:
        render_plan(out, json.load(open(argv[argv.index("--plan") + 1])))
    else:
        render_sheets(out, profs, geo)
    print("PACK_SHEET DONE", out, geo)


main()
