# Hearthstead QA Protocol

PROTOCOL_VERSION: 1.0.0

The canonical QA source of truth for the Hearthstead mod
(`hearthstead-neoforge/`). Every testing, debugging, verification, or
completion claim MUST flow through `tools/hearthstead-qa`. A plain green
Gradle build is never sufficient proof of anything.

## Permanent behavioral invariants

INV-1  Settlers never construct buildings autonomously. They repair and
       upgrade player-built structures only.
INV-2  Building REGISTRATION is automatic room detection (RoomScanner +
       BuildingManager) — the TekTopia model the owner confirmed: scan the
       room, and if it meets the requirements it works. Nothing may gate
       registration behind a player-placed marker.
       The Building Plaque (reinstated by the owner's spec, superseding the
       earlier removal) is an ACCESS POINT on top of that, never a second
       source of truth: it links to an existing building, and it manages
       resident occupancy through the authoritative building/citizen API. It
       must never maintain its own building registry or resident list.
INV-3  Every item is physically real: chest/warehouse contents are the truth;
       no item may be created or destroyed by logistics logic (conservation).
INV-4  All world scans and per-tick work are budgeted (bounded visits per
       scan, cooldowns between scans).
INV-5  Settlers are spawned only through SettlementManager; records are
       UUID-keyed and idempotent (no duplication).
INV-6  Server code never loads client-only classes.
INV-7  Needs drive behavior; a critical need with a reachable solution must
       not be ignored indefinitely.
INV-8  Guards protect civilians first (threatened civilian > post > nearest
       enemy).
INV-9  Persistence is loss-free: settlement, buildings, settlers, needs,
       professions, claimed beds survive save/reload and server restart.
INV-10 Tests are never deleted, skipped, loosened, or timeout-inflated to
       obtain green. Expectation changes require a recorded specification
       correction in the quality ledger.

## Suites

| Suite       | Command                         | What it proves |
|-------------|--------------------------------|----------------|
| doctor      | `tools/hearthstead-qa doctor`   | toolchain + env sanity |
| assets      | (part of full; `validate`)      | every resource cross-referenced, 1.21 layout |
| animation   | (part of full; `animation`)     | keyframe integrity, loop closure, sync contracts |
| build       | (part of full)                  | clean compile + jar assembly |
| gametest    | `tools/hearthstead-qa gametest` | all GameTest arenas headless |
| behavior    | `tools/hearthstead-qa behavior` | gametests with decision tracing + trace analysis (thrash/stuck/starvation detectors) |
| dedicated   | `tools/hearthstead-qa dedicated`| real NeoForge server: boot, found settlement via console, restart persistence, no client classloading |
| performance | `tools/hearthstead-qa performance` | 30 settlers on dedicated server, MSPT budget via /tick query |
| client      | `tools/hearthstead-qa client`   | real client boots under Xvfb (software GL) |
| visual      | `tools/hearthstead-qa visual`   | screenshots captured and inspected |
| full        | `tools/hearthstead-qa full`     | all of the above + manifest |
| gate        | `tools/hearthstead-qa gate`     | freshness + completeness check (fast, no MC launch) |

## Suite routing by changed path (machine-readable)

```routing
hearthstead-neoforge/src/main/java/com/hearthstead/entity/**      -> behavior gametest dedicated
hearthstead-neoforge/src/main/java/com/hearthstead/settlement/**  -> behavior gametest dedicated
hearthstead-neoforge/src/main/java/com/hearthstead/block/**       -> gametest dedicated
hearthstead-neoforge/src/main/java/com/hearthstead/menu/**        -> gametest client visual
hearthstead-neoforge/src/main/java/com/hearthstead/client/**      -> build client visual animation
hearthstead-neoforge/src/main/java/com/hearthstead/network/**     -> gametest dedicated
hearthstead-neoforge/src/main/java/com/hearthstead/command/**     -> gametest dedicated
hearthstead-neoforge/src/main/java/com/hearthstead/event/**       -> behavior gametest dedicated
hearthstead-neoforge/src/main/java/com/hearthstead/registry/**    -> full
hearthstead-neoforge/src/main/resources/assets/**                 -> assets animation client visual
hearthstead-neoforge/src/main/resources/data/**                   -> assets gametest
hearthstead-neoforge/src/main/resources/META-INF/**               -> full
hearthstead-neoforge/build.gradle                                 -> full
hearthstead-neoforge/gradle.properties                            -> full
hearthstead-neoforge/tools/**                                     -> assets animation
hearthstead-neoforge/src/main/java/com/hearthstead/gametest/**    -> gametest
qa/**                                                             -> full
```

## Completion criteria

A task may be reported complete only when ALL of the following hold:

1. `tools/hearthstead-qa full` PASSED for the exact current source
   fingerprint (the gate recomputes it; reports cannot be edited into
   validity).
2. The manifest lists every suite required by the changed files, each PASS —
   or BLOCKED with a documented environmental blocker (failing command,
   error evidence, resume checklist).
3. No critical/high failure fingerprints are open.
4. Two consecutive clean critical-path runs exist with zero intervening
   source changes (`green_streak >= 2` in the manifest chain).
5. The quality ledger (`hearthstead-neoforge/docs/HEARTHSTEAD_QUALITY_LEDGER.md`)
   is updated with evidence for every touched requirement.

## Behavior decision traces

When `behavior` runs, the mod records (system property
`hearthstead.qa.trace` set by the controller) one JSONL line per settler per
20 ticks: tick, uuid, name, activity, profession, pos, navDone, navTarget,
hunger, energy, morale, sleeping, bagCount. Location:
`hearthstead-neoforge/run/gametest/hearthstead-trace.jsonl`.

`qa/scripts/analyze_trace.py` fails the suite on: activity thrashing (>6
flips/200 ticks with no position change), navigation stuck (nav active, no
movement >200 ticks), starvation ignored (hunger <15 for >600 ticks while
food existed), teleport anomalies. Every failure gets a failure-id
`FB-<hash>`; `tools/hearthstead-qa reproduce FB-<hash>` re-runs the suite
that produced it (world seed recorded in the manifest).

## Freshness

Fingerprint = SHA-256 over the sorted `sha256sum` of every file in:
`hearthstead-neoforge/{src,tools,build.gradle,gradle.properties,settings.gradle}`
and `qa/PROTOCOL.md`. Any change → previous manifests STALE. The stale
marker `qa/reports/.stale` is set by the post-edit hook and cleared only by
a green `full` run.
