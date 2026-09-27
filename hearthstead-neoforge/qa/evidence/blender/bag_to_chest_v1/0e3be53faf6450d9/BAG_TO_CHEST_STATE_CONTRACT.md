# Bag-to-chest unloading — Candidate state contract

Status: **Author evidence only; independent visual review required.** This is
an editable Blender 5.2 source and offline physical review package; it does not
alter, replace, or approve the server-authoritative inventory transaction.

## Player-facing read

The worker arrives at a chest with a back load, puts the bag down at a fixed
world anchor, bends **toward** the bag, removes one visible oak log, opens the
chest with the free hand, deposits the log, closes the lid, and stands up. The
sequence is intentionally legible from a normal Minecraft camera instead of
being a hidden inventory update.

## Timing and ownership

The authored one-shot is 80 ticks / 4.00 seconds at the Minecraft 20 TPS
clock. The runtime implementation must use the same commits shown below.

| Beat | Ticks | Visible owner and exact event |
|---|---:|---|
| Laden arrival / brace | 0–7 | Sack is torso-owned. Both lowered elbows and palms contact named lower rail targets through the adjacent laden-arrival beats. |
| Set bag down | 8–12 | Both hands grip at tick 8. The right hand and strap release at tick 9; the left hand and strap visibly remain weight-bearing through tick 10, then release at tick 11. The sack reacts under the one-sided load, clears around the worker's left side without squeezing through torso or legs, and changes atomically to one fixed world anchor at tick 12. No inventory moves. |
| Reach into bag | 13–25 | World sack stays fixed. The worker lowers hips and chest toward its mouth. |
| Withdraw oak log | 26–36 | At tick 30, one real planned log transfers bag -> worker escrow/offhand. The visible log appears in the left hand on the same frame, then clears the sack through six measured 20-TPS lift keys (30–36), never a one-frame pop. |
| Open chest | 31–37 | The right palm contacts the exterior chest-lid pull at tick 31 and remains attached to the moving latch through tick 36. It releases on tick 37. The left hand retains the visibly distinct log throughout. |
| Deposit | 37–48 | At tick 48, the left palm reaches the chest cavity and the same planned log transfers worker escrow -> chest. |
| Stow / close / next cycle | 49–80 | The chest-owned log moves fully below the rim on tick 49 and remains clear of the complete lid sweep and closed lid. The chest finishes closing at tick 64. More items remain in the fixed bag, so the worker deliberately turns and reaches back toward it through adjacent next-cycle frames instead of returning to generic neutral. |

There is no point at which a bag, log, or chest item has two visible owners.
If a destination is full, destroyed, blocked, or the task is interrupted,
runtime must stop before the tick-48 commit and retain the real item safely in
the source owner; it must never play an unearned deposit.

## Physical pose rules

- The worker faces the chest along the negative Y axis; positive Blender X
  torso rotation is the forward hinge toward that target.
- Hips lower before the bag reach. Every bend/reach/deposit key keeps the
  chest moving toward the target; a negative forward-lean reading is rejected.
- The bag anchor is a world-space object. It is not parented to root, torso,
  arms, chest, or camera after tick 12.
- The torso-owned bag has two visible shoulder straps. The far/right strap
  releases first at tick 9 while the near/left strap visibly bears the bag
  until tick 11. The bag travels laterally around the left silhouette before
  descending; it never takes a diagonal shortcut through the torso. The remaining iron
  yoke and dark cloak are intentionally distinct from the tan canvas sack, so
  the worker cannot read as still wearing or dragging the world-owned bag.
- The chest is world-owned. Its lid opens only after the right-hand contact;
  its cavity receives the log only on the tick-48 commit. The palm follows the
  moving latch through tick 36 and releases at tick 37. The log is visibly
  below the rim before the lid begins closing and never intersects the lid,
  rim, complete sweep volume or closed-lid volume afterward.
- The visible item is one original Minecraft-proportioned horizontal oak-log
  cuboid, not a generic particle or abstract token. Every owner projection uses
  the exact same unscaled geometry, orientation and scale: one long bark body,
  two subtle surface ridges and two square pale end-grain caps. Its X-axis must
  remain visibly longer than both cross-section axes. Bag -> hand and hand -> chest commit transforms must be
  identical at their contact ticks.
- The free/right arm braces the bag or lower torso below shoulder height during
  the bag search. It then travels directly to a small centre brass lid latch;
  no side-pointing T-pose, crown crossing or behind-head silhouette is allowed.
- The evidence `.bbmodel` must contain a nested bone outliner plus a four-second
  one-shot with real torso, arm, bag, lid and item keyframes. A static file with
  an empty `animations` array fails this gate.
- The opening frames are the laden-walk-to-arrival handoff. Feet settle into a
  planted stationary transaction without sliding. The closing frames hand
  directly into the next unloading cycle because the grounded bag still
  visibly contains cargo.

## Offline gates

The author script writes a content-addressed, durable package under
`qa/evidence/blender/bag_to_chest_v1/<candidate-hash>/`. It contains the
source `.blend`, exact author script, this contract, an evidence-only
`.bbmodel`, contact report, manifest and four-view renders. This survives a
Gradle clean. The pre-open transfer frame and immediate post-transfer frames
are included so the item cannot be concealed by the chest rim.

- Each measured palm-to-target contact must be at or below **0.12 Blender m**.
- All bend/reach/deposit review frames must report a forward torso lean of at
  least **8 degrees**, never a backward/negative value.
- Set-down also requires at least **8 degrees** forward lean, so a worker does
  not appear to lean away from the bag while placing it.
- The face/eyes must point toward the active bag, lid pull, or chest cavity
  within **35 degrees** in the authored target-facing check. The neck pitch is
  capped at 14 degrees and yaw at 35 degrees: an anatomically plausible glance
  is preferred to rotating the head through the torso merely to reach a low
  bag anchor.
- The set-down anchor must have zero transform drift after tick 12.
- The contact/ownership frames must agree with ticks 12, 30, 36, 37, 48, 49,
  and 64.
- Tick 30 set-down/withdrawal evidence includes ticks 29–32; the full measured
  clearance path also includes ticks 33–36. No clearance increment may exceed
  the authored **0.15 Blender m** per 20-TPS step.
- The hand->chest approach at ticks 47–48 may move at most **0.24 Blender m**;
  its scale and rotation must be unchanged, and the exact tick-48 hand/chest
  ownership transforms must match.
- Bounding boxes for both arms and the held log must remain outside the chest
  lower-cavity safety volume, lid-sweep volume and visible chest meshes before
  the tick-48 deposit. The lid pull is an exterior contact, not a hand pushed
  through the lid mesh.
- The log silhouette gate requires an X long axis of at least **0.55 Blender m**,
  at least twice either cross-section axis, with one bark body and two visible
  square end-grain caps in pre-open and
  open-lid review frames.
- Tick 8–12 evidence must prove a monotonically left-side route before descent,
  exact grip gaps at every tick, distinct right-then-left hand/strap release,
  visible one-sided load reaction, and no bag/torso/leg intersection after the
  bag clears the worn state.
- Tick 0, the adjacent laden-arrival frames, and tick 8 must measure both palms
  against named lower rails with low elbows; a side-pointing T-pose fails.
- Ticks 31–36 must measure the right palm against the moving latch at every
  tick, followed by an explicit release on tick 37.
- Chest-owner checks at ticks 47, 48, 49, 56, 63, 64 and 65 must report the
  owner, presentation visibility, and intersection state against every rim
  piece, the animated lid, the full sweep volume, and the closed-lid volume.
- Reject floating logs, duplicated props, a following bag, hand gaps, lid
  action before right-hand contact, chest commits before item contact, hidden
  item transfer, or a backward-bending torso.

## Required in-game gate (not performed here)

Before approval, the actual client/server implementation must be tested in
singleplayer and multiplayer with an empty, partial, and full chest; empty and
partial bags; reassignment; chest loss; blocked path; and two simultaneous
workers. Native review must verify the renderer, inventory timing, transition
edges, sound contacts, navigation, and FPS. Until then, this package remains a
**Candidate**, not an approved Minecraft animation.
