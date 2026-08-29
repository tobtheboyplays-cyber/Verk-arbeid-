# Hearthstead Quality Ledger

Living quality register per the continuous-completion directive. A requirement
is only PASS with concrete, reproducible evidence. Green-streak rule: two full
review rounds with zero changes required, else streak resets.

**Active phase: A1 — Foundation port** (NeoForge 1.21.1).
Identified from DESIGN.md roadmap: A1 = port of the verified 1.20.1 prototype
core + room detection + homes + modular settler visuals. Logistics (A2) and
raids (A3) are OUT OF SCOPE for this gate — but the ported prototype systems
(settlement, settlers, AI, professions v1, hearth UI) are shared foundations
and therefore IN scope.

**Product decision log:**
- **SPECIFICATION CORRECTION (2026-08-24, owner-sourced).** The earlier
  "PLAQUE SYSTEM REMOVED — do not reinstate" directive is **superseded**. The
  owner reinstated the Building Plaque by written spec, then refined it in
  answers recorded as `docs/project/DECISIONS.md` D-005 and D-006:
  **the plaque is the surveyor** — a room is only detected because a plaque
  was hung, so no plaque means no building; and a plaque with no inserted
  Build Plan opens no UI. The plaque remains an ACCESS POINT and must never
  hold its own building registry or resident list.
  Recorded here because `CLAUDE.md`'s rule is that specification conflicts are
  resolved by a written correction in this ledger, not by editing an invariant
  quietly. `CLAUDE.md` and `qa/PROTOCOL.md` INV-2 were reconciled to match;
  this entry is the missing third piece.
- Loop directive: no completion claims until 2 consecutive green rounds.
- **Room detection = TekTopia model (user, this session):** "scan the room; if it
  meets all the requirements, it works." That is exactly the current engine:
  automatic seeded flood fill, requirements = enclosed + roofed + bed + door +
  light. Confirmed by the user, so it stays automatic.
- **DEFERRED — how the player is told what a room still needs.** The user has
  reopened the question ("maybe a plaque system or similar — we can fix that
  later"). Nothing is being built for it now, and the plaque removal stands
  until the user decides. `RoomScanner.Result.missing()` already produces the
  exact text ("not enclosed; no bed; no door; no light; open to the sky"), so
  whichever surface wins later — Tingboka entry, HUD toast, held-tool overlay,
  or a plaque-like marker — it only needs a presenter, not new detection work.
- **BACKLOG — cloak/armour texture pack** (user, long-term): a full cosmetic
  layer for cloaks and armour progression. Slots into A1d's appearance layering
  and the guard equipment progression in B1; not in scope now.

## Iteration log

### Iteration 1 (in progress)
| # | Requirement | Status | Evidence / gap |
|---|---|---|---|
| 1 | Clean build from scratch (NeoForge 1.21.1, Java 21, MDG 2.0.144) | PASS | `./gradlew build` → BUILD SUCCESSFUL, `build/libs/hearthstead-0.2.0.jar` |
| 2 | All prototype GameTests pass on 1.21.1 | PASS | `./gradlew runGameTestServer` → "All 9 required tests passed" (founding, profession, farmer, lumberer, guard, eat, alarm/flee, settler NBT round-trip, SavedData round-trip) |
| 3 | 1.21 datapack layout (loot_table/recipe/structure/tags/block singularized, recipe result {id}) | PASS | validator + gametests load structures; recipes rewritten |
| 4 | Networking ported to payloads (OpenSettlerScreenPayload) | PASS (code) / untested client | registered via RegisterPayloadHandlersEvent; client screen open needs client-side check |
| 5 | Capabilities: hearth item handler exposed | PASS (code) | RegisterCapabilitiesEvent registration; no automated test yet — add hopper/gametest check |
| 6 | Room detection engine (no plaque) | FAIL (not built yet) | A1c in progress |
| 7 | Homes: capacity from beds, bed claiming, sleep in own bed | FAIL (not built yet) | A1c |
| 8 | Furnishing quality score → morale | FAIL (not built yet) | A1c |
| 9 | Modular settler visuals | FAIL (not built yet) | A1d |
| 10 | Dedicated-server E2E on NeoForge (boot, found, persist) | FAIL (not run yet) | A1e |
| 11 | UI visual inspection | BLOCKED (headless env) | attempt xvfb+Mesa client; else code-based checks + manual screenshot checklist |
| 12 | Asset validator green on new layout | FAIL (not re-run) | update tools/validate_assets.py paths for 1.21 |
| 13 | No TODO/FIXME/placeholder in active scope | UNVERIFIED | sweep pending |
| 14 | Deprecation warnings triage | OPEN | non-removal deprecations remain; list & fix or justify |

Green streak: 0. Next: A1c room detection, A1d visuals, then full round re-run.

### Iteration 2 (in progress) — QA enforcement installed + A1c verification

Permanent QA system installed per directive: `qa/PROTOCOL.md` (v1.0.0),
`tools/hearthstead-qa` controller (sole approved test entry point),
`.claude/settings.json` hooks (bash guard blocks direct `runGameTestServer`/
`runClient`/`runServer` — validated exit 2; post-edit hook marks
`qa/reports/.stale` — validated; Stop-gate hook blocks completion while red —
validated exit 2), freshness manifests with source fingerprints, decision-trace
detectors (`qa/scripts/analyze_trace.py`), seeded reproduction, documented
`qa/reports/BLOCKED` escape (cleared automatically by every full run).

| Finding | Root cause | Fix | Evidence |
|---|---|---|---|
| GameTests 12/13: `settlersleepsinclaimedbed` — settler IDLE at hut doorway rel (8,1,6), bed claimed but never reached | Settlers had door-capable *pathfinding* (`setCanOpenDoors/PassDoors`) but no goal that physically opens doors — pathed to the closed oak door and pushed against it forever | Reference check (user directive): TekTopia + MineColonies villagers both open doors; TekTopia closes them behind. Adopted: `OpenDoorGoal(this, true)` at priority 1 (flag-free, runs beside move goals; closing keeps homes enclosed + raid-defensible) | artifacts/20260823T183955Z/gametest-failures.txt; fix in SettlerEntity.registerGoals |
| Stale-settlement purge regression (concurrent day-batch neighbors deleted each other) | first purge version removed any settlement <40 blocks unconditionally | replaced distance heuristic with exact ARENA-BOUNDS purge (`helper.getBounds()`): can only remove settlements standing in the space this test owns | in HearthsteadGameTests.makeSettlement(GameTestHelper,…) |
| **Breached/leaky room still registered** (found only after the fix above, and deterministic — the old suite had been passing this case by luck) | The GameTest arena is capped by a **barrier ceiling** (`y9=Barrier`, measured). So when the fill escapes a breached hut it spreads under that ceiling and comes back *enclosed and roofed* — `enc=true sky=false vol=1856` versus the intact hut's `vol=27`. Enclosure and roofing were both answering correctly; the missing rule was that a dwelling must be a bounded ROOM. | Added `MAX_HOME_VOLUME = 512` to `validHome()` — already a 16x16 hall with a 2-high ceiling, so no real cottage is affected, while a fill that has escaped into a cave/courtyard/outdoors is rejected. This is what makes a raid breach genuinely un-home a house. | `leakyroomrejected` + `homeinvalidatedwhenwallbroken` green |
| Housing was poll-driven: a homeless settler waited up to 40 ticks after a home appeared | settlers polled for a free bed; nothing pushed an assignment when a house registered | `BuildingManager.assignFreeBeds` hands a new home's free beds to loaded settlers without one, the moment it validates | in `processScan` |
| **Room registration flaky: 1/5 to 2/5 runs green** — `settlersleepsinclaimedbed` + `homeinvalidatedwhenwallbroken` intermittently saw `homes=0 buildings=0` | Diagnosed from evidence, not intent. Census diagnostics proved: the room was VALID (`liveScan enc=true beds=1 doors=1 lights=1`), the settlement DID hold it (`d=9.2 holds=true`), and the scan WAS processed (`requested=5 processed=5 pending=0`). Therefore the scan was rejected *at the time it ran*: `RoomScanner` gated validity on `level.canSeeSky`, which reads the heightmap that the **light engine settles asynchronously** after the arena's ~1300 block writes. One rejected scan was final — nothing ever re-scanned. | **(1)** The roof test is now **geometric**: for every cell at the top of its column, look upward for a block with a collision shape (`hasCoverAbove`). Deterministic, independent of the light engine, and it accepts glass and slab roofs that `canSeeSky` wrongly rejected. (An intermediate attempt simply dropped the sky gate — that was wrong and is recorded here rather than quietly reverted: it let a breached room register, which is how the barrier-ceiling finding above was uncovered.) **(2)** `BuildingManager` re-checks a failed scan 4x at 100-tick spacing, so a room that just missed — or a world that has not settled — still registers, matching how the reference colony sims re-check rather than decide once. **(3)** Added `Result.missing()`, the player-facing "why isn't this a home yet" string that inherits the job the removed plaque used to do. | stability sweep below |

**Stability evidence (this is the point of the exercise).** A single green run
proved nothing here: the suite passed once at 13/13 while carrying a defect
that failed 4 runs out of 5. Repeat-run measurement is therefore the standard,
recorded before and after every fix:

| stage | result |
|---|---|
| after the door fix | 2/5 green — flake still present, "green" was luck |
| after the arena-bounds purge | 1/5 green — hypothesis wrong, discarded |
| after the geometric roof test | 0/5 but **deterministic** (leaky room registered) — a better state than flaky |
| after `MAX_HOME_VOLUME` | **5/5 green** |
| with 2 new regression tests added (15 tests) | **3/3 green** |

New regression locks: `unlitRoomRegistersOnceLit` (a failed scan must be
re-checked, not written off) and `glassRoofCountsAsRoofed` (roofing is
geometric and must not consult the light engine).

### Iteration 5 — SLICE ANIM-1: recorded specification corrections

Per INV-10, defensible deviations from `docs/ANIMATION_CATALOGUE.md`'s
literal text, found and confirmed correct by the ANIM-1 RELEASE_GATE
(MEDIUM-6), recorded here rather than left silent:

| Deviation | Catalogue text | What shipped | Why |
|---|---|---|---|
| `RUN_PANIC` length | 0.55 s loop | `withLength(0.6F)` | 0.55 s puts the accent's quarter-beats off the 0.05 s tick grid `anim_check.py` enforces; 0.6 s lands every keyframe on an integer tick with no perceptible change to the silhouette. |
| `MELEE` end keyframes | catalogue's literal end-pose values | `right_arm`/`left_arm`/`torso`/`head`/`right_leg` end keyframes rewritten to exactly match each part's t=0 start pose | The catalogued end values left a visible pop back to rest the instant the one-shot expired (an interruption artifact, not a deliberate beat). Snapping the end pose to the start pose is the correct fix for any one-shot that is not itself a hold — the general form of `resetPose()`-safety authored into the clip data instead of the model code. |
| Off-grid keyframe timestamps (`WALK_HURRIED`, `RUN_PANIC`, `MELEE`, `CELEBRATE`) | as originally transcribed | nudged to the nearest 0.05 s tick | `anim_check.py` §17.4 enforces the tick grid; the nudges are sub-perceptual (≤1 tick) and do not change any pose. |
| `anim_check.py` checker exemptions: `EAT` added to `LEGS_EXEMPT`; `SHIELD_BLOCK` added to `CLOAK_PIN_ALLOWLIST` | catalogue §17.4's own enumerated allowlists do not name either clip | both exemptions kept | `EAT` is a stationary in-place clip the catalogue never asks to move the legs (§12.3 specifies no leg channel at all — flagging its absence would be a false positive). `SHIELD_BLOCK`'s cloak is deliberately pinned by the raised shield arm per §4.4's own bone list, not left to swing — the pin is the correct read of the spec, not a bug the checker should catch. |

### Iteration 6 — SLICE ANIM-1: REVISE round (RELEASE_GATE 2026-08-24)

RELEASE_GATE returned REVISE: 1 BLOCKER (see `docs/project/KNOWN_FAILURES.md`
KF-011 — bed-sleeping settlers permanently stuck asleep), 4 HIGH, 6 MEDIUM,
6 LOW. Fixed in one coordinated round per the standing rule (all findings
addressed together, one re-review). Evidence and per-finding detail: git
history on `claude/hearthstead-settlement-mod-vbdb9n` for this iteration,
`.claude/WORK_STATE.md`, and KF-011 above for the BLOCKER specifically.

### Iteration 7 — PLAQUE-2 step 3b: recorded specification correction

**What changed, and why it is a correction rather than a weakened test.**

`theSheetSaysWhatIsMissing` (added earlier the same day, in step 3) asserted
that a plaque on a satisfied room shows **one sheet line per requirement**,
all met. Step 3b makes a registered building show its **occupancy** instead —
`People 1/2` — and the test failed on exactly that line. Under INV-10 a
failing test is never edited to fit the code, so this is recorded here.

The change is a product decision the owner asked for, not an accommodation:

> *"Og en population slik jeg vet om et hus er fullt. People 1/2 eller
> lignende"* — and, minutes later, *"Rettelse dropp working eller ikke. Det
> lyset fungerer veldig fint."*

The sheet's writable field is about four model pixels tall. Six lines in it
are unreadable, so the sheet takes one of two faces: the **checklist** while
the building is not registered, and the **occupancy** once it is. That is also
the right split by usefulness — a finished checklist is four ticks nobody
needs to re-read, while "is there a bed free" is the live question — and it
degrades correctly: if a requirement later fails, the plaque unlinks on its
next survey and the checklist returns by itself, naming what broke.

**What was kept.** The half of the test that actually judges the slice's claim
— take the bed out, and *exactly* the beds line flips to unmet while doors,
lights and floor stay met — is unchanged, and still fails if the ink mapping
is broken (verified by mutation). Only the first phase's expectation moved,
from "the finished checklist" to "the registered face", and it now asserts
the new behaviour positively rather than asserting less.

**No working / not-working line was built**, per the owner's correction. The
lamp in the board already carries that signal, and carries it across a village
square where a word cannot.

---

## Iteration 8 — specification correction: the village clock

**What changed.** `SettlerEntity.DayPhase` (WORK / EVENING / REST) was replaced
by the settlement-wide `DayPhase` with six phases, adding a waking phase before
dawn and a midday meal. This is a product change the owner asked for: *"når det
er «jobbtid» så drar de til jobb. Ettermiddag så er det mat så kveld legge seg."*

**What it broke, and why that is not a test being weakened.** Two fixtures were
pinned to the old boundaries:

- `settlerWakesAtDawnWithRecoveredEnergy` set the clock to 23000 with the
  comment *"REST phase, close to dawn (23500)"*. Under the new clock 23000 is
  RISE, so the settler correctly never went to bed. The fixture moved to 22600 —
  still deep in REST, a few hundred ticks short of the new dawn. **The
  assertion is unchanged**: drive a full night through dawn and require a
  natural wake with recovered energy.
- `settlerSleepsInClaimedBed` failed as collateral. It shares a level, and
  therefore a day time, with the test above through the `night` batch; once the
  other test stopped being in REST, this one's settler was walked off to the
  gathering point. Fixing the first fixture fixed both.

Neither test was skipped, loosened or deleted, and neither assertion was
touched. What moved was a clock reading that the specification itself moved.

**What it caught that was a real defect.** `aFullSackSlowsTheCarrier` failed
because the new `GoToPostGoal` was walking a *laden* courier to the gathering
point, where he set the goods down. That is a genuine bug in the new feature —
the schedule must never override a job in progress — and the fix is in the
goal, not the test: a settler carrying anything is not re-posted. The first
attempt at that fix read the synced carry load, which is published from
`aiStep` **after** the goals have already run, so on a settler's first tick it
still said zero while the sack was full. Reading the container is the only
answer that is true on every tick.

**Mutation evidence.** Both new load-bearing rules were mutation-proven in one
run: setting `OFF_ROAD_MALUS` to zero failed
`settlersTakeTheLongWayRoundToStayOnTheRoad`, and removing the vacate step from
`Employment.hire` failed `noSettlerHoldsTwoPosts` and
`takingAWorkerNamesTheLoss`. Restored, 89/89 green.

---

## Iteration 9 — specification correction: the farmer sows by hand

**What changed.** The farmer's planting step now sets `WORK_SOW`
(`SOW_BROADCAST`) instead of `WORK_PLANT` (`FARM_PLANT`). This is a product
change the owner asked for directly: *"Farmer lager en strø animation at han
kaster ut strø for å legge ut nye seed."* Broadcasting seed reads at fifty
blocks; pressing one seed into one hole does not — and D-016 makes a distinct
signature motion part of what finishing a job means.

**What it broke.** `farmerReplantsAfterHarvest` asserted the farmer passes
through `WORK_PLANT`. The assertion — that the farmer visibly enters a planting
activity after harvesting, rather than silently refilling the field — is
unchanged; only the name of the activity it watches for moved, because the
activity itself moved. Nothing was skipped, loosened or deleted.

**FARM_PLANT is not deleted.** It stays authored and catalogued, unused for
now. A clip with no caller is not a defect; deleting a good clip to tidy a
report would be.

**Tool evidence.** `anim_preview.py` — new this iteration — read all three new
signature clips as clean against the craft standard, and caught two real
defects while it was being built: `HAMMER_ANVIL`'s torso peaked *on* the
contact tick instead of before it, and `GATHER_LOG` ended away from its start
pose, which would have snapped the settler when the one-shot expired. Both are
fixed. `MELEE` carries a recorded exception rather than a silent pass.

---

## Iteration 10 — KF-018 (lumberjack scan), D-016 closed, and an invisible
## nameplate regression found by a pig

### KF-018 — the lumberjack starved on sparse maps; uphill trees were invisible forever

**What was wrong, with the numbers.** `LumbererWorkGoal.canUse` scanned for a
tree base with `WorkScanner.scan`, whose offset table is the full **97 × 97 ×
9 = 84,681** positions around the settlement (`WorkScanner.MAX_RADIUS=48`,
`Y_BAND=4`). At a 512-position budget per call and a scan roughly every four
to six seconds (`scanCooldown = 80 + random(40)` ticks), one full sweep took
**about fourteen minutes** — a settler in a thin wood stood idle nearly a
quarter hour before noticing the tree behind him. Worse, the vertical band
was centred on the **hearth's own Y**, so a tree four blocks up a slope sat
outside the ±4 band and was never found — not slow, *permanently invisible*.
Both numbers and the "fourteen minutes" figure are stated in the fix's own
doc comment (`WorkScanner.java`), not recomputed for this entry.

**Fix.** `WorkScanner.scanColumns` — a second offset table of horizontal
**columns only** (9,409 of them, `side² = 97²`), independent of any Y
anchor. `LumbererWorkGoal.trunkInColumn` reads the heightmap
(`MOTION_BLOCKING_NO_LEAVES`) once per column and only descends the trunk
when the surface block is itself a log, capped at `TRUNK_DESCENT = 32` so a
decorative column can never be chased to bedrock. The code's own comment
puts a typical sweep at "about twelve calls" versus the old "a hundred and
sixty-five" — an average-case figure (the scan can stop once it has found
`maxResults=6` bases), not a worst-case one; the sparse-map case that KF-018
is about still needs to walk close to the full 9,409-column table, which is
still an order of magnitude cheaper than the old 84,681-position volume.

**Files.** `src/main/java/com/hearthstead/entity/ai/WorkScanner.java`
(`scanColumns`, `columnTable`), `src/main/java/com/hearthstead/entity/ai/
LumbererWorkGoal.java` (`trunkInColumn`, `TRUNK_DESCENT`).

**Not yet recorded in `docs/project/KNOWN_FAILURES.md`** — that file is
outside this worker's ownership this session; the KF-018 identifier exists
only in code comments right now and should be written up there by whoever
owns it next.

### D-016 closed in full — the last five signature motions

The five trades still on a borrowed motion (`COOK`, `CARPENTER`, `MASON`,
`FLETCHER`, `TANNER`) got their own: `COOK_STIR`, `CARPENTER_PLANE`,
`MASON_CHISEL` (the impact-checked clip — `tools/anim_preview.py`'s
`IMPACT_CLIPS` set, held to the wind-up/beat/overshoot standard),
`FLETCHER_FLETCH`, `TANNER_SCRAPE` — all authored in
`docs/ANIMATION_CATALOGUE.md` §20 and `SettlerAnimations.java`.

`SettlerActivity` gained `WORK_STIR/PLANE/CHISEL/FLETCH/SCRAPE`, **appended
after the existing values**, per the enum's own standing rule ("ordinals of
the values above must never shift, this is the wire format" —
`SettlerEntity.DATA_ACTIVITY` is ordinal-keyed). `Employment.motionOf` was
updated so every one of the twelve `Production`-backed trades now maps to a
distinct `SettlerActivity`/clip pair — no two trades share a work loop
anywhere in that switch. Recorded as **D-017**.

`tools/anim_preview.py` read all 44 catalogued clips clean (`44 of 44 clips
read clean`, re-run independently for this entry), and `tools/job_audit.py`
gained **smith** as its seventh CERTIFIED trade (verified against the
working diff:
`CERTIFIED = {"lumberer","farmer","courier","guard","miner","baker","smith"}`).

**What is not yet true, stated plainly so it is not overclaimed later.** The
five trades above have their motion but **not** certification: each still
borrows another trade's work sound (`docs/ANIMATION_CATALOGUE.md` §20: "that
debt keeps these five jobs out of the certified list until their own voices
land"), confirmed unchanged in `docs/project/JOB_STANDARD.md`'s trade table
— all five read "not yet". Motion-complete and job-certified are different
claims; only the first is true for cook/carpenter/mason/fletcher/tanner as of
this iteration.

### An invisible rendering regression, found by a pig standing next to a settler

`SettlerRenderer.renderNameTag` still used the 1.20-era
`pose.scale(-0.025F, -0.025F, 0.025F)` mirror. On 1.21 that negative X scale
flips every glyph quad to face away from the camera, so the whole name tag
was culled — silently, with no error, no log line, nothing to grep for. It
had been broken since the 1.21 port and nothing had ever proven a settler's
name actually rendered.

**Proof.** A vanilla pig's name tag rendered normally standing beside a
named-but-invisible settler in the same shot — evidence session
`qa/reports/artifacts/live/20260825T183505Z` (`shots/pigtest.png`,
`shots/nameclose.png`, `shots/nameclose2.png`). Comparing against an entity
that is known-good ruled out camera, font and distance as the cause and
pointed straight at the scale sign.

**Fix.** Positive X scale and the 1.21 `EntityAttachment.NAME_TAG` point
(`entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, …)`)
replace the manual offset. Also fixed in the same pass: `shouldShowName` now
branches on `entity.isCustomNameVisible()` — an explicitly flagged settler
(lineups, a player-named settler) gets vanilla's full range (`4096.0` sqr =
64 blocks), everyone else keeps the original `150.0` sqr intimate range.

**Same evidence session also found the showcase forceload lesson** (stage
chunks unloaded → `getHeightmapPos` answers the world floor at Y=-64 → a
whole lineup spawns inside bedrock and suffocates, reporting "N settlers
posed" over an empty field) — `qa/scripts/showcase.sh` now force-loads the
stage before building or spawning on it. Both findings share the one
timestamp because both were made in the same continuous live session, not a
citation error.

### Showcase tooling landed this session

`/hearthstead pose|pulse|lineup` (`HearthsteadCommand`) let an operator pose
any settler into any of the 33 catalogued poses, spawn a labelled lineup
page, or re-fire a one-shot, without touching the AI. `qa/scripts/showcase.sh`
wraps this into turnkey filmed scenes against a live session. **Recorded as
D-018**: `applyPose` calls `settler.setNoAi(true)` and writes the
activity/profession projection directly, bypassing goal selection entirely —
so this is a viewing aid, never a test oracle, and no GameTest may
pose-then-assert. Verified: no `GameTest` in the tree currently calls `pose`.

### Quality gate — honest status: in progress, not clean

`qa/reports/latest.json` (most recent full run, `20260825T172412Z` →
`173813Z`): **overall PASS, `green_streak: 1`** — one short of the ≥2 the
contract requires for any completion claim. Since that run, source has
changed substantially and remains **uncommitted**: `WorkScanner.java`,
`LumbererWorkGoal.java`, `SettlerRenderer.java`, `HearthsteadCommand.java`,
`SettlerAnimations.java`, `Employment.java`, `SettlerActivity.java`,
`SettlerEntity.java`, `SettlerModel.java`, `Schedule.java`, both lang files,
`docs/ANIMATION_CATALOGUE.md`, `tools/anim_preview.py`, `tools/job_audit.py`,
plus four new untracked GameTest files and the new `showcase.sh`. **No full
run has been made against the current source fingerprint at all** — the
`full ×2 + gate` requirement has not merely regressed, it has not started.
This is stated here rather than left implicit so no downstream claim treats
`latest.json`'s PASS as covering the current tree.

---

## Iteration 11 — specification correction: a load is bounded by mass, not only by count

**2026-08-26.** `logistics/Weight.java` was written, documented and committed
with **zero callers anywhere in the mod** — found by an interconnection audit,
not by a test, because nothing tests a class nobody calls. For as long as that
was true the owner's own stated design ("putte ting som er tungt nærme
warehouse") did not exist in play: a courier's load was bounded by item COUNT
alone, so eight iron ingots and eight feathers cost exactly the same walk, and
**no arrangement of buildings could beat any other**.

Wiring it into `CourierWorkGoal`'s two load loops turned two GameTests red.
Both were the tests encoding the old specification, and both are corrected
here rather than loosened. Recording the distinction, because "the fixer
changed the judge" is the exact shape this project forbids:

**`courierSackShowsTheRealLoad`** asserted `peak == capacity` — the sack always
fills to 8. That is only right for cargo light enough that the count binds
first; the test hauls OAK_LOG, which is HEAVY, so the true ceiling is 4. The
assertion is now `peak == Weight.perLoad(OAK_LOG, capacity)`. This is
**stricter than what it replaced**, not weaker: it still catches under-filling,
and it now also catches the weight table being wrong or silently bypassed. It
was deliberately NOT relaxed to `peak <= capacity`, which would have passed a
courier hauling one log at a time forever.

**`reservationLetsOnlyOneCourierFetchTheSameStock`** seeded 4 RAW_IRON —
DEAD_WEIGHT, so now two trips. The first courier finished, released the job,
and the second correctly took the remainder: the ledger working exactly as
designed, reported as a failure only because the setup quietly assumed one
trip empties the chest. The pile is now sized to exactly one load and **every
assertion is untouched**, including the strict "only one courier should ever
have hauled this stock". The bug was in the fixture's hidden premise, not in
the claim.

The lesson worth keeping is the first one, though, not the test-repair: a
feature can be complete, reviewed, documented and committed and still be
absent from the game, and no suite will say so. Nothing in this repository's
gate asks "is this code reachable at all". The interconnection audit that
found it is now `docs/project/SPIDER_WEB_AUDIT.md`.

## Iteration 11b — three buildings stopped advertising jobs nobody can fill

SCHOOL, INFIRMARY and MARKET each declared worker capacity, so their plaques
offered hiring, while none appears in `Employment.TRADES` — every hire was
refused with `no_trade`. The refusal was honest; the offer was not. Capacities
set to 0 until the matching trades exist. The plaque is the surveyor this
whole design rests on, and a plaque that advertises a post that cannot be
filled teaches the player its promises are decorative.

## Spec correction 2026-08-28 — the playtest's recruit expectation predated the tavern gate

**What changed.** `qa/scenarios/default.txt` expected `/hearthstead recruit`,
issued BEFORE the hearth is even placed, to print "Recruitment timers
advanced". The tavern gate (c3ed4d6, byggherre-dom 5) deliberately made that
sentence conditional: it is only true when at least one settlement has a
valid tavern. The first `full` run after the gate landed (fresh container,
2026-08-28, artifacts 20260828T190734Z) correctly failed the stale
expectation: with no settlement founded yet, recruit's answer is a
`sendFailure` the server log never carries at all.

**Why this is a correction and not a weakened judge.** The old expectation
only ever passed because the PRE-gate command printed "Recruitment timers
advanced" unconditionally — "for 0 settlement(s)" included (verified in
c3ed4d6's diff: the count argument was added there). The scenario was
asserting a sentence the product printed even when it had done nothing.
The replacement asserts strictly more truth, through the same real command
path: (1) pre-founding, the cmd input class is now probed with
`hearthstead info`, whose no-settlement answer is console-visible and
honestly "No settlement founded"; (2) post-founding, `hearthstead recruit`
must name the tavernless settlement it SKIPPED — the gate's "stille
feil"-forbud exercised live in a real world. The positive path is covered
at the correct layer by RecruitGameTests (noTavernMeansTheGaugeNeverFills,
aValidTavernReopensTheGate, aTavernAndItsInnkeeperAccelerateTheRecruitGauge);
no assertion anywhere was deleted or loosened.

## Spec correction 2026-08-29 — AC-3 was counting the backdrop, not the frame

**What changed.** `qa/scripts/check_screenshot.py` required more than 500
distinct colours. It now requires at least 64 distinct colours AND at least
1.5% of pixels on a real luminance edge. Both must pass.

**What triggered it.** The UI pass replaced `Screen#renderBackground`'s
full-screen blur with the translucent scrim vanilla's own container screens
use (110ms of the settler sheet's 117ms per frame, see
`qa/reports/uiperf/`). The first `full` run afterwards failed AC-3 on
`plaque-03-screen-open.png` at 484 colours — on a screenshot of a completely
and correctly rendered plaque screen, tabs, requirement rows, scrollbar,
buttons and all.

**Why this is a correction and not a weakened judge.** A blur interpolates
between pixels and manufactures thousands of intermediate colours out of
nothing, so the 500 floor was measuring how colourful the BACKDROP happened
to be. It was never testing whether the frame rendered. The proof is that it
fails the OLD screen too: the pre-existing parchment hearth capture has 307
distinct colours. That screen shipped; the check passed historically only
because the scenes it ran in had colourful backdrops behind it.

Measured on this repository's own captures:

| capture | colours | edge pixels |
|---|---|---|
| plaque screen, fully rendered (the failure) | 484 | 6.12% |
| hearth, new command centre | 170 | 8.96% |
| hearth, OLD parchment ledger | 307 | 11.12% |
| vanilla title screen | 20493 | 8.85% |
| the 22-colour artefact this check was written to reject | 22 | 0.23% |
| the 2-colour artefact | 2 | 0.00% |

Edge density separates real frames from artefacts by two orders of magnitude
and is what "a real rendered frame" actually means: structure, not palette.
The colour floor moves to where the observed artefacts really are (64, three
times the worst), and the edge floor carries the assertion (1.5%, four times
above the worst artefact and four times below the weakest real frame).

The judge is strictly stronger, not weaker: both artefacts in the original
docstring still fail, and they now fail on TWO independent criteria instead
of one. Nothing was deleted, skipped or excluded. Verified by running the
checker against every capture in the table above.
