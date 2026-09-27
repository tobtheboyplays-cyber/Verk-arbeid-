"""Preview v3: villager-sound voices (4 people) and a brute's dark grunts, at the in-game level."""
import os, random, subprocess, sys, json
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import audiolib as A  # noqa: E402
import apply as P  # noqa: E402
from labels import label  # noqa: E402
from babble_reel import pitch_shift  # noqa: E402

OUT = r"C:\Users\tobia\Hearthstead-Claude\videos\audio\babble-preview-v3.mp3"
snd, _ = P.load_json(P.SOUNDS_JSON)
PITCH = {"m_adult": (0.88, 0.96), "f_young": (1.22, 1.32), "m_old": (0.78, 0.86), "goblin": (1.36, 1.48),
         "brute": (0.92, 1.00)}
PEOPLE = [("A grown settler, a man", "m_adult", "yes"), ("A young woman", "f_young", "celebrate"),
          ("An old man", "m_old", "trade"), ("The goblin thief", "goblin", "no"),
          ("The toll brute", "brute", None)]


def files(ev):
    out = []
    for e in snd[ev]["sounds"]:
        p = P.file_for(e["name"])
        out.append(A.decode(p) * e.get("volume", 1.0))
    return out


def line(rnd, pool, base, n):
    t, ev = 0.0, []
    for _ in range(n):
        ev.append((t, pitch_shift(rnd.choice(pool), base * rnd.uniform(0.97, 1.03)) * rnd.uniform(0.75, 1.0)))
        t += rnd.uniform(0.18, 0.26) + (0.1 if rnd.random() < 0.25 else 0)
    y = np.zeros(int((t + 0.8) * A.SR))
    for t0, s in ev:
        i = int(t0 * A.SR); y[i:i + len(s)] += s[:len(y) - i]
    return np.concatenate([y, np.zeros(int(1.3 * A.SR))])       # text keeps typing in silence


def main():
    rnd = random.Random(9)
    seq, cues, t = [], [], 0.0
    for who, bank, tone in PEOPLE:
        lo, hi = PITCH[bank]
        base = rnd.uniform(lo, hi)
        parts = []
        if bank == "brute":
            g = files("voice.brute.grunt")
            for _ in range(2):
                parts += [rnd.choice(g), np.zeros(int(rnd.choice([0.9, 1.6]) * A.SR)),
                          rnd.choice(g) * 0.8 if rnd.random() < 0.6 else np.zeros(1), np.zeros(int(1.4 * A.SR))]
        else:
            pool = files("voice.npc.ambient")
            parts += [line(rnd, pool, base, rnd.randint(3, 5)), line(rnd, pool, base, rnd.randint(4, 6))]
            parts += [pitch_shift(rnd.choice(files(f"voice.npc.{tone}")), base), np.zeros(int(0.6 * A.SR))]
        x = A.normalize(np.concatenate(parts), -27.0)
        seg = np.concatenate([label(who + "."), np.zeros(int(0.35 * A.SR)), x, np.zeros(int(0.4 * A.SR))])
        cues.append(f"{int(t // 60):02d}:{t % 60:05.2f}  {who}  (voice {bank}, pitch {base:.2f})")
        seq.append(seg); t += len(seg) / A.SR
    y = A.soft_limit(np.concatenate(seq), -1.0)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(A.SR), "-ac", "1", "-i", "-",
                    "-c:a", "libmp3lame", "-b:a", "160k", OUT], input=y.astype(np.float32).tobytes(), check=True)
    with open(OUT[:-4] + ".txt", "w", encoding="utf-8") as fh:
        fh.write("Bannerhold voices v3: villager sounds, re-pitched per character, only at the start of each line; "
                 "brutes only grunt. Played at the in-game level, under the -20 LUFS narrator.\n\n" + "\n".join(cues) + "\n")
    print(f"wrote {OUT} ({t:.0f} s)")


if __name__ == "__main__":
    main()
