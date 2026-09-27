"""Emits the Java twins (CraftMotionAnimations) of the fisher v3 clips from their
authored bedrock JSON: ~every 0.1 s plus the clip's beats, CATMULLROM. Engine-on
play uses the JSON; the twin is the engine-off / fallback path.
    python tools/gen_fisher_v3_twins.py > fisher_twins.java.txt"""
import json, os
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
DIR = os.path.join(ROOT, "src/main/resources/assets/hearthstead/animations/settler")
CLIPS = ["fisher_cast_v3", "fisher_wait", "fisher_strike_reel", "fisher_land_fish"]
BONES = ["root", "torso", "head", "right_arm", "right_forearm", "right_item", "left_arm", "left_forearm", "cloak"]
out = []
for stem in CLIPS:
    d = json.load(open(os.path.join(DIR, stem + ".animation.json")))
    name, a = next(iter(d["animations"].items()))
    L = float(a["animation_length"]); loop = bool(a.get("loop"))
    step = 0.1 if L <= 2.5 else 0.2
    grid = [round(i * step, 4) for i in range(int(L / step + 1e-6) + 1)]
    if grid[-1] < L - 1e-6: grid.append(L)
    lines = [f"    public static final AnimationDefinition {stem.upper()} = AnimationDefinition.Builder",
             f"        .withLength({L:.2f}F){'.looping()' if loop else ''}"]
    for b in BONES:
        rot = a["bones"].get(b, {}).get("rotation")
        if not rot or not isinstance(rot, dict): continue
        keys = sorted((float(t), v["post"] if isinstance(v, dict) else v) for t, v in rot.items())
        picked = []
        for g in grid:
            t, v = min(keys, key=lambda kv: abs(kv[0] - g))
            if not picked or abs(picked[-1][0] - t) > 1e-4: picked.append((t, v))
        if all(max(abs(x) for x in v) < 0.05 for _, v in picked): continue
        ks = ", ".join(f"r({t:.3f}F, {v[0]:.1f}F, {v[1]:.1f}F, {v[2]:.1f}F, CATMULLROM)" for t, v in picked)
        # wrap long argument lists
        parts, cur = [], ""
        for k in ks.split(", r("):
            k = k if k.startswith("r(") else "r(" + k
            if len(cur) + len(k) > 100: parts.append(cur); cur = ""
            cur += (", " if cur else "") + k
        parts.append(cur)
        body = (",\n            ").join(parts)
        lines.append(f'        .addAnimation("{b}", new AnimationChannel(ROTATION,\n            {body}))')
    lines.append("        .build();\n")
    out.append("\n".join(lines))
print("\n".join(out))
