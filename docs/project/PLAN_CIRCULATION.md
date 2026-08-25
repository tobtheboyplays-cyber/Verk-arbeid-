# PLAN_CIRCULATION — making the economy true

*Master fix architecture synthesized from the 17-trade audit wave
(2026-08-25). The audits agree on one diagnosis: production, presentation
and tests are strong, but GOODS DO NOT MOVE and EQUIPMENT IS MINTED. This
plan makes FLOWS.md true instead of aspirational. Fix workers take slices
of this document under strict file ownership.*

## The five routes (extends the courier's JobPriority ladder)

R1 **SOURCE-OUT** (new): couriers collect from Ring-1 building chests
(mine today; fishery/pasture when staffed) → warehouse. Without it the
miner's stone is decorative (audit: dead-end output, mine chests).
R2 **FOOD-IN** (new): prepared food moves TOWARD the hearth and dining
hall only — split isHaulable into material-haulage vs food-delivery;
bakery/kitchen/butcher cooked output → hearth (and dining hall). Preserves
D-A2a-1's intent (food never drains AWAY from the hearth) while ending the
audit's C1 ("nobody ever eats what the cook makes").
R3 restock (exists), R4 consolidation (exists).
R5 **MILITARY-OUT** (new): arrows/armor/tools → armoury, barracks,
watchtower chests, so smithed goods physically arrive where consumed.

## Consumption becomes chest-true

- **Tools**: on hire, the settler TAKES their trade's iron tool from the
  smithy/warehouse chain if one exists (item leaves the chest); otherwise
  they carry their own crude tool (wooden/stone projection — honest
  "brought from home", visibly worse). Profession.tool() becomes that
  fallback only.
- **Tool wear v1**: work actions damage the held tool; a broken tool drops
  the settler to the crude fallback at ~70% work speed until a smithed
  replacement arrives via R5/restock. The smithy becomes the tempo dial
  FLOWS promises — because demand is real.
- **Guard armor**: GuardRank sets the CEILING; the ARMOURY chain sets
  availability. applyEquipment consumes real pieces (armoury/warehouse) up
  to rank; the in-code comment that already claims this becomes true.
  ARMOURY gets Production recipes (ingots+leather → pieces).
- **Arrows**: watchtower/barracks stock arrows via R5 now; the archer
  class (D1) consumes them later — stocked racks first, shooters second.

## Input sources (Ring-1 completion)

- **HERDER** (PASTURE): tends/breeds real animals in a bounded paddock,
  periodic chest-true yields (wool, eggs; slaughter feeds the butcher raw
  meat + hides). **FISHER** (FISHERY): works adjacent water, fish into the
  chest. **HUNTER** (HUNTERS_LODGE): bounded expeditions yield game meat,
  hides, FEATHERS (the fletcher's missing input), occasional mushrooms —
  the forage source the kitchen audit demands. Three professions appended
  (wire-format), three work goals, effort costs, outfits, clips per the
  polish standard.

## Honesty repairs (from specific audit findings)

- Smelter bloom math: rebalance so the smithy fed path is a TRUE ×2 by
  throughput (ticks/unit halves), proven by a ChainsGameTests-style test.
- Miner: drops via real loot semantics (ore blocks → raw metals, stone →
  cobble unless a stonecutter story is chosen — decide in-slice and
  document), exposed-face + adjacency before cutting, give-up/retarget on
  unreachable targets, idle reasons recorded (routeFailure idiom).
- MINE/WATCHTOWER ladder requirement: a bounded vertical column probe from
  the anchor (the miner audit's recommendation) instead of bending room
  semantics — shafts count, rooms stay rooms.
- Sinkless-output guard: FLOWS' acyclicity test gains a sibling: every
  Production output must have a consumer or an explicit terminal marker.
- Test honesty: TradeSmithGameTests + a real TradeMinerGameTests (drive
  the actual mining tick loop, storage-full stop); job_audit point 11
  upgraded from a string grep to requiring the trade's own test file.
- Dining hall: at MEAL, settlers prefer a stocked dining hall; eating
  there consumes real food from its chest and grants a variety bonus.

## Sequencing for the fix wave (file-ownership slices)

F1 courier routes R1/R2/R5 + isHaulable split (CourierWorkGoal + tests)
F2 tools chest-true + wear v1 (SettlerEntity equip path, Profession
   fallback, CrafterWorkGoal/goal hooks, smithy demand test)
F3 guard armor chest-true + armoury recipes (GuardRank, Production)
F4 herder/fisher/hunter (Profession/Employment — after the research
   worker releases them — three new goals + tests)
F5 miner honesty + ladder probe (MinerWorkGoal, RoomScanner-adjacent
   probe, TradeMinerGameTests)
F6 dining hall consumption + meal variety (EatFromHearthGoal split,
   Schedule, morale)
F7 balance + audit-tool upgrades (Production numbers, job_audit)
Every slice cites its audit findings; every fix lands with the test that
would have caught it.

F8 **crafter sound-contact sync** (audit: butcher §4): CrafterWorkGoal
fires soundOf at `workedTicks % period == 0` — the loop WRAP (rest pose) —
not the clip's contact beat. Fix: per-motion accent offsets (the contact
tick each catalogue section names) and add SOUND_CONTRACTS rows for all
six shared crafter clips in anim_check so the class of bug stays caught.
F9 **hide-path balance** (butcher §6.3): the rabbit→cured_hide fed path is
~4x cheaper per leather than the rough path and consumes MEAT not hide —
re-point the recipe at RABBIT_HIDE and bring the advantage into the
x1.5-x2 FLOWS band, with a test.

## Wave-2 synthesis (final four audits: farmer, mason, smelter, guard — 20260825)

Status marks: [LANDET] committed; [I ARBEID] a fix worker owns it now;
[SLICE] queued as its own slice.

**Systemic, resolved:**
- [LANDET] `Production.ready()` is need-aware (scarcest output wins, ties
  keep list order for the fed-path doctrine) — kills the sawyer/carpenter/
  weaver/fletcher starvation class AND the smithy's bloom_ingot-forever
  risk (tools at stock 0 now outrank topping up ingots). Smelter/sawyer
  gametests reconciled with the selector; weaver's proof is naturally safe
  (succeeds before wool_bolt becomes satisfiable).
- [LANDET] Miner banks real loot (`Block.getDrops` with an iron pickaxe),
  not block items — the mine→smelter chain exists at all now.
  MinerDropsGameTests pins it.

**In flight (fix workers own the files):**
- [I ARBEID] FARMER-BOOTSTRAP: first-planting on tilled ground (orphaned
  FARM_PLANT clip finally used), seed reserve on deposit, replant duration
  28t to match SOW_BROADCAST, no watering bare tiles.
- [I ARBEID] GUARD-PROGRESSION: STRENGTH trains from landed hits + patrol
  drill (the rank ladder becomes reachable by guarding), night-watch
  MORNING_WORK sleep gap, leap fizzle resolution, hostile-only splash,
  +0.5 dmg/rank in the goal.
- [I ARBEID] COURIER-R2: workshop outputs → warehouse (keep-back 8; MINE
  keep-back 0). Closes mason C2/smelter C1's stranded-output half.

**Queued slices (not yet owned):**
- [SLICE] REPAIR-1: raid-damage repair orders consume mason stone/bricks —
  the mason's first real sink (RaidDirector has the report half only).
- [SLICE] FUEL-1: firewood/charcoal upkeep for smelter/bakery/smithy
  (DESIGN.md:15's "firewood/warmth" pillar; furnaces currently run on
  nothing). Gives the lumberer's surplus a destination too.
- [SLICE] ARMOR-VISIBLE: rank armor renders on the settler model (server
  slots are set but SettlerRenderer has no armor layer — the audit calls
  it "invisible to the player"; custom model needs a bespoke layer).
- [SLICE] GUARD-3: Shield Bash (SPEARMAN) + Rally (CAPTAIN) — documented
  in GuardRank's own table, absent in code; pairs with the horn/banner
  command wheel (ROADMAP C2).
- [SLICE] FARM-COMPOST: the plaque-required composter becomes the farm's
  efficiency lever (chaff → bone meal → maintenance boost).
- [SLICE] STONE-VARIETY: mason recipes for the granite/diorite/andesite/
  deepslate the miner now genuinely banks; FLOWS' promised "cut stone".
- Doc honesty: JOB_STANDARD.md certification rows are stale three ways
  (mason reason wrong, smelter "needs GameTest" false, catalogue §2.2
  farmer trigger stale). Reconcile in the next doc pass; certification
  additions wait for VERIFY-1 suite evidence, per the smelter audit's
  warning that job_audit "ok" rows are presence-checks, not proof.
