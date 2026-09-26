package com.hearthstead.entity.ai;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.CraftOutputEscrow;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequirement;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.equipment.WorkplaceStorage;
import com.hearthstead.settlement.warehouse.WarehouseStorage;
import com.hearthstead.settlement.work.WorkerStorageAuthority;
import com.hearthstead.settlement.workzone.WorkZoneService;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Bounded physical crafting authority for the Lumber Camp's emergency axe.
 *
 * <p>This is deliberately not a second production system. It recognizes one
 * vanilla recipe through the live {@link net.minecraft.world.item.crafting.RecipeManager},
 * consumes exactly three real planks and two real sticks already held by
 * linked Lumber Camp storage, and places the one assembled wooden axe back in
 * that same storage. It never converts logs, never owns virtual ingredients,
 * and never equips or satisfies an {@link EquipmentRequest}; the existing
 * worker acquisition goal still performs that later physical transfer.
 */
public final class LumbererSelfCraftingService {
    /** A stopped/dead worker cannot strand a real workbench indefinitely. */
    public static final long TABLE_CLAIM_TTL_TICKS = 100L;
    /** Existing acquisition/Courier routes receive five full scan intervals. */
    public static final long NO_STOCK_GRACE_TICKS = 200L;

    private static final int PLANKS_REQUIRED = 3;
    private static final int STICKS_REQUIRED = 2;
    private static final int MAX_TABLE_SCAN_VOLUME = 16_384;
    private static final double LOCAL_GROUND_AXE_RANGE =
        AcquireRequestedEquipmentGoal.SEARCH_RANGE;
    private static final String FIELD_COLLECTION_OWNER =
        "HearthsteadGroundCollectionOwner";
    private static final ResourceLocation VANILLA_WOODEN_AXE_RECIPE =
        ResourceLocation.withDefaultNamespace("wooden_axe");

    private static final Map<ServerLevel, Map<BlockPos, TableClaim>> TABLE_CLAIMS =
        new WeakHashMap<>();

    /** One exact physical slot observation consumed by a reserved craft. */
    public record IngredientUse(BlockPos containerPos, int slot,
                                ItemStack observed, int count) {
        public IngredientUse {
            containerPos = containerPos.immutable();
            observed = observed.copy();
            if (slot < 0 || count <= 0 || observed.isEmpty()
                || observed.getCount() < count) {
                throw new IllegalArgumentException("invalid lumber axe ingredient");
            }
        }
    }

    /**
     * Ephemeral intent only. The table and every input are re-read at contact;
     * no item moves when this plan is created.
     */
    public record CraftPlan(UUID actionId, UUID workerId, UUID settlementId,
                            UUID campId, UUID requestId, BlockPos tablePos,
                            BlockPos outputContainerPos,
                            ResourceLocation recipeId,
                            List<IngredientUse> ingredients,
                            List<ItemStack> recipeGrid, ItemStack output) {
        public CraftPlan {
            actionId = java.util.Objects.requireNonNull(actionId, "actionId");
            workerId = java.util.Objects.requireNonNull(workerId, "workerId");
            settlementId = java.util.Objects.requireNonNull(settlementId,
                "settlementId");
            campId = java.util.Objects.requireNonNull(campId, "campId");
            requestId = java.util.Objects.requireNonNull(requestId, "requestId");
            tablePos = tablePos.immutable();
            outputContainerPos = outputContainerPos.immutable();
            recipeId = java.util.Objects.requireNonNull(recipeId, "recipeId");
            ingredients = List.copyOf(ingredients);
            if (recipeGrid == null || recipeGrid.size() != 9) {
                throw new IllegalArgumentException("craft plan needs a 3x3 recipe grid");
            }
            recipeGrid = recipeGrid.stream().map(ItemStack::copy).toList();
            output = output.copy();
            if (output.isEmpty() || output.getCount() != 1) {
                throw new IllegalArgumentException("craft plan needs one exact output");
            }
        }

        @Override
        public List<ItemStack> recipeGrid() {
            return recipeGrid.stream().map(ItemStack::copy).toList();
        }

        @Override
        public ItemStack output() {
            return output.copy();
        }
    }

    private static final class TableClaim {
        private final UUID actionId;
        private final UUID workerId;
        private final UUID campId;
        private long expiresAtTick;
        private boolean committed;

        private TableClaim(UUID actionId, UUID workerId, UUID campId,
                           long expiresAtTick) {
            this.actionId = actionId;
            this.workerId = workerId;
            this.campId = campId;
            this.expiresAtTick = expiresAtTick;
        }
    }

    private record ContainerImage(BlockPos pos, Container container,
                                  List<ItemStack> before,
                                  List<ItemStack> after) {
    }

    /**
     * Finds and reserves one real workbench only after recipe and output-space
     * preflight succeed. The reservation never reserves or removes materials;
     * the contact transaction revalidates every source slot and fails closed.
     */
    @Nullable
    public static CraftPlan reserve(ServerLevel level, Settlement settlement,
                                    Building camp, SettlerEntity worker,
                                    EquipmentRequest request) {
        if (!validContext(level, settlement, camp, worker, request)) {
            return null;
        }
        if (existingSupplyWins(level, settlement, camp, worker, request)) {
            return null;
        }
        List<BlockPos> tables = craftingTables(level, camp,
            worker.blockPosition());
        if (tables.isEmpty()) {
            return null;
        }
        List<IngredientUse> ingredients = selectIngredients(level, camp);
        if (ingredients.isEmpty()) {
            return null;
        }
        List<ItemStack> recipeGrid = woodenAxeGrid(ingredients);
        ItemStack recipeOutput = resolveWoodenAxe(level, recipeGrid);
        BlockPos outputContainer = outputContainerAfterConsumption(level, camp,
            ingredients, recipeOutput);
        if (!validWoodenAxe(recipeOutput, request.requirement())
            || outputContainer == null) {
            return null;
        }

        long now = level.getGameTime();
        UUID actionId = UUID.randomUUID();
        synchronized (TABLE_CLAIMS) {
            Map<BlockPos, TableClaim> claims = claimLedger(level, now, true);
            for (BlockPos table : tables) {
                TableClaim existing = claims.get(table);
                if (existing != null && existing.expiresAtTick > now
                    && !existing.workerId.equals(worker.getUUID())) {
                    continue;
                }
                claims.put(table, new TableClaim(actionId, worker.getUUID(),
                    camp.id, now + TABLE_CLAIM_TTL_TICKS));
                return new CraftPlan(actionId, worker.getUUID(), settlement.id,
                    camp.id, request.id(), table, outputContainer,
                    VANILLA_WOODEN_AXE_RECIPE, ingredients, recipeGrid,
                    recipeOutput);
            }
        }
        return null;
    }

    /** Renews only the exact live reservation represented by this plan. */
    public static boolean heartbeat(ServerLevel level, CraftPlan plan) {
        if (level == null || plan == null) {
            return false;
        }
        synchronized (TABLE_CLAIMS) {
            Map<BlockPos, TableClaim> claims = claimLedger(level,
                level.getGameTime(), false);
            TableClaim claim = claims == null ? null : claims.get(plan.tablePos());
            if (!matches(claim, plan) || claim.committed) {
                return false;
            }
            claim.expiresAtTick = level.getGameTime() + TABLE_CLAIM_TTL_TICKS;
            return true;
        }
    }

    /**
     * The single authoritative table-contact operation. It re-resolves the
     * vanilla recipe from the exact live inputs, consumes them atomically and
     * creates one durable worker output escrow. Storage is deliberately not
     * touched here: the selected container may be several blocks away and is
     * reached through a later visible deposit contact.
     */
    public static boolean commit(ServerLevel level, Settlement settlement,
                                 Building camp, SettlerEntity worker,
                                 EquipmentRequest request, CraftPlan plan) {
        if (plan == null || !validContext(level, settlement, camp, worker, request)
            || !plan.workerId().equals(worker.getUUID())
            || !plan.settlementId().equals(settlement.id)
            || !plan.campId().equals(camp.id)
            || !plan.requestId().equals(request.id())
            || !validTable(level, camp, plan.tablePos())
            || !workerAtTable(level, worker, plan.tablePos())) {
            return false;
        }
        if (existingSupplyWins(level, settlement, camp, worker, request)) {
            return false;
        }

        synchronized (TABLE_CLAIMS) {
            Map<BlockPos, TableClaim> claims = claimLedger(level,
                level.getGameTime(), false);
            TableClaim claim = claims == null ? null : claims.get(plan.tablePos());
            if (!matches(claim, plan) || claim.committed) {
                return false;
            }

            List<ContainerImage> images = inventoryImages(level, camp);
            if (!ingredientsStillExact(images, plan.ingredients())) {
                return false;
            }
            List<ItemStack> liveGrid = woodenAxeGrid(plan.ingredients());
            ItemStack output = resolveWoodenAxe(level, liveGrid);
            if (!validWoodenAxe(output, request.requirement())
                || !sameGrid(liveGrid, plan.recipeGrid())
                || !ItemStack.isSameItemSameComponents(output, plan.output())
                || output.getCount() != plan.output().getCount()
                || !worker.canBeginCraftOutputEscrow(plan.actionId(), output)
                || !simulateConsumption(images, plan.ingredients())) {
                return false;
            }

            long beforeItems = totalItems(images, true);
            boolean escrowCreated = false;
            try {
                apply(images);
                if (!matchesAfter(images)
                    || totalItems(images, false) != beforeItems
                        - PLANKS_REQUIRED - STICKS_REQUIRED) {
                    restore(images);
                    return false;
                }
                escrowCreated = worker.beginCraftOutputEscrow(plan.actionId(),
                    settlement.id, camp.id, plan.outputContainerPos(), output);
                if (!escrowCreated) {
                    restore(images);
                    return false;
                }
            } catch (RuntimeException unexpected) {
                if (escrowCreated) {
                    worker.clearCraftOutputEscrow(plan.actionId(), output);
                }
                restore(images);
                Hearthstead.LOGGER.error(
                    "HSQA_LUMBER_SELF_CRAFT event=rollback worker={} camp={} table={} reason=container_exception",
                    worker.getUUID(), camp.id, plan.tablePos(), unexpected);
                return false;
            }

            claim.committed = true;
            WarehouseStorage.refreshed(level, camp);
            Hearthstead.LOGGER.info(
                "HSQA_LUMBER_SELF_CRAFT event=table_commit worker={} settlement={} camp={} request={} table={} storage={} action={} recipe=minecraft:wooden_axe planks={} sticks={} escrow=1",
                worker.getUUID(), settlement.id, camp.id, request.id(),
                plan.tablePos(), plan.outputContainerPos(), plan.actionId(),
                PLANKS_REQUIRED, STICKS_REQUIRED);
            return true;
        }
    }

    /**
     * Per-tick reservation heartbeat for visible tabletop props. Any changed
     * source slot invalidates the projection before the next client frame;
     * no inventory mutation has happened yet, so the action simply clears.
     */
    public static boolean reservationStillExact(ServerLevel level,
                                                Settlement settlement,
                                                Building camp,
                                                SettlerEntity worker,
                                                EquipmentRequest request,
                                                CraftPlan plan) {
        if (plan == null || !validContext(level, settlement, camp, worker,
                request) || !plan.workerId().equals(worker.getUUID())
            || !plan.settlementId().equals(settlement.id)
            || !plan.campId().equals(camp.id)
            || !plan.requestId().equals(request.id())
            || !validTable(level, camp, plan.tablePos())) {
            return false;
        }
        synchronized (TABLE_CLAIMS) {
            Map<BlockPos, TableClaim> claims = claimLedger(level,
                level.getGameTime(), false);
            TableClaim claim = claims == null ? null : claims.get(plan.tablePos());
            if (!matches(claim, plan) || claim.committed) {
                return false;
            }
            List<ContainerImage> images = inventoryImages(level, camp);
            if (!ingredientsStillExact(images, plan.ingredients())) {
                return false;
            }
            List<ItemStack> liveGrid = woodenAxeGrid(plan.ingredients());
            ItemStack liveOutput = resolveWoodenAxe(level, liveGrid);
            return sameGrid(liveGrid, plan.recipeGrid())
                && ItemStack.isSameItemSameComponents(liveOutput, plan.output())
                && liveOutput.getCount() == plan.output().getCount();
        }
    }

    /** Exact recipe-slot copies for the generic server presentation API. */
    public static List<ItemStack> presentationGrid(CraftPlan plan) {
        return plan == null ? List.of() : plan.recipeGrid();
    }

    /**
     * Finds a currently valid linked container for the escrow, preferring the
     * originally reserved destination. No item moves and a full/missing camp
     * simply returns {@code null} for the goal's visible blocker/retry path.
     */
    @Nullable
    public static BlockPos availableEscrowStorage(ServerLevel level,
                                                  Building camp,
                                                  CraftOutputEscrow escrow) {
        if (level == null || camp == null || escrow == null || !camp.valid
            || !camp.id.equals(escrow.buildingId())) {
            return null;
        }
        List<BlockPos> loaded = WorkerStorageAuthority.loadedContainers(level,
            camp);
        if (loaded.contains(escrow.storageTarget())
            && containerAccepts(level, escrow.storageTarget(), escrow.output())) {
            return escrow.storageTarget();
        }
        for (BlockPos candidate : loaded) {
            if (containerAccepts(level, candidate, escrow.output())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Second and final authority contact: insert the escrow's exact stack in
     * its selected real container, then compare-and-clear escrow. If either
     * side fails, the complete container image is restored and escrow remains
     * the sole owner.
     */
    public static boolean depositEscrow(ServerLevel level, Settlement settlement,
                                        Building camp, SettlerEntity worker,
                                        UUID actionId) {
        CraftOutputEscrow escrow = worker == null
            ? null : worker.craftOutputEscrow();
        if (level == null || settlement == null || camp == null || worker == null
            || escrow == null || actionId == null
            || worker.level() != level
            || !actionId.equals(escrow.actionId())
            || !settlement.id.equals(escrow.settlementId())
            || !camp.id.equals(escrow.buildingId()) || !camp.valid
            || !WorkerStorageAuthority.loadedContainers(level, camp)
                .contains(escrow.storageTarget())
            || !(level.getBlockEntity(escrow.storageTarget())
                instanceof Container container)) {
            return false;
        }
        ItemStack output = escrow.output();
        List<ItemStack> before = snapshot(container);
        List<ItemStack> after = deepCopy(before);
        if (!insertExact(after, container.getMaxStackSize(), output)) {
            return false;
        }
        long countBefore = countItems(before);
        try {
            writeContainer(container, after);
            if (!containerMatches(container, after)
                || countItems(after) != countBefore + output.getCount()) {
                writeContainer(container, before);
                return false;
            }
            if (!worker.clearCraftOutputEscrow(actionId, output)) {
                writeContainer(container, before);
                return false;
            }
        } catch (RuntimeException unexpected) {
            writeContainer(container, before);
            Hearthstead.LOGGER.error(
                "HSQA_LUMBER_SELF_CRAFT event=deposit_rollback worker={} camp={} storage={} action={} reason=container_exception",
                worker.getUUID(), camp.id, escrow.storageTarget(), actionId,
                unexpected);
            return false;
        }
        WarehouseStorage.refreshed(level, camp);
        Hearthstead.LOGGER.info(
            "HSQA_LUMBER_SELF_CRAFT event=deposit worker={} settlement={} camp={} storage={} action={} output={}",
            worker.getUUID(), settlement.id, camp.id, escrow.storageTarget(),
            actionId, BuiltInRegistries.ITEM.getKey(output.getItem()));
        return true;
    }

    /** Prompt release on completion/interruption; stale leases are a backstop. */
    public static void release(ServerLevel level, CraftPlan plan) {
        if (level == null || plan == null) {
            return;
        }
        synchronized (TABLE_CLAIMS) {
            Map<BlockPos, TableClaim> claims = claimLedger(level,
                level.getGameTime(), false);
            TableClaim claim = claims == null ? null : claims.get(plan.tablePos());
            if (matches(claim, plan)) {
                claims.remove(plan.tablePos());
                if (claims.isEmpty()) {
                    TABLE_CLAIMS.remove(level);
                }
            }
        }
    }

    /**
     * Priority gate shared by canUse and the contact wind-up. A physical axe
     * already on the worker, in their bag, on eligible nearby ground, in camp
     * storage, in valid warehouse stock, or reserved/in transit for this exact
     * request always wins over crafting another one.
     */
    public static boolean existingSupplyWins(ServerLevel level,
                                             Settlement settlement,
                                             Building camp,
                                             SettlerEntity worker,
                                             EquipmentRequest request) {
        if (!validContext(level, settlement, camp, worker, request)) {
            return true;
        }
        EquipmentRequirement requirement = request.requirement();
        if (requirement.serviceable(worker.getItemBySlot(EquipmentSlot.MAINHAND))) {
            return true;
        }
        for (int slot = 0; slot < worker.bag.getContainerSize(); slot++) {
            if (requirement.serviceable(worker.bag.getItem(slot))) {
                return true;
            }
        }
        if (WorkplaceStorage.hasMatching(level, camp, requirement)) {
            return true;
        }
        AABB nearby = worker.getBoundingBox().inflate(LOCAL_GROUND_AXE_RANGE,
            4.0D, LOCAL_GROUND_AXE_RANGE);
        if (!level.getEntitiesOfClass(ItemEntity.class, nearby,
                item -> item.isAlive()
                    && !item.getPersistentData().hasUUID(FIELD_COLLECTION_OWNER)
                    && requirement.serviceable(item.getItem())).isEmpty()) {
            return true;
        }
        if (request.status() == EquipmentRequest.Status.CLAIMED
            || request.traceStage() == EquipmentRequest.TraceStage.COURIER_BAG) {
            return true;
        }
        for (Building building : settlement.buildings) {
            if (!building.valid || building.type != BuildingType.WAREHOUSE) {
                continue;
            }
            for (BlockPos containerPos : WorkerStorageAuthority.loadedContainers(
                    level, building)) {
                BlockEntity blockEntity = level.getBlockEntity(containerPos);
                if (!(blockEntity instanceof Container container)) {
                    continue;
                }
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    if (requirement.serviceable(container.getItem(slot))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Public deterministic reservation window for focused GameTests. */
    @Nullable
    public static UUID tableClaimant(ServerLevel level, BlockPos tablePos) {
        synchronized (TABLE_CLAIMS) {
            Map<BlockPos, TableClaim> claims = claimLedger(level,
                level.getGameTime(), false);
            TableClaim claim = claims == null ? null : claims.get(tablePos);
            return claim == null ? null : claim.workerId;
        }
    }

    /** Public deterministic recipe proof for focused GameTests. */
    public static boolean resolvesVanillaWoodenAxe(ServerLevel level,
                                                   Building camp) {
        List<IngredientUse> ingredients = selectIngredients(level, camp);
        return !ingredients.isEmpty()
            && validWoodenAxe(resolveWoodenAxe(level,
                    woodenAxeGrid(ingredients)),
                EquipmentRequests.requirementFor(Profession.LUMBERER));
    }

    private static boolean validContext(ServerLevel level,
                                        Settlement settlement,
                                        Building camp,
                                        SettlerEntity worker,
                                        EquipmentRequest request) {
        if (level == null || settlement == null || camp == null || worker == null
            || request == null || worker.level() != level || !worker.isBound()
            || worker.settlement() != settlement
            || worker.getProfession() != Profession.LUMBERER
            || !camp.valid || camp.type != BuildingType.LUMBER_CAMP
            || !camp.id.equals(request.destinationBuildingId())
            || !worker.getUUID().equals(request.requesterId())
            || request.profession() != Profession.LUMBERER) {
            return false;
        }
        Building employer = Employment.employerOf(settlement, worker.getUUID());
        return employer == camp
            && Employment.professionOf(settlement, worker.getUUID())
                == Profession.LUMBERER
            && EquipmentRequests.requestFor(camp, worker.getUUID()) == request
            && !request.cancelPending() && !request.traceQuarantined()
            && request.status() == EquipmentRequest.Status.OPEN
            && !request.requirement().serviceable(worker.getMainHandItem());
    }

    private static List<BlockPos> craftingTables(ServerLevel level,
                                                  Building camp,
                                                  BlockPos from) {
        if (camp.bounds == null || !withinTableScanBudget(camp.bounds)) {
            return List.of();
        }
        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BoundingBox bounds = camp.bounds;
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    cursor.set(x, y, z);
                    if (validTable(level, camp, cursor)) {
                        found.add(cursor.immutable());
                    }
                }
            }
        }
        found.sort(java.util.Comparator.comparingDouble(from::distSqr));
        return List.copyOf(found);
    }

    private static boolean validTable(ServerLevel level, Building camp,
                                      BlockPos table) {
        return level != null && camp != null && table != null && camp.valid
            && camp.type == BuildingType.LUMBER_CAMP && camp.contains(table)
            && WorkZoneService.livePositionAvailable(level, table)
            && level.getBlockState(table).is(Blocks.CRAFTING_TABLE);
    }

    private static boolean workerAtTable(ServerLevel level,
                                         SettlerEntity worker,
                                         BlockPos table) {
        if (worker.blockPosition().distSqr(table) > 2.25D) {
            return false;
        }
        BlockHitResult hit = level.clip(new ClipContext(worker.getEyePosition(),
            Vec3.atCenterOf(table), ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, worker));
        return hit.getType() == HitResult.Type.MISS
            || table.equals(hit.getBlockPos());
    }

    private static boolean withinTableScanBudget(BoundingBox bounds) {
        long sx = (long) bounds.maxX() - bounds.minX() + 1L;
        long sy = (long) bounds.maxY() - bounds.minY() + 1L;
        long sz = (long) bounds.maxZ() - bounds.minZ() + 1L;
        if (sx <= 0L || sy <= 0L || sz <= 0L
            || sx > MAX_TABLE_SCAN_VOLUME || sy > MAX_TABLE_SCAN_VOLUME
            || sz > MAX_TABLE_SCAN_VOLUME) {
            return false;
        }
        long xy = sx * sy;
        return xy <= MAX_TABLE_SCAN_VOLUME
            && xy * sz <= MAX_TABLE_SCAN_VOLUME;
    }

    private static List<IngredientUse> selectIngredients(ServerLevel level,
                                                          Building camp) {
        List<IngredientUse> uses = new ArrayList<>();
        List<ItemStack> plankUnits = new ArrayList<>();
        List<ItemStack> stickUnits = new ArrayList<>();
        int planksLeft = PLANKS_REQUIRED;
        int sticksLeft = STICKS_REQUIRED;
        for (BlockPos pos : WorkerStorageAuthority.loadedContainers(level, camp)) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof Container container)) {
                continue;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                int take = 0;
                if (planksLeft > 0 && stack.is(ItemTags.PLANKS)) {
                    take = Math.min(planksLeft, stack.getCount());
                    planksLeft -= take;
                    for (int i = 0; i < take; i++) {
                        plankUnits.add(stack.copyWithCount(1));
                    }
                } else if (sticksLeft > 0 && stack.is(Items.STICK)) {
                    take = Math.min(sticksLeft, stack.getCount());
                    sticksLeft -= take;
                    for (int i = 0; i < take; i++) {
                        stickUnits.add(stack.copyWithCount(1));
                    }
                }
                if (take > 0) {
                    uses.add(new IngredientUse(pos, slot, stack, take));
                }
                if (planksLeft == 0 && sticksLeft == 0) {
                    return withRecipeOrder(uses, plankUnits, stickUnits);
                }
            }
        }
        return List.of();
    }

    /**
     * Ingredient-use order is also the recipe-grid unit order. Store the
     * selected units as synthetic one-count entries after the physical uses,
     * encoded only in this method's stable ordering via {@link #recipeUnits}.
     */
    private static List<IngredientUse> withRecipeOrder(List<IngredientUse> uses,
                                                        List<ItemStack> planks,
                                                        List<ItemStack> sticks) {
        if (planks.size() != PLANKS_REQUIRED || sticks.size() != STICKS_REQUIRED) {
            return List.of();
        }
        // Physical-use ordering already groups the deterministic container
        // scan. recipeUnits reconstructs the same three/two unit streams.
        return List.copyOf(uses);
    }

    private static List<ItemStack> recipeUnits(List<IngredientUse> uses,
                                                boolean planks) {
        List<ItemStack> units = new ArrayList<>();
        for (IngredientUse use : uses) {
            boolean accepted = planks ? use.observed().is(ItemTags.PLANKS)
                : use.observed().is(Items.STICK);
            if (!accepted) {
                continue;
            }
            for (int i = 0; i < use.count(); i++) {
                units.add(use.observed().copyWithCount(1));
            }
        }
        return units;
    }

    private static List<ItemStack> woodenAxeGrid(
            List<IngredientUse> ingredients) {
        List<ItemStack> planks = recipeUnits(ingredients, true);
        List<ItemStack> sticks = recipeUnits(ingredients, false);
        if (planks.size() != PLANKS_REQUIRED || sticks.size() != STICKS_REQUIRED) {
            return List.of();
        }
        List<ItemStack> slots = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            slots.add(ItemStack.EMPTY);
        }
        // Vanilla wooden_axe shaped recipe: XX / X# /  #.
        slots.set(0, planks.get(0));
        slots.set(1, planks.get(1));
        slots.set(3, planks.get(2));
        slots.set(4, sticks.get(0));
        slots.set(7, sticks.get(1));
        return List.copyOf(slots);
    }

    private static ItemStack resolveWoodenAxe(ServerLevel level,
                                               List<ItemStack> recipeGrid) {
        if (recipeGrid == null || recipeGrid.size() != 9) {
            return ItemStack.EMPTY;
        }
        CraftingInput input = CraftingInput.of(3, 3,
            recipeGrid.stream().map(ItemStack::copy).toList());
        Optional<RecipeHolder<CraftingRecipe>> match = level.getRecipeManager()
            .getRecipeFor(RecipeType.CRAFTING, input, level);
        return match.filter(holder -> VANILLA_WOODEN_AXE_RECIPE.equals(
                holder.id()))
            .map(holder -> holder.value().assemble(input, level.registryAccess()))
            .orElse(ItemStack.EMPTY);
    }

    private static boolean validWoodenAxe(@Nullable ItemStack output,
                                          @Nullable EquipmentRequirement requirement) {
        return output != null && !output.isEmpty() && output.getCount() == 1
            && output.is(Items.WOODEN_AXE) && output.getDamageValue() == 0
            && requirement != null && requirement.serviceable(output);
    }

    @Nullable
    private static BlockPos outputContainerAfterConsumption(ServerLevel level,
                                                             Building camp,
                                                             List<IngredientUse> ingredients,
                                                             ItemStack output) {
        List<ContainerImage> images = inventoryImages(level, camp);
        if (!ingredientsStillExact(images, ingredients)
            || !simulateConsumption(images, ingredients)) {
            return null;
        }
        for (ContainerImage image : images) {
            if (listAccepts(image.after(), image.container().getMaxStackSize(),
                    output)) {
                return image.pos();
            }
        }
        return null;
    }

    private static List<ContainerImage> inventoryImages(ServerLevel level,
                                                         Building camp) {
        List<ContainerImage> images = new ArrayList<>();
        for (BlockPos pos : WorkerStorageAuthority.loadedContainers(level, camp)) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof Container container)) {
                continue;
            }
            List<ItemStack> before = snapshot(container);
            images.add(new ContainerImage(pos, container, before,
                deepCopy(before)));
        }
        return images;
    }

    private static boolean ingredientsStillExact(List<ContainerImage> images,
                                                 List<IngredientUse> uses) {
        Map<BlockPos, ContainerImage> byPos = new HashMap<>();
        for (ContainerImage image : images) {
            byPos.put(image.pos(), image);
        }
        Map<String, Integer> cumulative = new HashMap<>();
        for (IngredientUse use : uses) {
            ContainerImage image = byPos.get(use.containerPos());
            if (image == null || use.slot() >= image.before().size()) {
                return false;
            }
            ItemStack live = image.before().get(use.slot());
            if (!ItemStack.isSameItemSameComponents(live, use.observed())
                || live.getCount() != use.observed().getCount()) {
                return false;
            }
            String key = use.containerPos().asLong() + ":" + use.slot();
            int consumed = cumulative.merge(key, use.count(), Integer::sum);
            if (consumed > live.getCount()) {
                return false;
            }
        }
        return true;
    }

    private static boolean simulateConsumption(List<ContainerImage> images,
                                               List<IngredientUse> uses) {
        Map<BlockPos, ContainerImage> byPos = new LinkedHashMap<>();
        for (ContainerImage image : images) {
            byPos.put(image.pos(), image);
            for (int slot = 0; slot < image.before().size(); slot++) {
                image.after().set(slot, image.before().get(slot).copy());
            }
        }
        for (IngredientUse use : uses) {
            ContainerImage image = byPos.get(use.containerPos());
            if (image == null || use.slot() >= image.after().size()) {
                return false;
            }
            ItemStack stack = image.after().get(use.slot());
            if (!ItemStack.isSameItemSameComponents(stack, use.observed())
                || stack.getCount() < use.count()) {
                return false;
            }
            stack = stack.copy();
            stack.shrink(use.count());
            image.after().set(use.slot(), stack.isEmpty()
                ? ItemStack.EMPTY : stack);
        }
        return true;
    }

    private static boolean sameGrid(List<ItemStack> left,
                                    List<ItemStack> right) {
        if (left == null || right == null || left.size() != 9
            || right.size() != 9) {
            return false;
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack a = left.get(slot);
            ItemStack b = right.get(slot);
            if (a.getCount() != b.getCount()
                || !ItemStack.isSameItemSameComponents(a, b)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containerAccepts(ServerLevel level, BlockPos pos,
                                            ItemStack stack) {
        return level.getBlockEntity(pos) instanceof Container container
            && listAccepts(snapshot(container), container.getMaxStackSize(),
                stack);
    }

    private static boolean listAccepts(List<ItemStack> contents,
                                       int containerMaxStackSize,
                                       ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        for (ItemStack existing : contents) {
            if (existing.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(existing, stack)
                && existing.getCount() < Math.min(existing.getMaxStackSize(),
                    containerMaxStackSize)) {
                return true;
            }
        }
        return false;
    }

    /** Mutates only the supplied bounded image, never the live container. */
    private static boolean insertExact(List<ItemStack> contents,
                                       int containerMaxStackSize,
                                       ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < contents.size()
             && !remaining.isEmpty(); slot++) {
            ItemStack existing = contents.get(slot);
            if (existing.isEmpty()
                || !ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }
            int room = Math.min(existing.getMaxStackSize(),
                containerMaxStackSize) - existing.getCount();
            int moved = Math.min(Math.max(0, room), remaining.getCount());
            if (moved > 0) {
                ItemStack grown = existing.copy();
                grown.grow(moved);
                contents.set(slot, grown);
                remaining.shrink(moved);
            }
        }
        for (int slot = 0; slot < contents.size()
             && !remaining.isEmpty(); slot++) {
            if (!contents.get(slot).isEmpty()) {
                continue;
            }
            int moved = Math.min(Math.min(remaining.getMaxStackSize(),
                containerMaxStackSize), remaining.getCount());
            contents.set(slot, remaining.copyWithCount(moved));
            remaining.shrink(moved);
        }
        return remaining.isEmpty();
    }

    private static void writeContainer(Container container,
                                       List<ItemStack> contents) {
        if (container.getContainerSize() != contents.size()) {
            throw new IllegalStateException("container size changed mid-craft");
        }
        for (int slot = 0; slot < contents.size(); slot++) {
            container.setItem(slot, contents.get(slot).copy());
        }
        container.setChanged();
    }

    private static boolean containerMatches(Container container,
                                            List<ItemStack> expected) {
        if (container.getContainerSize() != expected.size()) {
            return false;
        }
        for (int slot = 0; slot < expected.size(); slot++) {
            ItemStack actual = container.getItem(slot);
            ItemStack wanted = expected.get(slot);
            if (actual.getCount() != wanted.getCount()
                || !ItemStack.isSameItemSameComponents(actual, wanted)) {
                return false;
            }
        }
        return true;
    }

    private static long countItems(List<ItemStack> stacks) {
        long total = 0L;
        for (ItemStack stack : stacks) {
            total += stack.getCount();
        }
        return total;
    }

    private static void apply(List<ContainerImage> images) {
        for (ContainerImage image : images) {
            for (int slot = 0; slot < image.after().size(); slot++) {
                image.container().setItem(slot, image.after().get(slot).copy());
            }
            image.container().setChanged();
        }
    }

    private static boolean matchesAfter(List<ContainerImage> images) {
        for (ContainerImage image : images) {
            for (int slot = 0; slot < image.after().size(); slot++) {
                ItemStack expected = image.after().get(slot);
                ItemStack actual = image.container().getItem(slot);
                if (expected.getCount() != actual.getCount()
                    || !ItemStack.isSameItemSameComponents(expected, actual)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void restore(List<ContainerImage> images) {
        for (ContainerImage image : images) {
            for (int slot = 0; slot < image.before().size(); slot++) {
                image.container().setItem(slot, image.before().get(slot).copy());
            }
            image.container().setChanged();
        }
    }

    private static long totalItems(List<ContainerImage> images,
                                   boolean snapshots) {
        long total = 0L;
        for (ContainerImage image : images) {
            List<ItemStack> stacks = snapshots ? image.before() : null;
            for (int slot = 0; slot < image.container().getContainerSize(); slot++) {
                total += snapshots ? stacks.get(slot).getCount()
                    : image.container().getItem(slot).getCount();
            }
        }
        return total;
    }

    private static List<ItemStack> snapshot(Container container) {
        List<ItemStack> stacks = new ArrayList<>(container.getContainerSize());
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            stacks.add(container.getItem(slot).copy());
        }
        return stacks;
    }

    private static List<ItemStack> deepCopy(List<ItemStack> stacks) {
        List<ItemStack> copy = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            copy.add(stack.copy());
        }
        return copy;
    }

    private static boolean matches(@Nullable TableClaim claim, CraftPlan plan) {
        return claim != null && claim.actionId.equals(plan.actionId())
            && claim.workerId.equals(plan.workerId())
            && claim.campId.equals(plan.campId());
    }

    @Nullable
    private static Map<BlockPos, TableClaim> claimLedger(ServerLevel level,
                                                         long now,
                                                         boolean create) {
        Map<BlockPos, TableClaim> claims = TABLE_CLAIMS.get(level);
        if (claims == null && create) {
            claims = new HashMap<>();
            TABLE_CLAIMS.put(level, claims);
        }
        if (claims != null) {
            claims.entrySet().removeIf(row -> row.getValue().expiresAtTick <= now);
            if (claims.isEmpty() && !create) {
                TABLE_CLAIMS.remove(level);
                return null;
            }
        }
        return claims;
    }

    private LumbererSelfCraftingService() {
    }
}
