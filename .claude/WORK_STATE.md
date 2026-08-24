# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Current goal

**SLICE HARNESS-1, ROUND 2 OF CORRECTION.** RELEASE_GATE re-review returned
REVISE with 8 defects; it independently re-derived findings 1, 2 and 3 from the
artefacts and confirmed them closed. Full list, both rounds:
`docs/project/REVIEW_FINDINGS.md`. **No `hearthstead-neoforge/src/` touched.**

## Status: 7 of 8 defects fixed in code; the matrix must now be re-run

The one that is not fixed is defect 4 — the shipped `live` path has no runtime
evidence — and it cannot be fixed by editing, only by running. See below.

- **D1 HIGH (fingerprint too narrow) — FIXED.** Now covers `qa/scripts/**` and
  `qa/scenarios/**` as well, excluding `qa/reports/**` (every run writes there,
  so it would never settle) and `__pycache__` (a generator rewrites it). Both
  implementations verified byte-identical by running them side by side.
- **D2 (metric overclaimed) — FIXED.** HUD band (bottom two grid rows: chat,
  hotbar, held item) excluded — a chat line fading scored **19.13** in the
  tick-frozen control, matching three walking settlers, and failed only by
  luck of the median. Frozen control 0.264 → **0.19**; both true positives
  unchanged (19.79 walking, 3.03 distant golem). Docs now say the number means
  "capture is live and something in frame animates", never "this settler moved".
- **D3 (derived verdict inert) — FIXED.** `film` and `shot` record pass/fail
  checks, so `stop`'s derived verdict has something to derive from.
- **D5 (reap matcher false positive) — FIXED.** `reap` excluded its own caller
  by *text*, which cannot work — a wrapper's argv legitimately contains the
  harness path. Now excluded by process ancestry, and the selftest assertion
  **fails if the exclusion is removed** (verified against a broken copy).
- **D6 (findings unmapped) — FIXED.** `docs/project/REVIEW_FINDINGS.md`. Round
  1's #14 is left explicitly unrecoverable rather than invented.
- **D7 (selftest re-typed the guard) — FIXED.** Guard extracted to
  `pid_disposition()`; `cmd_reap` and the selftest call the same function.
- **D8 (latent traps) — FIXED.** `#like this` comments no longer hard-fail a
  scenario; `require_ev_dir` stops `film`/`shot` from `mkdir -p` under `/`.
  (`status` and `stop` were already guarded — only those two were not.)

## Correction to a claim I made

"`reap check` clean before and after every item" was **not** true as written.
3 of 15 stored reap runs FAIL: two (`003615Z`, `003854Z`) correctly reported
real leaks during the earlier period when suites were run CONCURRENTLY, and one
(`002935Z`) was the matcher false positive now fixed. The accurate statement is
that reap was clean before and after every run once the runs were serialised —
which is also what made the concurrency the visible cause of the earlier N4
FAIL.

## The matrix must be re-run — the old one is invalid

Every stored manifest reports `d26bbe2f8a…`. HEAD is a different fingerprint
under either definition, because the round-1 documentation commit edited
`qa/PROTOCOL.md`, which was already covered. So widening the definition costs
nothing that was not already lost. Re-run, serially, `reap check` between each:

    dedicated x2 · performance x2 · client x2 · playtest x2 · live x2
    negative all · provision

`live` is the one that MUST be re-run for correctness, not just freshness: both
stored sessions show `overall: STOPPED`, the pre-fix literal, so the code that
ships at HEAD has never executed (D4).

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
- **No text pattern can exclude a process's own caller** — the caller's argv
  legitimately contains what you are searching for. Exclude by identity.
- Client boot under software GL / this proxy can take minutes (authlib stalls)
  while genuinely progressing — never treat slowness as a hang.
- **Never run two suites at once.** Every one launches a client and a server,
  and a cold start's broad `pkill` kills the other's client.

## Known problems (pre-existing, PLAQUE-1 scope, do not fix here)

KF-001, KF-004, KF-005 (plaque). `full` stays RED on exactly these three.
PLAQUE-1 is planned and pre-worked: `docs/project/PLAN_PLAQUE-1.md`, the 39
missing lang keys derived from source, and 41 bilingual strings drafted and
argument-checked in `hearthstead-neoforge/docs/plaque_lang_draft.json`.

## Next concrete action

Re-run the matrix above at the corrected fingerprint. Then PLAQUE-1. The Opus
budget for this task is spent (3 of 3) — no further gate call; the evidence
either shows the matrix green at one fingerprint or it does not.
