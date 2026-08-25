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
