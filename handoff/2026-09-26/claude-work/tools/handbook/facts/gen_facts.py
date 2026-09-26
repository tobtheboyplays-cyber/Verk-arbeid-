"""Regenerates the handbook fact files from the mod's code and data.

Read-only on the repo. Enum constants, prices, requirements, key defaults,
config defaults and tech-tree data are PARSED from source every run; only the
short player-facing sentences are hand-written (tables below, keyed by the
exact code id). Any enum constant without hand text is reported as MISSING.

    python gen_facts.py
"""
import json
import os
import re
import sys

REPO = 'C:/Users/tobia/Hearthstead-Claude/Verk-arbeid-/hearthstead-neoforge/src/main/'
JAVA = REPO + 'java/com/hearthstead/'
DATA = REPO + 'resources/data/hearthstead/'
LANG = REPO + 'resources/assets/hearthstead/lang/en_us.json'
OUT = os.path.dirname(os.path.abspath(__file__))
MAX = 110
problems = []


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


lang = json.loads(read(LANG))


def item_name(item_id):
    """'minecraft:wheat_seeds' -> 'Wheat Seeds'; '#minecraft:logs' -> 'any Logs'."""
    tag = item_id.startswith('#')
    path = item_id.lstrip('#').split(':')[-1]
    words = ' '.join(w.capitalize() for w in path.split('_'))
    return ('any ' + words) if tag else words


MASS = {'Leather', 'Flint', 'String', 'Bread', 'Wheat', 'Seeds', 'Cobblestone', 'Paper', 'Lazuli', 'Logs',
        'Wool', 'Planks', 'Glass', 'Sugar', 'Gravel', 'Lead', 'Charcoal', 'Ale', 'Diamond', 'Banners'}


def count_item(n, item_id):
    """'2 Iron Ingots', '8 Wheat Seeds', '4 Torches'."""
    name = item_name(item_id)
    last = name.split(' ')[-1]
    if n != 1 and last == 'Bookshelf':
        name = name[:-1] + 'ves'
    elif n != 1 and last not in MASS and not last.endswith('s'):
        name += 'es' if last.endswith(('ch', 'sh', 'x')) else 's'
    if n == 1 and name.endswith('Banners'):
        name = name[:-1]
    return '%d %s' % (n, name)


def java_item(expr):
    """Items.WHEAT_SEEDS -> minecraft:wheat_seeds; ModItems.X.get() -> hearthstead:x."""
    m = re.match(r'Items\.(\w+)', expr)
    if m:
        return 'minecraft:' + m.group(1).lower()
    m = re.search(r'(?:ModItems|RoleItems)\.(\w+)', expr)
    if m:
        return 'hearthstead:' + m.group(1).lower()
    return expr


def enum_constants(src, enum_name):
    """Returns [(NAME, argtext)] for the constants of `enum enum_name`."""
    start = src.index('enum ' + enum_name)
    body = src[src.index('{', start) + 1:]
    body = re.sub(r'/\*.*?\*/', '', body, flags=re.S)
    body = re.sub(r'//[^\n]*', '', body)
    # constants end at the first ';' at nesting depth 0
    depth = 0
    for i, ch in enumerate(body):
        if ch in '({':
            depth += 1
        elif ch in ')}':
            depth -= 1
        elif ch == ';' and depth == 0:
            body = body[:i]
            break
    body = re.sub(r'//[^\n]*', '', body)
    body = re.sub(r'/\*.*?\*/', '', body, flags=re.S)
    out = []
    depth = 0
    cur = ''
    for ch in body:
        if ch in '({':
            depth += 1
        elif ch in ')}':
            depth -= 1
        if ch == ',' and depth == 0:
            out.append(cur)
            cur = ''
        else:
            cur += ch
    if cur.strip():
        out.append(cur)
    result = []
    for c in out:
        c = c.strip()
        m = re.match(r'(\w+)\s*(?:\((.*)\))?\s*$', c, re.S)
        if m:
            result.append((m.group(1), m.group(2) or ''))
    return result


def check(rows, fields, label):
    for r in rows:
        for f in fields:
            v = r.get(f)
            if isinstance(v, str) and len(v) > MAX:
                problems.append('%s %s.%s is %d chars' % (label, r['id'], f, len(v)))


def write(name, rows):
    with open(os.path.join(OUT, name), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(rows, f, indent=2, ensure_ascii=False)
        f.write('\n')


# ============================================================ sources ===

prof_src = read(JAVA + 'entity/Profession.java')
PROFESSIONS = enum_constants(prof_src, 'Profession')
PROF_KEY = {n: re.search(r'"(\w+)"', a).group(1) for n, a in PROFESSIONS}

bt_src = read(JAVA + 'building/BuildingType.java')
BUILDINGS = enum_constants(bt_src, 'BuildingType')
yard_block = re.search(r'case ([A-Z_,\s]+)->\s*\n?\s*ValidationMode\.YARD_OR_ROOM', bt_src).group(1)
YARD_TYPES = {t.strip() for t in yard_block.split(',') if t.strip()}

emp_src = read(JAVA + 'settlement/Employment.java')
TRADES = dict(re.findall(r'TRADES\.put\(BuildingType\.(\w+), Profession\.(\w+)\)', emp_src))
WORKPLACE = {}
for b, p in TRADES.items():
    WORKPLACE.setdefault(p, []).append(b)

dn_src = read(JAVA + 'settlement/development/DevelopmentNode.java')
DEV_NODES = []
for name, args in enum_constants(dn_src, 'DevelopmentNode'):
    m = re.match(r'\s*(\d+),\s*"(\w+)",\s*Stage\.(\w+),\s*Branch\.(\w+)', args)
    blds = re.search(r'buildings\(([^)]*)\)', args)
    profs = re.search(r'professions\(([^)]*)\)', args)
    DEV_NODES.append(dict(
        enum=name, wire=int(m.group(1)), id=m.group(2), stage=m.group(3), branch=m.group(4),
        buildings=re.findall(r'BuildingType\.(\w+)', blds.group(1)) if blds else [],
        professions=re.findall(r'Profession\.(\w+)', profs.group(1)) if profs else []))
DEV_BY_ID = {n['id']: n for n in DEV_NODES}
DEV_BY_ENUM = {n['enum']: n for n in DEV_NODES}
EXTENDED_NODES = set(re.search(r'case (FORTIFICATION[^:]*?) -> true', dn_src, re.S).group(1).replace('\n', ' ').replace(' ', '').split(','))

cat_src = read(JAVA + 'settlement/development/JobEmblemCatalog.java')
CATALOG = {}
for m in re.finditer(r'entry\(Profession\.(\w+),\s*DevelopmentNode\.(\w+),?\s*([^;]*?)\)(?=,\s*(?://[^\n]*\n\s*)*entry\(|\s*\);)', cat_src, re.S):
    goods = []
    pairs = re.findall(r'(Items\.\w+),\s*(\d+)', m.group(3))
    for it, n in pairs:
        goods.append((java_item(it), int(n)))
    CATALOG[m.group(1)] = dict(node=m.group(2), goods=goods)
cp = re.search(r'coinPrice\(\) \{(.*?)\n        \}', cat_src, re.S).group(1)
COIN = {}
for m in re.finditer(r'case ([A-Z_, ]+) -> (\d+);', cp):
    for p in m.group(1).split(','):
        COIN[p.strip()] = int(m.group(2))
MARTIAL = set(re.findall(r'this == (\w+)', re.search(r'boolean martial\(\) \{(.*?)\}', prof_src, re.S).group(1)))


def coin_price(p):
    return COIN.get(p, 4 if p in MARTIAL else 2)


# v3 tech tree data + effect claims
TREE = json.loads(read(DATA + 'techtree/tree.json'))
ICONS = json.loads(read(DATA + 'techtree/icons.json'))
BRANCH_FILES = [b['id'] for b in TREE['branches']]
TIER_NAME = {t['id']: t['name'] for t in TREE['tiers']}
DATA_NODES = []
for bf in BRANCH_FILES:
    d = json.loads(read(DATA + 'techtree/%s.json' % bf))
    for n in d['nodes']:
        n['_branch'] = bf
        DATA_NODES.append(n)
DATA_BY_ID = {n['id']: n for n in DATA_NODES}

EFFECT_B, EFFECT_P = {}, {}
for ef in os.listdir(JAVA + 'settlement/techtree/effects'):
    src = read(JAVA + 'settlement/techtree/effects/' + ef)
    for block in re.split(r'\br\.node\(', src)[1:]:
        nid = re.match(r'"(\w+)"', block).group(1)
        chain = block.split(';', 1)[0]
        EFFECT_B.setdefault(nid, []).extend(re.findall(r'\.building\(BuildingType\.(\w+)\)', chain))
        EFFECT_P.setdefault(nid, []).extend(re.findall(r'\.profession\(Profession\.(\w+)\)', chain))
B_CLAIM, P_CLAIM = {}, {}
for nid, bs in EFFECT_B.items():
    for b in bs:
        B_CLAIM.setdefault(b, []).append(nid)
for nid, ps in EFFECT_P.items():
    for p in ps:
        P_CLAIM.setdefault(p, []).append(nid)

ru_src = read(JAVA + 'entity/combat/role/RoleUnlocks.java')
ROLE_UNLOCK = {}
for m in re.finditer(r'case ([A-Z_, ]+) -> DevelopmentNode\.(\w+);', ru_src):
    for b in m.group(1).split(','):
        ROLE_UNLOCK[b.strip()] = m.group(2)


def v3_plans(nid):
    """Build plans a v3 node opens (effect claims + unclaimed legacy lists)."""
    out = list(EFFECT_B.get(nid, []))
    n = DATA_BY_ID.get(nid)
    leg = n.get('legacy') if n else ('node:' + nid if nid in DEV_BY_ID else None)
    if leg and leg.startswith('node:'):
        for b in DEV_BY_ID[leg[5:]]['buildings']:
            if b not in B_CLAIM and b not in out:
                out.append(b)
    return out


def v3_emblems(nid):
    out = list(EFFECT_P.get(nid, []))
    n = DATA_BY_ID.get(nid)
    leg = n.get('legacy') if n else ('node:' + nid if nid in DEV_BY_ID else None)
    if leg and leg.startswith('node:'):
        for p in DEV_BY_ID[leg[5:]]['professions']:
            if p not in P_CLAIM and p not in out:
                out.append(p)
    return out


def unlock_node_for_building(b):
    if b in B_CLAIM:
        return B_CLAIM[b]
    for n in DEV_NODES:
        if b in n['buildings']:
            return [n['id']]
    if b in ROLE_UNLOCK:
        return [DEV_BY_ENUM[ROLE_UNLOCK[b]]['id']]
    return []


def unlock_node_for_profession(p):
    if p in P_CLAIM:
        return P_CLAIM[p]
    if p in CATALOG:
        return [DEV_BY_ENUM[CATALOG[p]['node']]['id']]
    return []


def node_name(nid):
    n = DATA_BY_ID.get(nid)
    return n['name'] if n else lang.get('hearthstead.development.node.%s.name' % nid, nid)


def bname(b):
    key = dict(BUILDINGS)[b]
    bid = re.search(r'"(\w+)"', key).group(1)
    return lang.get('hearthstead.building.' + bid, bid)


def pname(p):
    return lang.get('hearthstead.profession.' + PROF_KEY[p], PROF_KEY[p])


# ======================================================= professions ===

DOES = {
    'NONE': 'An unassigned settler waiting for a job.',
    'FARMER': 'Tills, sows and harvests the fields you mark, then stores the wheat.',
    'LUMBERER': 'Fells trees in its marked work area and brings the logs back to camp.',
    'GUARD': 'Patrols, answers the alarm and fights raiders; climbs the ranks up to Captain.',
    'COURIER': 'Carries goods between the Banner, the Warehouse and the workshops.',
    'BAKER': 'Bakes bread from wheat or flour at the Bakery.',
    'COOK': 'Cooks mushroom stew, baked potatoes and dried kelp into meals at the Kitchen.',
    'BUTCHER': 'Cooks raw beef, pork, mutton and chicken, and cures rabbit into Cured Hide.',
    'SMELTER': 'Smelts raw iron, copper and gold into ingots, and logs into charcoal.',
    'SMITH': 'Forges iron blooms into ingots and makes iron axes, pickaxes, hoes and swords.',
    'SAWYER': 'Saws logs into planks and Timber Beams at the Sawmill.',
    'CARPENTER': 'Makes sticks, barrels and ladders from planks and Timber Beams.',
    'MASON': 'Cuts cobblestone into stone and stone into stone bricks.',
    'FLETCHER': 'Makes arrows from flint or feathers, and bows from string.',
    'WEAVER': 'Spins string into wool, weaves Wool Bolts and sews banners.',
    'TANNER': 'Tans Cured Hide and rabbit hide into leather.',
    'MINER': 'Digs a shaft below the Mine and brings up stone and ore.',
    'INNKEEPER': 'Runs the Tavern: serves ale and food to seated guests and cuts the recruit price.',
    'SCHOLAR': 'Studies at the Architect\'s Study to advance your chosen research project.',
    'MILLER': 'Grinds wheat into flour and sugar cane into paper at the Mill.',
    'BREWER': 'Brews malt and ale from wheat at the Brewery.',
    'ARCHER': 'Shoots raiders from the Watchtower; ranks up to Master Archer with practice.',
    'ARMOURER': 'Makes leather and iron armour for your soldiers at the Armoury.',
    'HERDER': 'Shears sheep, feeds and breeds animals and collects eggs in the Pasture.',
    'FISHER': 'Fishes from the shore chair and lands fish and rare catches for food and trade.',
    'HUNTER': 'Hunts wild game near the Lodge and brings back meat, hides and feathers.',
    'MAYOR': 'Speaks for the village, sells job emblems and gives a boon from their best attribute.',
    'TRADER': 'Carries surplus goods from the Trading Post to visiting merchants and sells them for Coins.',
    'SPEARMAN': 'Battle role: fights from the second rank with long reach and braces against charges.',
    'LONGSWORDSMAN': 'Battle role: a two-handed swordsman who breaks shields and cuts through crowds.',
    'HEALER': 'Battle role: tends wounded soldiers in a fight and revives downed players.',
    'RUNE_MAGE': 'Battle role: spends rune charges on spells and wards that turn a fight.',
    'BUILDER': 'Builds blueprints, defense lines and Upgrade Orders from real delivered materials.',
}
ICON_OVERRIDE = {'NONE': 'hearthstead:settler_spawn_egg', 'MAYOR': 'minecraft:bell'}
HIRE_OVERRIDE = {
    'NONE': 'Every new settler starts unassigned. Firing a worker returns them here.',
    'MAYOR': 'A founder becomes Mayor when the Banner is raised. Appoint another from a settler\'s screen.',
}

professions = []
for name, args in PROFESSIONS:
    key = PROF_KEY[name]
    row = dict(id=name, key=key, name=pname(name), does=DOES.get(name, ''))
    if name not in DOES:
        problems.append('MISSING profession text: ' + name)
    if name in HIRE_OVERRIDE:
        row['hire'] = HIRE_OVERRIDE[name]
        row['unlocked_by'] = ''
    elif name in CATALOG:
        emblem = lang.get('item.hearthstead.job_emblem.' + key, key + ' Emblem')
        parts = ['%d Coin%s' % (coin_price(name), '' if coin_price(name) == 1 else 's')]
        parts += [count_item(n, i) for i, n in CATALOG[name]['goods']]
        row['hire'] = 'Buy the %s from the Mayor (%s).' % (emblem, ', '.join(parts))
        nodes = unlock_node_for_profession(name)
        row['unlocked_by'] = ', '.join(nodes)
        if len(row['hire']) > MAX:
            row['hire'] = 'Buy the %s from the Mayor: %s.' % (emblem, ', '.join(parts))
    else:
        row['hire'] = 'No emblem is sold for this job.'
        row['unlocked_by'] = ''
        problems.append('profession without catalogue entry: ' + name)
    row['building'] = ', '.join(dict((b, re.search(r'"(\w+)"', a).group(1)) for b, a in BUILDINGS)[b]
                                for b in WORKPLACE.get(name, [])) or 'none'
    if name in ICON_OVERRIDE:
        row['icon'] = ICON_OVERRIDE[name]
    else:
        row['icon'] = 'hearthstead:%s_emblem' % key
    tags = []
    if name in MARTIAL:
        tags.append('martial')
    if name == 'HEALER' or name in MARTIAL:
        tags.append('battlefield')
    if name in CATALOG and CATALOG[name]['node'] in EXTENDED_NODES:
        tags.append('extended_trade')
    if name in ('SPEARMAN', 'LONGSWORDSMAN', 'HEALER', 'RUNE_MAGE'):
        tags.append('battle_role')
    row['tags'] = tags
    professions.append(row)
check(professions, ['name', 'does', 'hire'], 'profession')
write('professions.json', professions)

# ========================================================== buildings ===

PURPOSE = {
    'HOUSE': 'A home: every bed you add lets one more settler live in the village.',
    'LODGING': 'A shared bunk house for up to 8 settlers.',
    'WAREHOUSE': 'The shared store Couriers fill and empty; higher levels manage more containers.',
    'ARCHITECTS_STUDY': 'The Scholar\'s study, where your chosen research project advances.',
    'SCHOOL': 'With Families & School learned, newcomers arrive one trade level higher (3 in all).',
    'FARMHOUSE': 'Base for the Farmer, who works the fields you mark with the Work Scepter.',
    'MILL': 'The Miller grinds wheat into flour and sugar cane into paper.',
    'BAKERY': 'The Baker turns wheat or flour into bread.',
    'KITCHEN': 'The Cook turns mushrooms, potatoes and kelp into ready meals.',
    'DINING_HALL': 'Settlers gather here at mealtime; it cuts the recruit price and the Mayor swap feast.',
    'PASTURE': 'A paddock you stock with animals; the Herder shears, breeds and collects eggs.',
    'BUTCHER': 'The Butcher cooks raw meat and cures rabbit into Cured Hide.',
    'FISHERY': 'A shore post where the Fisher fishes from a chair and racks the catch.',
    'HUNTERS_LODGE': 'The Hunter\'s base: game is hunted nearby and butchered here.',
    'BREWERY': 'The Brewer makes malt and ale from wheat.',
    'TAVERN': 'The Innkeeper serves ale and food here; travellers visit and can be recruited.',
    'WELL': 'While a valid Well House stands, every settler with a bed gets +2 morale.',
    'LUMBER_CAMP': 'Base for the Lumberer, who fells trees in the area you mark.',
    'SAWMILL': 'The Sawyer turns logs into planks and Timber Beams.',
    'CARPENTER': 'The Carpenter makes sticks, barrels and ladders.',
    'MINE': 'The Miner digs a shaft here for stone and ore.',
    'SMELTER': 'The Smelter turns raw ore into ingots and logs into charcoal.',
    'SMITHY': 'The Smith forges ingots and iron tools and swords.',
    'WEAVER': 'The Weaver makes wool, Wool Bolts and banners.',
    'INFIRMARY': 'The Healer\'s post; with the Infirmary node, wounded settlers inside heal steadily.',
    'BARRACKS': 'Home post of your Guards: they sleep, gear up and turn out from here.',
    'WATCHTOWER': 'Archers shoot from here, and Guards can be posted on the tower.',
    'TANNERY': 'The Tanner turns hides into leather.',
    'FLETCHER': 'The Fletcher makes arrows and bows.',
    'ARMOURY': 'The Armourer makes leather and iron armour for your soldiers.',
    'MASON': 'The Mason cuts cobblestone into stone and stone bricks.',
    'LIBRARY': 'Makes every research project 25% cheaper in materials.',
    'MARKET': 'While a valid Market stands, visiting merchants carry 6 more Coins.',
    'TRADING_POST': 'The Trader\'s post: surplus goods wait here to be sold to merchants.',
    'BUILDERS_HUT': 'The Builder\'s hut: materials are delivered here and the Builder builds your orders.',
    'PIKE_YARD': 'Drill yard and bunks for Spearmen.',
    'SWORD_HALL': 'Drill hall and bunks for Longswordsmen.',
    'RUNE_HALL': 'Where Rune Mages study and carve their runes.',
}
REQ_WORD = {'beds': 'bed', 'doors': 'door', 'lights': 'light', 'floor_space': 'floor', 'storage': 'storage',
            'ale_tap': 'ale tap', 'fishing_water': 'connected water', 'fishing_chair': 'fisher\'s chair',
            'fish_rack': 'fish rack', 'rod_barrel': 'barrel', 'hearth_fire': 'campfire', 'grindstone': 'grindstone',
            'oven': 'furnace/smoker', 'forge': 'furnace', 'sawbench': 'stonecutter', 'fletching': 'fletching table',
            'drill_target': 'hay bale', 'whetstone': 'grindstone', 'rune_table': 'enchanting table',
            'amethyst': 'amethyst block', 'dressed_stone': 'stone brick', 'stall': 'stall (barrel or scaffold)',
            'counter': 'scaffold or cartography table', 'hay': 'hay bale', 'water': 'water',
            'workbench': 'crafting table', 'lectern': 'lectern', 'bookshelf': 'bookshelf', 'smithing_table': 'smithing table',
            'composter': 'composter', 'smoker': 'smoker', 'cauldron': 'cauldron', 'brewing_stand': 'brewing stand',
            'bell': 'bell', 'loom': 'loom', 'anvil': 'anvil', 'ladder': 'ladder'}
PLURAL_SKIP = {'floor', 'storage', 'water', 'connected water'}


def req_text(args):
    parts = []
    for m in re.finditer(r'Requirement\.(\w+)\(\s*(?:"(\w+)",\s*)?(\d+)|new Requirement\("(\w+)",\s*(\d+)', args):
        if m.group(4):
            rid, n = m.group(4), int(m.group(5))
        else:
            kind = m.group(1)
            rid = {'beds': 'beds', 'doors': 'doors', 'lights': 'lights', 'floorSpace': 'floor_space',
                   'aleTap': 'ale_tap'}.get(kind, m.group(2))
            n = int(m.group(3))
        word = REQ_WORD.get(rid, rid.replace('_', ' '))
        if word.startswith('stall'):
            word = 'stalls (barrel or scaffold)' if n != 1 else word
        elif n != 1 and word not in PLURAL_SKIP and '/' not in word and ' or ' not in word:
            word = {'bookshelf': 'bookshelves'}.get(word, word + ('es' if word.endswith(('ch', 'sh', 's')) else 's'))
        parts.append('%d %s' % (n, word))
    return parts


# level summaries (hand-written from BuildingLevels.java; checked for presence below)
LEVELS = {
    'WAREHOUSE': 'L2 16 storage + ledger desk; L3 32 + 4 signs; L4 64 + 8 signs; L5 128 + 16 signs, 2 desks',
    'HOUSE': 'L2 2 lights, 1 storage, solid floor; L3 3 lights, 3 furnishing, 1 crafting table',
    'LODGING': 'L2 4 lights, 2 storage, solid floor; L3 6 beds, 6 lights',
    'TAVERN': 'L2 5 lights, 4 seats (stairs), solid floor',
    'BARRACKS': 'L2 4 beds, 4 lights, solid floor',
    'BUILDERS_HUT': 'L2 4 storage, 2 lights, 25 floor, solid floor; L3 8 storage, 4 lights, 36 floor, 2 crafting tables',
    'WATCHTOWER': 'L2 6 ladders, 6 lights',
}
lv_src = read(JAVA + 'building/BuildingLevels.java')
lv_body = lv_src[lv_src.index('switch (type)'):]
LEVELED = set(re.findall(r'case (\w+) ->', lv_body.split('default ->')[0]))
if LEVELED != set(LEVELS):
    problems.append('BuildingLevels cases %s differ from hand LEVELS %s' % (sorted(LEVELED), sorted(LEVELS)))

buildings = []
for name, args in BUILDINGS:
    m = re.match(r'\s*"(\w+)",\s*(\d+),\s*(\d+),\s*Items\.(\w+)', args)
    bid, res, workers, emblem = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4).lower()
    reqs = req_text(args)
    nodes = unlock_node_for_building(name)
    kind = 'yard' if name in YARD_TYPES else 'room'
    plan = ('Plan from ' + ' or '.join('%s (%s)' % (node_name(n), n) for n in nodes)) if nodes else 'No tech node opens this plan'
    row = dict(id=name, key=bid, name=bname(name), purpose=PURPOSE.get(name, ''),
               build=('%s: ' % ('Yard or room' if kind == 'yard' else 'Room')) + ', '.join(reqs),
               plan=plan, unlocked_by=', '.join(nodes), kind=kind,
               levels=LEVELS.get(name, ''), workers=workers, residents=res,
               trade=TRADES.get(name, 'NONE'), icon='minecraft:' + emblem)
    if name not in PURPOSE:
        problems.append('MISSING building text: ' + name)
    if not nodes:
        problems.append('building with no unlocking node: ' + name)
    buildings.append(row)
check(buildings, ['name', 'purpose', 'build', 'levels'], 'building')
write('buildings.json', buildings)

# ============================================================= events ===

we_src = read(JAVA + 'event/worldevent/WorldEventType.java')
WORLD_EVENTS = [(n, a) for n, a in enum_constants(we_src, 'WorldEventType')]
EVENT_TEXT = {
    'PEDDLER': ('Wandering Peddler', 'A colourful peddler with a pack llama sells rare goods for Coins by the Banner.',
                'Talk to him and buy what you need; he only takes Coins and stays about a day.'),
    'REFUGEES': ('Refugees', 'A family of three waits at the Banner and asks for shelter.',
                 'Take them in as settlers, give food, ask them to work, or send them away.'),
    'MINSTRELS': ('Travelling Minstrels', 'Three minstrels arrive at the Tavern at dusk.',
                  'Host a feast for a full set (costs Coins) or let them play for tips; listeners wake happier.'),
    'FIELD_FOX': ('Fox in the Fields', 'A fox sneaks into your fields and steals ripe crops.',
                  'Chase it off; your Farmer runs out and a Hunter can shoot it to drop the crop.'),
    'WOLF_PACK': ('Wolf Pack', 'Three wolves come for your livestock at night.',
                  'Guards fight them; the pack leaves after one kill or two losses and is gone by dawn.'),
    'WILD_BOAR': ('Wild Boar', 'A bad-tempered boar roots up crops in your fields.',
                  'Let Guards fight it or have a Hunter shoot it for extra meat.'),
    'TAVERN_BRAWL': ('Tavern Brawl', 'Two settlers fall out over ale and come to blows (no real damage).',
                     'Right-click a brawler to break it up, or a Guard will; each ending costs a little morale.'),
    'BRUTE_TOLL': ('Brute Toll', 'Three brutes walk up to the Banner and demand food.',
                   'Pay food or 10 Coins, talk them down, refuse or attack; answer within about 2 hours.'),
    'STRAY_DOG': ('Stray Dog', 'A scruffy dog hangs around the Banner.',
                  'Feed it and it becomes the village dog for good, or shoo it away.'),
    'CARAVAN': ('Passing Caravan', 'A trade caravan stops on the road near town.',
                'Barter in bulk, escort it to its far waypoint for Coins and goods, or wave it on.'),
    'RIVAL_ENVOY': ('Rival Envoy', 'An envoy of a neighbouring lord comes to the Banner.',
                    'Negotiate a trade pact, send a gift, insult him or dismiss him; the lord remembers.'),
}
EVENT_NEEDS = {
    'PEDDLER': '1+ settlers', 'REFUGEES': '2+ settlers', 'MINSTRELS': 'a Tavern and 2+ settlers',
    'FIELD_FOX': 'crop fields', 'WOLF_PACK': '2+ livestock animals', 'WILD_BOAR': 'crop fields',
    'TAVERN_BRAWL': 'a Tavern and 2 civilians near it', 'BRUTE_TOLL': '4+ settlers',
    'STRAY_DOG': '2+ settlers and no village dog yet', 'CARAVAN': '3+ settlers', 'RIVAL_ENVOY': '4+ settlers',
}
events = []
for name, args in WORLD_EVENTS:
    m = re.match(r'\s*"(\w+)",\s*([\d.]+),\s*(\d+),\s*([\d_]+),\s*([\d_]+),\s*([\d_]+),\s*(true|false)', args)
    eid, hostile = m.group(1), m.group(7) == 'true'
    t = EVENT_TEXT.get(name)
    if not t:
        problems.append('MISSING event text: ' + name)
        t = ('', '', '')
    events.append(dict(
        id=eid, enum=name, category='event', name=t[0], what=t[1], you=t[2],
        needs=EVENT_NEEDS.get(name, ''), hostile=hostile, min_gap_days=int(m.group(3)),
        enabled_by='features.worldEvents + events.%s' % eid + ('; not on Peaceful' if hostile else '')))

conv_ids = set()
for f in ['event/worldevent/WorldEventConversations.java', 'conversation/parley/RaidParley.java',
          'conversation/ConversationCommand.java', 'conversation/ConversationService.java']:
    conv_ids |= set(re.findall(r'"(hearthstead:\w+)"', read(JAVA + f)))
for fn in os.listdir(DATA + 'conversations'):
    conv_ids.add('hearthstead:' + fn[:-5])
conv_ids = {c for c in conv_ids if not c.startswith('hearthstead:traveller:')}
CONV_TEXT = {
    'hearthstead:raid_parley': ('Raid Parley', 'Before a recurring raid charges, its captain halts at the edge and waits to talk.',
                                'Pay tribute, persuade a truce, duel the captain alone, or refuse and fight.',
                                'features.conversations + conversations.raidParley'),
    'hearthstead:refugees': ('Refugees (talk)', 'The refugee family asks to be taken in.',
                             'Accept, ask them to work (a Charisma roll), give food, or decline.', 'features.conversations'),
    'hearthstead:minstrels': ('Minstrels (talk)', 'The minstrel troupe offers to play at your Tavern.',
                              'Host the feast for Coins, let them play for tips, or send them away.', 'features.conversations'),
    'hearthstead:brute_toll': ('Brute Toll (talk)', 'The brute chief demands a toll of food.',
                               'Pay food or Coins, try to talk them down (Intimidation), refuse, or attack.', 'features.conversations'),
    'hearthstead:peddler': ('Peddler (talk)', 'The peddler greets you and opens his wares.',
                            'Choose Trade to open the barter table, or leave.', 'features.conversations'),
    'hearthstead:stray_dog': ('Stray Dog (talk)', 'The stray dog looks up at you hopefully.',
                              'Feed it to adopt it, or shoo it away.', 'features.conversations'),
    'hearthstead:caravan': ('Caravan (talk)', 'The caravan master offers trade or asks for an escort.',
                            'Barter, agree to escort them past the woods, or let them pass.', 'features.conversations'),
    'hearthstead:rival_envoy': ('Rival Envoy (talk)', 'The rival lord\'s envoy states his master\'s business.',
                                'Negotiate a pact (a trade roll), befriend with a gift, insult, or dismiss.', 'features.conversations'),
    'hearthstead:traveller': ('Wandering Traveller', 'A data-driven sample talk: a traveller trades news for bread.',
                              'Only spawned by the admin command /hstalk traveller; not part of normal play.',
                              'features.conversations'),
    'hearthstead:barter_only': ('Barter Table', 'A bare barter screen opened directly on an NPC with trade stock.',
                                'Pick goods on both sides and accept the deal, or leave.', 'features.conversations'),
    'hearthstead:demo_peddler': ('Demo Peddler', 'A test peddler conversation for admins.',
                                 'Only spawned by the /hstalk admin command; not part of normal play.',
                                 'features.conversations'),
}
for cid in sorted(conv_ids):
    if cid.startswith('hearthstead:test_'):
        continue
    t = CONV_TEXT.get(cid)
    if not t:
        problems.append('MISSING conversation text: ' + cid)
        t = ('', '', '', 'features.conversations')
    events.append(dict(id=cid, category='conversation', name=t[0], what=t[1], you=t[2], enabled_by=t[3]))
OTHER = [
    dict(id='RaidDirector', category='other', name='Raids',
         what='After the first raid, a raid comes every 3-4 in-game days; the warning sounds at dusk the evening before.',
         you='Arm and post your Guards and Archers, keep the Banner stocked, and fight beside them.',
         enabled_by='raids.recurringRaidMinDays / raids.recurringRaidMaxDays'),
    dict(id='GoblinTheftDirector', category='other', name='Goblin Thief',
         what='Now and then a Goblin Thief sneaks in to pickpocket, then runs rather than fights.',
         you='Chase it down before it escapes; it sometimes drops goblin loot.',
         enabled_by='(always on; health via raids.goblinThiefBaseHealth)'),
    dict(id='EarlyCoinMerchant', category='other', name='Traveling Merchant',
         what='A merchant regularly walks to the Banner and buys your goods for Coins from a limited purse.',
         you='Sell goods to earn the Coins you spend on tech nodes, emblems and recruits.',
         enabled_by='economy.merchantPurseBase / merchantBuysCrafted'),
]
events += OTHER
check(events, ['name', 'what', 'you'], 'event')
write('events.json', events)

# ============================================================== nodes ===

STAGE_TIER = {'ROOT': 1, 'TRUNK': 1, 'AFTERMATH': 2, 'DOCTRINE': 2, 'SPECIALIZATION': 2}


def cost_text(n):
    parts = []
    if n.get('coins'):
        parts.append('%d Coin%s' % (n['coins'], '' if n['coins'] == 1 else 's'))
    for g in n.get('goods', []):
        parts.append(count_item(g['count'], ('#' + g['tag']) if g.get('tag') else g['item']))
    return ', '.join(parts) or 'free'


def first_sentence(text):
    parts = re.split(r'(?<=[.!?])\s', text.strip())
    s = parts[0]
    for nxt in parts[1:]:
        if len(s) >= 40:
            break
        s = s + ' ' + nxt
    if len(s) > MAX:
        s = s[:MAX - 3].rsplit(' ', 1)[0].rstrip(',;:') + '...'
    return s


nodes = []
for n in DATA_NODES:
    nid = n['id']
    plans = v3_plans(nid)
    emblems = v3_emblems(nid)
    bits = []
    if plans:
        bits.append('Plans: ' + ', '.join(bname(b) for b in plans))
    if emblems:
        bits.append('Emblems: ' + ', '.join(pname(p) for p in emblems))
    unlocks = '. '.join(bits) if bits else first_sentence(n['offers'])
    if len(unlocks) > MAX:
        unlocks = first_sentence(n['offers'])
    icon = n.get('icon') or ICONS['icons'].get(nid)
    icon_note = ''
    if not icon:
        # same order as TechTreeScreen.computeIcon
        profs = EFFECT_P.get(nid, []) + (DEV_BY_ID[n['legacy'][5:]]['professions']
                                          if (n.get('legacy') or '').startswith('node:') else [])
        items = [g['item'] for g in n.get('goods', []) if g.get('item')]
        if profs:
            icon = 'hearthstead:%s_emblem' % PROF_KEY[profs[0]]
        elif items:
            icon = items[0]
        else:
            icon = {'watch': 'minecraft:iron_sword', 'logistics': 'minecraft:chest', 'commons': 'minecraft:bread',
                    'craft': 'minecraft:iron_pickaxe'}.get(n['_branch'], 'minecraft:white_banner')
        icon_note = 'no icons.json entry; the screen falls back to this'
    leg = n.get('legacy') or ''
    enum = ''
    if leg.startswith('node:'):
        enum = 'DevelopmentNode.' + DEV_BY_ID[leg[5:]]['enum']
    elif leg.startswith('upgrade:'):
        enum = 'PostRaidUpgrade.' + leg[8:].upper()
    row = dict(id=nid, title_key='hearthstead.techtree.node.%s.name' % nid, name=n['name'],
               short=ICONS.get('short', {}).get(nid, ''),
               branch='%s, Ring %d %s' % (next(b['name'] for b in TREE['branches'] if b['id'] == n['_branch']),
                                          n['tier'], TIER_NAME[n['tier']]),
               branch_id=n['_branch'], tier=n['tier'], type=n['type'],
               unlocks=unlocks, offers=n['offers'], cost=cost_text(n), requires=n.get('requires', []),
               excludes=n.get('excludes', []), gate=n.get('gate_text', ''),
               study_days=n.get('study_days', 0), legacy=leg, enum=enum,
               legacy_title_key=('hearthstead.development.node.%s.name' % leg[5:]) if leg.startswith('node:') else '',
               extended_trade=bool(enum) and enum.split('.')[-1] in EXTENDED_NODES,
               source='data/hearthstead/techtree/%s.json' % n['_branch'], icon=icon)
    if icon_note:
        row['icon_note'] = icon_note
    nodes.append(row)
# DevelopmentNode constants with no v3 data node
for d in DEV_NODES:
    if d['id'] in DATA_BY_ID:
        continue
    nodes.append(dict(
        id=d['id'], title_key='hearthstead.development.node.%s.name' % d['id'],
        name=lang.get('hearthstead.development.node.%s.name' % d['id'], d['id']), short='',
        branch='Development (legacy) %s, %s' % (d['branch'].lower(), d['stage'].lower()),
        branch_id=d['branch'].lower(), tier=STAGE_TIER[d['stage']], type='legacy',
        unlocks=first_sentence(lang.get('hearthstead.development.node.%s.desc' % d['id'], '')),
        offers=lang.get('hearthstead.development.node.%s.desc' % d['id'], ''), cost='free',
        requires=[], excludes=[], gate='', study_days=0, legacy='node:' + d['id'],
        enum='DevelopmentNode.' + d['enum'], legacy_title_key='hearthstead.development.node.%s.name' % d['id'],
        extended_trade=False, source='settlement/development/DevelopmentNode.java', icon='hearthstead:hearth'))
check(nodes, ['name', 'unlocks'], 'node')
write('nodes.json', nodes)

# =============================================================== keys ===

KEY_DOES = {
    'key.hearthstead.command_knights': 'Order your Guards: tap = order at what you look at, hold = draw a line, Shift + key = back to posts.',
    'key.hearthstead.command_archers': 'Order your Archers: tap = target or spot, hold = draw a line, Shift + key = back to posts.',
    'key.hearthstead.command_spearmen': 'Order your Spearmen: tap = target or spot, hold = draw a line, Shift + key = back to posts.',
    'key.hearthstead.command_longswordsmen': 'Order your Longswordsmen: tap, hold to draw a line, Shift + key to send them back.',
    'key.hearthstead.command_mages': 'Order your Rune Mages: tap, hold to draw a line, Shift + key to send them back.',
    'key.hearthstead.command_healers': 'Order your Healers: tap, hold to draw a line, Shift + key to send them back.',
    'key.hearthstead.command_all': 'Order every soldier at once, the same way as the role keys.',
    'key.hearthstead.command_menu': 'Open the orders strip: hold fire / fire at will, all follow me, all back to posts (keys 1-3).',
    'key.hearthstead.summon': 'Look at any settler and press to call them to you.',
    'key.hearthstead.finisher': 'Execute a glowing, staggered enemy. While unbound, the Command Knights key (R) does it.',
    'key.hearthstead.handbook': 'Open the Handbook at the page a recent hint pointed to, or the last page you read.',
}
key_rows = []
ck = read(JAVA + 'client/command/CommandKeys.java')
for m in re.finditer(r'key\("(\w+)",\s*([\w.]+)(?:\.getValue\(\))?\)', ck):
    key_rows.append(('key.hearthstead.' + m.group(1), m.group(2), 'client/command/CommandKeys.java'))
for f in ['client/finisher/FinisherClientSetup.java', 'client/ui2/handbook/HandbookHints.java']:
    s = read(JAVA + f)
    for m in re.finditer(r'new KeyMapping\("([\w.]+)",\s*KeyConflictContext\.\w+,\s*InputConstants\.Type\.\w+,\s*([\w.]+)', s):
        key_rows.append((m.group(1), m.group(2), f))


def key_default(expr):
    if 'UNKNOWN' in expr:
        return 'unbound'
    return expr.split('GLFW_KEY_')[-1]


keys = []
for kid, expr, f in key_rows:
    keys.append(dict(id=kid, name=lang.get(kid, ''), default=key_default(expr), does=KEY_DOES.get(kid, ''),
                     category='Bannerhold', source=f))
    if kid not in KEY_DOES:
        problems.append('MISSING key text: ' + kid)
check(keys, ['does'], 'key')
write('keys.json', keys)

# ============================================================ options ===

CFG_FILES = [('server', 'HearthsteadServerConfig.java'), ('server', 'event/worldevent/WorldEventConfig.java'),
             ('server', 'entity/combat/captain/CaptainConfig.java'), ('server', 'conversation/ConversationConfig.java'),
             ('server', 'settlement/economy/EconomyConfig.java'), ('server', 'settlement/economy/QualityConfig.java'),
             ('server', 'settlement/techtree/TechTreeConfig.java'), ('server', 'entity/AttributeConfig.java'),
             ('client', 'HearthsteadClientConfig.java')]
PAT = re.compile(r'\.push\("(\w+)"\)|\.pop\(\)|\.define(?:InRange|Enum|InList)?\((\w+\.id\(\)|"\w+"),\s*([^,)]+)', re.S)


def resolve(expr, consts):
    expr = expr.strip()
    if expr in consts:
        expr = consts[expr].strip()
    m = re.match(r'com\.hearthstead\.([\w.]+)\.(\w+)\.(\w+)$', expr)
    if m:
        path = JAVA + m.group(1).replace('.', '/') + '/' + m.group(2) + '.java'
        c = re.search(r'static final \w+ %s = ([^;]+);' % m.group(3), read(path))
        if c:
            expr = c.group(1).strip()
    expr = re.sub(r'(?<=\d)[DF]$', '', expr)
    if re.match(r'^[\d_.]+$', expr):
        expr = expr.replace('_', '')
    if '.' in expr and not re.match(r'^[\d.]+$', expr):
        expr = expr.split('.')[-1]
    return expr


OPT_DOES = {
    'time.dayLengthMultiplier': 'How many times longer a day lasts than vanilla (2 = 40 real minutes).',
    'debug.workerWatchdog': 'Logs stuck or idle workers for debugging; never changes gameplay.',
    'hunting.huntingGrounds': 'While a Hunter works, wild game slowly returns around the Lodge.',
    'hunting.gameCap': 'Hunting grounds stop spawning once this many wild animals live near a Lodge.',
    'hunting.spawnIntervalSeconds': 'At most one animal returns per Lodge in this many seconds.',
    'hunting.minPlayerDistance': 'Game never appears closer than this many blocks to a player.',
    'hunting.villageCoreRadius': 'No game spawns within this many blocks of the Banner.',
    'hunting.butcherTableSeconds': 'Seconds a new Hunter needs to butcher a carcass at a Butchering Table.',
    'hunting.fieldDressingSeconds': 'Seconds to butcher on the Lodge floor without a Butchering Table.',
    'raids.recurringRaidMinDays': 'Fewest in-game days between one raid and the next.',
    'raids.recurringRaidMaxDays': 'Most in-game days between one raid and the next.',
    'raids.skirmisherBaseHealth': 'Health of an ordinary raid Skirmisher.',
    'raids.bruteBaseHealth': 'Health of an ordinary raid Brute.',
    'raids.goblinThiefBaseHealth': 'Health of a Goblin Thief.',
    'raids.enemyHealthMultiplier': 'Multiplies every raider\'s health: 0.5 halves fight length, 2 doubles it.',
    'combat.guardLightDamageMultiplier': 'Damage of a Guard\'s light slash and combo links.',
    'combat.guardHeavyDamageMultiplier': 'Damage of a Guard\'s heavy overhead chop (it also staggers).',
    'combat.guardFinisherDamageMultiplier': 'Damage of the third hit of a Guard\'s combo.',
    'combat.guardShieldBashDamageMultiplier': 'Damage of a Guard\'s shield bash (mostly an interrupt).',
    'combat.guardHeavyWindupTicks': 'Ticks from the start of a Guard heavy to its hit (20 ticks = 1 second).',
    'combat.guardLightCooldownTicks': 'Rest after a Guard\'s single light slash.',
    'combat.guardHeavyCooldownTicks': 'Rest after a Guard\'s heavy.',
    'combat.guardFinisherCooldownTicks': 'Rest after a Guard\'s combo finisher.',
    'combat.guardShieldBashCooldownTicks': 'Ticks between two shield bashes by the same Guard.',
    'combat.guardComboWindowTicks': 'Time a Guard has to reach the target again before a combo breaks.',
    'combat.raiderLightDamageMultiplier': 'Damage of a raider\'s light jab.',
    'combat.raiderHeavyDamageMultiplier': 'Damage of a raider\'s heavy smash, which staggers Guards.',
    'combat.raiderHeavyWindupTicks': 'Ticks from the start of a raider heavy to its hit.',
    'combat.raiderHeavyCooldownTicks': 'Rest after a raider heavy.',
    'combat.bruteHeavyChance': 'Chance a Brute swings its heavy instead of its quick club.',
    'combat.bruteClubDamageMultiplier': 'Damage of a Brute\'s club slam.',
    'combat.bruteSlamRadiusScale': 'Size of the shockwave when a Brute slams the ground.',
    'logistics.handCartPercent': 'Extra Courier load from the Hand Cart, in percent of the base trip.',
    'logistics.handCartRoughPercent': 'How much a hitched Hand Cart slows a Courier off-road, in percent.',
    'logistics.pavedRoadsPercent': 'Walking speed bonus on road blocks with Paved Roads, in percent.',
    'revive.enabled': 'In a raid at your settlement a lethal hit knocks you down; a teammate can revive you.',
    'revive.soloDowned': 'Also go down when no other player is near. Off: solo players die normally.',
    'revive.bleedOutSeconds': 'Seconds a downed player lasts before bleeding out.',
    'revive.reviveSeconds': 'Seconds a teammate holds use on you to revive you.',
    'revive.reviveHealthPercent': 'Health you get up with after a revive, in percent.',
    'revive.postRaidGraceSeconds': 'Seconds after a raid in which players still go down instead of dying.',
    'revive.raiderFinisherChance': 'Chance a nearby raider breaks off to finish a downed player.',
    'finisher.enabled': 'Executions: finish a glowing, staggered enemy with the Command Knights key (R).',
    'finisher.guardFinisherChance': 'Chance a Guard\'s finishing blow becomes a full execution.',
    'finisher.reach': 'How close you must be to start an execution, in blocks.',
    'features.builder': 'Off: Builders, build orders and the Builder\'s Plan stop working.',
    'features.worldEvents': 'Off: no world events (peddler, refugees, wolves and the rest) start.',
    'features.battleRoles': 'Off: Spearmen, Longswordsmen, Healers and Rune Mages cannot be hired or fight.',
    'features.guardCommands': 'Off: the command keys and summoning settlers stop working.',
    'features.warehouseLevels': 'Off: no Warehouse levels, sorting or warehouse index.',
    'features.gearTiers': 'Off: no settler gear tiers or tiered armour looks.',
    'features.characterSkins': 'Off: settlers, visitors and raiders use the old plain skins.',
    'features.logisticsUpgrades': 'Off: no sack tiers, hand carts or road speed bonus.',
    'features.conversations': 'Off: no talk screens; visitors answer in chat and raids never parley.',
    'features.extendedTrades': 'Off: hides the 4 specialization nodes and 15 trade emblems. Existing workers keep going.',
    'features.livingVillage': 'Off: settlers stop greeting, barking lines, sheltering from rain and cheering.',
    'features.patrolRoutes': 'Off: Guard squads walk ordinary rounds instead of your Patrol Map routes.',
    'features.captainWeapons': 'Off: short sword, double axe, halberd and warhammer lose their special traits.',
    'events.frequency': 'How often small events happen (1 = about every other day, 0 = never).',
    'captain.enabled': 'Off: no hero Captain kit or specials (the title stays).',
    'captain.renderScale': 'How big the Captain is drawn; the hitbox stays normal.',
    'captain.healthBonus': 'Extra max health for the Captain.',
    'captain.armorBonus': 'Extra armour points for the Captain.',
    'captain.damageBonus': 'Extra attack damage for the Captain.',
    'captain.knockbackResistance': 'How well the Captain resists knockback (0 to 1).',
    'captain.extraLoadouts': 'Halberd and Warhammer loadouts for the Captain (hidden by default).',
    'conversations.raidParley': 'Raid captains halt before the charge so you can talk, pay, duel or refuse.',
    'conversations.parleySeconds': 'Seconds a raid captain waits for someone to talk before charging.',
    'conversations.talkTimeoutSeconds': 'An open conversation with no reply closes after this many seconds.',
    'conversations.encounters': 'Walking up to a visitor pulls you into the talk. Off: right-click to talk.',
    'economy.craftTimeMultiplier': 'Every crafting batch takes this many times its base time.',
    'economy.selfFetch': 'A crafter with an empty bench walks to the Warehouse to fetch its own inputs.',
    'economy.selfFetchAfterSeconds': 'How long a bench stands empty before its crafter fetches for itself.',
    'economy.selfFetchRadius': 'A crafter only fetches from a Warehouse within this many blocks.',
    'economy.selfFetchMaxItems': 'Most items a crafter carries back in one fetch trip.',
    'economy.workshopUpkeep': 'Idle crafters tidy, mend tools and study their trade instead of wandering.',
    'economy.upkeepSeconds': 'Length of one upkeep session (gives 1 trade XP).',
    'economy.starvingWorkshopsFirst': 'Couriers restock workshops that cannot work at all before the rest.',
    'economy.courierDepositBundle': 'Items a Courier puts into a chest per stow motion.',
    'economy.hungerDrainMultiplier': 'How fast settlers get hungry. Lower it if your village starves.',
    'economy.merchantBuysCrafted': 'The visiting merchant also buys crafted goods (bread, leather, ale, tools...).',
    'economy.merchantPurseBase': 'Coins the merchant brings each visit before the village-size bonus.',
    'economy.merchantPursePerFiveSettlers': 'Extra Coins in the merchant\'s purse for every 5 settlers.',
    'economy.merchantPurseCap': 'Most Coins the merchant ever brings on one visit.',
    'quality.craftedQuality': 'Crafted goods roll a grade from Basic to Legendary. Off: everything is Basic.',
    'quality.statBonuses': 'Better grades give more durability, damage, toughness and saturation.',
    'quality.merchantPremiums': 'The merchant pays more for higher-grade goods (up to 5 Coins a sale).',
    'techtree.enabled': 'The v3 tech tree at the Banner. Off: the old Development screen; learned nodes stay.',
    'techtree.studyTimeScale': 'Multiplier on study time for Town+ nodes (0 = learning is instant).',
    'techtree.gateCrafting': 'Recipes listed by a tech node cannot be crafted until you learn it.',
    'attributes.effectStrength': 'How strongly settler attributes act in play (0 = off, 1 = as designed, max 2).',
    'pickupNotices.enabled': 'Show a short "+12 Oak Log" notice when items enter your inventory.',
    'pickupNotices.maxRows': 'Most pickup notice rows shown at once.',
    'pickupNotices.displaySeconds': 'Seconds a pickup notice stays before fading.',
    'pickupNotices.position': 'Screen corner the pickup notices stack from.',
    'motion.enabled': 'Smoother settler animation. Off plays the original keyframes.',
    'motion.secondaryMotion': 'Small follow-through: knees, elbows, turn lean, bag and tool lag.',
    'combat.cameraShake': 'A short camera shake when a Brute slams the ground near you.',
    'finisher.glowParticles': 'Red sparks drift off a finishable enemy\'s glowing torso.',
    'finisher.firstPersonArms': 'In first person, your weapon follows the execution move.',
    'conversations.cameraFocus': 'Ease the camera onto the person you talk to.',
    'conversations.encounterCinematics': 'A short, skippable camera swoop and name card when you walk up to a visitor.',
    'ambient.barks': 'Short lines over settlers\' heads (greetings, weather, work, a won raid).',
    'ambient.gestures': 'Small gestures: waves, nods, cheers, shivers.',
    'ambient.worldLife': 'Chimney smoke, butterflies and birdsong around the village.',
    'particles.enabled': 'Show Bannerhold\'s own particles.',
    'particles.intensity': 'Particle amount: 0 subtle, 1 normal, 2 rich.',
    'audio.voiceVolume': 'Volume of the gibberish character voices (0 = silent).',
    'audio.ambienceBeds': 'Quiet looping village ambience around your settlement.',
    'audio.bannerholdMusic': 'Bannerhold\'s own soundtrack through the Music slider; off = vanilla music only.',
    'hud.healthCounter': 'Health counter above the hotbar for what you look at: lookAt = on, off = never.',
}
options = []
we_types = [re.search(r'"(\w+)"', a).group(1) for n, a in WORLD_EVENTS]
for side, f in CFG_FILES:
    src = read(JAVA + f)
    consts = dict(re.findall(r'static final [\w.]+ (\w+) = ([^;]+);', src))
    section = None
    for m in PAT.finditer(src):
        if m.group(1):
            section = m.group(1)
            continue
        if m.group(0).startswith('.pop'):
            continue
        raw = m.group(2)
        if raw.endswith('.id()'):
            for eid in we_types:
                oid = 'events.' + eid
                options.append(dict(id=oid, side=side, section=section, default='true',
                                    does='Off: the %s event never happens.' % eid.replace('_', ' '),
                                    file=('hearthstead-%s.toml' % side), source=f))
            continue
        name = raw.strip('"')
        oid = '%s.%s' % (section, name)
        options.append(dict(id=oid, side=side, section=section, default=resolve(m.group(3), consts),
                            does=OPT_DOES.get(oid if side == 'server' or oid not in OPT_DOES else oid, ''),
                            file=('hearthstead-%s.toml' % side), source=f))
# client and server share some section names (combat, finisher, conversations):
# the dict is keyed by id, so check both sides got their own text
seen = {}
for o in options:
    if o['id'] in seen and seen[o['id']] == o['side']:
        problems.append('duplicate option id on one side: ' + o['id'])
    seen[o['id']] = o['side']
CLIENT_DOES = {k: v for k, v in OPT_DOES.items() if k in (
    'combat.cameraShake', 'finisher.glowParticles', 'finisher.firstPersonArms',
    'conversations.cameraFocus', 'conversations.encounterCinematics')}
for o in options:
    if o['side'] == 'client' and o['id'] in CLIENT_DOES:
        o['does'] = CLIENT_DOES[o['id']]
    if not o['does']:
        problems.append('MISSING option text: %s (%s)' % (o['id'], o['side']))
check(options, ['does'], 'option')
write('options.json', options)

# ============================================================ summary ===

counts = dict(professions=len(professions), buildings=len(buildings),
              events=sum(1 for e in events if e['category'] == 'event'),
              conversations=sum(1 for e in events if e['category'] == 'conversation'),
              other_events=sum(1 for e in events if e['category'] == 'other'),
              nodes=len(nodes), keys=len(keys), options=len(options),
              options_server=sum(1 for o in options if o['side'] == 'server'),
              options_client=sum(1 for o in options if o['side'] == 'client'),
              enum_Profession=len(PROFESSIONS), enum_BuildingType=len(BUILDINGS),
              enum_WorldEventType=len(WORLD_EVENTS), enum_DevelopmentNode=len(DEV_NODES),
              data_tech_nodes=len(DATA_NODES), features_switches=sum(1 for o in options if o['section'] == 'features'))
print(json.dumps(counts, indent=1))
print('PROBLEMS:' if problems else 'no problems')
for p in problems:
    print('  ' + p)
