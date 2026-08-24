---
name: animation-quality
description: Craft standard and concrete workflow for authoring/revising Hearthstead's keyframe animations (SettlerAnimations.java) so tool-impact clips read with real physical weight, at a level above TekTopia. Use whenever writing or reviewing a clip that involves a swing, strike, or tool impact (CHOP, FARM_TILL, LIMB_BRANCHES, MELEE, and future combat/work clips).
---

# Animation quality — weight and impact

Researched from Game Developer / Game Anim / AnimationMentor coverage of the
Disney 12 principles applied to games (2025/2026 sources — see citations at
the end). Distilled to what applies to a single-`AnimationChannel`,
keyframe-only rig like ours (no physics, no IK, no blend trees).

## The four things that make a swing feel heavy

1. **Anticipation reads as a deliberate wind-up, not a drift.** The raise
   before a strike should visibly gather force — a bigger arc than the
   strike itself often looks wrong; what sells weight is the RATE: slow
   start, accelerating into the top of the wind-up (ease-in), not a linear
   or overly-smooth glide. If every frame in the wind-up looks the same
   speed, it reads as floaty regardless of the arc size.
2. **The strike is fast-in, and — this is the part we were missing — has a
   BEAT at the bottom.** A weightless swing snaps through the impact point
   with nothing marking it. A weighty one holds, even briefly (2-3 ticks),
   at or just past the deepest point before recoiling — the tool "sticks"
   in the wood/ground for an instant. Zero dwell at impact is the single
   most common cause of a swing reading as "there but weak."
2. **Follow-through overshoots past rest, then settles — never a direct
   return.** "A sword keeps swinging past the point of impact before coming
   to rest... this communicates inertia." If the recovery keyframe goes
   straight back to the start pose, the swing has no momentum. Add one
   keyframe between impact and rest that overshoots slightly (a few degrees
   past the eventual resting rotation), then ease into rest.
3. **The torso leads, not just the arm.** A real chop/strike starts in the
   hips/torso and the arm follows — animating the arm alone with the torso
   nearly static (our CHOP's torso only swings 5→-7→15→5°, a narrow range
   that barely precedes the arm) reads as "wrist-only," which is exactly
   what looks weightless. Torso rotation should be LARGER in proportion and
   should lead the arm's timing by a few frames (peaks slightly before the
   arm's impact frame, not simultaneously).
4. **Secondary motion sells the impact frame.** A cloak snap, a leg
   micro-plant (weight shift into the front foot), or a small head dip at
   the exact impact tick reinforces the hit without needing bigger primary
   motion. Cheap to add, disproportionately effective.

## Concrete diagnosis: CHOP as it stood before this pass

`right_arm` went -140°→-38° in a single 1-tick LINEAR jump (0.5s→0.55s) with
**zero hold** at either end, then eased 0.55→0.75s straight back toward the
rest pose with no overshoot. Technically "fast in," but with nothing to
mark the impact and no follow-through overshoot, it reads exactly as
reported: present, but weak. `torso` moves only ±10-12° across the whole
clip — the arm is doing all the work. This is the template failure mode to
check for in every tool-swing clip, not just this one.

## Workflow: see it before you ship it

Never tune swing timing by reading keyframe numbers alone — the eye catches
weight/floatiness that a spreadsheet of angles won't show you.

1. Edit keyframes in `SettlerAnimations.java`.
2. `cd hearthstead-neoforge/tools/blockbench && python3 export_bbmodel.py`
3. `node bb_render.mjs /tmp/bb <clip> 0 0.1 0.2 ... <length>` — render frames
   across the WHOLE clip at a fine enough step to see the impact tick
   isolated (0.05s/1-tick steps around the strike, coarser elsewhere).
4. Read the PNGs. Check: does the wind-up accelerate or drift? Is there a
   visible hold at impact? Does the recovery overshoot before settling?
   Does the torso lead?
5. Iterate 2 (edit) through 4 until it reads right, THEN verify with
   `tools/hearthstead-qa animation` (tick-grid, sound-contract, structural
   checks) and a real in-game `tools/hearthstead-qa live film` close-up
   with the profession's real tool actually equipped (use
   `settler.assignProfession()` via the writ item / a bound settler — a
   raw NBT `Profession` merge in a `live` test does NOT equip the tool,
   `HandItems` stays empty, and the animation will look "toolless" for a
   reason that has nothing to do with the animation itself).

## Bar to hold

Explicit user directive: Hearthstead's animation count and polish must
exceed TekTopia's, not just match it. TekTopia's villager animations are
functional but generic-weight; ours should have real anticipation/impact/
follow-through on every tool-use and combat clip, not just idle/walk.

## Sources

- [The 12 principles of animation in video games — Game Developer](https://www.gamedeveloper.com/production/the-12-principles-of-animation-in-video-games)
- [The 12 Principles Of Animation (In Video Games) — Game Anim](https://www.gameanim.com/2019/05/15/the-12-principles-of-animation-in-video-games/)
- [12 principles for game animation — Game Developer](https://www.gamedeveloper.com/game-platforms/12-principles-for-game-animation)
- [Tutorial: How to Master the 12 Principles of Animation — AnimationMentor](https://www.animationmentor.com/blog/tutorial-12-principles-of-animation/)
- [Timing in Animation: Principles, Charts & Game-Ready Guide — Sunstrike Studios](https://sunstrikestudios.com/en/blog/timing_in_animation/)
- [12 Principles of Animation for Games, Reframed — Animworks](https://anim.works/the-12-principles-of-animation-reframed-for-games/)
