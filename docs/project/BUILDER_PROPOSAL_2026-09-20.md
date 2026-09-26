# Optional Builder — authorized implementation plan

Status: AUTHORIZED by Tobias on 20 September 2026, including all six stages.
Implementation coordinates source/QA ownership with the active raid task. This
plan does not replace its evidence. Tobias subsequently authorized a natural
Builder placement in the tech tree on 20 September, superseding the original
no-tech-tree-change restriction. Scope that edit to the optional Builder route;
the broader upcoming tree revamp is separate work.

## Owner outcome

Players retain unrestricted manual construction under existing settlement rules.
A Builder can construct a different commissioned building at the same time, or
cooperate on the same blueprint. He is the most technically capable construction
worker: he plans work, obtains supplies, crafts building components and furniture,
places them, handles access, and resumes interrupted projects.

Reference target: MineColonies-style preset selection and physical construction,
with original Hearthstead code, buildings, UI, sounds and animations. Better is a
testable aspiration, not an established comparative claim.

## Reference investigation and limits

Read-only source investigation, not execution or a whole-repository audit.
Initial default-branch discovery was superseded by the following 1.21 references:

- MineColonies release/1.21 commit `1e1b1d8ebf63bbd2c248736364fb59296a75c146`.
  Its gradle.properties declares Minecraft 1.21.1 and Structurize
  `1.0.832-1.21.1-snapshot`.
- Structurize release/1.21 commit `11982e0427028059cabe5be3fb13c8df4dcbdbd2`.
  This is a separately pinned reference, not verified as the exact dependency
  binary of the MineColonies revision. No integrated runtime claim is made.
- Both inspected repository LICENSE files contain GPL v3. Source is reference
  material; this plan does not propose importing code, assets or presets.

FACT: construction is separated into work orders, worker AI, material requests,
structure handling and placement handlers. The structure AI has clearing, solid,
weak-solid, decoration and entity stages; repair/upgrade paths omit initial
clearing. Position and stage are persisted.

FACT: Builder pathing selects a working position near the target and resets it
when navigation is stuck. Some branches permit continuation while changing the
working position. This is not proof of a navigation defect. It motivates checking
actual reach and access at each Hearthstead placement, rather than treating a
previously useful position as permanent permission to build.

FACT: private worker crafting exists; its resolver accepts recipes without an
intermediate workstation. The shared crafting resolver already limits recursion
depth. Do not claim MineColonies lacks crafting or dependency safeguards.

STATIC FINDING: `AbstractBuildingStructureBuilder.deserializeNBT`, lines 215–218,
checks for saved progress position and then indexes `BuildingProgressStage.values()`
using the saved integer without a local bounds check. An out-of-range saved stage
reaches an array-index exception at this expression. Not reproduced in Minecraft;
ordinary legitimate saves are not shown to produce that input. Hearthstead should
use stable stage IDs, validate them, and pause/reconcile unknown saved progress.

REPORT, NOT CONFIRMED CURRENT DEFECT: issue #11288 describes idle Builders on
MineColonies 1.1.1154 / Minecraft 1.21.1 and is closed. It is a source of interruption
test scenarios, not evidence that the inspected revision retains the problem.

Pinned source references:

- [Builder navigation](https://github.com/ldtteam/minecolonies/blob/1e1b1d8ebf63bbd2c248736364fb59296a75c146/src/main/java/com/minecolonies/core/entity/ai/workers/builder/EntityAIStructureBuilder.java)
- [Construction phases](https://github.com/ldtteam/minecolonies/blob/1e1b1d8ebf63bbd2c248736364fb59296a75c146/src/main/java/com/minecolonies/core/entity/ai/workers/AbstractEntityAIStructure.java)
- [Work orders and materials](https://github.com/ldtteam/minecolonies/blob/1e1b1d8ebf63bbd2c248736364fb59296a75c146/src/main/java/com/minecolonies/core/entity/ai/workers/AbstractEntityAIStructureWithWorkOrder.java)
- [Saved progress](https://github.com/ldtteam/minecolonies/blob/1e1b1d8ebf63bbd2c248736364fb59296a75c146/src/main/java/com/minecolonies/core/colony/buildings/AbstractBuildingStructureBuilder.java#L211-L219)
- [Private crafting](https://github.com/ldtteam/minecolonies/blob/1e1b1d8ebf63bbd2c248736364fb59296a75c146/src/main/java/com/minecolonies/core/colony/requestsystem/resolvers/PrivateWorkerCraftingRequestResolver.java)
- [Crafting dependencies](https://github.com/ldtteam/minecolonies/blob/1e1b1d8ebf63bbd2c248736364fb59296a75c146/src/main/java/com/minecolonies/core/colony/requestsystem/resolvers/core/AbstractCraftingRequestResolver.java)
- [Placement engine](https://github.com/ldtteam/structurize/blob/11982e0427028059cabe5be3fb13c8df4dcbdbd2/src/main/java/com/ldtteam/structurize/placement/StructurePlacer.java)
- [Block handlers](https://github.com/ldtteam/structurize/blob/11982e0427028059cabe5be3fb13c8df4dcbdbd2/src/main/java/com/ldtteam/structurize/placement/handlers/placement/PlacementHandlers.java)
- [Closed idle report](https://github.com/ldtteam/minecolonies/issues/11288)
- [Official schematic workflow](https://minecolonies.com/wiki/tutorials/schematics/)

## Player experience

1. Select an original preset or a validated scan of a player-built structure.
2. Preview its full footprint, rotate/mirror it, select supported material variants,
   and see terrain conflicts, access requirements and final/raw material demand.
3. Place a construction marker and assign a Builder. A marker and basic workbench
   must be manually obtainable; the Builder cannot require his own finished workshop
   before taking his first job. Use normal existing-resident hiring and a natural
   optional early unlock alongside established storage/logistics. Show the live
   price before payment; manual construction never requires that unlock.
4. Courier supplies the Builder's own base storage beside his real workbench.
   Players may supply that storage manually. Builder fetches only from his own
   base and carries materials to the project; he never visits production sites
   or village warehouses for supplies and never harvests raw materials.
5. Builder crafts missing components at a real bench, carries supplies, builds,
   furnishes and checks completion. Players keep building anywhere outside his site.
6. Pause, reprioritize or cancel explicitly. Cancellation leaves placed construction
   intact and releases unused reservations; real leftovers stay in real storage.

Within a shared site, correct player placements count toward completion without
charging materials again. A different player block becomes a marked conflict.
Default behavior never destroys that block or erases filled containers. Blueprint
air means leave existing space alone unless explicit clearing was previewed and
authorized. Jobs may not silently overlap ownership of the same cells.

### Portable Builder clipboard — owner correction, 20 September

Provide an original Hearthstead Builder clipboard item carried in the player's
inventory and opened from the hand. The owner explicitly corrected the initial
wall-mounted interpretation: the requested board is portable. Link it to the
Builder/project so the player can inspect progress while building elsewhere.
Reuse existing item/menu/network conventions; keep all authoritative state on
the server. The clipboard is the normal player entry to the Builder's active
project and orders, not a command-only debugging view.

The clipboard shows project/worker, phase and completed/remaining work; for each exact
material variant show required, available in base, reserved, ordered, carried by
Courier, delivered and still missing. Delivered is history, not a second stock
pool. An unclaimed order must not appear as an incoming physical delivery.
Show current crafting and carried construction goods with their physical owner.
List concrete actions needed from the player, including conflict coordinates,
blocked access, missing workbench, full base, missing recipe and absent supplies.
Support pause/resume/cancel and job priority with server-authorized actions and
freshness checks shared by simultaneous players. Cancellation leaves placed
blocks and physical leftovers intact. Preview/rotation/material review occurs
before commission; the board never grants implicit demolition or terrain work.

## Crafting and furniture

### Physical work and animation — owner clarification, 20 September

Owner wants the recognizable MineColonies Builder workflow with better animation,
and explicit proximity before placing a block. This retains the own-chest supply
rule above; it is not permission for remote placement or implicit demolition.
The Hearthstead implementation uses a maximum three-block eye-to-cell-center
distance for every cell of a placement unit, visible access and real standing
support. Three blocks is our current design value, not a claim about MineColonies'
exact internal constant. Walk to a reachable stance, stop, face the target, then
start work. Motion, lost footing or obstruction cannot spend material or place a
block. Recheck live range and authority immediately before the debit/placement,
including after cancellable protection callbacks.

The first motion integration reuses the existing21-tick mason motion under an
authoritative Builder phase: settle/start, preparation contact10, actual material
transfer on second contact31, recovery through42. No entity-ID phase offset.
Both placement and bench crafting use this phase; interrupted work reconciles
real inventory/world state, never a restored animation timer. Existing props and
this reused clip are NOT visual approval or the final better-animation deliverable.
Final acceptance needs the real held material/tool, target-relative pose, planted
feet, contact sound, recovery and transitions inspected in motion. Offline checks
and native evidence must use the existing serialized QA route after source handoff.

Reference behavior: [MineColonies Builder's Hut](https://minecolonies.com/wiki/buildings/builder/)
describes material requests, work orders and hut inventory. Use it as behavior
reference; retain original Hearthstead implementation and assets.

### Authoritative supply model — owner correction, 20 September

Production/storage -> existing Courier requests and physical delivery -> Builder
base storage -> workbench crafting -> physical carry to project -> placement.
Owner reaffirmed that the Builder fetches only from his own chest and orders the
rest. Each commission therefore persists one explicitly linked supply chest;
all ingredient pickups and surplus returns use that exact position. Other chests,
even inside the workshop, are not silent fallbacks. Courier parcels target that
same chest. Missing, full or unreachable linked storage produces an actionable
blocker. Warehouse inspection for choosing requests is not remote item transfer.
An order is intent, never stock. Missing raw material, absent Courier, blocked
route and full base storage must remain distinct visible blockers. Builder may
do other reachable supplied work while waiting; he must not log, mine, farm,
gather, teleport inventory or silently substitute a material variant.

Allocate finished goods in the base first, then account for exact reserved goods
and active deliveries once. Request only uncovered demand through the existing
request ledger, retaining action identity across repeated planning and reload.
If finished goods are unavailable, resolve supported live recipes into raw inputs
and request those inputs for delivery to the base. Do not simultaneously reserve
the same stock for two outputs or order both a finished component and its inputs
without reconciling their common demand. Worker interruptions leave physical
cargo/output owned and recoverable; cancellation releases intent, not real items.

Builder is a construction generalist, not a universal producer of weapons, food
and every modded item. He supports real 2x2/3x3 building recipes and an explicitly
tested furniture catalog. Proposed initial goods: planks, sticks, stairs, slabs,
doors, fences, chests, beds, tables and chairs. Finishing and stonecutting follow
the same physical workstation model where supported recipes require them.

Choose finished stock first, then outstanding deliveries, then craft the uncovered
shortfall. Show raw demand and avoid counting the same stock toward two outputs.
Resolve intermediate recipes with cycle detection, bounded depth, batch remainders,
exact variants/components and recipe-change checks. Never manufacture a missing
recipe or substitute a different wood finish silently.

Furniture is a deliverable: craft -> physical output -> carry -> place -> actual
use. Include standalone furniture orders for players' hand-built buildings.
Reuse the existing Another Furniture seating integration when installed and its
actual recipes are supported. Validate required mods before accepting a preset;
provide a core catalog without an undeclared furniture dependency. The first
furniture test must show an actual resident using the placed chair/table.

Specialists remain useful by supplying bulk goods while the Builder spends time
building. His self-crafting remains available; do not make him artificially helpless
just to force another profession. Skill improves measured work speed/complexity,
not free materials or multiplied crafting yields.

## Construction architecture

Keep separable responsibilities without inventing a framework before the first job:

- Versioned blueprint: bounded palette, block states, allowed metadata, material
  variants, dependencies and functional anchors. Server validates uploaded scans,
  size and palette; exclude stored inventory, entity UUIDs, commands and unsafe NBT.
  Unsupported blocks produce precise errors before ordering, not silent omissions.
- Saved construction job: stable ID, owner, blueprint revision, transform, approved
  footprint, reservations, completed actions, conflict state and temporary access.
- Planner: dependencies and work faces; foundation/frame, floors/walls, roof,
  fittings/furniture. Preserve entry and exit routes. Execute reachable tasks while
  reporting blockers; never mark inaccessible tasks complete.
- Worker: physical fetching, crafting, carrying, navigation and construction with
  normal food/rest/threat behavior. One active job per Builder; a site has one
  executing Builder initially, while several sites can run independently later.
- Placement adapters: normal blocks first, then doors/beds/multiblocks, attachment,
  rotation, waterlogging, supported block entities and furniture. Recheck expected
  world state, reach, material ownership and authorization immediately before commit.
- Supply/crafting: extend existing exact-item and transport seams. Persist an action
  identity so retry/reload cannot consume or deliver the same action twice.

Reuse inspected Hearthstead foundations: `ContainerApproach`, physical Courier
cargo/requests, `LumbererSelfCraftingService`, `CraftOutputEscrow`, relevant repair
material checks, worker lifecycle and Tavern seating. `Production.Recipe` is still
a single-input/count format; it is not a ready multi-input Builder recipe engine.
Do not copy repair's single-block assumptions into doors, beds or arbitrary furniture.

Restart reconciliation must distinguish world placement, carried/reserved items and
job progress, which may live in different save records. Test interrupted commits;
do not promise power-loss durability from clean-restart tests alone. Unknown state
pauses visibly instead of guessing, refunding twice or replacing a player's block.

## Access, terrain and feedback

### Builder appearance — owner reference, 20 September

Owner supplied `codex-clipboard-dd20182c-c393-4d63-a563-ed4dcd8f908b.png` as visual
direction: brown work cap, rolled pale shirt sleeves, worn leather apron, dark
muted neckerchief, tool belt, dark trousers and substantial boots. Adapt that
grounded craftsman silhouette to the existing Hearthstead settler model/UVs.
The reference sheet is not a production texture atlas. Its embedded headings,
logos, accessory labels and capability claims are not implementation requirements.
Tools/materials displayed in the hands must match actual work and owned cargo.
The player's portable clipboard remains separate from the worker's appearance.

First prove construction on a prepared site. The planned usable release includes
multi-floor access through owned temporary scaffold/ladder arrangements, real
placement/removal costs, safe exits and cleanup of only job-owned temporary blocks.
Choose access structures compatible with current navigation; ability to climb must
be demonstrated, not inferred from player movement.

Bounded foundation adjustment can follow. Major excavation, oceans/lava, arbitrary
redstone and untested mod blocks remain later capabilities, clearly rejected or
marked unsupported. No hidden clearing of occupied buildings or automatic filling
of caves. A staged prototype is not the full feature promised here.

Show target phase, completed/remaining work, available/in-transit/missing materials,
current crafting and exact blocker location. Limit retries, replan on relevant world
changes, and leave an actionable paused state when recovery fails. Limit planning,
scanning and path requests per tick; process only loaded areas and avoid forced
chunk loading as the default solution.

## Authorized delivery stages

| Stage | Deliverable | Required evidence before expansion |
|---|---|---|
| 1 | One original cottage preset, preview, job ownership, exact material plan and portable Builder clipboard | Rotations, conflicts, permissions and costs validated; clipboard distinguishes stock/orders/physical deliveries and reports fixes from the player's hand; no changes merely from preview |
| 2 | Physical Builder constructs the cottage from finished materials delivered to his own base | Existing Courier ordering/delivery, shortage/full storage, matching blocks and inventory accounting; player builds elsewhere or helps; pause/rejoin/restart |
| 3 | Integrated building-component and furniture crafting | Raw materials become exact goods; remainders preserved; furnished cottage and usable table/chair; standalone furniture order |
| 4 | Height/access and stronger recovery | Two-storey tower, scaffold setup/cleanup, blocked doorway, unavailable work face and interruption recovery |
| 5 | Preset catalog and player scans | Original cottage, storehouse and watchtower plus one player-designed building; mirror/variant checks and unsupported-block errors |
| 6 | Raid aftermath and shared settlement acceptance | Builder retreats/resumes, blueprint repair respects edits, real 3–4-player concurrency, performance and visual/audio review |

The full authorized scope includes all six stages. A later-stage failure prevents
calling the whole Builder finished. Do not cut crafting or furniture to label an
early block-placer complete. Subsequent upgrades and large terrain projects can be
separate extensions. Estimated calendar time awaits stage 2 navigation evidence;
this is a major feature requiring multiple implementation and gameplay cycles.

## Acceptance and comparison

Test material shortage/full storage; correct and conflicting player edits; another
player consuming the final ingredient; cancellation during crafting/carry/placement;
worker death; unloaded chunks; server restart; rejected placement; stale recipe or
blueprint; malformed save; tool loss; raid retreat; multi-block and furniture state.
For every destructive boundary, prove exact ownership and conservation.

Record completion rate, manual interventions, blocked time, unused/reserved stock,
time to resume and incremental server/client cost on fixed test worlds. Compare
MineColonies only using pinned runtime versions and matched supported scenarios;
report differences in capabilities and rules. No claim of overall superiority
without actual comparative playtests.

Implementation uses the existing QA entry and proportionate tests, with the bounded
read-only reviewer required by AGENTS.md for substantive code. Native visuals,
audio, performance and release evidence stay separate. The first implementation
acceptance is one original small cottage on a player-prepared site: validated
rotated preview and conflicts, exact material requirements, a base with physical
chest/workbench, existing Courier delivery, and actual worker placement alongside
manual building. Extend only after that loop has matching evidence. Ordinary
construction grants no tree clearing or excavation authority; any future terrain
work needs its own bounded order.
