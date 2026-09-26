package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidDirector;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Verifies the repair motion selected from the recorded original block. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RepairNailGameTests {

    @GameTest(template = "empty16", timeoutTicks = 1200, batch = "repair_day")
    public void woodenScarUsesNailAndRestoresOnce(GameTestHelper helper) {
        repairScar(helper, Blocks.OAK_PLANKS, Items.OAK_PLANKS,
            SettlerActivity.WORK_NAIL);
    }

    @GameTest(template = "empty16", timeoutTicks = 1200, batch = "repair_day")
    public void stoneScarUsesChiselAndRestoresOnce(GameTestHelper helper) {
        repairScar(helper, Blocks.STONE_BRICKS, Items.STONE_BRICKS,
            SettlerActivity.WORK_CHISEL);
    }

    private static void repairScar(GameTestHelper helper, Block original,
                                   Item material, SettlerActivity expectedActivity) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(3000);
        for (int x = 0; x < 14; x++) {
            for (int z = 0; z < 14; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }

        BlockPos center = new BlockPos(7, 1, 7);
        var arena = helper.getBounds();
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.values().removeIf(old ->
            arena.contains(old.center.getX() + 0.5, old.center.getY() + 0.5,
                old.center.getZ() + 0.5));
        Settlement settlement = new Settlement(UUID.randomUUID(), "Repair test",
            helper.absolutePos(center));
        settlement.radius = 12;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();

        Building yard = GameTestFixtures.register(helper, settlement,
            BuildingType.MASON, 3, 3);
        BlockPos chestRel = new BlockPos(4, 1, 3);
        helper.setBlock(chestRel, Blocks.CHEST);
        BlockEntity blockEntity = level.getBlockEntity(helper.absolutePos(chestRel));
        helper.assertTrue(blockEntity instanceof Container,
            "the mason yard chest must be a container");
        Container chest = (Container) blockEntity;
        chest.setItem(0, new ItemStack(material, 3));

        SettlerEntity mason = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(3, 1, 3));
        mason.setSettlerName("Repairer");
        mason.bindTo(settlement.id, settlement.center);
        settlement.putRecord(mason.getUUID(), "Repairer", Profession.NONE);
        helper.assertTrue(Employment.hire(level, settlement, yard, mason).ok(),
            "the test settler must be hired as mason");

        BlockPos scarRel = new BlockPos(10, 1, 10);
        BlockPos scarAbs = helper.absolutePos(scarRel);
        helper.assertTrue(helper.getBlockState(scarRel).isAir(),
            "the scar must begin as a hole");
        RaidDirector.recordScar(level, settlement.id, scarAbs,
            original.defaultBlockState());

        final boolean[] sawExpectedActivity = {false};
        final long[] restoredAt = {-1};
        helper.succeedWhen(() -> {
            if (mason.getActivity() == expectedActivity) {
                sawExpectedActivity[0] = true;
            }
            helper.assertTrue(helper.getBlockState(scarRel).is(original),
                "the original block must be restored");
            helper.assertTrue(sawExpectedActivity[0],
                "the repairer must visibly use " + expectedActivity);
            helper.assertTrue(chest.getItem(0).is(material)
                    && chest.getItem(0).getCount() == 2,
                "exactly one matching item must leave the chest");
            helper.assertTrue(RaidDirector.scarsOf(level, settlement.id).isEmpty(),
                "the completed scar must leave the ledger");
            helper.assertTrue(settlement.repairDiscountProgress() == 1,
                "the repair must be recorded exactly once");
            if (restoredAt[0] < 0) {
                restoredAt[0] = level.getGameTime();
            }
            helper.assertTrue(level.getGameTime() >= restoredAt[0] + 80,
                "wait to confirm the block, stock and repair count remain stable");
        });
    }
}
