#!/usr/bin/env python3
"""The layout specs the preview renders, written as code so the arithmetic is
checked rather than typed.

Hand-written JSON gets a coordinate wrong the moment a row height changes.
Everything here derives from the shared tokens and from three or four named
constants, so moving the button column is one edit and every box that depends
on it follows.

    python3 tools/ui/mockups.py && python3 tools/ui_preview.py --all
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SCREENS = os.path.join(HERE, "screens")
TOKENS = json.load(open(os.path.join(HERE, "tokens.json")))
M = TOKENS["metric"]

W = 256              # panel width; 176 is vanilla's, we need the room
PAD = 8              # panel edge to card edge
TEXT_X = 18          # card edge + inner pad
SCROLL_W = M["scroll_w"]
CARD_X = PAD
CARD_W = W - 2 * PAD - SCROLL_W - 2
BTN_W = 64
BTN_X = CARD_X + CARD_W - BTN_W - 8
CARD_H = 38
CARD_STEP = CARD_H + 4

# The cost sentence is the point of this screen (PLAN_EMPLOYMENT 3.2), so it
# gets the full card width on its own row, under the button rather than beside
# it. The first draft put it beside the button and the preview reported it
# overflowing by 38px in English and 10px in Norwegian -- which is exactly the
# bug this tool exists to catch before any Java is written.
NAME_BOX = BTN_X - TEXT_X - 40
POST_BOX = BTN_X - TEXT_X - 6
COST_BOX = CARD_W - 20


def hire_screen(name, title, tabs, cards, suggestion, close):
    els = [
        {"t": "window", "x": 0, "y": 0, "w": W, "h": 0},
        {"t": "title", "x": W // 2, "y": 12, "text": title, "align": "center",
         "box": W - 40, "id": "title"},
    ]
    tab_w = (W - 20 - 2 * 4) // 3
    for i, label in enumerate(tabs):
        els.append({"t": "tab", "x": 10 + i * (tab_w + 4), "y": 26,
                    "w": tab_w, "h": 16, "text": label, "selected": i == 1})
    list_top = 52
    els.append({"t": "divider", "x": 10, "y": 46, "w": W - 20})

    for i, c in enumerate(cards):
        cy = list_top + i * CARD_STEP
        els += [
            {"t": "card_hover" if c.get("hover") else "card",
             "x": CARD_X, "y": cy, "w": CARD_W, "h": CARD_H},
            {"t": "label", "x": TEXT_X, "y": cy + 5, "text": c["name"],
             "tone": "text_strong", "box": NAME_BOX, "id": f'name[{i}]'},
            {"t": "pips", "x": BTN_X - 36, "y": cy + 6, "n": c["fit"],
             "of": 5, "tone": "accent"},
            {"t": "button", "x": BTN_X, "y": cy + 4, "w": BTN_W,
             "h": M["button_h"], "text": c["action"],
             "state": c.get("state", "idle")},
            {"t": "label", "x": TEXT_X, "y": cy + 17, "text": c["post"],
             "tone": "text_muted", "box": POST_BOX, "id": f'post[{i}]'},
            {"t": "label", "x": TEXT_X, "y": cy + 28, "text": c["cost"],
             "tone": c.get("tone", "text_muted"), "box": COST_BOX,
             "id": f'cost[{i}]'},
        ]
    list_bottom = list_top + len(cards) * CARD_STEP - 4
    els.append({"t": "inset", "x": W - PAD - SCROLL_W, "y": list_top,
                "w": SCROLL_W, "h": list_bottom - list_top})
    els.append({"t": "fill", "x": W - PAD - SCROLL_W + 1, "y": list_top + 1,
                "w": SCROLL_W - 2, "h": 40, "colour": "accent"})

    foot = list_bottom + 6
    els += [
        {"t": "divider", "x": 10, "y": foot, "w": W - 20},
        {"t": "label", "x": 12, "y": foot + 7, "text": suggestion,
         "tone": "accent", "box": W - 24, "id": "suggestion"},
        {"t": "button", "x": BTN_X, "y": foot + 20, "w": BTN_W,
         "h": M["button_h"], "text": close},
    ]
    height = foot + 20 + M["button_h"] + 10
    els[0]["h"] = height
    return {"name": name, "width": W, "height": height, "elements": els}


EN = hire_screen(
    "plaque_hire", "Bakery",
    ["Requirements", "Work", "People"],
    [
        {"name": "Astrid Vollan", "fit": 4, "post": "Unemployed",
         "cost": "Nobody loses a worker", "action": "Hire", "hover": True},
        {"name": "Bjorn Kvam", "fit": 3, "post": "Farmhouse, 3 days",
         "cost": "The Farmhouse would have no farmer", "tone": "warn",
         "action": "Hire"},
        {"name": "Sigrid Haug", "fit": 5, "post": "Bakery, 1 day",
         "cost": "Works here now", "action": "Dismiss", "state": "danger"},
    ],
    "Suggested: Sigrid already knows the ovens", "Close")

NB = hire_screen(
    "plaque_hire_nb", "Bakeri",
    ["Krav", "Arbeid", "Folk"],
    [
        {"name": "Astrid Vollan", "fit": 4, "post": "Uten arbeid",
         "cost": "Ingen mister en arbeider", "action": "Ansett", "hover": True},
        {"name": "Bjorn Kvam", "fit": 3, "post": "Gardshuset, 3 dager",
         "cost": "Gardshuset ville sta uten bonde", "tone": "warn",
         "action": "Ansett"},
        {"name": "Sigrid Haug", "fit": 5, "post": "Bakeriet, 1 dag",
         "cost": "Arbeider her na", "action": "Si opp", "state": "danger"},
    ],
    "Forslag: Sigrid kjenner allerede ovnene", "Lukk")


def main():
    os.makedirs(SCREENS, exist_ok=True)
    for spec in (EN, NB):
        path = os.path.join(SCREENS, spec["name"] + ".json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(spec, fh, indent=2)
            fh.write("\n")
        print(f"mockups: {spec['name']} ({spec['width']}x{spec['height']})")


if __name__ == "__main__":
    main()
