# ANIM_SCORECARD — the owner's "lumber camp" pass, 2026-08-26

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

### `CHOP` (WORK_CHOP) — lumberer felling swing — **PASS, verified not touched**
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

## Tooling note (blocking, fixed)

`tools/blockbench/export_bbmodel.py` was broken at session start —
`anim_check.parse_definitions(anim_check.SRC)` — `SRC` was removed when
`anim_check.py` was refactored to `ANIMATION_SOURCES` (multi-file support
for the in-flight raider animations, commit `df30f38`, not mine). Fixed by
reading the settler entry's `path` from `ANIMATION_SOURCES` instead; this
is the only non-`SettlerAnimations.java`/non-scorecard file this pass
touched, and was necessary to use the offline renderer at all.
