package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.event.WarehouseLabels;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.warehouse.WarehouseSorting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Focused proof for persisted Warehouse resource grouping. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class WarehouseSortingGameTests {
    public WarehouseSortingGameTests() {
    }

    private static void arena(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static Building warehouse(GameTestHelper helper) {
        return new Building(UUID.randomUUID(), BuildingType.WAREHOUSE,
            helper.absolutePos(new BlockPos(1, 1, 1)),
            helper.absolutePos(new BlockPos(1, 1, 1)),
            BoundingBox.fromCorners(helper.absolutePos(new BlockPos(1, 1, 1)),
                helper.absolutePos(new BlockPos(10, 2, 10))));
    }

    private static BlockEntity blockEntity(GameTestHelper helper, BlockPos relative) {
        return helper.getLevel().getBlockEntity(helper.absolutePos(relative));
    }

    private static Container container(GameTestHelper helper, BlockPos relative) {
        BlockEntity blockEntity = blockEntity(helper, relative);
        return blockEntity instanceof Container value ? value : null;
    }

    private static void fill(Container container, ItemStack stack) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            container.setItem(slot, stack.copy());
        }
        container.setChanged();
    }

    private static Settlement settlement(GameTestHelper helper, BlockPos hearth) {
        Settlement settlement = new Settlement(UUID.randomUUID(), "Sortvik",
            helper.absolutePos(hearth));
        settlement.radius = 14;
        SettlementManager.data(helper.getLevel()).settlements.put(settlement.id, settlement);
        return settlement;
    }

    private static HearthBlockEntity hearth(GameTestHelper helper, Settlement settlement,
                                            BlockPos relative) {
        helper.setBlock(relative, ModBlocks.HEARTH.get());
        BlockEntity blockEntity = blockEntity(helper, relative);
        helper.assertTrue(blockEntity instanceof HearthBlockEntity,
            "sorting fixture needs a real Hearth");
        HearthBlockEntity hearth = (HearthBlockEntity) blockEntity;
        hearth.bindSettlement(settlement.id);
        return hearth;
    }

    private static SettlerEntity courier(GameTestHelper helper, Settlement settlement,
                                         BlockPos relative) {
        SettlerEntity courier = helper.spawn(ModEntities.SETTLER.get(), relative);
        courier.setSettlerName("Sortbud");
        courier.bindTo(settlement.id, settlement.center);
        settlement.putRecord(courier.getUUID(), courier.getSettlerName(), Profession.NONE);
        return courier;
    }

    private static int count(Container container, net.minecraft.world.item.Item item) {
        int result = 0;
        if (container == null) return result;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) result += stack.getCount();
        }
        return result;
    }

    private static int count(SettlerEntity courier, net.minecraft.world.item.Item item) {
        int result = 0;
        for (int slot = 0; slot < courier.bag.getContainerSize(); slot++) {
            ItemStack stack = courier.bag.getItem(slot);
            if (stack.is(item)) result += stack.getCount();
        }
        return result;
    }

    @GameTest(template = "empty16", batch = "warehouse_sorting", timeoutTicks = 40)
    public void namedWoodKeepsOneStableStorageHome(GameTestHelper helper) {
        arena(helper);
        Building warehouse = warehouse(helper);
        BlockPos chest = new BlockPos(3, 1, 3);
        helper.setBlock(chest, Blocks.BARREL);

        ItemStack namedLog = new ItemStack(Items.OAK_LOG, 4);
        namedLog.set(DataComponents.CUSTOM_NAME, Component.literal("Fine provenanced oak"));
        List<BlockPos> first = WarehouseSorting.destinations(helper.getLevel(),
            warehouse, namedLog);
        ItemStack sprucePlanks = new ItemStack(Items.SPRUCE_PLANKS, 2);
        List<BlockPos> replay = WarehouseSorting.destinations(helper.getLevel(),
            warehouse, sprucePlanks);
        BlockPos absolute = helper.absolutePos(chest);

        helper.assertTrue(WarehouseSorting.groupOf(namedLog)
                    == WarehouseSorting.Group.WOOD
                && first.equals(List.of(absolute)) && replay.equals(List.of(absolute))
                && WarehouseSorting.assignedGroup(blockEntity(helper, chest))
                    == WarehouseSorting.Group.WOOD,
            "a component-bearing Wood stack must keep its one persistent Wood storage home");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "warehouse_sorting", timeoutTicks = 40)
    public void fullWoodOverflowsOnlyToEmptyAndNeverMixedChest(GameTestHelper helper) {
        arena(helper);
        Building warehouse = warehouse(helper);
        BlockPos fullWood = new BlockPos(3, 1, 3);
        BlockPos mixedPersonal = new BlockPos(5, 1, 3);
        BlockPos empty = new BlockPos(7, 1, 3);
        helper.setBlock(fullWood, Blocks.CHEST);
        helper.setBlock(mixedPersonal, Blocks.CHEST);
        helper.setBlock(empty, Blocks.CHEST);
        Container wood = container(helper, fullWood);
        Container mixed = container(helper, mixedPersonal);
        helper.assertTrue(wood != null && mixed != null, "sorting fixture needs real chests");
        fill(wood, new ItemStack(Items.OAK_LOG, 64));
        mixed.setItem(0, new ItemStack(Items.IRON_SWORD));
        mixed.setItem(1, new ItemStack(Items.WHEAT));
        mixed.setChanged();

        List<BlockPos> targets = WarehouseSorting.destinations(helper.getLevel(),
            warehouse, new ItemStack(Items.STICK, 4));
        helper.assertTrue(targets.equals(List.of(helper.absolutePos(empty)))
                && WarehouseSorting.assignedGroup(blockEntity(helper, fullWood))
                    == WarehouseSorting.Group.WOOD
                && WarehouseSorting.assignedGroup(blockEntity(helper, empty))
                    == WarehouseSorting.Group.WOOD
                && WarehouseSorting.assignedGroup(blockEntity(helper, mixedPersonal)) == null,
            "a full Wood chest may label itself, but overflow must claim only the empty chest and leave mixed personal stock alone");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "warehouse_sorting", timeoutTicks = 40)
    public void doubleChestHalvesAlwaysShareCropAssignment(GameTestHelper helper) {
        arena(helper);
        Building warehouse = warehouse(helper);
        BlockPos first = new BlockPos(5, 1, 5);
        BlockState left = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockPos second = first.relative(ChestBlock.getConnectedDirection(left));
        BlockState right = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, ChestType.RIGHT);
        helper.setBlock(first, left);
        helper.setBlock(second, right);

        List<BlockPos> targets = WarehouseSorting.destinations(helper.getLevel(),
            warehouse, new ItemStack(Items.WHEAT_SEEDS, 8));
        List<BlockPos> expected = first.asLong() <= second.asLong()
            ? List.of(helper.absolutePos(first), helper.absolutePos(second))
            : List.of(helper.absolutePos(second), helper.absolutePos(first));
        helper.assertTrue(targets.equals(expected)
                && WarehouseSorting.assignedGroup(blockEntity(helper, first))
                    == WarehouseSorting.Group.CROPS
                && WarehouseSorting.assignedGroup(blockEntity(helper, second))
                    == WarehouseSorting.Group.CROPS,
            "both physical halves of one double chest must receive the same Crop assignment");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "warehouse_sorting", timeoutTicks = 100)
    public void assignedChestGetsOneFixedResourceLabel(GameTestHelper helper) {
        arena(helper);
        Building warehouse = warehouse(helper);
        BlockPos chest = new BlockPos(5, 1, 5);
        helper.setBlock(chest, Blocks.CHEST);
        BlockPos absolute = helper.absolutePos(chest);
        WarehouseSorting.destinations(helper.getLevel(), warehouse,
            new ItemStack(Items.OAK_LOG, 4));

        ItemFrame first = WarehouseLabels.ensure(helper.getLevel(), absolute);
        ItemFrame replay = WarehouseLabels.ensure(helper.getLevel(), absolute);
        List<ItemFrame> nearby = helper.getLevel().getEntitiesOfClass(ItemFrame.class,
            new AABB(absolute).inflate(2));
        helper.assertTrue(first != null && replay == first && nearby.size() == 1
                && first.getItem().is(WarehouseSorting.Group.WOOD.icon()),
            "one assigned chest must receive one stable Wood item-frame label");
        helper.getLevel().destroyBlock(absolute, false);
        helper.runAfterDelay(50, () -> {
            List<ItemFrame> remaining = helper.getLevel().getEntitiesOfClass(ItemFrame.class,
                new AABB(absolute).inflate(2));
            List<ItemEntity> dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(absolute).inflate(2), item -> item.getItem().is(WarehouseSorting.Group.WOOD.icon()));
            helper.assertTrue(!first.isAlive() && remaining.isEmpty() && dropped.isEmpty(),
                "breaking an assigned chest must discard its fixed label without dropping a Wood icon");
            helper.succeed();
        });
    }

    /** The Courier must physically carry the mixed stock; grouping never teleports it between chests. */
    @GameTest(template = "empty16", batch = "warehouse_sorting", timeoutTicks = 1800)
    public void courierPhysicallySortsMixedWarehouseWoodAndCrops(GameTestHelper helper) {
        arena(helper);
        helper.getLevel().setDayTime(2000);
        BlockPos hearthPos = new BlockPos(2, 1, 2);
        Settlement settlement = settlement(helper, hearthPos);
        HearthBlockEntity hearth = hearth(helper, settlement, hearthPos);
        helper.assertTrue(hearth.insertGoods(new ItemStack(Items.BREAD, 64)).isEmpty(),
            "sorting fixture must keep the real Hearth food reserve full");

        BoundingBox bounds = BoundingBox.fromCorners(helper.absolutePos(new BlockPos(3, 1, 3)),
            helper.absolutePos(new BlockPos(11, 3, 9)));
        Building warehouse = GameTestFixtures.registerWithBounds(helper, settlement,
            BuildingType.WAREHOUSE, new BlockPos(3, 1, 3), new BlockPos(3, 2, 3), bounds);
        BlockPos sourcePos = new BlockPos(5, 1, 5);
        BlockPos woodPos = new BlockPos(7, 1, 5);
        BlockPos cropsPos = new BlockPos(9, 1, 5);
        helper.setBlock(woodPos, Blocks.CHEST);
        WarehouseSorting.destinations(helper.getLevel(), warehouse, new ItemStack(Items.OAK_LOG));
        helper.setBlock(cropsPos, Blocks.CHEST);
        WarehouseSorting.destinations(helper.getLevel(), warehouse, new ItemStack(Items.WHEAT));
        helper.setBlock(sourcePos, Blocks.CHEST);
        Container source = container(helper, sourcePos);
        Container wood = container(helper, woodPos);
        Container crops = container(helper, cropsPos);
        helper.assertTrue(source != null && wood != null && crops != null
                && WarehouseSorting.assignedGroup(blockEntity(helper, woodPos)) == WarehouseSorting.Group.WOOD
                && WarehouseSorting.assignedGroup(blockEntity(helper, cropsPos)) == WarehouseSorting.Group.CROPS,
            "fixture must have separate real Wood and Crops storage chests");
        source.setItem(0, new ItemStack(Items.OAK_LOG, 4));
        source.setItem(1, new ItemStack(Items.WHEAT, 4));
        source.setChanged();

        SettlerEntity courier = courier(helper, settlement, new BlockPos(4, 1, 7));
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, warehouse, courier).ok(),
            "sorting must run through a real Courier employment assignment");
        courier.setHunger(100.0F);
        boolean[] heldWood = {false};
        boolean[] heldCrops = {false};
        helper.onEachTick(() -> {
            heldWood[0] |= count(courier, Items.OAK_LOG) > 0;
            heldCrops[0] |= count(courier, Items.WHEAT) > 0;
            helper.assertTrue(count(source, Items.OAK_LOG) + count(wood, Items.OAK_LOG)
                    + count(crops, Items.OAK_LOG) + count(courier, Items.OAK_LOG) == 4
                    && count(source, Items.WHEAT) + count(wood, Items.WHEAT)
                    + count(crops, Items.WHEAT) + count(courier, Items.WHEAT) == 4,
                "every real Wood and Crop unit must remain conserved while the Courier sorts it");
        });
        helper.succeedWhen(() -> helper.assertTrue(heldWood[0] && heldCrops[0]
                && count(source, Items.OAK_LOG) == 0 && count(source, Items.WHEAT) == 0
                && count(wood, Items.OAK_LOG) == 4 && count(wood, Items.WHEAT) == 0
                && count(crops, Items.WHEAT) == 4 && count(crops, Items.OAK_LOG) == 0
                && count(courier, Items.OAK_LOG) == 0 && count(courier, Items.WHEAT) == 0,
            "one real employed Courier must bag-carry mixed warehouse stock into its assigned Wood and Crops chests"));
    }
}
