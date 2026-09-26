"""Offline port of RoomScanner + the plaque survey + BuildingType L1 checklist.

This is the blueprint-artist lane's pre-flight check: every blueprint is placed
on a flat test site (dirt at y <= ground_level, air above, void cells take the
site's value), its plaque candidates are scanned exactly the way
PlaqueBlockEntity.surveyRoom does, and the result is measured against the L1
requirements parsed straight from BuildingType.java (so a changed checklist
fails the check instead of silently passing it).

The authoritative proof stays the GameTest (BlueprintCatalogGameTests).
"""
import collections
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
BT_JAVA = os.path.join(REPO, 'src/main/java/com/hearthstead/building/BuildingType.java')

MAX_VOLUME, MAX_EXTENT, MAX_HEIGHT, MAX_HOME_VOLUME = 2048, 24, 12, 512


def short(n):
    return n.split(':', 1)[1] if ':' in n else n


NO_COLLISION = {
    'air', 'cave_air', 'void_air', 'structure_void', 'torch', 'wall_torch', 'soul_torch', 'soul_wall_torch',
    'redstone_torch', 'redstone_wall_torch', 'lever', 'tripwire', 'redstone_wire', 'rail', 'powered_rail',
    'detector_rail', 'activator_rail', 'short_grass', 'tall_grass', 'fern', 'large_fern', 'poppy', 'dandelion',
    'cornflower', 'azure_bluet', 'red_tulip', 'oxeye_daisy', 'allium', 'cobweb', 'vine', 'light', 'water',
    'lava', 'fire', 'plaque', 'wheat', 'carrots', 'potatoes', 'sugar_cane', 'string', 'scaffolding',
}
NO_COLLISION_SUFFIX = ('_sign', '_banner', '_button', '_pressure_plate', '_sapling', '_carpet_none')


def has_collision(st):
    if st is None:
        return False
    s = short(st[0])
    p = st[1]
    if s in NO_COLLISION or s.endswith(NO_COLLISION_SUFFIX):
        return False
    if s.endswith('_fence_gate') and p.get('open') == 'true':
        return False
    if s == 'snow' and p.get('layers', '1') == '1':
        return False
    return True


def fluid(st):
    if st is None:
        return False
    s = short(st[0])
    return s in ('water', 'lava') or st[1].get('waterlogged') == 'true'


def is_door(st):
    return st is not None and short(st[0]).endswith('_door')


def is_bed(st):
    return st is not None and short(st[0]).endswith('_bed')


def passable(st):
    if st is None:
        return True
    s = short(st[0])
    if is_bed(st) or s.endswith('_carpet') or (s == 'ladder' and not fluid(st)):
        return True
    return not fluid(st) and not has_collision(st)


def light(st):
    if st is None:
        return 0
    s = short(st[0])
    p = st[1]
    if s in ('torch', 'wall_torch', 'end_rod'):
        return 14
    if s in ('lantern', 'glowstone', 'jack_o_lantern', 'sea_lantern', 'shroomlight', 'beacon', 'lava'):
        return 15
    if s in ('soul_lantern', 'soul_torch', 'soul_wall_torch', 'soul_campfire'):
        return 10 if 'campfire' not in s or p.get('lit') == 'true' else 0
    if s == 'campfire':
        return 15 if p.get('lit', 'true') == 'true' else 0
    if s in ('furnace', 'smoker', 'blast_furnace'):
        return 13 if p.get('lit') == 'true' else 0
    if s.endswith('candle'):
        return 3 * int(p.get('candles', '1')) if p.get('lit') == 'true' else 0
    if s == 'hearth':
        return 13
    return 0


class World:
    """A blueprint on a flat site (+ optional overlay such as a fishing pond)."""

    def __init__(self, cells, ground_level, overlay=None):
        self.cells = cells
        self.g = ground_level
        self.overlay = overlay or {}

    def get(self, p):
        if p in self.overlay:
            return self.overlay[p]
        st = self.cells.get(p)
        if st is not None and short(st[0]) != 'structure_void':
            if short(st[0]) == 'air':
                return None
            return st
        # The template's ground_level layer sits ON the site (the Builder and
        # the scenario arena both put template y=ground_level one above the
        # terrain surface), so a void cell there is AIR, not ground.
        if p[1] < self.g:
            return ('minecraft:dirt', {})
        return None


def classify(world, pos, st, bed_blocks, beds, counts, counted):
    for p in (pos, (pos[0], pos[1] - 1, pos[2])):
        s = st if p == pos else world.get(p)
        if is_bed(s) and p not in bed_blocks:
            bed_blocks.add(p)
            if s[1].get('part') == 'head':
                beds.append(p)
        if s is not None and p not in counted:
            counted.add(p)
            counts[s[0]] += 1


DIRS6 = [(1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)]


def find_seed(world, seed):
    st = world.get(seed)
    if passable(st) and not is_door(st):
        return seed
    x, y, z = seed
    for c in ((x, y + 1, z), (x, y, z - 1), (x, y, z + 1), (x + 1, y, z), (x - 1, y, z)):
        s = world.get(c)
        if passable(s) and not is_door(s):
            return c
    return None


def scan(world, seed):
    interior = find_seed(world, seed)
    if interior is None:
        return None
    filled = {interior}
    boundary = set()
    bed_blocks, beds = set(), []
    counts = collections.Counter()
    counted = set()
    doors = set()
    lights = 0
    enclosed = True
    q = collections.deque([interior])
    ix, iy, iz = interior
    while q:
        cur = q.popleft()
        if len(filled) > MAX_VOLUME:
            enclosed = False
            break
        cs = world.get(cur)
        classify(world, cur, cs, bed_blocks, beds, counts, counted)
        if light(cs) > 7:
            lights += 1
        for d in DIRS6:
            n = (cur[0] + d[0], cur[1] + d[1], cur[2] + d[2])
            if n in filled:
                continue
            if abs(n[0] - ix) > MAX_EXTENT or abs(n[2] - iz) > MAX_EXTENT or abs(n[1] - iy) > MAX_HEIGHT:
                enclosed = False
                continue
            s = world.get(n)
            if is_door(s):
                doors.add(n)
                continue
            if passable(s):
                filled.add(n)
                q.append(n)
            elif n not in boundary:
                boundary.add(n)
                classify(world, n, s, bed_blocks, beds, counts, counted)
                if light(s) > 7:
                    lights += 1
    sky = False
    for c in filled:
        if (c[0], c[1] + 1, c[2]) in filled:
            continue
        covered = False
        for y in range(c[1] + 1, c[1] + MAX_HEIGHT + 1):
            s = world.get((c[0], y, c[2]))
            if s is not None and short(s[0]) == 'barrier':
                break
            if s is not None and short(s[0]) != 'ladder' and has_collision(s):
                covered = True
                break
        if not covered:
            sky = True
            break
    lower = set()
    for d in doors:
        s = world.get(d)
        lower.add(d if s[1].get('half') == 'lower' else (d[0], d[1] - 1, d[2]))
    floor = collections.Counter()
    for c in filled:
        b = (c[0], c[1] - 1, c[2])
        if b not in filled:
            s = world.get(b)
            floor[s[0] if s else 'minecraft:air'] += 1
    taps = count_taps(world, filled, boundary)
    return dict(volume=len(filled), beds=beds, doors=len(lower), lights=lights, enclosed=enclosed,
                sky=sky, counts=counts, taps=taps, filled=filled, floor=floor, seed=interior)


FACE = {'north': (0, 0, -1), 'south': (0, 0, 1), 'east': (1, 0, 0), 'west': (-1, 0, 0)}


def count_taps(world, filled, boundary):
    observed = filled | boundary
    xs = [p[0] for p in filled]; ys = [p[1] for p in filled]; zs = [p[2] for p in filled]
    lo = (min(xs) - 1, min(ys) - 1, min(zs) - 1); hi = (max(xs) + 1, max(ys) + 1, max(zs) + 1)
    inside = lambda p: all(lo[i] <= p[i] <= hi[i] for i in range(3))
    n = 0
    for t in observed:
        s = world.get(t)
        if s is None or short(s[0]) != 'ale_tap' or not inside(t):
            continue
        f = FACE[s[1]['facing']]
        out = (t[0] + f[0], t[1], t[2] + f[2])
        barrel = (t[0] - f[0], t[1], t[2] - f[2])
        if out in filled and barrel in observed and inside(barrel):
            b = world.get(barrel)
            if b is not None and short(b[0]) == 'barrel':
                n += 1
    return n


# ---------------------------------------------------------------- requirements

def parse_requirements(path=BT_JAVA):
    src = open(path, encoding='utf-8').read()
    body = src[src.index('public enum BuildingType {'):src.index('private final String id;')]
    types = {}
    for m in re.finditer(r'\n    ([A-Z_]+)\("(\w+)", (\d+), (\d+), Items\.\w+,(.*?)\)(?:,|;)\s*(?=\n\s*(?://|[A-Z_]+\(|$))', body, re.S):
        tid = m.group(2)
        reqs = []
        txt = m.group(5)
        for r in re.finditer(r'Requirement\.(beds|doors|lights|floorSpace|aleTap)\((\d+)\)', txt):
            reqs.append((r.group(1), int(r.group(2)), None))
        for r in re.finditer(r'Requirement\.blocks\("(\w+)", (\d+), ([^)]*)\)', txt):
            blocks = ['minecraft:' + b.strip().split('.')[1].lower() for b in r.group(3).split(',')]
            reqs.append((r.group(1), int(r.group(2)), blocks))
        for r in re.finditer(r'new Requirement\("(\w+)", (\d+), .*?ModBlocks\.(\w+)\.get', txt):
            reqs.append((r.group(1), int(r.group(2)), ['hearthstead:' + r.group(3).lower()]))
        types[tid] = reqs
    # BUILDER.md section 6 -- the Builder lane adds this type; mirror it until it lands
    types.setdefault('builders_hut', [('blocks_workbench', 1, ['minecraft:crafting_table']),
                                      ('blocks_storage', 2, ['minecraft:chest', 'minecraft:barrel']),
                                      ('doors', 1, None), ('lights', 1, None), ('floorSpace', 16, None)])
    return types


def measure(req, res):
    kind, n, blocks = req
    if kind == 'beds':
        have = len(res['beds'])
    elif kind == 'doors':
        have = res['doors']
    elif kind == 'lights':
        have = res['lights']
    elif kind == 'floorSpace':
        have = res['volume']
    elif kind == 'aleTap':
        have = res['taps']
    else:
        have = sum(res['counts'].get(b, 0) for b in blocks)
    return have, n


def req_name(req):
    return req[0] if req[2] is None else req[0].replace('blocks_', '')


def survey(world, plaque_pos, facing, reqs):
    f = FACE[facing]
    cands = [(plaque_pos[0] + f[0], plaque_pos[1], plaque_pos[2] + f[2]),
             (plaque_pos[0] - 2 * f[0], plaque_pos[1], plaque_pos[2] - 2 * f[2]),
             (plaque_pos[0] - 3 * f[0], plaque_pos[1], plaque_pos[2] - 3 * f[2])]
    best, bscore = None, -1
    for c in cands:
        r = scan(world, c)
        if r is None:
            continue
        geo = r['enclosed'] and not r['sky'] and r['volume'] <= MAX_HOME_VOLUME
        met = sum(1 for q in reqs if measure(q, r)[0] >= q[1])
        if geo and met == len(reqs):
            return r
        sc = met * 2 + (1 if geo else 0)
        if sc > bscore:
            best, bscore = r, sc
    return best


# ---------------------------------------------------------------- fishery

def fishing_grounds(world, anchor, radius=16, height=3, need=20):
    water = set()
    ax, ay, az = anchor
    for x in range(ax - radius, ax + radius + 1):
        for y in range(ay - height, ay + height + 1):
            for z in range(az - radius, az + radius + 1):
                s = world.get((x, y, z))
                if s is not None and short(s[0]) == 'water' and s[1].get('level', '0') == '0':
                    up = world.get((x, y + 1, z))
                    if not (up is not None and short(up[0]) == 'water') and not has_collision(up):
                        water.add((x, y, z))
    best = 0
    ready = False
    while water:
        seed = water.pop()
        body = {seed}
        q = collections.deque([seed])
        while q:
            p = q.popleft()
            for d in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                n = (p[0] + d[0], p[1], p[2] + d[1])
                if n in water:
                    water.discard(n); body.add(n); q.append(n)
        best = max(best, len(body))
        if len(body) < need:
            continue
        for s in body:
            for name, (dx, _, dz) in FACE.items():
                chair = (s[0] + dx, s[1] + 1, s[2] + dz)
                st = world.get(chair)
                if st is None or short(st[0]) != 'fishers_chair':
                    continue
                facing = {'north': 'south', 'south': 'north', 'east': 'west', 'west': 'east'}[name]
                if st[1].get('facing') != facing:
                    continue
                below = world.get((chair[0], chair[1] - 1, chair[2]))
                if not has_collision(below):
                    continue
                ready = True
    return best, ready


# ---------------------------------------------------------------- walkability

def walk_check(world, starts, targets, max_nodes=20000):
    """BFS over standable cells (2 high, support below); ladders climbable;
    doors and open gates walkable. Returns the set of reached cells."""
    def standable(p):
        s0 = world.get(p)
        s1 = world.get((p[0], p[1] + 1, p[2]))
        below = world.get((p[0], p[1] - 1, p[2]))
        body_ok = lambda s: s is None or is_door(s) or not has_collision(s) or short(s[0]) == 'ladder' \
            or short(s[0]).endswith('_carpet') or short(s[0]).endswith('_fence_gate')
        if not (body_ok(s0) and body_ok(s1)):
            return False
        if s0 is not None and short(s0[0]) == 'ladder':
            return True
        if is_door(s0) or (s0 is not None and short(s0[0]).endswith('_fence_gate')):
            return True
        return below is not None and has_collision(below) and not short(below[0]).endswith(('_fence', '_wall', '_fence_gate')) \
            or (below is not None and short(below[0]) == 'ladder')
    seen = set(s for s in starts if standable(s))
    q = collections.deque(seen)
    while q and len(seen) < max_nodes:
        p = q.popleft()
        s0 = world.get(p)
        nbrs = []
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            for dy in (0, 1, -1, -2):
                nbrs.append(((p[0] + dx, p[1] + dy, p[2] + dz), dy))
        if s0 is not None and short(s0[0]) == 'ladder':
            nbrs += [((p[0], p[1] + 1, p[2]), 0), ((p[0], p[1] - 1, p[2]), 0)]
        nbrs.append(((p[0], p[1] - 1, p[2]), -1))
        for n, dy in nbrs:
            if n in seen or not standable(n):
                continue
            half_step = lambda q: q is not None and (short(q[0]).endswith('_stairs') or
                                                      (short(q[0]).endswith('_slab') and q[1].get('type') == 'bottom'))
            if dy == 1 and not half_step(world.get((n[0], n[1] - 1, n[2]))):
                # jumping up a full block needs headroom above the current cell
                above = world.get((p[0], p[1] + 2, p[2]))
                if above is not None and has_collision(above) and not is_door(above):
                    continue
            if dy < 0:
                # stepping down: the cells above the target must be free up to our height
                ok = all(not has_collision(world.get((n[0], n[1] + k, n[2]))) or is_door(world.get((n[0], n[1] + k, n[2])))
                         for k in range(2, 2 - dy))
                if not ok:
                    continue
            seen.add(n)
            q.append(n)
    return seen


# ---------------------------------------------------------------- work yard (proposal)

def yard_scan(world, seed, max_area=512, extent=24, depth=8, head=4):
    """Offline model of the proposed WORK YARD survey (open-air job lots).

    A yard is a bounded 2-D area at foot level: flood-fill passable foot cells
    from the seed; any collision block at foot level (fence, wall, hut wall,
    prop) is the boundary. Gates = fence gates or doors on that boundary.
    Contents are tallied in every yard and boundary column from `depth`
    below to `head` above the foot level, so a shaft's ladders, a crane's
    load and a lean-to's roof lanterns all count. `covered` = yard cells with
    a collision block within 4 above (the tool shelter)."""
    x, y, z = seed
    for _ in range(4):
        if passable(world.get((x, y - 1, z))) and not is_door(world.get((x, y - 1, z))):
            y -= 1
        else:
            break
    fy = y
    start = (x, z)
    if has_collision(world.get((x, fy, z))):
        return None
    area = {start}
    boundary = set()
    gates = set()
    q = collections.deque([start])
    bounded = True
    while q:
        cx, cz = q.popleft()
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (cx + dx, cz + dz)
            if n in area or n in boundary:
                continue
            if abs(n[0] - x) > extent or abs(n[1] - z) > extent:
                bounded = False
                continue
            s = world.get((n[0], fy, n[1]))
            if fluid(s):
                bounded = False       # water / lava is an unsafe edge, never a wall
                continue
            if is_door(s) or (s is not None and short(s[0]).endswith('_fence_gate')):
                b = (n[0] + dx, fy, n[1] + dz)
                if passable(world.get(b)) and passable(world.get((b[0], fy + 1, b[2]))):
                    gates.add((n[0], fy, n[1]))
                boundary.add(n)
                continue
            if has_collision(s) and short(s[0]) != 'ladder':
                boundary.add(n)
                continue
            area.add(n)
            if len(area) > max_area:
                bounded = False
                q.clear()
                break
            q.append(n)
    counts = collections.Counter()
    beds = []
    lights = 0
    for (cx, cz) in area | boundary:
        for yy in range(fy - depth, fy + head + 1):
            s = world.get((cx, yy, cz))
            if s is None:
                continue
            counts[s[0]] += 1
            if light(s) > 7:
                lights += 1
            if is_bed(s) and s[1].get('part') == 'head':
                beds.append((cx, yy, cz))
    covered = 0
    for (cx, cz) in area:
        if any(has_collision(world.get((cx, yy, cz))) for yy in range(fy + 2, fy + 6)):
            covered += 1
    filled = {(cx, fy, cz) for (cx, cz) in area} | {(cx, fy + 1, cz) for (cx, cz) in area}
    return dict(volume=len(area), beds=beds, doors=len(gates), lights=lights, enclosed=bounded and covered >= 4, sky=False,
                counts=counts, taps=0, filled=filled, floor={}, seed=(x, fy, z), covered=covered, yard=True)
