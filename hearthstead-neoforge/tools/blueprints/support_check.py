"""Blueprint-side build checks the Builder cares about (run by gen_town_blueprints):

* SUPPORT: every attached block (lantern, torch, ladder, door, bed, rail,
  flower, banner, lever, bell, pressure plate, carpet, chain, plaque, pot on a
  slab) has its support INSIDE the template, so the Builder can never place it
  first and defer it forever ("interior defer" skips);
* REACH: every non-air cell is within the Builder's reach (4.5 blocks, eye at
  +1.62) of a cell a settler can stand on in the FINISHED structure or on the
  ground around the lot -- cells beyond that need scaffolding and are listed.
"""
import math
from bplib import DIRS, OPP, short, is_full_solid

FLOWERS = {'poppy', 'dandelion', 'cornflower', 'azure_bluet', 'red_tulip', 'oxeye_daisy', 'allium', 'fern',
           'lily_of_the_valley', 'short_grass'}


def _state(bp, p, g):
    v = bp.c.get(p)
    if v is None:
        return ('minecraft:dirt', {}) if p[1] < g else None
    return v


def _full(st):
    if st is None:
        return False
    s = short(st[0])
    if s.endswith('_slab'):
        return st[1].get('type') == 'double'
    return is_full_solid(st) or s.endswith(('_log', '_wood')) or s in ('grass_block', 'dirt', 'coarse_dirt', 'podzol',
                                                                        'dirt_path', 'farmland', 'hay_block')


def face_down_ok(st):
    """Can something hang from under this block (hanging lantern, chain)?"""
    if st is None:
        return False
    s = short(st[0])
    if _full(st):
        return True
    if s.endswith('_slab'):
        return st[1].get('type') == 'bottom'
    if s.endswith('_stairs'):
        return st[1].get('half') == 'bottom'
    return s.endswith(('_fence', '_wall')) or s in ('chain', 'iron_bars') or s.endswith('_fence_gate')


def face_up_ok(st, center=False):
    """Can something stand on top of this block?"""
    if st is None:
        return False
    s = short(st[0])
    if _full(st) and s != 'dirt_path':
        return True
    if s == 'dirt_path':
        return True
    if s.endswith('_slab'):
        return st[1].get('type') in ('top', 'double')
    if s.endswith('_stairs'):
        return st[1].get('half') == 'top'
    if center:
        return s.endswith(('_fence', '_wall')) or s in ('chain', 'iron_bars')
    return False


def side_ok(st):
    """A full face for wall torches, ladders, plaques, wall banners."""
    return _full(st)


def support_problems(bp):
    g = bp.meta.get('ground_level', 0)
    out = []
    for (x, y, z), (n, p) in bp.c.items():
        s = short(n)
        below, above = (x, y - 1, z), (x, y + 1, z)
        ok = True
        need = None
        if s == 'lantern':
            need = above if p.get('hanging') == 'true' else below
            ok = face_down_ok(_state(bp, need, g)) if p.get('hanging') == 'true' else face_up_ok(_state(bp, need, g), True)
        elif s == 'chain':
            need = above
            ok = face_down_ok(_state(bp, above, g)) or short((_state(bp, below, g) or ('x:x', {}))[0]) == 'chain'
        elif s in ('wall_torch', 'ladder', 'plaque') or s.endswith('_wall_banner'):
            f = p.get('facing', 'north')
            dx, _, dz = DIRS[OPP[f]]
            need = (x + dx, y, z + dz)
            ok = side_ok(_state(bp, need, g))
        elif s == 'torch' or s.endswith('_pressure_plate') or (s.endswith('_banner') and not s.endswith('_wall_banner')):
            need = below
            ok = face_up_ok(_state(bp, below, g), True)
        elif s in ('rail',) or s.endswith('_carpet') or (s.endswith('_door') and p.get('half') == 'lower')                 or (s == 'lever' and p.get('face') == 'floor'):
            need = below
            ok = face_up_ok(_state(bp, below, g))
        elif s in FLOWERS:
            need = below
            st = _state(bp, below, g)
            ok = st is not None and short(st[0]) in ('grass_block', 'dirt', 'coarse_dirt', 'podzol', 'farmland', 'rooted_dirt', 'moss_block')
        elif s == 'grindstone':
            face = p.get('face', 'floor')
            if face == 'floor':
                need = below
                ok = face_up_ok(_state(bp, below, g))
            elif face == 'ceiling':
                need = above
                ok = face_down_ok(_state(bp, above, g))
            else:
                f = p.get('facing', 'north')
                dx, _, dz = DIRS[OPP[f]]
                need = (x + dx, y, z + dz)
                ok = side_ok(_state(bp, need, g))
        elif s == 'bell' and p.get('attachment') == 'ceiling':
            need = above
            ok = face_down_ok(_state(bp, above, g))
        if not ok:
            out.append(f'{s}@{(x, y, z)} needs {need}')
    return out


def reach_problems(bp, world, walkable_starts, walk_check, reach=4.5, eye=1.62):
    """Cells no stand cell reaches. Stand cells = everything walkable from the
    ground ring around the lot in the finished structure."""
    xs = [p[0] for p in bp.c]; zs = [p[2] for p in bp.c]
    g = bp.meta.get('ground_level', 0)
    ring = [(x, g, z) for x in range(min(xs) - 2, max(xs) + 3) for z in (min(zs) - 2, max(zs) + 2)]
    ring += [(x, g, z) for z in range(min(zs) - 2, max(zs) + 3) for x in (min(xs) - 2, max(xs) + 2)]
    stands = walk_check(world, list(walkable_starts) + ring, None)
    buckets = {}
    for st in stands:
        buckets.setdefault((st[0] // 5, st[2] // 5), []).append(st)
    far = []
    for (x, y, z), (n, p) in bp.c.items():
        if short(n) in ('air',):
            continue
        cx, cy, cz = x + 0.5, y + 0.5, z + 0.5
        best = 99
        for bx in (x // 5 - 1, x // 5, x // 5 + 1):
            for bz in (z // 5 - 1, z // 5, z // 5 + 1):
                for st in buckets.get((bx, bz), ()):
                    d = math.dist((st[0] + 0.5, st[1] + eye, st[2] + 0.5), (cx, cy, cz))
                    if d < best:
                        best = d
                        if best <= reach:
                            break
        if best > reach:
            far.append(((x, y, z), round(best, 1)))
    return far
