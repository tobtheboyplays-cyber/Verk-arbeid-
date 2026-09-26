# ROADMAP — demo delivery and full overhaul, updated 7 September 2026

## Active direction — 16 September 2026

Owner completed ten decisions and authorized work using the remaining included
weekly allowance and the PC. Core repair first: connected six roles, especially
workers/motion/Tavern, then real defense/aftermath and shared-settlement play for
3–4 players. First-raid pacing target is 60–90 minutes; prepared defense should
handle ordinary attacks and defeat must allow meaningful recovery.
Current task and actual evidence override the historic dated queue below.
Apply QUALITY_STANDARD.md and JOB_STANDARD.md to all work. After verified core
acceptance, advance production, defense and village-life/trade through complete
small additions under the existing POST_DEMO_MASTERPLAN.md. Accepted details:
MECHANICS_DECISIONS_2026-09-07.md, 16 September answer table.

## Current mandate: connected survival demo for Tobias's return after two days

The owner superseded the missed evening deadline with roughly two days away; no exact new return hour is confirmed. He authorized root to choose the design and a detailed, cheaper-model execution plan. Latest design direction is Becastled-like survival pressure with connected village depth: meaningful material/Coins/staffing choices, early optional defense, announced threats and recovery that rewards preparation. Preserve the six requested jobs, dedicated Mayor, physical Coins, all active UI, real motion/audio, normal terrain and friend-compatible shared settlement.

Begin at `PROJECT_STATE/START_HERE_48H_2026-09-07.md`. Execution order and model/quota policy are in `48H_DEMO_RUNBOOK_2026-09-07.md` and the task cards. `docs/project/MECHANICS_DECISIONS_2026-09-07.md` and `SURVIVAL_BALANCE_2026-09-07.md` are the current target decisions, superseding conflicting earlier proposals. Specified targets are NOT implemented/verified by documentation. Full overhaul scope remains open beyond a partial demo.

Latest goal-tool observation: **PAUSED**; root cannot resume it with a file edit. Tobias must resume the existing goal in the app for automatic goal continuation. Main-model recommendation is Terra High, with bounded Luna Medium tasks; no claim the app selection was changed. Latest live evidence belongs in CURRENT_TASK and the existing quality ledger.

The dated delivery order and evidence below are retained as history, not the current clock target or running status.

## Historical delivery order: Monday 7 September, 20:00 Europe/Oslo

The owner had confirmed this target after declining the proposed 09:00/12:00
schedule because he is unavailable before the evening. It supersedes earlier
deadlines. This is a delivery plan, not a claim of autonomous execution or a
guarantee of readiness. The existing goal was observed PAUSED during planning;
resuming it in the app is required for automatic goal continuation. Do not
create replacement scheduled jobs or claim the goal was resumed by editing docs.

**Outcome:** a coherent personal demo in normal Minecraft terrain, with a
dedicated Mayor, Courier, Lumberer, Farmer, Guard, Archer and staffed Tavern.
Latest evening scope also requires ALL active UI to be reworked, real two-player
shared-settlement play and a functioning physical Coins survival economy.
These requirements supersede the older UI, friend-test and economy deferrals.
The 16:00 feature freeze and final verification buffer remain the planning
target; report any slip against the larger scope instead of hiding omissions.
The prepared village makes the work and battle easy to inspect. A separate
survival check establishes which recruitment, supply and raid milestones are
actually earned. Prepared buildings, stock or an explicitly started combat band
must not be reported as unassisted survival or a naturally triggered first raid.

### Starting evidence, verified 6 September around 23:10

| Area | Current evidence | Delivery implication |
|---|---|---|
| Packaging | Package1665 PASS; candidate93c01a251257b0c92112, 442 unit tests, zero failures/errors | Grounded full-village source builds; no world/production/raid acceptance yet |
| Installed profile | Personal-test candidate9cac remains installed; fresh 22:54 startup was observed | Preserve the user's session; a new build is not a running update |
| Gameplay regression | Last complete GameTest run72584 FAILED 7/744; later corrections have not been rerun | Retest the corrected batch and retain actual failures |
| Motion | Offline FARM_PLANT preview51142 passed direction review; other corrections are built | Continuous native movement, contact and transitions are still required |
| Combat | Both simultaneous 1-versus-5 Guard cases failed on the earlier tested candidate | Balance remains open; 48 HP alone did not prove the intended strength |
| Tavern/housing | Earlier bounded service evidence; Oak Chair proposal source-reviewed; persisted-bed allocation defect identified | Close actual service and bed ownership before certifying the new seating/markers |

Exact identities and artifacts belong in CURRENT_TASK and the quality ledger.
Historical evidence below remains useful only within its recorded scope.

**Latest owner playtest feedback takes priority over earlier partial passes:**
Guard feels weak; Archer did not fire after the player supplied arrows; Lumberer
did not approach/chop; Innkeeper appeared idle. Farmer produced successfully in
the owner's test but did not put its bag down for filling. Treat the four broken
role experiences as RED until diagnosis and a new actual playtest resolve them.
Do not infer a correct assignment/tool/ammo path from a QA name or substitute a
successful isolated fixture for the user's failing route.

**Shared bag requirement:** every sack-equipped job uses the same observable
put-down → stationary fill/empty → close/lift → carry sequence, including chest
delivery. Apply it to Farmer as well as Lumberer and Courier; future sack users
inherit the contract. Keep the physical cargo's single owner. Courier's gait
must communicate its actual heavier load through restrained lean, stance and
step timing without backward knees, foot sliding or permanently extended arms.

### Work sequence and protected verification time

Times are planning windows, not unattended-work or completion claims. If a
window slips, spend contingency on the blocker and report the consequence.

| Window, Oslo time | Work and owner | Required exit evidence |
|---|---|---|
| Sunday evening to Monday 02:00 | Root: verify normal-world controller, current regression and grounded village; builder prepares only fixes supported by failures | Actual terrain placement, all intended actors, real wood/food transport, clean pre-raid checkpoint or an exact reproducible blocker |
| 02:00–08:00 | Builder: critical AI/motion patch batches; tester: independent review, bed ownership and recovery cases; root: combat decisions and serial integration | Correct carry grips, forward work contact, stationary bags, useful Guard/Archer behavior; accepted patches with appropriate checks |
| 08:00–12:00 | Root and builder: finish one shared village loop; tester checks Tavern, housing, navigation and save interruption | Six roles work in the same world, exclusive beds/seats, actual food service, successful route and restart checks |
| 12:00–16:00 | Root: continuous native animation/audio/UI review, real raid and aftermath; tester reviews receipts and captured failures; bounded two-client check | A complete playable round, legible controls, observed contact sounds, saved outcome and honest multiplayer limits |
| 16:00 | Freeze the proposed demo feature set and source candidate | No new feature or aesthetic redesign after this point |
| 16:00–18:30 | Root: relevant final regression, full player route, clean load and installation preparation; builder handles only demonstrated blockers | Exact candidate, reproducible demo world, known-defect list and rollback copy |
| 18:30–20:00 | Delivery buffer: final smoke after any blocker repair; install only with game closed; concise English start guide | Confirmed installed/running identity, correct world, start location and resettable pre-raid copy |

A repair after the freeze invalidates affected acceptance and must be retested.
Do not squeeze a late feature into the buffer or quietly skip the failed check.

### Demo acceptance: what must be visible and real

1. **Production and transport:** Lumberer reaches trees, clears legitimate
   blocking leaves, chops, collects forward, visits the distant bag and unloads
   individual items. Farmer approaches and faces actual crops, plants/harvests
   forward and delivers. Courier grips its real load, puts the bag down and
   transfers actual stock. Missing tools, blocked routes and full storage have
   understandable recovery; interruption/reload preserves goods.
2. **Movement and sound:** inspect several continuous normal-speed cycles of
   walking, carrying, work, pickup, deposit, eating and combat. Feet, body lean,
   hands, equipment and world-fixed bags agree. Contact sound follows actual
   contact; no Courier cart noise, incessant chatter or overlapping sound spam.
   Listen to captured audio. Stills and parser checks do not approve game feel.
3. **Defense:** dedicated Mayor remains civilian leadership; civilians flee
   toward shelter and signal danger, Guards respond purposefully and recover
   through actual food, and Archers draw/release without endless escape loops.
   Guards start with wooden swords and no free iron/shield. Preserve the strong
   Guard direction and 2 Guards + 2 Archers ordinary-defense target; compare
   named enemy/loadout combinations instead of assuming every Guard must defeat
   every group of five. Never remove a failed balance test merely to pass.
4. **Raid:** show actual enemies approaching through normal terrain and the
   defenders engaging with real health/ammunition. Verify warning, combat,
   outcome, earned reward and saved aftermath where claimed. A combat-only
   fixture and the natural milestone-triggered raid are separate checks.
5. **Tavern and residents:** Innkeeper stays useful inside, serves real food,
   handles tabletop props, welcomes an entering player and has restrained
   audible humming. A genuine visitor is not already a resident; recruitment
   refuses without a valid free bed and charges the actual displayed quote.
   Check seated approach, meal, interruption and safe exit. Each valid free bed
   is yellow and each valid assigned bed green, including daytime and unload;
   allocation and projection must share the same exclusive ownership.
6. **UI and delivery:** English labels, real names, readable costs/disabled
   reasons, clear health bars and direct-click Blessing selection. Inventory
   uses the sole currency display name **Coins**. Verify the existing shader,
   JEI, Mouse Tweaks and minimap profile as relevant, without new dependencies.
   Load, play, save and reopen the exact demo candidate; report actual tested
   player count rather than claiming 50–100 active residents from idle tests.

The new Oak Chair and player seating remain requested work. NPC-only source
acceptance cannot satisfy player use. If this optional furniture addition would
jeopardize the core loop, retain functional existing seating and explicitly list
the custom-chair limitation; do not omit it silently. Broader production
families remain in the post-demo sequence. Coins, quality-aware manual merchant
sales with bounded demand, physical treasury/recruitment/unlock payments, paid
Tavern visitors and raid income are now current demo work. Verify a new player's
gather → first sale → spend → worker output → improved sale route, full storage,
insufficient funds and reload/replay. Avoid free unlimited startup rewards.

All active screens and embedded panels require a coherent original Minecraft
design, readable information and intact actions, with current before/after
evidence. A real two-player check includes shared actions, last-item/last-bed
contention and save/rejoin; merely connecting two idle clients is insufficient.

### Faster execution without reducing the quality bar

- Root owns design, cross-system decisions, shared files, integration and one
  canonical QA/native queue. Builder owns one bounded patch; tester challenges
  it against actual failure cases; the third agent handles a separate concrete
  navigation, ownership or evidence task. Reuse the existing agents.
- Define the player-visible check before coding. Give each task exact ownership,
  current evidence, output path and stopping condition. During source freeze,
  prepare isolated proposals only. Do not duplicate audits or compete for the
  running game.
- Integrate compatible, independently reviewed fixes as one batch. Review once,
  resolve concrete findings, then run decisive tests. Reuse unchanged passing
  evidence and checkpoints; rerun when behavior, identity requirements or a
  concrete failure justify it. No repeated clean build for a small doc change.
- Limit work in progress to one integrated candidate and at most three isolated
  proposals. Investigations without progress for roughly 45 minutes get an
  alternative bounded approach or explicit deferral, never weaker acceptance.
- If terrain placement fails, inspect a suitable real normal-terrain site; do
  not substitute superflat, sky platforms or fabricated output. Keep the
  prepared resources explicit. Do not edit the user's active world to rescue QA.
- Status uses green for the stated check passed, yellow for implemented or
  partially checked, red for a demonstrated blocker. Report completed acceptance
  items, next action and risk to 20:00; no invented overall percentage. Send a
  short start/result update for each bounded task.
- Public release remains a later milestone with unchanged fullx2, gate and
  exact-candidate native release-client-gate requirements. A useful personal
  demo is not a public-release approval or a claim that the whole overhaul is done.

**Immediate next action:** canonical doctor for the new normal-world settings,
then rerun current gameplay corrections and the grounded-village production
scenario on candidate93c01a. Use the outcome to choose the first fix. Preserve
installed9cac while Tobias plays. Live process details belong in CURRENT_TASK.
In parallel, independent Temp proposals diagnose combat/ammunition, Lumberer
and Innkeeper inactivity, and the common bag lifecycle. Those explicit owner
failures outrank additional furniture polish or new town incidents.

**Demo scope — explicit owner decision, 6 September:** exactly **Courier,
Lumberer, Farmer, Guard, Archer and Innkeeper**. Deliver their existing production,
transport and defense experience through the first raid's persisted Aftermath,
including the shared Hearth, equipment, storage, housing/recruitment, save,
UI, animation, sound and co-op support they need. Other professions and new
production chains are deferred until after the demo and explicitly started.
The broad system register below remains the long-term overhaul inventory;
out-of-demo roles are not prerequisites for this demo. Prepared Hunter and
CLEAVE proposals and the cancelled Miner expansion remain deferred.

**Forward design, 5 September:** [POST_DEMO_MASTERPLAN.md](POST_DEMO_MASTERPLAN.md) is the dedicated plan for advanced production families, progression and the contracts current demo work must preserve. The owner retained the existing demo and deferred the four additional chain showcases. Plan future connections now; do not add them to the demo or treat this planning request as implementation approval. The current coverage and quality requirements below remain in force.

The owner's **Full Redesign and Production-Ready Overhaul** mandate supersedes the earlier Recruit/Town Hall design locks and the old phase ordering below. Active platform remains **NeoForge 1.21.1**. Preserve worlds, stable IDs, real inventories and useful implementations; a full overhaul covers every player-facing system, not every source line. No automatic purchases, banked resets, recurring jobs or external publication/distribution are authorized. The latest language direction is English-only mod content and project documentation, with Norwegian permitted in conversation with Tobias. Retire Norwegian localization/parity requirements, preserve English completeness and stable keys. The larger UI visual redesign is deferred at the owner's request; current readability checks do not approve its art direction.

This is the active coverage/sequence in the existing roadmap, paired with `hearthstead-neoforge/docs/HEARTHSTEAD_QUALITY_LEDGER.md`. Historical plans below are context, not current certification. In particular, the former approximately-50-test baseline, unreleased-archer/Blessing claims, emblem rejection, reference-layout copying requirement and automatic CurseForge destination are superseded. Earlier arbitrary session estimates and palette formulas do not establish quality.

## Chosen direction — six connected decisions

1. **Player fantasy:** build a recognisable medieval civilian village whose inhabitants understand their work. Workers lead with workwear, face, posture and carried material; military equipment belongs to real defenders. The approved eight-role stills establish material readability, but their shared rear loads and lack of motion are limits, not final character approval.
2. **Core loop:** the player builds, chooses workplaces/groups and resolves meaningful shortages; citizens obtain available tools, perform observable work, deliver physical output, eat/rest and improve. First-hour employment and a small self-supporting settlement around 1–2 hours remain pacing hypotheses to measure, not claims or artificial waits.
3. **Art direction:** Minecraft-scale pixels and block geometry; calm linen/parchment reading surfaces, moss-green navigation, charcoal/iron structure and restrained copper emphasis. Parchment means a flat readable material, not the rejected painted table. Preserve independent skin/hair/face/clothing axes; distinguish roles with actual garment construction and tools before adding hue/noise. Keep active cargo/ammunition truthful. Continue the pixel UI already implemented rather than restart from an unrelated reference.
4. **Interaction:** workplace and citizen actions share one vocabulary: current task, exact blocker, real next action, confirmed result. Keep screen ancestry and keyboard focus coherent, detailed hover accessible, work-area geometry explicit, full costs visible and disabled reasons actionable. Use local world markers and selection outlines; no map dependency, fake controls, invented stock or success before server acceptance.
5. **Motion and audio:** anticipation/contact/recovery follow actual work state; visible contact, item transfer and its sound agree. Carry/idle/interruption transitions must remain believable. Inspect audible distance, overlap and variety with several workers; sparse optional vocalizations supplement text and motion. Existing sound files or motion-check PASS alone cannot certify feel.
6. **Delivery:** first prove one integrated Lumberer journey, then apply its standard through the entire existing coverage below. Preserve depth and old-save compatibility. A polished reference slice is the quality bar, not permission to stop or silently shrink the overhaul. New children/seasons/caravans/classes belong to future ideas unless needed for an existing complete loop.

## Coverage of the current product

Paths in this table are relative to `hearthstead-neoforge/src/main/java/com/hearthstead/` unless prefixed otherwise. **UNTESTED** means the stated verification layer is missing from this focused review; it does not mean there is no implementation or no older test. Existing evidence is exact-candidate and must not be transferred to a new JAR. B1/B2/B3 identify the coherent work sequence below, not blanket acceptance of their rows.

| System / concrete source surface | Current state and evidenced weakness or gap | Observable result / coherent work | Verification required |
|---|---|---|---|
| Founding, Hearth/Town Hall, tutorial/Handbook, Mayor, paid plans and emblems (`registry/ModItems`, `ClientHooks`, `HearthScreen`, `HandbookScreen`, `EmblemShopScreen`, `settlement/development`) | Implemented; compact screen and physical purchase evidence exists. Old roadmap incorrectly rejects emblems. Command-assisted fixtures do not prove a newcomer can discover the whole start. | B1: found, understand the Mayor and real price, obtain plan/emblem and reach first productive employment without external explanation. Rework confusing steps, not just labels. | Fresh survival through actual UI; record elapsed active/wait time and every outside hint. Exact costs, repeat-click refusal and full keyboard path. |
| Building recognition, plans, housing and furnishing (`building/BuildingType`, `settlement/BuildingManager`, `block/PlaqueBlockEntity`, `PlaqueScreen`) | Thirty-three building types are declared; presence does not prove supported survival use. Plaque Requirements/Staff/Housing exist; complete new-world building comprehension is UNTESTED here. | B1/B2: each usable room explains exact missing requirements, capacity and staffing; breaking/rebuilding recovers cleanly. Legacy/parked rooms must not imply working services they lack. | Actual plan insertion/survey, blocked and repaired room, staffing/bed claims, removed plaque, reload and full housing tabs. |
| Employment, personal identity, attributes/traits, training and equipment (`entity/Profession`, `Trait`, `settlement/Employment`, `SettlerScreen`, `SettlerInventoryScreen`) | 26 stable profession values including NONE; actual hiring/training code and refreshed screens. Static outfits do not prove personality, training value or reassignment quality. | B1/B2: recognise citizen and trade, understand fitness and consequences, hire/reassign/fire while conserving gear/experience. All can learn jobs with meaningful advantages. | Real hire/emblem use, training after completed work, truthful bag/hand state, death/reassignment and save roundtrip; original-resolution compact/large UI and moving characters. |
| Work zones and replacement requests (`client/workzone/WorkZoneClient`, `WorkZoneConfirmScreen`, `EquipmentRequestListScreen`, `settlement/equipment`) | i33416 proves one replacement request and valid/refused/retried physical zone selection. Continuous native task discovery plus feedback is missing. | B1: choose exact tree area, understand boundaries and changed-area refusal, supply/recover a tool and resume the same job. | Selection through current controls, no commit on stale area, full reason/focus/escape and exact delivered axe; repeat after reload. |
| Lumberer and visible material handling (`LumbererWorkGoal`, `LumbererSelfCraftGoal`, `GroundCollectionSession`) | Two trees -> two saplings/eight own-chest logs verified in i33416; planted sack, real pickup and chest transfer exist. That run has no continuous native audio/motion/reload proof. | **B1 reference:** complete the genuine paid/hired/equipped journey and recover mid-work without loss, duplication or opaque waiting. Improve demonstrated contact/handling/readability together. | Continuous actual-client recording with sound, physical assertions and same-save reload mid-cycle; axe/bag/offhand/world/chest accounting and full-store/removed-workplace interruption. |
| Food, Farmer, eating, sleep, morale and village rhythm (`FarmerWorkGoal`, `EatFromHearthGoal`, `RestAtNightGoal`, `DayPhase`, `SettlerEntity`) | Present; FOOD durability and server behavior evidence exist. Sustained new-design survival balance and readable cause/effect remain UNTESTED here. | B2: harvest/plant/deliver/eat/rest visibly sustain citizens, with recoverable early shortages and understandable escalation. | Several complete day/night cycles; exact food movement, bed/meal conflicts, no seed/output invention, save during meal/work, recorded warning comprehension and pacing. |
| Courier, Warehouse, stock, production inputs, FOOD/equipment/ammunition (`CourierWorkGoal`, `TidyWarehouseGoal`, `logistics`, `StorageScreen`) | Physical request/reservation/persisted recovery paths tested; Storage is intentionally read-only. No capacity/throughput or multi-player playability claim follows. | B2: visible pickup/haul/deposit, bounded retries and precise shortages; recover cancellation/death/unload/full destination without duplicate cargo. | Existing physical regressions plus actual route observation and one interrupted loaded route; truthful stock UI and competing couriers; measured completed deliveries per game day. |
| Survival trades vs legacy/parked trades (`Profession:10–106`, `Employment:129–190`, `DevelopmentNode:25–140`, `building/Production`) | **Nine purchasable emblem trades:** Lumberer, Courier, Farmer, Innkeeper, Guard, Archer, Sawyer, Scholar, Hunter. Remaining implemented professions are not equivalent to released survival loops. | B2/B3: apply the same end-to-end standard to reachable trades; explicitly review legacy Baker, Cook, Butcher, Smelter, Smith, Carpenter, Mason, Fletcher, Weaver, Tanner, Miner, Miller, Brewer, Armourer, Herder and Fisher. Preserve saved jobs and mark availability honestly; release a parked branch only with a complete useful loop. | Per-trade actual employer/goal/input/output/contact/sound/save evidence. Registry existence, a spawn or historical certification table is insufficient. Do not erase this row by hiding old-save functionality. |
| Tavern, recruiting, travelers and growth (`SettlementManager`, `TravelerJoinGoal`, `InnkeeperWorkGoal`, `journey/JourneyServerHooks`) | Waiting-guest Watch routing and idempotent saved observations repaired; 672-test behavior evidence includes these. Isolated seeded observations do not prove natural arrival or affordable growth. | B2: player sees readiness/blocker before waiting, pays once, watches real arrival, and retains transaction through capacity changes/save. | Legitimate housed/food/tavern prerequisites, physical traveler route/admission/payment, full house and repeated reopen/reload. Measure time to first worker and stable growth. |
| Development, doctrine progression and research (`DevelopmentScreen`, `ResearchScreen`, `DevelopmentState`, `ResearchProject`) | Paid doctrines can be learned subsequently; six research IDs retained, only three accept new starts. Three unreleased-consumer projects refuse payment; Seasoned Timber can be planned before Sawmill. Research through a genuinely earned Study is not proven by existing UI shots. | B2/B3: dependencies and real effects lead to meaningful planning; impossible paid projects remain unavailable with reasons. Keep active/completed legacy data and cancellation. | Real earned Study/project start/cancel/complete and observed production/Guard/Farmer effect, no fake unlock; cost conservation, old schema roundtrip and full wrapped/focused compact UI. |
| Defense orders, Guards/Archers, ranks, projectiles, equipment and casualty response (`GuardOrderScreen`, `Guard*Goal`, `ArcherAttackGoal`, `GuardRank`, `ArcherRank`) | Substantial physical/server coverage; orders UI exists. A good 2-Guard/2-Archer ordinary defense with chosen protection/retreat priorities remains a player-feel hypothesis. | B3: clearly issued orders govern targets/position/risk; real equipment and ammunition match visuals; loss is meaningful but recoverable. | Actual order input and combat recording, low health/interruption, friendly bodies/doors, empty quiver, equipment break, path failure and reload. Distinguish ordinary attacks from captain/large raids. |
| Raids, pressure, warning, captains, theft/breach/fire, aftermath, repair and seals (`settlement/raid/RaidDirector`, `Raider*Goal`, `RepairWorkGoal`, `BlessingScreen`) | Milestone/first-raid and physical seal UI logic exist. QA-granted offers are not victories. Native i8d286's unguarded founders died to ordinary night mobs; that is evidence of exposure, not calibrated raid balance. | B3: a legible warning precedes an earned first raid; defenders achieve observable outcomes; aftermath/repair/reward reconcile real losses and goods. | Legitimate first-raid start-to-aftermath through UI, real victory/defeat/recovery, seal bind/rank, repair material accounting, repeated raids and save during raid. |
| Hunter, wildlife and ammunition (`HunterWorkGoal`, `HunterShotEvents`, `HunterPhysicalGameTests`) | Real hunting and durable ammo tests exist; new reloaded Arrow engine-hit regression passed in behavior18513 (673 required GameTests; 2,736 samples, 178 distinct test figures, 950 diagnostic events, zero findings). Offline motion frames do not prove native release/impact alignment. | B3: physical ammunition feeds a recognisable draw/release/recovery and leased wildlife loot, while protecting the population floor. | Actual employed Hunter with physical restock and repeated native shots/loot; verified saved-projectile regression plus, target/owner invalidation and empty quiver. |
| All 13 screens, in-world information and accessibility (`client/screen`, `SettlerRenderer`, `PlaqueRenderer`, `WorkZoneClient`, `ClientHooks`) | All 13 have current implementations; bounded subsets are visually reviewed. World names/status plates/plaque lamps/area outlines are UI too. A universal HUD or map is not assumed. | B1–B3: task-first hierarchy, correct ancestry, one tooltip, current-child focus, text/icon states and no hidden slot interaction. Retain full information. | Screen register below; 320×240 minimum logical plus 427×240/desktop, actual scale recorded (option 4 may clamp), long text, mouse/keyboard, selected/disabled/empty/loading/refused/success. |
| Characters, raiders, clothing, armor, tools and props (`SettlerModel`, `RaiderModel`, render layers; `tools/gen_settler.py`, `gen_raider.py`, `gen_armor.py`) | Eight workwear roles/28 actual stills accepted for bounded appearance; remaining variants/roles and raider/armor families are UNTESTED against this new direction. Static loaded Lumberer back reads as a brown strapped load, not distinct log ends. | B1–B3: connected civilian garments, readable materials/roles at distance, correct held gear and cargo silhouettes; retain independent variation and editable generators. | Actual front/side/back, near/normal distance, daylight/night and movement; cargo/empty ammo/armor variations. Compare real matched frames, not authoring mockups. |
| Blocks, items, GUI textures, models, recipes/loot and resource delivery (`registry/ModBlocks`, `ModItems`, `assets/hearthstead`, `data/hearthstead`) | Two registered block types; plans/emblems/seals/material intermediates and generated variants. Resource files exist, but whole-family art and player comprehension are UNTESTED here. | B1–B3: consistent pixel density/materials and understandable icons; all ordinary recipes/loot serve truthful reachable actions. Inventory icons and placed appearance agree. | Enumerate source registrations and model variants, actual held/placed/inventory/light states, recipe obtainability and data loading; origin/license and editable-source checks. |
| Animation and feedback (`SettlerAnimations`, `RaiderAnimations`, `SettlerModel`, actual work/contact/event callers) | Authored clips/checker and offline capture exist; known NO_GO warnings retain rejection. Static screenshots are not motion acceptance. | B1–B3: deliberate motion, stable contact/feet/grip and clean state transitions, matching actual progress and held equipment. | Supported strict motion/capture route, then actual gameplay video with interruptions, repeated cycles, normal speed and multi-angle review; no silent NO_GO approval. |
| Audio/atmosphere (`registry/ModSounds`, `sounds.json`, `tools/gen_sounds.py`, licensed `tools/audio_sources`) | 47 sound definitions and 70 OGG files observed; names/registrations do not prove listening quality. Chop contract declares 20-tick cycle/contact tick 11. | B1–B3: useful audible material/contact distinction, subdued UI confirmation, spatially sensible volume and sparse vocalizations; no mass-worker sound spam. | Listen to actual synchronized captures, single/several workers, near/far and repeated actions; inspect clipping/overlap/variation and sound settings. File validation alone stays insufficient. |
| Save/network/co-op, performance, architecture and packaged delivery (`SettlementSavedData`, payload/menu authority, `SettlerTextureCache`, QA controller) | behavior40877: 672 tests, 2,569 samples, 182 figures across fixtures, zero findings. This is not concurrent capacity. Historical native/UI captures differ by JAR; new batch is pending. | Across B1–B3: preserve IDs/items/worlds under interruption; shared ownership works for friends; bounded tick/render/network costs; clean exact-candidate installation. | Save/chunk/death/reassignment/migration regressions; 2–4 real players sharing a settlement; representative 50–100 total citizens only after baseline/metrics agreed and measured. Exact machine/settings, tick/render/memory/network measurements, clean install/native identity/release gate. |

### Explicit screen coverage register

`HearthScreen` (including Mayor/Journey/Requests/Recruitment/readiness/aftermath), `SettlerScreen`, `SettlerInventoryScreen`, `PlaqueScreen`, `StorageScreen`, `EquipmentRequestListScreen`, `WorkZoneConfirmScreen`, `GuardOrderScreen`, `DevelopmentScreen`, `ResearchScreen`, `HandbookScreen`, `EmblemShopScreen`, `BlessingScreen`. Evidence on one tab, one state or one profile does not certify the entire screen. Admin spawn eggs/QA menus and dormant branches are inventoried as tooling/legacy scope, never passed off as survival routes.

## Existing batch context — current timed order above takes precedence

**B1 — The working Lumberer reference.** Retain the existing bounded production evidence and inspect the current motion/recovery changes against it. Use legitimate founding, Mayor, paid Timber Rights, plan, employment/emblem, physical axe and work-zone controls where claiming survival progression. Observe two four-log trees become two saplings and eight logs in the correct chest, with empty loose/bag/offhand logs. Add save/reload during the same worker's action and inspect restored task/gear/cargo. Capture comparable original frames and a continuous audible native sequence covering approach, chop, pickup, carry and chest return. Fix concrete mismatches in the whole chain together. Reference: `qa/scenarios/lumberer_return_smoke.txt:69–142`; existing command-assisted proof is a reusable physical oracle, not a first-time-player completion claim. Hunter work remains deferred.

**B2 — The self-supporting village day.** Extend that same standard through Courier/Warehouse/equipment, Farmer/food, homes/sleep/morale and Tavern arrival. Carry one shared settlement through work, meal, rest, growth and a loaded-route restart, including missing tool/full storage/unreachable goal. Build the genuine supported production/Study planning path once prerequisites are earned; review legacy/parked trade contracts before opening additional paid content. Measure waiting and comprehension, then adjust actual friction without flattening logistics.

**B3 — The defended village and integrated candidate.** Complete Guard/Archer orders and real ammunition, first warning-to-raid-to-repair/aftermath/seal and supported post-raid outcomes. Roll the proven art/motion/audio interaction standard across the remaining catalogued role/material/screen families after the six-role demo. Re-run the complete first-hour-to-defense experience with shared players and measured load. Every required coverage row must have its relevant verification layer before calling the whole overhaul complete; these three batches are work order, not a shortcut around the matrix.

Historical numeric pacing/certification claims below are hypotheses or old evidence until revalidated. New features outside the matrix do not extend the finish line. Preserve stable saved identities and useful source; test any necessary migration before touching real user data. Owner taste/feel remains an independent final judgement.

---

## Historical roadmap — preserved, superseded where inconsistent

Physical **Coins**, emblems, current Archers/Blessings and the dedicated Mayor
supersede historical prohibitions or future-only descriptions below. Coins are
physical stock, not permission for a second virtual settlement currency.

# ROADMAP — the updated long-term plan (2026-08-25)

*Full review of where the mod actually stands against DESIGN.md's ten
systems and the original A1→B2 phasing, plus a deliberate gap analysis
against the two references (MineColonies, TekTopia). This supersedes the
phase ordering in the original plan where they differ; DESIGN.md's vision
and the permanent invariants stand unchanged.*

## Where we actually are (honest state)

The original plan expected us to be mid-A2. Reality: the fleet sessions
pulled several B-phase systems forward. LANDED and code-verified (gate run
pending): plaque room engine with grace period; hire/fire employment with
candidates and costs; five attributes + traits + training; Dagsverk effort
limits + skill-scaled farm plots; day rhythm/postings; road preference +
desire paths; warehouse/courier with reservation ledger; tavern recruiting
with goods price; INNKEEPER; mayor + boons; guard ranks + leap; raid
pressure/telegraph/theft/defense report; NAMED captains with succession
(saga v1); research v1 (Prøvebenken, in flight); six chain items (in
flight); 33 keyframe clips punched up + side-swing felling; five real trade
sounds; premium UI kit + five screens measured in two languages; QA: ~50
GameTests, live harness, fast client boot.

## Gap analysis vs the references

### From MineColonies — adopt
- **Requests board** (their postbox/request system, their best legibility
  idea): a Tingbok tab listing what every building currently wants
  (restock shortfalls, research materials, repair goods) with courier
  status. Ours stays chest-true — the board READS the ledger, never
  creates items. → C2.
- **Hunter candidate integrated; Fisher remains planned**: paid Border Wardens now opens
  only the physical Hunters Lodge and Hunter emblem. Hunters consume real bows
  and arrows and return physical wildlife goods. Fishery stays out of survival
  progression until its interruption, employer and water guidance contracts
  are complete. → C1 for Fisher.
- **Happiness breakdown**: morale exists; SHOW its components on the
  settler sheet (food variety, sleep, job satisfaction…) the way `why`
  does for AI. Legibility is our brand. → C2.
- **Colony border visualization**: a toggle on the hearth screen that
  shows the radius as a particle ring for ~30s. Cheap, answers the most
  common "why won't it register" confusion. → C2.
- **Guard classes (archer/knight)**: user-deferred; keep at D1.
- **University tree** → covered by Prøvebenken + traditions (D1).
- REJECT: builder/schematics (violates "settlers never construct"),
  taxes/abstract currency (violates chest truth), the 30-trade sprawl
  (quality over count; certify what exists first).

### From TekTopia — adopt
- **Building tiers via furnishing** (their structure-quality feel, already
  promised in DESIGN system 1): tier 2/3 via contents, quality feeds
  morale + capacity. → C1.
- **Patrol waypoints**: player-placed watch posts guards actually walk
  (banner/post item). Pairs with GUARD-2's captain. → C2.
- **Children + school** (their strongest life-sim beat): B2 as planned,
  after the core loop is gate-green. → D1.
- **Bard + festivals**: diegetic music/saga performance → D2.
- **Nomad/merchant caravans** → D2 with the outside world.
- REJECT: emblem/token hiring (user replaced with hire/fire), fight-night
  arena (fun, not core), villager nitwit (a Trait covers the flavor).

## The re-sequenced plan

**C0 — GATE (now, blocking everything):** integrate the fleet, gametest
suite green with fix-loops, full ×2 green_streak ≥ 2, RELEASE_GATE, the
showcase film per SHOWCASE_PLAN.md. The mod must be PLAYABLE and PROVEN.
**C1 — Complete the living village (1-2 sessions):** Fisher lifecycle;
building tiers via furnishing; COSTS-1 central pricing with village
discounts; GUARD-2 armor ranks + Vaktkaptein; scholar/summons/research
polish from the fleet's follow-up notes; wire Research bonuses into
Production; PICKUP_STOW triggers at every pickup site.
**C2 — Legibility & command (1-2 sessions):** Tingbok requests board;
morale breakdown; border toggle; patrol waypoints; horn/banner command
wheel v1 (rally/hold/shelter); healer + downed-not-dead rescue (infirmary
exists) — the last A3 promise.
**D1 — Depth:** archers + knight class; traditions tree + hearth tiers;
seasons/winter; fire/wolves/sickness; kidnapping + camp rescue; Blessings
v1 (post-raid card reveal — the roguelike loop the vision leads with).
**D2 — Living world:** children/school/marriage; bard + festivals;
caravans + NPC villages + rivals; second/third faction; full nemesis
growth; saga chronicle UI.
**1.0:** balance from live play, English completeness audit, trailer, private
beta → CurseForge.

## Working rules that got us here (keep)
Fleet parallelism under strict file ownership with the coordinator
playing/testing live and feeding findings back; constitutions
(FLOWS/COSTS/JOB_STANDARD) before content; every claim gated by the QA
system; the user sees films, not promises.
