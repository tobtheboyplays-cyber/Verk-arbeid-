package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.network.TechTreeNetwork;
import com.hearthstead.network.TechTreeSnapshotPayload;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.techtree.TechCosts;
import com.hearthstead.settlement.techtree.TechTreeData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.List;
import java.util.UUID;

/**
 * QA-UI-05: a co-op player's deposit into the Banner must reach another player's open Tech
 * Tree (affordability was stale until a close/reopen), and an unchanged Banner must not push
 * snapshots on its own.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TechTreeRefreshGameTests {

    private static final String NODE = "timber_rights";

    @SuppressWarnings("removal")
    @GameTest(template = "empty16", timeoutTicks = 120, batch = "techtree_banner_refresh")
    public void aDepositIntoTheBannerRefreshesAnOpenTreeAndIdleDoesNot(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        BlockPos rel = new BlockPos(3, 1, 3);
        helper.setBlock(rel, ModBlocks.HEARTH.get());
        BlockPos abs = helper.absolutePos(rel);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(abs);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Refreshby", abs);
        SettlementSavedData.get(helper.getLevel()).settlements.put(settlement.id, settlement);
        hearth.bindSettlement(settlement.id);
        Development.of(helper.getLevel(), settlement);

        List<DevelopmentNode.Cost> costs = TechCosts.costs(TechTreeData.get().node(NODE));
        int pick = -1;
        for (int i = 0; i < costs.size(); i++) {
            if (costs.get(i).item() != ModItems.GOLD_COIN.get()) {
                pick = i;
                break;
            }
        }
        helper.assertTrue(pick >= 0, "fixture: " + NODE + " costs a material: " + costs);
        DevelopmentNode.Cost cost = costs.get(pick);
        final int slot = pick;

        ServerPlayer viewer = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(viewer.connection.getConnection());
        viewer.setPos(abs.getX() + 2.5D, abs.getY(), abs.getZ() + 0.5D);
        TechTreeNetwork.open(viewer, settlement, hearth);
        int before = TechTreeNetwork.refreshesForTests();

        helper.runAtTickTime(45L, () -> {
            helper.assertTrue(TechTreeNetwork.refreshesForTests() == before,
                "an unchanged Banner pushes no snapshots");
            helper.assertTrue(have(viewer, settlement, hearth, slot) == 0, "fixture: nothing in stock yet");
            // Another player drops the material into the Banner.
            ItemStack rest = hearth.getInventory().insertItem(0, new ItemStack(cost.item(), cost.count()), false);
            helper.assertTrue(rest.isEmpty(), "fixture: the deposit fits");
        });
        helper.runAtTickTime(75L, () -> {
            helper.assertTrue(TechTreeNetwork.refreshesForTests() == before + 1,
                "one refresh reached the open tree after the deposit, got "
                    + (TechTreeNetwork.refreshesForTests() - before));
            helper.assertTrue(have(viewer, settlement, hearth, slot) == cost.count(),
                "the refreshed tree sees the deposited " + cost.count());
        });
        helper.runAtTickTime(110L, () -> {
            helper.assertTrue(TechTreeNetwork.refreshesForTests() == before + 1,
                "no further refresh without a further change");
            TechTreeNetwork.forget(viewer.getUUID());
            SettlementSavedData.get(helper.getLevel()).settlements.remove(settlement.id);
            helper.succeed();
        });
    }

    private static int have(ServerPlayer viewer, Settlement settlement, HearthBlockEntity hearth, int slot) {
        TechTreeSnapshotPayload.NodeState node = TechTreeNetwork.snapshot(viewer, settlement, hearth,
            "", "", false, false).node(NODE);
        return node == null ? -1 : node.have()[slot];
    }
}
