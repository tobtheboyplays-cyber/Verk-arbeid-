# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Current goal

**SLICE HARNESS-1 — DONE.** Two review rounds (both stored in
`docs/project/REVIEW_FINDINGS.md`), 22 findings total, all closed or
explicitly recorded unrecoverable. Full evidence matrix green at fingerprint
`cebeb07b98d2...`. Opus budget spent (3/3) — no further gate call for this
task. **No `hearthstead-neoforge/src/` touched this slice.**

## Closing evidence

| Suite | Runs | Result |
|---|---|---|
| dedicated / performance / client | x2 cold each | all PASS |
| playtest | x2 cold | PASS — **70/70 identical checks both runs** |
| negative all | x1 | PASS 4/4 in one invocation |
| provision | x1 | PASS |
| live | x3 | **FAIL, FAIL, PASS** |

`live`'s three-run shape is deliberate, not noise: cycles 1-2 (default
framing, no settler in view) correctly derived `overall: FAIL` from a genuine
failed `film` check — proof the verdict is no longer the old hard-coded
`STOPPED` literal. Cycle 3 (glass pen + `spreadplayers`, settler in frame)
correctly derived `overall: PASS`, `film` at `subject_mad 23.68`. Both
directions proven live is what makes the derivation trusted, not just
unit-tested. Evidence: `qa/reports/artifacts/live/{20260824T042738Z,
20260824T044458Z,20260824T051935Z}/`.

Round 2's motion-metric fix holds under both a false positive it was built to
catch (a chat line fading, HUD band now excluded) and a true positive found by
accident (a distant iron golem, correctly attributed as "something moved" not
"the settler moved" — see PROTOCOL D-H6 for exactly what the number claims).

## Key decisions this slice, for anyone picking this up cold

- **Freshness fingerprint widened** to cover `qa/scripts/**` +
  `qa/scenarios/**` (excludes `qa/reports/**`, `__pycache__`). Two
  implementations (`tools/hearthstead-qa`, `qa/scripts/lib_harness.sh`) MUST
  stay byte-identical — verified, and re-verify after touching either.
- **Motion (`film`) judges the loudest tile of a 16x9 grid**, HUD band (bottom
  2 rows) excluded, not the whole-frame average — a small subject averages to
  noise otherwise. `subject_mad > 2.0` = pass. Establishes "something in the
  world animated", never "this specific settler moved" — that needs a human
  reading the contact sheet.
- **`live start` sets a deterministic observation state** (peaceful, creative,
  day, clear, no spawns) and asserts `Health > 0` — a joined-but-dead player
  silently freezes the world (dead players stop nearby chunks ticking) while
  the session still looks "up".
- **`live stop` derives `overall` from stored checks** (`finish_result AUTO`),
  never a hard-coded literal. `film`/`shot` now record checks so there is
  something to derive from.
- **`reap` excludes its own process ancestry**, not by text pattern (a
  caller's argv can legitimately contain the harness path being searched for
  — no pattern fixes that) but by walking `/proc/*/stat` parent links.

## Load-bearing findings from live debugging (do not re-derive)

- `ss -ltnp` unreliable here — use `lsof -ti tcp:<port> -sTCP:LISTEN`.
- GNU `timeout` needs `--foreground`, else killing the PGID misses java.
- Xvfb ignores SIGHUP — explicit `pkill -9 -f "Xvfb :NN"`.
- `@argfile` JVM args never reach `/proc/PID/cmdline` — use `/proc/PID/cwd`.
- `hsqa-inst` is a substring of `hsqa-install` — anchor on `hsqa-inst/`.
- GLFW needs a real click before relative look works; quickPlay never gives one.
- Console `tp <t> ~ ~ ~` resolves `~` against the CONSOLE — use `execute at`.
- `execute at ... run fill ...` suppresses ALL feedback silently.
- `fill <box> X replace X` is a no-op the game never counts.
- A tmux pane's `tee`'d log echoes the pane's own keystrokes — read the real
  Log4j `logs/latest.log` for anything server-authoritative.
- `import -window root` on an Xvfb screen sized like the target window passes
  a size check regardless of the window's real size — capture the window id.
- `@e[...,limit=N]` bounds a selector, does not require a minimum.
- **Never run two suites at once** — every one launches a client and a
  server, and a cold start's broad `pkill` kills the other's client.
- Client boot under software GL / this proxy can take minutes (authlib
  stalls) while genuinely progressing — never treat slowness as a hang.

## Known problems (pre-existing, PLAQUE-1 scope, do not fix here)

KF-001, KF-004, KF-005 (plaque). `full` stays RED on exactly these three —
by design; see KF-008. `full` was not run this slice for that reason.

## Next concrete action

**SLICE PLAQUE-1**, from `docs/project/PLAN_PLAQUE-1.md` — state machine,
the save-compat failure mode (an existing world's plaques must not load as
EMPTY after the state ids are renamed), W1-W9 with acceptance criteria. Fully
pre-worked: 39 missing lang keys derived from source, 41 bilingual strings
drafted and argument-checked (`hearthstead-neoforge/docs/plaque_lang_draft.json`),
the lamp-art gap in `gen_plaque.py` identified. Hand to sonnet-builder for
Phase 4 of the premium-build-loop (PLAN_GATE already done, no second Opus
call needed).
