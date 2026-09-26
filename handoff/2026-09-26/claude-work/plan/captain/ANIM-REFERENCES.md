# Captain weapon animation: references and rules

Battle-roles lane, 26 Sep 2026. Written after the owner called the first dual-swords reel "very bad".
The rules below apply to the dual swords first, then to the double axe, halberd and warhammer.
Sources are text and descriptions only; no animation files were downloaded.

## What went wrong in reel v1

- **The arms moved but the body did not.** The hips turned 12 degrees and the spine 10. The feet were
  narrow and the knees nearly straight, so every move looked like waving.
- **The hands stayed within about 6 px of the chest.** The blades never left the silhouette. From the
  side or at a distance the character read as a block with sticks attached.
- **The blades pointed at the camera or along the body.** They were short on screen and did not read.
- **The off-hand blade just hung.** It was tucked low and did nothing between beats.

## Takeaways from the references

| Source | What reads well | What we take |
|---|---|---|
| For Honor: Berserker (dual axes), Shinobi | An endless left/right chain. Each blow starts from the hips. The off weapon is always cocked as the next attacker. The spin chop comes out of a dodge. | The hands alternate. The off hand chambers while the main hand hits, and the recovery of one blade is the wind-up of the other. |
| God of War: Blades of Chaos (Santa Monica animators) | Very large arcs and powerful follow-through. The blade is turned ("english") so the flat faces the camera and shows its full shape. | Rotate every blade to show its FLAT in the side and 3/4 views, and swing through wide arcs well away from the body. |
| Devil May Cry: Dante's twin weapons | Exaggerated key poses held for a beat, a clean silhouette at every key, and snap between keys. | Strong keys held 2 to 3 frames, with fast in-betweens (snappy). |
| Elden Ring / Dark Souls dual wield, twinblade | Paired attacks hit either both at once (straight swords) or in a quick sequence (katanas). The twinblade has a committed spinning moveset. | Twin Thrust hits with both points converging. Blade Whirl is a real 360-degree spin. |
| Assassin's Creed Valhalla (dual wield) | The off hand follows up in the main hand's recovery, with body turns of 60 degrees and more. | The same off-hand timing, with large torso twist. |
| For Honor Warden, Raider (great axe); Mordhau, Chivalry 2 | Heavy weapons are driven by hip torque. The overhead lifts the weight high with the torso arched back, then drops it. Recovery is long, with the weapon's weight pulling the body through. | For the double axe, halberd and warhammer: a long wind-up (the weight goes up and back), the hips fire first, then shoulders, then arms, and a follow-through that drags the torso. |
| Bannerlord, Kingdom Come (halberd) | The halberd can chop overhead, sweep left or right, thrust, and hook cavalry. It is fenced from a held low guard. | The halberd set: a thrust from a low guard, a wide sweep, and the hook as reach-over, catch and yank. |
| Animation fundamentals (Animotion, Slynyrd pixel melee, MoCap Online guides) | Anticipation, then impact, then recovery. Exaggerated key silhouettes. Hold the contact pose for a beat and overshoot slightly on the stop. Light attacks run 20-35 frames at 30 fps; heavies run 45-80. | The timing ratios below, a contact hold of 2-3 frames, and a small overshoot on the stop. |

## Timing (wind-up : strike : recovery)

The server's contact tick is fixed. Everything before it is wind-up; everything after it is recovery.

| Move type | Ratio | In our clips |
|---|---|---|
| Light (dual) | 1 : 0.5 : 1.5 | 0.50 s: coil 0.00-0.12 (fast anticipation), strike 0.12-0.20, contact at 0.20, then hold, follow-through and recover to 0.50 |
| Special (dual) | 2 : 0.5 : 2 | A telegraph hang before the burst, so the player can read it |
| Heavy (axe, hammer) | 3 : 0.5 : 3 | Lift to 40-60% of the wind-up, hang 10-15% (the telegraph), then drop |

## Key-pose rules (checked on every sheet)

1. **Stance.** Wide feet: about 7-8 px apart sideways and 5-6 px apart front to back. Knees bent: the
   hips drop about 2 px. Blades at clear, different angles (one high, one low), both held well away
   from the torso.
2. **Hips lead.** In every strike the hips turn first, then the shoulders about 0.03-0.05 s later,
   then the arm, then the blade. The torso twists at least 30 degrees and the hips at least 20.
3. **Anticipation opposes the strike.** Coil the opposite way: turn right to cut left. Lean back to
   drive forward. Crouch before a spin.
4. **Arcs, not lines.** The hand travels an arc of at least 10 px and the blade tip at least 25 px.
   The blade flat faces the viewer at contact.
5. **The off hand is never idle.** It guards high, chambers at the hip, or strikes in the main
   hand's recovery.
6. **Weight.** The knee dips at contact. The front foot steps into cuts and thrusts. The body
   overshoots slightly on the stop, then settles.
7. **Silhouette.** In both the side and the 3/4 view, each key pose must read as a distinct shape
   with the blade outside the body outline.
8. **Exaggerate for blocky Minecraft.** About 1.3x real motion ranges. Hold contact 2-3 frames.
   Minecraft's big head and short arms hide small movement.

## Dual swords: key poses per move

| Move | Key poses (the owner approves this sheet before the full render) |
|---|---|
| Stance | The right blade is high, point up and slightly toward the enemy (above the right shoulder). The left blade is low and forward, pointing at the enemy's knees. The left foot leads, feet are wide and knees bent, with a small bounce. |
| Light A (right forehand) | **Coil:** hips and shoulders turned right, the right blade cocked back behind the right shoulder, the left blade forward in guard. **Contact:** hips unwind first, the right arm is fully extended, and the blade cuts a flat diagonal across the front. The left foot steps and the knee dips. **Follow-through:** the blade is carried low-left, the torso over-rotated, and the left blade chambered at the hip. |
| Light B (left backhand) | **Coil:** the left blade is chambered at the right hip (the recovery of A) with the torso turned right. **Contact:** the torso unwinds to the left and the left arm extends in a rising horizontal backhand, while the right blade returns high. **Follow-through:** the left blade finishes high-left. |
| Blade Whirl | **Telegraph:** a deep crouch with both blades crossed over the left shoulder and the torso wound right. **Spin 1:** the right blade leads a flat sweep with both arms extended. **Spin 2 (half turn):** the left blade passes the front and the feet step around. **Finish:** a full 360 degrees, landing low with the blades out wide. |
| Twin Thrust | **Chamber:** both points drawn back to the hips with the elbows back and the torso upright. **Lunge:** a long front-foot step, the knee over the toe, both arms fully extended and both points converging on one spot ahead. **Recover:** push back off the front foot. |
| Disarm | **Parry:** the left blade meets the incoming weapon high, flat vertical. **Bind:** the left blade rolls over the enemy blade and presses it down across the body. **Twist:** the hips snap, and the right blade hooks under and flicks the weapon up and out to the right. |
| Dodge Step | **Load:** a quick dip onto the right leg. **Push:** a side-step 4-5 px to the left, with the torso leaning into the step and the blades tucked. **Land:** the knee absorbs, then the stance resets. |

## Heavy weapons (next, same approach)

- **Double axe:** vertical overhead and diagonal chops. The axe goes high behind the head and the torso
  arches back. The hips fire, the axe drops through the target and bites low, then heaves out.
- **Warhammer:** even slower. At the top, a clear hang with the head of the hammer behind the back. The
  slam drives the head into the ground and the body follows onto the front knee.
- **Halberd:** fenced from a low guard. Thrusts drive from the back hand. The sweep is a wide flat arc
  from the hips. The hook reaches over, catches and yanks back with a step back.
