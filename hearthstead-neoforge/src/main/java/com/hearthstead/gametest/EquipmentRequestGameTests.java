package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.ArcherAttackGoal;
import com.hearthstead.entity.ai.GuardMeleeGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.network.EquipmentRequestListNetwork;
import com.hearthstead.network.EquipmentRequestListPayload;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequestQueue;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Server-authoritative regression wall for the first equipment vertical. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class EquipmentRequestGameTests {

    private static Settlement settlement(GameTestHelper helper) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Kit Test",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 7;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement settlement,
                                         String name) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(6, 1, 6));
        settler.setSettlerName(name);
        settler.bindTo(settlement.id, settlement.center);
        settlement.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    private static Container chest(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative, Blocks.CHEST);
        return (Container) helper.getLevel().getBlockEntity(
            helper.absolutePos(relative));
    }

    private static int countIn(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int countInBag(SettlerEntity settler, Item item) {
        int count = 0;
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            ItemStack stack = settler.bag.getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    @GameTest(template = "empty16", batch = "equipment", timeoutTicks = 200)
    public void hiringPublishesOneRequestAndNeverProjectsATool(GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        Building farm = GameTestFixtures.register(helper, settlement,
            BuildingType.FARMHOUSE, 2, 2);
        SettlerEntity farmer = settler(helper, settlement, "Runa");

        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, farm,
            farmer).ok(), "hire must succeed");
        EquipmentRequests.refreshFor(helper.getLevel(), settlement, farm, farmer);
        EquipmentRequests.refreshFor(helper.getLevel(), settlement, farm, farmer);

        helper.assertTrue(farmer.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty(),
            "a job projection must never fabricate a hoe");
        helper.assertTrue(farm.equipmentRequests.size() == 1,
            "repeated reconciliation must deduplicate the request");
        helper.assertTrue(farm.equipmentRequests.get(0).reason()
                == EquipmentRequest.Reason.MISSING,
            "the request must explain that the tool is missing");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "equipment", timeoutTicks = 200)
    public void playerSuppliedWorkplaceToolMovesExactlyOnce(GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        Building camp = GameTestFixtures.register(helper, settlement,
            BuildingType.LUMBER_CAMP, 2, 2);
        Container storage = chest(helper, new BlockPos(3, 1, 3));
        storage.setItem(0, new ItemStack(Items.STONE_AXE));
        SettlerEntity lumberer = settler(helper, settlement, "Eira");
        Employment.hire(helper.getLevel(), settlement, camp, lumberer);

        helper.assertFalse(EquipmentRequests.equipFromWorkplace(helper.getLevel(),
            settlement, lumberer),
            "a workplace tool must not teleport from a distant chest");
        helper.assertTrue(storage.getItem(0).is(Items.STONE_AXE)
                && lumberer.getMainHandItem().isEmpty(),
            "a rejected distant handoff must leave both inventories unchanged");

        BlockPos contact = helper.absolutePos(new BlockPos(3, 1, 4));
        lumberer.teleportTo(contact.getX() + 0.5D, contact.getY(),
            contact.getZ() + 0.5D);
        BlockPos source = helper.absolutePos(new BlockPos(3, 1, 3));
        helper.assertTrue(EquipmentRequests.equipFromWorkplaceAt(helper.getLevel(),
            settlement, lumberer, source),
            "a compatible axe may move only after the worker reaches its chest");
        helper.assertTrue(storage.getItem(0).isEmpty(),
            "the physical axe must leave the chest");
        helper.assertTrue(lumberer.getMainHandItem().is(Items.STONE_AXE),
            "the same physical axe must reach the worker's hand");
        helper.assertTrue(camp.equipmentRequests.isEmpty(),
            "fulfilled request must leave no duplicate row");
        helper.assertTrue(EquipmentRequests.equipFromWorkplace(helper.getLevel(),
            settlement, lumberer), "reconciliation is idempotent");
        helper.assertTrue(lumberer.getMainHandItem().getCount() == 1,
            "idempotent fulfilment must not duplicate the axe");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "equipment", timeoutTicks = 2400)
    public void courierMovesOnePhysicalToolWarehouseToWorkplaceToWorker(
        GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        Settlement settlement = settlement(helper);
        Building warehouseBuilding = GameTestFixtures.register(helper, settlement,
            BuildingType.WAREHOUSE, 2, 2);
        Container warehouse = chest(helper, new BlockPos(3, 1, 3));
        warehouse.setItem(0, new ItemStack(Items.IRON_HOE));
        Building farm = GameTestFixtures.register(helper, settlement,
            BuildingType.FARMHOUSE, 9, 9);
        Container workplace = chest(helper, new BlockPos(10, 1, 10));

        SettlerEntity farmer = settler(helper, settlement, "Ingrid");
        farmer.setPos(helper.absolutePos(new BlockPos(11, 1, 10)).getCenter());
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                farm, farmer).ok(),
            "fixture must publish the farmer's real equipment request");
        SettlerEntity courier = settler(helper, settlement, "Bud");
        courier.setPos(helper.absolutePos(new BlockPos(6, 1, 6)).getCenter());
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                warehouseBuilding, courier).ok(),
            "fixture Courier must own the real Warehouse workplace required for delivery authority");

        final boolean[] sawClaim = {false};
        final boolean[] sawCourierBag = {false};
        final boolean[] conservationBroken = {false};
        helper.succeedWhen(() -> {
            EquipmentRequest request = EquipmentRequests.requestFor(
                farm, farmer.getUUID());
            if (request != null
                && request.status() == EquipmentRequest.Status.CLAIMED) {
                sawClaim[0] = true;
            }
            int atWarehouse = countIn(warehouse, Items.IRON_HOE);
            int inCourierBag = countInBag(courier, Items.IRON_HOE);
            int atWorkplace = countIn(workplace, Items.IRON_HOE);
            int inWorkerHand = farmer.getMainHandItem().is(Items.IRON_HOE) ? 1 : 0;
            if (inCourierBag > 0) {
                sawCourierBag[0] = true;
            }
            int total = atWarehouse + inCourierBag + atWorkplace + inWorkerHand;
            conservationBroken[0] |= total != 1;
            helper.assertFalse(conservationBroken[0],
                "the real hoe must be conserved on every observed route tick: "
                    + "warehouse=" + atWarehouse + " bag=" + inCourierBag
                    + " workplace=" + atWorkplace + " hand=" + inWorkerHand);
            helper.assertTrue(farmer.getMainHandItem().is(Items.IRON_HOE)
                    && atWarehouse == 0 && inCourierBag == 0
                    && atWorkplace == 0,
                "the route must finish warehouse -> Courier bag -> workplace -> hand"
                    + " [courier=" + courier.getActivity()
                    + " farmer=" + farmer.getActivity() + "]");
            helper.assertTrue(sawClaim[0],
                "the Courier goal must claim the persistent request before withdrawal");
            helper.assertTrue(sawCourierBag[0],
                "the physical hoe must be observed in the Courier's real bag");
            helper.assertTrue(farm.equipmentRequests.isEmpty(),
                "the worker's physical handoff must retire the delivered request");
        });
    }

    @GameTest(template = "empty16", batch = "equipment", timeoutTicks = 2400)
    public void courierDeliversFisherRodPastAnEmptyCatchRack(
        GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        Settlement settlement = settlement(helper);
        Building warehouseBuilding = GameTestFixtures.register(helper, settlement,
            BuildingType.WAREHOUSE, 2, 2);
        Container warehouse = chest(helper, new BlockPos(3, 1, 3));
        warehouse.setItem(0, new ItemStack(com.hearthstead.registry.ModItems.FISHERS_ROD.get()));
        Building farm = GameTestFixtures.register(helper, settlement,
            BuildingType.FISHERY, 9, 9);
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.BARREL);
        Container workplace = (Container) helper.getBlockEntity(new BlockPos(10, 1, 10));

        helper.setBlock(new BlockPos(9,1,9), com.hearthstead.registry.ModBlocks.FISH_RACK.get());
        SettlerEntity farmer = settler(helper, settlement, "Fisher");
        farmer.setPos(helper.absolutePos(new BlockPos(11, 1, 10)).getCenter());
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                farm, farmer).ok(),
            "fixture must publish the farmer's real equipment request");
        SettlerEntity courier = settler(helper, settlement, "Bud");
        courier.setPos(helper.absolutePos(new BlockPos(6, 1, 6)).getCenter());
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                warehouseBuilding, courier).ok(),
            "fixture Courier must own the real Warehouse workplace required for delivery authority");

        final boolean[] sawClaim = {false};
        final boolean[] sawCourierBag = {false};
        final boolean[] conservationBroken = {false};
        helper.succeedWhen(() -> {
            EquipmentRequest request = EquipmentRequests.requestFor(
                farm, farmer.getUUID());
            if (request != null
                && request.status() == EquipmentRequest.Status.CLAIMED) {
                sawClaim[0] = true;
            }
            int atWarehouse = countIn(warehouse, com.hearthstead.registry.ModItems.FISHERS_ROD.get());
            int inCourierBag = countInBag(courier, com.hearthstead.registry.ModItems.FISHERS_ROD.get());
            int atWorkplace = countIn(workplace, com.hearthstead.registry.ModItems.FISHERS_ROD.get());
            int inWorkerHand = farmer.getMainHandItem().is(com.hearthstead.registry.ModItems.FISHERS_ROD.get()) ? 1 : 0;
            if (inCourierBag > 0) {
                sawCourierBag[0] = true;
            }
            int total = atWarehouse + inCourierBag + atWorkplace + inWorkerHand;
            conservationBroken[0] |= total != 1;
            helper.assertFalse(conservationBroken[0],
                "the real hoe must be conserved on every observed route tick: "
                    + "warehouse=" + atWarehouse + " bag=" + inCourierBag
                    + " workplace=" + atWorkplace + " hand=" + inWorkerHand);
            helper.assertTrue(farmer.getMainHandItem().is(com.hearthstead.registry.ModItems.FISHERS_ROD.get())
                    && atWarehouse == 0 && inCourierBag == 0
                    && atWorkplace == 0,
                "the route must finish warehouse -> Courier bag -> workplace -> hand"
                    + " [courier=" + courier.getActivity()
                    + " farmer=" + farmer.getActivity() + "]");
            helper.assertTrue(sawClaim[0],
                "the Courier goal must claim the persistent request before withdrawal");
            helper.assertTrue(sawCourierBag[0],
                "the physical hoe must be observed in the Courier's real bag");
            helper.assertTrue(farm.equipmentRequests.isEmpty(),
                "the worker's physical handoff must retire the delivered request");
        });
    }

    @GameTest(template = "empty16", batch = "equipment", timeoutTicks = 2400)
    public void onlyWarehouseEmployedCourierDeliveryAuthorsGuardWatchCredit(
            GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        Settlement settlement = settlement(helper);
        Building warehouseBuilding = GameTestFixtures.register(helper, settlement,
            BuildingType.WAREHOUSE, 2, 2);
        Container warehouse = chest(helper, new BlockPos(3, 1, 3));
        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 9, 9);
        Container rack = chest(helper, new BlockPos(10, 1, 10));

        SettlerEntity courier = settler(helper, settlement, "Watch Courier");
        courier.setPos(helper.absolutePos(new BlockPos(6, 1, 6)).getCenter());
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                warehouseBuilding, courier).ok(),
            "the proof Courier must own one valid Warehouse post");

        // Initializing after the real Barracks exists grandfathers the old
        // knowledge chain, but Arm the Watch itself has no building to
        // grandfather. Its baseline therefore starts honestly at zero.
        var development = Development.of(helper.getLevel(), settlement);
        SettlerEntity directlySupplied = settler(helper, settlement,
            "Directly Supplied Guard");
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                barracks, directlySupplied).ok(),
            "fixture needs one Guard request for the direct-supply negative");
        directlySupplied.bag.setItem(0, new ItemStack(Items.IRON_SWORD));
        helper.assertTrue(EquipmentRequests.equipFromPersonalInventory(
                helper.getLevel(), settlement, directlySupplied),
            "the player-facing bag path should equip the physical sword");
        helper.assertTrue(development.writeNbt().getCompound("QuestCounters")
                .getInt("GuardEquipmentDeliveries") == 0,
            "direct inventory supply must never impersonate a Courier route");

        SettlerEntity requestedGuard = settler(helper, settlement,
            "Requested Guard");
        requestedGuard.setPos(helper.absolutePos(new BlockPos(11, 1, 10)).getCenter());
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                barracks, requestedGuard).ok()
                && EquipmentRequests.requestFor(barracks,
                    requestedGuard.getUUID()) != null,
            "the second Guard must publish one real missing-sword request");
        warehouse.setItem(0, new ItemStack(Items.IRON_SWORD));

        final boolean[] sawCourierBag = {false};
        helper.succeedWhen(() -> {
            int inCourierBag = countInBag(courier, Items.IRON_SWORD);
            if (inCourierBag > 0) {
                sawCourierBag[0] = true;
            }
            int credited = Development.of(helper.getLevel(), settlement).writeNbt()
                .getCompound("QuestCounters").getInt("GuardEquipmentDeliveries");
            EquipmentRequest liveRequest = EquipmentRequests.requestFor(barracks,
                requestedGuard.getUUID());
            int atBarracks = countIn(rack, Items.IRON_SWORD);
            int inGuardHand = requestedGuard.getMainHandItem()
                .is(Items.IRON_SWORD) ? 1 : 0;
            helper.assertTrue(warehouse.getItem(0).isEmpty()
                    && inCourierBag == 0 && atBarracks + inGuardHand == 1,
                "the same sword must finish Warehouse -> Courier -> Barracks"
                    + " and remain there until the Guard's separate acquisition goal"
                    + " moves it into hand"
                    + " [warehouse=" + countIn(warehouse, Items.IRON_SWORD)
                    + " courierBag=" + inCourierBag
                    + " barracks=" + atBarracks
                    + " hand=" + inGuardHand
                    + " courierActivity=" + courier.getActivity()
                    + " guardActivity=" + requestedGuard.getActivity()
                    + " request=" + (liveRequest == null ? "none"
                        : liveRequest.status())
                    + " credited=" + credited + "]");
            helper.assertTrue(sawCourierBag[0],
                "the Guard sword must be observed in the Courier's real bag");
            helper.assertTrue(credited == 1,
                "exactly one strict Guard-to-Barracks Courier handoff must be credited: credited=" + credited
                    + " request=" + (liveRequest == null ? "none" : liveRequest.status())
                    + " barracks=" + atBarracks + " hand=" + inGuardHand);
        });
    }

    @GameTest(template = "empty16", batch = "equipment", timeoutTicks = 200)
    public void wrongHeldItemIsNeverOverwrittenOrLost(GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        Building farm = GameTestFixtures.register(helper, settlement,
            BuildingType.FARMHOUSE, 2, 2);
        Container storage = chest(helper, new BlockPos(3, 1, 3));
        storage.setItem(0, new ItemStack(Items.IRON_HOE));
        SettlerEntity farmer = settler(helper, settlement, "Liv");
        farmer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        Employment.hire(helper.getLevel(), settlement, farm, farmer);

        helper.assertFalse(EquipmentRequests.equipFromWorkplace(helper.getLevel(),
            settlement, farmer), "a real held item blocks replacement");
        helper.assertTrue(farmer.getMainHandItem().is(Items.STICK),
            "the wrong held item must remain physical");
        helper.assertTrue(storage.getItem(0).is(Items.IRON_HOE),
            "the requested hoe must remain in storage until a safe handoff");
        helper.assertTrue(farm.equipmentRequests.get(0).reason()
                == EquipmentRequest.Reason.WRONG_TOOL,
            "the request list must explain the blocker");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "equipment", timeoutTicks = 200)
    public void reassignmentCancelsOldRequestAndPersistsOnlyTheNewOne(
        GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        Building farm = GameTestFixtures.register(helper, settlement,
            BuildingType.FARMHOUSE, 2, 2);
        Building camp = GameTestFixtures.register(helper, settlement,
            BuildingType.LUMBER_CAMP, 10, 10);
        SettlerEntity worker = settler(helper, settlement, "Siv");
        Employment.hire(helper.getLevel(), settlement, farm, worker);
        Employment.hire(helper.getLevel(), settlement, camp, worker);

        helper.assertTrue(farm.equipmentRequests.isEmpty(),
            "the old workplace must not retain a ghost request");
        helper.assertTrue(camp.equipmentRequests.size() == 1
                && camp.equipmentRequests.get(0).profession()
                    == Profession.LUMBERER,
            "the new workplace owns exactly the new trade's request");

        Building restored = Building.readNbt(camp.writeNbt());
        helper.assertTrue(restored.equipmentRequests.size() == 1
                && restored.equipmentRequests.get(0).requesterId()
                    .equals(worker.getUUID()),
            "request identity must survive building save/reload");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "equipment", timeoutTicks = 200)
    public void guardsAndArchersNeedPhysicalWeaponsAndWornWeaponsReopenRequests(
        GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 2, 2);
        Container barracksRack = chest(helper, new BlockPos(3, 1, 3));
        SettlerEntity guard = settler(helper, settlement, "Ragnhild");
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                barracks, guard).ok(),
            "the barracks must hire its guard");
        helper.assertTrue(guard.getMainHandItem().isEmpty()
                && barracks.equipmentRequests.size() == 1
                && barracks.equipmentRequests.get(0).requirement()
                    .preferredItem() == Items.WOODEN_SWORD,
            "a guard must request a sword instead of spawning with one");
        barracksRack.setItem(0, new ItemStack(Items.IRON_SWORD));
        guard.teleportTo(helper.absolutePos(new BlockPos(3, 1, 4)).getX() + 0.5D,
            helper.absolutePos(new BlockPos(3, 1, 4)).getY(),
            helper.absolutePos(new BlockPos(3, 1, 4)).getZ() + 0.5D);
        helper.assertTrue(EquipmentRequests.equipFromWorkplace(helper.getLevel(),
                settlement, guard),
            "a guard at the real barracks rack must take its sword");
        helper.assertTrue(barracksRack.getItem(0).isEmpty()
                && guard.getMainHandItem().is(Items.IRON_SWORD),
            "the exact physical sword must move chest to hand");

        guard.getMainHandItem().setDamageValue(
            guard.getMainHandItem().getMaxDamage() - 4);
        EquipmentRequests.refreshFor(helper.getLevel(), settlement, barracks, guard);
        helper.assertTrue(barracks.equipmentRequests.size() == 1
                && barracks.equipmentRequests.get(0).reason()
                    == EquipmentRequest.Reason.WORN,
            "a nearly broken combat weapon must publish an understandable replacement");

        Building tower = GameTestFixtures.register(helper, settlement,
            BuildingType.WATCHTOWER, 10, 10);
        Container towerRack = chest(helper, new BlockPos(11, 1, 11));
        towerRack.setItem(0, new ItemStack(Items.BOW));
        SettlerEntity archer = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(8, 1, 8));
        archer.setSettlerName("Yrsa");
        archer.bindTo(settlement.id, settlement.center);
        settlement.putRecord(archer.getUUID(), "Yrsa", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                tower, archer).ok(),
            "the watchtower must hire its archer");
        archer.teleportTo(helper.absolutePos(new BlockPos(11, 1, 12)).getX() + 0.5D,
            helper.absolutePos(new BlockPos(11, 1, 12)).getY(),
            helper.absolutePos(new BlockPos(11, 1, 12)).getZ() + 0.5D);
        helper.assertTrue(EquipmentRequests.equipFromWorkplace(helper.getLevel(),
                settlement, archer),
            "an archer at the real tower rack must take its bow");
        helper.assertTrue(towerRack.getItem(0).isEmpty()
                && archer.getMainHandItem().is(Items.BOW),
            "the exact physical bow must move rack to hand");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "equipment", timeoutTicks = 200)
    public void directProfessionCannotBypassPhysicalWeaponAuthority(
        GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        SettlerEntity guard = settler(helper, settlement, "Tomhendt");
        guard.assignProfession(Profession.GUARD);

        helper.assertFalse(EquipmentRequests.readyForProfession(helper.getLevel(),
                guard, Profession.GUARD),
            "a profession flag with no employer and no sword must not enable combat");
        helper.assertTrue(guard.getMainHandItem().isEmpty(),
            "the combat gate may never fabricate its missing weapon");
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        guard.assignProfession(Profession.FARMER);
        helper.assertFalse(EquipmentRequests.readyForProfession(helper.getLevel(),
                guard, Profession.GUARD),
            "a stale sword may not keep guard combat alive after reassignment");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "equipment", timeoutTicks = 200)
    public void combatGoalsRefuseEmptyHandsButAcceptRealHeldWeapons(
        GameTestHelper helper) {
        for (int x = 4; x <= 9; x++) {
            for (int z = 4; z <= 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        Settlement settlement = settlement(helper);
        SettlerEntity guard = settler(helper, settlement, "Vakt");
        guard.assignProfession(Profession.GUARD);
        RaiderEntity target = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(7, 1, 6));
        target.setNoAi(true);
        guard.setTarget(target);
        GuardMeleeGoal melee = new GuardMeleeGoal(guard);

        helper.assertFalse(melee.canUse(),
            "the melee goal itself must refuse a guard with no physical sword");
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        helper.assertTrue(melee.canUse(),
            "the same reachable target must become valid once a real sword is held");

        SettlerEntity archer = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(6, 1, 8));
        archer.setSettlerName("Skytter");
        archer.bindTo(settlement.id, settlement.center);
        settlement.putRecord(archer.getUUID(), "Skytter", Profession.NONE);
        archer.assignProfession(Profession.ARCHER);
        archer.setTarget(target);
        ArcherAttackGoal ranged = new ArcherAttackGoal(archer);
        helper.assertFalse(ranged.canUse(),
            "the archer goal itself must refuse an empty main hand");
        archer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        helper.assertTrue(ranged.canUse(),
            "the same live target must become valid once a real bow is held");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "equipment", timeoutTicks = 200)
    public void manualQueueOrderIsPersistentAndReplaySafe(GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        Building farm = GameTestFixtures.register(helper, settlement,
            BuildingType.FARMHOUSE, 2, 2);
        EquipmentRequest urgent = new EquipmentRequest(UUID.randomUUID(), farm.id,
            Profession.FARMER, EquipmentRequests.requirementFor(Profession.FARMER),
            1, EquipmentRequest.Priority.URGENT,
            EquipmentRequest.Reason.MISSING);
        EquipmentRequest normal = new EquipmentRequest(UUID.randomUUID(), farm.id,
            Profession.FARMER, EquipmentRequests.requirementFor(Profession.FARMER),
            1, EquipmentRequest.Priority.NORMAL,
            EquipmentRequest.Reason.MISSING);
        farm.workers.add(urgent.requesterId());
        farm.workers.add(normal.requesterId());
        settlement.putRecord(urgent.requesterId(), "Urgent", Profession.FARMER);
        settlement.putRecord(normal.requesterId(), "Normal", Profession.FARMER);
        farm.equipmentRequests.add(normal);
        farm.equipmentRequests.add(urgent);

        EquipmentRequests.list(helper.getLevel(), settlement);
        long revision = EquipmentRequests.queueRevision(helper.getLevel(), settlement);
        helper.assertTrue(EquipmentRequests.reorder(helper.getLevel(), settlement,
                normal.id(), urgent.id(), revision)
                == EquipmentRequestQueue.MoveResult.APPLIED,
            "an exact revision must atomically promote the dragged request");
        helper.assertTrue(EquipmentRequests.list(helper.getLevel(), settlement)
                .get(0).id().equals(normal.id()),
            "Courier AI must consume the promoted row as queue position #1");
        helper.assertTrue(EquipmentRequests.reorder(helper.getLevel(), settlement,
                normal.id(), urgent.id(), revision)
                == EquipmentRequestQueue.MoveResult.STALE,
            "replaying the old drag revision must be rejected");

        Settlement restored = Settlement.readNbt(settlement.writeNbt());
        helper.assertTrue(EquipmentRequests.list(restored).get(0).id()
                .equals(normal.id()),
            "manual order and its request identities must survive save/reload");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "equipment", timeoutTicks = 200)
    public void courierSnapshotIsPrioritySortedAndHardCapped(GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        Building farm = GameTestFixtures.register(helper, settlement,
            BuildingType.FARMHOUSE, 2, 2);
        for (int i = 0; i < EquipmentRequestListPayload.MAX_ROWS + 3; i++) {
            UUID workerId = UUID.randomUUID();
            farm.workers.add(workerId);
            settlement.putRecord(workerId, "Worker " + i, Profession.FARMER);
            EquipmentRequest.Priority priority = EquipmentRequest.Priority.values()[
                i % EquipmentRequest.Priority.values().length];
            farm.equipmentRequests.add(new EquipmentRequest(workerId, farm.id,
                Profession.FARMER,
                EquipmentRequests.requirementFor(Profession.FARMER), 1,
                priority, EquipmentRequest.Reason.MISSING));
        }

        EquipmentRequestListPayload snapshot = EquipmentRequestListNetwork.snapshot(
            helper.getLevel(), settlement, 91);
        helper.assertTrue(snapshot.rows().size()
                == EquipmentRequestListPayload.MAX_ROWS,
            "wire snapshot must never exceed its row budget");
        helper.assertTrue(snapshot.totalRequests()
                == EquipmentRequestListPayload.MAX_ROWS + 3,
            "the capped snapshot must still report the full queue");
        helper.assertTrue(snapshot.rows().get(0).priorityWireId()
                == EquipmentRequest.Priority.URGENT.ordinal(),
            "server must sort urgent requests first");
        helper.assertTrue(snapshot.rows().stream().allMatch(row ->
                row.count() == 1 && row.destinationBuildingId().equals(farm.id)
                    && !row.requesterName().isBlank()),
            "every row must carry count, requester and destination truth");
        for (int index = 0; index < snapshot.rows().size(); index++) {
            helper.assertTrue(snapshot.rows().get(index).queuePosition() == index + 1,
                "every visible # position must be authored from Courier AI order");
        }
        helper.assertTrue(!snapshot.nextRequestId()
                .equals(com.hearthstead.network.EquipmentRequestMovePayload.END),
            "a capped queue must expose the next hidden insertion anchor");
        helper.succeed();
    }
}
