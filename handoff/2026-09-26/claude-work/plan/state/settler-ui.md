# SETTLER UI lane: state note (paused 06:03, 26 Sep)

## Done. Compiles on the full tree (private snapshot copy, 05:21). JUnit 13/13 green.
- `client/screen/SettlerScreen.java`: rewritten in the Banner/ui2 style (CRLF). All previous actions and packets are kept.
  - Chrome: walnut board, a burgundy crest with the job icon, serif name, and a subtitle of role plus rank (Man-at-Arms).
  - Header counters: Health, and Level or Tier.
  - Left column: the live 3D settler (slow sway plus drag to turn), and needs bars (Health with the well-fed plus-mark, Hunger with a 90 tick, Energy, Morale).
  - Right column, tabs:
    - Overview: Right now (map status word and activity, next action or blocker, Builder site headline); Summon, Locate and Mark-on-map buttons; Work (Open plaque, Work zone, Requests or Guard Orders); Home; Standing (mayor, combat, blessings); refusal; footer with Appoint and Dismiss.
    - Skills: trade level, XP and bonuses; eight attributes; job focus; traits.
    - Gear: worn slots with Gear Tier lock marks and tooltips; the T0-T4 ladder and next unlock; bag with locks; wanted item; footer Inventory button.
  - Layout 304x224 up to 640x360; the page scrolls when content is taller.
- `client/ui2/JobIcons.java` (new; the map lane approved the location), `tools/ui/job_icons.py`, and 33 PNGs in `textures/gui/job/`, including builder and the battle roles.
- `network/SettlerSnapshotPayload.java`: new `homeBuildingId` and `hasBed` fields, plus a source-compatible 25-arg constructor. `network/SettlerNetwork.java` fills them.
- Tests: `SettlerScreenLayoutTest` rewritten; `SettlerScreenRenderCacheTest` now uses the "draw: begin/end" markers.
- `en_us.json`: `hearthstead.settler.sheet.*` keys; `hearthstead.rank.spearman` -> "Man-at-Arms" (the battle-roles lane asked for this); `hearthstead.emblem_shop.cant_afford`.
- `EmblemShopScreen.java`: unaffordable now reads "NEED GOODS" instead of LOCKED (QA). The "no refresh on inventory change" issue is NOT fixed.
- Summon uses `client/command/SummonClient` (command lane). Map uses `HearthScreen.requestMapFocus(UUID)` (map lane).
- NOTE: the Battle roles lane has since edited SettlerScreen (Guard Orders for battle roles). Re-read the file before the next edit.

## Open
1. FILM (queue slot 1b in WSL-QUEUE.md, after W1). Rig: `videos/ui/settler-sheet/rig/`:
   - Steps: `setup.sh` (GUI=2 for the main stills), `push.sh`, `run.sh` (in its own wsl session), `waitjoin.sh`, then the LAN-cheats clicks from `map-rig/stage.sh`, then `bring.sh <profId>`.
   - Build first: refresh the `settlerui-work/hearthstead-neoforge` copy from the shared tree (it has a .git for build identity), then `gradlew classes -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-agent-settlerui`.
   - Stills go to `videos/ui/settler-sheet/`:
     - worker: Profession 2
     - guard: Profession 3; `/data modify entity <sel> Attributes.Values[0] set value 27` gives Man-at-Arms
     - early-game settler: Profession 0
     - plus a ~20 s clip that presses Summon
   - Tab click positions must be read from a first screenshot.
2. QA items from Super-QA (ab999f83bf4009f8b), rows in `plan/QA-BUTTONS.md`:
   - Q-022: disable Locate and Summon for unbound travelers (`!settler.isBound()`), with a "not part of your settlement" tooltip.
   - Q-023: `canManage` in `SettlerNetwork` should include `player.mayBuild()`.
   - Q-024: disabled buttons should show the reason instead of the action tooltip. Requests needs a tooltip and should be disabled when the courier is unbound.
   - Q-026: the action rows need a width check. Wrap to a second row when x + w exceeds the column.
   - Q-006: `PlaqueScreen` disabled tooltips. That file is not mine; ask its owner first.
   - Info: add a "Back to sheet" route from the inventory.
3. The QA issue "status world-labels huge up close" is in `SettlerRenderer.renderNameTag`, owned by the animation lane. Not touched.

## New rule (captain, 06:0x)
Every Gradle call must pass `--no-daemon` or `-Dorg.gradle.daemon.idletimeout=300000`. Don't leave background builds running. Wait whenever RAM is over 90%. The captain sends "go" for slot 1b when W1 ends.

## 07:12 update
- Film slot 1 (07:01-07:10), GUI 2, 1280x720:
  - The sheet rendered in game on farmer Ansgar's Overview (crest, header counters, portrait, needs, Right now/Work/Home, footer).
  - Issues found: portrait too large. Fixed (scale 0.33*H, no tilt). "Mark on map" was wrongly disabled; fixed. Overview had empty space; I added a "Carrying" row.
  - That screenshot was later overwritten. No final stills yet.
- Staging lesson: `tp`-ing settlers to ^ ^ ^2.x suffocated them (Ansgar, Wilmot, Dunstan died in the disposable world). Summoned settlers are discarded. Next time:
  - `/gamemode creative`, then `/effect give <sel> slowness 300 255`, then `/execute at <sel> run tp @s ^ ^ ^2.5 facing entity <sel> eyes` (move the PLAYER, not the settler), then empty hand (key 2) and `use`.
  - Chat typing sometimes leaks: wait 0.5 s after Escape, and never double-Escape.
  - Tabs at GUI 2: Skills (519,123), Gear (600,123).
  - The world has 1 farmer, 1 lumberer, 1 courier, 1 mayor near the banner. No loaded guard was found, so the guard still needs a plan (set a courier or similar with `/data modify ... Profession 3b` plus Strength 27, and disclose it).
- QA fixes done:
  - Q-022: Locate and Summon disabled with the reason when `!canManage`.
  - Q-023: `canManage` now includes `mayBuild`.
  - Q-024: disabled buttons show the reason; Requests has a tooltip and needs manage rights.
  - Q-026: row buttons are clamped to the column.
  - Compile green, JUnit 13/13 at 07:12.
- Not mine: Q-006 (`PlaqueScreen`), the inventory "Back to sheet" button (`SettlerInventoryScreen`), and the world label size (`SettlerRenderer`).

## 08:48: film done, gate item done
- Stills in videos/ui/settler-sheet/shots/: worker-{overview,skills,gear}, guard-{overview,skills,gear}, early-{overview,skills}. Clip: videos/ui/settler-sheet/settler-sheet-clip.mp4 (22 s). The guard (Wilmot) and early settler (Dunstan) were staged with /data.
- After the film:
  - Fixed: empty Level counter box for unassigned or Mayor settlers.
  - World-label gate: `SettlerRenderer.nearLabelScale` (cap within 5 blocks, floor 0.45), applied in renderNameTag and SettlerThoughtBubble. New SettlerLabelScaleTest.
- JUnit 15/15 green, full tree compiles (08:47). Neither change has been seen in game yet.

## 13:42: health counter (owner feedback, three revisions: contextual bar, then in-world counter, then HUD plate)
- Final design is a HUD plate above the hotbar, shown when you look at an entity (16 blocks), lingering 1.5 s, or 3 s after you hit it, with a 0.2 s fade.
  - Content: name (plus a job icon for settlers), a 9x9 heart, "HP/max", and the gold dagger while `FinisherClient.isFinishable`.
  - Placement: above the action-bar line via `Gui.leftHeight/rightHeight`; compact (heart line only) on short crowded screens.
  - Hidden with F1, in spectator, behind screens and while downed.
- Files:
  - NEW: `client/render/HealthCounter.java`, `client/render/HealthCounterRules.java`, `test/.../client/render/HealthCounterRulesTest.java`.
  - EDIT: `HearthsteadClientConfig` gains `[hud] healthCounter = lookAt | off`.
  - DELETED: `client/EnemyHealthBars.java`.
- Mocks in `videos/ui/health-bars/` (`hud-*.png` is the final; `counter-*` and `1-5*` are earlier revisions).
- JUnit green (25 incl. SettlerScreen). Full tree compiles 13:40. NOT seen in game yet; needs a WSL slot.
- Open: a handbook options entry for healthCounter (asked main).
