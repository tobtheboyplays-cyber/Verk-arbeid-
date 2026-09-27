# Codex overnight mission: Sunday 27 Sep (Bannerhold)

You have the WHOLE PC overnight. There are no Claude lanes running after 00:40. MAIN Claude resumes in the morning for ONE final check before the owner plays: a fresh co-op world at 18:00, 2 players, Normal, PvP on, on a dedicated server with friends on AutoModpack.
Your job: test survival all night and hand MAIN Claude precise, reproducible inputs, so the morning is spent fixing real problems and not searching.

## What to test
The FROZEN CANDIDATE in `C:\Users\tobia\Hearthstead-Claude\candidate\<latest label>\`: the jar, SHA256, manifest (protocol "19") and source zip. Read `plan/SUNDAY-GATE.md` first for the captain's final suite result and the known issues. Verify that the jar SHA256 matches before every run.

## Hard rules
- NEVER touch `C:\Users\tobia\Hearthstead-Server`, its saves, or the owner's server JVM (PID 33040). Work only on copies.
- No deploy to the real server. No git commands that change the tree, and never push (the repo is public).
- Do NOT edit shared production source in `Verk-arbeid-\hearthstead-neoforge\src\main`. You are the checker: report findings with a repro. Test-only helpers go in your own dirs.
- No downloads except already-verified mods. No purchases or logins.
- Keep your own locks. Keep free RAM ≥ 3 GB for stability, and stop your JVMs cleanly at the end.

## Priorities (in order; timebox each and move on)
1. **Critical path, native survival, Builder-built** (most important).
   - Use a fresh world on seed `kongsgard` (level-seed=kongsgard; the Banner spot is at x=4 z=13; see `plan/SEED-PICK.md`), Normal difficulty, a survival player.
   - Chain: found the Banner → Builder's Hut L1 + Builder → Lumber Camp → Warehouse + Courier → Farmhouse → House → Tavern → Barracks + Guard → Archer. Use the real UI and the new Blueprint items ("<Building> Blueprint": craft, right-click, pick a style, anchored placement panel, confirm) AND the Builder's Plan catalog.
   - `/time add` is OK to speed up; NEVER `/time set`. Give materials by command where survival gathering would take hours, and note what you gave.
   - Then run the first raid (wait for it or trigger it by command) and confirm: warning, raiders arrive, guards and archers fight, the raid ends, rewards are given, nobody is stuck afterwards.
   - For every building, log the time-to-complete in real minutes, and whether a "needs a hand" line appeared (with the positions).
   - KNOWN RISK: the Tavern did not finish within 30k ticks headless. Measure it natively with and without a Courier, and state clearly whether it finishes and how long it takes.
2. **Long headless proof of slow builds.** Run the captain's GameTest batch `scenario_blueprint_build` for the pre-raid styles with a 90,000-tick timeout. Report per style: done or not, total ticks, and final stage and done/total. The styles are all tavern_*, barracks_large/rustic/stone, farmhouse_large/stone, house_stone, house_two_storey and warehouse_large/stone. See `build-agent-integration` for the captain's gt-*.cmd scripts and flags.
3. **Deploy dry-run on a COPY** per `plan/DEPLOY-SUNDAY.md` and `deploy/sunday/`:
   - a fresh world on kongsgard;
   - the candidate jar, DF + PuzzlesLib on the server, and the client mods + resource packs in the AutoModpack host content.
   - From a CLEAN client (vanilla + NeoForge + AutoModpack only), prove that ONE update gets everything and then joins; the protocol is 19; a first join gets the handbook + 8 bread.
   - Also test a second client joining at the same time (co-op), and a reconnect.
4. **Every job works + animations.** Hire each profession (see `plan/state/jobs-matrix.md`) and watch one work cycle per job. Film short clips to `videos/ingame/jobs/`. For each job, check that:
   - the loop completes with real output;
   - the animation plays, with no T-pose and no missing-clip warnings in the client log;
   - the carry pack is clip-free in every pose.

   Include the guard drill (morning), the tavern scene with food, the archer draw/reload, the hunter v2 loop, the trader counter deal, the lumberer whet and the fisher net.
5. **Events and goblin, with SOUND.**
   - `/hsevent start <id>` for peddler, caravan, refugees, rival_envoy, minstrels, stray_dog, field_fox, wolf_pack, wild_boar, tavern_brawl and brute_toll;
   - `/hsgoblin thief`;
   - `/hsstory visit` and one threat.

   For each, note: starts / completes / sounds play (grep the client log for "Unable to play unknown soundEvent") / chat readable.
6. **Commands.** Run every player-facing and admin command in a survival world, as op and as a non-op; the list is in `plan/BUGHUNT-LOG.md` or `CommandSweepGameTests`. Note any exception, raw translation key or wrong permission.
7. **UI, clean with no overlaps** (the owner's standard is the Banner UI; he hates scrolling, tabs and underlined text buttons). Screenshot at 1920x1080 GUI 2/3/4 and at 1280x720 GUI 2:
   - the settler one-page sheet and the framed buttons;
   - the plaque, Banner, banner designer and Builder;
   - the Blueprint style picker and placement panel, which NOBODY has seen in game yet;
   - the tech tree, Guildmaster, trader, warehouse, inventory (Shift+right-click a settler), conversation and handbook;
   - the HUD: heart counter, town chat, raid warning and downed.

   Flag every overlap, clipped text or off-panel element with a screenshot path.
8. **Stability soak.** Leave the critical-path world running 2+ hours with `/time add` progression and 2 clients if possible. Watch the server log for exceptions, TPS and memory. Save, restart the server, and reload: check that settlers, jobs, builds, the raid state and the banner design all survive.

## Deliverables (MAIN Claude reads these first in the morning)
Write `C:\Users\tobia\Hearthstead-Claude\Verk-arbeid-\COORD\CODEX-OVERNIGHT-REPORT.md`, and keep it updated as you go so a crash doesn't lose results. It contains:
- **TOP section: "BLOCKERS FOR 18:00"**, ranked. Each has a one-line symptom, an exact repro (world, commands, coordinates), the log excerpt or stack trace, the suspected file:line if you can tell, and a screenshot/video path.
- **A table per priority 1–8:** item → PASS / FAIL / NOT RUN → evidence path.
- **Timings:** real minutes per pre-raid building natively and in 90k headless, and the first-raid outcome.
- **Deploy dry-run:** the exact steps that worked, and any step in `DEPLOY-SUNDAY.md` that was wrong, with the corrected command.
- **"Nice to fix later"**, kept separate from the blockers.
Also append a short summary to `COORD/to-claude.md` at 06:00 and at the end.

Be precise, not verbose. Evidence levels must stay separate: GameTest vs native-seen vs filmed. If something could not be tested, say NOT RUN and why.

## Tip for priority 7 (UI)
The dev-only `/hsui <name>` command opens each screen with sample data, which is the fastest way to screenshot every screen. The UI sweep (00:07) fixed about 20 overlaps statically; see plan/state/ui-sweep.md for the list to verify. The known open items are the Patrol routes height at 1280x720 GUI 3, the tech tree "?" legend, the warehouse detail pane at small sizes, and pickup notices under the chat.
