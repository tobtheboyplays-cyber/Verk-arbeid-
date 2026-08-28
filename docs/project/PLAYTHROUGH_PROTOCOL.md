# PLAYTHROUGH PROTOCOL — the whole loop, in survival, on camera

*For SPILLER-1. The owner's order (2026-08-26 07:05Z): "Kjør en full gameplay
test. Spill igjennom alt. Få en lumberjack. altså uten creative. så jobb deg
opp prøv å research og jobb deg til de andre bygningene. Dette skal jo tross
alt være en survival opplevelse." Then: "Test hele loopen." That message is
the playtest authorization the standing rule requires.*

## The one rule
**Survival means survival.** No creative mode, no `/give`, no `/setblock`
for anything the player is supposed to build, no teleport past a distance
that matters. Server commands are allowed ONLY for: session setup (world,
spawn, time-of-day for filming legibility, gamemode survival), observation
(querying state), and camera work. If progress stops without cheating,
**stopping is the finding** — record the wall, do not climb over it.

Distinguish honestly in the log between:
- **WALL** — cannot proceed at all without cheating.
- **FRICTION** — proceeded, but it was confusing, slow, or felt broken.
- **DELIGHT** — worth protecting in the patch round; name it too, or the
  patch round optimizes it away.

## Driving (qa/scripts/live.sh, display :99)
- `live.sh start` boots the client under Xvfb, 1280x720, guiScale 3.
- Movement: `hold w <sec>`; mining: `hold`-based left-click via xdotool
  (`keydown`/mouse down — check `click` and extend the driving with raw
  `xdotool mousedown 1; sleep N; xdotool mouseup 1` after `focus`).
- Crafting happens in REAL GUIs: at fixed 1280x720/guiScale 3, slot
  coordinates are deterministic. Compute them once, verify with a `shot`
  before the first click, and screenshot after every craft to prove the
  result. Never assume a click landed — look.
- `scmd` for server-side observation; `film <sec> <fps>` for takes;
  `shot` liberally — evidence is cheap, memory is not.
- The session dies if anything else grabs the display: run only while the
  machine is otherwise quiet (SHOWCASE_PLAN records the :99 collision).

## The loop to play, in order
1. **Founding** — obtain the hearth honestly (craft it per its recipe from
   hand-gathered materials), place it, found the settlement.
2. **First settlers** — however the game actually delivers them; record how
   long it takes and whether the player understands what is happening.
3. **The lumberjack** — the owner's named milestone: lumber camp Build Plan
   crafted in survival, plaque hung, room approved, settler hired, logs
   actually flowing without player labour.
4. **Food** — farmhouse or hand-farming; watch the hearth larder; record
   the first moment settlers eat something the player did not hand-feed.
5. **The climb** — warehouse (courier!), mill→bakery (follow the bread),
   smelter→smithy (tools), tannery, armoury (can you hire the new
   armourer?), each through the real plaque flow.
6. **Research** — study built, scholar hired, a project started with real
   goods, and whether its effect is FELT (BALANCE_AUDIT says four of six
   are inert — confirm in play).
7. **The raid** — survive one, or lose to one; either is data. Watch
   whether the guard ladder and armoury chain matter.
8. **Free play** — 20 minutes of just watching the village; note what
   breaks the illusion.

At each step consult `docs/project/SURVIVAL_AUDIT.md` (in flight): it
predicts the walls. Confirming or refuting its predictions is worth as much
as new finds.

## Evidence
Every WALL/FRICTION/DELIGHT gets: timestamp, screenshot or clip, what was
expected, what happened, and (for walls) the exact missing link. Output:
`docs/project/PLAYTHROUGH_REPORT.md`, findings ranked, film takes listed
with their labels. The patch round is built directly from that report —
write it so a fix-worker can act on a finding without asking questions.
