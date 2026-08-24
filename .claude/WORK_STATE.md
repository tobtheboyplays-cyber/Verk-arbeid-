# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Current goal

**SLICE HARNESS-1, CORRECTION ROUND** — RELEASE_GATE returned REVISE with a
14-item defect list (3 HIGH, 5 MEDIUM incl. two AC-8/AC-10 evidence gaps, 4
LOW + cheap extras). Fixing all in one coordinated pass. Plan:
`docs/project/PLAN_HARNESS-1.md`. **No `hearthstead-neoforge/src/` touched.**

## Status: all 14 findings fixed in code; proving + repeat-run phase in progress

**Fixed, verified by direct evidence:**
- **Finding 1** (expect_server self-satisfy): playtest.sh now reads
  `$INST/logs/latest.log` (`SRV_LOG`, the server's own Log4j file) for every
  server-content check — never the tmux pane transcript, which echoes a
  scmd's typed text before the server executes it. PROVEN with a standalone
  repro (`qa/reports/artifacts/finding1-proof/transcript.txt`): a false
  conditional's token is NOT found via SRV_LOG (correctly dies) while the
  old pane-log method would have matched the echoed keystrokes and wrongly
  passed; a true unconditional `say` IS found. Reap clean after.
- **Finding 2** (client suite root-capture false pass): client_boot.sh now
  finds the real window id, asserts ITS geometry is 1280x720 before ever
  capturing, never `-window root`. Verified: `client/20260824T003609Z`
  PASS 4/4, `screenshot-title.png` non-black bbox = (0,0,1280,720) (full
  frame), vs. the old (213,120,1067,600) letterboxed-854x480 artifact.
- **Finding 3** (forced camera pan): `live film` pan is now opt-in
  (4th arg `pan`, default off). Proof run in progress (see below).
- **Finding 4**: `live start` now writes `reproduction.md`; `stop` appends
  a `session_stopped` check and re-aggregates via `finish_result` instead of
  overwriting result.json, so `start`'s checks survive.
- **Finding 5** (no durable AC-8/AC-10 evidence): `negative_tests.sh`,
  `reap.sh`'s check/dry-run/reap dispatch, and `cmd_provision` all now call
  `ev_init`/`check_pass`/`finish_result`/`write_reproduction` — durable
  evidence under `qa/reports/artifacts/{negative,reap,provision}/<TS>/`.
- **Finding 8**: unknown scenario directive is now `die()` (hard FAIL);
  every directive (not just expect_*) gets a `check_pass` entry.
- **Finding 9**: `ev_init` now computes and records `fingerprint`/
  `dirty_hash` identically to the controller's own (added `hsqa_fingerprint`/
  `hsqa_dirty_hash` to lib_harness.sh).
- **Finding 10**: `reap.sh selftest` now calls `matching_procs()` itself
  (via injectable `HSQA_REAP_TEST_INPUT`) instead of a separately re-typed
  pattern copy — verified: `reap.sh selftest` PASS.
- **Finding 11**: `cmd_reap`'s pidfile-stage kill now validates each PID's
  live cmdline first — skips if gone, refuses (does not kill) if the PID now
  belongs to a GradleDaemon.
- **Finding 12**: client_boot.sh now writes `options.txt` deterministically
  (same block as playtest.sh/live.sh).
- **Finding 13**: negative_tests.sh N2's revert trap is now
  `EXIT INT TERM RETURN`, not RETURN-only.
- **Cheap extras**: perf_probe.sh's `PERF_POPULATION_OK` now uses a real
  scoreboard count (`execute store result score ... if entity ...`) instead
  of `limit=25` (which only caps, never requires, a minimum) — verified live:
  `performance` PASS, ~27 settlers via genuine count, MSPT 1.17. 
  build_contact_sheet.py now actually enforces the AC-5 duration/fps floor
  (`duration_ok`/`fps_ok`/`ac5_ok`), not just `motion_ok`, matching its
  docstring's claim.

**In progress this pass (see Next concrete action):** `negative all` (N1-N3
PASS so far, N4 running — long budget by design), second cold-start
client/performance runs, finding-3 settler-motion proof, `provision`,
playtest+live repeat pairs for current source.

Two dedicated cold-starts already PASS at current fingerprint
(`d26bbe2f8a...`, runs `20260824T003041Z`/`20260824T003329Z`) — done, do not
re-run unless fingerprint changes again.

## Files changed this round (on top of the prior slice's set)

`qa/scripts/playtest.sh` (SRV_LOG, DIR_IDX per-directive checks, unknown
directive dies, authoritative-log copy in teardown), `qa/scripts/
client_boot.sh` (window-geometry-verified capture, deterministic
options.txt), `qa/scripts/live.sh` (opt-in pan, start writes reproduction.md,
stop preserves checks), `qa/scripts/lib_harness.sh` (hsqa_fingerprint/
hsqa_dirty_hash, manifest gains those fields), `qa/scripts/reap.sh`
(matching_procs testable via HSQA_REAP_TEST_INPUT, pidfile-stage PID
validation + GradleDaemon exclusion, check/dry-run/reap write evidence),
`qa/scripts/negative_tests.sh` (ev_init evidence, N2 trap widened),
`qa/scripts/perf_probe.sh` (scoreboard count), `qa/scripts/
build_contact_sheet.py` (duration/fps floor enforced), `tools/hearthstead-qa`
(sources lib_harness.sh; cmd_provision writes evidence), `qa/PROTOCOL.md`
(evidence-store paragraph for negative/reap/provision + fingerprint note).

## Load-bearing findings from live debugging (do not re-derive)

- **`ss -ltnp` is unreliable in this sandbox** — use `lsof -ti tcp:<port>
  -sTCP:LISTEN`.
- **GNU `timeout` needs `--foreground`** or killing the outer PGID never
  reaches the wrapped java process.
- **Xvfb ignores SIGHUP** — explicit `pkill -9 -f "Xvfb :NN"` required.
- **`@argfile` JVM args never appear in `/proc/PID/cmdline`** — use
  `/proc/PID/cwd` instead.
- **`hsqa-inst` (bare) is a substring of `hsqa-install`** — anchor on
  `hsqa-inst/`.
- **GLFW needs a real click before relative look works**; quickPlay never
  provides one.
- **Console `tp <target> ~ ~ ~ ...` resolves `~` against the console**, not
  the target — use `execute at <target> run tp ...`.
- **`execute at ... run fill ...` suppresses ALL feedback silently.**
- **`fill <box> X replace X` is a no-op the game never counts** — query
  mod-authoritative state instead.
- **NEW this round: a tmux pane's `tee`'d log includes the pane's own
  keystroke echo**, not just genuine server output — any check reading that
  file can self-satisfy on text it merely typed. Read the real Log4j
  `logs/latest.log` for anything server-authoritative.
- **NEW this round: `import -window root` on an Xvfb screen sized to match
  the target window's expected dimensions coincidentally "passes" a size
  check regardless of the real window's actual size** — always capture and
  measure the specific window id.
- **NEW this round: `@e[...,limit=N]` bounds a selector, it does not require
  a minimum** — `if entity @e[...,limit=25]` is satisfied by 1 entity, not
  25. Use `execute store result score ... if entity ...` to get a real count.
- Client boot under software GL / this proxy can take minutes (authlib
  stalls) while genuinely progressing — never treat slowness as a hang.

## Known problems (pre-existing, PLAQUE-1 scope, do not fix here)

KF-001, KF-004, KF-005 (plaque). `full` stays RED on exactly these three.

## Next concrete action

1. Let `negative all` finish (N4 is long by design); confirm 4/4 verdicts
   stored under `qa/reports/artifacts/negative/<TS>/`, then `reap check`.
2. Run `provision` (rebuild install from scratch + verify playtest) —
   currently blocked only by port/Xvfb contention with `negative`'s N4.
3. Confirm 2nd cold-start client + performance runs (kicked off
   concurrently) landed PASS; `reap check` clean.
4. Run playtest x2 and live x2 (cold starts) for current source; for live,
   also capture the finding-3 proof (`prove_finding3.sh` in scratchpad) —
   static camera, real settler motion, median_mad > 2.0, contact sheet
   showing a visible pose change.
5. Report closure of every finding with evidence paths. Do not run `full`.
