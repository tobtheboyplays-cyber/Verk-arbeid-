# Reference analysis — what TekTopia and MineColonies solved, and how we differ

Written because the owner named exactly what they like in each, and asked
that we build smarter *knowing* what those mods did rather than
rediscovering it. Sources at the end. This is a design input, not a
decision record — decisions land in `DECISIONS.md`.

## 1. Building detection — TekTopia

**How they did it.** A structure is legitimate when it has a wooden door
with an **item frame** mounted above or adjacent to its top, holding a
**structure marker purchased from the Architect**. The interior needs a
flat floor extending to the door base, and a roof.

**What we do, and why it is already better.**

| | TekTopia | Hearthstead |
|---|---|---|
| Marker | item frame + bought token, two blocks to place | one **plaque** block: marker, UI and state in one |
| Type declaration | the token you bought | a **Build Plan** item inserted into the plaque (D-006) |
| Validation | flat floor to door base + roof | capped **flood fill** (`RoomScanner`) — enclosure, beds, doors, lights, furnishing, volume cap |
| Roof test | (unclear/geometry) | **geometric** cover test, deliberately not `canSeeSky` (D-003) — the light engine settles asynchronously and made detection flaky; this was a real bug we already fixed |
| Feedback | marker glows | plaque glows red/amber/green **plus** `Result.missing()`, a player-facing "why isn't this a home yet" string |
| Failure recovery | rescan on interaction | a failed scan is **re-checked 4× at 100-tick spacing** — a room that just missed still registers |

**What to steal:** the *purchase* step. TekTopia's Architect selling
markers gives building types an economic gate and a progression curb. Our
Build Plan is craftable today; making the interesting plans **bought from
an architect** (per the design interview) restores that gate. Already in
the roadmap as the architect's study.

**What not to steal:** the flat-floor-to-door-base requirement. It quietly
forbids split-level and sunken rooms, which punishes exactly the creative
building the mod is supposed to reward. Our volume-capped flood fill
accepts any shape that is genuinely a bounded room.

## 2. The request/logistics chain — MineColonies

**How they did it.** The builder requests **one item sort at a time**, walks
to the site, builds with it, and when the build needs a different material
it does not have, runs back and files another request. If the Warehouse has
the item, a **Courier** brings it. If not, the colony asks whoever can
craft it. If nothing can, the request waits for the **player** to fulfil it
manually. Courier stats matter: Agility = run speed, Adaptability = how
many huts before returning to the Warehouse.

**The failure modes they shipped** — these are the real prize, because we
are building this system right now.

> **Read this column as a claim under test, not a result.** A2a shipped
> #2932 anyway (KF-013): the design in the table was right, the code did
> not implement it, and a fully green suite never noticed because its
> arena had no walls in it. Nothing here counts as avoided until a test
> reproduces that bug's actual shape and is shown to fail without the fix.

| Their bug | Why it happens | How A2a avoids it |
|---|---|---|
| Courier carries **one stack at a time** (#3594) | capacity treated as an afterthought | Capacity is a **designed, visible mechanic** (D-007): the load sack is tier 1, a cart upgrades it. Bag size is the budget, not a magic constant. |
| Deliveryman **won't deliver** requested materials (#2932) | request matching silently fails, nothing surfaces it | **We shipped this bug too, and it took playing the game to find it — see KF-013.** The design above was sound and the code did not implement it: the courier routed to the plaque block instead of to a chest, and stranded its load. What actually prevents it now: delivery targets a real container at a standable cell, arrival requires being *inside* the building's bounds, a failed route rests instead of re-triggering, and an undeliverable load is carried back to the hearth. Locked in by `courierEntersASealedWarehouseAndDelivers`, which builds a genuinely enclosed room — both new tests were verified to fail on the pre-fix code. |
| Courier ↔ builder **circular logic loop** (#5333) | two agents each waiting on the other | A2a deliberately has **one direction only**: hearth → warehouse. No fetch-to-worksite, no builder in the loop. Two-way routing lands only after one-way is proven. |
| Deliveries **stop after a while** (#3892) | a state machine wedges with no watchdog | Every courier GameTest asserts a **completed round trip**, and the acceptance list includes interruption paths explicitly (killed mid-carry, warehouse full, warehouse dissolved, save/reload mid-carry). A wedged state machine fails the suite, not the player's colony. |

**What to steal:** the request abstraction itself (it is genuinely good),
courier stats as a talent axis (Agility→speed, Adaptability→route length —
maps cleanly onto our 1–5★ talents), and "if nobody can supply it, the
**player** is the fallback" — that keeps the mayor in the loop instead of
letting the colony stall silently.

**What not to steal:** requesting one item sort at a time. It is the root
of both the slowness and the loop bugs. Our courier moves what is
**there**, in bag-sized loads, and never blocks on a specific item.

## 3. The builder — the honest answer

The owner likes MineColonies' builder and doubts we can match it.

**Straight answer: we are not going to copy it, and that is a deliberate
design choice, not a capability limit.** The permanent invariant
(`CLAUDE.md`, from the design interview R3) is *settlers never construct
buildings autonomously* — because the whole pillar of this mod is that
**you** are the architect and the village grows into what you built. A
builder that erects schematics turns the player into a resource supplier
for a construction robot, which is precisely the TekTopia-vs-MineColonies
fork we already chose.

**But the *feeling* the builder gives is worth capturing**, and it is
already permitted by the same invariant, which allows settlers to **repair
raid damage and upgrade player-built structures**. So:

> **Proposal — the Wright (a scoped builder).** After a raid, a Wright
> walks the damage, files material requests through the same warehouse/
> courier chain, and **visibly rebuilds the settlement block by block** —
> hauling stone, setting blocks, scaffolding, the lot. Same satisfying
> watch-it-rise loop, same supply chain, but it only ever restores or
> upgrades *your* architecture. It never invents a building.

That gives the builder fantasy, keeps the invariant, and makes raids
matter twice (the damage, then the dugnad). It is A3 work (raids exist
there), not A2a — recorded here so A2a's logistics is built with a second
consumer in mind rather than hard-wired to the hearth→warehouse case.

## 4. Guards and raids — both references, and where we deliberately diverge

**What the owner likes:** both mods' guard systems. **What they reject:**
how *rare* raids are in both. Raids in TekTopia and MineColonies are
occasional interruptions to a fundamentally peaceful builder game; the
village's default state is safe, and defence is a box you tick once.

**Our divergence, stated plainly: threat is the baseline, not the
interruption.** From the design interview the mod is "rough survival with
cozy veins" — raids can raze the settlement to the ground, settlers can be
kidnapped or die, and total destruction is a real outcome. That only means
anything if pressure is *constant enough to shape every decision*.

Design consequences to hold when A3 (the raid vertical) is built:

- **Frequent and escalating**, not a rare event roll. The question a
  player asks should be "are we ready for the next one" as a standing
  concern, not "oh, a raid happened".
- **Telegraphed 1–2 days ahead** (scouts, the bard's unease) — frequent
  *and* fair. Frequency without warning is noise; frequency with warning
  is pressure the player can act on, which is the point.
- **Genuinely hard.** Raiders breach gates (HP, smith-reinforceable), hunt
  settlers rather than mill around, steal from real chests, and commit
  arson. Lighting matters — a dark settlement gets infiltrated.
- **Defence must be maintained, not solved.** Guard equipment wears,
  training and veteran ranks decay without use, walls take damage.
  A defence that stays solved forever recreates the very rarity we are
  rejecting.
- **The reward loop depends on it.** Blessings only drop after surviving
  **raid nights** — the roguelike progression is literally gated on
  frequent combat. Rare raids would starve the mod's own reward economy.
- **Difficulty profiles** (Fredelig / Balansert / Jernvinter) let a player
  who wants the cozy game have it, without the default pretending danger
  is optional.

This is the one place we should be *harsher* than both references, and it
is a deliberate identity choice, not an oversight.

## 5. Complexity per settler — MineColonies

Their citizens are deep: skills, levels, per-job stat weighting, happiness
inputs. Ours is specified deeper still in the design interview (traits with
real AI effects, 1–5★ talents that cap growth, learning by doing, the full
life-wheel). The risk is not ambition, it is **legibility**: MineColonies
players routinely cannot tell *why* a citizen is unhappy or idle.

**Design rule to hold:** every simulated need or trait must be
**explainable in one line in the UI**. If the Tingboka cannot say "Kettil
is unhappy because he slept rough and his workplace is 40 blocks away",
the depth is noise. The `Result.missing()` pattern we already use for
plaques is the model: the system always tells you what it wants.

## Sources

- [TekTopia Wiki](https://sites.google.com/view/tektopia) · [Natural Village Generation](https://sites.google.com/view/tektopia/home/mechanics/natural-village-generation) · [TekTopia mod overview](https://www.9minecraft.net/tektopia-mod/) · [PwrDown guide](https://www.pwrdown.com/minecraft/minecraft-mods/tektopia-mod/)
- [MineColonies — Requests system](https://minecolonies.com/wiki/systems/request/) · [Courier's Hut](https://minecolonies.com/wiki/buildings/deliveryman/)
- Failure modes: [#3594 courier single stack / slow builder](https://github.com/ldtteam/minecolonies/issues/3594) · [#2932 deliveryman won't deliver](https://github.com/ldtteam/minecolonies/issues/2932) · [#5333 courier/builder circular loop](https://github.com/ldtteam/minecolonies/issues/5333) · [#3892 deliveries stop after a while](https://github.com/ldtteam/minecolonies/issues/3892)
