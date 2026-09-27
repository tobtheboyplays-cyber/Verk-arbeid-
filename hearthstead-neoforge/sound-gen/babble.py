"""Simlish-style gibberish voice banks (owner request 26 Sep).

Method (chosen after babble_test.py): ElevenLabs Voice Design previews. One design call per bank
describes the character (gender, age, body, role) and makes it speak a generated gibberish script
(no real words), followed by six emotes separated by long pauses. The call returns 3 previews =
3 candidate voices. We pick the preview whose pitch and cleanliness fit the bank, slice its
gibberish into 12-20 syllables (0.08-0.25 s) at energy dips, and cut the six emotes from the tail.
Voices are never saved to the owner's voice library.

    python babble.py --bank m_adult          # design + slice one bank
    python babble.py --all
    python babble.py --slice-only --all      # re-slice from cached previews (no credits)
"""
import argparse, base64, datetime, json, os, random, re, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import audiolib as A  # noqa: E402

WORK = r"C:\Users\tobia\Hearthstead-Claude\sound-gen-work"
DIR = os.path.join(WORK, "babble")
DESIGN_CREDITS = 400      # ledger figure per design call; measured ~265 from the account counter (17 calls = ~4.5k)

EMOTES = ["laugh", "hmm", "angry", "oh", "mmhm", "bye"]

# bank -> description, syllable flavour, emote texts, F0 window (Hz) the bank must sit in,
# and the pitch range (playback multiplier) a per-NPC offset may use without leaving the archetype.
BANKS = {
    "m_young": dict(desc="A young medieval village man in his late teens, bright light tenor voice, friendly and quick",
                    syl="ba de lo mi ta ne vo shu ki ra", f0=(110, 190), pitch=(0.96, 1.10)),
    "m_adult": dict(desc="A grown medieval village man, warm mid baritone voice, easy-going and plain-spoken",
                    syl="ba do mo ra te ne lu va ko sho", f0=(85, 150), pitch=(0.92, 1.06)),
    "m_old": dict(desc="An old medieval village man, gravelly slow voice with a slight quaver, kindly grandfather",
                  syl="bo mo ru da ne ho lu wa go tsu", f0=(75, 150), pitch=(0.92, 1.03)),
    "f_young": dict(desc="A young medieval village woman, light bright soprano voice, cheerful and quick",
                    syl="li ma ne sa bi lu ta mi ye ra", f0=(180, 360), pitch=(0.97, 1.10)),
    "f_adult": dict(desc="A grown medieval village woman, warm clear mezzo voice, calm and capable",
                    syl="ma la ne sho vi ra de lu na ke", f0=(160, 260), pitch=(0.94, 1.06)),
    "f_old": dict(desc="An elderly medieval village woman, thin wavering high voice, warm and a little shaky",
                  syl="me no lu da wi ha so mi te ra", f0=(160, 300), pitch=(0.95, 1.05)),
    "brute": dict(desc="A huge hulking ogre-like brute of a man, extremely deep bass voice, low growling gravelly rumble, slow menacing grunting speech, very low pitch",
                  syl="gra dun bor hag mok tur gur dro ruk bah", f0=(55, 120), pitch=(0.95, 1.01), shift=1.1),
    "goblin": dict(desc="A small sneaky goblin, high nasal scratchy voice, fast cackling chatter",
                   syl="nik skee gib yip tik neeb zik kee rit snee", f0=(200, 480), pitch=(0.97, 1.05), shift=0.92),
    "captain": dict(desc="A battle-hardened raid captain, low commanding raspy voice, confident and cold",
                    syl="dra kor vas tun hel rok mar gan dur sek", f0=(70, 140), pitch=(0.9, 1.02)),
    "peddler": dict(desc="A cheerful middle-aged male travelling merchant with a warm baritone voice, lively sing-song delivery, always selling, clearly a man",
                    syl="la di pa ti lo mi fa ri ko bee", f0=(110, 230), pitch=(0.97, 1.08)),
    "envoy": dict(desc="A posh noble envoy, measured refined baritone voice, precise and haughty",
                  syl="par veh lis mon dor ess ri qua bel sau", f0=(85, 160), pitch=(0.95, 1.04)),
    "minstrel": dict(desc="A cheerful young male wandering minstrel, melodic lilting tenor voice, a man who speaks almost singing",
                     syl="la lo li na ro di ma tee lu fa", f0=(110, 220), pitch=(0.97, 1.08)),
}

EMOTE_TEXT = {
    "laugh": "Hahaha!", "hmm": "Hmmmm...", "angry": "Hrrgh!", "oh": "Oh!", "mmhm": "Mm-hm.", "bye": "Ba-bai!",
}
BANNED = set("""a an the i me my we you he she it is am are be to do go no so oh ok hi ho ha lo la
ma pa ta ra mi mo me he be de di do la li lu ya ye yo on in at as or of up us by bye bay day may say way
see tea tee too two tu vie via via tar tin ton dor gun bar bor rod mod hag rok tun dun sek mar par bel
lis mon dun doom""".split())


def gibberish(bank, seed, n_phrases=11):
    """Sentences of made-up words from the bank's syllables; no dictionary words."""
    rnd = random.Random(seed)
    syl = BANKS[bank]["syl"].split()
    out = []
    for _ in range(n_phrases):
        words = []
        for _ in range(rnd.randint(2, 4)):
            w = "".join(rnd.choice(syl) for _ in range(rnd.randint(1, 3)))
            if w in BANNED or len(w) < 3:
                w = w + rnd.choice(syl)
            words.append(w)
        s = " ".join(words)
        s = s[0].upper() + s[1:] + rnd.choice([".", "?", "!", ",", "."])
        out.append(s)
    return " ".join(out)


def script(bank, seed=11):
    # Previews are capped at ~24 s, so the six emotes come FIRST (each followed by a long pause),
    # then the gibberish that gets sliced into syllables.
    head = " ... ... ".join(EMOTE_TEXT[e] for e in EMOTES)
    return f"{head} ... ... ... {gibberish(bank, seed, n_phrases=8)}"


def design(bank, seed=11):
    import gen
    b = BANKS[bank]
    d = os.path.join(DIR, bank)
    os.makedirs(d, exist_ok=True)
    text = script(bank, seed)
    s = gen._session()
    r = s.post(f"{gen.API}/v1/text-to-voice/design", params={"output_format": "mp3_44100_192"},
               json={"voice_description": b["desc"] + ", medieval fantasy game character, close mic, dry, no reverb",
                     "text": text, "model_id": "eleven_multilingual_ttv_v2", "seed": seed, "loudness": 0.5,
                     "guidance_scale": 5}, timeout=240)
    if not r.ok:
        raise RuntimeError(f"design failed {bank}: {r.status_code} {r.text[:200]}")
    meta = {"desc": b["desc"], "text": text, "seed": seed, "previews": []}
    for i, p in enumerate(r.json()["previews"]):
        f = os.path.join(d, f"preview{i}.mp3")
        with open(f, "wb") as fh:
            fh.write(base64.b64decode(p["audio_base_64"]))
        meta["previews"].append({"file": os.path.basename(f), "duration": p.get("duration_secs")})
    with open(os.path.join(d, "design.json"), "w", encoding="utf-8") as fh:
        json.dump(meta, fh, indent=1)
    led = gen._load_ledger()
    led["total"] += DESIGN_CREDITS; led["calls"] += 1
    led["entries"].append({"time": datetime.datetime.now().strftime("%m-%d %H:%M:%S"), "event": f"voice.{bank} (design)",
                           "cand": 0, "dur": 0, "credits": DESIGN_CREDITS})
    gen._save_ledger(led)


# ------------------------------------------------------------------ analysis

def f0_track(x, sr=A.SR, win=0.04, hop=0.01, lo=50, hi=600):
    """YIN pitch per voiced frame (cumulative-mean-normalised difference; robust to octave errors)."""
    n, h = int(win * sr), int(hop * sr)
    tau_min, tau_max = int(sr / hi), int(sr / lo)
    out = []
    for i in range(0, len(x) - n - tau_max, h):
        fr = x[i:i + n + tau_max]
        if np.sqrt(np.mean(fr[:n] ** 2)) < 0.01:
            continue
        d = np.array([np.sum((fr[:n] - fr[t:t + n]) ** 2) for t in range(tau_max + 1)])
        cm = d.copy(); cm[0] = 1.0
        cm[1:] = d[1:] * np.arange(1, tau_max + 1) / (np.cumsum(d[1:]) + 1e-12)
        below = np.where(cm[tau_min:] < 0.15)[0]
        if len(below) == 0:
            continue
        t = tau_min + below[0]
        while t + 1 <= tau_max and cm[t + 1] < cm[t]:
            t += 1
        out.append(sr / t)
    return np.array(out)


def segments(x, win=0.01, rel=-32, min_gap=0.04):
    e = A.env_db(x, win)
    thr = e.max() + rel
    on = e > thr
    segs, i = [], 0
    while i < len(on):
        if on[i]:
            j = i
            while j < len(on) and on[j]:
                j += 1
            segs.append([i, j]); i = j
        else:
            i += 1
    merged = []
    for s in segs:
        if merged and (s[0] - merged[-1][1]) * win < min_gap:
            merged[-1][1] = s[1]
        else:
            merged.append(s)
    return [(a * win, b * win) for a, b in merged]


def split_long(x, a, b, lo=0.08, hi=0.25):
    """Cut a voiced run into syllables at local energy dips."""
    seg = x[int(a * A.SR):int(b * A.SR)]
    if b - a <= hi:
        return [(a, b)] if b - a >= lo else []
    e = A.env_db(seg, 0.005)
    cuts, start = [], 0.0
    t = lo
    while (b - a) - start > hi:
        i0, i1 = int((start + lo) / 0.005), int(min(b - a - lo, start + hi) / 0.005)
        if i1 <= i0:
            break
        k = i0 + int(np.argmin(e[i0:i1]))
        cuts.append((a + start, a + k * 0.005)); start = k * 0.005
    if (b - a) - start >= lo:
        cuts.append((a + start, b))
    return cuts


def soften(x, target_lufs):
    """Owner v2: softer, airier, mumbly. 5.5 kHz low-pass, 2:1 compression, click-free fades."""
    from scipy import signal as sg
    y = sg.sosfilt(sg.butter(4, 5500, "lowpass", fs=A.SR, output="sos"), x)
    y = A.high_shelf(y, 3000, -3.0)                      # tame bright consonant edges / sibilance
    # gentle feed-forward compressor on a smoothed envelope
    env = np.sqrt(sg.lfilter([0.002], [1, -0.998], y ** 2) + 1e-12)
    thr = 10 ** (-24 / 20) * (np.abs(y).max() + 1e-9)
    gain = np.where(env > thr, (thr / env) ** 0.5, 1.0)
    y = y * gain
    y = A.fade(y, 0.012, 0.045)
    return A.normalize(y, target_lufs)


def harshness(y):
    """Higher = crisper/sibilant/clicky: HF energy share plus how sharp the onset is."""
    hf = A.hf_ratio(y, 4000)
    e = A.env_db(y, 0.002)
    rise = float(np.max(np.diff(e[:12]))) if len(e) > 12 else 0.0
    return hf * 10 + max(0.0, rise) / 6.0


def pick_preview(bank):
    d = os.path.join(DIR, bank)
    lo, hi = BANKS[bank]["f0"]
    best = None
    for p in sorted(os.listdir(d)):
        if not p.startswith("preview"):
            continue
        x = A.decode(os.path.join(d, p))
        f0 = f0_track(x[::2], sr=A.SR // 2)
        med = float(np.median(f0)) if len(f0) else 0.0
        inside = lo <= med <= hi
        # cleanliness: fraction of energy in gaps (noise/room) -> lower is better
        e = A.env_db(x, 0.01)
        floor = float(np.percentile(e, 15) - e.max())
        centre = (lo * hi) ** 0.5
        score = (0 if inside else 50 + min(abs(med - lo), abs(med - hi))) + max(0.0, floor + 45)             + 10 * abs(np.log2(max(med, 1) / centre))       # prefer the heart of the archetype's range
        cand = dict(file=p, f0=round(med, 1), floor=round(floor, 1), score=round(score, 1))
        if best is None or score < best["score"]:
            best = cand
    return best


def slice_bank(bank, n_syll=14):
    d = os.path.join(DIR, bank)
    pick = pick_preview(bank)
    x = A.hp(A.decode(os.path.join(d, pick["file"])), 70)
    shift = BANKS[bank].get("shift", 1.0)
    if shift != 1.0:                        # resample like Minecraft's pitch: <1 = deeper and slower
        from fractions import Fraction
        f = Fraction(shift).limit_denominator(200)
        x = A.signal.resample_poly(x, f.denominator, f.numerator)
    segs = segments(x)
    # The six emotes open the take, each followed by a long pause; group the first islands
    # into six emotes at the widest gaps inside the first ~45 % of the take.
    t_end = segs[-1][1]
    head = [sg for sg in segs if sg[1] < 0.5 * t_end]
    emote_segs = []
    body = segs
    if len(head) >= 7:
        gaps = sorted(((head[i + 1][0] - head[i][1], i) for i in range(len(head) - 1)), reverse=True)[:6]
        cuts = sorted(i for _, i in gaps)          # 6 widest gaps: 5 between emotes + 1 before the gibberish
        start = 0
        for i in cuts:
            emote_segs.append((head[start][0], head[i][1])); start = i + 1
        body = segs[start:]
    # an emote that is implausibly short/long means the grouping failed: drop it (the client
    # then falls back to the nearest sibling bank's emote)
    emote_segs = [s_ if 0.12 <= s_[1] - s_[0] <= 1.8 else None for s_ in emote_segs]
    syll = []
    for a, b in body:
        syll += split_long(x, a, b)
    rng = random.Random(3)
    cands = []
    for a, b in syll:
        y = x[max(0, int((a - 0.005) * A.SR)):int((b + 0.02) * A.SR)]
        if len(y) < 0.07 * A.SR:
            continue
        f0 = f0_track(y[::2], sr=A.SR // 2, win=0.03, hop=0.01)
        if len(f0) < 2:
            continue                                  # unvoiced / breath
        cands.append((a, b, y))
    # v2: keep the mumbly, vowel-heavy takes (lowest harshness), still spread across the take
    if len(cands) > n_syll:
        ranked = sorted(range(len(cands)), key=lambda i: harshness(cands[i][2]))
        keep = sorted(ranked[:n_syll])
        cands = [cands[i] for i in keep]
    out = os.path.join(DIR, bank, "out")
    os.makedirs(out, exist_ok=True)
    for f in os.listdir(out):
        os.remove(os.path.join(out, f))
    files = []
    for i, (a, b, y) in enumerate(cands, 1):
        y = soften(y, -27.5)
        name = f"babble{i}"
        A.encode_ogg(y, os.path.join(out, name + ".ogg"))
        files.append(name)
    emotes = {}
    for e_name, seg_ in zip(EMOTES, emote_segs):
        if seg_ is None:
            continue
        a, b = seg_
        y = x[max(0, int((a - 0.01) * A.SR)):int((b + 0.06) * A.SR)]
        y = soften(A.cap_length(y, 1.0), -29.0)
        A.encode_ogg(y, os.path.join(out, e_name + ".ogg"))
        emotes[e_name] = round(len(y) / A.SR, 2)
    info = dict(bank=bank, preview=pick, syllables=len(files), emotes=emotes, islands=len(segs),
                pitch_range=BANKS[bank]["pitch"])
    with open(os.path.join(DIR, bank, "slice.json"), "w", encoding="utf-8") as fh:
        json.dump(info, fh, indent=1)
    return info


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bank", action="append")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--slice-only", action="store_true")
    ap.add_argument("--seed", type=int, default=11)
    ap.add_argument("--force", action="store_true", help="redesign even if previews exist")
    a = ap.parse_args()
    banks = list(BANKS) if a.all else (a.bank or [])
    for b in banks:
        if not a.slice_only and (a.force or not os.path.exists(os.path.join(DIR, b, "design.json"))):
            design(b, a.seed)
        print(json.dumps(slice_bank(b)))


if __name__ == "__main__":
    main()
