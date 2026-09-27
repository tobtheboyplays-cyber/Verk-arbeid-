"""TRADER_DEAL (TRADER lane): the Trader strikes a deal with a visiting merchant across
the Trading Post counter. New clip.

    blender -b --factory-startup --python author_trader_deal.py -- [--fast|--full] [--no-export]

Contract (TraderDealScene): animation.settler.trader_deal, 10.00 s (200 ticks), ONE-SHOT,
played from the tick TraderWorkGoal enters SettlerActivity.TRADING.
  * THE SALE commits on the handover at 6.50 s (tick 130, TraderDealScene.COMMIT_TICK):
    both hands slide the goods across the counter.
  * Ledger tally on the three coins dropped into the purse, 4.60 / 5.05 / 5.50 s
    (ticks 92 / 101 / 110) and on the three pen strokes, 7.80 / 8.40 / 9.00 s
    (ticks 156 / 168 / 180).

Beats: a small bow and a raised open hand (greet); both hands to the goods on the
counter, a palm-up sweep (show); listening with a hand on the hip, a "no" head shake,
a counter-offer finger, then a double nod (haggle); the purse comes off the belt, three
coins picked from the counter into it, a weigh and a jingle (count); both hands push the
goods across (the sale), a nod; the quill from behind the ear, the ledger from the belt,
three strokes and a flourish (write it down); book and quill away, a farewell nod.
Props (hearthstead_props): prop_coin_purse (left) 4.30-6.15 s, a coin in the right hand
during each pick, prop_ledger (left) 7.00-9.72 s, a feather quill (right) 7.25-9.40 s.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import traderkit as tk  # noqa: E402

ck, hsrig = tk.ck, tk.hsrig
LENGTH = 10.0
COMMIT = 6.5
DROPS = (4.60, 5.05, 5.50)
STROKES = (7.80, 8.40, 9.00)
PICKS = (4.42, 4.87, 5.32)
MERCHANT = (0.0, -4.0, -32.0)
PURSE = (3.5, 6.8, -7.1)

ck.VIDEOS = tk.VIDEOS
ck.WORK = tk.WORK


class DealClip(ck.Clip):
    def cameras(self):
        return {
            "side": hsrig.camera("cam_side", (-4.9, -1.25, 1.55), (0.0, -1.05, 0.85), lens=34),
            "front34": hsrig.camera("cam_front34", (-3.3, -3.4, 1.9), (0.0, -0.75, 0.95), lens=34),
        }


skin = "settler_mayor.png"
clip = DealClip("TRADER_DEAL", LENGTH, False, skin, feet=((-2.6, 24.0, 0.0), (2.6, 24.0, 0.0)), knee_out=0.15)
clip.head_env = True
stagebits = tk.stage(goods=True)
# the merchant stand-in (the in-game visitor is a vanilla Wandering Trader)
tk.box("m_legs", (-4, 12, -34), (8, 12, 4), (0.20, 0.22, 0.42, 1))
tk.box("m_robe", (-4.5, 0, -34.5), (9, 13, 5), (0.22, 0.36, 0.62, 1))
tk.box("m_head", (-4, -8, -36), (8, 8, 8), (0.72, 0.56, 0.42, 1))
tk.box("m_hood", (-4.5, -9, -37), (9, 3, 9), (0.18, 0.28, 0.52, 1))
tk.box("m_arms", (-4.5, 3, -31.5), (9, 4, 4), (0.20, 0.33, 0.58, 1))

K = {
    "root_x": [(0.0, 0.0), (1.3, 0.3, "inout"), (2.7, 0.9, "inout"), (3.9, 0.6, "inout"), (4.4, -0.2, "inout"),
               (6.2, 0.0, "inout"), (6.5, -0.1, "inout"), (7.3, 0.4, "inout"), (9.4, 0.3, "inout"),
               (10.0, 0.0, "inout")],
    "root_y": [(0.0, 0.0), (0.6, 0.4, "inout"), (1.0, 0.0, "inout"), (1.7, 0.3, "inout"), (6.2, 0.2, "inout"),
               (6.5, 0.5, "out"), (6.8, 0.1, "inout"), (10.0, 0.0, "inout")],
    "root_z": [(0.0, 0.0), (0.6, -0.4, "inout"), (1.0, 0.0, "inout"), (1.6, -0.6, "inout"), (2.4, -0.3, "inout"),
               (2.8, 0.2, "inout"), (4.4, -0.4, "inout"), (6.32, -0.6, "inout"), (COMMIT, -1.2, "out"),
               (6.8, -0.3, "inout"), (7.4, 0.0, "inout"), (10.0, 0.0, "inout")],
    "root_yaw": [(0.0, 0.0), (2.8, 4.0, "inout"), (3.9, 1.0, "inout"), (4.5, -3.0, "inout"), (6.4, 0.0, "inout"),
                 (7.4, 3.0, "inout"), (9.4, 2.0, "inout"), (10.0, 0.0, "inout")],
    "torso_x": [(0.0, 0.0), (0.25, 3.0, "inout"), (0.6, 14.0, "out"), (0.95, 4.0, "inout"), (1.6, 11.0, "inout"),
                (2.3, 7.0, "inout"), (2.8, -1.0, "inout"), (3.95, 2.0, "inout"), (4.1, 6.0, "inout"),
                (4.3, 3.0, "inout"), (4.5, 9.0, "inout"), (5.6, 8.0, "inout"), (6.0, 4.0, "inout"),
                (6.32, 12.0, "inout"), (COMMIT, 19.0, "out"), (6.75, 8.0, "inout"), (7.3, 5.0, "inout"),
                (7.8, 7.0, "inout"), (9.1, 6.0, "inout"), (9.35, 2.0, "inout"), (9.55, 6.0, "inout"),
                (9.8, 2.0, "inout"), (10.0, 0.0, "inout")],
    "torso_y": [(0.0, 0.0), (0.6, -3.0, "inout"), (1.9, -5.0, "inout"), (2.3, 3.0, "inout"), (3.1, -3.0, "inout"),
                (3.4, 4.0, "inout"), (3.8, 0.0, "inout"), (4.6, 6.0, "inout"), (5.5, 5.0, "inout"),
                (6.2, 0.0, "inout"), (7.2, -5.0, "inout"), (7.6, 4.0, "inout"), (9.2, 4.0, "inout"),
                (9.6, 0.0, "inout"), (10.0, 0.0, "inout")],
    "torso_z": [(0.0, 0.0), (2.9, -2.5, "inout"), (3.8, -1.5, "inout"), (4.4, 0.0, "inout"), (10.0, 0.0)],
    "head_nod": [(0.0, 0.0), (0.35, 4.0, "inout"), (0.6, 14.0, "out"), (0.9, 2.0, "inout"), (3.9, 0.0, "inout"),
                 (4.02, 12.0, "out"), (4.15, 1.0, "inout"), (4.28, 8.0, "out"), (4.42, 0.0, "inout"),
                 (6.45, 0.0, "inout"), (6.58, 9.0, "out"), (6.75, 0.0, "inout"), (9.4, 0.0, "inout"),
                 (9.52, 10.0, "out"), (9.68, 0.0, "inout"), (10.0, 0.0)],
    "head_yaw": [(0.0, 0.0), (2.9, 0.0, "inout"), (3.02, -15.0, "inout"), (3.18, 12.0, "inout"),
                 (3.33, -9.0, "inout"), (3.48, 5.0, "inout"), (3.6, 0.0, "inout"), (10.0, 0.0)],
    "head_roll": [(0.0, 0.0), (2.7, 5.0, "inout"), (2.9, 3.0, "inout"), (3.6, -2.0, "inout"), (3.9, 0.0, "inout"),
                  (8.2, -3.0, "inout"), (9.2, -2.0, "inout"), (9.4, 0.0, "inout"), (10.0, 0.0)],
    "head_env": [(0.0, 0.0), (0.3, 1.0, "inout"), (9.7, 1.0, "inout"), (10.0, 0.0)],
    "cloak_add": [(0.0, 0.0), (0.6, 3.0, "inout"), (1.0, 0.0, "inout"), (COMMIT, 3.0, "out"), (7.0, 0.0, "inout"),
                  (10.0, 0.0)],
}
clip.keys(K)
clip.key_vec("look", [(0.0, MERCHANT), (1.2, MERCHANT, "inout"), (1.45, (0.0, 6.0, -12.0), "inout"),
                      (2.0, (-2.0, 6.0, -12.0), "inout"), (2.3, MERCHANT, "inout"), (4.3, MERCHANT, "inout"),
                      (4.45, (-1.0, 7.0, -9.5), "inout"), (5.6, (0.0, 6.5, -8.0), "inout"),
                      (5.75, MERCHANT, "inout"), (6.2, (0.0, 5.0, -12.0), "inout"), (6.45, MERCHANT, "inout"),
                      (7.3, MERCHANT, "inout"), (7.45, (1.5, 5.0, -9.0), "inout"), (9.2, (1.5, 5.0, -9.0), "inout"),
                      (9.35, MERCHANT, "inout"), (10.0, MERCHANT)])

RIGHT = [
    (0.0, "rest", None), (0.22, "rest", "inout"),
    (0.55, (-7.2, -0.8, -6.8), "out"), (0.78, (-6.6, -0.2, -7.4), "inout"), (0.98, (-7.0, -0.9, -6.9), "inout"),
    (1.25, (-4.8, 5.2, -7.5), "inout"), (1.5, (-3.2, 6.9, -10.6), "out"), (1.85, (-7.2, 5.4, -11.2), "inout"),
    (2.2, (-4.6, 6.6, -10.2), "inout"), (2.55, (-4.0, 9.5, -5.2), "inout"), (2.95, (-4.4, 10.2, -4.4), "inout"),
    (3.4, (-5.4, 1.8, -7.8), "out"), (3.55, (-5.0, 1.2, -8.4), "inout"), (3.68, (-5.8, 1.7, -7.9), "inout"),
    (3.9, (-4.3, 6.0, -6.8), "inout"), (4.3, (-3.6, 7.2, -8.6), "inout"),
    (PICKS[0], (-3.0, 7.5, -10.2), "accel"), (DROPS[0], (1.8, 5.4, -7.6), "out"),
    (PICKS[1], (-2.4, 7.5, -10.6), "accel"), (DROPS[1], (1.9, 5.6, -7.5), "out"),
    (PICKS[2], (-3.4, 7.5, -9.8), "accel"), (DROPS[2], (1.7, 5.3, -7.7), "out"),
    (5.8, (-3.8, 8.8, -6.0), "inout"), (6.15, (-3.6, 8.0, -7.5), "inout"),
    (6.32, (-2.6, 6.6, -10.4), "out"), (COMMIT, (-2.3, 6.7, -14.2), "accel"), (6.62, (-3.0, 5.6, -13.2), "out"),
    (6.9, (-4.2, 8.8, -6.2), "inout"), (7.1, (-5.2, -3.8, -2.0), "inout"), (7.25, (-5.4, -4.2, -1.6), "inout"),
    (7.5, (0.8, 4.3, -9.4), "inout"), (7.62, (1.1, 4.2, -9.3), "inout"),
    (STROKES[0], (-0.8, 4.6, -9.1), "linear"), (8.0, (0.9, 4.8, -9.1), "inout"), (8.2, (0.7, 4.3, -9.3), "inout"),
    (STROKES[1], (-1.0, 4.7, -9.0), "linear"), (8.6, (1.0, 5.0, -9.1), "inout"), (8.8, (0.4, 4.4, -9.3), "inout"),
    (STROKES[2], (-1.2, 4.9, -9.0), "linear"), (9.12, (-0.3, 4.1, -9.5), "out"),
    (9.3, (-5.3, -3.9, -1.8), "inout"), (9.42, (-5.4, -4.2, -1.6), "inout"), (9.72, (-4.6, 9.6, -3.2), "inout"),
    (10.0, "rest", None),
]
LEFT = [
    (0.0, "rest", None), (0.9, "rest", "inout"),
    (1.2, (4.2, 9.6, -4.6), "inout"), (1.55, (2.8, 6.9, -10.4), "out"), (2.0, (3.4, 7.2, -10.0), "inout"),
    (2.5, (4.4, 9.8, -4.8), "inout"), (2.85, (6.0, 11.0, -1.4), "inout"), (3.8, (6.1, 11.1, -1.2), "inout"),
    (4.3, (5.9, 11.6, -0.8), "inout"), (4.5, (3.6, 6.6, -7.0), "out"),
    (DROPS[0], (3.5, 6.9, -7.1), "inout"), (4.75, (3.6, 6.6, -7.0), "inout"),
    (DROPS[1], (3.4, 6.9, -7.2), "inout"), (5.2, (3.5, 6.6, -7.1), "inout"),
    (DROPS[2], (3.4, 7.0, -7.0), "inout"), (5.7, (3.6, 6.5, -6.9), "inout"), (5.85, (3.5, 7.2, -6.8), "inout"),
    (5.95, (3.6, 6.6, -6.9), "inout"), (6.12, (5.9, 11.4, -0.9), "inout"),
    (6.32, (2.6, 6.6, -10.4), "out"), (COMMIT, (2.3, 6.7, -14.2), "accel"), (6.62, (3.0, 5.8, -13.0), "out"),
    (6.9, (4.8, 10.0, -4.0), "inout"), (7.02, (5.9, 11.4, -0.9), "inout"), (7.3, (2.6, 5.2, -8.0), "out"),
    (STROKES[0], (2.5, 5.3, -8.1), "inout"), (STROKES[1], (2.6, 5.2, -8.0), "inout"),
    (STROKES[2], (2.4, 5.3, -8.2), "inout"), (9.35, (2.6, 5.0, -7.8), "inout"), (9.55, (4.2, 8.6, -5.0), "inout"),
    (9.72, (5.9, 11.4, -0.9), "inout"), (10.0, "rest", None),
]
clip.arm_goals("right", RIGHT, (-0.75, 0.25, 0.6))
clip.arm_goals("left", LEFT, (0.75, 0.25, 0.6))
clip.head_w = (0.5, 0.6)

PROPS = [
    {"hand": "offhand", "item": "hearthstead:prop_coin_purse", "from": 4.30, "to": 6.15, "hide_real": True},
    {"hand": "mainhand", "item": "hearthstead:gold_coin", "from": PICKS[0], "to": DROPS[0], "hide_real": True},
    {"hand": "mainhand", "item": "hearthstead:gold_coin", "from": PICKS[1], "to": DROPS[1], "hide_real": True},
    {"hand": "mainhand", "item": "hearthstead:gold_coin", "from": PICKS[2], "to": DROPS[2], "hide_real": True},
    {"hand": "offhand", "item": "hearthstead:prop_ledger", "from": 7.00, "to": 9.72, "hide_real": True},
    {"hand": "mainhand", "item": "minecraft:feather", "from": 7.25, "to": 9.40, "hide_real": True},
]
clip.hand_props = PROPS
by_item = {}
for p in PROPS:
    by_item.setdefault((p["item"], p["hand"]), []).append((p["from"], p["to"]))
for i, ((item, hand), windows) in enumerate(by_item.items()):
    tk.attach_prop(clip.objs, "p%d" % i, item, hand, windows)

# preview stage motion: coins appear once the price is agreed and go one by one into the purse;
# the goods slide across on the handover and leave with the merchant.
tk.key_windows(stagebits["goods"], [(0.0, COMMIT + 0.05)])
tk.key_loc_mc(stagebits["goods"], [(6.32, (0, 0, 0)), (COMMIT, (0, 0, -3.8))])
for coin, pick in zip(stagebits["coins"], PICKS):
    tk.key_windows(coin, [(4.05, pick)])

args = hsrig.parse_args()
if args["fast"]:
    import propkit
    for i in range(len(by_item)):
        propkit.recolour_sprite("mesh:p%d" % i)
clip.run(contacts=[("drop%d" % (i + 1), t, "right", PURSE) for i, t in enumerate(DROPS)],
         keep=(0.55, 1.5, 1.85, 3.02, 3.18, 3.4, 4.02, 4.28, 6.32, COMMIT, 6.62, 7.25) + PICKS + STROKES,
         strike=("right", list(STROKES)),
         meta={"source": "tools/blender/pipeline/clips/trader/author_trader_deal.py",
               "contract": "TRADER_DEAL 10.00 s one-shot (200 ticks) from the TRADING activity start; "
                           "sale commits on the handover at tick 130 (TraderDealScene.COMMIT_TICK); ledger "
                           "tally at ticks 92/101/110 (coins into the purse) and 156/168/180 (pen strokes)",
               "held_item": "props only: purse + coins (count), ledger + feather quill (write)"})

if "sheet" in args["rest"]:
    tk.review_sheet(clip.cameras(), [0.55, 1.6, 1.85, 3.1, 3.5, 4.05, 4.42, 4.6, 5.05, 5.85, 6.32, 6.5,
                                     7.25, 7.5, 7.8, 8.4, 9.0, 9.52],
                    os.path.join(tk.VIDEOS, "trader_deal_review.png"), cols=6)
