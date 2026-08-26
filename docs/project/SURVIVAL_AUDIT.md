# SURVIVAL AUDIT — the paper half of "spill igjennom alt"

*Read-only trace of the whole progression graph, done before the live
playthrough: hearth → first lumberjack → every other building, in survival,
by hand. Every claim below is either a quote from the mod's own recipe/code
files (file:line given) or a note on well-known vanilla Minecraft 1.21
crafting facts used to evaluate a **room requirement** — Hearthstead's own
recipe folder (38 files) only prices the *Build Plan*; the physical room a
plaque scans for is furnished with plain vanilla blocks whose own recipes
live outside this repo, so those are cited as "(vanilla)" rather than with a
file:line. Does not redo `docs/project/BALANCE_AUDIT.md`'s arithmetic — cited
and built on throughout, especially Q1/Q4/Q5/Q6. As of 2026-08-26.*

## Tier table

| Tier | Unlocks | Survival cost | Grind | Verdict |
|---|---|---|---|---|
| **0** | Hearth + **3 free settlers** | 3 logs + 5 cobblestone + 1 campfire (campfire itself: 3 sticks+3 logs+1 coal, vanilla) — `hearth.json` | <1 day | **SMOOTH** |
| **0** | First lumberjack (LUMBER_CAMP) | plaque 6 iron+1 copper+3 planks (`plaque.json`) + plan paper+feather+1 log (`build_plan_lumber_camp.json`) + room: 1 crafting table+1 chest+door+light (`BuildingType.java:143-148`) — hire one of the 3 free founders, **0 recruit price** | <1 day, mostly mining the plaque's iron/copper | **SMOOTH** |
| **1** | FARMHOUSE, MINE, SAWMILL, MASON, WAREHOUSE, HOUSE/LODGING, WELL, TAVERN, BUTCHER, FLETCHER, WEAVER, TANNERY, KITCHEN, SMELTER, CARPENTER, BAKERY (rough), DINING_HALL, BARRACKS, WATCHTOWER | each: paper+feather+1-2 hand-gathered items (recipe table in Findings §F12); rooms are vanilla furniture only | 1-3 days total (a plaque per building, ~1-2 real minutes of mining each) | **SMOOTH**, but see **F8** — the food valley opens right here |
| **1** | PASTURE, FISHERY, HUNTERS_LODGE | same trivial plaque/plan/room cost as above | — | **WALL** — buildable, but **nobody can ever be hired to work them** (F1) |
| **2** | Fed-path chains (MILL→bakery, tannery's cured-hide, sawmill's beams, smelter/smithy bloom) | needs the tier-1 upstream building already staffed and producing surplus | effort-bound, 1-4 days per pairing once staffed (BALANCE_AUDIT Q4/Q5) | **SMOOTH** once two workers exist, **GRIND** solo |
| **2** | SMITHY + ARMOURY | plaque/plan trivial; **each room needs an anvil** = 31 iron ingots (vanilla: 3 iron blocks+4 ingots), **×2 buildings = 62 ingots**, entirely player-hand-mined before either building can validate | 62 iron ingots by hand — no settler industry exists yet to help | **GRIND** (F6) |
| **2** | BREWERY, INFIRMARY | plaque/plan trivial (sugar+bottle / book+bottle); **room needs a `brewing_stand`** = 3 cobblestone + **1 blaze rod** (vanilla) | a full Nether trip: obsidian, portal, a fortress, a blaze kill | **WALL** (F3) until the player leaves the Overworld |
| **3** | ARCHITECTS_STUDY + 6 research projects | plaque/plan trivial; room needs a lectern+2 bookshelves = 3 bookshelf-equivalents = 27 paper+9 leather (vanilla); every project after that costs 4 paper + 12-24 domain items, by hand, forever | ~1 day for the room; every project's materials must be **manually carried in** (F5) | **GRIND** |
| **3** | LIBRARY | plaque/plan trivial (3 books); room needs **8 bookshelves + 1 lectern = 9 bookshelf-equivalents = 81 paper + 27 leather** — the single largest material bill of any building in the mod | paper is pure hand-gathering forever (no recipe makes it); leather ~2-3 tannery-days or ~14-27 cows by hand | **GRIND** (F7) |
| **3** | SCHOOL | plaque/plan trivial (2 books) | modest | **SMOOTH** to build, **INERT** once built (F2) |
| **3** | MARKET | plaque/plan needs paper+paper+feather+book+**1 emerald** (`build_plan_market.json`) | emerald has no guaranteed hand-source (no village nearby / no mountains biome / no loot found) | **WALL**, biome/seed-dependent (F4) — and **INERT** even if built (F2) |

**Legend.** WALL = a required ingredient or block has no survival source at
all, or none reachable without content outside this progression (the
Nether). GRIND = reachable, but the cost is a real multi-hour-to-multi-day
detour with nothing else productive happening. INERT = the building
constructs and the plaque goes `LINKED_VALID`, but nothing in the codebase
ever reads that building's presence for any effect — a fourth outcome the
brief's three verdicts don't have a word for, so it gets one here.

---

## Findings, severity order

### F1 — Three of FLOWS.md's own six "Ring 1" sources cannot ever be worked (WALL)

`FLOWS.md:26-28` names PASTURE, FISHERY and HUNTERS_LODGE, beside FARMHOUSE/
LUMBER_CAMP/MINE, as the settlement's foundational, dependency-free resource
buildings. But `Profession.java` has **exactly 22 values** (`NONE` through
`ARMOURER`) and `Employment.TRADES` (`Employment.java:96-150`) maps exactly
those 22 — there is no `SHEPHERD`, `FISHERMAN` or `HUNTER` anywhere in the
enum, so `Employment.hire()` refuses every attempt on these three buildings
with `no_trade` (`Employment.java:458-460`) unconditionally, forever. A
player can craft the plan, hang the plaque, build the room to spec, and the
plaque will read `LINKED_VALID` — and the building will sit empty, because
there is no worker-goal or Production entry that could ever animate someone
in it (confirmed: zero `Fishery|Pasture|Hunters_lodge`-named work-goal files
exist under `entity/ai/`). This is different in kind from the MILL/BREWERY/
ARMOURY gap BALANCE_AUDIT's follow-up found and closed (§F9 below) — those
had a Production table waiting for a trade to be wired on top; these three
have **no worker code at all**, wired or not. A third of Ring 1 is
architecture with nothing behind it.

### F2 — Four more buildings are constructible and functionally void (INERT)

Grepping the whole `src/main/java/com/hearthstead` tree for `BuildingType.X`
outside `BuildingType.java` itself, `RoomScanner.java` (generic) and
GameTests:

- **INFIRMARY** — zero hits. FLOWS.md claims "herbs → healing"
  (`FLOWS.md:47`); no healing mechanic reads this building anywhere.
- **SCHOOL** — zero hits. FLOWS.md claims "school + library → learning speed
  for the young" (`FLOWS.md:48`); no learning-speed mechanic exists in the
  codebase at all (checked for `learningSpeed`/`LEARNING`/`childLearn` —
  nothing).
- **MARKET** — zero hits. FLOWS.md claims "surplus → sølvmark"
  (`FLOWS.md:49`); no trade-currency or market goal exists.
- **WELL** — one hit, `BuildPlanRecipeGameTests.java:166`, and it only
  asserts the *Build Plan crafts*, not that the building does anything once
  built. FLOWS.md claims "water → kitchen/farm cadence" (`FLOWS.md:50`); no
  such cadence hook exists.

By contrast **DINING_HALL** and **LIBRARY** — the other two buildings with
no hireable trade — are real: `Costs.java:229-233` (recruit discount),
`Costs.java:250-253` (mayor-feast discount) and `Schedule.java:130`
(meal/social routing) for the hall; `Costs.java:240-243` (research discount)
for the library. So this is not "hub buildings never need code," it is
specifically these four that were never finished.

### F3 — BREWERY and INFIRMARY are gated behind a Nether trip, undocumented anywhere (WALL)

`BuildingType.java:122-128` (BREWERY) and `:195-201` (INFIRMARY) both
require a placed `Blocks.BREWING_STAND` in the room. Vanilla's own
`brewing_stand` recipe is 3 cobblestone + **1 blaze rod** — obtainable
nowhere but the Nether (a fortress, a blaze kill). Neither building's own
Build Plan needs anything Nether-related (`build_plan_brewery.json`:
sugar+glass_bottle; `build_plan_infirmary.json`: book+glass_bottle), so a
player reads both as "hearthside tier, 1 paper" cheap (COSTS.md's own tier
table, `COSTS.md:53-54`, lists brewery under "skilled" and infirmary under
"civic" — no hint of a dimension change) and only discovers the wall at the
room-scan step. I grepped every `.md` in `docs/project/` for
`blaze|nether|brewing_stand` — **zero hits anywhere**. This is a real,
previously undocumented gate on two full buildings.

### F4 — MARKET's Build Plan needs an emerald a hand-only player may not have (WALL, seed-dependent)

`build_plan_market.json`: paper, paper, feather, book, **`minecraft:emerald`**
— the only rare/non-craftable vanilla item across all 33 Build Plans (every
other recipe checked resolves to logs/stone/wool/wheat/meat/string/flint/
iron/copper, all directly hand-gatherable). Emerald has no crafting recipe;
it comes only from emerald ore (windswept-hills/mountains biome only),
villager trading, or exploration loot (buried treasure, ruins, ancient
cities). A player who spawned away from mountains and villages has no
guaranteed path to this building at all — and per **F2**, even a market that
gets built does nothing yet.

### F5 — Research materials are never courier-delivered; every project is hand-carried, forever

`CourierWorkGoal.java:1597`: `if (!crafter.valid || (!Production.produces(crafter.type) && !burns))` —
CRAFTER_RESTOCK only services a building with a `Production` recipe table
(or fuel). `Production.of(BuildingType.ARCHITECTS_STUDY)` is empty (research
has no recipe table by design — `Employment.java:129-134`'s own comment).
So no courier route will ever carry paper or a research domain sample to
the study. `Research.sourcesFor` (`Research.java:345-361`) pays from the
study's own chest **if one exists there**, else falls back to the hearth's
larder — but nothing auto-fills either: the hearth only receives FOOD via
`FOOD_DELIVERY` (`FLOWS.md:64`), never paper or raw materials. Every one of
the six research projects (`ResearchProject.java:32-53`) — 4 paper + 12-24
domain items each — is a manual player errand from world/warehouse to the
study, every single time, independent of how mature the settlement's
logistics otherwise are.

### F6 — Two anvils cost 62 iron ingots, mined by hand before any settler industry exists

`BuildingType.java:178-185` (SMITHY) and `:232-237` (ARMOURY) each require
1 anvil. Vanilla's anvil recipe is 3 blocks of iron (27 ingots) + 4 ingots =
31 ingots each. Both rooms must be *already valid* before either building
can hire a smelter/smith/armourer, so this iron is necessarily mined and
smelted by the player's own hand, pre-settlement-industry: **62 iron
ingots** just to stand up the two buildings the entire tool/armor chain
runs through, before a single tool or piece of armor is forged. This sits
on top of the armor materials themselves (a full iron Captain kit is
another 24 ingots per `Production.java:298-328`'s own commentary).

### F7 — The library's material bill is the largest in the mod, and half of it is uncraftable by settlers

`BuildingType.java:246-251`: LIBRARY needs 8 bookshelves + 1 lectern.
Vanilla's lectern recipe itself consumes a bookshelf, so the true total is
**9 bookshelf-equivalents**. Each bookshelf is 6 planks + 3 books; each book
is 3 paper + 1 leather. Total: **81 paper + 27 leather** (+ ~54-58 planks).
Checked `Production.java` end to end: **no recipe anywhere outputs PAPER or
BOOK** — paper can only ever come from the player's own hand at a crafting
table (sugar cane), regardless of settlement maturity. Leather can be
settler-produced (TANNERY, 10-12/day fresh worker per BALANCE_AUDIT Q4), so
roughly half this bill shrinks with a working tannery; the paper half never
does.

### F8 — The recruit-vs-food valley, located: population 5-8, days 1-3, before a mill exists

BALANCE_AUDIT Q1 (`BALANCE_AUDIT.md:69-85`) already proved a rough-path-only
bakery is short even at population 6, and Q6 (`:308-336`) already proved
recruitment can add a settler every 50-280 real seconds once a tavern is
staffed and morale/food clear their bars. Tracing that onto this audit's own
tier table: TAVERN is a **Tier 1** building (trivial plan: paper+feather+2
bread, `build_plan_tavern.json`) — reachable in the first survival day,
**before** MILL (Tier 2, needs a fed-path pairing to actually beat the
rough bakery) is typically built. `Settlement.capacity() = 3 +
validBedCount()` (`Settlement.java:122`, cited by BALANCE_AUDIT) means beds,
not food, cap growth — so the natural play order (found → house a few
recruits → staff a tavern for the recruiting boost) drives population past
6 while food is still running on the rough bakery alone. **This is the
valley**: Tier 1's own most attractive early building (the tavern) is what
triggers the population growth that Tier 1's own food building cannot
support.

### F9 — CORRECTION to BALANCE_AUDIT's own top-line: the armoury chain is now closed

BALANCE_AUDIT's ranking-table finding 1 ("Guard/armoury armor has no
producer anywhere," ranked **BROKEN**) is stale as of this read.
`Production.java:341-349` now carries all 8 armor recipes (confirmed: the
file's own follow-up note at `BALANCE_AUDIT.md:416-453` already flagged
this), **and** `Employment.java:144-150` now maps
`BuildingType.ARMOURY → Profession.ARMOURER` (the "half two" gap the
follow-up note left open — "ARMOURER-1 is closing it" — has landed). I
independently confirmed the courier side too, which the follow-up note did
not check: `CourierWorkGoal.java:1597`'s restock gate is keyed on
`Production.produces(type)`, generic across every building, so ARMOURY
qualifies automatically now that its recipe table exists — no bespoke
MILITARY-OUT tier was needed for input restocking. The chain (recipe +
trade + restock) is genuinely closed. Only the material cost (F6) and
FLOWS.md's separately-noted absence of an *output* MILITARY-OUT tier (moot,
since guards/archers self-serve straight from the armoury's own chests)
remain.

### F10 — COSTS.md is stale on whether research is charged

`COSTS.md:107-108`: "Research: ... still NOT charged anywhere ...
`Costs.PriceKey.RESEARCH` only reserves the row." False as read today:
`Research.start()` (`Research.java:186-220`) checks every line of
`project.costs()` against the study's chests and takes them atomically
before starting, discount-adjusted through `Costs.discountsFor`. Same shape
of staleness BALANCE_AUDIT finding 9 already caught in
`PLAN_CIRCULATION.md` — a second doc, same night, drifted the same way.

### F11 — BALANCE_AUDIT's dead-end outputs reconfirmed, one made worse by re-reading

ALE, WOOL_BOLT, WHITE_BANNER (BALANCE_AUDIT finding 5) are unchanged: still
zero consumers anywhere. BARREL is worth restating more sharply than the
original finding did: `Production.java:225-226` makes it from
`OAK_PLANKS×7` or `TIMBER_BEAM×2`, but `hearthstead:barrel` does not exist —
this recipe's output IS `Items.BARREL`, plain vanilla, directly
player-craftable at a crafting table (6 planks + 2 slabs) with **no plaque,
no building, no settler at all**. The carpenter recipe does not unlock
anything a player couldn't already do by hand in ten seconds; it only
spends a carpenter's effort budget on a good nobody, settler or player,
ever needs a settlement-grown supply of.

### F12 — Full trade roster: 22 of 33 buildings are hireable, 11 are not

| No trade, and fine by design (passive housing/utility or a valid-room hub effect) | No trade, and broken (F1/F2) |
|---|---|
| HOUSE, LODGING, WELL* | PASTURE, FISHERY, HUNTERS_LODGE (F1) |
| DINING_HALL, LIBRARY (real hub effects, F2) | SCHOOL, MARKET, INFIRMARY (F2) |

\* WELL claims a cadence effect in FLOWS.md that does not exist (F2) — listed
here because at least its passive "water source" framing is plausible by
design, unlike the other three.

---

## What the live playthrough should try to break

1. **The MARKET emerald (F4).** Does the actual generated world put a
   village or a mountains biome within a reasonable radius, or is this a
   real dead stop on this specific seed?
2. **Wheat growth rate vs. the food valley (F8).** BALANCE_AUDIT left crop
   growth explicitly UNCLEAR (vanilla random-tick, not overridden). Only
   real play can say whether a rough bakery starves a population-6 village
   before a mill gets built, or whether the player naturally builds the
   mill first and the valley never bites.
3. **Does the player actually walk into the Nether wall (F3)?** Following
   "get a lumberjack first, then work outward," does BREWERY/INFIRMARY get
   attempted early (and stall confusingly at the room scan) or skipped
   entirely without the player noticing content is missing?
4. **Recruitment pace, felt rather than computed.** BALANCE_AUDIT's 50-280
   real seconds/settler is a formula; does it read as "delightfully fast"
   or "alarmingly fast, my food can't keep up" at the table?
5. **Does the fed-path iron chain (smelter↔smithy, ×1.67 per
   BALANCE_AUDIT Q5) survive real courier travel time**, or does distance
   between buildings eat the theoretical advantage before it reaches the
   armoury?
6. **Research errands (F5), felt.** Is manually carrying 4 paper + a domain
   sample to the study six times a minor errand or a real chore, given
   actual building layout distances in a played village?
7. **The four inert research-tick projects (BALANCE_AUDIT finding 2).**
   Does a player who finishes BEDRE_GJÆR/TORRSETT_TØMMER/BLESTRING/GARVESYRE
   notice nothing changed, or is the effect invisible enough that it doesn't
   matter either way?
8. **Guard/archer armor reaching its post.** With F9 confirmed closed
   end-to-end on paper, does a forged piece actually walk from a
   real-world-placed armoury to a real-world-placed barracks/watchtower
   fast enough to matter in an active raid, given MILITARY-OUT still
   doesn't exist as an explicit courier tier (FLOWS.md's own open note)?

---

## Files consulted

`data/hearthstead/recipe/*.json` (all 38), `building/BuildingType.java`,
`building/Production.java`, `settlement/Employment.java`,
`settlement/Costs.java`, `settlement/RoomScanner.java`,
`settlement/SettlementManager.java`, `settlement/Settlement.java`,
`settlement/research/ResearchProject.java`, `settlement/research/Research.java`,
`entity/Profession.java`, `entity/GuardRank.java`,
`entity/ai/CourierWorkGoal.java`, `block/PlaqueBlockEntity.java`,
`building/PlaqueState.java`, `docs/project/FLOWS.md`, `docs/project/COSTS.md`,
`docs/project/BALANCE_AUDIT.md`, `docs/project/DECISIONS.md` (D-005, D-006),
plus whole-tree greps for `BuildingType.{WELL,INFIRMARY,SCHOOL,MARKET,
DINING_HALL}` and for `blaze|nether|brewing_stand` across every doc.
