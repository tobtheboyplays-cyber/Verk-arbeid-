package com.hearthstead.entity.path;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.block.StairBlock;
import com.hearthstead.entity.SettlerEntity;
import java.util.Set;

/**
 * Ordinary ground navigation, with {@link RoadNodeEvaluator} in it.
 *
 * <p>Everything else about how a settler walks — doors, water, fall damage —
 * stays vanilla on purpose. Nearby destinations may use a bounded longer
 * route when rooms and stairs require a detour.
 */
public class RoadNavigation extends GroundPathNavigation {

    private static final float DETOUR_RANGE = 96.0F;
    private static final float DETOUR_NODE_MULTIPLIER = 8.0F;
    private boolean searchingDetour;
    private float requestedNodeMultiplier = 1.0F;
    private long lastDetourSearch = Long.MIN_VALUE;
    private Set<BlockPos> lastDetourAttemptTargets = Set.of();
    private BlockPos lastDetourAttemptOrigin;
    private Set<BlockPos> detourTargets = Set.of();

    @Override
    public void setMaxVisitedNodesMultiplier(float multiplier) {
        requestedNodeMultiplier = multiplier;
        super.setMaxVisitedNodesMultiplier(multiplier);
    }

    @Override
    public void resetMaxVisitedNodesMultiplier() {
        requestedNodeMultiplier = 1.0F;
        super.resetMaxVisitedNodesMultiplier();
    }

    /**
     * FOLLOW_RANGE also limits the total walked path length in vanilla. A
     * nearby downstairs destination can require a much longer indoor route.
     * Retry an incomplete ordinary search once with a bounded detour budget,
     * preserving vanilla collision, stair, door and fall rules. This lives on
     * the actual navigator so opening a door and recomputing keeps the policy.
     */
    @Override
    protected Path createPath(Set<BlockPos> targets, int padding, boolean above,
                              int accuracy, float range) {
        // BH-31: the flag describes THIS search only; a stale "deferred" from an
        // earlier query must never make a plain refusal look like a budget wait.
        deferredLastSearch = false;
        boolean eligible = mob instanceof SettlerEntity && mob.getTarget() == null && !above
            && range > 0 && range < DETOUR_RANGE && requestedNodeMultiplier == 1.0F
            && !targets.isEmpty();
        // A real successful detour remains the policy for its destination.
        // Door-triggered recompute clears vanilla's path before calling here;
        // throttling that refresh would replace the route with a short partial.
        if (eligible && detourTargets.containsAll(targets) && targets.stream().allMatch(target ->
                target.distSqr(mob.blockPosition()) <= (double) DETOUR_RANGE * DETOUR_RANGE)) {
            Path refreshed = detourPath(targets, padding, above, accuracy);
            if (refreshed == null || !refreshed.canReach()) detourTargets = Set.of();
            return refreshed;
        }
        Path ordinary = super.createPath(targets, padding, above, accuracy, range);
        if (ordinary != null && ordinary.canReach()) return ordinary;
        // A worker may be on an open stair/landing while leaving a building.
        // Admit its existing destination within the bounded region regardless
        // of roof cover; never change work selection or combat aggro.
        float targetRange = DETOUR_RANGE;
        if (!eligible || targets.stream().anyMatch(target ->
                target.distSqr(mob.blockPosition()) > (double) targetRange * targetRange)) return ordinary;
        long now = level.getGameTime();
        // Opening a door can refresh navigation immediately before a worker
        // starts a return trip. Throttle only an identical retry: the same
        // destination from a new stair/door position needs a fresh long-route
        // search even when it happens within the next few ticks.
        if (lastDetourSearch != Long.MIN_VALUE && now - lastDetourSearch < 20L
                && lastDetourAttemptTargets.equals(targets)
                && mob.blockPosition().equals(lastDetourAttemptOrigin)) return ordinary;
        Path detour = detourPath(targets, padding, above, accuracy);
        if (detour != null && detour.canReach()) {
            detourTargets = targets.stream().map(BlockPos::immutable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        return detour != null ? detour : ordinary;
    }

    private Path detourPath(Set<BlockPos> targets, int padding, boolean above, int accuracy) {
        // Reuse a live completed-search route just as vanilla does. Incomplete
        // cached paths still require a fresh expanded search.
        if (path != null && !path.isDone() && path.canReach()
                && targets.contains(path.getTarget())) return path;
        deferredLastSearch = !takeExpandedSearch();
        if (deferredLastSearch) return null;
        long now = level.getGameTime();
        lastDetourSearch = now;
        lastDetourAttemptTargets = targets.stream().map(BlockPos::immutable)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        lastDetourAttemptOrigin = mob.blockPosition().immutable();
        // Vanilla returns an installed unfinished path before searching. A
        // partial path must not prevent this one retry; restore the installed
        // path because createPath itself must never commandeer movement.
        Path installed = path;
        try {
            path = null;
            searchingDetour = true;
            super.setMaxVisitedNodesMultiplier(DETOUR_NODE_MULTIPLIER);
            return super.createPath(targets, padding, above, accuracy, DETOUR_RANGE);
        } finally {
            searchingDetour = false;
            path = installed;
            super.setMaxVisitedNodesMultiplier(requestedNodeMultiplier);
        }
    }

    /** Shared explicit building route for detached bed/contact planners. */
    public Path createBuildingPath(Set<BlockPos> targets, int accuracy) {
        if (targets.isEmpty() || targets.stream().anyMatch(target ->
                target.distSqr(mob.blockPosition()) > (double) DETOUR_RANGE * DETOUR_RANGE)) return null;
        Path result = detourPath(targets, 8, false, accuracy);
        if (result != null && result.canReach()) {
            detourTargets = targets.stream().map(BlockPos::immutable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        return result;
    }

    /**
     * A route planned elsewhere (a detached home or contact planner) and
     * installed here must also become this navigator's target. Vanilla's
     * moveTo(Path) keeps the previous targetPos, so the next recompute (a
     * door the settler just opened, stuck recovery) silently re-planned to
     * the old destination: a settler climbing the owner's outside ladder to
     * bed turned round at the loft door and went back down, for ever.
     */
    @Override
    public boolean moveTo(@javax.annotation.Nullable Path newPath, double speed) {
        boolean started = super.moveTo(newPath, speed);
        if (started && newPath != null) {
            adoptedTarget = newPath.getTarget().equals(getTargetPos()) ? null
                : newPath.getTarget().immutable();
        }
        return started;
    }

    // Vanilla keeps targetPos private; remember an installed route's target
    // here and re-plan to it (which also re-syncs vanilla's own target).
    private BlockPos adoptedTarget;
    private long lastAdoptedRecompute = Long.MIN_VALUE;

    @Override
    public void recomputePath() {
        if (adoptedTarget == null) {
            super.recomputePath();
            return;
        }
        long now = level.getGameTime();
        if (lastAdoptedRecompute != Long.MIN_VALUE && now - lastAdoptedRecompute <= 20L) return;
        lastAdoptedRecompute = now;
        BlockPos target = adoptedTarget;
        path = null;
        path = createPath(target, 0);
        if (path != null) adoptedTarget = null;
    }

    @Override
    public void stop() {
        detourTargets = Set.of();
        lastDetourAttemptOrigin = null;
        adoptedTarget = null;
        resetProgress();
        super.stop();
    }

    @Override
    protected void followThePath() {
        if (path != null && !path.isDone() && mob.onClimbable()) {
            var next = path.getNextNodePos();
            var feet = mob.blockPosition();
            if (next.getX() == feet.getX() && next.getZ() == feet.getZ()
                    && IndoorBlocks.climbable(level, next)) {
                // Ground navigation accepts a one-block Y error. On a ladder
                // that skips the rung before the body physically reaches it.
                if (Math.abs(mob.getY() - next.getY()) < .12) path.advance();
                doStuckDetection(getTempMobPos());
                return;
            }
        }
        super.followThePath();
    }

    /**
     * Vanilla recalculates a per-node timeout when a route advances but keeps
     * the elapsed timer from prior nodes. A long, healthy indoor route can
     * therefore time out before its next door despite making progress. Count
     * time against the current node only; unchanged nodes retain vanilla's
     * ordinary stuck detection and timeout behavior.
     */
    @Override
    protected void doStuckDetection(net.minecraft.world.phys.Vec3 position) {
        if (path != null && !path.isDone()
                && !path.getNextNodePos().equals(timeoutCachedNode)) {
            timeoutTimer = 0L;
        }
        super.doStuckDetection(position);
    }

    @Override
    protected boolean canUpdatePath() {
        return mob.onClimbable() || super.canUpdatePath()
            || hasSupportedStairTransition();
    }

    /**
     * Walking up a stair can briefly report {@code onGround=false} despite a
     * solid stair directly under the settler. Permit a route refresh only for
     * that narrow supported transition, never for a jump or a free fall.
     */
    private boolean hasSupportedStairTransition() {
        double verticalVelocity = mob.getDeltaMovement().y;
        if (verticalVelocity < -0.15D || verticalVelocity > 0.35D) {
            return false;
        }
        BlockPos support = mob.blockPosition().below();
        return level.hasChunkAt(support)
            && level.getBlockState(support).getBlock() instanceof StairBlock;
    }

    @Override
    public void tick() {
        yielding = shouldYield() && yieldTicks < MAX_YIELD_TICKS;
        if (yielding) {
            yieldTicks++;
            mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0D);
            monitorProgress();
            return;
        }
        if (yieldTicks > 0 && !shouldYield()) yieldTicks = 0;
        super.tick();
        monitorProgress();
        if (path == null || path.isDone()) return;
        var next = path.getNextNodePos();
        var feet = mob.blockPosition();
        // Only real, continuous climbables may supply vertical motion. Never
        // propel a grounded worker towards an arbitrary elevated path node.
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(next)
                || !IndoorBlocks.climbable(level, feet)
                || !IndoorBlocks.climbable(level, next)
                || feet.getX() != next.getX() || feet.getZ() != next.getZ()
                || Math.abs(next.getY() - feet.getY()) > 1) return;
        double dy = next.getY() - mob.getY();
        if (Math.abs(dy) < .12) return;
        double vertical = Math.copySign(.12, dy);
        if (!level.noCollision(mob, mob.getBoundingBox().move(0, vertical, 0))) return;
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
        mob.setDeltaMovement((next.getX() + .5 - mob.getX()) * .15,
            vertical, (next.getZ() + .5 - mob.getZ()) * .15);
        mob.resetFallDistance();
    }

    // ---- Stuck recovery -------------------------------------------------
    //
    // MineColonies-style escalation for a live route that stops making
    // progress: first re-plan from where the settler really is, then give
    // one small physical nudge towards the next node, then give up so the
    // owning goal can choose its fallback. Never teleports.
    static final int REPATH_AFTER = 30;
    static final int NUDGE_AFTER = 60;
    static final int GIVE_UP_AFTER = 90;
    private static final double PROGRESS_SQR = 0.3D * 0.3D;
    private net.minecraft.world.phys.Vec3 progressAnchor;
    private double bestNodeDistance = Double.MAX_VALUE;
    private int progressNodeIndex = -1;
    private BlockPos progressTarget;
    private int stalledTicks;

    private void monitorProgress() {
        if (path == null || path.isDone() || mob.isPassenger() || mob.isSleeping()) {
            resetProgress();
            return;
        }
        var here = mob.position();
        if (progressTarget == null || !progressTarget.equals(path.getTarget())) {
            resetProgress();
            progressTarget = path.getTarget();
            progressAnchor = here;
            progressNodeIndex = path.getNextNodeIndex();
            return;
        }
        // Progress is getting closer to the next node or advancing along the
        // route. Merely moving is not progress: two settlers shoving each
        // other in a doorway move plenty and get nowhere.
        var nextNode = path.getNextNodePos();
        double toNext = here.distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(nextNode));
        if (path.getNextNodeIndex() > progressNodeIndex
                || toNext < bestNodeDistance - Math.sqrt(PROGRESS_SQR)) {
            progressAnchor = here;
            progressNodeIndex = path.getNextNodeIndex();
            bestNodeDistance = toNext;
            stalledTicks = 0;
            return;
        }
        if (yielding) return;
        stalledTicks++;
        if (stalledTicks == REPATH_AFTER) {
            // Stage 1: a fresh route from the current cell, same target.
            recomputePath();
            if (path != null) progressNodeIndex = path.getNextNodeIndex();
        } else if (stalledTicks == NUDGE_AFTER && path != null && !path.isDone()) {
            // Stage 2: a small physical nudge towards the next node's centre.
            // This frees corner snags on spiral steps, door sills and fence
            // posts. It is a velocity, never a position change.
            var next = path.getNextNodePos();
            double dx = next.getX() + 0.5D - mob.getX();
            double dz = next.getZ() + 0.5D - mob.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1.0E-3D) {
                double push = Math.min(0.2D, len);
                mob.setDeltaMovement(mob.getDeltaMovement().add(dx / len * push, 0.0D, dz / len * push));
            }
            if (next.getY() > mob.getY() + 0.5D && mob.onGround()) {
                mob.getJumpControl().jump();
            }
        } else if (stalledTicks >= GIVE_UP_AFTER) {
            // Stage 3: hand the route back so the owning goal chooses its
            // fallback target. Record why for tests and inspection.
            if (mob instanceof SettlerEntity settler) {
                settler.recordRouteFailure("nav:stuck@" + mob.blockPosition().toShortString()
                    + "->" + (path == null ? "?" : path.getTarget().toShortString()));
            }
            stop();
        }
    }

    private void resetProgress() {
        progressTarget = null;
        progressAnchor = null;
        progressNodeIndex = -1;
        bestNodeDistance = Double.MAX_VALUE;
        stalledTicks = 0;
    }

    // ---- Doorway courtesy ----------------------------------------------
    //
    // A one-wide passage (door, gate, gap between walls) fits one body. When
    // two settlers both want the same passage cell the one already in it,
    // or else the older entity, goes first; the other stands still for a
    // moment instead of both shoving at the sill for minutes.
    private static final int MAX_YIELD_TICKS = 40;
    private boolean yielding;
    private int yieldTicks;

    private boolean shouldYield() {
        if (!(mob instanceof SettlerEntity) || path == null || path.isDone()) return false;
        BlockPos next = path.getNextNodePos();
        if (!narrowPassage(next)) return false;
        net.minecraft.world.phys.AABB passage = new net.minecraft.world.phys.AABB(next);
        if (mob.getBoundingBox().intersects(passage)) return false;
        for (SettlerEntity other : level.getEntitiesOfClass(SettlerEntity.class,
                mob.getBoundingBox().inflate(1.5D), o -> o != mob && o.isAlive())) {
            if (other.getBoundingBox().intersects(passage)) return true;
            Path theirs = other.getNavigation().getPath();
            if (theirs != null && !theirs.isDone() && theirs.getNextNodePos().equals(next)
                    && other.getId() < mob.getId()) {
                return true;
            }
        }
        return false;
    }

    private boolean narrowPassage(BlockPos cell) {
        if (!level.hasChunkAt(cell)) return false;
        if (IndoorBlocks.openablePassage(level, cell) != null) return true;
        boolean walledX = solidAt(cell.east()) && solidAt(cell.west());
        boolean walledZ = solidAt(cell.north()) && solidAt(cell.south());
        return walledX || walledZ;
    }

    private boolean solidAt(BlockPos pos) {
        return level.hasChunkAt(pos) && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** Inspection seam: ticks the current route has made no progress. */
    public int stalledTicks() {
        return stalledTicks;
    }

    // ---- Search budget -------------------------------------------------
    //
    // An expanded indoor search visits up to eight times the ordinary node
    // budget. Many settlers re-planning on the same tick (bedtime, a door
    // opening) must not stack those into one spike: each level allows a
    // few expanded searches per tick; the rest keep the ordinary result and
    // their goals retry on their next interval.
    static final int EXPANDED_SEARCHES_PER_TICK = 4;
    private static final java.util.Map<Level, long[]> EXPANDED_BUDGET = new java.util.WeakHashMap<>();

    private boolean deferredLastSearch;

    /**
     * True when the last expanded search was skipped only because this
     * tick's budget was spent. Not a route failure: retry within a few ticks.
     */
    public boolean deferredLastSearch() {
        return deferredLastSearch;
    }

    /**
     * GameTest seam: frees this tick's shared expanded-search budget, so a
     * detached route probe measures the route itself rather than how many
     * other settlers (in parallel tests) already searched this tick.
     */
    public static void resetExpandedBudgetForTests(Level level) {
        EXPANDED_BUDGET.remove(level);
    }

    private boolean takeExpandedSearch() {
        long now = level.getGameTime();
        long[] slot = EXPANDED_BUDGET.computeIfAbsent(level, ignored -> new long[]{now, 0});
        if (slot[0] != now) {
            slot[0] = now;
            slot[1] = 0;
        }
        if (slot[1] >= EXPANDED_SEARCHES_PER_TICK) return false;
        slot[1]++;
        return true;
    }

    public RoadNavigation(Mob mob, Level level) {
        super(mob, level);
        // FloatGoal enables this on the live navigator. Detached road, meal
        // and home-route probes must share that policy so a stranded settler
        // can plan a real route from water to a grounded contact cell.
        setCanFloat(true);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new RoadNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        // Closed wooden doors must count as walkable or every path ENDS at
        // the door line and OpenDoorGoal never sees a door node to open.
        // Proven live (20260825T183505Z): a courier froze at the warehouse
        // door and two settlers froze at their own house doors at bedtime,
        // all at the same 0.31-block standoff, while the one whose path
        // happened to need no door slept fine. canPassDoors alone only
        // permits OPEN doors.
        this.nodeEvaluator.setCanOpenDoors(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes) {
            @Override
            public Path findPath(net.minecraft.world.level.PathNavigationRegion region,
                                 Mob actor, Set<BlockPos> targets, float range,
                                 int accuracy, float multiplier) {
                // Vanilla couples spatial radius and accumulated route length.
                // The observed 75-node U-hall route needs a larger walked budget
                // even with 4096 expansions. Retain the 96-block region while
                // allowing 192 units of accumulated search distance inside it.
                return super.findPath(region, actor, targets,
                    searchingDetour ? range * 2.0F : range, accuracy, multiplier);
            }
        };
    }
}
