package com.hearthstead.network;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.item.BlessingSealItem;
import com.hearthstead.settlement.BlessingPresentation;
import com.hearthstead.settlement.PendingPlayerDeliveryLedger;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.journey.JourneyTransactionIds;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.BlessingState;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;

/** Server authority for opening and committing a shared Blessing offer. */
public final class BlessingNetwork {

    /** Hearth-use reach, measured from the player's position to its centre. */
    private static final double REACH_SQUARED = 8.0 * 8.0;
    /** Long enough for deliberate reading, bounded enough to reject old UI. */
    private static final long SESSION_TTL_TICKS = 20L * 60L * 10L;

    /**
     * Server-scoped, server-authored proof that a player explicitly used a
     * real Hearth. Weak server keys prevent integrated-server restarts from
     * retaining sessions; every open/commit also prunes disconnected, moved,
     * expired and physically invalid viewers.
     */
    private static final Map<MinecraftServer, Map<UUID, ViewerSession>> VIEWERS =
        new WeakHashMap<>();

    /**
     * Sends the choice screen only for a live, nearby settlement with a real
     * unspent offer. This method is intentionally not called by raid victory:
     * the player chooses when they next use the Hearth.
     */
    public static boolean openFor(ServerPlayer player, Settlement settlement) {
        if (player == null) {
            return false;
        }
        prune(player.serverLevel().getServer(), null);
        forget(player);
        if (!isAuthoritativeInCurrentLevel(player, settlement)
            || !hasBoundHearth(player.serverLevel(), settlement)
            || !withinReach(player, settlement)
            || !hasSelectableOffer(settlement.blessingState)) {
            return false;
        }
        UUID sessionId = UUID.randomUUID();
        BlessingSnapshotPayload opening = snapshot(settlement, sessionId,
            BlessingSnapshotPayload.Delivery.OPEN,
            BlessingSnapshotPayload.Feedback.NONE, null);
        remember(player, opening);
        send(player, opening);
        return true;
    }

    /** Revalidates dimension, settlement identity, reach and offer revision. */
    public static void handle(ServerPlayer player, BlessingActionPayload action) {
        if (player == null || action == null) {
            return;
        }
        // CLOSE is deliberately side-effect-free beyond releasing the exact
        // screen session. In particular, an old CLOSE cannot prune, replace or
        // remove a newer session that happens to show the same shared offer.
        if (action.kind() == BlessingActionPayload.Kind.CLOSE) {
            removeExact(player, action.settlementId(), action.sessionId());
            return;
        }

        ServerLevel level = player.serverLevel();
        MinecraftServer server = level.getServer();
        // Do not prune this player before producing the precise TOO_FAR or
        // UNAVAILABLE result that releases an already-waiting client screen.
        prune(server, player.getUUID());
        ViewerSession session = sessionFor(player, action.settlementId(),
            action.sessionId());
        if (session == null) {
            reject(level, null, "invalid_session");
            sendUnavailable(player, action, null);
            return;
        }

        Settlement settlement = SettlementManager.byId(level, action.settlementId());
        if (settlement == null) {
            reject(level, null, "settlement_unavailable");
            removeExact(player, action.settlementId(), action.sessionId());
            sendUnavailable(player, action, session);
            return;
        }
        if (!hasBoundHearth(level, settlement)) {
            reject(level, settlement, "hearth_unbound");
            sendTerminal(player, session, settlement,
                BlessingSnapshotPayload.Feedback.UNAVAILABLE, null);
            return;
        }
        if (!withinReach(player, settlement)) {
            reject(level, settlement, "out_of_range");
            sendTerminal(player, session, settlement,
                BlessingSnapshotPayload.Feedback.TOO_FAR, null);
            return;
        }

        // A decoded future/invalid action kind is never allowed to borrow the
        // CONFIRM path. Keep an active exact session recoverable, while an
        // already-terminal one is consumed into an inert result.
        if (action.kind() != BlessingActionPayload.Kind.CONFIRM) {
            if (session.terminalReplay() != null) {
                removeExact(player, session.settlementId(), session.sessionId());
                send(player, snapshot(settlement, session.sessionId(),
                    BlessingSnapshotPayload.Delivery.RESULT,
                    BlessingSnapshotPayload.Feedback.UNAVAILABLE, null));
            } else if (hasSelectableOffer(settlement.blessingState)) {
                sendUpdate(player, session, settlement,
                    BlessingSnapshotPayload.Feedback.INVALID_CHOICE, null);
            } else {
                sendTerminal(player, session, settlement,
                    BlessingSnapshotPayload.Feedback.UNAVAILABLE, null);
            }
            return;
        }

        // Another viewer may commit while this player's action from the exact
        // same OPEN snapshot is already in flight. The broadcast has closed the
        // client screen, but retaining one bounded, non-actionable terminal
        // receipt lets that late packet converge on the same precise co-op
        // result instead of overwriting it with a generic UNAVAILABLE result.
        // Only the server-authored revision/serial and a known Blessing choice
        // may consume this one-shot receipt. Forged, older and unknown payloads
        // fail closed, and this path can neither touch the ledger nor deliver.
        if (session.terminalReplay() != null) {
            boolean exactConsumedOffer = actionMatchesSnapshot(action,
                session.lastServerSnapshot()) && action.choice().isPresent();
            removeExact(player, session.settlementId(), session.sessionId());
            send(player, exactConsumedOffer
                ? session.terminalReplay()
                : snapshot(settlement, session.sessionId(),
                    BlessingSnapshotPayload.Delivery.RESULT,
                    BlessingSnapshotPayload.Feedback.UNAVAILABLE, null));
            return;
        }

        // The compare-and-commit ledger prevents double spending globally,
        // while this second boundary proves that this player was actually
        // shown the exact offer identity being submitted. Without it, a
        // modified client with a still-live Hearth session could guess a
        // newer revision/serial that the server never sent to that player.
        if (!actionMatchesSnapshot(action, session.lastServerSnapshot())) {
            reject(level, settlement, "stale_offer_revision");
            if (hasSelectableOffer(settlement.blessingState)) {
                sendUpdate(player, session, settlement,
                    BlessingSnapshotPayload.Feedback.STALE, null);
            } else {
                sendTerminal(player, session, settlement,
                    BlessingSnapshotPayload.Feedback.OTHER_PLAYER_CHOSE, null);
            }
            return;
        }

        BlessingId blessing = action.choice().orElse(null);
        if (blessing == null) {
            reject(level, settlement, "invalid_choice");
            sendUpdate(player, session, settlement,
                BlessingSnapshotPayload.Feedback.INVALID_CHOICE, null);
            return;
        }

        int revisionBefore = settlement.blessingState.revision();
        int spentBefore = settlement.blessingState.spent();
        int offerSerialBefore = settlement.blessingState.offerSerial();
        ItemStack seal = BlessingSealItem.stackFor(blessing);
        UUID deliveryId = JourneyTransactionIds.forRevision(
            "blessing_seal_delivery", settlement.id, player.getUUID(),
            action.revision() + 1L);
        PendingPlayerDeliveryLedger.Reservation reservation =
            PendingPlayerDeliveryLedger.reservation(level, deliveryId, player,
                seal);
        BlessingState.CommitResult result = reservation == null
            ? BlessingState.CommitResult.DELIVERY_BACKLOG
            : settlement.blessingState.compareAndCommit(action.revision(),
                action.offerSerial(), blessing, reservation);
        BlessingSnapshotPayload.Feedback feedback = feedbackFor(
            result, settlement.blessingState.offerSerial());
        if (result == BlessingState.CommitResult.ACCEPTED) {
            // Persist the offer spend and exact source-owned output before any
            // best-effort hand/inventory/world materialization.
            SettlementManager.data(level).setDirty();
            PendingPlayerDeliveryLedger.DeliveryResult delivery =
                settlement.blessingState.deliverPending(level, player,
                    deliveryId);
            if (delivery.changed()) {
                SettlementManager.data(level).setDirty();
            }
            player.displayClientMessage(Component.translatable(
                "hearthstead.message.blessing_delivery."
                    + delivery.outcome().id(), Component.translatable(
                    "hearthstead.blessing." + blessing.id() + ".name")), true);
            BlessingPresentation.sealIssued(level, settlement, player, blessing);
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.BLESSING_OFFER_COMMITTED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.items(settlement.id,
                    "offer:" + offerSerialBefore, revisionBefore,
                    settlement.blessingState.revision(), spentBefore,
                    settlement.blessingState.spent(),
                    BuiltInRegistries.ITEM.getKey(seal.getItem()).toString(),
                    0, 1, 1, "seal_delivery_" + delivery.outcome().id()));
            broadcastCommittedChoice(level, settlement, player, blessing);
            return;
        }

        reject(level, settlement,
            "commit_" + result.name().toLowerCase(java.util.Locale.ROOT));

        if (feedback.allowsChoice()
            && hasSelectableOffer(settlement.blessingState)) {
            sendUpdate(player, session, settlement, feedback, blessing);
        } else {
            // A consumed/nonexistent offer is shared state. Every still-valid
            // viewer of this settlement gets a terminal co-op result.
            broadcastTerminal(level, settlement,
                feedback == BlessingSnapshotPayload.Feedback.INVALID_CHOICE
                    ? BlessingSnapshotPayload.Feedback.UNAVAILABLE
                    : BlessingSnapshotPayload.Feedback.OTHER_PLAYER_CHOSE,
                null);
        }
    }

    private static BlessingSnapshotPayload.Feedback feedbackFor(
            BlessingState.CommitResult result, int freshOfferSerial) {
        return switch (result) {
            case ACCEPTED -> BlessingSnapshotPayload.Feedback.ACCEPTED;
            case STALE -> freshOfferSerial > 0
                ? BlessingSnapshotPayload.Feedback.STALE
                : BlessingSnapshotPayload.Feedback.OTHER_PLAYER_CHOSE;
            case NO_OFFER -> BlessingSnapshotPayload.Feedback.OTHER_PLAYER_CHOSE;
            case MAXED -> BlessingSnapshotPayload.Feedback.MAXED;
            case INVALID -> BlessingSnapshotPayload.Feedback.INVALID_CHOICE;
            case DELIVERY_BACKLOG ->
                BlessingSnapshotPayload.Feedback.DELIVERY_BACKLOG;
        };
    }

    private static boolean isAuthoritativeInCurrentLevel(ServerPlayer player,
                                                           Settlement settlement) {
        return player != null && settlement != null
            && SettlementManager.byId(player.serverLevel(), settlement.id) == settlement;
    }

    private static boolean withinReach(ServerPlayer player, Settlement settlement) {
        return player.distanceToSqr(settlement.center.getX() + 0.5,
            settlement.center.getY() + 0.5, settlement.center.getZ() + 0.5)
            <= REACH_SQUARED;
    }

    /** The packet UUID must still name the Hearth physically bound here. */
    private static boolean hasBoundHearth(ServerLevel level, Settlement settlement) {
        if (!(level.getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth)) {
            return false;
        }
        return settlement.id.equals(hearth.getSettlementId());
    }

    private static boolean hasSelectableOffer(BlessingState state) {
        return state != null && state.offerSerial() > 0
            && !state.quarantined();
    }

    /** Package-visible for the wire-contract GameTests. */
    static boolean actionMatchesSnapshot(BlessingActionPayload action,
                                         BlessingSnapshotPayload snapshot) {
        return action != null && snapshot != null
            && action.kind() == BlessingActionPayload.Kind.CONFIRM
            && snapshot.delivery() != BlessingSnapshotPayload.Delivery.RESULT
            && snapshot.offerSerial() > 0
            && Objects.equals(action.settlementId(), snapshot.settlementId())
            && Objects.equals(action.sessionId(), snapshot.sessionId())
            && action.revision() == snapshot.revision()
            && action.offerSerial() == snapshot.offerSerial();
    }

    /** Package-visible multiplayer regression seams; no client input is trusted. */
    static boolean hasViewerSessionForTest(ServerPlayer player,
                                           UUID settlementId) {
        ViewerSession session = liveSessionFor(player);
        return session != null && session.settlementId().equals(settlementId);
    }

    static boolean hasExactViewerSessionForTest(ServerPlayer player,
                                                UUID settlementId,
                                                UUID sessionId) {
        return sessionFor(player, settlementId, sessionId) != null;
    }

    static UUID viewerSessionIdForTest(ServerPlayer player, UUID settlementId) {
        ViewerSession session = liveSessionFor(player);
        return session != null && session.settlementId().equals(settlementId)
            ? session.sessionId() : null;
    }

    static BlessingSnapshotPayload.Feedback viewerRefusalForTest(
            ServerPlayer viewer, Settlement settlement) {
        ViewerSession session = liveSessionFor(viewer);
        if (session != null && !session.settlementId().equals(settlement.id)) {
            session = null;
        }
        return session == null ? BlessingSnapshotPayload.Feedback.UNAVAILABLE
            : broadcastRefusal(viewer, session, settlement,
                hasBoundHearth(viewer.serverLevel(), settlement));
    }

    private static BlessingSnapshotPayload snapshot(
            Settlement settlement, UUID sessionId,
            BlessingSnapshotPayload.Delivery delivery,
            BlessingSnapshotPayload.Feedback feedback,
            BlessingId feedbackBlessing) {
        BlessingState state = settlement.blessingState;
        return new BlessingSnapshotPayload(settlement.id, sessionId,
            boundedName(settlement.name), state.revision(), state.offerSerial(),
            state.issuedCount(BlessingId.WARDEN_OATH),
            state.issuedCount(BlessingId.HEARTHWARD),
            state.issuedCount(BlessingId.THORNED_ROADS), delivery, feedback,
            feedbackBlessing == null ? -1 : feedbackBlessing.wireId());
    }

    private static void sendUpdate(ServerPlayer player, ViewerSession session,
                                   Settlement settlement,
                                   BlessingSnapshotPayload.Feedback feedback,
                                   BlessingId feedbackBlessing) {
        BlessingSnapshotPayload update = snapshot(settlement, session.sessionId(),
            BlessingSnapshotPayload.Delivery.UPDATE, feedback,
            feedbackBlessing);
        rememberUpdate(player, session, update);
        send(player, update);
    }

    private static void sendTerminal(ServerPlayer player, ViewerSession session,
                                     Settlement settlement,
                                     BlessingSnapshotPayload.Feedback feedback,
                                     BlessingId feedbackBlessing) {
        removeExact(player, session.settlementId(), session.sessionId());
        send(player, snapshot(settlement, session.sessionId(),
            BlessingSnapshotPayload.Delivery.RESULT, feedback,
            feedbackBlessing));
    }

    /**
     * Sends the winner an accepted result and every other legitimate viewer a
     * co-op result. If another earned offer exists the delivery is UPDATE so
     * the screen can briefly show feedback and then expose that next offer.
     * Otherwise it is a terminal RESULT: the winner is removed immediately,
     * while each valid other viewer retains one one-shot receipt for an action
     * that was already in flight from the exact consumed OPEN snapshot.
     */
    private static void broadcastCommittedChoice(ServerLevel level,
                                                 Settlement settlement,
                                                 ServerPlayer winner,
                                                 BlessingId blessing) {
        MinecraftServer server = level.getServer();
        Map<UUID, ViewerSession> viewers = VIEWERS.get(server);
        if (viewers == null) {
            return;
        }
        boolean followUp = hasSelectableOffer(settlement.blessingState);
        boolean hearthValid = hasBoundHearth(level, settlement);
        // Snapshot before any reach/TTL cleanup. A connected player may still
        // have this screen open even after walking away; silently pruning that
        // session would leave a false actionable offer on their client.
        for (Map.Entry<UUID, ViewerSession> entry : Map.copyOf(viewers).entrySet()) {
            ViewerSession session = entry.getValue();
            if (!session.matches(level.dimension(), settlement.id)) {
                continue;
            }
            ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
            if (viewer == null
                || session.playerIdentity().get() != viewer) {
                removeViewer(server, entry.getKey());
                continue;
            }
            BlessingSnapshotPayload.Feedback refusal = broadcastRefusal(
                viewer, session, settlement, hearthValid);
            if (refusal != null) {
                send(viewer, snapshot(settlement, session.sessionId(),
                    BlessingSnapshotPayload.Delivery.RESULT, refusal, null));
                removeViewer(server, entry.getKey());
                continue;
            }
            boolean acceptedHere = viewer.getUUID().equals(winner.getUUID());
            BlessingSnapshotPayload update = snapshot(settlement,
                session.sessionId(),
                followUp ? BlessingSnapshotPayload.Delivery.UPDATE
                    : BlessingSnapshotPayload.Delivery.RESULT,
                acceptedHere ? BlessingSnapshotPayload.Feedback.ACCEPTED
                    : BlessingSnapshotPayload.Feedback.OTHER_PLAYER_CHOSE,
                acceptedHere ? blessing : null);
            if (followUp) {
                rememberUpdate(viewer, session, update);
            } else if (!acceptedHere) {
                rememberTerminal(viewer, session, update);
            } else {
                removeViewer(server, entry.getKey());
            }
            send(viewer, update);
        }
    }

    private static void broadcastTerminal(ServerLevel level,
                                          Settlement settlement,
                                          BlessingSnapshotPayload.Feedback feedback,
                                          BlessingId feedbackBlessing) {
        MinecraftServer server = level.getServer();
        Map<UUID, ViewerSession> viewers = VIEWERS.get(server);
        if (viewers == null) {
            return;
        }
        boolean hearthValid = hasBoundHearth(level, settlement);
        for (Map.Entry<UUID, ViewerSession> entry : Map.copyOf(viewers).entrySet()) {
            ViewerSession session = entry.getValue();
            if (!session.matches(level.dimension(), settlement.id)) {
                continue;
            }
            ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
            if (viewer != null
                && session.playerIdentity().get() == viewer) {
                BlessingSnapshotPayload.Feedback refusal = broadcastRefusal(
                    viewer, session, settlement, hearthValid);
                send(viewer, snapshot(settlement, session.sessionId(),
                    BlessingSnapshotPayload.Delivery.RESULT,
                    refusal == null ? feedback : refusal,
                    refusal == null ? feedbackBlessing : null));
            }
            removeViewer(server, entry.getKey());
        }
        forgetSettlement(server, level.dimension(), settlement.id);
    }

    /** Precise terminal reason for a connected but no-longer-valid viewer. */
    private static BlessingSnapshotPayload.Feedback broadcastRefusal(
            ServerPlayer viewer, ViewerSession session, Settlement settlement,
            boolean hearthValid) {
        if (!viewer.serverLevel().dimension().equals(session.dimension())
            || expired(session, viewer.serverLevel().getGameTime())
            || !hearthValid) {
            return BlessingSnapshotPayload.Feedback.UNAVAILABLE;
        }
        return withinReach(viewer, settlement) ? null
            : BlessingSnapshotPayload.Feedback.TOO_FAR;
    }

    /** Always releases a matching waiting screen, even if its settlement died. */
    private static void sendUnavailable(ServerPlayer player,
                                        BlessingActionPayload action,
                                        ViewerSession session) {
        BlessingSnapshotPayload basis = session == null
            ? null : session.lastServerSnapshot();
        send(player, new BlessingSnapshotPayload(action.settlementId(),
            action.sessionId(),
            basis == null ? "" : basis.settlementName(),
            basis == null ? Math.max(0, action.revision()) : basis.revision(),
            basis == null ? Math.max(0, action.offerSerial()) : basis.offerSerial(),
            basis == null ? 0 : basis.wardenOathIssued(),
            basis == null ? 0 : basis.hearthwardIssued(),
            basis == null ? 0 : basis.thornedRoadsIssued(),
            BlessingSnapshotPayload.Delivery.RESULT,
            BlessingSnapshotPayload.Feedback.UNAVAILABLE, -1));
    }

    private static void remember(ServerPlayer player,
                                 BlessingSnapshotPayload serverSnapshot) {
        MinecraftServer server = player.serverLevel().getServer();
        VIEWERS.computeIfAbsent(server, ignored -> new HashMap<>()).put(
            player.getUUID(), new ViewerSession(serverSnapshot.sessionId(),
                serverSnapshot.settlementId(),
                player.serverLevel().dimension(), new WeakReference<>(player),
                player.serverLevel().getGameTime(), serverSnapshot, null));
    }

    /** Replaces state within one OPEN without extending that session's TTL. */
    private static void rememberUpdate(ServerPlayer player,
                                       ViewerSession session,
                                       BlessingSnapshotPayload serverSnapshot) {
        MinecraftServer server = player.serverLevel().getServer();
        VIEWERS.computeIfAbsent(server, ignored -> new HashMap<>()).put(
            player.getUUID(), new ViewerSession(session.sessionId(),
                session.settlementId(), session.dimension(),
                session.playerIdentity(), session.openedAtGameTime(),
                serverSnapshot, null));
    }

    /**
     * Retains only the exact actionable snapshot plus its immutable terminal
     * answer. This is not a second offer: {@link #handle} consumes it before
     * compare-and-commit, and the ordinary per-player TTL/logout/open cleanup
     * keeps the receipt bounded even when no late packet arrives.
     */
    private static void rememberTerminal(ServerPlayer player,
                                         ViewerSession actionableSession,
                                         BlessingSnapshotPayload terminal) {
        MinecraftServer server = player.serverLevel().getServer();
        VIEWERS.computeIfAbsent(server, ignored -> new HashMap<>()).put(
            player.getUUID(), new ViewerSession(actionableSession.sessionId(),
                actionableSession.settlementId(),
                actionableSession.dimension(), actionableSession.playerIdentity(),
                actionableSession.openedAtGameTime(),
                actionableSession.lastServerSnapshot(), terminal));
    }

    private static ViewerSession sessionFor(ServerPlayer player,
                                             UUID settlementId,
                                             UUID sessionId) {
        ViewerSession session = liveSessionFor(player);
        if (session == null
            || !session.settlementId().equals(settlementId)
            || !session.sessionId().equals(sessionId)) {
            // Packet identity mismatches are never authority to remove the
            // actual current session. This is what makes delayed CLOSE and
            // CONFIRM packets harmless after a same-offer reopen.
            return null;
        }
        return session;
    }

    private static ViewerSession liveSessionFor(ServerPlayer player) {
        Map<UUID, ViewerSession> viewers = VIEWERS.get(
            player.serverLevel().getServer());
        if (viewers == null) {
            return null;
        }
        ViewerSession session = viewers.get(player.getUUID());
        if (session == null) {
            return null;
        }
        // A same-UUID replacement may already own this slot. The superseded
        // object may inspect but must never delete its successor's session.
        if (session.playerIdentity().get() != player) {
            return null;
        }
        if (!session.dimension().equals(player.serverLevel().dimension())
            || expired(session, player.serverLevel().getGameTime())) {
            removeViewer(player.serverLevel().getServer(), player.getUUID());
            return null;
        }
        return session;
    }

    /** Removes only the session named by this exact player connection. */
    private static boolean removeExact(ServerPlayer player, UUID settlementId,
                                       UUID sessionId) {
        if (player == null || settlementId == null || sessionId == null) {
            return false;
        }
        MinecraftServer server = player.serverLevel().getServer();
        Map<UUID, ViewerSession> viewers = VIEWERS.get(server);
        ViewerSession session = viewers == null ? null
            : viewers.get(player.getUUID());
        if (session == null
            || session.playerIdentity().get() != player
            || !session.settlementId().equals(settlementId)
            || !session.sessionId().equals(sessionId)) {
            return false;
        }
        removeViewer(server, player.getUUID());
        return true;
    }

    private static boolean expired(ViewerSession session, long gameTime) {
        long age = gameTime - session.openedAtGameTime();
        return age < 0L || age > SESSION_TTL_TICKS;
    }

    /** Event-safe cleanup for a player leaving before another Blessing action. */
    public static void forget(ServerPlayer player) {
        if (player == null) {
            return;
        }
        MinecraftServer server = player.serverLevel().getServer();
        Map<UUID, ViewerSession> viewers = VIEWERS.get(server);
        ViewerSession session = viewers == null
            ? null : viewers.get(player.getUUID());
        // UUIDs survive reconnects, object identity does not. A delayed logout
        // event from the old connection must never erase the replacement
        // connection's newly opened offer or terminal receipt.
        if (session != null
            && session.playerIdentity().get() == player) {
            removeViewer(server, player.getUUID());
        }
    }

    /** Clears the server key eagerly; the weak key remains only a final guard. */
    public static void clear(MinecraftServer server) {
        if (server != null) {
            VIEWERS.remove(server);
        }
    }

    static void rememberForTest(ServerPlayer player,
                                BlessingSnapshotPayload snapshot) {
        remember(player, snapshot);
    }

    static int viewerSessionCountForTest(MinecraftServer server) {
        Map<UUID, ViewerSession> viewers = VIEWERS.get(server);
        return viewers == null ? 0 : viewers.size();
    }

    private static void removeViewer(MinecraftServer server, UUID playerId) {
        Map<UUID, ViewerSession> viewers = VIEWERS.get(server);
        if (viewers == null) {
            return;
        }
        viewers.remove(playerId);
        if (viewers.isEmpty()) {
            VIEWERS.remove(server);
        }
    }

    private static void forgetSettlement(MinecraftServer server,
                                         ResourceKey<Level> dimension,
                                         UUID settlementId) {
        Map<UUID, ViewerSession> viewers = VIEWERS.get(server);
        if (viewers == null) {
            return;
        }
        viewers.values().removeIf(session ->
            session.matches(dimension, settlementId));
        if (viewers.isEmpty()) {
            VIEWERS.remove(server);
        }
    }

    /**
     * Prunes only facts independently revalidated by the server. A live
     * connected viewer is always sent a terminal snapshot before removal, so
     * cleanup can never strand an actionable screen on the client.
     */
    private static void prune(MinecraftServer server, UUID exemptPlayer) {
        Map<UUID, ViewerSession> viewers = VIEWERS.get(server);
        if (viewers == null) {
            return;
        }
        for (Map.Entry<UUID, ViewerSession> entry
                : Map.copyOf(viewers).entrySet()) {
            if (entry.getKey().equals(exemptPlayer)) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            ViewerSession session = entry.getValue();
            if (player == null
                || session.playerIdentity().get() != player) {
                removeViewer(server, entry.getKey());
                continue;
            }
            BlessingSnapshotPayload.Feedback refusal = null;
            Settlement settlement = null;
            if (!player.serverLevel().dimension().equals(session.dimension())
                || expired(session, player.serverLevel().getGameTime())) {
                refusal = BlessingSnapshotPayload.Feedback.UNAVAILABLE;
            }
            ServerLevel level = server.getLevel(session.dimension());
            if (refusal == null) {
                settlement = level == null ? null
                    : SettlementManager.byId(level, session.settlementId());
                if (settlement == null || !hasBoundHearth(level, settlement)) {
                    refusal = BlessingSnapshotPayload.Feedback.UNAVAILABLE;
                } else if (!withinReach(player, settlement)) {
                    refusal = BlessingSnapshotPayload.Feedback.TOO_FAR;
                }
            }
            if (refusal == null) {
                continue;
            }
            BlessingSnapshotPayload terminal = settlement == null
                ? terminalFromSession(session, refusal)
                : snapshot(settlement, session.sessionId(),
                    BlessingSnapshotPayload.Delivery.RESULT,
                    refusal, null);
            send(player, terminal);
            removeViewer(server, entry.getKey());
        }
    }

    private static BlessingSnapshotPayload terminalFromSession(
            ViewerSession session, BlessingSnapshotPayload.Feedback feedback) {
        BlessingSnapshotPayload basis = session.lastServerSnapshot();
        return new BlessingSnapshotPayload(basis.settlementId(),
            session.sessionId(),
            basis.settlementName(), basis.revision(), basis.offerSerial(),
            basis.wardenOathIssued(), basis.hearthwardIssued(),
            basis.thornedRoadsIssued(), BlessingSnapshotPayload.Delivery.RESULT,
            feedback, -1);
    }

    private record ViewerSession(UUID sessionId,
                                 UUID settlementId,
                                 ResourceKey<Level> dimension,
                                 WeakReference<ServerPlayer> playerIdentity,
                                 long openedAtGameTime,
                                 BlessingSnapshotPayload lastServerSnapshot,
                                 BlessingSnapshotPayload terminalReplay) {
        private boolean matches(ResourceKey<Level> expectedDimension,
                                UUID expectedSettlement) {
            return dimension.equals(expectedDimension)
                && settlementId.equals(expectedSettlement);
        }
    }

    private static String boundedName(String name) {
        if (name == null) {
            return "";
        }
        return name.length() <= BlessingSnapshotPayload.MAX_SETTLEMENT_NAME_LENGTH
            ? name : name.substring(0,
                BlessingSnapshotPayload.MAX_SETTLEMENT_NAME_LENGTH);
    }

    private static void send(ServerPlayer player, BlessingSnapshotPayload snapshot) {
        PacketDistributor.sendToPlayer(player, snapshot);
    }

    private static void reject(ServerLevel level, Settlement settlement,
                               String reason) {
        int revision = settlement == null ? 0
            : settlement.blessingState.revision();
        int spent = settlement == null ? 0
            : settlement.blessingState.spent();
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.AUTHORITY_REJECTED,
            AuthorityTelemetry.Result.REJECTED,
            AuthorityTelemetry.Fields.state(
                settlement == null ? null : settlement.id,
                "blessing_offer", revision, revision, spent, spent, reason));
    }

    private BlessingNetwork() {
    }
}
