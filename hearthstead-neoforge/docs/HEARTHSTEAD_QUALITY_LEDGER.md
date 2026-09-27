## 2026-09-17 — autonomous split-stack Farmer loop and reload boundary

Same bbf candidate; no product edits. **PASS first autonomous loop; reload
conservation/unload PASS, subsequent harvest FAIL within180s.**
Fresh `playtest/20260917T105827.522380713Z-650.4VKW6l`: singleton fetched/planted
at1000 while17Fine seeds remained intact; real harvest2545, replant2978, wheat
stored3132. Clean checkpoint phase farmer-split-stock-loop-before-reload,
manifest573db3d22675a1cc9a5ce88217ece5b88884d0405791f61f3dd1d5c523a3ff4f,
all saved-file hashes verified. One assisted setup, then ordinary20TPS AI;
frozen morning and growth64 explicitly exclude normal pacing/unaided founding.
Screenshot inspected: crop visible, chat overlay, no motion/audio approval.

Exact reload `playtest/20260917T110603.888066987Z-651.UmDjvz`: same actor UUID,
18seed total/1wheat conservation PASS; remaining2seed cargo completed without
replaying committed wheat, chest18seed/1wheat and empty bag PASS. Next harvest
receipt timed out180s: overall FAIL, no new checkpoint. Read-only failed-world
comparison shows crop age1 unchanged despite worldTime3137->7089 and wet soil.
Spectator-only observer is a growth/ticking hypothesis, not a verified root cause.
Keep assertions/timeouts; diagnose active-player ticking before worker changes.
5yBQlu prelaunch failure was missing camera capture_pos; corrected only that
scenario requirement. Detailed next action in CURRENT_TASK; scenarios, backups,
lease and non-resumable diagnostic world in Temp/resume-20260917/farmer-loop/.
No native install/raid/release claim. Cleanup check no leaks/all five ports free.

## 2026-09-17 — singleton seed withdrawal repair

**VERIFIED package and all901 GameTests; autonomous farm loop remains OPEN.**
Candidate `0.2.0+g95425795c290.ibbf395c18f87f726af12`; JAR SHA256
`c162c8b9aa5f9731ae7d504582b0ef2472bc328403efc05c9b0a6795440e16be`.
Package PASS `qa/reports/artifacts/20260917T100054.490245544Z-395.e07T0G`.
GameTest PASS `qa/reports/artifacts/20260917T100436.020791826Z-1280.xzXMzT`,
authoritative server summary all901. One added required regression; controller
expected count900->901. Existing assertions/timeouts retained.

Old53d exact market-checkpoint resume `playtest/20260917T095246.432773312Z-655.2Qq4vx`
FAILED autonomous planting after120s/242 probes at20TPS, without actor/field/input
edits. Reproduction was saved; no replacement checkpoint was created.
WorkerStorageAuthority counted after removal using an emptied mutable stack's
AIR identity, rejecting18->0 instead of the real18->17 across split seed rows.
The repair uses the already captured pre-transfer item identity. Regression
proves one tagged seed withdrawn,17 reserve seeds unchanged with components,
and exact persisted action. Physical approach and full farm loop in a new bbf
world are still UNTESTED; old53d checkpoints remain immutable and cannot be
repinned. No native install, raid, visuals/audio/FPS/co-op/release approval.
Detailed diagnosis and rollback copies: Temp/resume-20260917/defense/.

## 2026-09-17 — fresh defense preparation, restart and physical market

**VERIFIED current53d; complete four-defender raid remains UNTESTED.** No product,
asset or controller edits in this batch. Same exact candidate
`0.2.0+g95425795c290.i53d611185a02780316b2`, JAR SHA256
`edc6c4273c222eea780b6c33d25c4e61c5484fbcf3e313bb696f9ee639505703`.
Existing all900/package results below remain the corresponding build evidence.

Three clean canonical playtests (paths under qa/reports/artifacts/playtest/):

- **PASS** `20260917T065559.522261009Z-641.1RJpqr`:
  `fresh-defense-ready6-phase-a`, checkpoint manifest SHA256
  `3b63b769437592a8f107cfb148634a969eb65e046152f9e185f2e46ea1933cd0`.
  Fresh normal seed20260907, actual Hearth(-587,71,-487), settlement
  `3a7c6eb3-631b-4e7d-b44b-62063fc5bc47`; two natural distinct-evening visitors
  admitted; six living residents and1Guard/1Archer, ready=true,SCHEDULED.
  Source scenario SHA15b6c84d... retained all previous assertions/timeouts.
  Buildings, costs, tools, work contacts and accelerated qualification were
  explicitly assisted; this is not unaided survival or60–90minute pacing proof.
  Actual Courier busy-with-cargo observation cleared within original completion
  window, and Archer readiness committed. Two screenshots and10s overview film.
  Root inspected overview still: chat obscures part of village, actors too small
  for detailed animation approval. Film presence is not motion/audio acceptance.
- **PASS** `20260917T071824.882563556Z-662.0XKPe3`:
  `fresh-defense-reloaded-guard-recovery`, manifest SHA256
  `0c03c2f957dca439da5083b0dbc4ff71759e69fe231e34ee5a68f99f0c839293`.
  Full process restart preserves all six UUIDs/jobs and same merchant. Exact
  Garrick `2091a5aa-c716-4be6-8a01-1a3b78b99de2` starts Energy0, walks to claimed
  bed, sleeps and reaches1.5 at tick56714. Normal20TPS, no health/needs/actor/
  order/clock edits. Same actor score comparison passes; clean saved Energy1.5.
  This proves beginning of ordinary recovery, not full rest or raid aftermath.
- **PASS** `20260917T072534.523530188Z-659.gi3j8d`:
  `fresh-defense-market-offers-inspected`, manifest SHA256
  `413223f8a3814475a5f4ce2140fe52d5ab06aede12b95bf33f1a8814ceb8f778`.
  Same arrived visitor UUID ints[-2121939113,1406026403,-1425902445,-1512478400]
  and exact settlement ownership. Safe player-only contact cells, tp facing that
  exact entity, real sneak-right-click; NPC unchanged. Actual CoinMerchantScreen
  and5 published offers observed in English1280x720 GUI3 and root-inspected still.
  Offers: Basic oak8/wheat16/iron8 ->1Coin, each max1use; Fine oak4/wheat8 ->1Coin,
  each max8uses. All unused; no trade, Coin gain, stock grant or recipe edit.
  Saved offers match logs; exact checkpoint world manifest has zero mismatches.
  DayTime51437/gameTime56783; same GuardEnergy4.5 sleeping; original6 roles persist.
  Player survival, handbook only, hotbar8 selected. This is latest resumable parent.

Failed observation attempts retained: NdjgKK rejected a relative checkpoint path
before instance creation (absolute /mnt/c path required). aJAiDj restored validly,
then the new energy observer lacked limit=1 for `data get entity`; server refused
its potentially plural selector. Fixed ONLY that scenario selector; 0XKPe3 passes.
Neither failure is classified as a gameplay defect. Parent checkpoint never edited.

**OPEN next core issue: Farmer autonomous restart.** In1RJpqr, last planting
receipt was tick11985; final harvest action eeb394e4-28b5-4569-bf32-1cb5b94a7b53
committed15077, followed by wheat15397 and seed15437/15477 deposits. Then repeated
NO_VALID_TARGET began BEFORE reload. Stopped-world crop(-584,72,-466) is AIR above
hydrated farmland(-584,71,-466), inside the valid one-column zone. Farmhouse
(-581,71,-471) retains1Basic+17Fine seeds and6Fine wheat, Warehouse1Fine wheat.
Inspected source accepts Fine seeds and no work-energy quota explains this.
The fixture has a direct harvest helper without reseeding, but attribution of
this last harvest and subsequent automatic refusal is not proved. Do not manually
reseed and call it a fix. Preserve pre-plant checkpoint; next inspect candidate
selection, ground/reach eligibility and final action origin. Exact source/log
citations and safe cells: Temp/resume-20260917/defense/external-proposals/FARMER_OPEN.md
(SHA02779641C3D92DE1554A2884205CE0D48CD40BDE51C51E28DCD9F5454E35EC43).
This supersedes the earlier immediate-manual-sowing proposal in RESOURCE_NEXT.md.

Known fixture limitation: its direct Lumber proof consumes two logs/leaves without
planting a sapling. The stopped root(-559,71,-481) is AIR over dirt, with no owned
sapling. Normal Lumberer has a separate replant path; no production regression is
established by this fixture's empty root. Do not wait expecting an absent tree to grow.

Remaining ordinary defense inputs: capacity9 and10physical beds, no demonstrated
housing expansion requirement;64bread but0player/HearthCoins. Two Fine logs and
7Fine wheat are below actual sale minima. Current Guard/Archer private wallets
hold3Coins each and are not earned player funds. Fixture Basic planks are not
among this first merchant's offers. No spare sword/bow/arrows; tower has8arrows.
Two actual admission quotes plus both4Coin emblems total16–24 before discounts,
with56/64ready-meal reserves, real equipment/request/Courier custody and orders.
First raid remains scheduled for night3; no rescheduling/forced targets/results.
Work-area expansion needs an actual Work Scepter(2flint+copper+2sticks); absent
flint/copper cannot be replaced by UI bypass or grant. The older proposal's
blanket literal-NPC-coordinate blocker is disproved by successful dynamic contact.

Raw stopped NBT audits, scenarios, backups and external proposals are consolidated
under Temp/resume-20260917/defense/. Authoritative checkpoint/world remains the
source of truth (ephemeral WSL diagnostic copies may disappear). No native profile
install, native motion/audio/FPS approval, co-op3–4, actual2G2A raid, victory/reward,
aftermath reload or release gate was completed. Runtime queue stopped; canonical
reap check reports no leaked processes and ports25571/72/73/74/76 free. Usage99%;
state preserved before exhaustion, no reset/purchase/paid-budget change.

## 2026-09-17 — merchant arrival and QA repairs verified

Final current candidate `0.2.0+g95425795c290.i53d611185a02780316b2`:
**all900 GameTests PASS** `20260917T061132.551115783Z-387.TDEyuQ`;
**package PASS** `20260917T061525.787612791Z-1571.4NUONZ`;
JAR SHA256 `edc6c4273c222eea780b6c33d25c4e61c5484fbcf3e313bb696f9ee639505703`.
All suites ended; no native install or release certification was performed.

Merchant production change: a rejected idle replan can query at most one safe
alternative per existing40-tick gate, cycling20 columns at radius2..6 around the
same bound Hearth. Existing loaded-chunk, support, fluid, collision, full-path,
ownership and absolute-expiry guards remain. Destination/restriction changes only
after navigation accepts the route. No teleport, new visitor, payment or receipt.

- Actual reconstructed west **RED**: `playtest/20260917T051427.785821130Z-1534.GQe1dv`,
  d412 candidate. Same original seed20260907/site(2,88,25)/ownerbb043586.../
  tick1200 origin(-53.5,78,25.5), production target(-1,92,25),59-node path.
  Visitor f7e29dfd... remained at(-36.501,82,26.300), repeatedly rejected
  unsafe node36(-6,96,26), no arrival before180seconds. Flushed world later
  unavailable; authoritative full NBT and trajectory logs were preserved.
- Added real dynamic-obstacle regression **RED** on original production:
  `20260917T052648.740994706Z-406.jR5qJc`, blockedAt3, alternate=false,
  navDone=true, walked15.32, failed1199. Actual publication and ordinary AI,
  no merchant teleport/direct goal tick/forced arrival. Separate Tavern test
  failed too; not conflated with this measured merchant failure.
- Merchant fix **900/900 PASS**: `20260917T053048.817553712Z-388.J4uMeE`.
  Same actor physically reaches replacement; safe remaining path nodes,
  ownership/expiry/receipt conservation, no duplicate visit and serialization
  checked. Roster899->900, original timeouts and assertions retained.
- Exact package **PASS**: `20260917T053426.267428323Z-1596.gkIT0r`,
  identity `0.2.0+g95425795c290.if14f268dc81e2128daaa`,
  JAR SHA256 `23ed95cda48f553e10c5846651ec47be8ae2536198c650bae7aa5151c2f05d88`.
- Same unchanged west scenario4BA5C0CF **PASS**:
  `playtest/20260917T053844.350928578Z-2845.FRegN4`.
  Exact same publication origin/target/59nodes; new visitor3263701e... rejected
  unsafe point(-6,96,26), selected(2,88,22), actually arrived tick1877 at
  (1.6375,89,22.6590), elapsed33.808576seconds, original180 limit.
  Same UUID/owner and expiry25200; saved/flushed/clean stop, no checkpoint.
  Diagnostic world `Temp/resume-20260916/west-fixed-FRegN4` preserved. Offline
  NBT terrain read confirms spruce leaves below the rejected feet node.
  Terminal screenshot inspected: environment/save state only; merchant is
  outside its framing. Arrival proof is actual server position/state, not image.
- Later QA-only clock restore plus observer, identityfa657fac, **898/900**:
  `20260917T054607.762675014Z-413.XJIm7v`. Doorstep and beyond-follow-range
  travelers were QUALIFYING by their arrival assertions. Production unchanged.
  This is not repaired by the shared-day-time restoration alone. Investigation
  also identified WorkerLogCapture creating an empty non-additive named logger
  after removal, suppressing later Hearthstead INFO and transition observations.
  Bounded QA logging/history repair applied; no gameplay relaxation.
- Logger repair **VERIFIED** in `20260917T055542.134669365Z-396.cjXq2E`
  (identity60fbb4c4): later Hearthstead INFO survives the worker capture batch,
  its exact captured-event assertions pass, and recruitment transitions are visible.
  Overall898/900, same two arrival assertions. Doorstep initialdayTime16145;
  physically WAITING_ADMISSION at66, legitimate DEPARTING at67 (night cutoff15000),
  LEFT/VISIT_COMPLETE124, next QUALIFYING126. This proves an ambient-night fixture
  error rather than failure to reach the door. Two isolated evening-window batches
  now retain every original arrival assertion/deadline, with prior dayTime restored
  on pass/fail/rerun. GameTime and night-departure gameplay are unchanged. Both
  arrival tests PASS in lGSkV2 and final TDEyuQ; actual observed arrival67 in lGSkV2.
- `20260917T060115.663106050Z-399.lGSkV2` overall899/900 exposed a different
  real aperture defect in the prepared raid fixture. All five raiders entered;
  Archer stayed at its post with no target/shot and all8 arrows in the rack.
  Eligible-live-target rays160/180/220 hit the OPEN south oak door;200 hit the
  OPEN powered iron door. Actual MC1.21.1 DoorBlock geometry shows the SOUTH
  pair LEFT/RIGHT folds into its central seam. Swapping only those two hinge
  arguments to RIGHT/LEFT moves panels to outer edges. Source materials/power,
  survey/container ownership, actors, orders/leashes and combat code unchanged.
  Exact prior source/patch/ten ray excerpts: Temp/resume-20260916/merchant-alternate-fix/.
- Final TDEyuQ **VERIFIED actual shot** at raid elapsed221: Archer
  18f17d41-631c-4184-b349-58138ee54d68 remained at the real TOWER_POST, acquired
  live original raider0c5f24f9-206c-4784-996b-e3cab96abf76, line-of-sight ray MISS,
  stock8 -> quiver7 + actual owned arrow2385af02-9052-49c7-8071-84bfab958c24.
  Five sealed raiders were still alive then. The unchanged real-shot assertion
  and all900 tests pass. No forced target, teleport, leash change or fake victory.
  This is a fixed local sight obstruction and verified route/shot, not proof that
  every possible raid bearing yields a shot or that the full native raid is won.

Native installation/motion/audio/FPS, current raid/aftermath and real3–4-player
co-op remain unverified. Prior789 earned-economy/Tavern checkpoints below remain
bound to789; they must not be repinned to these new identities.

## 2026-09-16 — verified core recovery, iteration scope

Current queue and accepted requirements: `../../PROJECT_STATE/CURRENT_TASK.md` and
`../../PROJECT_STATE/REQUIREMENTS.md`. Historical entries below are not startup reading.

- GameTest **899/899 PASS**: `qa/reports/artifacts/20260916T213652.991197797Z-395.oT5aLX/gametest.log`.
  Courier source-growth/save-reload recovery preserves all custody guards. Farmer
  rear-wall return now credits actual new consumed path nodes, avoiding false
  unreachable retirement; contact278/firstdeposit327/all14delivered847, cooldown0,
  original1200 limit. Existing sealed-storage alternative/conservation also passes.
- Label reservation-vs-contact fixture and four-target Guard allocation durability
  fixture corrected to actual current semantics; no production targeting change.
- Canonical playtest-checkpoint contracts **23/23 PASS**, including actual shell/client
  setup without inherited QA, spaced repo path, exact candidate/support bytes and world.
- Prior exact candidate771732e5d0368905123c Tavern/worker run
  `playtest/20260916T210154.119953187Z-2783.J5lbs2` remains **FAIL** for its unsupported
  generic-wheat-pickup→Finn-bag oracle. Valid separate evidence: real two-meal service,
  real Farmer output, Finn's own4oak pickup/delivery after source growth, and authorized
  Mayor wheat transport. Corrected69152/1VfLzP **PASS** on789: two completed meals,
  bread0/glass1 and welcome; Finn4oak request7b20fc4b-0322-4df5-a9ef-26e4ddb669c7
  reserve5780/pickups5942–6062/delivery6356–6476/satisfied6476; Elin harvest6629,
  replant7292 and wheat output; Mayor Alden3wheat request036ec61d-22d3-49a8-b649-4a9ed43c36cf
  own open/reserve15903/pickups15954–16037/delivery16252–16332/satisfied16332.
  Every actual transfer conserved, same source/target actors correlated, wheat label
  verified. Earlier source-growth reservations retired before pickup are distinct.
  Root/independent review packet: Temp/resume-20260916/tavern-1VfLzP-actor-request-evidence.md.
  Clean checkpoint manifest9f34de69feb18cd690362eb30a07cd2c287c95aa1050e33a21de3e1c757b4eeb.
  Seeded six-role fixture and sampled film stills; no native/motion/audio approval.
- Current exact package **PASS** `20260916T221704.815100919Z-395.2rOGoS`, identity
  `0.2.0+g95425795c290.i789666e79a1b55cf5cd0`, adds read-only UI/route observation.
  Earned-Coins parent **PASS** `playtest/20260916T222045.426635736Z-1513.JQVkLd`:
  same naturally arriving merchant, actual8Basic oak+16Basic wheat ->2physical Coins
  ->Timber Rights purchase, exact conservation, strict UI shots, clean checkpoint.
  Raw materials/Hearth/player contact are declared fixture assistance.
- First-worker child TNSpLx **FAIL** on obsolete left-click second-corner/height
  gestures; third sale, valid camp and first corner passed. Exactly two gesture
  corrections per existing right-click contract **PASS** as CXm73I: actual paid
  emblem, original founder586efa73-c88c-4aa0-acd0-f99ecd156caf hired into camp
  aa61c076-057d-4604-ba47-08ce19088877, normal axe pickup1634 and physical Fine oak
  deposit2782. Receipt matches worker/camp and actual chest1Fine; ten screenshots,
  clean stop/checkpoint. Manifest9130fd41613e27c38daf4eccb4ed04e4d1f1a589cab4ad8bdc89576eb406f7ce.
  Camp/tool/tree/contact positioning assisted, not unassisted survival/native motion.
  Original failed artifact remains unchanged. Fine sale82375/mxKcCm verified reload,
  actual4Fine oak/empty bag and physical chest UI withdrawal, then **FAIL** invalid
  `Offers.Recipes[1]{...}` NBT-path syntax before trade. Corrected632DF1DC scenario
  uses exact-index fresh diagnostic copies with copy-success/existence guards and
  valid named-object predicates; corrected32840 **PASS**, artifact
  `playtest/20260916T231302.424796402Z-14252.7veCqa`. Actual4Fine oak ->1physicalCoin,
  same original merchant/worker, exact rawFine row1 uses1/max8, source+bag+player oak0,
  Coin1, all nontraded inventory preserved including natural sapling. Five screenshots,
  strict merchant UI checks, root inspected selected/paid images, clean stop.
  Checkpoint phase earned-coins-worker-fine-sale,
  manifest21b7cdc372a3f8369844c488171407652bd7e837fb2d2469af81922191f883ed.
  This completes assisted earned-money -> unlock -> paid hire -> actual production ->
  paid Fine sale chain across real clean reloads on the same789 candidate.
- Natural west merchant7aTFPN **FAIL**, arrival absent at180seconds. Isolated
  6WwrVY probe **PASS** on current789: actual west-origin walk to(2,90,31), but this
  target comes from successful south parent, not the unrecorded original west visit.
  Original defect OPEN; no navigation behavior changed. Duplicate arrival2677/2678
  is a goal-tick telemetry issue, not proof of duplicate economic effects.
- Native motion/audio, current-profile identity, first raid/aftermath, actual co-op,
  performance and full release remain OPEN. Automated PASS is not premium/fun approval.

## 11 September 2026 — c407 real two-flank raid remains RED

Canonical94992: both Guards fought (8 real melee contacts,30XP),1Archer hit, five participants terminal. Raiders breached Warehouse and escaped with stores; outcome settlement_hit, Blessing0. Thus complete victory/reward/reload gate FAILED, no winning checkpoint. See PROJECT_STATE/RAID_TWO_FLANKS_2026-09-11.md. Delivered c407 remains unchanged.

## 11 September 2026 — c407 physical raid contact delivered

Raiders now require visible physical warehouse container contact on entry and every grab; full contact path with prior breach fallback retained. Three new real-goal regressions plus existing autonomous breach/loot conservation passed. Full GameTests800/801 (prior intermittent Hunter failure); canonical fresh normal six-resident assisted preparation67850 PASS. Identical c407 JAR native/server/friend exports; actual desktop Start/public Minecraft ping and clean Stop passed03:32–03:33. Real user save preserved. Exact evidence PROJECT_STATE/RAID_LOOT_CONTACT_2026-09-11.md. No animation/UI/balance changes in this batch; no fresh native visual/FPS/complete raid victory approval. Broad demo remains incomplete.
## Current update — 11 September 2026 02:48

Current installed/exported candidate is41691e23560b9ab76ed6, SHAedc5460719bfb876dfe741c8fa5687b73ee45086de898d362edc84ddc16a5d6b. Tower UI and strict civilian report/home/shelter regression passed; actual normal preparation/reload passed. Actual raid lost (no melee, settlement_hit); no raid payout approval. Native/server/friendfiles match; actual desktop Start/public ping and clean Stop passed on4169. UI/assets/animations unchanged from delivered6577. Full authoritative delivery and remaining limits: PROJECT_STATE/NAVIGATION_DELIVERY_2026-09-11.md. Older sections below are historical.

## 2026-09-11 — installed UI/trading update and fresh raid diagnosis

- VERIFIED, delivered candidate657721870f5deb8a2bb2 SHAaea09d4ba69bd7cb6032643184db83f87476e741feb4d824592e20fb4c32989b: package477, GameTests797, assets932. Installed native/server/friend ZIP hashes match. Actual public native join, JEI+ transfer with exact before/after inventory conservation, actual desktop Start/Stop and all-dimension save verified. Full report: PROJECT_STATE/UI_TRADING_DELIVERY_2026-09-11.md. These checks do not certify full survival or release.
- VERIFIED, scoped UI: all14screens use original shared materials; actual main/secondary screenshots and comparison gallery at art-output/ui-studio. Equipment/Blessing final visual scenarios were not reached. No animation change in this batch.
- VERIFIED, actual merchant6577: Basic single payment/disappearing offer, no repeat reward, Fine row remapping, close/reopen and 56logs+2Coins conservation. Artifactplaytest/20260910T224007.591494953Z-645.Q4Tqkp.
- FAILED, intermittent pre-raid delivery: normal88955 atplaytest/20260910T231049.569869734Z-645.tQv5mE stopped on3wheat_seed FARM_HARVEST remainder. No complete world survived that failed scenario. Second freshdiagnostic59357 atplaytest/20260910T232218.419829626Z-632.LK6H5I reached ready=true/no blockers; saved actualFarmerBagempty. Treat transit delay as OPEN, not fixed and not proven lost items.
- FAILED, actual Tower Post: raid96667 atplaytest/20260910T233254.598839663Z-648.7AVAq6 spawnedfive valid grounded raiders, passed GuardUIorder, then rejected ArcherTowerorder asreject_tower_post_unreachable. Diagnostic90760 preserved exact rejection/world atplaytest/20260910T234121.883590703Z-653.v9GLJU; ArcherOnGround1. PersistedWatchtoweranchor/plaque(-573,72,-460), bounds[-574,70,-467,-568,74,-461]. Radius1 candidatesinsideboundsallhitwallz=-461; validfloorinteriorz=-462 liesoutside search. This is a confirmed boundedpost-search geometry defect; patch/regression pending, no fixedclaim.
- UNTESTED in latest batch: actual victoriousproductionraid/+8Coinreload, paidTavernservice, fullorganic2playerplaythrough, fullx2/gate/sealednativeapproval. Keep overallgoalopen.
## 2026-09-07 — current combat defect, reviewable fixes and connected-demo plan

- **RED, actual gameplay:** captain in water confirmed on d3f6 by same-world playtest/20260907T204218.071579476Z-664.VTU6li, CAPTAIN_IN_WATER and CAPTAIN_OVER_WATER, after combat-only test201907 left it alive. All four defenders alive; previous battleqa lacks production pending-raid state and cannot certify first raid. No blanket production Archer path failure inferred from this fixture.
- **YELLOW, source/proposal:** Archer loose event now conditional on actual projectile success in source, unbuilt. Captain fluid-footing fix plus actual standableNear regression prepared and root-reviewed; corrected local/absolute assertion, added feet water/lava and head-water cases. Still unintegrated/unrun in handoff-inputs-20260907/captain-next. Preserve two previously failing GameTests; no new count/pass inferred.
- **GREEN, historical scoped evidence only:** d3f6 normal four-defender combat test201907 terminal PASS, two ordinary raiders gone, both Guards gained XP, one Guard lost6HP; follow-up204218 terminal PASS diagnosed water. Actual Home/Plaque82b7 test195747 had inspected screenshots and user-approved direction. Neither establishes all motion/UI, earned survival, native, co-op or release.
- **Planning delivered, not gameplay acceptance:** START_HERE_48H_2026-09-07 and linked mechanics/task/balance documents specify the connected physical-Coins route, early defense, visitor cadence, survival pressure/recovery and all demo checks. Actual code prices traced; first-income and staffing deadlocks reviewed. Goal PAUSED, owner resume required for automatic continuation. No new QA/native installation from the planning work.

## 2026-09-07 05:35 — one Archer test-phase correction; 766-test roster

Package69229 PASS candidate d378d6606bab497fd160 SHA5582FE01422C0822E8B7E10518C1CA0149A4B78D2F38EE5A4A58AD8D98FEB56F. GameTest64067 ran766 tests;765passed and only archerfindsandloosesataraiderwithnohelp failed. Artifact20260907T033008.569593734Z-386.yGguHZ. The new owned Coins paid/busy/reload case did not fail. Archer diagnostic had0quiver,4shots both before/after,dead target. Positive phase allowed lethal damage then reused that target for a40tick live-threat requirement; exact killer/tick was not recorded.

Integrated test-only BBC3CB65 after terminal: retire only this fixture's prior target and its archer-owned arrows in the owned volume, create a fresh real hostile for negative ammo phase. Preserve40ticks/autonomous acquisition/OUT_OF_AMMO/no-new-shots/goal-running/order/leash and16ammunition accounting. No production AI/equipment/health change. Required roster765->766 explicitly accounts for the reviewed one new owned merchant GameTest; this strengthens the expected count and does not drop checks. Backup Temp/early-coins-survival/root-integration-0535. Package plus relevant GameTest rerun next, then fresh Coins actual UI/arrival/worker chain. Userprofile unchanged; no demo/release approval claim.
## 2026-09-07 05:26 — physical Coins earned, saved and spent; detour fix integrated

Latest source batch is integrated, not yet packaged or runtime-approved. GoldCoinTrades now places newly issued owned-merchant Coin offers first without changing existing offer objects, uses, demand or saved order; open owned menus defer new rows. One real owned-merchant paid/busy/reload GameTest added (unrun). Guide labels now match Lumber Camp/Warehouse/Cultivated Ground/Home/Tavern. TravelerArrivalRoutes allows straight distance+32 capped128 while preserving16origins/4queries/2048nodes/retry/complete-loaded-safe-route requirements. QA-only first rejected-node detail added. Backups Temp/early-coins-survival/root-integration-0526.

Verified on prior e8c309cc004c344abb26 (JAR SHA27BE4FAC1B9B80D6BBB870A955F63FE9D1D7E52885B527A9F7CFB06A0A567C48):
- First-income44560 PASS artifactplaytest/20260907T024644.676482765Z-1500.04aYki. Actual outside56block publication and exact same trader/owner UUID, real walking,8Basiclogs->1Coin and physical Hearth storage/clean restart checkpoint. Manifestda5c017576478fa30e5009d262c04ac348334b1cef6e461a74f34cda5d3ff771, phaseearly-coins-earned-and-stored.
- Diagnostic10502 PASS artifactplaytest/20260907T030950.361582409Z-649.CLXiYY. Actual76logs->8Coins (7player+1Hearth), current Inventory capture; valid Tavern/houses, actual28000ticks, clean saved diagnostic checkpoint4429cbdfd0ef91042b5b77d2dcf7bdca8075383d3013b80f9437a5862ef6d05a. ZERO guests: diagnostic PASS is not arrival/payment PASS. Actual routes repeatedly stopped at56.934/57 and81.904/83, only3–5blocks from goal; saved final approaches are clear. This evidence motivates the integrated detour allowance, which still needs fresh same-site regression.
- Worker33186 FAIL artifactplaytest/20260907T031538.936101058Z-642.dhZFdA. Real34logs->4Coins, Development2Coin purchase, valid camp/3D zone, real Mayor Emblem purchase2Coins and correct wallet/Hearth depletion all passed. Worker click missed before hire; no first worker/output PASS. Source diagnosis: three frozen-world step ticks can publish on the last tick, leaving no client entity interpolation ticks. Ten real ticks match existing UI policy; tester prepares Temp corrected worker scene. No production NPC refusal established. Earlier92679 aimed toward stale client Mayor position; fixed Mayor contact now passed.

Controlled construction/raw inputs/contact positioning are assisted test setup, not a complete player-crafted/native survival session. Current user profile remains9cac unchanged; no native release, animation/audio/FPS or multiplayer approval follows from these results.

Next single root queue: package current batch, relevant GameTests once; fresh first-income F6BC scene, then same-candidate trade-before-construction06B3/28000 diagnostic and actual paid recruitment after authoritative WAITING; corrected worker continuation. Old checkpoints cannot be repinned across new source. Source/repo must freeze through each parent/child chain. Failed runs are never promoted.

ALL UI now has a prepared four-run batch at Temp/early-coins-survival/ui-execution-batch:13screen classes atGUI2+3,52screen and56state assertions; mainGUI2 5A1AC316, researchGUI2 7BFC1E2C, mainGUI3 FB6B875A, researchGUI3 886EC4CC. Static composition only; runtime/visual checks unrun. Actual2clientCoins/rejoin, natural-terrain productive village/combat, native motion/audio and release gates remain open. Latest complete gameplay suite remains765requiredPASS on170e97; do not present that as fresh full approval of this batch. One reset already redeemed, monitorpaused, no second credit. Delivery target remains7September20:00Oslo.
## 2026-09-07 04:23 — earned Coins verified; actual traveler route blocker isolated

First-income16808 PASS: ordinary seed20260907, four real founders, outside merchant walks to Hearth, actual8Basiclogs->1Coin, physical inventory->Hearth transfer, clean saved checkpoint early-coins-earned-and-stored. Artifactplaytest/20260907T013734.000394574Z-652.r6Semk. Candidate1e612bb3b34a9003d21a, JAR SHA E12A879C3948A800BA6F3BD73096584C6061DA0E53237F29416CDFBE7E0A9F85. Manifest SHA5DED3E28341BB95C1130BCBA8E9DD6C8E7A2960F57DF218D6587AE7043134DFF. Raw inputs/construction assisted; not complete player-crafted survival or native release.

Paid75190 FAIL at incorrect server-log arrival await: broadcast only writes client chat. Before failure, same-candidate restart retained actual earned HearthCoin; real76Basiclogs->8totalCoins, finite8uses/demand, actual InventoryScreen Coins tooltip/icon reviewed. Prepared compact3-lightTavern and3houses/9beds passed. Actual saved recruitment also reveals a SEPARATE gameplay blocker:1131/1131,600qualified, READY_TO_SPAWN with no published traveler/quote. Preserved autosave Temp/early-coins-survival/player-guide/paid75190-settlements.dat SHA FD632E629D40A3B564C46E49124537E5EFAE61ABEDE9B80F2303361EAB4B7541. Locked Tavern5,88,42, approach5,87,41. Two dry ground origins exist in retainedparent; source confirms correct explicit path-distance overload. No proven fix yet. Artifactplaytest/20260907T014457.569128342Z-652.Ue9hO4; no paid recruitment PASS.

First-worker38973 FAIL only Mayor contact preflight before purchase/hire. Actual4earnedCoins, TimberRights2Coin purchase through DevelopmentUI, real registeredcamp/workzone all passed. Actor ownership passed; originalcontact blocks are clear, foreign occupancy remains unresolved. Artifactplaytest/20260907T021226.157332082Z-633.2Ei120. Corrected controlled contact scene being prepared; no paid-worker/output PASS.

Integrated after both children terminated: two honest payment-blocker strings409C94; founding-guide early4Coin path E4A861; common true sneak-statistic initializer + early screen-grammar preflight BC076E (removed meaningless console double); QA-only route trace A28E207D (no new searches/random/limits or movement). Current candidate must be rebuilt; old checkpoints remain exact-candidate historical evidence and cannot resume on changed input. Next queue: targeted playtest-input contracts, package, fresh first-income, accelerated24000actual-tick route diagnostic with game-time delta and explicit diagnostic checkpoint; fix only observed cause, then actual paid recruit/worker acceptance. Accelerated simulation is not natural wall-clock pacing/native animation proof.

Fixture lessons: unused MerchantOffer.uses0 is absent; vanilla NoAIfalse absent; real player sneak statistics tick during world freeze. Earlier frozen-world diagnosis was wrong: original65600/63714 lacked hsqa_sneak objective. Generic screenshot+fresh exact Merchant/InventoryScreen for vanilla; expect_shot_ui is Hearthstead-only. No expect_screen null. Do not spoof logs or promote failed runs to accepted checkpoints.

Latest all-gameplay result remains765requiredPASS on170e97 (only later compactTavernlight/testdiagnostics/text changes). True2clientCoins/rejoin, allUI currentGUI2/3, native six-role motion/audio/FPS and normal-terrain productive raid remain open. Userprofile9cac unchanged. Fresh@oai/sky read showed CurseForge1.21.1/NeoForge21.1.248 and noMinecraftwindow; no native input/install/launch. One reset redeemed, monitorpaused, no second credit. Target remains7Sep20:00Oslo; no release approval claim.
## 2026-09-07 03:04 — compact Tavern correction packaged; first income input retry

Package75973 PASS, artifact20260907T010039.103223524Z-393.NycovJ. Candidate1e612bb3b34a9003d21a, JAR SHAE12A879C3948A800BA6F3BD73096584C6061DA0E53237F29416CDFBE7E0A9F85. Only source delta since765-pass170e97 is QA compact Tavern's third light; actual paid-recruitment construction is its next relevant verification. No repeat whole GameTest roster for this one fixture block.

Coins65600 failed only frozen sneak tick observation after real outside approach and founders. New strict-gate bounded-input scenes pending tester final hashes. Source frozen through first earned/stored checkpoint and same-candidate paid continuation. Bootstrap audit confirms player reachability,22Coins unlocks/emblems before3/4/6-discounted current traveler quote. Source audit is not earned full-survival evidence; controlled QA setup remains explicitly assisted.

## 2026-09-07 02:55 — 765 gameplay checks passed; actual first Coins trade next

Package5019 PASS, artifact20260907T004337.568925481Z-392.YdTYry.
Candidate170e97efef3f2e32a554, JAR SHA
B43D59CDCCB2DC0BFF8632F1CDA654ADDC3007B0456563A9788456A536BA045F.
457 JUnit tests across108 reports:0 failures/errors/skips.
GameTest22996 PASS all765 required; artifact20260907T005155.193860551Z-391.YlqThH,
terminal marker02:54:30. New actual14-meal Guard full-readiness test and both
wooden-sword1v5 tests pass. Capacity80 does not grant free healing on hire/load.

Previous60495 failed10/764. Seven order/equipment fixtures now explicitly start
their already-employed Guards healthy; the separate natural-hire test proves
actual meal debit and healing. Other three Farmer/Courier cases passed this run
after diagnostic-only changes; causality remains unproven. Tester compares traces,
not another speculative timeout increase or silent claim of a production fix.

Actual early-income playtest65600 now running, scene35093226 at fixed ordinary
seed20260907 terrain site. Artifactplaytest/20260907T005537.839220168Z-656.BloCxU.
Requires real outside merchant/walking, actual8Basiclogs->1Coin menu purchase,
physical inventory->Hearth storage and clean earned checkpoint. No Coin grant.
Source frozen through same-candidate F3F7 paid fifth-resident continuation.

ALL UI, actual paid recruitment, real two-client interactions/rejoin, native
six-role animation/audio and normal-terrain raid/release remain unapproved.
Installed user profile remains9cac; no user world modified. One authorized reset
already redeemed, heartbeat paused, no second reset authorized.

Coins65600 TERMINAL FAIL at frozen sneak tick witness; outside merchant, walking and four founders passed. No trade/inventory/checkpoint result. Corrected source QA compact Tavern from2 to3 lights to satisfy current production survey; next package then corrected ordinary-click scene. GUI2 supplemental1F8ECD45/research97DAF632 prepared under Temp/ui-rework-20260907/gui2-completion, unrun.

## 2026-09-07 02:29 — 761/764 gameplay checks; three bounded corrections ready

Package77056 PASS457JUnit/108reports/0fail/0error/0skip, artifact20260907T001415.945528607Z-394.WcaPxr.
Candidate87202c199b48c56d4c5e, SHA433B1F5454FE1952095917DC92FE62F2C716795D1AE4F067B9CC0345E8D6C912.
GameTest46757 FAIL3/764 (761passed), artifact20260907T001818.453559717Z-388.dKqnB6.
Earlier partial-ledger, ammunition, fuel, FOOD-refusal, workshop and Hearth-source
checks now pass. No full gameplay/native or release claim.

The three concrete failures and newly integrated corrections, not yet rerun:
- Source-session fixture actual belowAIR/bodyClearfalse is explained by vanilla
  structurePos(0,1,0): templateY0 floor loads at helperY1.4AF0 now prepares only
  two3x3supported/body-clear source/destination contact areas before fixtures.
  600/500ticks and all contact/4unit/reload/component oracles unchanged.
- Farmer harvested all9originals; last unload emitted seven uninterrupted unit
  contacts40ticks apart. Beet9 reached storage at elapsed8752,52after8700bound.
  Root adds bounded300non-MEALticks:7500active plus<=1500observedMEAL, absolute9000.
  Full nine-cell provenance, actual door passage and conservation requirements
  remain unchanged. Final bag lift is not an assertion in this journey test;
  its separate presentation contracts remain authoritative for that claim.
- Unarmored wooden Guard killed4/5 then reached19/64HP, below30%recovery threshold,
  retreated and died.646E increases employed Guardcapacity64->80 only, unchanged
  damage/footing/recovery/woodensword/real5enemies. Old64capacity migration preserves
  actual woundedHP; employment/rehire do not heal. Runtime balance remains open.

Paid-recruitment QA construction E5C9 integrated: observed dry compact8x8Tavern
plus three actual two-bed houses; complete preflight against real village radius,
fluids/protectedentities/actors and clearapproaches before mutation. Only ordinary
local foundations and footprint vegetation clearance; production plaque/bed/food,
samefounders/existingInnkeeper/naturaltraveler/physicalCoinpayment unchanged.
No paid-recruitment runtime evidence yet. Backups root-integration-0230.

Corrected UI scenarios Temp/ui-rework-20260907/batch-comment-fix/after are current:
criticalGUI2 2223562E, GUI3 452F8966; supplementalGUI3 88223154; researchGUI3 B92D7D6E.
Only two illegal in-batch comments removed from each critical scene; executable
lines unchanged. All nine copies structurally checked, not executed. Supplemental
GUI2 has no existing scene; do not claim that coverage is prepared or verified.

Next serial queue: package, GameTests; fresh fixed-natural-site35093226 first
earnedCoin/storage and SAMEcandidate F3F7 paidrecruitment continuation; then
critical/supplemental/researchUI and realtwo-clientCoins/rejoin. Installeduser9cac
unchanged; native six-role motion/audio, normalterrainraid and release gates open.

## 2026-09-07 02:14 — delivery corrections integrated; exact merchant diagnostic; package next

Package98673 PASS, artifact20260906T233554.071245877Z-394.NIhq8k.
Candidate218c747a84215d365962, JAR SHA
beb7e78659f142272f6f5a04f5b45348d99ccdbee0647eef42cea826ed547478.
GameTest68318 FAIL12/763 (751 passed), artifact20260906T234010.175218239Z-1307.NrFxDd.
Real partial unit deliveries falsely returned TARGET_FULL after a successful debit.
Fuel also returned remaining reserved fuel before the live firebox deficit was filled.
Two ammunition and one FOOD fixture still assumed instantaneous/bulk contact.
Farmer nine-crop trace observed1500 actual MEAL ticks, no eating/resting, seven crops;
the earlier repeated full-storage trace now passed. The all-nine result remains open.

Integrated now, NOT YET EXECUTED: root RequestLedgerService healthy IN_TRANSIT return;
D63 fuel deficit correction;18DC actual-tick fixture migrations;5AE bounded7200 non-MEAL
ticks plus at most1500 observed MEAL ticks; CEC exact Coins quote/guide; A98D English
research display names; corrected Hearth source4EFE (one actual unit/contact48,
same floor sack, final80, clean reload), plus one GameTest. Required roster764.
Root source-contact fixture now uses a cardinal cell and early diagnostic witness.
The original template ALREADY HAS a complete stone floor and vanilla spawn was
already centered: neither missing floor nor a stale RequestRecord explains its old
NO_PATH failure. Exact anchor rejection remains to be determined by fresh runtime.
Co-op only contact deadline changes180 to414 seconds: sixteen four-log trips,
source28->200 and destination80->200 ticks, preserving prior navigation slack.
64 logs,50/100 residents, two-client Coins race and reconnect requirements unchanged.
Backups: Temp/hearthstead-feedback-20260906/root-integration-0208 and0215.

Coins3654 FAIL preflight because of an inline comment inside a command batch;
corrected E545 scene58858 reached real Hearth(4,89,38), then FAILed to observe an
outside merchant within120seconds. Cause not recorded; do not assert universal failure.
Diagnostic93463 PASS CAPTURE ONLY, artifactplaytest/20260906T235849.383572086Z-643.uubNQX.
Immutable checkpoint manifest SHA5A3D2AA8CF5B5AD9B690DFF4EF4EE88F4B7BF24683E23AB0EF8EF8BE23186231.
Same candidate/seed but different actual siteHearth(2,88,25). Living Mayor+three
founders verified. Merchant publishedtick1200, physically outside initially and
walked to target(2,88,23) by30sec, then ordinary wander. No Coins earned in diagnostic.
Cold income scenario35093226 pins only player QA placement at this observed natural
site. Real8Basiclogs->1Coin->physicalHearthstorage remains unexecuted.
Paid continuation F3F7 uses saved coordinates, but dry terrain inspection found its
four construction plots unsuitable here; builder prepares a bounded fixture solution.

Root serial queue next: package, GameTests, fixed-site actual first income; preserve
same candidate through actual paid fifth-resident continuation when plots ready.
ALLUI GUI2/3/Research, true two-client co-op, six-role native motion/audio and natural
raid remain open. Installed user9cac unchanged. No native/release completion claim.
One authorized reset already redeemed; monitor paused, no second reset authorized.
All agents Temp-only throughout runtime freeze.

## 2026-09-07 01:34 — current Coins candidate built; normal-terrain fixture correction; source sack integration

Package41216 PASS: candidate3c99a781fbbf3695a436,
SHA C935FF91D251C80E809EC3203D138C624C73CEA61675AA8F3C623FBB95D50533.
108 JUnit XML suites contain457 tests,0failures,0errors,0skips.
Artifact20260906T232034.924017642Z-392.Cw8QTM. Prior58451 failed a QA-only
private quote.matches call; root replaced it with the equivalent public
traveler/settlement UUID equality checks before this successful build.

Coins playtest87229 TERMINAL FAIL before gameplay at natural placement
predicate HSQA_EC_PLACEABLE_GROUND. Artifact playtest/20260906T232811.757262608Z-645.fCftXM,
wrapper20260906T232755.945852230Z-389.vRO3Xw. Exact candidate startup and
GUI3 profile passed. Target was offset after height projection, producing
wrong slope height. No merchant, Coins, UI trade or checkpoint claim.
Tester prepares bounded actual natural-site selection, without terrain flattening.

Root read all four earlier70148 UI images. Mayor, Requests AND Journey
fallback panels are now centered in source; new runtime images still required.

Now integrated, not yet built/tested: typed source sack session F65798BF
(six files,+1GameTest), accompanying ABCEABAD two old FOOD fixture migrations,
and Farmer89E14D diagnostic-only needs observer. Reviewed exact preimages and
postimages backed up under Temp/hearthstead-feedback-20260906/root-integration-0134.
Source sack requires real individual unit48 contacts, fixed floor anchor,
reload ownership and final80 lift; source-specific actual rendered motion is
still UNTESTED. Earlier typed ledger13E1 adds oneGameTest/twoJUnit; current
expected roster762 before the pending destination regression test.

Farmer last authoritative runtime remains78004 FAIL2/760:repeated full-storage trace
failure plus9crop deadline. Done guard already built in3c99 but not rerun yet.
Diagnostic source ties old1519tick gap closely to ordinary1500tick midday phase;
this is an inference, not confirmed runtime attribution. All9/7200 retained.

Queue: finish bounded destination unit/contact patch, package, fresh GameTests,
corrected first-earned-Coins scenario and SAME candidate paid fifth-resident
continuation, actual GUI2/3 and real two-client co-op. No paid-recruit checkpoint
exists. Native role/raid/service motion, audio and release evidence remain open.
Installed user9cac unchanged. One authorized reset redeemed; monitor paused;
no second reset authorized. Agents are Temp-only; root serial QA authority.

## 2026-09-07 01:17 — 758/760 GameTests; first current UI frames; next integration

Latest package69392 PASS: candidate462326927e8d1c459ad9,
SHA02df3094252da46453ab5213d94721a33af8ea22258abeee795f03c544f3afaa,
455JUnit0fail0error0skip; artifact20260906T225813.728246391Z-388.ivOuL8.
Earlier94300 packagefailed1/455 only because two guide strings were mojibake;
source strings corrected, assertions retained, failedartifact20260906T225432.456817653Z-665.zDnu2a.

GameTest78004 TERMINAL FAIL2/760 (758passed),
artifact20260906T230123.552272020Z-1213.zzZnO2.
Both Courier regressions/economy migration/owned-crop-edge reload/merchant
exact-Hearth binding pass. RemainingFarmer9crop7200 ended7uniqueharvests,
route=none; actualharvestticks23379,24038,25218,25904,28291,28937,30141.
This is continued progress, not proof of starvation; reason incomplete remains
under investigation. Full-storage fixture caught repeatedfailuretrace.
Root source3B986 adds done guard against Mob's residual running-goal tick,
same640deadline/full47hold/displacement/conservation. Root ninecrop change
ONLY adds actualphase/bag/remainingcrop observer;7200/full9 unchanged.

ControlledGUI3 playtest70148 FAILat68Guardentityray, artifact
qa/reports/artifacts/playtest/20260906T230550.068881827Z-647.wnEnDD.
Fresh exactJAR frames: HearthHome,Mayor,Requests,Journey. Root visually read
first3; Mayor/Requests hadright-aligned411wideoverlap fallback, nowcentered
by32452two-line sourcefix. Journey frame captured, notyetvisuallyread.
NPCteleportedserverbutclientlerp didnotadvanceunderfrozenworld; corrected
Temp/capture-tracker-fix/after scenes use explicitQAactorsNoAI+10tickstep.
Exactentityrayrequirement unchanged. No Windowsnative or overallUIapproval.

Integrated afterthese runs, NOTYETBUILT/TESTED:
- Farmerdone3B986 + ninecropfailureobserver;
- Hearthmodals32452;
- typedpartialCourierpickup13E1: actualsource+bag count receipts, v2stricttrace,
  oldv1preserved, finite160traceedges, APIpickupOne optimisticmovedcount;
  +1GameTest/+2JUnit, required761. Source session/animation/AI wiring still
  being prepared by profileagent, NOTpartofcurrentbehavior.
- EarlyCoinsRecruitQa6DDD + separatecommand6920: explicitassistednormal-world
  construction ofsame4founders/Tavern/6beds/Innkeeper/food; noCoinsortraveler
  grants. Read-only exactquote/payment4to5receiptwitness aroundrealUIpurchase.
  Actualearning+paidrecruitcheckpointancestrymustuseTHISsamecandidate.

Coop targetedcontrollercontract passed12checks after concrete exactallowlist
fixes forHSQA_COOP_COINS and preexistingnormalworld/seedknobs. Actualcoopharness
69CF+founder5467 remainsUNRUN.
Rootnextqueue: package, normal-worldfirstearnedCoin+samecandidatepaidrecruit,
correctedGUIcritical+supplemental, remainingFarmer and newledgerGameTests,
real2clientco-op andnormalraid/Innkeeperservice/nativevideo. Canonicalserial
queue, agentsTemp-only. Installeduser9cac unchanged. Nosecondreset.


## 7 September 00:54 — six remaining regressions corrected in source; next package and 760 GameTests

Latest executed package91724 PASS: candidate65ef41e0a7d9294a92f3,
SHA975bc30fe796570bad594cd0a22f5cbfbf29b9db0e93276aa594a2e4e0d88692;
455JUnit0fail0error0skip. Artifact20260906T222334.223369535Z-392.Cwz7vS.
GameTest92547 crashed on BedMarkers broadcast to an unnegotiated mock player;
production network channel guard then integrated.
GameTest97291 TERMINAL FAIL6/759 (753passed), artifact
20260906T223402.155426327Z-389.h0Ry7B. New Coins treasury, goods quality,
manual merchant, paid visitor service and bed occupancy tests passed.
Both real wooden-sword Guard versus five full zombies tests passed; Lumber
strict contact12 plus physical cargo conservation passed. No native claim.

Integrated after97291, NOT YET BUILT/TESTED:
- Farmer14F489 resumes the retained stationary sack before chest contact.
- Courier773696 publishes actual TARGET_FULL hold and clears after real unit;
  fixture now fills AFTER the real destination session begins, retaining both
  reloads, all12bread conservation, 50tickhold and1600deadline.
- Economy8E2A4 fixes exact frozen Coin quote QA funding and stale doctrine
  whole-inventory conservation; production prices unchanged.
- Root Farmer bounded output drift follows authenticated terminal crop source,
  exact worker/employer/zone/action and original ItemEntity. Block mutation
  remains inside the exact field. Added real field-edge drop + worker reload
  variant with full hand/bag/chest conservation, same1400deadline. Required760.
  Nine-original-crop route remains7200/full9; actual cause still pending rerun.
- UI B28BAC + corrected A8E04 locale integrated; all13 Screen classes now have
  source revisions. Native readability/interaction/motion still UNTESTED.
- Real two-client Coins harness69CF + base founder correction5467 integrated;
  exact one communal payment/stale refusal, physical counts, genuine disconnect
  and same-player rejoin are required. Harness not run. Base50/100 workload
  gates unchanged; exact retained hireable founder fixes old51/101 setup.

Root sole serial QA queue. Agents Temp-only: tester bounded root drift review,
builder early merchant exact-Hearth binding correction and actual survival
earning scenario, profiler co-op readiness complete.
Next: canonical package, full760GT, targeted coop controller contract, actual
GUI2/3, normal-terrain productive village/Tavern/raid, real two-client actions.
Installed user candidate9cac8841d83433cbfdd9 remains unchanged and not proof
of these changes. No user world mutations. ONE authorized reset already used;
heartbeat PAUSED, no second redemption. Goal last observed PAUSED.
Physical coin texture verified32x32RGBA, alpha0/255, transparent outer/hole,
existing gold_coin item/model/displayCoins; actual inventory render unverified.


## 7 September 00:21 — package54581 compile failure corrected, not rerun

Artifact20260906T221830.702956512Z-392.9vi5KR: one actual Java compile error,
GoodsQualityGameTests parsed generic Tag through CompoundTag-only API. Root
now asserts real compound encoding then passes the correctly typed saved tag.
No runtime quality assertion removed. New earlyMerchant heightmap fixture also
gets skyAccess=true to avoid the same framework barrier ceiling as terrainGT.
Currency fixture migration still in progress; QA queue free, next package
should include reviewed fixture migration before full759GameTests.

## 7 September 00:18 — physical Coins integrated; regression corrections awaiting execution

Current target Monday7September20:00 Oslo: six roles, all active UI, actual shared
2player actions, full physical Coins early-survival economy. Goal last observed
PAUSED; current turn continues. ONE authorized reset redeemed at97%used with fixed
key93085ce4-fa7b-46d2-b7ef-734b671a2678. Backend outcome reset, refreshed0%used;
heartbeat hearthstead-one-reset-at-3-remaining now PAUSED. No second reset authorized.

Fresh package64951 PASS: candidate361cecca4c03dd9ab2e7,
SHA014FF161F2780DD0E3AE2316C74BCEC4C742441D463B57DA2F63B052CF7A48C6,
445JUnit0fail0error. Artifact20260906T214720.905957628Z-393.FhBNbs.
Gametest52961 TERMINALFAIL14/750 (736passed), artifact
20260906T215230.093512907Z-387.i2mXOY. New Archer ownbag, Guard continuity,
Lumber retired action and Farmer actual field-bag lifecycle tests passed;
whole worker batch is not gameplay-approved. Installed9cac remains unchanged.

Integrated AFTER52961, NOT BUILT/TESTED: CoinpricesDCA49 + root7file exact
payer/network/registry seams; GoodsQuality1726; RaidCoinsA689+38EE;
Bedoccupancy561C0 (protocol16) + actual plaque fixture; Courier typedFOOD139C
unit contact; compact earlymerchant0483 (5newfiles,4GT); paidTavernCE534+F9B874
(6files,1GT). Onlyexisting GOLD_COIN displayCoins. Wallet/Hearth/validWarehouse
actual inventory slots, recruitmentv2 4/6/8Coins with legacy v0/v1 preserved;
manual basic logs/wheat ->Coins, workerquality higher prices, bounded demand;
visitor existingbag3Coins once,1Coin at real food receiving8tick, no beer claim.

Regression source corrections: FarmerA643 adjacent standable pickup/full path
and actual contact-clock fixture corrections; ninecrop7200/full9 unchanged.
Root Guard64HP+65%employment-only knockback resistance (hiring never heals),
actual vanilla knockback witness; five full zombies criteria unchanged.
Root Lumber actual server pickup event start observes exact12tick contact,
not earlier sack placement. NO_BED now requires actual free bed for WAITING
admission; capacity base3 cannot bypass physical beds. Ground terrain GT gets
skyAccess=true65EBC because framework barrierroof polluted actual heightmap;
real flat/water/cliff requirements unchanged. Normal terrain still unrun.

UI integrated/unbuilt: coreF921, Catalogue/zoneC626, Field/ordersE3C,
Study/growth82B + existing layouttest and2English locale sidecars. Builder
continues embeddedMayor/Requests/Journey+Blessing and actual Coins guide.
Native current-candidate before/after/interaction acceptance still required.
RequiredGT counter759 =750+treasury1+quality1+bed1+FOOD1+merchant4+Tavern1.
Currency fixture migration agent is unfinished; old barter expectations may
fail until its reviewed patch lands. Root sole serialQA; agentsTemp-only.

Next: canonical package for integration compile, then finish currency fixture
migration and full759GT; actual normal-world productivevillage/Innkeeper/raid,
2realclient sharedCoins actions and cleanrejoin, continuous native animation
and GUI2/3 read/click before installed-candidate approval. All remaining bag
source filling/legacyCourier/fullco-op/normalworld limitations remain open.
Backups: Temp/hearthstead-feedback-20260906/root-economy-integration-0008,
root-economy-seams-before,root-worker-followup-0010,root-integrated-0016,
ui-rework-20260907/root-integrated-0019. Never overwrite userworldassignments.

## 6 September 23:45 — integrated worker batch, expanded survival scope

Latest explicit deadline remains Monday7September20:00 Oslo. ALL active UI,
real two-player shared actions and functional physical Coins early-survival
loop are now required; old deferrals superseded. See current AGENTS/ROADMAP.
Goal last observed PAUSED; active turn work continues, docs do not resume it.
Authorized one-reset heartbeat is ACTIVE every2minutes at weeklyremaining<=3%,
fixed key93085ce4-fa7b-46d2-b7ef-734b671a2678. Latestusage92%used; no reset redeemed.

Integrated, NOT YET BUILT/EXECUTED: Archer own plain-arrow quiver AD1992 (+1GT),
Guard close-target continuity673904 (+1GT, noHPbuff), Lumber retired stale-action
recovery9E7B6C (+2GT/+3JUnit); Farmer grounded field/chestD2C2D1 (+1GT) +
presentationDA52; Courier distance-based gaitC106512; shared UI/core F9217C.
Source-reviewed proposals; backups in Temp/hearthstead-feedback-20260906/
root-integration-2328 and root-integration-0035. Required normalGT roster750.
Current candidate must be rebuilt. Installed9cac remains unchanged.

Doctor5968 TERMINALFAIL artifact20260906T211349.134593245Z-426.f7nydr:
only fixture-plaque-guard failed; actual normal-world safe-path writer checked.
Targeted71214 identified CivilianAlarm fixture missing physical plaque. Root
added actual supported plaque. Targeted92652 fixture-plaques TERMINALPASS,
97files scanned. This is a narrow pass, not full doctor approval.
Last full gameplay72584 remains7/744FAILED until the new run.

Agents in Temp only: builder remaining UI batch; tester Courier FOOD/custody
migration (shared helper must NOT double-shrink typed RequestLedger delivery);
profile early merchant+quality/demand and startup route. Root must review/rebase
held coin-pricesDCA49/goods-quality1726/raid-coinsA689+38EE and registry/network
seams. Old coin-marketE1DC superseded. Existing actual recruitment still barter.
Bedv2 561C0 and ChairB08 remain unintegrated. Grounded full-village93 built but
not run. Innkeeper scenario0BEB74 prepared, not run; no all-role/native/co-op pass.
## 6 September late evening — current build and owner feedback

BUILD VERIFIED: package1665 PASS, candidate93c01a251257b0c92112,
SHA7769d3596f1b7d75f4732e58538a795e855e0e2c95438d3bbe53ad0eec02f2d0,
442 JUnit0failure0error. Grounded full-village addon builds; actual normal-world
placement, shared production and raid remain UNTESTED. Installed9cac unchanged;
fresh22:54 startup observed before latest feedback, not a motion approval.

OWNER-OBSERVED FAILURES: Guard weak; Archer did not fire despite player-supplied
arrows; Lumberer did not approach/chop; Innkeeper appeared idle. Cause and fix
still under source/log investigation. Owner-observed Farmer production worked,
but Farmer bag put-down/fill FAILED the intended common behavior. All sack jobs
require world-stationary transfer and Courier requires a convincing loaded gait.
This feedback supersedes any implication of current whole-role approval.

PENDING: bedv2 shared persistent occupancy source accepted, not integrated/tested;
Oak Chair NPC source accepted, player use unimplemented. FullGameTest last
FAILED7/744 retained. Monday7September20:00 is the new owner delivery target;
plan status is not gameplay evidence. Theft/peacekeeping is queued design.

## 22:52 personal-test installation VERIFIED

Candidate9cac8841d83433cbfdd9 installed in pinned SIVILASJON(1), SHA7e26caa92b9cfbc6e7e4a41ffd7d278bac14bbccb441a44552ec34f7ffbde678; package442JUnit PASS, installer receipt23948 PASS. User expressly accepts built changes before final gameplay testing. Native startup/motion UNTESTED; last fullGameTests72584 FAILED7/744, subsequent corrections unrerun. FARM_PLANT offline51142 forward direction accepted from originalframes byroot/tester, notallFarmer/nativecontact. Fullgroundedvillage/chair/bedmarkers notyetinstalled.

## 2026-09-06 22:18 — live feedback batch; source and runtime separated

BUILD VERIFIED: package43199 PASS, candidate f7a502fcfaa05134400b; 441 JUnit tests, zero failures/errors. Includes distinct Guard retreat, Archer local aim/fractional draw, physical double-door regression and future-fixture real names. The door GameTest has not run yet.

NEXT SOURCE INTEGRATED, UNTESTED: C068 carry/pickup/heel motion, 4393 shared grounded bag session with activity/contact body ownership, 67B5 single-unit Lumber storage with actual-bag conservation/reload test, and 9FA1 exact waiting traveler seat authority with housing refusal test. Backups retained outside repository. Leaf access clearing and physical door diagnosis are separate bounded proposals in progress.

NATIVE VERIFIED PARTIAL: installed/running48908, Complementary Reimagined r5.9 HIGH with Iris1.8.12 and Sodium0.6.13 rendered in the existing six-role QA world; original screenshot21.51.51 retained. JEI inventory/recipe UI and Xaero visible. User declined restart and is playing; new source is not installed. Actual new motion/audio, door traversal, strong-Guard balance, complete paid guest economy and release gate remain UNTESTED or FAILED as recorded below. Historical entries below are not current-candidate claims.
## 2026-09-06 — owner feedback correction, gameplay failures retained

BUILD VERIFIED: canonical18503 candidate0308470b1cc27e951f40 SHA B204B7D7535BD97A024AF84019932521CFA7124A8910128C5F66D34BD5081AEE;438JUnit,0failures/errors. Not installed. Current user4977 remains unchanged. HEAD95425795c290 dirty working tree preserved.
GAMEPLAY REJECTED: canonical55143 expected737,8required failures in artifacts/20260906T185534.173325942Z-1365.BEBwmr. Actual Mayor roster/founding defects, civilian retreat failure, Archer witness failure and obsolete role/weapon expectations require correction. Root integrated reviewed Mayor5B932 and sheet/wood expectations; isolated Archer/panic fixes pending. GuardBF0AED experiment integrated +2actual simultaneous-five tests, expected739; balance unrun. Build evidence predates these fixes.
VISUAL PARTIAL: animation61723 technicalPASS, original offline FARM_TILL right t0.6 inspected tool-ground contact/forward torso. Native transitions, gait, work motion, combat motion and audio remain unapproved. User authorizes complete audio overhaul; TekTopia reference research does not imply actual listening. No multiplayer/performance/release completion claim.
ECONOMY ACTIVE, NOT DELIVERED: quality-dependent prices and physical coins, meaningful building requirements and paying Tavern visitors authorized. Plain raw-goods merchant proposal held for latest quality direction. Origin-label and export-only proposals superseded by user-requested quality. Deadline21:00 withdrawn; full scope retained.
## 2026-09-06 19:50 — physical resupply and fourth resident VERIFIED

Current4977 exactSHA8dfd73983cd71c5001d1e43356813c04745dab529ba4f12e87242fe4e7bed2ce: resupply99679 PASS7shots at playtest/20260906T173651.553874676Z-648.SBFvHN, checkpoint3BB958E4FD5AE4943745DEB6255C382EC15450C3EF6143E44C7EB89120DABEDD. Actual ordinary inventory/crafting produced4planks from1log, returned7logs, preservedsource2beetroot/playerempty/otherHearthstacks. Independent saved-state review passed.
Admission32886 PASS3shots at playtest/20260906T174315.247524016Z-642.q31xc6, cleanhousing-fourth checkpoint74243C66880FC81501262DBFF15B6255850D9610B06F52C9D1BF44C20100BB64. Actualreceipt19:46:19.150 on originalFreydis traveler94a83612-ee28-4049-ba60-fb777f60ff0a:5bread+10planks, physical570→565bread/12→2oak, population3→4, correctresident/bed104,-59,129, healthyJourney35completedthroughFJ460rev38. All41worldfilehashes independentlyverified. Final transaction resets to next ATTRACTING cycle; originalquote+receipt+physicaldeltas prove payment. Source/fixture ancestry preserved. Initialresources/buildings remainassisted; this is notunassistedfirst-hour/fullraid/nativeanimation/co-op/releaseapproval. FirstWatch25720 remainsRUNNING.
## 2026-09-06 — current4977 native startup and ordinary traveler arrival

VERIFIED current installed candidate4977 SHA8dfd73983cd71c5001d1e43356813c04745dab529ba4f12e87242fe4e7bed2ce started in native Windows and loaded the existing six-role test world. Run `qa/reports/artifacts/release-client/native-ready-4977-20260906-1925`: registered livePID13412, fresh frameACK19:28:01 bound exact installedJAR and world, STATE_LOAD_SUMMARY buildings9/settlers8, original1024x768 screenshot `shots/loaded-current-candidate.png` SHA07008bcb00f592106347c3c2846f463eb6dd146676d9242c94c3bc2993ca612a. Sealed log segment4e90f042dfaaad5e5e773a6a170843a643b38bb0c217002ee2717238e4c3da92. Root observed Hearth and the two already-claimed Blessing items. Saved/quit/exited cleanly before resupply QA. This verifies startup/loading, not full role AI, continuous motion/audio, large-population performance, co-op or release gate.

VERIFIED controlled B1/B2 paid worker chain and conserved nine-beetroot delivery on4977 (B2 final8farm/1Hearth), followed by housing qualification and ordinary traveler arrival. Arrival canonical64058 PASS, artifact `playtest/20260906T165912.996345459Z-662.ow9IQ5`, clean housing-arrived-priced manifest7E02341B1723DD614E23302A8EC2A397AF73796B0D819FB6506E116473277373. Real arrival19:18:24, transaction0c1eb33b-c969-45ce-98e1-1ffbee9ff43c/traveler94a83612-ee28-4049-ba60-fb777f60ff0a. Resources/buildings remain declared fixtures; no unassisted survival-pacing approval.

Actual immutable quote5bread/10planks exceeds8availableplanks; admission preparation correctly rejected insufficient resources. Real warehouse has8logs+2beetroot. First physical resupply run21715 FAILED before moving items: external warehouse-plaque stand aimed into wall144,-59,131, outside reach of chest142,-59,135. This is a fixture geometry defect, preserved at `playtest/20260906T173045.508949702Z-672.mKXmxD`. Reviewed correction changes only two approach origins to actual clear interior142.5,-59,134.5. Canonical99679 rerun ACTIVE, scenarioC8659DC1EB838F781A77DBE65D035D8782DBB31DB299D2B477477FB739B07077. Crafting and paid admission remain UNTESTED until final runtime receipts/checkpoints pass. No source/JAR modification in this batch.
## B1 paid production PASS; B2 running — current 6 September 18:33 Oslo

Root89718 terminalPASS19screenshots, artifact playtest/20260906T162234.039137542Z-640.nEOd78. Actual paidemblem18:27:36, playerhire18:28:11, WorkZoneconfirm, physical axe/twotrees/replants/output8logs and exactstate18:31:07..08. Resultfinished163127Z with cleanserverstop. Published checkpointphaseb1-complete manifestSHA9D153529E95F0894098D74A92E0B491380331DEA69B13BC6BB4927115DC1581E, candidate4977SHA8dfd73983cd71c5001d1e43356813c04745dab529ba4f12e87242fe4e7bed2ce, cleanStoptrue. Assisted paidproduction, notunassistedfirsthour/nativeaudio.
Root89559 canonicalB2 playtest RUNNING, resumes exactabovecheckpoint/hash/phase, scenarioTemp/hearthstead-b2-batched-20260906/scenario-b2.txt SHAA9BDCD3CEA302732461C425ED5294CEABC94CFDBA1439F4A46ECBB9EBF8C1B59. Samecandidate, no source/nativewriters. Waitterminal before anynewQA; actual next checkpoint b2-complete required beforehousing.
Recruitment fixture proposal994875B3F698BF24BC678074A7C47D5FA183F0D6E796E30AEF89E94232855F8E sourceaccepted afterexactSchema2fix in3binders. Complete TEMPafter/housing andafter/seventh atTemp/hearthstead-native-next-20260906/recruitment-quote-fixture-fix; notexecuted. Introduces actualarrivedWAITING stoppedphase thenbinds real immutablequote, realbread/allslotoakcost/reserves, preservesIDs, nofixed4/8guess/refill.8oakfixturemaylegitimatelyfail10/12premium; actualfurtherresourceacquisitionneeded. Downstreampackagepinsneedreviewedreseal. Builder/testeridle.
Clientcacheproposal25904519A9422848C015773ADA6272B01EA542C73009DFA46D5C33B55B579666 deferred inTemp/client-install-cache. Actualinstallerwork13.7s, notentirestartupminutes; nointegration/toolframeworkdetour whileuserpressesgameplay. ProfileharnessB2commanddocready, agentidle.
UserlatestStatus thenFinishnow/press: rootprioritizes workers/recruitment/firstRaid, no newcosmeticwork. Do not promisefullready/ETA. FullgoalACTIVE; currentturn meaningfulB1evidence +B2dispatch, no blocker audit.
## Metadata render passed — current 6 September 18:20 Oslo

Canonical70120 terminalPASS3screenshots on candidate4977f6e316e025766980 SHA8DFD73983CD71C5001D1E43356813C04745DAB529BA4F12E87242FE4E7BED2CE. Artifact qa/reports/artifacts/playtest/20260906T161438.282844560Z-1515.EkeSZq. Root viewed original shots/direct-card-three-options.png: allthree COMMON/+1RANK complete, no truncation/overlap at1280x720GUI3, card130x108. Rare variant not drawn in this randomizedoffer; native1024 nextcandidate render not yet performed. Directcardstate stillactive/unselected and noSelectbutton.
Canonical82579 install-native-candidate PASS: installed4977 exactSHA8dfd73983cd71c5001d1e43356813c04745dab529ba4f12e87242fe4e7bed2ce,oneactiveJAR, prior4afc backedup. Fresh native startup4977 unverified. No native runtime live. Package74194 PASS435JUnit0fail0err as below.
Native directclick/doubleclick/physicalinventory/save proof is prior4afc, backend unchanged by metadata-only patch; preserve exactcandidate boundaries. Native excerpt available C:/Users/tobia/AppData/Local/Temp/hearthstead-native-next-20260906/native-card-clip/blessing-choice-excerpt.mp4,6seconds original34..40, original180decodedframesmatch, SHA22107397145efd30a9949b164d850d93b80b4761dea4b9fae1e5cad646c289a9. Presentation derivative only; continuous perceptual/audioapproval remainsOPEN.
Next functional lane: Temp/hearthstead-native-next-20260906/next-demo-acceptance.md exact B1 paidLumberer controlledleg8c137ffbe5b775bd11c67f8580c7f81fdd9cb37c9bb4e74eb79671ca1cd588f4. Current sixrolecheckpoint seeded, not naturalpaidchain. Before subsequentrecruitment, demo_builder preparing Temp recruitment-quote-fixture-fix/: oldfixed4bread8planksassertionsinvalid. Quote absent at QUALIFYING so need truthful WAITING_ARRIVAL stoppedcheckpoint thenbind actual quote beforeRecruit; no guessingstock/refills/productpricingchanges. Builder TEMPSONLY, no otherwriters/runtime. profile_harness reportdone; testeravailable independentreview.
## Native Blessing double-click verified — current 6 September 18:11 Oslo

Native16676 HWND22611186 cleanly saved/quit; PID absent before metadata patch integration. No native runtime currently live.
Actual installed4afc SHA4712a596313ee1e53c5814bcee0d9a6b1e6870d0ed27de012e91fbac1cd312f9. Run qa/reports/artifacts/release-client/native-card-20260906-1805.
Arm17038 PASS session hs_native_card_20260906_1805; registration75582 PASS nonce hs_native_card_launch_20260906_1805. Entry snapshot22428 PASS within110s marker age, correct QA world/runtime1024x768GUI3.
click-current right actually opened Blessing without camera jump. Native count2 click18:06:55.401..479 yielded exactlyone offer1 COMMITTED18:06:55.451 rev2→3 spent0→1 warden_oath_seal0→1 conserved mainhand. Snapshot69766 PASS nextoffer pending selected:none waiting:false activecards, rewardNONE. Original shots/next-offer-unselected.png SHA62fb1879bcc37a3c0d45a66b359fb3d3377052585738713e9ef8c37affcbbd5c.
Separate intentional count1 click18:08:32.981 committedoffer2 rev3→4 spent1→2 hearthward_seal0→1 conserved offhand. Actual inventory seen one Warden hotbar4 and one Hearthward offhand, no other seals. shots/two-intentional-claims-inventory.png SHA6a2efe86f8924795edee4ef07cd0dfa34b698541116fa25712841983b53139ff.
AV19893 PASS40s1196frames30fps capture only, av/blessing-double-click/video.mp4 and audio.wav. Tester originalframes35.5s selected-only/item and38.5s nextoffer. Continuous perceived motion and listened audio still unapproved. Captured interaction is QA-earned two rewards, not naturalraid proof.
seal-log PASS segmentSHAfc468092d9f8a6cce0eaa6e22c8478b2a11049d2def4c73b00e9c8efd7b5151b;2ACK694authoritylines. No external operatorseal/precommit, not full release approval.
Observed genuine UI defect: common rank caption truncated. Reviewed BEC971 metadata-fit patch integrated2modulefiles after hashes/native absent: rarity and rank separate cached lines. No geometry/input change. Backups Temp/hearthstead-native-next-20260906/card-metadata-fit/before.
Canonical74194 package RUNNING. Root singlequeue/source freeze; agents idle. Next terminalpackage then existing direct-card controlled scenario with fresh screenshot confirming full common+rare labels. Installed4afc now previouscandidate; don't claim newsource installed. Preserve historical evidence.
Derived native QA save now has2spent andtwo physicalseals; don't reuse as unclaimed fixture without explicit new QA preparation. Original sixrolecheckpoint remains pristine.
## 2026-09-06 native camera and click acceptance

- VERIFIED native camera recovery on installed4afc: exact-runtime ACKs show yaw7105.352/pitch90 to yaw7008.002/pitch37.499992 at unchanged player position and Hearth hitBlock520,80,515 after bounded relative look. Initial post-load move was ignored; repeat input worked.
- FAILED test-tool coordinate interaction: absolute move preceding right-click altered grabbed camera aim; no Blessing native claim occurred.
- VERIFIED logic only: explicit click-current patch31E958 retains bound identity, foreground checks and strict transcript shape. Canonical14229 PASS20/20 Windows contracts. Canonical62182 PASS native-input-evidence isolated import, cross-runtime paths and evidence validator contracts. Actual no-move interaction still UNTESTED.
- FAILED seal-log evidence: first frame request was546s after activation marker creation, outside permitted age. Preserved original unsealed-focus-latest.log SHA BDE68553BA09559954DC4F57264675B4F74B4A9B2E87CFE9ED501CB105D939D9 in native-six-focus-20260906-1750/logs. Not release evidence. Next startup needs immediate world-entry snapshot.
- Native PID33160 cleanly quit, derived QA world saved aiming at Hearth; original checkpoint untouched. Native rapid double-click, physical seal/slot, continuous spin/flight and audio remain OPEN.
## 6 September — native reload verified, input recovery pending

Exact4afc installed via native-install21523 PASS. Actual authenticated Windows
Minecraft loaded the isolated derived six-role world; native observer ACK matches
SHA4712a596313ee1e53c5814bcee0d9a6b1e6870d0ed27de012e91fbac1cd312f9,
1024x768 GUI3 en_us and expected world. Original checkpoint unchanged. Derived
QA copy has explicit native player UUID/host/name/commands transforms,39files.
Native run: release-client/native-six-4afc-20260906-1728. One exact frame sealed,
log SHA3ed4b5da4e630c3673b14489f88acb903118bbde8863a844b6444915a70924f8.
Keyboard hotbar switch works; repeated relative camera input returned COMPLETED
without corresponding observed F3 direction change. No native Blessing claim,
rapid-doubleclick, satisfying motion/audio or release acceptance was established.
RawInput OFF experiment did not recover movement; original true restored after
clean save/quit. Driver's redundant focus acquisition is a source-supported
hypothesis. Integrated FDE899 minimal full-validation foreground fast path, and
three regression cases. Canonical native-input selector now runs actual Windows
Python -3 -I. First Linux attempt72395 exposed two OS-assumption failures; corrected
runner88342 PASS18/18, no expectation weakening. Native recovery remains unproven.

## 6 September — six-role service PASS on4afc, native still open

Package17580 and playtest51398 terminal PASS. Candidate4afc707fc37ae2e4249f,
SHA4712A596313EE1E53C5814BCEE0D9A6B1E6870D0ED27DE012E91FBAC1CD312F9.
Artifact20260906T151538.438511799Z-650.72mEOF. Actual eight residents, all six
professions and physical surveyed workplaces pass. Both patrons seated and ate;
age1080 all service phases complete, bread0glass1, no loose items, claims cleared,
no remaining host session. Independent tester agrees. Clean save/stop and published
checkpoint six-role-native-prepared manifest2f87260a69d2e8600a7e732bb6dae7ae3d4b3e8215da6c690ba9c35718604d0e.
Only readonly diagnostics changed since149a. Prior slow service did not recur;
its cause remains unresolved. Age800 showed both patrons seated, clear aisles and
authorized active host. Current wave=false, no native motion/audio approval.
Root inspected real after-service screenshot: original chairs/tables and host in
physical Tavern, but guests already standing; still does not prove seat motion.
World is explicitly seeded QA, not earned survival progression. Native copy plan
checks authenticated player identity and frozen state before using a separate save.

## 6 September — six-role service investigation on149a

Package92698 PASS149a41e4151ab5631182,
SHA95C80B21B62C8C4FF23BB76AD8D8DEB295AA12D53D3BD14812F99B7AC29AA9F8.
Playtest35426 FAIL180s service deadline, artifact20260906T145523.054427903Z-646.fGU3sT.
Actual same-settlement six-role preparation and physical surveyed workplaces pass.
First patron completes service through RETURNING, with one bread consumed and
one bottle returned. Second patron eventually finishes eating in cleanup-era log,
but bottle recovery has only reached PICKING_UP before shutdown. The observer's
food/glass ownership invariant holds at observed ticks; full service/checkpoint
does not pass. Investigate latency using bounded read-only actor/seat diagnostics;
do not extend the deadline or infer a product fix without cause evidence.
Previous59729 failed fixture assertion requiring32arrows in16capacity quiver;
root corrected to actual capacity constant. Initial morning1000 also prevented
Tavern visits; reviewed scene now uses6000meal phase and restores daylight later.

## 6 September — direct card interaction verified on95fac

Package82673 PASS435 units/0failures/0errors; candidate95facdaa083a0f664e20,
SHA B205FA0E033A909C7411F55C3CDC781228056848374F9FBBF8822E2B60F5638A.
Playtest58976 PASS6screenshots, artifact20260906T143450.275570850Z-650.exq0QS.
Whole-card click claims one actual mainhand seal; next offer is unselected and
requires fresh input; second click produces exactly two total seals. Mandatory
Escape retention passes. Root inspected actual nextoffer and chosen-only frame.
No separate Select/Confirm controls remain. Continuousmotion/native/audio and
actual rapid doubleclick remain open; unit tests cover pending/phase/latch guards.
Previous26296 remains FAIL: two physical clicks were15.511seconds apart, legitimately
consuming two separate offers, contrary to the scenario's timing assumption.
Current package4446 integrates reviewed prepared six-role fixtureC0687231. It fixes
physical Tavern survey/link instead of direct registry insertion. Runtime pending;
seeded QA setup and employment do not prove natural progression or emblem payment.

## 6 September — candidate628b passes730 GameTests

Package29936 PASS candidate628b900382d7ebb47cac,
SHA256D622446AC94F4D6978C627DA9DEB545F9E9057F14347ECA5476D5473093E745D.
GameTest12555 PASS all730 requiredtests, artifact20260906T140701.580194755Z-1260.9FHAXj,
terminal16:09:13. Previous Farmer/Hunter failures didnotrecur; only diagnostics
changed, so this is not a provenbehaviorfix or closureofintermittency.
Food-restart11005 PASS, finished20260906T141220Z; artifact
20260906T141010.321942607Z-708.sKRehs. Exact628b candidate, two distinct JVM boots,
same Courier UUID/request, source0 bag4 Hearth0 -> source0 bag0 Hearth4. Both
clean stops and port release verified. Native motion and abrupt-crash atomicity
are not established by this clean-restart proof.
Doctor44347 failedonlytmux; A436 integrated, but targeted64994 failedearlierINT
case, harnessartifactsPASS. No fulltoolchain or releaseapproval. Optionalfailure
state diagnostic884DEpreparednotlive. Persistentloader72CDEfirstcold/hit pending.
## 6 September — controlled Blessing/recruitment scene61660 PASS

Actual GUI3/1280x720 playtest61660 passed all scenario assertions and12screenshots:
`20260906T133209.494871996Z-644.tbVpoG`, candidate347d. Two actual seal deliveries,
mandatory Escape retention, nextoffer unselected and sameguest WAITING_ADMISSION
status4 passed. Root inspected originals: chosen-only frame genuinely removes
cards; actualRare rim displayed. Gianttooltips obscured cards; CF513 fixes hover
scope and disabledConfirm, package24980 PASS candidate6c72,
SHA256C9121E1B8E2F3495D0587A1507AA241BA5DC3F1884ED478C6ED410517C7B8AD8.
Playtest15767 nowqualifies that polish and captures actualTraveler view. Prior
guestcard image was Settlement overview, and guestworldshot obscured actor behind
wall: neither approves traveler appearance. Developmentclasspath observer has
runtimeJarSha unavailable, so no native/exact-installed-JAR/DCAA replay approval.
No continuousnativeanimation/audio/FPS/releasecompletion. Earlier entries historical.
## 6 September — quality candidate347d: package verified, gameplay running

- Package9619 PASS432 JUnit tests, zero failures/errors/skips. Artifact
  `20260906T130705.740006562Z-393.A4zpFN`; JAR
  `hearthstead-0.2.0-g95425795c290-i347dfe442331ca8763ab.jar`, SHA256
  `397ADB94AD12429B763F636DA46989B0BD17638618D2425DC0C05850F16BBC62`.
- Genuine immutable Common +1 / Rare +2 quality, full-fit rankIII binding,
  legacy Common preservation, quality-aware physical receipts and protocol15
  now compiled and unit-tested. DCAA exact Java guest-pose capture/replay also
  integrated; actual recorded poses and native visual acceptance remain absent.
- GameTest37580 and10628 FAIL2/730. Outside default48 publication, real long-route
  walking and raised Tavern arrival passed37580. Both FirstRaid tests recruited
  in10628 but the expanded Tavern bounds violated the settlement sphere.
  Latest fixture15,0,12 satisfies full 3D bounds without weakening readiness.
- Canonical GameTest17993 running: `20260906T131119.878293383Z-398.MGYKNM`.
  This is the first gameplay verification of quality and latest fixture together.
- No fresh installation, native after image, motion/audio/FPS or release approval.
  Earlier entries below are historical snapshots, not current running sessions.
## 6 September: Housing progression defect repaired; fresh candidate acceptance remains open

## 6 September — current integrated UI, guest and recruitment candidate (not release approved)

- Package40874 VERIFIED: artifact `20260906T123915.776011803Z-382.tWpKjm`; candidate `b8624de8f7eb1dc2192f`, JAR SHA256 `6019EDBE1B79757BB50DF0BE483744334218F3724C53B657C50A7FED9BCF6BD9`. All427 JUnit tests passed, zero failures/errors. Initial package4662 failed a missing ItemStack import; corrected before40874.
- Integrated three standing Blessing cards and mandatory selection, chosen-only receipt, exact stamped-slot wait and54tick spin/flight, plus guest staff/pack projection and deterministic contact helpers. Protocol14,730 required GameTests. Build/unit evidence is not native animation or visual approval.
- Canonical GameTest37580 is running against this unchanged candidate. Earlier diagnostic54307 FAILED6/728 (`20260906T122838.000301991Z-390.3OTgiC`): FirstRaid2, raised arrival, default48, longroute and unpayable guest publication. Three short probes through the actual closed oak doorway all reached their endpoints (4/8/11nodes), disproving an obstructed doorway in that fixture.
- Current reachability change uses a separate vanilla ground navigator only for unpublished origin validation, avoiding road-preference expansion while preserving ordinary traveler RoadNavigation. Existing safety, complete loaded route, bounds and door rules remain. Actual outside publication AND final physical arrival must pass.
- Earlier package50708 passed413 units;62059 failed17/728,28211 and69287 failed5/728. Terrain closure prevents the observed Mayor fall; crop collection fixture now uses existing real pickup contact. Original evidence is preserved.
- Genuine Common1/Rare2 quality remains isolated development, not in candidateb8624. Legacy earned offers and existing seals must retain their exact one-rank meaning. No rare-color completion claim.
- Native baseline Blessing screenshot inspected: `PROJECT_STATE/evidence/2026-09-05-pixel-ui/after-native-08713-blessing-choice.png`. It shows the original three horizontal rows and Later control; new comparable screenshot remains pending. No new install/native motion/audio/release claim.
Housing2 39708 physically PASSED: real traveler arrival, same-transaction recruitment,
exact4bread/8planks payment, four members/claimed beds, clean housing-fourth checkpoint
B117FCF66218A88249CE50E10D27C86741617DADFBD54A6A1F25732608069A48. However the independent
First Watch preflight FAILED: an extra Lodging bed caused transaction_identity_collision
and cleared Journey history. The failed world is preserved; physical recruitment PASS
does not certify progression. Source diagnosis is in the current task checkpoint.

Counterfactual gametest52445 FAIL proved the new regression catches this exact later-time
first-building observation (artifact20260906T112030.067929820Z-392.AbsFfB). The corrected
domain hook retains the original receipt timestamp and strict payload equality while
allowing real housing-capacity reconciliation. Retry45323 PASS all726 required tests,
artifact20260906T112725.759406558Z-386.nDVBrr, summary13:29:54/build2m19s. Prior30932 stopped
before tests at transient Gradle stale-output cleanup; Java compilation passed, no test
verdict was inferred. No manual cleanup or world repair was performed.

English Recruit Traveler copy, cheaper flint Work Scepter, corrected foundation/Threat
guidance and resolved tooltip keys are integrated. Source/resources compile; current
native visuals and ordinary survival crafting remain unverified. New outside-only guest
arrival/bell and immutable aptitude-priced recruitment are still isolated proposals.

Arrival wait helper is integrated and canonical8347 playtest-input PASS:41tests9.440s.
New action-anchored wait captures receipts during command completion and ignores older
history; original future-only600second wait is unchanged. No game clock/rate changes.
Actual saved wall-time improvement requires the next scenario; none claimed yet.

## Historical candidate 5c8a: all725 GameTests and package VERIFIED

Housing stage1 canonical30447 TERMINAL PASS exit0, artifactplaytest/20260906T103619.576425360Z-627.PH5FHW,4shots/cleanstop. Actual Home/Hospitality payment, lodging/Tavern recognition and qualification start12:41:29 passed. Checkpoint39D31973ECB2460C418623758E0A42DF9D8C3EA222C8957C47A36E4999818869 preserves transaction1a5f0b72-44e0-403d-a64a-51933d93ddee; actual read-only binder verifiedprogress2/target741 plus exact survival ancestry and emitted boundarrival scenario3430C3C4....39708 now RUNNING stage2 at playtest/20260906T104528.208384701Z-645.XkDJAL, samecandidate/originalcheckpoint. Ordinary observation, arrival, paid fourthmember admission and bed claim remain unverified. Controlled building/material assistance is explicit; no earned-resource acquisition or native/Tavernservice claim.

12:36 Oslo: B2 canonical55344 TERMINAL PASS exit0, artifactplaytest/20260906T101932.157466130Z-642.m8aAcO. Actual unchanged nine-crop assertion that previously timed out passed12:32:49; exact chain12:33:47 verified farm8/Hearth1/warehouse0/no carried or loose crops.15original shots, clean save/stop and original b2-complete manifest1BF35216CA43AAA82E06568CB7631EE330E9D464F720527088180B0334C7BF9F. This is current-client evidence for the door-route correction, not blanket navigation/native certification. Strict read-only actual SavedData preflight PASS:28canonical ordinary-evidence steps throughFJ380,31evidence/revision31,active/noquarantine,FJ400home next. SavedDataSHA15a0ae0b6b48989d58c78c5ab1083da52e193180b8972d2ea6e6492c5c2b9f05; no world mutation. Housing stage1 session30447 now RUNNING at playtest/20260906T103619.576425360Z-627.PH5FHW, bound to that exact original checkpoint and same5c8a JAR. Housing/qualification/admission/native/raid/release remain open.

Current ledger readability VERIFIED by root and independent tester in original B2 image at12:24:25, playtest/20260906T101932.157466130Z-642.m8aAcO/shots/b2-real-request-ledger.png SHA269941D0DD1779B561F5EBBF21EBA864E8748C03D8DDCBC552A91DB3F3E6C9EB. Bound HearthScreen/current2f8f3aeaJAR/1280x720/GUI3/English/oneRow/loadingfalse. Compared original prior B2: no muddy label shadows, concise Requests:1, neutral readable footer, complete row and Refresh/Close controls. This is visual acceptance, not native motion or entire ledger lifecycle approval. B2 exact world restore,4-log Courier output and dismissal UI have passed; final crop/food chain still running.

12:19 Oslo: canonical75851 B1 TERMINAL PASS exit0, artifactplaytest/20260906T100830.579761416Z-648.AOGaGn. Both tree cycles,8logs conserved,2saplings,exact axe damage246,19original shots and clean save/stop passed. Original checkpoint manifestSHA37F382719C500732104A182368CA48D546E1D4ECEA7170CCA04DAB1F672B5070 binds5c8a/2f8f3aea candidate. B2 session55344 RUNNING at playtest/20260906T101932.157466130Z-642.m8aAcO, resumed from that exact original checkpoint with batched scenarioA9BDCD3C... and unchanged crop/conservation/timing acceptance. Farmer route repair and complete food chain remain unverified until this actual replay passes. No current native/release claim.

Integrated inventory visual VERIFIED by root and independent tester from actual fresh12:14:49 client PNG in playtest/20260906T100830.579761416Z-648.AOGaGn/shots/b1-real-lumberer-inventory.png, SHAABC011080223BB757303805721442CD7464459BC59A7E9DBD17DA600553ED744, exact runtimeJAR2f8f3aea.../1280x720/GUI3/English/SettlerInventoryScreen. Compared original c1d9 B1 same screen: shadow blur removed from dark labels, world nameplate no longer overlays role/portrait, role separated and portrait contained, identity retained in title, full Iron Axe request and8+36slots visible. Actors differ between worlds; no native motion, entire UI redesign or final B1 gameplay verdict implied. Ledger after-image remains pending B2. Independent report Temp/hearthstead-inventory-acceptance-20260906/review.md.

2026-09-06 12:08 Oslo: corrected routing, inventory and ledger readability, and reviewed harness optimizations are packaged. Canonical83647 PASS all725; validate2883 PASS916/916 with0errors/0warnings; package74914 PASS artifact20260906T100032.702309937Z-388.PXV5A2. Actual properties input5c8a979ac05afbadfe91265cc5e3c5c8154fa1d1b9c5a34cbd62b9866df40907 and exact JAR SHA2F8F3AEA851936CA943E78585705D6ACF580AE58DF468C071F24C0E9DBDBB7DB verified. Fresh required B1 session75851 RUNNING, followed only on PASS by B2 bound to its actual same-candidate checkpoint. No current client acceptance, after-images, native or release verdict yet. The added small-arena cargo test also passed old routing: normal return coverage, not a counterfactual reproduction. Actual prior B2 failure and route-switch trace remain the before-fix evidence; unchanged nine-crop B2 acceptance is still required.

## Historical checkpoints

Speed-up delivery: canonical incremental package97684 PASS exit0, artifact20260906T090143.230513103Z-393.EIp1ko. Current JARc1d9f73d73e03dc9454f, SHA6CCEF81C67BE42A41C191F8A375AA7E4B907D18DBA26D9CA9C17E20776217E5D; actual properties/hash verified. Packaging now succeeds without explicit clean or manual cleanup; the historical clean-build failure below remains recorded. Default clean release checks unchanged. Fresh79947 B1 RUNNING at playtest/20260906T090624.623808057Z-638.MpSDlW using reviewed batched scenario8C137F...; no final client verdict yet. B1/B2 setup batches remove140s of configured fixed sleep before fence/transport overhead, not a measured end-to-end gain. No repeated724 server run solely for packaging. Current queue/details in the shortened CURRENT_TASK; historical contents preserved byte-for-byte.

Packaging73907 FAILED after724PASS: quick artifact20260906T085128.735275797Z-386.dD6DmZ, generated raider_captain_marked.png could not be removed by Gradle clean. Assets916/916 and animation contract3knownwarnings passed. No current packaged/installed JAR. Automatic approval review rejected manual single-generated-file cleanup with only blocked-by-policy reason; no cleanup executed. No QA currently running. This is an output-filesystem/packaging blocker, not a regression-test failure. Full ordinary client replay still pending.

Canonical gametest11543 PASS exit0, artifact20260906T084746.211648235Z-391.vJVInf, all724required tests10:50:50. Four worker commit/output events now originate at fresh successful physical-domain mutations, independent of Journey completion/presentation. Re-observation adapters retain validation and progression but cannot emit new worker commits. Both new actual-log regressions passed for COMPLETE and SKIPPED Journey, two distinct tree actions/deposits, full persisted ownership and quantities, later-tick replay/invalid attempts, exact Journey NBT and physical inventory/tool conservation. Independent review68F3820A... and root exact hash/backup/integration completed. New ordinary B1 client replay, native visuals/audio and release remain UNVERIFIED. Canonical quick73907 now packages this tested source; old14c is historical.

Corrected source/trace diagnosis of87318: the second tree physically completed, with four second-site logs collected and hauled, bag4 at tick12404 and bag0 at12484. JourneyState returns ALREADY_COMPLETED after the first completed FJ170/FJ180, but JourneyServerHooks wrongly conditions subsequent domain telemetry on APPLIED/RECORDED_NON_PROGRESS. This suppresses later real tree/output events once tutorial prerequisites actually complete. Initial interpretation of a stopped worker below is superseded. The scenario remains FAILED, with final eight-item conservation and successful checkpoint UNVERIFIED. Production and meaningful regression proposals are isolated under Temp; no live source correction or new runtime verdict yet.

87318 TERMINAL FAIL, playtest/20260906T075032.163072370Z-641.4WoK56: actual initial Journey view, first Mayor appointment through SettlerScreen and paid Lumberer inventory passed. Original images inspected. Axe pickup and first four-log tree passed; second-tree event did not arrive within the unchanged240s bound. No successful checkpoint or B2 continuation on14c. Actual workerTove128187c2-1113-454f-a0c5-ff3f03b3b569/campd5532cb2-e737-44cb-83a7-1a9764181a05/settlementc1860322-d566-4585-b2f2-c2fb097a9041. Read-only trace/source diagnosis pending; no claimed cause or new source repair yet. Current Windows inventory no longer shows prior security dialog but also shows no running Minecraft; no native proof gained.

Canonical gametest85198 PASS exit0, artifact20260906T074658.501345508Z-387.01o7XU, all722requiredtests at09:49:23. The extended actual SettlerNetwork handler test now proves success-only real player/Mayor survival evidence and no invalid/repeated-action progress. Synthetic test setup uses an empty active Journey; no evidence grants/assertion relaxation. Previous processResources failure recovered through canonical quick10716 PASS (artifact20260906T074030.552603205Z-394.hOoROB), assets916/916PASS0warnings, animationPASS3knownwarnings.

Actual packaged candidate14c763086310838810fbdbf7ca6c03a9a28c195a45abc51301bbb3bb26550d46/JAR94AD908F5266AAC5A1E0EE9EC858073A1213333E465FA74D95B36C73009920F7 includes screen/driver/Mayor corrections. Fresh corrected B1 client87318 RUNNING, scenario83895A5880D9D41686A0B7C70D061CC5110FD1EE69E5DF790E7A7774D31D786E; future B2F5E2B5... requires its actual emitted same-source checkpoint. Earlierc411 physical B1/B2 success and failed progression prefix remain historical evidence. Corrected UI imagery, persisted new prefix, native motion/audio and full demo/release remain UNTESTED/open.

Ordinary Mayor appointment discrepancy now corrected in source: SettlerNetworkE58FF72B8EA815D1912C5B70634B86F649228F3029AD8F214AB0A4BE5D31940F records the same success-only Journey hook as HearthNetwork. Existing session/UUID/revision/permission/refusal rules remain. Extended actual-handler regression checks exact player/Mayor survival receipt and no invalid/repeated-action progress. First90622 failed because the pre-existing synthetic settlement defaults to skipped Journey; root initialized only this case with an empty active ledger, no evidence grants or assertion relaxation. Test4ABCD348... is running under23342; production runtime acceptance remains pending. The revised fresh scenarios now include the four omitted ordinary GUI actions and are undergoing independent contextual review.

24406 TERMINAL PASS: playtest/20260906T065840.752312369Z-643.BhOTvw. B2 exact nine crops and real OUTPUT→FOOD movement passed, farm8/Hearth1/no carried or loose beetroot, clean b2-complete checkpoint605FA7FA7F35AE4D659339A9B0D2C700CF9D7599E7B8BADD8DF21C8097DBC592. This controlled gameplay evidence does not prove all Journey requirements: actual prepared Housing preflight FAILED canonical-prefix-through-FJ380 check. Saved-evidence diagnosis is pending; no progression was granted or checkpoint changed.

Client correction now integrated after terminal: SettlerScreenCF92643DB1575773940F2A78F271C358477AA7FA659BE9EFF082079958025221 watches actual synced profession, refreshes cached view and widgets, preserves surviving action focus, adds rendered-state QA fields. Corresponding future B2 assertion requires actual NONE role/no Dismiss/disabled Work Zone. DriverD9F193... independently retains fresh rotation-query proof while removing redundant2s sleep. Both source-reviewed/backed-up/hash-verified; canonical controller test80641 RUNNING, corrected client compilation/runtime still UNTESTED. Current source no longer matches c411; no new packaged/installed/native claim.

09:15 current-candidate UI defect FAILED: original B2 b2-worker-released.png and bound09:06:58 frame still display Lumberer and Dismiss/Edit Work Zone after server dismissal at09:06:54. Actual simulation is running in this interval (unfreeze09:05:30, next freeze09:08:25). SettlerScreen caches profession-dependent text/layout but omits profession from cache invalidation and only rebuilds action widgets on separate snapshot/events; independent metadata ordering can leave stale text and controls. council_ui is preparing a bounded correction and semantic rendered-state oracle in Temp/hearthstead-dismiss-screen-review-20260906. No live edits, no repair PASS yet. B2 actual paid Farmer hire, same founder identity, physical farm-zone selection, harvest, replant and initial stored output have passed; exact nine-crop total and complete Courier food chain remain pending.

09:04 Oslo: fresh B1 session82603 TERMINAL PASS exit0, artifact playtest/20260906T064427.021849524Z-662.NKZW7B. Actual paid Lumberer hiring, physical axe pickup, two trees, eight separate stored-log receipts, two saplings and exact axe damage246 verified; clean b1-complete checkpoint manifest66A091AABC90776E872CFAB93D673828CE20ED66DEA1FFC1138DB5D07494B521. Same-candidate B2 session24406 RUNNING at playtest/20260906T065840.752312369Z-643.BhOTvw, continuing settlement32990a67-c6de-4256-bb24-d2536ccd3927. Courier/Farmer food-chain completion is still UNTESTED on this run. Current build and behavior already passed; the historical pending/failure entries below preserve chronology. Complete demo, native motion/audio and earned raid remain unverified after the09:00 target.

Quick19596 PASS, artifact20260906T063719.039454122Z-387.xtOFZi; exact JAR9B7F818CCF9B28602E6D52615212CECDF1D4656B4A5290CE342BAC7E637B1741/inputc411cece4d5ad69897b89549642320512609a856e5cce8ee2dd7cca758f29e0d. Build/assets916/916PASS; animationPASS with3knownwarnings. Fresh B1playtest82603 is running at playtest/20260906T064427.021849524Z-662.NKZW7B; fresh exact-JAR server/client/player identity passed and setup is progressing. Final physical production/checkpoint pending; B2chain/earnedraid/native/release remain open. Older92ff source-chest failure below is historical and motivates the current fresh replay.

08:36 current SOURCE behavior VERIFIED: canonical34571 PASS, artifact20260906T063237.833638454Z-384.ZQunGt, all722requiredtests and3447samples/233settlers/1280diagnostics/0findings. The real upstream test failed baseline80647 on arrival, then passed unchanged with grounded-wading SettlerEntityC293E9F58F48E45CF64D635081DEB27481F5C402A1D5FF823BFF74BC3E70E964. Existing underfloor-start and first-visibility regressions also pass. Courier and both bounded navigation corrections are now behavior-tested; packaging/newplayerjourney/native/fullrelease still pending. Earlier92ff/B2failure remains historical evidence, not certification of the changed source.

08:10 bounded navigation repair VERIFIED by actual regression: baseline gametest4035 failed exactly the new physical dry-centre/wet-AABB test at y=-60 vs expected-59 BLOCKED start. After RoadNodeEvaluatorD14A78AE155DB2787BAC1AC72F80AD65AAD89DCBB6294A57C8703530768C5FD2 integration, behavior7261 passed that unchanged test including actual arrival. Its sole failure was separate raid-fixture cycle0 traveler absence (artifact20260906T060643.148823260Z-387.AkqkEc/behavior-failures.txt). Overall behavior remains FAILED pending guest-lifecycle diagnosis; no new release package or native claim. Baseline/correction preserved under C:/Users/tobia/AppData/Local/Temp/hearthstead-water-start-{regression,fix}-20260906.

07:59 diagnostic behavior27923 PASS: artifact20260906T055613.583084295Z-395.ODwdQw, all720 required tests passed, analyzer3396samples225settlers1274diagnostics0findings. Actual additional actor-fluid observations establish grounded peripheral-water contact with dry center for Guard Eira, but this run did not reproduce the invalid floor-start path. The earlier61564 failure remains an intermittent defect under focused reproduction; a single later pass is not evidence of repair. Only optional QA diagnostics were added, no movement or analyzer relaxation. Candidate packaging is pending the bounded navigation regression/repair decision.

07:40 verification update: both new Courier regression tests and all720 required tests passed twice. First93081 canonical result failed because expected count was stale718; corrected to720. Second61564, artifact20260906T053736.792917305Z-387.BxC5gV, passed all720 at07:40:18 but FAILED behavior analysis with one stationary-navigation finding for a different Guard, Jorund UUID87eb53c2-b97c-4275-a02a-a71f566b20c0 ticks33504..33724. That Guard originated as an admitted traveler and was hired at33125. Investigation must establish actual fixture lifecycle or product cause before acceptance; no analyzer rule weakened and no suite rerun blindly. Courier bounded-loss/transient-recovery regression behavior is proven; overall behavior and renewed B2 chain remain open.

Follow-up at07:33: source repair integrated, runtime acceptance pending. Repeated pre-grip contact loss now charges the same TO_SOURCE path budget before retry; existing32/160 limits, NO_PATH reporting, ledger blocking and rest remain. Two added tests exercise finite repeated-loss recovery and actual pickup/ordinary four-bread delivery after one transient loss. Canonical behavior93081 RUNNING, artifact `qa/reports/artifacts/20260906T053315.541165117Z-410.5j6tcH`, expected720. Original files preserved in Temp/hearthstead-b2-courier-stall-20260906/original; patch017165384E291619FE6A1C017703FF2BC2F531144FB9B45A5EC0D50203EE30E5. Prior92ff evidence cannot certify this changed source; no new candidate packaged. Physical frozen-Farmer obstruction is a separate fixture concern, not solved merely by bounded retry.

Canonical50250 terminal exit1 at07:20:50, artifact `qa/reports/artifacts/playtest/20260906T050115.833669186Z-646.aidxc6`. Paid Farmer hire, physical work zone, hoe pickup, harvest/replant and nine stored beetroot passed. Courier reserved an OUTPUT_COLLECTION request but did not pick up/deliver within120 seconds; no final B2 checkpoint. Trace shows repeated lift/contact retries near the source chest with the paused Farmer immediately ahead. Exact cause and responsible product/fixture layer are under diagnosis; Tavern preemption is not established. No timeout/conservation requirement has been relaxed. B1 remains verified, Housing and later progression remain unrun. Earlier current-B2 running notes below are superseded.

## Current candidate92ff: Lumberer VERIFIED, earlier B2 running snapshot

Canonical B1 session46498 passed with clean exit0 and14 screenshots, artifact `qa/reports/artifacts/playtest/20260906T044639.965699791Z-646.L8kWDM`. Paid employment, physical axe pickup, two tree cycles, two replanted saplings, eight deposited logs and tool wear passed. The current candidate earned checkpoint `b1-complete`, manifest `DD32DB65BCD1EC94395E834FEFC00FEA66C87DC102D6518C713561A921FC6F98`. Candidate JAR SHA `FACA13BD5D4962C672115B3A8E869858E798B1CF122C4F82043C87E5011849E2`; source fingerprint `0907bd95bb1a64f17d3a47d60ed5bc861887728d0db04c51ce65587bef0e17c0`.

Session50250 is continuing from this exact checkpoint, artifact `qa/reports/artifacts/playtest/20260906T050115.833669186Z-646.aidxc6`. Paid Courier/Farmer transport and production acceptance is pending. Earlier cbec results remain historical. Native motion/audio, complete six-role progression, earned raid and release remain open. Source freeze remains active.

## Two UI readability fixes VERIFIED in actual headless client

Fresh92ff run46498 original1280x720GUI3images reviewed byroot: locked professions now showthecompleteactionableHearthline, portrait-onlyname/statuslabelremovedwhilemainscreenidentityretained. Exactbefore/afteroriginals+provenance inPROJECT_STATE/evidence/2026-09-06-ui-readability; beforecbec/after92ffdifferentfreshresidents areexplicit, no texturecomparison. Realpaidpurchase/physicalLumbererhirepassed. FullB1stillRUNNING, no native/motion/fullredesignclaim. FullUInativecheckremainsopenbehindWindowssecurityprompt.

## Candidate92ff built, installed, native fixture prepared — 6 September

VERIFIED: quick49217 artifact20260906T042948.756100310Z-388.6TcPKt build/assets916/animationPASS(3knownwarnings); behavior85610 all718/0findings. Current candidateSHA FACA13BD5D4962C672115B3A8E869858E798B1CF122C4F82043C87E5011849E2 input92ffca3175a29653d227a5dd74d11729443752392e26e6f5220f7b41fa73c42e. Freshbefore-service playtest6775 PASS2shots, checkpointAA545F45349C3A2D7D93E0A013A311BC389CD02490ED66F4CC90192464601FCD/source0907bd95bb1a64f17d3a47d60ed5bc861887728d0db04c51ce65587bef0e17c0/actual untouched2bread1glass/cleanstop. Nativeinstall18927 PASS witholdbackup2d45c1..., exactsameSHAatprofile/mods/hearthstead0.2.0.jar. Independentlyverified installedhash/noWindowsJava. DerivedNEWnativeworld Hearthstead Tavern QA92ff commands contains33verifiedfiles, onlyallowCommands changed, receipt21e2e88ce0c6ad28b1c547833cb86817b478f9dec2135180af80f008e7ed27be, originalcheckpointunchanged.

Native visual/motion/audio remains UNTESTED: freshCurseForge screenshot stillshowsWindowsSecurity/OpenJDKfirewallprompt. NoUIinput/securityactionperformed; no nativegame launched. ComputerUsepolicy requiresmanualhandling. Overallgoalcontinues withfreshB1playtest46498/current92ff andpaidchain/UIafter; nofullreleaseapproval. BeforeUIoriginals copiedhashidentically toPROJECT_STATE/evidence/2026-09-06-ui-readability/before-icbec, actualafterpending. Fullsixrole/raid/native/audio/fullx2/gate remainopen.

## Tavern meal-choice behavior VERIFIED — quick build preflight

Canonical85610 TERMINAL PASS exit0, artifact20260906T042544.678520057Z-418.wuK0jl/behavior.log. All718 required GameTests passed06:29:00; analysis3404samples227settlers1311diagnostics0findings. This verifies stocked-Hearth ordinary staffed Tavern choice, real approach/seating/service/meal/glass conservation plus unavailable/critical/ownedmeal fallback on current source. Prior failures resolved by supported first-tick fixture gravity, no production navigation relaxation and no weakened assertions; diagnostic removed. Next sole canonical quick packages current exact candidate and checks assets/animation. After that fresh tavern-before-service checkpoint/native motion/audio and UI checks; historical cbec checkpoints cannot be relabeled. Source/assets/QA freeze continues during next suite. No full/native/release approval implied.

## Tavern preference new regression still FAILED — 06:08 Oslo

Canonical39619 stopped at test-only unsupportedAABB(BlockPos,BlockPos) compile error; root retained bounds/assertions and converted both corners toVec3. Retry43767 compiled and executed718tests, artifact20260906T040442.701196258Z-388.xjIl6I. Exactlyonefailure: stockedHearth positive withdrewHearthfood beforeTavern; other717passed including missing/empty/sealed/full/critical/ownedmeal fallbackcases. OverallFAILED. New preference implementation is not approved; council diagnoses actualoffer predicate/goal scheduling before any nextnewJAR or nativeclaim. Previous cbec worker chain remains historicalverified; currentchangedsource requires renewedcandidateevidence.

## Paid B2 chain VERIFIED; next UI/Tavern candidate testing — 6 September

49758 PASS exit0, artifact20260906T033750.843758884Z-648.qhjxse. Paid Courier/Farmer employment, real workarea, wood collection,9actualFarmhousebeetroots,1Courier→Hearth food delivery, exactfinal9 acrossfixedstores with emptybags/noextras, normal save/stop verified on cbec SHA30613... . Clean b2-complete manifest2F6BBCE3FCED45ABF1991207C057D543918EA6EB7446C52BD24520155C505CA0. Explicitfixture buildings/materials/crops; no natural gathering/native/fullsixrole claim.

Root integrated two observed UI fixes (complete locked-job line, portrait-only nameplate suppression with finally restoration) plus stocked-Hearth Tavern preference. Six production files preserve Eat4/Tavern5 priorities; bounded transient offer checks staffed host, real food+glass source, free chair and detached reach probes before visit-owned reservation. Critical hunger/existingmeal fallback and existing mounted safety retained. Initial unavailable service yields Hearth; later obstruction remains bounded normal visit/safeexit. Exactpackages/backups in CURRENT_TASK. Seven independent actual-AI GameTests added, required roster718. Canonical behavior39619 now RUNNING; compilation/runtime/motion/audio/newJAR acceptance UNTESTED until results. Prior711/quick/native candidate evidence does not certify these edits.

## B1 checkpoint VERIFIED; B2 resume predicate corrected — 6 September

Controlled B1 playtest24052 PASS, artifact20260906T031429.128152418Z-650.wgqmgQ, clean b1-complete checkpoint manifest77FF8E0958DDCB07FB87ADBE650F1DBD618FB29CAD69AFED23C1FE5636F4FC4F. Paid Lumberer, actual workzone, two tree commits, eight physical chest logs, two replanted saplings and axe damage246 verified on cbec JAR30613CEDF2FF424675574EF27991F2F94231E37058693F40BB86AB01A494C3F9. Not natural gathering or native motion/audio approval.

B2v4 resume62170 FAIL, artifact20260906T033221.202887418Z-646.3hV24n: exact world restore and fresh runtime passed, actual chest8/single stack/axe246 remained correct. Initial predicate incorrectly required serialized NoAI:0b, omitted by vanilla Mob.java437–439. Root corrected only that read predicate to resident existence plus absence of NoAI:true; no product code or conservation requirement changed. Temp B2v5 scenario368CF96540C4AD13C0A06DE4AA6A30ED2254A0073A7C99F4300DB107DD37394A is executing in canonical49758, artifact20260906T033750.843758884Z-648.qhjxse. Completion UNTESTED pending terminal evidence.

Original B1 UI images reviewed by reference and root: small portrait includes overlapping overhead labels; locked job explanation is ellipsized mid-sentence. Temp-only minimal fix proposal assigned, not integrated. Existing pictures cannot verify axe/carry posture or loaded forward lean because worker is cropped or not equipped. Preserve these as open visual evidence gaps.

## B2 fixture failure confirmed — active B1 checkpoint24052

B2 playtest69098 TERMINAL FAIL exit1, artifact20260906T025537.839988915Z-29000.hTvORF, actual350open_at_hit missed4boundedrays after realCourierpaidemblem. Same cbec SHA30613... runtime proved. Player149.5,-60,133.5, targetNPC149.5,-60,136.5, yaw0pitch20.472055, hitTypeMISS. B1two-cycle production and actualCourierpurchase/provenance passed; Courierhire/delivery/Farmer unproven. Failed run NOT a checkpoint, do not reuse world as successful ancestry.

Reference confirmed concrete vanilla freeze mechanism: NPC teleport lerpTo3 advances in LivingEntity.aiStep, frozen ClientLevel skips nonplayers. Server actualNPCpos was correct; client lerp stays old. Clear apron geometry unchanged; no reach expansion. Preparing Temp/hearthstead-b2-paid-hires-v4-20260906 with bounded >=3tickstep after frozenNPC+Mayor teleports and actual completion, strictsamepaid/physical/conservation retained. Also split continuation to resume b1-complete; root told agent tickstep1 insufficient for3lerpticks. Coop audits sealedWatchArcher/FirstWatch/Housingv4 for same seam, Temp-only corrections, no duplication with referenceB2.

Root created Temp/hearthstead-b1-checkpoint-cbec-20260906/scenario.txt SHA490314389B4FCB27858992BEB7161ED42044497704370942CC9B6652E92D2AA7. Exact originalv3lines1–245 endingexpect_shot lumber-return-two-cycles, allbatchboundaries intact, no originalcheckpoint, appendedfinalcheckpoint b1-complete. This earns/publishes a legitimate successful B1checkpoint to avoid replay on downstream fixture failures; originalfailedB2untouched. Canonical playtest24052 RUNNING samecbec candidate, sourcefreeze active. Poll24052 before any writes/newQA. No native active.

WatchArchersealedcandidate(unrun) C6183CD97C3F76ECAD33F3B512539EEA8277667FFB1BB095C629C83F8A1FBBFA binderACFC311B456220B4491DAB0536BB934A8E1D099211CE6AC9E03522DE047A2E2A README43379F024850CB84BA58BB62B508A278ABBA1B39D830B54A27272AA8BDD09BF5 manifestCB24EC6F363D05468C8FF546EBB2B8B4B2D091C4113A700680EF376E554CB6CC. Root provisional binder/firstsection review only; now wait freeze-seam audit before full acceptance. No UI/productsourcechanges or newGameTests required for fixture fix.
## Tavern after-camera32556 PASS — active B2 paid hires playtest69098

Canonical Tavern playtest32556 TERMINAL PASS exit0, artifact qa/reports/artifacts/playtest/20260906T024905.117221390Z-2138.wlaCM3. Exact cbec candidate SHA30613CEDF2FF424675574EF27991F2F94231E37058693F40BB86AB01A494C3F9,8screenshots. Actual welcome entry+wave,2meals,all9service phases,original1glass returned,stockbread0glass1,actualseatedguest alarm→seated0/alarmCleartrue,finish,clean serverexit0 and checkpoint published. Checkpoint manifestSHA c23d1807700ddf669514fd79aafeb6c78f9f4e7184038bbabf108d3b1ee0d07a phase tavern-complete. Controlled fixture, not paid employment/survival acquisition/native/audio evidence.

Root inspected original three1280x720 after stills, copied byteidentical to PROJECT_STATE/evidence/2026-09-06-tavern/low-side-after-icbec with image-hashes.json. Shins/boots visibly separated ahead of chair; prior merge improved. Reference independently viewed all6before/after originals, confirms silhouette improvement, no definite new outfit/table penetration, occluded hips remain uncertain. Report pending Temp/hearthstead-tavern-after-cbec-20260906/review.md. Still acceptance is not entry/exit motion or native audio approval.

Canonical B2 paid-hires playtest69098 RUNNING, explicit scenario Temp/hearthstead-b2-paid-hires-v3-20260906/scenario-b2.txt verifiedSHA ECDF917A1EC7F6A86EE70006FF5C240E31E7B4AAA0D5DC0999C40CE67BA4C382. Fresh same cbec candidate. Source freeze active, no other QA/native. Poll69098 before source edits/newQA. Coop prepares Temp/hearthstead-watch-archer-continuation-20260906 actual nextarrival→paidArcher→bow/ammo/order after first-watch-armed, sourceonly. Housingv4 sourceacceptedUNRUN awaiting actual B2checkpoint.

Progress visualization updated04:56; same-turn final must emit its existing absolute visualization content reference. No native app actions; old nativePID/session stale.
## quick89075 PASS — active fresh Tavern after-camera playtest32556

Canonical quick89075 TERMINAL PASS exit0, artifact qa/reports/artifacts/20260906T024235.149762240Z-411.fTvTIj. Build/JUnit PASS,916/916assets0errors0warnings,animationPASS3warnings. Exact artifact properties: hearthstead-0.2.0-g95425795c290-icbeca30a9a521f64ff87.jar; inputHash cbeca30a9a521f64ff876049405c2150655c2a581b40149d1a3672d3e187d0c6; JAR SHA25630613CEDF2FF424675574EF27991F2F94231E37058693F40BB86AB01A494C3F9. Native profile still olde46 until controlled install; do not infer current native identity.

Canonical playtest32556 RUNNING with explicit WSL HSQA_PLAYTEST_SCENARIO=/mnt/c/Users/tobia/AppData/Local/Temp/hearthstead-tavern-camera-baseline-20260906/scenario.txt, verifiedSHA124C2712AF3CF2F12BF5B687BF4DFA285028E960DB4C4E7ECB67DFB6491D2B89. Fresh exact candidate, no old checkpoint resume. Root source freeze active; no other QA/native; poll32556 before writes/newQA. Compare original after screenshots once produced against PROJECT_STATE/evidence/2026-09-06-tavern/low-side-before-ie46d. Root reinspected original right-diner-before image: limb visually merges into chair, rejected. Do not assume runtime/visualPASS until exact scene outcomes and originals inspected.

Computer-use skill reread for future native stage; guidance/confirmations read including no Windows security prompt actions. No new native tools initialized or app input this turn. Old nativePID/session remain stale. User09:00 target remains priority.
## behavior16484 PASS711 — active quick89075 packaging corrected seats

Canonical behavior16484 TERMINAL PASS exit0, artifact qa/reports/artifacts/20260906T023840.758908065Z-395.INMveX. Actual All711requiredtests passed at04:41:51Oslo; behavior analysis3217samples210settlers1219diagnostic events0findings. Fixed44tick knees, both-aisle real clearance, planted foot/knee continuity, exact immediate recursive passenger reload, blocked exit/normal exit/owned meal and other seating regressions now pass. This is headless GameTest evidence, not visual/native/release approval.

Canonical quick89075 is RUNNING to clean build/JUnit/assets/animation/package exact current candidate. Source freeze active; no other QA/native, agents no live writes. Poll89075 before source edits/new QA. After quickPASS resolve exact JAR from artifact properties and run fresh same-camera Tavern scenario Temp/hearthstead-tavern-camera-baseline-20260906/scenario.txt SHA124C2712AF3CF2F12BF5B687BF4DFA285028E960DB4C4E7ECB67DFB6491D2B89 via canonical playtest. Do not resume old before checkpoint across fingerprints. Compare original three low-side shots with durable PROJECT_STATE/evidence/2026-09-06-tavern/low-side-before-ie46d. Actual native motion/audio remain outstanding; old nativePID30704 terminated and session expired. Preserve original checkpoint and user worlds.

Housingv4 sourceaccepted as preparation, no actual checkpoint/binding/runtime evidence. B2paidhiresv3 remains acceptedUNRUN. Follow full objective; do not stop at Tavern or shrink demo6role/raid scope.
## behavior70705 terminal FAIL — seating reduced to one reload failure

Artifact qa/reports/artifacts/20260906T023101.646186107Z-392.8jpXft. Actual711 tests, exactly1 required failure: actualentryreloadandnormalexitkeepleasefeetandidentity, assertion reload preserves exact phase clock, deadline, actor and physical location. Build3m17s exit1. Previous four seat failures reduced to this one; actual both-aisle89sample clearance, knee continuity/plant stationarity preceding reload passed, other previous seat failures absent. No visual/native approval. No active QA/native; source freeze released. Council investigating exact vehicle/passenger save/reload source in Temp only. Reference v2 independent review pending. Root must review/integrate any correction and rerun canonical behavior before candidate packaging and fresh comparable Tavern playtest.

Housing v4 sealed UNRUN at Temp/hearthstead-housing-checkpoint-v4-20260906; stage1SHA4FECC8EF3ACA6C0E039E474038D27C56B4FF7B173B7E4514076688B188DC591C, stage2template6FB9EB31AB25F60F462BF96CB1903917B25DB1A61E77F2AFD103D6954641641D, binderC8D82947AA2D276B719F85BEDBB83F3ADD20A0A007E34D65246F2522A1183E83. Root read binder; actual source contract/full scenario review still required. It binds stopped actual pending transaction and stage1 hashed start-event ancestry, cannot claim fresh live numeric progress. No checkpoint yet.
## 2026-09-06 04:13 — recruitment timing and migration VERIFIED in GameTests

behavior31962 artifact qa/reports/artifacts/20260906T020927.239256022Z-391.Np07kG:711tests,37recruitment_policy cases allpassed,4existingseatingfailures keepoverallFAIL. New ordinary10–20qualifiedminutes, unchangedWatch4–8, exactnestedlegacylocks preserved; pre-v7loosedata preservescorrectcycle without inventedclock/Tavernauthority. Nominal fresh3→7 waits34–68qualifiedminutes; actualeconomy/build/travel remains UNTESTED. Root-reviewedpaidB2scenario preparedUNRUN. Currentbuilt68fJAR predatespacing; no installedpacing/afterimageclaim.

## 2026-09-06 03:55 — paid recovery regression VERIFIED; seating FAILED

Canonical behavior31109:710 actual GameTests,4seating failures. Artifact qa/reports/artifacts/20260906T015141.007165766Z-390.YjLpOi. New first_raid_paid_provenance_recovery batch ran1test and passed; validates paid replacement/new physical deposit/NBT/stock conservation via actual server authority helpers, not ordinary player/native/restart proof. Seat initialt7 clearance now passes but t8leftthigh intersects actual stair lower component; both facing fixtures nowvalid. SAT independent review also requires nearparallel normalization fix. All seat visual/native/motion acceptance remains UNTESTED/FAILED as appropriate; no new candidate or after images.

## 2026-09-06 03:45 — seat failure localized, correction pending

FAILED: behavior75926, artifact qa/reports/artifacts/20260906T014147.656277403Z-399.wlmTdM, 709 actual GameTests / four seating failures, compilation succeeded. Strict diagnostic confirms initial valid entry blocked at motion tick7 by left-thigh envelope5 against the actual chair stair. Second test also loses its exact facing during21-tick wait. No collision bypass or test weakening applied. Diagnostic assertion itself exceeded Minecraft's1024-character book/network bound; full detail will move to logging. Pending geometry correction must pass real entry/exit, reload/ownership, meal pause and obstruction/alarm checks before new controlled images. Installed e46 remains the before candidate; no new visual/native acceptance.

## Current seating verdict: FAILED gameplay path validation

Behavior2444 compiled and ran709tests;4 seat-entry/ordinary-visit tests failed. Artifact012826.415474973Z-386.ecN6lD. Do not call the new sit/stand motion working. Strict sampled collision diagnostics are next; previous707/e46 pass applies only to old snap seating. Expected roster updated to709 for two actual additions. No safety/test conditions relaxed.

## 6 September 03:28 — articulated seating integrated, verification in progress

Full baseline Tavern controlled-client camera68553 PASS: qa/reports/artifacts/playtest/20260906T011911.885473819Z-648.JOFkav,8originals, actual meals/glass/alarm and clean published checkpoint0e66c64e... . Root and independent image reviewBC2CB159... reject lower legs merged into stairs. Preserved three original low-side before images and review in PROJECT_STATE/evidence/2026-09-06-tavern/low-side-before-ie46d.

Integrated6-file seatv3 plus one-file reservation snapshot correction, with exact live/proposed hash checks, reviewed patches and original backups. Own28-tick physical entry/exit, readiness-gated service, meal pause, parallel feet/flat boot solver, checked path and20-tick blocked preflight retry. Actual client interpolation and early passenger/metadata packet ordering addressed. Source accepted; geometry naturalness and live packet transitions remain UNTESTED until fresh after capture. Current installed/native-observed e46 is the BEFORE candidate.

Also integrated early scenario preflight and canonical native launch registration. Canonical80987 passed22 checkpoint contracts;47433 passed6 native wrapper contracts plus existing release-session helper. Actual Windows registration/audio remains UNTESTED. Full behavior2444 is RUNNING for compile/current expected709 tests. No build or gameplay completion claim for new source yet.
## 6 September 2026 — actual Tavern baseline and independent seating review

VERIFIED controlled installed-client service on candidate e46d76c5b36fc65f3d8c553d74efcbded2eabd2e93f57d1bcce11d858d781219 (JAR SHA256 2d45c1f11ff3fc1faed9fca5ab4fe06b12c586520658940d0118beaeeb486f3d): actual entry triggered the real welcome mode; both ordinary guests sat, received and consumed nutritious owned bread; both distinct servings traversed all nine phases; the sole original glass returned and the host session cleared. Source artifact qa/reports/artifacts/playtest/20260906T004610.405555617Z-648.vWWRhd. Overall run FAILED because the alarm command required a live seated guest after both first visits had ended. Server log later shows an ordinary returning diner; the corrected scenario waits for that real precondition. Run75326 is active at qa/reports/artifacts/playtest/20260906T005508.139882229Z-655.wCdctw; do not infer its final outcome.

Original stills and independent read-only review are preserved under PROJECT_STATE/evidence/2026-09-06-tavern/baseline-client-ie46d. Host/table occlusion prevents approval of feet contact and service choreography. A low side view is required. This is controlled-client evidence, not native motion/audio, paid progression, multiplayer or release approval.

The six-file articulated-seat proposal is UNINTEGRATED. Independent server review found one bounded-work defect: blocked normal exit repeated its full 57-sample preflight each tick. A Temp-only correction applies the existing 20-tick retry cadence while retaining active movement collision checks. Fully enclosed guests remain safely retained until a valid exit exists; 120 ticks does not guarantee forced release. Root is reviewing model endpoints before integration.
# Hearthstead Quality Ledger

Corrected gametest83647 TERMINAL PASS all725, artifact20260906T095410.094286457Z-392.UZXbqK, build3m13s. validate2883 TERMINAL PASS916/916,0errors/0warnings, artifact20260906T095815.313395453Z-388.710fLY. ContainerApproach correction now passes code/physical regression suite; actual same-B2 reversal acceptance still open because small baseline test passed old code too. Package74914 RUNNING; exact newJAR/freshB1→B2 next. Reuse required replay inventory/ledger images rather than an extra focused UI run. Active co-op proposal is being prepared independently in Temp; no active-population/native/release claim.

Baseline29223 TERMINAL PASS all725, artifact20260906T094935.324367389Z-388.1lBdmA, build2m43s. The small-arena Farmer cargo test also passes old routing, so it is ordinary-return coverage, not a reproduced reversal. Actual failed B2 trace remains before-fix evidence; synthetic arena has rim/open-space differences. Root integrated reviewed ContainerApproach door-detour precedence after terminal, rawSHAB67A4572BF6B0D6D003BA8B92DE18180DFEBB77E4025DF1FAF126A3E9444E321, and started correctedgametest83647. No lowered timing/search budgets. Full same-scenario B2 after-fix acceptance still required. UI source compilation passed in baseline; after-images/new package pending.

Doctor61495 TERMINAL PASS exit0, artifact20260906T093821.780097739Z-390.o5tnBx: integrated fresh-process parser and position-capture changes passed existing ownership tests and35input tests plus fulldoctorcontracts. End-to-end speed remains unmeasured. Root then integrated only the reviewed physical Farmer rear-wall regression, requiredcount725, and launched baselinegametest29223; route fix deliberately absent. Source/assets/QA frozen. Inventory/ledger compilation and baseline routing outcome pending. User requested explicit green/passed, yellow/pending, red/failed status colors; visual report updated accordingly.

11:36 B2 session10498 TERMINAL FAIL exit1, artifactplaytest/20260906T091844.518507772Z-79846.mOCut6: nine-crop storage await timed out at unchanged360s. Seven beetroot stored; return navigation repeatedly replaced door detour with rear-wall partial path, retaining aggregate cargo14 over184s before four-crop deposit. No final conservation/B2 checkpoint. Tester prepares physical return-leg regression and builder isolates door precedence fix. After terminal, root integrated reviewed inventory+ledger visual fixes and both harness optimizations with six baseline backups. Doctor61495 RUNNING at20260906T093821.780097739Z-390.o5tnBx; no compilation, new package, after-image or routing-fix pass yet. Old c1d9 checkpoints cannot certify changed source/QA.

11:25 current c1d9 dismissal UI VERIFIED in controlled client10498: actual released founder, fresh profession:NONE/dismiss:false/workZone:false and root-inspected original b2-worker-released.png. Visible Unassigned, no Dismiss, disabled Edit Work Zone. Earlier c411 stale profession defect is repaired at this verification layer; native verification remains separate. Inventory legibility/portrait fix is independently reviewed and apply-checked but Temp-only, not compiled or visually accepted after integration.

11:17 Oslo: B1 session79947 TERMINAL PASS exit0, artifact playtest/20260906T090624.623808057Z-638.MpSDlW. Current c1d9 candidate passed both tree cycles, eight-log conservation, replanting, exact axe damage246, 19 screenshots and clean save/stop. Published b1-complete manifest4d35725b3f79ef5abdfd16618fcb15c8663d80d1be0efdcff13a1a9057509dd4. B2 session10498 is RUNNING from this exact checkpoint using reviewed batched scenarioA9BDCD3C…; Farmer/Courier final conservation and corrected dismissal UI remain pending. Two additional harness speed patches are reviewed but isolated until a fresh-scenario boundary; see PROJECT_STATE/ITERATION_SPEED_REVIEW_2026-09-06.md. Native, earned raid and release evidence remain open.

Latest 6 September 02:23 Oslo — **VERIFIED server slice:** behavior62210 passed all707 required tests at `qa/reports/artifacts/20260906T000746.172806507Z-388.SapN6e`, including real reverse glass slide, midpoint reload and ordinary physical retrieval after diner departure/obstruction removal. Trace3248 samples204settlers1243diagnostics0findings. The initial707 run failed on a test observer that returned before checking a removed empty receipt; C8049 test-only correction retained the400tick deadline and added terminal stock/session/claim assertions. Quick50881 passed build,916resource checks and animation contract (3warnings), candidate `ie46d76c5b36fc65f3d8c`, JAR SHA256 `2d45c1f11ff3fc1faed9fca5ab4fe06b12c586520658940d0118beaeeb486f3d`.

**CLIENT ATTEMPT FAILED:** playtest5657 at `qa/reports/artifacts/playtest/20260906T001752.172887965Z-2105.42kk00` prepared the physical Tavern fixture, but player entry stayed false after eight short W taps. It never reached service assertions; no Tavern visual or motion approval follows. This non-checkpoint route used the development client and its runtime-JAR fields were unavailable; do not call it an installed-production or native proof. A separately reviewed checkpoint-enabled retry is being prepared with explicit position/screen evidence. The first offline welcome preview8068 failed because its external Blockbench bundle targeted Electron. The correct already-built browser tree has been copied and every included file hash checked; corrected-runtime preview30071 is running, unapproved. Physical seat endpoint/entry/exit polish, original before/after client imagery, audible song, B2-to-raid, active-job co-op and release gates remain open.

Latest 6 September 01:32 Oslo — **VERIFIED, server slice:** behavior99631 passed all705 required tests at `qa/reports/artifacts/20260905T232827.849101382Z-394.TNY58t`;3219 samples,201 settlers,1291 diagnostics,0 findings. Includes shared resident meal ownership/reload, physical seating/headroom/exit, arrival greeting and seven real host service tests including ordinary AI and side-facing recovery. The preceding quick77348 passed build,916 resource checks and animation contract (3 warnings), candidate `idf7c3216c9eb3738a569`. Later source includes coherent client presentation snapshots and the gated Tavern client fixture; neither server success nor compilation approves client motion/audio. **OPEN:** physically reachable glass return, hand/meal appearance continuity, equipment stow, actual client/native views and sound, the complete B2-to-raid journey, current active-job co-op and final release gates. Immediate demo remains Courier, Lumberer, Farmer, Guard, Archer and Innkeeper; other professions stay post-demo.

Farmer regression VERIFIED SLICE: behavior84328 TERMINALPASS exit0, All678requiredtests passed23:41:05 at20260905T213812.052095602Z-393.plIzCC. Positive physically opened/exited northdoor, harvested allnineoriginalcrops, proved one exactFarmhouse deposit receipt peroriginalcrop and complete production/chest/bag/loose conservation. Two mid-animation obstruction tests and three corrected direct-service fixtures passed. Trace3036samples187settlers1195diagnostic0findings. Production now checks actualfarmcontact, uses viable fieldstands and fresh budgets on newroutelegs; samelegbounds unchanged. Testtime6000 justified by actual one-seed transportcycle; no productionspeed/range increase. ActualB2playerchain/native/release remain pending; evidence does not transfer to those layers.

Farmer candidate72811 remains FAILED: gametest25504 and unchanged-source behavior11062 each reported four required failures. Artifacts20260905T211241.876216904Z-423.7KtyeF and20260905T211759.389914771Z-412.1btYw2. Closed-house worker exited/harvested4of9; trace shows complete paths and premature storage abandonment after roughly60ticks from inherited route counters, not proven door-path oscillation. New two mid-animation wall tests did not fail. One provenance fixture used squaredrange9against preserved6.5; corrected field placement is integrated with FirstRaid contact diagnostics (patch55DF), UNRUN. Two FirstRaid failures remain unexplained. Pending retry-budget proposal must be validated; no B2/native/release claim.

Farmer baseline71352 FAILED as intended: exactly one required failure, farmerexitsraisedclosedhouseandharvestseveryoppositecrop, in the676-test roster. Unmodified production Farmer harvested through the solid Farmhouse wall before opening/exiting the door; contact ray was blocked. Authoritative report: qa/reports/artifacts/20260905T210257.500609892Z-413.GPH46y/gametest-failures.txt. This reproduces the contact defect. The Temp-only correction still requires integration, regression PASS and actual B2 replay; no full navigation or food-chain claim.

Co-op capacity VERIFIED SLICE: canonical89216 terminalPASS at20260905T210201Z on i9b5/F2D37. Two separate actual production NeoForge clients remained joined for seven fresh samples at50and100 simultaneously registered idle citizens. Mean of reported average-MSPT samples:4.342857ms at50(budget45ms),5.457143ms at100(budget50ms). Exact roster/JAR/runtime frames, bracketing server player lists, population proofs and owned cleanup passed. Artifacts: qa/reports/artifacts/coop-playtest/20260905T205320.109151201Z-684.W6EIPy. Active jobs, shared-player actions,4clients,internet co-op,native frame pacing and release are not established. Native AV26and co-op10targeted tooling tests also PASS.

Latest B2 client85691 FAIL on i9b5/F2D37: corrected field selection passed; one real harvest/replant/deposit occurred, then nine-beetroot assertion timed out. Permanent evidence: qa/reports/artifacts/playtest/20260905T200047.945699380Z-640.HdkNok. Trace places the farmer inside the farmhouse back wall, within distance-only contact of the nearest crop, followed by repeated failed route budgets. Current code lacks a wall-visibility guard for farm mutation; exact navigation cause still requires regression. Full farm chain is not verified. Raw /tmp world no longer existed at subsequent inspection; logs/result/trace/shots survive.

Native AV tooling: reviewed v3 integrated; canonical controller-contract-selftest native-av26500 PASS,26tests0.677s. This verifies wrapper/controller contracts with mocked native boundaries. Actual Windows recording, audio listening, continuous motion and release remain UNTESTED by this result.

FOOD clean restart VERIFIED on i9b5/F2D37 (food-restart69292): ordinary AI picked up four bread, a real server process stopped and a different JVM resumed the same courier/request. Raw saved inventories changed source0/bag4/Hearth0 to source0/bag0/Hearth4, with the original ledger SATISFIED and a fresh actual food_delivery event. All23 inspector tests passed. Artifacts: qa/reports/artifacts/food-restart/20260905T195613.867804049Z-697.o6Bhi8. This is seeded transport and normal clean restart only; full farm-to-Hearth player journey, crash recovery, native and co-op remain open.

Living quality register per the continuous-completion directive. A requirement
is only PASS with concrete, reproducible evidence. Release requires two unchanged
canonical full passes, gate, and exact sealed native release-client-gate evidence.

## Active overhaul coverage — 5 September 2026
Latest 5 September update supersedes older pending rows below. English-only source/QA migration passed quick8365 (403unit tests,909asset checks, only en_us in actual JAR) and doctor94799; the native evidence matrix retains all three English display profiles. Preview10847 verifies legacy import conversion through twelve actual pose records; native exact-phase parity remains UNTESTED. Defense appearance quick54187 and actual client77249 PASS on iea09e9ddd763f7f1393f: fifteen original views independently accept the bounded Guard brow/Archer quiver/equipped visibility/Hunter control changes. Evidence: PROJECT_STATE/evidence/2026-09-05-defense-appearance. No full motion/combat/native/release approval. Earlier Staff/equipment instructions and timber cut ends retain their narrow readable-material acceptance; UI art remains too tame and larger redesign is deferred. Useful partial-path handling is VERIFIED for the bounded regression and original stalled route: old helper fails focused baseline42858, candidate13459 passes all675GameTests, quick65913 PASS. Actual client34489 on i571224f3a7ed1123dc90 repeats paid B1 and delivers four Courier oak logs at21:21:41.627 with conservation. That run later FAILS at farm corner-B aiming (crop occludes farmland), before harvest; corrected standing angle remains UNRUN. Full food route/restart/co-op/performance/release remain open.
**Active scope is the full existing player-facing NeoForge 1.21.1 mod**, including gameplay, progression, recruiting/Town Hall, citizen AI/navigation, visuals, animation, UI/world feedback, sound, persistence/co-op, performance and packaging. The owner's new mandate supersedes the A1-only phase restriction and former design locks. It does not authorize platform changes, data loss, purchases, banked resets, recurring automation or external distribution.

Use the active matrix and six-point direction in `docs/project/ROADMAP.md`; extend this existing ledger with evidence as each coherent batch ships. Older entries below remain historical. Their PASS labels, test counts, pending statuses and reference-copy requirements do not certify the present source. `OVERHAUL_PROGRAM.md` and `JOB_STANDARD.md` retain useful contracts but their old automatic certification/palette/reference mandates are superseded where inconsistent with current source and the new owner direction.

Statuses: **VERIFIED** (only named layer and exact candidate), **FAILED**, **BLOCKED** (identified missing prerequisite/tool), **UNTESTED**, **NOT APPLICABLE** (explicit scope reason). Source presence is a fact, not a pass. Preserve separate logic, server/GameTest, controlled-client, real player interaction, visual, motion/audio, experienced gameplay and performance results. Do not promote old headless or native evidence to a newer JAR.

| Current evidence / missing layer | Status and exact scope | Reproducible source / next acceptance |
|---|---|---|
| Paid emblem to physical Lumberer output | **VERIFIED — ide762 controlled player journey:** exact payment, one emblem issued/consumed, one actual hire, confirmed zone, own-chest axe, two trees/two saplings/eight returned logs, axe damage238→246. | playtest68043 PASS; evidence/2026-09-05-lumberer-player-journey contains original screenshots, result/scenario and independent review. Seeded setup; not natural firsthour, restart, audio, native work or release. |
| Native foot alignment and ordinary loaded lean | **VERIFIED — ide762 Windows static appearance:** parallel feet and retained forward lean, root and independent visual review. | evidence/2026-09-05-lumberer-foot-alignment/native-ide762: two original F2 images and fresh same-session exact-JAR ACKs. Isolated NoAI actor; dark material remains rejected. Installed JAR SHA F69130F6E02F7F89F2E1135CACDC24E21CA7954D77BBE48F1EC48068275E2A83. |
| Current polish source candidate | **UNTESTED in game:** integrated actual Staff emblem/instructions, workplace chest tool hint and shared cut-end generator fix; quick55225 running. | Each generated Lumberer sheet changes exactly eight intended RGBA pixels. Requires actual EN/NB UI and rear/side review before approval. No gameplay mutation. |
| Idle foot alignment and workplace guidance | **VERIFIED — bounded controlled client:** ide762 ordinary empty/full Lumberer feet parallel, loaded forward lean retained; NB workplace role/action and Requirements readable. EN workplace earlier i1759 also passed. | quick61509 PASS; playtest51660 PASS at20260905T154153.029009591Z-400.XWar2P; images playtest/20260905T154208.189501380Z-641.HcioV8. SharedIDLE offline20 frames accepted for client testing; equippedIDLE_LUMBERER offline midpose rejected pending renderer/runtime parity diagnosis. Actual equipped still looks down/forward, but is not phase-locked or continuous motion evidence. Native ide762 static foot/lean evidence is recorded above. |
| Carrier material and preview fidelity | **FAILED — final visual material:** dark logs merge with retainer in i1759 rear originals. Empty/half/full visible load differences exist. **UNTESTED:** corrected offline/runtime parity. | evidence/2026-09-05-lumberer-carrier/appearance-i1759 and evidence/2026-09-05-lumberer-foot-alignment. Preview overlay uses0.16 while runtime uses0.22; isolated parity patch unintegrated. Do not flip Java idle animation merely to match a suspect preview. Staff's missing visual referent is corrected in the current source candidate, pending runtime review. |
| Empty-settlement recovery guidance | **VERIFIED — i233d1 Windows, EN GUI3 1024x768:** summary and complete conditional Hospitality/start-over tooltip readable; separate red progress-loss warning. Root and independent pixel review complete. | `PROJECT_STATE/evidence/2026-09-05-pixel-ui/native-i233d1/README.md`, two original F2 images and fresh exact-JAR ACK. This is guidance only; actual Tavern recovery/reset and a full redesign are not established. |
| Canonical gameplay recovery | **VERIFIED — server/behavior only:** behavior40877, 672 required tests; 2,569 samples, 182 distinct figures across tests, 917 events, zero findings. | `PROJECT_STATE/evidence/2026-09-05-gameplay-recovery/{README.md,manifest.json}`; original `qa/reports/artifacts/20260905T122232.354202853Z-393.Yo1Be2`. Preserve RED63228, rejected88728 and FAIL95188 as history. |
| Work zone/equipment and Lumberer output | **VERIFIED — i33416 controlled client/physical subset:** stale zone refused, retry committed, one real replacement request, two saplings/eight own-chest logs, no stranded log cargo. | `PROJECT_STATE/evidence/2026-09-05-pixel-ui/equipment-i33416/manifest.json`; source combined5806. Continuous native employment-to-output plus mid-work reload and audible motion: **UNTESTED** in this evidence set. |
| Pixel UI and exact transactions | **VERIFIED — ib0f8f bounded EN GUI3:** 17 strict frames, three paid emblems/one seal, one tooltip, current-child keyboard behavior, Later 140-tick/reopen. | `PROJECT_STATE/evidence/2026-09-05-pixel-ui/ib0f8f/{README.md,manifest.json}`. QA offer is not a raid victory; not all 13 screens/states/profiles. |
| Native current installed candidate observation | **VERIFIED — i8d286 Windows startup/Hearth only:** existing separate QA world, original 1024×768 GUI3 frame, fresh exact-JAR render ACKs and actual menu open/close. | `PROJECT_STATE/evidence/2026-09-05-pixel-ui/native-i8d286/{README.md,manifest.json}`. Three unguarded founders died at night; survival balance is **UNTESTED**, not passed or proven defective by this alone. Not a sealed release-client-gate result. |
| Character material refresh | **VERIFIED — i33416 bounded appearance:** 28 original eight-role stills, complete independent view coverage. | `PROJECT_STATE/evidence/2026-09-05-workwear/runtime-i33416/{README.md,manifest.json}`. NoAI stills do not prove jobs/animation/hauling. Remaining role/armor/raider/material families and continuous motion: **UNTESTED** against the new standard. |
| New Hunter saved-arrow impact regression | **VERIFIED — server only:** behavior18513 passed all 673 required tests, including real reloaded Arrow collision, death/drop ownership, retained reserve shaft and one bow wear. Canonical analysis: 2,736 samples, 178 distinct figures, 950 events, zero findings. | `gametest/HunterPhysicalGameTests.reloadedHunterArrowReallyHitsAndLeasesItsPreyWithoutAnotherDebit`; artifact qa/reports/artifacts/20260905T130844.400539308Z-394.1xTu9U. Shooter remains loaded; not a full server-restart or native-animation test. No production Hunter change was needed. |
| Audio, complete new-player journey, sustained village balance, current-candidate co-op and measured capacity | **UNTESTED** by the evidence reviewed above. No new tool blocker is asserted without attempting the permitted route. | B1 continuous Lumberer recording with sound/reload, then B2 village cycles and B3 defense/co-op/performance. Names, screenshots, aggregate fixture counts and offline PASS do not substitute. |

For each new entry record exact input/JAR, scenario or supported controller route, result, original artifact/hash and limits. Capture before/after at comparable world/camera/profile; label authoring previews honestly. Keep scope-wide gaps open until verified rather than closing the overhaul when B1 is good.

---

**Product decision log:**
- **SUPERSEDING VISUAL UPDATE (2026-09-05).** Tobias rejected the painterly
  background in favour of a creative Minecraft-style UI. Hearth now draws
  crisp pixel surfaces directly; the unused large PNG was removed from
  packaged resources after checking an identical preserved evidence copy.
  The large-art size exception below is retired. Settler, Storage, Guard
  Orders and Plaque now share the new visual direction through reviewed
  screen-local drawing. Runtime verification of this combined batch is pending.
- **VISUAL SPECIFICATION UPDATE (2026-09-05, delegated visual authority).**
  Hearth receives a new chapter navigation, colony ledger and supplies drawer.
  Its single authored background `gui/identity/hearth_council_table.png` has
  an exact 1672 x 941 source contract (approximately 6 MiB decoded RGBA).
  The asset validator checks this exact path and size; other GUI assets keep
  the 512 x 512 limit. This is an explicit art budget change, not runtime
  performance approval. The background contains no map or live UI state.
  Build, compact rendering, slot interactions and runtime evidence remain
  required. The FOOD recovery correction is separately owner-approved;
  independent source review passed, fresh integrated QA is pending.
- **SPECIFICATION CORRECTION (2026-08-24, owner-sourced).** The earlier
  "PLAQUE SYSTEM REMOVED — do not reinstate" directive is **superseded**. The
  owner reinstated the Building Plaque by written spec, then refined it in
  answers recorded as `docs/project/DECISIONS.md` D-005 and D-006:
  **the plaque is the surveyor** — a room is only detected because a plaque
  was hung, so no plaque means no building; and a plaque with no inserted
  Build Plan opens no UI. The plaque remains an ACCESS POINT and must never
  hold its own building registry or resident list.
  Recorded here because `CLAUDE.md`'s rule is that specification conflicts are
  resolved by a written correction in this ledger, not by editing an invariant
  quietly. `CLAUDE.md` and `qa/PROTOCOL.md` INV-2 were reconciled to match;
  this entry is the missing third piece.
- Loop directive: no completion claims until 2 consecutive green rounds.
- **Room detection = TekTopia model (user, this session):** "scan the room; if it
  meets all the requirements, it works." That is exactly the current engine:
  automatic seeded flood fill, requirements = enclosed + roofed + bed + door +
  light. Confirmed by the user, so it stays automatic.
- **DEFERRED — how the player is told what a room still needs.** The user has
  reopened the question ("maybe a plaque system or similar — we can fix that
  later"). Nothing is being built for it now, and the plaque removal stands
  until the user decides. `RoomScanner.Result.missing()` already produces the
  exact text ("not enclosed; no bed; no door; no light; open to the sky"), so
  whichever surface wins later — Tingboka entry, HUD toast, held-tool overlay,
  or a plaque-like marker — it only needs a presenter, not new detection work.
- **BACKLOG — cloak/armour texture pack** (user, long-term): a full cosmetic
  layer for cloaks and armour progression. Slots into A1d's appearance layering
  and the guard equipment progression in B1; not in scope now.

## Iteration log

### Iteration 1 (in progress)
| # | Requirement | Status | Evidence / gap |
|---|---|---|---|
| 1 | Clean build from scratch (NeoForge 1.21.1, Java 21, MDG 2.0.144) | PASS | `./gradlew build` → BUILD SUCCESSFUL, `build/libs/hearthstead-0.2.0.jar` |
| 2 | All prototype GameTests pass on 1.21.1 | PASS | `./gradlew runGameTestServer` → "All 9 required tests passed" (founding, profession, farmer, lumberer, guard, eat, alarm/flee, settler NBT round-trip, SavedData round-trip) |
| 3 | 1.21 datapack layout (loot_table/recipe/structure/tags/block singularized, recipe result {id}) | PASS | validator + gametests load structures; recipes rewritten |
| 4 | Networking ported to payloads (OpenSettlerScreenPayload) | PASS (code) / untested client | registered via RegisterPayloadHandlersEvent; client screen open needs client-side check |
| 5 | Capabilities: hearth item handler exposed | PASS (code) | RegisterCapabilitiesEvent registration; no automated test yet — add hopper/gametest check |
| 6 | Room detection engine (no plaque) | FAIL (not built yet) | A1c in progress |
| 7 | Homes: capacity from beds, bed claiming, sleep in own bed | FAIL (not built yet) | A1c |
| 8 | Furnishing quality score → morale | FAIL (not built yet) | A1c |
| 9 | Modular settler visuals | FAIL (not built yet) | A1d |
| 10 | Dedicated-server E2E on NeoForge (boot, found, persist) | FAIL (not run yet) | A1e |
| 11 | UI visual inspection | BLOCKED (headless env) | attempt xvfb+Mesa client; else code-based checks + manual screenshot checklist |
| 12 | Asset validator green on new layout | FAIL (not re-run) | update tools/validate_assets.py paths for 1.21 |
| 13 | No TODO/FIXME/placeholder in active scope | UNVERIFIED | sweep pending |
| 14 | Deprecation warnings triage | OPEN | non-removal deprecations remain; list & fix or justify |

Green streak: 0. Next: A1c room detection, A1d visuals, then full round re-run.

### Iteration 2 (in progress) — QA enforcement installed + A1c verification

Permanent QA system installed per directive: `qa/PROTOCOL.md` (v1.0.0),
`tools/hearthstead-qa` controller (sole approved test entry point),
`.claude/settings.json` hooks (bash guard blocks direct `runGameTestServer`/
`runClient`/`runServer` — validated exit 2; post-edit hook marks
`qa/reports/.stale` — validated; Stop-gate hook blocks completion while red —
validated exit 2), freshness manifests with source fingerprints, decision-trace
detectors (`qa/scripts/analyze_trace.py`), seeded reproduction, documented
`qa/reports/BLOCKED` escape (cleared automatically by every full run).

| Finding | Root cause | Fix | Evidence |
|---|---|---|---|
| GameTests 12/13: `settlersleepsinclaimedbed` — settler IDLE at hut doorway rel (8,1,6), bed claimed but never reached | Settlers had door-capable *pathfinding* (`setCanOpenDoors/PassDoors`) but no goal that physically opens doors — pathed to the closed oak door and pushed against it forever | Reference check (user directive): TekTopia + MineColonies villagers both open doors; TekTopia closes them behind. Adopted: `OpenDoorGoal(this, true)` at priority 1 (flag-free, runs beside move goals; closing keeps homes enclosed + raid-defensible) | artifacts/20260823T183955Z/gametest-failures.txt; fix in SettlerEntity.registerGoals |
| Stale-settlement purge regression (concurrent day-batch neighbors deleted each other) | first purge version removed any settlement <40 blocks unconditionally | replaced distance heuristic with exact ARENA-BOUNDS purge (`helper.getBounds()`): can only remove settlements standing in the space this test owns | in HearthsteadGameTests.makeSettlement(GameTestHelper,…) |
| **Breached/leaky room still registered** (found only after the fix above, and deterministic — the old suite had been passing this case by luck) | The GameTest arena is capped by a **barrier ceiling** (`y9=Barrier`, measured). So when the fill escapes a breached hut it spreads under that ceiling and comes back *enclosed and roofed* — `enc=true sky=false vol=1856` versus the intact hut's `vol=27`. Enclosure and roofing were both answering correctly; the missing rule was that a dwelling must be a bounded ROOM. | Added `MAX_HOME_VOLUME = 512` to `validHome()` — already a 16x16 hall with a 2-high ceiling, so no real cottage is affected, while a fill that has escaped into a cave/courtyard/outdoors is rejected. This is what makes a raid breach genuinely un-home a house. | `leakyroomrejected` + `homeinvalidatedwhenwallbroken` green |
| Housing was poll-driven: a homeless settler waited up to 40 ticks after a home appeared | settlers polled for a free bed; nothing pushed an assignment when a house registered | `BuildingManager.assignFreeBeds` hands a new home's free beds to loaded settlers without one, the moment it validates | in `processScan` |
| **Room registration flaky: 1/5 to 2/5 runs green** — `settlersleepsinclaimedbed` + `homeinvalidatedwhenwallbroken` intermittently saw `homes=0 buildings=0` | Diagnosed from evidence, not intent. Census diagnostics proved: the room was VALID (`liveScan enc=true beds=1 doors=1 lights=1`), the settlement DID hold it (`d=9.2 holds=true`), and the scan WAS processed (`requested=5 processed=5 pending=0`). Therefore the scan was rejected *at the time it ran*: `RoomScanner` gated validity on `level.canSeeSky`, which reads the heightmap that the **light engine settles asynchronously** after the arena's ~1300 block writes. One rejected scan was final — nothing ever re-scanned. | **(1)** The roof test is now **geometric**: for every cell at the top of its column, look upward for a block with a collision shape (`hasCoverAbove`). Deterministic, independent of the light engine, and it accepts glass and slab roofs that `canSeeSky` wrongly rejected. (An intermediate attempt simply dropped the sky gate — that was wrong and is recorded here rather than quietly reverted: it let a breached room register, which is how the barrier-ceiling finding above was uncovered.) **(2)** `BuildingManager` re-checks a failed scan 4x at 100-tick spacing, so a room that just missed — or a world that has not settled — still registers, matching how the reference colony sims re-check rather than decide once. **(3)** Added `Result.missing()`, the player-facing "why isn't this a home yet" string that inherits the job the removed plaque used to do. | stability sweep below |

**Stability evidence (this is the point of the exercise).** A single green run
proved nothing here: the suite passed once at 13/13 while carrying a defect
that failed 4 runs out of 5. Repeat-run measurement is therefore the standard,
recorded before and after every fix:

| stage | result |
|---|---|
| after the door fix | 2/5 green — flake still present, "green" was luck |
| after the arena-bounds purge | 1/5 green — hypothesis wrong, discarded |
| after the geometric roof test | 0/5 but **deterministic** (leaky room registered) — a better state than flaky |
| after `MAX_HOME_VOLUME` | **5/5 green** |
| with 2 new regression tests added (15 tests) | **3/3 green** |

New regression locks: `unlitRoomRegistersOnceLit` (a failed scan must be
re-checked, not written off) and `glassRoofCountsAsRoofed` (roofing is
geometric and must not consult the light engine).

### Iteration 5 — SLICE ANIM-1: recorded specification corrections

Per INV-10, defensible deviations from `docs/ANIMATION_CATALOGUE.md`'s
literal text, found and confirmed correct by the ANIM-1 RELEASE_GATE
(MEDIUM-6), recorded here rather than left silent:

| Deviation | Catalogue text | What shipped | Why |
|---|---|---|---|
| `RUN_PANIC` length | 0.55 s loop | `withLength(0.6F)` | 0.55 s puts the accent's quarter-beats off the 0.05 s tick grid `anim_check.py` enforces; 0.6 s lands every keyframe on an integer tick with no perceptible change to the silhouette. |
| `MELEE` additive handoff | catalogue's literal non-zero start/end pose values | every authored upper-body channel now begins at zero, crosses rest by a small real overshoot at 0.40 s, then holds zero at both 0.45 and 0.50 s over either `GUARD_STANCE` or `WALK` | Merely matching a non-zero end frame to the same non-zero start still popped when the one-shot stopped. Reaching zero only on the expiry frame also retained 4–6°/tick terminal speed. The held terminal rest is the real runtime transition contract. |
| `MELEE` lunge ownership | fixed-time `right_leg` −30° / `left_leg` +28° overlay | no `root` or leg channels; the active stance/walk base remains sole lower-body owner | Multi-view rejection measured both feet sliding about 6 model px with no planted support foot or hip compensation. The strengthened gate now computes world-space foot endpoints and allows future root compensation only when one foot honestly stays within 0.75 px. |
| Off-grid keyframe timestamps (`WALK_HURRIED`, `RUN_PANIC`, `MELEE`, `CELEBRATE`) | as originally transcribed | nudged to the nearest 0.05 s tick | `anim_check.py` §17.4 enforces the tick grid; the nudges are sub-perceptual (≤1 tick) and do not change any pose. |
| `anim_check.py` checker exemptions: `EAT` added to `LEGS_EXEMPT`; `SHIELD_BLOCK` added to `CLOAK_PIN_ALLOWLIST` | catalogue §17.4's own enumerated allowlists do not name either clip | both exemptions kept | `EAT` is a stationary in-place clip the catalogue never asks to move the legs (§12.3 specifies no leg channel at all — flagging its absence would be a false positive). `SHIELD_BLOCK`'s cloak is deliberately pinned by the raised shield arm per §4.4's own bone list, not left to swing — the pin is the correct read of the spec, not a bug the checker should catch. |

### Iteration 6 — SLICE ANIM-1: REVISE round (RELEASE_GATE 2026-08-24)

RELEASE_GATE returned REVISE: 1 BLOCKER (see `docs/project/KNOWN_FAILURES.md`
KF-011 — bed-sleeping settlers permanently stuck asleep), 4 HIGH, 6 MEDIUM,
6 LOW. Fixed in one coordinated round per the standing rule (all findings
addressed together, one re-review). Evidence and per-finding detail: git
history on `claude/hearthstead-settlement-mod-vbdb9n` for this iteration,
`.claude/WORK_STATE.md`, and KF-011 above for the BLOCKER specifically.

### Iteration 7 — PLAQUE-2 step 3b: recorded specification correction

**What changed, and why it is a correction rather than a weakened test.**

`theSheetSaysWhatIsMissing` (added earlier the same day, in step 3) asserted
that a plaque on a satisfied room shows **one sheet line per requirement**,
all met. Step 3b makes a registered building show its **occupancy** instead —
`People 1/2` — and the test failed on exactly that line. Under INV-10 a
failing test is never edited to fit the code, so this is recorded here.

The change is a product decision the owner asked for, not an accommodation:

> *"Og en population slik jeg vet om et hus er fullt. People 1/2 eller
> lignende"* — and, minutes later, *"Rettelse dropp working eller ikke. Det
> lyset fungerer veldig fint."*

The sheet's writable field is about four model pixels tall. Six lines in it
are unreadable, so the sheet takes one of two faces: the **checklist** while
the building is not registered, and the **occupancy** once it is. That is also
the right split by usefulness — a finished checklist is four ticks nobody
needs to re-read, while "is there a bed free" is the live question — and it
degrades correctly: if a requirement later fails, the plaque unlinks on its
next survey and the checklist returns by itself, naming what broke.

**What was kept.** The half of the test that actually judges the slice's claim
— take the bed out, and *exactly* the beds line flips to unmet while doors,
lights and floor stay met — is unchanged, and still fails if the ink mapping
is broken (verified by mutation). Only the first phase's expectation moved,
from "the finished checklist" to "the registered face", and it now asserts
the new behaviour positively rather than asserting less.

**No working / not-working line was built**, per the owner's correction. The
lamp in the board already carries that signal, and carries it across a village
square where a word cannot.

---

## Iteration 8 — specification correction: the village clock

**What changed.** `SettlerEntity.DayPhase` (WORK / EVENING / REST) was replaced
by the settlement-wide `DayPhase` with six phases, adding a waking phase before
dawn and a midday meal. This is a product change the owner asked for: *"når det
er «jobbtid» så drar de til jobb. Ettermiddag så er det mat så kveld legge seg."*

**What it broke, and why that is not a test being weakened.** Two fixtures were
pinned to the old boundaries:

- `settlerWakesAtDawnWithRecoveredEnergy` set the clock to 23000 with the
  comment *"REST phase, close to dawn (23500)"*. Under the new clock 23000 is
  RISE, so the settler correctly never went to bed. The fixture moved to 22600 —
  still deep in REST, a few hundred ticks short of the new dawn. **The
  assertion is unchanged**: drive a full night through dawn and require a
  natural wake with recovered energy.
- `settlerSleepsInClaimedBed` failed as collateral. It shares a level, and
  therefore a day time, with the test above through the `night` batch; once the
  other test stopped being in REST, this one's settler was walked off to the
  gathering point. Fixing the first fixture fixed both.

Neither test was skipped, loosened or deleted, and neither assertion was
touched. What moved was a clock reading that the specification itself moved.

**What it caught that was a real defect.** `aFullSackSlowsTheCarrier` failed
because the new `GoToPostGoal` was walking a *laden* courier to the gathering
point, where he set the goods down. That is a genuine bug in the new feature —
the schedule must never override a job in progress — and the fix is in the
goal, not the test: a settler carrying anything is not re-posted. The first
attempt at that fix read the synced carry load, which is published from
`aiStep` **after** the goals have already run, so on a settler's first tick it
still said zero while the sack was full. Reading the container is the only
answer that is true on every tick.

**Mutation evidence.** Both new load-bearing rules were mutation-proven in one
run: setting `OFF_ROAD_MALUS` to zero failed
`settlersTakeTheLongWayRoundToStayOnTheRoad`, and removing the vacate step from
`Employment.hire` failed `noSettlerHoldsTwoPosts` and
`takingAWorkerNamesTheLoss`. Restored, 89/89 green.

---

## Iteration 9 — specification correction: the farmer sows by hand

**What changed.** The farmer's planting step now sets `WORK_SOW`
(`SOW_BROADCAST`) instead of `WORK_PLANT` (`FARM_PLANT`). This is a product
change the owner asked for directly: *"Farmer lager en strø animation at han
kaster ut strø for å legge ut nye seed."* Broadcasting seed reads at fifty
blocks; pressing one seed into one hole does not — and D-016 makes a distinct
signature motion part of what finishing a job means.

**What it broke.** `farmerReplantsAfterHarvest` asserted the farmer passes
through `WORK_PLANT`. The assertion — that the farmer visibly enters a planting
activity after harvesting, rather than silently refilling the field — is
unchanged; only the name of the activity it watches for moved, because the
activity itself moved. Nothing was skipped, loosened or deleted.

**FARM_PLANT is not deleted.** It stays authored and catalogued, unused for
now. A clip with no caller is not a defect; deleting a good clip to tidy a
report would be.

**Tool evidence.** `anim_preview.py` — new this iteration — read all three new
signature clips as clean against the craft standard, and caught two real
defects while it was being built: `HAMMER_ANVIL`'s torso peaked *on* the
contact tick instead of before it, and `GATHER_LOG` ended away from its start
pose, which would have snapped the settler when the one-shot expired. Both are
fixed. `MELEE`'s former recorded exception was removed in the combat recovery
pass: the clip now passes the same strict pop check as every other impact and
hands off as a zero-offset additive layer to `GUARD_STANCE`.

The first offline recovery Candidate was still rejected on semantic review:
both fixed-time leg channels moved their feet roughly six model pixels, the
last recovery tick retained 4–6°/tick of speed, the early sword silhouette was
occluded, and the contact sheet contained no physical target. The revised
Candidate removes lower-body ownership from the overlay, requires a real
rest-crossing overshoot plus a held terminal rest, places the anticipation
sword outside the torso, and renders a fixed raider/hitbox whose physical
blade intersection is asserted at tick 4. `BB_BASE_PHASE` now produces the
same target-aware evidence over five adversarial `GUARD_STANCE` phases and
`WALK`. This remains Candidate until independent re-review and native client
slow-motion; no offline result upgrades it to Approved.

---

## Iteration 10 — KF-018 (lumberjack scan), D-016 closed, and an invisible
## nameplate regression found by a pig

### KF-018 — the lumberjack starved on sparse maps; uphill trees were invisible forever

**What was wrong, with the numbers.** `LumbererWorkGoal.canUse` scanned for a
tree base with `WorkScanner.scan`, whose offset table is the full **97 × 97 ×
9 = 84,681** positions around the settlement (`WorkScanner.MAX_RADIUS=48`,
`Y_BAND=4`). At a 512-position budget per call and a scan roughly every four
to six seconds (`scanCooldown = 80 + random(40)` ticks), one full sweep took
**about fourteen minutes** — a settler in a thin wood stood idle nearly a
quarter hour before noticing the tree behind him. Worse, the vertical band
was centred on the **hearth's own Y**, so a tree four blocks up a slope sat
outside the ±4 band and was never found — not slow, *permanently invisible*.
Both numbers and the "fourteen minutes" figure are stated in the fix's own
doc comment (`WorkScanner.java`), not recomputed for this entry.

**Fix.** `WorkScanner.scanColumns` — a second offset table of horizontal
**columns only** (9,409 of them, `side² = 97²`), independent of any Y
anchor. `LumbererWorkGoal.trunkInColumn` reads the heightmap
(`MOTION_BLOCKING_NO_LEAVES`) once per column and only descends the trunk
when the surface block is itself a log, capped at `TRUNK_DESCENT = 32` so a
decorative column can never be chased to bedrock. The code's own comment
puts a typical sweep at "about twelve calls" versus the old "a hundred and
sixty-five" — an average-case figure (the scan can stop once it has found
`maxResults=6` bases), not a worst-case one; the sparse-map case that KF-018
is about still needs to walk close to the full 9,409-column table, which is
still an order of magnitude cheaper than the old 84,681-position volume.

**Files.** `src/main/java/com/hearthstead/entity/ai/WorkScanner.java`
(`scanColumns`, `columnTable`), `src/main/java/com/hearthstead/entity/ai/
LumbererWorkGoal.java` (`trunkInColumn`, `TRUNK_DESCENT`).

**Not yet recorded in `docs/project/KNOWN_FAILURES.md`** — that file is
outside this worker's ownership this session; the KF-018 identifier exists
only in code comments right now and should be written up there by whoever
owns it next.

### D-016 closed in full — the last five signature motions

The five trades still on a borrowed motion (`COOK`, `CARPENTER`, `MASON`,
`FLETCHER`, `TANNER`) got their own: `COOK_STIR`, `CARPENTER_PLANE`,
`MASON_CHISEL` (the impact-checked clip — `tools/anim_preview.py`'s
`IMPACT_CLIPS` set, held to the wind-up/beat/overshoot standard),
`FLETCHER_FLETCH`, `TANNER_SCRAPE` — all authored in
`docs/ANIMATION_CATALOGUE.md` §20 and `SettlerAnimations.java`.

`SettlerActivity` gained `WORK_STIR/PLANE/CHISEL/FLETCH/SCRAPE`, **appended
after the existing values**, per the enum's own standing rule ("ordinals of
the values above must never shift, this is the wire format" —
`SettlerEntity.DATA_ACTIVITY` is ordinal-keyed). `Employment.motionOf` was
updated so every one of the twelve `Production`-backed trades now maps to a
distinct `SettlerActivity`/clip pair — no two trades share a work loop
anywhere in that switch. Recorded as **D-017**.

`tools/anim_preview.py` read all 44 catalogued clips clean (`44 of 44 clips
read clean`, re-run independently for this entry), and `tools/job_audit.py`
gained **smith** as its seventh CERTIFIED trade (verified against the
working diff:
`CERTIFIED = {"lumberer","farmer","courier","guard","miner","baker","smith"}`).

**What is not yet true, stated plainly so it is not overclaimed later.** The
five trades above have their motion but **not** certification: each still
borrows another trade's work sound (`docs/ANIMATION_CATALOGUE.md` §20: "that
debt keeps these five jobs out of the certified list until their own voices
land"), confirmed unchanged in `docs/project/JOB_STANDARD.md`'s trade table
— all five read "not yet". Motion-complete and job-certified are different
claims; only the first is true for cook/carpenter/mason/fletcher/tanner as of
this iteration.

### An invisible rendering regression, found by a pig standing next to a settler

`SettlerRenderer.renderNameTag` still used the 1.20-era
`pose.scale(-0.025F, -0.025F, 0.025F)` mirror. On 1.21 that negative X scale
flips every glyph quad to face away from the camera, so the whole name tag
was culled — silently, with no error, no log line, nothing to grep for. It
had been broken since the 1.21 port and nothing had ever proven a settler's
name actually rendered.

**Proof.** A vanilla pig's name tag rendered normally standing beside a
named-but-invisible settler in the same shot — evidence session
`qa/reports/artifacts/live/20260825T183505Z` (`shots/pigtest.png`,
`shots/nameclose.png`, `shots/nameclose2.png`). Comparing against an entity
that is known-good ruled out camera, font and distance as the cause and
pointed straight at the scale sign.

**Fix.** Positive X scale and the 1.21 `EntityAttachment.NAME_TAG` point
(`entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, …)`)
replace the manual offset. Also fixed in the same pass: `shouldShowName` now
branches on `entity.isCustomNameVisible()` — an explicitly flagged settler
(lineups, a player-named settler) gets vanilla's full range (`4096.0` sqr =
64 blocks), everyone else keeps the original `150.0` sqr intimate range.

**Same evidence session also found the showcase forceload lesson** (stage
chunks unloaded → `getHeightmapPos` answers the world floor at Y=-64 → a
whole lineup spawns inside bedrock and suffocates, reporting "N settlers
posed" over an empty field) — `qa/scripts/showcase.sh` now force-loads the
stage before building or spawning on it. Both findings share the one
timestamp because both were made in the same continuous live session, not a
citation error.

### Showcase tooling landed this session

`/hearthstead pose|pulse|lineup` (`HearthsteadCommand`) let an operator pose
any settler into any of the 33 catalogued poses, spawn a labelled lineup
page, or re-fire a one-shot, without touching the AI. `qa/scripts/showcase.sh`
wraps this into turnkey filmed scenes against a live session. **Recorded as
D-018**: `applyPose` calls `settler.setNoAi(true)` and writes the
activity/profession projection directly, bypassing goal selection entirely —
so this is a viewing aid, never a test oracle, and no GameTest may
pose-then-assert. Verified: no `GameTest` in the tree currently calls `pose`.

### Quality gate — honest status: in progress, not clean

`qa/reports/latest.json` (most recent full run, `20260825T172412Z` →
`173813Z`): **overall PASS, `green_streak: 1`** — one short of the ≥2 the
contract requires for any completion claim. Since that run, source has
changed substantially and remains **uncommitted**: `WorkScanner.java`,
`LumbererWorkGoal.java`, `SettlerRenderer.java`, `HearthsteadCommand.java`,
`SettlerAnimations.java`, `Employment.java`, `SettlerActivity.java`,
`SettlerEntity.java`, `SettlerModel.java`, `Schedule.java`, both lang files,
`docs/ANIMATION_CATALOGUE.md`, `tools/anim_preview.py`, `tools/job_audit.py`,
plus four new untracked GameTest files and the new `showcase.sh`. **No full
run has been made against the current source fingerprint at all** — the
`full ×2 + gate` requirement has not merely regressed, it has not started.
This is stated here rather than left implicit so no downstream claim treats
`latest.json`'s PASS as covering the current tree.

---

## Iteration 11 — specification correction: a load is bounded by mass, not only by count

**2026-08-26.** `logistics/Weight.java` was written, documented and committed
with **zero callers anywhere in the mod** — found by an interconnection audit,
not by a test, because nothing tests a class nobody calls. For as long as that
was true the owner's own stated design ("putte ting som er tungt nærme
warehouse") did not exist in play: a courier's load was bounded by item COUNT
alone, so eight iron ingots and eight feathers cost exactly the same walk, and
**no arrangement of buildings could beat any other**.

Wiring it into `CourierWorkGoal`'s two load loops turned two GameTests red.
Both were the tests encoding the old specification, and both are corrected
here rather than loosened. Recording the distinction, because "the fixer
changed the judge" is the exact shape this project forbids:

**`courierSackShowsTheRealLoad`** asserted `peak == capacity` — the sack always
fills to 8. That is only right for cargo light enough that the count binds
first; the test hauls OAK_LOG, which is HEAVY, so the true ceiling is 4. The
assertion is now `peak == Weight.perLoad(OAK_LOG, capacity)`. This is
**stricter than what it replaced**, not weaker: it still catches under-filling,
and it now also catches the weight table being wrong or silently bypassed. It
was deliberately NOT relaxed to `peak <= capacity`, which would have passed a
courier hauling one log at a time forever.

**`reservationLetsOnlyOneCourierFetchTheSameStock`** seeded 4 RAW_IRON —
DEAD_WEIGHT, so now two trips. The first courier finished, released the job,
and the second correctly took the remainder: the ledger working exactly as
designed, reported as a failure only because the setup quietly assumed one
trip empties the chest. The pile is now sized to exactly one load and **every
assertion is untouched**, including the strict "only one courier should ever
have hauled this stock". The bug was in the fixture's hidden premise, not in
the claim.

The lesson worth keeping is the first one, though, not the test-repair: a
feature can be complete, reviewed, documented and committed and still be
absent from the game, and no suite will say so. Nothing in this repository's
gate asks "is this code reachable at all". The interconnection audit that
found it is now `docs/project/SPIDER_WEB_AUDIT.md`.

## Iteration 11b — three buildings stopped advertising jobs nobody can fill

SCHOOL, INFIRMARY and MARKET each declared worker capacity, so their plaques
offered hiring, while none appears in `Employment.TRADES` — every hire was
refused with `no_trade`. The refusal was honest; the offer was not. Capacities
set to 0 until the matching trades exist. The plaque is the surveyor this
whole design rests on, and a plaque that advertises a post that cannot be
filled teaches the player its promises are decorative.

## Iteration 12 — specification correction: fatigue slows work; it never forbids it

**2026-08-28.** Per INV-10, the earlier daily-effort hard stop is superseded
by the owner's explicit rule: a tired settler keeps doing otherwise valid
work, but does it more slowly. `Effort` may remain deterministic bookkeeping
for telemetry and balance, but `isEffortSpent()` and fixed low-energy
thresholds may not reject ordinary work. Day phase, combat/safety, target
authority, route cooldowns, storage capacity and night-rest rules remain
unchanged. The two GameTests that formerly certified a hard quota now assert
the stricter corrected behavior: already-spent farmer and lumberer work still
progresses instead of silently becoming ineligible.

## Iteration 13 — demo boundary and Work Scepter are explicit owner corrections

**2026-08-30.** Two later owner decisions supersede older release text and
must be treated as specification changes, never as a quiet weakening of a
test.

First, the playable demo ends after the first raid's persisted **Aftermath**.
The demo must still teach the complete path from a first-time player through
Hearth, buildings, roles, logistics, guards and the first raid, but a fully
operational post-raid doctrine branch is not a release prerequisite. A
doctrine view may be shown as a future-facing teaser. Any older gate that
requires the player to commit to a permanent doctrine after the first raid is
therefore testing the superseded scope and must be rewritten with the changed
contract recorded in its test name and evidence.

Second, Work Scepter selection is a deliberate three-step 3D interaction:
the player block-hits two horizontal corners and then performs a separate,
clearly previewed height selection. The resulting bounded volume is
server-authoritative, survives reload and is limited by the worker/building
level. Older wording or fixtures that infer an unlimited/full-height column
from only two corners are superseded. Replacement tests must prove both the
explicit height and the level-dependent capacity; they may not simply remove
the old assertion.

## 2026-09-11 ordered defender meal delivery
GREEN: both bounded meal outbound and return-to-post recovery; all802GameTests; new normal-world clean saved playtest; identical installed/server/friend candidate i9ecf855a05d3afd656a4. Actual Start/public ping/Stop passed04:52-04:53. Evidence PROJECT_STATE/ORDERED_MEAL_RECOVERY_2026-09-11.md. RED: paid seated G7 natural next visitor and full raid victory remain unapproved. Native visual/FPS approval not inferred.

## 2026-09-11 free-bed recovery delivery
GREEN newactualbedclaim/sleep/energy60/postreturn regression, build/install/publicserverstart/cleanstop fori599685bf0b171d934f26. Overall802/803: REDexistingCivilianAlarm reportcase. REDnaturalnextTavernguesttimeout/fullraid/fullsurvival/nativevisualapproval. Evidence PROJECT_STATE/FREE_BED_RECOVERY_2026-09-11.md.
