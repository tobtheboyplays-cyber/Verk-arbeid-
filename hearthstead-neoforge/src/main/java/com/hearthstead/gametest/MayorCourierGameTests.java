package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.CourierWorkGoal;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.request.RequestLedgerService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Mayor logistics is a Warehouse authority, never disguised employment. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class MayorCourierGameTests {

    @GameTest(template = "empty16", timeoutTicks = 1200, batch = "mayor_courier")
    public void mayorPhysicallyHaulsAndRetainsOfficeAcrossSettlementReload(GameTestHelper helper) {
        Fixture f = fixture(helper, 4);
        boolean[] carried = {false};
        helper.succeedWhen(() -> {
            carried[0] |= f.mayor.getActivity() == SettlerActivity.CARRYING;
            int delivered = count(f.chest(), Items.OAK_LOG);
            int hearth = count(f.hearth.getInventory(), Items.OAK_LOG);
            int bag = count(f.mayor.bag, Items.OAK_LOG);
            helper.assertTrue(delivered + hearth + bag == 4,
                "Mayor Courier must conserve every physical log");
            helper.assertTrue(delivered == 4,
                "Mayor must finish the complete physical Hearth-to-Warehouse haul, saw "
                    + delivered + " [hearth=" + hearth + ", bag=" + bag
                    + ", activity=" + f.mayor.getActivity()
                    + ", profession=" + f.mayor.getProfession()
                    + ", phase=" + f.mayor.dayPhase()
                    + ", route=" + f.mayor.routeFailureNote()
                    + ", warehouseValid=" + f.warehouse.valid
                    + ", associated=" + f.warehouse.id.equals(f.settlement.mayorCourierWarehouseId)
                    + ", position=" + f.mayor.blockPosition()
                    + ", container=" + f.mayor.placedWorkContainerPos()
                    + ", carryLoad=" + f.mayor.getCarryLoad() + "]");
            helper.assertTrue(carried[0], "Mayor must visibly carry the real load");
            helper.assertTrue(f.mayor.getProfession() == Profession.MAYOR
                    && f.mayor.getUUID().equals(f.settlement.mayorId)
                    && f.warehouse.workers.isEmpty(),
                "Mayor courier work must not replace office or create ordinary employment");
            helper.assertTrue(f.warehouse.id.equals(f.settlement.mayorCourierWarehouseId),
                "the selected Warehouse must be persisted as Mayor logistics authority");
            Settlement reloaded = Settlement.readNbt(f.settlement.writeNbt());
            helper.assertTrue(f.warehouse.id.equals(reloaded.mayorCourierWarehouseId)
                    && reloaded.buildings.stream().anyMatch(building ->
                        f.warehouse.id.equals(building.id) && building.valid
                            && building.type == BuildingType.WAREHOUSE),
                "reload must retain only the exact valid Warehouse helper binding");
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "mayor_courier")
    public void mayorCannotStartCourierWorkAfterAssignedWarehouseIsRemoved(GameTestHelper helper) {
        Fixture f = fixture(helper, 4);
        CourierWorkGoal first = new CourierWorkGoal(f.mayor);
        helper.assertTrue(first.canUse() && f.warehouse.id.equals(f.settlement.mayorCourierWarehouseId),
            "a seated Mayor must select one valid Warehouse before taking a real route");
        helper.assertTrue(f.settlement.buildings.remove(f.warehouse),
            "fixture must remove the exact assigned Warehouse");
        CourierWorkGoal afterRemoval = new CourierWorkGoal(f.mayor);
        helper.assertTrue(!RequestLedgerService.validCourier(helper.getLevel(), f.settlement, f.mayor)
                && !afterRemoval.canUse() && f.mayor.bag.isEmpty()
                && count(f.hearth.getInventory(), Items.OAK_LOG) == 4,
            "a removed Warehouse must revoke Mayor courier authority before any cargo moves");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 1200, batch = "mayor_courier")
    public void mayorStillHaulsWhenWarehouseHasNoOrdinaryVacancy(GameTestHelper helper) {
        Fixture f = fixture(helper, 4);
        CourierWorkGoal mayorFirst = new CourierWorkGoal(f.mayor);
        helper.assertTrue(mayorFirst.canUse()
                && f.warehouse.id.equals(f.settlement.mayorCourierWarehouseId)
                && Employment.hasVacancy(f.settlement, f.warehouse),
            "Mayor-first logistics authority must leave the ordinary Courier post available");
        f.warehouse.workers.add(UUID.randomUUID());
        boolean[] carried = {false};
        helper.succeedWhen(() -> {
            carried[0] |= f.mayor.getActivity() == SettlerActivity.CARRYING;
            int delivered = count(f.chest(), Items.OAK_LOG);
            int hearth = count(f.hearth.getInventory(), Items.OAK_LOG);
            int bag = count(f.mayor.bag, Items.OAK_LOG);
            helper.assertTrue(delivered + hearth + bag == 4,
                "Mayor Courier must conserve every physical log when Warehouse posts are full");
            helper.assertTrue(delivered == 4 && carried[0],
                "Mayor must remain the automatic Courier and complete the real haul with no vacancy");
            helper.assertTrue(f.mayor.getProfession() == Profession.MAYOR
                    && f.warehouse.workers.size() == 1
                    && f.warehouse.id.equals(f.settlement.mayorCourierWarehouseId),
                "Mayor logistics must not evict the existing Warehouse worker or replace the Mayor office");
        });
    }

    private static Fixture fixture(GameTestHelper helper, int logs) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
        }
        // empty16 supplies a second floor at Y1. Both real contact disks must
        // be cleared before the Hearth/chest are placed, or Courier contact
        // anchors are embedded in the template rather than physically walkable.
        prepareContactArena(helper, 3, 3);
        prepareContactArena(helper, 9, 9);
        helper.getLevel().setDayTime(2_000);
        BlockPos hearthPos = helper.absolutePos(new BlockPos(3, 1, 3));
        helper.setBlock(new BlockPos(3, 1, 3), ModBlocks.HEARTH.get());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Mayorholm", hearthPos);
        settlement.radius = 12;
        SettlementSavedData.get(helper.getLevel()).settlements.put(settlement.id, settlement);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(hearthPos);
        helper.assertTrue(hearth != null, "fixture needs a real bound Hearth");
        hearth.bindSettlement(settlement.id);
        hearth.insertGoods(new ItemStack(Items.OAK_LOG, logs));
        Building warehouse = GameTestFixtures.register(helper, settlement,
            BuildingType.WAREHOUSE, 8, 8);
        helper.setBlock(new BlockPos(9, 1, 9), Blocks.CHEST);
        SettlerEntity mayor = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 4));
        mayor.setSettlerName("Mayor Mara");
        mayor.bindTo(settlement.id, settlement.center);
        settlement.putRecord(mayor.getUUID(), mayor.getSettlerName(), Profession.NONE);
        settlement.mayorId = mayor.getUUID();
        mayor.setProfessionProjection(Profession.MAYOR);
        return new Fixture(settlement, warehouse, mayor, hearth, helper);
    }

    /** empty16 has a template floor at Y1; own both legs' standable contact cells. */
    private static void prepareContactArena(GameTestHelper helper, int centreX, int centreZ) {
        for (int x = centreX - 1; x <= centreX + 1; x++)
            for (int z = centreZ - 1; z <= centreZ + 1; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
            }
    }

    private static int count(net.neoforged.neoforge.items.ItemStackHandler inventory,
                             net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (inventory.getStackInSlot(slot).is(item)) total += inventory.getStackInSlot(slot).getCount();
        }
        return total;
    }

    private static int count(Container inventory, net.minecraft.world.item.Item item) {
        int total = 0;
        if (inventory != null) for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) total += inventory.getItem(slot).getCount();
        }
        return total;
    }

    private record Fixture(Settlement settlement, Building warehouse, SettlerEntity mayor,
                           HearthBlockEntity hearth, GameTestHelper helper) {
        Container chest() {
            BlockEntity entity = helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(9, 1, 9)));
            return entity instanceof Container container ? container : null;
        }
    }
}
