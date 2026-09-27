"""Adds the Building Plan lang keys and handbook entries (building-plan lane, 26 Sep).

Idempotent: only missing keys / pages / families are added, so it can be re-run on
a tree other lanes are editing. Usage: python apply_building_plan_docs.py <assets/hearthstead dir>
"""
import json
import os
import sys

ASSETS = sys.argv[1] if len(sys.argv) > 1 else "src/main/resources/assets/hearthstead"

LANG = {
    "item.hearthstead.building_plan": "Building Blueprint",
    "item.hearthstead.building_plan.named": "%s Blueprint",
    "item.hearthstead.building_plan.tooltip.1": "Building: %s",
    "item.hearthstead.building_plan.tooltip.2": "Right-click to choose a style and place it. Your Builder raises it.",
    "hearthstead.building_plan.main_hand": "Hold the blueprint in your main hand to place it.",
    "hearthstead.building_plan.pick.title": "Choose a style",
    "hearthstead.building_plan.pick.subtitle": "Pick one of the Builder's styles, then place it.",
    "hearthstead.building_plan.pick.hint": "Click a style (or press 1-5) to place its ghost.",
    "hearthstead.building_plan.pick.no_builder": "No Builder hired yet: the order waits for one.",
    "hearthstead.building_plan.pick.size": "%s × %s, %s high · %s blocks",
    "hearthstead.building_plan.pick.more": "…and %s more",
    "hearthstead.building_plan.pick.click": "Click to place this style",
    "hearthstead.building_plan.loading": "Loading the Builder's styles…",
    "hearthstead.building_plan.disabled": "The Builder is switched off on this server.",
    "hearthstead.building_plan.no_styles": "The Builder has no styles for this building yet.",
    "hearthstead.building_plan.cancel": "Cancel",
    "hearthstead.building_plan.panel.title": "Place the blueprint",
    "hearthstead.building_plan.panel.hide": "Hide",
    "hearthstead.building_plan.panel.hidden": "The ghost stays put. Right-click the blueprint to open its panel again.",
    "hearthstead.building_plan.panel.cancelled": "Placement cancelled. The blueprint is still yours.",
    "hearthstead.building_plan.panel.confirm": "Confirm",
    "hearthstead.building_plan.panel.forward": "Move away from you (Up arrow)",
    "hearthstead.building_plan.panel.back": "Move towards you (Down arrow)",
    "hearthstead.building_plan.panel.left": "Move left (Left arrow)",
    "hearthstead.building_plan.panel.right": "Move right (Right arrow)",
    "hearthstead.building_plan.panel.rotate_left": "Turn left (Shift+R)",
    "hearthstead.building_plan.panel.rotate_right": "Turn right (R)",
    "hearthstead.building_plan.panel.up": "Raise",
    "hearthstead.building_plan.panel.down": "Lower",
    "hearthstead.building_plan.panel.mirror": "Mirror",
    "hearthstead.building_plan.panel.mirrored": "mirrored",
    "hearthstead.building_plan.panel.keys": "Arrows move · PgUp/PgDn raise or lower · R turn · M mirror · Enter confirm · Esc cancel",
    # Item how-to (Shift tooltip) and handbook.
    "hearthstead.howto.building_plan.use": "Right-click to choose one of the Builder's styles and place it.",
    "hearthstead.howto.building_plan.s1": "Learn the building in the Tech Tree: its blueprint recipe unlocks.",
    "hearthstead.howto.building_plan.s2": "Craft it: paper, coal or charcoal, and the building's token item.",
    "hearthstead.howto.building_plan.s3": "Right-click, pick a style, then nudge the ghost with the panel.",
    "hearthstead.howto.building_plan.s4": "Confirm: your Builder raises it and the blueprint is used up.",
    "hearthstead.guide.builder.plans.title": "Building Blueprints",
    "hearthstead.guide.builder.plans.b1": "Craft a blueprint for any building you have learned: paper, coal or charcoal, and the building's token item.",
    "hearthstead.guide.builder.plans.b2": "Right-click the blueprint: the Builder's five styles appear side by side with size and materials. Click one.",
    "hearthstead.guide.builder.plans.b3": "The ghost appears where you looked and stays there. Walk around it; right-click the blueprint to reopen the panel.",
    "hearthstead.guide.builder.plans.b4": "Confirm checks the spot and shows the cost. When the Builder accepts the order, one blueprint is used up.",
    "hearthstead.guide.builder.plans.tip": "Panel keys: arrows move, PgUp/PgDn raise or lower, R turns, M mirrors, Enter confirms, Esc cancels.",
    "hearthstead.guide.builder.plans.k1": "Choose a style / reopen panel",
    "hearthstead.guide.builder.plans.t1": "Blueprint recipes (paper + coal or charcoal + token): House: any wooden door. Builder's Hut: crafting table. Lumber Camp: any log. Farmhouse: wheat seeds. Warehouse: chest. Well: bucket. Kitchen: bowl. Bakery: wheat. Dining Hall: bread. Tavern: barrel. Lodging: any bed. Market: apple. Trading Post: emerald.",
    "hearthstead.guide.builder.plans.t2": "Fishery: cod. Pasture: any wooden fence. Butcher: raw beef. Hunter's Lodge: arrow. Tannery: leather. Weaver: string. Carpenter: any wooden slab. Sawmill: stone axe. Mason: cobblestone. Mill: stone. Mine: stone pickaxe. Smelter: furnace.",
    "hearthstead.guide.builder.plans.t3": "Smithy: iron ingot. Armoury: shield. Fletcher: flint. Barracks: red wool. Sword Hall: stone sword. Pike Yard: stick. Infirmary: white wool. Brewery: glass bottle. Library: book. School: ink sac. Architect's Study: compass. Rune Hall: lapis lazuli.",
    "hearthstead.guide.builder.plans.t4": "Walls, gates, towers, upgrades and the site list stay in the Builder's Plan. Creative players keep their blueprint. (The plaque's Build Plan is a different item.)",
}

PAGE = {
    "id": "builder.plans",
    "title": "hearthstead.guide.builder.plans.title",
    "bullets": [
        "hearthstead.guide.builder.plans.b1",
        "hearthstead.guide.builder.plans.b2",
        "hearthstead.guide.builder.plans.b3",
        "hearthstead.guide.builder.plans.b4",
    ],
    "tip": "hearthstead.guide.builder.plans.tip",
    "image_wanted": "Style picker with five house cards, then an anchored brass-outlined ghost beside the placement panel.",
    "keys": [{"key": "key.use", "action": "hearthstead.guide.builder.plans.k1"}],
    "items": ["hearthstead:building_plan"],
    "text": [
        "hearthstead.guide.builder.plans.t1",
        "hearthstead.guide.builder.plans.t2",
        "hearthstead.guide.builder.plans.t3",
        "hearthstead.guide.builder.plans.t4",
    ],
    "recipes": ["hearthstead:building_plan_house", "hearthstead:building_plan_builders_hut"],
}

FAMILY = {
    "page": "builder.plans",
    "use": "hearthstead.howto.building_plan.use",
    "steps": [
        "hearthstead.howto.building_plan.s1",
        "hearthstead.howto.building_plan.s2",
        "hearthstead.howto.building_plan.s3",
        "hearthstead.howto.building_plan.s4",
    ],
}


def lang():
    path = os.path.join(ASSETS, "lang", "en_us.json")
    text = open(path, encoding="utf-8").read()
    existing = json.loads(text)
    changed = 0
    for k, v in LANG.items():
        if k in existing and existing[k] != v:
            line = "  " + json.dumps(k, ensure_ascii=False) + ": " + json.dumps(existing[k], ensure_ascii=False)
            assert text.count(line) == 1, k
            text = text.replace(line, "  " + json.dumps(k, ensure_ascii=False) + ": " + json.dumps(v, ensure_ascii=False))
            changed += 1
    if changed:
        json.loads(text)
        open(path, "w", encoding="utf-8", newline="\n").write(text)
    missing = [(k, v) for k, v in LANG.items() if k not in existing]
    if not missing:
        return changed
    body = text.rstrip()
    assert body.endswith("}")
    body = body[:-1].rstrip()
    lines = ",\n".join("  " + json.dumps(k, ensure_ascii=False) + ": " + json.dumps(v, ensure_ascii=False)
                       for k, v in missing)
    out = body + ",\n" + lines + "\n}\n"
    json.loads(out)
    open(path, "w", encoding="utf-8", newline="\n").write(out)
    return len(missing)


def rewrite(path, fn):
    data = json.load(open(path, encoding="utf-8"))
    if fn(data):
        open(path, "w", encoding="utf-8", newline="\n").write(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
        return 1
    return 0


def add_page(data):
    pages = data["pages"]
    if any(p.get("id") == PAGE["id"] for p in pages):
        return False
    at = next((i + 1 for i, p in enumerate(pages) if p.get("id") == "builder.hut"), len(pages))
    pages.insert(at, PAGE)
    return True


def add_family(data):
    changed = False
    if "building_plan" not in data["families"]:
        data["families"]["building_plan"] = FAMILY
        changed = True
    if "hearthstead:building_plan" not in data["items"]:
        data["items"]["hearthstead:building_plan"] = "building_plan"
        changed = True
    return changed


print("lang keys added:", lang())
print("builder page added:", rewrite(os.path.join(ASSETS, "handbook", "chapters", "builder.json"), add_page))
print("item family added:", rewrite(os.path.join(ASSETS, "handbook", "items.json"), add_family))
