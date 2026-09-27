"""Voices v3 (owner decision): villager sounds, a bit altered, for people; dark grunts for brutes.

- voice.npc.<ambient|yes|no|trade|celebrate>     -> vanilla villager files, referenced by path
- voice.trader.<ambient|yes|no|trade|celebrate>  -> vanilla wandering-trader files, referenced by path
  (nothing of Mojang's is copied into the jar; sounds.json only points at minecraft: sound paths)
  sounds.json volume puts every id at about -27 LUFS when played at volume 1.0.
- voice.brute.grunt -> our own ElevenLabs brute takes, re-cut darker: pitched down 12 %,
  2.5 kHz low-pass, <= 0.45 s, -26 LUFS.
The ElevenLabs babble banks leave the jar (moved to sound-gen-work/unused).
"""
import json, math, os, shutil, sys
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import apply as P  # noqa: E402
import audiolib as A  # noqa: E402
import babble as B  # noqa: E402

TARGET = -27.0
SETS = {
    "npc": {"ambient": "entity.villager.ambient", "yes": "entity.villager.yes", "no": "entity.villager.no",
            "trade": "entity.villager.trade", "celebrate": "entity.villager.celebrate"},
    "trader": {"ambient": "entity.wandering_trader.ambient", "yes": "entity.wandering_trader.yes",
               "no": "entity.wandering_trader.no", "trade": "entity.wandering_trader.trade",
               "celebrate": "entity.wandering_trader.yes"},
}
SUB = {"npc": {"ambient": "Villager mumbles", "yes": "Villager agrees", "no": "Villager disagrees",
               "trade": "Villager trades", "celebrate": "Villager cheers"},
       "trader": {"ambient": "Trader mumbles", "yes": "Trader agrees", "no": "Trader disagrees",
                  "trade": "Trader trades", "celebrate": "Trader cheers"}}


def brute_grunts(n=5):
    bank = "brute"
    d = os.path.join(B.DIR, bank)
    pick = B.pick_preview(bank)
    x = A.hp(A.decode(os.path.join(d, pick["file"])), 40)
    segs = B.segments(x)
    t_end = segs[-1][1]
    head = [sg for sg in segs if sg[1] < 0.5 * t_end]
    # emote islands (laugh/hmm/angry/oh/mmhm) are the grunt material; add a few low babble syllables
    pieces = [(a, b) for a, b in head if 0.15 <= b - a <= 1.2]
    from scipy import signal as sg
    out = []
    for a, b in pieces:
        y = x[max(0, int((a - 0.005) * A.SR)):int(min(b, a + 0.55) * A.SR)]
        y = A.signal.resample_poly(y, 25, 22)                   # 0.88 pitch: darker and heavier
        y = sg.sosfilt(sg.butter(4, 2500, "lowpass", fs=A.SR, output="sos"), y)
        y = A.fade(A.cap_length(y, 0.45, 0.12), 0.006, 0.12)
        out.append((A.spectral_centroid(y), y))
    out.sort(key=lambda p: p[0])                                # darkest first
    return [A.normalize(y, -26.0) for _, y in out[:n]]


def main():
    snd, crlf = P.load_json(P.SOUNDS_JSON)
    with open(P.LANG, encoding="utf-8") as fh:
        lang = json.load(fh)
    # 1) retire the ElevenLabs babble banks
    old_events = [k for k in snd if k.startswith("voice.")]
    for k in old_events:
        del snd[k]
    src = os.path.join(P.ASSETS, "sounds", "el", "voice")      # only the per-bank sub-folders are babble
    for bank in B.BANKS:
        if os.path.isdir(os.path.join(src, bank)):
            dst = os.path.join(P.WORK, "unused", "voice_babble_v2", bank)
            if os.path.isdir(dst):
                shutil.rmtree(dst)
            shutil.move(os.path.join(src, bank), dst)
    # 2) villager / trader sets, referenced by vanilla path
    subs = {}
    for set_, tones in SETS.items():
        for tone, vev in tones.items():
            entries = P.vanilla_event_entries(vev)
            eff = P.effective_db(entries)
            scale = 10 ** ((TARGET - eff) / 20)
            sounds = []
            for e in entries:
                v = round(min(1.0, e.get("volume", 1.0) * scale), 2)
                sounds.append({"name": e["name"], "volume": v, "attenuation_distance": 12})
            key = f"subtitles.hearthstead.voice.{set_}.{tone}"
            snd[f"voice.{set_}.{tone}"] = {"sounds": sounds, "subtitle": key}
            subs[key] = SUB[set_][tone]
    # 3) brute grunts, our own files
    gdir = os.path.join(P.ASSETS, "sounds", "el", "voice_brute")
    os.makedirs(gdir, exist_ok=True)
    grunts = brute_grunts()
    for i, y in enumerate(grunts, 1):
        A.encode_ogg(y, os.path.join(gdir, f"grunt{i}.ogg"))
    snd["voice.brute.grunt"] = {"sounds": [{"name": f"hearthstead:el/voice_brute/grunt{i}", "volume": 1.0,
                                            "attenuation_distance": 16} for i in range(1, len(grunts) + 1)],
                                "subtitle": "subtitles.hearthstead.voice.brute.grunt"}
    subs["subtitles.hearthstead.voice.brute.grunt"] = "Brute grunts"
    # rewrite sounds.json: the old voice.* blocks go, the new ones are appended
    P.dump_sounds_full(snd, crlf, removed=old_events)
    # lang: drop the retired voice subtitle lines, add the new ones
    retired = [k for k in lang if k.startswith("subtitles.hearthstead.voice.") and k not in subs]
    P.remove_lang(retired)
    n = P.apply_lang(subs)
    print(f"retired {len(old_events)} babble events + {len(retired)} subtitles; "
          f"added {len(SETS['npc']) + len(SETS['trader'])} villager-voice events, {len(grunts)} brute grunts, {n} subtitles")


if __name__ == "__main__":
    main()
