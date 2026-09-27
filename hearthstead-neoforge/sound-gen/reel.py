"""Before/after listening reel: old sound(s), short gap, new sound(s), grouped by category.

    python reel.py --cat combat --cat ui ...   (default: every applied target)

Writes videos/audio/elevenlabs-preview.mp3 plus a .txt cue sheet with timestamps.
Each old/new pair keeps its in-game relative level (file x sounds.json volume); the
pair as a whole is lifted so the louder side sits near -16 LUFS for listening.
"""
import argparse, json, os, sys, subprocess, math
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import audiolib as A  # noqa: E402
from targets import TARGETS  # noqa: E402
import apply as P  # noqa: E402
from labels import label  # noqa: E402

OUT = r"C:\Users\tobia\Hearthstead-Claude\videos\audio\elevenlabs-preview.mp3"
CAT_ORDER = ["combat", "raid", "ui", "builder", "tavern", "work", "voice", "world", "ambience"]


def entries_audio(entries, limit):
    out = []
    for e in entries:
        if isinstance(e, str):
            e = {"name": e}
        if e.get("type") == "event":
            continue
        p = P.file_for(e["name"])
        if not p or any(p == q for q, _ in out):
            continue
        out.append((p, e.get("volume", 1.0)))
        if len(out) >= limit:
            break
    return [(A.decode(p), v) for p, v in out]


def seq(clips, gap):
    parts = []
    for x, v in clips:
        parts += [x * v, np.zeros(int(gap * A.SR))]
    return np.concatenate(parts) if parts else np.zeros(0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cat", action="append")
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--pri", type=int, default=9)
    ap.add_argument("--minpri", type=int, default=1)
    a = ap.parse_args()
    with open(os.path.join(P.WORK, "before", "sounds.json"), encoding="utf-8") as fh:
        before = json.load(fh)
    now, _ = P.load_json(P.SOUNDS_JSON)
    with open(os.path.join(P.WORK, "picks.json"), encoding="utf-8") as fh:
        picks = json.load(fh)
    cats = a.cat or CAT_ORDER
    timeline, cues, t_now = [], [], 0.0
    for cat in [c for c in CAT_ORDER if c in cats]:
        ts = [t for t in TARGETS if t["cat"] == cat and a.minpri <= t["pri"] <= a.pri and t["event"] in now
              and any("/el/" in (s["name"] if isinstance(s, dict) else s).replace(":", "/")
                      for s in now[t["event"]]["sounds"])]
        ts.sort(key=lambda t: t["pri"])
        if not ts:
            continue
        cues.append(f"\n== {cat.upper()} ==")
        head = np.concatenate([label(f"{cat}."), np.zeros(int(0.6 * A.SR))])
        timeline.append(head); t_now += len(head) / A.SR
        for t in ts:
            ev = t["event"]
            if ev in before:
                old = entries_audio(before[ev]["sounds"], 2)
            elif P.REF.get(ev):
                ns, path = P.REF[ev].split(":", 1)
                src = P.vanilla_event_entries(path) if ns == "minecraft" else before.get(path, {}).get("sounds", [])
                old = entries_audio(src, 2)
            else:
                old = []
            new = entries_audio(now[ev]["sounds"], 3 if t["dur"] < 2.5 else 1)
            gap = 0.35 if t["dur"] < 1.5 else 0.6
            o, n = seq(old, gap), seq(new, gap)
            loud = max([A.lufs(x) for x in (o, n) if len(x)] or [-99])
            lift = 10 ** ((-16 - loud) / 20) if loud > -90 else 1.0
            lift = min(lift, 10 ** (30 / 20))
            block = []
            if len(o):
                block += [o * lift, np.zeros(int(0.5 * A.SR))]
            block += [n * lift, np.zeros(int(1.1 * A.SR))]
            x = np.concatenate(block)
            mm, ss = divmod(t_now, 60)
            cues.append(f"{int(mm):02d}:{ss:05.2f}  {ev:30s} {'old -> new' if len(o) else 'new only'}"
                        f"  ({t['cur'] or 'none'})")
            timeline.append(x)
            t_now += len(x) / A.SR
    if not timeline:
        raise SystemExit("nothing applied yet")
    y = A.soft_limit(np.concatenate(timeline), -1.0)
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    pcm = y.astype(np.float32).tobytes()
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(A.SR), "-ac", "1", "-i", "-",
                    "-c:a", "libmp3lame", "-b:a", "160k", a.out], input=pcm, check=True)
    with open(os.path.splitext(a.out)[0] + ".txt", "w", encoding="utf-8") as fh:
        fh.write("Bannerhold ElevenLabs preview: for each sound, the OLD version plays first (if any), "
                 "then the NEW variants.\n" + "\n".join(cues) + "\n")
    print(f"wrote {a.out} ({t_now/60:.1f} min, {len(cues)} cues)")


if __name__ == "__main__":
    main()
