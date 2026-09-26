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

W = 336              # building dossier: identity rail + focused work surface
PAD = 8
NAV_X = PAD
NAV_W = 88
NAV_TAB_X = NAV_X + 4
NAV_TAB_W = NAV_W - 8
CARD_X = NAV_X + NAV_W + PAD
TEXT_X = CARD_X + 10
SCROLL_W = M["scroll_w"]
CARD_W = W - CARD_X - PAD - SCROLL_W - 2
BTN_W = 56
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
        {"t": "inset", "x": NAV_X, "y": 8, "w": NAV_W, "h": 0},
        {"t": "label", "x": NAV_X + NAV_W // 2, "y": 14,
         "text": "BUILDING", "align": "center", "tone": "text_muted",
         "box": NAV_W - 12, "id": "building_kind"},
        {"t": "medallion", "x": NAV_X + NAV_W // 2, "y": 34,
         "radius": 10, "text": title[0], "tone": "accent"},
        {"t": "title", "x": CARD_X, "y": 10, "text": title,
         "box": CARD_W, "id": "title"},
        {"t": "label", "x": CARD_X, "y": 23,
         "text": "Active workplace dossier", "tone": "good",
         "box": CARD_W, "id": "building_state"},
    ]
    for i, label in enumerate(tabs):
        els.append({"t": "tab", "x": NAV_TAB_X, "y": 48 + i * 24,
                    "w": NAV_TAB_W, "h": M["button_h"], "text": label,
                    "selected": i == 2})
    list_top = 52
    els.append({"t": "divider", "x": CARD_X, "y": 40, "w": CARD_W})

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
        {"t": "divider", "x": CARD_X, "y": foot, "w": CARD_W},
        {"t": "label", "x": CARD_X, "y": foot + 7, "text": suggestion,
         "tone": "accent", "box": CARD_W, "id": "suggestion"},
        {"t": "button", "x": NAV_TAB_X, "y": foot + 20,
         "w": NAV_TAB_W, "h": M["button_h"], "text": "Refresh"},
        {"t": "button", "x": BTN_X, "y": foot + 20, "w": BTN_W,
         "h": M["button_h"], "text": close},
    ]
    height = foot + 20 + M["button_h"] + 10
    els[0]["h"] = height
    els[1]["h"] = height - 16
    return {"name": name, "width": W, "height": height, "elements": els}


EN = hire_screen(
    "plaque_hire", "Bakery",
    ["Requirements", "Staff", "Hire"],
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
    ["Krav", "Bemanning", "Ansett"],
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


def journey_screen():
    w, h, rail_w, pad = 304, 285, 76, 8
    content_x = pad + rail_w + 6
    content_w = w - content_x - pad
    card_x, card_w = content_x + 18, content_w - 18
    elements = [
        {"t": "window", "x": 0, "y": 0, "w": w, "h": h},
        {"t": "inset", "x": pad, "y": 8, "w": rail_w, "h": h - 16},
        {"t": "label", "x": pad + rail_w // 2, "y": 16,
         "text": "JOURNEY", "align": "center", "box": rail_w - 12,
         "tone": "text_strong", "id": "journey_rail_title"},
        {"t": "label", "x": pad + 6, "y": 34, "text": "Foundation",
         "box": rail_w - 12, "tone": "accent", "id": "journey_chapter"},
        {"t": "pips", "x": pad + 18, "y": 54, "n": 1, "of": 3,
         "tone": "accent"},
        {"t": "label", "x": pad + rail_w // 2, "y": 68,
         "text": "MILESTONE", "align": "center", "box": rail_w - 12,
         "tone": "text_muted", "id": "journey_marker"},
        {"t": "divider", "x": pad + 6, "y": 81, "w": rail_w - 12},
        {"t": "card", "x": pad + 4, "y": 92, "w": rail_w - 8, "h": 38},
        {"t": "label", "x": pad + rail_w // 2, "y": 103,
         "text": "2 / 18", "align": "center", "box": rail_w - 12,
         "tone": "text_strong", "id": "journey_progress"},
        {"t": "title", "x": content_x, "y": 12, "text": "Founding Journey",
         "box": content_w, "id": "journey_title"},
        {"t": "label", "x": content_x, "y": 27, "text": "Foundation",
         "box": 110, "tone": "accent", "id": "journey_head_chapter"},
        {"t": "label", "x": content_x + content_w, "y": 27,
         "text": "2 / 18", "align": "right", "box": content_w,
         "tone": "text_muted", "id": "journey_head_progress"},
        {"t": "divider", "x": content_x, "y": 42, "w": content_w},
        {"t": "line", "x": 0, "y": 0, "points": [[content_x + 7, 84],
         [content_x + 7, 187]], "tone": "accent", "width": 2},
    ]
    steps = [
        ("CURRENT", "Appoint a Mayor",
         "Choose a settlement member to authorise research and job emblems."),
        ("NEXT", "Research Lumber Camp",
         "Pay the shown cost in Development to learn the first build plan."),
    ]
    for index, (state, title, description) in enumerate(steps):
        y = 66 + index * 66
        elements += [
            {"t": "pips", "x": content_x + 5, "y": y + 11,
             "n": 1 if index == 0 else 0, "of": 1, "tone": "accent"},
            {"t": "card_hover" if index == 0 else "card", "x": card_x,
             "y": y, "w": card_w, "h": 58},
            {"t": "label", "x": card_x + 7, "y": y + 6, "text": title,
             "box": card_w - 54, "tone": "accent" if index == 0 else "text_muted",
             "id": f"journey_step_title[{index}]"},
            {"t": "label", "x": card_x + card_w - 7, "y": y + 6,
             "text": state, "align": "right", "box": 42,
             "tone": "accent" if index == 0 else "text_muted",
             "id": f"journey_step_state[{index}]"},
            {"t": "paragraph", "x": card_x + 7, "y": y + 21,
             "w": card_w - 14, "h": 28, "box": card_w - 14,
             "text": description, "lines": 3, "tone": "text_muted",
             "id": f"journey_step_desc[{index}]"},
        ]
    elements += [
        {"t": "divider", "x": content_x, "y": 232, "w": content_w},
        {"t": "label", "x": content_x, "y": 242,
         "text": "Complete this objective to continue.", "box": content_w,
         "tone": "text_muted", "id": "journey_footer"},
        {"t": "button", "x": (w - 106) // 2, "y": 257,
         "w": 106, "h": M["button_h"], "text": "Skip journey",
         "state": "danger"},
    ]
    return {"name": "journey_field_journal", "width": w, "height": h,
            "elements": elements}


def request_screen():
    w, h, rail_w, pad = 304, 238, 76, 8
    content_x = pad + rail_w + 6
    content_w = w - content_x - pad
    card_w = content_w - SCROLL_W - 3
    elements = [
        {"t": "window", "x": 0, "y": 0, "w": w, "h": h},
        {"t": "inset", "x": pad, "y": 8, "w": rail_w, "h": h - 16},
        {"t": "label", "x": pad + rail_w // 2, "y": 16, "text": "COURIER",
         "align": "center", "box": rail_w - 12, "tone": "text_strong",
         "id": "request_rail_title"},
        {"t": "label", "x": pad + rail_w // 2, "y": 28, "text": "QUEUE",
         "align": "center", "box": rail_w - 12, "tone": "text_muted",
         "id": "request_rail_subtitle"},
        {"t": "pips", "x": pad + 19, "y": 45, "n": 3, "of": 3,
         "tone": "accent"},
        {"t": "divider", "x": pad + 6, "y": 59, "w": rail_w - 12},
        {"t": "card", "x": pad + 4, "y": 70, "w": rail_w - 8, "h": 38},
        {"t": "label", "x": pad + rail_w // 2, "y": 79, "text": "LIVE",
         "align": "center", "box": rail_w - 12, "tone": "good",
         "id": "request_rail_live"},
        {"t": "pips", "x": pad + 31, "y": 94, "n": 3, "of": 3,
         "tone": "good"},
        {"t": "button", "x": pad + 4, "y": h - 30, "w": rail_w - 8,
         "h": M["button_h"], "text": "Refresh"},
        {"t": "title", "x": content_x, "y": 12, "text": "Request Ledger",
         "box": content_w, "id": "request_title"},
        {"t": "label", "x": content_x, "y": 34,
         "text": "3 requests · 1 assigned · 0 blocked", "box": content_w,
         "tone": "text_muted", "id": "request_meta"},
        {"t": "divider", "x": content_x, "y": 26, "w": content_w},
        {"t": "divider", "x": content_x, "y": 52, "w": content_w},
    ]
    cards = [
        ("Iron Axe · 1", "Warehouse → Lumber Camp", "Courier: Freya", "READY", "good"),
        ("Oak Log · 8", "Lumber Camp → Warehouse", "Courier: Ivar", "CARRYING", "accent"),
        ("Wheat Seed · 4", "Warehouse → Farmhouse", "Courier: unassigned", "NEEDS COURIER", "warn"),
    ]
    for index, (headline, route, courier, state, tone) in enumerate(cards):
        y = 59 + index * 46
        elements += [
            {"t": "card_hover" if index == 1 else "card", "x": content_x,
             "y": y, "w": card_w, "h": 42},
            {"t": "fill", "x": content_x, "y": y, "w": 3, "h": 42,
             "colour": tone},
            {"t": "label", "x": content_x + 7, "y": y + 3,
             "text": headline, "box": card_w - 10, "tone": "text_strong",
             "id": f"request_headline[{index}]"},
            {"t": "label", "x": content_x + 7, "y": y + 13,
             "text": route, "box": card_w - 10, "tone": "text_muted",
             "id": f"request_route[{index}]"},
            {"t": "label", "x": content_x + 7, "y": y + 23,
             "text": courier, "box": card_w - 10, "tone": "text_muted",
             "id": f"request_courier[{index}]"},
            {"t": "label", "x": content_x + 7, "y": y + 33,
             "text": state, "box": card_w - 10, "tone": tone,
             "id": f"request_state[{index}]"},
        ]
    elements += [
        {"t": "inset", "x": content_x + content_w - SCROLL_W, "y": 59,
         "w": SCROLL_W, "h": 134},
        {"t": "divider", "x": content_x, "y": 211, "w": content_w},
        {"t": "label", "x": content_x, "y": 219,
         "text": "Requests are physical item movements.", "box": content_w,
         "tone": "text_muted", "id": "request_footer"},
    ]
    return {"name": "request_courier_queue", "width": w, "height": h,
            "elements": elements}


def settler_inventory_screen():
    """Truthful preview of the real eight-slot settler bag screen.

    The Java menu owns the exact slot positions below.  This preview exists to
    catch clipping or an accidental return to a generic chest-shaped surface
    before a player has to open the game to see it.
    """
    w, h, pad = 344, 218, 8
    rail_x, rail_w = pad, 72
    content_x = rail_x + rail_w + pad
    content_w = w - content_x - pad
    elements = [
        {"t": "window", "x": 0, "y": 0, "w": w, "h": h},
        {"t": "divider", "x": pad, "y": 28, "w": w - 2 * pad},
        {"t": "inset", "x": rail_x, "y": 34, "w": rail_w,
         "h": h - 42},
        {"t": "card", "x": content_x, "y": 34, "w": content_w,
         "h": 48},
        {"t": "card", "x": content_x, "y": 86, "w": content_w,
         "h": 30},
        {"t": "title", "x": content_x, "y": 8, "text": "Freya's Inventory",
         "box": content_w, "id": "field_kit_title"},
        {"t": "label", "x": content_x, "y": 19,
         "text": "FIELD KIT — DIRECT BAG ACCESS", "box": content_w,
         "tone": "text_muted", "id": "field_kit_subtitle"},
        {"t": "label", "x": rail_x + rail_w // 2, "y": 42,
         "text": "FIELD KIT", "align": "center", "box": rail_w - 12,
         "tone": "accent", "id": "rail_title"},
        {"t": "label", "x": rail_x + 7, "y": 63, "text": "BAG SPACE",
         "box": rail_w - 14, "tone": "text_strong", "id": "rail_bag"},
        {"t": "label", "x": rail_x + 7, "y": 75, "text": "5/8",
         "box": rail_w - 14, "tone": "good", "id": "rail_capacity"},
        {"t": "divider", "x": rail_x + 7, "y": 91, "w": rail_w - 14},
        {"t": "label", "x": rail_x + 7, "y": 104, "text": "MOVE ITEMS",
         "box": rail_w - 14, "tone": "text_muted", "id": "rail_action"},
        {"t": "label", "x": rail_x + 7, "y": 116, "text": "Drag items",
         "box": rail_w - 14, "tone": "text", "id": "rail_action_detail"},
        {"t": "label", "x": content_x + 8, "y": 39, "text": "SETTLER BAG",
         "box": 74, "tone": "text_muted", "id": "bag_label"},
        {"t": "label", "x": 184, "y": 39, "text": "LIVE CAPACITY",
         "box": 144, "tone": "text_muted", "id": "live_label"},
        {"t": "label", "x": 184, "y": 54, "text": "5 / 8",
         "box": 144, "tone": "good", "id": "live_value"},
        {"t": "inventory_grid", "x": 98, "y": 44, "columns": 4,
         "rows": 2, "items": [
             {"index": 0, "resource": "textures/block/oak_log.png", "count": 12},
             {"index": 1, "resource": "textures/item/iron_axe.png"},
             {"index": 2, "resource": "textures/item/bread.png", "count": 4},
             {"index": 4, "resource": "textures/item/wheat_seeds.png", "count": 8},
             {"index": 5, "resource": "textures/block/oak_sapling.png", "count": 3}
         ]},
        {"t": "vanilla_icon", "x": content_x + 6, "y": 91,
         "resource": "textures/item/iron_axe.png", "size": 16},
        {"t": "label", "x": content_x + 28, "y": 89,
         "text": "Needs Iron Axe", "box": content_w - 36,
         "tone": "warn", "id": "request_title"},
        {"t": "label", "x": content_x + 28, "y": 101,
         "text": "Bag it or drop it nearby.", "box": content_w - 36,
         "tone": "text_muted", "id": "request_detail"},
        {"t": "label", "x": 96, "y": 121, "text": "Inventory",
         "box": content_w, "tone": "text_muted", "id": "player_inventory"},
        {"t": "inventory_grid", "x": 96, "y": 132, "columns": 9,
         "rows": 3, "items": [
             {"index": 0, "resource": "textures/block/oak_log.png", "count": 24},
             {"index": 1, "resource": "textures/item/iron_ingot.png", "count": 6},
             {"index": 3, "resource": "textures/item/apple.png", "count": 5},
             {"index": 8, "resource": "textures/block/torch.png", "count": 16}
         ]},
        {"t": "inventory_grid", "x": 96, "y": 190, "columns": 9,
         "rows": 1, "items": [
             {"index": 0, "resource": "textures/item/wooden_axe.png"},
             {"index": 1, "resource": "textures/item/bread.png", "count": 8},
             {"index": 8, "resource": "textures/block/torch.png", "count": 32}
         ]},
    ]
    return {"name": "settler_inventory_field_kit", "width": w, "height": h,
            "elements": elements}


def hearth_ledger_screen(view_w, view_h):
    """Exact Hearth slot/layout preflight inside a real logical viewport."""
    w, h = 320, 220
    ox = (view_w - w) // 2
    oy = max(20, (view_h - h) // 2)
    tab_widths = [64, 56, 52, 56, 72]
    tab_labels = ["Settlement", "Mayor", "Journey", "Requests", "Development"]
    tab_x = ox + 2
    elements = []
    for index, (label, width) in enumerate(zip(tab_labels, tab_widths)):
        elements.append({"t": "tab", "x": tab_x, "y": oy - 20,
                         "w": width, "h": 20, "text": label,
                         "selected": index == 0})
        tab_x += width + 4

    # Hearth-only material stack: soot stone, charred oak, iron and brass.
    elements += [
        {"t": "fill", "x": ox, "y": oy, "w": w, "h": h,
         "colour": "#FF3C403E"},
        {"t": "fill", "x": ox + 2, "y": oy + 2, "w": w - 4, "h": h - 4,
         "colour": "#FF292B2A"},
        {"t": "fill", "x": ox + 5, "y": oy + 5, "w": w - 10, "h": h - 10,
         "colour": "#FF171715"},
        {"t": "fill", "x": ox + 6, "y": oy + 24, "w": w - 12, "h": 2,
         "colour": "#FF6C5132"},
        {"t": "fill", "x": ox + 7, "y": oy + 26, "w": w - 14, "h": 1,
         "colour": "#FFC2A25B"},
        {"t": "fill", "x": ox + 101, "y": oy + 34, "w": 118, "h": 84,
         "colour": "#FF6C5132"},
        {"t": "fill", "x": ox + 103, "y": oy + 36, "w": 114, "h": 80,
         "colour": "#FF30261C"},
        {"t": "fill", "x": ox + 8, "y": oy + 38, "w": 92, "h": 76,
         "colour": "#FF292B2A"},
        {"t": "fill", "x": ox + 220, "y": oy + 38, "w": 92, "h": 76,
         "colour": "#FF292B2A"},
        {"t": "fill", "x": ox + 8, "y": oy + 114, "w": 304, "h": 16,
         "colour": "#FF30261C"},
        {"t": "fill", "x": ox + 8, "y": oy + 114, "w": 304, "h": 1,
         "colour": "#FFC2A25B"},
        {"t": "fill", "x": ox + 72, "y": oy + 130, "w": 176, "h": 89,
         "colour": "#FF6C5132"},
        {"t": "fill", "x": ox + 74, "y": oy + 132, "w": 172, "h": 85,
         "colour": "#FF30261C"},
        {"t": "title", "x": ox + 12, "y": oy + 8,
         "text": "Alderwatch", "box": 124, "id": "settlement_name"},
        {"t": "label", "x": ox + 308, "y": oy + 8,
         "text": "HEARTH LEDGER", "align": "right", "box": 124,
         "tone": "accent", "id": "ledger_stamp"},
        {"t": "label", "x": ox + 160, "y": oy + 31,
         "text": "COMMUNAL STORES", "align": "center", "box": 108,
         "tone": "text_strong", "id": "stores"},
        {"t": "label", "x": ox + 12, "y": oy + 44,
         "text": "POPULATION", "box": 82, "tone": "text_muted"},
        {"t": "label", "x": ox + 94, "y": oy + 53,
         "text": "7 / 10", "align": "right", "box": 82,
         "tone": "accent"},
        {"t": "label", "x": ox + 12, "y": oy + 66,
         "text": "EMPLOYED", "box": 82, "tone": "text_muted"},
        {"t": "label", "x": ox + 94, "y": oy + 75,
         "text": "5", "align": "right", "box": 82,
         "tone": "text_strong"},
        {"t": "label", "x": ox + 226, "y": oy + 44,
         "text": "FOOD", "box": 82, "tone": "text_muted"},
        {"t": "label", "x": ox + 308, "y": oy + 53,
         "text": "126", "align": "right", "box": 82, "tone": "good"},
        {"t": "label", "x": ox + 226, "y": oy + 66,
         "text": "RADIUS", "box": 82, "tone": "text_muted"},
        {"t": "label", "x": ox + 308, "y": oy + 75,
         "text": "64", "align": "right", "box": 82, "tone": "accent"},
        {"t": "label", "x": ox + 12, "y": oy + 88,
         "text": "MORALE", "box": 82, "tone": "text_muted"},
        {"t": "bar", "x": ox + 12, "y": oy + 100,
         "w": 82, "h": 6, "value": 0.78, "tone": "good"},
        {"t": "label", "x": ox + 226, "y": oy + 88,
         "text": "SETTLEMENT", "box": 82, "tone": "text_muted"},
        {"t": "label", "x": ox + 308, "y": oy + 99,
         "text": "STABLE", "align": "right", "box": 82,
         "tone": "text_strong"},
        {"t": "inventory_grid", "x": ox + 106, "y": oy + 42,
         "columns": 6, "rows": 4, "items": [
             {"index": 0, "resource": "textures/item/bread.png", "count": 18},
             {"index": 1, "resource": "textures/block/oak_log.png", "count": 42},
             {"index": 7, "resource": "textures/item/wheat_seeds.png", "count": 16},
             {"index": 9, "resource": "textures/item/iron_ingot.png", "count": 8}
         ]},
        {"t": "label", "x": ox + 14, "y": oy + 118,
         "text": "A traveler draws near...", "box": 218,
         "tone": "text_muted", "id": "context_notice"},
        {"t": "button", "x": ox + 247, "y": oy + 117,
         "w": 62, "h": 20, "text": "Review"},
        {"t": "label", "x": ox + 80, "y": oy + 132,
         "text": "Inventory", "box": 162, "tone": "text_strong"},
        {"t": "inventory_grid", "x": ox + 79, "y": oy + 142,
         "columns": 9, "rows": 3, "items": [
             {"index": 0, "resource": "textures/item/iron_axe.png"},
             {"index": 2, "resource": "textures/item/bread.png", "count": 6}
         ]},
        {"t": "inventory_grid", "x": ox + 79, "y": oy + 200,
         "columns": 9, "rows": 1, "items": []},
    ]
    return {"name": f"hearth_ledger_{view_w}x{view_h}",
            "width": view_w, "height": view_h, "elements": elements}


def main():
    os.makedirs(SCREENS, exist_ok=True)
    for spec in (EN, NB, journey_screen(), request_screen(),
                 settler_inventory_screen(),
                 hearth_ledger_screen(320, 240),
                 hearth_ledger_screen(427, 240),
                 hearth_ledger_screen(512, 274)):
        path = os.path.join(SCREENS, spec["name"] + ".json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(spec, fh, indent=2)
            fh.write("\n")
        print(f"mockups: {spec['name']} ({spec['width']}x{spec['height']})")


if __name__ == "__main__":
    main()
