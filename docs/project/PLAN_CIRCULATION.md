# PLAN_CIRCULATION — making the economy true

*Master fix architecture synthesized from the 17-trade audit wave
(2026-08-25). The audits agree on one diagnosis: production, presentation
and tests are strong, but GOODS DO NOT MOVE and EQUIPMENT IS MINTED. This
plan makes FLOWS.md true instead of aspirational. Fix workers take slices
of this document under strict file ownership.*

## The routes this plan adds (names, not numbers)

*Route numbering was retired after Byggherre-dom #3 krav 11: this document
numbered R1-R5 and `FLOWS.md` numbered 1-5, and the two lists disagreed, so
"route 5" named the armoury leg here and the warehouse->hearth food leg
there. `FLOWS.md` now holds the one canonical NAMED route map; this section
only says what this plan adds to it. Names match
`CourierWorkGoal.JobPriority` where a tier exists.*

**SOURCE-OUT** (new): couriers collect from Ring-1 building chests (mine
today; fishery/pasture when staffed) -> warehouse. Without it the miner's
stone is decorative (audit: dead-end output, mine chests). The mine half
shipped inside OUTPUT_COLLECTION; the gathering buildings are still to come.

**FOOD_DELIVERY** (new, shipped): prepared food moves TOWARD the hearth and
dining hall only -- `isHaulable` split into material-haulage vs
food-delivery; bakery/kitchen/butcher cooked output -> hearth (and dining
hall). Preserves D-A2a-1's intent (food never drains AWAY from the hearth)
while ending the audit's C1, "nobody ever eats what the cook makes".

**CRAFTER_RESTOCK** and **WAREHOUSE_CONSOLIDATION** already existed.

**MILITARY-OUT** (new, not yet a tier): arrows/armor/tools -> armoury,
barracks, watchtower chests, so smithed goods physically arrive where they
are consumed.

## Consumption becomes chest-true

- **Tools**: on hire, the settler TAKES their trade's iron tool from the
  smithy/warehouse chain if one exists (item leaves the chest); otherwise
  they carry their own crude tool (wooden/stone projection — honest
  "brought from home", visibly worse). Profession.tool() becomes that
  fallback only.
- **Tool wear v1**: work actions damage the held tool; a broken tool drops
  the settler to the crude fallback at ~70% work speed until a smithed
  replacement arrives via MILITARY-OUT/restock. The smithy becomes the tempo dial
  FLOWS promises — because demand is real.
- **Guard armor**: GuardRank sets the CEILING; the ARMOURY chain sets
  availability. applyEquipment consumes real pieces (armoury/warehouse) up
  to rank; the in-code comment that already claims this becomes true.
  ARMOURY gets Production recipes (ingots+leather → pieces). **[LANDET
  2026-08-26, ARMOURY-2]** — see the F3 status entry below for what shipped
  and what is still open.
- **Arrows**: watchtower/barracks stock arrows via MILITARY-OUT now; the archer
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

F1 courier routes SOURCE-OUT/FOOD_DELIVERY/MILITARY-OUT + isHaulable split (CourierWorkGoal + tests)
F2 tools chest-true + wear v1 (SettlerEntity equip path, Profession
   fallback, CrafterWorkGoal/goal hooks, smithy demand test)
F3 guard armor chest-true + armoury recipes (GuardRank, Production)
   [LANDET 2026-08-26 -- see the wave-2 status entry below; GuardRank's
   half shipped earlier, Production's half (this slice) shipped
   2026-08-26, and it surfaced a THIRD gap neither audit named]
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

**Landed:**
- [LANDET 2026-08-26, ARMOURY-2] **F3, the Production half**: `Production.of(ARMOURY)`
  gained eight recipes — LEATHER_HELMET/CHESTPLATE/LEGGINGS/BOOTS and
  IRON_HELMET/CHESTPLATE/LEGGINGS/BOOTS, all from LEATHER (tannery) or
  IRON_INGOT (smelter), the two goods this economy already produces. Note
  for the audit trail: GuardRank's own equipment table actually names
  EIGHT distinct items, not the seven BALANCE_AUDIT.md finding 1 listed —
  LEATHER_LEGGINGS (VETERAN's own piece) was missing from that count too,
  and is covered here. Material counts anchor to vanilla's own
  helmet/chestplate/leggings/boots exchange rate (5/8/7/4, same on both
  tiers); tick cost anchors to the smithy's own register (iron at 130
  ticks/ingot, the smithy's sword rate; leather deliberately cheaper at 80
  ticks/leather) so the ladder holds by tick cost AND by upstream material
  cost, not just by fiat — `ArmouryGameTests`
  (`theArmouryPricesIronMeaningfullyAboveLeatherPerPieceAndPerKit`) checks
  the ladder off the live table. Chest-true proof: `ArmouryGameTests`
  (e)/(f), the direct `Production.ready`/`Production.run` idiom
  `ChainsGameTests` already uses for every other recipe table — real
  materials into a real chest, real armour out, inputs gone. Acyclic: every
  new output is a sink (never an input to any recipe anywhere in the
  table), so this only adds leaf edges off LEATHER/IRON_INGOT; the existing
  static DFS proof (`ChainsGameTests#noValueMintingCycleInProductionTable`)
  covers it automatically since it iterates every `BuildingType`.
  **`ArmouryGameTests`'s fixture was one of the ~23 missing-plaque
  fixtures FLAKE-2 fixed (KF-021); that fix landed first and this slice's
  new tests were written against the corrected `GameTestFixtures.register`
  helper from the start, so no collision.**
- [LANDET 2026-08-26, ARMOURY-3] **The THIRD gap, closed**: the armoury
  now has a trade. `Profession.ARMOURER` (id 22) joins right after `ARCHER`
  — hands free like every other crafting trade (CHAINS-1's convention),
  identity colour `0x6E7A8A`, a cool steel-blue distinct from every colour
  already in use. `Employment.TRADES.put(BuildingType.ARMOURY,
  Profession.ARMOURER)` makes `Employment.hire()` accept a worker instead
  of refusing with `no_trade`; `motionOf` gives the trade
  `SettlerActivity.WORK_HAMMER` — an armourer hammering plate at an anvil
  IS a smith hammering a blade at one, so this reuses the smithy's own
  HAMMER_ANVIL clip rather than authoring a bespoke one (the same call
  already made for INNKEEPER/SCHOLAR/MILLER/BREWER/ARCHER), which means
  `soundOf`/`soundPeriodOf`/`soundContactOf` (ANVIL_RING at contact tick 9)
  follow for free since those three key off the motion, not the trade —
  no separate table entries needed; `trainedBy` joins the smith/mason/
  smelter/lumberer/miner group under `Attribute.STRENGTH`. The idle gate
  in `SettlerEntity#setupAnimationStates` adds `ARMOURER` to
  `idleForgeState` alongside `SMITH`/`SMELTER` (no new clip, per the
  fourteen-idle set's own invariant: one profession, one gate). The outfit
  (`gen_settler.py`) is bare-headed with an ember-toned apron and gauntlets
  — deliberately NOT a hood or helm, which would need
  `SettlerModel#setupAnim`'s `hood.visible` switch (client/model, outside
  this slice's file ownership) extended, the exact bug that switch's own
  header comment already documents seven trades once hit; distinct from
  the smith (apron/iron + bracers/leather) and every other bare-headed
  apron trade by both ramp family and part combination, checked pairwise
  per the archer/scholar doctrine. Both lang files carry
  `hearthstead.profession.armourer` (`nb_no`: "Rustningssmed", matching the
  `-smed` family `Smed`/`Smeltemester` already use); `validate_assets.py`
  is 850/850. Proof: `ArmouryGameTests#aHiredArmourerActuallyForgesAHelmetIntoTheArmouryChest`
  goes through `Employment.hire()` and the settler's own `CrafterWorkGoal`
  — not `Production.run()` directly, the shape (e)-(g) in that file
  deliberately use to pin the recipe table — so it is the one proof in the
  file that a PERSON can be put to work at this building at all, and it
  was red (hire refused `no_trade`) before this slice.
- **Still open, present tense**: MILITARY-OUT itself — the courier route
  that would carry smithed goods (arrows/armour/tools) OUT to the armoury,
  barracks and watchtower chests — remains unbuilt. `CrafterWorkGoal`
  reads only the armoury's OWN chest (D-007, "a building works alone"), so
  the armourer above works fine standing alone; what is still missing is
  the delivery leg TO the barracks/watchtower that would put that armour
  where `GuardRank.applyEquipment` looks for it without a player hand-
  carrying it there. That is `CourierWorkGoal` territory (F1), not this
  slice's file ownership.

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
