package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Production;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.request.CraftingOrderBook;
import com.hearthstead.settlement.request.CraftingOrderSavedData;
import com.hearthstead.settlement.request.CraftingOrderService;
import com.hearthstead.settlement.request.RequestBlocker;
import com.hearthstead.settlement.request.RequestLedgerSnapshot;
import com.hearthstead.settlement.request.RequestType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Logistics M1 "Crafting orders" (minecolonies-logistics-plan.md). Bare,
 * UNREGISTERED settlements (the ChainsGameTests convention): the resolver
 * reads only the Settlement object and building containers, so no
 * BuildingManager sweep can dissolve these fixtures. World time is never
 * touched.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class CraftingOrderGameTests {

    private static final String BATCH = "crafting_orders";

    private record Arena(Settlement settlement, Building bakery, Building smelter,
                         Building warehouse, Container bakeryChest,
                         Container smelterChest, Container warehouseChest) {
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    private static Building building(GameTestHelper helper, BuildingType type,
                                     int x0, int z0, int x1, int z1, boolean staffed) {
        BlockPos min = helper.absolutePos(new BlockPos(x0, 1, z0));
        BlockPos max = helper.absolutePos(new BlockPos(x1, 3, z1));
        Building b = new Building(UUID.randomUUID(), type, min, min,
            BoundingBox.fromCorners(min, max));
        b.valid = true;
        if (staffed) b.workers.add(UUID.randomUUID());
        return b;
    }

    private static Container chest(GameTestHelper helper, int x, int z) {
        helper.setBlock(new BlockPos(x, 1, z), Blocks.CHEST);
        return (Container) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(x, 1, z)));
    }

    private static Settlement settlement(GameTestHelper helper) {
        Settlement s = new Settlement(UUID.randomUUID(), "Ordreby",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 8;
        return s;
    }

    /** Bakery with wheat but no fire, Smelter with logs/raw iron/coal, empty Warehouse. */
    private static Arena fuelArena(GameTestHelper helper) {
        floor(helper);
        Settlement s = settlement(helper);
        Building bakery = building(helper, BuildingType.BAKERY, 1, 1, 4, 4, true);
        Building smelter = building(helper, BuildingType.SMELTER, 6, 1, 9, 4, true);
        Building warehouse = building(helper, BuildingType.WAREHOUSE, 11, 1, 14, 4, false);
        s.buildings.add(bakery);
        s.buildings.add(smelter);
        s.buildings.add(warehouse);
        Container bakeryChest = chest(helper, 2, 2);
        Container smelterChest = chest(helper, 7, 2);
        Container warehouseChest = chest(helper, 12, 2);
        bakeryChest.setItem(0, new ItemStack(Items.WHEAT, 6));
        smelterChest.setItem(0, new ItemStack(Items.OAK_LOG, 8));
        smelterChest.setItem(1, new ItemStack(Items.RAW_IRON, 6));
        smelterChest.setItem(2, new ItemStack(Items.COAL, 8));
        return new Arena(s, bakery, smelter, warehouse, bakeryChest, smelterChest, warehouseChest);
    }

    private static List<CraftingOrderBook.Order> orders(GameTestHelper helper, Settlement s) {
        return CraftingOrderService.orders(helper.getLevel(), s);
    }

    private static int count(Container c, Item item) {
        int total = 0;
        for (int slot = 0; slot < c.getContainerSize(); slot++) {
            if (c.getItem(slot).is(item)) total += c.getItem(slot).getCount();
        }
        return total;
    }

    private static void move(Container from, Container to, Item item) {
        for (int slot = 0; slot < from.getContainerSize(); slot++) {
            ItemStack stack = from.getItem(slot);
            if (stack.isEmpty() || !stack.is(item)) continue;
            for (int target = 0; target < to.getContainerSize(); target++) {
                if (to.getItem(target).isEmpty()) {
                    to.setItem(target, stack.copy());
                    from.setItem(slot, ItemStack.EMPTY);
                    break;
                }
            }
        }
    }

    /** Crafts the preferred recipe exactly as CrafterWorkGoal does, N times. */
    private static void craftPreferred(GameTestHelper helper, Arena arena, int batches) {
        for (int i = 0; i < batches; i++) {
            Production.Recipe recipe = Production.ready(helper.getLevel(), arena.smelter(),
                CraftingOrderService.preferredOutput(helper.getLevel(), arena.settlement(), arena.smelter()));
            helper.assertTrue(recipe != null, "the smelter should have ready work");
            helper.assertTrue(Production.run(helper.getLevel(), arena.smelter(), recipe),
                "the preferred recipe should run");
            CraftingOrderService.noteCrafted(helper.getLevel(), arena.settlement(), arena.smelter(), recipe);
        }
    }

    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 100)
    public void fuelDeficitWithEmptyWarehouseOpensExactlyOneOrder(GameTestHelper helper) {
        Arena arena = fuelArena(helper);
        helper.assertTrue(Production.starvedForFuel(helper.getLevel(), arena.bakery()),
            "fixture: the bakery should be idle for want of fuel");
        CraftingOrderService.scan(helper.getLevel(), arena.settlement());
        CraftingOrderService.scan(helper.getLevel(), arena.settlement());
        List<CraftingOrderBook.Order> orders = orders(helper, arena.settlement());
        helper.assertTrue(orders.size() == 1, "exactly one order expected, saw " + orders.size());
        CraftingOrderBook.Order order = orders.get(0);
        helper.assertTrue(order.requesterId().equals(arena.bakery().id), "requester is the bakery");
        helper.assertTrue(order.itemId().equals(BuiltInRegistries.ITEM.getKey(Items.CHARCOAL)),
            "a cold bakery orders charcoal, got " + order.itemId());
        helper.assertTrue(arena.smelter().id.equals(order.workshopId()),
            "the smelter (charcoal recipe, staffed) is the workshop");
        helper.assertTrue(order.status() == CraftingOrderBook.Status.OPEN
            && order.source() == CraftingOrderBook.Source.FUEL, "open fuel order");
        List<RequestLedgerSnapshot.Row> rows = CraftingOrderService.snapshotRows(
            helper.getLevel(), arena.settlement(), 16);
        helper.assertTrue(rows.size() == 1 && rows.get(0).type() == RequestType.CRAFT_ORDER
            && rows.get(0).blocker() == RequestBlocker.AWAITING_CRAFT,
            "Tasks shows one craft-order row awaiting the workshop");
        helper.succeed();
    }

    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 100)
    public void crafterPrefersOrderedRecipeAndCountsBatches(GameTestHelper helper) {
        Arena arena = fuelArena(helper);
        Production.Recipe before = Production.ready(helper.getLevel(), arena.smelter());
        helper.assertTrue(before != null && before.output() != Items.CHARCOAL,
            "fixture: without an order the smelter's default choice is not charcoal");
        CraftingOrderService.scan(helper.getLevel(), arena.settlement());
        CraftingOrderBook.Order order = orders(helper, arena.settlement()).get(0);
        int ordered = order.count();
        craftPreferred(helper, arena, ordered);
        helper.assertTrue(count(arena.smelterChest(), Items.CHARCOAL) == ordered,
            "the smelter made the ordered charcoal, saw " + count(arena.smelterChest(), Items.CHARCOAL));
        helper.assertTrue(order.made() == ordered && order.status() == CraftingOrderBook.Status.CRAFTED,
            "made " + order.made() + " status " + order.status());
        helper.assertTrue(count(arena.smelterChest(), Items.RAW_IRON) == 6,
            "no raw iron was spent while the order was preferred");
        helper.succeed();
    }

    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 100)
    public void deliveryClosesOrderExactlyOnce(GameTestHelper helper) {
        Arena arena = fuelArena(helper);
        CraftingOrderService.scan(helper.getLevel(), arena.settlement());
        CraftingOrderBook.Order order = orders(helper, arena.settlement()).get(0);
        craftPreferred(helper, arena, order.count());
        // The courier hop, hand-simulated as in ChainsGameTests.
        move(arena.smelterChest(), arena.bakeryChest(), Items.CHARCOAL);
        CraftingOrderService.scan(helper.getLevel(), arena.settlement());
        CraftingOrderService.scan(helper.getLevel(), arena.settlement());
        List<CraftingOrderBook.Order> after = orders(helper, arena.settlement());
        helper.assertTrue(after.size() == 1, "no second order after delivery, saw " + after.size());
        helper.assertTrue(after.get(0).status() == CraftingOrderBook.Status.FULFILLED,
            "delivered order is fulfilled, saw " + after.get(0).status());
        CraftingOrderBook book = CraftingOrderSavedData.get(helper.getLevel()).book(arena.settlement().id);
        helper.assertFalse(book.close(after.get(0), true, helper.getLevel().getGameTime()),
            "a second close of the same order is refused");
        helper.succeed();
    }

    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 100)
    public void duplicateNeedsShareOneOrderAndCapHolds(GameTestHelper helper) {
        Arena arena = fuelArena(helper);
        CraftingOrderBook.OpenDecision first = CraftingOrderService.requestCraft(helper.getLevel(),
            arena.settlement(), arena.bakery(), Items.CHARCOAL, 4, CraftingOrderBook.Source.DIRECT);
        CraftingOrderBook.OpenDecision again = CraftingOrderService.requestCraft(helper.getLevel(),
            arena.settlement(), arena.bakery(), Items.CHARCOAL, 9, CraftingOrderBook.Source.FUEL);
        helper.assertTrue(first.result() == CraftingOrderBook.OpenResult.CREATED
            && again.result() == CraftingOrderBook.OpenResult.DUPLICATE
            && again.order() == first.order(), "same (building,item) reuses the live order");
        int created = 1;
        int capped = 0;
        for (int i = 0; i < 40; i++) {
            Building requester = building(helper, BuildingType.SMITHY, 1, 6, 2, 7, false);
            CraftingOrderBook.OpenDecision d = CraftingOrderService.requestCraft(helper.getLevel(),
                arena.settlement(), requester, Items.CHARCOAL, 2, CraftingOrderBook.Source.DIRECT);
            if (d.result() == CraftingOrderBook.OpenResult.CREATED) created++;
            if (d.result() == CraftingOrderBook.OpenResult.CAPPED) capped++;
        }
        helper.assertTrue(created == CraftingOrderBook.MAX_ACTIVE,
            "cap is 32 open orders per settlement, created " + created);
        helper.assertTrue(capped == 41 - CraftingOrderBook.MAX_ACTIVE, "the rest are refused, capped " + capped);
        helper.succeed();
    }

    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 100)
    public void noWorkshopRecipeProducesNeedsYouBlocker(GameTestHelper helper) {
        floor(helper);
        Settlement s = settlement(helper);
        Building mill = building(helper, BuildingType.MILL, 1, 1, 4, 4, true);
        Building warehouse = building(helper, BuildingType.WAREHOUSE, 11, 1, 14, 4, false);
        s.buildings.add(mill);
        s.buildings.add(warehouse);
        chest(helper, 2, 2);
        chest(helper, 12, 2);
        CraftingOrderService.scan(helper.getLevel(), s);
        List<CraftingOrderBook.Order> orders = orders(helper, s);
        helper.assertTrue(orders.size() == 1, "one needs-you record, saw " + orders.size());
        CraftingOrderBook.Order order = orders.get(0);
        helper.assertTrue(order.status() == CraftingOrderBook.Status.NEEDS_PLAYER
            && order.workshopId() == null, "no workshop makes mill inputs: player must supply");
        List<RequestLedgerSnapshot.Row> rows = CraftingOrderService.snapshotRows(helper.getLevel(), s, 16);
        helper.assertTrue(rows.size() == 1 && rows.get(0).blocker() == RequestBlocker.NEEDS_PLAYER,
            "Tasks shows a needs-you blocker row");
        helper.succeed();
    }

    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 100)
    public void orderSurvivesSaveReloadRoundTrip(GameTestHelper helper) {
        Arena arena = fuelArena(helper);
        CraftingOrderService.scan(helper.getLevel(), arena.settlement());
        craftPreferred(helper, arena, 1);
        CraftingOrderSavedData saved = CraftingOrderSavedData.get(helper.getLevel());
        CraftingOrderBook.Order before = saved.book(arena.settlement().id).active().get(0);
        CompoundTag tag = saved.save(new CompoundTag(), helper.getLevel().registryAccess());
        CraftingOrderSavedData reloaded = CraftingOrderSavedData.load(tag, helper.getLevel().registryAccess());
        CraftingOrderBook book = reloaded.book(arena.settlement().id);
        helper.assertTrue(book.active().size() == 1, "one live order after reload");
        CraftingOrderBook.Order after = book.active().get(0);
        helper.assertTrue(after.id().equals(before.id()) && after.made() == before.made()
            && after.status() == before.status() && after.count() == before.count()
            && after.itemId().equals(before.itemId())
            && java.util.Objects.equals(after.workshopId(), before.workshopId()),
            "order identity, progress and workshop survive the round trip");
        saved.replace(arena.settlement().id, book);
        CraftingOrderService.scan(helper.getLevel(), arena.settlement());
        helper.assertTrue(orders(helper, arena.settlement()).size() == 1,
            "a scan after reload does not open a duplicate");
        helper.succeed();
    }
}
