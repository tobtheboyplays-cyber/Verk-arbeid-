# Review findings — HARNESS-1

The numbered list a RELEASE_GATE returns is now stored here **on arrival**.
It was not, for round 1, and the round-2 reviewer could not judge whether
three of its own earlier findings had been closed because nothing in the
repository recorded what they were. That is the process defect; this file is
the fix.

---

## Round 1 — REVISE, 14 findings

**Reconstructed, not recovered.** The verdict was never written down. Numbers
1–5 and 8–13 are attested by `.claude/WORK_STATE.md`, which maps each to a fix
and an evidence path. 6 and 7 are attested by the blocker file as it stood at
commit `1b901b3`. **14 is unrecoverable** — it is not invented here.

| # | Finding | Status |
|---|---|---|
| 1 | HIGH — `expect_server` read the tmux pane, which echoes the typed command, so an assertion was satisfied before the server acted | closed, `artifacts/finding1-proof/` |
| 2 | HIGH — client suite captured the Xvfb root, so an 854x480 window passed a 1280x720 size assertion by coincidence | closed, `client/20260824T003854Z` |
| 3 | HIGH — `live film` forced a camera pan, so `motion_ok` could never fail | closed; see round 2 for what this fix in turn broke |
| 4 | `live stop` discarded `start`'s checks instead of recording the stop | closed |
| 5 | `negative`, `reap` and `provision` produced no durable evidence | closed |
| 6 | INV-2 was edited quietly instead of recording a specification correction in the quality ledger, which `CLAUDE.md` requires | closed — correction recorded in `HEARTHSTEAD_QUALITY_LEDGER.md`, sourced to D-005/D-006 |
| 7 | A stale `qa/reports/BLOCKED` made `gate` exit 0, green-lighting the very gate this slice hardened | closed — gate returned to exit 2; the file is now rewritten whenever its content stops being true |
| 8 | An unknown scenario directive was ignored rather than failing | closed |
| 9 | Manifests did not record the fingerprint that produced them | closed |
| 10 | `reap selftest` graded a re-typed copy of the matcher, not the matcher | closed |
| 11 | The pidfile kill stage did not validate a PID before killing it | closed |
| 12 | `client_boot.sh` did not write `options.txt` deterministically | closed |
| 13 | N2's revert trap was `RETURN`-only | closed |
| 14 | **unrecorded** | one of the two items `WORK_STATE` lists as "cheap extras" — the perf-population scoreboard count, or the contact sheet's AC-5 duration/fps floor — but which is not attested, so it is left open rather than claimed |

---

## Round 2 — REVISE, 8 defects

Received 2026-08-24. Findings 1, 2 and 3 of round 1 were independently
re-derived from the artefacts and confirmed closed.

| # | Severity | Defect | Status |
|---|---|---|---|
| 1 | HIGH | Freshness fingerprint did not cover `qa/scripts/`, contradicting the protocol's own `qa/** -> full` rule. Demonstrated: `4487f1b` changed verdict logic while every manifest kept reporting the same fingerprint | **fixed** — widened to `qa/scripts/**` + `qa/scenarios/**` + `qa/PROTOCOL.md`, excluding `qa/reports/**` and `__pycache__`; both implementations verified byte-identical |
| 2 | MEDIUM | D-H6 claimed the metric judges a settler's animation; it cannot attribute motion. A chat line fading scored 19.13 in the tick-frozen control | **fixed** — HUD band excluded from measurement (frozen control 0.264 → 0.19, both true positives unchanged) and the claim corrected to what it actually establishes |
| 3 | MEDIUM | `live stop`'s derived verdict was inert: no `live` subcommand ever created a check it could act on | **fixed** — `film` and `shot` now record pass/fail checks |
| 4 | MEDIUM | The shipped `live` path had zero runtime evidence; both stored sessions show pre-fix output | open — needs two cold cycles at the corrected fingerprint |
| 5 | LOW–MED | "reap check clean before and after every item" is not what the artefacts show: 3 of 15 stored reap runs FAIL, two from genuine concurrency and one a matcher false positive on the caller's own argv | **fixed** (matcher) — reap now excludes its own process ancestry, proven falsifiable; ledger claim corrected |
| 6 | LOW–MED | Findings 6, 7 and 14 had no stored mapping | **fixed** — this file |
| 7 | LOW | `reap selftest`'s recycled-PID assertions re-typed the predicate instead of exercising the guard | **fixed** — guard extracted to `pid_disposition()`, called by both `cmd_reap` and the selftest |
| 8 | LOW | `playtest.sh` hard-failed on a comment written `#like this`; `live.sh` `film`/`shot` would `mkdir -p` under `/` with an empty `EV_DIR` | **fixed** — comment glob widened, `require_ev_dir` added. (`status` and `stop` were already guarded; only `shot` and `film` were not.) |

---

## Round 2, defect 4 — closed

**The shipped `live` path had zero runtime evidence.** Closed by re-running
the matrix at the corrected fingerprint. Three `live` cycles, deliberately
both directions:

- Cycles 1-2 (default framing, no settler in view): correctly derived
  `overall: FAIL` from a genuine failed `film` check — proof `finish_result
  AUTO` is deriving, not returning the old hard-coded `STOPPED`.
- Cycle 3 (glass pen + `spreadplayers`, settler in frame): correctly derived
  `overall: PASS`, all 11 checks including `film` at `subject_mad 23.68`.

Evidence: `qa/reports/artifacts/live/{20260824T042738Z,20260824T044458Z,
20260824T051935Z}/`. Full matrix and all fingerprints:
`.claude/WORK_STATE.md`.

**Round 2 is now fully closed — all 8 defects fixed and evidenced.**
