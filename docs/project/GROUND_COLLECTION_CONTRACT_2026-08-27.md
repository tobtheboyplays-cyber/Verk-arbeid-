# Physical ground collection / portable work-container contract

## Reusable authority boundary

`GroundCollectionSession` is the reusable server helper for field jobs. The
first integration is Lumberer, but nothing in the transaction assumes a log or
a sack:

1. A produced `ItemStack` is spawned as a real `ItemEntity` at its real source.
2. A bounded UUID/last-position index (96 rows for Lumberer) names physical
   entities; it never stores a second stack.
3. At the pickup contact, exactly one item leaves the selected entity and enters
   the settler's persisted/synced OFFHAND. Main hand is never overwritten.
4. At the container contact, exactly that one offhand item enters the settler's
   persistent bag, subject to both count capacity and real slot capacity.
5. If the goal stops mid-carry, the offhand item is re-materialised in the
   world before the hand is cleared. A failed entity spawn leaves the hand
   untouched.

Owned ItemEntities carry the worker UUID in persistent entity data. A newly
constructed session can rebuild its bounded transient index from matching real
entities in a caller-supplied bounded AABB after save/reload. Loaded leases are
renewed every 20 ticks for 100 ticks. If the worker remains unloaded, the item
is not immortal or invisible: its finite pickup delay expires and the real
world item remains collectible.

## Lumberer phases

The server state machine is deliberately explicit and observable:

1. `TO_TREE`
2. `CHOPPING` — the entire validated tree is felled top-down; every log becomes
   a physical drop.
3. `LIMBING` — replant and finish the tree job before collection begins.
4. `CONTAINER_DOWN` — place one fixed sack projection.
5. `SELECTING_ITEM` / `TO_ITEM`
6. `PICKING_ITEM` — exact-one transfer to OFFHAND on the authored contact tick.
7. `RETURNING_TO_CONTAINER`
8. `STOWING_ITEM` — exact-one transfer to persistent bag on contact.
9. Repeat 5–8 while physical owned drops remain and capacity is available.
10. `CONTAINER_UP` / `TO_CAMP` — shoulder the sack and deliver to the Lumber
    Camp's real storage.
11. If capacity forced an early trip, `TO_COLLECTION_SITE` returns to the same
    fixed coordinate, puts the sack down again and resumes the remaining drops.

The sack anchor is deterministic: one block forward in current facing, then
left, right and back. A candidate must be loaded, collision-free/replaceable
at the sack cell and have solid support. The worker's current cell is only a
diagnosed last-resort fallback (`work_container_anchor_fallback`). Down, stow
and up all look at the same persisted coordinate; the sack never follows the
worker between drops.

## Animation event API

The server contract consumed by the client model is:

| Transaction | Trigger | Activity | Duration | Contact |
|---|---|---|---:|---:|
| place container | `triggerWorkContainerDown()` | `GATHERING_LOG` | 28 ticks | projection set at start |
| pick ground item | `triggerGroundItemPickup()` | `GATHERING_LOG` | 20 ticks | tick 12 |
| stow in container | `triggerWorkContainerStow()` | `GATHERING_LOG` | 22 ticks | tick 12 |
| shoulder container | `triggerWorkContainerUp()` | `GATHERING_LOG` | 32 ticks | projection clears at completion |

Walking to and from physical drops uses `COLLECTING_ITEMS`. Hauling the loaded
sack uses `HAULING_LOG`. `WorkContainerKind.SACK` plus the placed BlockPos are
synced and persisted by `SettlerEntity`; this helper does not author client
poses.

## Failure and recovery rules

- Pathing is repathed on a bounded timer and abandoned after eight failures.
  An unreachable item is released with no pickup delay, never discarded.
- Tracker overflow still spawns the physical output; only ownership is omitted.
- Full bag/count or full slot layout rejects stow without mutating either owner,
  then safely hauls what already fits.
- A missing projected container re-materialises carried cargo and records a
  route diagnostic.
- Unloaded owned entities stay physical. When they are loaded inside the
  recovery volume they are re-indexed; entities outside loaded chunks are not
  guessed into existence.

GameTests cover exact-one transfer, interruption, full-container and tracker
overflow, and transient-index rebuild from a persistently-owned physical item.
The full Lumberer test observes the world drops, fixed sack anchor, offhand
return, sack contact and final camp-chest count.
