# SHOWCASE_PLAN — the full playthrough film, shot for shot

*Execution checklist for FILM-2 (task #29). Every scene names its driver
commands so filming is mechanical. All takes via showcase.sh/live2.sh, F3+D
before rolling, nameplates ON (no F1). Target: 14-18 clips, 7-9 min total. Scenes 15-18 require FIXWAVE-3 landed.*

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
| 11 | Anim lineup pages 0-4 with nameplates | all 33 poses + W9 punch-up | showcase.sh anim-all |
| 12 | Raid: dusk scout omen → wave led by NAMED captain → guard leap → morning report | A3+saga | pressure forced via data; two cams: gate + plaza |
| 13 | Research desk: lectern screen, start Bedre Gjær, scholar works | W20 | UI capture + study interior 15s |
| 14 | Grounded drops: felled-tree saplings LYING flat | W19 | macro shot 8s |
| 15 | Guard close-up: confident low-guard stance, salute to the captain, rank armor tiers side by side | stance retune + GUARD-2 + ARMOR-VISIBLE | slow orbit 15s; lineup recruit→captain 10s |
| 16 | Farmer bootstrap: empty plot + seeds in chest → till → FIRST planting (FARM_PLANT) → field appears | FARMER-BOOTSTRAP | fixed frame timelapse 40s |
| 17 | Economy in motion: bread baked (fuel burning), courier hauls it to the warehouse, then the FOOD run to a hungry hearth | FUEL-1 + R2 + FOOD-1 | follow-cam one full courier round, 45s |
| 18 | Aftermath: raid scars + the repair dugnad — mason and idle settlers fixing the wall, stone leaving the chest | REPAIR-1 | extends scene 12; morning-after wide 25s |

Post: concat clips (ffmpeg concat demuxer) → showcase.mp4; contact sheet per
clip stays in evidence. Deliver clips 4, 6, 11, 12 individually to the user
as well — they are the requested proof pieces.
