# Hearthstead — active goal refinement

**Refinement ID:** `HS-DEMO-GOAL-20260831-V1`  
**Status:** Governing operational interpretation of the active durable goal  
**Decision authority:** Tobias, represented by the Hearthstead Orchestrator  
**Recorded:** 2026-08-31, Europe/Oslo

## Refined goal

Deliver one traceable, enjoyable Hearthstead friends-demo on Minecraft
`1.21.1`, NeoForge `21.1.248`, and Java `21`, playable naturally from a new
player's first minute through a satisfying first raid. The demo must make its
core systems understandable without commands or external explanation, must
feel visibly and audibly like the overhauled Hearthstead rather than the old
build, and must preserve items, roles, structures, and settlement state across
the supported singleplayer and multiplayer lifecycle.

The demo is the product target. Broad post-demo branching, large content
expansion, and speculative feature breadth do not outrank completing this
vertical slice.

## Player-facing completion contract

### 1. New-player journey

- The player receives or can safely recover the Starter Handbook exactly once.
- The handbook and in-game feedback lead to the Hearth before Hearth knowledge
  is assumed.
- The path from Hearth to the first buildings, workers, equipment requests,
  defenses, and first raid has one clear next action at every blocking step.
- The demo path requires no debug command, hidden recipe, external wiki, or
  developer explanation.

### 2. Settlement foundation loop

- Hearth, house/tavern, lumber, farm, and warehouse/courier flows are coherent.
- Emblems assign the intended job directly when the required building is live.
- Lumberer, Farmer, and Carrier continue through repeated work rather than one
  successful action followed by spinning, idling, or false `no target` states.
- Workers traverse supported doors and height-aware work zones reliably.
- Workers request missing equipment; equipment is not silently granted.
- Workplace storage and courier requests form a visible, recoverable logistics
  loop without item duplication or loss.

### 3. Presentation bar

- Settler, Hearth, Development, building, inventory, alert, tooltip, empty,
  error, and assignment surfaces use one original Hearthstead design language.
- All attribute values are numeric and visible on the primary settler surface;
  job-relevant attributes explain their direct practical effects simply.
- Researched entries clearly reveal the unlocked emblem and exact build-plan
  recipe.
- UI opening and interaction target `p95 <= 16.67 ms` on Settler, Hearth, and
  Development in the fixed native comparison; control screens may not regress
  by more than 10% without a measured explanation.
- Development supports useful far zoom without incorrect scale, clipping,
  illegible interaction, or runaway rendering cost.
- Animation state changes remain physically readable: forward bending, placed
  bags remain placed, carried loads match pose, visible transfers agree with
  inventory truth, and crafting/deposit actions do not snap or double-play.
- Sound communicates action, success, failure, warning, role, and progression
  without generic repetition or spam; every external asset has compatible
  provenance.

### 4. Guard and first-raid vertical slice

- The player can field at least one Knight and one Archer before the first raid.
- Guards can follow/protect the player, hold/patrol where supported, acquire
  sensible targets, spread pressure instead of dog-piling blindly, and gain
  experience with readable audiovisual feedback.
- Knight and Archer have meaningfully different strengths and weaknesses.
- The first enemy group has a recognizable Hearthstead identity, readable
  counterplay, escalating pressure, and a satisfying reward/conclusion.
- The raid is challenging but does not depend on unexplained rules, spawn on
  top of the player, or collapse from one stuck entity.

### 5. Integrity and evidence

- Singleplayer: first join, play, save, quit, reload, death, and dimension
  transitions preserve authoritative state.
- Multiplayer: join, reconnect, role/inventory mutation, shared settlement use,
  and interruption paths preserve authority without duplication or loss.
- Completion evidence names the exact source state, JAR path, SHA-256, profile,
  world, settings, and test time.
- Compile, static analysis, mockup, screenshot, or deterministic tests alone do
  not prove a player-facing feature finished.

## Immediate execution order

1. Finish the exact-JAR native UI baseline/candidate A/B and repair any failed
   P0 performance, clipping, input, or scale gate.
2. Seal the coherent Hearth → first buildings → Lumberer/Farmer/Carrier loop.
3. Complete visible requests, equipment, storage, courier queue, and recovery.
4. Complete guards and the first-raid slice.
5. Run deterministic, native singleplayer, multiplayer, reconnect, and exact-
   artifact gates; loop on every failed acceptance criterion.

## Explicitly deferred until the vertical slice passes

- A broad branching tech-tree sandbox beyond what the demo journey needs.
- Full late-game role/equipment progression.
- Large building catalogues, multiple raid factions, and deep end-game balance.
- A Minecraft `26.2` port, a `1.20.1` Forge backport, or an unfrozen loader
  upgrade.

Deferred does not mean rejected. These become informed product decisions after
the demo proves the core experience is worth expanding.

## Council execution rule

The Orchestrator represents Tobias in routine council decisions, may disagree
with every chief, and issues the next bounded work order. Normally one
specialist is activated, two require two distinct risks, and three require
three named independently falsifiable risks. Normally one Implementation
Worker is used; up to three may hold disjoint exact file leases under chief
supervision and one integration owner. Tobias is asked only for a genuine
consequential choice, external authority, or a change to the product goal.

The weekly Codex usage display has a hard floor at 60% remaining. At a trusted
65% report, the council spawns no new agents and closes the current safe
checkpoint; at 60% or less it preserves state and stops new work. When the host
does not expose the percentage, status is `UNKNOWN` and must never be guessed.
