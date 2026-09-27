package com.hearthstead.settlement.workzone;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.Attribute;
import com.hearthstead.network.WorkZoneActionPayload;
import com.hearthstead.network.WorkZoneSelectionPayload;
import com.hearthstead.network.WorkZoneSnapshotPayload;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.FoundingJourneyProgress;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.DevelopmentState;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Server authority for the Work Scepter's select, preview and commit flow.
 *
 * <p>No client coordinate or revision is trusted. Both corners are checked
 * against the server's current interaction ray, and commit re-resolves every
 * identity plus all bounded world constraints before changing SavedData.
 */
public final class WorkZoneService {
    public static final int MAX_SIZE_X = WorkZone.MAX_PERSISTED_SIZE_X;
    public static final int MAX_SIZE_Y = WorkZone.MAX_PERSISTED_SIZE_Y;
    public static final int MAX_SIZE_Z = WorkZone.MAX_PERSISTED_SIZE_Z;
    /** Hard synchronous interaction budget: e.g. 32x16x32 or 48x7x48. */
    public static final long MAX_VOLUME = WorkZone.MAX_PERSISTED_VOLUME;
    public static final int MAX_TOUCHED_CHUNKS = 16;
    public static final int MAX_CONTENT_BLOCK_READS = 32_768;
    private static final long SESSION_TTL_TICKS = 20L * 300L;

    private static final Map<MinecraftServer, Map<UUID, Session>> SESSIONS =
        new WeakHashMap<>();

    public enum Result {
        APPLIED("hearthstead.work_zone.result.applied"),
        SELECTED("hearthstead.work_zone.result.selected"),
        FIRST_CORNER("hearthstead.work_zone.result.first_corner"),
        SECOND_CORNER("hearthstead.work_zone.result.second_corner"),
        PREVIEW_READY("hearthstead.work_zone.result.preview_ready"),
        CANCELLED("hearthstead.work_zone.result.cancelled"),
        NO_SESSION("hearthstead.work_zone.blocked.no_session"),
        NO_SCEPTER("hearthstead.work_zone.blocked.no_scepter"),
        SPECTATOR("hearthstead.work_zone.blocked.read_only"),
        TOO_FAR("hearthstead.work_zone.blocked.too_far"),
        WRONG_TARGET("hearthstead.work_zone.blocked.wrong_target"),
        WRONG_WORKPLACE("hearthstead.work_zone.blocked.wrong_workplace"),
        WRONG_SETTLEMENT("hearthstead.work_zone.blocked.wrong_settlement"),
        WRONG_DIMENSION("hearthstead.work_zone.blocked.wrong_dimension"),
        TECH_LOCKED("hearthstead.work_zone.blocked.tech_locked"),
        STALE("hearthstead.work_zone.blocked.stale"),
        CORRUPT("hearthstead.work_zone.blocked.corrupt"),
        MALFORMED("hearthstead.work_zone.blocked.malformed"),
        OVERSIZE("hearthstead.work_zone.blocked.oversize"),
        OUTSIDE_SETTLEMENT("hearthstead.work_zone.blocked.outside_settlement"),
        WORLD_BORDER("hearthstead.work_zone.blocked.world_border"),
        UNLOADED("hearthstead.work_zone.blocked.unloaded"),
        NO_NATURAL_TREE("hearthstead.work_zone.blocked.no_natural_tree"),
        NO_FIELD("hearthstead.work_zone.blocked.no_field"),
        FARM_HEIGHT_REQUIRED("hearthstead.work_zone.blocked.farm_height_required"),
        FARM_SKILL_LIMIT("hearthstead.work_zone.blocked.farm_skill_limit"),
        REPLAY("hearthstead.work_zone.blocked.replay");

        private final String key;

        Result(String key) {
            this.key = key;
        }

        public Component message() {
            return Component.translatable(key);
        }
    }

    public static Result selectWorker(ServerPlayer player,
                                      SettlerEntity settler) {
        if (player == null || settler == null || !hasScepter(player)) {
            return rejectWithoutSession(player, Result.NO_SCEPTER, "select_worker");
        }
        if (player.isSpectator() || !player.mayBuild()) {
            return rejectWithoutSession(player, Result.SPECTATOR, "select_worker");
        }
        if (!settler.isAlive() || !player.canInteractWithEntity(settler, 0.0D)) {
            return rejectWithoutSession(player, Result.TOO_FAR, "select_worker");
        }
        Settlement settlement = settler.settlement();
        Building building = settlement == null ? null
            : Employment.employerOf(settlement, settler.getUUID());
        WorkZone.Type type = building == null ? null
            : WorkZone.Type.fromBuilding(building.type);
        if (settlement == null || building == null || type == null
            || !settlement.id.equals(settler.getSettlementId())
            || settler.getProfession() != type.profession()
            || !building.workers.contains(settler.getUUID())) {
            return rejectWithoutSession(player, Result.WRONG_TARGET, "select_worker");
        }
        return select(player, settlement, building, type, farmerTier(settler));
    }

    public static Result selectPlaque(ServerPlayer player,
                                      PlaqueBlockEntity plaque) {
        if (player == null || plaque == null || !hasScepter(player)) {
            return rejectWithoutSession(player, Result.NO_SCEPTER, "select_plaque");
        }
        if (player.isSpectator() || !player.mayBuild()) {
            return rejectWithoutSession(player, Result.SPECTATOR, "select_plaque");
        }
        if (!physicalReach(player, plaque.getBlockPos())) {
            return rejectWithoutSession(player, Result.TOO_FAR, "select_plaque");
        }
        ServerLevel level = player.serverLevel();
        Settlement settlement = plaque.settlementFor(level);
        Building building = plaque.building(level);
        WorkZone.Type type = building == null ? null
            : WorkZone.Type.fromBuilding(building.type);
        if (settlement == null || building == null || type == null) {
            return rejectWithoutSession(player, Result.WRONG_WORKPLACE,
                "select_plaque");
        }
        return select(player, settlement, building, type,
            strongestLoadedFarmerTier(player.serverLevel(), building));
    }

    private static Result select(ServerPlayer player, Settlement settlement,
                                 Building building, WorkZone.Type type,
                                 int workerTier) {
        Result validity = validateTarget(player.serverLevel(), settlement,
            building, type);
        if (validity != Result.APPLIED) {
            return rejectWithoutSession(player, validity, "select");
        }
        UUID sessionId = UUID.randomUUID();
        Session session = new Session(sessionId, player.getUUID(),
            settlement.id, building.id, type,
            player.serverLevel().dimension().location().toString(),
            building.workZoneRevision(), player.serverLevel().getGameTime(),
            null, null, false, workerTier);
        sessions(player.server).put(player.getUUID(), session);
        send(player, snapshot(session, building,
            WorkZoneSnapshotPayload.Stage.TARGET_SELECTED, Result.SELECTED));
        trace(player, "select", session, Result.SELECTED);
        return Result.SELECTED;
    }

    /** Server-authoritative first physical right-click after target selection. */
    public static Result setFirstCorner(ServerPlayer player, BlockPos corner) {
        Session session = session(player);
        if (session == null) {
            return rejectWithoutSession(player, Result.NO_SESSION, "corner_one");
        }
        return setFirstCorner(player, session, corner);
    }

    private static Result setFirstCorner(ServerPlayer player, Session session,
                                         @Nullable BlockPos corner) {
        // The session revision belongs to the committed zone, so it does not
        // change between click stages. Reject a replayed first-click instead
        // of letting it replace an already acknowledged corner.
        if (session.cornerOne != null) {
            trace(player, "corner_one_replay", session, Result.REPLAY);
            return Result.REPLAY;
        }
        Resolved resolved = resolve(player, session);
        if (resolved.result != Result.APPLIED) {
            return reject(player, session, resolved.building, resolved.result,
                "corner_one");
        }
        if (corner == null || !physicalReach(player, corner)
            || !serverRayMatches(player, corner)) {
            return reject(player, session, resolved.building, Result.TOO_FAR,
                "corner_one");
        }
        Session updated = session.withCorners(corner.immutable(), null, false);
        sessions(player.server).put(player.getUUID(), updated);
        send(player, snapshot(updated, resolved.building,
            WorkZoneSnapshotPayload.Stage.CORNER_ONE, Result.FIRST_CORNER));
        trace(player, "corner_one", updated, Result.FIRST_CORNER);
        return Result.FIRST_CORNER;
    }

    public static void handle(ServerPlayer player, WorkZoneActionPayload action) {
        if (player == null || action == null
            || action.kind() == WorkZoneActionPayload.Kind.UNKNOWN) {
            return;
        }
        Session session = session(player);
        if (session == null) {
            reset(player, action, Result.NO_SESSION, "action_without_session");
            return;
        }
        if (!session.matches(action)) {
            reset(player, action, Result.REPLAY, "identity_mismatch");
            return;
        }
        switch (action.kind()) {
            case SET_FIRST_CORNER -> setFirstCorner(player, session,
                action.corner().orElse(null));
            case SET_SECOND_CORNER -> setSecondCorner(player, session,
                action.corner().orElse(null));
            case SET_HEIGHT -> setHeight(player, session,
                action.corner().orElse(null));
            case CONFIRM -> confirm(player, session);
            case CANCEL -> cancel(player, session);
            case UNKNOWN -> { }
        }
    }

    /** Resolves a right-click target server-side before any session is opened. */
    public static void handleSelection(ServerPlayer player,
                                       WorkZoneSelectionPayload selection) {
        if (player == null || selection == null
            || selection.kind() == WorkZoneSelectionPayload.Kind.UNKNOWN) {
            return;
        }
        // A duplicate select packet must not replace an already acknowledged
        // target session while its later clicks are still in flight.
        if (session(player) != null) {
            player.displayClientMessage(Result.REPLAY.message(), true);
            return;
        }
        switch (selection.kind()) {
            case SETTLER -> {
                net.minecraft.world.entity.Entity entity = selection.settlerId()
                    .map(player.serverLevel()::getEntity).orElse(null);
                if (entity instanceof SettlerEntity settler) {
                    selectWorker(player, settler);
                } else {
                    rejectWithoutSession(player, Result.WRONG_TARGET, "select_worker");
                }
            }
            case WORKPLACE -> {
                BlockPos pos = selection.workplacePos().orElse(null);
                if (pos == null || !physicalReach(player, pos)
                    || !serverRayMatches(player, pos)) {
                    rejectWithoutSession(player, Result.TOO_FAR, "select_plaque");
                    return;
                }
                if (player.serverLevel().getBlockEntity(pos) instanceof PlaqueBlockEntity plaque) {
                    selectPlaque(player, plaque);
                } else {
                    rejectWithoutSession(player, Result.WRONG_WORKPLACE, "select_plaque");
                }
            }
            case UNKNOWN -> { }
        }
    }

    private static Result setSecondCorner(ServerPlayer player, Session session,
                                          @Nullable BlockPos claimedCorner) {
        if (session.cornerTwo != null) {
            trace(player, "corner_two_replay", session, Result.REPLAY);
            return Result.REPLAY;
        }
        Resolved resolved = resolve(player, session);
        if (resolved.result != Result.APPLIED) {
            return reject(player, session, resolved.building, resolved.result,
                "corner_two");
        }
        if (session.cornerOne == null || claimedCorner == null
            || !physicalReach(player, claimedCorner)
            || !serverRayMatches(player, claimedCorner)) {
            return reject(player, session, resolved.building, Result.TOO_FAR,
                "corner_two");
        }
        int floorY = Math.min(session.cornerOne.getY(), claimedCorner.getY());
        BlockPos flatFirst = new BlockPos(session.cornerOne.getX(), floorY,
            session.cornerOne.getZ());
        BlockPos flatSecond = new BlockPos(claimedCorner.getX(), floorY,
            claimedCorner.getZ());
        Session updated = session.withCorners(flatFirst, flatSecond, false);
        sessions(player.server).put(player.getUUID(), updated);
        send(player, snapshot(updated, resolved.building,
            WorkZoneSnapshotPayload.Stage.CORNER_TWO, Result.SECOND_CORNER));
        trace(player, "corner_two", updated, Result.SECOND_CORNER);
        return Result.SECOND_CORNER;
    }

    /** Third physical click contributes only Y; the first two clicks own X/Z. */
    private static Result setHeight(ServerPlayer player, Session session,
                                    @Nullable BlockPos claimedHeight) {
        if (session.previewReady) {
            trace(player, "height_replay", session, Result.REPLAY);
            return Result.REPLAY;
        }
        Resolved resolved = resolve(player, session);
        if (resolved.result != Result.APPLIED) {
            return reject(player, session, resolved.building, resolved.result,
                "height");
        }
        if (session.cornerOne == null || session.cornerTwo == null
            || claimedHeight == null || !physicalReach(player, claimedHeight)
            || !serverRayMatches(player, claimedHeight)) {
            return reject(player, session, resolved.building, Result.TOO_FAR,
                "height");
        }
        BlockPos heightCorner = new BlockPos(session.cornerTwo.getX(),
            claimedHeight.getY(), session.cornerTwo.getZ());
        WorkZone candidate;
        try {
            candidate = WorkZone.between(session.settlementId,
                session.buildingId, session.type,
                player.serverLevel().dimension().location(), session.cornerOne,
                heightCorner, session.expectedRevision + 1);
        } catch (IllegalArgumentException malformed) {
            return reject(player, session, resolved.building, Result.MALFORMED,
                "preview");
        }
        Result validation = validateCandidate(player.serverLevel(),
            resolved.settlement, resolved.building, candidate,
            session.workerTier);
        if (validation != Result.APPLIED) {
            return reject(player, session, resolved.building, validation,
                "preview");
        }
        Session updated = session.withCorners(session.cornerOne,
            heightCorner, true);
        sessions(player.server).put(player.getUUID(), updated);
        emitObservation(player, AuthorityTelemetry.Event.WORK_ZONE_PREVIEWED,
            updated, resolved.building, "preview_ready");
        send(player, snapshot(updated, resolved.building,
            WorkZoneSnapshotPayload.Stage.PREVIEW_READY, Result.PREVIEW_READY));
        trace(player, "preview", updated, Result.PREVIEW_READY);
        return Result.PREVIEW_READY;
    }

    private static Result confirm(ServerPlayer player, Session session) {
        Resolved resolved = resolve(player, session);
        if (resolved.result != Result.APPLIED) {
            return reject(player, session, resolved.building, resolved.result,
                "commit");
        }
        if (!session.previewReady || session.cornerOne == null
            || session.cornerTwo == null) {
            return reject(player, session, resolved.building, Result.MALFORMED,
                "commit");
        }
        WorkZone candidate;
        try {
            candidate = WorkZone.between(session.settlementId,
                session.buildingId, session.type,
                player.serverLevel().dimension().location(), session.cornerOne,
                session.cornerTwo, session.expectedRevision + 1);
        } catch (IllegalArgumentException malformed) {
            return reject(player, session, resolved.building, Result.MALFORMED,
                "commit");
        }
        Result committed = commitValidated(player.serverLevel(),
            resolved.settlement, resolved.building, candidate,
            session.workerTier);
        if (committed != Result.APPLIED) {
            return reject(player, session, resolved.building, committed,
                "commit");
        }
        sessions(player.server).remove(player.getUUID());
        send(player, snapshot(session, resolved.building,
            WorkZoneSnapshotPayload.Stage.COMMITTED, Result.APPLIED));
        trace(player, "commit", session, Result.APPLIED);
        return Result.APPLIED;
    }

    /**
     * One authoritative validated-zone transaction shared by the interactive
     * Work Scepter flow and permission-two fixture authors.  Callers may
     * supply a real worker so Farm side limits use that worker's actual tier;
     * no caller writes Building, Journey, dirty state or telemetry separately.
     */
    public static Result commitValidated(ServerLevel level,
                                         Settlement settlement,
                                         Building building,
                                         SettlerEntity worker,
                                         WorkZone candidate) {
        if (level == null || settlement == null || worker == null
            || building == null
            || worker.level() != level || !worker.isAlive()
            || SettlementManager.byId(level, settlement.id) != settlement
            || worker.settlement() != settlement
            || !settlement.id.equals(worker.getSettlementId())
            || !building.workers.contains(worker.getUUID())
            || Employment.employerOf(settlement, worker.getUUID()) != building
            || candidate == null
            || candidate.type().profession() != worker.getProfession()) {
            return Result.WRONG_WORKPLACE;
        }
        Result target = validateTarget(level, settlement, building,
            candidate.type());
        if (target != Result.APPLIED) {
            return target;
        }
        return commitValidated(level, settlement, building, candidate,
            farmerTier(worker));
    }

    private static Result commitValidated(ServerLevel level,
                                          Settlement settlement,
                                          Building building,
                                          WorkZone candidate,
                                          int workerTier) {
        Result validation = validateCandidate(level, settlement, building,
            candidate, workerTier);
        if (validation != Result.APPLIED) {
            return validation;
        }
        long revisionBefore = building.workZoneRevision();
        long countBefore = building.workZone().isPresent() ? 1L : 0L;
        if (!building.commitWorkZone(candidate.revision() - 1, candidate)) {
            return Result.STALE;
        }
        SettlementSavedData.get(level).setDirty();
        FoundingJourneyProgress.noteWorkZoneCommitted(level, settlement,
            building, candidate);
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.WORK_ZONE_COMMITTED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.state(settlement.id,
                authorityTarget(building, "commit"),
                revisionBefore, building.workZoneRevision(), countBefore,
                building.workZone().isPresent() ? 1L : 0L,
                "confirmed_bounds"));
        return Result.APPLIED;
    }

    private static Result cancel(ServerPlayer player, Session session) {
        Building building = null;
        Settlement settlement = SettlementManager.byId(player.serverLevel(),
            session.settlementId);
        if (settlement != null) {
            building = exactBuilding(settlement, session.buildingId);
        }
        emitObservation(player, AuthorityTelemetry.Event.WORK_ZONE_CANCELLED,
            session, building, "cancelled_without_commit");
        sessions(player.server).remove(player.getUUID());
        send(player, snapshot(session, building,
            WorkZoneSnapshotPayload.Stage.CANCELLED, Result.CANCELLED));
        trace(player, "cancel", session, Result.CANCELLED);
        return Result.CANCELLED;
    }

    /** Strict pure validation used again immediately before every commit. */
    public static Result validateCandidate(ServerLevel level,
                                           Settlement settlement,
                                           Building building,
                                           WorkZone zone) {
        return validateCandidate(level, settlement, building, zone, 5);
    }

    private static Result validateCandidate(ServerLevel level,
                                            Settlement settlement,
                                            Building building,
                                            WorkZone zone,
                                            int workerTier) {
        if (level == null || settlement == null || building == null || zone == null) {
            return Result.MALFORMED;
        }
        if (!settlement.id.equals(zone.settlementId())
            || !building.id.equals(zone.buildingId())
            || !exactRegisteredBuilding(settlement, building)) {
            return Result.WRONG_SETTLEMENT;
        }
        if (!level.dimension().location().equals(zone.dimension())) {
            return Result.WRONG_DIMENSION;
        }
        if (building.workZoneQuarantined()
            || persistedZoneHasWrongDimension(level, building)) {
            return Result.CORRUPT;
        }
        if (!building.valid || zone.type().buildingType() != building.type) {
            return Result.WRONG_WORKPLACE;
        }
        if (zone.revision() != building.workZoneRevision() + 1) {
            return Result.STALE;
        }
        if (!zone.withinPersistentLimits()) {
            return Result.OVERSIZE;
        }
        // City membership is the exact settlement/building UUID pair above.
        // Do not reintroduce a center-radius gate here: the persistent zone
        // limits, dimension, world border and loaded-chunk checks below still
        // bound every candidate independently.
        if (!level.getWorldBorder().isWithinBounds(zone.min())
            || !level.getWorldBorder().isWithinBounds(zone.max())) {
            return Result.WORLD_BORDER;
        }
        if (zone.type() == WorkZone.Type.FARM) {
            if (zone.sizeY() < 2) {
                return Result.FARM_HEIGHT_REQUIRED;
            }
            int side = farmerSideLimit(workerTier);
            long area = (long) zone.sizeX() * zone.sizeZ();
            if (zone.sizeX() > side || zone.sizeZ() > side
                || area > (long) side * side) {
                return Result.FARM_SKILL_LIMIT;
            }
        }
        int minChunkX = zone.min().getX() >> 4;
        int maxChunkX = zone.max().getX() >> 4;
        int minChunkZ = zone.min().getZ() >> 4;
        int maxChunkZ = zone.max().getZ() >> 4;
        long touched = (long) (maxChunkX - minChunkX + 1)
            * (maxChunkZ - minChunkZ + 1);
        if (touched > MAX_TOUCHED_CHUNKS) {
            return Result.OVERSIZE;
        }
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    return Result.UNLOADED;
                }
            }
        }
        return switch (zone.type()) {
            case LUMBER -> containsNaturalTree(level, zone)
                ? Result.APPLIED : Result.NO_NATURAL_TREE;
            case FARM -> containsField(level, zone)
                ? Result.APPLIED : Result.NO_FIELD;
        };
    }

    /* package-private: the adversarial GameTests exercise this exact
       settlement-scoped progression gate without opening a synthetic client
       networking session. Runtime selection calls the same method above. */
    static Result validateTarget(ServerLevel level,
                                 Settlement settlement,
                                 Building building,
                                 WorkZone.Type type) {
        if (!exactRegisteredBuilding(settlement, building)
            || !building.valid || building.type != type.buildingType()) {
            return Result.WRONG_WORKPLACE;
        }
        if (building.workZoneQuarantined()
            || persistedZoneHasWrongDimension(level, building)) {
            return Result.CORRUPT;
        }
        DevelopmentState development = Development.of(level, settlement);
        if (development.quarantined()) {
            // A damaged development ledger must be reported separately from
            // ordinary unlearned Timber Rights. The latter is actionable
            // progression; the former requires an explicit save repair.
            return Result.CORRUPT;
        }
        // Timber Rights gates only the LUMBER zone. Since the founding
        // trades (tech tree Option 2) Fields & Farmer needs only the Banner,
        // so a Farmhouse-first village must be able to set its farm zone.
        if ((type == WorkZone.Type.LUMBER
                && !development.unlocked(DevelopmentNode.TIMBER_RIGHTS))
            || !Development.isBuildingUnlocked(level, settlement,
                type.buildingType())) {
            return Result.TECH_LOCKED;
        }
        return Result.APPLIED;
    }

    /** Bounded natural-tree proof; no chunk acquisition occurs here. */
    private static boolean containsNaturalTree(ServerLevel level,
                                               WorkZone zone) {
        ReadBudget reads = new ReadBudget(MAX_CONTENT_BLOCK_READS, zone);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = zone.min().getX(); x <= zone.max().getX(); x++) {
            for (int z = zone.min().getZ(); z <= zone.max().getZ(); z++) {
                for (int y = zone.min().getY(); y <= zone.max().getY(); y++) {
                    cursor.set(x, y, z);
                    if (!zone.contains(cursor.below())) {
                        continue;
                    }
                    BlockState state = reads.read(level, cursor);
                    BlockState below = reads.read(level, cursor.below());
                    if (state == null || below == null) {
                        return false;
                    }
                    if (!state.is(BlockTags.LOGS_THAT_BURN)
                        || !below.is(BlockTags.DIRT)) {
                        continue;
                    }
                    int logs = 0;
                    BlockPos.MutableBlockPos trunk = cursor.mutable();
                    while (logs < 12 && zone.contains(trunk)) {
                        BlockState trunkState = reads.read(level, trunk);
                        if (trunkState == null) {
                            return false;
                        }
                        if (!trunkState.is(BlockTags.LOGS_THAT_BURN)) {
                            break;
                        }
                        logs++;
                        trunk.move(0, 1, 0);
                    }
                    if (logs < 2) {
                        continue;
                    }
                    int leaves = 0;
                    int crownY = y + logs - 1;
                    for (int dx = -3; dx <= 3 && leaves < 4; dx++) {
                        for (int dy = -2; dy <= 3 && leaves < 4; dy++) {
                            for (int dz = -3; dz <= 3 && leaves < 4; dz++) {
                                BlockPos leaf = new BlockPos(x + dx,
                                    crownY + dy, z + dz);
                                if (!zone.contains(leaf)) {
                                    continue;
                                }
                                BlockState leafState = reads.read(level, leaf);
                                if (leafState == null) {
                                    return false;
                                }
                                if (leafState.is(BlockTags.LEAVES)) {
                                    leaves++;
                                }
                            }
                        }
                    }
                    if (leaves >= 4) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean containsField(ServerLevel level, WorkZone zone) {
        ReadBudget reads = new ReadBudget(MAX_CONTENT_BLOCK_READS, zone);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = zone.min().getX(); x <= zone.max().getX(); x++) {
            for (int z = zone.min().getZ(); z <= zone.max().getZ(); z++) {
                for (int y = zone.min().getY(); y <= zone.max().getY(); y++) {
                    cursor.set(x, y, z);
                    BlockState state = reads.read(level, cursor);
                    if (state == null) {
                        return false;
                    }
                    if (state.is(Blocks.FARMLAND)
                        && zone.contains(cursor.above())) {
                        return true;
                    }
                    if (state.getBlock() instanceof CropBlock
                        && zone.contains(cursor.below())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Deterministic upper bound for preview plus confirm content reads. */
    static long worstCaseContentReadsPerTransaction() {
        return (long) MAX_CONTENT_BLOCK_READS * 2L;
    }

    private static final class ReadBudget {
        private final int maximum;
        private final WorkZone zone;
        private int used;

        private ReadBudget(int maximum, WorkZone zone) {
            this.maximum = maximum;
            this.zone = zone;
        }

        @Nullable
        private BlockState read(ServerLevel level, BlockPos pos) {
            if (used >= maximum || !livePositionAllowed(level, zone, pos)) {
                return null;
            }
            used++;
            return level.getBlockState(pos);
        }
    }

    @Nullable
    private static Session session(ServerPlayer player) {
        Map<UUID, Session> sessions = sessions(player.server);
        Session session = sessions.get(player.getUUID());
        if (session == null) {
            return null;
        }
        long age = player.serverLevel().getGameTime() - session.openedAt;
        if (age < 0 || age > SESSION_TTL_TICKS
            || !session.playerId.equals(player.getUUID())) {
            sessions.remove(player.getUUID());
            return null;
        }
        return session;
    }

    private static Resolved resolve(ServerPlayer player, Session session) {
        if (!hasScepter(player)) {
            return new Resolved(null, null, Result.NO_SCEPTER);
        }
        if (player.isSpectator() || !player.mayBuild()) {
            return new Resolved(null, null, Result.SPECTATOR);
        }
        ServerLevel level = player.serverLevel();
        if (!level.dimension().location().toString().equals(session.dimension)) {
            return new Resolved(null, null, Result.WRONG_DIMENSION);
        }
        Settlement settlement = SettlementManager.byId(level, session.settlementId);
        if (settlement == null) {
            return new Resolved(null, null, Result.WRONG_SETTLEMENT);
        }
        Building building = exactBuilding(settlement, session.buildingId);
        if (building == null) {
            return new Resolved(settlement, null, Result.WRONG_WORKPLACE);
        }
        Result target = validateTarget(level, settlement, building, session.type);
        if (target != Result.APPLIED) {
            return new Resolved(settlement, building, target);
        }
        if (building.workZoneRevision() != session.expectedRevision) {
            return new Resolved(settlement, building, Result.STALE);
        }
        return new Resolved(settlement, building, Result.APPLIED);
    }

    static double blockReachDistance(ServerPlayer player) {
        return player == null ? 0.0D : player.blockInteractionRange();
    }

    private static boolean serverRayMatches(ServerPlayer player, BlockPos claimed) {
        HitResult hit = player.pick(blockReachDistance(player), 1.0F, false);
        return hit instanceof BlockHitResult blockHit
            && blockHit.getType() == HitResult.Type.BLOCK
            && blockHit.getBlockPos().equals(claimed);
    }

    static boolean physicalReach(ServerPlayer player, BlockPos pos) {
        return player != null && livePositionAvailable(player.serverLevel(), pos)
            && player.canInteractWithBlock(pos, 0.0D);
    }

    /**
     * Read/mutation preflight that never acquires a chunk. Call this before
     * any block-state read whose coordinates came from persisted or AI state.
     */
    public static boolean livePositionAvailable(ServerLevel level, BlockPos pos) {
        return level != null && pos != null
            && level.getWorldBorder().isWithinBounds(pos)
            && level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** Exact committed-zone authority plus the non-loading live preflight. */
    public static boolean livePositionAllowed(ServerLevel level, WorkZone zone,
                                              BlockPos pos) {
        return level != null && zone != null && pos != null
            && zone.dimension().equals(level.dimension().location())
            && zone.contains(pos) && livePositionAvailable(level, pos);
    }

    private static boolean persistedZoneHasWrongDimension(ServerLevel level,
                                                           Building building) {
        return level != null && building != null
            && building.workZone().map(zone -> !zone.dimension().equals(
                level.dimension().location())).orElse(false);
    }

    private static boolean hasScepter(ServerPlayer player) {
        // Selection, both corners and the client attack-cancel hook all use
        // one explicit hand policy. Offhand clicks are rejected visibly by
        // the ordinary service path instead of opening an unusable session.
        return isScepter(player.getMainHandItem());
    }

    private static boolean isScepter(ItemStack stack) {
        return stack != null && stack.is(ModItems.WORK_SCEPTER.get());
    }

    private static boolean exactRegisteredBuilding(Settlement settlement,
                                                   Building building) {
        return building != null && exactBuilding(settlement, building.id) == building;
    }

    @Nullable
    private static Building exactBuilding(Settlement settlement, UUID id) {
        if (settlement == null || id == null) {
            return null;
        }
        Building match = null;
        for (Building building : settlement.buildings) {
            if (building.id.equals(id)) {
                if (match != null) {
                    return null; // duplicate persistent identity: fail closed
                }
                match = building;
            }
        }
        return match;
    }

    private static Map<UUID, Session> sessions(MinecraftServer server) {
        synchronized (SESSIONS) {
            return SESSIONS.computeIfAbsent(server, ignored -> new HashMap<>());
        }
    }

    public static void forget(ServerPlayer player) {
        if (player != null) {
            sessions(player.server).remove(player.getUUID());
        }
    }

    public static void clear(MinecraftServer server) {
        synchronized (SESSIONS) {
            SESSIONS.remove(server);
        }
    }

    private static Result rejectWithoutSession(@Nullable ServerPlayer player,
                                               Result result, String event) {
        if (player != null) {
            player.displayClientMessage(result.message(), true);
            emitRejection(player, null, null, result, event);
            Hearthstead.LOGGER.info("HSQA_WORK_ZONE event={} outcome={} player={} thread={}",
                event, result.name(), player.getUUID(), Thread.currentThread().getName());
        }
        return result;
    }

    private static Result reject(ServerPlayer player, Session session,
                                 @Nullable Building building, Result result,
                                 String event) {
        // Before the modal exists, preserve the last actionable stage so a
        // bad corner can be retried. Once a validated preview is open, keep
        // the explicit rejection screen and its working server-side Cancel.
        WorkZoneSnapshotPayload.Stage stage = rejectionStage(session.previewReady,
            session.cornerOne != null, session.cornerTwo != null);
        send(player, snapshot(session, building, stage, result));
        emitRejection(player, session, building, result, event);
        trace(player, event, session, result);
        return result;
    }

    /** The retry snapshot must describe the server's retained click state. */
    static WorkZoneSnapshotPayload.Stage rejectionStage(boolean previewReady,
                                                        boolean hasCornerOne,
                                                        boolean hasCornerTwo) {
        if (previewReady) {
            return WorkZoneSnapshotPayload.Stage.REJECTED;
        }
        if (hasCornerTwo) {
            return WorkZoneSnapshotPayload.Stage.CORNER_TWO;
        }
        return hasCornerOne ? WorkZoneSnapshotPayload.Stage.CORNER_ONE
            : WorkZoneSnapshotPayload.Stage.TARGET_SELECTED;
    }

    /** Stateless terminal reply for an expired or replayed client identity. */
    private static Result reset(ServerPlayer player, WorkZoneActionPayload action,
                                Result result, String event) {
        WorkZoneSnapshotPayload snapshot = new WorkZoneSnapshotPayload(
            action.sessionId(), action.settlementId(), action.buildingId(),
            action.typeWireId(),
            player.serverLevel().dimension().location().toString(),
            action.expectedRevision(), WorkZoneSnapshotPayload.Stage.RESET,
            Optional.empty(), Optional.empty(),
            Component.translatable("hearthstead.work_zone.unknown_workplace"),
            Optional.of(result.message()));
        send(player, snapshot);
        emitRejection(player, null, null, result, event);
        Hearthstead.LOGGER.info(
            "HSQA_WORK_ZONE event={} outcome={} session={} player={} settlement={} building={} expectedRevision={} thread={}",
            event, result.name(), action.sessionId(), player.getUUID(),
            action.settlementId(), action.buildingId(),
            action.expectedRevision(), Thread.currentThread().getName());
        return result;
    }

    private static WorkZoneSnapshotPayload snapshot(Session session,
                                                    @Nullable Building building,
                                                    WorkZoneSnapshotPayload.Stage stage,
                                                    Result result) {
        Component workplace = building == null
            ? Component.translatable("hearthstead.work_zone.unknown_workplace")
            : building.type.displayName();
        return new WorkZoneSnapshotPayload(session.sessionId,
            session.settlementId, session.buildingId, session.type.wireId(),
            session.dimension, session.expectedRevision, stage,
            Optional.ofNullable(session.cornerOne),
            Optional.ofNullable(session.cornerTwo), workplace,
            Optional.of(result.message()));
    }

    private static void send(ServerPlayer player,
                             WorkZoneSnapshotPayload snapshot) {
        com.hearthstead.network.PayloadSend.toPlayer(player, snapshot);
        snapshot.feedback().ifPresent(message ->
            player.displayClientMessage(message, true));
    }

    private static void trace(ServerPlayer player, String event,
                              Session session, Result result) {
        Hearthstead.LOGGER.info(
            "HSQA_WORK_ZONE event={} outcome={} session={} player={} settlement={} building={} type={} expectedRevision={} corner1={} corner2={} dimension={} thread={}",
            event, result.name(), session.sessionId, player.getUUID(),
            session.settlementId, session.buildingId, session.type.id(),
            session.expectedRevision, session.cornerOne, session.cornerTwo,
            session.dimension, Thread.currentThread().getName());
    }

    private static void emitObservation(ServerPlayer player,
                                        AuthorityTelemetry.Event event,
                                        Session session,
                                        @Nullable Building building,
                                        String reason) {
        long revision = building == null
            ? session.expectedRevision : building.workZoneRevision();
        long count = building != null && building.workZone().isPresent()
            ? 1L : 0L;
        AuthorityTelemetry.emit(player.serverLevel(), event,
            AuthorityTelemetry.Result.OBSERVED,
            AuthorityTelemetry.Fields.state(session.settlementId,
                authorityTarget(building, reason), revision, revision,
                count, count, reason));
    }

    private static void emitRejection(ServerPlayer player,
                                      @Nullable Session session,
                                      @Nullable Building building,
                                      Result result, String operation) {
        long revision = building != null
            ? building.workZoneRevision()
            : session == null ? 0L : session.expectedRevision;
        long count = building != null && building.workZone().isPresent()
            ? 1L : 0L;
        AuthorityTelemetry.emit(player.serverLevel(),
            AuthorityTelemetry.Event.AUTHORITY_REJECTED,
            AuthorityTelemetry.Result.REJECTED,
            AuthorityTelemetry.Fields.state(
                session == null ? null : session.settlementId,
                authorityTarget(building, operation), revision, revision,
                count, count,
                "work_zone_" + result.name().toLowerCase(
                    java.util.Locale.ROOT)));
    }

    private static String authorityTarget(@Nullable Building building,
                                          String operation) {
        return building == null
            ? "work_zone:" + operation
            : "work_zone:" + building.id;
    }

    private record Resolved(@Nullable Settlement settlement,
                            @Nullable Building building, Result result) { }

    private record Session(UUID sessionId, UUID playerId, UUID settlementId,
                           UUID buildingId, WorkZone.Type type,
                           String dimension, int expectedRevision,
                           long openedAt, @Nullable BlockPos cornerOne,
                           @Nullable BlockPos cornerTwo,
                           boolean previewReady, int workerTier) {
        private Session withCorners(@Nullable BlockPos first,
                                    @Nullable BlockPos second,
                                    boolean ready) {
            return new Session(sessionId, playerId, settlementId, buildingId,
                type, dimension, expectedRevision, openedAt,
                first == null ? null : first.immutable(),
                second == null ? null : second.immutable(), ready, workerTier);
        }

        private boolean matches(WorkZoneActionPayload action) {
            return sessionId.equals(action.sessionId())
                && settlementId.equals(action.settlementId())
                && buildingId.equals(action.buildingId())
                && type.wireId() == action.typeWireId()
                && expectedRevision == action.expectedRevision();
        }
    }

    static int farmerSideLimit(int tier) {
        return switch (Math.max(1, Math.min(5, tier))) {
            case 1 -> 12;
            case 2 -> 16;
            case 3 -> 20;
            case 4 -> 24;
            default -> 28;
        };
    }

    private static int farmerTier(@Nullable SettlerEntity settler) {
        if (settler == null || settler.getProfession()
                != com.hearthstead.entity.Profession.FARMER) {
            return 1;
        }
        return 1 + Math.min(4, settler.attribute(Attribute.DEXTERITY) / 20);
    }

    private static int strongestLoadedFarmerTier(ServerLevel level,
                                                  Building building) {
        int best = 1;
        for (UUID worker : building.workers) {
            if (level.getEntity(worker) instanceof SettlerEntity settler) {
                best = Math.max(best, farmerTier(settler));
            }
        }
        return best;
    }

    private WorkZoneService() { }
}
