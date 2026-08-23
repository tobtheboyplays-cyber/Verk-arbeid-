# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Current goal

**SLICE HARNESS-1** — repair the QA harness (`tools/hearthstead-qa`, `qa/scripts/*`)
so in-game testing is real, isolated, torn down, and honestly gated. Plan:
`docs/project/PLAN_HARNESS-1.md`. **No `hearthstead-neoforge/src/` touched**,
except `build.gradle`'s client run args (allowed, not `src/`).

## Status: ALL 14 ACs implemented and proven with real, stored evidence

AC-1..AC-14 each have real evidence under `qa/reports/artifacts/`. Both
"twice from cold starts" pairs done for dedicated/performance/client/
playtest/live, identical check sets confirmed. All 3 AC-7 teardown trials
done (clean finish, assertion failure, deliberately-aborted `live start` —
the exact PID-1273 shape, reproduced and cleaned). All 4 AC-8 negative
tests (N1-N4) driven for real through the controller, each asserting on the
real failure message. AC-10 provision done (install rebuilt from scratch,
verified by a passing playtest, backup deleted). AC-11 evidence-layout grep
done. AC-12 driven (doctor after a red state still leaves gate non-zero).
AC-5 film has real motion (median_mad 11.67, contact sheet opened and
inspected). Ready for RELEASE_GATE.

**Not run this slice, deliberately:** `tools/hearthstead-qa full` — it will
be RED on pre-existing KF-001/004/005 (plaque, PLAQUE-1 scope) by design.

## Files changed this slice

Same set as before plus: `qa/scripts/negative_tests.sh` OUT-dir bug fixed
(was colliding with the subcommand arg, scattering `n1/`..`n4/` dirs into
the repo root — now `HSQA_NEGATIVE_OUT` env override, fixed default);
`qa/scripts/reap.sh` pattern fixed (`hsqa-inst` → `hsqa-inst/`, was a
substring false-positive against `hsqa-install`, the shared cache dir —
found live when a harmless diagnostic echo got matched); `qa/scripts/
live.sh` `server_pid()` rewritten to scan `/proc/*/cwd` (see finding below);
`qa/scripts/lib_harness.sh` `die()` now always writes `reproduction.md`
even on failure (AC-11 needs all 5 elements unconditionally); `qa/
PROTOCOL.md` gained playtest/live/reap/provision/negative suite rows, gate-
integrity and evidence-store sections; `docs/project/KNOWN_FAILURES.md`
KF-002/003 marked RESOLVED (re-measured PASS x2), KF-006 marked RESOLVED
with full root-cause list.

Full file list otherwise unchanged from the previous checkpoint: `tools/
hearthstead-qa`, `qa/hooks/bash_guard.sh`+`bash_guard_filter.py`+
`test_bash_guard.py`, `qa/scripts/{lib_harness,reap,server_install,
server_instance,dedicated_e2e,perf_probe,client_boot,playtest,live,
check_screenshot,pixel_diff,build_contact_sheet,negative_tests}.{sh,py}`,
`qa/scenarios/default.txt`, `hearthstead-neoforge/build.gradle` (client
`--width 1280 --height 720` args only).

## Load-bearing findings from live debugging (do not re-derive)

- **`ss -ltnp` is unreliable in this sandbox** — a real leaked listener was
  invisible to it in every mode; `lsof -ti tcp:<port> -sTCP:LISTEN` finds it
  correctly. All port checks now use `lsof`.
- **GNU `timeout` creates a NEW process group for its child by default** —
  killing the outer PGID never reached the java process wrapped by a bare
  `timeout N cmd`. Fix: `timeout --foreground` everywhere.
- **Xvfb ignores SIGHUP.** `tmux kill-session` sends it to a pane and the
  Xvfb window survives. Explicit `pkill -9 -f "Xvfb :NN"` required.
- **`@argfile` JVM args never appear in `/proc/PID/cmdline`** — java expands
  them internally, not the kernel, so a `-Dmarker=...` in `user_jvm_args.txt`
  is invisible to `pgrep -f`. `/proc/PID/cwd` is reliable instead (the
  server's cwd is always its instance dir).
- **`hsqa-inst` (bare) is a substring of `hsqa-install`** — the shared,
  long-lived install cache directory. reap's pattern must anchor on
  `hsqa-inst/` (trailing slash) or it false-matches anything that merely
  mentions the install-cache path.
- **GLFW does not grab the mouse for relative look until the first real
  click into the window.** quickPlay never provides one. `move`/`look`
  click-then-send twice now (a single click didn't always survive several
  intervening directives — exact trigger not fully isolated, this is belt
  and braces).
- **A bare console `tp <target> ~ ~ ~ ...` resolves `~` against the
  CONSOLE's own position, not the target's.** Use `execute at <target> run
  tp <target> ~ ~ ~ ...`.
- **`execute at ... run fill ...` (and likely other `execute ... run`
  wraps) suppresses ALL feedback silently** — effect happens, nothing logs.
  Use bare absolute-coordinate commands for anything whose feedback matters.
- **`fill <box> X replace X` (self-replace) is a no-op the game never
  counts**, even when X is present — a fundamentally broken non-destructive
  existence probe. `expect_block_near_player` was built, proven broken this
  way, and removed. Query mod-authoritative state instead (`hearthstead
  info`).
- **Placement pitch 60° intersects the player's own hitbox**; 45° reliably
  lands a few blocks out and actually founds the settlement.
- Client boot under software GL / this proxy can take several minutes
  (authlib session-server calls stall) while genuinely progressing (high
  CPU, not hung) — budgets bumped accordingly, never treat slowness as a hang.
- A negative-test script's own `${1:-default}` for an output dir will
  silently collide if `$1` is also used as a subcommand selector elsewhere
  in the same script — use a named env var instead of positional overload.

## Known problems (pre-existing, PLAQUE-1 scope, do not fix here)

KF-001 (hangPlaque punches a wall hole), KF-004 (missing plaque lang keys),
KF-005 (missing plaque_red/amber/green models). `full` will still be RED on
exactly these three.

## Next concrete action

Slice implementation complete. Awaiting RELEASE_GATE (Opus). If resumed
with defects: address each, re-run the specific suite/AC that covers it,
and re-verify `reap check` clean afterward for anything that touches a
server/client/tmux session.
