package com.hearthstead.network;

import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-owned proof that a player has one exact inspection sheet open.
 *
 * <p>There is deliberately at most one entry per player: Minecraft can show
 * only one screen at a time, so retaining a list would create stale viewers
 * rather than useful state. Refresh is event-driven by an APPLIED Blessing;
 * there is no tick scan. CLOSE, logout and server stop release entries
 * eagerly, while the TTL and live-world checks are a bounded safety net for
 * a lost close packet or an abruptly disconnected client.
 */
public final class InspectionViewers {
    static final long SESSION_TTL_TICKS = 20L * 60L * 20L;
    private static final double REACH_SQUARED = 8.0D * 8.0D;

    /** MinecraftServer identity is the lifecycle boundary, not its equals(). */
    private static final Map<MinecraftServer, Map<UUID, ViewerSession>> VIEWERS =
        new IdentityHashMap<>();

    static UUID openSettler(ServerPlayer player, SettlerEntity settler) {
        UUID sessionId = UUID.randomUUID();
        remember(player, new ViewerSession(System.identityHashCode(player),
            player.serverLevel().dimension(), player.serverLevel().getGameTime(),
            sessionId, Kind.SETTLER, System.identityHashCode(settler),
            settler.getId(), settler.getUUID(), null, null));
        return sessionId;
    }

    static UUID openPlaque(ServerPlayer player, PlaqueBlockEntity plaque) {
        UUID sessionId = UUID.randomUUID();
        remember(player, new ViewerSession(System.identityHashCode(player),
            player.serverLevel().dimension(), player.serverLevel().getGameTime(),
            sessionId, Kind.PLAQUE, System.identityHashCode(plaque), 0, null,
            plaque.getBlockPos().immutable(), normaliseBuilding(plaque.buildingId())));
        return sessionId;
    }

    /**
     * Authorizes one exact, still-live settler sheet and refreshes its TTL.
     * A forged target/session never destroys the legitimate current sheet;
     * world replacement, expiry or dimension drift invalidates it eagerly.
     */
    static boolean authorizeSettler(ServerPlayer player, SettlerEntity settler,
                                    UUID expectedSettlerId,
                                    UUID expectedSessionId) {
        Map<UUID, ViewerSession> viewers = VIEWERS.get(player.server);
        ViewerSession session = viewers == null ? null : viewers.get(player.getUUID());
        if (!validIdentity(player, session)
            || !Objects.equals(expectedSessionId, session.sessionId())) {
            return false;
        }
        long now = player.serverLevel().getGameTime();
        if (expired(session, now)
            || !session.dimension().equals(player.serverLevel().dimension())
            || session.kind() != Kind.SETTLER
            || session.entityId() != settler.getId()
            || !Objects.equals(session.settlerId(), expectedSettlerId)
            || !Objects.equals(session.settlerId(), settler.getUUID())) {
            remove(player.server, player.getUUID());
            return false;
        }
        if (!settler.isAlive() || settler.level() != player.serverLevel()
            || player.serverLevel().getEntity(settler.getId()) != settler
            || session.targetIdentity() != System.identityHashCode(settler)) {
            remove(player.server, player.getUUID());
            return false;
        }
        viewers.put(player.getUUID(), session.at(now));
        return true;
    }

    enum PlaqueAuthorization {
        AUTHORIZED,
        /** Exact physical session, but the action used an older/wrong link id. */
        STALE,
        INVALID
    }

    /** Exact-session equivalent for a plaque, including BE replacement. */
    static PlaqueAuthorization authorizePlaque(ServerPlayer player,
                                               PlaqueBlockEntity plaque,
                                               UUID expectedBuildingId,
                                               UUID expectedSessionId) {
        Map<UUID, ViewerSession> viewers = VIEWERS.get(player.server);
        ViewerSession session = viewers == null ? null : viewers.get(player.getUUID());
        if (!validIdentity(player, session)
            || !Objects.equals(expectedSessionId, session.sessionId())) {
            return PlaqueAuthorization.INVALID;
        }
        long now = player.serverLevel().getGameTime();
        UUID currentBuildingId = normaliseBuilding(plaque.buildingId());
        if (expired(session, now)
            || !session.dimension().equals(player.serverLevel().dimension())
            || session.kind() != Kind.PLAQUE
            || !plaque.getBlockPos().equals(session.plaquePos())) {
            remove(player.server, player.getUUID());
            return PlaqueAuthorization.INVALID;
        }
        if (plaque.isRemoved() || plaque.getLevel() != player.serverLevel()
            || player.serverLevel().getBlockEntity(plaque.getBlockPos()) != plaque
            || session.targetIdentity() != System.identityHashCode(plaque)) {
            remove(player.server, player.getUUID());
            return PlaqueAuthorization.INVALID;
        }
        if (!Objects.equals(session.buildingId(), currentBuildingId)) {
            // A server tick may legitimately link an unfinished plan while
            // its sheet is open. Adopt current server truth but reject this
            // action, which was authored from the older snapshot; the caller
            // returns a fresh UPDATE and requires another deliberate click.
            viewers.put(player.getUUID(), session.withBuildingId(
                currentBuildingId).at(now));
            return PlaqueAuthorization.STALE;
        }
        if (!Objects.equals(session.buildingId(), expectedBuildingId)) {
            // Wrong/stale packet identity is not authority to destroy the
            // player's real current sheet.
            return PlaqueAuthorization.STALE;
        }
        viewers.put(player.getUUID(), session.at(now));
        return PlaqueAuthorization.AUTHORIZED;
    }

    /**
     * Commits a server-authored building-id transition after one already
     * authorized plaque action.
     *
     * <p>REFRESH can legitimately link an unregistered plan (nil -> UUID) or
     * replace its authoritative link while the same physical plaque screen is
     * open. The pre-action id is still required by {@link #authorizePlaque};
     * only after that check and the server-side mutation may the session adopt
     * the plaque's new id. Exact player, session, dimension, position, live BE
     * object and object identity remain mandatory, so this cannot retarget a
     * delayed packet onto a replacement plaque.
     */
    static boolean updatePlaqueIdentityAfterAuthorizedMutation(
            ServerPlayer player, PlaqueBlockEntity plaque, UUID sessionId) {
        Map<UUID, ViewerSession> viewers = VIEWERS.get(player.server);
        ViewerSession session = viewers == null ? null : viewers.get(player.getUUID());
        if (!validIdentity(player, session)
            || session.kind() != Kind.PLAQUE
            || !Objects.equals(session.sessionId(), sessionId)
            || !plaque.getBlockPos().equals(session.plaquePos())
            || plaque.isRemoved() || plaque.getLevel() != player.serverLevel()
            || player.serverLevel().getBlockEntity(plaque.getBlockPos()) != plaque
            || session.targetIdentity() != System.identityHashCode(plaque)) {
            return false;
        }
        viewers.put(player.getUUID(), session.withBuildingId(
            normaliseBuilding(plaque.buildingId())));
        return true;
    }

    static void closeSettler(ServerPlayer player, int entityId, UUID settlerId,
                             UUID sessionId) {
        removeIf(player, session -> session.kind() == Kind.SETTLER
            && session.entityId() == entityId
            && Objects.equals(session.settlerId(), settlerId)
            && Objects.equals(session.sessionId(), sessionId));
    }

    static void closePlaque(ServerPlayer player, BlockPos pos, UUID buildingId,
                            UUID sessionId) {
        removeIf(player, session -> session.kind() == Kind.PLAQUE
            && pos.equals(session.plaquePos())
            && Objects.equals(session.buildingId(), buildingId)
            && Objects.equals(session.sessionId(), sessionId));
    }

    /**
     * Pushes one update-only authoritative snapshot to every other client
     * still inspecting this exact live settler. Called only after the target
     * ledger has accepted and stored a rank.
     */
    public static void refreshSettler(ServerLevel level, SettlerEntity settler) {
        MinecraftServer server = level.getServer();
        Map<UUID, ViewerSession> viewers = VIEWERS.get(server);
        if (viewers == null) {
            return;
        }
        long now = level.getGameTime();
        for (Map.Entry<UUID, ViewerSession> entry : Map.copyOf(viewers).entrySet()) {
            ViewerSession session = entry.getValue();
            if (session.kind() != Kind.SETTLER
                || !session.dimension().equals(level.dimension())
                || !settler.getUUID().equals(session.settlerId())) {
                continue;
            }
            ServerPlayer viewer = liveViewer(server, entry.getKey(), session, now);
            if (viewer == null || !settler.isAlive()
                || settler.getId() != session.entityId()
                || session.targetIdentity() != System.identityHashCode(settler)
                || viewer.serverLevel() != level
                || viewer.distanceToSqr(settler) > SettlerNetwork.SHEET_REACH_SQUARED) {
                remove(server, entry.getKey());
                continue;
            }
            SettlerNetwork.sendUpdate(viewer, settler, session.sessionId());
        }
    }

    /** Same exact-target, update-only path for one registered live plaque. */
    public static void refreshPlaque(ServerLevel level, PlaqueBlockEntity plaque) {
        refreshPlaque(level, plaque, null);
    }

    /**
     * Package-local overload for the query-count regression. The probe is
     * passed only into snapshots authored by this one refresh and is never
     * retained in session state.
     */
    static void refreshPlaque(ServerLevel level, PlaqueBlockEntity plaque,
                              PlaqueNetwork.SnapshotProbe probe) {
        MinecraftServer server = level.getServer();
        Map<UUID, ViewerSession> viewers = VIEWERS.get(server);
        if (viewers == null) {
            return;
        }
        Building building = plaque.building(level);
        if (building == null || !building.valid
            || !plaque.getBlockPos().equals(building.plaquePos)) {
            return;
        }
        long now = level.getGameTime();
        for (Map.Entry<UUID, ViewerSession> entry : Map.copyOf(viewers).entrySet()) {
            ViewerSession session = entry.getValue();
            if (session.kind() != Kind.PLAQUE
                || !session.dimension().equals(level.dimension())
                || !plaque.getBlockPos().equals(session.plaquePos())
                || !building.id.equals(session.buildingId())) {
                continue;
            }
            ServerPlayer viewer = liveViewer(server, entry.getKey(), session, now);
            if (viewer == null || plaque.isRemoved()
                || level.getBlockEntity(plaque.getBlockPos()) != plaque
                || session.targetIdentity() != System.identityHashCode(plaque)
                || viewer.serverLevel() != level
                || viewer.distanceToSqr(plaque.getBlockPos().getX() + 0.5D,
                    plaque.getBlockPos().getY() + 0.5D,
                    plaque.getBlockPos().getZ() + 0.5D) > REACH_SQUARED) {
                remove(server, entry.getKey());
                continue;
            }
            PlaqueNetwork.sendUpdate(viewer, plaque, session.sessionId(), probe);
        }
    }

    public static void forget(ServerPlayer player) {
        // Identity-check this too: a delayed logout for an old connection
        // must not erase a replacement connection's newly opened sheet.
        removeIf(player, ignored -> true);
    }

    public static void clear(MinecraftServer server) {
        VIEWERS.remove(server);
    }

    private static void remember(ServerPlayer player, ViewerSession session) {
        MinecraftServer server = player.server;
        prune(server, player.serverLevel().getGameTime());
        VIEWERS.computeIfAbsent(server, ignored -> new java.util.HashMap<>())
            .put(player.getUUID(), session);
    }

    private static void removeIf(ServerPlayer player,
                                 java.util.function.Predicate<ViewerSession> target) {
        Map<UUID, ViewerSession> viewers = VIEWERS.get(player.server);
        if (viewers == null) {
            return;
        }
        ViewerSession session = viewers.get(player.getUUID());
        if (validIdentity(player, session) && target.test(session)) {
            remove(player.server, player.getUUID());
        }
    }

    private static ServerPlayer liveViewer(MinecraftServer server, UUID playerId,
                                           ViewerSession session, long now) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (!validIdentity(player, session) || expired(session, now)
            || !player.serverLevel().dimension().equals(session.dimension())) {
            return null;
        }
        return player;
    }

    private static boolean validIdentity(ServerPlayer player, ViewerSession session) {
        return player != null && session != null
            && session.playerIdentity() == System.identityHashCode(player);
    }

    private static boolean expired(ViewerSession session, long now) {
        long age = now - session.touchedAtGameTime();
        return age < 0L || age > SESSION_TTL_TICKS;
    }

    private static void prune(MinecraftServer server, long now) {
        Map<UUID, ViewerSession> viewers = VIEWERS.get(server);
        if (viewers == null) {
            return;
        }
        for (Map.Entry<UUID, ViewerSession> entry : Map.copyOf(viewers).entrySet()) {
            if (liveViewer(server, entry.getKey(), entry.getValue(), now) == null) {
                viewers.remove(entry.getKey());
            }
        }
        if (viewers.isEmpty()) {
            VIEWERS.remove(server);
        }
    }

    private static void remove(MinecraftServer server, UUID playerId) {
        Map<UUID, ViewerSession> viewers = VIEWERS.get(server);
        if (viewers == null) {
            return;
        }
        viewers.remove(playerId);
        if (viewers.isEmpty()) {
            VIEWERS.remove(server);
        }
    }

    static int sessionCountForTest(MinecraftServer server) {
        Map<UUID, ViewerSession> viewers = VIEWERS.get(server);
        return viewers == null ? 0 : viewers.size();
    }

    static boolean hasSettlerForTest(ServerPlayer player, UUID settlerId) {
        Map<UUID, ViewerSession> viewers = VIEWERS.get(player.server);
        ViewerSession session = viewers == null ? null : viewers.get(player.getUUID());
        return validIdentity(player, session) && session.kind() == Kind.SETTLER
            && settlerId.equals(session.settlerId());
    }

    static boolean hasPlaqueForTest(ServerPlayer player, BlockPos plaquePos) {
        Map<UUID, ViewerSession> viewers = VIEWERS.get(player.server);
        ViewerSession session = viewers == null ? null : viewers.get(player.getUUID());
        return validIdentity(player, session) && session.kind() == Kind.PLAQUE
            && plaquePos.equals(session.plaquePos());
    }

    static void ageForTest(ServerPlayer player, long touchedAtGameTime) {
        Map<UUID, ViewerSession> viewers = VIEWERS.get(player.server);
        if (viewers == null) {
            return;
        }
        ViewerSession session = viewers.get(player.getUUID());
        if (validIdentity(player, session)) {
            viewers.put(player.getUUID(), session.at(touchedAtGameTime));
        }
    }

    static void pruneForTest(MinecraftServer server, long now) {
        prune(server, now);
    }

    private enum Kind {
        SETTLER,
        PLAQUE
    }

    private static UUID normaliseBuilding(UUID buildingId) {
        return buildingId == null ? PlaqueAction.NO_BUILDING : buildingId;
    }

    private record ViewerSession(int playerIdentity, ResourceKey<Level> dimension,
                                 long touchedAtGameTime, UUID sessionId, Kind kind,
                                 int targetIdentity, int entityId,
                                 UUID settlerId, BlockPos plaquePos, UUID buildingId) {
        ViewerSession at(long gameTime) {
            return new ViewerSession(playerIdentity, dimension, gameTime,
                sessionId, kind, targetIdentity, entityId, settlerId,
                plaquePos, buildingId);
        }

        ViewerSession withBuildingId(UUID newBuildingId) {
            return new ViewerSession(playerIdentity, dimension,
                touchedAtGameTime, sessionId, kind, targetIdentity, entityId,
                settlerId, plaquePos, newBuildingId);
        }
    }

    private InspectionViewers() {
    }
}
