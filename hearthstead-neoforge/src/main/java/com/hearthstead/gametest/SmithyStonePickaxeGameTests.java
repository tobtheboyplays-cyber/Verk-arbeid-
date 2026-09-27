package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Production;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.request.CraftingOrderBook;
import com.hearthstead.settlement.request.CraftingOrderService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
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
 * Owner decision 26 Sep ("Steinhakke hos smeden"): with no iron in town, a
 * Miner's "Needs: pickaxe" becomes a crafting order for a STONE pickaxe at
 * the smithy (cobblestone, fired with wood); that pick really mines iron
 * ore; and once iron is in stock the same requests are ordered as iron
 * tools again -- the axe, and the Herder's shears, which the smithy now
 * forges.
 *
 * <p>The order half uses a bare, unregistered settlement (the
 * CraftingOrderGameTests convention: the resolver reads only the Settlement
 * object and building chests). The mining half is the real MinerWorkGoal in
 * a registered settlement (the MinerDropsGameTests fixture).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class SmithyStonePickaxeGameTests {

    private static final String BATCH = "smithy_stone_pickaxe";

    private record Forge(Settlement settlement, Building smithy, Container smithyChest,
                         Container warehouseChest) {
    }

    private static void clearArena(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.OAK_PLANKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static Building bare(GameTestHelper helper, BuildingType type,
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

    /** Staffed smithy (x11-14, z11-14) and an empty warehouse (x11-14, z6-8),
     *  far from the mining corner. */
    private static Forge forge(GameTestHelper helper) {
        Settlement s = new Settlement(UUID.randomUUID(), "Steinby",
            helper.absolutePos(new BlockPos(12, 1, 12)));
        s.radius = 8;
        Building smithy = bare(helper, BuildingType.SMITHY, 11, 11, 14, 14, true);
        Building warehouse = bare(helper, BuildingType.WAREHOUSE, 11, 6, 14, 8, false);
        s.buildings.add(smithy);
        s.buildings.add(warehouse);
        Container smithyChest = chest(helper, 12, 12);
        Container warehouseChest = chest(helper, 12, 7);
        smithyChest.setItem(0, new ItemStack(Items.OAK_LOG, 8)); // the forge's wood
        return new Forge(s, smithy, smithyChest, warehouseChest);
    }

    /** A workplace with one open "needs a tool" request, as refreshFor opens it. */
    private static Building requesting(GameTestHelper helper, Forge forge, BuildingType type,
                                       Profession profession, int x0) {
        Building workplace = bare(helper, type, x0, 1, x0 + 1, 2, true);
        forge.settlement().buildings.add(workplace);
        workplace.equipmentRequests.add(new EquipmentRequest(UUID.randomUUID(), workplace.id,
            profession, EquipmentRequests.requirementFor(profession), 1,
            EquipmentRequest.Priority.URGENT, EquipmentRequest.Reason.MISSING,
            helper.getLevel().getGameTime()));
        return workplace;
    }

    private static CraftingOrderBook.Order orderFor(GameTestHelper helper, Forge forge,
                                                   Building requester) {
        for (CraftingOrderBook.Order order : CraftingOrderService.orders(helper.getLevel(), forge.settlement())) {
            if (order.requesterId().equals(requester.id) && order.status() == CraftingOrderBook.Status.OPEN) {
                return order;
            }
        }
        return null;
    }

    private static Item itemOf(CraftingOrderBook.Order order) {
        return BuiltInRegistries.ITEM.get(order.itemId());
    }

    private static int count(Container c, Item item) {
        int total = 0;
        for (int slot = 0; slot < c.getContainerSize(); slot++) {
            if (c.getItem(slot).is(item)) total += c.getItem(slot).getCount();
        }
        return total;
    }

    private static ItemStack takeOne(Container c, Item item) {
        for (int slot = 0; slot < c.getContainerSize(); slot++) {
            if (c.getItem(slot).is(item)) {
                return c.removeItem(slot, 1);
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * No iron anywhere: the Miner's pickaxe need is ordered as a stone
     * pickaxe at the smithy, the smith forges it from cobblestone and wood
     * (and only because of the order), and a Miner holding exactly that pick
     * digs iron ore into RAW_IRON.
     */
    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 600)
    public void minerGetsStonePickaxeFromSmithThenMinesIron(GameTestHelper helper) {
        clearArena(helper);
        Forge forge = forge(helper);
        forge.smithyChest().setItem(1, new ItemStack(Items.COBBLESTONE, 3));
        Building mineReq = requesting(helper, forge, BuildingType.MINE, Profession.MINER, 11);

        helper.assertTrue(Production.ready(helper.getLevel(), forge.smithy()) == null,
            "without an order the smithy must not turn cobblestone into picks");
        CraftingOrderService.scan(helper.getLevel(), forge.settlement());
        CraftingOrderBook.Order order = orderFor(helper, forge, mineReq);
        helper.assertTrue(order != null, "the Miner's Needs: pickaxe must open a crafting order, saw "
            + CraftingOrderService.orders(helper.getLevel(), forge.settlement()).size());
        helper.assertTrue(itemOf(order) == Items.STONE_PICKAXE,
            "with no iron the order must be a stone pickaxe, saw " + order.itemId());
        helper.assertTrue(forge.smithy().id.equals(order.workshopId()),
            "the smithy must be asked to forge it");

        Item preferred = CraftingOrderService.preferredOutput(helper.getLevel(), forge.settlement(), forge.smithy());
        helper.assertTrue(preferred == Items.STONE_PICKAXE, "the smith prefers the ordered pick, saw " + preferred);
        Production.Recipe recipe = Production.ready(helper.getLevel(), forge.smithy(), preferred);
        helper.assertTrue(recipe != null && Production.STONE_PICKAXE_RECIPE.equals(recipe.id()),
            "the stone pickaxe recipe must be ready for the order, saw " + (recipe == null ? null : recipe.id()));
        helper.assertTrue(Production.run(helper.getLevel(), forge.smithy(), recipe), "the forge must run");
        CraftingOrderService.noteCrafted(helper.getLevel(), forge.settlement(), forge.smithy(), recipe);
        helper.assertTrue(count(forge.smithyChest(), Items.STONE_PICKAXE) == 1
                && count(forge.smithyChest(), Items.COBBLESTONE) == 0
                && count(forge.smithyChest(), Items.OAK_LOG) < 8,
            "3 cobblestone and wood became one stone pickaxe: pick=" + count(forge.smithyChest(), Items.STONE_PICKAXE)
                + " cobble=" + count(forge.smithyChest(), Items.COBBLESTONE)
                + " logs=" + count(forge.smithyChest(), Items.OAK_LOG));
        ItemStack pick = takeOne(forge.smithyChest(), Items.STONE_PICKAXE);
        helper.assertTrue(EquipmentRequests.requirementFor(Profession.MINER).serviceable(pick),
            "the forged stone pickaxe must satisfy the Miner's request");
        helper.assertTrue(pick.isCorrectToolForDrops(Blocks.IRON_ORE.defaultBlockState()),
            "a stone pickaxe is strong enough for iron ore");

        // The mining half: the real MinerWorkGoal, holding exactly that pick.
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement live = new Settlement(UUID.randomUUID(), "Gruvestein",
            helper.absolutePos(new BlockPos(4, 1, 4)));
        live.radius = 6;
        data.settlements.put(live.id, live);
        data.setDirty();
        Building mine = GameTestFixtures.register(helper, live, BuildingType.MINE, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        Container mineChest = (Container) helper.getLevel().getBlockEntity(
            helper.absolutePos(new BlockPos(5, 1, 4)));
        BlockPos oreRel = new BlockPos(6, 0, 6);
        helper.setBlock(oreRel, Blocks.IRON_ORE);
        SettlerEntity miner = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 4));
        miner.setSettlerName("Stein");
        miner.bindTo(live.id, live.center);
        live.putRecord(miner.getUUID(), "Stein", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), live, mine, miner).ok(),
            "a mine entrance must take a miner");
        miner.setItemSlot(EquipmentSlot.MAINHAND, pick);
        helper.getLevel().setDayTime(3000);

        helper.succeedWhen(() -> {
            helper.assertTrue(count(mineChest, Items.RAW_IRON) >= 1,
                "the Miner with the smith's stone pickaxe must bank RAW_IRON (act=" + miner.getActivity()
                    + " stop=" + miner.logisticsStopReason() + " hand=" + miner.getMainHandItem()
                    + " rock=" + helper.getBlockState(oreRel) + ")");
            helper.assertTrue(miner.getMainHandItem().is(Items.STONE_PICKAXE),
                "it was the stone pickaxe that did it, hand=" + miner.getMainHandItem());
        });
    }

    /** Iron in the Warehouse: the same pickaxe need is ordered as iron again. */
    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 100)
    public void ironInStockOrdersTheIronPickaxe(GameTestHelper helper) {
        clearArena(helper);
        Forge forge = forge(helper);
        forge.warehouseChest().setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        Building mineReq = requesting(helper, forge, BuildingType.MINE, Profession.MINER, 11);
        // The Warehouse holds no pickaxe, so the order still opens.
        CraftingOrderService.scan(helper.getLevel(), forge.settlement());
        CraftingOrderBook.Order order = orderFor(helper, forge, mineReq);
        helper.assertTrue(order != null && itemOf(order) == Items.IRON_PICKAXE,
            "with iron in stock the pickaxe order is iron, saw " + (order == null ? null : order.itemId()));
        helper.succeed();
    }

    /** Once iron is in stock the Herder's shears and the Lumberer's axe are
     *  ordered from the smithy and forged, each for its own order. */
    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 100)
    public void ironShearsAndAxeAreOrderedAndForged(GameTestHelper helper) {
        clearArena(helper);
        Forge forge = forge(helper);
        forge.smithyChest().setItem(1, new ItemStack(Items.IRON_INGOT, 5));
        Building pasture = requesting(helper, forge, BuildingType.PASTURE, Profession.HERDER, 1);
        Building camp = requesting(helper, forge, BuildingType.LUMBER_CAMP, Profession.LUMBERER, 4);
        CraftingOrderService.scan(helper.getLevel(), forge.settlement());
        CraftingOrderBook.Order shearsOrder = orderFor(helper, forge, pasture);
        CraftingOrderBook.Order axeOrder = orderFor(helper, forge, camp);
        helper.assertTrue(shearsOrder != null && itemOf(shearsOrder) == Items.SHEARS
                && forge.smithy().id.equals(shearsOrder.workshopId()),
            "the Herder's shears must be ordered from the smithy, saw "
                + (shearsOrder == null ? null : shearsOrder.itemId()));
        helper.assertTrue(axeOrder != null && itemOf(axeOrder) == Items.IRON_AXE
                && forge.smithy().id.equals(axeOrder.workshopId()),
            "the Lumberer's axe must be ordered from the smithy, saw "
                + (axeOrder == null ? null : axeOrder.itemId()));
        for (Item want : List.of(Items.SHEARS, Items.IRON_AXE)) {
            Production.Recipe recipe = Production.ready(helper.getLevel(), forge.smithy(), want);
            helper.assertTrue(recipe != null && recipe.output() == want, "the smithy can forge " + want);
            helper.assertTrue(Production.run(helper.getLevel(), forge.smithy(), recipe), "forge " + want);
        }
        helper.assertTrue(count(forge.smithyChest(), Items.SHEARS) == 1
                && count(forge.smithyChest(), Items.IRON_AXE) == 1
                && count(forge.smithyChest(), Items.IRON_INGOT) == 0,
            "5 ingots became shears (2) and an axe (3)");
        helper.succeed();
    }
}
