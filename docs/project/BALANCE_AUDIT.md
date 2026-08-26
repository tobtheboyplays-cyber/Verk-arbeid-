# BALANCE AUDIT — does the numbers cohere?

*Read-only arithmetic pass across `docs/project/FLOWS.md`, `COSTS.md`,
`PLAN_CHAINS.md`, `PLAN_CIRCULATION.md` and the live source
(`Production.java`, `Fuel.java`, `Costs.java`, `Employment.java`,
`SettlerEntity.java`, `Effort.java`, `SettlerAttributes.java`, `DayPhase.java`,
`EatFromHearthGoal.java`, `CourierWorkGoal.java`, `FarmerWorkGoal.java`,
`GuardRank.java`, `SettlementManager.java`, `Settlement.java`,
`ResearchProject.java`) as of 2026-08-26. No file was edited to produce this;
every number below is either quoted from a doc, computed from a source
constant with the computation shown, or marked as not found.*

## Ranking table

| # | Finding | Rank | One line |
|---|---|---|---|
| 1 | Guard/armoury armor has **no producer anywhere** | **BROKEN** | `GuardRank.applyEquipment` withdraws `IRON_HELMET/CHESTPLATE/LEGGINGS/BOOTS` and `LEATHER_*` from chests, but zero recipe in `Production.java`, zero recipe JSON, zero other code path creates any of the 8 armor items. Guards can only ever wear what a player manually places in a chest. |
| 2 | 4 of 6 research projects are **measurably inert** | **BROKEN** | `BAKERY_TICKS/SAWMILL_TICKS/SMELTER_TICKS/TANNERY_TICKS` cut recipe ticks 15%, but effort (flat 2/batch) — not ticks — caps every recipe's batches/day, and the tick floor is never reached. A -15% tick change produces a **0-batch/day** change in every crafting building's output. |
| 3 | Fed-path multiplier claims fail their own stated band for 3 of 5 chains | **UNBALANCED** | FLOWS.md mandates ×1.5–2×; measured end-to-end the same way the doc itself measures iron: bread ×1.07, barrel ×1.29, leather ×1.06, ale ×1.36. Only iron (×1.67) actually clears the floor. |
| 4 | Recruitment can vastly outpace food production | **UNBALANCED** | A settlement can gain a settler every 50–280 real seconds (≤5 real minutes, a fraction of one 20-minute in-game day) once merely "attractive" (food ≥8 units, morale ≥60); nothing throttles this against actual food *throughput*, only against housing (`capacity() = 3 + beds`). |
| 5 | Four production outputs have **zero consumers** anywhere in the codebase | **UNBALANCED** | `ALE`, `WOOL_BOLT`, `WHITE_BANNER`, `BARREL` (both its recipes) — confirmed by whole-repo grep, no hit outside the recipe that creates them (and generic test/UI files). |
| 6 | Charcoal recipe is fuel-neutral | **UNBALANCED** | Smelter's `charcoal` (log→charcoal, 90t, cold-start exempt) converts 1 fuel-item into 1 fuel-item 1:1 (both logs and charcoal are flat "1 unit" to `Fuel.perBatch`, both stack to 64) — it spends 90 ticks of smelter time and produces no net fuel gain and no evident logistics gain. |
| 7 | Barrel's fed path gives **zero** extra output per effort | **UNBALANCED** | `barrel_beam` (fed) and `barrel` (rough) both yield exactly 1 barrel/batch. Since effort — flat 2/batch regardless of building — is the binding daily resource, the fed recipe is strictly worse: same barrels/day per carpenter, plus it now needs a sawyer's separate effort budget too. |
| 8 | Real wheat/food *production rate* | **UNCLEAR** | Bounded above by effort (20–39 harvests/day/farmer) but the realizable rate depends on vanilla's unmodified random-tick crop growth, which is not overridden in code (checked `FarmerWorkGoal.java` in full — only a probabilistic `FARM_GROWTH` research bonus exists on top of vanilla). I cannot state a real wheat/day number. |
| 9 | `PLAN_CIRCULATION.md` is stale on 3 load-bearing points | **UNCLEAR** (process, not economy) | Marks FUEL-1 and REPAIR-1 "queued/not yet owned" and MILL/BREWERY hiring an open "uncertainty" — all three are confirmed **live** in code (`Fuel.java`, `RepairWorkGoal.java` consuming `STONE_BRICKS`/planks, `Employment.TRADES` mapping both buildings). Other workers reading that doc tonight may be acting on stale status. |

---

## Q1 — Does the village feed itself?

**Consumption side (from code, not estimated):**
- `tickNeeds()` runs on a **once-a-second cadence** (`SettlerEntity.java:859`,
  `tickCount % 20 == 0`), draining hunger `-0.10`/call while working,
  `-0.04`/call otherwise (`Trait.hunger()` multiplier = 1.0 for a settler with
  no relevant trait — confirmed via `Trait.product()`'s empty-set identity).
- Working hours are `DayPhase.MORNING_WORK` (1000–5500) +
  `AFTERNOON_WORK` (7000–11500) = 9000 of 24000 ticks/day = 375 of 1200
  real seconds/day.
- Full-day drain at that mix: `375s × 0.10 + 825s × 0.04 = 37.5 + 33 = 70.5`
  hunger/day (no traits). A meal (`EatFromHearthGoal`) restores
  `nutrition × 8` and is triggered at hunger `<40` (or `<75` during the two
  daily meal/social windows) — bread (nutrition 5) restores 40,
  cooked meat/stew ~48–64.
- `EatFromHearthGoal.java`'s and `CourierWorkGoal.java`'s own comment settles
  the count precisely: **"sits down to roughly two or three meals across a
  day"** (`CourierWorkGoal.java:163-168`, the `FOOD_PER_SETTLER=4` doc). My
  independent drain arithmetic above (70.5 hunger/day ÷ ~40-64/meal ≈
  1.1–1.8) is a bit below that, consistent once the threshold-overshoot of
  the meal/social `<75` top-up rule is counted. I use the code's own **2–3
  meals/settler/day**.

**Production side, per worker (effort-bound — see Q4 for why this, not
ticks, is the real ceiling):** a *fresh* settler (STAMINA 1–15 at creation,
`SettlerAttributes.START_CAP=15`) has effort capacity 20–23, i.e.
**10–12 crafting batches/day** (2 effort/batch).

| building/path | output/batch | fresh-settler output/day |
|---|---|---|
| BAKERY rough (`bread`) | 1 bread | 10–12 bread/day |
| BAKERY fed (`bread_flour`, needs a MILL) | 2 bread | 20–24 bread/day |
| MILL (`flour`) | 2 flour | 20–24 flour/day |
| BUTCHER (cooked meat) | 1/batch | 10–12 meals/day |
| KITCHEN (stew/potato/kelp) | 1/batch | 10–12 meals/day |

(Miller's 20–24 flour/day and fed-baker's 2-flour/batch × 10–12 batches =
20–24 flour/day demand is an exact 1:1 match — a genuinely well-tuned pairing,
noted as a positive, not a defect.)

**Meals needed/day at 2–3 meals/settler:**

| population | meals needed/day | 1 rough baker | 1 fed baker + 1 miller | verdict |
|---|---|---|---|---|
| 6 | 12–18 | 10–12 | 20–24 | rough: **marginal shortfall even here**; fed: comfortable surplus |
| 12 | 24–36 | 10–12 (badly short) | 20–24 | fed: **borderline** — matches the low end, short at the high end |
| 24 | 48–72 | 10–12 (starves) | 20–24 (starves) | **needs 2–3 parallel food buildings** (bakery+mill, plus kitchen/butcher) to clear even the low end |

**The flip point:** with exactly one dedicated food-production pairing
(the best available — fed-path bakery + mill), break-even sits around
**population ≈ 10–12**; past that the village runs a structural deficit
unless the player staffs a second food chain. A rough-path-only bakery (no
mill yet — the literal state FLOWS.md itself says a young village should be
in) is **already short at population 6**, before any growth at all — a young
village's "runs on rough paths and feels busy, not broken" (FLOWS.md,
"Sequencing honesty") does not hold for food specifically once the
consumption arithmetic is run.

*Not determined:* whether the FARMHOUSE's own wheat harvest can keep either
baker path's WHEAT/FLOUR input full — that traces to vanilla's un-overridden
crop-growth random tick, see the ranking table's UNCLEAR item.

---

## Q2 — Does every chain close?

Checked by grepping every produced item across the entire `src/main/java`
tree (not just `Production.java`) for a consumer outside the recipe that
creates it, tests, or purely cosmetic references (UI icons, `BuildingType`
sample items).

**Dead ends (output nothing consumes), confirmed:**
- **ALE** — zero references anywhere outside `Production.java`/`ModItems.java`.
  `PLAN_CHAINS.md` already flags this ("not drinkable yet") but the flag is
  from the item's own author; nothing has closed it since.
- **WOOL_BOLT** — zero references anywhere, including tests. `PLAN_CHAINS.md`
  itself calls this out: "no in-table consumer yet ... waiting for a future
  outfits/market building."
- **WHITE_BANNER** — zero references outside its own recipe. Pre-existing
  (not part of SLICE CHAINS), and now doubly dead: `WOOL_BOLT` is listed
  first with a lower threshold (3 vs 6 wool), so it also wins the weaver's
  attention before banner's own recipe fires (`PLAN_CHAINS.md`
  Uncertainty #2).
- **BARREL** — both the rough (`OAK_PLANKS×7`) and fed (`TIMBER_BEAM×2`)
  recipes. FLOWS.md's own table claims carpenter "feeds: tavern, warehouse
  upkeep," but no code consumes a barrel anywhere.

**Starved head (input nothing produces), confirmed:**
- **Guard/watchtower armor.** `GuardRank.applyEquipment`
  (`entity/GuardRank.java`) withdraws real vanilla armor items —
  `IRON_HELMET/CHESTPLATE/LEGGINGS/BOOTS`, `LEATHER_HELMET/CHESTPLATE/
  LEGGINGS/BOOTS` — from the armoury's chests, then any warehouse, then the
  hearth. I grepped the whole `src/main/java` tree plus every recipe JSON in
  `src/main/resources/data/hearthstead/recipe/`: **no recipe anywhere
  produces any of these 8 items.** `Production.of(BuildingType.ARMOURY)` is
  empty (ARMOURY has no entry in the recipe map at all), and the SMITHY only
  makes tools (`axe/pickaxe/hoe/sword`), never armor. `PLAN_CIRCULATION.md`'s
  own F3 slice ("ARMOURY gets Production recipes (ingots+leather → pieces)")
  is the acknowledgment that this was meant to exist — it has not landed.
  This is FLOWS.md's own "one soft exception" (the martial chain is allowed
  real inputs) with **no low-tier alone-path** either, contradicting FLOWS'
  own stated requirement that the martial chain always keep "a low-tier
  alone-path... so a poor village can still defend itself badly rather than
  not at all." A settlement that never received player-placed armor cannot
  rank a guard past whatever bare-handed baseline exists.

**Confirmed NOT broken (checked, closes fine):**
- STONE_BRICKS/planks — consumed by `RepairWorkGoal.java` (raid repair,
  chest-true, live). `PLAN_CIRCULATION.md` marks this "[SLICE] queued," code
  says otherwise.
- Bread/cooked food/stew — consumed via the hearth (`EatFromHearthGoal`),
  routed there by `FOOD_DELIVERY`.
- Tools (axe/pickaxe/hoe/sword) — consumed via settler equip
  (`Profession.java`, `MinerWorkGoal.java`).
- Arrows/bows — consumed by `ArcherAttackGoal.java`.
- The six SLICE-CHAINS intermediates (FLOUR, MALT, IRON_BLOOM, TIMBER_BEAM,
  CURED_HIDE) all have a live consumer at the building FLOWS.md names.

**MILITARY-OUT does not exist as a courier tier.** `CourierWorkGoal`'s
`JobPriority` enum has exactly `CRAFTER_RESTOCK`, `FOOD_DELIVERY`,
`OUTPUT_COLLECTION` (and consolidation) — no military tier, matching
`PLAN_CIRCULATION.md`'s own "not yet a tier" note. This is moot for armor
specifically (guards self-serve from armoury/warehouse/hearth directly) but
it does mean smithed arrows never automatically reach the watchtower/barracks
either, regardless of the armor-producer gap above.

---

## Q3 — Is fuel solvent?

`Fuel.burns()` gates exactly four buildings: SMELTER, BAKERY, SMITHY,
BREWERY, at a flat `perBatch=1` fuel item (any log, coal, or charcoal —
`isFuel` is a **kind test, not a magnitude test**). The smelter's own
`charcoal` recipe (log→charcoal, 1:1, 90 ticks) is the sole fuel-making
recipe and is explicitly exempt from the fuel gate (the cold-start
exemption, `Production.FUEL_EXEMPT_RECIPE`).

**Net fuel balance of the charcoal recipe itself: zero.** Since `isFuel`
counts a log and a charcoal identically (each is "1 unit" toward
`perBatch`), converting 1 log → 1 charcoal changes the fuel supply by
**0 units**, while consuming 90 ticks of smelter time and one need-aware
"slot" that could otherwise have gone to `iron_bloom`/`iron`/`copper`/`gold`.
Both items also stack to 64 in vanilla, so there is no logistics win either
(the doc's own rationale, "denser, courier-friendly firewood," does not
hold under `ItemStack` semantics as coded). I could not find any reason in
`Fuel.java` or `Production.java` for this recipe to ever be worth running —
this looks like dead weight rather than the "second economic destination"
pillar 2 of DESIGN.md wants for the lumberjack's surplus.

**Fed-path fuel advantage that DOES hold, confirmed:** the iron_bloom→
bloom_ingot chain genuinely burns less fuel per ingot than the rough smelt:
3 batches' fuel (1 smelter + 2 smithy, per 4 bloom→4 ingot) = 0.75 fuel/ingot
vs. the rough path's 1 fuel/ingot — a real ×1.33 advantage, matching
`PLAN_CHAINS.md`'s own claim.

**Cold-start case:** with zero fuel anywhere, only the charcoal recipe can
run (fuel-exempt), so the settlement is never permanently deadlocked — this
part of the design is sound and verified directly in `Production.ready()`'s
fuel-gate logic.

**Not determined:** the steady-state fuel *supply* rate (logs/day from a
lumber camp) — `LUMBER_CAMP` has no `Production` entry (Ring-1 source,
gathered by a work goal I did not have budget to trace in full); I did not
find a per-day log yield constant to weigh against the four burning
buildings' combined demand (up to `4 buildings × 10-12 batches/day × 1 fuel
= 40-48 fuel/day` for fresh workers, before BREWERY's `malt` recipe — also
fuel-gated — is counted separately).

---

## Q4 — Does effort bind?

**Confirmed: effort, not ticks, is the binding constraint for every single
recipe in the table**, and by a wide margin.

- Crafting: flat **2 effort/batch** regardless of ticks or building
  (`CrafterWorkGoal.java:182`, `settler.spendEffort(2)`, once per completed
  `Production.run`).
- Capacity: `20 + STAMINA/5` (`Effort.java`). Fresh settler STAMINA is capped
  at 15 on roll (`SettlerAttributes.START_CAP`), so capacity ranges **20–23**
  at creation, rising toward **39** for a heavily-trained veteran
  (STAMINA→~99, matching the class doc's own "high thirties").
- Because effort never goes negative mid-batch but blocks a *new* batch once
  `≤0`, an odd capacity buys one extra batch: 20→10 batches, 23→12, 39→20.
- **Fresh-settler crafting range: 10–12 batches/day. Veteran ceiling: 20/day.**
- Time budget: 9000 working ticks/day. Fastest recipe in the table (80
  ticks/batch) allows 112 batches/day by time alone; the *slowest*
  (300 ticks, smithy's axe) still allows 30/day by time alone.
  **Time is never the tighter constraint — effort always binds first**,
  by a factor of 2.5–11× depending on the recipe.
- Farming: only **1** effort/harvest (`FarmerWorkGoal.harvest()`,
  `spendEffort(1)`), so a fresh farmer gets **20–23 harvests/day**
  (HARVEST_DURATION=36 ticks; time alone would allow 250/day — effort binds
  ~11× tighter here too).

**Consequence for research (see ranking table #2):** `BEDRE_GJAER`,
`TORRSETT_TOMMER`, `BLESTRING`, `GARVESYRE` each cut their building's recipe
ticks by 15% (`ResearchProject.java`, all four at `0.85F`). Since time was
never the binding constraint (every recipe's time-cap already exceeds its
effort-cap by 2.5×+), a 15% tick cut moves the time-cap further above the
already-unreached effort-cap and changes **batches/day by exactly zero**.
Concretely: BAKERY's `bread_flour` at 160 ticks allows 56 batches/day by
time; researched down to 136 ticks it allows 66/day — both numbers are
irrelevant next to the **10–12 the effort pool actually permits**. These
four research projects currently buy nothing measurable in daily output.
(`FARM_GROWTH` and `GUARD_TRAINING`, the other two research keys, are not
subject to this critique — they touch crop-growth probability and an
attribute-training rate respectively, not batch ticks.)

**Does any building's expected throughput exceed a worker's real capacity?**
No — the reverse is true everywhere: every recipe's time-affordable
batch count is comfortably above what effort allows, so no building is
"throughput-starved by the clock." The binding resource is uniformly effort,
confirmed for the full STAMINA range (low: 20 cap/10 batches; high starting:
23 cap/12 batches; trained veteran: 39 cap/20 batches).

---

## Q5 — Do prices mean anything?

**Fed-path "half the ticks" claims, re-measured end-to-end.**
`PLAN_CHAINS.md`'s own arithmetic is internally inconsistent: for the
smelter↔smithy iron pair it correctly sums BOTH buildings' ticks
("rough 200t/ingot; fed (160+2×160)/4 = 120t/ingot... ×1.67, inside the
band"). For bread, barrel, ale and leather it does **not** — it only counts
the ticks of the recipe at the *consuming* building and ignores the upstream
building's tick cost entirely, an apples-to-oranges comparison against its
own iron-row method one paragraph earlier. Re-run with the same
whole-chain method used for iron (from the shared raw material, since
one side of each pair has no upstream building and the other genuinely does):

| chain | rough (ticks/unit, from raw) | fed (ticks/unit, end-to-end, from raw) | ratio | doc's own claimed ratio | in FLOWS' ×1.5–2 band? |
|---|---|---|---|---|---|
| iron (raw→ingot) | 200 | (3raw→4bloom@160t = 40t/bloom) + 80t/ingot = **120** | **1.67×** | 1.67× | **yes** |
| bread (wheat→loaf) | 160 | (3wheat→2flour@140t = 70t/flour) + 80t/loaf = **150** | **1.07×** | "exactly half" (2.0×) | **no** |
| ale (wheat→ale) | 200 | (4wheat→3malt@140t = 46.7t/malt) + 100t/ale = **146.7** | **1.36×** | "exactly half" (2.0×) | no (close) |
| barrel (log→barrel) | (7planks needed@20t/plank=140)+260 = **400** | (2beams needed@90t/beam=180)+130 = **310** | **1.29×** | "exactly half" (2.0×) | no |
| leather (rabbit→leather) | 180 (rabbit_hide is a free drop, no building) | (2rabbit→2hide@160t=80t/hide)+90t/leather = **170** | **1.06×** | "exactly half" (2.0×) | no |

Three of four "half the ticks" claims in the design doc do not survive being
measured the way the doc's own iron entry is measured. Separately, because
Q4 established effort (not ticks) is what actually binds a worker's day, the
*economically meaningful* multiplier is output-per-batch at the consuming
building, not ticks-per-unit — under that lens bread and leather genuinely
hit ×2 (their fed recipe yields 2 units/batch vs rough's 1, paid for by a
**separate** building's worker, so it costs the consuming worker nothing),
but barrel hits exactly **×1** (see ranking table #7 — same 1 barrel/batch
either way) and ale's true multiplier drops to ≈**×1.2** (its upstream
malting step shares the *same* brewer's effort pool as the brewing step,
unlike the other three pairs). Two genuinely different metrics, two
genuinely different sets of winners and losers — the doc states only one
number and gets neither metric right for 3 of 5 pairs.

**Build-plan price ledger.** Verified: `src/main/resources/data/hearthstead/
recipe/` holds exactly 33 `build_plan_*.json` files, matching COSTS.md's
"all 33 registered BuildingTypes are craftable" claim. I did not diff every
ingredient list against COSTS.md's tier table (33 recipes × cross-checking
each against the tier prose was out of budget) — flagging as **not fully
verified**, though the count and the sampled entries I did read (ARMOURY's
sample = `IRON_CHESTPLATE`, matching COSTS.md's "martial" row) were
consistent.

**Recruit price against production capacity.** Base 4 bread + 8 planks,
floor (both discounts) 2 bread + 4 planks (`Costs.java`). Against even the
*weakest* rough-path baker (10 bread/day) and any sawyer (planks recipe:
1 log→6 planks/batch, so 8 planks costs under 2 batches), the full price is
earned in well under one in-game day once a single bakery and single sawmill
exist. This is consistent with COSTS.md's own "a rich village barely
notices" language, but it means the recruit price is **not** the throttle on
population growth once minimal production exists — the throttle described
in COSTS.md ("a young settlement genuinely has to wait and stock up first")
only bites in the earliest hours before any bakery/sawmill is staffed at
all; after that the price is close to free relative to production capacity.
See Q6 for why this matters more than it first appears.

---

## Q6 — Where does the player's time go?

**Recruitment is not the bottleneck — it is dramatically faster than food
production can track.** `SettlementManager.tickRecruitment` runs on a
**one-second cadence** (comment at `SettlementManager.java:196`). While
"attractive" (population < capacity, hearth food-cache ≥8 units, average
morale ≥60), the recruit gauge gains 1–4/second (base 1–2, +1 unstaffed
tavern / +2 staffed tavern) against a target randomized 200–280. **Time to
fill: 50–280 real seconds** — at most ~4.7 real minutes, a small fraction of
one 20-minute in-game day (1200 real seconds). A waiting guest then has
`GUEST_PATIENCE_TICKS = 60,000` ticks (2.5 in-game days, doubled with a
staffed innkeeper) to be paid — ample slack once the settlement can afford
the price at all (see Q5: trivial once one bakery+sawmill exist).

Population is only hard-capped by housing: `Settlement.capacity() = 3 +
validBedCount()` (`Settlement.java:122`). Nothing in `tickRecruitment` reads
food *production rate* — only the hearth's current cached stock (`foodCache
≥8`), a one-time snapshot, not a throughput check.

**Consequence:** a settlement that keeps a tavern staffed and morale above
60 can add a new settler roughly every 1–5 real minutes for as long as beds
exist — i.e., population could plausibly **triple or quadruple within the
first in-game day** — while, per Q1, a single food-production pairing tops
out around population 10–12 before running a structural deficit. **The
gating resource on "days to self-sustaining" is not recruiting or gold-cost
at all — it is whether the player staffs food production (and housing) fast
enough to keep pace with a recruitment system that has almost no brake of
its own.** A village that recruits eagerly and staffs food/beds reactively
is the exact shape of village that starves not from bad luck but from the
economy's own default incentives (a staffed tavern is cheap and immediately
useful; a second bakery+mill is a bigger, less obviously "next" investment).

I could not produce a single "N days to self-sustaining" figure — it
depends on player staffing order, which is not determined by the code (per
D-013, hiring is a player-accepted suggestion, never automatic) — but the
*shape* of the answer is: population growth is effectively unbounded by
food math for as long as beds and a live tavern exist, so the honest
framing is "self-sustaining" is a staffing choice the player must make
deliberately and early, not an outcome the current numbers steer toward on
their own.

---

## Files consulted (for completeness)

`docs/project/FLOWS.md`, `docs/project/COSTS.md`, `docs/project/
PLAN_CHAINS.md`, `docs/project/PLAN_CIRCULATION.md`,
`hearthstead-neoforge/src/main/java/com/hearthstead/building/Production.java`,
`.../building/Fuel.java`, `.../building/BuildingType.java`,
`.../settlement/Costs.java`, `.../settlement/Employment.java`,
`.../settlement/Settlement.java`, `.../settlement/SettlementManager.java`,
`.../settlement/DayPhase.java`, `.../settlement/research/ResearchProject.java`,
`.../settlement/research/ResearchKey.java`,
`.../entity/SettlerEntity.java`, `.../entity/Effort.java`,
`.../entity/SettlerAttributes.java`, `.../entity/Trait.java`,
`.../entity/GuardRank.java`,
`.../entity/ai/CrafterWorkGoal.java`, `.../entity/ai/EatFromHearthGoal.java`,
`.../entity/ai/CourierWorkGoal.java`, `.../entity/ai/FarmerWorkGoal.java`,
`.../entity/ai/RepairWorkGoal.java`,
plus whole-tree greps for consumers of ALE/WOOL_BOLT/WHITE_BANNER/BARREL/
STONE_BRICKS/armor items, and a listing of
`src/main/resources/data/hearthstead/recipe/build_plan_*.json`.

---

## Appendix — the animation catalogue's 40 unbuilt entries, classified

*Added 2026-08-26 by the worker that authored the trade idles, because a
catalogue listing 40 animations the mod does not have is a document that
cannot be trusted about the ones it does.*

**Stale (3) — superseded in the text, never deleted from the file:**
- `SMITH_HAMMER`, `SMITH_BELLOWS` (§9.1, §9.2). §18's own intro says these
  are superseded by `HAMMER_ANVIL` and `STOKE`, both of which are built. The
  old headings were simply never removed.
- `COOK_CHOP_VEG` (§7.1). §18 said it would be "absorbed into `CLEAVE` when
  the kitchen's own clips land." `COOK_STIR` landed; the absorption did not.
  **This one is a real product gap, not a documentation one:**
  `Employment.java` hard-wires `COOK` to `WORK_STIR` and never to
  `WORK_CLEAVE`, so the cook has no chopping motion at all and the catalogue
  describes a plan rather than a thing under a new name.

**Genuinely planned, not built (37).** Every one ties to a numbered phase in
the catalogue's own roadmap, and in each case spot-checked, the underlying
*gameplay system* does not exist either — this is not art lagging behind
mechanics:
- **A2, logistics and livelihood (9):** `INN_POUR`, `INN_SERVE`, `INN_GREET`,
  `COOK_SERVE_MEAL`, `EAT_AT_TABLE`, `SOCIAL_TALK`, `SOCIAL_LISTEN`,
  `REACT_HUNGRY`, `REACT_EXHAUSTED`. No served-meal or social-sit interaction
  exists on `InnkeeperWorkGoal` or `EatFromHearthGoal`; there are no
  `REACT_*` trigger hooks anywhere.
- **A3, the raid vertical (19):** the horn, rally and militia clips, the
  shelter/cower set, the downed-and-carried set, the healer's bandage, revive
  and herb clips, mourning, `REACT_STARTLE`, `REACT_BLESSED`, `GIFT_ACCEPT`.
  Worth noting against the gap: a *partial* raid system already exists
  (`RaidDirector`, `RaidBroadcast`, `RepairWorkGoal`, a raider model), so A3
  is further along than this list alone suggests — it is specifically the
  horn, militia, downed and healer mechanics that are unwired.
- **B1, depth (5):** `MINE_HAUL_ORE`, `SMITH_QUENCH`, `EMERGENCY_BUCKET`,
  `REACT_SHIVER`, `CAPTIVE`.
- **B2, the living world (4):** `SCRIBE_WRITE`, `SCRIBE_TEACH`, `CHILD_PLAY`,
  `COUPLE_GREET`.

The useful conclusion is that the catalogue is honest about the future and
untidy about the past: nothing here is a clip someone built and lost track
of. Deleting the three stale headings, and either building the cook's chop or
striking it, would make the file's own count mean what it says.

---

## Follow-up: finding 1 closed in two halves, and the second half was hiding

**2026-08-26 06:15Z.** Finding 1 said the guards' armour had no producer. It
had two causes, and fixing the obvious one exposed the other.

**Half one — the recipes.** `Production.of(ARMOURY)` now holds eight recipes,
leather and iron across all four slots, from tannery leather and smelter
ingots. Material counts copy vanilla's own crafting exchange rate (5 / 8 / 7 /
4 for helmet / chestplate / leggings / boots), and iron is priced at exactly
the smithy's own sword rate of 130 ticks per ingot while leather runs at 80.
That gap compounds with the upstream gap already in the table, so a full
leather Veteran kit costs 1920 ticks against a Captain's iron 3120 — a real
ladder rather than a relabelling. All eight outputs are terminal sinks, so the
table gains only leaf edges and `noValueMintingCycleInProductionTable` covers
them automatically.

The audit undercounted, and the worker caught it: `GuardRank` needs **eight**
distinct pieces, not seven — `LEATHER_LEGGINGS`, the Veteran's piece, was
missed. Covered now.

**Half two — nobody can be hired to make any of it.**
`Employment.tradeOf(BuildingType.ARMOURY)` is `Profession.NONE`. `TRADES` has
22 entries and the armoury is not among them, so `Employment.hire()` refuses
with `no_trade`. The recipes run in the new tests only because those tests
drive `Production` directly, which is this repo's established idiom for
proving a recipe table. In actual play the armoury remains a building nobody
can work.

This is worth recording as its own lesson rather than folded into finding 1.
The audit asked "does anything produce this item?" and got a clean answer.
The question it did not ask was "can anyone be employed to run the thing that
produces it?" — and a recipe table with no trade behind it looks identical to
a working one from every angle except a player trying to use it. The same
shape of gap existed for the mill and the brewery before MILLER and BREWER
landed. **A production chain is not closed until someone can be hired at every
link in it**, and that is the check this audit should have run and did not.

ARMOURER-1 is closing it.
