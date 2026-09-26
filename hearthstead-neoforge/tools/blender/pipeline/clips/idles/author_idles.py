"""All settler idles (base + job idles + variants) in ONE headless Blender run.

    blender -b --factory-startup --python author_idles.py -- [--fast] [--review] [--no-export] [stem ...]

stem filters (e.g. `idle_baker` also runs idle_baker__v2/__v3). Specs: idle_specs.py,
generator: idlekit.py. JSON -> assets/hearthstead/animations/settler/<stem>.animation.json,
previews -> C:/Users/tobia/Hearthstead-Claude/videos/blender/clips/idles/<stem>_sheet.png / _side.mp4.
"""
import importlib
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import idlekit  # noqa: E402
import idle_specs  # noqa: E402
importlib.reload(idlekit)
importlib.reload(idle_specs)

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
args = {"fast": "--fast" in argv, "review": "--review" in argv,
        "export": "--no-export" not in argv, "rest": [a for a in argv if not a.startswith("--")]}
t0 = time.time()
res = idlekit.run(idle_specs.SPECS, args)
print("IDLES DONE", len(res), "clips in", round(time.time() - t0, 1), "s")
