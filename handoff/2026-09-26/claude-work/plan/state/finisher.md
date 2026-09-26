# Finisher lane state (07:45, 2026-09-26)

## Done
- The finisher system is implemented and compiled. JUnit passes 25/25.
- **GameTests: all `finisher_*` PASSED in the integration captain's W4a run, including executor_untouchable.**
- 11 moves authored and exported: 13 player clips + 11 victim clips.
- In game (take 1, 07:20):
  - Clips load: the log shows the motion engine loading the `finisher_victim_*` clips.
  - The red glow's health bar and the "[R] Finish" prompt show.
  - Take 1 is NOT usable:
    - the third-person camera sat directly behind the player and hid the victim;
    - typed chat commands broke after the second finisher (the creative inventory opened), so the rest of the film, including the command-menu part, shows only the inventory.

## Fix made for take 2 (compiled, not yet run)
- New `/hsfinisher reel` (`finisher/FinisherReel.java`), a server-timed showcase with no typing during recording:
  1. t=0: the player's own sword finisher. The script presses the real R at about 1.2 s; the view pitch is 26 so the victim shows over the shoulder.
  2. t=90: the player becomes a spectator camera beside a stage.
  3. The scripted knight "Sir_Aldric" (a mock ServerPlayer; equipment is pushed to watchers by hand) performs the axe hook-chop, brute knee-buckle, goblin scruff-slam and mace gut-slam.
  4. Sir_Aldric and Sir_Bryn do the co-op double on a brute captain.
  5. About 27 s in total; then back to creative.
- `FinisherService.moveActor` now keeps the player's own pitch, clamped to -10..35 (it was forced to 5).
- `tools/film_finisher.sh` rewritten around the reel. The command-menu part is unchanged: battleqa sandbox, R/G dots, charge. Its Gradle call includes the daemon idle timeout.

## To run when the slot comes (WSL-QUEUE.md: finisher is 4b, "second take")
1. Check RAM is 90% or lower, and that the lock is free or the captain has said "go".
2. `echo "finisher-agent $(date +%T) :79" > C:\Users\tobia\Hearthstead-Claude\wsl-client.lock`
3. PowerShell: `wsl -e bash -c "bash /mnt/c/Users/tobia/Hearthstead-Claude/tools/film_finisher.sh > /tmp/hsc-fin-run.log 2>&1"` (about 5 min)
4. Delete the lock, then ping the next agent in the queue and the captain.
5. Check `shots/finisher/{glow-player,move-player,stage-*}.png` and `cmd-*`.
6. The small copy is made automatically (854p, crf 30; take 1 was 0.27 MB).
7. Send main the paths: `videos/finisher/finisher-showcase.mp4`, `videos/finisher/finisher-showcase-small.mp4`, `videos/command/command-menu.mp4`.


## Take 2 (08:45-08:50): filmed; lock released and captain pinged
- Command-menu clip is good: first person, "Knights: Hold the line" HUD, ground dots.
- Showcase:
  - The player's own finisher is good: glow, the "[R] Finish" prompt, the thrust, and the weapon trail.
  - The stage part (scripted knights) works: the axe, brute, goblin and mace finishers and the double all ran, with dust and poof particles.
  - But the stage part is framed too far away (stage 8 blocks ahead + 4 right, camera 6.5 blocks off). The stage also sat past the edge of the floating Command-Film platform.
- Fixed (compiled) for an optional take 3: stage 5 blocks ahead on the ground, camera 4.2 blocks to the side.
  Same script, about 5 min, ask the captain for a slot.

## Take 3 (08:56:38-08:58:19): aborted, lock released, raid-spawn lane + captain pinged
- The build failed in another lane's file: settlement/RoomScanner.java:425-427 can't resolve StairBlock, Half and Direction.
- The script is ready: reel framing fixed, chat cleared with F3+D before each recording, `hsfinisher cleanup` instead of `/kill`.
- Asked the captain to re-queue finisher for one 5-min slot once the tree is green.
- Resume: same run steps as above.
- The take-2 files are still in place and usable: videos/finisher/finisher-showcase(.mp4|-small.mp4) and videos/command/command-menu.mp4.

## Take 3 (09:06:35-09:10:18): filmed, lock released, captain pinged
- The opening is good: a village setting, the player in armour, the "[R] Finish" prompt over the glowing skirmisher, chat clean.
- BAD: the save had persisted the player at the battleqa village (from take 2's command section). The 5-block stage landed INSIDE a house; the side camera shows the house interior and the knights are mostly hidden behind a table and a chest.
- Take 3 overwrote the take-2 files (no backup).
- Fix for take 4 (script only): tp to the open sky platform (112.9 140.7 -158.1, facing 0) before the reel. Possible take 4: one 5-min slot, only if main wants it.

## Take 4 (11:35:45-11:40:11): filmed, NOT usable; lock released, builder + captain pinged
- Fresh copy of the Command-Film save. A Command-Film settler (courier) stood right in front of the player, and a "Social Interactions" toast showed. The F5 third-person press didn't take.
- The reel started (Sir_Aldric and Sir_Bryn joined), but the later stills are IDENTICAL frames. The game was frozen or paused, probably lost window focus, so no stage finishers were recorded. The video is 0.56 MB.
- Good still: shots/finisher/stage-6.png shows the new Ui2Hud prompt plate "[R] Finish" and the red torso glow clearly on a raider, in first person.
- Next time, if another take is ever wanted:
  - kill settlers near the player (`kill @e[type=hearthstead:settler,distance=..30]`);
  - re-focus the window (`xdotool windowactivate`) before each key press;
  - press F1-free third person after the reel teleports.
  - Not worth more slots without main's call.

## Combat agent confirmation
- With the two SettlerEntity finisher hooks in place, its 32 GameTests all passed (guard_melee_contact_, cinematic_finisher_, guard_moveset_, raider_brute_club_, raider_rework_). No seam needed.

## Lock/Gradle rules (main, after 13:00)
- The film script never calls `gradlew --stop`. It kills only processes matching its own build dir, `hsc-fin-build`. Its Gradle daemon ends through the 5-min idle timeout.
- Take the lock atomically, and only after the captain says "go":
  `(set -o noclobber; echo "finisher-agent $(date +%T) :79" > C:/Users/tobia/Hearthstead-Claude/wsl-client.lock)`
- Release it only if it is still ours:
  `grep -q '^finisher-agent' wsl-client.lock && rm wsl-client.lock`

## Take 5: approved by main, scripted, WAIT for the captain's "go"
- Queued after survival QA session 1 and the real-server follow-up.
- Script changes (tools/film_finisher.sh, syntax OK):
  - run/options.txt is set to `pauseOnLostFocus:false` at start and restored on exit;
  - the window is re-focused before every key press and command;
  - settlers, raiders and items within 48 blocks are killed after the teleport to the platform;
  - chat is cleared;
  - the player's own finisher is filmed in third-person FRONT view (camera in front of the executor), then first person just before the reel's side camera at 4.5 s.
- Run steps:
  1. Check RAM is 90% or lower.
  2. Reset the save: `rm -rf run/saves/Finisher-Film && cp -r run/saves/Command-Film run/saves/Finisher-Film && rm -f run/saves/Finisher-Film/session.lock`
  3. Take the lock atomically (noclobber): `(set -o noclobber; echo "finisher-agent $(date +%T) :79" > C:/Users/tobia/Hearthstead-Claude/wsl-client.lock)`
  4. PowerShell: `wsl -e bash -c "bash /mnt/c/Users/tobia/Hearthstead-Claude/tools/film_finisher.sh > /tmp/hsc-fin-run.log 2>&1"`
  5. Release the lock only if it is ours: `grep -q '^finisher-agent' wsl-client.lock && rm wsl-client.lock`, then ping the next agent and the captain.
  6. Check shots/finisher (glow-player, move-player, stage-*, cmd-*), then send main the 3 paths.

## OWNER RULE (13:40): finish = OFF-BALANCE AND HP < 10%
- FinishWindowTracker rewritten:
  - explicit 2.5 s off-balance spell;
  - the window is OPEN exactly while both conditions hold;
  - reserve() re-checks both on use.
- Off-balance sources start on the rising edge of: raider stagger/whiff, a heavy slowness stun (amplifier 3 or higher: warhammer, pommel stun, role stagger), a knockback of strength 0.85 or more, or a player's heavy hit. Other lanes use FinisherHooks.markOffBalance(enemy, ticks).
- Synced to clients by the new Balance payload. Client render wobble (sway around the feet) in FinisherClient. FinisherClient.isOffBalance / isFinishable are the HUD flags (the settler UI glyph uses isFinishable).
- Guards: execute only inside an open window.
- Knight Captain EXECUTION gated by FinisherHooks.executionAllowed. That's a 2-line edit in CaptainSpecialGoal (owned by the battle-roles lane ab2beb0f1e2d547df, informed).
- Handbook finish b1/b2 text updated (en_us).
- Tests:
  - JUnit: finisher 24/24, entity.combat 60/60.
  - GameTests: 3 new owner-rule tests; fixtures at 5/60 HP. Handed to the captain, not yet run.
