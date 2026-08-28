# The five numbers: what a settler is made of, how they grow, and what they cost

*"Du må også lage ett helt system hva sjansen for å få høye stats på folk skal
være. Og buffsa de starter med. … Strength, Stamina, Intelligence, et par til du
kan lage. Så lage en sannsynlighet for å få høy av de. **Ingen skal være maks.**
Kanskje 15 av 100 som maks uten å trene en spesifikk ting. Du må også lage et
system hva som øker strength stamina osv. Og hva de ulike buffsa skal være."*
— owner, 2026-08-25.

## Why numbers at all

Two settlers with the same job must not be the same person. Thirty
interchangeable workers is a spreadsheet; a settlement where you know Astrid is
the strong one and Bjørn learns fast is a place. It is also what turns the hire
screen from a list into a decision.

## The five

| attribute | what it is | what it will drive |
|---|---|---|
| **Strength** | force | heavy work speed, carry capacity, melee damage |
| **Stamina** | endurance | energy drain, walking speed, shift length |
| **Wits** | judgement | **the growth rate of every attribute**, craft precision |
| **Dexterity** | hands | fine work — fields, benches, looms, bowstrings |
| **Spirit** | heart | morale resilience, panic threshold, lifting neighbours |

Five, not three: Strength/Stamina/Intelligence were named, and Dexterity and
Spirit were added because without them every crafting trade and every social
building would score off the same number, which is how you get a "best settler"
instead of a right settler for a job.

Wits is the one compounding decision in the system: it multiplies the growth of
everything, itself included. Putting a clever settler in the school early pays
for the rest of their life.

## The roll — nobody starts good

> **A newcomer caps at 15 out of 100.** Not "usually low" — capped.

Everything above 15 must be earned by doing the specific work that trains it, so
a strong settler is evidence of a settlement that handed them axes, not evidence
of a lucky roll.

The distribution is `1 + floor(15 · u^2.2)`:

| | value |
|---|---|
| median | **4** |
| mean | 5.2 |
| P(≥ 10) | 20.8% |
| P(≥ 13) | 9.7% |
| P(= 15) | **3.1%** |

**The exponent is the design, not a tuning constant.** A flat 1–15 roll would
satisfy the cap and still make every newcomer a solid seven; `u^2.2` crushes the
distribution towards the bottom so that most people are ordinary and a good one
is a find. `nobodyArrivesBetterThanFifteen` asserts both the cap *and* that the
mean stays under 7, precisely so a future "small tuning change" cannot quietly
flatten it.

### The knack

Each settler has **one** attribute rolled on `u^1.1` instead — median **7**,
6.1% at the cap. One line of code, and it is what makes every settler arrive as
somebody in particular rather than as a bundle of fours. The hire screen names
it: *"Astrid has a natural hand for Strength."*

## The growth — and nobody ever finishes

```
gain per work unit  =  0.05 · (1 − v/100)²  ·  (1 + wits/200)  ·  traits
```

The squared headroom term is what makes **100 unreachable**: at 90 an attribute
grows a hundredth as fast as at 0. 99 is a hard clamp that nothing gets near.

Starting from 5, with ordinary wits:

| target | work units |
|---|---|
| 25 | ~550 |
| 50 | ~1,800 |
| 70 | ~4,400 |
| 80 | ~7,700 |

A **work unit is one completed action** — a chop landed, a delivery made, a blow
struck — never a tick on a timer. "Learning by doing" is only true if doing is
what gets counted; a settler who gets stronger for standing in the right room is
a settler with a stat that means nothing.

### What raises what

| attribute | trained by |
|---|---|
| Strength | felling, mining, hauling a full sack, blows landed |
| Stamina | long shifts, long walks, patrol laps |
| Wits | schooling, the study and library, multi-step recipes |
| Dexterity | fields, benches, looms, bowstrings |
| Spirit | shared meals, tavern evenings, nights survived, rescues |

The "only trained things pass 15" rule needs no special case: growth only ever
comes from that attribute's own work.

## The buffs they start with — and what each one costs

A settler arrives with **one** trait; one in ten arrives with two. Two is
uncommon on purpose — you should be able to summarise every settler in a
sentence.

> **Every trait has a real trade-off.** A trait that is only an advantage is a
> stat point with a name, and a roster of pure advantages collapses into
> "reroll until you get the good one". `everyTraitCostsSomething` fails the
> build if a future trait forgets this.

| trait | gives | costs |
|---|---|---|
| **Strong back** | carries ×1.25 | walks ×0.92 |
| **Watchful** | spots trouble ×1.30 farther | learns ×0.90 |
| **Early riser** | works from first light | spent by evening |
| **Night owl** | full effect on night watch | sluggish before noon |
| **Green fingers** | things grow for them | eats ×1.15 |
| **Stoic** | morale falls ×0.70 | morale rises ×0.70 |
| **Quick study** | learns ×1.40 | −1 on every attribute at the start |
| **Big eater** | works ×1.15 | eats ×1.40 |
| **Fearful** | flees ×1.15 faster | frightens early |
| **Welcoming** | travellers stay | works ×0.92 |

The good ones trade in a **different currency than they pay in** — the strong
back pays in speed, the quick study pays in where it starts. That is what makes
choosing between two candidates interesting rather than arithmetic.

Multipliers compose by multiplication, so two traits pulling the same way stack
and two pulling against each other cancel. No special cases.

## What is wired, and what is not — honestly

**Wired now:** the roll, the knack, growth with diminishing returns, the trait
table, appetite (`hunger`), morale in both directions (`moraleDecay` /
`moraleGain`), growth rate (`growth`), and **the hire screen's fitness pips**,
which read the real attribute a trade leans on.

**Designed, not yet wired:** carry capacity (`carry`), walking speed (`speed`),
work speed (`work`), spotting radius (`sight`), and the behavioural flags
(night owl, early riser, green fingers, fearful, welcoming). These are wired as
each system lands rather than all at once, because attaching a multiplier to
carry capacity today would break the courier tests that pin exact capacities —
and a number that quietly changes what a test measures is worse than a number
that is not connected yet.

**Not started:** training call sites. `SettlerEntity.train(...)` exists and is
correct; the work goals do not call it yet, so nobody is actually getting
stronger. That is the next slice's first job, and it is one line per completed
action.
