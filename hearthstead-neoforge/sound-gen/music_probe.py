"""ONE music test track (owner question: can the Creator plan make the soundtrack?)."""
import os, sys, time, json, datetime
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen
OUT = os.path.join(gen.WORK, "music")
s = gen._session()
u0 = gen._used(s)
prompt = ("Calm medieval folk village morning, in the spirit of Minecraft's gentle soundtrack: solo lute and soft harp, "
          "a quiet wooden recorder melody, light warm strings, no drums, sparse and peaceful, instrumental")
t = time.time()
r = s.post(f"{gen.API}/v1/music", params={"output_format": "mp3_44100_128"},
           json={"prompt": prompt, "music_length_ms": 35000, "model_id": "music_v1", "force_instrumental": True}, timeout=600)
print("status", r.status_code, "secs", round(time.time() - t, 1))
if not r.ok:
    print(r.text[:400]); sys.exit(1)
open(os.path.join(OUT, "probe_village_morning.mp3"), "wb").write(r.content)
hdr = {k: v for k, v in r.headers.items() if any(w in k.lower() for w in ("cost", "char", "credit", "song", "request"))}
print("headers", hdr)
time.sleep(20)
u1 = gen._used(s)
print("account before", u0, "after", u1)
json.dump({"prompt": prompt, "ms": 35000, "before": u0, "after": u1, "headers": hdr}, open(os.path.join(OUT, "probe.json"), "w"))
