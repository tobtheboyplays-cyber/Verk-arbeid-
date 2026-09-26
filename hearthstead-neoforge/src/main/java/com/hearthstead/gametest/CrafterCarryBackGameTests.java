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
import com.hearthstead.settlement.economy.EconomyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Codex P2 lead, verified 2026-09-26: the crafter's self-fetch carry-back
 * (FetchLeg.TO_BENCH) had no timeout and re-pathed every tick once navigation
 * was done, so a bench chest that became unreachable pinned the crafter for
 * its whole shift. The return leg now gives up after FETCH_GIVE_UP_TICKS,
 * keeps the load in the bag, backs off, and delivers once the route is back.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class CrafterCarryBackGameTests {
    private static final String BATCH = "crafter_carry_back";

    @BeforeBatch(batch = BATCH)
    public static void tune(ServerLevel level) {
        EconomyConfig.testOverride = Boolean.TRUE;
    }

    @AfterBatch(batch = BATCH)
    public static void untune(ServerLevel level) {
        EconomyConfig.testOverride = null;
    }

    private static int count(Container c, Item item) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    @GameTest(template = "empty16", timeoutTicks = 3600, batch = BATCH)
    public void anUnreachableBenchChestStopsTheCarryBackAndResumesWhenOpened(GameTestHelper helper) {
        EconomyConfig.testOverride = Boolean.TRUE;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Bakvei", helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
        Building sawmill = GameTestFixtures.register(helper, s, BuildingType.SAWMILL, 2, 2);
        BlockPos benchRel = new BlockPos(4, 1, 4);
        helper.setBlock(benchRel, Blocks.CHEST);
        Container bench = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(benchRel));
        // Wall the chest in: every stand cell around it is solid, two high.
        BlockPos[] ring = new BlockPos[8];
        int k = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                ring[k++] = benchRel.offset(dx, 0, dz);
            }
        }
        for (BlockPos p : ring) {
            helper.setBlock(p, Blocks.STONE_BRICKS);
            helper.setBlock(p.above(), Blocks.STONE_BRICKS);
        }
        SettlerEntity saw = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(11, 1, 11));
        saw.setSettlerName("Sawny");
        saw.bindTo(s.id, s.center);
        s.putRecord(saw.getUUID(), "Sawny", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, sawmill, saw).ok(), "hire sawyer");
        saw.bag.setItem(0, new ItemStack(Items.OAK_LOG, 8)); // a fetched load on the way home
        saw.setHunger(100.0F);
        saw.setEnergy(100.0F);

        final long[] gaveUpAt = {-1L};
        final int[] carryingAfterGiveUp = {0};
        final boolean[] reopened = {false};
        helper.onEachTick(() -> {
            helper.getLevel().setDayTime(2000);
            int logs = count(bench, Items.OAK_LOG) + count(saw.bag, Items.OAK_LOG)
                + helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds())
                    .stream().filter(e -> e.getItem().is(Items.OAK_LOG)).mapToInt(e -> e.getItem().getCount()).sum();
            int planks = count(bench, Items.OAK_PLANKS);
            if (logs + (planks + 5) / 6 < 8 && planks == 0) {
                helper.fail("logs must be conserved while blocked: " + logs);
            }
            if (gaveUpAt[0] < 0 && saw.routeFailureNote().startsWith("crafter_carry_back_unreachable")) {
                gaveUpAt[0] = helper.getTick();
            }
            if (gaveUpAt[0] >= 0 && !reopened[0] && saw.getActivity() == SettlerActivity.CARRYING
                && helper.getTick() - gaveUpAt[0] < 300) {
                carryingAfterGiveUp[0]++;
            }
            if (gaveUpAt[0] >= 0 && !reopened[0] && helper.getTick() - gaveUpAt[0] > 100) {
                // Repair the route: open the south side of the ring.
                helper.setBlock(benchRel.offset(0, 0, 1), Blocks.AIR);
                helper.setBlock(benchRel.offset(0, 1, 1), Blocks.AIR);
                reopened[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(gaveUpAt[0] >= 0 && gaveUpAt[0] <= 1600,
                "the blocked carry-back must give up in bounded time, note=" + saw.routeFailureNote());
            helper.assertTrue(carryingAfterGiveUp[0] < 40,
                "after giving up the sawyer must not keep walking the dead route");
            helper.assertTrue(count(saw.bag, Items.OAK_LOG) == 0
                    && count(bench, Items.OAK_LOG) + count(bench, Items.OAK_PLANKS) > 0,
                "after the route is repaired the load reaches the bench; bag=" + count(saw.bag, Items.OAK_LOG));
        });
    }
}
