# PLAN — SLICE RECRUIT-1: the tavern recruiting chain

Source: sonnet-driver worker W11, deepening the traveler/recruit seam that
already existed (`TravelerJoinGoal`, `Settlement.recruitProgress/recruitTarget/
travelerId`) into the loop DESIGN.md system 8 actually describes: *"Travelers
visit the tavern; recruit by paying a price in village-grown goods."*

## The loop

1. **Attraction — gated on a tavern.** Once a second,
   `SettlementManager.tickRecruitment` grows `Settlement.recruitProgress`
   toward `recruitTarget` whenever the settlement is fed, has room, morale
   holds, AND a valid **TAVERN** building exists. `PLAN_TAVERN_GATE.md`
   (D-TAVERN-1) tightened the tavern from a speed bonus into a hard gate: no
   valid tavern means zero gain, ever — the existing decay branch is the only
   path a tavern-less settlement ever takes, and no traveler ever spawns. The
   gate reads building VALIDITY, never staffing — an unstaffed tavern still
   opens it, so a settlement can never deadlock on having nobody left to hire
   into the very building that would let a replacement arrive. A **TAVERN
   with an INNKEEPER employed** adds MORE on top of an already-open gate —
   the same one gauge, never a second hidden one. When it fills, a traveler
   spawns at the settlement's edge and starts walking in
   (`spawnSettler(..., traveler=true)`).
2. **Arrival, not admission — and never re-gated.** `TravelerJoinGoal` walks
   the traveler to the tavern's anchor (`Schedule.firstValid(s, TAVERN)`)
   when one stands, or to the hearth when none does — including a tavern
   that was valid when this guest was drawn in but has since gone invalid,
   `PLAN_TAVERN_GATE.md`'s grandfather clause (D-TAVERN-2) — then stops and
   stands them like a guest. It never decides whether they join, and the
   tavern gate above never applies to this step: that gate governs attraction
   only, so a guest already on their way is never stranded by their tavern
   breaking mid-wait. Arriving used to mean joining instantly; now it means
   waiting.
3. **The price.** Every second `tickRecruitment` asks whether the settlement
   can pay `RECRUIT_PRICE` out of the hearth's own `HearthBlockEntity`
   inventory (chest truth — real slots, real extraction, INV-3). The moment it
   can, and the guest has actually reached the waiting spot, the price is
   deducted and `convertTraveler` binds them into the settlement
   (`hearthstead.message.recruited`).
4. **Patience.** If the settlement cannot pay, the guest keeps waiting — up to
   2.5 game days, doubled to 5 while an innkeeper is on shift. Past that they
   walk away (`settler.discard()`, `hearthstead.message.traveler_left`) and
   the settlement is free to attract another.

## The price table

```java
// SettlementManager.RECRUIT_PRICE
new ItemStack(Items.BREAD, 4),
new ItemStack(Items.OAK_PLANKS, 8),
```

Chosen from what already flows through the production chains today (bread,
planks, wool — Bakery/Sawmill/Weaver). Bread is what every settlement has
from its first harvest; oak planks are one crafted good stacked on top, cheap
enough that a running sawmill (or a player's own axe) buries the cost, but
real enough that a settlement with no production yet has to wait and stock
up first. Wool would have made the same point but needs a weaver *and*
sheep — a taller bar than "a young settlement can still just about afford
it." The price is static and never scales with settlement wealth; a rich
settlement pays the exact same four loaves and eight planks a young one does
— it simply never notices.

## The knobs

| Knob | Value | Where |
|---|---|---|
| Base attraction gain | +1/tick (+2 if morale ≥ 80) | `tickRecruitment` |
| Tavern bonus | +1/tick (no innkeeper) | `tickRecruitment` |
| Tavern + innkeeper bonus | +2/tick | `tickRecruitment` |
| Recruit price | 4 bread + 8 oak planks | `RECRUIT_PRICE` |
| Guest patience | 60,000 ticks (2.5 days) | `GUEST_PATIENCE_TICKS` |
| Guest patience, innkeeper on shift | 120,000 ticks (5 days) | `tickWaitingTraveler` |
| Innkeeper's motion | `SettlerActivity.SORTING` (reused) | `Employment.motionOf` |
| Innkeeper's sound | `ModSounds.CHEST_STOW` (reused) | `Employment.soundOf` |
| Innkeeper's trained attribute | `Attribute.WITS` | `Employment.trainedBy` |

## Deliberately out of scope for this slice

- **Role cards.** DESIGN.md's fuller phrase is "pay a price → craft the role
  card → assign a work post." A role card as a physical, tradeable item does
  not exist yet; joining is unconditional once paid, the way it always was.
  Assigning the new settler a post is already free — `Employment.hire` works
  for any building type the moment they are in the roster.
- **A bespoke innkeeper clip.** `Employment.motionOf(TAVERN)` reuses
  `SettlerActivity.SORTING` (the courier's tidying motion) rather than a
  signature one. D-016's signature-motion pass covered eleven trades and
  never reached the tavern; a real "working the bar" animation is future
  work, tracked the same way SORTING's reuse is commented in `Employment`.
- **Wiring `InnkeeperWorkGoal` into `SettlerEntity#registerGoals`.**
  `SettlerEntity.java` is owned by another concurrent worker under this
  slice's file-ownership split. The goal is complete and tested at the
  manager/`Employment` level, but needs one line added to the goal selector
  (priority 6, alongside the other `*WorkGoal`s) before an innkeeper visibly
  works in-game. See the RECRUIT-1 report for the exact line.

## Suggested DECISIONS.md entries (not recorded by this worker)

- **D-RECRUIT-1 — a traveler is a guest before a settler.** Arrival at the
  tavern (or hearth) starts a wait, not a bind; only `tickRecruitment` may
  call `convertTraveler`, and only after payment. `TravelerJoinGoal` owns
  motion only, never the join/reject decision.
- **D-RECRUIT-2 — the price is static and paid from the hearth.** No
  discount, no scaling with wealth or population; deducted via real
  `ItemStackHandler` extraction, never a virtual ledger.
