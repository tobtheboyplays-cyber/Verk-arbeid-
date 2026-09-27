package com.hearthstead.entity.ai;

import com.hearthstead.Hearthstead;
import com.hearthstead.conversation.ConversationService;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.path.IndoorBlocks;
import com.hearthstead.entity.path.PathingConfig;
import com.hearthstead.entity.path.RoadNavigation;
import com.hearthstead.entity.path.SealedGates;
import com.hearthstead.settlement.BlessingEffects;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.TownChat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Universal settler stuck watchdog (owner, 27 Sep: "settlers must never get
 * stuck, and there must be an emergency fallback if it happens").
 *
 * <p>Runs on the server for every settler, whatever its job, once per second.
 * A settler that <em>wants to move</em> (its navigation has a live route, or a
 * goal asked to walk somewhere in the last few seconds and it is not already
 * there) but has made less than half a block of progress escalates:
 * <ol>
 *   <li>10 s: re-plan (the route is dropped; the goal plans a fresh one);</li>
 *   <li>20 s / 30 s / 40 s: step off to the nearest cell with a whole floor
 *       and full head room within two blocks, by walking (never a jump);</li>
 *   <li>45 s: optional ([pathing] routeHome): interrupt the task, walk home;</li>
 *   <li>{@code rescueAfterSeconds} (90 s): EMERGENCY RESCUE when the settler is
 *       provably shut in (no way out within a bounded search): it is moved to
 *       the nearest open cell outside its pocket, or next to the Banner, with a
 *       soft poof, a sound and one town chat line. Never during a raid, at most
 *       once per settler per 5 minutes, and no block is ever changed.</li>
 * </ol>
 * A settler inside a suffocating block is freed at once.
 *
 * <p>Legitimately still settlers are never flagged: sleeping, seated, riding,
 * leashed, talking, fighting, drilling, on post, working at a station
 * (nothing to walk to) and martial settlers during a raid. The Builder and a
 * Miner escaping a pit own their own recovery; the watchdog only adds the
 * last-resort rescue for them.
 */
public final class SettlerStuckWatchdog {
    public static final int SAMPLE_TICKS = 20;
    static final int REPATH_TICKS = 200;
    static final int UNWEDGE_TICKS = 400;
    static final int UNWEDGE_AGAIN_TICKS = 600;
    static final int UNWEDGE_LAST_TICKS = 800;
    static final int ROUTE_HOME_TICKS = 900;
    static final long RESCUE_COOLDOWN_TICKS = 6000L;
    /** A goal's move request counts as "wanting to move" for this long. */
    static final int INTENT_WINDOW = 60;
    static final double PROGRESS = 0.5D;
    static final double ROAM = 3.0D;
    static final double ARRIVED_H = 2.5D;
    static final int POCKET_MAX_CELLS = 200;
    static final int POCKET_RADIUS = 8;
    static final int RESCUE_SEARCH = 8;

    private static final Map<SettlerEntity, State> STATES = new WeakHashMap<>();

    // Aggregate counters for tonight's check (/hearthstead why and the log).
    public static final AtomicLong REPATHS = new AtomicLong();
    public static final AtomicLong UNWEDGES = new AtomicLong();
    public static final AtomicLong ROUTE_HOMES = new AtomicLong();
    public static final AtomicLong RESCUES = new AtomicLong();
    public static final AtomicLong FREED = new AtomicLong();

    private SettlerStuckWatchdog() {
    }

    /** Per-settler, transient state. Never saved. */
    static final class State {
        Vec3 origin;
        BlockPos target;
        double best = Double.MAX_VALUE;
        long stuckTicks;
        int navSamples;
        int stage;
        long lastRescueTick = Long.MIN_VALUE;
        long lastRescueCheck = Long.MIN_VALUE;
        long lastFreeTick = Long.MIN_VALUE;
        String lastAction = "none";
        long lastActionTick = Long.MIN_VALUE;
        String status = "idle";
        int rescues;
        int freed;
        @Nullable Boolean testEnabled;
        @Nullable Integer testRescueTicks;

        void restart(Vec3 here, @Nullable BlockPos target, double dist, String why) {
            origin = here;
            this.target = target;
            best = dist;
            stuckTicks = 0;
            navSamples = 0;
            stage = 0;
            status = why;
        }

        void clear(String why) {
            origin = null;
            target = null;
            best = Double.MAX_VALUE;
            stuckTicks = 0;
            navSamples = 0;
            stage = 0;
            status = why;
        }
    }

    private static State state(SettlerEntity settler) {
        return STATES.computeIfAbsent(settler, s -> new State());
    }

    // ------------------------------------------------------------ entry ---

    /** Called once per server tick from SettlerEntity#aiStep. Cheap. */
    public static void tick(SettlerEntity settler) {
        if (!(settler.level() instanceof ServerLevel level) || !settler.isAlive()) {
            return;
        }
        int phase = Math.floorMod(settler.getId(), SAMPLE_TICKS);
        if (settler.tickCount % 5 == phase % 5 && settler.tickCount > 5) {
            State st = STATES.get(settler);
            boolean on = st != null && st.testEnabled != null ? st.testEnabled : PathingConfig.stuckWatchdog();
            if (on && insideBlock(level, settler)) {
                free(level, settler, state(settler));
                return;
            }
        }
        if (settler.tickCount % SAMPLE_TICKS != phase) {
            return;
        }
        sample(level, settler, level.getGameTime());
    }

    static void sample(ServerLevel level, SettlerEntity s, long now) {
        State st = state(s);
        boolean on = st.testEnabled != null ? st.testEnabled : PathingConfig.stuckWatchdog();
        if (!on) {
            st.clear("off");
            return;
        }
        String excluded = exclusion(level, s);
        if (excluded != null) {
            st.clear(excluded);
            return;
        }
        PathNavigation nav = s.getNavigation();
        Path path = nav.getPath();
        boolean navActive = path != null && !path.isDone();
        BlockPos target = null;
        boolean recentIntent = false;
        if (nav instanceof RoadNavigation road) {
            recentIntent = now - road.lastMoveIntentTick() <= INTENT_WINDOW;
            if (recentIntent) {
                target = road.lastMoveIntentTarget();
            }
        }
        if (navActive) {
            target = path.getTarget();
        }
        boolean wants = navActive || (recentIntent && target != null);
        if (!wants) {
            st.clear("idle");
            return;
        }
        Vec3 here = s.position();
        if (arrived(here, target)) {
            st.clear("arrived");
            return;
        }
        double dist = distTo(here, target);
        if (st.origin == null) {
            st.restart(here, target, dist, "moving");
            return;
        }
        boolean progress = false;
        if (target != null && target.equals(st.target)) {
            if (dist < st.best - PROGRESS) {
                progress = true;
            }
        } else {
            // A new destination gives no credit by itself: a shut-in settler
            // whose goal keeps choosing new spots is still shut in.
            st.target = target;
            st.best = dist;
        }
        if (here.distanceTo(st.origin) >= ROAM) {
            progress = true;
        }
        if (progress) {
            st.restart(here, target, dist, "moving");
            return;
        }
        st.stuckTicks += SAMPLE_TICKS;
        if (navActive) {
            st.navSamples++;
        }
        st.status = "stuck " + (st.stuckTicks / 20) + "s";
        escalate(level, s, st, now, navActive);
    }

    // ------------------------------------------------------- escalation ---

    private static void escalate(ServerLevel level, SettlerEntity s, State st, long now, boolean navActive) {
        long t = st.stuckTicks;
        boolean selfRecovering = s.getProfession() == Profession.BUILDER;
        if (t >= REPATH_TICKS && st.stage < 1) {
            st.stage = 1;
            if (!selfRecovering) {
                s.getNavigation().stop();
                act(s, st, now, "stuck_repath", REPATHS);
            }
        }
        if (!selfRecovering) {
            boolean wedged = st.navSamples > 0 || !goodCell(level, s, s.blockPosition());
            if (t >= UNWEDGE_TICKS && st.stage < 2) {
                st.stage = 2;
                if (wedged) {
                    unwedge(level, s, st, now);
                }
            }
            if (t >= UNWEDGE_AGAIN_TICKS && st.stage < 3) {
                st.stage = 3;
                if (wedged) {
                    unwedge(level, s, st, now);
                }
            }
            if (t >= UNWEDGE_LAST_TICKS && st.stage < 4) {
                st.stage = 4;
                if (wedged) {
                    unwedge(level, s, st, now);
                }
            }
            if (t >= ROUTE_HOME_TICKS && st.stage < 5) {
                st.stage = 5;
                if (PathingConfig.routeHome()) {
                    routeHome(s, st, now);
                }
            }
        }
        long rescueTicks = st.testRescueTicks != null ? st.testRescueTicks
            : PathingConfig.rescueAfterSeconds() * 20L;
        if (t >= rescueTicks && (st.lastRescueCheck == Long.MIN_VALUE || now - st.lastRescueCheck >= 100L)) {
            st.lastRescueCheck = now;
            tryRescue(level, s, st, now, t >= rescueTicks * 2 && st.navSamples * (long) SAMPLE_TICKS * 2 >= t);
        }
    }

    private static void act(SettlerEntity s, State st, long now, String action, AtomicLong counter) {
        st.lastAction = action;
        st.lastActionTick = now;
        counter.incrementAndGet();
        Hearthstead.LOGGER.info("{} settler={} pos={} stuck={}s goal={}", action, s.getSettlerName(),
            s.blockPosition().toShortString(), st.stuckTicks / 20, runningGoals(s));
    }

    /** Step off to the nearest cell with a whole floor and full head room, by walking. */
    private static void unwedge(ServerLevel level, SettlerEntity s, State st, long now) {
        BlockPos me = s.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos cell = me.offset(dx, dy, dz);
                    if ((dx == 0 && dz == 0) || !goodCell(level, s, cell)) {
                        continue;
                    }
                    double d = cell.distSqr(me);
                    if (d < bestDist) {
                        bestDist = d;
                        best = cell;
                    }
                }
            }
        }
        if (best == null) {
            st.lastAction = "unwedge_no_cell";
            st.lastActionTick = now;
            return;
        }
        double y = standY(level, s, best);
        PathNavigation nav = s.getNavigation();
        nav.stop();
        if (!nav.moveTo(best.getX() + 0.5D, y, best.getZ() + 0.5D, 1.0D)) {
            // No route even for two blocks: walk straight at it.
            s.getMoveControl().setWantedPosition(best.getX() + 0.5D, y, best.getZ() + 0.5D, 1.0D);
        }
        act(s, st, now, "stuck_unwedge", UNWEDGES);
    }

    private static void routeHome(SettlerEntity s, State st, long now) {
        BlockPos home = s.getHearthPos();
        if (home == null) {
            return;
        }
        // The goals' own stop() is the ordinary interruption path: it releases
        // their reservations exactly as when a higher-priority goal takes over.
        for (WrappedGoal goal : new ArrayList<>(s.goalSelector.getAvailableGoals())) {
            if (goal.isRunning()) {
                goal.stop();
            }
        }
        s.getNavigation().stop();
        s.getNavigation().moveTo(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D, 1.0D);
        act(s, st, now, "stuck_route_home", ROUTE_HOMES);
    }

    // ------------------------------------------------------------ rescue ---

    private static void tryRescue(ServerLevel level, SettlerEntity s, State st, long now, boolean longStuck) {
        if (st.lastRescueTick != Long.MIN_VALUE && now - st.lastRescueTick < RESCUE_COOLDOWN_TICKS) {
            st.status = "stuck, rescue cooling down";
            return;
        }
        Settlement town = s.settlement();
        if (town != null && BlessingEffects.raidActive(town)) {
            st.status = "stuck, rescue waits for the raid to end";
            return;
        }
        Pocket pocket = flood(level, s, s.blockPosition());
        if (!pocket.closed && !longStuck) {
            st.status = "stuck, not shut in (" + pocket.cells.size() + "+ cells)";
            return;
        }
        rescue(level, s, st, now, pocket, pocket.closed ? "pocketed" : "no_progress_long");
    }

    /**
     * Moves a shut-in settler to the nearest open cell outside its pocket
     * (preferring the Banner side), or next to the Banner. No block changes.
     */
    static boolean rescue(ServerLevel level, SettlerEntity s, State st, long now, Pocket pocket, String reason) {
        BlockPos from = s.blockPosition();
        BlockPos hearth = s.getHearthPos();
        List<RaiderEntity> raiders = level.getEntitiesOfClass(RaiderEntity.class,
            s.getBoundingBox().inflate(48.0D), RaiderEntity::isAlive);
        BlockPos dest = pocket.closed ? nearestOpenCell(level, s, from, pocket.cells, hearth, raiders) : null;
        String where = "near";
        if (dest == null && hearth != null) {
            dest = bannerCell(level, s, hearth, raiders);
            where = "banner";
        }
        if (dest == null) {
            st.lastAction = "rescue_no_cell";
            st.lastActionTick = now;
            Hearthstead.LOGGER.warn("settler_stuck_rescue_failed settler={} pos={} reason={} goal={}",
                s.getSettlerName(), from.toShortString(), reason, runningGoals(s));
            return false;
        }
        String goals = runningGoals(s);
        Vec3 fromVec = s.position();
        place(level, s, dest);
        // Verify a real route to the Banner from here; fall back to the Banner itself.
        if ("near".equals(where) && hearth != null && hearth.distSqr(dest) <= 90 * 90) {
            Path home = s.getNavigation().createPath(hearth, 2);
            boolean deferred = s.getNavigation() instanceof RoadNavigation road && road.deferredLastSearch();
            if (!deferred && (home == null || !home.canReach())) {
                BlockPos banner = bannerCell(level, s, hearth, raiders);
                if (banner != null) {
                    dest = banner;
                    where = "banner";
                    place(level, s, dest);
                }
            }
        }
        st.lastRescueTick = now;
        st.rescues++;
        st.clear("rescued");
        st.lastAction = "settler_stuck_rescue";
        st.lastActionTick = now;
        RESCUES.incrementAndGet();
        Hearthstead.LOGGER.info("settler_stuck_rescue settler={} from={} to={} ({}) reason={} goal={}",
            s.getSettlerName(), from.toShortString(), dest.toShortString(), where, reason, goals);
        level.sendParticles(ParticleTypes.POOF, fromVec.x, fromVec.y + 0.9D, fromVec.z, 12, 0.3D, 0.5D, 0.3D, 0.02D);
        level.sendParticles(ParticleTypes.POOF, s.getX(), s.getY() + 0.9D, s.getZ(), 12, 0.3D, 0.5D, 0.3D, 0.02D);
        level.playSound(null, s.getX(), s.getY(), s.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME,
            SoundSource.NEUTRAL, 0.6F, 1.2F);
        Settlement town = s.settlement();
        if (town != null && PathingConfig.rescueChat()) {
            TownChat.send(level, town, TownChat.Kind.RESEARCH,
                Component.translatable("hearthstead.chat.stuck_rescue", s.getSettlerName(),
                    town.name == null ? "" : town.name).withStyle(ChatFormatting.GRAY));
        }
        return true;
    }

    private static void place(ServerLevel level, SettlerEntity s, BlockPos cell) {
        s.getNavigation().stop();
        if (s.isPassenger()) {
            s.stopRiding();
        }
        double y = standY(level, s, cell);
        s.moveTo(cell.getX() + 0.5D, y, cell.getZ() + 0.5D, s.getYRot(), s.getXRot());
        s.setDeltaMovement(Vec3.ZERO);
        s.resetFallDistance();
    }

    @Nullable
    private static BlockPos nearestOpenCell(ServerLevel level, SettlerEntity s, BlockPos from, Set<BlockPos> pocket,
                                            @Nullable BlockPos hearth, List<RaiderEntity> raiders) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -RESCUE_SEARCH; dx <= RESCUE_SEARCH; dx++) {
                for (int dz = -RESCUE_SEARCH; dz <= RESCUE_SEARCH; dz++) {
                    BlockPos cell = from.offset(dx, dy, dz);
                    if (pocket.contains(cell) || !safeCell(level, s, cell, raiders)) {
                        continue;
                    }
                    candidates.add(cell);
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(score(a, from, hearth), score(b, from, hearth)));
        int tried = 0;
        for (BlockPos cell : candidates) {
            if (tried++ >= 10) {
                break;
            }
            if (!flood(level, s, cell).closed) {
                return cell.immutable();
            }
        }
        return null;
    }

    private static double score(BlockPos cell, BlockPos from, @Nullable BlockPos hearth) {
        double d = Math.sqrt(cell.distSqr(from));
        return hearth == null ? d : d + 0.15D * Math.sqrt(cell.distSqr(hearth));
    }

    @Nullable
    private static BlockPos bannerCell(ServerLevel level, SettlerEntity s, BlockPos hearth, List<RaiderEntity> raiders) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int r = 1; r <= 5 && best == null; r++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        BlockPos cell = hearth.offset(dx, dy, dz);
                        if (!safeCell(level, s, cell, raiders)) {
                            continue;
                        }
                        // Prefer the side away from any raider.
                        double score = Math.abs(dy);
                        for (RaiderEntity raider : raiders) {
                            score -= 0.1D * Math.sqrt(raider.blockPosition().distSqr(cell));
                        }
                        if (score < bestScore) {
                            bestScore = score;
                            best = cell.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    private static boolean safeCell(ServerLevel level, SettlerEntity s, BlockPos cell, List<RaiderEntity> raiders) {
        if (!level.isLoaded(cell) || !goodCell(level, s, cell)) {
            return false;
        }
        for (RaiderEntity raider : raiders) {
            if (raider.blockPosition().distSqr(cell) < 12 * 12) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------- suffocation ---

    static String lastInsideHit = "";

    /**
     * Vanilla's own suffocation test (the eyes inside a suffocating block).
     * Feet merely sunk into a floor block are not treated as "inside": many
     * fixtures and some real terrain leave a settler standing like that, and
     * it takes no damage there.
     */
    static boolean insideBlock(ServerLevel level, SettlerEntity s) {
        if (s.isPassenger() || s.isSleeping() || s.noPhysics || !s.isInWall()) {
            return false;
        }
        BlockPos eyes = BlockPos.containing(s.getEyePosition());
        lastInsideHit = level.getBlockState(eyes) + "@" + eyes.toShortString();
        return true;
    }

    /** Moves a settler caught inside a block to the nearest free cell: up first, then around. */
    static boolean free(ServerLevel level, SettlerEntity s, State st) {
        long now = level.getGameTime();
        BlockPos me = s.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dy = 1; dy <= 3 && best == null; dy++) {
            if (goodCell(level, s, me.above(dy))) {
                best = me.above(dy);
            }
        }
        if (best == null) {
            for (int dy = -1; dy <= 3; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        BlockPos cell = me.offset(dx, dy, dz);
                        if (!goodCell(level, s, cell)) {
                            continue;
                        }
                        double d = cell.distSqr(me);
                        if (d < bestDist) {
                            bestDist = d;
                            best = cell.immutable();
                        }
                    }
                }
            }
        }
        if (best == null) {
            if (now - st.lastFreeTick > 200L) {
                st.lastFreeTick = now;
                Hearthstead.LOGGER.warn("settler_stuck_inside_block_no_cell settler={} pos={}",
                    s.getSettlerName(), me.toShortString());
            }
            return false;
        }
        place(level, s, best);
        st.lastFreeTick = now;
        st.freed++;
        st.clear("freed");
        act(s, st, now, "settler_freed_from_block", FREED);
        Hearthstead.LOGGER.info("settler_freed_from_block detail settler={} from={} hit={}", s.getSettlerName(),
            me.toShortString(), lastInsideHit);
        return true;
    }

    // ------------------------------------------------------ cell helpers ---

    /**
     * A cell a settler (1.95 tall) can stand in: a floor under it no taller
     * than a block (a fence top is not a floor), nothing in the body's way,
     * no fire or lava. Carpets, snow layers and bottom slabs at the feet are
     * fine.
     */
    static boolean standable(ServerLevel level, SettlerEntity s, BlockPos cell) {
        if (!level.isLoaded(cell)) {
            return false;
        }
        double y = standY(level, s, cell);
        if (Double.isNaN(y)) {
            return false;
        }
        BlockState feet = level.getBlockState(cell);
        BlockState floor = level.getBlockState(cell.below());
        if (!level.getFluidState(cell).isEmpty() || !level.getFluidState(cell.above()).isEmpty()
            || feet.is(BlockTags.FIRE) || feet.is(BlockTags.CAMPFIRES) || floor.is(BlockTags.CAMPFIRES)
            || floor.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
            || floor.is(net.minecraft.world.level.block.Blocks.CACTUS)) {
            return false;
        }
        AABB box = s.getDimensions(Pose.STANDING).makeBoundingBox(cell.getX() + 0.5D, y, cell.getZ() + 0.5D)
            .deflate(1.0E-3D);
        return level.noCollision(s, box);
    }

    /** Standing height in this cell, or NaN when there is no floor to stand on. */
    static double standY(ServerLevel level, SettlerEntity s, BlockPos cell) {
        CollisionContext ctx = CollisionContext.of(s);
        BlockState feet = level.getBlockState(cell);
        VoxelShape feetShape = feet.getCollisionShape(level, cell, ctx);
        if (!feetShape.isEmpty()) {
            double top = feetShape.max(Direction.Axis.Y);
            return top <= 0.5625D ? cell.getY() + top : Double.NaN;
        }
        BlockPos below = cell.below();
        BlockState floor = level.getBlockState(below);
        VoxelShape floorShape = floor.getCollisionShape(level, below, ctx);
        if (floorShape.isEmpty()) {
            return IndoorBlocks.climbable(level, cell) || IndoorBlocks.climbable(level, below)
                ? cell.getY() : Double.NaN;
        }
        double top = floorShape.max(Direction.Axis.Y);
        if (top > 1.0D) {
            return Double.NaN; // fence / wall top
        }
        return top < 0.5D ? Double.NaN : below.getY() + top;
    }

    /** Standable with a whole floor (the Builder's rule: not a stair, chair or chest). */
    static boolean goodCell(ServerLevel level, SettlerEntity s, BlockPos cell) {
        if (!standable(level, s, cell)) {
            return false;
        }
        BlockPos below = cell.below();
        BlockState floor = level.getBlockState(below);
        VoxelShape atFeet = level.getBlockState(cell).getCollisionShape(level, cell);
        if (!atFeet.isEmpty()) {
            return atFeet.max(Direction.Axis.Y) <= 0.1875D; // a carpet or snow layer, not a half slab
        }
        if (floor.isFaceSturdy(level, below, Direction.UP)) {
            return true;
        }
        if (floor.getBlock() instanceof StairBlock) {
            return false;
        }
        VoxelShape shape = floor.getCollisionShape(level, below);
        if (shape.isEmpty()) {
            return false;
        }
        AABB box = shape.bounds();
        return box.maxY >= 0.8D && box.maxY <= 1.0D
            && box.minX <= 0.01D && box.maxX >= 0.99D && box.minZ <= 0.01D && box.maxZ >= 0.99D;
    }

    private static boolean passable(ServerLevel level, SettlerEntity s, BlockPos cell) {
        if (standable(level, s, cell)) {
            return true;
        }
        if (IndoorBlocks.climbable(level, cell)) {
            return true;
        }
        if (level.isLoaded(cell) && level.getFluidState(cell).is(net.minecraft.tags.FluidTags.WATER)
            && level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()
            && level.getBlockState(cell.above()).getCollisionShape(level, cell.above()).isEmpty()) {
            return true; // settlers swim
        }
        BlockPos door = IndoorBlocks.openablePassage(level, cell);
        if (door != null && !SealedGates.sealed(level, door)) {
            BlockState floor = level.getBlockState(cell.below());
            return !floor.getCollisionShape(level, cell.below()).isEmpty();
        }
        return false;
    }

    /** Result of the bounded "is this settler shut in" search. */
    record Pocket(boolean closed, Set<BlockPos> cells) {
    }

    /**
     * Bounded flood fill over cells a settler could walk, climb or open its
     * way through. Closed = the whole reachable region is small and stays
     * within {@link #POCKET_RADIUS} blocks: provably no way out.
     */
    static Pocket flood(ServerLevel level, SettlerEntity s, BlockPos start) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(start.immutable());
        queue.add(start.immutable());
        while (!queue.isEmpty()) {
            BlockPos at = queue.poll();
            int hx = Math.abs(at.getX() - start.getX());
            int hz = Math.abs(at.getZ() - start.getZ());
            if (hx >= POCKET_RADIUS || hz >= POCKET_RADIUS || seen.size() >= POCKET_MAX_CELLS) {
                return new Pocket(false, seen);
            }
            boolean climb = IndoorBlocks.climbable(level, at) || IndoorBlocks.climbable(level, at.below());
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos side = at.relative(dir);
                for (int dy = 1; dy >= -3; dy--) {
                    BlockPos next = side.above(dy);
                    if (dy < 0) {
                        // Dropping down: the column above the landing must be open.
                        boolean open = true;
                        for (int k = 0; k > dy; k--) {
                            if (!level.getBlockState(side.above(k)).getCollisionShape(level, side.above(k)).isEmpty()) {
                                open = false;
                                break;
                            }
                        }
                        if (!open) {
                            break;
                        }
                    }
                    if (passable(level, s, next)) {
                        if (seen.add(next.immutable())) {
                            queue.add(next.immutable());
                        }
                        break;
                    }
                }
            }
            if (climb) {
                for (BlockPos next : new BlockPos[]{at.above(), at.below()}) {
                    if (passable(level, s, next) && seen.add(next.immutable())) {
                        queue.add(next.immutable());
                    }
                }
            }
        }
        return new Pocket(true, seen);
    }

    // -------------------------------------------------------- exclusions ---

    /** Why this settler is legitimately still (not stuck), or null. */
    @Nullable
    static String exclusion(ServerLevel level, SettlerEntity s) {
        if (s.isPassenger()) {
            return "seated";
        }
        if (s.isSleeping()) {
            return "sleeping";
        }
        if (s.isLeashed()) {
            return "leashed";
        }
        if (ConversationService.isTalking(s)) {
            return "talking";
        }
        if (s.getTarget() != null) {
            return "fighting";
        }
        if (!s.guardDrillCue().isEmpty()) {
            return "drill";
        }
        SettlerActivity activity = s.getActivity();
        switch (activity) {
            case SLEEPING, RESTING, EATING, COMBAT, TRADING, SOCIALIZING, PLAYING_MUSIC, CELEBRATING,
                 CAST_FIREBOLT, CAST_FROST, CAST_WARD -> {
                return "busy:" + activity.name().toLowerCase(java.util.Locale.ROOT);
            }
            default -> {
            }
        }
        if (s.getProfession().martial()) {
            Settlement town = s.settlement();
            if (town != null && BlessingEffects.raidActive(town)) {
                return "raid";
            }
        }
        for (WrappedGoal goal : s.goalSelector.getAvailableGoals()) {
            if (goal.isRunning() && goal.getGoal() instanceof MinerEscapeGoal) {
                return "miner_escape";
            }
        }
        return null;
    }

    private static boolean arrived(Vec3 here, @Nullable BlockPos target) {
        if (target == null) {
            return false;
        }
        double dx = here.x - (target.getX() + 0.5D);
        double dz = here.z - (target.getZ() + 0.5D);
        double dy = here.y - target.getY();
        return dx * dx + dz * dz <= ARRIVED_H * ARRIVED_H && Math.abs(dy) <= 2.5D;
    }

    private static double distTo(Vec3 here, @Nullable BlockPos target) {
        return target == null ? Double.MAX_VALUE : here.distanceTo(Vec3.atBottomCenterOf(target));
    }

    private static String runningGoals(SettlerEntity s) {
        StringBuilder out = new StringBuilder();
        for (WrappedGoal goal : s.goalSelector.getAvailableGoals()) {
            if (goal.isRunning() && goal.getGoal().getFlags().contains(net.minecraft.world.entity.ai.goal.Goal.Flag.MOVE)) {
                if (out.length() > 0) {
                    out.append('+');
                }
                out.append(goal.getGoal().getClass().getSimpleName());
            }
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    // ------------------------------------------------------- inspection ---

    /** One line for /hearthstead why. */
    public static String describe(SettlerEntity s) {
        State st = STATES.get(s);
        String own = st == null ? "idle" : st.status + ", stage " + st.stage + ", last " + st.lastAction
            + (st.lastActionTick == Long.MIN_VALUE ? "" : "@" + st.lastActionTick)
            + ", rescues " + st.rescues + ", freed " + st.freed;
        return own + " | all: repath " + REPATHS.get() + ", unwedge " + UNWEDGES.get() 
            + ", home " + ROUTE_HOMES.get() + ", rescue " + RESCUES.get() + ", freed " + FREED.get();
    }

    public static int stage(SettlerEntity s) {
        State st = STATES.get(s);
        return st == null ? 0 : st.stage;
    }

    public static int rescues(SettlerEntity s) {
        State st = STATES.get(s);
        return st == null ? 0 : st.rescues;
    }

    public static int freedCount(SettlerEntity s) {
        State st = STATES.get(s);
        return st == null ? 0 : st.freed;
    }

    public static String lastAction(SettlerEntity s) {
        State st = STATES.get(s);
        return st == null ? "none" : st.lastAction;
    }

    public static long stuckTicks(SettlerEntity s) {
        State st = STATES.get(s);
        return st == null ? 0L : st.stuckTicks;
    }

    /** True when the bounded search finds no way out from where this settler stands. */
    public static boolean shutIn(SettlerEntity s) {
        return s.level() instanceof ServerLevel level && flood(level, s, s.blockPosition()).closed;
    }

    /**
     * GameTest seam, per settler so parallel tests never share it:
     * {@code enabled} null = config; {@code rescueAfterTicks} null = config.
     */
    public static void overrideForTests(SettlerEntity s, @Nullable Boolean enabled, @Nullable Integer rescueAfterTicks) {
        State st = state(s);
        st.testEnabled = enabled;
        st.testRescueTicks = rescueAfterTicks;
    }

    /** The per-settler rescue cooldown (5 minutes). */
    public static long rescueCooldownTicks() {
        return RESCUE_COOLDOWN_TICKS;
    }
}
