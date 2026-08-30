package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import com.hearthstead.settlement.state.GuardOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;
import java.util.List;

/**
 * Executes this exact Guard's explicit order above the ordinary clock.
 * Combat and alerts retain their higher priorities; when neither is active,
 * a valid order is a real movement/hold instruction rather than persisted UI
 * data with no consumer.
 */
public final class GuardOrderGoal extends Goal {
    private static final double ORDER_REACH_SQR = 2.25D;
    private static final int REPATH_INTERVAL = 20;
    private static final int PATROL_PAUSE_TICKS = 20;
    private static final int MAX_FAILED_PATHS = 8;
    private static final int RETRY_COOLDOWN_TICKS = 100;

    private final SettlerEntity settler;
    private BlockPos destination;
    private GuardOrder.Mode mode = GuardOrder.Mode.NONE;
    private List<BlockPos> route = List.of();
    private int routeIndex;
    private int routeDirection = 1;
    private GuardOrder.Traversal traversal = GuardOrder.Traversal.LOOP;
    private int pauseTicks;
    private int seenRevision;
    private int repathIn;
    private int failedPaths;
    private long retryAt;
    private boolean finished;

    public GuardOrderGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(settler.level() instanceof ServerLevel level)
            || settler.getTarget() != null
            || level.getGameTime() < retryAt) {
            return false;
        }
        Profession profession = settler.getProfession();
        if (!profession.martial()
            || !EquipmentRequests.readyForProfession(level, settler,
                profession)) {
            return false;
        }
        Settlement settlement = settler.settlement();
        return captureActiveOrder(level, settlement, level.getGameTime());
    }

    @Override
    public boolean canContinueToUse() {
        if (finished || settler.getTarget() != null
            || !(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        Profession profession = settler.getProfession();
        if (!profession.martial()
            || !EquipmentRequests.readyForProfession(level, settler,
                profession)) {
            return false;
        }
        Settlement settlement = settler.settlement();
        return captureActiveOrder(level, settlement, level.getGameTime());
    }

    private boolean captureActiveOrder(ServerLevel level, Settlement settlement,
                                       long now) {
        if (settlement == null) {
            destination = null;
            return false;
        }
        GuardAssignmentService.Validation validation =
            GuardAssignmentService.validate(level, settlement, settler, false);
        if (!validation.valid()) {
            destination = null;
            return false;
        }
        GuardOrder order = validation.order().orElseThrow();
        GuardOrder.Mode activeMode = order.modeAt(now);
        if (activeMode == GuardOrder.Mode.NONE) {
            destination = null;
            return false;
        }
        List<BlockPos> authoredRoute = order.patrolPoints();
        if (activeMode == GuardOrder.Mode.PATROL_ROUTE
            && (authoredRoute.size() < GuardOrder.MIN_PATROL_POINTS
                || authoredRoute.size() > GuardOrder.MAX_PATROL_POINTS)) {
            destination = null;
            return false;
        }
        BlockPos ordered = activeMode == GuardOrder.Mode.PATROL_ROUTE
            ? authoredRoute.getFirst() : order.pos().orElse(null);
        if (ordered == null) {
            destination = null;
            return false;
        }
        if (seenRevision != order.revision() || mode != activeMode
            || destination == null) {
            mode = activeMode;
            route = activeMode == GuardOrder.Mode.PATROL_ROUTE
                ? authoredRoute : List.of();
            routeIndex = activeMode == GuardOrder.Mode.PATROL_ROUTE
                ? nearestRouteIndex(route) : 0;
            routeDirection = 1;
            traversal = order.traversal();
            destination = activeMode == GuardOrder.Mode.PATROL_ROUTE
                ? route.get(routeIndex) : ordered.immutable();
            seenRevision = order.revision();
            repathIn = 0;
            failedPaths = 0;
            pauseTicks = 0;
        }
        return true;
    }

    private int nearestRouteIndex(List<BlockPos> points) {
        int nearest = 0;
        double nearestDistance = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            double distance = settler.blockPosition().distSqr(points.get(i));
            if (distance < nearestDistance) {
                nearest = i;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    @Override
    public void start() {
        finished = false;
        repathIn = 0;
        failedPaths = 0;
        pauseTicks = 0;
        settler.setActivity(SettlerActivity.PATROLLING);
        pathOrHold();
    }

    @Override
    public void tick() {
        if (destination == null) {
            finished = true;
            return;
        }
        settler.getLookControl().setLookAt(destination.getX() + 0.5D,
            destination.getY() + 0.8D, destination.getZ() + 0.5D);
        if (settler.blockPosition().distSqr(destination) <= ORDER_REACH_SQR) {
            settler.getNavigation().stop();
            if (mode == GuardOrder.Mode.PATROL_ROUTE
                && ++pauseTicks >= PATROL_PAUSE_TICKS) {
                advanceRoute();
                destination = route.get(routeIndex);
                pauseTicks = 0;
                repathIn = 0;
                failedPaths = 0;
            }
            return;
        }
        pauseTicks = 0;
        if (--repathIn <= 0) {
            pathOrHold();
        }
    }

    private void advanceRoute() {
        if (traversal == GuardOrder.Traversal.LOOP || route.size() <= 2) {
            routeIndex = (routeIndex + 1) % route.size();
            return;
        }
        int next = routeIndex + routeDirection;
        if (next >= route.size() || next < 0) {
            routeDirection = -routeDirection;
            next = routeIndex + routeDirection;
        }
        routeIndex = next;
    }

    private void pathOrHold() {
        if (destination == null
            || settler.blockPosition().distSqr(destination) <= ORDER_REACH_SQR) {
            settler.getNavigation().stop();
            repathIn = REPATH_INTERVAL;
            return;
        }
        Path path = settler.getNavigation().createPath(destination, 0);
        boolean moving = path != null && path.canReach()
            && settler.getNavigation().moveTo(path, 1.0D);
        repathIn = REPATH_INTERVAL;
        if (!moving && ++failedPaths >= MAX_FAILED_PATHS) {
            settler.recordRouteFailure("guard_order:no_path");
            retryAt = settler.level().getGameTime() + RETRY_COOLDOWN_TICKS;
            finished = true;
        }
    }

    @Override
    public void stop() {
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.IDLE);
        destination = null;
        mode = GuardOrder.Mode.NONE;
        route = List.of();
        routeIndex = 0;
        routeDirection = 1;
        traversal = GuardOrder.Traversal.LOOP;
        pauseTicks = 0;
        finished = false;
        repathIn = 0;
        failedPaths = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
