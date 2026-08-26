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

---

## Findings (ranked) — filled in as the session concludes

## Audit predictions confirmed/refuted — filled in as the session concludes

## Take list — filled in as the session concludes
