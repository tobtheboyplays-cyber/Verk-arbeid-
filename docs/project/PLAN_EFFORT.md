# The daily labor pool — why "energy" was never the lever, and what is

*"Finn på et system for alle jobbene sånn at det gir mening. Jeg vil ikke at
bonden skal bonde for alltid, jeg vil ha en cap på hver jobb. Finn en logisk
løsning og bygg systemet rundt det."* — owner, 2026-08-25. Plus, on the
farmer specifically: start with a 3x3 plot; more farmers or more skill grows
it.

So: **every trade gets a natural limit, built around one new number.**

---

## 1. Why energy was the wrong lever

Every settler already has ENERGY, and it already falls while working. It was
never going to produce "the farmer stops for the day", for three reasons that
all point the same way:

1. **It drains from existing, not from working.** Standing idle costs energy
   too (0.02–0.04/tick vs. 0.09–0.10/tick while working) — it is a survival
   number, not a labor meter.
2. **It refills from any rest**, work-related or not, so a farmer napping at
   noon is back to full and "farming forever" again an hour later.
3. **A settler at 40 energy is still perfectly willing to work.** Nothing
   about the number says "you have done a day's work" — it says "you are
   getting tired", which is a different sentence.

Capping trades at low energy would have made every settler quit at the same
threshold for the same reason, which reads as *fatigue*, not as *a finished
day*. The owner asked for the second thing.

## 2. The fix: Effort, a pool spent by finishing work

**`Effort`** (`src/main/java/com/hearthstead/entity/Effort.java`) is a small
component hanging off `SettlerEntity`, composed exactly the way
`SettlerAttributes` is (`settler.effort()` mirrors `settler.attributes()`) —
read that file first if this one is unclear.

- **Capacity:** `20 + STAMINA / 5` → 20 for a newcomer, up to the high
  thirties as STAMINA is trained. Earned, not rolled — the same shape as the
  rest of the attribute system (`PLAN_ATTRIBUTES.md`), so a settlement that
  has worked someone hard for weeks gets more out of them because they
  genuinely got tougher, not because a number was tuned.
- **Spend:** fixed amounts, charged on **completed** work actions only —
  never on a timer, never while merely standing near work. See §3 for the
  per-trade table.
- **Spent:** `left <= 0`. A spent settler's work goal returns `canUse() ==
  false` for the rest of the day. That is the *entire* mechanism — no new
  "go be idle" goal exists or is needed. The goal priority list in
  `SettlerEntity.registerGoals()` already puts `RestAtNightGoal` and
  `BoundedStrollGoal` below the work goals; the instant a work goal steps
  aside, one of those already picks the settler up. Nobody has to write
  "and then go be idle" anywhere.
- **Refill:** at wake, not on a timer — see §4.
- **Persistence:** one float, `EffortLeft`, written straight onto the
  settler's own save tag. Absent means full, for both a fresh settler and
  one saved before this system existed.

## 3. Every trade's natural limit

The owner asked for "a cap on every job" and "a logical solution" — logical
meaning each trade's limit comes from what actually bounds that trade in the
world, not from one flat number copy-pasted five times.

| Trade | The limit | Effort cost |
|---|---|---|
| **Farmer** | **THE TENDED PLOT** (§5) — a bounded field, not the whole settlement | 1 / harvested crop, 1 / 4 planted-tilled-watered actions (batched) |
| **Lumberer** | Replants what it fells (§6) — forestry regrows the resource | 3 / felled tree (whole tree, not per log), 1 / limbing stint |
| **Crafter** (all 11 bench trades) | Input scarcity already limits it; effort adds the human limit | 2 / completed production batch |
| **Miner** | The mine's own storage — the existing "stop when chests are full" | 2 / mined block |
| **Guard** | Patrol tires; combat and alerts never do (§7) | 1 / completed patrol leg |

Every cost above is charged **on completion**, at the exact call site the
work already finishes at — the same rule `SettlerAttributes.train()` already
follows for "learning by doing". A tree half-felled when the pool runs dry
still gets felled (its cost is charged once, at the end); the next tree
simply does not start.

## 4. Sleep quality is the refill lever — on purpose

The pool does not refill on a timer either. It refills **at wake**, and how
much depends on *where the settler slept*:

- **A genuine night in a claimed bed → full.** Detected as `isSleeping()`
  going `true → false` (vanilla sleep state), watched from
  `SettlerEntity.tickEffortRefill()`.
- **Rough rest with no bed (RESTING, by the hearth) → 60%.** Detected as the
  `RESTING` activity ending, gated on the day having actually turned over
  into working hours (see the file for why that guard exists — a bed found
  mid-rest must not look like a finished night).

This is the whole point of building the system around a *housing* decision
rather than a flat daily reset: **a village of open-air sleepers is visibly
less productive than one with real houses**, and the fix is exactly the
thing the game already asks the player to build. Sleep quality was already an
aesthetic idea in this codebase (rough rest costs morale in
`RestAtNightGoal`); Effort makes it an economic one too.

Both edges live on `SettlerEntity`, not inside `RestAtNightGoal` — the entity
already knows its own `isSleeping()` and `getActivity()` every tick, so the
refill is a pure "need", the same category `tickNeeds()` already is, and
needed no changes to the rest goal itself.

## 5. THE TENDED PLOT — the farmer's specific answer

The owner's follow-up was concrete: *"maybe [the farmer] starts with a 3x3
plot; with more farmers you can have bigger, or it grows with how much skill
he has in it."* Both halves are in `FarmerWorkGoal`:

- **Skill widens it.** Side = `3 + 2*(DEXTERITY / 20)`: 3x3 below 20, 5x5 at
  20+, 7x7 at 40+, 9x9 at 60+, 11x11 at 80+.
- **Company widens it too.** Every *other* farmer employed at the same
  farmhouse adds one more ring (+2 side) — many hands really can tend a
  bigger field.
- **Either path caps at 11x11.** There is no version of this job that farms
  the whole map, no matter how skilled or how staffed.

The square is centred on the farmhouse's own `Building.anchor` and the bound
applies to the *filter*, not the *scan*: `isMatureCrop`/`isMaintainable`
(the predicates the scan and the target-validity checks already share) throw
out anything outside the square, so "ignored entirely" costs nothing beyond
the test the goal was already doing. Tilling and watering are bounded the
same way as harvesting — without that, a farmer could terraform the whole
settlement into farmland while only ever *harvesting* inside the square,
which would defeat the point of a bounded plot.

## 6. The lumberer already replants — this just gave it a price

`LumbererWorkGoal.finishTree()` already planted a matching sapling on the
stump (dirt below, air above, skipped silently otherwise) before this slice;
that renewal *is* the trade's natural limit — a lumberer works the same patch
of forest indefinitely because the forest itself regrows. Effort's
contribution here is purely the cost: 3 for the whole tree (charged once,
not per log, so a jungle giant and a single oak cost the same to fell) plus 1
for the limbing stint that follows.

## 7. Guards: patrol tires, defending the village never does

A guard's pool only gates `GuardPatrolGoal` — walking the ring. It is
deliberately **never** read by `GuardMeleeGoal` or
`GuardRespondToAlertGoal`: a guard who has walked their legs off still fights
and still answers an alarm. Safety beats bookkeeping — the owner's "cap on
every job" is about ordinary labor, not about a guard refusing to defend the
settlement because they are tired of walking.

## 8. Legibility

No hidden numbers (job standard, point 1). `Effort.describe()` returns
`"14/32"`; `SettlerEntity` exposes it as `effortDescribe()`, alongside plain
getters `effortCapacity()`, `effortLeft()`, `effortSpent()` and
`isEffortSpent()`. Scope was kept to entity-side getters for this slice — the
settler screen and `/hearthstead why` are owned by other work in flight; the
one line for `/hearthstead why` to add (right after the existing `needs:`
line in `HearthsteadCommand.why()`) is:

```java
lines.add("effort: " + s0.effortDescribe());
```

A future settler-screen pass can render `effortLeft()/effortCapacity()` as a
bar or pips the same way attributes are, once `SettlerSnapshotPayload` (or
the already-synced entity data) is the one carrying it across the wire.

## 9. Deferred — file ownership, not design

`CourierWorkGoal` and `TidyWarehouseGoal` are intentionally **not** touched by
this slice: both are owned by another worker in the same parallel fan-out
this plan was built under. A courier's own natural limit is an obvious
next step in the same shape as the table in §3 (carry capacity and travel
distance already bound a single haul; effort would bound how many hauls a
day) — left for whoever next owns those files, following exactly the pattern
here: pick the limit that is already true of the trade, price the action
that already marks it complete, and let `canUse()` read `isEffortSpent()`.

## 10. Tuning knobs

Every number above is a constant, not a magic literal buried in logic:

- `Effort.BASE_CAPACITY` (20), `Effort.STAMINA_PER_UNIT` (5) — the daily
  ceiling and how STAMINA training raises it.
- `Effort.BED_REFILL_FRACTION` (1.0), `Effort.ROUGH_REFILL_FRACTION` (0.6) —
  the housing lever from §4.
- `FarmerWorkGoal.TENDED_SIDE_CAP` (11), the `3 + 2*(DEX/20)` formula and the
  `+2` per companion — the plot from §5.
- `FarmerWorkGoal.LIGHT_ACTIONS_PER_EFFORT` (4) — the light-work batch size.
- The per-trade spend amounts in §3's table, each a literal at its own call
  site (a felled tree's `3`, a patrol leg's `1`, and so on) rather than a
  shared constant, because they are independent design decisions that happen
  to currently share no relationship worth enforcing in code.

If a full day (23 units for a newcomer) feels short or long once played, the
first knob to turn is `Effort.BASE_CAPACITY`, not any individual trade's
spend — that keeps every trade's *relative* cost the same while moving how
much of a day one settler gets.
