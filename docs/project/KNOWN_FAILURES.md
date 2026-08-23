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

## KF-002 — Dedicated-server E2E: settlers did not spawn

**Status:** NOT diagnosed. **Severity:** high.

**Evidence:** `dedicated.log` in the run above — "settlers did not spawn on
dedicated server". This suite passed earlier the same session (boot, found,
restart persistence all verified), so it is a **regression** introduced by the
plaque conversion, not an inherited failure.

**Suspected (LIKELY, unverified):** the founding flow spawns settlers, but the
E2E asserts on population via a path affected by the `BuildingManager` rewrite
— or the reused `/tmp` server world carries stale state. Reproduce before
theorising further: `tools/hearthstead-qa dedicated`.

---

## KF-003 — Performance probe could not stand up 25+ settlers

**Status:** NOT diagnosed. **Severity:** medium.

**Evidence:** `performance.log` — "could not stand up 25+ settlers". Almost
certainly the same underlying cause as KF-002 (the probe summons settlers via
the same path). Fix KF-002 first, then re-measure. The MSPT parser itself is
fixed and verified (extracted 1.3/1.2/1.2 ms from a real run).

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
