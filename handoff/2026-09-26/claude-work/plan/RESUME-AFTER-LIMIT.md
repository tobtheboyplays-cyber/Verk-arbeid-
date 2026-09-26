# Resume checklist after a usage-limit stop (written 26 Sep ~02:12)

Goal: everything working smoothly for the Sunday friend test (freeze Sunday morning; unstable features ship anyway, with [features] kill-switches).

On resume:
1. `get_usage`, then `scratchpad\load.ps1`.
2. Resume each lane that stopped mid-work with SendMessage: "Usage limit hit; continue where you left off, re-read files before editing, private compile first."
3. Check `plan/INTEGRATION-LOG.md`, `plan/SUNDAY-GATE.md`, `plan/BUGHUNT-LOG.md`, and COORD/to-claude.md (Codex).
4. Restart the Codex watcher (the to-claude.md size loop).

Lanes (agent ids):
| Lane | Id |
|---|---|
| Integration captain (green build, GameTests, gate) | ace73d42bf01cec8d |
| Bug hunter | a8450891fcfc4a7a2 |
| Soak round 2 (miner stairs, archer default post, axes, tick-crash sweep, hunter) | ab8f4ef2bb37b0168 |
| Engine / motion | a22dc62297e3f687e |
| Map / Banner screen (heads LOD walk, outline, Legend→?, drop Elmfield plate, Summon button, showcase clip) | acd0a5a18e727f5b9 |
| Balance (enemy HP longer fights, config) | a3dec6ca7db9a97ec |
| Animation audit | ab37ea5dab0102132 |
| Command (per-role keys, ground dots, salute, Summon for all settlers + outline) | a77dcc6059299345d |
| Finisher | a0e9c16ae13e8d541 |
| Gear / armor | ac36696d6b537eb95 |
| Warehouse | a053cd97fe5e2bfab |
| Battle roles | ab2beb0f1e2d547df |
| Builder (core → defense tab → missing-materials UI → scan tool, resource scroll, work orders, decorations, styles, deconstruct) | a1f86e34f18da84e3 |
| Blueprints (town palette, 2 variants per building, Another Furniture optional) | abe4f8c266eb4b115 |
| Settler UI + job icons | a0035f4128be96961 |
| World events (7 approved + brute toll; uses the conversation API) | a29c68ccbeac8219f |
| Tavern animations + seating (Another Furniture; Codex tavern patch) | a3f6c811019e78601 |
| Conversation system (Bannerlord + Shadow of War pull-in, line of sight) | a86e7e73b8c0da88f |
| Mod compat (atmosphere / building / content packs, test env only) | aa9a616be11ba76bd |

Codex: T7 conversation refs active; T3b builder exact-once review when builder core compiles.
| Stress tests (S1–S7, stress/STRESS-REPORT.md) | a8eb054ce3b267818 |
| Economy (every settler works, coins/food curves, events rhythm, plan/ECONOMY.md) | a60cce1309921f3b3 |

(Budget reserve cancelled by the owner at 10:45; anim, bughunt and tavern resumed.)

Owner goals (26 Sep 02:25, before sleeping): every settler works, the economy makes sense, events come when they should, the whole thing feels smooth. Stress tests must prove it (stress/STRESS-REPORT.md). Report to the owner with short Norwegian summaries plus video.
| Super QA (every button + every job, plan/QA-BUTTONS.md + QA-JOBS.md) | ab999f83bf4009f8b |
| Animation overkill (combat fixes, 13 role clips, talk and event clips, variants and fidgets) | aa945c9f20dced2d9 |

## 26 Sep 06:02: WEEKLY BUDGET THROTTLE
Weekly usage is at 61% (it was 50% at 05:10: about 11% per 50 min with 11 lanes). To keep reserve for the Sunday test, only 4 lanes run: captain (W1 GameTests), bug hunter, builder (Codex scaffold fixes), map (fj_230 journey fix). Paused, each with a state note in plan/state/<lane>.md: anim overkill, economy, conversation (film pending), settler UI (film pending), soak round 2, finisher (film pending), balance (guards-not-engaging investigation!), super QA (audit B). Resume them one or two at a time, by priority: balance (guard engage), then settler UI/finisher/conversation films after W1, then QA-B, economy, anim.
| Trades unlock (15 locked professions → learnable nodes + emblems, [features] extendedTrades) | aa9e5fcab2280a521 |
| GameTest fixer (W3a failures: finisher, barter, field orders, summon, builder palisade/2-storey, miner_pit) | af3aff63f9cbf2e77 |

## 26 Sep 07:47 budget plan
Weekly usage was 71% at 07:47 (about 6% per hour with 3–4 lanes). At about 85%, pause every lane except the captain (for the soak/MSPT and the Sunday-morning freeze candidate), and keep about 10% for fixes during the Sunday test.
Films pending: finisher take 2 (/hsfinisher reel, WSL queue 4b), settler sheet, conversation, map summon-line, builder house. Send them to the owner as they land.
| Goods quality (crafted gear tiers, stats, merchant price, plan/QUALITY.md) | a5c747dc68a8ab1f7 |

## 26 Sep 10:45 owner: NO budget reserve
"Nei ikke hold av noe." Use the full budget. Priority: verify from a SURVIVAL player's perspective that everything works with no bugs. Full access to progress. A survival playthrough QA lane has been spawned.
| Survival playthrough QA (full survival progression, qa-survival/) | af80008a1c649caf4 |
| Living village (ambient life) | a14c5d2829b368127 |
| Scenario coverage (every path as GameTests, plan/COVERAGE.md) | af2c74acb4c0f768e |
| Patrol routes (routes, group patrol, map) | aec71675fd75856ab |
| Handbook rework (visual, chapters.json, images, key chips) | a3a38436433cbc8cc |
| Tech tree v3 framework (data-driven, EffectRegistry, screen, IMPLEMENTATION.md, BRANCH-BRIEFS.md) | aeb275ee519bdaebd |
NEXT: when it reports FRAMEWORK READY, spawn 5 branch lanes (watch, commons, logistics, craft, crown) from plan/techtree/BRANCH-BRIEFS.md.
| Attributes rework (every attribute useful, plan/ATTRIBUTES.md) | a89a1e6fc08a88993 |
| Particles & juice (fx_, videos/fx) | a58e613ad44dfac40 |
| Premium sound (ElevenLabs, sound-gen/, key in secrets/elevenlabs.key) | abfd31f9fe2f83e6e |
| UI consistency (all screens to Banner kit) | a599a90841fa9241a |
## 26 Sep ~11:45 tech tree FRAMEWORK READY; branch lanes spawned (Crown already done)
| techtree-watch (23 nodes) | a4bc51ff0b8494bb8 |
| techtree-logistics (18 nodes) | a7773744b578ff0d4 |
| techtree-craft (17 nodes) | a54e12a4682572107 |
| techtree-commons (21 nodes) | a1d9636e2d3c7dad1 |
