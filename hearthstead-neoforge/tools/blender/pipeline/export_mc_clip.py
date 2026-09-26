"""Dense channel samples -> Bedrock/GeckoLib animation JSON the motion engine plays.

Pure Python (no bpy), so it can be unit-checked outside Blender.

Keyframe reduction is judged against the RUNTIME's interpolation, not
Blender's: vanilla KeyframeAnimations / the motion engine use uniform
Catmull-Rom over key INDICES (Mth.catmullrom), neighbours clamped at the array
ends, no wrap on loops. A key is only dropped if every dense sample in the
affected span still reconstructs within tolerance, so the Blender easing
(acceleration into impact, the stop, the settle) survives export.
All keys are emitted as catmullrom so the result is identical whether a
segment takes its mode from its end key (vanilla) or from either end.
"""

from __future__ import annotations

import json
import math
import os


def catmullrom(a, p0, p1, p2, p3):
    # net.minecraft.util.Mth.catmullrom
    return 0.5 * (2.0 * p1 + (p2 - p0) * a
                  + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * a * a
                  + (3.0 * p1 - p0 - 3.0 * p2 + p3) * a * a * a)


def evaluate(keys, t):
    """keys: list of (time, value) sorted; vanilla KeyframeAnimations.animate semantics."""
    n = len(keys)
    # i = last index with time < t  (binarySearch(f <= ts) - 1, floored at 0)
    i = 0
    for k in range(n):
        if t <= keys[k][0]:
            i = max(0, k - 1)
            break
    else:
        i = max(0, n - 1)
    j = min(n - 1, i + 1)
    if j == i:
        return keys[i][1]
    a = (t - keys[i][0]) / (keys[j][0] - keys[i][0])
    a = max(0.0, min(1.0, a))
    p0 = keys[max(0, i - 1)][1]
    p3 = keys[min(n - 1, j + 1)][1]
    return catmullrom(a, p0, keys[i][1], keys[j][1], p3)


def reduce_channel(times, values, tol, keep=()):
    """Greedy key removal on one scalar curve. Returns kept indices."""
    idx = list(range(len(times)))
    keep = set(keep) | {0, len(times) - 1}

    def err(candidate):
        keys = [(times[i], values[i]) for i in candidate]
        return max(abs(evaluate(keys, times[s]) - values[s]) for s in range(len(times)))

    changed = True
    while changed and len(idx) > 2:
        changed = False
        best = None
        for pos in range(1, len(idx) - 1):
            if idx[pos] in keep:
                continue
            trial = idx[:pos] + idx[pos + 1:]
            e = err(trial)
            if e <= tol and (best is None or e < best[0]):
                best = (e, pos)
        if best is not None:
            idx.pop(best[1])
            changed = True
    return idx


def _eval_idx(times, vals, idx, t):
    """evaluate() on the key subset idx (indices into times/vals) with bisect lookup."""
    import bisect
    n = len(idx)
    kt = [times[i] for i in idx]
    k = bisect.bisect_left(kt, t)          # first key with time >= t
    i = max(0, k - 1)
    j = min(n - 1, i + 1)
    if j == i:
        return vals[idx[i]]
    a = max(0.0, min(1.0, (t - kt[i]) / (kt[j] - kt[i])))
    return catmullrom(a, vals[idx[max(0, i - 1)]], vals[idx[i]], vals[idx[j]], vals[idx[min(n - 1, j + 1)]])


def reduce_vector(times, vecs, tol, keep=()):
    """Reduce a 3-vector channel with one shared key set (keeps JSON simple).

    Removing a key only changes the Catmull-Rom curve between the keys two
    places either side of it, so each candidate is scored on that window only
    (fast even for multi-second clips at 60 Hz)."""
    idx = list(range(len(times)))
    keep = set(keep) | {0, len(times) - 1}
    comps = [[v[c] for v in vecs] for c in range(3)]

    def local_err(trial, pos):
        lo = trial[max(0, pos - 2)]
        hi = trial[min(len(trial) - 1, pos + 1)]
        worst = 0.0
        for c in range(3):
            vals = comps[c]
            for s_ in range(lo, hi + 1):
                worst = max(worst, abs(_eval_idx(times, vals, trial, times[s_]) - vals[s_]))
        return worst

    while len(idx) > 2:
        best = None
        for pos in range(1, len(idx) - 1):
            if idx[pos] in keep:
                continue
            trial = idx[:pos] + idx[pos + 1:]
            e = local_err(trial, pos)
            if e <= tol and (best is None or e < best[0]):
                best = (e, pos)
        if best is None:
            break
        idx.pop(best[1])

    def full_err(candidate):
        return max(abs(_eval_idx(times, comps[c], candidate, times[s_]) - comps[c][s_])
                   for c in range(3) for s_ in range(len(times)))
    return idx, full_err(idx)


def is_constant_zero(vecs, eps):
    return all(abs(v) < eps for vec in vecs for v in vec)


def build_bedrock(name, length, loop, times, channels, rot_tol=0.25, pos_tol=0.02,
                  keep_times=(), meta=None):
    """channels: {bone: {'rotation': [vec...], 'position': [vec...]}} sampled at `times`."""
    keep = [min(range(len(times)), key=lambda i: abs(times[i] - kt)) for kt in keep_times]
    bones = {}
    report = {}
    for bone, chans in channels.items():
        out = {}
        for kind, vecs in chans.items():
            tol = rot_tol if kind == "rotation" else pos_tol
            if is_constant_zero(vecs, tol * 0.5):
                continue
            idx, e = reduce_vector(times, vecs, tol, keep)
            out[kind] = {
                f"{times[i]:.4f}".rstrip("0").rstrip(".") if times[i] else "0.0": {
                    "post": [round(v, 3) for v in vecs[i]], "lerp_mode": "catmullrom"}
                for i in idx
            }
            report[f"{bone}.{kind}"] = {"keys": len(idx), "max_err": round(e, 4)}
        if out:
            bones[bone] = out
    doc = {
        "format_version": "1.8.0",
        "animations": {
            name: {"loop": bool(loop), "animation_length": length, "bones": bones}
        },
    }
    if meta:
        doc["hearthstead_meta"] = meta
    return doc, report


# QA 2026-09-26: settler clips written into the mod get the head-overlap pass
# (overlap_pass.py: the head trails the chest by 0.07 s). Combat/martial clips are
# owned by the combat animator and the owner's reference idle stays as authored.
OVERLAP_SKIP_PREFIX = ("guard_", "melee", "shield_block", "archer_", "hunter_loose", "run_panic",
                       "walk_hurried", "leap_")
OVERLAP_SKIP = {"idle_lumberer__v2", "bag_to_chest_unload"}
OVERLAP_GROUPS = ("clips/farm", "clips/craft", "clips/logistics", "clips/life", "clips/idles",
                  "author_lumberer_chop", "author_bag_down", "author_pickup_to_bag")


def _overlap(doc, path):
    norm = path.replace(chr(92), "/")
    if "/assets/hearthstead/animations/settler/" not in norm:
        return
    stem = os.path.basename(norm).replace(".animation.json", "")
    if stem in OVERLAP_SKIP or stem.startswith(OVERLAP_SKIP_PREFIX):
        return
    src = str(doc.get("hearthstead_meta", {}).get("source", ""))
    if not any(g in src for g in OVERLAP_GROUPS):      # other groups' clips stay untouched
        return
    import overlap_pass
    overlap_pass.apply(doc)


def write(doc, path):
    _overlap(doc, path)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(doc, fh, indent=1)
        fh.write("\n")


def load_keys(doc, name, bone, kind):
    """Read back one channel from a written doc -> [(t, [x,y,z])]."""
    ch = doc["animations"][name]["bones"][bone][kind]
    out = []
    for t, v in ch.items():
        vec = v["post"] if isinstance(v, dict) else v
        out.append((float(t), vec))
    out.sort()
    return out


def sample(doc, name, bone, kind, t):
    keys = load_keys(doc, name, bone, kind)
    return [evaluate([(k[0], k[1][c]) for k in keys], t) for c in range(3)]
