"""Anim-lane helpers for the trade clips: preview-only recolouring of the held prop sprite
(Workbench shows material colours, not the sprite's vertex colours)."""


def recolour_sprite(name="mesh:axe"):
    import bpy
    ob = bpy.data.objects.get(name)
    if ob is None:
        return
    me = ob.data
    col = me.color_attributes.get("Col")
    if col is None:
        return
    mats = {}
    me.materials.clear()
    for poly in me.polygons:
        cc = tuple(round(v, 2) for v in col.data[poly.loop_indices[0]].color)
        if cc not in mats:
            m = bpy.data.materials.new(f"sprite_{len(mats)}")
            m.diffuse_color = cc
            m.use_nodes = False
            me.materials.append(m)
            mats[cc] = len(mats)
        poly.material_index = mats[cc]
