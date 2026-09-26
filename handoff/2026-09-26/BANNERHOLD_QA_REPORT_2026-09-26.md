# Bannerhold broad QA — working report

Status: IN PROGRESS. Owner requested testing through 17:17 Europe/Oslo on 26 September 2026 and delivery to Claude. This is a snapshot report, not a release approval.

## Scope and isolation

- Source: C:/Users/tobia/Hearthstead-Claude/Verk-arbeid-/hearthstead-neoforge.
- Frozen test copy: C:/Users/tobia/Hearthstead-Claude/build-codex/proj/hearthstead-neoforge.
- Build output: C:/Users/tobia/Hearthstead-Claude/build-codex/pbuild.
- Fresh GameTest world/config: C:/Users/tobia/Hearthstead-Claude/build-codex/gametest-20260926-full.
- No shared source edits, real saves, deployment, or shared GameTest world.
- Java 21, NeoForge 21.1.248, ModDevGradle 2.0.144, mod 0.2.0.
- The existing controller is inherited from a different serialized environment. Private-copy Gradle route follows current COORD. The owner explicitly requested broad tests; GameTests use an isolated new directory.

## Commands

Private cwd, read-only GIT_DIR pointing at shared repository metadata; GIT_WORK_TREE points at private project parent; GIT_OPTIONAL_LOCKS=0.

1. `./gradlew.bat build --offline --max-workers=2 -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/pbuild`
2. `./gradlew.bat runGameTestServer --offline --max-workers=2 -I C:/Users/tobia/Hearthstead-Claude/build-codex/isolated-gametest.init.gradle -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/pbuild`
3. `python tools/validate_assets.py --quiet`
4. `python anim_check.py` from private module tools.
5. `python C:/Users/tobia/Hearthstead-Claude/build-codex/audio-audit.py`

## Results so far

| Layer | Result | Interpretation |
|---|---|---|
| Compile and JAR assembly | PASS | Full build overall FAIL due to tests below |
| JUnit | 1144 executed; 1137 pass; 7 fail; 0 errors; 0 skipped | Exact XML saved |
| GameTests | RUNNING | Do not infer final counts yet |
| Animation structural validator | PASS; 97 definitions, 733 channels; 3 warnings | Does not prove native animation quality; 39 catalogued clips missing/phased |
| Asset validator with documentation copied | FAIL; 3723/4138 pass; 415 errors; 5 warnings | Substantial stale-validator assumptions; not 415 confirmed gameplay bugs |
| Audio decode/reference audit | PASS; 584 OGG files, 275 sound events | No missing files, decode errors, silent files or nonfinite samples |
| Dialog duration check | PASS | 34 recordings; none differ by over 200 ms from manifest |
| Native visual/listening/co-op/performance | NOT VERIFIED | Automated output is insufficient |

## JUnit failures — exact messages

### com.hearthstead.block.BannerholdIdentityTest / englishTextNeverShowsTheRetiredNames()

org.opentest4j.AssertionFailedError: player-facing text still says Hearth/Hearthstead: [hearthstead.guide.ref.tech_branches.courier_ledger.text = Couriers serve Urgent, then High, then Normal requests, whatever the kind: Hearth food goes before workshop inputs., hearthstead.guide.ref.tech_branches.warm_hearth.name = Hearth Fires, hearthstead.guide.options.switches1.b1 = Server owners can turn whole systems off in hearthstead-server.toml, [features].] ==> expected: <true> but was: <false>

### com.hearthstead.client.motion.AuthoredClipAssetsTest / everyAuthoredClipParsesAndKeepsItsLegacyContract()

org.opentest4j.AssertionFailedError: raider/raider_heavy length is the contact contract ==> expected: <2.2> but was: <1.8>

### com.hearthstead.client.screen.HandbookChapterLanguageTest / everyChapterPageHasTextAndEveryWrittenPageIsInTheBook()

org.opentest4j.AssertionFailedError: hearthstead.guide.taverns_ale.title is written but no Handbook chapter shows it ==> expected: <true> but was: <false>

### com.hearthstead.client.ui2.handbook.HandbookChaptersSchemaTest / everyPageIsWellFormedAndEveryReferenceResolves()

org.opentest4j.AssertionFailedError: finisher_revive.finish: bullet: hearthstead.guide.finisher_revive.finish.b1 is 180 chars (max 140)
finisher_revive.finish: bullet: hearthstead.guide.finisher_revive.finish.b2 is 143 chars (max 140) ==> expected: <true> but was: <false>

### com.hearthstead.client.ui2.handbook.HandbookChaptersSchemaTest / theIndexParsesWithoutProblemsAndReachesEveryChapterFile()

org.opentest4j.AssertionFailedError: taverns_ale.json exists but chapters.json never shows it ==> expected: <true> but was: <false>

### com.hearthstead.client.ui2.handbook.ReferenceGuardTest / everyFeatureSwitchAndConfigSectionIsOnTheOptionsPage()

org.opentest4j.AssertionFailedError: Config sections / feature switches without a handbook entry (add a row to tools/handbook/facts and run tools/handbook/check.sh): [option:hud] ==> expected: <true> but was: <false>

### com.hearthstead.settlement.journey.NewPlayerGuidanceContractTest / handbookTechTreePageListsEveryTrunkPrice()

java.lang.AssertionError: Tech Tree page has no line for Lumber Camp: Open the Banner and choose Tech Tree. The Banner sits in the middle; ranks are rings (Hamlet inside, then Village, Town, Castle) and each branch owns a wedge: Watch, Logistics, Commons and Craft. When the tree opens, the node your Journey asks for next is selected and wears a gold Journey ribbon: for a new village that is Timber Rights (Lumber Camp). Click a node to see its cost, milestones and what it unlocks. Research asks twice: the first click shows "Confirm" with the full price, the second click pays (hold Shift to skip the confirm). A red line warns you when a purchase would spend the Coin your Journey step needs. Coins and goods are paid once, from your pack, the Banner and the Warehouse.

## Initial triage (requires follow-up)

- Confirmed integration gap: taverns_ale is absent from chapters.json. This causes two tests; lead owns index, already notified.
- Confirmed UI data constraint: finisher_revive bullets 180 and 143 characters exceed the schema limit of 140.
- Confirmed documentation gap: hud config section missing from generated options reference.
- Animation contact-contract mismatch: authored raider_heavy is 1.8 s vs legacy 2.2 s. Determine authoritative combat timing before changing either.
- Identity test mixes real branding drift (Hearth Fires vs Warm Homes) with literal config filename hearthstead-server.toml, which remains technically correct. Do not rename the config merely to satisfy a broad name regex.
- Tech-tree guidance test requires an older fixed price list in prose. Current prose describes the interactive tree; establish intended data-driven price reference before treating this as wrong in-game prices.
- Asset registration validator misses dynamically registered WeaponItems (tier/type loop) and client-only HsSound ids intentionally resolved through sounds.json. Do not add duplicate registrations to silence false positives.
- Texture dimensions validator assumes every entity texture is 128x64; new look layers include 64x64. Compare actual model UV contracts before labelling assets corrupt.
- Generator parity differences are real differences, not proof which art version is desired. Never overwrite new authored textures with old generators blindly.
- Empty click handlers occur on already-selected Builder preset/Captain loadout buttons. Selection works on other options; this is a minor inactive-state/accessibility issue, not evidence that all buttons are broken.
- Stereo sound files are exclusively music (11 event/file references); no positional stereo defect found by this check.

## Evidence files

- full-build.log; full-junit-summary.json; pbuild/test-results/test/*.xml
- full-gametest.log; gametest-20260926-full/logs
- full-assets-with-docs.log; full-animation-with-docs.log
- audio-audit.py; audio-audit.json
- full-test-source-manifest.json (per-file SHA-256)

Manifest SHA-256: `495c45a0da63491b742f06cb41bca60d80272e99d753779926005276357e4d10`.

## Confirmed GameTest fixture defects (source-backed)

### QA-TEST-01 — Crafting gate test uses a wooden shovel recipe, not a spear
- Evidence: `TechCraftGateGameTests.java:67-69` fills table slots 1/4/7 with plank/stick/stick (vertical). `data/hearthstead/recipe/wooden_spear.json:4` requires a diagonal (top right / centre / bottom left), so correct slots are 3/5/7.
- Trigger/result: `craftingTableIsGatedAndCoopPlayerTwoShares` fails its first assertion that result must be empty. The vertical pattern matches vanilla wooden shovel; it cannot test the spear gate.
- Classification: confirmed test defect, not proof the spear gate is bypassed. Fix fixture slots and assert result recipe identity; rerun both locked/unlocked phases. Do not weaken the production gate.

### QA-TEST-02 — Finisher disabled test demands an opening at full health
- Evidence: `ScenarioFinisherGameTests.java:78-79` sets max/current health to 60; `:104` expects forceOpenWindow to return true. `FinisherService.java:1192-1210` explicitly requires health below 10% AND off balance.
- Trigger/result: `switchedOffRefusesTheRequest` fails fixture setup before exercising the disabled switch.
- Classification: confirmed test fixture out of date. Set eligible low health before opening; preserve separate full-health rejection coverage. Not proof disabled finishers execute.

### QA-UI-01 — Already-selected options remain clickable with empty action
- Locations: `client/builder/BuilderPlanScreen.java:267-268`, `client/captain/CaptainScreen.java:72-73`.
- Trigger: click selected preset/loadout. Button is visually active but action is empty. Other options have real handlers.
- Severity: minor UX/accessibility; prefer disabled/selected semantics. Not broken selection mechanics.

## Coverage expansion requested by owner

All screens/actions, priorities/orderings, variants and co-op are explicit scope. Inventory artifacts:
- UI_CONTROL_MATRIX.csv: 24 *Screen source files, 246 candidate control construction sites. This is not an exact count of unique controls and excludes overlays until separately inspected. Native interaction columns start NOT VERIFIED.
- JUNIT_CASE_MATRIX.csv: all 1144 executed cases with per-case XML evidence.
- GAMETEST_STATIC_INVENTORY.csv: 1473 directly discoverable annotated methods; generated scenarios add further cases.
- CO_OP_SCENARIO_MATRIX.csv, PRIORITY_ORDER_SCENARIO_MATRIX.csv, PERSISTENCE_SCENARIO_MATRIX.csv: name-based discovery only until assertions and execution results are mapped. Do not report these as independent passed suites.

Version scope is the repository's supported MC1.21.1/NeoForge21.1.248/Java21. No claim of cross-version compatibility. Two server-side test players do not prove two-client synchronization, network-latency resilience or actual screen usability.

## Focused interaction/ordering review — source evidence only

- Barter acceptance: `BarterScreen.java:343-350` suppresses repeated submit for the same revision. `ConversationService.java:351-375` validates and consumes the revision BEFORE applying the deal. Refused normal attempts send the incremented revision, so the button can recover after refusal. This source check does not establish two-client synchronization or packet-loss behavior.
- Captain actions: `CaptainNetwork.java:30-40` schedules actions on server work queue; `CaptainService.java:41-48` rejects nonliving/wrong entity, >8-block reach, and spectators. Hero checks occur in rename/cosmetics/loadout. Shared-settlement permission policy needs confirmation before calling proximity-only editing an unauthorized-access defect. Successful changes broadcast to tracking players; initial state is sent on start tracking.
- Native UI remains NOT VERIFIED: 24 screen classes and overlays need real input/render checks. Structural construction-site inventory must not be described as clicking all buttons.
- Coverage discovery found 43 co-op/replay/access candidate methods, 55 priority/order/interrupt candidates and 148 save/load/restart candidates. These sets overlap; counts are not extra test totals.

## Blueprint batch 0 — interim runtime result

50 generated construction scenarios began 13:44:23; 48 failures logged at batch completion around13:53:16. Most report ACTIVE/FETCHING with partial completion. Full suite is still running; do not extrapolate remaining batches.

Potential common cause requiring focused reproduction: `BuilderWorkGoal.nextStep` scans up to512 pending same-layer steps and locks the chosen step; `windowNeeds` collects only the next64 item units from job.cursor. If the locked current step is outside that prefix and needs a different material, `tickLoad` detects that material missing but `takeBatch` only loads windowNeeds. The selected step is not explicitly seeded into the batch. This can create repeated loading without obtaining the material required by the locked step. Source references: BuilderWorkGoal.java:338-342, 373-411, 796, 877, 939-960, 1009-1015. This is a hypothesis until an isolated test drives the real methods; the broad FETCHING failures alone do not establish this cause.

Request reservation invariant inspected: RequestLedgerGameTests.java:258-286 creates two couriers requesting the same request on the authoritative thread; first accepted, second refused RESERVED_BY_OTHER, winner UUID retained. This covers serialization of competing claims, not actual client latency.

## QA-BUILD-01 — P1: chosen build step can be excluded from its material load (CONFIRMED)

Independent isolated GameTest reproduction completed at14:01. Two cases: selected step within64-item prefix PASS; selected step beyond prefix FAIL. Exact failure: `Selected step 64 needs minecraft:oak_planks but batch={minecraft:cobblestone=64} capacity=64`.

Production source is byte-for-byte baseline; private harness calls actual `nextStep` and `batchNeeds` via reflection. Job has64 distant cobblestone steps followed by a nearby oak-plank step in the same STRUCTURE phase/layer. Empty-bag Builder chooses nearby plank step, but loading only requests first64 cobblestone. `nextStep` retains chosen pending step, while subsequent `tickLoad` uses the same prefix, so required plank cannot enter the batch through this path.

Suggested correction: seed loading/deposit-retention window with CURRENT selected step costs (and necessary scaffold), then fill remaining capacity from ordered upcoming steps; or constrain selection to the loadable window. Preserve phase/layer ordering and count conservation. Add regression for63/64 boundary, mixed materials, full bag, scaffold reserve, different sack capacities, selected-step changes and two builders. Do not simply increase64: FRONT_SCAN remains512 and larger jobs still reproduce.

Evidence: diagnostic-builder-v2.log, diagnostic-proj/.../CodexBuilderBatchGameTests.java, diagnostic.init.gradle. Run command equals baseline GameTest command with diagnostic private cwd/build/init. Namespace codex_diagnostics isolates the two probe cases; fresh diagnostic-world-v2. Result2run/1pass/1fail. This proves the loading invariant defect; it does not yet prove it explains every broad blueprint timeout.

Harness audit: independent read-only reviewer found initial zero phase bytes meant CLEAR, making material assertions vacuous; corrected to STRUCTURE plus nonempty-cost assertion. Initial run also failed due to namespace-qualified template concatenation before executing tests; fixed private namespace resource copy. Reviewer rechecked corrections and found no remaining concrete defect. Initial run is invalid evidence; only v2 is used.


## Fresh current-source JUnit verification — 2026-09-26T14:13:56.364463

Private source snapshot `current-verification`, 4126 source files, manifest SHA-256 `5b91dbe0bd7c1f622a7bd008b99b753cf0497d429a888c829e15b993b1fbbbc3`. Command: `./gradlew.bat test --offline --max-workers=2 -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/current-verification-build`. Compile PASS; JUnit 1150 run, 1144 pass, 6 fail, 0 errors/skips. Current heavy animation duration test now PASSES: do not report the obsolete 1.8/2.2 mismatch as an unresolved current defect. Remaining failures:
- com.hearthstead.block.BannerholdIdentityTest.englishTextNeverShowsTheRetiredNames()
- com.hearthstead.client.screen.HandbookChapterLanguageTest.everyChapterPageHasTextAndEveryWrittenPageIsInTheBook()
- com.hearthstead.client.ui2.handbook.HandbookChaptersSchemaTest.everyPageIsWellFormedAndEveryReferenceResolves()
- com.hearthstead.client.ui2.handbook.HandbookChaptersSchemaTest.theIndexParsesWithoutProblemsAndReachesEveryChapterFile()
- com.hearthstead.client.ui2.handbook.ReferenceGuardTest.everyFeatureSwitchAndConfigSectionIsOnTheOptionsPage()
- com.hearthstead.settlement.journey.NewPlayerGuidanceContractTest.handbookTechTreePageListsEveryTrunkPrice()

Evidence: current-verification-junit.log, current-verification-junit-summary.json, current-verification-build/test-results/test. Historical handbook lane claim of 33 green guards does not prove current integrated data green. Broader baseline GameTests continue independently on the original snapshot.


## Full baseline GameTest suite — terminal result 14:19:34 Oslo

2091 GameTests completed in 36.76 minutes of reported game-suite time; 208 required failures, hence 1883 non-failing cases. The process shut down normally after reporting failures; Gradle task exit was failure (game JVM exit208). No tests were deliberately excluded in this baseline run.

192 of the 194 generated blueprint construction cases failed. These are correlated outcomes, not 192 distinct root causes. Sixteen other tests failed; exact names/reasons are in `gametest-failures-final.json`. Two finisher failures stop at the same full-health fixture setup; crafting gate failure uses the wrong recipe fixture. The remaining outcomes need current-snapshot rechecks and focused diagnosis before assigning production severity.

A second run on the current 4126-file snapshot has now started in a new private world. It uses the existing `hearthstead.gametest.skipBlueprintBuilds=true` switch to avoid repeating the 194 long construction cases before their confirmed batching defect is addressed. This second run is explicitly NOT a replacement for the complete baseline run. It preserves the other builder, yard, fishery and mechanism tests selected by the project's registry.


## QA-TEST-03 — P2 test isolation: drunkenness tests clear another test's enable switch

Confirmed by paired runs against current source. `DrunkennessGameTests.aleLevelsSlowCiviliansButNeverTheWatch` fails with the four-test tavern_drunk batch (4 run /3 pass /1 fail), but PASSES alone (1 run /1 pass). Production code is unchanged; the only source difference in the private single-case copy is that test's batch label, `tavern_drunk` to `codex_drunk_single`.

Cause: all four tests share a JVM-global `Drunkenness.testOverride`. `drunkennessSurvivesAReload` sets it true, executes immediately, then clears it to null. Other tests still have delayed assertions at ticks21/25/26/62. `Drunkenness.enabledIn` explicitly disables drunkenness on GameTestServer when the override is null, and SettlerEntity.tickDrunk clears points/level when disabled. Depending on start ordering, the levels test fails at one ale or three ales. The isolated case passes with its original assertions intact.

Locations: `gametest/DrunkennessGameTests.java` batch declarations and testOverride assignments; `entity/Drunkenness.java:230-233`; `entity/SettlerEntity.java:263-269`. Fix the fixture's switch lifetime: enable at batch start and reset after the entire batch, or give independently toggling cases separate batches. Do not weaken production drunkenness rules or remove assertions to make the suite green.

Commands: `./gradlew.bat runGameTestServer --offline --max-workers=2 -I <private>/drunk-isolation.init.gradle -PhearthsteadBuildDir=<private>/current-verification-build` in current-verification/hearthstead-neoforge; then the same command with drunk-single.init.gradle and drunk-single-build in drunk-single/hearthstead-neoforge. Both init scripts set private fresh game directories and the existing batchPrefix filter. Evidence: drunk-isolation.log (4 cases,1 failure), drunk-single.log (1 case,BUILD SUCCESSFUL). This identifies test interference; it does not validate every native drunkenness animation or sound.
