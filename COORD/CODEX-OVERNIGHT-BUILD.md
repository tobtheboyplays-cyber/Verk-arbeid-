# Codex overnight BUILD tasks (owner-approved at 00:28, 27 Sep)

The owner decided: "Codex bygger i natt, jeg (Main Claude) lander i morgen".
Codex builds in its OWN PRIVATE COPY and never edits the shared tree. In the morning MAIN Claude reviews, lands, runs the tests and makes a new candidate.
Run this IN PARALLEL with the overnight test mission (CODEX-OVERNIGHT-PROMPT.md). The testing of the frozen candidate stays priority 1.

## Setup
- Private copy: C:\Users\tobia\Hearthstead-Claude\codex-build\proj\hearthstead-neoforge (copy FROM the shared tree `Verk-arbeid-\hearthstead-neoforge` at the 00:20 freeze state).
- Build with ./gradlew --max-workers=2. Full JUnit must be green. Write GameTests for every change, and run them in your copy.
- Deliver per task: a unified diff (codex-build/patches/<task>.patch), the list of new files, the test results, and a short README. NO landing into the shared tree.
- All mod text is in English. Update the handbook for any player-visible change (guard tests enforce it).

## B1. Builder "needs" sheet, like MineColonies (owner: "jeg vil ha et ark jeg kan shift right clicke en builder å se hva han mangler som i minecolonies")
- Shift + right-click (empty hand) on a settler whose profession is BUILDER opens a one-page "Builder needs" sheet instead of the inventory. Other professions keep Shift → inventory. Add an "Inventory" framed button on the sheet so his inventory stays reachable.
- The sheet has NO tabs and NO scrolling (the owner hates both). Size ≤ 464x256 GUI px; use the Ui2 widgets and the framed buttons (client/ui2/Ui2Button). The Banner UI is the style standard.
- Content:
  - the current site name + stage + progress done/total;
  - a MATERIALS NEEDED list: item icon, name, needed / in hut / in warehouse / on the way / MISSING, with missing rows in red;
  - blocks that "need a hand", with coordinates;
  - the status line (e.g. "Waiting for 2 Oak Stairs").
  If the list is longer than fits, show the top N by missing amount plus "+K more" with a tooltip; never scroll.
- The data already exists: BuildJob bill, the WAITING_FOR/NEEDS_PLAYER status args, VillageSupply, the Builder UI Sites/"missing stock" (settlement/builder/**, network/Builder*, client/builder/**). Reuse the existing sync payloads where possible; if a new S2C payload is needed, send it on open and throttle refreshes.
- Server: validate distance, membership and permission, the same as the inventory open in SettlerEntity.mobInteract.
- Tests: a JUnit layout test (fits 464x256 with no overlap) and a GameTest (a builder with a short bill → the payload lists the missing item with the right counts).

## B2. First raid on a timer (owner picked; REVISED 00:40: "Kan komme raid før vakter, men hold den mulig å fullføre")
- The first raid is scheduled automatically about 3 in-game days after founding, EVEN WITHOUT guards or a Barracks. Keep the player-confirmed early path as it is.
- It MUST be winnable. Scale the first raid to the village's actual defence:
  - with no guards or archers, a small raid that 2 players with basic gear can beat (e.g. 2–3 of the weakest raiders, no brutes or heavies);
  - scale up only with guards, archers and walls.
  - Keep the existing escalation for later raids.
- Before it arrives, give a clear warning (town chat + the raid warning HUD) with enough lead time, e.g. one in-game day ahead plus a sunset warning.
- Settlers without a combat job flee or shelter (the existing panic/shelter behaviour). The raid must end cleanly: raiders retreat after a budget, there's no endless siege, and rewards/recovery work.
- Code: RaidLifecycle.java:715,723-741; RaidDirector.java:776-795; FirstRaidReadinessService.java; the raid size/escalation code.
- Config values: [raid] firstRaidAutoDays = 3 and firstRaidMinimalSize.
- Update the handbook raid page ("the first raid can come before you have guards; it's small, so defend with your own swords").
- GameTests:
  - with no guards, the timer fires and spawns the small raid, and two armed test players (or a scripted defence) can resolve it;
  - the raid ends by budget if the players ignore it (no stuck state);
  - with guards, the size scales;
  - the existing readiness and raid tests stay green.

## B3. Smaller food reserve + starter Coins (owner picked)
- The first-raid/recruitment meal reserve drops from 8 per settler (40 at 5) to about 16 total before the first raid. Look at RecruitmentPolicy.java:31-34,192-197,244-249 (RESERVE_DAYS) and FirstRaidReadinessService.java:687-692. Keep the later-game reserve sensible, and make it a config value.
- A new village starts with 5 Coins in the Hearth/Banner stores, as a config value [start] startCoins = 5.
- The EarlyCoinMerchant's first visit comes right after founding (EarlyCoinMerchant.java:43,69-72). Keep the player-proximity rule for later visits, but not for the first.
- Update the handbook, and write GameTests for each.

## B4. No lock-in below 3 settlers (owner picked)
- Hospitality's objective counts ALL living settlers, not only housed ones (DevelopmentNode.java:70-71 HOUSED_SETTLERS 3 → population 3).
- VillageSupply.canMake counts ONLY workshops/recipes the village has actually unlocked (and staffed, if that's how it works), so the Builder never waits for material nobody can make yet. It then falls back to the "needs X" player line.
- Tests: a village with 3 settlers (not all housed) can learn Hospitality; a Builder with glass in the bill and no unlocked glass workshop reports "needs Glass" to the player instead of waiting on a crafting order.

Report progress in COORD/CODEX-OVERNIGHT-REPORT.md under a "BUILD" section: per task DONE/PARTIAL, the patch path, the tests and the risks.
