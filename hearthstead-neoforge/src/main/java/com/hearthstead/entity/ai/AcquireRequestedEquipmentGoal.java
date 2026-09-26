package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.equipment.WorkplaceStorage;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.work.ContainerApproach;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Walks a worker to one physical world item or matching workplace chest
 * that satisfies their active equipment request, then performs the universal
 * pickup one-shot.
 *
 * <p>This is intentionally not a generic item magnet. The target predicate
 * is authored from the worker's live persistent {@link EquipmentRequest},
 * requires a serviceable tool, excludes drops reserved by field-collection
 * sessions, and is revalidated again on the animation's contact tick.
 */
public final class AcquireRequestedEquipmentGoal extends Goal {
    public static final double SEARCH_RANGE = 14.0D;
    public static final double CONTACT_DISTANCE_SQR = 2.25D;
    /** PICKUP_STOW's authored snatch is at 0.55 s. */
    public static final int CONTACT_TICK = 11;
    /** Full PICKUP_STOW length: 1.40 s. */
    public static final int PICKUP_DURATION_TICKS = 28;

    private static final int GROUND_REPATH_TICKS = 10;
    private static final int PATH_PROGRESS_SAMPLE_TICKS = 15;
    private static final int MAX_FAILED_PATHS = 6;
    private static final int MAX_NO_PROGRESS_SAMPLES = 8;
    private static final int MAX_ROUTE_SAMPLES = 80;
    private static final double PATH_PROGRESS_DISTANCE_SQR = 0.04D;
    /** One failed exact source yields briefly while alternatives are tried. */
    private static final int TARGET_EXCLUSION_TICKS = 100;
    private static final String FIELD_COLLECTION_OWNER =
        "HearthsteadGroundCollectionOwner";

    private final SettlerEntity settler;
    @Nullable
    private EquipmentRequest request;
    @Nullable
    private ItemEntity target;
    @Nullable
    private BlockPos workplaceTarget;
    private boolean picking;
    private boolean transferred;
    private int phaseTicks;
    private int repathIn;
    private int failedPaths;
    private int noProgressSamples;
    private int routeSamples;
    private double pathSampleX;
    private double pathSampleY;
    private double pathSampleZ;
    private boolean finished;
    private final Map<UUID, Long> excludedGroundTargets = new HashMap<>();
    private final Map<BlockPos, Long> excludedWorkplaceTargets = new HashMap<>();

    public AcquireRequestedEquipmentGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(settler.level() instanceof ServerLevel level)
            || settler.isSleeping()
            || settler.getActivity() == SettlerActivity.FLEEING
            || settler.getActivity() == SettlerActivity.RETREATING
            || settler.getActivity() == SettlerActivity.COMBAT) {
            return false;
        }
        long now = level.getGameTime();
        expireTargetExclusions(now);
        request = EquipmentRequests.refreshFor(level, settler);
        if (request == null) {
            return false;
        }
        target = nearestEligible(level, request, now);
        workplaceTarget = null;
        if (target != null) {
            return true;
        }
        Settlement settlement = settler.settlement();
        Building workplace = settlement == null ? null
            : Employment.employerOf(settlement, settler.getUUID());
        if (workplace != null) {
            for (BlockPos candidate : WorkplaceStorage.matchingContainers(level,
                    workplace, request.requirement(), settler.blockPosition())) {
                if (targetAvailable(now,
                        excludedWorkplaceTargets.get(candidate))) {
                    workplaceTarget = candidate;
                    break;
                }
            }
        }
        return workplaceTarget != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (finished || request == null
            || !(settler.level() instanceof ServerLevel)) {
            return false;
        }
        if (picking) {
            // Finish the visible one-shot even if another player wins the
            // race for the item before contact; the transaction itself will
            // simply fail closed on that exact tick.
            return phaseTicks < PICKUP_DURATION_TICKS;
        }
        if (request.requirement().serviceable(settler.getMainHandItem())) {
            return false;
        }
        if (target != null) {
            return target.isAlive()
                && request.requirement().serviceable(target.getItem())
                && settler.distanceToSqr(target)
                    <= (SEARCH_RANGE + 4.0D) * (SEARCH_RANGE + 4.0D);
        }
        return workplaceTarget != null;
    }

    @Override
    public void start() {
        picking = false;
        transferred = false;
        finished = false;
        phaseTicks = 0;
        repathIn = 0;
        failedPaths = 0;
        noProgressSamples = 0;
        routeSamples = 0;
        pathSampleX = settler.getX();
        pathSampleY = settler.getY();
        pathSampleZ = settler.getZ();
        settler.setActivity(SettlerActivity.COLLECTING_ITEMS);
        pathToTarget();
    }

    @Override
    public void tick() {
        // Vanilla ticks every-tick goals once more after tick() finished them,
        // without canContinueToUse() (see CrafterWorkGoal.tick): never act again.
        if (finished) return;
        if (target == null && workplaceTarget == null) {
            finished = true;
            return;
        }
        if (target != null) {
            settler.getLookControl().setLookAt(target, 30.0F, 30.0F);
        } else {
            settler.getLookControl().setLookAt(workplaceTarget.getX() + 0.5D,
                workplaceTarget.getY() + 0.5D,
                workplaceTarget.getZ() + 0.5D, 30.0F, 30.0F);
        }
        if (picking) {
            tickPickup();
            return;
        }
        if (workplaceTarget != null) {
            tickWorkplaceRoute((ServerLevel) settler.level());
            return;
        }
        if (settler.distanceToSqr(target) <= CONTACT_DISTANCE_SQR) {
            if (!EquipmentRequests.hasPhysicalGroundContact(
                    (ServerLevel) settler.level(), settler, target)) {
                failRoute("equipment:ground_occluded");
                return;
            }
            beginPickup();
            return;
        }
        if (--repathIn <= 0) {
            boolean progressing = samplePathProgress();
            if (routeBudgetExhausted(routeSamples, noProgressSamples)) {
                failRoute(noProgressSamples >= MAX_NO_PROGRESS_SAMPLES
                    ? "equipment:ground_stalled"
                    : "equipment:ground_route_budget");
            } else if (!progressing || settler.getNavigation().isDone()) {
                pathToTarget();
            } else {
                repathIn = GROUND_REPATH_TICKS;
            }
        }
    }

    private void beginPickup() {
        picking = true;
        transferred = false;
        phaseTicks = 0;
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.IDLE);
        settler.triggerPickup();
    }

    private void tickPickup() {
        phaseTicks++;
        if (!transferred && phaseTicks == CONTACT_TICK
            && settler.level() instanceof ServerLevel level) {
            if (target != null) {
                if (!EquipmentRequests.hasPhysicalGroundContact(level, settler,
                        target)) {
                    failRoute("equipment:ground_occluded");
                    return;
                }
                transferred = EquipmentRequests.equipFromGround(level, settler,
                    target, CONTACT_DISTANCE_SQR);
            } else if (workplaceTarget != null && settler.settlement() != null) {
                transferred = EquipmentRequests.equipFromWorkplaceAt(level,
                    settler.settlement(), settler, workplaceTarget);
            }
        }
        if (phaseTicks >= PICKUP_DURATION_TICKS) {
            finished = true;
        }
    }

    private void pathToTarget() {
        if (target == null && workplaceTarget == null) {
            finished = true;
            return;
        }
        boolean moving;
        if (target != null) {
            if (!target.isAlive()) {
                finished = true;
                return;
            }
            moving = settler.getNavigation().moveTo(target, 1.05D);
        } else {
            pathToWorkplace((ServerLevel) settler.level());
            return;
        }
        if (!moving
            && ++failedPaths >= MAX_FAILED_PATHS) {
            failRoute("equipment:no_path");
        }
        repathIn = GROUND_REPATH_TICKS;
    }

    /**
     * Workplace tools use the same physical CONTACT proof as the later
     * inventory mutation. An accepted path is not progress by itself: sample
     * the worker's body and release MOVE when a path stalls or oscillates.
     */
    private void tickWorkplaceRoute(ServerLevel level) {
        ContainerApproach.Result contact = ContainerApproach.inspect(level,
            settler, workplaceTarget);
        if (contact.canInteract()) {
            beginPickup();
            return;
        }
        if (contact.state() == ContainerApproach.State.INVALID_TARGET) {
            failRoute("equipment:invalid_workplace_target");
            return;
        }
        if (--repathIn > 0) {
            return;
        }
        repathIn = PATH_PROGRESS_SAMPLE_TICKS;

        boolean progressing = samplePathProgress();
        if (routeBudgetExhausted(routeSamples, noProgressSamples)) {
            failRoute(noProgressSamples >= MAX_NO_PROGRESS_SAMPLES
                ? "equipment:workplace_stalled"
                : "equipment:workplace_route_budget");
            return;
        }
        // Preserve a healthy active path. Rebuild only after a stopped path or
        // one full sample with no measurable body progress.
        if (!progressing || settler.getNavigation().isDone()) {
            pathToWorkplace(level);
        }
    }

    private void pathToWorkplace(ServerLevel level) {
        ContainerApproach.Result route = ContainerApproach.moveToContact(level,
            settler, workplaceTarget, 1.05D);
        switch (route.state()) {
            case CONTACT -> beginPickup();
            case PATH_STARTED -> failedPaths = 0;
            case INVALID_TARGET -> failRoute("equipment:invalid_workplace_target");
            case NO_STANDABLE_SIDE, NO_REACHABLE_SIDE -> {
                if (++failedPaths >= MAX_FAILED_PATHS) {
                    failRoute("equipment:workplace_"
                        + route.state().name().toLowerCase(java.util.Locale.ROOT));
                }
            }
            case OUT_OF_REACH, OCCLUDED -> {
                // moveToContact normally resolves these into a path result.
                // Treat any future unresolved state as one bounded failure.
                if (++failedPaths >= MAX_FAILED_PATHS) {
                    failRoute("equipment:workplace_"
                        + route.state().name().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        repathIn = PATH_PROGRESS_SAMPLE_TICKS;
    }

    private void failRoute(String reason) {
        settler.recordRouteFailure(reason);
        settler.getNavigation().stop();
        excludeCurrentTarget(settler.level().getGameTime());
        finished = true;
    }

    private boolean samplePathProgress() {
        double dx = settler.getX() - pathSampleX;
        double dy = settler.getY() - pathSampleY;
        double dz = settler.getZ() - pathSampleZ;
        pathSampleX = settler.getX();
        pathSampleY = settler.getY();
        pathSampleZ = settler.getZ();
        routeSamples++;
        boolean progressing = madePhysicalPathProgress(
            dx * dx + dy * dy + dz * dz);
        if (progressing) {
            noProgressSamples = 0;
            failedPaths = 0;
        } else {
            noProgressSamples++;
        }
        return progressing;
    }

    private void excludeCurrentTarget(long now) {
        long retryAfter = now + TARGET_EXCLUSION_TICKS;
        if (target != null) {
            excludedGroundTargets.put(target.getUUID(), retryAfter);
        }
        if (workplaceTarget != null) {
            excludedWorkplaceTargets.put(workplaceTarget.immutable(), retryAfter);
        }
    }

    private void expireTargetExclusions(long now) {
        excludedGroundTargets.entrySet().removeIf(entry ->
            targetAvailable(now, entry.getValue()));
        excludedWorkplaceTargets.entrySet().removeIf(entry ->
            targetAvailable(now, entry.getValue()));
    }

    /** Package-visible deterministic seam for exact-target retry timing. */
    static boolean targetAvailable(long now, @Nullable Long retryAfter) {
        return retryAfter == null || retryAfter <= now;
    }

    /** Package-visible deterministic seams for the liveness budget. */
    static boolean madePhysicalPathProgress(double movementDistanceSqr) {
        return movementDistanceSqr > PATH_PROGRESS_DISTANCE_SQR;
    }

    static boolean routeBudgetExhausted(int samples, int stalledSamples) {
        return samples >= MAX_ROUTE_SAMPLES
            || stalledSamples >= MAX_NO_PROGRESS_SAMPLES;
    }

    @Override
    public void stop() {
        settler.getNavigation().stop();
        if (settler.getActivity() == SettlerActivity.COLLECTING_ITEMS) {
            settler.setActivity(SettlerActivity.IDLE);
        }
        request = null;
        target = null;
        workplaceTarget = null;
        picking = false;
        transferred = false;
        finished = false;
        phaseTicks = 0;
        noProgressSamples = 0;
        routeSamples = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Nullable
    private ItemEntity nearestEligible(ServerLevel level,
                                       EquipmentRequest activeRequest,
                                       long now) {
        AABB bounds = settler.getBoundingBox().inflate(SEARCH_RANGE, 4.0D,
            SEARCH_RANGE);
        ItemEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (ItemEntity candidate : level.getEntitiesOfClass(ItemEntity.class,
                bounds, item -> item.isAlive()
                    && !item.getPersistentData().hasUUID(FIELD_COLLECTION_OWNER)
                    && targetAvailable(now,
                        excludedGroundTargets.get(item.getUUID()))
                    && activeRequest.requirement().serviceable(item.getItem()))) {
            double distance = settler.distanceToSqr(candidate);
            if (distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

}
