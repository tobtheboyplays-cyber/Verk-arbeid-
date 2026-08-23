# Quality standard

The bar, and the lifecycle a slice climbs to reach it.

## Lifecycle

    SPEC_READY → IMPLEMENTED → BUILD_GREEN → RUNTIME_PROVEN
               → OPUS_APPROVED → LOCKED

Only **LOCKED** means finished. Nothing else may be reported as done.

- Compilation is not completion.
- A green test is not visual quality.
- A working runtime is not sound architecture.

## Definition of done — every item, no exceptions

A slice is LOCKED only when all of these hold:

1. Every in-scope requirement (`REQ-*`) is satisfied.
2. Every acceptance criterion (`AC-*`) has stored evidence.
3. The build passes.
4. Relevant automated tests pass, through `tools/hearthstead-qa`.
5. Minecraft QA passes **twice from separate clean launches**. FLAKY is not
   PASS.
6. Persistence is proven where relevant (save, restart, chunk unload).
7. Multiplayer/server-authority is proven where relevant.
8. Invalid states degrade safely — no crash, no corruption.
9. Required assets exist and resolve (models, textures, blockstates, loot,
   recipes).
10. Player-facing text is English, keyed, and present in every language file
    the validator enforces parity across.
11. No new unexplained ERROR in client or server logs; WARN triaged.
12. No critical or high-severity Opus finding remains.
13. No TODO, FIXME, placeholder, dead control, fake counter, mock success or
    unconnected UI inside the completed scope.
14. Performance measured where the slice could affect it.
15. `docs/project/` updated.
16. A fresh `opus-quality-gate` returns **PASS**.

## Evidence rules

- A screenshot nobody opened is not evidence. If a UI or art claim rests on an
  image, that image must have been **looked at**, and the reviewer must be
  able to open it too.
- A client-side observation is not proof of server state. Check the
  authoritative record.
- One green run does not establish stability where flakiness is plausible.
  Repeat-run it and report the ratio. This project has already shipped a suite
  that passed 1 run in 5 while looking green on any single run.
- "Flake" is not a root cause.

## Never, to reach green

Delete or skip a failing test; weaken an assertion; inflate a timeout without
diagnosing; silence an exception; disable the harness; or edit production code
from inside QA. A genuine specification conflict is recorded as a
specification correction in the quality ledger — it is never resolved by
quietly lowering the bar.

## Visual and UX bar

Standardised shots: fixed world, position, angle, FOV, time, weather,
resolution and GUI scale, so two screenshots are comparable.

Review for composition, spacing, alignment, hierarchy, material consistency,
readability, contrast, icon consistency, texture resolution, animation
restraint, z-fighting, clipping, text overflow, long localisation strings, and
the loading / empty / disabled / error / permission states.

**Colour is never the only carrier of status** — pair every colour with a word
or an icon. UI data must be live and authoritative: no dead controls, no fake
counters, no placeholder portraits in a completed slice.

Visual language: carved oak, forged iron, aged brass, parchment, leather;
restrained ornamentation; warm believable materials; readability underneath
the decoration. Avoid grey developer panels, neon fantasy, visual noise, flat
placeholder icons, baked-in text, and decoration that hides information.

## Performance

Baseline before changing anything performance-sensitive. Measure client FPS,
server MSPT, memory, entity count, pathfinding load, packet volume, save/load
duration. Scale-test at 1 / 25 / 50 / 100 settlers and with many plaques.
Compare against this hardware's own baseline, not invented absolutes. Explain
and get approval for any significant regression.

Avoid per-tick global scans, unchanged-state resync, repeated allocation,
unbounded collections, cooldown-free pathfinding, and UI polling where events
exist.

## Human gate

Automation cannot prove that the mod feels alive, intuitive or premium. After
a milestone, mark `HUMAN_PLAYTEST_RECOMMENDED` with a short checklist:
clarity, responsiveness, fun, settler believability, animation quality, sound
quality, UI readability, visual consistency, and friction after repeated use.
Never claim subjective premium feel was proven by automation.
