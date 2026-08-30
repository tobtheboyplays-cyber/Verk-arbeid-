package com.hearthstead.entity.ai;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.JobEffects;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.Trait;
import com.hearthstead.entity.WorkContainerKind;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.FoundingJourneyProgress;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.development.DevelopmentQuests;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.work.ContainerApproach;
import com.hearthstead.settlement.work.WorkerProvenanceSavedData;
import com.hearthstead.settlement.work.WorkerProvenanceService;
import com.hearthstead.settlement.work.WorkerStackProvenance;
import com.hearthstead.settlement.work.WorkerStorageAuthority;
import com.hearthstead.settlement.workzone.WorkZone;
import com.hearthstead.settlement.workzone.WorkZoneService;
import com.hearthstead.logistics.StopReason;
import com.hearthstead.util.QaTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * The lumberer fells natural trees top-down (never leaving floating trunks),
 * replants a matching sapling, and hauls the logs home.
 */
public class LumbererWorkGoal extends Goal {
    private static final int MAX_TREE_LOGS = 96;
    private static final int MAX_DRIFT = 8;
    private static final int MIN_LEAVES = 4;
    /** CHOP is a 20-tick loop with its physical axe/wood contact at tick 11. */
    public static final int CHOP_CYCLE_TICKS = 20;
    public static final int CHOP_CONTACT_TICK = 11;
    /** Mid-band authored reference; Strength may require two to four contacts. */
    public static final int CHOP_CONTACTS_PER_LOG = 3;
    /** Mid-band reference duration retained for animation-contract tooling. */
    public static final int TICKS_PER_LOG =
        (CHOP_CONTACTS_PER_LOG - 1) * CHOP_CYCLE_TICKS + CHOP_CONTACT_TICK;
    private static final int LIMB_DURATION = 26;
    /**
     * An idle lumberer must not look broken while a resumable survey crawls
     * across a large settlement. Small batches resume every one or two goal
     * evaluations, keeping the outer-radius latency short without concentrating
     * tens of thousands of block-state reads in one server tick. Already-
     * claimed trunks are filtered inside the batch so a second worker does not
     * burn its result quota rediscovering the first worker's tree.
     */
    /**
     * A column may require up to {@link #TRUNK_DESCENT} block reads below its
     * heightmap surface. 128 columns therefore caps one idle-worker survey at
     * 4,096 state reads instead of the previous 24,576-tick spike. A one-tick
     * resumable cadence preserves the same outer-radius discovery latency.
     */
    private static final int TREE_SCAN_BUDGET = 128;
    private static final int TREE_SCAN_RESULTS = 12;
    private static final int TREE_SCAN_COOLDOWN_MIN = 1;
    private static final int TREE_SCAN_COOLDOWN_JITTER = 2;
    private static final int ARRIVAL_REPATH_TICKS = 20;
    private static final int CAMP_REPATH_TICKS = 40;
    private static final int MAX_PATH_FAILURES = 8;
    private static final int RECOVERY_RETRY_TICKS = 100;
    private static final int UNLOADED_DROP_WAIT_TICKS = 100;
    /**
     * Ground navigation stops an adult mob on an adjacent standing node, not
     * with its origin centred on the tiny ItemEntity. Origin-to-origin
     * distance there is commonly 1.6-2.3 blocks (especially when the drop is
     * resting on a sapling), even though the item is visibly within ordinary
     * hand reach. The previous 1.5-block gate made the navigator finish, then
     * rejected that valid contact forever. 2.5 blocks remains a short,
     * player-scale reach and the item still transfers only on the authored
     * pickup contact frame.
     */
    private static final double ITEM_CONTACT_DISTANCE_SQR = 6.25;
    /**
     * A path may terminate on the adjacent lower floor while the worker is
     * still standing one block higher on the stump/dirt shoulder. The exact
     * approach was already proven reachable; this small terminal tolerance
     * accepts that ordinary step-height pose while the independent short-reach
     * and clear-ray gates below still own physical contact.
     */
    private static final double ITEM_APPROACH_ARRIVAL_DISTANCE_SQR = 2.25;
    private static final double CONTAINER_CONTACT_DISTANCE_SQR = 2.25;
    private static final double RECOVERY_HORIZONTAL = 64.0;
    private static final double RECOVERY_VERTICAL = 128.0;
    private static final String COLLECTION_SITE_POS_TAG =
        "HearthsteadLumberCollectionSite";
    private static final String COLLECTION_SITE_DIMENSION_TAG =
        "HearthsteadLumberCollectionDimension";
    private static final String RECOVERY_RETRY_AFTER_TAG =
        "HearthsteadLumberRecoveryRetryAfter";

    /** Public server/client animation transaction contract (20 ticks = 1 s). */
    public static final int CONTAINER_DOWN_DURATION = 28;
    /** Frame contact: the detached frame reaches the ground. */
    public static final int CONTAINER_DOWN_CONTACT_TICK = 20;
    public static final int GROUND_PICKUP_DURATION = 20;
    public static final int GROUND_PICKUP_CONTACT_TICK = 12;
    public static final int CONTAINER_STOW_DURATION = 22;
    public static final int CONTAINER_STOW_CONTACT_TICK = 12;
    public static final int CONTAINER_UP_DURATION = 32;
    /** Frame contact: the lifted load breaks cleanly from the ground. */
    public static final int CONTAINER_UP_CONTACT_TICK = 12;
    /**
     * How long a claimed tree stays claimed without a heartbeat. Renewed
     * every active tick this goal is running with a tree in hand ({@link
     * #renewClaim}), so a lumberer genuinely felling a tree never loses it
     * mid-chop however long the whole job takes -- a single big jungle
     * trunk alone can run {@code MAX_TREE_LOGS * TICKS_PER_LOG} ticks of
     * chopping, well past this TTL, and renewal is what covers that. This
     * only ever reclaims a tree whose lumberer stopped ticking altogether
     * (death, a permanent unbind) without ever reaching {@link #stop()}'s
     * normal release. Same order of magnitude as {@code
     * RepairWorkGoal#CLAIM_TTL_TICKS} for the same reason: generous enough
     * that no ordinary gap between renewals ever lapses it, short enough
     * that a genuinely stuck lumberer does not strand a tree for good.
     */
    public static final int TREE_CLAIM_TTL_TICKS = 600;

    private static final Map<Block, Block> SAPLING_FOR_LOG = new HashMap<>();

    static {
        SAPLING_FOR_LOG.put(Blocks.OAK_LOG, Blocks.OAK_SAPLING);
        SAPLING_FOR_LOG.put(Blocks.BIRCH_LOG, Blocks.BIRCH_SAPLING);
        SAPLING_FOR_LOG.put(Blocks.SPRUCE_LOG, Blocks.SPRUCE_SAPLING);
        SAPLING_FOR_LOG.put(Blocks.JUNGLE_LOG, Blocks.JUNGLE_SAPLING);
        SAPLING_FOR_LOG.put(Blocks.ACACIA_LOG, Blocks.ACACIA_SAPLING);
        SAPLING_FOR_LOG.put(Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_SAPLING);
        SAPLING_FOR_LOG.put(Blocks.CHERRY_LOG, Blocks.CHERRY_SAPLING);
        SAPLING_FOR_LOG.put(Blocks.MANGROVE_LOG, Blocks.MANGROVE_PROPAGULE);
    }

    private enum Mode {
        TO_TREE,
        CHOPPING,
        LIMBING,
        CONTAINER_DOWN,
        SELECTING_ITEM,
        TO_ITEM,
        PICKING_ITEM,
        RETURNING_TO_CONTAINER,
        STOWING_ITEM,
        CONTAINER_UP,
        TO_CAMP,
        TO_COLLECTION_SITE
    }

    private final SettlerEntity settler;
    private final WorkScanner scanner = new WorkScanner();
    private final GroundCollectionSession collection;
    private Mode mode;
    private BlockPos treeBase;
    private List<BlockPos> treeLogs = List.of();
    private Block treeLogBlock;
    private int chopTicks;
    /** Extra bounded rest between logs; fatigue slows work but never hard-stops it. */
    private int chopRecoveryTicks;
    private int limbTicks;
    private int haulTicks;
    private int phaseTicks;
    private int unresolvedDropTicks;
    private int scanCooldown;
    /** Missing-tool chest/request retry is bounded; AI selection itself may run every tick. */
    private int equipmentRetryCooldown;
    private int repathTimer;
    private int stuckChecks;
    /** The generic route budget is rebased only for a genuinely new target. */
    @Nullable
    private Mode pathBudgetMode;
    @Nullable
    private BlockPos pathBudgetTarget;
    private double bestPathDistanceSqr = Double.MAX_VALUE;
    private int campStuckChecks;
    @Nullable
    private BlockPos campBudgetTarget;
    private double bestCampDistanceSqr = Double.MAX_VALUE;
    /** Best real distance reached for the selected physical drop. */
    private double bestItemDistanceSqr = Double.MAX_VALUE;
    /** Exact reachable feet node selected for the current physical drop. */
    @Nullable
    private BlockPos selectedItemApproach;
    private boolean done;
    /** Real chest in the lumber camp; no output is posted to the Hearth. */
    private BlockPos depositTarget;
    @Nullable
    private BlockPos containerPos;
    private boolean contactTransferred;
    /** Exact-path arrival was proven when the pickup wind-up began. */
    private boolean pickupRouteAuthorized;
    /** Last Work Zone diagnosis emitted by this goal; suppresses tick spam. */
    private StopReason workZoneState = StopReason.NONE;
    /** Persisted domain authority lives in WorkerProvenanceSavedData. */
    @Nullable
    private UUID workActionId;

    public LumbererWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        this.collection = new GroundCollectionSession(settler,
            stack -> stack.is(ItemTags.LOGS), MAX_TREE_LOGS);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private void persistCollectionSite(ServerLevel level, BlockPos site) {
        CompoundTag persistent = settler.getPersistentData();
        persistent.putLong(COLLECTION_SITE_POS_TAG, site.asLong());
        persistent.putString(COLLECTION_SITE_DIMENSION_TAG,
            level.dimension().location().toString());
    }

    @Nullable
    private BlockPos persistedCollectionSite(ServerLevel level) {
        CompoundTag persistent = settler.getPersistentData();
        if (!persistent.contains(COLLECTION_SITE_POS_TAG, Tag.TAG_LONG)
            || !persistent.contains(COLLECTION_SITE_DIMENSION_TAG, Tag.TAG_STRING)
            || !level.dimension().location().toString().equals(
                persistent.getString(COLLECTION_SITE_DIMENSION_TAG))) {
            return null;
        }
        return BlockPos.of(persistent.getLong(COLLECTION_SITE_POS_TAG));
    }

    private void clearCollectionSite() {
        settler.getPersistentData().remove(COLLECTION_SITE_POS_TAG);
        settler.getPersistentData().remove(COLLECTION_SITE_DIMENSION_TAG);
    }

    /** Persisted wall-clock gate prevents stop/start or reload retry storms. */
    private boolean recoveryRetryReady(ServerLevel level) {
        CompoundTag persistent = settler.getPersistentData();
        if (!persistent.contains(RECOVERY_RETRY_AFTER_TAG, Tag.TAG_LONG)) {
            return true;
        }
        if (level.getGameTime() < persistent.getLong(RECOVERY_RETRY_AFTER_TAG)) {
            return false;
        }
        persistent.remove(RECOVERY_RETRY_AFTER_TAG);
        return true;
    }

    private void pauseRecoveryRoute(ServerLevel level, String reason) {
        settler.recordRouteFailure(reason);
        settler.getPersistentData().putLong(RECOVERY_RETRY_AFTER_TAG,
            level.getGameTime() + RECOVERY_RETRY_TICKS);
        settler.getNavigation().stop();
        done = true;
    }

    private boolean workConditions() {
        return settler.getProfession() == Profession.LUMBERER
            && settler.isBound()
            && settler.dayPhase().work();
    }

    @Override
    public boolean canUse() {
        if (!(settler.level() instanceof ServerLevel level)
            || settler.getProfession() != Profession.LUMBERER
            || !settler.isBound()) {
            return false;
        }
        Settlement s = settler.settlement();
        if (s == null) {
            return false;
        }
        // Strength is real gameplay state: it expands the physical trip budget.
        // Preserve deliberately reduced capacities used by tests/admin tools.
        int strengthCapacity = JobEffects.carryItems(
            settler.attribute(Attribute.STRENGTH), Trait.carry(settler.traits()));
        if (settler.getCarryCapacity() >= SettlerEntity.BASE_CARRY_CAPACITY
            && strengthCapacity > settler.getCarryCapacity()) {
            settler.setCarryCapacity(strengthCapacity);
        }
        Building employer = Employment.employerOf(s, settler.getUUID());
        WorkZone zone = authoritativeZone(level, s, employer);

        // A tree action survives goal/entity reload independently of the
        // transient claim table. Resume its exact remaining block set before
        // considering cargo recovery or a new scan. The root is felled last,
        // so every non-terminal resume still has a physical base to reclaim.
        WorkerProvenanceSavedData.ActionView resume = employer == null ? null
            : WorkerProvenanceService.resumableLumberTree(level, s, employer,
                settler);
        if (resume != null) {
            List<BlockPos> remaining = new ArrayList<>();
            for (BlockPos planned : resume.planned()) {
                if (!resume.resolved().contains(planned)) {
                    remaining.add(planned);
                }
            }
            // A non-terminal tree is new physical chopping, not recovery.
            // Without this gate canUse() starts it after hours, while
            // canContinueToUse() immediately stops TO_TREE/CHOPPING, producing
            // repeated claim/path/equipment churn until the next work phase.
            if (!remaining.isEmpty() && !workConditions()) {
                return false;
            }
            // A fully felled persisted action only needs terminal limbing and
            // commit. Do not demand a replacement axe after every physical
            // log and its exact durability receipt already exist.
            if (!remaining.isEmpty()
                && !EquipmentRequests.equipFromWorkplace(level, s, settler)) {
                equipmentRetryCooldown = 20;
                return false;
            }
            if (!remaining.isEmpty()
                && !claim(level, resume.source(), level.getGameTime())) {
                return false;
            }
            workActionId = resume.id();
            treeBase = resume.source();
            treeLogs = List.copyOf(remaining);
            treeLogBlock = level.getBlockState(treeBase).getBlock();
            mode = remaining.isEmpty() ? Mode.LIMBING : Mode.TO_TREE;
            if (remaining.isEmpty()) {
                limbTicks = 0;
                settler.setActivity(SettlerActivity.WORK_LIMB);
            }
            resetPathBudget();
            clearWorkZoneStop();
            return true;
        }

        BlockPos rememberedSite = persistedCollectionSite(level);
        BlockPos placed = settler.placedWorkContainerPos();
        boolean hasRecoveryWork = bagCount() > 0
            || collection.hasTrackedDrops() || placed != null
            || rememberedSite != null;
        if (hasRecoveryWork && !recoveryRetryReady(level)) {
            return false;
        }

        // A persisted placed container is physical authority after an AI
        // interruption or chunk reload. Go back to it before doing anything
        // else; never make its contents jump onto the worker at canUse().
        if (placed != null && settler.placedWorkContainerKind() == WorkContainerKind.SACK) {
            containerPos = placed.immutable();
            persistCollectionSite(level, containerPos);
            if (collectionTargetAllowed(level, zone, placed)) {
                recoverOwnedInsideZone(level, zone, placed);
            }
            collection.adoptLegacyEligibleOffhandAtPlacedContainer();
            if (bagCount() > 0 || (zone != null && collection.hasTrackedDrops())
                || collection.ownsOffhandItem()) {
                mode = Mode.RETURNING_TO_CONTAINER;
                return true;
            }
            // Empty stale projection (for example a reload after the final
            // stow but before the up event) is safe to clear server-side.
            settler.clearWorkContainer();
            clearCollectionSite();
            containerPos = null;
            rememberedSite = null;
        }

        // The sack projection intentionally disappears while it is on the
        // worker's back. The collection site's exact coordinate and bounded
        // UUID index remain persisted separately so a server/entity reload
        // cannot turn a 3+1 haul into an orphaned fourth log.
        if (rememberedSite != null) {
            containerPos = rememberedSite.immutable();
            if (collectionTargetAllowed(level, zone, rememberedSite)) {
                recoverOwnedInsideZone(level, zone, rememberedSite);
            }
            collection.adoptEligibleOffhand();
            if (bagCount() > 0) {
                mode = Mode.TO_CAMP;
                return true;
            }
            if ((zone != null && collection.hasTrackedDrops())
                || collection.ownsOffhandItem()) {
                mode = Mode.TO_COLLECTION_SITE;
                return true;
            }
            clearCollectionSite();
            containerPos = null;
        }

        // Existing cargo is a recovery/haul, not permission to start another
        // tree. It remains deliverable outside ordinary work hours.
        if (bagCount() > 0) {
            mode = Mode.TO_CAMP;
            return true;
        }
        // A capacity round-trip can be interrupted after the sack was lifted
        // and emptied at camp. Resume at the exact old site, not a new tree.
        if (zone != null && collection.hasTrackedDrops() && containerPos != null) {
            persistCollectionSite(level, containerPos);
            mode = Mode.TO_COLLECTION_SITE;
            return true;
        }
        if (!workConditions() || !settler.getOffhandItem().isEmpty()) {
            return false;
        }
        if (zone == null) {
            publishWorkZoneStop(StopReason.NO_WORK_ZONE,
                employer == null ? null : employer.plaquePos, "ai_idle_no_zone", null);
            return false;
        }
        clearWorkZoneStop();
        if (equipmentRetryCooldown > 0) {
            equipmentRetryCooldown--;
            return false;
        }
        if (!EquipmentRequests.equipFromWorkplace(level, s, settler)) {
            equipmentRetryCooldown = 20;
            return false;
        }
        if (scanCooldown > 0) {
            scanCooldown--;
            return false;
        }
        long now = settler.level().getGameTime();
        List<BlockPos> bases = scanner.scanBoxColumns(zone.min(), zone.max(),
            zone.min().getY(),
            TREE_SCAN_BUDGET, TREE_SCAN_RESULTS, column -> {
                BlockPos base = trunkInColumn(column, zone);
                return base == null || !zone.contains(base)
                    || heldByOther(level, base, now) ? null : base;
            });
        scanCooldown = bases.isEmpty()
            ? TREE_SCAN_COOLDOWN_MIN
                + settler.getRandom().nextInt(TREE_SCAN_COOLDOWN_JITTER)
            // A batch containing only malformed/contested candidates should
            // advance promptly rather than looking idle for another second.
            : 4;
        // A base already claimed by another lumberer is skipped before it is
        // even validated (cheaper, and it is the whole point: two
        // lumberjacks converging on the identical trunk -- the filmed
        // finding this exists to fix -- is a base-selection bug, not a
        // pathing one). If every candidate this scan turns up is claimed,
        // the loop simply runs dry and this returns false below: the second
        // lumberer idles at the camp like any other lumberer with no tree to
        // fell, rather than shadowing the first onto her own trunk.
        for (BlockPos base : bases) {
            BlockPos immutableBase = base.immutable();
            // Re-check after the scan because another lumberer can claim a
            // candidate earlier in this same server tick.
            if (heldByOther(level, immutableBase, now)) continue;
            List<BlockPos> logs = validateTree(base, zone);
            if (!logs.isEmpty()) {
                if (!claim(level, immutableBase, now)) {
                    continue; // lost a same-tick race to another lumberer's claim
                }
                treeBase = immutableBase;
                treeLogs = logs;
                if (!WorkZoneService.livePositionAllowed(level, zone, base)) {
                    releaseClaim(immutableBase);
                    treeBase = null;
                    treeLogs = List.of();
                    continue;
                }
                treeLogBlock = level.getBlockState(base).getBlock();
                UUID action = WorkerProvenanceService.beginLumberTree(level,
                    s, employer, settler, zone, immutableBase, logs);
                if (action == null) {
                    releaseClaim(immutableBase);
                    treeBase = null;
                    treeLogs = List.of();
                    continue;
                }
                workActionId = action;
                mode = Mode.TO_TREE;
                resetPathBudget();
                clearWorkZoneStop();
                return true;
            }
        }
        publishWorkZoneStop(StopReason.NO_VALID_TARGET, employer.plaquePos,
            "ai_no_valid_target", zone);
        return false;
    }

    @Nullable
    private WorkZone authoritativeZone(ServerLevel level, Settlement settlement,
                                       @Nullable Building employer) {
        if (level == null || settlement == null || employer == null
            || !employer.valid || employer.type != BuildingType.LUMBER_CAMP
            || employer.workZoneQuarantined()
            || !employer.workers.contains(settler.getUUID())) {
            return null;
        }
        WorkZone zone = employer.workZone().orElse(null);
        return zone != null && zone.type() == WorkZone.Type.LUMBER
            && zone.settlementId().equals(settlement.id)
            && zone.buildingId().equals(employer.id)
            && zone.dimension().equals(level.dimension().location())
            ? zone : null;
    }

    @Nullable
    private WorkZone currentCollectionZone(ServerLevel level) {
        Settlement settlement = settler.settlement();
        Building employer = settlement == null ? null
            : Employment.employerOf(settlement, settler.getUUID());
        return authoritativeZone(level, settlement, employer);
    }

    /** Shared by runtime contact gates and adversarial GameTests. */
    public static boolean collectionTargetAllowed(ServerLevel level,
                                                  @Nullable WorkZone zone,
                                                  @Nullable BlockPos target) {
        return WorkZoneService.livePositionAllowed(level, zone, target);
    }

    private static AABB recoveryBounds(WorkZone zone, BlockPos site) {
        AABB exactZone = new AABB(zone.min().getX(), zone.min().getY(),
            zone.min().getZ(), zone.max().getX() + 1.0D,
            zone.max().getY() + 1.0D, zone.max().getZ() + 1.0D);
        return new AABB(site).inflate(RECOVERY_HORIZONTAL, RECOVERY_VERTICAL,
            RECOVERY_HORIZONTAL).intersect(exactZone);
    }

    private void recoverOwnedInsideZone(ServerLevel level, WorkZone zone,
                                        BlockPos site) {
        collection.recoverOwned(level, recoveryBounds(zone, site),
            pos -> zone.dimension().equals(level.dimension().location())
                && zone.contains(pos),
            pos -> collectionTargetAllowed(level, zone, pos));
    }

    private void publishWorkZoneStop(StopReason reason, @Nullable BlockPos target,
                                     String event, @Nullable WorkZone zone) {
        settler.setLogisticsStop(reason, target, 0);
        if (workZoneState == reason) {
            return;
        }
        workZoneState = reason;
        Hearthstead.LOGGER.info(
            "HSQA_WORK_ZONE event={} outcome={} settler={} settlement={} building={} revision={} target={} thread={}",
            event, reason.name(), settler.getUUID(), settler.getSettlementId(),
            zone == null ? null : zone.buildingId(),
            zone == null ? -1 : zone.revision(), target,
            Thread.currentThread().getName());
    }

    private void clearWorkZoneStop() {
        if (workZoneState == StopReason.NO_WORK_ZONE
            || workZoneState == StopReason.NO_VALID_TARGET) {
            settler.clearLogisticsStop();
            workZoneState = StopReason.NONE;
        }
    }


    /**
     * Looks for the foot of a trunk in one column of the settlement.
     *
     * <p>Reads the surface once, but the surface is only ever a starting
     * point, never a verdict: {@code MOTION_BLOCKING_NO_LEAVES} reports
     * whatever is physically highest in the column, and that is honestly
     * often not the trunk -- a GameTest arena's barrier roof, a real
     * overhang, a player-built platform, or a snow layer over the canopy
     * all sit above a tree without being it (KF-022: the arena case KF-018
     * never covered). So this first descends past whatever is NOT a log,
     * looking for the first one, before it does the real work of following
     * the trunk down to its base. Both phases share the same
     * {@link #TRUNK_DESCENT} budget: a tree under cover is exactly the same
     * shape as one in the open, and the cap is what keeps a decorative
     * column (or a false roof with nothing under it) from being walked all
     * the way to bedrock.
     */
    @Nullable
    private BlockPos trunkInColumn(BlockPos column, WorkZone zone) {
        if (!(settler.level() instanceof ServerLevel level) || zone == null
            || !WorkZoneService.livePositionAllowed(level, zone,
                new BlockPos(column.getX(), zone.min().getY(), column.getZ()))) {
            return null;
        }
        // The confirmed Y bounds are authoritative too. A heightmap lookup
        // would inspect the whole column outside that volume, so descend only
        // from zone.maxY to zone.minY (already capped at 64 blocks).
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(
            column.getX(), zone.max().getY(), column.getZ());
        for (int step = 0; step < Math.min(TRUNK_DESCENT, zone.sizeY()); step++) {
            if (!WorkZoneService.livePositionAllowed(level, zone, cursor)) {
                return null;
            }
            if (!level.getBlockState(cursor).is(BlockTags.LOGS_THAT_BURN)) {
                cursor.move(Direction.DOWN);
                continue;
            }
            BlockPos below = cursor.below();
            if (!WorkZoneService.livePositionAllowed(level, zone, below)) {
                return null;
            }
            if (!level.getBlockState(below).is(BlockTags.LOGS_THAT_BURN)) {
                return isTreeBase(cursor, zone) ? cursor.immutable() : null;
            }
            cursor.move(Direction.DOWN);
        }
        return null;
    }

    private static final int TRUNK_DESCENT = 32;

    private boolean isTreeBase(BlockPos pos, WorkZone zone) {
        if (!(settler.level() instanceof ServerLevel level)
            || !WorkZoneService.livePositionAllowed(level, zone, pos)
            || !WorkZoneService.livePositionAllowed(level, zone, pos.below())) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.is(BlockTags.LOGS_THAT_BURN)
            && level.getBlockState(pos.below()).is(BlockTags.DIRT);
    }

    /**
     * Natural-tree heuristic: a connected log cluster (capped size and
     * horizontal drift) wearing at least a few non-persistent leaves. Player
     * builds (cabins, pillars) fail this and are left alone.
     */
    private List<BlockPos> validateTree(BlockPos base, WorkZone zone) {
        if (!(settler.level() instanceof ServerLevel level)
            || !WorkZoneService.livePositionAllowed(level, zone, base)) {
            return List.of();
        }
        Set<BlockPos> logs = new HashSet<>();
        Set<BlockPos> leaves = new HashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(base);
        logs.add(base);
        while (!frontier.isEmpty()) {
            BlockPos current = frontier.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = current.offset(dx, dy, dz);
                        if (!zone.contains(next)) {
                            continue;
                        }
                        if (!WorkZoneService.livePositionAllowed(level, zone, next)) {
                            return List.of();
                        }
                        if (logs.contains(next)) {
                            continue;
                        }
                        if (Math.abs(next.getX() - base.getX()) > MAX_DRIFT
                            || Math.abs(next.getZ() - base.getZ()) > MAX_DRIFT
                            || next.getY() < base.getY()) {
                            continue;
                        }
                        BlockState state = level.getBlockState(next);
                        if (state.is(BlockTags.LOGS_THAT_BURN)) {
                            if (logs.size() >= MAX_TREE_LOGS) {
                                return List.of(); // suspiciously large; skip
                            }
                            logs.add(next);
                            frontier.add(next);
                        } else if (state.getBlock() instanceof LeavesBlock
                            && !state.getValue(LeavesBlock.PERSISTENT)) {
                            leaves.add(next);
                        }
                    }
                }
            }
        }
        if (leaves.size() < MIN_LEAVES) {
            return List.of();
        }
        List<BlockPos> sorted = new ArrayList<>(logs);
        sorted.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed());
        return sorted;
    }

    private int bagCount() {
        return collection.load();
    }

    @Override
    public void start() {
        done = false;
        switch (mode) {
            case TO_CAMP -> {
                settler.setActivity(SettlerActivity.HAULING_LOG);
                if (!pathToCamp() && !done
                    && ++campStuckChecks > MAX_PATH_FAILURES
                    && settler.level() instanceof ServerLevel level) {
                    pauseRecoveryRoute(level, "lumber_camp_unreachable");
                }
            }
            case RETURNING_TO_CONTAINER -> {
                settler.setActivity(SettlerActivity.COLLECTING_ITEMS);
                if (!pathToContainer() && !done
                    && ++stuckChecks > MAX_PATH_FAILURES
                    && settler.level() instanceof ServerLevel level) {
                    pauseRecoveryRoute(level, "work_container_unreachable");
                }
            }
            case TO_COLLECTION_SITE -> {
                settler.setActivity(SettlerActivity.COLLECTING_ITEMS);
                if (!pathToCollectionSite() && !done
                    && ++stuckChecks > MAX_PATH_FAILURES
                    && settler.level() instanceof ServerLevel level) {
                    pauseRecoveryRoute(level, "collection_site_unreachable");
                }
            }
            default -> {
                chopTicks = 0;
                chopRecoveryTicks = 0;
                settler.setActivity(SettlerActivity.TRAVELING);
                pathToTree();
            }
        }
    }

    private void pathToTree() {
        if (treeBase != null) {
            // Two lumberers can no longer land on the SAME tree (the claim
            // table above), but a pair of trees a stride apart could still
            // funnel both toward the identical side of their own trunk and
            // brush past each other on the last step. Biasing the walk-in
            // target toward one of four cardinal sides -- stable per settler
            // via her own UUID, no shared state needed -- spreads that out
            // for the cost of one extra block on an already-approximate
            // travel hint; tickTravel's own generous 7.5-block-squared
            // arrival radius (and the fact that the trunk itself is solid,
            // so the pathfinder was always resolving to an adjacent node
            // anyway) means this never changes which tree gets chopped or
            // how close the settler ends up standing to it.
            BlockPos approach = treeBase.relative(standSide());
            settler.getNavigation().moveTo(approach.getX() + 0.5, treeBase.getY(),
                approach.getZ() + 0.5, 1.0);
        }
    }

    /** See {@link #pathToTree}. */
    private Direction standSide() {
        return Direction.from2DDataValue((int) (settler.getUUID().getLeastSignificantBits() & 3));
    }

    private boolean pathToCamp() {
        if (!(settler.level() instanceof ServerLevel level)) {
            done = true;
            return false;
        }
        Settlement settlement = settler.settlement();
        Building camp = settlement == null ? null
            : Employment.employerOf(settlement, settler.getUUID());
        if (camp == null) {
            pauseRecoveryRoute(level, "lumber_camp_missing");
            return false;
        }
        // One haul owns one exact destination until it completes or that
        // physical container becomes invalid. Re-selecting on every retry is
        // what made a worker bounce between sealed sides of a doorway.
        if (depositTarget == null) {
            depositTarget = WorkerStorageAuthority.nearestLoadedContainer(level,
                camp, settler.blockPosition());
        }
        if (depositTarget == null) {
            pauseRecoveryRoute(level, "lumber_camp_storage_missing");
            return false;
        }
        prepareCampBudget(depositTarget);
        ContainerApproach.Result approach = ContainerApproach.moveToContact(
            level, settler, depositTarget, 1.0D);
        if (approach.state() == ContainerApproach.State.INVALID_TARGET) {
            depositTarget = null;
        }
        repathTimer = CAMP_REPATH_TICKS;
        return approach.canInteract() || approach.startedPath();
    }

    private void prepareCampBudget(BlockPos target) {
        if (!Objects.equals(campBudgetTarget, target)) {
            campBudgetTarget = target.immutable();
            bestCampDistanceSqr = settler.blockPosition().distSqr(target);
            campStuckChecks = 0;
        }
    }

    private void resetCampBudget() {
        campBudgetTarget = null;
        bestCampDistanceSqr = Double.MAX_VALUE;
        campStuckChecks = 0;
    }

    @Override
    public boolean canContinueToUse() {
        if (done || settler.getProfession() != Profession.LUMBERER
            || !settler.isBound()) {
            return false;
        }
        return switch (mode) {
            // Once physical contact animation begins it owns its complete
            // transaction window. A day-phase edge cannot strand an item in
            // a half-transferred state.
            // Once a tree has produced physical drops, finishing that exact
            // ownership transaction is recovery, not permission to begin new
            // work. Shift, energy and daily-effort gates may stop the NEXT
            // tree, but may never strand the last log of this one.
            case LIMBING, CONTAINER_DOWN, SELECTING_ITEM, TO_ITEM, PICKING_ITEM,
                 RETURNING_TO_CONTAINER, STOWING_ITEM, CONTAINER_UP,
                 TO_CAMP, TO_COLLECTION_SITE -> true;
            default -> workConditions();
        };
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        // A claim is a heartbeat, not a one-shot timer, exactly like
        // CourierWorkGoal's restock RESERVATIONS: as long as this goal is
        // actively ticking with a tree in hand, the lock cannot expire out
        // from under her. A no-op once the tree is felled and the claim
        // already released (treeBase == null; see finishTree/renewClaim).
        if (settler.level() instanceof ServerLevel level) {
            renewClaim(level);
            collection.heartbeat(level);
        }
        switch (mode) {
            case TO_TREE -> tickTravel();
            case CHOPPING -> tickChop();
            case LIMBING -> tickLimb();
            case CONTAINER_DOWN -> tickContainerDown();
            case SELECTING_ITEM -> tickSelectingItem();
            case TO_ITEM -> tickToItem();
            case PICKING_ITEM -> tickPickingItem();
            case RETURNING_TO_CONTAINER -> tickReturningToContainer();
            case STOWING_ITEM -> tickStowingItem();
            case CONTAINER_UP -> tickContainerUp();
            case TO_CAMP -> tickDeposit();
            case TO_COLLECTION_SITE -> tickToCollectionSite();
        }
    }

    private void tickTravel() {
        if (treeBase == null) {
            done = true;
            return;
        }
        if (settler.level() instanceof ServerLevel level
            && !zoneAllows(level, treeBase, "travel")) {
            abortTreeOutsideZone(level, treeBase, "travel");
            return;
        }
        settler.getLookControl().setLookAt(treeBase.getX() + 0.5, treeBase.getY() + 1.0,
            treeBase.getZ() + 0.5);
        if (settler.blockPosition().distSqr(treeBase) <= 7.5) {
            mode = Mode.CHOPPING;
            chopTicks = 0;
            chopRecoveryTicks = 0;
            settler.getNavigation().stop();
            settler.setActivity(SettlerActivity.WORK_CHOP);
        } else if (--repathTimer <= 0) {
            repathTimer = 40;
            if (++stuckChecks > 6) {
                // Unreachable tree; rescan later. Recorded rather than
                // endured (SettlerEntity#recordRouteFailure's whole reason
                // to exist, per GoToPostGoal's own use of it): a lumberer
                // who silently gives up on a tree it cannot path to reads
                // identically to one that never found a tree at all, and
                // KF-020 spent real investigation time unable to tell those
                // two apart from the outside.
                settler.recordRouteFailure("tree_unreachable");
                done = true;
            } else {
                pathToTree();
            }
        }
    }

    private void tickChop() {
        if (treeLogs.isEmpty()) {
            startLimbing();
            return;
        }
        if (chopRecoveryTicks > 0) {
            chopRecoveryTicks--;
            settler.setActivity(SettlerActivity.IDLE);
            if (chopRecoveryTicks == 0) {
                settler.setActivity(SettlerActivity.WORK_CHOP);
            }
            return;
        }
        BlockPos topLog = treeLogs.get(0);
        if (settler.level() instanceof ServerLevel level
            && (!zoneAllows(level, treeBase, "chop_base")
                || !zoneAllows(level, topLog, "chop_log"))) {
            abortTreeOutsideZone(level, topLog, "chop");
            return;
        }
        settler.getLookControl().setLookAt(treeBase.getX() + 0.5,
            settler.getEyeY(), treeBase.getZ() + 0.5);
        chopTicks++;
        // The strike lands at the animation's impact frame (1s loop).
        if (chopTicks % CHOP_CYCLE_TICKS == CHOP_CONTACT_TICK
            && settler.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, treeBase, ModSounds.CHOP.get(),
                SoundSource.NEUTRAL, 0.9F, 0.9F + settler.getRandom().nextFloat() * 0.2F);
        }
        int requiredTicks = chopTicksForStrength(
            settler.attribute(Attribute.STRENGTH));
        if (chopTicks >= requiredTicks) {
            chopTicks = 0;
            if (settler.level() instanceof ServerLevel serverLevel) {
                BlockState logState = serverLevel.getBlockState(topLog);
                if (logState.is(BlockTags.LOGS_THAT_BURN)) {
                    Settlement settlement = settler.settlement();
                    Building camp = settlement == null ? null
                        : Employment.employerOf(settlement, settler.getUUID());
                    if (workActionId == null || settlement == null || camp == null
                        || !WorkerProvenanceService.prepareLumberLog(serverLevel,
                            settlement, camp, settler, workActionId, topLog)) {
                        pauseRecoveryRoute(serverLevel,
                            "lumber_action_tool_or_authority_blocked");
                        return;
                    }
                    ItemStack physicalLog = new ItemStack(
                        logState.getBlock().asItem());
                    if (!WorkerProvenanceService.stampOutput(serverLevel,
                            settlement, camp, settler, workActionId,
                            physicalLog, topLog)) {
                        pauseRecoveryRoute(serverLevel,
                            "lumber_action_output_prepare_failed");
                        return;
                    }
                    // Queue the exact stamped output before clearing the log
                    // block. If the bounded physical-item escrow refuses it,
                    // the tree remains authoritative and this action retries;
                    // no unchecked fallback may erase a paid unit of work.
                    UUID physicalLogId = collection.queuePhysical(serverLevel,
                        topLog, physicalLog);
                    if (physicalLogId == null) {
                        pauseRecoveryRoute(serverLevel,
                            "lumber_action_output_escrow_blocked");
                        return;
                    }
                    if (!serverLevel.destroyBlock(topLog, false)) {
                        collection.cancelQueued(serverLevel, physicalLogId);
                        pauseRecoveryRoute(serverLevel,
                            "lumber_action_output_prepare_failed");
                        return;
                    }
                    // Point 8 of the job standard: one felled log is one unit
                    // of work, and it is counted HERE -- at the moment the log
                    // comes down, not on a timer while the settler stands
                    // near a tree. Learning by doing is only true if doing is
                    // what gets counted.
                    settler.train(com.hearthstead.entity.Attribute.STRENGTH, 1.0F);
                    // Immediate insertion is best-effort. A false result means
                    // the SavedData row still owns the only copy and will retry
                    // with this stable UUID; it is not permission to restore,
                    // duplicate or silently complete an unowned fallback.
                    collection.materializeQueued(serverLevel, topLog,
                        physicalLogId);
                    if (!WorkerProvenanceService.completeLumberLog(serverLevel,
                            settlement, camp, settler, workActionId, topLog,
                            physicalLog)) {
                        pauseRecoveryRoute(serverLevel,
                            "lumber_action_receipt_failed");
                        return;
                    }
                } else {
                    pauseRecoveryRoute(serverLevel,
                        "lumber_action_target_changed");
                    return;
                }
            }
            treeLogs = treeLogs.subList(1, treeLogs.size());
            if (treeLogs.isEmpty()) {
                startLimbing();
            } else {
                // Low energy stretches the pause between physical log actions.
                // It never invalidates an otherwise valid target or ends the day.
                chopRecoveryTicks = fatiguePauseTicks(settler.getEnergy(),
                    settler.attribute(Attribute.STAMINA));
            }
        }
    }

    /** Pure contract seam: exact commit tick for the current Strength band. */
    public static int chopTicksForStrength(int strength) {
        int contacts = JobEffects.lumberContacts(strength);
        return (contacts - 1) * CHOP_CYCLE_TICKS + CHOP_CONTACT_TICK;
    }

    /** Pure contract seam: bounded inter-log fatigue pause, never a hard stop. */
    public static int fatiguePauseTicks(double energy, int stamina) {
        double pace = JobEffects.workPace(energy, stamina);
        return Math.max(0,
            (int) Math.ceil(CHOP_CYCLE_TICKS / pace) - CHOP_CYCLE_TICKS);
    }

    /** Trimming the felled trunk: a short beat between the last strike and
     *  the sapling/hearth wrap-up, per docs/ANIMATION_CATALOGUE.md §3.2 --
     *  the goal already implicitly "limbs" the tree by only chopping the log
     *  column, it just had no clip for that step. */
    private void tickLimb() {
        limbTicks++;
        if (settler.level() instanceof ServerLevel serverLevel) {
            if (limbTicks % LIMB_DURATION == 6 || limbTicks % LIMB_DURATION == 19) {
                // Reuses CHOP's synthesis at higher pitch/shorter tail per
                // the catalogue's own suggestion -- no new sound asset.
                serverLevel.playSound(null, treeBase, ModSounds.CHOP.get(),
                    SoundSource.NEUTRAL, 0.6F, 1.35F + settler.getRandom().nextFloat() * 0.1F);
            }
        }
        if (limbTicks >= LIMB_DURATION) {
            limbTicks = 0;
            finishTree();
        }
    }

    private void startLimbing() {
        mode = Mode.LIMBING;
        limbTicks = 0;
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.WORK_LIMB);
    }

    private void finishTree() {
        if (!(settler.level() instanceof ServerLevel serverLevel)
            || treeBase == null) {
            done = true;
            return;
        }
        Settlement settlement = settler.settlement();
        Building camp = settlement == null ? null
            : Employment.employerOf(settlement, settler.getUUID());
        if (settlement == null || camp == null || workActionId == null
            || !zoneAllows(serverLevel, treeBase, "tree_terminal")) {
            pauseRecoveryRoute(serverLevel, "lumber_action_terminal_blocked");
            return;
        }
        {
            Block sapling = SAPLING_FOR_LOG.get(treeLogBlock);
            if (sapling != null
                && zoneAllows(serverLevel, treeBase, "replant")
                && zoneAllows(serverLevel, treeBase.below(), "replant_support")
                && serverLevel.getBlockState(treeBase).isAir()
                && serverLevel.getBlockState(treeBase.below()).is(BlockTags.DIRT)) {
                serverLevel.setBlock(treeBase, sapling.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        if (!WorkerProvenanceService.commitLumberTree(serverLevel, settlement,
                camp, settler, workActionId)) {
            pauseRecoveryRoute(serverLevel, "lumber_action_terminal_receipt_failed");
            return;
        }
        workActionId = null;
        // Released the moment the tree is genuinely gone -- the base no
        // longer holds a log a second lumberer's scan could even match, so
        // holding the claim any longer (through the whole haul-home leg)
        // would only waste a row in the table for nothing.
        releaseClaim(treeBase);
        treeBase = null;
        if (collection.hasTrackedDrops()) {
            startContainerDown(chooseContainerAnchor());
        } else {
            settler.setActivity(SettlerActivity.IDLE);
            done = true;
        }
    }

    private boolean zoneAllows(ServerLevel level, BlockPos pos,
                               String operation) {
        Settlement settlement = settler.settlement();
        Building employer = settlement == null ? null
            : Employment.employerOf(settlement, settler.getUUID());
        WorkZone zone = authoritativeZone(level, settlement, employer);
        if (zone == null) {
            publishWorkZoneStop(StopReason.NO_WORK_ZONE,
                employer == null ? null : employer.plaquePos,
                "ai_idle_no_zone", null);
            return false;
        }
        if (!WorkZoneService.livePositionAllowed(level, zone, pos)) {
            publishWorkZoneStop(StopReason.NO_VALID_TARGET,
                employer.plaquePos, "ai_outside_zone_rejected", zone);
            return false;
        }
        clearWorkZoneStop();
        return true;
    }

    private void abortTreeOutsideZone(ServerLevel level, BlockPos rejected,
                                      String operation) {
        Hearthstead.LOGGER.info(
            "HSQA_WORK_ZONE event=ai_outside_zone_no_mutation operation={} settler={} rejected={} thread={}",
            operation, settler.getUUID(), rejected,
            Thread.currentThread().getName());
        settler.getNavigation().stop();
        releaseClaim(treeBase);
        treeBase = null;
        treeLogs = List.of();
        done = true;
    }

    // ----------------------------------------- physical ground collection ---

    /**
     * The sack stands beside the worker, never inside their feet. Candidate
     * order is stable and readable: facing, left, right, back. A position
     * must be loaded, non-colliding/replaceable at sack height, and have a
     * sturdy top face under it. The current cell is a last-resort safety
     * fallback only; it is diagnosed so a bad work site never fails silently.
     */
    private BlockPos chooseContainerAnchor() {
        if (!(settler.level() instanceof ServerLevel level)) {
            return settler.blockPosition();
        }
        WorkZone zone = currentCollectionZone(level);
        Direction facing = settler.getDirection();
        Direction[] order = {
            facing,
            facing.getCounterClockWise(),
            facing.getClockWise(),
            facing.getOpposite()
        };
        BlockPos origin = settler.blockPosition();
        for (Direction direction : order) {
            BlockPos candidate = origin.relative(direction);
            BlockPos support = candidate.below();
            if (!collectionTargetAllowed(level, zone, candidate)
                || !collectionTargetAllowed(level, zone, support)) {
                continue;
            }
            BlockState at = level.getBlockState(candidate);
            boolean clear = at.isAir() || at.canBeReplaced()
                || at.getCollisionShape(level, candidate).isEmpty();
            if (clear && level.getBlockState(support)
                .isFaceSturdy(level, support, Direction.UP)) {
                return candidate.immutable();
            }
        }
        settler.recordRouteFailure("work_container_anchor_fallback");
        return collectionTargetAllowed(level, zone, origin)
            ? origin.immutable()
            : treeBase == null ? origin.immutable() : treeBase.immutable();
    }

    private void startContainerDown(BlockPos fixedPosition) {
        if (settler.level() instanceof ServerLevel level
            && !collectionTargetAllowed(level, currentCollectionZone(level),
                fixedPosition)) {
            settler.recordRouteFailure("work_container_outside_zone");
            done = true;
            return;
        }
        containerPos = fixedPosition.immutable();
        if (settler.level() instanceof ServerLevel level) {
            persistCollectionSite(level, containerPos);
        }
        mode = Mode.CONTAINER_DOWN;
        phaseTicks = 0;
        contactTransferred = false;
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.GATHERING_LOG);
        lookAtContainer();
        // Projection becomes authoritative at animation start: the client
        // animates the detach, then world-locks the same fixed coordinate.
        settler.placeWorkContainer(WorkContainerKind.SACK, containerPos);
        settler.triggerWorkContainerDown();
    }

    private void tickContainerDown() {
        lookAtContainer();
        phaseTicks++;
        if (phaseTicks == CONTAINER_DOWN_CONTACT_TICK
            && settler.level() instanceof ServerLevel level) {
            level.playSound(null,
                containerPos == null ? settler.blockPosition() : containerPos,
                ModSounds.BAG_DOWN.get(), SoundSource.NEUTRAL, 0.70F,
                0.96F + settler.getRandom().nextFloat() * 0.08F);
        }
        if (phaseTicks >= CONTAINER_DOWN_DURATION) {
            mode = Mode.SELECTING_ITEM;
            phaseTicks = 0;
            unresolvedDropTicks = 0;
        }
    }

    private void tickSelectingItem() {
        if (!(settler.level() instanceof ServerLevel level)) {
            done = true;
            return;
        }
        if (collection.ownsOffhandItem()) {
            startReturningToContainer();
            return;
        }
        if (!collection.hasCapacity()) {
            startContainerUp();
            return;
        }
        WorkZone zone = currentCollectionZone(level);
        if (zone == null) {
            done = true;
            return;
        }
        ItemEntity next = null;
        int unsafeRows = collection.trackedCount();
        while (unsafeRows-- > 0) {
            next = collection.nearestLoaded(level,
                containerPos == null ? settler.blockPosition() : containerPos);
            if (next == null || collectionTargetAllowed(level, zone,
                    next.blockPosition())) {
                break;
            }
            // A persisted/moved entity outside the current exact claim is a
            // real world item, but no longer this worker's authority.
            collection.select(next);
            collection.releaseSelected(level);
            next = null;
        }
        if (next != null) {
            unresolvedDropTicks = 0;
            collection.select(next);
            mode = Mode.TO_ITEM;
            settler.setActivity(SettlerActivity.COLLECTING_ITEMS);
            resetPathBudget();
            selectedItemApproach = null;
            bestItemDistanceSqr = Double.MAX_VALUE;
            pickupRouteAuthorized = false;
            boolean pathStarted = pathToSelectedItem(level);
            traceItemRoute("select", next, selectedItemApproach, pathStarted);
            return;
        }
        if (!collection.hasTrackedDrops()) {
            startContainerUp();
        } else if (++unresolvedDropTicks >= UNLOADED_DROP_WAIT_TICKS) {
            // Their chunks are gone, not their items. Yield MOVE/LOOK and
            // retain the persisted UUID/site intent instead of converting a
            // temporary unload into a permanently forgotten fourth log.
            pauseRecoveryRoute(level, "felled_item_unloaded");
        }
    }

    private boolean pathToSelectedItem(ServerLevel level) {
        ItemEntity target = collection.selected(level);
        WorkZone zone = currentCollectionZone(level);
        if (target != null && collectionTargetAllowed(level, zone,
                target.blockPosition())) {
            BlockPos previousApproach = selectedItemApproach;
            Set<BlockPos> approaches = standableItemApproaches(level, target);
            BlockPos current = settler.blockPosition();
            boolean started = false;
            if (approaches.contains(current)
                && collection.hasClearPickupLine(level, target)
                && settler.distanceToSqr(target) <= ITEM_CONTACT_DISTANCE_SQR) {
                selectedItemApproach = current.immutable();
                settler.getNavigation().stop();
                started = true;
            } else if (!approaches.isEmpty()) {
                // One bounded multi-target search chooses the nearest node it
                // can reach exactly. The old Euclidean-only choice could keep
                // aiming at a sealed side of a stump and eventually abandon a
                // log even though another side was open.
                Path path = settler.getNavigation().createPath(approaches, 0);
                if (path != null && path.canReach()
                    && approaches.contains(path.getTarget())) {
                    selectedItemApproach = path.getTarget().immutable();
                    started = settler.getNavigation().moveTo(path, 1.0);
                } else {
                    selectedItemApproach = null;
                }
            }
            if (!Objects.equals(previousApproach, selectedItemApproach)) {
                // A moving ItemEntity can legitimately move the exact feet
                // target. Distances to two different world nodes are not one
                // progress series; rebase instead of spending the old node's
                // stuck budget against the new one.
                bestItemDistanceSqr = selectedItemApproach == null
                    ? Double.MAX_VALUE
                    : distanceToApproachSqr(selectedItemApproach);
                stuckChecks = 0;
            } else if (bestItemDistanceSqr == Double.MAX_VALUE
                && selectedItemApproach != null) {
                bestItemDistanceSqr = distanceToApproachSqr(selectedItemApproach);
            }
            traceItemRoute("path", target, selectedItemApproach, started);
            repathTimer = ARRIVAL_REPATH_TICKS;
            return started;
        }
        return false;
    }

    /**
     * Item entities often rest inside a sapling's collision or against the
     * stump they came from. Asking ground navigation to occupy that exact
     * entity coordinate is therefore nondeterministic: the item is reachable
     * by hand, but the destination node itself may be rejected. Walk to the
     * nearest real standing cell at the same or adjacent Y instead. The scan
     * is a fixed fifteen candidates (centre plus four sides across three
     * levels), so this cannot become an unbounded pathfinding hot path.
     */
    private Set<BlockPos> standableItemApproaches(ServerLevel level,
                                                  ItemEntity item) {
        Set<BlockPos> candidates = new LinkedHashSet<>();
        BlockPos itemBlock = item.blockPosition();
        for (int dy = -1; dy <= 1; dy++) {
            BlockPos centre = itemBlock.offset(0, dy, 0);
            addItemApproach(level, item, centre, candidates);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                addItemApproach(level, item, centre.relative(direction), candidates);
            }
        }
        return candidates;
    }

    private void addItemApproach(ServerLevel level, ItemEntity item,
                                 BlockPos candidate, Set<BlockPos> candidates) {
        if (!isStandable(level, candidate)) {
            return;
        }
        Vec3 feet = new Vec3(candidate.getX() + 0.5, candidate.getY(),
            candidate.getZ() + 0.5);
        if (feet.distanceToSqr(item.position()) > ITEM_CONTACT_DISTANCE_SQR) {
            return;
        }
        Vec3 eye = feet.add(0.0, settler.getEyeHeight(), 0.0);
        if (collection.hasClearPickupLineFrom(level, eye, item)) {
            candidates.add(candidate.immutable());
        }
    }

    private static boolean isStandable(ServerLevel level, BlockPos pos) {
        return WorkZoneService.livePositionAvailable(level, pos)
            && WorkZoneService.livePositionAvailable(level, pos.above())
            && WorkZoneService.livePositionAvailable(level, pos.below())
            && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
            && level.getBlockState(pos.above())
                .getCollisionShape(level, pos.above()).isEmpty()
            && level.getBlockState(pos.below())
                .isFaceSturdy(level, pos.below(), Direction.UP);
    }

    private void tickToItem() {
        if (!(settler.level() instanceof ServerLevel level)) {
            done = true;
            return;
        }
        ItemEntity target = collection.selected(level);
        if (target == null) {
            selectedItemApproach = null;
            if (collection.selectedDropId() == null) {
                mode = Mode.SELECTING_ITEM;
            } else if (++unresolvedDropTicks >= UNLOADED_DROP_WAIT_TICKS) {
                collection.clearSelection();
                pauseRecoveryRoute(level, "felled_item_unloaded");
            }
            return;
        }
        WorkZone zone = currentCollectionZone(level);
        if (!collectionTargetAllowed(level, zone, target.blockPosition())) {
            traceItemRoute("release_outside_zone", target, null, false);
            collection.releaseSelected(level);
            selectedItemApproach = null;
            mode = Mode.SELECTING_ITEM;
            return;
        }
        unresolvedDropTicks = 0;
        settler.getLookControl().setLookAt(target, 30.0F, 30.0F);
        // Euclidean reach alone is not authority: it can be short through a
        // wall or across a rejected partial path. selectedItemApproach was
        // already chosen from an exact canReach path to a standable, visible
        // node. Ground navigation may finish within its ordinary terminal
        // tolerance without the entity origin flooring to that exact block,
        // especially while standing one block higher on the old stump. Accept
        // a tight 1.5-block arrival around that authorised node instead of
        // abandoning the final physical log after successful-but-not-bit-
        // identical repaths.
        boolean atApproach = selectedItemApproach != null
            && distanceToApproachSqr(selectedItemApproach)
                <= ITEM_APPROACH_ARRIVAL_DISTANCE_SQR;
        if (atApproach && collection.hasClearPickupLine(level, target)
            && settler.distanceToSqr(target) <= ITEM_CONTACT_DISTANCE_SQR) {
            startPickingItem();
        } else if (--repathTimer <= 0) {
            double distance = selectedItemApproach == null ? Double.MAX_VALUE
                : distanceToApproachSqr(selectedItemApproach);
            if (distance + 0.25 < bestItemDistanceSqr) {
                // Count lack of progress, not elapsed repaths. A moving item
                // or a worker making a long but valid route must not be
                // released merely because 8 intervals passed.
                bestItemDistanceSqr = distance;
                stuckChecks = 0;
            } else {
                stuckChecks++;
            }
            if (stuckChecks > MAX_PATH_FAILURES) {
                traceItemRoute("release_unreachable", target, null, false);
                settler.recordRouteFailure("felled_item_unreachable");
                collection.releaseSelected(level);
                selectedItemApproach = null;
                mode = Mode.SELECTING_ITEM;
            } else {
                pathToSelectedItem(level);
            }
        }
    }

    private void traceItemRoute(String event, ItemEntity target,
                                @Nullable BlockPos approach, boolean pathStarted) {
        if (!QaTrace.ENABLED) {
            return;
        }
        QaTrace.event(settler, "lumber_item_" + event,
            "target=" + target.blockPosition()
                + ";targetPos=" + target.position()
                + ";targetVelocity=" + target.getDeltaMovement()
                + ";targetOnGround=" + target.onGround()
                + ";approach=" + approach
                + ";distance=" + settler.distanceToSqr(target)
                + ";best=" + bestItemDistanceSqr
                + ";stuck=" + stuckChecks
                + ";pathStarted=" + pathStarted
                + ";navDone=" + settler.getNavigation().isDone()
                + ";tracked=" + collection.trackedCount());
    }

    private double distanceToApproachSqr(BlockPos approach) {
        double dx = settler.getX() - (approach.getX() + 0.5);
        double dy = settler.getY() - approach.getY();
        double dz = settler.getZ() - (approach.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private void startPickingItem() {
        mode = Mode.PICKING_ITEM;
        phaseTicks = 0;
        contactTransferred = false;
        // tickToItem reaches this method only from the exact selected node,
        // with short reach and a clear ray. Preserve that route authority for
        // the wind-up: ground navigation can decelerate across a block border
        // after stop(), but physical contact is still valid if distance and
        // line of sight are revalidated on the authored frame.
        pickupRouteAuthorized = true;
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.GATHERING_LOG);
        settler.triggerGroundItemPickup();
    }

    private void tickPickingItem() {
        if (!(settler.level() instanceof ServerLevel level)) {
            done = true;
            return;
        }
        ItemEntity target = collection.selected(level);
        if (target != null) {
            settler.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
        phaseTicks++;
        if (!contactTransferred && phaseTicks == GROUND_PICKUP_CONTACT_TICK) {
            ItemEntity contactTarget = collection.selected(level);
            WorkZone zone = currentCollectionZone(level);
            if (contactTarget != null && !collectionTargetAllowed(level, zone,
                    contactTarget.blockPosition())) {
                traceItemRoute("contact_abort_outside_zone", contactTarget,
                    selectedItemApproach, false);
                collection.releaseSelected(level);
                contactTarget = null;
            }
            GroundCollectionSession.PickupResult result = contactTarget != null
                && pickupRouteAuthorized
                ? collection.takeOneToOffhand(level, ITEM_CONTACT_DISTANCE_SQR)
                : GroundCollectionSession.PickupResult.NO_TARGET;
            contactTransferred = result
                == GroundCollectionSession.PickupResult.PICKED;
            if (contactTransferred) {
                level.playSound(null, settler.blockPosition(),
                    ModSounds.ITEM_PICKUP.get(), SoundSource.NEUTRAL, 0.65F,
                    0.97F + settler.getRandom().nextFloat() * 0.08F);
            } else if (contactTarget != null) {
                traceItemRoute("contact_abort_" + result.name().toLowerCase(),
                    contactTarget, selectedItemApproach, false);
            }
        }
        if (phaseTicks < GROUND_PICKUP_DURATION) {
            return;
        }
        if (collection.ownsOffhandItem()) {
            startReturningToContainer();
        } else {
            // Keep the same physical target and bounded route budget. Going
            // back through SELECTING_ITEM used to reset stuckChecks after
            // every missed wind-up, so the same moving/on-stump drop could
            // retry the same bad approach indefinitely.
            ItemEntity retry = collection.selected(level);
            phaseTicks = 0;
            pickupRouteAuthorized = false;
            selectedItemApproach = null;
            if (retry == null) {
                mode = Mode.SELECTING_ITEM;
                return;
            }
            stuckChecks++;
            if (stuckChecks > MAX_PATH_FAILURES) {
                traceItemRoute("release_unreachable_after_contact", retry,
                    null, false);
                settler.recordRouteFailure("felled_item_unreachable");
                collection.releaseSelected(level);
                mode = Mode.SELECTING_ITEM;
                return;
            }
            mode = Mode.TO_ITEM;
            settler.setActivity(SettlerActivity.COLLECTING_ITEMS);
            pathToSelectedItem(level);
        }
    }

    private void startReturningToContainer() {
        mode = Mode.RETURNING_TO_CONTAINER;
        settler.setActivity(SettlerActivity.COLLECTING_ITEMS);
        resetPathBudget();
        if (!pathToContainer() && settler.level() instanceof ServerLevel level
            && ++stuckChecks > MAX_PATH_FAILURES) {
            pauseRecoveryRoute(level, "work_container_unreachable");
        }
    }

    private boolean pathToContainer() {
        if (containerPos == null) {
            done = true;
            return false;
        }
        preparePathBudget(Mode.RETURNING_TO_CONTAINER, containerPos);
        boolean started = settler.getNavigation().moveTo(containerPos.getX() + 0.5,
            containerPos.getY(), containerPos.getZ() + 0.5, 1.0);
        repathTimer = ARRIVAL_REPATH_TICKS;
        return started;
    }

    private void tickReturningToContainer() {
        if (!(settler.level() instanceof ServerLevel level) || containerPos == null) {
            done = true;
            return;
        }
        BlockPos projected = settler.placedWorkContainerPos();
        if (projected == null
            || settler.placedWorkContainerKind() != WorkContainerKind.SACK) {
            collection.returnCarriedToWorld(level, settler.blockPosition(), false);
            settler.recordRouteFailure("work_container_missing");
            done = true;
            return;
        }
        containerPos = projected.immutable();
        settler.getLookControl().setLookAt(containerPos.getX() + 0.5,
            containerPos.getY() + 0.5, containerPos.getZ() + 0.5);
        if (settler.blockPosition().distSqr(containerPos)
            <= CONTAINER_CONTACT_DISTANCE_SQR) {
            settler.getNavigation().stop();
            if (collection.ownsOffhandItem()) {
                startStowingItem();
            } else if (collection.hasTrackedDrops() && collection.hasCapacity()) {
                mode = Mode.SELECTING_ITEM;
                unresolvedDropTicks = 0;
            } else {
                startContainerUp();
            }
        } else if (--repathTimer <= 0) {
            notePathProgress(containerPos);
            boolean started = pathToContainer();
            if (!started) {
                stuckChecks++;
            }
            if (stuckChecks > MAX_PATH_FAILURES) {
                pauseRecoveryRoute(level, "work_container_unreachable");
            }
        }
    }

    private void startStowingItem() {
        mode = Mode.STOWING_ITEM;
        phaseTicks = 0;
        contactTransferred = false;
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.GATHERING_LOG);
        lookAtContainer();
        settler.triggerWorkContainerStow();
    }

    private void tickStowingItem() {
        if (!(settler.level() instanceof ServerLevel level)) {
            done = true;
            return;
        }
        lookAtContainer();
        phaseTicks++;
        if (!contactTransferred && phaseTicks == CONTAINER_STOW_CONTACT_TICK) {
            contactTransferred = collection.stowOne()
                == GroundCollectionSession.StowResult.STOWED;
            if (contactTransferred) {
                level.playSound(null,
                    containerPos == null ? settler.blockPosition() : containerPos,
                    ModSounds.BAG_STOW.get(), SoundSource.NEUTRAL, 0.65F,
                    0.96F + settler.getRandom().nextFloat() * 0.08F);
            }
        }
        if (phaseTicks < CONTAINER_STOW_DURATION) {
            return;
        }
        if (collection.ownsOffhandItem()) {
            // A capacity/slot race never deletes the hand item. Put it back
            // beside the real sack and collect it after this haul.
            if (!collection.returnCarriedToWorld(level, containerPos, true)) {
                settler.recordRouteFailure("work_container_stow_blocked");
                done = true;
                return;
            }
            startContainerUp();
        } else {
            mode = Mode.SELECTING_ITEM;
            phaseTicks = 0;
            unresolvedDropTicks = 0;
        }
    }

    private void startContainerUp() {
        mode = Mode.CONTAINER_UP;
        phaseTicks = 0;
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.GATHERING_LOG);
        lookAtContainer();
        settler.triggerWorkContainerUp();
    }

    private void tickContainerUp() {
        lookAtContainer();
        phaseTicks++;
        if (phaseTicks == CONTAINER_UP_CONTACT_TICK
            && settler.level() instanceof ServerLevel level) {
            level.playSound(null,
                containerPos == null ? settler.blockPosition() : containerPos,
                ModSounds.BAG_UP.get(), SoundSource.NEUTRAL, 0.65F,
                0.96F + settler.getRandom().nextFloat() * 0.08F);
        }
        if (phaseTicks < CONTAINER_UP_DURATION) {
            return;
        }
        // Fixed-world projection clears only after the authored lift is
        // complete. From this exact tick the persistent bag is on the back.
        settler.clearWorkContainer();
        phaseTicks = 0;
        if (bagCount() <= 0) {
            done = true;
            settler.setActivity(SettlerActivity.IDLE);
            return;
        }
        mode = Mode.TO_CAMP;
        settler.setActivity(SettlerActivity.HAULING_LOG);
        resetCampBudget();
        if (!pathToCamp() && !done
            && ++campStuckChecks > MAX_PATH_FAILURES
            && settler.level() instanceof ServerLevel level) {
            pauseRecoveryRoute(level, "lumber_camp_unreachable");
        }
    }

    private boolean pathToCollectionSite() {
        if (containerPos == null) {
            done = true;
            return false;
        }
        preparePathBudget(Mode.TO_COLLECTION_SITE, containerPos);
        boolean started = settler.getNavigation().moveTo(containerPos.getX() + 0.5,
            containerPos.getY(), containerPos.getZ() + 0.5, 1.0);
        repathTimer = ARRIVAL_REPATH_TICKS;
        return started;
    }

    private void tickToCollectionSite() {
        if (!(settler.level() instanceof ServerLevel level) || containerPos == null) {
            done = true;
            return;
        }
        if (settler.blockPosition().distSqr(containerPos)
            <= CONTAINER_CONTACT_DISTANCE_SQR) {
            startContainerDown(containerPos);
        } else if (--repathTimer <= 0) {
            notePathProgress(containerPos);
            boolean started = pathToCollectionSite();
            if (!started) {
                stuckChecks++;
            }
            if (stuckChecks > MAX_PATH_FAILURES) {
                pauseRecoveryRoute(level, "collection_site_unreachable");
            }
        }
    }

    private void resetPathBudget() {
        repathTimer = 0;
        stuckChecks = 0;
        pathBudgetMode = null;
        pathBudgetTarget = null;
        bestPathDistanceSqr = Double.MAX_VALUE;
    }

    private void preparePathBudget(Mode budgetMode, BlockPos target) {
        if (pathBudgetMode != budgetMode
            || !Objects.equals(pathBudgetTarget, target)) {
            pathBudgetMode = budgetMode;
            pathBudgetTarget = target.immutable();
            bestPathDistanceSqr = distanceToBlockSqr(target);
            stuckChecks = 0;
        }
    }

    private void notePathProgress(BlockPos target) {
        double distance = distanceToBlockSqr(target);
        if (distance + 0.25 < bestPathDistanceSqr) {
            bestPathDistanceSqr = distance;
            stuckChecks = 0;
        } else {
            stuckChecks++;
        }
    }

    private double distanceToBlockSqr(BlockPos target) {
        double dx = settler.getX() - (target.getX() + 0.5);
        double dy = settler.getY() - target.getY();
        double dz = settler.getZ() - (target.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private void lookAtContainer() {
        if (containerPos != null) {
            settler.getLookControl().setLookAt(containerPos.getX() + 0.5,
                containerPos.getY() + 0.45, containerPos.getZ() + 0.5,
                30.0F, 30.0F);
        }
    }

    private void tickDeposit() {
        if (!(settler.level() instanceof ServerLevel serverLevel)) {
            done = true;
            return;
        }
        Settlement settlement = settler.settlement();
        Building camp = settlement == null ? null
            : Employment.employerOf(settlement, settler.getUUID());
        if (settlement == null || camp == null) {
            pauseRecoveryRoute(serverLevel, "lumber_camp_missing");
            return;
        }
        if (depositTarget == null) {
            depositTarget = WorkerStorageAuthority.nearestLoadedContainer(
                serverLevel, camp, settler.blockPosition());
        }
        if (depositTarget == null) {
            pauseRecoveryRoute(serverLevel, "lumber_camp_storage_missing");
            return;
        }
        settler.getLookControl().setLookAt(depositTarget.getX() + 0.5,
            depositTarget.getY() + 0.6, depositTarget.getZ() + 0.5);
        // HAUL_LOG's strain accent: t=1.20s of its 2.4s loop -> tick 24 of 48.
        // Reuses settler_hm (an exhale-shaped voice sound) pitched down --
        // the catalogue's own suggested reuse for this accent, no new asset.
        haulTicks++;
        if (haulTicks % 48 == 24) {
            serverLevel.playSound(null, settler.blockPosition(), ModSounds.SETTLER_HM.get(),
                SoundSource.NEUTRAL, 0.35F, 0.6F + settler.getRandom().nextFloat() * 0.05F);
        }
        ContainerApproach.Result contact = ContainerApproach.inspect(serverLevel,
            settler, depositTarget);
        if (contact.canInteract()) {
            settler.getNavigation().stop();
            campStuckChecks = 0;
            for (int i = 0; i < settler.bag.getContainerSize(); i++) {
                ItemStack stack = settler.bag.getItem(i);
                if (!stack.isEmpty()) {
                    ItemStack offered = stack.copy();
                    WorkerProvenanceService.DepositResult result =
                        WorkerStackProvenance.readTransit(stack).isPresent()
                            ? WorkerProvenanceService.depositOutput(serverLevel,
                                settlement, camp, settler, depositTarget, stack)
                            : WorkerProvenanceService.depositOrdinary(serverLevel,
                                settlement, camp, settler, depositTarget, stack,
                                BuildingType.LUMBER_CAMP, WorkZone.Type.LUMBER);
                    ItemStack remainder = result.remainder();
                    settler.bag.setItem(i, remainder);
                    int inserted = offered.getCount() - remainder.getCount();
                    if (inserted > 0 && offered.is(ItemTags.LOGS)
                        && result.receipt() != null) {
                            FoundingJourneyProgress.noteLogStored(serverLevel,
                                settlement, camp, settler, offered, inserted);
                            DevelopmentQuests.noteLumberLogsStored(serverLevel,
                                settlement, camp, settler, offered, inserted);
                    }
                }
            }
            depositTarget = null;
            if (bagCount() <= 0 && collection.hasTrackedDrops()
                && containerPos != null) {
                mode = Mode.TO_COLLECTION_SITE;
                settler.setActivity(SettlerActivity.COLLECTING_ITEMS);
                resetPathBudget();
                if (!pathToCollectionSite() && !done
                    && ++stuckChecks > MAX_PATH_FAILURES) {
                    pauseRecoveryRoute(serverLevel, "collection_site_unreachable");
                }
            } else {
                if (!collection.hasTrackedDrops()) {
                    containerPos = null;
                    clearCollectionSite();
                }
                if (bagCount() > 0) {
                    pauseRecoveryRoute(serverLevel, "lumber_camp_storage_full");
                } else {
                    resetCampBudget();
                    done = true;
                }
            }
        } else if (--repathTimer <= 0) {
            double distance = settler.blockPosition().distSqr(depositTarget);
            if (distance + 0.25 < bestCampDistanceSqr) {
                bestCampDistanceSqr = distance;
                campStuckChecks = 0;
            } else {
                campStuckChecks++;
            }
            if (contact.state() == ContainerApproach.State.INVALID_TARGET) {
                depositTarget = null;
            }
            boolean started = pathToCamp();
            if (!started && !done) {
                campStuckChecks++;
            }
            if (!done && campStuckChecks > MAX_PATH_FAILURES) {
                pauseRecoveryRoute(serverLevel, "lumber_camp_unreachable");
            }
        }
    }

    @Override
    public void stop() {
        // Covers every path OUT of a claimed tree that is not the normal
        // finishTree() release above: an interruption (combat, an unbind)
        // or tickTravel's own "tree_unreachable" give-up, both of which
        // leave treeBase set and simply stop the goal. A no-op once
        // finishTree already released and nulled treeBase.
        releaseClaim(treeBase);
        boolean stillLumberer = settler.getProfession() == Profession.LUMBERER
            && settler.isBound();
        boolean collectionStarted = containerPos != null
            && mode != Mode.TO_TREE && mode != Mode.CHOPPING
            && mode != Mode.LIMBING;
        collectionStarted = collectionStarted
            || (workActionId != null && collection.hasTrackedDrops());
        if (settler.level() instanceof ServerLevel level) {
            if (stillLumberer && collectionStarted) {
                // The fixed sack and all inserted contents persist. A
                // mid-carry offhand item is re-materialised before releasing
                // movement, then the same session can resume next work tick.
                collection.suspend(level);
            } else {
                collection.abandon(level);
                if (settler.placedWorkContainerPos() != null
                    && settler.placedWorkContainerKind() == WorkContainerKind.SACK) {
                    settler.clearWorkContainer();
                }
                clearCollectionSite();
                containerPos = null;
            }
        }
        treeBase = null;
        treeLogs = List.of();
        chopRecoveryTicks = 0;
        phaseTicks = 0;
        contactTransferred = false;
        pickupRouteAuthorized = false;
        settler.setActivity(SettlerActivity.IDLE);
        settler.getNavigation().stop();
    }

    // ------------------------------------------------------- tree claims ---

    private record TreeClaim(UUID worker, long expiresAtTick) {
    }

    /**
     * Every tree currently claimed by a lumberer, across every settlement.
     * Same shape and lifecycle discipline as {@code
     * CourierWorkGoal#RESERVATIONS} and {@code RepairWorkGoal#CLAIMS} (read
     * either for the established idiom): claim on pick, in the same
     * synchronous {@link #canUse()} call that found the tree, so two
     * lumberers scanning in the same tick cannot both land on the identical
     * base; renew every active tick ({@link #renewClaim}) so a lumberer
     * genuinely working a tree never loses it mid-chop; release on
     * completion ({@link #finishTree}) and on any other way the goal stops
     * ({@link #stop()}); and a TTL ({@link #TREE_CLAIM_TTL_TICKS}) so a
     * dead/stuck lumberer never strands a tree for good.
     *
     * <p>Bounded and cleaned, the same invariant the courier and repair
     * ledgers already keep: entries are removed promptly on release (or
     * lapse on their TTL), so this only ever holds as many rows as there are
     * lumberers actively working a tree right now -- bounded by how many
     * lumberers exist, never by how many trees have ever been scanned or
     * felled. Static like its siblings: intent ("who is felling this"),
     * never a second copy of world state, so a stale entry for a settlement
     * that no longer exists just sits unreferenced until its lease lapses.
     */
    private static final Map<ServerLevel, Map<BlockPos, TreeClaim>> TREE_CLAIMS =
        new WeakHashMap<>();

    private static Map<BlockPos, TreeClaim> claimLedger(ServerLevel level,
                                                         boolean create) {
        Map<BlockPos, TreeClaim> ledger = TREE_CLAIMS.get(level);
        if (ledger == null && create) {
            ledger = new HashMap<>();
            TREE_CLAIMS.put(level, ledger);
        }
        if (ledger != null) {
            long now = level.getGameTime();
            ledger.entrySet().removeIf(entry -> entry.getValue().expiresAtTick() <= now);
            if (ledger.isEmpty() && !create) {
                TREE_CLAIMS.remove(level);
            }
        }
        return ledger;
    }

    private boolean heldByOther(ServerLevel level, BlockPos base, long now) {
        Map<BlockPos, TreeClaim> ledger = claimLedger(level, false);
        TreeClaim held = ledger == null ? null : ledger.get(base);
        return held != null && held.expiresAtTick() > now
            && !held.worker().equals(settler.getUUID());
    }

    private boolean claim(ServerLevel level, BlockPos base, long now) {
        if (heldByOther(level, base, now)) {
            return false;
        }
        claimLedger(level, true).put(base,
            new TreeClaim(settler.getUUID(), now + TREE_CLAIM_TTL_TICKS));
        return true;
    }

    private void renewClaim(ServerLevel level) {
        if (treeBase == null) {
            return;
        }
        claimLedger(level, true).put(treeBase,
            new TreeClaim(settler.getUUID(), level.getGameTime()
                + TREE_CLAIM_TTL_TICKS));
    }

    private void releaseClaim(BlockPos base) {
        if (base == null) {
            return;
        }
        if (!(settler.level() instanceof ServerLevel level)) {
            return;
        }
        Map<BlockPos, TreeClaim> ledger = claimLedger(level, false);
        TreeClaim held = ledger == null ? null : ledger.get(base);
        if (held != null && held.worker().equals(settler.getUUID())) {
            ledger.remove(base);
            if (ledger.isEmpty()) {
                TREE_CLAIMS.remove(level);
            }
        }
    }

    /**
     * Test-only window into the ledger -- the same reason {@code
     * CourierWorkGoal#restockJobIsHeld} and {@code
     * RepairWorkGoal#scarIsClaimed} exist: from the outside, "the second
     * lumberer was locked out of this tree" and "the second lumberer just
     * has not scanned yet" can look identical, and only the lock itself
     * (and who holds it) tells them apart.
     */
    public static boolean treeIsClaimed(ServerLevel level, BlockPos base) {
        return treeClaimant(level, base) != null;
    }

    /** @return the claiming settler's UUID, or {@code null} if unclaimed. */
    public static UUID treeClaimant(ServerLevel level, BlockPos base) {
        Map<BlockPos, TreeClaim> ledger = claimLedger(level, false);
        TreeClaim held = ledger == null ? null : ledger.get(base);
        return held == null ? null : held.worker();
    }
}
