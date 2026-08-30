package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.equipment.WorkplaceStorage;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.EnumSet;

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

    private static final int REPATH_TICKS = 10;
    private static final int MAX_FAILED_PATHS = 6;
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
    private boolean finished;
    private long retryAt;

    public AcquireRequestedEquipmentGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(settler.level() instanceof ServerLevel level)
            || settler.isSleeping()
            || settler.getActivity() == SettlerActivity.FLEEING
            || settler.getActivity() == SettlerActivity.COMBAT
            || level.getGameTime() < retryAt) {
            return false;
        }
        request = EquipmentRequests.refreshFor(level, settler);
        if (request == null) {
            return false;
        }
        target = nearestEligible(level, request);
        workplaceTarget = null;
        if (target != null) {
            return true;
        }
        Settlement settlement = settler.settlement();
        Building workplace = settlement == null ? null
            : Employment.employerOf(settlement, settler.getUUID());
        if (workplace != null) {
            workplaceTarget = WorkplaceStorage.nearestMatchingContainer(level,
                workplace, request.requirement(), settler.blockPosition());
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
        settler.setActivity(SettlerActivity.COLLECTING_ITEMS);
        pathToTarget();
    }

    @Override
    public void tick() {
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
        double distance = target != null ? settler.distanceToSqr(target)
            : settler.blockPosition().distSqr(workplaceTarget);
        if (distance <= CONTACT_DISTANCE_SQR) {
            beginPickup();
            return;
        }
        if (--repathIn <= 0) {
            pathToTarget();
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
            BlockPos approach = approachTo((ServerLevel) settler.level(),
                workplaceTarget, settler.blockPosition());
            moving = settler.getNavigation().moveTo(approach.getX() + 0.5D,
                approach.getY(), approach.getZ() + 0.5D, 1.05D);
        }
        if (!moving
            && ++failedPaths >= MAX_FAILED_PATHS) {
            settler.recordRouteFailure("equipment:no_path");
            retryAt = settler.level().getGameTime() + 100L;
            finished = true;
        }
        repathIn = REPATH_TICKS;
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
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Nullable
    private ItemEntity nearestEligible(ServerLevel level,
                                       EquipmentRequest activeRequest) {
        AABB bounds = settler.getBoundingBox().inflate(SEARCH_RANGE, 4.0D,
            SEARCH_RANGE);
        ItemEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (ItemEntity candidate : level.getEntitiesOfClass(ItemEntity.class,
                bounds, item -> item.isAlive()
                    && !item.getPersistentData().hasUUID(FIELD_COLLECTION_OWNER)
                    && activeRequest.requirement().serviceable(item.getItem()))) {
            double distance = settler.distanceToSqr(candidate);
            if (distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static BlockPos approachTo(ServerLevel level, BlockPos container,
                                       BlockPos from) {
        BlockPos best = container;
        double bestDistance = Double.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = container.relative(direction);
            if (!level.getBlockState(side).getCollisionShape(level, side).isEmpty()
                || !level.getBlockState(side.above())
                    .getCollisionShape(level, side.above()).isEmpty()
                || level.getBlockState(side.below())
                    .getCollisionShape(level, side.below()).isEmpty()) {
                continue;
            }
            double distance = from.distSqr(side);
            if (distance < bestDistance) {
                best = side;
                bestDistance = distance;
            }
        }
        return best;
    }
}
