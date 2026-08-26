# SHOWCASE_PLAN — the full playthrough film, shot for shot

*Execution checklist for FILM-2 (task #29). Every scene names its driver
commands so filming is mechanical. All takes via showcase.sh/live2.sh, F3+D
before rolling, nameplates ON (no F1). Target: 16-20 clips, 9-12 min total. Scenes 15-18 require FIXWAVE-3 landed.*

| # | scene | proves | camera & driver |
|---|---|---|---|
| 1 | Cold open: hearth set, founding chime, 3 settlers gather | founding | ground-level 8 blocks back; setblock hearth, film 20s |
| 2 | Plaque flow: hang blank → insert plan → red rows → build room live → rows tick green → seal | D-005/006, PLAQUE-2, W17 art | close on plaque wall; fill room around it while filming 40s |
| 3 | Hire tab: candidate cards, pips, cost sentence → hire → cheer | JOBS-1 UI | client click-path; screenshot set + 15s film |
| 4 | Lumberjack: SIDE-SWING chop, gather stoop, PICKUP_STOW, haul, sapling replant | KF-018 + new anims | tracking shot at tree line, 30s + close-up 10s |
| 5 | Farmer: tended plot, sow arc, harvest, delivery | effort/plot | aerial 12 blocks, 30s |
| 6 | Courier: hearth pickup THROUGH the door, warehouse deposit, tidy | door fix + ledger | doorway framing, 30s |
| 7 | Mayor tab: appoint from hearth screen, boon sentence | W6 | UI capture + settling badge |
| 8 | Settler sheet: right-click → portrait, pips, needs, traits | W1 | UI capture both languages |
| 9 | Summon: People tab button → glow → they come | W16+W12 | wide shot so the glow reads, 20s |
| 10 | Life: meal gathering at hearth (morale jump), night → beds, wake stretch | LIFE-1 | plaza aerial 30s + bedroom cut 10s |
| 11 | Anim lineup pages 0-6 with nameplates | all 47 poses (33 work/haulage/guard/life + 14 trade idles) + W9 punch-up | showcase.sh anim-all |
| 12 | Raid: dusk scout omen → wave led by NAMED captain → guard leap → morning report | A3+saga | pressure forced via data; two cams: gate + plaza |
| 13 | Research desk: lectern screen, start Bedre Gjær, scholar works | W20 | UI capture + study interior 15s |
| 14 | Grounded drops: felled-tree saplings LYING flat | W19 | macro shot 8s |
| 15 | Guard close-up: confident low-guard stance, salute to the captain, rank armor tiers side by side | stance retune + GUARD-2 + ARMOR-VISIBLE | slow orbit 15s; lineup recruit→captain 10s |
| 16 | Farmer bootstrap: empty plot + seeds in chest → till → FIRST planting (FARM_PLANT) → field appears | FARMER-BOOTSTRAP | fixed frame timelapse 40s |
| 17 | Economy in motion: bread baked (fuel burning), courier hauls it to the warehouse, then the FOOD run to a hungry hearth | FUEL-1 + R2 + FOOD-1 | follow-cam one full courier round, 45s |
| 18 | Aftermath: raid scars + the repair dugnad — mason and idle settlers fixing the wall, stone leaving the chest | REPAIR-1 | extends scene 12; morning-after wide 25s |
| 19 | **Follow the arrow** — one camera, one arrow, no cuts: it leaves the watchtower chest → the courier carries it → the archer nocks THAT arrow → Triple Shot into a raider mid-door-breach (the scar already registered before the block broke) → the raider dies → the guard's rank ticks up on the kill → the armour on his BODY upgrades in the same shot | chest truth end to end, ARCHER-1 abilities, raid scars registering before destruction, GuardRank buying from the armoury, ARMOR-VISIBLE | single locked follow-cam from the tower chest to the gate; no cut, no camera change, ~60s. Stage: seed the tower chest with exactly ONE arrow so the one on screen is provably the one fired |
| 20 | **Follow the bread** — the chain closes on itself: field → hearth → warehouse → mill → bakery → warehouse → hearth → a settler's mouth | FLOWS.md is true, not aspirational: every leg is a real courier walking real items | follow-cam handed off leg to leg, ~90s. This is the one scene that proves the economy rather than illustrating it |
| 21 | Trade idles I — frontier & field: farmer leaning on the hoe, lumberjack thumbing the axe edge, miner, courier, guard (sentry), fletcher spinning an arrow, mason sighting down an outstretched arm — nameplated, at rest | 7 of the 14 new profession-matched idle clips (IDLE_FARMER/LUMBERER/MINER/COURIER/SENTRY/FLETCHER/SIGHT_EDGE); each also covers its shared professions (SENTRY: GUARD+ARCHER; SIGHT_EDGE: MASON+CARPENTER+SAWYER) | showcase.sh anim 5 |
| 22 | Trade idles II — hearth & bench: smith (forge), baker, cook, weaver, butcher (blade bench), scholar, innkeeper — nameplated, at rest | remaining 7 of 14 (IDLE_FORGE/BAKER/COOK/WEAVER/BLADE_BENCH/SCHOLAR/INNKEEPER; FORGE also covers SMELTER, BAKER also MILLER, COOK also BREWER, BLADE_BENCH also TANNER); together with scene 21, all 21 employed professions read a distinct idle | showcase.sh anim 6 |

Scene 19 is the critic's own demand (Byggherre-dom #3, "NESTE AMBISJON"),
and it is deliberately the hardest shot in the film: every cut would be a
place to hide a lie, so there are none. Scene 20 is the owner's. Both are
gated on the last courier failures closing — a chain film cannot be shot
while the courier's arrival predicate is under repair.

Post: concat clips (ffmpeg concat demuxer) → showcase.mp4; contact sheet per
clip stays in evidence. Deliver clips 4, 6, 11, 12, 19 and 20 individually to the user
as well — they are the requested proof pieces.
