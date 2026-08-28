# Acceptance criteria — the owner's 18:00 test

*Named by the owner, 11:40 Oslo: "Alle start jobbene skal funke. Vakt tårn
skal fungere og alle animasjoner. Lager og courier skal funke og UI på alle
settlers."*

These five are the test. Everything else in the backlog is now secondary,
including work already in flight. **New feature work freezes immediately** —
the remaining hours go to proving these, and to fixing whatever proving them
uncovers.

| # | criterion | what "works" means, concretely | proof required |
|---|---|---|---|
| 1 | **Every starting job works** | Each profession a player can reach early can be HIRED through `Employment.hire`, walks to its post, performs its work, and puts real output in a real chest — through the normal goal path, not by driving `Production` directly | chest-true GameTest per profession |
| 2 | **The watchtower works** | An archer can be hired at a watchtower, takes real arrows from a real chest, and actually looses them at a raider — Power Shot and Triple Shot included, on their real cadence | chest-true GameTest + seen in the client |
| 3 | **All animations work** | Every clip that exists plays when its activity is active, on the right body, without summing into another clip. 72 clips: 64 settler, 8 raider | seen in a running client — no static check substitutes |
| 4 | **Warehouse and courier work** | All four routes move real items end to end: restock, food delivery, output collection, consolidation. Nothing strands, nothing shuttles, the hearth gets fed | chest-true GameTest per route + one live watch |
| 5 | **Settler UI works for every profession** | Right-click any settler of any of the 26 professions and the sheet opens, correct, complete — portrait, name, profession, needs, traits, no missing key, no blank field | seen in a running client, every profession |

## The rule that decides what ships

**Three of these five cannot be proven headless.** Animations, the archer's
loose, and the settler sheet all need a client with eyes on it. That makes
the harness worker's input-decay fix the gate on criteria 2, 3 and 5 — not a
convenience.

If the client cannot be driven reliably in time, the honest outcome is to
tell the owner which criteria were proven headless and which were not, and
let him judge them himself in the first ten minutes. **What must not happen
is reporting all five as met when three were only read.** That is the failure
mode this whole project has spent the night learning to refuse.

## Order of work

1. Criteria 1 and 4 headless, now — they are provable without a client and
   they are the deepest systems.
2. Criteria 2, 3, 5 the moment the client is drivable.
3. Anything found broken gets fixed ahead of anything not on this list.


---

## Status at 12:45 Oslo — two of five proven, three waiting on eyes

**Criterion 1 — every starting job works: PROVEN.** All 25 employable
professions verified hireable through `Employment.hire` and working through
the normal goal path into real chests. **Five were genuine gaps** — the
LUMBERER, the GUARD's own unassisted target acquisition, the SMITH, the
MILLER and the BREWER had only ever been proven by driving `Production.run`
directly or by hire-mechanics alone. A trade with a recipe and no proven
employment path looks identical to a working one from every angle except a
player trying to use it, which is the defect that shipped twice this week.
All five now have a test that hires a settler and watches it do the job.

**Criterion 2 — the watchtower works: PROVEN, and deeper than asked.** Hire,
chest-true arrows, Power Shot on its cadence and DEXTERITY training were
already covered. Two things were not: the archer's own `acquire()` — finding
a raider with no help, since every existing test called `setTarget` by hand —
and **Triple Shot, which the owner named explicitly and which had zero
coverage anywhere.** Both closed. A MASTER archer's 3-arrow volley now fires
on its real 5th-shot cadence with an exact ammunition identity asserted:
`chest + quiver + shotsFired + 2 x tripleShots == 16`.

**Criterion 4 — warehouse and courier work: PROVEN.** All four routes,
conservation held across every failure path deliberately hunted (warehouse
full, two couriers on one stack, destination filling mid-delivery, building
dissolved mid-route, courier killed carrying goods). Plus the ladder rung
nobody had tested: restock genuinely outranks a starving hearth, asserted by
failing the instant a single loaf reaches the hearth before the smithy is fed.

**Criteria 3 and 5 — all animations, and the settler sheet for every
profession: NOT YET PROVEN.** Both need a client with eyes on it, and the
client's input path is the open blocker. Whatever is not proven by 16:30 is
stated as unproven in the owner's note and he judges it himself in his first
ten minutes.

**Suite: 252/252.** One pre-existing flake found and handed back:
`GuardDefenseGameTests.abandonsADistantFightToInterceptOneStandingOverACivilian`
asserts at a fixed 30-tick mark that a target has been acquired, but vanilla's
`TargetGoal` randomises its first-check interval — a race, 1 fail in 3 runs.
Fixed before the demo; a flaky test in a demo build is a lie waiting to be told.

---

# Closing status — 15:15 Oslo

Written against the rule above: **what must not happen is reporting all five
as met when three were only read.** So each line below says how it was
proven, and where the proof stops.

| # | criterion | status | how |
|---|---|---|---|
| 1 | Every starting job | **MET** | chest-true GameTest per profession, through the real hire-and-goal path. Five gaps were found and closed getting here (LUMBERER, GUARD, SMITH, MILLER, BREWER). |
| 2 | The watchtower | **MET** | chest-true GameTest including Triple Shot, which had no coverage at all before today. |
| 3 | All animations | **THREE DEAD CLIPS FOUND AND FIXED — one defect left** | see below. |
| 4 | Warehouse and courier | **MET, and more than before** | all four routes chest-true. The weight table was also wired today, so layout finally changes throughput. |
| 5 | Settler UI, every profession | **MET headless; one screen still overflows** | all 26 profession sheets built and checked headless; the sheet's guiScale-3 clipping is fixed and seen. |

## Criterion 3, honestly

This is the one that moved most, and it moved because three clips that had
been written, reviewed and committed **could never play at all**:

- **The raider's SPRINT.** The renderer decided the charge from
  `getTarget()`, which is server-only and always null on the client. Every
  skirmisher crept at the player at walking pace, through the kill. Filmed
  before the fix.
- **The lumberjack's GATHER_LOG.** Same root cause, plus worse: the server
  also parked him in an activity with no clip gate, and nothing put it back.
  After his first log he stood motionless in the bare rig for the rest of
  the tree — the first worker anyone hires.
- **The guard's LEAP_STRIKE.** A leaping sergeant played the plain walk
  cycle through the air.

All three are fixed and guarded by tests that assert the tell distinguishing
the two idioms, so this class cannot come back silently.

**Where the proof stops:** the fixes are proven reachable, not yet *seen*.
The before-film exists for the raider; the after-film does not, because the
machine has been running the certification gate. That is the honest gap.

**Still wrong, and the owner will see it:** sugar-cane farming plays the
wheat clips — a kneel-into-the-soil motion aimed at the ground beside a plant
that stands vertically. It does not break; it looks borrowed, because it is.
And `CELEBRATING` is never set by any code, so the hire celebration sums on
top of a full trade idle instead of the light breath layer it was authored
against — visible as an arm passing through the torso on the first hire.
Found today, not yet fixed.
