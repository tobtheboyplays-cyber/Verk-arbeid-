# PLAN — HARNESS-1 (PLAN_GATE output, approved)

Repair the QA harness so an in-game session is launched, driven, proven and
torn down reliably, and so that when it fails it names the true first cause.

**No file under `hearthstead-neoforge/src/` is touched by this slice.**

## Evidence established at PLAN_GATE (PROVEN)

- **KF-006 root cause found.** The "player never joined the world" run failed
  because `./gradlew runClient` hit a **compile error** — the tree was
  mid-refactor (`Settlement.java:58 Building.Type.HOME`, `PlaqueBlock.java:144`,
  10 errors). The server was healthy (`Done (0.594s)`). The harness waited for
  a join that could never come, then reported a downstream symptom. Not a flake.
- **KF-002/003 confirmed** as `Address already in use` in both
  `dedicated-first.log` and `performance.server.log`.
- **KF-002 does not block an in-game session.** `SettlementManager.tryFound`
  spawns 3 settlers unconditionally — no room, plaque or building gate.
- **The gate can be laundered green.** Every single-suite command calls
  `write_manifest`, overwriting `latest.json` with `overall: PASS`,
  `green_streak: 1` and only that suite, and `rm -f .stale`. So
  `tools/hearthstead-qa doctor` alone turns a red gate green.
- **`playtest.sh` and `live.sh` are unreachable** through the only approved
  entry point — orphaned, run by hand, which `qa/PROTOCOL.md` forbids.
- **Persistent sessions work** — `setsid nohup` and `tmux` both survived across
  separate Bash invocations (probed). `ffmpeg -f x11grab` + libx264 and
  `import -window` both work on bare Xvfb.
- **No window manager** → `xdotool windowactivate` (needs EWMH) fails; use
  `windowfocus` + XTEST, and never `key --window` (GLFW discards XSendEvent).
- **No `x11vnc`** → live video streaming is impossible here. Deliver stills and
  an openable mp4.

## Requirements

REQ-1 ordered fact ladder (report the first wrong fact, with its own id) ·
REQ-2 isolation (no port or world contention) · REQ-3 guaranteed teardown on
every exit path · REQ-4 persistent session · REQ-5 judgeable motion ·
REQ-6 install rebuildable on demand · REQ-7 one evidence store ·
REQ-8 gate integrity · REQ-9 reachable only via the controller.

## Design decisions

- **D-H1 tmux, not FIFO.** Named session `hsqa-live` with windows
  `xvfb`/`server`/`client`; console via `tmux send-keys`, output via
  `capture-pane`. Removes the `sleep 86400 > fifo` writer that leaked PID 1273
  and gives the server a real TTY stdin.
- **D-H2 shared install, per-role instances, distinct ports.**
  `server_install.sh` provisions the cached install (idempotent);
  `server_instance.sh <role> <port>` materialises
  `/tmp/claude-0/hsqa-inst/<role>/` with `libraries` symlinked, fresh world,
  current jar, own `server.properties`. Ports: dedicated 25571, performance
  25572, playtest 25573, live 25574.
- **D-H3 evidence: reconcile, do not fork.** Canonical store stays
  `qa/reports/artifacts/`, keyed `<scenario-id>/<timestamp>/`; a repo-root
  symlink `artifacts/qa -> qa/reports/artifacts` makes the contract's path
  resolve literally. Existing flat dirs stay (KNOWN_FAILURES cites one by
  path); readers glob both shapes.
- **D-H4 input via XTEST with explicit focus** (`windowfocus --sync`);
  `windowactivate` and `key --window` forbidden, with the reason in a comment.
- **D-H5 no live streaming.** Record the limitation in `qa/PROTOCOL.md` beside
  the sound limitation; deliver stills, `clip.mp4`, and a `watch` mode that
  refreshes a PNG at a stable path.

## Acceptance criteria

Evidence for all of these lands under
`qa/reports/artifacts/<scenario-id>/<TS>/`.

- **AC-1** join proven **server-side**: `result.json.checks.joined = PASS`
  quoting the server's `<player> joined the game` line and a console
  `data get entity <player> Pos`. Client-side signals do not satisfy it.
- **AC-2** four input classes, each proven by a non-client observable — **cmd**
  server log shows execution; **key** shot differs >2% of pixels in the lower
  third; **click** right-click places the hearth and
  `execute if block … run say HS_CLICK_OK` echoes; **look** server-side
  `Rotation` changes >30°. Any one failing fails the suite.
- **AC-3** every PNG in `shots/` is 1280×720, captured with the recorded window
  id, mean luminance >8/255 and >500 distinct colours. The 22-colour and
  2-colour artefacts from the failed runs must be rejected.
- **AC-4** `shots/04-settlement.png` passes AC-3 **and** is corroborated
  server-side (`hearthstead info` population ≥3, plus a settler-entity echo).
  The builder must open the image and say what is in it.
- **AC-5** `film/clip.mp4` ≥3 s at ≥20 fps, ffprobe-decodable;
  `film/contact-sheet.png` with ≥12 evenly sampled frames ≥426×240, labelled
  with frame index and elapsed ms; `motion_ok = true` from a median
  inter-frame mean-absolute-difference threshold. Frozen or black FAILS.
- **AC-6** `live start`, `status`, `cmd`, `shot`, `film`, `stop` each in a
  **separate** Bash invocation against one session; `status` reports the same
  server PID and player each time; ≥2 shots pass AC-3.
- **AC-7** teardown proven in three trials — clean finish, assertion failure,
  and a **deliberately aborted `live start`** (the PID-1273 scenario) — each
  followed by `reap --check` reporting zero harness processes and all four
  ports free.
- **AC-8** four *driven* negative tests: **N1** port held → names the holding
  PID/cmdline, says nothing about settlers; **N2** client build broken → says
  the client build failed and quotes the first javac error, and does **not**
  say "player never joined"; **N3** server never reaches `Done (` → says that;
  **N4** client up but no join → says that and stores `FAILED-state.png`.
- **AC-9** two consecutive cold-start `playtest` runs both PASS with identical
  check sets. 1/2 is FLAKY, which is not PASS, and stops the slice.
- **AC-10** with the install moved aside, `provision` rebuilds it and a
  following `playtest` passes; only then delete the backup.
- **AC-11** one evidence store; each scenario dir has `manifest.json`,
  `result.json`, `reproduction.md`, `logs/`, `shots/`; proven by a grep showing
  no other artifact write path.
- **AC-12** gate cannot be laundered: from a red state, `doctor` then `gate`
  → still non-zero, saying no full-scope run exists. `green_streak` increments
  only on full-scope PASS; `latest.json` written only by `full`.
- **AC-13** `dedicated_e2e.sh` and `perf_probe.sh` assert `Done (` before
  anything about settlers, use isolated ports, and preflight. Back-to-back
  `dedicated` then `performance` must not clash.
- **AC-14** scenario language gains `expect_server`, `expect_shot`,
  `expect_rotation_change`; `result.json` records a per-directive outcome so a
  directive that silently did nothing is visible.

## Regression risks

The controller is every suite's single dependency (readers must accept both
artifact layouts; `bash -n` + dry-run `gate`/`status`/`changed` before any
launch) · AC-12 will make the Stop hook fail until a full-scope run exists —
correct, but its message must name the next command · editing
`qa/PROTOCOL.md` invalidates every manifest (expected) · `dedicated`/
`performance` going green must be reported as *the harness stopped poisoning
them*, never as a mod fix · **`reap.sh` must not kill the Gradle daemon** —
pidfile first, pattern fallback restricted to
`hsqa-inst|hsqa-server|Xvfb :9[5-9]|tmux.*hsqa-live` with `GradleDaemon`
excluded, plus a dry-run test of the matcher · the dev client rewrites
`options.txt` on exit, so write it deterministically each run and assert
1280×720 · `run/client/` is stale from the old wrong-game-dir assumption —
never write there, and comment why.

## Out of scope

The plaque slice entirely, with KF-001, KF-004, KF-005. KF-007, settler
appearance, the animation catalogue. **Any change under
`hearthstead-neoforge/src/`** — if the repaired harness reveals a genuine mod
defect, write it up as a new KF for a later slice.

KF-002/KF-003 are pulled in **only as harness defects** (leakage, contention,
misordered assertions). After the fix they are re-measured from fresh
evidence.

## Completion (coordinator decision)

`full` cannot go green in this slice: it includes `gametest` and `assets`,
red on the pre-existing KF-001/004/005 which belong to PLAQUE-1.

**Decision: HARNESS-1 locks on a slice-scoped gate** — AC-1..AC-14 satisfied,
and `dedicated` + `performance` + `client` + `playtest` + `live` green twice
from cold starts, with `full` still red on exactly those three plaque items,
recorded as pre-existing. PLAQUE-1 then takes `full` green. The harness is the
measuring instrument; it is fixed first so the plaque slice can be *proven*
rather than described.
