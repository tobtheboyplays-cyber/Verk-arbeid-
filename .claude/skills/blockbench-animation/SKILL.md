---
name: blockbench-animation
description: Professional Blockbench/Minecraft entity animation craft — walk-cycle key poses, secondary motion and drag, squash-and-stretch at low-poly scale, easing/interpolation choice, and the Blockbench-bridge workflow. Use when authoring or revising ANY settler clip, especially locomotion and full-body cycles. Complements `animation-quality` (which covers impact/weight on swings).
---

# Animating like a pro — Blockbench and Minecraft entities

Researched from the Blockbench wiki, Bedrock Wiki, NeoForged docs and
standard walk-cycle/12-principles references (sources at the end).
`animation-quality` covers **impact and weight on swings**; this covers
**cycles, secondary motion and the tooling**. Read both before touching a
clip.

## 1. Walk cycles have four key poses — always

Every locomotion cycle (WALK, WALK_HURRIED, RUN_PANIC, WALK_LIMP,
CREEP_NIGHT, WALK_LADEN) is built from four poses per step, not two:

| pose | what defines it | body height |
|---|---|---|
| **Contact** | front heel touches down, legs at max spread | mid |
| **Down / recoil** | leading foot flat, bent leg takes the weight | **lowest** |
| **Passing** | feet together, weight over one leg | mid, rising |
| **Up / push-off** | pushing foot lifts the pelvis | **highest** |

Then mirrored for the other leg — so a full loop is 8 keyframes of leg
rotation, not 4. **A cycle authored with only contact + passing reads as
floaty**, which is the most common reason a Minecraft walk looks cheap.

The `root` POSITION channel carries the down/up rhythm: it must dip at
Down and rise at Up, twice per loop. If `root` is static, the character
glides.

## 2. Secondary motion lags the primary by 1–2 frames

"Place the animation for the secondary objects a frame or two behind the
primary object to create a lag effect." At 20 tps that is **1–2 ticks
(0.05–0.10 s)**.

Applies to: `cloak` (always — it must never peak at the same tick as the
torso), `head` (leads on intentional looks, lags on reactions), hair/pack
if they ever become animated parts, and the far end of any limb.

**Drag rule:** when the body moves, the tip of an appendage is the last to
catch up; when the body stops, the tip overshoots furthest before
settling. Our `cloak` is the settler's only real appendage tip — treat it
that way rather than as decoration.

## 3. Squash and stretch at 16px scale = 1 pixel

At our resolution, exaggeration is measured in single pixels and small
SCALE factors, not dramatic deformation:
- Body drops ~1 px on contact, rises ~1 px on passing.
- Breathing/effort is a `torso` SCALE channel around 1.01–1.03, never more.
- **Volume preservation:** if you stretch a part on one axis, narrow it on
  another. A torso scaled `(1.0, 1.03, 1.0)` reads as a breath; scaled
  `(1.03, 1.03, 1.03)` reads as the settler inflating.

## 4. Interpolation is a design choice, not a default

- `CATMULLROM` (smooth) for continuous motion: breathing, sways, cycles.
- `LINEAR` for a snap: the tick of impact, a flinch, a knockback. Every
  sound-accent keyframe in this repo must be LINEAR — `anim_check.py`
  enforces it.
- A CATMULLROM keyframe more than ~0.10 s before an impact makes the limb
  *pre-swing through* the contact point (the checker warns about exactly
  this). Put a LINEAR keyframe just before a hard contact.

## 5. Keyframe economy

"Minimize excessive keyframes to maintain smooth and manageable
animations." Every keyframe you add is one a future editor must keep
consistent. Prefer the fewest poses that read correctly — but never fewer
than the four walk poses above; that is economy, not sparseness.

Loop closure: for `.looping()` clips the first and last keyframe of every
channel must match exactly, or the loop pops. For one-shots the end pose
must match the *start* pose unless the clip is explicitly in
`ENDS_IN_POSE_ALLOWLIST` — otherwise the settler snaps when it expires.

## 6. Our tooling — the Blockbench bridge

Full setup in `hearthstead-neoforge/tools/blockbench/README.md`. Working
loop:

```bash
# after editing SettlerAnimations.java
cd hearthstead-neoforge/tools/blockbench
python3 export_bbmodel.py                     # model + all clips -> .bbmodel
node bb_render.mjs /tmp/mywork walk 0 0.25 0.5 0.75   # posed frames
```

Then **Read the PNGs** and judge the poses. For a cycle, render at each of
the four key poses and check the body actually dips and rises. Never tune
a cycle from keyframe numbers alone.

Blockbench itself (the real engine, running headless here) also offers a
Graph Editor for easing curves — useful for reasoning about a curve, but
our source of truth is `SettlerAnimations.java`, so any Blockbench-side
edit must be transcribed back by hand.

## 7. Minecraft/NeoForge specifics that bite

- **`animate()` is ADDITIVE**, not override: two clips touching the same
  bone/channel SUM. `ModelPart.resetPose()` before applying a clip is the
  only way to get a true override. This is the single most common source
  of "why does the arm swing when it should be locked."
- A per-entity phase offset (`ageInTicks + id % N`) is safe ONLY for
  looping clips. On a one-shot it can jump past the clip's own length on
  the first evaluated frame and skip it entirely — stagger the **trigger
  tick** server-side instead.
- NeoForge can define these animations in JSON
  (`assets/<ns>/neoforge/animations/entity/<path>.json`) with a Blockbench
  export plugin. We deliberately keep them in Java: the sound-contract
  checker parses the Java, and the values live next to the code that
  triggers them. Revisit only if the clip count makes Java unwieldy.
- Animation names must be valid Java identifiers (no spaces/dots).

## Standard to hold

The user's benchmark is explicit: **better than TekTopia**, both in count
and in polish. TekTopia's villagers move functionally; ours should read as
deliberately animated — four-pose cycles, lagging cloaks, weight on every
impact. When in doubt, look up how it is done properly rather than
shipping a first attempt.

## Sources

- [Blockbench Overview & Tips](https://blockbench.net/wiki/guides/blockbench-overview-tips/)
- [Bedrock Modeling and Animation — Blockbench Wiki](https://blockbench.net/wiki/guides/bedrock-modeling/)
- [Blockbench: Modeling, Texturing & Animating — Bedrock Wiki](https://wiki.bedrock.dev/guide/blockbench)
- [Entity Renderers — NeoForged docs](https://docs.neoforged.net/docs/entities/renderer/)
- [Entity Modeling and Animation — Microsoft Learn](https://learn.microsoft.com/en-us/minecraft/creator/documents/entitymodelingandanimation)
- [The poses: Contact, down, passing, and high point — LinkedIn Learning](https://www.linkedin.com/learning/2d-animation-walk-cycles-basics/the-poses-contact-down-passing-and-high-point)
- [Walk Cycle — How to animate](https://marionettestudio.com/how-to-animate-a-walk-cycle/)
- [The 12 animation principles adapted for pixel art sprites](https://www.sprite-ai.art/guides/animation-principles)
- [Pixel Art Animation Techniques For Character Movement](https://peerdh.com/blogs/programming-insights/pixel-art-animation-techniques-for-character-movement)
