# Quality standard

## Binding owner standard — 16 September 2026

Every existing or new job/mechanic must meet every applicable requirement below
and improve the connected experience. These are a minimum, not an implementation
or certification claim. Older model-specific approval names and bilingual
requirements below are historical: current AGENTS controls review routing and
English-only player content. Do not weaken evidence or release gates.

1. Repair the existing connected six-role loop before adding professions/chains.
2. Premium means coherent, readable and verified behavior, motion, sound and UI.
3. A job has an obtainable start and a complete assignment-to-useful-output loop.
4. Work direction, hand/tool contact, animation accent, sound and physical commit agree; preserve the approved Lumber reference.
5. Sack work visibly follows put-down, stationary fill/unload, close/lift and carry; cargo has one physical owner.
6. Jobs have recognizable appearance/actions/sound, completion-based learning and a coherent work/meal/rest schedule.
7. Goods, food, ammunition and Coins are actual conserved inventory; interruption/reload cannot duplicate or erase them.
8. Mechanics create understandable choices through layout, staffing, reserves, tools, economy or defense, with acquisition, use and recovery.
9. Threats are readable and answerable. Theft moves actual recoverable goods; difficulty is not broken AI or hidden unavoidable punishment.
10. Full storage, absent tools, blocked paths and interruption produce actionable feedback and bounded recovery without developer commands.
11. Citizens visibly work, eat, rest and react; Tavern service and restrained atmosphere are physical and useful.
12. Original grounded medieval Minecraft presentation, readable truthful UI, English player text, and no unfinished placeholders in accepted scope.
13. Verify relevant save/restart, chunk lifecycle and simultaneous-player behavior; measure performance before making capacity claims.
14. Separate implemented, built, tested, observed and release-approved. Inspect images/motion, listen to audio, and retain actual gameplay evidence; automation cannot certify subjective fun.
15. Each task/agent has a concrete outcome, exact ownership and acceptance check. Preserve good implementations and user data; report unresolved limits honestly.

Use the accepted design in MECHANICS_DECISIONS_2026-09-07.md and the existing
quality ledger for evidence. New content must satisfy this same standard; no
independent weaker definition of done is allowed for an individual feature.

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
