package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * ACCEPT-JOBS audit (2026-08-26): MILLER has stood in {@code
 * Employment.TRADES} since the mill and brewery grew recipe tables (the
 * coordinator addendum {@link Profession#MILLER}'s own javadoc names --
 * "CrafterWorkGoal already knows how to run any building with a recipe
 * table, it only needed somebody hireable to send there"), but the ONLY
 * gametest coverage of {@code BuildingType.MILL} is {@code
 * ChainsGameTests}, which calls {@code Production.run} directly and says so
 * in its own class doc: it proves the recipe table, never that a hired
 * miller reaches the mill and turns the crank themselves. This is the
 * missing proof: {@link Employment#hire} at a real MILL, {@code
 * CrafterWorkGoal} (never {@code Production.run}), WORK_KNEAD observed, and
 * real flour sitting in the mill's own chest.
 *
 * <p>The mill is the one burning-adjacent building that does not burn
 * ({@link com.hearthstead.building.Fuel#burns} omits MILL), so no fuel is
 * seeded here on purpose -- a test that fed it charcoal it never needs would
 * misstate the trade's real inputs.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeMillerGameTests {

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    private static Settlement settlement(GameTestHelper helper) {
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Mollholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static Building building(GameTestHelper helper, Settlement s,
                                     BuildingType type, int x, int z) {
        return GameTestFixtures.register(helper, s, type, x, z);
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s,
                                         String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    private static int countOf(Container chest, Item item) {
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * The whole loop, end to end: a mill's chest holding wheat, a settler
     * hired into the building, and real flour that did not exist before --
     * with no farmhouse or warehouse anywhere in the world (D-007). Wheat is
     * seeded well above any one settler's daily effort pool could ever spend
     * (this test is about the loop existing, not the exact batch count --
     * {@code ChainsGameTests} and {@code ResearchGameTests} already pin the
     * 3-wheat-to-2-flour ratio's arithmetic), so the conservation check is
     * the ratio itself: every 2 flour banked must have cost exactly 3 wheat,
     * no more, no less.
     */
    @GameTest(batch = "trade_miller", template = "empty16", timeoutTicks = 600)
    public void aHiredMillerActuallyGrindsFlour(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building mill = building(helper, s, BuildingType.MILL, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        BlockEntity be =
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(5, 1, 4)));
        helper.assertTrue(be instanceof Container, "the arena chest should be a container");
        Container chest = (Container) be;
        chest.setItem(0, new ItemStack(Items.WHEAT, 30));
        int wheatBefore = countOf(chest, Items.WHEAT);

        SettlerEntity molle = settler(helper, s, "Molle", 4, 4);
        molle.attributes().pinForTest(com.hearthstead.entity.Attribute.STAMINA, 50);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, mill, molle).ok(),
            "a mill must be able to take a miller");
        helper.assertTrue(molle.getProfession() == Profession.MILLER,
            "hired into a mill, they mill");
        helper.assertTrue(
            Employment.motionOf(BuildingType.MILL) == SettlerActivity.WORK_KNEAD,
            "the miller's motion must be its own, not a shared work loop");

        helper.getLevel().setDayTime(3000);

        boolean[] sawKneading = new boolean[1];

        helper.succeedWhen(() -> {
            if (molle.getActivity() == SettlerActivity.WORK_KNEAD) {
                sawKneading[0] = true;
            }
            int flour = countOf(chest, ModItems.FLOUR.get());
            int wheatLeft = countOf(chest, Items.WHEAT);
            helper.assertTrue(flour % 2 == 0,
                "flour recipe's own output is 2 per batch, so the total must "
                    + "land on an even number, got " + flour);
            helper.assertTrue(wheatBefore - wheatLeft == (flour / 2) * 3,
                "3 wheat per 2 flour, conserved exactly: spent "
                    + (wheatBefore - wheatLeft) + " wheat for " + flour + " flour");
            helper.assertTrue(flour > 0,
                "a settler hired into the mill through Employment.hire must "
                    + "actually grind flour (activity=" + molle.getActivity() + ")");
            helper.assertTrue(sawKneading[0],
                "the miller must actually be seen performing WORK_KNEAD at some "
                    + "point, not just have the output appear while idle");
        });
    }
}
