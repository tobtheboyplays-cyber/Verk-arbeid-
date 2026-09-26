# Item catalog: how to GET and how to USE every mod item (owner rule, 26 Sep).
# Facts: code audit of ModItems/RoleItems/TradeEmblemItems + builder lane (26 Sep 12:xx).
# Produces families (tooltips), the Items & Tools chapter pages and lang keys.

H = "hearthstead:"

TIERS = ["wooden", "stone", "iron", "golden", "diamond", "netherite"]

EMBLEMS = [
    "lumberer", "farmer", "fisher", "courier", "trader", "innkeeper", "guard", "archer", "hunter", "sawyer",
    "scholar", "builder", "spearman", "longswordsman", "healer", "rune_mage", "miller", "herder", "baker",
    "butcher", "miner", "smelter", "smith", "tanner", "carpenter", "mason", "weaver", "cook", "brewer",
    "armourer", "fletcher",
]
PROPS = ["prop_smith_hammer", "prop_hammer", "prop_mallet", "prop_chisel", "prop_plane", "prop_cleaver",
         "prop_scraper", "prop_peel", "prop_ladle", "prop_shuttle", "prop_mash_paddle", "prop_sack_scoop"]

# family id -> dict(items, page, use, steps, obtain (None when every member has a recipe), hint)
FAMILIES = {
    "banner": dict(items=["hearth"], page="items.banner",
        use="Place it to found your settlement; right-click it to open it.",
        steps=["Place it on open ground with 2 free blocks above it.",
               "Right-click it to open the Banner screen.",
               "Sneak + right-click to open its storage.",
               "Right-click it holding a banner to copy that banner's colours."]),
    "handbook": dict(items=["handbook"], page="items.handbook",
        use="Right-click to read the Settler's Handbook.",
        steps=["Right-click in the air to open the Handbook.",
               "Sneak + right-click to ask what your settlement stores.",
               "Or press the Handbook key anywhere."],
        obtain="You get one when you first join; you can also craft it from a book and a sapling."),
    "plaque": dict(items=["plaque"], page="items.plaque",
        use="Hang it in a room, then fit a Build Plan to make it a building.",
        steps=["Hang it on a wall inside the room.",
               "Right-click it holding a Build Plan to set the building.",
               "Right-click with an empty hand to see its checklist.",
               "Sneak + right-click with an empty hand to take the plan back."]),
    "build_plan": dict(items=["build_plan"], page="items.plaque",
        use="Use it on a blank Plaque to set what the building is.",
        steps=["Learn the building in the Tech Tree: its plan recipe unlocks.",
               "Craft it: paper, a feather and the building's token item.",
               "Right-click a blank Plaque with it."],
        obtain="Crafted; each building's plan recipe unlocks when you learn it in the Tech Tree."),
    "work_scepter": dict(items=["work_scepter"], page="items.work_scepter", hint=True,
        use="Marks the area a Lumberer or Farmer works in.",
        steps=["Hold it and right-click the Lumber Camp or Farmhouse plaque.",
               "Right-click two ground corners around the work area.",
               "Right-click once more at the height you want.",
               "Check the outlined box and press Confirm Zone."]),
    "builders_plan": dict(items=["builders_plan"], page="items.builders_plan", hint=True,
        use="Right-click to order buildings, walls and upgrades from your Builder.",
        steps=["Right-click in the air to open the Plan.",
               "Pick a blueprint: a ghost follows your aim. Scroll rotates it.",
               "Right-click to check the spot, then press Confirm.",
               "Needs a Builder's Hut with a hired Builder."]),
    "survey_rod": dict(items=["survey_rod"], page="items.survey_rod", hint=True,
        use="Copies a structure you built into Our Designs.",
        steps=["Right-click one corner block.",
               "Right-click the opposite corner block (max 32 per side).",
               "Name it: it appears under Our Designs in the Builder's Plan.",
               "Left-click to cancel."]),
    "resource_scroll": dict(items=["resource_scroll"], page="items.resource_scroll", hint=True,
        use="Shows what a Builder's site needs and what is missing.",
        steps=["Right-click a Builder's Hut plaque to link the scroll.",
               "Right-click anywhere to see needs, stock and what is on the way."]),
    "patrol_map": dict(items=["patrol_map"], page="items.patrol_map", hint=True,
        use="Draws a patrol route for your guards.",
        steps=["Right-click the ground to add the next waypoint.",
               "Click waypoint 1 again to close the loop.",
               "Sneak + right-click a marker to remove it.",
               "Right-click the air to choose guards and shifts."]),
    "job_emblem": dict(items=[e + "_emblem" for e in EMBLEMS], page="items.emblems", hint=True,
        use="Right-click a settler to give them this job.",
        steps=["Buy it: right-click the Mayor with an empty hand.",
               "Hold it in your main hand.",
               "Right-click a settler without sneaking to hire them.",
               "It is only used up if the hire works."],
        obtain="Bought from the Mayor once its Tech Tree node is learned. Firing a worker returns it."),
    "blessing_seal": dict(items=["warden_oath_seal", "hearthward_seal", "thorned_roads_seal"],
        page="items.seals", hint=True,
        use="Sneak + right-click a settler or a plaque to bless it.",
        steps=["Win a raid, then right-click the Banner and pick a seal.",
               "Sneak + right-click a settler or a building's plaque.",
               "It is only used up if the blessing binds."],
        obtain="A reward you choose at the Banner after winning a raid."),
    "coin": dict(items=["gold_coin"], page="items.coins",
        use="The settlement's money: knowledge, emblems and recruits cost Coins.",
        steps=["Sell goods to the Traveling Merchant at the Banner.",
               "Win raids and help events for more Coins.",
               "Payments take from you, the Banner, then Warehouse chests."],
        obtain="Selling goods to the merchant, raid victories and world events pay Coins."),
    "role_weapon": dict(items=["wooden_spear", "iron_spear", "diamond_spear"],
        page="items.weapons",
        use="A spear for you, or for your Spearmen.",
        steps=["Fight with it yourself: spears reach 1 block farther.",
               "Or put it in a Warehouse chest.",
               "A Courier brings it to the soldier who asks for it."]),
    # Weapons lane facts (26 Sep): 4 captain types x 6 tiers + longswords. Netherite is smithed.
    "captain_short_sword": dict(items=[t + "_short_sword" for t in TIERS], page="items.short_sword",
        use="A fast one-handed blade, carried as a pair.",
        steps=["Craft two and carry one in each hand.",
               "Hold one in each hand; you attack with the main hand.",
               "Give a pair to your Captain for the Dual Swords loadout."]),
    "captain_double_axe": dict(items=[t + "_double_axe" for t in TIERS], page="items.double_axe",
        use="Two-handed great axe: cleaves armour and knocks shields down.",
        steps=["Keep your off hand empty for the two-handed guard.",
               "Hits deal more to armoured foes, and +2 to Brutes.",
               "A hit on a raised shield knocks it down for 5 seconds.",
               "Give it to your Captain for the Great Axe loadout."]),
    "captain_halberd": dict(items=[t + "_halberd" for t in TIERS], page="items.halberd",
        use="Two-handed polearm: 1.5 blocks more reach, punishes charges.",
        steps=["Keep your off hand empty for the two-handed guard.",
               "You reach 1.5 blocks further than with a sword.",
               "Hit a foe running at you for +35% damage."]),
    "captain_warhammer": dict(items=[t + "_warhammer" for t in TIERS], page="items.warhammer",
        use="Two-handed hammer: big knockback, breaks shields, may stun.",
        steps=["Keep your off hand empty for the two-handed guard.",
               "A quarter of your hits stun: foes slow down or stagger.",
               "It breaks a raised shield."]),
    "longsword": dict(items=[t + "_longsword" for t in TIERS], page="items.longsword",
        use="A heavy two-handed sword that cleaves several foes.",
        steps=["Keep your off hand empty and swing into a crowd.",
               "Or put it in a Warehouse chest for your Longswordsmen."]),
    "bandage": dict(items=["bandage"], page="items.battle_supplies",
        use="Hold right-click to heal 8 health over 8 seconds.",
        steps=["When hurt, hold right-click for 1.5 seconds.",
               "Healers carry bandages and restock from the Infirmary or Warehouse."]),
    "rune_stone": dict(items=["rune_stone"], page="items.battle_supplies",
        use="Refills a Rune Mage's spell charges.",
        steps=["Put rune stones in the Rune Hall or a Warehouse chest.",
               "Rune Mages fetch them to refill their charges."]),
    "fishers_rod": dict(items=["fishers_rod"], page="items.fishery",
        use="The Fisher's tool: they cannot fish without it.",
        steps=["Put it in a Warehouse chest; a Courier brings it to the Fisher.",
               "You can fish with it yourself too."]),
    "fishers_chair": dict(items=["fishers_chair"], page="items.fishery",
        use="The Fisher sits on it, facing the water.",
        steps=["Place it in the Fishery by the water.", "A Fishery needs one."]),
    "fish_rack": dict(items=["fish_rack"], page="items.fishery",
        use="Holds and shows off the Fisher's catch.",
        steps=["Place it in the Fishery: a Fishery needs one.", "Right-click to store up to 4 fish."]),
    "ale_tap": dict(items=["ale_tap"], page="items.workplace",
        use="Pours ale: right-click with a glass bottle and a Coin.",
        steps=["Place it on a wall with a barrel right behind it.",
               "A Tavern needs one.",
               "Right-click holding a glass bottle: it takes 1 Coin."]),
    "butchering_table": dict(items=["butchering_table"], page="items.workplace",
        use="Where your Hunter butchers carcasses quickly.",
        steps=["Place it inside the Hunter's Lodge.",
               "Right-click holding a carcass to lay it down.",
               "Right-click with an empty hand to take it back."]),
    "goods": dict(items=["flour", "malt", "ale", "iron_bloom", "timber_beam", "cured_hide", "wool_bolt"],
        page="items.goods",
        use="Trade goods: made in a workshop, used by the next one or sold.",
        steps=["Your workers make it in their workshop.",
               "Couriers carry it to where it is needed.",
               "The Traveling Merchant buys many goods for Coins."],
        obtain="Made by your Miller, Brewer, Innkeeper, Smelter, Sawyer, Butcher or Weaver."),
    "fish": dict(items=["river_perch", "brown_trout", "silver_pike", "golden_char", "fish_portion"],
        page="items.fish_game",
        use="Caught by your Fisher: eat it, sell it or turn it into meals.",
        steps=["Put plain fish in the Banner: it cuts them into Fish Portions.",
               "Graded fish are for selling, not for meals.",
               "The merchant pays well for the better fish."],
        obtain="Caught by a Fisher settler; Fish Portions are made in the Banner."),
    "carcass": dict(items=["carcass"], page="items.fish_game",
        use="An animal your Hunter shot, waiting to be butchered.",
        steps=["The Hunter carries it home and butchers it.",
               "On a Butchering Table it goes much faster."],
        obtain="Only from animals your Hunter shoots."),
    "goblin_loot": dict(items=["poop_stick", "troll_toenail"], page="items.oddities",
        use="Goblin loot. The stick poisons; the toenail is best not eaten.",
        steps=["Hit an enemy with the stick: poison for 5 seconds.",
               "Eating the toenail drops you to half a heart."],
        obtain="Goblin thieves sometimes drop them."),
    "spawn_egg": dict(items=["settler_spawn_egg"], page="items.oddities",
        use="Right-click the ground to spawn a settler.",
        steps=["Right-click the ground where the settler should appear."],
        obtain="Creative menu only."),
    "props": dict(items=PROPS, internal="Display-only work tools drawn in settlers' hands; never in any inventory."),
}

# Items & Tools chapter: page slug -> (title, summary bullets, recipes, icons, tip, link, families shown)
PAGES = [
    ("banner", "The Banner", ["The heart of your settlement: everything is counted from it."],
     ["hearth"], ["hearth"], "Place it where you want the town square.", "founding.p1", ["banner"]),
    ("handbook", "The Handbook", ["This book. You get one when you first join."],
     ["handbook"], ["handbook"], "Lost it? Craft a new one from a book and any sapling.", "start_here.welcome", ["handbook"]),
    ("plaque", "Plaque and Build Plans", ["A Plaque turns a room into a building once a plan is fitted."],
     ["plaque", "build_plan_lumber_camp"], ["plaque", "build_plan"], "Hang the plaque first, then build the room around its checklist.",
     "plaque.p1", ["plaque", "build_plan"]),
    ("work_scepter", "Work Scepter", ["Draws the Work Zone your Lumberers and Farmers work in."],
     ["work_scepter"], ["work_scepter"], "Draw a zone around a few whole trees.", "work_zones.p1", ["work_scepter"]),
    ("builders_plan", "Builder's Plan", ["Your order book for the Builder: buildings, walls and upgrades."],
     ["builders_plan"], ["builders_plan"], "Check the Sites tab when a Builder seems stuck.", "builder.hut", ["builders_plan"]),
    ("survey_rod", "Survey Rod", ["Save something you built so the Builder can build it again."],
     ["survey_rod"], ["survey_rod"], "Click blocks, not the air: two corners, then a name.", "builder.designs", ["survey_rod"]),
    ("resource_scroll", "Resource Scroll", ["Your Builder's shopping list, readable anywhere."],
     ["resource_scroll"], ["resource_scroll"], "Link it once, then check it before a supply run.", "builder.sites", ["resource_scroll"]),
    ("patrol_map", "Patrol Map", ["Draw a route your guards walk on their shift."],
     ["patrol_map"], ["patrol_map"], "Close the loop by clicking the first marker again.", "patrols.watch", ["patrol_map"]),
    ("emblems", "Job Emblems", ["An Emblem is a job contract: give it to a settler to hire them."],
     [], ["lumberer_emblem", "farmer_emblem", "courier_emblem", "guard_emblem", "archer_emblem", "builder_emblem"],
     "Prices are listed under More detail.", "jobs.p1", ["job_emblem"]),
    ("seals", "Blessing Seals", ["A raid victory lets you pick one seal: a lasting blessing."],
     [], ["warden_oath_seal", "hearthward_seal", "thorned_roads_seal"], "Bless your best defender or the Barracks.",
     "raids.p1", ["blessing_seal"]),
    ("coins", "Coins", ["Real items, and the only money in Bannerhold."],
     [], ["gold_coin"], "Keep spare Coins in the Banner: purchases can take them from there.", "coins.p1", ["coin"]),
    ("weapons", "Spears", ["Spears for you and for your Spearmen."],
     ["wooden_spear", "iron_spear"], ["wooden_spear", "iron_spear", "diamond_spear"],
     "Stock one spare weapon per soldier in the Warehouse.", "battle_roles.front", ["role_weapon"]),
    ("short_sword", "Short Swords", ["Light blades for a two-sword fighter."],
     ["iron_short_sword"], [t + "_short_sword" for t in ["wooden", "iron", "diamond", "netherite"]],
     "Netherite ones are made at a smithing table from diamond ones.", "hero_captain.specials", ["captain_short_sword"]),
    ("double_axe", "Double Axe", ["A great axe for breaking armour and shields."],
     ["iron_double_axe"], [t + "_double_axe" for t in ["wooden", "iron", "diamond", "netherite"]],
     "Save it for armoured raiders and Brutes.", "hero_captain.specials", ["captain_double_axe"]),
    ("halberd", "Halberd", ["A long polearm: strike first, strike far."],
     ["iron_halberd"], [t + "_halberd" for t in ["wooden", "iron", "diamond", "netherite"]],
     "Stand still and let them run onto your point.", "hero_captain.specials", ["captain_halberd"]),
    ("warhammer", "Warhammer", ["A heavy hammer that stuns and smashes shields."],
     ["iron_warhammer"], [t + "_warhammer" for t in ["wooden", "iron", "diamond", "netherite"]],
     "Knock a shield-bearer down before your guards charge.", "hero_captain.specials", ["captain_warhammer"]),
    ("longsword", "Longswords", ["Two-handed swords that cleave through a crowd."],
     ["stone_longsword", "iron_longsword"], [t + "_longsword" for t in ["wooden", "iron", "diamond", "netherite"]],
     "Longswordsmen ask for one before they can fight.", "battle_roles.front", ["longsword"]),
    ("battle_supplies", "Bandages and Rune Stones", ["Supplies your Healers and Rune Mages use up."],
     ["bandage", "rune_stone"], ["bandage", "rune_stone"], "Keep a stack of each in the Warehouse before a raid.",
     "battle_roles.support", ["bandage", "rune_stone"]),
    ("fishery", "Fishery gear", ["A Fishery needs a chair and a rack; the Fisher needs the rod."],
     ["fishers_rod", "fishers_chair", "fish_rack"], ["fishers_rod", "fishers_chair", "fish_rack"],
     "Put the chair right at the water's edge.", "jobs.p1", ["fishers_rod", "fishers_chair", "fish_rack"]),
    ("workplace", "Ale Tap and Butchering Table", ["Workplace blocks for the Tavern and the Hunter's Lodge."],
     ["ale_tap", "butchering_table"], ["ale_tap", "butchering_table"], "The tap needs a barrel directly behind it.",
     "tavern.p1", ["ale_tap", "butchering_table"]),
    ("goods", "Trade goods", ["Flour, malt, ale, iron bloom, beams, hides and wool bolts."],
     ["flour", "wool_bolt"], ["flour", "malt", "ale", "iron_bloom", "timber_beam", "wool_bolt"],
     "Better workers make better goods, and better goods sell for more.", "quality.grades", ["goods"]),
    ("fish_game", "Fish and game", ["What your Fisher catches and your Hunter brings home."],
     [], ["river_perch", "brown_trout", "silver_pike", "golden_char", "fish_portion", "carcass"],
     "Plain fish in the Banner become meals for everyone.", "logistics.p1", ["fish", "carcass"]),
    ("oddities", "Oddities", ["Goblin loot and the settler spawn egg."],
     [], ["poop_stick", "troll_toenail", "settler_spawn_egg"], "Don't eat the toenail.", "raids.p2",
     ["goblin_loot", "spawn_egg"]),
]

EMBLEM_PRICES = (
    "Prices at the Mayor (Coins first, then goods): Lumberer 1 + 2 flint. Farmer 2 + 8 seeds + 1 leather. "
    "Fisher 2 + 2 string + 1 leather. Courier 2 + chest + 2 leather. Trader 2 + 2 paper + 2 leather. "
    "Innkeeper 2 + 2 bread + 2 leather. Guard 3 + 2 iron + 1 leather. Archer 3 + 4 arrows + 1 leather. "
    "Hunter 2 + 12 arrows + 2 leather. Sawyer 2 + 2 iron + 2 leather. Scholar 2 + 2 books + 2 leather. "
    "Builder 2 + 2 flint + 8 sticks. Spearman 4 + 1 iron + 1 leather. Longswordsman 5 + 3 iron + 2 leather. "
    "Healer 3 + 2 paper + 1 leather. Rune Mage 6 + 8 lapis + 4 amethyst + 1 book. "
    "Other trades cost 2 Coins plus a trade item: Miller 8 wheat, Herder hay, Baker 2 bread, Butcher iron, "
    "Miner 8 torches, Smelter furnace, Smith 2 iron, Tanner 2 leather, Carpenter 8 sticks, Mason 16 cobblestone, "
    "Weaver 4 string, Cook 2 bowls, Brewer 2 bottles, Armourer 2 iron, Fletcher 4 feathers (most also 1 leather). "
    "Each Emblem is sold once its Tech Tree node is learned.")


TIER_NAMES = {"wooden": "Wooden", "stone": "Stone", "iron": "Iron", "golden": "Golden", "diamond": "Diamond",
              "netherite": "Netherite"}
# Final weapon gates (weapons/watch lanes, 26 Sep): recipe grids read the live gate too.
TIER_GATES = {"wooden": "Barracks & Guard", "stone": "Barracks & Guard", "iron": "Armoury & Armourer",
              "golden": "Armoury & Armourer", "diamond": "Master Armoury", "netherite": "Master Armoury"}
TIER_TABLES = {
    "short_sword": ("short_sword", [3, 4, 5, 3, 6, 7], "2.0 attacks per second"),
    "double_axe": ("double_axe", [8, 9, 10, 8, 11, 12], "0.8 attacks per second, sweeps"),
    "halberd": ("halberd", [6, 7, 8, 6, 9, 10], "0.9 attacks per second, sweeps"),
    "warhammer": ("warhammer", [7, 8, 9, 7, 10, 11], "0.8 attacks per second, no sweep"),
}


def build(L, page_fn):
    """Returns (items.json dict, chapter dict). L(key, text) registers a lang key."""
    families = {}
    items = {}
    for fid, f in FAMILIES.items():
        entry = {}
        if f.get("internal"):
            entry["internal"] = f["internal"]
        else:
            base = "hearthstead.howto." + fid
            entry["page"] = f["page"]
            entry["use"] = L(base + ".use", f["use"])
            entry["steps"] = [L("%s.s%d" % (base, i + 1), s) for i, s in enumerate(f["steps"])]
            if f.get("obtain"):
                entry["obtain"] = L(base + ".obtain", f["obtain"])
            if f.get("hint"):
                entry["hint"] = True
        families[fid] = entry
        for item in f["items"]:
            items[H + item] = fid
    catalog = {"schema": 1, "families": families, "items": items}

    pages = []
    for slug, title, bullets, recipes, icons, tip, link, fams in PAGES:
        p = page_fn("items", slug, title, bullets, tip=tip, items=[H + i for i in icons[:6]],
                    keys=[tuple(k) for k in PAGE_KEYS.get(slug, [])])
        main = FAMILIES[fams[0]]
        p["steps"] = ["hearthstead.howto.%s.s%d" % (fams[0], i + 1) for i in range(len(main["steps"]))]
        # Other families on the page: their one-line use becomes an extra bullet.
        for other in fams[1:]:
            p["bullets"].append("hearthstead.howto.%s.use" % other)
        obtain = next((FAMILIES[x].get("obtain") for x in fams if FAMILIES[x].get("obtain")), None)
        if obtain:
            p["obtain"] = "hearthstead.howto.%s.obtain" % next(x for x in fams if FAMILIES[x].get("obtain"))
        if recipes:
            p["recipes"] = [H + r for r in recipes]
        if link:
            p["link"] = link
        if slug in TIER_TABLES:
            kind, dmg, speed = TIER_TABLES[slug]
            p["entries"] = []
            for t, d in zip(TIERS, dmg):
                base = "hearthstead.guide.items.%s.tier_%s" % (slug, t)
                p["entries"].append({"icon": H + t + "_" + kind,
                                     "name": L(base + ".name", TIER_NAMES[t]),
                                     "text": L(base + ".text", "%d damage, %s. Unlocked by %s." % (
                                         d, speed, TIER_GATES[t]))})
        if slug == "emblems":
            p["text"] = [L("hearthstead.guide.items.emblems.prices", EMBLEM_PRICES)]
        pages.append(p)
    return catalog, pages

# Key chips per Items & Tools page: (KeyMapping, action text, extra fields)
PAGE_KEYS = {
    "banner": [("key.use", "Open the Banner"), ("key.use", "Open its storage", {"modifier": "key.sneak"})],
    "handbook": [("key.use", "Read"), ("key.hearthstead.handbook", "Open the Handbook")],
    "plaque": [("key.use", "Fit plan / checklist"), ("key.use", "Take the plan back", {"modifier": "key.sneak"})],
    "work_scepter": [("key.use", "Mark a corner")],
    "builders_plan": [("key.use", "Open / place"), ("key.attack", "Cancel")],
    "survey_rod": [("key.use", "Mark a corner"), ("key.attack", "Cancel")],
    "resource_scroll": [("key.use", "Link / read")],
    "patrol_map": [("key.use", "Add a waypoint"), ("key.use", "Remove a marker", {"modifier": "key.sneak"})],
    "emblems": [("key.use", "Hire the settler")],
    "seals": [("key.use", "Bless", {"modifier": "key.sneak"})],
    "weapons": [("key.attack", "Strike")],
    "short_sword": [("key.attack", "Strike"), ("key.swapOffhand", "Put one in your off hand")],
    "double_axe": [("key.attack", "Strike")],
    "halberd": [("key.attack", "Strike")],
    "warhammer": [("key.attack", "Strike")],
    "longsword": [("key.attack", "Strike")],
    "battle_supplies": [("key.use", "Bandage yourself", {"hold": True})],
    "fishery": [("key.use", "Open the rack")],
    "workplace": [("key.use", "Pour ale / lay carcass")],
    "oddities": [("key.use", "Spawn a settler")],
}
