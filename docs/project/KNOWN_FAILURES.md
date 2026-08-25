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

## KF-014 — `courierEntersASealedWarehouseAndDelivers` failed once and has not reproduced

**Status:** OPEN, not root-caused. Observed **once in ten runs**. Recorded
rather than quietly re-run, because an intermittent test is a bad judge and
the next reader deserves to know it was seen.

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
