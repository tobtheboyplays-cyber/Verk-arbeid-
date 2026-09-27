package com.hearthstead.settlement;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.util.QaTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import com.hearthstead.registry.ModSounds;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.WeakHashMap;

/** Bounded server-thread probes for a real walk from outside to the locked Tavern. */
final class TravelerArrivalRoutes {
    private static final int CANDIDATES = 16;
    private static final int MAX_PATH_QUERIES = 4;
    private static final int MAX_PATH_RANGE = 128;
    private static final long RETRY_TICKS = 100L;
    // Transient optimization only: transaction state is still the saved authority.
    // Weak settlement keys do not retain unloaded worlds. Reload may retry sooner.
    private static final Map<Settlement, Long> NEXT_ATTEMPT = new WeakHashMap<>();

    static boolean beginAttempt(Settlement settlement, long now) {
        Long next = NEXT_ATTEMPT.get(settlement);
        if (next != null && now < next && next - now <= RETRY_TICKS) {
            return false;
        }
        NEXT_ATTEMPT.put(settlement, now + RETRY_TICKS);
        return true;
    }

    /** A committed published guest ends this attempt; new trips get a fresh budget. */
    static void completeAttempt(Settlement settlement) {
        NEXT_ATTEMPT.remove(settlement);
    }

    @Nullable
    static BlockPos findOrigin(ServerLevel level, Settlement settlement,
                               SettlerEntity probe, @Nullable BlockPos approach) {
        if (QaTrace.ENABLED) {
            QaTrace.event(probe, "traveler_route_attempt", "settlement=" + settlement.id
                + ";center=" + settlement.center + ";radius=" + settlement.radius
                + ";approach=" + approach);
        }
        if (approach == null) {
            if (QaTrace.ENABLED) {
                QaTrace.event(probe, "traveler_route_rejected", "settlement=" + settlement.id
                    + ";reason=approach_null");
            }

            return null;
        }
        int radius = Math.max(0, settlement.radius) + 8;
        if (radius > MAX_PATH_RANGE) {
            if (QaTrace.ENABLED) {
                QaTrace.event(probe, "traveler_route_rejected", "settlement=" + settlement.id
                    + ";reason=radius_out_of_range;radius=" + radius);
            }
            return null; // fail closed for unsupported/corrupt settlement extents
        }
        int first = level.random.nextInt(CANDIDATES);
        var origins = new ArrayList<BlockPos>(CANDIDATES);
        for (int attempt = 0; attempt < CANDIDATES; attempt++) {
            double angle = (first + attempt) * (Math.PI * 2.0D / CANDIDATES);
            int x = settlement.center.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = settlement.center.getZ() + (int) Math.round(Math.sin(angle) * radius);
            for (int dy = 6; dy >= -6; dy--) {
                BlockPos feet = new BlockPos(x, settlement.center.getY() + dy, z);
                if (!outside(settlement, feet) || feet.distSqr(approach) < 144.0D
                    || !safeFeet(level, probe, feet)) {
                    continue;
                }
                double distance = Math.sqrt(feet.distSqr(approach));
                if (distance > MAX_PATH_RANGE - 16) {
                    continue;
                }
                origins.add(feet);
                break; // one safe surface per sampled column
            }
        }
        // Always try the nearest lawful origin first. The remaining slots
        // rotate through all other origins so a farther reachable route is
        // not starved forever by four nearer disconnected surfaces.
        origins.sort(Comparator.comparingDouble(origin -> origin.distSqr(approach)));
        if (QaTrace.ENABLED) {
            QaTrace.event(probe, "traveler_route_origins", "settlement=" + settlement.id
                + ";approach=" + approach + ";count=" + origins.size() + ";positions=" + origins);
        }
        // Reachability must not spend its bounded search ranking road detours.
        // Use the same vanilla walking/door rules without RoadNodeEvaluator's
        // off-road preference. The published entity retains its normal road
        // navigation and movement goals; this navigator is never installed.
        var navigation = new net.minecraft.world.entity.ai.navigation.GroundPathNavigation(probe, level);
        navigation.setCanOpenDoors(true);
        navigation.setCanPassDoors(true);
        for (int query = 0; query < Math.min(MAX_PATH_QUERIES, origins.size()); query++) {
            int index = query == 0 ? 0
                : 1 + (first + query - 1) % (origins.size() - 1);
            BlockPos feet = origins.get(index);
            double distance = Math.sqrt(feet.distSqr(approach));
            // Unpublished entity; the validated floor satisfies the normal
            // GroundPathNavigation on-ground start gate.
            probe.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0, 0);
            probe.setOnGround(true);
            // Walking distance includes real terrain detours, not only the straight line.
            int maxDistance = Math.min(MAX_PATH_RANGE, (int) Math.ceil(distance) + 32);
            Path path;
            // Only this unpublished origin probe searches farther than a
            // normal movement leg. Keep its node work bounded at2048 and
            // restore the ordinary512-node budget before any publication.
            navigation.setMaxVisitedNodesMultiplier(4.0F);
            try {
                path = navigation.createPath(approach, 0,
                    maxDistance);
            } finally {
                navigation.resetMaxVisitedNodesMultiplier();
            }
            if (QaTrace.ENABLED) {
                QaTrace.event(probe, "traveler_route_query", "settlement=" + settlement.id
                    + ";query=" + query + ";origin=" + feet + ";approach=" + approach
                    + ";maxDistance=" + (maxDistance)
                    + ";pathNull=" + (path == null)
                    + ";canReach=" + (path != null && path.canReach())
                    + ";target=" + (path == null ? null : path.getTarget())
                    + ";nodes=" + (path == null ? 0 : path.getNodeCount())
                    + ";end=" + (path == null || path.getEndNode() == null
                        ? null : path.getEndNode().asBlockPos())
                    + ";walked=" + (path == null || path.getEndNode() == null
                        ? -1.0F : path.getEndNode().walkedDistance));
            }
            if (completeLoadedRoute(level, probe, path, approach)) {
                return feet;
            }
        }
        if (QaTrace.ENABLED) {
            QaTrace.event(probe, "traveler_route_rejected", "settlement=" + settlement.id
                + ";reason=no_origin;approach=" + approach + ";origins=" + origins.size());
        }
        return null;
    }

    private static boolean outside(Settlement settlement, BlockPos feet) {
        double dx = feet.getX() - settlement.center.getX();
        double dz = feet.getZ() - settlement.center.getZ();
        if (dx * dx + dz * dz <= (double) settlement.radius * settlement.radius) {
            return false;
        }
        for (Building building : settlement.buildings) {
            if (building.valid && building.bounds != null
                && feet.getX() >= building.bounds.minX()
                && feet.getX() <= building.bounds.maxX()
                && feet.getZ() >= building.bounds.minZ()
                && feet.getZ() <= building.bounds.maxZ()) {
                return false;
            }
        }
        return true;
    }

    private static boolean safeFeet(ServerLevel level, SettlerEntity probe, BlockPos feet) {
        if (!level.isLoaded(feet) || !level.isLoaded(feet.below())
            || !level.isLoaded(feet.above()) || !level.getWorldBorder().isWithinBounds(feet)) {
            return false;
        }
        var floor = level.getBlockState(feet.below());
        if (!floor.isFaceSturdy(level, feet.below(), Direction.UP)
            || floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.CAMPFIRE)
            || floor.is(Blocks.SOUL_CAMPFIRE) || floor.is(Blocks.CACTUS)
            || !level.getFluidState(feet).isEmpty()
            || !level.getFluidState(feet.above()).isEmpty()) {
            return false;
        }
        probe.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        return level.noCollision(probe, probe.getBoundingBox());
    }

    private static boolean completeLoadedRoute(ServerLevel level, SettlerEntity probe, @Nullable Path path,
                                                BlockPos approach) {
        if (path == null || !path.canReach() || path.getEndNode() == null
            || !path.getEndNode().asBlockPos().equals(approach)) {
            return false;
        }
        for (int index = 0; index < path.getNodeCount(); index++) {
            var node = path.getNode(index);
            BlockPos feet = node.asBlockPos();
            if (!level.isLoaded(feet) || !level.isLoaded(feet.above())
                || !level.isLoaded(feet.below())
                || !level.getWorldBorder().isWithinBounds(feet)
                || dangerous(node.type)) {
                if (QaTrace.ENABLED) {
                    String reason = !level.isLoaded(feet) || !level.isLoaded(feet.above())
                        || !level.isLoaded(feet.below()) ? "unloaded"
                        : !level.getWorldBorder().isWithinBounds(feet) ? "border" : "hazard";
                    QaTrace.event(probe, "traveler_route_node_rejected",
                        "index=" + index + ";pos=" + feet + ";type=" + node.type
                            + ";reason=" + reason);
                }
                return false;
            }
        }
        return true;
    }

    private static boolean dangerous(PathType type) {
        return switch (type) {
            case LAVA, WATER, WATER_BORDER, BREACH, POWDER_SNOW,
                DANGER_POWDER_SNOW, DANGER_FIRE, DAMAGE_FIRE, DANGER_OTHER,
                DAMAGE_OTHER, DAMAGE_CAUTIOUS -> true;
            default -> false;
        };
    }

    static void ringArrivalBell(ServerLevel level, @Nullable Building tavern) {
        if (tavern == null || tavern.bounds == null) {
            return;
        }
        var bounds = tavern.bounds;
        long volume = (long) bounds.getXSpan() * bounds.getYSpan() * bounds.getZSpan();
        if (volume <= 0 || volume > 4096) {
            return;
        }
        for (BlockPos pos : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            if (level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.BELL)) {
                // A short local cue at an actual bell in this exact Tavern.
                // No fictitious Hearth bell, village-wide event or forced load.
                level.playSound(null, pos, ModSounds.VILLAGE_BELL.get(), SoundSource.BLOCKS,
                    0.6F, 1.0F);
                return;
            }
        }
    }

    private TravelerArrivalRoutes() {}
}
