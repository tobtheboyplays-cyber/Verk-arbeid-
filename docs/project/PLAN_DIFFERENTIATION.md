# Fifteen ways Hearthstead is not TekTopia and not MineColonies

*"Og finn en måte slik vi skiller oss fra de andre. Så alt som er negativt med
reviews fra tektopia og minecolonies, så fikser vi det. Og har en løsning som
skiller oss i en bedre retning. Tenk fra 15 forslag som skiller oss ut."*
— owner, 2026-08-25.

Every item below starts from **a complaint people actually made**, with the
source, and ends with **what we do instead**. Nothing here is a differentiator
invented for its own sake; if a line cannot name the problem it solves, it does
not belong on this list.

Sources are GitHub issues on the two mods, their own wikis, and community
threads — the same research base as `RAID_REFERENCE_RESEARCH.md`.

---

## The complaints, grouped

**MineColonies**
- Builders are *"so slow it's sometimes better just to do it yourself"*
  (issue #3633); builds stall on "waiting for builder" with the builder idle.
- Couriers stand in the warehouse and deliver nothing (#10214); couriers loop
  when the warehouse is upgraded (#9682); at scale a delivery takes 3-4 in-game
  days; one courier holds 15 requests while another holds none (#9199).
- Colonists spam *"I want variety"* even when the food is varied (#10861) —
  a need that nags without being satisfiable.
- Server TPS collapses: 20 → 2-3 with three players (#6366, #2506, #6187);
  a colony visit drops a healthy server to 4-7 TPS (ATM-6 #615).
- The raid curve *"is not publicly known"* (their own wiki), and raids come
  from the same spawn point every time (feature #193); losing >15% of the
  colony *lowers* difficulty and buys quiet nights (#4838, #9465).
- Chief raiders *"don't even stand out"*; raiders mirror your own stat sheet.

**TekTopia**
- Villagers stick in blocks and spin; break the block and they stick somewhere
  else (community #947). They cannot path through water at all (their FAQ).
- Villagers jitter — start, stop, start — on servers (#246).
- The Necromancer's spawn chance is `villagers/10` and can be switched off
  with a marker rotation, so danger arrives exactly when it stops mattering.
- Development stopped; the community fork carries the bugs.

---

## The fifteen

### 1. Settlers never build, so "waiting for the builder" cannot exist
MineColonies' single most-complained-about experience is a builder standing
idle in front of a half-built house. We deleted the category: **the player
builds, the settlement validates.** A plaque surveys the room you actually
made. Nothing is ever half-built by an NPC, so nothing can stall.
*This is already built and shipped (D-005/D-006).*

### 2. Work is proven by evidence, not by a progress bar
Every claim in this repository is backed by a run of `tools/hearthstead-qa`
with a fingerprint. That discipline is why KF-013 — a courier who loaded and
never delivered, MineColonies' #10214 in our own code — was caught **by
playing** rather than shipped.

### 3. The courier carries a visible load, and the load is the mechanic
MineColonies' couriers are a black box: you see a person walking and trust the
request system. Ours carries a **sack that grows**, moves **slower the heavier
it is**, and struggles home. When logistics are wrong you can see it from
across the square, not by reading a request queue.
*Built: A2b.*

### 4. One delivery route, one direction, provably no deadlock
MineColonies #5333 is a two-way request deadlock. `CourierWorkGoal` moves goods
in **one direction only** (source → warehouse), which makes the deadlock
structurally impossible rather than fixed. A failed route **rests and retries**
with a backoff and carries an undeliverable load home, instead of looping.
*Built, and the failure mode is pinned by two GameTests.*

### 5. Every need is satisfiable, and the UI says how
MineColonies' variety complaint nags without a route to satisfaction. Rule
here: **no need may be shown unless the Tingbok can name the exact thing that
fixes it.** "Wants variety" is banned; "has eaten bread nine meals running —
the kitchen can fix this" is the standard.

### 6. Pressure is public, and printed
Their own wiki concedes the raid curve *"is not publicly known"*. Ours prints
the stage, the pressure value and **tonight's percentage** in the Tingbok and
in `/hearthstead info`, in both languages. A threat you can read is a decision;
one you cannot is an annoyance.
*Built: A3.*

### 7. Losing never buys safety
Losing >15% of a MineColonies colony lowers difficulty *and* grants six quiet
nights, so the system converges on quiet however you play. Here **repelling a
raid raises Pressure** and losing lowers it only slightly — you paid in
settlers and goods for that dip, so it is never a strategy.
*Built: A3.*

### 8. No night is provably safe
Nine of MineColonies' fourteen nights are guaranteed safe; TekTopia's threat
can be switched off with a marker. Ours rolls **every night**, gated only on
the settlement being worth attacking, and guarantees only that you never get
two quiet nights in a row at the top stage.
*Built: A3.*

### 9. The enemy is a person, with a name and a memory
Raiders that mirror your stat sheet cannot be characters. Ours are led by a
captain with an earned byname, a record that grows from wins and losses, a
grudge against one **named** settler, and an approach always ≥60° off their
last — so the tower that worked last time will not work twice (their feature
request #193, answered).
*Built: A3.*

### 10. Pathfinding failures are recorded, not endured
TekTopia's villagers stick in blocks and spin. Ours **record every route
failure with a reason string** (`settler.recordRouteFailure`), which is how
KF-013 was diagnosed at all. A settler that cannot reach its job says so, in
the Tingbok, with the reason — instead of jittering in place.

### 11. Budgeted scanning, and a hard MSPT ceiling in CI
MineColonies drops servers from 20 TPS to 2-3. **Every scan here is bounded**
(capped flood fill, round-robin sweeps, no unbounded per-tick work) and the QA
suite fails the build if average MSPT crosses its budget with ~27 settlers.
Performance is a test, not a hope.
*Built: the `performance` suite, budget 45 ms, currently ~1.5 ms.*

### 12. A chain is a multiplier, never a gate
MineColonies gates buildings behind research and behind each other; a new
player builds a lot before anything happens. **D-007:** every building is
useful the day it is finished, alone. The bakery bakes with no mill in the
world.

### 13. Every task has its own animation
Both references reuse one generic work loop, which is why their villages read
as busy rather than alive. Here **every settler task has its own keyframe
clip** — 23 of them so far — and a checker enforces that no clip silently
falls back to walking.
*Built: ANIM-1, enforced by `anim_check.py`.*

### 14. The village is legible in one screen
MineColonies' answer to "why is nothing happening?" is to dig through per-hut
request lists. Ours is **three pools on one page** — food, fuel, metal — plus
the named bottleneck. The player should never have to guess which building is
starving.
*Designed: `PLAN_WORK_AND_CHAINS.md`.*

### 15. It is finished, or it is not claimed
TekTopia was abandoned mid-flight and its community fork inherited the bugs.
The rule here is `docs/project/QUALITY_STANDARD.md`: **only LOCKED means
finished** — compilation is not completion, a green test is not visual quality,
and a working runtime is not sound architecture. Every slice ends with the same
bar: `full` green twice at one fingerprint, plus video of the thing happening.

---

## The one sentence

> **TekTopia gives you a village that lives but cannot be managed.
> MineColonies gives you a colony that can be managed but does not live.
> Hearthstead is a settlement you build with your own hands, that lives
> visibly, and that tells you the truth about itself.**

## What is still only a promise

Honesty about which of the fifteen are shipped and which are design:

- **Built and verified:** 1, 2, 3, 4, 6, 7, 8, 9, 10, 11, 13, 15.
- **Designed, not built:** 5 (needs the needs UI), 12 (needs professions in
  the new buildings), 14 (needs the Tingbok).

Three of fifteen are promises. That ratio is the thing to protect.
