package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * SCENARIO lane (26 Sep): idle workshop upkeep in the TUNED economy the
 * friends play (batch {@code scenario_economy_upkeep}). A crafter with
 * nothing to make and nothing to fetch tidies its bench: split stacks are
 * merged with nothing lost or made, and the first input that arrives ends
 * the upkeep and gets crafted.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ScenarioEconomyGameTests {
    private static final String UPKEEP = "scenario_economy_upkeep";

    @BeforeBatch(batch = UPKEEP)
    public static void tune(ServerLevel level) { EconomyConfig.testOverride = Boolean.TRUE; }

    @AfterBatch(batch = UPKEEP)
    public static void untune(ServerLevel level) { EconomyConfig.testOverride = null; }

    @GameTest(template = "empty16", timeoutTicks = 3600, batch = UPKEEP)
    public void anIdleCrafterTidiesItsBenchThenCraftsTheFirstInput(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        Settlement s = new Settlement(UUID.randomUUID(), "Tidyholm", helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 10;
        SettlementSavedData.get(helper.getLevel()).settlements.put(s.id, s);
        Building shop = GameTestFixtures.register(helper, s, BuildingType.CARPENTER, 2, 2);
        BlockPos benchRel = new BlockPos(3, 1, 2);
        helper.setBlock(benchRel, Blocks.CHEST);
        Container bench = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(benchRel));
        // Finished barrels left in split stacks: nothing to make, nothing to fetch.
        bench.setItem(0, new ItemStack(Items.BARREL, 10));
        bench.setItem(9, new ItemStack(Items.BARREL, 10));
        bench.setItem(18, new ItemStack(Items.BARREL, 10));
        SettlerEntity carpenter = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 4));
        carpenter.setSettlerName("Tidy");
        carpenter.bindTo(s.id, s.center);
        s.putRecord(carpenter.getUUID(), "Tidy", Profession.NONE);
        carpenter.attributes().pinForTest(Attribute.STAMINA, 50);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, shop, carpenter).ok(), "fixture: a carpenter is hired");
        helper.onEachTick(() -> helper.getLevel().setDayTime(3000)); // the morning work block
        boolean[] fed = {false};
        helper.succeedWhen(() -> {
            if (!fed[0]) {
                int stacks = 0;
                for (int i = 0; i < bench.getContainerSize(); i++) if (bench.getItem(i).is(Items.BARREL)) stacks++;
                helper.assertTrue(count(bench, Items.BARREL) == 30, "tidying never loses or makes goods: "
                    + count(bench, Items.BARREL));
                helper.assertTrue(stacks == 1, "idle upkeep merges the split barrels into one stack (" + stacks
                    + " stacks, activity " + carpenter.getActivity() + ")");
                // The first input arrives.
                bench.setItem(26, new ItemStack(Items.OAK_PLANKS, 8));
                fed[0] = true;
                helper.fail("fed: wait for crafting");
            }
            helper.assertTrue(count(bench, Items.OAK_PLANKS) < 8,
                "the first input ends the upkeep and is crafted (planks left " + count(bench, Items.OAK_PLANKS)
                    + ", activity " + carpenter.getActivity() + ")");
        });
    }

    private static int count(Container container, Item item) {
        int n = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(item)) n += container.getItem(i).getCount();
        }
        return n;
    }
}
