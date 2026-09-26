# Bannerhold survival playthrough (QA lane, 26 Sep 2026)

Setup: brand-new SURVIVAL world, Normal difficulty, allow commands ON (for the logged time fast-forwards only).
Every mod config is freshly generated (defaults, all [features] on). The game dir is `/root/hssurv-game` (WSL) and the world is `Survival-QA-1`.
Build: a frozen WSL snapshot of the shared tree (`/root/hssurv-src`, commit noted per session), built into `/root/hssurv-build`.
Client: Xvfb :80, 1280x720, player "Tobi", integrated server (single JVM).
Screenshots: `qa-survival/shots/`, clips: `qa-survival/clips/`.

## Command log (every non-survival action and why)
The full raw log, with every typed command and a `#` reason line before each batch, is in `qa-survival/cmdlog.txt`. Categories:
- SIM GATHER: raw materials in realistic amounts (logs, cobblestone, coal, flint, copper/iron ingots as if smelted, paper, feathers). No mod items, Coins or research were ever given.
- SIM CRAFT: vanilla crafts only (a stone sword).
- TRAVEL: tp instead of walking.
- QA AID: slowness on one NPC so the scripted camera can aim; camera-only `tp ~ ~ ~ facing`.
- WORKAROUND: `/hsevent stop` after bug #3 killed 2 founders.
- TERRAFORM: a 21x21 pad, which caused a tester error (see below).


## Branch coverage (run 1 / run 2)
| Fork | Run 1 | Run 2 |
|---|---|---|
| Brute toll answer (world 1, day 0) | Persuade (failed at 38%), then fought and lost | - |
| Founding terrain | World 1: forest hillside by water (merchant stuck, bug #5). World 2: plains terraces, then a levelled pad | small flat empty area: pending |
| First tech node | World 1: Lumber Camp. World 2: Houses & Lodging first (by mistake, UX U8), then Lumber Camp | - |
| Mayor | World 2: Mayor died (tester error), so Garrick was appointed via the Settlers tab -> Change -> Mayor's Seat | - |

## Timeline

### Session 1 (12:43-13:30). Snapshot 12:43, rebuilt at 13:11 with the events-lane hostile-grace fix.

**World 1 "Survival-QA-1" (forest hillside by water, settlement "Heathergate")**
- 12:45 World created (Survival, Normal, commands ON). The handbook arrived in slot 1 with the correct chat hint (PASS). The config was generated fresh (`config-used-hearthstead-server.toml`: all [features] on, soloDowned=false, dayLengthMultiplier=2.0).
- 12:46 Handbook stills for the owner (videos/ui/handbook/ingame/). PASS; small nits went to the handbook lane (already fixed).
- 12:47 The recipe book shows the Settlement Banner once you hold logs. Crafted planks, table, campfire and Banner through the recipe book: PASS.
- 12:48 The first placement under a tree canopy was refused with a clear message ("needs two free blocks above its stand"): PASS. The second placement founded "Heathergate". 4 founders plus the Mayor walked in, and "A Traveling Merchant is approaching" appeared at once: PASS. The founders spawn on top of the player (U5).
- 12:50 Banner screen: Overview map, Journey ("Founding Journey 3/56", the next step is clear), and the Journey "?" opens the right handbook page: PASS.
- 12:51-12:57 The merchant got stuck in a hillside notch 25 blocks away (bug #5). I died from suffocation after a bad tp (tester error), and death/respawn/item pickup all worked. Sold 32 logs for 4 Coins in the custom merchant UI: PASS (the purse dropped 12->8, the row locked at 4, and Fine logs are refused for Basic).
- 12:58 Learned Lumber Camp (1 Coin, 8 logs, 8 cobblestone): PASS; the recipe toast appeared.
- 12:58 **A brute toll on day 0** (bug #3): 3 brutes with 70 HP each. The conversation UI (name card, pull-in camera, options with locks and costs) works well visually, but a new town can't pay. Persuasion failed, and founders Yrsa and Liv plus the player were killed. Routed, and **fixed by the events lane within 15 min**.
- 13:00 The client was killed by someone else's `gradlew --stop`; the world saved cleanly and reloaded mid-event. The launcher now bypasses Gradle.

**World 2 "Survival-QA-2" (plains terraces, settlement "Millbrook"), built with the hostile-grace fix**
- 13:16 Founded. `WORLD_EVENT_PLAN day=0 event=none`: **the fix is verified on day 0**. The merchant arrived at the Banner in 8 s (logged ARRIVED twice).
- 13:17 Label check (names only, no roles yet), `shots/labels/`: the names read at vanilla nametag size from 1 to 8 m, straight and angled. Roles are to be re-checked once jobs exist.
- 13:18 Sold logs for 4 Coins: PASS.
- 13:22 Clicking RESEARCH learned **Houses & Lodging** instead of Lumber Camp (U8), then Lumber Camp: PASS. Learning a node unlocks the plan recipes in the recipe book (it doesn't give the plan item).
- 13:26 Banner -> Settlers / Buildings / Storage tabs: PASS. Summon to me works ("On the way to y...").
- 13:27 Tester error: my terraform /fill suffocated Mayor Eira, so this became the "Mayor died" edge case. The Settlers tab shows "No mayor - Change"; the Mayor's Seat lists candidates with attributes; I appointed Garrick: PASS. There's no warning on the Overview that the Mayor is missing (U11).
- 13:29 Mayor's Job Emblems shop: bought a Lumberer emblem for 1 Coin + 2 flint: PASS ("Emblem purchase confirmed"). Holding the emblem shows the hint toast "New in your Handbook - Press [Y]", and Y opens the Job Emblems page: PASS. Hiring with no Lumber Camp gives a clear refusal: "No active workplace can employ a Lumberer yet - build and register one first": PASS.
- 13:29 "A goblin thief has been spotted near your stores!" on day 1, about 13 min after founding. Still to verify whether it steals the new player's first Coins (bug #7, OPEN).
- 13:30 Save & quit, lock released. **Next: plaque + Lumber Camp room + Work Scepter zone + the Lumberer working (the rest of the first 30 min).**
