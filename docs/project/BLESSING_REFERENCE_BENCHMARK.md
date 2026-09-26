# Blessing reference benchmark — TekTopia, MineColonies, Hearthstead

**Research snapshot:** 2026-08-27  
**Decision in scope:** the raid reward → Blessing choice → physical seal →
permanent settler/building binding loop.  
**Why this exists:** "premium" is not an implementation requirement. The gates
below turn it into observable behaviour and measurements.

This is a reference benchmark, not permission to imitate either mod. TekTopia
is closed source and distributed as **All Rights Reserved**; only its official
wiki and official CurseForge page were inspected. MineColonies' public source
was inspected at commit [`2d45333`](https://github.com/ldtteam/minecolonies/tree/2d45333576141d88635a7c2f71cedae3812e2f98),
but no source code or assets are to be copied into Hearthstead.

## Executive conclusion

Hearthstead should combine three proven strengths without inheriting their
costs:

1. **TekTopia's physical clarity:** hold a meaningful item, point at a world
   target, act, and see an immediate result. Its profession tokens, glowing
   structure markers, and above-head need icons make causes visible without
   forcing the player into an administration screen.
2. **MineColonies' information depth:** detailed citizen state, searchable
   lists, clear health/happiness/saturation signals, and a UI system capable of
   structured, scrollable information.
3. **Hearthstead's own identity:** a dangerous raid earns a deliberate choice;
   that choice becomes a physical seal; the mayor permanently binds it to one
   settler or one player-built plaque. The settlement is shaped in the world,
   not primarily in menus.

The honest current verdict is **not yet benchmark-passing**. The Blessing slice
is under construction and does not count as better than either reference until
the visual, runtime, multiplayer, persistence, and scale gates in this document
all have stored evidence.

## Evidence-backed reference findings

### TekTopia — the benchmark for diegetic intent

- A purchased profession token is held in the player's hand and applied by
  right-clicking a nomad or villager. This is an exceptionally legible
  item → target interaction with almost no menu friction.
- A structure marker is a physical item placed in an item frame by the door;
  it glows once the structure is valid. The player sees both the control point
  and its status in the world.
- Above-head thought icons identify concrete unmet needs such as a missing
  tool, food, bed, or workplace. The player does not have to infer why a worker
  stopped.
- The official page presents visible, profession-specific work as a core
  promise and advertises settlements of 100+ villagers. Because the code is
  closed and the last official release targets Minecraft 1.12.2 (2019), that
  marketing claim is **not** treated as a current, reproducible performance
  measurement.

Sources: [Getting Started](https://sites.google.com/view/tektopia/home/getting-started),
[Thought Icons](https://sites.google.com/view/tektopia/home/mechanics/thought-icons),
[Profession AI](https://sites.google.com/view/tektopia/home/mechanics/profession-ai),
[Architect](https://sites.google.com/view/tektopia/home/villagers/architect),
[official CurseForge listing](https://www.curseforge.com/minecraft/mc-mods/tektopia).

### MineColonies — the benchmark for information architecture

- The Town Hall citizen view exposes health, happiness, saturation, jobs,
  search, recall, colony statistics, permissions, and other administrative
  state. The citizen screen separates information into tabs and uses a
  scrollable skill region.
- BlockUI separates XML layout from backing behaviour and supports images,
  buttons, text input, scrolling, and dragging. That makes MineColonies' UI
  broad and maintainable, although breadth also makes it more menu-heavy than
  the intended Hearthstead experience.
- Its current source uses held items, swing events, and targeted particle
  messages to make work actions visible. This is a good baseline for readable
  feedback, not evidence that every action has a bespoke, weighty animation.
- Its official configuration explicitly exposes AI update throttling,
  pathfinding-node cost, and bounded request retry/delay controls. Those
  controls are evidence that citizen simulation, navigation, and retries are
  material scale costs that need designed budgets.
- Public issue reports show that performance and navigation failures have
  occurred at colony scale. An old 1.12.2 report describes severe load around
  30 citizens; a 2025 report describes stutter in a developed colony; another
  2025 report describes citizens attempting invalid paths. These are primary
  user reports, **not proof that the current release still has those exact
  defects**. They are risk cases Hearthstead should reproduce in its own tests.

Sources: [Town Hall](https://minecolonies.com/wiki/buildings/townhall/),
[BlockUI overview](https://minecolonies.com/wiki/dependencies/blockui/),
[official MineColonies repository](https://github.com/ldtteam/minecolonies),
[citizen main layout at inspected commit](https://github.com/ldtteam/minecolonies/blob/2d45333576141d88635a7c2f71cedae3812e2f98/src/main/resources/assets/minecolonies/gui/citizen/main.xml),
[Town Hall citizen layout](https://github.com/ldtteam/minecolonies/blob/2d45333576141d88635a7c2f71cedae3812e2f98/src/main/resources/assets/minecolonies/gui/townhall/layoutcitizens.xml),
[official config copy](https://github.com/ldtteam/minecolonies/blob/2d45333576141d88635a7c2f71cedae3812e2f98/src/main/resources/assets/minecolonies/lang/manual_en_us.json),
[30-citizen report #4121](https://github.com/ldtteam/minecolonies/issues/4121),
[2025 stutter report #10972](https://github.com/ldtteam/minecolonies/issues/10972),
[2025 pathfinding report #10634](https://github.com/ldtteam/minecolonies/issues/10634).

## Honest feature matrix

Scale: **Strong** = a reference strength worth matching; **Adequate** = useful
but not differentiating; **Weak** = material friction or risk; **Unknown** =
the evidence is insufficient; **Unproven target** = designed or in progress,
but not eligible for a shipped rating.

| Capability that matters to the player | TekTopia | MineColonies | Hearthstead Blessing target |
|---|---|---|---|
| Physical item → living target | **Strong** — profession token in hand, right-click target | **Adequate** — many world tools/items, but deeper control trends toward building/citizen UI | **Unproven target** — seal in hand, Shift-right-click settler |
| Physical item → building target | **Strong** — marker in item frame at door | **Adequate** — building blocks/tools plus UI | **Unproven target** — same seal interaction on a registered plaque |
| Reward choice safety | **Unknown** — no auditable source for concurrent/replayed actions | **Adequate** — server-backed colony actions, not the same reward problem | **Unproven target** — exact raid offer/revision, first valid commit wins, one seal issued |
| Wrong/full target safety | **Weak** for mistaken purchases — official FAQ says wrongly purchased tokens cannot be sold back | **Adequate** — extensive validation/messages, feature-dependent | **Unproven target** — seal is consumed only after authoritative `APPLIED` |
| World-readable target state | **Strong** — glowing markers and thought icons | **Adequate** — entities, particles, building/citizen views | **Unproven target** — permanent rune/status plus one-shot binding feedback |
| UI information depth | **Adequate** — customizable per-worker AI, but narrower presentation | **Strong** — searchable lists, tabs, vital status, statistics, permissions | **Unproven target** — deliberately smaller three-card choice with exact before/after effect copy |
| Low-friction decision path | **Strong** — direct tokens and markers | **Adequate/Weak** — powerful but menu-heavy | **Unproven target** — Select + Confirm prevents accidents; one world action binds |
| Visible work/action identity | **Strong** in official presentation; closed source prevents technical audit | **Adequate** — held tools, swings, particles; bespoke clip depth varies | **Unproven target** — authored receive/bind animation with type-specific accent |
| Texture/material identity | **Strong** — professions and markers are recognisable | **Strong** — broad, consistent GUI/entity/item asset language | **Unproven target** — three original seals in Hearthstead's oak/iron/brass/wax language |
| 50+ resident performance evidence | **Unknown** — 100+ is an official claim, not a reproducible modern benchmark | **Mixed** — explicit scale controls and active engineering, with real issue history | **Unproven target** — mandatory 1/25/50/100 scale matrix and soak |
| Explainable failure | **Strong** for many villager needs via thought icons; some token failures still require FAQ knowledge | **Strong** in depth, but information can be dispersed across screens | **Unproven target** — one-line, target-specific reason; never silent |

## Strengths, weaknesses, and the opening Hearthstead can own

### TekTopia

**Strengths**

- Its most important controls are objects in the Minecraft world.
- Status is often visible on the object or above the villager.
- The visual fantasy and interaction model reinforce each other.

**Weaknesses / constraints**

- It is frozen on an old game version and cannot provide a current NeoForge
  implementation reference.
- Closed source means security, save migration, multiplayer arbitration, and
  scale behaviour cannot be audited.
- Some error recovery is weak: a mistaken purchase is irreversible, and the
  official FAQ is needed for several marker failure cases.

### MineColonies

**Strengths**

- Deep, searchable colony and citizen information.
- Mature separation of UI layout and behaviour.
- Explicit engineering controls for expensive AI, pathfinding, and retries.
- Open source gives us auditable failure patterns and design lessons.

**Weaknesses / risks**

- Dense, multi-tab administration can pull attention away from the living
  settlement.
- More simulated systems create more ways for pathing, retries, synchronization,
  and UI state to become expensive or hard to explain.
- Issue history means "large colony" must be demonstrated under load, not
  inferred from feature breadth.

### Hearthstead opportunity

Own **the tactile, dangerous settlement bond**: the reward exists because the
village survived, becomes a crafted-looking object in the player's hand, and
permanently marks a person or a place the player actually cares about. Keep the
choice UI short and intentional; put the lasting proof back in the world.

This differentiates on an axis neither reference fully owns:

| | World-first | Menu-first |
|---|---:|---:|
| Calm administration | TekTopia | MineColonies |
| Consequential survival bond | **Hearthstead target** | avoid |

## Binding implementation requirements

These requirements are the practical result of the comparison. A later design
may exceed them, but should not quietly weaken them.

### REQ-BRB-01 — tangible, auditable reward

- Every accepted raid offer issues exactly one physical seal item matching the
  chosen Blessing.
- Delivery order is hand if appropriate, then inventory, then a server-spawned
  drop at the player; there is no silent deletion when inventory is full.
- The offer ledger and item issuance are one authoritative transaction. Replayed,
  stale, out-of-range, wrong-dimension, or too-distant actions issue nothing.
- Two players confirming the same offer concurrently produce one winner and
  exactly one seal in total; all viewers receive the authoritative result.

### REQ-BRB-02 — physical target contract

- Shift-right-clicking a settler or a registered building plaque is the only
  normal binding action.
- The server validates player, hand, seal type, reach, target registration,
  target rank, and target availability. Seals are intentionally portable and
  tradable; they carry no hidden settlement-owner metadata.
- The seal shrinks only after `APPLIED`. `MAXED`, invalid, stale, unloaded,
  foreign-settlement, or otherwise rejected targets keep the stack unchanged
  and return a one-line reason.
- Each target stores at most the three known Blessing ids and ranks I–III.
  Unknown, duplicate, mismatched, or out-of-range saved values fail closed.
- Permanent means save/reload, chunk unload/reload, server restart, entity
  conversion paths, and multiplayer reconnect all preserve the exact rank.

### REQ-BRB-03 — UI parity and differentiation

- The Hearth shows three cards, one selected state, and a separate Confirm.
  Clicking a card can never commit by itself; double-clicking cannot send two
  accepted commits.
- Every card says what the seal affects, what target types accept it, and the
  exact per-rank effect. Do not show settlement-wide rank language for a
  target-bound reward.
- Loading/waiting, accepted, other-player-won, stale, invalid, too-far,
  unavailable, target-maxed, and all-safe-delivery outcomes have explicit copy.
- Status is never colour-only. Selection, warning, success, and disabled states
  pair colour with border/icon/word.
- EN and NB must pass at GUI scales 2, 3, and 4 at both 1280×720 and 1920×1080:
  zero clipped controls, overlapping text, unreachable buttons, or text outside
  its material panel. Long copy may wrap; essential numbers may not ellipsize.
- The whole choice path is keyboard-navigable in deterministic order and Escape
  is non-destructive.

### REQ-BRB-04 — animation and feedback craft

- A successful settler binding starts a dedicated `BLESSING_RECEIVE` reaction,
  not only the generic hand swing: settle/anticipation → clear contact/acceptance
  → held recognition pose → weighted recovery.
- Torso and root participate; the limb may not animate over a statue. Contact
  timing is shared by sound and VFX, with no one-frame pop.
- Warden's Oath, Hearthward, and Thorned Roads have distinguishable accent
  silhouettes or motion/VFX beats even when colour is removed.
- A plaque binding has a short authored rune activation rather than a permanent
  particle fountain. One-shot feedback is capped at 12 particles, one positional
  sound, and one localized status message.
- Start of visible acknowledgement occurs on the authoritative response, with a
  local reference-hardware target of ≤100 ms at 20 TPS. No client predicts a
  successful permanent binding.
- Standard evidence includes front-three-quarter and side views, the exact
  contact frame, one full recovery, and a repeat showing the loop has no stuck
  pose. Animation checks and human visual review are both required.

### REQ-BRB-05 — texture and world-state craft

- The three seals use original Hearthstead art; no reference pixels, silhouettes,
  UVs, or code are copied. Source-of-truth generation remains reproducible.
- At inventory size, each seal is identifiable by silhouette/rune before colour.
  A grayscale contact sheet must score 3/3 correct identities in blind review.
- Palette and material read as wax/parchment/aged brass/forged iron, aligned with
  the existing Hearthstead visual language. Avoid neon magic, flat placeholder
  fills, baked-in text, or the enchantment glint doing all identity work.
- The in-hand render, dropped item, tooltip, creative-tab icon, binding burst,
  settler status, and plaque status use the same type language.
- Persistent world state is restrained: a badge/rune, inspection line, or
  distance-capped overlay. It must remain discoverable after particles end, and
  must not spawn particles every tick.

### REQ-BRB-06 — performance architecture

- Binding is event-driven. There are no per-tick global settler, building,
  plaque, or inventory scans.
- A target rank lookup is constant-time and allocation-free on the hot combat
  path. Target data is bounded to three entries.
- Building-aura lookup uses a bounded spatial/indexed query or an already-known
  building identity; it may not scan every building for every damage or movement
  event. Any cache has explicit invalidation on register, move/dissolve, chunk
  unload, and save reload.
- Permanent target state sends no idle heartbeat. Network updates are emitted on
  open, authoritative mutation, co-op result, and relevant tracking start only;
  unchanged state is not resent.
- Particles and sounds are success-event-only and tracking-distance limited.
  A rejected interaction does not broadcast effects.

## Measurable premium gates

All measurements compare the same seed, location, view distance, simulation
distance, Java/runtime, JVM flags, mods, player route, and hardware. Baseline is
the identical build/world with Blessing effects disabled, not an invented TPS
number. Record median and p95/p99 where the harness supports them.

| Gate | Pass condition | Evidence required |
|---|---|---|
| **G-BRB-01 Transaction integrity** | 1 accepted offer = 1 seal across hand, inventory, entities, and persistence; 0 duplication/loss in 100 repeated and 20 two-player concurrent trials | GameTests plus authoritative inventory/entity/save audit |
| **G-BRB-02 Target safety** | 100% of invalid/maxed/unloaded/replayed cases retain the seal; 100% of valid settler/plaque cases increment exactly one rank, including an intentional cross-settlement gift | Result matrix, stack counts, saved NBT before/after |
| **G-BRB-03 Persistence** | Exact target ranks survive two clean restarts, chunk unload/reload, and multiplayer reconnect; malformed ledgers grant zero effect and remain quarantined | Save fixtures and two clean runtime passes |
| **G-BRB-04 UI scales** | EN/NB × GUI 2/3/4 × 1280×720/1920×1080: 24/24 captures with zero P1/P2 clipping, overlap, dead control, or unreadable essential value | Opened screenshot grid and input replay |
| **G-BRB-05 Visual identity** | Three seals recognized 3/3 in grayscale; settler and plaque binding readable at 4, 12, and 24 blocks; no colour-only status | Opened contact sheet plus fixed-camera film |
| **G-BRB-06 Animation** | Dedicated receive/bind beat passes the project animation checker with no new notes; sound/VFX land on authored contact; no stuck pose in 50 repeats | Keyframe report, fixed-camera film, live review |
| **G-BRB-07 50-settler steady state** | With 50 settlers and 50 plaques, median server MSPT regression ≤5%, p95 ≤8%, no sustained FPS regression >5%, and zero Blessing idle packets/particles | 15-minute baseline and enabled profiles after warm-up |
| **G-BRB-08 100-settler stress** | With 100 settlers and 100 plaques, median server MSPT regression ≤10%; no unbounded queue/cache growth; memory returns to a stable band after GC | 30-minute soak, profiler, packet and heap trend |
| **G-BRB-09 Hot combat path** | 50 settlers + authorized raid: Blessing lookup/aura work is bounded; no global building scan appears in sampled hot paths; no retry/path request is created by the effect itself | Profiler flame view and instrumented query counts |
| **G-BRB-10 Multiplayer feedback** | Winner, loser, and observer converge to the same state without reopening; acknowledgement target ≤100 ms locally at 20 TPS and never falsely predicts success | Two-client film, packet timestamps, server ledger |
| **G-BRB-11 Asset completeness** | Models/textures resolve, no missing-texture purple/black, EN/NB key parity, creative-tab and dropped/in-hand views verified | Asset validator plus opened runtime captures |
| **G-BRB-12 Full quality gate** | Relevant automated suite passes; Minecraft QA passes twice from clean launches; no critical/high finding; human playtest remains explicitly recommended | Standard quality report and fresh final review |

Relative regression limits are intentionally strict because the permanent
Blessing state is tiny. If a feature cannot meet them, first change the lookup,
sync, or feedback design; do not hide the cost behind a user-facing throttle.

## Concrete test scenes

1. **The deliberate choice:** two players at one Hearth, both select different
   cards and confirm on the same tick. One receives one seal; both screens show
   the same winner and next offer state.
2. **The safe mistake:** use every seal on a vanilla mob, invalid plaque,
   max-rank settler, max-rank plaque, and unloaded/dissolved building. Stack
   count never changes; each refusal is explainable. Then gift one to a second
   settlement and prove that portability is deliberate rather than accidental.
3. **The permanent person:** bind all three types and ranks to one settler,
   restart twice, unload the chunk, reconnect a second player, then inspect and
   exercise each effect under authorized and unrelated combat.
4. **The permanent place:** bind a plaque, rebuild/revalidate the room, restart,
   dissolve the building, and prove both correct persistence while valid and
   safe teardown when identity ends.
5. **The crowded square:** 50 then 100 settlers, the same number of plaques,
   mixed ranks, no raid for idle measurement, then a controlled raid for hot
   path measurement. Compare against the exact same world with effects disabled.
6. **The premium shot:** fixed camera captures selection, seal in hand, settler
   contact/reaction, plaque rune activation, persistent status, and wrong-target
   feedback in EN and NB.

## Strategic guardrails

- **Match TekTopia** on physical clarity and world-readable cause/effect.
- **Match MineColonies** on precise state and explainability, not total menu
  surface area.
- **Beat both references** only when stored evidence shows safer transactions,
  clearer target feedback, original visual craft, and a measured 50/100-settler
  cost. Until then, say "designed to exceed," not "better than."
- Do not add a general-purpose Blessing management screen merely to look deep.
  Inspection belongs on the settler/plaque; the Hearth screen exists only for
  the irreversible choice.
- Do not spend frame time continuously advertising permanence. The binding beat
  can be rich because it happens once; steady state must be quiet and cheap.

## Review cadence

Refresh this benchmark when either reference changes materially, before locking
the Blessing slice, and whenever performance architecture changes. TekTopia's
official pages are historically stable; MineColonies' `version/main` is active,
so source-linked observations should be rechecked against a new pinned commit.
