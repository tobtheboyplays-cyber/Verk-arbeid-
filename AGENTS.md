# Exported continuation snapshot

Read HANDOFF_START_HERE.md first for this branch and evidence status. The original shared-workspace rules follow.

# READ FIRST — shared workspace with Claude (25 Sep 2026)

This folder is a working COPY shared by Claude (lead) and Codex. Before anything else read
`COORD/README.md`, `COORD/DECISIONS.md`, `COORD/OWNERSHIP.md` and `COORD/TASKS-CODEX.md`.
Those override the older rules below wherever they conflict (Root/Tester roles, canonical QA
lane, name "Hearthstead", raid timing). Talk to Claude via `COORD/to-claude.md`.

# Hearthstead active project rules

## Short startup — owner requested 16 September 2026

Read this file, PROJECT_STATE/START_HERE.md and PROJECT_STATE/CURRENT_TASK.md.
That is the complete default startup. Do not preload archived handovers, every
plan, the whole quality ledger, all skills or past task histories.
For new design read PROJECT_STATE/REQUIREMENTS.md; use FOLDER_MAP.md to locate
only the relevant code/evidence. Dated documents are history unless current
instructions explicitly adopt them. Current user instructions take precedence.

## Goal and scope

Deliver a coherent, playable settlement from new-world founding through real
work/transport/economy, first raid and saved aftermath. Fix existing failures
first, especially workers, animations and Tavern. Six demo professions only:
Courier, Lumberer, Farmer, Guard, Archer, Innkeeper; Mayor is civilian leadership.
Preserve approved Lumber motion and good existing implementations.
All current player-facing systems remain in scope: assets, sound/motion, AI,
navigation, UI, progression, stability, performance and actual co-op.
After core acceptance, implement small complete additions from the existing
long-term plan and fitting ideas. Production, defense and village life/trade
are all wanted. No extra chain/profession may displace unfinished core repair.

Accepted targets: first proper raid around 60–90 minutes; prepared defenders can
handle ordinary attacks; defeat is meaningful and recoverable; residents manage
routine logistics under player priorities; grounded medieval tone with restrained
goblins/humor;3–4 players sharing a settlement. Full short contract: REQUIREMENTS.md.
Tavern includes physical meals/seats, useful Innkeeper, limited song/atmosphere;
Coins is the one physical currency, with reachable manual income before paid labor.

## Platform, permissions and preservation

- Active: hearthstead-neoforge, Minecraft 1.21.1 / NeoForge 21.1.248 / Java 21.
  Legacy hearthstead Fabric 1.20.1 is frozen. Platform/core identity changes require
  owner approval.
- English game content, implementation and project documentation; concise
  Norwegian updates to Tobias. Stable translation keys; no Norwegian parity rule.
- Owner16 September: use remaining included weekly allowance, superseding old
 75% used/other reserve limits. Check usage between batches and about every two
 minutes; checkpoint before exhaustion. No purchases, resets, payment-limit
 changes or quota circumvention.
- Full PC use for this project and controlled native Minecraft tests was explicitly
  authorized16 September. Preserve actual worlds and unrelated apps; use test saves.
- Preserve dirty work, identifiers and functionality. Back up affected uncommitted
  files before substantial edits; test migrations. No irreversible user-data loss,
  external publish/push/distribution or new recurring monitoring without authority.
- Never claim a model/effort, app goal or automation changed unless it actually did.

## Work and delegation

Define the next player-visible acceptance before implementing. Inspect the exact
failure and relevant files; classify product, fixture, dependency or evidence
failure. Do not manufacture changes or rerun passed checks without a reason.
Root owns design, shared files, integration and one serialized runtime queue.
Use bounded builder/tester lanes for substantive independent work. Each agent gets
an exact deliverable, one owner, allowed files, read-only inputs, acceptance and
stop condition. No overlapping writers, duplicated audits, recursive swarm or
automatic full council. Relevant agent/skill contracts are consulted on demand.
Record bounded write ownership and rollback; release all source/asset/QA writers
before a suite. During freeze workers may prepare external isolated proposals.
Read the reference-curator profile before a new visual batch; reuse good existing
references and create original assets. Do not copy protected code/assets/layouts.
Use UTF-8 without BOM and LF for shell/scenario files. Verify actual directive
syntax, units, actor identity, coordinates and log windows before running a scenario.

## QA and completion

Only build/test/run/install entry: bash tools/hearthstead-qa <command>.
Before QA, read .agents/skills/hearthstead-council/references/qa-contract.md once.
No direct Gradle, helper substitution or overlapping Minecraft/QA/source changes.
Use package for incremental build, then proportionate relevant verification;
package alone is not gameplay, visual, audio, performance or release approval.
If cleanup is uncertain use reap check; mutate cleanup only for proved owned jobs.
Never weaken assertions, skip failures, fake receipts or increase timeouts without
diagnosing. Validate intended spec changes explicitly.
Keep VERIFIED / FAILED / BLOCKED / UNTESTED / NOT APPLICABLE evidence separate.
Match exact source/JAR and fresh runtime; inspect screenshots/motion, listen to
audio and measure performance. Idle clients do not prove multiplayer interactions.
Full release still requires unchanged full twice + gate + sealed native
release-client-gate. No subjective premium/fun claim from automated tests.
Detailed requirements: docs/project/QUALITY_STANDARD.md and JOB_STANDARD.md;
their historical certification/model/language statements are not current proof.

## Persistent record and communication

PROJECT_STATE/CURRENT_TASK.md is the only active queue: concise current outcome,
exact recent evidence, open defects and next action. Replace stale state; archive
long history instead of appending another diary. Keep default startup under about
2000 words. PROJECT_STATE/REQUIREMENTS.md is the short owner contract; update the
affected detailed design section when decisions change.
docs/project/ROADMAP.md holds coverage; POST_DEMO_MASTERPLAN.md holds full future
design; hearthstead-neoforge/docs/HEARTHSTEAD_QUALITY_LEDGER.md holds evidence.
These are targeted lookups, not startup reading. Historical material remains
available but never overrides current evidence.
Give short Norwegian start/progress/result updates. Deliver work and its actual
verification limits; no promises of background work without an active process.
