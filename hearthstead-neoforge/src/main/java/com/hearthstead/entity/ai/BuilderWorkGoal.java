package com.hearthstead.entity.ai;

import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.path.StandCells;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.builder.Blueprint;
import com.hearthstead.settlement.builder.BuildExecutor;
import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildJobs;
import com.hearthstead.settlement.builder.BuildPhase;
import com.hearthstead.settlement.builder.BuildSiteSavedData;
import com.hearthstead.settlement.builder.BuildStatus;
import com.hearthstead.settlement.builder.BuilderCraft;
import com.hearthstead.settlement.builder.BuilderMaterials;
import com.hearthstead.settlement.builder.BuilderStock;
import com.hearthstead.settlement.builder.BuilderSupply;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Builder's day (plan/BUILDER.md section 5): take the next site in the
 * queue, load exactly what the next steps need from the hut, carry it to the
 * site, and set block after block from a real stand cell -- clear, fill,
 * foundation, walls, roof, interior, redstone, finish.
 *
 * <p>Every world change goes through {@link BuildExecutor}, which owns the
 * exact-once guarantees. This goal only decides where to stand, what to
 * carry and what to say. Every wait sets a {@link BuildStatus} with numbers
 * or a position; nothing idles silently.
 *
 * <p>Bounded work: at most one step executes per {@link #placeTicks} window,
 * stand-cell searches run only when the target step changes or the route
 * failed (rate-limited), and the stuck guard turns a step that cannot be
 * reached into a skipped step after {@link #MAX_TRIES} tries -- never a loop.
 */
public class BuilderWorkGoal extends Goal {

    /** How often an idle Builder looks for a site. */
    private static final int LOOK_INTERVAL = 20;
    /** Ticks for the first block of a run (walked up to it: ~2 blocks/s). */
    public static final int PLACE_TICKS = 10;
    /**
     * Ticks per block while he keeps setting blocks from the same spot with
     * the materials in hand (owner, film take 2: place in fluent runs).
     */
    public static final int RUN_TICKS = 6;
    /** Ticks per cleared / drained cell. */
    public static final int CLEAR_TICKS = 8;
    /** A run continues while the next block follows within this many ticks. */
    private static final int RUN_GAP = 3;
    /** Steps of the current layer looked at when choosing the nearest next block. */
    private static final int FRONT_SCAN = 512;
    /**
     * Out of reach and not moving for this long (with somewhere to stand)
     * is a failed try at once -- not 15 seconds of standing still.
     */
    public static final int STALL_TICKS = 60;
    /** Reach from the eye to the target block centre. */
    public static final double BUILD_REACH = 4.5D;
    /**
     * Stand cells are chosen well inside the reach (W3a: a cell right at the
     * 4.5 edge left him a hair out of reach after navigation stopped short of
     * the cell centre, until the stuck guard skipped the step).
     */
    public static final double PLAN_REACH = BUILD_REACH - 0.9D;
    private static final double MOVE_SPEED = 0.8D;
    /** No progress on one target for this long = one failed try. */
    public static final int STUCK_TICKS = 300;
    public static final int MAX_TRIES = 3;
    /** Material requests are refreshed at most this often. */
    private static final int REQUEST_INTERVAL = 100;
    private static final int REPLAN_INTERVAL = 20;

    private final SettlerEntity settler;
    @Nullable
    private UUID jobId;
    private Stage stage = Stage.IDLE;
    private int lookCooldown;
    private int step = -1;
    private long stepSince;
    private long lastReplan;
    private long nextRequest;
    private int workTicks;
    private int placedSinceSave;
    @Nullable
    private BlockPos fetchFrom;
    private boolean done;
    /** The ladder column being hung for the current step, if it needs one. */
    @Nullable
    private com.hearthstead.settlement.builder.BuilderScaffold.Column column;
    private int columnStep = -1;
    /** Ladders the column still needs in the sack (joins the load). */
    private int scaffoldNeed;
    /** A step waits this long without any stand cell before a ladder goes up. */
    public static final int SCAFFOLD_AFTER = 80;
    /** What this tick was spent on, for {@link BuilderUtilisation}. */
    @Nullable
    private BuilderUtilisation.Kind kind;
    private long kindTick = -1L;
    private long lastPlacedTick = -100L;
    @Nullable
    private Vec3 lastTickPos;
    private int stillTicks;
    /**
     * A workshop order that has not delivered after this long is not waited
     * on any more: the Builder makes it himself (owner: early towns have no
     * staffed workshop, and an order nobody fills must not stall the site).
     */
    private static final long SELF_CRAFT_AFTER = 400L;
    /** A delivery this late (ticks) and he fetches it himself on the Hybrid setting. */
    private static final long COURIER_LATE = 800L;
    @Nullable
    /** When he first waited on each item (a shape list alternates stairs / fence / door). */
    private final Map<Item, Long> waitingSince = new java.util.HashMap<>();

    private long waitedFor(Item item, long now) {
        if (waitingSince.size() > 64) {
            waitingSince.clear();
        }
        return now - waitingSince.computeIfAbsent(item, k -> now);
    }

    private static boolean otherWorkThan(BuildJob job, int step, long now) {
        for (int i = job.cursor(); i < job.size(); i++) {
            if (i != step && pending(job, i, now)) {
                return true;
            }
        }
        return false;
    }

    /** An input to fetch for something he makes himself; null when none. */
    @Nullable
    private BuilderMaterials.ItemCount fetchExtra;
    private long craftPauseUntil;
    /** A route that has not moved the Builder for this long is stepped off (W10). */
    public static final int WEDGE_TICKS = 100;
    /** Standing still this long while wanting to move is a wedge even without a route. */
    private static final int LONG_STILL_TICKS = 400;
    private long wantMoveTick;
    private static final int STEP_OFF_TICKS = 20;
    @Nullable
    private Vec3 wedgePos;
    private long wedgeSince;
    @Nullable
    private BlockPos stepOffTo;
    private long stepOffUntil;
    private int wedgeRounds;

    private enum Stage {
        IDLE, LOAD, BUILD, RETURN
    }

    public BuilderWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        BuilderUtilisation.register(settler, this);
    }

    /** One line of state for GameTest failure messages (stage, step, sack, target, route). */
    String debugState() {
        StringBuilder out = new StringBuilder("stage=" + stage + " step=" + step);
        if (settler.level() instanceof ServerLevel level && settler.settlement() != null) {
            BuildJob job = job(level, settler.settlement());
            if (job != null && step >= 0 && step < job.size()) {
                out.append(" at=").append(job.pos(step).toShortString()).append(' ')
                    .append(Blueprint.idOf(job.state(step))).append(" costs=")
                    .append(BuilderMaterials.costsOfStep(job, step)).append(" why=").append(job.skipReport());
                out.append(" window=").append(windowNeeds(job));
            }
        }
        out.append(" bag=[");
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack s = settler.bag.getItem(i);
            if (!s.isEmpty()) {
                out.append(s.getCount()).append('x').append(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(s.getItem()).getPath()).append(' ');
            }
        }
        out.append("] fetchFrom=").append(fetchFrom == null ? "-" : fetchFrom.toShortString())
            .append(" me=").append(settler.blockPosition().toShortString())
            .append(" nav=").append(settler.getNavigation().getTargetPos() == null ? "-"
                : settler.getNavigation().getTargetPos().toShortString())
            .append(" navDone=").append(settler.getNavigation().isDone())
            .append(" still=").append(stillTicks);
        return out.toString();
    }

    /**
     * Close enough to use a chest or barrel. The strict stand-cell rule
     * (face-on, visible) first; failing that, a Builder who has stopped
     * within his building reach of it uses it anyway (Sunday blocker: in a
     * packed barrel block he arrived on a cell the strict rule never
     * accepted and stood there ~17,000 ticks, FETCHING forever).
     */
    private boolean atContainer(ServerLevel level, BlockPos container) {
        if (StandCells.inReach(level, settler, container)) {
            return true;
        }
        return stillTicks >= 10
            && settler.getEyePosition().distanceToSqr(Vec3.atCenterOf(container)) <= BUILD_REACH * BUILD_REACH;
    }

    /** What the goal did at {@code now}, or null when it did not run then. */
    @Nullable
    BuilderUtilisation.Kind kindAt(long now) {
        return kindTick >= now - 1L ? kind : null;
    }

    private void mark(BuilderUtilisation.Kind k) {
        kind = k;
        kindTick = settler.level().getGameTime();
    }

    /** How far a roof course may be set from, once reach and scaffold failed. */
    public static final double ROOF_REACH = 8.0D;
    /** A block he has tried to reach this long may be set by the far reach (W32: 118 eave skips). */
    private static final long FAR_REACH_AFTER = 60L;

    /**
     * Fallback B for roof courses: after one failed try (no stand cell and
     * no ladder column reached it), a Builder standing on the ground inside
     * the job's footprint or within two blocks of its edge may set it from
     * up to {@link #ROOF_REACH}, with a clear line of sight to the block.
     */
    private boolean roofReach(ServerLevel level, BuildJob job, BlockPos target) {
        // Every phase (lead: no building left unfinished): after one failed
        // try -- no stand cell, no ladder column -- the block is set from the
        // ground or a ladder near the footprint, with a clear line of sight.
        if ((job.tries(step) < 1 && level.getGameTime() - stepSince < FAR_REACH_AFTER)
            || !(settler.onGround() || settler.onClimbable())) {
            return false;
        }
        BlockPos me = settler.blockPosition();
        var box = job.bounds;
        if (me.getX() < box.minX() - 3 || me.getX() > box.maxX() + 3
            || me.getZ() < box.minZ() - 3 || me.getZ() > box.maxZ() + 3) {
            return false;
        }
        Vec3 eye = settler.getEyePosition();
        Vec3 aim = Vec3.atCenterOf(target);
        if (eye.distanceToSqr(aim) > ROOF_REACH * ROOF_REACH) {
            return false;
        }
        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, aim,
            net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
            settler));
        // An eave stair sits on the wall top: the ray from below brushes the
        // blocks it rests on. A hit right beside the target still sees it.
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().equals(target)
            || hit.getBlockPos().distSqr(target) <= 2;
    }

    // ------------------------------------------------------ last resort ---

    /**
     * Owner 26 Sep 23:10: "a last-resort fix he gets after he has tried
     * everything: he can reach about 10 blocks away, just to finish the
     * build". Only for a block that walking, the far reach and a ladder
     * column have all failed on (two failed tries, or the end-of-job
     * second pass).
     */
    private boolean lastResortStep(BuildJob job) {
        return HearthsteadServerConfig.lastResortReach() > 0
            && (job.secondPass || job.tries(step) >= MAX_TRIES - 1);
    }

    private static boolean onSite(BuildJob job, BlockPos feet) {
        var box = job.bounds;
        return feet.getX() >= box.minX() - 3 && feet.getX() <= box.maxX() + 3
            && feet.getZ() >= box.minZ() - 3 && feet.getZ() <= box.maxZ() + 3;
    }

    /** He stands on the site and the block is within the last-resort stretch of his eyes. */
    private boolean lastResort(ServerLevel level, BuildJob job, BlockPos target) {
        if (!lastResortStep(job) || !(settler.onGround() || settler.onClimbable())
            || !onSite(job, settler.blockPosition())) {
            return false;
        }
        double r = HearthsteadServerConfig.lastResortReach();
        Vec3 eye = settler.getEyePosition();
        Vec3 aim = Vec3.atCenterOf(target);
        return eye.distanceToSqr(aim) <= r * r && !throughForeign(level, job, eye, aim, target);
    }

    /**
     * No line of sight is needed inside the blueprint's own box, but a solid
     * block outside it on the way (a player's building next door, a hill)
     * stops the stretch: he never places through someone else's house.
     */
    private static boolean throughForeign(ServerLevel level, BuildJob job, Vec3 from, Vec3 to, BlockPos target) {
        int n = Math.max(1, (int) Math.ceil(from.distanceTo(to) / 0.25D));
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 1; i < n; i++) {
            Vec3 v = from.lerp(to, i / (double) n);
            p.set(net.minecraft.util.Mth.floor(v.x), net.minecraft.util.Mth.floor(v.y),
                net.minecraft.util.Mth.floor(v.z));
            if (p.equals(target) || job.bounds.isInside(p)) {
                continue;
            }
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private BuildJob lastResortJob;
    private int lastResortFor = -1;
    private Set<BlockPos> lastResortCells = Set.of();

    /** Standable cells on the site from which the last-resort stretch reaches the target. */
    private Set<BlockPos> lastResortCells(ServerLevel level, BuildJob job, BlockPos target) {
        if (lastResortJob == job && lastResortFor == step) {
            return lastResortCells;
        }
        lastResortJob = job;
        lastResortFor = step;
        int r = HearthsteadServerConfig.lastResortReach();
        double eyeHeight = settler.getEyeHeight();
        Vec3 aim = Vec3.atCenterOf(target);
        List<BlockPos> found = new ArrayList<>();
        for (int dy = -r - 1; dy <= 1; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (!onSite(job, feet) || feet.equals(target) || feet.above().equals(target)) {
                        continue;
                    }
                    Vec3 eye = new Vec3(feet.getX() + 0.5D, feet.getY() + eyeHeight, feet.getZ() + 0.5D);
                    if (eye.distanceToSqr(aim) > (double) r * r || !standable(level, feet)) {
                        continue;
                    }
                    found.add(feet);
                }
            }
        }
        BlockPos me = settler.blockPosition();
        found.sort((a, b) -> Double.compare(a.distSqr(me), b.distSqr(me)));
        Set<BlockPos> out = new LinkedHashSet<>();
        for (int i = 0; i < found.size() && out.size() < 64; i++) {
            BlockPos feet = found.get(i);
            Vec3 eye = new Vec3(feet.getX() + 0.5D, feet.getY() + eyeHeight, feet.getZ() + 0.5D);
            if (!throughForeign(level, job, eye, aim, target)) {
                out.add(feet);
            }
        }
        lastResortCells = out;
        return out;
    }

    /** Walking, or stood still for a few ticks at most. */
    private boolean moving() {
        return stillTicks < 10;
    }

    // -------------------------------------------------------- lifecycle ---

    @Override
    public boolean canUse() {
        if (!HearthsteadServerConfig.builderEnabled()
            || settler.getProfession() != Profession.BUILDER
            || !settler.isBound() || settler.getTarget() != null) {
            return false;
        }
        if (lookCooldown > 0) {
            lookCooldown--;
            return false;
        }
        lookCooldown = LOOK_INTERVAL;
        if (!(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null || settlement.pendingRaid != null) {
            return false;
        }
        Building hut = BuildJobs.hutOf(settlement, settler);
        if (hut == null) {
            return false;
        }
        BuildJob job = BuildJobs.claimNext(level, settlement, settler);
        if (job == null) {
            // Nothing to build: bring anything still in the bag home.
            if (!settler.bag.isEmpty()) {
                jobId = null;
                stage = Stage.RETURN;
                return true;
            }
            return false;
        }
        if (!mayWorkNow(level, settlement, job)) {
            BuildJobs.release(job, settler);
            return false;
        }
        jobId = job.id;
        stage = Stage.BUILD;
        return true;
    }

    /** Working hours -- or a barricade rush before a warned raid. */
    private boolean mayWorkNow(ServerLevel level, Settlement settlement, BuildJob job) {
        if (settler.dayPhase().work()) {
            return true;
        }
        return job.rush && job.kind == BuildJob.Kind.BARRICADE && settlement.pendingRaid == null
            && BuildJobs.raidWarned(level, settlement);
    }

    @Override
    public boolean canContinueToUse() {
        if (done || !HearthsteadServerConfig.builderEnabled() || settler.getTarget() != null
            || !(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null || settlement.pendingRaid != null
            || BuildJobs.hutOf(settlement, settler) == null) {
            return false;
        }
        if (stage == Stage.RETURN) {
            return !settler.bag.isEmpty();
        }
        BuildJob job = job(level, settlement);
        // Tech tree (Barricades): on a raid warning, drop other work for a
        // waiting barricade; canUse then claims it (rush sorts first).
        if (job != null && job.kind != BuildJob.Kind.BARRICADE && settler.tickCount % 40 == 0
            && com.hearthstead.settlement.techtree.effects.WatchEffects.barricadeCallsBuilders(level, settlement)) {
            return false;
        }
        return job != null && job.workable() && mayWorkNow(level, settlement, job);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        done = false;
        step = -1;
        workTicks = 0;
        placedSinceSave = 0;
        fetchFrom = null;
        settler.setActivity(SettlerActivity.TRAVELING);
    }

    @Override
    public void stop() {
        if (settler.level() instanceof ServerLevel level) {
            Settlement settlement = settler.settlement();
            BuildJob job = settlement == null ? null : job(level, settlement);
            if (job != null) {
                BuildJobs.release(job, settler);
                BuildSiteSavedData.get(level).changed();
            }
        }
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.IDLE);
        jobId = null;
        stage = Stage.IDLE;
        step = -1;
        fetchFrom = null;
    }

    @Nullable
    private BuildJob job(ServerLevel level, Settlement settlement) {
        UUID id = jobId;
        return id == null ? null : BuildSiteSavedData.get(level).job(settlement.id, id);
    }

    // ------------------------------------------------------------- tick ---

    @Override
    public void tick() {
        if (!(settler.level() instanceof ServerLevel level)) {
            return;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null) {
            done = true;
            return;
        }
        Building hut = BuildJobs.hutOf(settlement, settler);
        if (hut == null) {
            done = true;
            return;
        }
        Vec3 here = settler.position();
        stillTicks = lastTickPos != null && here.distanceToSqr(lastTickPos) < 0.0004D ? stillTicks + 1 : 0;
        lastTickPos = here;
        mark(BuilderUtilisation.Kind.STALL);
        if (unwedge(level, level.getGameTime())) {
            return;
        }
        if (stage == Stage.RETURN) {
            tickReturn(level, hut);
            return;
        }
        BuildJob job = job(level, settlement);
        if (job == null || !job.workable()) {
            done = true;
            return;
        }
        BuildJobs.renew(level, job, settler);
        long now = level.getGameTime();
        int next = nextStep(job, now);
        if (next < 0) {
            finishOrWait(level, settlement, job, hut);
            return;
        }
        if (next != step) {
            step = next;
            stepSince = now;
            lastReplan = 0L;
            workTicks = 0;
            if (columnStep != step) {
                column = null;
                scaffoldNeed = 0;
            }
        }
        List<BuilderMaterials.ItemCount> costs = new ArrayList<>(BuilderMaterials.costsOfStep(job, step));
        if (scaffoldNeed > 0 && column != null && columnStep == step) {
            // One rung's ladder in the sack keeps him hanging (Codex T3b:
            // never demand the whole column at once).
            costs.add(new BuilderMaterials.ItemCount(net.minecraft.world.item.Items.LADDER, 1));
        }
        if (stage == Stage.LOAD || !bagCovers(costs)) {
            stage = Stage.LOAD;
            tickLoad(level, settlement, job, hut, costs);
            return;
        }
        // Pre-fetch: while he builds, the couriers already bring the next
        // load's worth to the hut, so the trip back finds it waiting.
        if (now >= nextRequest) {
            nextRequest = now + REQUEST_INTERVAL * 2L;
            if (BuildSiteSavedData.get(level).settings(settlement.id).pickup()
                != BuildSiteSavedData.Pickup.FETCH_MYSELF) {
                BuilderSupply.request(level, settlement, hut, job, settler.bag);
            }
        }
        tickBuild(level, settlement, job, hut);
    }

    private static boolean pending(BuildJob job, int i, long now) {
        return !(job.isDone(i) || job.isSkipped(i) || job.isBlocked(i) || job.deferred(i, now));
    }

    /**
     * The next step to work. Order between phases and layers is the plan's
     * (solid before fragile, bottom up); WITHIN the current layer of the
     * current phase he takes the nearest block -- one he can reach, with the
     * material in his sack, first -- so he sets blocks in fluent runs from
     * one spot instead of zig-zagging across the house in row order.
     */
    private int nextStep(BuildJob job, long now) {
        int first = -1;
        for (int i = job.cursor(); i < job.size(); i++) {
            if (pending(job, i, now)) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return -1;
        }
        BuildPhase phase = job.phase(first);
        int y = job.pos(first).getY();
        if (step >= first && step < job.size() && pending(job, step, now)
            && job.phase(step) == phase && job.pos(step).getY() == y) {
            return step; // keep the chosen block until it is done or given up
        }
        if (job.hasFlag(first, BuildJob.F_FIT_PLAN)) {
            return first;
        }
        Vec3 eye = settler.getEyePosition();
        int best = first;
        double bestScore = Double.MAX_VALUE;
        int scanned = 0;
        for (int i = first; i < job.size() && scanned < FRONT_SCAN; i++) {
            if (job.phase(i) != phase || job.pos(i).getY() != y) {
                break;
            }
            if (!pending(job, i, now) || job.hasFlag(i, BuildJob.F_FIT_PLAN)) {
                continue;
            }
            scanned++;
            double dist = eye.distanceToSqr(Vec3.atCenterOf(job.pos(i)));
            double score = dist;
            if (dist > BUILD_REACH * BUILD_REACH) {
                score += 1000.0D;
            }
            if (!bagCovers(BuilderMaterials.costsOfStep(job, i))) {
                score += 500.0D;
            }
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private boolean bagCovers(List<BuilderMaterials.ItemCount> costs) {
        for (BuilderMaterials.ItemCount cost : costs) {
            if (BuilderStock.bagCount(settler.bag, cost.item()) < cost.count()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------- build ---

    private void tickBuild(ServerLevel level, Settlement settlement, BuildJob job, Building hut) {
        long now = level.getGameTime();
        BlockPos target = job.pos(step);
        settler.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        boolean stretch = false;
        boolean far = !inReach(target) && !occupies(target) && roofReach(level, job, target);
        if (!far && !inReach(target) && !occupies(target) && lastResort(level, job, target)) {
            far = true;
            stretch = true;
        }
        if (far && column != null && columnStep == step && job.scaffold.isEmpty()) {
            column = null; // no rung hung yet: the far reach is quicker and needs no ladders
            scaffoldNeed = 0;
        }
        if (!far && column != null && columnStep == step) {
            mark(BuilderUtilisation.Kind.SCAFFOLD);
            tickScaffold(level, job, now);
            return;
        }
        BlockPos feet = settler.blockPosition();
        if (inReach(target) && occupies(target) && (feet.getX() != target.getX() || feet.getZ() != target.getZ())
            && stillTicks < 40) {
            // His body only overhangs the cell (a doorway edge, W21 cottage
            // door): stepping to the middle of his own cell clears it -- a
            // route "to a stand cell" he already stands in never would.
            BlockPos me = settler.blockPosition();
            settler.getNavigation().stop();
            settler.getMoveControl().setWantedPosition(me.getX() + 0.5D, me.getY(), me.getZ() + 0.5D, MOVE_SPEED);
            mark(BuilderUtilisation.Kind.MOVE_SITE);
            return;
        }
        if (far) {
            // Fallback B (lead, Sunday): a roof course that neither a stand
            // cell nor a ladder column reached is set from the ground at the
            // footprint's edge -- up to ROOF_REACH, never through a wall.
            settler.getNavigation().stop();
        } else if (!inReach(target) || occupies(target)) {
            mark(moving() ? BuilderUtilisation.Kind.MOVE_SITE : BuilderUtilisation.Kind.STALL);
            walkTo(level, job, target, now);
            if (stillTicks >= STALL_TICKS && now - stepSince >= STALL_TICKS
                && !standCells(level, job, target).isEmpty()) {
                // Standing still with somewhere to stand: the route failed.
                // One try is spent now and he turns to the next block; the
                // step rests and comes back (never 15 s of waiting).
                var path = settler.getNavigation().getPath();
                // Only a route that truly cannot reach counts here; arriving
                // just short of the reach is left to the replans and the
                // long STUCK_TICKS guard (W20: a cottage bed / door was given
                // up after three quick 'arrived but short' stalls).
                if ((path == null || !path.canReach()) && columnStep != step) {
                    // No route to any stand cell: a ladder column first.
                    columnStep = step;
                    column = com.hearthstead.settlement.builder.BuilderScaffold.plan(level, job, target);
                    if (column != null) {
                        scaffoldNeed = column.missing(level);
                        stepSince = now;
                        stillTicks = 0;
                        return;
                    }
                }
                if (path == null || !path.canReach()) {
                    BlockPos me = settler.blockPosition();
                    failStep(level, settlement, job, "stalled at " + me.getX() + "," + me.getY() + "," + me.getZ()
                        + " path=" + (path == null ? "none" : path.canReach() ? "ok" : "partial"));
                    stillTicks = 0;
                    return;
                }
            }
            var route = settler.getNavigation().getPath();
            boolean noRoute = route == null || !route.canReach();
            if (now - stepSince > SCAFFOLD_AFTER && columnStep != step
                && (standCells(level, job, target).isEmpty() || (noRoute && stillTicks >= 20))) {
                // Nothing to stand on within reach: hang a ladder on the
                // wall below and climb it (the pathing lane climbs ladders).
                columnStep = step;
                column = com.hearthstead.settlement.builder.BuilderScaffold.plan(level, job, target);
                if (column != null) {
                    scaffoldNeed = column.missing(level);
                    stepSince = now;
                    return;
                }
            }
            if (now - stepSince > STUCK_TICKS) {
                // Diagnostics for sites and GameTest logs: where he stood, how
                // many stand cells the target had, and whether a path existed.
                BlockPos me = settler.blockPosition();
                var path = settler.getNavigation().getPath();
                failStep(level, settlement, job, "unreachable from " + me.getX() + "," + me.getY() + "," + me.getZ()
                    + " cells=" + standCells(level, job, target).size()
                    + " path=" + (path == null ? "none" : path.canReach() ? "ok" : "partial"));
            }
            settler.setActivity(settler.bag.isEmpty() ? SettlerActivity.TRAVELING
                : SettlerActivity.CARRY_MATERIALS);
            return;
        }
        settler.getNavigation().stop();
        mark(BuilderUtilisation.Kind.PLACE);
        BuildPhase phase = job.phase(step);
        SettlerActivity motion = motionFor(job, step);
        if (settler.getActivity() != motion) {
            settler.setActivity(motion);
        }
        workTicks++;
        beat(level, job, target, motion);
        boolean inRun = now - lastPlacedTick <= RUN_GAP;
        int pace = phase.removes() || job.hasFlag(step, BuildJob.F_CLEAR) || job.hasFlag(step, BuildJob.F_DRAIN)
            ? (inRun ? RUN_TICKS : CLEAR_TICKS) : placeTicks(job, inRun);
        if (workTicks < pace) {
            return;
        }
        workTicks = 0;
        List<Container> hutStock = BuilderStock.hutContainers(level, hut);
        BuildExecutor.Outcome outcome = BuildExecutor.execute(level, job, step, settler.bag, hutStock,
            settler.blockPosition());
        switch (outcome) {
            case DONE -> {
                if (stretch) {
                    settler.recordRouteFailure("builder_last_resort");
                }
                stepSince = now;
                lastPlacedTick = now;
                placedSinceSave++;
                settler.train(Attribute.DEXTERITY, 0.25F);
                if (placedSinceSave % 4 == 0) {
                    settler.spendEffort(1);
                }
                updateStatus(job, step);
                BuildSiteSavedData data = BuildSiteSavedData.get(level);
                if (placedSinceSave % 8 == 0) {
                    data.changed();
                } else {
                    data.setDirty();
                }
            }
            case NEED_MATERIAL -> stage = Stage.LOAD;
            case BLOCKED -> {
                BlockPos at = job.pos(step);
                job.setStatus(BuildStatus.BLOCKED_PLAYER_BLOCK, at.getX(), at.getY(), at.getZ());
                BuildJobs.noticeBlocked(level, settlement, job, settler,
                    Component.translatable("hearthstead.builder.notice.player_block",
                        at.getX(), at.getY(), at.getZ()));
                BuildSiteSavedData.get(level).changed();
            }
            case DEFER -> {
                // A block waiting on another (a lantern on its beam, a torch on
                // its wall) rests without spending a try while other work
                // remains; only when it is all that is left do tries count.
                if (otherWorkThan(job, step, now)) {
                    job.noteWhy(step, "defer");
                    job.deferStep(step, now + 100L);
                    step = -1;
                } else {
                    failStep(level, settlement, job, "defer");
                }
            }
            case IMPOSSIBLE -> {
                job.noteWhy(step, "impossible");
                job.skipStep(step);
                BuildSiteSavedData.get(level).changed();
            }
        }
    }

    /** Faster in a run, with a rush (warned raid) and with practice. */
    private int placeTicks(BuildJob job, boolean inRun) {
        int ticks = inRun ? RUN_TICKS : PLACE_TICKS;
        if (job.rush) {
            ticks -= inRun ? 1 : 3;
        }
        // Job fit (Dexterity + Wits): 0..15% quicker placement (plan/ATTRIBUTES.md).
        ticks = com.hearthstead.entity.AttributeRuntime.shortenWork(settler, ticks);
        return Math.max(inRun ? 4 : 6, ticks);
    }

    /** One more failed try; after {@link #MAX_TRIES}, skip and flag. */
    private void failStep(ServerLevel level, Settlement settlement, BuildJob job, String why) {
        long now = level.getGameTime();
        int tries = job.noteFailure(step);
        job.noteWhy(step, why);
        if (tries >= MAX_TRIES) {
            job.skipStep(step);
            job.setStatus(BuildStatus.SKIPPED, job.skippedCount(), job.skippedWhere(3));
            settler.recordRouteFailure("builder_" + why);
        } else {
            job.deferStep(step, now + 60L * tries);
        }
        stepSince = now;
        step = -1;
        settler.getNavigation().stop();
        BuildSiteSavedData.get(level).changed();
    }

    private boolean inReach(BlockPos target) {
        Vec3 eye = settler.getEyePosition();
        return eye.distanceToSqr(Vec3.atCenterOf(target)) <= BUILD_REACH * BUILD_REACH;
    }

    /** Never set a block inside the Builder himself. */
    private boolean occupies(BlockPos target) {
        return settler.getBoundingBox().intersects(new AABB(target));
    }

    private void walkTo(ServerLevel level, BuildJob job, BlockPos target, long now) {
        wantMoveTick = now;
        if (now - lastReplan < REPLAN_INTERVAL && !settler.getNavigation().isDone()) {
            return;
        }
        lastReplan = now;
        Set<BlockPos> cells = standCells(level, job, target);
        if (lastResortStep(job)) {
            // Everything else failed for this block: any site cell within the
            // last-resort stretch will do; the pathfinder picks one it reaches.
            cells = new LinkedHashSet<>(cells);
            cells.addAll(lastResortCells(level, job, target));
        }
        if (!cells.isEmpty() && StandCells.moveToAny(level, settler, cells, MOVE_SPEED)) {
            return;
        }
        settler.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, MOVE_SPEED);
    }

    /**
     * Standable cells from which the eye reaches the target: a 7x7 column
     * box from 4 below to 1 above -- built floors of the house itself count,
     * which is how upper storeys and roofs get reached. Cells where the job
     * still has to put a block, or the target itself, are excluded.
     */
    private Set<BlockPos> standCells(ServerLevel level, BuildJob job, BlockPos target) {
        List<BlockPos> found = new ArrayList<>();
        for (int dy = -4; dy <= 1; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (feet.equals(target) || feet.above().equals(target) || !standable(level, feet)) {
                        continue;
                    }
                    Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + settler.getEyeHeight(), feet.getZ() + 0.5);
                    if (eye.distanceToSqr(Vec3.atCenterOf(target)) <= PLAN_REACH * PLAN_REACH) {
                        found.add(feet.immutable());
                    }
                }
            }
        }
        // W4a: hand the pathfinder every candidate (it picks the cheapest one
        // it can actually reach). A short list sorted by closeness to the
        // work kept only cells on top of an unfinished roof -- unreachable --
        // and dropped the upper-floor cells he could walk to.
        BlockPos me = settler.blockPosition();
        found.sort((a, b) -> Double.compare(a.distSqr(me), b.distSqr(me)));
        Set<BlockPos> out = new LinkedHashSet<>();
        for (int i = 0; i < found.size() && out.size() < 64; i++) {
            out.add(found.get(i));
        }
        return out;
    }

    private static boolean standable(ServerLevel level, BlockPos feet) {
        if (!level.isLoaded(feet) || !level.isLoaded(feet.below())) {
            return false;
        }
        BlockState below = level.getBlockState(feet.below());
        return !level.getBlockState(feet).blocksMotion()
            && !level.getBlockState(feet.above()).blocksMotion()
            && level.getFluidState(feet).isEmpty()
            && (fullFloor(level, feet.below(), below) || below.is(BlockTags.CLIMBABLE));
    }

    /**
     * Film W10: a work spot needs a whole floor under it. A chair (a stair),
     * a half slab, a fence or a chest is not one -- a Builder sent to stand
     * on a chair wedged there and never found a route off it. Paths and
     * farmland (15/16 high, full width) still count.
     */
    private static boolean fullFloor(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isFaceSturdy(level, pos, Direction.UP)) {
            return true;
        }
        if (state.getBlock() instanceof StairBlock) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        AABB box = shape.bounds();
        return box.maxY >= 0.8D && box.maxY <= 1.0D
            && box.minX <= 0.01D && box.maxX >= 0.99D && box.minZ <= 0.01D && box.maxZ >= 0.99D;
    }

    /**
     * Film W10: a route that never advances (wedged on a half block or among
     * furniture) is not waited out forever. After WEDGE_TICKS without moving
     * the Builder steps off to the nearest cell with a whole floor, walking
     * (no teleport), and the next replan starts from there.
     */
    private boolean unwedge(ServerLevel level, long now) {
        if (now < stepOffUntil) {
            if (stepOffTo != null) {
                settler.getMoveControl().setWantedPosition(stepOffTo.getX() + 0.5D, stepOffTo.getY(),
                    stepOffTo.getZ() + 0.5D, MOVE_SPEED);
            }
            return true;
        }
        stepOffTo = null;
        // W36d (hunters_lodge 13k ticks, tavern_small 15k): when no route to
        // the hut or warehouse can even be made, navigation reports "done"
        // every replan and the wedge clock never ran. Still wanting to move
        // but standing still for long counts as wedged too.
        boolean trying = now - wantMoveTick <= REPLAN_INTERVAL * 3L;
        if (settler.getNavigation().isDone() && !(trying && stillTicks >= LONG_STILL_TICKS)) {
            wedgePos = null;
            return false;
        }
        Vec3 here = settler.position();
        if (wedgePos == null || here.distanceToSqr(wedgePos) > 0.04D) {
            if (wedgePos != null) {
                wedgeRounds = 0; // he moved: the last step-off worked
            }
            wedgePos = here;
            wedgeSince = now;
            return false;
        }
        if (now - wedgeSince < WEDGE_TICKS) {
            return false;
        }
        wedgePos = null;
        BlockPos me = settler.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos cell = me.offset(dx, dy, dz);
                    if ((dx == 0 && dz == 0) || !standable(level, cell)) {
                        continue;
                    }
                    double dist = cell.distSqr(me);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = cell;
                    }
                }
            }
        }
        settler.getNavigation().stop();
        lastReplan = 0L;
        if (best == null) {
            return false;
        }
        wedgeRounds++;
        if (wedgeRounds >= 3) {
            // Walked-off attempts failed three times: he is caught in
            // furniture collision (W29a tavern beer garden: 19,670 ticks
            // inside a table's fence corner). The same recovery vanilla gives
            // a player pushed into a block: a short slide (at most two blocks)
            // to the nearest free cell with a whole floor.
            AABB there = settler.getDimensions(settler.getPose())
                .makeBoundingBox(best.getX() + 0.5D, best.getY(), best.getZ() + 0.5D);
            if (level.noCollision(settler, there)) {
                settler.moveTo(best.getX() + 0.5D, best.getY(), best.getZ() + 0.5D, settler.getYRot(),
                    settler.getXRot());
                settler.recordRouteFailure("builder_unwedged");
                wedgeRounds = 0;
                return true;
            }
        }
        stepOffTo = best;
        stepOffUntil = now + STEP_OFF_TICKS;
        return true;
    }

    /** Motion per phase (owner: "work animations per phase"). */
    private SettlerActivity motionFor(BuildJob job, int i) {
        if (job.phase(i).removes() || job.hasFlag(i, BuildJob.F_CLEAR) || job.hasFlag(i, BuildJob.F_DRAIN)) {
            return SettlerActivity.WORK_MINE;
        }
        return switch (job.phase(i)) {
            case FOUNDATION, FILL -> job.state(i).is(BlockTags.MINEABLE_WITH_PICKAXE)
                ? SettlerActivity.WORK_CHISEL : SettlerActivity.WORK_BUILD;
            case ROOF, FINISH -> SettlerActivity.WORK_BUILD_HAMMER;
            default -> SettlerActivity.WORK_BUILD;
        };
    }

    /**
     * The tap on the clip's contact beat (never the loop seam): nail for
     * wood, chisel for stone. BUILD_PLACE taps at 0.90 s and 1.20 s of its
     * 1.60 s loop; BUILD_HAMMER strikes at 0.45 s of 1.00 s.
     */
    private void beat(ServerLevel level, BuildJob job, BlockPos target, SettlerActivity motion) {
        if (motion == SettlerActivity.WORK_MINE) {
            return; // the dig itself makes the block's break sound on removal
        }
        boolean stone = job.state(step).is(BlockTags.MINEABLE_WITH_PICKAXE);
        var sound = stone ? ModSounds.CHISEL_TAP.get() : ModSounds.NAIL_TAP.get();
        int t = workTicks;
        boolean contact = switch (motion) {
            case WORK_BUILD_HAMMER -> t % 20 == 9;
            case WORK_CHISEL -> t % 21 == 10;
            default -> t % 32 == 18 || t % 32 == 24;
        };
        if (contact) {
            WorkSoundSync.play(level, target, sound, 0.5F, 1.0F);
        }
    }

    private void updateStatus(BuildJob job, int i) {
        BuildPhase phase = job.phase(i);
        if (job.hasFlag(i, BuildJob.F_POUR)) {
            int done = 0;
            int total = 0;
            for (int k = 0; k < job.size(); k++) {
                if (job.hasFlag(k, BuildJob.F_POUR)) {
                    total++;
                    if (job.isDone(k)) {
                        done++;
                    }
                }
            }
            job.setStatus(BuildStatus.POURING, done, total);
            return;
        }
        switch (phase) {
            case CLEAR -> job.setStatus(BuildStatus.CLEARING,
                job.countPhase(BuildPhase.CLEAR, true), job.countPhase(BuildPhase.CLEAR, false));
            case FILL -> job.setStatus(BuildStatus.FILLING,
                job.countPhase(BuildPhase.FILL, true), job.countPhase(BuildPhase.FILL, false));
            case FOUNDATION, STRUCTURE -> {
                int[] layer = job.layerOf(i);
                job.setStatus(BuildStatus.BUILDING_LAYER, layer[0], layer[1]);
            }
            case ROOF -> {
                int[] layer = job.layerOf(i);
                job.setStatus(BuildStatus.ROOFING, layer[0], layer[1]);
            }
            case INTERIOR -> job.setStatus(BuildStatus.FITTING,
                job.countPhase(BuildPhase.INTERIOR, true), job.countPhase(BuildPhase.INTERIOR, false));
            case REDSTONE -> job.setStatus(BuildStatus.REDSTONE,
                job.countPhase(BuildPhase.REDSTONE, true), job.countPhase(BuildPhase.REDSTONE, false));
            case FINISH -> job.setStatus(BuildStatus.FINISHING);
            case DISMANTLE -> job.setStatus(BuildStatus.DISMANTLING,
                job.countPhase(BuildPhase.DISMANTLE, true), job.countPhase(BuildPhase.DISMANTLE, false));
        }
    }

    // -------------------------------------------------------------- load ---

    /**
     * Loads the next batch into the bag: exactly what the coming steps need,
     * up to the sack's capacity (the Logistics lane's sack tiers and cart
     * raise it), from the hut -- or, with no Courier employed, straight from
     * the warehouse. When neither has it, says so and asks.
     */
    private void tickLoad(ServerLevel level, Settlement settlement, BuildJob job, Building hut,
                          List<BuilderMaterials.ItemCount> stepCosts) {
        long now = level.getGameTime();
        List<Container> hutStock = BuilderStock.hutContainers(level, hut);
        Map<Item, Integer> batch = batchNeeds(job);
        // Does the hut (or the warehouse, self-fetching) hold the CURRENT step's item?
        if (scaffoldNeed > 0 && column != null) {
            int inBag = BuilderStock.bagCount(settler.bag, net.minecraft.world.item.Items.LADDER);
            if (inBag < 1) {
                List<BuilderMaterials.ItemCount> withLadders = new ArrayList<>(stepCosts);
                withLadders.add(new BuilderMaterials.ItemCount(net.minecraft.world.item.Items.LADDER, 1));
                stepCosts = withLadders;
            }
            int inHut = BuilderStock.count(hutStock, net.minecraft.world.item.Items.LADDER);
            if (inHut + inBag < scaffoldNeed) {
                // The village's own stock first: ladders made at the hut from
                // its planks, then a request to the warehouse.
                inHut += BuilderStock.craftLadders(hutStock, scaffoldNeed - inHut - inBag);
            }
            if (inHut + inBag < scaffoldNeed) {
                BuilderSupply.requestItem(level, settlement, hut, net.minecraft.world.item.Items.LADDER,
                    scaffoldNeed - inHut - inBag);
                if (inHut + inBag == 0 && BuilderStock.warehouseCount(level, settlement,
                    net.minecraft.world.item.Items.LADDER) == 0) {
                    // No ladder anywhere in the village: this block is set by
                    // the far reach instead (never a wait for the player).
                    job.noteFailure(step);
                    job.noteWhy(step, "no ladders in the village");
                    column = null;
                    scaffoldNeed = 0;
                    stage = Stage.BUILD;
                    return;
                }
            }
        }
        Item firstMissing = null;
        int firstShort = 0;
        for (BuilderMaterials.ItemCount cost : stepCosts) {
            int inBag = BuilderStock.bagCount(settler.bag, cost.item());
            if (inBag >= cost.count()) {
                waitingSince.remove(cost.item()); // on hand: the next shortage waits afresh
                continue;
            }
            if (BuilderStock.count(hutStock, cost.item()) >= cost.count() - inBag) {
                waitingSince.remove(cost.item());
                continue;
            }
            firstMissing = cost.item();
            // batchNeeds already takes the bag off: subtract only the hut here.
            firstShort = batch.getOrDefault(cost.item(), cost.count() - inBag)
                - BuilderStock.count(hutStock, cost.item());
            break;
        }
        BuildSiteSavedData.Pickup pickup = BuildSiteSavedData.get(level).settings(settlement.id).pickup();
        if (now >= nextRequest && pickup != BuildSiteSavedData.Pickup.FETCH_MYSELF) {
            nextRequest = now + REQUEST_INTERVAL;
            BuilderSupply.request(level, settlement, hut, job, settler.bag);
            if (firstMissing != null) {
                // QA-BUILD-01: the chosen step can lie beyond the ordered
                // batch BuilderSupply asks for; ask for its item directly
                // (requestItem refuses a second live row for the item).
                BuilderSupply.requestItem(level, settlement, hut, firstMissing, Math.max(1, firstShort));
            }
        }
        // What the player reads is the SITE's item ("needs 1 oak planks"),
        // even while he fetches the input to make it himself (W37 short bill).
        Item shownItem = firstMissing;
        int shownCount = firstShort;
        if (firstMissing != null && BuilderCraft.recipeFor(firstMissing) != null
            && (BuilderSupply.onOrder(level, settlement, hut, firstMissing) <= 0
                || waitedFor(firstMissing, now) >= SELF_CRAFT_AFTER)
            && BuilderStock.warehouseCount(level, settlement, firstMissing) <= 0) {
            // Owner: "Builder lager det selv". A staffed workshop's order
            // comes first (above); with none running, he makes the simple
            // shapes (and glass, slowly) himself at his hut from the
            // village's own stock. What is then missing is the INPUT.
            BlockPos bench = firstContainer(level, hut);
            if (bench != null && !atContainer(level, bench)) {
                mark(BuilderUtilisation.Kind.CARRY);
                job.setStatus(BuildStatus.FETCHING);
                waitAtHut(level, hut);
                return;
            }
            if (now < craftPauseUntil) {
                mark(BuilderUtilisation.Kind.PLACE);
                return;
            }
            List<Container> from = new ArrayList<>();
            from.add(settler.bag);
            from.addAll(hutStock);
            BuilderCraft.Recipe recipe = BuilderCraft.recipeFor(firstMissing);
            int made = BuilderCraft.craft(from, hutStock, firstMissing, Math.max(1, firstShort), 8);
            if (made > 0) {
                waitingSince.remove(firstMissing);
                job.setStatus(BuildStatus.CRAFTING, made,
                    "item:" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(firstMissing));
                craftPauseUntil = now + (recipe.smelt() ? 100L : 30L);
                settler.setActivity(SettlerActivity.WORK_BUILD);
                mark(BuilderUtilisation.Kind.PLACE);
                return; // next tick the hut holds it and he loads as usual
            }
            Item input = BuilderCraft.missingInput(from, firstMissing);
            if (input != null && input != firstMissing) {
                int apps = (Math.max(1, firstShort) + recipe.count() - 1) / recipe.count();
                int need = 0;
                for (BuilderMaterials.ItemCount in : recipe.inputs()) {
                    need += in.item() == input ? in.count() * apps : 0;
                }
                need = Math.max(need, recipe.smelt() ? 1 : 2) + (input == net.minecraft.world.item.Items.OAK_PLANKS ? 4 : 0);
                fetchExtra = new BuilderMaterials.ItemCount(input, Math.min(64, need));
                BuilderSupply.requestItem(level, settlement, hut, input, Math.min(64, need));
                firstMissing = input;
                firstShort = Math.min(64, need);
            }
        }
        if (firstMissing != null) {
            boolean courier = BuilderSupply.courierAvailable(level, settlement);
            int inWarehouse = BuilderStock.warehouseCount(level, settlement, firstMissing);
            // Pickup setting (MineColonies' builder options): fetch himself
            // always, only when no Courier works here, or never.
            // W37 (Carpenter stairs: 8 in the warehouse, a Courier hired, 0/2
            // set): on the default Hybrid setting he never waits on a late
            // delivery forever -- after COURIER_LATE he walks for it himself.
            boolean selfFetch = pickup == BuildSiteSavedData.Pickup.FETCH_MYSELF
                || (pickup == BuildSiteSavedData.Pickup.HYBRID
                    && (!courier || (inWarehouse > 0 && waitedFor(firstMissing, now) >= COURIER_LATE)));
            if (selfFetch && inWarehouse > 0) {
                mark(BuilderUtilisation.Kind.CARRY);
                selfFetch(level, settlement, firstMissing, batch);
                return;
            }
            int onTheWay = BuilderSupply.inFlight(level, settlement, hut).getOrDefault(firstMissing, 0);
            String item = "item:" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(shownItem);
            int onOrder = BuilderSupply.onOrder(level, settlement, hut, firstMissing);
            if (inWarehouse <= 0 && onTheWay <= 0 && onOrder <= 0) {
                job.setStatus(BuildStatus.NEEDS_PLAYER, Math.max(1, shownCount), item);
                BuildJobs.noticeBlocked(level, settlement, job, settler,
                    Component.translatable("hearthstead.builder.notice.needs",
                        Math.max(1, shownCount), new net.minecraft.world.item.ItemStack(shownItem).getHoverName()));
            } else {
                job.setStatus(BuildStatus.WAITING_FOR, Math.max(1, shownCount), item, inWarehouse, onTheWay);
            }
            BuildSiteSavedData.get(level).changed();
            // Wait at the hut, where deliveries land; bring the bag home meanwhile.
            mark(BuilderUtilisation.Kind.WAIT_MATERIAL);
            waitAtHut(level, hut);
            return;
        }
        // Walk to the hut and take the batch.
        BlockPos chest = firstContainer(level, hut);
        if (chest == null) {
            done = true;
            return;
        }
        job.setStatus(BuildStatus.FETCHING);
        mark(BuilderUtilisation.Kind.CARRY);
        if (!atContainer(level, chest)) {
            wantMoveTick = level.getGameTime();
            if (now - lastReplan >= REPLAN_INTERVAL || settler.getNavigation().isDone()) {
                lastReplan = now;
                StandCells.moveNextTo(level, settler, chest, MOVE_SPEED);
            }
            settler.setActivity(SettlerActivity.TRAVELING);
            return;
        }
        settler.getNavigation().stop();
        depositUnneeded(level, job, hutStock);
        takeBatch(hutStock, batchNeeds(job));
        restIfNotLoaded(job, stepCosts, now);
        stage = Stage.BUILD;
        lastReplan = 0L;
        stepSince = now;
    }

    /**
     * The chosen block's material could not go into the sack (a full chest
     * would not take the rest of the sack back): rest that block and set the
     * ones the sack does cover first, then come back for it -- never a
     * FETCHING loop at the chest.
     */
    private void restIfNotLoaded(BuildJob job, List<BuilderMaterials.ItemCount> stepCosts, long now) {
        if (step >= 0 && !bagCovers(stepCosts)) {
            job.noteWhy(step, "no room in sack");
            job.deferStep(step, now + REQUEST_INTERVAL);
            step = -1;
        }
    }

    /**
     * At the hut, anything in the sack this site no longer needs (leftovers
     * of a stopped site, salvage) goes into the hut first, so a full sack can
     * never keep the Builder from loading what the next step needs.
     */
    private void depositUnneeded(ServerLevel level, BuildJob job, List<Container> into) {
        // Codex T3b: keep only what the next load's worth of steps uses; the
        // excess goes into the container at hand. Whatever does not fit
        // stays in the sack -- physical, never dropped, never deleted.
        Map<Item, Integer> keep = new java.util.HashMap<>(windowNeeds(job));
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            net.minecraft.world.item.ItemStack stack = settler.bag.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            int kept = Math.min(stack.getCount(), keep.getOrDefault(stack.getItem(), 0));
            keep.merge(stack.getItem(), -kept, Integer::sum);
            int excess = stack.getCount() - kept;
            if (excess <= 0) {
                continue;
            }
            net.minecraft.world.item.ItemStack rest = BuilderStock.insertAll(into, stack.copyWithCount(excess));
            stack.setCount(kept + rest.getCount());
            if (stack.isEmpty()) {
                settler.bag.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
            }
        }
        settler.bag.setChanged();
    }

    /**
     * A Builder carries a working load, not a courier's parcel: 24 items by
     * default, and every extra item the Logistics lane's sack tiers or the
     * hand cart add to the settler's capacity counts double for him.
     */
    public static int builderCapacity(SettlerEntity settler) {
        return BASE_LOAD + 2 * Math.max(0, settler.getCarryCapacity() - SettlerEntity.BASE_CARRY_CAPACITY);
    }

    public static final int BASE_LOAD = 64;

    /** What the next steps need beyond the bag, capped at the Builder's load. */
    private Map<Item, Integer> batchNeeds(BuildJob job) {
        Map<Item, Integer> need = windowNeeds(job);
        for (Map.Entry<Item, Integer> entry : need.entrySet()) {
            entry.setValue(Math.max(0, entry.getValue() - BuilderStock.bagCount(settler.bag, entry.getKey())));
        }
        need.values().removeIf(v -> v <= 0);
        return need;
    }

    /**
     * The whole of what the next load's worth of steps needs, bag or not --
     * the most of any item worth carrying right now (ladders for a column
     * being hung included). Both loading and what he keeps at the hut use it.
     */
    private Map<Item, Integer> windowNeeds(BuildJob job) {
        int capacity = Math.max(1, builderCapacity(settler));
        // Ladders share the load with the materials (Codex T3b): a tall
        // column is carried in trips, never a sack of ladders only.
        int ladders = scaffoldNeed > 0 && column != null ? Math.min(scaffoldNeed, Math.max(1, capacity / 2)) : 0;
        return loadWindow(job, step, ladders, capacity, settler.bag.getContainerSize(),
            settler.level().getGameTime());
    }

    /**
     * The load window. The selected step comes first: {@link #nextStep} may
     * choose any pending block of the layer within {@link #FRONT_SCAN}, far
     * beyond the ordered prefix, and it keeps that block until it is set --
     * so its material must be loaded and must never be handed back at the
     * hut (QA-BUILD-01: a sack full of prefix cobblestone looped FETCHING
     * for a plank forever). Then the column's ladders, then the pending
     * steps in build order, clamped so the window never exceeds the load:
     * what is kept at the hut then always leaves room for the chosen step.
     */
    public static Map<Item, Integer> loadWindow(BuildJob job, int selected, int ladders, int capacity, long now) {
        return loadWindow(job, selected, ladders, capacity, Integer.MAX_VALUE, now);
    }

    /**
     * As above, and the window also fits the sack's SLOTS (Sunday blocker,
     * 188 of 194 generated builds stuck FETCHING at ~100 steps): once the
     * walls start, the next 64 units span more item kinds (logs, planks,
     * stairs, panes, trapdoors, doors...) than the 8-slot sack holds, so the
     * chosen block's item never fitted and he fetched forever. Steps whose
     * items would need another slot are left for the next trip.
     */
    public static Map<Item, Integer> loadWindow(BuildJob job, int selected, int ladders, int capacity, int slots,
                                                long now) {
        Map<Item, Integer> need = new LinkedHashMap<>();
        int units = 0;
        int[] used = {0};
        if (selected >= 0 && selected < job.size() && pending(job, selected, now)) {
            for (BuilderMaterials.ItemCount c : BuilderMaterials.costsOfStep(job, selected)) {
                used[0] += slotDelta(need, c.item(), c.count());
                need.merge(c.item(), c.count(), Integer::sum);
                units += c.count();
            }
        }
        if (ladders > 0) {
            int fit = (int) Math.min(ladders, Math.max(0L, (long) slots - used[0]) * 64L);
            if (fit > 0) {
                used[0] += slotDelta(need, net.minecraft.world.item.Items.LADDER, fit);
                need.merge(net.minecraft.world.item.Items.LADDER, fit, Integer::sum);
                units += fit;
            }
        }
        for (int i = job.cursor(); i < job.size() && units < capacity; i++) {
            if (i == selected || !pending(job, i, now)) {
                continue;
            }
            List<BuilderMaterials.ItemCount> costs = BuilderMaterials.costsOfStep(job, i);
            // Whole steps only, and only while they still fit the sack's slots.
            Map<Item, Integer> trial = new LinkedHashMap<>();
            int extraSlots = 0;
            int stepUnits = 0;
            for (BuilderMaterials.ItemCount c : costs) {
                int take = Math.min(c.count(), capacity - units - stepUnits);
                if (take <= 0) {
                    break;
                }
                Map<Item, Integer> view = new LinkedHashMap<>(need);
                trial.forEach((k, v) -> view.merge(k, v, Integer::sum));
                extraSlots += slotDelta(view, c.item(), take);
                trial.merge(c.item(), take, Integer::sum);
                stepUnits += take;
            }
            if (trial.isEmpty() || used[0] + extraSlots > slots) {
                continue;
            }
            used[0] += extraSlots;
            trial.forEach((k, v) -> need.merge(k, v, Integer::sum));
            units += stepUnits;
        }
        return need;
    }

    /** Extra sack slots {@code count} more of {@code item} takes on top of {@code have}. */
    private static int slotDelta(Map<Item, Integer> have, Item item, int count) {
        int max = Math.max(1, item.getDefaultMaxStackSize());
        int before = have.getOrDefault(item, 0);
        int after = before + count;
        return (after + max - 1) / max - (before + max - 1) / max;
    }

    /** The batch recomputed after a deposit freed room (falls back to the given one). */
    private Map<Item, Integer> batchNeeds(@Nullable BuildJob job, Map<Item, Integer> fallback) {
        return job == null ? fallback : batchNeeds(job);
    }

    private void takeBatch(List<Container> from, Map<Item, Integer> batch) {
        int room = Math.max(0, builderCapacity(settler) - BuilderStock.bagLoad(settler.bag));
        for (Map.Entry<Item, Integer> entry : batch.entrySet()) {
            if (room <= 0) {
                break;
            }
            int moved = BuilderStock.moveToBag(from, entry.getKey(), Math.min(room, entry.getValue()), settler.bag);
            room -= moved;
        }
    }

    /** No Courier: walk to the warehouse chest holding the item and take the batch. */
    private void selfFetch(ServerLevel level, Settlement settlement, Item item, Map<Item, Integer> batch) {
        long now = level.getGameTime();
        if (fetchFrom == null
            || level.hasChunkAt(fetchFrom) && !(level.getBlockEntity(fetchFrom) instanceof Container)) {
            List<BlockPos> sources = BuilderStock.warehouseContainersHolding(level, settlement, item);
            fetchFrom = sources.isEmpty() ? null : sources.get(0);
            if (fetchFrom == null) {
                return;
            }
        }
        BuildJob job = job(level, settlement);
        if (job != null) {
            job.setStatus(BuildStatus.FETCHING);
        }
        if (!atContainer(level, fetchFrom)) {
            wantMoveTick = level.getGameTime();
            if (now - lastReplan >= REPLAN_INTERVAL || settler.getNavigation().isDone()) {
                lastReplan = now;
                StandCells.moveNextTo(level, settler, fetchFrom, MOVE_SPEED);
            }
            settler.setActivity(SettlerActivity.TRAVELING);
            return;
        }
        settler.getNavigation().stop();
        if (level.getBlockEntity(fetchFrom) instanceof Container chest) {
            if (job != null) {
                depositUnneeded(level, job, List.of(chest));
            }
            Map<Item, Integer> want = new LinkedHashMap<>(batchNeeds(job == null ? null : job, batch));
            if (fetchExtra != null) {
                // An input for something he makes himself (planks for stairs).
                want.merge(fetchExtra.item(), fetchExtra.count(), Math::max);
                fetchExtra = null;
            }
            takeBatch(List.of(chest), want);
            if (job != null && step >= 0 && step < job.size()) {
                restIfNotLoaded(job, BuilderMaterials.costsOfStep(job, step), now);
            }
        }
        fetchFrom = null;
        stage = Stage.BUILD;
        stepSince = now;
    }

    private void waitAtHut(ServerLevel level, Building hut) {
        BlockPos chest = firstContainer(level, hut);
        if (chest != null && !atContainer(level, chest)) {
            wantMoveTick = level.getGameTime();
            long now = level.getGameTime();
            if (now - lastReplan >= REPLAN_INTERVAL || settler.getNavigation().isDone()) {
                lastReplan = now;
                StandCells.moveNextTo(level, settler, chest, MOVE_SPEED);
            }
            settler.setActivity(SettlerActivity.TRAVELING);
            return;
        }
        settler.getNavigation().stop();
        if (settler.getActivity() != SettlerActivity.IDLE) {
            settler.setActivity(SettlerActivity.IDLE);
        }
        // Stay in LOAD: the next tick re-reads the hut, so a delivery (or a
        // player dropping planks in the chest) is picked up at once.
    }

    @Nullable
    private static BlockPos firstContainer(ServerLevel level, Building hut) {
        List<BlockPos> containers = WarehouseIndex.containers(level, hut);
        return containers.isEmpty() ? null : containers.get(0);
    }

    // ------------------------------------------------------------ finish ---

    /** Hangs the column rung by rung from the sack, standing below each new rung. */
    private void tickScaffold(ServerLevel level, BuildJob job, long now) {
        var col = column;
        BlockPos rung = col == null ? null : col.next(level);
        if (rung == null) {
            scaffoldNeed = 0;
            column = null;                 // the column stands: back to the step
            stepSince = now;
            lastReplan = 0L;
            return;
        }
        int total = col.rungs().size();
        job.setStatus(BuildStatus.SCAFFOLDING, total - col.missing(level), total);
        if (!inReach(rung) || occupies(rung)) {
            walkTo(level, job, rung, now);
            settler.setActivity(SettlerActivity.CARRY_MATERIALS);
            if (now - stepSince > STUCK_TICKS) {
                column = null;             // cannot hang it: let the stuck guard decide
            }
            return;
        }
        settler.getNavigation().stop();
        if (settler.getActivity() != SettlerActivity.WORK_BUILD) {
            settler.setActivity(SettlerActivity.WORK_BUILD);
        }
        if (++workTicks < PLACE_TICKS) {
            return;
        }
        workTicks = 0;
        if (com.hearthstead.settlement.builder.BuilderScaffold.hang(level, job, rung, col.facing(), settler.bag)) {
            scaffoldNeed = col.missing(level);
            stepSince = now;
            WorkSoundSync.play(level, rung, ModSounds.BUILDER_LADDER_RUNG.get(), 0.5F, 1.0F);
        } else if (BuilderStock.bagCount(settler.bag, net.minecraft.world.item.Items.LADDER) < 1) {
            scaffoldNeed = col.missing(level);
            stage = Stage.LOAD;
        } else {
            // The planned rung or its support changed. Keep installed ladders
            // and the step's retry clock; normal routing/stuck recovery resumes.
            column = null;
            scaffoldNeed = 0;
        }
    }

    /** Before a job completes, every temporary rung comes down, top first. */
    private boolean takeDownScaffold(ServerLevel level, BuildJob job, Building hut, long now) {
        BlockPos rung = com.hearthstead.settlement.builder.BuilderScaffold.topRung(job);
        if (rung == null) {
            takedownSince = 0L;
            return false;
        }
        job.setStatus(BuildStatus.UNSCAFFOLDING, job.scaffold.size());
        if (takedownSince == 0L) {
            takedownSince = now;
        }
        if (now - takedownSince > STUCK_TICKS * 2L) {
            // Bounded: never an endless take-down. The remaining rungs become
            // their own persisted cleanup job; this site may complete.
            takedownSince = 0L;
            com.hearthstead.settlement.builder.BuildJobs.queueScaffoldCleanupFor(level, job);
            return !job.scaffold.isEmpty();
        }
        if (!level.getBlockState(rung).is(net.minecraft.world.level.block.Blocks.LADDER)) {
            com.hearthstead.settlement.builder.BuilderScaffold.takeDown(level, job, rung,
                BuilderStock.hutContainers(level, hut), settler.blockPosition());
            return true;
        }
        // W3b: he must not stand on (or cling to) the column he is taking
        // down -- the top rung is often the one he stood on to set the top.
        boolean farOk = settler.onGround() && !onLadder(level) && !occupies(rung)
            && now - takedownSince > STUCK_TICKS / 2
            && settler.getEyePosition().distanceToSqr(Vec3.atCenterOf(rung)) <= ROOF_REACH * ROOF_REACH;
        if (!farOk && (!inReach(rung) || occupies(rung) || onLadder(level))) {
            walkToGround(level, job, rung, now);
            return true;
        }
        settler.getNavigation().stop();
        if (++workTicks < CLEAR_TICKS) {
            return true;
        }
        workTicks = 0;
        takedownSince = now;
        // The ladder goes into the sack and home with the leftovers (physical).
        level.setBlock(rung, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
            net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
        int left = BuilderStock.insert(settler.bag, new net.minecraft.world.item.ItemStack(
            net.minecraft.world.item.Items.LADDER));
        if (left > 0) {
            BuilderStock.store(level, BuilderStock.hutContainers(level, hut),
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LADDER), settler.blockPosition());
        }
        job.scaffold.remove(rung);
        BuildSiteSavedData.get(level).setDirty();
        return true;
    }

    private long takedownSince;

    private boolean onLadder(ServerLevel level) {
        return level.getBlockState(settler.blockPosition()).is(net.minecraft.world.level.block.Blocks.LADDER);
    }

    /** Like walkTo, but only to cells on real ground, off every ladder. */
    private void walkToGround(ServerLevel level, BuildJob job, BlockPos target, long now) {
        wantMoveTick = now;
        if (now - lastReplan < REPLAN_INTERVAL && !settler.getNavigation().isDone()) {
            return;
        }
        lastReplan = now;
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (BlockPos cell : standCells(level, job, target)) {
            if (!level.getBlockState(cell).is(net.minecraft.world.level.block.Blocks.LADDER)
                && !level.getBlockState(cell.below()).is(net.minecraft.world.level.block.Blocks.LADDER)) {
                cells.add(cell);
            }
        }
        if (!cells.isEmpty() && StandCells.moveToAny(level, settler, cells, MOVE_SPEED)) {
            return;
        }
        settler.getNavigation().moveTo(target.getX() + 0.5, target.getY() - 4, target.getZ() + 0.5, MOVE_SPEED);
    }

    private void finishOrWait(ServerLevel level, Settlement settlement, BuildJob job, Building hut) {
        long now = level.getGameTime();
        if (job.exhausted() && !job.scaffold.isEmpty() && takeDownScaffold(level, job, hut, now)) {
            mark(BuilderUtilisation.Kind.SCAFFOLD);
            return;
        }
        if (!job.exhausted()) {
            // Only resting (deferred) steps left: wait for them.
            settler.getNavigation().stop();
            if (settler.getActivity() != SettlerActivity.IDLE) {
                settler.setActivity(SettlerActivity.IDLE);
            }
            return;
        }
        if (job.blockedCount() > 0 && !job.allowOverwrite) {
            BlockPos first = null;
            for (int i = 0; i < job.size(); i++) {
                if (job.isBlocked(i)) {
                    first = job.pos(i);
                    break;
                }
            }
            if (first != null) {
                job.setStatus(BuildStatus.BLOCKED_PLAYER_BLOCK, first.getX(), first.getY(), first.getZ());
                BuildJobs.noticeBlocked(level, settlement, job, settler,
                    Component.translatable("hearthstead.builder.notice.player_block",
                        first.getX(), first.getY(), first.getZ()));
            }
            BuildJobs.release(job, settler);
            BuildSiteSavedData.get(level).changed();
            stage = Stage.RETURN;
            return;
        }
        BuildJobs.finish(level, settlement, job);
        if (job.state != BuildJob.State.ACTIVE) {
            BuildJobs.release(job, settler);
            stage = Stage.RETURN;
        }
        stepSince = now;
    }

    /** Leftovers go back to the hut: nothing stays in a sack by accident. */
    private void tickReturn(ServerLevel level, Building hut) {
        mark(BuilderUtilisation.Kind.CARRY);
        if (settler.bag.isEmpty()) {
            done = true;
            return;
        }
        BlockPos chest = firstContainer(level, hut);
        if (chest == null) {
            done = true;
            return;
        }
        if (!atContainer(level, chest)) {
            wantMoveTick = level.getGameTime();
            long now = level.getGameTime();
            if (now - lastReplan >= REPLAN_INTERVAL || settler.getNavigation().isDone()) {
                lastReplan = now;
                StandCells.moveNextTo(level, settler, chest, MOVE_SPEED);
            }
            settler.setActivity(SettlerActivity.CARRY_MATERIALS);
            return;
        }
        settler.getNavigation().stop();
        BuilderStock.emptyBag(level, settler.bag, BuilderStock.hutContainers(level, hut), settler.blockPosition());
        done = true;
    }
}
