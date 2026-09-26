package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.CraftOutputEscrow;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.LumbererSelfCraftingService;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Deterministic P0 coverage for Lumber Camp physical axe self-maintenance. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class LumbererSelfCraftGameTests {

    /**
     * A real camp axe and a real request-owned Courier bag load each suppress
     * the fallback without consuming one plank or stick.
     */
    @GameTest(template = "empty16", timeoutTicks = 120,
        batch = "lumber_self_craft_priority")
    public void localAndCourierAxesBeatSelfCraft(GameTestHelper helper) {
        Fixture f = fixture(helper, 1, true);
        putIngredients(f.chest(), 3, 2);
        f.chest().setItem(2, new ItemStack(Items.STONE_AXE));
        EquipmentRequest request = f.requests().getFirst();

        helper.assertTrue(LumbererSelfCraftingService.existingSupplyWins(
                f.level(), f.settlement(), f.camp(), f.workers().getFirst(),
                request),
            "one real serviceable axe already in camp storage must win");
        helper.assertTrue(LumbererSelfCraftingService.reserve(f.level(),
                f.settlement(), f.camp(), f.workers().getFirst(), request) == null,
            "the fallback must not reserve a table while local axe stock exists");
        assertIngredients(helper, f.chest(), 3, 2,
            "local-stock priority must not consume materials");

        f.chest().setItem(2, ItemStack.EMPTY);
        Building warehouse = GameTestFixtures.register(helper, f.settlement(),
            BuildingType.WAREHOUSE, 1, 10);
        BlockPos warehouseChestRel = new BlockPos(2, 1, 11);
        helper.setBlock(warehouseChestRel, Blocks.CHEST);
        BlockPos warehouseChestAbs = helper.absolutePos(warehouseChestRel);
        Container warehouseChest = (Container) f.level().getBlockEntity(
            warehouseChestAbs);
        warehouseChest.setItem(0, new ItemStack(Items.IRON_AXE));

        SettlerEntity courier = employed(helper, f.settlement(), warehouse,
            "Runa", new BlockPos(2, 1, 9));
        helper.assertTrue(EquipmentRequests.claim(f.level(), f.settlement(),
                request.id(), courier.getUUID()),
            "fixture: real Courier must claim the persistent request");
        helper.assertTrue(EquipmentRequests.bindRoute(f.level(), f.settlement(),
                request.id(), courier, warehouse, warehouseChestAbs, 0,
                f.camp(), f.chestPos(), new ItemStack(Items.IRON_AXE)),
            "fixture: request must bind to exact real source and camp target");
        ItemStack physicalAxe = warehouseChest.removeItem(0, 1);
        warehouseChest.setChanged();
        helper.assertTrue(courier.bag.addItem(physicalAxe).isEmpty(),
            "fixture: the one real axe must enter the Courier bag");
        helper.assertTrue(EquipmentRequests.markPickedUp(f.level(),
                f.settlement(), request.id(), courier),
            "fixture: request trace must prove the real in-transit axe");
        helper.assertTrue(request.traceStage()
                == EquipmentRequest.TraceStage.COURIER_BAG,
            "priority proof requires a real Courier-bag trace, got "
                + request.traceStage());

        helper.assertTrue(LumbererSelfCraftingService.existingSupplyWins(
                f.level(), f.settlement(), f.camp(), f.workers().getFirst(),
                request),
            "a request-owned physical axe already in Courier transit must win");
        helper.assertTrue(LumbererSelfCraftingService.reserve(f.level(),
                f.settlement(), f.camp(), f.workers().getFirst(), request) == null,
            "in-transit priority must prevent a competing self-craft plan");
        assertIngredients(helper, f.chest(), 3, 2,
            "Courier-transit priority must not consume materials");
        helper.succeed();
    }

    /** RecipeManager resolution and the exact 3+2 -> 1 physical transaction. */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "lumber_self_craft_recipe_commit")
    public void recipeManagerCraftConsumesExactMaterialsOnce(GameTestHelper helper) {
        Fixture f = fixture(helper, 1, true);
        putIngredients(f.chest(), 3, 2);
        SettlerEntity worker = f.workers().getFirst();
        EquipmentRequest request = f.requests().getFirst();

        helper.assertTrue(LumbererSelfCraftingService.resolvesVanillaWoodenAxe(
                f.level(), f.camp()),
            "the selected physical 3x3 grid must resolve through RecipeManager");
        LumbererSelfCraftingService.CraftPlan plan =
            LumbererSelfCraftingService.reserve(f.level(), f.settlement(),
                f.camp(), worker, request);
        helper.assertTrue(plan != null,
            "three real planks, two sticks and one in-bounds table must plan");
        helper.assertTrue(plan.tablePos().equals(f.tablePos()),
            "the reserved target must be the exact in-bounds crafting table");
        assertWoodenAxeGrid(helper, plan.recipeGrid());
        helper.assertTrue(worker.getMainHandItem().isEmpty(),
            "planning may not project or equip a free axe");

        helper.assertTrue(LumbererSelfCraftingService.commit(f.level(),
                f.settlement(), f.camp(), worker, request, plan),
            "the authored contact must commit the exact vanilla recipe once");
        assertIngredients(helper, f.chest(), 0, 0,
            "one commit must consume exactly three planks and two sticks");
        helper.assertTrue(count(f.chest(), Items.WOODEN_AXE) == 0,
            "table contact may not teleport output into distant storage");
        CraftOutputEscrow escrow = worker.craftOutputEscrow();
        helper.assertTrue(escrow != null
                && escrow.actionId().equals(plan.actionId())
                && escrow.output().is(Items.WOODEN_AXE)
                && escrow.output().getCount() == 1,
            "tick-30 contact must create exactly one action-owned output escrow");
        helper.assertTrue(worker.getMainHandItem().isEmpty(),
            "crafting escrows the axe; pickup/equip remains a later real action");
        helper.assertTrue(EquipmentRequests.requestFor(f.camp(),
                worker.getUUID()) == request,
            "crafting alone may not close the worker's persistent request");

        helper.assertFalse(LumbererSelfCraftingService.commit(f.level(),
                f.settlement(), f.camp(), worker, request, plan),
            "replaying the same contact plan must be idempotently refused");
        helper.assertTrue(worker.craftOutputEscrow() != null
                && worker.craftOutputEscrow().output().getCount() == 1
                && count(f.chest(), Items.WOODEN_AXE) == 0,
            "a replay must not duplicate or move the one escrow output");
        helper.assertTrue(LumbererSelfCraftingService.depositEscrow(f.level(),
                f.settlement(), f.camp(), worker, plan.actionId()),
            "the later storage-contact operation must move the exact escrow");
        helper.assertTrue(worker.craftOutputEscrow() == null
                && count(f.chest(), Items.WOODEN_AXE) == 1,
            "after deposit the axe must exist in exactly one real storage slot");
        helper.succeed();
    }

    /** Any source-slot drift invalidates the visible action before contact. */
    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "lumber_self_craft_source_binding")
    public void reservedRecipeProjectionFailsClosedOnSourceSlotDrift(
            GameTestHelper helper) {
        Fixture f = fixture(helper, 1, true);
        putIngredients(f.chest(), 3, 2);
        SettlerEntity worker = f.workers().getFirst();
        EquipmentRequest request = f.requests().getFirst();
        LumbererSelfCraftingService.CraftPlan plan =
            LumbererSelfCraftingService.reserve(f.level(), f.settlement(),
                f.camp(), worker, request);
        helper.assertTrue(plan != null,
            "fixture: exact source slots must reserve one action");
        assertWoodenAxeGrid(helper, plan.recipeGrid());
        helper.assertTrue(LumbererSelfCraftingService.reservationStillExact(
                f.level(), f.settlement(), f.camp(), worker, request, plan),
            "unchanged action/source slots must remain presentable");

        f.chest().setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 3));
        f.chest().setChanged();
        helper.assertFalse(LumbererSelfCraftingService.reservationStillExact(
                f.level(), f.settlement(), f.camp(), worker, request, plan),
            "a changed identity in the reserved source slot must invalidate immediately");
        helper.assertFalse(LumbererSelfCraftingService.commit(f.level(),
                f.settlement(), f.camp(), worker, request, plan),
            "an invalidated projection may never consume or create output");
        helper.assertTrue(count(f.chest(), Items.SPRUCE_PLANKS) == 3
                && count(f.chest(), Items.STICK) == 2
                && worker.craftOutputEscrow() == null,
            "failed pre-contact validation must leave the live inventory untouched");
        LumbererSelfCraftingService.release(f.level(), plan);
        helper.succeed();
    }

    /** First P0 explicitly forbids log conversion and partial substitutes. */
    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "lumber_self_craft_material_gate")
    public void logsNeverSubstituteForThreeRealPlanks(GameTestHelper helper) {
        Fixture f = fixture(helper, 1, true);
        f.chest().setItem(0, new ItemStack(Items.OAK_LOG, 3));
        f.chest().setItem(1, new ItemStack(Items.STICK, 2));
        EquipmentRequest request = f.requests().getFirst();

        helper.assertFalse(LumbererSelfCraftingService.resolvesVanillaWoodenAxe(
                f.level(), f.camp()),
            "logs must not be converted or treated as planks in P0");
        helper.assertTrue(LumbererSelfCraftingService.reserve(f.level(),
                f.settlement(), f.camp(), f.workers().getFirst(), request) == null,
            "no three real planks means no craft reservation");
        helper.assertTrue(count(f.chest(), Items.OAK_LOG) == 3
                && count(f.chest(), Items.STICK) == 2,
            "a rejected material set must remain byte-for-byte physical");
        helper.succeed();
    }

    /** A decorative or nearby table outside registered camp bounds has no authority. */
    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "lumber_self_craft_table_bounds")
    public void craftingTableMustBeLiveAndInsideCampBounds(GameTestHelper helper) {
        Fixture f = fixture(helper, 1, false);
        putIngredients(f.chest(), 3, 2);
        BlockPos outsideRel = new BlockPos(10, 1, 8);
        helper.setBlock(outsideRel, Blocks.CRAFTING_TABLE);
        EquipmentRequest request = f.requests().getFirst();

        helper.assertTrue(LumbererSelfCraftingService.reserve(f.level(),
                f.settlement(), f.camp(), f.workers().getFirst(), request) == null,
            "a live table outside camp bounds must not authorize crafting");
        // tablePos is already absolute. Feeding it through relativePos and
        // then helper.setBlock applies the template transform twice and puts
        // the table outside the registered camp in rotated/full-suite runs.
        f.level().setBlockAndUpdate(f.tablePos(),
            Blocks.CRAFTING_TABLE.defaultBlockState());
        LumbererSelfCraftingService.CraftPlan plan =
            LumbererSelfCraftingService.reserve(f.level(), f.settlement(),
                f.camp(), f.workers().getFirst(), request);
        helper.assertTrue(plan != null && plan.tablePos().equals(f.tablePos()),
            "placing the same block inside camp bounds must create one exact plan");
        LumbererSelfCraftingService.release(f.level(), plan);
        f.level().destroyBlock(f.tablePos(), false);
        helper.assertTrue(LumbererSelfCraftingService.reserve(f.level(),
                f.settlement(), f.camp(), f.workers().getFirst(), request) == null,
            "destroying the in-bounds table must immediately remove authority");
        assertIngredients(helper, f.chest(), 3, 2,
            "table validation never consumes inventory");
        helper.succeed();
    }

    /** Output capacity is preflighted after simulated input consumption. */
    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "lumber_self_craft_full_output")
    public void fullStorageRejectsWithoutIngredientLoss(GameTestHelper helper) {
        Fixture f = fixture(helper, 1, true);
        f.chest().setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
        f.chest().setItem(1, new ItemStack(Items.STICK, 64));
        for (int slot = 2; slot < f.chest().getContainerSize(); slot++) {
            f.chest().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        EquipmentRequest request = f.requests().getFirst();

        helper.assertTrue(LumbererSelfCraftingService.reserve(f.level(),
                f.settlement(), f.camp(), f.workers().getFirst(), request) == null,
            "an unstackable axe with no post-consumption slot must not plan");
        helper.assertTrue(count(f.chest(), Items.OAK_PLANKS) == 64
                && count(f.chest(), Items.STICK) == 64
                && count(f.chest(), Items.COBBLESTONE) == 25 * 64,
            "full-output refusal must preserve every physical input and filler");
        helper.assertTrue(count(f.chest(), Items.WOODEN_AXE) == 0,
            "full storage may not spill or conjure output");
        helper.succeed();
    }

    /**
     * One table has one claimant; interruption releases it; the successor's
     * contact consumes once, and both stale/replayed plans fail closed.
     */
    @GameTest(template = "empty16", timeoutTicks = 120,
        batch = "lumber_self_craft_concurrency")
    public void concurrentWorkersAreReservedIdempotentAndInterruptSafe(
            GameTestHelper helper) {
        Fixture f = fixture(helper, 2, true);
        putIngredients(f.chest(), 6, 4);
        SettlerEntity first = f.workers().get(0);
        SettlerEntity second = f.workers().get(1);
        EquipmentRequest firstRequest = f.requests().get(0);
        EquipmentRequest secondRequest = f.requests().get(1);

        LumbererSelfCraftingService.CraftPlan firstPlan =
            LumbererSelfCraftingService.reserve(f.level(), f.settlement(),
                f.camp(), first, firstRequest);
        helper.assertTrue(firstPlan != null,
            "first worker must reserve the one real table");
        helper.assertTrue(first.getUUID().equals(
                LumbererSelfCraftingService.tableClaimant(f.level(),
                    f.tablePos())),
            "reservation ledger must name the exact first worker");
        helper.assertTrue(LumbererSelfCraftingService.reserve(f.level(),
                f.settlement(), f.camp(), second, secondRequest) == null,
            "a concurrent worker may not reserve the same live table");
        assertIngredients(helper, f.chest(), 6, 4,
            "reservation alone must not move materials");

        LumbererSelfCraftingService.release(f.level(), firstPlan);
        helper.assertTrue(LumbererSelfCraftingService.tableClaimant(f.level(),
                f.tablePos()) == null,
            "an interruption before contact must release the table promptly");
        LumbererSelfCraftingService.CraftPlan secondPlan =
            LumbererSelfCraftingService.reserve(f.level(), f.settlement(),
                f.camp(), second, secondRequest);
        helper.assertTrue(secondPlan != null,
            "the second worker must acquire the table after interruption");
        moveBeside(helper, second, f.tablePos());
        helper.assertTrue(LumbererSelfCraftingService.commit(f.level(),
                f.settlement(), f.camp(), second, secondRequest, secondPlan),
            "the successor's exact contact must commit once");
        helper.assertFalse(LumbererSelfCraftingService.commit(f.level(),
                f.settlement(), f.camp(), second, secondRequest, secondPlan),
            "the successor's repeated contact must not commit twice");
        helper.assertFalse(LumbererSelfCraftingService.commit(f.level(),
                f.settlement(), f.camp(), first, firstRequest, firstPlan),
            "the interrupted stale plan may never revive after ownership moved");
        helper.assertTrue(count(f.chest(), Items.OAK_PLANKS) == 3
                && count(f.chest(), Items.STICK) == 2
                && count(f.chest(), Items.WOODEN_AXE) == 0
                && second.craftOutputEscrow() != null
                && second.craftOutputEscrow().output().getCount() == 1,
            "two contenders must yield exactly one conserved escrow output");
        helper.assertTrue(LumbererSelfCraftingService.depositEscrow(f.level(),
                f.settlement(), f.camp(), second, secondPlan.actionId()),
            "the winner's later storage contact must deposit once");
        helper.assertTrue(count(f.chest(), Items.WOODEN_AXE) == 1
                && second.craftOutputEscrow() == null,
            "one contender must yield one final physical axe, never two");
        helper.succeed();
    }

    /** Full/missing storage after contact keeps output in durable escrow. */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "lumber_self_craft_post_contact_blocker")
    public void postContactFullStorageRetainsExactlyOneEscrowOutput(
            GameTestHelper helper) {
        Fixture f = fixture(helper, 1, true);
        putIngredients(f.chest(), 3, 2);
        SettlerEntity worker = f.workers().getFirst();
        EquipmentRequest request = f.requests().getFirst();
        LumbererSelfCraftingService.CraftPlan plan =
            LumbererSelfCraftingService.reserve(f.level(), f.settlement(),
                f.camp(), worker, request);
        helper.assertTrue(plan != null && LumbererSelfCraftingService.commit(
                f.level(), f.settlement(), f.camp(), worker, request, plan),
            "fixture: contact must create the single escrow output");
        for (int slot = 0; slot < f.chest().getContainerSize(); slot++) {
            f.chest().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        f.chest().setChanged();

        helper.assertTrue(LumbererSelfCraftingService.availableEscrowStorage(
                f.level(), f.camp(), worker.craftOutputEscrow()) == null,
            "a newly full linked chest must surface as a retry blocker");
        helper.assertFalse(LumbererSelfCraftingService.depositEscrow(f.level(),
                f.settlement(), f.camp(), worker, plan.actionId()),
            "full storage must refuse the deposit contact");
        helper.assertTrue(worker.craftOutputEscrow() != null
                && worker.craftOutputEscrow().output().is(Items.WOODEN_AXE)
                && worker.craftOutputEscrow().output().getCount() == 1
                && count(f.chest(), Items.WOODEN_AXE) == 0,
            "blocked deposit must neither drop, duplicate nor snap output back");
        helper.succeed();
    }

    /**
     * Crafting leaves the request open; the existing physical pickup closes it
     * for a wooden axe; worn/broken serviceability opens maintenance again.
     */
    @GameTest(template = "empty16", timeoutTicks = 120,
        batch = "lumber_self_craft_request_durability")
    public void requestClosesOnlyOnEquipAndReopensWhenWoodenAxeWears(
            GameTestHelper helper) {
        Fixture f = fixture(helper, 1, true);
        putIngredients(f.chest(), 3, 2);
        SettlerEntity worker = f.workers().getFirst();
        EquipmentRequest firstRequest = f.requests().getFirst();
        LumbererSelfCraftingService.CraftPlan plan =
            LumbererSelfCraftingService.reserve(f.level(), f.settlement(),
                f.camp(), worker, firstRequest);
        helper.assertTrue(plan != null && LumbererSelfCraftingService.commit(
                f.level(), f.settlement(), f.camp(), worker, firstRequest, plan),
            "fixture: one wooden axe must be crafted into worker escrow");
        helper.assertTrue(EquipmentRequests.requestFor(f.camp(),
                worker.getUUID()) == firstRequest && worker.getMainHandItem().isEmpty(),
            "escrow output is not request satisfaction until physical equip");
        helper.assertTrue(LumbererSelfCraftingService.depositEscrow(f.level(),
                f.settlement(), f.camp(), worker, plan.actionId()),
            "fixture: the separate visible storage contact must deposit output");

        helper.assertTrue(EquipmentRequests.equipFromWorkplaceAt(f.level(),
                f.settlement(), worker, f.chestPos()),
            "the existing acquisition contact must move the real camp axe to hand");
        helper.assertTrue(worker.getMainHandItem().is(Items.WOODEN_AXE),
            "the physically acquired tool must be the exact crafted wooden axe");
        helper.assertTrue(EquipmentRequests.requestFor(f.camp(),
                worker.getUUID()) == null,
            "the request must close only after the serviceable axe is in hand");
        helper.assertTrue(EquipmentRequests.refreshFor(f.level(), f.settlement(),
                f.camp(), worker) == null,
            "a fresh wooden axe satisfies the axe tag; no iron-upgrade request may open");

        ItemStack worn = worker.getMainHandItem();
        worn.setDamageValue(worn.getMaxDamage() - 7);
        worker.setItemSlot(EquipmentSlot.MAINHAND, worn);
        EquipmentRequest wornRequest = EquipmentRequests.refreshFor(f.level(),
            f.settlement(), f.camp(), worker);
        helper.assertTrue(wornRequest != null
                && wornRequest.reason() == EquipmentRequest.Reason.WORN,
            "below the eight-use serviceability floor must reopen a WORN request");
        worker.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        EquipmentRequest missingRequest = EquipmentRequests.refreshFor(f.level(),
            f.settlement(), f.camp(), worker);
        helper.assertTrue(missingRequest == wornRequest
                && missingRequest.reason() == EquipmentRequest.Reason.MISSING,
            "actual loss/break must update the same persistent need to MISSING");
        helper.succeed();
    }

    /** Full goal-selector proof: grace -> travel -> table work -> storage -> pickup. */
    @GameTest(template = "empty16", timeoutTicks = 650,
        batch = "lumber_self_craft_live_loop")
    public void liveLumbererWalksCraftsStoresThenPhysicallyEquips(
            GameTestHelper helper) {
        Fixture f = fixture(helper, 1, true);
        putIngredients(f.chest(), 3, 2);
        SettlerEntity worker = f.workers().getFirst();
        moveBeside(helper, worker, helper.absolutePos(new BlockPos(2, 1, 2)));
        final boolean[] sawCraftAtTable = {false};
        final boolean[] sawEscrowBeforeStorage = {false};
        final boolean[] sawStorageContact = {false};
        final boolean[] sawStoredBeforeEquip = {false};
        final boolean[] storedRequestStillOpen = {false};

        helper.succeedWhen(() -> {
            if (worker.getActivity() == SettlerActivity.WORK_CRAFT) {
                sawCraftAtTable[0] = true;
                helper.assertTrue(worker.blockPosition().distSqr(f.tablePos())
                        <= 2.25D,
                    "WORK_CRAFT must be physically bound to the exact table");
            }
            if (worker.craftOutputEscrow() != null
                && count(f.chest(), Items.WOODEN_AXE) == 0) {
                sawEscrowBeforeStorage[0] = true;
            }
            if (worker.getActivity() == SettlerActivity.STORE_CRAFT_OUTPUT) {
                sawStorageContact[0] = true;
            }
            if (count(f.chest(), Items.WOODEN_AXE) == 1
                && worker.getMainHandItem().isEmpty()) {
                sawStoredBeforeEquip[0] = true;
                storedRequestStillOpen[0] = EquipmentRequests.requestFor(
                    f.camp(), worker.getUUID()) != null;
            }
            helper.assertTrue(worker.getMainHandItem().is(Items.WOODEN_AXE),
                "live fallback has not yet completed physical pickup (activity="
                    + worker.getActivity() + ", route=" + worker.routeFailureNote()
                    + ", request=" + EquipmentRequests.requestFor(f.camp(),
                        worker.getUUID()) + ")");
            helper.assertTrue(sawCraftAtTable[0],
                "the live worker must visibly enter WORK_CRAFT at the table");
            helper.assertTrue(sawEscrowBeforeStorage[0]
                    && sawStorageContact[0],
                "the live worker must carry escrow then enter a separate storage contact");
            helper.assertTrue(sawStoredBeforeEquip[0]
                    && storedRequestStillOpen[0],
                "the axe must exist in real camp storage while its request remains open");
            helper.assertTrue(EquipmentRequests.requestFor(f.camp(),
                    worker.getUUID()) == null,
                "only the later physical pickup may close the live request");
            helper.assertTrue(count(f.chest(), Items.OAK_PLANKS) == 0
                    && count(f.chest(), Items.STICK) == 0
                    && count(f.chest(), Items.WOODEN_AXE) == 0,
                "live loop must conserve 3+2 inputs and remove the one output only on pickup");
        });
    }

    private record Fixture(ServerLevel level, Settlement settlement,
                           Building camp, BlockPos tablePos,
                           BlockPos chestPos, Container chest,
                           List<SettlerEntity> workers,
                           List<EquipmentRequest> requests) {
    }

    private static Fixture fixture(GameTestHelper helper, int workers,
                                   boolean placeTable) {
        helper.getLevel().setDayTime(2_000L);
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(2, 1, 2));
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Craftstead", center);
        settlement.radius = 14;
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.put(settlement.id, settlement);
        data.setDirty();

        Building camp = GameTestFixtures.register(helper, settlement,
            BuildingType.LUMBER_CAMP, 6, 6);
        BlockPos tableRel = new BlockPos(7, 1, 8);
        BlockPos chestRel = new BlockPos(8, 1, 8);
        if (placeTable) {
            helper.setBlock(tableRel, Blocks.CRAFTING_TABLE);
        }
        helper.setBlock(chestRel, Blocks.CHEST);
        BlockPos tableAbs = helper.absolutePos(tableRel);
        BlockPos chestAbs = helper.absolutePos(chestRel);
        Container chest = (Container) level.getBlockEntity(chestAbs);

        List<SettlerEntity> hired = new ArrayList<>();
        List<EquipmentRequest> requests = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            SettlerEntity worker = employed(helper, settlement, camp,
                "Lumberer " + i, new BlockPos(8 + i, 1, 7));
            EquipmentRequest request = EquipmentRequests.refreshFor(level,
                settlement, camp, worker);
            helper.assertTrue(request != null,
                "fixture: an empty-handed employed Lumberer needs one real axe");
            hired.add(worker);
            requests.add(request);
        }
        return new Fixture(level, settlement, camp, tableAbs, chestAbs, chest,
            List.copyOf(hired), List.copyOf(requests));
    }

    private static SettlerEntity employed(GameTestHelper helper,
                                          Settlement settlement,
                                          Building workplace,
                                          String name, BlockPos spawnRel) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), spawnRel);
        settler.setSettlerName(name);
        settler.bindTo(settlement.id, settlement.center);
        settlement.putRecord(settler.getUUID(), name, Profession.NONE);
        Employment.Hired hired = Employment.hire(helper.getLevel(), settlement,
            workplace, settler);
        helper.assertTrue(hired.ok(),
            "fixture employment must commit through Employment, refused="
                + hired.refusal());
        return settler;
    }

    private static void putIngredients(Container chest, int planks, int sticks) {
        chest.setItem(0, new ItemStack(Items.OAK_PLANKS, planks));
        chest.setItem(1, new ItemStack(Items.STICK, sticks));
        chest.setChanged();
    }

    private static void assertIngredients(GameTestHelper helper, Container chest,
                                          int planks, int sticks,
                                          String message) {
        helper.assertTrue(count(chest, Items.OAK_PLANKS) == planks
                && count(chest, Items.STICK) == sticks,
            message + " (planks=" + count(chest, Items.OAK_PLANKS)
                + ", sticks=" + count(chest, Items.STICK) + ")");
    }

    private static int count(Container container,
                             net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) {
                total += container.getItem(slot).getCount();
            }
        }
        return total;
    }

    private static void assertWoodenAxeGrid(GameTestHelper helper,
                                            List<ItemStack> grid) {
        helper.assertTrue(grid.size() == 9,
            "presentation contract requires one exact 3x3 recipe grid");
        for (int slot = 0; slot < grid.size(); slot++) {
            ItemStack stack = grid.get(slot);
            boolean plankSlot = slot == 0 || slot == 1 || slot == 3;
            boolean stickSlot = slot == 4 || slot == 7;
            helper.assertTrue(plankSlot
                    ? stack.is(net.minecraft.tags.ItemTags.PLANKS)
                        && stack.getCount() == 1
                    : stickSlot ? stack.is(Items.STICK)
                        && stack.getCount() == 1
                    : stack.isEmpty(),
                "slot " + slot + " must match vanilla wooden-axe layout");
        }
    }

    private static void moveBeside(GameTestHelper helper, SettlerEntity worker,
                                   BlockPos absoluteTarget) {
        worker.setPos(absoluteTarget.getX() + 0.5D,
            absoluteTarget.getY(), absoluteTarget.getZ() - 0.5D);
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    public LumbererSelfCraftGameTests() {
    }
}
