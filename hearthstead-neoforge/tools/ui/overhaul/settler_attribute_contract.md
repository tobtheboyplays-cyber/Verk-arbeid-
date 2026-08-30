# Settler Overview attribute contract

Status: visual/runtime contract. Offline previews are `Candidate`; only
server-wired effects with passing tests may be labelled as current bonuses.

## First-page rule

All eight broad attributes are visible together. There is no Attributes tab
and no Blessings tab.

```text
Strength    Stamina
Wits        Dexterity
Spirit      Perception
Focus       Presence
```

Each tile always shows the attribute name and `NN / 100`. The practical
runtime maximum is 99, and `99 / 100` hover may say `Practical maximum`.
Pips, stars, colour and bars may reinforce a value but never replace its
number. Active Blessings remain visible by identity and rank on the same page.

## Readable tiers

Newcomers start at 1–15, so the low range is split rather than painting every
new person the same red.

| Value | Tier | Required non-colour signal |
|---|---|---|
| 0–9 | Untrained | tier label plus number |
| 10–19 | Promising | tier label plus number |
| 20–39 | Developing | tier label plus number |
| 40–59 | Capable | tier label plus number |
| 60–79 | Strong | tier label plus number |
| 80–100 | Exceptional | tier label plus number |

There is no glow, pulse or flicker. Positive effects may use green, but text
and exact signs/numbers repeat the meaning for colour-independent reading.

## Semantic boundaries

- Strength: force, heavy contacts and carrying.
- Stamina: slower fatigue, recovery and pace under load.
- Wits: learning and unfamiliar problem solving.
- Dexterity: hand skill and physical precision.
- Spirit: personal morale and resilience.
- Perception: choosing useful targets from an already bounded search.
- Focus: setup, recovery and continuity; never obedience or endurance.
- Presence: recruitment, teaching, coordination and rally effects.

## Role highlighting

Every employed role has exactly two `CORE` attributes and at most one
`SUPPORT` attribute. Those existing tiles receive a discrete role border/tag;
the other five or six values stay visible in the same positions.

The default role explanation stays simple:

```text
Strength  — Fewer axe strikes and heavier loads
Stamina   — Keeps pace with a heavy sack
Wits      — Learns the trade faster
```

Hover or keyboard focus expands this with the exact current bonus and next
threshold, but only when those numbers come from the same server calculator
used by the AI. Planned effects must be marked `Planned`, never presented as
live green bonuses.

## Work Pace

The screen shows a calculated `Work Pace` percentage instead of `Daily work
left`. Fatigue slows work progressively and may lead into a visible rest or
sleep state. It never makes otherwise valid work fail because a hidden daily
quota reached zero. Authored physical animation/contact timing remains fixed;
fatigue adds bounded recovery between actions.

## Choice and progression

Broad attributes describe the person. Profession XP and level describe what
they have learned in a job. They are displayed separately so switching jobs
cannot turn an unrelated broad attribute into instant mastery.

The UI never produces a fit score, recommended recipient, best-candidate
badge, automatic sort or automatic selection. The player compares the eight
values and physically gives the Emblem to the person they choose.

## Verification gate

- Eight values fit on the first page at every supported resolution/GUI scale.
- English and Norwegian names and `NN / 100` never clip.
- Mouse hover and keyboard focus reveal the same details.
- Snapshot data is exact, bounded and server authoritative.
- Two clients may inspect the same settler through a value change without
  stale overwrite or crash.
- No per-frame list rebuild, formula allocation, inventory scan or world scan.
- Native-client visual/performance evidence is required before in-game
  approval.

