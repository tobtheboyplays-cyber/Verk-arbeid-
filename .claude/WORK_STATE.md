# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Current goal

**SLICE PLAQUE-1 — code done, KF-009 fully resolved (7 real harness bugs
found and fixed, no mod defect at any point). A `full` run against a
freshly rebuilt jar is verifying end to end** — check its result before
declaring the slice complete; two consecutive clean `full` passes at one
fingerprint are required (see `qa/PROTOCOL.md`).

## What's done and evidenced

- **W1-W9 all implemented.** Blank plaque (EMPTY state, no UI, dark lamp),
  separate `BuildPlanItem` (39 keys, 41 bilingual strings), 5-state machine
  (`EMPTY -> PLAN_INSERTED_UNLINKED -> LINKED_INCOMPLETE/LINKED_VALID`,
  `ORPHANED`), save-compat via a legacy-id alias table, 4-value lamp art
  (off/red/amber/green), 7 recipes, KF-001's `hangPlaque()` fix.
- **`assets` PASS 230/230** — closes KF-004, KF-005.
- **`gametest` PASS 19/19** (re-verified after the `info()` source fix,
  commit 09c3c7a) — closes KF-001. Includes
  `legacyPlaqueStateLoadsWithoutLosingBuilding`.
- **`playtest` — see KF-009.** Every check in `qa/scenarios/default.txt`
  now passes when run against a jar actually built from current source;
  the harness itself was the last blocker, not the mod.

## KF-009 — resolved, read the full entry (docs/project/KNOWN_FAILURES.md) before touching this area again

Seven real, distinct bugs, found across two Opus gate calls (1 RELEASE_GATE
+ 1 BLOCKER_GATE — 2 of 3 Opus calls used on this slice):

1. `cmd`/`move`'s grab-restoring click destroyed whatever block the
   crosshair held (creative mode = instant break) — this was destroying
   the plaque itself right after registration. Fixed: `safe_regrab()`.
2. "Look up" alone isn't safe underground (room is at Y≈-60) — fixed:
   teleport the player to a fixed clear height (Y=300) before clicking.
3. 1s wasn't always enough for the client to catch up to a 360-block
   teleport — bumped to 3s.
4. `~`-relative scan targeting could drift across regrab cycles and floor
   to the wrong block — fixed: `capture_pos` directive freezes absolute
   coordinates once, before any drift can happen.
5. `hearthstead info` via `scmd` (console) resolves "nearest settlement"
   from the CONSOLE's position (near spawn), not the player's — always
   reported the wrong, empty settlement after PLAQUE-1's relocation.
   Fixed: ask it `cmd` (as the player), like `scan` already had to be.
6. `info()`'s `sendSuccess` used `broadcastToAdmins=false` unlike its
   siblings `scan()`/`recruit()` (`true`) — a player-issued `info()` call
   produced ZERO server-log trace. Fixed: changed to `true` (product fix,
   `HearthsteadCommand.java`).
7. **`playtest.sh` never rebuilt the jar and never checked it was
   current** — after fixes 5 and 6 landed, the SAME failure kept
   recurring identically for 5 more runs because the dedicated server was
   running a jar 6 hours stale. `runGameTestServer`/`runClient` compile
   fresh so they reflected the fixes; `playtest`'s server did not. Found
   via the client's own log (`playtest-client.log`) directly showing the
   correct answer twice, every time — it just never reached the stale
   server. Fixed: `playtest.sh` now refuses to run on a jar older than
   any source file; `expect_server` now searches only log lines appended
   since the most recent action (was: the whole cumulative file, a
   symmetric false-PASS risk).

**Lesson to keep:** when a fix that should work keeps failing identically,
check what's actually running before checking why it's failing.

## Next concrete action

1. Check the `full` run's result. If PASS: run it again for the required
   second consecutive clean pass at the same fingerprint, then commit any
   final doc touch-ups and consider PLAQUE-1 done.
2. If FAIL: read the actual failing suite's evidence before assuming
   anything — do not repeat this session's early mistake of theorizing
   without reading `playtest-client.log`/equivalent first.
3. Once genuinely done (green_streak >= 2 at one fingerprint): SLICE
   VISUAL-1 next (fix KF-007's `gen_settler.py` non-determinism first,
   then modular settler visuals).

## Load-bearing findings from live debugging (do not re-derive)

- `ss -ltnp` unreliable here — use `lsof -ti tcp:<port> -sTCP:LISTEN`.
- GNU `timeout` needs `--foreground`, else killing the PGID misses java.
- Xvfb ignores SIGHUP — explicit `pkill -9 -f "Xvfb :NN"`.
- GLFW needs a real click before relative look works; quickPlay never gives one.
- Console `tp <t> ~ ~ ~` and any `~`-relative command resolves against the
  CONSOLE's own position/context, not a target entity's — use `execute at`,
  or better, ask as the player (`cmd`) when the check needs the PLAYER's
  own position/nearest-settlement resolution (this is also KF-009 cause 5).
- `execute at ... run <anything>` suppresses ALL feedback silently.
- `fill <box> X replace X` is a no-op the game never counts.
- A `PlaqueScreen` (or any screen) left open silently absorbs later clicks
  as UI interaction — always `key Escape` between manual test iterations.
- Never nest `nohup cmd &` inside a `run_in_background: true` Bash call —
  pass the real command directly to a single-layer backgrounded call.
- `@e[...,limit=N]` bounds a selector, does not require a minimum.
- Never run two suites at once — every one launches a client and a server.
- Client boot under software GL / this proxy can take minutes while
  genuinely progressing — never treat slowness as a hang.
- **A jar sitting in `build/libs/` can be arbitrarily stale — `playtest`
  (unlike `full`) never rebuilds. `playtest.sh` now hard-fails if any
  source file is newer than the selected jar; don't remove that check.**
- **`expect_server` searches only the log since the last action-producing
  directive (`LOG_ANCHOR`), not the whole file — a scenario edit that adds
  a new no-op directive between an action and its `expect_server` does not
  reset this; only `cmd`/`scmd`/`click`/`move`/`key`/`type` do.**
- **`sendSuccess(msg, broadcastToAdmins)` — `false` means a PLAYER-issued
  command produces NO server console/log trace at all, only client-side
  chat. Check this flag before adding a log-based `expect_server` check
  against any command's output.**

## Known problems (pre-existing, other slices' scope)

KF-007 (`gen_settler.py` non-determinism) — VISUAL-1's first task.

## Architecture note for later (not blocking, not urgent)

`safe_regrab()`'s Y=300 round trip (used by every `cmd`/`move`) generates
`moved too quickly!` warnings and measurable position drift in this
environment — it works (with `capture_pos` covering the drift for
position-sensitive targeting) but is a fragility source. A regrab
mechanism that never moves the player would retire this whole class;
not worth redesigning now that it's reliable, worth remembering if a
similar symptom reappears elsewhere.
