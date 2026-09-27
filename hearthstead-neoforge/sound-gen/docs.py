"""Regenerate SOUNDS.md from targets.py (+ picks/apply state when present)."""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from targets import TARGETS, candidates_for  # noqa: E402

WORK = r"C:\Users\tobia\Hearthstead-Claude\sound-gen-work"
SOUNDS_JSON = os.path.join(os.path.dirname(HERE), "src", "main", "resources", "assets", "hearthstead", "sounds.json")

STYLE = """## Style guide: "made for Minecraft"

Minecraft sounds are short, dry, mono, a little lo-fi and very direct. The new set follows that, not a film mix.

- **Format:** mono OGG Vorbis (q4, about 128 kbit/s), 44.1 kHz. Generated as 48 kHz 16-bit PCM from ElevenLabs, resampled once.
- **Tails:** trimmed at -42 dB below peak, then capped per target (`maxlen`) with a 40–80 ms fade. One-shots end with the sound, not a room.
  Long sounds (horn, bells, fanfares, loops) keep a natural decay but never exceed the target length.
- **Transient first:** contact sounds (hits, hammer, chisel, chop) must peak in the first 120 ms, so WorkSoundSync contact ticks and combat
  impacts line up. Leading silence is trimmed to 4 ms.
- **Low end:** 40 Hz high-pass on everything (no sub rumble, no DC). **Top end:** a gentle low-pass per target (`lofi` 0.2–0.6 maps to
  14.6–11.8 kHz; 1.0 would be 9 kHz). Stingers and chimes keep more air than foley.
- **Loudness:** measured as "padded LUFS" (BS.1770 integrated loudness with 0.5 s of silence either side, so short clips are measured
  fairly), true-peak ceiling -1 dBTP with a soft limiter. Vanilla references measured the same way from the 1.21.1 asset index (not
  redistributed):

  | Vanilla reference | Padded LUFS | Length |
  |---|---:|---:|
  | player attack strong / sweep | -20.6 / -16.4 | 0.18 / 0.23 s |
  | shield block | -12.9 | 0.31 s |
  | dig stone / dig wood | -28.5 / -26.3 | 0.5 s |
  | anvil use / anvil land | -11.3 / -10.5 | 1.25 / 0.75 s |
  | villager yes | -18.8 | 0.73 s |
  | random levelup / xp orb | -16.8 / -18.9 | 1.75 / 0.75 s |
  | ui button click | -26.5 | 0.28 s |
  | raid horn | -17.2 | 6.9 s |
  | ravager roar | -9.7 | 2.8 s |
  | bell use | -31.3 | 2.5 s |

  File targets by class: combat hits -16 to -19; swings -18/-19; brute roar/slam -14; voices -16 to -19; stingers -15 to -19;
  UI clicks -22 to -24; chimes -17 to -21; work contact -20 to -25; tavern foley -21 to -23; ambience loops -22 to -30.
- **In-game level:** the `volume` in sounds.json is recomputed so each replaced event keeps the *effective* loudness
  (file LUFS + volume dB) it had before. The earlier pass balanced those levels by ear against vanilla, so the mix does not jump.
  New events use volume 1.0 and rely on the file target and the call-site volume.
- **Variants:** 2–6 per event; 4–6 for anything repeated constantly (swings, hits, hammer, chop). Picked to be mutually different
  (spectral-envelope correlation < 0.995 or a length difference > 50 ms) so Minecraft's random pick never sounds like a loop.
- **Attenuation:** replaced events keep their old `attenuation_distance`; new ones use the value in the table (default 16).
- **Prompts:** every prompt names one physical action, the material, and ends with "short video game sound effect, dry, close,
  no reverb, no music". Words are avoided in voices (wordless barks/cheers only) so nothing needs localising.
"""

PIPE = """## Pipeline

1. `python gen.py --cat <cat> --pri 1` → candidates (PCM 48 kHz) in `sound-gen-work/raw/<event>/` (outside the repo) and the
   credit ledger in `LEDGER.md`. The key is read only from `secrets/elevenlabs.key` inside the script and sent only to
   api.elevenlabs.io. Hard stop at 110,000 credits.
2. `python process.py --cat <cat>` → trim, fade, high-pass, lo-fi EQ, loudness-normalise, score (raw clipping, length fit, burst
   count, transient position, noise floor, hiss), pick the best N distinct takes, write processed OGGs to `sound-gen-work/proc/`
   and a waveform + spectrogram sheet to `sound-gen-work/sheets/<event>.png` for the human pass.
   `--override event=c03,c01` pins a manual choice after listening/looking.
3. `python apply.py --cat <cat>` → copies picks to `assets/hearthstead/sounds/el/<folder>/`, rewrites only those events' blocks in
   sounds.json, adds subtitles; `--prune` moves unreferenced old files out of the mod to `sound-gen-work/replaced/`.
4. `python reel.py` → `videos/audio/elevenlabs-preview.mp3` (+ `.txt` cue sheet): old, then new, per event, grouped by category.
5. `python docs.py` → this file.

Rejected candidates never leave `sound-gen-work/`.
"""


def main():
    picks = {}
    p = os.path.join(WORK, "picks.json")
    if os.path.exists(p):
        with open(p, encoding="utf-8") as fh:
            picks = json.load(fh)
    applied = {}
    if os.path.exists(SOUNDS_JSON):
        with open(SOUNDS_JSON, encoding="utf-8") as fh:
            sj = json.load(fh)
        for ev, v in sj.items():
            applied[ev] = any("hearthstead:el/" in (s["name"] if isinstance(s, dict) else s) for s in v.get("sounds", []))
    lines = ["# Bannerhold sound targets (ElevenLabs premium pass)", "",
             "Generated by `sound-gen/docs.py` from `sound-gen/targets.py`. Event ids are in the `hearthstead:` namespace.",
             "Source column: *old* = the earlier Sonniss/Kenney/CC0 edit, *vanilla* = a Minecraft sound stood in, *none* = silent today.",
             "Status: **shipped** = ElevenLabs files are live in sounds.json; *picked* = processed, not yet applied; blank = not generated.", "",
             STYLE, PIPE, "## Targets", ""]
    cats = []
    for t in TARGETS:
        if t["cat"] not in cats:
            cats.append(t["cat"])
    total_files = total_gens = 0
    for c in cats:
        lines += [f"### {c}", "", "| P | Event | Where it plays | Current source | Var | Dur (s) | LUFS | Status | Prompt |",
                  "|---|---|---|---|---:|---:|---:|---|---|"]
        for t in [x for x in TARGETS if x["cat"] == c]:
            ev = t["event"]
            st = "**shipped**" if applied.get(ev) else ("*picked*" if picks.get(ev, {}).get("files") else "")
            name = f"`{ev}`" + (" (new)" if t.get("new") else "")
            lines.append(f"| P{t['pri']} | {name} | {t['hook']} | {t['cur']} | {t['n']} | {t['maxlen'] or t['dur']} | "
                         f"{t['lufs']} | {st} | {t['prompt'].replace('|', '/')} |")
            total_files += t["n"]; total_gens += len(candidates_for(t))
        lines.append("")
    lines += ["## Kept as they are", "",
              "- **Goblin** (`goblin_*`): the owner likes them; frozen, never regenerated.",
              "- `settler_hm`, `innkeeper_hum`, `yawn`, `settler_panic`: recorded voice performances, already good.",
              "- Carry grammar (`haul_step`, `crate_*`, `bag_*`, `item_pickup`, `chest_stow`), `ladder_creak`, `settler_eat`, "
              "`banner_*`, `tree_*`, `cart_roll`, `fisher_whistle`: fine as they are for now (P3 if budget remains).",
              "- Vanilla bow/crossbow shots, doors/gates, chest open/close: the right sound already.", "",
              f"Plan: {len(TARGETS)} targets, {total_files} shipped files, about {total_gens} generations."]
    with open(os.path.join(HERE, "SOUNDS.md"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    print("SOUNDS.md written")


if __name__ == "__main__":
    main()
