# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Current goal

**SLICE HARNESS-1** — make in-game testing work end to end, so every later
slice can actually be proven rather than described.

## Acceptance criteria

- AC-1 Client joins the world, confirmed **server-side** (`joined the game`),
  not merely "a window appeared".
- AC-2 Synthetic input reaches the game: key, chat command, click, look each
  produce an observable change.
- AC-3 Screenshots capture the **game window** and can be opened afterwards.
- AC-4 The mod's own content is visible in-world (hearth, settlers) — not a
  menu, not the title screen.
- AC-5 `film` yields an ordered contact sheet an animation can be judged from.
- AC-6 A live session survives across separate shell invocations.
- AC-7 Teardown runs on **every** exit path; no leaked server or client.
- AC-8 Failure to reach the world is a hard FAIL with a diagnostic screenshot.
- AC-9 Two consecutive clean launches agree.

## Key decisions

- Target is NeoForge 1.21.1, not Fabric (D-002).
- Build Plan reverts to a separate item; plaque starts EMPTY (D-006) — next
  slice, not this one.
- Room detection is plaque-seeded (D-005); a home must be a bounded room
  (D-004); roofing is geometric, never `canSeeSky` (D-003).
- Model policy: Sonnet is the continuous worker; Opus only at PLAN_GATE and
  RELEASE_GATE (max 2 calls, 3 absolute). Fable dormant unless asked.

## Changed files (this slice)

None yet — PLAN_GATE in progress.

## Tests passing

- `./gradlew build` green; jar produced.
- `tools/hearthstead-qa doctor` PASS.
- Asset validator 160/167 (7 failures are the plaque's missing resources).
- Animation check PASS (9 definitions, 48 channels).
- Client boots for real under Xvfb; title screenshot captured.

## Tests failing

- GameTests 10/15. All 5 failures share one cause — KF-001.
- Assets: plaque lang keys missing (KF-004).
- Dedicated + performance: were port contention, re-running to confirm.

## Known problems

- **KF-001** `hangPlaque()` replaces a wall block; the plaque is non-solid, so
  the room's flood fill escapes (1856 cells vs 27). Fix: hang it in the air
  cell against the wall, `hutOrigin.offset(1,2,-1)` facing NORTH.
- **KF-002/003** NOT mod bugs — a leaked server held port 25565. Harness must
  tear down on every path and check the port before starting.
- **KF-005** blockstate references `plaque_red|amber|green` models that do not
  exist.
- **KF-006** playtest reaches the world only sometimes; possibly the same
  leaked-port defect.
- **KF-007** `gen_settler.py` seeds with Python's salted `hash()`, so it is not
  reproducible.

## Next concrete action

Await PLAN_GATE output, then hand the whole implementation and fix loop to a
single continuous `sonnet-builder`. No model switching mid-loop. Opus returns
only for RELEASE_GATE.
