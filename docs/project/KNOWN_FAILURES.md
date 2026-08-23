# Known failures

Every entry is a real, currently-failing thing with evidence. Nothing here is
a guess. Pre-existing failures must never be attributed to a new slice.

Latest full run: `qa/reports/artifacts/20260823T204752Z/` (overall FAIL).

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

**This belongs to HARNESS-1** (scenario HARNESS-7, clean shutdown). Required:
teardown that actually runs on every exit path; a pre-flight check that the
port is free, with a clear message naming the holder; and the E2E asserting
"the server reached Done (" *before* asserting anything about settlers, so the
next failure of this kind names itself.

---

## KF-003 — Performance probe could not stand up 25+ settlers

**Status:** almost certainly the same cause as KF-002 (LIKELY, not yet
re-measured). **Severity:** medium.

**Evidence:** `performance.log` — "could not stand up 25+ settlers". The probe
boots its own dedicated server on the same port, so a leaked server would have
blocked it identically.

**Next step:** re-run `tools/hearthstead-qa performance` once the harness
guarantees a free port. Do not investigate a performance defect until the
measurement is known to have actually run. The MSPT parser itself is fixed and
verified (it extracted 1.3/1.2/1.2 ms from a real run).

---

## KF-004 — Plaque lang keys missing

**Status:** known, trivial, not yet done. **Severity:** medium (fails assets).

**Evidence:** validator — `block.hearthstead.plaque` missing in `en_us.json`
and `nb_no.json`, and the item key with it. Full key parity between the two
files is enforced.

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

**Status:** partially diagnosed; this is the current slice (HARNESS-1).

**Fixed and verified:** the client's game directory is `run/`, not
`run/client` (options written elsewhere were ignored, leaving the
accessibility onboarding screen up, which blocks quickPlay entirely); the
dedicated server opened a Swing GUI on the same X display, stealing synthetic
input and being what root-window screenshots captured (now `nogui` plus
window-targeted capture); a scenario opening with `Escape` *opened* the pause
menu, because quickPlay drops straight into the world.

**Still unknown:** a later run failed with "player never joined the world" and
the cause was never established.

**Also unresolved:** `live.sh`'s persistent-session design assumes the X
display, the server, its FIFO writer and the client all survive between
separate shell invocations. Unproven.

---

## KF-007 — `gen_settler.py` is not reproducible

**Status:** confirmed independently by two agents. **Severity:** medium.

**Evidence:** it seeds with `random.Random(hash(prof_key) & 0xFFFF | 1420)`.
Python salts `hash()` on strings per process, so consecutive runs emit
different skins and the committed PNGs match neither.

**Expected fix:** seed with an explicit integer constant, regenerate, and add
a validator check so the pipeline's "run twice, identical bytes" rule is
actually enforced rather than assumed.
