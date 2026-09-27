package com.hearthstead.entity.ai;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.CraftOutputEscrow;
import com.hearthstead.entity.CraftPresentation;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.logistics.StopReason;
import com.hearthstead.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lumberer-only emergency maintenance phase for a real wooden axe.
 *
 * <p>The normal acquisition goal is registered first at the same priority, so
 * a local tool always wins. This goal becomes eligible only after the same
 * persistent equipment request has observed a bounded period with neither
 * serviceable stock nor a Courier claim. It then walks to the exact reserved
 * crafting table, faces it, and asks {@link LumbererSelfCraftingService} for
 * one inventory commit at the authored hand/contact frame.
 */
public final class LumbererSelfCraftGoal extends Goal {
    /** Full authored work beat: 2.4 seconds at 20 TPS. */
    public static final int CRAFT_DURATION_TICKS = 48;
    /** Hands finish the axe assembly 1.5 seconds into the beat. */
    public static final int CRAFT_CONTACT_TICK = 30;
    /** Result reach begins after six readable post-transform ticks. */
    public static final int PICKUP_START_TICK = 37;
    /** The exact table projection hands ownership to protected carry. */
    public static final int PICKUP_CONTACT_TICK = 43;
    /** Keep the world projection on the contact frame; hide it one tick later. */
    public static final int PICKUP_TRANSFER_TICK = 44;
    /** Separate chest interaction; never compressed into the table beat. */
    public static final int DEPOSIT_DURATION_TICKS = 24;
    /** Output enters the real linked container at the authored hand contact. */
    public static final int DEPOSIT_CONTACT_TICK = 14;

    private static final int[] LAYOUT_CONTACT_TICKS = {4, 8, 12, 16, 18};

    private static final int REPATH_TICKS = 20;
    private static final int MAX_FAILED_PATHS = 6;
    private static final long FAILED_PLAN_RETRY_TICKS = 40L;
    private static final int STORAGE_RETRY_TICKS = 40;
    private static final double TABLE_CONTACT_DISTANCE_SQR = 2.25D;
    private static final String GRACE_REQUEST_TAG =
        "HearthsteadLumberAxeGraceRequest";
    private static final String GRACE_SINCE_TAG =
        "HearthsteadLumberAxeGraceSince";

    private enum Stage {
        TO_TABLE,
        TABLE,
        TO_STORAGE,
        DEPOSIT
    }

    private final SettlerEntity settler;
    @Nullable
    private Settlement settlement;
    @Nullable
    private Building camp;
    @Nullable
    private EquipmentRequest request;
    @Nullable
    private LumbererSelfCraftingService.CraftPlan plan;
    private Stage stage = Stage.TO_TABLE;
    private boolean contactAttempted;
    private boolean committed;
    private boolean deposited;
    private boolean finished;
    private int phaseTicks;
    private int depositTicks;
    private int repathIn;
    private int failedPaths;
    private long retryAt;
    private static final long STORAGE_WAIT_RETRY_TICKS = 600L;
    @Nullable
    private BlockPos storageTarget;
    private Direction craftFacing = Direction.NORTH;

    public LumbererSelfCraftGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(settler.level() instanceof ServerLevel level)
            || level.getGameTime() < retryAt) {
            clearGraceIfNoLongerLumberer();
            return false;
        }
        if (settler.hasCraftOutputEscrow()) {
            return resolveEscrowContext();
        }
        if (!workContextReady()) {
            clearGraceIfNoLongerLumberer();
            return false;
        }
        settlement = settler.settlement();
        camp = settlement == null ? null
            : Employment.employerOf(settlement, settler.getUUID());
        if (settlement == null || camp == null || !camp.valid
            || camp.type != BuildingType.LUMBER_CAMP
            || Employment.professionOf(settlement, settler.getUUID())
                != Profession.LUMBERER) {
            clearGrace();
            return false;
        }

        // Refresh consumes a real serviceable tool from the worker's own bag
        // first, otherwise it returns the one persistent request this phase
        // is allowed to wait behind. It never fabricates an inventory stack.
        request = EquipmentRequests.refreshFor(level, settlement, camp, settler);
        if (request == null) {
            clearGrace();
            return false;
        }
        if (LumbererSelfCraftingService.existingSupplyWins(level, settlement,
                camp, settler, request)) {
            clearGrace();
            return false;
        }
        if (!graceElapsed(level, request)) {
            return false;
        }

        plan = LumbererSelfCraftingService.reserve(level, settlement, camp,
            settler, request);
        if (plan == null) {
            retryAt = level.getGameTime() + FAILED_PLAN_RETRY_TICKS;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (finished || settlement == null || camp == null
            || !(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        if (deposited) {
            return stage == Stage.DEPOSIT
                && depositTicks < DEPOSIT_DURATION_TICKS;
        }
        CraftOutputEscrow escrow = settler.craftOutputEscrow();
        if (committed || escrow != null) {
            return escrow != null
                && escrow.settlementId().equals(settlement.id)
                && escrow.buildingId().equals(camp.id);
        }
        return plan != null && request != null && workContextReady()
            && Employment.employerOf(settlement, settler.getUUID()) == camp
            && EquipmentRequests.requestFor(camp, settler.getUUID()) == request
            && !LumbererSelfCraftingService.existingSupplyWins(level,
                settlement, camp, settler, request);
    }

    @Override
    public void start() {
        contactAttempted = false;
        committed = settler.hasCraftOutputEscrow();
        deposited = false;
        finished = false;
        phaseTicks = 0;
        depositTicks = 0;
        repathIn = 0;
        failedPaths = 0;
        storageTarget = null;
        settler.setActivity(SettlerActivity.TRAVELING);
        if (committed) {
            stage = Stage.TO_STORAGE;
            beginOrRetryStorageRoute();
        } else {
            stage = Stage.TO_TABLE;
            pathToTable();
        }
    }

    @Override
    public void tick() {
        // Vanilla ticks every-tick goals once more after tick() finished them,
        // without canContinueToUse() (see CrafterWorkGoal.tick): never act again.
        if (finished) return;
        if (!(settler.level() instanceof ServerLevel level)) {
            finished = true;
            return;
        }
        switch (stage) {
            case TO_TABLE -> tickTravelToTable(level);
            case TABLE -> tickCraftAtTable(level);
            case TO_STORAGE -> tickTravelToStorage(level);
            case DEPOSIT -> tickDeposit(level);
        }
    }

    private void tickCraftAtTable(ServerLevel level) {
        if (plan == null || settlement == null || camp == null || request == null
            || !atTableContact(level)) {
            failPreContact(level, "lost_table_contact");
            return;
        }
        faceTable();
        phaseTicks++;
        if (!committed && (!LumbererSelfCraftingService.heartbeat(level, plan)
            || !LumbererSelfCraftingService.reservationStillExact(level,
                settlement, camp, settler, request, plan))) {
            failPreContact(level, "reservation_invalid");
            return;
        }

        if (!committed && phaseTicks <= LAYOUT_CONTACT_TICKS[4]) {
            publishTablePhase(CraftPresentation.Phase.LAY_OUT,
                visibleRecipeMask(phaseTicks));
        } else if (!committed && phaseTicks < CRAFT_CONTACT_TICK) {
            publishTablePhase(CraftPresentation.Phase.WIND_UP,
                occupiedRecipeMask());
        }

        if (!contactAttempted && phaseTicks == CRAFT_CONTACT_TICK) {
            contactAttempted = true;
            committed = LumbererSelfCraftingService.commit(level, settlement,
                camp, settler, request, plan);
            if (!committed) {
                settler.recordRouteFailure("lumber_self_craft:contact_rejected");
                playCraftFailure(level);
                settler.clearCraftPresentation(plan.actionId());
                finished = true;
                return;
            }
            publishTablePhase(CraftPresentation.Phase.RESULT_READ, 0);
            level.playSound(null, plan.tablePos(),
                level.getBlockState(plan.tablePos()).getSoundType().getHitSound(),
                SoundSource.BLOCKS, 0.72F, 0.92F);
            level.playSound(null, plan.tablePos(), SoundEvents.CRAFTER_CRAFT,
                SoundSource.BLOCKS, 0.62F, 1.05F);
        } else if (committed && phaseTicks == PICKUP_START_TICK) {
            publishTablePhase(CraftPresentation.Phase.PICK_UP, 0);
        } else if (committed && phaseTicks == PICKUP_CONTACT_TICK) {
            level.playSound(null, settler.blockPosition(),
                ModSounds.ITEM_PICKUP.get(), SoundSource.NEUTRAL, 0.55F, 1.0F);
        } else if (committed && phaseTicks == PICKUP_TRANSFER_TICK) {
            publishCarriedPhase();
        }
        if (phaseTicks >= CRAFT_DURATION_TICKS) {
            startStorageDelivery(level);
        }
    }

    private void tickTravelToTable(ServerLevel level) {
        if (plan == null) {
            finished = true;
            return;
        }
        if (!LumbererSelfCraftingService.heartbeat(level, plan)
            || settlement == null || camp == null || request == null
            || !LumbererSelfCraftingService.reservationStillExact(level,
                settlement, camp, settler, request, plan)) {
            failPreContact(level, "reservation_invalid_en_route");
            return;
        }
        faceTable();
        if (atTableContact(level)) {
            stage = Stage.TABLE;
            phaseTicks = 0;
            settler.getNavigation().stop();
            craftFacing = facingToward(settler.blockPosition(), plan.tablePos());
            settler.setActivity(SettlerActivity.WORK_CRAFT);
            publishTablePhase(CraftPresentation.Phase.LAY_OUT, 0);
            return;
        }
        if (--repathIn <= 0) {
            if (!pathToTable() && ++failedPaths >= MAX_FAILED_PATHS) {
                settler.recordRouteFailure("lumber_self_craft:no_path");
                retryAt = level.getGameTime() + 100L;
                finished = true;
            }
        }
    }

    private void tickTravelToStorage(ServerLevel level) {
        CraftOutputEscrow escrow = settler.craftOutputEscrow();
        if (escrow == null || settlement == null || camp == null) {
            finished = true;
            return;
        }
        BlockPos available = LumbererSelfCraftingService.availableEscrowStorage(
            level, camp, escrow);
        if (available == null) {
            storageTarget = escrow.storageTarget();
            settler.getNavigation().stop();
            settler.setLogisticsStop(StopReason.CHEST_FULL, storageTarget,
                STORAGE_RETRY_TICKS);
            // Release MOVE while storage is full: the escrowed axe persists and
            // canUse resumes it, but sleep and other goals must not be locked
            // out by an unbounded same-priority wait.
            retryAt = level.getGameTime() + STORAGE_WAIT_RETRY_TICKS;
            finished = true;
            return;
        }
        if (!available.equals(escrow.storageTarget())) {
            settler.retargetCraftOutputEscrow(escrow.actionId(), available);
            escrow = settler.craftOutputEscrow();
        }
        storageTarget = available;
        settler.clearLogisticsStop();
        faceStorage();
        if (atStorageContact(level)) {
            beginDeposit(level, escrow);
            return;
        }
        if (--repathIn <= 0 && !pathToStorage()
            && ++failedPaths >= MAX_FAILED_PATHS) {
            settler.recordRouteFailure("lumber_self_craft:storage_no_path");
            settler.setLogisticsStop(StopReason.NO_PATH, storageTarget,
                STORAGE_RETRY_TICKS);
            repathIn = STORAGE_RETRY_TICKS;
            failedPaths = 0;
            retryAt = level.getGameTime() + STORAGE_WAIT_RETRY_TICKS;
            finished = true;
        }
    }

    private void tickDeposit(ServerLevel level) {
        if (storageTarget == null || settlement == null || camp == null) {
            finished = true;
            return;
        }
        faceStorage();
        depositTicks++;
        if (!deposited && depositTicks == DEPOSIT_CONTACT_TICK) {
            CraftOutputEscrow escrow = settler.craftOutputEscrow();
            UUID actionId = escrow == null ? null : escrow.actionId();
            deposited = actionId != null && atStorageContact(level)
                && LumbererSelfCraftingService.depositEscrow(level, settlement,
                    camp, settler, actionId);
            if (!deposited) {
                closeStorage(level);
                stage = Stage.TO_STORAGE;
                depositTicks = 0;
                settler.setActivity(SettlerActivity.TRAVELING);
                settler.setLogisticsStop(StopReason.CHEST_FULL, storageTarget,
                    STORAGE_RETRY_TICKS);
                publishCarriedPhase();
                repathIn = STORAGE_RETRY_TICKS;
                return;
            }
            level.playSound(null, storageTarget, ModSounds.CHEST_STOW.get(),
                SoundSource.NEUTRAL, 0.65F, 1.0F);
        }
        // The server-authoritative deposit happens on the authored contact
        // tick, but its read-only world projection survives that exact frame.
        // Removing it one tick later prevents the contact pose from reaching
        // into an already-empty chest without duplicating inventory ownership.
        if (deposited && depositTicks == DEPOSIT_CONTACT_TICK + 1) {
            UUID actionId = settler.craftPresentation().actionId();
            if (actionId != null) {
                settler.clearCraftPresentation(actionId);
            }
        }
        if (deposited && depositTicks >= DEPOSIT_DURATION_TICKS) {
            closeStorage(level);
            settler.clearLogisticsStop();
            finished = true;
        }
    }

    private boolean resolveEscrowContext() {
        CraftOutputEscrow escrow = settler.craftOutputEscrow();
        settlement = settler.settlement();
        camp = null;
        request = null;
        plan = null;
        if (escrow == null || settlement == null
            || !settlement.id.equals(escrow.settlementId())) {
            return false;
        }
        for (Building candidate : settlement.buildings) {
            if (candidate.id.equals(escrow.buildingId()) && candidate.valid
                && candidate.type == BuildingType.LUMBER_CAMP) {
                camp = candidate;
                break;
            }
        }
        if (camp == null) {
            return false;
        }
        request = EquipmentRequests.requestFor(camp, settler.getUUID());
        return true;
    }

    private void failPreContact(ServerLevel level, String reason) {
        if (plan != null) {
            settler.clearCraftPresentation(plan.actionId());
            LumbererSelfCraftingService.release(level, plan);
        }
        settler.recordRouteFailure("lumber_self_craft:" + reason);
        playCraftFailure(level);
        finished = true;
    }

    private void playCraftFailure(ServerLevel level) {
        BlockPos at = plan == null ? settler.blockPosition() : plan.tablePos();
        level.playSound(null, at, SoundEvents.CRAFTER_FAIL,
            SoundSource.BLOCKS, 0.45F, 0.92F);
    }

    private void publishTablePhase(CraftPresentation.Phase phase, int mask) {
        if (plan == null) {
            return;
        }
        settler.publishCraftPresentation(new CraftPresentation(plan.actionId(),
            plan.tablePos(), craftFacing, phase, mask, plan.recipeGrid(),
            plan.output()));
    }

    private void publishCarriedPhase() {
        CraftOutputEscrow escrow = settler.craftOutputEscrow();
        if (escrow == null) {
            return;
        }
        CraftPresentation current = settler.craftPresentation();
        List<ItemStack> grid = current.active()
            && escrow.actionId().equals(current.actionId())
                ? current.recipeGrid() : emptyRecipeGrid();
        settler.publishCraftPresentation(new CraftPresentation(
            escrow.actionId(), escrow.storageTarget(), craftFacing,
            CraftPresentation.Phase.CARRIED, 0, grid, escrow.output()));
    }

    private int visibleRecipeMask(int tick) {
        if (plan == null) {
            return 0;
        }
        List<ItemStack> grid = plan.recipeGrid();
        int contacts = 0;
        for (int contact : LAYOUT_CONTACT_TICKS) {
            if (tick >= contact) {
                contacts++;
            }
        }
        int mask = 0;
        for (int slot = 0; slot < grid.size() && contacts > 0; slot++) {
            if (!grid.get(slot).isEmpty()) {
                mask |= 1 << slot;
                contacts--;
            }
        }
        return mask;
    }

    private int occupiedRecipeMask() {
        if (plan == null) {
            return 0;
        }
        int mask = 0;
        List<ItemStack> grid = plan.recipeGrid();
        for (int slot = 0; slot < grid.size(); slot++) {
            if (!grid.get(slot).isEmpty()) {
                mask |= 1 << slot;
            }
        }
        return mask;
    }

    private void startStorageDelivery(ServerLevel level) {
        if (plan != null) {
            LumbererSelfCraftingService.release(level, plan);
            plan = null;
        }
        stage = Stage.TO_STORAGE;
        phaseTicks = CRAFT_DURATION_TICKS;
        repathIn = 0;
        failedPaths = 0;
        settler.setActivity(SettlerActivity.TRAVELING);
        publishCarriedPhase();
        beginOrRetryStorageRoute();
    }

    private void beginOrRetryStorageRoute() {
        if (!(settler.level() instanceof ServerLevel level)
            || camp == null || settler.craftOutputEscrow() == null) {
            finished = true;
            return;
        }
        CraftOutputEscrow escrow = settler.craftOutputEscrow();
        BlockPos available = LumbererSelfCraftingService.availableEscrowStorage(
            level, camp, escrow);
        storageTarget = available == null ? escrow.storageTarget() : available;
        if (available == null) {
            settler.setLogisticsStop(StopReason.CHEST_FULL, storageTarget,
                STORAGE_RETRY_TICKS);
            repathIn = STORAGE_RETRY_TICKS;
            return;
        }
        if (!available.equals(escrow.storageTarget())) {
            settler.retargetCraftOutputEscrow(escrow.actionId(), available);
        }
        settler.clearLogisticsStop();
        pathToStorage();
    }

    private boolean pathToStorage() {
        if (!(settler.level() instanceof ServerLevel level)
            || storageTarget == null) {
            return false;
        }
        BlockPos approach = approachTo(level, storageTarget,
            settler.blockPosition());
        boolean moving = settler.getNavigation().moveTo(approach.getX() + 0.5D,
            approach.getY(), approach.getZ() + 0.5D, 1.0D);
        repathIn = REPATH_TICKS;
        return moving;
    }

    private void faceStorage() {
        if (storageTarget != null) {
            settler.getLookControl().setLookAt(storageTarget.getX() + 0.5D,
                storageTarget.getY() + 0.6D, storageTarget.getZ() + 0.5D,
                30.0F, 30.0F);
        }
    }

    private boolean atStorageContact(ServerLevel level) {
        if (storageTarget == null
            || settler.blockPosition().distSqr(storageTarget)
                > TABLE_CONTACT_DISTANCE_SQR) {
            return false;
        }
        BlockHitResult hit = level.clip(new ClipContext(settler.getEyePosition(),
            Vec3.atCenterOf(storageTarget), ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, settler));
        return (hit.getType() == HitResult.Type.MISS
                || storageTarget.equals(hit.getBlockPos()))
            && level.getBlockEntity(storageTarget) instanceof Container;
    }

    private void beginDeposit(ServerLevel level, CraftOutputEscrow escrow) {
        stage = Stage.DEPOSIT;
        depositTicks = 0;
        deposited = false;
        failedPaths = 0;
        settler.getNavigation().stop();
        craftFacing = facingToward(settler.blockPosition(), storageTarget);
        settler.setActivity(SettlerActivity.STORE_CRAFT_OUTPUT);
        settler.publishCraftPresentation(new CraftPresentation(
            escrow.actionId(), storageTarget, craftFacing,
            CraftPresentation.Phase.DEPOSIT, 0, emptyRecipeGrid(),
            escrow.output()));
        var state = level.getBlockState(storageTarget);
        level.blockEvent(storageTarget, state.getBlock(), 1, 1);
    }

    private void closeStorage(ServerLevel level) {
        if (storageTarget != null) {
            var state = level.getBlockState(storageTarget);
            level.blockEvent(storageTarget, state.getBlock(), 1, 0);
        }
    }

    private static List<ItemStack> emptyRecipeGrid() {
        List<ItemStack> grid = new ArrayList<>(CraftPresentation.GRID_SIZE);
        for (int slot = 0; slot < CraftPresentation.GRID_SIZE; slot++) {
            grid.add(ItemStack.EMPTY);
        }
        return grid;
    }

    private static Direction facingToward(BlockPos from, BlockPos target) {
        if (from == null || target == null) {
            return Direction.NORTH;
        }
        int dx = target.getX() - from.getX();
        int dz = target.getZ() - from.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private boolean pathToTable() {
        if (!(settler.level() instanceof ServerLevel level) || plan == null) {
            return false;
        }
        BlockPos approach = approachTo(level, plan.tablePos(),
            settler.blockPosition());
        boolean moving = settler.getNavigation().moveTo(approach.getX() + 0.5D,
            approach.getY(), approach.getZ() + 0.5D, 1.0D);
        repathIn = REPATH_TICKS;
        return moving;
    }

    private void faceTable() {
        if (plan != null) {
            settler.getLookControl().setLookAt(plan.tablePos().getX() + 0.5D,
                plan.tablePos().getY() + 0.65D,
                plan.tablePos().getZ() + 0.5D, 30.0F, 30.0F);
        }
    }

    private boolean atTableContact(ServerLevel level) {
        if (plan == null
            || settler.blockPosition().distSqr(plan.tablePos())
                > TABLE_CONTACT_DISTANCE_SQR) {
            return false;
        }
        BlockHitResult hit = level.clip(new ClipContext(settler.getEyePosition(),
            Vec3.atCenterOf(plan.tablePos()), ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, settler));
        return hit.getType() == HitResult.Type.MISS
            || plan.tablePos().equals(hit.getBlockPos());
    }

    private boolean workContextReady() {
        if (settler.getProfession() != Profession.LUMBERER || !settler.isBound()
            || settler.isSleeping() || !settler.dayPhase().work()
            || !settler.getOffhandItem().isEmpty()
            || settler.placedWorkContainerPos() != null) {
            return false;
        }
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            if (!settler.bag.getItem(slot).isEmpty()) {
                return false; // existing haul/recovery remains priority six
            }
        }
        return true;
    }

    private boolean graceElapsed(ServerLevel level, EquipmentRequest active) {
        CompoundTag persistent = settler.getPersistentData();
        boolean sameRequest = persistent.hasUUID(GRACE_REQUEST_TAG)
            && persistent.getUUID(GRACE_REQUEST_TAG).equals(active.id());
        if (!sameRequest || !persistent.contains(GRACE_SINCE_TAG, Tag.TAG_LONG)) {
            persistent.putUUID(GRACE_REQUEST_TAG, active.id());
            persistent.putLong(GRACE_SINCE_TAG, level.getGameTime());
            return false;
        }
        long since = persistent.getLong(GRACE_SINCE_TAG);
        if (since < 0L || since > level.getGameTime()) {
            persistent.putLong(GRACE_SINCE_TAG, level.getGameTime());
            return false;
        }
        return level.getGameTime() - since
            >= LumbererSelfCraftingService.NO_STOCK_GRACE_TICKS;
    }

    private void clearGraceIfNoLongerLumberer() {
        if (settler.getProfession() != Profession.LUMBERER || !settler.isBound()) {
            clearGrace();
        }
    }

    private void clearGrace() {
        CompoundTag persistent = settler.getPersistentData();
        persistent.remove(GRACE_REQUEST_TAG);
        persistent.remove(GRACE_SINCE_TAG);
    }

    @Override
    public void stop() {
        CraftOutputEscrow escrow = settler.craftOutputEscrow();
        UUID actionId = escrow != null ? escrow.actionId()
            : plan == null ? null : plan.actionId();
        if (settler.level() instanceof ServerLevel level) {
            if (stage == Stage.DEPOSIT) {
                closeStorage(level);
            }
            if (plan != null) {
                LumbererSelfCraftingService.release(level, plan);
            }
        }
        settler.getNavigation().stop();
        if (settler.getActivity() == SettlerActivity.WORK_CRAFT
            || settler.getActivity() == SettlerActivity.STORE_CRAFT_OUTPUT
            || settler.getActivity() == SettlerActivity.TRAVELING) {
            settler.setActivity(SettlerActivity.IDLE);
        }
        if (actionId != null) {
            // Clear the fixed table/chest projection on every interruption.
            // Pre-contact this is the whole presentation; post-contact the
            // sole real axe remains protected by the escrow below.
            settler.clearCraftPresentation(actionId);
        }
        if (escrow != null) {
            // Interruption after contact may hide the table/chest interaction,
            // but it cannot discard or visually leave the sole output behind.
            publishCarriedPhase();
        }
        settlement = null;
        camp = null;
        request = null;
        plan = null;
        stage = Stage.TO_TABLE;
        contactAttempted = false;
        committed = false;
        deposited = false;
        finished = false;
        phaseTicks = 0;
        depositTicks = 0;
        storageTarget = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private static BlockPos approachTo(ServerLevel level, BlockPos table,
                                       BlockPos from) {
        BlockPos best = table;
        double bestDistance = Double.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = table.relative(direction);
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
