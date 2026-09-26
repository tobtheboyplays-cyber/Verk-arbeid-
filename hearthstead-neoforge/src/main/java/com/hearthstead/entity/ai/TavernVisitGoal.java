package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.TavernSeatEntity;
import com.hearthstead.entity.TavernServingEntity;
import com.hearthstead.entity.TavernCue;
import com.hearthstead.settlement.TavernSeating;
import com.hearthstead.settlement.work.TavernHostService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import java.util.EnumSet;
import net.minecraft.world.phys.Vec3;

/** Approach a real reserved chair, then keep movement ownership throughout the visit. */
public final class TavernVisitGoal extends Goal {
    private final SettlerEntity actor;
    private TavernSeatEntity seat;
    private Path path;
    private long nextSearch;
    private int walking;
    private int nextApproachRepath;
    private long entryAttemptStartedAt = Long.MIN_VALUE;
    private long lastEntryAttemptElapsed = -1L;
    private String lastEntryPath = "none";
    private TavernSeating.MealCandidate mealCandidate;
    private String firstMealSearch = "not_searched";
    private boolean firstRetryPending;
    private long offerUntil;
    private boolean mealAttempt;
    private TavernServingEntity order;
    private int orderingWalkTicks;
    private int hostQueueTicks;
    public TavernVisitGoal(SettlerEntity actor) {
        this.actor = actor;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }
    private static final int MEAL_RETRY_TICKS = 40;
    private long nextMealSearch;
    /** Called by the higher-priority eating goal, so it cannot starve the initial visit search. */
    public boolean prefersMeal() {
        var loadedOrder = order != null ? order : actor.level() instanceof ServerLevel
            ? TavernHostService.orderForGuest(actor) : null;
        if (loadedOrder != null && !loadedOrder.isRemoved() && loadedOrder.isRestaurantOrder()
            && !"COMPLETE".equals(loadedOrder.restaurantStage().name())
            && !"CANCELLED".equals(loadedOrder.restaurantStage().name())
            && !"REFUND_PENDING".equals(loadedOrder.restaurantStage().name())
            && TavernSeating.mayVisit(actor) && actor.level().getGameTime() < loadedOrder.orderExpiresAt()
            && actor.level() instanceof ServerLevel level && loadedOrder.reservedSeatId() != null
            && level.getEntity(loadedOrder.reservedSeatId()) instanceof TavernSeatEntity reserved
            && reserved.owns(actor) && !reserved.isClosing()) return true;
        if (actor.hasMeal() || actor.getHunger() < 40 || actor.getHunger() >= 75
            || actor.isPassenger() || !TavernSeating.mayVisit(actor)) return false;
        // Once this visit holds a live order, the host session and table
        // claim make the pre-order offer check fail by design; that must not
        // hand the guest to the Hearth mid-visit.
        if (order != null && !order.isRemoved() && !order.isTerminalOrder() && seat != null
            && !seat.isRemoved() && seat.owns(actor)) return true;
        if (mealAttempt && seat != null) {
            return walking < 200 && TavernSeating.mealCandidateValid(actor, mealCandidate, seat);
        }
        long now = actor.level().getGameTime();
        if (mealCandidate != null && now <= offerUntil) {
            return TavernSeating.mealCandidateValid(actor, mealCandidate, null);
        }
        mealCandidate = null;
        // The meal search has its own short cadence; sharing the 200-tick
        // social-visit cooldown turned one transient miss into a whole
        // evening at the Hearth. The first empty offer gets one short retry
        // before the higher-priority Hearth goal takes MOVE (Codex
        // tavern-first-retry); later misses yield to the Hearth at once.
        if (now < nextMealSearch) return firstRetryPending;
        boolean firstAttempt = "not_searched".equals(firstMealSearch);
        nextMealSearch = now + MEAL_RETRY_TICKS;
        TavernSeating.MealSearch search = TavernSeating.findReachableMealDiagnostic(actor);
        mealCandidate = search.candidate();
        if (firstAttempt) firstMealSearch = "tick=" + now + "," + search.diagnostic();
        firstRetryPending = firstAttempt && mealCandidate == null;
        if (firstRetryPending) nextMealSearch = now + 20;
        offerUntil = now + 20;
        return mealCandidate != null || firstRetryPending;
    }
    /**
     * Read-only failure seam for the registered visit goal. It never searches,
     * reserves, changes navigation, or samples ownership through canUse().
     */
    public String diagnosticState() {
        long now = actor.level().getGameTime();
        return "last=" + lastEntryAttemptElapsed + "/" + lastEntryPath
            + ",n=" + nextSearch + ",cool=" + (now < nextSearch) + ",walk="
            + (walking >= 200 ? "expired" : walking > 0 ? "active" : "new")
            + ",meal=" + mealAttempt + ",seat=" + seatStateKey()
            + ",firstMealSearch={" + firstMealSearch + "}";
    }
    private String entryPathKey() {
        if (path == null) return "none";
        String target = path.getTarget().toShortString();
        String end = path.getNodeCount() == 0 ? "none"
            : path.getNode(path.getNodeCount() - 1).asBlockPos().toShortString();
        return target + ">" + end + "/" + (path.canReach() ? "reach" : "partial")
            + "/" + (path.isDone() ? "done" : "moving");
    }
    private String seatStateKey() {
        if (seat == null) return "none";
        var site = seat.site();
        if (site == null) return "missingSite";
        // Fixed bit order: removed, closing, owned, passenger, settled, moving, atEntry.
        return (seat.isRemoved() ? "1" : "0") + (seat.isClosing() ? "1" : "0")
            + (seat.owns(actor) ? "1" : "0") + (seat.hasPassenger(actor) ? "1" : "0")
            + (seat.isSettled() ? "1" : "0") + (seat.isTransitioning() ? "1" : "0")
            + (seat.atEntry(actor) ? "1" : "0");
    }
    @Override public boolean canUse() {
        if (actor.level() instanceof ServerLevel level) {
            var pending = TavernHostService.orderForGuest(actor);
            if (pending != null && pending.isRestaurantOrder()) {
                var reserved = pending.reservedSeatId() == null ? null : level.getEntity(pending.reservedSeatId());
                if (reserved == null && actor.level().getGameTime() < pending.orderExpiresAt()) return false;
                if (reserved instanceof TavernSeatEntity savedSeat && savedSeat.owns(actor)
                    && !savedSeat.isClosing() && TavernSeating.mayVisit(actor)) {
                    seat = savedSeat; order = pending;
                    path = actor.getNavigation().createPath(net.minecraft.core.BlockPos.containing(
                        com.hearthstead.entity.TavernSeatMotion.staging(seat.site())), 0);
                    return true;
                }
                TavernHostService.cancelReason(pending, "seat_unavailable");
                return false;
            }
            // An unloaded order remains its saved owner's responsibility; never create a second order.
            if (TavernHostService.hasOrderForGuest(actor)) return false;
        }
        if (TavernSeating.hasTavernSeat(actor) && TavernSeating.mayVisit(actor)
            && !((TavernSeatEntity) actor.getVehicle()).isClosing()) {
            seat = (TavernSeatEntity) actor.getVehicle(); path = null; return true;
        }
        if (mealCandidate != null && actor.level().getGameTime() <= offerUntil) {
            seat = TavernSeating.claimMeal(actor, mealCandidate);
            if (seat != null) { path = mealCandidate.path(); mealAttempt = true; prepareOrder(); return true; }
            mealCandidate = null;
        }
        if (actor.level().getGameTime() < nextSearch) return false;
        nextSearch = actor.level().getGameTime() + 200;
        TavernSeating.Search found = TavernSeating.reserveReachable(actor);
        seat = found.seat(); path = found.path();
        mealAttempt = false;
        if (seat != null) prepareOrder();
        else if (TavernSeating.mayVisit(actor)) actor.showTavernCue(TavernCue.LOOKING, 0, 40);
        return seat != null;
    }
    private void prepareOrder() {
        order = null;
        if (!(actor.level() instanceof ServerLevel level) || seat == null || actor.getHunger() >= 75) return;
        var tavern = TavernSeating.building(TavernSeating.visitSettlement(actor), seat.site().tavernId());
        if (tavern == null) return;
        for (var id : tavern.workers) {
            if (!(level.getEntity(id) instanceof SettlerEntity host)
                || !TavernHostService.authorizedHost(host, tavern.id) || TavernHostService.hasSession(host)) continue;
            order = TavernHostService.reserveQuote(host, actor, seat, TavernHostService.canOfferAle(host, actor));
            if (order != null) {
                if (!seat.holdForRestaurant(actor, order.getUUID(), order.orderExpiresAt())) {
                    TavernHostService.cancelReason(order, "seat_unavailable"); order = null; return;
                }

            }
            return;
        }
    }
    @Override public boolean canContinueToUse() {
        return seat != null && !seat.isRemoved() && !seat.isClosing() && TavernSeating.mayVisit(actor)
            && (actor.getVehicle() == seat || order != null && !order.isRemoved()
                && actor.level().getGameTime() < order.orderExpiresAt() || walking < 200);
    }
    @Override public void start() {
        walking = 0;
        orderingWalkTicks = 0;
        hostQueueTicks = 0;
        nextApproachRepath = 20;
        entryAttemptStartedAt = actor.level().getGameTime();
        if (actor.getVehicle() != seat) {
            actor.setActivity(SettlerActivity.TRAVELING);
            actor.getNavigation().moveTo(path, 1.0);
        }
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        // Queue before mounting: a busy host must not strand the next hungry diner in a chair.
        if (order == null && actor.getVehicle() != seat && actor.getHunger() < 75
            && actor.level() instanceof ServerLevel level && seat != null
            // A seat released last tick is closing: never queue a new order on it
            // in the extra every-tick pass before stop() (reliability sweep).
            && !seat.isRemoved() && !seat.isClosing()) {
            var tavern = TavernSeating.building(TavernSeating.visitSettlement(actor), seat.site().tavernId());
            if (tavern != null && TavernSeating.staffed(level, TavernSeating.visitSettlement(actor), tavern)) {
                actor.getNavigation().stop(); actor.setActivity(SettlerActivity.IDLE);
                actor.showTavernCue(TavernCue.WAITING, 0, 60);
                if (++hostQueueTicks % 20 == 1) prepareOrder();
                if (order == null) {
                    if (hostQueueTicks >= 200) {
                        actor.showTavernCue(TavernCue.CANCELLED, 0, 60); seat.release();
                    }
                    return;
                }
            }
        }
        if (order != null) {
            if (order.isRemoved() || "COMPLETE".equals(order.restaurantStage().name())) {

                actor.showTavernCue("COMPLETE".equals(order.restaurantStage().name())
                    ? TavernCue.DONE : TavernCue.CANCELLED, 0, 50);
                order = null;
                if (actor.getVehicle() == seat) seat.requestNormalExit();
                return;
            }
            String stage = order.restaurantStage().name();
            if ("CANCELLED".equals(stage) || "REFUND_PENDING".equals(stage)) {
                actor.showTavernCue("REFUND_PENDING".equals(stage) ? TavernCue.REFUND_PENDING : TavernCue.CANCELLED, 0, 60);
                if (actor.getVehicle() == seat) seat.requestNormalExit();
                else seat.release();
                return;
            }
            if (!order.isOrderPaid()) { approachHost(); return; }
        }
        if (actor.getVehicle() == seat) {
            actor.getNavigation().stop();
            if (!seat.isSettled()) { actor.setActivity(SettlerActivity.IDLE); return; }
            // The durable meal slice alone owns food, chew time, bite sound and completion.
            // An evening bard is chosen only from residents already seated here;
            // playing never moves the actor and yields at once to a meal.
            boolean bard = !actor.hasMeal() && com.hearthstead.settlement.TavernBard.isBard(actor);
            actor.setActivity(actor.hasMeal() ? SettlerActivity.EATING
                : bard ? SettlerActivity.PLAYING_MUSIC : SettlerActivity.IDLE);
            // Exact Tavern meal completion: the only place a seated meal finishes.
            if (actor.hasMeal() && actor.tickMeal())
                com.hearthstead.settlement.TavernBard.onRefreshmentCompleted(actor, seat.site().tavernId());
            if (order != null) actor.showTavernCue(actor.hasMeal() ? TavernCue.EATING
                : order.phase() == TavernServingEntity.Phase.DRINKING ? TavernCue.DRINKING : TavernCue.WAITING, 0, 60);
            var table = seat.site().tabletopCenter(actor.level());
            actor.getLookControl().setLookAt(table.x, table.y + .2, table.z, 25, 20);
        } else {
            walking++;
            if (seat.beginEntry(actor)) return;
            Vec3 target=com.hearthstead.entity.TavernSeatMotion.staging(seat.site());
            if(actor.position().distanceToSqr(target)<2.25
                && actor.level().noCollision(actor,actor.getBoundingBox().expandTowards(target.subtract(actor.position())))) {
                actor.getNavigation().stop();
                if(actor.position().distanceToSqr(target)>.0009) {
                    double speed=net.minecraft.util.Mth.clamp(actor.position().distanceTo(target)*2,.16,.5);
                    actor.getMoveControl().setWantedPosition(target.x,target.y,target.z,speed);
                } else {
                    actor.getMoveControl().setWantedPosition(actor.getX(),actor.getY(),actor.getZ(),0);
                    float yaw=seat.site().dinerFacing().toYRot();
                    actor.setYRot(net.minecraft.util.Mth.approachDegrees(actor.getYRot(),yaw,12));
                    actor.setYBodyRot(net.minecraft.util.Mth.approachDegrees(actor.yBodyRot,yaw,12));
                    actor.setYHeadRot(net.minecraft.util.Mth.approachDegrees(actor.getYHeadRot(),yaw,12));
                }
            } else if (actor.getNavigation().isDone() && walking >= nextApproachRepath) {
                // Door movement or a short-lived obstruction can end navigation
                // before the reserved chair's entry point. Keep this same chair
                // within the original 200-tick budget and plan from the actual
                // new position, instead of immediately sending the guest outside.
                nextApproachRepath = walking + 20;
                Path retry = actor.getNavigation().createPath(
                    net.minecraft.core.BlockPos.containing(target), 0);
                if (retry != null && retry.canReach()) {
                    path = retry;
                    actor.getNavigation().moveTo(retry, 1.0);
                }
            }
        }
    }
    private void approachHost() {
        if (!(actor.level() instanceof ServerLevel level)) return;
        if (!(level.getEntity(order.hostId()) instanceof SettlerEntity host)) {
            actor.getNavigation().stop(); actor.showTavernCue(TavernCue.WAITING, 0, 60); return;
        }
        actor.getLookControl().setLookAt(host, 25, 20);
        if (TavernHostService.quoteAtHostContact(host, actor, order.getUUID())) {
            actor.getNavigation().stop(); actor.setActivity(SettlerActivity.IDLE);
            if (TavernHostService.acceptAtHostContact(host, actor, order.getUUID())) {
                actor.showTavernCue(TavernHostService.quotedPrice(order) > 0 ? TavernCue.PAYING : TavernCue.WAITING,
                    TavernHostService.quotedPrice(order), 40);
                walking = 0; nextApproachRepath = 20;
                path = actor.getNavigation().createPath(net.minecraft.core.BlockPos.containing(
                    com.hearthstead.entity.TavernSeatMotion.staging(seat.site())), 0);
                if (path != null && path.canReach()) actor.getNavigation().moveTo(path, 1.0);
            }
            return;
        }
        actor.setActivity(SettlerActivity.TRAVELING);
        actor.showTavernCue(TavernCue.ORDERING, 0, 60);
        if (++orderingWalkTicks > 360) {
            TavernHostService.cancelReason(order, "host_unreachable");
            actor.showTavernCue(TavernCue.BLOCKED, 0, 60); return;
        }
        if (orderingWalkTicks % 20 == 1) actor.getNavigation().moveTo(host, 1.0);
    }
    @Override public void stop() {
        if (order != null && !order.isRemoved() && !"COMPLETE".equals(order.restaurantStage().name()))
            TavernHostService.cancelReason(order, "visit_interrupted");

        order = null;
        actor.getNavigation().stop();
        long now = actor.level().getGameTime();
        // Only a full live entry budget earns a short retry; interruptions
        // keep the normal scan cooldown.
        boolean exhaustedReservedEntry = seat != null && !seat.isRemoved() && !seat.isClosing()
            && TavernSeating.mayVisit(actor) && walking >= 200
            && entryAttemptStartedAt != Long.MIN_VALUE && now - entryAttemptStartedAt >= 200;
        lastEntryAttemptElapsed = entryAttemptStartedAt == Long.MIN_VALUE ? -1L : now - entryAttemptStartedAt;
        lastEntryPath = entryPathKey();
        if (seat != null) seat.release();
        seat = null; path = null;
        mealCandidate = null; mealAttempt = false; entryAttemptStartedAt = Long.MIN_VALUE;
        nextSearch = now + (exhaustedReservedEntry ? 20 : 200);
        actor.setActivity(SettlerActivity.IDLE);
    }
}
