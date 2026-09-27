"""Research: three ways to get Sims-style gibberish from ElevenLabs, cost-measured."""
import base64, json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen
OUT = os.path.join(gen.WORK, "babble", "test")
GIB = ("Sha-bah nee dorra, vo keh mili-lo? Bah tuh zeera fo! Gunna yah wem, pollo sheeb nah-doo. "
       "Meh, oobi dah kessa? Vorra lin tobo, ha!")
s = gen._session()
def used(): return gen._used(s)[0]
res = {}
# A: premade voice TTS of gibberish
u0 = used()
r = s.post(f"{gen.API}/v1/text-to-speech/SOYHLrjzK2X1ezoPC6cr", params={"output_format": "mp3_44100_128"},
           json={"text": GIB, "model_id": "eleven_multilingual_v2", "voice_settings": {"stability": 0.35, "similarity_boost": 0.7, "style": 0.4}}, timeout=90)
r.raise_for_status(); open(os.path.join(OUT, "A_tts_harry.mp3"), "wb").write(r.content); time.sleep(2)
res["A_tts"] = used() - u0
# B: voice design previews speaking the gibberish
u0 = used()
r = s.post(f"{gen.API}/v1/text-to-voice/design", params={"output_format": "mp3_44100_128"},
           json={"voice_description": "An elderly village woman, thin wavering high voice, warm and a little shaky, medieval peasant",
                 "text": GIB, "model_id": "eleven_multilingual_ttv_v2", "seed": 7}, timeout=180)
print("design status", r.status_code, r.text[:200] if not r.ok else "")
if r.ok:
    for i, p in enumerate(r.json()["previews"]):
        open(os.path.join(OUT, f"B_design_oldwoman_{i}.mp3"), "wb").write(base64.b64decode(p["audio_base_64"]))
        res.setdefault("ids", []).append(p["generated_voice_id"])
time.sleep(2); res["B_design"] = used() - u0
# C: sound-generation babble
u0 = used()
r = s.post(f"{gen.API}/v1/sound-generation", params={"output_format": "mp3_44100_128"},
           json={"text": "A medieval peasant man talking gibberish nonsense syllables like a Sims character, no real words, cheerful, close mic, dry",
                 "duration_seconds": 3.0, "prompt_influence": 0.5}, timeout=120)
r.raise_for_status(); open(os.path.join(OUT, "C_sfx_babble.mp3"), "wb").write(r.content); time.sleep(2)
res["C_sfx"] = used() - u0
print(json.dumps({k: v for k, v in res.items() if k != "ids"}), "chars", len(GIB))
json.dump(res, open(os.path.join(OUT, "test.json"), "w"))
