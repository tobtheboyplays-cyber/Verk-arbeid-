#!/usr/bin/env python3
"""Job-identity concept buildings (owner direction 26 Sep: "the buildings must
be UNIQUE to what the job is -- they don't even need a roof").

Inspiration: MineColonies huts (see plan/blueprints/MINECOLONIES-NOTES.md) --
ideas only, every block here is authored by this script.

Writes nothing into the data pack: concepts go to tools/blueprints/out/ for the
owner's approval sheet. Open-air types are validated with the proposed WORK
YARD survey (scanner.yard_scan); enclosed types with the normal room survey.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bplib import BP, connect_pass, save  # noqa: E402
import kit  # noqa: E402
from kit import building, furnish, lights  # noqa: E402
import scanner  # noqa: E402
import gen_town_blueprints as G  # noqa: E402

OUT = os.path.join(HERE, 'out')
CONCEPTS = []


def concept(fn):
    CONCEPTS.append(fn)
    return fn


def cbp(bid, name, btype, inspired, mode):
    bp = BP(bid, name=name, building_type=btype, variant='concept', category='work', kind='building',
            requires='builders_hut', style='elmfield', preset='Elmfield (job concept)')
    bp.meta['inspired'] = inspired
    bp.meta['validation'] = mode
    bp.meta['ground_level'] = 0
    return bp


# ------------------------------------------------------------------ props

def fence_ring(bp, x0, z0, x1, z1, gates=(), post=4, lantern_posts=(), wood='oak'):
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if not (x in (x0, x1) or z in (z0, z1)):
                continue
            if (x, 1, z) in bp.c:
                continue
            corner = x in (x0, x1) and z in (z0, z1)
            if (x, z) in gates:
                f = 'south' if z in (z0, z1) else 'east'
                bp.set(x, 1, z, f'{wood}_fence_gate', facing=f, open='false', in_wall='false', powered='false')
            elif corner or (z in (z0, z1) and (x - x0) % post == 0) or (x in (x0, x1) and (z - z0) % post == 0):
                bp.set(x, 1, z, f'{wood}_log', axis='y')
                bp.set(x, 2, z, f'{wood}_fence')
                if (x, z) in lantern_posts:
                    bp.set(x, 3, z, 'lantern', hanging='false', waterlogged='false')
            else:
                bp.set(x, 1, z, f'{wood}_fence')


def lamp_post(bp, x, z, h=2):
    for y in range(1, h + 1):
        bp.set(x, y, z, 'oak_fence')
    bp.set(x, h + 1, z, 'lantern', hanging='false', waterlogged='false')


def logs(bp, x0, z0, w, d, hgt, axis='z', wood='oak'):
    for x in range(x0, x0 + w):
        for z in range(z0, z0 + d):
            for y in range(1, hgt + 1):
                bp.set(x, y, z, f'{wood}_log', axis=axis)


def lean_to(bp, x0, x1, z0, depth=3, wood='oak', wall='oak_planks', back_posts=3):
    """A mono-pitch tool shelter: back wall on z0, open front, 3 clear blocks under."""
    zf = z0 + depth - 1
    for x in range(x0, x1 + 1):
        for y in range(1, 5):
            post = (x - x0) % back_posts == 0 or x == x1
            bp.set(x, y, z0, f'{wood}_log' if post else wall, **({'axis': 'y'} if post else {}))
        bp.set(x, 0, z0, 'cobblestone')
        for z in range(z0 + 1, zf + 1):
            for y in range(1, 4):
                if (x, y, z) not in bp.c:
                    bp.set(x, y, z, 'air')
    for z in range(z0 + 1, zf + 1):
        for x in (x0, x1):
            for y in range(1, 4):
                bp.set(x, y, z, f'{wood}_log' if z == zf else wall, **({'axis': 'y'} if z == zf else {}))
    for x in range(x0 - 1, x1 + 2):
        bp.set(x, 5, z0, f'{wood}_slab', type='bottom')
        for z in range(z0 + 1, zf + 1):
            bp.set(x, 4, z, f'{wood}_stairs', facing='north', half='bottom', shape='straight')
        bp.set(x, 3, zf + 1, f'{wood}_stairs', facing='north', half='bottom', shape='straight')


def chopping_block(bp, x, z):
    bp.set(x, 1, z, 'oak_log', axis='y')
    bp.set(x, 2, z, 'lever', face='floor', facing='east', powered='false')   # the axe in the block


def sawbuck(bp, x, z, wood='oak'):
    bp.set(x, 1, z, f'{wood}_fence'); bp.set(x + 2, 1, z, f'{wood}_fence')
    for dx in range(3):
        bp.set(x + dx, 2, z, 'oak_log', axis='x')


def dummy(bp, x, z, facing='south'):
    bp.set(x, 1, z, 'oak_fence')
    bp.set(x, 2, z, 'hay_block', axis='y')
    bp.set(x, 3, z, 'carved_pumpkin', facing=facing)


def banner_pole(bp, x, z, color='red'):
    for y in (1, 2, 3):
        bp.set(x, y, z, 'oak_fence')
    bp.set(x, 4, z, f'{color}_banner', rotation='0')


def spear_rack(bp, x0, z, n=3):
    for dx in range(n):
        bp.set(x0 + dx, 1, z, 'spruce_fence')
        bp.set(x0 + dx, 2, z, 'lightning_rod', facing='up', powered='false', waterlogged='false')


def crate(bp, x, y, z):
    bp.set(x, y, z, 'barrel', facing='up', open='false')


def plaque(bp, x, y, z, facing):
    bp.set(x, y, z, 'hearthstead:plaque', facing=facing, glow='empty', registered='false')
    bp.plaque = ((x, y, z), facing)


# ------------------------------------------------------------------ concepts

@concept
def lumber_camp_yard():
    """Open-air camp: log piles, chopping block with an axe, a sawbuck, a tool lean-to, a fenced yard."""
    bp = cbp('lumber_camp_yard', "Woodcutter's Camp", 'lumber_camp',
             'MineColonies Forester: log piles and a sawbuck say "lumber" from afar', 'yard')
    fence_ring(bp, 0, 0, 12, 10, gates={(6, 10)}, lantern_posts={(4, 10), (8, 10), (0, 0), (12, 0)})
    lean_to(bp, 3, 9, 0, depth=3)
    bp.set(4, 1, 1, 'crafting_table'); bp.work.append((4, 1, 2))
    bp.set(5, 1, 1, 'chest', facing='south', type='single', waterlogged='false')
    crate(bp, 7, 1, 1); crate(bp, 8, 1, 1)
    # sharpened_axes: the Lumberer whets his axe here (decorative; not a checklist item)
    bp.set(6, 1, 1, 'grindstone', face='floor', facing='east')
    bp.set(6, 3, 2, 'lantern', hanging='true', waterlogged='false')
    plaque(bp, 6, 2, 1, 'south')
    logs(bp, 10, 2, 2, 5, 2, 'z')
    logs(bp, 1, 3, 2, 4, 2, 'z', 'spruce')
    bp.set(1, 3, 4, 'spruce_log', axis='z'); bp.set(1, 3, 5, 'spruce_log', axis='z')
    chopping_block(bp, 4, 6)
    sawbuck(bp, 7, 7)
    for x in range(6, 7):
        for z in range(3, 11):
            bp.set(x, 0, z, 'dirt_path')
    bp.set(3, 1, 8, 'oak_log', axis='x'); bp.set(3, 1, 9, 'oak_log', axis='x')   # a felled trunk
    return bp


@concept
def mine_headframe():
    """A timber headframe over a lined shaft, rails to a spoil heap, an ore cart, a tool lean-to."""
    bp = cbp('mine_headframe', 'Mine Headframe', 'mine',
             'MineColonies Miner: the hut is a shaft head; the ladder going down is the identity', 'yard')
    fence_ring(bp, 0, 0, 12, 12, gates={(6, 12)}, lantern_posts={(4, 12), (8, 12)})
    # MINE V2: no pre-dug shaft (the Builder was trapped in it). A capped,
    # lined mouth; the ladder on the north face marks the shaft and the Miner
    # digs his own ladder shaft down from it (MinerWorkGoal shaft mode).
    for x in range(4, 8):
        for z in range(3, 7):
            bp.set(x, 0, z, 'cobblestone')
    for y in range(1, 4):
        bp.set(5, y, 4, 'ladder', facing='south', waterlogged='false')
    bp.set(5, 2, 3, 'oak_log', axis='y'); bp.set(5, 3, 3, 'oak_log', axis='y')
    for x in range(4, 8):
        for z in range(3, 7):
            edge = x in (4, 7) or z in (3, 6)
            if edge and (x, z) not in ((4, 3), (7, 3), (4, 6), (7, 6)):
                bp.set(x, 1, z, 'cobblestone' if (x, z) == (5, 3) else 'cobblestone_wall')
    bp.set(4, 1, 5, 'air'); bp.set(4, 1, 4, 'air')     # collar gap, west side
    # headframe: four legs, beams, a sheave beam and a hanging bucket
    for (x, z) in ((4, 3), (7, 3), (4, 6), (7, 6)):
        for y in range(1, 9):
            bp.set(x, y, z, 'oak_log', axis='y')
    for x in range(4, 8):
        bp.set(x, 9, 3, 'oak_log', axis='x'); bp.set(x, 9, 6, 'oak_log', axis='x')
        bp.set(x, 5, 3, 'oak_log', axis='x'); bp.set(x, 5, 6, 'oak_log', axis='x')
    for z in range(4, 6):
        bp.set(4, 9, z, 'oak_log', axis='z'); bp.set(7, 9, z, 'oak_log', axis='z')
    for x in range(4, 8):
        bp.set(x, 9, 5, 'stripped_oak_log', axis='x')
    for x in range(3, 9):
        for z in range(2, 8):
            if (x, 10, z) not in bp.c:
                bp.set(x, 10, z, 'oak_slab', type='bottom')
    for y in range(3, 9):
        bp.set(6, y, 5, 'chain', axis='y', waterlogged='false')
    bp.set(6, 2, 5, 'lantern', hanging='true', waterlogged='false')
    bp.set(4, 8, 4, 'lantern', hanging='true', waterlogged='false')
    bp.set(7, 8, 5, 'lantern', hanging='true', waterlogged='false')
    # rails from the collar to the spoil heap, an ore cart at the end
    for z in range(4, 10):
        bp.set(3, 1, z, 'rail', shape='north_south', waterlogged='false')
    bp.set(3, 1, 10, 'hopper', facing='down', enabled='true')
    for (x, z, h) in ((9, 8, 2), (10, 8, 3), (11, 8, 2), (9, 9, 1), (10, 9, 2), (11, 9, 1), (10, 10, 1), (10, 7, 1)):
        for y in range(1, h + 1):
            bp.set(x, y, z, ['cobblestone', 'andesite', 'coarse_dirt', 'cobblestone'][(x + y + z) % 4])
    bp.set(10, 3 + 1, 8, 'coal_ore') if False else None
    # tool lean-to
    lean_to(bp, 8, 11, 0, depth=3)
    bp.set(9, 1, 1, 'chest', facing='south', type='single', waterlogged='false')
    crate(bp, 10, 1, 1)
    plaque(bp, 10, 2, 1, 'south')
    bp.work.append((5, 1, 3))
    for z in range(7, 13):
        bp.set(6, 0, z, 'dirt_path')
    lamp_post(bp, 2, 11)
    return bp


@concept
def fishery_dock():
    """Boathouse with the indoor basin + a jetty over the lake with drying racks, nets, crates."""
    bp, h = G.fishery('fishery_dock', 'Fishing Dock', 'concept', 10, 9, 1,
                      [[G.W('hearthstead:fish_rack', ['east']), G.BR]], [1], slip=(2, 5), door_x=7)
    bp.meta.update(variant='concept', preset='Elmfield (job concept)', validation='room',
                   inspired='MineColonies Fisher: a hut on a dock over open water')
    D = 9
    # jetty east over the lake (void below = the site's water stays)
    for x in range(10, 17):
        for z in range(5, 8):
            bp.set(x, 0, z, 'oak_planks' if x < 16 else 'oak_slab', **({} if x < 16 else {'type': 'top'}))
    for x in (11, 14, 16):
        for z in (5, 7):
            bp.set(x, -1, z, 'oak_log', axis='y')
            bp.set(x, 1, z, 'oak_fence')
    bp.set(11, 2, 5, 'lantern', hanging='false', waterlogged='false')
    bp.set(16, 2, 7, 'lantern', hanging='false', waterlogged='false')
    # drying rack with fish (trapdoors on a fence frame) and a net between posts
    for x in (12, 13):
        bp.set(x, 1, 5, 'oak_fence'); bp.set(x, 2, 5, 'oak_fence')
        bp.set(x, 3, 5, 'spruce_slab', type='bottom')
    bp.set(15, 1, 7, 'cobweb'); bp.set(15, 2, 7, 'cobweb') if False else None
    crate(bp, 12, 1, 7); crate(bp, 13, 1, 7)
    bp.set(16, 1, 6, 'hearthstead:fishers_chair', facing='east')
    bp.meta['preview_pond'] = [(x, 0, z) for x in range(9, 20) for z in range(1, 12) if not (x < 16 and 5 <= z <= 7 and x >= 10)]
    # shore props
    G.drying_rack(bp, 1, D + 1, 3)
    crate(bp, 5, 1, D); crate(bp, 5, 2, D) if False else None
    return bp


@concept
def smithy_forge():
    """Open-fronted stone forge under a canopy, a big brick chimney, an anvil, a quench cauldron."""
    bp = cbp('smithy_forge', 'Open Forge', 'smithy',
             'MineColonies Blacksmith: an open forge with a chimney you can see from the square', 'yard')
    X0, X1, Z0, ZF = 0, 8, 0, 4
    for x in range(X0, X1 + 1):
        for z in range(Z0, ZF + 1):
            bp.set(x, 0, z, 'cobblestone' if (x + z) % 5 else 'mossy_cobblestone')
            for y in range(1, 5):
                wall = z == Z0 or x in (X0, X1)
                front_post = z == ZF and x in (X0, X1, 4)
                if front_post:
                    bp.set(x, y, z, 'oak_log', axis='y')
                elif wall:
                    bp.set(x, y, z, 'cobblestone' if (x * 7 + y * 3 + z) % 6 else 'mossy_cobblestone')
                else:
                    bp.set(x, y, z, 'air')
    for x in range(X0, X1 + 1):
        bp.set(x, 5, ZF, 'oak_log', axis='x'); bp.set(x, 5, Z0, 'oak_log', axis='x')
    # gable roof, ridge along x
    for x in range(X0 - 1, X1 + 2):
        for z, y, f in ((Z0 - 1, 5, 'south'), (Z0, 6, 'south'), (Z0 + 1, 7, 'south'), (ZF - 1, 7, 'north'), (ZF, 6, 'north'), (ZF + 1, 5, 'north')):
            bp.set(x, y, z, 'spruce_stairs', facing=f, half='bottom', shape='straight')
        bp.set(x, 8, 2, 'spruce_slab', type='bottom')
        for y in (5, 6, 7):
            if 0 < x < X1:
                for z in range(1, 4):
                    if (x, y, z) not in bp.c:
                        bp.set(x, y, z, 'air')
    for x in (X0, X1):
        for (z, top) in ((1, 6), (2, 7), (3, 6)):
            for y in range(5, top + 1):
                bp.set(x, y, z, 'cobblestone')
    # the chimney stack: 2x2 bricks behind the forge, rising above the ridge
    for x in (3, 4):
        for z in (-2, -1):
            for y in range(0, 12):
                bp.set(x, y, z, 'bricks')
    bp.set(3, 12, -2, 'campfire', lit='true', signal_fire='false', facing='south', waterlogged='false')
    # forge hearth inside the back wall, anvil under the canopy, quench cauldron
    bp.set(3, 1, 0, 'blast_furnace', facing='south', lit='false')
    bp.set(4, 1, 0, 'furnace', facing='south', lit='false')
    bp.set(3, 2, 0, 'bricks'); bp.set(4, 2, 0, 'bricks')
    bp.set(4, 1, 2, 'anvil', facing='east'); bp.work.append((4, 1, 3))
    bp.set(6, 1, 1, 'cauldron')
    bp.set(1, 1, 1, 'smithing_table')
    bp.set(7, 1, 1, 'grindstone', face='floor', facing='south')
    bp.set(1, 1, 3, 'chest', facing='east', type='single', waterlogged='false')
    bp.set(7, 1, 3, 'chest', facing='west', type='single', waterlogged='false')
    for x in range(1, 8):
        bp.set(x, 5, 2, 'oak_log', axis='x')          # a tie beam across the forge
    bp.set(2, 4, 2, 'lantern', hanging='true', waterlogged='false')
    bp.set(6, 4, 2, 'lantern', hanging='true', waterlogged='false')
    plaque(bp, 5, 2, 1, 'south')
    # the smithy yard in front
    fence_ring(bp, -1, 4, 9, 9, gates={(4, 9)}, lantern_posts={(-1, 9), (9, 9)})
    for z in range(5, 10):
        bp.set(4, 0, z, 'dirt_path')
    logs(bp, 0, 6, 1, 3, 1, 'z', 'spruce')
    crate(bp, 8, 1, 6); crate(bp, 8, 1, 7); bp.set(8, 2, 6, 'coal_block')
    bp.set(1, 1, 8, 'iron_block') if False else None
    return bp


@concept
def pasture_barn():
    """A gambrel-roofed barn, big double doors in the gable, a hay-loft hoist, a paddock with a trough."""
    bp = cbp('pasture_barn', 'Barn & Paddock', 'pasture',
             'MineColonies Farmer/Herder: a barn + yard read as "animals" at a glance', 'room')
    X, Z = 10, 11          # x 0..9, z 0..10, ridge along z, gable doors at z=10
    T = 5
    for x in range(X):
        for z in range(Z):
            edge = x in (0, X - 1) or z in (0, Z - 1)
            bp.set(x, 0, z, 'cobblestone' if edge else 'oak_planks')
            for y in range(1, T):
                if not edge:
                    bp.set(x, y, z, 'air')
                    continue
                post = (x in (0, X - 1) and z % 3 == 1) or (z in (0, Z - 1) and x in (0, 3, 6, X - 1))
                bp.set(x, y, z, 'oak_log' if post else 'spruce_planks', **({'axis': 'y'} if post else {}))
            bp.set(x, T, z, 'oak_log' if edge else 'spruce_planks', **({'axis': 'z' if x in (0, X - 1) else 'x'} if edge else {}))   # hay-loft floor
    # gambrel: steep lower pitch (2 per step), shallow upper (1 per step)
    prof = [0, 2, 4, 5, 6]
    for x in range(-1, X + 1):
        a = min(x + 1, X - x)
        hh = T + prof[min(a, len(prof) - 1)]
        prev = T + prof[a - 1] if a >= 1 else T
        f = 'east' if x < X / 2 else 'west'
        for z in range(-1, Z + 1):
            bp.set(x, hh, z, 'dark_oak_stairs', facing=f, half='bottom', shape='straight')
            for y in range(prev + 1, hh):
                bp.set(x, y, z, 'dark_oak_planks')
            if 0 < x < X - 1 and 0 < z < Z - 1:
                for y in range(T + 1, hh):
                    if (x, y, z) not in bp.c:
                        bp.set(x, y, z, 'air')
    for z in (0, Z - 1):
        for x in range(0, X):
            a = min(x + 1, X - x)
            hh = T + prof[min(a, len(prof) - 1)]
            for y in range(T + 1, hh):
                if bp.name(x, y, z) in (None, 'minecraft:air'):
                    bp.set(x, y, z, 'spruce_planks')
    # big double doors + hay-loft doors + hoist beam
    bp.door(4, 1, Z - 1, 'north', hinge='left', kind='spruce_door')
    bp.door(5, 1, Z - 1, 'north', hinge='right', kind='spruce_door')
    for y in (1, 2):
        bp.set(3, y, Z - 1, 'oak_log', axis='y'); bp.set(6, y, Z - 1, 'oak_log', axis='y')
    for x in range(3, 7):
        bp.set(x, 3, Z - 1, 'oak_log', axis='x')
    for x in (4, 5):
        bp.set(x, 7, Z - 1, 'spruce_trapdoor', facing='south', half='bottom', open='true', powered='false', waterlogged='false')
        bp.set(x, 7, Z - 2, 'spruce_planks')
    for z in range(Z - 1, Z + 2):
        bp.set(4, 9, z, 'oak_log', axis='z')
    bp.set(4, 8, Z + 1, 'chain', axis='y', waterlogged='false')
    bp.set(4, 7, Z + 1, 'hay_block', axis='y')
    for z in (3, 7):
        bp.set(0, 2, z, 'glass_pane'); bp.set(X - 1, 2, z, 'glass_pane')
    # interior: hay, storage, a light
    for (x, z) in ((1, 1), (2, 1), (1, 2), (8, 1)):
        bp.set(x, 1, z, 'hay_block', axis='y')
    bp.set(1, 2, 1, 'hay_block', axis='y')
    bp.set(8, 1, 2, 'chest', facing='west', type='single', waterlogged='false')
    bp.set(8, 1, 3, 'barrel', facing='up', open='false')
    bp.set(4, 4, 5, 'lantern', hanging='true', waterlogged='false') if False else None
    bp.set(1, 2, 5, 'wall_torch', facing='east'); bp.set(8, 2, 6, 'wall_torch', facing='west')
    plaque(bp, 6, 2, Z - 2, 'north')
    bp.work.append((2, 1, 2))
    # paddock east with a trough and a hay feeder
    fence_ring(bp, X + 1, 1, X + 8, Z - 1, gates={(X + 4, Z - 1)}, lantern_posts={(X + 1, Z - 1)})
    for z in (4, 5, 6):
        bp.set(X + 7, 1, z, 'cauldron')
    bp.set(X + 3, 1, 3, 'hay_block', axis='y')
    bp.set(X, 1, 5, 'oak_fence_gate', facing='east', open='false', in_wall='false', powered='false') if False else None
    for z in range(Z, Z + 2):
        bp.set(4, 0, z, 'dirt_path'); bp.set(5, 0, z, 'dirt_path')
    bp.meta['eave_y'] = T
    return bp


@concept
def mill_windmill():
    """A real windmill: stone base, timber body, a pyramid cap, four canvas sails on the east face."""
    bp = cbp('mill_windmill', 'Windmill', 'mill',
             'MineColonies-style signature silhouette: the sails ARE the building', 'room')
    for x in range(7):
        for z in range(7):
            edge = x in (0, 6) or z in (0, 6)
            bp.set(x, 0, z, 'cobblestone' if edge else 'oak_planks')
            for y in range(1, 5):
                if edge:
                    corner = x in (0, 6) and z in (0, 6)
                    bp.set(x, y, z, 'stone_bricks' if corner else ('cobblestone' if (x + y + z) % 7 else 'mossy_cobblestone'))
                else:
                    bp.set(x, y, z, 'air')
            bp.set(x, 5, z, 'oak_planks')
    # skirt roof round the base, timber upper body, pyramid cap
    for x in range(-1, 8):
        for z in range(-1, 8):
            if x in (-1, 7) or z in (-1, 7):
                f = 'south' if z == -1 else 'north' if z == 7 else 'east' if x == -1 else 'west'
                bp.set(x, 5, z, 'spruce_stairs', facing=f, half='bottom', shape='straight')
    for x in range(1, 6):
        for z in range(1, 6):
            edge = x in (1, 5) or z in (1, 5)
            for y in range(6, 11):
                if edge:
                    corner = x in (1, 5) and z in (1, 5)
                    bp.set(x, y, z, 'oak_log' if corner else 'oak_planks', **({'axis': 'y'} if corner else {}))
                else:
                    bp.set(x, y, z, 'air')
    for k, y in enumerate(range(11, 14)):
        lo, hi = 0 + k, 6 - k
        for x in range(lo, hi + 1):
            for z in range(lo, hi + 1):
                if x in (lo, hi) or z in (lo, hi):
                    f = 'south' if z == lo else 'north' if z == hi else 'east' if x == lo else 'west'
                    bp.set(x, y, z, 'spruce_stairs', facing=f, half='bottom', shape='straight')
                else:
                    bp.set(x, y, z, 'air') if (x, y, z) not in bp.c else None
    bp.set(3, 14, 3, 'spruce_slab', type='bottom')
    bp.set(3, 8, 1, 'glass_pane'); bp.set(3, 8, 5, 'glass_pane'); bp.set(1, 8, 3, 'glass_pane')
    # sails on the east face
    bp.set(6, 8, 3, 'oak_log', axis='x'); bp.set(7, 8, 3, 'oak_log', axis='x')
    hx = 8
    for k in range(1, 6):
        bp.set(hx, 8 + k, 3, 'oak_fence'); bp.set(hx, 8 - k, 3, 'oak_fence')
        bp.set(hx, 8, 3 + k, 'oak_fence'); bp.set(hx, 8, 3 - k, 'oak_fence')
        if k >= 2:
            bp.set(hx, 8 + k, 4, 'white_wool'); bp.set(hx, 8 - k, 2, 'white_wool')
            bp.set(hx, 7, 3 + k, 'white_wool'); bp.set(hx, 9, 3 - k, 'white_wool')
    bp.set(hx, 8, 3, 'oak_log', axis='x')
    # the milling room
    bp.door(3, 1, 6, 'north')
    plaque(bp, 4, 2, 5, 'north')
    bp.set(3, 1, 2, 'grindstone', face='floor', facing='east'); bp.work.append((3, 1, 3))
    bp.set(1, 1, 1, 'barrel', facing='up', open='false'); bp.set(5, 1, 1, 'chest', facing='south', type='single', waterlogged='false')
    bp.set(1, 1, 2, 'barrel', facing='up', open='false')
    bp.set(3, 4, 3, 'lantern', hanging='true', waterlogged='false')
    bp.set(0, 2, 3, 'glass_pane'); bp.set(6, 2, 3, 'glass_pane')
    for z in range(7, 9):
        bp.set(3, 0, z, 'dirt_path')
    G.hay_stack(bp, 1, 8); G.hay_stack(bp, -1, 5, 1)
    crate(bp, 5, 1, 7)
    bp.meta['eave_y'] = 5
    return bp


@concept
def tannery_yard():
    """Soaking vats, hides stretched on frames, a drying line and a shed: a smelly working yard."""
    bp = cbp('tannery_yard', "Tanner's Yard", 'tannery',
             'MineColonies crafter huts: the yard props (vats, frames) show the trade', 'yard')
    fence_ring(bp, 0, 0, 12, 10, gates={(6, 10)}, lantern_posts={(0, 10), (12, 10)}, wood='spruce')
    lean_to(bp, 1, 6, 0, depth=3, wood='spruce', wall='spruce_planks')
    bp.set(2, 1, 1, 'chest', facing='south', type='single', waterlogged='false')
    bp.set(3, 1, 1, 'chest', facing='south', type='single', waterlogged='false')
    crate(bp, 5, 1, 1)
    bp.set(4, 3, 2, 'lantern', hanging='true', waterlogged='false')
    plaque(bp, 4, 2, 1, 'south')
    # soaking vats on a stone pad
    for x in range(7, 12):
        for z in range(2, 5):
            bp.set(x, 0, z, 'cobblestone')
    for x in (8, 9, 10):
        bp.set(x, 1, 3, 'cauldron')
    bp.work.append((9, 1, 4))
    # hides stretched on frames
    for (x0, z) in ((7, 7), (1, 6)):
        for x in (x0, x0 + 3):
            for y in (1, 2, 3):
                bp.set(x, y, z, 'spruce_fence')
        for x in range(x0, x0 + 4):
            bp.set(x, 4, z, 'spruce_slab', type='bottom')
        for x in (x0 + 1, x0 + 2):
            for y in (2, 3):
                bp.set(x, y, z, 'spruce_trapdoor', facing='north', half='bottom', open='true', powered='false', waterlogged='false')
    # smelly ground and a composter
    for (x, z) in ((3, 8), (4, 8), (4, 9), (8, 5), (9, 6), (2, 4)):
        bp.set(x, 0, z, 'coarse_dirt')
    bp.set(11, 1, 9, 'composter', level='3')
    crate(bp, 11, 1, 8)
    for z in range(5, 11):
        bp.set(6, 0, z, 'dirt_path')
    return bp


@concept
def barracks_drill_yard():
    """Barracks with a drill yard: straw dummies, targets, a spear rack and the town banner."""
    bp = G.bp_('barracks_drill_yard', 'Barracks & Drill Yard', 'barracks', 'concept', 'military')
    h = building(bp, 11, 7, chimney='east', door_x=5, roof_shape='hip')
    furnish(h, 0, [G.B('white', ['north']), G.B('white', ['north']), G.C, G.C, G.T(2)])
    lights(h, 0, 2)
    bp.meta.update(style='elmfield', preset='Elmfield (job concept)', validation='room',
                   inspired='MineColonies Barracks/Guard tower: the training yard makes it military')
    fence_ring(bp, -1, 8, 11, 15, gates={(5, 15)}, lantern_posts={(-1, 15), (11, 15)})
    for (x, z) in ((1, 12), (3, 12), (8, 12)):
        dummy(bp, x, z, 'north')
    bp.set(10, 1, 10, 'target'); bp.set(10, 2, 10, 'target') if False else None
    bp.set(0, 1, 10, 'target')
    spear_rack(bp, 7, 9, 3)
    banner_pole(bp, 5, 11, 'red')
    bp.chair(1, 1, 9, 'south'); bp.chair(2, 1, 9, 'south')
    for z in range(7, 16):
        bp.set(5, 0, z, 'dirt_path')
    bp.set(5, 0, 11, 'cobblestone')
    return bp


@concept
def mason_yard():
    """A paved stone yard: cut blocks stacked, a stonecutter under a lean-to, a timber crane lifting a block."""
    bp = cbp('mason_yard', "Masons' Yard", 'mason',
             'MineColonies Stonemason: a yard of cut blocks + a crane reads "masonry"', 'yard')
    for x in range(1, 12):
        for z in range(1, 10):
            bp.set(x, 0, z, 'stone_bricks' if (x + z) % 3 else 'cobblestone')
    for x in range(0, 13):
        for z in (0, 10):
            bp.set(x, 1, z, 'cobblestone_wall')
    for z in range(0, 11):
        bp.set(0, 1, z, 'cobblestone_wall'); bp.set(12, 1, z, 'cobblestone_wall')
    for (x, z) in ((0, 0), (12, 0), (0, 10), (12, 10), (4, 10), (8, 10)):
        bp.set(x, 1, z, 'stone_bricks'); bp.set(x, 2, z, 'stone_bricks')
    bp.set(4, 3, 10, 'lantern', hanging='false', waterlogged='false'); bp.set(8, 3, 10, 'lantern', hanging='false', waterlogged='false')
    bp.set(6, 1, 10, 'oak_fence_gate', facing='south', open='false', in_wall='false', powered='false')
    lean_to(bp, 1, 6, 0, depth=3, wood='spruce', wall='cobblestone')
    bp.set(3, 1, 1, 'stonecutter', facing='south'); bp.work.append((3, 1, 2))
    bp.set(2, 1, 1, 'chest', facing='south', type='single', waterlogged='false')
    bp.set(5, 1, 1, 'chest', facing='south', type='single', waterlogged='false')
    bp.set(4, 3, 2, 'lantern', hanging='true', waterlogged='false')
    plaque(bp, 4, 2, 1, 'south')
    # block stacks
    for (x, z, h, b) in ((9, 2, 2, 'stone'), (10, 2, 2, 'stone'), (9, 3, 1, 'stone'), (10, 3, 2, 'andesite'),
                         (2, 7, 2, 'stone_bricks'), (3, 7, 1, 'stone_bricks'), (2, 8, 1, 'cobblestone')):
        for y in range(1, h + 1):
            bp.set(x, y, z, b)
    # the crane: mast, jib, chain, a hanging block
    for y in range(1, 9):
        bp.set(8, y, 6, 'spruce_log', axis='y')
    for x in range(7, 12):
        bp.set(x, 9, 6, 'spruce_log', axis='x')
    bp.set(9, 8, 6, 'spruce_fence'); bp.set(7, 8, 6, 'spruce_fence')
    for y in range(5, 9):
        bp.set(11, y, 6, 'chain', axis='y', waterlogged='false')
    bp.set(11, 4, 6, 'stone_bricks')
    bp.set(8, 1, 7, 'barrel', facing='up', open='false')  # the winch drum
    return bp


@concept
def bakery_oven():
    """A bakehouse with a big brick bread oven built onto the gable, a flour store and a bench."""
    bp = G.bp_('bakery_oven', 'Bakehouse & Bread Oven', 'bakery', 'concept', 'work')
    h = building(bp, 7, 6, door_x=3)
    L = 7
    # the two ovens sit IN the east wall, facing into the room; the dome wraps them outside
    bp.set(L - 1, 1, 2, 'furnace', facing='west', lit='false')
    bp.set(L - 1, 1, 3, 'smoker', facing='west', lit='false')
    bp.work.append((L - 2, 1, 2))
    for x in range(L, L + 3):
        for z in range(1, 5):
            bp.set(x, 0, z, 'cobblestone')
            for y in (1, 2):
                bp.set(x, y, z, 'bricks')
        for z in range(1, 5):
            f = 'south' if z == 1 else 'north' if z == 4 else None
            if f:
                bp.set(x, 3, z, 'brick_stairs', facing=f, half='bottom', shape='straight')
            else:
                bp.set(x, 3, z, 'bricks')
    for z in range(1, 5):
        bp.set(L + 2, 3, z, 'brick_stairs', facing='west', half='bottom', shape='straight')
    for y in range(3, 9):
        bp.set(L + 1, y, 2, 'bricks')
    bp.set(L + 1, 9, 2, 'campfire', lit='true', signal_fire='false', facing='south', waterlogged='false')
    furnish(h, 0, [G.C, G.BR, G.T(2)])
    lights(h, 0, 1)
    bp.meta.update(style='elmfield', preset='Elmfield (job concept)', validation='room',
                   inspired='MineColonies Baker: the oven is the building\'s face')
    # flour store: sacks (white wool) and barrels under a little slab roof
    for (x, z) in ((-2, 1), (-2, 2), (-2, 3)):
        crate(bp, x, 1, z)
    bp.set(-2, 2, 1, 'white_wool'); bp.set(-2, 2, 2, 'white_wool')
    for z in range(0, 5):
        bp.set(-2, 3, z, 'spruce_slab', type='bottom'); bp.set(-3, 3, z, 'spruce_slab', type='bottom')
    bp.set(-3, 1, 0, 'spruce_fence'); bp.set(-3, 2, 0, 'spruce_fence'); bp.set(-3, 1, 4, 'spruce_fence'); bp.set(-3, 2, 4, 'spruce_fence')
    bp.chair(5, 1, 6, 'south')
    return bp


# ------------------------------------------------------------------ check + export

def check(bp, reqs):
    if bp.meta.get('validation') != 'yard':
        return G.check(bp, reqs)
    rep = {'id': bp.id, 'type': bp.meta.get('building_type'), 'kind': 'building'}
    world = scanner.World(bp.c, 0)
    f = scanner.FACE[bp.plaque[1]]
    p = bp.plaque[0]
    res = scanner.yard_scan(world, (p[0] + f[0], p[1], p[2] + f[2]))
    rq = reqs[bp.meta['building_type']]
    lines = [f"YARD bounded={res['enclosed']} area={res['volume']} covered={res['covered']} gates={res['doors']}"]
    ok = res['enclosed'] and res['covered'] >= 4
    for q in rq:
        have, need = scanner.measure(q, res)
        lines.append(f"{scanner.req_name(q)} {have}/{need}")
        ok &= have >= need
    rep['l1'] = 'PASS' if ok else 'FAIL'
    rep['survey'] = lines
    # walk: from outside the gate to every job block / container in the yard
    gate = next(p for p, (n, pr) in bp.c.items() if n.endswith('_fence_gate'))
    reach = scanner.walk_check(world, [(gate[0], 1, gate[2] + 1), (gate[0], 1, gate[2] - 1)], None)
    missing = []
    for p, (n, pr) in bp.c.items():
        s = n.split(':')[1]
        if s in ('chest', 'barrel', 'crafting_table', 'stonecutter', 'anvil', 'smithing_table', 'grindstone',
                 'cauldron', 'water_cauldron', 'blast_furnace', 'furnace') and p[1] == 1:
            near = [(p[0] + dx, 1, p[2] + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))]
            if not any(c in reach for c in near):
                missing.append(f'{s}@{p}')
    rep['walk'] = 'OK' if not missing else 'FAIL'
    rep['walk_missing'] = missing
    if missing:
        rep['survey'].append('unreachable: ' + ','.join(missing))
    return rep


def main():
    reqs = scanner.parse_requirements()
    cdir = os.path.join(OUT, 'cells')
    report = []
    for fn in CONCEPTS:
        bp = fn()
        kit.stairs_shape_pass(bp)
        connect_pass(bp)
        rep = check(bp, reqs)
        meta, origin = save(bp, os.path.join(OUT, 'concept_pack'))
        rep.update(size=meta['size'], blocks=meta['block_count'], name=meta['name'], category=meta['category'],
                   variant='concept', preset=meta.get('preset'), style='concept', inspired=bp.meta.get('inspired'),
                   validation=bp.meta.get('validation'))
        pond = bp.meta.get('preview_pond')
        if pond:
            bp.meta['test_pond'] = {'cells': [tuple(q) for q in pond]}
        G.export_cells(bp, origin, os.path.join(cdir, bp.id + '.json'))
        report.append(rep)
        print(f"{bp.id:22s} {meta['building_type']:12s} {'x'.join(map(str, meta['size'])):9s} blocks={meta['block_count']:4d} "
              f"{rep['l1']} walk={rep.get('walk')} | " + ' | '.join(rep.get('survey', [])[:9]))
    json.dump(report, open(os.path.join(OUT, 'concepts_report.json'), 'w'), indent=1)


if __name__ == '__main__':
    main()
