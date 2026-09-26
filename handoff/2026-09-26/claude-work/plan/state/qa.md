# Super QA lane: state at pause (26 Sep 2026, around 06:20)

Paused by the lead to save weekly usage. Resume from here.

## Done
- Audit A static is complete: plan/QA-BUTTONS.md.
  - Covers 22 screens plus the keybinds, event chat buttons, conversation and barter.
  - About 100 PASS rows and 27 findings (Q-001 to Q-027).
- Audit B static loop mapping is complete for all 33 professions: plan/QA-JOBS.md, with 17 findings (J-01 to J-17).
- The journey UI-path sweep is done. Only fj_230 is blocked (Q-001, routed to the Banner lane by the lead).

## My code edits (all javac-checked, not yet seen in game)
1. `client/screen/BannerOrderScreen.java:37`: the tooltip shows `no_team` when members = 0 (Q-004).
2. `client/ClientHooks.java`: new `leaveContainerScreen(mc)` before 7 `setScreen(new …)` sites. It closes the Banner container when swapping to a plain screen (Q-011). The file has mixed CRLF/LF; inserted lines match their neighbours.
3. `entity/SettlerEntity.java` `setProfessionProjection`: `clearLogisticsStop()` on every profession change (J-12).

Lightweight compile method (no gradle):
- Run `javac -proc:none -implicit:none -sourcepath src/main/java`.
- Classpath: `scratchpad/jc/cp.txt` (from soak/javac-cp-after3b.txt, first entry swapped to build-agent-settlerui classes) plus the jetbrains annotations-24.1.0.jar.

## Fixed by other lanes after my reports
- Q-012 (Guard Orders button for the battle roles) and Q-008 (stale GameTest): Battle roles lane.
- Q-007 (design delete owner check): Builder lane.

## Assigned, still open
- Banner lane (acd0a5a18e727f5b9): Q-001, Q-002, Q-003 (via the lead), Q-009, Q-013 to Q-021.
- Settler UI lane (a0035f4128be96961): Q-006, Q-022 to Q-026.
- Command lane (a77dcc6059299345d): Q-005 (**not yet messaged**).
- Audit B findings J-01 to J-17 are **not yet messaged** to their lanes. The owners are listed in QA-JOBS.md. Send them on resume:
  - Battle roles: J-02, J-04, J-06, J-08.
  - Bug hunter: J-03, J-05.
  - Economy: J-01, J-09.
  - Tavern: J-07.
  - Animation: J-13.
  - Builder: J-15.
  - Captain: J-17 tests.
- **Message the lead that audit B static is complete** (not yet sent; the lead asked for the pause first).

## Next (dynamic)
- Queue in WSL-QUEUE.md (not yet added).
- The dynamic plan is at the bottom of both QA files: button clicks at GUI scales 2, 3 and 4 with 4 and 40 settlers, and a soak with the watchdog on for the 16 released jobs, including a restart.
- Verify the three edits in game, above all Q-011: Banner → Tech Tree → Esc → E → move an item.

## Update (after pause): Banner lane report
- Banner lane (acd0a5a18e727f5b9) reports these fixed and marked in QA-BUTTONS.md: Q-001, Q-002, Q-003, Q-010, Q-013, Q-014, Q-015, Q-016, Q-017, Q-020, Q-021, Q-025.
- Q-019 is partly fixed: the held-stack message is added. The VIEW_SETTLER and OPEN_DEVELOPMENT silent refusals remain, in other lanes' files.
- Q-018 is left in place: the dead branches are guarded by a flag that is never true.
- Evidence so far is compile and JUnit only. The dynamic pass must verify: fj_230 completes when Storage opens; the stacked layout at GUI 4 shows Mayor and Review; Esc closes the popout first.

## RAM rule (from the captain, in WSL-CLIENT-LOCK.md)
- Every Gradle call uses `-Dorg.gradle.daemon.idletimeout=300000` or `--no-daemon`.
- No lingering background builds.
- Wait whenever RAM is over 90%.
- My javac-only checks start no Gradle daemon; keep using them, capped at `-J-Xmx1500m`.

## Update: Settler UI lane (a0035f4128be96961)
- Fixed: Q-022, Q-023, Q-024, Q-026 (compile and JUnit 13/13, not seen in game).
- Still open and needs routing (not their files):
  - Q-006 PlaqueScreen disabled tooltips. Owner unclear: ask the lead or send to the bug hunter a8450891fcfc4a7a2.
  - The "Back to sheet" button in SettlerInventoryScreen (info item).
