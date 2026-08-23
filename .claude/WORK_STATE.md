# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Current goal

**SLICE HARNESS-1** — repair the QA harness (`tools/hearthstead-qa`, `qa/scripts/*`)
so in-game testing is real, isolated, torn down, and honestly gated. Plan:
`docs/project/PLAN_HARNESS-1.md`. **No `hearthstead-neoforge/src/` touched**,
except `build.gradle`'s client run args (see below — allowed, not `src/`).

## Status: implementation done and proven; hardening/repeat-run pass remains

All 14 ACs have been implemented and each individually driven at least once
with real evidence. Remaining: the "twice from cold starts" repeat-run
requirement (AC-9-style) for dedicated/performance/client/playtest/live, the
3 AC-7 teardown trials (1/3 done), the 4 AC-8 negative tests (written,
untested), AC-10 provision, AC-11 grep proof, PROTOCOL.md/KNOWN_FAILURES.md
updates.

## Files changed this slice

- `tools/hearthstead-qa`: AC-12 fix (single-suite commands never write
  `latest.json`/clear `.stale` — only `full` does); added `playtest`, `live`,
  `reap`, `provision`, `negative` commands; `doctor` now runs bash_guard +
  reap selftests.
- `qa/hooks/bash_guard.sh` + new `bash_guard_filter.py` + `test_bash_guard.py`:
  fixed false-positive blocking text inside heredocs/quotes/comments; kept
  strict fallback for `bash -c`/`eval` (real bypass risk). 11/11 selftest PASS.
- `qa/scripts/lib_harness.sh` (NEW): shared preflight (port via `lsof`, NOT
  `ss` — proven unreliable in this sandbox), pidfile registration, AC-11
  evidence scaffold (`ev_init`), `check_pass`/`check_fail`/`die`/`finish_result`.
- `qa/scripts/reap.sh` (NEW): unconditional teardown, pidfiles first then a
  restricted pattern fallback (`hsqa-inst|hsqa-server|Xvfb :9[5-9]|
  tmux.*hsqa-(live|playtest)`, GradleDaemon always excluded); `check`/
  `dry-run`/`selftest`/`reap` modes.
- `qa/scripts/server_install.sh` + `server_instance.sh` (NEW, D-H2): shared
  cached NeoForge install + per-role instance materialisation, isolated
  ports 25571(dedicated)/25572(performance)/25573(playtest)/25574(live).
- `qa/scripts/dedicated_e2e.sh`, `perf_probe.sh`: rewritten on the shared lib,
  ordered fact ladder (port free → Done( → settlers), isolated instances,
  `set -m` + `timeout --foreground` + PGID kill for real teardown.
- `qa/scripts/client_boot.sh`: rewritten; title-screen readiness is NOT a log
  string (sound is off; Realms check can stall minutes on this proxy) — it
  polls by screenshotting and running `check_screenshot.py` until real
  content renders.
- `qa/scripts/playtest.sh`: full rewrite. Console now via a dedicated tmux
  session `hsqa-playtest` (wide pane, `-x 500 -y 50`), NOT the old FIFO — a
  FIFO command sent minutes into a session sometimes silently vanished,
  reproduced in isolation; tmux fixed it. New directives: `expect_server`,
  `expect_shot`, `expect_pixel_change`, `expect_rotation_change` (AC-14).
  Test-only hooks `HSQA_TEST_BAD_EULA`, `HSQA_TEST_BAD_JOIN_PORT` for AC-8.
- `qa/scripts/live.sh`: full D-H1 tmux rewrite (`hsqa-live`, windows xvfb/
  server/client). Xvfb ignores SIGHUP from `tmux kill-session` — needs an
  explicit SIGKILL, now in both `start`'s pre-cleanup and `stop`.
- `qa/scripts/check_screenshot.py`, `pixel_diff.py`, `build_contact_sheet.py`
  (NEW): AC-3/AC-2(key)/AC-5 validators.
- `qa/scripts/negative_tests.sh` (NEW): AC-8 N1-N4, driven via the real
  controller, not simulated.
- `qa/scenarios/default.txt`: rewritten to exercise all 4 AC-2 input classes
  + AC-14 directives + AC-4 corroboration.
- `hearthstead-neoforge/build.gradle`: client run gained `--width 1280
  --height 720` program args — options.txt's overrideWidth/overrideHeight do
  NOT set the initial GLFW window size on this launch path (proven: window
  opened at vanilla's 854x480 default regardless). Not `src/`; allowed.

## Load-bearing findings from live debugging (do not re-derive)

- **`ss -ltnp` is unreliable in this sandbox** — a real leaked listener was
  invisible to it in every mode; `lsof -ti tcp:<port> -sTCP:LISTEN` finds it
  correctly. All port checks now use `lsof`.
- **GNU `timeout` creates a NEW process group for its child by default** —
  killing the outer PGID never reached the java process wrapped by a bare
  `timeout N cmd`. Fix: `timeout --foreground` everywhere a server/client is
  launched this way.
- **Xvfb ignores SIGHUP.** `tmux kill-session` sends it to a pane and the
  Xvfb window survives. Explicit `pkill -9 -f "Xvfb :NN"` required.
- **GLFW does not grab the mouse for relative look until the first real
  click into the window.** quickPlay never provides that click. `move`/
  `look` handlers now click-then-send twice (belt and braces — a single
  click didn't always survive several intervening directives, exact
  trigger not fully isolated). One-time initial click added right after join.
- **A bare console `tp <target> ~ ~ ~ ...` resolves `~` against the
  CONSOLE's own position, not the target's** — teleported to the world
  origin instead of keeping position. Fix: `execute at <target> run tp
  <target> ~ ~ ~ ...`.
- **`execute at ... run fill ...` (and likely other `execute ... run`
  wraps) suppresses ALL feedback silently** — the block placement/effect
  happens but nothing is logged, so `expect_server` can never see it. Use
  bare absolute-coordinate commands for anything whose feedback must be
  observed.
- **`fill <box> X replace X` (self-replace) is a no-op the game never
  counts**, even when X is present — this makes a "non-destructive existence
  probe via fill" fundamentally impossible; `expect_block_near_player` was
  built, proven broken this exact way, and removed. Use mod-authoritative
  state instead (`hearthstead info` etc.).
- **Placement pitch 60° is too steep** (target intersects the player's own
  hitbox); 45° reliably lands a few blocks out. Scenario uses 45.
- Client boot under software GL / this proxy can take up to ~9 minutes
  (authlib session-server calls stall) while genuinely progressing (high
  CPU, not hung) — budgets bumped accordingly, never treat slowness as a hang.

## Known problems (pre-existing, PLAQUE-1 scope, do not fix here)

KF-001 (hangPlaque punches a wall hole), KF-004 (missing plaque lang keys),
KF-005 (missing plaque_red/amber/green models). `full` will still be RED on
exactly these three.

## Next concrete action

1. Second cold-start run each: dedicated, performance, client (2 already
   done — see artifacts), playtest, live (AC-9-style "twice" requirement).
2. AC-7: 2 more teardown trials (assertion-failure abort, deliberately
   aborted `live start`) — clean-finish trial already proven via reap check
   after every real run so far.
3. Run `qa/scripts/negative_tests.sh` for real (N1-N4, AC-8).
4. `provision` (AC-10), evidence-layout grep (AC-11), `live film` for AC-5.
5. Update `qa/PROTOCOL.md` (routing table gains playtest/live; D-H5 note) and
   `docs/project/KNOWN_FAILURES.md` (KF-002/003 re-measured PASS; new
   findings above worth a KF-008 style entry if useful for future sessions).
6. Do NOT run `tools/hearthstead-qa full` as a completion claim — it will be
   RED on KF-001/004/005 by design this slice; only run it if/when doing so
   deliberately to clear the stale `qa/reports/BLOCKED` file at the very end.
