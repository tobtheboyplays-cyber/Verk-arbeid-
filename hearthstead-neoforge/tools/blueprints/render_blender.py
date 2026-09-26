"""Headless Blender preview renderer for blueprint meshes (bake_mesh.py output).

    blender.exe -b --factory-startup --python tools/blueprints/render_blender.py -- <mesh_dir> <out_dir> [ids...]

One 3/4 view (front-right, elevated, orthographic) per blueprint, real
Minecraft textures with nearest filtering, EEVEE, transparent film.
"""
import json
import math
import os
import sys

import bpy
from mathutils import Vector

argv = sys.argv[sys.argv.index('--') + 1:]
MESH_DIR, OUT_DIR = argv[0], argv[1]
IDS = argv[2:]
os.makedirs(OUT_DIR, exist_ok=True)

scene = bpy.context.scene
for eng in ('BLENDER_EEVEE_NEXT', 'BLENDER_EEVEE'):
    try:
        scene.render.engine = eng
        break
    except TypeError:
        continue
try:
    scene.eevee.taa_render_samples = 24
except Exception:
    pass
for attr, val in (('use_shadows', True), ('use_raytracing', False)):
    try:
        setattr(scene.eevee, attr, val)
    except Exception:
        pass
scene.render.resolution_x = 1200
scene.render.resolution_y = 900
scene.render.film_transparent = True
scene.view_settings.view_transform = 'Standard'
scene.view_settings.look = 'None'
scene.render.image_settings.file_format = 'PNG'
scene.render.image_settings.color_mode = 'RGBA'

# clear
for o in list(bpy.data.objects):
    bpy.data.objects.remove(o, do_unlink=True)

world = bpy.data.worlds.new('w') if not bpy.data.worlds else bpy.data.worlds[0]
scene.world = world
world.use_nodes = True
bg = world.node_tree.nodes.get('Background')
bg.inputs[0].default_value = (0.78, 0.84, 0.95, 1)
bg.inputs[1].default_value = 0.9

sun_data = bpy.data.lights.new('sun', 'SUN')
sun_data.energy = 3.2
sun_data.angle = math.radians(6)
sun = bpy.data.objects.new('sun', sun_data)
scene.collection.objects.link(sun)
sun.rotation_euler = (math.radians(50), math.radians(-18), math.radians(-35))

cam_data = bpy.data.cameras.new('cam')
cam_data.type = 'ORTHO'
cam = bpy.data.objects.new('cam', cam_data)
scene.collection.objects.link(cam)
scene.camera = cam

_mats = {}
_imgs = {}


def material(path, kind):
    key = (path, kind)
    if key in _mats:
        return _mats[key]
    m = bpy.data.materials.new(os.path.basename(path) + str(kind))
    m.use_nodes = True
    nt = m.node_tree
    bsdf = nt.nodes.get('Principled BSDF')
    bsdf.inputs['Roughness'].default_value = 1.0
    for nm in ('Specular IOR Level', 'Specular'):
        if nm in bsdf.inputs:
            bsdf.inputs[nm].default_value = 0.0
    if path not in _imgs:
        img = bpy.data.images.load(path, check_existing=True)
        _imgs[path] = img
    tex = nt.nodes.new('ShaderNodeTexImage')
    tex.image = _imgs[path]
    tex.interpolation = 'Closest'
    col = tex.outputs['Color']
    if kind == 1:  # foliage tint
        mix = nt.nodes.new('ShaderNodeMixRGB'); mix.blend_type = 'MULTIPLY'; mix.inputs[0].default_value = 1
        nt.links.new(col, mix.inputs[1]); mix.inputs[2].default_value = (0.47, 0.74, 0.33, 1)
        col = mix.outputs[0]
    if kind == 2:  # water tint
        mix = nt.nodes.new('ShaderNodeMixRGB'); mix.blend_type = 'MULTIPLY'; mix.inputs[0].default_value = 1
        nt.links.new(col, mix.inputs[1]); mix.inputs[2].default_value = (0.25, 0.46, 0.89, 1)
        col = mix.outputs[0]
    nt.links.new(col, bsdf.inputs['Base Color'])
    if kind == 2:
        bsdf.inputs['Alpha'].default_value = 0.75
    else:
        nt.links.new(tex.outputs['Alpha'], bsdf.inputs['Alpha'])
    try:
        m.surface_render_method = 'DITHERED'
    except Exception:
        m.blend_method = 'CLIP'
    try:
        m.use_backface_culling = False
    except Exception:
        pass
    _mats[key] = m
    return m


def ground(size, gl, pond):
    """A grass-coloured plane just under the plinth top, plus nothing else."""
    sx, sy, sz = size
    pad = 3
    verts = [(-pad, pad, gl + 0.97), (sx + pad, pad, gl + 0.97), (sx + pad, -(sz + pad), gl + 0.97), (-pad, -(sz + pad), gl + 0.97)]
    me = bpy.data.meshes.new('ground')
    me.from_pydata(verts, [], [(0, 1, 2, 3)])
    ob = bpy.data.objects.new('ground', me)
    m = bpy.data.materials.new('groundmat')
    m.use_nodes = True
    b = m.node_tree.nodes.get('Principled BSDF')
    b.inputs['Base Color'].default_value = (0.36, 0.52, 0.25, 1)
    b.inputs['Roughness'].default_value = 1
    me.materials.append(m)
    scene.collection.objects.link(ob)
    return ob


def render(mesh_path, out_path):
    data = json.load(open(mesh_path))
    for o in list(scene.objects):
        if o.name.startswith(('bp', 'ground')):
            bpy.data.objects.remove(o, do_unlink=True)
    verts, faces, uvs, mats = [], [], [], []
    mat_index = {}
    me = bpy.data.meshes.new('bp')
    for pts, uv, ti, kind in data['quads']:
        base = len(verts)
        for (x, y, z) in reversed(pts):
            verts.append((x, -z, y))
        faces.append((base, base + 1, base + 2, base + 3))
        uvs.append(list(reversed(uv)))
        key = (data['textures'][ti], kind)
        if key not in mat_index:
            mat_index[key] = len(mat_index)
            me.materials.append(material(*key))
        mats.append(mat_index[key])
    me.from_pydata(verts, [], faces)
    uvl = me.uv_layers.new(name='uv')
    for poly in me.polygons:
        for k, li in enumerate(poly.loop_indices):
            uvl.data[li].uv = uvs[poly.index][k]
        poly.material_index = mats[poly.index]
    ob = bpy.data.objects.new('bp', me)
    scene.collection.objects.link(ob)
    if data.get('type') is not None or True:
        ground(data['size'], data.get('ground_level', 0), data.get('pond'))
    # camera: 3/4 from the front-right, 30 deg up
    sx, sy, sz = data['size']
    center = Vector((sx / 2, -sz / 2, sy / 2 - 0.5))
    az, el = math.radians(-35), math.radians(30)
    d = Vector((math.sin(-az) * math.cos(el), -math.cos(-az) * math.cos(el), math.sin(el)))
    d = Vector((math.cos(el) * math.sin(math.radians(40)), -math.cos(el) * math.cos(math.radians(40)), math.sin(el)))
    cam.location = center + d * 60
    cam.rotation_euler = (-d).to_track_quat('-Z', 'Y').to_euler()
    # fit ortho scale to the projected bounding box
    right = d.cross(Vector((0, 0, 1))).normalized()
    up = right.cross(d).normalized()
    xs, ys = [], []
    for cx in (0, sx):
        for cy in (0, -sz):
            for cz in (0, sy):
                p = Vector((cx, cy, cz)) - center
                xs.append(p.dot(right)); ys.append(p.dot(up))
    w = max(xs) - min(xs); h = max(ys) - min(ys)
    aspect = scene.render.resolution_x / scene.render.resolution_y
    cam_data.ortho_scale = max(w, h * aspect) * 1.08
    cam.location = center + d * 60 + right * ((max(xs) + min(xs)) / 2) * 0 + up * ((max(ys) + min(ys)) / 2)
    cam_data.clip_end = 200
    scene.render.filepath = out_path
    bpy.ops.render.render(write_still=True)


ids = IDS or sorted(f[:-5] for f in os.listdir(MESH_DIR) if f.endswith('.json'))
for bid in ids:
    render(os.path.join(MESH_DIR, bid + '.json'), os.path.join(OUT_DIR, bid + '.png'))
    print('rendered', bid, flush=True)
