# BUILDER — design (Bannerhold / `hearthstead`)

Status: design v2 (26 Sep 2026). v2 includes the owner's change: there are no blueprint levels and no hut levels.
A building's level comes from what its room contains (the checklist). Owner of this lane: Builder agent.

## 0. What the owner asked for, in one paragraph

The Builder does three jobs:
1. **Defensive works from player-placed plans.** Palisade and stone wall lines, gates, towers and barricades.
2. **Starter buildings from a blueprint catalog**, MineColonies-style: pick a building, place a ghost, and the Builder raises it.
3. **Upgrade Orders.** The Builder reads a building's level checklist ("Next level: add 2 lanterns, a second bed") and places the missing pieces inside the room. This works on hand-built houses too, which is the key feature.

Players can still build everything by hand (hybrid). The Mason keeps post-raid repair (`RepairWorkGoal`).

**Invariant note.** DESIGN.md says settlers "never construct autonomously". The Builder does not break this. He only builds what a player explicitly ordered: a placed plan, a drawn line, or a confirmed Upgrade Order. The plaque is still the only surveyor. A blueprint building becomes a building only because its plaque scans a valid room.
- Raid barricade rushing is the one automatic behaviour, and it only re-raises barricade plans the player already placed.
- This exception should be recorded in `COORD/DECISIONS.md` (lead's file).

## 1. What we take from MineColonies, and what we fix

| MineColonies | Keep | Fix |
|---|---|---|
| Build Tool: category → style → level, ghost with move/rotate/mirror, confirm | Catalog + ghost + rotate/mirror + confirm | One active ghost only; rotation pivots around the footprint centre; the ghost shows **material cost, stock and blocks that would be removed** *before* you confirm |
| Work orders, builder takes the next within range | Build jobs with a FIFO queue + "rush" priority | A job never silently idles. Every wait has a status line with numbers and a position |
| Resource list, three colours | Material list per job | Per item: needed / in hut / in warehouse / on the way. Headline blocker in plain words: "Waiting for 32 oak planks (warehouse has 0)" |
| Requests in buckets sized to builder inventory | Batches sized to the next build steps and the courier parcel cap | Batches follow the *build order*: only the next ~1–2 layers' materials are requested, so a half-stocked warehouse still makes visible progress |
| Stages: clear → solid → weak → non-solid → decorate | CLEAR → SOLID (bottom-up) → FRAGILE (bottom-up) → FINISH (plaque) | Equivalent-state matching (grass ≈ dirt, stair shape drift, waterlogging); a block that cannot be placed after 3 tries is **skipped and flagged**, never looped |
| Builder destroys player blocks in the footprint, drops lost | — | Natural terrain (grass, dirt, sand, gravel, stone, plants, snow, leaves) is cleared automatically. **Player blocks are never overwritten** unless the player confirms. Every removed block's item goes into the hut chest (salvage); nothing is deleted |
| No scaffolding; places blocks out of reach | — | The Builder stands on a real stand cell (`StandCells`). For tall builds he places **temporary scaffolding** (vanilla scaffolding block, consumed from stock and recovered afterwards). Reach is 4.5 blocks |
| Hut level caps building level | — | No hut levels (owner). The tech tree decides *what kinds* of work the Builder may do |
| Walls only via Shape Tool | — | Dedicated **line tool**: click A, click B, pick palisade/stone, toggle gate. Towers are blueprints |

## 2. Building levels = checklist (shared with the Warehouse lane)

`com.hearthstead.building.BuildingLevelChecklist` is the pure, JUnit-tested core. `com.hearthstead.building.BuildingLevels` holds the per-type tables.

- **Levels are cumulative lists of checklist items.** L1 is always exactly today's `BuildingType.requirements()`, so nothing that exists today changes.
- **Each item** is `(id, have(result), needed(result), fix)`. The `fix` tells the Builder how to add the missing piece:
  - `PLACE(blocks, spot)`, where spot is WALL_MOUNT | FLOOR_FREE | ALONG_WALL | CEILING
  - `REPLACE_FLOOR(blocks)`
  - `HAND_ONLY` (for example "room must be bigger")
- **Level = the highest L whose every item (and every lower level's) is met.** The plaque survey writes it into `Building.level` on every scan. It is re-derived, never bought. `Building.nextLevelGap` (runtime only) holds the unmet items of level+1 for UI: "Next level: add 2 lanterns (1/3), a second bed (1/2), solid floor (12/20)".
- **Tech-caps are applied by the reader, not the checklist.** The warehouse applies its own `techMax`, and other buildings can do the same later. The plaque shows "Level N built — needs <node>" when capped (Warehouse lane exposes `gateFor`).
- **Floor material** needs `RoomScanner.Result.floorCounts` (cells directly below interior cells). This is an additive record component, and old constructors are kept.
- Tables v1:
  - **WAREHOUSE** L1–L5: numbers from the Warehouse lane. Storage 4/16/32/64/128, ledger desk, labels, lights, doors, floor, solid floor.
  - **HOUSE** L2: 2 lights, solid floor, 1 storage. L3: 3 lights, 2 furnishing kinds (carpet, flower pot, bookshelf …), 1 workbench.
  - **LODGING** L2: 4 lights, solid floor, 2 storage. L3: 6 beds, 6 lights.
  - **TAVERN** L2: 5 lights, 4 seats (stairs/slabs as chairs).
  - **BARRACKS** L2: 4 beds, 4 lights, solid floor.
  - **WATCHTOWER** L2: 6 ladders, 6 lights.
  - Other types have L1 only until a lane defines more. What a level *does* (capacity, quality) belongs to each building's lane. The Warehouse already reads it.

### Upgrade Orders
- In the Builder's Plan screen → "Upgrade" tab, pick a building. It shows its gap list. **Order upgrade** → the server converts each fixable gap item into concrete placements inside the room:
  - capped flood fill of the room's interior within `Building.bounds`;
  - candidates never block a door, a bed head or the 1-wide walking lane.
- The placements become an ordinary build job (kind UPGRADE) with an explicit step list. Materials, couriers, status and exact-once rules are then identical to a blueprint.
- HAND_ONLY items stay in the list as "you must do this by hand".
- Checked again at each placement. If the player filled the spot meanwhile, the step is skipped (not blocked). When the job ends, the plaque rescans and the new level shows.

## 3. Blueprints

- **Files:**
  - Structure: `data/hearthstead/structure/blueprints/<id>.nbt` (vanilla structure template, DataVersion 3955).
  - Metadata: `data/hearthstead/blueprints/<id>.json`.
  - Both are generated by `tools/gen_blueprints.py` (nbtlib), which writes blocks from a small voxel DSL. Style reference: the owner's server house (`path_server_house.nbt`): oak log frame, oak planks infill, cobblestone base, oak stair/slab roofs, glass panes, wall torches.
- **Metadata JSON:**
```json
{ "id": "cottage", "category": "homes", "kind": "building", "building_type": "house",
  "style": "timber", "requires": "builders_hut", "size": [7,8,9], "ground_level": 1,
  "plaque": {"pos": [3,2,1], "facing": "south"}, "work": [[3,1,4]],
  "segment": null, "materials": [{"item": "minecraft:oak_planks", "count": 96}, ...] }
```
  - `materials` is informational (shown in the catalog without loading the NBT). The server always recomputes it from the template.
  - `kind`: `building` (registers via plaque), `defense` (a wall/gate/tower piece, no plaque unless it has a type, e.g. WATCHTOWER), `barricade`.
  - `segment`: `palisade` | `stone` | `gate` | `barricade`, for the raid lane's HP / weak-point lookup (see §7).
- **No per-level variants.** A blueprint is a *starting* building that satisfies the checklist's L1 (the Warehouse blueprint satisfies L1). Upgrades afterwards go through Upgrade Orders exactly like a hand-built room.
- **Rotation and mirror:** `BlueprintTransform(rotation 0..3, mirror)`, a pure integer transform about the footprint origin. Mirror is applied first (x → sizeX-1-x), then rotation. Block states are rotated and mirrored with vanilla `BlockState.rotate/mirror`. The ghost and the job use the same transform code (single source of truth), and JUnit covers it.
- **Catalog v1** (grouped by category; each item shows name, footprint, material summary, and a lock reason if not unlocked):

| Category | Blueprints | Unlock |
|---|---|---|
| Homes | Cottage, Lodging House | builders_hut (on timber_rights for now) + HOME node for the plaque type |
| Work | Builder's Hut, Warehouse | builders_hut + the building's own node |
| Common | Tavern | builders_hut + HOSPITALITY |
| Military | Barracks | builders_hut + FIRST_WATCH |
| Defense | Watchtower (timber), palisade line, palisade gate | defense_plans (upgrade wire 19) |
| Defense | Stone wall line, stone gatehouse | masonry (upgrade wire 20) |
| Defense | Barricade | builders_hut |

## 4. The Builder's Plan (placement tool)

- **New item `hearthstead:builders_plan`:** a rolled drawing on a board, crafted from paper + a plank + charcoal/coal. It is separate from the existing `build_plan` slip (plaque dedication), which is unchanged.
- **Use (right-click in air)** → opens `BuilderPlanScreen` (ui2 kit: `Ui2Surface.sheet`, `Ui2Tabs`, `Ui2RowButton`, `Ui2Button`). Tabs:
  - **Catalog:** category list left; blueprint detail right (materials, size, lock reason); "Place" button.
  - **Defense:** Palisade line / Stone line / Barricade + a "Gate in line" toggle.
  - **Upgrades:** buildings of this settlement with level and gap list; "Order upgrade".
  - **Sites:** active jobs with progress bar, status line, Cancel / Allow overwrite / Rush.
- **Ghost preview (blueprint):**
  - Client-side `BuildGhost` state (blueprint id, anchor, rotation, mirror, block list from the server's preview payload).
  - A `RenderLevelStageEvent` renderer draws every non-air block translucently (alpha 0.45), outlines the footprint, and tints red the cells where a player block would be overwritten. Terrain cells to clear are tinted amber.
  - Controls while holding the plan:
    - the ghost follows the block you look at (snaps to its top face);
    - **scroll** rotates;
    - **shift+scroll** raises/lowers;
    - **middle-click or M** mirrors;
    - **right-click** locks the position and opens the confirm sheet;
    - **left-click / Esc** cancels.
- **Confirm sheet:** material totals with stock/warehouse counts, "N natural blocks will be cleared", "M player blocks in the way" and an **Allow overwrite (salvaged to hut)** checkbox (off by default). Then Confirm.
  - The server validates again: inside the settlement radius, chunk loaded, unlocked, footprint not intersecting another active job, ground support ≥ 60% under the bottom layer.
- **Line tool (defense):** pick Palisade/Stone in the Defense tab. Click point A, then B. The ghost shows the segment line along the dominant axis or a 45° diagonal, max 32 blocks. With the Gate toggle, the gate is centred and can be moved along the line with scroll. Confirm the same way. Each line becomes one job; the height follows terrain (each column is placed from the local ground up).
- **Barricade:** a single-click placement (3×1 or 3×2 footprint) that faces away from the Banner by default. It is a normal job with the `barricade` flag.

## 5. The build loop (physical, exact-once)

**Job (`BuildJob`, persisted in `BuildSiteSavedData` "hearthstead_build_sites")**
- Fields: id, settlement, kind, source (blueprint id / line spec / upgrade building id), transform, anchor, bounds, **explicit ordered step list** (palette of BlockStates + packed positions + phase), cursor, state, status, allowOverwrite, rush, owner player, claimant builder + lease, skip counts.
- The job is self-contained: a changed blueprint file never corrupts an existing site. The step list is capped at 4096 steps.

**Order**
1. **CLEAR** (natural blocks → air, top-down, drops salvaged).
2. **SOLID** layer by layer bottom-up.
3. **FRAGILE** (torches, lanterns, doors, glass/panes, beds, ladders, carpets, signs, trapdoors, flower pots, chests), bottom-up.
4. **FINISH:** place the plaque and fit its plan. The plaque then surveys the room and the building registers through the normal plaque path ("the plaque is the surveyor").

Air cells inside the footprint that hold a *player* block are listed as BLOCKED unless overwrite was allowed.

**Materials**
- `MaterialRules` (pure, JUnit) maps a block state to the item and count it costs:
  - door → 1 per lower half;
  - bed → 1 per foot;
  - double slab → 2;
  - wall torch → torch; wall sign → sign;
  - water/lava/fire/air → nothing;
  - grass block / dirt path / farmland → dirt.
- The plaque costs 1 `hearthstead:plaque`. Its build plan is drawn by the Builder from 1 paper + 1 feather + 1 oak plank, a flat drafting cost for every building type, so nothing is conjured. Hand-crafted plans use each type's own recipe: the Builder's Hut plan is paper + feather + crafting table, and the House plan is paper + feather + planks.

**Supply**
- Materials are always taken from the **Builder's Hut containers** (chest truth).
- For the next batch (the materials for the next steps up to ~2 layers, capped by 4 parcels), the Builder computes the deficit (hut stock − in-flight rows). He opens `MATERIAL_INPUT` rows Warehouse → Hut through `RequestLedgerService.openBuilderMaterial`, and **Couriers deliver them through their existing restock tier**.
- Any player can also drop materials straight into the hut chest.
- If the warehouse has none, the status says so and names the item.

**Carry and place**
- The Builder loads what the next steps need from the hut into his bag (exactly, up to carry capacity), walks to the site (activity CARRY_MATERIALS, clip CARRY_PLANKS) and places each block from a stand cell within reach:
  - activity WORK_BUILD, clip BUILD_PLACE (reach, place, tap);
  - every Nth block, or on doors and frames, a short BUILD_HAMMER beat;
  - sounds: vanilla place sound of the block + `nail_tap` for wood and `chisel_tap` for stone on the contact beat.
- **Exact-once:** consuming one item from the bag and `setBlock` happen in the same server tick, in the same method. If the item is missing, nothing is placed. If placement fails, the item stays in the bag. A save can never land between the two.
- On reload the cursor resumes. Steps already satisfied in the world (same state, or an equivalent) are skipped without charge. Items in the bag persist with the settler (and drop on death, physical).
- Leftovers are returned to the hut when the job ends or is cancelled.

**Reach and scaffolding**
- The stand cell is chosen with `StandCells` (walkable, 2-high, within 4.5 blocks, not inside the footprint's future solid cells).
- If no stand cell reaches a step (tall walls, roofs), the Builder places a column of `scaffolding` (from hut stock, 1 per level, requested like any material) next to the footprint and climbs it; vanilla scaffolding is climbable.
- The scaffolding positions are recorded in the job and removed in FINISH, with the items back to the hut.

**Stuck guard**
- No progress for 30 s (600 ticks) at one step → try the next stand cell. After 3 fails the step is **skipped and flagged** ("Skipped: can't reach x,y,z").
- At job end skipped steps get one more pass; still failing → job completes with "N blocks skipped" listed in Sites. It never loops forever.

**Status lines** (`BuildStatus`, one enum + args, lang keys `hearthstead.builder.status.*`):
- `Waiting for 32 oak planks (warehouse 0, on the way 0)`
- `Fetching materials`
- `Clearing ground 12/40`
- `Building layer 3/9`
- `Fitting doors & lights`
- `Blocked: player block at x,y,z`
- `Skipped 2 unreachable blocks`
- `Done`

These show in the Builder's Plan → Sites tab, on the Builder's settler sheet, and in the **Banner screen → Buildings** page (progress chip; UI lane owns `HearthScreen`, I supply a snapshot payload).

**Completion.** For `kind=building`, FINISH places the plaque (state from the blueprint), creates the typed build plan and calls `PlaqueBlockEntity.insertPlan` → survey → link. The GameTest asserts the new `Building` is registered and valid.

**Multiple builders.** Jobs are claimed with a lease (TTL 600 ticks, renewed while working), the `RepairWorkGoal.CLAIMS` idiom. Two builders never work one job in v1.

## 6. Builder's Hut and the tech tree

- **`BuildingType.BUILDERS_HUT`** ("builders_hut", 0 residents, 1 worker, icon `Items.SCAFFOLDING`).
  - Requirements: workbench 1 (crafting table), storage 2, doors 1, lights 1, floor_space 16.
  - Hand-buildable in the first evening. This is the bootstrap: build the hut by hand, fit its plan, and hire a Builder with the Builder emblem.
- **No hut levels.** Build speed scales with the Builder's own skill (`train(DEXTERITY)`), not the hut.
- **Tech (ids agreed with the tech-tree designer):**
  - `builders_hut`: the hut plan, the Builder emblem, starter blueprints, barricades and Upgrade Orders. **For now** BUILDERS_HUT + BUILDER sit on the existing `timber_rights` node (no new DevelopmentNode, whose layout belongs to another lane). They move to the `builders_hut` node when the tree is implemented.
  - `defense_plans`: `PostRaidUpgrade` wire 19, WATCH, requires FIRST_WATCH, 3 Coins + 16 logs + 16 sticks. Palisade lines, palisade gate, timber watchtower.
  - `masonry`: `PostRaidUpgrade` wire 20, CRAFT, requires FIRST_RAID_AFTERMATH + defense_plans, 6 Coins + 32 cobblestone + 16 stone bricks. Stone wall lines, stone gatehouse, stone floor fixes in Upgrade Orders.
  - `stone_walls` (combat lane) will read segment metadata for HP / arrow slits.

## 7. Defense integration

- **Wall/gate/barricade blocks are vanilla blocks** (logs/fences/stone bricks/cobblestone walls/oak fence gates). Raiders already breach anything collidable without a block entity (`RaiderBreachGoal`: doors 3 hits, walls 6 hits), and the Mason repairs through the scar ledger. So defense works are breakable and repairable from day one.
- **Weak points / HP:**
  - Each finished defense job registers its blocks in `DefenseWorks` (per settlement: job id, segment kind, block list, gate positions).
  - Public read API for the raid/combat lanes: `DefenseWorks.segmentAt(level, settlementId, pos)` and `gates(settlementId)`.
  - The raid lane can scale `WALL_HITS` by segment kind (palisade < stone) and send raiders to gates or the weakest segment. I do not edit `RaiderBreachGoal`.
- **Raid rush:** when a raid warning is committed (first-raid queued plan, or recurring warned plan; read like `RaidThreatInfo`), `BuilderWorkGoal` moves every placed **barricade** job to the front (rush) and marks broken barricades for rebuild.
  - The status says "Rushing barricades before the raid".
  - While `pendingRaid != null` the Builder stops and shelters like other civilians.
- The tech designer's separate `barricades` node (idle settlers place stored barricades) can reuse the same barricade jobs later.

## 8. Profession, look, motion, sound

- `Profession.BUILDER(28, "builder", empty hands, 0x9A6A3A)`: hands stay free (the carry and hammer clips own them).
  - Attribute: DEXTERITY. Motion: WORK_BUILD. `worksAtTheBuilding = false`.
- Emblem `builder_emblem` (procedural 16→32 px: a hammer over a plumb line on the job-emblem disc). The Job Emblem catalog entry is on timber_rights.
- Outfit (`gen_settler.py`): leather apron with tool belt, rolled sleeves, a flat cap (miller_cap silhouette in walnut). Distinct from carpenter and mason in colour + headgear.
- Clips (Blender `motionkit`, `--fast` previews, one process at a time):
  - `BUILD_PLACE` (1.6 s loop): reach forward/down, set block, two hammer taps at 0.9 s and 1.2 s.
  - `BUILD_HAMMER` (1.0 s loop): overhead hammer beat with contact at 0.45 s.
  - `CARRY_PLANKS` (walk overlay): arms under a plank bundle at chest height.
  - New activities WORK_BUILD, WORK_BUILD_HAMMER and CARRY_MATERIALS are appended to `SettlerActivity`. The model wiring (one `activityClock` + `sampleClip` per clip, as done for WORK_NAIL) is requested from the animation-engine lane, which owns `SettlerModel`.
- Sounds: reuse `nail_tap` (wood) and `chisel_tap` (stone) on the contact beats via `WorkSoundSync`, plus the block's own place sound.

## 9. Tests

- **JUnit:**
  - `BlueprintTransformTest` (rotation/mirror round trips, footprint bounds);
  - `MaterialRulesTest` (doors, beds, slabs, wall torches, fluids);
  - `BuildingLevelChecklistTest` (cumulative levels, gap lists);
  - `DefenseLinePlannerTest` (axis/diagonal lines, gate centre, length cap);
  - `BuildOrderTest` (phase ordering: clear top-down, solid bottom-up, fragile last).
- **GameTests** (`BuilderGameTests`, run only through the lead's serialized queue):
  1. blueprint places exactly its blocks and consumes exactly its materials;
  2. save/reload mid-build resumes without double consumption;
  3. refuses to overwrite a player block (status BLOCKED, block intact, item count unchanged);
  4. a palisade line builds;
  5. a building auto-registers on completion (plaque linked, `Building` valid);
  6. an Upgrade Order adds a lantern and the level rises;
  7. a courier delivers requested planks to the hut (exact count).

## 10. MineColonies parity checklist (owner, 26 Sep) and how each item is met

| Required | Mechanism |
|---|---|
| Right order: clear → foundation → walls → roof → interior → redstone | Each step gets a `BuildPhase`: CLEAR, FILL, FOUNDATION, STRUCTURE, ROOF, INTERIOR, REDSTONE, FINISH. Sort key = (phase, y, distance from the stand side). Classification is pure (`BuildOrder`): FOUNDATION = y ≤ ground_level; ROOF = stairs/slabs/any solid above the eave line (the highest y with a wall-log/plank ring, from metadata `eave_y` or computed); INTERIOR = fragile/furniture; REDSTONE = redstone wire, repeater, comparator, lever, button, pressure plate, tripwire, observer, piston |
| Rotation and mirror before placing | `BlueprintTransform` is applied once when the job is created. The ghost uses the same code |
| Clear material list with live progress | Sites tab: per item needed / used / in hut / in warehouse / on the way, plus an overall % bar and a phase bar |
| Fetches its own materials, requests what's missing, no silent stalls | Courier `MATERIAL_INPUT` rows Warehouse → Hut, plus the Builder self-fetches from the warehouse when no Courier is employed (walks there with his sack and takes exactly the batch; same chest truth). Every wait carries a status |
| Ghost preview before confirming | §4 |
| Build and upgrade defense plans | Line tool (palisade/stone) + an **Upgrade line** action: a palisade job can be replaced in place by a stone job over the same line (steps = diff; removed logs are salvaged to the hut) |
| Saves and resumes after restart | Explicit step list + cursor + placed-bitset in SavedData; bag contents persist with the settler |
| Uneven terrain (fill and clear) | CLEAR removes natural blocks above ground inside the footprint. FILL puts dirt (or cobblestone under stone foundations) in air/fluid cells *below* the foundation down to solid ground, max 4 deep (deeper → validation refuses: "needs a flatter spot") |
| Fluids and falling blocks | Fluids: footprint fluid cells are part of CLEAR (outer ring first, then inside), and a target solid simply displaces them. Falling blocks (sand, gravel, concrete powder, anvils): placed only when the cell below is already sturdy, otherwise deferred; clearing is top-down so a sand overhang is removed before what it rests on |
| Multi-floor + navigation while building | Stand cells are searched on already-built floors inside the footprint too (not only outside). Stairs/ladders in the blueprint are placed in their structural layer so upper floors stay reachable. Temporary scaffolding columns (recorded, removed at FINISH) where nothing reaches |
| Cancel refunds placed materials | Cancel offers **Stop (keep what's built)**: bag + unused hut stock stay, rows closed. **Dismantle**: a reverse job removes exactly the blocks this job placed (placed-bitset), top-down, and their items go back to the hut chest |

Better than MineColonies:
- **What's blocking, right now.** One headline status with numbers or a position.
- **Motion per phase:**
  - CLEAR → dig (reuse WORK_MINE / shovel);
  - FOUNDATION → WORK_CHISEL beat (stone) + BUILD_PLACE;
  - STRUCTURE → BUILD_PLACE + nail taps;
  - ROOF → BUILD_HAMMER;
  - INTERIOR → BUILD_PLACE;
  - carrying → CARRY_PLANKS.
- **Fewer trips.** A batch fills the whole sack. `getCarryCapacity()` already includes the Logistics lane's sack tiers and cart, and the batch mixes item types in the order the next steps need them.
- **Pause / reorder / rush.** Sites tab buttons: Pause, Resume, ▲, ▼, Rush, Cancel. The queue order is persisted.

## 11. MineColonies extras (owner, 26 Sep) — as built

- **Survey Rod** (`hearthstead:survey_rod`, a scan tool):
  - Right-click two corners, name the design (max 32 blocks per side, 8192 cells).
  - Saved per world in `hearthstead_player_designs` (overworld data, so a dedicated server keeps it and every co-op player sees it).
  - Shows in the catalog under **Our Designs**.
  - Captures block states only: no container contents, no entities, no fluids.
  - A plaque with a fitted plan makes the design a registering building.
- **Resource Scroll** (`hearthstead:resource_scroll`): right-click a Builder's Hut to link it. Right-click anywhere to open the Sites page on that Builder's site (needed / at hut / warehouse / on the way).
- **Work orders on the Banner screen:** the map lane renders them from `BuildSitesClient`. Actions go through `BuilderClientState.siteAction`.
- **Decorations:** blueprint `kind: "decoration"` (no plaque).
- **Style packs:** the catalog has a style filter, and Our Designs is listed first.
- **Hut-level gate:** Builder's Hut checklist levels.
  - L1: footprint ≤ 11, ≤ 2048 steps.
  - L2 (4 storage, 2 lights, 25 floor, solid floor): ≤ 17 and ≤ 3072, and it enables Upgrade Orders.
  - L3: ≤ 32 and 4096.
  - Defense lines are not footprint-gated.
- **Settings** (per settlement, Sites page):
  - Pickup: couriers (default; self-fetch only without a Courier), fetch himself, or deliveries only.
  - Fill: match (cobble under stone, dirt otherwise), dirt, or cobblestone.
- **Deconstruct any building** (Upgrades page):
  - Takes down every non-natural block of a registered building's room shell, top-down, refunding each block's own items and any container contents to the hut. The plaque's plan returns as itself.
  - Never the Builder's own hut or the Banner. Never blocks with a non-container block entity.
- **Scaffolding:** temporary ladder columns on the wall below out-of-reach work (settler pathing climbs ladders, not vanilla scaffolding). Ladders are real items, hung from the sack, taken down before completion and returned to the hut. They are persisted on the job.
- **Exact-once review (Codex T3b):**
  - State-aware satisfaction: `MaterialRules.compare` gives SAME / REORIENT / DIFFERENT. Material props must match, orientation is turned for free, world props are ignored.
  - Door and bed pairs are handled as one unit.
  - Dismantle refunds the present state and leaves blocks the player changed.
  - Sack excess is deposited, never dropped.

## 12. Evidence and phasing

- **Core first (owner):** blueprint house built in the correct order, material list + requests, save/resume, clear blocking status, auto-registration. Then:
  - the catalog UI + ghost;
  - defense lines;
  - Upgrade Orders;
  - pause/reorder/dismantle;
  - scaffolding climb.
- Anything not finished in this pass is listed in the lane report as a queue for the coordinator.
- Evidence levels are reported per piece: Implemented / Compiled / JUnit / GameTest / Seen in game.
