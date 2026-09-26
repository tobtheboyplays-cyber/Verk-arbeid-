"""Job-first designs for every building type (owner, 26 Sep: "the buildings
must be UNIQUE to what the job is"; concept sheet approved "Veldig bra!!!").

Each type gets a structure that reads as its trade -- open work yards for the
camps, a headframe for the mine, a dock for the fishery, a windmill, an open
forge -- and the enclosed trades get their trade's props and silhouette
around them. MineColonies huts are the inspiration (ideas only, see
plan/blueprints/MINECOLONIES-NOTES.md); every block is authored here.

OVERRIDES replaces a catalogue entry by id; ADDITIONS adds new ids. The style
presets (Whitewashed Timber, Stone Hall, Rustic Log) are generated from these
by gen_town_blueprints.build_all.
"""
import gen_town_blueprints as G
import concepts as K
from kit import building, furnish, lights
from concepts import fence_ring, lamp_post, logs, lean_to, chopping_block, sawbuck, dummy, banner_pole, \
    spear_rack, crate, plaque

OVERRIDES = {}
ADDITIONS = []


def override(bid):
    def deco(fn):
        OVERRIDES[bid] = fn
        return fn
    return deco


def addition(fn):
    ADDITIONS.append(fn)
    return fn


def _small(bp):
    bp.meta['variant'] = 'small'
    return bp


def put(bp, x, y, z, name, **p):
    if (x, y, z) not in bp.c:
        bp.set(x, y, z, name, **p)


def as_id(bp, bid, name, variant):
    """Re-badge a concept design as a shipped catalogue entry."""
    bp.id = bid
    bp.meta.update(name=name, variant=variant, style='elmfield', preset='Elmfield Timber')
    bp.meta.setdefault('eave_y', 4)
    return bp


def std_deco(bid, name, btype, variant, category, L, D, items, lts, deco, **kw):
    bp, h = G.std(bid, name, btype, variant, category, L, D, items, lts, **kw)
    deco(bp, h)
    return bp, h


# ------------------------------------------------------------------ props

def bench(bp, x, z, looks='south'):
    if (x, 1, z) not in bp.c:
        bp.chair(x, 1, z, looks)


def garden_bed(bp, x0, z0, w, d, flowers=('poppy', 'cornflower', 'dandelion', 'oxeye_daisy', 'allium')):
    for x in range(x0, x0 + w):
        for z in range(z0, z0 + d):
            put(bp, x, 0, z, 'podzol' if (x + z) % 3 else 'coarse_dirt')
            if (x, 1, z) not in bp.c and (x + 2 * z) % 2 == 0:
                bp.set(x, 1, z, bp.rng.choice(flowers))


def herb_bed(bp, x0, z0, w, d):
    garden_bed(bp, x0, z0, w, d, flowers=('fern', 'azure_bluet', 'lily_of_the_valley', 'cornflower', 'oxeye_daisy'))
    for x in range(x0 - 1, x0 + w + 1):
        for z in (z0 - 1, z0 + d):
            put(bp, x, 1, z, 'spruce_trapdoor', facing='north', half='bottom', open='false', powered='false', waterlogged='false')


def crates(bp, cells):
    for (x, y, z) in cells:
        put(bp, x, y, z, 'barrel', facing='up', open='false')


def wool_line(bp, x0, x1, z, colors=('white_wool', 'light_gray_wool', 'yellow_wool', 'red_wool')):
    for x in (x0, x1):
        for y in (1, 2, 3):
            put(bp, x, y, z, 'spruce_fence')
    for i, x in enumerate(range(x0 + 1, x1)):
        put(bp, x, 3, z, 'spruce_fence')
        put(bp, x, 2, z, colors[i % len(colors)])


def archery_butt(bp, x, z):
    put(bp, x, 1, z, 'hay_block', axis='y')
    put(bp, x, 2, z, 'target')


def hop_poles(bp, x0, z0, n=3):
    for i in range(n):
        x = x0 + 2 * i
        for y in (1, 2, 3):
            put(bp, x, y, z0, 'spruce_fence')
        put(bp, x, 4, z0, 'oak_leaves', persistent='true', distance='1', waterlogged='false')
        put(bp, x, 3, z0 + 1, 'oak_leaves', persistent='true', distance='1', waterlogged='false') if False else None


def hanging_sign(bp, x, y, z, facing='south', wood='oak'):
    # a sign board hung from a beam over the door
    put(bp, x, y, z, f'{wood}_wall_hanging_sign', facing=facing, waterlogged='false')


def scaffold_frame(bp, x0, z0, h=4):
    # a timber mock-up frame (vanilla scaffolding needs jungle bamboo: seed-dependent)
    for dx in (0, 2):
        for dz in (0, 2):
            for y in range(1, h + 1):
                put(bp, x0 + dx, y, z0 + dz, 'oak_fence')
    for x in range(x0, x0 + 3):
        for z in range(z0, z0 + 3):
            put(bp, x, h, z, 'oak_slab', type='bottom')


def stack(bp, x, z, block, h=2, **p):
    for y in range(1, h + 1):
        put(bp, x, y, z, block, **p)


def pen(bp, x0, z0, x1, z1, gate):
    fence_ring(bp, x0, z0, x1, z1, gates={gate}, post=3)


def trough(bp, x, z, n=2):
    for i in range(n):
        put(bp, x + i, 1, z, 'cauldron')


def lamp(bp, x, z):
    if (x, 1, z) not in bp.c and (x, 2, z) not in bp.c:
        lamp_post(bp, x, z)


# ================================================================== homes

@override('house_cottage')
def house_cottage():
    def deco(bp, h):
        fence_ring(bp, -3, 7, 9, 10, gates={(3, 10)}, post=3, lantern_posts={(-3, 10), (9, 10)})
        for (x, z) in ((3, 7), (3, 8), (3, 9)):
            bp.set(x, 0, z, 'dirt_path')
            if (x, 1, z) in bp.c and bp.c[(x, 1, z)][0].endswith('fence'):
                bp.remove(x, 1, z)
        garden_bed(bp, -2, 8, 4, 2)
        garden_bed(bp, 5, 8, 3, 2)
        bench(bp, 5, 6, 'south')
        logs(bp, 7, 1, 1, 3, 2, 'z')
    return std_deco('house_cottage', 'Cottage', 'house', 'small', 'homes', 7, 6,
                    [[G.B('red', ['north']), G.T(2)]], [1], deco, chimney='west')


@override('house_two_storey')
def house_two_storey():
    def deco(bp, h):
        garden_bed(bp, -3, 1, 2, 5)
        bench(bp, 6, 7, 'south'); bench(bp, 7, 7, 'south')
        logs(bp, 10, 1, 1, 4, 2, 'z')
        crates(bp, [(10, 1, 5)])
    return std_deco('house_two_storey', 'Two-Storey House', 'house', 'large', 'homes', 9, 7,
                    [[G.T(3), G.K('crafting_table', ['north'])], [G.B('red', ['north']), G.B('white', ['north'])]], [1, 1],
                    deco, lantern=[True, False], storeys=2, jetty=True, chimney='east', stair_x=2)


@override('lodging_small')
def lodging_small():
    def deco(bp, h):
        # a porch bench row and a hitching rail
        for x in (1, 2, 8, 9):
            bench(bp, x, 7, 'south')
        for x in range(0, 4):
            put(bp, x, 1, 9, 'oak_fence')
        crates(bp, [(10, 1, 8)])
        lamp(bp, 11, 9)
    return std_deco('lodging_small', 'Bunkhouse', 'lodging', 'small', 'homes', 11, 7,
                    [[G.B(), G.B(), G.B(), G.B()]], [2], deco, chimney='west', door_x=5, roof_shape='hip')


@override('lodging_hall')
def lodging_hall():
    def deco(bp, h):
        banner_pole(bp, 12, 8, 'blue')
        for x in (1, 2, 3):
            bench(bp, x, 8, 'south')
        crates(bp, [(10, 1, 8), (11, 1, 8)])
    return std_deco('lodging_hall', 'Lodging Hall', 'lodging', 'large', 'homes', 11, 7,
                    [[G.T(4), G.B('white', ['east', 'west']), G.B('white', ['east', 'west'])], [G.B(), G.B(), G.B(), G.B()]],
                    [1, 1], deco, lantern=[True, False], storeys=2, jetty=True, chimney='west', stair_x=2, door_x=6)


# ================================================================== work

@override('lumber_camp_small')
def lumber_camp_small():
    return as_id(K.lumber_camp_yard(), 'lumber_camp_small', "Woodcutter's Camp", 'small'), None


@override('lumber_camp_large')
def lumber_camp_large():
    def deco(bp, h):
        fence_ring(bp, -8, -1, -1, 9, gates={(-4, 9)}, lantern_posts={(-8, 9)})
        logs(bp, -7, 0, 2, 5, 2, 'z')
        logs(bp, -4, 0, 2, 3, 2, 'z', 'spruce')
        sawbuck(bp, -7, 7)
        chopping_block(bp, -3, 6)
    return std_deco('lumber_camp_large', 'Lumber Camp', 'lumber_camp', 'large', 'work', 9, 7,
                    [[G.W('crafting_table', ['north']), G.C, G.BR, G.T(1)]], [1], deco, chimney='east')


@override('mine_small')
def mine_small():
    return as_id(K.mine_headframe(), 'mine_small', 'Mine Headframe', 'small'), None


@override('mine_large')
def mine_large():
    bp, h = G.mine_large()
    for z in range(8, 13):
        put(bp, 4, 1, z, 'rail', shape='north_south', waterlogged='false')
    put(bp, 4, 1, 13, 'hopper', facing='down', enabled='true')
    for (x, z, hh) in ((7, 10, 2), (8, 10, 3), (9, 10, 2), (8, 11, 1), (7, 11, 1), (9, 9, 1)):
        for y in range(1, hh + 1):
            put(bp, x, y, z, ['cobblestone', 'andesite', 'coarse_dirt'][(x + y + z) % 3])
    lamp(bp, 1, 9)
    return bp, h


@override('fishery_small')
def fishery_small():
    return as_id(K.fishery_dock(), 'fishery_small', 'Fishing Dock', 'small'), None


@override('smithy_small')
def smithy_small():
    return as_id(K.smithy_forge(), 'smithy_small', 'Open Forge', 'small'), None


@override('smithy_large')
def smithy_large():
    def deco(bp, h):
        fence_ring(bp, -1, 8, 11, 12, gates={(5, 12)}, lantern_posts={(-1, 12), (11, 12)})
        put(bp, 2, 1, 10, 'cauldron')
        put(bp, 8, 1, 10, 'anvil', facing='east')
        crates(bp, [(9, 1, 9), (10, 1, 9)])
        put(bp, 10, 2, 9, 'coal_block')
    return std_deco('smithy_large', 'Smithy', 'smithy', 'large', 'work', 11, 7,
                    [[G.W('anvil', ['north']), G.K('smithing_table', ['north']), G.K('blast_furnace', ['north']),
                      G.K('furnace', ['north']), G.C, G.C, G.BR, G.K('grindstone')]], [2], deco, lantern=[True],
                    stone_ground=True, chimney='east', door_x=5)


@override('pasture_small')
def pasture_small():
    return as_id(K.pasture_barn(), 'pasture_small', 'Barn & Paddock', 'small'), None


@override('mill_small')
def mill_small():
    return as_id(K.mill_windmill(), 'mill_small', 'Windmill', 'small'), None


@override('tannery_small')
def tannery_small():
    return as_id(K.tannery_yard(), 'tannery_small', "Tanner's Yard", 'small'), None


@override('tannery_large')
def tannery_large():
    def deco(bp, h):
        fence_ring(bp, -1, 8, 9, 13, gates={(4, 13)}, lantern_posts={(-1, 13)}, wood='spruce')
        for x in (1, 2, 3):
            put(bp, x, 1, 10, 'cauldron')
        G.drying_rack(bp, 5, 11, 3)
        put(bp, 7, 1, 9, 'composter', level='3')
    return std_deco('tannery_large', 'Tannery', 'tannery', 'large', 'work', 9, 7,
                    [[G.W('cauldron', ['north']), G.K('cauldron', ['north']), G.K('cauldron', ['north']), G.C, G.C, G.BR]], [1],
                    deco, stone_ground=True)


@override('mason_small')
def mason_small():
    return as_id(K.mason_yard(), 'mason_small', "Masons' Yard", 'small'), None


@override('mason_large')
def mason_large():
    def deco(bp, h):
        for x in range(-1, 10):
            for z in range(8, 12):
                put(bp, x, 0, z, 'stone_bricks' if (x + z) % 3 else 'cobblestone')
        for (x, z, hh, b) in ((0, 9, 2, 'stone'), (1, 9, 1, 'stone'), (7, 10, 2, 'stone_bricks'), (8, 10, 1, 'andesite')):
            stack(bp, x, z, b, hh)
        for y in range(1, 8):
            put(bp, 4, y, 10, 'spruce_log', axis='y')
        for x in range(3, 8):
            put(bp, x, 8, 10, 'spruce_log', axis='x')
        for y in range(5, 8):
            put(bp, 7, y, 10, 'chain', axis='y', waterlogged='false')
        put(bp, 7, 4, 10, 'stone_bricks')
    return std_deco('mason_large', "Mason's Workshop", 'mason', 'large', 'work', 9, 7,
                    [[G.W('stonecutter', ['north']), G.C, G.C, G.BR, G.T(1)]], [2], deco, floor='stone_bricks',
                    stone_all=True, roof='cobblestone', chimney='west', style='stone')


@override('bakery_small')
def bakery_small():
    return as_id(K.bakery_oven(), 'bakery_small', 'Bakehouse & Bread Oven', 'small'), None


@override('bakery_large')
def bakery_large():
    def deco(bp, h):
        # a second, free-standing bread oven in the yard and a flour store
        for x in range(10, 13):
            for z in range(2, 5):
                put(bp, x, 0, z, 'cobblestone')
                for y in (1, 2):
                    put(bp, x, y, z, 'bricks')
            put(bp, x, 3, 3, 'brick_slab', type='bottom')
        put(bp, 11, 1, 5, 'furnace', facing='south', lit='false')
        crates(bp, [(-2, 1, 1), (-2, 1, 2), (-2, 2, 1)])
        put(bp, -2, 1, 3, 'white_wool')
    return std_deco('bakery_large', 'Bakery', 'bakery', 'large', 'work', 9, 7,
                    [[G.W('furnace', ['north']), G.K('furnace', ['north']), G.K('smoker', ['north']), G.C, G.BR, G.T(2)], [G.BR, G.BR, G.C]],
                    [1, 1], deco, lantern=[True, False], storeys=2, jetty=True, stone_ground=True, chimney='west', awning=True)


@override('barracks_small')
def barracks_small():
    bp = K.barracks_drill_yard()
    bp.id = 'barracks_small'
    bp.meta.update(name='Barracks & Drill Yard', variant='small', style='elmfield', preset='Elmfield Timber')
    return bp, None


@override('barracks_large')
def barracks_large():
    def deco(bp, h):
        fence_ring(bp, -1, 10, 13, 16, gates={(6, 16)}, lantern_posts={(-1, 16), (13, 16)})
        for (x, z) in ((2, 13), (4, 13), (9, 13), (11, 13)):
            dummy(bp, x, z, 'north')
        banner_pole(bp, 6, 12, 'red')
        spear_rack(bp, 1, 11, 3)
    return std_deco('barracks_large', 'Barracks', 'barracks', 'large', 'military', 13, 9,
                    [[G.C, G.C, G.C, G.C, G.BR, G.T(3), G.T(3)],
                     [G.B(), G.B(), G.B(), G.B(), G.B('white', ['east', 'west']), G.B('white', ['east', 'west'])]],
                    [2, 2], deco, lantern=[True, False], storeys=2, stone_ground=True, ceiling=True, chimney='east',
                    door_x=6, roof_shape='hip')


# ---------------------------------------------------------------- open yards (new)

@override('sawmill_small')
def sawmill_small():
    """An open-sided timber shed over a long saw bench, with plank and log stacks."""
    bp = _small(K.cbp('sawmill_small', 'Saw Shed', 'sawmill', 'MineColonies Sawmill: the big saw bench IS the building', 'yard'))
    bp.meta['eave_y'] = 4
    fence_ring(bp, 0, 0, 12, 10, gates={(6, 10)}, lantern_posts={(0, 10), (12, 10)})
    # open-sided shed: 6 posts, a gable roof, no walls
    for x in (3, 6, 9):
        for z in (2, 6):
            for y in range(1, 4):
                bp.set(x, y, z, 'oak_log', axis='y')
    for x in range(2, 11):
        for z, y, f in ((1, 4, 'south'), (2, 5, 'south'), (3, 6, 'south'), (5, 6, 'north'), (6, 5, 'north'), (7, 4, 'north')):
            bp.set(x, y, z, 'oak_stairs', facing=f, half='bottom', shape='straight')
        bp.set(x, 6, 4, 'oak_slab', type='bottom')
        bp.set(x, 4, 2, 'oak_log', axis='x') if x in range(3, 10) else None
        bp.set(x, 4, 6, 'oak_log', axis='x') if x in range(3, 10) else None
    for x in range(3, 10):
        for z in (3, 4, 5):
            for y in (4, 5):
                if (x, y, z) not in bp.c:
                    bp.set(x, y, z, 'air')
    # the saw bench: a log on trestles with the stonecutter blade in the middle
    for x in (4, 5, 7, 8):
        bp.set(x, 1, 4, 'stripped_oak_log', axis='x')
    bp.set(6, 1, 4, 'stonecutter', facing='south'); bp.work.append((6, 1, 5))
    bp.set(4, 2, 4, 'oak_log', axis='x'); bp.set(5, 2, 4, 'oak_log', axis='x')
    bp.set(6, 3, 3, 'lantern', hanging='true', waterlogged='false')
    bp.set(8, 3, 5, 'lantern', hanging='true', waterlogged='false')
    # storage under the shed, the plaque on a post
    bp.set(4, 1, 3, 'chest', facing='south', type='single', waterlogged='false')
    bp.set(8, 1, 3, 'chest', facing='south', type='single', waterlogged='false')
    crates(bp, [(8, 1, 5)])
    plaque(bp, 6, 2, 7, 'south')
    bp.set(6, 2, 6, 'oak_planks'); bp.set(6, 1, 6, 'oak_log', axis='y'); bp.set(6, 3, 6, 'oak_log', axis='y')
    # log pile in, plank stacks out
    logs(bp, 1, 2, 1, 6, 2, 'z')
    for z in (7, 8):
        for x in (9, 10, 11):
            stack(bp, x, z, 'oak_planks', 1 + (x + z) % 2)
    for z in range(8, 11):
        bp.set(6, 0, z, 'dirt_path')
    return bp, None


@override('smelter_small')
def smelter_small():
    """A bloomery: a stone furnace tower with blast furnaces, ore and charcoal heaps, a lean-to."""
    bp = _small(K.cbp('smelter_small', 'Bloomery', 'smelter', 'MineColonies Smelter: the furnace tower reads from the gate', 'yard'))
    bp.meta['eave_y'] = 4
    fence_ring(bp, 0, 0, 12, 10, gates={(6, 10)}, lantern_posts={(0, 10), (12, 10)})
    # furnace tower 3x3, tapering, smoking
    for x in range(4, 7):
        for z in range(1, 4):
            for y in range(0, 5):
                bp.set(x, y, z, 'bricks' if (x, z) != (5, 2) or y == 0 else 'air')
    for y in range(5, 9):
        bp.set(5, y, 2, 'bricks'); bp.set(4, y, 2, 'bricks') if y < 7 else None; bp.set(6, y, 2, 'bricks') if y < 7 else None
    bp.set(5, 9, 2, 'campfire', lit='true', signal_fire='false', facing='south', waterlogged='false')
    bp.set(5, 1, 3, 'blast_furnace', facing='south', lit='false'); bp.work.append((5, 1, 4))
    bp.set(4, 1, 3, 'furnace', facing='south', lit='false')
    bp.set(6, 1, 3, 'blast_furnace', facing='south', lit='false')
    # lean-to store
    lean_to(bp, 8, 11, 0, depth=3, wood='spruce', wall='cobblestone')
    bp.set(9, 1, 1, 'chest', facing='south', type='single', waterlogged='false')
    bp.set(10, 1, 1, 'chest', facing='south', type='single', waterlogged='false')
    bp.set(9, 3, 2, 'lantern', hanging='true', waterlogged='false')
    plaque(bp, 10, 2, 1, 'south')
    # ore heap and charcoal heap
    for (x, z, hh, b) in ((1, 6, 2, 'raw_iron_block'), (2, 6, 1, 'cobblestone'), (1, 7, 1, 'raw_copper_block'), (2, 7, 1, 'andesite'),
                          (9, 7, 1, 'coal_block'), (10, 7, 2, 'coal_block'), (10, 8, 1, 'coal_block')):
        stack(bp, x, z, b, hh)
    crates(bp, [(1, 1, 9), (2, 1, 9)])
    for z in range(5, 11):
        bp.set(6, 0, z, 'dirt_path')
    return bp, None


@override('well_small')
def well_small():
    """An open village well in a small walled square: stone curb, roof on four posts, bucket winch."""
    bp = _small(K.cbp('well_small', 'Village Well', 'well', 'MineColonies-style square landmark', 'yard'))
    bp.meta['eave_y'] = 4
    for x in range(0, 9):
        for z in range(0, 9):
            edge = x in (0, 8) or z in (0, 8)
            bp.set(x, 0, z, 'cobblestone' if (x + z) % 4 else 'mossy_cobblestone')
            if edge:
                bp.set(x, 1, z, 'cobblestone_wall')
    for (x, z) in ((0, 0), (8, 0), (0, 8), (8, 8)):
        bp.set(x, 1, z, 'stone_bricks'); bp.set(x, 2, z, 'lantern', hanging='false', waterlogged='false')
    bp.set(4, 1, 8, 'oak_fence_gate', facing='south', open='false', in_wall='false', powered='false')
    # the well: 2x2 water in a stone curb
    for x in range(3, 7):
        for z in range(3, 7):
            inner = x in (4, 5) and z in (4, 5)
            bp.set(x, -1, z, 'cobblestone')
            post = (x, z) in ((3, 3), (6, 3), (3, 6), (6, 6))
            if inner:
                bp.set(x, 0, z, 'water', level='0')
            elif post:
                bp.set(x, 1, z, 'oak_log', axis='y')
            elif z in (3, 6):
                # the curb stands on the north and south sides only: a bucket is
                # dipped from the open east/west sides (and the yard survey sees the water)
                bp.set(x, 1, z, 'stone_brick_slab', type='bottom')
            else:
                bp.set(x, 0, z, 'stone_bricks')
    for (x, z) in ((3, 3), (6, 3), (3, 6), (6, 6)):
        for y in (2, 3):
            bp.set(x, y, z, 'oak_fence')
    for x in range(2, 8):
        for z, y, f in ((2, 4, 'south'), (3, 5, 'south'), (4, 6, 'south'), (5, 6, 'north'), (6, 5, 'north'), (7, 4, 'north')):
            bp.set(x, y, z, 'oak_stairs', facing=f, half='bottom', shape='straight')
    for x in (3, 6):
        for z in (4, 5):
            bp.set(x, 4, z, 'oak_planks')
    for x in range(3, 7):
        bp.set(x, 4, 3, 'oak_log', axis='x'); bp.set(x, 4, 6, 'oak_log', axis='x')
    for x in range(4, 6):
        bp.set(x, 5, 4, 'oak_log', axis='x') if False else None
    bp.set(4, 3, 4, 'chain', axis='y', waterlogged='false')
    bp.set(3, 3, 4, 'lantern', hanging='true', waterlogged='false')
    bp.set(6, 3, 5, 'lantern', hanging='true', waterlogged='false')
    bp.set(4, 2, 4, 'lantern', hanging='true', waterlogged='false') if False else None
    # plaque on a signpost facing the square
    bp.set(2, 1, 1, 'oak_log', axis='y'); bp.set(2, 2, 1, 'oak_log', axis='y')
    plaque(bp, 2, 2, 2, 'south')
    for (x, z) in ((6, 1), (1, 6)):
        bench(bp, x, z, 'south' if z == 1 else 'east')
    garden_bed(bp, 6, 6, 2, 1)
    crates(bp, [(7, 1, 2)])
    bp.notes.append('The Builder pours the 2x2 well with 2 water buckets.')
    return bp, None


@override('market_small')
def market_small():
    """An open market square: four stalls with striped wool awnings, a fountain-less crossing."""
    bp = _small(K.cbp('market_small', 'Market Square', 'market', 'MineColonies Market: open stalls under awnings', 'yard'))
    bp.meta['eave_y'] = 3
    for x in range(0, 13):
        for z in range(0, 11):
            bp.set(x, 0, z, 'cobblestone' if (x * 3 + z) % 5 else 'andesite')
    fence_ring(bp, 0, 0, 12, 10, gates={(6, 10), (6, 0)}, lantern_posts={(4, 10), (8, 10), (4, 0), (8, 0)})
    stalls = ((2, 2, 'red_wool', 'white_wool'), (8, 2, 'blue_wool', 'white_wool'), (2, 6, 'yellow_wool', 'white_wool'), (8, 6, 'green_wool', 'white_wool'))
    for (x0, z0, c1, c2) in stalls:
        for (x, z) in ((x0, z0), (x0 + 2, z0)):
            for y in (1, 2):
                bp.set(x, y, z, 'spruce_fence')
        for x in range(x0 - 1, x0 + 4):
            for z in (z0 - 1, z0, z0 + 1):
                bp.set(x, 3, z, c1 if (x + z) % 2 else c2)
        bp.set(x0 + 1, 1, z0, 'barrel', facing='up', open='false')
        bp.set(x0, 1, z0 + 1, 'barrel', facing='up', open='false') if False else None
    bp.work.append((3, 1, 3))
    bp.set(9, 1, 1, 'chest', facing='south', type='single', waterlogged='false')
    bp.set(10, 1, 1, 'chest', facing='south', type='single', waterlogged='false')
    crates(bp, [(3, 1, 1), (9, 1, 5)])
    bp.set(6, 1, 4, 'oak_log', axis='y'); bp.set(6, 2, 4, 'oak_log', axis='y')
    plaque(bp, 6, 2, 5, 'south')
    bench(bp, 5, 8, 'north'); bench(bp, 7, 8, 'north')
    return bp, None


# ---------------------------------------------------------------- enclosed trades + props

@override('builders_hut_yard')
def builders_hut_yard():
    """A builder's yard: a scaffold frame, stacks of planks, cobble and logs, a small hut."""
    def deco(bp, h):
        fence_ring(bp, 7, -1, 16, 9, gates={(12, 9)}, lantern_posts={(16, 9)})
        scaffold_frame(bp, 12, 0, 4)
        for (x, z, b, hh) in ((9, 1, 'oak_planks', 2), (10, 1, 'oak_planks', 1), (9, 3, 'cobblestone', 2), (10, 3, 'cobblestone', 2),
                              (14, 5, 'stone_bricks', 1), (15, 5, 'stone_bricks', 2)):
            stack(bp, x, z, b, hh)
        logs(bp, 9, 5, 2, 3, 2, 'z')
        crates(bp, [(15, 1, 7), (15, 1, 8)])
    return std_deco('builders_hut_yard', "Builder's Yard", 'builders_hut', 'large', 'work', 7, 7,
                    [[G.W('crafting_table', ['north']), G.C, G.C, G.BR]], [1], deco, chimney='west')


@override('builders_hut_small')
def builders_hut_small():
    def deco(bp, h):
        stack(bp, 7, 1, 'oak_planks', 2); stack(bp, 8, 1, 'cobblestone', 1)
        logs(bp, 7, 2, 2, 2, 2, 'z')
        bp.set(8, 1, 4, 'oak_fence'); bp.set(8, 2, 4, 'oak_fence'); bp.set(8, 3, 4, 'oak_slab', type='bottom')
        sawbuck(bp, -4, 3)
    return std_deco('builders_hut_small', "Builder's Hut", 'builders_hut', 'small', 'work', 7, 6,
                    [[G.W('crafting_table', ['north']), G.C, G.BR]], [1], deco)


@override('warehouse_small')
def warehouse_small():
    def deco(bp, h):
        # loading bay: crates stacked by the gable, a hoist beam and a cart
        for x in range(9, 12):
            for z in range(1, 5):
                if (x + z) % 2 == 0:
                    crates(bp, [(x, 1, z)])
                    if x == 9:
                        crates(bp, [(x, 2, z)])
        for x in range(8, 11):
            put(bp, x, 5, 3, 'oak_log', axis='x')
        put(bp, 10, 4, 3, 'chain', axis='y', waterlogged='false')
        put(bp, 10, 3, 3, 'barrel', facing='up', open='false')
        lamp(bp, 12, 6)
    return std_deco('warehouse_small', 'Storehouse', 'warehouse', 'small', 'work', 9, 7,
                    [[G.C, G.C, G.BR, G.BR]], [2], deco, floor='birch_planks', door_x=4, roof_shape='hip')


@override('warehouse_large')
def warehouse_large():
    def deco(bp, h):
        # a timber crane over the loading yard
        for y in range(1, 10):
            put(bp, 13, y, 4, 'spruce_log', axis='y')
        for x in range(10, 16):
            put(bp, x, 10, 4, 'spruce_log', axis='x')
        for y in range(6, 10):
            put(bp, 15, y, 4, 'chain', axis='y', waterlogged='false')
        put(bp, 15, 5, 4, 'barrel', facing='up', open='false')
        for (x, z) in ((14, 1), (15, 1), (14, 2), (15, 7), (14, 7), (15, 6)):
            crates(bp, [(x, 1, z)])
        crates(bp, [(14, 2, 1), (15, 2, 7)])
        lamp(bp, 12, 9)
    return std_deco('warehouse_large', 'Great Warehouse', 'warehouse', 'large', 'work', 11, 9,
                    [[G.C, G.C, G.C, G.C, G.C, G.C, G.BR, G.BR], [G.BR, G.BR, G.BR, G.BR]], [2, 1], deco,
                    lantern=[True, False], storeys=2, stone_ground=True, ceiling=True, floor='birch_planks', stair_x=2,
                    door_x=5, roof_shape='hip')


@override('farmhouse_small')
def farmhouse_small():
    def deco(bp, h):
        # the field: farmland rows with a water channel, fenced
        fence_ring(bp, -8, -1, -1, 8, gates={(-4, 8)}, lantern_posts={(-8, 8)})
        for x in range(-7, -1):
            for z in range(0, 8):
                bp.set(x, 0, z, 'water' if x == -4 else 'farmland', **({'level': '0'} if x == -4 else {'moisture': '7'}))
        hay = [(7, 1), (7, 2), (8, 1)]
        for (x, z) in hay:
            G.hay_stack(bp, x, z, 1)
        trough(bp, 7, 4, 1)
    bp, h = std_deco('farmhouse_small', 'Farmstead', 'farmhouse', 'small', 'work', 7, 6,
                     [[G.W('composter', ['north']), G.C]], [1], deco)
    bp.notes.append('The field is farmland with a water channel: plant it after the build (the Builder pours the channel).')
    return bp, h


@override('farmhouse_large')
def farmhouse_large():
    def deco(bp, h):
        fence_ring(bp, 10, -1, 18, 8, gates={(14, 8)}, lantern_posts={(18, 8)})
        for x in range(11, 18):
            for z in range(0, 8):
                bp.set(x, 0, z, 'water' if x == 14 else 'farmland', **({'level': '0'} if x == 14 else {'moisture': '7'}))
        G.hay_stack(bp, -2, 2); G.hay_stack(bp, -2, 3, 1)
        trough(bp, -3, 5, 2)
    return std_deco('farmhouse_large', 'Farmhouse', 'farmhouse', 'large', 'work', 9, 7,
                    [[G.W('composter', ['north']), G.C, G.BR, G.T(2)], [G.K('hay_block'), G.K('hay_block'), G.K('hay_block')]], [1, 0],
                    deco, storeys=2, jetty=True, chimney='west')


@override('pasture_large')
def pasture_large():
    def deco(bp, h):
        fence_ring(bp, 11, 0, 18, 7, gates={(14, 7)}, lantern_posts={(18, 7)})
        trough(bp, 16, 2, 2)
        put(bp, 13, 1, 2, 'hay_block', axis='y')
        for y in range(5, 8):
            put(bp, -1, y, 3, 'oak_log', axis='x') if False else None
    return std_deco('pasture_large', 'Great Barn', 'pasture', 'large', 'work', 11, 7,
                    [[G.W('hay_block', ['north']), G.K('hay_block', ['north']), G.K('hay_block'), G.C, G.BR],
                     [G.K('hay_block'), G.K('hay_block'), G.K('hay_block')]], [1, 0], deco, storeys=2, stone_ground=True, door_x=6,
                    roof='dark_oak')


@override('mill_large')
def mill_large():
    bp, h = G.mill_large()
    crates(bp, [(10, 1, 6), (11, 1, 6)])
    put(bp, 10, 2, 6, 'white_wool')
    return bp, h


@override('kitchen_small')
def kitchen_small():
    def deco(bp, h):
        # an outdoor cook canopy with a cauldron over a fire, a woodpile, herb pots
        for (x, z) in ((8, 1), (10, 1), (8, 4), (10, 4)):
            for y in (1, 2):
                put(bp, x, y, z, 'spruce_fence')
        for x in range(8, 11):
            for z in range(1, 5):
                put(bp, x, 3, z, 'spruce_slab', type='bottom')
        put(bp, 9, 1, 2, 'campfire', lit='true', signal_fire='false', facing='south', waterlogged='false')
        put(bp, 9, 2, 2, 'chain', axis='y', waterlogged='false') if False else None
        put(bp, 9, 1, 3, 'cauldron')
        logs(bp, -2, 1, 1, 3, 1, 'z')
        herb_bed(bp, -3, 5, 2, 1)
    return std_deco('kitchen_small', 'Cookhouse', 'kitchen', 'small', 'work', 7, 7,
                    [[G.W('furnace', ['north']), G.K('cauldron', ['north']), G.C, G.C]], [2], deco, chimney='east')


@override('kitchen_large')
def kitchen_large():
    def deco(bp, h):
        herb_bed(bp, 10, 1, 2, 4)
        logs(bp, -3, 1, 1, 4, 2, 'z')
        crates(bp, [(-3, 1, 6)])
    return std_deco('kitchen_large', 'Great Kitchen', 'kitchen', 'large', 'work', 9, 7,
                    [[G.W('smoker', ['north']), G.K('furnace', ['north']), G.K('cauldron'), G.C, G.C, G.BR, G.T(2)]], [2], deco,
                    lantern=[True], stone_ground=True, chimney='east', roof_shape='hip')


@override('dining_hall_small')
def dining_hall_small():
    def deco(bp, h):
        for x in (1, 2, 6, 7):
            bench(bp, x, 8, 'north')
        banner_pole(bp, 4, 9, 'yellow')
    return std_deco('dining_hall_small', 'Common Hall', 'dining_hall', 'small', 'common', 9, 7,
                    [[G.W('campfire', ['west']), G.C, G.T(2), G.T(2)]], [3], deco, chimney='west')


@override('dining_hall_large')
def dining_hall_large():
    def deco(bp, h):
        banner_pole(bp, 2, 10, 'red'); banner_pole(bp, 10, 10, 'red')
        for x in (4, 8):
            bench(bp, x, 10, 'north')
    return std_deco('dining_hall_large', 'Great Hall', 'dining_hall', 'large', 'common', 13, 9,
                    [[G.W('campfire', ['west']), G.C, G.BR, G.T(2), G.T(2), G.T(2), G.T(2)]], [3], deco, lantern=[True],
                    stone_ground=True, chimney='west', door_x=6, roof_shape='hip')


@override('butcher_small')
def butcher_small():
    def deco(bp, h):
        pen(bp, 8, 0, 12, 4, (10, 4))
        trough(bp, 10, 1, 1)
        put(bp, 11, 1, 2, 'hay_block', axis='y')
    return std_deco('butcher_small', 'Butchery', 'butcher', 'small', 'work', 7, 6,
                    [[G.W('smoker', ['north']), G.C, G.C]], [1], deco, chimney='east', awning=True)


@override('butcher_large')
def butcher_large():
    def deco(bp, h):
        # a stone smokehouse beside the shambles
        for x in range(-5, -2):
            for z in range(1, 4):
                bp.set(x, 0, z, 'cobblestone')
                for y in (1, 2, 3):
                    if x in (-5, -3) or z in (1, 3):
                        bp.set(x, y, z, 'cobblestone')
                bp.set(x, 4, z, 'cobblestone_slab', type='bottom')
        bp.set(-4, 1, 2, 'campfire', lit='true', signal_fire='false', facing='south', waterlogged='false')
        bp.set(-4, 4, 2, 'cobblestone_wall')
        pen(bp, -6, 5, -2, 9, (-4, 9))
    return std_deco('butcher_large', 'Shambles', 'butcher', 'large', 'work', 9, 7,
                    [[G.W('smoker', ['north']), G.K('smoker', ['north']), G.C, G.C, G.BR, G.T(1)]], [1], deco,
                    stone_ground=True, chimney='east')


@override('hunters_lodge_small')
def hunters_lodge_small():
    def deco(bp, h):
        G.drying_rack(bp, -4, 2)
        G.drying_rack(bp, -4, 5)
        # a small smokehouse
        for x in (8, 9, 10):
            for z in (1, 2, 3):
                bp.set(x, 0, z, 'cobblestone')
                for y in (1, 2):
                    if x in (8, 10) or z in (1, 3):
                        bp.set(x, y, z, 'cobblestone')
                bp.set(x, 3, z, 'spruce_slab', type='bottom')
        bp.set(9, 1, 2, 'campfire', lit='true', signal_fire='false', facing='south', waterlogged='false')
        archery_butt(bp, 9, 7)
    return std_deco('hunters_lodge_small', "Hunter's Cabin", 'hunters_lodge', 'small', 'work', 7, 6,
                    [[G.W('fletching_table', ['north']), G.C]], [1], deco, chimney='west')


@override('hunters_lodge_large')
def hunters_lodge_large():
    def deco(bp, h):
        G.drying_rack(bp, -4, 3)
        archery_butt(bp, 11, 2); archery_butt(bp, 11, 5)
        logs(bp, -3, 6, 1, 2, 1, 'z')
    return std_deco('hunters_lodge_large', "Hunter's Lodge", 'hunters_lodge', 'large', 'work', 9, 7,
                    [[G.W('fletching_table', ['north']), G.C, G.BR, G.T(2)]], [1], deco, chimney='east', stone_ground=True)


@override('brewery_small')
def brewery_small():
    def deco(bp, h):
        # barrel racks: kegs lying on their side, hop poles
        for x in range(8, 11):
            for y in (1, 2):
                put(bp, x, y, 1, 'barrel', facing='south', open='false')
        hop_poles(bp, -4, 1, 3)
        hop_poles(bp, -4, 4, 3)
    return std_deco('brewery_small', 'Brewhouse', 'brewery', 'small', 'work', 7, 7,
                    [[G.W('brewing_stand', ['north']), G.K('cauldron', ['north']), G.BR, G.BR]], [1], deco, chimney='east')


@override('brewery_large')
def brewery_large():
    def deco(bp, h):
        for x in range(10, 13):
            for y in (1, 2):
                put(bp, x, y, 1, 'barrel', facing='south', open='false')
                put(bp, x, y, 3, 'barrel', facing='south', open='false')
        # the mash tun: a cauldron ringed by planks
        put(bp, 11, 1, 6, 'cauldron')
        for (x, z) in ((10, 6), (12, 6), (11, 5), (11, 7)):
            put(bp, x, 1, z, 'oak_slab', type='top') if False else put(bp, x, 1, z, 'spruce_trapdoor', facing='north', half='bottom', open='false', powered='false', waterlogged='false')
        hop_poles(bp, -5, 2, 3)
    return std_deco('brewery_large', 'Brewery', 'brewery', 'large', 'work', 9, 7,
                    [[G.W('brewing_stand', ['north']), G.K('cauldron', ['north']), G.BR, G.BR, G.BR, G.C], [G.BR, G.BR, G.BR, G.BR]], [1, 1],
                    deco, storeys=2, stone_ground=True, chimney='east')


@override('tavern_small')
def tavern_small():
    def deco(bp, h):
        # porch seating under the awning, a signboard on a bracket
        for x in (2, 8):
            bp.table(x, 1, 11)
            bench(bp, x - 1, 11, 'east'); bench(bp, x + 1, 11, 'west')
        for x in range(0, 2):
            put(bp, x - 1 + 11, 3, 9, 'oak_fence') if False else None
        banner_pole(bp, 11, 10, 'yellow')
        crates(bp, [(-1, 1, 1), (-1, 1, 2), (-1, 2, 1)])
    return std_deco('tavern_small', 'Alehouse', 'tavern', 'small', 'common', 11, 9,
                    [[('tap', None, ['north']), G.W('bell', ['north']), G.C, G.C, G.T(2), G.T(3)]], [3], deco,
                    chimney='west', door_x=5, awning=True)


@override('tavern_inn')
def tavern_inn():
    def deco(bp, h):
        for x in (2, 9):
            bp.table(x, 1, 12)
            bench(bp, x - 1, 12, 'east'); bench(bp, x + 1, 12, 'west')
        banner_pole(bp, 12, 11, 'yellow')
        # a stable lean-to with a trough and hay
        for z in range(1, 6):
            put(bp, -3, 1, z, 'oak_fence') if z in (1, 5) else None
            put(bp, -3, 3, z, 'spruce_slab', type='bottom'); put(bp, -2, 3, z, 'spruce_slab', type='bottom')
            put(bp, -3, 2, z, 'oak_fence') if z in (1, 5) else None
        put(bp, -2, 1, 2, 'hay_block', axis='y'); trough(bp, -2, 4, 1)
    return std_deco('tavern_inn', 'Coaching Inn', 'tavern', 'large', 'common', 11, 9,
                    [[('tap', None, ['north']), G.W('bell', ['north']), G.C, G.C, G.T(3), G.T(3)],
                     [G.B('red'), G.B('red'), G.B('red'), G.T(2)]], [3, 1], deco, lantern=[True, False], storeys=2, jetty=True,
                    stone_ground=True, ceiling=True, chimney='west', door_x=6)


@override('well_large')
def well_large():
    bp, h = G.well_large()
    for (x, z) in ((-2, 1), (9, 1)):
        lamp(bp, x, z)
    bench(bp, -2, 4, 'east')
    return bp, h


@override('sawmill_large')
def sawmill_large():
    def deco(bp, h):
        logs(bp, -3, 0, 2, 6, 3, 'z')
        for z in (1, 2, 3):
            stack(bp, 10, z, 'oak_planks', 2); stack(bp, 11, z, 'spruce_planks', 1)
        sawbuck(bp, 9, 5)
    return std_deco('sawmill_large', 'Sawmill', 'sawmill', 'large', 'work', 9, 7,
                    [[G.W('stonecutter', ['north']), G.K('stonecutter', ['north']), G.C, G.C, G.BR]], [1], deco, stone_ground=True)


@override('carpenter_small')
def carpenter_small():
    def deco(bp, h):
        # lumber rack under a slab roof and a sawhorse
        for z in (1, 4):
            for y in (1, 2, 3):
                put(bp, 8, y, z, 'spruce_fence')
        for z in range(1, 5):
            put(bp, 8, 4, z, 'spruce_slab', type='bottom'); put(bp, 9, 4, z, 'spruce_slab', type='bottom')
            put(bp, 9, 1, z, 'oak_log', axis='z'); put(bp, 9, 2, z, 'birch_log', axis='z') if z < 4 else None
        sawbuck(bp, -4, 3)
    return std_deco('carpenter_small', "Joiner's Shop", 'carpenter', 'small', 'work', 7, 7,
                    [[G.W('crafting_table', ['north']), G.K('crafting_table', ['north']), G.C, G.C]], [2], deco, roof_shape='hip')


@override('carpenter_large')
def carpenter_large():
    def deco(bp, h):
        log_rack = [(-3, 1), (-3, 2), (-3, 3), (-3, 4)]
        for (x, z) in log_rack:
            logs(bp, x, z, 1, 1, 2, 'x')
        sawbuck(bp, 10, 5)
        crates(bp, [(10, 1, 1), (11, 1, 1)])
    return std_deco('carpenter_large', "Carpenter's Workshop", 'carpenter', 'large', 'work', 9, 7,
                    [[G.W('crafting_table', ['north']), G.K('crafting_table', ['north']), G.K('crafting_table'), G.C, G.C, G.BR, G.T(1)]],
                    [2], deco, chimney='east')


@override('smelter_large')
def smelter_large():
    def deco(bp, h):
        for (x, z, hh, b) in ((-3, 1, 2, 'raw_iron_block'), (-3, 2, 1, 'cobblestone'), (-2, 1, 1, 'raw_copper_block'),
                              (10, 1, 1, 'coal_block'), (10, 2, 2, 'coal_block'), (11, 1, 1, 'coal_block')):
            stack(bp, x, z, b, hh)
    return std_deco('smelter_large', 'Smeltery', 'smelter', 'large', 'work', 9, 7,
                    [[G.W('blast_furnace', ['north']), G.K('blast_furnace', ['north']), G.K('furnace', ['north']), G.C, G.C, G.BR]], [1],
                    deco, stone_all=True, roof='cobblestone', chimney='west', style='stone')


@override('weaver_small')
def weaver_small():
    def deco(bp, h):
        wool_line(bp, -5, -1, 2)
        pen(bp, 8, 0, 12, 5, (10, 5))
        put(bp, 11, 1, 2, 'hay_block', axis='y')
    return std_deco('weaver_small', "Weaver's Cottage", 'weaver', 'small', 'work', 7, 7,
                    [[G.W('loom', ['north']), G.K('cauldron', ['north']), G.C, G.C]], [2], deco, roof_shape='hip')


@override('weaver_large')
def weaver_large():
    def deco(bp, h):
        wool_line(bp, -6, -1, 2)
        wool_line(bp, -6, -1, 5, ('blue_wool', 'white_wool', 'green_wool'))
    return std_deco('weaver_large', 'Weaving Hall', 'weaver', 'large', 'work', 9, 7,
                    [[G.W('loom', ['north']), G.K('loom', ['north']), G.K('cauldron'), G.C, G.C], [G.K('white_wool'), G.K('white_wool'), G.BR]],
                    [2, 1], deco, storeys=2, jetty=True, chimney='east')


@override('infirmary_small')
def infirmary_small():
    def deco(bp, h):
        herb_bed(bp, -5, 1, 3, 2)
        herb_bed(bp, -5, 5, 3, 1)
        bench(bp, 8, 8, 'north')
    return std_deco('infirmary_small', "Healer's House", 'infirmary', 'common', 'common', 9, 7,
                    [[G.B('white', ['north']), G.B('white', ['north']), G.W('cauldron'), G.C]], [2], deco, chimney='west',
                    roof_shape='hip') if False else std_deco('infirmary_small', "Healer's House", 'infirmary', 'small', 'common', 9, 7,
                    [[G.B('white', ['north']), G.B('white', ['north']), G.W('cauldron'), G.C]], [2], deco, chimney='west',
                    roof_shape='hip')


@override('infirmary_large')
def infirmary_large():
    def deco(bp, h):
        herb_bed(bp, 12, 1, 3, 2)
        herb_bed(bp, 12, 5, 3, 1)
        bench(bp, 1, 9, 'north'); bench(bp, 2, 9, 'north')
    return std_deco('infirmary_large', 'Infirmary', 'infirmary', 'large', 'common', 11, 7,
                    [[G.W('cauldron', ['north']), G.K('brewing_stand', ['north']), G.C, G.BR, G.T(2)], [G.B(), G.B(), G.B(), G.B()]],
                    [1, 1], deco, lantern=[True, False], storeys=2, jetty=True, chimney='west', door_x=6)


@override('fletcher_small')
def fletcher_small():
    def deco(bp, h):
        for z in (2, 5):
            archery_butt(bp, -6, z)
        G.drying_rack(bp, 8, 3)
    return std_deco('fletcher_small', "Fletcher's Shop", 'fletcher', 'small', 'military', 7, 7,
                    [[G.W('fletching_table', ['north']), G.K('crafting_table', ['north']), G.C, G.C]], [2], deco)


@override('fletcher_large')
def fletcher_large():
    def deco(bp, h):
        fence_ring(bp, 10, -1, 17, 8, gates={(13, 8)}, lantern_posts={(17, 8)})
        for z in (1, 3, 5):
            archery_butt(bp, 16, z)
    return std_deco('fletcher_large', "Bowyer's Hall", 'fletcher', 'large', 'military', 9, 7,
                    [[G.W('fletching_table', ['north']), G.K('crafting_table', ['north']), G.C, G.C, G.BR, G.T(1)]], [2], deco,
                    chimney='west', stone_ground=True)


@override('armoury_small')
def armoury_small():
    def deco(bp, h):
        spear_rack(bp, 10, 2, 3)
        spear_rack(bp, 10, 4, 3)
        banner_pole(bp, 4, 9, 'red')
    return std_deco('armoury_small', 'Arms Store', 'armoury', 'small', 'military', 9, 7,
                    [[G.W('smithing_table', ['north']), G.C, G.C, G.BR, G.BR]], [2], deco, stone_ground=True, roof_shape='hip')


@override('armoury_large')
def armoury_large():
    def deco(bp, h):
        spear_rack(bp, -5, 2, 3); spear_rack(bp, -5, 5, 3)
        banner_pole(bp, 2, 9, 'red'); banner_pole(bp, 8, 9, 'red')
    return std_deco('armoury_large', 'Armoury', 'armoury', 'large', 'military', 11, 7,
                    [[G.W('smithing_table', ['north']), G.C, G.C, G.C, G.C, G.BR, G.BR]], [2], deco, lantern=[True],
                    stone_all=True, roof='cobblestone', chimney='east', door_x=5, style='stone')


@override('library_small')
def library_small():
    def deco(bp, h):
        bench(bp, 1, 8, 'north'); bench(bp, 7, 8, 'north')
        garden_bed(bp, 10, 1, 2, 4)
    return std_deco('library_small', 'Reading Room', 'library', 'small', 'common', 9, 7,
                    [[G.K('bookshelf', ['north'])] * 7 + [G.K('bookshelf', ['west']), G.W('lectern', ['east']), G.T(2)]], [3], deco,
                    H=4, roof_shape='hip')


@override('library_large')
def library_large():
    def deco(bp, h):
        garden_bed(bp, -4, 1, 2, 5)
        bench(bp, 2, 9, 'north'); bench(bp, 9, 9, 'north')
    return std_deco('library_large', 'Library', 'library', 'large', 'common', 11, 7,
                    [[G.K('bookshelf', ['north'])] * 5 + [G.W('lectern', ['east']), G.T(3)],
                     [G.K('bookshelf', ['north'])] * 6 + [G.K('bookshelf', ['east', 'west'])] * 3 + [G.T(2)]], [2, 1], deco,
                    storeys=2, jetty=True, chimney='west', door_x=6)


@override('market_large')
def market_large():
    def deco(bp, h):
        for (x0, c) in ((-5, 'red_wool'), (14, 'blue_wool')):
            for (x, z) in ((x0, 2), (x0 + 3, 2), (x0, 5), (x0 + 3, 5)):
                for y in (1, 2):
                    put(bp, x, y, z, 'spruce_fence')
            for x in range(x0, x0 + 4):
                for z in range(2, 6):
                    put(bp, x, 3, z, c if (x + z) % 2 else 'white_wool')
            crates(bp, [(x0 + 1, 1, 3), (x0 + 2, 1, 4)])
    return std_deco('market_large', 'Guild Market', 'market', 'large', 'common', 13, 9,
                    [[G.W('barrel', ['north']), G.BR, G.BR, G.BR, G.BR, G.BR, G.C, G.C, G.T(2)]], [2], deco, lantern=[True],
                    stone_ground=True, door_x=6, chimney='east', roof_shape='hip', awning=True)


@override('trading_post_small')
def trading_post_small():
    def deco(bp, h):
        # a trader's cart and crates
        for x in (8, 9, 10):
            put(bp, x, 1, 5, 'spruce_slab', type='top')
        put(bp, 8, 1, 4, 'spruce_fence'); put(bp, 10, 1, 4, 'spruce_fence') if False else None
        crates(bp, [(9, 2, 5), (8, 1, 2), (8, 1, 3)])
        lamp(bp, 10, 2)
    return std_deco('trading_post_small', 'Trading Post', 'trading_post', 'small', 'common', 7, 6,
                    [[G.W('cartography_table', ['north']), G.C, G.C]], [1], deco, roof_shape='hip', awning=True)


@override('trading_post_large')
def trading_post_large():
    def deco(bp, h):
        crates(bp, [(10, 1, 1), (10, 1, 2), (11, 1, 1), (10, 2, 1)])
        banner_pole(bp, 11, 8, 'green')
    return std_deco('trading_post_large', 'Merchant House', 'trading_post', 'large', 'common', 9, 7,
                    [[G.W('cartography_table', ['north']), G.C, G.C, G.BR, G.BR, G.T(2)], [G.B('red', ['north']), G.C]], [1, 1], deco,
                    storeys=2, jetty=True, chimney='west', awning=True)


@override('architects_study_small')
def architects_study_small():
    def deco(bp, h):
        # a model-and-drafting yard: a scaffold mock-up and stone samples
        scaffold_frame(bp, 8, 1, 3)
        stack(bp, 8, 5, 'stone_bricks', 1); stack(bp, 9, 5, 'oak_planks', 1); stack(bp, 10, 5, 'bricks', 1)
    return std_deco('architects_study_small', "Architect's Study", 'architects_study', 'small', 'common', 7, 7,
                    [[G.W('lectern', ['north']), G.K('bookshelf', ['north']), G.K('bookshelf', ['north']), G.K('cartography_table')]], [2],
                    deco, H=4)


@override('architects_study_large')
def architects_study_large():
    def deco(bp, h):
        scaffold_frame(bp, 10, 1, 4)
        garden_bed(bp, -4, 1, 2, 4)
    return std_deco('architects_study_large', 'Drawing Office', 'architects_study', 'large', 'common', 9, 7,
                    [[G.W('lectern', ['north']), G.K('bookshelf', ['north']), G.K('bookshelf', ['north']), G.K('bookshelf', ['north']),
                      G.K('cartography_table'), G.T(2)], [G.K('bookshelf', ['north'])] * 3 + [G.C]], [2, 1], deco,
                    storeys=2, jetty=True, chimney='east')


@override('school_small')
def school_small():
    def deco(bp, h):
        # a bell post and a small fenced play yard
        for y in (1, 2, 3):
            put(bp, 11, y, 3, 'oak_log', axis='y')
        put(bp, 11, 4, 3, 'oak_log', axis='y')
        put(bp, 12, 4, 3, 'oak_log', axis='x'); put(bp, 12, 3, 3, 'lantern', hanging='true', waterlogged='false')
        fence_ring(bp, -8, 0, -2, 6, gates={(-5, 6)}, lantern_posts={(-8, 6)})
        bench(bp, -6, 2, 'east'); bench(bp, -6, 3, 'east')
    return std_deco('school_small', 'Schoolroom', 'school', 'small', 'common', 9, 7,
                    [[G.W('lectern', ['north']), G.K('lectern', ['north']), G.K('bookshelf', ['west'])] +
                     [G.K('bookshelf', ['west', 'east'])] * 3 + [G.T(2)]], [2], deco, H=4)


@override('school_large')
def school_large():
    def deco(bp, h):
        fence_ring(bp, -8, 0, -2, 7, gates={(-5, 7)}, lantern_posts={(-8, 7)})
        for y in range(1, 5):
            put(bp, 13, y, 3, 'oak_log', axis='y')
        put(bp, 14, 4, 3, 'oak_log', axis='x'); put(bp, 14, 3, 3, 'lantern', hanging='true', waterlogged='false')
    return std_deco('school_large', 'School', 'school', 'large', 'common', 11, 7,
                    [[G.W('lectern', ['north']), G.K('lectern', ['north']), G.K('bookshelf', ['west', 'east'])] +
                     [G.K('bookshelf', ['west', 'east'])] * 3 + [G.T(2), G.T(2)]], [2], deco, lantern=[True],
                    stone_ground=True, chimney='west', door_x=5, H=4, roof_shape='hip')


@override('fishery_large')
def fishery_large():
    bp, h = G.fishery('fishery_large', "Fisher's Hall", 'large', 11, 9, 1,
                      [[G.W('hearthstead:fish_rack', ['east']), G.K('hearthstead:fish_rack', ['east']), G.BR, G.C]], [2],
                      slip=(2, 5), door_x=8, chimney='east')
    for x in range(12, 18):
        for z in range(5, 8):
            bp.set(x, 0, z, 'oak_planks')
    for x in (13, 17):
        for z in (5, 7):
            bp.set(x, -1, z, 'oak_log', axis='y'); bp.set(x, 1, z, 'oak_fence')
    bp.set(17, 2, 5, 'lantern', hanging='false', waterlogged='false')
    G.drying_rack(bp, 14, 5, 3)
    crates(bp, [(14, 1, 7), (15, 1, 7)])
    bp.meta['preview_pond'] = [(x, 0, z) for x in range(11, 21) for z in range(1, 12) if not (12 <= x <= 17 and 5 <= z <= 7)]
    return bp, h


# ================================================================== battle-role halls (new types)

@addition
def pike_yard_small():
    def deco(bp, h):
        fence_ring(bp, -1, 8, 11, 14, gates={(5, 14)}, lantern_posts={(-1, 14), (11, 14)})
        for x in (2, 5, 8):
            dummy(bp, x, 11, 'north')
        spear_rack(bp, 1, 9, 3)
    return std_deco('pike_yard_small', 'Pike Yard', 'pike_yard', 'small', 'military', 11, 7,
                    [[G.B('white', ['north']), G.B('white', ['north']), G.K('hay_block'), G.K('hay_block'), G.C]], [2], deco,
                    chimney='east', door_x=5, roof_shape='hip')


@addition
def pike_yard_large():
    def deco(bp, h):
        fence_ring(bp, -1, 10, 13, 16, gates={(6, 16)}, lantern_posts={(-1, 16), (13, 16)})
        for x in (2, 4, 8, 10):
            dummy(bp, x, 13, 'north')
        banner_pole(bp, 6, 12, 'blue')
    return std_deco('pike_yard_large', 'Pike Hall', 'pike_yard', 'large', 'military', 13, 9,
                    [[G.K('hay_block'), G.K('hay_block'), G.K('hay_block'), G.C, G.C, G.T(2)],
                     [G.B(), G.B(), G.B(), G.B()]], [2, 1], deco, lantern=[True, False], storeys=2, stone_ground=True,
                    ceiling=True, door_x=6)


@addition
def sword_hall_small():
    def deco(bp, h):
        # a sparring ring of fence posts
        fence_ring(bp, -1, 8, 10, 13, gates={(4, 13)}, post=2, lantern_posts={(-1, 13), (10, 13)})
        dummy(bp, 4, 10, 'north')
    return std_deco('sword_hall_small', 'Sword Hall', 'sword_hall', 'small', 'military', 10, 7,
                    [[G.B('white', ['north']), G.B('white', ['north']), G.W('anvil', ['north']), G.K('grindstone'), G.C]], [2], deco,
                    stone_ground=True, chimney='east', door_x=5)


@addition
def sword_hall_large():
    def deco(bp, h):
        fence_ring(bp, -1, 10, 13, 16, gates={(6, 16)}, post=2, lantern_posts={(-1, 16), (13, 16)})
        for x in (3, 9):
            dummy(bp, x, 13, 'north')
        banner_pole(bp, 6, 12, 'gray')
    return std_deco('sword_hall_large', 'Hall of Blades', 'sword_hall', 'large', 'military', 13, 9,
                    [[G.W('anvil', ['north']), G.K('grindstone', ['north']), G.C, G.BR, G.T(2)], [G.B(), G.B(), G.B(), G.B()]],
                    [2, 1], deco, lantern=[True, False], storeys=2, stone_all=True, roof='cobblestone', ceiling=True,
                    door_x=6, style='stone')


def _rune_room(bp, h):
    # amethyst set into the back wall, glowing lanterns
    for x in (2, 3, 5, 6):
        bp.set(x, 2, 0, 'amethyst_block')


@addition
def rune_hall_small():
    def deco(bp, h):
        _rune_room(bp, h)
        for (x, z) in ((-2, 1), (9, 1)):
            put(bp, x, 0, z, 'stone_bricks'); put(bp, x, 1, z, 'amethyst_block'); put(bp, x, 2, z, 'lantern', hanging='false', waterlogged='false')
    return std_deco('rune_hall_small', 'Rune Hall', 'rune_hall', 'small', 'military', 9, 7,
                    [[G.W('enchanting_table'), G.K('bookshelf', ['west', 'east'])] + [G.K('bookshelf', ['west', 'east'])] * 3], [3],
                    deco, stone_all=True, roof='cobblestone', H=4, style='stone', gable_window=True)


@addition
def rune_hall_large():
    def deco(bp, h):
        _rune_room(bp, h)
        for (x, z) in ((-2, 1), (-2, 5), (11, 1), (11, 5)):
            put(bp, x, 0, z, 'stone_bricks'); put(bp, x, 1, z, 'stone_bricks'); put(bp, x, 2, z, 'amethyst_block')
    return std_deco('rune_hall_large', 'Rune Sanctum', 'rune_hall', 'large', 'military', 11, 7,
                    [[G.W('enchanting_table'), G.K('bookshelf', ['west', 'east'])] + [G.K('bookshelf', ['west', 'east'])] * 5 + [G.C]],
                    [3], deco, lantern=[True], stone_all=True, roof='cobblestone', H=4, chimney='east', style='stone')
