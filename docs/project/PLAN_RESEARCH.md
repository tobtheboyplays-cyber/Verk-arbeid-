# Prøvebenken — the research system, nine ways considered and the one built

*User request, relayed by the coordinator: "Build the research system. Make
9 proposals, then build the one that makes most sense — complete: the job,
the building, what it costs, what is in it. The UI must look good."*

This document records all nine proposals the coordinator drafted, why one of
them was chosen over the other eight, and then the complete design of the
system actually built — SLICE RESEARCH-1.

---

## §1. Nine proposals

Each is a real, buildable shape for "how does a settlement get better at
things over time." None is a strawman; several would have been good systems.

1. **Lærdomsboka** ("The learning-book"). A scribe writes a physical book of
   the settlement's learnings — a real `WRITTEN_BOOK` item sitting on a
   lectern — and the player opens it to pick which page (project) to pursue
   next, the way a research tree is normally browsed. Charming because the
   book is a real, lootable, raidable object, but it adds a whole new UI
   metaphor (reading pages of a Minecraft book as a menu) on top of the one
   this mod already has, for no mechanical gain over a normal panel.

2. **Prøvebenken** ("The trial bench"). An active experiment that consumes
   real material samples for a concrete, numeric recipe bonus — pay paper and
   a domain material, wait out scholar-days, get a percentage. Chest-true,
   readable at a glance, and every bonus maps onto one existing FLOWS.md fed
   path. **CHOSEN — see §2.**

3. **Tradisjoner-lite** ("Traditions, light"). A three-branch tech tree
   (Craft / Land / War) that trickles a small bonus daily with no player
   choice beyond which branch to favor. Zero busywork, but also zero of the
   "the player did a thing and the settlement is now visibly different"
   payoff every other Hearthstead system is built around (D-013's whole
   point: the player decides, not a background number).

4. **Mesterbrev** ("The master's letter"). A settler who has reached the top
   of their trade authors a "trade letter" that grants every worker of that
   trade a bonus. Elegant because it ties research to the attribute/rank
   systems already built, but it makes research a side effect of something
   else (a master existing) rather than a thing the player does on purpose,
   and it has no natural "building" — it would either bolt onto an existing
   workshop (muddying that building's own identity) or invent a ceremony with
   no room to hold it.

5. **Kartleggingen** ("The survey"). Scholars lead world-survey expeditions —
   settlers leave the radius, come back after some ticks, unlock knowledge
   tied to what biome or structure they found. The most narratively
   interesting of the nine, but it needs pathfinding-out-of-settlement
   logistics, a new "expedition" entity state, and biome/structure detection
   that nothing else in this codebase does yet — a multi-slice system wearing
   this slice's clothes.

6. **Skriverstua** ("The scriptorium"). Pure passive: paper and ink flow in,
   "lore points" accumulate, the player spends them in a book-shaped shop UI.
   No active decision beyond stocking paper, so a settlement's whole research
   trajectory is decided the moment its warehouse policy is — which is the
   opposite of D-013 (the settlement never decides on its own; the player
   does), just relocated to a spending screen instead of a work goal.

7. **Oppfinnelser** ("Inventions"). Periodically (each in-game week) the
   player is offered three random invention cards, roguelike-draft style, and
   picks one. Fun the first few times, but the roster is fixed at six
   projects for v1 — a random-three-of-six draft mostly just adds a waiting
   timer and a coin flip on top of the same six choices Prøvebenken already
   offers up front, honestly.

8. **Læretid** ("Apprenticeship"). A schoolmaster converts the settlement's
   masters' accumulated experience into a higher *starting* attribute cap for
   newcomers — research as demographics, not buildings. Real and interesting,
   but it reads as an extension of the attribute/SLOW_START system
   (`PLAN_ATTRIBUTES.md`) far more than a new building-and-job system, and it
   answers a different question ("are new settlers better") than the one
   asked ("does the settlement's *work* get better").

9. **Runesteinen** ("The runestone"). A plaza monument that physically grows
   — more runes carved, taller, wider — with each completed research tier,
   visible from anywhere in the settlement. The best *symbol* of the nine,
   but it is a building-growth system wearing a research system's name: the
   actual mechanic (what triggers a tier, what a tier buys) still has to be
   one of the other eight underneath it. It is a skin, not an answer.

## §2. Why Prøvebenken (#2), hybridized with #6's trickle

**Chosen: #2, with one piece of #6 folded in** — an active, resource-consuming
project (#2's whole premise) *plus* a small passive trickle from simply
employing a scholar (#6's one genuinely good idea, at a scale that never lets
it replace the active project).

Four reasons, each a hard constraint this repository already carries:

1. **Chest-true (INV-3).** Every other system in this mod moves real items
   through real containers — Production, the courier network, the plaque's
   own requirements. A system whose "cost" is an abstract point total (#6
   alone, #3, #8) would be the one un-physical economy in a mod whose entire
   design language is "if you can't point at the item, it isn't real." #2's
   paper-plus-domain-sample cost is chest-true the same way a smithy's iron
   is.

2. **Reads at a glance.** The user's own ask was "the UI must look good," and
   a screen only looks good when what it shows is legible in one look. A
   percentage-tick-multiplier project ("Bakery works 15% faster") is a single
   sentence a player already understands from every other building in the
   game. A tech-tree (#3), a card draft (#7), or a demographic shift (#8) all
   need more explaining before the number means anything.

3. **Plugs straight into FLOWS.md's own shape.** `docs/project/FLOWS.md` (read
   first, per the brief — it is binding) is built entirely out of
   *multipliers between fed paths*, never gates. Prøvebenken's six v1 projects
   are exactly that: each sits on one existing fed path and gives it a modest,
   readable multiplier, so a scholar's work reads as *one more voice in the
   same economy* rather than a bolted-on second one. None of the other eight
   proposals maps this cleanly onto FLOWS' existing vocabulary.

4. **Uses a building that already exists, unaltered.**
   `BuildingType.ARCHITECTS_STUDY` — lectern, two bookshelves, a door, two
   lights, 16 floor — has stood in the enum since the plaque system landed,
   with **no trade and no purpose**. #2 is the only one of the nine that
   needs nothing more from that building than what it already demands: a
   lectern to work at, bookshelves to justify the room. #4, #5 and #9 all
   need a building this repository does not have, which is out of scope for
   one worker's slice.

The design plan's own line — quoted directly in the coordinator's brief —
settled the hybrid: *"one active resource-consuming project + passive scribe
trickle."* That is #2 plus a sliver of #6, and nothing else on the list
matches it as closely.

---

## §3. What was built

### 3.1 The job — `Profession.SCHOLAR`

Id 18, ink-blue `0x3E5C8A`, hands empty (the work animation and the room
identify the trade, the same choice made for every crafting profession since
CHAINS-1 — a vanilla item at the hip would read as clutter, not a scholar).

`Employment.tradeOf(ARCHITECTS_STUDY) = SCHOLAR`, `worksAtTheBuilding = true`
(default — a scholar's work is at the lectern, not in a field), `trainedBy =
WITS` (judgement, named explicitly rather than left to the default, the same
way INNKEEPER is), motion `WORK_WEAVE` — FINE_WORK's close, careful hand
motion is the nearest existing clip to bending over a lectern, and its
activity key genuinely is `WORK_WEAVE` (see `SettlerEntity#setupAnimationStates`,
which animates `fineWorkState` on it). **A dedicated WRITE clip — a real quill
moving, a page turning — is future signature-motion work**, the same footnote
every reused motion in this codebase carries (INNKEEPER's SORTING, the
coordinator's own MILLER/BREWER additions this slice).

The sound is *not* the naive fall-through. `soundOf`/`soundPeriodOf` key off
the shared **motion**, and the scholar's motion is WORK_WEAVE — which the
existing table already gives to the weaver's LOOM_CLACK. That is a mechanical
clack, not a pen, so `Employment.soundOf` special-cases the scholar's own
trade to `ModSounds.FEATHER_PINCH` (the fletcher's own sound) at a slower
40-tick period: **a quill is a feather**, and the fletcher's soft pinch-and-set
is the one existing sound in the whole catalogue that actually reads as a nib
on paper, quieter and slower than any workshop rhythm in the table.

### 3.2 The building — `ARCHITECTS_STUDY`, unaltered

| requirement | why |
|---|---|
| 1 lectern | the scholar's own bench — where `ScholarWorkGoal` sends them and where `ResearchEvents` opens the screen |
| 2 bookshelves | the room reads as a place of learning before a single project is chosen — the plaque's own furnishing-score logic already rewards this |
| 1 door, 2 lights, 16 floor | the standard livable-room minimums every work building carries |

No requirement changed. The study was designed for this job years before this
slice landed; it needed a trade, not a rebuild.

**No storage requirement exists on it.** `BuildingType` is another worker's
file this slice, so it was not touched. `Research`'s `sourcesFor` pays from a
chest or barrel actually standing in the study's own room when one is present
(`WarehouseIndex.containers`, the exact lookup `Production` uses for every
crafting building's own chests — mirrored, not reinvented), and falls back to
the settlement hearth's communal larder otherwise. **Follow-up for v2:**
give `ARCHITECTS_STUDY` a real `Requirement.blocks("storage", ...)` line so a
player can furnish the study with its own chest the way every crafting
building already can.

### 3.3 The six v1 projects

Every project spends **4 paper** (the write-up) plus one domain sample, and
targets exactly one `docs/project/FLOWS.md` fed path with a gentle, readable
multiplier — `1.0` is always the neutral, uncompleted value (FLOWS' own rule:
multiply, never gate).

| project | domain cost | work-days | effect |
|---|---|---|---|
| **Bedre Gjær** | wheat ×16 | 3 | Bakery: works 15% faster |
| **Tørrsett Tømmer** | oak log ×24 | 3 | Sawmill: works 15% faster |
| **Blestring** | raw iron ×12 | 3 | Smelter: works 15% faster |
| **Garvesyre** | charcoal ×12 | 3 | Tannery: works 15% faster |
| **Åkerskifte** | wheat seeds ×16 | 2 | Farmed crops grow 15% more often |
| **Vaktdrill** | iron ingot ×8 | 4 | Guards train STRENGTH 10% faster |

Domain items were chosen to avoid competing with what a live production
recipe already consumes from the same chest — e.g. Garvesyre spends charcoal
rather than the rabbit hide the tannery's own rough recipe needs, so
researching tanning does not starve the tannery of its own input.

**Effects are exposed, not yet wired.** `Research.bonus(ServerLevel,
settlementId, ResearchKey)` returns the live multiplier for a finished
project's key (`BAKERY_TICKS`, `SAWMILL_TICKS`, `SMELTER_TICKS`,
`TANNERY_TICKS`, `FARM_GROWTH`, `GUARD_TRAINING`) — but *reading* it from
`Production`'s recipe ticks, a farm-growth goal, or a guard-training call is
integration work for whichever worker owns those files next. The system is
complete and independently tested up to that seam; nothing downstream of it
exists yet to plug into.

### 3.4 The state — its own `SavedData`

`com.hearthstead.settlement.research.Research` mirrors
`SettlementSavedData`'s exact shape (one `Map<UUID, ResearchState>`, one
factory, one `get(level)`) and is deliberately **not** a field on `Settlement`
— that file belongs to another worker this slice. `ResearchState` is the
per-settlement record (completed projects, the one active project, the daily
trickle's own clock) — the plain-data half, the way `Settlement` is to
`SettlementSavedData`. **Follow-up:** when `Settlement` is free to touch,
`ResearchState` can move onto it directly; only `Research#load`/`#save` would
need to change, because the record is already the thing that would move.

**One active project at a time.** Costs are paid **up front**, atomically —
every line item is checked before any is taken, so a settlement is never left
short after a refused start. Progress advances two ways:

- **Active work**, via `ScholarWorkGoal` (see 3.5) — most of the pace.
- **The passive trickle** — once per in-game day, only when the study has an
  active project *and* an employed scholar, a small amount (`0.34` of a
  session/day, i.e. roughly one free session every three days) rolls in.
  Deliberately small: an attended study is always the main driver, an
  unattended one merely does not stall completely — which is the "passive
  scribe trickle" half of the hybrid from §2.

**Cancelling** refunds half the domain sample (never the paper — the
write-up so far is spent regardless) into the same source it was paid from,
dropping whatever cannot fit at the study's anchor rather than voiding it
(INV-3). A flat half, not a per-project number, on purpose: a cancelled
project is abandoned effort, not a free undo, and a player should be able to
say "half" without checking six different figures.

### 3.5 The trigger and the goal

**`ScholarWorkGoal`** is shaped after `CrafterWorkGoal` (look, work a fixed
stint, pay the cost, chain into the next stint) but is its own goal, the same
reason `InnkeeperWorkGoal` exists rather than reusing `CrafterWorkGoal`
directly: there is no `Production.Recipe` under research, only one shared
project state. A session is 200 ticks of active work (the same scale as every
other crafting trade) and costs 6 units of the daily `Effort` pool — sized so
a fresh scholar's 20-unit pool affords roughly three sessions before running
dry, which is exactly what makes "3 scholar work-days" in the table above
read as "about three real work-days of dedicated attention" without the goal
ever having to know what day it is.

**`ResearchEvents`** opens the screen. The study's lectern is plain vanilla
`Blocks.LECTERN` (a furniture *requirement*, not a block this mod owns), so
there is no block subclass to override the way `PlaqueBlock`/`HearthBlock`
are — `PlayerInteractEvent.RightClickBlock` is the documented seam for
intercepting a vanilla block's interaction from mod code, and cancelling it
on a registered study's own lectern is deliberate: the lectern becomes the
study's control surface the same way the hearth block replaces a plain
container's behaviour once it is founding a settlement. A lectern anywhere
else — including the unrelated one `BuildingType.LIBRARY` also requires —
behaves exactly like vanilla, gated purely by "does this position sit inside
a registered, valid `ARCHITECTS_STUDY`'s bounds."

### 3.6 The UI

A hero card for the active project (or a quiet placeholder when none is
chosen) — name, effect sentence, a progress bar with a day-mark notch at each
work-day boundary, the scholar's name, what was paid — sits above the six
project cards, each with its own emblem, effect sentence, and itemised
have/need cost chips coloured green or amber. Choose is disabled with the
reason in its tooltip whenever a project cannot be started right now — already
researched, a project already under way, or simply not enough on hand (D-014).
Cancel carries its refund warning in its own tooltip.

Verified against both languages with the offline preview
(`tools/ui_preview.py --strict` against `tools/ui/specs/research_{en,nb}.json`,
which the report's screenshots are drawn from): the panel widened from an
initial 280px to 320px after the preview caught two genuine overflows (a
project's effect sentence and the Norwegian progress line, "arbeidsdager"
being the long pole) — exactly the workflow the `minecraft-ui` skill
describes, catching a real problem before a single client boot.

### 3.7 Tests

`ResearchGameTests` (mirrors `EmploymentGameTests`'s own fixtures) covers:
starting a project consumes exactly its costs and conserves items; a refused
start (insufficient materials) changes nothing; progress advances only while
a scholar is actually employed and working, never on its own; a completed
project's bonus is retrievable via `Research.bonus` and survives a
`SavedData` reload (`Research.load`/`Research.save` round-trip); cancelling
refunds the documented half-share and no more.

---

## §4. Uncertainties for the coordinator

- **The six project *names*** ("Bedre Gjær", "Tørrsett Tømmer", "Blestring",
  "Garvesyre", "Åkerskifte", "Vaktdrill") are kept as the same Norwegian proper
  nouns in **both** `en_us` and `nb_no` — the way a spell or technique name
  stays a proper noun across locales — with the *effect sentence* doing the
  plain-language explaining in each language. If the lang-key owner would
  rather gloss the names into English for `en_us` ("Better Yeast," "Seasoned
  Timber," …), only the six `.name` values change; nothing else in the system
  depends on the choice.
- **A signature WRITE clip** for the scholar (and, per the coordinator's own
  addendum, signature clips for MILLER/BREWER) is explicitly future
  animation work, not this slice's — flagged inline everywhere a motion is
  reused.
- **Effect wiring** (`Research.bonus` actually read by `Production`, a
  farm-growth goal, a guard-training call) is integration work for whichever
  worker owns those files next; see §3.3.
- **The study's own chest requirement** (v2 follow-up) — see §3.2.
