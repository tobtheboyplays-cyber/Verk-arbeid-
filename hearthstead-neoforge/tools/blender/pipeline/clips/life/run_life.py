"""Run several life clip scripts (with per-script arguments) in ONE Blender process.

    blender -b --factory-startup --python run_life.py -- [--fast|--stills|--no-export] author_eat.py@v2 author_rest.py

`script@arg1,arg2` passes positional args (e.g. the variant name) to that script;
flags go to every script. Paths are relative to this folder. A failing clip is
reported and the rest still run (same idea as ../../run_batch.py).
"""

import os
import runpy
import sys
import time
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
flags = [a for a in args if a.startswith("--")]
specs = [a for a in args if not a.startswith("--")]
argv0 = sys.argv[0]
results = []
for spec in specs:
    script, _, extra = spec.partition("@")
    path = script if os.path.isabs(script) else os.path.join(HERE, script)
    t0 = time.time()
    sys.argv = [argv0, "--"] + flags + [x for x in extra.split(",") if x]
    try:
        runpy.run_path(path, run_name="__main__")
        results.append((spec, "ok", time.time() - t0))
    except SystemExit as e:
        results.append((spec, "exit %s" % e.code, time.time() - t0))
    except Exception:
        traceback.print_exc()
        results.append((spec, "FAILED", time.time() - t0))
print("LIFE SUMMARY")
for s, status, dt in results:
    print(f"  {status:8s} {dt:6.1f}s  {s}")
