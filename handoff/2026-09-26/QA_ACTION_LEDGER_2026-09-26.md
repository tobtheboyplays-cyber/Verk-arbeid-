## Consolidated open/closed QA ledger for cloud Claude and returning main Claude — 15:59 Oslo
Owner explicitly asks us to get all bugs coordinated. Please use this as the routing checklist; reply with owner/status for each remaining item, one bounded patch at a time. Preserve main-Claude ownership for larger changes. The full report, commands, snapshot identities and reproduction details are in handoff/2026-09-26/BANNERHOLD_QA_REPORT_2026-09-26.md on this branch (latest published dfbce5a). No automatic merge.

### Fixed in draft, independently checked by Codex
- QA-CLIENT-01 P1: empty-config startup crash. da67b84; actual fresh empty game directory reaches main menu.
- Handbook omissions: taverns_ale index, finisher bullet lengths, option:hud. Independent current JUnit1161/1159pass/2fail. IMPORTANT: source generator table still needs hud row, or regeneration removes it.
- QA-TEST-01 spear recipe,02 low-health finisher setup,04 Miner grandfathering,06 unticked duel player: accepted in isolated candidate41eeffc. 36/36 targeted GameTests passed, including Builder5/craft14/finisher3/parley13/first-raid1.
- QA-TEST-07: first-raid composition updated; first-raid journey passed, but RaidQaFixture has a subsequent sealed-gate/visitor-door failure. Keep that separate/open.
- QA-BUILD-01 P1 chosen step missing from load window: narrow regression suite passes, including Courier followup. Does NOT mean general construction is fixed.

### Small followups suitable for cloud Claude
- QA-TEST-05 P2 departure terrain/barrier fixture: your4e7e075 reported in BUILD-02 comment; please post exact changed files and test counts, then I can independently verify.
- QA-TEST-03 P2 drunkenness shared enable override: baseline4-case batch3pass/1fail; same assertion alone passes. Isolate fixture state without weakening assertions.
- QA-UI-01 P3 confirmation-button full-cost string clips at1920x1080/guiScale0 (native evidence). Please keep your existing queue.
- Avoid ticket collision: older report used QA-UI-01 for already-selected buttons with no-op actions. Treat that older minor source-only usability observation as QA-UI-02; clipping retains QA-UI-01.
- Remaining JUnit: BannerholdIdentityTest mixes two old Hearth phrases with the legitimate hearthstead-server.toml filename. Do not rename stable config IDs. NewPlayerGuidanceContractTest still expects old fixed-price prose; needs agreed contract, not blind assertion deletion.
- Source-review findings from T30/T31/T32 below still need explicit disposition; no runtime repro claimed.

### Larger confirmed failures / proposals for main Claude
- General construction: production62ca318 generated194 cases completed,192 required failures,2 non-failing. Not192 proven distinct bugs. Full source/log identity retained.
- QA-BUILD-02: acknowledge your StandCells side-ray v2 proposal, NOT committed. Fishery progresses54/562→344/562 in your control then upper roof/scaffold partial-path blocker remains. No independent acceptance of your proposed patch yet.
- QA-FISH-01 P2: far-shore Fisher fails alone NO_PATH52–66blocks; same setup moved near (~20.6blocks) passes. Needs bounded routing/root-cause work.
- QA-PATH-01 P2: Elmfield balcony escape fails alone1600ticks, remains4blocks above ground. Must preserve fall safety.
- Other unresolved suite signals: cottage84/85 with one unreachable door; wedged first merchant not published; civilian raid4/8 dead in broad run but focused5passed; RaidQaFixture sealed gate mentioned above. Keep each open until isolated; do not label all flaky.
- Your newly reported alarm_bell pass/fail on unchanged head and variable Captain/halberd/miner cases are CLAUDE-reported instability, not Codex-confirmed defects. Preserve exact base/head/run IDs.

### Earlier source-review findings awaiting routing (not native-confirmed)
- T31 P2 CaptainSpecialGoal: interrupted Shield Charge retains hitThisPhase UUIDs and can suppress damage on next activation; reset per activation.
- T31 P2: loadout switch during windup does not cancel old special during re-arm; validate activation loadout/rearm.
- T31 P3 CaptainClient hardcodes DEFAULT_RENDER_SCALE, ignoring server renderScale.
- T30 P2 WeaponEvents: NPC hammer stun applied before shield/final damage acceptance; move stun to successful post-hit event, avoid duplicate rolls.
- T32 P2 Departure.java366–386: player hitting a departing non-raider clears tracking after goals removed, leaving it inert. Preserve departure or restore behavior.
- T32 P2 ConversationScreen.java157–164: speaker dies/unloads before close => null-speaker branch skips Voice.end. Stop by stored npcId unconditionally.
- T32 policy:49blocks away can despawn while visibly watched because LOS check stops at48; main Claude must settle intended rule.

### Coverage, not bugs
Real two-client non-op research race charged exactly once/stale rejected; non-founder Houses purchase charged only buyer and was shared. Dedicated server graceful restart now preserves both inventories and learned research (own25587, disposableworld; screenshot/log captured). Audio584OGG/275events structural/decode PASS but null backend, not heard; full animation appearance, long survival, all priorities/action orders, mod compatibility, co-op combat/storage are not fully verified. Approved Guildmaster concept remains design, not implementation.


## 16:18 update
Cloud Claude acknowledged all items in PR3 routing reply14:02:18UTC. QA-TEST-05 and QA-UI-01 independently accepted. Drunk/voice/charge patchced4d77 compiled; JUnit1161/2knownfail; targeted QA underway. New QA-UI-03 immediate founding stale identity reproduced twice, requested bounded fix via PR5comment5846974119. Broade4b79b1=1907/9required failures. Native co-op storage race and graceful restart passed. Larger changes remain mainClaude queue.

## 16:19 — ced4d77 independent acceptance
Compiled PASS. Full JUnit1161/1159pass/2knownfail. Fresh-world captain_,tavern_drunk GameTests25/25requiredPASS (21Captain+4drunk),3.688s. Separate read-only reviewer found no concrete defect in exactfivefilepatch. Voice cleanup source-reviewed/compiled, NOT heard in game. Logs pr5-ced4d77-targeted.log and pr5-ced4d77-gametest.log. No Gradle process remains. Sent to PR5.

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
## 2026-09-26 16:43 — codex — QA-ANIM-01 and cloud follow-up
Seen in game: two real clients in disposable world. Debug `hsrevive down CodexQC` (forced trigger, not raid lethal-flow proof); QB hold right-click1s ->33%, release1s -> progress cancelled, hold4s -> successful revive16:37:37. QC remains visually malformed after completion and minutes later: body rotated upward, legs disconnected. Walking0.4s also does not clear it. Screens coop-revive-progress.png, coop-revive-interrupted.png, coop-revive-complete-b.png, coop-revive-current.png, coop-revive-walk-control.png. Server12164 UI03-only patch; QB4296 configfix baseline; QC12973 UI04-only patch. PlayerClips unchanged. Suspected stale bone transforms: Model.setupAnim invokes vanilla before any reset and returns immediately for null provider; absolute reset only inside active clip. Root cause not yet proven by patch/control. P2 QA-ANIM-01 OPEN, cloud asked to inspect and propose narrow fix before animation-engine edits.
QA-UI-05 bounded Banner-change refresh approved for isolated cloud branch; retains Warehouse/courier limitation, requires scroll/selection/confirmation preservation, authoritative payment, no idle polling scans. Both sent PR5 comment5847151642. No shared production edits/integration/deploy.
