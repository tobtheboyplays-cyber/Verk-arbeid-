package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Production;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * VISUAL-2 — the tanner's production loop, end to end.
 *
 * <p>Mirrors {@code EmploymentGameTests#aHiredBakerActuallyBakes}: a hired
 * tradesperson standing in their own building, working from their own
 * chests alone (D-007), with the new signature motion this trade was wired
 * up with in {@link Employment#motionOf} actually observed doing the work,
 * not just declared. See that class's helper Javadoc for why the settlement
 * is registered through {@code SettlementSavedData} rather than kept as a
 * bare object, and why the radius stays small (6): the arena floor is
 * shared with concurrently running tests, and a generous radius answers for
 * a neighbour's hearth.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeTannerGameTests {

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
        Settlement s = new Settlement(UUID.randomUUID(), "Garveriholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static Building building(GameTestHelper helper, Settlement s,
                                     BuildingType type, int x, int z) {
        BlockPos anchor = helper.absolutePos(new BlockPos(x, 1, z));
        Building building = new Building(UUID.randomUUID(), type,
            helper.absolutePos(new BlockPos(x, 2, z)), anchor,
            BoundingBox.fromCorners(anchor, anchor.offset(3, 2, 3)));
        building.valid = true;
        s.buildings.add(building);
        return building;
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

    private static Container containerAt(GameTestHelper helper, BlockPos rel) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        return be instanceof Container c ? c : null;
    }

    private static int countOf(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static Production.Recipe recipeById(BuildingType type, String id) {
        for (Production.Recipe recipe : Production.of(type)) {
            if (recipe.id().equals(id)) {
                return recipe;
            }
        }
        throw new IllegalStateException("no recipe '" + id + "' for " + type.id());
    }

    /**
     * The whole tanner loop, end to end.
     *
     * <p>First an honest negative: with no rabbit hide in the chest, a hired
     * tanner at their post must sit idle rather than fake the clip — this is
     * what stops a trade's motion from becoming decoration playing over
     * nothing (fails-if-idle). Then hide appears, and inside a bounded
     * number of ticks the tanner scrapes it into leather — WORK_SCRAPE (this
     * trade's newly wired signature motion) is actually observed doing it,
     * chest deltas match the recipe's own numbers exactly (four hides in for
     * one leather out, and only ever in whole batches, so nothing is created
     * or lost outside what the recipe declares), and a second batch runs
     * straight after the first with no re-hire, proving this is a loop and
     * not a one-shot.
     */
    @GameTest(template = "empty16", timeoutTicks = 900)
    public void aHiredTannerScrapesHidesIntoLeather(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building tannery = building(helper, s, BuildingType.TANNERY, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        Container chest = containerAt(helper, new BlockPos(5, 1, 4));
        helper.assertTrue(chest != null, "the arena chest should be a container");

        SettlerEntity garvar = settler(helper, s, "Garvar", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tannery, garvar).ok(),
            "a tannery must be able to take a tanner");
        helper.assertTrue(garvar.getProfession() == Profession.TANNER,
            "hired into a tannery, they tan");
        helper.assertTrue(Employment.motionOf(BuildingType.TANNERY) == SettlerActivity.WORK_SCRAPE,
            "the tanner's signature motion must be the newly wired WORK_SCRAPE");

        helper.getLevel().setDayTime(3000); // mid-morning: working hours

        Production.Recipe recipe = recipeById(BuildingType.TANNERY, "leather");
        int cycles = 3;
        int initialHides = recipe.inputCount() * cycles;

        // fails-if-idle: nothing to scrape yet. Give the goal several
        // LOOK_INTERVAL cycles (CrafterWorkGoal, 20 ticks) to have looked and
        // found nothing, then prove it did nothing.
        final long[] productionStart = {-1};
        helper.runAtTickTime(60, () -> {
            helper.assertTrue(countOf(chest, Items.LEATHER) == 0,
                "an empty chest must produce no leather at all");
            helper.assertTrue(garvar.getActivity() != SettlerActivity.WORK_SCRAPE,
                "with no hide to work, the tanner must not be playing the "
                    + "scrape motion over nothing, got " + garvar.getActivity());
            chest.setItem(0, new ItemStack(Items.RABBIT_HIDE, initialHides));
            productionStart[0] = helper.getLevel().getGameTime();
        });

        final boolean[] sawScrape = {false};
        helper.succeedWhen(() -> {
            if (productionStart[0] < 0) {
                helper.assertTrue(false, "waiting for hide to be delivered");
                return;
            }
            if (garvar.getActivity() == SettlerActivity.WORK_SCRAPE) {
                sawScrape[0] = true;
            }
            int hidesLeft = countOf(chest, Items.RABBIT_HIDE);
            int leather = countOf(chest, Items.LEATHER);
            helper.assertTrue(leather % recipe.outputCount() == 0,
                "leather must only ever appear in whole batches of "
                    + recipe.outputCount() + ", saw " + leather);
            int completedCycles = leather / recipe.outputCount();
            helper.assertTrue(
                hidesLeft == initialHides - completedCycles * recipe.inputCount(),
                "chest deltas must add up exactly: hides=" + hidesLeft
                    + " leather=" + leather + " cycles=" + completedCycles
                    + " initialHides=" + initialHides);

            long elapsed = helper.getLevel().getGameTime() - productionStart[0];
            helper.assertTrue(leather >= recipe.outputCount() * 2,
                "two full batches must be scraped back to back, no re-hire "
                    + "needed between them (act=" + garvar.getActivity()
                    + " hides=" + hidesLeft + " leather=" + leather
                    + " elapsed=" + elapsed + ")");
            helper.assertTrue(elapsed <= 2L * recipe.ticks() + 40,
                "two batches must finish inside a bounded tick budget, not "
                    + "stall indefinitely: elapsed=" + elapsed
                    + " budget=" + (2L * recipe.ticks() + 40));
            helper.assertTrue(sawScrape[0],
                "WORK_SCRAPE must actually have been observed while hide was "
                    + "being worked, not just declared by motionOf");
        });
    }
}
