# Bannerhold broad QA — working report
## Current reading guide — 16:32 Oslo (interim)

This guide supersedes older statuses in the chronological evidence below. No release approval or shared production integration. Large navigation/building/design changes remain main-Claude scope.

| Area | Latest independent evidence | Limitation |
|---|---|---|
| Candidate e4b79b1 broad GameTests | 1907 completed, 9 required failures, 1898 non-failing; fresh world, 3.257 minutes | Excludes 194 generated construction scenarios; different snapshots must not be subtracted as improvement |
| Candidate ced4d77 | Compiled PASS; JUnit 1161 tests, 1159 pass, 2 fail | Naming guard and fixed-price prose contract remain; Captain21 + drunkenness4 = 25/25 required GameTests PASS |
| Earlier focused acceptance | 41eeffc 36/36; 4e7e075 departure 10/10 | Narrow acceptance, not all mechanics |
| Generated construction | 62ca318 194 completed, 192 required failures, 2 non-failing, 37.78 min | Not 192 independent root causes; general construction remains unreliable |
| Native fixes | Empty-config startup da67b84 PASS; research confirmation e4b79b1 PASS for label/tooltip/timeout/exact payment | Isolated candidate, not installed on owner's server |
| Native co-op | Two non-op clients: research race charges once; non-founder purchase shared; graceful restart preserves knowledge/inventories; simultaneous shared-storage withdrawal conserves items | Creative/Peaceful disposable server; combat/crash recovery/full survival not covered |
| QA-UI-03 P2 | Fixed in1f01db1;112/112 related GameTests and actual immediate-open native repro PASS | No shared deployment; multi-viewer delayed refresh not native-tested |
| Confirmed open | Far-shore Fisher NO_PATH; balcony escape; general construction; co-op affordability stale UI05; toast truncation UI04; broad failure signals below | Some suite signals unstable or fixture-related, not all confirmed product defects |
| Small new patches | ced4d77 charge-reset, voice cleanup, drunk-test isolation independently read-only reviewed | No native audio listening; focused 25/25 GameTests PASS |
| Assets | 584 OGG / 275 events decoded/referenced; 97 animation definitions / 733 channels structural check | Null audio backend; no blanket animation appearance approval |

Current baseline manifest SHA256 5b91dbe0bd7c1f622a7bd008b99b753cf0497d429a888c829e15b993b1fbbbc3. Exact patch deltas and logs are retained separately. Cloud Claude explicitly acknowledged the full routing ledger in the PR3 routing reply of14:02:18UTC. Source review findings and native failures remain distinct. Final delivery due17:17.
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

## Historical initial snapshot results (superseded by current guide)

| Layer | Result | Interpretation |
|---|---|---|
| Compile and JAR assembly | PASS | Full build overall FAIL due to tests below |
| JUnit | 1144 executed; 1137 pass; 7 fail; 0 errors; 0 skipped | Exact XML saved |
| GameTests | Was running at initial report capture; later terminal2091/208failure | See dated current results; no GameTest is running as of16:32 |
| Animation structural validator | PASS; 97 definitions, 733 channels; 3 warnings | Does not prove native animation quality; 39 catalogued clips missing/phased |
| Asset validator with documentation copied | FAIL; 3723/4138 pass; 415 errors; 5 warnings | Substantial stale-validator assumptions; not 415 confirmed gameplay bugs |
| Audio decode/reference audit | PASS; 584 OGG files, 275 sound events | No missing files, decode errors, silent files or nonfinite samples |
| Dialog duration check | PASS | 34 recordings; none differ by over 200 ms from manifest |
| Native visual/listening/co-op/performance | Not yet verified at initial capture | Later native checks documented below; audio listening still not verified |

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

### QA-UI-02 — Already-selected options remain clickable with empty action
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


## Historical in-game still review (Codex visual inspection)

Inspected original 1920x1080 GUI-scale4 captures from Claude's survival session: hb-ingame-3-items-banner-recipe-gs4.png, hb-ingame-4-items-work-scepter-grid-gs4.png and s2-046-change-mayor.png. The first two show rendered recipe grids, readable item names and keyboard/mouse action chips, and scrollable page content. They are genuine historical render evidence, not evidence that every latest-state recipe grid or interaction passed.

Visible issues in those captures: long handbook search placeholder reaches the sidebar edge; Banner recipe happens to show crimson logs/blackstone (cycling tag ingredients may mislead a beginner); Mayor header and mourning text are truncated. Claude's later transcript says the placeholder, resting tag choice and Mayor header were changed. Therefore these are historical findings awaiting a fresh screenshot, NOT newly confirmed current defects. Preserve the later fixes and recheck at GUI scales2/3/4 instead of reimplementing them blindly. No new controls were clicked in this review.


## Current-source GameTest rerun — completed 14:38:21 Oslo

1902 cases completed in 2.323 minutes;17 required failures,1885 non-failing cases. This snapshot registers2096 total cases; only194 generated blueprint construction cases were intentionally excluded by the existing skipBlueprintBuilds flag. This is NOT the same denominator as the earlier2091-case snapshot. Both source manifests are retained.

Command: `./gradlew.bat runGameTestServer --offline --max-workers=2 -I C:/Users/tobia/Hearthstead-Claude/build-codex/current-gametest-v2.init.gradle -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/current-verification-build`. Private cwd current-verification/hearthstead-neoforge, fresh current-gametest-world-v2. Evidence current-gametest-v2.log and current-gametest-failures.json. The earlier current-source attempt ended without a suite summary and is not counted.

Notable unresolved runtime outcomes: civilian raid scenario lost4 of8 against its <=1 target; far-shore Fisher had NO_PATH; balcony descent stayed stuck; cottage skipped its final door; fishery construction remained FETCHING at54/562 before water/plaque; miner tech race showed1050 vs1047 ticks, failing the promised speed ordering; departing actors failed to leave; first-raid/merchant/duel scenarios have failing assertions. These are reproducible test outcomes on this run, not yet all classified root causes. The spade/spear and full-health finisher fixtures and shared drunkenness override are separately identified test defects.


## Focused civilian-safety / Craft rerun

`mechanics-focused.init.gradle` selects existing batch prefixes `raid_civilians_,techtree_craft`, fresh mechanics-focused-world, unchanged current-verification production source. Command: `./gradlew.bat runGameTestServer --offline --max-workers=2 -I C:/Users/tobia/Hearthstead-Claude/build-codex/mechanics-focused.init.gradle -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/current-verification-build`.

19 tests completed,17 pass,2 fail. Failures: known wrong spear fixture and Miner tech-speed comparison (1047 vs1046 ticks for8 blocks). The Miner comparison now fails in baseline, current broad and focused runs; root cause remains under investigation. Civilian-safety cases PASS in this focused run although the broad run lost4/8 civilians. Preserve both results: this is a stability/ordering/randomness concern, not proof the broad failure is fixed. Evidence mechanics-focused.log, terminal14:40:31.

Owner design direction (not implemented): replace Mayor assignment with a separate role-selling NPC sitting at the Banner, avoiding consumption of a working settler. Existing bonuses/migration remain undecided. Shared with cloud Claude in PR3; kept outside Builder fix scope.

## QA-TEST-04 (P2 test reliability): Miner comparison accidentally grants both settlements the bonus

Confirmed 26 September 14:49 Oslo. Original broad and focused runs failed the miner speed comparison. Diagnostic miner-probe.log shows fastNode=true AND slowNode=true, both diamond picks and toolTicks=48. TechTreeCraftGameTests.mineArena registers a MINE before Development.of first initializes the settlement. Development.java:166-179 invokes grandfatherExistingBuildings; lines739-774 unlock the matching node and prerequisites. DevelopmentNode.java:161-164 associates MINE with CRAFT_AND_INDUSTRY. Thus the intended untreated control already has the treatment.

Private diagnostic fix only: in mineArena, call Development.of(helper.getLevel(), s) before GameTestFixtures.registerWithBounds(...MINE...). No production source changed. Same techtree_craft batch, fresh miner-corrected-world; miner-corrected.log shows fastNode=true, slowNode=false, tool durations48 versus60, and the miner test passes. One remaining required batch failure is the separately documented wrong spear recipe fixture. Command: ./gradlew.bat runGameTestServer --offline --max-workers=2 -I C:/Users/tobia/Hearthstead-Claude/build-codex/miner-corrected.init.gradle -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/drunk-single-build ; cwd build-codex/drunk-single/hearthstead-neoforge. Exit1 is expected from that unrelated remaining failure, not an all-green suite claim.

Recommendation: initialize new settlement state before fixture building registration, and assert the control lacks the tech before starting the timed race. Preserve legacy-save grandfathering. This experiment supports a fixture defect, not a gameplay speed regression or cross-settlement state leak. Evidence: miner-probe.log, miner-corrected.log, private modified TechTreeCraftGameTests.java.

## Approved Guildmaster concept

Owner approved visual NPC/UI concepts and selected Laugsmester / Guildmaster; screen title Yrker og emblemer / Professions & Emblems. Private concept package: design/guildmaster/DESIGN_BRIEF.md, guildmaster-reference-v1.png, professions-emblems-ui-v1.png. Generated labels saying merchant are superseded. No production implementation. Name decision sent in PR3 comment5846407524.

## Focused departure rerun - 14:51:52 Oslo

Selected existing batch prefixes event_departure_brute_toll,event_departure_field_fox,event_departure_wild_boar with unchanged current-verification production/test source and fresh departure-focused-world. Three cases; fox and boar pass, paid-brute departure still fails (raider at BlockPos{x=84157,y=-50,z=-2444290}, test origin84093,-60,-2444322). Command ./gradlew.bat runGameTestServer --offline --max-workers=2 -I C:/Users/tobia/Hearthstead-Claude/build-codex/departure-focused.init.gradle -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/current-verification-build. Evidence departure-focused.log; exit1.

Keep broad fox/boar failures as unstable outcomes, not fixed or independently confirmed production faults. watchDeparture follows the party for100 ticks, then moves its observer90 blocks away and forces nearby chunks. Tests require removal within900 ticks and at least19 blocks horizontal travel. DepartureRules has a2400-tick stuck fallback, so that fallback cannot rescue these tests. This mismatch alone does not explain the failure: the intended normal-path departure should still complete. Brute test needs path/progress and observer diagnostics before assigning root cause. No source change made in this run.

## Paid-brute departure probe - 14:53:07 Oslo

Private test-only logging in WorldEventGameTests.watchDeparture; production unchanged. Single event_departure_brute_toll case in fresh departure-probe-world, FAILED at900 ticks (departure-probe.log). All logged observers at t700-900 were108-201 blocks away, beyond DepartureRules.FAR=48. Therefore those observed stalls were NOT waiting for a nearby viewer. Leader remained at y=-50 while target columns repeatedly resolved to y=-60; navDone oscillated true/false. Horizontal displacement at t700 about19.96 blocks for leader and13.6/13.98 for followers; by t900 about15.79/17.95/16.26. The20-block departure threshold was not satisfied at these sampled instants. This supports terrain/path progress failure in this fixture, not a visibility-policy failure. The2400-tick fallback was not reached.

Relevant source: Departure.walk -> moveOnGround uses MOTION_BLOCKING_NO_LEAVES height for target column; a10-block height discontinuity appears in this arena. Further reproduction on navigable flat terrain is needed before attributing this specific test failure to ordinary gameplay. Do not solve by removing the unseen condition or weakening the minimum walk assertion. Command uses departure-probe.init.gradle, private drunk-single module and drunk-single-build. Process terminal exit1; no active test process remains from this probe.

### Terrain-extension experiment - 14:54:36

Extended only this test's floor at relative y0 from the64x64 arena to[-64,128) in x/z, clearing four blocks above, in private test fixture. Single brute test still FAILS at900 ticks, actors at y=-50; evidence departure-flat.log. This does not prove a flat arena at the actors' elevation: their actual support blocks/heights need inspection. Therefore the proposed arena-edge explanation remains unconfirmed. No production changes; do not count the experiment as a successful correction or ordinary-terrain validation.

## QA-TEST-05 (P2 fixture reliability): paid-brute departure spawns/walks on GameTest barriers

The ground probe identifies support=minecraft:barrier at y=-51 (actor feet y=-50), while relative zero is y=-60. Thus the earlier10-block discrepancy is the GameTest barrier enclosure, not just an unprepared outside floor. Extending the floor alone did not resolve it.

Controlled follow-up kept the same extended floor, same production code,900-tick timeout, nearby-observer first100 ticks and minimum walk assertions. Removed ONLY Blocks.BARRIER at relative x/z[-2,65],y[1,12] before event start in this private single-case arena. Result: All1 required tests passed; BUILD SUCCESSFUL, exit0 at14:57:04. Logged actors now stand on minecraft:stone at y=-59. Evidence departure-ground.log (barriers, fail) and departure-no-barrier.log (stone, pass). Init departure-no-barrier.init.gradle; fresh departure-no-barrier-world, cwd drunk-single/hearthstead-neoforge, build drunk-single-build; otherwise same offline runGameTestServer command.

Conclusion: confirmed fixture sensitivity to the artificial barrier enclosure. This passing comparison does not prove all departure terrain or multiplayer cases. Recommendation: use a supported open departure fixture or explicitly prepare a reachable departure corridor and assert spawn support is not BARRIER; preserve the unseen and travel checks. Do not ship blanket barrier deletion in gameplay code. Recheck fox/boar broad failures against their actual support before classifying them similarly.

## Fisher far-shore isolated reproduction - 14:58:14 Oslo

Existing fisher_far_shore batch, unchanged current-verification snapshot, fresh fisher-focused-world:1 test,1 required failure, exit1. At2400 ticks: act=TRAVELING, reported relative position(-55,1,-41), route=fisher_chair_unreachable@2449, stop=NO_PATH. Evidence fisher-focused.log. Command ./gradlew.bat runGameTestServer --offline --max-workers=2 -I C:/Users/tobia/Hearthstead-Claude/build-codex/fisher-focused.init.gradle -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/current-verification-build.

This reproduces the broad failure without other GameTest batches. FisherFarShoreGameTests.java:38-85 checks only reaching WORK_FISH, not completed fish production; it validates shore before spawning, supplies rod and starts at(56,1,56). FisherWorkGoal.java:113-155 has explicit far-range partial-path fallback then switches to exact reachable aisle. Root cause remains unclassified; inspect actual world coordinates/chair facing and partial-path progress before changing arrival checks. Reported relative negative coordinates should not be interpreted as a verified position outside the arena until helper rotation/coordinate conversion is checked. No code changes in this reproduction.

## QA-FISH-01 (P2): far-shore approach fails while near control works

Private test-only position/path logging, production unchanged: fisher-probe.log reproduces failure on ordinary stone_bricks support at the same elevation as the chair. At t400 the chair is66.5 blocks away, navigation target is the shore aisle but pathEnd=null and done=true. Later samples repeatedly show null routes at52-57 blocks. This is distinct from the departure barrier defect.

Control changed ONLY starting position in the instrumented fixture from(56,1,56) to(24,1,24), about20.6 blocks from chair(8,1,11). All1 required tests PASS, BUILD SUCCESSFUL at14:59:58; evidence fisher-near-control.log, fresh fisher-near-control-world. Command uses fisher-near-control.init.gradle with private drunk-single module/build. Near control is diagnostic evidence, NOT a replacement for the required far-shore regression.

Relevant FisherWorkGoal.java:113-155: attempts exact path, then for distances>24 chooses a standable aisle, and asks navigation for a path all the way to that distant aisle. When createPath returns null, coordinate moveTo retries that same distant target rather than choosing a reachable intermediate destination. Observations support inadequate far-distance approach, but exact internal navigation cause is not yet independently established. Recommend investigate bounded reachable intermediate waypoints, retaining final exact-contact checks; verify far, near, blocked shore, change of job and unloading before integration. No production fix applied by Codex.

## QA-PATH-01 (P2): Elmfield balcony escape fails in isolation

Unchanged current-verification production and test source; elmfield_balcony batch, fresh balcony-focused-world:1 required test,1 failure at1600 ticks. Evidence balcony-focused.log, terminal15:01:06, exit1. Actor remains at balcony elevation y6, four blocks above ground; route note nav:stuck. Command ./gradlew.bat runGameTestServer --offline --max-workers=2 -I C:/Users/tobia/Hearthstead-Claude/build-codex/balcony-focused.init.gradle -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/current-verification-build.

ElmfieldBalconyGameTests.java builds the historical corner geometry: top slab, fence posts, log beam, wall ladder and exhausted settler at an intentionally precise offset inside the corner cell. This independently reproduces broad failure without other batches. Root cause not yet classified (collision recovery vs ladder-entry route). Inspect RoadNodeEvaluator ladder neighbours and RoadNavigation stuck recovery; preserve fall safety.

Coverage limit: the assertion only checks final feet elevation <=ground. Even a pass would not prove safe ladder descent; it could accept falling. A future correction should additionally establish safe descent/no injury and reaching the intended resting destination, while retaining exact problematic spawn position. Do not move the actor off the troublesome corner as the production fix.

## Duel-loss isolated reproduction - 15:02:25 Oslo

Existing scenario_parley_duel_lost batch, unchanged current-verification source; fresh duel-focused-world:1 required test,1 failure, exit1. Player health remains20 and outcome list is empty after the scheduled captain-source hurt call. Evidence duel-focused.log. Command ./gradlew.bat runGameTestServer --offline --max-workers=2 -I C:/Users/tobia/Hearthstead-Claude/build-codex/duel-focused.init.gradle -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/current-verification-build.

ScenarioParleyGameTests.java:294-312 schedules damage65 ticks after setup, clears invulnerableTime, calls player.hurt(mobAttack(captain), health-2), then expects loss without death and band release. Player helper uses real ServerPlayer with embedded connection and vanilla placeNewPlayer, not makeMockServerPlayer. Therefore mock invulnerability cannot be assumed as the cause. Need inspect hurt return and incoming/yield hooks, spawn immunity and player tick progression. This is an isolated failed outcome, not yet a classified duel production defect.

## QA-TEST-06 (P2): embedded duel player retains spawn damage protection

Single-case diagnostic duel-probe.log: after65 scheduled world ticks player tickCount=0, isInvulnerableTo=false, abilities.invulnerable=false, alive=true, duel=true; hurt returnsfalse, health20,outcomes[]. Vanilla mapped ServerPlayer.hurt separately rejects damage while spawnInvulnerableTime>0; ServerPlayer.tick decrements that field (not the same counter as LivingEntity.invulnerableTime). Thus clearing invulnerableTime in ScenarioParleyGameTests:302 does not clear login protection.

Private control only: explicitly invoke p.player().tick()65 times immediately before the same damage call; unchanged production. Result duel-ticked.log: hurt=true, health20,outcomes=[false]; All1 required tests passed, BUILD SUCCESSFUL at15:04:30. Health remaining20 is compatible with yield cancellation; outcome and band-release assertions pass. tickCount still0 because this ServerPlayer override and doTick serve different parts of the tick lifecycle; do not use tickCount alone as proof no server-side method ever ran.

Command: ./gradlew.bat runGameTestServer --offline --max-workers=2 -I C:/Users/tobia/Hearthstead-Claude/build-codex/duel-ticked.init.gradle -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/drunk-single-build ; cwd drunk-single/hearthstead-neoforge, fresh duel-ticked-world. Recommendation: proper embedded player connection/ticking lifecycle in the fixture and explicit damage-readiness precondition; do not remove production spawn protection. Diagnostic burst ticking is evidence, not necessarily the preferred permanent test fix. Real client duel behavior remains unverified by this experiment.

## PR5 independent local verification - head62ca3188294e5ffdff892d65993a691a879890e3

Read production diff and both new test files, relevant loading/retention/self-fetch callers, requestItem duplicate suppression and full-chest deferral. No concrete blocking defect identified in this bounded review; not a blanket release approval.

Private pr5-verification copied from current-verification with only the3 PR files replaced from exact head. New private pr5-build. Targeted JUnit command: ./gradlew.bat test --tests com.hearthstead.entity.ai.BuilderLoadWindowTest --offline --max-workers=2 -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/pr5-build. XML:7 tests,0 failures,0 errors. GameTest command: ./gradlew.bat runGameTestServer --offline --max-workers=2 -I C:/Users/tobia/Hearthstead-Claude/build-codex/pr5-builder.init.gradle -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/pr5-build. Fresh pr5-builder-world, builder_load batch:4/4 PASS, exit0 at15:08:09. Evidence pr5-junit.log, pr5-builder.log and JUnit XML.

Coverage: selected material beyond64, full sack, full hut, two Builders/two sites with per-item conservation; pure window boundaries through511 and larger bag/scaffold. Does not verify courier request beyond128 end-to-end,192 generated blueprint failures, real co-op or native play. Full JUnit not rerun by Codex for this PR; Claude reports1157/1151/6 separately. No shared-source integration or merge.

## QA-TEST-07 (P2): first-raid assertions retain the old five-actor composition

Current source inspection explains two broad-suite failures without changing gameplay. RaidDirector.java:115-120 documents the26 Sep curve: one bandit captain plus three bandits, no Brute; firstRaidVariantFor:482-487 returns BANDIT for every valid first-raid slot. FirstRaidReadinessGameTests.java:733-743 already checks the new composition, then:744-763 contradicts it by requiring one non-captain BRUTE plus three SKIRMISHER records and saying five actors. RaidQaFixtureGameTests.java:824,827 hardcodes spawned==5 and participants==5; later:874-879 repeats5. Actual broad log shows STARTED with4, replay ALREADY_STARTED with4, as the new composition specifies.

Recommendation: update stale test expectations to the current explicit four-bandit contract (and retain exact captain/UUID/role ledger agreement and replay conservation checks). Do not simply delete roster assertions or change production back to five. Confirm product policy with Claude if the escalation curve is disputed. Evidence current-gametest-v2.log and source locations. No corrected rerun yet, so this only diagnoses these specific failing predicates and does not claim either whole test now passes.

## Native client preparation audit (not yet launched by Codex)

Read qa-survival/launch.sh only; did not execute it. It targets Claude-owned /root/hssurv-frozen, /root/hssurv-game and display:80, so it is unsuitable for direct Codex reuse. Read-only WSL inspection confirms Xvfb, xdotool and a106-line launch template with97 classpath entries; six entries reference hssurv-build. No --gameDir or --accessToken option present in that template. Existing Claude game directory retained untouched.

Next native step requires a Codex-owned frozen classes/resources directory, separate game directory/display, and a sanitized copied launch description that substitutes every source/build reference. Do not count the old frozen client as latest snapshot evidence. No screenshot or interactive result has been obtained from this preparation.

## PR5 follow-up test review / client preparation

Remote PR5 advanced to529d59827ac7382f98dd6d626d86cb45fb56e58e with only BuilderLoadWindowGameTests changed. Reviewed added Courier case:144 prefix cobblestone, plank beyond BATCH_UNITS, warehouse holds plank, employed Courier, completion and material conservation across stores/bags/ground/placed. Claude reports5/5 and4/5 after removing targeted request line. These are Claude results, not Codex reruns. Production remains62ca318, so ongoing generated run is still the same production version.

Preparing isolated native client /root/codex-bannerhold-native-20260926 from current-verification-build classes/resources, with own game directory and copied/rebased NeoForge21.1.248 client launch metadata. This uses the baseline current snapshot, NOT PR5. Preparation process52637; do not rerun blindly if observation times out. Client not launched yet. Existing Claude folders remain untouched. Exact frozen file hashes will be frozen-identity.json when copy completes.

## QA-CLIENT-01 (potential P1): native clean-client default configuration failure

Actual native launch baseline current-verification classes/resources (5039 frozen files hashed in /root/codex-bannerhold-native-20260926/frozen-identity.json), new empty game directory, MC1.21.1/NeoForge21.1.248/Java21, rendered mod-loading error. Screenshot native-startup.png. Stack ImmutableCollections.List12.contains -> ModConfigSpec.ValueSpec.test -> correct -> ConfigTracker.createDefaultConfig. Candidate HearthsteadClientConfig.java:134 defines healthCounter through List.of(lookAt,off), whose contains(null) throws. Causality control still needed; this is an observed startup failure, not yet a fully confirmed root cause. Reported to Claude for priority. Existing config may mask it. No gameplay/world loaded; native UI tests not achieved.

### QA-CLIENT-01 causal control: clean config fails, single default value succeeds

Same5039 frozen classes/resources, new game-control directory; seeded ONLY config/hearthstead-client.toml with [hud] healthCounter="lookAt". Native client reaches Minecraft welcome/accessibility screen, screenshot native-control.png. Initial empty-config directory fails during mod load, screenshot native-startup.png. No code or dependency change. Confirms missing-value/default-config path as blocker; HearthsteadClientConfig:134 List.of validator is the corresponding null-unsafe path. Severity P1 for first-time users or missing-key config migration. Requires a source fix plus empty/missing-key correction regressions; seeded-config workaround is NOT a shipped fix.

Original client385 exited via its Quit Game button; original Xvfb381 remains. Control client777 on private display:92 (Xvfb771), game-control folder. Main client process is now available for further visual QA with explicitly documented seeded config. Null audio output still precludes audible verification.

## Native UI execution — 2026-09-26 15:27 Oslo
Evidence level: Seen in game, narrow single-client UI checks only. Frozen current-verification classes/resources, private WSL display :92 and game-control directory. The QA-CLIENT-01 workaround is explicitly present: only hud.healthCounter="lookAt" was seeded before launch; this is not a fix acceptance run.
- Created new disposable world "Codex Visual QA 20260926", Creative, Normal, commands ON, Superflat. Rendered terrain and starter handbook in hand; no existing saves used.
- Right-click handbook opens rendered Settler's Handbook. Next changes Start here 1/10 to 2/10 (Raise the Banner).
- Search "tavern" populates results. Clicking Tavern evenings navigates to its second page and displays seating/bard guidance. No crash in these interactions.
- Screenshots: native-game-settings.png, native-world-load.png, native-handbook.png, native-handbook-next.png, native-handbook-search.png, native-handbook-result.png, native-handbook-scroll.png (all under build-codex).
- Not yet covered: full button matrix, Banner/tech screens, real two-client co-op, audible playback (null audio driver), survival progression or PR5 native integration. Existing Mayor text reflects the pre-Guildmaster snapshot; the new design is not implemented here.
- PR5 generated-construction session65543 positively re-polled live at15:24; no restart. Cloud Claude acknowledged QA-CLIENT-01 and is preparing a bounded correction separately.
## Native Banner navigation — 15:29 Oslo
Same frozen baseline/client and disposable Creative world. Gave one hearthstead:hearth item by command, placed using actual right-click. Settlement Birchwick founded and advancement appeared. Right-click on stand opened Banner overview with 3 Unassigned +1 Mayor. Opened Settlers, Buildings (empty state), Storage (empty inventory/open request), Journey (Research Lumber Camp), closed Journey via X, then opened Tech Tree. Tech Tree selected Journey Lumber Camp and Research was disabled with missing 1 Coin/8 logs/8 cobblestone. No purchase success or exact-once payment claim yet. Screenshots native-banner-place2.png, native-banner-ui.png, native-settlers.png, native-buildings.png, native-storage.png, native-journey.png, native-tech-open.png.
Visual observation to investigate: Settlers list renders Unassigned almost touching status circle/Idle at automatic GUI scale in 1920x1080; do not classify as confirmed overlapping text until measured. Vanilla tutorial toast obscures upper-right portions; this is not a Bannerhold layout defect. native-tech.png was still Journey overlay (outside click), not Tech Tree evidence; use native-tech-open.png.
## Native research transaction and world reload — 15:32 Oslo
PASS for this single-client scenario only: command-provided inventory2 gold_coin/16 oak_log/16 cobblestone; Lumber Camp ready. First Research click shows Confirm and coin count stays2. Confirmation expires if waiting; a prompt second click learns Lumber Camp, craft count1/17, inventory remaining1 Coin/8 oak_log/8 cobblestone. No Banner/Warehouse goods involved. Used Save and Quit to Title, selected same disposable world, reopened Banner/Tech Tree. Settlement still4 residents; research remains learned and inventory1/8/8 persists. This is an integrated-server world unload/reload, not a full client-process restart or dedicated-server/co-op proof.
Evidence: native-tech-funded.png, native-tech-confirm.png, native-tech-learned.png, native-after-inventory.png, native-saved-menu.png, native-world-select.png, native-reload.png, native-reload-tech.png, native-reload-learned.png.
QA-UI-01(P3): confirmation button text visibly clipped at1920x1080, guiScale=0: Confirm -1 Coins +8 An...; does not expose full cost inside button, though separate cost panel is visible. Sent cloud Claude on PR3 comment5846669267. Suggest concise Confirm label plus full cost tooltip/panel; no broad redesign. Note native-tech-purchased.png is only a renewed confirmation after timeout, NOT purchase proof; native-tech-learned.png is actual purchase proof.
Cloud config correction da67b842ca143246e9f5e7951dccf99edfac6cd5 reviewed: List.of replaced by Arrays.asList at healthCounter definition;4 JUnit empty client/server, invalid, valid-off cases added. Native empty-config acceptance still pending. No shared code changed.
## 2026-09-26 15:35 — codex — QA-CLIENT-01 native acceptance PASS
Evidence: Compiled + Seen in game (empty-config client startup). Claude commit da67b842ca143246e9f5e7951dccf99edfac6cd5 replaces List.of with null-safe Arrays.asList in HearthsteadClientConfig. Independently compiled ONLY that source (SHA256 efc4bddef15c0bb782390b804d1ad9117ecf91d7b28eb967ec30160c7798006f) against frozen current-verification dependencies using Java21; copied baseline compiled tree and compared hashes: only config main/enum class differs. New entirely empty game directory /root/codex-bannerhold-native-20260926/configfix-da67b84/game, no seeded config: rendered Welcome and main menu, generated hud.healthCounter="lookAt". Launch script, full command/identity, log and screenshots in build-codex/configfix-native. Baseline empty-config NPE and seeded-key control previously retained. Shared source not integrated. Native acceptance passed for this defect only. GitHub PR5 comment5846696266 delivered to cloud Claude.
## Two actual clients joined private dedicated server — 15:41 Oslo
Dedicated server PID4014, MC1.21.1/NeoForge21.1.248, frozen baseline plus config-only da67b84. New world /root/codex-bannerhold-native-20260926/coop-server/game/codex-coop-disposable. Bind127.0.0.1:25587 ONLY, offline test identities, Creative/Peaceful/view4/simulation4. Launch script build-codex/launch-coop-server.py; full command identity under WSL coop-server/identity.json. No owner server/save used.
Two real rendered clients connected simultaneously: CodexQA(pid777,display92) at15:40:06, UUID da2c6267-afd6-3685-ac37-914b6c3d528b; CodexQB(pid4296,display93) at15:40:49, UUID1fa4fcc3-6527-39d3-8a4a-2426ceacb2c0. Both rendered world and received starter handbook. Server log confirms both login and joined messages. Screenshots coop-a-joined.png and coop-b-joined.png. PASS limited to dedicated startup/two concurrent connections/starter handbook delivery. Shared purchase, settlement permissions, race conditions, combat and reconnect persistence remain untested; no broad co-op pass claimed.
## 2026-09-26 15:49 — Independent construction result and co-op research race
- Production revision62ca318: generated construction GameTests completed194 cases in37.78min;192 required failures,2 non-failing. Log pr5-blueprints.log. Narrow Builder load-window regressions passed earlier, but general blueprint completion remains unresolved. Do not treat192 failures as192 independent bugs.
- Two actual non-op clients (CodexQA and CodexQB) on own loopback dedicated server25587 founded/accessed Frostfield. Concurrent Lumber Camp research produced exactly one committed purchase and one stale rejection. A inventory2coins/16logs/16cobble became1/8/8; B stayed2/16/16. Both clients displayed the learned node. Evidence coop-research-clicks.json, coop-a-tech-after.png, coop-b-tech-after.png and server-controlled.log. Broader co-op remains unverified.
- Cloud Claude owns small patches already submitted through41eeffc; no duplicate Codex patch. Independent compile/fullJUnit is running against frozen private copy plus the12 changed files from62ca318 to41eeffc. Diff and downloaded hashes retained in pr5-fixes-41eeffc-diff.json and pr5-fixes-41eeffc-files.json.
- Owner requests small patches/debugging now; broad pathfinding/design changes deferred for main Claude. No shared production source changed or PR merged.
## 2026-09-26 15:52 — codex — Claude patch acceptance through41eeffc
Evidence: Compiled PASS. Full JUnit1161:1159 passed,2 failed,0 errors/skipped (BannerholdIdentityTest and NewPlayerGuidanceContractTest remain deferred). GameTest36/36 required PASS: builder_load5,craft_gate6,craft8,finisher3,parley13,first-raid journey1. Fresh private world,14.48s. Logs pr5-fixes-41eeffc-junit.log and pr5-fixes-41eeffc-gametest.log.
Command: ./gradlew.bat compileJava test --offline --max-workers=2 -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/pr5-build
GameTest: ./gradlew.bat runGameTestServer --offline --max-workers=2 -I C:/Users/tobia/Hearthstead-Claude/build-codex/pr5-fixes-41eeffc.init.gradle -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/pr5-build
Init uses own pr5-fixes-41eeffc-world and batchPrefix builder_load,techtree_craft,scenario_finisher,scenario_parley,first_raid_readiness_real_journey. Result sent PR5. Claude owns subsequent departure fixture and UI small fixes; Codex did not duplicate patches. Handbook generator source still needs hud row preserved on integration. Larger pathing/building work remains deferred, no shared code integration.
## 2026-09-26 15:55 — Native non-founder shared research
CodexQB, a non-op non-founder, bought Houses & Lodging through actual UI. Server committed techtree:home at revision1→2, item cost21 conserved. QB changed2coins/16logs/16cobble→1/4/8. QA remained1/8/8. QA reopened Tech Tree and saw Houses & Lodging Learned, Commons1/21. This proves shared access/payment/knowledge for this case, not continuous-open-screen push synchronisation. Screenshots coop-house-ready.png, coop-house-purchased-b.png, coop-house-shared-a.png; exact inventory and commit lines in native server-controlled.log. Server5080, clients777/4296, private25587, no ops.

## 16:00 — Co-op restart persistence and consolidated routing
Own dedicated server5080 stopped normally and saved all dimensions; same world restarted as7063. Both actual clients rejoined. Inventories unchanged: QA1coin8logs8cobble; QB1coin4logs8cobble, each one handbook. QB reopened Tech Tree: Houses & Lodging Learned, Commons1/21 and Craft1/17 retained. Evidence coop-restarted-learned-b.png and server-controlled.log; script coop-persistence-restart.py. This is graceful server-process restart, not crash recovery.
All currently known QA and outstanding T30/T31/T32 findings routed together on PR3 comment5846855198 and COORD/to-claude.md. Local ledger QA_ACTION_LEDGER_2026-09-26.md. Corrected duplicate UI ticket id: old no-op selection observation is QA-UI-02; native cost clipping remains QA-UI-01.

## 2026-09-26 16:02 — Native shared storage race
Both clients viewed the same8cobblestone in Banner slot0. QB deposited via actual shift-click first; block NBT confirmed8. Concurrent shift-clicks issued2.172202ms apart by coop-storage-clicks.py. After: Banner Items empty; QA kept its original8cobble; QB received8 in hotbar slot8. Sum16 acrossplayers+Banner conserved, no doubled stack. Both UI views showed empty Banner. Narrow one-run PASS, not all container actions proved. Screenshots storage-before-a.png,storage-after-a.png,storage-after-b.png;click trace coop-storage-clicks.json;console log copied to coop-server-evidence.log. Native results appended to co-op/persistence matrices without claiming all discovered cases executed.
## 2026-09-26 16:03 — Independent departure patch acceptance
4e7e075 WorldEventGameTests.java SHA256CBAE4C4AAF7206C2A78254939958A8888D9CD5BB08FD6804C71E0EDEFE305008 atop41eeffc private candidate. Command ./gradlew.bat runGameTestServer --offline --max-workers=2 -I C:/Users/tobia/Hearthstead-Claude/build-codex/pr5-departure-4e7e075.init.gradle -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/pr5-build. Own new pr5-departure-4e7e075-world;event_departure_ filter kept10/2101;10/10required PASS10.51s,CompiledPASS. Log pr5-departure-4e7e075.log. Sent PR5 comment5846880474. This is fixture-only acceptance, not proof all departure gameplay correct. Terrain extends40blocks outside arena; full-suite neighbour isolation remains to verify. dd90575 contains generator-source handoff patch; reviewed only, not integrated/regenerated locally.
## 2026-09-26 16:10 — Broad candidate and native UI acceptance
Candidate module through e4b79b1:1907GameTests completed in3.257min,9required failures,1898non-failing. Generated194excluded via skipBlueprintBuilds=true because earlier long construction run retained separately. New isolatedworld pr5-broad-e4b79b1-world. Exact command: ./gradlew.bat runGameTestServer --offline --max-workers=2 -I C:/Users/tobia/Hearthstead-Claude/build-codex/pr5-broad-e4b79b1.init.gradle -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/pr5-build. Terminal16:07:48. Failure artifact pr5-broad-e4b79b1-failures.json,log pr5-broad-e4b79b1.log. Failed: halberdkit,raiddefenderdeath,farFisher,balcony,cottage-door,wedgedmerchant,drunksharedoverride,RaidQaFixturesealedgate,fisheryBuilder54/562. Departure10 and prior fixedcraft/finisher/miner/duel show no failure in this run. Different snapshots/order: no simple improvement attribution from17→9.
QA-UI-01 Native accepted: baselineconfigfix plus e4b79b1 TechTreeScreen sourceSHA256c46f63d4786b4862df9f42535af3953eaa17d8712b910112e80c2a80777ceadb,only3TechTreeScreen classfiles differ. ClientCodexQC8783 display94 ownui-e4b79b1/game joinsprivate25587. Fresh secondsettlement2000,1. Actual1920x1080autoGUI: intact Confirm-Cost Above,fullpricehover,timeoutResearch+normaltooltip restored,confirmedLumberCamp consumes2/16/16→1/8/8once. Evidence ui-fix-confirm-tooltip.png,ui-fix-timeout.png,ui-fix-purchased.png andnativeidentity. NofullPRnativeapproval.
Potential separatefoundingUIissue: openedBanner~0.5safterplacement;SurveyingtheRealm persisted andTechTreeclick rejected invalid_open_menu_authority;close/reopenresolved. Observedonce,needsreproduction/sourcecheck;notyetconfirmedbug.
## 2026-09-26 16:17 — QA-UI-03 P2: immediate founding menu keeps null identity (native confirmed)
Two fresh settlements reproduced on private dedicated server7063/port25587, clientQC8783/display94 (configfix + e4b79b1 UI). Place Banner, open about200ms later, leave open past founding. UI stays Settlement/Surveying the realm; TechTree requests rejected. Second case at4000,-60,1: FOUNDING_COMMITTED tick42656, invalid_open_menu_authority tick43009. Closing/reopening succeeds immediately. Screenshots founding-race-open.png, founding-race-denied.png, founding-race-reopen.png. Server log coop-server-evidence.log.
Source: HearthBlockEntity.serverTick lines63-83 only founds every20ticks; HearthBlock.useWithoutItem lines189-211 opens even while idnull; HearthMenu final settlementId at77/96/106 retains NO_SETTLEMENT. HearthNetwork.settlementOfExactOpenMenu781+ correctly rejects old authority. Fix must ensure valid identity at menu open/reopen; do not weaken authority checks. Sent cloudClaude PR5 comment5846974119. Production unchanged.

New cloud patches ced4d77: exact five files downloaded to private verification only. Separate read-only review found no concrete defects in bounded diff (charge lifecycle, stop voice by stored id, batch-scoped drunk override). Compilation/JUnit/targeted GameTests in progress; no native sound acceptance claimed.
## 16:19 — ced4d77 independent acceptance
Compiled PASS. Full JUnit1161/1159pass/2knownfail. Fresh-world captain_,tavern_drunk GameTests25/25requiredPASS (21Captain+4drunk),3.688s. Separate read-only reviewer found no concrete defect in exactfivefilepatch. Voice cleanup source-reviewed/compiled, NOT heard in game. Logs pr5-ced4d77-targeted.log and pr5-ced4d77-gametest.log. No Gradle process remains. Sent to PR5.

## 16:23 — native lifecycle and priority coverage audit
- Actual QC8783/display94 resource reload F3+T completed. Client reloaded291 authored clips/83 voiced lines; Banner map, resident icons and text render after reload. ui-qc-resource-reload.log; native-resource-reload-banner.png. No audio listening or long-run texture-leak claim. The first follow-up aim missed the Banner; repositioned and re-opened successfully, not logged as product failure.
- Open TechTree, teleport same non-op player50blocks away: menu closes automatically. native-range-before.png/native-range-after.png. One positive native range-lifecycle check; not a forged-packet security test.
- Priority matrix audited3cases: manual equipment reorder checks exact revision, stale rejection and NBT persistence; snapshot checks urgent-first/cap/positions. ledgerServesHighPriorityFirst only asserts computed FOOD-before-RESTOCK ladder, not a live delivery competition. Thus names alone do not prove all priorities/action orders. Matrix records explicit limits.
- Head62ca318 generated-build failure progress:188unique done-ratio rows total18,733/92,836steps (20.1786%);4additional lantern/raid-lane-registration failures. CSV pr5-blueprints-progress.csv. Sent requested aggregate toClaudePR5comment5847003314. Baseline18,248 figure is Claude-reported; same192failure count is not proof of same every-root-cause.
## 16:25 — native research failure/recovery and co-op funding
- Insufficient-material rejection PASS: QC had1coin/8logs/8cobble, opened LumberCamp in newWolfstead3430c563-f275-4f84-8a67-d6db2e4cc40c. Testconsole removed8logs while UI open. Six actual clicks led to materials rejection tick51918; coin1/cobble8 unchanged, no learning. Screenshots research-lowgoods-before/after.png.
- QA-UI-04 P3 CONFIRMED: rejection toast truncates after 'Banner, your pack and'; Warehouse omitted. Tooltip contains full message. TechTreeScreen.renderToast1404-1417 selects only first font.split line. Suggested wrap sizedbox or shorten toast while preserving full detail. Screenshot research-lowgoods-after.png.
- QA-UI-05 P2 CONFIRMED co-op affordability does not refresh: QB actually shiftclicked8logs into Banner4000,-60,1 while QC's TechTree stayed open/disabled. ServerNBT16:24:20 confirms8logs; QC still have0/disabled >15s later. Closing/reopening refreshes togreen8 and Research enabled. TechTreeNetwork sends snapshot on open/action and broadcasts from TechTree changes, not ordinary stock changes; TechTreeScreen retains snapshot. Suggested bounded refresh/explicit refresh affordance, preserve authority and avoid per-tick full scans. Screenshots coop-fund-deposited.png, coop-fund-stale.png, coop-fund-stale-later.png, coop-fund-reopened.png. Actual donor client93 and researcher94, ownserver25587.
- Recovery purchase PASS afterreopen: sixrapidclicks, exactlyone techcommit tick54535; QC1coin+8cobble and Banner8logs consumed once, finalpack onlybook+2Banners and Bannerempty. coop-fund-purchased.png, coop-server-evidence.log. Sharedstock+personalpack payment covered, not Warehouse physicalstock.
- Findings sent cloudClaude PR5comment5847039664; production unchanged. UI03 remains earlier separately reported startup identity race.
## 16:30 — QA-UI-03 independently accepted, including native repro
Exact commit1f01db1f3bf877d8baf66961f62575e04d7838df threefilediff pr5-ui03-diff.json. Separate read-only reviewer found no concrete defect. CompilePASS;112/112requiredGameTestsPASS,12.70s (banner_fresh_open plus hearth/founding/journey/blessing/first_raid/settlement), pr5-ui03-gametest.log/init.gradle/world. No new fullJUnit run sinceced4d77 (1161/2knownfail).
Native ownserver7063 gracefully saved/stopped;12164 restarted same disposableworld/port25587 with only exact HearthBlock.java and HearthBlockEntity.java patch compiled onto frozenconfigfixbaseline;5compiledclasses differed. Native identity server-ui03-native-identity.json; SHA HearthBlock5db191239abace9abbb45f78f7a37dde1df6df317e742e3ebe73692193d484a8, HearthBlockEntity142dda69f2ea8d538bd0d25bbb1a5d4cba5ed79f9fce07aaf282390ef1385c3b. ClientQC8783/display94 unchanged e4b79b1UI.
Actualplace Banner6000,-60,1 thenopen~200ms later: Oakholm/map/population4 immediately available; firstTechTreeclick works withoutreopen. Founding settlement8dad551d-2247-4b6e-bce6-60040e1ceea3 tick59485. Screens ui03-fixed-open.png/ui03-fixed-tech.png; earlierbaselinefailedtwice. DelayedrefreshGameTestpasses; multiple real stale viewers not exercised. Sharedsource/deployment untouched. SentClaudePR5.
## Reproduction index — candidate verification through16:32
All commands run from `C:/Users/tobia/Hearthstead-Claude/build-codex/pr5-verification/hearthstead-neoforge` with `GIT_DIR=C:/Users/tobia/Hearthstead-Claude/Verk-arbeid-/.git`, `GIT_WORK_TREE=C:/Users/tobia/Hearthstead-Claude/build-codex/pr5-verification`, `GIT_OPTIONAL_LOCKS=0`. Shared Git metadata is read-only. This is a composed frozen copy, not a claim that shared HEAD equals tested source.

Current private source manifest: `pr5-1f01db1-source-manifest.json`,4130srcfiles,SHA2569314f712615d1e9e441227136b34acc79f194e1903ca4263044c34b035f3adbc. Original candidate manifest `pr5-source-identity.json` identifies62ca318, followed by exact deltas41eeffc/4e7e075/e4b79b1/ced4d77/1f01db1. Logs have their own identity in `QA_TERMINAL_EVIDENCE_2026-09-26.json`; the manifest made now does not retroactively identify an older run.

Common GameTest command:
```
./gradlew.bat runGameTestServer --offline --max-workers=2 -I C:/Users/tobia/Hearthstead-Claude/build-codex/<INIT>.init.gradle -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex/pr5-build
```
| INIT | Run identity | Filter | Terminal result |
|---|---|---|---|
| pr5-broad-e4b79b1 | accumulatede4b79b1 | skipBlueprintBuilds=true |1907total/9requiredfail |
| pr5-ced4d77-targeted | accumulatedced4d77 |captain_,tavern_drunk |25/25requiredPASS |
| pr5-ui03 | accumulated1f01db1 |banner_fresh_open,hearth,founding,journey,blessing,first_raid,settlement |112/112requiredPASS |
Each init chooses its distinct private `<INIT>-world` directory, except ced4d77 uses `pr5-ced4d77-targeted-world`. Do not reuse an existing test world for a fresh run; choose a new directory in a copied init.
The ced4d77 command first attempted `compileJava test runGameTestServer`; JUnit's2knownfailures stopped it before GameTest, so GameTest was run separately. FullJUnit1161/1159pass/2fail is real; no fullJUnit rerun on1f01db1 claimed.

Native identity is separate from accumulatedGameTest identity: ownserver12164 carries frozenbaseline+da67b84config+onlytwoUI03productionfiles; QC8783 carries baseline+da67b84+onlyTechTreeScreene4b79b1. See `server-ui03-native-identity.json`, `ui-e4b79b1-native-identity.json`, ownprocesscommand in WSLcoop-server/identity.json. Native screenshot successes do not validate allPR5patches together.
## 16:35 — QA-UI-04 native acceptance
0bdbac44e0f737a55f372c6e0debca943d7a8abf exactTechTreeScreen SHA256c1f0a02ab16070001490eb686ae4514d470a064787bbd9ece0c6317962917414 compiled on priorUIbaseline; QC12973/display94. Same materialremoval-whileopen repro now showsboth toastlines includingWarehouse). Screenshotui04-full-refusal.png. Separate readonly reviewer no concretedefect. Native serverUI03-12164 unchanged; no fullsuite rerun claimed. Files launch-ui04-native.py,ui04-native-identity.json. UI05 affordability stillopen.

Asset-validatortriage:415flags classify Lang1,Textures193,Sounds132,Recipes32,Tags52,Pipeline4,UI1. RaiderLook.java61 explicitly uses64x64, contradicting validator128x64 assumption for raiderlayers. WeaponregistrationsgeneratedbyWeaponType; ambient.market_bustle usedbyAmbienceBedsClient. Theseexamples demonstratefalsepositives, not blanketclearance of415flags. Pipeline4differences need ownerassetauthority; do not overwrite hand-authored assets by oldgenerators. Animation97definitionchecker is older scope than native291loadedclips; counts are not equivalent.

## 2026-09-26 16:43 — codex — QA-ANIM-01 and cloud follow-up
Seen in game: two real clients in disposable world. Debug `hsrevive down CodexQC` (forced trigger, not raid lethal-flow proof); QB hold right-click1s ->33%, release1s -> progress cancelled, hold4s -> successful revive16:37:37. QC remains visually malformed after completion and minutes later: body rotated upward, legs disconnected. Walking0.4s also does not clear it. Screens coop-revive-progress.png, coop-revive-interrupted.png, coop-revive-complete-b.png, coop-revive-current.png, coop-revive-walk-control.png. Server12164 UI03-only patch; QB4296 configfix baseline; QC12973 UI04-only patch. PlayerClips unchanged. Suspected stale bone transforms: Model.setupAnim invokes vanilla before any reset and returns immediately for null provider; absolute reset only inside active clip. Root cause not yet proven by patch/control. P2 QA-ANIM-01 OPEN, cloud asked to inspect and propose narrow fix before animation-engine edits.
QA-UI-05 bounded Banner-change refresh approved for isolated cloud branch; retains Warehouse/courier limitation, requires scroll/selection/confirmation preservation, authoritative payment, no idle polling scans. Both sent PR5 comment5847151642. No shared production edits/integration/deploy.
