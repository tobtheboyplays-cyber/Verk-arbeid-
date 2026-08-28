# Hiring and dismissal — MineColonies' system, and the six things it gets wrong

*"Vil også ha en emblem kjøper som i tektopia for å gi jobber. Til settlers"* →
*"Rettelse der vil jeg ha heller hire eller fire systemet fra Minecolonies. Lag
den slik og enda bedre."* — owner, 2026-08-25.

So: **the writ is retired and employment becomes hire/fire at the building.**
This document says what that means mechanically, and — as with
`PLAN_DIFFERENTIATION.md` — every "better" below names the actual complaint it
answers. A differentiator that cannot name its problem does not belong here.

---

## 1. The change of shape, and why it is the important part

Today a job is **an item you use on a person**: a writ of trade, TekTopia's
emblem in all but name. The settler carries a `Profession` and nothing connects
them to a room.

MineColonies' shape is the opposite and it is the better one: **you hire a
person into a building, and the building decides the trade.** Every consequence
below falls out of that one change.

> **D-011 — employment is a relationship between a settler and a BUILDING.**
> A settler stores which building employs them. Their profession is *derived*
> from that building's type. There is no second place where a job is written.

Three things this buys immediately, and they are the reason it is worth the
churn:

1. **Twenty-eight buildings do not need twenty-eight writ items**, twenty-eight
   recipes and twenty-eight sprites. The roster stops being an art problem.
2. **It obeys the permanent invariant.** The plaque is the access point, the
   settlement is the truth. Hire/fire is read and written on the settlement;
   the plaque shows it and sends the command, exactly like every other thing it
   displays. It never keeps a resident list — that is written into the
   invariants already.
3. **It closes the loop the player is currently left holding.** Right now the
   flow is *build a room → hang a plaque → it registers → **nothing**.* After
   this it is *build a room → hang a plaque → the plaque says it needs a worker
   → hire one → they walk over and start.* That dead end is the single biggest
   gap in the game's flow today.

## 2. What MineColonies does, honestly

Each hut's GUI has a hire tab. It lists citizens with the two skills that
matter for that hut as numbers, you press Hire, and the citizen is moved. There
is a per-building and per-colony **automatic hiring** toggle. Building level
caps how many workers a hut seats. Children cannot work.

The bones are good. The six problems are all in the flesh.

## 3. The six things we do differently

### 3.1 The list is people, not a column of digits
**Complaint:** at thirty citizens the hire list is a wall of names and numbers,
and you cannot tell who is any good without comparing figures by eye.
**Ours:** each candidate is a card — their name, the post they hold now, how
long they have held it, and how they are doing. The numbers that matter are
drawn as pips, not printed as integers.

### 3.2 Nobody is ever taken from a job silently
**Complaint:** hiring someone who already has a job quietly guts the building
they came from. Nothing tells you the farm now has no farmer; you find out when
the bread stops.
**Ours, and this is the one that matters most:** the hire button *states the
cost before you press it*.

> *Astrid leaves the Farmhouse — it will have no farmer.*

Amber when the old building is left empty, plain when it is not. **A settlement
never loses a trade without saying so in the sentence that takes it away.**

### 3.3 The best candidate explains itself
**Complaint:** MineColonies sorts, and sorting tells you *that* someone is on
top, never *why*.
**Ours:** the suggested hire carries one line of reason — *"unemployed, and the
strongest arms in the settlement"* — because an explanation is a decision and a
sort order is a shrug.

### 3.4 The settlement never moves a person behind your back
**Complaint:** automatic hiring makes choices players cannot predict, which is
why the community advice is to turn it off.
**Ours:** there is **no automatic hiring**. There is a *suggestion* you accept
with one press. The difference sounds small and is not: it is the same promise
as the printed raid odds — the settlement tells you the truth and then you
decide. Nothing in Hearthstead reassigns a person without the player.

> **D-013 — suggestion, never automation.** No system may change a settler's
> employer except a player command. If we ever want auto-staffing it arrives as
> a visible standing order with a name, not as a hidden default.

### 3.5 Dismissal has weight
**Complaint:** firing is a button with no consequence, so managing people feels
like editing a spreadsheet.
**Ours:** a dismissed settler takes a morale hit, keeps the dismissal as a
memory in their saga, and **walks out** — to the tavern, the hearth, wherever
the idle gather. You watch them go. You can rehire them, and they remember both
things. That is the Nemesis principle pointed inward, and it costs almost
nothing to build because the saga store already has to exist.

### 3.6 A dead building cannot own a worker
**Complaint (ours, not theirs):** the failure mode this shape invites is a
settler pointing at a building that no longer exists — the exact class of bug
KF-013 and KF-014 both were.
**Ours:** when a building dissolves, its workers are freed in the same
operation. A settler is employed by a live building or by nothing. This is a
GameTest before it is a feature.

## 4. The rules, stated so they can be tested

Each of these is a GameTest in JOBS-1, and each is meant to be mutation-proven
— break the rule in the code, watch the named test fail.

| rule | test |
|---|---|
| A settler holds at most one job | `noSettlerHoldsTwoPosts` |
| Hiring never exceeds the building's worker capacity | `hiringStopsAtCapacity` |
| Hiring away states which building loses the worker | `takingAWorkerNamesTheLoss` |
| Dissolving a building frees its workers | `aDissolvedBuildingKeepsNoWorkers` |
| Nothing but a player command changes an employer | `nobodyIsReassignedBehindYourBack` |
| A dismissed settler is unemployed, not deleted | `dismissalLeavesThemInTheVillage` |
| Profession always agrees with the employer's type | `professionIsDerivedNeverStored` |

## 5. What is deliberately NOT in this slice

- **Talents and traits (1–5★).** The card is designed with a `Fitness` seam —
  one call returning a score *and the sentence explaining it* — so when talents
  land, the screen does not change, only what the seam returns. Building the
  hire screen against a talent system that does not exist is how you get a UI
  that lies.
- **The settlement-wide employment page.** That is the Tingbok's Work tab. The
  plaque answers "who works *here*"; the Tingbok will answer "who works
  *anywhere*". Per-building first, because the plaque is the access point.
- **Children, schooling and age gates.** The life-wheel is its own slice; until
  it exists every settler is an adult and hireable.

## 6. The flow this produces, end to end

```
build a room ──▶ hang a plaque ──▶ insert a Build Plan ──▶ it registers
                                                              │
                            "Needs a worker" (amber)  ◀────────┘
                                      │
                        click the plaque ──▶ candidate cards
                                      │        · unemployed first
                                      │        · each states its cost
                                      ▼
                          hire ──▶ they walk over ──▶ they work
                                                          │
                                       goods appear in the building's chest
```

Every arrow in that diagram exists in code today except the last three, and
those three are JOBS-1.
