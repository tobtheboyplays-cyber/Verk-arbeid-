# Hearthstead connected mechanics — implementation decisions

## Accepted owner answers — 16 September 2026

These explicit answers supersede conflicting older design targets; they do not
prove implementation. Work resumed after all ten answers.

| Question | Accepted choice | Binding direction |
|---|---|---|
| 1 | A + C | Repair the entire work/transport/supply/defense loop, emphasizing workers, animations and Tavern. |
| 2 | A | Aim for the first proper raid around 60–90 minutes from a fresh settlement; measure actual survival pacing, preserve readable readiness/warnings rather than punish an unready player with a hidden timer. |
| 3 | A | Prepared defenders can handle ordinary attacks; player intervention matters under pressure. |
| 4 | A | Defeat causes meaningful losses with a clear playable recovery path. |
| 5 | A | Player chooses workplaces/priorities; citizens handle ordinary logistics, meals and rest. |
| 6 | A + B + C | After core acceptance, include goblin theft/trade visits, disputes/suspicious visitors and traveler requests/rewards in small complete slices. |
| 7 | B + A | Primarily grounded medieval life, restrained goblins, dry humor and limited mystery; no broad high-fantasy pivot. |
| 8 | A | First real co-op target: 3–4 simultaneous players sharing one settlement. Verify shared actions, contention and rejoin, not just idle connections. |
| 9 | A + B + C | Long-term production/crafts, defense/enemies and village life/trade all remain in scope; select order by useful dependencies after existing gameplay works. |
| 10 | Use the rest | Remaining included weekly quota authorized, replacing old reserve limits; no purchase/reset/payment change. |

PC use for controlled project tests is explicitly authorized. Preserve actual
worlds. Root may implement fitting reversible ideas and fix encountered defects
without another routine approval. QUALITY_STANDARD.md and JOB_STANDARD.md are
the minimum acceptance contract for every addition.

Decision owner: root, under Tobias's 7 September mandate to settle the major mechanics for lower-cost implementation models. This document specifies the target, not completed functionality. Existing behavior is preserved where it already satisfies these decisions. Every discrepancy becomes a named task with evidence; do not rewrite entire systems or reset saves.

Use with `PROJECT_STATE/48H_DEMO_RUNBOOK_2026-09-07.md` and its task cards. These decisions supersede conflicting old design proposals for the six-role demo. New professions and production families remain post-demo. The full overhaul goal remains wider than the demo.

## A. The experience we are building

The player is the village's builder, organizer and defender. Initial manual gathering establishes a useful automated settlement. Later player effort improves layout, supply reserves, equipment, specialization and defense. Optimization should reduce recurring problems and produce visibly better results. The challenge is diagnosing and fixing understandable shortages and threats, not repeatedly fetching arbitrary items for an opaque timer.

The connected loop is:

`Hearth + player-gathered Basic goods -> first manual sale -> physical Coins -> first workplace unlock + job -> worker-made goods -> quality-aware sales -> more physical Coins -> recruits/upgrades/defense -> raid/shortage recovery -> a better village`.

Latest owner direction: **Becastled-like survival pressure with MineColonies-like connected depth**. Use `docs/project/SURVIVAL_BALANCE_2026-09-07.md` for the explicit difficulty, progression, warning and recovery contract. Its tuning values are targets requiring measurement, not claims of shipped behavior.

Food supports work, recovery and Tavern trade. Housing supports recruitment. Warehouse layout supports throughput and treasury access. Guard/Archer protect the labor and goods that fund growth. Tavern supports recruitment, paid hospitality and recovery. Skills/tools improve quality, which makes labor investment economically visible. Every demo building/role must connect to at least two other systems through a real input/output or constraint.

Difficulty should create a solvable decision: low stock, long route, wrong tool, exposed approach, unsuitable staffing or insufficient housing. UI shows cause, consequence and an actionable fix. Do not secretly destroy resources, add invisible failure rolls or increase the same requirement whenever the player catches up.

### Pacing targets, to measure in an actual fresh survival route

| Stage | Target experience | Implementation condition |
|---|---|---|
| First 5–10 minutes | Establish Hearth, identify Mayor, see next build and how to earn first Coins | Obtainable plans/recipes, local Basic goods and reachable merchant; no paid-worker prerequisite |
| First 15–30 minutes | First worker turns real materials into useful output; player sees a meaningful improvement | First employment/unlock affordable from finite manual trade; understandable tool/zone setup |
| First 30–60 minutes | Production, food and courier routes connect; recruit or equip the next useful person | No circular Tavern/staffing or quality/income prerequisite |
| Around 1–2 hours | Small self-supporting village; player optimizes rather than repeats routine hauling | Food/material reserves and tools genuinely sustain operation; shortages remain recoverable |
| First raid | Readiness milestone, visible warning, ordinary combat, reward/report and repair | Trigger from production progression, never a silent fixed-time punishment on an unready new village |

These are tuning targets, not forced delays. If setup consumes the target through unexplained clicks or waiting, simplify feedback/availability before increasing rewards. A fast player may advance faster. Never hold a satisfied milestone for a hidden minimum timer. The explicitly visible, bounded visitor travel/qualification window in section C is a supply cadence, not a blanket delay on earned unlocks.

## B. Coins: one physical currency, one accounting model

### B1. Identity, storage and authority

- Display name is **Coins**. Preserve the existing registered item ID (`ModItems.GOLD_COIN`), not a second denomination or replacement currency.
- Preserve supplied round gold/square-hole/four-mark design and sharp 32x32 transparent texture. Economy changes must not reintroduce alternative coin items.
- A balance shown in UI is a sum of actual, currently accessible Coin stacks. It is not a saved wallet number that can drift from inventory.
- Early storage: bound Hearth inventory. Later storage: valid, loaded, settlement-bound Warehouse containers. A clear treasury label/filter identifies where the player can put Coins; do not require a new vault building to start playing.
- A paying player's own inventory is included only in that player's authorized transaction. Never debit another player's inventory. Preserve current deterministic payer -> Hearth -> eligible Warehouse order, and show the same order/total in the UI.
- Inaccessible/unloaded/foreign containers are not spendable. No forced chunk loading or whole-world inventory scan. Bound searches and deduplicate container/slot identities, including double chests.
- Pending delivery after raid/service is an unspendable entitlement until physical Coins are inserted. Show `Reward waiting: storage full`, not an inflated spendable balance.

### B2. Where Coins enter and leave

| Source | What creates value | Bound |
|---|---|---|
| Visiting merchant buys local goods | Real goods leave player inventory, physical Coins arrive | Persisted offer uses, quality, demand; each published purchase row has eight uses, no restock |
| Nonresident Tavern guest buys service | Actual guest order receives real food/drink | Finite persisted visitor allowance and one paid receipt per delivered order |
| Won raid | Production raid lifecycle commits a victory | One receipt per first raid/recurring serial; physical payout or saved pending delivery |

Primary sinks: recruitment, one-time development unlocks/job access and useful merchant purchases. Physical building materials, food, arrows and tool replacement remain resource costs, making production matter. **No daily Coin wages/upkeep in this demo**: do not add a passive bankruptcy timer to make Coins relevant. No player coin crafting, duplicate quest payouts, free login Coins or unlimited startup reward.

### B3. Price baseline: preserve a coherent small-number economy

17 September supersession: owner requested a whole-economy revision. The
preserve-prices instruction below is a historical baseline. Current code has
Basic1use and Lumberer emblem1Coin, unlike portions of this old table. Follow
[the new economy review](ECONOMY_REBALANCE_2026-09-17.md) for the proposed model;
no proposed price/cadence is implemented or playtested yet.


Keep the current early-game price baseline unless a measured fresh-play result fails pacing. Price changes update one server-owned catalog/calculator and every quote/tooltip; never tune independent UI copies.

| Item sold | Basic goods / 1 Coin | Fine | Superior | Exceptional |
|---|---:|---:|---:|---:|
| Supported local logs | 8 | 4 | 3 | 2 |
| Supported crops | 16 | 8 | 6 | 4 |
| Planks, **only when an actual offer exists** | 24 | 12 | 8 | 6 |

The table follows current `GoldCoinTrades.purchase`: ceil(base / quality multiplier 1,2,3,4). It does not claim every item is currently offered. Enumerate actual published goods separately; do not expose a promised crop/plank sale the merchant never offers. Unsupported specialty resources need an explicit price entry, not accidental fallback to the log price. Audit iron's current fallback before making it a primary income route; abundant renewable production should remain the main engine.

Current visit pressure is `+ floor(completed uses / 2)` goods to the next quote, with the existing finite offer-use limit. At the Basic log baseline, the first eight costs are `8,8,9,9,10,10,11,11` = **76 logs for 8 Coins**. First four are **34 logs for 4 Coins**. Preserve offer identity, use count and pressure across reopening/save. A new visitor brings a new finite market; reopening one does not reset it. Quality increases return, while repeated sales of the same offer reduce marginal return. UI shows the exact next input/output, not an ambiguous percentage.

Recruitment base prices remain **4 / 6 / 8 Coins**, derived from the actual traveler's frozen starting-aptitude tier. Show the attributes/reason for premium. Apply supported discounts once on the server and freeze the effective trip quote. Do not roll a new price on reopen, standing up, save/load or name change.

Keep the verified first employment route within **4 Coins total** for the initial development unlock plus job emblem (currently 2 + 2 for Timber Rights/Lumberer). This is payable from the first finite Basic-log offer. `DevelopmentNode.costs()` overrides the old constructor material lists: Timber Rights / Stores and Roads / Cultivated Ground cost 2 Coins each; Home / Hospitality / First Watch cost 4 each; other nonempty nodes cost 6; empty-cost nodes remain free. Civilian emblems cost 2, Guard/Archer emblems 4. Preserve those runtime prices for the first balance pass. Physical construction still consumes real materials; do not accidentally charge the inactive constructor material lists as an additional unlock cost. Keep UI quotes tied to the same accessor.

Current raid baseline is **8 Coins per committed victory**: four civilian emblems, two martial emblems, or one undiscounted 8-Coin recruit. Keep it bounded and once per raid, independent of player kill ownership. Do not also mint duplicate Coin drops per raider. Balance raid strength with actual defense tests, not the income needed to reimburse impossible losses. Do not reduce recurring rewards merely because a discarded proposal suggested 4; measure the actual income mix first.

Tavern baseline: an actual paid meal costs **1 Coin** from the visitor's **three physical Coins**, issued once at creation by `TavernGuestPayment.initializeTraveler`. `receive` transfers one Coin only at real receiving contact and only if the meal can begin; a resident meal is not outside income. Keep the saved purse and existing meal/cooldown ownership. A drink purchase is a future supported-order extension if no real drink exists yet; do not label a prop as a paid drink mechanic. If order semantics change, retain persisted old orders and migrate explicitly. No passive Coin per tick or refill on seating/reload/recruitment.

### B4. Quality, labor and fair optimization

Player-created/gathered goods are Basic by default and remain saleable. Village production creates at least Fine output through the actual worker production completion. Better relevant skill/tool/workplace can yield Superior and rarer Exceptional goods. Preserve current tier thresholds until a measured balance change is justified. The output quality is decided once from the actual completed production action and persisted; reloading, passing through a Courier, dropping/re-picking or moving between inventories never rerolls/upgrades it.

Keep quality as item metadata with compatible stacking; do not replace each vanilla material with a different village-only item. No secret owner/village-origin gate. A quality item remains useful if traded to a friend. Courier transport preserves quality and quantity. Recipes without an implemented quality-aware production rule create Basic output; crafting/decrafting cannot launder quality or multiply Coin value for free. Do not silently consume a high-quality stack in a lower-quality sale: offer the appropriate visible quote/selection.

The economic benefit must be visible: compare 8 Basic logs and 4 Fine logs for the same first Coin. The player can improve tool supply, layout and skills to raise quality/throughput. Avoid trivial infinite buy-low/craft/sell arbitrage; test each implemented recipe cycle using actual material inputs, offer budget and demand.

### B5. Transactions and failure behavior

All economic mutations occur on the server. A quote/request carries a stable transaction ID, actor/settlement/target IDs, price basis and version. On action: revalidate authorization and current prerequisites, reserve what must be exclusive, confirm physical source/destination capacity, commit exactly one outcome, then publish effects/UI. Replayed packets return the existing outcome or fail clearly without another charge/grant.

Payment, bed claim and recruitment cannot each succeed independently. Use existing `RecruitmentTransaction` ownership/rollback path; do not create another ledger. If the bed/guest/funds disappear before commit, preserve the money and leave a recoverable explanation. Whole-inventory rollback must not overwrite unrelated concurrent purchases. Server-thread serialization does not excuse stale UI or repeated packets. Persist receipts and pending physical delivery through supported save boundaries; crash safety is a separate test, not implied by clean restart.

Mandatory economic scenario matrix:
1. 8 Basic logs -> 1 Coin; correct inventories and offer use after reopen/restart.
2. 34 Basic logs -> 4 Coins -> first 2+2 unlock/job purchase; zero double debit.
3. Real worker Fine output -> actual unload -> better sale; movement/reload preserves metadata.
4. Recruit 4/6/8 tier; reject missing bed/insufficient funds/foreign actor; commit once under duplicate click and two-player contention.
5. Split player/Hearth/Warehouse payment; reject inaccessible/foreign sources; double chest cannot double-count Coins.
6. Food handover + guest order -> one payment; interruption before/after transfer and full treasury never duplicate food/Coins.
7. Victory ->8 pending/physical Coins; full storage/reload/replay delivers exactly once.
8. Exhausted merchant offer stays exhausted; new visitor is finite and earned through its normal appearance rules.

Conservation equation for audit: closing physical Coins + legitimate pending entitlements = opening physical Coins + committed external earnings - committed sinks. Movement between inventories is neither earning nor spending. Record actual event/transaction identities so the equation cannot count the same reward twice.

## C. Progression and dependency graph

Preserve the dedicated Mayor and the currently authorized starting cohort; do not add free settlers to hide a staffing deadlock. Mayor cannot become a worker/combatant. First merchant access must not require a paid job, Fine goods or paid building unlock. Basic manual sale bootstraps employment.

Required graph:

`Hearth -> Basic manual sale -> first workplace/job -> wood + food + transport -> housing/Tavern -> recruitment -> staffing/defense -> readiness + warning -> raid -> aftermath/improvement`.

Current baseline staffing is one dedicated Mayor plus three hireable founders. The reliable economic route is Lumberer -> Courier -> Farmer, then a valid House/Lodging and Tavern, then the first recruited visitor becomes Innkeeper. Later recruits supply Guard and Archer. An unstaffed Tavern MUST attract/admit that first visitor; an Innkeeper or a purchased meal cannot be a prerequisite for it. Innkeeper adds service and the current 25% recruitment discount, with supported Dining Hall discount adding another 25%, capped at 50%; use the actual frozen final quote.

**Visitor pacing decision for D6:** new ordinary cycles use a visible deterministic **3–6 qualified minute** window instead of current 10–20 minutes. Reuse the existing cycle identity/timer, with progress paused on lost eligibility rather than rerolled. Preserve already locked legacy trip targets and accumulated progress with an explicit version/migration; no new traveler on reload. The UI shows readiness blockers, accumulated progress and expected arrival window. Existing Call to Arms stays a separately priced/defined 4–8 minute policy until reviewed; do not advertise it as a speed boost over the new ordinary cadence. Its recruitment-quality benefit, if any, must be truthful. No activity-acceleration subsystem or Innkeeper prerequisite is added.

Time semantics: the new target is **180–360 qualified seconds**, with at most one increment per existing one-second Hearth service opportunity while loaded and eligible. It is not milliseconds, one increment per game tick, or offline wall-clock accrual. Invalid/unloaded periods do not advance it or reset/reroll accumulated eligible progress. Avoid a second ticking owner. One cycle may publish at most one actual traveler and its frozen quote.

Audit this graph in source. **A valid unstaffed Tavern must have a reachable way to receive/recruit its first traveler.** Do not require hiring an Innkeeper from a visitor pool that itself requires an Innkeeper. Staffing enables better/automatic service and hospitality, not the only way to break the first-recruitment cycle. If reassigning an existing founder is an intended alternative, UI must explain it; it cannot be the only hidden escape.

Plans/buildings/jobs show their complete prerequisite chain and reward. Avoid duplicate Coin gates for plaque, plan and emblem unless each buys a different visible benefit. Emblems/licenses follow current item semantics and survive ordinary supported reassignment; do not add repeated hiring fees silently. All citizens can learn any non-Mayor role; traits provide advantages without making ordinary settlers useless.

Upgrades need a real effect: capacity, range where appropriate, quality eligibility, tool access, beds/seats/storage or efficiency. No dead upgrade whose only result is a larger number in UI. Preserve later production-chain hooks using existing work/task/provenance contracts; do not implement Trader/Blacksmith/new jobs in the six-role demo.

## D. Buildings, space and navigation

Rooms are meaningful workplaces, not a bed+chest ritual. Every required prop must support a behavior or a clearly shown structural need. Use current `RoomScanner`/building type rules as the authoritative base; consolidate conflicting checks rather than making another validator.

Functional requirements:
- House: reachable enclosed/dry room, valid beds, safe doorway/aisles and light. Capacity equals valid exclusive beds.
- Warehouse: usable bound containers and walking/access space. Store real materials/tools/Coins; do not demand a cosmetic prop that workers never use.
- Lumber Camp: work storage/tool access, reachable authorized tree zone and bag parking.
- Farm: usable crop zone, seed/tool storage and accessible work positions; no room validity claim from unrelated farmland elsewhere.
- Tavern: reachable room, tables and real seats, service/food storage, lighting and an aisle Innkeeper/guests can use. Capacity is actual available seats, not a decorative counter.
- Barracks/Watchtower: equipment/ammo access and reachable duty positions with useful sight lines. A fully sealed tower is not a viable firing position.

Before changing numerical area/prop requirements, compare existing valid saves and current demo geometry. Make the new minimum explicit in one rule table, render exactly that table in UI, and show what is missing. Preserve old goods/residents if a room becomes invalid; suspend new work/recruitment gracefully, never delete people/items. Do not silently grandfather invalid safety geometry.

Navigation: wooden doors/usable pressure plates/stairs and legitimate open trapdoor routes should work. Repath with cooldown and bounded alternate approaches; do not break player builds or phase through blocks. Lumberer may remove explicitly permitted blocking leaves in its own work area. Report `Path blocked` with location/retry rather than infinite movement animation. A dry spawn does not prove an approach route exists: include coast, hill, doorway and blocked-route tests.

## E. Workers, needs and logistics

Work states: assigned -> check prerequisites -> reserve source/target -> travel -> perform physical work -> collect -> deliver -> release claims. Interruptions retain enough saved context to resume/recover. Prefer the existing WorkerLifecycle and bag/task contracts; no new manager per role.

Tools: worker obtains serviceable available tools, self-crafts only with actual recipe/material access, and requests missing supply clearly. Tool durability matters; reassign/reload cannot duplicate or restore it. Requests deduplicate and display actual blocker. Ordinary player role becomes building, prioritizing and exploring once supply works.

Shared bag: visible world-fixed put-down/fill/empty/lift phases for every sack job, with cargo owned once. Work/contact and item transfer trigger sound/animation at the same confirmed event. Large cargo changes gait modestly; legs still locomote and hands grip the frame. No inventory teleport hidden by a long emote.

Food/housing shortage escalates: early clear warning -> reduced efficiency/needs -> serious prolonged consequences. It is recoverable after supply returns. No instant death/unemployment cascade from one missed meal. Meal consumption is physical and bounded. Priorities/reserves prevent the last food being sold while villagers starve; manual sell remains possible with a clear warning, not hidden confiscation. Future automated Trader must respect player-approved offers and reserves; that job remains later.

## F. Defense, danger and recovery

Guards are valuable trained defenders. Starting equipment is wooden sword, no free iron/shield. Aim for an ordinary Guard to hold roughly five ordinary attackers under a named tested configuration; not every captain/brute/terrain combination. Two Guards + two Archers should handle ordinary raids, while captains/larger bands can require player help. Attackers threaten the player enough that positioning behind defenders matters; avoid arbitrary instant kills.

Orders remain meaningful: protect chosen priorities and hold/defend/retreat behavior. Civilians react to a visible threat by seeking shelter and producing a bounded call for help. Guards respond with purposeful running and weight, not civilian panic. At low health the chosen stance determines retreat; recovering guards consume real food, heal a bounded amount over time, then return only after recovery threshold. No instant heal on hire/reload or free infinite regeneration.

Early opposition: preserve existing thief/public-order functionality if present. A bounded attempted theft is the preferred small early Guard purpose, already requested by Tobias, but implement only after the core defense/worker loop passes and without adding another profession or a whole social simulation. Thieves target real surplus, use existing alerts/ownership, and leave a recoverable loss/trail; do not steal the sole starter resource or wipe the treasury without warning. Record as unmet requested work if not implemented; do not advertise it from a concept.

First raid requires actual readiness and a clear warning. Player gets useful preparation decisions: move posts, supply arrows/food, secure doors and protect workers. Later raids remain natural threats bounded by progression and a real recovery interval. No endless attack queue while recovering from the same defeat. Tune by measured event/idle time, not a cosmetic timer.

Raid lifecycle: warning -> published participants -> approach/combat -> resolved victory/defeat -> report/physical reward/repair -> saved aftermath. Each actor belongs to the actual raid. Death/unload/reload and fleeing are handled explicitly; a lost actor must not keep a raid active forever, and unloading must not award victory. Once-only reward receipt and source-of-truth roster survive save/load.

## G. Tavern, identity and presentation

Travelers walk in from outside, look like visitors, trigger one restrained bell/notice, and retain a trip/quote identity. They can sit and spend a finite allowance without becoming residents. Recruitment is direct `Recruit Traveler`, with price and free-bed condition visible. Better aptitude costs more; ordinary visitors remain useful recruits. No GuestA/B already-resident substitution in acceptance.

Innkeeper has useful actual actions: greet, find serviceable order, collect food/drink, carry/set/slide a serving, clean/recover props and return to service. Hum/sing only sparsely. It must not play a satisfying delivery when the food transfer failed. Guest/seat/order claims are exclusive; interruption and dismount return claims safely. Player can sit on the requested chair. Building/bed status uses yellow/green/red plus text/icon, not color alone.

UI communicates `what is happening -> why -> next useful action`. Preserve the approved Minecraft-like Home/Plaque direction. No Survey again label, fake counters, invisible costs, duplicated action names or long confirmation chains. Coins, tools, work zones and blocked routes should be discoverable where the player acts. Blessings use direct card click, rarity indication and confirmed selection animation; animations do not grant an effect the server refused.

## H. Prevent boredom without a new content explosion

During the first hour, aim for a meaningful player choice every few minutes: place/adjust a building, choose a worker, improve a route, handle a shortage, select a sale, prepare defense, recruit, or inspect an upgrade. Do not manufacture trivial prompts to satisfy this metric. Production should keep visibly working while the player builds/explores. A comfortable period after successful optimization is a reward, not automatically a problem.

Use existing systems to create overlapping decisions: a visitor wants food while reserves are low; better output earns an upgrade but a new bed/recruit competes for Coins; guard resupply competes with an expansion; after a raid, repair routes and restore food before expanding. The UI supplies enough information to choose. Do not force a random calamity every few minutes or repeatedly undo completed work.

Playtest record: minutes with no useful available action; time to first employed worker/first quality sale; reason for each blocker; Coins/material totals at each milestone; guard/worker losses; whether player intervention improved throughput or safety. Fix the largest actual friction first. Difficulty must be attributed to choices/resource constraints, not defective pathfinding or missing feedback.

## I. Small-model implementation contract

For every change, the supervising model produces a packet:

```text
Outcome: one observable player behavior.
Decision IDs: sections of this document that define it.
Current evidence: one actual artifact and exact defect; current vs target values.
Owned files: explicit list. Read-only dependencies: explicit list.
State/ownership: who owns every item/claim/transaction before and after.
Transitions: success, missing prerequisite, interruption, reload, repeated input.
Do not change: stable IDs, unrelated behavior, source/runtime ownership.
Acceptance: one positive, one relevant failure/interruption check, actual UI/motion layer if needed.
Deliverable: materialized files/diff + baseline hashes + concise evidence/remaining limits.
Stop: after the packet; no new systems, new tests mirroring code, or extra framework.
```

A worker cannot resolve an unspecified economic or persistence decision by inventing a second system. Return the exact ambiguity to Terra; Terra chooses based on this design and current code, escalating only consequential unresolved state changes. Tests must cover the transition and ownership property, not the fact that a constant exists. A good specification makes work bounded; it cannot guarantee a smaller model never makes a mistake.

## J. Full connection audit before calling the demo ready

Every role has reachable hire/prerequisites, a useful input/output, an understandable blocker/recovery, an animation/sound path and saved state. Every Coin sink has a reachable prior source and an observable benefit. Every building requirement has a function. Every progression lock has a noncircular route. Every alert has a cause and useful response. Every item has one owner. Every repeatable action has a bounded cost/reward and replay behavior. Every network action validates the acting player and current state.

The final real route must connect these systems in one village and include save/rejoin and two-player interaction. Disconnected green fixtures do not establish this. Preserve all still-open full-overhaul requirements in ROADMAP/quality ledger; do not call the entire project complete because this design is written.
