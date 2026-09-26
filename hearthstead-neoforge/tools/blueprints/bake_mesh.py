#!/usr/bin/env python3
"""Bake blueprint cells into textured quads using the real block models.

    python tools/blueprints/bake_mesh.py <assets-root-json> [ids...]

<assets-root-json> maps namespace -> extracted assets dir, e.g.
    {"minecraft": ".../assets/minecraft", "another_furniture": ".../assets/another_furniture",
     "hearthstead": "src/main/resources/assets/hearthstead"}
(the vanilla dir must also contain textures/entity/{chest,bed}).

Reads tools/blueprints/out/cells/<id>.json, writes out/mesh/<id>.json with
quads [[x,y,z]x4, [u,v]x4, texture index, alpha] for render_blender.py.
Another Furniture swaps from the blueprint JSON are applied (preview shows
the owner's furniture mod), everything else is the vanilla NBT.
"""
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bplib import is_full_solid, short  # noqa: E402

OUT = os.path.join(HERE, 'out')
ROOTS = {}
_json_cache = {}
_model_cache = {}
_tex_size = {}


def jload(path):
    if path not in _json_cache:
        with open(path, encoding='utf-8') as f:
            _json_cache[path] = json.load(f)
    return _json_cache[path]


def split(ref, default_ns='minecraft'):
    if ':' in ref:
        ns, p = ref.split(':', 1)
    else:
        ns, p = default_ns, ref
    return ns, p


def model(ref):
    if ref in _model_cache:
        return _model_cache[ref]
    ns, p = split(ref)
    path = os.path.join(ROOTS[ns], 'models', p + '.json')
    if not os.path.exists(path):
        _model_cache[ref] = {'textures': {}, 'elements': []}
        return _model_cache[ref]
    m = jload(path)
    textures, elements = {}, None
    if 'parent' in m and not m['parent'].startswith('builtin'):
        par = model(m['parent'])
        textures.update(par['textures'])
        elements = par['elements']
    textures.update(m.get('textures', {}))
    if 'elements' in m:
        elements = m['elements']
    res = {'textures': textures, 'elements': elements or []}
    _model_cache[ref] = res
    return res


def resolve_tex(textures, key, depth=0):
    v = textures.get(key.lstrip('#'))
    if v is None or depth > 8:
        return None
    if v.startswith('#'):
        return resolve_tex(textures, v, depth + 1)
    return v


def tex_file(ref):
    ns, p = split(ref)
    return os.path.join(ROOTS[ns], 'textures', p + '.png')


def tex_aspect(path):
    if path not in _tex_size:
        from PIL import Image
        try:
            with Image.open(path) as im:
                _tex_size[path] = im.size
        except Exception:
            _tex_size[path] = (16, 16)
    w, h = _tex_size[path]
    return w / h if h > w else 1.0


def match(when, props):
    if 'OR' in when:
        return any(match(w, props) for w in when['OR'])
    if 'AND' in when:
        return all(match(w, props) for w in when['AND'])
    for k, v in when.items():
        vals = str(v).lower().split('|')
        if props.get(k, None) not in vals:
            if k not in props and False:
                continue
            return False
    return True


def models_for(name, props):
    ns, p = split(name)
    path = os.path.join(ROOTS.get(ns, ''), 'blockstates', p + '.json')
    if not os.path.exists(path):
        return []
    bs = jload(path)
    out = []
    if 'variants' in bs:
        best, bscore = None, -1
        for key, v in bs['variants'].items():
            conds = dict(kv.split('=') for kv in key.split(',') if '=' in kv)
            ok = all(props.get(k, None) in (None, val) for k, val in conds.items())
            miss = sum(1 for k in conds if k not in props)
            if ok and (best is None or -miss > bscore):
                best, bscore = v, -miss
        if best is not None:
            out.append(best[0] if isinstance(best, list) else best)
    else:
        for part in bs.get('multipart', []):
            if 'when' not in part or match(part['when'], props):
                a = part['apply']
                out.append(a[0] if isinstance(a, list) else a)
    return out


FACES = {
    'north': lambda a, b: [(b[0], b[1], a[2]), (a[0], b[1], a[2]), (a[0], a[1], a[2]), (b[0], a[1], a[2])],
    'south': lambda a, b: [(a[0], b[1], b[2]), (b[0], b[1], b[2]), (b[0], a[1], b[2]), (a[0], a[1], b[2])],
    'west': lambda a, b: [(a[0], b[1], a[2]), (a[0], b[1], b[2]), (a[0], a[1], b[2]), (a[0], a[1], a[2])],
    'east': lambda a, b: [(b[0], b[1], b[2]), (b[0], b[1], a[2]), (b[0], a[1], a[2]), (b[0], a[1], b[2])],
    'up': lambda a, b: [(a[0], b[1], a[2]), (b[0], b[1], a[2]), (b[0], b[1], b[2]), (a[0], b[1], b[2])],
    'down': lambda a, b: [(a[0], a[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2])],
}
DEF_UV = {
    'north': lambda a, b: [16 - b[0], 16 - b[1], 16 - a[0], 16 - a[1]],
    'south': lambda a, b: [a[0], 16 - b[1], b[0], 16 - a[1]],
    'west': lambda a, b: [a[2], 16 - b[1], b[2], 16 - a[1]],
    'east': lambda a, b: [16 - b[2], 16 - b[1], 16 - a[2], 16 - a[1]],
    'up': lambda a, b: [a[0], a[2], b[0], b[2]],
    'down': lambda a, b: [a[0], 16 - b[2], b[0], 16 - a[2]],
}
DVEC = {'north': (0, 0, -1), 'south': (0, 0, 1), 'west': (-1, 0, 0), 'east': (1, 0, 0), 'up': (0, 1, 0), 'down': (0, -1, 0)}


def rot_elem(p, r):
    if not r:
        return p
    o = r.get('origin', [8, 8, 8])
    ang = math.radians(r['angle'])
    c, s = math.cos(ang), math.sin(ang)
    x, y, z = p[0] - o[0], p[1] - o[1], p[2] - o[2]
    ax = r['axis']
    if ax == 'x':
        y, z = y * c - z * s, y * s + z * c
    elif ax == 'y':
        x, z = x * c + z * s, -x * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return (x + o[0], y + o[1], z + o[2])


def rot_variant(p, xr, yr):
    x, y, z = p
    for _ in range((xr // 90) % 4):
        # x rotation: up -> south? (MC: x=90 turns the model's top toward the south)
        y, z = 16 - z, y
    for _ in range((yr // 90) % 4):
        x, z = 16 - z, x
    return (x, y, z)


def rot_dir(d, xr, yr):
    v = DVEC[d]
    x, y, z = v
    for _ in range((xr // 90) % 4):
        y, z = -z, y
    for _ in range((yr // 90) % 4):
        x, z = -z, x
    for k, vv in DVEC.items():
        if vv == (x, y, z):
            return k
    return d


class Mesher:
    def __init__(self, cells):
        self.cells = cells
        self.tex = []
        self.tidx = {}
        self.quads = []

    def t(self, path, alpha=1.0):
        key = path
        if key not in self.tidx:
            self.tidx[key] = len(self.tex)
            self.tex.append(path)
        return self.tidx[key]

    def opaque(self, p):
        st = self.cells.get(p)
        if st is None:
            return False
        s = short(st[0])
        if 'glass' in s or s.endswith('_leaves'):
            return False
        return is_full_solid(st)

    def add_model(self, pos, v):
        m = model(v['model'])
        xr, yr = v.get('x', 0), v.get('y', 0)
        for el in m['elements']:
            a, b = el['from'], el['to']
            for fname, face in el.get('faces', {}).items():
                cull = face.get('cullface')
                if cull:
                    cd = rot_dir(cull, xr, yr)
                    dv = DVEC[cd]
                    if self.opaque((pos[0] + dv[0], pos[1] + dv[1], pos[2] + dv[2])):
                        continue
                tref = resolve_tex(m['textures'], face.get('texture', ''))
                if not tref:
                    continue
                tpath = tex_file(tref)
                if not os.path.exists(tpath):
                    continue
                corners = FACES[fname](a, b)
                corners = [rot_elem(c, el.get('rotation')) for c in corners]
                corners = [rot_variant(c, xr, yr) for c in corners]
                uv = face.get('uv') or DEF_UV[fname](a, b)
                uvs = [(uv[0], uv[1]), (uv[2], uv[1]), (uv[2], uv[3]), (uv[0], uv[3])]
                k = (face.get('rotation', 0) // 90) % 4
                if k:
                    uvs = uvs[-k:] + uvs[:-k]
                asp = tex_aspect(tpath)
                uvs = [(u / 16.0, 1.0 - (vv / 16.0) * asp) for u, vv in uvs]
                pts = [(pos[0] + c[0] / 16.0, pos[1] + c[1] / 16.0, pos[2] + c[2] / 16.0) for c in corners]
                tint = 1 if face.get('tintindex') is not None else 0
                self.quads.append([pts, uvs, self.t(tpath), tint])

    def box(self, pos, a, b, tpath, uvmap, px=64):
        """uvmap: face -> (u1,v1,u2,v2) in texture pixels, or None to skip."""
        for fname, uv in uvmap.items():
            if uv is None:
                continue
            corners = FACES[fname](a, b)
            s = 16.0 / px
            u1, v1, u2, v2 = [c * s for c in uv[:4]]
            k = uv[4] if len(uv) > 4 else 0
            uvs = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]
            if k:
                uvs = uvs[-k:] + uvs[:-k]
            uvs = [(u / 16.0, 1.0 - vv / 16.0) for u, vv in uvs]
            pts = [(pos[0] + c[0] / 16.0, pos[1] + c[1] / 16.0, pos[2] + c[2] / 16.0) for c in corners]
            self.quads.append([pts, uvs, self.t(tpath), 0])

    def special(self, pos, st):
        s = short(st[0])
        p = st[1]
        mc = ROOTS['minecraft']
        rotk = {'north': 0, 'east': 1, 'south': 2, 'west': 3}
        if s == 'chest':
            tp = os.path.join(mc, 'textures/entity/chest/normal.png')
            f = p.get('facing', 'north')
            side = (14, 33, 28, 43)
            front = (42, 33, 56, 43)
            lid_side = (14, 14, 28, 19)
            lid_front = (42, 14, 56, 19)
            m = {'up': None, 'down': None}
            for d in ('north', 'east', 'south', 'west'):
                m[d] = front if d == f else side
            self.box(pos, (1, 0, 1), (15, 10, 15), tp, m)
            m2 = {'up': (14, 0, 28, 14), 'down': None}
            for d in ('north', 'east', 'south', 'west'):
                m2[d] = lid_front if d == f else lid_side
            self.box(pos, (1, 10, 1), (15, 14, 15), tp, m2)
            return True
        if s.endswith('_bed'):
            color = s[:-4]
            tp = os.path.join(mc, f'textures/entity/bed/{color}.png')
            if not os.path.exists(tp):
                tp = os.path.join(mc, 'textures/entity/bed/red.png')
            f = p.get('facing', 'north')
            top = (6, 6, 22, 22) if p.get('part') == 'head' else (6, 28, 22, 44)
            k = rotk[f]
            m = {'up': top + (k,), 'down': None}
            for d in ('north', 'east', 'south', 'west'):
                m[d] = (22, 22, 38, 25)
            self.box(pos, (0, 3, 0), (16, 9, 16), tp, m)
            leg = (50, 3, 53, 6)
            for (lx, lz) in ((0, 0), (13, 13), (0, 13), (13, 0)):
                self.box(pos, (lx, 0, lz), (lx + 3, 3, lz + 3), tp, {d: leg for d in ('north', 'east', 'south', 'west')})
            return True
        if s == 'water':
            tp = os.path.join(mc, 'textures/block/water_still.png')
            asp = tex_aspect(tp)
            h = 1.003  # drawn at ground height so the preview grass plane never hides it
            pts = [(pos[0], pos[1] + h, pos[2]), (pos[0] + 1, pos[1] + h, pos[2]),
                   (pos[0] + 1, pos[1] + h, pos[2] + 1), (pos[0], pos[1] + h, pos[2] + 1)]
            uvs = [(0, 1), (1, 1), (1, 1 - asp), (0, 1 - asp)]
            self.quads.append([pts, uvs, self.t(tp), 2])
            return True
        if s.endswith('_banner') and not s.endswith('_wall_banner'):
            color = s[:-len('_banner')]
            wool = os.path.join(mc, f'textures/block/{color}_wool.png')
            pole = os.path.join(mc, 'textures/block/stripped_oak_log.png')
            self.box(pos, (7, 0, 7), (9, 30, 9), pole, {d: (0, 0, 16, 16) for d in ('north', 'east', 'south', 'west', 'up')}, px=16)
            self.box(pos, (1, 30, 7), (15, 32, 9), pole, {d: (0, 0, 16, 16) for d in ('north', 'east', 'south', 'west', 'up', 'down')}, px=16)
            self.box(pos, (1, 2, 7.5), (15, 30, 8.5), wool, {d: (0, 0, 16, 16) for d in ('north', 'east', 'south', 'west')}, px=16)
            return True
        if s == 'bell':
            tp = os.path.join(mc, 'textures/entity/bell/bell_body.png')
            self.box(pos, (5, 6, 5), (11, 13, 11), tp, {d: (0, 7, 6, 14) for d in ('north', 'east', 'south', 'west', 'up')}, px=32)
        return False

    def build(self, swaps):
        for pos, st in self.cells.items():
            if pos in swaps:
                st = swaps[pos]
                if st is None:
                    continue
            s = short(st[0])
            if s in ('air', 'structure_void'):
                continue
            if self.special(pos, st):
                continue
            for v in models_for(st[0], st[1]):
                self.add_model(pos, v)


def parse_state(s):
    if '[' in s:
        name, rest = s.split('[', 1)
        props = dict(kv.split('=') for kv in rest.rstrip(']').split(',') if kv)
    else:
        name, props = s, {}
    return name, props


def main():
    roots = json.load(open(sys.argv[1]))
    ROOTS.update(roots)
    ids = sys.argv[2:]
    cdir = os.path.join(OUT, 'cells')
    mdir = os.path.join(OUT, 'mesh')
    os.makedirs(mdir, exist_ok=True)
    if not ids:
        ids = sorted(f[:-5] for f in os.listdir(cdir) if f.endswith('.json'))
    for bid in ids:
        data = json.load(open(os.path.join(cdir, bid + '.json')))
        cells = {tuple(c[:3]): (c[3], c[4]) for c in data['cells']}
        swaps = {}
        for fu in data.get('furniture') or []:
            name, props = parse_state(fu['to'])
            swaps[tuple(fu['pos'])] = None if short(name) == 'air' else (name, props)
        m = Mesher(cells)
        m.build(swaps)
        for p in data.get('pond') or []:
            m.special(tuple(p), ('minecraft:water', {'level': '0'}))
        size = [max(p[i] for p in cells) + 1 for i in range(3)]
        with open(os.path.join(mdir, bid + '.json'), 'w') as f:
            json.dump({'id': bid, 'name': data.get('name'), 'size': size, 'ground_level': data.get('ground_level', 0),
                       'type': data.get('type'), 'pond': data.get('pond') or [], 'textures': m.tex, 'quads': m.quads}, f)
        print(bid, len(m.quads), 'quads', len(m.tex), 'textures')


if __name__ == '__main__':
    main()
