"""Author + export several clip scripts in ONE Blender process (Blender starts once).

    blender -b --factory-startup --python run_batch.py -- [--fast|--full] clipA.py clipB.py ...

Every flag before/between the script paths (--fast, --full, --no-export) is
passed to every script as if it had been run on its own
(`blender ... --python clipA.py -- --fast`). Script paths may be absolute or
relative to this folder. One failing clip is reported and the batch goes on.
"""

import os
import runpy
import sys
import time
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
flags = [a for a in args if a.startswith("--")]
scripts = [a for a in args if not a.startswith("--")]
results = []
argv0 = sys.argv[0]
for s in scripts:
    path = s if os.path.isabs(s) else os.path.join(HERE, s)
    if not os.path.exists(path) and os.path.exists(os.path.abspath(s)):
        path = os.path.abspath(s)
    t0 = time.time()
    sys.argv = [argv0, "--"] + flags
    try:
        runpy.run_path(path, run_name="__main__")
        results.append((s, "ok", time.time() - t0))
    except SystemExit as e:
        results.append((s, "exit %s" % e.code, time.time() - t0))
    except Exception:
        traceback.print_exc()
        results.append((s, "FAILED", time.time() - t0))
print("BATCH SUMMARY")
for s, status, dt in results:
    print(f"  {status:8s} {dt:6.1f}s  {s}")
