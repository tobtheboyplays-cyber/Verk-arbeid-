# Queued work (start only when running tasks finish — owner rule 26 Sep)

1. **Wrist pass** (engine has `right_item`/`left_item` bones now): re-author tool clips with a real wrist —
   CHOP (+v2/v3, owner: "axe swung weird"), FARM_TILL (too hunched), combat slashes (blade alignment,
   heavy wrist snap), hammer/saw/chisel if they benefit. One agent, combat+chop first.
2. **LEAP_STRIKE** re-author after engine confirms layering (stance double-apply issue).
3. **Full GameTest suite** (serialized, `rm -rf run/world`) + JUnit once all lanes are done; include
   RepairNailGameTests (repair_day), HunterCarcassGameTests, GuardMovesetGameTests, RaidSpawnClaimGameTests.
4. **In-game film of everything** (one lane): all authored clips + variants, hunter, Banner block, map UI.
5. **Recruitment change**: tavern visits now start with beds full (uncommitted edits in RecruitmentPolicy /
   SettlementManager, owner unknown) — confirm intended; check tests that assume otherwise.
6. **Settler sheet (SettlerScreen) in the new UI style** using Codex ref `ui-settler-sheet.png`.
7. **Sonniss 2026 voices** result review with owner (in progress in sound lane).
8. **Codex**: T3 exact-once review; later T4 Xaero plan.
9. **Integration plan** back to the original repo + Sunday build proposal (ask owner before deploy);
   GitHub private + Actions once owner makes the repo private.
10. **UI polish (non-Banner screens)** from QA playthrough: tech-tree help tooltip covers header + clipped nodes +
    cost "1/6" pager; emblem shop says LOCKED when merely unaffordable + no refresh on inventory change;
    handbook chapter tabs clipped; redundant label tooltips everywhere; settler status world-labels huge up close.
11. **Raid robustness (QA code findings)**: first-raid timeout/dawn retreat; readiness-drop after warning waits
    silently (now has hold notice — verify); pendingRaid not cleared on quarantine; sleep-block radius+64 vs
    raid-start radius+32 mismatch; missing lang key `raid_dawn_retreat`.
12. **Founding numbers**: base capacity 3 vs 4 founders ("4/3 people", "Beds 4/3" with zero beds) — design
    call (owner): capacity = founders? `/hearthstead info` shows "5% tonight", "99 nights since a raid" pre-ready.
13. **Economy check (owner decision)**: early Coins tight (~31 Coins of nodes/emblems vs 8-Coin first purse,
    then 12 per 20 real min).
14. **Night-1 safety**: founders die to mobs on night 1 — shelter/warning proposal.
15. **Settler hitbox 1.95 → 1.9 tall** (pathing proposal): makes carpet under 2-high doors/ceilings walkable.
    Check renderer/model scale + GameTests. Remove the now-unused PathNavigation AT lines at the same time
    (forces one re-decompile — do it when no lanes are building).
16. **Pathing film**: server-house before/after (WSL crashed during attempt).

## Owner priority 26 Sep 01:00 — "base work on all jobs we've thought of" (start as load allows, ≤80% CPU)
A. **Logistics upgrades**: bigger sack tiers (satchel → pack → frame pack), carts (hand cart physical/visible,
   courier cart routes), road speed bonus — tie into new tech tree (plan/techtree/techtree.json).
B. **Bard as a real profession** (TavernBard exists as feature; Profession enum has no BARD): hire at Tavern,
   bard_play clips exist, morale/Coins bonuses, tavern music.
C. **New jobs, foundation for each** (from ui-proposal/future-loops-and-jobs.md): Healer (Infirmary — ties to
   permanent death unless hospital), Leatherworker (satchels/cart parts from Tanner leather), Beekeeper/Chandler
   (mead, candles), Charcoal Burner, Teacher (School), Stallkeeper (Market), Carter/Stablehand, Cheesemaker,
   Smokehouse Provisioner, Lamplighter/Night Watch, Caravan Master. Split into 3 agents:
   C1 Healer+Teacher+Lamplighter (life/defense), C2 Leatherworker+Charcoal+Beekeeper+Cheesemaker+Smokehouse
   (production chains), C3 Stallkeeper+Carter+Caravan (trade/logistics).
   Each: Profession entry (append ids), building/workstation, work goal loop using existing physical-work
   patterns, emblem/tech-tree unlock, texture/outfit, Blender clips via pipeline, GameTests.
D. **Tech-tree implementation** of the approved design (after owner reviews the preview page).

17. **Icon + texture pass (owner asked 26 Sep 01:35)**: job icons in UI (Codex ref `icons-jobs.png`), per-job outfits (`outfits-jobs.png`), hunter/butcher station, fix QA texture/label issues. Pair with item 6 (SettlerScreen) as one UI lane — start first when load < 70%.
18. **Revive GameTests** (ReviveGameTests, 9 batches prefix `revive`: down/revive/bleed/interrupt/noraid/kill/logout/login_refused/death_canceled; Codex findings fixed, JUnit 10/10) — run in the next serialized GameTest window. Also: raid aftermath should read `ReviveService.tally`.
19. **Shared-tree compile check** — at 01:40 the tree was red in the finisher client, Profession.java and WarehouseGameTests (lanes mid-edit). Verify green before any GameTest window.
20. **Blueprints lane** (running): 2 medieval variants per building type in the owner's town palette + previews.
21. **TAVERN ANIMATION REMAKE (owner 26 Sep 01:50: "lag nye tavern animasjoner … alle de skal du lage på nytt for å bli bedre")** — NEXT Blender lane when RAM < 75%.
    - Remake at the lumber-break bar: idle_innkeeper(+v2,v3), inn_welcome, eat(+v2), village_chat(+v2), the TavernServingMotion/HandPose serving walk, and PLAYING_MUSIC (bard lute).
    - New clips: pour ale at ale_tap, wipe the counter, carry a tray/mugs, seated drink + mug-lift toast, table cheer/clink (2+ patrons in sync), laugh/slap the table, tipsy sway on the walk home, dance jig (bard nights), arm-wrestle idle (optional), sleepy head-nod at closing.
    - Seated poses must sit on real chairs/benches (no floating). Props via the prop hook (mug, tray, lute). Keep contact ticks for sounds (clink, pour, footstep dance).
22. **LIVING VILLAGE lane (owner 26 Sep 02:30: "legg til ting å få ting innlevende")** — spawn as soon as an agent slot frees (30-agent cap hit). Scope, ambient only (no new event types):
    - player reactions (glance, wave, greet bark);
    - context barks via the thought bubble, rate-limited;
    - daily rhythm (morning stretch, meal gathering, evening at the Banner/tavern);
    - weather reactions;
    - raid cheer and mourning;
    - chimney smoke, birds and butterflies.
    Behind `[features] livingVillage`, with <2% work-time cost. Clip requests to the audit/tavern lanes; engine look-at. Build dir build-agent-alive.
23. Warehouse player marks (priority/excluded) are server-done, but the Work Scepter trigger is unwired: WorkZoneClient captures every scepter right-click. Needs the Work Zone owner or the bug hunter. Also: PostRaidUpgradeContractTest must cover wire ids 19/20 (builder) — the captain was told.
24. Command lane DONE (05:25), with R/G/J/K/N/H role keys, B strip, O summon, ground dots, salute. Pending: GameTests (FieldOrderGameTests, GuardSaluteGameTests, PlayerSummonGameTests) in the captain window; film command-menu.mp4 (script scratchpad/film_command.sh, world run/saves/Command-Film). Note: B clashes with Xaero "new waypoint" if the friends use Xaero.
25. Battle roles DONE (05:30): JUnit 815/0. Pending: 8 GameTests (BattleRoleGameTests) in the captain window, and a healer-revive GameTest. Follow-ups: courier supply of bandages/rune stones via the ledger; Ward translucent shield visual; settler sheet live charges/supplies; Shieldbearer raider (not built). The 13 role clips are approved and routed to the audit lane.
26. Events DONE (05:45): all 7 plus brute toll, JUnit 11/11. Pending: 12 GameTests (prefix event_), in the captain window. Crows not done (fox only). Raid-quiet rule interpreted as: no event during a raid, after a warning, or on the eve/day of an attack. ASK THE OWNER when awake: approve which of the EVENTS-IDEAS.md top 5 (stray dog, passing caravan, rival envoy, lost child, harvest-moon feast), as multiple choice.
27. Animation audit DONE (05:50): 184 clips audited, 27 fixed (farm till, chop, mine, hammer, head snaps, axe through the face). Report: qa-animation/audit.md. The remaining work moved to the new lane aa945c9f20dced2d9.
28. 15 professions were unhireable in survival (nodes implemented=false, no emblems). A trades-unlock lane is making them learnable behind [features] extendedTrades (default on). TELL THE OWNER; he can turn it off.
29. QA findings not yet routed: 15 Banner-lane items (QA-BUTTONS.md, incl. Q-013 GUI4 lost buttons, Q-003 readiness points at the removed Requests page); 6 settler UI items; Q-005 to command (lane finished → bug hunter later). Route the Banner items when the map lane finishes fj_230.
30. Map lane: figures DONE (06:10), clip at realm-map-figures.mp4. Polish seen in the stills, to do on resume:
    - Settlers page detail text clipped ("Live sheet availa…", "Waiting with y…", "Eira, may…", "No trave…"); use wrap or a shorter label.
    - The "+25" crowd tag overlaps the zoom controls at the top-right edge.
    - Summon missing on the stacked Settlers page (Q-010).
    - Film the summon line plus the Storage list, GUI4 and Esc checks in a 10-min slot after W1.
31. Economy (on resume): courier Hearth withdrawal is 1 item per 40 ticks (CourierHearthBagSession), which caps the '4 per chest trip' gain. Speed up the withdrawal too. From the bug hunter, BH-22.
32. ASK THE OWNER when awake (multiple choice): economy decisions from the economy lane (plan/state/economy.md):
    - idle crafter with no inputs: (a) tidy+study (recommended) (b) fetch only (c) help haul;
    - hunger rate: (a) 0.75 (current live default, recommended) (b) 1.0 (c) 0.6;
    - merchant buys crafted goods: (a) yes, purse grows with the village (recommended) (b) yes, fixed 12 (c) no.
    Also ask which EVENTS-IDEAS.md top 5 to build.
    Economy live defaults now (unsoaked): craft time x3, hunger x0.75, courier stow 4/cycle, starved-workshop priority. The CrafterWorkGoal self-fetch is only in a private copy.
