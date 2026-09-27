# ElevenLabs credit ledger (sound lane)

Hard cap: 118,000 credits (ask the lead before going past it).

**Total used: 110,679 credits over 1123 generations** (ledger, 40 credits per requested second, TTS labels at 1 per character).

Account's own counter at last read: 55379 of 131000 (it lags and has read lower than the ledger; the ledger is the conservative figure used for the cap).

Output format in use: `pcm_48000` (interleaved stereo, folded to mono in post).

## Per target

| Target | Generations | Credits |
|---|---:|---:|
| `_babble_test_design` | 1 | 400 |
| `_babble_test_sfx` | 1 | 120 |
| `_babble_test_tts` | 1 | 129 |
| `_test/anvil_A_plain` | 2 | 48 |
| `_test/anvil_B_game` | 2 | 48 |
| `_test/anvil_C_foley` | 2 | 48 |
| `_test/anvil_D_mc` | 2 | 48 |
| `_test/chop_A_plain` | 2 | 48 |
| `_test/chop_B_game` | 2 | 48 |
| `_test/chop_C_foley` | 2 | 48 |
| `_test/chop_D_mc` | 2 | 48 |
| `_tts_label` | 60 | 967 |
| `ambient.market_bustle` | 2 | 800 |
| `ambient.night_crickets` | 2 | 800 |
| `ambient.rain_roof` | 2 | 800 |
| `ambient.village_murmur` | 3 | 1,200 |
| `ambient.workshop_smithy` | 2 | 640 |
| `ambient.workshop_wood` | 2 | 640 |
| `anvil_ring` | 11 | 264 |
| `armour_clink` | 5 | 100 |
| `bellows_puff` | 4 | 128 |
| `blade_hit` | 16 | 320 |
| `brawl.grunt` | 4 | 80 |
| `brawl.punch_hit` | 4 | 80 |
| `builder.ladder_rung` | 4 | 80 |
| `builder.place` | 7 | 140 |
| `captain.impact` | 4 | 144 |
| `captain.promoted` | 4 | 480 |
| `captain.rally` | 4 | 400 |
| `captain.windup` | 4 | 128 |
| `cheer` | 7 | 336 |
| `chisel_tap` | 9 | 180 |
| `chop` | 9 | 180 |
| `cleaver_chop` | 5 | 100 |
| `combat.bash_swing` | 6 | 144 |
| `combat.execution_body_fall` | 6 | 192 |
| `combat.execution_double` | 4 | 192 |
| `combat.execution_stinger` | 6 | 288 |
| `combat.execution_stinger.axe` | 4 | 160 |
| `combat.execution_stinger.bare` | 4 | 128 |
| `combat.execution_stinger.mace` | 4 | 160 |
| `combat.execution_stinger.spear` | 4 | 160 |
| `combat.execution_windup` | 6 | 120 |
| `combat.guard_cheer` | 7 | 392 |
| `combat.heavy_impact` | 7 | 196 |
| `combat.swing_heavy` | 7 | 196 |
| `combat.swing_light` | 7 | 140 |
| `command_ack` | 6 | 216 |
| `command_ack.archer` | 4 | 144 |
| `command_ack.mage` | 3 | 120 |
| `command_ack.spear` | 4 | 144 |
| `command_shout` | 6 | 216 |
| `convo.deal` | 4 | 144 |
| `convo.name_card` | 4 | 96 |
| `convo.persuade_fail` | 4 | 128 |
| `convo.persuade_ok` | 4 | 160 |
| `convo.pull_in` | 4 | 128 |
| `crop_pull` | 5 | 100 |
| `dialogue.barter.greet` | 6 | 150 |
| `dialogue.brute_toll.demand` | 4 | 160 |
| `dialogue.brute_toll.demand2` | 4 | 272 |
| `dialogue.brute_toll.talk` | 4 | 180 |
| `dialogue.brute_toll.talk_honest` | 4 | 368 |
| `dialogue.brute_toll.talk_walls` | 4 | 180 |
| `dialogue.caravan.line1` | 6 | 330 |
| `dialogue.caravan.line2` | 6 | 552 |
| `dialogue.demo_peddler.greet` | 4 | 356 |
| `dialogue.demo_peddler.haggle_no` | 4 | 168 |
| `dialogue.demo_peddler.haggle_yes` | 4 | 180 |
| `dialogue.demo_peddler.intro` | 4 | 204 |
| `dialogue.minstrels.line1` | 2 | 184 |
| `dialogue.minstrels.line2` | 2 | 144 |
| `dialogue.parley.bandit.demand` | 2 | 178 |
| `dialogue.parley.bandit.farewell.duel` | 2 | 52 |
| `dialogue.parley.bandit.farewell.tribute` | 2 | 58 |
| `dialogue.parley.bandit.farewell.truce` | 2 | 66 |
| `dialogue.parley.bandit.intro` | 2 | 126 |
| `dialogue.parley.bandit.reply.duel` | 2 | 106 |
| `dialogue.parley.bandit.reply.refuse` | 2 | 26 |
| `dialogue.parley.bandit.reply.tribute` | 2 | 48 |
| `dialogue.parley.bandit.reply.truce_no` | 2 | 72 |
| `dialogue.parley.bandit.reply.truce_yes` | 2 | 92 |
| `dialogue.parley.bandit.reply.warn_no` | 2 | 86 |
| `dialogue.parley.bandit.reply.warn_yes` | 2 | 90 |
| `dialogue.parley.demand` | 2 | 192 |
| `dialogue.parley.demand.reaper` | 2 | 192 |
| `dialogue.parley.demand.red` | 2 | 192 |
| `dialogue.parley.demand.torch` | 2 | 192 |
| `dialogue.parley.farewell.duel` | 2 | 44 |
| `dialogue.parley.farewell.duel.reaper` | 2 | 44 |
| `dialogue.parley.farewell.duel.red` | 2 | 44 |
| `dialogue.parley.farewell.duel.torch` | 2 | 44 |
| `dialogue.parley.farewell.tribute` | 2 | 68 |
| `dialogue.parley.farewell.tribute.reaper` | 2 | 68 |
| `dialogue.parley.farewell.tribute.red` | 2 | 68 |
| `dialogue.parley.farewell.tribute.torch` | 2 | 68 |
| `dialogue.parley.farewell.truce` | 2 | 90 |
| `dialogue.parley.farewell.truce.reaper` | 2 | 90 |
| `dialogue.parley.farewell.truce.red` | 2 | 90 |
| `dialogue.parley.farewell.truce.torch` | 2 | 90 |
| `dialogue.parley.intro` | 2 | 86 |
| `dialogue.parley.intro.reaper` | 2 | 86 |
| `dialogue.parley.intro.red` | 2 | 86 |
| `dialogue.parley.intro.torch` | 2 | 86 |
| `dialogue.parley.reply.duel` | 2 | 120 |
| `dialogue.parley.reply.duel.reaper` | 2 | 120 |
| `dialogue.parley.reply.duel.red` | 2 | 120 |
| `dialogue.parley.reply.duel.torch` | 2 | 120 |
| `dialogue.parley.reply.refuse` | 2 | 18 |
| `dialogue.parley.reply.refuse.reaper` | 2 | 18 |
| `dialogue.parley.reply.refuse.red` | 2 | 18 |
| `dialogue.parley.reply.refuse.torch` | 2 | 18 |
| `dialogue.parley.reply.tribute` | 2 | 98 |
| `dialogue.parley.reply.tribute.reaper` | 2 | 98 |
| `dialogue.parley.reply.tribute.red` | 2 | 98 |
| `dialogue.parley.reply.tribute.torch` | 2 | 98 |
| `dialogue.parley.reply.truce_no` | 2 | 68 |
| `dialogue.parley.reply.truce_no.reaper` | 2 | 68 |
| `dialogue.parley.reply.truce_no.red` | 2 | 68 |
| `dialogue.parley.reply.truce_no.torch` | 2 | 68 |
| `dialogue.parley.reply.truce_yes` | 2 | 104 |
| `dialogue.parley.reply.truce_yes.reaper` | 2 | 104 |
| `dialogue.parley.reply.truce_yes.red` | 2 | 104 |
| `dialogue.parley.reply.truce_yes.torch` | 2 | 104 |
| `dialogue.parley.reply.warn_no` | 2 | 66 |
| `dialogue.parley.reply.warn_no.reaper` | 2 | 66 |
| `dialogue.parley.reply.warn_no.red` | 2 | 66 |
| `dialogue.parley.reply.warn_no.torch` | 2 | 66 |
| `dialogue.parley.reply.warn_yes` | 2 | 108 |
| `dialogue.parley.reply.warn_yes.reaper` | 2 | 108 |
| `dialogue.parley.reply.warn_yes.red` | 2 | 108 |
| `dialogue.parley.reply.warn_yes.torch` | 2 | 108 |
| `dialogue.peddler.line1` | 6 | 372 |
| `dialogue.refugees.line1` | 4 | 220 |
| `dialogue.refugees.line2` | 4 | 232 |
| `dialogue.refugees.work` | 4 | 148 |
| `dialogue.rival_envoy.intro` | 2 | 40 |
| `dialogue.rival_envoy.line1` | 2 | 164 |
| `dialogue.rival_envoy.line2` | 2 | 62 |
| `dialogue.traveller.bye` | 6 | 234 |
| `dialogue.traveller.greet` | 6 | 420 |
| `dialogue.traveller.intro` | 6 | 288 |
| `dialogue.traveller.news` | 6 | 462 |
| `dialogue.traveller.shrug` | 6 | 156 |
| `dialogue.traveller.thanks` | 6 | 282 |
| `dialogue.voice_design.captain_reaper` | 1 | 147 |
| `dialogue.voice_design.captain_red` | 1 | 147 |
| `dialogue.voice_design.captain_torch` | 1 | 147 |
| `dialogue.voice_design.caravan` | 2 | 294 |
| `dialogue.voice_design.envoy` | 1 | 147 |
| `dialogue.voice_design.merchant` | 1 | 147 |
| `dialogue.voice_design.minstrel` | 1 | 147 |
| `dialogue.voice_design.peddler` | 2 | 294 |
| `downed_alert` | 6 | 216 |
| `downed_heartbeat` | 7 | 168 |
| `downed_revived` | 4 | 192 |
| `drunk.groan` | 4 | 128 |
| `drunk.scuff` | 4 | 80 |
| `drunk.thud` | 4 | 96 |
| `event.boar_charge` | 4 | 160 |
| `event.boar_grunt` | 4 | 128 |
| `event.brute_demand` | 4 | 256 |
| `event.brute_grunt` | 4 | 160 |
| `event.caravan_arrive` | 4 | 480 |
| `event.dog_bark` | 5 | 100 |
| `event.dog_whine` | 4 | 128 |
| `event.envoy_fanfare` | 4 | 480 |
| `event.fox_yip` | 3 | 72 |
| `event.minstrel_sting` | 4 | 480 |
| `event.peddler_bells` | 4 | 320 |
| `event.wolf_howl` | 4 | 480 |
| `farmer_work` | 6 | 120 |
| `feather_pinch` | 4 | 80 |
| `fx.build_done` | 4 | 240 |
| `fx.building_level_up` | 4 | 400 |
| `fx.coin_sale` | 7 | 196 |
| `fx.craft_glint` | 6 | 192 |
| `fx.craft_legendary` | 4 | 320 |
| `fx.journey_chapter` | 4 | 400 |
| `fx.order_confirmed` | 4 | 96 |
| `fx.patrol_waypoint` | 4 | 96 |
| `fx.raid_won` | 6 | 288 |
| `fx.skill_level_up` | 4 | 160 |
| `fx.summon_arrival` | 4 | 192 |
| `fx.tech_learned` | 4 | 320 |
| `fx.warehouse_level_up` | 3 | 240 |
| `guard_alert` | 6 | 528 |
| `guard_experience` | 6 | 120 |
| `hide_scrape` | 4 | 112 |
| `knead_press` | 4 | 96 |
| `leap_slam` | 6 | 192 |
| `loom_clack` | 5 | 100 |
| `mug_set` | 7 | 168 |
| `music.day_banner` | 2 | 4,500 |
| `music.day_harvest` | 2 | 4,200 |
| `music.day_hearth` | 2 | 4,500 |
| `music.day_meadow` | 2 | 4,500 |
| `music.night_embers` | 2 | 4,500 |
| `music.night_watch` | 2 | 4,200 |
| `music.raid` | 2 | 3,600 |
| `music.raid_defeat` | 2 | 600 |
| `music.raid_victory` | 2 | 300 |
| `music.tavern_jig` | 2 | 2,700 |
| `music.title` | 2 | 4,500 |
| `nail_tap` | 9 | 180 |
| `oven_slide` | 4 | 128 |
| `patrol.halt` | 4 | 128 |
| `patrol.march` | 4 | 192 |
| `pick_strike` | 9 | 180 |
| `plane_shave` | 5 | 140 |
| `pot_stir` | 4 | 160 |
| `profession_assigned` | 6 | 240 |
| `raid.lost_toll` | 4 | 640 |
| `raid.won_fanfare` | 4 | 480 |
| `raid_horn` | 4 | 800 |
| `raider.bark` | 7 | 224 |
| `raider.brute_roar` | 6 | 360 |
| `raider.brute_slam` | 6 | 240 |
| `raider.death` | 4 | 160 |
| `raider.hurt` | 5 | 100 |
| `role.bandage` | 4 | 128 |
| `role.firebolt_impact` | 4 | 160 |
| `role.frost_rune` | 4 | 160 |
| `role.longsword_cleave` | 4 | 112 |
| `role.rune_cast` | 4 | 192 |
| `role.spear_thrust` | 4 | 80 |
| `role.ward_up` | 4 | 192 |
| `saw_stroke` | 5 | 200 |
| `seed_press` | 4 | 80 |
| `settlement_founded` | 4 | 560 |
| `settler.death` | 3 | 108 |
| `settler.hurt` | 5 | 100 |
| `settler_recruited` | 4 | 352 |
| `shield_thud` | 9 | 180 |
| `summon.horn` | 4 | 320 |
| `tavern.bar_creak` | 4 | 96 |
| `tavern.burp` | 4 | 96 |
| `tavern.chuckle` | 4 | 128 |
| `tavern.clink` | 7 | 224 |
| `tavern.cloth_rustle` | 4 | 80 |
| `tavern.drink` | 4 | 112 |
| `tavern.heh` | 4 | 80 |
| `tavern.hiccup` | 5 | 100 |
| `tavern.jig_step` | 5 | 100 |
| `tavern.pour` | 6 | 312 |
| `tavern.seat_creak` | 4 | 96 |
| `tavern.table_slap` | 4 | 80 |
| `tavern.tap_valve` | 4 | 80 |
| `tavern_ambience` | 4 | 1,920 |
| `tavern_fire` | 4 | 560 |
| `tavern_laugh` | 7 | 700 |
| `ui.click` | 6 | 120 |
| `ui.conversation_open` | 6 | 216 |
| `ui.map_ping` | 4 | 96 |
| `ui.page_turn` | 7 | 140 |
| `ui_close` | 4 | 80 |
| `ui_confirm` | 6 | 120 |
| `ui_error` | 4 | 80 |
| `ui_open` | 6 | 120 |
| `village_bell` | 4 | 640 |
| `voice.brute (design)` | 3 | 2,400 |
| `voice.captain (design)` | 2 | 1,400 |
| `voice.envoy (design)` | 2 | 1,400 |
| `voice.f_adult (design)` | 2 | 1,400 |
| `voice.f_old (design)` | 2 | 1,400 |
| `voice.f_young (design)` | 2 | 1,400 |
| `voice.goblin (design)` | 2 | 1,400 |
| `voice.m_adult (design)` | 2 | 1,400 |
| `voice.m_old (design)` | 2 | 1,400 |
| `voice.m_young (design)` | 2 | 1,400 |
| `voice.minstrel (design)` | 3 | 2,400 |
| `voice.peddler (design)` | 4 | 3,400 |
| `voice_pilot.brute` | 1 | 128 |
| `voice_pilot.captain` | 1 | 103 |
| `voice_pilot.old_man` | 1 | 146 |
| `voice_pilot.traveller` | 1 | 131 |
| `voice_pilot.young_woman` | 1 | 149 |
| `water_pour` | 4 | 160 |
| `whetstone_scrape` | 5 | 100 |
| `work.bar_wipe` | 4 | 96 |
| `work.bow_loose` | 4 | 80 |
| `work.fish_splash` | 4 | 128 |
| `work.ledger_tally` | 4 | 96 |
| `work.mash_stir` | 4 | 144 |
| `work.pestle_grind` | 4 | 112 |
| `work.plate_hammer` | 5 | 100 |
| `work.quern_grind` | 4 | 144 |
| `work.quill_scratch` | 5 | 120 |
| `work.shear_snip` | 5 | 100 |

## Log (newest last)

| Time | Target | Cand | Dur (s) | Credits | Running |
|---|---|---:|---:|---:|---:|
| 09-26 11:13:40 | `fx.coin_sale` | 0 | 0.7 | 28 | 28 |
| 09-26 11:13:48 | `combat.swing_light` | 0 | 0.5 | 20 | 48 |
| 09-26 11:13:52 | `combat.swing_light` | 1 | 0.5 | 20 | 68 |
| 09-26 11:13:55 | `combat.swing_light` | 2 | 0.5 | 20 | 88 |
| 09-26 11:13:59 | `combat.swing_light` | 3 | 0.5 | 20 | 108 |
| 09-26 11:14:02 | `combat.swing_light` | 4 | 0.5 | 20 | 128 |
| 09-26 11:14:07 | `combat.swing_light` | 5 | 0.5 | 20 | 148 |
| 09-26 11:14:09 | `combat.swing_light` | 6 | 0.5 | 20 | 168 |
| 09-26 11:14:11 | `combat.swing_heavy` | 0 | 0.7 | 28 | 196 |
| 09-26 11:14:13 | `combat.swing_heavy` | 1 | 0.7 | 28 | 224 |
| 09-26 11:14:15 | `combat.swing_heavy` | 2 | 0.7 | 28 | 252 |
| 09-26 11:14:18 | `combat.swing_heavy` | 3 | 0.7 | 28 | 280 |
| 09-26 11:14:21 | `combat.swing_heavy` | 4 | 0.7 | 28 | 308 |
| 09-26 11:14:23 | `combat.swing_heavy` | 5 | 0.7 | 28 | 336 |
| 09-26 11:14:25 | `combat.swing_heavy` | 6 | 0.7 | 28 | 364 |
| 09-26 11:14:27 | `blade_hit` | 0 | 0.5 | 20 | 384 |
| 09-26 11:14:31 | `blade_hit` | 1 | 0.5 | 20 | 404 |
| 09-26 11:14:33 | `blade_hit` | 2 | 0.5 | 20 | 424 |
| 09-26 11:14:35 | `blade_hit` | 3 | 0.5 | 20 | 444 |
| 09-26 11:14:37 | `blade_hit` | 4 | 0.5 | 20 | 464 |
| 09-26 11:14:39 | `blade_hit` | 5 | 0.5 | 20 | 484 |
| 09-26 11:14:41 | `blade_hit` | 6 | 0.5 | 20 | 504 |
| 09-26 11:14:44 | `blade_hit` | 7 | 0.5 | 20 | 524 |
| 09-26 11:14:47 | `blade_hit` | 8 | 0.5 | 20 | 544 |
| 09-26 11:14:49 | `combat.heavy_impact` | 0 | 0.7 | 28 | 572 |
| 09-26 11:14:51 | `combat.heavy_impact` | 1 | 0.7 | 28 | 600 |
| 09-26 11:14:54 | `combat.heavy_impact` | 2 | 0.7 | 28 | 628 |
| 09-26 11:14:57 | `combat.heavy_impact` | 3 | 0.7 | 28 | 656 |
| 09-26 11:14:59 | `combat.heavy_impact` | 4 | 0.7 | 28 | 684 |
| 09-26 11:16:34 | `_test/anvil_A_plain` | 0 | 0.6 | 24 | 708 |
| 09-26 11:16:34 | `_test/anvil_A_plain` | 1 | 0.6 | 24 | 732 |
| 09-26 11:16:35 | `_test/anvil_B_game` | 1 | 0.6 | 24 | 756 |
| 09-26 11:16:35 | `_test/anvil_B_game` | 0 | 0.6 | 24 | 780 |
| 09-26 11:16:36 | `_test/anvil_D_mc` | 0 | 0.6 | 24 | 804 |
| 09-26 11:16:36 | `_test/anvil_C_foley` | 1 | 0.6 | 24 | 828 |
| 09-26 11:16:36 | `_test/anvil_C_foley` | 0 | 0.6 | 24 | 852 |
| 09-26 11:16:37 | `_test/anvil_D_mc` | 1 | 0.6 | 24 | 876 |
| 09-26 11:16:38 | `_test/chop_A_plain` | 0 | 0.6 | 24 | 900 |
| 09-26 11:16:38 | `_test/chop_A_plain` | 1 | 0.6 | 24 | 924 |
| 09-26 11:16:39 | `_test/chop_B_game` | 0 | 0.6 | 24 | 948 |
| 09-26 11:16:39 | `_test/chop_B_game` | 1 | 0.6 | 24 | 972 |
| 09-26 11:16:40 | `_test/chop_C_foley` | 1 | 0.6 | 24 | 996 |
| 09-26 11:16:40 | `_test/chop_C_foley` | 0 | 0.6 | 24 | 1,020 |
| 09-26 11:16:40 | `_test/chop_D_mc` | 0 | 0.6 | 24 | 1,044 |
| 09-26 11:16:41 | `_test/chop_D_mc` | 1 | 0.6 | 24 | 1,068 |
| 09-26 11:18:40 | `ui.click` | 0 | 0.5 | 20 | 1,088 |
| 09-26 11:18:40 | `ui.click` | 1 | 0.5 | 20 | 1,108 |
| 09-26 11:18:40 | `ui.click` | 3 | 0.5 | 20 | 1,128 |
| 09-26 11:18:40 | `ui.click` | 2 | 0.5 | 20 | 1,148 |
| 09-26 11:18:41 | `ui.click` | 4 | 0.5 | 20 | 1,168 |
| 09-26 11:18:42 | `fx.skill_level_up` | 0 | 1.0 | 40 | 1,208 |
| 09-26 11:18:42 | `fx.skill_level_up` | 1 | 1.0 | 40 | 1,248 |
| 09-26 11:18:42 | `ui.click` | 5 | 0.5 | 20 | 1,268 |
| 09-26 11:18:43 | `fx.skill_level_up` | 2 | 1.0 | 40 | 1,308 |
| 09-26 11:18:43 | `fx.skill_level_up` | 3 | 1.0 | 40 | 1,348 |
| 09-26 11:18:44 | `tavern.clink` | 0 | 0.8 | 32 | 1,380 |
| 09-26 11:18:44 | `tavern.clink` | 1 | 0.8 | 32 | 1,412 |
| 09-26 11:18:44 | `tavern.clink` | 2 | 0.8 | 32 | 1,444 |
| 09-26 11:18:45 | `tavern.clink` | 3 | 0.8 | 32 | 1,476 |
| 09-26 11:18:45 | `tavern.clink` | 4 | 0.8 | 32 | 1,508 |
| 09-26 11:18:45 | `tavern.clink` | 5 | 0.8 | 32 | 1,540 |
| 09-26 11:18:46 | `tavern.clink` | 6 | 0.8 | 32 | 1,572 |
| 09-26 11:18:47 | `anvil_ring` | 1 | 0.6 | 24 | 1,596 |
| 09-26 11:18:47 | `anvil_ring` | 0 | 0.6 | 24 | 1,620 |
| 09-26 11:18:47 | `anvil_ring` | 2 | 0.6 | 24 | 1,644 |
| 09-26 11:18:48 | `anvil_ring` | 3 | 0.6 | 24 | 1,668 |
| 09-26 11:18:48 | `anvil_ring` | 4 | 0.6 | 24 | 1,692 |
| 09-26 11:18:49 | `anvil_ring` | 6 | 0.6 | 24 | 1,716 |
| 09-26 11:18:49 | `anvil_ring` | 5 | 0.6 | 24 | 1,740 |
| 09-26 11:18:50 | `anvil_ring` | 7 | 0.6 | 24 | 1,764 |
| 09-26 11:18:50 | `anvil_ring` | 8 | 0.6 | 24 | 1,788 |
| 09-26 11:18:50 | `anvil_ring` | 9 | 0.6 | 24 | 1,812 |
| 09-26 11:18:50 | `anvil_ring` | 10 | 0.6 | 24 | 1,836 |
| 09-26 11:18:51 | `chop` | 0 | 0.5 | 20 | 1,856 |
| 09-26 11:18:52 | `chop` | 1 | 0.5 | 20 | 1,876 |
| 09-26 11:18:52 | `chop` | 2 | 0.5 | 20 | 1,896 |
| 09-26 11:18:52 | `chop` | 3 | 0.5 | 20 | 1,916 |
| 09-26 11:18:53 | `chop` | 4 | 0.5 | 20 | 1,936 |
| 09-26 11:18:53 | `chop` | 5 | 0.5 | 20 | 1,956 |
| 09-26 11:18:53 | `chop` | 6 | 0.5 | 20 | 1,976 |
| 09-26 11:18:53 | `chop` | 7 | 0.5 | 20 | 1,996 |
| 09-26 11:18:54 | `chop` | 8 | 0.5 | 20 | 2,016 |
| 09-26 11:21:22 | `blade_hit` | 9 | 0.5 | 20 | 2,036 |
| 09-26 11:21:22 | `blade_hit` | 11 | 0.5 | 20 | 2,056 |
| 09-26 11:21:22 | `blade_hit` | 10 | 0.5 | 20 | 2,076 |
| 09-26 11:21:22 | `blade_hit` | 12 | 0.5 | 20 | 2,096 |
| 09-26 11:21:39 | `blade_hit` | 14 | 0.5 | 20 | 2,116 |
| 09-26 11:21:40 | `blade_hit` | 15 | 0.5 | 20 | 2,136 |
| 09-26 11:21:40 | `blade_hit` | 13 | 0.5 | 20 | 2,156 |
| 09-26 11:22:51 | `_tts_label` | 0 | 0 | 8 | 2,164 |
| 09-26 11:22:52 | `_tts_label` | 0 | 0 | 8 | 2,172 |
| 09-26 11:22:54 | `_tts_label` | 0 | 0 | 4 | 2,176 |
| 09-26 11:22:55 | `_tts_label` | 0 | 0 | 13 | 2,189 |
| 09-26 11:22:57 | `_tts_label` | 0 | 0 | 13 | 2,202 |
| 09-26 11:22:58 | `_tts_label` | 0 | 0 | 13 | 2,215 |
| 09-26 11:23:00 | `_tts_label` | 0 | 0 | 18 | 2,233 |
| 09-26 11:23:01 | `_tts_label` | 0 | 0 | 19 | 2,252 |
| 09-26 11:23:03 | `_tts_label` | 0 | 0 | 15 | 2,267 |
| 09-26 11:54:15 | `combat.heavy_impact` | 6 | 0.7 | 28 | 2,295 |
| 09-26 11:54:15 | `combat.heavy_impact` | 5 | 0.7 | 28 | 2,323 |
| 09-26 11:54:15 | `combat.bash_swing` | 1 | 0.6 | 24 | 2,347 |
| 09-26 11:54:15 | `combat.bash_swing` | 0 | 0.6 | 24 | 2,371 |
| 09-26 11:54:16 | `combat.bash_swing` | 2 | 0.6 | 24 | 2,395 |
| 09-26 11:54:17 | `combat.bash_swing` | 3 | 0.6 | 24 | 2,419 |
| 09-26 11:54:17 | `combat.bash_swing` | 5 | 0.6 | 24 | 2,443 |
| 09-26 11:54:17 | `combat.bash_swing` | 4 | 0.6 | 24 | 2,467 |
| 09-26 11:54:18 | `shield_thud` | 0 | 0.5 | 20 | 2,487 |
| 09-26 11:54:19 | `shield_thud` | 1 | 0.5 | 20 | 2,507 |
| 09-26 11:54:19 | `shield_thud` | 3 | 0.5 | 20 | 2,527 |
| 09-26 11:54:19 | `shield_thud` | 2 | 0.5 | 20 | 2,547 |
| 09-26 11:54:20 | `shield_thud` | 4 | 0.5 | 20 | 2,567 |
| 09-26 11:54:20 | `shield_thud` | 7 | 0.5 | 20 | 2,587 |
| 09-26 11:54:20 | `shield_thud` | 6 | 0.5 | 20 | 2,607 |
| 09-26 11:54:20 | `shield_thud` | 5 | 0.5 | 20 | 2,627 |
| 09-26 11:54:21 | `shield_thud` | 8 | 0.5 | 20 | 2,647 |
| 09-26 11:54:22 | `leap_slam` | 0 | 0.8 | 32 | 2,679 |
| 09-26 11:54:22 | `leap_slam` | 1 | 0.8 | 32 | 2,711 |
| 09-26 11:54:22 | `leap_slam` | 2 | 0.8 | 32 | 2,743 |
| 09-26 11:54:23 | `leap_slam` | 3 | 0.8 | 32 | 2,775 |
| 09-26 11:54:24 | `leap_slam` | 4 | 0.8 | 32 | 2,807 |
| 09-26 11:54:24 | `leap_slam` | 5 | 0.8 | 32 | 2,839 |
| 09-26 11:54:24 | `raider.brute_roar` | 0 | 1.5 | 60 | 2,899 |
| 09-26 11:54:25 | `raider.brute_roar` | 1 | 1.5 | 60 | 2,959 |
| 09-26 11:54:25 | `raider.brute_roar` | 2 | 1.5 | 60 | 3,019 |
| 09-26 11:54:25 | `raider.brute_roar` | 3 | 1.5 | 60 | 3,079 |
| 09-26 11:54:26 | `raider.brute_roar` | 4 | 1.5 | 60 | 3,139 |
| 09-26 11:54:27 | `raider.brute_roar` | 5 | 1.5 | 60 | 3,199 |
| 09-26 11:54:27 | `raider.brute_slam` | 0 | 1.0 | 40 | 3,239 |
| 09-26 11:54:27 | `raider.brute_slam` | 1 | 1.0 | 40 | 3,279 |
| 09-26 11:54:28 | `raider.brute_slam` | 2 | 1.0 | 40 | 3,319 |
| 09-26 11:54:28 | `raider.brute_slam` | 3 | 1.0 | 40 | 3,359 |
| 09-26 11:54:28 | `raider.brute_slam` | 4 | 1.0 | 40 | 3,399 |
| 09-26 11:54:29 | `raider.brute_slam` | 5 | 1.0 | 40 | 3,439 |
| 09-26 11:54:30 | `raider.bark` | 0 | 0.8 | 32 | 3,471 |
| 09-26 11:54:30 | `raider.bark` | 1 | 0.8 | 32 | 3,503 |
| 09-26 11:54:30 | `raider.bark` | 2 | 0.8 | 32 | 3,535 |
| 09-26 11:54:31 | `raider.bark` | 3 | 0.8 | 32 | 3,567 |
| 09-26 11:54:31 | `raider.bark` | 4 | 0.8 | 32 | 3,599 |
| 09-26 11:54:31 | `raider.bark` | 5 | 0.8 | 32 | 3,631 |
| 09-26 11:54:32 | `raider.bark` | 6 | 0.8 | 32 | 3,663 |
| 09-26 11:54:32 | `combat.execution_windup` | 0 | 0.5 | 20 | 3,683 |
| 09-26 11:54:33 | `combat.execution_windup` | 1 | 0.5 | 20 | 3,703 |
| 09-26 11:54:33 | `combat.execution_windup` | 2 | 0.5 | 20 | 3,723 |
| 09-26 11:54:33 | `combat.execution_windup` | 3 | 0.5 | 20 | 3,743 |
| 09-26 11:54:34 | `combat.execution_windup` | 4 | 0.5 | 20 | 3,763 |
| 09-26 11:54:34 | `combat.execution_windup` | 5 | 0.5 | 20 | 3,783 |
| 09-26 11:54:35 | `combat.execution_stinger` | 0 | 1.2 | 48 | 3,831 |
| 09-26 11:54:35 | `combat.execution_stinger` | 1 | 1.2 | 48 | 3,879 |
| 09-26 11:54:36 | `combat.execution_stinger` | 2 | 1.2 | 48 | 3,927 |
| 09-26 11:54:36 | `combat.execution_stinger` | 3 | 1.2 | 48 | 3,975 |
| 09-26 11:54:36 | `combat.execution_stinger` | 5 | 1.2 | 48 | 4,023 |
| 09-26 11:54:36 | `combat.execution_stinger` | 4 | 1.2 | 48 | 4,071 |
| 09-26 11:54:37 | `combat.execution_double` | 0 | 1.2 | 48 | 4,119 |
| 09-26 11:54:38 | `combat.execution_double` | 2 | 1.2 | 48 | 4,167 |
| 09-26 11:54:38 | `combat.execution_double` | 1 | 1.2 | 48 | 4,215 |
| 09-26 11:54:38 | `combat.execution_double` | 3 | 1.2 | 48 | 4,263 |
| 09-26 11:54:39 | `combat.execution_body_fall` | 0 | 0.8 | 32 | 4,295 |
| 09-26 11:54:39 | `combat.execution_body_fall` | 1 | 0.8 | 32 | 4,327 |
| 09-26 11:54:40 | `combat.execution_body_fall` | 3 | 0.8 | 32 | 4,359 |
| 09-26 11:54:40 | `combat.execution_body_fall` | 2 | 0.8 | 32 | 4,391 |
| 09-26 11:54:41 | `combat.execution_body_fall` | 4 | 0.8 | 32 | 4,423 |
| 09-26 11:54:41 | `combat.execution_body_fall` | 5 | 0.8 | 32 | 4,455 |
| 09-26 11:54:41 | `combat.guard_cheer` | 0 | 1.4 | 56 | 4,511 |
| 09-26 11:54:41 | `combat.guard_cheer` | 1 | 1.4 | 56 | 4,567 |
| 09-26 11:54:42 | `combat.guard_cheer` | 2 | 1.4 | 56 | 4,623 |
| 09-26 11:54:43 | `combat.guard_cheer` | 4 | 1.4 | 56 | 4,679 |
| 09-26 11:54:43 | `combat.guard_cheer` | 3 | 1.4 | 56 | 4,735 |
| 09-26 11:54:43 | `combat.guard_cheer` | 5 | 1.4 | 56 | 4,791 |
| 09-26 11:54:44 | `combat.guard_cheer` | 6 | 1.4 | 56 | 4,847 |
| 09-26 11:54:44 | `command_shout` | 0 | 0.9 | 36 | 4,883 |
| 09-26 11:54:44 | `command_shout` | 1 | 0.9 | 36 | 4,919 |
| 09-26 11:54:45 | `command_shout` | 2 | 0.9 | 36 | 4,955 |
| 09-26 11:54:46 | `command_shout` | 3 | 0.9 | 36 | 4,991 |
| 09-26 11:54:46 | `command_shout` | 4 | 0.9 | 36 | 5,027 |
| 09-26 11:54:46 | `command_shout` | 5 | 0.9 | 36 | 5,063 |
| 09-26 11:54:47 | `command_ack` | 0 | 0.9 | 36 | 5,099 |
| 09-26 11:54:47 | `command_ack` | 1 | 0.9 | 36 | 5,135 |
| 09-26 11:54:48 | `command_ack` | 2 | 0.9 | 36 | 5,171 |
| 09-26 11:54:48 | `command_ack` | 3 | 0.9 | 36 | 5,207 |
| 09-26 11:54:48 | `command_ack` | 4 | 0.9 | 36 | 5,243 |
| 09-26 11:54:49 | `downed_heartbeat` | 0 | 0.6 | 24 | 5,267 |
| 09-26 11:54:49 | `command_ack` | 5 | 0.9 | 36 | 5,303 |
| 09-26 11:54:49 | `downed_heartbeat` | 1 | 0.6 | 24 | 5,327 |
| 09-26 11:54:50 | `downed_heartbeat` | 2 | 0.6 | 24 | 5,351 |
| 09-26 11:54:50 | `downed_heartbeat` | 3 | 0.6 | 24 | 5,375 |
| 09-26 11:54:51 | `downed_heartbeat` | 4 | 0.6 | 24 | 5,399 |
| 09-26 11:54:51 | `downed_heartbeat` | 5 | 0.6 | 24 | 5,423 |
| 09-26 11:54:52 | `downed_heartbeat` | 6 | 0.6 | 24 | 5,447 |
| 09-26 11:54:52 | `downed_revived` | 0 | 1.2 | 48 | 5,495 |
| 09-26 11:54:52 | `downed_revived` | 1 | 1.2 | 48 | 5,543 |
| 09-26 11:54:53 | `downed_revived` | 2 | 1.2 | 48 | 5,591 |
| 09-26 11:54:54 | `downed_alert` | 0 | 0.9 | 36 | 5,627 |
| 09-26 11:54:54 | `downed_revived` | 3 | 1.2 | 48 | 5,675 |
| 09-26 11:54:54 | `downed_alert` | 1 | 0.9 | 36 | 5,711 |
| 09-26 11:54:54 | `downed_alert` | 2 | 0.9 | 36 | 5,747 |
| 09-26 11:54:55 | `downed_alert` | 3 | 0.9 | 36 | 5,783 |
| 09-26 11:54:55 | `downed_alert` | 4 | 0.9 | 36 | 5,819 |
| 09-26 11:54:55 | `downed_alert` | 5 | 0.9 | 36 | 5,855 |
| 09-26 11:54:56 | `ui_open` | 0 | 0.5 | 20 | 5,875 |
| 09-26 11:54:57 | `ui_open` | 1 | 0.5 | 20 | 5,895 |
| 09-26 11:54:57 | `ui_open` | 2 | 0.5 | 20 | 5,915 |
| 09-26 11:54:57 | `ui_open` | 3 | 0.5 | 20 | 5,935 |
| 09-26 11:54:58 | `ui_open` | 4 | 0.5 | 20 | 5,955 |
| 09-26 11:54:58 | `ui_open` | 5 | 0.5 | 20 | 5,975 |
| 09-26 11:54:58 | `ui_close` | 0 | 0.5 | 20 | 5,995 |
| 09-26 11:54:59 | `ui_close` | 1 | 0.5 | 20 | 6,015 |
| 09-26 11:54:59 | `ui_close` | 2 | 0.5 | 20 | 6,035 |
| 09-26 11:55:00 | `ui_close` | 3 | 0.5 | 20 | 6,055 |
| 09-26 11:55:00 | `ui_confirm` | 0 | 0.5 | 20 | 6,075 |
| 09-26 11:55:01 | `ui_confirm` | 1 | 0.5 | 20 | 6,095 |
| 09-26 11:55:01 | `ui_confirm` | 2 | 0.5 | 20 | 6,115 |
| 09-26 11:55:01 | `ui_confirm` | 3 | 0.5 | 20 | 6,135 |
| 09-26 11:55:02 | `ui_confirm` | 4 | 0.5 | 20 | 6,155 |
| 09-26 11:55:02 | `ui_confirm` | 5 | 0.5 | 20 | 6,175 |
| 09-26 11:55:02 | `ui_error` | 0 | 0.5 | 20 | 6,195 |
| 09-26 11:55:03 | `ui_error` | 1 | 0.5 | 20 | 6,215 |
| 09-26 11:55:03 | `ui_error` | 2 | 0.5 | 20 | 6,235 |
| 09-26 11:55:04 | `ui_error` | 3 | 0.5 | 20 | 6,255 |
| 09-26 11:55:04 | `fx.tech_learned` | 0 | 2.0 | 80 | 6,335 |
| 09-26 11:55:05 | `fx.tech_learned` | 1 | 2.0 | 80 | 6,415 |
| 09-26 11:55:05 | `fx.tech_learned` | 2 | 2.0 | 80 | 6,495 |
| 09-26 11:55:05 | `fx.tech_learned` | 3 | 2.0 | 80 | 6,575 |
| 09-26 11:55:06 | `fx.building_level_up` | 0 | 2.5 | 100 | 6,675 |
| 09-26 11:55:07 | `fx.building_level_up` | 2 | 2.5 | 100 | 6,775 |
| 09-26 11:55:07 | `fx.building_level_up` | 1 | 2.5 | 100 | 6,875 |
| 09-26 11:55:08 | `fx.building_level_up` | 3 | 2.5 | 100 | 6,975 |
| 09-26 11:55:08 | `fx.journey_chapter` | 0 | 2.5 | 100 | 7,075 |
| 09-26 11:55:09 | `fx.journey_chapter` | 2 | 2.5 | 100 | 7,175 |
| 09-26 11:55:09 | `fx.journey_chapter` | 1 | 2.5 | 100 | 7,275 |
| 09-26 11:55:09 | `fx.journey_chapter` | 3 | 2.5 | 100 | 7,375 |
| 09-26 11:55:10 | `fx.craft_glint` | 0 | 0.8 | 32 | 7,407 |
| 09-26 11:55:10 | `fx.craft_glint` | 1 | 0.8 | 32 | 7,439 |
| 09-26 11:55:10 | `fx.craft_glint` | 2 | 0.8 | 32 | 7,471 |
| 09-26 11:55:11 | `fx.craft_glint` | 3 | 0.8 | 32 | 7,503 |
| 09-26 11:55:11 | `fx.craft_glint` | 4 | 0.8 | 32 | 7,535 |
| 09-26 11:55:12 | `fx.craft_glint` | 5 | 0.8 | 32 | 7,567 |
| 09-26 11:55:13 | `fx.craft_legendary` | 0 | 2.0 | 80 | 7,647 |
| 09-26 11:55:13 | `fx.craft_legendary` | 2 | 2.0 | 80 | 7,727 |
| 09-26 11:55:13 | `fx.craft_legendary` | 1 | 2.0 | 80 | 7,807 |
| 09-26 11:55:14 | `fx.craft_legendary` | 3 | 2.0 | 80 | 7,887 |
| 09-26 11:55:14 | `fx.coin_sale` | 1 | 0.7 | 28 | 7,915 |
| 09-26 11:55:15 | `fx.coin_sale` | 2 | 0.7 | 28 | 7,943 |
| 09-26 11:55:15 | `fx.coin_sale` | 3 | 0.7 | 28 | 7,971 |
| 09-26 11:55:15 | `fx.coin_sale` | 4 | 0.7 | 28 | 7,999 |
| 09-26 11:55:16 | `fx.coin_sale` | 5 | 0.7 | 28 | 8,027 |
| 09-26 11:55:16 | `fx.coin_sale` | 6 | 0.7 | 28 | 8,055 |
| 09-26 11:55:17 | `fx.build_done` | 0 | 1.5 | 60 | 8,115 |
| 09-26 11:55:17 | `fx.build_done` | 1 | 1.5 | 60 | 8,175 |
| 09-26 11:55:17 | `fx.build_done` | 2 | 1.5 | 60 | 8,235 |
| 09-26 11:55:18 | `fx.build_done` | 3 | 1.5 | 60 | 8,295 |
| 09-26 11:55:18 | `fx.raid_won` | 0 | 1.2 | 48 | 8,343 |
| 09-26 11:55:19 | `fx.raid_won` | 1 | 1.2 | 48 | 8,391 |
| 09-26 11:55:19 | `fx.raid_won` | 2 | 1.2 | 48 | 8,439 |
| 09-26 11:55:20 | `fx.raid_won` | 3 | 1.2 | 48 | 8,487 |
| 09-26 11:55:20 | `fx.raid_won` | 4 | 1.2 | 48 | 8,535 |
| 09-26 11:55:21 | `fx.raid_won` | 5 | 1.2 | 48 | 8,583 |
| 09-26 11:55:21 | `settlement_founded` | 0 | 3.5 | 140 | 8,723 |
| 09-26 11:55:21 | `settlement_founded` | 1 | 3.5 | 140 | 8,863 |
| 09-26 11:55:22 | `settlement_founded` | 2 | 3.5 | 140 | 9,003 |
| 09-26 11:55:22 | `settlement_founded` | 3 | 3.5 | 140 | 9,143 |
| 09-26 11:55:23 | `profession_assigned` | 0 | 1.0 | 40 | 9,183 |
| 09-26 11:55:23 | `profession_assigned` | 1 | 1.0 | 40 | 9,223 |
| 09-26 11:55:24 | `profession_assigned` | 2 | 1.0 | 40 | 9,263 |
| 09-26 11:55:24 | `profession_assigned` | 3 | 1.0 | 40 | 9,303 |
| 09-26 11:55:24 | `profession_assigned` | 4 | 1.0 | 40 | 9,343 |
| 09-26 11:55:25 | `profession_assigned` | 5 | 1.0 | 40 | 9,383 |
| 09-26 11:55:26 | `settler_recruited` | 0 | 2.2 | 88 | 9,471 |
| 09-26 11:55:26 | `settler_recruited` | 1 | 2.2 | 88 | 9,559 |
| 09-26 11:55:26 | `settler_recruited` | 3 | 2.2 | 88 | 9,647 |
| 09-26 11:55:26 | `settler_recruited` | 2 | 2.2 | 88 | 9,735 |
| 09-26 11:55:27 | `guard_alert` | 1 | 2.2 | 88 | 9,823 |
| 09-26 11:55:27 | `guard_alert` | 0 | 2.2 | 88 | 9,911 |
| 09-26 11:55:28 | `guard_alert` | 2 | 2.2 | 88 | 9,999 |
| 09-26 11:55:28 | `guard_alert` | 3 | 2.2 | 88 | 10,087 |
| 09-26 11:55:29 | `guard_alert` | 4 | 2.2 | 88 | 10,175 |
| 09-26 11:55:29 | `guard_alert` | 5 | 2.2 | 88 | 10,263 |
| 09-26 11:55:29 | `guard_experience` | 0 | 0.5 | 20 | 10,283 |
| 09-26 11:55:30 | `guard_experience` | 1 | 0.5 | 20 | 10,303 |
| 09-26 11:55:31 | `guard_experience` | 2 | 0.5 | 20 | 10,323 |
| 09-26 11:55:31 | `guard_experience` | 3 | 0.5 | 20 | 10,343 |
| 09-26 11:55:31 | `guard_experience` | 4 | 0.5 | 20 | 10,363 |
| 09-26 11:55:31 | `guard_experience` | 5 | 0.5 | 20 | 10,383 |
| 09-26 11:55:32 | `mug_set` | 0 | 0.6 | 24 | 10,407 |
| 09-26 11:55:32 | `mug_set` | 1 | 0.6 | 24 | 10,431 |
| 09-26 11:55:33 | `mug_set` | 2 | 0.6 | 24 | 10,455 |
| 09-26 11:55:33 | `mug_set` | 3 | 0.6 | 24 | 10,479 |
| 09-26 11:55:34 | `mug_set` | 4 | 0.6 | 24 | 10,503 |
| 09-26 11:55:34 | `mug_set` | 5 | 0.6 | 24 | 10,527 |
| 09-26 11:55:34 | `mug_set` | 6 | 0.6 | 24 | 10,551 |
| 09-26 11:55:35 | `tavern.pour` | 0 | 1.3 | 52 | 10,603 |
| 09-26 11:55:35 | `tavern.pour` | 1 | 1.3 | 52 | 10,655 |
| 09-26 11:55:36 | `tavern.pour` | 2 | 1.3 | 52 | 10,707 |
| 09-26 11:55:36 | `tavern.pour` | 3 | 1.3 | 52 | 10,759 |
| 09-26 11:55:37 | `tavern.pour` | 4 | 1.3 | 52 | 10,811 |
| 09-26 11:55:37 | `tavern.pour` | 5 | 1.3 | 52 | 10,863 |
| 09-26 11:55:37 | `cheer` | 1 | 1.2 | 48 | 10,911 |
| 09-26 11:55:38 | `cheer` | 0 | 1.2 | 48 | 10,959 |
| 09-26 11:55:39 | `cheer` | 2 | 1.2 | 48 | 11,007 |
| 09-26 11:55:39 | `cheer` | 3 | 1.2 | 48 | 11,055 |
| 09-26 11:55:39 | `cheer` | 4 | 1.2 | 48 | 11,103 |
| 09-26 11:55:40 | `cheer` | 5 | 1.2 | 48 | 11,151 |
| 09-26 11:55:40 | `cheer` | 6 | 1.2 | 48 | 11,199 |
| 09-26 11:55:41 | `tavern_laugh` | 1 | 2.5 | 100 | 11,299 |
| 09-26 11:55:42 | `tavern_laugh` | 2 | 2.5 | 100 | 11,399 |
| 09-26 11:55:42 | `tavern_laugh` | 0 | 2.5 | 100 | 11,499 |
| 09-26 11:55:42 | `tavern_laugh` | 4 | 2.5 | 100 | 11,599 |
| 09-26 11:55:43 | `tavern_laugh` | 3 | 2.5 | 100 | 11,699 |
| 09-26 11:55:44 | `tavern_laugh` | 5 | 2.5 | 100 | 11,799 |
| 09-26 11:55:44 | `chisel_tap` | 0 | 0.5 | 20 | 11,819 |
| 09-26 11:55:44 | `tavern_laugh` | 6 | 2.5 | 100 | 11,919 |
| 09-26 11:55:44 | `chisel_tap` | 1 | 0.5 | 20 | 11,939 |
| 09-26 11:55:45 | `chisel_tap` | 2 | 0.5 | 20 | 11,959 |
| 09-26 11:55:45 | `chisel_tap` | 3 | 0.5 | 20 | 11,979 |
| 09-26 11:55:46 | `chisel_tap` | 4 | 0.5 | 20 | 11,999 |
| 09-26 11:55:46 | `chisel_tap` | 5 | 0.5 | 20 | 12,019 |
| 09-26 11:55:47 | `chisel_tap` | 7 | 0.5 | 20 | 12,039 |
| 09-26 11:55:47 | `chisel_tap` | 8 | 0.5 | 20 | 12,059 |
| 09-26 11:55:47 | `chisel_tap` | 6 | 0.5 | 20 | 12,079 |
| 09-26 11:55:48 | `nail_tap` | 0 | 0.5 | 20 | 12,099 |
| 09-26 11:55:49 | `nail_tap` | 1 | 0.5 | 20 | 12,119 |
| 09-26 11:55:49 | `nail_tap` | 2 | 0.5 | 20 | 12,139 |
| 09-26 11:55:49 | `nail_tap` | 3 | 0.5 | 20 | 12,159 |
| 09-26 11:55:49 | `nail_tap` | 4 | 0.5 | 20 | 12,179 |
| 09-26 11:55:50 | `nail_tap` | 6 | 0.5 | 20 | 12,199 |
| 09-26 11:55:50 | `nail_tap` | 5 | 0.5 | 20 | 12,219 |
| 09-26 11:55:50 | `nail_tap` | 7 | 0.5 | 20 | 12,239 |
| 09-26 11:55:51 | `nail_tap` | 8 | 0.5 | 20 | 12,259 |
| 09-26 11:55:52 | `builder.place` | 0 | 0.5 | 20 | 12,279 |
| 09-26 11:55:52 | `builder.place` | 1 | 0.5 | 20 | 12,299 |
| 09-26 11:55:52 | `builder.place` | 2 | 0.5 | 20 | 12,319 |
| 09-26 11:55:52 | `builder.place` | 3 | 0.5 | 20 | 12,339 |
| 09-26 11:55:54 | `builder.place` | 4 | 0.5 | 20 | 12,359 |
| 09-26 11:55:54 | `builder.place` | 6 | 0.5 | 20 | 12,379 |
| 09-26 11:55:54 | `builder.place` | 5 | 0.5 | 20 | 12,399 |
| 09-26 11:55:54 | `pick_strike` | 0 | 0.5 | 20 | 12,419 |
| 09-26 11:55:55 | `pick_strike` | 1 | 0.5 | 20 | 12,439 |
| 09-26 11:55:55 | `pick_strike` | 2 | 0.5 | 20 | 12,459 |
| 09-26 11:55:55 | `pick_strike` | 3 | 0.5 | 20 | 12,479 |
| 09-26 11:55:56 | `pick_strike` | 4 | 0.5 | 20 | 12,499 |
| 09-26 11:55:57 | `pick_strike` | 5 | 0.5 | 20 | 12,519 |
| 09-26 11:55:57 | `pick_strike` | 7 | 0.5 | 20 | 12,539 |
| 09-26 11:55:57 | `pick_strike` | 6 | 0.5 | 20 | 12,559 |
| 09-26 11:55:57 | `pick_strike` | 8 | 0.5 | 20 | 12,579 |
| 09-26 11:55:59 | `raid_horn` | 0 | 5.0 | 200 | 12,779 |
| 09-26 11:55:59 | `raid_horn` | 1 | 5.0 | 200 | 12,979 |
| 09-26 11:55:59 | `raid_horn` | 2 | 5.0 | 200 | 13,179 |
| 09-26 11:56:00 | `raid_horn` | 3 | 5.0 | 200 | 13,379 |
| 09-26 11:56:01 | `raid.won_fanfare` | 2 | 3.0 | 120 | 13,499 |
| 09-26 11:56:01 | `raid.won_fanfare` | 0 | 3.0 | 120 | 13,619 |
| 09-26 11:56:01 | `raid.won_fanfare` | 1 | 3.0 | 120 | 13,739 |
| 09-26 11:56:01 | `raid.won_fanfare` | 3 | 3.0 | 120 | 13,859 |
| 09-26 11:56:03 | `raid.lost_toll` | 0 | 4.0 | 160 | 14,019 |
| 09-26 11:56:03 | `raid.lost_toll` | 1 | 4.0 | 160 | 14,179 |
| 09-26 11:56:03 | `raid.lost_toll` | 2 | 4.0 | 160 | 14,339 |
| 09-26 11:56:04 | `raid.lost_toll` | 3 | 4.0 | 160 | 14,499 |
| 09-26 11:56:05 | `ui.page_turn` | 0 | 0.5 | 20 | 14,519 |
| 09-26 11:56:05 | `ui.page_turn` | 1 | 0.5 | 20 | 14,539 |
| 09-26 11:56:05 | `ui.page_turn` | 2 | 0.5 | 20 | 14,559 |
| 09-26 11:56:05 | `ui.page_turn` | 3 | 0.5 | 20 | 14,579 |
| 09-26 11:56:06 | `ui.page_turn` | 4 | 0.5 | 20 | 14,599 |
| 09-26 11:56:06 | `ui.page_turn` | 5 | 0.5 | 20 | 14,619 |
| 09-26 11:56:06 | `ui.page_turn` | 6 | 0.5 | 20 | 14,639 |
| 09-26 12:02:31 | `_tts_label` | 0 | 0 | 7 | 14,646 |
| 09-26 12:02:36 | `_tts_label` | 0 | 0 | 5 | 14,651 |
| 09-26 12:02:39 | `_tts_label` | 0 | 0 | 6 | 14,657 |
| 09-26 12:02:44 | `_tts_label` | 0 | 0 | 3 | 14,660 |
| 09-26 12:02:54 | `_tts_label` | 0 | 0 | 7 | 14,667 |
| 09-26 12:02:59 | `_tts_label` | 0 | 0 | 8 | 14,675 |
| 09-26 12:03:01 | `_tts_label` | 0 | 0 | 5 | 14,680 |
| 09-26 12:08:20 | `_babble_test_tts` | 0 | 0 | 129 | 14,809 |
| 09-26 12:08:20 | `_babble_test_design` | 0 | 0 | 400 | 15,209 |
| 09-26 12:08:20 | `_babble_test_sfx` | 0 | 0 | 120 | 15,329 |
| 09-26 12:10:15 | `voice.m_adult (design)` | 0 | 0 | 1000 | 16,329 |
| 09-26 12:10:34 | `voice.m_young (design)` | 0 | 0 | 1000 | 17,329 |
| 09-26 12:10:52 | `voice.m_old (design)` | 0 | 0 | 1000 | 18,329 |
| 09-26 12:11:04 | `voice.f_young (design)` | 0 | 0 | 1000 | 19,329 |
| 09-26 12:11:15 | `voice.f_adult (design)` | 0 | 0 | 1000 | 20,329 |
| 09-26 12:11:28 | `voice.f_old (design)` | 0 | 0 | 1000 | 21,329 |
| 09-26 12:11:39 | `voice.brute (design)` | 0 | 0 | 1000 | 22,329 |
| 09-26 12:11:52 | `voice.goblin (design)` | 0 | 0 | 1000 | 23,329 |
| 09-26 12:12:05 | `voice.captain (design)` | 0 | 0 | 1000 | 24,329 |
| 09-26 12:12:18 | `voice.peddler (design)` | 0 | 0 | 1000 | 25,329 |
| 09-26 12:12:31 | `voice.envoy (design)` | 0 | 0 | 1000 | 26,329 |
| 09-26 12:12:43 | `voice.minstrel (design)` | 0 | 0 | 1000 | 27,329 |
| 09-26 12:14:47 | `voice.brute (design)` | 0 | 0 | 1000 | 28,329 |
| 09-26 12:15:01 | `voice.peddler (design)` | 0 | 0 | 1000 | 29,329 |
| 09-26 12:15:11 | `voice.minstrel (design)` | 0 | 0 | 1000 | 30,329 |
| 09-26 12:16:13 | `voice.peddler (design)` | 0 | 0 | 1000 | 31,329 |
| 09-26 12:22:06 | `voice.m_young (design)` | 0 | 0 | 400 | 31,729 |
| 09-26 12:22:22 | `voice.m_adult (design)` | 0 | 0 | 400 | 32,129 |
| 09-26 12:22:41 | `voice.m_old (design)` | 0 | 0 | 400 | 32,529 |
| 09-26 12:23:05 | `voice.f_young (design)` | 0 | 0 | 400 | 32,929 |
| 09-26 12:23:33 | `voice.f_adult (design)` | 0 | 0 | 400 | 33,329 |
| 09-26 12:23:55 | `voice.f_old (design)` | 0 | 0 | 400 | 33,729 |
| 09-26 12:24:19 | `voice.brute (design)` | 0 | 0 | 400 | 34,129 |
| 09-26 12:24:43 | `voice.goblin (design)` | 0 | 0 | 400 | 34,529 |
| 09-26 12:25:04 | `voice.captain (design)` | 0 | 0 | 400 | 34,929 |
| 09-26 12:25:24 | `voice.peddler (design)` | 0 | 0 | 400 | 35,329 |
| 09-26 12:25:44 | `voice.envoy (design)` | 0 | 0 | 400 | 35,729 |
| 09-26 12:26:03 | `voice.minstrel (design)` | 0 | 0 | 400 | 36,129 |
| 09-26 12:27:26 | `ui.conversation_open` | 0 | 0.9 | 36 | 36,165 |
| 09-26 12:27:27 | `ui.conversation_open` | 1 | 0.9 | 36 | 36,201 |
| 09-26 12:27:27 | `ui.conversation_open` | 2 | 0.9 | 36 | 36,237 |
| 09-26 12:27:27 | `ui.conversation_open` | 3 | 0.9 | 36 | 36,273 |
| 09-26 12:27:27 | `ui.conversation_open` | 4 | 0.9 | 36 | 36,309 |
| 09-26 12:27:28 | `ui.conversation_open` | 5 | 0.9 | 36 | 36,345 |
| 09-26 12:27:29 | `convo.pull_in` | 1 | 0.8 | 32 | 36,377 |
| 09-26 12:27:29 | `convo.pull_in` | 0 | 0.8 | 32 | 36,409 |
| 09-26 12:27:29 | `convo.pull_in` | 2 | 0.8 | 32 | 36,441 |
| 09-26 12:27:30 | `convo.pull_in` | 3 | 0.8 | 32 | 36,473 |
| 09-26 12:27:30 | `convo.name_card` | 0 | 0.6 | 24 | 36,497 |
| 09-26 12:27:30 | `convo.name_card` | 1 | 0.6 | 24 | 36,521 |
| 09-26 12:27:31 | `convo.name_card` | 2 | 0.6 | 24 | 36,545 |
| 09-26 12:27:32 | `convo.name_card` | 3 | 0.6 | 24 | 36,569 |
| 09-26 12:27:32 | `convo.persuade_ok` | 0 | 1.0 | 40 | 36,609 |
| 09-26 12:27:32 | `convo.persuade_ok` | 2 | 1.0 | 40 | 36,649 |
| 09-26 12:27:32 | `convo.persuade_ok` | 1 | 1.0 | 40 | 36,689 |
| 09-26 12:27:34 | `convo.persuade_ok` | 3 | 1.0 | 40 | 36,729 |
| 09-26 12:27:34 | `convo.persuade_fail` | 0 | 0.8 | 32 | 36,761 |
| 09-26 12:27:34 | `convo.persuade_fail` | 1 | 0.8 | 32 | 36,793 |
| 09-26 12:27:34 | `convo.persuade_fail` | 2 | 0.8 | 32 | 36,825 |
| 09-26 12:27:35 | `convo.persuade_fail` | 3 | 0.8 | 32 | 36,857 |
| 09-26 12:27:35 | `convo.deal` | 2 | 0.9 | 36 | 36,893 |
| 09-26 12:27:36 | `convo.deal` | 0 | 0.9 | 36 | 36,929 |
| 09-26 12:27:36 | `convo.deal` | 1 | 0.9 | 36 | 36,965 |
| 09-26 12:27:37 | `convo.deal` | 3 | 0.9 | 36 | 37,001 |
| 09-26 12:28:48 | `_tts_label` | 0 | 0 | 17 | 37,018 |
| 09-26 12:28:51 | `_tts_label` | 0 | 0 | 23 | 37,041 |
| 09-26 12:28:54 | `_tts_label` | 0 | 0 | 11 | 37,052 |
| 09-26 12:28:57 | `_tts_label` | 0 | 0 | 14 | 37,066 |
| 09-26 12:29:01 | `_tts_label` | 0 | 0 | 25 | 37,091 |
| 09-26 12:29:04 | `_tts_label` | 0 | 0 | 13 | 37,104 |
| 09-26 12:29:07 | `_tts_label` | 0 | 0 | 15 | 37,119 |
| 09-26 12:29:14 | `_tts_label` | 0 | 0 | 17 | 37,136 |
| 09-26 12:29:18 | `_tts_label` | 0 | 0 | 22 | 37,158 |
| 09-26 12:29:22 | `_tts_label` | 0 | 0 | 12 | 37,170 |
| 09-26 12:29:25 | `_tts_label` | 0 | 0 | 23 | 37,193 |
| 09-26 12:29:29 | `_tts_label` | 0 | 0 | 23 | 37,216 |
| 09-26 12:37:57 | `armour_clink` | 1 | 0.5 | 20 | 37,236 |
| 09-26 12:37:57 | `armour_clink` | 3 | 0.5 | 20 | 37,256 |
| 09-26 12:37:57 | `armour_clink` | 0 | 0.5 | 20 | 37,276 |
| 09-26 12:37:57 | `armour_clink` | 2 | 0.5 | 20 | 37,296 |
| 09-26 12:37:59 | `armour_clink` | 4 | 0.5 | 20 | 37,316 |
| 09-26 12:37:59 | `raider.hurt` | 1 | 0.5 | 20 | 37,336 |
| 09-26 12:37:59 | `raider.hurt` | 0 | 0.5 | 20 | 37,356 |
| 09-26 12:37:59 | `raider.hurt` | 2 | 0.5 | 20 | 37,376 |
| 09-26 12:38:00 | `raider.hurt` | 3 | 0.5 | 20 | 37,396 |
| 09-26 12:38:00 | `raider.hurt` | 4 | 0.5 | 20 | 37,416 |
| 09-26 12:38:01 | `raider.death` | 0 | 1.0 | 40 | 37,456 |
| 09-26 12:38:01 | `raider.death` | 1 | 1.0 | 40 | 37,496 |
| 09-26 12:38:02 | `raider.death` | 2 | 1.0 | 40 | 37,536 |
| 09-26 12:38:02 | `combat.execution_stinger.axe` | 0 | 1.0 | 40 | 37,576 |
| 09-26 12:38:02 | `raider.death` | 3 | 1.0 | 40 | 37,616 |
| 09-26 12:38:02 | `combat.execution_stinger.axe` | 1 | 1.0 | 40 | 37,656 |
| 09-26 12:38:04 | `combat.execution_stinger.axe` | 3 | 1.0 | 40 | 37,696 |
| 09-26 12:38:04 | `combat.execution_stinger.axe` | 2 | 1.0 | 40 | 37,736 |
| 09-26 12:38:04 | `combat.execution_stinger.mace` | 0 | 1.0 | 40 | 37,776 |
| 09-26 12:38:04 | `combat.execution_stinger.mace` | 1 | 1.0 | 40 | 37,816 |
| 09-26 12:38:05 | `combat.execution_stinger.mace` | 2 | 1.0 | 40 | 37,856 |
| 09-26 12:38:05 | `combat.execution_stinger.mace` | 3 | 1.0 | 40 | 37,896 |
| 09-26 12:38:05 | `combat.execution_stinger.spear` | 1 | 1.0 | 40 | 37,936 |
| 09-26 12:38:06 | `combat.execution_stinger.spear` | 0 | 1.0 | 40 | 37,976 |
| 09-26 12:38:07 | `combat.execution_stinger.spear` | 2 | 1.0 | 40 | 38,016 |
| 09-26 12:38:07 | `combat.execution_stinger.spear` | 3 | 1.0 | 40 | 38,056 |
| 09-26 12:38:07 | `combat.execution_stinger.bare` | 0 | 0.8 | 32 | 38,088 |
| 09-26 12:38:07 | `combat.execution_stinger.bare` | 1 | 0.8 | 32 | 38,120 |
| 09-26 12:38:09 | `combat.execution_stinger.bare` | 3 | 0.8 | 32 | 38,152 |
| 09-26 12:38:09 | `role.spear_thrust` | 0 | 0.5 | 20 | 38,172 |
| 09-26 12:38:09 | `combat.execution_stinger.bare` | 2 | 0.8 | 32 | 38,204 |
| 09-26 12:38:09 | `role.spear_thrust` | 1 | 0.5 | 20 | 38,224 |
| 09-26 12:38:10 | `role.spear_thrust` | 3 | 0.5 | 20 | 38,244 |
| 09-26 12:38:10 | `role.spear_thrust` | 2 | 0.5 | 20 | 38,264 |
| 09-26 12:38:10 | `role.longsword_cleave` | 0 | 0.7 | 28 | 38,292 |
| 09-26 12:38:10 | `role.longsword_cleave` | 1 | 0.7 | 28 | 38,320 |
| 09-26 12:38:12 | `role.longsword_cleave` | 2 | 0.7 | 28 | 38,348 |
| 09-26 12:38:12 | `role.longsword_cleave` | 3 | 0.7 | 28 | 38,376 |
| 09-26 12:38:12 | `role.rune_cast` | 0 | 1.2 | 48 | 38,424 |
| 09-26 12:38:12 | `role.rune_cast` | 1 | 1.2 | 48 | 38,472 |
| 09-26 12:38:13 | `role.rune_cast` | 2 | 1.2 | 48 | 38,520 |
| 09-26 12:38:14 | `role.firebolt_impact` | 0 | 1.0 | 40 | 38,560 |
| 09-26 12:38:14 | `role.firebolt_impact` | 1 | 1.0 | 40 | 38,600 |
| 09-26 12:38:14 | `role.rune_cast` | 3 | 1.2 | 48 | 38,648 |
| 09-26 12:38:15 | `role.firebolt_impact` | 2 | 1.0 | 40 | 38,688 |
| 09-26 12:38:15 | `role.firebolt_impact` | 3 | 1.0 | 40 | 38,728 |
| 09-26 12:38:16 | `role.ward_up` | 0 | 1.2 | 48 | 38,776 |
| 09-26 12:38:17 | `role.ward_up` | 2 | 1.2 | 48 | 38,824 |
| 09-26 12:38:17 | `role.ward_up` | 3 | 1.2 | 48 | 38,872 |
| 09-26 12:38:18 | `role.frost_rune` | 0 | 1.0 | 40 | 38,912 |
| 09-26 12:38:18 | `role.frost_rune` | 1 | 1.0 | 40 | 38,952 |
| 09-26 12:38:18 | `role.ward_up` | 1 | 1.2 | 48 | 39,000 |
| 09-26 12:38:19 | `role.frost_rune` | 2 | 1.0 | 40 | 39,040 |
| 09-26 12:38:19 | `role.frost_rune` | 3 | 1.0 | 40 | 39,080 |
| 09-26 12:38:20 | `role.bandage` | 0 | 0.8 | 32 | 39,112 |
| 09-26 12:38:20 | `role.bandage` | 1 | 0.8 | 32 | 39,144 |
| 09-26 12:38:20 | `role.bandage` | 2 | 0.8 | 32 | 39,176 |
| 09-26 12:38:21 | `role.bandage` | 3 | 0.8 | 32 | 39,208 |
| 09-26 12:38:22 | `fx.warehouse_level_up` | 0 | 2.0 | 80 | 39,288 |
| 09-26 12:38:22 | `fx.warehouse_level_up` | 1 | 2.0 | 80 | 39,368 |
| 09-26 12:38:22 | `fx.warehouse_level_up` | 2 | 2.0 | 80 | 39,448 |
| 09-26 12:38:22 | `fx.summon_arrival` | 0 | 1.2 | 48 | 39,496 |
| 09-26 12:38:23 | `fx.summon_arrival` | 2 | 1.2 | 48 | 39,544 |
| 09-26 12:38:23 | `fx.summon_arrival` | 1 | 1.2 | 48 | 39,592 |
| 09-26 12:38:24 | `fx.summon_arrival` | 3 | 1.2 | 48 | 39,640 |
| 09-26 12:38:24 | `fx.patrol_waypoint` | 0 | 0.6 | 24 | 39,664 |
| 09-26 12:38:25 | `fx.patrol_waypoint` | 2 | 0.6 | 24 | 39,688 |
| 09-26 12:38:25 | `fx.patrol_waypoint` | 1 | 0.6 | 24 | 39,712 |
| 09-26 12:38:25 | `fx.patrol_waypoint` | 3 | 0.6 | 24 | 39,736 |
| 09-26 12:38:26 | `fx.order_confirmed` | 0 | 0.6 | 24 | 39,760 |
| 09-26 12:38:26 | `fx.order_confirmed` | 1 | 0.6 | 24 | 39,784 |
| 09-26 12:38:26 | `fx.order_confirmed` | 2 | 0.6 | 24 | 39,808 |
| 09-26 12:38:27 | `fx.order_confirmed` | 3 | 0.6 | 24 | 39,832 |
| 09-26 12:38:27 | `ui.map_ping` | 0 | 0.6 | 24 | 39,856 |
| 09-26 12:38:28 | `ui.map_ping` | 1 | 0.6 | 24 | 39,880 |
| 09-26 12:38:28 | `ui.map_ping` | 2 | 0.6 | 24 | 39,904 |
| 09-26 12:38:28 | `ui.map_ping` | 3 | 0.6 | 24 | 39,928 |
| 09-26 12:38:29 | `tavern.drink` | 0 | 0.7 | 28 | 39,956 |
| 09-26 12:38:29 | `tavern.drink` | 1 | 0.7 | 28 | 39,984 |
| 09-26 12:38:30 | `tavern.drink` | 2 | 0.7 | 28 | 40,012 |
| 09-26 12:38:30 | `tavern.drink` | 3 | 0.7 | 28 | 40,040 |
| 09-26 12:38:33 | `tavern_ambience` | 0 | 12.0 | 480 | 40,520 |
| 09-26 12:38:33 | `tavern_ambience` | 1 | 12.0 | 480 | 41,000 |
| 09-26 12:38:34 | `tavern_ambience` | 2 | 12.0 | 480 | 41,480 |
| 09-26 12:38:34 | `tavern_ambience` | 3 | 12.0 | 480 | 41,960 |
| 09-26 12:38:35 | `tavern_fire` | 0 | 3.5 | 140 | 42,100 |
| 09-26 12:38:36 | `tavern_fire` | 1 | 3.5 | 140 | 42,240 |
| 09-26 12:38:36 | `tavern_fire` | 2 | 3.5 | 140 | 42,380 |
| 09-26 12:38:37 | `tavern_fire` | 3 | 3.5 | 140 | 42,520 |
| 09-26 12:38:37 | `saw_stroke` | 0 | 1.0 | 40 | 42,560 |
| 09-26 12:38:37 | `saw_stroke` | 1 | 1.0 | 40 | 42,600 |
| 09-26 12:38:37 | `saw_stroke` | 2 | 1.0 | 40 | 42,640 |
| 09-26 12:38:38 | `saw_stroke` | 3 | 1.0 | 40 | 42,680 |
| 09-26 12:38:38 | `saw_stroke` | 4 | 1.0 | 40 | 42,720 |
| 09-26 12:38:39 | `plane_shave` | 0 | 0.7 | 28 | 42,748 |
| 09-26 12:38:39 | `plane_shave` | 1 | 0.7 | 28 | 42,776 |
| 09-26 12:38:40 | `plane_shave` | 2 | 0.7 | 28 | 42,804 |
| 09-26 12:38:40 | `plane_shave` | 3 | 0.7 | 28 | 42,832 |
| 09-26 12:38:41 | `plane_shave` | 4 | 0.7 | 28 | 42,860 |
| 09-26 12:38:41 | `whetstone_scrape` | 0 | 0.5 | 20 | 42,880 |
| 09-26 12:38:42 | `whetstone_scrape` | 1 | 0.5 | 20 | 42,900 |
| 09-26 12:38:42 | `whetstone_scrape` | 2 | 0.5 | 20 | 42,920 |
| 09-26 12:38:42 | `whetstone_scrape` | 3 | 0.5 | 20 | 42,940 |
| 09-26 12:38:43 | `whetstone_scrape` | 4 | 0.5 | 20 | 42,960 |
| 09-26 12:38:43 | `bellows_puff` | 0 | 0.8 | 32 | 42,992 |
| 09-26 12:38:44 | `bellows_puff` | 1 | 0.8 | 32 | 43,024 |
| 09-26 12:38:44 | `bellows_puff` | 2 | 0.8 | 32 | 43,056 |
| 09-26 12:38:44 | `bellows_puff` | 3 | 0.8 | 32 | 43,088 |
| 09-26 12:38:45 | `cleaver_chop` | 0 | 0.5 | 20 | 43,108 |
| 09-26 12:38:45 | `cleaver_chop` | 1 | 0.5 | 20 | 43,128 |
| 09-26 12:38:45 | `cleaver_chop` | 2 | 0.5 | 20 | 43,148 |
| 09-26 12:38:46 | `cleaver_chop` | 3 | 0.5 | 20 | 43,168 |
| 09-26 12:38:46 | `cleaver_chop` | 4 | 0.5 | 20 | 43,188 |
| 09-26 12:38:47 | `knead_press` | 0 | 0.6 | 24 | 43,212 |
| 09-26 12:38:47 | `knead_press` | 1 | 0.6 | 24 | 43,236 |
| 09-26 12:38:47 | `knead_press` | 2 | 0.6 | 24 | 43,260 |
| 09-26 12:38:48 | `knead_press` | 3 | 0.6 | 24 | 43,284 |
| 09-26 12:38:48 | `loom_clack` | 0 | 0.5 | 20 | 43,304 |
| 09-26 12:38:48 | `loom_clack` | 1 | 0.5 | 20 | 43,324 |
| 09-26 12:38:49 | `loom_clack` | 2 | 0.5 | 20 | 43,344 |
| 09-26 12:38:50 | `loom_clack` | 4 | 0.5 | 20 | 43,364 |
| 09-26 12:38:50 | `loom_clack` | 3 | 0.5 | 20 | 43,384 |
| 09-26 12:38:50 | `pot_stir` | 0 | 1.0 | 40 | 43,424 |
| 09-26 12:38:50 | `pot_stir` | 1 | 1.0 | 40 | 43,464 |
| 09-26 12:38:51 | `pot_stir` | 3 | 1.0 | 40 | 43,504 |
| 09-26 12:38:51 | `pot_stir` | 2 | 1.0 | 40 | 43,544 |
| 09-26 12:38:52 | `feather_pinch` | 0 | 0.5 | 20 | 43,564 |
| 09-26 12:38:52 | `feather_pinch` | 1 | 0.5 | 20 | 43,584 |
| 09-26 12:38:53 | `feather_pinch` | 2 | 0.5 | 20 | 43,604 |
| 09-26 12:38:53 | `feather_pinch` | 3 | 0.5 | 20 | 43,624 |
| 09-26 12:38:53 | `hide_scrape` | 0 | 0.7 | 28 | 43,652 |
| 09-26 12:38:54 | `hide_scrape` | 1 | 0.7 | 28 | 43,680 |
| 09-26 12:38:55 | `hide_scrape` | 2 | 0.7 | 28 | 43,708 |
| 09-26 12:38:55 | `hide_scrape` | 3 | 0.7 | 28 | 43,736 |
| 09-26 12:38:55 | `farmer_work` | 0 | 0.5 | 20 | 43,756 |
| 09-26 12:38:55 | `farmer_work` | 1 | 0.5 | 20 | 43,776 |
| 09-26 12:38:56 | `farmer_work` | 2 | 0.5 | 20 | 43,796 |
| 09-26 12:38:56 | `farmer_work` | 3 | 0.5 | 20 | 43,816 |
| 09-26 12:38:56 | `farmer_work` | 4 | 0.5 | 20 | 43,836 |
| 09-26 12:38:57 | `farmer_work` | 5 | 0.5 | 20 | 43,856 |
| 09-26 12:38:58 | `seed_press` | 1 | 0.5 | 20 | 43,876 |
| 09-26 12:38:58 | `seed_press` | 0 | 0.5 | 20 | 43,896 |
| 09-26 12:38:58 | `seed_press` | 2 | 0.5 | 20 | 43,916 |
| 09-26 12:38:58 | `seed_press` | 3 | 0.5 | 20 | 43,936 |
| 09-26 12:38:59 | `crop_pull` | 0 | 0.5 | 20 | 43,956 |
| 09-26 12:38:59 | `crop_pull` | 1 | 0.5 | 20 | 43,976 |
| 09-26 12:39:00 | `crop_pull` | 2 | 0.5 | 20 | 43,996 |
| 09-26 12:39:00 | `crop_pull` | 3 | 0.5 | 20 | 44,016 |
| 09-26 12:39:01 | `crop_pull` | 4 | 0.5 | 20 | 44,036 |
| 09-26 12:39:01 | `oven_slide` | 0 | 0.8 | 32 | 44,068 |
| 09-26 12:39:01 | `oven_slide` | 1 | 0.8 | 32 | 44,100 |
| 09-26 12:39:02 | `oven_slide` | 2 | 0.8 | 32 | 44,132 |
| 09-26 12:39:02 | `oven_slide` | 3 | 0.8 | 32 | 44,164 |
| 09-26 12:39:02 | `water_pour` | 0 | 1.0 | 40 | 44,204 |
| 09-26 12:39:03 | `water_pour` | 1 | 1.0 | 40 | 44,244 |
| 09-26 12:39:03 | `water_pour` | 2 | 1.0 | 40 | 44,284 |
| 09-26 12:39:04 | `event.dog_bark` | 0 | 0.5 | 20 | 44,304 |
| 09-26 12:39:04 | `water_pour` | 3 | 1.0 | 40 | 44,344 |
| 09-26 12:39:04 | `event.dog_bark` | 1 | 0.5 | 20 | 44,364 |
| 09-26 12:39:05 | `event.dog_bark` | 2 | 0.5 | 20 | 44,384 |
| 09-26 12:39:06 | `event.dog_bark` | 3 | 0.5 | 20 | 44,404 |
| 09-26 12:39:06 | `event.dog_bark` | 4 | 0.5 | 20 | 44,424 |
| 09-26 12:39:06 | `event.dog_whine` | 0 | 0.8 | 32 | 44,456 |
| 09-26 12:39:07 | `event.dog_whine` | 1 | 0.8 | 32 | 44,488 |
| 09-26 12:39:07 | `event.dog_whine` | 2 | 0.8 | 32 | 44,520 |
| 09-26 12:39:07 | `event.dog_whine` | 3 | 0.8 | 32 | 44,552 |
| 09-26 12:39:08 | `event.caravan_arrive` | 0 | 3.0 | 120 | 44,672 |
| 09-26 12:39:09 | `event.caravan_arrive` | 2 | 3.0 | 120 | 44,792 |
| 09-26 12:39:09 | `event.caravan_arrive` | 3 | 3.0 | 120 | 44,912 |
| 09-26 12:39:09 | `event.caravan_arrive` | 1 | 3.0 | 120 | 45,032 |
| 09-26 12:39:10 | `event.wolf_howl` | 0 | 3.0 | 120 | 45,152 |
| 09-26 12:39:11 | `event.wolf_howl` | 1 | 3.0 | 120 | 45,272 |
| 09-26 12:39:11 | `event.wolf_howl` | 3 | 3.0 | 120 | 45,392 |
| 09-26 12:39:11 | `event.wolf_howl` | 2 | 3.0 | 120 | 45,512 |
| 09-26 12:39:12 | `event.boar_grunt` | 0 | 0.8 | 32 | 45,544 |
| 09-26 12:39:13 | `event.boar_grunt` | 1 | 0.8 | 32 | 45,576 |
| 09-26 12:39:13 | `event.boar_grunt` | 2 | 0.8 | 32 | 45,608 |
| 09-26 12:39:13 | `event.boar_grunt` | 3 | 0.8 | 32 | 45,640 |
| 09-26 12:39:13 | `event.boar_charge` | 0 | 1.0 | 40 | 45,680 |
| 09-26 12:39:14 | `event.boar_charge` | 1 | 1.0 | 40 | 45,720 |
| 09-26 12:39:14 | `event.boar_charge` | 2 | 1.0 | 40 | 45,760 |
| 09-26 12:39:15 | `event.boar_charge` | 3 | 1.0 | 40 | 45,800 |
| 09-26 12:39:16 | `event.envoy_fanfare` | 0 | 3.0 | 120 | 45,920 |
| 09-26 12:39:16 | `event.envoy_fanfare` | 2 | 3.0 | 120 | 46,040 |
| 09-26 12:39:16 | `event.envoy_fanfare` | 1 | 3.0 | 120 | 46,160 |
| 09-26 12:39:16 | `event.envoy_fanfare` | 3 | 3.0 | 120 | 46,280 |
| 09-26 12:39:17 | `village_bell` | 0 | 4.0 | 160 | 46,440 |
| 09-26 12:39:18 | `village_bell` | 1 | 4.0 | 160 | 46,600 |
| 09-26 12:39:18 | `village_bell` | 2 | 4.0 | 160 | 46,760 |
| 09-26 12:39:18 | `village_bell` | 3 | 4.0 | 160 | 46,920 |
| 09-26 12:39:19 | `settler.hurt` | 0 | 0.5 | 20 | 46,940 |
| 09-26 12:39:19 | `settler.hurt` | 1 | 0.5 | 20 | 46,960 |
| 09-26 12:39:20 | `settler.hurt` | 2 | 0.5 | 20 | 46,980 |
| 09-26 12:39:20 | `settler.hurt` | 3 | 0.5 | 20 | 47,000 |
| 09-26 12:39:21 | `settler.hurt` | 4 | 0.5 | 20 | 47,020 |
| 09-26 12:39:23 | `ambient.village_murmur` | 0 | 10.0 | 400 | 47,420 |
| 09-26 12:39:24 | `ambient.village_murmur` | 1 | 10.0 | 400 | 47,820 |
| 09-26 12:39:24 | `ambient.village_murmur` | 2 | 10.0 | 400 | 48,220 |
| 09-26 12:39:25 | `ambient.workshop_smithy` | 0 | 8.0 | 320 | 48,540 |
| 09-26 12:39:25 | `work.plate_hammer` | 0 | 0.5 | 20 | 48,560 |
| 09-26 12:39:26 | `work.plate_hammer` | 1 | 0.5 | 20 | 48,580 |
| 09-26 12:39:26 | `work.plate_hammer` | 2 | 0.5 | 20 | 48,600 |
| 09-26 12:39:27 | `work.plate_hammer` | 3 | 0.5 | 20 | 48,620 |
| 09-26 12:39:27 | `ambient.workshop_smithy` | 1 | 8.0 | 320 | 48,940 |
| 09-26 12:39:27 | `work.plate_hammer` | 4 | 0.5 | 20 | 48,960 |
| 09-26 12:39:28 | `work.quern_grind` | 0 | 0.9 | 36 | 48,996 |
| 09-26 12:39:28 | `work.quern_grind` | 1 | 0.9 | 36 | 49,032 |
| 09-26 12:39:28 | `work.quern_grind` | 2 | 0.9 | 36 | 49,068 |
| 09-26 12:39:29 | `work.quern_grind` | 3 | 0.9 | 36 | 49,104 |
| 09-26 12:39:29 | `work.mash_stir` | 0 | 0.9 | 36 | 49,140 |
| 09-26 12:39:30 | `work.mash_stir` | 1 | 0.9 | 36 | 49,176 |
| 09-26 12:39:30 | `work.mash_stir` | 2 | 0.9 | 36 | 49,212 |
| 09-26 12:39:30 | `work.mash_stir` | 3 | 0.9 | 36 | 49,248 |
| 09-26 12:39:31 | `work.quill_scratch` | 0 | 0.6 | 24 | 49,272 |
| 09-26 12:39:31 | `work.quill_scratch` | 1 | 0.6 | 24 | 49,296 |
| 09-26 12:39:32 | `work.quill_scratch` | 2 | 0.6 | 24 | 49,320 |
| 09-26 12:39:32 | `work.quill_scratch` | 3 | 0.6 | 24 | 49,344 |
| 09-26 12:39:33 | `work.quill_scratch` | 4 | 0.6 | 24 | 49,368 |
| 09-26 12:39:33 | `work.pestle_grind` | 0 | 0.7 | 28 | 49,396 |
| 09-26 12:39:33 | `work.pestle_grind` | 1 | 0.7 | 28 | 49,424 |
| 09-26 12:39:34 | `work.pestle_grind` | 2 | 0.7 | 28 | 49,452 |
| 09-26 12:39:34 | `work.pestle_grind` | 3 | 0.7 | 28 | 49,480 |
| 09-26 12:39:35 | `work.shear_snip` | 0 | 0.5 | 20 | 49,500 |
| 09-26 12:39:35 | `work.shear_snip` | 1 | 0.5 | 20 | 49,520 |
| 09-26 12:39:36 | `work.shear_snip` | 2 | 0.5 | 20 | 49,540 |
| 09-26 12:39:36 | `work.shear_snip` | 3 | 0.5 | 20 | 49,560 |
| 09-26 12:39:36 | `work.shear_snip` | 4 | 0.5 | 20 | 49,580 |
| 09-26 12:39:37 | `work.fish_splash` | 0 | 0.8 | 32 | 49,612 |
| 09-26 12:39:38 | `work.fish_splash` | 1 | 0.8 | 32 | 49,644 |
| 09-26 12:39:38 | `work.fish_splash` | 2 | 0.8 | 32 | 49,676 |
| 09-26 12:39:38 | `work.fish_splash` | 3 | 0.8 | 32 | 49,708 |
| 09-26 12:39:38 | `work.bow_loose` | 0 | 0.5 | 20 | 49,728 |
| 09-26 12:39:39 | `work.bow_loose` | 2 | 0.5 | 20 | 49,748 |
| 09-26 12:39:40 | `work.bow_loose` | 1 | 0.5 | 20 | 49,768 |
| 09-26 12:39:40 | `work.ledger_tally` | 0 | 0.6 | 24 | 49,792 |
| 09-26 12:39:40 | `work.bow_loose` | 3 | 0.5 | 20 | 49,812 |
| 09-26 12:39:41 | `work.ledger_tally` | 2 | 0.6 | 24 | 49,836 |
| 09-26 12:39:41 | `work.ledger_tally` | 3 | 0.6 | 24 | 49,860 |
| 09-26 12:39:42 | `work.bar_wipe` | 0 | 0.6 | 24 | 49,884 |
| 09-26 12:39:43 | `work.bar_wipe` | 2 | 0.6 | 24 | 49,908 |
| 09-26 12:39:43 | `work.bar_wipe` | 1 | 0.6 | 24 | 49,932 |
| 09-26 12:39:43 | `work.bar_wipe` | 3 | 0.6 | 24 | 49,956 |
| 09-26 12:39:44 | `work.ledger_tally` | 1 | 0.6 | 24 | 49,980 |
| 09-26 12:39:44 | `builder.ladder_rung` | 0 | 0.5 | 20 | 50,000 |
| 09-26 12:39:45 | `builder.ladder_rung` | 2 | 0.5 | 20 | 50,020 |
| 09-26 12:39:45 | `builder.ladder_rung` | 1 | 0.5 | 20 | 50,040 |
| 09-26 12:39:46 | `builder.ladder_rung` | 3 | 0.5 | 20 | 50,060 |
| 09-26 12:39:46 | `patrol.march` | 0 | 1.2 | 48 | 50,108 |
| 09-26 12:39:47 | `patrol.march` | 1 | 1.2 | 48 | 50,156 |
| 09-26 12:39:47 | `patrol.march` | 2 | 1.2 | 48 | 50,204 |
| 09-26 12:39:47 | `patrol.march` | 3 | 1.2 | 48 | 50,252 |
| 09-26 12:39:48 | `patrol.halt` | 0 | 0.8 | 32 | 50,284 |
| 09-26 12:39:48 | `patrol.halt` | 2 | 0.8 | 32 | 50,316 |
| 09-26 12:39:48 | `patrol.halt` | 1 | 0.8 | 32 | 50,348 |
| 09-26 12:39:49 | `patrol.halt` | 3 | 0.8 | 32 | 50,380 |
| 09-26 12:39:49 | `summon.horn` | 0 | 2.0 | 80 | 50,460 |
| 09-26 12:39:50 | `summon.horn` | 1 | 2.0 | 80 | 50,540 |
| 09-26 12:39:50 | `summon.horn` | 2 | 2.0 | 80 | 50,620 |
| 09-26 12:39:51 | `summon.horn` | 3 | 2.0 | 80 | 50,700 |
| 09-26 12:39:51 | `command_ack.spear` | 0 | 0.9 | 36 | 50,736 |
| 09-26 12:39:51 | `command_ack.spear` | 1 | 0.9 | 36 | 50,772 |
| 09-26 12:39:52 | `command_ack.spear` | 2 | 0.9 | 36 | 50,808 |
| 09-26 12:39:52 | `command_ack.spear` | 3 | 0.9 | 36 | 50,844 |
| 09-26 12:39:53 | `command_ack.archer` | 0 | 0.9 | 36 | 50,880 |
| 09-26 12:39:53 | `command_ack.archer` | 1 | 0.9 | 36 | 50,916 |
| 09-26 12:39:53 | `command_ack.archer` | 2 | 0.9 | 36 | 50,952 |
| 09-26 12:39:54 | `command_ack.archer` | 3 | 0.9 | 36 | 50,988 |
| 09-26 12:39:54 | `command_ack.mage` | 0 | 1.0 | 40 | 51,028 |
| 09-26 12:39:54 | `command_ack.mage` | 1 | 1.0 | 40 | 51,068 |
| 09-26 12:39:55 | `event.brute_grunt` | 0 | 1.0 | 40 | 51,108 |
| 09-26 12:39:56 | `command_ack.mage` | 2 | 1.0 | 40 | 51,148 |
| 09-26 12:39:56 | `event.brute_grunt` | 1 | 1.0 | 40 | 51,188 |
| 09-26 12:39:56 | `event.brute_grunt` | 2 | 1.0 | 40 | 51,228 |
| 09-26 12:39:57 | `event.brute_grunt` | 3 | 1.0 | 40 | 51,268 |
| 09-26 12:39:57 | `event.brute_demand` | 0 | 1.6 | 64 | 51,332 |
| 09-26 12:39:57 | `event.brute_demand` | 1 | 1.6 | 64 | 51,396 |
| 09-26 12:39:58 | `event.brute_demand` | 2 | 1.6 | 64 | 51,460 |
| 09-26 12:39:59 | `event.brute_demand` | 3 | 1.6 | 64 | 51,524 |
| 09-26 12:39:59 | `event.fox_yip` | 0 | 0.6 | 24 | 51,548 |
| 09-26 12:39:59 | `event.fox_yip` | 1 | 0.6 | 24 | 51,572 |
| 09-26 12:39:59 | `event.fox_yip` | 2 | 0.6 | 24 | 51,596 |
| 09-26 12:40:00 | `event.peddler_bells` | 1 | 2.0 | 80 | 51,676 |
| 09-26 12:40:00 | `event.peddler_bells` | 0 | 2.0 | 80 | 51,756 |
| 09-26 12:40:01 | `event.peddler_bells` | 2 | 2.0 | 80 | 51,836 |
| 09-26 12:40:01 | `event.peddler_bells` | 3 | 2.0 | 80 | 51,916 |
| 09-26 12:40:02 | `event.minstrel_sting` | 0 | 3.0 | 120 | 52,036 |
| 09-26 12:40:02 | `event.minstrel_sting` | 1 | 3.0 | 120 | 52,156 |
| 09-26 12:40:03 | `event.minstrel_sting` | 2 | 3.0 | 120 | 52,276 |
| 09-26 12:40:03 | `event.minstrel_sting` | 3 | 3.0 | 120 | 52,396 |
| 09-26 12:40:04 | `settler.death` | 0 | 0.9 | 36 | 52,432 |
| 09-26 12:40:04 | `settler.death` | 1 | 0.9 | 36 | 52,468 |
| 09-26 12:40:04 | `settler.death` | 2 | 0.9 | 36 | 52,504 |
| 09-26 12:40:06 | `ambient.night_crickets` | 0 | 10.0 | 400 | 52,904 |
| 09-26 12:40:08 | `ambient.night_crickets` | 1 | 10.0 | 400 | 53,304 |
| 09-26 12:40:08 | `ambient.market_bustle` | 0 | 10.0 | 400 | 53,704 |
| 09-26 12:40:08 | `ambient.market_bustle` | 1 | 10.0 | 400 | 54,104 |
| 09-26 12:40:10 | `ambient.rain_roof` | 0 | 10.0 | 400 | 54,504 |
| 09-26 12:40:12 | `ambient.rain_roof` | 1 | 10.0 | 400 | 54,904 |
| 09-26 12:40:12 | `captain.windup` | 0 | 0.8 | 32 | 54,936 |
| 09-26 12:40:12 | `ambient.workshop_wood` | 1 | 8.0 | 320 | 55,256 |
| 09-26 12:40:13 | `ambient.workshop_wood` | 0 | 8.0 | 320 | 55,576 |
| 09-26 12:40:13 | `captain.windup` | 1 | 0.8 | 32 | 55,608 |
| 09-26 12:40:13 | `captain.windup` | 2 | 0.8 | 32 | 55,640 |
| 09-26 12:40:13 | `captain.windup` | 3 | 0.8 | 32 | 55,672 |
| 09-26 12:40:14 | `captain.impact` | 0 | 0.9 | 36 | 55,708 |
| 09-26 12:40:15 | `captain.impact` | 1 | 0.9 | 36 | 55,744 |
| 09-26 12:40:15 | `captain.impact` | 3 | 0.9 | 36 | 55,780 |
| 09-26 12:40:15 | `captain.impact` | 2 | 0.9 | 36 | 55,816 |
| 09-26 12:40:16 | `captain.rally` | 0 | 2.5 | 100 | 55,916 |
| 09-26 12:40:17 | `captain.rally` | 2 | 2.5 | 100 | 56,016 |
| 09-26 12:40:17 | `captain.rally` | 3 | 2.5 | 100 | 56,116 |
| 09-26 12:40:17 | `captain.rally` | 1 | 2.5 | 100 | 56,216 |
| 09-26 12:40:18 | `captain.promoted` | 0 | 3.0 | 120 | 56,336 |
| 09-26 12:40:19 | `captain.promoted` | 1 | 3.0 | 120 | 56,456 |
| 09-26 12:40:19 | `captain.promoted` | 2 | 3.0 | 120 | 56,576 |
| 09-26 12:40:19 | `captain.promoted` | 3 | 3.0 | 120 | 56,696 |
| 09-26 12:43:58 | `music.day_hearth` | 1 | 150 | 2250 | 58,946 |
| 09-26 12:43:59 | `music.day_meadow` | 1 | 150 | 2250 | 61,196 |
| 09-26 12:45:52 | `music.day_meadow` | 2 | 150 | 2250 | 63,446 |
| 09-26 12:45:54 | `music.day_hearth` | 2 | 150 | 2250 | 65,696 |
| 09-26 12:46:05 | `music.day_harvest` | 1 | 140 | 2100 | 67,796 |
| 09-26 12:46:06 | `music.day_harvest` | 2 | 140 | 2100 | 69,896 |
| 09-26 12:46:20 | `music.day_banner` | 1 | 150 | 2250 | 72,146 |
| 09-26 12:46:22 | `music.day_banner` | 2 | 150 | 2250 | 74,396 |
| 09-26 12:46:35 | `music.night_embers` | 1 | 150 | 2250 | 76,646 |
| 09-26 12:46:37 | `music.night_embers` | 2 | 150 | 2250 | 78,896 |
| 09-26 12:46:48 | `music.night_watch` | 1 | 140 | 2100 | 80,996 |
| 09-26 12:46:51 | `music.night_watch` | 2 | 140 | 2100 | 83,096 |
| 09-26 12:47:01 | `music.tavern_jig` | 1 | 90 | 1350 | 84,446 |
| 09-26 12:47:13 | `music.raid` | 1 | 120 | 1800 | 86,246 |
| 09-26 12:47:14 | `music.raid` | 2 | 120 | 1800 | 88,046 |
| 09-26 12:47:17 | `music.raid_victory` | 1 | 10 | 150 | 88,196 |
| 09-26 12:47:18 | `music.raid_victory` | 2 | 10 | 150 | 88,346 |
| 09-26 12:47:22 | `music.raid_defeat` | 1 | 20 | 300 | 88,646 |
| 09-26 12:47:24 | `music.raid_defeat` | 2 | 20 | 300 | 88,946 |
| 09-26 12:47:38 | `music.title` | 2 | 150 | 2250 | 91,196 |
| 09-26 12:47:38 | `music.title` | 1 | 150 | 2250 | 93,446 |
| 09-26 12:47:56 | `voice_pilot.traveller` | 0 | 0 | 131 | 93,577 |
| 09-26 12:48:03 | `voice_pilot.young_woman` | 0 | 0 | 149 | 93,726 |
| 09-26 12:48:09 | `voice_pilot.old_man` | 0 | 0 | 146 | 93,872 |
| 09-26 12:48:15 | `voice_pilot.brute` | 0 | 0 | 128 | 94,000 |
| 09-26 12:48:21 | `voice_pilot.captain` | 0 | 0 | 103 | 94,103 |
| 09-26 12:48:29 | `_tts_label` | 0 | 0 | 24 | 94,127 |
| 09-26 12:48:36 | `_tts_label` | 0 | 0 | 22 | 94,149 |
| 09-26 12:48:44 | `_tts_label` | 0 | 0 | 31 | 94,180 |
| 09-26 12:49:00 | `_tts_label` | 0 | 0 | 23 | 94,203 |
| 09-26 12:53:26 | `_tts_label` | 0 | 0 | 6 | 94,209 |
| 09-26 12:53:31 | `_tts_label` | 0 | 0 | 9 | 94,218 |
| 09-26 12:57:06 | `dialogue.voice_design.minstrel` | 0 | 0 | 147 | 94,365 |
| 09-26 12:57:14 | `dialogue.voice_design.envoy` | 0 | 0 | 147 | 94,512 |
| 09-26 12:57:23 | `dialogue.voice_design.merchant` | 0 | 0 | 147 | 94,659 |
| 09-26 12:57:28 | `dialogue.traveller.intro` | 0 | 0 | 48 | 94,707 |
| 09-26 12:57:28 | `dialogue.traveller.intro` | 0 | 0 | 48 | 94,755 |
| 09-26 12:57:32 | `dialogue.refugees.line1` | 0 | 0 | 55 | 94,810 |
| 09-26 12:57:33 | `dialogue.refugees.line1` | 0 | 0 | 55 | 94,865 |
| 09-26 12:57:39 | `dialogue.demo_peddler.haggle_no` | 0 | 0 | 42 | 94,907 |
| 09-26 12:57:40 | `dialogue.demo_peddler.haggle_no` | 0 | 0 | 42 | 94,949 |
| 09-26 12:57:44 | `dialogue.brute_toll.demand` | 0 | 0 | 40 | 94,989 |
| 09-26 12:57:44 | `dialogue.brute_toll.demand` | 0 | 0 | 40 | 95,029 |
| 09-26 12:57:48 | `dialogue.parley.reply.truce_yes` | 0 | 0 | 52 | 95,081 |
| 09-26 12:57:48 | `dialogue.parley.reply.truce_yes` | 0 | 0 | 52 | 95,133 |
| 09-26 12:57:52 | `dialogue.minstrels.line1` | 0 | 0 | 92 | 95,225 |
| 09-26 12:57:53 | `dialogue.minstrels.line1` | 0 | 0 | 92 | 95,317 |
| 09-26 12:57:58 | `dialogue.rival_envoy.line2` | 0 | 0 | 31 | 95,348 |
| 09-26 12:57:59 | `dialogue.rival_envoy.line2` | 0 | 0 | 31 | 95,379 |
| 09-26 12:58:01 | `dialogue.caravan.line1` | 0 | 0 | 55 | 95,434 |
| 09-26 12:58:02 | `dialogue.caravan.line1` | 0 | 0 | 55 | 95,489 |
| 09-26 12:58:06 | `_tts_label` | 0 | 0 | 14 | 95,503 |
| 09-26 12:58:07 | `_tts_label` | 0 | 0 | 16 | 95,519 |
| 09-26 12:58:09 | `_tts_label` | 0 | 0 | 19 | 95,538 |
| 09-26 12:58:11 | `_tts_label` | 0 | 0 | 13 | 95,551 |
| 09-26 12:58:13 | `_tts_label` | 0 | 0 | 21 | 95,572 |
| 09-26 12:59:18 | `dialogue.refugees.line2` | 0 | 0 | 58 | 95,630 |
| 09-26 12:59:18 | `dialogue.refugees.line2` | 0 | 0 | 58 | 95,688 |
| 09-26 12:59:21 | `dialogue.refugees.work` | 0 | 0 | 37 | 95,725 |
| 09-26 12:59:22 | `dialogue.refugees.work` | 0 | 0 | 37 | 95,762 |
| 09-26 12:59:29 | `dialogue.minstrels.line2` | 0 | 0 | 72 | 95,834 |
| 09-26 12:59:30 | `dialogue.minstrels.line2` | 0 | 0 | 72 | 95,906 |
| 09-26 12:59:36 | `dialogue.brute_toll.demand2` | 0 | 0 | 68 | 95,974 |
| 09-26 12:59:37 | `dialogue.brute_toll.demand2` | 0 | 0 | 68 | 96,042 |
| 09-26 12:59:41 | `dialogue.brute_toll.talk` | 0 | 0 | 45 | 96,087 |
| 09-26 12:59:42 | `dialogue.brute_toll.talk` | 0 | 0 | 45 | 96,132 |
| 09-26 12:59:45 | `dialogue.peddler.line1` | 0 | 0 | 62 | 96,194 |
| 09-26 12:59:46 | `dialogue.peddler.line1` | 0 | 0 | 62 | 96,256 |
| 09-26 12:59:49 | `dialogue.barter.greet` | 0 | 0 | 25 | 96,281 |
| 09-26 12:59:50 | `dialogue.barter.greet` | 0 | 0 | 25 | 96,306 |
| 09-26 12:59:52 | `dialogue.parley.intro` | 0 | 0 | 43 | 96,349 |
| 09-26 12:59:53 | `dialogue.parley.intro` | 0 | 0 | 43 | 96,392 |
| 09-26 12:59:56 | `dialogue.parley.demand` | 0 | 0 | 96 | 96,488 |
| 09-26 12:59:57 | `dialogue.parley.demand` | 0 | 0 | 96 | 96,584 |
| 09-26 13:00:01 | `dialogue.parley.reply.tribute` | 0 | 0 | 49 | 96,633 |
| 09-26 13:00:02 | `dialogue.parley.reply.tribute` | 0 | 0 | 49 | 96,682 |
| 09-26 13:00:07 | `dialogue.parley.reply.truce_no` | 0 | 0 | 34 | 96,716 |
| 09-26 13:00:08 | `dialogue.parley.reply.truce_no` | 0 | 0 | 34 | 96,750 |
| 09-26 13:00:11 | `dialogue.parley.reply.duel` | 0 | 0 | 60 | 96,810 |
| 09-26 13:00:12 | `dialogue.parley.reply.duel` | 0 | 0 | 60 | 96,870 |
| 09-26 13:00:15 | `dialogue.parley.reply.refuse` | 0 | 0 | 9 | 96,879 |
| 09-26 13:00:15 | `dialogue.parley.reply.refuse` | 0 | 0 | 9 | 96,888 |
| 09-26 13:00:18 | `dialogue.traveller.greet` | 0 | 0 | 70 | 96,958 |
| 09-26 13:00:19 | `dialogue.traveller.greet` | 0 | 0 | 70 | 97,028 |
| 09-26 13:00:21 | `dialogue.traveller.thanks` | 0 | 0 | 47 | 97,075 |
| 09-26 13:00:22 | `dialogue.traveller.thanks` | 0 | 0 | 47 | 97,122 |
| 09-26 13:00:24 | `dialogue.traveller.shrug` | 0 | 0 | 26 | 97,148 |
| 09-26 13:00:25 | `dialogue.traveller.shrug` | 0 | 0 | 26 | 97,174 |
| 09-26 13:00:26 | `dialogue.traveller.news` | 0 | 0 | 77 | 97,251 |
| 09-26 13:00:27 | `dialogue.traveller.news` | 0 | 0 | 77 | 97,328 |
| 09-26 13:00:29 | `dialogue.traveller.bye` | 0 | 0 | 39 | 97,367 |
| 09-26 13:00:29 | `dialogue.traveller.bye` | 0 | 0 | 39 | 97,406 |
| 09-26 13:00:31 | `dialogue.demo_peddler.intro` | 0 | 0 | 51 | 97,457 |
| 09-26 13:00:31 | `dialogue.demo_peddler.intro` | 0 | 0 | 51 | 97,508 |
| 09-26 13:00:33 | `dialogue.demo_peddler.greet` | 0 | 0 | 89 | 97,597 |
| 09-26 13:00:34 | `dialogue.demo_peddler.greet` | 0 | 0 | 89 | 97,686 |
| 09-26 13:00:36 | `dialogue.demo_peddler.haggle_yes` | 0 | 0 | 45 | 97,731 |
| 09-26 13:00:37 | `dialogue.demo_peddler.haggle_yes` | 0 | 0 | 45 | 97,776 |
| 09-26 13:00:42 | `dialogue.caravan.line2` | 0 | 0 | 92 | 97,868 |
| 09-26 13:00:43 | `dialogue.caravan.line2` | 0 | 0 | 92 | 97,960 |
| 09-26 13:00:46 | `dialogue.rival_envoy.line1` | 0 | 0 | 82 | 98,042 |
| 09-26 13:00:47 | `dialogue.rival_envoy.line1` | 0 | 0 | 82 | 98,124 |
| 09-26 13:00:50 | `dialogue.parley.farewell.tribute` | 0 | 0 | 34 | 98,158 |
| 09-26 13:00:51 | `dialogue.parley.farewell.tribute` | 0 | 0 | 34 | 98,192 |
| 09-26 13:00:53 | `dialogue.parley.farewell.truce` | 0 | 0 | 45 | 98,237 |
| 09-26 13:00:54 | `dialogue.parley.farewell.truce` | 0 | 0 | 45 | 98,282 |
| 09-26 13:00:56 | `dialogue.parley.farewell.duel` | 0 | 0 | 22 | 98,304 |
| 09-26 13:00:56 | `dialogue.parley.farewell.duel` | 0 | 0 | 22 | 98,326 |
| 09-26 13:01:20 | `tavern.chuckle` | 0 | 0.8 | 32 | 98,358 |
| 09-26 13:01:21 | `tavern.chuckle` | 1 | 0.8 | 32 | 98,390 |
| 09-26 13:01:22 | `tavern.chuckle` | 2 | 0.8 | 32 | 98,422 |
| 09-26 13:01:23 | `tavern.chuckle` | 3 | 0.8 | 32 | 98,454 |
| 09-26 13:01:23 | `tavern.heh` | 0 | 0.5 | 20 | 98,474 |
| 09-26 13:01:24 | `tavern.heh` | 1 | 0.5 | 20 | 98,494 |
| 09-26 13:01:25 | `tavern.heh` | 2 | 0.5 | 20 | 98,514 |
| 09-26 13:01:26 | `tavern.heh` | 3 | 0.5 | 20 | 98,534 |
| 09-26 13:01:26 | `tavern.table_slap` | 0 | 0.5 | 20 | 98,554 |
| 09-26 13:01:28 | `tavern.table_slap` | 1 | 0.5 | 20 | 98,574 |
| 09-26 13:01:28 | `tavern.table_slap` | 2 | 0.5 | 20 | 98,594 |
| 09-26 13:01:29 | `tavern.table_slap` | 3 | 0.5 | 20 | 98,614 |
| 09-26 13:01:29 | `tavern.seat_creak` | 0 | 0.6 | 24 | 98,638 |
| 09-26 13:01:31 | `tavern.seat_creak` | 2 | 0.6 | 24 | 98,662 |
| 09-26 13:01:31 | `tavern.seat_creak` | 1 | 0.6 | 24 | 98,686 |
| 09-26 13:01:32 | `tavern.cloth_rustle` | 0 | 0.5 | 20 | 98,706 |
| 09-26 13:01:32 | `tavern.seat_creak` | 3 | 0.6 | 24 | 98,730 |
| 09-26 13:01:34 | `tavern.cloth_rustle` | 1 | 0.5 | 20 | 98,750 |
| 09-26 13:01:34 | `tavern.cloth_rustle` | 2 | 0.5 | 20 | 98,770 |
| 09-26 13:01:36 | `tavern.cloth_rustle` | 3 | 0.5 | 20 | 98,790 |
| 09-26 13:01:36 | `tavern.tap_valve` | 0 | 0.5 | 20 | 98,810 |
| 09-26 13:01:37 | `tavern.tap_valve` | 1 | 0.5 | 20 | 98,830 |
| 09-26 13:01:37 | `tavern.tap_valve` | 2 | 0.5 | 20 | 98,850 |
| 09-26 13:01:39 | `tavern.tap_valve` | 3 | 0.5 | 20 | 98,870 |
| 09-26 13:01:39 | `tavern.bar_creak` | 0 | 0.6 | 24 | 98,894 |
| 09-26 13:01:40 | `tavern.bar_creak` | 1 | 0.6 | 24 | 98,918 |
| 09-26 13:01:40 | `tavern.bar_creak` | 2 | 0.6 | 24 | 98,942 |
| 09-26 13:01:42 | `tavern.bar_creak` | 3 | 0.6 | 24 | 98,966 |
| 09-26 13:01:42 | `tavern.jig_step` | 0 | 0.5 | 20 | 98,986 |
| 09-26 13:01:43 | `tavern.jig_step` | 1 | 0.5 | 20 | 99,006 |
| 09-26 13:01:43 | `tavern.jig_step` | 2 | 0.5 | 20 | 99,026 |
| 09-26 13:01:45 | `tavern.jig_step` | 3 | 0.5 | 20 | 99,046 |
| 09-26 13:01:45 | `tavern.jig_step` | 4 | 0.5 | 20 | 99,066 |
| 09-26 13:01:46 | `brawl.punch_hit` | 0 | 0.5 | 20 | 99,086 |
| 09-26 13:01:47 | `brawl.punch_hit` | 1 | 0.5 | 20 | 99,106 |
| 09-26 13:01:48 | `brawl.punch_hit` | 2 | 0.5 | 20 | 99,126 |
| 09-26 13:01:49 | `brawl.punch_hit` | 3 | 0.5 | 20 | 99,146 |
| 09-26 13:01:50 | `brawl.grunt` | 0 | 0.5 | 20 | 99,166 |
| 09-26 13:01:50 | `brawl.grunt` | 1 | 0.5 | 20 | 99,186 |
| 09-26 13:01:51 | `brawl.grunt` | 2 | 0.5 | 20 | 99,206 |
| 09-26 13:01:52 | `brawl.grunt` | 3 | 0.5 | 20 | 99,226 |
| 09-26 13:01:53 | `tavern.hiccup` | 0 | 0.5 | 20 | 99,246 |
| 09-26 13:01:54 | `tavern.hiccup` | 1 | 0.5 | 20 | 99,266 |
| 09-26 13:01:54 | `tavern.hiccup` | 2 | 0.5 | 20 | 99,286 |
| 09-26 13:01:55 | `tavern.hiccup` | 3 | 0.5 | 20 | 99,306 |
| 09-26 13:01:56 | `tavern.hiccup` | 4 | 0.5 | 20 | 99,326 |
| 09-26 13:01:57 | `tavern.burp` | 0 | 0.6 | 24 | 99,350 |
| 09-26 13:01:57 | `tavern.burp` | 1 | 0.6 | 24 | 99,374 |
| 09-26 13:01:58 | `tavern.burp` | 2 | 0.6 | 24 | 99,398 |
| 09-26 13:01:59 | `tavern.burp` | 3 | 0.6 | 24 | 99,422 |
| 09-26 13:05:03 | `drunk.scuff` | 1 | 0.5 | 20 | 99,442 |
| 09-26 13:05:03 | `drunk.scuff` | 0 | 0.5 | 20 | 99,462 |
| 09-26 13:05:04 | `drunk.scuff` | 2 | 0.5 | 20 | 99,482 |
| 09-26 13:05:05 | `drunk.scuff` | 3 | 0.5 | 20 | 99,502 |
| 09-26 13:05:06 | `drunk.thud` | 0 | 0.6 | 24 | 99,526 |
| 09-26 13:05:07 | `drunk.thud` | 1 | 0.6 | 24 | 99,550 |
| 09-26 13:05:07 | `drunk.thud` | 2 | 0.6 | 24 | 99,574 |
| 09-26 13:05:08 | `drunk.thud` | 3 | 0.6 | 24 | 99,598 |
| 09-26 13:05:09 | `drunk.groan` | 0 | 0.8 | 32 | 99,630 |
| 09-26 13:05:11 | `drunk.groan` | 2 | 0.8 | 32 | 99,662 |
| 09-26 13:05:11 | `drunk.groan` | 1 | 0.8 | 32 | 99,694 |
| 09-26 13:05:13 | `drunk.groan` | 3 | 0.8 | 32 | 99,726 |
| 09-26 13:06:36 | `music.tavern_jig` | 2 | 90 | 1350 | 101,076 |
| 09-26 13:11:03 | `dialogue.voice_design.caravan` | 0 | 0 | 147 | 101,223 |
| 09-26 13:11:29 | `dialogue.voice_design.peddler` | 0 | 0 | 147 | 101,370 |
| 09-26 13:11:50 | `dialogue.voice_design.captain_red` | 0 | 0 | 147 | 101,517 |
| 09-26 13:12:07 | `dialogue.voice_design.captain_torch` | 0 | 0 | 147 | 101,664 |
| 09-26 13:12:27 | `dialogue.voice_design.captain_reaper` | 0 | 0 | 147 | 101,811 |
| 09-26 13:13:12 | `dialogue.peddler.line1` | 0 | 0 | 62 | 101,873 |
| 09-26 13:13:13 | `dialogue.peddler.line1` | 0 | 0 | 62 | 101,935 |
| 09-26 13:13:23 | `dialogue.barter.greet` | 0 | 0 | 25 | 101,960 |
| 09-26 13:13:23 | `dialogue.barter.greet` | 0 | 0 | 25 | 101,985 |
| 09-26 13:13:31 | `dialogue.parley.intro.red` | 0 | 0 | 43 | 102,028 |
| 09-26 13:13:32 | `dialogue.parley.intro.red` | 0 | 0 | 43 | 102,071 |
| 09-26 13:13:36 | `dialogue.parley.intro.torch` | 0 | 0 | 43 | 102,114 |
| 09-26 13:13:37 | `dialogue.parley.intro.torch` | 0 | 0 | 43 | 102,157 |
| 09-26 13:13:42 | `dialogue.parley.intro.reaper` | 0 | 0 | 43 | 102,200 |
| 09-26 13:13:43 | `dialogue.parley.intro.reaper` | 0 | 0 | 43 | 102,243 |
| 09-26 13:13:53 | `dialogue.parley.demand.red` | 0 | 0 | 96 | 102,339 |
| 09-26 13:13:55 | `dialogue.parley.demand.red` | 0 | 0 | 96 | 102,435 |
| 09-26 13:14:02 | `dialogue.parley.demand.torch` | 0 | 0 | 96 | 102,531 |
| 09-26 13:14:04 | `dialogue.parley.demand.torch` | 0 | 0 | 96 | 102,627 |
| 09-26 13:14:19 | `dialogue.parley.demand.reaper` | 0 | 0 | 96 | 102,723 |
| 09-26 13:14:21 | `dialogue.parley.demand.reaper` | 0 | 0 | 96 | 102,819 |
| 09-26 13:14:32 | `dialogue.parley.reply.tribute.red` | 0 | 0 | 49 | 102,868 |
| 09-26 13:14:33 | `dialogue.parley.reply.tribute.red` | 0 | 0 | 49 | 102,917 |
| 09-26 13:14:37 | `dialogue.parley.reply.tribute.torch` | 0 | 0 | 49 | 102,966 |
| 09-26 13:14:38 | `dialogue.parley.reply.tribute.torch` | 0 | 0 | 49 | 103,015 |
| 09-26 13:14:43 | `dialogue.parley.reply.tribute.reaper` | 0 | 0 | 49 | 103,064 |
| 09-26 13:14:44 | `dialogue.parley.reply.tribute.reaper` | 0 | 0 | 49 | 103,113 |
| 09-26 13:14:52 | `dialogue.parley.reply.truce_yes.red` | 0 | 0 | 52 | 103,165 |
| 09-26 13:14:53 | `dialogue.parley.reply.truce_yes.red` | 0 | 0 | 52 | 103,217 |
| 09-26 13:14:57 | `dialogue.parley.reply.truce_yes.torch` | 0 | 0 | 52 | 103,269 |
| 09-26 13:14:59 | `dialogue.parley.reply.truce_yes.torch` | 0 | 0 | 52 | 103,321 |
| 09-26 13:15:07 | `dialogue.parley.reply.truce_yes.reaper` | 0 | 0 | 52 | 103,373 |
| 09-26 13:15:09 | `dialogue.parley.reply.truce_yes.reaper` | 0 | 0 | 52 | 103,425 |
| 09-26 13:15:17 | `dialogue.parley.reply.truce_no.red` | 0 | 0 | 34 | 103,459 |
| 09-26 13:15:18 | `dialogue.parley.reply.truce_no.red` | 0 | 0 | 34 | 103,493 |
| 09-26 13:15:21 | `dialogue.parley.reply.truce_no.torch` | 0 | 0 | 34 | 103,527 |
| 09-26 13:15:22 | `dialogue.parley.reply.truce_no.torch` | 0 | 0 | 34 | 103,561 |
| 09-26 13:15:26 | `dialogue.parley.reply.truce_no.reaper` | 0 | 0 | 34 | 103,595 |
| 09-26 13:15:27 | `dialogue.parley.reply.truce_no.reaper` | 0 | 0 | 34 | 103,629 |
| 09-26 13:15:33 | `dialogue.parley.reply.duel.red` | 0 | 0 | 60 | 103,689 |
| 09-26 13:15:34 | `dialogue.parley.reply.duel.red` | 0 | 0 | 60 | 103,749 |
| 09-26 13:15:40 | `dialogue.parley.reply.duel.torch` | 0 | 0 | 60 | 103,809 |
| 09-26 13:15:41 | `dialogue.parley.reply.duel.torch` | 0 | 0 | 60 | 103,869 |
| 09-26 13:15:46 | `dialogue.parley.reply.duel.reaper` | 0 | 0 | 60 | 103,929 |
| 09-26 13:15:47 | `dialogue.parley.reply.duel.reaper` | 0 | 0 | 60 | 103,989 |
| 09-26 13:15:53 | `dialogue.parley.reply.refuse.red` | 0 | 0 | 9 | 103,998 |
| 09-26 13:15:54 | `dialogue.parley.reply.refuse.red` | 0 | 0 | 9 | 104,007 |
| 09-26 13:15:56 | `dialogue.parley.reply.refuse.torch` | 0 | 0 | 9 | 104,016 |
| 09-26 13:15:56 | `dialogue.parley.reply.refuse.torch` | 0 | 0 | 9 | 104,025 |
| 09-26 13:15:58 | `dialogue.parley.reply.refuse.reaper` | 0 | 0 | 9 | 104,034 |
| 09-26 13:15:58 | `dialogue.parley.reply.refuse.reaper` | 0 | 0 | 9 | 104,043 |
| 09-26 13:16:45 | `dialogue.caravan.line1` | 0 | 0 | 55 | 104,098 |
| 09-26 13:16:46 | `dialogue.caravan.line1` | 0 | 0 | 55 | 104,153 |
| 09-26 13:17:02 | `dialogue.caravan.line2` | 0 | 0 | 92 | 104,245 |
| 09-26 13:17:03 | `dialogue.caravan.line2` | 0 | 0 | 92 | 104,337 |
| 09-26 13:17:11 | `dialogue.rival_envoy.intro` | 0 | 0 | 20 | 104,357 |
| 09-26 13:17:11 | `dialogue.rival_envoy.intro` | 0 | 0 | 20 | 104,377 |
| 09-26 13:17:23 | `dialogue.parley.farewell.tribute.red` | 0 | 0 | 34 | 104,411 |
| 09-26 13:17:24 | `dialogue.parley.farewell.tribute.red` | 0 | 0 | 34 | 104,445 |
| 09-26 13:17:27 | `dialogue.parley.farewell.tribute.torch` | 0 | 0 | 34 | 104,479 |
| 09-26 13:17:28 | `dialogue.parley.farewell.tribute.torch` | 0 | 0 | 34 | 104,513 |
| 09-26 13:17:33 | `dialogue.parley.farewell.tribute.reaper` | 0 | 0 | 34 | 104,547 |
| 09-26 13:17:34 | `dialogue.parley.farewell.tribute.reaper` | 0 | 0 | 34 | 104,581 |
| 09-26 13:17:41 | `dialogue.parley.farewell.truce.red` | 0 | 0 | 45 | 104,626 |
| 09-26 13:17:42 | `dialogue.parley.farewell.truce.red` | 0 | 0 | 45 | 104,671 |
| 09-26 13:17:46 | `dialogue.parley.farewell.truce.torch` | 0 | 0 | 45 | 104,716 |
| 09-26 13:17:47 | `dialogue.parley.farewell.truce.torch` | 0 | 0 | 45 | 104,761 |
| 09-26 13:17:51 | `dialogue.parley.farewell.truce.reaper` | 0 | 0 | 45 | 104,806 |
| 09-26 13:17:52 | `dialogue.parley.farewell.truce.reaper` | 0 | 0 | 45 | 104,851 |
| 09-26 13:17:57 | `dialogue.parley.farewell.duel.red` | 0 | 0 | 22 | 104,873 |
| 09-26 13:17:59 | `dialogue.parley.farewell.duel.red` | 0 | 0 | 22 | 104,895 |
| 09-26 13:18:01 | `dialogue.parley.farewell.duel.torch` | 0 | 0 | 22 | 104,917 |
| 09-26 13:18:02 | `dialogue.parley.farewell.duel.torch` | 0 | 0 | 22 | 104,939 |
| 09-26 13:18:05 | `dialogue.parley.farewell.duel.reaper` | 0 | 0 | 22 | 104,961 |
| 09-26 13:18:05 | `dialogue.parley.farewell.duel.reaper` | 0 | 0 | 22 | 104,983 |
| 09-26 13:18:08 | `dialogue.parley.reply.warn_yes` | 0 | 0 | 54 | 105,037 |
| 09-26 13:18:09 | `dialogue.parley.reply.warn_yes` | 0 | 0 | 54 | 105,091 |
| 09-26 13:18:13 | `dialogue.parley.reply.warn_yes.red` | 0 | 0 | 54 | 105,145 |
| 09-26 13:18:14 | `dialogue.parley.reply.warn_yes.red` | 0 | 0 | 54 | 105,199 |
| 09-26 13:18:18 | `dialogue.parley.reply.warn_yes.torch` | 0 | 0 | 54 | 105,253 |
| 09-26 13:18:20 | `dialogue.parley.reply.warn_yes.torch` | 0 | 0 | 54 | 105,307 |
| 09-26 13:18:29 | `dialogue.parley.reply.warn_yes.reaper` | 0 | 0 | 54 | 105,361 |
| 09-26 13:18:31 | `dialogue.parley.reply.warn_yes.reaper` | 0 | 0 | 54 | 105,415 |
| 09-26 13:18:38 | `dialogue.parley.reply.warn_no` | 0 | 0 | 33 | 105,448 |
| 09-26 13:18:38 | `dialogue.parley.reply.warn_no` | 0 | 0 | 33 | 105,481 |
| 09-26 13:18:41 | `dialogue.parley.reply.warn_no.red` | 0 | 0 | 33 | 105,514 |
| 09-26 13:18:42 | `dialogue.parley.reply.warn_no.red` | 0 | 0 | 33 | 105,547 |
| 09-26 13:18:45 | `dialogue.parley.reply.warn_no.torch` | 0 | 0 | 33 | 105,580 |
| 09-26 13:18:45 | `dialogue.parley.reply.warn_no.torch` | 0 | 0 | 33 | 105,613 |
| 09-26 13:18:49 | `dialogue.parley.reply.warn_no.reaper` | 0 | 0 | 33 | 105,646 |
| 09-26 13:18:50 | `dialogue.parley.reply.warn_no.reaper` | 0 | 0 | 33 | 105,679 |
| 09-26 13:23:41 | `dialogue.brute_toll.talk_walls` | 0 | 0 | 45 | 105,724 |
| 09-26 13:23:41 | `dialogue.brute_toll.talk_walls` | 0 | 0 | 45 | 105,769 |
| 09-26 13:23:45 | `dialogue.brute_toll.talk_honest` | 0 | 0 | 92 | 105,861 |
| 09-26 13:23:46 | `dialogue.brute_toll.talk_honest` | 0 | 0 | 92 | 105,953 |
| 09-26 13:25:30 | `dialogue.brute_toll.demand` | 0 | 0 | 40 | 105,993 |
| 09-26 13:25:31 | `dialogue.brute_toll.demand` | 0 | 0 | 40 | 106,033 |
| 09-26 13:25:35 | `dialogue.brute_toll.demand2` | 0 | 0 | 68 | 106,101 |
| 09-26 13:25:36 | `dialogue.brute_toll.demand2` | 0 | 0 | 68 | 106,169 |
| 09-26 13:25:42 | `dialogue.brute_toll.talk` | 0 | 0 | 45 | 106,214 |
| 09-26 13:25:42 | `dialogue.brute_toll.talk` | 0 | 0 | 45 | 106,259 |
| 09-26 13:29:27 | `dialogue.brute_toll.talk_walls` | 0 | 0 | 45 | 106,304 |
| 09-26 13:29:28 | `dialogue.brute_toll.talk_walls` | 0 | 0 | 45 | 106,349 |
| 09-26 13:29:32 | `dialogue.brute_toll.talk_honest` | 0 | 0 | 92 | 106,441 |
| 09-26 13:29:33 | `dialogue.brute_toll.talk_honest` | 0 | 0 | 92 | 106,533 |
| 09-26 13:29:57 | `_tts_label` | 0 | 0 | 23 | 106,556 |
| 09-26 13:29:59 | `_tts_label` | 0 | 0 | 16 | 106,572 |
| 09-26 13:30:01 | `_tts_label` | 0 | 0 | 18 | 106,590 |
| 09-26 13:30:02 | `_tts_label` | 0 | 0 | 19 | 106,609 |
| 09-26 13:30:04 | `_tts_label` | 0 | 0 | 27 | 106,636 |
| 09-26 13:31:26 | `_tts_label` | 0 | 0 | 12 | 106,648 |
| 09-26 13:31:27 | `_tts_label` | 0 | 0 | 20 | 106,668 |
| 09-26 13:31:29 | `_tts_label` | 0 | 0 | 20 | 106,688 |
| 09-26 13:31:30 | `_tts_label` | 0 | 0 | 21 | 106,709 |
| 09-26 13:31:32 | `_tts_label` | 0 | 0 | 20 | 106,729 |
| 09-26 13:31:33 | `_tts_label` | 0 | 0 | 14 | 106,743 |
| 09-26 13:31:35 | `_tts_label` | 0 | 0 | 12 | 106,755 |
| 09-26 13:31:36 | `_tts_label` | 0 | 0 | 11 | 106,766 |
| 09-26 13:31:38 | `_tts_label` | 0 | 0 | 19 | 106,785 |
| 09-26 13:31:39 | `_tts_label` | 0 | 0 | 18 | 106,803 |
| 09-26 13:33:39 | `dialogue.voice_design.caravan` | 0 | 0 | 147 | 106,950 |
| 09-26 13:33:59 | `dialogue.voice_design.peddler` | 0 | 0 | 147 | 107,097 |
| 09-26 13:34:02 | `dialogue.refugees.line1` | 0 | 0 | 55 | 107,152 |
| 09-26 13:34:03 | `dialogue.refugees.line1` | 0 | 0 | 55 | 107,207 |
| 09-26 13:34:06 | `dialogue.refugees.line2` | 0 | 0 | 58 | 107,265 |
| 09-26 13:34:07 | `dialogue.refugees.line2` | 0 | 0 | 58 | 107,323 |
| 09-26 13:34:10 | `dialogue.refugees.work` | 0 | 0 | 37 | 107,360 |
| 09-26 13:34:11 | `dialogue.refugees.work` | 0 | 0 | 37 | 107,397 |
| 09-26 13:34:34 | `dialogue.peddler.line1` | 0 | 0 | 62 | 107,459 |
| 09-26 13:34:36 | `dialogue.peddler.line1` | 0 | 0 | 62 | 107,521 |
| 09-26 13:34:41 | `dialogue.barter.greet` | 0 | 0 | 25 | 107,546 |
| 09-26 13:34:42 | `dialogue.barter.greet` | 0 | 0 | 25 | 107,571 |
| 09-26 13:36:13 | `dialogue.traveller.intro` | 0 | 0 | 48 | 107,619 |
| 09-26 13:36:14 | `dialogue.traveller.intro` | 0 | 0 | 48 | 107,667 |
| 09-26 13:36:17 | `dialogue.traveller.greet` | 0 | 0 | 70 | 107,737 |
| 09-26 13:36:18 | `dialogue.traveller.greet` | 0 | 0 | 70 | 107,807 |
| 09-26 13:36:26 | `dialogue.traveller.thanks` | 0 | 0 | 47 | 107,854 |
| 09-26 13:36:27 | `dialogue.traveller.thanks` | 0 | 0 | 47 | 107,901 |
| 09-26 13:36:31 | `dialogue.traveller.shrug` | 0 | 0 | 26 | 107,927 |
| 09-26 13:36:32 | `dialogue.traveller.shrug` | 0 | 0 | 26 | 107,953 |
| 09-26 13:36:34 | `dialogue.traveller.news` | 0 | 0 | 77 | 108,030 |
| 09-26 13:36:35 | `dialogue.traveller.news` | 0 | 0 | 77 | 108,107 |
| 09-26 13:36:47 | `dialogue.traveller.bye` | 0 | 0 | 39 | 108,146 |
| 09-26 13:36:48 | `dialogue.traveller.bye` | 0 | 0 | 39 | 108,185 |
| 09-26 13:36:54 | `dialogue.demo_peddler.intro` | 0 | 0 | 51 | 108,236 |
| 09-26 13:36:55 | `dialogue.demo_peddler.intro` | 0 | 0 | 51 | 108,287 |
| 09-26 13:37:00 | `dialogue.demo_peddler.greet` | 0 | 0 | 89 | 108,376 |
| 09-26 13:37:01 | `dialogue.demo_peddler.greet` | 0 | 0 | 89 | 108,465 |
| 09-26 13:37:12 | `dialogue.demo_peddler.haggle_yes` | 0 | 0 | 45 | 108,510 |
| 09-26 13:37:13 | `dialogue.demo_peddler.haggle_yes` | 0 | 0 | 45 | 108,555 |
| 09-26 13:37:20 | `dialogue.demo_peddler.haggle_no` | 0 | 0 | 42 | 108,597 |
| 09-26 13:37:21 | `dialogue.demo_peddler.haggle_no` | 0 | 0 | 42 | 108,639 |
| 09-26 13:37:26 | `dialogue.caravan.line1` | 0 | 0 | 55 | 108,694 |
| 09-26 13:37:27 | `dialogue.caravan.line1` | 0 | 0 | 55 | 108,749 |
| 09-26 13:37:35 | `dialogue.caravan.line2` | 0 | 0 | 92 | 108,841 |
| 09-26 13:37:36 | `dialogue.caravan.line2` | 0 | 0 | 92 | 108,933 |
| 09-26 13:44:30 | `dialogue.traveller.intro` | 0 | 0 | 48 | 108,981 |
| 09-26 13:44:31 | `dialogue.traveller.intro` | 0 | 0 | 48 | 109,029 |
| 09-26 13:44:34 | `dialogue.traveller.greet` | 0 | 0 | 70 | 109,099 |
| 09-26 13:44:35 | `dialogue.traveller.greet` | 0 | 0 | 70 | 109,169 |
| 09-26 13:44:39 | `dialogue.traveller.thanks` | 0 | 0 | 47 | 109,216 |
| 09-26 13:44:40 | `dialogue.traveller.thanks` | 0 | 0 | 47 | 109,263 |
| 09-26 13:44:43 | `dialogue.traveller.shrug` | 0 | 0 | 26 | 109,289 |
| 09-26 13:44:43 | `dialogue.traveller.shrug` | 0 | 0 | 26 | 109,315 |
| 09-26 13:44:46 | `dialogue.traveller.news` | 0 | 0 | 77 | 109,392 |
| 09-26 13:44:47 | `dialogue.traveller.news` | 0 | 0 | 77 | 109,469 |
| 09-26 13:44:52 | `dialogue.traveller.bye` | 0 | 0 | 39 | 109,508 |
| 09-26 13:44:53 | `dialogue.traveller.bye` | 0 | 0 | 39 | 109,547 |
| 09-26 13:46:23 | `dialogue.parley.bandit.intro` | 0 | 0 | 63 | 109,610 |
| 09-26 13:46:24 | `dialogue.parley.bandit.intro` | 0 | 0 | 63 | 109,673 |
| 09-26 13:46:28 | `dialogue.parley.bandit.reply.tribute` | 0 | 0 | 24 | 109,697 |
| 09-26 13:46:29 | `dialogue.parley.bandit.reply.tribute` | 0 | 0 | 24 | 109,721 |
| 09-26 13:46:31 | `dialogue.parley.bandit.reply.truce_yes` | 0 | 0 | 46 | 109,767 |
| 09-26 13:46:32 | `dialogue.parley.bandit.reply.truce_yes` | 0 | 0 | 46 | 109,813 |
| 09-26 13:46:35 | `dialogue.parley.bandit.reply.truce_no` | 0 | 0 | 36 | 109,849 |
| 09-26 13:46:36 | `dialogue.parley.bandit.reply.truce_no` | 0 | 0 | 36 | 109,885 |
| 09-26 13:46:39 | `dialogue.parley.bandit.reply.warn_yes` | 0 | 0 | 45 | 109,930 |
| 09-26 13:46:40 | `dialogue.parley.bandit.reply.warn_yes` | 0 | 0 | 45 | 109,975 |
| 09-26 13:46:43 | `dialogue.parley.bandit.reply.warn_no` | 0 | 0 | 43 | 110,018 |
| 09-26 13:46:44 | `dialogue.parley.bandit.reply.warn_no` | 0 | 0 | 43 | 110,061 |
| 09-26 13:46:47 | `dialogue.parley.bandit.reply.duel` | 0 | 0 | 53 | 110,114 |
| 09-26 13:46:48 | `dialogue.parley.bandit.reply.duel` | 0 | 0 | 53 | 110,167 |
| 09-26 13:46:51 | `dialogue.parley.bandit.reply.refuse` | 0 | 0 | 13 | 110,180 |
| 09-26 13:46:52 | `dialogue.parley.bandit.reply.refuse` | 0 | 0 | 13 | 110,193 |
| 09-26 17:24:16 | `dialogue.parley.bandit.demand` | 0 | 0 | 89 | 110,282 |
| 09-26 17:24:17 | `dialogue.parley.bandit.demand` | 0 | 0 | 89 | 110,371 |
| 09-26 17:24:42 | `dialogue.parley.bandit.farewell.tribute` | 0 | 0 | 29 | 110,400 |
| 09-26 17:24:43 | `dialogue.parley.bandit.farewell.tribute` | 0 | 0 | 29 | 110,429 |
| 09-26 17:24:46 | `dialogue.parley.bandit.farewell.truce` | 0 | 0 | 33 | 110,462 |
| 09-26 17:24:47 | `dialogue.parley.bandit.farewell.truce` | 0 | 0 | 33 | 110,495 |
| 09-26 17:24:50 | `dialogue.parley.bandit.farewell.duel` | 0 | 0 | 26 | 110,521 |
| 09-26 17:24:50 | `dialogue.parley.bandit.farewell.duel` | 0 | 0 | 26 | 110,547 |
| 09-26 17:25:28 | `_tts_label` | 0 | 0 | 24 | 110,571 |
| 09-26 17:25:31 | `_tts_label` | 0 | 0 | 17 | 110,588 |
| 09-26 17:25:34 | `_tts_label` | 0 | 0 | 32 | 110,620 |
| 09-26 17:25:36 | `_tts_label` | 0 | 0 | 18 | 110,638 |
| 09-26 17:25:38 | `_tts_label` | 0 | 0 | 23 | 110,661 |
| 09-26 17:25:41 | `_tts_label` | 0 | 0 | 18 | 110,679 |
