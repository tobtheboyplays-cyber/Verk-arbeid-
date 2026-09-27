package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.raid.FirstRaidReadinessService;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.warehouse.WarehouseLevelService;
import com.hearthstead.settlement.warehouse.WarehouseLevels;
import com.hearthstead.settlement.warehouse.WarehouseStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Warehouse levels (owner, 26 Sep): capacity follows the level, containers
 * beyond it are simply "not managed", never a failure or a readiness
 * blocker; the index is incremental; old saves are grandfathered.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class WarehouseLevelGameTests {

    private static final int SIZE = 12;

    private static void buildArena(GameTestHelper helper) {
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                boolean rim = x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1;
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z),
                        rim && y <= 2 ? Blocks.STONE_BRICKS.defaultBlockState()
                                      : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /** Places {@code count} chests on y=1 in a 9x9 grid, row by row. */
    private static List<BlockPos> placeChests(GameTestHelper helper, int count) {
        List<BlockPos> placed = new ArrayList<>();
        for (int x = 1; x <= 9 && placed.size() < count; x++) {
            for (int z = 1; z <= 9 && placed.size() < count; z++) {
                BlockPos rel = new BlockPos(x, 1, z);
                helper.setBlock(rel, Blocks.CHEST);
                placed.add(helper.absolutePos(rel));
            }
        }
        return placed;
    }

    private static Building newWarehouse(GameTestHelper helper) {
        BlockPos min = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos max = helper.absolutePos(new BlockPos(SIZE - 2, 3, SIZE - 2));
        Building building = new Building(UUID.randomUUID(), BuildingType.WAREHOUSE,
            helper.absolutePos(new BlockPos(1, 2, 1)),
            helper.absolutePos(new BlockPos(1, 1, 1)),
            BoundingBox.fromCorners(min, max));
        building.interiorVolume = 200;
        building.valid = true;
        return building;
    }

    private static Set<BlockPos> nearest(List<BlockPos> all, BlockPos plaque, int n) {
        List<BlockPos> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.<BlockPos>comparingDouble(p -> p.distSqr(plaque))
            .thenComparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ));
        return new HashSet<>(sorted.subList(0, Math.min(n, sorted.size())));
    }

    /**
     * 40 chests in an L1 warehouse (16): the 16 nearest the plaque are
     * managed, 24 are listed as not managed, and readiness stays healthy.
     * Then 70 chests (above the OLD 64 cap that used to break readiness)
     * in an L3 warehouse: 64 managed, 6 not managed, still healthy.
     */
    @GameTest(batch = "warehouse_levels", template = "empty16", timeoutTicks = 200)
    public void overCapacityIsNotManagedAndNeverBlocks(GameTestHelper helper) {
        buildArena(helper);
        List<BlockPos> chests = placeChests(helper, 40);
        Building warehouse = newWarehouse(helper);

        List<BlockPos> managed = WarehouseIndex.containers(helper.getLevel(), warehouse);
        WarehouseIndex.View view = WarehouseIndex.view(helper.getLevel(), warehouse);
        helper.assertTrue(WarehouseIndex.capacityFor(warehouse) == 16,
            "a new warehouse is L1 = 16, got " + WarehouseIndex.capacityFor(warehouse));
        helper.assertTrue(managed.size() == 16, "16 managed, got " + managed.size());
        helper.assertTrue(view.unmanaged().size() == 24,
            "24 not managed, got " + view.unmanaged().size());
        helper.assertTrue(view.known() == 40, "40 known, got " + view.known());
        helper.assertTrue(new HashSet<>(managed).equals(
                nearest(chests, warehouse.plaquePos, 16)),
            "the managed 16 must be the 16 nearest the plaque");
        for (int i = 1; i < managed.size(); i++) {
            BlockPos a = managed.get(i - 1);
            BlockPos b = managed.get(i);
            helper.assertTrue(a.getY() < b.getY() || (a.getY() == b.getY()
                    && (a.getX() < b.getX() || (a.getX() == b.getX() && a.getZ() < b.getZ()))),
                "managed list must stay in y/x/z scan order");
        }
        helper.assertTrue(FirstRaidReadinessService.warehouseStorageHealthy(
                helper.getLevel(), warehouse),
            "too many containers must never raise warehouse_storage_unavailable");
        WarehouseStorage storage = WarehouseStorage.refreshed(helper.getLevel(), warehouse);
        helper.assertTrue(storage.lastVisitCount() == 16,
            "contents are read from managed containers only, visited "
                + storage.lastVisitCount());

        placeChests(helper, 70); // same grid, 30 more
        warehouse.level = 3;
        warehouse.warehouseTechMax = 3;
        List<BlockPos> managedL3 = WarehouseIndex.containers(helper.getLevel(), warehouse);
        WarehouseIndex.View viewL3 = WarehouseIndex.view(helper.getLevel(), warehouse);
        helper.assertTrue(managedL3.size() == 64, "L3 manages 64, got " + managedL3.size());
        helper.assertTrue(viewL3.unmanaged().size() == 6,
            "6 not managed, got " + viewL3.unmanaged().size());
        helper.assertTrue(FirstRaidReadinessService.warehouseStorageHealthy(
                helper.getLevel(), warehouse),
            "70 containers (old cap 64) must no longer break readiness");
        helper.succeed();
    }

    /**
     * Capacity follows the effective level: the room checklist level,
     * capped by the Logistics tree, never below the floor.
     */
    @GameTest(batch = "warehouse_levels", template = "empty16", timeoutTicks = 200)
    public void upgradeRaisesCapacity(GameTestHelper helper) {
        buildArena(helper);
        placeChests(helper, 40);
        Building warehouse = newWarehouse(helper);
        var level = helper.getLevel();

        WarehouseLevelService.Status l1 = WarehouseLevelService.status(level, null, warehouse);
        helper.assertTrue(l1.level() == 1 && l1.capacity() == 16 && l1.managed() == 16,
            "L1: 16/16, got " + l1);
        helper.assertTrue(l1.nextLevel() == 2 && l1.nextCapacity() == 32
                && l1.nextGate() == null,
            "L2 needs no tree node, got " + l1);

        warehouse.level = 2; // the room now meets checklist L2
        WarehouseLevelService.Status l2 = WarehouseLevelService.status(level, null, warehouse);
        helper.assertTrue(l2.level() == 2 && l2.capacity() == 32 && l2.managed() == 32,
            "L2: 32/32, got " + l2);

        warehouse.level = 3; // room meets L3, but no Warehouse Racks yet
        WarehouseLevelService.Status locked = WarehouseLevelService.status(level, null, warehouse);
        helper.assertTrue(locked.level() == 2 && locked.capacity() == 32,
            "without the tree node L3 is not recognised, got " + locked);
        helper.assertTrue(locked.techLocked()
                && locked.nextGate() == com.hearthstead.settlement.development
                    .PostRaidUpgrade.WAREHOUSE_RACKS,
            "status must name Warehouse Racks as the missing node, got " + locked);

        warehouse.warehouseTechMax = 3; // as synced once the node is owned
        List<BlockPos> managed = WarehouseIndex.containers(level, warehouse);
        helper.assertTrue(WarehouseIndex.capacityFor(warehouse) == 64
                && managed.size() == 40,
            "with the node owned L3 manages all 40 (cap 64), got "
                + managed.size() + "/" + WarehouseIndex.capacityFor(warehouse));
        helper.succeed();
    }

    /**
     * Placing and breaking containers inside the bounds updates the cached
     * index without a full rescan; a flag-2 edit (no neighbour event) is
     * picked up by the background sweep.
     */
    @GameTest(batch = "warehouse_levels", template = "empty16", timeoutTicks = 200)
    public void placeAndBreakUpdateTheIndex(GameTestHelper helper) {
        buildArena(helper);
        placeChests(helper, 3);
        Building warehouse = newWarehouse(helper);
        var level = helper.getLevel();

        helper.assertTrue(WarehouseIndex.containers(level, warehouse).size() == 3,
            "3 chests indexed");
        int rebuilds = WarehouseIndex.rebuildCount(level, warehouse);
        helper.assertTrue(rebuilds == 1, "one initial build, got " + rebuilds);

        BlockPos barrel = new BlockPos(5, 1, 5);
        helper.setBlock(barrel, Blocks.BARREL);
        helper.assertTrue(WarehouseIndex.containers(level, warehouse)
                .contains(helper.absolutePos(barrel)),
            "a placed barrel is indexed on the next query");
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        List<BlockPos> after = WarehouseIndex.containers(level, warehouse);
        helper.assertTrue(after.size() == 3 && !after.contains(helper.absolutePos(
                new BlockPos(1, 1, 1))),
            "a broken chest leaves the index, got " + after);
        helper.assertTrue(WarehouseIndex.rebuildCount(level, warehouse) == rebuilds,
            "place/break must be incremental, not a rescan");

        // A flag-2 edit fires no neighbour event: only the sweep sees it.
        BlockPos quiet = helper.absolutePos(new BlockPos(7, 2, 7));
        level.setBlock(quiet, Blocks.BARREL.defaultBlockState(), Block.UPDATE_CLIENTS);
        boolean seen = false;
        for (int i = 0; i < 4000 && !seen; i++) {
            WarehouseIndex.tick(level);
            seen = WarehouseIndex.containers(level, warehouse).contains(quiet);
        }
        helper.assertTrue(seen, "the background sweep must find a silent edit");
        helper.assertTrue(WarehouseIndex.rebuildCount(level, warehouse) == rebuilds,
            "the sweep revalidates in place, it never rescans");
        helper.succeed();
    }

    /**
     * A save written before warehouse levels has no floor: the first
     * complete count grandfathers it to the level covering what it has,
     * up to L3, so no existing warehouse shrinks.
     */
    @GameTest(batch = "warehouse_levels", template = "empty16", timeoutTicks = 200)
    public void oldSaveIsGrandfathered(GameTestHelper helper) {
        buildArena(helper);
        placeChests(helper, 70);
        var level = helper.getLevel();

        Building old = legacyCopy(newWarehouse(helper));
        helper.assertTrue(old.warehouseLevelFloor == Building.WAREHOUSE_FLOOR_PENDING,
            "a pre-level save must load with a pending floor");
        List<BlockPos> managed = WarehouseIndex.containers(level, old);
        helper.assertTrue(old.warehouseLevelFloor == 3,
            "70 containers grandfather to L3, got " + old.warehouseLevelFloor);
        helper.assertTrue(managed.size() == 64,
            "an old 70-chest warehouse keeps the old 64, got " + managed.size());

        // The floor persists: a round trip keeps it, and it is never pending again.
        Building reloaded = Building.readNbt(old.writeNbt());
        helper.assertTrue(reloaded.warehouseLevelFloor == 3,
            "the floor is saved, got " + reloaded.warehouseLevelFloor);

        // A new warehouse over the same room is plain L1.
        Building fresh = newWarehouse(helper);
        helper.assertTrue(WarehouseIndex.containers(level, fresh).size() == 16,
            "a new warehouse is not grandfathered");
        helper.succeed();
    }

    /** Small old warehouse: grandfathered to the level that covers it (L1). */
    @GameTest(batch = "warehouse_levels", template = "empty16", timeoutTicks = 200)
    public void smallOldSaveGrandfathersToL1(GameTestHelper helper) {
        buildArena(helper);
        placeChests(helper, 10);
        Building old = legacyCopy(newWarehouse(helper));
        List<BlockPos> managed = WarehouseIndex.containers(helper.getLevel(), old);
        helper.assertTrue(old.warehouseLevelFloor == 1 && managed.size() == 10,
            "10 chests grandfather to L1 and all stay managed, got L"
                + old.warehouseLevelFloor + " " + managed.size());
        helper.assertTrue(WarehouseLevels.capacity(WarehouseIndex.effectiveLevel(old)) == 16,
            "L1 capacity");
        helper.succeed();
    }

    /** Player marks: priority beats distance, excluded is never managed. */
    @GameTest(batch = "warehouse_levels", template = "empty16", timeoutTicks = 200)
    public void playerMarksSteerTheManagedSet(GameTestHelper helper) {
        buildArena(helper);
        List<BlockPos> chests = placeChests(helper, 40);
        Building warehouse = newWarehouse(helper);
        var level = helper.getLevel();
        BlockPos far = chests.stream()
            .max(Comparator.comparingDouble(p -> p.distSqr(warehouse.plaquePos)))
            .orElseThrow();
        helper.assertTrue(!WarehouseIndex.containers(level, warehouse).contains(far),
            "precondition: the farthest chest is not managed at L1");
        helper.assertTrue(WarehouseLevelService.cycleMark(warehouse, far)
                == WarehouseLevels.MARK_PRIORITY, "first cycle marks priority");
        List<BlockPos> withPriority = WarehouseIndex.containers(level, warehouse);
        helper.assertTrue(withPriority.contains(far) && withPriority.size() == 16,
            "a priority chest is managed first, capacity still 16");
        helper.assertTrue(WarehouseLevelService.cycleMark(warehouse, far)
                == WarehouseLevels.MARK_EXCLUDED, "second cycle excludes");
        warehouse.level = 3;
        warehouse.warehouseTechMax = 3; // room for all 40
        List<BlockPos> excluded = WarehouseIndex.containers(level, warehouse);
        helper.assertTrue(!excluded.contains(far) && excluded.size() == 39,
            "an excluded chest is never managed, got " + excluded.size());
        helper.assertTrue(Building.readNbt(warehouse.writeNbt()).containerMarks
                .get(far.asLong()) == WarehouseLevels.MARK_EXCLUDED,
            "marks persist");
        helper.succeed();
    }

    /**
     * Kill-switch {@code [features] warehouseLevels=false}: the warehouse
     * behaves like before this lane (first 64 in scan order, no levels, no
     * grandfather write), never loses or hides an item that was reachable,
     * and never raises the readiness blocker. Re-enabling restores levels.
     */
    @GameTest(batch = "warehouse_levels", template = "empty16", timeoutTicks = 200)
    public void killSwitchDisabledIsSafe(GameTestHelper helper) {
        buildArena(helper);
        List<BlockPos> chests = placeChests(helper, 70);
        var level = helper.getLevel();
        for (int i = 0; i < chests.size(); i++) {
            if (level.getBlockEntity(chests.get(i)) instanceof net.minecraft.world.Container c) {
                c.setItem(0, new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.OAK_LOG, 1 + i % 5));
            }
        }
        int physicalBefore = totalItems(helper, chests);
        Building warehouse = newWarehouse(helper);
        Building legacySave = legacyCopy(newWarehouse(helper));
        helper.assertTrue(WarehouseIndex.containers(level, warehouse).size() == 16,
            "enabled: L1 manages 16");

        var flag = com.hearthstead.HearthsteadServerConfig.WAREHOUSE_LEVELS_ENABLED;
        boolean before = flag.get();
        try {
            flag.set(false);
            List<BlockPos> legacy = WarehouseIndex.containers(level, warehouse);
            List<BlockPos> scanOrderFirst64 = new ArrayList<>(chests);
            scanOrderFirst64.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
            helper.assertTrue(legacy.equals(scanOrderFirst64.subList(0, 64)),
                "disabled: exactly the pre-level first 64 in scan order");
            helper.assertTrue(WarehouseIndex.capacityFor(warehouse) == 64,
                "disabled: no level capacity");
            WarehouseStorage storage = WarehouseStorage.refreshed(level, warehouse);
            helper.assertTrue(storage.lastVisitCount() == 64,
                "disabled: the old 64 containers are all readable");
            net.minecraft.world.item.ItemStack left = storage.insert(level, warehouse,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 32));
            helper.assertTrue(left.isEmpty(), "disabled: inserts still land");
            helper.assertTrue(totalItems(helper, chests) == physicalBefore + 32,
                "disabled: no item lost or duplicated");
            helper.assertTrue(FirstRaidReadinessService.warehouseStorageHealthy(level, warehouse),
                "disabled: 70 containers still never block readiness");
            WarehouseIndex.containers(level, legacySave);
            helper.assertTrue(legacySave.warehouseLevelFloor == Building.WAREHOUSE_FLOOR_PENDING,
                "disabled: no grandfather write");
        } finally {
            flag.set(before);
        }
        helper.assertTrue(WarehouseIndex.containers(level, warehouse).size() == 16,
            "re-enabled: levels apply again");
        helper.assertTrue(totalItems(helper, chests) == physicalBefore + 32,
            "switching never moves items");
        helper.succeed();
    }

    /**
     * Economy soak, 26 Sep: every chest had once been labelled Building
     * Materials (filled with cobble, later emptied), so food had no home and
     * the village starved. An EMPTY unit with an old label is now a last-
     * resort home and is re-labelled; a unit holding items, or labelled
     * within {@link com.hearthstead.settlement.warehouse.WarehouseSorting#RECLAIM_AFTER_TICKS},
     * is never taken.
     */
    @GameTest(batch = "warehouse_levels", template = "empty16", timeoutTicks = 200)
    public void emptiedStaleLabelledChestTakesFood(GameTestHelper helper) {
        buildArena(helper);
        var level = helper.getLevel();
        Building warehouse = newWarehouse(helper);
        List<BlockPos> barrels = new ArrayList<>();
        for (int x : new int[]{3, 5, 7}) {
            BlockPos rel = new BlockPos(x, 1, 3);
            helper.setBlock(rel, Blocks.BARREL);
            BlockPos abs = helper.absolutePos(rel);
            barrels.add(abs);
            // A pre-26-Sep label: owner + group, no timestamp.
            var data = level.getBlockEntity(abs).getPersistentData();
            data.putUUID("HearthsteadWarehouseSortingWarehouse", warehouse.id);
            data.putString("HearthsteadWarehouseSortingGroup", "BUILDING_MATERIALS");
        }
        ((net.minecraft.world.Container) level.getBlockEntity(barrels.get(0)))
            .setItem(0, new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.COBBLESTONE, 10));

        List<BlockPos> food = com.hearthstead.settlement.warehouse.WarehouseSorting
            .destinations(level, warehouse,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD));
        helper.assertTrue(food.equals(List.of(barrels.get(1))),
            "food must reclaim the first EMPTY stale-labelled barrel, got " + food);
        helper.assertTrue(com.hearthstead.settlement.warehouse.WarehouseSorting.assignedGroup(
                level.getBlockEntity(barrels.get(1)))
                == com.hearthstead.settlement.warehouse.WarehouseSorting.Group.FOOD,
            "and it is re-labelled Food");
        helper.assertTrue(com.hearthstead.settlement.warehouse.WarehouseSorting.assignedGroup(
                level.getBlockEntity(barrels.get(0)))
                == com.hearthstead.settlement.warehouse.WarehouseSorting.Group.BUILDING_MATERIALS,
            "a labelled barrel that holds items is never re-labelled");

        List<BlockPos> crops = com.hearthstead.settlement.warehouse.WarehouseSorting
            .destinations(level, warehouse,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT));
        helper.assertTrue(crops.equals(List.of(barrels.get(2))),
            "crops take the other stale empty barrel, not the fresh Food one, got " + crops);

        List<BlockPos> tools = com.hearthstead.settlement.warehouse.WarehouseSorting
            .destinations(level, warehouse,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_AXE));
        helper.assertTrue(tools.isEmpty(),
            "fresh labels are protected from a second Courier, got " + tools);
        helper.succeed();
    }

    /**
     * Owner-approved food overflow (26 Sep): when every unit is labelled for
     * other goods, FOOD overflows into a unit with room (label unchanged),
     * the insert really lands with no loss or duplication, the player notice
     * fires once per episode, and a freshly reserved empty unit is never
     * taken.
     */
    @GameTest(batch = "warehouse_levels", template = "empty16", timeoutTicks = 200)
    public void foodOverflowsWhenEveryChestHoldsOtherGoods(GameTestHelper helper) {
        buildArena(helper);
        var level = helper.getLevel();
        Building warehouse = newWarehouse(helper);
        List<BlockPos> barrels = new ArrayList<>();
        for (int x : new int[]{3, 5, 7}) {
            BlockPos rel = new BlockPos(x, 1, 3);
            helper.setBlock(rel, Blocks.BARREL);
            barrels.add(helper.absolutePos(rel));
        }
        BlockPos materials = barrels.get(0);
        BlockPos tools = barrels.get(1);
        BlockPos reserved = barrels.get(2);
        for (BlockPos pos : List.of(materials, tools)) {
            var data = level.getBlockEntity(pos).getPersistentData();
            data.putUUID("HearthsteadWarehouseSortingWarehouse", warehouse.id);
            data.putString("HearthsteadWarehouseSortingGroup",
                pos.equals(materials) ? "BUILDING_MATERIALS" : "TOOLS");
        }
        ((net.minecraft.world.Container) level.getBlockEntity(materials)).setItem(0,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 10));
        ((net.minecraft.world.Container) level.getBlockEntity(tools)).setItem(0,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_AXE));
        // A Courier reserves the last empty barrel for Wood right now.
        List<BlockPos> wood = com.hearthstead.settlement.warehouse.WarehouseSorting
            .destinations(level, warehouse,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG));
        helper.assertTrue(wood.equals(List.of(reserved)),
            "precondition: wood reserves the empty barrel, got " + wood);

        int noticesBefore = com.hearthstead.settlement.warehouse.WarehouseSorting
            .overflowNoticeCount();
        var bread = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 5);
        List<BlockPos> food = com.hearthstead.settlement.warehouse.WarehouseSorting
            .destinations(level, warehouse, bread.copyWithCount(1));
        helper.assertTrue(food.equals(List.of(materials)),
            "food overflows into the first unit with room, never the fresh reservation; got "
                + food);
        helper.assertTrue(com.hearthstead.settlement.warehouse.WarehouseSorting
                .foodOverflowActive(warehouse.id), "overflow status is visible");
        com.hearthstead.settlement.warehouse.WarehouseSorting.destinations(level, warehouse,
            bread.copyWithCount(1));
        helper.assertTrue(com.hearthstead.settlement.warehouse.WarehouseSorting
                .overflowNoticeCount() == noticesBefore + 1,
            "the player notice fires exactly once per overflow episode");

        List<BlockPos> containers = WarehouseIndex.containers(level, warehouse);
        int before = totalItems(helper, containers);
        var left = WarehouseStorage.of(level, warehouse).insertAt(level, warehouse,
            materials, bread.copy());
        helper.assertTrue(left.isEmpty(), "the overflow insert lands, left " + left);
        helper.assertTrue(totalItems(helper, containers) == before + 5,
            "exactly 5 bread stored: no loss, no duplication");
        helper.assertTrue(com.hearthstead.settlement.warehouse.WarehouseSorting.assignedGroup(
                level.getBlockEntity(materials))
                == com.hearthstead.settlement.warehouse.WarehouseSorting.Group.BUILDING_MATERIALS
                && com.hearthstead.settlement.warehouse.WarehouseSorting.assignedGroup(
                    level.getBlockEntity(reserved))
                == com.hearthstead.settlement.warehouse.WarehouseSorting.Group.WOOD,
            "overflow never relabels a unit and never steals the reservation");
        var stone = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_BRICKS, 3);
        helper.assertTrue(WarehouseStorage.of(level, warehouse).insertAt(level, warehouse,
                tools, stone.copy()).getCount() == 3,
            "non-food goods still respect labels (honest warehouse full)");
        helper.succeed();
    }

    private static int totalItems(GameTestHelper helper, List<BlockPos> chests) {
        int total = 0;
        for (BlockPos pos : chests) {
            if (helper.getLevel().getBlockEntity(pos) instanceof net.minecraft.world.Container c) {
                for (int slot = 0; slot < c.getContainerSize(); slot++) {
                    total += c.getItem(slot).getCount();
                }
            }
        }
        return total;
    }

    /** Serialises and strips the warehouse-level key, like a pre-level save. */
    private static Building legacyCopy(Building building) {
        CompoundTag tag = building.writeNbt();
        tag.remove("WarehouseLevelFloor");
        return Building.readNbt(tag);
    }
}
