# WORK_STATE — 2026-08-25 late evening (ultracode fleet session)

## Mode
Ultracode ON permanently (user). Coordinator + parallel Sonnet workers under
strict file ownership; coordinator compiles/commits/runs QA, workers never
touch gradle. User loop: coordinator plays/tests, feeds findings to workers.
Standing user directives: premium only; core-loop playability is THE focus;
ASK USER before any `playtest` suite run (other suites fine); showcase video
when this wave lands ("var veldig nice"); nameplates show profession
("tydeligere for jobben" — LANDED).

## Committed & pushed (branch claude/hearthstead-settlement-mod-vbdb9n, PR #1)
Latest: PLAN_CIRCULATION wave-2 synthesis; animation-quality skill v2;
1917340 miner real-loot fix + smelter/sawyer test reconciliation; e6bc6d8
Prøvebenken research system + need-aware Production.ready() + profession
nameplate; d4c8962 recruit conservation; earlier: plaque beautification,
fleet wave 3, door fix 426b627.

## Agents in flight
- polermester cycle 1 (owns SettlerAnimations, SettlerModel, SettlerEntity
  anim wiring, 4 screens subset, gen_settler/gen_sounds, BOTH lang files,
  SettlerSnapshotPayload/Network). Queued addenda: guard sword stance
  ("selvsikker og kontrolert", use skill v2 §2.2 Pflug recipe), better base
  skins, research lang keys (scratchpad/research_lang_keys.md), ScholarWorkGoal
  registration line, outfit_scholar/miller/brewer.
- FARMER-BOOTSTRAP (FarmerWorkGoal + new gametest): first-planting, seed
  reserve, replant 28t.
- GUARD-PROGRESSION (GuardRank/Melee/Leap/Patrol/Schedule + new gametest):
  STRENGTH training, night-watch sleep gap, leap fizzle, hostile-only splash.
- COURIER-R2 (CourierWorkGoal + new gametest): workshop outputs → warehouse,
  keep-back 8 (MINE 0).
- RESEARCH-TEX + RESEARCH-UI: skill drafts → scratchpad (anim draft LANDED,
  installed as animation-quality v2).

## Next actions (order)
1. Land remaining fix workers → compile-probe → commit each.
2. polermester lands → compile, validate_assets, commit; THEN VERIFY-1:
   tools/hearthstead-qa gametest, fix-loop with owners.
3. Install texture/UI skill drafts after review (SKILLS-1 #39).
4. REVIEW-ALL (#40) + IDLE-1 (#41, polermester cycle 2 headline).
5. FILM-2 showcase video for user (#29) on the new jar; then GATE-1 full ×2.
Delete qa/reports/BLOCKED at integration (documents mid-fleet block).

## Key facts (avoid re-deriving)
- Production.ready() is need-aware; ties keep list order (fed paths win).
- Miner banks loot via Block.getDrops(iron pickaxe); smelter test seeds 2
  raw iron (below bloom threshold); sawyer ledger spans planks+beams.
- Research: 6 projects, SCHOLAR(18)/MILLER(19)/BREWER(20); bonus consumers
  (Production ticks, farm growth, guard training) intentionally unwired —
  integration later; 2 research gametests need ScholarWorkGoal registration.
- Guard audit: rank armor is server-real but INVISIBLE (no armor layer on
  custom model) — queued slice ARMOR-VISIBLE; Shield Bash/Rally unbuilt.
- Slices queued in PLAN_CIRCULATION wave-2: REPAIR-1, FUEL-1,
  ARMOR-VISIBLE, GUARD-3, FARM-COMPOST, STONE-VARIETY.
- Live tmux hsqa-live may still hold the OLD jar — restart with new jar
  before filming.
