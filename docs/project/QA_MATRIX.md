# QA matrix

Scenarios, their ids, and what each must prove. A scenario is only real when
it names its setup, its actions, its expected **authoritative** result, and
its failure conditions.

Evidence lives in `artifacts/qa/<scenario-id>/<timestamp>/`.
Verdicts: PASS / FAIL / BLOCKED / **FLAKY (which is not PASS)**.

## Fixed QA conditions

So two screenshots are comparable and two runs are equivalent:

| setting | value |
|---|---|
| world | flat, dedicated server at `/tmp/claude-0/hsqa-server` (rebuildable) |
| dimension | overworld |
| game mode | creative for setup, survival where the scenario is about survival |
| time / weather | `time set 1000`, `weather clear`, `doDaylightCycle false` |
| resolution | 1280×720 |
| GUI scale | 3 |
| graphics | software GL (Mesa llvmpipe under Xvfb) |
| sound | **unavailable in this environment** — never assert on it |

## HARNESS-* — the QA system proving itself (current slice)

| id | must prove |
|---|---|
| HARNESS-1 | The client launches, joins the world, and the player is confirmed **server-side** (`joined the game`), not merely that a window appeared. |
| HARNESS-2 | Synthetic input reaches the game: a key, a chat command, a click, and a look each produce an observable change. |
| HARNESS-3 | A screenshot captures the **game window**, and it can be opened and inspected afterwards. |
| HARNESS-4 | The mod's own content is visible in-world (hearth placed, settlers present) — not a menu, not the title screen. |
| HARNESS-5 | Motion capture: `film` yields an ordered contact sheet, so an animation can be judged moving. |
| HARNESS-6 | A live session survives across separate invocations, so behaviour can be stepped through. |
| HARNESS-7 | Clean shutdown; no stale client or server blocks the next run. |
| HARNESS-8 | Failure to reach the world is a hard FAIL with a diagnostic screenshot — never a silent pass. |
| HARNESS-9 | Two consecutive clean launches produce the same result. |

## PLAQUE-* — required before the plaque slice may lock

Per the operating contract. Not all are in the first slice; each is required
for the scope that includes it.

Placement and physicality: every wall facing; rotation; bounding box;
breaking; item drops; support removal.

Build Plan: insertion; removal; invalid item rejected without being consumed;
duplicate/rapid interaction; **empty interaction opens no UI**; correct state
transition on each.

State model: `EMPTY`, `PLAN_INSERTED_UNLINKED`, `LINKED_VALID`,
`LINKED_INCOMPLETE`, `ORPHANED`, `NO_PERMISSION` — each with deterministic
behaviour and visible feedback.

Persistence: save and reload; full restart; chunk unload and reload; dimension
handling; plaque moved or removed; building deleted; invalid and orphaned
links.

Management: assignment; eviction; capacity change; resident removal;
permission denied. Eviction must change **only** the home assignment — never
profession, equipment, position or life.

Multiplayer: simultaneous interaction by two players converging on one state;
stale-revision action refused; client reconnect; server restart.

Presentation: translation keys present; no missing resources; texture and
model correctness; no z-fighting; all lighting conditions; GUI scales and
resolutions; animation and sound timing (sound **BLOCKED** in this
environment); no duplicate sounds or double actions; clean client and server
logs; performance with many plaques.

## CITIZEN-* / AI-*

Settler behaviour that must be exercised in-game, not inferred: pathing
through a closed door; claiming and sleeping in a bed; losing a home and
seeking another; work loops producing real inventory changes; stuck detection
and recovery; behaviour across chunk unload and save/load.

The decision-trace detectors (`qa/scripts/analyze_trace.py`) already catch
thrashing, stuck navigation, ignored starvation and teleports — a scenario
that trips one of them is FAIL regardless of what the screenshot shows.
