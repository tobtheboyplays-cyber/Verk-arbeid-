# Door passage staging — NOT integrated

## Observed shared defect

`SettlerDoorGoal` is deliberately flag-free. Each settler independently opens the same wooden door and keeps navigating, so two settlers approaching a one-block doorway from opposite sides can overlap/push at the sill. `RoadNavigation` is the one shared movement layer for every settler role; Lumber animation and role goals do not need changing.

## Apply surface

1. Add `DoorPassageReservations.java` from `stage-src` to `com.hearthstead.entity.path`.
2. Add `private boolean waitingForDoorPassage;` to `RoadNavigation`. In
   `followThePath()`, before `super.followThePath()`, add:

```java
if (path != null && !path.isDone()
        && !DoorPassageReservations.permit(level, mob, path)) {
    waitingForDoorPassage = true; // tick() holds after super.tick()
    return;
}
```

3. Replace the beginning of `RoadNavigation.tick()` with:

```java
waitingForDoorPassage = false;
DoorPassageReservations.maintain(level, mob);
super.tick();
if (waitingForDoorPassage) {
    mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0D);
    return;
}
```

The holder alone gets forward navigation through a nearby wooden-door node. Other settlers preserve their path and wait in place. The queue is FIFO. Its two-second lease renews only while the holder measurably advances toward the sill, expires on interruption or a stalled holder, and releases immediately only after the holder has reached then cleared the doorway. This gives opposite-direction traffic one holder rather than two conflicting pushers; it never changes blocks, collision, positions or inventory.

## Proposed regression GameTest

Add to `PathGameTests` (existing `path` batch; 500-tick crossing and 300-tick expiry cases):

- Build a sealed, one-cell-wide north-south corridor with a single closed oak door at its centre and no bypass.
- Spawn two normal `SettlerEntity` actors on opposite sides and attach one `OneShotMoveGoal` each to targets beyond the other side.
- Each tick, assert at most one actor's real bounding box intersects the door-cell AABB. Also assert each actor's per-tick horizontal displacement stays below a generous normal-navigation ceiling (no teleport).
- Success requires both exact targets reached, the door opened at least once, neither actor remains at the sill, and no actor held the doorway after the other had crossed.
- The same scenario must also interrupt the first actor before entry in a second pass: after the two-second lease the opposite actor reaches its target. This pins expiry/release and prevents opposite-direction deadlock through the real navigation layer, not a mock queue.

## Limits

This is an apply-ready isolated proposal only. It has not been compiled or run, and it intentionally does not add a MOVE flag to `SettlerDoorGoal`: that would cancel the active job routes which own movement. The gate lives in shared navigation instead.
