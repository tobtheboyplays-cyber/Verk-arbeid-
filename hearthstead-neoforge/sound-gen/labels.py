"""Spoken labels for the preview reels (ElevenLabs TTS, cached; never shipped in the mod)."""
import os, re, sys, datetime
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gen  # noqa: E402
import audiolib as A  # noqa: E402

VOICE = "JBFqnCBsd6RMkjVDRZzb"   # premade "George", warm British storyteller
DIR = os.path.join(gen.WORK, "labels")


def label(text):
    os.makedirs(DIR, exist_ok=True)
    slug = re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")[:60]
    path = os.path.join(DIR, slug + ".mp3")
    if not os.path.exists(path):
        s = gen._session()
        r = s.post(f"{gen.API}/v1/text-to-speech/{VOICE}", params={"output_format": "mp3_44100_128"},
                   json={"text": text, "model_id": "eleven_multilingual_v2",
                         "voice_settings": {"stability": 0.6, "similarity_boost": 0.7}}, timeout=60)
        r.raise_for_status()
        with open(path, "wb") as fh:
            fh.write(r.content)
        led = gen._load_ledger()
        cost = len(text)
        led["total"] += cost; led["calls"] += 1
        led["entries"].append({"time": datetime.datetime.now().strftime("%m-%d %H:%M:%S"), "event": "_tts_label",
                               "cand": 0, "dur": 0, "credits": cost})
        gen._save_ledger(led)
    x = A.decode(path)
    return A.normalize(A.trim(x, -40), -20)
