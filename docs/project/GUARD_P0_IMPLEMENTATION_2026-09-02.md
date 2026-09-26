# Hearthstead Guard P0 Implementation

## Active write lease

- Lease: `HS-GUARD-P0-20260902-01`
- Owner and integration owner: `/root`
- Reviewers: Engineering chief and Quality chief
- Authorized outcome: make the Thursday first-raid defense truthful, safe, bounded, and demonstrably playable with one melee Guard and one tower Archer.
- In scope: owned-arrow authority on owner unload, exact raid authority for bodyguard mode, first-raid Guard order compatibility, bounded Archer line-of-sight recovery, real counter-damage wiring evidence, order-resume evidence, targeted tests and serialized QA.
- Out of scope: broad combat rebalance, new enemy classes, a second escort follower, new animations, new audio, full UI changes, or replacing persisted Guard orders.
- Tactical decision: one melee Guard temporarily screens the issuing player; the Archer keeps the Watchtower and provides ranged protection. This preserves readable roles and physical ammunition authority.
- QA route: only `bash tools/hearthstead-qa ...`, one suite at a time and no source edits while a suite is active.

## Acceptance contract

1. Any arrow claiming Hearthstead ownership fails closed when its ledger is malformed or its exact Archer owner cannot be resolved; it may not fall through to uncontrolled vanilla damage.
2. A loaded, exact Archer still blocks friendly fire, commits each valid contact once, and applies the 25 percent Skirmisher counter only at the real incoming-damage boundary. Captain and Brute remain unmodified by that counter.
3. Bodyguard mode starts only when `PendingRaid` equals a real active first, legacy-bridge, or recurring raid plan. A stale compatibility mirror cannot move a Guard.
4. First-raid readiness guarantees a serviceable Stand Guard and a serviceable Tower Archer; a Patrol-only Guard is not enough for the authored first-raid tutorial contract.
5. A target inside the Archer ring but behind cover causes a throttled physical reposition attempt. The Archer may not remain forever in a stop/cancel loop when a reachable firing position exists.
6. Combat still outranks escort, target allocation keeps ordinary enemies covered before dogpiling, and persistent Stand/Tower orders are never mutated by the raid overlay.
7. Automated evidence must cover the owner-unavailable arrow, stale `PendingRaid`, blocked line of sight, role allocation, counter boundary, and post-raid order recovery. Native visual and two-player proof remain separate.

## Evidence boundary

Static review and GameTests can prove authority, state transitions, target choice, physical damage, and bounded recovery. They cannot approve final combat feel, animation readability, sound mix, or real two-client synchronization without a fresh native session on the exact candidate.

## Accepted implementation checkpoint

- The Quality chief first rejected the checkpoint because three claims were not exercised through their real runtime boundaries: counter damage, an actually unresolved projectile owner, and authoritative raid completion. The package remained red until all three gaps were replaced with production-path evidence.
- Hearthstead-owned arrows now retain strict, self-contained identity when the Archer entity is unavailable. A valid-but-unresolved or malformed claim fails closed: no friendly health loss, no contact commit, and no Dexterity training.
- The Archer counter runs at the real NeoForge incoming-damage boundary. The gameplay test observes exactly `3.75` incoming damage against a non-captain Skirmisher and `3.0` against a Brute or captain, while also requiring real post-armour health loss, one contact commit, and no replay training.
- Bodyguard authority now requires an authored first-raid participant capture to be sealed (`participantsTracked`) or an authored recurring capture to be sealed (`participantsSealed`). Legacy migration bridges keep their explicit compatibility behavior. `PendingRaid` alone remains non-authoritative.
- The first-raid readiness contract now requires one serviceable Guard on `STAND_POST` and one serviceable Archer on `TOWER_POST`. Ordinary post-raid Guard validation may still accept a Patrol order.
- One elected melee Guard follows only the exact player who authored its Stand order and remains inside that order's leash. The Archer remains tower support. Patrol, reserve Stand and Tower orders are not mutated by the temporary raid overlay.
- An Archer still needs line of sight for first acquisition. Once a valid target has been seen, normal navigation may request a route around cover at most once every ten ticks, bounded by the existing vanilla unseen-target memory and the normal settlement/range/hostility authority checks.
- The escort regression now proves the negative unsealed state, sealed activation, player-leash return, re-election, terminal participant recording, real `RaidDirector.resolveIfOver`, `ACTIVE -> COMPLETED`, compatibility-mirror cleanup, return to post, and unchanged persisted orders.

### Final automated evidence before documentation-only lease close

- `bash tools/hearthstead-qa quick`: PASS, including 995/995 asset checks and animation validation with the three pre-existing warnings.
- `bash tools/hearthstead-qa gametest`: PASS, 629/629 required GameTests.
- `bash tools/hearthstead-qa behavior`: PASS, 2300 samples, 174 settlers, 257 diagnostic events, 0 findings.
- `bash tools/hearthstead-qa dedicated`: PASS, including boot, mod discovery, clean restart persistence, and no client-classloading leak.
- Quality chief final verdict: `ACCEPT CHECKPOINT` after the three runtime-evidence blockers were closed.
- Reviewed pre-documentation candidate: `hearthstead-0.2.0-g95425795c290-i2d07165fcc3c0d1d9640.jar`.
- Reviewed pre-documentation SHA-256: `c96b39beec4aa4ede4a62bfa091a69ff6dab9cc37daa3a82dbc192951e10f53`.

### Lease close

- Lease `HS-GUARD-P0-20260902-01` is released at this checkpoint.
- This closes the bounded Guard/Archer authority and first-raid combat-readiness package only. It does not claim native visual approval, final combat feel, real two-player synchronization, final audio, full UI approval, or complete demo/release readiness.
