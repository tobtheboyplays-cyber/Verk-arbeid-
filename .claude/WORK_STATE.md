# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Closed slices (full history in docs/, not here)

- **HARNESS-1, PLAQUE-1 — DONE.** KF-009 (8 harness bugs) in KNOWN_FAILURES.
- **VISUAL-1 — DONE, RELEASE_GATE PASS** (all 3 Opus calls spent). Modular
  appearance (seed → 5 composited layer sheets, client `SettlerTextureCache`),
  KF-007 determinism fix + `check_pipeline()`, KF-010 constructor-seed fix.
- **FIX-1 — DONE** (dedicated-server + performance regressions).

## SLICE ANIM-1 — all 23 A1 clips landed; both Opus calls spent

RELEASE_GATE (call 1) REVISE → whole fix round landed and verified; the
re-review (call 2) REVISE → all its findings landed too. Full detail lives
in `docs/HEARTHSTEAD_QUALITY_LEDGER.md` and KNOWN_FAILURES (KF-011 the
stuck-asleep blocker, KF-012 the corrected scmd attribution). Highlights
worth carrying: SLEEPING now restores energy faster than RESTING; HAUL_LOG
and the courier carry clips fully replace WALK; all 7 accent sound
contracts wired via `SettlerEntity.tickAccents()` and enforced by
`anim_check.ENTITY_SOUND_CONTRACTS`; `export_bbmodel.py` UUIDs SHA-1-derived
so Blockbench renders no longer invalidate the fingerprint. **ANIM-1's Opus
budget is spent (2 of 2)** — remaining defects there are Sonnet's; a third
call needs a genuine BLOCKER_GATE.

## SLICE A2a — warehouse + courier

Landed: `WarehouseStorage` (derived, revisioned, never persisted,
destination-first `insert()`); `CourierWorkGoal` (hearth → warehouse, one
direction only so it cannot deadlock like MineColonies #5333; food never
leaves the hearth); the read-only storage view; 7 courier sounds; the five
courier clips. Multi-`@GameTestHolder` discovery PROVEN (suite 25 → 33).

**KF-013 — the courier loaded and never delivered.** Found by *playing*,
after `gate` reported `green_streak=2`. A warehouse has no beds, so its
anchor is the plaque block — in a wall — and the goal routed there, gave up
one block outside the arrival radius, and re-entered on the next tick with
the settlement's logs parked in a bag. This is MineColonies #2932, in the
system whose design note claimed to avoid it. Fixed: deliver to a real
container at a standable cell; arrival requires being *inside* the
building's bounds (no posting through walls); a failed route rests 400
ticks; a new RETURNING mode carries an undeliverable load home. Also fixed
a latent overflow — `WarehouseStorage.of()` compared against a
`Long.MIN_VALUE` sentinel, so a never-refreshed index read as fresh and
every warehouse looked empty.

Two new GameTests, **both verified to fail on the pre-fix code** (restore
the old file, run `gametest`, restore the fix — do this for every
regression test worth trusting).

## SLICE A3 — raids. Steps 1 and 2 (schedule + enemies) are built.

**GATE: PASS (green_streak=2)** at fingerprint `20260825T024140Z`, 49/49.

`RaidPressure` replaces the timer both references use. No night is ever
provably safe: a real roll every night, gated on the settlement being worth
attacking, chance 5%→55% with pressure. Quiet nights raise it faster for a
richer settlement; **repelling a raid raises it** (the deliberate inverse of
MineColonies, where losing buys quiet); one hard guarantee — never two
nights running below BELEIRING. The roll is a parameter, so every rule is
exactly testable.

`RaidObjective` (KORN/BLOD/BRANN/LØSEPENGER) is picked from what the
settlement actually has. `RaidCaptain` carries an earned byname, a record
that grows from wins and losses, a grudge against one named settler, and an
approach always ≥60° off their last — because walling the road that worked
last time must not be a solution. `RaidPlan` + the capped enemy gallery
persist on the settlement. `RaidDirector` runs it all from the hearth tick;
`/hearthstead info` shows stage/pressure/chance in both languages.

**Nothing spawns yet, by design.** A scheduled raid is logged with who,
what and from where. Step 3 is the entity that enacts the plan: the first
faction's raider, the captain's visible identity, and the Korn objective
against the warehouse A2a already built and proved.

Tests are proven judges by mutation, not assumed: forcing `inGracePeriod()`
false + the chance floor to zero fails five tests; removing the approach
shift fails the captain test naming the exact angle.

## Next concrete action

**PLAQUE-2 steps 1-3 done.** The survey crosses to the client, the plaque is
portrait, and **the sheet is now written on**: header drawing, ruled line,
title, and one live line per requirement with its count and a tick or cross,
green / amber / red to match the lamp. Place the bed and the line flips on the
wall — no click, no command. Verified in game in all four mockup states
(empty well / no room found / gathering / ready), each reached through the
real right-click-with-a-plan path, not by NBT.

Model changes that made it possible: the frame opened from 5.4x7.4 to 7.4x8.3
model px, the iron brackets now stop at the brass rails so none crosses the
sheet's corner, and the lower rail lifted off the status lamp (which had been
showing as a 1px sliver of its dome).

**Owner note acted on:** *"Kan lage litt tydeligere bygg"* — the header
elevation was redrawn bigger, one ink weight, no studs or braces, and sized to
sit inside the opening with margins. Two attempts: the first redraw ran its
eaves off the visible edge.

`PlaqueSheet` (common, not client) decides what each line says, so the product
decisions are GameTest-judged rather than screenshot-judged. Both new tests
proven by mutation: forcing every ink to MET fails them.

**Next: PLAQUE-2 steps 4-5** — per-type header drawings and per-requirement
icons (the `Line.id` needed for that is already carried), then the right-click
screen rebuilt to match the mockup, with the READY TO BUILD button.

**KF-015 is OPEN** — a raid can resolve as repelled while raiders live in
unloaded chunks. Design question, recorded not patched.

## SLICE A2b — the courier's sack — DONE

Synced `DATA_CARRY_LOAD`/`DATA_CARRY_CAPACITY`; the goal's private
`LOAD_TRIGGER` is gone, so the AI's stop condition and the renderer read
one number. New `sack` part scales 0.55→1.15 with the load, hangs lower as
it fills, lags the stride and bends the spine; the decorative pack hides
while it shows. First version passed every check and looked like a pale
crate — reshaped 8x8x5 → 7x8x6 and repainted around contrast after staging
three settlers side by side and *looking*. UV (0,17) verified free against
every region (the first pick collided with `hat_brim`); generator and
Blockbench export both byte-stable across two runs.

## Standing infrastructure (new this session — use it)

- **Fast-quality mode** (CLAUDE.md + premium-build-loop skill): parallel
  Sonnet workers under strict file ownership, seam-then-fan-out, Opus only
  at gates, full QA rarely (slice end ×2), video evidence per finished task.
- **`tools/hearthstead-qa quick`** — the after-every-change check (~15s).
  `qa/QUICKSTART.md` — whole workflow on one page.
- **`sonnet-driver` skill** — session-start recovery + escalation table
  (agent defs are model-pinned; a Sonnet main session gets Opus at gates).
- **Blockbench bridge** (`tools/blockbench/README.md`): real Blockbench
  web build served locally, driven headless via playwright-core +
  preinstalled Chromium; `export_bbmodel.py` (model + all 23 clips),
  `bb_render.mjs` (static/posed viewport renders). Mandated for art work.
- **`animation-quality` skill** — researched weight/impact principles +
  the CHOP diagnosis template.

## Load-bearing findings from live debugging (do not re-derive)

- `ss -ltnp` unreliable here — use `lsof -ti tcp:<port> -sTCP:LISTEN`.
- GNU `timeout` needs `--foreground`, else killing the PGID misses java.
- Xvfb ignores SIGHUP — explicit `pkill -9 -f "Xvfb :NN"`.
- GLFW needs a real click before relative look works; quickPlay never gives one.
- Console `~`-relative commands resolve against the console's context —
  use `execute at`, or `cmd` when the check needs the player's position.
- Player position drifts even from passive waiting — `capture_pos` before
  any `~`-relative command after a wait (KF-009).
- `execute at ... run <anything>` suppresses ALL feedback silently.
- `fill <box> X replace X` is a no-op the game never counts.
- A screen left open absorbs later clicks — `key Escape` between iterations.
- Never nest `nohup cmd &` inside a backgrounded Bash call.
- Never run two suites at once (KF-002/KF-003); never edit/compile source
  while a `full` run executes (false "stale jar" in its playtest step).
- Client boot under software GL can take minutes while genuinely
  progressing — never treat slowness as a hang.
- `playtest.sh` hard-fails on a stale jar — that check has caught real
  staleness twice; don't fight it, rebuild.
- `sendSuccess(msg, false)` leaves NO server-log trace for player-issued
  commands — check the flag before adding log-based expectations.
- Silence in a log is not proof a path ran — swallowed exceptions need
  explicit success/failure logging before evidence-based verification.
- Raw NBT `Profession` merge on a settler skips `assignProfession()` (no
  tool equip, no records) — fine for animation smoke checks, NOT for
  filming "real" behavior; use the writ/demo flow for user-facing video.
- `data merge` CAN set persisted fields (Profession) on a live entity;
  synced-only state (Activity) it cannot.

## Known problems (pre-existing, other slices' scope)

- `safe_regrab()`'s Y=300 round trip causes drift warnings (architecture
  note; a non-moving regrab would retire the class — not now).
- Village-wide dawn wake window (settlement scheduler) — deferred, noted
  in catalogue decisions.
