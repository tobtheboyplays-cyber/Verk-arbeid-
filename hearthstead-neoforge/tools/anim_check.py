#!/usr/bin/env python3
"""Static QA for SettlerAnimations.java: parses every keyframe channel and
checks it against docs/ANIMATION_CATALOGUE.md's §17 assertion list --
structural sanity (bone whitelist, tick grid, loop closure, amplitude caps,
duplicate channels), the sound-sync contract (accent-frame keyframe exists,
matches the goal's tick-modulo math, the sound exists in sounds.json), and
craft rules (work clips have legs, carry clips lock their arms, one-shots
return to neutral, catalogue coverage).

No client boot required -- pure source parsing, so this stays in the fast
gate (tools/hearthstead-qa animation)."""
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(__file__), "..")
SRC = os.path.join(ROOT, "src/main/java/com/hearthstead/client/model/SettlerAnimations.java")
AI_DIR = os.path.join(ROOT, "src/main/java/com/hearthstead/entity/ai")
SOUNDS_JSON = os.path.join(ROOT, "src/main/resources/assets/hearthstead/sounds.json")
CATALOGUE = os.path.join(os.path.dirname(__file__), "..",
                         "docs", "ANIMATION_CATALOGUE.md")

BONE_WHITELIST = {"root", "torso", "head", "right_arm", "left_arm",
                  "right_leg", "left_leg", "cloak"}

# Clips exempt from "every clip touches >= 3 bones" (check 17): they are
# genuinely 1-2 bone additive layers by design.
BONE_COUNT_EXEMPT = {"GUARD_PATROL"}  # arms + head only, by design (§4.2)

# Clips exempt from "cloak motion on loops >= 1s" (check 18): the load pins
# the cloak still, per §0.5.
CLOAK_PIN_ALLOWLIST = {"SLEEP_IN_BED", "SHIELD_BLOCK"}

# Clips exempt from "work clips have legs" (check 20): layers, or clips the
# catalogue explicitly scopes to arms/torso/head/cloak only (EAT: "Kept as
# -is... add only cloak, root" -- no leg instruction was ever given).
LEGS_EXEMPT = {"IDLE", "GUARD_PATROL", "EAT"}

# One-shots allowed to end away from their start pose (§17.4 check 21).
ENDS_IN_POSE_ALLOWLIST = set()  # none in A1; COURIER_LIFT/HEAL_REVIVE are A2/A3.

# Clips declared as carry/arm layers (§16.2) -- must lock arm rotation to
# <= 6 degrees of travel (§17.4 check 22). None of A1's clips are carry
# layers in the strict §16.2 sense (WALK_LADEN doesn't exist yet); HAUL_LOG
# is self-contained this phase (see its own doc comment), so it is NOT
# checked as a carry layer here -- it deliberately isn't one yet.
CARRY_LAYER_CLIPS = set()

# Head-tracking damping table (§17.4 check 24), for clips this phase gives a
# non-default damp value to. Cross-checked against SettlerModel.java's damp
# table by grepping for each literal below.
DAMPING_TABLE = {
    "SLEEP_IN_BED": 0.0,
    "SHIELD_BLOCK": 0.15,
    "CLIMB_LADDER": 0.3,
    "RUN_PANIC": 0.4,
    "REST": 0.25,
    "EAT": 0.25,
}

# Clips requiring a per-entity phase-offset or amplitude-jitter call site
# (§17.4 check 25).
PER_ENTITY_VARIATION_CLIPS = {"CELEBRATE", "SLEEP_IN_BED", "WAKE_STRETCH", "IDLE"}

# Sound-sync contract table (§17.3 check 13): one row per accent frame.
# (clip, bone, target, accent_seconds, sound_field, tick, period)
# sound_field is the ModSounds constant name; tick/period are the goal-side
# workTicks%period==tick this accent must match exactly.
SOUND_CONTRACTS = [
    ("FARM_TILL", "right_arm", "ROTATION", 0.60, "FARMER_WORK", 12, 30),
    ("FARM_PLANT", "right_arm", "ROTATION", 0.70, "SEED_PRESS", 14, 40),
    ("FARM_HARVEST", "right_arm", "ROTATION", 0.45, "CROP_PULL", 9, 36),
    ("FARM_WATER", "right_arm", "ROTATION", 0.80, "WATER_POUR", 16, 48),
    ("CHOP", "right_arm", "ROTATION", 0.55, "CHOP", 11, 20),
    ("LIMB_BRANCHES", "right_arm", "ROTATION", 0.30, "CHOP", 6, 26),
    ("HAUL_LOG", "right_arm", "ROTATION", 1.20, "SETTLER_HM", 24, 48),
]

# Sounds reused via SoundEvent pitch parameter rather than a new asset --
# the goal-side call for these plays an *existing* registered sound at a
# different pitch, so "sound existence" (check 15) still resolves correctly
# against sounds.json under the sound's own canonical key, not a synthetic
# one. LIMB_BRANCHES reuses CHOP; HAUL_LOG reuses SETTLER_HM.


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    return text


def parse_definitions(path):
    text = strip_comments(open(path, encoding="utf-8").read())
    defs = {}
    for m in re.finditer(
            r'AnimationDefinition (\w+) = AnimationDefinition\.Builder\s*'
            r'\.withLength\(([\d.]+)F\)(\.looping\(\))?(.*?)\.build\(\);',
            text, re.S):
        name, length, looping, body = m.group(1), float(m.group(2)), bool(m.group(3)), m.group(4)
        channels = []
        for cm in re.finditer(
                r'\.addAnimation\("(\w+)",\s*new AnimationChannel\((\w+),(.*?)\)\)\s*'
                r'(?=\.addAnimation|\Z)',
                body, re.S):
            bone, target, kbody = cm.group(1), cm.group(2), cm.group(3)
            frames = []
            for km in re.finditer(
                    r'new Keyframe\(([\d.]+)F,\s*KeyframeAnimations\.(\w+)\('
                    r'([-\d.F]+),\s*([-\d.F]+),\s*([-\d.F]+)\),\s*(\w+)\)', kbody):
                t = float(km.group(1))
                vec = tuple(float(v.rstrip('F')) for v in km.group(3, 4, 5))
                frames.append((t, km.group(2), vec, km.group(6)))
            channels.append((bone, target, frames))
        defs[name] = dict(length=length, looping=looping, channels=channels)
    return defs


def parse_catalogue_clip_names(path):
    """Clip names declared in the catalogue's numbered §N.M headings.
    One heading (§12.5) declares two names joined by 'and'."""
    if not os.path.isfile(path):
        return set()
    text = open(path, encoding="utf-8").read()
    names = set()
    for m in re.finditer(r'^### \d+\.\d+ `([A-Z_]+)`(?:\s*\(?\+?`([A-Z_]+)`)?', text, re.M):
        names.add(m.group(1))
        if m.group(2):
            names.add(m.group(2))
    # §12.5 heading form: "`SOCIAL_TALK` and `SOCIAL_LISTEN`"
    for m in re.finditer(r'^### \d+\.\d+ `([A-Z_]+)` and `([A-Z_]+)`', text, re.M):
        names.add(m.group(1))
        names.add(m.group(2))
    # Sub-variant names in parens, e.g. "HEAL_REVIVE (+REVIVE_SUCCESS/REVIVE_FAIL)"
    for m in re.finditer(r'\(\+\s*`([A-Z_]+)`(?:/`([A-Z_]+)`)?\)', text):
        names.add(m.group(1))
        if m.group(2):
            names.add(m.group(2))
    return names


def parse_goal_tick_contracts(ai_dir):
    """Every `x % N == K` pattern in the AI goal sources, as {(N, K): [locations]}."""
    contracts = {}
    if not os.path.isdir(ai_dir):
        return contracts
    for fn in sorted(os.listdir(ai_dir)):
        if not fn.endswith(".java"):
            continue
        path = os.path.join(ai_dir, fn)
        text = strip_comments(open(path, encoding="utf-8").read())
        for i, line in enumerate(text.splitlines(), 1):
            m = re.search(r'\w+\s*%\s*(\w+|\d+)\s*==\s*(\d+)', line)
            if not m:
                continue
            period_tok, tick = m.group(1), int(m.group(2))
            period = int(period_tok) if period_tok.isdigit() else None
            if period is None:
                # Resolve a named constant like HARVEST_DURATION from the
                # same file (private static final int X = N;).
                cm = re.search(rf'\b{re.escape(period_tok)}\s*=\s*(\d+)\s*;', text)
                if cm:
                    period = int(cm.group(1))
            contracts.setdefault((period, tick), []).append(f"{fn}:{i}")
    return contracts


def load_sounds_json():
    if not os.path.isfile(SOUNDS_JSON):
        return {}
    with open(SOUNDS_JSON, encoding="utf-8") as f:
        return json.load(f)


def check_pipeline_present_in_model(model_path, needle):
    if not os.path.isfile(model_path):
        return False
    return needle in open(model_path, encoding="utf-8").read()


def main():
    errors = []
    warns = []
    defs = parse_definitions(SRC)
    assert defs, "no definitions parsed"

    # ---- 17.1 keep + 17.2 structural -----------------------------------
    for name, d in defs.items():
        assert d["channels"], f"{name}: no channels parsed"
        seen_bone_targets = set()
        touched_bones = set()
        for bone, target, frames in d["channels"]:
            label = f"{name}.{bone}.{target}"
            if bone not in BONE_WHITELIST:
                errors.append(f"{label}: '{bone}' is not one of the eight whitelisted bones")
            key = (bone, target)
            if key in seen_bone_targets:
                errors.append(f"{label}: duplicate (bone, target) channel -- the second "
                              f"silently discards the first")
            seen_bone_targets.add(key)
            touched_bones.add(bone)
            if not frames:
                errors.append(f"{label}: no keyframes parsed")
                continue
            if d["looping"] and len(frames) < 2:
                errors.append(f"{label}: looping clip has a single-keyframe channel")
            times = [f[0] for f in frames]
            if times != sorted(times):
                errors.append(f"{label}: timestamps not ascending: {times}")
            if times[-1] > d["length"] + 1e-6:
                errors.append(f"{label}: last key {times[-1]} exceeds length {d['length']}")
            for t in times + [d["length"]]:
                # Tick grid: every timestamp and the clip length must be a
                # multiple of 0.05s (within float tolerance).
                ticks = t / 0.05
                if abs(ticks - round(ticks)) > 1e-3:
                    errors.append(f"{label}: {t}s is not on the 0.05s tick grid")
            if d["looping"]:
                first, last = frames[0][2], frames[-1][2]
                if any(abs(a - b) > 0.01 for a, b in zip(first, last)):
                    errors.append(f"{label}: loop does not close: {first} -> {last}")
                if abs(times[-1] - d["length"]) > 1e-6:
                    errors.append(f"{label}: looping channel ends at {times[-1]}, "
                                  f"not at length {d['length']} (holds last pose, "
                                  f"desyncs from the other channels across the loop)")
            for t, kind, vec, interp in frames:
                if kind == "degreeVec" and any(abs(v) > 180 for v in vec):
                    errors.append(f"{label}@{t}: rotation beyond 180deg: {vec}")
                if kind == "posVec" and any(abs(v) > 12 for v in vec):
                    errors.append(f"{label}@{t}: position offset beyond 12px: {vec}")
                if kind == "scaleVec" and any(v < 0.5 or v > 1.5 for v in vec):
                    errors.append(f"{label}@{t}: extreme scale: {vec}")

        if name not in BONE_COUNT_EXEMPT and len(touched_bones) < 3:
            errors.append(f"{name}: touches only {len(touched_bones)} bone(s) "
                          f"({sorted(touched_bones)}) -- looks like a placeholder")

        # 17.4-18: cloak motion on loops >= 1.0s. Bone-count-exempt layer
        # clips (e.g. GUARD_PATROL) deliberately touch only a couple of
        # bones and are exempt from this too, for the same reason.
        if d["looping"] and d["length"] >= 1.0 and name not in CLOAK_PIN_ALLOWLIST \
                and name not in BONE_COUNT_EXEMPT:
            cloak_channels = [c for c in d["channels"] if c[0] == "cloak"]
            has_motion = any(
                any(abs(a - b) > 0.01 for a, b in zip(c[2][0][2], c[2][-1][2]))
                or len(c[2]) > 2
                for c in cloak_channels)
            if not cloak_channels:
                warns.append(f"{name}: no cloak channel on a >=1s loop (allowlist it in "
                             f"CLOAK_PIN_ALLOWLIST if the load genuinely pins it)")
            elif not has_motion and len(cloak_channels[0][2]) < 3:
                warns.append(f"{name}: cloak channel present but static -- "
                             f"the cape should have secondary motion")

        # 17.4-20: work clips have legs.
        if name not in LEGS_EXEMPT and "right_leg" not in touched_bones \
                and "left_leg" not in touched_bones:
            errors.append(f"{name}: no right_leg/left_leg channel -- the settler will "
                          f"read as floating (add LEGS_EXEMPT if this is deliberate)")

        # 17.4-21: one-shots return to neutral.
        if not d["looping"] and name not in ENDS_IN_POSE_ALLOWLIST:
            for bone, target, frames in d["channels"]:
                if len(frames) < 2:
                    continue
                first, last = frames[0][2], frames[-1][2]
                tol = 3.0 if frames[0][1] == "degreeVec" else (0.5 if frames[0][1] == "posVec" else 0.01)
                if any(abs(a - b) > tol for a, b in zip(first, last)):
                    errors.append(f"{name}.{bone}.{target}: one-shot does not return to its "
                                  f"start pose ({first} -> {last}) -- the settler will snap "
                                  f"when it expires")

        # 17.4-22: carry-layer arms are locked (<=6deg travel).
        if name in CARRY_LAYER_CLIPS:
            for bone, target, frames in d["channels"]:
                if bone not in ("right_arm", "left_arm") or target != "ROTATION":
                    continue
                vecs = [f[2] for f in frames]
                for axis in range(3):
                    span = max(v[axis] for v in vecs) - min(v[axis] for v in vecs)
                    if span > 6.0:
                        errors.append(f"{name}.{bone}: carry layer arm travels {span:.1f}deg "
                                      f"on axis {axis} (limit 6deg) -- a 'locked' arm that "
                                      f"visibly swings breaks the whole carry read")

    # 17.2-12: catalogue coverage.
    catalogued = parse_catalogue_clip_names(CATALOGUE)
    if catalogued:
        implemented = set(defs.keys())
        missing = sorted(catalogued - implemented)
        uncatalogued = sorted(implemented - catalogued)
        if missing:
            warns.append(f"catalogued but not yet implemented ({len(missing)}, phased "
                         f"authoring expected): {', '.join(missing[:12])}"
                         + (" ..." if len(missing) > 12 else ""))
        for name in uncatalogued:
            errors.append(f"{name}: implemented but not in ANIMATION_CATALOGUE.md -- "
                          f"every clip must be designed there first")
    else:
        warns.append("ANIMATION_CATALOGUE.md not found or has no clip headings -- "
                     "skipped catalogue-coverage check")

    # 17.3: sound-sync contract table.
    goal_contracts = parse_goal_tick_contracts(AI_DIR)
    sounds_data = load_sounds_json()
    for clip, bone, target, accent_s, sound_field, tick, period in SOUND_CONTRACTS:
        d = defs.get(clip)
        if d is None:
            errors.append(f"sound contract for {clip}: clip not implemented")
            continue
        chan = next((c for c in d["channels"] if c[0] == bone and c[1] == target), None)
        if chan is None:
            errors.append(f"{clip}: sound contract channel {bone}.{target} not found")
            continue
        hit = next((f for f in chan[2] if abs(f[0] - accent_s) < 1e-6), None)
        if hit is None:
            errors.append(f"{clip}: no keyframe at accent_seconds={accent_s} on "
                          f"{bone}.{target}")
        elif hit[3] != "LINEAR":
            errors.append(f"{clip}@{accent_s}: accent keyframe interpolation is "
                          f"{hit[3]}, must be LINEAR")
        expect_tick = round(accent_s * 20)
        if expect_tick != tick:
            errors.append(f"{clip}: accent_seconds={accent_s} -> tick {expect_tick}, "
                          f"but the contract table says tick {tick} (fix the table)")
        expect_period = round(d["length"] * 20)
        if expect_period != period:
            errors.append(f"{clip}: length={d['length']}s -> period {expect_period}, "
                          f"but the contract table says period {period} (fix the table)")
        if (period, tick) not in goal_contracts:
            errors.append(f"{clip}: no AI goal source has 'x % {period} == {tick}' -- "
                          f"the sound-sync contract (comment/goal/checker) is broken")
        sound_key = sound_field.lower()
        if sound_key not in sounds_data:
            errors.append(f"{clip}: sound '{sound_key}' (ModSounds.{sound_field}) has no "
                          f"entry in sounds.json")

        # 17.3-16: impact-frame neighbours (warning).
        idx = chan[2].index(hit) if hit else -1
        if idx > 0:
            prev = chan[2][idx - 1]
            if prev[3] != "LINEAR" and (hit[0] - prev[0]) > 0.10:
                warns.append(f"{clip}@{accent_s}: the preceding keyframe at {prev[0]}s is "
                             f"CATMULLROM and more than 0.10s before the impact -- risk of "
                             f"pre-swinging through the contact point")

    # 17.4-24: head-damping table cross-check (grep-based, structural).
    model_path = os.path.join(ROOT,
        "src/main/java/com/hearthstead/client/model/SettlerModel.java")
    for clip, damp in DAMPING_TABLE.items():
        needle = f"{damp}F" if damp != int(damp) or damp == 0.0 else f"{damp}F"
        # Search for the literal damp value in SettlerModel.java; this is a
        # structural smoke check, not a semantic one -- it would miss a
        # value assigned to the wrong condition, but it catches the more
        # common failure (a clip added here with no damping wired at all).
        if not check_pipeline_present_in_model(model_path, needle):
            errors.append(f"damping table: {clip} needs damp={damp} in "
                          f"SettlerModel.setupAnim's damping table, literal not found")

    # 17.4-25: per-entity variation call sites (grep-based, warning).
    for clip in PER_ENTITY_VARIATION_CLIPS:
        needle = f"SettlerAnimations.{clip}"
        text = open(model_path, encoding="utf-8").read() if os.path.isfile(model_path) else ""
        if needle in text:
            around = text[text.index(needle):text.index(needle) + 200]
            if "% " not in around and "id %" not in text:
                warns.append(f"{clip}: no visible per-entity phase-offset/jitter call site "
                             f"near its animate() call -- crowds may move in unison")
        else:
            warns.append(f"{clip}: not wired in SettlerModel.setupAnim -- cannot check "
                         f"per-entity variation")

    total = sum(len(d["channels"]) for d in defs.values())
    print(f"parsed {len(defs)} definitions, {total} channels")
    for w in warns:
        print("  ⚠", w)
    if errors:
        for e in errors:
            print("  ✗", e)
        print(f"anim check FAIL: {len(errors)} error(s), {len(warns)} warning(s)")
        sys.exit(1)
    print(f"anim check PASS ({len(warns)} warning(s))")


if __name__ == "__main__":
    main()
