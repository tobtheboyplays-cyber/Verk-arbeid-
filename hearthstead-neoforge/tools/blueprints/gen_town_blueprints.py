#!/usr/bin/env python3
"""Generate the Bannerhold blueprint catalogue in the owner's town palette.

    python tools/blueprints/gen_town_blueprints.py            # write nbt + json + report
    python tools/blueprints/gen_town_blueprints.py --only house_cottage

Writes src/main/resources/data/hearthstead/{structure/blueprints,blueprints}/
and tools/blueprints/out/report.json (+ one cells JSON per blueprint for the
Blender preview renderer). Every building blueprint is checked offline against
its BuildingType L1 checklist (scanner.py, a port of RoomScanner + the plaque
survey) and for walkability (door -> every bed, container, work block and the
upper floor). A failure aborts the run.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bplib import BP, DIRS, OPP, HORIZ, save, short, connect_pass  # noqa: E402
import kit  # noqa: E402
from kit import building, furnish, lights  # noqa: E402
import scanner  # noqa: E402
import styles  # noqa: E402

REPO = os.path.dirname(os.path.dirname(HERE))
RES = os.path.join(REPO, 'src/main/resources')
OUT = os.path.join(HERE, 'out')

CATALOG = []


def bp_(bid, name, btype, variant, category, kind='building', requires='builders_hut', style='timber', segment=None):
    return BP(bid, name=name, building_type=btype, variant=variant, category=category, kind=kind,
              requires=requires, style=style, segment=segment)


def register(fn):
    CATALOG.append(fn)
    return fn


# ============================================================ decorations ===

def log_pile(bp, x0, z0, w=2, d=3, hgt=2):
    for x in range(x0, x0 + w):
        for z in range(z0, z0 + d):
            bp.set(x, 0, z, 'cobblestone')
            for y in range(1, 1 + hgt):
                bp.set(x, y, z, 'oak_log', axis='z')


def hay_stack(bp, x, z, n=2):
    bp.set(x, 0, z, 'cobblestone')
    for y in range(1, 1 + n):
        bp.set(x, y, z, 'hay_block', axis='y')


def paddock(bp, x0, z0, x1, z1, gate=None):
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if x in (x0, x1) or z in (z0, z1):
                if gate and (x, z) == gate:
                    bp.set(x, 1, z, 'oak_fence_gate', facing='south', open='false', in_wall='false', powered='false')
                elif (x, 1, z) not in bp.c:
                    bp.set(x, 1, z, 'oak_fence')


def sails(bp, x, yc, zc, arm=3, side='east'):
    """A windmill hub on a gable end with four fence arms and wool cloth."""
    dx = 1 if side == 'east' else -1
    bp.set(x, yc, zc, 'oak_log', axis='x')
    hx = x + dx
    bp.set(hx, yc, zc, 'oak_log', axis='x')
    for k in range(1, arm + 1):
        bp.set(hx, yc + k, zc, 'oak_fence')
        bp.set(hx, yc - k, zc, 'oak_fence')
        bp.set(hx, yc, zc + k, 'oak_fence')
        bp.set(hx, yc, zc - k, 'oak_fence')
        if k >= 2:
            bp.set(hx, yc + k, zc + 1, 'white_wool')
            bp.set(hx, yc - k, zc - 1, 'white_wool')
            bp.set(hx, yc - 1, zc + k, 'white_wool')
            bp.set(hx, yc + 1, zc - k, 'white_wool')


def bench_outside(bp, x, z, looks='south'):
    bp.chair(x, 1, z, looks)


def drying_rack(bp, x0, z, w=3):
    for x in (x0, x0 + w - 1):
        bp.set(x, 1, z, 'oak_fence')
        bp.set(x, 2, z, 'oak_fence')
    for x in range(x0, x0 + w):
        bp.set(x, 3, z, 'oak_slab', type='bottom')
    for x in range(x0 + 1, x0 + w - 1):
        bp.set(x, 2, z, 'oak_trapdoor', facing='south', half='top', open='true', powered='false', waterlogged='false')


# ============================================================ standard ===

def std(bid, name, btype, variant, category, L, D, storey_items, storey_lights, deco=None,
        style='timber', lantern=None, notes=None, **kw):
    bp = bp_(bid, name, btype, variant, category, style=style)
    h = building(bp, L, D, **kw)
    for k, items in enumerate(storey_items):
        furnish(h, k, items)
    for k, n in enumerate(storey_lights):
        if n:
            lights(h, k, n, lantern=(lantern or [False] * 9)[k])
    if deco:
        deco(bp, h)
    if notes:
        bp.notes += notes
    return bp, h


B = lambda color='white', walls=None: ('bed', color, walls)
C = ('blk', 'chest')
BR = ('blk', 'barrel')
W = lambda block, walls=None: ('work', block, walls)
K = lambda block, walls=None: ('blk', block, walls)
T = lambda chairs=2: ('table', chairs)

# ---------------------------------------------------------------- homes

@register
def house_cottage():
    return std('house_cottage', 'Cottage', 'house', 'small', 'homes', 7, 6,
               [[B('red', ['north']), T(2)]], [1], chimney='west')


@register
def house_two_storey():
    # modelled on the owner's server house: cobble plinth, oak log frame,
    # plank infill, a log beam ring at the floor line, oak stair roof
    return std('house_two_storey', 'Two-Storey House', 'house', 'large', 'homes', 9, 7,
               [[T(3), K('crafting_table', ['north'])], [B('red', ['north']), B('white', ['north'])]], [1, 1],
               lantern=[True, False], storeys=2, jetty=True, chimney='east', stair_x=2)


@register
def lodging_small():
    return std('lodging_small', 'Bunkhouse', 'lodging', 'small', 'homes', 11, 7,
               [[B(), B(), B(), B()]], [2], chimney='west', door_x=5, roof_shape='hip')


@register
def lodging_hall():
    return std('lodging_hall', 'Lodging Hall', 'lodging', 'large', 'homes', 11, 7,
               [[T(4), B('white', ['east', 'west']), B('white', ['east', 'west'])], [B(), B(), B(), B()]], [1, 1],
               lantern=[True, False], storeys=2, jetty=True, chimney='west', stair_x=2, door_x=6)

# ---------------------------------------------------------------- work


@register
def builders_hut_small():
    return std('builders_hut_small', "Builder's Hut", 'builders_hut', 'small', 'work', 7, 6,
               [[W('crafting_table', ['north']), C, BR]], [1],
               deco=lambda bp, h: log_pile(bp, 7, 1, 2, 3, 2))


@register
def builders_hut_yard():
    def deco(bp, h):
        log_pile(bp, -3, 1, 2, 4, 2)
        for z in range(1, 4):
            bp.set(9, 0, z, 'cobblestone')
            bp.set(9, 1, z, 'cobblestone')
    return std('builders_hut_yard', "Builder's Yard", 'builders_hut', 'large', 'work', 9, 7,
               [[W('crafting_table', ['north']), C, C, BR, BR, T(1)]], [1],
               stone_ground=True, chimney='east', deco=deco)


@register
def warehouse_small():
    return std('warehouse_small', 'Storehouse', 'warehouse', 'small', 'work', 9, 7,
               [[C, C, BR, BR]], [2], floor='birch_planks', door_x=4, roof_shape='hip')


@register
def warehouse_large():
    return std('warehouse_large', 'Great Warehouse', 'warehouse', 'large', 'work', 11, 9,
               [[C, C, C, C, C, C, BR, BR], [BR, BR, BR, BR]], [2, 1], lantern=[True, False],
               storeys=2, stone_ground=True, ceiling=True, floor='birch_planks', stair_x=2, door_x=5, roof_shape='hip')


@register
def farmhouse_small():
    return std('farmhouse_small', 'Farmstead', 'farmhouse', 'small', 'work', 7, 6,
               [[W('composter', ['north']), C]], [1],
               deco=lambda bp, h: (hay_stack(bp, 7, 1), hay_stack(bp, 7, 2, 1)))


@register
def farmhouse_large():
    def deco(bp, h):
        paddock(bp, 9, 0, 13, 6, gate=(11, 6))
        hay_stack(bp, -2, 2); hay_stack(bp, -2, 3, 1)
    return std('farmhouse_large', 'Farmhouse', 'farmhouse', 'large', 'work', 9, 7,
               [[W('composter', ['north']), C, BR, T(2)], [K('hay_block'), K('hay_block'), K('hay_block')]], [1, 0],
               storeys=2, jetty=True, chimney='west', deco=deco)


@register
def mill_small():
    def deco(bp, h):
        sails(bp, 7, h.T + 1, 3, 3, 'east')
    return std('mill_small', 'Grist Mill', 'mill', 'small', 'work', 7, 7,
               [[W('grindstone', ['north']), C, BR]], [1], deco=deco, gable_window=False)


@register
def mill_large():
    def deco(bp, h):
        sails(bp, 9, h.T + 1, 3, 4, 'east')
        hay_stack(bp, -2, 1); hay_stack(bp, -2, 2)
    return std('mill_large', 'Windmill', 'mill', 'large', 'work', 9, 7,
               [[W('grindstone', ['north']), C, BR, BR], [BR, K('hay_block'), K('hay_block')]], [1, 1],
               storeys=2, stone_ground=True, deco=deco, gable_window=False)


@register
def bakery_small():
    return std('bakery_small', 'Bakehouse', 'bakery', 'small', 'work', 7, 6,
               [[W('furnace', ['north']), K('smoker', ['north']), C]], [1], chimney='west', roof_shape='hip')


@register
def bakery_large():
    return std('bakery_large', 'Bakery', 'bakery', 'large', 'work', 9, 7,
               [[W('furnace', ['north']), K('furnace', ['north']), K('smoker', ['north']), C, BR, T(2)], [BR, BR, C]],
               [1, 1], lantern=[True, False], storeys=2, jetty=True, stone_ground=True, chimney='west', awning=True)


@register
def kitchen_small():
    return std('kitchen_small', 'Cookhouse', 'kitchen', 'small', 'work', 7, 7,
               [[W('furnace', ['north']), K('cauldron', ['north']), C, C]], [2], chimney='east')


@register
def kitchen_large():
    return std('kitchen_large', 'Great Kitchen', 'kitchen', 'large', 'work', 9, 7,
               [[W('smoker', ['north']), K('furnace', ['north']), K('cauldron'), C, C, BR, T(2)]], [2],
               lantern=[True], stone_ground=True, chimney='east', roof_shape='hip')


@register
def dining_hall_small():
    return std('dining_hall_small', 'Common Hall', 'dining_hall', 'small', 'common', 9, 7,
               [[W('campfire', ['west']), C, T(2), T(2)]], [3], chimney='west')


@register
def dining_hall_large():
    return std('dining_hall_large', 'Great Hall', 'dining_hall', 'large', 'common', 13, 9,
               [[W('campfire', ['west']), C, BR, T(2), T(2), T(2), T(2)]], [3], lantern=[True],
               stone_ground=True, chimney='west', door_x=6, roof_shape='hip')


@register
def pasture_small():
    def deco(bp, h):
        paddock(bp, 9, 0, 14, 6, gate=(11, 6))
    return std('pasture_small', 'Byre', 'pasture', 'small', 'work', 9, 7,
               [[W('hay_block', ['north']), K('hay_block', ['north']), C]], [1], deco=deco)


@register
def pasture_large():
    def deco(bp, h):
        paddock(bp, 11, 0, 17, 7, gate=(14, 7))
    return std('pasture_large', 'Barn', 'pasture', 'large', 'work', 11, 7,
               [[W('hay_block', ['north']), K('hay_block', ['north']), K('hay_block'), C, BR], [K('hay_block'), K('hay_block'), K('hay_block')]],
               [1, 0], storeys=2, stone_ground=True, deco=deco, door_x=6)


@register
def butcher_small():
    return std('butcher_small', 'Butchery', 'butcher', 'small', 'work', 7, 6,
               [[W('smoker', ['north']), C, C]], [1], chimney='east', awning=True)


@register
def butcher_large():
    return std('butcher_large', 'Shambles', 'butcher', 'large', 'work', 9, 7,
               [[W('smoker', ['north']), K('smoker', ['north']), C, C, BR, T(1)]], [1],
               stone_ground=True, chimney='east',
               deco=lambda bp, h: drying_rack(bp, -4, 3))


def fishery(bid, name, variant, L, D, storeys, items, lts, slip=(2, 4), **kw):
    """A boathouse with a closed indoor slip (a 1-deep cobblestone basin).

    The room must hold the fisher's chair and 20+ surface water itself:
    PlaqueBlockEntity.surveyRoom scores candidates BEFORE the fishing-grounds
    override, so a chair + water outside would let an outdoor sprawl out-score
    the real room. An earlier version let the slip run out under the back wall
    as void cells; on a dry site (the W19 scenario arena) that was a hole and
    the room read "not enclosed". The basin is enclosed whatever the site is,
    with or without water in it. The Builder never places fluids, so the
    player pours two buckets into the basin (infinite source)."""
    bp = bp_(bid, name, 'fishery', variant, 'work')
    h = building(bp, L, D, storeys=storeys, path=False, **kw)
    st = h.storeys[0]
    sx0, sx1 = slip
    for x in range(sx0 - 1, sx1 + 2):
        for z in range(0, D - 1):
            bp.set(x, -1, z, 'cobblestone')
    for x in range(sx0, sx1 + 1):
        for z in range(1, D - 2):
            bp.set(x, 0, z, 'water', level='0')
            st.occ.add((x, z))
    # the chair sits on the east walkway facing the slip
    cz = 2
    bp.set(sx1 + 1, 1, cz, 'hearthstead:fishers_chair', facing='west')
    st.occ.add((sx1 + 1, cz)); st.groups.append([(sx1 + 1, cz)])
    bp.work.append((sx1 + 1, 1, cz + 1))
    for k, it in enumerate(items):
        furnish(h, k, it)
    for k, n in enumerate(lts):
        if n:
            lights(h, k, n)
    bp.notes.append('The Builder never places fluids: pour two water buckets into the indoor slip '
                    '(needs 20+ connected surface water; the slip holds %d).' % ((sx1 - sx0 + 1) * (D - 3)))
    return bp, h


@register
def fishery_small():
    return fishery('fishery_small', 'Boathouse', 'small', 10, 9, 1,
                   [[W('hearthstead:fish_rack', ['east']), BR]], [1], slip=(2, 5), door_x=7)


@register
def fishery_large():
    return fishery('fishery_large', "Fisher's Hall", 'large', 11, 9, 1,
                   [[W('hearthstead:fish_rack', ['east']), K('hearthstead:fish_rack', ['east']), BR, C]], [2],
                   slip=(2, 5), door_x=8, chimney='east')


@register
def hunters_lodge_small():
    return std('hunters_lodge_small', "Hunter's Hut", 'hunters_lodge', 'small', 'work', 7, 6,
               [[W('fletching_table', ['north']), C]], [1],
               deco=lambda bp, h: drying_rack(bp, 7, 2, 3) if False else drying_rack(bp, -4, 2))


@register
def hunters_lodge_large():
    return std('hunters_lodge_large', "Hunter's Lodge", 'hunters_lodge', 'large', 'work', 9, 7,
               [[W('fletching_table', ['north']), C, BR, T(2)]], [1], chimney='east', stone_ground=True,
               deco=lambda bp, h: drying_rack(bp, -4, 3))


@register
def brewery_small():
    return std('brewery_small', 'Brewhouse', 'brewery', 'small', 'work', 7, 7,
               [[W('brewing_stand', ['north']), K('cauldron', ['north']), BR, BR]], [1], chimney='east')


@register
def brewery_large():
    return std('brewery_large', 'Brewery', 'brewery', 'large', 'work', 9, 7,
               [[W('brewing_stand', ['north']), K('cauldron', ['north']), BR, BR, BR, C], [BR, BR, BR, BR]], [1, 1],
               storeys=2, stone_ground=True, chimney='east')


@register
def tavern_small():
    return std('tavern_small', 'Alehouse', 'tavern', 'small', 'common', 11, 9,
               [[('tap', None, ['north']), W('bell', ['north']), C, C, T(2), T(3)]], [3],
               chimney='west', door_x=5, awning=True)


@register
def tavern_inn():
    return std('tavern_inn', 'Coaching Inn', 'tavern', 'large', 'common', 11, 9,
               [[('tap', None, ['north']), W('bell', ['north']), C, C, T(3), T(3)], [B('red'), B('red'), B('red'), T(2)]],
               [3, 1], lantern=[True, False], storeys=2, jetty=True, stone_ground=True, ceiling=True,
               chimney='west', door_x=6)


def well(bid, name, variant, L, D, stone):
    bp = bp_(bid, name, 'well', variant, 'common', style='stone' if stone else 'timber')
    h = building(bp, L, D, stone_ground=stone)
    st = h.storeys[0]
    bx, bz = L // 2 - 1, 1
    for x in (bx, bx + 1):
        for z in (bz, bz + 1):
            bp.set(x, 0, z, 'water', level='0')
            bp.set(x, -1, z, 'cobblestone')
            st.occ.add((x, z))
    for x in range(bx - 1, bx + 3):
        for z in range(bz - 1, bz + 3):
            if (x, 0, z) not in bp.c or bp.c[(x, 0, z)][0] != 'minecraft:water':
                if x in (bx - 1, bx + 2) or z in (bz - 1, bz + 2):
                    if 0 < x < L - 1 and 0 < z < D - 1:
                        pass
            if not (bx <= x <= bx + 1 and bz <= z <= bz + 1) and 0 <= x < L and 0 <= z < D:
                bp.set(x, -1, z, 'cobblestone')
    # winch: two fence posts beside the basin carrying a log, a chain over the water
    for x in (bx - 1, bx + 2):
        st.occ.add((x, bz + 1))
        st.groups.append([(x, bz + 1)])
        bp.set(x, 1, bz + 1, 'oak_fence'); bp.set(x, 2, bz + 1, 'oak_fence')
    for x in range(bx - 1, bx + 3):
        bp.set(x, 3, bz + 1, 'oak_log', axis='x')
    bp.set(bx, 2, bz + 1, 'chain', axis='y', waterlogged='false')
    st.groups.append([(bx, bz)])
    furnish(h, 0, [])
    lights(h, 0, 1)
    bp.notes.append('The Builder never places fluids: after the build, pour two water buckets into the 2x2 basin.')
    return bp, h


@register
def well_small():
    return well('well_small', 'Well House', 'small', 7, 6, False)


@register
def well_large():
    return well('well_large', 'Stone Well House', 'large', 8, 7, True)


@register
def lumber_camp_small():
    return std('lumber_camp_small', "Woodcutter's Hut", 'lumber_camp', 'small', 'work', 7, 6,
               [[W('crafting_table', ['north']), C]], [1],
               deco=lambda bp, h: log_pile(bp, -3, 1, 2, 3, 2))


@register
def lumber_camp_large():
    def deco(bp, h):
        log_pile(bp, -3, 0, 2, 5, 3)
        bp.set(10, 1, 5, 'oak_log', axis='y')  # chopping block
    return std('lumber_camp_large', 'Lumber Camp', 'lumber_camp', 'large', 'work', 9, 7,
               [[W('crafting_table', ['north']), C, BR, T(1)]], [1], chimney='east', deco=deco)


@register
def sawmill_small():
    return std('sawmill_small', 'Saw Pit', 'sawmill', 'small', 'work', 7, 7,
               [[W('stonecutter', ['north']), C, BR]], [1],
               deco=lambda bp, h: log_pile(bp, 7, 1, 2, 4, 2))


@register
def sawmill_large():
    return std('sawmill_large', 'Sawmill', 'sawmill', 'large', 'work', 9, 7,
               [[W('stonecutter', ['north']), K('stonecutter', ['north']), C, C, BR]], [1],
               stone_ground=True, deco=lambda bp, h: log_pile(bp, -3, 0, 2, 6, 3))


@register
def carpenter_small():
    return std('carpenter_small', "Joiner's Shop", 'carpenter', 'small', 'work', 7, 7,
               [[W('crafting_table', ['north']), K('crafting_table', ['north']), C, C]], [2], roof_shape='hip')


@register
def carpenter_large():
    return std('carpenter_large', "Carpenter's Workshop", 'carpenter', 'large', 'work', 9, 7,
               [[W('crafting_table', ['north']), K('crafting_table', ['north']), K('crafting_table'), C, C, BR, T(1)]], [2],
               chimney='east', deco=lambda bp, h: log_pile(bp, -3, 1, 2, 4, 2))


@register
def mine_small():
    return std('mine_small', 'Mine Head', 'mine', 'small', 'work', 7, 7,
               [[('ladder', 3, ['north']), C, BR]], [3], stone_ground=True, style='stone')


@register
def mine_large():
    bp = bp_('mine_large', 'Mine Shaft House', 'mine', 'large', 'work', style='stone')
    h = building(bp, 9, 7, stone_ground=True, chimney='east')
    st = h.storeys[0]
    # a real shaft: 1x2 hole in the floor, cobblestone lining, ladder down 5
    sx, sz = 1, 1
    for y in range(-5, 1):
        for x in range(sx - 1, sx + 2):
            for z in range(sz - 1, sz + 3):
                if x == sx and z in (sz, sz + 1) and y > -5:
                    bp.set(x, y, z, 'air')
                else:
                    bp.set(x, y, z, 'cobblestone')
    for y in range(-4, 4):
        bp.set(sx, y, sz, 'ladder', facing='south', waterlogged='false')
        st.no_light.add((sx, y, sz)); st.no_light.add((sx, y, sz + 1))
    st.occ |= {(sx, sz)}
    st.groups.append([(sx, sz)])
    st.occ.add((sx, sz + 1))  # the open shaft mouth
    furnish(h, 0, [C, C, BR, T(1)])
    lights(h, 0, 3)
    bp.notes.append('The shaft is dug 5 blocks below the floor (air cells below ground_level).')
    return bp, h


@register
def smelter_small():
    return std('smelter_small', 'Bloomery', 'smelter', 'small', 'work', 7, 7,
               [[W('blast_furnace', ['north']), K('furnace', ['north']), C, C]], [1],
               stone_ground=True, chimney='west', style='stone')


@register
def smelter_large():
    return std('smelter_large', 'Smeltery', 'smelter', 'large', 'work', 9, 7,
               [[W('blast_furnace', ['north']), K('blast_furnace', ['north']), K('furnace', ['north']), C, C, BR]], [1],
               stone_all=True, roof='cobblestone', chimney='west', style='stone')


@register
def smithy_small():
    return std('smithy_small', 'Forge', 'smithy', 'small', 'work', 9, 6,
               [[W('anvil', ['north']), K('smithing_table', ['north']), K('furnace', ['north']), C, C]], [2],
               stone_ground=True, chimney='east')


@register
def smithy_large():
    return std('smithy_large', 'Smithy', 'smithy', 'large', 'work', 11, 7,
               [[W('anvil', ['north']), K('smithing_table', ['north']), K('blast_furnace', ['north']),
                 K('furnace', ['north']), C, C, BR, K('grindstone')]], [2], lantern=[True],
               stone_ground=True, chimney='east', door_x=5, roof_shape='hip')


@register
def weaver_small():
    return std('weaver_small', "Weaver's Cottage", 'weaver', 'small', 'work', 7, 7,
               [[W('loom', ['north']), K('cauldron', ['north']), C, C]], [2], roof_shape='hip')


@register
def weaver_large():
    return std('weaver_large', 'Weaving Hall', 'weaver', 'large', 'work', 9, 7,
               [[W('loom', ['north']), K('loom', ['north']), K('cauldron'), C, C], [K('white_wool'), K('white_wool'), BR]], [2, 1],
               storeys=2, jetty=True, chimney='east')


@register
def infirmary_small():
    return std('infirmary_small', 'Healer\'s House', 'infirmary', 'small', 'common', 9, 7,
               [[B('white', ['north']), B('white', ['north']), W('cauldron'), C]], [2], chimney='west', roof_shape='hip')


@register
def infirmary_large():
    return std('infirmary_large', 'Infirmary', 'infirmary', 'large', 'common', 11, 7,
               [[W('cauldron', ['north']), K('brewing_stand', ['north']), C, BR, T(2)], [B(), B(), B(), B()]], [1, 1],
               lantern=[True, False], storeys=2, jetty=True, chimney='west', door_x=6)


@register
def barracks_small():
    return std('barracks_small', 'Guardhouse', 'barracks', 'small', 'military', 11, 7,
               [[B('white', ['north']), B('white', ['north']), C, C, T(2)]], [2], chimney='east', door_x=5, roof_shape='hip')


@register
def barracks_large():
    return std('barracks_large', 'Barracks', 'barracks', 'large', 'military', 13, 9,
               [[C, C, C, C, BR, T(3), T(3)], [B(), B(), B(), B(), B('white', ['east', 'west']), B('white', ['east', 'west'])]],
               [2, 2], lantern=[True, False], storeys=2, stone_ground=True, ceiling=True, chimney='east', door_x=6, roof_shape='hip')


@register
def tannery_small():
    return std('tannery_small', 'Tanner\'s Shed', 'tannery', 'small', 'work', 7, 7,
               [[W('cauldron', ['north']), K('cauldron', ['north']), C, C]], [1],
               deco=lambda bp, h: drying_rack(bp, 7, 3))


@register
def tannery_large():
    return std('tannery_large', 'Tannery', 'tannery', 'large', 'work', 9, 7,
               [[W('cauldron', ['north']), K('cauldron', ['north']), K('cauldron', ['north']), C, C, BR]], [1],
               stone_ground=True, deco=lambda bp, h: (drying_rack(bp, -4, 1), drying_rack(bp, -4, 4)))


@register
def fletcher_small():
    return std('fletcher_small', "Fletcher's Shop", 'fletcher', 'small', 'military', 7, 7,
               [[W('fletching_table', ['north']), K('crafting_table', ['north']), C, C]], [2])


@register
def fletcher_large():
    return std('fletcher_large', "Bowyer's Hall", 'fletcher', 'large', 'military', 9, 7,
               [[W('fletching_table', ['north']), K('crafting_table', ['north']), C, C, BR, T(1)]], [2],
               chimney='west', stone_ground=True)


@register
def armoury_small():
    return std('armoury_small', 'Arms Store', 'armoury', 'small', 'military', 9, 7,
               [[W('smithing_table', ['north']), C, C, BR, BR]], [2], stone_ground=True, roof_shape='hip')


@register
def armoury_large():
    return std('armoury_large', 'Armoury', 'armoury', 'large', 'military', 11, 7,
               [[W('smithing_table', ['north']), C, C, C, C, BR, BR]], [2], lantern=[True],
               stone_all=True, roof='cobblestone', chimney='east', door_x=5, style='stone')


@register
def mason_small():
    return std('mason_small', "Mason's Lodge", 'mason', 'small', 'work', 7, 7,
               [[W('stonecutter', ['north']), C, C]], [2], floor='stone_bricks', stone_ground=True,
               deco=lambda bp, h: [bp.set(7, y, z, 'cobblestone') for z in range(1, 4) for y in (0, 1)])


@register
def mason_large():
    return std('mason_large', "Masons' Yard", 'mason', 'large', 'work', 9, 7,
               [[W('stonecutter', ['north']), C, C, BR, T(1)]], [2], floor='stone_bricks',
               stone_all=True, roof='cobblestone', chimney='west', style='stone')


@register
def library_small():
    return std('library_small', 'Reading Room', 'library', 'small', 'common', 9, 7,
               [[K('bookshelf', ['north'])] * 7 + [K('bookshelf', ['west']), W('lectern', ['east']), T(2)]], [3], roof_shape='hip')


@register
def library_large():
    return std('library_large', 'Library', 'library', 'large', 'common', 11, 7,
               [[K('bookshelf', ['north'])] * 5 + [W('lectern', ['east']), T(3)],
                [K('bookshelf', ['north'])] * 6 + [K('bookshelf', ['east', 'west'])] * 3 + [T(2)]], [2, 1],
               storeys=2, jetty=True, chimney='west', door_x=6)


@register
def market_small():
    return std('market_small', 'Market Hall', 'market', 'small', 'common', 11, 7,
               [[W('barrel', ['north']), BR, BR, BR, C, C]], [2], door_x=5, roof_shape='hip', awning=True)


@register
def market_large():
    return std('market_large', 'Guild Market', 'market', 'large', 'common', 13, 9,
               [[W('barrel', ['north']), BR, BR, BR, BR, BR, C, C, T(2)]], [2], lantern=[True],
               stone_ground=True, door_x=6, chimney='east', roof_shape='hip', awning=True)


@register
def trading_post_small():
    return std('trading_post_small', 'Trading Post', 'trading_post', 'small', 'common', 7, 6,
               [[W('cartography_table', ['north']), C, C]], [1], roof_shape='hip', awning=True)


@register
def trading_post_large():
    return std('trading_post_large', 'Merchant House', 'trading_post', 'large', 'common', 9, 7,
               [[W('cartography_table', ['north']), C, C, BR, BR, T(2)], [B('red', ['north']), C]], [1, 1],
               storeys=2, jetty=True, chimney='west', awning=True)


@register
def architects_study_small():
    return std('architects_study_small', "Architect's Study", 'architects_study', 'small', 'common', 7, 7,
               [[W('lectern', ['north']), K('bookshelf', ['north']), K('bookshelf', ['north']), K('cartography_table')]], [2])


@register
def architects_study_large():
    return std('architects_study_large', 'Drawing Office', 'architects_study', 'large', 'common', 9, 7,
               [[W('lectern', ['north']), K('bookshelf', ['north']), K('bookshelf', ['north']), K('bookshelf', ['north']),
                 K('cartography_table'), T(2)], [K('bookshelf', ['north'])] * 3 + [C]], [2, 1],
               storeys=2, jetty=True, chimney='east')


@register
def school_small():
    return std('school_small', 'Schoolroom', 'school', 'small', 'common', 9, 7,
               [[W('lectern', ['north']), K('lectern', ['north']), K('bookshelf', ['west'])] +
                [K('bookshelf', ['west', 'east'])] * 3 + [T(2)]], [2])


@register
def school_large():
    return std('school_large', 'School', 'school', 'large', 'common', 11, 7,
               [[W('lectern', ['north']), K('lectern', ['north']), K('bookshelf', ['west', 'east'])] +
                [K('bookshelf', ['west', 'east'])] * 3 + [T(2), T(2)]], [2], lantern=[True],
               stone_ground=True, chimney='west', door_x=5, roof_shape='hip')


# ============================================================ defense ===

def tower(bid, name, stone):
    """5x5 tower: two chambers joined by a ladder, a hatch to the lookout."""
    bp = bp_(bid, name, 'watchtower', 'stone' if stone else 'timber', 'defense', kind='defense',
             requires='masonry' if stone else 'defense_plans', style='stone' if stone else 'timber')
    S = 5
    wallb = 'cobblestone' if stone else 'oak_planks'
    for x in range(S):
        for z in range(S):
            edge = x in (0, S - 1) or z in (0, S - 1)
            corner = x in (0, S - 1) and z in (0, S - 1)
            bp.set(x, 0, z, 'cobblestone' if edge else ('cobblestone' if stone else 'oak_planks'))
            for y in range(1, 8):
                if not edge:
                    bp.set(x, y, z, 'air')
                elif stone:
                    bp.set(x, y, z, 'stone_bricks' if corner else kit._mossy(bp, 0.1))
                elif corner:
                    bp.set(x, y, z, 'oak_log', axis='y')
                else:
                    bp.set(x, y, z, 'cobblestone' if y <= 1 else wallb)
            # mid floor + ceiling
            if not edge:
                bp.set(x, 4, z, 'oak_planks')
            else:
                bp.set(x, 4, z, 'stone_bricks' if stone else 'oak_log', **({} if stone else {'axis': 'x' if z in (0, S - 1) else 'z'}))
    # lookout deck (1-block overhang), railing, corner posts, pyramid roof
    for x in range(-1, S + 1):
        for z in range(-1, S + 1):
            ring = x in (-1, S) or z in (-1, S)
            bp.set(x, 8, z, ('stone_brick_slab' if stone else 'oak_slab') if ring else ('stone_bricks' if stone else 'oak_planks'),
                   **({'type': 'top'} if ring else {}))
            if ring:
                cornr = x in (-1, S) and z in (-1, S)
                if cornr:
                    for y in (9, 10):
                        bp.set(x, y, z, 'stone_bricks' if stone else 'oak_log', **({} if stone else {'axis': 'y'}))
                else:
                    bp.set(x, 9, z, 'cobblestone_wall' if stone else 'oak_fence')
            else:
                bp.set(x, 9, z, 'air'); bp.set(x, 10, z, 'air')
    for x in range(-1, S + 1):
        for z in range(-1, S + 1):
            if not (x in (-1, S) or z in (-1, S)):
                continue
            if x in (-1, S) and z in (-1, S):
                continue
            bp.set(x, 10, z, 'air')
    roof = 'cobblestone' if stone else 'oak'
    for k, y in enumerate(range(11, 15)):
        lo, hi = -2 + k, S + 1 - k
        for x in range(lo, hi + 1):
            for z in range(lo, hi + 1):
                if x in (lo, hi) or z in (lo, hi):
                    f = 'south' if z == lo else 'north' if z == hi else 'east' if x == lo else 'west'
                    bp.set(x, y, z, roof + '_stairs', facing=f, half='bottom', shape='straight')
                elif k == 3 or True:
                    bp.set(x, y, z, 'air') if (x, y, z) not in bp.c else None
    bp.set(2, 15, 2, roof + '_slab', type='bottom')
    bp.set(2, 14, 2, 'oak_planks' if not stone else 'cobblestone')
    bp.set(2, 13, 2, 'lantern', hanging='true', waterlogged='false')
    # door, plaque, ladder, hatch, windows
    bp.door(2, 1, S - 1, 'north')
    if not stone:
        for y in (1, 2, 3):
            bp.set(1, y, S - 1, 'oak_log', axis='y'); bp.set(3, y, S - 1, 'oak_log', axis='y')
    bp.set(3, 2, S - 2, 'hearthstead:plaque', facing='north', glow='empty', registered='false')
    bp.plaque = ((3, 2, S - 2), 'north')
    for y in range(1, 8):
        bp.set(2, y, 1, 'ladder', facing='south', waterlogged='false')
    bp.set(2, 8, 1, 'oak_trapdoor', facing='south', half='bottom', open='false', powered='false', waterlogged='false')
    for (x, z) in ((2, 0), (0, 2), (S - 1, 2)):
        bp.set(x, 6, z, 'glass_pane')
    bp.set(2, 6, S - 1, 'glass_pane')
    bp.set(0, 2, 2, 'glass_pane'); bp.set(S - 1, 2, 2, 'glass_pane')
    bp.set(1, 1, 1, 'chest', facing='south', type='single', waterlogged='false')
    bp.set(3, 1, 1, 'barrel', facing='up', open='false')
    bp.work.append((2, 9, 2))
    for (x, y, z, f) in ((1, 2, 2, 'east'), (3, 3, 2, 'west'), (1, 6, 3, 'east'), (3, 6, 3, 'west')):
        bp.set(x, y, z, 'wall_torch', facing=f)
    # outside lanterns at the lookout corners
    for (x, z) in ((-1, -1), (S, S)):
        bp.set(x, 11 - 1, z, 'stone_bricks' if stone else 'oak_log', **({} if stone else {'axis': 'y'}))
    bp.set(2, 0, S, 'dirt_path')
    bp.meta['ground_level'] = 0
    bp.meta['eave_y'] = 8
    bp.notes.append('Guards climb the ladder and open the hatch to the lookout; the lookout is outside the room.')
    return bp, None


@register
def watchtower_timber():
    return tower('watchtower_timber', 'Timber Watchtower', False)


@register
def watchtower_stone():
    return tower('watchtower_stone', 'Stone Watchtower', True)


@register
def stone_gatehouse():
    bp = bp_('stone_gatehouse', 'Stone Gatehouse', 'watchtower', 'large', 'defense', kind='defense',
             requires='masonry', style='stone', segment='gate')
    X, Z = 9, 7
    for x in range(X):
        for z in range(Z):
            passage = 3 <= x <= 5
            bp.set(x, 0, z, 'dirt_path' if passage else 'cobblestone')
            for y in range(1, 4):
                if passage:
                    bp.set(x, y, z, 'air')
                elif x in (0, 2, 6, 8) or z in (0, Z - 1):
                    bp.set(x, y, z, 'stone_bricks' if (x in (0, 8) and z in (0, Z - 1)) else kit._mossy(bp, 0.1))
                else:
                    bp.set(x, y, z, 'air')
            # guard room floor
            bp.set(x, 4, z, 'stone_bricks')
            for y in range(5, 8):
                if x in (0, 8) or z in (0, Z - 1):
                    bp.set(x, y, z, 'stone_bricks' if (x in (0, 8) and z in (0, Z - 1)) else kit._mossy(bp, 0.1))
                else:
                    bp.set(x, y, z, 'air')
            bp.set(x, 8, z, 'stone_bricks')
            if x in (0, 8) or z in (0, Z - 1):
                bp.set(x, 9, z, 'cobblestone_wall' if (x + z) % 2 else 'stone_bricks')
    # arch over the passage, the gate (fence gates) in the middle
    for z in (0, Z - 1):
        bp.set(3, 3, z, 'stone_brick_stairs', facing='west', half='top', shape='straight')
        bp.set(5, 3, z, 'stone_brick_stairs', facing='east', half='top', shape='straight')
    for x in range(3, 6):
        bp.set(x, 1, 3, 'oak_fence_gate', facing='south', open='false', in_wall='false', powered='false')
        bp.set(x, 3, 3, 'iron_bars')
    # west tower: door from the passage, ladder up into the guard room
    bp.set(1, 4, 1, 'ladder', facing='south', waterlogged='false')
    for y in range(1, 8):
        bp.set(1, y, 1, 'ladder', facing='south', waterlogged='false')
    bp.door(2, 1, 4, 'east')
    bp.set(1, 2, 4, 'hearthstead:plaque', facing='west', glow='empty', registered='false')
    bp.plaque = ((1, 2, 4), 'west')
    bp.set(1, 1, 5, 'chest', facing='north', type='single', waterlogged='false')
    for (x, y, z, f) in ((1, 3, 3, 'north'), (2, 6, 3, 'east'), (6, 6, 3, 'west'), (4, 6, 1, 'south')):
        bp.set(x, y, z, 'wall_torch', facing=f)
    for x in (2, 4, 6):
        bp.set(x, 6, 0, 'glass_pane'); bp.set(x, 6, Z - 1, 'glass_pane')
    # east tower is solid with a barrel store in the guard room
    bp.set(7, 5, 5, 'barrel', facing='up', open='false')
    bp.set(7, 5, 1, 'chest', facing='south', type='single', waterlogged='false')
    for (x, z) in ((3, -1), (5, -1), (3, Z), (5, Z)):
        bp.set(x, 3, z, 'lantern', hanging='false', waterlogged='false') if False else None
    for (x, z) in ((2, Z), (6, Z)):
        bp.set(x, 3, z, 'wall_torch', facing='south')
    bp.meta['ground_level'] = 0
    bp.meta['eave_y'] = 8
    bp.work.append((4, 9, 3))
    bp.notes.append('Gate = 3 oak fence gates across the passage; guard room above registers as a watchtower.')
    return bp, None


@register
def palisade_segment():
    bp = bp_('palisade_segment', 'Palisade (5)', None, 'segment', 'defense', kind='defense',
             requires='defense_plans', segment='palisade')
    for x in range(5):
        bp.set(x, 0, 0, 'cobblestone')
        for y in range(1, 4):
            bp.set(x, y, 0, 'oak_log', axis='y')
        bp.set(x, 4, 0, 'oak_fence')
        bp.set(x, 1, 1, 'oak_slab', type='top') if x % 2 == 0 else None
    return bp, None


@register
def palisade_gate():
    bp = bp_('palisade_gate', 'Palisade Gate', None, 'gate', 'defense', kind='defense',
             requires='defense_plans', segment='gate')
    for x in range(5):
        bp.set(x, 0, 0, 'cobblestone' if x in (0, 4) else 'dirt_path')
        if x in (0, 4):
            for y in range(1, 5):
                bp.set(x, y, 0, 'oak_log', axis='y')
            bp.set(x, 5, 0, 'oak_fence')
    for x in (1, 2, 3):
        bp.set(x, 1, 0, 'oak_fence_gate', facing='south', open='false', in_wall='false', powered='false')
        bp.set(x, 2, 0, 'air'); bp.set(x, 3, 0, 'air')
        bp.set(x, 4, 0, 'oak_log', axis='x')
    bp.set(2, 5, 0, 'oak_fence')
    bp.set(2, 3, 1, 'lantern', hanging='true', waterlogged='false')
    bp.set(2, 4, 1, 'oak_slab', type='top')
    return bp, None


@register
def stone_wall_segment():
    bp = bp_('stone_wall_segment', 'Stone Wall (5)', None, 'segment', 'defense', kind='defense',
             requires='masonry', style='stone', segment='stone')
    for x in range(5):
        for z in (0, 1):
            bp.set(x, 0, z, 'cobblestone')
            for y in range(1, 4):
                bp.set(x, y, z, kit._mossy(bp, 0.1))
        bp.set(x, 4, 0, 'cobblestone_wall' if x % 2 == 0 else 'air')
        bp.set(x, 4, 1, 'air')
    return bp, None


@register
def stone_wall_gate():
    bp = bp_('stone_wall_gate', 'Stone Wall Gate', None, 'gate', 'defense', kind='defense',
             requires='masonry', style='stone', segment='gate')
    for x in range(5):
        for z in (0, 1):
            if x in (0, 4):
                bp.set(x, 0, z, 'cobblestone')
                for y in range(1, 5):
                    bp.set(x, y, z, 'stone_bricks' if y == 4 else kit._mossy(bp, 0.1))
            else:
                bp.set(x, 0, z, 'dirt_path')
                for y in (1, 2):
                    bp.set(x, y, z, 'air')
                bp.set(x, 3, z, 'stone_bricks')
                bp.set(x, 4, z, 'stone_bricks')
        bp.set(x, 5, 0, 'cobblestone_wall' if x % 2 == 0 else 'air')
    for x in (1, 2, 3):
        bp.set(x, 1, 0, 'oak_fence_gate', facing='south', open='false', in_wall='false', powered='false')
    bp.set(1, 2, 0, 'stone_brick_stairs', facing='west', half='top', shape='straight')
    bp.set(3, 2, 0, 'stone_brick_stairs', facing='east', half='top', shape='straight')
    bp.set(1, 2, 1, 'stone_brick_stairs', facing='west', half='top', shape='straight')
    bp.set(3, 2, 1, 'stone_brick_stairs', facing='east', half='top', shape='straight')
    return bp, None


@register
def barricade_3x1():
    bp = bp_('barricade_3x1', 'Barricade', None, 'small', 'defense', kind='barricade',
             requires='builders_hut', segment='barricade')
    for x in range(3):
        bp.set(x, 1, 0, 'oak_log', axis='x')
        bp.set(x, 2, 0, 'oak_fence')
    return bp, None


@register
def barricade_3x2():
    bp = bp_('barricade_3x2', 'Heavy Barricade', None, 'large', 'defense', kind='barricade',
             requires='builders_hut', segment='barricade')
    for x in range(3):
        bp.set(x, 1, 0, 'oak_log', axis='x')
        bp.set(x, 2, 0, 'oak_log', axis='x')
        bp.set(x, 3, 0, 'oak_fence')
        bp.set(x, 1, 1, 'oak_slab', type='top')
    return bp, None


# ============================================================ checks ===

def check(bp, reqs):
    """L1 + walkability on a flat test site. Returns a report dict."""
    rep = {'id': bp.id, 'type': bp.meta.get('building_type'), 'kind': bp.meta.get('kind')}
    if not bp.plaque:
        rep['l1'] = 'n/a (no plaque)'
        return rep
    g = bp.meta.get('ground_level', 0)
    overlay = {}
    if bp.meta.get('test_pond'):
        for p in bp.meta['test_pond']['cells']:
            if p not in bp.c:
                overlay[p] = ('minecraft:water', {'level': '0'})
    world = scanner.World(bp.c, g, overlay)
    t = bp.meta['building_type']
    rq = reqs[t]
    res = scanner.survey(world, bp.plaque[0], bp.plaque[1], rq)
    room_ok = res is not None and res['enclosed'] and not res['sky'] and res['volume'] <= scanner.MAX_HOME_VOLUME         and all(scanner.measure(q, res)[0] >= q[1] for q in rq)
    if not room_ok and t in YARD_TYPES:
        f_ = scanner.FACE[bp.plaque[1]]
        p_ = bp.plaque[0]
        for seed in ((p_[0] + f_[0], p_[1], p_[2] + f_[2]), (p_[0] - 2 * f_[0], p_[1], p_[2] - 2 * f_[2])):
            y = scanner.yard_scan(world, seed)
            if y is not None and y['enclosed'] and all(scanner.measure(q, y)[0] >= q[1] for q in rq):
                res = y
                break
    if res and t == 'fishery':
        water, ready = scanner.fishing_grounds(world, bp.plaque[0])
        res['counts']['minecraft:water'] = water
        res['counts']['hearthstead:fishers_chair'] = 1 if ready else 0
    lines = []
    ok = res is not None and res['enclosed'] and not res['sky'] and res['volume'] <= scanner.MAX_HOME_VOLUME
    if res is None:
        lines.append('no interior')
    else:
        lines.append(f"enclosed={res['enclosed']} roofed={not res['sky']} volume={res['volume']}")
        for q in rq:
            have, need = scanner.measure(q, res)
            lines.append(f"{scanner.req_name(q)} {have}/{need}")
            ok &= have >= need
    for p, (n, pr) in bp.c.items():
        if short(n).endswith('_trapdoor') and pr.get('half') == 'top' and pr.get('open') != 'true':
            lines.append(f'closed top trapdoor at {p}')
            ok = False
    rep['l1'] = 'PASS' if ok else 'FAIL'
    rep['survey'] = lines
    # walkability: from outside the door to every container/bed/work block
    if res is not None:
        door = None
        for p, (n, pr) in bp.c.items():
            if n.endswith('_door') and pr.get('half') == 'lower':
                door = (p, pr['facing'])
                break
        if res.get('yard'):
            gates = sorted((p for p, (n, pr) in bp.c.items() if n.endswith('_fence_gate')), key=lambda q: -q[2])
            door = (gates[0], 'north') if gates else door     # the front (south-most) gate
        if door is None:
            rep['walk'] = 'FAIL'
            rep['walk_missing'] = ['no door or gate reachable']
            rep['l1'] = 'FAIL'
            return rep
        dp, f = door
        fd = scanner.FACE[f]
        outside = (dp[0] - fd[0], dp[1], dp[2] - fd[2])
        starts = [outside]
        if res.get('yard'):
            # a gate on the lot edge: the cell outside it is off the template
            # (void = air), so the yard is also walked from just inside it
            starts.append((dp[0] + fd[0], dp[1], dp[2] + fd[2]))
        reach = scanner.walk_check(world, starts, None)
        missing = []
        for p, (n, pr) in bp.c.items():
            s = short(n)
            if s in ('chest', 'barrel', 'crafting_table', 'furnace', 'smoker', 'blast_furnace', 'composter', 'grindstone',
                     'lectern', 'loom', 'stonecutter', 'anvil', 'smithing_table', 'fletching_table', 'cartography_table',
                     'brewing_stand', 'cauldron', 'bell', 'fish_rack', 'ale_tap', 'hay_block') or s.endswith('_bed'):
                if p not in res['filled'] and not any((p[0] + d[0], p[1] + d[1], p[2] + d[2]) in res['filled'] for d in scanner.DIRS6):
                    continue  # outside decoration
                if s.endswith('_bed') and pr.get('part') == 'head':
                    continue  # the foot is checked
                if s == 'hay_block' and bp.meta.get('eave_y') is not None and p[1] >= bp.meta['eave_y']:
                    continue  # thatch roof, not a hay bale
                if s == 'barrel' and any(short(bp.c.get((p[0] + d[0], p[1], p[2] + d[2]), ('x:x', {}))[0]) == 'ale_tap'
                                         for d in scanner.DIRS6):
                    continue  # the keg behind an ale tap is served through the tap
                near = [(p[0] + dx, p[1] + dy, p[2] + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)) for dy in (0, -1)]
                if not any(c in reach for c in near):
                    missing.append(f'{s}@{p}')
        # upper floor reached?
        ys = sorted({p[1] for p in reach if p in res['filled']})
        rep['walk_levels'] = ys
        # a valid door: both halves, walkable on both sides
        inside = (dp[0] + fd[0], dp[1], dp[2] + fd[2])
        up = bp.c.get((dp[0], dp[1] + 1, dp[2]))
        door_ok = (res.get('yard') or (up is not None and up[0] == bp.c[dp][0] and up[1].get('half') == 'upper'))             and (outside in reach or res.get('yard')) and inside in reach
        rep['door'] = 'OK' if door_ok else 'FAIL'
        if not door_ok:
            missing.append(f'door@{dp}')
        # stair headroom: 3 clear blocks over every walked stair tread in the room
        chairs = {tuple(fu['pos']) for fu in bp.furniture if 'pos' in fu}
        for p, (n, pr) in bp.c.items():
            if short(n).endswith('_stairs') and pr.get('half') == 'bottom' and p not in chairs                     and (p[0], p[1] + 1, p[2]) in res['filled']:
                for k in (1, 2, 3):
                    s3 = world.get((p[0], p[1] + k, p[2]))
                    if s3 is not None and scanner.has_collision(s3) and short(s3[0]) != 'ladder':
                        missing.append(f'headroom@{p}+{k}')
                        break
        rep['walk_missing'] = missing
        rep['walk'] = 'OK' if not missing else 'FAIL'
    return rep


def export_cells(bp, origin, path):
    x0, y0, z0 = origin
    cells = [[p[0] - x0, p[1] - y0, p[2] - z0, n, pr] for p, (n, pr) in bp.c.items() if short(n) != 'air']
    pond_cells = (bp.meta.get('test_pond') or {}).get('cells', []) or bp.meta.get('preview_pond') or []
    pond = [[p[0] - x0, p[1] - y0, p[2] - z0] for p in pond_cells if tuple(p) not in bp.c]
    with open(path, 'w') as f:
        json.dump({'id': bp.id, 'name': bp.meta.get('name'), 'pond': pond,
                   'ground_level': bp.meta.get('ground_level', 0) - y0, 'type': bp.meta.get('building_type'), 'furniture': bp.furniture and
                   [dict(fu, pos=[fu['pos'][0] - x0, fu['pos'][1] - y0, fu['pos'][2] - z0]) for fu in bp.furniture if 'pos' in fu],
                   'cells': cells}, f)


PRESET_STYLES = (('timber', 'small'), ('stone', 'large'), ('rustic', 'small'))


YARD_TYPES = {'lumber_camp', 'sawmill', 'mine', 'mason', 'smithy', 'smelter', 'tannery', 'builders_hut', 'well', 'market'}


def fill_ground(bp):
    """Every void cell of the ground_level layer inside the lot becomes ground.

    Template y=ground_level is placed ON the site (one above the terrain
    surface), so a void cell there is air in the world: a yard's foot level
    would drop under its own fence and props would float. Water the
    blueprint wants kept (a dock's lake) stays void."""
    g = bp.meta.get('ground_level', 0)
    keep = {tuple(q) for q in (bp.meta.get('preview_pond') or [])}
    keep |= {tuple(q) for q in ((bp.meta.get('test_pond') or {}).get('cells') or [])}
    xs = [p[0] for p in bp.c]
    zs = [p[2] for p in bp.c]
    for x in range(min(xs), max(xs) + 1):
        for z in range(min(zs), max(zs) + 1):
            if (x, g, z) in bp.c or (x, g, z) in keep:
                continue
            bp.set(x, g, z, 'grass_block', snowy='false')


def build_all(only=None):
    """Job-first catalogue (Elmfield town palette) + 3 style presets per building type."""
    import jobs
    items = []
    by_type = {}
    fns = [jobs.OVERRIDES.get(fn.__name__, fn) for fn in CATALOG] + list(jobs.ADDITIONS)
    for fn in fns:
        bp, h = fn()
        typed = bp.plaque is not None and bp.meta.get('kind') == 'building'
        if typed:
            bp.meta['style'] = 'elmfield'
            bp.meta['preset'] = styles.STYLES['elmfield']['label']
            by_type.setdefault(bp.meta['building_type'], {})[bp.meta['variant']] = (fn, bp.meta['name'])
        elif bp.meta.get('preset') is None:
            bp.meta['preset'] = (bp.meta.get('style') or '').title() or None
        items.append((bp, h))
    for btype, variants in by_type.items():
        for style, shape in PRESET_STYLES:
            fn, base_name = variants.get(shape) or next(iter(variants.values()))
            src, h = fn()
            label = styles.STYLES[style]['label']
            nid, nname = f'{btype}_{style}', f'{base_name} ({label})'
            if h is not None:
                items.append((styles.restyle(src, h, style, nid, nname), h))
            else:
                items.append((styles.restyle_generic(src, style, nid, nname), None))
    if only:
        items = [it for it in items if it[0].id in only]
    return items


def main():
    only = None
    if '--only' in sys.argv:
        only = set(sys.argv[sys.argv.index('--only') + 1].split(','))
    reqs = scanner.parse_requirements()
    os.makedirs(os.path.join(OUT, 'cells'), exist_ok=True)
    report = []
    failed = []
    for bp, h in build_all(only):
        fill_ground(bp)
        kit.stairs_shape_pass(bp)
        connect_pass(bp)
        rep = check(bp, reqs)
        meta, origin = save(bp, RES)
        rep.update(size=meta['size'], blocks=meta['block_count'], cells=meta['cells'],
                   name=meta['name'], category=meta['category'], variant=meta['variant'],
                   style=meta.get('style'), preset=meta.get('preset'))
        export_cells(bp, origin, os.path.join(OUT, 'cells', bp.id + '.json'))
        report.append(rep)
        flag = rep.get('l1')
        print(f"{bp.id:28s} {str(meta['building_type']):16s} {'x'.join(map(str, meta['size'])):9s} "
              f"blocks={meta['block_count']:4d} L1={flag} walk={rep.get('walk', '-')} "
              + ('' if flag in ('PASS', None) or flag.startswith('n/a') else ' | '.join(rep.get('survey', [])))
              + ('' if rep.get('walk') in (None, 'OK') else ' missing=' + ','.join(rep['walk_missing'][:4])))
        if flag == 'FAIL' or rep.get('walk') == 'FAIL':
            failed.append(bp.id)
    with open(os.path.join(OUT, 'report.json'), 'w') as f:
        json.dump(report, f, indent=1)
    if not only:
        # the shipped id list, read by ScenarioBlueprintGameTests so its list can never drift
        with open(os.path.join(RES, 'data/hearthstead/blueprint_catalog.txt'), 'w', newline='\n') as f:
            f.write('# generated by tools/blueprints/gen_town_blueprints.py -- one shipped blueprint id per line\n')
            for r in sorted(report, key=lambda r: r['id']):
                f.write(r['id'] + '\n')
    print(f'{len(report)} blueprints, {len(failed)} failing: {failed}')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
