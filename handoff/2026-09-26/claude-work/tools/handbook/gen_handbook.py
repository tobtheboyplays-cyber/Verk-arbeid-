# Handbook lane generator: my chapter files, the index, UI/start/summary lang keys, textures.
import json, os, re, sys
from PIL import Image

REPO = r"C:\Users\tobia\Hearthstead-Claude\Verk-arbeid-\hearthstead-neoforge\src\main\resources\assets\hearthstead"
HB = os.path.join(REPO, "handbook")
TEX = os.path.join(REPO, "textures", "gui", "handbook")
LANG = os.path.join(REPO, "lang", "en_us.json")
B = r"C:\Users\tobia\Hearthstead-Claude"
SCR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "frames")

lang = {}

def L(key, text):
    lang[key] = text
    return key

# ------------------------------------------------------------------ images
# name -> (source, (x, y, w, h)) ; w/h must be 16:9
IMAGES = {
    "tech_tree": (B + r"\videos\ui\ui-tech-tree-default.png", (40, 180, 800, 450)),
    "plaque_checklist": (B + r"\videos\ui\ui-plaque-requirements.png", (64, 36, 1152, 648)),
    "work_zone": (B + r"\videos\ui\ui-work-zone-confirm.png", (30, 30, 1220, 686)),
    "storage_page": (B + r"\videos\ui\banner-stills\figures\chk-storage.png", (40, 22, 1200, 675)),
    "realm_map": (B + r"\videos\ui\realm-map-still-1-hover.png", (296, 150, 704, 396)),
    "finisher_glow": (B + r"\shots\finisher\glow-player.png", (320, 150, 640, 360)),
    "talk": (SCR + r"\barter.png", (0, 0, 1280, 720)),
    "parley": (SCR + r"\parley.png", (440, 247, 840, 473)),
}
# Replace world shots with QA shots when they exist.
QA = B + r"\qa-survival\shots\handbook"
QA_SHOTS = {
    "banner_placed": "hb_banner_placed.png", "mayor_shop": "hb_mayor_shop.png", "emblem_hire": "hb_emblem_hire.png",
    "plaque_room": "hb_plaque_room.png", "plaque_screen": "hb_plaque_screen.png", "scepter_zone": "hb_scepter_zone.png",
    "axe_request": "hb_axe_request.png", "lumberer_work": "hb_lumberer_work.png", "warehouse": "hb_warehouse.png",
    "courier": "hb_courier.png", "builders_plan": "hb_builders_plan.png", "survey_rod": "hb_survey_rod.png",
    "ghost": "hb_ghost.png", "tech_tree_qa": "hb_tech_tree.png", "merchant": "hb_merchant.png",
    # Current Banner nav (Overview, Settlers, Buildings, Storage, Journey, Tech Tree), fresh world.
    "journey": "hb_journey_panel.png", "settlers_page": "hb_settlers_page.png",
    "buildings_page": "hb_buildings_page.png", "storage_requests": "hb_storage_requests.png",
    "settler_sheet": "hb_settler_sheet.png", "command_dots": "hb_command_dots.png", "patrol_ui": "hb_patrol.png",
    "banner_fresh": "hb_banner_overview.png",
}
for name, file in QA_SHOTS.items():
    p = os.path.join(QA, file)
    if os.path.exists(p):
        im = Image.open(p)
        w, h = im.size
        cw = min(w, h * 16 // 9)
        ch = cw * 9 // 16
        IMAGES[name] = (p, ((w - cw) // 2, (h - ch) // 2, cw, ch))

def make_images():
    os.makedirs(TEX, exist_ok=True)
    for old in os.listdir(TEX):
        if old[:-4] not in IMAGES:
            os.remove(os.path.join(TEX, old))
    made = set()
    for name, (src, (x, y, w, h)) in IMAGES.items():
        if not os.path.exists(src):
            print("missing source", src)
            continue
        im = Image.open(src).convert("RGB")
        im = im.crop((x, y, x + w, y + h)).resize((256, 144), Image.LANCZOS)
        im.save(os.path.join(TEX, name + ".png"), optimize=True)
        made.add(name)
    return made

def img(name, caption=None, placement="auto"):
    o = {"texture": "hearthstead:textures/gui/handbook/%s.png" % name, "width": 256, "height": 144}
    if caption:
        o["caption"] = caption
    o["placement"] = placement
    return o

# ------------------------------------------------------------------ pages
def page(ch, slug, title=None, bullets=(), tip=None, text=(), keys=(), items=(), image=None, journey=(), caption=None):
    pid = "%s.%s" % (ch, slug)
    base = "hearthstead.guide.%s.%s" % (ch, slug)
    p = {"id": pid}
    if title:
        p["title"] = L(base + ".title", title)
    if image:
        p["image"] = img(image, L(base + ".caption", caption) if caption else None)
    p["bullets"] = [L("%s.b%d" % (base, i + 1), b) for i, b in enumerate(bullets)]
    if keys:
        p["keys"] = []
        for i, k in enumerate(keys):
            o = {"key": k[0], "action": L("%s.k%d" % (base, i + 1), k[1])}
            for extra in k[2:]:
                o.update(extra)
            p["keys"].append(o)
    if items:
        p["items"] = list(items)
    if tip:
        p["tip"] = L(base + ".tip", tip)
    if text:
        p["text"] = list(text)
    if journey:
        p["journey"] = list(journey)
    return p

def J(step):
    return "journey.hearthstead.step.%s.description" % step

SNEAK = {"modifier": "key.sneak"}
HOLD = {"hold": True}

CH = {}

def chapter(cid, icon, pages, title=None):
    t = "hearthstead.guide.%s.title" % cid
    if title:
        L(t, title)
    CH[cid] = {"id": cid, "title": t, "icon": icon, "pages": pages}

# ---- Start here: the first ten minutes, following the Journey
S = "start_here"
chapter(S, "hearthstead:handbook", [
    page(S, "welcome", "Welcome to Bannerhold", [
        "You raise a Banner, give settlers jobs and defend them from raids.",
        "Settlers do real work with real items: tools, chests and bags.",
        "This chapter walks you through your first ten minutes, one step per page.",
        "Use Back and Next below, or pick any chapter on the left.",
    ], tip="Press Next to begin. The Journey at your Banner always shows the same next step.",
        keys=[("key.hearthstead.handbook", "Open this book")],
        image="banner_fresh" if "banner_fresh" in IMAGES else None,
        caption="The Banner screen: your settlement at a glance"),
    page(S, "banner", "1. Raise the Banner", [
        "Craft a Banner from 3 logs, 5 cobblestone and a campfire.",
        "Place it on open ground, away from other settlements, with two free blocks above it.",
        "Your Mayor and three founding workers walk in and rest by the Banner.",
    ], tip="Pick a flat spot with trees nearby: your first worker will be a Lumberer.",
        items=["hearthstead:hearth", "minecraft:oak_log", "minecraft:cobblestone", "minecraft:campfire"],
        image="banner_placed" if "banner_placed" in IMAGES else None,
        caption="Your settlement grows around the Banner", text=[J("fj_010_found_hearth")],
        journey=["fj_010_found_hearth"]),
    page(S, "journey", "2. Open the Journey", [
        "Right-click the Banner to open it.",
        "Press Journey in the left rail.",
        "The Journey always shows your current goal and exactly what it needs.",
    ], tip="Stuck? Open Journey first, then this book for the how.",
        keys=[("key.use", "Open the Banner")], image="journey", caption="Journey: the current goal and the next one",
        text=[J("fj_020_open_journey")], journey=["fj_020_open_journey"]),
    page(S, "mayor", "3. The Mayor and the Merchant", [
        "Right-click the Mayor with an empty hand to open the Job Emblem shop.",
        "A Traveling Merchant walks up to the Banner soon after you found it.",
        "Sell him logs, iron or crops for Coins: 8 basic logs make 1 Coin.",
    ], tip="Chop 16 logs now: 8 to sell and 8 for the Lumber Camp.",
        keys=[("key.use", "Talk to the Mayor (empty hand)")],
        items=["hearthstead:gold_coin", "minecraft:oak_log", "minecraft:iron_ingot", "minecraft:wheat"],
        image="mayor_shop" if "mayor_shop" in IMAGES else None,
        text=[J("fj_030_appoint_mayor")], journey=["fj_030_appoint_mayor"]),
    page(S, "lumber_camp", "4. Learn the Lumber Camp", [
        "Open the Banner, choose Tech Tree and learn Lumber Camp.",
        "It costs 1 Coin, 8 logs and 8 cobblestone.",
        "Learning it gives you the Lumber Camp plan and the Lumberer Emblem.",
    ], tip="Hover a node in the Tech Tree to see its exact cost.",
        items=["hearthstead:gold_coin", "minecraft:oak_log", "minecraft:cobblestone"],
        image="tech_tree_qa" if "tech_tree_qa" in IMAGES else "tech_tree", caption="Tech Tree",
        text=[J("fj_100_unlock_lumber_camp")], journey=["fj_100_unlock_lumber_camp"]),
    page(S, "plaque", "5. Build the camp and hang its Plaque", [
        "Build a small room: crafting table, chest, door, a light and 16 floor blocks.",
        "Craft a Plaque (5 copper, 1 iron, 3 planks) and hang it inside.",
        "Use the Lumber Camp plan on the Plaque, then right-click it for the checklist.",
        "The Plaque glows green when the room is complete.",
    ], tip="Fix one red line at a time; the Plaque re-checks the room by itself.",
        keys=[("key.use", "Read the checklist")],
        items=["hearthstead:plaque", "hearthstead:build_plan", "minecraft:crafting_table", "minecraft:chest"],
        image="plaque_room" if "plaque_room" in IMAGES else "plaque_checklist", caption="The Plaque's checklist",
        text=[J("fj_110_link_lumber_camp")], journey=["fj_110_link_lumber_camp"]),
    page(S, "hire", "6. Hire your Lumberer", [
        "Buy a Lumberer Emblem from the Mayor: 1 Coin and 2 flint.",
        "Hold it and right-click a founder without sneaking: they take the job.",
        "Empty both hands and Shift-right-click them to see their inventory.",
    ], tip="There is no Hire button: the Emblem in your hand is the contract.",
        keys=[("key.use", "Give the Emblem"), ("key.use", "Open their inventory", SNEAK)],
        items=["hearthstead:lumberer_emblem", "minecraft:flint"],
        image="emblem_hire" if "emblem_hire" in IMAGES else "settlers_page", caption="Settlers and their jobs",
        text=[J("fj_120_staff_lumber_camp"), J("fj_130_open_lumberer_inventory")],
        journey=["fj_120_staff_lumber_camp", "fj_130_open_lumberer_inventory"]),
    page(S, "zone", "7. Mark the trees", [
        "Craft a Work Scepter: 2 flint, 1 copper ingot and 2 sticks.",
        "Right-click the camp's Plaque with it, then two ground corners around trees.",
        "Right-click once more at the height you want, then press Confirm Zone.",
    ], tip="Include a few whole trees, trunk and leaves, inside the box.",
        keys=[("key.use", "Mark a corner")], items=["hearthstead:work_scepter", "minecraft:flint", "minecraft:copper_ingot"],
        image="scepter_zone" if "scepter_zone" in IMAGES else "work_zone", caption="Confirm the zone before it counts",
        text=[J("fj_140_set_lumber_zone")], journey=["fj_140_set_lumber_zone"]),
    page(S, "axe", "8. Give them an axe", [
        "The Lumberer asks for an axe. Any axe works, even a wooden one.",
        "Put it in their inventory or in the Lumber Camp chest.",
        "They fell a tree in the zone and store the logs in the camp chest.",
    ], tip="Watch the first tree fall, then check Journey for the next goal.",
        items=["minecraft:wooden_axe", "minecraft:stone_axe", "minecraft:iron_axe"],
        image="lumberer_work" if "lumberer_work" in IMAGES else ("axe_request" if "axe_request" in IMAGES else None),
        text=[J("fj_150_lumberer_requests_axe"), J("fj_160_give_lumberer_axe"), J("fj_170_lumberer_fells_tree"),
              J("fj_180_lumber_camp_stores_log")],
        journey=["fj_150_lumberer_requests_axe", "fj_160_give_lumberer_axe", "fj_170_lumberer_fells_tree",
                 "fj_180_lumber_camp_stores_log"]),
    page(S, "next", "What comes next", [
        "Warehouse and Courier: goods start moving by themselves (see Logistics).",
        "Farm, Homes and a Tavern: food, beds and new settlers.",
        "The Watch: arm a Guard and an Archer before the first raid.",
        "Journey shows each next step; this book explains how to do it.",
    ], tip="Open Journey now and read your next goal.", image="defense_page",
        caption="Defense: who guards the settlement"),
], title="Start here")

# ---- The existing chapters, each page summarised as bullets with the old text kept as detail
def g(ch, n=""):
    return "hearthstead.guide.%s.body%s" % (ch, n)

chapter("founding", "hearthstead:hearth", [
    page("founding", "p1", "Found your settlement", [
        "Craft a Banner from 3 logs, 5 cobblestone and a campfire.",
        "Place it on open ground away from other settlements, with two free blocks above.",
        "Your Mayor and three founders arrive; later recruits each need a free bed.",
        "Purchases take Coins and goods from you, then the Banner, then Warehouse chests.",
    ], tip="Right-click the Banner and press Journey: it always shows your next step.",
        items=["hearthstead:hearth", "minecraft:oak_log", "minecraft:cobblestone", "minecraft:campfire"],
        keys=[("key.use", "Open the Banner")],
        image="banner_placed" if "banner_placed" in IMAGES else None, text=[g("founding")]),
])
chapter("coins", "hearthstead:gold_coin", [
    page("coins", "p1", "Earn your first Coins", [
        "Coins are real items and the settlement's only currency.",
        "A Traveling Merchant walks to the Banner while you are within 80 blocks.",
        "Basic logs, iron and fish sell 8 per Coin; crops sell 16 per Coin.",
        "Repeat sales of the same goods cost 25% more, so bring a mix.",
    ], tip="Sell 8 logs to the first merchant for your first Coin.",
        keys=[("key.use", "Trade with the merchant")],
        items=["hearthstead:gold_coin", "minecraft:oak_log", "minecraft:iron_ingot", "minecraft:cod", "minecraft:wheat"],
        image="merchant" if "merchant" in IMAGES else None, text=[g("coins")],
        journey=[]),
    page("coins", "p2", "Spending Coins", [
        "Spend Coins on Tech Tree knowledge, upgrades, Job Emblems and recruits.",
        "Payments come from your inventory first, then the Banner, then Warehouse chests.",
        "Winning the first raid pays 8 Coins; each later victory pays 4.",
    ], tip="A refused purchase always lists exactly what is missing.", text=[g("coins", "2")]),
])
chapter("mayor", "minecraft:bell", [
    page("mayor", "p1", "Your Mayor", [
        "The Mayor arrives with the founders and serves the settlement full-time.",
        "Right-click the Mayor with an empty hand to open the Job Emblem shop.",
        "The Mayor's best attribute boosts the whole settlement after a day and a half.",
        "Losing the Mayor hurts morale; a successor's boon waits three days.",
    ], tip="Keep the Mayor safe during raids: a new one takes time to help.",
        keys=[("key.use", "Open the Emblem shop")], image="mayor_shop" if "mayor_shop" in IMAGES else None,
        text=[g("mayor")]),
])
chapter("plaque", "hearthstead:plaque", [
    page("plaque", "p1", "Plaques make buildings", [
        "Craft a Plaque from 5 copper, 1 iron and 3 planks and hang it inside the room.",
        "Use a Build Plan on it to set what the building is.",
        "Its glow shows progress: red missing, amber partly done, green complete.",
    ], tip="Hang the plaque first, then build the room around its checklist.",
        items=["hearthstead:plaque", "hearthstead:build_plan", "minecraft:copper_ingot", "minecraft:iron_ingot"],
        image="plaque_screen" if "plaque_screen" in IMAGES else "plaque_checklist", caption="A plaque's checklist",
        text=[g("plaque")]),
    page("plaque", "p2", "Read the checklist", [
        "Right-click a plaque to see every requirement: space, furniture, storage, access.",
        "Green checks are done; each missing line says what to build.",
        "The room is re-checked by itself; Refresh checks it now.",
        "Beds show yellow when free and green when assigned.",
    ], tip="Open Banner, then Buildings, to see every building's state at once.",
        keys=[("key.use", "Open the checklist")], image="buildings_page", caption="Banner, Buildings page",
        text=[g("plaque", "2")]),
])
chapter("tech_tree", "minecraft:lectern", [
    page("tech_tree", "p1", "Learn new buildings", [
        "Open the Banner and choose Tech Tree.",
        "Each node teaches building plans and unlocks Job Emblems.",
        "Learn a node once its parent is learned and its quest is done.",
        "Costs are Coins plus goods, all paid at once.",
    ], tip="Start with Lumber Camp: 1 Coin, 8 logs, 8 cobblestone.",
        image="tech_tree_qa" if "tech_tree_qa" in IMAGES else "tech_tree", text=[g("tech_tree")]),
    page("tech_tree", "p2", "Upgrades", [
        "Upgrades are one-time purchases in the same tree.",
        "Early picks: Warm Homes, Sharpened Axes, Stout Straps and Guard Drill.",
        "After the first raid, First Raid Aftermath opens the outer ring.",
    ], tip="Hover any node for its exact cost, requirement and effect.", text=[g("tech_tree", "2")]),
])
chapter("day", "minecraft:clock", [
    page("day", "p1", "The settlement's day", [
        "Everyone shares one clock: Rise, Work, Meal, Evening and Rest.",
        "Workers walk to their trade in the morning and eat together at midday.",
        "A raid or real exhaustion overrides the schedule.",
        "Half of your Guards keep a night watch.",
    ], tip="Plan big jobs for the morning: evenings belong to the Tavern.", text=[g("day")]),
])
chapter("research", "minecraft:writable_book", [
    page("research", "p1", "Research", [
        "Research needs a registered Architect's Study.",
        "First finish the first-raid aftermath, then learn Commons Doctrine.",
        "Right-click the study's lectern to open the research bench.",
        "Hire a Scholar to push projects forward.",
    ], tip="Research is optional: build it once the first raid is behind you.", text=[g("research")]),
    page("research", "p2", "Projects", [
        "Six projects are available; work on one at a time.",
        "Each costs paper and a trade sample, paid up front.",
        "Finished research gives a permanent bonus.",
    ], tip="Cancelling returns half the sample, never the paper.", text=[g("research", "2")]),
])
chapter("jobs", "hearthstead:lumberer_emblem", [
    page("jobs", "p1", "Give a settler a job", [
        "Learn and register the workplace, then buy its Job Emblem from the Mayor.",
        "Hold the Emblem and right-click a settler without sneaking to hire them.",
        "New workers start without tools; their need icon shows what to bring.",
        "Empty both hands and Shift-right-click a settler to open their inventory.",
    ], tip="A refused assignment never uses up the Emblem.",
        keys=[("key.use", "Give the Emblem"), ("key.use", "Open inventory (empty hands)", SNEAK)],
        items=["hearthstead:lumberer_emblem", "hearthstead:farmer_emblem", "hearthstead:courier_emblem",
               "hearthstead:guard_emblem"],
        image="emblem_hire" if "emblem_hire" in IMAGES else "settlers_page", text=[g("jobs")]),
])
chapter("work_zones", "hearthstead:work_scepter", [
    page("work_zones", "p1", "Draw a Work Zone", [
        "Lumberers and Farmers only work inside a zone you draw.",
        "Craft a Work Scepter: 2 flint, 1 copper ingot and 2 sticks.",
        "Right-click the plaque or worker, then two ground corners, then the height.",
        "Check the outlined box and press Confirm Zone.",
    ], tip="A worker's settler sheet also has Edit Work Zone.",
        keys=[("key.use", "Mark a corner")],
        items=["hearthstead:work_scepter", "minecraft:flint", "minecraft:copper_ingot", "minecraft:stick"],
        image="scepter_zone" if "scepter_zone" in IMAGES else "work_zone", text=[g("work_zones")],
        journey=["fj_330_set_farm_zone"]),
    page("work_zones", "p2", "What a zone needs", [
        "A Lumber Zone needs a natural tree with its trunk and leaves.",
        "A Farm Zone needs farmland or a planted crop, with room above it.",
        "Zones stay inside the settlement, at most 48 x 64 x 48 blocks.",
    ], tip="NO WORK ZONE in Banner, Storage means that worker still needs one.",
        text=[g("work_zones", "2")]),
])
chapter("logistics", "hearthstead:courier_emblem", [
    page("logistics", "p1", "Couriers move everything", [
        "Goods move for real: from workplace chests, in a Courier's bag, to the Warehouse.",
        "Plan about one Courier for every 10 settlers.",
        "Banner, Storage lists open requests; hover one for its route and Stop reason.",
        "Lay dirt paths or gravel: carts are fast on roads and slow on grass.",
    ], tip="Open Banner, Storage and hover the first request to see who carries it.",
        items=["hearthstead:courier_emblem", "minecraft:chest", "minecraft:barrel"],
        image="storage_requests" if "storage_requests" in IMAGES else "storage_page",
        caption="Banner, Storage: stores and open requests", text=[g("logistics")],
        journey=["fj_200_unlock_warehouse", "fj_210_link_warehouse", "fj_220_staff_warehouse",
                 "fj_230_open_request_ledger", "fj_240_request_first_pickup", "fj_250_courier_claims_pickup",
                 "fj_260_warehouse_receives_log", "fj_380_warehouse_receives_crop"]),
])
chapter("crafting_orders", "minecraft:crafting_table", [
    page("crafting_orders", "p1", "Crafting orders", [
        "Workplaces open crafting orders by themselves when they run short.",
        "An order checks the workplace, then the Warehouse, then a workshop that can make it.",
        "If nobody can make it, it is marked Needs you in Banner, Storage.",
        "Put the item in that workplace or a Warehouse chest and the order closes.",
    ], tip="Check the top of the open requests for Needs you.", text=[g("crafting_orders")]),
])
chapter("summons", "minecraft:goat_horn", [
    page("summons", "p1", "Call a worker to their post", [
        "Right-click a working building's plaque and press Summon.",
        "Its worker walks straight to their post, glowing through walls.",
        "A summons lasts about a minute and a half.",
    ], tip="Lost a worker? Summon them and follow the glow.",
        keys=[("key.use", "Open the plaque"), ("key.hearthstead.summon", "Call the settler you look at")],
        text=[g("summons")]),
])
chapter("attributes", "minecraft:experience_bottle", [
    page("attributes", "p1", "Eight attributes", [
        "Every settler has eight attributes, shown on the sheet out of 100.",
        "Newcomers arrive at 1 to 15 and grow only by doing the work.",
        "Small numbers already count: 15 gives about 40% of the full effect.",
        "Attributes make people different; they never pick the job for you.",
    ], tip="Hover an attribute on the Skills tab to see what it affects right now.",
        keys=[("key.use", "Open the settler sheet", SNEAK)], text=[g("attributes")]),
    page("attributes", "p2", "Attributes and jobs", [
        "Each job has a primary attribute (green) and a secondary one (gold) on the Skills tab.",
        "A strong primary cuts work time by up to 10%, the secondary by up to 5%.",
        "Wits makes every attribute grow up to 50% faster.",
        "The hire card names the jobs that suit a newcomer's two best attributes.",
    ], tip="Give each newcomer a job their hire card suggests.", text=[g("attributes", "2")]),
    page("attributes", "p3", "Strength, Stamina, Wits, Dexterity", [
        "Strength: harder melee hits, 4 more items per Courier trip, fewer axe hits per log.",
        "Stamina: tires more slowly, 2 extra hearts, keeps up to 80% pace when exhausted.",
        "Wits: faster growth, up to 25% more trade XP and faster research.",
        "Dexterity: tighter arrows, quicker swings, bigger fields, better catches and crafted goods.",
    ], tip="These are the effects at 99; a settler grows toward them over weeks."),
    page("attributes", "p4", "Spirit, Perception, Focus, Presence", [
        "Spirit: loses less morale, and Healers heal more.",
        "Perception: Hunters search wider, Archers shoot farther, harvests sometimes give 1 extra item.",
        "Focus: faster workbench batches, faster bow draws and spell casts.",
        "Presence: better persuasion in talks, more Coins for Traders, happier Tavern guests.",
    ], tip="Your best speaker (Mayor, Trader, Innkeeper or Guard) handles persuasion."),
])
chapter("trade_levels", "minecraft:iron_pickaxe", [
    page("trade_levels", "p1", "Trade levels", [
        "Each trade has a level from 1 to 10, earned by real work.",
        "A steady worker earns about 25 XP a day; level 10 takes about 44 days.",
        "Changing jobs starts the new trade at level 1 but keeps the old XP.",
    ], tip="Keep a good worker in one trade to build their level.", text=[g("trade_levels")]),
    page("trade_levels", "p2", "Which attribute helps", [
        "Each trade uses a primary and a secondary attribute.",
        "Each level above 1 makes the worker up to 2% faster, at most 18%.",
        "From level 5 the secondary attribute adds up to 8% more.",
    ], tip="Open More detail below for every trade's attributes.", text=[g("trade_levels", "2")]),
])
chapter("dagsverk", "minecraft:red_bed", [
    page("dagsverk", "p1", "Energy and Work Pace", [
        "Workers have Energy and a visible Work Pace, not a hidden quota.",
        "Tired workers keep working, only more slowly.",
        "The settler sheet shows Energy and the current pace.",
    ], tip="A slow worker is usually a tired one: check their Energy first.", text=[g("dagsverk")]),
    page("dagsverk", "p2", "Rest and Stamina", [
        "Rest and the daily routine restore Energy.",
        "Stamina decides how much pace a tired worker keeps.",
        "Missing tools and blocked routes are reported separately.",
    ], tip="Good beds and a Tavern evening keep the whole village moving.", text=[g("dagsverk", "2")]),
])
chapter("recruiting", "minecraft:emerald", [
    page("recruiting", "p1", "Recruit travelers", [
        "Travelers come once you have a valid Tavern, a free bed, good morale and food.",
        "You need 8 ready meals per resident at the Banner, counting the newcomer.",
        "Open Banner, People, then Traveler and press Recruit Traveler.",
        "A recruit costs 4 to 8 Coins, charged only if recruiting succeeds.",
    ], tip="Build the spare bed before the traveler arrives.", text=[g("recruiting")],
        journey=["fj_440_recruitment_window_starts", "fj_450_traveler_arrives", "fj_460_admit_traveler",
                 "fj_552_add_fifth_bed", "fj_553_second_recruitment_window", "fj_554_second_traveler_arrives",
                 "fj_555_admit_fifth_settler", "fj_400_unlock_home", "fj_410_link_first_home"]),
])
chapter("tavern", "hearthstead:ale", [
    page("tavern", "p1", "Build a Tavern", [
        "Learn Hospitality & Trade in the Tech Tree.",
        "Build it with a bell, 2 chests or barrels, an Ale Tap, a door, 3 lights and 36 floor.",
        "You need a Tavern to recruit travelers and to be ready for the first raid.",
        "Hire an Innkeeper to brew ale and serve meals.",
    ], tip="Right-click the Tavern's plaque for its live checklist.",
        items=["minecraft:bell", "hearthstead:ale_tap", "minecraft:barrel", "hearthstead:innkeeper_emblem"],
        text=[g("tavern")], journey=["fj_420_unlock_tavern", "fj_430_link_tavern"]),
    page("tavern", "p2", "Tavern evenings", [
        "In the evening, free residents walk to the Tavern and take a seat.",
        "A seat is a wooden chair facing a table, or a stair facing a fence with a pressure plate.",
        "One guest plays as the bard; eating while music plays gives +5 morale.",
    ], tip="Put three seats around a table so the bard has an audience.", text=[g("tavern", "2")]),
])
chapter("watch", "hearthstead:guard_emblem", [
    page("watch", "p1", "Arm your Guards", [
        "New Guards and Archers start without weapon or armour.",
        "Put a weapon in Warehouse storage; a Courier carries it to them.",
        "Open Guard Orders on the settler sheet: Hold Here, Defend Banner or Patrol.",
        "An Archer at a linked Watchtower can take a Tower Post.",
    ], tip="Stock a sword, a bow and 8 arrows in the Warehouse before you hire.",
        items=["hearthstead:guard_emblem", "hearthstead:archer_emblem", "minecraft:iron_sword", "minecraft:bow",
               "minecraft:arrow"],
        image="defense_page", caption="Banner, Defense page", text=[g("watch")],
        journey=["fj_500_unlock_first_watch", "fj_510_link_barracks", "fj_520_staff_barracks",
                 "fj_530_guard_requests_weapon", "fj_540_equip_guard", "fj_550_set_guard_order",
                 "fj_551_unlock_arm_the_watch", "fj_556_link_watchtower", "fj_557_staff_watchtower",
                 "fj_558_archer_requests_bow", "fj_559_equip_archer", "fj_559a_supply_archer_ammunition",
                 "fj_559b_set_tower_post"]),
    page("watch", "p2", "Ranks and the captain", [
        "Ranks are earned by patrols and real combat, never chosen.",
        "Rank armour must exist in storage before it can be worn.",
        "The highest-ranked living defender becomes your captain.",
    ], tip="Let the same Guards fight every raid so they rank up.", text=[g("watch", "2")]),
])
chapter("threat", "minecraft:crossbow", [
    page("threat", "p1", "Get ready for the first raid", [
        "Besides the Mayor you need a Lumberer, a Courier, a Farmer or Fisher, a Guard and an Archer.",
        "Also a bed for everyone, a Barracks with 2 beds, a Watchtower and 8 meals per settler.",
        "In Journey press Check Readiness, then Declare Ready.",
        "A warning one dusk earlier names the night, direction and objective.",
    ], tip="Check Readiness lists everything still missing.", text=[g("threat")],
        journey=["fj_560_declare_raid_ready", "fj_600_receive_first_warning"]),
    page("threat", "p2", "After the raid", [
        "Open Journey at the Banner to read the raid report.",
        "Replace lost equipment and restock the Banner's food.",
        "Learn First Raid Aftermath in the Tech Tree to open new upgrades.",
    ], tip="Raids now return on a schedule: keep defenders supplied.", text=[g("threat", "2")],
        journey=["fj_610_first_raid_resolved", "fj_620_review_aftermath"]),
])
chapter("raids", "minecraft:bell", [
    page("raids", "p1", "Raids", [
        "After Declare Ready, raids return on a schedule.",
        "A warning comes one dusk before every raid.",
        "After a win the next raid is two nights later; after a loss, three.",
        "Each victory pays Coins into the Banner: 8 for the first, then 4.",
    ], tip="The Banner screen shows the next raid night.", text=[g("raids")]),
    page("raids", "p2", "The alarm bell", [
        "Any bell inside the settlement is an alarm bell, the Tavern's too.",
        "Right-click it or shoot it with an arrow for two minutes of alarm.",
        "Civilians run home; defenders without orders go to the bell.",
    ], tip="Ring the bell the moment you see raiders.", keys=[("key.use", "Ring the bell")],
        items=["minecraft:bell"], text=[g("raids", "2")]),
])
chapter("saga", "minecraft:written_book", [
    page("saga", "p1", "The Saga", [
        "Every raid captain who reaches your walls has a name.",
        "A captain who gets away with loot earns a title, like the Grain-Thief.",
        "Captains hold a grudge against the settler who hurt them most.",
    ], tip="Check the raid report to see which captain you faced.", text=[g("saga")]),
])

# ---- index
GROUPS = [
    ("start", "Start here", ["start_here"]),
    ("items", "Items & Tools", ["items"]),
    ("settlement", "Your settlement", ["founding", "coins", "mayor", "plaque", "tech_tree", "day", "housing_limits", "food_overflow",
                               "research"]),
    ("workers", "Workers", ["jobs", "work_zones", "logistics", "crafting_orders", "summons", "attributes",
                            "attribute_effects", "jobs_directory", "trade_levels", "dagsverk", "gear_tiers", "quality"]),
    ("building", "Building", ["builder", "work_yards", "buildings_directory"]),
    ("people", "People and trade", ["recruiting", "tavern", "conversation", "realm_map", "world_events",
                                   "visitor_departure", "events_directory"]),
    ("defence", "Defence", ["watch", "command", "battle_roles", "patrols", "finisher_revive", "threat", "raids",
                            "parley", "hero_captain", "weapons", "saga"]),
    ("techtree", "Tech Tree", ["tech_branches"]),
    ("settings", "Settings", ["options"]),
]
HINTS = {
    "hearthstead:builders_plan": "builder.hut",
    "hearthstead:survey_rod": "builder.designs",
    "hearthstead:resource_scroll": "builder.sites",
    "hearthstead:guard_emblem": "watch.p1",
    "hearthstead:archer_emblem": "watch.p1",
    "hearthstead:work_scepter": "work_zones.p1",
    "hearthstead:spearman_emblem": "battle_roles.front",
    "hearthstead:longswordsman_emblem": "battle_roles.front",
    "hearthstead:healer_emblem": "battle_roles.support",
    "hearthstead:rune_mage_emblem": "battle_roles.support",
}
# Journey steps explained by a page that does not list them itself.
JOURNEY = {
    "fj_300_unlock_farmhouse": "tech_tree.p1",
    "fj_310_link_farmhouse": "plaque.p1",
    "fj_320_staff_farmhouse": "jobs.p1",
    "fj_340_farmer_requests_hoe": "jobs.p1",
    "fj_350_equip_farmer": "jobs.p1",
    "fj_360_supply_first_seed": "work_zones.p2",
    "fj_370_farmhouse_stores_crop": "logistics.p1",
}
# Pictures for chapters written by Codex (their files stay theirs; pictures are this lane's).
# NOTE tech_tree.png: refresh when the tech-tree lane's new graph screen lands.
PAGE_IMAGES = {
    "builder.hut": ("builders_plan", "The Builder's Plan"),
    "builder.designs": ("ghost", "A planned building, shown as a ghost"),
    "command.roles": ("command_dots", "Hold the key: dots show where they will stand"),
    "patrols.watch": ("patrol_ui", "Patrol orders"),
    "finisher_revive.finish": ("finisher_glow", "A finishable enemy glows red"),
    "conversation.talk": ("talk", "Talking to a visitor"),
    "parley.talk": ("parley", "A raid captain comes to talk"),
    "realm_map.locate": ("realm_map", "Hover a figure on the realm map"),
}

UI = {
    "hearthstead.guide.ui.search": "Search…",
    "hearthstead.guide.ui.back": "Back",
    "hearthstead.guide.ui.next": "Next",
    "hearthstead.guide.ui.try_it": "Try it",
    "hearthstead.guide.ui.more": "More detail",
    "hearthstead.guide.ui.less": "Less detail",
    "hearthstead.guide.ui.hold": "Hold",
    "hearthstead.guide.ui.unbound": "Not bound",
    "hearthstead.guide.ui.no_results": "No matches",
    "hearthstead.guide.ui.empty": "The handbook could not be loaded.",
    "hearthstead.guide.ui.learn_more": "Learn more in the Handbook",
    "hearthstead.guide.ui.hint.title": "New in your Handbook",
    "hearthstead.guide.ui.hint.body": "Press %s to read: %s",
    "hearthstead.guide.ui.hint.body_unbound": "Right-click the Handbook to read: %s",
    "key.hearthstead.handbook": "Open the Handbook",
    "hearthstead.guide.ui.get": "How to get it",
    "hearthstead.guide.ui.unlocked_by": "Unlocked by: %s (Tech Tree)",
    "hearthstead.guide.ui.unlocked_by_done": "Unlocked by: %s (learned)",
    "hearthstead.guide.ui.book_position": "Page %s of %s in the whole book",
    "hearthstead.guide.ui.use": "How to use",
    "hearthstead.guide.ui.no_recipe": "Recipe not available in this world.",
    "hearthstead.howto.shift": "Hold [Shift] for how to use",
    "hearthstead.howto.handbook": "Press %s while holding it to open its Handbook page",
}

# Tip links ("Try it" -> another page), for any chapter's page. Items & Tools pages link back themselves.
LINKS = {
    "start_here.plaque": "items.plaque", "start_here.hire": "items.emblems", "start_here.zone": "items.work_scepter",
    "start_here.banner": "items.banner", "start_here.mayor": "items.coins", "founding.p1": "items.banner",
    "coins.p1": "items.coins", "plaque.p1": "items.plaque", "work_zones.p1": "items.work_scepter",
    "jobs.p1": "items.emblems", "watch.p1": "items.weapons", "tavern.p1": "items.workplace",
    "raids.p1": "items.seals", "builder.hut": "items.builders_plan", "builder.designs": "items.survey_rod",
    "builder.sites": "items.resource_scroll", "patrols.watch": "items.patrol_map",
    "battle_roles.front": "items.weapons", "battle_roles.support": "items.battle_supplies",
    "quality.grades": "items.goods",
}

def main():
    import items_catalog
    catalog, item_pages = items_catalog.build(L, page)
    chapter("items", "hearthstead:work_scepter", item_pages, title="Items & Tools")
    import reference_pages
    for cid, (icon, title, pages) in reference_pages.build(L, page, {}).items():
        chapter(cid, icon, pages, title=title)
    try:
        with open(os.path.join(HB, "items.json"), encoding="utf-8") as f:
            prev = json.load(f)
        for fid, fam in prev.get("families", {}).items():
            if fid not in catalog["families"]:
                print("items: keeping hand-added family", fid)
                catalog["families"][fid] = fam
        for iid, fid in prev.get("items", {}).items():
            catalog["items"].setdefault(iid, fid)
    except FileNotFoundError:
        pass
    with open(os.path.join(HB, "items.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(catalog, f, indent=2, ensure_ascii=False)
        f.write("\n")
    made = make_images()
    # Drop pictures whose texture could not be made.
    for c in CH.values():
        for p in c["pages"]:
            if "image" in p:
                name = p["image"]["texture"].split("/")[-1][:-4]
                if name not in made:
                    lang.pop(p["image"].get("caption"), None)
                    del p["image"]
    os.makedirs(os.path.join(HB, "chapters"), exist_ok=True)
    for cid, c in CH.items():
        with open(os.path.join(HB, "chapters", cid + ".json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(c, f, indent=2, ensure_ascii=False)
            f.write("\n")
    index = {"schema": 1, "groups": [], "hints": HINTS, "journey": JOURNEY, "links": LINKS, "images": {}}
    old = {}
    try:
        with open(os.path.join(HB, "chapters.json"), encoding="utf-8") as f:
            old = json.load(f)
    except Exception:
        pass
    old_groups = {g["id"]: g for g in old.get("groups", [])}
    known = {c for _, _, ids in GROUPS for c in ids}
    for gid, title, ids in GROUPS:
        extra = [c for c in old_groups.get(gid, {}).get("chapters", []) if c not in known]
        if extra:
            print("index: keeping hand-added chapters", gid, extra)
        index["groups"].append({"id": gid, "title": L("hearthstead.guide.group." + gid, title),
                                "chapters": ids + extra})
    for g in old.get("groups", []):
        if g["id"] not in {x[0] for x in GROUPS}:
            print("index: keeping hand-added group", g["id"])
            index["groups"].append(g)
    for field in ("hints", "journey", "links"):
        for k, v in old.get(field, {}).items():
            index[field].setdefault(k, v)
    for pid, (name, cap) in PAGE_IMAGES.items():
        if name in made:
            o = img(name, L("hearthstead.guide.ui.caption." + pid.replace(".", "_"), cap))
            index["images"][pid] = o
    with open(os.path.join(HB, "chapters.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(index, f, indent=2, ensure_ascii=False)
        f.write("\n")
    lang.update(UI)
    insert_lang()
    total = sum(os.path.getsize(os.path.join(TEX, n)) for n in os.listdir(TEX))
    print("chapters", len(CH), "keys", len(lang), "images", len(made), "tex bytes", total)

# Generated keys whose text another lane now owns (targeted grants), kept as they are.
FOREIGN_KEYS = {
    "hearthstead.guide.ref.buildings_directory.fishery.text",  # Codex T29, real basin/water rules
}


def insert_lang():
    with open(LANG, encoding="utf-8", newline="") as f:
        text = f.read()
    lines = text.split("\n")
    keyline = re.compile(r'^\s*"((?:[^"\\]|\\.)*)"\s*:')
    index = {}
    for i, line in enumerate(lines):
        m = keyline.match(line)
        if m:
            index[json.loads('"' + m.group(1) + '"')] = i
    manifest = os.path.join(os.path.dirname(os.path.abspath(__file__)), "generated_keys.txt")
    previous = set()
    if os.path.exists(manifest):
        previous = set(open(manifest, encoding="utf-8").read().split())
    stale = [i for k, i in index.items() if k in previous and k not in lang]
    for i in sorted(stale, reverse=True):
        del lines[i]
    if stale:
        print("lang: removed", len(stale), "stale lines")
        index = {}
        for i, line in enumerate(lines):
            m = keyline.match(line)
            if m:
                index[json.loads('"' + m.group(1) + '"')] = i
    anchor = index["hearthstead.guide.title"]
    new = []
    for k in FOREIGN_KEYS:
        if k in index:
            lang.pop(k, None)  # another lane owns this value now: never overwrite it
    for k, v in lang.items():
        line = '  %s: %s,' % (json.dumps(k, ensure_ascii=False), json.dumps(v, ensure_ascii=False))
        if k in index:
            old = lines[index[k]]
            if not old.rstrip().endswith(","):
                line = line[:-1]
            lines[index[k]] = line
        else:
            new.append(line)
    lines[anchor + 1:anchor + 1] = new
    out = "\n".join(lines)
    json.loads(out)  # still valid JSON
    with open(LANG, "w", encoding="utf-8", newline="") as f:
        f.write(out)
    with open(manifest, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(sorted(lang)) + "\n")
    print("lang: inserted", len(new), "updated", len(lang) - len(new))

if __name__ == "__main__":
    main()
