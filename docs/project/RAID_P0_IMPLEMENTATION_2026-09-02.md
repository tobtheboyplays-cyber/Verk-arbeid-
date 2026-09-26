# Hearthstead Raid P0 Implementation

## Active write lease

- Lease: `HS-RAID-P0-20260902-01`
- Owner: `/root`
- Supervisors: Engineering chief and Quality chief
- Integration owner: `/root`
- Authorized outcome: make the authored first raid impossible to skip by sleeping, expose truthful server-authoritative encounter status, and preserve exact participant identity through save/restart.
- In scope: raid participant records, first/recurring raid ledgers, server-only encounter status, sleep eligibility events, exact localization, targeted deterministic/GameTest coverage.
- Out of scope: broad combat rebalance, new enemy classes, animation replacement, full HUD redesign, audio replacement, unrelated cleanup.
- QA owner: `/root`, serialized exclusively through `bash tools/hearthstead-qa`.
- Release condition: lease closes only after the targeted build/test gate has passed or the work has been rolled back to a safe state.

## Acceptance contract

1. A due or active Hearthstead raid in a player's dimension blocks entering sleep and continuing sleep; a warning-only night remains sleepable.
2. The first raid seals exactly one captain, one non-captain Brute, and three non-captain Skirmishers.
3. The encounter status is derived from the persisted sealed roster and terminal ledger, never from a client-side entity scan.
4. Captain health is shown only when the exact captain entity is loaded and authoritative; follower counts remain exact through unload/restart.
5. Chunk unload is not a death. A terminal count changes only after definitive death or explicit destruction.
6. No duplicate status bars, client/server double updates, or stale bars survive logout, dimension change, resolution, or server stop.
7. Legacy active saves without an exact role roster fail closed: no fabricated class counts or captain identity.

## Frozen authority decisions after Quality rejection

- **Migration:** the nested first-raid roster schema is optional and versioned. A pre-schema UUID-only ACTIVE first or recurring raid remains valid for completion and sleep denial, but receives no role/count bossbar. No loaded-AABB observation and no random replay may upgrade it. New first raids must seal the complete roster before activation is considered presentable.
- **Recurring scope:** recurring raids participate in dimension-wide sleep denial. Their role roster and encounter bar are deliberately deferred; current recurring random role selection cannot be reconstructed safely from UUIDs after restart.
- **Captain unavailable:** an alive captain whose exact entity is unloaded or not yet rehydrated produces no bar for that player. The controller never substitutes 0%, 100%, or another entity's health. A rostered terminal captain may truthfully show 0% with remaining follower counts.
- **Player arbitration:** candidates are active, roster-valid first raids in the player's dimension within `settlement.radius + 32` blocks. Choose the smallest squared distance to the settlement center; ties use the unsigned lexical settlement UUID. One player is attached to at most one bar.
- **Lifecycle key:** dimension + settlement UUID + authored first-attack night. A completed or replaced lifecycle cannot reuse an existing transient event.
- **Corruption:** an ACTIVE lifecycle with roster/schema integrity loss remains a sleep blocker but receives no bossbar. Completion continues to use the existing UUID terminal ledger; presentation never becomes completion authority.
- **Cleanup:** reconcile membership once per second; remove stale members on range/dimension change and remove all controller state on level unload/server stop.

## Evidence boundary

Source inspection and tests can prove state-machine, persistence, and event-policy behavior. A fresh native multiplayer raid is still required to prove final HUD placement, visual readability, combat feel, audio mix, and real two-client synchronization.

## Accepted implementation checkpoint

- Checkpoint accepted by the Quality chief after review of the final bounded diff.
- Persisted first-raid participants now carry authoritative role and captain metadata for newly begun raids. Pre-roster ACTIVE saves remain valid completion/sleep authority without fabricated role data or HUD presentation.
- Due and active raids now deny entering or continuing sleep on the server. Warning-only nights remain sleepable.
- A server-owned native boss bar presents truthful captain health and remaining first-raid composition from the sealed roster and terminal ledger. It is removed on resolution, logout, dimension/range loss, level unload, and server stop.
- The migration regression proves that a pre-roster SCHEDULED lifecycle is promoted only when a genuinely new raid begins, survives two loads, reaches `HELD`, and remains reward-eligible after every exact participant becomes terminal.

### Final automated evidence

- `bash tools/hearthstead-qa quick`: PASS, including 995/995 asset validation and animation validation with the three pre-existing warnings.
- `bash tools/hearthstead-qa gametest`: PASS, 629/629 required GameTests.
- `bash tools/hearthstead-qa dedicated`: PASS, including boot, found-settlement state, clean restart persistence, and client-classloading guard.
- Prepared Windows server candidate: `hearthstead-0.2.0-g95425795c290-if138bfe218ddc088410b.jar`.
- Installed/current SHA-256: `cef9af7700672cabd922c273b4aa220259cfc61f0b8d5e740d248f66e2bf67ba`.
- Install receipt: `C:\Users\tobia\HearthsteadServer\evidence\mod-install-20260901T225111274Z.json`.
- Server status recheck proves `exactSourceInstalled=True`.

### Lease close

- Lease `HS-RAID-P0-20260902-01` is released at this checkpoint.
- This closes the bounded raid-status/sleep/HUD implementation only. It does not claim native visual approval, real two-player synchronization, guard combat quality, audio quality, or full demo/release readiness.
