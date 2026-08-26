# PLAYTHROUGH REPORT — SPILLER-1, full survival loop

**Jar:** `hearthstead-0.2.0.jar`, built from tree pinned at commit `fe889ed`
(byte-clean worktree, `/tmp/.../scratchpad/verify-tree`).
**Session start:** 2026-08-26 ~05:42Z, `qa/scripts/live.sh` on display `:99`,
port 25574, player `Dev`.
**Session setup performed (permitted category):** gamemode survival (was
creative by `live.sh start`'s own default — switched immediately, before any
gathering), difficulty normal (was peaceful by default — switched because
peaceful suppresses both raids and hostile mobs, which would make this not a
survival playthrough at all), `doMobSpawning true` (was false by default,
same reason), time set day.
**Rules in force:** no `/give`, no `/setblock` for anything a player builds,
no cheated materials. `/hearthstead demo` (which hands out a hearth, build
plan, saplings etc. for free) is explicitly NOT used — everything is
hand-gathered and hand-crafted. Server commands used only for session setup,
observation (`scmd`/`cmd` reads), and camera work, per protocol.

---

## Running log

Format: `HH:MM` stage — VERDICT — what happened — evidence.

### Founding

- `05:42` Founding — OK — Session up, survival+normal difficulty confirmed
  by chat toast. Spawned on open plain, hotbar empty (no cheats). Sparse
  low vegetation visible on horizon, no trees in the immediate spawn view.
  Evidence: `qa/reports/artifacts/live/20260826T054216Z/shots/status.png`.

- `05:43` Founding — **WALL (harness)** — `live.sh look`'s `safe_regrab`
  teleports the player to `~ 300 ~` to click safely away from the crosshair
  before restoring position. That trick was built and proven only against
  CREATIVE-mode sessions (KF-009), where the anti-cheat "flying" check is
  exempt. The moment the player is in SURVIVAL mode, the same teleport
  leaves them airborne at Y=300 with no ground under them; the vanilla
  server's own movement check kicked with `Dev was kicked for floating too
  long! ... Flying is not enabled on this server` within ~4s of the
  teleport (`logs/latest.log`: `05:43:41` teleport → `05:43:45` kick). A
  second immediate rejoin was kicked the same way (still airborne from the
  same teleport). A third rejoin caught the player already falling, and it
  **fatally fell and died** (`Dev fell from a high place`, 0 items lost —
  inventory was still empty at that point, so chest-truth is not implicated,
  but a later-game player carrying goods would have dropped them at the
  death location). Root cause: `qa/scripts/live.sh`'s `look`/`cmd` handlers
  (both call `safe_regrab`) are unsafe to use against a survival-mode
  player at all — every call risks a lethal fall or a kick-driven
  disconnect loop. Worked around for the rest of this session by never
  calling `look`/`cmd`; camera turns went through raw
  `xdotool mousemove_relative` (grab already established once at session
  start survives fine across plain camera turns and mining clicks — it is
  only `safe_regrab`'s specific Y=300 teleport that is unsafe), and
  server-side reads went through `scmd` (acceptable here since the hearth
  ends up close to world spawn, so the "nearest settlement to command
  source" resolution KF-009 cause 5 warned about is not in play). **Action
  for the harness owner: `safe_regrab` needs a survival-safe variant (e.g.
  look straight up without teleporting, verified clear first) before this
  tool is used for any future non-creative playtest.** Evidence:
  `qa/reports/artifacts/live/20260826T054216Z/logs` (server log lines
  above), `shots/scan-1.png` (first kick), `shots/rejoin-1.png` (second
  kick), `shots/status.png` (death screen), `shots/after-respawn.png`
  (clean respawn, 0 items lost).

- `05:55` Founding — **WALL, most severe finding of the session (harness,
  pre-existing, not a hearthstead defect)** — after dying twice more to a
  dense slime swarm right at spawn (`Dev was slain by Slime` ×2 more,
  `logs/latest.log` 05:49-05:51; no items lost, inventory was still empty),
  I set difficulty back to peaceful (session-setup exception) purely to
  heal and scout safely, then sprinted ~350 blocks in one direction and
  checked with the client's own F3 debug overlay (not a server command —
  every real player has this): biome stayed `minecraft:plains` the entire
  way. `scmd locate biome minecraft:forest` (observation-category command,
  no teleport, no materials granted — informational only, same as reading
  the F3 overlay) answered **"Could not find a biome of type
  \"minecraft:forest\" within reasonable distance."** Root cause, confirmed
  by reading the harness itself:
  `qa/scripts/server_instance.sh` hard-codes
  `server.properties`: `level-type=minecraft\:flat` with
  `generator-settings={}` for every `live`/`playtest` instance — vanilla's
  *default superflat preset* (bedrock + 2 dirt + grass, one biome,
  **decoration disabled — no trees, no ore veins, no stone exposed
  anywhere, no structures**) for every world this harness has ever booted.
  **This means no prior playtest through this harness could ever have
  gathered a single log, cobblestone or iron ingot by hand** — every
  earlier "playtest"/"live" session that reached the plaque/hearth flow
  must have used `/hearthstead demo`, creative mode, or `/give`, because
  the world itself makes hand-gathering structurally impossible: hearth
  needs 3 logs + 5 cobblestone (neither obtainable — no trees, and mining
  the ground yields only dirt down to bedrock), the plaque needs iron+
  copper ingots (no ore anywhere), and even a crafting table needs planks
  from logs that do not exist. This is the single biggest finding of the
  night: **the QA harness itself has never been capable of hosting a real
  survival playthrough**, independent of anything in the mod's own code.
  **Remediation taken (categorized as permitted "session setup — world",
  identical to a real player picking "Default" instead of "Superflat" when
  creating a new world; no material, plaque, or building was granted):**
  edited this session's own copy of `qa/scripts/server_instance.sh`
  (`level-type=minecraft:flat` → `minecraft:normal`) and restarted the
  live session so the rest of this playthrough runs on a real generated
  overworld with trees, stone and ore. **This edit is local to the
  scratchpad verify-tree used only for tonight's play session — it is not
  a committed repository change** and is flagged here for whoever owns the
  harness: `qa/scripts/server_instance.sh` needs a real decision (a
  survival-capable default, or a documented `HSQA_LEVEL_TYPE` override) so
  the next person who tries an honest survival playtest doesn't lose the
  same time rediscovering this. Evidence: `server.properties` excerpt
  above, `qa/reports/artifacts/live/20260826T054216Z/shots/f3-1.png` /
  `f3-2.png` (F3 overlay, `Biome: minecraft:plains` at two points ~350
  blocks apart), server log lines for the `locate biome` result.

---

## Findings (ranked) — filled in as the session concludes

## Audit predictions confirmed/refuted — filled in as the session concludes

## Take list — filled in as the session concludes
