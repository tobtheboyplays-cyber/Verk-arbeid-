# The job standard

*"Lag en standard. Så gå all inn på lumberjack. Slik det er en standard så gjør
vi det samme med alle de andre jobbene vi legger inn."* — owner, 2026-08-25.

A job is not finished when a settler can be hired into it. It is finished when
it meets every point below. **The lumberjack is the reference implementation**:
when something here is ambiguous, look at what the lumberjack does.

This standard is **executable**. `tools/job_audit.py` checks all eleven points
for every trade and fails the build for any trade on the certified list that
has slipped. Adding a trade to that list is how a job is declared done, and it
can never quietly regress afterwards.

---

## The eleven points

### 1. A trade, and a building that practises it
A `Profession` entry, mapped from a `BuildingType` in `Employment.TRADES`. No
free-floating professions: employment is a relationship to a building (D-011).

### 2. Work that exists without anything else being built
Either recipes in `Production` or a gathering/service goal of its own. A
building must be useful the day it is finished, alone (D-007) — a job whose
first day is spent waiting for another building is not a job, it is a
prerequisite.

### 3. A work goal that takes real time
The settler goes to the workplace, works for the time the recipe or task
declares, and the result appears **when the work finishes**. A profession that
teleports its output makes the animation decoration.

### 4. Its own work motion
A keyframe clip meeting the `animation-quality` bar: the wind-up accelerates
rather than drifting, the torso leads the arm, there is a real beat at contact,
and the recovery overshoots past rest before settling. Keyed to the **action**,
not the job title (D-015) — but never a generic work loop.

### 5. Designed in the catalogue before it is implemented
A `### N.M \`CLIP\`` heading in `docs/ANIMATION_CATALOGUE.md`. `anim_check.py`
already refuses any clip that skipped this.

### 6. A distinct sound, on the accent frame
You must be able to tell what somebody is doing with your eyes shut. The sound
plays on the clip's contact tick — not at the start of the action, not when the
item appears — so the sound and the motion agree.

### 7. An outfit you can read across a square
Its own layer in the settler's outfit axis. Eleven crafts in one brown apron is
eleven settlers you cannot tell apart, which is what the outfit layer is for.

### 8. Doing the job makes you better at it
One completed action trains the attribute the trade leans on
(`Employment.trainedBy`). Counted **on completion**, never on a timer:
"learning by doing" is only true if doing is what is counted.

### 9. Somewhere to be at every hour
The trade obeys the village day — it works in working hours, eats at the meal,
sleeps at night, or documents its own exception the way the night watch does.

### 10. Legible in the UI, in both languages
Profession name, work activity and hire card in `en_us` and `nb_no` at full
parity, and the plaque's hire tab shows fitness and the cost of taking them.

### 11. Named tests, and one that would fail if the rule broke
At least one GameTest that drives the job end to end, and the load-bearing rule
mutation-proven at least once — break it in the code, watch the named test
fail. A green test that cannot fail is not evidence.

---

## Certification

A trade is **certified** when all eleven points pass. `tools/job_audit.py`
holds the list and fails on regression, so certification is a ratchet rather
than a claim.

| trade | certified | notes |
|---|---|---|
| lumberer | **yes — the reference** | felling, limbing, hauling; three clips |
| farmer | not yet | needs point 8 |
| courier | not yet | needs point 8 |
| guard | not yet | needs points 2, 8 |
| baker, cook, butcher, smelter, smith, sawyer, carpenter, mason, fletcher, weaver, tanner | not yet | need points 6 and 11 |

## Why this shape

Two failure modes it exists to prevent, both of which are how a village mod
ends up feeling thin:

- **The half-job.** Somebody is hired, they walk somewhere, and nothing else
  about them is finished — no sound, no growth, a borrowed animation. Ten
  half-jobs read far worse than three finished ones, and they are much harder
  to finish later because nobody remembers what was skipped.
- **The silent regression.** A job that was finished stops being finished when
  a refactor drops its sound or its clip. Points that are only written down do
  not survive; points that fail a build do.
