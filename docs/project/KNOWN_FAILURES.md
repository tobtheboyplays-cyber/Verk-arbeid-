# Known failures

Every entry is a real, currently-failing thing with evidence. Nothing here is
a guess. Pre-existing failures must never be attributed to a new slice.

No `full` run is current, deliberately: `full` is red on exactly KF-001,
KF-004 and KF-005 below, which belong to PLAQUE-1. HARNESS-1's evidence is
per-suite, at fingerprint `cebeb07b98…`; see `.claude/WORK_STATE.md` for the
matrix and `docs/project/REVIEW_FINDINGS.md` for both review rounds.

---

## KF-001 — 5 of 15 GameTests fail: the test helper punches a hole in the room

**Status:** diagnosed, not fixed. **Severity:** high (blocks the gate).

**Failing:** `roomdetectedashome`, `glassroofcountsasroofed`,
`homeinvalidatedwhenwallbroken`, `unlitroomregistersoncelit`,
`settlersleepsinclaimedbed`.

**Evidence:** `roomdetectedashome` reports
`enc=true sky=false beds=1 doors=1 lights=1 vol=1856`. A 3×3×3 hut interior is
27 cells. 1856 means the flood fill escaped the building.

**Root cause (PROVEN by reading the helper):** `hangPlaque()` in
`HearthsteadGameTests` places the plaque *by replacing a wall block*.
`PlaqueBlock` is `noCollission`/`noOcclusion`, so the wall now has a hole and
the room is no longer enclosed. The detection engine is behaving correctly by
refusing it.

**Expected fix:** hang the plaque in the **air cell against** the wall, as a
player does. For the hut's south wall at `z = hutOrigin.z`, that is
`hutOrigin.offset(1, 2, -1)` with `FACING = NORTH`, so `canSurvive()` finds
the wall behind it. The block entity's seed search then reaches the room via
its second candidate — the mounted-outside case it was written for.

---

## KF-002 — Dedicated-server E2E failed: **port already in use, not a mod bug**

**Status:** ROOT-CAUSED and corrected. **Severity:** was recorded as high;
the mod defect does not exist.

**Correction of an earlier entry.** This was first written up as a suspected
regression from the `BuildingManager` rewrite. That was wrong, and the wrong
diagnosis is left visible here rather than quietly deleted, because it is
exactly the kind of plausible-but-false lead that costs a correction cycle.

**Evidence (PROVEN).** `dedicated-first.log` never reaches `Done (`. It ends
in a startup crash:

    **** FAILED TO BIND TO PORT!
    io.netty.channel.unix.Errors$NativeIoException: bind(..) failed:
    Address already in use
    java.lang.IllegalStateException: Failed to initialize server

The server never started, so of course no settlers spawned and no settlement
info was printed. "settlers did not spawn" was the E2E's assertion firing on a
server that was already dead — a **misleading failure message**, since the
first thing the script checks is a symptom rather than whether the server came
up at all.

**Actual root cause.** A dedicated server leaked from an aborted
`qa/scripts/live.sh start` was still running and holding port 25565 (found
alive as PID 1273 well over an hour later). The harness does not guarantee
teardown, and a leaked server silently breaks every later suite that needs the
port.

**This belonged to HARNESS-1** (scenario HARNESS-7, clean shutdown) — DONE.
`dedicated_e2e.sh` now: preflights the port and names the holder if it's
held (driven negative test N1, `tools/hearthstead-qa negative n1`); asserts
"the server reached Done(" before anything about settlers; runs its own
isolated instance on port 25571; and every exit path (success, `die`, an
aborted invocation) tears down through a trap, verified clean by `reap
check` after each of two consecutive cold-start PASSes. Re-measured: PASS,
twice, with population 3 and zero classloading errors both times.

---

## KF-003 — Performance probe could not stand up 25+ settlers

**Status:** RESOLVED — same cause as KF-002, confirmed. **Severity:** was
medium; not a mod defect.

**Re-measured (HARNESS-1):** with the harness fixed (isolated port 25572,
own instance, preflight, ordered fact ladder), `tools/hearthstead-qa
performance` PASSes repeatably — two consecutive cold-start runs, ~27
settlers, avg MSPT ≈1.1ms (budget 45.0). See
`qa/reports/artifacts/performance/*/result.json`. Confirms KF-002's
diagnosis: this was harness port contention the whole time, never a
performance regression. `performance.log`'s "could not stand up 25+
settlers" was the same downstream-symptom-before-first-cause pattern as
KF-002 — fixed by the same ordered-fact-ladder discipline (AC-13).

---

## KF-004 — Plaque lang keys missing

**Status:** known, trivial, not yet done. **Severity:** medium (fails assets).

**Evidence:** validator — `block.hearthstead.plaque` missing in `en_us.json`
and `nb_no.json`, and the item key with it. Full key parity between the two
files is enforced.

**Exact scope, derived from source (2026-08-24):** 39 keys, listed in
`hearthstead-neoforge/docs/plaque_missing_keys.txt`, with 41 bilingual strings
already drafted and argument-checked in `docs/plaque_lang_draft.json`. Three
prefix families are empty — `building.*`, `plaque.state.*`, `requirement.*` —
and `requirement.*` keys take two format arguments (have, needed), not none.

**Note:** the UI strings the plaque screen and network reference
(`hearthstead.plaque.*`, `hearthstead.requirement.*`,
`hearthstead.building.*`, `hearthstead.mood.*`) must be enumerated from the
Java and added too — the validator only catches the registry-derived ones.

---

## KF-005 — Blockstate references three models that do not exist

**Status:** known, not fixed. **Severity:** high (would fail model resolution
in a real client).

**Evidence:** `blockstates/plaque.json` maps every `glow` value to
`block/plaque_red|amber|green`. Only `block/plaque.json` exists.

**Expected fix:** three thin variants of the 3D model differing in the status
lamp, or a single model plus a per-state emissive element. The red/amber/green
signal is a product requirement, not decoration.

---

## KF-006 — Playtest harness reaches the world only sometimes

**Status:** RESOLVED (HARNESS-1). Two consecutive cold-start `playtest`
PASSes with identical check sets; server-side `joined the game` proven every
time.

**Root causes found and fixed, in the end:**
- The original "player never joined the world" incident (PLAN_GATE) turned
  out to be a real client **compile error** — the harness was reporting a
  downstream symptom of a build failure it never checked for. Fixed by AC-13's
  ordered fact ladder plus a driven build-failure check (N2).
- Client boot under software GL behind this environment's proxy can
  genuinely take several minutes (an authlib session-server HTTPS call
  stalls) even while progressing normally — old fixed timeouts read this as
  a hang. Budgets widened; readiness for the title screen is judged by
  actually screenshotting and checking for real rendered content
  (`check_screenshot.py`), not a specific log line (sound is off, and the
  Realms-notification line depends on that same stalling network call).
- `overrideWidth`/`overrideHeight` in `options.txt` do **not** set the
  initial window size on this launch path — it opened at vanilla's 854×480
  default regardless. Fixed with explicit `--width 1280 --height 720`
  program arguments on the client run (`build.gradle`).
- **GLFW does not grab the mouse for relative look until the first real
  click into the window** — quickPlay never provides one. `move`/`look`
  directives now click immediately before every relative-motion send.
- A bare console `tp <target> ~ ~ ~ ...` resolves `~` against the
  **console's own position**, not the target's — needs `execute at
  <target> run tp <target> ~ ~ ~ ...`.
- `execute at ... run fill ...` (and likely other `execute ... run` wraps)
  silently drops ALL feedback — the effect happens, nothing is logged. Use
  bare, absolute-coordinate commands for anything whose feedback must be
  observed.
- `fill <box> X replace X` (self-replace, meant as a non-destructive
  existence probe) is a no-op the game never counts, even when X is
  genuinely present — this makes fill-based existence probing impossible;
  removed in favour of querying mod-authoritative state (`hearthstead info`).

**Fixed and verified earlier, still holds:** the client's game directory is
`run/`, not `run/client`; `nogui` plus window-targeted capture (never root);
quickPlay drops straight into the world so `Escape` opens the pause menu,
not dismisses one.

**Correction (HARNESS-1 review round):** the "window-targeted capture
(never root)" claim above was true for `playtest.sh`/`live.sh` but NOT for
`client_boot.sh`, which used `import -window root` — invisible because
Xvfb's own virtual screen (`-screen 0 1280x720x24`) happens to be exactly
the required 1280x720, so `check_screenshot.py`'s size assertion passed by
coincidence regardless of the real window's size. The only green `client`
evidence at the time was actually an 854x480 window letterboxed inside a
1280x720 root capture (`screenshot-title.png`'s non-black bounding box was
(213,120,1067,600), not the full frame). Fixed: `client_boot.sh` now finds
the recorded window id, asserts its own reported geometry is 1280x720
*before* ever capturing it, and only ever captures that window. Re-measured:
PASS, with a full-frame (0,0,1280,720) bounding box.

**No longer unresolved:** `live.sh`'s persistent tmux session (D-H1)
survives separate shell invocations — proven directly: `start`, `status`
(twice, same server PID and player both times), `cmd`, `shot`, `film`,
`stop` each as its own Bash call against one session (HARNESS-6). One
teardown gap was found and fixed along the way: **Xvfb ignores SIGHUP**, so
`tmux kill-session` alone left it running; `live.sh` now sends it an
explicit SIGKILL on both `start`'s pre-cleanup and `stop`.

---

## KF-007 — `gen_settler.py` is not reproducible

**Status:** confirmed independently by two agents. **Severity:** medium.

**Evidence:** it seeds with `random.Random(hash(prof_key) & 0xFFFF | 1420)`.
Python salts `hash()` on strings per process, so consecutive runs emit
different skins and the committed PNGs match neither.

**Expected fix:** seed with an explicit integer constant, regenerate, and add
a validator check so the pipeline's "run twice, identical bytes" rule is
actually enforced rather than assumed.


---

## KF-008 — `full` cannot currently be run without re-reporting PLAQUE-1's failures as this slice's

**Status:** by design, not a defect. **Severity:** informational.

Recorded so nobody "discovers" it again. `full` includes `gametest` and
`assets`, which are red on KF-001, KF-004 and KF-005. Those are owned by
PLAQUE-1 and documented above. Running `full` before that slice lands produces
a red gate that says nothing new, which is why HARNESS-1's completion evidence
is per-suite and why `qa/reports/BLOCKED` records NOT READY rather than any
suite being skipped or loosened to obtain green (INV-10).

The first `full` that can honestly go green is PLAQUE-1's.
