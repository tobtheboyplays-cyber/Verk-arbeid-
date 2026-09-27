"""Save an unanimated settler rig .blend (+ optional test stills).

    blender -b --factory-startup --python build_rig_blend.py -- [--texture settler_x.png] [--render]
"""
import os
import sys

import bpy

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import hsrig  # noqa: E402

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
WORK = os.environ.get("HS_PIPELINE", r"C:\Users\tobia\Hearthstead-Claude\tools\blender-pipeline")
tex_name = argv[argv.index("--texture") + 1] if "--texture" in argv else "settler_lumberer.png"
TEX = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "textures",
                   "entity", "settler", tex_name)
AXE = os.path.join(WORK, "ref", "assets", "minecraft", "textures", "item", "iron_axe.png")

hsrig.reset()
hsrig.build_scene(TEX, AXE)
hsrig.prop_box("ground", (-40, 24, -40), (80, 1, 80), (0.30, 0.45, 0.22, 1))
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(WORK, "settler_rig.blend"))
if "--render" in argv:
    hsrig.setup_render(res=(640, 480))
    for name, loc in (("front34", (-1.6, -2.4, 1.5)), ("back34", (2.4, 1.6, 1.3))):
        cam = hsrig.camera("cam_" + name, loc, (0, 0, 0.95), lens=45)
        hsrig.render_frames(cam, os.path.join(WORK, "out", "rigtest_" + name), [0])
