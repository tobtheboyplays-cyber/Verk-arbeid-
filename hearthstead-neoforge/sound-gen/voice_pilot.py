"""Real-speech voice PILOT (owner: small, ~2,000 credits max; do not voice the full set yet).

Five characters, two real lines each from the mod's conversation text. One ElevenLabs Voice Design
call per character: the description sets the voice (medieval village folk with a slightly nasal,
warm 'Minecraft villager' flavour), the text is the two lines, and the call returns 3 previews =
3 candidate voices already speaking them. Pick by pitch window + cleanliness, then process:
-23 LUFS, light room tone, mono. Nothing is saved to the owner's voice library.
"""
import base64, datetime, json, os, subprocess, sys, time
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import audiolib as A  # noqa: E402
import babble as B  # noqa: E402
import gen  # noqa: E402
from labels import label  # noqa: E402

DIR = os.path.join(gen.WORK, "voice_pilot")
OUT = r"C:\Users\tobia\Hearthstead-Claude\videos\audio\voice-pilot.mp3"
CAP = 2000
FLAVOUR = ("medieval village folk, slightly nasal and warm like a Minecraft villager, a hint of a thoughtful "
           "'hrmm' in the delivery, natural unhurried pacing, not theatrical, close mic, dry")
CHARS = [
    ("traveller", "The wandering traveller", (85, 150),
     "A weary adult man, a wandering traveller in his thirties, mid baritone, friendly, " + FLAVOUR,
     ['"Well met, stranger. Mind if I rest by your fire?"',
      '"Raiders gather past the river. They say their captain never forgets a slight."']),
    ("young_woman", "A young woman settler", (170, 320),
     "A young village woman in her early twenties, clear light voice, tired but hopeful, " + FLAVOUR,
     ['"Raiders burned our farm. We have walked for three days."',
      '"Let us stay in Eastmere. We will work; we only need a roof and bread for the four of us."']),
    ("old_man", "An old man, Aldric the peddler", (75, 150),
     "An old village man in his seventies, a travelling peddler, slightly gravelly kindly grandfather voice, " + FLAVOUR,
     ['"Ho there! Aldric of the long road, at your service."',
      '"The roads have been rough since the last raid. I still have a few useful things to trade."']),
    ("brute", "The toll brute", (55, 120),
     "A huge hulking brute of a man, very deep gruff gravelly bass voice, speaks slowly in short heavy words, menacing, "
     "close mic, dry, not theatrical",
     ['"Long road. Empty bellies. You have food."',
      '"Ten food, or twenty of your shiny Coins, and we go. Or we take it from your bones."']),
    ("captain", "The Ashen raid captain", (70, 140),
     "A battle-hardened raid captain called the Ashen, low raspy commanding voice, cold and confident, measured, "
     "close mic, dry, not theatrical",
     ['"Hold! Before my blades go to work, we talk."',
      '"Wise. We will remember Eastmere kindly... for a while."']),
]


def design(key, desc, lines):
    d = os.path.join(DIR, key)
    os.makedirs(d, exist_ok=True)
    if os.path.exists(os.path.join(d, "design.json")):
        return 0
    text = "  ".join(lines)
    s = gen._session()
    for attempt in range(40):
        r = s.post(f"{gen.API}/v1/text-to-voice/design", params={"output_format": "mp3_44100_192"},
                   json={"voice_description": desc, "text": text, "model_id": "eleven_multilingual_ttv_v2",
                         "seed": 5, "guidance_scale": 5}, timeout=240)
        if r.status_code == 429:
            time.sleep(15); continue
        r.raise_for_status()
        break
    else:
        raise RuntimeError("rate limited too long")
    for i, p in enumerate(r.json()["previews"]):
        with open(os.path.join(d, f"preview{i}.mp3"), "wb") as fh:
            fh.write(base64.b64decode(p["audio_base_64"]))
    json.dump({"desc": desc, "text": text}, open(os.path.join(d, "design.json"), "w"), indent=1)
    cost = len(text)
    led = gen._load_ledger()
    led["total"] += cost; led["calls"] += 1
    led["entries"].append({"time": datetime.datetime.now().strftime("%m-%d %H:%M:%S"),
                           "event": f"voice_pilot.{key}", "cand": 0, "dur": 0, "credits": cost})
    gen._save_ledger(led)
    return cost


def room(x):
    """Light room tone: a 70 ms synthetic early-reflection tail at 12 %, plus a -60 dB air bed."""
    rng = np.random.default_rng(1)
    n = int(0.07 * A.SR)
    ir = rng.standard_normal(n) * np.exp(-np.linspace(0, 6, n))
    ir = A.lp(ir, 4000) / np.sqrt(np.sum(ir ** 2))
    wet = np.convolve(x, ir)[:len(x)]
    y = x + 0.12 * wet * (np.abs(x).max() / (np.abs(wet).max() + 1e-9))
    bed = A.lp(rng.standard_normal(len(y)), 2000) * 10 ** (-60 / 20)
    return y + bed


def pick(key, window):
    d = os.path.join(DIR, key)
    lo, hi = window
    best = None
    for p in sorted(f for f in os.listdir(d) if f.startswith("preview")):
        x = A.decode(os.path.join(d, p))
        f0 = B.f0_track(x[::2], sr=A.SR // 2)
        med = float(np.median(f0)) if len(f0) else 0.0
        e = A.env_db(x, 0.01)
        floor = float(np.percentile(e, 15) - e.max())
        centre = (lo * hi) ** 0.5
        score = (0 if lo <= med <= hi else 50) + max(0.0, floor + 45) + 10 * abs(np.log2(max(med, 1) / centre))
        if best is None or score < best[0]:
            best = (score, p, med)
    return best


def main():
    os.makedirs(DIR, exist_ok=True)
    before = gen._used(gen._session())
    spent = 0
    for key, who, window, desc, lines in CHARS:
        est = len("  ".join(lines))
        if spent + est > CAP:
            raise SystemExit("STOP: pilot cap reached")
        spent += design(key, desc, lines)
    seq, cues, t = [], [], 0.0
    for key, who, window, desc, lines in CHARS:
        score, p, med = pick(key, window)
        x = A.hp(A.decode(os.path.join(DIR, key, p)), 70)
        x = A.normalize(room(A.trim(x, -45)), -23.0)
        A.encode_ogg(x, os.path.join(DIR, key, "pilot.ogg"))
        seg = np.concatenate([label(who + "."), np.zeros(int(0.4 * A.SR)), x, np.zeros(int(0.9 * A.SR))])
        cues.append(f"{int(t // 60):02d}:{t % 60:05.2f}  {who}  (take {p}, pitch ~{med:.0f} Hz)  " + " / ".join(lines))
        seq.append(seg); t += len(seg) / A.SR
    y = A.soft_limit(np.concatenate(seq), -1.0)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(A.SR), "-ac", "1", "-i", "-",
                    "-c:a", "libmp3lame", "-b:a", "160k", OUT], input=y.astype(np.float32).tobytes(), check=True)
    time.sleep(20)
    after = gen._used(gen._session())
    with open(OUT[:-4] + ".txt", "w", encoding="utf-8") as fh:
        fh.write("Bannerhold real-speech voice pilot: 5 characters x 2 real lines, ElevenLabs Voice Design, "
                 "-23 LUFS, light room tone.\n\n" + "\n".join(cues) +
                 f"\n\nCredits: ledger {spent} (1 per character of text); account counter {before} -> {after}\n")
    print(f"wrote {OUT} ({t:.0f} s); ledger {spent}; account {before} -> {after}")


if __name__ == "__main__":
    main()
