#!/usr/bin/env python3
"""Block/item models and blockstate for the settlement Banner (block id
hearthstead:hearth), after COORD/refs/block-banner-stand.png.

The counter is authored facing north (front at z=0). Seen from the front, a
gallows pole stands at the back left (east, high x) on a stepped plinth; its
arm reaches across the block and the banner cloth hangs from it on two iron
straps. Everything above the counter lives in a standalone model that the
block-entity renderer draws one block higher, because block model elements
cannot rise above y=32. Pole-model y = world y - 16."""
import json
import os

ASSETS = os.path.join(os.path.dirname(__file__), "..",
                      "src/main/resources/assets/hearthstead")
SIDES = ("north", "south", "west", "east")
DIRS = SIDES + ("up", "down")


def box(frm, to, tex, *, uv=None, rotation=None, faces=DIRS, face_tex=None,
        face_uv=None, face_rot=None):
    """One element. uv defaults to the element's own footprint per face,
    wrapped into 0..16 so tall pieces still sample a sensible strip."""
    x1, y1, z1 = frm
    x2, y2, z2 = to
    h = min(16.0, y2 - y1)
    auto = {
        "north": [16 - x2, 0, 16 - x1, h],
        "south": [x1, 0, x2, h],
        "west": [z1, 0, z2, h],
        "east": [16 - z2, 0, 16 - z1, h],
        "up": [x1, z1, x2, z2],
        "down": [x1, 16 - z2, x2, 16 - z1],
    }
    out = {"from": list(frm), "to": list(to), "faces": {}}
    if rotation:
        out["rotation"] = rotation
    for face in faces:
        f_uv = (face_uv or {}).get(face) or uv or auto[face]
        f_uv = [max(0.0, min(16.0, float(v))) for v in f_uv]
        entry = {"uv": [round(v, 3) for v in f_uv],
                 "texture": (face_tex or {}).get(face, tex)}
        if face_rot and face in face_rot:
            entry["rotation"] = face_rot[face]
        out["faces"][face] = entry
    return out


def stand_model():
    iron = [0, 3, 4, 7]
    return {
        "parent": "block/block",
        "render_type": "minecraft:cutout",
        "textures": {
            "particle": "hearthstead:block/banner_stand_wood",
            "wood": "hearthstead:block/banner_stand_wood",
            "top": "hearthstead:block/banner_stand_top",
            "pole": "hearthstead:block/banner_pole",
            "end": "hearthstead:block/banner_pole_end",
            "iron": "hearthstead:block/banner_iron",
            "brass": "hearthstead:block/banner_brass",
            "lantern": "hearthstead:block/banner_lantern",
            "runner": "hearthstead:block/banner_runner",
            "book": "hearthstead:block/banner_book",
            "pages": "hearthstead:block/banner_pages",
            "pouch": "hearthstead:block/banner_pouch",
            "coin": "hearthstead:block/banner_coin",
        },
        "elements": [
            # counter: trim, body, top slab
            box([0.5, 0, 0.5], [11.5, 1.5, 10], "#wood", face_uv={"up": [0, 0, 16, 16]}),
            box([1, 1.5, 1], [11, 10, 9.5], "#wood", faces=SIDES,
                face_uv={f: [0, 3, 16, 12] for f in SIDES}),
            box([0.5, 10, 0.5], [11.5, 11.5, 10], "#wood", face_tex={"up": "#top"},
                face_uv={"north": [0, 0, 16, 2], "south": [0, 0, 16, 2],
                         "west": [0, 0, 16, 2], "east": [0, 0, 16, 2]}),
            # iron corner brackets on the front
            box([0.25, 0, 0.25], [2, 2, 2], "#iron", uv=iron),
            box([10, 0, 0.25], [11.75, 2, 2], "#iron", uv=iron),
            box([0.25, 9.75, 0.25], [2, 11.75, 2], "#iron", uv=iron),
            box([10, 9.75, 0.25], [11.75, 11.75, 2], "#iron", uv=iron),
            # runner over the front edge
            box([4.5, 2.5, 0.3], [7.5, 11.6, 0.3], "#runner", faces=("north",),
                face_uv={"north": [0, 0, 16, 16]}),
            box([4.5, 11.55, 0.3], [7.5, 11.55, 4], "#runner", faces=("up",),
                face_uv={"up": [0, 0, 16, 6]}),
            # closed ledger by the pole; coin pouch, coins and lantern toward the open side
            box([6.5, 11.5, 2.5], [10.5, 12.75, 7], "#book",
                face_tex={"north": "#pages", "east": "#pages", "west": "#pages"},
                face_uv={"north": [0, 4, 16, 8], "east": [0, 4, 16, 8], "west": [0, 4, 16, 8],
                         "south": [0, 0, 16, 5], "up": [0, 0, 16, 16], "down": [0, 0, 16, 16]}),
            box([3.75, 11.5, 4.5], [5.5, 13.25, 6.25], "#pouch", uv=[2, 5, 14, 16]),
            box([4.25, 13.25, 5], [5, 13.75, 5.75], "#pouch", uv=[4, 0, 8, 4]),
            box([4.25, 11.5, 2], [5.75, 11.9, 3.5], "#coin", uv=[3, 3, 13, 13]),
            box([3, 11.5, 2.5], [4.25, 11.75, 3.75], "#coin", uv=[3, 3, 13, 13]),
            # brass lantern
            box([1, 11.5, 4], [3.5, 12, 6.5], "#brass", uv=[0, 0, 16, 4]),
            box([1.25, 12, 4.25], [3.25, 15, 6.25], "#lantern", faces=SIDES,
                face_uv={f: [0, 0, 16, 16] for f in SIDES}),
            box([1, 15, 4], [3.5, 15.5, 6.5], "#brass", uv=[0, 0, 16, 4]),
            box([1.75, 15.5, 5.125], [2.75, 16.5, 5.375], "#brass", uv=[0, 0, 4, 4]),
            # stepped plinth for the pole
            box([10.5, 0, 8.5], [16, 3.5, 15], "#wood", face_tex={"up": "#top"},
                face_uv={f: [0, 0, 16, 4] for f in SIDES}),
            box([11, 3.5, 9], [16, 6.5, 14], "#pole", face_tex={"up": "#end"}),
            box([11.25, 5.75, 9.25], [15.75, 7.25, 13.75], "#iron", faces=SIDES, uv=iron),
        ],
    }


def pole_model():
    """Pole, cap, arm, brace and cloth straps. Model y = world y - 16."""
    iron = [0, 3, 4, 7]
    bands = [box([11.25, y1, 9.25], [15.75, y2, 13.75], "#iron", faces=SIDES, uv=iron)
             for y1, y2 in ((10, 11.5), (20, 21.5), (24.25, 25.25))]
    pole = []
    for y1, y2 in ((-9.5, 6.5), (6.5, 22.5), (22.5, 27)):
        pole.append(box([11.5, y1, 9.5], [15.5, y2, 13.5], "#pole", faces=SIDES,
                        face_uv={f: [6, 0, 10, y2 - y1] for f in SIDES}))
    arm_uv = {"north": [0, 6, 14.5, 9], "south": [0, 6, 14.5, 9],
              "up": [0, 6, 14.5, 9], "down": [0, 6, 14.5, 9],
              "west": [0, 0, 3, 3]}
    return {
        "parent": "block/block",
        "ambientocclusion": False,
        "textures": {
            "particle": "hearthstead:block/banner_pole",
            "pole": "hearthstead:block/banner_pole",
            "end": "hearthstead:block/banner_pole_end",
            "iron": "hearthstead:block/banner_iron",
        },
        "elements": pole + bands + [
            box([11.25, 27, 9.25], [15.75, 28, 13.75], "#end",
                face_uv={f: [0, 0, 16, 2] for f in SIDES}),
            # arm across the block (overhanging slightly), end grain at its tip
            box([-2.5, 21, 10], [12, 24, 13], "#pole", face_uv=arm_uv,
                face_tex={"west": "#end"}, faces=("north", "south", "up", "down", "west")),
            # diagonal brace from pole to arm, just behind the cloth
            box([10.25, 14.25, 12], [11.5, 21.75, 13], "#pole",
                rotation={"angle": 22.5, "axis": "z", "origin": [11.5, 14.25, 12.5]},
                faces=SIDES + ("up",), face_uv={f: [6, 0, 7.25, 7.5] for f in SIDES}),
            # iron straps the cloth hangs from
            box([-0.75, 19.75, 9.75], [0.25, 24.25, 13.25], "#iron", uv=[0, 3, 2, 8]),
            box([7.25, 19.75, 9.75], [8.25, 24.25, 13.25], "#iron", uv=[0, 3, 2, 8]),
        ],
    }


def blockstate():
    ys = {"north": 0, "east": 90, "south": 180, "west": 270}
    variants = {}
    for facing, y in ys.items():
        v = {"model": "hearthstead:block/hearth"}
        if y:
            v["y"] = y
        variants[f"facing={facing}"] = v
    return {"variants": variants}


def item_model():
    return {"parent": "minecraft:item/generated",
            "textures": {"layer0": "hearthstead:item/settlement_banner"}}


def write(rel, data):
    path = os.path.join(ASSETS, rel)
    text = json.dumps(data, indent=2) + "\n"
    with open(path, "wb") as fp:
        fp.write(text.replace("\n", "\r\n").encode("utf-8"))
    print(f"  wrote {rel}")


if __name__ == "__main__":
    write("models/block/hearth.json", stand_model())
    write("models/block/settlement_banner_pole.json", pole_model())
    write("blockstates/hearth.json", blockstate())
    write("models/item/hearth.json", item_model())
