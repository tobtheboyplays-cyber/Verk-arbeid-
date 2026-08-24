# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Current goal

**SLICE PLAQUE-1 — DONE.** `tools/hearthstead-qa full` PASS twice
consecutively at fingerprint `ba754b936aba...`, commit `c47acfe`, clean
tree (`dirty_hash` = empty-tree hash), all 11 suites green both times
(doctor, assets, animation, build, gametest, behavior, dedicated,
performance, client, playtest, visual). See
`qa/reports/artifacts/20260824T143441Z/manifest.json` for the closing
manifest. All 3 of 3 Opus calls for this slice were used (1 RELEASE_GATE,
1 BLOCKER_GATE, 1 RELEASE_GATE re-review) and are exhausted — SLICE
VISUAL-1 starts Sonnet-only, per the recorded order (HARNESS-1 ->
PLAQUE-1 -> VISUAL-1 -> ANIM-1).

## What's done and evidenced

- **W1-W9 all implemented.** Blank plaque (EMPTY state, no UI, dark lamp),
  separate `BuildPlanItem` (39 keys, 41 bilingual strings), 5-state machine
  (`EMPTY -> PLAN_INSERTED_UNLINKED -> LINKED_INCOMPLETE/LINKED_VALID`,
  `ORPHANED`), save-compat via a legacy-id alias table, 4-value lamp art
  (off/red/amber/green), 7 recipes, KF-001's `hangPlaque()` fix. None of
  this is affected by KF-009 — confirmed intact by the RELEASE_GATE
  re-review, independent of anything below.
- **`assets` PASS 230/230** — closes KF-004, KF-005.
- **`gametest` PASS 19/19** (re-verified after the `info()` source fix) —
  closes KF-001. Includes `legacyPlaqueStateLoadsWithoutLosingBuilding`.
- **`playtest` — see KF-009. Now genuinely green, twice consecutively,
  at the current fingerprint** (`ba754b936aba...`, commit `c47acfe`) —
  the 8th and final cause fixed, then verified, not just fixed.

## KF-009 — 8 real bugs found, 8th just fixed — read the full entry (docs/project/KNOWN_FAILURES.md) before touching this area again

1. `cmd`/`move`'s grab-restoring click destroyed whatever block the
   crosshair held (creative mode = instant break). Fixed: `safe_regrab()`.
2. "Look up" alone isn't safe underground (room is at Y≈-60) — fixed:
   teleport the player to a fixed clear height (Y=300) before clicking.
3. 1s wasn't always enough for the client to catch up to a 360-block
   teleport — bumped to 3s.
4. `~`-relative SCAN targeting could drift across regrab cycles and floor
   to the wrong block — fixed: `capture_pos` directive freezes absolute
   coordinates once, before any drift can happen.
5. `hearthstead info` via `scmd` (console) resolves "nearest settlement"
   from the CONSOLE's position (near spawn), not the player's. Fixed:
   ask it `cmd` (as the player), like `scan` already had to be.
6. `info()`'s `sendSuccess` used `broadcastToAdmins=false` unlike its
   siblings `scan()`/`recruit()` (`true`) — a player-issued `info()` call
   produced ZERO server-log trace. Fixed: changed to `true`.
7. `playtest.sh` never rebuilt the jar and never checked it was current —
   after fixes 5/6 landed, the SAME failure recurred identically because
   the server was running a jar 6 hours stale. Found via the CLIENT's own
   log showing the correct answer every time, never reaching the stale
   server. Fixed: hard-fail on a stale jar; `expect_server` now anchors
   to post-action log lines only (`LOG_ANCHOR`), not the whole file.
8. **The player's STAND-BACK reposition (`tp $PLAYER ~1 ~ ~-1 0 0`) was
   still live `~` math**, unlike the scan targets which cause 4 already
   fixed — so it could drift during the 15s founding wait, same as cause
   4 proved for regrab churn. This produced 2 of 4 `full`-run failures
   that I WRONGLY diagnosed as "environmental flakiness" without checking
   evidence — a RELEASE_GATE re-review found a pre-click screenshot
   showing the crosshair a full block off the plaque, disproving that.
   Fixed: a second `capture_pos STANDBACK 1 0 -1`, captured at the same
   safe moment as PLAQUE, replaces the live `~1 ~ ~-1`.

**Lesson, learned twice this slice — worth remembering hard:** when a fix
that should work keeps failing, look at the actual evidence (client log,
screenshot, exact mechanism) BEFORE reaching for "flakiness." That word
describes not having looked yet, not a diagnosis.

## Next concrete action

**SLICE VISUAL-1: fix KF-007's `gen_settler.py` non-determinism first**
(it seeds with `random.Random(hash(prof_key) & 0xFFFF | 1420)`; Python
salts `hash()` on strings per process, so two runs emit different skins
and the committed PNGs match neither — seed with an explicit integer
constant, regenerate, add a validator check enforcing "run twice,
identical bytes"), **then modular settler visuals** (skin×hair×face×
clothing + profession outfits, per DESIGN.md). Sonnet-only for
implementation; PLAN_GATE (Opus) before editing begins, per the
mandatory multi-model gate in the repo's CLAUDE.md.

## Load-bearing findings from live debugging (do not re-derive)

- `ss -ltnp` unreliable here — use `lsof -ti tcp:<port> -sTCP:LISTEN`.
- GNU `timeout` needs `--foreground`, else killing the PGID misses java.
- Xvfb ignores SIGHUP — explicit `pkill -9 -f "Xvfb :NN"`.
- GLFW needs a real click before relative look works; quickPlay never gives one.
- Console `tp <t> ~ ~ ~` and any `~`-relative command resolves against the
  CONSOLE's own position/context, not a target entity's — use `execute at`,
  or better, ask as the player (`cmd`) when the check needs the PLAYER's
  own position/nearest-settlement resolution (KF-009 cause 5).
- **A player's position can drift measurably even from PASSIVE waiting
  (not just regrab-teleport churn) — any LATER `~`-relative command that
  assumes "still where it was placed" needs a `capture_pos` freeze, not
  just the ones immediately following active teleport cycles (KF-009
  causes 4 and 8 — cause 8 was missed the first time because this wasn't
  generalized from cause 4's fix).**
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
- A jar sitting in `build/libs/` can be arbitrarily stale — `playtest.sh`
  now hard-fails if any source file is newer than the selected jar.
- `expect_server` searches only the log since the last action-producing
  directive (`LOG_ANCHOR`), not the whole file.
- `sendSuccess(msg, broadcastToAdmins)` — `false` means a PLAYER-issued
  command produces NO server console/log trace at all, only client-side
  chat. Check this flag before adding a log-based `expect_server` check.
- **Before writing down "environmental flakiness" for ANY repeated
  failure: read the client log AND look at the actual failing
  screenshot(s) first. Both times this slice skipped that step, the real
  cause was directly visible in evidence already captured.**

## Known problems (pre-existing, other slices' scope)

KF-007 (`gen_settler.py` non-determinism) — VISUAL-1's first task.

## Architecture note for later (not blocking, not urgent)

`safe_regrab()`'s Y=300 round trip (used by every `cmd`/`move`) generates
`moved too quickly!` warnings and measurable position drift in this
environment. `capture_pos` covers this for the specific positions it has
been applied to (plaque scan target, player stand-back) — it does NOT
generically cover every `~`-relative command in a scenario; any NEW
`~`-relative command added after a long wait or several regrab cycles
needs its own `capture_pos`, checked deliberately, not assumed safe. A
regrab mechanism that never moves the player would retire this whole
class; not worth redesigning now, worth remembering if a similar symptom
reappears elsewhere.
