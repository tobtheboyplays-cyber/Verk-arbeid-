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

- `06:42`-`07:04` Founding — FRICTION (driving, not a mod bug) — hand-gathering
  the first 6 oak logs from a real forest, then crafting table→sticks→
  wooden pickaxe, took roughly 20 real minutes and ~90 driving commands,
  almost all of it fighting the harness/input layer rather than the game:
  (1) `xdotool mousemove_relative` pitch accumulates silent error across many
  calls — after a string of `look up`/`look down` adjustments with no
  absolute reference, the crosshair drifted ~35-45° off from where the
  screenshot visually suggested, so repeated "aim at the obvious trunk in
  frame" attempts silently mined dirt/leaves instead. Fix that worked: read
  F3's `Facing (yaw/PITCH)` and `Targeted Block` line before every
  swing once things feel off, don't trust the screenshot's apparent
  crosshair alignment alone. (2) GUI clicks (crafting table slots) failed
  silently and often *without any visible error* — a right-click that
  should place an item into a grid cell would sometimes do nothing, other
  times it worked; no pattern found beyond "verify with a screenshot after
  every single click, never chain assumptions." (3) Standing inside a
  1-wide dug trench, `hold w`/`keydown w` sequences frequently did nothing
  for several consecutive attempts (same key, same target, no terrain
  change) then suddenly worked — never resolved to a root cause; treat
  movement as **unconfirmed until a position readout proves it**, the same
  discipline as clicks. **Calibrated, working GUI slot coordinates for this
  exact 1280x720/guiScale 3 session** (useful for round 2+): personal 2x2
  crafting grid cells (692,180) (746,180) (692,233) (746,233), output
  (860,218); 3x3 table grid cells (490,180) (544,180) (598,180) / (490,234)
  (544,234) (598,234) / (490,288) (544,288) (598,288), output (770,238);
  hotbar/inventory row slots run along y≈547 (hotbar-in-panel) and the 3
  main-inventory rows above it at y≈387/441/495. None of this is a
  hearthstead defect — it is entirely vanilla UI plus this harness's input
  path — but it is the single largest real-time cost of the session so far
  and worth the coordinator's attention for round 2 (a more reliable
  click/key delivery primitive would pay for itself many times over).
  Net result, chest-truth confirmed throughout (nothing lost, only
  temporarily misplaced across inventory slots during fumbled clicks): 3
  oak logs (reserved for the hearth), 1 wooden pickaxe, 2 oak planks, 2
  sticks, 1 oak sapling, 1 oak button (byproduct, harmless), 2 dirt.
  Evidence: `qa/reports/artifacts/live/20260826T055725Z/shots/` (dozens of
  `f3-*`, `aim-*`, `craft-*`, `pickaxe-attempt.png` showing the confirmed
  wooden-pickaxe recipe match).

- `07:04`-`07:27` Founding — **WALL (harness), the dominant finding of this
  round** — after the wooden pickaxe, gathering the hearth's 5 cobblestone
  exposed a much more severe version of the same input-reliability problem:
  as real playtime accumulated, `mousedown`/`mouseup` and `keydown w`
  sequences increasingly did **nothing at all** — no camera change, no
  position change, no pickaxe-durability change, i.e. the input was not
  merely mis-aimed (as in the earlier plank/pickaxe friction), it was not
  **received** by the client at all for stretches of several consecutive
  commands. The only reliable un-stick move found: a bare `mousemove 640
  360` + `click 1` (re-establishing GLFW's grab) immediately followed by a
  `mousemove_relative` to prove the look now responds, before trying
  mine/move again — and even that stopped being reliable by the end of the
  round (documented below). **This reads as progressive degradation over
  session length, not a one-off** — the first ~30 minutes of the session
  (founding scouting, log gathering) had occasional stalls fixable by one
  regrab click; the last ~10 minutes (stone mining) needed a regrab before
  nearly every single action, and by the final attempts even regrab
  stopped restoring input (see the last few `data get entity Dev
  Inventory` calls in the log, all byte-identical across 6-8 full
  mine+move attempts with zero durability change). No exception or warning
  appears in `logs/live-client.log` at these moments — input is silently
  dropped from this session's point of view, which makes it invisible to
  anything watching only the server or the mod's own logs. **This is
  categorically different from KF-006/KF-009's known creative-mode
  gotchas** (those are documented, deterministic, single-cause quirks with
  fixes; this is an open-ended reliability decay across a long
  session that nothing in the existing KF ledger describes.) Suspect
  surface for round 2 investigation: X11/XTest event queue backpressure
  under this environment's sustained low frame rate (6-27 fps observed,
  correlating loosely with when input got worst), or GLFW's raw-input
  grab silently dropping under repeated focus/unfocus cycles from the
  `shot`/`scmd` helpers interleaved between game actions. **Progress made
  despite the wall, by working around it with patient regrab-and-retry**:
  3 raw oak logs (reserved for the hearth's `tag:minecraft:logs`
  ingredient), 15 dirt (byproduct, no use yet), 3/5 cobblestone toward the
  hearth's `5 cobblestone` requirement, 1 wooden pickaxe (25→38 durability
  used), 2 oak planks + 2 sticks + 1 oak button in reserve. The **Stone
  Age** vanilla advancement fired, confirming the mining chain (log→
  planks→stick→pickaxe→cobblestone) is fully intact end-to-end in this
  mod's world — nothing about the recipes or the room/plaque system was
  ever in question here; every failure this round was the driving layer,
  not the game. Evidence: `qa/reports/artifacts/live/20260826T055725Z/
  shots/on-stone-floor.png` (Stone Age advancement toast),
  `shots/cobble-check2.png`, `shots/stone-stuck-check.png` (uncollected
  drops visible, proving breaks succeeded even when pickup/movement
  stalled immediately after), and the full `data get entity Dev
  Inventory` transcript in `logs/live-server-latest.log` once `live.sh
  stop` is run.
- **Session left running for continuity** (tmux `hsqa-live`, port 25574;
  `qa/scripts/live.sh status`/`shot`/`scmd` all still reachable), per the
  coordinator's note that round 2 continues from here rather than
  restarting. Current player state: gamemode survival, difficulty normal,
  standing inside the small hand-dug stone chamber described above, with
  the inventory listed just above. **Immediate next step for whoever
  continues this round:** get the remaining 2 cobblestone (identical
  technique: regrab, point-blank aim confirmed by screenshot, short
  mine-hold, step forward, re-check inventory after every single attempt
  rather than batching several blind ones — batching is exactly what hid
  how bad the input drops had gotten here), craft the hearth (`hearth.json`:
  `LLL / SCS / SSS` = 3 logs top row + 5 cobblestone sides/bottom + 1
  campfire centre — a campfire still needs to be crafted first, itself
  needing coal/charcoal not yet gathered this round), place it, and found
  the settlement.

---

## Findings (ranked) — filled in as the session concludes

## Audit predictions confirmed/refuted — filled in as the session concludes

## Take list — filled in as the session concludes
