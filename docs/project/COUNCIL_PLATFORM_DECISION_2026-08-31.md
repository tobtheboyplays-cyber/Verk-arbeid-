# Hearthstead Council — platform decision

**Decision ID:** `HS-PLATFORM-ORDER-20260831-V1`  
**Agenda:** `PLATFORM_TARGET`  
**Status:** Binding for the active friends-demo  
**Decision authority:** `HS-ORCHESTRATOR-V1`  
**Recorded:** 2026-08-31, Europe/Oslo

## Executive order

The active Hearthstead demo is frozen on:

- Minecraft `1.21.1`;
- NeoForge `21.1.248`;
- Java `21`.

NeoForge `21.1.249` must not enter the active UI candidate before the exact
`21.1.248` native Windows evidence is complete and frozen. It may later be
tested as a separate maintenance candidate under the same gate.

The `1.20.1` / Forge backport and the Minecraft `26.2` / NeoForge port are
rejected for the active demo. Either target may be reconsidered only through a
new evidence-backed platform order. A future frozen friend-modpack matrix may
reopen this decision if it proves a concrete must-have blocker with no workable
`1.21.1` / NeoForge build or acceptable substitute.

Platform approval is not UI approval, compatibility certification, demo
approval, or release approval.

## Orchestrator authority

The Hearthstead Orchestrator is Tobias's executive proxy and the highest role
in the council. Product, Creative, Engineering, and Quality report to the
Orchestrator. The Orchestrator may disagree with or overrule any or all chiefs,
must publish the evidence for an override, and issues the next bounded work
order. Missing evidence, data loss, item duplication, save corruption,
client/server desynchronization, unsafe licensing, or security failure cannot
be voted into a pass.

## Frozen evidence

- Packet: `docs/project/COUNCIL_PLATFORM_PACKET_2026-08-31.md`
- Packet ID: `HS-PLATFORM-20260831-V2`
- Packet SHA-256:
  `f0f3f9cae5a7cef9f1c4f8e3f9299419cb1ba6a1cbc80147f1a844eec1a3c25f`
- Existing implementation target: Minecraft `1.21.1`, NeoForge `21.1.248`,
  Java `21`.
- Prior exact-JAR native p95: same world `9.37 ms`, vanilla Creative
  `9.26 ms`, Settler `50.59 ms`, Hearth `48.80 ms`, Development `47.22 ms`.
- The prior shared panel path was estimated at `14,300–18,864` immediate
  sprite draws per frame on the measured screens.
- The replacement renderer is designed to use at most nine raw-texture slices
  per resizable surface. Controller `quick` passed; native improvement remains
  unproven.
- The frozen catalogue comparison found Minecraft `1.21.1` to be the strongest
  compared NeoForge ecosystem. Minecraft `1.20.1` / Forge had the largest raw
  catalogue, but requires both a Minecraft backport and a loader migration.

## Actual chief debate

All responses were prompt-bound to their project profiles and the exact frozen
packet hash.

### Round 1

- `P1` — Product: approve candidate P1 to preserve player-facing demo momentum
  and the strongest practical NeoForge ecosystem.
- `C1` — Creative: approve candidate P1; neither migration automatically
  improves Hearthstead's UI, animation, sound, or raids.
- `E1` — Engineering: approve candidate P1; preserve causal A/B attribution and
  test `21.1.249` later as one isolated variable.
- `Q1` — Quality: approve candidate P1 for `PLATFORM_TARGET`; keep UI and
  release claims blocked pending exact-JAR native, visual, gameplay,
  multiplayer, reconnect, and identity evidence.

### Round 2

- Product accepted Engineering's sequencing and Quality's broader release
  evidence; it retained only a concrete future friend-mod blocker as a reason
  to reopen the target.
- Creative accepted the same sequencing and explicitly required native
  presentation, scaling, clipping, input, animation, and sound review.
- Engineering challenged any reading that an unfrozen friend-modpack matrix
  blocks today's platform decision; it accepted all exact-JAR native and
  continuity gates.
- Quality agreed that the missing friend matrix does not block today's target;
  it retained the release block and exact-candidate proof requirements.

**Final chief result:** unanimous approval of Minecraft `1.21.1` / NeoForge
`21.1.248` for `PLATFORM_TARGET`. There was no final chief dissent.

## Orchestrator decision

`HS-ORCHESTRATOR-V1` followed the unanimous target recommendation and
strengthened its sequencing:

1. Freeze `21.1.248` throughout the native UI experiment.
2. Do not mix `21.1.249` into the active candidate.
3. Compare the prior exact baseline and the new exact candidate on the same
   Windows machine, world, settings, resolution, GUI scale, and paths.
4. If a gate fails, repair the smallest root cause inside the already
   authorized UI patch, create a newly identified JAR, and rerun the failed
   slice plus shared regressions.

## Current candidate identity

- Source branch: `integration/hearthstead-demo-recovery-20260830`
- Source HEAD: `95425795c2909fea7f5b94bc3397e420f8e683e3`
- Candidate JAR:
  `hearthstead-neoforge/build/libs/hearthstead-0.2.0-g95425795c290-ia5801c7b8b204186bb20.jar`
- Candidate SHA-256:
  `783a352a9d65cad0dddd252c5aac98a22d449f5f89b4cf4b02a197e353fb8ba0`
- Candidate size: `3,905,038` bytes.
- Deterministic status: controller `quick` passed.
- Native status: not yet measured; no completion claim is authorized.

The worktree contains an explicit, recorded delta beyond HEAD. The final
evidence bundle must bind that exact source state to the JAR rather than relying
on HEAD alone.

## Next work order

Freeze and install the current candidate, then execute a controlled native
Windows exact-JAR A/B package covering:

- same-world and vanilla Creative controls;
- Settler, Hearth, and Development screens;
- renderer-path and maximum-nine-slices evidence;
- layout, hover, click, keyboard, escape/back, scroll, pan, zoom, resizing, and
  reopen behavior;
- affected gameplay interactions;
- multiplayer synchronization;
- disconnect and reconnect continuity;
- exact source-to-JAR-to-runtime identity.

At least three comparable captures per condition are required. The same-world
and vanilla controls may not regress by more than 10% p95 without a proven
external cause. Every changed screen must show a clear improvement and reach
native p95 at or below `16.67 ms`; the Orchestrator did not authorize a
narrative waiver.

## Release gate

**BLOCKED.** The candidate remains blocked until Quality records an explicit
pass for identity, native performance, visual/input behavior, affected
gameplay, multiplayer, and reconnect; Creative records a presentation pass;
and Product records a friends-demo interaction pass. Passing this UI order
advances the candidate to the next bounded demo-readiness gate and does not by
itself prove the entire Hearthstead demo complete.

## Council session registry

- Orchestrator: `/root/platform_orchestrator` | `HS-ORCHESTRATOR-V1` |
  prompt-bound profile | completed
- Product: `/root/platform_product_chief` | `HS-PRODUCT-CHIEF-V1` |
  prompt-bound profile | completed
- Creative: `/root/platform_creative_chief` | `HS-CREATIVE-CHIEF-V1` |
  prompt-bound profile | completed
- Engineering: `/root/platform_engineering_chief` |
  `HS-ENGINEERING-CHIEF-V1` | prompt-bound profile | completed
- Quality: `/root/platform_quality_chief` | `HS-QUALITY-CHIEF-V1` |
  prompt-bound profile | completed

