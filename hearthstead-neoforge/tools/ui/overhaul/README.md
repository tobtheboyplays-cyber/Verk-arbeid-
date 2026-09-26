# Hearthstead UI overhaul — tool-rendered design contract

These specs are the visual source of truth for the current UI overhaul review.
The approved **Living Settlement Tree** is the minimum bar for every screen;
the earlier flat command-card compositions are retained only until their
replacement states pass review and must not be described as final.
They render through `tools/ui_preview.py`, using the same generated nine-slice
sprites, token values and Minecraft font metrics as the game. Run the complete
strict and deterministic preview set with:

```text
python tools/render_ui_overhaul.py
```

Generated review files live under `build/ui-overhaul/`. They are evidence for
composition and text fit, not a substitute for the later native-client gate.

## Player-facing contract

**Deep simulation, shallow interface.** Hearthstead may use exact thresholds,
weighted capacity, physical ownership, server revisions and role-specific
formulas internally, but the default screen explains the result in ordinary
player language: "fewer swings", "carries more" and "works longer". Exact
numbers, formulas and next thresholds belong in hover/focus detail or an
advanced inspector. Complexity must create believable play, never a wall of
instructions.

Every primary screen must answer, in this order:

1. **What am I looking at?** — stable title and object/settlement identity.
2. **What is the current state?** — one readable summary, not a wall of values.
3. **Why is anything blocked?** — the real server reason in warning/bad tone.
4. **What can I do next?** — one visually dominant valid action.
5. **Where is deeper detail?** — inspector, secondary tab or tooltip, never
   overlapping the primary decision.

## Living Settlement visual direction

- The shared design system is a common material, typography, colour, spacing
  and interaction language — **not one repeated composition**. Each surface
  is shaped around its job: Development may use connected paths because
  prerequisite relationships are the content; the Emblem Counter explains
  the role and its attribute effects without choosing a settler; Courier
  Requests uses an ordered, draggable ticket queue; inventory uses physical
  slots; Guard Orders uses a command map. Decorative route lines are rejected
  when they do not encode a real relationship the player can act on.
- Original Hearthstead dark oak, forged iron and restrained brass identity,
  with quiet terrain shapes and route lines that make information feel tied to
  a living place rather than a generic administration panel.
- Minecraft font and pixel geometry; no borrowed layouts, icons or assets.
- Four-pixel grid, eight-pixel normal padding, four-pixel normal gutter.
- Warm double frames define the selected/authoritative surface. Soft organic
  fields sit behind content; they never reduce contrast or become decoration
  that competes with text.
- Real Hearthstead icons and role Emblems carry identity. Medallions, route
  segments and ribbons are used only when they explain progression, ownership
  or the current task—not as repeated ornament.
- Strong text for identity, ordinary text for state, muted text for supporting
  detail. Accent is reserved for the next action or current selection.
- Good/warn/bad are never the only carriers of meaning: each state also has a
  readable label or icon.
- Bounded fields group one decision or one status. Flat repeated cards are not
  the primary composition. Empty, loading, blocked and error
  states occupy the same stable geometry as populated content.
- Full-screen/centred surfaces replace fixed right-edge panels. Narrow variants
  must preserve the same information order without overlap.
- Animation is later runtime polish. No preview is allowed to hide information
  behind pulsing, glow, flicker or perpetual movement.

## Workflow rules encoded in the previews

- Job assignment is physical: buy the correct Emblem from the living Mayor,
  hold it and right-click a compatible settler. A workplace Plaque never shows
  a separate **Hire** action.
- Housing is distinct from employment and uses **Residents**, **Move In** and
  **Assign Bed** language.
- Worker and building blockers state both the missing condition and the next
  player action.
- Settler attributes are explicit numbers on a stable scale, for example
  **Strength 72 / 100**. Pips, stars or bars may reinforce a value, but never
  replace the number the player uses to compare settlers.
- Every attribute changes real gameplay; there are no decorative statistics.
  Hover/focus reveals its current, concrete bonuses and the next meaningful
  threshold without forcing the player to consult an external guide.
- Attribute values use restrained, consistent tiers (low red, developing
  amber, good green, exceptional gold). Positive and negative tooltip effects
  are also coloured, while explicit numbers and signs preserve meaning for
  players who cannot distinguish the colours.
- Every profession declares exactly two core attributes and at most one
  support attribute, then explains the job-specific effect of each. Plaque
  Staff/Requirements shows the role contract before assignment; Emblem hover
  carries the short form; the assigned settler view highlights the same
  attributes in place. These are gameplay contracts, never a score,
  recommendation, automatic selection or reason to hide the other values.
- All eight broad attributes are visible on the first Settler Overview in the
  stable order Strength, Stamina, Wits, Dexterity, Spirit, Perception, Focus,
  Presence. Job proficiency and experience are separate from these broad
  personal attributes.
- Work Pace replaces a hard daily-work remainder in player-facing UI. Fatigue
  may slow a settler or make a visible rest state take over, but a hidden daily
  quota must never make them abruptly stop accepting otherwise valid work.
- Development owns progression/knowledge. Operational Requests, workers and
  settlement alerts stay on their own task-focused surfaces.
- Development follows a readable trunk to the first raid, then three doctrine
  branches. Far zoom reduces card detail but never removes state or selection.
- Development node hover shows the real knowledge and Mayor-access icons. Once
  a Build Plan node is learned, its pinned inspector exposes the authoritative
  crafting recipe, ingredient names, shaped/shapeless status and exact typed
  output. Before learning, the recipe is hidden from that player's recipe book;
  settlement authorization—not the recipe-book presentation—still validates
  use of the crafted plan. Research never grants a free physical Emblem: it
  makes that profession's Emblem available to buy from the living Mayor.

## Required coverage

Every runtime screen needs normal, hover/focus, empty, blocked/read-only,
error/timeout and narrow evidence where the state applies. The full set covers
Hearth and its Mayor/Journey/Requests/Raid surfaces, Plaques, Settlers and their
inventory, Courier requests, Guard orders, the Emblem shop, Development,
Architect's Study projects, settlement storage, Blessings, the Handbook and
Work Scepter confirmation/HUD. World nameplates, request bubbles, Mayor marks
and physical Plaque sheets have their own image/state QA rather than being
silently excluded from the UI standard.

## Preview acceptance

- `tools/render_ui_overhaul.py` reports zero strict warnings.
- Two renders of every spec are pixel-identical.
- English is the authored mod language. Worst-case and narrow specs must still
  fit; supported translations are validated before runtime approval.
- No text crosses a card, panel or assigned box. No control overlaps another.
- Every preview has one clear focus and no more than one primary action.
- Final runtime implementation must later preserve focus order, narration,
  click targets, multiplayer authority and measured frame performance.
