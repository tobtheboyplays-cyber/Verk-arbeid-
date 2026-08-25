# WORK_STATE — 2026-08-25 evening (ultracode fleet session)

## Mode
Ultracode ON (user-authorized). Coordinator (this session) + parallel Sonnet
workers under strict file ownership. User's operating loop: coordinator
plays/tests live, feeds findings to workers, spawns a worker per problem.

## Committed & pushed (branch claude/hearthstead-settlement-mod-vbdb9n, PR #1)
- 6921be9 fleet wave 1 (motions, sounds, 9 trade tests, settler/mayor UI,
  raids depth, column scan KF-018, showcase tooling, nametag fix)
- 426b627 door pathing fix (RoadNavigation.setCanOpenDoors — root cause of
  three live freezes) + D-007b renumber

## Live evidence session 20260825T183505Z — village "Heatherbrook"
PROVEN live on the pre-fleet jar: founding → plaque survey → hire;
lumberjack full cycle via column scan (hearth logs 0→38); farmer harvest+
replant+delivery (wheat in hearth); eating (bread 48→35→15 at meal, morale
68→87); traveler recruiting (pop 3→6: Cedric, Isolde, Wilmot +1); homes
via house plaques (3 registered); plaque-break dissolution (3→2, clean);
desire paths worn (7 dirt_path in the walked corridor); mine + barracks
registered, miner + guard hired. KNOWN on this old jar: settlers freeze at
CLOSED DOORS (courier Eira, miner Isolde, two at house doors) — FIXED in
426b627, deploys with the next jar.

## Fleet in flight (9 workers; feedback loops via SendMessage)
- W9 anim punch-up: ALL work clips bigger/heavier + user: CHOP must swing
  FROM THE SIDE like a real feller. Owns SettlerAnimations + catalogue.
- W10 job-limits: "Dagsverk" effort pool (stamina-scaled, sleep-quality
  refill) + farmer's skill-scaled tended plot (3x3→11x11) + lumberjack
  sapling replanting. Owns SettlerEntity + five work goals + Effort.
- W11 recruit: tavern attraction, goods price from hearth (chest-true),
  INNKEEPER trade. Owns Employment/Profession/SettlementManager/TravelerJoinGoal.
- W12 ui-polish: measured text budgets (mcfont/ui_preview, en+nb) over
  Settler/Hearth/Handbook/Plaque screens + plaque screen DECORATION (user:
  "veldig tamt") + Summon button on People tab.
- W13 saga-captains: named raid captains (deterministic roster, epithets
  earned from raidLog, death→lieutenant grudge succession, captain texture
  via gen_raider.py). Owns saga/, Settlement, RaidDirector, Raider*.
- W14 chains: FLOUR/MALT/IRON_BLOOM/TIMBER_BEAM/CURED_HIDE/WOOL_BOLT per
  docs/project/FLOWS.md (binding). Owns Production/ModItems/gen_blocks_items.
- W15 fastboot: production-style client install (display :98 sandbox),
  live2.sh swap-in candidate. Owns qa/scripts/client_install.sh + live2.sh.
- W16 summons: user mechanic — summon worker to workplace, GLOW while
  responding. Owns Summons/RespondToSummonsGoal/PlaqueAction/PlaqueNetwork.
- W17 plaque-art: physical plaque beautification (gen_plaque.py + renderer).
DONE: W1-W8 (wave 1), W5 logistics (reservation ledger, tidy convergence,
StorageScreen HsUi, door regression test).

## Design authority written this session
docs/project/FLOWS.md — the economy constitution (multiply-never-gate,
three rings, courier routes, tool wear, acyclicity). Binding for W14+.

## Integration runbook (when W9+W10 land — they gate the jar)
scratchpad/integrate.sh: compile → validators → jar → live stop →
gametest → quick → live start → showcase.sh village + anim-all → films.
Then full x2 (green_streak >= 2) + RELEASE_GATE (GATE-1). qa/reports/BLOCKED
documents why full cannot run mid-fleet; DELETE it at integration.

## Findings queue (not yet coded)
- Room scanner does not follow ladder shafts below floor (mine's own shaft
  ladders read 0/3) — design question, wave 3.
- Loose leaf-decay saplings litter the ground (W10's replant reduces;
  consider courier groundskeeping later).
- /hearthstead mayor from console replies silently (UI supersedes; low).
- Guard armor gated by rank + Vaktkaptein salute = task #35 (blocked on
  W10/W11 files).

## Coordinator-owned integration duties
Goal registration line for RespondToSummonsGoal (W16 reports it);
lang merges from every worker report (en+nb); ModBusEvents wiring done for
Settler/HearthMayor payloads; task list #27-#35 tracks the schedule.
