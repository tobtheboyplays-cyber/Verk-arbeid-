# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Closed slices (full history in docs/, not here)

**HARNESS-1, PLAQUE-1, VISUAL-1, FIX-1, ANIM-1, A2a/A2b, A3 steps 1-3,
PLAQUE-2 steps 1-4, CHAINS-1 core, UI-1** — DONE, gated, pushed. KF-007
through KF-014 closed with evidence in the ledger. Permanent rules that came
out of them live in `docs/project/DECISIONS.md` (plaque is the surveyor,
chest truth, geometric roofing, bounded rooms, no dead controls) and
`qa/PROTOCOL.md`'s invariant list.

## Open slices

- **PLAQUE-2 step 5** — the right-click screen, rebuilt on UI-1 (also
  JOBS-1's hire/fire screen, same screen). Protocol exists
  (`PlaqueSnapshot`/`PlaqueAction`, revision-checked ASSIGN/EVICT).
- **CHAINS-1 step 5** — `Production.java` is in and gated (take from own
  chests, spend time, put back, written once). Step 5: the screen matching
  the mockup, then professions on the seam, food chain first. Six
  intermediate items stand (D-008); art was drawn and thrown away, not
  redrawn.
- **UI-1** — built, gated, the standard: nine-slice sprite kit, real vanilla
  font, offline `--strict` preview, `HsUi`/`HsButton` generated from
  `tools/ui/tokens.json`, D-014 no-dead-controls mutation-proven. **Still
  not proven in game** — no screen uses it yet; that's PLAQUE-2 step 5.
- **JOBS-1** — IN, gate pending. Employment is settler→**BUILDING** only
  (D-011); `Profession` is a derived projection, never stored. One
  settlement `DayPhase` (6 phases incl. midday meal); guards split the clock
  by worker index (derived). Five attributes + ten traits, asymptotic
  growth, mutation-proven. Roads wear from footpath. 89/89 GameTests as of
  iteration 8. **The writ stays until the hire screen exists** (D-012) — do
  not remove it early.

## THE JOB STANDARD — `docs/project/JOB_STANDARD.md`. Read this first.

`tools/job_audit.py` CERTIFIED (ratchet, **7** as of this session): lumberer
(the reference), farmer, courier, guard, miner, baker, **smith** (new).
Motion-complete but not certified — sound still borrowed (catalogue §20):
cook, carpenter, mason, fletcher, tanner (D-017/D-016: every trade now has
its own motion; remaining debt on these five is sound only).
`tools/anim_preview.py` reads all 44 catalogued clips clean; `--strict`
wired into `validate_assets`.

## This session — KF-018, D-016 closed, an invisible nameplate bug

- **KF-018 fixed.** Lumberjack tree scan starved on sparse maps: old volume
  scan was 97×97×9=84,681 positions (~14 min/sweep at 512/call), Y band
  anchored to hearth Y so uphill trees were invisible *forever*. Fix:
  `WorkScanner.scanColumns` (heightmap-based, 9,409 columns) +
  `LumbererWorkGoal.trunkInColumn` (walks down from surface, capped
  `TRUNK_DESCENT=32`). **Not yet in `docs/project/KNOWN_FAILURES.md`**
  (outside this worker's ownership — flag for its owner).
- **D-016 closed in full.** Last five signature motions: `COOK_STIR`,
  `CARPENTER_PLANE`, `MASON_CHISEL` (impact-checked), `FLETCHER_FLETCH`,
  `TANNER_SCRAPE` (catalogue §20). `SettlerActivity` gained
  `WORK_STIR/PLANE/CHISEL/FLETCH/SCRAPE`, appended per its own wire-format
  rule. `Employment.motionOf`: no two trades share a clip now. Detail:
  ledger iteration 10, **D-017**.
- **Nameplates silently broken since the 1.21 port.**
  `SettlerRenderer.renderNameTag` kept the 1.20 `scale(-0.025F,...)` mirror,
  which culls every glyph on 1.21 — no error, nothing to grep. Proven live: a
  vanilla pig's tag rendered beside a nameless settler
  (`qa/reports/artifacts/live/20260825T183505Z`). Fixed to positive-X scale +
  `EntityAttachment.NAME_TAG`. `shouldShowName`: an explicitly flagged name
  (`isCustomNameVisible()`) gets 64 blocks, everyone else the original 150.0
  sqr intimate range.
- **Showcase tooling landed.** `/hearthstead pose|pulse|lineup` +
  `qa/scripts/showcase.sh` for filmed scenes. Same evidence session found
  the forceload lesson: unloaded stage chunks made `getHeightmapPos` answer
  -64 and a lineup suffocated in bedrock — `showcase.sh` now force-loads the
  stage first. **D-018**: pose is a viewing aid, never a test oracle
  (`applyPose` calls `setNoAi(true)`, writes state directly) — no GameTest
  may pose-then-assert.

**Fast-quality fleet (unverified by this worker).** Working tree matches the
described fan-out — new `TradeButcherGameTests.java`,
`TradeSawyerGameTests.java`, `TradeSmelterGameTests.java`,
`TradeWeaverGameTests.java`, plus `Schedule.java`/`SettlerModel.java`/lang
edits beyond this worker's scope. Settler UI rebuild, 5 trade sounds, raid
slice, courier reservation hardening, hearth mayor tab, handbook overhaul:
reported, **not independently confirmed** — verify before citing as done.

## QUALITY GATE — HONEST STATUS: in progress, not clean

`qa/reports/latest.json` (`20260825T172412Z`→`173813Z`): overall PASS,
**`green_streak: 1`** — one short of the ≥2 required. Source has changed
substantially and remains uncommitted since that run (every file above,
plus the fleet's): **the current fingerprint has had zero full runs**, not
merely a broken streak. No completion claim is valid until `full` passes
twice clean and `gate` confirms it.

**KF-015 is OPEN** — a raid can resolve "repelled" while raiders live in
unloaded chunks. Design question, recorded not patched.

## Standing infrastructure (use it)

`tools/hearthstead-qa quick` after every change, `fast` before moving on,
`full` ×2 + `gate` before any completion claim (`qa/QUICKSTART.md`). Fast-
quality mode: parallel Sonnet workers, strict file ownership, Opus only at
gates; only the coordinator runs the QA suite, never two at once, never
mid-edit. `sonnet-driver` skill for session-start recovery; `tools/
blockbench/README.md` mandated for art; `animation-quality` skill for
weight/impact principles. **DECISIONS.md**: D-017/D-018 added this session
(top of file); flagging not fixing, the file has a pre-existing duplicate
D-007 and no D-015 entry though D-016 refers to "D-015's motion-sharing."

## Load-bearing findings from live debugging (do not re-derive)

- `ss -ltnp` unreliable — use `lsof -ti tcp:<port> -sTCP:LISTEN`. GNU
  `timeout` needs `--foreground`; Xvfb ignores SIGHUP (`pkill -9`).
- GLFW needs a real click before relative look works (quickPlay never gives
  one); console `~`-relative commands resolve against the console (use
  `execute at`), which then suppresses ALL feedback silently.
- A screen left open absorbs later clicks (`key Escape` between iterations);
  the FIRST `live click right` after a `key` often misses — re-issue it.
- Never run two suites at once; never edit/compile source while `full` runs.
- `sendSuccess(msg, false)` leaves NO server-log trace; silence in a log is
  not proof a path ran.
- `data merge` sets persisted fields but not synced-only ones; merging
  `State` on a plaque never calls `survey()`.
- UI findings live in the **`minecraft-ui` skill** — read before a screen.
- **1.21 nametag convention**: positive X scale + `EntityAttachment.NAME_TAG`;
  the 1.20 mirrored-scale idiom culls every glyph silently.
- **Force-load a filming/test stage before spawning/building on it** —
  `getHeightmapPos` on an unloaded chunk answers the world floor.

## Known problems (pre-existing, other slices' scope)

- `safe_regrab()`'s Y=300 round trip causes drift warnings (architecture note).
- Village-wide dawn wake window (settlement scheduler) — deferred.
