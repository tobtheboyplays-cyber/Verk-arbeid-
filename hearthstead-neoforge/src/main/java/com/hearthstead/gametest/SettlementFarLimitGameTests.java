package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Bigger towns (owner, 26 Sep: "Større"): a workplace near the edge of the
 * new claim ([settlement] radius, default 72) must still work. The Banner
 * stands in one corner of a 64x64 arena and the Lumber Camp in the far
 * corner, about 68 blocks away. The real Lumberer walks out to the camp for
 * the axe, back to the tree in its Work Zone near the Banner, and banks the
 * logs in the far camp chest: long walks both ways, a real delivery.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class SettlementFarLimitGameTests {

    @GameTest(batch = "settlement_far_limit", template = "empty64", timeoutTicks = 6000)
    public void aLumberCampNearTheNewClaimEdgeStillDelivers(GameTestHelper helper) {
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 8; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        var data = com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Vidmark",
            helper.absolutePos(new BlockPos(3, 1, 3)));
        s.radius = HearthsteadServerConfig.settlementRadius();
        data.settlements.put(s.id, s);
        data.setDirty();
        Building camp = GameTestFixtures.register(helper, s, BuildingType.LUMBER_CAMP, 50, 50);
        double campDistance = Math.sqrt(camp.anchor.distSqr(s.center));
        helper.assertTrue(campDistance > 60.0D && campDistance <= s.radius,
            "the camp stands near the claim edge: " + campDistance + " of " + s.radius);
        BlockPos chestRel = new BlockPos(51, 1, 52);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container storage = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(chestRel));
        storage.setItem(0, new ItemStack(Items.IRON_AXE));

        // The fixture Work Zone covers the arena's first 16x16 corner, by the Banner.
        BlockPos dirt = new BlockPos(8, 1, 8);
        helper.setBlock(dirt, Blocks.DIRT);
        BlockPos trunk = dirt.above();
        for (int y = 0; y < 4; y++) {
            helper.setBlock(trunk.above(y), Blocks.OAK_LOG);
        }
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                helper.setBlock(trunk.above(4).offset(x, 0, z), Blocks.OAK_LEAVES);
            }
        }

        SettlerEntity worker = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(5, 1, 5));
        worker.setSettlerName("Wide Walker");
        worker.bindTo(s.id, s.center);
        s.putRecord(worker.getUUID(), "Wide Walker", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, camp, worker).ok(), "hire the Lumberer");
        worker.setHunger(100.0F);
        worker.setEnergy(100.0F);
        helper.getLevel().setDayTime(2000);

        final boolean[] reachedCamp = {false};
        final boolean[] chopped = {false};
        helper.succeedWhen(() -> {
            reachedCamp[0] |= worker.blockPosition().distSqr(camp.anchor) <= 36.0D;
            chopped[0] |= worker.getActivity() == SettlerActivity.WORK_CHOP;
            int stored = 0;
            for (int slot = 0; slot < storage.getContainerSize(); slot++) {
                if (storage.getItem(slot).is(Items.OAK_LOG)) {
                    stored += storage.getItem(slot).getCount();
                }
            }
            helper.assertTrue(reachedCamp[0] && chopped[0] && stored >= 4,
                "the far Lumberer walks out, chops by the Banner and banks the logs at the far camp"
                    + " [reached=" + reachedCamp[0] + " chopped=" + chopped[0] + " stored=" + stored
                    + " at " + helper.relativePos(worker.blockPosition()) + " act=" + worker.getActivity()
                    + " route=" + worker.routeFailureNote() + "]");
        });
    }
}
