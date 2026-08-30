# Hearthstead QA Protocol

PROTOCOL_VERSION: 1.3.0

The canonical QA source of truth for the Hearthstead mod
(`hearthstead-neoforge/`). Every testing, debugging, verification, or
completion claim MUST flow through `tools/hearthstead-qa`. A plain green
Gradle build is never sufficient proof of anything.

## Approval terminology

- **Candidate** is the highest status that compilation, unit tests, asset
  validation, GameTests, dedicated-server tests, Xvfb playtests, static
  renders, offline audio inspection, or deterministic geometry checks may
  award.
- **Approved** requires the exact candidate JAR to run in the native Minecraft
  client with ordinary player-equivalent input. The expected visible result,
  server-authoritative state, native audio, frame-time window, and persistence
  evidence must all be present where the release matrix requires them. A
  render-thread acknowledgement must bind the current native QA session to
  Windows, the exact runtime JAR hash and installed-mod resolution source,
  the exact expected CurseForge game directory, installed-JAR path, integrated
  save directory, runtime epoch, display profile and selected language. Raw
  paths stay in the sealed manifest; the log carries privacy-safe SHA-256 path
  tokens. Frame-report ACKs must exactly equal the hashed native client-log
  roster, and selected windows must be the newest matching profile/state
  across the helper-sealed launch-segment sequence. In integrated singleplayer,
  client ACKs and genuine Server-thread authority events are ranges in those
  same immutable `latest.log` segments; a relabelled or fabricated separate
  dedicated-server log is not evidence. Consumed-marker, ACK, source-log and
  seal times all bind to the same recent run interval; manifest/report
  attestations alone are not runtime proof. Manifest schema v3 additionally
  seals a complete hash-chained `logs/native-input.jsonl` created by the
  foreground-only Windows `SendInput` helper and a separately hash-chained
  launch registry. Approval requires exact HWND/PID, kernel process-creation
  time, full Java executable path, runtime JAR/world/log-segment identity,
  ordinary movement/look/UI/click/settlement-name action classes, atomic
  Shift-right-click, and input on distinct one-to-one PIDs across the required
  client relaunch. Each input batch must prove the same foreground target
  before and after delivery; generic hotkeys, clipboard/paste and command-like
  text do not exist. The transcript and launch registry must also match one
  HMAC-authenticated operator seal outside the run and a separately posted
  pre-input `HSQA_INPUT_PRECOMMIT_V1` line. HMAC is not OS immutability:
  approval therefore requires the external key plus exact externally posted
  key SHA-256, session nonce, installed-JAR SHA-256 and world id. Any missing
  or mismatched component fails closed.
- Approval is per matrix row. One missing, unrun, stale, ambiguous, or failed
  required row keeps the complete demo unapproved.
- Only a passing `tools/hearthstead-qa release-client-gate <run>` may promote
  the complete demo from Candidate to Approved. A written claim, screenshot,
  video without authority proof, or any other green suite cannot substitute
  for that gate.

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
INV-11 Lumberers and Farmers work only inside a player-confirmed, persisted,
       bounded two-corner Work Zone. Preview/Cancel never mutate; invalid,
       stale, cross-settlement or unloaded requests never force-load or
       mutate; no-zone fallback is visibly labelled and remains idle.

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
| blessing restart | `tools/hearthstead-qa blessing-restart` | physical settler/plaque Blessing ranks and sticky quarantine survive a real chunk unload plus two clean full-process restarts; the same real viewer reconnects after each restart |
| performance | `tools/hearthstead-qa performance` | exact 1/25/50/100-settler matrix on an isolated dedicated server; scoreboard population proof, median/average MSPT via `/tick query`, absolute gates |
| client      | `tools/hearthstead-qa client`   | real client boots under Xvfb (software GL) |
| playtest    | `tools/hearthstead-qa playtest` | scripted client+server session: join proven server-side, all 4 input classes (cmd/key/click/look), screenshots validated (AC-3), mod content in-world (AC-4) |
| visual      | `tools/hearthstead-qa visual`   | screenshots captured and inspected |
| full        | `tools/hearthstead-qa full`     | all of the above + manifest |
| gate        | `tools/hearthstead-qa gate`     | freshness + completeness check (fast, no MC launch) |
| release-client-gate | `tools/hearthstead-qa release-client-gate <run>` | fail-closed proof that every native in-game approval row is fresh and matches the frozen source, exact expected profile/JAR/save, consumed marker, immutable integrated-client launch segments, native session and newest cross-restart client windows |
| live        | `tools/hearthstead-qa live <start\|status\|shot\|key\|hold\|type\|cmd\|scmd\|click\|look\|film\|stop>` | a persistent, drivable session across separate invocations (HARNESS-6) — not part of `full`, driven by hand or by an agent |
| reap        | `tools/hearthstead-qa reap [check\|dry-run\|selftest\|reap]` | unconditional teardown of anything the harness might have leaked; never touches the Gradle daemon |
| provision   | `tools/hearthstead-qa provision` | rebuilds the shared NeoForge install from scratch and proves it with a passing `playtest` (AC-10) |
| negative    | `tools/hearthstead-qa negative [n1\|n2\|n3\|n4\|all]` | drives the four required negative tests (port held, client build broken, server never `Done(`, client up but no join) through the real controller and asserts on the real failure message |

### Native integrated-log restart chain

Use `qa/scripts/native_release_session.py create-marker` for the strict V2
one-shot marker; its production CLI is pinned to the exact CurseForge profile
and refuses replacement or environment/marker ambiguity. Before every native
client stop or relaunch, run its `seal-log` command against the release-client
run directory. `latest.log` rotates at relaunch, so a segment not sealed first
is irrecoverable evidence. Each new segment keeps the same native session,
gets a monotonically numbered immutable filename, and atomically extends
`logs/native-log-segments.json`. The final manifest seals that index hash.
The same run must drive the client through
`qa/scripts/windows_native_input.py`; its output transcript is canonical at
`logs/native-input.jsonl`. Before first input, publish the exact line produced
by `native_release_session.py input-precommit`; after every launch, append its
live PID/process-creation/full-path/runtime/world/log-segment identity with
`register-launch`; after final input and log sealing, append the external HMAC
record with `seal-input` using the same published 64-hex session nonce. The
operator key and append-only seal ledger must remain outside the run. The
canonical `release-client-gate` invocation requires both paths and repeats the
externally posted key SHA-256, nonce, installed-JAR SHA-256 and world id as
explicit arguments. The release validator independently recomputes the input
and launch full sequence/hash chains and rejects an omitted, shortened,
hand-edited, non-Minecraft-targeted or single-launch trace, even if mutable
manifest hashes were recomputed.

The validator independently reparses every segment, consumed-marker epoch,
ACK identity/timestamp/profile, runtime JAR/save token, and exact integrated
`Server thread/INFO` Hearthstead authority range. Each authority locator must
resolve to exactly one fixed-schema `HEARTHSTEAD_AUTHORITY_V1` record with an
exact event, result, and target; all 15 ordered fields, canonical values, item
conservation, and no-mutation rejection semantics are checked fail-closed.
Every sealed segment must be
referenced as client-log evidence, the restart row must bind distinct pre/post
client and Server-thread ranges, and a selected frame window must remain the
newest matching state across all later segments. Do not extract, synthesise,
rename or maintain a fake separate server log for the integrated-client run.

Single-suite commands (everything except `full`) never write
`qa/reports/latest.json` or clear `qa/reports/.stale` — only a full-scope
`full` run may. This is deliberate (see Gate integrity below): running
`doctor` or `dedicated` alone must never turn a red gate green.

The normal `gametest` and `behavior` verdict is pinned to exactly one
authoritative `minecraft/GameTestServer` summary for **600 required tests**,
zero authoritative failure summaries, and Gradle exit status zero. Both
suites explicitly remove any ambient `HSQA_GAMETEST_NAMESPACE`; the separate
active-performance namespace can therefore never shrink a normal/full run to
four tests.

### Isolation (D-H2)

`dedicated`, `blessing-restart`, `performance`, `playtest`, and `live` each get their own
NeoForge server instance and port, materialised fresh per run by
`qa/scripts/server_instance.sh` from one shared, idempotently-cached install
(`qa/scripts/server_install.sh`) — so they never contend for a world or a
port, and a leaked one can't poison the others. Ports: dedicated 25571,
performance 25572, playtest 25573, live 25574, blessing-restart 25576.

### Physical Blessing restart proof

`blessing-restart` creates one named world exactly once and reuses that exact
directory for an initial setup boot and two subsequent clean restarts. Every
server stop must finish through the real `stop` command, exit its process and
release its port before the next boot. A persisted random scoreboard token,
the settlement UUID, the building UUID, both target-entity UUIDs and the
offline viewer UUID jointly prove that a replacement world or target cannot
silently satisfy a later pass.

The initial real client physically inserts the house Build Plan, holds each
Blessing Seal and sends Shift-right-click to reach Hearthward II on one
settler and Thorned Roads III on the registered plaque's building. Synthetic
input counts only when server state changes exactly and the matching physical
item count falls by one. A second settler is fed one malformed target ledger
through vanilla's entity data path; its real decoder must expose an empty,
inert quarantine before any reload.

Before the first process restart, the viewer moves to the Nether, the target
chunk loses its force-load, and `execute unless loaded` must prove it actually
unloaded. The chunk is then reloaded and all entity state is checked again.
After that pass and both full restarts, a real viewer joins, server-authority
checks require the exact entity ranks/quarantine and plaque identity, and
`save-all flush` precedes a fresh snapshot of
`hearthstead_settlements.dat`. The snapshot's mtime must advance on every
pass. A stdlib NBT reader independent of the mod decoder requires root schema
v4, stable settlement/building identity, the exact persisted first Founding
Journey task, and exactly one active Thorned Roads rank III on the building.
Logs, raw snapshots, normalized JSON, mtime/SHA-256 metadata and a complete
world archive are preserved; the deterministic world must remain under the
fixed 64 MiB artifact ceiling. Every wait is bounded, and the suite's wall
time is carried into the full-run manifest note.

### Performance scale matrix

`performance` measures **1, 25, 50, and 100 real settler entities** in four
separately delimited windows inside its one fresh, port-isolated server. The
first is a surviving member of the real three-settler founding flow. Added
settlers copy that member's settlement binding and make a dimension round-trip
so the normal entity-join path registers them in the settlement's UUID-keyed
records before measurement; unbound `/summon` shells are not accepted as the
population under test.

The three growth batches must emit exact transit and return proofs for
24, 25, and 50 entities. At every scale, `hearthstead info` must independently
report a settlement UUID-record population equal to 1/25/50/100 before the
scoreboard proof and sample window. This prevents a matrix from passing on 100
tagged entities while the manager still owns only the original record.

Every scale receives a short warm-up, then two scoreboard read-backs prove both
the total settler population and the bound-member population are **exactly**
the requested scale. A condition-gated population marker must follow those
read-backs. Only `/tick query` values between that scale's begin/end markers
belong to it; the parser requires the configured sample count with no missing
or borrowed values. `performance-matrix.json` records raw samples, population
evidence line numbers, median, average, budget, and verdict for every scale.
The suite first builds the current fingerprinted source, requires the exact
versioned JAR, and verifies that the isolated instance copy has the same
SHA-256. A stale pre-existing JAR cannot receive a current-source verdict.

The absolute gates apply to **average MSPT**: 25 settlers preserves the legacy
45 ms ceiling, 50 settlers must be at or below 45 ms, and 100 settlers must be
at or below 50 ms. The 1-settler row is a reporting baseline. Median is always
reported but does not replace the average gate. Scale and budgets are fixed in
the parser and cannot be weakened through environment variables.

The default profile is deliberately bounded for `full`: 6 seconds of warm-up
and five samples two seconds apart per scale. A deliberate longer profile can
set `HSQA_PERF_WARMUP_SECONDS`, `HSQA_PERF_SAMPLE_COUNT`, and
`HSQA_PERF_SAMPLE_INTERVAL_SECONDS` (plus the wall-clock timeout when needed)
without changing proof semantics or thresholds. The four stages are cumulative
within one server, so they share world/JIT history; this short matrix is an
absolute regression gate, not the separate 15/30-minute baseline-vs-enabled
soak and profiler evidence required for a final performance study.

After those four dedicated-server windows have been parsed, `performance`
runs a second, isolated GameTest namespace for **active permanent Blessings**.
It creates exactly 1, 25, 50, and 100 bound settlers plus the same number of
UUID-sealed authorized raid participants, split across real 1-9-participant
raid ledgers (1/3/6/12 settlements). Every row must prove a permanent personal
rank, exact settlement record population, and a valid blessed-building zone
backed by one in-arena, survivable physical plaque with a fitted matching plan,
LINKED_VALID block-entity identity and registered/green blockstate. It also
proves both damage directions, personal Torneveier snare, stable modifier
object identity after the first snare pass, exact spatial-index lookup/rebuild
bounds, one rejected unsealed control, and bounded teardown. The parser
requires exactly one one-test batch for each named
`active_blessing_scale_001/025/050/100:0` case and rejects rogue batches.
`active-blessing-performance-matrix.json` is the fail-closed machine record;
missing, duplicate, extra or miscounted 1/25/50/100 evidence fails the suite.
Its nanosecond duration is informational only. The established dedicated
average-MSPT thresholds and `performance-matrix.json` semantics above are not
changed or replaced by GameTest fixture timing.

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

Fingerprint = SHA-256 over a NUL-delimited, path-safe sorted `sha256sum` of
every file in `hearthstead-neoforge/{src,tools,gradle/wrapper}` plus
`build.gradle`, `gradle.properties`, `settings.gradle`, `gradlew` and
`gradlew.bat`, plus `docs/ANIMATION_CATALOGUE.md` because the animation checker
parses it as an assertion contract; `qa/{hooks,scripts,scenarios}`,
`qa/PROTOCOL.md`, `qa/RELEASE_CLIENT_GATE.md`,
`qa/release_client_matrix.json`, the controller
`tools/hearthstead-qa`, and the repository `.gitattributes`. Any change →
previous manifests STALE. The path-safe implementation is self-tested under a
directory and filename containing spaces, and any missing input propagates as
a hard error instead of hashing an incomplete list.

`qa/hooks`, `qa/scripts` and `qa/scenarios` are in it because they decide what every suite
ASSERTS, not merely how it runs. Leaving them out meant an assertion could be
loosened while every stored green went on looking current — INV-10 with the
lock removed, and the same self-satisfying shape as a check that reads a log
it wrote itself, one level up. It was not hypothetical: a commit changed
`live.sh`'s and `lib_harness.sh`'s verdict logic while all 36 stored manifests
carried on reporting the same fingerprint.

`qa/reports/**` is deliberately OUT — every run writes into it, so including it
would change the fingerprint on every run and it would never settle. So are
`__pycache__` directories, which a generator rewrites without anything real
having changed. The Gradle wrapper is in because it builds the supposedly
fingerprinted JAR; `.gitattributes` is in because its LF policy determines
whether the cross-platform judge can execute at all.

`fingerprint()` in `tools/hearthstead-qa` and `hsqa_fingerprint()` in
`qa/scripts/lib_harness.sh` both delegate to the single fail-closed
`qa/scripts/source_fingerprint.sh`; the controller still asserts equal results
before every command. If either caller drifts, a manifest's fingerprint stops
being comparable to `latest.json`'s, which is the whole reason manifests record it. The stale
marker `qa/reports/.stale` is set by the post-edit hook and cleared only by
a green `full` run.
