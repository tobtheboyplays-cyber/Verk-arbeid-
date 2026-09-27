"""Babble preview reel: every voice bank says two lines and one emote, the way the game plays it
(a syllable every 90-160 ms, +-6 % jitter around a per-character base pitch inside the bank's
range, a pause on punctuation). Spoken label = who the voice belongs to."""
import os, random, subprocess, sys
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import audiolib as A  # noqa: E402
import babble as B  # noqa: E402
from labels import label  # noqa: E402

OUT = r"C:\Users\tobia\Hearthstead-Claude\videos\audio\babble-preview-v2.mp3"
WHO = {"m_young": "A young farmhand", "m_adult": "A grown settler, a man", "m_old": "An old man",
       "f_young": "A young woman", "f_adult": "A grown settler, a woman", "f_old": "An old woman",
       "brute": "The toll brute", "goblin": "The goblin thief", "captain": "An Ashen raid captain",
       "peddler": "The peddler", "envoy": "The rival lord's envoy", "minstrel": "The wandering minstrel"}
LINES = [[3, ",", 3], [2, "!", 3]]    # v2: 4-8 syllables per line, only the first ~1.5 s babbles
EMOTE = {"m_young": "laugh", "m_adult": "mmhm", "m_old": "hmm", "f_young": "oh", "f_adult": "laugh",
         "f_old": "bye", "brute": "angry", "goblin": "laugh", "captain": "angry", "peddler": "laugh",
         "envoy": "hmm", "minstrel": "bye"}


def pitch_shift(x, factor):
    """Play-rate pitch change, exactly what Minecraft does with a sound's pitch."""
    from fractions import Fraction
    f = Fraction(factor).limit_denominator(300)
    return A.signal.resample_poly(x, f.denominator, f.numerator)


def speak(bank, rnd):
    d = os.path.join(B.DIR, bank, "out")
    syl = [A.decode(os.path.join(d, f)) for f in sorted(os.listdir(d)) if f.startswith("babble")]
    lo, hi = B.BANKS[bank]["pitch"]
    base = rnd.uniform(lo + 0.01, hi - 0.01)
    parts = []
    for line in LINES:
        t = 0.0
        events = []
        for item in line:
            if isinstance(item, int):
                for _ in range(item):
                    events.append((t, pitch_shift(rnd.choice(syl), base * rnd.uniform(0.97, 1.03)) * rnd.uniform(0.8, 1.0)))
                    t += rnd.uniform(0.14, 0.22) + (0.12 if rnd.random() < 0.2 else 0.0)
            else:
                t += 0.28 if item == "," else 0.45
        n = int((t + 0.5) * A.SR)
        y = np.zeros(n)
        for t0, s in events:
            i = int(t0 * A.SR); y[i:i + len(s)] += s[:n - i]
        parts += [y, np.zeros(int(1.2 * A.SR))]         # the text keeps typing in silence
    emo = os.path.join(d, EMOTE[bank] + ".ogg")
    if os.path.exists(emo):
        parts += [pitch_shift(A.decode(emo), base), np.zeros(int(0.5 * A.SR))]
    # kept at the in-game file level (-27.5 LUFS) so it sits clearly under the -20 LUFS narrator
    return A.normalize(np.concatenate(parts), -27.5)


def main():
    rnd = random.Random(5)
    seq, cues, t = [], [], 0.0
    for bank in ["m_adult", "f_young", "m_old", "brute", "goblin", "peddler"]:
        seg = [label(WHO[bank] + "."), np.zeros(int(0.35 * A.SR)), speak(bank, rnd), np.zeros(int(0.5 * A.SR))]
        x = np.concatenate(seg)
        cues.append(f"{int(t // 60):02d}:{t % 60:05.2f}  {WHO[bank]}  (bank {bank}, emote {EMOTE[bank]})")
        seq.append(x); t += len(x) / A.SR
    y = A.soft_limit(np.concatenate(seq), -1.0)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(A.SR), "-ac", "1", "-i", "-",
                    "-c:a", "libmp3lame", "-b:a", "160k", OUT], input=y.astype(np.float32).tobytes(), check=True)
    with open(OUT[:-4] + ".txt", "w", encoding="utf-8") as fh:
        fh.write("Bannerhold babble voices v2 (softer): each character says two gibberish lines as the game plays them, "
                 "then one emote.\n\n" + "\n".join(cues) + "\n")
    print(f"wrote {OUT} ({t:.0f} s)")


if __name__ == "__main__":
    main()
