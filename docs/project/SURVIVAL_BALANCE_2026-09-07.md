# Survival balance contract — pressure, depth and recovery

Owner direction, 7 September: surviving should require effort, with Becastled-like preparation/defense rhythm and deeper connected village management. Root chooses the following targets for the authorized demo. **DECISION is not IMPLEMENTED or TESTED.** Apply through the existing production systems and ordered task cards; do not build another difficulty director.

Primary reference fact: Becastled's official store describes building by day, defending at night, and the economy supporting defense. This informs our rhythm, not an asset/code copy or an obligation to copy every mechanic: https://store.steampowered.com/app/1330460/Becastled/ . The depth comparison is Tobias's design preference, not a measured claim about another game's quality.

## 1. What difficulty must feel like

The village should be capable but vulnerable. A player who neglects food, ammunition, safe access or staffing should suffer visible shortages, weaker defense and recoverable losses. A player who improves those things should gain more capacity, safety and freedom. Do not instantly strengthen the next enemy because the player equipped a better sword or executed a clean defense.

Daytime decisions: food reserve or export; new resident or trained Guard; expansion or shorter safe hauling route; better tool now or Coins saved for recruitment. Night makes those decisions matter through ordinary Minecraft threats and, on announced raid nights, a stronger settlement attack. A quiet night can still require safe work routes and shelter; it does not need a large scripted raid.

Target three distinct outcomes using the SAME prepared attack and seed:
- **Underprepared:** weak positions, low arrows and food require player intervention; someone may be injured or goods lost. Clear warnings explain what failed.
- **Adequate:** one Guard plus one Archer and player help can survive the first five-actor raid using cover and resupply; do not expect them to effortlessly beat its captain/brute.
- **Optimized:** two Guards plus two Archers, good posts and stocked recovery should hold an ordinary raid without player damage contribution. Captains/larger threats remain consequential. A single Guard's separate five-ordinary-enemy target is a balance test with named loadout, not a promise against five elites.

Difficulty is rejected when the reason for loss is water spawning, doors failing, canceled bow shots forever, fake inventory ownership, unreadable alerts or a civilian panic goal on Guards. Fix those defects before raising enemy damage/count.

## 2. Explicit first changes and preserved baselines

| ID | CURRENT, source-audited | DECISION for implementation | Acceptance |
|---|---|---|---|
| B01 Early defense | First Watch follows Hospitality and four housed settlers; node/emblem each 4 Coins | Move First Watch to an optional sibling after Stores and Roads plus one actual Courier delivery. Keep 4+4 price, valid Barracks, dedicated Mayor and wire ID 6. Keep Arm the Watch's real equipment-delivery prerequisite unchanged. | A founder can become an early Guard before Tavern; normal economic branch remains reachable; no automatic first raid; old saves retain unlocks/quest baselines. |
| B02 First attack timing | First founding floor 4–7 nights; Balanced warning lead two nights; explicit readiness still required | For newly initialized BALANCED settlements only, use a founding floor of 2–3 nights and one-night warning lead. Preserve other profiles and every already saved/announced plan. No attack before real readiness declaration. | A competent first-hour/second-hour village need not wait additional multiple empty days; actual first raid still requires five settlers/beds, Guard, Archer, tracked arrows and the full existing food/equipment/readiness checks. |
| B03 Recovery | Normal stage has one quiet night after arrival; siege removes it | Start a saved cooldown AFTER RESOLUTION at every stage: 24,000 game ticks after victory, 48,000 after defeat, before any new warning can be committed. Keep the existing nightly scheduler; no second director or band while active/unresolved. | Victory/defeat, long-running battle and reload preserve cooldown; sleeping or changing day-time does not reduce elapsed game-time. No victory from unloading actors. |
| B04 Predictable danger | Nightly recurring roll at 13,000; 5–55% chance; omens can be independent of actual attacks | Retain current chance/caps for initial combat comparison. Show general risk separately from a COMMITTED incoming attack. Queue at an eligible nightly roll and arrive on a later night, at least 6,000 game ticks after the actual warning. After three eligible quiet-night rolls without a raid, queue the same warned production plan, rather than a surprise or separate spawning system. | No ambiguous omen labeled as a certain raid; no long unlucky silence with nothing actionable; one schedule/roster/receipt. Preserve existing published plans. Test repeated ticks, sleeps, reload and unloaded settlements; no offline backlog of waves. |
| B05 Reward for competence | Held raid adds 12 pressure; captain menace grows 0.15 per win and 0.05 per loss | First tuning pass: held raid adds 0 pressure; captain menace grows 0.05 per win, 0 on loss. Keep village-worth pressure growth and 2–9 recurring actor cap. Better equipment/layout must yield a real easier interval before expansion raises exposure. | Same village/loadout does not immediately jump a full difficulty stage after success; deliberate loss yields no reward and actual losses, so it is not profitable progression. |
| B06 Guard recovery | 80 HP, retreat at 30%, resume at 65%, max 4 HP per physical meal, six-block safe radius | Keep current numerical healing baseline until actual retreat/feed/return works. Do not boost max HP again as a substitute for sword timing, target selection or food access. | Guard with safe food can recover; one without food visibly requests it. Stance remains meaningful, no free heal on reassignment/reload. |
| B07 Recruitment pace | Ordinary 10–20 qualified minutes | New cycles 3–6 visible qualified minutes, versioned locked-trip compatibility; no Innkeeper prerequisite. | Unstaffed first Tavern -> real visitor -> paid recruit -> Innkeeper, without hidden waiting or arrival/price rerolls. |
| B08 Mayor loss | Mayor death clears the office, applies -22 morale and blocks appointment for 72,000 game ticks; early merchant requires a living Mayor | Retain the death penalty/mourning and lost boon, but permit a free replacement into a genuinely vacant office through existing authorized appointment/dismissal. The new Mayor has administrative authority; boon stays off until both inherited mourning and the normal 30,000-tick settling period finish. | A dead Mayor does not freeze manual income/emblems for an hour. No extra settler, free currency, repeated death penalty or unpaid living-incumbent swap. Unloaded incumbent remains incumbent. |

B03/B04 change persisted scheduling semantics: Terra owns implementation and a focused state/ownership review. Luna may extract cases or update an already specified label; it must not invent the migration. Root may keep one named tuning delta pending if testing disproves it; record the concrete reason and revised value in this document before another worker implements it. No broad easier-mode changes from a source audit alone.

Scheduling details: eligibility requires a loaded, valid settlement with an online participating player in the current supported presence radius. Do not run catch-up rolls for missed nights. Recovery uses server game-time, which stops when the world is not running; it may expire while players are elsewhere, but returning never causes an unannounced attack. A new warning is committed only during present eligibility, followed by its full 6,000-tick minimum. An already ACTIVE raid resumes its actual roster; unloading is not an escape or victory. Existing issued plans keep their saved dates; new schedules use a versioned field/default rule. Counter overflow or unknown/corrupt schedule state blocks that schedule with a visible reason instead of spawning an untracked band. Count the three quiet eligible rolls only after recovery, once per night, and reset that count on a committed plan.

B02 reachability decision, 8 September: source review found no production profile selector; genuine founding constructed PEACEFUL immediately before rolling its calendar. To make the chosen survival pacing reachable, initialize BALANCED only inside the successful new-founding transaction before its founding roll. Exact Hearth rebind returns before this change. Leave Settlement's constructor and saved-data PEACEFUL fallback unchanged so existing worlds, explicitly saved profiles and legacy calendars retain their identity. This is a recorded decision and reviewed Temp proposal, not yet an integrated or tested change.

B02 affects new-plan validity as well as random selection: inspect `prepareAtFounding`, `initializeAtFounding`, validators and saved version/migration together. A new floor of two must not later fail a legacy validator expecting four. Preserve old scheduled dates and profile semantics; test old and new loads, not just the selected constant.

B08 source owner is `settlement/Mayor.java` plus its real appointment UI/payload and tests. `Mayor.appoint` already handles same-settlement membership, dismissal/return refusal, an unloaded incumbent and free genuinely vacant office. Preserve those checks. Only confirmed vacancy permits succession during mourning; `find()==null` is not vacancy. `activeBoon` must also honor existing `mourningUntil`; do not clear it when appointing. The successor occupies the dedicated Mayor role and cannot simultaneously work. If no living resident remains, show settlement defeat and support the ordinary new-settlement route; do not silently respawn founders into the old settlement or erase its builds/saves.

First Watch's old `first_watch/housed_settlers` baseline may load as inactive history; the new courier objective gets its normal baseline. Never clear all progression to migrate this one node. Having an early Guard may require temporarily assigning one of the three founders to defense and supplying food manually. UI explains that staffing tradeoff. The economic route remains Lumberer/Courier/Farmer followed by recruitment; Mayor never changes job.

Explicit B01 persistence case: a real qualifying Courier delivery is recorded; the same founder may later become Guard through supported reassignment without erasing the recorded delivery or unlocked node. A role name alone must not create the delivery proof. B07 uses 180–360 qualified seconds through the existing once-per-second Hearth owner, pausing without reroll while invalid/unloaded; legacy locked cycles retain their explicit original targets.

## 3. Finite economy and the full cost to reach six roles

Preserve current Coin prices while measuring the actual route. The ordinary example below assumes no premium recruits or Dining Hall, a first unstaffed-Tavern recruit priced at 4, then two NEW visitor quotes at 3 each after an Innkeeper grants its 25% discount. An already frozen older quote does not become cheaper retroactively.

| Milestone | New Coin spend | Cumulative |
|---|---:|---:|
| Timber Rights + Lumberer emblem | 2 + 2 | 4 |
| Stores and Roads + Courier emblem | 2 + 2 | 8 |
| Cultivated Ground + Farmer emblem | 2 + 2 | 12 |
| Home | 4 | 16 |
| Hospitality / Tavern | 4 | 20 |
| First ordinary recruit + Innkeeper emblem | 4 + 2 | 26 |
| First Watch | 4 | 30 |
| New ordinary recruit + Guard emblem | 3 + 4 | 37 |
| Arm the Watch + new ordinary recruit + Archer emblem | 0 + 3 + 4 | **44** |

This excludes real construction, tools, arrows and food, which must also be obtainable. It is a reachable-cost example, not a guarantee every visitor is ordinary. Explain premium prices and allow waiting for another suitable visitor. The optional early-defense branch changes spend order, not total price. Two more ordinary martial recruits/emblems for 2 Guards + 2 Archers add 14 under the same discount assumptions.

Actual first merchant uses eight sales per published row and no restock. Basic logs: first four trades cost 34 logs for 4 Coins; all eight cost 76 logs for 8. Fine logs: first eight cost 44 logs for 8 Coins. This means workers fund meaningful acceleration while player Basic output remains a bootstrap/recovery option.

Current `EarlyCoinMerchant` cadence is 24,000 ticks on site, next visit eligible 30,000 ticks after publication (20/25 minutes at 20 TPS), with persisted site receipts and a real safe approach. Keep this for the first connected income test and SHOW the next availability/blocked route. Before accepting 1–2 hour self-sufficiency, prove that actual offered rows, worker output and visitor cadence can fund the 44-Coin path. If finite sale capacity is the bottleneck, root first measures eligible goods left and empty minutes; tune a single visit cadence or add one actual supported local crop row, not free money, unlimited restock or a new Trader profession. Record the selected changed value and replay tests before implementation.

Target: production sales are the largest repeatable income source across two raid cycles; Tavern adds useful small service income; 8-Coin raid victory is a meaningful bonus, not the best farm. Food spent on recovery remains part of defense cost. Track earnings by source, spending by sink and actual lost/consumed stock. Do not lower the existing 8-Coin reward without that evidence.

## 4. Loss, repair and anti-grind rules

- Losing a citizen costs experience and replacement Coins; it must not delete the settlement's learned unlocks. Mayor succession follows B08: real vacancy, real surviving citizen, inherited mourning and preserved administrative/ownership checks; never a new Mayor on every reload.
- Raids target real actors/resources through existing objectives. No duplicate Coin drops, arbitrary deletion of all storage, automatic destruction of every bed, or repeated spawn next to the Hearth.
- After defeat, clear the resolved attack correctly, show casualties/lost goods/blockers, restore safe civilian work and give the promised recovery interval. Recovery uses actual production/manual sales, not a forced free-wallet bailout.
- One failed meal causes a warning and recoverable efficiency/needs effects, not an instant death spiral. Keep food reserves meaningful, but use actual edible units consistently across recruitment, Tavern and recovery; a raw crop is not automatically a prepared meal.
- Strong positions help. Ranged sight lines, entrances and route lengths should matter. Do not add wall-clipping attacks or unannounced siege equipment to negate good building. A wholly unreachable raid needs a truthful bounded lifecycle outcome, never a silent victory or an immortal orphan.
- Save/rejoin and a friend's arrival do not reroll offers/quotes/threat, mint a purse, reset recovery, or immediately double an announced attack. Scale new plans from village exposure, not a joining player's equipment or all online players across the server.

## 5. Measure a play experience, then change one cause

Use a single normal-world progression record with time, stage, spendable Coins, edible stock, bed capacity, productive workers, available actions and next threat. Label assisted setup separately. Add the same prepared combat in the three readiness states from section 1. A small number of targeted repetitions can establish direction; it cannot prove a statistical win rate.

Reject pacing when there are repeated stretches of more than about two minutes where the only possible progress is watching a hidden timer. Do not reject peaceful time in which the player can build, explore, plan or enjoy functioning production. Judge waste and uncertainty, not whether the player is continuously clicking.

Change order: broken behavior -> unclear blocker -> missing reachable input -> duration/cadence -> rewards/prices -> encounter size/damage. Keep the previous comparison and changed number. Do not adjust all six at once; then neither a small model nor a playtester can identify what improved the game.

The desired result is demanding but learnable survival: preparation buys safety, safety lets you expand, expansion exposes new logistics/defense choices, and a well-run village visibly rewards the work invested in it.
