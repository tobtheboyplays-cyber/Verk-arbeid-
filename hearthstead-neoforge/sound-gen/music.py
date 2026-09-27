"""Bannerhold soundtrack via Eleven Music (owner approved 26 Sep after the probe).

Two takes per track, instrumental, music_v1, mp3_44100_128 (Creator: no 'high quality' downloads).
Ledger: 900 credits per generated minute (ElevenLabs' published subscription rate; the probe
measured ~825). Hard stop at the 62-minute monthly music generation cap and at 110k ledger.

    python music.py --dry
    python music.py            # generates whatever is missing
"""
import argparse, datetime, json, os, sys, time
from concurrent.futures import ThreadPoolExecutor
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gen  # noqa: E402

OUT = os.path.join(gen.WORK, "music")
CREDITS_PER_MIN = 900
MUSIC_CAP_MIN = 62.0
STYLE = ("medieval folk in the calm, sparse spirit of Minecraft's soundtrack, period instruments, "
         "gentle and warm, no vocals, instrumental")
TRACKS = {
    "day_meadow": (150, "Village day, morning in the fields: solo lute arpeggios, a wooden recorder melody, soft harp, "
                        "light warm strings, no drums, peaceful and hopeful, " + STYLE),
    "day_hearth": (150, "Village day, quiet afternoon: plucked harp and hammered dulcimer, a slow viol line, "
                        "sparse and contemplative, long gentle pauses, " + STYLE),
    "day_harvest": (140, "Village day, harvest: cheerful but calm lute and recorder duet, a soft frame drum pulse, "
                         "light and pastoral, " + STYLE),
    "day_banner": (150, "Village day, a proud small settlement: slow noble theme on lute and low strings, "
                        "a distant soft horn, warm and quietly heroic, " + STYLE),
    "night_embers": (150, "Night in the village, embers and stars: very soft harp and a low drone, a lonely recorder, "
                          "slow, dreamy, lots of space, " + STYLE),
    "night_watch": (140, "Night watch on the palisade: quiet low strings and a soft lute, a little mysterious, "
                         "calm but alert, very sparse, " + STYLE),
    "tavern_jig": (90, "Lively tavern jig for a medieval inn: fiddle, lute, tin whistle and a frame drum, "
                       "cheerful dance in 6/8, loopable with the ending leading back to the start, " + STYLE),
    "raid": (120, "A raid on the village: urgent medieval battle music, war drums, low brass horns, driving strings, "
                  "tense and heroic but not orchestral-epic, " + STYLE),
    "raid_victory": (10, "A short medieval victory fanfare: two natural trumpets and a drum roll, bright, ends cleanly, " + STYLE),
    "raid_defeat": (20, "A short sombre aftermath sting after a lost battle: slow low strings and a distant tolling bell, "
                        "mournful, ends softly, " + STYLE),
    "title": (150, "Title theme for Bannerhold, a medieval settlement game: begins with a lone lute, a warm recorder "
                   "melody joins, then soft strings and a gentle frame drum, proud and inviting, " + STYLE),
}
TAKES = 2


def existing():
    return {f[:-4] for f in os.listdir(OUT) if f.endswith(".mp3")} if os.path.isdir(OUT) else set()


def jobs():
    have = existing()
    return [(name, k) for name in TRACKS for k in range(1, TAKES + 1) if f"{name}_t{k}" not in have]


def used_minutes(led):
    return sum(e.get("dur", 0) for e in led["entries"] if e["event"].startswith("music.")) / 60.0 + 35 / 60.0


def generate(s, led, name, take):
    secs, prompt = TRACKS[name]
    r = s.post(f"{gen.API}/v1/music", params={"output_format": "mp3_44100_128"},
               json={"prompt": prompt, "music_length_ms": int(secs * 1000), "model_id": "music_v1",
                     "force_instrumental": True}, timeout=900)
    if not r.ok:
        print("  !", name, take, r.status_code, r.text[:200], flush=True)
        return
    path = os.path.join(OUT, f"{name}_t{take}.mp3")
    with open(path, "wb") as fh:
        fh.write(r.content)
    cost = int(round(CREDITS_PER_MIN * secs / 60.0))
    with gen.LOCK:
        led["total"] += cost; led["calls"] += 1
        led["entries"].append({"time": datetime.datetime.now().strftime("%m-%d %H:%M:%S"), "event": f"music.{name}",
                               "cand": take, "dur": secs, "credits": cost})
        gen._save_ledger(led)
        meta = json.load(open(os.path.join(OUT, "tracks.json"))) if os.path.exists(os.path.join(OUT, "tracks.json")) else {}
        meta[f"{name}_t{take}"] = {"prompt": prompt, "seconds": secs, "song_id": r.headers.get("song-id")}
        json.dump(meta, open(os.path.join(OUT, "tracks.json"), "w"), indent=1)
    print(f"  {name} t{take} {secs}s -> {cost} (ledger {led['total']:,})", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry", action="store_true")
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    led = gen._load_ledger()
    todo = jobs()
    mins = sum(TRACKS[n][0] for n, _ in todo) / 60.0
    cost = int(mins * CREDITS_PER_MIN)
    print(f"{len(todo)} takes, {mins:.1f} min, ~{cost:,} credits; music minutes used so far {used_minutes(led):.1f}")
    if used_minutes(led) + mins > MUSIC_CAP_MIN or led["total"] + cost > gen.HARD_CAP:
        raise SystemExit("STOP: would pass the 62-min music cap or the 110k ledger cap")
    if a.dry:
        return
    s = gen._session()
    with ThreadPoolExecutor(2) as ex:   # Creator allows 2 concurrent requests
        list(ex.map(lambda j: generate(s, led, *j), todo))
    print(f"done. ledger {led['total']:,}")


if __name__ == "__main__":
    main()
