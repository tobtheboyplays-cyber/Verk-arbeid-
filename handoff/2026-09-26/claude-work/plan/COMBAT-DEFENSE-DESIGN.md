# Bannerhold — Combat & Defense design (owner decisions 26 Sep 2026)

## What the owner dislikes in MineColonies / Tektopia
Too passive (guards do everything), boring waves, no meaningful choices.

## Pillars (decided)
- **Win condition:** kill all raiders (clean battlefield). Keep the dawn-retreat/rout only as a fail-safe.
- **Player role:** BOTH commander and fighter.
- **Balance:** preparation and the fight weigh equally — good prep gives an edge, the fight decides.
- **Raid size:** scales with the village: ~5–8 early, 15–25 as a town, more enemy types as you grow.
- **Cadence:** recurring raids every 3–4 in-game days (implemented); frequent non-raid events in between.

## Orders (owner picked)
1. **Hold the line / shield wall** — guards form up shoulder to shoulder at a point and hold.
2. **Charge / follow me** — guards follow the player or rush forward.
3. **Focus target** — point at an enemy (e.g. the captain); all guards engage it.
(Not picked: war-banner rally point — can still be the anchor for "hold the line".)
Delivery idea: a Commander's Horn item (or keybind) with a small radial: Hold / Follow / Charge / Focus.

## Enemy roster (owner picked)
- **Shieldbearers** — block frontal hits; must be flanked, bashed or hit with heavies.
- **Raider archers** — shoot defenders from range; force towers/cover and guard pushes.
- **Torchbearers** — try to set buildings on fire; priority targets (fire spreads, repairs after).
- **Wolves / beasts** — fast, run around the line, harass archers and civilians.
Existing: skirmishers, brutes (heavy club), captains (nemesis memory), goblin thieves (event, not raid).

## Defensive structures (owner picked)
- **Walls & gates** — palisade → stone; guards open/close gates; raiders target weak points (gates, gaps).
- **Towers & arrow slits** — archers get height, cover, range bonus; watchtower already exists.
- **Barricades** — quick temporary barriers placed/built after the raid warning (costs wood; settlers help).

## Already built (26 Sep)
Guard moveset (light/heavy/combo/shield bash/stagger), raider light/heavy, alarm bell, watchtower
archers with ammunition logistics, raid cadence 3–4 days, spawn outside claim, hold notices,
nemesis captains, repairs after raids (nail/chisel), health regen when well fed.

## Proposed build order (after the Sunday build; one slice at a time)
1. **Orders slice**: Commander's Horn + Hold line (shield wall formation) + Follow/Charge + Focus target;
   guards obey then return to posts; clear feedback (banner icons over guards, horn sounds).
2. **Enemy roster slice**: shieldbearer + raider archer (reuse RaiderModel variants, new clips via Blender),
   composition tables scaling with village rank/worth.
3. **Torchbearers + fire**: controlled fire on buildings, extinguish/repair loop, priority targeting.
4. **Walls & gates**: gate block guards operate; raiders path to weak points and bash gates (HP, repair).
5. **Towers & arrow slits**: archer post bonuses, line-of-sight checks.
6. **Barricades**: warning-time build action, material cost, settlers help.
7. **Wolves / beasts**: fast flankers, pack AI.
Each slice: GameTests for exact-once/damage timing, Blender clips, sounds, video before owner review.

## Raid rules (owner decisions 26 Sep, round 2)
- **Timing:** raids can come ANY time of day (after a warning).
- **Warning:** a scout/watchtower spots them ~1 in-game hour ahead; better watch (towers, archers, ranks) = earlier warning.
- **Losing:** warehouse is plundered (goods + Coins), buildings burn (repair loop), settlers die or are CAPTURED.
- **Captured settlers:** rescue expedition to the raider camp (no ransom, no timer chosen).
- **Winning:** loot + Coins from raiders, guard XP/ranks, and **Blessings as a roguelike layer**:
  pick 1 of 3 random blessings after each victory; rarities (common/rare/legendary) with synergies;
  optional curses for stronger blessings (risk/reward). Builds on existing Blessing system
  (WARDEN_OATH, HEARTHWARD, THORNED_ROADS, BlessingSealItem, BlessingScreen).
- **Offline:** when no player is online, NOTHING advances on the server (no raids, no day clock, no
  settlement progress) until someone returns — implement a pause-when-empty for time + settlement logic.

## Guard control (owner decision 26 Sep, after research)
"A very simple menu, a bit like Bannerlord, with dots that show on the ground."
One key (default B) → small non-pausing menu: step 1 who (All / Knights / Archers), step 2 order
(Move here, Follow me, Charge, Hold/Shield wall, Focus target, Return to posts; archers: Take tower, Hold/Fire at will).
Ground-dot formation preview per soldier slot, scroll = line width, click confirms. Default = smart defense.
Research: plan/GUARD-CONTROL-RESEARCH.md. In progress (agent) 01:10.
**UPDATE 26 Sep 01:15 — owner picked Pitch A "To rop"**: R = knights, G = archers, tap = context order by what you
look at (ground → shield line / take position; enemy → charge / focus; wall/tower → archers climb; own feet →
follow me), Shift+key = return to posts, HOLD key = ground-dots formation preview (scroll = width, release =
confirm). "4/6 heard" line. No menu needed; optional tiny B-menu later for rare orders.
