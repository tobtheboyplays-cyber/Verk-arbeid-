# Courier FOOD Hearth-contact mismatch — review proposal

## Observed failure

The failed restock fixture logs:

- Hearth: `(841321, -59, 1733056)`
- Courier: `(841322.8672163, -59, 1733058.5472074)`
- Navigation target: `(841322, -59, 1733058)`, done
- Food bag: 8; transfer presentation inactive; worker lifecycle interrupted.

`CourierWorkGoal.tickToHearth` treats that position as arrived because
`blockPosition().distSqr(hearth) = 1 + 4 = 5 <= 6.25`.
It then creates `CourierFoodBagSession` with the same block as its bag
anchor. The session instead requires `withinContainerReach` to the Hearth
centre `(841321.5, -58.5, 1733056.5)`; the logged entity position has squared
distance about `6.31`, so it cannot transfer. Its retry navigation targets
the already-reached anchor, so it remains done and cannot close the margin.

This is independent of ledger container adaptation: `containerAtLoaded`
already exposes the bound Hearth inventory as a live Container view.

## Narrow proposed correction

1. Add one public FOOD contact predicate in
   `RequestLedgerService` that performs its existing private
   `targetContact` behaviour for a Hearth: exact `withinContainerReach`
   plus the current eye-to-Hearth-centre ray test. Make the existing ledger
   delivery gate call that predicate.

2. For `JobPriority.FOOD_DELIVERY`, replace raw
   `pathAbove(hearth)` in `CourierWorkGoal` with
   `HearthApproach.findProgressingContactPath(...)` and
   `navigation.moveTo(path, 0.95)`. This helper chooses only grounded,
   ray-clear feet cells whose centre is inside the same 2.5-block contract.
   Keep raw `pathAbove` for the untyped Hearth load/return behaviours.

3. In `tickToHearth`, start `CourierFoodBagSession` only when that same
   shared FOOD predicate is true. In `CourierFoodBagSession.tick`, use it
   before advancing the animation; if false, repath through the legal
   `HearthApproach` contact path rather than the stale bag-anchor centre.

No inventory mutation, request transition, carry capacity, or reach radius
changes. A wall/closed door remains a failed physical contact; it cannot be
bypassed by the presentation clock.

## Regression shape

Add one focused GameTest to `CourierFoodRouteGameTests`:

- create the normal real FOOD request and pickup;
- place the Courier at the logged edge position whose **block** distance is
  5 but whose **centre** distance is greater than 6.25;
- enable ordinary AI;
- assert its first pre-transfer position has real reach and a clear Hearth
  ray, then assert one real Hearth insertion, empty bag, original source
  count minus eight, and a satisfied request.

The assertion must not accept a transfer from the edge position. It proves
the worker repositions to a legal contact before the ledger commits the first
unit.

## Review boundary

This is a staged proposal only. No source patch accompanies it, and the
previous `RequestLedgerService.patch` is withdrawn.

