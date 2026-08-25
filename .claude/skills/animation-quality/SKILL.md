---
name: animation-quality
description: Craft standard and concrete workflow for authoring/revising Hearthstead's keyframe animations (SettlerAnimations.java) so every clip — tool impacts, combat, locomotion, emotes, idles — reads with real physical weight, at a level above TekTopia. Use whenever writing or reviewing ANY settler clip, and always for swings, strikes, tool impacts, stances and cycles (CHOP, FARM_TILL, MELEE, GUARD_STANCE, HAMMER_ANVIL and all future work/combat clips).
---

# Animation quality — weight, timing and readability

Distilled from the Disney 12 principles as applied to games, GDC Animation
Bootcamp material (Overwatch first-person animation), fighting-game frame
data / hit-stop practice, HEMA guard theory, and walk-cycle doctrine
(Williams) — reduced to what applies to our rig: 8 bones (`root`, `torso`,
`head`, `right_arm`, `left_arm`, `right_leg`, `left_leg`, `cloak`), Java
`AnimationDefinition.Builder`, degree/pos keyframes, CATMULLROM/LINEAR only,
20 tps (1 tick = 0.05 s, every timestamp a multiple of 0.05). No IK, no
physics, no blend trees, no fingers, no face. Everything below is stated in
ticks and degrees so it can be checked against the Java directly.
`ANIMATION_CATALOGUE.md` §0 (bones, amplitude budget, sound contract, carry
grammars) remains binding; this skill is the craft layer on top.

## 1. Core principles, with numbers at 20 tps

1. **Timing IS weight.** Speed of change, not size of arc, is what the eye
   reads as mass. A heavy strike travels 100–135° in a single tick
   (HAMMER_ANVIL: 135°/tick; CHOP: 102°/tick). Anything under ~60°/tick at
   the contact moment reads as a wave, not a hit. Conversely, heavy things
   *start* slow: the first third of a wind-up covers only ~20–25% of its
   arc, the last third covers ~50–60% (slow-out).
2. **Anticipation scales with force, and with who must read it.** Fighting
   games start a jab in ~4 frames (@60 fps ≈ 1.3 ticks) and a heavy in
   20–30 frames (≈ 7–10 ticks). Our settlers are NPCs — readability beats
   responsiveness — so work swings get 6–9 ticks of wind-up and any attack
   a player might want to dodge gets **≥ 5 ticks (250 ms, human visual
   reaction time)** of visible wind-up before the strike.
3. **The beat at the bottom is hit-stop, done in pose.** Fighting games
   freeze the game 9/11/13 frames (@60) on light/medium/hard hits — that is
   3 / 3.5 / 4.5 ticks. We cannot pause the game, so we hold the pose:
   **2 ticks for a light tap, 3–4 ticks for a heavy impact**, keys within
   ~3° of each other, LINEAR. Zero dwell at contact is the single most
   common cause of "present but weak."
4. **Follow-through overshoots by 5–15% of the travel, then settles.**
   After the hold, one keyframe passes the rest pose by 5–10% of the strike
   arc (controlled) up to 15% (cartoony/exhausted), then eases into rest.
   HAMMER_ANVIL: 12° past a −40° rest on a ~135° travel ≈ 9%. A recovery
   that goes straight back to rest has no momentum.
5. **The torso leads; the arm follows.** Force starts in the hips/torso.
   Torso peak (wind-up extreme) lands **2–3 ticks before** the arm's
   contact key, and torso range is **30–50% of the arm's range** on heavy
   work (HAMMER torso swings 58° against the arm's ~140°). Arm-only motion
   over a near-static torso is the definitive "weightless NPC" smell.
6. **Slow-in/slow-out is key spacing, not a curve setting.** CATMULLROM
   gives local smoothness; the *ease* comes from where you place keys. To
   accelerate, place intermediate keys so successive intervals cover
   growing arcs (e.g. 26° in 3 ticks, then 106° in 3 ticks); to settle,
   shrink them. The fastest point of a swing sits **60–70% through its
   arc** — put the LINEAR snap late, not at the start.
7. **Arcs.** Euler bone rotation arcs are free per-bone, but a swing keyed
   only on x travels a flat plane. Give every big swing a secondary y or z
   component (8–20°) so the hand traces a diagonal. `root` POSITION moves
   need a mid keyframe to arc — a two-key posVec is a dead straight line.
8. **Secondary action lags 1–2 ticks; cloak lags 2–3.** The cloak is the
   rig's drag/appendage channel: it must never peak on the same tick as the
   torso and must never be static in a moving clip. When the body stops,
   the cloak overshoots furthest before settling.
9. **Asymmetric timing = gravity.** Down happens faster than up. A bob that
   rises in 8 ticks should fall in 4–5. A perfect sine reads mechanical;
   offset the midpoint so the two halves differ by ≥ 15% of the cycle.
10. **Silhouette test, analytically.** Fill the pose black: is the action
    readable? On our rig that means: limbs separated from the torso
    (y/z components open negative space), one intention per pose ("one line
    = one intention" — a pose saying two things says nothing), and the five
    canonical poses (idle, attack, walk, hit, celebrate) each distinct at a
    glance. If two clips share their extreme pose, one of them is wrong.
11. **Stillness sells violence.** The off-hand that grips the work and
    barely moves (≤ 4° total) is what makes the striking hand read. Same at
    catalogue scale: FINE_WORK exists so HAMMER_ANVIL looks heavy. Never
    give every bone the same energy.
12. **Exaggeration at 16 px = pixels and degrees, not deformation.**
    Squash/stretch on a rigid rig is pose: contact drops `root` 0.7–1.3 px,
    wind-up rises it 0.3–0.75 px, breath is torso SCALE 1.01–1.03 on y only
    (compensate other axes; uniform scale = inflation).

## 2. Recipes per category

### 2.1 Work-loop impacts (HAMMER_ANVIL is the template)

Phase budget for a 20-tick (1.0 s) loop — scale proportionally to 1.05–1.4 s:

| phase | ticks | what happens |
|---|---|---|
| settle-in | 0–3 | rest pose drifts toward the wind-up, CATMULLROM |
| wind-up | 3–8 | accelerating raise: first half ≤ 1/3 of the arc, second half the rest; torso rotates opposite the coming strike |
| suspension | 1–2 | near-hang at the top (arm moves ≤ 7°) — the "gather" |
| strike | 1–2 | 90–135°/tick heavy, 60–90 medium; LINEAR on this key AND the key before it; torso snaps forward LINEAR; `root` drops 0.7–1.3 px |
| impact hold | 2–4 | keys within 3°, LINEAR; head counter-dips 5–12°; this is the accent frame |
| release + overshoot | 3–5 | passes rest by 5–15% of strike travel |
| settle | 3–6 | CATMULLROM ease back to the loop start pose |

- Accent frame = the first hold key. `K = round(contact_s * 20)`,
  `P = round(length_s * 20)`; comment, goal modulo and `anim_check.py`
  must agree. The accent must never sit within 2 ticks of t=0 or t=length.
- Torso range 30–50% of arm range; torso peak 2–3 ticks before contact.
- Legs are load-bearing: at contact at least one leg key shifts ≥ 3° and/or
  `root` dips ≥ 0.3 px. A striking body with statue legs floats.
- **No-impact loops (SAW, TANNER_SCRAPE) put the weight in the reversals:**
  a 2-tick LINEAR hold at each end of the stroke while the blade/scraper
  "bites," torso rocking with the arms. A smooth sine saw is waving.

### 2.2 Combat

**MELEE / attacks (one-shots).** Total 0.75–1.2 s (one-hand light swings
complete in 0.4–0.8 s in action games; two-hand heavies 0.8–1.5 s). Phase
ratio: recovery ≥ wind-up > strike. Wind-up ≥ 5 ticks (dodgeable telegraph),
strike 1–2 ticks, hold 2–3 ticks, recovery 6–10 ticks with overshoot then a
long ease — "stay in the recovery pose, then pop back to idle quick." If a
follow-up action ever chains, it branches ~70% through recovery. Hit
*reactions* are the opposite: brief (6–10 ticks, 0.3–0.5 s) and snappy.

**GUARD_STANCE — how a trained swordsman rests.** Model on the low guard
(Pflug-derived): controlled, threatening, zero wasted tension.

- **Blade:** point forward-and-down **30–45° below horizontal**, aimed at an
  imagined opponent's knee/ground line. Never hanging straight down
  (defeated), never wrist-flicked upward.
- **Wrist/forearm:** the blade continues the forearm line — wrist straight.
  On our rig (no wrist bone) that means the arm's x sets the blade angle
  directly: `right_arm` x ≈ **−20 to −35°**, with y ≈ −5 to −15° drawing
  the hilt in front of the hip. Elbow "bend" is implied by that y plus a
  small z (3–8°) tucking the upper arm to the ribs.
- **Weight:** slightly forward and ready — torso x +4–8°, feet staggered
  (legs x ∓8–14°, z out 2–5°), knees soft. Weight ~60/40 on the front foot.
- **Off-hand/shield:** across the body, left_arm x −15 to −25°, y +10–20°.
- Greatsword/axe variant: point rested on the ground, both hands stacked on
  the grip, torso upright — imposing through stillness, not readiness.
- **Confident vs nervous is a numbers swap, not a new pose.** Confident:
  breath cycle 3.5–4 s, sway 1–3°, head still with one slow deliberate scan
  (y ≤ 20°) per 4–8 s. Nervous: cycle 2–2.5 s and shallower, quick head
  checks ±8–15° at irregular intervals, weight micro-shifts, a grip
  re-adjust beat. Same skeleton pose, different clock.

### 2.3 Locomotion cycles

Cadence (from the 24 fps step chart, converted): natural walk = 12
frames/step = 0.5 s/step → **1.0–1.2 s full loop**; hurried/jog 0.7–0.8 s;
flat-out run 0.5–0.6 s. A limp is *asymmetric time*, not just asymmetric
pose: the good leg's step takes ~45% of the cycle, the bad leg's ~55–65%.

- **Four poses per step — always** (contact / down / passing / up), so 8 leg
  keys per loop. Contact: legs at max spread (stride 32–40°), arms opposite.
  **Down comes 1–2 ticks AFTER contact** — the knee absorbs, `root` hits its
  lowest (−0.4 px walking, halved when laden). Up: highest point off the
  push. `root` static = gliding ghost.
- **Counter-rotation:** torso yaw opposes the forward leg by ~5° (≤ 6°), and
  rolls 1–2° toward the planted leg as it takes weight. Our rig has no
  pelvis, so torso carries both jobs; head counter-stabilizes (stays roughly
  world-level, lagging torso by 1–2 ticks).
- Run: torso leans forward 10–20°, bob roughly doubles, arms bend higher and
  swing harder; panic adds head-back and over-stride.
- Carry variants: obey §0.5 of the catalogue (arms locked means locked; the
  read moves to legs, torso, cloak, cadence).

### 2.4 One-shot emotes / social clips

Structure: **counter-move, snap, HOLD, release.** (1) 2–4 ticks of
anticipation *opposite* the gesture (a wave starts with the hand dipping);
(2) reach the storytelling pose fast, 2–4 ticks; (3) **hold that pose 40–60%
of the clip** with only micro-motion (≤ 3°) — the audience reads the pose,
not the transition; (4) release with one small overshoot key, then rest.
End pose must equal the rest pose unless the clip is in
`ENDS_IN_POSE_ALLOWLIST`, or the settler snaps on expiry. Never phase-offset
a one-shot per entity (it can skip the whole clip) — stagger the server-side
trigger tick instead.

### 2.5 Ambient / breathing / idle layers

- Breath rate: relaxed 15–20 breaths/min → **3–4 s cycle**; alert 2.4–3 s;
  exhausted/panicked 1.5–2 s with double amplitude. Chest travel is 1–2 cm
  in life ≈ torso SCALE 1.01–1.03 (y) or ≤ 0.5 px here — perceptible,
  barely.
- Ripple upward with offsets: torso first, shoulders (arm z 1–2°) +1 tick,
  head +1–2 ticks. Simultaneous rise reads as one rigid block inflating.
- Weight shift foot-to-foot on a 4–8 s clock for long idles; head "floats"
  (never frozen, never metronomic).
- **Additive hazard:** `animate()` SUMS channels. A breathing layer may only
  touch bone/channel pairs the base clip does not key (SCALE on torso is the
  safe channel), or the sum drifts the pose. `resetPose()` first is the only
  true override.

## 3. Critique checklist — run against the Java, per clip

Measurable from keyframes alone; check before ever rendering:

1. **Peak velocity:** max °/tick on the primary channel. Impact clip:
   ≥ 90 (heavy) / 60 (medium) at contact. If no channel exceeds ~30°/tick
   anywhere, the clip has no accent at all — flag it.
2. **Impact hold:** ≥ 2 consecutive keys within 3° spanning 2–4 ticks
   immediately after the fastest interval. Absent → "present but weak."
3. **Overshoot:** between hold and final key, at least one key past the rest
   value by 5–15% of the strike travel. Straight-home recovery → flag.
4. **Torso lead:** torso extreme timestamp ≤ (arm contact − 2 ticks); torso
   range ≥ 30% of arm range on heavy work, ≥ 20% on medium.
5. **Leg involvement:** any leg delta ≥ 3° or root dip ≥ 0.3 px at contact.
6. **Loop seam continuity — value AND velocity:** first/last key equal per
   channel (value), and the slope into t=L vs out of t=0 (Δ°/Δtick of the
   adjacent intervals) neither opposite in sign nor > 2× apart. A seam that
   matches position but not velocity pops every loop.
7. **Interpolation contract:** LINEAR on the contact key and the key before
   it; no CATMULLROM key within 2 ticks ahead of an impact (pre-swing).
8. **Accent placement:** accent frame ≥ 2 ticks away from t=0 and t=L, and
   `K/P` matches the goal's modulo and the clip comment.
9. **Grid:** every timestamp and the length are multiples of 0.05 s.
10. **Amplitude budget:** within catalogue §0.3 (arm work wind-up 100–170°,
    torso lean 20–32°, stride 32–40°, cloak 6–16°, root −2..−9 px, etc.).
11. **Asymmetry:** idle/bob halves differ ≥ 15% in duration (down faster);
    left and right arms not numerically mirrored in idles and stances.
12. **Cloak:** keyed in every moving clip; peak lags torso peak 2–3 ticks;
    never peaks on the torso's tick.
13. **Head:** participates at contact (3–12° dip, LINEAR) and in stances
    (scan or stabilize); a head keyed flat across an action is dead weight.
14. **Economy:** 4–10 keys per channel is the normal band. > 14 = noise a
    future editor must maintain; < 3 on the primary channel of an action =
    drift, not motion.
15. **Silhouette divergence:** the clip's extreme pose differs from every
    other clip's extreme on at least one major bone by ≥ 20° — no two clips
    may share their read.

## 4. Failure smells seen in this codebase

- **The old CHOP** (canonical bad example): arm −140°→−38° in 1 tick,
  LINEAR, **zero hold**, straight-line recovery, torso ±10–12° doing none of
  the work. Fast-in but nothing marking impact = "there but weak." Checks
  2, 3, 4 above catch it analytically.
- **Loop-wrap cut-off:** recovery still moving fast at t=length; value
  matches t=0 but velocity doesn't, so the loop hitches every cycle.
  Usually caused by cramming settle into too few end ticks. Check 6.
- **Sound at the loop seam:** accent frame at/near t=0 or t=length. The
  server tick modulo is ambiguous at the seam and the visual contact never
  aligns with the thock. Put contact mid-clip. Check 8.
- **Floppy wrists:** hand-flourish read attempted with arm y/z wiggles over
  a static torso. No wrist bone exists — perceived wrist energy must come
  from torso support plus arm rotation together, or it reads as a rubber
  glove. Check 4.
- **Legs left behind:** heavy upper-body work with legs frozen at the pose
  values for the whole loop. Statue-legs float. Check 5.
- **Metronome idle:** perfect-sine bob, both halves equal, both arms
  mirrored — reads as a wind-up toy. Checks 11.
- **Everything at the same energy:** off-hand swinging as hard as the tool
  hand, every trade animated from the shoulder. Contrast (still vs violent,
  FINE_WORK vs HAMMER_ANVIL) is what creates scale.
- **Toolless render confusion:** in a `live` test, a raw NBT `Profession`
  merge does NOT equip the tool (`HandItems` stays empty) — use
  `settler.assignProfession()` via the writ item, or the clip will look
  broken for a reason that has nothing to do with the animation.

## 5. Workflow: see it before you ship it

Never tune timing from keyframe numbers alone — the checklist catches
structure, only the eye catches feel.

1. Edit keyframes in `SettlerAnimations.java`; run the §3 checklist
   mentally/by script against the diff.
2. `cd hearthstead-neoforge/tools/blockbench && python3 export_bbmodel.py`
3. `node bb_render.mjs /tmp/bb <clip> <times...>` — 1-tick steps (0.05 s)
   around the contact, coarser elsewhere; for cycles render all four poses
   per step and confirm the body dips and rises.
4. Read the PNGs: does the wind-up accelerate or drift? Is the hold visible?
   Does recovery overshoot? Does the torso lead? Black-fill silhouette
   distinct from other clips?
5. Iterate 1–4, then verify via `tools/hearthstead-qa animation`
   (tick-grid, sound-contract, structural checks) and a real in-game
   `tools/hearthstead-qa live film` close-up with the profession's actual
   tool equipped.

## 6. Bar to hold

Explicit user directive: Hearthstead must exceed TekTopia in both clip count
and polish. TekTopia villagers move functionally; every Hearthstead clip has
anticipation, a marked contact, follow-through, a leading torso and a
distinct silhouette. LOCKED means all §3 checks pass AND the rendered/live
footage reads right — a green structural check on a dead-feeling clip is not
done.

## Sources

- [The 12 principles of animation in video games — Game Developer](https://www.gamedeveloper.com/production/the-12-principles-of-animation-in-video-games)
- [The 12 Principles Of Animation (In Video Games) — Game Anim](https://www.gameanim.com/2019/05/15/the-12-principles-of-animation-in-video-games/)
- [Animation Bootcamp: The First Person Animation of Overwatch — GDC Vault (Matt Boehm, 2017)](https://gdcvault.com/play/1024319/Animation-Bootcamp-The-First-Person) — personality within strict frame budgets (a 45-frame reload), readability first
- [Impact Freeze — sonichurricane.com](https://sonichurricane.com/?p=1043) — hit-stop 9/11/13 frames @60 by attack weight
- [Anticipation, Action, Recovery — Rivals Workshop Library](https://www.rivalslib.com/workshop_guide/art/anticipation_action_recovery.html) — pose fast, hold, pop back; stronger = more obvious anticipation
- [Frame Data — SuperCombo Wiki](https://wiki.supercombo.gg/w/Frame_Data) — jab ~4-frame startup vs heavy 20–30
- [Sword & Melee Weapon Animation guide — MoCap Online](https://mocaponline.com/blogs/mocap-news/sword-melee-animation-guide) — swing/recovery second budgets, 60–70% peak-velocity point, weapon idle stances
- [Idle Animation for Games — MoCap Online](https://mocaponline.com/blogs/mocap-news/idle-animation-game-dev-guide) — breath rates, 1–2 cm chest travel, weight-shift clocks
- [Pixelblog 52: Idle Fighting Stance — SLYNYRD](https://www.slynyrd.com/blog/2024/9/26/pixelblog-52-idle-fighting-stance) — asymmetric bob timing at pixel scale
- [Tutorial: Animate Natural Breathing Loops — Animation Mentor](https://www.animationmentor.com/blog/tutorial-animate-natural-breathing-loops/) — belly→chest→shoulders→head offsets
- Richard Williams, *The Animator's Survival Kit* — step timing chart (12 f/step walk, 6–8 f/step run) via [Monmouth walk-cycle notes](https://animation.monmouth.edu/instruct/animation/walk-cycle/)
- [Anatomy of a Walk Cycle — Polygon Treehouse](https://www.polygon-treehouse.com/blog/2019/1/30/anatomy-of-a-walk-cycle) — hip roll/weight transfer, counter-rotation
- [German Longsword Techniques — historicaleuropeanmartialarts.com](https://www.historicaleuropeanmartialarts.com/2020/10/05/german-long-sword-fighting-techniques-in-hema/) and [Meyer's Longsword 101 — HEMA101](https://www.hema101.com/post/meyer-s-longsword-101-chapters-1-4) — Pflug/vom Tag/Ochs guard geometry
- [Realistic Bounce and Overshoot — motionscript.com](https://motionscript.com/articles/bounce-and-overshoot.html) / [Animation Easing Explained — OlafMotion](https://olafmotion.com/motion-knowledge/animation-easing-explained-guide/) — 5–10% controlled overshoot
- [How to Create a Readable Silhouette in Gameplay Animation — AnimotionX](https://www.animotionx.com/en/post/how-to-create-a-readable-silhouette-in-gameplay-animation) / [Silhouette in Animation — Animworks](https://anim.works/silhouette-in-animation/) — black-fill test, one intention per pose, five canonical poses
- [How to Design Enemy Attack Telegraphs — Bugnet](https://bugnet.io/blog/how-to-design-enemy-attack-telegraphs) — NPC wind-ups must beat player reaction time
