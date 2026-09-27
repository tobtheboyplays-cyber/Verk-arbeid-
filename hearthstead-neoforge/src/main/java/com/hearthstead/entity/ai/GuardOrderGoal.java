package com.hearthstead.entity.ai;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.path.RoadNavigation;
import com.hearthstead.settlement.BuildingManager;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import com.hearthstead.settlement.state.GuardOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * Executes this exact Guard's explicit order above the ordinary clock.
 * Combat retains its higher priority. Stand/Tower orders reject generic
 * alarm movement; other orders preserve their existing alarm behavior.
 * A valid order is a real movement/hold instruction rather than persisted UI
 * data with no consumer.
 */
public final class GuardOrderGoal extends Goal {
    private static final double ORDER_REACH_SQR = 2.25D;
    private static final int REPATH_INTERVAL = 20;
    private static final int PATROL_PAUSE_TICKS = 20;
    private static final int MAX_FAILED_PATHS = 8;
    private static final int RETRY_COOLDOWN_TICKS = 100;
    private static final double ESSENTIAL_NEED_SAFE_RADIUS = 6.0D;

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
        if (com.hearthstead.settlement.guard.BannerTeams.active(settler) != null) return false;
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
        if (shouldYieldToEssentialNeed(level, settlement)) return false;
        return captureActiveOrder(level, settlement, level.getGameTime());
    }

    @Override
    public boolean canContinueToUse() {
        if (com.hearthstead.settlement.guard.BannerTeams.active(settler) != null) return false;
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
        if (shouldYieldToEssentialNeed(level, settlement)) return false;
        return captureActiveOrder(level, settlement, level.getGameTime());
    }

    /**
     * A player's persisted post remains authoritative, but a hungry or
     * exhausted defender may reach a real essential need while its settlement
     * is safe. Combat, alerts, nearby hostiles and unreachable supplies keep
     * the order in control; this method never rewrites order state.
     */
    boolean shouldYieldToEssentialNeed(ServerLevel level,
                                               Settlement settlement) {
        if (settlement == null || settler.getTarget() != null
            || settlement.alertActive(level.getGameTime())) {
            return false;
        }
        BlockPos hearthPos = settler.getHearthPos();
        if (hearthPos == null || !level.hasChunkAt(hearthPos)
            || nearbyHostileMakesNeedUnsafe(level, hearthPos)) {
            return false;
        }
        if (shouldYieldToPhysicalMeal(level, settlement, hearthPos)) {
            return true;
        }
        return shouldYieldToCriticalRest(level, settlement, hearthPos);
    }

    private boolean shouldYieldToPhysicalMeal(ServerLevel level, Settlement settlement,
                                               BlockPos hearthPos) {
        if (settler.hasTavernSeat() || settler.getHunger() >= 40.0F) return false;
        HearthBlockEntity hearth = settler.hearth();
        if (hearth == null || !settlement.id.equals(hearth.getSettlementId())) {
            return false;
        }
        if (settler.hasMeal()) return true;
        boolean stocked = false;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            var stack = hearth.getInventory().getStackInSlot(slot);
            if (com.hearthstead.settlement.ReadyFood.isReadyMeal(stack)) {
                stocked = true;
                break;
            }
        }
        // A hungry defender may yield for one bounded Road prefix that is
        // strictly nearer to its real Hearth contact.  The prefix grants no
        // food authority: EatFromHearthGoal still needs live range and ray
        // contact before withdrawing a stack.  This lets a posted defender
        // cross a long, legal detour whose full route lies beyond follow
        // range, while unreachable or non-progressing supplies keep the
        // order in control.
        return stocked && HearthApproach.findProgressingContactPath(
            settler, level, hearthPos) != null;
    }

    private boolean shouldYieldToCriticalRest(ServerLevel level, Settlement settlement,
                                              BlockPos hearthPos) {
        // Once RestAtNightGoal has begun, retain its existing recovery exit
        // (energy 60/night wake) rather than cancelling it after one second.
        boolean recovering = settler.isSleeping()
            || settler.getActivity() == SettlerActivity.RESTING;
        // A real rest already owns MOVE. Do not re-probe a bed from a
        // sleeping body: that isolated navigation query may report false and
        // let this post reclaim MOVE, changing the activity to PATROLLING at
        // the next needs sample and preventing the sleeper from recovering.
        if (recovering) return true;
        if (settler.getEnergy() >= 12.0F) return false;
        BlockPos claimedBed = settler.getClaimedBed();
        if (claimedBed == null) {
            // RestAtNightGoal claims a real free bed as soon as it starts.
            // Probe that exact, bounded destination before asking whether the
            // homeless Hearth fallback is fully reachable; otherwise an
            // exhausted ordered defender cannot yield far enough to make the
            // existing bed claim at all.
            BlockPos freeBed = BuildingManager.findFreeBed(level, settlement);
            if (freeBed != null && canReachNeed(level, freeBed)) {
                return true;
            }
            // Without a bed claim, the Hearth is the rest fallback target.
            // Share its strict full-contact probe so raw Road radius-one
            // probing cannot report that safe fallback as unreachable.
            return HearthApproach.findReachableContactPath(settler, level, hearthPos) != null;
        }
        return canReachNeed(level, claimedBed);
    }

    private boolean canReachNeed(ServerLevel level, BlockPos target) {
        // Need consumers require a nearby physical contact, not occupation
        // of the Hearth/bed cell. A Hearth's collision top is only 11/16 of a
        // block, so an exact target.above()/range-zero node is not a stable
        // walking endpoint. The vanilla one-block arrival radius selects a
        // grounded adjacent contact square; EatFromHearthGoal still performs
        // its own exact distance and ray check before withdrawing any food.
        if (!level.hasChunkAt(target)) return false;
        // A reachability predicate must not rewrite a live post/patrol route's
        // target or stuck metadata before the order decides whether to yield.
        Path route = new RoadNavigation(settler, level).createPath(target, 1);
        return route != null && route.canReach();
    }

    private boolean nearbyHostileMakesNeedUnsafe(ServerLevel level,
                                                  BlockPos hearthPos) {
        AABB danger = settler.getBoundingBox().inflate(ESSENTIAL_NEED_SAFE_RADIUS)
            .minmax(new AABB(hearthPos).inflate(ESSENTIAL_NEED_SAFE_RADIUS));
        return !level.getEntitiesOfClass(Mob.class, danger, enemy ->
            enemy instanceof Enemy && enemy.isAlive() && enemy.canAttack(settler)
                && (enemy.distanceToSqr(settler)
                        <= ESSENTIAL_NEED_SAFE_RADIUS * ESSENTIAL_NEED_SAFE_RADIUS
                    || enemy.distanceToSqr(Vec3.atCenterOf(hearthPos))
                        <= ESSENTIAL_NEED_SAFE_RADIUS * ESSENTIAL_NEED_SAFE_RADIUS)).isEmpty();
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
        // A distant authored post can exceed the same bounded search as the
        // trip to a meal. Follow only a prefix that makes real progress toward
        // that unchanged destination; actual distance still decides arrival.
        BlockPos start = settler.blockPosition();
        BlockPos end = path == null || path.getNodeCount() == 0 ? null
            : path.getNodePos(path.getNodeCount() - 1);
        boolean progressing = end != null && !end.equals(start)
            && end.distSqr(destination) < start.distSqr(destination);
        boolean moving = path != null && (path.canReach() || progressing)
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
