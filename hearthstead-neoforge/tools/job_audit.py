#!/usr/bin/env python3
"""Scores every trade against the job standard, and refuses regressions.

`docs/project/JOB_STANDARD.md` says what a finished job is. This makes that
executable: eleven checks per trade, a table you can read at a glance, and a
non-zero exit if anything on the CERTIFIED list has slipped.

Certification is a **ratchet**. A trade goes on the list the day it passes all
eleven, and from then on the build fails if it stops passing — which is the
only way a standard survives a refactor that quietly drops a sound or borrows
somebody else's animation.

    python3 tools/job_audit.py            # the table
    python3 tools/job_audit.py --all      # fail on ANY incomplete trade
"""
import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "src", "main")
JAVA = os.path.join(SRC, "java", "com", "hearthstead")
ASSETS = os.path.join(SRC, "resources", "assets", "hearthstead")
DOCS = os.path.join(HERE, "..", "docs")

# Trades that have met every point. Add a trade here the day it passes; never
# remove one to make a build green -- fix the trade instead.
# The starter pack, the set both references hand you first. Every one of
# these passes all eleven points; from here the build fails if any of them
# stops passing.
CERTIFIED = {"lumberer", "farmer", "courier", "guard", "miner", "baker",
             "smith"}

POINTS = ["trade", "work", "goal", "motion", "catalogue", "sound",
          "outfit", "trains", "schedule", "lang", "tests"]


def read(*parts):
    path = os.path.join(*parts)
    if not os.path.isfile(path):
        return ""
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def java(*parts):
    return read(JAVA, *parts)


def trades():
    """Every profession except NONE, with the building that practises it."""
    prof = java("entity", "Profession.java")
    employ = java("settlement", "Employment.java")
    found = {}
    for m in re.finditer(r'^\s{4}([A-Z_]+)\(\d+,\s*"([a-z_]+)"', prof, re.M):
        if m.group(1) == "NONE":
            continue
        found[m.group(2)] = {"const": m.group(1), "building": None}
    for m in re.finditer(r"TRADES\.put\(BuildingType\.([A-Z_]+),\s*Profession\.([A-Z_]+)\)",
                         employ):
        for key, info in found.items():
            if info["const"] == m.group(2) and info["building"] is None:
                info["building"] = m.group(1)
    return found


def audit(key, info):
    employ = java("settlement", "Employment.java")
    prod = java("building", "Production.java")
    anims = java("client", "model", "SettlerAnimations.java")
    entity = java("entity", "SettlerEntity.java")
    catalogue = read(DOCS, "ANIMATION_CATALOGUE.md")
    building = info["building"]
    result = {}

    # 1. A trade, and a building that practises it.
    result["trade"] = building is not None

    # 2. Work that exists on its own: recipes, or a goal named for the trade.
    goal_files = os.listdir(os.path.join(JAVA, "entity", "ai"))
    # Prefer the goal that IS the job -- a guard has GuardMeleeGoal,
    # GuardPatrolGoal and GuardRespondToAlertGoal, and only the patrol is
    # their work. Alphabetical order would have picked the melee one and
    # reported a guard as having no schedule.
    candidates = [f for f in goal_files
                  if f.lower().startswith(key.replace("_", "")[:5])
                  and f.endswith("Goal.java") and "Target" not in f]
    candidates.sort(key=lambda f: (0 if ("Work" in f or "Patrol" in f) else 1, f))
    own_goal = candidates[0] if candidates else None
    has_recipes = building is not None and f"put(BuildingType.{building}," in prod
    result["work"] = bool(own_goal) or has_recipes

    # 3. A work goal that spends real time on it.
    goal_src = java("entity", "ai", own_goal) if own_goal else \
        java("entity", "ai", "CrafterWorkGoal.java")
    result["goal"] = "ticks" in goal_src or "Ticks" in goal_src

    # 4/5. Its own motion, authored and catalogued.
    motion = None
    if building:
        block = re.search(r"public static SettlerActivity motionOf\(BuildingType type\) \{(.+?)\n    \}",
                          employ, re.S)
        if block:
            for line in block.group(1).splitlines():
                if info["const"] in line and "->" in line:
                    m = re.search(r"SettlerActivity\.([A-Z_]+)", line)
                    if m:
                        motion = m.group(1)
    clip = CLIP_FOR.get(key) or (motion and MOTION_CLIP.get(motion))
    result["motion"] = bool(clip) and f"AnimationDefinition {clip}" in anims
    result["catalogue"] = bool(clip) and re.search(
        r"^### \d+\.\d+ `%s`" % re.escape(clip or "-"), catalogue, re.M) is not None

    # 6. A distinct sound, played from the work goal.
    sounds = json.loads(read(ASSETS, "sounds.json") or "{}")
    played = re.findall(r"ModSounds\.([A-Z_]+)\.get\(\)", goal_src)
    work_sounds = [s for s in played
                   if s.lower() in sounds and s not in ("SETTLER_HM",)]
    # A crafter's goal does not name its sound directly: it asks
    # Employment.soundOf for the one belonging to its motion. That is still a
    # distinct sound per trade -- more rigorously so, since the table is
    # exhaustive over motions.
    resolves = "Employment.soundOf(" in goal_src
    result["sound"] = bool(work_sounds) or resolves

    # 7. An outfit you can read across a square.
    result["outfit"] = os.path.isfile(os.path.join(
        ASSETS, "textures", "entity", "settler", "layers", f"outfit_{key}.png"))

    # 8. Doing the job makes you better at it.
    result["trains"] = ".train(" in goal_src or (
        building is not None and "CrafterWorkGoal" in goal_src and ".train(" in goal_src)

    # 9. Obeys the village day.
    result["schedule"] = ("dayPhase()" in goal_src or "Schedule." in goal_src)

    # 10. Legible in both languages.
    en = json.loads(read(ASSETS, "lang", "en_us.json") or "{}")
    nb = json.loads(read(ASSETS, "lang", "nb_no.json") or "{}")
    lang_key = f"hearthstead.profession.{key}"
    result["lang"] = lang_key in en and lang_key in nb

    # 11. Named tests that drive it.
    tests = ""
    test_dir = os.path.join(JAVA, "gametest")
    for name in os.listdir(test_dir):
        tests += read(test_dir, name)
    result["tests"] = (f"Profession.{info['const']}" in tests
                       or (building and f"BuildingType.{building}" in tests))
    return result


# A trade whose clip is not derivable from motionOf (the four that predate
# CHAINS-1 and own bespoke clips).
CLIP_FOR = {
    "lumberer": "CHOP",
    "farmer": "SOW_BROADCAST",
    "courier": "COURIER_CARRY",
    "guard": "GUARD_STANCE",
}

MOTION_CLIP = {
    "WORK_KNEAD": "KNEAD",
    "WORK_CLEAVE": "CLEAVE",
    "WORK_STOKE": "STOKE",
    "WORK_HAMMER": "HAMMER_ANVIL",
    "WORK_SAW": "SAW",
    "WORK_WEAVE": "FINE_WORK",
    "WORK_OVEN": "OVEN_TEND",
    "WORK_MINE": "MINE_PICK",
    "WORK_SOW": "SOW_BROADCAST",
    # VISUAL-2: the five signature motions that ended the borrowing (D-016).
    "WORK_STIR": "COOK_STIR",
    "WORK_PLANE": "CARPENTER_PLANE",
    "WORK_CHISEL": "MASON_CHISEL",
    "WORK_FLETCH": "FLETCHER_FLETCH",
    "WORK_SCRAPE": "TANNER_SCRAPE",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true",
                    help="fail on any incomplete trade, not only certified ones")
    args = ap.parse_args()

    found = trades()
    width = max(len(k) for k in found)
    header = "trade".ljust(width) + "  " + " ".join(p[:4].rjust(4) for p in POINTS)
    print(header)
    print("-" * len(header))

    failures = []
    for key in sorted(found):
        scores = audit(key, found[key])
        row = key.ljust(width) + "  " + " ".join(
            ("  ok" if scores[p] else "  --").rjust(4) for p in POINTS)
        complete = all(scores.values())
        mark = " [certified]" if key in CERTIFIED else ""
        print(row + mark + ("" if complete else "   <- incomplete"))
        missing = [p for p in POINTS if not scores[p]]
        if missing and (key in CERTIFIED or args.all):
            failures.append(f"{key}: missing {', '.join(missing)}")

    print()
    if failures:
        print("FAIL  a job below the standard (docs/project/JOB_STANDARD.md):")
        for line in failures:
            print(f"    {line}")
        return 1
    print(f"PASS  {len(CERTIFIED)} certified trade(s) meet all "
          f"{len(POINTS)} points of the job standard")
    return 0


if __name__ == "__main__":
    sys.exit(main())
