"""Author + export finisher clips in ONE headless Blender process.

    blender -b --factory-startup --python author_finishers.py -- [--fast] [--no-export] [ID ...]

ID is a FinisherVariant id (sword_parry_thrust, ...); none = every clip. --fast renders a
Workbench contact sheet (side + front three-quarter) and a side MP4 per clip into
C:/Users/tobia/Hearthstead-Claude/videos/blender/clips/finisher/. Exports go to
assets/hearthstead/animations/player/finisher_<id>*.animation.json and
assets/hearthstead/animations/<raider|goblin>/finisher_victim_<id>.animation.json.
"""
import json
import os
import sys
import time
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import finkit  # noqa: E402
import finisher_clips  # noqa: E402

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
fast = "--fast" in argv
write = "--no-export" not in argv
names = [a for a in argv if not a.startswith("--")] or (list(finisher_clips.CLIPS) + ["ready"])
results = []
if "ready" in names:
    names.remove("ready")
    results.append(finkit.export_ready("finisher_ready", finisher_clips.ready_hold(), 1.0, write))
for n in names:
    t0 = time.time()
    try:
        clip = finisher_clips.CLIPS[n]()
        r = finkit.run(clip, fast=fast, write=write)
        r["seconds"] = round(time.time() - t0, 1)
        results.append(r)
    except Exception:
        traceback.print_exc()
        results.append({"id": n, "error": traceback.format_exc(limit=3)})
out = os.path.join(finkit.WORK, "out", "finisher")
os.makedirs(out, exist_ok=True)
with open(os.path.join(out, "summary_" + "_".join(names[:3]) + ".json"), "w") as fh:
    json.dump(results, fh, indent=1)
print("FINISHER SUMMARY")
for r in results:
    print("  ", json.dumps(r))
