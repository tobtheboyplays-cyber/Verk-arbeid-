# QA quickstart — the whole workflow in one page

Written so any session (including a plain Sonnet 5 worker with no prior
context) can test correctly. `qa/PROTOCOL.md` stays the canonical source of
truth; nothing here overrides it.

## The three commands you actually use

Run everything from the repo root. `tools/hearthstead-qa` is the ONLY
approved test entry point — never invoke `gradlew runGameTestServer`,
`runServer` or `runClient` directly.

| when | command | cost |
|---|---|---|
| after any code/asset change | `tools/hearthstead-qa quick` | ~15 s |
| before claiming a task done | `tools/hearthstead-qa full` (twice, back to back) | ~10–15 min each |
| to check whether the gate is already satisfied | `tools/hearthstead-qa gate` | instant |

- `quick` = compile + asset validators + animation contract checker + an
  advisory listing which heavier suites your changed files map to. Green
  `quick` is iteration feedback only — it is NEVER completion evidence.
- `full` = every suite in order, writes the evidence manifest. Completion
  requires `full` to PASS **twice consecutively with no source changes in
  between** (green_streak ≥ 2, one fingerprint). `gate` verifies that
  instantly without running anything.
- Targeted single suites (`gametest`, `behavior`, `dedicated`, …) exist for
  debugging one failure; `changed` prints which ones matter for your diff.

## The two hard rules (violating either produces false results)

1. **Never run two suites at the same time.** All suites share game ports
   and run directories (documented failures KF-002/KF-003). One suite at a
   time, always.
2. **Never edit or compile source while a `full` run is executing.** The
   run's later stages compare its jar against source mtimes; an
   out-of-band compile makes a healthy run fail with a false "stale jar".
   Start `full`, then keep your hands off the tree until it finishes.

## Where the evidence lives

- `qa/reports/latest.json` — overall status, fingerprint, green_streak.
- `qa/reports/artifacts/<timestamp>/` — per-run logs, gametest failures,
  screenshots, behavior traces.
- Debug from these captured artifacts, not from intended behavior.
  Reproduce a recorded failure with `tools/hearthstead-qa reproduce <id>`.

## Seeing it with your own eyes (video/screenshots)

Interactive session against a real client (also used for user-facing
progress videos):

```
tools/hearthstead-qa live start          # boots server+client under Xvfb
tools/hearthstead-qa live scmd '<mc-command>'   # server console command
tools/hearthstead-qa live shot <name>    # screenshot
tools/hearthstead-qa live film <secs> <fps>     # real video (verifies motion)
tools/hearthstead-qa live stop
```

Useful: `scmd execute at <Player> run setblock ~ ~1 ~-2 hearthstead:hearth`
founds a settlement; the `/hearthstead demo` in-game command gives writs,
food and settler eggs.

## Never do these (INV-10)

No deleting/skipping/loosening required tests, no timeout inflation without
diagnosis, no silenced exceptions, no editing reports. If a spec is genuinely
wrong, record a specification correction in
`hearthstead-neoforge/docs/HEARTHSTEAD_QUALITY_LEDGER.md` instead.
