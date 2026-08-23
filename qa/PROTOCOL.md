# Hearthstead QA Protocol

PROTOCOL_VERSION: 1.0.0

The canonical QA source of truth for the Hearthstead mod
(`hearthstead-neoforge/`). Every testing, debugging, verification, or
completion claim MUST flow through `tools/hearthstead-qa`. A plain green
Gradle build is never sufficient proof of anything.

## Permanent behavioral invariants

INV-1  Settlers never construct buildings autonomously. They repair and
       upgrade player-built structures only.
INV-2  The plaque is the surveyor. A building exists because a player hung a
       plaque and the room around it satisfied that plaque's requirements —
       the TekTopia model the owner confirmed: scan the room, and if it meets
       the requirements it works. No plaque, no building; and no inserted
       Build Plan means no plaque UI (DECISIONS D-005, D-006).
       The plaque remains an ACCESS POINT, never a second source of truth: it
       stores its type, state, revision and a building id, and reads
       everything else from the settlement. It must never maintain its own
       building registry or resident list.
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
| playtest    | `tools/hearthstead-qa playtest` | scripted client+server session: join proven server-side, all 4 input classes (cmd/key/click/look), screenshots validated (AC-3), mod content in-world (AC-4) |
| visual      | `tools/hearthstead-qa visual`   | screenshots captured and inspected |
| full        | `tools/hearthstead-qa full`     | all of the above + manifest |
| gate        | `tools/hearthstead-qa gate`     | freshness + completeness check (fast, no MC launch) |
| live        | `tools/hearthstead-qa live <start\|status\|shot\|key\|hold\|type\|cmd\|scmd\|click\|look\|film\|stop>` | a persistent, drivable session across separate invocations (HARNESS-6) — not part of `full`, driven by hand or by an agent |
| reap        | `tools/hearthstead-qa reap [check\|dry-run\|selftest\|reap]` | unconditional teardown of anything the harness might have leaked; never touches the Gradle daemon |
| provision   | `tools/hearthstead-qa provision` | rebuilds the shared NeoForge install from scratch and proves it with a passing `playtest` (AC-10) |
| negative    | `tools/hearthstead-qa negative [n1\|n2\|n3\|n4\|all]` | drives the four required negative tests (port held, client build broken, server never `Done(`, client up but no join) through the real controller and asserts on the real failure message |

Single-suite commands (everything except `full`) never write
`qa/reports/latest.json` or clear `qa/reports/.stale` — only a full-scope
`full` run may. This is deliberate (see Gate integrity below): running
`doctor` or `dedicated` alone must never turn a red gate green.

### Isolation (D-H2)

`dedicated`, `performance`, `playtest`, and `live` each get their own
NeoForge server instance and port, materialised fresh per run by
`qa/scripts/server_instance.sh` from one shared, idempotently-cached install
(`qa/scripts/server_install.sh`) — so they never contend for a world or a
port, and a leaked one can't poison the others. Ports: dedicated 25571,
performance 25572, playtest 25573, live 25574.

### No live streaming (D-H5)

Like sound, live video streaming is unavailable in this environment (no
`x11vnc` or equivalent). `live status`/`live shot` refresh a still PNG at a
stable path instead, and `live film` records a short clip plus a labelled
contact sheet — that is the closest this environment gets to "watching it
happen," and it is enough to judge motion (HARNESS-5) even though it isn't
a live feed.

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

`changed` additionally recognises `qa/scenarios/**` and `qa/scripts/{playtest,live,check_screenshot,pixel_diff,build_contact_sheet}*` as
`-> playtest` on top of `full` (they change what the in-game session actually
asserts, so re-running it is the direct feedback, even though `qa/**` still
means the fingerprint invalidates and a completion claim ultimately needs
`full`).

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

### Gate integrity

`doctor`, `dedicated`, `client`, and every other single-suite command are
useful for fast iteration, but NONE of them may turn the gate green —
only a full-scope `full` run writes `qa/reports/latest.json` or clears
`qa/reports/.stale`, and `green_streak` increments only on a full-scope
PASS. Running ten single suites in a row, all green, still leaves `gate`
reporting "no full-scope run exists yet" if `full` has never run since the
last change. This is deliberate: a single suite proves that one thing
works, not that the gate is green.

## Evidence store (D-H3)

One canonical store: `qa/reports/artifacts/<scenario-id>/<TS>/`, each with
`manifest.json`, `result.json`, `reproduction.md`, `logs/`, `shots/` (and
`film/` for scenarios that record motion) — always all five, pass or fail.
A repo-root symlink `artifacts/qa -> qa/reports/artifacts` makes that path
resolve literally for anything written against the older contract wording.
Some suites (`build`, `assets`, `animation`, `gametest`, `behavior`,
`doctor`) still use the older flat `qa/reports/artifacts/<TS>/` layout from
before this slice — both shapes coexist, and any reader (`visual`,
`reproduce`) globs both rather than assuming one.

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
