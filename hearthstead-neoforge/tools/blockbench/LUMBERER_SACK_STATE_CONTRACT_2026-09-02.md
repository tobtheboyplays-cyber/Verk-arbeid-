# Lumberer sack animation state contract

Status: implementation contract for offline Candidate review. Native Minecraft
motion is still required before approval.

## Runtime source of truth

- Actor: a `Profession.LUMBERER` `SettlerEntity` with a physical mainhand axe.
- Animation source: `SettlerAnimations.java`; `SettlerModel.java` composes the
  clips and projects the attached or detached timber frame.
- Preview source: a fresh evidence-only `.bbmodel` exported from those Java
  sources. The tracked `.bbmodel` is never accepted as fresh evidence by itself.
- Blender 5.2 is installed and the reproducible carry source now lives at
  `tools/blender/hearthstead_lumber_sack_v1.blend`. The authoring script
  `tools/blender/author_lumber_sack.py` rebuilds the scene from the same
  WALK_LADEN, HAUL_LOG_HEAVY and full-load spine channels used at runtime,
  renders all four required views, and emits numeric palm-to-grip evidence.
  Minecraft remains the final visual gate.

## Carry loop

| Contract | Runtime truth |
|---|---|
| Trigger/owner | Server activity `HAULING_LOG`; `haulState` blends in/out over five client ticks. |
| Locomotion | `WALK_LADEN`, 1.20 s looping definition sampled from travelled distance by `animateWalk`; it owns legs, root, torso, head and cloak. It must stop stepping when navigation stops. |
| Load overlay | `HAUL_LOG` or `HAUL_LOG_HEAVY`, 2.40 s loop; it owns only the two arms. Fill selects/blends the variant without changing the foot clock. |
| Mainhand | The authoritative axe remains in the synced MAINHAND slot. During `HAULING_LOG` only, the settler held-item layer suppresses its presentation so both visible hands can brace the load. Every other activity delegates unchanged to vanilla `ItemInHandLayer`, restoring the real item immediately. |
| Hands | Both one-piece arms brace the visible lower frame grips. The server stack is never cleared, moved or fabricated to obtain this pose. |
| World prop | None. `lumber_frame` is a torso child and its visible log count follows `visualCarryFraction()`. |
| Start/end | Both loops are seam-closed. The arm layer eases to/from the sampled locomotion pose; no activity-edge snap. |
| Weight | Torso hinges forward into the load; head counters enough to keep the route visible. A back load must never arch the torso backward. |

Target directions: feet follow travelled distance; hips/chest follow the route;
eyes stay near the route horizon; both hands stay visually connected to the
frame rails. The synced axe remains authoritative but has no carry-state
silhouette near either hand, the face or the crown.

## Detach, collect, stow and shoulder sequence

| State | Trigger and duration | Physical authority and exact contact |
|---|---|---|
| Set down | `EV_WORK_CONTAINER_DOWN`; `WORK_CONTAINER_DOWN`, 28 ticks / 1.40 s | The server publishes `WorkContainerKind.SACK` and its immutable `BlockPos` at animation start. The duplicate follows one monotonic shoulder-to-anchor path. Both palms start on the lower backpack grips, migrate along the existing frame to its upper side rails, and release at ground contact/sound on tick 20 / 1.00 s. The authoritative axe stays in MAINHAND but remains visually suppressed until 1.20 s recovery. No inventory transfer occurs. |
| Walk to item | `COLLECTING_ITEMS` with empty offhand | The placed frame is the persisted world prop and must remain at the same world position and heading. Ordinary locomotion owns the feet. |
| Pick up item | `EV_GROUND_ITEM_PICKUP`; `GROUND_ITEM_PICKUP`, 20 ticks / 1.00 s | The real world `ItemEntity` remains visible through anticipation. At tick 12 / 0.60 s exactly, one item moves world -> synced offhand and the visible prop changes on the same frame. |
| Return with item | `COLLECTING_ITEMS` with non-empty offhand; `WALK_CARRY_ITEM`, 1.00 s loop sampled from distance | The actual item is visible in offhand. The placed frame remains world-fixed behind the worker. Mainhand remains the real axe. |
| Stow item | `EV_WORK_CONTAINER_STOW`; `WORK_CONTAINER_STOW`, 22 ticks / 1.10 s | At tick 12 / 0.60 s exactly, one item moves synced offhand -> authoritative bag and the visible offhand item disappears on that frame. |
| Shoulder frame | `EV_WORK_CONTAINER_UP`; `WORK_CONTAINER_UP`, 32 ticks / 1.60 s | The ground duplicate stays authoritative while it follows the exact reverse anchor-to-shoulder path. The real axe is visible only during approach; at tick 12 / 0.60 s it is visually suppressed as both palms contact the upper side rails and take ownership of the lift, then migrate to the lower backpack grips. The server clears the placed anchor only after tick 32, when the one-shot has completed; the attached frame then owns the load. |

For all one-shots, the start establishes the target, anticipation lowers the
hips/chest, the listed contact tick owns the visible handoff, recovery shows the
load settling, and the final pose hands cleanly to locomotion or the next phase.
At least one support foot remains believable. The face, chest and reaching hand
must bend toward the item/frame rather than away from it.

The transition contact anchors are the centres of the existing upper side
rails at Java model pixels `(-4, 0.5, 0.5)` and `(4, 0.5, 0.5)` (Blockbench
`(-4, -0.5, 0.5)` and `(4, -0.5, 0.5)`). At the 1.00 s set-down and 0.60 s
pick-up contact frames, each hand centre must be within 8.0 model pixels of its
paired upper-rail anchor. The lower backpack grips remain the start/end target,
not the unreachable ground-contact target.

## Interruption and authority

- A placed frame has one persisted world position and one world heading. Actor
  root crouch, body yaw, walking and navigation must be cancelled out of the
  detached prop transform.
- A log exists in exactly one visible/authoritative place: world entity,
  offhand, bag, or destination storage. No frame is allowed to duplicate or
  hide an inventory transfer.
- Reload or interruption may resume from the persisted placed-container state;
  it may not snap the frame back onto the worker before the authored lift ends.

## Evidence required for offline Candidate

- Carry loop: `front34`, `left`, `right`, `back34` at 0, 0.30, 0.60, 0.90
  and 1.20 seconds, with full load, held-item presentation suppressed, attached
  frame and logs. Separate non-`HAULING_LOG` evidence must show the real axe
  presentation restoring.
- Set down and shoulder: the same four views at start, anticipation, immediately
  before contact, exact contact, immediately after contact, and recovery/end.
- World-lock proof: actor yaw/offset changes while the detached frame's projected
  world position and heading remain invariant.
- Hard reject on backward spine, missed grip, hand over crown, missing/wrong
  props, sliding feet, transition pop, or any detached frame that follows,
  bobs, teleports or spins with the actor.
