# SLICE A3 — raids that are always plausible tonight

Status: **design proposal.** Needs a PLAN_GATE before any implementation
begins (premium-build-loop). Grounded in `RAID_REFERENCE_RESEARCH.md` — read
that first; every claim about the references here is sourced there.

## The requirement

*"liker begge sin vakt system, men raidsene kommer alt for sjeldent så det må
bli mer inngravert å vanskelig i vår mod."*

The research says the naive reading of that — "shorten the timer" — is a trap.
MineColonies issues #4838 and #9465 are exactly what a short timer produces: a
raid every single night until the server restarts, which players experience as
misery, not tension. The problem with both references is not the number. It is
that **the calendar is knowable**. Nine nights of MineColonies' fourteen are
provably safe, and TekTopia's threat can be switched off with a marker.

So the target is not "more often". It is **never provably safe**, with the
frequency actually rising as the settlement becomes worth attacking.

## The eight decisions

**D-A3-1 — Pressure replaces the timer.** No `nightsSinceLastRaid` floor.
Every night rolls against a single settlement value, **Pressure**, on the
shape of vanilla's zombie siege (a real roll every night, gated on the place
being a real settlement) rather than MineColonies' dead zone. Pressure rises
with what you have built and what you have survived; a young settlement is
genuinely almost safe, a rich one is genuinely never safe.

**D-A3-2 — The feedback loop points forward.** Repelling a raid **raises**
Pressure: you proved the settlement is worth the effort and you kept the
goods. This is the direct inversion of MineColonies, where losing >15% of the
population lowers difficulty *and* buys six extra safe nights, so the system
converges on quiet no matter how you play. Losing here lowers Pressure
slightly — but you lost settlers, goods and buildings to get that, so it is
never a strategy.

**D-A3-3 — Pressure is public.** A named stage in the Tingbok
(*Rolig / Uro / Varsel / Beleiring*), with what raised it. The MineColonies
wiki concedes its own curve is *"not publicly known"*, and an unreadable
threat produces annoyance, not dread. Ours is legible so the player can
*decide* whether to expand now or fortify first — which is the actual game.

**D-A3-4 — Never two nights running below Beleiring.** One hard guarantee, to
avoid the #4838 failure. At the top stage, consecutive nights become possible
and are telegraphed as a siege — a designed crescendo, not a bug.

**D-A3-5 — The threat has an agenda and a name.** Every raid is led by a Saga
captain with a stated **objective**, which determines pathing and win
conditions:

| Objective | What they go for | How you counter it |
|---|---|---|
| **Korn** (grain) | warehouse and hearth, then leave with what they can carry | guard the storage line, not the perimeter |
| **Blod** | hunt settlers wherever they are | get civilians to shelter, hold a line |
| **Brann** (fire) | arson on buildings, spreading | bucket chains, firebreaks, lighting |
| **Løsepenger** (ransom) | seize one *named* settler and withdraw | protect that person specifically |

This is the answer to MineColonies' *"we want them to be similar to guards"*
and to the player complaint that *"chief raiders don't even stand out"*.
Raiders that mirror your own stat sheet cannot be characters. Ones that want a
specific thing can.

**D-A3-6 — The approach varies, and the captain remembers.** The spawn arc is
chosen per raid and **biased away from where you last successfully defended**.
Feature request #193 is a player working out that MineColonies raiders
*"usually come from the same spawn point"* and gang up on one tower guard. A
captain who has been beaten at the north gate does not walk into it twice.

**D-A3-7 — Not only at night.** Night is the default because it is the honest
horror hour, but at Varsel and above a raid can begin at dawn, and a siege can
persist into daylight. Every reference hangs everything on nightfall, an
instant players already ritualise.

**D-A3-8 — It leaves a scar.** The single biggest differentiator the research
found: MineColonies gives one day of mourning and a chat line, and its own
feature requests (#113, #129) are both, at root, asking for consequences that
outlive the fight. Here:

- burned blocks **stay burned** until settlers repair them (settlers repair,
  never build — the permanent invariant),
- stolen goods **physically move** to the captain's camp and can be raided
  back (chest truth means theft is real, not a counter decrement),
- taken settlers **sit in cages** at that camp and can be rescued.

## What this does NOT do

- No enemy-HP inflation. Difficulty profiles (Fredelig / Balansert /
  Jernvinter) scale **the Pressure curve and the objectives**, never raider
  health. #11655 and features #129 are both complaints about HP sponges; the
  fix for "too easy" is smarter and greedier, not spongier.
- No opt-out switch. TekTopia's Town Hall marker rotation disables the
  Necromancer entirely, and his spawn chance is `villagers/10` — so danger
  arrives precisely when it no longer matters. Ours scales the same direction
  but cannot be turned off from inside the fiction.

## Build order

1. **Pressure model + the nightly roll**, headless and fully testable before
   any entity exists. GameTests: no raid below the settlement threshold; the
   never-two-nights guarantee holds; repelling raises Pressure; the value
   survives save/reload.
2. **One faction, one captain, one objective (Korn)** — end to end, because
   the storage line already exists and A2a's warehouse is the natural target.
3. **Telegraph**: scouts as real killable entities, the Tingbok stage, the
   bard's unease. Killing a scout delays the raid *and* raises Pressure — a
   real trade, not a free win.
4. **Guard response**: protect-civilians-first, the horn command wheel.
5. **The scar**: burn state, real theft into a real camp chest, repair dugnad.
6. Remaining objectives, then the second faction.

Each step ends the way every slice here does: `tools/hearthstead-qa full`
green twice at one fingerprint, plus live video of the thing actually
happening. A raid system is exactly the kind of feature that passes a suite
and feels wrong in play, so Level C evidence is mandatory before it is called
done — and KF-013 is the standing reminder of what a green suite is worth when
the arena has no architecture in it.
