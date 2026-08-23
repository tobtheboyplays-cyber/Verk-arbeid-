#!/usr/bin/env python3
"""Static QA for SettlerAnimations.java: parses every keyframe channel and
checks loop closure, amplitude sanity, timestamp ordering, and the
chop-strike/sound sync contract."""
import re
import sys
import os

SRC = os.path.join(os.path.dirname(__file__), "..",
                   "src/main/java/com/hearthstead/client/model/SettlerAnimations.java")

def parse(path):
    text = open(path).read()
    defs = {}
    # Split into definitions.
    for m in re.finditer(
            r'AnimationDefinition (\w+) = AnimationDefinition\.Builder\s*'
            r'\.withLength\(([\d.]+)F\)(\.looping\(\))?(.*?)\.build\(\);',
            text, re.S):
        name, length, looping, body = m.group(1), float(m.group(2)), bool(m.group(3)), m.group(4)
        channels = []
        for cm in re.finditer(
                r'\.addAnimation\("(\w+)",\s*new AnimationChannel\((\w+),(.*?)\)\)\s*(?=\.addAnimation|\Z)',
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


def main():
    defs = parse(SRC)
    errors = []
    warns = []
    assert defs, "no definitions parsed"
    for name, d in defs.items():
        assert d["channels"], f"{name}: no channels parsed"
        for bone, target, frames in d["channels"]:
            label = f"{name}.{bone}.{target}"
            if not frames:
                errors.append(f"{label}: no keyframes parsed")
                continue
            times = [f[0] for f in frames]
            if times != sorted(times):
                errors.append(f"{label}: timestamps not ascending: {times}")
            if times[-1] > d["length"] + 1e-6:
                errors.append(f"{label}: last key {times[-1]} exceeds length {d['length']}")
            if d["looping"]:
                first, last = frames[0][2], frames[-1][2]
                if any(abs(a - b) > 0.01 for a, b in zip(first, last)):
                    errors.append(f"{label}: loop does not close: {first} -> {last}")
                if abs(times[-1] - d["length"]) > 1e-6:
                    warns.append(f"{label}: looping channel ends at {times[-1]}, "
                                 f"not at length {d['length']} (holds last pose)")
            for t, kind, vec, interp in frames:
                if kind == "degreeVec" and any(abs(v) > 180 for v in vec):
                    errors.append(f"{label}@{t}: rotation beyond 180deg: {vec}")
                if kind == "posVec" and any(abs(v) > 12 for v in vec):
                    errors.append(f"{label}@{t}: position offset beyond 12px: {vec}")
                if kind == "scaleVec" and any(v < 0.5 or v > 1.5 for v in vec):
                    errors.append(f"{label}@{t}: extreme scale: {vec}")

    # Contract: CHOP strike keyframe at 0.55s (sound plays tick 11 of 20).
    chop = defs.get("CHOP")
    ok_strike = False
    for bone, target, frames in chop["channels"]:
        if bone == "right_arm" and target == "ROTATION":
            ok_strike = any(abs(t - 0.55) < 1e-6 and interp == "LINEAR"
                            for t, _, _, interp in frames)
    if not ok_strike:
        errors.append("CHOP: right_arm strike key at 0.55s with LINEAR interp missing "
                      "(sound sync contract)")

    # Contract: REST sinks the root down (posVec y negative = down).
    rest = defs.get("REST")
    sunk = any(bone == "root" and target == "POSITION"
               and all(f[2][1] < 0 for f in frames)
               for bone, target, frames in rest["channels"])
    if not sunk:
        errors.append("REST: root should sink (negative y posVec)")

    total = sum(len(d["channels"]) for d in defs.values())
    print(f"parsed {len(defs)} definitions, {total} channels")
    for w in warns:
        print("  ⚠", w)
    if errors:
        for e in errors:
            print("  ✗", e)
        sys.exit(1)
    print("anim check PASS")


if __name__ == "__main__":
    main()
