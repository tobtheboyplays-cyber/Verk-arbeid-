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

**Status: RESOLVED** (SLICE VISUAL-1, task 1). **Severity:** was medium.

**Original defect:** `gen_settler.py:464` seeded with
`random.Random(hash(prof_key) & 0xFFFF | 1420)`. Python salts `hash()` on
strings per process (`PYTHONHASHSEED`), so consecutive runs emitted different
skins and the committed PNGs matched neither.

**Fix:** seed is now `zlib.crc32(prof_key.encode("utf-8")) & 0xFFFF |
SEED_BASE` (`SEED_BASE = 1420`, an explicit module constant) — stable across
processes, unlike Python's salted `hash()`. `generate()` was also split into
a pure `build(prof_key)` (paints, returns the image, no I/O) and `generate()`
(`build` + `save`); `preview_settler.py` now calls `build` so previewing no
longer mutates committed assets as a side effect.

**Verified, not assumed:** ran `gen_settler.py` twice in separate,
fully-isolated temp trees with `PYTHONHASHSEED=0` and `PYTHONHASHSEED=1` —
the four output PNGs were byte-identical across both runs. All four
committed `settler_*.png` were regenerated and committed. A new
`check_pipeline()` in `tools/validate_assets.py` (category `Pipeline`, part
of the `assets` suite) now enforces this permanently: for each of
`gen_settler.py`, `gen_blocks_items.py`, `gen_gui.py`, `gen_plaque.py`,
`gen_structures.py`, it runs the generator twice as separate subprocesses
under `PYTHONHASHSEED=0`/`=1` in isolated temp copies of `tools/`, and checks
(a) both runs produce the same file set, (b) the bytes are identical between
runs, (c) the bytes match the committed tree — so a stale or newly
non-deterministic generator fails `tools/hearthstead-qa assets` outright
instead of being discovered by accident. Confirmed: `python3
tools/validate_assets.py` now reports `PASS: 242/242`, with all 13 new
Pipeline checks green (`gen_structures.py` has no PNGs to compare and is
correctly skipped, not silently passed).


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

---

## KF-009 — Playtest's plaque section repeatedly failed; eight real, distinct harness bugs, no mod defect

**Status: RESOLVED.** Eight genuine root causes found and corrected
across three review cycles; the 8th (found by a RELEASE_GATE re-review
after this entry had already, wrongly, a second time — see "What this
cost, honestly" below — attributed two failing `full` runs to
unspecified "environmental input-delivery flakiness") was fixed and then
independently re-verified: `tools/hearthstead-qa full` PASSED twice
consecutively at fingerprint `ba754b936aba...`, commit `c47acfe`, clean
tree, all 11 suites green both times. Manifest:
`qa/reports/artifacts/20260824T143441Z/manifest.json`.
**Severity:** was blocking `playtest`'s PLAQUE-1 section entirely; **never**
indicated any defect in the mod itself — confirmed at every stage by
GameTest (deterministic, no client input) and, for the trickiest causes,
by direct evidence (the Minecraft client's own log for cause 7; a
pre-click screenshot and worked-backward teleport arithmetic for cause 8)
that named the real mechanism instead of gesturing at "flakiness."

**This entry previously said the cause was "genuinely not isolated" and
matched an "unresolved input-delivery flakiness class."** That conclusion
was wrong, and is left below (struck through in spirit, not deleted) rather
than quietly rewritten, because it is exactly the kind of plausible-but-
false lead worth a full correction cycle. An Opus RELEASE_GATE and, later,
an Opus BLOCKER_GATE each independently found real, provable causes that
had simply never been checked for.

### The eight causes, in the order they were found and fixed

1. **Destructive grab-restoring click (RELEASE_GATE, root cause of the
   original symptom).** `cmd`/`move` directive handlers ended with a click
   to re-establish GLFW's relative-mouse grab, aimed at whatever the
   crosshair currently held. In creative mode that is an instant block
   break — it was destroying the plaque itself immediately after a
   successful survey, deterministically. Every earlier "the click didn't
   register" observation was actually "the plaque got destroyed by a LATER,
   unrelated click and a screenshot several steps on shows it gone."
   Fixed by `safe_regrab()`: capture rotation, look away, click, restore.
2. **"Look up" is not safe underground.** PLAQUE-1's test room sits at
   Y≈-60; looking straight up from inside or near it hits the room's own
   roof or natural terrain, still well within creative reach. Fixed by
   teleporting the PLAYER to a fixed clear height (Y=300) before clicking,
   not just changing look direction.
3. **1s was not enough for the client to catch up to a 360-block
   teleport.** The same class of gotcha KF-006 already named for rotation
   changes, worse for a much bigger scene change (new chunks, new
   lighting). Bumped the post-teleport pause to 3s.
4. **`~`-relative scan targeting could drift across regrab cycles.** The
   player's true position was observed to drift by a few tenths of a block
   between consecutive `cmd` sends in this environment (client movement
   packets trickling in after a round trip's own restore already ran) —
   enough, occasionally, to floor a `~1 ~2`-style offset onto the wrong
   integer block. Fixed with a new `capture_pos` scenario directive that
   freezes the plaque's absolute coordinates once, right after it's
   placed, before any regrab churn can touch them.
5. **`hearthstead info` issued via console (`scmd`) resolves the wrong
   settlement.** `HearthsteadCommand.info()`'s "nearest settlement" search
   measures distance from the COMMAND SOURCE's own position — for a
   console-issued command that is a fixed point near world spawn, not
   wherever the player has since walked to. PLAQUE-1 deliberately relocates
   300+ blocks away, so `scmd hearthstead info` there was always reporting
   on the OLD, empty settlement near spawn, never the new one the plaque
   had just registered into. Fixed by asking it `cmd` (as the player)
   instead, matching how the scan calls already had to be asked.
6. **`info()`'s `sendSuccess` calls used `broadcastToAdmins=false`,
   unlike its siblings.** `scan()` and `recruit()` both pass `true` and
   reliably log `[Dev: ...]`-bracketed lines even for player-issued
   commands; `info()`'s `false` meant a PLAYER-issued call produced zero
   trace in the server console log — not a wrong answer, no answer at all.
   Fixed by changing all three `sendSuccess` calls in `info()` to `true`,
   for consistency with `scan()`/`recruit()` (a legitimate small product
   fix, not a test workaround: `info()` is the same kind of admin/
   diagnostic read they are, with no reason to behave differently).
7. **`playtest.sh` never rebuilt the jar, and never checked it was
   current (BLOCKER_GATE, closes the mystery that survived causes 5 and 6
   being fixed).** After causes 5 and 6 were fixed and pushed, the exact
   same failure kept recurring, identically, across five more runs — with
   `xdotool`'s own exit codes clean and `focus()` reporting no failure
   either. The actual explanation: `playtest.sh` picks the newest
   PRE-EXISTING jar in `build/libs/` and only fails if none exists at all
   — it never rebuilds, and never compares the jar's age against the
   source. The dedicated server under test was running a jar that predated
   causes 5 and 6 by six hours, so every one of those five runs was
   correctly reporting that the (stale) server code still didn't log
   anything — while `runGameTestServer`/`runClient`, which DO compile
   fresh, kept reflecting the real fixes, producing the confusing
   appearance of "compiles clean, GameTest still 19/19, playtest still
   fails identically." The client's OWN log (`playtest-client.log`, never
   previously read for this check) directly proved the command worked
   perfectly, twice, every time: correct settlement, correct message,
   just never reaching the stale server's outdated code. Fixed two ways:
   `playtest.sh` now refuses to run at all if any source file is newer
   than the selected jar, naming the stale file; and `expect_server` now
   searches only the log appended since the most recent action-producing
   directive (a `LOG_ANCHOR`), not the whole cumulative file, closing the
   symmetric false-PASS risk the same design gap allowed.
8. **The player's stand-back reposition was still computed from live `~`
   math (RELEASE_GATE re-review, closes the mystery left after cause 7's
   fix).** Two of the four `full` runs after cause 7 landed still failed —
   this time at the PLAQUE-1 section's plan-insertion click, with the
   plaque UI never opening. I attributed this to "genuine environmental
   input-delivery flakiness (the KF-006 class)" without checking the
   failing runs' own screenshots first — the exact mistake this entry
   already corrected once, made again. A RELEASE_GATE re-review checked
   `qa/reports/artifacts/playtest/20260824T130654Z/shots/plaque-01-before-
   click.png` (captured BEFORE any click) and found the crosshair sitting
   on the door, a full block off the plaque — the click never had a
   chance; the aim itself was wrong. Cause: `tp $PLAYER ~1 ~ ~-1 0 0`
   (repositioning the player to stand back from the plaque) was still a
   LIVE relative offset from whatever the player's CURRENT position
   happened to be — computed after the 15s hearth-founding wait, during
   which position can drift the same way cause 4 already proved it can
   during regrab churn, just from passive waiting this time. `capture_pos`
   already existed for exactly this problem but had only been applied to
   the scan targets, not this reposition. Fixed by capturing a second
   frozen position (`STANDBACK`, offset (1, 0, -1) from the same safe
   moment `PLAQUE` is captured) and using it instead of the live `~`.

### What this cost, honestly

Roughly a dozen verification runs across three investigation arcs, three
Opus gate calls (the maximum allowed for one slice), and — worth stating
plainly since it happened twice — two separate collapses into "genuine
environmental flakiness" as an explanation nobody had actually checked
evidence for. The first (per this entry's own now-corrected prior text)
was "the same unresolved class of flakiness KF-006 already recorded";
the second was the identical move made again, immediately after fixing
the first one, on a DIFFERENT failure. In both cases the real cause was
sitting in evidence (a stale jar; a screenshot with a mis-aimed crosshair)
that a look would have found directly, faster than the theorizing did.
The load-bearing lesson, worth keeping past this slice, restated because
it needed learning twice: **when a fix that should work keeps failing,
look at the actual evidence (the client log, the screenshot, the exact
mechanism) before reaching for "flakiness" — that word is a description
of not having looked yet, not a diagnosis.**

**PLAQUE-1's nine work items are implemented and independently
verified, and PLAQUE-1 is done.** GameTest (room detection, save-compat
via a synthetic legacy tag), the asset validator (230/230, closing
KF-004 and KF-005), and `playtest` itself — green end to end, twice
consecutively at one fingerprint — all confirm it. See
`.claude/WORK_STATE.md` for the exact evidence and the next slice.

---

## KF-010 — settlers created outside `SettlementManager` stayed permanently
locked to a degenerate, identical appearance

**Status: RESOLVED** (SLICE VISUAL-1). **Severity:** was high.

**Found by:** the VISUAL-1 RELEASE_GATE (Opus), not by the GameTest suite —
worth recording because it shows the suite passing does not by itself rule
out a real defect on an untested path.

**Original defect:** `SettlerEntity`'s synced appearance seed
(`DATA_APPEARANCE_SEED`) defaulted to `0` in `defineSynchedData`, and the
only place that ever rolled a real value was `SettlementManager
.spawnSettler`. Any settler created by a different path — a spawn egg
(player-reachable, registered in the creative tab, and handed out four at
a time by `/hearthstead demo`), `/summon`, or a mob spawner — kept seed
`0` forever. `SettlerAppearance.decode(0)` composites to exactly the
legacy `settler_none.png` pixels, so this read as "normal" in every
screenshot and log; nothing about it looked broken until someone asked
"what happens on a path other than the one I tested." Once saved, the
degenerate value is written unconditionally by `addAdditionalSaveData`,
so it became permanent.

**Fix:** the appearance seed is now rolled directly in the
`SettlerEntity` constructor (`entityData.set(DATA_APPEARANCE_SEED,
random.nextInt())`, right after `super(type, level)`), the one point
every creation path passes through, regardless of loader mechanism.
`SettlementManager`'s own explicit roll became redundant and was removed.

**Verified, not assumed:** new GameTest
`settlerSpawnedOutsideSettlementManagerGetsRealAppearance` spawns 6
settlers via a raw `helper.spawn` (bypassing `SettlementManager` entirely,
the same shape as a spawn egg or `/summon`) and asserts none has seed `0`
and they are not all identical. The RELEASE_GATE re-review additionally
re-decompiled `Entity`/`LivingEntity` from the real 1.21.1 sources to
confirm `random` and `entityData` are both initialized before the roll
runs, and that `entityData.set` on this key cannot trigger any
`onSyncedDataUpdated` side effect on a partially-constructed entity.

---

## KF-011 — bed-sleeping settlers no longer regained energy and became
permanently stuck asleep

**Status: RESOLVED** (SLICE ANIM-1). **Severity: BLOCKER** — the only
BLOCKER-severity finding this repository has recorded to date.

**Found by:** the ANIM-1 RELEASE_GATE (Opus), by reading `tickNeeds()`
directly and reasoning through the state machine, not by any GameTest
failing — worth recording because the existing suite passed the whole time
this defect was live; nothing exercised a settler through a full night at
low starting energy.

**Original defect:** `RestAtNightGoal` was changed this slice to set the new
`SettlerActivity.SLEEPING` while a settler sleeps in a claimed bed (it
previously reused `RESTING` for both rough hearth-side rest and real bed
sleep, which was itself a defect this slice fixed on purpose — `SLEEPING`
and `RESTING` are different clips). But `SettlerEntity.tickNeeds()`'s
energy-recovery branch still checked only `activity == RESTING`; every other
activity, `SLEEPING` included, hit the drain branch
(`getEnergy() - (working ? 0.09F : 0.02F)`). Combined with
`RestAtNightGoal.canContinueToUse()`'s `energy < 60` exit condition once
curfew ends, and `tick()` returning early while `isSleeping()`, energy could
not rise while asleep — so any settler who went to bed under ~60 energy
(i.e. anyone who had worked that day) could never satisfy the goal's own
exit condition. The goal never stopped, `stopSleeping()`/`triggerWakeStretch()`
(both only called from the goal's `stop()`) never ran, and the settler never
worked again.

**Fix:** `tickNeeds()` now recovers energy for `SLEEPING` as well as
`RESTING`, and recovers it *faster* (`+1.5F`/20-tick period vs. `+1.2F`) —
a real bed must beat rough hearth-side rest, preserving the housing
incentive, not merely match it.

**Verified, not assumed:** new GameTest
`settlerWakesAtDawnWithRecoveredEnergy` sets a settler's energy to 20 (a
realistic post-workday value), sends it through `RestAtNightGoal` from deep
night to past dawn, and asserts: the settler was actually observed in
`SLEEPING` with energy visibly rising while in that state (not just present
at any single tick), energy reaches the ≥60 wake threshold, and the settler
is no longer `isSleeping()`/no longer carries `SLEEPING` once awake. All 25
required GameTests, including this one and the pre-existing
`settlerSleepsInClaimedBed`, pass — see `qa/reports/artifacts/` for the run
that included this fix.

---

## KF-012 — playtest `scmd()` cannot be made poll-based on log growth

**Status: RECORDED (optimization reverted).** **Severity:** none shipped —
the attempt was caught by the suite it was meant to speed up.

**What was tried.** `scmd()` ends with a flat `sleep 2` after every server
console command. With ~50 commands per scenario (plus two `data get`
round trips inside every `safe_regrab`), that is well over a minute of
pure waiting per playtest run, most of it after the server had already
answered. The optimization: send the command, then poll the server log's
byte size and return as soon as it grows, keeping the same 2s ceiling —
apparently the same guarantee with none of the dead time. The two
`sleep 1`s in `safe_regrab` were reduced to 0.2s on the same reasoning
(scmd would now already have waited for the reply).

**Why it is wrong.** *Log growth is not this command's reply.* The
playtest server is a live world with settlers, goals, chunk activity and
the mod's own logging — `logs/latest.log` grows continuously regardless
of what was just typed. So the poll returns on whatever line happened to
land next, which means `safe_regrab` can grep a **stale** position or
rotation, and any step depending on the command having actually completed
proceeds too early.

**How it appeared to fail, and the correction.** A real
`tools/hearthstead-qa playtest` run failed at scenario step 283:
`cmd hearthstead scan ...` followed by `expect_server:Registered`. That
was attributed to this change and it was reverted.

**That attribution was WRONG, and is corrected here rather than quietly
deleted.** A later `full` run, with the change fully reverted and
`scmd()` byte-identical to its original form, failed at **exactly the
same step 283 in exactly the same way**. So the change cannot have been
the cause. Evidence from that run's own server log
(`qa/reports/artifacts/playtest/20260824T215724Z/logs/`): the Build Plan
was given at 22:04:57, the following safe_regrab/click cycles produced no
plaque state change at all, and no `Surveyed the House` line ever
appeared for the step-283 scan — i.e. the plan was never inserted and the
later `cmd` produced no server output, which is the KF-009 family of
click-raycast / open-screen timing problems, not a wait-length problem.
Two full runs immediately before this one (20:37 and 21:12) passed the
same scenario, so it is intermittent.

**The reasoning below still stands on its own merits** — polling a shared,
noisy log for "my command answered" IS a race, and the byte-growth version
should not be revived. But it was not what broke step 283, and recording
it as the cause would have left a false lead for the next reader. This is
the same discipline as KF-002's correction.

**The lesson worth keeping.** A "wait for the system to respond" that
polls a *shared, noisy* signal is not equivalent to a fixed sleep — it is
a race. To be sound it would have to poll for a response **specific to
the command just sent** (a unique marker echoed back, or a matching
result line), not for generic activity. That is a real design, not a
tweak, and it is not worth the risk in the harness everything else is
verified by: `full` is deliberately rare (slice end only) and
`tools/hearthstead-qa fast` (~50s, no client boot) is the continuous
check, so the 90 seconds this would have saved buys very little.

**Do not retry** the byte-growth version. If revisited, the only sound
shape is a per-command correlation marker.

---

## KF-013 — the courier loaded but never delivered: the wedge A2a claimed to have designed away

**Status:** RESOLVED (fix + two regression GameTests, both proven to fail
on the pre-fix code).

**Severity:** BLOCKER. This is MineColonies issue #2932 — "the
deliveryman picks things up and never delivers them" — reproduced in our
own mod, in the exact system whose design note claims to avoid it.

**How it was found.** Not by the suite. `tools/hearthstead-qa full` was
green twice consecutively and `gate` reported `green_streak=2` when this
shipped. It was found by playing the game: a live session with a real
hollow oak-plank warehouse (x16-22 / z6-12), a plaque at `19 -57 7` that
surveyed successfully, 24 oak logs fed into the hearth, and settler Hedda
assigned COURIER. Queried directly from the running server:

```
18,-58,9  []        20,-58,9  []
18,-58,10 []        20,-58,10 []
10,-59,10 {Items: [{count: 17, Slot: 0, id: "minecraft:oak_log"}]}
Hedda     [{count: 7, id: "minecraft:oak_log"}]
Hedda pos [19.50, -60.0, 6.38]
```

Items were conserved (17 + 7 = 24), so no invariant tripped. The delivery
simply never completed, silently, forever.

**Root cause — the delivery target was never a place goods can go.**
`PlaqueBlockEntity.link()` sets `building.anchor = beds.isEmpty() ?
worldPosition : beds.get(0)`. A warehouse has no beds, so its anchor is
**the plaque block itself** — mounted in a wall, with no standable cell
beside it and solid blocks above it. `CourierWorkGoal` routed to that
anchor and declared arrival at `blockPosition().distSqr(anchor) <= 9.0`.
Hedda's own numbers: `(19,-60,6)` to `(19,-57,7)` is `0 + 9 + 1 = 10`.
One more than the radius, standing outside a wall she was never pathing
through, because `moveTo(anchor.x, anchor.y + 1, anchor.z)` aims *inside*
the wall.

Then the second half of the wedge: on giving up, the goal set
`done = true` and `canUse()` re-entered on the very next tick, because a
non-empty bag unconditionally selects `TO_WAREHOUSE`. Walk out, fail,
walk out, fail — with the settlement's goods parked in a bag where
nothing can see or use them.

**Why the GameTests missed it.** `CourierGameTests` registered its
warehouse with `anchor = minRel` in a wide-open arena with the chest
adjacent and no walls at all. Every geometry that makes the bug possible
— a wall-mounted anchor, a chest deep inside, an enclosure to path
through — was absent. The suite was measuring a situation the game never
produces.

**The fix (three parts, all in `CourierWorkGoal` + `WarehouseStorage`).**

1. **Deliver to a container, not to a plaque.** `pickDropOff()` picks the
   nearest real chest/barrel from the warehouse index, and
   `approachTo()` resolves a genuinely standable cell beside it — a chest
   is never walkable, so aiming at the container block left the navigator
   guessing, and outside a sealed room its guess is the wrong side of the
   wall.
2. **Arrival means physically inside.** `hasArrivedAt()` requires the
   settler to be within the building's own bounds *and* within reach of
   the drop-off. Reaching through a wall is not delivering — and the
   pre-fix code did exactly that whenever the geometry allowed it (see
   the regression evidence below).
3. **Failure is never silent and never strands goods.** `giveUp()` rests
   the route for `RETRY_COOLDOWN_TICKS` (400) instead of re-triggering
   next tick, and a new `RETURNING` mode carries an undeliverable load
   back to the hearth — for an unreachable warehouse, a dissolved one, a
   full one, or one with no container at all.

**A second, latent defect found while fixing this.** `WarehouseStorage.of()`
tested freshness with `level.getGameTime() - lastRefreshTick >=
REFRESH_INTERVAL_TICKS` against a `Long.MIN_VALUE` sentinel. That
subtraction **overflows to a negative age**, so a never-refreshed index
read as fresh and reported an empty warehouse forever. It was invisible
before only because `insert()` scans the world directly; the moment
`of()` was used to *decide* anything, every warehouse looked empty. Now
tracked by an explicit `everRefreshed` flag.

**Regression tests, and the proof that they are real judges.** The two
new tests were run against the restored pre-fix `CourierWorkGoal` and
both failed:

```
courierwithnowheretodeliverbringsgoodsbacktothehearth failed!
  the undeliverable load should come back to the hearth, still carrying 5
courierentersasealedwarehouseanddelivers failed!
  the courier must walk into the warehouse, not post goods through the
  wall -- stowed from -2726715, -59, -3368743
```

That second line is the wall-posting caught in the act: `y=-59` is a
block *below* the room floor, outside the room, while the logs landed in
a chest in the far corner.

`courierEntersASealedWarehouseAndDelivers` builds what the live world
had and the old arena did not: a closed 7x7 oak room with a ceiling and a
real closed oak door, the chest diagonally opposite the door, and the
anchor set to a wall block beside the door exactly as
`PlaqueBlockEntity` assigns it. It asserts *where the courier stood at
the tick the chest count rose* — "was she ever inside" is not enough,
because she can post through the wall and wander in afterwards, which is
precisely how the first version of this test passed on broken code.

**The lesson worth keeping.** A green suite proved only that the courier
works in an arena with no architecture in it. The mod's entire premise is
that players build real enclosed rooms, so any test whose arena has no
walls is not testing the product. When a system's correctness depends on
geometry the player supplies, the test must supply that geometry too —
and `docs/project/REFERENCE_ANALYSIS.md` must not claim a reference mod's
failure is "avoided by design" until a test reproduces that failure's
actual shape.

---

## KF-014 — couriers intermittently stopped working — RESOLVED (root cause found on the fourth occurrence)

**Status:** **RESOLVED.** Root cause found on the fourth occurrence, by the
diagnostics added after the third. Ten consecutive clean runs afterwards,
against a rate that had reached roughly one in four.

**The cause: the test fixtures registered buildings the game then deleted.**
`BuildingManager.tick()` sweeps one building per interval and, when the
block at `building.plaquePos` is not a `PlaqueBlock`, calls `dissolve()` —
correctly, because **no plaque, no building** is the permanent invariant
(D-005). The GameTest fixtures registered warehouses at anchor positions
where no plaque block had been placed, so the sweep eventually deleted the
warehouse out from under the running test. The courier then behaved exactly
as designed: no warehouse, so return the load to the hearth and idle.

**Why it was intermittent, precisely.** The sweep is round-robin across
*every* building in *every* settlement, one per interval — and GameTests in
a batch run concurrently, so how quickly the cursor reaches any particular
fixture's warehouse depends on how many buildings the other tests happen to
have registered at that moment. That is the nondeterminism, and it is why
it presented as a rate rather than as a reproducible failure.

**The fix:** fixtures now hang a real plaque at the anchor
(`helper.setBlock(anchorRel, ModBlocks.PLAQUE.get())`), which is what the
game requires of a real warehouse anyway. The product was never wrong; the
tests were asserting against a building the product was entitled to remove.

**How the diagnostics got there**, since this is the part worth repeating:
each occurrence added exactly the field that would separate the surviving
hypotheses, and the fourth one named the answer outright —
`lastRouteFailure=none` killed the cooldown hypothesis, and `containers=-2`
(the sentinel for *no valid warehouse at all*) pointed straight at the
building having been dissolved. Guessing at a fix after the first occurrence
would have papered over a fixture bug and left it to resurface.

---

### Original entry, kept for the reasoning trail

**The one failure** (during a `fast` run, immediately after the KF-013
follow-up edits landed):

```
courierentersasealedwarehouseanddelivers failed! all 6 logs should reach
the sealed warehouse, saw 0 (act=IDLE)
```

The very next isolated `gametest` run passed, as did eight more after it
(five consecutive `gametest` runs plus three consecutive `fast` runs).

**Hypotheses tested and RULED OUT — each with evidence, not reasoning:**

1. **The 2400-tick budget is marginal.** Ruled out by probe: the timeout was
   temporarily cut to **900** ticks and the test still passed, so a normal
   run finishes in well under a third of its budget. `timeoutTicks` was
   restored to 2400 immediately; it was never inflated.
2. **A higher-priority goal starves the courier.** Ruled out by reading the
   goal table: `CourierWorkGoal` sits at priority 6, above `BoundedStrollGoal`
   (8) and the look goals (9, 10). `RestAtNightGoal` (5) cannot fire — the
   test runs at dayTime 2000 and ends before dusk.
3. **The `energy <= 15` gate closes mid-trip.** Ruled out arithmetically from
   the real constants: energy starts at 90 and the courier's activities
   (CARRYING / SORTING / TRAVELING) are **not** in `tickNeeds()`'s `working`
   set, so the drain is 0.02/tick — 3750 ticks to reach the gate, well past
   the timeout.
4. **A hungry courier abandons the load to eat.** Ruled out by reading
   `EatFromHearthGoal.canUse()`: hunger does cross its threshold (80 at
   0.04/tick reaches 40 around tick 1000), but the goal returns false and
   sets a 100-tick cooldown when `hearth.countFoodUnits() <= 0`, and the
   sealed-warehouse hearth holds only logs. It never takes the MOVE flag.

**What remains:** pathfinding and door-interaction nondeterminism.
`DoorInteractGoal.canUse()` requires `mob.horizontalCollision` — the settler
must physically bump the door before it opens — and `WalkNodeEvaluator`
refuses diagonal movement through a `WALKABLE_DOOR` node
(`isDiagonalValid`). GameTests in one batch run concurrently in adjacent
arenas and share the level's random source, so the AI's draws differ between
runs. That is the plausible remaining cause and it is **not confirmed**.

**Diagnostics are armed.** The timeout message now carries everything needed
to tell these apart on the next occurrence:

```
saw 0 [act=… pos=… energy=… bag=… everInside=… doorOpen=…]
```

`everInside` distinguishes "never got through the door" from "got in and
stalled"; `doorOpen` says whether the door was ever operated at all;
`bag` says whether she even loaded.

**Do not** raise the timeout, loosen the assertions, or delete the test in
response to a recurrence. Read the diagnostic line first — it was added for
exactly that moment.

### Second occurrence, different test — and an arithmetic correction

`courierSackShowsTheRealLoad` (A2b) failed once the same way and passed on
the next run: `saw 16 [load=0 peak=8 act=IDLE]`. Two of its three round
trips completed, the peak load equalled capacity exactly as designed, and
then ~1800 ticks passed with nothing happening. So this is not specific to
the sealed-warehouse arena: it is **multi-trip courier work occasionally
stalling**, in a batch where GameTests run concurrently in adjacent arenas
and therefore share the level's random source.

**Correction to hypothesis 3 above.** It said energy drains 0.02 per tick.
It does not: `tickNeeds()` is called from `aiStep()` under
`tickCount % 20 == 0`, so hunger and energy drain 0.04 and 0.02 per
**twenty** ticks. Over a 2400-tick test that is 4.8 hunger and 2.4 energy —
so the needs model is ruled out by a factor of twenty more than claimed,
and the stall cannot be a needs threshold in either test.

**What was done about it (not a fix — a narrowing).** The courier re-pathed
on a 40-tick timer, so any leg whose path was cancelled left her standing
still for two full seconds before retrying, repeatedly. That is a real
gameplay wart on its own — a courier who stands around looks broken — and
it is the largest idle window in the trip, so it is also the most plausible
place for a stalled leg to eat a delivery budget. `REPATH_INTERVAL` is now
15 ticks with the stuck limits scaled up (20 and 32) so **total patience
before giving up is unchanged**: same give-up behaviour, 2.6× more
responsive walking.

This is offered as a narrowing, **not** as a root cause. If either test
fails again, the diagnostics now report pos/energy/hunger/phase/hearth
remaining, and that evidence should be read before anything else is
changed.

### A fifth hypothesis, investigated and ruled out: shared world time

`setDayTime` is **level-wide**, and every GameTest in a batch shares one
level. A test that flipped the world to night would set `dayPhase()` to
REST for every settler in every arena beside it, and any work goal gating
on `dayPhase() == WORK` would stop dead — which is precisely the observed
signature (`act=IDLE`, partial delivery, intermittent).

`HearthsteadGameTests` does set 16000 and 23000. **But those two tests
declare `batch = "night"`, and GameTest batches run sequentially**, so they
cannot overlap the `day` batch the courier tests live in. Every `day` test
sets the same 2000. So this is ruled out as the cause.

**It remains a live trap for future tests, and is now designed around.**
`RaidDirector`'s nightfall gate was extracted into a pure
`isRollTime(dayTime)` precisely so its test could assert the arithmetic
instead of setting the world to night inside the `day` batch — which would
have introduced exactly this failure rather than diagnosed it.

**Rule for new tests:** never call `setDayTime` from a `day`-batch test
with a night value. If a test genuinely needs night, put it in the
`night` batch; if it only needs the *arithmetic* of time, test the pure
function.

### Third occurrence — with diagnostics, which narrowed it to one gate

`courierSackShowsTheRealLoad` failed again, and this time the message
carried everything:

```
saw 8 [load=0 peak=8 act=IDLE energy=87.6 hunger=75.2 phase=WORK
       hearth=12 pos=1760720,-59,-6454871]
```

Read it: she is **on shift** (`phase=WORK`), **not tired** (87.6), **not
hungry** (75.2), **carrying nothing**, there are **twelve logs in the
hearth** and a chest to put them in — and she is idle. That is not a
stalled walk; it is `canUse()` returning false with obvious work available.

Three gates can do that, and only three:

1. the retry cooldown (`gameTime < cooldownUntil`),
2. `pickDropOff` returning null (the warehouse index reporting no
   containers),
3. `settler.hearth()` returning null, which makes
   `hearthHasHaulableGoods()` false despite a full hearth.

The energy, hunger and day-phase hypotheses are now **dead by
measurement**, not by argument.

**Diagnostics are armed for all three.** `SettlerEntity.recordRouteFailure`
now records *which leg* was abandoned, with the stuck count, the rest
length and the failure streak, and the test message prints it. If the next
failure says `lastRouteFailure=none`, cause 1 is excluded and it is 2 or 3;
if it names a leg, it is 1 and the leg is named. Six further runs did not
reproduce it, so this waits for evidence rather than a guess.

**Blast radius reduced, and this part IS a fix.** A single abandoned leg
used to take a courier off shift for a flat 400 ticks — twenty seconds
idle with a full hearth two steps away, whatever the cause. The rest now
starts at 100 ticks and doubles per *consecutive* failure up to 400, and
any completed delivery clears the streak. A transient hiccup costs five
seconds; a genuinely unreachable warehouse still stops the spin. This does
not claim to cure the stall — it makes the stall cost a fifth as much
while the cause is still unknown.

---

## KF-015 — a raid can resolve as "repelled" while raiders are still alive

**Status:** OPEN, by design decision rather than oversight. Found while
root-causing an intermittent A3 test, and recorded rather than patched at
speed, because the correct answer is a design question.

**The evidence.** `araidarrivesandthenresolves` failed intermittently with
the diagnostic `spawned=3 living=2`: three raiders were created and added to
the world, including the captain, but `livingRaidersOf()` could only see
two. A raider that forms up 26-38 blocks from the settlement lands outside
the small region a GameTest force-loads, and `getEntitiesOfClass` only
returns entities in loaded chunks.

**Why that matters in the real game, not just in a test.**
`resolveIfOver()` ends a raid when `livingRaidersOf()` comes back empty, and
then records it as **repelled** — raising pressure and marking a defeat
against the captain. But "no raiders visible in a bounded box right now" is
not the same claim as "no raiders left". A band whose members are in
unloaded chunks — because the player walked away, or the raid formed up at
the edge of loaded terrain — would be declared driven off while it is
still coming.

**Why it is not patched here.** The obvious fixes each have a real cost:

- Track spawned raider UUIDs on the settlement and resolve only when all are
  confirmed dead — correct, but a raider that despawns or is removed by
  another mod would wedge the raid open forever, which is the failure class
  this slice exists to avoid.
- Add a minimum raid duration before resolution is allowed — cheap, hides
  the symptom, does not make the claim true.
- Force-load the raid area for the duration — heavy, and a design decision
  about performance that should be made deliberately.

**What was done now:** the test no longer asserts through the bounded query,
because that was never what it was testing — it asserts on the band
`spawnBand()` actually produced. The production concern is untouched and
recorded here.

**Do not** treat a green suite as evidence this is resolved. It is a real
gap in raid resolution, and it belongs to the raid slice's remaining work
alongside the telegraph and the scar.

## KF-018 — the lumberjack's tree scan starved on sparse maps

**Status:** FIXED (2026-08-25), verified live.

**The evidence.** In live scene 20260825T180116Z a hired lumberjack stood
idle beside four planted oaks for the entire session. No exception, no route
failure — `LumbererWorkGoal.canUse` simply never found a tree.

**Root cause, two independent halves.** The volume scan
(`WorkScanner.scan`) walks a 97×97×9 offset table — 84 681 positions — at
512 positions per call, one call every 4–6 seconds: a full sweep of the
settlement takes about fourteen minutes of standing still. Worse, the
vertical band (±4) is anchored to the *hearth's* Y, so a tree four blocks up
a slope was invisible forever, not merely late.

**The fix.** `WorkScanner.scanColumns`: anything that grows out of the
ground is found by asking each *column* what is on top of it —
`getHeightmapPos` per column, 9 409 columns, a sweep in ~12 calls — and
elevation stops mattering because the surface is wherever the surface is.
`LumbererWorkGoal.trunkInColumn` walks down from the surface only when the
surface block is itself a log (capped at `TRUNK_DESCENT = 32`), so the
expensive part is paid only on columns that hold a trunk.

**Live proof** (20260825T183505Z, village "Heatherbrook"): hired lumberjack
walked to a `place feature`-planted oak within seconds of daylight, felled
it (trunk verified gone), and hauled repeatedly — the hearth's inventory
grew 0 → 11 → 16 → 27 oak logs across three checks, chest-true.

**The trap this leaves behind.** `scanColumns` sees only the surface: work
UNDER a roof (a tree farm in a hall, logs in a cave) is invisible to it.
The lumberjack accepts that trade — natural trees stand under the sky — but
no underground trade may adopt the column scan without reading this entry.

## KF-019 — hired settlers never arrive at work (activity=TRAVELING)

**Found:** 2026-08-25 22:15Z, first real GameTest suite run of the fleet era
(clean worktree at committed HEAD: 186 tests, 31 failed). A baseline run at
the previous commit (160 tests, 25 failed) proves this PREDATES the fleet
wave — it is not a regression from any of tonight's work.

**Shape:** about twenty tests across unrelated trades fail with the settler
stuck in `activity=TRAVELING`, never arriving to do the work: weaver,
sawyer, fletcher, carpenter, tanner, cook, mason, scholar, miner, farmer,
lumberer, and the courier's full restock route
(`lastRouteFailure=post:idle@5044`). The Schedule test
`thedaysendspeoplesomewherereal` — "in working hours a hired settler is
sent to their own building" — fails too, which points at the posting layer
rather than at any one trade. Two sleep tests fail in the same family
("must actually wake once the night is over", "must actually leave the bed
once energy recovers past dawn"), so a stuck goal is the likely shared
cause.

**Why it was invisible:** the live harness proved the loop by hand (a
lumberjack really did fell a tree, a farmer really did harvest and deliver
on 2026-08-25). Whatever is broken bites in the GameTest arenas, or bites
intermittently, and no suite had been run against the fleet's own code
until tonight. That is the honest lesson: hand-verified live play is not
suite evidence, and the protocol says so.

**Owned by:** ARRIVAL-1 (GoToPostGoal, Schedule, RoadNavigation,
RestAtNightGoal). Anything found in SettlerEntity is reported, not patched
— another worker holds that file.

**Not this:** the other 8 failures at HEAD are tonight's NEW tests awaiting
their goal-registration lines (repair ×2) or blocked behind this same
arrival bug (fuel ×3, miner drops ×2, farmer seed reserve). Two tests were
FIXED tonight (`ahiredsmelteractuallysmelts`, `workshopoutputkeepsitskeepback`).


### KF-019 update — the first layer was the harness, not the mod

**2026-08-25 23:00Z.** Root cause of the twenty "settlers never arrive"
failures: GameTests grouped in one batch spawn together and tick
CONCURRENTLY in a single ServerLevel, and `setDayTime` is global — so every
test that set a time silently rewrote the premise of every sibling running
beside it. Three sleep tests shared batch "night" and set 16000, 22600 and
22600 on the same clock; thirteen more classes declared no batch at all and
landed in `defaultBatch` with up to fifty strangers. A settler standing
exactly on their own post reported TRAVELING because the day phase moved
under them mid-test.

Fixed by giving every daytime-sensitive class its own batch and splitting
the three night tests. A second, real product defect was found alongside it
and fixed: `GoToPostGoal.stop()` never reset the activity, so a settler who
arrived — or was pulled off by a combat target — stayed pinned at TRAVELING
until some other goal happened to overwrite it.

Suite went 31 → 21 failures of 194. The live harness had been right all
along: the mod's loop worked, the suite was lying about it.

**The honest lesson, recorded because it cost a night:** an unrun suite is
not a passing suite, and a suite that has never been isolated can fail for
reasons that have nothing to do with the code under test. Neither excuse
survives contact with evidence — which is why the evidence had to be run.

### KF-020 — crafters stall mid-chain; farmer and lumberer stand idle at real work

**Second layer, visible only once KF-019 cleared.** Crafters do work but
stall: two batches that should finish in 2×ticks+40 take the full 841-tick
window (cook budget 240, mason 360, tanner 400; carpenter and fletcher the
same shape). Farmer and lumberer stand at a mature crop and a standing tree
with `act=IDLE` and do nothing at all.

Leading hypothesis (unproven, owned by STALL-1): a higher-priority goal is
starving the trade goals. `EatFromHearthGoal` sits above them and these
arenas have no hearth, so a `canUse()` that returns true while it can never
succeed would hold the slot forever. `RepairWorkGoal` was just registered
at priority 5, above the trades at 6, and claims unemployed settlers.


### KF-021 — the suite is nondeterministic (and that outranks every test in it)

**2026-08-26 00:30Z.** Six consecutive runs of the same 194 tests, no code
change between several of them, gave failure counts 31 -> 25 -> 21 -> 20 ->
22 -> 24 — and the MEMBERSHIP churned, not just the count. The sawyer test
passed then failed; the mason and fletcher tests failed then passed; the
butcher, the raid-resolves and the farmer-bootstrap tests appeared only in
the last run.

**Why this outranks the individual failures:** a flaky suite cannot gate
anything, and chasing one test at a time is wasted work while the ground
moves under it. It also makes every earlier count in this file approximate
— the honest reading of tonight is "roughly twenty stable failures plus
churn", not any single number.

**Leading hypothesis (owned by FLAKE-1):** the tests ride on random settler
attribute rolls. SettlerAttributes rolls starting values; the Dagsverk
capacity is 20 + STAMINA/5, so a low roll gives fewer batches inside a
fixed tick budget, and the farmer's tended plot is DEXTERITY-scaled, so a
low roll can put a test's crop outside the plot entirely. A test asserting
"two full batches inside 2x ticks + 40" would then pass or fail on the dice.

**The rule that applies (qa/PROTOCOL.md):** "flake" is not a root cause.
Nothing here gets waived, widened or skipped — either the randomness has no
design purpose and goes, or the randomness IS the design (a settler's
character should vary) and the fixture pins what it measures.


### KF-021 update — the churn had a geometry, and it was the raiders

**2026-08-26 00:39Z, run 20260826T003823Z: 24 -> 17 of 194.** The
nondeterminism was not the dice. `RaidDirector.spawnBand` forms a band
26-38 blocks from the settlement centre on a random arc — far outside the
16x16 arena the test built, and GameTest packs arenas close together. A
raider from one test wandered into a neighbour's and was cut down by a
guard belonging to a different test entirely, so "a raid with raiders left
standing is not over" failed for a reason with nothing to do with raids.
Sharing a batch with a SIBLING was as fatal as sharing with a stranger:
all 45 raid-spawning tests across four classes now get one batch each.

Seven tests went green on that alone — the raid resolve, the armour ladder,
the butcher, the sawyer, the carpenter, the scholar, the charcoal cold
start — none of which had anything wrong with them.

**The attribute-roll hypothesis was half right and worth recording as
wrong:** settler attributes really do roll from an unseeded per-entity
RandomSource, so identical runs get different numbers — but the named tests
carry a fivefold margin on effort and sit deterministically below the
plot-widening threshold, so the dice never decided them. The throughput
tests now pin what they measure anyway, so a future balance change cannot
quietly reopen the door.

**Still churning:** the cook, mason and tanner batch tests. **Stable red,
and now the priority:** the three lumberjack tests — felling a tree is the
first thing a player does, and KF-018 records this exact area as fixed and
live-verified, so either that regressed or it never covered the arena case.


### KF-022 — the lumberjack could only see trees standing in the open

**2026-08-26 01:18Z, run 20260826T011756Z: 11 of 194 red.** Root cause,
proven by a live column dump rather than by reading: `trunkInColumn` read
the `MOTION_BLOCKING_NO_LEAVES` surface and treated the block beneath it as
"the trunk, or there is no tree here". A GameTest arena is roofed with
barrier blocks, so the surface was the roof — and the scan gave up three
blocks of air and one leaf above four perfectly good oak logs.

**This was never a test defect.** A tree under a natural overhang, under a
player-built platform, or with a snow layer over its canopy is exactly the
same shape, and until tonight this mod could not see any of them. The
descent now walks past whatever is not a log before following the trunk
down, sharing one TRUNK_DESCENT budget so a decorative column still cannot
be walked to bedrock. The three fixtures are byte-for-byte unchanged,
because they were never wrong.

KF-018 recorded this area as fixed and live-verified, and that was true —
live trees stand in the open. The arena case it never covered is this one.

### The night's trajectory, for the record

31 -> 25 -> 21 -> 20 -> 22 -> 24 -> 17 -> 14 -> **11** of 194, and the
three big causes were: tests sharing one world clock, raiders walking into
the neighbours' arenas, and a scanner that assumed nothing is ever above a
tree. Two of those three were the harness lying about the mod. The third
was a real blind spot in the game that no amount of reading had found and
only a live dump did.

**Still open (11):** the two new raider-breach tests, the courier's restock
and starving-hearth routes, the fuel band test, the smelter/butcher churn,
the summon payload error, `homeInvalidatedWhenWallBroken` ("no sequences
finished"), the plaque advancement's mock-player placement, and the
bystander pig that is REMOVED rather than damaged.

### KF-023 — the courier delivered one bag and then stranded the rest forever

**2026-08-26 02:00Z, run after 298fc11: 4 of 196 red**, all four in the
courier cluster. The restock route was instrumented and the log settles the
argument without a single line of reading:

```
COURIER-DIAG restock-skip crafter=SMELTER recipe=iron_bloom have=10 need=3 roomFor=true
COURIER-DIAG restock-skip crafter=SMELTER recipe=iron       have=10 need=1 roomFor=true
COURIER-DIAG restock-skip crafter=SMELTER recipe=iron_bloom have=10 need=3 roomFor=true
```

The restock predicate asked "is this crafter SHORT of any recipe minimum?"
One courier bag is eight items and the largest minimum on that bench is
three, so the first delivery clears every minimum at once and the crafter is
never short again. `roomFor=true` — the chest has space, the goods exist,
the route is walkable, and the courier declines the trip forever. The
remaining stock strands in the warehouse and the conservation test that
counts every item across the full route fails not because items were
destroyed but because they never moved.

"Short of the minimum" is the wrong question. A minimum is a floor for
*starting* a batch, not a target for *stocking* a bench; the fuel route
already knew this and reserves whole batches ahead (`FUEL_RESERVE_BATCHES`).
The restock route was the only one still asking the floor question.

Two sibling failures — `courierEntersASealedWarehouseAndDelivers` and
`courierOpensAClosedDoorToDeliver` — are a *different* defect in the same
cluster and are tracked with this one because they were found in the same
run: the arrival predicate became a reach-plus-line-of-sight test (5d881b0,
correctly, after a courier was live-witnessed frozen at a 0.31-block
standoff outside a shed), and both door tests stayed red afterwards. The
suspects on the table are whether the sight ray is now too strict from
*inside* a room as well — it runs from eye height to the container's block
centre, which for a chest one block away can clip the wall above it — and
whether the door-opening goal still gets its chance to run once arrival
correctly reports false.

### KF-021, reopened with a cleaner measurement (2026-08-26 02:12Z)

The batching fixes (KF-019, KF-021's raid-arena half) removed most of the
churn but not the cause. Commit 298fc11, run twice, nothing changed between
them:

| where | red | membership |
|---|---|---|
| main tree, 02:00Z | **4** | the four courier tests |
| a fresh worktree at the same commit, 02:12Z | **10** | those four, plus the cook, the smelter, the tanner, the carpenter, and both archer tests |

The six extra tests share one shape: **the worker is IDLE with its inputs
sitting right there** (`saw 0 (raw=2 charcoal=2)`, `act=IDLE potato=3
baked=0`). The two archer tests are the DEX-scaled ones. That points back at
the thing the earlier investigation looked at and cleared: settler attributes
roll from an unseeded per-entity `RandomSource`, effort capacity is
`20 + STAMINA/5` at 2 effort per crafting batch, and the archer's cadence is
DEX-scaled. The earlier conclusion — that the crafter tests carried a
fivefold effort margin, so the dice could not decide them — is now suspect
and is being re-measured rather than inherited.

There are no wall-clock or nanosecond budgets anywhere in the simulation, so
machine load should not be able to change a tick-determinate outcome. If it
turns out that it does, that is the finding, not a footnote.

**FLAKE-2 owns this**, in its own worktree, starting from a three-run
baseline of one unchanged commit — because until the same source produces the
same number twice, every other verdict in this document is provisional,
including the good ones.

### KF-023, closed on the third pass — reach was measured to the wrong thing

**2026-08-26 04:20Z.** The transition diagnostic caught the courier six
blocks from the chest, outside the warehouse walls, reporting arrived:

```
COURIER-DIAG hasArrived=true reason=outside-reachAndLOS
  at=…,783 target=…,789 insideBounds=false
courierentersasealedwarehouseanddelivers failed!
  the courier must walk into the warehouse, not post goods through the wall
```

`hasArrived`'s fallback accepted `distSqrToBounds(at, building.bounds) <=
CHEST_REACH_SQR` — distance to the **building's bounding box**, not to the
container. Standing anywhere along the outside of a warehouse wall satisfied
it, and from there a diagonal ray reached the chest straight through the open
doorway and reported clear. It *was* clear: she could see the chest. **Seeing
it is not reaching it.** The line-of-sight test added in the previous pass was
never the wrong idea — it was guarding the wrong precondition.

The box clause bought nothing even in the case it was written for. A courier
one step short of a chest flush on the box's edge stands two blocks from that
chest, and `CHEST_REACH_SQR` is 6.25 — 2.5 blocks — so the container test
already covers her. Reach is to the CONTAINER, always; bounds survives only as
a cheap fast path for genuinely being inside. The predicate is now three lines
where it was fifteen, and the live 0.31-block standoff that started all of
this stays fixed.

**The reserve was accused and is exonerated.** `MATERIAL_RESERVE_BATCHES = 4`
fixed the stranding (one bag of 8 cleared every recipe minimum of 3, so the
courier never came back), and a worktree run afterwards went from 10 red to
20 — so it was recorded here, and passed to the worker, as the suspect. It
is not. The new failures were the crafter roster — the smelter, the cook,
the tanner, the carpenter — and `TradeSmelterGameTests` and its siblings
contain no courier and no warehouse **at all**: `grep -c "courier\|WAREHOUSE"`
returns 0 for that file. The restock route cannot run in a fixture that has
no warehouse to restock from, so a constant inside it cannot decide those
tests. The 10-to-20 jump belongs to KF-021, not here.

Recording the correction rather than quietly dropping it, because the
reasoning that produced the wrong suspect is the interesting part: two
numbers moved together (a code change, and a failure count) and the
conclusion was drawn from the correlation before anyone checked whether the
changed code was even reachable from the failing tests. One `grep` settled
it. The rule this earns: **before blaming a change for a failure, prove the
changed code runs in that test.**

Four batches stays. `restockConservesItemsAcrossTheFullRoute` asserts
`atSmithy == 12` — every ingot must arrive — and 12 is exactly 4 x the
smithy's 3-ingot recipe, so that test was always asking for this number.

### The courier cluster is closed — verified, not asserted

**Run 20260826T041906Z at commit a6638eb, clean worktree at pushed HEAD:
14 red of 196, and not one of them is a courier test.** All four that were
red before it — `courierEntersASealedWarehouseAndDelivers`,
`courierOpensAClosedDoorToDeliver`, `restockConservesItemsAcrossTheFullRoute`,
`restockDeliversWhenTheOnlyStandableCellIsOutsideTheCraftersBounds` — pass
on the one change described above. The whole cluster came down to measuring
reach against the wrong object.

What remains is a single shape, and it has an owner:

```
barelogsstillbecomecharcoalcoldstart     ahiredsmelteractuallysmelts
firewoodfedsmeltersmeltswithanexactledger ahiredmasonchiselsstoneintobricks
ahiredcookstirspotatoesintobakedpotatoes  ahiredtannerscrapeshidesintoleather
anemployedscholaractuallyadvancestheproject farmerbootstrapsabrandnewplotfromchestseeds
completionexposesthebonusandsurvivesreload  ahiredcarpenterplanessticksintoladders
aminedironorearrivesasrawiron              ahiredbakeractuallybakes
aminedstonearrivesascobblestone            amasonrepairsaraidscarconsumingexactlyonebrick
```

Fourteen tests, no couriers, no raids, no plaques: **a hired worker that does
not work.** Whether that is one cause or several is FLAKE-2's question, and
the count itself is still not a comparison — the same commit has produced 4,
10, 14 and 20 red tonight in different trees. The three-run baseline comes
first; every number after it is measured against that.

### KF-024 — the judge was under-reporting, and nothing would have caught it

**2026-08-26 04:35Z.** FLAKE-2, hunting the suite's nondeterminism, noticed
that `tools/hearthstead-qa` piped the failure roster through `head -5`. It
was worse than that on inspection, and both defects erred in the one
direction a judge must never err in — toward looking better than reality:

1. **The roster was truncated.** A run with fourteen failures stored five of
   them. The log still held the truth, but the artifact is what survives the
   run, and the artifact silently disagreed with the server that produced it.
2. **The count was fragile, and the claim first written here about it was
   wrong.** This entry originally said the manifest recorded `failures=5`
   while the server said 20. It did not. `failures=` came from
   `grep -c "failed at"` on the **log**, and the log had 20 such lines; the
   5 came from the truncated artifact file, and the two were conflated while
   writing this up. Checked against every surviving run: server count,
   roster length and message count all agree (14 / 14 / 14).

   The count is still worth replacing, on its own merits rather than on an
   observed failure: it counts ERROR lines, so it is only correct while every
   failing test prints exactly one. A test that runs out of ticks without
   tripping an assertion prints nothing, and one that trips twice prints
   twice. The count now comes from GameTestServer's own "N required tests
   failed" line, which is the authority on how many TESTS failed, and the
   evidence file says so explicitly whenever the message count is lower than
   the roster.

   Recording the retraction rather than quietly editing it, for the same
   reason the reserve exoneration is recorded above: the mistake was drawing
   a conclusion from two numbers without checking which file each came from,
   which is the identical error made about `MATERIAL_RESERVE_BATCHES` four
   hours earlier. Twice in one night is a pattern, not an accident.
3. **The controller was outside the source fingerprint.** `qa/scripts` is
   fingerprinted, and the comment there says why — an assertion can be
   loosened in that directory. But the file that reads the log, counts the
   failures and writes the verdict was not fingerprinted at all. The two
   defects above could have been introduced, or fixed, without a single
   stored fingerprint changing. A judge that is not fingerprinted is a judge
   that can be edited without the evidence noticing.

All three are fixed: the roster and every `failed at` line are written in
full, the count comes from the server's own "N required tests failed" line,
and the controller is inside the fingerprint. That last change invalidates
every fingerprint stored before today, which is correct rather than
unfortunate — those runs were judged by a different judge.

**The exposure, stated correctly.** The manifest counts appear to have been
right; it is the stored ROSTERS that were short, every time there were more
than five failures. So the numbers in this document stand, and what was lost
is membership: for any earlier run with more than five red, the artifact
cannot say which tests they were. That matters most for KF-021, where
membership is the whole signal — a shifting roster is the evidence, and five
names out of twenty cannot show a shift.

### KF-024, part two — the fingerprint has two clocks, and nothing compared them

Fixing KF-024 nearly caused a worse defect than the one it fixed. The
fingerprint is computed **twice** — `fingerprint()` in `tools/hearthstead-qa`
and `hsqa_fingerprint()` in `qa/scripts/lib_harness.sh` — and `qa/PROTOCOL.md`
says in plain words that the two MUST stay byte-for-byte equivalent, because a
manifest's fingerprint is only meaningful if it can be compared to
`latest.json`'s. Adding the controller to the fingerprint changed **one** of
the two. The requirement was a comment; nothing enforced it. Every manifest
written after that point would have recorded a fingerprint nothing could
compare, and no suite would have gone red to say so.

Caught by reading PROTOCOL.md rather than by any check, which is the point.
Both implementations now include the controller, verified equal by
computation (`f6a62524…` from each), and the controller refuses to run at all
when they disagree:

```
FATAL: the two fingerprint implementations disagree.
  tools/hearthstead-qa   : a6a8176f…
  qa/scripts/lib_harness : 5288be76…
```

**The guard was tested by breaking it on purpose** — the twin was edited in a
throwaway worktree, and the controller exited 2 and ran nothing. A guard
nobody has watched fail is not a guard; it is a comment with a shell around
it, which is exactly what the equivalence requirement had been until tonight.

### KF-021 — SOLVED. The suite was dissolving the buildings it was testing

**2026-08-26 05:00Z, proven live by FLAKE-2 with one instrumented `println`,
not by reading.** Two causes, one mechanism, and between them they account
for every number this document has recorded tonight.

**Cause 1 — the fixtures never satisfied the product's own precondition.**
`BuildingManager.tick()` sweeps ONE building per 20 ticks across every
settlement in the save, and dissolves any whose `plaquePos` no longer holds a
`PlaqueBlock`. That is correct and must stay: *the plaque is the surveyor; no
plaque, no building* (D-005). It is properly guarded against the chunk-unload
case (`level.isLoaded(plaquePos)` at BuildingManager:104), so it cannot
dissolve a real player's village while they are away.

But roughly 23 GameTest fixture files construct a `Building` directly and
`settlement.buildings.add(...)` it **without ever placing a plaque block**.
The sweep reaches one of them and correctly deletes it. Caught in the act:

```
ahiredsmelteractuallysmelts failed! activity=IDLE
BM-DIAG dissolve type=smelter settlement=Testholm tick=4880
        workers=1 sweepCursor=244 totalBuildings=441
```

"Testholm" is the settlement name that test hardcodes. It was its own
building being deleted underneath it. After that `Employment.employerOf()`
returns null forever, `CrafterWorkGoal.canUse()` is false forever, and the
settler stands there with its inputs untouched — **"a hired worker that does
not work"**, exactly the signature, including the tests that were never
effort-shaped: the scholar, both miner tests, the farmer bootstrap, the
mason's repair scar. One fixture defect, not an attribute margin.

This is KF-014's pattern recurring project-wide, because that fix was applied
only to the courier and warehouse fixtures and never generalized.

**Cause 2 — the world was never wiped, so every run inherited the last one.**
`runGameTestServer` reuses `hearthstead-neoforge/run/world` and nothing ever
cleared it. The main tree's had reached **217 MB**, holding settlements and
buildings dating back to 23 August. Measured across two back-to-back runs
with no code change: `hearthstead_settlements.dat` 52080 → 65905 bytes, poi
region files 28 → 34.

That is what made it a *flake* rather than a deterministic failure. Whether
the sweep cursor reaches any particular test's building inside that test's
lifetime depends on how many buildings the save holds — 441 in the
instrumented run — which depends on every fixture that ran before it **and on
every run that ever ran before that**. Same commit, 1 / 12 / 14 / 20 red.

**Neither fix weakens anything.** The invariant stays enforced; the fixtures
are changed to build what the game actually requires a building to be — the
same argument KF-013 and KF-014 already made. Wiping the world makes each run
independent, which is the definition of a suite you can gate on.

**Batch order was ruled out by direct measurement**, not by argument: all 84
`Running test batch` lines are byte-identical across three consecutive runs.
That was my hypothesis and it was wrong; recording it because a ruled-out
cause is worth as much as a found one to whoever reads this next.

### KF-025 — an unloaded mayor is indistinguishable from no mayor

**Found by COSTS-2 while wiring the feast, flagged rather than fixed because
it sits outside that worker's files and predates the change.** Not yet
reproduced live; recorded so it is not rediscovered from scratch.

`Mayor.appoint` decides whether an appointment is a SWAP or a first
appointment by `previous = find(level, settlement)`, and `find` returns null
in two quite different situations: there genuinely is no mayor, **and** the
sitting mayor's entity is not currently loaded or not `isAlive()`. In the
second case a real swap is treated as a first appointment — so the feast is
not charged, and the stand-down morale hit that already depended on the same
call is skipped too. A player who appoints a new mayor while the old one is
asleep in an unloaded chunk gets the swap for free.

The morale defect has been there all along; the feast simply inherits it,
which is the useful part of the finding: `find()`'s two meanings were
harmless while nothing important hung off them, and stopped being harmless
the moment a price did. Fixing it means separating "no mayor is recorded" (a
settlement fact, which the settlement knows) from "the mayor's entity is not
in memory right now" (a loading fact, which it does not) — the same
distinction `BuildingManager` already makes correctly with
`level.isLoaded(plaquePos)` before dissolving anything (KF-021).

### The world wipe on its own is not the cure — measured, not assumed

**Run 20260826T0508Z at a09f018, first run with the new
`rm -rf run/world`: 15 red, where the last contaminated run at the same
area of the tree was 14.** Membership shifted again (the butcher and the
fletcher in, the carpenter out).

So the wipe changed nothing about how red the suite is, and that is the
expected result rather than a disappointment. Two contaminations were
running, and they are different sizes:

- **Across runs:** yesterday's settlements resumed from `run/world`. The wipe
  removes this entirely. It is what makes two runs of one commit *comparable*.
- **Within one run:** 84 batches feed one `SettlementSavedData` in a single
  JVM, reaching 441 buildings by tick 4880. The wipe does nothing about this,
  and this is the one that decides whether the sweep cursor reaches a given
  test's plaqueless building before that test ends.

Only the fixture fix — a real `PlaqueBlock` at every registered building's
`plaquePos` — addresses the second, and it is the one that makes the suite
green. The wipe is what makes the green mean something afterwards. Recorded
because "we fixed the world contamination and the count went UP by one" is
exactly the shape of result that gets quietly dropped, and the reason it went
up is more useful than the number.

### KF-026 — the animation suite reads a file the fingerprint does not cover

**Found while reviewing what to change next, not by a failure. Recorded, not
yet fixed, because fixing it now would invalidate a verification run in
flight.**

`tools/anim_check.py` decides the animation suite's verdict partly from
`hearthstead-neoforge/docs/ANIMATION_CATALOGUE.md` — the catalogue-coverage
check that failed on the 14 new trade idles until their §22 section was
written. But the fingerprint covers `$MOD/src`, `$MOD/tools`, `$QA/scripts`,
`$QA/scenarios`, the build files, `PROTOCOL.md` and (since KF-024) the
controller. **`$MOD/docs` is not in it.**

So the animation suite's answer can change — red to green, or green to red —
with no fingerprint moving to say anything changed. That is the same hole as
KF-024's third defect, one level along: a document that decides a verdict is
part of the judge, whatever directory it lives in.

The fix is to add the catalogue to both fingerprint implementations (they
must stay byte-for-byte equivalent, and the drift guard added in KF-024 will
now catch it if they do not). It is deliberately deferred: FLAKE-2 is running
`full` twice right now to establish whether tonight's work gates, and moving
the fingerprint mid-verification would throw away the evidence it is
producing. It lands immediately after, and before any gate run that matters.

### KF-027 — eight iron ingots unaccounted for, once in four runs

**2026-08-26 06:30Z.** `LogisticsGameTests#restockDeliversWhenTheOnlyStandableCellIsOutsideTheCraftersBounds`
failed once in four consecutive runs of one unchanged commit (db7fd1e); the
other three passed.

```
iron ingots must be conserved across the restock route, saw 4
  [smithy=0 warehouse=4 bag=0]
```

The fixture seeds **12**. At the tick the test gave up: 4 in the warehouse, 0
at the smithy, 0 in the courier's bag. Eight are missing.

This is not being treated as a flaky test. *Every item is physically real
(chest truth); logistics must conserve items* is a permanent product
invariant, and if eight ingots can vanish it is the most serious class of bug
in this project.

**The standing hypothesis, recorded as a hypothesis:** `CourierWorkGoal.giveUp()`
carries the load back to the **hearth** so goods stay in circulation, and this
assertion counts only the smithy chest, the warehouse chest and one courier's
bag — never the hearth. If she gave up mid-route the ingots would be sitting
there, perfectly conserved and entirely invisible to the assertion. That would
make the mod right and the test's accounting short, and the fix would be to
count every container an item may legitimately reach — which makes the test
STRONGER, since it would then prove conservation across the whole route
instead of three of its stops.

**It is explicitly not to be assumed.** Three times tonight — twice by the
coordinator — a conclusion was drawn from two facts that looked related
without checking whether the mechanism was even reachable, and was wrong each
time. The worker on this is instructed to dump every container in the arena,
the hearth included, at the failing tick, and to stop and escalate rather than
fix if the items turn out to be genuinely destroyed.

**A second question rides on the same failure:** this test is named for the
hard case — the only standable cell lies outside the crafter's bounds — and
the arrival predicate was rewritten tonight for exactly that case (KF-023). If
the courier gives up there intermittently, the arrival fix works most of the
time and not always, which is a separate finding from where the ingots went.

**Bookkeeping correction:** commit `04543b5` carries this entry's title but
contains none of it. A `cd` into the mod directory left the heredoc writing to
a path that did not exist, and the `git add -A` that followed swept
ARMOURER-1's in-flight profession work under this message instead. That work
is sound and compiles clean; only the commit message is wrong. Not rewritten,
because the branch is pushed and carries an open pull request, and a
misleading message in history is a smaller problem than a force-push under a
reviewer. The same `cd`-drift has now cost this session a detached HEAD, a
commit outside the branch, and this — the rule in WORK_STATE stands: use
`git -C <path>`, never `cd`.

### KF-027 — CLOSED. The ingots were never lost; the courier was murdered

**2026-08-26 08:10Z, CONSERVE-1, ~21 instrumented runs.** Both standing
hypotheses were wrong, and the method that has carried the whole night —
instrument, catch it live, trust nothing — won again.

**The hearth hypothesis (the coordinator's) was structurally impossible:**
`returnsToSource()` is unconditionally true for `CRAFTER_RESTOCK`, so a
giving-up restock courier can only ever fall back to the WAREHOUSE; the two
`hearth.insertGoods` call sites are gated to other job types. Across 21 runs
with the hearth's inventory dumped on every change, not one ingot ever
appeared there. Recorded with satisfaction rather than embarrassment: the
worker was told not to trust the hypothesis, and did not.

**Nothing was destroyed either.** Caught live, twice, in the server log:

```
Named entity SettlerEntity['Bud'/142] died: Bud was slain by Raider
```

A raider belonging to a DIFFERENT test's raid ("Breachholm",
RaidDamageGameTests) reached this test's arena and killed the courier.
`SettlerEntity#die` drops the bag as real ground `ItemEntity`s — chest-truth
compliant, nothing conjured or destroyed — but invisible to an assertion that
counts chests and one bag. Killed mid-haul with 8 ingots in hand, the
arithmetic reads exactly `smithy=0 warehouse=4 bag=0, total=4`. 2 failures
in 21 runs, both this signature; `giveUp()` was never called once, and the
rewritten arrival predicate (KF-023) worked correctly in every trace — 1-2
repaths, arrival, done.

**The real defect is `RaiderEntity`'s target selector:** it attacks ANY
`SettlerEntity`, unscoped to the settlement its raid is against. In the
suite that means cross-test murder; in the product it means that when NPC
neighbour villages land (phase B2), any passing raider band will aggro
villagers it was never raiding. The fix is to scope target selection to the
raid's own settlement (retaliation via hurt-by stays universal — anyone who
strikes a raider is fair game). Queued for the patch round; `RaiderEntity`
is currently owned by the raider-animation worker, so it is not edited
underneath them.

**The worker changed nothing, and that was the assignment.** Instrumentation
added and fully stripped, both owned files byte-identical to HEAD, no
assertion widened — because widening the count to include ground drops would
have made the test pass while papering over the targeting bug that put the
drops there. A test that goes red when a courier is murdered by a raider
from another test is not wrong about conservation; it is right about
something else.

**A stale-tree false alarm, resolved:** CONSERVE-1's ~21 runs also showed
`everyTradeHasWorkAndAMotionOfItsOwn` red with "armoury has recipes but
nobody can be hired". Its worktree predated d21d5e9 (the commit that wired
ARMOURER's hire path); the main tree's 206/206 run at that commit includes
this test green. No live defect.

### KF-028 — playtest step 284: the same commit passed and failed the same click

**2026-08-26 08:20Z.** Two `full` runs at one byte-clean commit (db7fd1e):
run 1 (04:52Z) — all ten suites PASS, playtest included. Run 2 (05:23Z) —
**nine of ten PASS, playtest FAIL** at step 284:
`expect_server:hearthstead:build_plan — "server log never matched"`, right
after a click and a 5-second wait in the plaque flow.

Same source both times, so this is a probabilistic step, and its family is
already on file: KF-009 records eight distinct harness bugs in exactly the
playtest plaque section, all of the click-timing / focus / frame-race kind,
and none of them mod defects. Suspicion accordingly goes to the harness's
click landing (or the 5s wait racing the client), not to the build-plan
insertion itself — but suspicion is not a verdict, and nobody has looked yet.

Deliberately parked rather than chased: the owner has ordered a live human-
style survival playthrough that walks the very same plaque flow by hand, on
camera, and will answer "does inserting a build plan work in reality"
better than the scripted step can. If the playthrough inserts plans without
trouble, this becomes a harness-timing fix; if it stumbles at the same spot,
it is a mod bug and jumps the queue. The streak consequence is accepted
honestly: green_streak on db7fd1e is 0, and GATE-1 was always going to run
on the patched tip, not on tonight's midpoint.

The night's verification haul at db7fd1e, for the record: gametest 202/202
twice, behavior 0 findings twice, dedicated/performance/client green twice,
performance at avg MSPT 0.6-1.8 against a 45.0 budget.

### The helper hangs the plaque; it does not furnish the room

**2026-08-26 09:05Z.** The first suite run after the wall-removal wave came
back **1 red of 207**, and the red was the new test proving the mill's paper
recipe:

```
millgrindssugarcaneintopaperchesttrue failed!
  the registered mill's chest should be a container
```

`GameTestFixtures.register(...)` — tonight's fix for KF-021 — places the
plaque, records the bounds and registers the building. It deliberately does
NOT place a chest. The new test called it and then looked for a container
that nothing had put there, so `Production.run` had nowhere to take sugar
cane from.

Worth a line because it is the shape of mistake a good helper invites: the
helper made the *dangerous* part (the plaque) impossible to forget, and by
doing so made it easy to assume it handled the rest of the room too. The
fix is one `setBlock` in the fixture plus a comment at the call site saying
what the helper does and does not do — not a change to the helper, which is
correctly narrow. A fixture that furnishes rooms automatically would start
guessing at what each building needs, and guessing is how the twenty
hand-rolled copies happened in the first place.

### The guard earned its keep in four hours

**2026-08-26 09:40Z.** `qa/scripts/check_fixture_plaques.py` was written at
~06:00 to stop KF-021 from ever returning quietly. At 09:40 it caught a real
regression — introduced *after* it was built, by a different worker, in the
exact file that had been audited as safe.

`ChainsGameTests.java` hand-builds bare `Building` objects with no plaque
throughout. FLAKE-2 audited it and correctly left it alone, because the file
never registers its settlement into `SettlementSavedData`, so
`BuildingManager`'s sweep can never reach those buildings. Sound *today*, as
that audit said in as many words — and explicitly flagged as **not
structurally stable**, because one added line would make it live.

WALLS-2 then added the mill's paper test, and wrote that line:

```java
data.settlements.put(s.id, s);
data.setDirty();
```

Not carelessly — it is the obvious way to set up a settlement, and it is what
most fixtures do. But it silently armed every other hand-rolled Building in
the file for dissolution mid-run, which is KF-021 reopening in the one place
everybody had reasoned was fine.

The guard failed the build with the mechanism spelled out, the fix was to
delete two lines (`Production.run` takes a level and a Building; it never
wanted the settlement), and the call site now carries a comment saying why
the registration is absent so the next person does not re-add it.

**The lesson is about guards, not about this bug.** FLAKE-2's answer to "is
this stable?" was *"nothing would catch it"* — and rather than record that as
a known edge, we spent thirty minutes closing it. Four hours later it was the
only thing standing between a green suite and a silent return of the night's
worst defect. A guard against a bug you have already fixed feels redundant
right up until the moment someone reintroduces its precondition from a
completely different direction.

### KF-029 — the hunter took the whole server down on its first kill

**2026-08-26 09:55Z, run 20260826T063349Z.** The first suite run after the
three Ring-1 trades landed did not fail — it *crashed*:

```
NullPointerException: Cannot invoke "Collection.size()" because "captured" is null
  at AnimalHarvest.kill(AnimalHarvest.java:73)
  at HunterWorkGoal.tickHunt(HunterWorkGoal.java:280)
  ...
  Game test server crashed
```

`AnimalHarvest.kill` used the standard capture-drops idiom — swap in a
collector, deal lethal damage, swap the old one back and read what came out:

```java
Collection<ItemEntity> previous = target.captureDrops(new ArrayList<>());
target.hurt(source, target.getHealth() + 1.0F);
Collection<ItemEntity> captured = target.captureDrops(previous);   // null
```

**The idiom is not re-entrant, and `hurt()` re-enters it.**
`LivingEntity#die` performs the identical save/swap/restore around
`dropAllDeathLoot` internally, so by the time `hurt()` returns, the field no
longer holds what this method put there and its value is not this method's to
reason about. The fix holds a reference to the sink list that was passed in,
never reads the second call's return at all, and restores inside a `finally`
so a throw in `hurt()` cannot strand an animal capturing drops forever.

**What makes this entry worth writing is how it was caught.** The run
reported:

```
[gametest] FAIL failures=0
0+ (no server summary; error lines, not tests)
```

That is the honest branch added to the controller hours earlier under KF-024.
The *old* counting expression was `grep -c "failed at"` — and since the server
died before a single test could file a complaint, it would have counted **zero
failures**. A crash that destroyed all 207 results would have been reported as
a number indistinguishable from success. The replacement refuses to guess:
no server summary means no verdict, and it says so in words instead of a
number.

The judge repair was written to fix a truncated roster. It caught a server
crash instead — which is the argument for repairing a judge even when the
defect you can see looks cosmetic.

**Also recorded:** this is a defect that compiled cleanly, passed
`anim_check`, passed `validate_assets` at 875/875, passed the fixture-plaque
guard, and had its own purpose-built GameTests — and destroyed the server the
first time it actually killed something. Static verification cannot see a
re-entrancy bug in a framework call. Only running it can.

### KF-030 — the camera helper killed the player, and only survival could reveal it

**2026-08-26, first survival playthrough, ~05:43Z.** `live.sh`'s
`safe_regrab` restores the mouse grab by clicking — and to make sure that
click lands on nothing breakable, it teleports the player to Y=300 first,
clicks at empty sky, then teleports back. Three prior fixes are commented
above it, each proven live.

Every one of them was proven in a **creative** session, where flight is
exempt and a fall is harmless. The first time this harness drove a
**survival** player:

```
05:43:41  teleport to Y=300
05:43:45  Dev was kicked for floating too long! Flying is not enabled
          (rejoin, still airborne -> kicked again)
          (third rejoin, now falling) Dev fell from a high place
```

The player was killed by the camera helper. That session's inventory was
empty, so nothing was lost — but a later-game player would have dropped
everything they carried at the death site, and chest truth would have been
violated by a *tool*, not by the game.

Fixed: only a creative session gets the teleport. A survival session looks
straight up in place instead, and if there is no clear sky overhead it
**skips the grab click entirely** rather than risk breaking a block — a lost
grab costs a retry, a broken plaque costs a silent re-survey nobody sees
(the exact failure the second fix above was written for). Unknown game mode
is treated as survival, because guessing creative is the guess that kills.

**The lesson is about test harnesses, not about this bug.** Every fix in that
function was real, careful, and validated against the only mode anyone had
ever driven it in. A harness that has only ever been exercised in creative
encodes creative's assumptions invisibly — and the owner's instruction to
play *"uten creative"* is precisely what made it visible. Three prior fixes
and none of them found it, because none of them was allowed to fall.

### KF-031 — the ransom raid that never took anybody

**2026-08-26, found by a raid-night audit, not by a test.**

`RaidObjective.LOSEPENGER` ("Ransom") is a selectable raid objective. It is
gated on population ≥ 4, it has its own lang string, and a captain who
succeeds at one earns an epithet from it — *the Ransomer*, *Chain-Bringer*.

**There is no goal that implements it.** `grep -rin "kidnap\|hostage\|captive"`
across the whole `com.hearthstead` tree returns nothing behavioural. Every
raider's `objectivePos` is set to `settlement.center` regardless of objective
(`RaidDirector.java:253`), so a Ransom raid is mechanically identical to a
Blood raid: the band converges on one point and fights whatever it sees.

This is worse than a missing feature, and it is the reason it gets its own
entry rather than a line in a backlog. A missing feature is an absence the
player can see. **This is the game reporting an event that did not happen** —
the morning report, the captain's earned title and the objective name all
testify to a hostage-taking, and no settler was ever taken. Everywhere else
in this project that pattern is treated as the unforgivable one: it is the
same shape as a manifest under-reporting failures (KF-024), a document
claiming research was uncharged while it charged (COSTS.md), and a catalogue
listing forty animations that do not exist. A system that misreports itself
cannot be reasoned about, and every conclusion drawn downstream of it is
suspect.

**Disarmed first, built second.** LOSEPENGER is removed from
`isAvailableAt` immediately, with a comment at the removal site naming what
would bring it back — stopping a lie takes minutes and building the truth
takes a slice, and the two must not be in flight at once. The real mechanic
(seize, carry, hold, and eventually a camp and a rescue) is a phase-A3 slice
of its own.

**The audit that found it also swept for siblings:** any other captain title
or broadcast line that claims a behaviour with nothing behind it is disarmed
the same way. That sweep is the durable part of this entry — one lie found by
reading is worth less than the habit of checking whether every line the game
says about itself is earned.

### KF-031 — CLOSED (the lie). The sibling sweep found a second, quieter one

**Landed:** `RaidObjective.isAvailableAt` now hard-returns `false` for
LOSEPENGER, with the reasoning inline at the removal site
(`RaidObjective.java`). `RaidPressureGameTests#objectivesMatchWhatTheSettlementActuallyHas`
now asserts LOSEPENGER stays unavailable even for a settlement rich enough
to attract every other objective, so a future edit cannot silently re-enable
it without the test noticing. Suite re-run in an isolated worktree after the
change: the only two red tests are the pre-existing, other-owned failures
(`ahiredhunterhuntsbutneverbreaksthefloor`, `ahiredfisheractuallyfishes`) —
nothing raid-side broke.

**The sibling sweep found one more, smaller instance of the exact same
shape.** `Captain#earnEpithetFrom` is only ever called with `!held`
(`RaidDirector.recordAftermath`), and `held` is computed purely from whether
loot physically escaped (`RaidDirector.resolveIfOver`, driven only by
`RaiderLootGoal`'s KORN-only success flag). No code path ever sets that flag
for a BRANN or BLOD raid, so `Captain#epithetsFor`'s BRANN pair ("the
Torch"/"Ember-Bringer") and BLOD pair ("Red-Handed"/"the Reaper") are
currently **unreachable** — a captain can burn every building and kill every
settler in a BRANN or BLOD raid and can never earn either epithet, because
the gate that grants one only ever looks at whether loot got away.

Worse: `SagaGameTests#aVictoriousRaidGrowsTheLeaderAndEarnsAnEpithet` was
already green for exactly this case, and reads as proof it works — its own
doc comment claimed "since this one actually burned something, earns them
their first epithet". It does not test that. It forces
`s.raidLootEscaped = true` on a BRANN-objective raid, a state no real BRANN
raid can ever produce (only KORN's loot goal sets that flag), to make the
epithet fire. A green test built on a scenario that cannot occur in real
play is the audit-shaped version of the same defect: a passing check telling
the project something is true that isn't.

**Disarmed the same way, scoped to what "disarm" safely covers today:** the
one player-facing line that named a concrete, currently-unreachable outcome
— the Tingbok guide's "'Grimr the Torch' for one who burned you" example
(`hearthstead.guide.saga.body`, en_us + nb_no) — now names only KORN's two
epithets ("the Grain-Thief" / "Larder's Bane"), both genuinely reachable
today. `Captain`'s class doc and the SagaGameTest's own comment are
corrected to state the gap plainly instead of repeating the unreachable
example, so a reader of either no longer walks away with the wrong idea of
what the code does. None of this touches `RaidDirector`'s `held`/`lost`
computation, `RaidLogEntry`, or the defense-report broadcast — those are
tested, documented, deliberate (`RaidDirector`'s own comment: "whether the
settlement HELD is not about who died"), and out of scope for a same-night
disarm.

**Not fixed, and deliberately left open rather than folded into this
entry:** actually making BRANN/BLOD epithets earnable is a real fix, not a
disarm — it would mean keying epithet-eligibility off each objective's own
already-tracked signal (arson count this raid for BRANN, settlers hurt this
raid for BLOD) instead of the settlement-wide loot flag, and rewriting the
SagaGameTest to prove it through a real burn/hurt rather than a forced flag.
Small and contained to `RaidDirector.java` as far as it's been scoped, but
it is new behaviour and a new test, not a lie stopped — it was not one of
the three things asked for tonight, so it is named here for the owner to
pick up rather than started unasked.

### KF-032 — the test world had no trees, and nobody had ever needed one

**2026-08-26, first survival playthrough.** After the camera helper stopped
killing the player (KF-030), founding was still impossible — for a reason
that had nothing to do with the mod:

`qa/scripts/server_instance.sh` hardcoded `level-type=minecraft:flat`. A
superflat world has **no trees, no stone and no ore anywhere.** The player
stood on an infinite grass plain with nothing to pick up, and the first step
of the entire game — punch wood, craft a hearth — could not begin.

Flat is the *correct* default and it stays the default: every automated suite
wants a fast, deterministic, featureless world, and the GameTest and E2E
scenarios build their own arenas regardless. Nothing was wrong with the
choice. What was wrong is that it was not overridable, and that nobody had
ever noticed — which is the finding.

**The absence of trees is proof this harness had never once been driven by a
player who had to gather anything.** Every prior session was creative, or
was a scenario that spawned what it needed. The owner's instruction to play
*"uten creative"* has now produced three findings in a row that no test suite
could ever have produced — a camera helper that kills (KF-030), a world with
nothing in it (this), and an input path that decays over a long session
(below) — and not one of them is a bug in the mod. They are bugs in the
ability to *look* at the mod, which is strictly upstream of every claim this
project makes about itself.

Fixed by making it overridable, default unchanged: `HSQA_LEVEL_TYPE=normal`
gives a real world for playthroughs; suites keep flat and keep their speed.

**Still open from the same session, and now the round's dominant blocker:**
input reliability decays the longer a live session runs — `mousedown` and
`keydown` are increasingly *not received* (not misaimed: unreceived), until
even regrab-and-retry stops working. No root cause yet. It is the single
biggest obstacle to round 2 reaching the lumberjack milestone, and it is
logged rather than worked around, per the standing rule that a harness fought
in silence gets fought again next round.

### KF-033 — the adversarial review, and the honest yield

**2026-08-26.** Five independent lenses read the night's `298fc11..HEAD` diff
(78 files, 6590 insertions); every finding was then attacked by three
skeptics with different angles, majority-refuted findings discarded. 75
agents. **Ten findings survived; twelve were killed.** Ten across 6590 lines
is a thin yield and it is reported as thin rather than padded.

The two that matter most are both **seam defects — each worker correct inside
its own files, and wrong across the boundary**:

**BLOCKER — the three new gathering trades produce into chests no courier
will ever open.** `CourierWorkGoal.findCollectionJob` skips any building that
is neither a MINE nor has a `Production` table; PASTURE, FISHERY and
HUNTERS_LODGE have no `Production` entry because they gather rather than
craft. So the fisher's cod, the herder's wool and eggs and the hunter's meat
sit in their own chests forever, and no settler can ever eat any of it. The
trades worker built three correct trades. The courier worker had, months
earlier, written a correct gate. Nobody owned the space between them.

**HIGH — the restock top-up now exceeds the collection keep-back**, so for any
item that is both an output and an input of one building (the mason's STONE,
the smithy's IRON_INGOT, the weaver's WOOL) two courier routes shuttle the
same stack back and forth forever. `MATERIAL_RESERVE_BATCHES × inputCount` is
16 against an `OUTPUT_KEEP_BACK` of 8; before that constant existed the
threshold was 4 and the band `[4,8]` was stable. `keepBackFor` already carries
exactly this anti-carousel guard for fuel and was never extended to
materials. Restock is the top priority, so a courier locked in the shuttle
consumes settlement logistics capacity indefinitely.

Also confirmed: the mayor's feast is skipped whenever the incumbent is
unloaded (the KF-025 shape, now with a price attached); the mill's paper
recipe is *worse* than hand-crafting and no settler grows sugar cane, so the
wall it was added to remove is still standing; guard armour is destroyed on
death with no drop; `RaiderModel` reads `Mob.getTarget()` client-side where
it is always null, so the authored SPRINT clip never plays; the
fixture-plaque guard's regex matches an ITEM and matches inside comments; and
`GameTestFixtures.placePlaque` sets a plaque state `PlaqueBlock.canSurvive`
would refuse, which any later neighbour update deletes.

**The most valuable section of the report was the one naming what nobody
looked at.** All five lenses were static and single-file: the three new work
goals were never audited as *running code* (the family that produced a
server-killing NPE the same night); the raid rewrite's targeting was never
read at all, and its failure mode is the inverse of a bug — raiders that now
ignore legitimate targets — which nothing static can see; the day-scale
throughput ledger was attempted by two lenses and **both were refuted for
arithmetic errors**, meaning it is unexamined rather than clean; and **nobody
opened a `298fc11` world on HEAD**, which is the cheapest high-yield check
available and the only one whose failure mode is unrecoverable for a player.

That last omission is the finding about the review itself: a panel of readers
will exhaustively read, and will not think to run.

### KF-034 — my hypothesis was wrong twice, and the real causes were better

**2026-08-26.** The hunter and the fisher tests were the last two red for
hours. I gave the worker two confident steers from reading the gates. Both
were wrong, and recording that is the point of this entry.

**The hunter — I said the herd was seeded below the floor.** It was not.
`MIN_SPECIES_POPULATION` was correctly refusing at `alive=4`, exactly as
designed. The real bug was in `tickHunt`/`tickForage`, which set `done=true`
after one action instead of checking the bag: a single kill yields 2-4 items
against a `BAG_TRIGGER` of 6, and once the floor legitimately stops further
hunting there is no second kill to top it off — so **real loot sat in the
settler's bag forever while she read as IDLE.** The floor was never the
problem; it was the thing that made the real bug visible. Fixed by returning
to the lodge whenever the bag holds anything at all.

**The fisher — I said the pond was too small or the box mis-centred.** Four
compounding bugs, none of them that, found by live per-tick tracing:
1. `empty16` is not a cleared void — real generated stone sat where the
   fixture assumed air.
2. `findFishingSpot` picked the *first* dockable tile in scan order, which
   could route the pathfinder straight through the water.
3. **The dock stood flush with the water, and the pond flooded it.** Open air
   beside a live source block is a valid spread target; over the run the
   fisher's own pond swallowed the tile she was standing on and ordinary
   flowing-water physics pushed her off (`waterSeen` 2 → 115 mid-run). Fixed
   at the goal level by recognising a raised-bank shore — the ordinary
   vanilla shoreline shape — and by carving ponds into solid floor instead of
   floating them in the walking layer.
4. Even fishing perfectly (6 clean catches), she then sat TRAVELING forever:
   the chest was one block **outside** the building's bounds, so
   `WarehouseIndex.containers()` found nothing and every deposit trip
   silently emptied nothing and restarted.

Neither threshold moved. The puddle-refusal test still passes, and it passes
because the geometry no longer lets water spread — not because the bar was
lowered.

**The lesson, which is about me and not about the worker.** Both my steers
were plausible, both were derived from reading the code, and both would have
sent someone to change a threshold that was doing its job. The worker ignored
the conclusion and kept the method — instrument, trace live, fix one cause,
re-run, repeat — and found four real bugs where I had guessed one imaginary
one. That is the third time tonight a confident read lost to a live trace,
and the tally is now unambiguous: **on this project, reading generates
hypotheses and only running generates findings.**

Four consecutive clean runs: `All 211 required tests passed`.

---

## KF-035 — the input-decay WALL: GLFW's grab desyncs, and it self-heals

**Status: MITIGATED (verify-and-retry shipped; root X/GLFW cause not fully
pinned).** **Severity:** was BLOCKER — this is round 1's dominant, session-
ending finding (KF-032's closing note), the single largest time cost of that
round, and the one thing standing between the harness and the lumberjack
milestone. **Owner: HARNESS-2, round 2, 2026-08-26.**

### The mechanism (confirmed live, not assumed)

Round 1 described the symptom precisely: `mousedown`/`keydown` "increasingly
do nothing at all — not misaimed, unreceived." Driving a fresh, real
survival session (a leftover round-1 session first, then a clean `start` on
a real `normal`-type world) and instrumenting every layer between `xdotool`
and the game found the actual mechanism:

**GLFW's X11 disabled-cursor input-capture grab on the Minecraft client
silently desyncs from its own capture state.** When it does, *every*
world-control input goes dead together — WASD, jump, mouse-look, mouse
buttons — while *screen-gated* keyboard input (opening chat with `t`,
typing into it, `Return`, `F3`) keeps working normally the entire time.
That split is the fingerprint: whatever is broken is specifically the
grabbed-camera-control gate, not a general input pipe, not X11, and not
`xdotool`. Proven, not inferred:

1. **X11-level delivery was independently confirmed intact at the exact
   moment gameplay input was dead.** With the Minecraft window definitely
   not responding to held WASD or `mousedown`, a bare `xev` window was
   focused and sent the identical `xdotool`-synthesized key — it received a
   real `KeyPress`, `synthetic NO`, immediately. This rules out the X
   server and `xdotool`'s XTEST path as the fault; the event is delivered
   correctly and the client simply never acts on it.
2. **A JVM thread dump (`kill -QUIT`) during a dead-input window showed the
   render thread `RUNNABLE`, mid-frame in `GameRenderer.render` →
   `glDrawElements`** — not blocked, not deadlocked, not in GC. It is
   genuinely running the game loop; it is just not reacting to input that
   reached it.
3. **Chat vs. world-control input diverges exactly as the mechanism
   predicts.** A chat round-trip (`t`, type, `Return`) reached the server
   as real text every time, even in the same window where WASD/look/click
   had just done nothing — because opening chat forces GLFW's cursor into
   normal mode independent of whatever the disabled-cursor grab's own state
   was, sidestepping the desync rather than exercising it.
4. **A single real click into the window (`mousemove` to centre + `click
   1`) instantly and completely restored BOTH mouse-look and WASD movement
   together**, every time this was tried — confirmed via screenshot diffs
   (look) and server-authoritative `data get entity Pos` (movement), not
   by trusting the screen.

### No single deterministic trigger pinned down — and a real reason why not

Isolated fault-injection (a held `mousedown` with concurrent `import`
screenshot capture, at various intensities, up to 8 rapid-fire screenshots
during one hold) reproduced the dead state exactly once and failed to
reproduce it on several repeats of the same pattern. What *did* correlate,
measured directly: this environment's client renders at ~11fps / ~92%
CPU("GPU") even near-idle on `llvmpipe` software GL, on a 4-core sandbox —
and, discovered mid-session by an untouched, unrelated `M
hearthstead-neoforge/src/main/java/.../FarmerWorkGoal.java` appearing in
the working tree and an auto-swept `HARNESS-2 in flight` commit landing
without this session running `git commit`: **this sandbox is shared,
multi-tenant, and other concurrent sessions were building and running their
own Minecraft servers/clients on the same box at the same time.**
`uptime` read **load average 10.57 on 4 cores** during this round's one
genuine, budget-exhausting recovery failure (below) — 2.5x oversubscribed,
entirely from tenants this harness cannot see or control. The render-thread
contention that makes the grab's internal bookkeeping race is real, but its
size is not something any one script can bound. This is the "genuine
external limitation" the fix below is designed around, not against.

### The fix: verify, don't assume — `qa/scripts/live.sh` and `live2.sh`

Round 1's harness already had a regrab click (`safe_regrab`, KF-009/KF-030);
what it never had was a way to tell whether that click had *worked*. A
caller kept sending input into a window that had gone quietly deaf, with no
signal anywhere. The fix adds `ensure_grab()`: nudge the camera a small
amount, read the player's own rotation back from the **server** (never the
screen), undo the nudge (drift-neutral), and if it didn't move, `safe_regrab`
and check again — up to `HSQA_ENSURE_GRAB_ATTEMPTS` (default 5) regrabs,
every attempt and every outcome recorded as a real `check_pass`/`check_fail`
in the session's evidence, not silent. Wired into `look`, `hold`, and a new
first-class `mine <seconds>` primitive (mining/attacking — round 1's
`PLAYTHROUGH_PROTOCOL.md` had told drivers to bypass this script entirely
and send raw `mousedown`/`mouseup` for exactly this action; that gap is now
closed), and into `cmd` in place of its old blind `safe_regrab`. Deliberately
**not** wired into `click`/`key`, which are also used for GUI slots and menu
keys — a regrab click while a screen is open lands on the screen, not on
safe empty space (KF-009 cause 1's exact risk).

**A bug in the fix itself, caught before it shipped.** The first version's
retry loop checked, then regrabbed, for a fixed count — meaning the *last*
regrab's own result was never re-verified. Live evidence caught it directly:
it reported "still dead after 5 attempts" while a manual check moments later
showed the grab was in fact alive — the 5th regrab had worked; nothing had
re-checked it. That false negative would have told a caller to treat a
perfectly good session as a WALL. Fixed by restructuring so the budget
counts regrabs and every regrab is always followed by one more check before
giving up.

**A second, independent bug found in the same code path.**
`$STATE/sky_seen` (the survival-mode safe_regrab's "have I already seen a
clear-sky reading" counter, added by KF-030) is written but was never reset
between sessions. It compares against the fresh per-instance log's own
count on every call, so a value left over from a long previous session can
outrun what a short new session's log will ever reach — silently disabling
the survival-mode regrab click for an entire new session with zero error.
Fixed: `start` now removes it, in both `live.sh` and `live2.sh`.

**Not every "input dead" is this bug.** Late in the proving session the
player was killed by a zombie (real night, real mob spawning, difficulty
normal) and several `input_dead` failures during that window were the
player legitimately sitting on the "You Died!" screen — a screen `ensure_grab`
has no way to distinguish from a desynced grab, so it retries uselessly
until a human clicks Respawn. Recorded here rather than folded into the
mechanism above because conflating "no gameplay effect because the screen
is a menu" with "no gameplay effect because the grab died" would be exactly
the kind of unearned claim this ledger exists to catch. A future
improvement could special-case `Health == 0`; not done tonight, named for
whoever picks this up next.

### Proof: driven live, not asserted

A fresh `start` on `HSQA_LEVEL_TYPE=normal` (a real snowy-taiga world, not
flat), survival + normal difficulty, was driven through founding, camera
turns, confirmed WASD movement (`hold` reporting real before/after server
positions), real mining (`mine` growing inventory counts verified via
`data get`, not screenshots), a full day-to-night transition, and a real
death and respawn — a longer and more varied session than round 1's
active-play window, entirely through the new primitives. The session's own
`.checks.jsonl` recorded **9 genuine `input_regrab` recoveries** (2-6
checks each, all during real, unscripted play — not fault injection) and
**7 genuine `input_dead` exhaustions**, several during the measured
load-average-10-on-4-cores window and the death-screen window above. Every
one of those 16 events is a line of durable evidence; in round 1 the
identical failures left no trace anywhere and were only noticed by a human
watching the game stop responding.

**What this does and does not claim.** The fix does not make the GLFW
desync stop happening — nothing this harness controls can promise that
under genuine, external multi-tenant CPU contention. What it does is turn a
silent, worsening, eventually-unrecoverable failure (round 1's shape) into
a visible, bounded, self-healing one: every dead-input window this round
was followed by either an automatic recovery within budget or a loud,
logged failure that the very next command recovered from cleanly — the
session was never stuck the way round 1's was. `docs/project/
PLAYTHROUGH_PROTOCOL.md`'s old advice to reach for raw `mousedown`/`mouseup`
for mining is superseded by `mine`; that document should be updated by
whoever runs round 2's playthrough to point at it instead.

**Left open for round 3 or later, named rather than guessed at:** the exact
GLFW-internal state transition that desyncs is still not identified (would
require instrumenting or decompiling GLFW's X11 platform layer, out of
scope for a harness-only investigation); whether raising
`HSQA_ENSURE_GRAB_ATTEMPTS` or backing off on detected contention
(`uptime`'s load average) would meaningfully shrink the 7 genuine exhaustions
seen this round, versus the death-screen confound accounting for most of
them, is untested; and a `Health == 0` special case in `ensure_grab` would
close the one confirmed non-bug source of `input_dead` noise.

### KF-035 — sugar cane borrows the wheat farmer's hands

**2026-08-26, flagged by the worker that built it rather than shipped
quietly, which is the right instinct.**

The farmer can now plant and harvest sugar cane. Planting reuses
`WORK_PLANT` and harvesting reuses `WORK_HARVEST` — the wheat clips.

Cutting a cane stalk is not pulling a wheat head, and setting a cane base
beside water is not pressing a seed into tilled soil. The repo's permanent
invariant is explicit: *every settler task has its own keyframe animation —
no shared generic work loops.* Two placeholder reuses is a real, if small,
violation of it.

**Deliberately not fixed before the owner's 18:00 test.** The distinction
that decides it: the clips **play**. Nothing is broken, nothing freezes,
nothing sums into another clip — the acceptance criterion "alle animasjoner
skal funke" is met in the sense of working. What is missing is that they are
not *bespoke*, which is a quality debt rather than a defect. Authoring two
clips to the project's craft standard under four hours of deadline pressure
is exactly how a clip ships that nobody is proud of, and the standing order
is that premium is the only standard.

Recorded here, listed in the owner's known-issues note, and queued as the
first animation work after the test. If he watches a farmer at a cane bed
and it reads wrong to him, that is a better brief for authoring them than
anything written down now.
