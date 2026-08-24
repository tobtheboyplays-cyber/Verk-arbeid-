# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Current goal

**SLICE PLAQUE-1 — DONE.** 9 work items (blank plaque, Build Plan item,
5-state machine, save-compat, lamp art, recipes) plus KF-009's 8 harness
bugs, all fixed and verified. Full history: `docs/project/KNOWN_FAILURES.md`
KF-009.

**SLICE VISUAL-1 — implementation DONE, gate green twice; RELEASE_GATE
next.** Sonnet-only (PLAQUE-1 spent all 3 of its Opus calls; VISUAL-1 got
its own fresh budget: 1 PLAN_GATE call used so far).
- KF-007 fixed (commit `83a0408`): `gen_settler.py` seeded with Python's
  salted `hash()`, so two runs emitted different pixels for the same key.
  Now `zlib.crc32`. Added `check_pipeline()` to `validate_assets.py` (runs
  every deterministic generator twice under different `PYTHONHASHSEED`,
  asserts byte-identity + match to committed tree) so this class of bug
  can't silently regress again.
- V2a data model (commit `d04f935`): `SettlerAppearance` record (seed ->
  skinTone/hairStyle/hairColor/faceVariant/clothingVariant, cardinalities
  4x4x4x3x4). `SettlerEntity` syncs+saves the seed; `SettlementManager
  .spawnSettler` rolls it once (INV-5); legacy saves fall back to a
  UUID-derived seed, never 0-for-all. 2 new GameTests (persistence +
  8-spawn variety).
- V2b generator (commit `b99cf87`): `gen_settler.py` rewritten into 5
  independent 128x64 layer sheets (base/hair/face/clothing/outfit; outfit
  is the only profession-tied axis) composited by plain alpha-over. 31
  new layer PNGs + 4 regenerated legacy fallbacks. `preview_settler.py`
  rewritten: 18-combo x 3-view contact sheet, visually inspected — real
  variety, no corruption.
- V2c client rendering (commit `b539823`): new `SettlerTextureCache`
  (client-only, INV-6) composites the 5 layers via `NativeImage
  .blendPixel` into one `DynamicTexture`, name built directly from the
  appearance/profession fields (not a hashCode — rules out two settlers
  colliding and overwriting each other's texture); bounded 256-entry
  cache releasing the GPU texture on eviction; cleared on resource-pack
  reload. `SettlerRenderer.getTextureLocation` falls back to the static
  legacy texture on any failure. One-per-key WARN on failure + a
  once-per-session INFO on first success (silence alone can't tell
  "never ran" from "ran and failed" from "working" apart). **Proven with
  direct log evidence**: a real `playtest` client log shows `first
  composed settler texture registered: hearthstead:settler/
  composed_3_1_3_2_1_none` with zero failure warnings anywhere
  (`qa/reports/artifacts/playtest/20260824T154714Z/logs/
  playtest-client.log`).

**RELEASE_GATE (Opus call 2) ran: REVISE.** Findings: HIGH-1 (settlers
created outside `SettlementManager.spawnSettler` -- spawn egg, `/summon`,
mob spawner -- stayed permanently stuck at appearance seed 0, a
player-reachable path via `/hearthstead demo`'s spawn eggs); MEDIUM-2
(`check_pipeline()`'s new determinism guard degraded generator-execution
failures and missing Pillow to a warning/info instead of failing, and
`gen_structures.py` was listed but never actually compared since it
emits `.nbt` not `.png`); MEDIUM-3 (nothing bound Java's
`SettlerAppearance`/`SettlerTextureCache` cardinalities and key arrays to
the layer files `gen_settler.py` actually produces); MEDIUM-4 (hair style
2 "buzzed" painted a sideburn dot disconnected from its own hair, on
~25% of settlers). Full findings in the RELEASE_GATE transcript.

**All 4 fixed in one round (commit `9d4f830`), Level A verified, then
`tools/hearthstead-qa full` PASS twice consecutively again:**
`green_streak: 2`, fingerprint
`635407d14b4b329fb9be48ba42c7d3b50fa9be872d29affa8a1d1cbf663f3b7b`,
commit `9d4f83034c15d0c76fb5e93a3dde611c15fd50d3`, clean tree, all 11
suites PASS both times (confirmed by reading `qa/reports/latest.json`
directly). Fixes:
- HIGH-1: appearance seed now rolled in the `SettlerEntity` constructor
  itself (the one point every creation path passes through), not just
  by `SettlementManager`. New GameTest
  `settlerSpawnedOutsideSettlementManagerGetsRealAppearance`.
- MEDIUM-2: generator-execution failure is now a real `check()` failure;
  comparison covers `.nbt` alongside `.png` so `gen_structures.py` gets
  real coverage. Verified by deliberately breaking a generator (now
  fails with the real traceback) and confirming `gen_structures.py` is
  no longer silently skipped.
- MEDIUM-3: new `check_appearance_binding()` in `validate_assets.py`
  parses the Java constants/key arrays/profession keys, computes the
  exact cross product `SettlerTextureCache` will request, and asserts it
  matches `layers/` exactly. Verified by deleting a layer file and
  confirming it's caught.
- MEDIUM-4: sideburn dot now gated on `side_rows >= 3` so it's only ever
  painted contiguous with real side hair. 4 affected `hair_2_*` PNGs
  regenerated.

## Next concrete action

**Run the one short Opus re-review of just the changed areas** (per
premium-build-loop's post-REVISE rule -- Opus call 3 of the 3-call
absolute max for this slice). Package: the 4 findings + fixes above,
`git diff --stat` for commit `9d4f830`, the fresh green-twice evidence.
If PASS: mark VISUAL-1 complete, move to SLICE ANIM-1 (A1 animation set,
23 clips), Sonnet-only (all 3 Opus calls will be spent). If findings
remain: Sonnet fixes ordinary defects alone from here — no more Opus
calls permitted for this slice.

## Load-bearing findings from live debugging (do not re-derive)

- `ss -ltnp` unreliable here — use `lsof -ti tcp:<port> -sTCP:LISTEN`.
- GNU `timeout` needs `--foreground`, else killing the PGID misses java.
- Xvfb ignores SIGHUP — explicit `pkill -9 -f "Xvfb :NN"`.
- GLFW needs a real click before relative look works; quickPlay never gives one.
- Console `tp <t> ~ ~ ~` and any `~`-relative command resolves against the
  CONSOLE's own position/context, not a target entity's — use `execute at`,
  or better, ask as the player (`cmd`) when the check needs the PLAYER's
  own position/nearest-settlement resolution.
- A player's position can drift measurably even from PASSIVE waiting (not
  just regrab-teleport churn) — any `~`-relative command issued after a
  long wait or several regrab cycles needs a `capture_pos` freeze; it is
  never safe to assume unless deliberately checked (KF-009, full detail in
  KNOWN_FAILURES.md).
- `execute at ... run <anything>` suppresses ALL feedback silently.
- `fill <box> X replace X` is a no-op the game never counts.
- A `PlaqueScreen` (or any screen) left open silently absorbs later clicks
  as UI interaction — always `key Escape` between manual test iterations.
- Never nest `nohup cmd &` inside a `run_in_background: true` Bash call —
  pass the real command directly to a single-layer backgrounded call. Made
  this exact mistake again in VISUAL-1 (double-backgrounded a playtest
  run); recovered by finding the real PID via `ps` and blocking on it.
- `@e[...,limit=N]` bounds a selector, does not require a minimum.
- Never run two suites at once — every one launches a client and a server.
- Client boot under software GL / this proxy can take minutes while
  genuinely progressing — never treat slowness as a hang.
- A jar sitting in `build/libs/` can be arbitrarily stale — `playtest.sh`
  hard-fails if any source file is newer than the selected jar (proved
  itself useful again in VISUAL-1: caught a compile-only, not-yet-built
  jar before it could produce a false result).
- `expect_server` searches only the log since the last action-producing
  directive (`LOG_ANCHOR`), not the whole file.
- `sendSuccess(msg, broadcastToAdmins)` — `false` means a PLAYER-issued
  command produces NO server console/log trace at all, only client-side
  chat. Check this flag before adding a log-based `expect_server` check.
- Before writing down "environmental flakiness" for ANY repeated failure:
  read the client log AND look at the actual failing screenshot(s) first.
- **Silence in a log is not proof a code path ran and succeeded** — it is
  equally consistent with "never called" and "failed but nothing logs
  failure." A try/catch that swallows exceptions to provide a fallback
  needs its own explicit success/failure logging, or in-game verification
  of that specific path stays impossible from evidence alone (this is why
  V2c got the WARN/INFO logging above, not just a `null`-on-catch).

## Known problems (pre-existing, other slices' scope)

None currently open outside the standing slice backlog (ANIM-1 etc).

## Architecture note for later (not blocking, not urgent)

`safe_regrab()`'s Y=300 round trip (used by every `cmd`/`move`) generates
`moved too quickly!` warnings and measurable position drift in this
environment. `capture_pos` covers this only for the specific positions it
has been deliberately applied to — it does NOT generically cover every
`~`-relative command in a scenario. A regrab mechanism that never moves
the player would retire this whole class; not worth redesigning now,
worth remembering if a similar symptom reappears elsewhere.
