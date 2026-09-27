package com.hearthstead.settlement.request;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Pins the two request-ledger stalls the worker-watchdog soak found on a copy
 * of the owner's Elmfield world (2026-09-25):
 *
 * <ol>
 *   <li>A shared Warehouse chest lost stock to another consumer between the
 *       row opening and its final deposit. The satisfaction proof compared the
 *       whole chest against the count seen at open, failed, and quarantined
 *       the entire settlement ledger: every Courier froze.</li>
 *   <li>A Farmhouse whose bounds overlapped the Warehouse wall offered the
 *       Warehouse's own chests as farm output, opening pointless
 *       Warehouse-to-Warehouse rows that raced real deliveries into them.</li>
 * </ol>
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class RequestLedgerSoakRegressionGameTests {

    @GameTest(template = "empty16", timeoutTicks = 40, batch = "request_ledger_authority")
    public void sharedWarehouseWithdrawalDuringDeliveryDoesNotQuarantine(GameTestHelper helper) {
        var data = SettlementManager.data(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Soakholm",
            helper.absolutePos(new BlockPos(7, 1, 7)));
        settlement.radius = 24;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        Building camp = GameTestFixtures.register(helper, settlement, BuildingType.LUMBER_CAMP, 2, 2);
        Building warehouse = GameTestFixtures.register(helper, settlement, BuildingType.WAREHOUSE, 9, 2);
        BlockPos sourceRel = new BlockPos(3, 1, 3);
        BlockPos targetRel = new BlockPos(10, 1, 3);
        helper.setBlock(sourceRel, Blocks.CHEST);
        helper.setBlock(targetRel, Blocks.CHEST);
        Container source = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(sourceRel));
        Container target = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(targetRel));
        source.setItem(0, new ItemStack(Items.OAK_LOG, 4));
        target.setItem(0, new ItemStack(Items.OAK_LOG, 10));
        source.setChanged();
        target.setChanged();
        SettlerEntity courier = courier(helper, settlement, warehouse, "Carrier");

        RequestLedgerService.Decision opened = RequestLedgerService.openOutputPickup(
            helper.getLevel(), settlement, camp, helper.absolutePos(sourceRel), 0,
            warehouse, helper.absolutePos(targetRel), 4, RequestPriority.URGENT);
        helper.assertTrue(opened.accepted() && opened.request() != null, "row must open");
        UUID id = opened.request().id();
        helper.assertTrue(RequestLedgerService.reserve(helper.getLevel(), settlement, id, courier)
            .accepted(), "reserve");
        helper.assertTrue(RequestLedgerService.pickup(helper.getLevel(), settlement, id, courier)
            .accepted(), "pickup");

        // Another consumer (a food run, a crafter restock) legitimately takes
        // logs out of the same Warehouse chest while the Courier walks.
        target.removeItem(0, 7);
        target.setChanged();

        BlockPos t = helper.absolutePos(targetRel);
        courier.teleportTo(t.getX() + 1.5D, t.getY(), t.getZ() + 0.5D);
        RequestLedgerService.Decision delivered = RequestLedgerService.deliver(
            helper.getLevel(), settlement, id, courier);
        helper.assertTrue(delivered.outcome() == RequestLedgerService.Outcome.SATISFIED,
            "a proven deposit must satisfy even after an unrelated withdrawal, got "
                + delivered.outcome());
        RequestLedger ledger = RequestLedgerSavedData.get(helper.getLevel()).existing(settlement.id);
        helper.assertTrue(ledger != null && !ledger.quarantined(),
            "one withdrawal must never quarantine the whole settlement ledger");
        helper.assertTrue(target.getItem(0).getCount() == 7,
            "3 left after the withdrawal plus the 4 delivered");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40, batch = "request_ledger_authority")
    public void producerOverlappingWarehouseCannotShipWarehouseStockToItself(GameTestHelper helper) {
        var data = SettlementManager.data(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Wallholm",
            helper.absolutePos(new BlockPos(7, 1, 7)));
        settlement.radius = 24;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        Building warehouse = GameTestFixtures.register(helper, settlement, BuildingType.WAREHOUSE, 9, 2);
        // The farmhouse shares the Warehouse's z=3..5 wall rows, like Elmfield's
        // farmhouse and warehouse at z=-111.
        Building farmhouse = GameTestFixtures.registerWithBounds(helper, settlement,
            BuildingType.FARMHOUSE, new BlockPos(10, 1, 7), new BlockPos(10, 2, 9),
            BoundingBox.fromCorners(helper.absolutePos(new BlockPos(9, 1, 5)),
                helper.absolutePos(new BlockPos(12, 3, 8))));
        BlockPos sharedRel = new BlockPos(11, 1, 5);
        BlockPos targetRel = new BlockPos(10, 1, 3);
        helper.setBlock(sharedRel, Blocks.CHEST);
        helper.setBlock(targetRel, Blocks.CHEST);
        Container shared = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(sharedRel));
        shared.setItem(0, new ItemStack(Items.CARROT, 8));
        shared.setChanged();
        helper.assertTrue(warehouse.contains(helper.absolutePos(sharedRel))
                && farmhouse.contains(helper.absolutePos(sharedRel)),
            "fixture: the chest must sit inside both buildings");

        RequestLedgerService.Decision opened = RequestLedgerService.openOutputPickup(
            helper.getLevel(), settlement, farmhouse, helper.absolutePos(sharedRel), 0,
            warehouse, helper.absolutePos(targetRel), 8, RequestPriority.NORMAL);
        helper.assertTrue(!opened.accepted() && opened.blocker() == RequestBlocker.TARGET_INVALID,
            "goods already inside the Warehouse are not farm output, got " + opened.outcome());
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40, batch = "request_ledger_authority")
    public void shrunkFoodSourceBeforeLiftExpiresInsteadOfWedgingTheCourier(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, com.hearthstead.registry.ModBlocks.HEARTH.get());
        var data = SettlementManager.data(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Larderholm",
            helper.absolutePos(hearthRel));
        settlement.radius = 12;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        ((com.hearthstead.block.HearthBlockEntity) helper.getLevel()
            .getBlockEntity(helper.absolutePos(hearthRel))).bindSettlement(settlement.id);
        Building warehouse = GameTestFixtures.register(helper, settlement, BuildingType.WAREHOUSE, 9, 2);
        BlockPos chestRel = new BlockPos(10, 1, 3);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(chestRel));
        chest.setItem(0, new ItemStack(Items.BREAD, 12));
        chest.setChanged();
        SettlerEntity courier = courier(helper, settlement, warehouse, "Larder");
        courier.setNoAi(true);

        RequestLedgerService.Decision opened = RequestLedgerService.openFoodDelivery(
            helper.getLevel(), settlement, warehouse, helper.absolutePos(chestRel), 0, 4);
        helper.assertTrue(opened.accepted() && opened.request() != null, "food row must open");
        UUID id = opened.request().id();
        helper.assertTrue(RequestLedgerService.reserve(helper.getLevel(), settlement, id, courier)
            .accepted(), "reserve");

        // A player (or the tidy pass) takes most of the bread before the lift.
        chest.setItem(0, new ItemStack(Items.BREAD, 2));
        chest.setChanged();
        // Exactly what CourierSourceBagSession does when its live source check
        // fails before the first unit is lifted.
        RequestLedgerService.block(helper.getLevel(), settlement, id, courier,
            RequestBlocker.FINGERPRINT_MISMATCH);

        RequestLedgerService.Route route = RequestLedgerService.routeForCourier(
            helper.getLevel(), settlement, courier);
        RequestRecord row = RequestLedgerSavedData.get(helper.getLevel()).existing(settlement.id).any(id);
        helper.assertTrue(route.request() == null && row != null && row.state().terminal(),
            "a cargo-free food row on a vanished stack must expire, not wedge the Courier; got "
                + route.outcome() + " state=" + (row == null ? "null" : row.state()));
        helper.assertTrue(courier.bag.isEmpty() && chest.getItem(0).getCount() == 2,
            "expiry moves nothing");
        RequestLedgerService.Decision fresh = RequestLedgerService.openFoodDelivery(
            helper.getLevel(), settlement, warehouse, helper.absolutePos(chestRel), 0, 2);
        helper.assertTrue(fresh.outcome() == RequestLedgerService.Outcome.COMMITTED,
            "the dead row must no longer block a fresh bread delivery as a duplicate, got "
                + fresh.outcome());
        helper.succeed();
    }

    private static SettlerEntity courier(GameTestHelper helper, Settlement settlement,
                                         Building warehouse, String name) {
        SettlerEntity courier = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 4));
        courier.setSettlerName(name);
        courier.bindTo(settlement.id, settlement.center);
        settlement.putRecord(courier.getUUID(), name, Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, warehouse, courier).ok(),
            "fixture Warehouse must hire the Courier");
        return courier;
    }
}
