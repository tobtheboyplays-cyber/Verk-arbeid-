package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.CourierWorkGoal;
import com.hearthstead.logistics.StopReason;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.UUID;

/** Regression wall for the courier's bounded, world-first stop projection. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class CourierStopReasonGameTests {

    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean rim = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 5; y++) {
                    helper.setBlock(new BlockPos(x, y, z),
                        rim && y <= 2 ? Blocks.STONE_BRICKS.defaultBlockState()
                            : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static Settlement settlement(GameTestHelper helper, BlockPos centerRel,
                                         int radius) {
        var data = SettlementManager.data(helper.getLevel());
        var arena = helper.getBounds();
        data.settlements.values().removeIf(old -> arena.contains(
            old.center.getX() + 0.5, old.center.getY() + 0.5,
            old.center.getZ() + 0.5));
        Settlement settlement = new Settlement(UUID.randomUUID(), "Signalvik",
            helper.absolutePos(centerRel));
        settlement.radius = radius;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static HearthBlockEntity hearth(GameTestHelper helper, Settlement settlement,
                                            BlockPos hearthRel) {
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockEntity blockEntity = helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel));
        helper.assertTrue(blockEntity instanceof HearthBlockEntity,
            "setup: hearth block entity should exist");
        HearthBlockEntity hearth = (HearthBlockEntity) blockEntity;
        hearth.bindSettlement(settlement.id);
        return hearth;
    }

    private static Building building(GameTestHelper helper, Settlement settlement,
                                     BuildingType type, BlockPos minRel,
                                     BlockPos maxRel, BlockPos plaqueRel) {
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get());
        Building building = new Building(UUID.randomUUID(), type,
            helper.absolutePos(plaqueRel), helper.absolutePos(plaqueRel),
            BoundingBox.fromCorners(helper.absolutePos(minRel),
                helper.absolutePos(maxRel)));
        building.valid = true;
        settlement.buildings.add(building);
        return building;
    }

    private static SettlerEntity courier(GameTestHelper helper, Settlement settlement,
                                         BlockPos rel) {
        SettlerEntity courier = helper.spawn(ModEntities.SETTLER.get(), rel);
        courier.setSettlerName("Bud");
        courier.bindTo(settlement.id, settlement.center);
        settlement.putRecord(courier.getUUID(), courier.getSettlerName(), Profession.NONE);
        courier.assignProfession(Profession.COURIER);
        return courier;
    }

    private static Container container(GameTestHelper helper, BlockPos rel) {
        BlockEntity blockEntity = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        return blockEntity instanceof Container found ? found : null;
    }

    private static int count(Container container, Item item) {
        if (container == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int count(ItemStackHandler inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int bagCount(SettlerEntity courier, Item item) {
        return count(courier.bag, item);
    }

    private static void fill(Container container, Item item) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            container.setItem(slot, new ItemStack(item, item.getDefaultMaxStackSize()));
        }
        container.setChanged();
    }

    @GameTest(template = "empty16", timeoutTicks = 240,
        batch = "courier_stop_reason_day")
    public void noWarehouseIsVisible(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        Settlement settlement = settlement(helper, hearthRel, 11);
        HearthBlockEntity hearth = hearth(helper, settlement, hearthRel);
        hearth.insertGoods(new ItemStack(Items.OAK_LOG, 4));
        SettlerEntity courier = courier(helper, settlement, new BlockPos(5, 1, 5));

        helper.succeedWhen(() -> helper.assertTrue(
            courier.logisticsStopReason() == StopReason.NO_WAREHOUSE_SPACE,
            "hearth cargo with no warehouse should read NO_WAREHOUSE_SPACE, got "
                + courier.logisticsStopReason()));
    }

    @GameTest(template = "empty16", timeoutTicks = 240,
        batch = "courier_stop_reason_day")
    public void emptyWarehouseInputIsVisible(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        Settlement settlement = settlement(helper, hearthRel, 11);
        hearth(helper, settlement, hearthRel);

        building(helper, settlement, BuildingType.WAREHOUSE,
            new BlockPos(8, 1, 2), new BlockPos(10, 3, 4), new BlockPos(8, 1, 2));
        helper.setBlock(new BlockPos(9, 1, 3), Blocks.CHEST);
        Building smelter = building(helper, settlement, BuildingType.SMELTER,
            new BlockPos(3, 1, 7), new BlockPos(5, 3, 9), new BlockPos(3, 1, 7));
        helper.setBlock(new BlockPos(4, 1, 8), Blocks.CHEST);
        SettlerEntity courier = courier(helper, settlement, new BlockPos(6, 1, 5));

        helper.succeedWhen(() -> {
            helper.assertTrue(courier.logisticsStopReason() == StopReason.WAITING_INPUT,
                "empty warehouse stock should read WAITING_INPUT, got "
                    + courier.logisticsStopReason());
            helper.assertTrue(courier.logisticsStopTarget().orElse(BlockPos.ZERO)
                    .equals(smelter.plaquePos),
                "WAITING_INPUT should point at the stopped crafter plaque");
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 240,
        batch = "courier_stop_reason_day")
    public void fullCrafterChestIsVisible(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        Settlement settlement = settlement(helper, hearthRel, 11);
        hearth(helper, settlement, hearthRel);

        building(helper, settlement, BuildingType.WAREHOUSE,
            new BlockPos(8, 1, 2), new BlockPos(10, 3, 4), new BlockPos(8, 1, 2));
        BlockPos warehouseChestRel = new BlockPos(9, 1, 3);
        helper.setBlock(warehouseChestRel, Blocks.CHEST);
        Container warehouse = container(helper, warehouseChestRel);
        helper.assertTrue(warehouse != null, "setup: warehouse chest should exist");
        warehouse.setItem(0, new ItemStack(Items.RAW_IRON, 8));

        building(helper, settlement, BuildingType.SMELTER,
            new BlockPos(3, 1, 7), new BlockPos(5, 3, 9), new BlockPos(3, 1, 7));
        BlockPos crafterChestRel = new BlockPos(4, 1, 8);
        helper.setBlock(crafterChestRel, Blocks.CHEST);
        Container crafter = container(helper, crafterChestRel);
        helper.assertTrue(crafter != null, "setup: crafter chest should exist");
        fill(crafter, Items.COBBLESTONE);
        SettlerEntity courier = courier(helper, settlement, new BlockPos(6, 1, 5));

        helper.succeedWhen(() -> helper.assertTrue(
            courier.logisticsStopReason() == StopReason.CHEST_FULL,
            "short crafter with no receiving slot should read CHEST_FULL, got "
                + courier.logisticsStopReason()));
    }

    @GameTest(template = "empty16", timeoutTicks = 1200,
        batch = "courier_stop_reason_day")
    public void hearthThatFillsDuringDeliveryIsVisible(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        Settlement settlement = settlement(helper, hearthRel, 11);
        HearthBlockEntity hearth = hearth(helper, settlement, hearthRel);

        building(helper, settlement, BuildingType.WAREHOUSE,
            new BlockPos(9, 1, 9), new BlockPos(11, 3, 11), new BlockPos(9, 1, 9));
        BlockPos chestRel = new BlockPos(10, 1, 10);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container warehouse = container(helper, chestRel);
        helper.assertTrue(warehouse != null, "setup: warehouse chest should exist");
        warehouse.setItem(0, new ItemStack(Items.BREAD, 4));
        SettlerEntity courier = courier(helper, settlement, new BlockPos(5, 1, 5));
        boolean[] filled = {false};

        helper.succeedWhen(() -> {
            if (!filled[0] && bagCount(courier, Items.BREAD) > 0) {
                for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
                    hearth.getInventory().setStackInSlot(slot,
                        new ItemStack(Items.COBBLESTONE, 64));
                }
                filled[0] = true;
            }
            helper.assertTrue(filled[0],
                "courier should first lift food before the hearth is filled");
            helper.assertTrue(courier.logisticsStopReason() == StopReason.HEARTH_FULL,
                "mid-route full hearth should read HEARTH_FULL, got "
                    + courier.logisticsStopReason());
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 500,
        batch = "courier_stop_reason_day")
    public void heldReservationIsVisibleToTheOtherCourier(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        Settlement settlement = settlement(helper, hearthRel, 11);
        hearth(helper, settlement, hearthRel);

        building(helper, settlement, BuildingType.WAREHOUSE,
            new BlockPos(9, 1, 2), new BlockPos(11, 3, 4), new BlockPos(9, 1, 2));
        BlockPos sourceRel = new BlockPos(10, 1, 3);
        helper.setBlock(sourceRel, Blocks.CHEST);
        Container source = container(helper, sourceRel);
        helper.assertTrue(source != null, "setup: warehouse chest should exist");
        source.setItem(0, new ItemStack(Items.RAW_IRON, 2));
        building(helper, settlement, BuildingType.SMELTER,
            new BlockPos(2, 1, 9), new BlockPos(4, 3, 11), new BlockPos(2, 1, 9));
        helper.setBlock(new BlockPos(3, 1, 10), Blocks.CHEST);

        SettlerEntity first = courier(helper, settlement, new BlockPos(6, 1, 6));
        SettlerEntity second = courier(helper, settlement, new BlockPos(7, 1, 6));
        helper.succeedWhen(() -> helper.assertTrue(
            first.logisticsStopReason() == StopReason.RESERVED_BY_OTHER
                || second.logisticsStopReason() == StopReason.RESERVED_BY_OTHER,
            "one courier should expose the other's held reservation [first="
                + first.logisticsStopReason() + " second="
                + second.logisticsStopReason() + "]"));
    }

    @GameTest(template = "empty16", timeoutTicks = 2200,
        batch = "courier_stop_reason_day")
    public void noPathShowsCountdownAndClearsAfterRetrySucceeds(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        Settlement settlement = settlement(helper, hearthRel, 11);
        HearthBlockEntity hearth = hearth(helper, settlement, hearthRel);
        hearth.insertGoods(new ItemStack(Items.OAK_LOG, 4));

        BlockPos chestRel = new BlockPos(10, 1, 10);
        building(helper, settlement, BuildingType.WAREHOUSE, chestRel, chestRel,
            new BlockPos(12, 1, 12));
        helper.setBlock(chestRel, Blocks.CHEST);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            helper.setBlock(chestRel.relative(direction), Blocks.STONE_BRICKS);
            helper.setBlock(chestRel.relative(direction).above(), Blocks.STONE_BRICKS);
        }
        helper.setBlock(chestRel.above(), Blocks.STONE_BRICKS);
        Container warehouse = container(helper, chestRel);
        helper.assertTrue(warehouse != null, "setup: sealed warehouse chest should exist");
        SettlerEntity courier = courier(helper, settlement, new BlockPos(4, 1, 4));
        boolean[] openedAfterFailure = {false};
        String[] lastFailureTrace = {""};
        java.util.List<String> failureSnapshots = new java.util.ArrayList<>();

        helper.succeedWhen(() -> {
            if (courier.logisticsStopReason() == StopReason.NO_PATH
                && !courier.routeFailureNote().equals(lastFailureTrace[0])) {
                lastFailureTrace[0] = courier.routeFailureNote();
                failureSnapshots.add(courier.blockPosition() + " " + lastFailureTrace[0]);
            }
            if (!openedAfterFailure[0]
                && courier.logisticsStopReason() == StopReason.NO_PATH
                && courier.logisticsRetrySeconds() > 0) {
                BlockPos approach = chestRel.west();
                helper.setBlock(approach, Blocks.AIR);
                helper.setBlock(approach.above(), Blocks.AIR);
                // A chest with a full block directly above it is still
                // physically sealed: the courier's eye ray hits that cap
                // before the chest, just as a player's interaction would.
                // Open the lid as well as the two-block approach corridor so
                // this phase changes the fixture from genuinely unreachable
                // to genuinely usable rather than weakening hasArrived().
                helper.setBlock(chestRel.above(), Blocks.AIR);
                openedAfterFailure[0] = true;
            }
            helper.assertTrue(openedAfterFailure[0],
                "sealed target should publish NO_PATH with a non-zero retry countdown"
                    + " [reason=" + courier.logisticsStopReason()
                    + " retry=" + courier.logisticsRetrySeconds()
                    + " trace=" + courier.routeFailureNote() + "]");
            helper.assertTrue(helper.getBlockState(chestRel.west()).isAir()
                    && helper.getBlockState(chestRel.west().above()).isAir()
                    && helper.getBlockState(chestRel.above()).isAir(),
                "the recovery corridor and lid must remain physically open");
            int delivered = count(warehouse, Items.OAK_LOG);
            helper.assertTrue(delivered == 4,
                "after opening the route, the real retry should deliver all four logs"
                    + " [delivered=" + delivered
                    + " bag=" + bagCount(courier, Items.OAK_LOG)
                    + " hearth=" + count(hearth.getInventory(), Items.OAK_LOG)
                    + " pos=" + courier.blockPosition()
                    + " activity=" + courier.getActivity()
                    + " reason=" + courier.logisticsStopReason()
                    + " retry=" + courier.logisticsRetrySeconds()
                    + " trace=" + courier.routeFailureNote()
                    + " failures=" + failureSnapshots
                    + " warehousePresent=" + settlement.buildings.stream()
                        .anyMatch(building -> building.type == BuildingType.WAREHOUSE)
                    + "]");
            helper.assertTrue(courier.logisticsStopReason() == StopReason.NONE,
                "a successful retry must clear the old stop reason, got "
                    + courier.logisticsStopReason());
        });
    }

    /**
     * Being inside a warehouse's broad registered box is not permission to
     * stow through an internal wall. This pins the physical half of the
     * arrival contract independently of the small single-cell fixture above:
     * both the courier and chest are inside the same bounds and already
     * within ordinary reach, but the partition must produce NO_PATH until a
     * real two-block doorway is opened.
     */
    @GameTest(template = "empty16", timeoutTicks = 2200,
        batch = "courier_stop_reason_day")
    public void warehouseBoundsNeverBypassAnInternalWall(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(3, 1, 7);
        Settlement settlement = settlement(helper, hearthRel, 12);
        HearthBlockEntity hearth = hearth(helper, settlement, hearthRel);
        hearth.insertGoods(new ItemStack(Items.OAK_LOG, 4));

        BlockPos chestRel = new BlockPos(10, 1, 7);
        building(helper, settlement, BuildingType.WAREHOUSE,
            new BlockPos(2, 1, 1), new BlockPos(12, 3, 12),
            new BlockPos(12, 1, 12));
        helper.setBlock(chestRel, Blocks.CHEST);
        Container warehouse = container(helper, chestRel);
        helper.assertTrue(warehouse != null, "setup: warehouse chest should exist");

        // A floor-to-head-height partition spans the whole bounded arena.
        // The courier can stand two blocks west of the chest -- inside both
        // CHEST_REACH and the warehouse bounds -- but cannot see or reach the
        // container until this test opens the door after a real failure.
        for (int z = 1; z <= 12; z++) {
            helper.setBlock(new BlockPos(9, 1, z), Blocks.STONE_BRICKS);
            helper.setBlock(new BlockPos(9, 2, z), Blocks.STONE_BRICKS);
        }
        SettlerEntity courier = courier(helper, settlement, new BlockPos(6, 1, 7));
        boolean[] opened = {false};

        helper.succeedWhen(() -> {
            int delivered = count(warehouse, Items.OAK_LOG);
            if (!opened[0]) {
                helper.assertTrue(delivered == 0,
                    "warehouse bounds must never authorize delivery through the partition"
                        + " [delivered=" + delivered
                        + " pos=" + courier.blockPosition()
                        + " reason=" + courier.logisticsStopReason() + "]");
                if (courier.logisticsStopReason() == StopReason.NO_PATH
                    && courier.logisticsRetrySeconds() > 0) {
                    helper.setBlock(new BlockPos(9, 1, 7), Blocks.AIR);
                    helper.setBlock(new BlockPos(9, 2, 7), Blocks.AIR);
                    opened[0] = true;
                }
            }
            helper.assertTrue(opened[0],
                "the intact partition should first produce visible NO_PATH");
            helper.assertTrue(delivered == 4,
                "after opening a physical doorway, all four logs should arrive"
                    + " [delivered=" + delivered
                    + " bag=" + bagCount(courier, Items.OAK_LOG)
                    + " hearth=" + count(hearth.getInventory(), Items.OAK_LOG)
                    + " pos=" + courier.blockPosition()
                    + " reason=" + courier.logisticsStopReason()
                    + " trace=" + courier.routeFailureNote() + "]");
            helper.assertTrue(courier.logisticsStopReason() == StopReason.NONE,
                "a successful physical retry must clear NO_PATH");
        });
    }

    @GameTest(template = "empty5", timeoutTicks = 60,
        batch = "courier_stop_reason_contract")
    public void stopReasonWireIdsAreStable(GameTestHelper helper) {
        StopReason[] reasons = {
            StopReason.NONE, StopReason.WAITING_INPUT, StopReason.CHEST_FULL,
            StopReason.HEARTH_FULL, StopReason.NO_WAREHOUSE_SPACE,
            StopReason.RESERVED_BY_OTHER, StopReason.RESTING_AFTER_FAIL,
            StopReason.NO_PATH, StopReason.NO_WORK_ZONE,
            StopReason.NO_VALID_TARGET
        };
        int[] ids = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        helper.assertTrue(StopReason.values().length == 10,
            "packed protocol must contain exactly 10 reasons");
        for (int index = 0; index < reasons.length; index++) {
            StopReason reason = reasons[index];
            int expected = ids[index];
            helper.assertTrue(reason.wireId() == expected,
                reason + " wire id changed: expected " + expected + " got "
                    + reason.wireId());
            helper.assertTrue(StopReason.fromWireId(expected) == reason,
                "wire id " + expected + " does not decode to " + reason);
        }
        helper.assertTrue(StopReason.fromWireId(-1) == StopReason.NONE
                && StopReason.fromWireId(10) == StopReason.NONE,
            "unknown wire ids must fail closed to NONE");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "courier_stop_reason_contract")
    public void courierStopProjectionIsNotPersisted(GameTestHelper helper) {
        SettlerEntity original = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(2, 1, 2));
        BlockPos target = helper.absolutePos(new BlockPos(1, 1, 1));
        original.setLogisticsStop(StopReason.NO_PATH, target,
            CourierWorkGoal.FIRST_REST_TICKS);

        helper.succeedWhen(() -> {
            helper.assertTrue(original.logisticsStopReason() == StopReason.NO_PATH,
                "setup: packed stop reason should first reach entity data");
            CompoundTag tag = new CompoundTag();
            original.addAdditionalSaveData(tag);
            helper.assertTrue(tag.getAllKeys().stream()
                    .noneMatch(key -> key.toLowerCase(java.util.Locale.ROOT)
                        .contains("logistics")),
                "courier diagnosis must not be written to settler NBT: " + tag.getAllKeys());
            SettlerEntity copy = helper.spawn(ModEntities.SETTLER.get(),
                new BlockPos(3, 1, 3));
            copy.readAdditionalSaveData(tag);
            helper.assertTrue(copy.logisticsStopReason() == StopReason.NONE
                    && copy.logisticsStopTarget().isEmpty()
                    && copy.logisticsRetrySeconds() == 0,
                "a loaded settler must re-derive logistics state from the live world");
        });
    }

    private static BlockPos buildHouse(GameTestHelper helper, BlockPos origin) {
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                boolean wall = x == 0 || z == 0 || x == 4 || z == 4;
                for (int y = 1; y <= 3; y++) {
                    if (wall) {
                        helper.setBlock(origin.offset(x, y, z), Blocks.STONE_BRICKS);
                    }
                }
                helper.setBlock(origin.offset(x, 4, z), Blocks.STONE_BRICKS);
            }
        }
        helper.setBlock(origin.offset(2, 1, 0), Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(origin.offset(2, 2, 0), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        helper.setBlock(origin.offset(2, 1, 2), Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.NORTH)
            .setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(origin.offset(2, 1, 3), Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.NORTH)
            .setValue(BedBlock.PART, BedPart.HEAD));
        helper.setBlock(origin.offset(1, 2, 1), Blocks.TORCH);
        return origin.offset(1, 2, -1);
    }

    private static PlaqueFixture validHousePlaque(GameTestHelper helper) {
        buildArena(helper, 14);
        settlement(helper, new BlockPos(7, 1, 7), 10);
        BlockPos plaqueRel = buildHouse(helper, new BlockPos(5, 0, 5));
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(PlaqueBlock.FACING, Direction.NORTH));
        BlockEntity blockEntity = helper.getLevel().getBlockEntity(helper.absolutePos(plaqueRel));
        helper.assertTrue(blockEntity instanceof PlaqueBlockEntity,
            "setup: house plaque should have a block entity");
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) blockEntity;
        ItemStack plan = PlaqueItemData.stamped(new ItemStack(ModItems.BUILD_PLAN.get()),
            BuildingType.HOUSE);
        plaque.insertPlan(helper.getLevel(), plan);
        return new PlaqueFixture(plaque, plaqueRel);
    }

    @GameTest(template = "empty16", timeoutTicks = 300,
        batch = "courier_stop_reason_contract")
    public void plaqueAggregatesTwoCourierReportsUntilTheLastClear(GameTestHelper helper) {
        PlaqueFixture fixture = validHousePlaque(helper);
        UUID redCourier = UUID.randomUUID();
        UUID amberCourier = UUID.randomUUID();
        PlaqueBlockEntity plaque = fixture.plaque();
        helper.assertTrue(plaque.state() == PlaqueState.LINKED_VALID,
            "setup: house must be registered before testing operational lamp truth");
        plaque.setLogisticsStopReason(helper.getLevel(), redCourier,
            StopReason.NO_WAREHOUSE_SPACE);
        plaque.setLogisticsStopReason(helper.getLevel(), amberCourier,
            StopReason.WAITING_INPUT);

        helper.runAfterDelay(2, () -> {
            helper.assertTrue(plaque.logisticsStopReason() == StopReason.NO_WAREHOUSE_SPACE
                    && helper.getBlockState(fixture.relativePos())
                        .getValue(PlaqueBlock.REGISTERED)
                    && helper.getBlockState(fixture.relativePos()).getValue(PlaqueBlock.GLOW)
                        == PlaqueBlock.Glow.RED,
                "red must outrank amber without hiding registered truth");
            CompoundTag wire = plaque.getUpdateTag(helper.getLevel().registryAccess());
            CompoundTag disk = plaque.saveWithoutMetadata(helper.getLevel().registryAccess());
            helper.assertTrue(wire.contains("LogisticsStop")
                    && !disk.contains("LogisticsStop"),
                "aggregate reason must ride the wire but never persist to disk");

            plaque.setLogisticsStopReason(helper.getLevel(), redCourier, StopReason.NONE);
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(plaque.logisticsStopReason() == StopReason.WAITING_INPUT
                        && helper.getBlockState(fixture.relativePos())
                            .getValue(PlaqueBlock.REGISTERED)
                        && helper.getBlockState(fixture.relativePos())
                            .getValue(PlaqueBlock.GLOW) == PlaqueBlock.Glow.AMBER,
                    "one clear must preserve amber and registered truth");

                plaque.setLogisticsStopReason(helper.getLevel(), amberCourier,
                    StopReason.NONE);
                helper.runAfterDelay(2, () -> {
                    helper.assertTrue(plaque.logisticsStopReason() == StopReason.NONE
                            && plaque.state() == PlaqueState.LINKED_VALID
                            && helper.getBlockState(fixture.relativePos())
                                .getValue(PlaqueBlock.REGISTERED)
                            && helper.getBlockState(fixture.relativePos())
                                .getValue(PlaqueBlock.GLOW) == PlaqueBlock.Glow.GREEN,
                        "only the final courier clear may restore green; validity must stay linked");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 300,
        batch = "courier_stop_reason_contract")
    public void plaqueReportExpiresWhenCourierStopsHeartbeating(GameTestHelper helper) {
        PlaqueFixture fixture = validHousePlaque(helper);
        UUID vanishedCourier = UUID.randomUUID();
        boolean[] armed = {false};
        long[] armedAt = {0L};

        helper.succeedWhen(() -> {
            PlaqueBlockEntity plaque = fixture.plaque();
            helper.assertTrue(plaque.state() == PlaqueState.LINKED_VALID,
                "setup: house must be registered before TTL check");
            if (!armed[0]) {
                plaque.setLogisticsStopReason(helper.getLevel(), vanishedCourier,
                    StopReason.NO_PATH);
                armed[0] = true;
                armedAt[0] = helper.getLevel().getGameTime();
            }
            helper.assertTrue(helper.getLevel().getGameTime() - armedAt[0] > 120,
                "wait until the non-heartbeating report has crossed its TTL");
            helper.assertTrue(plaque.logisticsStopReason() == StopReason.NONE
                    && helper.getBlockState(fixture.relativePos())
                        .getValue(PlaqueBlock.REGISTERED)
                    && helper.getBlockState(fixture.relativePos()).getValue(PlaqueBlock.GLOW)
                        == PlaqueBlock.Glow.GREEN,
                "stale courier report should expire back to valid green");
        });
    }

    private record PlaqueFixture(PlaqueBlockEntity plaque, BlockPos relativePos) {
    }
}
