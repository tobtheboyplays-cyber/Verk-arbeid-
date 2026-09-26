# Hearthstead elite mod-stack audit

**Date:** 2026-09-01  
**Scope:** clean-room comparison of public, legitimate sources relevant to
Hearthstead's settlement AI, UI, animation, audio, pathfinding, onboarding,
performance and raids.  
**Decision rule:** observe principles and documented tools; do not copy or
extract third-party code, assets, layouts, wording, animations or sounds.

## Executive decision

Hearthstead does **not** currently need a broad dependency-stack migration.
The active module is already a lean Minecraft 1.21.1 / NeoForge 21.1.248 mod
with an unusually substantial internal QA and preview toolchain. The main gap
is not a missing framework: it is enforcing one proven execution contract
across every job, one measurable layout/performance contract across every
screen, and one deterministic combat director across the full first-raid
journey.

The best immediate choices are:

1. Finish a shared worker-task kernel with explicit state, blocker reason,
   reservations, bounded retry/backoff and durable inventory authority.
2. Build the first raid around a server-authoritative squad director with
   knight/archer roles and per-target engagement caps, not independent guards
   all selecting the same target.
3. Keep Hearthstead's original UI kit; enforce responsive-layout, function and
   frame-time gates on every screen. Use `spark` only as a development profiler,
   not a shipped dependency.
4. Keep the existing Blockbench/native animation route for the demo. Evaluate
   NeoForge's native JSON animation loader on one future clip before considering
   GeckoLib; do not migrate 86 existing clips during demo stabilization.
5. Add an optional JEI integration after the current demo gate. Keep the custom
   Hearthstead handbook. Ponder is a strong later candidate for visual build
   tutorials; Patchouli needs an explicit license/product decision.

## Current Hearthstead baseline — FACT

The following is direct local inspection, not inference:

| Area | Current fact | Evidence |
|---|---|---|
| Platform | Minecraft **1.21.1**, NeoForge **21.1.248**, JavaFML loader 4+, mod version 0.2.0. | `hearthstead-neoforge/gradle.properties:5-7`; `src/main/resources/META-INF/neoforge.mods.toml:1-30` |
| Runtime dependencies | The manifest requires only NeoForge and Minecraft. The Gradle dependency block contains JUnit test dependencies only. | `neoforge.mods.toml:20-30`; `build.gradle:334-338` |
| License | Hearthstead currently declares **All Rights Reserved**. | `neoforge.mods.toml:3` |
| UI | A custom `HsUi`/token pipeline already exists. It uses constant-cost stretched nine-slice surfaces, generated tokens/assets, cached labels and explicit UI sound semantics. | `client/ui/HsUi.java:18-77`; `tools/gen_ui.py`; `tools/ui_preview.py` |
| UI profiling | The native client observer has a fixed 360-frame ring and reports p50/p95/p99/max plus >33 ms and >100 ms counts with exact runtime-JAR identity. | `client/QaClientObserver.java:51-89,390-419` |
| UI breadth | Static inventory found 13 Java files in the major screen package. Layout/cache tests already exist for several key screens. | `client/screen`; `src/test/java/com/hearthstead/client/screen` |
| Animation | Static inventory found **86** native `AnimationDefinition` declarations. Settler and raider Blockbench sources plus exporters, strict checker and preview renderer already exist. | `client/model`; `tools/blockbench/settler.bbmodel`; `tools/blockbench/raider.bbmodel`; `tools/anim_check.py`; `tools/anim_preview.py` |
| Audio | Static inventory found **50** registered custom sound events and **70** shipped `.ogg` files. A CC0 source manifest and original source-license copies already exist. | `registry/ModSounds.java`; `assets/hearthstead/sounds`; `tools/audio_sources/manifest.json` |
| Deterministic QA | The module has JUnit, NeoForge GameTest-server configuration, native-client scenario tooling, exact artifact identity, and reproducible archive settings. A static annotation count found 738 `@GameTest` occurrences and 328 `@Test` occurrences; these counts are inventory signals, not a claim that every test presently passes. | `build.gradle:263-355`; `qa`; `tools/hearthstead-qa` |
| Current animation API option | The locally resolved NeoForge 21.1.248 runtime includes `AnimationHolder` and `RegisterJsonAnimationTypesEvent`, so NeoForge's native JSON animation path is available without a third-party runtime. | Local Gradle cache `neoforge-21.1.248-universal.jar`; [NeoForge entity-renderer documentation](https://docs.neoforged.net/docs/1.21.8/entities/renderer/) |

### INFERENCE

Hearthstead is already far beyond the point where installing a large UI or AI
framework guarantees improvement. A framework migration would replace known
bugs with integration, licensing and regression risk. The active quality
problem is consistency and verified player-facing behavior across the breadth
already built.

### PROPOSAL

Treat external projects as design and workflow references. Add a new runtime
dependency only after a one-feature spike proves a measurable benefit over the
current implementation and passes dedicated-server plus compatibility tests.

## Source-backed reference matrix

### 1. MineColonies — settlement AI, requests, paths, guards and information architecture

**FACT**

- The current official 1.21.1 release line lists four required dependencies:
  Structurize, MultiPiston, BlockUI and Domum Ornamentum; JEI and JourneyMap are
  optional. [MineColonies 1.21.1 releases](https://github.com/ldtteam/minecolonies/releases)
- The version/1.21 properties declare Minecraft 1.21.1, Java 21, BlockUI,
  Structurize, MultiPiston, JEI/JourneyMap integration, data generation,
  Crowdin and SonarQube. [Official Gradle properties](https://raw.githubusercontent.com/ldtteam/minecolonies/version/1.21/gradle.properties)
- BlockUI describes itself as an XML-based UI system with a backing `Window`
  class for callbacks and data. [BlockUI repository](https://github.com/ldtteam/BlockUI)
- The public request-system documentation describes workers checking local
  inventory/storage, automatically opening requests, couriers delivering from
  a warehouse, visible unresolved requests and pickup priority.
  [MineColonies requests](https://minecolonies.com/wiki/systems/request/)
- The public UI text exposes bounded retry count/delay and an off-thread
  pathfinding node-limit tradeoff. It also provides status strings such as
  searching, chopping, planting, harvesting, patrolling, guarding and blocked
  conditions. [Official language source](https://github.com/ldtteam/minecolonies/blob/version/main/src/main/resources/assets/minecolonies/lang/manual_en_us.json)
- Guard UI exposes patrol, guard and follow tasks, manual patrol points,
  tight/loose follow modes, retreat behavior and hostile filters.
  [Barracks Tower documentation](https://minecolonies.com/wiki/buildings/barrackstower/)
- Raids stop civilian work, produce directional warning and progress feedback,
  and can scale to multiple directions/types as the colony develops.
  [Raid documentation](https://minecolonies.com/wiki/systems/raid/)
- MineColonies, BlockUI and Structurize repositories are GPL-3.0. Their source
  may be studied under that license, but no code or assets should be copied into
  this All-Rights-Reserved project.

**INFERENCE**

MineColonies' strongest reusable principle is not its number of systems. It is
that long-running work is externally legible: a worker has a named current
state, a request has a lifecycle, retries are bounded, and players have a
specific intervention when automation cannot finish. Its current public issues
also show that a large custom pathfinder does not eliminate building-layout and
door/ladder edge cases; architecture still needs scenario tests.

**PROPOSAL — original Hearthstead adaptation**

- Standardize every work goal on one compact server-authoritative contract:
  `ACQUIRE -> TRAVEL -> INTERACT -> COLLECT -> DEPOSIT -> COMPLETE`, with an
  explicit blocker code, retry budget, next-retry tick, owned target/reservation
  and safe recovery state.
- Keep Hearthstead's own simpler storage/request model, but expose one player
  sentence for every blocked state: what is missing, where it is expected, and
  the exact next action.
- Use a shared path-interaction layer for doors, containers and height-changing
  targets. Add fixture cases for closed doors, offset containers, one-block
  height changes, narrow rooms and re-path after obstruction.
- Do **not** add BlockUI/Structurize/MineColonies as dependencies for the demo.

### 2. Create + Ponder — visual explanation, animation readability and build workflow

**FACT**

- Create's 1.21.1 branch declares NeoForge 21.1.219 plus Registrate, Ponder,
  Flywheel/Vanillin and JEI. It runs data generation and a GameTest server.
  [Create properties](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/gradle.properties),
  [Create build](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/build.gradle)
- Ponder 1.21.1 is a separate interactive in-game documentation library. A
  scene combines a schematic with an ordered storyboard/instruction sequence.
  [Ponder README](https://github.com/Creators-of-Create/Ponder/blob/mc1.21.1/dev/README.md),
  [Ponder scene description](https://github.com/Creators-of-Create/Create/wiki/Internal---Ponder-UI)
- Ponder's 1.21.1 branch uses NeoForge 21.1.206 and Flywheel 1.0.4; Create's
  current branch uses Ponder 1.0.82 and Flywheel 1.0.6.
  [Ponder properties](https://github.com/Creators-of-Create/Ponder/blob/mc1.21.1/dev/gradle.properties)
- Ponder code is MIT. Create code is MIT, but Create's `assets` directory is
  All Rights Reserved. [Ponder license](https://raw.githubusercontent.com/Creators-of-Create/Ponder/mc1.21.1/dev/LICENSE),
  [Create license split](https://raw.githubusercontent.com/Creators-of-Create/Create/mc1.21.1/dev/LICENSE.md)

**INFERENCE**

Create's clarity comes from showing the mechanism in the world and sequencing
attention, not from filling screens with prose. That principle maps well to
Hearthstead's plaque, work-zone, emblem and build-plan learning. However,
shipping Ponder adds a nontrivial rendering/dependency chain and new scene
authoring work; it cannot fix today's worker/raid correctness.

**PROPOSAL — original Hearthstead adaptation**

- For the demo, preserve the physical handbook plus short contextual prompts.
- After the demo, spike one original visual tutorial: `Hearth -> plaque ->
  build plan -> emblem -> first worker`. Measure startup, client FPS, JAR size,
  dedicated-server behavior and modpack compatibility before adopting Ponder.
- Copy no Create scenes, assets, language, UI layout or animations.

### 3. GeckoLib + Blockbench — entity animation authoring and layered state

**FACT**

- GeckoLib 4 supports Minecraft 1.21-1.21.1 and NeoForge, is MIT-licensed, and
  uses Blockbench for models/animations. It provides easing, animation stacking,
  controllers and resource-pack-overridable animation data.
  [GeckoLib project/docs](https://github.com/bernie-g/geckolib/wiki/),
  [1.21.1 compatibility](https://modrinth.com/mod/geckolib/versions?g=1.21.1),
  [license](https://raw.githubusercontent.com/bernie-g/geckolib/main/LICENSE)
- One GeckoLib controller handles one concurrent animation; multiple controllers
  are required for concurrent layers, and competing controllers on the same bone
  require priority discipline. [Animation controller documentation](https://github.com/bernie-g/geckolib/wiki/Defining-Animations-in-Code-%28Geckolib4%29)
- NeoForge itself now supplies a JSON animation loader and recommends using it
  with Blockbench. The exact classes are present in Hearthstead's resolved
  NeoForge 21.1.248 runtime.

**INFERENCE**

GeckoLib is valuable when a project lacks an animation runtime. Hearthstead
already has 86 native definitions, client/server reachability tests, Blockbench
sources, deterministic asset checks and truth-render tooling. Migrating all
clips would be high risk and would not itself correct backward bending,
clipping, state timing or multiplayer duplication.

**PROPOSAL — original Hearthstead adaptation**

- Demo: keep the existing runtime and apply the animation-director gate to every
  reachable work/raid state. Reject a clip unless the physical phase, visible
  prop, authoritative gameplay event and sound timing agree in-game.
- Post-demo: convert **one new unique-raider clip** to NeoForge native JSON from
  Blockbench. Compare iteration time, reload behavior, file size, visual parity
  and multiplayer state sync. Prefer this zero-new-dependency route if it wins.
- Consider GeckoLib only if the native spike cannot provide required layered
  animation or event authoring. Never migrate solely because another mod uses it.

### 4. Epic Fight — combat telegraphing and animation/event synchronization

**FACT**

- Epic Fight supports a NeoForge 1.21.1 API and documents a Blender rig/exporter
  workflow for biped animation. [Epic Fight developer guide](https://github.com/Antikythera-Studios/epicfight.github.io/blob/main/docs/API/Starting.en.md)
- Its 1.21.1 changelog documents data-driven emotes, configurable swing/hit
  sound and particles, UI categorization, and a persistent mapped-buffer
  rendering optimization. [Epic Fight 1.21.1 changelog](https://github.com/Antikythera-Studios/epicfight/blob/1.21.1/CHANGELOG.md)
- Epic Fight code is GPL-3.0. Its images, audio, animations, models, textures,
  sounds and other assets are explicitly All Rights Reserved.
  [Code license](https://raw.githubusercontent.com/Antikythera-Studios/epicfight/1.21.1/LICENSE),
  [asset license](https://raw.githubusercontent.com/Antikythera-Studios/epicfight/1.21.1/LICENSE-ASSETS)
- A current dedicated-server issue demonstrates that combat-animation ownership
  can be applied to the wrong client if synchronization identity is wrong.
  [Multiplayer animation issue](https://github.com/Antikythera-Studios/epicfight/issues/2572)

**INFERENCE**

The useful principle is a combat action with explicit wind-up, commit, recovery,
sound and particle phases plus strict actor identity. Epic Fight is not an
appropriate Hearthstead dependency: it would change the whole combat layer and
introduce license, compatibility and multiplayer surface area.

**PROPOSAL — original Hearthstead adaptation**

- Model each raid attack as server-owned `WINDUP -> COMMIT -> RECOVER` with one
  action nonce/actor ID. The client renders that event but never owns damage.
- Give knight and archer different engagement slots and target preferences.
  Cap melee attackers per raider and assign archers to exposed/ranged threats,
  so guards cooperate instead of dogpiling.
- Every unique enemy needs a readable silhouette, threat sound, telegraphed
  attack and counterplay. No Epic Fight code/assets/animations are reused.

### 5. Patchouli + JEI — handbook, recipes and progression discoverability

**FACT**

- Patchouli supports NeoForge on 1.21.1 and supplies data-driven books,
  categories, entries, recipe pages, custom book items and advancement-gated
  content. Its docs describe a one-time first-join book pattern using an
  advancement reward. [Patchouli repository](https://github.com/VazkiiMods/Patchouli),
  [book JSON](https://vazkiimods.github.io/Patchouli/docs/reference/book-json/),
  [entry JSON](https://vazkiimods.github.io/Patchouli/docs/reference/entry-json/),
  [first-join books](https://vazkiimods.github.io/Patchouli/docs/patchouli-basics/giving-new/)
- Patchouli code/assets are CC-BY-NC-SA 3.0 and its maintainers specifically
  warn about Jar-in-Jar licensing/mapping concerns. Normal external dependency
  is their recommended route.
- JEI supports NeoForge 1.21.1, is MIT, and offers an API for recipe categories,
  recipes, catalysts and ingredient/search integration.
  [JEI repository](https://github.com/mezz/JustEnoughItems)

**INFERENCE**

Patchouli would accelerate document authoring, but its noncommercial/share-alike
terms need a deliberate product/licensing decision because Tobias may later
want a commercial path. Hearthstead's custom handbook also needs exact-once and
full-inventory behavior that a generic book framework does not prove. JEI is a
smaller, permissively licensed optional integration and solves a different
problem: recipe lookup for players who already use JEI.

**PROPOSAL — original Hearthstead adaptation**

- Keep the custom handbook as Hearthstead's canonical onboarding surface.
- Add a small optional JEI plugin after demo stabilization so researched build
  plans and emblems are discoverable in normal recipe browsing. Hearthstead
  must still be playable without JEI.
- Do not add Patchouli until Tobias explicitly chooses how its license fits the
  mod's future distribution. Do not Jar-in-Jar it.

### 6. spark + Lithium — measurement and compatibility, not product design

**FACT**

- `spark` is a client/server profiler with CPU sampling, memory inspection and
  server-health reporting. The main mod is GPL-3.0; its API module is MIT.
  [spark repository](https://github.com/lucko/spark)
- Lithium supports NeoForge and has a maintained 1.21.1 line. It is LGPL-3.0
  and targets behavior-preserving server/client optimization.
  [Lithium repository](https://github.com/CaffeineMC/lithium),
  [support policy](https://github.com/CaffeineMC/lithium/wiki/Support-Policy),
  [1.21.1 releases](https://github.com/CaffeineMC/lithium/releases)

**INFERENCE**

Neither tool should hide an expensive Hearthstead loop. `spark` can attribute
server/client hot paths that frame percentiles alone cannot. Lithium is useful
as a compatibility/performance test profile, but a demo that only performs well
with Lithium has failed its own performance requirement.

**PROPOSAL — original Hearthstead adaptation**

- Install `spark` only in a separate development/playtest profile. Capture the
  same UI-open, 20-worker logistics and first-raid scenarios with internal frame
  telemetry and a 30-60 second profile.
- Add a secondary compatibility run with Lithium 0.15.4 for MC 1.21.1, while
  preserving a no-Lithium performance gate as the release authority.
- Never bundle or silently require either tool in the Hearthstead JAR.

## Highest-impact Hearthstead improvements

### P0-1 — shared worker execution contract

**Player problem:** workers spin, idle, lose a deferred item, fail a door or say
an unhelpful generic blocker after one successful action.

**Original solution:** one task contract used by Lumberer, Farmer, Courier and
future jobs:

1. exact state and state-enter tick;
2. immutable target identity/reservation;
3. authoritative source/destination inventory ownership;
4. named blocker and player action;
5. bounded retry/backoff and escalation;
6. safe interrupt/reassign/restart recovery;
7. completion only after world/inventory state matches presentation.

**Acceptance:** two consecutive jobs each for Lumberer/Farmer/Courier; closed
door and one-block elevation; full/removed container; reload/reassignment; no
duplication/loss; no unbounded spin; blocker visible in settler/building/request
UI.

### P0-2 — server-authoritative first-raid squad director

**Player problem:** guards are individually functional but can dogpile, lose
the player, choose poor matchups or make the raid feel random rather than epic.

**Original solution:** a lightweight raid director assigns roles and engagement
slots while guards retain local movement/attacks:

- Knight: intercept/breach protection, one primary plus one support slot.
- Archer: maintain range, prioritize exposed/ranged/low-health threats, avoid
  firing through allies.
- Shared: protect/follow radius around player/Hearth, retarget lease, stuck
  recovery, low-health decision and no friendly owner targeting.
- Raid: warning direction, boss/progress state, civilians shelter, at least one
  memorable enemy with a distinct telegraphed move and counterplay.

**Acceptance:** minimum one knight + one archer; no all-on-one dogpile while a
second threat is active; both roles contribute; owner is never selected; raid
survives save/reload and multiplayer observer; victory state/XP/sound fire once.

### P0-3 — UI semantic, overlap and performance gate

**Player problem:** a screen can look new yet overlap, require scrolling,
contain dead controls or collapse FPS.

**Original solution:** keep `HsUi`, but require every surface to publish:

- semantic regions and a single primary purpose;
- computed responsive bounds, not fixed-right placement;
- one handler/tooltip/disabled reason for every interactive control;
- stable cached layout/content until data, language or dimensions change;
- clipped scroll regions only where content genuinely exceeds available space;
- exact `QaUiInspectable` state for automated native inspection.

**Acceptance:** Hearth, Settler, Plaque, Storage, Development, Research, Emblem
and Work Zone at 1280x720 and 1920x1080, GUI scales 2/3/4; no overlaps/clipping;
every button proves its result or disabled reason; keyboard/Escape works;
two warmed 360-frame windows per screen; no Hearthstead-attributable >100 ms
frame and p95 within the project's existing comparison ceiling.

### P0-4 — animation/action truth gate

**Player problem:** an offline-pretty clip can bend backward, move the wrong
prop, play without its job state or commit inventory at a different time.

**Original solution:** for every demo-reachable animation, approve the whole
state contract, not just the pose: trigger, facing, planted feet, prop binding,
wind-up, contact/transfer moment, recovery, exit, sound and authoritative state.

**Acceptance:** deterministic check + strict preview + multi-view truth render +
native in-game normal camera + multiplayer observer. Lumberer, Farmer, Courier,
Knight, Archer and unique first-raider states must all pass. A still image or
compile is never approval.

### P1-1 — optional recipe/tutorial integrations

After the playable demo is stable:

1. add optional JEI recipe integration;
2. spike one Ponder scene for the complete first-worker loop;
3. spike one NeoForge JSON animation for the unique raider;
4. adopt only the spikes with measured benefit and clean compatibility.

## Explicit rejections

- **Reject:** copying MineColonies request, pathfinding, UI or raid code/assets.
  **Reason:** clean-room boundary and GPL/asset ownership; Hearthstead needs an
  original implementation.
- **Reject for demo:** migrate all screens to BlockUI. **Reason:** GPL and broad
  regression scope; Hearthstead already has a custom performant design system.
- **Reject for demo:** migrate all entity animation to GeckoLib. **Reason:** 86
  existing clips and state-sync logic make the migration risk larger than the
  proven benefit.
- **Reject for now:** depend on Epic Fight. **Reason:** gameplay takeover,
  GPL/All-Rights-Reserved split, and large multiplayer compatibility surface.
- **Reject pending decision:** Patchouli Jar-in-Jar. **Reason:** explicit upstream
  license warning and a future commercialization question.
- **Reject:** claim performance because Lithium is installed. **Reason:** the
  base Hearthstead JAR must meet its own gate.

## License and integration register

| Project/tool | License/status | Permitted use in this audit | Hearthstead integration position |
|---|---|---|---|
| MineColonies | GPL-3.0 code; project assets not assumed reusable | Public behavior/source study and paraphrased principles | Reference only; no copied implementation/assets |
| BlockUI | GPL-3.0 | Public architecture study | No demo dependency; legal/product review before any future adoption |
| Structurize | GPL-3.0 | Public architecture study | No dependency proposed |
| Create | MIT code; `assets/` All Rights Reserved | Study public code architecture and gameplay principles | No assets/code copied; no dependency proposed |
| Ponder | MIT | Potential later external dependency/spike | P1 after demo, with Flywheel/compatibility measurement |
| GeckoLib | MIT | Potential runtime library; docs/architecture study | Evaluate only if NeoForge-native JSON spike is insufficient |
| Epic Fight | GPL-3.0 code; assets All Rights Reserved | Public behavior/docs study | Reference only; no dependency or asset reuse |
| Patchouli | CC-BY-NC-SA 3.0; upstream warns about Jar-in-Jar | Docs/behavior study | No integration without explicit licensing decision; never implicit bundle |
| JEI | MIT | Optional public API integration | Recommended P1 optional integration |
| spark | GPL-3.0 mod; API MIT | Separate development profiler | QA profile only; not bundled |
| Lithium | LGPL-3.0 | Separate compatibility/performance profile | Secondary test profile only; not required |
| NeoForge documentation/API | Public official project documentation and target runtime | Platform-authoritative technical guidance | Preferred zero-extra-dependency animation/UI/sound primitives |

## Recommended execution order

1. Complete and verify the active Lumberer/Courier/Farmer/door P0 journey using
   the shared worker contract.
2. Run the full UI overlap/function/frame gate and fix failures before adding UI
   polish.
3. Approve every demo-reachable animation through deterministic, visual and
   native in-game evidence.
4. Implement/verify the knight + archer raid director and unique first enemy.
5. Run the complete new-world-to-first-raid journey twice, then the official
   release gate and exact installed-JAR identity check.
6. Only after that: JEI, Ponder and native JSON-animation spikes.

## Verification performed for this research task

- Inspected the active module version, manifest, dependency block, UI kit,
  native frame observer, animation/Blockbench tooling, audio provenance and QA
  structure.
- Confirmed the exact NeoForge 21.1.248 cache contains the native JSON-animation
  classes named above.
- Consulted current official repositories, docs, release pages and license files
  linked in this report.
- Did **not** run Hearthstead's official QA suite and did **not** modify production
  code or assets.

## Remaining limitations

- This audit proves available tools and architectural fit; it does not prove a
  third-party dependency is compatible with Hearthstead's full modpack until a
  dedicated spike and client/server run exist.
- Public documentation describes intended behavior, not the absence of bugs.
- License notes are engineering risk flags, not legal advice.
