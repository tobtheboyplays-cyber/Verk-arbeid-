# Hearthstead — Animation Catalogue

The complete keyframe catalogue for the settler. This is the direct source for
`src/main/java/com/hearthstead/client/model/SettlerAnimations.java`: every clip
below is specified to the point where it can be typed as an
`AnimationDefinition` without further design decisions.

**Standing product rule (owner directive):** *every task has its own
animation.* A task that borrows a neighbouring task's clip is a placeholder,
not a feature. Reference bar is TekTopia; the instruction is to beat it. The
way we beat it is **silhouette** — each clip below carries a one-sentence
silhouette idea, and that silhouette must be readable from 30 blocks with no
nameplate, no particles, no item in hand.

Theme is fixed: grounded European medieval. Work is heavy, tools are heavy,
people brace against weight. Nothing floats.

---

## 0. Conventions

### 0.1 Bones

Clips may only address these eight bones. Names must match
`SettlerModel.createBodyLayer()` exactly.

| bone | parent | pivot | notes |
|---|---|---|---|
| `root` | — | (0, 24, 0) | whole-body offset; **the only bone allowed a large `posVec`** (crouch, sink, hop, ladder rise) |
| `torso` | root | (0, −12, 0) | 10-wide chest; `SCALE` on torso = breath |
| `head` | torso | (0, −12, 0) | additive head tracking is layered on top by `setupAnim` — never fight it with big yaw |
| `right_arm` | torso | (−6, −10, 0) | tool hand (`ArmedModel.translateToHand` uses it) |
| `left_arm` | torso | (+6, −10, 0) | shield / off-hand / crate hand |
| `right_leg` | root | (−2.6, −12, 0) | |
| `left_leg` | root | (+2.6, −12, 0) | |
| `cloak` | torso | (0, −12, 0) | the shoulder cape. **Cloak is the mod's signature drag channel** — it lags the torso by 0.10–0.15 s and overshoots on stops. Never leave cloak static in a moving clip. |

`hood`, `hat_brim`, `backpack` and `belt` are visibility-toggled parts, not
animation targets. Do not address them from a clip.

### 0.2 Units and grid

- Rotations in degrees via `KeyframeAnimations.degreeVec(x, y, z)`.
- Positions in model pixels via `posVec(x, y, z)`, **y is up-positive**.
- Scale via `scaleVec` (breath only; 1.00–1.03 range).
- Minecraft runs at 20 tps. **Every keyframe timestamp must be a multiple of
  0.05 s**, and every clip length must be a multiple of 0.05 s, so an accent
  frame can be matched exactly by a server-side tick counter. All times in
  this document already obey that.
- Interpolation: `CATMULLROM` for flowing motion, `LINEAR` on **and around** an
  impact frame. An impact needs the key *before* it to be LINEAR too, otherwise
  Catmull-Rom pre-swings through the contact and the hit reads soft.

### 0.3 Amplitude budget

`anim_check.py` rejects rotations beyond 180°, `posVec` beyond 12 px, scale
outside 0.5–1.5. Those are hard limits, not targets. House style:

| motion | typical peak |
|---|---|
| arm swing, walking | 28–34° |
| arm wind-up, tool work | 100–170° |
| torso lean, work | 20–32° |
| torso twist (y) | ≤ 22° |
| head glance (y) | ≤ 30° (leave headroom for tracking) |
| leg stride | 32–40° |
| cloak drag | 6–16° |
| `root` crouch/sink | −2 to −9 px |
| `root` hop | +2 to +3 px |

### 0.4 Sound-sync contract

Each clip that makes a noise names an **accent frame**: a timestamp inside the
clip and the sound the server must play at the matching tick. The contract is
written three places and must agree in all three:

1. a comment above the clip in `SettlerAnimations.java`,
2. the goal's tick modulo (`workTicks % P == K`),
3. an assertion in `tools/anim_check.py`.

Conversion: `K = round(accent_seconds * 20)`, `P = round(length_seconds * 20)`.
Established precedent — `CHOP` strikes at t=0.55 s of a 1.0 s loop and
`LumbererWorkGoal` plays the thock at tick 11 of a 20-tick cycle.

The accent frame is also where the **client-side animation state must be
running for the sound to be believable**. For one-shots the server broadcasts
an entity event (`EV_MELEE` pattern) and the sound is played in the same tick.

### 0.5 Carried-item handling

Hearthstead's logistics are item-accurate: what a settler carries physically
exists. Animation therefore has to sell weight. Four carry grammars, used
consistently across the catalogue:

| grammar | arms | torso | stride | used by |
|---|---|---|---|---|
| **CRATE** (two-handed, chest height) | both arms locked forward-up, `x ≈ −78°`, elbows implied by inward `z`; no swing at all | leans **back** 10–14° to counterweight, and the whole clip rides 1 px lower on `root` | 0.85× length, 1.15× cadence | courier crate, cook's cauldron, healer's basket |
| **SHOULDER** (one-handed, over-shoulder) | right arm up and back gripping the load (`x ≈ −140°`, `z ≈ −18°`), left arm swings **more** than normal to compensate | leans forward 8° and **tilts 6° away** from the loaded side; hips counter-rotate | 0.9× length | lumberer log, miner ore sack |
| **CARRIED BODY** (a downed settler) | both arms cradled, `x ≈ −70°`, wide `z` spread | leans back 16°, visible strain wobble at 0.5 Hz | 0.75× length, wide stance | rescue carry |
| **VESSEL** (small, held level) | one arm bent to 90°, held rock-steady; the *other* arm and the torso do all the moving | upright, minimal bob | 0.95× length | tankard, water bucket, bandage kit |

Rules that apply to all four:

- **Arms locked means locked.** A carry clip's arm channels hold a single pose
  for the whole loop with at most 2° of jiggle. The read comes from legs, torso
  and cloak. This is the single biggest reason TekTopia hauling looks weightless
  and ours will not.
- **`root` drops.** Any load ≥ crate weight rides at `posVec(0, −1, 0)` on root
  for the whole clip. The settler is compressed by the load.
- **Vertical bob halves.** Loaded walk bobs `−0.2` px instead of the unloaded
  `−0.4`; a laden person does not bounce.
- **Cloak stops swinging** under CRATE and CARRIED BODY (the load pins it) but
  swings *harder* under SHOULDER (asymmetric gait throws it).
- The held `ItemStack` follows `right_arm` through `translateToHand`. For CRATE
  the crate model must instead be pinned between both hands — render it from the
  `torso` transform at a fixed forward offset, not from the arm, or it will
  shear when the arms jiggle.

### 0.6 Naming

Java constant style, `SCREAMING_SNAKE_CASE`, grouped by domain prefix:
`WALK_*` / `RUN_*` locomotion, profession clips by verb (`FARM_TILL`,
`SMITH_HAMMER`), social `SOCIAL_*` where ambiguous, emergency `EMERGENCY_*`,
reaction beats `REACT_*`. Existing constants (`IDLE`, `WALK`, `EAT`, `FARM`,
`CHOP`, `GUARD_STANCE`, `MELEE`, `REST`, `CELEBRATE`) keep their names where
they survive; §16.5 records the one rename.

### 0.7 Activity enum extensions

`SettlerActivity` currently has 10 values. The catalogue needs these additional
synced states. Add them **at the end** of the enum — `id()` is the wire format
and `byId` is used on the client, so ordinals must not shift.

```
WORK_TILL, WORK_PLANT, WORK_HARVEST, WORK_WATER,      // farmer split
WORK_LIMB, HAULING_LOG,                                // lumberer
CARRYING, SORTING,                                     // courier
SERVING, POURING, COOKING, STIRRING,                   // inn + kitchen
MINING, SMITHING, BELLOWS, QUENCHING,                  // miner + smith
HEALING, TENDING, REVIVING,                            // healer
WRITING, TEACHING,                                     // scribe
SLEEPING, MOURNING, SOCIALIZING, PLAYING,              // life
BUCKET_CHAIN, REPAIRING, CARRYING_DOWNED, COWERING,
MILITIA, CAPTIVE,                                      // raid
SHIVERING, EXHAUSTED, STARTLED                         // reactions
```

Sub-states that are *phases of one activity* (crate lift → carry → set down)
are **not** separate enum values; they are one activity plus a phase byte or a
one-shot entity event, exactly as `MELEE` works today. Each clip below names
which mechanism drives it: **`activity`** (looping, synced state) or
**`event`** (one-shot, `broadcastEntityEvent`).

### 0.8 The catalogue at a glance

65 primary clips and 5 sub-variants. `L` = looping, `1` = one-shot,
`+` = additive/layer.

| § | clip | s | kind | phase |
|---|---|---|---|---|
| 1.1 | `WALK` | 1.00 | L | A1 |
| 1.2 | `WALK_LADEN` | 1.20 | L+ | A2 |
| 1.3 | `WALK_HURRIED` | 0.70 | L | A1 |
| 1.4 | `RUN_PANIC` | 0.55 | L | A1 |
| 1.5 | `WALK_LIMP` | 1.40 | L | A1 |
| 1.6 | `CREEP_NIGHT` | 1.60 | L | A1 |
| 1.7 | `CLIMB_LADDER` | 1.00 | L | A1 |
| 2.1 | `FARM_TILL` | 1.50 | L | A1 |
| 2.2 | `FARM_PLANT` | 2.00 | L | A1 |
| 2.3 | `FARM_HARVEST` | 1.80 | L | A1 |
| 2.4 | `FARM_WATER` | 2.40 | L | A1 |
| 3.1 | `CHOP` | 1.00 | L | A1 |
| 3.2 | `LIMB_BRANCHES` | 1.30 | L | A1 |
| 3.3 | `HAUL_LOG` | 2.40 | L+ | A1 |
| 4.1 | `GUARD_STANCE` | 3.00 | L | A1 |
| 4.2 | `GUARD_PATROL` | 4.00 | L+ | A1 |
| 4.3 | `MELEE` | 0.50 | 1 | A1 |
| 4.4 | `SHIELD_BLOCK` | 1.60 | L | A1 |
| 4.5 | `HORN_CALL` | 2.60 | 1 | A3 |
| 4.6 | `RALLY` | 1.80 | 1 | A3 |
| 5.1 | `COURIER_LIFT` | 1.40 | 1 | A2 |
| 5.2 | `COURIER_CARRY` | 2.00 | L+ | A2 |
| 5.3 | `COURIER_SET_DOWN` | 1.20 | 1 | A2 |
| 5.4 | `COURIER_SORT` | 1.60 | L | A2 |
| 6.1 | `INN_POUR` | 2.80 | L | A2 |
| 6.2 | `INN_SERVE` | 1.60 | 1 | A2 |
| 6.3 | `INN_GREET` | 2.20 | 1 | A2 |
| 7.1 | `COOK_CHOP_VEG` | 1.20 | L | A2 |
| 7.2 | `COOK_STIR` | 2.40 | L | A2 |
| 7.3 | `COOK_SERVE_MEAL` | 1.80 | 1 | A2 |
| 8.1 | `MINE_PICK` | 1.20 | L | B1 |
| 8.2 | `MINE_HAUL_ORE` | 2.60 | L+ | B1 |
| 9.1 | `SMITH_HAMMER` | 1.00 | L | B1 |
| 9.2 | `SMITH_BELLOWS` | 2.00 | L | B1 |
| 9.3 | `SMITH_QUENCH` | 2.00 | 1 | B1 |
| 10.1 | `HEAL_BANDAGE` | 2.20 | L | A3 |
| 10.2 | `HEAL_TEND_HERBS` | 3.20 | L | A3 |
| 10.3 | `HEAL_REVIVE` (+`REVIVE_SUCCESS`/`REVIVE_FAIL`) | 4.00 | 1 | A3 |
| 11.1 | `SCRIBE_WRITE` | 3.00 | L | B2 |
| 11.2 | `SCRIBE_TEACH` | 4.60 | L | B2 |
| 12.1 | `SLEEP_IN_BED` | 8.00 | L | A1 |
| 12.2 | `WAKE_STRETCH` | 2.60 | 1 | A1 |
| 12.3 | `EAT` | 1.20 | L | A1 |
| 12.4 | `EAT_AT_TABLE` | 3.60 | L | A2 |
| 12.5 | `SOCIAL_TALK` | 3.00 | L | A2 |
| 12.5 | `SOCIAL_LISTEN` | 3.00 | L | A2 |
| 12.6 | `MOURN` (+`MOURN_SOB`) | 6.00 | L | A3 |
| 12.7 | `CELEBRATE` | 2.00 | 1 | A1 |
| 12.8 | `GIFT_ACCEPT` | 2.40 | 1 | A3 |
| 12.9 | `CHILD_PLAY` | 2.80 | L | B2 |
| 12.10 | `COUPLE_GREET` | 2.60 | 1 | B2 |
| 12.11 | `IDLE` | 4.00 | L | A1 |
| 12.12 | `REST` | 6.00 | L | A1 |
| 13.1 | `EMERGENCY_FLEE_SHELTER` | 1.10 | L | A3 |
| 13.2 | `EMERGENCY_BUCKET` | 1.60 | L | B1 |
| 18.1 | `KNEAD` | 1.20 | L | A2 |
| 18.2 | `CLEAVE` | 0.85 | L | A2 |
| 18.3 | `STOKE` | 1.40 | L | A2 |
| 18.4 | `HAMMER_ANVIL` | 1.00 | L | A2 |
| 18.5 | `SAW` | 1.10 | L | A2 |
| 18.6 | `FINE_WORK` | 0.90 | L | A2 |
| 18.7 | `GATHER_LOG` | 1.10 | 1 | A2 |
| 18.8 | `OVEN_TEND` | 1.60 | L | A2 |
| 18.9 | `SOW_BROADCAST` | 1.40 | L | A2 |
| 19.1 | `LEAP_STRIKE` | 1.30 | 1 | A3 |
| 13.3 | `EMERGENCY_REPAIR` | 1.80 | L | A3 |
| 13.4 | `EMERGENCY_CARRY_DOWNED` (+`DOWNED`) | 2.20 | L+ | A3 |
| 13.5 | `EMERGENCY_COWER` (+`COWER_FLINCH`) | 2.60 | L | A3 |
| 13.6 | `MILITIA_STANCE` | 2.20 | L | A3 |
| 13.7 | `CAPTIVE` | 5.00 | L | B1 |
| 14.1 | `REACT_STARTLE` | 0.60 | 1+ | A3 |
| 14.2 | `REACT_SHIVER` | 0.80 | L+ | B1 |
| 14.3 | `REACT_EXHAUSTED` | 3.40 | L | A2 |
| 14.4 | `REACT_HUNGRY` | 1.40 | 1+ | A2 |
| 14.5 | `REACT_BLESSED` | 2.80 | 1+ | A3 |

Per phase: **A1 23, A2 16, A3 14, B1 8, B2 4 = 65.**

---

## 1. Locomotion set

Locomotion is consumed through `animateWalk(...)`, which scales the clip by
limb-swing amount — so the *authored* amplitude is the full-speed pose. All
locomotion clips are 1-block-per-cycle-matched: keep leg peak near ±35° or the
feet will skate.

### 1.1 `WALK` — the everyday gait *(exists; retune)*

- **Trigger:** any settler moving at normal speed. Default locomotion layer.
- **Activity:** none — driven by `walkAnimation.speed()`, always active under
  the pose clips.
- **Length:** 1.00 s, **looping**.
- **Silhouette:** an unhurried working stride with a visible heel-fall dip and a
  cloak that lags half a beat behind the shoulders.
- **Bones:**
  - `right_leg` ROT: −35 @0.00 → 0 @0.25 → +35 @0.50 → 0 @0.75 → −35 @1.00
  - `left_leg` ROT: mirrored (+35 @0.00 …)
  - `right_arm` ROT: (28, 0, 2) @0.00 → (−28, 0, 2) @0.50 → (28, 0, 2) @1.00
  - `left_arm` ROT: mirrored on x, z = −2
  - `torso` ROT: (3, 4, 0) @0.00 → (3, −4, 0) @0.50 → (3, 4, 0) @1.00 — the y
    channel is the counter-rotation that makes it read as a person, not a doll
  - `torso` POS: 0 → (0, −0.4, 0) @0.25 → 0 @0.50 → (0, −0.4, 0) @0.75 → 0 — two
    dips per cycle, one per footfall
  - `cloak` ROT: 2 @0.00 → 9 @0.25 → 2 @0.50 → 9 @0.75 → 2 @1.00
  - `head`: **leave free** for tracking.
- **Retune vs. current:** add `cloak` z-sway of ±3° at the half-beats (currently
  x-only, which reads stiff from the side), and add 1.5° of `torso` z-roll
  toward the planted foot.
- **Accent:** footfalls at **t = 0.25 s and 0.75 s** — vanilla step sounds fire
  from movement, so no custom sound; but any future boot-on-plank foley must use
  these two timestamps.
- **Carry:** none.

### 1.2 `WALK_LADEN` — carrying something heavy

- **Trigger:** courier with a crate, lumberer with a log, miner with an ore
  sack, anyone whose `bag` is over the haul threshold. Replaces `WALK` as the
  locomotion layer whenever the carry flag is set.
- **Activity:** `CARRYING` / `HAULING_LOG` (activity selects which *arm* pose
  overlays; the leg-and-torso engine below is shared — see §16.2 reuse rule).
- **Length:** 1.20 s, **looping** (slower cadence = heavier read).
- **Silhouette:** a short-stepping, backward-leaning wedge — the load is
  invisible at 30 blocks but the lean makes you certain there is one.
- **Bones:**
  - `root` POS: hold (0, −1, 0) for the whole loop — compressed by the load.
  - `right_leg` ROT: −24 @0.00 → 0 @0.30 → +24 @0.60 → 0 @0.90 → −24 @1.20
    (stride cut to 0.7× of `WALK`; laden people take short steps)
  - `left_leg` ROT: mirrored.
  - `torso` ROT: (−12, 3, 0) @0.00 → (−12, −3, 0) @0.60 → (−12, 3, 0) @1.20 —
    **negative x = leaning back**, the counterweight. Y-sway halved from `WALK`.
  - `torso` POS: 0 → (0, −0.2, 0) @0.30 → 0 @0.60 → (0, −0.2, 0) @0.90 → 0.
  - `right_arm` / `left_arm`: **not animated here.** The carry grammar clip
    (§4.2 `COURIER_CARRY`, §3.3 `HAUL_LOG`) owns the arms and must run on a
    second animation state layered over this one.
  - `cloak` ROT: hold (4, 0, 0) ±1° — pinned by the load, does *not* swing.
  - `head` ROT: (6, 0, 0) constant offset — chin tucked over the load; tracking
    still layers on top.
- **Accent:** heavier footfalls at **t = 0.30 s and 0.90 s** → play
  `hearthstead:haul_step` at ticks 6 and 18 of a 24-tick cycle. Low, scuffing,
  0.5 volume.
- **Carry:** grammar CRATE or SHOULDER — see §0.5.

### 1.3 `WALK_HURRIED` — the errand jog

- **Trigger:** courier with a priority request, guard moving to a post,
  innkeeper crossing to a waiting traveler, anyone with a "late" flag. Speed
  modifier is set on the entity; this clip matches it.
- **Activity:** `TRAVELING` with the hurry flag; locomotion layer.
- **Length:** 0.70 s, **looping**.
- **Silhouette:** a forward-pitched trot with tight, pumping elbows — busy, not
  frightened; the difference from panic is that the head stays level.
- **Bones:**
  - `right_leg` ROT: −40 @0.00 → 0 @0.175 → +40 @0.35 → 0 @0.525 → −40 @0.70
  - `left_leg` ROT: mirrored.
  - `right_arm` ROT: (42, 0, 6) @0.00 → (−34, 0, 6) @0.35 → (42, 0, 6) @0.70 —
    **elbows in** (`z` toward the body) is what separates a jog from a walk.
  - `left_arm` ROT: mirrored, z = −6.
  - `torso` ROT: (11, 6, 0) @0.00 → (11, −6, 0) @0.35 → (11, 6, 0) @0.70 —
    pitched forward, and the y-twist is *doubled* from `WALK`.
  - `torso` POS: 0 → (0, −0.5, 0) @0.175 → 0 @0.35 → (0, −0.5, 0) @0.525 → 0.
  - `cloak` ROT: 6 @0.00 → 16 @0.175 → 6 @0.35 → 16 @0.525 → 6 @0.70 — the
    biggest cloak amplitude in the catalogue except panic.
  - `head`: free; **do not** add pitch — a level head is the whole tell.
- **Accent:** t = 0.175 s / 0.525 s footfalls; no custom sound.
- **Carry:** if hurrying while laden, use `WALK_LADEN` instead — a laden hurry
  is a limp, not a jog. This is a deliberate gameplay read: couriers that look
  like they're jogging are *unloaded*.

### 1.4 `RUN_PANIC` — flat-out flight

- **Trigger:** raid alarm, fire, wolf pack, `SettlerPanicGoal`. Civilians only —
  guards get `WALK_HURRIED` even under alarm (they do not panic; that is the
  point of guards).
- **Activity:** `FLEEING`; locomotion layer.
- **Length:** 0.55 s, **looping**.
- **Silhouette:** flailing over-extended arms above shoulder height and a head
  thrown back — the one gait in the mod where the arms go *above* the head, so a
  panicking crowd is legible instantly from the hill.
- **Bones:**
  - `right_leg` ROT: −52 @0.00 → 0 @0.1375 → +52 @0.275 → 0 @0.4125 → −52 @0.55
  - `left_leg` ROT: mirrored.
  - `right_arm` ROT: (−96, 0, −22) @0.00 → (−128, 0, −30) @0.275 →
    (−96, 0, −22) @0.55 — up and thrown outward.
  - `left_arm` ROT: (−128, 0, 30) @0.00 → (−96, 0, 22) @0.275 →
    (−128, 0, 30) @0.55 — **deliberately out of phase with the right arm** so
    the flail looks uncoordinated rather than like a dance.
  - `torso` ROT: (16, 9, 3) @0.00 → (16, −9, −3) @0.275 → (16, 9, 3) @0.55.
  - `torso` POS: 0 → (0, −0.7, 0) @0.1375 → 0 @0.275 → (0, −0.7, 0) @0.4125 → 0.
  - `head` ROT: (−12, 0, 0) constant — chin up, looking anywhere but forward.
    Damp head tracking to 0.4 while fleeing.
  - `cloak` ROT: 10 @0.00 → 22 @0.1375 → 10 @0.275 → 22 @0.4125 → 10 @0.55 —
    maximum drag; the cape is streaming.
  - `root` POS: 0 → (0, 0.4, 0) @0.275 → 0 @0.55 — a slight airborne lift at
    mid-stride. Only clip besides `CELEBRATE` that leaves the ground.
- **Accent:** t = 0.1375 s → `hearthstead:settler_panic` breath yelp on the
  *first* cycle only (ticks 3 of a 11-tick cycle); thereafter throttle to one
  vocal per 2 s so a fleeing crowd is not a wall of noise.
- **Carry:** panicking settlers **drop the load** (items really drop — chest
  truth). No carry variant. The dropped crate on the ground is the storytelling.

### 1.5 `WALK_LIMP` — injured

- **Trigger:** health below 40 %, or the first 200 ticks after being revived
  from downed. Replaces `WALK`.
- **Activity:** locomotion layer; no enum value (health-driven on the client).
- **Length:** 1.40 s, **looping** — asymmetric: the loop is one *pair* of steps,
  a good one and a bad one.
- **Silhouette:** a lopsided hitch — the body drops hard on one side and hangs
  there a beat too long. It is the only gait with an uneven rhythm, so it reads
  as wrong before you can see any detail.
- **Bones:**
  - `right_leg` (the **hurt** leg) ROT: −16 @0.00 → −4 @0.20 → +14 @0.40 →
    +6 @0.70 → −8 @1.05 → −16 @1.40. Short, dragging, never straightens.
  - `left_leg` (the good leg) ROT: +30 @0.00 → 0 @0.35 → −30 @0.70 → 0 @1.05 →
    +30 @1.40. Full stride.
  - `root` POS: (0, −0.5, 0) @0.00 → **(0, −2.2, 0) @0.40** → (0, −0.6, 0) @0.70
    → (0, −0.4, 0) @1.05 → (0, −0.5, 0) @1.40. The deep dip at 0.40 is the
    weight landing on the bad leg — LINEAR into it, CATMULLROM out.
  - `torso` ROT: (8, 0, 7) @0.00 → **(12, 0, 14) @0.40** → (8, 0, 2) @0.70 →
    (8, 0, 7) @1.40 — rolls hard toward the bad side on the dip.
  - `right_arm` ROT: (10, 0, 14) @0.00 → (−4, 0, 18) @0.40 → (10, 0, 14) @1.40 —
    hangs, held slightly out for balance, barely swings.
  - `left_arm` ROT: (−26, 0, −4) @0.00 → (26, 0, −4) @0.70 → (−26, 0, −4) @1.40 —
    the good side swings normally, which sharpens the asymmetry.
  - `head` ROT: (6, 0, 4) @0.00 → (10, 0, 9) @0.40 → (6, 0, 4) @1.40 — winces
    with the dip.
  - `cloak` ROT: (3, 0, 5) @0.00 → (10, 0, 9) @0.40 → (3, 0, 5) @1.40.
- **Accent:** t = 0.40 s → `hearthstead:settler_hm` pitched down 0.8×, played on
  every third cycle (a grunt on every step is comedy, on every third it is pain).
  Tick 8 of a 28-tick cycle.
- **Carry:** injured settlers refuse hauling work; no carry variant.

### 1.6 `CREEP_NIGHT` — moving quietly after dark

- **Trigger:** unarmed settler outdoors between dusk and dawn while the
  settlement is unlit or a raid is telegraphed; also the scout-spotted state.
  Lighting matters in this mod, so this clip is how the player *feels* darkness.
- **Activity:** `TRAVELING` with the night flag; locomotion layer.
- **Length:** 1.60 s, **looping** (slow).
- **Silhouette:** a hunched, narrow figure with both arms held close and a head
  that sweeps side to side — compact where every other gait is wide.
- **Bones:**
  - `root` POS: hold (0, −3, 0) — crouched the whole time.
  - `right_leg` ROT: −20 @0.00 → 0 @0.40 → +20 @0.80 → 0 @1.20 → −20 @1.60,
    plus a constant `z` of −5 (knees turned in, narrow stance).
  - `left_leg` ROT: mirrored, `z` = +5.
  - `torso` ROT: (22, 3, 0) @0.00 → (22, −3, 0) @0.80 → (22, 3, 0) @1.60 —
    deeply hunched, minimal twist.
  - `right_arm` ROT: (−28, 0, 10) @0.00 → (−22, 0, 10) @0.80 →
    (−28, 0, 10) @1.60 — held in front, tiny movement.
  - `left_arm` ROT: mirrored, `z` = −10.
  - `head` ROT: (−8, 24, 0) @0.00 → (−8, 0, 0) @0.40 → (−8, −24, 0) @0.80 →
    (−8, 0, 0) @1.20 → (−8, 24, 0) @1.60 — chin up out of the hunch, scanning.
    This is the same scan rhythm as `GUARD_STANCE` but from a coward's posture.
  - `cloak` ROT: (1, 0, 0) @0.00 → (4, 0, 0) @0.40 → (1, 0, 0) @0.80 →
    (4, 0, 0) @1.20 → (1, 0, 0) @1.60 — almost still.
  - `torso` POS: no dip. Sneaking has no bounce.
- **Accent:** none. Silence is the point; suppress footstep foley volume to
  0.3× while this clip runs.
- **Carry:** none — a settler carrying a crate at night walks `WALK_LADEN` and
  *should* look vulnerable.

### 1.7 `CLIMB_LADDER` — going up

- **Trigger:** any settler on a ladder/vine. Vanilla has no ladder animation for
  mobs at all; this is a free win over TekTopia, which also has none.
- **Activity:** locomotion layer, driven by `onClimbable()` on the client.
- **Length:** 1.00 s, **looping** (one full hand-over-hand cycle = two rungs).
- **Silhouette:** a flattened, vertical figure with alternating high arms and a
  head pressed toward the wall — unmistakable against a ladder even in
  silhouette.
- **Bones:**
  - `right_arm` ROT: (−168, 0, −6) @0.00 → (−168, 0, −6) @0.25 →
    (−96, 0, −10) @0.50 → (−168, 0, −6) @0.75 → (−168, 0, −6) @1.00 — reaches,
    holds, pulls down, reaches. The hold is what sells grip.
  - `left_arm` ROT: (−96, 0, 10) @0.00 → (−168, 0, 6) @0.25 →
    (−168, 0, 6) @0.75 → (−96, 0, 10) @1.00 — offset by half a cycle.
  - `right_leg` ROT: (−58, 0, −6) @0.00 → (−20, 0, −6) @0.50 →
    (−58, 0, −6) @1.00 — high knee, then push down.
  - `left_leg` ROT: (−20, 0, 6) @0.00 → (−58, 0, 6) @0.50 → (−20, 0, 6) @1.00.
  - `torso` ROT: (−8, 4, 0) @0.00 → (−8, −4, 0) @0.50 → (−8, 4, 0) @1.00 —
    slight backward pitch (chest to the ladder) and a small alternating twist.
  - `head` ROT: (−14, 0, 0) constant — looking up the ladder. Damp tracking to
    0.3 while climbing.
  - `cloak` ROT: hold (−6, 0, 0) — hanging straight down behind, against the
    torso's backward pitch.
  - `root` POS: (0, 0, 0) @0.00 → (0, 0.6, 0) @0.25 → (0, 0, 0) @0.50 →
    (0, 0.6, 0) @0.75 → (0, 0, 0) @1.00 — the small ratchet of each pull.
- **Accent:** t = 0.25 s and 0.75 s → `hearthstead:ladder_creak`, ticks 5 and 15
  of a 20-tick cycle, volume 0.4, pitch jittered ±0.15.
- **Carry:** a laden settler on a ladder uses **one-arm climb**: freeze
  `left_arm` at (−78, 0, 14) (crate hugged to the chest) and let only the right
  arm cycle at half rate. Do not author this as a separate clip — it is
  `CLIMB_LADDER` with the left-arm channel suppressed by the carry layer.

---

## 2. Farmer

The existing `FARM` clip is one generic hoe loop covering everything the farmer
does. It is split into four bespoke clips. `FARM` is **renamed** `FARM_TILL`
(see §16.5) and retuned; the other three are new.

The farmer's four tasks read as four different *heights*: tilling is
mid-height and rhythmic, planting is a deep crouch, harvesting is a twist-and-
lift, watering is upright with a tilted vessel. Height is the cheapest
long-range silhouette cue we have and the farmer uses all of it.

### 2.1 `FARM_TILL` — breaking soil with the hoe *(was `FARM`; retune)*

- **Trigger:** `FarmerWorkGoal`, tilling dirt/grass into farmland.
- **Activity:** `WORK_TILL`.
- **Length:** 1.50 s, **looping**.
- **Silhouette:** bent at the waist, the hoe swings in a tight arc that ends
  with a sharp downward jab and a small recoil back up the arms.
- **Bones** *(punch-up pass 2026-08-25 — pushed for 15+ block readability: a
  deeper dip before the strike, a harder jab, legs that brace on contact, and
  a real root jolt instead of a static hold)*:
  - `torso` ROT: (24, −5, 0) @0.00 → (16, −7, 0) @0.40 → **(44, 5, 0) @0.50
    LINEAR** → (36, 3, 0) @0.60 LINEAR → (32, 2, 0) @0.70 LINEAR →
    (18, −3, 0) @0.90 → (14, −7, 0) @1.10 → (24, −5, 0) @1.50. The torso
    *drops into* the strike well before the arm arrives (peaks at 0.50,
    strictly ahead of the 0.60 contact).
  - `right_arm` ROT: (−68, 8, 0) @0.00 → (−84, 10, 0) @0.20 →
    (−108, 13, 0) @0.40 → (−155, 8, −7) @0.50 LINEAR →
    **(−26, −7, 0) @0.60 LINEAR** (contact) → (−25, −7, 0) @0.65 LINEAR →
    (−24, −8, 0) @0.75 LINEAR (a genuine three-tick beat, the hoe biting the
    dirt) → (−52, −13, 0) @0.90 → (−90, −16, 0) @1.10 (a real overshoot past
    the −68 rest) → (−68, 8, 0) @1.50.
  - `left_arm` ROT: mirrors the right at similar amplitude: (−42, −10, 0)
    baseline → (−64, −16, 0) @0.50 → (−16, −6, 0) @0.60 LINEAR (through
    0.75) → (−56, −17, 0) @1.10. It travels *with* the right now, not a
    fraction of it — a hoe is two-handed.
  - `head` ROT: (12, 0, 0) @0.00 → (5, 2, 0) @0.35 (glances up on the
    wind-up) → (22, −3, 0) @0.50 (ducks toward the target) →
    (26, 0, 0) @0.60 LINEAR (watches the strike land) → (16, 0, 0) @0.75 →
    (12, 0, 0) @1.50.
  - `right_leg` / `left_leg` ROT: hold (−8, 0, −3) / (8, 0, 3), braced wider
    to (−14, 0, −6) / (14, 0, 6) at the 0.60 contact and back — the stance
    takes the impact too, not just the arms.
  - `cloak` ROT: (4, 0, 0) @0.00 → (18, 0, 0) @0.35 (thrown back on the
    wind-up) → (10, 0, 0) @0.50 → (−14, 0, 0) @0.65 (whipped through by the
    strike, lagging the torso's own peak) → (10, 0, 0) @0.85 (overshoot) →
    (4, 0, 0) @1.50.
  - `root` POS: (0, −1, 0) @0.00 → (0, −0.6, 0) @0.40 (rises for the wind-up)
    → (0, −0.4, 0) @0.50 LINEAR → **(0, −2.0, 0) @0.60 LINEAR** (the jolt) →
    (0, −1.8, 0) @0.75 LINEAR → (0, −1.3, 0) @0.90 → (0, −1.1, 0) @1.10 →
    (0, −1, 0) @1.50.
- **Accent:** **t = 0.60 s** → `hearthstead:farmer_work` (3 variants exist), at
  tick 12 of a 30-tick cycle. *Contract change:* `FarmerWorkGoal` currently
  plays at `workTicks % 12 == 3`, which does not correspond to any keyframe —
  it must become `workTicks % 30 == 12`.
- **Carry:** hoe in right hand via `translateToHand`.

### 2.2 `FARM_PLANT` — setting seed

- **Trigger:** `FarmerWorkGoal` FIRST planting of a new field only — a
  40-tick cycle (`FIRST_PLANT_DURATION`) under `WORK_PLANT`, press beat at
  tick 14. The REPLANT after a harvest does **not** play this clip any
  more: it runs the owner's broadcast-sowing signature — `WORK_SOW` /
  `SOW_BROADCAST` on a 28-tick cycle (`REPLANT_DURATION`), release beat at
  tick 14 *(trued 2026-08-25; the old "replant step and the spring sowing
  pass" wording predated FarmerWorkGoal's split)*.
- **Activity:** `WORK_PLANT`.
- **Length:** 2.00 s, **looping**.
- **Silhouette:** a deep squat with one hand pressing into the ground and the
  other cupped at the belt — the lowest working pose in the mod, so at range you
  see "someone kneeling in the field".
- **Bones** *(punch-up pass 2026-08-25: a deeper reach, a real dip on the
  press, and secondary motion on head/cloak that used to be dead-static)*:
  - `root` POS: (0, −6, 0) @0.00 → (0, −6.5, 0) @0.55 → **(0, −9, 0) @0.70** →
    (0, −6, 0) @2.00 — the press has real downward weight behind it now, not
    just a held squat.
  - `right_leg` ROT: hold (−62, 0, −8) — folded under.
  - `left_leg` ROT: hold (−34, 0, 10) — knee up, foot planted. Asymmetric legs
    are what makes a squat read as a squat rather than a sit.
  - `torso` ROT: (30, −8, 0) @0.00 → (46, −12, 0) @0.70 → (38, −4, 0) @1.20 →
    (30, −8, 0) @2.00.
  - `right_arm` ROT: (−40, 14, 0) @0.00 → (5, 20, 0) @0.55 →
    **(18, 24, 0) @0.70 LINEAR** → (−18, 17, 0) @1.10 → (−40, 14, 0) @2.00 —
    reaches well past vertical to press the seed in, a genuinely deeper dig
    than before, then withdraws.
  - `left_arm` ROT: hold (−58, −22, 6) ±3° — the seed bag hand, cupped at the
    hip, feeding the right hand. Small inward roll at 1.40 s (grabbing the next
    seed): (−66, −30, 6) @1.40.
  - `head` ROT: (22, 6, 0) @0.00 → (26, 3, 0) @0.55 → (29, 1, 0) @0.70 →
    (24, 5, 0) @1.10 → (22, 6, 0) @2.00 — tracks the press instead of holding
    a fixed stare.
  - `cloak` ROT: (−4, 0, 0) @0.00 → (−10, 0, 0) @0.70 → (2, 0, 0) @1.30 →
    (−4, 0, 0) @2.00 — no longer a static hold; the cape settles and lifts
    with the crouch.
- **Accent:** **t = 0.70 s** → `hearthstead:seed_press` (new: a soft soil pat,
  no metal), tick 14 of a 40-tick cycle.
- **Carry:** seeds are in the left hand and should render as a small item at the
  belt; the placement particle must fire on the accent frame, not on the goal's
  block-set tick, or the two will be visibly out of step.

### 2.3 `FARM_HARVEST` — pulling the crop

- **Trigger:** `FarmerWorkGoal` at a mature crop.
- **Activity:** `WORK_HARVEST`.
- **Length:** 1.80 s, **looping**.
- **Silhouette:** a twist — reach down and out to one side, then rise and swing
  the arm across the body to the shoulder bag. It is the only farm clip with a
  big horizontal component, so it stands out against tilling from any angle.
- **Bones** *(punch-up pass 2026-08-25: the twist and the reach roughly
  doubled — the arm now genuinely crosses in front of the body on the grab
  before swinging all the way to the far hip for the stow)*:
  - `torso` ROT: (28, 16, 0) @0.00 → (38, 22, 0) @0.35 →
    (−1, −32, 0) @0.85 → (6, −19, 0) @1.20 → (28, 16, 0) @1.80. The twist now
    runs from +22° to −32° on `y` — a real 54° turn through the body, not a
    sway.
  - `right_arm` ROT: (−36, 30, 0) @0.00 → (21, 38, 0) @0.35 →
    **(25, 41, 0) @0.45 LINEAR** (the grab, arm now fully forward and open) →
    (−104, −61, 0) @0.85 (swung hard across to the far hip for the stow) →
    (−78, −46, 0) @1.20 → (−36, 30, 0) @1.80.
  - `left_arm` ROT: (−46, −16, 0) @0.00 → (−52, −19, 0) @0.35 →
    (−104, −29, 16) @0.85 → (−97, −26, 16) @1.20 → (−46, −16, 0) @1.80 — the
    left hand comes up to hold the bag open for the drop at 0.85.
  - `head` ROT: (18, 12, 0) @0.00 → (24, 18, 0) @0.35 → (−1, −23, 0) @0.85 →
    (18, 12, 0) @1.80 — follows the crop from ground to bag, further now that
    the bag itself is further away.
  - `right_leg` / `left_leg` ROT: hold (−12, 0, −4) / (6, 0, 4), bracing wider
    to (−18, 0, −7) / (11, 0, 7) at the 0.85 twist and back.
  - `root` POS: (0, −3, 0) @0.00 → (0, −5, 0) @0.35 → (0, 1, 0) @0.85 →
    (0, −3, 0) @1.80 — down for the grab, a genuine rise (not just less-down)
    for the stow.
  - `cloak` ROT: (3, 0, −4) @0.00 → (8, 0, −9) @0.35 → (7, 0, 14) @0.90 →
    (3, 0, −4) @1.80 — swings across with the twist, bigger now that the
    twist itself is bigger.
- **Accent:** two — **t = 0.45 s** (the grab) → `hearthstead:crop_pull` (a dry
  rustle-snap), tick 9; and **t = 0.90 s** (into the bag) →
  `hearthstead:bag_stow`, tick 18. Cycle = 36 ticks.
- **Carry:** harvested item briefly visible in the right hand between 0.45 s
  and 0.90 s, then hidden (it is in the bag). If the render can't do the
  handoff, show nothing — a permanently-held wheat sheaf breaks the read.

### 2.4 `FARM_WATER` — watering from the can

- **Trigger:** dry farmland or the seasonal drought event; also the herb-garden
  chore shared with the healer.
- **Activity:** `WORK_WATER`.
- **Length:** 2.40 s, **looping**.
- **Silhouette:** upright, one arm extended forward and slowly tipping — the
  only field task performed standing tall, so a watering farmer is instantly
  distinguishable from a tilling one at any distance.
- **Bones** *(punch-up pass 2026-08-25: the can tip is now unmistakable at
  range — the `z` roll runs to −85° instead of −34° — and root/cloak/head
  carry a gentle weight shift instead of standing dead still)*:
  - `torso` ROT: (12, −10, 0) @0.00 → (22, −20, 0) @1.00 → (17, −5, 0) @1.80 →
    (12, −10, 0) @2.40 — still mostly still; the stillness is still the
    point, just less rigid.
  - `right_arm` ROT: (−58, −18, 0) @0.00 → **(−18, −28, −65) @0.80** →
    (−8, −28, −85) @1.60 → (−43, −23, −35) @2.10 → (−58, −18, 0) @2.40. The
    `z` roll now runs 0° to −85° — the can visibly upends.
  - `left_arm` ROT: (−22, 10, 8) @0.00 → (−38, 18, 12) @1.20 →
    (−22, 10, 8) @2.40 — hanging, balancing.
  - `head` ROT: (20, −8, 0) @0.00 → (24, −11, 0) @0.80 → (26, −11, 0) @1.60 →
    (20, −8, 0) @2.40 — follows the pour instead of holding a fixed stare.
  - `right_leg` ROT: hold (−6, 0, −3); `left_leg` ROT: hold (4, 0, 3).
  - `cloak` ROT: (2, 0, 3) @0.00 → (−4, 0, 8) @0.80 → (13, 0, 10) @1.60 →
    (2, 0, 3) @2.40.
  - `root` POS: (0, 0, 0) @0.00 → (0, −0.3, 0.2) @0.80 → (0, −0.2, 0.3) @1.60
    → (0, 0, 0) @2.40 — a small weight shift through the pour, not a rigid
    stand.
- **Accent:** **t = 0.80 s** → `hearthstead:water_pour` (a 1.2 s trickle, so it
  runs across the tipped section), tick 16 of a 48-tick cycle. Water particles
  spawn from t = 0.90 s to t = 1.90 s.
- **Carry:** grammar VESSEL. Watering can rendered in the right hand; its `z`
  roll is the animation.

---

## 3. Lumberer

### 3.1 `CHOP` — felling *(REBUILT 2026-08-25: the cascade build)*

- **Trigger:** `LumbererWorkGoal`, chopping a validated tree.
- **Activity:** `WORK_CHOP`.
- **Length:** 1.00 s, **looping**.
- **Silhouette:** a real feller's notch cut at CHEST height. Owner note:
  *"på hogging animasjonen så vil jeg han hogger fra siden som i real
  life"*; owner verdict on the first side-arc retune: *"Øksa til siden ser
  fortsatt jævlig ut"* — that build kept the arm pitched down (axe at thigh
  height, read as a wave), peaked torso and arm on the same tick (one
  synchronized jerk), had zero overshoot, a flat-0 torso roll and statue
  legs. This build is a **cascade**: hips → shoulders → arm, each firing
  2 ticks after the last.
- **Bones (the numbers the owner-critic verifies):**
  - `right_arm` ROT: rest (−55, −25, 8); wind-up accelerates to
    **(−104, −86, 14) @0.35** (axe cocked high across the body — arm pitch
    stays −80..−104 all cycle so the axe rides a chest-high line);
    2-tick gather to (−100, −80, 13) @0.45; **(−92, −58, 8) @0.50 LINEAR**
    (22°/tick) → **(−80, 34, 2) @0.55 LINEAR** — contact, **92°/tick** on
    `y`, ramp 3 → 22 → 92 over the last three intervals; bite hold
    (−79.5, 33, 2) @0.60 / (−78.5, 31.5, 2) @0.70 (LINEAR, ≤2.5° drift);
    release RAMPS out of the bite ((−76, 22, 3) @0.75, ~10°/tick) then
    pulls hard to **(−52, −38, 10) @0.85** — 13° past the −25 rest on `y`,
    ~11% of the 120° strike travel; recoil key @0.90, settle @1.00 with a
    velocity-continuous seam into the next wind-up.
  - `left_arm` ROT: same shape at ~0.75 amplitude (−38/−76/−58 family),
    LINEAR through the same contact and hold ticks.
  - `torso` ROT — **fires first**: coil to (2, 22, −4) @0.35 (`y` extreme
    = contact − 4 ticks), unwind RAMPS in at (4, 18, −2) @0.40 (4°/tick);
    **peak-velocity interval 0.40→0.45 (15°/tick on `y`) leads the arm's
    0.50→0.55 by 2 ticks**; pitch (`x`) peaks at 16 @0.50 — strictly
    before the contact — and the torso arrives (15, −14, 10) @0.55 already
    decelerating (4°/tick). Total `y` swing 36° = 30% of the arm's 120°.
    `z` **rolls −4 → +10** through the strike — the weight visibly
    transfers rear foot → front foot.
  - `right_leg` / `left_leg` ROT: staggered (−14/+12 x); the rear leg
    plants to −11 in the coil then drives to **−19 with a 3° heel pivot
    (`y` 2 → −3) at contact**; the front leg 16 → 7 with its own 3° toe
    pivot — he steps INTO it.
  - `root` POS: (−0.6, +0.35, −0.45) @0.40 (loaded back and UP, §1.12) →
    LINEAR drive to **(+0.7, −0.9, +0.35) @0.55** — 0.9 px drop, 1.3 px of
    lateral travel through the strike.
  - `head` ROT: fixed on the notch; counter-dips 8 → 14 LINEAR at contact.
  - `cloak` ROT: dragged to (−12.5, 0, 6) @0.50 (lagging the torso peak by
    2 ticks), whipped through to (15, 0, −5) @0.70 (3 ticks after the
    torso's arrival), settles through the seam.
- **Accent:** **t = 0.55 s** → `hearthstead:chop`, tick 11 of a 20-tick cycle.
  **This contract is live and correct — locked, do not change it.**
- **Carry:** axe in the right hand.

### 3.2 `LIMB_BRANCHES` — trimming the felled trunk

- **Trigger:** `LumbererWorkGoal` after the trunk is down, before hauling; also
  the sapling-pruning chore.
- **Activity:** `WORK_LIMB`.
- **Length:** 1.30 s, **looping**.
- **Silhouette:** short, fast, sideways axe flicks at knee height with the body
  hunched over — quick and choppy where felling is slow and grand. Two strikes
  per loop, so the rhythm alone distinguishes it at range.
- **Bones** *(punch-up pass 2026-08-25: every amplitude scaled roughly 1.4-1.8×
  from a fixed rest pose per bone, so both chops read at range without
  touching the accent timing)*:
  - `torso` ROT: (26, 10, 0) @0.00 → (46, −4, 0) @0.20 → (40, 0, 0) @0.30 →
    (26, 10, 0) @0.40 → (12, 19, 0) @0.55 → (50, −2, 0) @0.85 →
    (43, 2, 0) @0.95 → (12, 19, 0) @1.15 → (26, 10, 0) @1.30 — hunched,
    now with real twist between the two flicks.
  - `right_arm` ROT: (−74, 24, −10) @0.00 → (−94, 27, −14) @0.15 →
    (−116, 30, −18) @0.20 → **(−38, 4, −2) @0.30 LINEAR** →
    (−82, 27, −13) @0.55 → (−119, 32, −21) @0.80 →
    **(−40, 7, −2) @0.95 LINEAR** → (−88, 27, −11) @1.20 →
    (−74, 24, −10) @1.30. Two complete chops, the second slightly bigger,
    both noticeably wider than before.
  - `left_arm` ROT: same shape, scaled: (−66, −8, 8) baseline →
    (−35, −2, 2) @0.30 → (−74, −11, 9) @1.20.
  - `head` ROT: (24, 10, 0) @0.00 → (31, 5, 0) @0.25 → (22, 12, 0) @0.40 →
    (31, 3, 0) @0.90 → (22, 12, 0) @1.05 → (24, 10, 0) @1.30 — fixed on the
    branch with a small tracking dip at each flick, not a dead hold.
  - `right_leg` / `left_leg` ROT: hold (−16, 0, −6) / (4, 0, 6), bracing to
    (−22, 0, −9) / (10, 0, 9) at each accent.
  - `root` POS: hold (0, −2, 0), with a −1.4 px LINEAR dip at each accent
    (was −0.6).
  - `cloak` ROT: (6, 0, 0) @0.00 → (−10, 0, 0) @0.20 → (19, 0, 0) @0.35 →
    (−10, 0, 0) @0.80 → (19, 0, 0) @1.00 → (6, 0, 0) @1.30.
- **Accent:** **two per loop — t = 0.30 s and t = 0.95 s** →
  `hearthstead:limb_snap` (lighter and higher than `chop`; reuse the chop
  synthesis at 1.4× pitch with a shorter tail), ticks 6 and 19 of a 26-tick
  cycle.
- **Carry:** axe in the right hand, held short (choked up on the haft) — if the
  renderer supports a grip offset, shift the item 2 px toward the wrist here.

### 3.3 `HAUL_LOG` — the log on the shoulder

- **Trigger:** lumberer moving logs from the felling site to the warehouse.
- **Activity:** `HAULING_LOG` (arms layer, over `WALK_LADEN` legs).
- **Length:** 2.40 s, **looping** (a slow strain cycle that deliberately does
  *not* divide evenly into the 1.20 s `WALK_LADEN` loop — the resulting drift
  between gait and strain is what makes long hauls stop looking mechanical).
- **Silhouette:** a horizontal bar across the shoulders with a figure tilted
  under it — the whole silhouette becomes a lopsided T. Reads at 50 blocks.
- **Bones** *(punch-up pass 2026-08-25: the arms stay locked — carry-layer
  clips are capped at 6° of travel per axis and `right_arm`/`left_arm` are
  already at that cap, so the weight now reads through torso, head, cloak,
  root, and this clip's own self-authored legs instead)*:
  - `right_arm` ROT: hold (−142, −6, −20), with the same slow 3° strain
    wobble as before — this channel is already at the carry-layer's 6° cap
    and cannot travel further without breaking the "locked" read.
  - `left_arm` ROT: (−28, 4, −12) @0.00 → (−34, 6, −16) @1.20 →
    (−28, 4, −12) @2.40 — unchanged, also at the 6° cap.
  - `torso` ROT: (9, 0, 8) @0.00 → (14, 0, 16) @1.20 → (9, 0, 8) @2.40 —
    forward and **tilted away from the load side**, the tilt now roughly
    doubled.
  - `head` ROT: (4, −14, 0) @0.00 → (7, −16, 0) @1.20 → (4, −14, 0) @2.40 —
    turned away from the log with a real strain nod through the cycle.
  - `right_leg` / `left_leg` ROT (this clip's own short-stepping legs, A1
    simplification): the short-step swing roughly doubled, from ±10° to
    (−10, 0, −7) → (6, 0, −7) @0.60 → (22, 0, −7) @1.20 → (6, 0, −7) @1.80
    → (−10, 0, −7) @2.40 (mirrored on the left), and the brace `z` widened
    from ±4° to ±7° throughout.
  - `cloak` ROT: (5, 0, −6) @0.00 → (13, 0, −14) @1.20 → (5, 0, −6) @2.40 —
    pushed off to the unloaded side, roughly doubled.
  - `root` POS: (0, −1, 0) @0.00 → (0, −1.6, 0) @1.20 → (0, −1, 0) @2.40 — a
    strain bob instead of a static hold. Legs are self-authored for this A1
    slice; see the class-level comment on why.
- **Accent:** **t = 1.20 s** → `hearthstead:haul_strain` (a low exhale), tick 24
  of a 48-tick cycle, volume 0.35, throttled to at most one per 3 s per settler.
- **Carry:** grammar SHOULDER. The log renders from the `torso` transform at
  `(0, −16, −1)` with a 90° `z` rotation — pinned to the shoulders, **not** to
  `right_arm`, or the strain wobble will make a 3-block log wag.

---

## 4. Guard

Guards are the mod's readability workhorse: during a raid the player must
parse a dozen of them at once. Every guard clip therefore has a distinct
**arm silhouette** — low guard, high guard, shield wall, horn to lips, arm
raised. Never two guard clips with the same arm shape.

### 4.1 `GUARD_STANCE` — at the post *(exists; reworked 2026-08-25 to the Pflug low guard)*

- **Trigger:** guard standing a post, or in combat but out of swing range.
- **Activity:** `PATROLLING` / `COMBAT` while stationary.
- **Length:** 4.00 s, **looping** — the loop IS the breath cycle.
- **Silhouette:** the low guard (Pflug-derived, animation-quality §2.2):
  blade forward-and-down 30–45° below horizontal continuing the forearm
  line, hilt in front of the hip, off-hand across the body, feet staggered,
  weight ~60/40 on the front foot, torso forward and ready. Confident and
  controlled — the owner's direct demand (*"han skal virke selvsikker og
  kontrolert"*): the confident-vs-nervous difference is the CLOCK, not the
  pose — one slow breath, one slow scan, zero fidget.
- **Bones** *(rework 2026-08-25 — replaces the earlier punch-up pass, which
  read as nervous: 15° torso sway, two ±36° head sweeps per 3 s and a ±9°
  arm pump were weight-shifting far too often for a trained soldier)*:
  - **`right_arm` ROT (sword):** (−28, −10, 5) with ≤ 2° breath drift to
    (−30, −11.5, 6.5) @2.80 — x sets the blade angle (30–45° below
    horizontal), y draws the hilt in front of the hip, z tucks the upper
    arm to the ribs. Never flared outward, never hanging straight down.
  - **`left_arm` ROT (off-hand/shield):** across the body at the hip,
    (−20, 14, 4) → (−22, 15.5, 5) @2.80 → back.
  - **`right_leg` / `left_leg` ROT:** staggered stance — left foot leads
    (x −10), sword-side foot back (x +12), toes out (y −6/+5, z ∓3),
    knees soft; ≤ 0.5° of weight micro-shift with the breath. Asymmetric
    magnitudes on purpose (§3 check 11).
  - **`torso` ROT:** +6° forward at rest, +7.5° at the top of the breath;
    sway is y +1.5° → −1.5° across the loop (3° total — §2.2's 1–3° band).
  - **`root` POS breath:** trough @0.60 → (0, +0.35, 0) @2.80 → falling
    through the seam — rise 2.2 s, fall 1.8 s (down faster, §1.9).
  - **`head`:** ONE slow deliberate scan per loop — float near center,
    gather @1.80, out to (0, 17, 0) @2.60 (~1°/tick, y ≤ 20°), hold the
    look to @3.10, controlled return. No metronome sweeps.
  - **`cloak` ROT:** (3, 0, 1.5) trough @0.75 → (5.5, 0, −1.5) @2.95 —
    lagging the torso peak (@2.80) by 3 ticks.
  - **Loop seam:** every channel keys t=0 mid-motion (v0 = trough + Δ/3),
    so value AND velocity are continuous across the wrap (§3 check 6).
- **Accent:** none.
- **Carry:** sword right, shield left.

### 4.2 `GUARD_PATROL` — the walking watch

- **Trigger:** `GuardPatrolGoal` while moving. Layers **over** `WALK`, replacing
  its arm and head channels.
- **Activity:** `PATROLLING` while moving.
- **Length:** 4.00 s, **looping** — deliberately co-prime-ish with the 1.00 s
  `WALK` so the scan never syncs to the footfalls.
- **Silhouette:** a walk in which the arms do *not* swing — one hand rests on
  the sword pommel, the other holds the shield edge — plus a head that turns
  independently of the stride. Instantly separable from a civilian walking.
- **Bones** *(rework 2026-08-25 — same Pflug carry as §4.1; the old carry
  flared the sword arm outward, z −12, which is what read as "holder
  sverdet rart")*:
  - `right_arm` ROT: (−30, −8, 5) with ≤ 1° drift to (−31, −8.6, 5.6)
    @2.80 — blade continuing the forearm line, hilt before the hip, upper
    arm tucked to the ribs. **This overriding of the walk swing is the
    entire read.**
  - `left_arm` ROT: (−20, 15, 6) → (−21, 15.6, 6.6) @2.80 → back — the
    off-hand/shield carried across the body at the hip.
  - `head` ROT: ONE slow deliberate scan per 4 s loop, to the OPPOSITE
    side of §4.1's — float near center, gather @1.80, out to (−1, −18, 0)
    @2.60, hold to @3.10, controlled return. A guard alternating post and
    patrol appears to cover both flanks.
  - `torso`, `cloak`, legs, `torso` POS: **inherited from `WALK`; do not
    author** (this clip stays a 2-bone-class arm+head layer,
    `BONE_COUNT_EXEMPT` in `anim_check.py`).
  - **Loop seam:** every channel keys t=0 mid-motion (velocity-continuous
    wrap, §3 check 6).
- **Accent:** none.
- **Carry:** sword right, shield left.

### 4.3 `MELEE` — the sword stroke *(exists; retune)*

- **Trigger:** `GuardMeleeGoal` / `doHurtTarget`, broadcast as `EV_MELEE`.
- **Activity:** **event** one-shot (expires at 500 ms in `setupAnimationStates`).
- **Length:** 0.50 s, **one-shot**.
- **Silhouette:** a diagonal slash driven from the hips — the shoulder line
  rotates 38° across the swing, so the whole body turns, not just the arm.
- **Bones:** as authored, plus:
  - **Add `right_leg` / `left_leg`:** (−4, 0, −4) → (−22, 0, −6) @0.22 LINEAR →
    (−8, 0, −4) @0.50 for the right (the lunging leg), and
    (6, 0, 4) → (18, 0, 6) @0.22 LINEAR → (8, 0, 4) @0.50 for the left. The
    current clip moves the torso forward on `posVec` but leaves the legs behind,
    which is the one genuinely broken thing in the existing library.
  - **Add `cloak` ROT:** (2, 0, 0) @0.00 → (−10, 0, −8) @0.08 (thrown back on
    the wind-up) → (12, 0, 10) @0.26 (whipped through) → (2, 0, 0) @0.50.
  - **Add `head` ROT:** (0, 14, 0) @0.00 → (4, −12, 0) @0.22 LINEAR →
    (0, 0, 0) @0.50 — the head leads the twist by one frame.
- **Accent:** **t = 0.22 s** (contact) → weapon swish + the damage tick. Play
  `hearthstead:blade_hit` in the same tick the server applies damage — 4 ticks
  after the event broadcast. If damage lands on the event tick instead, delay
  the sound, not the animation.
- **Carry:** sword right, shield left.

### 4.4 `SHIELD_BLOCK` — bracing behind the shield

- **Trigger:** guard in melee with an incoming attack telegraphed, or on
  "hold" from the command wheel; also the reflexive block after taking a hit.
- **Activity:** `COMBAT` sub-state; entered as a looping brace, exited on the
  next swing. Also usable as a 0.30 s **event** impact hit-react.
- **Length:** 1.60 s, **looping** (brace); the impact variant is the first
  0.30 s played as a one-shot.
- **Silhouette:** the body disappears behind a raised forearm — head tucked
  down and turned in, shoulders squared to the threat, back leg braced. The
  only guard pose where the head goes *below* the shoulder line.
- **Bones:**
  - `left_arm` ROT: (−96, 26, 22) @0.00 → (−100, 28, 24) @0.80 →
    (−96, 26, 22) @1.60 — shield high and across, nearly still.
  - `right_arm` ROT: (−48, −22, −16) @0.00 → (−44, −22, −16) @0.80 →
    (−48, −22, −16) @1.60 — sword drawn back behind the shield, ready.
  - `torso` ROT: (14, 22, 0) @0.00 → (16, 24, 0) @0.80 → (14, 22, 0) @1.60 —
    turned so the shield shoulder leads.
  - `head` ROT: hold (16, 18, 0) — tucked behind the rim. Damp head tracking to
    **0.15** during this clip; a guard peeking around his own shield at the
    player ruins it.
  - `right_leg` ROT: hold (18, 0, −8) — back leg braced behind.
  - `left_leg` ROT: hold (−14, 0, 8) — front leg bent under.
  - `root` POS: hold (0, −2, 0) — sunk into the brace.
  - `cloak` ROT: hold (6, 0, −5).
  - **Impact variant (one-shot, 0.30 s):** same pose but with
    `root` POS 0 → (0, −1, 1.6) @0.10 LINEAR → (0, −2, 0) @0.30 — knocked back
    16 mm and recovering, and `left_arm` `x` spiking to −108 @0.10 LINEAR.
- **Accent:** impact variant **t = 0.10 s** → `hearthstead:shield_thud`, played
  on the tick the block is registered (2 ticks after the event).
- **Carry:** shield left, sword right.

### 4.5 `HORN_CALL` — sounding the alarm

- **Trigger:** raid telegraph, `GuardRespondToAlertGoal` first contact, and the
  player's command-wheel horn. This is a **story beat**, not a work loop: it is
  the sound the whole village reacts to.
- **Activity:** **event** one-shot; the guard is frozen in place for the whole
  clip (movement suppressed by the goal).
- **Length:** 2.60 s, **one-shot**.
- **Silhouette:** head thrown back and one arm raised to the mouth with the
  elbow high — a hard 45° diagonal from hip to horn that appears nowhere else
  in the catalogue. The chest visibly expands.
- **Bones:**
  - `right_arm` ROT: (−26, 0, −6) @0.00 → (−118, −34, −30) @0.45 →
    (−124, −36, −32) @0.60 → hold to @2.00 → (−40, −8, −10) @2.35 →
    (−26, 0, −6) @2.60. Raise, plant, hold, lower.
  - `left_arm` ROT: (−34, 18, 6) @0.00 → (−26, 12, 12) @0.45 → hold to @2.00 →
    (−34, 18, 6) @2.60 — the free arm drops a little and opens outward.
  - `head` ROT: 0 @0.00 → (−22, −6, 0) @0.55 → (−26, −6, 0) @1.30 →
    (−18, −4, 0) @2.00 → 0 @2.60 — chin up through the whole blow.
  - `torso` ROT: 0 @0.00 → (−10, −4, 0) @0.55 → (−13, −4, 0) @1.30 →
    (−6, −2, 0) @2.10 → 0 @2.60 — arched back.
  - `torso` SCALE: 1.0 @0.00 → 1.0 @0.45 → **(1.03, 1.025, 1.03) @0.70** →
    (1.005, 1.005, 1.005) @1.90 → 1.0 @2.60 — the breath. This is the only clip
    that uses scale dramatically and it is worth it: the chest filling and
    emptying is what makes the horn look *blown* rather than *held*.
  - `right_leg` / `left_leg`: hold (−6, 0, −5) / (6, 0, 5), planted wide.
  - `root` POS: 0 @0.00 → (0, 0.8, 0) @0.60 → (0, 0.4, 0) @1.90 → 0 @2.60 —
    rises onto the balls of the feet with the effort.
  - `cloak` ROT: (2, 0, 0) @0.00 → (−8, 0, 0) @0.60 → (−6, 0, 0) @1.90 →
    (2, 0, 0) @2.60 — pulled back by the arch.
- **Accent:** **t = 0.60 s** → `hearthstead:war_horn` (the existing `horn_note`
  synthesis in `gen_sounds.py`, ~1.4 s), tick 12 of the one-shot. The note must
  end before t = 2.00 s so the arm lowers into silence. Village-wide: this is
  the sound that starts `RUN_PANIC` for civilians — the panic broadcast should
  be scheduled for tick 14, two ticks after the horn, so the reaction reads as
  *caused*.
- **Carry:** horn in the right hand; hide the sword for the duration.

### 4.6 `RALLY` — calling the line together

- **Trigger:** the command wheel's rally segment, a captain kill, or a guard
  reaching a defended point with allies nearby. The morale beat.
- **Activity:** **event** one-shot.
- **Length:** 1.80 s, **one-shot**.
- **Silhouette:** sword thrust straight up, other arm swept back and open, body
  turned to face the settlement — a vertical exclamation mark. Contrasts with
  `CELEBRATE` (both arms up) by using exactly one.
- **Bones:**
  - `right_arm` ROT: (−26, 0, −6) @0.00 → **(−178, 0, −4) @0.30 LINEAR** →
    (−166, 0, −6) @0.50 → (−174, 0, −5) @0.75 → (−172, 0, −5) @1.30 →
    (−26, 0, −6) @1.80. Snaps up (LINEAR — this is a thrust, not a wave),
    settles with one small bounce, holds, drops.
  - `left_arm` ROT: (−34, 18, 6) @0.00 → (−22, 46, 26) @0.35 →
    (−26, 42, 22) @1.30 → (−34, 18, 6) @1.80 — swept back and open, the
    "come on" gesture.
  - `torso` ROT: 0 @0.00 → (−8, −12, 0) @0.30 → (−6, −10, 0) @1.30 → 0 @1.80.
  - `head` ROT: 0 @0.00 → (−14, −10, 0) @0.30 → (−10, −8, 0) @1.30 → 0 @1.80 —
    looking up at the blade, then out.
  - `right_leg` / `left_leg`: hold (−10, 0, −6) / (10, 0, 6) — wide, planted.
  - `root` POS: 0 @0.00 → (0, −1.2, 0) @0.18 LINEAR (the crouch before the
    thrust) → (0, 0.6, 0) @0.30 LINEAR → 0 @0.60 → 0 @1.80. The pre-crouch is
    two frames long and does more for the punch than anything else in the clip.
  - `cloak` ROT: (2, 0, 0) @0.00 → (14, 0, 6) @0.35 → (5, 0, 2) @0.90 →
    (2, 0, 0) @1.80.
- **Accent:** **t = 0.30 s** → `hearthstead:rally_shout` (a short male/female
  mumble shout, two variants, following the mumble-voice rule: no words), tick 6
  of the one-shot. Nearby guards should start their own `GUARD_STANCE` with a
  reset phase at tick 8 so the line visibly stiffens together.
- **Carry:** sword right; shield stays on the left arm through the sweep.

---

## 5. Courier — the flagship carry set

The owner named this one explicitly: *"an animation where the courier carries
something."* Logistics is the mod's flagship system and the courier is the only
settler the player will watch continuously, crossing the whole village dozens
of times an hour. The carry must be the best-looking thing in the mod.

The courier's job is one continuous sentence — **lift → carry → set down →
sort** — and the four clips are authored as a single sequence with matching
in/out poses so they cut together with no pop. The **handoff pose** is:

```
right_arm (−78,  16, −14)   left_arm (−78, −16, 14)
torso     (−11,  0,   0)    root     (0, −1, 0)
```

Every courier clip starts and ends here except `COURIER_LIFT`, which *arrives*
at it, and `COURIER_SET_DOWN`, which *departs* from it.

### 5.1 `COURIER_LIFT` — picking the crate up

- **Trigger:** courier reaching a source chest with a reserved request. Fires
  once, then the carry loop begins.
- **Activity:** **event** one-shot; `CARRYING` is set on the final tick.
- **Length:** 1.40 s, **one-shot**.
- **Silhouette:** a squat that stands up — the figure compresses to two-thirds
  height, pauses, then rises with the shoulders visibly hauling. The vertical
  travel of the whole body is the read.
- **Bones:**
  - `root` POS: 0 @0.00 → **(0, −7, 0) @0.35** → (0, −7, 0) @0.60 →
    (0, −2, 0) @0.95 → (0, −1, 0) @1.40. Down, *hold* (the grip beat — without
    this pause the lift has no weight), up, settle.
  - `right_leg` ROT: 0 @0.00 → (−54, 0, −10) @0.35 → (−54, 0, −10) @0.60 →
    (−14, 0, −4) @0.95 → (−4, 0, −3) @1.40.
  - `left_leg` ROT: mirrored (`z` positive).
  - `torso` ROT: 0 @0.00 → (34, 0, 0) @0.35 → (32, 0, 0) @0.60 →
    (−16, 0, 0) @1.00 → (−11, 0, 0) @1.40. Folds forward over the crate, then
    **overshoots past vertical to −16°** before settling at −11° — that
    overshoot is the counterweight snapping in, and it is the frame that sells
    the crate as heavy.
  - `right_arm` ROT: (−6, 4, −2) @0.00 → (−34, 20, −18) @0.35 →
    **(−40, 22, −20) @0.60 LINEAR** (the grip) → (−84, 18, −16) @1.00 →
    (−78, 16, −14) @1.40.
  - `left_arm` ROT: mirrored (`y`, `z` negated).
  - `head` ROT: 0 @0.00 → (28, 0, 0) @0.35 → (24, 0, 0) @0.60 →
    (−4, 0, 0) @1.00 → 0 @1.40 — looks at the crate, then up and away.
  - `cloak` ROT: 0 @0.00 → (−9, 0, 0) @0.35 → (6, 0, 0) @1.00 → (3, 0, 0) @1.40.
- **Accent:** **t = 0.60 s** → `hearthstead:crate_grip` (a wooden scrape, dry,
  0.25 s), tick 12; and **t = 0.95 s** → `hearthstead:haul_strain` exhale,
  tick 19. Two accents, 7 ticks apart — grip then effort.
- **Carry:** the crate model becomes visible at t = 0.60 s, parented to `torso`
  at `(0, −6, −5)`. It must appear *on the grip frame*, not at clip start.

### 5.2 `COURIER_CARRY` — the crate walk *(the flagship clip)*

- **Trigger:** courier in transit with goods. Arms layer over `WALK_LADEN`.
- **Activity:** `CARRYING`.
- **Length:** 2.00 s, **looping** (against `WALK_LADEN`'s 1.20 s, so the strain
  cycle and the stride drift apart and back over 6 seconds — the haul never
  looks looped).
- **Silhouette:** a boxy figure leaning back with both forearms locked out
  front at chest height and no arm swing whatsoever, taking short steps. From
  30 blocks you see a moving rectangle carried by a wedge — nothing else in the
  village looks like it. *This is the clip the mod is judged on.*
- **Bones:**
  - `right_arm` ROT: (−78, 16, −14) @0.00 → (−76, 16, −15) @0.70 →
    (−79, 17, −14) @1.40 → (−78, 16, −14) @2.00. **Total travel: 3°.** The arms
    are a clamp. Resist every instinct to animate them.
  - `left_arm` ROT: mirrored, same 3° budget.
  - `torso` ROT: (−11, 2, 0) @0.00 → (−13, 0, 1.5) @0.50 → (−11, −2, 0) @1.00 →
    (−13, 0, −1.5) @1.50 → (−11, 2, 0) @2.00 — the lean deepens twice per loop
    as the load settles, with a tiny `z` roll. **This is where the life is.**
  - `torso` SCALE: 1.0 @0.00 → (1.012, 1.008, 1.012) @0.55 → 1.0 @1.10 →
    (1.010, 1.006, 1.010) @1.65 → 1.0 @2.00 — two working breaths per loop.
  - `head` ROT: (5, 0, 0) @0.00 → (7, 8, 0) @0.60 → (4, 0, 0) @1.10 →
    (7, −6, 0) @1.60 → (5, 0, 0) @2.00 — the courier is looking around the load
    to see where he is going. Head tracking layers over this at full strength;
    it is the courier's only expressive channel and it should stay lively.
  - `cloak` ROT: hold (7, 0, 0) ±1 — pinned between the back and the load.
  - `root` POS: hold (0, −1, 0) — carried by the layer, reasserted here so the
    clip is correct if played standing still (a courier waiting at a chest).
  - Legs and the walk bob: **inherited from `WALK_LADEN`; do not author.**
- **Accent:** **t = 1.40 s** → `hearthstead:crate_creak` (soft wood stress,
  volume 0.3), tick 28 of a 40-tick cycle, throttled to one per 4 s.
- **Carry:** grammar CRATE. Crate parented to `torso` at `(0, −6, −5)`, **not**
  to an arm. Crate contents drive the model variant (grain sack, ore crate,
  bread tray) — three meshes, one animation.
- **Standing-still variant:** when a carrying courier is stopped (queued at a
  chest, waiting for a door), play this clip with `WALK_LADEN` suppressed and
  add a weight-shift: `root` POS (0, −1, 0) → (0, −1.4, 0) @1.00 →
  (0, −1, 0) @2.00 with `torso` `z` swinging ±3°. A courier standing perfectly
  still while holding a crate is the single most robotic thing this mod could
  ship, so this variant is **required**, not optional.

### 5.3 `COURIER_SET_DOWN` — putting the crate down

- **Trigger:** courier arriving at the destination chest. Fires once before
  sorting.
- **Activity:** **event** one-shot; `CARRYING` clears on the final tick.
- **Length:** 1.20 s, **one-shot**.
- **Silhouette:** the reverse of the lift, but *faster and looser* — the body
  drops, the crate lands, the shoulders come up and roll back in relief.
- **Bones:**
  - `root` POS: (0, −1, 0) @0.00 → (0, −6.5, 0) @0.45 → (0, −6.5, 0) @0.60 →
    (0, −0.5, 0) @0.95 → 0 @1.20.
  - `torso` ROT: (−11, 0, 0) @0.00 → (30, 0, 0) @0.45 → (28, 0, 0) @0.60 →
    (−4, 0, 0) @0.95 → 0 @1.20.
  - `right_arm` ROT: (−78, 16, −14) @0.00 → (−36, 22, −20) @0.45 →
    **(−30, 22, −20) @0.60 LINEAR** (release) → (−12, 8, −6) @0.95 →
    (−4, 2, −2) @1.20.
  - `left_arm` ROT: mirrored.
  - `right_leg` / `left_leg`: mirror of `COURIER_LIFT`, but reaching only −48°
    (the set-down squat is shallower than the lift squat — you drop the last
    inch).
  - `head` ROT: 0 @0.00 → (26, 0, 0) @0.45 → (10, 0, 0) @0.95 → 0 @1.20.
  - `cloak` ROT: (7, 0, 0) @0.00 → (−8, 0, 0) @0.45 → (5, 0, 0) @0.95 →
    0 @1.20 — released, it swings forward and settles.
  - **Relief beat:** `torso` ROT gets a final flourish — (0, 0, 0) @1.20 is
    reached via (−5, 0, 0) @1.05, a small backward stretch. Two frames of
    "that's better".
- **Accent:** **t = 0.60 s** → `hearthstead:crate_down` (a solid wooden knock
  with a short room tail), tick 12. Dust particle at the same tick.
- **Carry:** crate visibility ends at t = 0.60 s; the world-placed crate or the
  chest insert happens on the same tick. If the item transaction is server-side
  at a different tick, **move the transaction**, not the accent.

### 5.4 `COURIER_SORT` — filing goods into the chest

- **Trigger:** courier at the destination chest, transferring stacks. One loop
  per stack moved, so the length of the animation naturally equals the size of
  the delivery — a big delivery visibly takes longer.
- **Activity:** `SORTING`.
- **Length:** 1.60 s, **looping**.
- **Silhouette:** a steady two-beat reach — down to the crate at the feet, up
  and forward into the chest — with the head bobbing between the two. The
  ping-pong of the arm between two heights is the read.
- **Bones:**
  - `right_arm` ROT: (−22, 12, −6) @0.00 → **(−8, 16, −10) @0.25 LINEAR**
    (grab from the crate) → (−52, 10, −6) @0.55 →
    **(−86, 4, −4) @0.80 LINEAR** (place in the chest) → (−64, 8, −5) @1.10 →
    (−22, 12, −6) @1.60.
  - `left_arm` ROT: (−58, −14, 8) @0.00 → (−66, −18, 10) @0.40 →
    (−62, −16, 9) @1.00 → (−58, −14, 8) @1.60 — holding the chest lid open,
    almost static. **One busy arm and one holding arm** is what makes sorting
    look like sorting rather than waving.
  - `torso` ROT: (18, 8, 0) @0.00 → (24, 12, 0) @0.25 → (6, −4, 0) @0.80 →
    (12, 2, 0) @1.10 → (18, 8, 0) @1.60.
  - `head` ROT: (22, 10, 0) @0.00 → (26, 12, 0) @0.25 → (2, −6, 0) @0.80 →
    (22, 10, 0) @1.60 — looks at what he picks up, then at where it goes. The
    eyeline moving *with* the item is the difference between a worker and a
    puppet.
  - `right_leg` ROT: hold (−8, 0, −4); `left_leg` ROT: hold (6, 0, 4).
  - `root` POS: (0, −2, 0) @0.00 → (0, −3.5, 0) @0.25 → (0, −0.5, 0) @0.80 →
    (0, −2, 0) @1.60 — bobs down for the grab, up for the place.
  - `cloak` ROT: (5, 0, 2) @0.00 → (9, 0, 3) @0.30 → (2, 0, −1) @0.85 →
    (5, 0, 2) @1.60.
- **Accent:** **two — t = 0.25 s** → `hearthstead:item_pickup` (tick 5) and
  **t = 0.80 s** → `hearthstead:chest_stow` (tick 16), of a 32-tick cycle. The
  actual `ItemStack` move must happen on tick 16 so the Tingboka index, the
  chest contents and the sound all change on the same frame. Chest lid opens on
  the goal's `start()` and closes on `stop()`, not per loop.
- **Carry:** a single item renders in the right hand between t = 0.25 s and
  t = 0.80 s. Because the mod is item-accurate, **render the actual stack being
  moved** — watching a courier physically carry your bread across the room, one
  stack at a time, is the flagship system made visible.

---

## 6. Innkeeper

### 6.1 `INN_POUR` — filling a tankard from the barrel

- **Trigger:** innkeeper at the tavern barrel with a pending order.
- **Activity:** `POURING`.
- **Length:** 2.80 s, **looping**.
- **Silhouette:** a still figure with one arm out and slightly down, holding a
  pose for nearly three seconds — the *stillness* is the read. Everyone else in
  the village is moving; the innkeeper waiting on a pour is a fixed point.
- **Bones:**
  - `right_arm` ROT: (−54, −20, 6) @0.00 → (−58, −22, 4) @0.35 →
    (−58, −22, 4) @2.10 → (−48, −16, 8) @2.50 → (−54, −20, 6) @2.80 — reaches,
    **holds for 1.75 s**, lifts the full tankard away.
  - `left_arm` ROT: (−32, 24, −10) @0.00 → (−40, 30, −16) @0.30 →
    (−40, 30, −16) @2.10 → (−32, 24, −10) @2.80 — on the tap handle,
    with a 4° pull at 0.30 s (opening it) and a 4° push at 2.10 s (closing it).
  - `torso` ROT: (10, −14, 0) @0.00 → (12, −16, 0) @1.20 → (10, −14, 0) @2.80 —
    almost nothing.
  - `head` ROT: hold (18, −12, 0) — watching the level rise. At 2.20 s add a
    single upward flick to (2, −12, 0) and back: the moment he judges it full.
  - `torso` SCALE: 1.0 → (1.01, 1.014, 1.01) @1.40 → 1.0 @2.80 — one calm
    breath, which is all that keeps the long hold alive.
  - `right_leg` / `left_leg`: hold (−4, 0, −3) / (4, 0, 3).
  - `cloak` ROT: (2, 0, −3) @0.00 → (3, 0, −4) @1.40 → (2, 0, −3) @2.80.
- **Accent:** **t = 0.35 s** → `hearthstead:tap_open` (tick 7) and a looped
  `hearthstead:pour_stream` from t = 0.40 s to t = 2.10 s (ticks 8–42), fading
  in pitch upward across its run as the vessel fills — the classic filling-
  vessel cue, and cheap to synthesize as a filtered noise band whose centre
  frequency rises. Cycle = 56 ticks.
- **Carry:** grammar VESSEL, tankard in the right hand.

### 6.2 `INN_SERVE` — delivering the drink to a table

- **Trigger:** innkeeper reaching a seated settler with an order.
- **Activity:** `SERVING` (one-shot at the table; the walk there is
  `WALK_HURRIED` with the vessel arm locked).
- **Length:** 1.60 s, **one-shot**.
- **Silhouette:** an extended arm placing something on a low surface, followed
  by a small bow of the head — the little courtesy is what makes the tavern
  feel like a tavern.
- **Bones:**
  - `right_arm` ROT: (−72, −10, 4) @0.00 → (−88, −22, 2) @0.35 →
    **(−62, −28, 0) @0.60 LINEAR** (the set-down) → (−40, −18, 4) @0.95 →
    (−24, −6, 6) @1.60.
  - `left_arm` ROT: (−28, 12, −6) @0.00 → (−36, 20, −10) @0.60 →
    (−30, 14, −7) @1.10 → (−24, 8, −5) @1.60 — comes across the body, the
    other-hand-hovering habit of every server ever.
  - `torso` ROT: (8, −16, 0) @0.00 → (20, −20, 0) @0.60 → (10, −8, 0) @1.00 →
    (4, 0, 0) @1.60 — leans in to place, straightens to leave.
  - `head` ROT: (12, −14, 0) @0.00 → (24, −16, 0) @0.60 →
    **(16, 0, 0) @1.05** (the nod, toward the guest) → (6, 0, 0) @1.35 →
    (2, 0, 0) @1.60.
  - `right_leg` ROT: (−8, 0, −4) @0.00 → (−16, 0, −5) @0.60 → (−4, 0, −3) @1.60;
    `left_leg` mirrored — a half-step in and back.
  - `root` POS: 0 @0.00 → (0, −2.5, 0) @0.60 → (0, −0.5, 0) @1.10 → 0 @1.60.
  - `cloak` ROT: (2, 0, 0) @0.00 → (10, 0, −4) @0.60 → (4, 0, 0) @1.60.
- **Accent:** **t = 0.60 s** → `hearthstead:tankard_set` (a dull wooden clunk
  with a faint liquid slosh), tick 12. **t = 1.05 s** → optional
  `hearthstead:settler_hm` at 0.4 volume with the nod — the mumble-voice rule
  allows it because it is a direct interaction.
- **Carry:** tankard in the right hand until t = 0.60 s, then hidden and the
  world item appears on the table.

### 6.3 `INN_GREET` — welcoming a traveler

- **Trigger:** a traveler entity arriving at the tavern (`TravelerJoinGoal`);
  also the recruiting handshake at the start of the hire flow.
- **Activity:** **event** one-shot.
- **Length:** 2.20 s, **one-shot**.
- **Silhouette:** both arms opening wide and low, palms out, with a slight
  forward bow — the widest, most open shape in the catalogue. It is the
  opposite of `COWER` and that contrast is intentional.
- **Bones:**
  - `right_arm` ROT: (−6, 0, −4) @0.00 → (−52, −38, −34) @0.50 →
    (−48, −36, −32) @1.30 → (−20, −12, −12) @1.85 → (−6, 0, −4) @2.20.
  - `left_arm` ROT: mirrored.
  - `torso` ROT: 0 @0.00 → (14, 0, 0) @0.55 (the bow) → (4, 0, 0) @1.00 →
    (6, 0, 0) @1.50 → 0 @2.20.
  - `head` ROT: 0 @0.00 → (20, 0, 0) @0.55 → (−6, 0, 0) @1.00 →
    (−4, 6, 0) @1.40 → (−4, −6, 0) @1.75 → 0 @2.20 — bow, then look up at the
    guest, then a small friendly waggle.
  - `right_leg` / `left_leg`: hold (−5, 0, −4) / (5, 0, 4), plus a 1 px
    `root` rock: 0 → (0, −1, 0) @0.55 → 0 @1.00 → 0 @2.20.
  - `cloak` ROT: 0 @0.00 → (9, 0, 0) @0.55 → (2, 0, 0) @1.20 → 0 @2.20.
- **Accent:** **t = 0.55 s** → `hearthstead:greet_mumble` (a warm two-syllable
  mumble, 3 variants, subtitled — direct talk, so subtitles are allowed per the
  design), tick 11. The traveler should answer with its own mumble at tick 26,
  creating a beat of conversation the player can hear across the square.
- **Carry:** none. Hands empty is part of the welcome.

---

## 7. Cook

### 7.1 `COOK_CHOP_VEG` — knife work on the board

- **Trigger:** cook at a kitchen work surface preparing a meal.
- **Activity:** `COOKING`.
- **Length:** 1.20 s, **looping** — with **four** knife taps inside it.
- **Silhouette:** a fast, small, high-frequency chatter of one forearm over a
  low surface while the body stays still — the highest-frequency motion in the
  whole catalogue, and therefore unmistakable at range even though the
  amplitude is tiny.
- **Bones:**
  - `right_arm` ROT: baseline (−64, −16, −8), tapping to (−48, −16, −8) with
    **LINEAR** keys at t = 0.15, 0.45, 0.75, 1.05 and CATMULLROM returns to
    baseline at 0.30, 0.60, 0.90, 1.20. Four down-taps, 0.30 s apart.
  - `left_arm` ROT: (−70, 18, 10) @0.00 → (−70, 20, 10) @0.60 →
    (−70, 18, 10) @1.20 — the guiding hand, creeping backwards along the
    vegetable. Barely moves, must never move *with* the knife.
  - `torso` ROT: hold (20, −6, 0) with a ±1° `y` breath over the full loop.
  - `head` ROT: hold (26, −4, 0) — locked on the board. Damp head tracking to
    0.3; a cook watching the player instead of the knife is alarming.
  - `right_leg` / `left_leg`: hold (−4, 0, −3) / (4, 0, 3).
  - `root` POS: hold (0, −1, 0), with a −0.3 px LINEAR blip on each tap.
  - `cloak` ROT: hold (3, 0, 0) ±0.5.
- **Accent:** **four per loop — t = 0.15, 0.45, 0.75, 1.05** →
  `hearthstead:knife_tap` (a short, bright wooden tick; 3 pitch variants chosen
  round-robin so four taps never sound identical), ticks 3, 9, 15, 21 of a
  24-tick cycle.
- **Carry:** knife in the right hand.

### 7.2 `COOK_STIR` — the pot over the fire

- **Trigger:** cook at the cauldron; also the healer brewing a remedy.
- **Activity:** `STIRRING`.
- **Length:** 2.40 s, **looping** — one full stir revolution per loop.
- **Silhouette:** a slow circular sweep of one arm at chest height with the
  shoulder rolling around with it — a rotation, where every other work clip is
  an oscillation. The circle reads even as a moving dot at distance.
- **Bones:**
  - `right_arm` ROT: the circle, four quadrant keys —
    (−82, 22, 6) @0.00 → (−70, 4, 18) @0.60 → (−58, −20, 6) @1.20 →
    (−70, 2, −8) @1.80 → (−82, 22, 6) @2.40. Note `x` peaks 90° out of phase
    with `y`; that phase offset *is* the circle. All CATMULLROM, no LINEAR —
    stirring has no impact.
  - `left_arm` ROT: (−48, 34, 14) @0.00 → (−52, 36, 16) @1.20 →
    (−48, 34, 14) @2.40 — braced on the pot rim, steadying it.
  - `torso` ROT: (14, 6, 0) @0.00 → (16, 0, 0) @0.60 → (14, −6, 0) @1.20 →
    (16, 0, 0) @1.80 → (14, 6, 0) @2.40 — the shoulder line rotating with the
    stir. **Without this the arm looks detached.**
  - `head` ROT: hold (24, 2, 0), with a small lean-away at 1.20 s to
    (20, 2, 0) — a puff of steam in the face.
  - `right_leg` / `left_leg`: hold (−5, 0, −3) / (5, 0, 3).
  - `root` POS: (0, −1, 0) @0.00 → (0, −1.6, 0) @1.20 → (0, −1, 0) @2.40 — a
    slow weight shift across the stir.
  - `cloak` ROT: (3, 0, 2) @0.00 → (4, 0, −2) @1.20 → (3, 0, 2) @2.40.
- **Accent:** **t = 1.20 s** → `hearthstead:pot_stir` (a thick wet swirl, 0.8 s,
  volume 0.45), tick 24 of a 48-tick cycle. A separate ambient
  `hearthstead:pot_bubble` loop belongs to the cauldron block, not the settler.
- **Carry:** ladle in the right hand.

### 7.3 `COOK_SERVE_MEAL` — plating and handing over

- **Trigger:** cook delivering a finished meal to the dining hall table or to a
  courier for distribution.
- **Activity:** `SERVING`.
- **Length:** 1.80 s, **one-shot**.
- **Silhouette:** both hands carrying a wide flat tray at waist height, then
  lowering it with a straight back — a horizontal plane of arms, distinct from
  the innkeeper's single-arm serve and from the courier's chest-height crate.
- **Bones:**
  - `right_arm` ROT: (−68, 10, −18) @0.00 → (−72, 8, −20) @0.45 →
    **(−52, 4, −14) @0.85 LINEAR** (tray down) → (−34, 6, −10) @1.30 →
    (−16, 4, −6) @1.80.
  - `left_arm` ROT: mirrored — **both arms move identically**; a tray needs
    two level hands, and the symmetry is the silhouette.
  - `torso` ROT: (−4, 0, 0) @0.00 → (12, 0, 0) @0.85 → (2, 0, 0) @1.30 →
    0 @1.80 — leans from a slight backward counterweight into the placement.
  - `head` ROT: (6, 0, 0) @0.00 → (22, 0, 0) @0.85 → (4, 6, 0) @1.35 →
    0 @1.80 — down at the table, then up at whoever is eating.
  - `right_leg` / `left_leg`: hold (−6, 0, −4) / (6, 0, 4); `root` POS
    (0, −1, 0) → (0, −3, 0) @0.85 → 0 @1.80.
  - `cloak` ROT: (5, 0, 0) @0.00 → (10, 0, 0) @0.85 → 0 @1.80.
- **Accent:** **t = 0.85 s** → `hearthstead:plate_set` (ceramic-on-wood, brighter
  than `tankard_set`), tick 17. Eating settlers at that table should begin
  `EAT_AT_TABLE` within 10 ticks so the causality is visible.
- **Carry:** tray parented to `torso` at `(0, −4, −6)` — a flat wide mesh, both
  hands at its corners.

---

## 8. Miner

### 8.1 `MINE_PICK` — swinging the pick *(retuned 2026-08-25 to match the
    actual 0.95 s implementation — the previous entry described a 1.20 s
    clip that was never what shipped)*

- **Trigger:** miner at an ore face in the mineshaft.
- **Activity:** `MINING`.
- **Length:** 0.95 s, **looping**.
- **Silhouette:** an overhead-to-forward diagonal drive with the whole body
  behind it and a distinct **rebound** — the pick bounces off the rock and the
  shoulders recoil. Distinguished from `CHOP` by being aimed *forward* into a
  wall rather than *sideways* into a trunk, and by the recoil, which `CHOP`
  lacks.
- **Bones:**
  - `right_arm` ROT: (−38, −12, −6) @0.00 → (−73, −11, −7) @0.15 →
    (−161, −8, −9) @0.30 → (−156, −8, −9) @0.40 LINEAR →
    **(−51, −13, −5) @0.45 LINEAR** (impact) → (−48, −13, −5) @0.65 LINEAR
    (a two-tick hold, the pick point lodged in stone) →
    (−13, −12, −6) @0.75 (the rebound — the arm comes *back up*) →
    (−46, −12, −6) @0.85 → (−38, −12, −6) @0.95.
  - `left_arm` ROT: same shape, mirrored: (−46, 16, 6) baseline →
    (−149, 11, 9) @0.30 → (−56, 17, 5) @0.45 → (−26, 16, 6) @0.80.
  - `torso` ROT: (14, 3, 0) @0.00 → (−12, 14, 0) @0.20 → (−21, 17, 0) @0.30 →
    (40, −10, 0) @0.40 LINEAR → (35, −8, 0) @0.60 LINEAR → (1, 9, 0) @0.75 →
    (14, 3, 0) @0.95.
  - `head` ROT: (6, 0, 0) @0.00 → (−14, 0, 0) @0.40 → (22, 0, 0) @0.60
    LINEAR → (6, 0, 0) @0.95 — and a **flinch away** at 0.70 s to
    (15, −9, 0): chips fly.
  - `right_leg` / `left_leg` ROT: hold (−13, 0, −5) / (10, 0, 5), bracing to
    (−24, 0, −8) / (16, 0, 8) at 0.45 s — a deep braced stance, wider than
    the lumberer's.
  - `root` POS: 0 @0.00 → (0, 0.7, 0) @0.30 → **(0, −1.3, 0.4) @0.45
    LINEAR** → (0, −1.2, 0.4) @0.60 LINEAR → 0 @0.95.
  - `cloak` ROT: (4, 0, 0) @0.00 → (−30, 0, 0) @0.30 → (24, 0, 0) @0.50 →
    (8, 0, 0) @0.70 → (4, 0, 0) @0.95.
- **Accent:** roughly **t = 0.45 s** → `hearthstead:pick_strike` (metal on
  stone, with a short cave tail — the reverb is what makes the mine feel
  underground). Spark/dust particles on the same tick. The exact tick-modulo
  contract lives in the miner's AI goal, outside this file's ownership.
- **Carry:** pick in the right hand.

### 8.2 `MINE_HAUL_ORE` — the ore sack

- **Trigger:** miner returning from the face to the mine-entrance drop chest.
  Arms layer over `WALK_LADEN`.
- **Activity:** `CARRYING` with the sack variant.
- **Length:** 2.60 s, **looping**.
- **Silhouette:** a lumpy mass riding one shoulder with the head pushed forward
  and down under it — similar family to `HAUL_LOG` but **compact and round**
  where the log is long and straight. At range: log = a bar, sack = a bulge.
- **Bones:**
  - `right_arm` ROT: hold (−128, −14, −26) with a 4° strain wobble peaking at
    1.30 s — hand gripping the sack's neck behind the shoulder.
  - `left_arm` ROT: (−20, 6, −14) @0.00 → (−28, 10, −18) @1.30 →
    (−20, 6, −14) @2.60 — swings across the front.
  - `torso` ROT: (13, −4, 10) @0.00 → (16, −4, 13) @1.30 → (13, −4, 10) @2.60 —
    more forward pitch than `HAUL_LOG` (a sack sits lower and drags you down).
  - `head` ROT: hold (16, −10, 0) — pushed down and forward by the load. This
    is the tell: the log-hauler's head is *up and turned*, the sack-hauler's is
    *down and forward*.
  - `cloak` ROT: hold (9, 0, −7).
  - `root` POS: hold (0, −1.5, 0) — heavier than a crate.
- **Accent:** **t = 1.30 s** → `hearthstead:sack_shift` (a granular rustle),
  tick 26 of a 52-tick cycle, volume 0.3.
- **Carry:** grammar SHOULDER; sack parented to `torso` at `(−4, −14, 3)`.

---

## 9. Smith

### 9.1 `SMITH_HAMMER` — at the anvil

- **Trigger:** smith working an ingot; equipment upgrades and gate
  reinforcement both use it.
- **Activity:** `SMITHING`.
- **Length:** 1.00 s, **looping** — **two strikes**, a heavy and a light, the
  classic blacksmith rhythm (*BANG-tap*). The uneven pair is the most
  recognisable audio-visual signature available and no other clip uses it.
- **Silhouette:** a compact figure hunched over a waist-high point with one arm
  ratcheting up and down in a tight arc and the other locked holding tongs —
  small, tight, and violently rhythmic.
- **Bones:**
  - `right_arm` ROT: (−96, −8, −10) @0.00 → (−162, −6, −14) @0.20 →
    **(−152, −6, −14) @0.25 LINEAR** →
    **(−78, −10, −8) @0.35 LINEAR** (heavy strike) → (−104, −8, −10) @0.50 →
    (−128, −6, −12) @0.60 → **(−86, −10, −8) @0.70 LINEAR** (light tap) →
    (−96, −8, −10) @1.00. Big arc then small arc.
  - `left_arm` ROT: hold (−88, 26, 16) ± 2° — the tongs hand. **Absolutely
    locked**; every frame it moves, the hot iron looks like it is wobbling.
  - `torso` ROT: (22, 6, 0) @0.00 → (14, 4, 0) @0.20 → (28, 8, 0) @0.35 LINEAR →
    (20, 6, 0) @0.55 → (26, 7, 0) @0.70 LINEAR → (22, 6, 0) @1.00.
  - `head` ROT: hold (28, 4, 0), flinching to (32, 4, 0) on each strike. Damp
    head tracking to 0.25 — the smith is looking at the metal.
  - `right_leg` / `left_leg`: hold (−6, 0, −5) / (6, 0, 5).
  - `root` POS: 0 @0.00 → (0, 0.4, 0) @0.20 → (0, −0.9, 0) @0.35 LINEAR →
    0 @0.50 → (0, −0.4, 0) @0.70 LINEAR → 0 @1.00.
  - `cloak` ROT: (4, 0, 0) @0.00 → (−4, 0, 0) @0.20 → (8, 0, 0) @0.40 →
    (6, 0, 0) @0.75 → (4, 0, 0) @1.00.
- **Accent:** **two — t = 0.35 s** → `hearthstead:anvil_heavy` (tick 7) and
  **t = 0.70 s** → `hearthstead:anvil_light` (tick 14, the same synthesis one
  octave up at 0.6 volume), of a 20-tick cycle. Sparks on tick 7 only.
- **Carry:** hammer right, tongs + glowing ingot left.

### 9.2 `SMITH_BELLOWS` — feeding the forge

- **Trigger:** smith between heats; also a settler assisting at the forge.
- **Activity:** `BELLOWS`.
- **Length:** 2.00 s, **looping**.
- **Silhouette:** a long, slow, full-body push-and-release on a diagonal — the
  whole settler leans into the down-stroke and rocks back on the up. Big and
  slow where hammering is small and fast, so the smithy visibly alternates
  between two rhythms.
- **Bones:**
  - `right_arm` ROT: (−58, −12, −10) @0.00 → (−40, −10, −8) @0.55 →
    **(−18, −8, −6) @0.80 LINEAR** (bottom of the push) → (−44, −10, −8) @1.40
    → (−58, −12, −10) @2.00.
  - `left_arm` ROT: mirrored, at 0.9× (one hand leads on the handle).
  - `torso` ROT: (16, 0, 0) @0.00 → (26, 0, 0) @0.55 → (32, 0, 0) @0.80 →
    (18, 0, 0) @1.40 → (16, 0, 0) @2.00 — the lean is most of the work.
  - `head` ROT: (20, 8, 0) @0.00 → (28, 6, 0) @0.80 → (16, 10, 0) @1.50 →
    (20, 8, 0) @2.00 — glancing into the coals as they brighten.
  - `right_leg` ROT: (−12, 0, −5) @0.00 → (−20, 0, −6) @0.80 →
    (−12, 0, −5) @2.00; `left_leg` mirrored — the legs push too.
  - `root` POS: (0, −1, 0) @0.00 → (0, −3.5, 0) @0.80 → (0, −1, 0) @2.00.
  - `cloak` ROT: (4, 0, 0) @0.00 → (12, 0, 0) @0.85 → (4, 0, 0) @2.00 — biggest
    slow cloak swing in the working set.
- **Accent:** **t = 0.80 s** → `hearthstead:bellows_puff` (a broadband whoosh,
  0.9 s, reusing `fire_whoosh` from `gen_sounds.py` filtered darker), tick 16 of
  a 40-tick cycle. The forge block should brighten its light emission for 12
  ticks starting at tick 16 — a synced light pulse per puff.
- **Carry:** none; hands on the bellows handle.

### 9.3 `SMITH_QUENCH` — plunging the blade

- **Trigger:** completion of any smithing job — the punctuation of the smithy's
  work cycle, and the moment the player learns a piece of equipment is done.
- **Activity:** **event** one-shot.
- **Length:** 2.00 s, **one-shot**.
- **Silhouette:** a fast downward plunge of both arms followed by a **complete
  freeze** — 1.2 seconds of total stillness while the steam rises. The freeze
  is the animation; nothing else in the mod stops dead like this.
- **Bones:**
  - `right_arm` ROT: (−94, −6, −10) @0.00 → (−108, −6, −12) @0.20 →
    **(−36, −10, −6) @0.40 LINEAR** (the plunge) → (−34, −10, −6) @1.55 →
    (−72, −8, −8) @1.85 → (−94, −6, −10) @2.00. Note the 1.15 s hold.
  - `left_arm` ROT: mirrored — both hands on the tongs for the plunge.
  - `torso` ROT: (18, 4, 0) @0.00 → (10, 4, 0) @0.20 → (30, 2, 0) @0.40 LINEAR
    → (28, 2, 0) @1.55 → (18, 4, 0) @2.00.
  - `head` ROT: (24, 2, 0) @0.00 → (30, 2, 0) @0.40 →
    **(14, −4, 0) @0.70** (leans back from the steam) → (18, −2, 0) @1.55 →
    (24, 2, 0) @2.00. The steam-flinch at 0.70 s is the one movement inside the
    freeze, and it is what keeps the hold from looking like a hitch.
  - `right_leg` / `left_leg`: hold (−7, 0, −5) / (7, 0, 5).
  - `root` POS: 0 @0.00 → (0, −2.6, 0) @0.40 LINEAR → (0, −2.4, 0) @1.55 →
    0 @2.00.
  - `cloak` ROT: (4, 0, 0) @0.00 → (−6, 0, 0) @0.25 → (10, 0, 0) @0.45 →
    (5, 0, 0) @0.90 → (4, 0, 0) @2.00 — the only thing still moving during the
    hold, settling for half a second after the body has stopped.
- **Accent:** **t = 0.40 s** → `hearthstead:quench_hiss` (a 1.3 s steam hiss,
  sharp attack, long decay), tick 8. Steam particles from tick 8 to tick 34.
  The item-complete toast fires at tick 8, not at goal end.
- **Carry:** tongs + blade both hands; the blade's glow texture must fade to
  cold over ticks 8–30, matched to the hiss decay.

---

## 10. Healer

### 10.1 `HEAL_BANDAGE` — dressing a wound

- **Trigger:** healer tending an injured (not downed) settler at the infirmary
  or in the field.
- **Activity:** `HEALING`.
- **Length:** 2.20 s, **looping** — one wrap per loop, so a bad wound visibly
  takes several.
- **Silhouette:** two hands working close together in a small circle in front of
  the chest, with the head bowed right over them — the most *inward*,
  concentrated pose in the mod. From range: a person folded around their own
  hands.
- **Bones:**
  - `right_arm` ROT: (−78, 22, −14) @0.00 → (−72, 6, −20) @0.55 →
    (−80, −10, −14) @1.10 → (−86, 6, −8) @1.65 → (−78, 22, −14) @2.20 — a small
    circle (the wrapping motion), 90° phase offset between `y` and `z` as in
    `COOK_STIR` but a third of the radius.
  - `left_arm` ROT: (−82, −18, 12) @0.00 → (−84, −20, 12) @1.10 →
    (−82, −18, 12) @2.20 — holding the limb steady, near-static.
  - `torso` ROT: hold (24, 4, 0) with ±1.5° `y` breath.
  - `head` ROT: hold (32, 2, 0) — the deepest head bow in the catalogue. Damp
    head tracking to **0.1**; a healer must never look away from the wound.
  - `right_leg` ROT: hold (−56, 0, −10); `left_leg` ROT: hold (−30, 0, 12) —
    kneeling, same asymmetric fold as `FARM_PLANT`.
  - `root` POS: hold (0, −6, 0).
  - `cloak` ROT: hold (−3, 0, 0) — pooled.
- **Accent:** **t = 1.10 s** → `hearthstead:cloth_wrap` (a soft fabric pull),
  tick 22 of a 44-tick cycle, volume 0.35. On the **final** loop only, add
  `hearthstead:heal_chime` at the same tick — a single soft bell (reuse
  `bell_tone`) so the player hears the moment the healing lands.
- **Carry:** grammar VESSEL — a bandage roll in the left hand; herb basket set
  on the ground beside the kneeling healer as a world prop.

### 10.2 `HEAL_TEND_HERBS` — the physic garden

- **Trigger:** healer at the herb garden between patients; the mod's calmest
  clip and a deliberate visual rest for the eye.
- **Activity:** `TENDING`.
- **Length:** 3.20 s, **looping**.
- **Silhouette:** a slow, upright, almost ceremonial reach-and-inspect —
  the hand comes up to eye level holding something small and turns it. The
  raised, turning hand at head height is unique.
- **Bones:**
  - `right_arm` ROT: (−34, 18, 0) @0.00 → (−16, 24, 4) @0.60 →
    (−12, 26, 6) @0.85 (the pick) → (−118, 8, −18) @1.60 →
    (−122, −4, −14) @2.10 (turning it in the light) → (−114, 12, −20) @2.55 →
    (−34, 18, 0) @3.20.
  - `left_arm` ROT: (−52, −24, 10) @0.00 → (−58, −28, 12) @1.60 →
    (−52, −24, 10) @3.20 — the basket arm, VESSEL grammar.
  - `torso` ROT: (26, 10, 0) @0.00 → (30, 12, 0) @0.85 → (4, 4, 0) @1.90 →
    (10, 8, 0) @2.60 → (26, 10, 0) @3.20 — folds down to the bed, rises to
    inspect, folds back.
  - `head` ROT: (28, 8, 0) @0.00 → (32, 10, 0) @0.85 → (−6, 2, 0) @1.90 →
    (−4, −6, 0) @2.30 → (28, 8, 0) @3.20 — follows the sprig up and studies it.
  - `right_leg` ROT: (−22, 0, −6) @0.00 → (−30, 0, −7) @0.85 →
    (−8, 0, −4) @1.90 → (−22, 0, −6) @3.20; `left_leg` mirrored.
  - `root` POS: (0, −3, 0) @0.00 → (0, −5, 0) @0.85 → (0, −0.5, 0) @1.90 →
    (0, −3, 0) @3.20.
  - `cloak` ROT: (5, 0, 2) @0.00 → (11, 0, 3) @0.90 → (1, 0, −1) @1.95 →
    (5, 0, 2) @3.20.
- **Accent:** **t = 0.85 s** → `hearthstead:herb_snip` (a tiny green snap),
  tick 17 of a 64-tick cycle, volume 0.3.
- **Carry:** basket in the left hand; a sprig item appears in the right hand
  from t = 0.85 s to t = 3.00 s.

### 10.3 `HEAL_REVIVE` — bringing back a downed settler

- **Trigger:** healer (or the player-assisted flow) reaching a downed settler
  before the bleed-out timer expires. **The most emotionally loaded clip in the
  mod** — this is the moment a life is saved or lost, and it must be worth
  watching from across the battlefield.
- **Activity:** `REVIVING`; a long **one-shot** (the goal blocks movement).
- **Length:** 4.00 s, **one-shot**.
- **Silhouette:** three distinct beats visible in profile — kneel, two hard
  downward chest compressions with locked straight arms, then a rock back onto
  the heels with the head thrown up. The straight-armed press is a shape that
  appears nowhere else.
- **Bones:**
  - **Beat 1, drop to knees (0.00–0.70 s):**
    `root` POS 0 → (0, −7.5, 0) @0.70 LINEAR (he *drops*, he does not lower);
    `right_leg` 0 → (−64, 0, −10) @0.70; `left_leg` 0 → (−58, 0, 12) @0.70;
    `torso` 0 → (30, 6, 0) @0.70; `head` 0 → (34, 6, 0) @0.70.
  - **Beat 2, compressions (0.70–2.40 s):**
    `right_arm` (−52, 12, −6) @0.70 → (−98, 8, −4) @0.95 →
    **(−58, 8, −4) @1.15 LINEAR** (press 1) → (−96, 8, −4) @1.45 →
    **(−56, 8, −4) @1.65 LINEAR** (press 2) → (−88, 10, −5) @1.95 →
    (−80, 10, −5) @2.40. `left_arm` identical, mirrored on `y`/`z` — **both
    arms locked together and straight**, that is the pose.
    `torso` (30, 6, 0) @0.70 → (22, 4, 0) @0.95 → (38, 4, 0) @1.15 LINEAR →
    (24, 4, 0) @1.45 → (38, 4, 0) @1.65 LINEAR → (30, 5, 0) @2.40.
    `root` POS adds a −1.2 px LINEAR dip on each press.
  - **Beat 3, the outcome (2.40–4.00 s):**
    `torso` (30, 5, 0) @2.40 → (−14, 0, 0) @2.95 → (−6, 0, 0) @3.40 →
    (4, 0, 0) @4.00 — rocks back on the heels.
    `head` (34, 4, 0) @2.40 → (−24, 0, 0) @2.95 → (−16, 0, 0) @3.50 →
    (0, 0, 0) @4.00 — thrown up. Whether the eyeline goes to the sky
    (success) or down to the body (failure) is handled by the **two exit
    variants** below.
    `right_arm` / `left_arm` (−80, ±10, ∓5) @2.40 → (−30, ±20, ∓14) @2.95 →
    (−10, ±8, ∓4) @4.00 — fall open at the sides.
    `root` POS (0, −7.5, 0) held to 3.40, then → (0, −6.5, 0) @4.00 (starting
    to rise; the standing-up itself is a transition to `IDLE`, not part of the
    clip).
    `cloak` ROT (−2, 0, 0) @2.40 → (10, 0, 0) @2.95 → (2, 0, 0) @4.00.
- **Exit variants:** author **one** clip and branch the last 0.6 s with a
  second short one-shot — `REVIVE_SUCCESS` (head up, `torso` `x` → −6°,
  `CELEBRATE` may follow) or `REVIVE_FAIL` (head down, `torso` `x` → +20°,
  transitions into `MOURN`). Do not duplicate the whole 4 s clip.
- **Accent:** **t = 1.15 s and t = 1.65 s** → `hearthstead:revive_press` (a
  muffled thud, no music), ticks 23 and 33. **t = 2.95 s** → the outcome
  sound: `hearthstead:revive_gasp` (success) or `hearthstead:revive_fail` (a
  single low bell tone, held) at tick 59 of the 80-tick one-shot. The gasp
  should come from the *revived* settler, not the healer — two entities, one
  timeline. This is the single most important sync in the mod.
- **Carry:** none. Empty hands.

---

## 11. Scribe

### 11.1 `SCRIBE_WRITE` — the ledger and the saga chronicle

- **Trigger:** scribe at a desk doing passive research or writing the saga
  chronicle; the visual proof that research is ticking.
- **Activity:** `WRITING`.
- **Length:** 3.00 s, **looping**.
- **Silhouette:** seated, hunched, with one hand making tiny fast horizontal
  scratches and a periodic long **dip to the inkwell and back** — the dip is
  the beat that carries at distance; the scratching is texture up close.
- **Bones:**
  - `root` POS: hold (0, −7, 0) — seated (shares the seated base with `REST`;
    see §16).
  - `right_leg` ROT: hold (−84, 0, −5); `left_leg` ROT: hold (−84, 0, 5) —
    seated legs forward, same as `REST`.
  - `right_arm` ROT: baseline (−72, −14, −6) with fast small `y` scratches:
    −14 → −8 @0.25 → −16 @0.50 → −9 @0.75 → −15 @1.00 → −8 @1.25 → −14 @1.50;
    then **the inkwell dip**: (−54, −26, −12) @1.85 → (−50, −28, −12) @2.05 →
    (−72, −14, −6) @2.40; then two more scratches to @3.00.
  - `left_arm` ROT: hold (−66, 22, 10) — pinning the page. Add one small
    page-slide at 2.70 s: (−62, 26, 10) and back by 3.00 s.
  - `torso` ROT: hold (26, −6, 0), rising to (20, −8, 0) at 1.90 s for the
    inkwell reach.
  - `head` ROT: hold (30, −4, 0), lifting to (18, −6, 0) at 1.95 s (looking at
    the nib) and to (6, 0, 0) at 2.85 s (a moment of thought). Damp tracking
    to 0.35.
  - `cloak` ROT: hold (−4, 0, 0).
- **Accent:** **t = 1.90 s** → `hearthstead:quill_dip` (a wet tick), tick 38 of
  a 60-tick cycle. A quiet `hearthstead:quill_scratch` loop runs 0.00–1.50 s
  and 2.40–3.00 s at volume 0.2.
- **Carry:** quill in the right hand; the book is a world prop on the desk.

### 11.2 `SCRIBE_TEACH` — the lesson at the school

- **Trigger:** scribe with children present (school system, B2). Standing, not
  seated — the deliberate contrast with `SCRIBE_WRITE`.
- **Activity:** `TEACHING`.
- **Length:** 4.60 s, **looping** — long, so the gestures do not feel like a
  tic.
- **Silhouette:** standing tall with one arm making broad, unhurried
  presentational sweeps at shoulder height while the other holds a book open at
  the chest. The wide sweep against a static book-arm is the read.
- **Bones:**
  - `left_arm` ROT: hold (−76, −20, 16) ± 2° — the open book, VESSEL grammar.
  - `right_arm` ROT: (−36, 10, −8) @0.00 → (−92, 34, −22) @0.90 →
    (−86, 8, −18) @1.60 → (−40, 12, −8) @2.30 → (−98, −18, −26) @3.20 →
    (−90, 4, −20) @3.90 → (−36, 10, −8) @4.60 — two sweeps, one to each side,
    the second wider. Both CATMULLROM; teaching has no impacts.
  - `torso` ROT: (2, 8, 0) @0.00 → (4, 16, 0) @0.90 → (2, 0, 0) @2.30 →
    (4, −14, 0) @3.20 → (2, 8, 0) @4.60 — turns to address both sides of the
    room.
  - `head` ROT: (0, 10, 0) @0.00 → (−4, 20, 0) @0.90 → (2, 0, 0) @2.30 →
    (−4, −18, 0) @3.20 → (0, 10, 0) @4.60 — leads the torso by 0.15 s.
  - `right_leg` / `left_leg`: hold (−4, 0, −4) / (4, 0, 4), plus a `root` weight
    shift: 0 → (0, −0.8, 0) @1.15 → 0 @2.30 → (0, −0.8, 0) @3.45 → 0 @4.60.
  - `cloak` ROT: (3, 0, −3) @0.00 → (6, 0, −6) @1.00 → (3, 0, 3) @3.30 →
    (3, 0, −3) @4.60.
- **Accent:** **t = 0.90 s and t = 3.20 s** → `hearthstead:teach_mumble`
  (2 variants, a rising then a falling cadence so it reads as explanation),
  ticks 18 and 64 of a 92-tick cycle. Child settlers nearby should trigger a
  small `head` nod at ticks 24 and 70.
- **Carry:** book in the left hand.

---

## 12. Social and life

These clips do no work. They exist because the interview asked for settlers
who "live visibly complex lives" — the player must be able to stand in the
square at dusk and read relationships, moods and losses without opening a UI.

### 12.1 `SLEEP_IN_BED` — the night

- **Trigger:** settler in a claimed bed. Runs all night; it is on screen more
  total minutes than any other clip in the mod, so it must not tick.
- **Activity:** `SLEEPING`.
- **Length:** 8.00 s, **looping** — the longest clip in the catalogue. Long
  enough that a player watching a bunkhouse of six settlers never sees them
  breathe in unison (add a per-entity phase offset of `entityId % 160` ticks).
- **Silhouette:** a horizontal body with a slow, deep rise and fall at the
  chest. From the doorway you see a room breathing.
- **Bones:**
  - `root` POS: hold (0, −20, −2) — laid out on the mattress. **This is the
    largest offset in the catalogue and it exceeds the 12 px `anim_check`
    ceiling**, so `SLEEP_IN_BED` must be exempted by name in the validator
    (§17), or the lie-down must instead be done by the renderer's bed pose with
    the clip supplying only the breathing. **Preferred: renderer handles the
    lie-down rotation; this clip only supplies breath and stir.**
  - `torso` ROT: (2, 0, 0) @0.00 → (3, 0, 0) @4.00 → (2, 0, 0) @8.00.
  - `torso` SCALE: 1.0 @0.00 → (1.02, 1.03, 1.02) @1.80 → 1.0 @3.60 →
    (1.018, 1.026, 1.018) @5.60 → 1.0 @8.00 — **two breaths per loop of
    slightly different depth.** Uneven breathing is the entire craft of this
    clip.
  - `head` ROT: (0, 8, 0) @0.00 → (0, 8, 0) @3.00 → (2, −6, 0) @3.60 →
    (2, −6, 0) @6.40 → (0, 8, 0) @8.00 — one slow roll of the head, mid-loop.
    **Head tracking must be fully suppressed (damp 0.0) while sleeping.**
  - `right_arm` ROT: hold (−4, 0, 12); `left_arm` ROT: hold (−4, 0, −12) —
    arms in at the sides, slightly splayed.
  - `right_leg` / `left_leg`: hold (0, 0, −3) / (0, 0, 3).
  - `cloak` ROT: hold (−2, 0, 0) — it is a blanket now, it does not move.
- **Accent:** optional `hearthstead:sleep_breath` at t = 1.80 s, volume 0.15,
  audible only within 4 blocks, and **only for one settler per room** (pick the
  lowest entity id) so a bunkhouse is not a chorus.
- **Carry:** none. Hide all held items and the hood.

### 12.2 `WAKE_STRETCH` — getting up

- **Trigger:** morning, on leaving the bed. Every settler in the village plays
  it within a 60-tick window at dawn — a village-wide beat that tells the
  player the day has started.
- **Activity:** **event** one-shot.
- **Length:** 2.60 s, **one-shot**.
- **Silhouette:** arms up and out in a wide Y with the back arched and the head
  tipped back, held for a full second, then a slump. The held Y at the top is
  the pose; it is `CELEBRATE`'s shape performed at a quarter of the speed,
  which is exactly why it reads as tiredness rather than joy.
- **Bones:**
  - `right_arm` ROT: (−8, 0, −4) @0.00 → (−96, 0, −26) @0.60 →
    (−152, 0, −34) @1.10 → (−148, 0, −33) @1.80 → (−52, 0, −14) @2.25 →
    (−8, 0, −4) @2.60. **Slow up, hold, drop fast.** The asymmetric timing is
    the tiredness.
  - `left_arm` ROT: mirrored, arriving 0.10 s later (−152 at 1.20 s) — nobody
    stretches perfectly symmetrically.
  - `torso` ROT: 0 @0.00 → (−8, 0, 0) @0.70 → (−16, 0, 0) @1.20 →
    (−15, 0, 0) @1.80 → (6, 0, 0) @2.30 → 0 @2.60 — arch, then a small slump
    past neutral.
  - `torso` SCALE: 1.0 @0.00 → (1.025, 1.03, 1.025) @1.20 → 1.0 @2.10 →
    1.0 @2.60 — the big inhale of the stretch.
  - `head` ROT: 0 @0.00 → (−20, 0, 0) @1.20 → (−18, 0, 0) @1.80 →
    (10, 0, 0) @2.30 → 0 @2.60.
  - `right_leg` ROT: (0, 0, −3) @0.00 → (−6, 0, −4) @1.20 → (0, 0, −3) @2.60;
    `left_leg` mirrored — rising onto the toes.
  - `root` POS: 0 @0.00 → (0, 1.2, 0) @1.20 → (0, 1.1, 0) @1.80 →
    (0, −0.6, 0) @2.35 → 0 @2.60 — up on the toes, then a small sag.
  - `cloak` ROT: 0 @0.00 → (−10, 0, 0) @1.20 → (4, 0, 0) @2.35 → 0 @2.60.
- **Accent:** **t = 1.20 s** → `hearthstead:yawn` (a mumble-voice yawn, 3
  variants, ~0.9 s), tick 24 of the 52-tick one-shot. Stagger village-wide
  wake events by at least 8 ticks per settler so the yawns overlap into a
  morning murmur rather than a unison groan.
- **Carry:** none.

### 12.3 `EAT` — eating on the move *(exists; keep)*

- **Trigger:** `EatFromHearthGoal` — eating standing at the hearth or in the
  field. Kept as-is; it is the "grabbed a bite" version.
- **Activity:** `EATING` while not seated.
- **Length:** 1.20 s, **looping**. Values as authored. Add only a `cloak` hold
  at (2, 0, 0) and a `root` POS hold at (0, −0.5, 0).
- **Accent:** **t = 0.25 s and t = 0.70 s** (the two bites) →
  `hearthstead:settler_eat`, ticks 5 and 14 of a 24-tick cycle.

### 12.4 `EAT_AT_TABLE` — the proper meal

- **Trigger:** settler seated in the dining hall with a served meal. Meals are
  a real system (cook → dining hall → morale), and this is where the player
  sees the payoff.
- **Activity:** `EATING` while seated.
- **Length:** 3.60 s, **looping**.
- **Silhouette:** seated, elbows on the table, a slow lift-to-mouth-and-back
  with a **pause between bites where the head turns to a neighbour**. The
  social glance between bites is the whole point: dinner is a scene, not a
  refuelling.
- **Bones:**
  - `root` POS: hold (0, −7, 0) — seated (shared seated base, §16).
  - `right_leg` / `left_leg`: hold (−84, 0, −5) / (−84, 0, 5).
  - `right_arm` ROT: (−58, −16, −4) @0.00 → (−34, −20, −6) @0.35 (down to the
    bowl) → (−112, −26, −2) @0.80 → **(−118, −28, −2) @0.95** (the bite) →
    (−106, −24, −3) @1.20 → (−56, −16, −4) @1.70 → hold to @2.60 →
    (−114, −28, −2) @3.05 (second bite) → (−58, −16, −4) @3.60.
  - `left_arm` ROT: hold (−72, 20, 8) — forearm on the table, steadying the
    bowl. Static except a 3° shift at 2.20 s.
  - `torso` ROT: (16, −6, 0) @0.00 → (12, −6, 0) @0.95 → (14, 10, 0) @2.10 →
    (12, 12, 0) @2.45 → (16, −6, 0) @3.60 — **turns to the neighbour between
    bites** (the `y` swing from −6° to +12°).
  - `head` ROT: (18, −6, 0) @0.00 → (24, −8, 0) @0.95 → (20, −8, 0) @1.30 →
    (2, 22, 0) @2.15 → (0, 24, 0) @2.50 → (18, −6, 0) @3.60 — the glance is
    bigger and earlier than the torso's, as heads always lead.
  - `cloak` ROT: hold (−3, 0, 0), with a ±3° `z` at 2.30 s from the turn.
- **Accent:** **t = 0.95 s and t = 3.05 s** → `hearthstead:settler_eat`,
  ticks 19 and 61 of a 72-tick cycle. Optionally at **t = 2.50 s** →
  `hearthstead:settler_hm` at 0.3 volume: table talk.
- **Carry:** spoon/bread in the right hand; bowl a world prop on the table.

### 12.5 `SOCIAL_TALK` and `SOCIAL_LISTEN` — the conversation pair

- **Trigger:** two settlers meeting a social need at a social anchor (tavern,
  dining hall, plaza). The pair goal picks one speaker and one listener and
  **swaps the roles every 4–6 seconds**; both clips are the same length so the
  swap is clean.
- **Activity:** `SOCIALIZING` (a phase byte selects talk vs listen).
- **Length:** 3.00 s, **looping**, both clips.
- **Silhouette (talk):** upright with one hand making small conversational
  gestures at chest height and the head bobbing on the stresses.
  **(listen):** weight on one hip, arms folded or hanging, head tilted and
  nodding occasionally. From 30 blocks the pair reads as *one is animated, one
  is still* — which is exactly how a conversation looks in real life, and is
  the thing TekTopia's identical mirrored villagers never achieved.
- **`SOCIAL_TALK` bones:**
  - `right_arm` ROT: (−48, 16, −10) @0.00 → (−62, 26, −16) @0.45 →
    (−44, 12, −8) @0.85 → (−58, 22, −14) @1.35 → (−42, 10, −8) @1.75 →
    (−66, 30, −18) @2.30 → (−48, 16, −10) @3.00 — four gestures of unequal
    size, at unequal intervals. **Regular spacing kills this clip.**
  - `left_arm` ROT: (−26, −10, 6) @0.00 → (−32, −14, 8) @1.35 →
    (−24, −8, 6) @2.30 → (−26, −10, 6) @3.00 — mostly hanging; it joins in once.
  - `torso` ROT: (2, 6, 0) @0.00 → (4, 10, 0) @0.85 → (2, 2, 0) @1.75 →
    (4, 8, 0) @2.30 → (2, 6, 0) @3.00.
  - `head` ROT: (0, 8, 0) @0.00 → (−4, 10, 0) @0.45 → (4, 6, 0) @0.85 →
    (−3, 9, 0) @1.35 → (5, 7, 0) @1.75 → (−5, 11, 0) @2.30 → (0, 8, 0) @3.00 —
    small nods on the stresses, synced to the gesture peaks.
  - `right_leg` / `left_leg`: hold (−3, 0, −4) / (3, 0, 4); `root` POS
    0 → (0, −0.6, 0) @1.50 → 0 @3.00.
  - `cloak` ROT: (2, 0, 1) @0.00 → (4, 0, 2) @0.90 → (2, 0, −1) @2.35 →
    (2, 0, 1) @3.00.
- **`SOCIAL_LISTEN` bones:**
  - `right_arm` ROT: hold (−14, 0, 8); `left_arm` ROT: hold (−16, 0, −10) —
    hanging, arms slightly in.
  - `torso` ROT: hold (0, −4, 4) — **weight on one hip**, the `z` roll. This
    single value does most of the work.
  - `head` ROT: (2, −6, 5) @0.00 → (2, −6, 5) @1.10 → (10, −6, 5) @1.30 →
    (2, −6, 5) @1.50 → (2, −6, 5) @2.40 → (8, −5, 5) @2.60 → (2, −6, 5) @3.00
    — **two nods, and long stillness between them.** A listener who nods
    continuously looks demented.
  - `right_leg` ROT: hold (0, 0, −6); `left_leg` ROT: hold (0, 0, 2) — uneven,
    the hip-shot stance.
  - `root` POS: hold (0, −0.5, 0), with a weight shift to (0, −1.2, 0) at
    2.00 s and back by 3.00 s — one restless shift per loop.
  - `cloak` ROT: hold (1, 0, 3).
- **Accent:** `SOCIAL_TALK` — `hearthstead:settler_hm` variants at
  **t = 0.45 s and t = 2.30 s** (ticks 9 and 46 of 60), volume 0.4, throttled
  village-wide so no more than 3 mumbles per second are audible.
  `SOCIAL_LISTEN` — a short affirmative mumble at **t = 1.30 s** (tick 26), 40 %
  chance per loop.
- **Carry:** none.

### 12.6 `MOURN` — at the grave

- **Trigger:** a settler visiting the memorial stone of someone they had a
  Saga relationship with; also the whole village, once, on the morning after a
  death. Deaths are permanent in this mod and this clip is what makes the
  player feel that.
- **Activity:** `MOURNING`.
- **Length:** 6.00 s, **looping** — very slow. It should feel like it is barely
  moving.
- **Silhouette:** head bowed almost to the chest, hands clasped low in front,
  shoulders rounded and **completely still** for seconds at a time. The
  stillness among a moving village is the read; a mourner is a hole in the
  motion.
- **Bones:**
  - `head` ROT: hold (38, 0, 0) — the deepest bow in the catalogue, deeper even
    than the healer's. **Head tracking damped to 0.0.** A mourner does not look
    at the player.
  - `torso` ROT: (16, 0, 0) @0.00 → (17, 0, 0) @3.00 → (16, 0, 0) @6.00.
  - `torso` SCALE: 1.0 @0.00 → (1.008, 1.014, 1.008) @2.20 → 1.0 @4.40 →
    1.0 @6.00 — one shallow, uneven breath, then nothing. Held breath.
  - `right_arm` ROT: hold (−34, 22, 16); `left_arm` ROT: hold (−34, −22, −16) —
    clasped in front, low.
  - `right_leg` / `left_leg`: hold (0, 0, −2) / (0, 0, 2) — feet together,
    which no other standing clip uses. Narrow stance = grief.
  - `root` POS: hold (0, −1.5, 0), with a single slow settle to (0, −2, 0) at
    4.00 s and back by 6.00 s.
  - `cloak` ROT: hold (−1, 0, 0).
  - **Optional grief beat (30 % chance per loop, as a 1.0 s additive one-shot
    `MOURN_SOB`):** `torso` `x` +5° and `torso` SCALE spike over 0.25 s, then
    release — a single shudder. Never scripted, always chance-based.
- **Accent:** none by default. On the `MOURN_SOB` beat, `hearthstead:sob` at
  volume 0.25, at most once per settler per 30 s. **No music.** The bard's
  lament belongs to the funeral event, not to this loop.
- **Carry:** none. If the settler is leaving an offering, that is
  `GIFT_ACCEPT` played in reverse — do not build it here.

### 12.7 `CELEBRATE` — the festival *(exists; retune)*

- **Trigger:** festival, raid survived, blessing revealed, building completed.
- **Activity:** `CELEBRATING`; **event** one-shot (2100 ms expiry already set).
- **Length:** 2.00 s, **one-shot**. Values as authored, plus:
  - **Add `right_leg` / `left_leg`:** they currently do nothing while the
    `root` hops, which reads as a puppet on a string. Give them a tuck —
    (0, 0, −3) → (−26, 0, −6) @0.45 → (0, 0, −3) @0.60 → (−26, 0, −6) @1.10 →
    (0, 0, −3) @1.25 → (0, 0, −3) @2.00, mirrored on the left.
  - **Add `cloak` ROT:** 0 → (−14, 0, 0) @0.45 → (8, 0, 0) @0.62 →
    (−12, 0, 0) @1.10 → (6, 0, 0) @1.27 → 0 @2.00 — the cape flies on each hop.
  - **Add `torso` ROT:** 0 → (−8, 6, 0) @0.65 → (−8, −6, 0) @1.35 → 0 @2.00 — a
    little twist so two celebrating settlers side by side are not identical.
  - **Per-entity variation:** offset the whole clip's playback phase by
    `entityId % 7` ticks and scale arm amplitude by 0.9–1.1 from a per-entity
    seed. A crowd celebrating in perfect unison is uncanny; this is cheap and
    fixes it.
- **Accent:** **t = 0.45 s and t = 1.10 s** (the two hops land) →
  `hearthstead:cheer` mumble variants, ticks 9 and 22. Village-wide festival
  celebration must stagger start ticks across settlers by up to 20 ticks.

### 12.8 `GIFT_ACCEPT` — receiving something from the player

- **Trigger:** the player gives a settler a gift (the only direct-relationship
  lever in the design — gifts feed Saga loyalty). It must feel worth doing.
- **Activity:** **event** one-shot.
- **Length:** 2.40 s, **one-shot**.
- **Silhouette:** both hands come up and **cup together** at chest height, then
  the whole body dips in a small grateful bow with the gift held to the chest.
  The cupped-hands-to-chest shape is unique.
- **Bones:**
  - `right_arm` ROT: (−8, 0, −4) @0.00 → (−72, −18, −22) @0.40 →
    **(−76, −20, −24) @0.55 LINEAR** (the take) → (−84, −14, −18) @1.00 →
    (−88, −10, −14) @1.50 (hugged to the chest) → (−30, −4, −8) @2.10 →
    (−8, 0, −4) @2.40.
  - `left_arm` ROT: mirrored — **both hands, cupped**. One-handed acceptance
    reads as indifference.
  - `torso` ROT: 0 @0.00 → (−6, 0, 0) @0.40 (leans in) → (10, 0, 0) @1.10 →
    (16, 0, 0) @1.50 (the bow) → (4, 0, 0) @2.05 → 0 @2.40.
  - `head` ROT: 0 @0.00 → (−8, 0, 0) @0.30 (looks up at the player) →
    (26, 0, 0) @1.20 (down at the gift, then the bow) → (18, 0, 0) @1.60 →
    (−6, 0, 0) @2.10 (up again, happy) → 0 @2.40. **The eyeline arc —
    player, gift, player — is the acting.**
  - `right_leg` / `left_leg`: hold (−3, 0, −3) / (3, 0, 3); `root` POS
    0 → (0, −1.6, 0) @1.50 → 0 @2.40.
  - `cloak` ROT: 0 → (4, 0, 0) @0.55 → (9, 0, 0) @1.50 → 0 @2.40.
- **Accent:** **t = 0.55 s** → `hearthstead:gift_take` (a soft cloth-and-item
  handoff), tick 11; **t = 1.50 s** → `hearthstead:settler_thanks` mumble
  (subtitled — direct interaction), tick 30 of the 48-tick one-shot. Heart or
  spark particles fire at tick 30, **not** at tick 11 — the reaction, not the
  transaction.
- **Carry:** the gifted `ItemStack` renders in the hands from t = 0.55 s and is
  hidden at t = 2.10 s.

### 12.9 `CHILD_PLAY` — the children

- **Trigger:** child-stage settler with nothing to do. Children are a full
  life-wheel system (5–8 days per stage, school at the scribe's) and they need
  to look like children, not small adults. **Never let a child play an adult
  work clip.**
- **Activity:** `PLAYING`.
- **Length:** 2.80 s, **looping**.
- **Silhouette:** high-frequency, wide-amplitude, off-balance — arms out wide,
  a spin, a hop, a stumble. It breaks every "amplitudes stay in vanilla range"
  instinct on purpose: a child is the one settler allowed to look uncontrolled.
- **Bones:**
  - `root` POS: 0 @0.00 → (0, 2.2, 0) @0.30 LINEAR → 0 @0.55 LINEAR →
    0 @1.10 → (0, 1.8, 0) @1.35 LINEAR → 0 @1.60 LINEAR → 0 @2.10 →
    (0, 0.8, 0) @2.30 → 0 @2.80 — three hops of decreasing size.
  - `torso` ROT: (0, 40, 0) @0.00 → (−6, 120, 0) @0.70 → (−4, 200, 0) @1.40 →
    **not allowed** — `anim_check` caps at 180°. Instead do the spin in two
    halves: (0, 40, 0) @0.00 → (−6, 160, 0) @0.70 → (0, −160, 0) @0.75 →
    (−4, −40, 0) @1.40 → (2, 20, 0) @2.10 → (0, 40, 0) @2.80. **The 0.05 s
    wrap at 0.70→0.75 is invisible at 20 fps and keeps the clip legal.**
  - `right_arm` ROT: (−90, 0, −64) @0.00 → (−104, 0, −78) @0.70 →
    (−82, 0, −56) @1.40 → (−98, 0, −70) @2.10 → (−90, 0, −64) @2.80 — arms
    straight out sideways, the aeroplane.
  - `left_arm` ROT: mirrored, phase-shifted by 0.35 s so they are never level.
  - `head` ROT: (−16, 0, 8) @0.00 → (−22, 0, −10) @0.70 → (−14, 0, 12) @1.40 →
    (−20, 0, −8) @2.10 → (−16, 0, 8) @2.80 — thrown back, lolling.
  - `right_leg` ROT: (−22, 0, −8) @0.00 → (14, 0, −10) @0.30 →
    (−26, 0, −8) @0.70 → (10, 0, −9) @1.35 → (−22, 0, −8) @2.80;
    `left_leg` mirrored and offset — deliberately unsynchronised.
  - `cloak` ROT: (6, 0, 8) @0.00 → (16, 0, −10) @0.70 → (4, 0, 12) @1.40 →
    (14, 0, −8) @2.10 → (6, 0, 8) @2.80 — flying.
- **Accent:** **t = 0.30 s** → `hearthstead:child_laugh` (a short high mumble),
  tick 6 of a 56-tick cycle, 50 % chance per loop so it is not relentless.
- **Carry:** none. If two children are near each other, offset their clip
  phases by 20 ticks and let one play `CHILD_PLAY` while the other plays
  `SOCIAL_LISTEN` at 1.3× speed — chasing reads for free.

### 12.10 `COUPLE_GREET` — two settlers who are a couple meeting

- **Trigger:** an automatic couple (double bed) meeting after being apart for
  more than a few minutes; also the evening return home. Plays on **both**
  settlers, mirrored, with a shared start tick.
- **Activity:** **event** one-shot on both entities.
- **Length:** 2.60 s, **one-shot**.
- **Silhouette:** two figures leaning toward each other, one arm each reaching
  to the other's shoulder, heads inclined together — a symmetrical arch between
  two bodies. It is the only two-entity composition in the catalogue and it
  reads instantly as intimacy from any distance.
- **Bones (right-hand partner; the left-hand partner mirrors `y`/`z`):**
  - `right_arm` ROT: (−10, 0, −4) @0.00 → (−58, −34, −26) @0.55 →
    **(−64, −40, −30) @0.75** (hand lands on the shoulder) →
    (−60, −38, −28) @1.70 → (−24, −12, −10) @2.25 → (−10, 0, −4) @2.60.
  - `left_arm` ROT: (−12, 0, 4) @0.00 → (−22, 8, 10) @0.75 →
    (−18, 6, 8) @1.70 → (−12, 0, 4) @2.60 — the other arm barely moves; one
    hand is enough and two would read as a tackle.
  - `torso` ROT: 0 @0.00 → (6, −16, 0) @0.75 → (5, −15, 0) @1.70 →
    (2, −4, 0) @2.25 → 0 @2.60 — turns in toward the partner.
  - `head` ROT: 0 @0.00 → (4, −24, 0) @0.60 → (8, −22, 4) @1.00 →
    (7, −22, 4) @1.70 → (0, −8, 0) @2.30 → 0 @2.60 — inclines and **tilts**
    (the `z` at 1.00 s). The tilt is the tenderness.
  - `right_leg` / `left_leg`: hold (−4, −6, −3) / (4, −6, 3) — feet angled
    toward the partner.
  - `root` POS: 0 @0.00 → (0, −0.8, 0) @0.75 → (0, −0.6, 0) @1.70 → 0 @2.60.
  - `cloak` ROT: 0 @0.00 → (5, 0, −6) @0.80 → (3, 0, −4) @1.70 → 0 @2.60.
- **Accent:** **t = 0.75 s** → `hearthstead:settler_hm` warm variant on **one**
  of the two (the initiator), tick 15; the partner answers at tick 24. Two
  entities, staggered — never simultaneous, that always sounds like a bug.
- **Carry:** both settlers must have empty hands; if either is carrying, defer
  the greeting until the load is delivered. A courier hugging his wife through
  a crate is funny once and then it is a bug report.

### 12.11 `IDLE` — standing about *(exists; retune)*

- **Trigger:** the default. No task, not moving. On screen more than any clip
  except `WALK`, so its faults are the mod's most-seen faults.
- **Activity:** `IDLE`.
- **Length:** 4.00 s, **looping**. Values as authored (breath on `torso` SCALE,
  ±2.5° arm sway, an idle glance, small cloak lift), plus:
  - **Add `root` POS weight shift:** 0 @0.00 → (0, −0.7, 0) @1.60 → 0 @3.00 →
    (0, −0.5, 0) @3.60 → 0 @4.00. A standing person shifts weight; a settler
    who does not is furniture.
  - **Add `right_leg` / `left_leg`:** hold (0, 0, −2) / (0, 0, 3) — very
    slightly uneven. Perfectly symmetric legs read as a mannequin.
  - **Retune `head`:** the existing glance keys at 1.2 / 2.0 / 3.0 are close to
    evenly spaced. Move them to 0.9 / 1.4 / 3.1 and make the second glance
    smaller (−3° instead of −5°) — an uneven glance rhythm is the whole
    difference between alive and looping.
  - **Per-entity phase offset** of `entityId % 80` ticks (§17, check 25). A row
    of idle settlers breathing in unison is the most common village-mod tell.
- **Accent:** none. `hearthstead:settler_hm` may fire at t = 1.40 s with a low
  chance from the ambient voice system, not from the clip.
- **Carry:** if the settler is holding something and idle, play the carry arm
  layer over this clip and suppress the arm sway.

### 12.12 `REST` — sitting by the fire *(exists; keep)*

- **Trigger:** `RestAtNightGoal` at the hearth, and off-shift settlers at a
  social anchor. **This clip defines the SEATED base pose (§16.1)** — its leg
  and root values are copied by `EAT_AT_TABLE`, `SCRIBE_WRITE` and `CAPTIVE`,
  so it must not be changed without updating those three.
- **Activity:** `RESTING`.
- **Length:** 6.00 s, **looping**. Values as authored, plus:
  - **Add `cloak` ROT:** hold (−5, 0, 0) — pooled behind on the ground.
  - **Uneven breath:** the existing `torso` SCALE peak sits exactly at the
    midpoint (3.00 s). Move it to 2.40 s and add a smaller second breath at
    4.80 s, so the loop point is not also the breath point.
  - **Fire-glance beat:** every few loops (chance-based, as a 0.8 s additive
    one-shot) let the head lift from (10, 0, 0) to (−4, 6, 0) and back — a
    settler looking up from the fire at a passer-by.
- **Accent:** none.
- **Carry:** none; loads are set down before resting.

---

## 13. Raid and emergency

Raid night is the mod's climax and total destruction is possible. These clips
carry the fear. The design rule for the whole set: **civilians and soldiers
must never share a shape.** If the player cannot tell at a glance who can fight
and who cannot, the raid is unreadable and the command wheel is useless.

### 13.1 `EMERGENCY_FLEE_SHELTER` — reaching the door

- **Trigger:** the arrival beat at the end of a flight to shelter — the settler
  reaches the shelter door and it is closed or occupied. The *running* is
  `RUN_PANIC` (§1.4); this is what happens when the running stops.
- **Activity:** `FLEEING` while stationary at a shelter door.
- **Length:** 1.10 s, **looping**.
- **Silhouette:** hammering on a door with both fists while looking back over
  the shoulder — the body faces one way and the head the other, a wrenched,
  broken shape that appears nowhere else.
- **Bones:**
  - `right_arm` ROT: (−104, −6, −14) @0.00 → **(−86, −6, −12) @0.15 LINEAR** →
    (−106, −6, −14) @0.35 → **(−88, −6, −12) @0.50 LINEAR** →
    (−104, −6, −14) @0.75 → **(−84, −6, −12) @0.90 LINEAR** →
    (−104, −6, −14) @1.10 — three unequal hammer blows.
  - `left_arm` ROT: same shape offset by 0.10 s — the two fists never land
    together.
  - `torso` ROT: (6, 2, 0) @0.00 → (8, 0, 0) @0.50 → (6, 4, 0) @1.10.
  - `head` ROT: (0, 4, 0) @0.00 → (−4, −52, 0) @0.40 → (−6, −54, 0) @0.65 →
    (0, 8, 0) @1.10 — **wrenched around to look behind**, held, snapped back.
    This exceeds the ±30° head guideline deliberately; suppress head tracking
    to 0.0 for this clip so the value survives.
  - `right_leg` ROT: hold (−14, 0, −6); `left_leg` ROT: hold (8, 0, 6) —
    braced against the door.
  - `root` POS: (0, −1, 0) @0.00 → (0, −1.6, 0) @0.50 → (0, −1, 0) @1.10, with
    a −0.4 px LINEAR blip on each hammer.
  - `cloak` ROT: (8, 0, 0) @0.00 → (12, 0, −5) @0.45 → (8, 0, 4) @1.10 — still
    settling from the run.
- **Accent:** **t = 0.15, 0.50, 0.90** → `hearthstead:door_pound` (a heavy
  wooden thud), ticks 3, 10, 18 of a 22-tick cycle, plus a panicked mumble at
  tick 10 with 40 % chance.
- **Carry:** none — the load was dropped when the panic started.

### 13.2 `EMERGENCY_BUCKET` — the fire bucket chain

- **Trigger:** the arson/fire threat. Settlers form a chain from water to fire
  and pass buckets. Multiple settlers play this **in a synchronised wave**,
  each offset by 6 ticks along the chain, so the bucket visibly travels down
  the line. That wave is one of the best sights in the mod and it costs one
  clip.
- **Activity:** `BUCKET_CHAIN`.
- **Length:** 1.60 s, **looping**.
- **Silhouette:** a big sideways pass — the body twists a full 34° one way to
  take, then the other way to give, with both arms swinging a heavy vessel
  across the front at hip height. Chained together, the line looks like a rope
  being pulled.
- **Bones:**
  - `torso` ROT: (6, 30, 0) @0.00 → (10, 34, 0) @0.25 → (8, 0, 0) @0.75 →
    (10, −32, 0) @1.05 → (6, 30, 0) @1.60 — a genuine full-body twist, the
    biggest `y` in the catalogue.
  - `right_arm` ROT: (−48, 34, −10) @0.00 → **(−54, 38, −12) @0.25** (take) →
    (−56, 4, −14) @0.70 → (−50, −30, −12) @1.05 (give) → (−48, 34, −10) @1.60.
  - `left_arm` ROT: (−44, 30, 12) @0.00 → (−50, 34, 14) @0.25 →
    (−52, 2, 16) @0.70 → (−46, −28, 14) @1.05 → (−44, 30, 12) @1.60 —
    **both hands on the bucket**, moving as one unit.
  - `head` ROT: (6, 26, 0) @0.00 → (8, 30, 0) @0.25 → (4, −26, 0) @1.00 →
    (6, 26, 0) @1.60 — looks where the bucket is going, ahead of the arms.
  - `right_leg` ROT: hold (−6, 14, −6); `left_leg` ROT: hold (6, 14, 6) — feet
    planted at 45° to the chain, the natural stance for passing.
  - `root` POS: (0, −1, 0) @0.00 → (0, −2.4, 0) @0.30 (weight sags on the take)
    → (0, −0.6, 0) @1.05 → (0, −1, 0) @1.60.
  - `cloak` ROT: (4, 0, −5) @0.00 → (7, 0, −8) @0.35 → (5, 0, 7) @1.10 →
    (4, 0, −5) @1.60 — flung across on each pass.
- **Accent:** **t = 0.25 s** → `hearthstead:bucket_take` (a wooden clonk with a
  water slosh), tick 5; **t = 1.05 s** → `hearthstead:bucket_pass`, tick 21 of
  a 32-tick cycle. **The chain offset (6 ticks per settler) must be a multiple
  that keeps take-and-pass alternating down the line** — with a 32-tick loop and
  a 16-tick take-to-pass gap, an offset of 16 ticks per settler makes each
  settler's "take" land exactly on their neighbour's "pass". Use 16, not 6.
- **Carry:** bucket parented to `torso` at `(0, −4, −4)`, both hands on it.
  Visible for the whole loop; the last settler in the chain plays a variant
  where the bucket is hidden after t = 1.05 s (thrown on the fire).

### 13.3 `EMERGENCY_REPAIR` — mending the wall

- **Trigger:** the post-raid repair *dugnad*, and settlers repairing damaged
  gates/walls during a lull. Settlers **never build** (permanent invariant) —
  they repair and upgrade, and this clip is the visual statement of that rule.
- **Activity:** `REPAIRING`.
- **Length:** 1.80 s, **looping**.
- **Silhouette:** facing a vertical surface, one arm places while the other
  taps — a **two-height alternation against a wall**, distinct from smithing
  (which faces down at an anvil) and from mining (which drives forward with the
  whole body). Repair is gentle: no recoil, no spark.
- **Bones:**
  - `left_arm` ROT: (−98, 20, 14) @0.00 → (−104, 24, 16) @0.30 →
    (−102, 22, 15) @1.10 → (−98, 20, 14) @1.80 — holding the piece up against
    the wall, high and near-static.
  - `right_arm` ROT: (−82, −10, −8) @0.00 → (−96, −12, −10) @0.35 →
    **(−70, −8, −6) @0.50 LINEAR** (tap 1) → (−92, −10, −9) @0.85 →
    **(−68, −8, −6) @1.00 LINEAR** (tap 2) → (−86, −10, −8) @1.35 →
    (−82, −10, −8) @1.80 — two light mallet taps.
  - `torso` ROT: hold (−4, −6, 0) — leaning slightly *back* to see the work
    above eye level.
  - `head` ROT: hold (−16, −4, 0) — looking up at the repair. **The upward
    eyeline is the tell that separates repair from every other work clip.**
  - `right_leg` ROT: hold (−8, 0, −4); `left_leg` ROT: hold (6, 0, 4).
  - `root` POS: hold (0, 0.4, 0) — up on the toes reaching, with a −0.3 px
    LINEAR blip on each tap.
  - `cloak` ROT: hold (−5, 0, 0) — hanging back from the arch.
- **Accent:** **t = 0.50 s and t = 1.00 s** → `hearthstead:repair_tap` (a
  softer, woodier `anvil_light`), ticks 10 and 20 of a 36-tick cycle. Block
  HP restoration should tick on tick 20 so the visible damage state changes on
  a hammer blow, never between them.
- **Carry:** mallet right, a plank or stone piece in the left hand, parented to
  `left_arm`.

### 13.4 `EMERGENCY_CARRY_DOWNED` — dragging someone to safety

- **Trigger:** a settler or the player moving a downed settler out of the fight
  to the infirmary. Rescue is a core system (downed-not-dead) and this is what
  the player will remember from the raid.
- **Activity:** `CARRYING_DOWNED`; arms layer over `WALK_LADEN` at 0.75×
  cadence.
- **Length:** 2.20 s, **looping**.
- **Silhouette:** a deep backward lean with both arms cradling a horizontal
  body in front — the widest, heaviest, slowest shape in the mod, and the only
  time one settler's silhouette contains another. Unmistakable at any range.
- **Bones:**
  - `right_arm` ROT: (−70, 26, −18) @0.00 → (−67, 26, −20) @1.10 →
    (−70, 26, −18) @2.20 — locked cradle, 3° of strain.
  - `left_arm` ROT: mirrored — arms **wide apart** (the `y` spread of ±26° is
    what makes a body fit between them rather than a crate).
  - `torso` ROT: (−16, 0, 0) @0.00 → (−19, 0, 2) @0.55 → (−16, 0, 0) @1.10 →
    (−19, 0, −2) @1.65 → (−16, 0, 0) @2.20 — the deepest backward lean in the
    catalogue, with a strain wobble rolling side to side.
  - `head` ROT: (10, 0, 0) @0.00 → (14, −8, 0) @0.70 (checks the face of the
    person he is carrying) → (8, 0, 0) @1.40 → (10, 0, 0) @2.20. **That glance
    down at the casualty is the beat that makes players care.**
  - `cloak` ROT: hold (10, 0, 0) — pinned.
  - `root` POS: hold (0, −2, 0) — the heaviest load in the mod.
  - Legs: from `WALK_LADEN` at 0.75× cadence (a 1.60 s stride loop) with the
    stance widened by ±3° on `z`.
- **Accent:** **t = 0.55 s and 1.65 s** → `hearthstead:haul_strain` alternating
  with `hearthstead:settler_hm` at 0.4 volume, ticks 11 and 33 of a 44-tick
  cycle. Throttle to one vocal per 2 s.
- **Carry:** grammar CARRIED BODY. The downed entity is rendered with its own
  `DOWNED` pose (see below), parented to the carrier's `torso` at
  `(0, −8, −7)` with a 90° `z` rotation, plus a 2° sway at 0.5 Hz so it is not
  rigid.
- **Companion pose `DOWNED`** (1.0 s loop, played on the *downed* settler
  whether on the ground or being carried): `root` POS (0, −18, 0) if the
  renderer does not handle the lie-down (same caveat as `SLEEP_IN_BED`);
  `torso` ROT (0, 0, 84); `head` ROT (0, 14, 0); arms (−4, 0, ±20); legs
  (0, 0, ±6); `torso` SCALE 1.0 → (1.006, 1.01, 1.006) @0.50 → 1.0 @1.00 —
  a fast, shallow, *wrong* breathing rate. Downed settlers breathe twice as
  fast as sleeping ones; that single number is how the player tells "asleep"
  from "dying" at a glance.

### 13.5 `EMERGENCY_COWER` — hiding

- **Trigger:** civilian trapped with no shelter reachable, or a settler in a
  shelter while raiders are audible outside. The mod's rock-bottom pose.
- **Activity:** `COWERING`.
- **Length:** 2.60 s, **looping**.
- **Silhouette:** a ball — knees up, arms over the head, the head buried. It
  is the smallest silhouette in the mod, roughly half the standing height, and
  it is the exact inverse of `INN_GREET`'s open welcome. A square of cowering
  settlers in a lodging house should be legible through a window as *fear*.
- **Bones:**
  - `root` POS: hold (0, −9, 0) — crouched to the floor.
  - `right_leg` ROT: hold (−92, 0, −12); `left_leg` ROT: hold (−92, 0, 12) —
    knees pulled all the way up.
  - `torso` ROT: (44, 0, 0) @0.00 → (46, 0, 0) @1.30 → (44, 0, 0) @2.60 —
    folded right over the knees. Deepest torso fold in the catalogue.
  - `right_arm` ROT: hold (−158, 12, 26); `left_arm` ROT: hold (−158, −12, −26)
    — **over the head**, forearms shielding. This is the only clip where the
    arms go behind the head.
  - `head` ROT: hold (30, 0, 0) — buried. Head tracking damped to 0.0.
  - `torso` SCALE: 1.0 @0.00 → (1.014, 1.02, 1.014) @0.65 → 1.0 @1.30 →
    (1.014, 1.02, 1.014) @1.95 → 1.0 @2.60 — **fast, shallow, panicked
    breathing: two per loop.**
  - **Flinch beat (additive one-shot `COWER_FLINCH`, 0.35 s, triggered by any
    loud nearby sound — an explosion, a horn, a wall breaking):** `root` POS
    −1.5 px, `torso` `x` +6°, all four limbs tighten by 6°, over 0.10 s LINEAR,
    releasing over 0.25 s. **Every loud raid event should make the whole
    cowering room flinch in unison.** It is one tiny clip and it will be the
    thing people post clips of.
  - `cloak` ROT: hold (−8, 0, 0).
- **Accent:** none in the loop. `COWER_FLINCH` may play `hearthstead:whimper`
  at volume 0.2 with 25 % chance.
- **Carry:** none.

### 13.6 `MILITIA_STANCE` — frightened people holding tools

- **Trigger:** the tool-militia call-up from the command wheel. Civilians armed
  with work tools: desperate, frightened, costly. The design explicitly calls
  for *fear*, and this clip is where that promise is kept.
- **Activity:** `MILITIA`.
- **Length:** 2.20 s, **looping**.
- **Silhouette:** a guard's stance **done wrong** — feet too close together,
  the tool held too high and too tight with both hands, shoulders hunched up
  around the ears, and a head that jerks in short scared movements instead of
  sweeping. Side by side with a real guard, the difference is instantly
  legible, which is the entire design goal of the tool-militia.
- **Bones:**
  - `right_arm` ROT: (−106, 8, −22) @0.00 → (−102, 8, −24) @0.55 →
    (−108, 8, −21) @1.30 → (−106, 8, −22) @2.20 — held high across the chest,
    **with a visible tremor**: add ±1.5° jitter keys every 0.20 s. The tremor
    is the clip.
  - `left_arm` ROT: (−104, −10, 20) @0.00 → same tremor pattern, offset — both
    hands white-knuckled on the same haft.
  - `torso` ROT: (12, 4, 0) @0.00 → (14, −5, 0) @0.75 → (11, 6, 0) @1.55 →
    (12, 4, 0) @2.20 — hunched forward (a guard stands at 2°, the militiaman at
    12°) with restless, irregular twisting.
  - `head` ROT: (0, 0, 0) @0.00 → (2, −18, 0) @0.30 → (0, −16, 0) @0.55 →
    (4, 22, 0) @0.85 → (2, 20, 0) @1.05 → (0, −6, 0) @1.45 →
    (3, 14, 0) @1.80 → (0, 0, 0) @2.20 — **short, snapped, irregular glances**
    with tiny holds. Contrast this line by line with `GUARD_STANCE`'s smooth
    ±30° sweep: same channel, opposite character.
  - `right_leg` ROT: hold (−2, 0, −3); `left_leg` ROT: hold (2, 0, 3) — feet
    almost together (the guard's are 8° apart), an unstable base.
  - `root` POS: (0, −2, 0) @0.00 → (0, −2.8, 0) @0.60 → (0, −1.6, 0) @1.30 →
    (0, −2.4, 0) @1.85 → (0, −2, 0) @2.20 — never settles; keeps shifting
    weight.
  - `torso` SCALE: 1.0 @0.00 → (1.012, 1.016, 1.012) @0.50 → 1.0 @1.00 →
    (1.012, 1.016, 1.012) @1.50 → 1.0 @2.20 — quick shallow breaths.
  - `cloak` ROT: (3, 0, 2) @0.00 → (5, 0, −3) @0.80 → (3, 0, 3) @1.60 →
    (3, 0, 2) @2.20.
- **Accent:** none required. Optional `hearthstead:nervous_breath` at
  **t = 0.50 s**, tick 10 of a 44-tick cycle, volume 0.15, 20 % chance.
- **Carry:** the settler's work tool (hoe, axe, pick) in **both** hands, held
  like a weapon it is not. A farmer holding a hoe like a spear is the whole
  story in one image.

### 13.7 `CAPTIVE` — caged at a raider camp

- **Trigger:** a kidnapped settler in a visible cage at an enemy camp, awaiting
  a rescue expedition. The player will travel a long way to find this; the pose
  has to justify the trip.
- **Activity:** `CAPTIVE`.
- **Length:** 5.00 s, **looping** — slow and defeated, with one beat of hope.
- **Silhouette:** seated against the bars, knees up, head down — and **once per
  loop the head lifts and turns toward the outside**. That single lift, seen
  from outside the camp, is what makes the player commit to the rescue.
- **Bones:**
  - `root` POS: hold (0, −8, 1.5) — sitting back against the cage wall.
  - `right_leg` ROT: hold (−76, 0, −10); `left_leg` ROT: hold (−58, 0, 14) —
    one knee up, one leg sprawled. Asymmetric = exhausted, symmetric = posed.
  - `torso` ROT: (18, 6, 0) @0.00 → (19, 6, 0) @2.20 → (14, −4, 0) @3.00 →
    (16, −2, 0) @3.80 → (18, 6, 0) @5.00 — slumped, straightening slightly for
    the look-up.
  - `head` ROT: (34, 8, 0) @0.00 → (35, 8, 0) @2.20 →
    **(−4, −22, 0) @2.85** (the lift — fast, 0.65 s) → (−2, −24, 0) @3.30 →
    (12, −10, 0) @3.90 → (34, 8, 0) @5.00. Head tracking at **1.0** during
    ticks 57–78 only, so the captive genuinely looks at an approaching player,
    then sinks back. That is the hook.
  - `right_arm` ROT: hold (−28, 14, 18) — draped over the raised knee.
  - `left_arm` ROT: hold (−12, −6, −14) — limp at the side. Add a single slow
    drag at 4.20 s: (−18, −8, −16), back by 5.00 s.
  - `torso` SCALE: 1.0 @0.00 → (1.01, 1.016, 1.01) @1.60 → 1.0 @3.20 →
    1.0 @5.00 — one slow, deep, resigned breath per loop.
  - `cloak` ROT: hold (−6, 0, 0) — torn and hanging (use the damaged cloak
    texture variant).
- **Accent:** **t = 2.85 s** → `hearthstead:captive_stir` (a soft chain
  rattle), tick 57 of a 100-tick cycle, volume 0.3. On the rescue moment the
  captive plays `GIFT_ACCEPT` — receiving freedom uses the gratitude clip, and
  that reuse is intentional and correct (§16).
- **Carry:** none; hide all held items and any profession headgear.

---

## 14. Reaction beats

Short additive one-shots that layer over whatever the settler is already
doing. They are the difference between a village of workers and a village of
people. All of them are **additive**: they must be authored as small offsets
that read correctly on top of any base clip, so they touch as few bones as
possible and always return exactly to zero.

### 14.1 `REACT_STARTLE` — something happened behind me

- **Trigger:** a nearby loud sound, a mob spotted, a block broken close by, the
  player sprinting up behind a settler.
- **Activity:** **event** one-shot, additive over anything.
- **Length:** 0.60 s, **one-shot**.
- **Silhouette:** a sharp upward jolt of the whole body followed by a snap of
  the head toward the source. Fast enough that the player registers it
  peripherally, which is exactly what a startle should be.
- **Bones:**
  - `root` POS: 0 @0.00 → **(0, 1.4, 0) @0.08 LINEAR** → (0, −0.5, 0) @0.20 →
    0 @0.60. The jolt happens in under two frames.
  - `torso` ROT: 0 @0.00 → (−10, 0, 0) @0.08 LINEAR → (4, 0, 0) @0.25 →
    0 @0.60.
  - `head` ROT: 0 @0.00 → (−14, 0, 0) @0.08 LINEAR → (−4, 34, 0) @0.28 →
    (−2, 30, 0) @0.42 → 0 @0.60. The `y` is signed toward the source at
    runtime — author it positive and negate on the client when the source is on
    the other side.
  - `right_arm` ROT: 0 @0.00 → (−22, 0, −14) @0.10 LINEAR → 0 @0.60;
    `left_arm` mirrored — hands come up a little.
  - `cloak` ROT: 0 @0.00 → (−7, 0, 0) @0.12 → (3, 0, 0) @0.30 → 0 @0.60.
- **Accent:** **t = 0.08 s** → `hearthstead:startle_gasp` (a very short intake),
  tick 2, volume 0.3, 40 % chance so a crowd startling does not roar.
- **Carry:** carried loads do **not** drop on a startle (only on panic). The
  crate jerks with the torso, which is a free and very satisfying detail.

### 14.2 `REACT_SHIVER` — cold

- **Trigger:** winter, low warmth need, standing in rain or snow away from a
  fire. Warmth is a real need in the season system, and this clip is the
  player's first warning that firewood is short.
- **Activity:** `SHIVERING`, **looping** and additive over `IDLE`, `WALK`, or
  any standing work clip.
- **Length:** 0.80 s, **looping**.
- **Silhouette:** shoulders hunched up and in, arms clamped to the sides, and a
  fast fine tremor over everything — from a distance the settler looks *smaller*
  and slightly blurred. A field of shivering settlers reads as winter instantly.
- **Bones:**
  - `torso` ROT: (5, 0, 0) baseline with a tremor: +1.2° peaks at t = 0.10,
    0.30, 0.50, 0.70 (LINEAR in, CATMULLROM out). Four shudders per loop.
  - `right_arm` ROT: hold (−12, 0, 22); `left_arm` ROT: hold (−12, 0, −22) —
    clamped in against the ribs. **The additive `z` clamp is what makes it read
    as cold rather than as a glitch.**
  - `head` ROT: hold (8, 0, 0) with ±1° tremor at double the torso rate (peaks
    every 0.10 s) — the head shivers faster than the body.
  - `root` POS: hold (0, −1, 0) with a −0.3 px blip on each torso shudder.
  - `torso` SCALE: 1.0 @0.00 → (1.006, 0.996, 1.006) @0.40 → 1.0 @0.80 — a
    slight *narrowing*: pulling in.
  - `cloak` ROT: hold (−6, 0, 0) — pulled tight around the shoulders.
  - `right_leg` / `left_leg`: hold (0, 0, −4) / (0, 0, 4) — knees together.
- **Accent:** `hearthstead:shiver_breath` at **t = 0.10 s**, tick 2 of a
  16-tick cycle, volume 0.12, 15 % chance per loop. Cold breath particles
  should puff on the same tick.
- **Carry:** compatible with all carry grammars — apply the tremor to `torso`
  only when a load is held, so the crate visibly shakes.

### 14.3 `REACT_EXHAUSTED` — out of energy

- **Trigger:** energy below 20, or 30 s after finishing a long haul. Precedes
  the settler going to rest, and gives the player a chance to intervene.
- **Activity:** `EXHAUSTED`, **looping**, replaces `IDLE` rather than layering.
- **Length:** 3.40 s, **looping**.
- **Silhouette:** bent forward with both hands on the knees, back rounded, head
  hanging — the universal "give me a minute" pose. Nothing else in the mod puts
  the hands on the knees.
- **Bones:**
  - `torso` ROT: (40, 0, 0) @0.00 → (43, 0, 0) @1.00 → (38, 0, 0) @2.20 →
    (40, 0, 0) @3.40 — deeply folded, rising and falling with the breath.
  - `right_arm` ROT: hold (−58, 20, −24); `left_arm` ROT: hold (−58, −20, 24) —
    straight down onto the knees, elbows locked.
  - `head` ROT: (24, 0, 0) @0.00 → (30, 0, 0) @1.00 → (18, 4, 0) @2.20 →
    (24, 0, 0) @3.40 — hangs, with one weak lift.
  - `right_leg` ROT: hold (−16, 0, −6); `left_leg` ROT: hold (−16, 0, 6) —
    knees bent under the fold.
  - `torso` SCALE: 1.0 @0.00 → (1.028, 1.034, 1.028) @0.85 → 1.0 @1.70 →
    (1.026, 1.032, 1.026) @2.55 → 1.0 @3.40 — **the deepest breathing in the
    catalogue**, two heaving breaths per loop. This clip is 80 % breath.
  - `root` POS: (0, −3, 0) @0.00 → (0, −3.8, 0) @1.70 → (0, −3, 0) @3.40.
  - `cloak` ROT: (12, 0, 0) @0.00 → (15, 0, 0) @1.00 → (12, 0, 0) @3.40 —
    hanging forward off the rounded back.
- **Accent:** **t = 0.85 s and t = 2.55 s** → `hearthstead:heavy_breath`
  (ticks 17 and 51 of a 68-tick cycle), volume 0.25 — audible only within
  6 blocks, so you have to be near a settler to hear that they are spent.
- **Carry:** exhausted settlers put loads down first; no carry variant.

### 14.4 `REACT_HUNGRY` — the hunger pang

- **Trigger:** hunger need below 25. Additive over work clips, so a hungry
  farmer keeps working but visibly suffers — the player sees productivity as a
  *feeling*, not a number.
- **Activity:** **event** one-shot, fired every 8–14 s while hungry (jittered).
- **Length:** 1.40 s, **one-shot**.
- **Silhouette:** one hand goes to the stomach and the torso curls in around it
  for a beat. Small, brief, and unmistakable.
- **Bones:**
  - `left_arm` ROT: 0 @0.00 → (−52, −26, 20) @0.30 → (−56, −28, 22) @0.60 →
    (−30, −14, 12) @1.05 → 0 @1.40 — the free hand (left, so a right-handed
    tool keeps working through the pang).
  - `torso` ROT: 0 @0.00 → (10, 0, 0) @0.35 → (12, 0, 0) @0.65 →
    (4, 0, 0) @1.10 → 0 @1.40 — curls in.
  - `head` ROT: 0 @0.00 → (10, −6, 0) @0.40 → (12, −6, 0) @0.70 → 0 @1.40 —
    down toward the hand.
  - `root` POS: 0 @0.00 → (0, −1.2, 0) @0.55 → 0 @1.40.
  - `cloak` ROT: 0 @0.00 → (4, 0, 0) @0.55 → 0 @1.40.
  - `right_arm`, legs: **untouched** — that is what makes it additive over a
    work loop.
- **Accent:** **t = 0.60 s** → `hearthstead:stomach_growl` (a low gurgle),
  tick 12, volume 0.2, audible within 5 blocks. 35 % chance so it is a
  surprise, not a metronome.
- **Carry:** if carrying, suppress the `left_arm` channel and let the torso
  curl carry the read alone.

### 14.5 `REACT_BLESSED` — after a blessing

- **Trigger:** a person-blessing applied to this settler at the hearth
  card-reveal. The blessing loop is the roguelike payoff and the settler must
  visibly *receive* something.
- **Activity:** **event** one-shot.
- **Length:** 2.80 s, **one-shot**.
- **Silhouette:** the body straightens up out of whatever slump it was in,
  arms open slightly at the sides, palms turned out, head tipped back toward
  the light. It is `WAKE_STRETCH`'s vocabulary with the tiredness removed and
  the timing reversed — **fast up, slow release** instead of slow up, fast drop.
- **Bones:**
  - `root` POS: 0 @0.00 → **(0, 1.6, 0) @0.45** → (0, 1.2, 0) @1.40 →
    (0, 0.3, 0) @2.30 → 0 @2.80 — lifted, then gently set down. The rise is
    the blessing.
  - `torso` ROT: 0 @0.00 → (−14, 0, 0) @0.45 → (−12, 0, 0) @1.40 →
    (−3, 0, 0) @2.30 → 0 @2.80.
  - `torso` SCALE: 1.0 @0.00 → (1.022, 1.026, 1.022) @0.55 → (1.01, 1.012,
    1.01) @1.60 → 1.0 @2.80 — a filling breath.
  - `right_arm` ROT: 0 @0.00 → (−24, −20, −38) @0.50 → (−22, −18, −36) @1.50 →
    (−8, −6, −14) @2.35 → 0 @2.80 — open at the side, palm out (the `z`).
  - `left_arm` ROT: mirrored.
  - `head` ROT: 0 @0.00 → (−26, 0, 0) @0.50 → (−24, 0, 0) @1.50 →
    (−8, 0, 0) @2.35 → 0 @2.80 — tipped back and up.
  - `right_leg` / `left_leg`: hold (0, 0, −2) / (0, 0, 2) with `root` doing
    the lift; a small toe-rise reads through the `root` offset.
  - `cloak` ROT: 0 @0.00 → (−12, 0, 0) @0.50 → (−8, 0, 0) @1.50 → 0 @2.80 —
    lifted behind, as if by a draught.
- **Accent:** **t = 0.50 s** → `hearthstead:blessing_receive` (a `bell_tone`
  chord with a long tail, ~2.0 s), tick 10 of the 56-tick one-shot. Blessing
  particles rise from tick 10 to tick 46 — **rising**, matched to the body's
  lift and its slow settle. A settler may follow this with `CELEBRATE`, but
  only after a 10-tick gap; back-to-back reads as a stutter.
- **Carry:** none; loads are set down for the hearth ceremony.

---

## 15. Implementation order

**65 primary clips + 5 sub-variants.** Authoring them all now would be waste:
a clip whose gameplay does not exist cannot be seen, cannot be verified against
a real goal's tick timing, and cannot be judged. Clips are authored **in the
phase where their system lands**, and the QA gate for that phase includes them.

The two clips carried forward from the prototype that everything else is
measured against are `WALK` (locomotion craft) and `CHOP` (impact craft). They
are retuned first, in A1, because every later clip copies their conventions.

### Phase A1 — Foundation (now)

Everything here has live gameplay in the current build or lands with homes and
modular visuals. 23 clips.

| clip | status | why now |
|---|---|---|
| `WALK` | retune | reference gait; every locomotion clip derives from it |
| `IDLE` (§12.11) | retune (`root` weight shift, uneven legs, uneven glance) | on screen constantly |
| `WALK_HURRIED` | new | guards move to posts already |
| `RUN_PANIC` | new | `SettlerPanicGoal` exists and currently plays `WALK` |
| `WALK_LIMP` | new | settlers already take damage |
| `CREEP_NIGHT` | new | night + lighting already matter |
| `CLIMB_LADDER` | new | free win; ladders already exist in player builds |
| `FARM_TILL` | rename + retune from `FARM` | `FarmerWorkGoal` live |
| `FARM_PLANT` | new | `FarmerWorkGoal` already replants — it just has no clip |
| `FARM_HARVEST` | new | `FarmerWorkGoal` already harvests |
| `FARM_WATER` | new | completes the farmer before A2 moves on |
| `CHOP` | retune (legs, cloak, root) | reference impact clip |
| `LIMB_BRANCHES` | new | `LumbererWorkGoal` fells top-down; limbing is implicit |
| `HAUL_LOG` | new | the goal already carries logs home with no carry pose |
| `GUARD_STANCE` | retune | live |
| `GUARD_PATROL` | new | `GuardPatrolGoal` live and currently walks like a civilian |
| `MELEE` | retune (legs — current version is genuinely broken) | live |
| `SHIELD_BLOCK` | new | guards already take hits |
| `EAT` | retune (cloak, root) | live |
| `REST` (§12.12) | keep + uneven breath; defines the **SEATED base** (§16.1) | live |
| `CELEBRATE` | retune (legs, cloak, per-entity variation) | live |
| `SLEEP_IN_BED` + `WAKE_STRETCH` | new | beds and bed-claiming land in A1c |

**A1 exit criterion:** `python3 tools/anim_check.py` green with the new
assertions from §17, `tools/hearthstead-qa animation` PASS, and every clip
above visually inspected in a preview reel.

### Phase A2 — Logistics and livelihood

Lands with the warehouse, courier, tavern recruiting, lodging, cook and meals.
16 clips. **The courier set is the phase's headline deliverable** — it is the
first thing to author in A2, not the last.

`COURIER_LIFT`, `COURIER_CARRY`, `COURIER_SET_DOWN`, `COURIER_SORT`,
`WALK_LADEN`, `INN_POUR`, `INN_SERVE`, `INN_GREET`, `COOK_CHOP_VEG`,
`COOK_STIR`, `COOK_SERVE_MEAL`, `EAT_AT_TABLE`, `SOCIAL_TALK`,
`SOCIAL_LISTEN`, `REACT_HUNGRY`, `REACT_EXHAUSTED`.

`WALK_LADEN` must be authored **before** the four courier clips — they are all
layers over it and cannot be judged without it.

### Phase A3 — Raid vertical

Lands with the first faction, telegraphed raids, gates, horn commands, militia,
downed/rescue, healer, repair dugnad and Blessings v1. 14 clips.

`HORN_CALL`, `RALLY`, `MILITIA_STANCE`, `EMERGENCY_FLEE_SHELTER`,
`EMERGENCY_COWER` (+ `COWER_FLINCH`), `EMERGENCY_REPAIR`,
`EMERGENCY_CARRY_DOWNED` (+ `DOWNED`), `HEAL_BANDAGE`, `HEAL_REVIVE`
(+ `REVIVE_SUCCESS` / `REVIVE_FAIL`), `HEAL_TEND_HERBS`, `MOURN`
(+ `MOURN_SOB`), `REACT_STARTLE`, `REACT_BLESSED`, `GIFT_ACCEPT`.

`HORN_CALL` and `RUN_PANIC` must be verified **together** — the two-tick
causal gap between the horn and the village panic (§4.5) is the single most
important cross-clip timing in the mod and it can only be judged live.

### Phase B1 — Depth

Miner, smith, equipment progression, seasons and winter, fire, kidnapping.
8 clips.

`MINE_PICK`, `MINE_HAUL_ORE`, `SMITH_HAMMER`, `SMITH_BELLOWS`, `SMITH_QUENCH`,
`EMERGENCY_BUCKET`, `REACT_SHIVER`, `CAPTIVE`.

### Phase B2 — Living world

Families, children, school, festivals, the saga chronicle. 4 clips.

`SCRIBE_WRITE`, `SCRIBE_TEACH`, `CHILD_PLAY`, `COUPLE_GREET`.

`CHILD_PLAY` cannot be authored earlier than the child model scale exists —
the amplitudes above assume a smaller body and will look manic on an adult.

### Phase 1.0 — Polish pass

No new clips. A dedicated pass over the whole catalogue for:

- per-entity phase offsets and amplitude jitter on every looping social clip,
- transition smoothing between the highest-traffic pairs
  (`WALK` ↔ `COURIER_CARRY`, `GUARD_STANCE` ↔ `MELEE`, `IDLE` ↔ any work clip),
- audio mixing pass across every accent frame at once, in-game,
- the trailer-worthy moments audit: does `HEAL_REVIVE` land? does the bucket
  chain wave read? does a cowering room flinch together?

---

## 16. Reuse rule

The owner's directive is that every task has its own animation. That rule is
about **what the player can distinguish**, not about forbidding shared
scaffolding. This section states exactly where sharing is legitimate and where
it is a defect.

### 16.1 Shared base poses — legitimate

Four base poses are shared. Sharing here is correct because the differentiating
motion lives in other channels, and because it keeps the poses consistent (a
kneeling healer and a kneeling farmer should kneel the same way).

| base | pose | used by | what makes each distinct |
|---|---|---|---|
| **SEATED** | `root` (0, −7, 0); `right_leg` (−84, 0, −5); `left_leg` (−84, 0, 5) | `REST`, `EAT_AT_TABLE`, `SCRIBE_WRITE`, (`CAPTIVE` at −8) | arms, head, breath rate — all completely different |
| **KNEELING** | `root` (0, −6, 0); one leg folded ~−60°, one at ~−32° with opposite `z` | `FARM_PLANT`, `HEAL_BANDAGE`, `HEAL_REVIVE` beat 1 | arm motion is bespoke in each; the fold is just anatomy |
| **BRACED WORK STANCE** | `right_leg` (−6..−16, 0, −4..−6); `left_leg` mirrored | `FARM_TILL`, `CHOP`, `LIMB_BRANCHES`, `MINE_PICK`, `SMITH_HAMMER`, `EMERGENCY_REPAIR` | every one of these is a *different swing* on the same feet |
| **CARRY GRAMMARS** | the four arm-lock poses in §0.5 | all hauling clips | the load and the gait are what differ, and they differ a lot |

### 16.2 Layered clips — legitimate

These clips are **authored as layers** and are meaningless alone. They are not
reuse; they are composition, and they exist so that the leg engine is written
once and correctly.

| layer | provides | rides on |
|---|---|---|
| `WALK_LADEN` | legs, torso lean, root drop, cloak pin | `COURIER_CARRY`, `HAUL_LOG`, `MINE_HAUL_ORE`, `EMERGENCY_CARRY_DOWNED` (arms only, each) |
| `WALK` | legs, bob, cloak swing | `GUARD_PATROL` (arms + head only) |
| `REACT_*` | small additive offsets | any base clip |

**Hard rule:** a layer clip may not be shown to the player on its own. If
`WALK_LADEN` ever plays with no arm layer over it, the settler will walk around
leaning backward holding nothing, and that is a bug — assert it (§17).

### 16.3 Deliberate reuse — approved, with reasons

Three cases where one clip serves two situations. Each is a design choice, not
laziness, and each is recorded here so it is never "fixed" by accident.

1. **`GIFT_ACCEPT` plays on a rescued captive.** Receiving freedom and
   receiving a gift are the same emotional beat and the same body language.
   Approved.
2. **`CELEBRATE` serves festival, raid-survived, blessing-revealed and
   building-completed.** These are all "the village is happy", and per-entity
   phase/amplitude variation (§12.7) makes each crowd instance look different.
   Approved.
3. **`COOK_STIR` serves the healer brewing remedies.** Same physical action,
   same vessel, different profession outfit. Approved.

Everything else is bespoke.

### 16.4 Must be fully bespoke — no exceptions

These pairs are the ones a future implementer will be tempted to collapse.
They must not be.

| tempting collapse | why it is forbidden |
|---|---|
| `CHOP` for `MINE_PICK` | one is a downward fell, one is a forward drive with a rebound; the rebound is the whole read |
| `CHOP` for `LIMB_BRANCHES` | slow-and-grand vs fast-and-choppy; the rhythm is the profession's identity |
| `FARM_TILL` for `FARM_PLANT` / `FARM_HARVEST` / `FARM_WATER` | the four farm tasks read as four different *heights* (§2); collapsing them loses the entire farmer |
| `GUARD_STANCE` for `MILITIA_STANCE` | the design's whole point is that militia are visibly not soldiers |
| `WALK_HURRIED` for `RUN_PANIC` | busy vs terrified; the head is level in one and thrown back in the other |
| `INN_SERVE` for `COOK_SERVE_MEAL` | one arm and a tankard vs two arms and a tray |
| `HAUL_LOG` for `MINE_HAUL_ORE` | a bar vs a bulge; head up-and-turned vs down-and-forward |
| `SOCIAL_TALK` for `SOCIAL_LISTEN` | two mirrored talkers is exactly TekTopia's failure |
| `REST` for `EAT_AT_TABLE` / `SCRIBE_WRITE` | shared SEATED base only; arms, head and rhythm bespoke |
| `IDLE` for anything | `IDLE` is the absence of a task; using it as a work loop is the placeholder this whole document exists to prevent |
| any adult work clip for `CHILD_PLAY` | children are a separate life stage with separate physics |

### 16.5 Renames and deprecations

| old | new | note |
|---|---|---|
| `FARM` | `FARM_TILL` | the generic farm loop becomes the tilling clip; the other three farm tasks stop borrowing it. Update `SettlerModel.setupAnim` and `SettlerEntity.farmState`. |
| — | — | No other constant is renamed. `IDLE`, `WALK`, `EAT`, `CHOP`, `GUARD_STANCE`, `MELEE`, `REST`, `CELEBRATE` keep their names. |

`SettlerEntity`'s eight `AnimationState` fields will not scale to 65 clips.
Before A2, replace them with a small state machine: one `AnimationState` for
the **base pose layer**, one for the **locomotion layer**, one for the
**carry/arm layer**, and a short queue for **one-shots**. The catalogue is
written assuming exactly those four slots.

---

## 17. QA — what `tools/anim_check.py` must assert

`anim_check.py` today parses every definition and checks timestamp ordering,
loop closure, length bounds, and three hard amplitude caps, plus two named
contracts (`CHOP` strike, `REST` sink). It must grow with the catalogue. Every
item below is a check the script can make **statically, from the Java source**,
with no client boot — which is why it stays in the fast gate.

### 17.1 Keep (already implemented)

1. Timestamps ascending per channel.
2. Last keyframe ≤ clip length.
3. Looping channels close (first vec == last vec within 0.01).
4. Rotation ≤ 180°, position ≤ 12 px, scale within 0.5–1.5.
5. `CHOP` right_arm strike at 0.55 s with `LINEAR`.
6. `REST` root sinks (negative y).

### 17.2 New — structural

7. **Bone whitelist.** Every `addAnimation` bone must be one of the eight in
   §0.1. A typo currently fails silently at runtime; it must fail the gate.
   *(Error, not warning.)*
8. **Tick grid.** Every keyframe timestamp and every clip length must be a
   multiple of 0.05 s (within 1e-6). A keyframe at 0.53 s can never be
   sound-synced. *(Error.)*
9. **Looping channels end at length.** Currently a warning; promote to an
   error for any clip in the catalogue, since a channel that holds its last
   pose desynchronises from the others across the loop.
10. **Duplicate channel detection.** The same (bone, target) pair added twice
    to one definition silently discards one — `WALK` legitimately has
    `torso` ROTATION and `torso` POSITION, which is different. Assert no
    exact (bone, target) duplicate. *(Error.)*
11. **No empty clips and no single-keyframe channels** on a looping clip.
12. **Catalogue coverage.** Parse the clip-name list out of this document —
    headings match `^### \d+\.\d+ \`(NAME)\`` and one heading (§12.5) declares
    two names joined by `and` — then report which catalogued clips are not yet
    implemented, and which implemented clips are not catalogued. Missing
    implementations are a **warning** (phased authoring is expected);
    **uncatalogued clips are an error** — every clip must be designed here
    first.

### 17.3 New — the sound-sync contract

13. **Contract table.** Replace the hardcoded `CHOP` check with a table
    generated from §0.4 of this document, of the form
    `(clip, bone, target, accent_seconds, interp, sound_id, tick, period)`.
    For each row assert: a keyframe exists at `accent_seconds` on that channel,
    its interpolation is `LINEAR`, `round(accent_seconds * 20) == tick`, and
    `round(length * 20) == period`.
14. **Goal-side cross-check.** Grep the AI goal sources for
    `workTicks % N == K` patterns and assert they match the contract table.
    This would have caught the live `FarmerWorkGoal` bug — it plays at
    `% 12 == 3` while the clip's stroke lands at 0.60 s of a 1.5 s loop
    (§2.1), so the farmer's sound and motion have never been in sync.
    *(Error once `FARM_TILL` lands.)*
15. **Sound existence.** Every `sound_id` in the contract table must exist in
    `assets/hearthstead/sounds.json`. *(Error.)*
16. **Impact-frame neighbours.** For every keyframe marked `LINEAR`, assert the
    immediately preceding keyframe on the same channel is also `LINEAR` or is
    within 0.10 s. A Catmull-Rom approach to an impact pre-swings through the
    contact point. *(Warning — there are legitimate exceptions.)*

### 17.4 New — craft assertions

These encode the rules that make the catalogue better than a list of poses.
They are the ones worth arguing about, so each says what it is protecting.

17. **Every clip touches ≥ 3 bones.** A two-bone clip is a placeholder.
    Exempt: the `REACT_*` additive one-shots and layer clips, by name.
    *(Error.)*
18. **Every looping clip ≥ 1.0 s long has motion on `cloak`, or is on the
    pinned-cloak allowlist** (`COURIER_CARRY`, `EMERGENCY_CARRY_DOWNED`,
    `SLEEP_IN_BED`, `MOURN`, `CAPTIVE`, `HEAL_BANDAGE`, `COOK_CHOP_VEG`,
    `SMITH_HAMMER`). The cloak is the signature secondary motion; forgetting it
    is the single most common way a clip reads stiff. *(Warning with the
    allowlist named in the message.)*
19. **Silhouette distinctness.** For every pair of clips, compare the pose at
    t=0 across all eight bones as a vector; if two clips' start poses are within
    a small distance **and** their lengths are within 0.1 s, flag them. This is
    the automated version of "no two tasks share a clip". *(Warning — it will
    correctly flag the shared base poses of §16.1, which should then be
    allowlisted by pair, making the allowlist itself the reviewed artifact.)*
20. **Work clips have legs.** Any clip whose name is not in the `REACT_*`,
    layer, or seated set must animate or hold `right_leg` and `left_leg`. The
    existing `CHOP` and `MELEE` both fail this today and it is why they read as
    floating. *(Error.)*
21. **One-shots return to neutral.** For a non-looping clip, the final keyframe
    of every channel must be within 3° / 0.5 px / 0.01 scale of the channel's
    first keyframe, unless the clip is on the `ENDS_IN_POSE` allowlist
    (`COURIER_LIFT`, `HEAL_REVIVE`). Otherwise the settler snaps when the
    one-shot expires. *(Error.)*
22. **Carry clips lock their arms.** For every clip declared as a carry layer,
    assert total travel on `right_arm` and `left_arm` rotation is ≤ 6° per
    channel across the whole clip. This is §0.5's most important rule and the
    easiest one to erode during a later "make it livelier" pass. *(Error.)*
23. **Layer clips are never standalone.** Assert that every clip named in
    §16.2 as a layer is referenced in `SettlerModel.setupAnim` only inside a
    conditional that also plays an arm layer. *(Warning — structural grep.)*
24. **Head-tracking damping table.** Clips listed in this document with a
    damping value (`SLEEP_IN_BED` 0.0, `MOURN` 0.0, `EMERGENCY_COWER` 0.0,
    `EMERGENCY_FLEE_SHELTER` 0.0, `HEAL_BANDAGE` 0.1, `SHIELD_BLOCK` 0.15,
    `SMITH_HAMMER` 0.25, `CLIMB_LADDER` 0.3, `COOK_CHOP_VEG` 0.3,
    `SCRIBE_WRITE` 0.35, `RUN_PANIC` 0.4, `REST`/`EAT` 0.25) must have a
    matching entry in `SettlerModel.setupAnim`'s damp table. A mourner who
    turns to stare at the player destroys the scene. *(Error.)*
25. **Per-entity variation.** `CELEBRATE`, `SOCIAL_TALK`, `SOCIAL_LISTEN`,
    `SLEEP_IN_BED`, `CHILD_PLAY`, `WAKE_STRETCH` must have a phase-offset or
    amplitude-jitter call site. Crowds in unison are the uncanny-valley
    failure mode of every village mod. *(Warning — grep for the offset helper.)*

### 17.5 Beyond the static check

Static parsing cannot see whether a clip is *good*. Two additions to the wider
QA pipeline, both consistent with `qa/PROTOCOL.md`'s evidence rule:

- **Pose sampler / preview reel.** Extend `tools/preview_settler.py` to sample
  any clip at N evenly spaced times and composite an orthographic contact sheet
  (front + side) — the headless equivalent of flipping through keyframes. A
  new clip is not reviewable without one, and `tools/hearthstead-qa visual`
  should treat a missing reel for a new clip as BLOCKED, not PASS. The
  silhouette claim in each entry above is checkable this way: **render the
  side view as a solid black fill and confirm the activity is still
  identifiable.** That is the literal 30-block test and it should be an
  artifact in `qa/reports/`.
- **In-game audio-sync capture.** For each contract row, a GameTest that spawns
  a settler, forces the activity, and asserts the sound event fires on the
  contracted tick. This is the only way to prove the three-way contract
  (comment, goal, checker) is actually honoured at runtime.

**Never weaken the judge to satisfy the fixer.** If a clip cannot meet an
assertion here, the clip changes, or the assertion is renegotiated in the
quality ledger as a recorded specification correction — never silently relaxed.


## 18. Craft motions — one action, many trades

**D-015.** Every settler task having its own clip is a permanent invariant, and
it exists to forbid a single generic work loop that every profession shares.
It does not require a clip per **job title**, and pretending it does produces
worse animation, not better: a butcher and a tanner make the same stroke at the
same bench, and a smith and a mason both swing a hammer at a hard surface.
Giving those two pairs four subtly different clips would be four half-observed
animations instead of two well-observed ones.

So these six clips are keyed to the **action**. Eleven trades map onto them,
and none of the six is generic — each is a specific job of work with its own
timing, weight and failure mode.

| motion | trades | why they share it |
|---|---|---|
| `KNEAD` | baker, cook | pressing food into a bench |
| `CLEAVE` | butcher, tanner | a short cleaving stroke at board height |
| `STOKE` | smelter | two-handed bellows, and flinching off the heat |
| `HAMMER_ANVIL` | smith, mason | a full strike at a hard surface |
| `SAW` | sawyer, carpenter | a two-handed push-pull cut |
| `FINE_WORK` | weaver, fletcher | close, quick, fiddly hand work |

**This supersedes** the per-profession entries §9.1 `SMITH_HAMMER` and §9.2
`SMITH_BELLOWS` (now `HAMMER_ANVIL` and `STOKE`), and will absorb §7.1
`COOK_CHOP_VEG` into `CLEAVE` when the kitchen's own clips land. Those rows
stay in the table as planned refinements, not as duplicates to be built.

### 18.1 `KNEAD` — dough into a bench *(1.20 s, loop)*

No strike, so no impact beat. The weight comes from **continuous pressure**:
the push bottoms out and *stays* there while the torso keeps driving down for
three more ticks, which reads as leaning body weight onto the heel of the hand
rather than tapping a table. Arms alternate out of phase so the hands look
busy. Root drops 1.1 px at the press *(punch-up 2026-08-25, was 0.6; the arm
and torso reach also roughly doubled)*.

### 18.2 `CLEAVE` — the butcher's stroke *(0.85 s, loop)*

Shorter travel and much faster through the bottom than `CHOP` — a cleaver is
light and the target is close. Keeps the standard's beat: three ticks parked at
the board. The off hand holds the work down and barely moves, which is what
makes the stroke look aimed rather than flailed. *(Punch-up 2026-08-25: wind-up
and strike both deepened roughly 30%, torso now swings −4° to +28° instead of
sitting near +12°, root dips −1.0 instead of −0.5.)*

### 18.3 `STOKE` — bellows, and the heat *(1.40 s, loop)*

Slow, two-handed, resisted. The beat is at the **end of the stroke**, arms
compressed while the fire answers. The recovery is the interesting half: the
torso pulls back *and away* (root −0.6 z) rather than simply returning, which
reads as standing in front of something far too hot. *(Punch-up 2026-08-25:
was −0.35; the push itself now drives the arm to −105° instead of −86°, and
torso swings up to 37° instead of 26°.)*

### 18.4 `HAMMER_ANVIL` — the strike *(1.00 s, loop)*

The heaviest clip in the mod, and the reference implementation of the craft
standard: the wind-up accelerates into the top, the torso peaks noticeably
before the arm reaches the anvil, the hammer parks for a four-tick beat at the
bottom, and the recovery overshoots to −52° — well past the −40° rest — before
settling *(punch-up 2026-08-25: was −46°; the wind-up itself now reaches
−172° and the strike snaps to −30°, both deeper than before)*. The off hand
grips with tongs and does not move; a still hand beside a violent one is what
makes the violent one read.

### 18.5 `SAW` — the two-handed cut *(1.10 s, loop)*

No impact, so the weight lives in the **reversals**: each end of the stroke
holds two ticks while the blade bites and the body changes direction. A saw
animated as a smooth sine wave looks like waving; the pauses are what make it
cut. Root travels +0.6/−0.5 z and −0.6/0 y with the stroke *(punch-up
2026-08-25: was ±0.35 z only; the arm swing itself now reaches −140° instead
of −82°)*.

### 18.6 `FINE_WORK` — close hand work *(0.90 s, loop)*

Deliberately the opposite of everything above: small amplitude, high frequency,
head down at 27–31°, torso almost still. It is in the set to make the heavy
clips read heavy — a village where every trade swings from the shoulder has no
scale to it. Two passes per loop so the hands look busy rather than metronomic,
the second pass a touch deeper than the first so it never reads as a metronome.

### 18.7 `GATHER_LOG` — the lumberjack stoops *(1.10 s, one-shot)*

Felling was only ever half the job. The half that makes it read as *work* is
what happens after the tree comes down: the knees bend, the back takes the
weight, and the settler comes up **slower than they went down** — 0.35 s to
drop, 0.55 s to rise. That asymmetry is the whole clip, because standing up
under a log is not the reverse of bending over. Root drops 2.4 px.

### 18.8 `OVEN_TEND` — the baker's peel *(1.60 s, loop)*

The signature is the **flip**. Both hands drive the peel forward into the oven
mouth, hold three ticks while the loaf goes in, then the wrists snap over — a
fast roll on the arms' Z axis (±61°, punch-up 2026-08-25, was ±46°) that
nothing else in the mod does. The torso then leans away as the heat comes
back, swinging to −18° instead of −10°. That recoil is the beat you actually
recognise a baker by.

### 18.9 `SOW_BROADCAST` — the farmer casts seed *(1.40 s, loop)*

The oldest sowing motion there is, and it makes a farmer readable at fifty
blocks. The off hand cradles the seed bag at the hip, the right hand dips into
it, then sweeps across the body in a wide arc and opens. The **release** is the
beat — the arm parks two ticks at the end of the arc while the seed leaves the
hand — and the torso rotates a full 63° across the stroke *(punch-up
2026-08-25: was 42°; the arm's own sweep now runs from +52° to −83° on `y`)*,
because the arc comes from the hips or it comes from nowhere.

## 19. Guard abilities

Ranks are earned, not bought: a guard's Strength decides what they can do, and
Strength is trained by the patrols and fights they survive. See
`entity/GuardRank.java`.

### 19.1 `LEAP_STRIKE` — the sergeant clears the gap *(1.30 s, one-shot)*

Four beats, and it lives or dies on the third. **Coil** (0–0.20 s): a deeper
crouch than before *(punch-up 2026-08-25: torso now folds to −30°, was
−22°)* that accelerates into the bottom, root dipping to −3.4 px (was −2.6),
sword back rather than up, because this is a lunge. **Launch** (0.20–0.30 s):
legs snap straight, root rises to 4.6 px (was 3.4), torso opens to 42° (was
34°) — fast, and allowed to be, because it is bracketed by the coil's hold and
the float. **Float** (0.30–0.50 s): the sword comes overhead and *hangs* four
ticks, root now floating at 5.2 px at the peak (was 3.8) — noticeably higher
hang time reads as a real leap rather than a hop, and it is the moment the
player reads the threat. **Slam** (0.50–0.60 s): down hard to −2.9 px (was
−2.2), with a five-tick beat at the bottom, then a slow rise — nobody springs
back up from that landing.

The damage resolves **when the guard lands**, not when the goal starts, so the
clip is the attack rather than a decoration on one.

## 20. Signature motions — the last five trades

D-016 said every trade gets the one motion nobody else does. §18 left five
trades borrowing a neighbour's: the cook kneaded, the carpenter sawed, the
mason hammered, the fletcher wove, the tanner cleaved. This section ends the
borrowing. **The sound debt is paid too** (2026-08-25): all five now speak
with their own synthesized voices — `pot_stir`, `plane_shave`, `chisel_tap`,
`feather_pinch`, `hide_scrape` — registered in `ModSounds`, pooled with
variants in `sounds.json`, routed by `Employment.soundOf`, and fired at each
clip's own contact tick via `Employment.soundContactOf` (the F8 contract;
`anim_check.py` asserts the pairing).

### 20.1 `COOK_STIR` — the pot *(1.50 s, loop)*

The left hand grips the pot rim and stays; the right traces a slow ellipse
through the stew with a faster sweep at the far side, the way a real stir
accelerates through the thick of it. The head hangs in the steam the whole
loop. Nothing else in the mod moves an arm in a circle — that is the
signature. *(Punch-up 2026-08-25: the ellipse itself roughly doubled — the
arm now swings from −24° to +86° on `y` across the loop instead of −24° to
+26°.)*

### 20.2 `CARPENTER_PLANE` — the push *(1.30 s, loop)*

Both hands on the plane, and the torso commits first — 8° to 53° across the
push *(punch-up 2026-08-25: was 8° to 24°)* with the arms following into
full extension at −160° (was −88°), then a three-tick hold at the end of the
stroke while the shaving clears, then the light drag back. Push fast, return
slow: planing is asymmetric or it is sanding. The root slides 0.7 forward and
back (was 0.35), so the weight visibly travels.

### 20.3 `MASON_CHISEL` — the tap *(1.05 s, loop, impact)*

Left hand seats the chisel and barely moves all clip — precision is the
character. The right raises the mallet through an accelerating three-stage
wind-up (4.5°, 10°, 13.5° per tick, punch-up 2026-08-25 — was 3°, 8°, 12°),
lands a 107°-in-one-tick strike (was 85°), holds a three-tick beat on the
stone, and recovers 18° past rest before settling (was 8°). The chisel hand
takes a two-tick recoil at contact — the blow travels through it. Full
craft-standard impact clip, checked as one.

### 20.4 `FLETCHER_FLETCH` — the feather *(1.60 s, loop)*

An arrow held up to the eye in the left hand; the right makes three small
pinches seating the feathers, each a quick 10–19° press with its own one-tick
hold, on an irregular rhythm (0.35, 0.75, 1.20) so it reads as judgement
rather than metronome. The head tilts in close. The smallest clip in the
set on purpose: fine work reads through stillness, not sweep.

### 20.5 `TANNER_SCRAPE` — the hide *(1.20 s, loop)*

A two-handed scraper drawn hard down the frame — both arms sweep 83° with the
torso rocking 36° into the stroke and the whole root dipping 0.5 with it,
then a two-tick hold at the bottom while the stroke finishes, then the light
lift back up. The heaviest loop of the five: tanning is shoulders, not
fingers.

## 21. Universal punctuation

Owner request 2026-08-25: *"jeg vil at ALLE settlere av enhver type skal ha
en animasjon for å plukke opp ting og legge dem i vesken sin"* — every
settler, any profession, gets this one. It is the village's "I took a thing"
beat: it will fire on harvest pickups, chest withdrawals, and ground pickups.
Wiring those triggers to the clip is a separate piece of work (goal-side,
outside this file); this section only specifies the clip itself.

### 21.1 `PICKUP_STOW` — stoop, grab, and tuck it at the hip *(1.40 s,
    one-shot; REBUILT 2026-08-25)*

Deliberately the light counterpart to `GATHER_LOG`: one arm, not two, and a
snatch rather than a haul — the two must never be confused for each other
even though both stoop deep. Rebuilt after the owner's *"Den må fikses på"*
and the owner-critic's numeric verdict on the first build (no channel
anywhere over 16°/tick — "no accent at all"; CATMULLROM softening both
story beats; the return home faster than the stow it existed to punctuate;
the arm still sweeping ~46° through the final 3 ticks, which snapped on
film when the state expired). §2.4 structure, two beats a viewer can count:
**snatch… stow.**

- **The build, phase by phase (28 ticks):**
  - **Counter-move 0.00–0.10:** the head drops first to lock the item
    (5 → 16 x) while the torso *straightens* (5 → 1) and root rises +0.3 —
    anticipation opposite the stoop.
  - **Reach 0.10–0.50:** dive to torso 58°, knees to −38/+36, the hand
    HOVERING short of the item at arm x −62.
  - **The SNATCH 0.50–0.55:** arm x −62 → −97 in one tick (**35°/tick,
    LINEAR on both keys**) — the grab accent, a heron strike.
  - **Grab hold 0.55–0.70:** 3 ticks LINEAR, every channel within 2°.
  - **Rise 0.70–0.90:** torso unwinds fast, root over-rises +0.7.
  - **The STOW ROLL 0.90–0.95:** wrist turns into the left-hip bag,
    z 8 → 46 (**38°/tick, the fastest motion in the clip**, LINEAR pair);
    the head snaps its glance to the bag in the same tick.
  - **Bag-contact hold 0.95–1.10:** 3 ticks LINEAR, drift ≤ 2.5°.
  - **Release 1.10–1.40:** DECELERATING (16 → 9 → ≤4°/tick), one past-rest
    overshoot key at 1.25–1.30 on every major channel, then a near-still
    settle. Every channel ends exactly on its start value, so the
    `SettlerEntity` expiry (1450 ms) can never cut visible motion.
- **Key values:** grab pose torso (61, 10.5, 3), arm (−97, −18, −8),
  legs −43/+41, root −5.7; tuck pose arm (−30, 40, 46) → (−28.5, 42, 45.5)
  held; torso passes level to (−2.5, −5.5, −1.2) during the tuck; cloak
  peak (29, 0, 4) @0.65 lags the grab by 2 ticks, second flick @1.00 after
  the rise.
- **Accent:** none authored here — a grab/stow sound, if any, is the
  wiring goal's call, not this clip's. The snatch (0.55) and the roll
  (0.95) are the two candidate instants.
- **Carry:** grammar SNATCH — whatever was picked up should render briefly
  in the right hand between the grab (0.55 s) and the tuck (1.10 s), then
  vanish into the bag, the same handoff discipline as `FARM_HARVEST`.

## 22. Trade idles

Owner request, 2026-08-25: *"vil også ha idle animations som matcher
jobben"* — idle animations that match the job. Standing order from the same
owner: *"bare premium er standaren"* (premium is the only standard). Before
this section every settler who was not actively working played the same
generic `IDLE` — a blacksmith idled exactly like a scholar. These fourteen
clips fix that: every employed profession now reads as its trade at a
glance, from across a plaza, with no nameplate. `NONE` keeps the generic
`IDLE` above; the other 21 employed professions map onto the fourteen clips
below.

Each clip **fully replaces** `IDLE` rather than layering on it —
`SettlerEntity.setupAnimationStates()` gates `idleState` to `profession ==
NONE` and gates exactly one trade-idle state on `activity == IDLE &&
profession == <trade>`, mutually exclusive with every other state in the
group, so `SettlerModel` never needs to sum two full-body idle loops on the
same bones (the additive hazard `IDLE`'s own header documents). Every clip
therefore authors its own breath (torso `SCALE`, 1.01–1.03 on `y`) and its
own weight shift rather than borrowing `IDLE`'s.

As with §18/§20, this is one clip per genuinely shared **motion**, not one
per job title where the job titles happen to be adjacent:

| clip | trades | why they share it |
|---|---|---|
| `IDLE_SENTRY` | guard, archer | a lowered weapon and a hand at the belt reads the same whether the tool is a blade or a bow |
| `IDLE_FORGE` | smith, smelter | hands worked raw by forge heat, regardless of whether the tool was a hammer or the bellows |
| `IDLE_BAKER` | baker, miller | the miller grinds the same flour the baker knocks off their hands |
| `IDLE_COOK` | cook, brewer | tasting the batch mid-shift — a spoon at the pot, a ladle at the vat, the same beat |
| `IDLE_SIGHT_EDGE` | mason, carpenter, sawyer | sighting down a straight edge with one eye shut does not care whether the edge is stone or a plank |
| `IDLE_BLADE_BENCH` | butcher, tanner | both work a bench with a blade and empty hands otherwise; the read is testing the edge, not the cut |

The other eight professions (farmer, lumberer, courier, fletcher, miner,
scholar, innkeeper, weaver) get a clip of their own — nothing else in the
roster shares their gesture without forcing it.

**Variation over time.** `SettlerModel` offsets each clip's sampled
`ageInTicks` by a distinct `id % N` — the same scheme `IDLE` and the §18
craft loops already use, valid here because every one of these fourteen is
a **looping** clip (a one-shot offset this way can skip past its own length
on the first evaluated frame — see §17.4 and the file-level note in
`SettlerAnimations.java`). Because each loop runs several breath cycles
before its one signature gesture comes back around, a single settler reads
as breathing and shifting weight far more often than performing the trade
tic, and two settlers of the same trade land at different points in the
loop.

### 22.1 `IDLE_FARMER` — leaning on the hoe *(5.50 s, loop)*

Both hands stay on the hoe shaft in the same two-handed grip `FARM_TILL`
rests in (right arm −64°, left arm −44° on `x`) — leaning into the tool
reads as the same one the settler works with, not a different prop.
Partway through the loop the torso straightens from an 18–20° lean to 7°
and the head snaps from a soft +10° down-look to a −22° up-squint (LINEAR
at 2.75 s, held to 3.55 s) — checking the sky — while root rises +0.35
before settling back to −0.3, leaning into the shaft again.

### 22.2 `IDLE_LUMBERER` — thumbing the edge *(5.00 s, loop)*

The axe stays shouldered the whole loop, elbow locked at (−150°, −20°,
30°) — a held pose, not a swing. The left hand climbs from the side up to
the blade (−99° on `x`) across 2.20–2.60 s, holds there testing the edge
with a thumb to 3.10 s, then eases back down over 1.2 s while the head
tilts to watch it.

### 22.3 `IDLE_SENTRY` — at ease *(5.50 s, loop)*

Complements `GUARD_STANCE`, does not duplicate it: the weapon rests at
−22° on the arm's `x`, well short of `GUARD_STANCE`'s readied −28° to −35°
Pflug, and the off hand drifts out from the hip to the belt (`y` 10° → 22°,
held 3.20–3.80 s) rather than staying locked across the body. One slow
deliberate head scan to −16° at 3.00 s runs opposite phase to
`GUARD_PATROL`'s +17° scan, so a guard passing between post and patrol
never repeats the same beat.

### 22.4 `IDLE_COURIER` — the tally *(4.50 s, loop)*

Empty-handed and relaxed until the right hand rises to the shoulder strap
(`x` −58° → −82°, 1.85–2.15 s reach), then thumbs two quick tally flicks
against the fingers (−79° ↔ −83°, LINEAR pairs, 2.35–2.85 s) before
dropping back to the side over 1.65 s. The head dips to watch the count.

### 22.5 `IDLE_FORGE` — hands worked raw *(5.00 s, loop)*

Both hands flex outward, then wipe slowly down the apron front — the
right leads (arm sweeps to −52° on `x`, 2.20–3.20 s) and the left follows
a quarter-beat behind (to −49°, 2.75–3.60 s) so the two wipes never
mirror. The torso leans in to 17° at the stroke and root drops −0.5,
carrying the weight of it.

### 22.6 `IDLE_BAKER` — flour off the palms *(4.50 s, loop)*

Two sharp claps (arms snapping to −52°/−49° on `x`, LINEAR, 1.95–2.20 s,
torso punching +2° on each beat) knock the dust off, then a single slower
brushing pass down the apron at 3.40 s settles the last of it.

### 22.7 `IDLE_COOK` — the taste-test *(4.50 s, loop)*

The tasting hand climbs from the waist to the mouth across 1.80–2.35 s
(arm to −108° on `x`, torso leaning in to 11°), holds the taste to 2.85 s,
then gives one small judging nod (head 20° → 14° → 17°) before easing back
over 1.3 s.

### 22.8 `IDLE_SIGHT_EDGE` — checking true *(5.00 s, loop)*

The arm extends flat out to the side (−58°, −58°, −26° at 2.20 s) as the
sighting reference while the head cants hard to 32° on `y` to sight along
it, one eye effectively shut by the tilt. Held 2.20–3.30 s, then both fold
back over 1.4 s — one physical check, regardless of the material on the
bench.

### 22.9 `IDLE_FLETCHER` — the twirl *(4.00 s, loop)*

Hands stay close at the chest around an arrow shaft; the right gives two
opposite-direction twirls in one beat (`z` swings 24° → −14° → 6° across
1.85–2.20 s, LINEAR on both snaps) while the torso and head barely move.
The smallest, fastest gesture in the set, on purpose — fine work reads
through a flick, not a sweep.

### 22.10 `IDLE_MINER` — the sore back *(5.00 s, loop)*

The pick stays planted, the right hand steady on the haft. The left climbs
to the small of the back (2.00–2.60 s, arm to −42° on `x`) as the torso
arches from +10° to −6° and the head tips back to −12°, held to 3.30 s,
then the whole stretch releases over 1.1 s.

### 22.11 `IDLE_SCHOLAR` — thumbing a page *(4.50 s, loop)*

The book stays cradled in the near-static right hand (−64° to −66° on
`x`) the whole loop. The left thumbs a page in one quick flick (2.15–2.30
s, arm to −70° then back to −58°) and the head dips into a small
confirming nod (18° → 24° → 17°), as if the line just read settled
something.

### 22.12 `IDLE_INNKEEPER` — polish and check *(4.50 s, loop)*

The left hand polishes a held mug in a slow circular pass with the apron
hem — a genuine loop within the loop, cycling `y` between 14° and 20°
across 0.60–2.90 s — while the right lifts the mug itself to check the
shine at 2.40 s (arm to −46° on `x`) before both settle back.

### 22.13 `IDLE_WEAVER` — testing the twist *(4.00 s, loop)*

Rolls a length of thread between finger and thumb: two small opposite
rolls (right arm `z` swings 16° → −4° → 10° across 1.55–2.00 s), then
lifts it to sight the twist against the light (head tips to 9–10° on `x`,
held 2.05–2.55 s) before returning to the roll.

### 22.14 `IDLE_BLADE_BENCH` — testing the edge *(5.00 s, loop)*

The knife wipes down the apron in one long stroke (2.20–2.45 s, arm to
−74° on `x`), then taps the flat twice against the bench to test it's
true (−58° / −62°, 2.60–2.75 s), torso and root dropping into each tap.

## 23. Raiders

Owner request: *"Enemies skal se unike ut og vaere skumle med syke
animasjonene"* — enemies must look unique and be scary, with sick
animations. Same standing order as §22: *"bare premium er standaren."*
Before this section `RaiderModel` had no `AnimationDefinition` at all — a
raider was a settler-shaped procedural swing wearing a different texture, so
a raid where the villagers moved beautifully and the raiders shambled
generically was backwards. Fear comes from motion.

The rig is the counterpart to §0.1, not the same table: `root, torso, head,
right_arm, left_arm, right_leg, left_leg` — seven bones, no `cloak`
(`RaiderModel.createBodyLayer()` has none). `hood`, `helm` and `pauldron`
are visibility-toggled (captain vs. grunt), never animation targets, the
same rule §0.1 already gives the settler's `hood`/`hat_brim`.

Two builds, one contract (`RaiderEntity.Variant`, committed separately):
**SKIRMISHER** is the pack — lean, hooded, quick — and **BRUTE** is the
door-breaker — fewer, slower, huge. The builds are shaped apart before a
single frame of motion plays: `RaiderModel.setupAnim` scales the BRUTE's
`torso`, `right_arm`/`left_arm`, `head` and `right_leg`/`left_leg` directly
(broader and squashed-flatter chest, longer and thicker arms, a wider low
skull, stockier legs) entirely on `ModelPart` `SCALE` — never `POSITION` or
`ROTATION` — so that persistent shape can never be erased by a one-shot
clearing a bone's motion underneath it, and never fights `animate()`'s
additive rotation channels. A captain of either build stands a few degrees
straighter than its troops (`RaiderModel`, a flat `−0.09` rad correction on
`torso`/`head` after every clip below has run) — confidence is the tell,
the same principle as §4.3's confident-vs-nervous `GUARD_STANCE` split:
same skeleton, a shallower number, not a second pose.

Below the geometry, exactly one locomotion clip is active per state
(`STALK`/`BRUTE_MARCH`/`SPRINT`, mutually exclusive, same reasoning §1
gives `WALK`/`WALK_HURRIED`/`RUN_PANIC`), `MENACE_IDLE` layers on top while
stationary the same way `IDLE` layers under §22's trade idles, and the
three one-shots (`BREACH_SLAM`, `RAIDER_STRIKE`, `LOOT_SNATCH`) reset only
the bones they own — never a full `resetPose()`, which would erase the
BRUTE's persistent scale along with whatever clip came before it.

**`DEATH` is deliberately not in this section.** `RaiderRenderer` (not
authored in this slice) does not override `LivingEntityRenderer`'s vanilla
`setupRotations`, so a dying raider already gets vanilla's own whole-body
Z-axis topple (`deathTime`-driven, up to 90° over roughly the first 0.6 s)
applied at the render level, on top of anything a bone-level clip does. A
real fall authored here would fight that outer roll rather than sell it —
exactly the "if vanilla's death rotation fights you, say so and skip it"
case. Fixing this means `RaiderRenderer` overriding `setupRotations`/
`getFlipDegrees` to hand control to a clip instead; until that lands,
raiders keep vanilla's own death roll.

### 23.1 `STALK` — the pack closes in *(0.80 s, loop)*

SKIRMISHER's walk, and nothing like the settler's upright, loose-armed
`WALK`: the torso holds a heavy 24–28° forward crouch the whole cycle
(against `WALK`'s 3°), `root` sits permanently 1.2 px low, and both hands
stay tucked tight near the belt — a 30° total swing (arm `x` −16° to 14°),
well inside `WALK`'s free 56° arc — with a `z`-axis knife-hand flex riding
the same beat as the 38° leg stride. The head stays low and hunts a 24°
arc side to side rather than tracking level.

### 23.2 `BRUTE_MARCH` — the walk itself is the threat *(1.20 s, loop)*

Slow and ground-eating: a 40° stride on a 1.20 s cycle (half again
`STALK`'s 0.80 s), a shoulder-led torso that twists a full 24°
peak-to-peak (`y`) while holding a 28–32° forward lean, and a heavy
asymmetric footfall — `torso` drops a full pixel on contact (0.30 s) and
rises almost flat by 0.60 s before dropping 1.1 px on the next step.
`root` sits a full 2 px low the whole cycle (against `STALK`'s 1.2), the
BRUTE's low centre of gravity made literal.

### 23.3 `SPRINT` — feral, all-out, arms trailing *(0.60 s, loop)*

SKIRMISHER only, and only while actually closing on a live target
(`RaiderModel`) — never for ordinary travel, which stays `STALK`. A 42°
stride at sprint cadence, arms swept back to 46° and trailing rather than
pumping front-to-back symmetrically (the two arms are never numerically
mirrored), torso lean pushed to 32–34° with a fast 28° twist, and a nearly
full-pixel bob every half-step. The head thrusts forward and stops hunting
— sighted on one thing.

### 23.4 `BREACH_SLAM` — the door-breaking blow *(1.50 s, one-shot)*

BRUTE's signature: a huge wind-up (`right_arm` cocked to −165° by 0.35 s),
a two-tick LINEAR strike that travels 215° in total (−160° to 55°, roughly
108°/tick — solidly in heavy-impact range), a 3-tick LINEAR hold, then a
follow-through overshoot to 58° — 20% past the 15° rest pose, past the
usual 5–15% "controlled" range on purpose, because this is the one blow in
the set that earns it — before an ugly off-balance stagger (`torso` and
both legs lurch the wrong way at 1.25 s) finally settles by 1.50 s. `torso`
leads the arm by three ticks (peak at 0.40 s, ~30% of the arm's own range)
and `root` drops a full 2 px extra at the strike (−1.1 to −3.2). Triggered
from `RaiderBreachGoal` the instant the target actually gives way, so the
scar and this clip's playback start the same tick — the clip's own
internal impact keyframe lands a few ticks into that playback rather than
on tick zero, the same shape as `MELEE`'s own precedent (§17.3) where the
damage tick and the clip's visual accent are not literally identical
either.

### 23.5 `RAIDER_STRIKE` — the wild swing *(0.55 s, one-shot)*

Both builds' ordinary melee attack, and deliberately less disciplined than
the guard's `MELEE` (§4.4) — that contrast is the point. The wind-up
travels further off-axis (`right_arm` to −175° with a 48° `z` twist, against
`MELEE`'s cleaner −168° with none), the two-tick LINEAR strike swings 217°
in total, and recovery does not return cleanly: it overshoots hard into a
−58° sloppy backswing at 0.40 s (15% past the −25° rest pose, against
`MELEE`'s controlled 6%) before easing home. Also plays when a SKIRMISHER
breaches a door or wall (`RaiderBreachGoal`); the BRUTE's own breach gets
`BREACH_SLAM` instead.

### 23.6 `LOOT_SNATCH` — fast grab, look over the shoulder *(0.70 s, one-shot)*

A fast reach into the chest (`right_arm` out to −95° by 0.20 s, LINEAR
into the −100° grip by 0.30 s), then the torso and head whip together into
a hard look-back — head yaw to −50°, held from 0.45 s to 0.60 s and well
past `MENACE_IDLE`'s 42° scan range, the clip's one clearly held pose
(the counter-move/reach/hold/release emote structure the animation-quality
skill specifies) — before releasing back to neutral by 0.70 s. Triggered
from `RaiderLootGoal` the instant a stack actually leaves the chest.

### 23.7 `MENACE_IDLE` — the stationary read *(4.20 s, loop)*

What the player actually watches during the dusk telegraph, so it has to
carry the dread on its own: shoulders roll on an asymmetric, out-of-phase
clock (`right_arm` peaks at 1.10 s, `left_arm` at 1.55 s — never mirrored),
the head hunts a full 42° arc side to side on an irregular timing (0.75 s,
1.60 s, held 2.10 s, 3.00 s, 3.70 s — no even quarters), and the weight
shifts foot to foot on its own ~1.9 s beat, independent of both. Every
raider plays this while stopped and nothing else has claimed the pose —
pack, brute, captain, and the telegraph scout at the treeline alike
(`RaiderModel` gates it on `!moving` alone, no profession- or
variant-specific condition).

## 24. Ring-1 trades — herder, fisher, hunter

TRADES-1 (SURVIVAL_AUDIT.md finding F1): `PASTURE`, `FISHERY` and
`HUNTERS_LODGE` had stood since `BuildingType` was written with no trade
that could ever staff them — buildable, plaquable, `LINKED_VALID`, and
permanently empty, because `Profession` had no shepherd, fisherman or
hunter and `Employment.hire()` refused every attempt with `no_trade`. This
section is these three trades' own signature motions, following §18/§20's
precedent exactly: one bespoke clip per trade's real signature action, and
the trade's OTHER actions (where it has them) reusing an already-authored
motion with the reuse justified in the goal file that plays it
(`HerderWorkGoal`), never in this document alone.

### 24.1 `HERDER_SHEAR` — the shears close *(1.00 s, loop, impact)*

One hand runs the shears through a fast snip — the tightest single-tick
delta of the three (`right_arm` −9° to −82°, 73°/tick, LINEAR both keys) —
while the other hand just holds the fleece taut and barely moves at all
(2° of drift the whole loop): the working hand only reads because the
steadying one holds still (animation-quality principle 11). Torso leads
the snap by two ticks (peak lean at 0.35 s against the arm's 0.45 s
contact), legs shift into the crouch at the same beat, and the recovery
overshoots rest by 7° before easing home. One full loop is one shear pass
(`HerderWorkGoal.SHEAR_DURATION`); the accent (`hide_scrape`, pitched up)
lands at t=0.45 s → tick 9 of 20.

### 24.2 `FISHER_CAST` — the line goes taut *(2.00 s, loop)*

The longest of the three on purpose — fishing is patient work, not a
strike — so 1.30 s of the loop is a slow, continuous watching sway (torso
and head keep drifting the whole time; nothing in this clip is ever
dead-still) before the bite: torso yanks back at 1.35 s, the rod arm snaps
40° in one tick at 1.45 s (LINEAR both keys), holds through the fight for
two ticks, then eases UP past its resting angle — a real angler lifts the
rod tip higher than rest after landing a catch, which is exactly the
overshoot this recovery uses rather than a plain return-to-neutral. One
full loop is one catch (`FisherWorkGoal.FISH_CADENCE`); the accent
(`water_pour`, pitched up) lands at t=1.45 s → tick 29 of 40.

### 24.3 `HUNTER_LOOSE` — the string looses *(1.20 s, loop, impact)*

The bow arm (`left_arm`) is held to 4° of drift for the whole loop — the
stillness principle again, this time on the OFF-hand, because a bow only
reads if the arm holding it never wavers. The draw hand accelerates back to
full extension at the cheek (0.20–0.55 s), holds the aim for two ticks,
then looses at 96°/tick (`right_arm` −136° → −40° in one tick, LINEAR both
keys — heavy-impact velocity) as the hand snaps forward with the released
tension. Torso leads the loose by three ticks (peak twist at 0.55 s against
the arm's 0.70 s release), and the recovery overshoots rest by 5° before
settling. One full loop is one shot (`HunterWorkGoal.HUNT_DURATION`); the
accent (`pick_strike`, a stand-in for a string's own transient — no bow
sound exists in the catalogue yet) lands at t=0.70 s → tick 14 of 24.

### 24.4 `IDLE_FISHER` — watching the water *(4.50 s, loop)*

FISHER is the only one of the three whose idle needed its own clip — a
shepherd's watchful stance over the paddock is genuinely the same
watching-open-ground gesture `IDLE_FARMER` already is (§22.1), and a
hunter's alert scan for game is genuinely the same readiness `IDLE_SENTRY`
already is (§22.3), so both are reused (`SettlerEntity.setupAnimationStates`)
rather than duplicated. A fisher's patience is its own thing: the rod
rests loosely across the shoulder, held about as still as `HUNTER_LOOSE`'s
own off-hand, while partway through the loop the free hand rises to shade
the eyes and the head tips out and down scanning the water, then both ease
back to rest.
