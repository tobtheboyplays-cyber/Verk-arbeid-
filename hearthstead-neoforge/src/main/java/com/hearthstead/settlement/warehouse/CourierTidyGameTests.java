package com.hearthstead.settlement.warehouse;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.CourierWorkGoal;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.TechTreeTestGrants;
import com.hearthstead.settlement.request.CourierBatching;
import com.hearthstead.settlement.request.RequestLedger;
import com.hearthstead.settlement.request.RequestLedgerSavedData;
import com.hearthstead.settlement.request.RequestLedgerService;
import com.hearthstead.settlement.request.RequestPriority;
import com.hearthstead.settlement.request.RequestRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Courier tidying and Warehouse staff (batch {@code courier_tidy}): misplaced
 * stock reaches its group chest with nothing lost; two same-group chests
 * never trade stacks, partial stacks merge one way; a player's renamed chest
 * is left alone; four Couriers on a level-2 Warehouse split the work under
 * one tidy lease per chest; a real delivery beats the next tidy load; tidy
 * moves never join a Stout Straps batch, switch on or off.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class CourierTidyGameTests {
    private static final String BATCH = "courier_tidy";

    public CourierTidyGameTests() {
    }

    @GameTest(template = "empty16", timeoutTicks = 2400, batch = BATCH)
    public void misplacedStockReachesItsGroupChestWithNothingLost(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000L);
        Fixture f = fixture(helper, 1);
        // A mixed, unlabelled chest: the real Couriers (their own AI) tidy it.
        f.a.setItem(0, new ItemStack(Items.OAK_LOG, 4));
        f.a.setItem(1, new ItemStack(Items.COBBLESTONE, 4));
        f.a.setChanged();
        for (SettlerEntity courier : f.couriers) {
            courier.setHunger(100.0F);
            courier.setEnergy(100.0F);
        }
        helper.onEachTick(() -> {
            int logs = count(f.a, Items.OAK_LOG) + count(f.b, Items.OAK_LOG) + count(f.c, Items.OAK_LOG);
            int cobble = count(f.a, Items.COBBLESTONE) + count(f.b, Items.COBBLESTONE) + count(f.c, Items.COBBLESTONE);
            for (SettlerEntity courier : f.couriers) {
                logs += count(courier.bag, Items.OAK_LOG) + count(courier.batchStow, Items.OAK_LOG);
                cobble += count(courier.bag, Items.COBBLESTONE) + count(courier.batchStow, Items.COBBLESTONE);
            }
            helper.assertTrue(logs == 4 && cobble == 4, "conserved every tick: logs " + logs + ", cobble " + cobble);
        });
        helper.succeedWhen(() -> {
            boolean anyCarrying = false;
            for (SettlerEntity courier : f.couriers) {
                anyCarrying |= !courier.bag.isEmpty();
            }
            helper.assertTrue(!anyCarrying && unmixed(f.a) && unmixed(f.b) && unmixed(f.c)
                    && correctlyStored(helper, f.warehouse, f.aPos, f.a)
                    && correctlyStored(helper, f.warehouse, f.bPos, f.b)
                    && correctlyStored(helper, f.warehouse, f.cPos, f.c)
                    && count(f.a, Items.OAK_LOG) + count(f.b, Items.OAK_LOG) + count(f.c, Items.OAK_LOG) == 4
                    && count(f.a, Items.COBBLESTONE) + count(f.b, Items.COBBLESTONE) + count(f.c, Items.COBBLESTONE) == 4,
                "all stock separated into correctly labelled units [A logs " + count(f.a, Items.OAK_LOG)
                    + " cobble " + count(f.a, Items.COBBLESTONE) + ", B " + count(f.b, Items.OAK_LOG) + "/"
                    + count(f.b, Items.COBBLESTONE) + ", C " + count(f.c, Items.OAK_LOG) + "/"
                    + count(f.c, Items.COBBLESTONE) + ", couriers " + describe(helper, f) + "]");
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = BATCH)
    public void sameGroupChestsNeverTradeStacksAndPartialsMergeOneWay(GameTestHelper helper) {
        Fixture f = fixture(helper, 1);
        label(f.a, f.warehouse, "WOOD");
        label(f.b, f.warehouse, "WOOD");
        f.a.setItem(0, new ItemStack(Items.OAK_LOG, 64));
        f.a.setItem(1, new ItemStack(Items.OAK_LOG, 10));
        f.b.setItem(0, new ItemStack(Items.OAK_LOG, 30));
        ServerLevelView v = new ServerLevelView(helper, f);
        helper.assertTrue(WarehouseSorting.correctlyPlaced(helper.getLevel(), f.warehouse, f.aPos,
            f.a.getItem(0)), "a stack in its own group chest is correctly placed");
        helper.assertTrue(WarehouseSorting.mergeTarget(helper.getLevel(), f.warehouse, f.aPos, f.a.getItem(0)) == null,
            "a full stack never moves");
        helper.assertTrue(WarehouseSorting.mergeTarget(helper.getLevel(), f.warehouse, f.aPos, f.a.getItem(1)) == null,
            "the fuller chest's partial stays (no ping-pong)");
        helper.assertTrue(f.aPos.equals(WarehouseSorting.mergeTarget(helper.getLevel(), f.warehouse, f.bPos,
            f.b.getItem(0))), "the emptier chest's partial merges into the fuller one");
        v.touch();
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = BATCH)
    public void aPlayersRenamedChestIsNeverTidied(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000L);
        Fixture f = fixture(helper, 1);
        ItemStack named = new ItemStack(Items.CHEST);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("My stuff"));
        ChestBlockEntity mine = (ChestBlockEntity) helper.getLevel().getBlockEntity(f.aPos);
        mine.applyComponentsFromItemStack(named);
        helper.assertTrue(WarehouseSorting.playerOwned(mine), "the renamed chest reads as the player's own");
        f.a.setItem(0, new ItemStack(Items.OAK_LOG, 12));
        f.a.setItem(1, new ItemStack(Items.COBBLESTONE, 20));
        SettlerEntity courier = f.couriers.getFirst();
        courier.setNoAi(true);
        CourierWorkGoal goal = new CourierWorkGoal(courier);
        goal.canUse();
        RequestLedger ledger = RequestLedgerSavedData.get(helper.getLevel()).existing(f.settlement.id);
        if (ledger != null) {
            for (RequestRecord row : ledger.active()) {
                helper.assertTrue(!f.aPos.equals(row.sourceContainer()) && !f.aPos.equals(row.targetContainer()),
                    "no Courier move touches the player's chest");
            }
        }
        helper.assertTrue(count(f.a, Items.OAK_LOG) == 12 && count(f.a, Items.COBBLESTONE) == 20,
            "the renamed chest keeps everything");
        helper.assertTrue(WarehouseSorting.assignedGroup(helper.getLevel().getBlockEntity(f.aPos)) == null,
            "and it is never labelled");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = BATCH)
    public void fourCouriersOnALevelTwoWarehouseSplitTheWork(GameTestHelper helper) {
        Fixture f = fixture(helper, 2);
        helper.assertTrue(f.couriers.size() == 4, "a level-2 Warehouse hires four Couriers");
        SettlerEntity fifth = spawnCourier(helper, f, "Fifth");
        helper.assertTrue(!Employment.hire(helper.getLevel(), f.settlement, f.warehouse, fifth).ok(),
            "the fifth is refused");
        f.warehouse.level = 1;
        helper.assertTrue(!Employment.hasVacancy(f.settlement, f.warehouse), "level 1 hires no more");
        f.warehouse.level = 2;
        // One tidy lease per chest: Courier 0 owns a tidy move out of chest A.
        // Two blocks: a light load any fresh Courier's weight budget takes.
        f.a.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
        label(f.c, f.warehouse, "BUILDING_MATERIALS");
        RequestLedgerService.Decision tidy = RequestLedgerService.openOutputPickup(helper.getLevel(), f.settlement,
            f.warehouse, f.aPos, 0, f.warehouse, f.cPos, 2, RequestPriority.NORMAL);
        helper.assertTrue(tidy.request() != null, "tidy row opens: " + tidy);
        SettlerEntity tidier = f.couriers.getFirst();
        helper.assertTrue(RequestLedgerService.reserve(helper.getLevel(), f.settlement, tidy.request().id(), tidier)
            .accepted(), "Courier 0 takes the tidy move");
        helper.assertTrue(RequestLedgerService.tidyLeased(helper.getLevel(), f.settlement, f.aPos, f.couriers.get(1))
                && RequestLedgerService.tidyLeased(helper.getLevel(), f.settlement, f.cPos, f.couriers.get(2)),
            "the other Couriers see both chests of that move as leased");
        helper.assertTrue(!RequestLedgerService.tidyLeased(helper.getLevel(), f.settlement, f.aPos, tidier),
            "its own lease never blocks the owner");
        // Three output rows at the Lumber Camp for the other three: distinct owners.
        for (int slot = 0; slot < 3; slot++) {
            f.camp.setItem(slot, new ItemStack(slot % 2 == 0 ? Items.OAK_LOG : Items.BIRCH_LOG, 2));
            RequestLedgerService.Decision opened = RequestLedgerService.openOutputPickup(helper.getLevel(),
                f.settlement, f.lumberCamp, f.campPos, slot, f.warehouse, f.bPos, 2, RequestPriority.NORMAL);
            helper.assertTrue(opened.request() != null, "open row " + slot + ": " + opened);
        }
        Set<UUID> claimed = new HashSet<>();
        claimed.add(tidy.request().id());
        for (SettlerEntity courier : f.couriers.subList(1, 4)) {
            RequestLedgerService.Decision decision = RequestLedgerService.claimNextOutput(helper.getLevel(),
                f.settlement, courier);
            helper.assertTrue(decision.accepted() && decision.request() != null
                && claimed.add(decision.request().id()), "each Courier owns its own row: " + decision);
        }
        RequestLedger ledger = RequestLedgerSavedData.get(helper.getLevel()).existing(f.settlement.id);
        helper.assertTrue(ledger != null && !ledger.quarantined(), "four Couriers, no quarantine");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = BATCH)
    public void aRealDeliveryComesBeforeTheNextTidyLoad(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000L);
        Fixture f = fixture(helper, 1);
        f.a.setItem(0, new ItemStack(Items.OAK_LOG, 12));
        f.a.setItem(1, new ItemStack(Items.COBBLESTONE, 20));
        f.camp.setItem(0, new ItemStack(Items.BIRCH_LOG, 4));
        RequestLedgerService.Decision real = RequestLedgerService.openOutputPickup(helper.getLevel(),
            f.settlement, f.lumberCamp, f.campPos, 0, f.warehouse, f.bPos, 4, RequestPriority.NORMAL);
        helper.assertTrue(real.request() != null, "a real output row waits: " + real);
        SettlerEntity courier = f.couriers.getFirst();
        courier.setNoAi(true);
        CourierWorkGoal goal = new CourierWorkGoal(courier);
        goal.canUse();
        RequestLedger ledger = RequestLedgerSavedData.get(helper.getLevel()).existing(f.settlement.id);
        RequestRecord row = ledger.active(real.request().id());
        helper.assertTrue(row != null && courier.getUUID().equals(row.courierId()),
            "the Courier takes the real delivery, not the tidy move");
        for (RequestRecord other : ledger.active()) {
            helper.assertTrue(!RequestLedgerService.storageSort(other) || other.courierId() == null,
                "no tidy move is owned while real work waits");
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = BATCH)
    public void aTidyMoveNeverJoinsABatchSwitchOnOrOff(GameTestHelper helper) {
        Fixture f = fixture(helper, 1);
        TechTreeTestGrants.grant(Development.of(helper.getLevel(), f.settlement), CourierBatching.NODE);
        SettlerEntity courier = f.couriers.getFirst();
        courier.setCarryCapacity(16);
        try {
            for (boolean on : new boolean[]{true, false}) {
                CourierBatching.overrideForTests(on);
                f.a.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
                label(f.c, f.warehouse, "BUILDING_MATERIALS");
                RequestLedgerService.Decision tidy = RequestLedgerService.openOutputPickup(helper.getLevel(),
                    f.settlement, f.warehouse, f.aPos, 0, f.warehouse, f.cPos, 2, RequestPriority.NORMAL);
                helper.assertTrue(tidy.request() != null, "tidy opens: " + tidy);
                UUID tidyId = tidy.request().id();
                helper.assertTrue(RequestLedgerService.reserve(helper.getLevel(), f.settlement, tidyId, courier).accepted()
                    && RequestLedgerService.pickup(helper.getLevel(), f.settlement, tidyId, courier).accepted(),
                    "the tidy load is lifted");
                f.camp.setItem(0, new ItemStack(Items.BIRCH_LOG, 2));
                RequestLedgerService.Decision partner = RequestLedgerService.openOutputPickup(helper.getLevel(),
                    f.settlement, f.lumberCamp, f.campPos, 0, f.warehouse, f.bPos, 2, RequestPriority.NORMAL);
                helper.assertTrue(partner.request() != null, "a nearby partner waits");
                RequestLedgerService.Decision batch = RequestLedgerService.reserveBatchPartner(helper.getLevel(),
                    f.settlement, courier, courier.blockPosition());
                helper.assertTrue(batch.outcome() != RequestLedgerService.Outcome.COMMITTED
                        && courier.batchStow.isEmpty() && count(courier.bag, Items.COBBLESTONE) == 2,
                    "switch " + on + ": a tidy load never starts a batch");
                // Deliver the tidy load and clear the partner for the next round.
                courier.teleportTo(f.cPos.getX() + 1.5D, f.cPos.getY(), f.cPos.getZ() + 0.5D);
                helper.assertTrue(RequestLedgerService.deliver(helper.getLevel(), f.settlement, tidyId, courier).outcome()
                    == RequestLedgerService.Outcome.SATISFIED, "tidy delivered");
                courier.teleportTo(4.5D + f.origin().getX(), f.origin().getY() + 1, 4.5D + f.origin().getZ());
                f.c.removeItemNoUpdate(0);
                f.camp.removeItemNoUpdate(0);
            }
            RequestLedger ledger = RequestLedgerSavedData.get(helper.getLevel()).existing(f.settlement.id);
            helper.assertTrue(ledger != null && !ledger.quarantined(), "ledger healthy");
        } finally {
            CourierBatching.overrideForTests(null);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------ helpers

    /** A walkable arena: establish support and clear the template terrain above it. */
    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static String describe(GameTestHelper helper, Fixture f) {
        StringBuilder out = new StringBuilder();
        for (SettlerEntity courier : f.couriers) {
            out.append(courier.getActivity()).append('@').append(courier.blockPosition().subtract(f.origin()))
                .append(" bag ").append(courier.bag.isEmpty() ? 0 : 1)
                .append(" stop ").append(courier.logisticsStopReason()).append("; ");
        }
        return out.toString();
    }

    private static boolean correctlyStored(GameTestHelper helper, Building warehouse,
                                           BlockPos position, Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && !WarehouseSorting.correctlyPlaced(
                    helper.getLevel(), warehouse, position, stack)) return false;
        }
        return true;
    }

    private static boolean unmixed(Container c) {
        return count(c, Items.OAK_LOG) == 0 || count(c, Items.COBBLESTONE) == 0;
    }

    private static void label(Container container, Building warehouse, String group) {
        if (container instanceof BlockEntity be) {
            be.getPersistentData().putUUID("HearthsteadWarehouseSortingWarehouse", warehouse.id);
            be.getPersistentData().putString("HearthsteadWarehouseSortingGroup", group);
            be.setChanged();
        }
    }

    private static int count(Container container, Item item) {
        int n = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private static SettlerEntity spawnCourier(GameTestHelper helper, Fixture f, String name) {
        SettlerEntity courier = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 4));
        courier.setSettlerName(name);
        courier.bindTo(f.settlement.id, f.settlement.center);
        f.settlement.putRecord(courier.getUUID(), name, Profession.NONE);
        return courier;
    }

    private static Fixture fixture(GameTestHelper helper, int warehouseLevel) {
        floor(helper);
        var data = SettlementManager.data(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Tidyholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 24;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        Building warehouse = GameTestFixtures.register(helper, settlement, BuildingType.WAREHOUSE, 2, 2);
        warehouse.level = warehouseLevel;
        Building lumberCamp = GameTestFixtures.register(helper, settlement, BuildingType.LUMBER_CAMP, 9, 2);
        BlockPos aRel = new BlockPos(3, 1, 3);
        BlockPos bRel = new BlockPos(5, 1, 3);
        BlockPos cRel = new BlockPos(3, 1, 5);
        BlockPos campRel = new BlockPos(10, 1, 3);
        for (BlockPos rel : new BlockPos[]{aRel, bRel, cRel, campRel}) {
            helper.setBlock(rel, Blocks.CHEST);
        }
        Fixture f = new Fixture(settlement, warehouse, lumberCamp, new java.util.ArrayList<>(),
            helper.absolutePos(aRel), helper.absolutePos(bRel), helper.absolutePos(cRel), helper.absolutePos(campRel),
            container(helper, aRel), container(helper, bRel), container(helper, cRel), container(helper, campRel),
            helper.absolutePos(BlockPos.ZERO));
        int wanted = warehouse.workerCapacity();
        for (int i = 0; i < wanted; i++) {
            SettlerEntity courier = spawnCourier(helper, f, "Courier " + i);
            helper.assertTrue(Employment.hire(helper.getLevel(), settlement, warehouse, courier).ok(),
                "hire Courier " + i + " of " + wanted);
            f.couriers.add(courier);
        }
        return f;
    }

    private static Container container(GameTestHelper helper, BlockPos relative) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(relative));
        return be instanceof Container c ? c : null;
    }

    /** Keeps the fixture's chests referenced for the static-rule test. */
    private record ServerLevelView(GameTestHelper helper, Fixture f) {
        void touch() {
            f.a.setChanged();
            f.b.setChanged();
        }
    }

    private record Fixture(Settlement settlement, Building warehouse, Building lumberCamp,
                           List<SettlerEntity> couriers, BlockPos aPos, BlockPos bPos, BlockPos cPos,
                           BlockPos campPos, Container a, Container b, Container c, Container camp,
                           BlockPos origin) {
    }
}
