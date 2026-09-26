# Lumberer timber-frame carry contract

Status: active Blender authoring and review contract. The editable source is
`hearthstead_lumber_sack_v1.blend`; Minecraft remains the final visual gate.

## Player-facing read

- The load is a timber carrying frame on the back, not an item held in front.
- The chest hinges forward into the weight. It may never arch backward.
- Both hands stay on the lower frame grips for the entire loaded gait.
- Feet are driven by travelled distance in Minecraft; the Blender loop shows
  the same 1.20-second short-step grammar over a 2.40-second carry cycle.
- The real axe remains authoritative in main hand but its render is suppressed
  only while both hands visibly brace the frame.

## Physical state contract

| State | Parent/owner | Contact | Transfer |
|---|---|---|---|
| Loaded walk | torso-attached timber frame | both palms on lower grips | none |
| Set down | root/world duplicate travelling to persisted anchor | palms release at ground contact | no inventory transfer |
| Ground collection | persisted world duplicate | worker is detached from frame | world item to offhand only at pickup contact |
| Stow | persisted world duplicate | offhand reaches into the load | offhand to bag only at stow contact |
| Shoulder | root/world duplicate travelling to torso | both palms own frame before lift | no inventory transfer |

There is never more than one visible owner of the frame. A detached frame must
remain at its persisted world position and heading while the worker translates,
crouches, or turns.

## Carry acceptance gates

- Inspect front-three-quarter, left, right, and back-three-quarter views at
  0.00, 0.30, 0.60, 0.90, and 1.20 seconds.
- Inspect the 1.20/2.40-second seam.
- Both palm centres must stay within 0.12 Blender metres of their paired grip
  centres in the authored proxy rig.
- At least one foot reads as planted at every gait key.
- Head counter-rotation preserves route visibility without cancelling the
  forward chest wedge.
- Reject hand-over-head silhouettes, backward spine, detached-looking palms,
  crossed legs, foot sliding, frame clipping, or a visible axe during the
  two-hand carry.

The Blender proxy is a deterministic review rig, not a replacement renderer.
The corresponding Minecraft animation channels remain in
`SettlerAnimations.WALK_LADEN` and `SettlerAnimations.HAUL_LOG_HEAVY`, with
the fill-dependent spine contribution in `SettlerModel.applyWorkContainer`.

