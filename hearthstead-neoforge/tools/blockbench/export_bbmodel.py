#!/usr/bin/env python3
"""Export the settler model + every SettlerAnimations.java clip to a
Blockbench .bbmodel, so the real Blockbench engine (run headless via
bb_render.mjs, or the desktop app) can open, preview and edit them.

Geometry is transcribed from SettlerModel.createBodyLayer() (the mirror
of tools/gen_settler.py's UV table). Coordinate mapping is the EXACT
inverse used by Blockbench's own Java importer
(blockbench/js/formats/java/modded_entity.js):
  bone bb-origin  = [-sum(javaX), 24 - sum(javaY), sum(javaZ)]
  cube bb-from    = [O.x - bx - w, O.y - by - h, O.z + bz]; to = from + [w,h,d]
Animation channels are serialized in the declared legacy 4.10 format:
  rotation -> (x, y, z) degrees; position -> (x, y, z); scale unchanged.
Blockbench 5 upgrades legacy position X and rotation X/Y on import, producing
the required mesh-space (-x, -y, z) rotations and (-x, y, z) positions.
Do not pre-apply those signs: that would convert legacy animation data twice.
Static PartPose rotations below are already mesh-space and are unaffected.
"""
import base64
import hashlib
import importlib.util
import json
import math
import os
import sys

def stable_uuid(*parts):
    """Deterministic UUID derived from the thing being identified.

    Blockbench only needs these to be unique and stable within one file.
    Using uuid4() made every export byte-different, and since
    hearthstead-neoforge/tools is inside the QA fingerprint, merely
    RENDERING a preview invalidated every stored green run and marked QA
    stale -- while the repo's own convention is that generated artifacts
    come from a deterministic pipeline. Derived from a SHA-1 of the
    identifying strings instead, so re-exporting an unchanged model
    produces an unchanged file.
    """
    digest = hashlib.sha1("|".join(str(p) for p in parts).encode("utf-8")).hexdigest()
    return (f"{digest[0:8]}-{digest[8:12]}-{digest[12:16]}"
            f"-{digest[16:20]}-{digest[20:32]}")


HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
NEOFORGE = os.path.dirname(TOOLS)
TEXTURE = os.path.join(NEOFORGE, "src/main/resources/assets/hearthstead/"
                                 "textures/entity/settler/settler_none.png")
# Keep the tracked .bbmodel byte-identical across Windows/Linux checkouts while
# still giving desktop Blockbench a path that resolves from tools/blockbench.
# The embedded source below remains authoritative; this is portable metadata,
# never a machine-specific absolute directory.
TEXTURE_MODEL_PATH = os.path.relpath(TEXTURE, HERE).replace(os.sep, "/")
OUT = os.path.join(HERE, "settler.bbmodel")

spec = importlib.util.spec_from_file_location(
    "anim_check", os.path.join(TOOLS, "anim_check.py"))
anim_check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(anim_check)

# (name, parent, java_offset(x,y,z), [(u,v, bx,by,bz, w,h,d, inflate, mirror)])
BONES = [
    ("root",      None,    (0, 24, 0),      []),
    ("torso",     "root",  (0, -12, 0),     [(64, 0, -5, -12, -2.5, 10, 12, 5, 0.0, False)]),
    ("head",      "torso", (0, -12, 0),     [(0, 0, -4, -8, -4, 8, 8, 8, 0.0, False)]),
    ("hood",      "head",  (0, 0, 0),       [(32, 0, -4, -8, -4, 8, 8, 8, 0.6, False)]),
    # Exact runtime Guard-only brow and two side returns, with no inflation.
    ("guard_rim", "head", (0, 0, 0), [
        (64, 44, -5.5, -8, -5.5, 11, 1, 2, 0.0, False),
        (90, 44, -5.5, -8, -3.5, 1, 1, 8, 0.0, False),
        (90, 44, 4.5, -8, -3.5, 1, 1, 8, 0.0, False),
    ]),
    ("hat_brim",  "head",  (0, 0, 0),       [(64, 44, -6, -5, -6, 12, 1, 12, 0.0, False)]),
    ("right_arm", "torso", (-6, -10, 0),    [(0, 32, -2, -2, -2, 4, 12, 4, 0.0, False)]),
    ("left_arm",  "torso", (6, -10, 0),     [(16, 32, -2, -2, -2, 4, 12, 4, 0.0, True)]),
    # Farmer WORK_WATER vessel, including the rotated spout child. Visibility
    # remains a runtime fact in SettlerModel and is reconstructed by the
    # headless renderer only for FARM_WATER+farmer evidence.
    ("watering_can", "left_arm", (0, 12, -3), [
        (0, 0, -3.5, -1, -3, 7, 5, 6, 0.0, False),
        (0, 0, -4, -2, -3.5, 8, 1, 7, 0.0, False),
        (0, 0, -3.5, -6, -0.75, 1.5, 5, 1.5, 0.0, False),
        (0, 0, 2, -6, -0.75, 1.5, 5, 1.5, 0.0, False),
        (0, 0, -2, -6, -0.75, 4, 1.5, 1.5, 0.0, False),
    ]),
    ("spout", "watering_can", (-3, 0, 0), [
        (0, 0, -0.75, -0.75, -9, 1.5, 1.5, 9, 0.0, False),
        (0, 0, -1.5, -1.5, -11, 3, 3, 2, 0.0, False),
    ]),
    ("cloak",     "torso", (0, -12, 0),     [(64, 32, -5.5, 0, -3, 11, 4, 6, 0.2, False)]),
    ("backpack",  "torso", (0, 0, 0),       [(96, 0, -3, -9, 2.5, 6, 7, 3, 0.0, False)]),
    # Archer-only open leather case; exact runtime cuboids and existing UV island.
    # No static arrows: the persisted ammunition count is not client-synced.
    ("archer_quiver", "torso", (0, 0, 0), [
        (96, 0, 0.5, -8, 3.5, 3, 6, 3, 0.0, False),
        (99, 3, 0.5, -11, 3.5, 3, 3, 0.5, 0.0, False),
        (99, 3, 0.5, -11, 6, 3, 3, 0.5, 0.0, False),
        (99, 3, 0.5, -11, 4, 0.5, 3, 2, 0.0, False),
        (99, 3, 3, -11, 4, 0.5, 3, 2, 0.0, False),
        # Exact leather mounts: torso rear z=2.5 to quiver front z=3.5.
        (99, 5, 1, -7, 2.5, 2, 1, 1, 0.0, False),
        (99, 5, 1, -4, 2.5, 2, 1, 1, 0.0, False),
    ]),
    ("belt",      "torso", (0, 0, 0),       [(96, 20, -5, -5, -2.5, 10, 2, 5, 0.3, False)]),
    # A2b carried sack: always visible on field workers, with size driven by
    # real fill, and always exported so the bridge shows the runtime rig.
    ("sack",      "torso", (0, -10.5, 2.5), [
        (28, 17, -2.5, 0, 1, 5, 3, 4, 0.0, False),
        (0, 17, -3.5, 2, 0, 7, 6, 6, 0.0, False),
    ]),
    # Original Hearthstead timber carrying frame. Logs are separate children
    # so runtime/preview can communicate real fill without scaling the frame.
    ("lumber_frame", "torso", (0, -10.5, 4.0), [
        (0, 49, -4.5, 0, 0, 1, 10, 1, 0.0, False),
        (0, 49, 3.5, 0, 0, 1, 10, 1, 0.0, False),
        (7, 49, -4, 1, 0, 8, 1, 1, 0.0, False),
        (7, 49, -4, 8, 0, 8, 1, 1, 0.0, False),
        (7, 49, -4.5, 9, 3, 0.5, 1, 1, 0.0, False),
        (7, 49, 4, 9, 3, 0.5, 1, 1, 0.0, False),
        (26, 49, -4, 9, 0, 8, 1, 4, 0.0, False),
        (0, 49, -0.5, 2, 6, 1, 7, 1, 0.0, False),
    ]),
    ("log_retainer_top", "lumber_frame", (0, 1, 4), [
        (7, 49, -3, 0, -0.5, 6, 1, 1, 0.0, False),
    ]),
    ("log_retainer_base", "lumber_frame", (0, 9, 6), [
        (7, 49, -2, 0, -0.5, 4, 1, 1, 0.0, False),
    ]),
    ("log_left", "lumber_frame", (0, 7.9, 3.9), [
        (50, 49, -1, -4, -1, 2, 8, 2, 0.1, False),
    ]),
    ("log_center", "lumber_frame", (0, 5.7, 3.9), [
        (50, 49, -1, -4, -1, 2, 8, 2, 0.1, False),
    ]),
    ("log_right", "lumber_frame", (0, 3.5, 3.9), [
        (50, 49, -1, -4, -1, 2, 8, 2, 0.1, False),
    ]),
    ("right_leg", "root",  (-2.6, -12, 0),  [(32, 32, -2, 0, -2, 4, 12, 4, 0.0, False)]),
    ("left_leg",  "root",  (2.6, -12, 0),   [(48, 32, -2, 0, -2, 4, 12, 4, 0.0, True)]),
    # Root-owned duplicate for a portable SACK placed in the world. Runtime
    # keeps this hidden until the server publishes a WorkContainer anchor.
    ("ground_sack", "root", (0, 0, 0),       [
        (28, 17, -2.5, 0, 1, 5, 3, 4, 0.0, False),
        (0, 17, -3.5, 2, 0, 7, 6, 6, 0.0, False),
    ]),
    ("ground_lumber_frame", "root", (0, 0, 0), [
        (0, 49, -4.5, 0, 0, 1, 10, 1, 0.0, False),
        (0, 49, 3.5, 0, 0, 1, 10, 1, 0.0, False),
        (7, 49, -4, 1, 0, 8, 1, 1, 0.0, False),
        (7, 49, -4, 8, 0, 8, 1, 1, 0.0, False),
        (7, 49, -4.5, 9, 3, 0.5, 1, 1, 0.0, False),
        (7, 49, 4, 9, 3, 0.5, 1, 1, 0.0, False),
        (26, 49, -4, 9, 0, 8, 1, 4, 0.0, False),
        (0, 49, -0.5, 2, 6, 1, 7, 1, 0.0, False),
    ]),
    ("ground_log_retainer_top", "ground_lumber_frame", (0, 1, 4), [
        (7, 49, -3, 0, -0.5, 6, 1, 1, 0.0, False),
    ]),
    ("ground_log_retainer_base", "ground_lumber_frame", (0, 9, 6), [
        (7, 49, -2, 0, -0.5, 4, 1, 1, 0.0, False),
    ]),
    ("ground_log_left", "ground_lumber_frame", (0, 7.9, 3.9), [
        (50, 49, -1, -4, -1, 2, 8, 2, 0.1, False),
    ]),
    ("ground_log_center", "ground_lumber_frame", (0, 5.7, 3.9), [
        (50, 49, -1, -4, -1, 2, 8, 2, 0.1, False),
    ]),
    ("ground_log_right", "ground_lumber_frame", (0, 3.5, 3.9), [
        (50, 49, -1, -4, -1, 2, 8, 2, 0.1, False),
    ]),
]

# Static PartPose rotations, converted with the same Java -> Blockbench sign
# mapping as imported mesh-space animation channels, not legacy serialization.
# All omitted groups use PartPose offset only.
BASE_ROTATIONS_DEG = {
    "spout": (-math.degrees(0.75), -math.degrees(0.42), 0.0),
    # SettlerModel.addLumberLogs: Java (-90, +/-45, 0), same 2x8x2 UV.
    # Keep both copies equivalent; preview must expose the actual pale ends.
    "log_left": (90.0, -45.0, 0.0),
    "log_center": (90.0, 45.0, 0.0),
    "log_right": (90.0, -45.0, 0.0),
    "ground_log_left": (90.0, -45.0, 0.0),
    "ground_log_center": (90.0, 45.0, 0.0),
    "ground_log_right": (90.0, -45.0, 0.0),
    "log_retainer_top": (0.0, -90.0, 0.0),
    "log_retainer_base": (0.0, -90.0, 0.0),
    "ground_log_retainer_top": (0.0, -90.0, 0.0),
    "ground_log_retainer_base": (0.0, -90.0, 0.0),
}


def build():
    abs_java = {}
    for name, parent, off, _ in BONES:
        px, py, pz = abs_java.get(parent, (0, 0, 0))
        abs_java[name] = (off[0] + px, off[1] + py, off[2] + pz)

    origins = {n: [-x, 24 - y, z] for n, (x, y, z) in abs_java.items()}

    elements = []
    group_uuid = {n: stable_uuid("group", n) for n, *_ in BONES}
    cubes_of = {n: [] for n, *_ in BONES}
    for name, _parent, _off, cubes in BONES:
        ox, oy, oz = origins[name]
        for (u, v, bx, by, bz, w, h, d, inflate, mirror) in cubes:
            cu = stable_uuid("cube", name, u, v, bx, by, bz, w, h, d)
            frm = [ox - bx - w, oy - by - h, oz + bz]
            elements.append({
                "name": name, "box_uv": True, "rescale": False,
                "locked": False, "render_order": "default", "allow_mirror_modeling": True,
                "from": frm, "to": [frm[0] + w, frm[1] + h, frm[2] + d],
                "autouv": 0, "color": 0, "inflate": inflate,
                "origin": origins[name], "uv_offset": [u, v],
                "mirror_uv": mirror, "type": "cube", "uuid": cu,
            })
            cubes_of[name].append(cu)

    def outline(name):
        node = {
            "name": name, "origin": origins[name], "color": 0,
            "uuid": group_uuid[name], "export": True, "mirror_uv": False,
            "isOpen": True, "locked": False, "visibility": True,
            "autouv": 0, "selected": False,
            "children": list(cubes_of[name]),
        }
        if name in BASE_ROTATIONS_DEG:
            node["rotation"] = list(BASE_ROTATIONS_DEG[name])
        for child, parent, _o, _c in BONES:
            if parent == name:
                node["children"].append(outline(child))
        return node

    with open(TEXTURE, "rb") as f:
        tex_b64 = base64.b64encode(f.read()).decode("ascii")

    # anim_check now supports multiple animation source files (settler,
    # raider, ...) via ANIMATION_SOURCES instead of one hardcoded SRC path
    # (df30f38). This exporter only ever built the settler model/rig, so it
    # deliberately keeps reading just the settler entry.
    settler_source = next(
        s for s in anim_check.ANIMATION_SOURCES if s["label"] == "settler")
    defs = anim_check.parse_definitions(settler_source["path"])
    animations = []
    for clip, d in sorted(defs.items()):
        animators = {}
        for bone, target, frames in d["channels"]:
            if bone not in group_uuid:
                continue
            gu = group_uuid[bone]
            animators.setdefault(gu, {"name": bone, "type": "bone", "keyframes": []})
            channel = {"ROTATION": "rotation", "POSITION": "position",
                       "SCALE": "scale"}[target]
            for (t, _kind, (x, y, z), interp) in frames:
                if channel == "rotation":
                    dp = {"x": x, "y": y, "z": z}
                elif channel == "position":
                    dp = {"x": x, "y": y, "z": z}
                else:
                    dp = {"x": x, "y": y, "z": z}
                animators[gu]["keyframes"].append({
                    "channel": channel,
                    "data_points": [dp],
                    "uuid": stable_uuid("kf", clip, bone, channel, t),
                    "time": round(t, 4),
                    "color": -1,
                    "interpolation": "catmullrom" if interp == "CATMULLROM" else "linear",
                })
        animations.append({
            "uuid": stable_uuid("anim", clip),
            "name": f"animation.settler.{clip.lower()}",
            "loop": "loop" if d["looping"] else "once",
            "override": False,
            "length": d["length"],
            "snapping": 20,
            "selected": False,
            "animators": animators,
        })

    model = {
        "meta": {"format_version": "4.10", "model_format": "modded_entity",
                 "box_uv": True},
        "name": "settler",
        "model_identifier": "settler",
        "modded_entity_version": "1.17",
        "modded_entity_flip_y": True,
        "visible_box": [1, 1, 0],
        "variable_placeholders": "",
        "variable_placeholder_buttons": [],
        "timeline_setups": [],
        "resolution": {"width": 128, "height": 64},
        "elements": elements,
        "outliner": [outline("root")],
        "textures": [{
            "path": TEXTURE_MODEL_PATH, "name": "settler_none.png", "folder": "settler",
            "namespace": "hearthstead", "id": "0", "width": 128, "height": 64,
            "uv_width": 128, "uv_height": 64, "particle": False,
            "use_as_default": False, "layers_enabled": False,
            "sync_to_project": "", "render_mode": "default",
            "render_sides": "auto", "frame_time": 1, "frame_order_type": "loop",
            "frame_order": "", "frame_interpolate": False, "visible": True,
            "internal": True, "saved": True, "uuid": stable_uuid("tex", "settler_none"),
            "source": "data:image/png;base64," + tex_b64,
        }],
        "animations": animations,
    }
    return model


def main():
    args = sys.argv[1:]
    check = args == ["--check"]
    output = OUT
    if len(args) == 2 and args[0] == "--output":
        output = os.path.abspath(args[1])
    elif args not in ([], ["--check"]):
        print("usage: export_bbmodel.py [--check | --output PATH]", file=sys.stderr)
        return 2
    model = build()
    payload = json.dumps(model)
    n_anim = len(model["animations"])
    n_el = len(model["elements"])
    if check:
        try:
            with open(OUT, encoding="utf-8") as current_file:
                current = current_file.read()
        except OSError as exc:
            print(f"bbmodel check FAIL: {exc}", file=sys.stderr)
            return 1
        if current != payload:
            print(f"bbmodel check FAIL: regenerate {OUT}", file=sys.stderr)
            return 1
        print(f"bbmodel check PASS: {n_el} cubes, {n_anim} animations")
        return 0
    os.makedirs(os.path.dirname(output), exist_ok=True)
    with open(output, "w", encoding="utf-8") as out_file:
        out_file.write(payload)
    print(f"wrote {output}: {n_el} cubes, {n_anim} animations")
    return 0


if __name__ == "__main__":
    sys.exit(main())
