# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Current goal

**SLICE HARNESS-1, CORRECTION ROUND** — RELEASE_GATE returned REVISE with 14
findings. All 14 are fixed AND proven. Proving them surfaced four further
defects, also fixed. Plan: `docs/project/PLAN_HARNESS-1.md`.
**No `hearthstead-neoforge/src/` touched in this round.**

## Status: run matrix complete at fingerprint `d26bbe2f8a...`, ready for re-review

| Evidence | Result |
|---|---|
| `dedicated` x2 cold | PASS, population 3, no client classloading |
| `performance` x2 cold | PASS, ~27 settlers by real scoreboard count, MSPT ~1.17 |
| `client` x2 cold | PASS 4/4, non-black bbox (0,0,1280,720) full frame |
| `playtest` x2 cold | PASS, **70 identical checks both runs** |
| `live` x2 cold | both drove start/status/shot/key/scmd/film/stop, torn down clean |
| `negative all` | **4/4 PASS in ONE invocation**, all transcripts stored |
| `provision` | PASS — install rebuilt from scratch, verified by a passing playtest |
| `reap check` | clean before and after every item above |

Every manifest records this fingerprint and a clean `dirty_hash`.

## The 14 review findings — all closed

1 (expect_server self-satisfy) playtest.sh reads `$INST/logs/latest.log`, never
the tmux pane; proven by standalone repro `artifacts/finding1-proof/`. •
2 (client root-capture false pass) client_boot.sh asserts the window's own
geometry before capturing; bbox went (213,120,1067,600) → (0,0,1280,720). •
3 (forced pan) pan is opt-in; **proven in both directions**, see below. •
4 `live start` writes reproduction.md; `stop` preserves start's checks. •
5 negative/reap/provision all write durable evidence. • 8 unknown directive
now dies. • 9 manifests carry fingerprint/dirty_hash. • 10 reap selftest calls
the real `matching_procs`. • 11 pidfile kill validates each PID, refuses
GradleDaemons. • 12 client_boot writes options.txt deterministically. •
13 N2's revert trap widened to EXIT INT TERM RETURN. • Extras: perf population
is a real scoreboard count; contact sheet enforces the AC-5 duration/fps floor.

## Four defects found WHILE proving finding 3 (all fixed)

- **The motion check could no longer pass.** Making the pan opt-in removed
  what guaranteed inter-frame difference but left a threshold only a pan can
  reach: whole-frame mean-abs-difference is dominated by the ~90% of pixels
  that never change, so three settlers plainly walking scored **0.34** against
  a threshold of 2.0. Same unfalsifiability as before, mirrored. Now measured
  as the **loudest tile of a 16x9 grid** (`subject_mad`), whole-frame reported
  alongside so a pan stays distinguishable. **Proven both ways on one framing:
  settlers walking 19.79 PASS; identical shot with `tick freeze` 0.26 FAIL.**
  Evidence: `artifacts/live/20260824T013205Z/film/take-01-settler-motion/`
  and `take-02-frozen-control/`.
- **`live start` left a world that stops running.** Unattended, a Slime killed
  the player; a dead player stops holding nearby chunks at full ticking, so a
  hearth placed afterwards never founded — and nothing moved — while `live
  status` still reported the session up. `start` now sets a deterministic
  observation state (peaceful/creative/day/clear/no-spawns) and asserts
  **Health > 0**, not merely "connected". Verified on a cold start:
  `player_alive -- observation state set, Health=20.0`.
- **Each `film` overwrote the previous take**, so proving a claim that needs a
  passing take AND a failing control destroyed the first. Takes now get their
  own directory (`film/take-NN[-label]/`, label via `HSQA_FILM_LABEL`).
- **`live stop` hard-coded `overall: STOPPED`**, so a session carrying a FAILED
  check recorded identically to a clean one. `finish_result AUTO` now derives
  the verdict from the checks; unit-tested both ways.

## Files changed this round

`qa/scripts/{playtest,client_boot,live,lib_harness,reap,negative_tests,
perf_probe}.sh`, `qa/scripts/build_contact_sheet.py`, `tools/hearthstead-qa`
(sources lib_harness; provision writes evidence; `negative` added to usage),
`qa/PROTOCOL.md`, `docs/project/{NEXT_ACTION,PLAN_PLAQUE-1}.md`.

## Load-bearing findings from live debugging (do not re-derive)

- `ss -ltnp` is unreliable here — use `lsof -ti tcp:<port> -sTCP:LISTEN`.
- GNU `timeout` needs `--foreground`, else killing the PGID misses java.
- **Xvfb ignores SIGHUP** — explicit `pkill -9 -f "Xvfb :NN"`.
- `@argfile` JVM args never reach `/proc/PID/cmdline` — use `/proc/PID/cwd`.
- `hsqa-inst` is a substring of `hsqa-install` — anchor on `hsqa-inst/`.
- GLFW needs a real click before relative look works; quickPlay never gives one.
- Console `tp <t> ~ ~ ~` resolves `~` against the CONSOLE — use `execute at`.
- `execute at ... run fill ...` suppresses ALL feedback silently.
- `fill <box> X replace X` is a no-op the game never counts.
- A tmux pane's `tee`'d log contains the pane's own keystroke echo — read the
  real Log4j `logs/latest.log` for anything server-authoritative.
- `import -window root` on an Xvfb screen sized like the target window passes a
  size check regardless of the window's real size — capture the window id.
- `@e[...,limit=N]` bounds a selector, it does not require a minimum.
- **A dead player stops nearby chunks ticking** — block entities near them stop
  too, so the world silently freezes while the session still looks "up".
- Client boot under software GL / this proxy can take minutes (authlib stalls)
  while genuinely progressing — never treat slowness as a hang.

## Note on the fingerprint

The matrix above was measured at `d26bbe2f8a...`; every manifest records it.
The documentation commit that describes these fixes edits `qa/PROTOCOL.md`,
which IS in the fingerprint, so it moves afterwards — documenting evidence
after gathering it is the normal order, and HARNESS-1 claims no completion:
PLAQUE-1's `full` re-establishes the gate.

## Open question for the re-review (not fixed unilaterally)

The freshness fingerprint covers `qa/PROTOCOL.md` but **not `qa/scripts/`**,
even though the routing table says `qa/** -> full` and those scripts decide
what every suite asserts. Changing the definition invalidates the whole matrix
above, so it is raised rather than changed mid-round.

## Known problems (pre-existing, PLAQUE-1 scope, do not fix here)

KF-001, KF-004, KF-005 (plaque). `full` stays RED on exactly these three.
PLAQUE-1's plan is written: `docs/project/PLAN_PLAQUE-1.md`.

## Next concrete action

One short `opus-quality-gate` re-review of the changed areas only (Opus call 3,
the governor's absolute maximum). Then start PLAQUE-1 from its plan.
