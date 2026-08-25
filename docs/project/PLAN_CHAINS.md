# PLAN_CHAINS — SLICE CHAINS, six intermediate goods

*Worker W14. Owns `Production.java`, `ModItems.java`,
`tools/gen_blocks_items.py`, the item models/recipes it adds, and
`ChainsGameTests.java`. Everything below is scoped to those files; the
cross-file gaps this slice surfaces (there is one real one) are called out
under Uncertainties rather than fixed here.*

## Governing documents, in the order they actually bound this slice

1. `docs/project/DECISIONS.md` D-007 (a building works alone; a chain
   multiplies, never gates) and D-008 (six intermediate goods, only where
   vanilla has no equivalent) — the original brief.
2. **`docs/project/FLOWS.md`**, published by the coordinator mid-task as
   binding design authority and explicitly overriding the item SET (not the
   D-007/D-008 *rules*, which FLOWS.md restates and operationalizes). FLOWS
   names the six items actually implemented here: **FLOUR, MALT, IRON_BLOOM,
   TIMBER_BEAM, CURED_HIDE, WOOL_BOLT** — different from D-008's original
   six (flour, cloth, tool hafts, ale, cured meat, prepared meals). This
   document follows FLOWS.md; D-008's item list is superseded for this
   slice (D-008's *rule* — few items, real chests, vanilla reused where it
   exists — is unchanged and still honoured).

## The six items, and why each one

Every item sits on exactly one **fed-path edge**: a second recipe on a
building that already has a rough (alone) recipe, listed FIRST in
`Production`'s table so it is preferred once the ingredient exists, with the
rough recipe left completely untouched underneath it. That is D-007 as data,
not as a comment.

| item | made by | feeds | why this pairing |
|---|---|---|---|
| **FLOUR** | Mill (wheat) | Bakery | The one pairing every document agrees on. Mill had ZERO recipes before this slice — flour is what gives the building type a reason to exist. |
| **MALT** | Brewery (wheat) | Brewery's own ale recipe | Brewery also had ZERO recipes. FLOWS.md's row names the fed edge as "malt→ale"; the coordinator's clarifying message pinned the exact shape to wheat→malt→ale, self-contained in one building (the same shape Mason's existing cobblestone→stone→stone_bricks chain already uses). |
| **IRON_BLOOM** | Smelter (raw iron) | Smithy (finishes bloom into ingot) | FLOWS.md names this "smelter↔smithy" explicitly — the one item that is genuinely cross-building on BOTH legs (smelter makes the bloom, smithy finishes it into an ingot smithy can then use in its own existing tool recipes). |
| **TIMBER_BEAM** | Sawmill (oak log) | Carpenter (barrel) | FLOWS.md: "sawmill→carpenter". A second, bulkier product from the same log the plank recipes already use, feeding a cheaper/faster carpenter barrel. |
| **CURED_HIDE** | Butcher (rabbit) | Tannery (leather) | FLOWS.md: "butcher→tannery". Butcher had no hide output at all before this slice; tannery already existed (rabbit_hide→leather) so this is a genuine second building feeding an existing one. |
| **WOOL_BOLT** | Weaver (white wool) | *(no in-table consumer yet)* | FLOWS.md: "weaver→outfits/market" — an upstream good, the same "pure upstream, no fed/rough split of its own" shape as flour, waiting for a future outfits/market building. Not a gate on anything today; purely additive. |

### The one addition beyond FLOWS.md's six: ALE

FLOWS.md's brewery row and its acyclicity section both talk about **ale**
("ale is terminal (drunk)") as if it already exists, but it is not one of
the six named CHAINS items, and vanilla has no ale-equivalent item the way
it already has BREAD for flour or IRON_INGOT for iron_bloom. The malt→ale
edge has to end *somewhere* concrete for the recipe to type-check and mean
anything, so `ModItems.ALE` was added as a seventh, terminal, plain `Item` —
no new mechanic, not drinkable yet, exactly the role BREAD already plays for
flour. Flagged here per the coordinator's own instruction ("adjust the
concrete item with a note in PLAN_CHAINS.md referencing FLOWS.md").

## The recipe table, in full (Production.java)

Existing (untouched) recipes are omitted from ratios below; see
`Production.java` for the complete table. **Bold** = new this slice.

| building | recipe id | input → output | ticks | role |
|---|---|---|---|---|
| MILL | **flour** | wheat×3 → FLOUR×2 | 140 | pure upstream (mill had no recipe before) |
| BAKERY | **bread_flour** | FLOUR×2 → bread×2 | 160 | fed, listed first (80 ticks/loaf) |
| BAKERY | bread *(unchanged)* | wheat×3 → bread×1 | 160 | rough (160 ticks/loaf) |
| BREWERY | **ale_malt** | MALT×2 → ALE×2 | 200 | fed (100 ticks/unit) |
| BREWERY | **malt** | wheat×4 → MALT×3 | 140 | upstream, higher threshold than rough ale |
| BREWERY | **ale** | wheat×3 → ALE×1 | 200 | rough (200 ticks/unit — brewery had no recipe before) |
| SMELTER | **iron_bloom** | raw_iron×3 → IRON_BLOOM×4 | 200 | fed-source, higher threshold than plain smelt |
| SMELTER | iron *(unchanged)* | raw_iron×1 → iron_ingot×1 | 200 | rough |
| SMITHY | **bloom_ingot** | IRON_BLOOM×2 → iron_ingot×2 | 200 | finishes the bloom (100 ticks/ingot, half of the smelter's 200) |
| SMITHY | axe/pickaxe/hoe/sword *(unchanged)* | iron_ingot → tool | 240–300 | unaffected — a different input item, cannot collide |
| SAWMILL | **timber_beam** | oak_log×3 → TIMBER_BEAM×2 | 180 | listed first, higher threshold (3) than plank recipes' (1) |
| SAWMILL | planks/spruce/birch *(unchanged)* | log×1 → planks×6 | 120 | rough, protected |
| CARPENTER | **barrel_beam** | TIMBER_BEAM×2 → barrel×1 | 130 | fed (130 ticks/unit, half of barrel's 260) |
| CARPENTER | sticks/barrel/ladder *(unchanged)* | planks/stick → … | 60–260 | unaffected — different input item |
| BUTCHER | **hide** | rabbit×2 → CURED_HIDE×2 | 160 | new input (RABBIT), cannot collide with beef/pork/mutton/chicken |
| TANNERY | **leather_cured** | CURED_HIDE×2 → leather×2 | 180 | fed (90 ticks/unit, half of the rabbit-hide recipe's 180) |
| TANNERY | leather *(unchanged)* | rabbit_hide×4 → leather×1 | 180 | rough, protected |
| WEAVER | **wool_bolt** | white_wool×3 → WOOL_BOLT×2 | 130 | listed before banner, lower threshold (3 vs 6) |
| WEAVER | wool/banner *(unchanged, reordered)* | string→wool, wool×6→banner | 140/260 | banner now sits below wool_bolt in priority — see Uncertainties |

### Tick-cost rule applied

The coordinator's instruction: *"a fed-path recipe should cost roughly HALF
the ticks per unit output of its rough path."* Checked directly against the
three pairs that share an output item:

- Bread: rough 160 ticks/loaf → fed 80 ticks/loaf (160/2 = 80 exactly).
- Barrel: rough 260 ticks/unit → fed 130 ticks/unit (260/2 = 130 exactly).
- Leather: rough 180 ticks/unit → fed 90 ticks/unit (180/2 = 90 exactly).
- Ale: rough 200 ticks/unit → fed 100 ticks/unit (200/2 = 100 exactly).
- Ingot (bloom route): smelter's rough 200 ticks/unit → smithy's finishing
  step 100 ticks/unit (200/2 = 100 exactly) — the yield side of that
  multiplier (3 raw iron → 4 bloom → 4 ingot vs. 3 raw iron → 3 ingot
  directly) is the ×1.33 the smelter recipe itself carries.

FLOUR and WOOL_BOLT are "pure upstream" goods (mirroring FLOWS.md's own
`mill: — (pure upstream)` row) with no rough/fed pair on the SAME output
item, so the halving rule does not apply to them directly — there is nothing
to halve against.

### Ordering discipline (why nothing was silently starved)

`Production#ready` returns the FIRST recipe in a building's list whose
inputs are satisfied — an existing behaviour, not something this slice
introduced. Two consequences were deliberately engineered around it:

1. **Different input item ⇒ order never matters.** bread_flour/bread,
   bloom_ingot/tool-recipes, barrel_beam/barrel, leather_cured/leather all
   key on DIFFERENT items than their sibling recipes, so there is no
   competition to order at all — both simply run whenever their own
   ingredient exists.
2. **Same input item ⇒ threshold discipline protects the rough path.**
   Three places add a NEW recipe that shares its input with an EXISTING one
   (smelter's iron_bloom vs. iron; sawmill's timber_beam vs. planks;
   weaver's wool_bolt vs. banner). In the first two, the new recipe is given
   a HIGHER threshold than the untouched one and listed first — a modest
   stockpile still runs the old recipe, only a genuine surplus gets
   diverted to the new one, and the old recipe's typical availability is
   unchanged. wool_bolt is the deliberate exception — see Uncertainties.

## Generator changes and the determinism proof

`tools/gen_blocks_items.py` gained seven new 16×16 icon functions
(`gen_item_flour`, `gen_item_malt`, `gen_item_ale`, `gen_item_iron_bloom`,
`gen_item_timber_beam`, `gen_item_cured_hide`, `gen_item_wool_bolt`), each
built from `texlib`'s shared palettes (`linen_raw`, `wheat`, `oak_light`,
`amber`, `iron`, `ember`, `oak`, `leather`, `linen`) with a fixed
`random.Random(N)` seed per function (201–205) — never the bare `random`
module, matching every existing generator in this file. Determinism was
verified directly, the same way `tools/validate_assets.py`'s
`check_pipeline` verifies it: the script was run twice, once under
`PYTHONHASHSEED=0` and once under `PYTHONHASHSEED=1`, in isolated copies of
`tools/`, and all seven new PNGs came back byte-identical across both runs
AND identical to the committed files:

```
flour: OK identical (hashseed0==hashseed1==committed)
malt: OK identical (hashseed0==hashseed1==committed)
ale: OK identical (hashseed0==hashseed1==committed)
iron_bloom: OK identical (hashseed0==hashseed1==committed)
timber_beam: OK identical (hashseed0==hashseed1==committed)
cured_hide: OK identical (hashseed0==hashseed1==committed)
wool_bolt: OK identical (hashseed0==hashseed1==committed)
```

All seven textures are 16×16 RGBA, the project standard per
`validate_assets.py`'s `check_textures` (block/item textures must be 16×16,
32×32/64×64 "signature", or an animation strip — 16×16 is correct here,
unlike the 64×64 plaque/build_plan items, which are the deliberate
oversized exception for GUI-held signature pieces).

## Item models and recipes

Seven `models/item/<name>.json`, each `{"parent": "minecraft:item/generated",
"textures": {"layer0": "hearthstead:item/<name>"}}` — the same shape as
`handbook.json`, the majority convention in this mod (3 of 4 pre-existing
item models spell the parent with the explicit `minecraft:` namespace).

Two player-craftable fallbacks were added, in `data/hearthstead/recipe/`
(the singular `recipe/` directory this repo actually uses under MC
1.21.1's newer datapack layout — not the `recipes/` the task brief
mentioned):

- `flour.json` — 3× wheat (shapeless) → 2× flour, matching the mill's own
  ratio exactly.
- `wool_bolt.json` — 3× white_wool (shapeless) → 2× wool_bolt, matching the
  weaver's own ratio.

The other five (MALT, ALE, IRON_BLOOM, TIMBER_BEAM, CURED_HIDE) were
deliberately left without a player-craftable fallback: each presumes a
workshop process (malting/brewing, a forge bloomery, a saw bench, tanning) a
player cannot reasonably replicate by hand at a crafting table, the same
split D-007's "a smithy forges a rough tool from metal alone" language draws
between what a settler-run building does and what raw improvisation can do.

## Acyclicity proof

Built by hand first (below), then re-proven as a static GameTest
(`ChainsGameTests#noValueMintingCycleInProductionTable`) that walks the ENTIRE
`Production` table — not just the six new items — as a directed graph
(edge: recipe input → recipe output) and runs a standard white/gray/black
DFS for cycles.

Every edge in the table after this slice:

```
wheat -> bread, flour, malt, ale
flour -> bread
beef/porkchop/mutton/chicken -> their cooked forms
rabbit -> cured_hide
raw_iron -> iron_ingot, iron_bloom
raw_copper -> copper_ingot
raw_gold -> gold_ingot
brown_mushroom -> mushroom_stew | potato -> baked_potato | kelp -> dried_kelp
oak_log -> oak_planks, timber_beam
spruce_log -> spruce_planks | birch_log -> birch_planks
oak_planks -> stick, barrel
stick -> ladder
timber_beam -> barrel
iron_ingot -> iron_axe, iron_pickaxe, iron_hoe, iron_sword
iron_bloom -> iron_ingot
stone -> stone_bricks | cobblestone -> stone
flint -> arrow | string -> bow, wool
wool -> banner, wool_bolt
rabbit_hide -> leather | cured_hide -> leather
malt -> ale
```

No item appears on both sides of any path — the closest thing to a cycle is
iron's two-hop `raw_iron -> iron_bloom -> iron_ingot`, which terminates at
`iron_ingot -> {tools}` and never returns to `raw_iron` or `iron_bloom`. A
DAG, by inspection and by the GameTest.

## Validator tail (current state)

```
FAIL: 749/763 checks passed, 14 error(s), 0 warning(s)
Failures:
  ✗ [Items] lang key 'item.hearthstead.ale' in en_us.json / nb_no.json
  ✗ [Items] lang key 'item.hearthstead.cured_hide' in en_us.json / nb_no.json
  ✗ [Items] lang key 'item.hearthstead.flour' in en_us.json / nb_no.json
  ✗ [Items] lang key 'item.hearthstead.iron_bloom' in en_us.json / nb_no.json
  ✗ [Items] lang key 'item.hearthstead.malt' in en_us.json / nb_no.json
  ✗ [Items] lang key 'item.hearthstead.timber_beam' in en_us.json / nb_no.json
  ✗ [Items] lang key 'item.hearthstead.wool_bolt' in en_us.json / nb_no.json
```

All 14 failures are exactly the 7 items × 2 languages this worker is
FORBIDDEN from fixing directly ("No lang json edits (report keys)") — see
the Lang keys table below for what to add. Everything else — pipeline
determinism, item models, textures (16×16), the two new recipe JSONs, and
every existing check — passes. (An earlier run also showed a `gen_plaque.py`
"committed assets are stale" pipeline failure; that was a concurrent
worker's in-progress edit to a file this worker never touched, and it
resolved itself once that worker committed — confirmed via `git status`
before this slice started, and gone from this re-run.)

## Lang keys needed (en_us + nb_no) — for whoever owns lang files

```
item.hearthstead.flour        = Flour                | Mel
item.hearthstead.malt         = Malt                  | Malt
item.hearthstead.ale          = Ale                   | Øl
item.hearthstead.iron_bloom   = Iron Bloom             | Jernklump
item.hearthstead.timber_beam  = Timber Beam            | Tømmerbjelke
item.hearthstead.cured_hide   = Cured Hide             | Herdet skinn
item.hearthstead.wool_bolt    = Wool Bolt              | Ullbolt
```

(nb_no renderings are a plain best-effort translation, not reviewed by a
Norwegian speaker — flag for correction if wrong.)

## Test assertions (ChainsGameTests.java)

Mirrors `EmploymentGameTests`' helper *shape* (`floor`, a bare `Building`
record) rather than its full settlement+hire flow, for the same reason
`WarehouseGameTests` does: `Production` reads only `building.bounds` and its
containers, so a registered `Settlement` and a live `SettlerEntity` would be
testing machinery these four assertions never touch — and two of the six
items (MILL, BREWERY) have no `Profession` to hire into yet regardless (see
Uncertainties).

- **(a) `millGrindsWheatIntoFlourChestTrue`** — a MILL with 10 wheat in its
  own chest, no other building anywhere: `ready()` returns `flour`
  (3 wheat → 2 flour), `run()` succeeds, and the chest afterward holds
  exactly 7 wheat and 2 flour.
- **(b) `bakeryWithFlourOutproducesWheatAlone`** — two independent bakeries,
  same tick cost. One holds only wheat (5): `ready()` must still return
  `bread` (D-007 — the rough path never stops working) and produce 1 loaf.
  The other holds only flour (4): `ready()` must return `bread_flour`
  (the fed path is preferred once it exists) and produce 2 loaves. Asserts
  `rough.ticks() == fed.ticks()` (the multiplier is yield, not a shorter
  clip) and `breadFromFlour > breadFromWheat`.
- **(c) `threeBuildingChainConservesItemsEndToEnd`** — LUMBER_CAMP (6 logs,
  no Production recipe — a Ring-1 source) → hand-simulated courier hop →
  SAWMILL (`timber_beam` run twice: 6 logs → 4 beams) → hop →
  CARPENTER (`barrel_beam` run twice: 4 beams → 2 barrels). Asserts every
  intermediate count exactly, and a final check that zero logs and zero
  beams remain anywhere across all three buildings' chests — only the 2
  barrels.
- **(d) `noValueMintingCycleInProductionTable`** — builds a directed graph
  from every recipe in every `BuildingType` (not just the six new items),
  asserts no recipe's output matches its own input in one step, then runs a
  full DFS cycle check over the graph and fails with the exact cycle path if
  one is ever found.

## Uncertainties

1. **MILL and BREWERY have no `Profession` to hire into yet — a real,
   currently-failing cross-file gap.** `Employment.TRADES`
   (`settlement/Employment.java`, owned by the recruit/Employment worker,
   FORBIDDEN for this worker to touch) has no entry for `BuildingType.MILL`
   or `BuildingType.BREWERY`, and no `Profession.MILLER` / `Profession.BREWER`
   exists in `entity/Profession.java` (also forbidden) either. This means:
   - The recipes are fully correct and tested at the `Production` layer
     (test (a) and half of test (c) exercise them directly).
   - But **no settler can actually be hired to run them in live play** —
     `CrafterWorkGoal.canUse()` requires `Employment.employerOf(...)`
     non-null, which requires a trade mapping that does not exist for these
     two buildings.
   - **`EmploymentGameTests#everyTradeHasWorkAndAMotionOfItsOwn` (a test this
     worker does not own) will now fail**, because it asserts `if
     Production.produces(type) then Employment.teaches(type)` for every
     `BuildingType`, and `Production.produces(MILL)` /
     `Production.produces(BREWERY)` are now true. This is flagged prominently
     here and in the final report rather than worked around, because working
     around it would mean either not implementing FLOUR/MALT/ALE (contradicts
     FLOWS.md, which is binding) or editing `Employment.java`/`Profession.java`
     (forbidden). **Needed follow-up, for whoever owns those files:** add
     `Profession.MILLER` and `Profession.BREWER` (or equivalent), map them in
     `Employment.TRADES`, and add `motionOf`/`soundOf`/`trainedBy` cases —
     mechanical once the profession enum values exist.
2. **WOOL_BOLT deprioritizes the existing banner recipe.** Both key on WHITE_WOOL;
   wool_bolt is listed first with a lower threshold (3 vs. banner's 6), so once a
   weaver has any real wool surplus, wool_bolt wins every time and banner
   effectively stops firing through automatic settler production (still
   directly player-craftable at a loom, unaffected). This was a deliberate
   call, not an oversight: FLOWS.md frames `wool -> [bolt]` as the weaver's
   primary upstream good, and no existing GameTest depends on banner firing
   automatically (`TradeWeaverGameTests` only ever supplies STRING, never
   enough WOOL for either recipe). Flagging in case the intent was for banner
   to stay dominant.
3. **FLOWS.md's smithy row also calls for a stone-tool rough path**
   ("smithy | cobble→stone tools (slow) | ingots→iron tools/arms") that does
   not exist in `Production` today — the smithy currently produces NOTHING
   without an ingot already in the building's chest, which is arguably a
   D-007 gap of its own. Not implemented here: it touches none of the six
   CHAINS items (all-vanilla ingredients/outputs) and would meaningfully
   grow this slice's surface. Flagged as a follow-up, not fixed.
4. **`docs/project/PLAN_PRODUCTION_CHAINS.md`** (pre-existing, written before
   FLOWS.md) still describes the OLD D-008 item names (flour, cured meat,
   meal, ale, cloth, tool haft) in its "goods that move between them" table.
   That document was not edited (out of this worker's ownership) — FLOWS.md
   is the newer, binding source and this file follows it; the older document
   is now partly stale and may want a maintainer pass.
5. **nb_no translations above are unreviewed** — see the lang key table.
