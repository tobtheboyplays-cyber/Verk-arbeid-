# Bag-to-chest unloading — Candidate state contract

Status: **Candidate for in-game integration.** This is an editable Blender 5.2
source and an offline physical review package; it does not alter, replace, or
approve the server-authoritative inventory transaction.

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
| Arrive / brace | 0–7 | Sack is torso-owned; both hands brace its lower rails. |
| Set bag down | 8–12 | At tick 12, the sack changes atomically from torso projection to one fixed world anchor. No inventory moves. |
| Reach into bag | 13–25 | World sack stays fixed. The worker lowers hips and chest toward its mouth. |
| Withdraw oak log | 26–36 | At tick 30, one real planned log transfers bag -> worker escrow/offhand. The visible log appears in the left hand on the same frame, then clears the sack through six measured 20-TPS lift keys (30–36), never a one-frame pop. |
| Open chest | 31–36 | The right palm separately contacts the exterior chest-lid pull at tick 31, releases it, and the lid reaches its open position at tick 36. The left hand retains the visibly distinct log throughout. |
| Deposit | 37–48 | At tick 48, the left palm reaches the chest cavity and the same planned log transfers worker escrow -> chest. |
| Close / recover | 49–80 | Chest finishes closing at tick 64. Bag remains at its original world anchor; the worker returns to neutral only after the close. |

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
- The torso-owned bag has two visible shoulder straps. They release at tick 11
  before the carried bag projection disappears at tick 12. The remaining iron
  yoke and dark cloak are intentionally distinct from the tan canvas sack, so
  the worker cannot read as still wearing or dragging the world-owned bag.
- The chest is world-owned. Its lid opens only after the right-hand contact;
  its cavity receives the log only on the tick-48 commit.
- The visible item is one original Minecraft-proportioned oak-log proxy, not
  a generic particle or abstract token. Every owner projection uses the exact
  same unscaled log geometry, orientation and scale: dark bark-side slabs plus
  pale end-grain caps. Bag -> hand and hand -> chest commit transforms must be
  identical at their contact ticks.
- Feet remain planted during this stationary transaction. The candidate has no
  locomotion loop to hide foot sliding.

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
- The contact/ownership frames must agree with ticks 12, 30, 36, 48, and 64.
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
- The log silhouette gate requires a long axis of at least **0.45 Blender m**
  with three bark-side meshes and two visible end-grain caps in pre-open and
  open-lid review frames.
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
