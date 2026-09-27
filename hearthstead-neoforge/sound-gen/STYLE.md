# Bannerhold sound style: how a Minecraft sound is made

Research pass, 26 Sep 2026, before the full ElevenLabs run. Measurements come from `vanilla_study.py` (vanilla 1.21.1
assets from the local asset index; analysis only, nothing redistributed) and `prompt_test.py` / `prompt_eval.py`
(16 test generations). The raw numbers are in `sound-gen-work/vanilla_study.txt`.

## 1. What vanilla actually is

| Group | Examples measured | Length | Attack | Decay to -30 dB | Padded LUFS | Brightness (centroid) |
|---|---|---|---|---|---|---|
| Block hits/breaks | wood/stone hit and break, dig | 0.5 s files, ~0.15 s of sound | 0–10 ms | 0.05–0.15 s | -26 to -33 | 280–400 Hz (dark, thuddy) |
| Villager work (the trades) | farmer, fletcher, mason, cleric, fisherman, shepherd, toolsmith, weaponsmith, leatherworker | 0.6–1.1 s | 0–40 ms (some are 2–3 gestures, attack 0.3 s) | 0.1–0.5 s | -19 to -29 | 400 Hz–5.7 kHz |
| Metal/tool | anvil use/land, smithing table, chain | 0.5–1.25 s | 0–40 ms | 0.1–0.5 s | -10 to -21 | 3.3–9.5 kHz (bright, hot) |
| Combat | attack strong/sweep/crit, shield block | 0.2–0.56 s | ≤70 ms | 0.06–0.17 s | -13 to -18 | 530–690 Hz |
| Voices | villager/pillager hurt, death, ambient, celebrate | 0.25–1.1 s | 20–130 ms | 0.15–0.5 s | -15 to -18 | ~1 kHz |
| UI / reward | button click, level up, xp orb, item pickup | 0.17–1.75 s | 0 ms | 0.04–1.0 s | -17 to -24 | 1–5.4 kHz |
| Big cues | raid horn, ravager roar, bell | 2.5–6.9 s | up to 1 s swell | 0.6–3.2 s | -10 to -31 | 300–1350 Hz |
| Ambience | campfire crackle, rain, cave | 2–7 s | slow | long | -21 to -44 | 600–2600 Hz |

Findings:

- **Everything is mono**, 44.1 or 48 kHz (the fishing splash is 11 kHz!). Only the button click and toast are stereo.
- **The sound is over fast.** One-shot foley decays 30 dB within 0.05–0.3 s. Files are often padded to 0.5 s, but the
  audible part is 0.1–0.3 s. Nothing has a room tail.
- **Mastered hot, not clean.** Anvil, zombie-wood-break, sweep and shield block peak at or above 0 dBFS with visible flat tops.
  A little clipping *is* part of the vanilla character; heavy distortion is not.
- **Tone is honest, not uniformly lo-fi.** Wood and stone are very dark (centroid ~300 Hz); metal is bright (5–9 kHz).
  The "lo-fi" feel comes from short, simple, single-gesture recordings and Vorbis at ~q4, not from filtering everything dull.
- **Variants, not length.** Common events have 3–7 files (strong attack 6, sweep 7, block hits 6, bundle insert 9, cave 23).
- **Pitch randomisation lives in code, not in sounds.json.** Only a handful of vanilla entries set `pitch` (bell 0.93–1.0,
  mason 0.92, bundle 0.95); mobs get `(rand-rand)*0.2+1.0` from `getVoicePitch`, blocks play at 0.8. Our call sites already
  jitter (WorkSoundSync `PITCH_SPREAD`, guard moves `+0..0.1`), so our sounds.json keeps `pitch` at 1.0.
- **`volume` in sounds.json is used to balance files** (raid horn 0.01 in file-space because the code plays it at 64 blocks,
  shepherd 0.5, librarian 2.0 which is clamped to 1). `attenuation_distance` is rarely set (ravager 35); the default 16 is normal.
- **Subtitles** are `subtitles.<type>.<thing>.<action>` with short present-tense text: "Villager works", "Block breaks",
  "Anvil used". Ours follow the same shape ("Blade swings", "Mugs clink").

## 2. How Mojang and the community approach it

- Mojang's audio team (Samuel Åberg, Sandra Karlsson, Tom Koselnik Olovsson; see the "how Minecraft's sounds are made" feature
  and the Minecraft Dungeons audio interview on A Sound Effect) records **simple real foley** (flower pots smashed with an
  axe, everyday objects, the audio director's own voice processed for mobs). The advice is to not overthink it: record, add a
  little processing. Sounds describe **weight and material** literally; music carries the emotion.
- They avoid realism that would feel harsh: no gore, no screaming, voices abstracted. Charm over detail.
- C418-era rules still hold in resource-pack circles: **short, organic, single gesture, many small variants**, keep the
  transient, no reverb (the game has none, so baked-in rooms sound wrong outdoors), mono so positional audio works.
- sounds.json fields that matter to us: `volume` (0–1, clamped), `pitch`, `weight` (duplicate-free way to favour a take),
  `stream` (only for long music/loops so they are not loaded fully), `preload`, `attenuation_distance` (default 16),
  `type: "event"` (alias another event). Mono is required for 3D attenuation: stereo files play non-positional.

## 3. ElevenLabs prompting: what won

Tested four styles on an anvil hit and an axe chop, two takes each, 0.6 s, measured against vanilla:

| Style | Example | Result |
|---|---|---|
| A. Plain sentence | "A blacksmith's hammer striking a steel anvil once" | long tails (dec30 0.3–0.4 s), one take was a low wobble, not a chop |
| **B. "Single X" + game words** | "Single hammer hit on steel anvil, short video game sound effect, dry, close, no reverb, no music" | **tight (0.05–0.2 s), clean transient, vanilla-like** |
| **C. Foley technical** | "Foley, hammer hit on steel anvil, one single hit, close mic, dry, mono, tight fast decay, no room, no music" | **tight and clean; slightly quieter takes** |
| D. "Like Minecraft" | "Simple blocky video game sound like Minecraft: …, slightly lo-fi, crunchy" | worst: late attacks (0.2–0.4 s), noisy beds (-32 dB floor), double hits |

Rules adopted (B+C merged):

1. Open with **"A single <tool/material> <action> <target>"**, then 1–3 concrete material words ("solid woody thock").
2. End every foley prompt with: **"close mic, dry, tight fast decay, no reverb, no music, short video game sound effect"**.
3. **Never** say "Minecraft" or "lo-fi": the model answers with noise and mush. Get the grain in post.
4. `duration_seconds` = the minimum that fits (0.5 s for hits, 0.6–1.0 s for gestures; the API floor is 0.5 s). Cost is
   40 credits per requested second, so short is also cheap.
5. `prompt_influence` 0.55 for foley (0.4 for every third take to get variety), 0.45–0.5 for music/voice stingers.
6. Words that make the model add a metal ring ("sword", "blade") must be avoided when the wanted sound is a body hit.
   "Blade hit into a padded leather jacket, dull thwack" gives meat; "sword … no clang" still rings.
7. "Punchy" makes hot, lightly clipped takes. That is fine for hits (vanilla does it), wrong for chimes.
8. Output: request `pcm_48000`. It arrives as interleaved **stereo** s16le; we fold to mono.
9. Raw output level swings from -12 to -51 LUFS between takes, so loudness normalisation is mandatory.

## 4. The processing chain (process.py follows this)

1. Decode, fold to mono, resample to 44.1 kHz.
2. 40 Hz high-pass (no DC, no sub rumble).
3. Tone: high-shelf cut at 8 kHz of 0 to -6 dB and a soft low-pass from 18 kHz down to 12 kHz (`lofi` 0–1 per target;
   0.3 default, 0.2 for metal and bells, 0.4–0.6 for UI and heartbeat). No bit-crushing: audible crush reads as cheap.
4. Impacts only: +2 dB soft tanh drive for density (vanilla hits are hot).
5. Trim: leading/trailing silence at -42 dB re peak; for impacts the start is moved onto the transient (-24 dB, minus 3 ms)
   so WorkSoundSync's contact tick lands on the hit.
6. Length cap per target (`maxlen`) with a 50–80 ms squared fade-out. Fade-in 2 ms.
7. Normalise to the target padded LUFS; true-peak ceiling -1 dBTP through a soft limiter.
8. Encode mono Vorbis q4, 44.1 kHz.
9. Loops (ambience): 250 ms equal-power crossfade of the tail into the head, no trim.

## 5. Targets per category

| Category | File LUFS (padded) | Max length | Variants | Notes |
|---|---|---|---|---|
| Swings | -18 / -19 | 0.45–0.6 s | 4 | no impact in the swing |
| Hits, blocks, bash | -16 to -18 | 0.45–0.6 s | 4–5 | mix meaty and edged takes |
| Heavy/brute | -14 to -15 | 0.7–1.0 s | 3 | |
| Work contact (hammer, chisel, chop, pick) | -20 to -22 | 0.35–0.55 s | 5–6 | transient at 0 ms |
| Work gestures (saw, plane, stir, scrape) | -20 to -24 | 0.6–1.0 s | 3–4 | |
| Small foley (seed, feather, bag) | -24 to -25 | 0.35–0.5 s | 3 | |
| Voices (barks, hurt, cheers) | -16 to -19 | 0.45–1.4 s | 3–4 | wordless |
| UI clicks / pages | -22 to -24 | 0.2–0.45 s | 2–3 | |
| Rewards / chimes / stingers | -17 to -21 | 1.0–2.5 s | 2–3 | skill level-up stays short and airy |
| Big cues (horn, bell, fanfare) | -14 to -16 | 3–5 s | 2–3 | natural decay kept |
| Ambience loops | -22 to -30 | 8–12 s | 1–2 | `stream: true` above 8 s |

In game the sounds.json `volume` is then set so each **replaced** event keeps the effective loudness (file LUFS + volume dB)
it had before; new events use volume 1.0. `pitch` stays 1.0 (code jitters). `attenuation_distance` is kept from the old entry
(16 default, 24–64 for horns, bells and roars).

## 6. Picking

Automatic score (process.py): flat-top clipping (tolerant for hits), length fit, burst count, transient position, noise
floor under one-shots, decay time for impacts, hiss. Then the best N that are mutually different. Then a human pass on the
waveform + spectrogram sheet (`sound-gen-work/sheets/<event>.png`) with `--override` where the ear/eye disagrees
(e.g. pilot `blade_hit`: two meaty thwacks + three edged hits instead of five ringing ones).

Sources: [A Sound Effect: making the sound of Minecraft Dungeons](https://www.asoundeffect.com/minecraft-dungeons-game-audio/),
[Samuel Åberg (Minecraft Wiki)](https://minecraft.wiki/w/Samuel_%C3%85berg),
[GoNintendo: how Minecraft's sound effects are made](https://www.gonintendo.com/contents/38665-learn-how-minecraft-s-sound-effects-are-made-in-new-video),
[ElevenLabs sound effects docs](https://elevenlabs.io/docs/capabilities/sound-effects),
[ElevenLabs sound generation API](https://elevenlabs.io/docs/api-reference/text-to-sound-effects/convert).
