"""Real-speech dialogue for conversation/interaction lines only (owner approved 26 Sep).

Voices (saved to the owner's library as "Bannerhold – <character>", owner-approved):
  the 5 pilot voices are cloned (instant voice clone) from their chosen pilot take; three more
  (minstrel, envoy, merchant) are designed. TTS: eleven_multilingual_v2, low stability for natural
  variation, 2 takes per line, auto-pick the more natural (more intonation, fewer dead pauses).
Lines with names/numbers are voiced as a name-free variant (VOICE_TEXT), never a wrong name.
Post: long AI pauses trimmed, 9.5 kHz roll-off, low-mid warmth, subtle saturation, a light room
or outdoor air matched to the setting, -23 LUFS mono OGG.

    python dialogue.py voices       # create/clone the 8 voices (once)
    python dialogue.py preview      # 6-8 finished lines -> videos/audio/voice-final-preview.mp3
    python dialogue.py all          # every line, install into the mod
"""
import base64, datetime, json, os, subprocess, sys, time
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import audiolib as A  # noqa: E402
import babble as B  # noqa: E402
import gen  # noqa: E402
import apply as P  # noqa: E402
import voice_pilot as VP  # noqa: E402

DIR = os.path.join(gen.WORK, "dialogue")
VOICES_JSON = os.path.join(DIR, "voices.json")
CAP = 10000
FLAVOUR = ("plain-spoken medieval village folk, understated, a little regional warmth, natural pacing, "
           "not theatrical, no announcer tone, close mic, dry")

# character -> pilot key to clone from, or a design description
CHARACTERS = {
    "traveller": {"clone": "traveller", "setting": "outdoor"},
    "refugee": {"clone": "young_woman", "setting": "outdoor"},
    "aldric": {"clone": "old_man", "setting": "outdoor"},
    "brute": {"clone": "brute", "setting": "outdoor"},
    "captain": {"clone": "captain", "setting": "outdoor"},
    "minstrel": {"design": "A cheerful wandering minstrel man in his thirties, light warm tenor, easy smile in the voice, "
                           + FLAVOUR, "setting": "room"},
    "envoy": {"design": "A noble's envoy, a composed man in his forties, measured refined baritone, cool and polite, "
                        "quietly condescending, " + FLAVOUR, "setting": "outdoor"},
    # owner: the first merchant voice was "awful" -> retired; a new grounded trader instead
    # owner round 2: the bright/salesy traders were "awful", the dark enemy voices "damn good" ->
    # lower, grounded, weathered, understated, plain-spoken; no smile, no upbeat pace.
    "caravan": {"design": "A gruff road-worn older teamster who leads a merchant caravan, man in his late fifties, low dry "
                          "baritone, weathered and a little raspy, speaks plainly and slowly, understated, no salesman "
                          "tone, no smile in the voice, close mic, dry, not theatrical", "setting": "outdoor",
                "label": "Caravan Master (v2)", "grounded": True},
    "peddler": {"design": "A wiry sly old travelling tinker, man in his sixties, mid-low voice, slow, a little crackly and "
                          "conspiratorial, speaks quietly as if sharing a secret, not cheerful, no salesman tone, close mic, "
                          "dry, not theatrical", "setting": "outdoor", "label": "Peddler (v2)", "grounded": True},
    "captain_red": {"design": "A young hot-headed raid captain called the Red, rough tenor with a bark in it, impatient and "
                              "cocky but not shouting, " + FLAVOUR, "setting": "outdoor", "label": "Captain the Red"},
    "captain_torch": {"design": "A zealous raid captain called the Torch, intense clipped baritone, burning conviction held "
                                "under control, quiet menace, " + FLAVOUR, "setting": "outdoor", "label": "Captain the Torch"},
    "captain_reaper": {"design": "An old grim raid captain called the Reaper, slow deep gravelly voice, weary and cold, "
                                 "few words, " + FLAVOUR, "setting": "outdoor", "label": "Captain the Reaper"},
}
# captains by epithet: the parley lines get extra takes in these voices; ids voice.line.<key>.<epithet>
VARIANTS = {"parley": {"red": "captain_red", "torch": "captain_torch", "reaper": "captain_reaper"}}
SPEAKER = {"traveller": "traveller", "refugees": "refugee", "demo_peddler": "aldric", "brute_toll": "brute",
           "parley": "captain", "minstrels": "minstrel", "rival_envoy": "envoy", "caravan": "caravan",
           "peddler": "peddler", "barter": "peddler"}
# name-free spoken variants for lines whose display text carries names or numbers
VOICE_TEXT = {
    "parley.bandit.demand": "Me and the lads want coin. Or food, we're not fussy. Pay up and we're gone before supper.",
    "parley.bandit.farewell.tribute": "Nice doing business with you.",
    "parley.bandit.farewell.truce": "We'll be back when you're fatter.",
    "parley.bandit.farewell.duel": "You swing well. We're off.",
    "rival_envoy.intro": "I speak for my lord.",
    "refugees.line2": "Let us stay. We will work; we only need a roof, and bread.",
    "minstrels.line1": "Lute, drum and fiddle, friend! Host us tonight, and your folk will dance till the fire dies.",
    "minstrels.line2": "Coins for a feast. Or we play a short set for whatever lands in the hat.",
    "brute_toll.demand2": "Food. Or your shiny coins. And we go. Or we take it from your bones.",
    "parley.demand": "My band waits at your edge. Pay in coin, or in food, and we ride on. Refuse, and we take it all.",
    "parley.reply.tribute": "Wise. We will remember you kindly... for a while.",
    "traveller.bye": "And to you. I will remember this place.",
    "demo_peddler.haggle_yes": "Ha! Fine, fine. For a friend, a better price.",
    "caravan.line2": "The woods ahead are thick with bandits. Ride with us to the far road, and I'll pay you well.",
    "rival_envoy.line1": "My lord watches you grow. He wonders whether you will be a friend... or a problem.",
    "parley.farewell.tribute": "A fair price. We'll remember this.",
    "parley.farewell.truce": "Your words carry weight. We'll remember this.",
    "parley.farewell.duel": "You fight well. We go.",
}
PREVIEW = ["traveller.intro", "refugees.line1", "demo_peddler.haggle_no", "brute_toll.demand",
           "parley.reply.truce_yes", "minstrels.line1", "rival_envoy.line2", "caravan.line1"]
SETTINGS = {  # stability low = more natural variation; style moderate
    "brute": dict(stability=0.45, similarity_boost=0.8, style=0.15, speed=0.8),   # owner: slow, guttural
    "captain": dict(stability=0.45, similarity_boost=0.8, style=0.2, speed=0.95),
}
DEFAULT = dict(stability=0.33, similarity_boost=0.8, style=0.25, speed=1.0)
GROUNDED = dict(stability=0.45, similarity_boost=0.8, style=0.15, speed=0.88)   # what made the enemy voices work
for _k in ("caravan", "peddler", "traveller", "aldric", "refugee"):
    SETTINGS[_k] = GROUNDED
GROUNDED_SPEAKERS = {"caravan", "peddler", "traveller", "aldric", "refugee"}
SETTINGS["traveller"] = dict(GROUNDED, speed=0.8, stability=0.5)   # still measured fast/sing-song at 0.88


SKIPPED = []


def lines():
    with open(P.LANG, encoding="utf-8") as fh:
        d = json.load(fh)
    out = {}
    for k, v in d.items():
        key = k[len("conversation.hearthstead."):]
        if not k.startswith("conversation.hearthstead.") or (not v.strip().startswith('"') and key not in VOICE_TEXT):
            continue
        group = key.split(".")[0]
        if group not in SPEAKER:
            continue
        text = VOICE_TEXT.get(key, v.strip().strip('"'))
        if "%" in text:
            # never read a name/number: without a name-free VOICE_TEXT the line stays unvoiced (hmm fallback)
            SKIPPED.append(key)
            continue
        if key.startswith("parley.bandit."):
            # road-bandit parley: one rough voice (the Red's), no epithet variants
            out[key] = {"speaker": "captain_red", "text": text, "display": v}
            continue
        out[key] = {"speaker": SPEAKER[group], "text": text, "display": v}
        for variant, spk in VARIANTS.get(group, {}).items():
            out[f"{key}.{variant}"] = {"speaker": spk, "text": text, "display": v}
    return out


def _ledger(event, credits):
    led = gen._load_ledger()
    led["total"] += credits; led["calls"] += 1
    led["entries"].append({"time": datetime.datetime.now().strftime("%m-%d %H:%M:%S"), "event": event,
                           "cand": 0, "dur": 0, "credits": credits})
    gen._save_ledger(led)


def _post(s, url, **kw):
    for _ in range(40):
        r = s.post(url, timeout=240, **kw)
        if r.status_code == 429:
            time.sleep(10); continue
        return r
    raise RuntimeError("rate limited")


def make_voices():
    os.makedirs(DIR, exist_ok=True)
    voices = json.load(open(VOICES_JSON)) if os.path.exists(VOICES_JSON) else {}
    s = gen._session()
    hdr = {"xi-api-key": s.headers["xi-api-key"]}
    for name, c in CHARACTERS.items():
        if name in voices:
            continue
        label_ = f"Bannerhold – {c.get('label', name.capitalize())}"
        if "clone" in c:
            window = next(ch[2] for ch in VP.CHARS if ch[0] == c["clone"])
            score, p, med = VP.pick(c["clone"], window)
            path = os.path.join(VP.DIR, c["clone"], p)
            with open(path, "rb") as fh:
                r = None
                for _ in range(40):
                    fh.seek(0)
                    r = __import__("requests").post(f"{gen.API}/v1/voices/add", headers=hdr, timeout=240,
                                                   data={"name": label_, "description": "Bannerhold mod dialogue voice",
                                                         "remove_background_noise": "false"},
                                                   files={"files": (os.path.basename(path), fh, "audio/mpeg")})
                    if r.status_code != 429:
                        break
                    time.sleep(10)
            r.raise_for_status()
            voices[name] = {"voice_id": r.json()["voice_id"], "from": f"clone of pilot {c['clone']}/{p}"}
        else:
            text = ("Well now, that is a fair question, and I will answer it plainly. The road was long, the weather "
                    "was poor, and still here we are, safe by your fire.")
            r = _post(s, f"{gen.API}/v1/text-to-voice/design", params={"output_format": "mp3_44100_128"},
                      json={"voice_description": c["design"], "text": text, "model_id": "eleven_multilingual_ttv_v2",
                            "seed": 3})
            r.raise_for_status()
            prev = r.json()["previews"]
            d = os.path.join(DIR, "design_" + name); os.makedirs(d, exist_ok=True)
            best = None
            for i, p in enumerate(prev):
                f = os.path.join(d, f"preview{i}.mp3")
                open(f, "wb").write(base64.b64decode(p["audio_base_64"]))
                x = A.decode(f); e = A.env_db(x, 0.01)
                floor = float(np.percentile(e, 15) - e.max())
                q = (-brightness(x) if c.get("grounded") else naturalness(x)) - 0.1 * max(0.0, floor + 50) - artefacts(x)
                if best is None or q > best[0]:
                    best = (q, p["generated_voice_id"], i)
            _ledger(f"dialogue.voice_design.{name}", len(text))
            r = _post(s, f"{gen.API}/v1/text-to-voice", json={"voice_name": label_, "voice_description": c["design"][:490],
                                                               "generated_voice_id": best[1]})
            r.raise_for_status()
            voices[name] = {"voice_id": r.json()["voice_id"], "from": f"designed, preview {best[2]}"}
        json.dump(voices, open(VOICES_JSON, "w"), indent=1)
        print("voice", name, voices[name]["from"])
    return voices


def tts(s, voice_id, text, speaker, take):
    vs = dict(SETTINGS.get(speaker, DEFAULT))
    speed = vs.pop("speed")
    vs["use_speaker_boost"] = True
    vs["speed"] = speed
    r = _post(s, f"{gen.API}/v1/text-to-speech/{voice_id}", params={"output_format": "mp3_44100_128"},
              json={"text": text, "model_id": "eleven_multilingual_v2", "voice_settings": vs, "seed": 100 + take})
    r.raise_for_status()
    return r.content


def tighten_pauses(x, max_gap=0.32, keep=0.24):
    """Shorten over-long AI pauses; keep small breaths/hesitations."""
    e = A.env_db(x, 0.01)
    quiet = e < e.max() - 38
    out, i, n = [], 0, len(e)
    while i < n:
        j = i
        while j < n and quiet[j] == quiet[i]:
            j += 1
        seg = x[i * 441:j * 441]
        if quiet[i] and (j - i) * 0.01 > max_gap and i > 0 and j < n:
            k = int(keep * A.SR)
            seg = np.concatenate([seg[:k // 2], seg[-k // 2:]])
        out.append(seg); i = j
    return np.concatenate(out)


def artefacts(x):
    """Penalty for pitch glitches (octave jumps), metallic/buzzy tone (high spectral flatness in voiced
    frames) and rushed pacing (too many syllable onsets per second)."""
    from scipy import signal as sg
    f0 = B.f0_track(x[::2], sr=A.SR // 2)
    jumps = float(np.mean(np.abs(np.diff(12 * np.log2(f0 + 1e-9))) > 7)) if len(f0) > 5 else 0.0
    f, t, Z = sg.stft(x, A.SR, nperseg=1024)
    P_ = np.abs(Z) ** 2 + 1e-12
    band = (f > 300) & (f < 5000)
    flat = np.exp(np.mean(np.log(P_[band]), axis=0)) / np.mean(P_[band], axis=0)
    loud = 10 * np.log10(np.sum(P_, axis=0)); voiced = loud > loud.max() - 25
    metallic = float(np.mean(flat[voiced])) if voiced.any() else 0.0
    e = A.env_db(x, 0.01); on = np.sum((e[1:] > e.max() - 20) & (e[:-1] <= e.max() - 20))
    rate = on / max(0.5, len(x) / A.SR)
    return 10 * jumps + 20 * max(0.0, metallic - 0.25) + max(0.0, rate - 4.5)


def brightness(x, text_len=None):
    """'Salesy/smiling' penalty: bright spectrum, sing-song pitch, fast delivery."""
    f0 = B.f0_track(x[::2], sr=A.SR // 2)
    std = 0.0
    if len(f0) > 5:
        semis = 12 * np.log2(f0 / np.median(f0)); semis = semis[np.abs(semis) < 7]
        std = float(np.std(semis)) if len(semis) else 0.0
    pen = max(0.0, A.spectral_centroid(x) - 700) / 400 + 2 * max(0.0, std - 2.4)
    if text_len:
        cps = text_len / max(0.5, len(A.trim(x, -45)) / A.SR)
        pen += 0.5 * max(0.0, cps - 13)
    return pen


def naturalness(x):
    f0 = B.f0_track(x[::2], sr=A.SR // 2)
    if len(f0) < 5:
        return -99
    semis = 12 * np.log2(f0 / np.median(f0))
    semis = semis[np.abs(semis) < 7]                       # ignore octave errors
    e = A.env_db(x, 0.01)
    quiet = np.mean(e < e.max() - 38)
    return float(np.std(semis)) - 4 * max(0.0, quiet - 0.25)   # intonation, minus dead air


def peaking(x, f0, gain_db, q):
    """RBJ peaking bell."""
    from scipy import signal as sg
    A_ = 10 ** (gain_db / 40); w0 = 2 * np.pi * f0 / A.SR; alpha = np.sin(w0) / (2 * q)
    b = [1 + alpha * A_, -2 * np.cos(w0), 1 - alpha * A_]
    a = [1 + alpha / A_, -2 * np.cos(w0), 1 - alpha / A_]
    return sg.lfilter(np.array(b) / a[0], np.array(a) / a[0], x)


def deess(x, thr_db=-30, band=(5500, 9000), max_cut_db=6):
    """Light split-band de-esser: duck only the sibilant band when it jumps."""
    from scipy import signal as sg
    sos = sg.butter(2, band, "bandpass", fs=A.SR, output="sos")
    s_ = sg.sosfilt(sos, x)
    env = np.sqrt(sg.lfilter([0.01], [1, -0.99], s_ ** 2) + 1e-12)
    lvl = 20 * np.log10(env / (np.abs(x).max() + 1e-9))
    cut = np.clip(lvl - thr_db, 0, max_cut_db)
    return x - s_ * (1 - 10 ** (-cut / 20))


def master(x, setting, speaker=""):
    """v2 (owner: 'a bit foggy, want it sharper'): clear and present; the game world supplies the space."""
    x = A.hp(A.trim(x, -45, post=0.08), 60 if speaker == "brute" else 85)
    x = tighten_pauses(x)
    x = peaking(x, 280, -2.0, 0.9)                         # de-mud 200-350 Hz
    x = peaking(x, 4000, 2.5, 0.7)                         # presence bell 3-5 kHz
    x = A.high_shelf(x, 10000, 1.5)                        # a little air
    x = A.lp(x, 16000, 2)                                  # only a gentle top cut
    x = deess(x)
    x = A.saturate(x, 0.4)
    if setting == "room":                                  # minstrel: a subtle room only
        rng = np.random.default_rng(7)
        n = int(0.05 * A.SR)
        ir = rng.standard_normal(n) * np.exp(-np.linspace(0, 7, n)); ir = A.lp(ir, 5000) / np.sqrt(np.sum(ir ** 2))
        wet = np.convolve(x, ir)[:len(x)]
        x = x + 0.05 * wet * (np.abs(x).max() / (np.abs(wet).max() + 1e-9))
    x = A.fade(x, 0.01, 0.08)
    return A.normalize(x, -23.0)


def render(keys, all_lines, voices):
    s = gen._session()
    spent = 0
    for key in keys:
        L = all_lines[key]
        d = os.path.join(DIR, "lines", key)
        os.makedirs(d, exist_ok=True)
        for take in (1, 2):
            f = os.path.join(d, f"take{take}.mp3")
            if os.path.exists(f):
                continue
            if spent + len(L["text"]) > CAP:
                raise SystemExit("STOP: dialogue cap")
            open(f, "wb").write(tts(s, voices[L["speaker"]]["voice_id"], L["text"], L["speaker"], take))
            _ledger(f"dialogue.{key}", len(L["text"])); spent += len(L["text"])
        # a take far longer/shorter than the text implies has rambled, sung or dropped words: reject it
        expect = len(L["text"]) / 14.0 + 0.4
        takes = []
        for t in (1, 2):
            x = A.decode(os.path.join(d, f"take{t}.mp3"))
            ratio = len(A.trim(x, -45)) / A.SR / expect
            nat = -brightness(x, len(L["text"])) if L["speaker"] in GROUNDED_SPEAKERS else naturalness(x)
            takes.append((nat - artefacts(x) - (20 if not 0.55 <= ratio <= 1.6 else 0), t))
        best = max(takes)[1]
        y = master(A.decode(os.path.join(d, f"take{best}.mp3")), CHARACTERS[L["speaker"]]["setting"], L["speaker"])
        A.encode_ogg(y, os.path.join(d, "final.ogg"))
        L["take"] = best; L["seconds"] = round(len(y) / A.SR, 2)
    return spent


def envelope(y, hz=20):
    n = int(A.SR / hz)
    m = len(y) // n
    r = np.sqrt(np.mean(y[: m * n].reshape(m, n) ** 2, axis=1) + 1e-12)
    db = 20 * np.log10(r)
    v = np.clip((db - (db.max() - 40)) / 40, 0, 1)
    return [round(float(a), 2) for a in v]


def install(all_lines):
    """Contract with the conversation lane: id hearthstead:voice.line.<key> (key = lang key minus
    'conversation.hearthstead.'), manifest assets/hearthstead/voice_lines.json {lines: {key: {ms, env}}}."""
    import shutil
    snd, crlf = P.load_json(P.SOUNDS_JSON)
    dst = os.path.join(P.ASSETS, "sounds", "el", "dialogue")
    os.makedirs(dst, exist_ok=True)
    changed, manifest = [], {}
    for key, L in all_lines.items():
        src = os.path.join(DIR, "lines", key, "final.ogg")
        if not os.path.exists(src):
            continue
        name = key.replace(".", "_")
        shutil.copyfile(src, os.path.join(dst, name + ".ogg"))
        ev = f"voice.line.{key}"
        snd[ev] = {"sounds": [{"name": f"hearthstead:el/dialogue/{name}", "volume": 1.0,
                               "attenuation_distance": 16}]}
        changed.append(ev)
        y = A.decode(src)
        manifest[key] = {"ms": int(round(len(y) / A.SR * 1000)), "env": envelope(y)}
    P.dump_sounds(snd, crlf, changed)
    with open(os.path.join(P.ASSETS, "voice_lines.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump({"_comment": "Voiced conversation lines (sound pass). id = hearthstead:voice.line.<key>; "
                               "ms = clip length; env = 20 Hz loudness 0..1. Lines with names/numbers are voiced "
                               "name-free (e.g. 'I will remember this place').", "lines": manifest},
                  fh, indent=1, sort_keys=True)
        fh.write("\n")
    print(f"installed {len(changed)} dialogue lines")


def preview(all_lines):
    from labels import label
    who = {"traveller": "The traveller", "refugee": "A refugee woman", "aldric": "Aldric the peddler",
           "brute": "The toll brute", "captain": "The Ashen raid captain", "minstrel": "The minstrel",
           "envoy": "The rival lord's envoy", "merchant": "The caravan merchant"}
    seq = []
    for key in PREVIEW:
        L = all_lines[key]
        y = A.decode(os.path.join(DIR, "lines", key, "final.ogg"))
        seq += [label(who[L["speaker"]] + "."), np.zeros(int(0.3 * A.SR)), A.normalize(y, -16), np.zeros(int(0.8 * A.SR))]
    y = A.soft_limit(np.concatenate(seq), -1.0)
    out = r"C:\Users\tobia\Hearthstead-Claude\videos\audio\voice-final-preview-v2.mp3"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(A.SR), "-ac", "1", "-i", "-",
                    "-c:a", "libmp3lame", "-b:a", "160k", out], input=y.astype(np.float32).tobytes(), check=True)
    with open(out[:-4] + ".txt", "w", encoding="utf-8") as fh:
        for key in PREVIEW:
            L = all_lines[key]
            fh.write(f"{who[L['speaker']]}: {L['text']}  (take {L.get('take')}, {L.get('seconds')} s)\n")
    print("wrote", out, f"{len(y) / A.SR:.0f} s")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "preview"
    voices = make_voices()
    if cmd == "voices":
        return
    L = lines()
    keys = PREVIEW if cmd == "preview" else list(L)
    spent = render(keys, L, voices)
    json.dump(L, open(os.path.join(DIR, "lines.json"), "w"), indent=1)
    print("tts credits this run", spent)
    if cmd == "preview":
        preview(L)
    else:
        install(L)


if __name__ == "__main__":
    main()
