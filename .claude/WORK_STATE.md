# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Current goal

**SLICE PLAQUE-1 — code done; one playtest interaction is harness-blocked
(KF-009), not a mod defect.** Full findings: `docs/project/KNOWN_FAILURES.md`
KF-009. Blocker file: `qa/reports/BLOCKED`.

## What's done and evidenced

- **W1-W9 all implemented.** Blank plaque (EMPTY state, no UI, dark lamp),
  separate `BuildPlanItem` (39 keys, 41 bilingual strings), 5-state machine
  (`EMPTY -> PLAN_INSERTED_UNLINKED -> LINKED_INCOMPLETE/LINKED_VALID`,
  `ORPHANED`), save-compat via a legacy-id alias table (never a bare `EMPTY`
  fallback), 4-value lamp art (off/red/amber/green) via a base model + 3-line
  child variants, 7 recipes (plaque + 6 build plans), KF-001's `hangPlaque()`
  fix.
- **`assets` PASS 230/230** at current fingerprint — closes KF-004, KF-005.
- **`gametest` PASS 19/19** at current fingerprint (twice, at two different
  fingerprints as scenario edits landed) — closes KF-001. Includes
  `legacyPlaqueStateLoadsWithoutLosingBuilding`, which loads a SYNTHETIC OLD
  TAG (`State="linked"` + a `Building` UUID) and proves it resolves to
  `LINKED_VALID`, not the un-homing `EMPTY` a naive fallback would produce.
- **The interaction itself proven correct via manual reproduction** — see
  KF-009 below. This is the load-bearing fact: the MOD is not broken.

## KF-009 — the one open item, read the full entry before touching this again

`playtest`'s PLAQUE-1 section fails two `expect_server` checks. Diagnosed at
length (settlement absence, aim/geometry, session/GLFW staleness, stale
window id — all ruled out with direct evidence, two real hardening fixes
applied to `qa/scripts/playtest.sh` and kept). Root cause not isolated;
matches KF-006's pre-existing, unresolved click/move flakiness class, now
shown to also affect `click` and `cmd` late in a long scenario. **Do not
re-run the same four ruled-out experiments** — KF-009 lists unexplored
directions for whoever continues this.

## Next concrete action

1. Confirm `git push` landed (this round: KF-009 write-up, BLOCKED rewrite,
   `qa/scripts/playtest.sh` hardening, `qa/scenarios/default.txt` fixes).
2. Send PLAQUE-1 to RELEASE_GATE (one Opus call — 0 of 3 spent on this slice
   so far) with KF-009 as the explicit open question: is GameTest +
   independently-verified manual reproduction sufficient proof to call this
   slice done with the scripted playtest check BLOCKED, or does it need
   more harness work first. Do not self-judge this — that is exactly what
   the gate is for.
3. If PASS or accepted-BLOCKED: SLICE VISUAL-1 next (fix KF-007's
   `gen_settler.py` non-determinism first, then modular settler visuals).

## Load-bearing findings from live debugging (do not re-derive)

- `ss -ltnp` unreliable here — use `lsof -ti tcp:<port> -sTCP:LISTEN`.
- GNU `timeout` needs `--foreground`, else killing the PGID misses java.
- Xvfb ignores SIGHUP — explicit `pkill -9 -f "Xvfb :NN"`.
- `hsqa-inst` is a substring of `hsqa-install` — anchor on `hsqa-inst/`.
- GLFW needs a real click before relative look works; quickPlay never gives one.
- Console `tp <t> ~ ~ ~` resolves `~` against the CONSOLE — use `execute at`.
- `execute at ... run <anything>` suppresses ALL feedback silently — this
  bit me AGAIN this round on a `data get block` diagnostic probe; always
  use bare absolute coordinates when you need to SEE a command's output.
- `fill <box> X replace X` is a no-op the game never counts.
- A tmux pane's `tee`'d log echoes the pane's own keystrokes — read the real
  Log4j `logs/latest.log` for anything server-authoritative.
- **`tp ... facing <x> <y> <z>` computed rotations look correct in a
  screenshot but do NOT reliably reproduce a working interaction the way a
  fixed `yaw=0 pitch=0` rotation at a matching stand-back offset does** —
  proven by direct A/B repro this round. Prefer fixed rotations + a computed
  stand-back position over `facing` for anything that needs to click.
- **A `PlaqueScreen` (or any screen) left open from an earlier manual test
  silently absorbs later clicks as UI interaction, not world interaction** —
  always `key Escape` between unrelated manual test iterations, or you will
  chase a phantom "click doesn't work" bug that is actually your own
  leftover UI state. (This is NOT what KF-009 turned out to be, but cost
  real time to rule out.)
- **Never nest `nohup cmd &` inside a `run_in_background: true` Bash call** —
  the tool only tracks the launcher (which exits almost instantly), not the
  backgrounded suite; the "completed" notification is a false signal and
  the real process can keep running, overlapping with whatever you start
  next. Pass the suite command directly to a backgrounded Bash call instead.
- **`playtest.sh`'s `focus()` used to cache `$WIN` once at boot and swallow
  its own exit code unconditionally** — now self-healing (checks the exit
  code, re-searches on failure). `live.sh`'s equivalent already re-searches
  fresh every call and never had this defect.
- `@e[...,limit=N]` bounds a selector, does not require a minimum.
- **Never run two suites at once** — every one launches a client and a
  server, and a cold start's broad `pkill` kills the other's client.
- Client boot under software GL / this proxy can take minutes (authlib
  stalls) while genuinely progressing — never treat slowness as a hang.

## Known problems (pre-existing, other slices' scope)

KF-007 (`gen_settler.py` non-determinism) — VISUAL-1's first task.
