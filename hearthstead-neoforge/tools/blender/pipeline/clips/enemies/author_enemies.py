"""Author + export enemy clips in ONE headless Blender process.

    blender -b --factory-startup --python author_enemies.py -- [--fast] [--no-export] [NAME ...]

NAME is a clip constant (STALK, RAIDER_HEAVY, GOBLIN_FLEE, ...); none = every raider and
goblin clip. --fast renders the Workbench contact sheet + side MP4 per clip into
C:/Users/tobia/Hearthstead-Claude/videos/blender/clips/enemies/.
Clips with a Java constant export into assets/hearthstead/animations/<rig>/; clips the
runtime cannot load yet (no constant) go to clips/enemies/staged/<rig>/.
"""
import json
import os
import sys
import time
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import enemyrig as er  # noqa: E402
import raider_clips  # noqa: E402
import goblin_clips  # noqa: E402

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
fast = "--fast" in argv
export = "--no-export" not in argv
ALL = dict(raider_clips.CLIPS)
ALL.update(goblin_clips.CLIPS)
names = [a for a in argv if not a.startswith("--")] or list(ALL)
results = []
for n in names:
    t0 = time.time()
    try:
        clip = ALL[n]()
        if n in getattr(raider_clips, "STAGE_UNTIL_JAVA", ()):
            clip["staged"] = True
        r = er.run(clip, fast=fast, export=export)
        r["seconds"] = round(time.time() - t0, 1)
        results.append(r)
    except Exception:
        traceback.print_exc()
        results.append({"const": n, "error": traceback.format_exc(limit=2)})
os.makedirs(os.path.join(er.WORK, "out", "enemies"), exist_ok=True)
with open(os.path.join(er.WORK, "out", "enemies", "summary_" + "_".join(names[:3]) + ".json"), "w") as fh:
    json.dump(results, fh, indent=1)
print("ENEMY SUMMARY")
for r in results:
    print("  ", json.dumps(r))
