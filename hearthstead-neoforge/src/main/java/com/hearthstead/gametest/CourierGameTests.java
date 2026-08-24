package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
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

import java.util.UUID;

/**
 * SLICE A2a — the courier actually delivers, and never loses an item.
 *
 * <p>These are aimed squarely at MineColonies' shipped delivery failures
 * (see docs/project/REFERENCE_ANALYSIS.md): deliveries that silently never
 * happen, and items that vanish when a destination cannot take them.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class CourierGameTests {

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

    private static Settlement makeSettlement(GameTestHelper helper, BlockPos centerRel,
                                             int radius) {
        var level = helper.getLevel();
        var arena = helper.getBounds();
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.values().removeIf(old ->
            arena.contains(old.center.getX() + 0.5, old.center.getY() + 0.5,
                old.center.getZ() + 0.5));
        Settlement s = new Settlement(UUID.randomUUID(), "Courierholm",
            helper.absolutePos(centerRel));
        s.radius = radius;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    /** Registers a warehouse Building over a corner of the arena. */
    private static Building addWarehouse(GameTestHelper helper, Settlement s,
                                         BlockPos minRel, BlockPos maxRel) {
        BoundingBox bounds = BoundingBox.fromCorners(
            helper.absolutePos(minRel), helper.absolutePos(maxRel));
        Building b = new Building(UUID.randomUUID(), BuildingType.WAREHOUSE,
            helper.absolutePos(minRel), helper.absolutePos(minRel), bounds);
        b.valid = true;
        s.buildings.add(b);
        return b;
    }

    private static Container containerAt(GameTestHelper helper, BlockPos rel) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        return be instanceof Container c ? c : null;
    }

    private static int countIn(Container c, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int slot = 0; slot < c.getContainerSize(); slot++) {
            ItemStack stack = c.getItem(slot);
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private static SettlerEntity courier(GameTestHelper helper, Settlement s, BlockPos rel) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), rel);
        settler.setSettlerName("Bud");
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), settler.getSettlerName(), Profession.NONE);
        settler.assignProfession(Profession.COURIER);
        return settler;
    }

    /**
     * The whole point: goods left at the hearth end up in warehouse chests,
     * carried there by a settler, with the total item count unchanged.
     */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "day")
    public void courierHaulsGoodsFromHearthToWarehouse(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = makeSettlement(helper, hearthRel, 12);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
            hearth.insertGoods(new ItemStack(Items.OAK_LOG, 6));
        }
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.CHEST);
        addWarehouse(helper, s, new BlockPos(9, 1, 9), new BlockPos(11, 3, 11));

        SettlerEntity bud = courier(helper, s, new BlockPos(4, 1, 4));
        final boolean[] sawCarrying = {false};

        helper.succeedWhen(() -> {
            if (bud.getActivity() == SettlerActivity.CARRYING) {
                sawCarrying[0] = true;
            }
            Container chest = containerAt(helper, new BlockPos(10, 1, 10));
            helper.assertTrue(chest != null, "warehouse chest should exist");
            int delivered = countIn(chest, Items.OAK_LOG);
            helper.assertTrue(delivered >= 6,
                "all 6 logs should reach the warehouse, saw " + delivered
                    + " (act=" + bud.getActivity() + ")");
            helper.assertTrue(sawCarrying[0],
                "the courier should visibly carry (CARRYING), not teleport goods");
        });
    }

    /**
     * Food is the settlement's life support and must never be hauled away
     * (D-A2a-1) -- draining the hearth would quietly starve everyone.
     */
    @GameTest(template = "empty16", timeoutTicks = 1200, batch = "day")
    public void courierNeverTakesFoodFromTheHearth(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = makeSettlement(helper, hearthRel, 12);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
            hearth.insertGoods(new ItemStack(Items.BREAD, 8));
        }
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.CHEST);
        addWarehouse(helper, s, new BlockPos(9, 1, 9), new BlockPos(11, 3, 11));
        courier(helper, s, new BlockPos(4, 1, 4));

        helper.runAtTickTime(600, () -> {
            Container chest = containerAt(helper, new BlockPos(10, 1, 10));
            helper.assertTrue(chest != null, "warehouse chest should exist");
            helper.assertTrue(countIn(chest, Items.BREAD) == 0,
                "bread must never be hauled out of the hearth, found "
                    + countIn(chest, Items.BREAD) + " in the warehouse");
            BlockEntity be = helper.getLevel()
                .getBlockEntity(helper.absolutePos(hearthRel));
            helper.assertTrue(be instanceof HearthBlockEntity h
                    && h.countFoodUnits() > 0,
                "the hearth should still hold its food");
            helper.succeed();
        });
    }

    /**
     * With no warehouse the courier must idle quietly, not thrash between
     * states -- MineColonies' delivery loop failures (#5333/#3892) are
     * exactly this shape.
     */
    @GameTest(template = "empty16", timeoutTicks = 800, batch = "day")
    public void courierIdlesWithoutAWarehouse(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 12);
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = makeSettlement(helper, hearthRel, 10);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
            hearth.insertGoods(new ItemStack(Items.OAK_LOG, 4));
        }
        SettlerEntity bud = courier(helper, s, new BlockPos(5, 1, 5));

        helper.runAtTickTime(400, () -> {
            helper.assertTrue(bud.getActivity() != SettlerActivity.CARRYING
                    && bud.getActivity() != SettlerActivity.SORTING,
                "with no warehouse the courier must not enter a haul state, got "
                    + bud.getActivity());
            int bagged = 0;
            for (int i = 0; i < bud.bag.getContainerSize(); i++) {
                bagged += bud.bag.getItem(i).getCount();
            }
            helper.assertTrue(bagged == 0,
                "the courier should not have picked anything up, bag=" + bagged);
            BlockEntity be = helper.getLevel()
                .getBlockEntity(helper.absolutePos(hearthRel));
            helper.assertTrue(be instanceof HearthBlockEntity h
                    && h.getInventory().getStackInSlot(0).getCount() == 4,
                "the logs should still be in the hearth, untouched");
            helper.succeed();
        });
    }
}
