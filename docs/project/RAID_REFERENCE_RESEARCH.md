# Raid pacing — what the references actually do

Sourced research, gathered because the user's requirement is comparative:
*"liker begge sin vakt system, men raidsene kommer alt for sjeldent så det må
bli mer inngravert å vanskelig i vår mod."* You cannot beat a system you have
only guessed at, so these are real config keys, real defaults and real code,
each with a source. Anything that could not be verified is marked as such and
must not be cited as fact.

## MineColonies — one global timer with a nine-night dead zone

Config category `combat`, from `ServerConfiguration.java` and confirmed in
prose at <https://minecolonies.com/wiki/misc/configfile/>:

| Key | Default | Range |
|---|---|---|
| `dobarbariansspawn` | `true` | bool |
| `barbarianhordedifficulty` | `5` | 0–10 |
| `maxBarbarianSize` | `80` | 6–400 |
| **`minimumnumberofnightsbetweenraids`** | **`10`** | 1–30 |
| **`averagenumberofnightsbetweenraids`** | **`14`** | 1–50 |
| `dobarbariansbreakthroughwalls` | `true` | bool |
| `shouldraiderbreakdoors` | `true` | bool |

`RaidManager` constants: `MIN_RAID_DIFFICULTY 1`, `MAX_RAID_DIFFICULTY 14`,
`INITIAL_RAID_DIFFICULTY 7`, `MIN_DIFFICULTY_MODIFIER 0.2`,
`LOST_CITIZEN_DIFF_REDUCE_PCT 0.15`, `LOST_CITIZEN_DIFF_INCREASE_PCT 0.05`,
`MIN_BUILDING_SPAWN_DIST 35`, `SPAWN_MODIFIER 60`, `MIN_REQUIRED_RAIDLEVEL 75`,
`INCREASE_PER_PLAYER 0.05`.
(<https://github.com/ldtteam/minecolonies/blob/main/src/main/java/com/minecolonies/core/colony/events/raid/RaidManager.java>)

The decision function, verbatim in shape:

```java
if (nightsSinceLastRaid < minimumNights + extraDaysToNextRaid) return false;
if (nightsSinceLastRaid > averageNights + 2)                   return true;
return world.random.nextDouble() < 1.0 / (average - minimum);
```

*Derived (arithmetic, not a source):* at stock 10/14, a raid is **impossible on
nights 1–9**, then 25% per night, and **forced on night 17**. Mean ≈ 12.6
nights. After a raid that killed >15% of the population, `extraDaysToNextRaid
= ceil(14 × 0.4) = 6`, pushing the next window to 16–17 nights **and** lowering
difficulty. Winning adds +1 difficulty but never shortens the interval.

Raid size is a function of the player's own bookkeeping: `getColonyRaidLevel()`
is +5 per adult citizen + skillSum/100, +5 + level²/5 per building, +3 per
completed research, scaled by `currentCitizens / maxCitizens` — and **nothing
happens below raid level 75**. World difficulty is a flat multiplier
(Easy 0.5 / Normal 1.0 / Hard 1.5).

Raids are night-start only. The wiki concedes the escalation is opaque: *"How
quickly they increase in difficulty or what affects their difficulty is not
publicly known."* (<https://minecolonies.com/wiki/systems/raid/>)

## TekTopia — there is no scheduled raid at all

This corrects a common assumption. The official wiki has **no Raid page**. The
entire hostile layer is one probabilistic elite spawn plus vanilla night mobs.

- Necromancer: rolled **every 3600 ticks (3 minutes)**, chance
  `villagers / 10`, **+2 at night**. A 20-villager village = 2% day / 4% night
  per roll. (<https://sites.google.com/view/tektopia/home/visitors/necromancer>)
- *Derived:* ≈6.67 rolls per in-game day → roughly **one Necromancer every ~6
  in-game days** at 20 villagers, and almost never for a young village.
- He is **immune to all damage while controlling minions** (v1.0.68).
- The player can **switch him off entirely** by rotating the Town Hall marker.
- All visitors spawn at **four fixed corners, ~120 blocks** from the Town Hall,
  and fail outright if no path to it exists.
- Nomads are recruitment candidates, not attackers: one pack attempted per day
  at `1/sqrt(villagers)`.

So TekTopia's threat *scales with your safety* — it arrives when you no longer
need to care, and it can be disabled.

## Vanilla and Millénaire — threat paced causally, not by clock

- **Vanilla raids are not time-based.** Bad Omen converts to Raid Omen on
  entering a village; the raid fires **30 seconds** later. Waves: Easy 3,
  Normal 5, Hard 7, +1 at Raid Omen II+. Raid expires after 48000 ticks.
- **Zombie siege** is the useful hybrid: a **10% roll at midnight every single
  night**, gated on the place being a real settlement (claimed beds/bells/job
  sites). Danger is always plausible tonight without being frequent.
- **Pillager patrols**: begin at world age 100 minutes, then a 20% roll every
  ~10–11 minutes, 24–48 blocks from the player.
- **Millénaire** ties raids to *relations between villages* — bad relations
  plus enough fighters produces a raid, and the player can take a side.

## What actually makes them feel rare — the eight things to beat

1. **A single global timer with a long provably-safe dead zone.** Nine tenths
   of MineColonies' calendar is guaranteed safe and players learn it in two
   cycles. Nothing they do moves the date.
2. **The feedback loop points the wrong way.** Losing badly makes the game
   *easier and quieter* (−difficulty, +6 safe nights). The system converges on
   "rare and survivable" regardless of play.
3. **Threat mirrors your stat sheet, not the world.** Raiders are generated
   from your citizen/building/research totals — a dev quote says they should be
   *"similar to guards"* — which is why players report them as HP sponges with
   no identity (*"Chief raiders don't even stand out from regular raiders."*).
4. **Night-gating collapses the surprise surface.** Everything hangs on an
   instant players already ritualise. No dawn raid, no siege that persists into
   day, no multi-night pressure.
5. **One spawn ring, one objective.** MineColonies spawns outside a 35-block
   radius and paths at hut blocks; TekTopia uses four fixed corners. Players
   learn the arrows and wall exactly those.
6. **The escalation curve is invisible**, by the wiki's own admission. A threat
   you cannot read produces annoyance or boredom, never dread.
7. **TekTopia's danger is opt-out and inversely scaled** — off by a marker
   rotation, and rarest exactly when you are most fragile.
8. **Raids leave no scar.** One day of mourning and a chat line. Nothing to
   rebuild, nothing stolen to recover, no captured raiders, no reputation with
   the faction. Feature requests #113 and #129 are both asking for a tail.

## Direct evidence the base systems are felt to be insufficient

The **MineColonies CustomRaids** add-on exists solely to replace the raid
layer, and its feature list reads as a list of what stock raids lack:
multi-wave raids with per-wave mob lists, wave advance conditions, objective
based targeting (guards → civilians → Town Hall), and a boss bar with direction
and wave progress.
(<https://www.curseforge.com/minecraft/mc-mods/minecolonies-customraids>)

Player quotes worth keeping:

- *"They should have a stronger attack strength, considering they are heavily
  armed. Right now they only deal 2 hp per hit."* — minecolonies #11655
- *"as raiders/pirates usually come from the same spawn point they get together
  and own the lonely single guard locked in his tower"* — features #193, which
  also proposes raids triggered by **the player leaving town**.
- *"Once a raid happens it happens again every night, night-after-night, until
  server is restarted."* — #4838, and #9465 for the same night-counting bug.
- TekTopia: *"Guards Sleep through Necro Attack"* (#715), and *"no necro
  attacks at all since update"* (#411).

**Unverified, do not cite:** a claim that TekTopia has "zombie sieges, goblin
attacks and bandit raids" (appears only on a content-farm page, contradicted by
the official wiki); and a forum snippet about "only two raids in over 100 days"
whose source page could not be located.

## What this means for Hearthstead's design

The user wants raids *"mer inngravert"* — more deeply embedded — and harder.
The research says the way to do that is **not** simply a shorter timer, which
would reproduce #4838's every-night misery. The levers that actually change the
feel are:

- **Replace the dead zone with a nightly possibility**, vanilla-siege style: a
  real roll every night, conditioned on the settlement being worth raiding, so
  no night is ever provably safe.
- **Point the feedback loop forward:** a settlement that repels a raid becomes a
  more attractive target, not a safer one. Losing must not buy peace.
- **Give the threat its own agenda** — the Saga captains from the design plan
  are the answer to "raiders mirror your stat sheet". They want specific things
  (grain, the hearth, a named settler) and remember.
- **Vary the approach.** Never one ring, never one hour.
- **Make it leave a scar** — burned buildings to repair, stolen goods in a real
  chest at a real camp, settlers taken. The design plan already calls for all
  three; the research says it is the single biggest differentiator.
- **Telegraph honestly** so escalation is readable, which is the one thing every
  reference fails at and the thing that turns frequency into dread rather than
  noise.
