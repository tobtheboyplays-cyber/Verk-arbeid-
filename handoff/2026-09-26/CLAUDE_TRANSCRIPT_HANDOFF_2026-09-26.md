# Claude transcript handoff — 26 September 2026

## Scope and evidence
Read the ten Agent transcripts named in the owner's screenshot, including substantive assistant reports and messages to the lead before the quota error. Source session: `4f4a9c83-1374-46cf-9243-77f4104be392`, subagent IDs below. Times in transcripts are UTC; add two hours for Oslo. The UI's Failed label is not evidence that implementation was lost. Agent-reported compile/test results are historical claims, not a fresh Codex rerun. Do not publish raw transcripts.

The wider project launch index contains 153 unique project Agent launches. It has been indexed, but this document does NOT claim a full evidence review of every historical launch. Screenshot Bash queue/completion rows also do not independently establish successful rendering or product verification.

| Task / transcript ID | Progress before quota | Remaining / resume point |
|---|---|---|
| Companion mods `aa9a616be11ba76bd` | Compatibility scripts/private setup staged; jar block IDs inspected; optional integration plan written and sent to builder/blueprint lanes. At 11:42 UTC requested permission to start S1/S2. | Do not claim runtime compatibility passed. Read modcompat plan, run staged compatibility scenarios in allocated isolated slot. Property preservation, cost aliases and computed window properties were proposals, not implemented integration. |
| Survival QA `af80008a1c649caf4` | Session 12:43–13:30 Oslo: founding chapter, 16 steps passed, 7 bugs + 12 UX notes reported. Recipe collision and recipe-book omissions fixed. Day-zero brute toll fix observed in a second fresh world. | Resume with Lumber Camp, plaque, Work Scepter zone, actual Lumberer work and early economy. Hillside merchant and early goblin exposure remain open. No complete survival or two-client playthrough. |
| Handbook `a3a38436433cbc8cc` | New book layout, recipe grids, item catalog/tooltips, search ranking, tech-gate display, generated reference chapters; final report claims 33 JUnit tests passed with empty allowlists. Earlier UI was seen by survival QA. | Latest recipe grids/tooltips/gates still need in-game verification. Fresh Codex snapshot has handbook schema/reference failures; fix those against current data rather than rewriting the book. |
| Founding/coins/raids `a3dec6ca7db9a97ec` | Civilian death analysis traced unsafe homeward flight. Shelter threat checks, route clearance, target ranking, alarm changes and scenario test authored; historical private compile reported. Last substantive action: shared alarm helper. | Civilian-safety GameTests and before/after soak remained queued. Confirm final helper state. Do not describe the survival improvement as demonstrated. |
| Watch & Defense `a4bc51ff0b8494bb8` | Defense branch implemented; removed defense cells no longer slow raiders; regression assertion added. Weapon gate coordination settled on framework recipe_gates. | Last requested 14-test rerun; barricade and captains_commission had not yet passed at request time. Check captain's later W20/W21 results before duplicating. |
| Medieval blueprints `abe4f8c266eb4b115` | Reports 194 blueprints, job-specific yards, fishery basin fix, YardScanner, TownStyle; private compile and TownPaletteRules 6/6, offline validator 194/194. | Offline geometry is not building execution. Blueprint yard/catalog/fishery and full builder cycles needed. Town-style UI not seen. Codex full construction run is now exposing failures; preserve designs and triage builder behavior. |
| Tavern Blender `a3f6c811019e78601` | Drunk clips exported with preview reel; 34 voiced clips received sound cues. Innkeeper aisle/counter fix and drunkenness fixture fixes landed after W20 snapshot. | Last Java edits waiting on compile retry due unrelated RaiderRenderer failure. Rerun tavern_seating and four tavern_drunk cases on new snapshot. Preview reel is not in-game proof. |
| Builder `a1f86e34f18da84e3` | Nearest-block selection, 64-item loads, utilisation stats, faster placement, water basin support, grouped presets; compile/building units and preset tests reported. Take-2 measurements recorded. | builder_util / builder_water and take-3 film pending. Codex now independently reproduces a selected-material batching defect at 65 required units; do not merely increase sack capacity or repeat old footage. |
| Captain weapons `aeb08bef7b66216aa` | Weapon models/recipes/poses implemented. Short-sword recipe stack-count bug fixed; four focused JUnit suites reported passing. | Latest low-ready v5 is a rendered proposal awaiting approval, NOT wired into game. Weapon GameTest rerun, dedicated-server boot and in-game stills pending. Preserve distinction between v4 live and v5 preview. |
| Enemy Blender `a0f3ccd6436f1843f` | Raider/goblin clips authored; clipping/impact fixes iterated. At 11:42 UTC reported heavy re-export 2.20 seconds landed, final previews next. | Verify timing against combat hooks and render/review final previews. Codex checked disk: current heavy clip IS 2.2s; earlier Codex test snapshot IS 1.8s. Old duration failure is stale for current asset and needs targeted rerun, not undoing the new export. |

## Verified current artifacts / discrepancies
- Read current `qa-survival/PLAYTHROUGH.md`: ends at save/quit 13:30, before workplace/worker loop completion. It documents the day-zero fix in a new world.
- Read `modcompat/INTEGRATION-PLAN.md`: explicitly titled plan only, nothing implemented. Its mention of versions tested must not be promoted to completed gameplay compatibility.
- Checked current `src/main/resources/assets/hearthstead/animations/raider/raider_heavy.animation.json`: animation length 2.2. Tested baseline private copy: 1.8. This proves source drift, not that every timing issue is resolved.
- Existing builder state note: `plan/state/builder.md`.
- Full Codex GameTests are still running in a private project/world; pending results are not final.

## Continuation rules
1. Preserve landed code; resume from the open evidence gaps above.
2. Compare each new result to the exact tested source snapshot. W20 predates some fixes.
3. Read captain results for queued runs before repeating them; verify actual results, not queue acknowledgements.
4. Keep native graphics/audio, dedicated-server and real two-client co-op checks explicitly separate from unit tests and synthetic GameTests.
5. For the GitHub handoff include sanitized authored reports, current source and portable task state, not raw logs, credentials, personal worlds or machine caches.
