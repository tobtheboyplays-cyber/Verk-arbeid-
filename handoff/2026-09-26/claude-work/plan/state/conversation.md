# Conversation lane (a86e7e73b8c0da88f) - state 26 Sep 06:10

## Done (compiled green in private build dir build-agent-talk; JUnit 21/21 conversation.*)
- Core: com.hearthstead.conversation.* - ConversationService (bind/open/choose/barter, server-authoritative,
  revision nonce per state, costs exactly once via CostPayer = player inv then Hearth+Warehouse),
  ConversationGraph + Conversation builder + GraphJson (data/<ns>/conversations/*.json) + GraphValidator,
  ConversationActions (actions/conditions), RelationSavedData (per settlement per identity, -100..100, memory,
  reputation per kind), Persuasion (pure, 5..95), BarterMath/BarterDeal/ListBarterStock (pure valuation,
  hostile-line refusal), ConversationConfig ([conversations] wired by captain), ConversationNetwork (own payloads),
  ConversationEvents (interact both hands, tick, damage abort, reload listener), ConversationCommand (/hstalk
  traveller|peddler|raid|relation, op2 QA/film helpers).
- Encounter pull-in (Shadow of War): radius per graph, eye+torso collider rays, not in combat/downed, one per
  binding revision; intro card + camera swoop client side.
- Raid parley: conversation/parley/RaidParley.java (recurring raids only; band holds via NoAi+tag; tribute coins/food,
  truce persuasion, duel with fair-damage rules and yield lines, refuse; timeout counts only with a player near).
  RaidDirector got an additive resolveRecurringRetreat(level, settlement, Component) overload (balance lane OK'd).
- Client: client/conversation/ ConversationClient, ConversationScreen (bottom Banner bar, typed lines, keys 1-9,
  persuasion seal, name card), BarterScreen, ConversationCamera (framing via FOV hook + reflection, unclip, ease),
  ConversationMarkers ("!" + "X is talking", talk overlay clips settler/village_chat|village_listen).
- HearthsteadClientConfig: [conversations] cameraFocus, encounterCinematics (additive, captain OK'd).
- Lang: conversation.hearthstead.* keys appended to en_us.json (JSON validated). Sample graph traveller.json.
- Events lane (a29c68ccbeac8219f) builds refugees/peddler/minstrels/brute_toll on this API (WorldEventConversations).
- Codex P1/P2 barter exploits fixed (re-verified closed by Codex).

## GameTests (handed to captain, W1; NOT run by me)
talk_cost_once, talk_barter_exact, talk_replay, talk_encounter_sight, talk_parley_tribute
(com.hearthstead.conversation.ConversationGameTests)

## Pending: film (~40 s) - queued in WSL-QUEUE.md after W1/settler-ui/soak/finisher
- Scripts ready: C:\Users\tobia\Hearthstead-Claude\tools\talk\ start.sh / wait.sh / do.sh / rec.sh / stop.sh
  (Xvfb :74, build /tmp/hsc-talk-build, save run/saves/Talk-Film copied from Elmfield-Claude-Test).
  start.sh already uses -Dorg.gradle.daemon.idletimeout=300000 (RAM rule).
- Plan: /hsevent start brute_toll (walk up -> pull-in, name card, dialog) ; /hstalk peddler (wares -> barter) ;
  /hstalk raid (captain halts, walk up, pay tribute -> raid leaves). Output videos\conversation\.
- NOTHING seen in game yet: portrait offset, camera framing, card, barter layout all unverified visually.

## Open items / risks
- Camera uses reflection on Camera.setPosition/setRotation/detached (Mojmap runtime names); falls back off if missing.
- First (authored) raid never parleys; duel captain AI targeting relies on setTarget each tick.
- Only local talker sees exact typing-synced gesture; watchers get village_chat loop.

## 06:20 update
- Bug-hunter fix: RaidParley.blockDamage no longer refuses environmental / BYPASSES_INVULNERABILITY damage in a duel
  (only blows from other living attackers are refused; yields still catch lava etc.). Compiled green.
- 06:3x: W2a crash (conv_state sent to channel-less mock player from encounter tick). Bug hunter routed all sends via
  PayloadSend (BH-20); I added: auto encounter tick only for players with the conv_state channel. Compiled green.
- 06:35 W3a: all talk_* passed except talk_barter_exact (test bug: coins overwrote bought emerald in slot 1).
  Fixed test (slot 20), compiled green, captain asked to re-run talk_.

## 08:05 film slot 1 (lock 07:23-08:02, released, captain pinged)
- SEEN IN GAME: traveller walk-up pull-in -> name card (+ "Remembers you") -> intro swoop -> over-shoulder talk
  framing with Banner bar; share-bread cost paid (8->6); peddler Aldric card -> "Show me your wares" -> barter
  64 logs <-> 4 emeralds, "Deal struck", stock updated exactly.
- Clip: videos\conversation\talk-visitor-barter.mp4 (43.8 s) + -small.mp4; raw talk-raw.mp4.
- Fixed after viewing (compiled, not yet re-seen): player body hidden during intro swoop; taller text panel;
  "!" marker NORMAL/gold; card not skipped by held movement keys; /hstalk raid first-raid fixture night 4.
- Film env: WSL client needs ALSOFT_DRIVERS=null (OpenAL hang) - in start.sh; save needs allowCommands=1
  (tools/talk/cheats.py). Settlement Elmfield centre 110 72 -116.
- PENDING: second slot (~15 min) for raid-captain parley + brute toll (/hsevent start brute_toll) shots.

## 08:33 film slot 2 (lock 08:27-08:31, released, captain pinged)
- SEEN IN GAME: events-lane brute toll on my framework: brutes approach, walk-up pull-in, "BRUTE CHIEF" name card,
  bar with food 16 / coins 10 / (Persuade 36%) / Refuse / Attack; "Give them the food" paid exactly 16 (32->16),
  outcome broadcast, brutes leave.
- NOT filmed: raid parley - /hstalk raid: "band could not spawn (no footing outside the claim)" on Elmfield save.
- Framing flaw seen: in a forest the over-shoulder camera got pulled in onto the player's head. Fixed after
  (compiled, not re-seen): try other shoulder, else first-person look at the speaker with the body hidden.
- Final clip: videos\conversation\talk-showcase.mp4 (50 s: traveller pull-in + card + dialog, peddler barter,
  brute toll) + talk-showcase-small.mp4.

## 12:01 film slot 3 (lock 11:52-~12:00, released, map lane pinged)
- SEEN IN GAME: raid parley end to end. /hstalk raid -> real recurring band; Saga captain "Skarde the Ashen" halts
  at the edge (broadcast with 45 s), walk-up pull-in, bar: tribute 15 coins / 30 food / (Persuade 27%) truce /
  duel / refuse. Tribute paid exactly 15 (64->49); raid resolved "The raiders leave without a fight."
- Cause of earlier "could not spawn": recurring start needs the Journey's first-raid chapter; QA fixture now sets a
  skipped Journey (ConversationCommand, QA-only). Compiled green.
- Clips: videos\conversation\talk-showcase.mp4 (76 s: traveller, peddler barter, brute toll, raid parley)
  + talk-showcase-small.mp4; parley alone talk-raid-parley.mp4.
- Still not seen in game: duel, truce roll, forest camera fallback (compiled only).

## ~13:00 owner feedback round (compiled green, JUnit 25/25, NOT yet seen in game)
- Departure walk-off: com.hearthstead.conversation.Departure (+DepartureRules pure, JUnit) - farewell beat, group walk
  48-64 out, despawn only unseen (>48, or >24 & no LOS), stuck repick 20 s, give-up 120 s, player blow breaks truce
  (re-arm + relation -25 + notice), leavers dropped on load. RaidParley tribute/truce/duel-won walk off (no poof)
  via RaidDirector.resolveRecurringRetreat(..., discardLoaded=false) + RaiderEntity.detachForDeparture().
  Events lane WorldEventDeparture delegates to it. GameTests batch talk_departure (4).
- Marker: owner-approved badge (textures/gui/marker/*.png, preview videos/conversation/marker-preview.png), rendered in
  ConversationMarkers (22 px @12-40 blocks, max 30 px close, bob, glow pulse, fade, hidden in talk/>48/no LOS/after
  talking until re-bound); kinds talk/trade/parley/quest. FX shimmer removed by fx lane; map lane told to use texture.
- Voice (ConversationVoice, HsSound ids voice.<arch>.babble with villager fallback) + TalkingHead (render-time head
  pitch/yaw nods synced to syllables, listening nods) + ui.conversation_open.
- Short intro: card 1.2 s (captain 1.5 s), shrinks into header; repeat talks skip the card (0.4 s camera).
- Truncation: names step down size, options wrap 2 lines (column 50%), body scrolls/follows typing; HUD crosshair etc
  hidden during talk (was the stray "x").
- Workflow now: edit private copy (build-agent-talk/proj), pending.txt + publish.sh into shared tree after green.
- Pending: film slot requested from captain.
- ~13:30: voice uses sound lane's VoiceBanks (voice matches look); owner "softer Sims chatter": babble only first
  ~1.8 s per line (4-8 syllables, 140-220 ms, +-3 %, ~-7 dB x voiceVolume slider), head motion whole line + ease-out.
  Map lane draws the same badge textures. All published, shared tree compiles. Waiting for film slot (film4.sh).
- ~14:00 owner decision: talk voice = vanilla villager sounds (ambient/yes/no/trade/celebrate by tone tag) via
  HsSound ids voice.npc.<mood> with vanilla fallback, 3-6 hmms in first 1.5 s, per-character pitch +-3 %, 0.22 vol;
  brutes: no babble, one dark grunt (voice.brute.grunt / VINDICATOR_AMBIENT p0.55) at line start + 40 % mid-line.
  Head motion unchanged. Published, compiles. Still waiting for film slot.
- ~14:20 voice: VoiceBanks.voice(tone) ids (npc/trader/brute), vol 0.75/1.0, grunt 1.0 in bank window, MAX_SYLLABLES/GAP/MAX_BABBLE_MS constants. Published.
- ~15:00 real voiced lines: VoiceLines (assets/hearthstead/voice_lines.json {lines:{<rest>:{ms,env}}}, sound hearthstead:voice.line.<rest> from lang key conversation.hearthstead.<rest>), VOICE category x voiceVolume, reveal paced to clip ms+150, skip/next line stops clip, head nods from envelope or rhythm, unvoiced lines fall back to hmm. Published.
- WSL rules: never gradlew --stop; lock via tools/talk/take-lock.sh (noclobber) / release-lock.sh (own only); slots only from captain.
- ~15:30 honest persuasion: TownFacts (pure, JUnit) + TownFactsLive conditions town.defended/guards/walls/fed/raids_won; odds bonus by skill from facts; parley 'warn' only when defended; events lane split brute_toll talk_walls/talk_honest. GameTest batch talk_honest_persuasion. Published, compiles.
- ~16:00 voiced lines: captain epithet variants (voice.line.<key>.<epithet> first). Bandit parley graph hearthstead:bandit_parley (+lang parley.bandit.*, title Bandit Leader); switch RaidParley.outlaw() returns false until raid lane's Variant.BANDIT/isBandit() lands. Published, compiles.
