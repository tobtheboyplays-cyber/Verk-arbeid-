"""Generates models/item/fishers_rod.json (Fisher v3, 27 Sep): a long two-piece
spruce rod (~1.8 blocks held) with leather grip, copper guides and a side reel.
Rod axis x=z=8, butt y=-4, tip y=30. Reel hangs on the item -Z side (= below the
rod in the settler's hand), crank handle on -X (toward the body). Textures are our own tiles."""
import json, sys
E = []
def box(frm, to, tex, rot=None):
    faces = {f: {"texture": "#" + tex} for f in ("north", "south", "east", "west", "up", "down")}
    e = {"from": list(frm), "to": list(to), "faces": faces}
    if rot: e["rotation"] = rot
    E.append(e)
def col(y0, y1, w, tex):
    h = w / 2
    box((8 - h, y0, 8 - h), (8 + h, y1, 8 + h), tex)
col(-4.0, -3.0, 2.2, "copper")          # butt cap
col(-3.0, 6.0, 1.9, "grip")             # leather grip (hand at y~4)
col(6.0, 7.2, 1.6, "copper")            # reel seat ring
col(7.2, 12.0, 1.5, "dark")             # dark fore-grip / seat
col(12.0, 17.2, 1.2, "shaft")           # butt section
col(17.0, 17.6, 1.5, "copper")          # ferrule
col(17.6, 24.5, 1.0, "shaft")           # mid section
col(24.5, 30.0, 0.7, "shaft")           # tip section
col(29.6, 30.4, 1.0, "copper")          # tip-top
# reel (below the rod = item -Z): foot, spool body, rims, crank
box((7.6, 8.0, 6.2), (8.4, 11.0, 7.3), "copper")       # reel foot
box((6.6, 8.2, 3.6), (9.4, 10.8, 6.2), "dark")         # spool body
box((6.4, 8.0, 3.4), (6.8, 11.0, 6.4), "copper")       # rim -x
box((9.2, 8.0, 3.4), (9.6, 11.0, 6.4), "copper")       # rim +x
box((7.0, 9.1, 3.2), (9.0, 9.9, 3.6), "line")          # line wound on the spool (visible band)
box((5.1, 9.2, 4.6), (6.4, 9.8, 5.2), "copper")        # crank arm (body side: the left hand cranks)
box((3.9, 8.6, 4.4), (5.1, 10.4, 5.4), "grip")         # crank knob
# guides (rings under the blank) and the line running along them to the tip
for y in (13.5, 19.0, 23.5, 27.2):
    box((7.7, y - 0.25, 6.9), (8.3, y + 0.25, 7.6), "copper")
box((7.93, 10.8, 6.95), (8.07, 29.8, 7.05), "line")
d = {"parent": "minecraft:block/block",
     "textures": {"shaft": "hearthstead:item/material/spruce_shaft", "dark": "hearthstead:item/material/darkoak_shaft",
                  "grip": "hearthstead:item/material/leather_wrap", "copper": "hearthstead:item/material/copper_fitting",
                  "line": "hearthstead:item/material/line_wool", "particle": "hearthstead:item/material/spruce_shaft"},
     "elements": E,
     "display": {
         # Held in the settler/player fist at y~4, rod 60 deg above the forearm, reel underneath.
         "thirdperson_righthand": {"rotation": [-30, 0, 0], "translation": [-1, 0.94, -1.7], "scale": [0.85, 0.85, 0.85]},
         "thirdperson_lefthand": {"rotation": [-30, 0, 0], "translation": [-1, 0.94, -1.7], "scale": [0.85, 0.85, 0.85]},
         "firstperson_righthand": {"rotation": [-20, -90, 25], "translation": [1.13, 1.2, 1.13], "scale": [0.55, 0.55, 0.55]},
         "firstperson_lefthand": {"rotation": [-20, 90, -25], "translation": [1.13, 1.2, 1.13], "scale": [0.55, 0.55, 0.55]},
         "gui": {"rotation": [0, 0, -45], "translation": [-2.1, -2.1, 0], "scale": [0.55, 0.55, 0.55]},
         "ground": {"rotation": [0, 0, 90], "translation": [3, 2, 0], "scale": [0.4, 0.4, 0.4]},
         "fixed": {"rotation": [0, 0, -45], "translation": [-2.1, -2.1, 0], "scale": [0.55, 0.55, 0.55]},
         "head": {"rotation": [0, 0, 0], "translation": [0, 8, 0], "scale": [0.5, 0.5, 0.5]}}}
out = sys.argv[1]
json.dump(d, open(out, "w"), indent=2)
print("elements", len(E))
