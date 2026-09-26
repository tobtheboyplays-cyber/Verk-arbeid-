# Bannerhold optional mod integration plan (plan only, nothing implemented)

Owner approved the "linking" idea for the content and building packs. Every link below is a **soft
dependency**. There is no entry in `neoforge.mods.toml`, no compile dependency and no class loading
from the other mod. Detection is by `ModList.get().isLoaded(id)` (already done in
`settlement/builder/BlueprintLibrary.java:306 modLoaded`) and by registry-id lookups
(`BuiltInRegistries.ITEM.getOptional(ResourceLocation)`), plus data tags with `"required": false`
entries (the pattern already used in `data/hearthstead/tags/block/settler_tables.json`).

When the mod is absent, nothing changes. When it is present, the new content simply turns on.

Mod ids and versions tested: `farmersdelight` 1.21.1-1.3.4, `brewinandchewin` 4.5.0+1.21.1 (jar-in-jar
greenhouseconfig), `sereneseasons` 10.1.0.9 (+ `glitchcore` 2.1.0.2), `mcwroofs` 2.3.2, `mcwwindows`
2.4.2, `mcwdoors` 1.1.5, `mcwbridges` 3.1.2, `mcwfences` 1.2.1.

---

## 1. Farmer's Delight + Brewin' and Chewin': Cook and Tavern

### What we have today (file:line, tree of 26 Sep)
- The Cook has no class of its own. `Profession.COOK` works the KITCHEN through the shared
  `CrafterWorkGoal`. The menu is a hard-coded table in `building/Production.java:357`
  (`put(BuildingType.KITCHEN, ...)`: mushroom stew, baked potato, dried kelp). `Recipe` is a record
  with an `Ingredient` input and a single output `Item`. `put()` (`:656`) stores an immutable list,
  and `of(type)` (`:709`) is the only reader. There is no registration hook.
- The Tavern serves any "ready meal" (`settlement/ReadyFood.java:27 isReadyMeal`: anything with
  `FoodProperties`). The best-nutrition item wins. **FD dishes therefore already get served if
  they are in the tavern's service chest.** Drinks are ale only: `ModItems.ALE` is hard-coded in
  `entity/TavernServingEntity.java:262/306/1076`, and brewing is in
  `settlement/work/TavernAleService.java:19/70`.
- Eating (`entity/ResidentMeal.java`) applies hunger `nutrition*8` and +2 morale. It deliberately
  applies no item effects, so FD's Comfort and Nourishment effects are not applied to settlers.
- Farmer (`entity/ai/FarmerWorkGoal.java`) is generic over `CropBlock`, but hard-requires
  `Blocks.FARMLAND` (`:465, :569, :613, :828, :855`).

### Plan
1. **Kitchen recipes as data (small refactor, independent of FD).** Add a `hearthstead:kitchen_menu`
   JSON data folder that is read on reload. Each entry is `{id, inputs:[{item|tag, count}],
   output:{item, count}, container?, ticks, mod?}`. Entries whose `mod` is not loaded, or whose items
   don't resolve, are skipped. The `Production.of(KITCHEN)` result becomes the hard-coded list plus
   the resolved data entries. The DAG/acyclicity GameTest must include data entries.
2. **FD menu pack (data only, `mod: farmersdelight`).** Pick about 10 dishes that suit a medieval
   village and use raw goods our settlers already produce:
   - bowl dishes: `vegetable_soup`, `beef_stew`, `chicken_soup`, `fish_stew`, `baked_cod_stew`,
     `pumpkin_soup`, `mushroom_rice`;
   - plated dishes: `roast_chicken_block`, `shepherds_pie_block`, `honey_glazed_ham_block`, served
     as a slice by the tavern;
   - snacks: `bacon_sandwich`, `mutton_wrap`, `apple_pie`.

   Prefer the **kitchen as a virtual cooking pot**, so the Cook does not need to operate FD's
   block entity. Optionally require a `farmersdelight:cooking_pot` or `farmersdelight:stove` in the
   KITCHEN survey only when FD is present (a `BuildingType` "optional station": counts toward the
   quality score, not a hard requirement).
   Alternative (bigger, later): read FD's `farmersdelight:cooking` recipe type from the
   `RecipeManager` generically (`getIngredients()` + `getResultItem()`). That covers every FD
   add-on, but needs a whitelist so the Cook doesn't make 60 dishes.
3. **BnC tavern drinks.** Add an item tag `hearthstead:tavern_drinks`. It holds `hearthstead:ale`,
   plus optional entries `brewinandchewin:beer`, `mead`, `rice_wine`, `egg_grog`, `strongroot_ale`,
   `saccharine_rum`, `pale_jane`, `salty_folly`, `steel_toe_stout`, `glittering_grenadine`, `kombucha`,
   `red_rum`, `bloody_mary`, `dread_nog`, `withering_dross` and `vodka`, all with
   `"required": false`. Replace the three `is(ModItems.ALE.get())` checks with
   `is(HearthsteadTags.TAVERN_DRINKS)`. The tavern cup renderer (`client/render/TavernServingRenderer.java`,
   ALE/FOAM meshes) keeps its mesh but takes a tint per drink, from a small client map (unknown id
   uses the ale colour).
   Brewing: the Innkeeper keeps brewing our ale. If a `brewinandchewin:keg` is inside the tavern,
   they may also ferment 1-2 BnC drinks with a data recipe (same format as item 1, `building:
   tavern`, `mod: brewinandchewin`). Only a whitelist of drinks goes in: no withering_dross or
   dread_nog, because they are harmful potions for a friendly inn.
   Prices (`settlement/work/TavernGuestPayment.java`): the drink price is +1 for ale, +2 for BnC
   drinks and +3 for spirits (rum, vodka). This is a balance-lane call.
4. **Serving FD plated food.** For `*_block` feasts, the Innkeeper right-clicks the placed feast
   through FD's own block logic via `useItemOn` with a bowl, or skips blocks and uses only the
   item dishes. The first version uses only item dishes.
5. **Effects.** Keep today's rule (no potion effects on settlers). Optionally give
   `farmersdelight:comfort` and `nourishment` dishes +1 extra morale, via an item tag
   `hearthstead:hearty_meals`.
6. **Farmer + FD crops.**
   - Accept `farmersdelight:rich_soil_farmland` wherever `Blocks.FARMLAND` is required (use a block
     tag `hearthstead:farmland` = `minecraft:farmland` + optional FD rich soil farmland).
   - Cabbage and onion are `CropBlock`s and should then just work.
   - Tomatoes (`BuddingTomatoBlock`, a bush plus a climbing vine) and rice (a water crop) are **not**
     `CropBlock`s. List them as a later "special crops" task (rice needs a water-plant path in
     `FarmerWorkGoal`).
7. **Tests.** Add a GameTest batch `compat_fd`, gated on `ModList.isLoaded("farmersdelight")`. It
   makes one FD dish in a kitchen, serves one BnC drink in a tavern, and checks that a missing mod
   leaves the menu unchanged.

Effort: items 1 and 3 are medium (2-3 h each, mostly Cook/Tavern lane). Item 2 is data only.
Item 6 is small.

---

## 2. Macaw's blocks: optional substitution in Builder blueprints

### What we have today
`settlement/builder/BlueprintMeta.java:36-45` `FurnitureSwap(mod, pos|from, to)`. The `mod` value
defaults to the namespace of `to`, so **Macaw's needs no Java change for simple swaps**:
`{"pos":[x,y,z],"to":"mcwwindows:oak_window[facing=north]"}` works when mcwwindows is loaded.
The loading side is `BlueprintLibrary.applyFurniture` (`:278`). It keeps the vanilla block if the
mod is missing or the state doesn't parse.

### Gaps to fix before Macaw's roofs are useful
1. **Property carry-over for `from` swaps.** Roofs replace stairs. A swap like
   `{"from":"minecraft:spruce_stairs","to":"mcwroofs:spruce_roof"}` must keep
   `facing/half/shape/waterlogged` from each original cell. Today `to` is a fixed state string.
   Add `"keep_properties": true` (copy every property that has the same name and type).
   `mcwroofs:*_roof` uses exactly the stair properties (`facing, half, shape`, checked in the
   blockstates), so this is a direct copy.
2. **Material costs.** Macaw's items differ from vanilla stairs. Costs already "follow whichever
   block is actually placed" (BlueprintMeta javadoc), so the Builder asks for `mcwroofs:spruce_roof`
   items, which the settlement can't make. Proposal: a cost alias table in blueprint JSON
   (`"cost_as":"minecraft:spruce_stairs"`), so the builder pays vanilla materials and places the
   Macaw's block. Otherwise the swap is useless for survival villages.
3. **MaterialRules** (`settlement/builder/MaterialRules.java:223 compare`). Roofs' `shape` is
   already in `DYNAMIC_PROPS`, and window `part` is computed by the block. `part` must count as
   dynamic, or windows would REORIENT forever. The builder lane will do this **scoped to the
   `mcwwindows` namespace**, because vanilla beds need `part=head/foot` for pair logic (their
   reply, 26 Sep).

### Block ids to use (verified in the jars' blockstates)
- **Roofs** (`mcwroofs`, stair-like: `facing, half, shape`): `oak_roof`, `spruce_roof`,
  `dark_oak_roof`, `oak_planks_roof`, `spruce_planks_roof`, `thatch_roof`, `cobblestone_roof`,
  `stone_bricks_roof`, `deepslate_roof`, `red_terracotta_roof`. Each has variants
  `_top_roof`, `_attic_roof`, `_lower_roof`, `_steep_roof`, `_upper_lower_roof` and
  `_upper_steep_roof`. `thatch_*` suits farm buildings, spruce and dark oak suit timber houses,
  `stone_bricks_*` suits barracks and the keep.
- **Windows** (`mcwwindows`, `facing, part`): `oak_window`, `spruce_window`, `dark_oak_window`,
  `oak_plank_window`, `spruce_four_window`, `stone_window`, `oak_shutter`, `spruce_shutter`,
  `oak_louvered_shutter`, `oak_curtain_rod`, `oak_blinds`, `stone_brick_arrow_slit`,
  `cobblestone_arrow_slit` (the arrow slits are for defence blueprints), `oak_log_parapet`.
- **Doors** (`mcwdoors`, door properties `facing, half, hinge, open`): `oak_barn_door`,
  `spruce_barn_door`, `oak_cottage_door`, `spruce_classic_door`, `oak_stable_door`,
  `oak_stable_head_door`, `spruce_western_door`, `metal_reinforced_door` (gatehouse). Door swaps must
  keep `half` for both cells, so use `keep_properties`.
- **Bridges** (`mcwbridges`): `rope_oak_bridge`, `rope_spruce_bridge`, `oak_log_bridge_middle`,
  `spruce_log_bridge_middle`, `stone_brick_bridge`, `cobblestone_bridge`, `*_bridge_pier`. These are
  for a future bridge or road blueprint.
- **Fences/walls** (`mcwfences`): `oak_picket_fence`, `spruce_picket_fence`, `oak_stockade_fence`,
  `spruce_stockade_fence` (palisade look), `oak_horse_fence` (paddock), `oak_wired_fence`,
  `oak_highley_gate`, `oak_pyramid_gate`, `railing_stone_brick_wall`, `stone_pillar_wall`,
  `wooden_cheval_de_frise` (a raid barricade, and a good fit for the defence lane).

Handed to the builder lane (a1f86e34f18da84e3) and the blueprint lane (abe4f8c266eb4b115) by
message on 26 Sep.

---

## 3. Serene Seasons: farmer crop seasons

### What we have today
There is no season system (`settlement/Costs.java:160` is only a comment). The Farmer plants any
`CropBlock` seed it has, all year.

### What Serene Seasons does to us if installed as-is
Out-of-season crops don't grow; by default they "break" or pause, depending on SS config
`fertility.toml`. Our Farmer would keep planting wheat in winter, and watering or harvest loops
would find nothing mature. The village food economy stalls for 1 of 4 seasons. **So SS must not be
installed without the link below.**

### Plan (soft link, via SS's own data tags; no SS classes needed for the first step)
1. SS publishes item tags `sereneseasons:spring_crops`, `summer_crops`, `autumn_crops`,
   `winter_crops` and `year_round_crops` (the block-tag twins also exist; checked in the jar). The
   Farmer reads the current season once per work cycle via a tiny reflection-free bridge:
   `SeasonHelper.getSeasonState(level).getSeason()`. It lives in a class that is only loaded when
   `ModList.isLoaded("sereneseasons")`, the same pattern as the other optional classes.
2. Seed choice: prefer seeds whose item is in the current season's tag. If none, leave the field
   fallow and do "winter work" (till, compost, fetch), rather than plant a crop that won't grow.
3. The Farmer's status line and the Banner "Tasks" tab show "Fallow for winter" instead of
   "No seeds", so the player understands.
4. Greenhouses: SS's `greenhouse_glass` block tag makes glass-roofed fields fertile. The Farmer
   treats a field with a glass roof as year-round (SS computes that itself; we just ask
   `ModFertility`/`SeasonHooks` whether the crop is fertile at that position, if the API is stable).
   Otherwise, trust the tags.
5. Tests: a GameTest that sets `/season set winter` (SS command) and checks the farmer doesn't
   plant wheat.

### Season event ideas (NOT to build; owner must pick any of these first)
- Harvest festival at the end of autumn: a tavern feast, a morale boost, a merchant caravan.
- Midwinter market: the peddler brings winter seeds and preserved food.
- Spring planting day: the farmers' work speed rises for one day.
- Winter raids: raiders bring fewer men but come more often, or come in bigger groups when food is
  short.
- Summer fair or tournament: a guard training contest, and villagers watch.
- Autumn storms: the roof-repair job appears (the Builder patches roofs).
- Snowed-in roads: couriers slower in winter unless roads are paved.

---

## 4. Supplementaries (building pack), ideas only
- `supplementaries:sign_post` for road signs at the village entrance, a Builder "road sign"
  blueprint option.
- `supplementaries:jar`, `sack` and `crate` as optional storage furniture in stores/warehouse
  blueprints. Use the same FurnitureSwap, and check that our storage scanner counts their
  inventories (they have item handlers).
- `supplementaries:bellows` or `faucet` could be optional smithy/brewery props.
