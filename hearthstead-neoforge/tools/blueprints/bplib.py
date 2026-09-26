"""Blueprint Artist lane -- voxel DSL, timber-frame building kit and furnisher.

Owned by the blueprint-artist lane (content). The Builder lane owns the loader
and tools/gen_blueprints.py; this kit only writes CONTENT:

    data/hearthstead/structure/blueprints/<id>.nbt   (vanilla template, DV 3955)
    data/hearthstead/blueprints/<id>.json            (BUILDER.md section 3 schema)

Conventions (agreed with the Builder lane, 26 Sep):
  * template y of the floor surface = ground_level; everything at
    y <= ground_level is FOUNDATION;
  * cells absent from the template = structure_void (don't care);
    minecraft:air = must end up empty (the Builder clears it);
  * both door halves, both bed parts, no entities, no block-entity nbt;
  * the plaque is IN the template (hung inside, facing into the room);
  * furniture: vanilla stand-ins in the NBT, Another Furniture swaps listed
    in the JSON "furniture" array (applied only when the mod is loaded).

The front of every blueprint is SOUTH (+z); ridges run along x.
"""
import collections
import json
import os
import random

N = 'minecraft:'
DIRS = {'north': (0, 0, -1), 'south': (0, 0, 1), 'east': (1, 0, 0),
        'west': (-1, 0, 0), 'up': (0, 1, 0), 'down': (0, -1, 0)}
OPP = {'north': 'south', 'south': 'north', 'east': 'west', 'west': 'east',
       'up': 'down', 'down': 'up'}
HORIZ = ['north', 'east', 'south', 'west']


def dname(dx, dz):
    for k, (x, y, z) in DIRS.items():
        if (x, z) == (dx, dz) and y == 0:
            return k
    raise ValueError((dx, dz))


def full(name):
    return name if ':' in name else N + name


def short(name):
    return name.split(':', 1)[1]


class BP:
    """A sparse voxel blueprint. Coordinates are free; save() normalises."""

    def __init__(self, bid, **meta):
        self.id = bid
        self.c = {}
        self.meta = dict(meta)
        self.work = []
        self.plaque = None           # (pos, facing)
        self.furniture = []          # AF swaps, positions in build coords
        self.notes = []
        self.rng = random.Random(hash(bid) & 0xFFFF)

    # ------------------------------------------------------------ cells --
    def set(self, x, y, z, name, **p):
        self.c[(x, y, z)] = (full(name), {k: str(v).lower() for k, v in p.items()})

    def get(self, x, y, z):
        return self.c.get((x, y, z))

    def name(self, x, y, z):
        v = self.c.get((x, y, z))
        return v[0] if v else None

    def is_(self, pos, *names):
        v = self.c.get(pos)
        return v is not None and short(v[0]) in names

    def fill(self, x0, y0, z0, x1, y1, z1, name, **p):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    self.set(x, y, z, name, **p)

    def air(self, x0, y0, z0, x1, y1, z1, only_empty=False):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    if only_empty and (x, y, z) in self.c:
                        continue
                    self.set(x, y, z, 'air')

    def remove(self, x, y, z):
        self.c.pop((x, y, z), None)

    # -------------------------------------------------------- composite --
    def door(self, x, y, z, facing, hinge='left', kind='oak_door'):
        self.set(x, y, z, kind, facing=facing, half='lower', hinge=hinge, open='false', powered='false')
        self.set(x, y + 1, z, kind, facing=facing, half='upper', hinge=hinge, open='false', powered='false')

    def bed(self, head, facing, color='white'):
        """head cell + facing (foot -> head direction)."""
        dx, _, dz = DIRS[facing]
        hx, hy, hz = head
        self.set(hx, hy, hz, f'{color}_bed', facing=facing, part='head', occupied='false')
        self.set(hx - dx, hy, hz - dz, f'{color}_bed', facing=facing, part='foot', occupied='false')

    def table(self, x, y, z, wood='oak'):
        """Vanilla table (fence + pressure plate) with an AF oak_table swap."""
        self.set(x, y, z, f'{wood}_fence')
        self.set(x, y + 1, z, f'{wood}_pressure_plate', powered='false')
        self.furniture.append({'pos': [x, y, z], 'to': f'another_furniture:{wood}_table[facing=north,leg_1=true,leg_2=true,leg_3=true,leg_4=true]'})
        self.furniture.append({'pos': [x, y + 1, z], 'to': 'minecraft:air', 'mod': 'another_furniture'})

    def chair(self, x, y, z, looks, wood='oak'):
        """A seat whose sitter looks `looks`. Vanilla: a stair with its back opposite."""
        self.set(x, y, z, f'{wood}_stairs', facing=OPP[looks], half='bottom', shape='straight', waterlogged='false')
        self.furniture.append({'pos': [x, y, z], 'to': f'another_furniture:{wood}_chair[facing={looks},tucked=false,variant=1]'})

    # ----------------------------------------------------------- output --
    def bounds(self):
        xs = [p[0] for p in self.c]
        ys = [p[1] for p in self.c]
        zs = [p[2] for p in self.c]
        return min(xs), min(ys), min(zs), max(xs), max(ys), max(zs)


# ---------------------------------------------------------------------------
# connection pass (fences, panes, walls, bars) -- the template stores states,
# so neighbour shapes are written explicitly.
FULL_SOLID_SUFFIX = ('_planks', '_log', '_wood', 'cobblestone', 'stone_bricks', 'bricks', 'stone',
                     'glass', 'bookshelf', 'barrel', 'crafting_table', 'hay_block', 'furnace',
                     'smoker', 'blast_furnace', 'smooth_stone', 'mossy_cobblestone', 'chiseled_stone_bricks',
                     'cracked_stone_bricks', 'mossy_stone_bricks', '_concrete', '_terracotta', 'terracotta', 'calcite', 'loom', 'fletching_table',
                     'smithing_table', 'cartography_table', 'sandstone', 'cut_sandstone', 'white_wool')


def is_full_solid(st):
    if st is None:
        return False
    s = short(st[0])
    if s.endswith('_slab'):
        return st[1].get('type') == 'double'
    if s.endswith('_stairs'):
        return False
    return s.endswith(FULL_SOLID_SUFFIX) or s in FULL_SOLID_SUFFIX


def family(st):
    if st is None:
        return None
    s = short(st[0])
    if s.endswith('_fence') or s.endswith('_fence_gate'):
        return 'fence'
    if s.endswith('glass_pane') or s == 'iron_bars':
        return 'pane'
    if s.endswith('_wall') and not s.endswith('_wall_torch') and 'sign' not in s and 'banner' not in s:
        return 'wall'
    return None


def connect_pass(bp):
    for pos, (name, props) in list(bp.c.items()):
        s = short(name)
        fam = family((name, props))
        if fam is None or s.endswith('_fence_gate'):
            continue
        x, y, z = pos
        new = dict(props)
        for d in HORIZ:
            dx, _, dz = DIRS[d]
            nb = bp.c.get((x + dx, y, z + dz))
            nf = family(nb)
            ok = is_full_solid(nb) or (nf == fam) or (fam == 'fence' and nf == 'fence') \
                or (fam == 'wall' and nb is not None and short(nb[0]).endswith('_fence_gate'))
            if fam == 'fence' and nb is not None and short(nb[0]).endswith('_fence_gate'):
                ok = True
            if fam == 'wall':
                new[d] = 'low' if ok else 'none'
            else:
                new[d] = 'true' if ok else 'false'
        if fam == 'wall':
            above = bp.c.get((x, y + 1, z))
            straight = (new['north'] != 'none' and new['south'] != 'none' and new['east'] == 'none' and new['west'] == 'none') or \
                       (new['east'] != 'none' and new['west'] != 'none' and new['north'] == 'none' and new['south'] == 'none')
            new['up'] = 'false' if straight and above is None else 'true'
        new.setdefault('waterlogged', 'false')
        bp.c[pos] = (name, new)


# ---------------------------------------------------------------------------
# materials (mirror of MaterialRules.costOf)
FREE = {'air', 'cave_air', 'void_air', 'water', 'lava', 'fire', 'soul_fire', 'structure_void', 'barrier'}
SUBST = {'grass_block': 'dirt', 'dirt_path': 'dirt', 'farmland': 'dirt', 'podzol': 'dirt', 'mycelium': 'dirt'}


def cost_of(name, props):
    s = short(name)
    if name.startswith(N) and s in FREE:
        return None
    if props.get('half') == 'upper' and s.endswith('_door'):
        return None
    if s.endswith('_bed') and props.get('part') == 'head':
        return None
    ns = name.split(':')[0]
    item = SUBST.get(s, s)
    if item == 'wall_torch':
        item = 'torch'
    if item.startswith('potted_'):
        return [(ns + ':flower_pot', 1), (ns + ':' + item[len('potted_'):], 1)]
    n = 2 if s.endswith('_slab') and props.get('type') == 'double' else 1
    return [(ns + ':' + item, n)]


def materials(bp):
    tot = collections.OrderedDict()
    for pos in sorted(bp.c, key=lambda p: (p[1], p[2], p[0])):
        name, props = bp.c[pos]
        c = cost_of(name, props)
        if not c:
            continue
        for item, n in c:
            tot[item] = tot.get(item, 0) + n
    return sorted(({'item': k, 'count': v} for k, v in tot.items()), key=lambda e: -e['count'])


# ---------------------------------------------------------------------------
# saving

def save(bp, root):
    import nbtlib
    from nbtlib import Compound, List, Int, String
    connect_pass(bp)
    x0, y0, z0, x1, y1, z1 = bp.bounds()
    sx, sy, sz = x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1
    assert sx <= 24 and sy <= 16 and sz <= 24, (bp.id, sx, sy, sz)
    assert len(bp.c) <= 4096, (bp.id, len(bp.c))
    palette, index, blocks = [], {}, []
    for (x, y, z) in sorted(bp.c, key=lambda p: (p[1], p[2], p[0])):
        name, props = bp.c[(x, y, z)]
        key = (name, tuple(sorted(props.items())))
        if key not in index:
            index[key] = len(palette)
            c = Compound({'Name': String(name)})
            if props:
                c['Properties'] = Compound({k: String(v) for k, v in sorted(props.items())})
            palette.append(c)
        blocks.append(Compound({'pos': List[Int]([Int(x - x0), Int(y - y0), Int(z - z0)]),
                                'state': Int(index[key])}))
    rootc = Compound({'DataVersion': Int(3955),
                      'size': List[Int]([Int(sx), Int(sy), Int(sz)]),
                      'palette': List[Compound](palette), 'blocks': List[Compound](blocks),
                      'entities': List[Compound]([])})
    nbt_dir = os.path.join(root, 'data/hearthstead/structure/blueprints')
    js_dir = os.path.join(root, 'data/hearthstead/blueprints')
    os.makedirs(nbt_dir, exist_ok=True)
    os.makedirs(js_dir, exist_ok=True)
    nbtlib.File(rootc).save(os.path.join(nbt_dir, bp.id + '.nbt'), gzipped=True)

    def sh(p):
        return [p[0] - x0, p[1] - y0, p[2] - z0]

    meta = collections.OrderedDict()
    meta['id'] = bp.id
    for k in ('name', 'category', 'kind', 'building_type', 'variant', 'style', 'preset', 'requires'):
        meta[k] = bp.meta.get(k)
    meta['group'] = bp.meta.get('group') or bp.meta.get('building_type') or bp.id
    meta['size'] = [sx, sy, sz]
    meta['ground_level'] = bp.meta.get('ground_level', 0) - y0
    meta['eave_y'] = bp.meta['eave_y'] - y0 if bp.meta.get('eave_y') is not None else None
    meta['plaque'] = {'pos': sh(bp.plaque[0]), 'facing': bp.plaque[1]} if bp.plaque else None
    meta['work'] = [sh(w) for w in bp.work]
    meta['segment'] = bp.meta.get('segment')
    meta['furniture'] = [dict(f, pos=sh(f['pos'])) if 'pos' in f else f for f in bp.furniture]
    meta['materials'] = materials(bp)
    meta['block_count'] = sum(1 for v in bp.c.values() if short(v[0]) != 'air')
    meta['cells'] = len(bp.c)
    if bp.notes:
        meta['notes'] = bp.notes
    with open(os.path.join(js_dir, bp.id + '.json'), 'w', newline='\n') as f:
        json.dump(meta, f, indent=2)
        f.write('\n')
    return meta, (x0, y0, z0)
