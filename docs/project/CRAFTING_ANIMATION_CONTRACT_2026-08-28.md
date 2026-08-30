# Hearthstead visible crafting contract

Date: 2026-08-28  
Status: active vertical-slice contract  
First actor: Lumberer  
First recipe: `minecraft:wooden_axe`

## Player-facing outcome

Crafting must read as a real physical action. The worker places the actual
recipe ingredients on a crafting table, performs one decisive strike or press,
and the finished item appears at the exact contact beat. The worker then takes
the result and physically delivers it to real storage. The sequence must meet
the same body, prop, timing, ownership and interruption standard as the approved
bag-to-chest work.

## Research record

### FACT

- Tobias selected the crafting-table scene from *A Minecraft Movie* as a
  readability reference: ingredients are placed, a decisive contact happens,
  and the output is immediately legible.
- Warner Bros. publishes an official trailer for the film. The trailer is a
  reference only; no film assets, camera work or exact animation are to be
  reproduced: https://www.youtube.com/watch?v=wJO_vIDZn-Ig
- Minecraft's official Crafter documentation establishes two useful native
  conventions: recipes use a 3x3 grid, and a successful activation produces a
  visible result. It also distinguishes successful and failed feedback:
  https://www.minecraft.net/en-us/article/minecraft-snapshot-23w42a
- Hearthstead currently reserves one exact crafting table and exact ingredient
  slots, resolves the live vanilla wooden-axe recipe, and performs a guarded
  atomic commit at tick 30 of a 48-tick work phase.
- A linked destination container may be several blocks away from the crafting
  table. Depositing into it during the table strike would therefore be a hidden
  teleport rather than a physical action.

### INFERENCE

- The reference feels clear because preparation, anticipation, contact and
  result are separate readable beats.
- The contact frame is the only honest moment for the gameplay recipe commit.
- The 3x3 arrangement makes the shown ingredients understandable without an
  extra UI panel.
- Crafting and storage delivery must be separate state machines when the real
  destination is not at the table.

### ORIGINAL HEARTHSTEAD PROPOSAL

Use a reusable, recipe-driven tabletop presentation owned by a server action
ID. Render the exact reserved ingredients in their vanilla recipe slots. The
worker lays them out, winds up with their free hand or a role-appropriate safe
tool, strikes the table, and the server atomically transforms the reserved
inputs into one output escrow at the visible contact frame. The output is then
picked up, carried to a real container and deposited through the existing
physical container-interaction standard.

This is not a recreation of the movie scene. Camera, staging, timing, poses,
effects and sound are original Hearthstead work.

## Physical state contract

| State | Visible truth | Authoritative truth | Exit rule |
|---|---|---|---|
| `RESERVED` | No props yet | Exact table, recipe, source slots and action ID reserved | Route to table remains valid |
| `LAY_OUT` | Exact ingredients appear one recipe slot at a time | Inputs remain immutable under the reservation; no output exists | Every required slot is visibly populated |
| `WIND_UP` | Worker plants feet, leans toward the table and prepares one clear strike | Reservation revalidates continuously | Contact pose can reach the table without clipping |
| `CONTACT` | Hand/tool meets the table; ingredients transform | One atomic input-to-output-escrow commit, once per action ID | Commit succeeds or the action fails closed |
| `RESULT_READ` | Finished item is clearly visible on the table | Output exists only in action escrow | Short readable hold completes |
| `PICK_UP` | Worker takes the exact output from the table | Escrow transfers to the worker's protected carry state | Item is visibly owned by the worker |
| `DELIVER` | Worker carries the output to the selected real storage | Output remains in exactly one protected state | Worker reaches a valid container contact point |
| `DEPOSIT` | Container opens and the real output is placed inside | Escrow transfers atomically into the exact container slot | Container and visible sequence agree |

## First timing candidate

The 48-tick table beat is retained only if strict previews prove it readable:

- ticks 0–18: lay out 3 planks and 2 sticks in the wooden-axe recipe pattern;
- ticks 19–27: settle stance and accelerate the wind-up;
- ticks 28–30: decisive strike, with authoritative transformation at tick 30;
- ticks 31–36: brief output readability hold;
- ticks 37–46: reach, grip and take the wooden axe;
- ticks 47–48: return to a travel-ready pose.

Storage navigation and deposit are a second physical sequence and are not
compressed into these 48 ticks.

## Failure and interruption rules

- Before contact: invalid table, moved/changed source item, lost authority,
  reassignment or interruption removes presentation props and releases the
  reservation. No inventory mutation has happened.
- At contact: re-read every source slot and recipe. A failed revalidation shows
  a failed action and commits nothing.
- After contact: the output remains in protected escrow until it is visibly
  picked up and deposited. Full, blocked, missing or destroyed storage creates
  a blocker and retry; it never duplicates, disappears or silently teleports.
- Client callbacks and particles never own inventory mutation.
- Replayed packets, animation restarts and goal retries cannot reuse a committed
  action ID.

## Visual and sound requirements

- Worker bends and reaches forward toward the table, never backward.
- Feet remain planted through the strike; torso leads the working arm.
- Hands, ingredients, table surface and output remain aligned from front 3/4,
  left, right and back views.
- Every ingredient uses the real item representation when available.
- No floating, clipping, snapping, duplicate props or dead idle gap.
- The contact sound has a tight transient; the transformation has a separate,
  warm success layer. Failed revalidation uses distinct restrained feedback.
- Repeated workers use controlled variation and distance attenuation without
  audio spam.

## Acceptance gates

1. Strict deterministic animation checks pass.
2. Fresh Blockbench model and front 3/4, both side and back previews are
   reviewed frame by frame, including every contact transition.
3. Tests prove exact recipe layout, one commit at tick 30, pre-contact rollback,
   post-contact escrow recovery, full/missing storage retry and no duplication.
4. The complete in-game sequence is observed with the real wooden-axe recipe.
5. Multiplayer verifies one authoritative craft and one presentation per nearby
   client.
6. Only after gates 1–5 may the vertical slice be marked in-game Approved.

## Scope boundary

The vertical slice implements and proves Lumberer wooden-axe crafting first.
The action contract and recipe layout API must be reusable, but other roles and
recipes are not called complete until each receives its own physically suitable
pose, prop and in-game verification.
