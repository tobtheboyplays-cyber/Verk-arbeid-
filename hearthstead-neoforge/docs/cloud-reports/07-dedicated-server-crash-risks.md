# 07: Dedicated-server and client-class crash-risk sweep (CLOUD-08)

**Source reviewed:** branch `claude/pensive-lamport-1i69ux` at `93a0c9c`. Its `hearthstead-neoforge/src` is byte-identical to `6dac68f`.

**Scope:** could a NeoForge 21.1.248 dedicated server crash, or refuse to start, because it loads client-only classes? And could a client crash or be kicked because of null or stale state when it joins, respawns or changes dimension?

All paths are relative to `hearthstead-neoforge/src/main/java/com/hearthstead/`.

## Evidence, kept separate by kind

| Kind | What was done | Result |
|---|---|---|
| **Executed (sandbox)** | Built the mod jar from this source in a private build folder. Put it in a **fresh, throwaway** NeoForge 21.1.248 dedicated server with no other mods, no players and a new world, and started it through `tools/deploy/start-server.ps1` with a 3 GB heap. | Started in 29 s. Bannerhold was found and constructed ("kindling the fire"), 1 conversation graph was loaded, and all 3 dimensions logged `STATE_LOAD_SUMMARY … item_conserved=true`. It ticked about 2.5 min, took one scheduled console backup, and stopped with exit 0. |
| | Searched the server's `debug.log` for `invalid dist`, `Attempted to load class`, `NoClassDefFound` and `ClassNotFound`. | **0 matches.** The only WARN/ERROR lines were 2 vanilla "Assets URL … unexpected schema" warnings. |
| **Prior executed** | The GameTest server runs a dedicated-server dist. The captain, combat, finisher and blessing batches (70/70 on `6dac68f`, per earlier PR #5 reports) boot the same mod classes on that dist. | Consistent with the above. |
| **Source review** | Static sweep of every client/common boundary listed below, plus a read-only reviewer on join, respawn and logout state. I re-read every finding marked *verified*. | See the findings. |
| **Not done** | No client ever joined the sandbox server. So join, respawn and dimension change are **source review only**, not executed. There was no load test, and nothing ran on Windows or a real host. | — |

Jar identity note: the jar is named `…gda67b842ca14…` because the private worktree's git HEAD is `da67b84`. Its `src` was verified identical to this commit with `diff -rq`.

## Inventory of entry points and boundaries

| Boundary | Count | How it is isolated | Status |
|---|---|---|---|
| Classes under `client/` | 222 files | Never imported outside `client/`. `grep '^import (net.minecraft.client\|com.mojang.blaze3d\|net.neoforged.neoforge.client)'` outside `client/` returns 0 files, and there are 0 fully qualified `net.minecraft.client.` references outside `client/` | OK |
| `com.hearthstead.client.*` referenced from common code | 31 references | All are either in client-bound payload lambdas or in three items. The payload lambdas are wrapped in `ModBusEvents.runClientOnly(FMLEnvironment.dist, () -> () -> …)` (`event/ModBusEvents.java:217-229`); on a dedicated server the supplier is never evaluated, so the client class is never resolved | OK |
| Client-bound payload registrations | 39 `playToClient` | Every handler that touches client code goes through `runClientOnly`: ModBusEvents, Banner, PickupNotice, FieldOrder, Fx, Revive, Patrol, Builder, Ambient, RealmMap, TechTree, Conversation, Captain and Finisher registrations | OK |
| Items calling client code | 3 | `item/BuildersPlanItem.java:29-31`, `item/ResourceScrollItem.java:76-77` and `item/HandbookItem.java:31-32` call `ClientHooks` only inside `if (level.isClientSide)`, which a dedicated server never enters. The call is a lazily resolved `invokestatic` | OK (executed boot + source) |
| Event subscribers | 55 classes declare `Dist.CLIENT` | Every `@EventBusSubscriber` under `client/` declares `Dist.CLIENT`. No client-only event (render, GUI, input, client tick, screen, key mappings, renderers, particles, client network) is subscribed outside `client/` | OK |
| `@OnlyIn` | 0 uses | — | OK (nothing to misuse) |
| `Minecraft.getInstance()` outside `client/` | 0 calls | The only hit is a javadoc comment (`network/HearthMayorSnapshot.java:36`) | OK |
| Mixins | 5 (`hearthstead.mixins.json`) | All in the common `mixins` array, and all target common classes: CraftingMenu, CrafterBlock, CrafterMenu, Slot, SmithingMenu. There is no client mixin array, and none is needed | OK (executed boot) |
| Configs | SERVER and CLIENT specs (`Hearthstead.java:27-28`) | `HearthsteadClientConfig` is never read outside `client/`. `HearthsteadServerConfig` getters check `SPEC.isLoaded()` and fall back to a default (for example `:443`, `:744-745`), so client-side reads such as `client/weapon/WeaponArmPoses.java:70` and `client/weapon/SettlerBowHold.java:108` cannot throw before the config syncs | OK |
| Mod constructor | `Hearthstead.java` | Only config registration and `DeferredRegister.register` calls. No dist branch and no client class | OK |
| Static initialisers | reviewed sample | Pure data: TechTree gates, Employment/Production maps, `TagKey.create`, vanilla block references, shapes. `event/worldevent/WorldEventDirector.java:75-87` constructs 11 event handlers in a static block. A reviewer could not rule out a `DeferredHolder.get()` there, but the class loads with its subscriber at mod construction and the sandbox boot above succeeded, so it is **resolved by execution** | OK |
| Commands | 10 registration sites (sampled) | The debug, QA and demo trees require permission level 2–4 (for example `command/HearthsteadCommand.java:24-75`, `event/worldevent/WorldEventCommand.java:35-42`, `conversation/ConversationCommand.java:62`) | OK, with an operational note |

## Findings

No finding can crash a dedicated server or keep it from starting. No P1.

### D-01 (P2, verified; stale area): the client keeps conversation state after a disconnect, hiding the HUD on the next join

- **Code:** `client/conversation/ConversationClient.java:20-22` holds `private static ConvStatePayload state; private static int session = -1; private static int npcId = -1;`.
- **No reset on logout:** there is no `ClientPlayerNetworkEvent.LoggingOut` handler anywhere in `client/conversation/` (checked with grep). `ConversationScreen.removed()` (`client/conversation/ConversationScreen.java:157`) does not call `endLocal()`; only `onClose()` (`:170`), which is Esc, reaches it.
- **Trigger:** a timeout, kick, server stop or lost connection while a conversation or barter screen is open. The server ends its side (`ConversationEvents.java:98`, reported), but the client never receives the close payload.
- **Consequence on the next join:**
  - `ConversationClient.active()` is still true, so `client/conversation/ConversationHud.java:28` keeps cancelling the crosshair, hotbar, health, food and XP layers indefinitely.
  - `ConversationCamera` may re-frame an NPC that reuses the old entity id (reported).
  - There is no crash. Recovery means starting and ending a new conversation, or restarting the client.
  - This matters for Sunday because a friend who drops mid-conversation comes back "blind".
- **Smallest fix:** a `@SubscribeEvent` on `ClientPlayerNetworkEvent.LoggingOut` in `ConversationClient` that calls `endLocal()` and clears the markers, plus a `ConversationCamera.reset()` that snaps back to vanilla instead of easing.
- **Stale:** the local named-visitors and conversation lanes may already touch this file. Check against the current tree.

### D-02 (P3, reported): other client state not reset on logout (cosmetic)

- **Conversation markers.** `client/conversation/ConversationMarkers.java:70-73` keeps an overlay on the entity id. Its 60-tick age guard passes when the new world's game time is lower than the old one. D-01's fix covers this.
- **Work Scepter preview.** `client/workzone/WorkZoneClient.java:36` (`current`, verified) is cleared only on a dimension mismatch. The old box is drawn in a new world that has the same dimension id.
- **Builder catalog cache.** `BuilderClientState` keeps `catalog`, `wantThumb` and similar fields. The logout handler (`BuilderPlacement.java:266-269`) clears only placement and sites, so a pending thumbnail can stay "loading" on the next server.
- **Fix:** one `LoggingOut` reset per class.

### D-03 (hardening, reported; no concrete throw found)

- **Where:** these login and player-tick handlers run without the try/catch that `TechTreeEvents.onLogin` uses (`:37-41`): `PendingPlayerDeliveryEvents.java:20-52`, `StarterHandbookEvents.java:19-23,82-88` and `ReviveEvents.java:79-84`.
- **Risk:** a throw at login would kick that player, and a throw in the player tick would crash the server tick.
- **What was traced:** the reviewer followed each path (`Development.retryPending`, `PendingPlayerDeliveryLedger.retry`, `BlessingState.retryPending`, `StarterHandbookDelivery`) and found them null- and quarantine-guarded.
- **Suggestion:** wrap each body in the same try/catch-and-log pattern.

### Operational note (not a code defect)

- QA and demo commands (`/hearthstead … demo`, `battleqa`, `/hstalk`, world-event `start`, …) exist in the production jar behind op level 2–4.
- The owner will be op on Sunday. Don't run QA or demo commands on the live world; several spawn or rewrite state for tests.

## Coverage and limits

| Area | Status |
|---|---|
| Server boot and ticking with the mod on a dedicated dist | **executed** (sandbox, no players) |
| Client classes reachable from common code, `@OnlyIn`, `Minecraft.getInstance`, mixin sides, config sides, subscriber sides | source-reviewed, and boot-confirmed where it applies |
| Client payload handlers on join, respawn and dimension change (null `player`/`level`/`screen`) | source-reviewed: no unguarded dereference found. **Not executed**, because no client joined |
| Client state across logout | source-reviewed: D-01 and D-02 |
| Server login, logout, respawn and dimension handlers | source-reviewed: guarded map removals, no respawn handler. `EntityJoinLevelEvent` handlers filter out players |
| Static initialisers | sampled plus boot; not exhaustively read |
| Commands | sampled, not exhaustive |
| Local uncommitted lanes (Guildmaster, courier, UI, town chat, visitors) | **unresolved:** not available to me. The equivalent code in the local tree must be re-checked with the same greps |

**How to re-run the key checks on the current local tree (read-only):**

```
grep -rlE "^import (net\.minecraft\.client|com\.mojang\.blaze3d|net\.neoforged\.neoforge\.client)" --include=*.java src/main/java | grep -v /client/
grep -rn "com\.hearthstead\.client\." --include=*.java src/main/java | grep -v /client/   # each hit must sit inside runClientOnly or an isClientSide branch
grep -rn "@OnlyIn\|Minecraft.getInstance()" --include=*.java src/main/java | grep -v /client/
```

Then boot a throwaway dedicated server with the built jar and check `logs/debug.log` for `invalid dist|Attempted to load class`.

## Recommendation

- **Before Sunday:** D-01. It is small and client-only.
- **When convenient:** D-02 and D-03.
- **Best final check:** one real co-op join on the actual server, with Tobias plus one friend: join, die and respawn, visit the Nether, disconnect during a conversation, then rejoin. It is the only thing this report could not execute.
