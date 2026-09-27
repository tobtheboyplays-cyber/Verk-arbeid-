"""Overlap / follow-through pass for exported settler clips (QA 2026-09-26).

Owner brief: "nothing moves all at once -- hips -> torso -> shoulders -> arms -> head".
Most clips already stagger hips, spine and arms in their keys, but the head is solved
per frame (aim / nod) and so turns in lock-step with the chest. This pass lets the head
TRAIL the body: its channels get a share of the torso's (and pelvis yaw's) motion from
`lag` seconds earlier minus the motion now, so when the chest pitches or twists the
head holds back for a beat and then catches up (and overshoots a touch on a stop).

Hands, tools and contacts are untouched: the head is a leaf bone. Holds are unchanged
(the lag term is zero whenever the body is still), loops stay seamless (time wraps),
one-shots keep their first and last frame exactly (the term fades in/out over 0.1 s).

Idempotent: writes hearthstead_meta.overlap_pass and skips a doc that already has it.

    python overlap_pass.py <clip stem> ...          (settler clips, in place)
    import overlap_pass; overlap_pass.apply(doc)    (from an exporter, before write)
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import export_mc_clip as ex  # noqa: E402

ANIM = os.path.abspath(os.path.join(HERE, "..", "..", "..", "src", "main", "resources", "assets",
                                    "hearthstead", "animations", "settler"))
LAG = 0.07          # seconds the head trails the chest
GAIN_PITCH = 0.55   # share of the torso pitch change the head holds back
GAIN_YAW = 0.45     # share of the chest + pelvis twist change the head holds back
FPS = 60


def _keys(bones, bone, kind):
    ch = bones.get(bone, {}).get(kind)
    if ch is None:
        return None
    out = []
    for t, v in ch.items():
        vec = v["post"] if isinstance(v, dict) else v
        out.append((float(t), [float(x) for x in vec]))
    out.sort()
    return out


def _eval(keys, t):
    if keys is None:
        return [0.0, 0.0, 0.0]
    return [ex.evaluate([(k[0], k[1][c]) for k in keys], t) for c in range(3)]


def apply(doc, lag=LAG, gain_pitch=GAIN_PITCH, gain_yaw=GAIN_YAW):
    meta = doc.setdefault("hearthstead_meta", {})
    if "overlap_pass" in meta:
        return False
    (name, anim), = list(doc["animations"].items())[:1]
    bones = anim.get("bones", {})
    torso = _keys(bones, "torso", "rotation")
    root = _keys(bones, "root", "rotation")
    head = _keys(bones, "head", "rotation")
    if torso is None and root is None:
        return False
    L = float(anim.get("animation_length", 0.0))
    loop = bool(anim.get("loop", False))
    n = int(round(L * FPS))
    times = [i / FPS for i in range(n + 1)]

    def wrap(u):
        if loop:
            return u % L
        return min(max(u, 0.0), L)

    def body(u):
        tv, rv = _eval(torso, wrap(u)), _eval(root, wrap(u))
        return tv[0], tv[1] + rv[1]

    vecs = []
    worst = 0.0
    for t in times:
        h = _eval(head, t)
        p_now, y_now = body(t)
        p_then, y_then = body(t - lag)
        env = 1.0
        if not loop:   # first/last frame exact (hand-offs to neighbouring clips)
            env = min(1.0, t / 0.1, (L - t) / 0.1) if L > 0.2 else 0.0
            env = max(0.0, env)
            env = env * env * (3 - 2 * env)
        dx = gain_pitch * (p_then - p_now) * env
        dy = gain_yaw * (y_then - y_now) * env
        worst = max(worst, abs(dx), abs(dy))
        vecs.append([h[0] + dx, h[1] + dy, h[2]])
    if worst < 0.3:            # body barely moves: nothing to add
        meta["overlap_pass"] = {"lag_s": lag, "applied": False}
        return False
    keep = [t for t, _ in (head or [])] + [0.0, L]
    idx, err = ex.reduce_vector(times, vecs, 0.25, keep=[min(range(len(times)), key=lambda i: abs(times[i] - k))
                                                         for k in keep])
    bones.setdefault("head", {})["rotation"] = {
        (f"{times[i]:.4f}".rstrip("0").rstrip(".") if times[i] else "0.0"): {
            "post": [round(v, 3) for v in vecs[i]], "lerp_mode": "catmullrom"} for i in idx}
    meta["overlap_pass"] = {"lag_s": lag, "gain_pitch": gain_pitch, "gain_yaw": gain_yaw,
                            "applied": True, "max_head_offset_deg": round(worst, 2),
                            "head_keys": len(idx), "fit_err_deg": round(err, 3)}
    return True


def apply_file(path):
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    changed = apply(doc)
    if changed:
        ex.write(doc, path)
    return changed, doc.get("hearthstead_meta", {}).get("overlap_pass")


if __name__ == "__main__":
    for stem in sys.argv[1:]:
        p = os.path.join(ANIM, stem + ".animation.json")
        print(stem, *apply_file(p))
