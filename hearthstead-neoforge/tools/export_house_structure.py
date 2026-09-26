#!/usr/bin/env python3
"""Export a region of a real Hearthstead world into a GameTest structure.

Made for the pathing regression fixture ``data/hearthstead/structure/
path_server_house.nbt`` (the owner's two-storey house and barracks, server
copy 2026-09-25, region x101..120 y70..81 z-135..-115)::

    python tools/export_house_structure.py <world-dir> <out.nbt>         101 70 -135 120 81 -115

Reads the Anvil region files directly (read-only; the world is never
opened by Minecraft). Another Furniture tables and chairs become oak fences
(non-walkable obstacles of the same footprint), the hearth becomes
cobblestone, occupied beds become unoccupied and plaques unregistered.
Requires ``nbtlib``.
"""
import sys
import nbtlib
from nbtlib import Compound, List, Int, String
import zlib, struct, io
WORLD=None
_cache={}
def chunk(cx,cz):
    key=(cx,cz)
    if key in _cache: return _cache[key]
    rx,rz=cx>>5,cz>>5
    with open(f'{WORLD}/region/r.{rx}.{rz}.mca','rb') as f: data=f.read()
    i=4*((cx&31)+(cz&31)*32)
    off=int.from_bytes(data[i:i+3],'big'); 
    if off==0: _cache[key]=None; return None
    o=off*4096
    ln=struct.unpack('>I',data[o:o+4])[0]; comp=data[o+4]
    raw=zlib.decompress(data[o+5:o+4+ln])
    nbt=nbtlib.File.parse(io.BytesIO(raw))
    secs={}
    for s in nbt['sections']:
        y=int(s['Y'])
        if 'block_states' not in s: continue
        bs=s['block_states']; pal=bs['palette']
        names=[]
        for p in pal:
            n=str(p['Name']).replace('minecraft:','')
            if 'Properties' in p:
                n+='['+','.join(f'{k}={v}' for k,v in p['Properties'].items())+']'
            names.append(n)
        arr=None
        if 'data' in bs:
            bits=max(4,(len(pal)-1).bit_length())
            per=64//bits; mask=(1<<bits)-1
            longs=[int(x)&0xFFFFFFFFFFFFFFFF for x in bs['data']]
            arr=[]
            for idx in range(4096):
                l=longs[idx//per]; arr.append((l>>((idx%per)*bits))&mask)
        secs[y]=(names,arr)
    _cache[key]=secs
    return secs
def block(x,y,z):
    secs=chunk(x>>4,z>>4)
    if not secs: return '?'
    s=secs.get(y>>4)
    if not s: return 'air'
    names,arr=s
    if arr is None: return names[0]
    return names[arr[((y&15)*16+(z&15))*16+(x&15)]]

if __name__ == '__main__':
    WORLD = sys.argv[1]
    out = sys.argv[2]
    X0, Y0, Z0, X1, Y1, Z1 = [int(a) for a in sys.argv[3:9]]
    """Export the owner's real two-storey house (server copy 2026-09-25) into a
    GameTest structure. Another Furniture tables/chairs -> oak_fence (a
    non-walkable obstacle of the same footprint), hearth -> cobblestone,
    occupied beds -> unoccupied. Everything else verbatim."""
    def parse(s):
        name=s.split('[')[0]
        if ':' not in name: name='minecraft:'+name
        props={}
        if '[' in s:
            for kv in s[s.index('[')+1:-1].split(','):
                k,v=kv.split('='); props[k]=v
        return name,props
    def remap(name,props):
        if name.startswith('another_furniture:'):
            return 'minecraft:oak_fence',{}
        if name=='hearthstead:hearth': return 'minecraft:cobblestone',{}
        if name.endswith('_bed') and 'occupied' in props: props=dict(props,occupied='false')
        if name=='hearthstead:plaque': props=dict(props,registered='false')
        if name=='minecraft:cave_air': return 'minecraft:air',{}
        return name,props
    palette=[]; index={}; blocks=[]
    for y in range(Y0,Y1+1):
      for z in range(Z0,Z1+1):
        for x in range(X0,X1+1):
          n,p=remap(*parse(block(x,y,z)))
          key=(n,tuple(sorted(p.items())))
          if key not in index:
            index[key]=len(palette)
            c=Compound({'Name':String(n)})
            if p: c['Properties']=Compound({k:String(v) for k,v in p.items()})
            palette.append(c)
          blocks.append(Compound({'pos':List[Int]([Int(x-X0),Int(y-Y0),Int(z-Z0)]),'state':Int(index[key])}))
    root=Compound({'DataVersion':Int(3955),
      'size':List[Int]([Int(X1-X0+1),Int(Y1-Y0+1),Int(Z1-Z0+1)]),
      'palette':List[Compound](palette),'blocks':List[Compound](blocks),'entities':List[Compound]([])})
    nbtlib.File(root).save(out,gzipped=True)
    print('saved',out,'size',X1-X0+1,Y1-Y0+1,Z1-Z0+1,'palette',len(palette))
    for c in palette: print('  ',c['Name'],dict(c.get('Properties',{})) if 'Properties' in c else '')
