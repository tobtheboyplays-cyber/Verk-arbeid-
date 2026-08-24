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

### Judging motion (D-H6)

`film` judges motion on the **loudest tile** of a 16x9 grid laid over each
inter-frame difference (`subject_mad`), not on the whole-frame average
(`median_mad`, still reported). A whole-frame average is dominated by the
pixels that never change, so a settler three blocks from a static camera —
unmistakably walking to any human looking at the contact sheet — averaged out
to 0.34 against a threshold of 2.0. Combined with the pan being opt-in, that
made `motion_ok` unable to report motion at all, which is the same
unfalsifiability as a forced pan, mirrored.

Both directions are proven against one framing and stored side by side:
settlers walking in a pen score 19.79 and PASS; the identical shot with the
server `tick freeze`d scores 0.19 and FAILS. A camera pan still passes — every
tile is loud — so the measure widens what can be detected without loosening
what counts as motion. Reporting both figures keeps a pan (the two converge)
distinguishable from subject motion (they do not).

**What `motion_ok` establishes, exactly.** That the capture is live and that
something in the world part of the frame is animating. It does **not**
attribute that motion to a subject, and no claim that a particular settler
animated may rest on it alone — that needs the contact sheet read by a human.
An unrelated entity carries the number just as well: a 5s clip of an
apparently empty world scored 3.03, and the loudest tile was a distant iron
golem walking, not noise.

The HUD band (the bottom two grid rows: chat backlog, hotbar, the held item
bobbing) is excluded from the measurement, because all three change while the
world stands still. Measured: in the tick-frozen control — where nothing in
the world *could* move — one frame pair scored 19.13 in a bottom-row tile,
which was the `[Server: The game is frozen]` chat line fading out, the same
magnitude as three walking settlers. It failed only because the median over
pairs happened to land below the threshold, i.e. by luck; a session where chat
ticks over once a second would have passed with a completely frozen subject.
The cost of excluding it is that a subject framed low in the shot is not
measured, so frame the subject centrally for animation review.

Each take gets its own directory (`film/take-NN[-label]/`, label via
`HSQA_FILM_LABEL`): proving a claim that needs a passing take AND a failing
control must not destroy the first when the second runs.

### A session that is up must be a session that RUNS (D-H7)

`live start` does not stop at proving a join. A joined player is not a running
world: a player killed while the session sits unattended leaves the client on
the death screen, and a dead player stops holding the surrounding chunks at
full ticking — block entities near them stop ticking too, so a hearth placed
afterwards never founds its settlement and nothing moves, while `live status`
still reports the session up. Anything judged from that state is judged from a
frozen world.

So `start` also sets a deterministic observation state (peaceful, creative,
day, clear, no mob spawning) and asserts the player is **alive** (`Health > 0`)
rather than merely connected.

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

A scenario that ends for a reason of its own — `live stop` tearing a session
down — records `overall` derived from its own checks, never a hard-coded
terminal status: "it stopped" says nothing about whether it went well, and a
literal status made a session carrying a FAILED check read exactly like a
clean one.

`negative` (scenario `negative`), `reap check`/`dry-run`/`reap` (scenario
`reap`) and `provision` (scenario `provision`) all write into this same
store too — each invocation's transcript and per-item verdict (N1..N4 for
`negative`; the check/dry-run/reap transcript for `reap`; reinstall +
verify-playtest for `provision`) land under their own `<TS>/`, not only in
`/tmp` (which does not survive a container restart).

Every scenario manifest additionally records `fingerprint` and `dirty_hash`
computed the same way the controller computes them for `latest.json` — so a
piece of evidence can be checked directly against what source state actually
produced it, instead of trusting only the manifest's own `git_commit` (the
last commit, not the working tree).

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
`hearthstead-neoforge/{src,tools,build.gradle,gradle.properties,settings.gradle}`,
`qa/PROTOCOL.md`, **`qa/scripts/**` and `qa/scenarios/**`**. Any change →
previous manifests STALE.

`qa/scripts` and `qa/scenarios` are in it because they decide what every suite
ASSERTS, not merely how it runs. Leaving them out meant an assertion could be
loosened while every stored green went on looking current — INV-10 with the
lock removed, and the same self-satisfying shape as a check that reads a log
it wrote itself, one level up. It was not hypothetical: a commit changed
`live.sh`'s and `lib_harness.sh`'s verdict logic while all 36 stored manifests
carried on reporting the same fingerprint.

`qa/reports/**` is deliberately OUT — every run writes into it, so including it
would change the fingerprint on every run and it would never settle. So are
`__pycache__` directories, which a generator rewrites without anything real
having changed.

The computation exists twice — `fingerprint()` in `tools/hearthstead-qa` and
`hsqa_fingerprint()` in `qa/scripts/lib_harness.sh` — and the two MUST stay
byte-for-byte equivalent. If they drift, a manifest's fingerprint stops being
comparable to `latest.json`'s, which is the whole reason manifests record it. The stale
marker `qa/reports/.stale` is set by the post-edit hook and cleared only by
a green `full` run.
