package com.hearthstead.entity.ai;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.JobEffects;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.BagTransferPresentation;
import com.hearthstead.entity.animation.BagToChestAnimationContract;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
    /**
     * Retained drops own a finite lease. Wake with two normal heartbeat
     * windows still available; using the full lease duration made the exact
     * retry tick lose authority before it could reclaim the physical item.
     */
    private static final int RECOVERY_RETRY_TICKS =
        GroundCollectionSession.OWNERSHIP_LEASE_TICKS
            - 2 * GroundCollectionSession.OWNERSHIP_REFRESH_TICKS;
    /** Approach changes may reset progress, but never this absolute budget. */
    private static final int MAX_ITEM_ROUTE_ATTEMPTS = 24;
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
    /**
     * A stamped output can land above the final reachable ground node after a
     * real tree fall. Eight blocks is still a local axe action, while the
     * provenance, exact work-zone, first-hit ray, natural-leaf and building
     * guards below keep it from becoming broad canopy clearance.
     */
    private static final double CANOPY_CLEAR_REACH_SQR = 64.0;
    /**
     * A claimed tree's fixed stand can remain just beyond the entity's normal
     * collision box after a failed route. Scan only that final direct lane;
     * this does not expand clearance while collecting physical output.
     */
    private static final double TREE_FINAL_LEAF_APPROACH_REACH = 3.0D;
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
    private long nextLeafClearTick;
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
    /** Last real body sample; valid routes may initially move away around a wall. */
    private double campSampleX;
    private double campSampleY;
    private double campSampleZ;
    /** Best real distance reached for the selected physical drop. */
    private double bestItemDistanceSqr = Double.MAX_VALUE;
    /** Exact reachable feet node selected for the current physical drop. */
    @Nullable
    private BlockPos selectedItemApproach;
    /** Last block occupied by the selected physical ItemEntity. */
    @Nullable
    private BlockPos selectedItemTargetBlock;
    /** Repath calls spent on this physical UUID during the current attempt. */
    private int itemRouteAttempts;
    private boolean done;
    /** Real chest in the lumber camp; no output is posted to the Hearth. */
    private BlockPos depositTarget;
    private long lastUnloadTick = Long.MIN_VALUE;
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
        // This attempt has deliberately yielded and the persisted wall-clock
        // gate owns the bounded backoff.  Its exhausted transient progress
        // counters must not poison the next attempt after the environment has
        // changed (for example, a sealed camp being opened); otherwise start()
        // immediately re-yields forever while the real cargo remains safe in
        // the bag.  Destination and collection ownership stay untouched.
        resetPathBudget();
        resetCampBudget();
        done = true;
    }

    private boolean workConditions() {
        return settler.getProfession() == Profession.LUMBERER
            && settler.isBound()
            && settler.dayPhase().work();
    }

    @Override
    public boolean canUse() {
        if (settler.level() instanceof ServerLevel server)
            WorkerProvenanceService.reconcileLumberWork(server, settler);
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
        // Paid Worker Packs: the same budget, half again (WorkerPacks).
        com.hearthstead.settlement.development.WorkerPacks.apply(level, s, settler);
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
            // A provenance action is intentionally resumable, but a failed
            // attempt may also have authored the persisted bounded retry gate
            // below. Checking cargo recovery only after this branch bypassed
            // that gate and could restart the same blocked log action every
            // tick. Ordinary combat/reload resumes have no tag and still
            // continue immediately.
            if (!recoveryRetryReady(level)) {
                return false;
            }
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

        var recovery = WorkerProvenanceService.recoverableLumberWork(level, s,
            employer, settler);
        WorkZone recoveryZone = recovery == null ? zone : recovery.zone();
        BlockPos rememberedSite = persistedCollectionSite(level);
        BlockPos placed = settler.placedWorkContainerPos();
        boolean hasRecoveryWork = bagCount() > 0
            || collection.hasTrackedDrops() || placed != null
            || rememberedSite != null;
        if (hasRecoveryWork && !recoveryRetryReady(level)) {
            return false;
        }

        // Camp unloading owns a separate fixed bag, not the original collection
        // site. Resume its saved contact clock without reclassifying the camp
        // anchor as the tree/drop collection origin.
        // If that saved chest was broken mid-animation, forcing it again would
        // loop forever against tickDeposit's replacement chest. Release only
        // the visual claim (bag contents stay) so a fresh cycle can choose.
        if (GroundedBagUnload.presentationTargetGone(level, settler,
                settler.bagTransferPresentation())) {
            GroundedBagUnload.clearPresentation(settler,
                settler.bagTransferPresentation());
        }
        if (settler.bagTransferPresentation().active()) {
            depositTarget = settler.bagTransferPresentation().containerPos();
            containerPos = rememberedSite;
            mode = Mode.TO_CAMP;
            return true;
        }

        // A persisted placed container is physical authority after an AI
        // interruption or chunk reload. Go back to it before doing anything
        // else; never make its contents jump onto the worker at canUse().
        if (placed != null && settler.placedWorkContainerKind() == WorkContainerKind.SACK) {
            containerPos = placed.immutable();
            persistCollectionSite(level, containerPos);
            if (collectionTargetAllowed(level, recoveryZone, placed)) {
                recoverOwnedInsideZone(level, recoveryZone, placed);
            }
            collection.adoptLegacyEligibleOffhandAtPlacedContainer();
            if (bagCount() > 0 || (recoveryZone != null && collection.hasTrackedDrops())
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
        if (rememberedSite != null
                && !collectionTargetAllowed(level, recoveryZone, rememberedSite)) {
            // A prior version could persist a fallback at the worker's old
            // elevation, outside the exact source zone. It is only a routing
            // hint: discard that stale coordinate, never the authenticated
            // physical output or its recovery action.
            clearCollectionSite();
            rememberedSite = null;
            containerPos = null;
            if (recovery == null || recoveryZone == null) {
                pauseRecoveryRoute(level, "collection_site_outside_zone");
                return false;
            }
        }
        if (rememberedSite != null) {
            containerPos = rememberedSite.immutable();
            recoverOwnedInsideZone(level, recoveryZone, rememberedSite);
            collection.adoptEligibleOffhand();
            if (bagCount() > 0) {
                mode = Mode.TO_CAMP;
                return true;
            }
            if ((recoveryZone != null && collection.hasTrackedDrops())
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
        if (recoveryZone != null && collection.hasTrackedDrops() && containerPos != null) {
            persistCollectionSite(level, containerPos);
            mode = Mode.TO_COLLECTION_SITE;
            return true;
        }
        if (recovery != null) {
            // A zone change can interrupt before the original tree ever chose
            // a collection site. Recover only its indexed physical outputs.
            BlockPos anchor = chooseContainerAnchor(recovery.source());
            if (anchor == null) {
                pauseRecoveryRoute(level, "collection_site_no_legal_anchor");
                return false;
            }
            containerPos = anchor;
            persistCollectionSite(level, containerPos);
            recoverOwnedInsideZone(level, recoveryZone, recovery.source());
            if (collection.hasTrackedDrops() || collection.ownsOffhandItem()) {
                mode = Mode.TO_COLLECTION_SITE;
                resetPathBudget();
                return true;
            }
            // Missing loaded output may have been picked up by a player or
            // despawned. Preserve its ledger; suspend this blocking recovery
            // only after the service proves no custody or unloaded uncertainty.
            if (WorkerProvenanceService.suspendUnavailableLumberOutput(level, s,
                    employer, settler, recovery)) {
                clearCollectionSite();
                containerPos = null;
                settler.recordRouteFailure("old_output_unavailable_recovery_suspended");
                return false;
            }
            pauseRecoveryRoute(level, "obsolete_output_not_loaded");
            return false;
        }
        if (!workConditions() || !settler.getOffhandItem().isEmpty()) {
            return false;
        }
        if (zone == null) {
            publishWorkZoneStop(StopReason.NO_WORK_ZONE,
                employer == null ? null : employer.plaquePos, "ai_idle_no_zone", null);
            return false;
        }
        // A valid zone resolves NO_WORK_ZONE. Keep NO_VALID_TARGET visible
        // until a resumed or newly claimed tree actually provides work.
        if (workZoneState == StopReason.NO_WORK_ZONE) {
            clearWorkZoneStop();
        }
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
            Math.min(TREE_SCAN_BUDGET, 4096 / Math.max(1, zone.sizeY())), TREE_SCAN_RESULTS, column -> {
                BlockPos base = trunkInColumn(column, zone);
                return base == null || !zone.contains(base)
                    || heldByOther(level, base, now)
                    || recentlyUnreachable(base, now) ? null : base;
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
        var recovery = WorkerProvenanceService.recoverableLumberWork(level,
            settlement, employer, settler);
        return recovery == null ? authoritativeZone(level, settlement, employer)
            : recovery.zone();
    }

    /** Shared by runtime contact gates and adversarial GameTests. */
    public static boolean collectionTargetAllowed(ServerLevel level,
                                                  @Nullable WorkZone zone,
                                                  @Nullable BlockPos target) {
        return WorkZoneService.livePositionAllowed(level, zone, target);
    }

    /**
     * Runtime collection adds one narrow exception for a real output whose
     * authenticated source is inside this exact zone but whose vanilla item
     * motion settled just across its edge.
     */
    private boolean currentCollectionTargetAllowed(ServerLevel level,
                                                   @Nullable WorkZone zone,
                                                   @Nullable ItemEntity target) {
        if (target == null) {
            return false;
        }
        Settlement settlement = settler.settlement();
        Building employer = settlement == null ? null
            : Employment.employerOf(settlement, settler.getUUID());
        var recovery = WorkerProvenanceService.recoverableLumberWork(level,
            settlement, employer, settler);
        boolean oldZone = recovery != null && !recovery.zone().equals(
            authoritativeZone(level, settlement, employer));
        return (!oldZone && collectionTargetAllowed(level, zone, target.blockPosition()))
            || WorkerProvenanceService.collectableLumberOutput(level,
                settlement, employer, settler, zone, target.getItem(),
                target.blockPosition());
    }

    private static AABB recoveryBounds(WorkZone zone, BlockPos site) {
        AABB exactZone = new AABB(zone.min().getX(), zone.min().getY(),
            zone.min().getZ(), zone.max().getX() + 1.0D,
            zone.max().getY() + 1.0D, zone.max().getZ() + 1.0D);
        return new AABB(site).inflate(RECOVERY_HORIZONTAL, RECOVERY_VERTICAL,
            RECOVERY_HORIZONTAL).intersect(exactZone.inflate(2.0D));
    }

    private void recoverOwnedInsideZone(ServerLevel level, WorkZone zone,
                                        BlockPos site) {
        Settlement settlement = settler.settlement();
        Building employer = settlement == null ? null
            : Employment.employerOf(settlement, settler.getUUID());
        // A death/reload or long interruption can outlive the public pickup
        // lease. Reclaim only surviving physical output authenticated against
        // this worker's outstanding action, employer, source and exact zone.
        // Plain logs and another worker's ownership remain untouched.
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                recoveryBounds(zone, site), ItemEntity::isAlive)) {
            if (WorkerProvenanceService.collectableLumberOutput(level,
                    settlement, employer, settler, zone, item.getItem(),
                    item.blockPosition())) {
                collection.reclaimExactExpiredFieldOutput(level, item.getUUID(),
                    item.getItem().copy(), candidate ->
                        WorkerProvenanceService.collectableLumberOutput(level,
                            settlement, employer, settler, zone,
                            candidate.getItem(), candidate.blockPosition()));
            }
        }
        collection.recoverOwned(level, recoveryBounds(zone, site),
            pos -> WorkerProvenanceService
                .lumberOutputRecoveryPositionAllowed(level, zone, pos),
            pos -> WorkerProvenanceService
                .lumberOutputRecoveryPositionAllowed(level, zone, pos));
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

    private static final int TRUNK_DESCENT = 64;

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
                settler.setActivity(settler.bagTransferPresentation().active()
                    ? SettlerActivity.TRAVELING : SettlerActivity.HAULING_LOG);
                pathToCamp();
                if (!done && campStuckChecks > MAX_PATH_FAILURES
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

    private boolean pathToTree() {
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
            preparePathBudget(Mode.TO_TREE, approach);
            return settler.getNavigation().moveTo(approach.getX() + 0.5, treeBase.getY(),
                approach.getZ() + 0.5, 1.0);
        }
        return false;
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
            campSampleX = settler.getX();
            campSampleY = settler.getY();
            campSampleZ = settler.getZ();
            campStuckChecks = 0;
        }
    }

    private void resetCampBudget() {
        campBudgetTarget = null;
        campSampleX = settler.getX();
        campSampleY = settler.getY();
        campSampleZ = settler.getZ();
        campStuckChecks = 0;
    }

    /**
     * Counts lack of physical movement, never lack of straight-line progress.
     * A real entrance can sit on the opposite side of a workplace, so walking
     * around its wall must not look stuck merely because the first leg moves
     * farther from the chest. The finite consecutive-still budget still
     * yields a genuinely sealed or failed route.
     */
    private void noteCampPathProgress() {
        double dx = settler.getX() - campSampleX;
        double dy = settler.getY() - campSampleY;
        double dz = settler.getZ() - campSampleZ;
        campSampleX = settler.getX();
        campSampleY = settler.getY();
        campSampleZ = settler.getZ();
        if (campRouteMadeProgress(dx, dy, dz)) {
            campStuckChecks = 0;
        } else {
            campStuckChecks++;
        }
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
        // Vanilla ticks every-tick goals once more after tick() finished them,
        // without canContinueToUse() (see CrafterWorkGoal.tick): never act again.
        if (done) return;
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
        if (settler.level() instanceof ServerLevel level && workActionId != null
            && settler.getUUID().equals(treeClaimant(level, treeBase))) {
            clearObstructingLeaf(level, treeBase.relative(standSide()),
                TREE_FINAL_LEAF_APPROACH_REACH);
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
            BlockPos approach = treeBase.relative(standSide());
            if (pathToTree()) {
                notePathProgress(approach);
            } else {
                stuckChecks++;
            }
            if (stuckChecks > 6) {
                // Unreachable tree; rescan later. Recorded rather than
                // endured (SettlerEntity#recordRouteFailure's whole reason
                // to exist, per GoToPostGoal's own use of it): a lumberer
                // who silently gives up on a tree it cannot path to reads
                // identically to one that never found a tree at all, and
                // KF-020 spent real investigation time unable to tell those
                // two apart from the outside.
                settler.recordRouteFailure("tree_unreachable");
                rememberUnreachable(treeBase, settler.level().getGameTime());
                done = true;
            }
        }
    }

    /**
     * Tree bases this lumberer just failed to reach, with the game time until
     * which the scan skips them. Without it the rescan picked the same
     * nearest trunk (below a drop, behind a ladder) every time and a whole
     * workday went by with no logs (soak/QA 2026-09-25). Transient and small.
     */
    private final java.util.Map<BlockPos, Long> unreachableBases = new java.util.HashMap<>();
    private static final long UNREACHABLE_TREE_COOLDOWN = 2_400L;

    private boolean recentlyUnreachable(BlockPos base, long now) {
        if (unreachableBases.isEmpty()) return false;
        Long until = unreachableBases.get(base);
        return until != null && until > now;
    }

    private void rememberUnreachable(@Nullable BlockPos base, long now) {
        if (base == null) return;
        if (unreachableBases.size() >= 32) {
            unreachableBases.values().removeIf(until -> until <= now);
            if (unreachableBases.size() >= 32) unreachableBases.clear();
        }
        unreachableBases.put(base.immutable(), now + UNREACHABLE_TREE_COOLDOWN);
    }

    /** Clears only the first physical obstruction in a short, blocked work approach. */
    private void clearObstructingLeaf(ServerLevel level, BlockPos destination, double maximumReach) {
        long now = level.getGameTime();
        if (now < nextLeafClearTick || (!settler.horizontalCollision
                && !settler.getNavigation().isDone())
            || !settler.getMainHandItem().is(ItemTags.AXES)) return;
        nextLeafClearTick = now + 10;
        Settlement settlement = settler.settlement();
        Building employer = settlement == null ? null
            : Employment.employerOf(settlement, settler.getUUID());
        WorkZone zone = authoritativeZone(level, settlement, employer);
        if (zone == null || !WorkZoneService.livePositionAllowed(level, zone, destination)) return;
        Vec3 direction = Vec3.atBottomCenterOf(destination).subtract(settler.position());
        direction = new Vec3(direction.x, 0, direction.z);
        if (direction.lengthSqr() < 0.01) return;
        Vec3 reach = direction.normalize().scale(Math.min(maximumReach,
            Math.sqrt(direction.lengthSqr())));
        AABB corridor = settler.getBoundingBox().deflate(0.01).expandTowards(reach);
        BlockPos obstruction = null;
        double nearest = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(corridor.minX, corridor.minY, corridor.minZ),
                BlockPos.containing(corridor.maxX, corridor.maxY, corridor.maxZ))) {
            if (!WorkZoneService.livePositionAvailable(level, pos)) return;
            BlockState state = level.getBlockState(pos);
            boolean blocks = false;
            for (AABB box : state.getCollisionShape(level, pos).toAabbs()) {
                if (box.move(pos).intersects(corridor)) { blocks = true; break; }
            }
            if (!blocks) continue;
            double distance = Vec3.atCenterOf(pos).distanceToSqr(settler.position());
            if (distance < nearest) { nearest = distance; obstruction = pos.immutable(); }
        }
        if (obstruction == null || !WorkZoneService.livePositionAllowed(level, zone, obstruction)) return;
        BlockState leaf = level.getBlockState(obstruction);
        if (!(leaf.getBlock() instanceof LeavesBlock) || leaf.getValue(LeavesBlock.PERSISTENT)) return;
        // A selected natural tree is not permission to dismantle a registered room.
        for (Building building : settlement.buildings) {
            if (building.bounds != null && building.bounds.isInside(obstruction)) return;
        }
        // Vanilla entity block destruction creates ordinary physical leaf loot.
        // It does not invent a worker log receipt or insert anything into cargo.
        if (level.destroyBlock(obstruction, true, settler)) {
            settler.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            settler.getNavigation().recomputePath();
        }
    }

    /**
     * An owned log can settle on a neighboring canopy. Clear only its first
     * reachable natural-leaf obstruction; the original item must fall and
     * pass the unchanged pickup/contact gates before entering the bag.
     */
    private void clearCanopyLeafForSelectedOutput(ServerLevel level,
                                                   @Nullable WorkZone zone,
                                                   ItemEntity target) {
        if (zone == null || !settler.getMainHandItem().is(ItemTags.AXES)
            || level.getGameTime() < nextLeafClearTick) return;
        Settlement settlement = settler.settlement();
        Building employer = settlement == null ? null
            : Employment.employerOf(settlement, settler.getUUID());
        if (settlement == null || employer == null
            || !WorkerProvenanceService.collectableLumberOutput(level, settlement,
                employer, settler, zone, target.getItem(), target.blockPosition())) return;
        Vec3 eye = settler.getEyePosition();
        Vec3 item = target.getBoundingBox().getCenter();
        if (eye.distanceToSqr(item) > CANOPY_CLEAR_REACH_SQR
            || !level.hasChunksAt(BlockPos.containing(eye), BlockPos.containing(item))) return;
        BlockHitResult hit = level.clip(new ClipContext(eye, item,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, settler));
        if (hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos obstruction = hit.getBlockPos();
        if (!WorkZoneService.livePositionAvailable(level, obstruction)
            || !WorkZoneService.livePositionAllowed(level, zone, obstruction)
            || eye.distanceToSqr(Vec3.atCenterOf(obstruction)) > CANOPY_CLEAR_REACH_SQR)
            return;
        BlockState leaf = level.getBlockState(obstruction);
        if (!(leaf.getBlock() instanceof LeavesBlock)
            || leaf.getValue(LeavesBlock.PERSISTENT)) return;
        for (Building building : settlement.buildings)
            if (building.bounds != null && building.bounds.isInside(obstruction)) return;
        nextLeafClearTick = level.getGameTime() + 10;
        if (level.destroyBlock(obstruction, true, settler)) {
            settler.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            settler.getNavigation().recomputePath();
        }
    }

    private BlockPos fellingDropPos;
    private long fellingDropAt;
    private boolean committingFell;

    /** Work stays at the root; no source block is removed before final contact. */
    private void tickChop() {
        if (treeLogs.isEmpty()) { startLimbing(); return; }
        if (!(settler.level() instanceof ServerLevel level) || workActionId == null) return;
        var saved = settler.getPersistentData();
        String action = workActionId.toString();
        if (!action.equals(saved.getString("HearthsteadFellingAction"))) {
            saved.putString("HearthsteadFellingAction", action);
            saved.putInt("HearthsteadFellingTicks", 0);
        }
        if (!zoneAllows(level, treeBase, "fell_root")) return;
        settler.getLookControl().setLookAt(treeBase.getX()+.5, settler.getEyeY(), treeBase.getZ()+.5);
        // Bounded without freezing the chop phase: the old Math.min(20000, ..)
        // pinned elapsed at 20000, where 20000 % CHOP_CYCLE_TICKS never equals
        // CHOP_CONTACT_TICK, so a tree whose first contacts had paused (a worn
        // axe) could never be felled again - a Lumberer "chopping" one trunk
        // for hours (captain1 soak 2026-09-26, Bramwell).
        int elapsed = saved.getInt("HearthsteadFellingTicks") + 1;
        if (elapsed > 20000) {
            elapsed -= CHOP_CYCLE_TICKS;
        }
        saved.putInt("HearthsteadFellingTicks", elapsed);
        if (elapsed % CHOP_CYCLE_TICKS == CHOP_CONTACT_TICK)
            WorkSoundSync.play(level, treeBase, ModSounds.CHOP.get(), .6F, .97F);
        int required = com.hearthstead.settlement.development.DevelopmentBonuses.fellingTicks(
            level, settler.settlement(),
            chopTicksForStrength(settler.attribute(Attribute.STRENGTH)) * treeLogs.size());
        // Tech tree Mine, Smelter & Smithy (techtree-craft): iron axe -10%, diamond+ -20%.
        required = com.hearthstead.settlement.techtree.effects.CraftEffects.toolTicks(
            level, settler.settlement(), settler.getMainHandItem(), required);
        if (elapsed < required || elapsed % CHOP_CYCLE_TICKS != CHOP_CONTACT_TICK) return;
        var tool = settler.getMainHandItem();
        if (!tool.isDamageableItem() || tool.getMaxDamage()-tool.getDamageValue() <= treeLogs.size()) {
            // The axe cannot outlast this tree, but still counts as usable by
            // the ordinary 8-use floor: raise this worker's floor to the tree
            // so a replacement is requested (and shown as "Needs: axe")
            // instead of retrying the same trunk forever.
            com.hearthstead.settlement.equipment.EquipmentRequests.requireJobUses(settler,
                treeLogs.size() + 1);
            Settlement village = settler.settlement();
            Building camp = village == null ? null : Employment.employerOf(village, settler.getUUID());
            if (camp != null) {
                com.hearthstead.settlement.equipment.EquipmentRequests.cancelFor(level, camp, settler.getUUID());
                com.hearthstead.settlement.equipment.EquipmentRequests.refreshFor(level, village, camp, settler);
            }
            pauseRecoveryRoute(level, "tree_requires_more_axe_durability");
            return;
        }
        for (BlockPos log : treeLogs) {
            if (!zoneAllows(level, log, "fell_log") || !level.getBlockState(log).is(BlockTags.LOGS_THAT_BURN)) {
                pauseRecoveryRoute(level, "lumber_action_target_changed"); return;
            }
        }
        var logs = List.copyOf(treeLogs);
        var leaves = fallingLeaves(level, logs);
        Direction direction = safeFallDirection(logs);
        if (direction == null) { pauseRecoveryRoute(level, "tree_fall_space_blocked"); return; }
        var visual = com.hearthstead.entity.FallingTreeEntity.capture(level, treeBase, logs, leaves, direction);
        if (visual == null) { pauseRecoveryRoute(level, "tree_fall_snapshot_blocked"); return; }
        // Grounded output remains in durable escrow until the visible impact.
        fellingDropPos = treeBase;
        fellingDropAt = level.getGameTime() + com.hearthstead.entity.FallingTreeEntity.DURATION_TICKS;
        committingFell = true;
        try {
            while (!treeLogs.isEmpty()) {
                int before = treeLogs.size();
                chopRecoveryTicks = 0;
                chopTicks = chopTicksForStrength(settler.attribute(Attribute.STRENGTH)) - 1;
                commitNextFelledLog();
                if (treeLogs.size() >= before || done) return;
            }
        } finally { committingFell = false; }
        // Publish only after every log has transferred to exact item custody.
        for (BlockPos leaf : leaves) {
            var state = level.getBlockState(leaf);
            if (state.getBlock() instanceof LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT))
                level.destroyBlock(leaf, true, settler);
        }
        level.addFreshEntity(visual);
        saved.putLong("HearthsteadFellingWaitUntil", fellingDropAt + 10);
        saved.remove("HearthsteadFellingTicks");
        saved.remove("HearthsteadFellingAction");
        settler.setActivity(SettlerActivity.IDLE);
        BlockPos back = treeBase.relative(direction.getOpposite(), 2);
        if (level.noCollision(settler, settler.getBoundingBox().move(
                back.getX()+.5-settler.getX(), 0, back.getZ()+.5-settler.getZ())))
            settler.getNavigation().moveTo(back.getX()+.5, settler.getY(), back.getZ()+.5, .8);
    }

    private List<BlockPos> fallingLeaves(ServerLevel level, List<BlockPos> logs) {
        Set<BlockPos> result = new HashSet<>();
        var village = settler.settlement();
        var camp = village == null ? null : Employment.employerOf(village, settler.getUUID());
        var zone = authoritativeZone(level, village, camp);
        if (zone == null) return List.of();
        for (BlockPos log : logs) for (BlockPos pos : BlockPos.betweenClosed(log.offset(-3,-3,-3),log.offset(3,3,3))) {
            if (result.size() >= 384) return List.copyOf(result);
            if (pos.getY() < treeBase.getY() || !WorkZoneService.livePositionAllowed(level,zone,pos)) continue;
            var state = level.getBlockState(pos);
            if (state.getBlock() instanceof LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT)) {
                boolean foreignTrunk = false;
                for (Direction side : Direction.values()) {
                    BlockPos neighbor = pos.relative(side);
                    if (level.hasChunkAt(neighbor) && level.getBlockState(neighbor).is(BlockTags.LOGS_THAT_BURN)
                            && !logs.contains(neighbor)) { foreignTrunk=true; break; }
                }
                if (!foreignTrunk) result.add(pos.immutable());
            }
        }
        return List.copyOf(result);
    }

    private Direction safeFallDirection(List<BlockPos> logs) {
        var directions = new ArrayList<Direction>(List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST));
        directions.sort(Comparator.comparingDouble(d -> -((treeBase.getX()-settler.getX())*d.getStepX()
            +(treeBase.getZ()-settler.getZ())*d.getStepZ())));
        int height=logs.stream().mapToInt(p -> p.getY()-treeBase.getY()+1).max().orElse(1);
        var village=settler.settlement();
        for (Direction direction : directions) {
            boolean blocked=false;
            if (village != null) for (Building building : village.buildings) {
                if (!building.valid || building.bounds == null) continue;
                for (int distance=1;distance<=height+2;distance++) {
                    BlockPos point=treeBase.relative(direction,distance);
                    if (point.getX()>=building.bounds.minX()-2 && point.getX()<=building.bounds.maxX()+2
                            && point.getZ()>=building.bounds.minZ()-2 && point.getZ()<=building.bounds.maxZ()+2) {
                        blocked=true; break;
                    }
                }
                if (blocked) break;
            }
            if (!blocked) return direction;
        }
        return null;
    }

    private void commitNextFelledLog() {
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
        if (!committingFell && chopTicks % CHOP_CYCLE_TICKS == CHOP_CONTACT_TICK
            && settler.level() instanceof ServerLevel serverLevel) {
            WorkSoundSync.play(serverLevel, treeBase, ModSounds.CHOP.get(), 0.6F, 1.0F);
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
                        fellingDropPos, physicalLog, fellingDropAt);
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
                    // A felled log is also real sustained labor: the second core
                    // stat advances only at this committed physical endpoint.
                    settler.train(com.hearthstead.entity.Attribute.STAMINA, 0.5F);
                    // Immediate insertion is best-effort. A false result means
                    // the SavedData row still owns the only copy and will retry
                    // with this stable UUID; it is not permission to restore,
                    // duplicate or silently complete an unowned fallback.
                    collection.materializeQueued(serverLevel, fellingDropPos,
                        physicalLogId);
                    if (!WorkerProvenanceService.completeLumberLog(serverLevel,
                            settlement, camp, settler, workActionId, topLog,
                            physicalLog)) {
                        pauseRecoveryRoute(serverLevel,
                            "lumber_action_receipt_failed");
                        return;
                    }
                    // Trade skill: one log felled AND banked is one unit (STR/STA
                    // already trained above, so SkillLevels adds only the XP).
                    com.hearthstead.entity.SkillLevels.completeUnit(settler, 1,
                        Attribute.STRENGTH, Attribute.STAMINA);
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
                chopRecoveryTicks = com.hearthstead.entity.SkillLevels.shortenWait(settler,
                    fatiguePauseTicks(settler.getEnergy(),
                    settler.attribute(Attribute.STAMINA)));
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
        if (settler.level().getGameTime() < settler.getPersistentData().getLong("HearthsteadFellingWaitUntil")) return;
        settler.getPersistentData().remove("HearthsteadFellingWaitUntil");
        // A resumed limbing stage starts wherever the interruption left her:
        // walk back to the stump first, never limb (and finish) a tree from afar.
        if (treeBase != null && settler.blockPosition().distSqr(treeBase) > 7.5D) {
            if (--repathTimer <= 0) {
                repathTimer = 40;
                pathToTree();
            }
            settler.setActivity(SettlerActivity.TRAVELING);
            return;
        }
        settler.setActivity(SettlerActivity.WORK_LIMB);
        limbTicks++;
        if (settler.level() instanceof ServerLevel serverLevel) {
            if (limbTicks % LIMB_DURATION == 6 || limbTicks % LIMB_DURATION == 19) {
                // Reuses CHOP's synthesis at higher pitch/shorter tail per
                // the catalogue's own suggestion -- no new sound asset.
                WorkSoundSync.play(serverLevel, treeBase, ModSounds.CHOP.get(), 0.4F, 1.4F);
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
        com.hearthstead.settlement.equipment.EquipmentRequests.clearJobUses(settler);
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
        BlockPos completedTree = treeBase;
        releaseClaim(treeBase);
        treeBase = null;
        if (collection.hasTrackedDrops()) {
            BlockPos anchor = chooseContainerAnchor(completedTree);
            if (anchor == null) {
                pauseRecoveryRoute(serverLevel, "collection_site_no_legal_anchor");
                return;
            }
            containerPos = anchor;
            persistCollectionSite(serverLevel, containerPos);
            // The actor must physically reach the chosen clearing before the
            // first set-down animation can publish a grounded container.
            mode = Mode.TO_COLLECTION_SITE;
            settler.setActivity(SettlerActivity.COLLECTING_ITEMS);
            resetPathBudget();
            pathToCollectionSite();
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
    @Nullable
    private BlockPos chooseContainerAnchor(BlockPos completedTree) {
        if (!(settler.level() instanceof ServerLevel level)) {
            return null;
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
        // The worker can have returned to the lower Hearth while a recovery
        // action still owns an elevated tree. Collection candidates must use
        // the physical source's Y, never the worker's unrelated current Y.
        if (completedTree != null) {
            for (int distance = 3; distance <= 4; distance++) {
                for (Direction direction : order) {
                  for (int heightOffset : new int[] {0, -1, 1}) {
                    BlockPos candidate = completedTree.relative(direction, distance).offset(0, heightOffset, 0);
                    if (!collectionTargetAllowed(level, zone, candidate) || !isStandable(level, candidate)) continue;
                    Path path = settler.getNavigation().createPath(candidate, 0);
                    if (path == null || !path.canReach() || !candidate.equals(path.getTarget())) continue;
                    boolean loaded = true;
                    for (int node = 0; node < path.getNodeCount(); node++) {
                        if (!WorkZoneService.livePositionAvailable(level, path.getNode(node).asBlockPos())) {
                            loaded = false; break;
                        }
                    }
                    if (loaded) return candidate.immutable();
                  }
                }
            }
        }
        // The source itself is the final lawful tree-side fallback only
        // when it is a real standing cell. It precedes every worker-position
        // fallback so a returned lower-Y worker cannot persist an off-zone
        // anchor.
        if (completedTree != null && collectionTargetAllowed(level, zone, completedTree)
            && isStandable(level, completedTree)) {
            return completedTree.immutable();
        }
        // Tight plots retain the existing nearby safe fallback. Distance is a
        // preference, never permission to move a sack outside its owned zone.
        for (Direction direction : order) {
            BlockPos candidate = origin.relative(direction);
            BlockPos support = candidate.below();
            if (!collectionTargetAllowed(level, zone, candidate)
                || !WorkZoneService.livePositionAvailable(level, support)) {
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
        if (collectionTargetAllowed(level, zone, origin) && isStandable(level, origin)) {
            return origin.immutable();
        }
        settler.recordRouteFailure("work_container_no_legal_anchor");
        return null;
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
            WorkSoundSync.play(level,
                containerPos == null ? settler.blockPosition() : containerPos,
                ModSounds.BAG_DOWN.get(), 0.5F, 1.0F);
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
            if (next == null || currentCollectionTargetAllowed(level, zone,
                    next)) {
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
            selectedItemTargetBlock = null;
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
        if (target != null && currentCollectionTargetAllowed(level, zone,
                target)) {
            if (itemRouteBudgetExhausted(++itemRouteAttempts)) {
                traceItemRoute("pause_route_budget", target,
                    selectedItemApproach, false);
                pauseRecoveryRoute(level, "felled_item_unreachable");
                return false;
            }
            BlockPos previousApproach = selectedItemApproach;
            BlockPos previousTargetBlock = selectedItemTargetBlock;
            selectedItemTargetBlock = target.blockPosition().immutable();
            Set<BlockPos> approaches = standableItemApproaches(level, target);
            BlockPos current = settler.blockPosition();
            boolean started = false;
            boolean currentHasContact = approaches.contains(current)
                && collection.hasClearPickupLine(level, target)
                && settler.distanceToSqr(target) <= ITEM_CONTACT_DISTANCE_SQR;
            if (currentHasContact) {
                selectedItemApproach = current.immutable();
                settler.getNavigation().stop();
                started = true;
            } else {
                // The pathfinder may finish off-centre on an otherwise valid
                // standing node. If that live pose makes the stump/floor
                // occlude hand contact, do not keep selecting the same
                // already-reached node forever. Route to another pre-checked
                // side while retaining the real item and strict live ray at
                // the eventual animation contact frame.
                approaches.remove(current);
            }
            if (!started && !approaches.isEmpty()) {
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
            if (!Objects.equals(previousApproach, selectedItemApproach)
                || !Objects.equals(previousTargetBlock,
                    selectedItemTargetBlock)) {
                // A moving ItemEntity can legitimately move the exact feet
                // target. Distances to two different world nodes, or to a
                // different block occupied by the same physical UUID, are
                // not one progress series; rebase instead of spending the
                // old target's stuck budget against the new one.
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
            selectedItemTargetBlock = null;
            if (collection.selectedDropId() == null) {
                mode = Mode.SELECTING_ITEM;
            } else if (++unresolvedDropTicks >= UNLOADED_DROP_WAIT_TICKS) {
                collection.clearSelection();
                pauseRecoveryRoute(level, "felled_item_unloaded");
            }
            return;
        }
        BlockPos liveTargetBlock = target.blockPosition();
        if (!Objects.equals(selectedItemTargetBlock, liveTargetBlock)) {
            // Rebase as soon as the same physical UUID enters a new block;
            // waiting for the next repath tick would spend the old target's
            // bounded stuck budget while the item is already moving.
            selectedItemTargetBlock = liveTargetBlock.immutable();
            selectedItemApproach = null;
            bestItemDistanceSqr = Double.MAX_VALUE;
            stuckChecks = 0;
            pickupRouteAuthorized = false;
            repathTimer = 0;
        }
        WorkZone zone = currentCollectionZone(level);
        if (!currentCollectionTargetAllowed(level, zone, target)) {
            traceItemRoute("release_outside_zone", target, null, false);
            collection.releaseSelected(level);
            selectedItemApproach = null;
            selectedItemTargetBlock = null;
            mode = Mode.SELECTING_ITEM;
            return;
        }
        clearCanopyLeafForSelectedOutput(level, zone, target);
        clearObstructingLeaf(level, target.blockPosition(), 0.85D);
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
                traceItemRoute("pause_unreachable", target, null, false);
                // Keep the real felled drop under this worker's persisted
                // ownership. A transient route failure must yield and retry,
                // not permanently orphan the fourth log after three succeed.
                pauseRecoveryRoute(level, "felled_item_unreachable");
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
        WorkZone traceZone = settler.level() instanceof ServerLevel level
            ? currentCollectionZone(level) : null;
        QaTrace.event(settler, "lumber_item_" + event,
            "target=" + target.blockPosition()
                + ";targetPos=" + target.position()
                + ";targetVelocity=" + target.getDeltaMovement()
                + ";targetOnGround=" + target.onGround()
                + ";approach=" + approach
                + ";distance=" + settler.distanceToSqr(target)
                + ";best=" + bestItemDistanceSqr
                + ";stuck=" + stuckChecks
                + ";routeAttempts=" + itemRouteAttempts
                + ";pathStarted=" + pathStarted
                + ";navDone=" + settler.getNavigation().isDone()
                + ";tracked=" + collection.trackedCount()
                + ";source=" + WorkerStackProvenance.readTransit(
                    target.getItem()).map(transit -> BlockPos.of(
                        transit.sourcePos()).toString()).orElse("none")
                + ";zone=" + (traceZone == null ? "none"
                    : traceZone.min() + ".." + traceZone.max()));
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
            if (contactTarget != null && !currentCollectionTargetAllowed(level,
                    zone, contactTarget)) {
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
            selectedItemTargetBlock = null;
            if (retry == null) {
                mode = Mode.SELECTING_ITEM;
                return;
            }
            stuckChecks++;
            if (stuckChecks > MAX_PATH_FAILURES) {
                traceItemRoute("pause_unreachable_after_contact", retry,
                    null, false);
                // The item remains the sole physical authority while the
                // bounded recovery delay lets navigation/environment settle.
                pauseRecoveryRoute(level, "felled_item_unreachable");
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
                WorkSoundSync.play(level,
                    containerPos == null ? settler.blockPosition() : containerPos,
                    ModSounds.BAG_STOW.get(), 0.5F, 1.0F);
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
            WorkSoundSync.play(level,
                containerPos == null ? settler.blockPosition() : containerPos,
                ModSounds.BAG_UP.get(), 0.5F, 1.0F);
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
        pathToCamp();
        if (!done && campStuckChecks > MAX_PATH_FAILURES
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
        if (collectionTargetAllowed(level, currentCollectionZone(level), containerPos)
            && settler.blockPosition().distSqr(containerPos) <= CONTAINER_CONTACT_DISTANCE_SQR
            && level.noCollision(settler, settler.getBoundingBox().expandTowards(
                Vec3.atBottomCenterOf(containerPos).subtract(settler.position())))) {
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
        itemRouteAttempts = 0;
        pathBudgetMode = null;
        pathBudgetTarget = null;
        bestPathDistanceSqr = Double.MAX_VALUE;
    }

    /** Package-visible deterministic seams for retry/route invariants. */
    public static int recoveryRetryTicksForQa() {
        return RECOVERY_RETRY_TICKS;
    }

    static boolean itemRouteBudgetExhausted(int attempts) {
        return attempts >= MAX_ITEM_ROUTE_ATTEMPTS;
    }

    /** Package-visible deterministic seam for the camp detour regression. */
    static boolean campRouteMadeProgress(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz > 0.0625D;
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
            if (!tickCampUnload(serverLevel, settlement, camp)) return;
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
            noteCampPathProgress();
            if (contact.state() == ContainerApproach.State.INVALID_TARGET) {
                depositTarget = null;
                // A claim on the vanished chest would reject every new one.
                if (GroundedBagUnload.presentationTargetGone(serverLevel, settler,
                        settler.bagTransferPresentation())) {
                    GroundedBagUnload.clearPresentation(settler,
                        settler.bagTransferPresentation());
                }
            }
            pathToCamp();
            if (!done && campStuckChecks > MAX_PATH_FAILURES) {
                pauseRecoveryRoute(serverLevel, "lumber_camp_unreachable");
            }
        }
    }

    /** One physical log per visible contact; projection is never inventory. */
    private boolean tickCampUnload(ServerLevel level, Settlement settlement, Building camp) {
        if (lastUnloadTick == level.getGameTime()) return false;
        lastUnloadTick = level.getGameTime();
        BagTransferPresentation view = settler.bagTransferPresentation();
        if (!view.active()) {
            if (bagCount() <= 0) return true;
            ItemStack unit = firstUnloadUnit();
            if (unit.isEmpty()) return false;
            view = new BagTransferPresentation(UUID.randomUUID(), settler.blockPosition(),
                settler.getYRot(), depositTarget, 0, false, unit);
            settler.setActivity(SettlerActivity.SORTING);
            settler.publishBagTransferPresentation(view);
            settler.triggerBagToChestUnload();
            return false;
        }
        if (!depositTarget.equals(view.containerPos())) {
            pauseRecoveryRoute(level, "lumber_unload_target_changed");
            return false;
        }
        settler.setActivity(SettlerActivity.SORTING);
        int next = view.clock() + 1;
        if (next == BagToChestAnimationContract.BAG_WORLD_CONTACT_TICK) {
            settler.placeWorkContainer(WorkContainerKind.SACK, view.bagAnchor());
            WorkSoundSync.play(level, view.bagAnchor(), ModSounds.BAG_DOWN.get(), 0.5F, 1.0F);
        }
        if (next == BagToChestAnimationContract.LID_CONTACT_TICK) {
            level.blockEvent(depositTarget, level.getBlockState(depositTarget).getBlock(), 1, 1);
        }
        boolean committed = view.committed();
        if (BagToChestAnimationContract.mayCommit(next, committed)) {
            ItemStack unit = view.item();
            int slot = -1;
            for (int i=0; i<settler.bag.getContainerSize(); i++) {
                ItemStack live = settler.bag.getItem(i);
                if (!live.isEmpty() && unit.getCount() == 1
                    && ItemStack.isSameItemSameComponents(live, unit)) { slot=i; break; }
            }
            if (slot < 0) {
                // External inventory edits cannot authorize a replacement item.
                settler.clearBagTransferPresentation(view.transferId());
                return false;
            }
            WorkerProvenanceService.DepositResult result =
                WorkerStackProvenance.readTransit(unit).isPresent()
                    ? WorkerProvenanceService.depositOutput(level, settlement, camp,
                        settler, depositTarget, unit.copy())
                    : WorkerProvenanceService.depositOrdinary(level, settlement, camp,
                        settler, depositTarget, unit.copy(), BuildingType.LUMBER_CAMP,
                        WorkZone.Type.LUMBER);
            int inserted = 1 - result.remainder().getCount();
            if (inserted != 1) {
                // Hold before contact; no clock or cargo advance when full/invalid.
                pauseRecoveryRoute(level, "lumber_camp_storage_full");
                return false;
            }
            ItemStack live = settler.bag.getItem(slot);
            live.shrink(1);
            settler.bag.setItem(slot, live);
            committed = true;
            if (unit.is(ItemTags.LOGS) && result.receipt() != null) {
                FoundingJourneyProgress.noteLogStored(level, settlement, camp, settler, unit, 1);
                DevelopmentQuests.noteLumberLogsStored(level, settlement, camp, settler, unit, 1);
            }
        }
        if (next == BagToChestAnimationContract.LID_CLOSED_TICK) {
            level.blockEvent(depositTarget, level.getBlockState(depositTarget).getBlock(), 1, 0);
            if (BagToChestAnimationContract.continuesGroundedSession(next, committed, bagCount() <= 0)) {
                ItemStack unit = firstUnloadUnit();
                settler.publishBagTransferPresentation(new BagTransferPresentation(UUID.randomUUID(),
                    view.bagAnchor(), view.bagYaw(), depositTarget,
                    BagToChestAnimationContract.GROUNDED_REPEAT_TICK, false, unit));
                return false;
            }
        }
        if (next >= BagToChestAnimationContract.DURATION_TICKS) {
            settler.clearBagTransferPresentation(view.transferId());
            settler.clearWorkContainer();
            return bagCount() <= 0;
        }
        settler.publishBagTransferPresentation(view.advance(next, committed));
        return false;
    }

    private ItemStack firstUnloadUnit() {
        for (int i=0; i<settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (!stack.isEmpty()) return stack.copyWithCount(1);
        }
        return ItemStack.EMPTY;
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
            if (stillLumberer && settler.bagTransferPresentation().active()) {
                // Bag inventory and its actual grounded camp anchor remain.
                // The next attempt resumes the exact visible unit/commit bit.
            } else if (stillLumberer && collectionStarted) {
                // The fixed sack and all inserted contents persist. A
                // mid-carry offhand item is re-materialised before releasing
                // movement, then the same session can resume next work tick.
                collection.suspend(level);
            } else {
                if (settler.bagTransferPresentation().active()) {
                    settler.clearBagTransferPresentation(
                        settler.bagTransferPresentation().transferId());
                }
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
