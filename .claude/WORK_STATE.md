# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Closed slices (full history in docs/, not here)

- **HARNESS-1, PLAQUE-1 — DONE.** KF-009 (8 harness bugs) in KNOWN_FAILURES.
- **VISUAL-1 — DONE, RELEASE_GATE PASS** (all 3 Opus calls spent). Modular
  appearance (seed → 5 composited layer sheets, client `SettlerTextureCache`),
  KF-007 determinism fix + `check_pipeline()`, KF-010 constructor-seed fix.
- **FIX-1 — DONE** (dedicated-server + performance regressions).

## SLICE ANIM-1 + A2a (pieces 1/2/5) — GATE GREEN

All 23 A1 clips implemented (commits `9a1ce02`, `6d472bf`, `db98ae6`),
gate was green twice, first in-game video sent. **RELEASE_GATE (Opus call
1) returned REVISE**: 1 BLOCKER, 4 HIGH, 6 MEDIUM, 6 LOW.

**Fix round complete (commits `b4d4650`, `0a18f40`, `ad4a20b`, `7500e22`),
all Level A checks green** (`quick` PASS: build + 418/418 assets + anim;
gametest 25/25 including the new regression test):

- BLOCKER-1 → KF-011: `tickNeeds()` now restores energy for SLEEPING
  (faster than RESTING — a bed must beat rough rest); new GameTest
  `settlerWakesAtDawnWithRecoveredEnergy` drives night→dawn.
- HIGH-1: HAUL_LOG fully replaces WALK in `setupAnim` (own gait, like
  climbing) — carry arms genuinely locked; checker now enforces it
  (CARRY_LAYER_CLIPS).
- HIGH-2: lang keys for all 6 new activities (en+nb) + validator rule
  parsing the enum (`check_settler_activities`).
- HIGH-4: all 7 missing accent contracts WIRED for real (yawn, ladder
  creak, settler_eat bites, panic yelp, shield thud, cheer, limp grunt) via
  a server-side accent scheduler in `SettlerEntity.tickAccents()` + goal
  hooks; 3 new generated sounds (settler_panic/shield_thud/cheer, 8 oggs);
  `anim_check.py` ENTITY_SOUND_CONTRACTS greps the named constants from the
  owning Java files — gaps can no longer hide. Header doc now truthful.
- MEDIUM-1..6: SHIELD_BLOCK resets all shared parts before the react;
  one-shot stagger moved server-side to trigger tick (id-based delays);
  withheld seed returned on every farmer exit path; tilling crop-anchored
  (no cascade terraforming); 5 new work activities in the needs model;
  spec corrections recorded in quality ledger Iterations 5/6.
- LOW: GUARD_PATROL gate uses patrolState.isStarted(); lumberer full-bag
  resume sets HAULING_LOG; OGG encode now bitexact-deterministic (proven
  by identical double-run); doc drift fixed.
- Harness: playtest/live renderDistance 12→6 (playtest was ~62% of full
  runtime, chunk-gen-bound), client_boot polls 3s, contact-sheet
  end-frame crash fixed (verified against the real failing clip).

**HIGH-3 evidence**: live session restarted on the fixed jar; two long
films (90s, 100s — camera tracking a lumberer through felling + haul)
captured and sent to the user. User's verdict on watching: swings lack
WEIGHT (valid craft feedback → in flight below). "No tool in hand" in
that film was a harness artifact: raw-NBT Profession merge skips
`assignProfession()`'s tool equip — confirmed by manual `item replace`
+ screenshot; not a code defect. Film real settlers via the writ flow.

**Weight + texture passes landed** (worker-built, verified): CHOP,
FARM_TILL, LIMB_BRANCHES, MELEE gained impact holds, follow-through
overshoot and a leading torso per `.claude/skills/animation-quality`;
`gen_settler.py` gained hair strands, face gradient, cloth creases,
4-tone boots. Both verified visually through the Blockbench bridge.

**RELEASE_GATE re-review (Opus call 2 of 2): REVISE.** Its BLOCKER was a
process error of mine -- I kept editing (A2a) while the review ran, so the
tree moved under it. Findings and their fixes (all landed):
- MEDIUM-1 (gate-breaking): `export_bbmodel.py` used `uuid4()`, so every
  Blockbench render invalidated the fingerprint. UUIDs now SHA-1-derived;
  byte-identical across three exports.
- HIGH-1 (dead control): the Courier's Writ shipped craftable and
  described, while `CourierWorkGoal.canUse()` returned false. Implemented
  the goal for real instead of hiding the item.
- LOW-3: the farmer's replant seed lived in a goal field (destroyed on
  unload). It now stays in the persisted bag and is taken back out only
  after every placement guard passes.
- MEDIUM-2/LOW-1: CLIMB_LADDER and WALK_LIMP were claimed as phase-locked
  sound contracts, which they cannot be (one samples accumulated state
  time, the other is driven by limbSwing). Both now honestly documented as
  frequency-only, in the header and in `anim_check`.
- HIGH-2: fresh evidence captured on the current jar -- a 45s tracked
  close-up (`live/20260824T214537Z/film/take-04-chop-clean/`) plus
  Blockbench frames of CHOP's anticipation (0.20s) and impact (0.55s).

**A2a is underway** (piece 1, 2 and 5 done, seam already landed):
- `WarehouseStorage` -- derived, revisioned, never persisted, insert()
  destination-first with a true leftover. 3 GameTests.
- `CourierWorkGoal` -- hearth to warehouse, deliberately one-directional
  so it cannot deadlock like MineColonies #5333; food never leaves the
  hearth; idles visibly with no warehouse. 3 GameTests.
- Storage view -- payloads + `StorageScreen`, sneak-use the handbook,
  read-only on purpose.
- Multi-`@GameTestHolder` discovery PROVEN (suite 25 -> 31 tests), which
  was the A2a plan's one recorded unknown.

## Next concrete action

**GATE IS GREEN: `GATE: PASS (green_streak=2)`** — two consecutive clean
full runs at one fingerprint (`20260824T222152Z`), covering ANIM-1's whole
REVISE round plus A2a pieces 1, 2 and 5. ANIM-1's Opus budget is spent
(2 of 2), so any remaining defect there is Sonnet's to fix; a third call
needs a genuine BLOCKER_GATE.

One intermittent playtest failure was seen and root-caused before
re-running (see KF-012's correction): step 283's plan-insertion click
occasionally does not land — the KF-009 family, not a code defect. Runs on
either side of it passed the identical scenario.

Next: A2a **piece 4 (7 courier sounds) FIRST**, then piece 3 (the courier
clips), per `docs/project/PLAN_A2a.md` — anim_check asserts each
contracted sound exists in sounds.json, so the assets must land before the
clips' final `animation` run. Piece 3 = WALK_LADEN + COURIER_LIFT/CARRY/
SET_DOWN/SORT; apply `animation-quality` (lift and set-down are
weight-bearing beats) and `blockbench-animation` (WALK_LADEN is a
four-pose cycle, not two).

**Process lesson from the re-review: never edit source while a gate or a
review is in flight.** Park the next slice until the current one is frozen
and green.

## Standing infrastructure (new this session — use it)

- **Fast-quality mode** (CLAUDE.md + premium-build-loop skill): parallel
  Sonnet workers under strict file ownership, seam-then-fan-out, Opus only
  at gates, full QA rarely (slice end ×2), video evidence per finished task.
- **`tools/hearthstead-qa quick`** — the after-every-change check (~15s).
  `qa/QUICKSTART.md` — whole workflow on one page.
- **`sonnet-driver` skill** — session-start recovery + escalation table
  (agent defs are model-pinned; a Sonnet main session gets Opus at gates).
- **Blockbench bridge** (`tools/blockbench/README.md`): real Blockbench
  web build served locally, driven headless via playwright-core +
  preinstalled Chromium; `export_bbmodel.py` (model + all 23 clips),
  `bb_render.mjs` (static/posed viewport renders). Mandated for art work.
- **`animation-quality` skill** — researched weight/impact principles +
  the CHOP diagnosis template.

## Load-bearing findings from live debugging (do not re-derive)

- `ss -ltnp` unreliable here — use `lsof -ti tcp:<port> -sTCP:LISTEN`.
- GNU `timeout` needs `--foreground`, else killing the PGID misses java.
- Xvfb ignores SIGHUP — explicit `pkill -9 -f "Xvfb :NN"`.
- GLFW needs a real click before relative look works; quickPlay never gives one.
- Console `~`-relative commands resolve against the console's context —
  use `execute at`, or `cmd` when the check needs the player's position.
- Player position drifts even from passive waiting — `capture_pos` before
  any `~`-relative command after a wait (KF-009).
- `execute at ... run <anything>` suppresses ALL feedback silently.
- `fill <box> X replace X` is a no-op the game never counts.
- A screen left open absorbs later clicks — `key Escape` between iterations.
- Never nest `nohup cmd &` inside a backgrounded Bash call.
- Never run two suites at once (KF-002/KF-003); never edit/compile source
  while a `full` run executes (false "stale jar" in its playtest step).
- Client boot under software GL can take minutes while genuinely
  progressing — never treat slowness as a hang.
- `playtest.sh` hard-fails on a stale jar — that check has caught real
  staleness twice; don't fight it, rebuild.
- `sendSuccess(msg, false)` leaves NO server-log trace for player-issued
  commands — check the flag before adding log-based expectations.
- Silence in a log is not proof a path ran — swallowed exceptions need
  explicit success/failure logging before evidence-based verification.
- Raw NBT `Profession` merge on a settler skips `assignProfession()` (no
  tool equip, no records) — fine for animation smoke checks, NOT for
  filming "real" behavior; use the writ/demo flow for user-facing video.
- `data merge` CAN set persisted fields (Profession) on a live entity;
  synced-only state (Activity) it cannot.

## Known problems (pre-existing, other slices' scope)

- `safe_regrab()`'s Y=300 round trip causes drift warnings (architecture
  note; a non-moving regrab would retire the class — not now).
- Village-wide dawn wake window (settlement scheduler) — deferred, noted
  in catalogue decisions.
