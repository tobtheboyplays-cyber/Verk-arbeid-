# ANIM_SCORECARD — the owner's "lumber camp" pass, 2026-08-26

**UPDATE, same session, live from the owner's play session (overrides the
CHOP verdict below):** *"Vil også ha litt mer vekt på settlerene — de
virker for lette. Så på lumberjacken at det er vekt bak slaget, og slaget
skal komme fra siden som i TekTopia."* Two orders: (1) `CHOP` had to become
an actual lateral/side swing — torso-driven, TekTopia-style — not an
overhead drop with a wide arm sweep; the "PASS, verified not touched"
verdict this document gave `CHOP` below is superseded. (2) A weight pass
across everything scored tonight — settlers read too light in general, and
the owner names the mechanism: root/legs staying still while only the arm
moves. Both are addressed in **"CHOP — side-swing rebuild (owner override)"**
and **"Weight pass"** near the end of this document; the original CHOP
write-up is left in place below for the record of what was actually
checked, with a pointer to the new section.

Owner directive (filmed session, verbatim): *"Du må begynne å sammenligne
animasjoner — våres ser tullete ut i forhold til proffe. Skjerp deg."*
Footage 4:08–4:31 of the lumber camp: *"Animasjoner her må vi fikse på...
Dette ligger ikke."*

Scored analytically against `animation-quality` skill §3 (15-point
checklist) and `blockbench-animation` skill §1 (four-pose walk-cycle
doctrine), directly on the keyframe numbers in `SettlerAnimations.java` at
the exact clip list the owner named, in order. "FAIL" means a §3 check
fails with a measured number; "PASS" means every applicable check clears.
Two passes recorded per fixed clip: **BEFORE** (state at session start,
matching the committed HEAD `git log` shows) and **AFTER** (this session's
fix). Six clips were fixed; the rest were measured and found already
passing (several — `CHOP`, `IDLE_LUMBERER`, `COURIER_LIFT` — were fixed in
earlier sessions per `docs/project/BYGGHERRE_DOM_2_ANIM.md` and this pass
re-verified them rather than re-touching them).

## Worst 5 (6 found, all fixed)

| # | Clip | Defect (measured) | Fix |
|---|------|--------------------|-----|
| 1 | `WALK` | Body-bob dips at the **passing** pose (t=0.25/0.75s) and rises at **contact** (t=0/0.5s) — exactly backwards from real gait (down should follow contact, up should precede the next contact). Two-pose leg cycle (contact+passing only), no down/up poses at all. Measured via pixel-top tracking: BEFORE top-of-head y=63(contact)/69(passing)/64/69/63 — lowest at passing. | Four-pose leg cycle (8 keys/loop), bob retimed to dip at 0.10s/0.60s (2 ticks after contact) and peak at 0.40s/0.90s (before next contact), fall (4 ticks) faster than rise (6 ticks). AFTER: y=65/68/66/64/65/69/66/63/65 — now lowest at DOWN, highest at UP. |
| 2 | `WALK_HURRIED` | Same structural defect, 0.7s cadence. BEFORE dip at passing (0.15s/0.55s). | Same four-pose retime scaled to cadence. AFTER measured: y=62(contact)/66(DOWN)/62(passing)/60(UP), repeating — correct phase. |
| 3 | `GATHER_LOG` | Torso rotation-y peak (52°) and both arms' peak reach (-92°) land on the **same tick**, t=0.35s — zero-tick lead. Checklist item 4 requires torso ≤ (arm extreme − 2 ticks). | Torso now reaches 48° (96% of travel) at t=0.25s, 2 ticks before the arms finish reaching at 0.35s. |
| 4 | `FARM_HARVEST` | Torso twist (y=-32°) and right-arm reach (x=-104°) both peak at t=0.85s — zero-tick lead. Torso twist also 45% over the catalogue's ≤22° typical-peak budget (Sec.0.3). | Torso reaches -20° (91% of travel) at t=0.70s, 3 ticks before the arm's t=0.85s reach; final twist capped at -22°, on budget. Accent frames (0.45s grab, 0.90s stow) untouched. |
| 5 | `COURIER_CARRY` | Its own javadoc claims "total arm travel 3 degrees, a clamp, not a swing," but the arm channel actually swung up to 5° on x — over the catalogue's Sec.0.5 "≤2° jiggle" budget for a locked carry. | Tightened to ≤1° on every axis; comment corrected. |
| 6 (found while fixing #4) | `FARM_PLANT` | Same pattern as #3/#4: torso peak (46°) and the planting hand's press both land on t=0.70s (the clip's own sound accent) — zero-tick lead. | Torso now reaches 44° (96%) at t=0.55s, 3 ticks before the press. Accent frame at 0.70s untouched. |

Three separate clips (`GATHER_LOG`, `FARM_HARVEST`, `FARM_PLANT`) had the
*identical* defect — torso and the working arm peaking on the same tick —
which suggests this was a systemic authoring habit for two-handed
reach/press clips, not three independent mistakes. Worth checking any
future clip of this shape against the same failure.

## Full checklist pass, in the owner's viewing order

### `CHOP` (WORK_CHOP) — lumberer felling swing — **PASS, verified not touched (SUPERSEDED — see below)**
Rebuilt earlier this session (`git log`: "REBUILT 2026-08-25 ... owner:
'Oksa til siden ser fortsatt javlig ut'"), addressing
`BYGGHERRE_DOM_2_ANIM.md`'s six numbered defects. Re-measured against §3
fresh rather than trusting the prior verdict:
- **#1 peak velocity**: right_arm y, 0.50→0.55s: -58°→34° = 92°/tick ≥ 90
  heavy floor. PASS.
- **#2 impact hold**: keys at 0.55/0.60/0.70 = 34/33/31.5° (≤3° over 3
  ticks). PASS.
- **#3 overshoot**: rest y=-25; recovery reaches y=-38 at t=0.85 = 13° past
  rest = 10.8% of the 120° strike travel (5-15% band). PASS.
- **#4 torso lead**: torso y peak 22° at t=0.35s; arm contact t=0.55s = 4
  ticks lead (≥2-3 required). Torso range 36° / arm range 120° = 30% (≥30%
  heavy floor, exactly on the line). PASS.
- **#5 legs**: right_leg x 0.5→0.55: -14.5°→-19° = 4.5° ≥3°. Root drops
  0.55px ≥0.3px. PASS.
- **#6 loop seam**: first/last keys match every channel; slope-in/out
  comparable magnitude, same sign. PASS.
- **#7 interpolation**: LINEAR at 0.5 and 0.55 (contact and the key
  before). PASS.
- **#8 accent**: K=11, P=20, 9-11 ticks from either end. PASS.
Rendered `qa/reports/artifacts/anim-pro/chop_verify.png` (wind-up →
contact → hold → recover, 9 frames): the wind-up visibly accelerates, the
strike lands as a clean silhouette change, the hold is readable, recovery
overshoots before settling. No changes made — the owner's footage almost
certainly predates this same-day rebuild.

### `GATHER_LOG` — lumberjack stooping for the felled log — **FAIL → FIXED**
See Worst-5 #3. Everything else passed: down (0.35s) faster than up
(0.60s, i.e. asymmetric, matching the clip's own javadoc claim); hold at
the bottom (0.35→0.50, ≤2° drift); legs bend 30-34° (well over the 3°
floor); ends back at its own start pose (one-shot, no `ALLOWLIST` entry
needed). Render: `qa/reports/artifacts/anim-pro/gather_log_after.png`.

### `WALK` — **FAIL → FIXED**. See Worst-5 #1.
Render: `qa/reports/artifacts/anim-pro/walk_after.png` (full 9-pose loop)
and `walk_before_after.png` (direct old-vs-new comparison with measured
pixel-y per frame).

### `WALK_HURRIED` — **FAIL → FIXED**. See Worst-5 #2.

### `IDLE` — **PASS**
Root bob at 1.6s(-0.7)/3.6s(-0.5) — deliberately asymmetric depth and
timing (principle 9), not a metronome. Head glance offset from the breath
peak. Cloak has real secondary motion. No swing/contact dynamic to check
against items 1-4.

### `IDLE_LUMBERER` — **PASS**
Right arm (shouldered axe) holds within 2-5° all loop — correct
"stillness sells" contrast (principle 11) against the left arm's real
69°-range blade-test reach. Torso/head/root/cloak staggered on different
sub-beats (1.10/1.30/2.20/2.60s), not simultaneous. Already professional;
not touched.

### `FARM_PLANT` (WORK_PLANT) — **FAIL → FIXED**. See Worst-5 #6.
Render: `qa/reports/artifacts/anim-pro/farm_plant_after.png`.

### `FARM_HARVEST` (WORK_HARVEST) — **FAIL → FIXED**. See Worst-5 #4.
Render: `qa/reports/artifacts/anim-pro/farm_harvest_after.png`.

### `COURIER_LIFT` — **PASS**
Torso peak (34°) at t=0.35s clearly precedes both arm dynamics (deep-reach
extreme at 0.6s, carry-transition extreme at 1.0s) by 5-13 ticks. Grip
hold: root and legs static 0.35-0.6s (5 ticks). Overshoot: torso passes
final rest (-11°) by reaching -16° at t=1.0 = 11% of its 45° travel (5-15%
band). Not touched.

### `COURIER_CARRY` — **FAIL → FIXED**. See Worst-5 #5.
Render: `qa/reports/artifacts/anim-pro/courier_carry_after.png`.

### `COURIER_SET_DOWN` — **PASS**
Mirror of `COURIER_LIFT`'s dynamic (a release/recovery, not a force-into-
contact swing, so the torso-leads-arm check doesn't apply the same way).
In `ENDS_IN_POSE_ALLOWLIST` per its own comment and confirmed by
`anim_check.py` passing with 0 errors. Not touched.

### `CELEBRATE` — **PASS**
Distinct extreme pose (arms to -176°, cloak flying, two small root hops at
LINEAR keys) unlike anything else in the catalogue — passes the
silhouette-divergence spirit of item 15. Two-hop structure with real
LINEAR punctuation on the root position (item 7's spirit applied to a
non-impact accent). This is an emote (§2.4 "counter-move, snap, HOLD,
release"), not a work swing, so the torso-leads-arm rule from §1 principle
5 does not apply the same way it does to `GATHER_LOG`/`FARM_HARVEST`/
`FARM_PLANT`. Not touched.

## Verification

- `tools/hearthstead-qa animation` → **PASS** (8 warnings, all pre-existing
  and unrelated to the six changed clips — confirmed by re-running
  `python3 tools/anim_check.py` directly and reading each warning's clip
  name: `EAT`/`REST`/`CLIMB_LADDER` static-cloak notices, the 40
  not-yet-implemented catalogue clips, and four unrelated pre-swing
  warnings on `FARM_PLANT@0.7`/`FARM_HARVEST@0.45`/`FARM_WATER@0.8`/
  `HAUL_LOG@1.2` whose accent-adjacent CATMULLROM keyframes I did not
  move).
- `tools/hearthstead-qa quick` → **PASS** (build, 875/875 asset checks,
  animation).
- No sound-contract tick moved: `CHOP` (K=11/P=20, LumbererWorkGoal),
  `FARM_PLANT` (t=0.70s), `FARM_HARVEST` (t=0.45s/0.90s) accent frames are
  bit-for-bit unchanged. **No `entity/ai/*.java` goal file needed editing**
  this pass.
- Renders: `hearthstead-neoforge/qa/reports/artifacts/anim-pro/*.png` (see
  each clip's section above). Empirical pixel-top measurement for the two
  walk clips (script: scratch `measure_top.py`, not checked in) confirms
  the bob's phase moved to the correct gait timing, not just the keyframe
  math.
- **Not run this pass** (out of scope / owned elsewhere / no game client
  available to this worker): `tools/hearthstead-qa full`, `live film`.
  `LumbererWorkGoal.java` is mid-edit by another parallel worker tonight;
  none of my six fixes required touching it (no contact tick moved), so no
  coordination was needed.

## CHOP — side-swing rebuild (owner override)

The owner watched the lumberjack live and named the specific mechanism:
*"slaget skal komme fra siden som i TekTopia"* (the strike should come
from the side, like TekTopia) — a horizontal swing driven by torso
rotation around the vertical axis, not an overhead drop. The prior build
(this document's `CHOP` section above) passed every §3 number but was
still, on inspection, an **arm-driven** swing: `right_arm` X (pitch, the
overhead/sagittal plane) was the single largest-travel channel (-55° to
-104°, 49° of range) while Y (yaw, the horizontal plane) did the real work
almost as a side effect. That is exactly backwards from what the owner is
naming.

**Rebuild.** `right_arm`/`left_arm` X is now held in a narrow band
(-70°..-92°) so the axe stays at a roughly constant, roughly-horizontal
height through the whole cycle — it never reads as raised overhead. Y is
now the swing: the wind-up rotates it to +55° (arm drawn back across the
body), the strike snaps it to -72° at contact. `torso` Y (the actual hip
twist that drives the strike) reaches -32° (96% of its post-wind-up
travel) at t=0.45s, 2 ticks before the arm's t=0.55s contact. Root now
shifts **laterally toward the strike side** (+1.0px on the axis the sweep
travels across) in addition to dropping 1.1px, and the legs stagger
**oppositely** — `right_leg` braces back (-24°), `left_leg` plants forward
(+20°) — rather than moving together, selling a real weight transfer
across the stance rather than just down into it.

**Verified visually, not just numerically**, per the owner's own framing
of the defect: rendered from a straight-overhead camera
(`qa/reports/artifacts/anim-pro/chop_topdown.png`) as well as the usual
front-three-quarter view. From directly above, the torso's silhouette
visibly *rotates* across the swing (compare the wind-up frame to the
contact frame — the head/shoulder box is turned a different way) — a
projection a pure overhead/pitch swing cannot produce, since pitching a
limb doesn't change its silhouette's orientation as seen from above. That
is the concrete, camera-level evidence the swing plane actually changed,
not just the keyframe numbers.

**A second, independent bug surfaced by this rebuild and fixed in the same
pass:** `tools/anim_preview.py --strict` (the craft-standard checker,
wired into `tools/hearthstead-qa quick` via `validate_assets.py`) failed
the first draft of this rebuild on two counts:
- **`pop`**: the release after the hold (`0.70s -> 0.75s`, ~30°/tick on
  both arms) had nothing ramping into it and nothing holding after it —
  "motion that arrives from nowhere," the checker's definition of a glitch
  rather than an intentional beat. Fixed by extending the hold one tick
  (0.55/0.60/0.65, still within the 3-4 tick spec) and inserting a
  moderate ramp tick (0.65→0.70→0.75, 13-16°/tick — under the 18°/tick
  strike threshold, so it isn't itself flagged) immediately before the big
  release snap, so the release reads as an accelerating wind-down rather
  than a pop. The `strike -> hold -> ramp -> release` shape is exactly the
  "ramp -> fast" case the checker's own docstring names as ideal.
- **`lead`**: the checker's torso-lead rule specifically reads
  `torso`'s **X channel** (`f[2][0]`, the forward-lean-into-the-strike
  cue), not Y — a hardcoded assumption from when X was always the swing
  axis. My first draft left torso X undifferentiated (peaking exactly at
  the 0.55s contact tick, same as the old failure pattern). Retimed X to
  peak at t=0.50s (one tick before contact) by having the torso's forward
  lean arrive slightly ahead of its own twist — physically sound (weight
  commits forward into the target before the twist snaps through) and not
  a compromise, just an additional real cue layered onto the Y-driven
  swing.

Final state: `python3 tools/anim_preview.py CHOP --strict` → **0 notes**;
`python3 tools/anim_preview.py --strict` (all 63 clips) → **63 of 63 clean**;
`tools/hearthstead-qa animation` and `quick` → **PASS**. I did not touch
`tools/anim_preview.py` or any other checker — the fix is entirely in
`SettlerAnimations.java`'s `CHOP` keyframes, per the file-ownership
constraint. **The sound-accent tick did not move**: contact is still
t=0.55s / tick 11 of the 20-tick loop, so `LumbererWorkGoal`'s modulo
needs no change — no goal-file edit was required or made.

Renders: `qa/reports/artifacts/anim-pro/chop_side_swing_after.png` (8-pose
front-3/4 sequence through wind-up/contact/hold/ramp/release),
`chop_topdown.png` (the straight-overhead silhouette-rotation proof),
`chop_before_after.png` (old overhead-read rebuild vs. the final
side-swing, same two timestamps).

## Weight pass — "de virker for lette"

The owner's second order was general: go back over every clip scored
tonight and check the §1 mass cues specifically — does `root` actually
move at contact (the catalogue's own crate-carry precedent uses 0.7-1.3px
for a load-bearing beat), do legs absorb (≥3° at the moment of exertion),
and is a clip technically passing on the §3 checklist while actually
reading light because root/legs sit still and only the arm animates. Pulled
the real numbers (`root.y` and leg-`x` ranges) straight out of the parsed
keyframes rather than eyeballing:

| clip | root.y span | leg span | read |
|---|---|---|---|
| `CHOP` (after rebuild) | 1.10px | 18°/15° (staggered) | weighted — root now also shifts laterally, see above |
| `GATHER_LOG` | 2.40px | 30°/26° | already heavy — a full-log lift, well over the 0.7-1.3 floor |
| `COURIER_LIFT` | 7.00px | 54°/54° | the best-weighted clip in the set — a full squat-and-lift |
| `COURIER_SET_DOWN` | 6.50px | 48°/48° | heavy — crate lands hard |
| `FARM_HARVEST` | 6.00px | 6°/5° | root does the work (a standing reach-and-pull, legs only brace lightly — correct for the pose) |
| `FARM_PLANT` | 3.00px | **0°/0°** | legs are genuinely static — but this is a kneeling pose (both knees already on the ground); root carries the entire weight cue instead, which is the physically correct place for it. Not a "statue" bug. |
| `CELEBRATE` | 2.50px | 26°/26° | real kicks, real hops — not static |
| `IDLE_LUMBERER` | 0.60px | 3°/3° | small on purpose — a standing idle, not an exertion |
| `IDLE` | 0.70px | **0°/0°** | legs static — but this is passive breathing; a person doesn't shift weight leg-to-leg on every breath. Not the "for lette" smell the owner is naming. |
| `WALK` / `WALK_HURRIED` | 0.30/0.45px (this session's fix) | full 4-pose stride, 70-80° | calibrated for a *light gait*, not a work impact — forcing 0.7-1.3px onto ordinary walking would make every settler visibly stomp. Kept as fixed earlier in this session. |
| `COURIER_CARRY` | 0.30px | (legs owned by `WALK_LADEN`, a separate layered clip) | a continuous loaded walk, not a discrete-contact clip — no single "contact tick" to measure a dip against |

**Finding: only `CHOP` was the real "for lette" culprit**, and not because
root/legs were static — the pre-rebuild `CHOP` already moved root by a
comparable ~0.9-1.1px at contact. The lightness came specifically from the
swing being arm/wrist-driven on the wrong axis (X/overhead) while the
torso's real force-generating rotation (Y) was present but subordinate —
exactly the "arm-driven motion over a statue **plane**," not a statue
*body*. Every other clip already has a real, load-appropriate root/leg
reaction; `FARM_PLANT` and `IDLE`/`IDLE_LUMBERER` have static legs by
design (kneeling and standing-still poses respectively) with root doing
the weight-carrying instead, which is correct, not a bug. No further edits
were made to `GATHER_LOG`, `FARM_HARVEST`, `FARM_PLANT`, `COURIER_LIFT`,
`COURIER_SET_DOWN`, `COURIER_CARRY`, `WALK`, `WALK_HURRIED`, `IDLE`,
`IDLE_LUMBERER`, or `CELEBRATE` as a result of this pass — they were
already correct on the specific mass cues named, and I did not want to
tune numbers with no defect behind the tuning.

**One honest residual noted, not fixed:** `FARM_PLANT`'s `right_arm`
reach (the planting press) covers roughly its full arc at a fairly
constant rate rather than a pronounced slow-out at the very start (arc
coverage ~78% by 79% of the wind-up's duration — close to linear, not
front-loaded). This wasn't part of tonight's torso-lead fix to that clip
and isn't one of the four cues the owner named tonight (root dip, leg
absorption, slow-out, asymmetric timing) — it borders on the third one.
Flagging it rather than opportunistically re-timing an arm channel that
wasn't in scope for either directive tonight.

## Tooling note (blocking, fixed)

`tools/blockbench/export_bbmodel.py` was broken at session start —
`anim_check.parse_definitions(anim_check.SRC)` — `SRC` was removed when
`anim_check.py` was refactored to `ANIMATION_SOURCES` (multi-file support
for the in-flight raider animations, commit `df30f38`, not mine). Fixed by
reading the settler entry's `path` from `ANIMATION_SOURCES` instead; this
is the only non-`SettlerAnimations.java`/non-scorecard file this pass
touched, and was necessary to use the offline renderer at all.
