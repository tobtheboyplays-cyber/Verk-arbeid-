"""Generates data/hearthstead/recipe/building_plan_<type>.json (building-plan lane).

Each plan is shapeless: paper + coal or charcoal (ink) + one signature item.
Gating needs no entry here: TechRecipeGates.buildingFor maps building_plan_<type>
to the node that unlocks that building. Re-run after editing SIGNATURES.
"""
import json, os, sys

SIGNATURES = {
    "house": {"tag": "minecraft:wooden_doors"},
    "builders_hut": {"item": "minecraft:crafting_table"},
    "lumber_camp": {"tag": "minecraft:logs"},
    "farmhouse": {"item": "minecraft:wheat_seeds"},
    "warehouse": {"item": "minecraft:chest"},
    "well": {"item": "minecraft:bucket"},
    "kitchen": {"item": "minecraft:bowl"},
    "bakery": {"item": "minecraft:wheat"},
    "dining_hall": {"item": "minecraft:bread"},
    "tavern": {"item": "minecraft:barrel"},
    "lodging": {"tag": "minecraft:beds"},
    "market": {"item": "minecraft:apple"},
    "trading_post": {"item": "minecraft:emerald"},
    "fishery": {"item": "minecraft:cod"},
    "pasture": {"tag": "minecraft:wooden_fences"},
    "butcher": {"item": "minecraft:beef"},
    "hunters_lodge": {"item": "minecraft:arrow"},
    "tannery": {"item": "minecraft:leather"},
    "weaver": {"item": "minecraft:string"},
    "carpenter": {"tag": "minecraft:wooden_slabs"},
    "sawmill": {"item": "minecraft:stone_axe"},
    "mason": {"item": "minecraft:cobblestone"},
    "mill": {"item": "minecraft:stone"},
    "mine": {"item": "minecraft:stone_pickaxe"},
    "smelter": {"item": "minecraft:furnace"},
    "smithy": {"item": "minecraft:iron_ingot"},
    "armoury": {"item": "minecraft:shield"},
    "fletcher": {"item": "minecraft:flint"},
    "barracks": {"item": "minecraft:red_wool"},
    "sword_hall": {"item": "minecraft:stone_sword"},
    "pike_yard": {"item": "minecraft:stick"},
    "infirmary": {"item": "minecraft:white_wool"},
    "brewery": {"item": "minecraft:glass_bottle"},
    "library": {"item": "minecraft:book"},
    "school": {"item": "minecraft:ink_sac"},
    "architects_study": {"item": "minecraft:compass"},
    "rune_hall": {"item": "minecraft:lapis_lazuli"},
}

root = sys.argv[1] if len(sys.argv) > 1 else "src/main/resources/data/hearthstead/recipe"
for type_id, sig in SIGNATURES.items():
    recipe = {
        "type": "minecraft:crafting_shapeless",
        "category": "misc",
        "group": "hearthstead:building_plan",
        "ingredients": [{"item": "minecraft:paper"}, {"tag": "minecraft:coals"}, sig],
        "result": {"id": "hearthstead:building_plan", "count": 1,
                   "components": {"hearthstead:building_type": type_id}},
    }
    with open(os.path.join(root, "building_plan_" + type_id + ".json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(recipe, f, indent=2)
        f.write("\n")
print(len(SIGNATURES), "recipes")
