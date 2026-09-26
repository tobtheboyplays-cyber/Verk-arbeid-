# 01: Server trust review of every client-to-server packet (CLOUD-02)

- **Source reviewed:** branch `claude/pensive-lamport-1i69ux` at `2f0bf73`. Its `hearthstead-neoforge/src` is byte-identical to `6dac68f`; the only later commits touch `tools/deploy`.
- **Method:** source review only. No test, GameTest, client or server was run for this report, and no packet was sent anywhere.
  - Four read-only reviewers each took a disjoint set of handlers.
  - Every finding marked **verified** below was re-read by me at the cited lines.
  - Findings marked **reported** come from a reviewer's reading and were not re-read line by line. Check them first.
- **Paths:** all relative to `hearthstead-neoforge/src/main/java/com/hearthstead/`.
- **Threat model:**
  - The Sunday server is a whitelisted co-op world for 2-4 players with PvP on.
  - Any client can be modified, so every payload field is attacker-controlled.
  - The settlement permission model is deliberate: whoever can reach it may manage it (`network/PlaqueNetwork.java:519-526`, `mayManage`). Equal rights inside the shared settlement are **not** reported as defects.
  - Handlers run on the server main thread (`context.enqueueWork`). Nothing is called a race unless a stale-state path is shown.

## Stale areas (the local main tree has newer, uncommitted work here)

These findings may no longer match the local tree. Re-check them there rather than trusting the line numbers:

| Local lane | Handlers in this report that it affects |
|---|---|
| Guildmaster replaces Mayor | `HearthNetwork` (`HearthMayorAction`: APPOINT, VIEW_SETTLER…), `SettlerNetwork` APPOINT, `DevelopmentNetwork` INSPECT_MAYOR / emblem shop |
| Courier batching and tidying | `EquipmentRequestListNetwork`, `StorageNetwork` |
| New tech-tree layout | `TechTreeNetwork` |
| Builder roof, upgrade and mixed-build fixes | `BuilderNetwork` |
| Named visitors, threats, town chat, barter and departing-actor fixes | `ConversationService` (C-06, P-10) |

## Summary

- **For a shared co-op settlement (the Sunday plan): no P1.** Nothing found lets a client duplicate items, create items or coins without paying, pay once and be delivered twice, or crash the server.
  - Every enum and id decoder is bounded with an UNKNOWN or null fallback.
  - Strings are capped at 64 or 128, and lists at 36.
  - Every mutating path found has a revision or compare-and-set fence plus all-or-nothing payment.
- **Three P2s apply even inside one shared settlement:**
  - **P-01:** a plaque can be unlinked instantly by spamming REFRESH.
  - **P-02:** research forces a chunk load far away.
  - **P-03:** builder actions have no rate limit and are expensive.
- **If players run separate, rival settlements, three items become P1:**
  - **C-01:** looting a captain's armoury.
  - **C-02:** summoning another settlement's settlers.
  - **C-03:** taking over a banner team.
  - None of these handlers asks which settlement the player belongs to, because no such concept exists yet.

## Inventory: 22 serverbound payloads and 3 container menus

Each row gives the payload record, its registration and its handler, what it can change, and the main guards.

| # | Payload (record) | Registered | Handler | Mutates | Main guards |
|---|---|---|---|---|---|
| 1 | `network/PlaqueAction.java:21` | `event/ModBusEvents.java:80` | `network/PlaqueNetwork.java:51` | bed claims, workers, employment, emblem delivery, survey | `isLoaded` before the block entity; exact server session; reach 8; revision; `mayBuild` except REFRESH |
| 2 | `network/StorageRequestPayload.java:15` | `ModBusEvents.java:92` | `network/StorageNetwork.java:31` | nothing (read-only) | open Hearth menu `stillValid`, or player inside the settlement radius; `hasChunkAt` |
| 3 | `network/SettlerActionPayload.java:22` | `ModBusEvents.java:104` | `network/SettlerNetwork.java:67` | dismiss/appoint, opens menus | loaded, alive entity; exact session; reach 24; `mayBuild`; hashed revision |
| 4 | `network/GuardOrderActionPayload.java:17` | `ModBusEvents.java:116` | `network/GuardOrderNetwork.java:34` | guard orders and patrol points (positions chosen by the server) | entity id plus UUID; reach 8; session; settlement id; revision; point cap |
| 5 | `network/EquipmentRequestListRequestPayload.java:12` | `ModBusEvents.java:176` | `network/EquipmentRequestListNetwork.java:32` | reconciles requests | courier, reach 8, session |
| 6 | `network/EquipmentRequestMovePayload.java:12` | `ModBusEvents.java:185` | `network/EquipmentRequestListNetwork.java:48` | reorders the courier queue | as #5, plus queue revision and membership |
| 7 | `network/HearthMayorAction.java:30` | `ModBusEvents.java:128` | `network/HearthNetwork.java:75` | mayor, journey skip, traveler admit (paid) or reject, raid readiness | exact open HearthMenu id, position and settlement plus `stillValid`; revision; one-shot readiness lease |
| 8 | `network/ResearchActionPayload.java:21` | `ModBusEvents.java:140` | `network/ResearchNetwork.java:41` | research state, study chests | reach 8 (**after** a snapshot is built, see P-02); revision; `mayBuild` |
| 9 | `network/DevelopmentActionPayload.java:13` | `ModBusEvents.java:152` | `network/DevelopmentNetwork.java:73` | nodes, upgrades, emblem purchase (treasury) | bound hearth in a loaded chunk; reach 8, or the live mayor within 5; exact revision; atomic `pay` |
| 10 | `network/BlessingActionPayload.java:19` | `ModBusEvents.java:164` | `network/BlessingNetwork.java:74` | offer spend, seal item | viewer session; reach 8; snapshot match; compare-and-commit plus delivery reservation |
| 11 | `network/TechTreeActionPayload.java:13` | `event/TechTreeNetworkRegistration.java:22` | `network/TechTreeNetwork.java:47` | tech state, treasury | loaded live hearth; reach 8; actor gate; revision; atomic pay; node id at most 64 characters |
| 12 | `network/BannerOrderActionPayload.java:10` | `event/BannerNetworkRegistration.java:17` | `network/BannerOrderNetwork.java:32` | banner team members, leaders, commands | holds a banner; ASSIGN within 6 with line of sight; 600-tick single-use session with an 8-block origin; targets within 52 |
| 13 | `network/FieldOrderRequestPayload.java:16` | `event/FieldOrderNetworkRegistration.java:22` | `settlement/guard/FieldOrders.java:310` | in-memory soldier orders | feature flag; 4-tick rate limit; whitelisted fields; target within 72 and loaded; enemy hostile with line of sight |
| 14 | `network/SummonRequestPayload.java:16` | `FieldOrderNetworkRegistration.java:29` | `settlement/summon/PlayerSummons.java:144` | summon entry; clears the field order | entity id plus UUID agree; bound and not a traveler; membership **or within 64** (see C-02) |
| 15 | `network/PatrolActionPayload.java:16` | `event/PatrolNetworkRegistration.java:23` | `settlement/guard/patrol/PatrolService.java:544` | patrol route book | `memberSettlement`; 2-tick rate limit except REFRESH; 6 routes; names capped and cleaned |
| 16 | `network/BuilderActionPayload.java:17` | `event/BuilderEvents.java:37` | `network/BuilderNetwork.java:60` | build jobs, designs, settings | player inside a settlement; planned cells loaded and within radius+16; design capture within 48 and at most 32³; **no rate limit** (P-03) |
| 17 | `network/WorkZoneActionPayload.java:14` | `ModBusEvents.java:199` | `settlement/workzone/WorkZoneService.java:208` | work-zone session and commit | session plus revision; scepter in hand; `mayBuild`; each corner loaded, in reach and ray-cast; zone at most 48×64×48; compare-and-set commit |
| 18 | `network/WorkZoneSelectionPayload.java:14` | `ModBusEvents.java:207` | `WorkZoneService.java:236` | selects a settler or workplace | settler by UUID in the same level, within reach |
| 19 | `network/RealmMapRequestPayload.java:25` | `event/RealmMapNetworkRegistration.java:24` | `network/RealmMapNetwork.java:126` | per-player subscription only | exact open HearthMenu plus `stillValid`; `isLoaded`; 4-tick immediate-send limit; lists capped |
| 20 | `conversation/net/ConvActionPayload.java:20` | `conversation/ConversationNetwork.java:34` | `conversation/ConversationService.java:329` | inventory, settlement costs, barter, relations | session owner; NPC within 12; revision consumed once; reply re-checked against the offered list; all-or-nothing costs and barter |
| 21 | `entity/combat/captain/CaptainPayloads.java:21` | `entity/combat/captain/CaptainNetwork.java:30` | `entity/combat/captain/CaptainService.java:41` | captain name, cosmetics, weapons; armoury chests; player inventory | live settler within 8; not a spectator; `isHero` (see C-01) |
| 22 | `finisher/FinisherPayloads.java:19` | `finisher/FinisherNetwork.java:28` | `finisher/FinisherService.java:286` | kills the victim, moves the executor | never players or bosses; same level; finish window open; config reach with abs(dy) < 2; line of sight; actor not busy |
| M1 | `menu/HearthMenu.java:22` | vanilla click packets | — | Hearth inventory | vanilla 4-block `stillValid`; one-way `quickMoveStack` that shrinks its source |
| M2 | `menu/SettlerInventoryMenu.java:36` | vanilla click packets | — | settler bag | alive, id, UUID and level check; reach 24 (P-13) |
| M3 | `menu/FishRackMenu.java:15` | vanilla click packets | — | fish rack | `stillValidBlockEntity`; four one-item slots, fish tag only |

## Coverage

| Area | Status |
|---|---|
| All 22 payload codecs (bounds, enum ordinals, string and list caps) | reviewed; no issue |
| Reach and distance on all 22 handlers | reviewed; gaps at P-02, P-08, P-12 and C-02/C-03 |
| Unloaded positions and chunk loads | reviewed; gaps at P-02, P-08, P-09 |
| Item, coin and payment presence; exactly-once delivery (plaque FIRE, development, blessing, tech tree, research, traveler admit, barter, costs, captain) | reviewed; no dupe found |
| Stale revision | reviewed; every mutating path is fenced, except patrol, which is fenced only for REMOVE_POINT (it has a rate limit and caps instead) |
| Rate limits and expensive per-packet work | reviewed; P-01, P-03, P-04, P-05 |
| Settlement membership | **not applicable by design:** equal-rights model, no owner concept. The consequences for rival settlements are listed under C-xx |
| Container menus (`stillValid`, `quickMoveStack`, `clickMenuButton`) | reviewed; no dupe; none overrides `clickMenuButton` |
| Item use and vanilla interactions (`use`/`useOn` on mod items and blocks) | **not reviewed:** outside payload scope, and a large surface. Candidate for a later pass |
| Local uncommitted Guildmaster, courier, UI and events code | **unresolved:** not available to me; marked stale above |

## Findings that apply in a shared co-op settlement

### P-01 (P2, verified): REFRESH spam skips the raid-damage grace period and unlinks a building instantly
- **Trigger:** a player with an open plaque session within 8 blocks sends REFRESH four times while the room fails its scan. For example, a raid has knocked out one wall block. Pressing the refresh button quickly does the same, so a normal player can trigger it too.
- **Effect:** `unlink` sets `building.valid = false` and releases residents. It also frees workers unless the raid-repair exemption applies (`block/PlaqueBlockEntity.java:1148-1176`).
- **Existing guard:** `GRACE_SURVEYS = 3` (`block/PlaqueBlockEntity.java:179`). Its javadoc intends about 30 s of sustained failure at the 10 s survey cadence (`:168-178`).
- **Why it fails:** the grace counts surveys, not time:
  ```java
  // block/PlaqueBlockEntity.java:1138-1141
  failedSurveys++;
  if (failedSurveys > GRACE_SURVEYS) {
  ```
  REFRESH runs a survey directly, and it is exempt from the `mayBuild` gate, so an adventure-mode visitor can do it too:
  ```java
  // network/PlaqueNetwork.java:106
  case REFRESH -> plaque.survey(level);
  ```
- **Suggested fix (scoped):** a REFRESH-triggered survey must not advance `failedSurveys`. Alternatively, key the grace to game time (the tick of the first failure plus `GRACE_SURVEYS * SURVEY_INTERVAL`). Optionally add a per-plaque REFRESH cooldown.

### P-02 (P2, verified): Research sends a snapshot before checking distance, forcing a far-away chunk load
- **Trigger:** a `ResearchActionPayload` whose `pos` lies inside any registered study in the dimension, sent from any distance.
- **Code:**
  - `SettlementManager.at` (`network/ResearchNetwork.java:46`) and `studyAt` (`:50`) resolve the study wherever it is.
  - The distance refusal still builds and sends a snapshot (`:54-58`), which calls `Research.haveCounts` (`:116`) and then `sourcesFor`.
  - `sourcesFor` falls back to the Hearth when the study's containers are not loaded:
    ```java
    // settlement/research/Research.java:377
    if (level.getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth) {
    ```
    There is no `hasChunkAt` before it, and `WarehouseIndex.containers` returns an empty list for unloaded chunks.
- **Effect:** each packet synchronously loads the remote Hearth chunk on the main thread, which is lag you can repeat on demand. It also leaks another settlement's research state and material counts.
- **Fix:** in `ResearchNetwork.handle`, check `level.isLoaded(pos)` and reach before resolving the study, and return **without** a snapshot when either fails. Add `level.hasChunkAt(settlement.center)` before `Research.java:377`.

### P-03 (P2, verified): Builder actions have no rate limit and each packet is expensive
- **Code:** the `switch` in `network/BuilderNetwork.java:60-121` has no throttle.
  - CATALOG walks every blueprint cell twice (`:172-187`, reported).
  - `BlueprintLibrary.all` rebuilds up to 64 player designs of up to 8192 cells on every call (`settlement/builder/BlueprintLibrary.java:131-138`).
  - VALIDATE and VALIDATE_UPGRADE run a full plan, and DECONSTRUCT walks the whole building box.
- **Effect:** one client can add hundreds of thousands of block operations per packet, every packet. This breaks the "all world scanning is budgeted" invariant.
- **Fix:** a per-player tick throttle like `FieldOrders` (4 ticks), and cache the catalog by library/design revision.

### P-04 (P2, reported; partly verified): A failed work-zone CONFIRM keeps the session open, so each retry costs up to 32k block reads
- **Trigger:** open a valid preview, invalidate it (for example, break the crop), then repeat CONFIRM.
- **Code:** `confirm` re-runs `validateCandidate`, which scans up to 32,768 block states (reported, `WorkZoneService.java:578`, `:642`). `reject` sends a snapshot and deliberately keeps the session: "keep the explicit rejection screen" (verified, `settlement/workzone/WorkZoneService.java:839-851`).
  - Each rejection also writes an INFO log line and telemetry (`:848-849`).
  - There is no per-player throttle in `handle` (`:208`).
- **Fix:** a per-player cooldown in `handle`/`handleSelection`, and rate-limited rejection logging.

### P-05 (P3, reported): Other per-packet work without a throttle
| Handler | Work per packet |
|---|---|
| Plaque REFRESH (`PlaqueNetwork.java:106`) | flood fill up to 2048 cells, plus fishing-grounds scan and occupancy count. A no-change survey keeps the same revision, so the packet can be replayed |
| Guard DEFEND_HEARTH / TOWER_POST (`GuardOrderNetwork.java:345`, `:375`) | up to dozens of synchronous `createPath` calls. A refusal does not bump the revision |
| StorageRequest (`StorageNetwork.java:64-81`) | refreshes every warehouse and reads every container |
| EquipmentRequestList refresh (`EquipmentRequestListNetwork.java:191-197`) | `reconcile` on every request |
| Patrol REFRESH (`PatrolService.java:550-553`) | returns before `rateOk` (`:554`) and builds a full snapshot |
| Captain CAPE/PLUME/LOADOUT (`CaptainService.java:61-63`, `:207-221`) | a broadcast to all trackers, plus a full armoury and warehouse slot scan |

**Fix:** one shared per-player/per-target cooldown helper, applied in each handler.

### P-06 (P3, verified for guard orders; reported for the others): Adventure-mode players are gated inconsistently
- Plaque, settler, research and tech-tree mutations require `mayBuild()`.
- These do not:
  - `GuardOrderNetwork.validateMutationContext` checks only alive and not spectator (`network/GuardOrderNetwork.java:123-124`);
  - courier queue reorder checks only not spectator (`EquipmentRequestListNetwork.java:279-281`, reported);
  - BUY_EMBLEM has no actor gate (`network/DevelopmentNetwork.java:127-133`; `assessEmblem` refuses only spectators, reported at `Development.java:571`).
- **Fix:** add `!player.mayBuild()` to these three.

### P-07 (P3, reported): Snapshots and names reach remote players
- Tech-tree viewers are removed only by CLOSE. `pushToViewers` checks only the level, not distance (`TechTreeNetwork.java:43`, `:77`, `:118-136`), so coin totals keep streaming to a client that never closes.
- DevelopmentNetwork sends a MAYOR_UNAVAILABLE snapshot, including available coins, with no distance check (`:97-103`, `:262-269`).
- Summon of a settler in another dimension returns its name (`PlayerSummons.java:157-160`).
- **Fix:** drop viewers who are out of reach; require reach before sending any snapshot.

### P-08 (P3, reported): Blessing CONFIRM loads the Hearth block entity before the reach check
`hasBoundHearth` (`BlessingNetwork.java:106`, `:284`) runs before `withinReach` (`:112`). A player who walked away within the 10-minute session can force that chunk to load.
**Fix:** check reach first, or guard with `hasChunkAt`.

### P-09 (P3, reported): Field-order formation slots can load chunks outside the checked centre
Only the target centre gets `hasChunkAt` (`FieldOrders.java:336`). `FieldTerrain.snap` and `standable` read slots up to about 30 blocks further out.
**Fix:** check `hasChunkAt` per slot. It is already bounded by the 4-tick rate limit.

### P-10 (P3, reported): Relation farming with one-item gifts (stale area)
BARTER_ACCEPT with one item given and nothing taken passes `BarterMath.acceptable` (`:24-27`), and every accept adds +1 relation (`ConversationService.java:558`). The cap is +100, which lowers ask prices and raises persuasion odds.
**Fix:** grant the +1 only when the given value is at least one coin, or once per session.

### P-11 (P3, reported): Player designs
- The 64-design limit is server-wide, not per player (`PlayerDesignSavedData.java:43`, `:107`).
- Designs with a null owner can be deleted by anyone (`BuilderNetwork.java:99`).
- Names keep `§` formatting codes (`PlayerDesignSavedData.java:156`).

### P-12 (P3, reported, UNSURE): EVICT of an unloaded worker skips the employment lifecycle
`building.workers.remove(settlerId)` (`PlaqueNetwork.java:179`) bypasses `Employment.dismiss`, so the authorization receipt and request rows go stale. It is not a dupe as reviewed, because FIRE still requires `workers.contains`.
**To confirm:** check whether any path re-adds a worker without spending an emblem.

### P-13 (P3, reported, design note): Settler bag stays open at 24 blocks
`SettlerInventoryMenu.java:151` uses a 24-block reach, so items can be moved through walls once the bag is open. Consider about 8.

### UNSURE items
- **Bed double-booking (reported).** ASSIGN counts only beds held by *loaded* residents (`PlaqueNetwork.java:496-500`), so a bed held by an unloaded resident may be handed out again. To confirm, check whether `BuildingManager.findFreeBed` reads the persisted claims.
- **BUY_EMBLEM payment failure (reported).** If a container refuses extraction, `purchaseEmblem` rethrows (`Development.java:688-693`). That disconnects only that player, and vanilla chests do not refuse.
- **Conversation cost before outcome (reported).** The conversation cost is taken before a world-event outcome can be refused ("moment has passed"). No live path was found; check `BruteTollEvent`.

## Only if players run separate, rival settlements

These all follow from the equal-rights model: no handler knows which settlement a player belongs to. They are **not** defects for one shared co-op settlement. With rival settlements on a PvP server they are real theft and griefing paths, and the first three are P1.

### C-01 (P1 if rival settlements, verified): The Captain panel moves another settlement's armoury into the requester's inventory
- **Guard:** the only check is a live settler within 8 blocks and not a spectator (`entity/combat/captain/CaptainService.java:43-45`).
- **What LOADOUT does:**
  - Takes the new weapons from **any** ARMOURY or WAREHOUSE container of the *captain's* settlement, in any loaded chunk: `find(level, player, captain.settlement(), …)` at `:161`, container walk at `:207-221`.
  - Gives the captain's old kit to the **requester**: `give(player, oldMain); give(player, oldOff);` at `:187-188`.
- **Effect:** cycling through loadouts drains a rival's armoury and disarms their captain. There is no switch cooldown.
- **Fix:** require the player to stand in the captain's claim until a membership hook exists; take kit only from containers within reach; return old kit to the source container.

### C-02 (P1 if rival settlements, verified): Summon any settler within 64 blocks
- **Code:** membership is an OR:
  ```java
  // settlement/summon/PlayerSummons.java:170-171
  boolean member = home != null && home.id.equals(settlement.id)
      || player.distanceToSqr(settler) <= NEAR_SETTLER * NEAR_SETTLER;
  ```
- **Effect:** a player pulls an enemy guard off its post for up to 2 minutes, and `FieldOrders.release(settler)` (`:179`) wipes its defence order in the middle of a raid.
- **Fix:** require `home` to be the settler's settlement; drop the bare 64-block branch or limit it to settlements the player is inside.

### C-03 (P1 if rival settlements, verified): Banner team takeover, and dimension-wide command
- **The check:** `resolveSettlement` counts a player as "nearby" when any of these holds:
  ```java
  // settlement/guard/BannerTeams.java:60-63
  boolean nearby=player.blockPosition().distSqr(s.center)<=96*96
      || player.getUUID().equals(s.bannerTeams.leader(color.getId()))
      || s.settlers.stream().anyMatch(... player.distanceToSqr(member)<=64*64);
  ```
  - within 96 blocks of the settlement centre;
  - already the team's leader, which skips distance entirely;
  - within 64 blocks of a team member.
- **What follows:**
  - TAKEOVER calls `BannerTeamBook.claim`, which overwrites the leader (`BannerTeamBook.java:31-33`), even while the rightful leader is online.
  - ASSIGN can take an unbannered guard within 6 blocks (`BannerTeams.java:32-43`).
  - After that the leader-bypass lets FOLLOW, MOVE and HOLD sessions be opened from anywhere in the dimension.
- **Fix:** drop the leader-anywhere bypass; require hearth reach for TAKEOVER and ASSIGN until a membership hook exists.

### C-04 to C-07 (P2 if rival settlements, reported)
- **C-04, field orders:** they obey the nearest settlement whose command reach covers the player (`FieldOrders.java:611-626`). A raider standing in your reach can order HOLD_FIRE to your archers.
- **C-05, staffing and orders:**
  - A rival within reach can EVICT, ASSIGN or FIRE through the plaque. FIRE delivers the Job Emblem to the **actor** (`PlaqueNetwork.java:263-271`).
  - They can also DISMISS or APPOINT settlers (`SettlerNetwork.java:122-136`), issue guard orders (`GuardOrderNetwork.java:144-215`) and reorder courier queues.
- **C-06, conversations (stale area):** CHOOSE answers world events and raid parley for the victim's settlement and pays from its stores (`ConversationService.java:442`, `CostPayer.java:75-80`). This skips the `insideArea` check that the direct route enforces (`WorldEventVisitors.java:221` vs `:266-277`).
- **C-07, work zones (UNSURE):** there is deliberately no radius gate (`WorkZoneService.java:503-506`), so a zone may sit inside another settlement's claim. Confirm whether farmer and lumber goals check the claim.

### Design note (PvP)
The finisher makes the executor immune to damage while the execution runs, including damage from rival players (`FinisherService.java:1071`). It is bounded and intended, but it needs a PvP decision.

## Recommended order (for main Claude to verify against the current tree)

1. **P-01.** Small and self-contained. It also fixes an innocent failure: a player refreshing a plaque during a raid.
2. **P-02.** Small: two guards and one early return.
3. **P-03, P-04, P-05.** One shared per-player cooldown helper.
4. **Before any world with rival settlements:** a single `SettlementAccess.mayAct(player, settlement)` hook, starting with "inside the claim", applied to C-01, C-02, C-03, then C-04 to C-06.
5. **P-06 to P-13** when convenient.
