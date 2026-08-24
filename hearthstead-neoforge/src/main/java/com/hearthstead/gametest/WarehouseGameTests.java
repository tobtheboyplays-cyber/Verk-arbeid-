package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.warehouse.WarehouseStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * SLICE A2a — warehouse storage index.
 *
 * <p>This is also the proof for the plan's one recorded UNKNOWN: whether
 * `tools/hearthstead-qa gametest` discovers a SECOND {@code @GameTestHolder}
 * class at all (everything lived in {@code HearthsteadGameTests} until now).
 * If these tests appear in the run's test count, multi-holder discovery
 * works and the remaining A2a pieces can each own their own test class
 * instead of contending over one file.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class WarehouseGameTests {

    /** Flat stone floor with a 2-high rim, matching the shared arena style. */
    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean rim = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z),
                        rim && y <= 2 ? Blocks.STONE_BRICKS.defaultBlockState()
                                      : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /** A Building whose bounds cover the arena, without needing a plaque. */
    private static Building warehouseOver(GameTestHelper helper, int size) {
        BlockPos min = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos max = helper.absolutePos(new BlockPos(size - 2, 3, size - 2));
        BoundingBox bounds = BoundingBox.fromCorners(min, max);
        return new Building(UUID.randomUUID(),
            com.hearthstead.building.BuildingType.WAREHOUSE,
            helper.absolutePos(new BlockPos(1, 2, 1)),
            helper.absolutePos(new BlockPos(1, 1, 1)), bounds);
    }

    private static Container containerAt(GameTestHelper helper, BlockPos rel) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        return be instanceof Container c ? c : null;
    }

    /** Brute-force recount of every container in the arena, for comparison. */
    private static int bruteForceCount(GameTestHelper helper, int size) {
        int total = 0;
        for (int x = 0; x < size; x++) {
            for (int y = 0; y <= 4; y++) {
                for (int z = 0; z < size; z++) {
                    Container c = containerAt(helper, new BlockPos(x, y, z));
                    if (c == null) {
                        continue;
                    }
                    for (int slot = 0; slot < c.getContainerSize(); slot++) {
                        total += c.getItem(slot).getCount();
                    }
                }
            }
        }
        return total;
    }

    /**
     * The index must equal a brute-force recount of the same chests, and
     * must stay correct after the world is edited underneath it (a player
     * moving items by hand is the normal case, not an edge case).
     */
    @GameTest(template = "empty16", timeoutTicks = 400)
    public void warehouseIndexMatchesRealChests(GameTestHelper helper) {
        buildArena(helper, 12);
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.CHEST);
        helper.setBlock(new BlockPos(5, 1, 3), Blocks.BARREL);
        Building warehouse = warehouseOver(helper, 12);

        Container chest = containerAt(helper, new BlockPos(3, 1, 3));
        Container barrel = containerAt(helper, new BlockPos(5, 1, 3));
        helper.assertTrue(chest != null && barrel != null,
            "arena chest and barrel should both be real containers");
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 12));
        barrel.setItem(0, new ItemStack(Items.WHEAT, 7));

        WarehouseStorage storage =
            WarehouseStorage.refreshed(helper.getLevel(), warehouse);
        helper.assertTrue(storage.totalItems() == 19,
            "index should see 19 items, saw " + storage.totalItems());
        helper.assertTrue(storage.distinctTypes() == 2,
            "index should see 2 distinct types, saw " + storage.distinctTypes());
        helper.assertTrue(storage.totalItems() == bruteForceCount(helper, 12),
            "index total must equal a brute-force recount");

        // Edit the world behind the index, then prove a refresh tracks it.
        barrel.setItem(1, new ItemStack(Items.STONE, 5));
        WarehouseStorage after =
            WarehouseStorage.refreshed(helper.getLevel(), warehouse);
        helper.assertTrue(after.totalItems() == 24,
            "index should follow a hand edit to 24, saw " + after.totalItems());
        helper.assertTrue(after.totalItems() == bruteForceCount(helper, 12),
            "index must still equal a brute-force recount after a hand edit");
        helper.succeed();
    }

    /** The container walk is capped, so a huge warehouse cannot stall a tick. */
    @GameTest(template = "empty16", timeoutTicks = 400)
    public void warehouseScanIsBounded(GameTestHelper helper) {
        buildArena(helper, 12);
        int placed = 0;
        for (int x = 1; x <= 9 && placed < 40; x++) {
            for (int z = 1; z <= 9 && placed < 40; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.CHEST);
                placed++;
            }
        }
        Building warehouse = warehouseOver(helper, 12);
        List<BlockPos> found =
            WarehouseIndex.containers(helper.getLevel(), warehouse);
        helper.assertTrue(found.size() <= WarehouseIndex.MAX_CONTAINERS,
            "container scan must respect MAX_CONTAINERS, got " + found.size());
        helper.assertTrue(found.size() >= 20,
            "scan should still find the chests it is meant to, got " + found.size());

        WarehouseStorage storage =
            WarehouseStorage.refreshed(helper.getLevel(), warehouse);
        helper.assertTrue(storage.lastVisitCount() <= WarehouseIndex.MAX_CONTAINERS,
            "refresh must visit at most MAX_CONTAINERS containers, visited "
                + storage.lastVisitCount());
        helper.succeed();
    }

    /**
     * Item conservation on insert (INV-3): whatever the warehouse does not
     * accept must come back as leftover, never vanish. A full warehouse is
     * the case that matters — MineColonies shipped delivery bugs where a
     * rejected item silently disappeared.
     */
    @GameTest(template = "empty16", timeoutTicks = 400)
    public void warehouseInsertConservesItems(GameTestHelper helper) {
        buildArena(helper, 12);
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.CHEST);
        Building warehouse = warehouseOver(helper, 12);
        Container chest = containerAt(helper, new BlockPos(3, 1, 3));
        helper.assertTrue(chest != null, "arena chest should be a real container");

        WarehouseStorage storage =
            WarehouseStorage.refreshed(helper.getLevel(), warehouse);
        ItemStack leftover = storage.insert(helper.getLevel(), warehouse,
            new ItemStack(Items.OAK_LOG, 30));
        helper.assertTrue(leftover.isEmpty(),
            "an empty chest should take 30 logs, leftover=" + leftover.getCount());
        helper.assertTrue(bruteForceCount(helper, 12) == 30,
            "30 logs should now physically be in the chest, saw "
                + bruteForceCount(helper, 12));

        // Fill every slot so the next insert cannot be accepted at all.
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.STONE, 64));
        }
        int before = bruteForceCount(helper, 12);
        WarehouseStorage full =
            WarehouseStorage.refreshed(helper.getLevel(), warehouse);
        helper.assertTrue(!full.hasRoom(helper.getLevel(), warehouse),
            "a chest with every slot at max stack should report no room");
        ItemStack rejected = full.insert(helper.getLevel(), warehouse,
            new ItemStack(Items.OAK_LOG, 16));
        helper.assertTrue(rejected.getCount() == 16,
            "a full warehouse must return all 16 logs, returned "
                + rejected.getCount());
        helper.assertTrue(bruteForceCount(helper, 12) == before,
            "a rejected insert must not change what is stored");
        helper.succeed();
    }
}
