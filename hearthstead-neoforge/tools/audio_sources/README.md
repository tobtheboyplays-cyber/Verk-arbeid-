# Hearthstead demo-audio provenance

The files under `cc0_raw/` are selected, unmodified source recordings from
the two Kenney packs recorded in `manifest.json`. Both archives declare the
Creative Commons Zero 1.0 dedication in their included license files.

`tools/build_demo_audio.py` is the authoritative recipe. Each finished sound
is assembled from two or more physical foley layers, then pitch-shaped,
filtered, compressed, and peak-normalized with Spotify Pedalboard. The source
filenames remain visible in the recipe so every shipped OGG can be traced back
to its raw inputs. No Minecraft, TekTopia, MineColonies, or other mod audio is
used.

Spotify Pedalboard and the other Python packages in
`tools/audio_requirements.txt` are workstation-only dependencies. They are
not copied into the resources directory or bundled in the mod JAR. FFmpeg is
used only by the optional BS.1770/EBU R128 verification pass and is likewise
not distributed with the mod.

Build and verify:

```text
python tools/build_demo_audio.py
python tools/build_demo_audio.py --verify
```

The verifier rejects missing sources, wrong duration, clipping, excessive
leading/trailing silence, non-mono output, or out-of-family loudness. It also
writes `build/reports/hearthstead/demo_audio_report.json` for QA handoff.

