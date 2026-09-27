"""Pick, master and install the Bannerhold soundtrack (stereo Vorbis, loudness-matched to vanilla music).

Vanilla 1.21.1 game music measures -17 to -20 LUFS (a_familiar_room -18.0, ancestry -18.2,
bromeliad -16.9, an_ordinary_day -20.1), so tracks go to -18 LUFS, stingers to -17.
The tavern jig gets a 2 s equal-power crossfade of its tail into its head so it loops seamlessly.
"""
import json, os, subprocess, sys
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import audiolib as A  # noqa: E402
import apply as P  # noqa: E402

M = os.path.join(P.WORK, "music")
PICK = {  # chosen from the spectrogram pass: steady takes without dropouts or holes
    "day_meadow": "t1", "day_harvest": "t1", "day_hearth": "t2", "day_banner": "t1",
    "night_embers": "t1", "night_watch": "t2", "tavern_jig": "t1", "raid": "t1",
    "raid_victory": "t2", "raid_defeat": "t1", "title": "t1",
}
EVENTS = {  # sound event -> tracks
    "music.village_day": ["day_meadow", "day_harvest", "day_hearth", "day_banner"],
    "music.village_night": ["night_embers", "night_watch"],
    "music.tavern": ["tavern_jig"],
    "music.raid": ["raid"],
    "music.raid_victory": ["raid_victory"],
    "music.raid_defeat": ["raid_defeat"],
    "music.title": ["title"],
}
SUBS = {"music.raid_victory": "Victory music plays", "music.raid_defeat": "Mournful music plays"}


def decode_stereo(p):
    r = subprocess.run(["ffmpeg", "-v", "error", "-i", p, "-ac", "2", "-ar", str(A.SR), "-f", "f32le", "-"],
                       capture_output=True, check=True)
    return np.frombuffer(r.stdout, np.float32).astype(np.float64).reshape(-1, 2)


def lufs_st(x):
    import pyloudnorm as pyln
    return pyln.Meter(A.SR).integrated_loudness(x)


def master(x, target, loop=False):
    if loop:
        n = int(2.0 * A.SR)
        head, body, tail = x[:n], x[n:-n], x[-n:]
        r = np.linspace(0, 1, n)[:, None]
        x = np.concatenate([body, tail * np.sqrt(1 - r) + head * np.sqrt(r)])
    else:
        e = A.env_db(x.mean(axis=1), 0.05)
        on = np.where(e > e.max() - 60)[0]
        x = x[: int((on[-1] + 1) * 0.05 * A.SR) + int(0.3 * A.SR)]
        k = int(0.02 * A.SR); x[:k] *= np.linspace(0, 1, k)[:, None]
        k = int(1.5 * A.SR); x[-k:] *= (np.linspace(1, 0, k) ** 2)[:, None]
    x = x * 10 ** ((target - lufs_st(x)) / 20)
    c = 10 ** (-1.0 / 20)
    return c * np.tanh(x / c)


def encode(x, path):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(A.SR), "-ac", "2", "-i", "-",
                    "-c:a", "libvorbis", "-q:a", "2", path], input=x.astype(np.float32).tobytes(), check=True)


def main():
    dst = os.path.join(P.ASSETS, "sounds", "el", "music")
    os.makedirs(dst, exist_ok=True)
    for name, take in PICK.items():
        x = decode_stereo(os.path.join(M, f"{name}_{take}.mp3"))
        stinger = name in ("raid_victory", "raid_defeat")
        y = master(x, -17.0 if stinger else -18.0, loop=(name == "tavern_jig"))
        encode(y, os.path.join(dst, name + ".ogg"))
        print(f"{name}: {len(y) / A.SR:.0f} s, {os.path.getsize(os.path.join(dst, name + '.ogg')) / 1e6:.2f} MB")
    snd, crlf = P.load_json(P.SOUNDS_JSON)
    for ev, tracks in EVENTS.items():
        e = {"sounds": [{"name": f"hearthstead:el/music/{t}", "stream": not ev.endswith(("victory", "defeat")),
                         "volume": 1.0} for t in tracks]}
        if ev in SUBS:
            e["subtitle"] = f"subtitles.hearthstead.{ev}"
        snd[ev] = e
    P.dump_sounds(snd, crlf, list(EVENTS))
    P.apply_lang({f"subtitles.hearthstead.{k}": v for k, v in SUBS.items()})
    total = sum(os.path.getsize(os.path.join(dst, f)) for f in os.listdir(dst)) / 1e6
    print(f"soundtrack installed: {total:.1f} MB")


if __name__ == "__main__":
    main()
