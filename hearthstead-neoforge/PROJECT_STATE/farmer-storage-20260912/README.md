# Farmer storage fallback candidate

## Observed live failure

At afternoon work, Ansgar retained four authenticated `FARM_CROP` carrots and
recorded a new `farmhouse_storage_unreachable@753876`. This is no longer the
scheduled meal pause observed at day 5630.

`FarmerWorkGoal` selects `WorkerStorageAuthority.nearestLoadedContainer(...)`
without testing route reachability. The exact target stays sticky during seven
40-tick physical contact retries. On failure it waits 100 ticks, but retains
that target in the goal object; the next selection repeats the same target.
A Farmhouse with a closer sealed chest and a farther reachable chest therefore
can retain genuine crop cargo indefinitely.

## Candidate behavior

`FarmerWorkGoal.storage-fallback.patch` makes a deliberately small change:

1. Keep the selected Farmhouse chest unchanged throughout its existing seven
   physical retries; `ContainerApproach` remains the sole navigation and
   door-contact authority.
2. After that terminal failure, record the failed target coordinate, exclude
   only that target for the current carried crop load, and choose the next
   nearest *loaded* Farmhouse chest after the normal 100-tick wait.
3. Clear the bounded exclusion set once the carried crop is fully deposited.
   If every current candidate has failed once, clear it and retry normally
   after the same wait so a player-repaired door or chest can recover.

The candidate neither moves an entity, opens a room remotely, changes
container contents before contact, nor treats a full chest as a path failure.

## Regression to add before merging

Create one focused Farmer GameTest, preferably in
`FarmerRecoveryAdversarialGameTests`:

- Build a valid Farmhouse with two real chests. Make the closer chest have no
  legal reachable contact side; leave the farther chest fully reachable.
- Give an employed Farmer a small authenticated `FARM_CROP` bag load and
  execute ordinary goal selection/ticks during work hours.
- Assert the crop count is conserved in `primary + secondary + bag` on every
  tick, the first trace includes the sealed primary coordinate, and no chest
  gains cargo before ordinary physical contact.
- After the existing failure/cooldown budget, assert the reachable secondary
  receives the exact load, bag is empty, and the sealed primary remains empty.

This checks the fallback without weakening the physical-contact, sticky-route,
or storage-full contracts.

## Status

Staged only. No `src`, world, QA, build, or live server mutation was made.
