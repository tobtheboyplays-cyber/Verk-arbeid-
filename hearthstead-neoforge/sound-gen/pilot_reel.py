"""Pilot A/B reel: for each pilot sound, closest VANILLA -> CURRENT mod -> NEW (ElevenLabs picks).

Private listening file only (vanilla clips are used for comparison, never shipped).
All clips are levelled to the same padded loudness so the ear judges character, not volume.
"""
import os, sys, json, subprocess
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import audiolib as A  # noqa: E402
import apply as P  # noqa: E402
from labels import label  # noqa: E402

OUT = r"C:\Users\tobia\Hearthstead-Claude\videos\audio\pilot-ab.mp3"
W = P.WORK
V = lambda n: P.vanilla_file(n)
B = lambda n: os.path.join(W, "before", "sounds", n + ".ogg")
N = lambda ev, c: os.path.join(W, "proc", ev, c + ".ogg")

picks = json.load(open(os.path.join(W, "picks.json"), encoding="utf-8"))


def new(ev, k=3):
    return [N(ev, c) for c in picks[ev]["files"][:k]]


def combo(a, b, offset):
    """Whoosh then hit, the way a guard slash plays in game."""
    x, y = A.decode(a), A.decode(b)
    n = int(offset * A.SR)
    out = np.zeros(max(len(x), n + len(y)))
    out[:len(x)] += x; out[n:n + len(y)] += y
    return out


ITEMS = [
    ("Anvil hit", [V("random/anvil_use")], [B("work/anvil_ring1"), B("work/anvil_ring2")], new("anvil_ring", 4)),
    ("Wood chop", [V("dig/wood1"), V("dig/wood2")], [B("work/chop1"), B("work/chop2")], new("chop", 4)),
    ("Mug clink", [V("block/decorated_pot/place1")], [B("ambient/mug_set1")], new("tavern.clink", 3)),
    ("Level up chime", [V("random/levelup")], [V("random/levelup")], new("fx.skill_level_up", 2)),
    ("Interface click", [V("random/click")], [V("block/chiseled_bookshelf/insert1")], new("ui.click", 3)),
    ("Guard slash", [("combo", V("entity/player/attack/sweep1"), V("entity/player/attack/strong1"))],
     [("combo", B("combat/swing_light1"), B("combat/blade_hit1"))],
     [("combo", N("combat.swing_light", a), N("blade_hit", b))
      for a, b in zip(picks["combat.swing_light"]["files"], picks["blade_hit"]["files"][:4])]),
]


def clip(src):
    if isinstance(src, tuple):
        x = combo(src[1], src[2], 0.12)
    else:
        x = A.decode(src)
    return A.normalize(x, -18)


def sil(s):
    return np.zeros(int(s * A.SR))


def main():
    parts, cues, t = [], [], 0.0
    words = {k: label(k) for k in ("Vanilla.", "Current.", "New.")}
    for i, (name, van, cur, nw) in enumerate(ITEMS, 1):
        seg = [label(f"{i}. {name}."), sil(0.5)]
        for word, group in (("Vanilla.", van), ("Current.", cur), ("New.", nw)):
            seg += [words[word], sil(0.35)]
            for src in group:
                if src and (isinstance(src, tuple) or os.path.exists(src)):
                    seg += [clip(src), sil(0.45)]
            seg.append(sil(0.4))
        seg.append(sil(0.8))
        x = np.concatenate(seg)
        cues.append(f"{int(t // 60):02d}:{t % 60:05.2f}  {i}. {name}: vanilla {len(van)}, current {len(cur)}, new {len(nw)}")
        parts.append(x); t += len(x) / A.SR
    y = A.soft_limit(np.concatenate(parts), -1.0)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(A.SR), "-ac", "1", "-i", "-",
                    "-c:a", "libmp3lame", "-b:a", "192k", OUT], input=y.astype(np.float32).tobytes(), check=True)
    with open(OUT[:-4] + ".txt", "w", encoding="utf-8") as fh:
        fh.write("Bannerhold sound pilot A/B. Each sound: VANILLA, then CURRENT mod, then NEW ElevenLabs variants.\n"
                 "All clips levelled to the same loudness (in game, the new files keep each event's current mix level).\n\n"
                 + "\n".join(cues) + "\n")
    print(f"wrote {OUT} ({t:.0f} s)")


if __name__ == "__main__":
    main()
