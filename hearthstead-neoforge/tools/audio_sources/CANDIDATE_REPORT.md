# Hearthstead first-raid demo audio — candidate report

Date: 2026-08-27

Status: **CANDIDATE — offline QA passed; not approved until live Minecraft playback.**

## Scope

This pass rebuilds the audible grammar needed by the first-raid demo:

- interface: open, close, authoritative confirm, authoritative refusal;
- Hearth and settlement: founding, recruiting, assigning a profession;
- Lumberer: three chop variants, item pickup, sack stow, sack down, sack up;
- Farmer: three soil/work variants, seed press, crop pull;
- Courier: three carrying steps, crate grip, strain, creak, set-down, chest stow;
- Guard and raid: alert, two blade hits, two shield thuds, experience, leap slam,
  and two armour clinks.

There are 36 candidate renders. Every render is mono Vorbis at 44.1 kHz.

## Provenance and repeatability

Only selected CC0 source material from Kenney's `Impact Sounds 1.0` and `RPG
Audio` packs is used. The original archives, hashes, project URLs, local
license files, and local source directories are recorded in `manifest.json`.
Per-render source filenames and SHA-256 hashes are recorded in the generated
`build/reports/hearthstead/demo_audio_report.json`.

`tools/build_demo_audio.py` is the deterministic recipe. Each candidate is
made from at least two source layers and is shaped with EQ, pitch, compression,
reverb, and gain through Spotify Pedalboard 0.9.24. Pedalboard, NumPy, SciPy,
SoundFile, and FFmpeg are authoring/verification tools only; none is bundled in
the mod JAR.

No sound from Minecraft, TekTopia, MineColonies, or another mod is copied.

## Offline verification result

`python tools/build_demo_audio.py --verify` passes all 36 candidates. The gate
checks source presence, duration, mono/44.1 kHz format, peak level, RMS level,
leading silence, trailing silence, and EBU R128 integrated loudness when the
clip is long enough for a meaningful measurement.

Observed candidate range:

- duration: 0.10–3.00 seconds;
- sample peak: -14.26 to -8.29 dBFS;
- RMS: -38.35 to -22.40 dBFS;
- measurable integrated loudness: -34.0 to -24.3 LUFS;
- verifier verdict: 36 PASS, 0 FAIL.

`python tools/gen_sounds.py --verify` also passes every existing sound contract
affected by this pass. A clean JDK 21 `compileJava --rerun-tasks` completes
successfully. `processResources --rerun-tasks` also succeeds and copies all 36
candidate assets into the built resource tree. The compiler reports only
existing NeoForge deprecation warnings.

## Event timing contract

The Lumberer sounds follow authored physical contacts:

- sack down at `WORK_CONTAINER_DOWN` tick 20;
- pickup only after a successful physical transfer at `GROUND_ITEM_PICKUP`
  tick 12;
- stow only after a successful transfer into the sack at
  `WORK_CONTAINER_STOW` tick 12;
- sack up at `WORK_CONTAINER_UP` ground-break tick 12;
- no sound on retry, failed transfer, or animation-clear tick.

The UI events are deliberately separate from Minecraft's ordinary button
click. `UI_OPEN` and `UI_CLOSE` belong only on real screen transitions;
`UI_CONFIRM` and `UI_ERROR` belong only after an authoritative result. They
must never fire on hover or an attempted action whose result is still unknown.

Current call-site audit:

| Family | Runtime status before live gate |
|---|---|
| UI open/close/confirm/error | Registered and rendered; no screen/result call site yet |
| Hearth founded | Registered and rendered; no authoritative founding call site yet |
| Recruit and profession/emblem | Existing runtime call sites found |
| Lumber chop/sack/pickup/stow | Runtime call sites found, including the four contact-tick hooks above |
| Farmer | Existing work/contact call sites found |
| Courier | Existing carry/container call sites found |
| Guard alert/combat/experience | Existing runtime call sites found |

## Live approval gate

Offline waveform and codec checks cannot approve feel or mix. Before any sound
is marked approved, the actual Minecraft client must exercise each relevant
event and verify:

1. the sound lands on the visible contact frame;
2. it remains audible beside Minecraft ambience without becoming harsh;
3. repeated work loops do not become tiring or machine-gun-like;
4. confirm/refusal sounds never lie about a server result;
5. spatial sounds attenuate naturally at normal play distance;
6. the first-raid sequence has a clear alert → combat → experience hierarchy.

The four UI assets are registered but still require screen-owner integration
at genuine transition/result points. `HEARTH_FOUNDED` is likewise registered
and rendered but still needs one authoritative post-founding call site. English
and Norwegian subtitle entries
for `ui_open`, `ui_close`, `ui_confirm`, `ui_error`, `bag_down`, and `bag_up`
are present and JSON-valid. The global asset validator reaches 980/981 checks;
its only remaining failure is a separate set of non-audio English-to-Norwegian
UI parity keys owned by the language/UI pass. No sound event, sound asset, or
audio subtitle fails that gate.
