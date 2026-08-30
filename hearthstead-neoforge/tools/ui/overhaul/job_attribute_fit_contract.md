# Hearthstead role-attribute contract

Status: locked design contract; a described effect is only `LIVE` after the
shared server calculator and its gameplay test pass. Machine-readable source:
`job_attribute_fit_matrix_en.json`.

## Player-facing rule

Hearthstead keeps the simulation deep and the default interface simple.

- Every Settler Overview shows all eight values as `NN / 100`, never pips.
- The order never changes: Strength, Stamina, Wits, Dexterity, Spirit,
  Perception, Focus, Presence.
- Each employed role marks exactly two `CORE` attributes and at most one
  `SUPPORT` attribute in those existing tiles.
- The role card states a short effect such as `Fewer axe strikes and heavier
  loads`. Hover or keyboard focus may reveal the exact current bonus and next
  threshold.
- The interface never calculates a fit score, labels a best candidate, sorts
  people by attributes, recommends a recipient or automatically selects one.
  The player compares people and physically gives the Emblem to the person
  they choose.
- Job proficiency and XP are separate from broad attributes. Experience in
  one profession cannot silently make a settler a master of another.

## Attribute seams

| Attribute | Owns | Must not own |
|---|---|---|
| Strength | physical force, heavy contacts, carrying | learning, target choice |
| Stamina | fatigue, recovery, loaded pace | skill level, obedience |
| Wits | learning, unfamiliar problem solving | hand precision, world-scan size |
| Dexterity | physical precision and fine work | profession mastery |
| Spirit | personal morale and resilience | social authority |
| Perception | ranking an already bounded target set | extra unbounded scans |
| Focus | setup, recovery and continuity | endurance, obedience, aim |
| Presence | recruitment, teaching, coordination, rally | personal fear resistance |

## Work Pace replaces a daily quota

The Overview shows `Work Pace`, not `Daily work left`. A tired settler works
progressively more slowly, or enters a visible rest/sleep state when the
higher-priority needs system takes over. A hidden daily counter must never
make otherwise valid work abruptly stop.

Physical animation timing remains authored and readable. Fatigue adds bounded
recovery between actions; it does not move a hand/contact sound away from the
server-authoritative contact frame or make a loaded worker crawl.

## Role matrix

The complete 25-role table lives in the JSON. Registry tests must prove:

- one profile for every employed profession;
- exactly two unique core attributes;
- no more than one unique support attribute;
- all eight attributes are meaningfully used by at least three roles;
- every displayed exact bonus comes from the same server-side calculator the
  AI uses;
- no recommendation, score or candidate-sorting API exists in the profile.

## UI placement

### Settler Overview

The Overview contains identity, current action/blocker, needs, Work Pace, all
eight attributes, active Blessings, workplace, physical inventory/equipment
and real controls. There is no separate Attributes or Blessings tab.

### Plaque Staff / Requirements

Show the required role and its role-attribute contract. Explain the physical
Emblem assignment flow. Do not show a `Hire` button or a ranked candidate
list. Housing is separate and never displays job fit.

### Emblem hover

Show what the role does, its two core attributes and optional support
attribute, then the instruction to give the physical Emblem to a compatible
settler. It must not tell the player who should receive it.

## Verification gate

- Pure boundary and monotonicity tests for every displayed formula.
- Save migration from five to eight attributes is deterministic and preserves
  all original values/progress/knack.
- Network decoding rejects wrong counts, unknown IDs and out-of-range values
  before allocation or UI use.
- Server/client snapshot test proves displayed exact bonuses equal gameplay.
- Normal, blocked, stale, multiplayer and reconnect tests for controls.
- Strict English/Norwegian text-fit and keyboard-focus checks.
- Native-client visual and performance evidence before `Approved in game`.

