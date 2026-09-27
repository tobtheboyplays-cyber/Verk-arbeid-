"""IDLE_TRADER__v4 (TRADER lane): the Trader's ledger check, authored with the shared idle
generator (idlekit) on the Trader's own posture (idle_specs.TRAD), so it starts and ends in
the same clasped rest pose as idle_trader v1-v3 and the engine can cut between them.

    blender -b --factory-startup --python author_trader_idles.py -- [--fast] [--no-export]

Beats (7.0 s loop): the left hand takes the ledger off the belt (1.0) and opens it in front of
the chest (1.5); the eyes run down the page; the right index finger taps two lines (tally at
2.55 and 3.25 s: "hearthstead:work.ledger_tally", only while the book is open); a small pleased
nod (3.9); a glance up at the square (4.3-4.9); the book closes and goes back on the belt
(5.4-5.8); hands clasp again. Props: hearthstead:prop_ledger (left hand) 1.0-5.75 s.
"""
import importlib
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
IDLES = os.path.abspath(os.path.join(HERE, "..", "idles"))
sys.path.insert(0, HERE)
sys.path.insert(0, IDLES)
import idlekit  # noqa: E402
import idle_specs as isx  # noqa: E402
import traderkit as tk  # noqa: E402
importlib.reload(idlekit)

idlekit.VIDEOS = tk.VIDEOS
idlekit.WORK = tk.WORK
# preview-only: draw the ledger with its own display transform (idlekit's table is (t, r, s))
(rx, ry, rz), (tx, ty, tz), sc = tk.LEDGER_DISPLAY
idlekit.DISPLAY["ledger"] = ((tx, ty, tz), (rx, ry, rz), sc)
idlekit.ITEM_KIND["prop_ledger"] = (os.path.join(tk.ITEM_TEX, "prop_ledger"), "ledger")

L = 7.0
TAPS = (2.55, 3.25)
T = isx.TRAD
CLASP_R = (-2.0, -2.2, -5.4)
CLASP_L = (2.0, -2.6, -5.2)
BELT = (5.4, -0.6, -1.6)
spec = T.clip(
    "idle_trader__v4", L,
    # both hands start and end in the Trader's clasp (idle_trader rest), IK weight 1 throughout
    {"reach": {
        "l": {"space": "torso", "w": [(0, 1.0), (L, 1.0)],
              "p": [(0, CLASP_L), (0.55, CLASP_L), (1.0, BELT), (1.5, (2.2, -6.2, -7.4)), (2.55, (2.3, -6.0, -7.5)),
                    (3.25, (2.2, -6.1, -7.4)), (4.6, (2.3, -6.3, -7.3)), (5.4, (3.8, -4.0, -6.0)), (5.75, BELT),
                    (6.25, CLASP_L), (L, CLASP_L)]},
        "r": {"space": "torso", "w": [(0, 1.0), (L, 1.0)],
              "p": [(0, CLASP_R), (1.4, CLASP_R), (1.8, (-0.6, -5.4, -8.2)), (2.2, (0.4, -6.4, -8.6)),
                    (2.45, (0.9, -5.9, -8.8)), (TAPS[0], (1.0, -5.5, -8.5)), (2.8, (0.6, -5.8, -8.8)),
                    (3.15, (1.2, -5.2, -8.7)), (TAPS[1], (1.3, -4.8, -8.4)), (3.5, (0.2, -5.4, -8.2)),
                    (3.95, CLASP_R), (L, CLASP_R)]}}},
    isx.keys(head_x=[(1.3, 0), (1.7, 11), (3.6, 12), (3.9, 17), (4.05, 10), (4.3, 2), (4.9, 3), (5.3, 9),
                     (5.8, 0)],
             torso_x=[(1.2, 0), (1.7, 3), (3.8, 3), (4.3, 0)],
             head_z=[(2.2, 0), (2.6, 3), (3.4, -2), (3.9, 0)],
             sigh=[(3.8, 0), (4.1, 0.6), (4.9, 0)]),
    weight=[(1.2, -0.5), (3.9, 0.6), (5.6, 0.5)],
    looks=[(1.2, 0.0, 1.0), (1.8, 6.0, 30.0), (2.5, 7.0, 32.0), (3.3, 8.0, 33.0), (3.8, 5.0, 28.0),
           (4.3, -14.0, 3.0), (4.9, -8.0, 2.0), (5.3, 3.0, 22.0), (6.1, 0.0, 1.0)],
    offhand="prop_ledger",
    hs_props=[{"hand": "offhand", "item": "hearthstead:prop_ledger", "from": 1.0, "to": 5.75, "hide_real": True}],
    hs_sounds=[{"t": t, "sound": "hearthstead:work.ledger_tally", "volume": 0.35, "pitch_jitter": 0.06}
               for t in TAPS],
    note="TRADER break: checks the ledger - opens it off the belt, runs a finger down the page, taps "
         "two tallies (ledger tally sound), a pleased nod, a glance at the square, book back on the belt.")

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
args = {"fast": "--fast" in argv, "review": "--review" in argv, "export": "--no-export" not in argv,
        "rest": ["idle_trader__v4"]}
res = idlekit.run({"idle_trader__v4": spec}, args)
print("TRADER_IDLES_DONE", res)
