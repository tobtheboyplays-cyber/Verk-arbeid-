"""Write the ElevenLabs section of sound-sources/LICENSES.md (between markers, so the older
Sonniss/CC0 build script's table above is left alone) and drop rows for files no longer shipped."""
import json, os, sys, datetime
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import apply as P  # noqa: E402
from targets import TARGETS  # noqa: E402

LIC = r"C:\Users\tobia\Hearthstead-Claude\sound-sources\LICENSES.md"
BEGIN = "<!-- ELEVENLABS:BEGIN -->"
END = "<!-- ELEVENLABS:END -->"


def main():
    snd, _ = P.load_json(P.SOUNDS_JSON)
    by_event = {t["event"]: t for t in TARGETS}
    picks = json.load(open(os.path.join(P.WORK, "picks.json"), encoding="utf-8"))
    rows = []
    for ev, v in snd.items():
        files = [s["name"] for s in v.get("sounds", []) if isinstance(s, dict) and s.get("name", "").startswith("hearthstead:el/")]
        for i, name in enumerate(files):
            rel = name[len("hearthstead:"):] + ".ogg"
            cand = picks.get(ev, {}).get("files", [])
            c = cand[i] if i < len(cand) else "?"
            meta_p = os.path.join(P.WORK, "raw", ev, c + ".json")
            prompt = json.load(open(meta_p, encoding="utf-8"))["prompt"] if os.path.exists(meta_p) else by_event.get(ev, {}).get("prompt", "")
            if ev.startswith("music."):
                src_, lic = "Eleven Music API (music_v1), instrumental", ("Eleven Music, Creator plan: commercial use, no attribution; "
                                                                        "NOT for film/TV/radio/'Studio Games' (see MUSIC-NOTES.md)")
                prompt = "see sound-gen/music.py TRACKS"
            elif ev.startswith("voice.line."):
                src_, lic = "ElevenLabs TTS (eleven_multilingual_v2), owner-approved library voice",                     "ElevenLabs Creator plan, commercial licence (owner's account)"
                prompt = "spoken line " + ev[len("voice.line."):]
            elif ev.startswith("voice."):
                src_, lic = "ElevenLabs Voice Design take, re-cut", "ElevenLabs Creator plan, commercial licence (owner's account)"
            else:
                src_, lic = f"ElevenLabs Sound Effects API (eleven_text_to_sound_v2), take {c}",                     "ElevenLabs Creator plan, commercial licence (owner's account)"
            rows.append(f"| `{rel}` | {ev} | {src_} | {prompt.replace('|', '/')} | {lic} |")
    today = datetime.date.today().isoformat()
    section = [BEGIN, "", "## ElevenLabs generated sounds (premium pass)", "",
               "- **ElevenLabs Sound Effects** (`POST /v1/sound-generation`, model `eleven_text_to_sound_v2`), generated with the "
               "owner's own API key under the owner's paid **Creator** plan, which includes a commercial licence for generated "
               "output. The mod ships the processed results (trimmed, EQ'd, loudness-normalised, mono Vorbis). "
               f"Verdict: **allowed**. Section written by `sound-gen/licenses.py` on {today}.",
               "- Rejected takes and raw 48 kHz outputs stay in `sound-gen-work/` outside the repo and are never shipped.",
               "- Preview reels used spoken labels from ElevenLabs TTS (premade voice \"George\"); those labels are not in the mod.",
               "- Music: Eleven Music (music_v1) under the Creator plan. Commercial use allowed with no attribution, EXCEPT "
               "film, TV, radio and 'Studio Games' (\"video games which are commercialised ... and made available ... through "
               "more than one platform\"). A free, non-monetised mod is outside that; if Bannerhold is ever monetised on more "
               "than one platform, the music needs an Enterprise music licence. Details in sound-gen/MUSIC-NOTES.md.",
               "- Villager 'hmm' voices (voice.npc.*, voice.trader.*) are NOT shipped files: sounds.json references Minecraft's "
               "own sounds by path (minecraft:mob/villager/...), nothing of Mojang's is in the jar.", "",
               "| Shipped file | Event | Source | Prompt | Licence |", "|---|---|---|---|---|"] + rows + \
              ["", f"Total ElevenLabs files shipped: {len(rows)}.", "", END]
    text = open(LIC, encoding="utf-8").read()
    if BEGIN in text:
        a, b = text.index(BEGIN), text.index(END) + len(END)
        text = text[:a] + "\n".join(section) + text[b:]
    else:
        text = text.rstrip("\n") + "\n\n" + "\n".join(section) + "\n"
    # rows of the older table whose file no longer ships are marked as replaced
    shipped = set()
    for v in snd.values():
        for s in v.get("sounds", []):
            n = s["name"] if isinstance(s, dict) else s
            if n.startswith("hearthstead:"):
                shipped.add(n[len("hearthstead:"):] + ".ogg")
    out = []
    for line in text.split("\n"):
        if line.startswith("| `") and "ElevenLabs" not in line and "~~" not in line:
            f = line.split("`")[1]
            if f.endswith(".ogg") and f not in shipped:
                line = line.replace(f"| `{f}` |", f"| ~~`{f}`~~ (replaced by ElevenLabs pass, no longer shipped) |", 1)
        out.append(line)
    with open(LIC, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(out))
    print(f"LICENSES.md: {len(rows)} ElevenLabs rows")


if __name__ == "__main__":
    main()
