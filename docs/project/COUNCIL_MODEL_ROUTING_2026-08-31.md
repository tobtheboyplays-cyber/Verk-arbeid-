# Hearthstead Council — credit-aware model routing

**Policy ID:** `HS-MODEL-ROUTING-20260831-V1`  
**Status:** Binding council default  
**Scope:** Council analysis, implementation dispatch, and evidence review  
**Recorded:** 2026-08-31, Europe/Oslo

## Outcome

Hearthstead uses the strongest model where a wrong judgment is expensive, a
balanced model for substantive work, and the lowest-cost capable model for
bounded evidence. The council does not activate its entire specialist bank.
Normally one specialist is dispatched, two are used for two distinct risks,
and three require three independently falsifiable risks named in advance.

This policy optimizes task routing. It does **not** promise a particular Codex
weekly-usage percentage: API token prices and Codex product quota accounting
are different systems.

## Official basis

OpenAI's current model catalogue describes:

- `gpt-5.6-sol` as the flagship for complex reasoning and coding;
- `gpt-5.6-terra` as the intelligence/cost-balanced model;
- `gpt-5.6-luna` as the cost-sensitive, high-volume model.

The current guidance also treats `medium` reasoning as the balanced default
and recommends higher efforts only when evaluation shows a benefit. Sources:

- <https://developers.openai.com/api/docs/models>
- <https://developers.openai.com/api/docs/guides/latest-model>

## Fixed profile allocation

### Sol / high — four profiles

Use for executive adjudication, P0 authority decisions, final quality judgment,
or the one narrow adversarial role where a mistake can cause griefing, item
duplication, or save risk.

1. Hearthstead Orchestrator
2. Engineering Chief
3. Quality Chief
4. Multiplayer Abuse, Permissions & Griefing Specialist — trigger only

### Terra / high — twenty profiles

Use for bounded product, creative, engineering, and QA analysis plus the only
workspace-writing role.

- Product Chief and Creative Chief
- Gameplay & Progression
- Economy & Balance
- First-Session Comprehension
- UI & UX
- Animation & Audio
- Visual Art & World
- Narrative, Terminology & Localization
- Ambient Soundscape & Mix
- Icon Semantics
- Worker AI & Logistics
- Combat & Raid
- Platform & Performance
- World, Structure & Claim Integrity
- Dependency & Upgrade Compatibility
- Deterministic Tests
- Native Playtest
- Compatibility & Clean Install
- Implementation Worker

### Luna — four profiles

Use for bounded extraction, evidence inventories, matrices, hashes, and
repeatable consistency audits. These roles may supply facts but may never cast
a final council vote, adjudicate architecture, change product priority, hold
the write lease, or open the release gate.

- Release Identity — `medium`
- Playtest Signal — `high`
- Test Harness Credibility — `high`
- Player-Promise Evidence — `high`

## Dispatch policy

1. Classify the agenda by an exact specialist trigger.
2. Start with one specialist and the smallest evidence set that can answer it.
3. Use two only when the agenda has two distinct risks owned by different
   disciplines.
4. Use three only after the Orchestrator names three independently falsifiable
   risks. Three is the normal hard maximum for one wave.
5. Never dispatch a role merely because its discipline is adjacent.
6. Reuse accepted, fresh evidence unless source, candidate, version, or named
   evidence gap has changed.
7. Route extraction and evidence to Luna, domain interpretation and bounded
   implementation to Terra, and executive/P0/release adjudication to Sol.
8. Use `high` as the normal ceiling. `xhigh`, `max`, and `ultra` require an
   explicit exceptional escalation with a reason and a measurable expected
   benefit.
9. Normally one Implementation Worker runs. Up to three instances may write in
   parallel only under disjoint exact file leases, chief supervision and one
   integration owner. All leases end before QA.
10. At a trusted report of 65% weekly usage remaining, stop spawning and close
    the current safe checkpoint. At 60% or less, stop new work. When the host
    does not expose usage, record `UNKNOWN` rather than guessing.

## Escalation path

```text
Luna: collect facts
        ↓
Terra: interpret, design, or implement a bounded solution
        ↓
Sol: adjudicate P0 risk, executive conflict, or final release judgment
```

The receiving chief and Orchestrator remain accountable for checking whether
the evidence is fresh and sufficient. A cheaper model's output is evidence,
not automatic approval.

## Verification state

- Static council validation: passed for 1 Orchestrator, 4 chiefs, 22 read-only
  specialists, and 1 execution role.
- TOML parsing and validator compilation: passed.
- Static validation does not prove runtime profile binding, model availability,
  implementation quality, or player-facing behavior.
