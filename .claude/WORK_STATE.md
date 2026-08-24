# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Closed slices (full history in docs/, not here)

- **HARNESS-1, PLAQUE-1 — DONE.** KF-009 (8 harness bugs) in KNOWN_FAILURES.
- **VISUAL-1 — DONE, RELEASE_GATE PASS** (all 3 Opus calls spent). Modular
  appearance (seed → 5 composited layer sheets, client `SettlerTextureCache`),
  KF-007 determinism fix + `check_pipeline()`, KF-010 constructor-seed fix.
- **FIX-1 — DONE** (dedicated-server + performance regressions).

## SLICE ANIM-1 — REVISE round nearly closed; re-review is the next gate

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

**In flight (2 parallel sonnet-builders, disjoint files):**
1. SettlerAnimations.java only: weight/impact pass on CHOP, FARM_TILL,
   LIMB_BRANCHES, MELEE per `.claude/skills/animation-quality/SKILL.md`
   (impact hold, follow-through overshoot, torso lead, secondary motion),
   accent timestamps untouched, visually iterated via Blockbench bridge.
2. gen_settler.py + regenerated PNGs only: premium texture pass (shading/
   AO/cloth folds), determinism double-run proof, validate_assets green.

## Next concrete action

When both workers land: reconcile → `quick` → `full` twice (green_streak
≥ 2, hands off the tree during runs) → one short confirmation film of the
improved CHOP → **RELEASE_GATE re-review (Opus call 2, the last normal
one)** scoped to changed areas → close ANIM-1 → then SLICE A2a per the
PLAN_GATE output (warehouse/courier; seam-then-fan-out plan already
delivered by opus-planner).

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
