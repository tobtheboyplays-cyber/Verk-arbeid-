package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.path.RoadNavigation;
import com.hearthstead.settlement.request.RequestLedgerService;
import com.hearthstead.util.QaTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Plans a legal standing cell from which a settler can use the existing
 * Hearth contact contract.  It owns no meal, danger, or order state.
 */
public final class HearthApproach {
    private static final double CONTACT_DISTANCE_SQR = 6.25D;
    /** Keeps normal navigation completion inside the ledger's 2.5-block reach. */
    private static final double COURIER_FOOD_ARRIVAL_MARGIN = 0.75D;
    /**
     * Hungry civilians may be farther from the communal Hearth than their
     * ordinary follow range. This is a planning bound only: it never changes
     * the entity attribute used by combat or other goals.
     */
    private static final float CIVILIAN_MEAL_ROUTE_RANGE = 96.0F;
    private static final float CIVILIAN_MEAL_ROUTE_NODE_MULTIPLIER = 3.0F;

    private HearthApproach() {
    }

    /**
     * Returns a fully reachable route to one grounded, ray-clear contact cell.
     * The isolated navigation probes leave a live worker's current route
     * untouched when an order merely asks whether it may yield for a meal.
     */
    public static Path findReachableContactPath(SettlerEntity settler,
                                                ServerLevel level,
                                                BlockPos hearth) {
        ContactPathProbes probes = probeContactPaths(settler, level, hearth);
        if (fullContactRoute(probes.road(), probes.candidates())) return probes.road();
        if (fullContactRoute(probes.vanilla(), probes.candidates())) return probes.vanilla();
        recordMiss(settler, level, hearth, probes);
        return null;
    }

    /**
     * Returns the usual exact-contact route when one is available. A hungry
     * civilian may otherwise follow one Road prefix only when its endpoint is
     * strictly nearer to the Hearth. The prefix never authorizes a meal: the
     * caller must still pass its live range and ray contact check before it
     * transfers food.
     *
     * <p>The strictly-nearer rule intentionally declines layouts whose only
     * useful detour begins farther from the Hearth. That keeps this recovery
     * movement bounded; a later normal repath may reconsider changed terrain.</p>
     */
    public static Path findProgressingContactPath(SettlerEntity settler,
                                                  ServerLevel level,
                                                  BlockPos hearth) {
        ContactPathProbes probes = probeContactPaths(settler, level, hearth);
        if (fullContactRoute(probes.road(), probes.candidates())) return probes.road();
        if (fullContactRoute(probes.vanilla(), probes.candidates())) return probes.vanilla();
        if (strictlyProgressingPrefix(probes.road(), settler.blockPosition(), hearth)) {
            return probes.road();
        }
        recordMiss(settler, level, hearth, probes);
        return null;
    }

    /**
     * FOOD delivery targets reserve a small completion margin inside the exact
     * ledger contact sphere. A navigation path may report done short of its
     * final node; without this margin the old raw Hearth target could start a
     * bag animation from a block-position arrival the ledger then rejected.
     */
    public static Path findCourierFoodContactPath(SettlerEntity settler,
                                                  ServerLevel level,
                                                  BlockPos hearth) {
        ContactPathProbes probes = probeContactPaths(settler, level, hearth,
            courierFoodContactTargets(settler, level, hearth));
        if (fullContactRoute(probes.road(), probes.candidates())) return probes.road();
        if (fullContactRoute(probes.vanilla(), probes.candidates())) return probes.vanilla();
        if (strictlyProgressingPrefix(probes.road(), settler.blockPosition(), hearth)) {
            return probes.road();
        }
        recordMiss(settler, level, hearth, probes);
        return null;
    }

    /**
     * Civilian meal route with one bounded full-route probe after the ordinary
     * exact and strictly-nearer paths fail. This admits a necessary initial
     * detour around a house, doors or stairs for a hungry resident without
     * enlarging FOLLOW_RANGE or changing combat navigation. The returned path
     * still ends at one existing ray-clear Hearth contact cell; callers retain
     * the existing live-contact gate before withdrawing food.
     */
    public static Path findCivilianMealContactPath(SettlerEntity settler,
                                                   ServerLevel level,
                                                   BlockPos hearth) {
        ContactPathProbes probes = probeContactPaths(settler, level, hearth);
        if (fullContactRoute(probes.road(), probes.candidates())) return probes.road();
        if (fullContactRoute(probes.vanilla(), probes.candidates())) return probes.vanilla();
        // Prefer a bounded complete detour before accepting a partial prefix.
        // This is required for an elevated home whose only legal first step is
        // away from the Hearth.
        Path longRoute = boundedCivilianMealRoute(settler, level, probes.candidates());
        if (fullContactRoute(longRoute, probes.candidates())) return longRoute;
        if (strictlyProgressingPrefix(probes.road(), settler.blockPosition(), hearth)) {
            return probes.road();
        }
        recordMiss(settler, level, hearth, probes);
        return null;
    }

    @Nullable
    private static Path boundedCivilianMealRoute(SettlerEntity settler,
                                                  ServerLevel level,
                                                  Set<BlockPos> candidates) {
        if (candidates.isEmpty()) return null;
        Path road = new CivilianMealNavigation(settler, level).createLongContactPath(candidates);
        if (fullContactRoute(road, candidates) || settler.isInWater()) return road;
        // A long open field has no road to prefer. The per-node road penalty
        // can exhaust the bounded search before reaching food, despite a
        // straight legal route. Match the ordinary unweighted fallback without
        // increasing the shared follow range or the per-search node budget.
        return new CivilianMealVanillaNavigation(settler, level).createLongContactPath(candidates);
    }

    /**
     * Exposes the protected multi-target navigator API without changing the
     * actor's own range or navigation state. One query ranks the existing
     * legal contact set; it never loops independent target searches.
     */
    private static final class CivilianMealNavigation extends RoadNavigation {
        private CivilianMealNavigation(SettlerEntity settler, ServerLevel level) {
            super(settler, level);
        }

        @Nullable
        private Path createLongContactPath(Set<BlockPos> targets) {
            setMaxVisitedNodesMultiplier(CIVILIAN_MEAL_ROUTE_NODE_MULTIPLIER);
            // PathNavigation bytecode: (targets, regionOffset, above, accuracy,
            // searchRange).  The range is the final float, not the fourth int.
            return createPath(targets, 0, false, 0, CIVILIAN_MEAL_ROUTE_RANGE);
        }
    }

    private static final class CivilianMealVanillaNavigation extends GroundPathNavigation {
        private CivilianMealVanillaNavigation(SettlerEntity settler, ServerLevel level) {
            super(settler, level);
            setCanOpenDoors(true);
            setCanPassDoors(true);
        }

        @Nullable
        private Path createLongContactPath(Set<BlockPos> targets) {
            setMaxVisitedNodesMultiplier(CIVILIAN_MEAL_ROUTE_NODE_MULTIPLIER);
            return createPath(targets, 0, false, 0, CIVILIAN_MEAL_ROUTE_RANGE);
        }
    }

    private static ContactPathProbes probeContactPaths(SettlerEntity settler,
                                                        ServerLevel level,
                                                        BlockPos hearth) {
        return probeContactPaths(settler, level, hearth,
            contactTargets(settler, level, hearth));
    }

    private static ContactPathProbes probeContactPaths(SettlerEntity settler,
                                                        ServerLevel level,
                                                        BlockPos hearth,
                                                        Set<BlockPos> candidates) {
        Path road = candidates.isEmpty() ? null
            : new RoadNavigation(settler, level).createPath(candidates, 0);
        Path vanilla = null;
        // Road preference can exhaust the ordinary search before a reachable
        // meal. Keep its full routes first, then try one isolated unweighted
        // ground search with the same targets, door rules and search budget.
        // Do not displace RoadNodeEvaluator's shallow-water start correction.
        if (!fullContactRoute(road, candidates) && !candidates.isEmpty() && !settler.isInWater()) {
            GroundPathNavigation fallback = new GroundPathNavigation(settler, level);
            fallback.setCanOpenDoors(true);
            fallback.setCanPassDoors(true);
            vanilla = fallback.createPath(candidates, 0);
        }
        return new ContactPathProbes(candidates, road, vanilla);
    }

    private static void recordMiss(SettlerEntity settler, ServerLevel level, BlockPos hearth,
                                   ContactPathProbes probes) {
        if (QaTrace.shouldRecordHearthApproachMiss(settler)) {
            CandidateSurvey survey = diagnoseCandidates(settler, level, hearth);
            QaTrace.recordHearthApproachMiss(settler, survey.missDetail(
                settler, hearth, probes.candidates(), probes.road()));
        }
    }

    /** A partial route never authorizes a meal handoff or inventory withdrawal. */
    private static boolean fullContactRoute(Path route, Set<BlockPos> candidates) {
        return route != null && route.canReach() && candidates.contains(route.getTarget());
    }

    private static boolean strictlyProgressingPrefix(Path route, BlockPos start, BlockPos hearth) {
        if (route == null || route.canReach() || route.getNodeCount() <= 0) return false;
        BlockPos end = route.getNodePos(route.getNodeCount() - 1);
        return !end.equals(start) && end.distSqr(hearth) < start.distSqr(hearth);
    }

    private record ContactPathProbes(Set<BlockPos> candidates, Path road, Path vanilla) {
    }

    private static Set<BlockPos> contactTargets(SettlerEntity settler,
                                                ServerLevel level,
                                                BlockPos hearth) {
        Set<BlockPos> candidates = new LinkedHashSet<>();
        addCandidate(settler, level, hearth, hearth.above(), candidates);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (x == 0 && z == 0) continue;
                addCandidate(settler, level, hearth, hearth.offset(x, 0, z), candidates);
            }
        }
        return candidates;
    }

    private static void addCandidate(SettlerEntity settler, ServerLevel level,
                                     BlockPos hearth, BlockPos feet,
                                     Set<BlockPos> candidates) {
        if (feet.distSqr(hearth) > CONTACT_DISTANCE_SQR
            || !standable(level, feet) || !hasVisibleHearth(settler, level, feet, hearth)) {
            return;
        }
        candidates.add(feet.immutable());
    }

    /** FOOD keeps the resident candidate set unchanged and adds only the
     * ledger's exact centre/ray contract plus a path-completion margin. */
    private static Set<BlockPos> courierFoodContactTargets(SettlerEntity settler,
                                                            ServerLevel level,
                                                            BlockPos hearth) {
        Set<BlockPos> candidates = new LinkedHashSet<>();
        addCourierFoodCandidate(settler, level, hearth, hearth.above(), candidates);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (x == 0 && z == 0) continue;
                addCourierFoodCandidate(settler, level, hearth, hearth.offset(x, 0, z), candidates);
            }
        }
        return candidates;
    }

    private static void addCourierFoodCandidate(SettlerEntity settler,
                                                 ServerLevel level,
                                                 BlockPos hearth, BlockPos feet,
                                                 Set<BlockPos> candidates) {
        if (standable(level, feet) && RequestLedgerService.hasFoodHearthContact(level,
                settler, Vec3.atBottomCenterOf(feet), hearth,
                COURIER_FOOD_ARRIVAL_MARGIN)) {
            candidates.add(feet.immutable());
        }
    }

    /** Builds detail only after an actual QA-eligible miss. */
    private static CandidateSurvey diagnoseCandidates(SettlerEntity settler, ServerLevel level,
                                                       BlockPos hearth) {
        CandidateSurvey survey = new CandidateSurvey();
        addDiagnosticCandidate(settler, level, hearth, hearth.above(), survey);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (x == 0 && z == 0) continue;
                addDiagnosticCandidate(settler, level, hearth, hearth.offset(x, 0, z), survey);
            }
        }
        return survey;
    }

    private static void addDiagnosticCandidate(SettlerEntity settler, ServerLevel level,
                                               BlockPos hearth, BlockPos feet,
                                               CandidateSurvey survey) {
        survey.considered++;
        if (feet.distSqr(hearth) > CONTACT_DISTANCE_SQR) {
            survey.tooFar.add(feet.immutable());
        } else if (!standable(level, feet)) {
            survey.notStandable.add(feet.immutable());
        } else if (!hasVisibleHearth(settler, level, feet, hearth)) {
            survey.noRay.add(feet.immutable());
        }
    }

    private static boolean standable(ServerLevel level, BlockPos feet) {
        return level.hasChunkAt(feet) && level.hasChunkAt(feet.above())
            && level.hasChunkAt(feet.below())
            && level.getFluidState(feet).isEmpty()
            && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
            && !level.getBlockState(feet.below())
                .getCollisionShape(level, feet.below()).isEmpty();
    }

    private static boolean hasVisibleHearth(SettlerEntity settler, ServerLevel level,
                                            BlockPos feet, BlockPos hearth) {
        Vec3 eye = new Vec3(feet.getX() + 0.5D, feet.getY() + settler.getEyeHeight(),
            feet.getZ() + 0.5D);
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(eye), hearth)) {
            if (!level.hasChunkAt(cell)) return false;
        }
        var hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(hearth),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, settler));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(hearth);
    }

    /** Diagnostic-only snapshot; never participates in candidate selection. */
    private static final class CandidateSurvey {
        private final List<BlockPos> tooFar = new ArrayList<>();
        private final List<BlockPos> notStandable = new ArrayList<>();
        private final List<BlockPos> noRay = new ArrayList<>();
        private int considered;

        private String missDetail(SettlerEntity settler, BlockPos hearth,
                                  Set<BlockPos> candidates, Path route) {
            int nodeCount = route == null ? 0 : route.getNodeCount();
            BlockPos end = nodeCount <= 0 ? null : route.getNodePos(nodeCount - 1);
            return "start=" + settler.blockPosition().toShortString()
                + ";actual=" + settler.position()
                + ";onGround=" + settler.onGround()
                + ";follow_range=" + settler.getAttributeValue(Attributes.FOLLOW_RANGE)
                + ";hearth=" + hearth.toShortString()
                + ";considered=" + considered
                + ";candidates=" + candidates.size() + positions(candidates)
                + ";reject_range=" + tooFar.size() + positions(tooFar)
                + ";reject_stand=" + notStandable.size() + positions(notStandable)
                + ";reject_ray=" + noRay.size() + positions(noRay)
                + ";probe_target=" + (route == null || route.getTarget() == null
                    ? "none" : route.getTarget().toShortString())
                + ";probe_nodes=" + nodeCount
                + ";probe_end=" + (end == null ? "none" : end.toShortString())
                + ";probe_canReach=" + (route != null && route.canReach());
        }

        private static String positions(Iterable<BlockPos> positions) {
            StringBuilder result = new StringBuilder("[");
            for (BlockPos pos : positions) {
                if (result.length() > 1) result.append(",");
                result.append(pos.toShortString());
            }
            return result.append("]").toString();
        }
    }
}
