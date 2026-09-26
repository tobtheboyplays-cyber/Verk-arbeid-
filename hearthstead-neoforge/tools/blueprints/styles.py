"""Style presets: re-skin an Elmfield (town palette) blueprint by ROLE.

Roles come from the timber-frame kit's own geometry (storey floor rows, wall
rings, eave row, roof), not from guessing, so a style can never turn a roof
stair into a wall or a floor into a frame post. Every substitute used for a
structural role is a full, non-gravity block; roof stairs/slabs map to
stairs/slabs of the style's roof material (or full thatch blocks), so the
room stays enclosed and roofed exactly as before -- the generator re-runs
the full L1/door/headroom/walk check on every preset anyway.
"""
import copy

from bplib import BP, short

STYLES = {
    'elmfield': {'label': 'Elmfield Timber'},
    'timber': {
        'label': 'Whitewashed Timber',
        'post': 'spruce_log', 'beam': 'oak_log', 'wall': 'white_concrete', 'gable': 'white_concrete',
        'floor': 'spruce_planks', 'plinth': 'cobblestone', 'stone_wall': 'cobblestone',
        'roof': 'spruce', 'wood': 'spruce',
    },
    'stone': {
        'label': 'Stone Hall',
        'post': 'stone_bricks', 'post_upper': 'dark_oak_log', 'beam': 'dark_oak_log',
        'wall': 'cobblestone', 'wall_upper': 'spruce_planks', 'gable': 'cobblestone',
        'floor': 'dark_oak_planks', 'plinth': 'stone_bricks', 'stone_wall': 'cobblestone',
        'roof': 'dark_oak', 'wood': 'dark_oak',
    },
    'rustic': {
        'label': 'Rustic Log',
        'post': 'spruce_log', 'beam': 'spruce_log', 'wall': 'stripped_spruce_log@along', 'gable': 'stripped_spruce_log@along',
        'floor': 'spruce_planks', 'plinth': 'mossy_cobblestone', 'stone_wall': 'cobblestone',
        'roof': 'thatch', 'wood': 'spruce',
    },
}

WOOD_TRIM = ('_fence', '_fence_gate', '_trapdoor', '_door', '_pressure_plate', '_stairs', '_slab')


def roles(h):
    """(x,y,z) -> role for the shell the kit built."""
    bp = h.bp
    out = {}
    T = h.T
    for st in h.storeys:
        for x in range(st.x0, st.x1 + 1):
            for z in range(st.z0, st.z1 + 1):
                edge = x in (st.x0, st.x1) or z in (st.z0, st.z1)
                if st.k == 0:
                    out[(x, st.F, z)] = 'plinth' if edge else 'floor'
                else:
                    out[(x, st.F, z)] = 'beam' if edge else 'floor'
                if edge:
                    for y in range(st.F + 1, st.F + st.H + 1):
                        axis = 'x' if z in (st.z0, st.z1) else 'z'
                        out[(x, y, z)] = ('wall', st.k, axis)
    top = h.storeys[-1]
    for x in range(top.x0, top.x1 + 1):
        for z in range(top.z0, top.z1 + 1):
            if x in (top.x0, top.x1) or z in (top.z0, top.z1):
                out[(x, T, z)] = 'beam'
    for (x, y, z), (n, p) in bp.c.items():
        s = short(n)
        if y > T and x in (0, h.L - 1) and s in ('oak_planks', 'cobblestone', 'oak_log'):
            out.setdefault((x, y, z), ('gable', 0, 'z'))
        if y >= T and (s.endswith('_stairs') or s.endswith('_slab')) and (x, y, z) not in out:
            out[(x, y, z)] = 'roof'
    return out


def _wood(name, wood):
    s = short(name)
    for suf in WOOD_TRIM:
        if s.startswith('oak_') and s.endswith(suf):
            return 'minecraft:' + wood + s[3:]
    return None


def restyle(src, h, style, new_id, name):
    st = STYLES[style]
    bp = BP(new_id, **copy.deepcopy(src.meta))
    bp.meta['name'] = name
    bp.meta['style'] = style
    bp.meta['preset'] = st['label']
    bp.plaque = src.plaque
    bp.work = list(src.work)
    bp.notes = list(src.notes)
    rl = roles(h)
    wood = st['wood']
    for pos, (n, p) in src.c.items():
        s = short(n)
        x_, y_, z_ = pos
        r = rl.get(pos)
        newn, newp = n, dict(p)
        rk = r[0] if isinstance(r, tuple) else r
        if s == 'oak_log':
            axis = p.get('axis', 'y')
            if rk == 'wall' or rk == 'gable':
                upper = isinstance(r, tuple) and r[1] > 0
                blk = st.get('post_upper', st['post']) if upper else st['post']
                if axis != 'y':
                    blk = st['beam']
            else:
                blk = st['beam'] if axis != 'y' else st['post']
            newn = 'minecraft:' + blk
            if not blk.endswith('_log'):
                newp = {}
        elif s in ('oak_planks', 'birch_planks'):
            if rk == 'floor':
                newn = 'minecraft:' + st['floor']
            elif rk in ('wall', 'gable'):
                upper = isinstance(r, tuple) and r[1] > 0
                blk = st.get('wall_upper', st['wall']) if upper else st[rk if rk == 'gable' else 'wall']
                if blk.endswith('@along'):
                    blk = blk.split('@')[0]
                    newp = {'axis': r[2]}
                newn = 'minecraft:' + blk
        elif s in ('cobblestone', 'mossy_cobblestone'):
            if rk == 'plinth':
                newn = 'minecraft:' + st['plinth']
                if st['plinth'] == 'mossy_cobblestone' and src.rng.random() < 0.5:
                    newn = 'minecraft:cobblestone'
            elif rk == 'wall':
                newn = 'minecraft:' + st['stone_wall'] if s == 'cobblestone' else n
        elif rk == 'roof' and (s.endswith('_stairs') or s.endswith('_slab')):
            if st['roof'] == 'thatch':
                top = h.storeys[-1]
                outer = x_ in (-1, h.L) or z_ in (top.z0 - 1, top.z1 + 1)
                if s.endswith('_slab'):
                    newn, newp = 'minecraft:spruce_log', {'axis': 'x'}        # ridge beam
                elif outer:
                    newn = 'minecraft:dark_oak_stairs'                          # eave and barge trim
                else:
                    newn, newp = 'minecraft:hay_block', {'axis': 'y'}
            else:
                base = s.rsplit('_', 1)[0]
                mat = st['roof'] if base in ('oak', 'cobblestone') else base
                if base == 'cobblestone' and style != 'stone':
                    mat = 'cobblestone'
                newn = 'minecraft:' + mat + ('_stairs' if s.endswith('_stairs') else '_slab')
        else:
            w = _wood(n, wood)
            if w:
                newn = w
        bp.c[pos] = (newn, newp)
    _details(bp, style)
    for fu in src.furniture:
        f = dict(fu)
        if f['to'].startswith('another_furniture:oak_'):
            f['to'] = f['to'].replace('another_furniture:oak_', 'another_furniture:' + wood + '_')
        bp.furniture.append(f)
    return bp


def _details(bp, style):
    if style == 'rustic':
        for (x, y, z), ox, oz in _corner_posts(bp):
            if y % 2 == 1 and (x + ox, y, z) not in bp.c:
                bp.c[(x + ox, y, z)] = ('minecraft:spruce_log', {'axis': 'x'})
            elif y % 2 == 0 and (x, y, z + oz) not in bp.c:
                bp.c[(x, y, z + oz)] = ('minecraft:spruce_log', {'axis': 'z'})
    if style == 'stone':
        for (x, y, z), ox, oz in _corner_posts(bp):
            bp.c[(x, y, z)] = ('minecraft:stone_bricks', {})


# ---------------------------------------------------------------------------
# generic restyle for hand-authored job designs (yards, forges, barns ...)

def _corner_posts(bp):
    out = []
    for (x, y, z), (n, p) in bp.c.items():
        if short(n).endswith('_log') and p.get('axis', 'y') == 'y' and y >= 1:
            def solid(q):
                v = bp.c.get(q)
                return v is not None and short(v[0]) not in ('air',) and not short(v[0]).endswith(('_fence', '_pane'))
            xs = [solid((x + d, y, z)) for d in (-1, 1)]
            zs = [solid((x, y, z + d)) for d in (-1, 1)]
            if any(xs) and any(zs) and not all(xs) and not all(zs):
                ox = -1 if xs[1] else 1
                oz = -1 if zs[1] else 1
                out.append(((x, y, z), ox, oz))
    return out


def restyle_generic(src, style, new_id, name):
    """Material/detail restyle by block name for designs not built by kit.building."""
    st = STYLES[style]
    bp = BP(new_id, **copy.deepcopy(src.meta))
    bp.meta['name'] = name
    bp.meta['style'] = style
    bp.meta['preset'] = st['label']
    bp.plaque = src.plaque
    bp.work = list(src.work)
    bp.notes = list(src.notes)
    eave = src.meta.get('eave_y') or 4
    wood = st['wood']
    for pos, (n, p) in src.c.items():
        s = short(n)
        x, y, z = pos
        newn, newp = n, dict(p)
        if s in ('oak_log', 'spruce_log') and pos != (src.plaque[0] if src.plaque else None):
            axis = p.get('axis', 'y')
            blk = st['beam'] if axis != 'y' else st['post']
            newn = 'minecraft:' + blk
            newp = {'axis': axis} if blk.endswith('_log') else {}
        elif s in ('oak_planks', 'spruce_planks', 'birch_planks'):
            if y <= 0:
                newn = 'minecraft:' + st['floor']
            else:
                blk = st['wall']
                if blk.endswith('@along'):
                    blk = blk.split('@')[0]
                    xs = any(short(src.c.get((x + d, y, z), ('x:air', {}))[0]).endswith(('_planks', '_log')) for d in (-1, 1))
                    newp = {'axis': 'x' if xs else 'z'}
                newn = 'minecraft:' + blk
        elif s in ('cobblestone', 'mossy_cobblestone') and y <= 0:
            newn = 'minecraft:' + st['plinth']
            if st['plinth'] == 'mossy_cobblestone' and src.rng.random() < 0.5:
                newn = 'minecraft:cobblestone'
        elif (s.endswith('_stairs') or s.endswith('_slab')) and y >= eave and s.split('_')[0] in ('oak', 'spruce', 'dark'):
            if st['roof'] == 'thatch':
                newn, newp = ('minecraft:hay_block', {'axis': 'y'}) if s.endswith('_stairs') else ('minecraft:spruce_slab', dict(p))
            else:
                newn = 'minecraft:' + st['roof'] + ('_stairs' if s.endswith('_stairs') else '_slab')
        else:
            w = _wood(n, wood) if s.startswith('oak_') else None
            if w is None and s.startswith('spruce_') and s.endswith(WOOD_TRIM) and wood != 'spruce':
                w = 'minecraft:' + wood + s[len('spruce'):]
            if w:
                newn = w
        bp.c[pos] = (newn, newp)
    _details(bp, style)
    for fu in src.furniture:
        f = dict(fu)
        if f['to'].startswith('another_furniture:oak_'):
            f['to'] = f['to'].replace('another_furniture:oak_', 'another_furniture:' + wood + '_')
        bp.furniture.append(f)
    return bp
