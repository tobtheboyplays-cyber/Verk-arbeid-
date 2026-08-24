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

**RELEASE_GATE re-review (Opus call 3 of 3): PASS.** Independently
re-verified all 4 fixes with real experiments, not just diff-reading —
re-decompiled Entity/LivingEntity to confirm no constructor-ordering or
`onSyncedDataUpdated` hazard for HIGH-1; re-broke a generator and
re-deleted/renamed a layer file to confirm MEDIUM-2/3 genuinely fail
(not just reworded skips); decoded committed pixels for all 4 hair
styles to confirm MEDIUM-4's `>= 3` threshold is correct, not just
correct for the one broken case; re-ran `tools/hearthstead-qa gate`
itself against current HEAD (`f34d16c`): `GATE: PASS (green_streak=2)`.
4 non-blocking observations recorded (astronomically-unlikely seed-0
collision cosmetic edge case; two GameTest spawn positions sit just
outside a template's nominal bounds but not a real leak; a pre-existing
one-row gap in style 3; Pillow-absent texture checks stay warn-only
outside MEDIUM-2's scope) — none block, none need action this slice.

**SLICE VISUAL-1 — DONE.** All 3 Opus calls used (1 PLAN_GATE, 1
RELEASE_GATE, 1 re-review) — exhausted, per the resource governor.
SLICE ANIM-1 starts Sonnet-only.

## SLICE ANIM-1 — in progress. No PLAN_GATE call spent (plan already
complete, PLAQUE-1 precedent)

`hearthstead-neoforge/docs/ANIMATION_CATALOGUE.md` already specifies all 23
A1 clips down to exact bone keyframes, sound contracts and QA assertions
(§0-§17) -- more detailed than a PLAN_GATE would produce. Reusing it
without a second Opus call, same as PLAQUE-1 reused `PLAN_PLAQUE-1.md`.
ANIM-1's Opus budget (2-3 calls) stays fully unspent, for RELEASE_GATE
(+BLOCKER_GATE if needed) only.

**A1 exit criterion (catalogue's own words):** `anim_check.py` green with
new §17 assertions, `tools/hearthstead-qa animation` PASS, every clip
visually inspected.

**Scoping decisions made (real, not deferred without saying so):**
- Farmer: split the existing single WORKING phase into real HARVEST-then-
  PLANT beats (both already happen in one `harvest()` call -- splitting is
  re-sequencing, not new gameplay). Added a small genuine TILL/WATER field-
  maintenance pass (till bare dirt next to farmland; water dry farmland)
  so all 4 farm clips have live triggers, not just 2.
- Lumberer: add a LIMBING phase after felling, before hauling; HAUL_LOG
  plays while carrying logs home.
- Locomotion (WALK/WALK_HURRIED/RUN_PANIC/WALK_LIMP/CREEP_NIGHT) are
  mutually exclusive alternatives picked once per frame in `setupAnim`,
  priority: onClimbable() > FLEEING > health<40% > night+dark+unarmed >
  TRAVELING > default WALK. CLIMB_LADDER bypasses `animateWalk` entirely
  (hand-over-hand cadence isn't swing-amount-driven).
- GUARD_PATROL layers arms+head over WALK's legs/torso/cloak by calling
  `rightArm.resetPose()`/`leftArm.resetPose()` before applying its own
  channels -- vanilla's `animate()` is additive, so this is the only way
  to get a genuine override instead of arm-swing-plus-lock garbage.
- SHIELD_BLOCK wired to its "reflexive block after taking a hit" trigger
  (real, live) -- the command-wheel "hold" trigger doesn't exist yet
  (A3), documented as deferred, not silently dropped.
- WAKE_STRETCH fires from `RestAtNightGoal.stop()` when the settler was
  sleeping; per-entity phase offset via `entityId % N` satisfies §17.4
  check 25. Village-wide 60-tick dawn window is NOT implemented (needs a
  settlement-wide scheduler that doesn't exist) -- documented deferred.
- New sounds: seed_press, crop_pull, bag_stow, water_pour, blade_hit,
  yawn, ladder_creak, settler_eat (8 new `render_*` in `gen_sounds.py`,
  reusing its existing DSP toolkit). limb_snap reuses `chop`'s render at
  higher pitch via `SoundEvent` pitch param (catalogue's own suggestion,
  no new asset). haul_strain reuses `settler_hm` pitched down, same
  reason. CELEBRATE's cheer accent reuses `settler_hm` (a real, honest
  simplification, not silence) -- a bespoke "cheer" mumble is deferred.
  `SLEEP_IN_BED`'s optional breath sound and `GUARD_STANCE`'s optional
  armour clink are both explicitly optional in the catalogue -- deferred.
- Pose-sampler contact sheet (§17.5) is NOT built as an offline Python
  renderer (would mean reimplementing keyframe interpolation + forward
  kinematics from scratch in Python for one QA artifact). Substituting
  REAL in-game screenshots via `tools/hearthstead-qa playtest`/`live` of
  each new clip -- honest, uses existing infra, arguably stronger
  evidence. Documented as a scoping substitution, not a silent skip.

## Next concrete action

Implementation order: (1) SettlerActivity 7 new values -> (2) gen_sounds.py
8 new sounds + regenerate -> (3) ModSounds registration -> (4)
SettlerAnimations.java all 23 clips -> (5) SettlerEntity new
AnimationState fields + wiring -> (6) SettlerModel.setupAnim locomotion
selection + layering + damping table -> (7) Goal classes (Farmer/Lumberer/
GuardPatrol/RestAtNight) -> (8) anim_check.py rewrite (§17 checks) -> (9)
lang keys for new sounds -> (10) GameTests -> (11) full verification +
in-game visual inspection -> (12) RELEASE_GATE.

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
