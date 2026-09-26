"""Timber-frame building kit + interior furnisher (blueprint-artist lane).

Everything is built front = SOUTH (+z), ridge along x, floor surface at y=0.
Style anchor: the owner's server house (cobblestone plinth, oak log frame,
oak plank infill, oak stair roof, glass panes, wall torches) -- see
plan/blueprints/TOWN-PALETTE.md -- with medieval additions: jettied upper
storeys, gable roofs with slab ridges, 1-block overhangs, cobblestone
chimneys, trapdoor shutters, window boxes and porch lanterns.
"""
import collections
from bplib import BP, DIRS, OPP, HORIZ, dname, short, is_full_solid

FLOWERS = ['poppy', 'dandelion', 'cornflower', 'azure_bluet', 'red_tulip', 'oxeye_daisy', 'allium']


def rot_cw(d):
    return HORIZ[(HORIZ.index(d) + 1) % 4]


class Storey:
    def __init__(self, k, F, H, x0, x1, z0, z1, stone):
        self.k, self.F, self.H = k, F, H
        self.x0, self.x1, self.z0, self.z1 = x0, x1, z0, z1
        self.stone = stone
        self.cells = {(x, z) for x in range(x0 + 1, x1) for z in range(z0 + 1, z1)}
        self.occ = set()        # floor cells taken by fixtures / furniture
        self.res = set()        # floor cells that must stay free (walk lanes)
        self.groups = []        # furniture groups (list of cells) that must be reachable
        self.start = set()      # where a walker enters this storey
        self.windows = []       # (x, y, z, outward dir)
        self.no_light = set()   # (x, y, z) cells a torch must not take

    def perimeter(self):
        """[(x, z, n)] interior cells against a wall, back wall first."""
        out = []
        order = {'north': 0, 'east': 1, 'west': 2, 'south': 3}
        for (x, z) in self.cells:
            for n in HORIZ:
                dx, _, dz = DIRS[n]
                if (x + dx, z + dz) not in self.cells:
                    out.append((x, z, n))
        out.sort(key=lambda t: (order[t[2]], t[0] if t[2] in ('north', 'south') else t[1], t[1], t[0]))
        return out


class House:
    """A built shell plus the bookkeeping the furnisher needs."""

    def __init__(self, bp, L, D):
        self.bp, self.L, self.D = bp, L, D
        self.storeys = []
        self.T = None
        self.door = None
        self.front_z = None


def _mossy(bp, rate=0.12):
    return 'mossy_cobblestone' if bp.rng.random() < rate else 'cobblestone'


def _log_axis(x, z, st):
    if z in (st.z0, st.z1):
        return 'x'
    return 'z'


def building(bp, L, D, storeys=1, H=3, jetty=False, stone_ground=False, stone_all=False,
             ceiling=False, door_x=None, chimney=None, chimney_fire=True, post_step=3,
             floor='oak_planks', window_boxes=True, porch=True, stair_x=2,
             roof='oak', brackets=True, gable_window=True, upper_H=None, path=True,
             shutters=True, wall='oak_planks', roof_shape='gable', awning=False):
    h = House(bp, L, D)
    F = 0
    for k in range(storeys):
        Hk = H if k == 0 else (upper_H or H)
        z1 = D - 1 + (1 if (jetty and k > 0) else 0)
        st = Storey(k, F, Hk, 0, L - 1, 0, z1, stone_all or (stone_ground and k == 0))
        h.storeys.append(st)
        F += Hk + 1
    T = F
    h.T = T
    top = h.storeys[-1]
    door_x = door_x if door_x is not None else L // 2
    h.door = (door_x, 1, D - 1)
    h.front_z = D - 1

    def posts_along(n0, n1):
        ps = set(range(n0, n1 + 1, post_step)) | {n0, n1}
        return ps

    # ---------------------------------------------------------- storeys --
    for st in h.storeys:
        Fk = st.F
        xs_posts = posts_along(st.x0, st.x1)
        zs_posts = posts_along(st.z0, st.z1)
        if st.k == 0:
            xs_posts |= {door_x - 1, door_x + 1}
        # floor row
        for x in range(st.x0, st.x1 + 1):
            for z in range(st.z0, st.z1 + 1):
                edge = x in (st.x0, st.x1) or z in (st.z0, st.z1)
                if st.k == 0:
                    bp.set(x, Fk, z, _mossy(bp) if edge else floor)
                elif edge:
                    bp.set(x, Fk, z, 'oak_log', axis=_log_axis(x, z, st))
                else:
                    bp.set(x, Fk, z, 'oak_planks')
        # walls
        for y in range(Fk + 1, Fk + st.H + 1):
            for x in range(st.x0, st.x1 + 1):
                for z in range(st.z0, st.z1 + 1):
                    edge_x = x in (st.x0, st.x1)
                    edge_z = z in (st.z0, st.z1)
                    if not (edge_x or edge_z):
                        bp.set(x, y, z, 'air')
                        continue
                    corner = edge_x and edge_z
                    post = corner or (edge_z and x in xs_posts) or (edge_x and z in zs_posts)
                    if st.stone:
                        bp.set(x, y, z, _mossy(bp, 0.08) if not corner else 'cobblestone')
                    elif post:
                        bp.set(x, y, z, 'oak_log', axis='y')
                    else:
                        bp.set(x, y, z, wall)
        # windows
        wy = [Fk + 2] + ([Fk + 3] if st.H >= 4 else [])
        runs = []
        for z, out in ((st.z0, 'north'), (st.z1, 'south')):
            run = []
            for x in range(st.x0 + 1, st.x1):
                ok = x not in xs_posts and not (st.k == 0 and z == D - 1 and abs(x - door_x) <= 1)
                if ok:
                    run.append((x, z))
                elif run:
                    runs.append((run, out)); run = []
            if run:
                runs.append((run, out))
        for x, out in ((st.x0, 'west'), (st.x1, 'east')):
            run = []
            for z in range(st.z0 + 1, st.z1):
                if z not in zs_posts:
                    run.append((x, z))
                elif run:
                    runs.append((run, out)); run = []
            if run:
                runs.append((run, out))
        for run, out in runs:
            n = len(run)
            if st.stone and n >= 3:
                pick = [run[n // 2]]
            elif n <= 2:
                pick = run[:1] if n == 1 else run
            elif n % 2:
                pick = [run[n // 2]]
            else:
                pick = run[n // 2 - 1:n // 2 + 1]
            if chimney and ((chimney == 'west' and out == 'west') or (chimney == 'east' and out == 'east')):
                continue
            for (x, z) in pick:
                for y in wy:
                    bp.set(x, y, z, 'glass_pane')
                    st.windows.append((x, y, z, out))
        # door, frame, plaque
        if st.k == 0:
            dz = D - 1
            if not st.stone:
                for y in range(1, st.H + 1):
                    bp.set(door_x - 1, y, dz, 'oak_log', axis='y')
                    bp.set(door_x + 1, y, dz, 'oak_log', axis='y')
                bp.set(door_x, 3, dz, 'oak_log', axis='x')
            else:
                bp.set(door_x, 3, dz, 'oak_log', axis='x')
            bp.door(door_x, 1, dz, 'north')
            st.res |= {(door_x, dz - 1), (door_x, dz - 2)}
            st.start |= {(door_x, dz - 1)}
            bp.set(door_x + 1, 2, dz - 1, 'hearthstead:plaque', facing='north', glow='empty', registered='false')
            bp.plaque = ((door_x + 1, 2, dz - 1), 'north')
            st.no_light.add((door_x + 1, 2, dz - 1))
            st.no_light.add((door_x + 1, 2, dz - 2))

    # ------------------------------------------------------ eave + roof --
    for x in range(top.x0, top.x1 + 1):
        for z in range(top.z0, top.z1 + 1):
            if x in (top.x0, top.x1) or z in (top.z0, top.z1):
                bp.set(x, T, z, 'oak_log', axis=_log_axis(x, z, top))
            else:
                bp.set(x, T, z, 'oak_planks' if ceiling else 'air')
    zr0, zr1 = top.z0, top.z1

    def hz(z):
        return T + min(z - (zr0 - 1), (zr1 + 1) - z)
    h.hz = hz
    roof_st = roof + '_stairs'
    roof_sl = roof + '_slab'
    zc = (zr0 + zr1) // 2
    peak = T
    if roof_shape == 'hip':
        # the owner's own house is hipped: rings of stairs climbing inward
        k = 0
        while True:
            xl, xh, zl, zh = -1 + k, L - k, zr0 - 1 + k, zr1 + 1 - k
            if xl > xh or zl > zh:
                break
            y = T + k
            peak = y
            for x in range(xl, xh + 1):
                for z in range(zl, zh + 1):
                    on = x in (xl, xh) or z in (zl, zh)
                    if not on:
                        if k > 0 and 0 < x < L - 1 and zr0 < z < zr1:
                            bp.set(x, y, z, 'air')
                        continue
                    if zl == zh:
                        bp.set(x, y, z, roof_sl, type='bottom')
                    elif z == zl:
                        bp.set(x, y, z, roof_st, facing='south', half='bottom', shape='straight')
                    elif z == zh:
                        bp.set(x, y, z, roof_st, facing='north', half='bottom', shape='straight')
                    elif x == xl:
                        bp.set(x, y, z, roof_st, facing='east', half='bottom', shape='straight')
                    else:
                        bp.set(x, y, z, roof_st, facing='west', half='bottom', shape='straight')
            if zl == zh or zh - zl == 1:
                break
            k += 1
        h.hz = lambda z: peak
    else:
        for x in range(-1, L + 1):
            for z in range(zr0 - 1, zr1 + 2):
                a_, b_ = z - (zr0 - 1), (zr1 + 1) - z
                y = hz(z)
                peak = max(peak, y)
                if a_ < b_:
                    bp.set(x, y, z, roof_st, facing='south', half='bottom', shape='straight')
                elif b_ < a_:
                    bp.set(x, y, z, roof_st, facing='north', half='bottom', shape='straight')
                else:
                    bp.set(x, y, z, roof_sl, type='bottom')
                if 0 < x < L - 1 and zr0 < z < zr1:
                    for yy in range(T + 1, y):
                        bp.set(x, yy, z, 'air')
        # gable walls
        for x in (0, L - 1):
            for z in range(zr0 + 1, zr1):
                for y in range(T + 1, hz(z)):
                    if z == zc or (zr1 - zr0) % 2 == 1 and z == zc + 1:
                        bp.set(x, y, z, 'oak_log', axis='y')
                    else:
                        bp.set(x, y, z, 'cobblestone' if stone_all else wall)
            if gable_window and hz(zc) - 1 >= T + 2 and not (chimney and ((chimney == 'west') == (x == 0))):
                if (zr1 - zr0) % 2 == 0:
                    bp.set(x, T + 2, zc, 'glass_pane')
    h.peak = peak
    # ---------------------------------------------------- jetty details --
    if jetty and storeys > 1:
        F1 = h.storeys[1].F
        if brackets:
            for x in sorted(posts_along(0, L - 1)):
                if abs(x - door_x) <= 1:
                    continue
                bp.set(x, F1 - 1, D, 'oak_stairs', facing='north', half='top', shape='straight')
    # ---------------------------------------------------- front dressing --
    g = h.storeys[0]
    zout = D
    if awning:
        # a market awning on fence posts in front of the door
        for x in range(door_x - 2, door_x + 3):
            for z in (zout, zout + 1):
                if (x, 3, z) not in bp.c:
                    bp.set(x, 3, z, 'oak_slab', type='bottom')
        for x in (door_x - 2, door_x + 2):
            for y in (1, 2):
                bp.set(x, y, zout + 1, 'oak_fence')
        porch = False
        bp.set(door_x - 1, 2, zout + 1, 'lantern', hanging='true', waterlogged='false')
    if porch:
        ly = None
        for y in range(3, T + 1):
            if (door_x - 1, y + 1, zout) in bp.c and is_solid_support(bp.c[(door_x - 1, y + 1, zout)]):
                ly = y
                break
        if ly is not None:
            bp.set(door_x - 1, ly, zout, 'lantern', hanging='true', waterlogged='false')
    if path:
        bp.set(door_x, 0, zout, 'dirt_path')
        bp.set(door_x, 0, zout + 1, 'dirt_path')
    for st in h.storeys:
        front = [w for w in st.windows if w[3] == 'south' and w[1] == st.F + 2]
        by_run = collections.defaultdict(list)
        for w in front:
            by_run[w[2]].append(w[0])
        zo = st.z1 + 1
        xs = sorted(w[0] for w in front)
        # group consecutive xs
        groups, cur = [], []
        for x in xs:
            if cur and x != cur[-1] + 1:
                groups.append(cur); cur = []
            cur.append(x)
        if cur:
            groups.append(cur)
        for grp in groups:
            if shutters and not st.stone:
                for sx in (grp[0] - 1, grp[-1] + 1):
                    if (sx, st.F + 2, zo) not in bp.c:
                        bp.set(sx, st.F + 2, zo, 'oak_trapdoor', facing='south', half='bottom', open='true', powered='false', waterlogged='false')
            if window_boxes and st.k == 0:
                for x in grp:
                    if (x, st.F + 1, zo) not in bp.c:
                        # A top slab, not a closed top trapdoor: the shelf sits
                        # 1.5-2.0 above the outside ground, inside a 1.95-tall
                        # settler's body. Vanilla calls every trapdoor passable
                        # and routed settlers under it, where they wedged; a
                        # slab is never pathfindable, so every mob walks round.
                        bp.set(x, st.F + 1, zo, 'oak_slab', type='top', waterlogged='false')
                        bp.set(x, st.F + 2, zo, 'potted_' + bp.rng.choice(FLOWERS))
    # ------------------------------------------------------------ chimney --
    if chimney:
        cx = -1 if chimney == 'west' else L
        cz = zc + 1 if zr1 - zr0 >= 4 else zc
        top_y = (hz(cz) if roof_shape == 'gable' else peak) + 1
        for y in range(0, top_y + 1):
            bp.set(cx, y, cz, _mossy(bp, 0.1))
        if chimney_fire:
            bp.set(cx, top_y + 1, cz, 'campfire', lit='true', signal_fire='false', facing='south', waterlogged='false')
        else:
            bp.set(cx, top_y + 1, cz, 'cobblestone_wall')
    # ------------------------------------------------------------- stairs --
    if storeys > 1:
        _stairs(h, stair_x)
    bp.meta['eave_y'] = T
    bp.meta['ground_level'] = 0
    return h


def is_solid_support(st):
    s = short(st[0])
    if s.endswith('_stairs'):
        return st[1].get('half') == 'bottom' or True
    return is_full_solid(st) or s.endswith('_log') or s.endswith('_slab')


def _stairs(h, s):
    """Straight oak stair along the back wall from storey 0 to storey 1."""
    bp = h.bp
    g, u = h.storeys[0], h.storeys[1]
    H = g.H
    F1 = u.F
    assert s + H + 1 <= h.L - 2, 'stair run does not fit'
    z = 1
    for i in range(H + 1):
        bp.set(s + i, 1 + i, z, 'oak_stairs', facing='east', half='bottom', shape='straight')
        for y in range(1, 1 + i):
            bp.set(s + i, y, z, 'oak_planks')
        g.occ.add((s + i, z))
        for y in range(1, F1 + 3):
            g.no_light.add((s + i, y, z))
    # Stepping up from a stair lifts the body (1.95 tall, 0.6 wide) while it
    # still overlaps the column it steps from, so that column needs three
    # clear blocks above its floor (vanilla Entity.collide clips the step at
    # the ceiling; WalkNodeEvaluator.tryJumpOn checks exactly this). Open the
    # upper floor over every stair whose third block above its tread would
    # be floor, not just the two a standing head needs.
    hole = list(range(max(0, H - 3), H))
    for i in hole:
        bp.set(s + i, F1, z, 'air')
    g.res.add((s - 1, z))
    for y in range(1, F1):
        g.no_light.add((s - 1, y, z))   # no lantern over the first step's approach
    # railing on the upper floor
    rail = s + hole[0] - 1
    if rail >= 1:
        bp.set(rail, F1 + 1, z, 'oak_fence')
        u.occ.add((rail, z))
    for i in hole:
        bp.set(s + i, F1 + 1, z + 1, 'oak_fence')
        u.occ.add((s + i, z + 1))
        u.occ.add((s + i, z))
    u.occ.add((s + H, z))            # stair top (walkable, but keep clear)
    u.res.add((s + H + 1, z))
    u.start.add((s + H + 1, z))
    for i in range(H + 1):
        for y in range(F1 + 1, F1 + 3):
            u.no_light.add((s + i, y, z))


# ---------------------------------------------------------------------------
# furnisher

def _reach_ok(st):
    free = st.cells - st.occ
    if not st.start:
        return True
    seen = set(c for c in st.start if c in free)
    q = collections.deque(seen)
    while q:
        x, z = q.popleft()
        for d in HORIZ:
            dx, _, dz = DIRS[d]
            n = (x + dx, z + dz)
            if n in free and n not in seen:
                seen.add(n); q.append(n)
    if seen != free:
        return False
    for grp in st.groups:
        if not any((c[0] + DIRS[d][0], c[1] + DIRS[d][2]) in seen for c in grp for d in HORIZ):
            return False
    return True


def facing_for(kind, n):
    away = OPP[n]
    return away


ITEM_PROPS = {
    'chest': lambda n: dict(facing=OPP[n], type='single', waterlogged='false'),
    'barrel': lambda n: dict(facing='up', open='false'),
    'furnace': lambda n: dict(facing=OPP[n], lit='false'),
    'smoker': lambda n: dict(facing=OPP[n], lit='false'),
    'blast_furnace': lambda n: dict(facing=OPP[n], lit='false'),
    'lectern': lambda n: dict(facing=OPP[n], has_book='false', powered='false'),
    'loom': lambda n: dict(facing=OPP[n]),
    'stonecutter': lambda n: dict(facing=OPP[n]),
    'grindstone': lambda n: dict(face='floor', facing=rot_cw(n)),
    'anvil': lambda n: dict(facing=rot_cw(n)),
    'bell': lambda n: dict(attachment='floor', facing=OPP[n], powered='false'),
    'campfire': lambda n: dict(lit='true', signal_fire='false', facing=OPP[n], waterlogged='false'),
    'composter': lambda n: dict(level='0'),
    'hay_block': lambda n: dict(axis='y'),
    'hearthstead:fish_rack': lambda n: dict(facing=OPP[n], hanging='0'),
}


def place_item(bp, st, x, z, n, kind):
    y = st.F + 1
    props = ITEM_PROPS.get(kind, lambda n: {})(n)
    bp.set(x, y, z, kind, **props)


def furnish(h, k, items, rng=None):
    """items: list of ('bed', color) | ('blk', block) | ('work', block) |
    ('ladder', rows) | ('table', chairs) | ('tap',) """
    bp = h.bp
    st = h.storeys[k]
    for it in items:
        kind = it[0]
        placed = False
        cands = st.perimeter()
        if len(it) > 2 and it[2]:
            walls = it[2]
            cands = [c for c in cands if c[2] in walls] + [c for c in cands if c[2] not in walls]
        if kind == 'table':
            placed = _place_table(h, st, it[1])
            if not placed:
                raise RuntimeError(f'{bp.id}: no room for table set')
            continue
        for (x, z, n) in cands:
            if (x, z) in st.occ or (x, z) in st.res:
                continue
            if kind == 'bed':
                dx, _, dz = DIRS[n]
                foot = (x - dx, z - dz)
                if foot not in st.cells or foot in st.occ or foot in st.res:
                    continue
                # never put a bed foot against another wall cell of a door
                st.occ |= {(x, z), foot}
                st.groups.append([(x, z), foot])
                if _reach_ok(st):
                    bp.bed((x, st.F + 1, z), n, it[1])
                    placed = True
                    break
                st.occ -= {(x, z), foot}
                st.groups.pop()
            elif kind == 'ladder':
                rows = it[1]
                wx, wz = x + DIRS[n][0], z + DIRS[n][2]
                if not all(_sturdy(bp.get(wx, y, wz)) for y in range(st.F + 1, st.F + 1 + rows)):
                    continue
                if not all(bp.name(x, y, z) in (None, 'minecraft:air') for y in range(st.F + 1, st.F + 1 + rows)):
                    continue
                if any((x, y, z) in st.no_light for y in range(st.F + 1, st.F + 1 + rows)):
                    continue
                st.occ.add((x, z)); st.groups.append([(x, z)])
                if _reach_ok(st):
                    for y in range(st.F + 1, st.F + 1 + rows):
                        bp.set(x, y, z, 'ladder', facing=OPP[n], waterlogged='false')
                        st.no_light.add((x, y, z))
                    placed = True
                    break
                st.occ.discard((x, z)); st.groups.pop()
            elif kind == 'tap':
                # barrel against the wall + ale tap in front of it, facing into the room
                dx, _, dz = DIRS[n]
                front = (x - dx, z - dz)
                if front not in st.cells or front in st.occ or front in st.res:
                    continue
                st.occ |= {(x, z), front}
                st.groups.append([front])
                if _reach_ok(st):
                    bp.set(x, st.F + 1, z, 'barrel', facing=OPP[n], open='false')
                    bp.set(front[0], st.F + 1, front[1], 'hearthstead:ale_tap', facing=OPP[n], pouring='false')
                    placed = True
                    break
                st.occ -= {(x, z), front}
                st.groups.pop()
            else:
                block = it[1]
                st.occ.add((x, z)); st.groups.append([(x, z)])
                if _reach_ok(st):
                    place_item(bp, st, x, z, n, block)
                    if kind == 'work':
                        dx, _, dz = DIRS[n]
                        bp.work.append((x - dx, st.F + 1, z - dz))
                    placed = True
                    break
                st.occ.discard((x, z)); st.groups.pop()
        if not placed:
            raise RuntimeError(f'{bp.id}: could not place {it} on storey {k}')


def _sturdy(st):
    if st is None:
        return False
    s = short(st[0])
    return is_full_solid(st) or s.endswith('_log')


def _place_table(h, st, chairs):
    """A table (fence+plate) in a free inner cell with up to `chairs` seats."""
    bp = h.bp
    inner = sorted((c for c in st.cells if c not in st.occ and c not in st.res
                    and all((c[0] + DIRS[d][0], c[1] + DIRS[d][2]) in st.cells for d in HORIZ)),
                   key=lambda c: (abs(c[0] - h.L / 2) + abs(c[1] - (st.z0 + st.z1) / 2)))
    for c in inner:
        seats = []
        for d in ('west', 'east', 'north', 'south'):
            s = (c[0] + DIRS[d][0], c[1] + DIRS[d][2])
            if s in st.cells and s not in st.occ and s not in st.res:
                seats.append((s, OPP[d]))
        st.occ.add(c); st.groups.append([c])
        chosen = []
        for s, looks in seats:
            if len(chosen) >= chairs:
                break
            st.occ.add(s); st.groups.append([s])
            if _reach_ok(st):
                chosen.append((s, looks))
            else:
                st.occ.discard(s); st.groups.pop()
        if _reach_ok(st) and (chairs == 0 or chosen):
            bp.table(c[0], st.F + 1, c[1])
            for s, looks in chosen:
                bp.chair(s[0], st.F + 1, s[1], looks)
            return True
        for s, looks in chosen:
            st.occ.discard(s); st.groups.pop()
        st.occ.discard(c); st.groups.pop()
    return False


def lights(h, k, n, lantern=False):
    """n wall torches (or hanging ceiling lanterns) spread over storey k."""
    bp = h.bp
    st = h.storeys[k]
    pool = []
    if lantern:
        y = st.F + st.H
        for (x, z) in sorted(st.cells):
            if (x, z) in st.occ or bp.name(x, y, z) != 'minecraft:air' or (x, y, z) in st.no_light:
                continue
            above = bp.get(x, y + 1, z)
            if above and _sturdy(above) and all(bp.name(x, yy, z) == 'minecraft:air' for yy in range(st.F + 2, y)):
                pool.append(((x, y, z), None))
    if not pool:
        for (x, z, d) in st.perimeter():
            y = st.F + 2
            wx, wz = x + DIRS[d][0], z + DIRS[d][2]
            if (x, y, z) in st.no_light or bp.name(x, y, z) != 'minecraft:air':
                continue
            if not _sturdy(bp.get(wx, y, wz)):
                continue
            pool.append(((x, y, z), d))
    anchor = (h.door[0], 2, h.door[2]) if k == 0 else (h.L // 2, 0, 0)
    chosen = []
    for _ in range(n):
        best, bd = None, -1
        for c in pool:
            p = c[0]
            if any(p == q[0] for q in chosen):
                continue
            ds = [abs(p[0] - q[0][0]) + abs(p[2] - q[0][2]) for q in chosen]
            dmin = min(ds) if ds else abs(p[0] - anchor[0]) + abs(p[2] - anchor[2])
            if dmin > bd:
                best, bd = c, dmin
        if best is None:
            raise RuntimeError(f'{bp.id}: no light spot on storey {k}')
        chosen.append(best)
    for (x, y, z), d in chosen:
        if d is None:
            bp.set(x, y, z, 'lantern', hanging='true', waterlogged='false')
        else:
            bp.set(x, y, z, 'wall_torch', facing=OPP[d])
        st.no_light.add((x, y, z))
    return True


# ---------------------------------------------------------------------------
# vanilla StairBlock.getStairsShape, so hip corners read right in the template

def _ccw(d):
    return HORIZ[(HORIZ.index(d) - 1) % 4]


def _axis(d):
    return 'x' if d in ('east', 'west') else 'z'


def stairs_shape_pass(bp):
    def stair(p):
        v = bp.c.get(p)
        return v if v is not None and short(v[0]).endswith('_stairs') else None

    def can_take(st, p, face):
        dx, _, dz = DIRS[face]
        o = stair((p[0] + dx, p[1], p[2] + dz))
        return o is None or o[1].get('facing') != st[1].get('facing') or o[1].get('half') != st[1].get('half')

    new = {}
    for p, v in bp.c.items():
        if not short(v[0]).endswith('_stairs'):
            continue
        d = v[1]['facing']
        half = v[1].get('half', 'bottom')
        dx, _, dz = DIRS[d]
        shape = 'straight'
        back = stair((p[0] + dx, p[1], p[2] + dz))
        if back and back[1].get('half', 'bottom') == half:
            d1 = back[1]['facing']
            if _axis(d1) != _axis(d) and can_take(v, p, OPP[d1]):
                shape = 'outer_left' if d1 == _ccw(d) else 'outer_right'
        if shape == 'straight':
            front = stair((p[0] - dx, p[1], p[2] - dz))
            if front and front[1].get('half', 'bottom') == half:
                d2 = front[1]['facing']
                if _axis(d2) != _axis(d) and can_take(v, p, d2):
                    shape = 'inner_left' if d2 == _ccw(d) else 'inner_right'
        props = dict(v[1]); props['shape'] = shape
        props.setdefault('waterlogged', 'false')
        new[p] = (v[0], props)
    bp.c.update(new)
