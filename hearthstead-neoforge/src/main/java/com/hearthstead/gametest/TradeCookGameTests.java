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
 * VISUAL-2 — the cook's production loop, end to end.
 *
 * <p>Mirrors {@code TradeMasonGameTests#aHiredMasonChiselsStoneIntoBricks}: a
 * hired tradesperson standing in their own building, working from their own
 * chests alone (D-007), with the new signature motion this trade was wired
 * up with in {@link Employment#motionOf} actually observed doing the work,
 * not just declared. See {@code EmploymentGameTests}' helper Javadoc for why
 * the settlement is registered through {@code SettlementSavedData} rather
 * than kept as a bare object, and why the radius stays small (6): the arena
 * floor is shared with concurrently running tests, and a generous radius
 * answers for a neighbour's hearth.
 *
 * <p>The building is a bare {@link Building} record, exactly as
 * {@code EmploymentGameTests} builds its crafters — deliberately bypassing
 * the plaque survey (which would additionally require a real furnace/smoker,
 * cauldron, two storage blocks, a door and two lights — the KITCHEN
 * requirements in {@link BuildingType#KITCHEN}), because {@link Production}
 * only ever reads a building's own containers and this test's job is the
 * recipe loop, not the survey that already has its own coverage.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeCookGameTests {

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    /**
     * A settlement the entity layer can actually find.
     *
     * <p>Registered with {@link com.hearthstead.settlement.SettlementSavedData},
     * because {@code settler.settlement()} resolves by id through the manager:
     * a bare Settlement object is invisible to every goal, and the symptom is
     * a settler who simply stands there with no error anywhere. Radius kept
     * small (6) so this test's settlement cannot hijack a neighbouring
     * arena's hearth (GameTest arenas sit close together and
     * SettlementManager.at() resolves by radius).
     */
    private static Settlement settlement(GameTestHelper helper) {
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Kjokkenvik",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static Building building(GameTestHelper helper, Settlement s,
                                     BuildingType type, int x, int z) {
        // Delegates to the one place that places the plaque a building
        // needs to survive BuildingManager's sweep -- see GameTestFixtures
        // (KF-021 / FLAKE-2, 2026-08-26).
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
     * The whole cook loop, end to end.
     *
     * <p>First an honest negative: with no potatoes in the chest, a hired
     * cook at their post must sit idle rather than fake the clip — this is
     * what stops a trade's motion from becoming decoration playing over
     * nothing (fails-if-idle). Then potatoes appear, and inside a bounded
     * number of ticks the cook stirs them into baked potatoes — WORK_STIR
     * (this trade's newly wired signature motion, and the thing that proves
     * {@link Employment#motionOf}'s new COOK -> WORK_STIR mapping is actually
     * wired into {@code CrafterWorkGoal} rather than merely declared in the
     * switch) is actually observed doing it, chest deltas match the recipe's
     * own numbers exactly (one potato in for one baked potato out, so the
     * count is conserved as well as correct), and a second batch runs
     * straight after the first with no re-hire, proving this is a loop and
     * not a one-shot.
     */
    @GameTest(batch = "trade_cook", template = "empty16", timeoutTicks = 900)
    public void aHiredCookStirsPotatoesIntoBakedPotatoes(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building kitchen = building(helper, s, BuildingType.KITCHEN, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        Container chest = containerAt(helper, new BlockPos(5, 1, 4));
        helper.assertTrue(chest != null, "the arena chest should be a container");

        SettlerEntity kokk = settler(helper, s, "Kokk", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, kitchen, kokk).ok(),
            "a kitchen must be able to take a cook");
        helper.assertTrue(kokk.getProfession() == Profession.COOK,
            "hired into a kitchen, they cook");
        helper.assertTrue(Employment.motionOf(BuildingType.KITCHEN) == SettlerActivity.WORK_STIR,
            "the cook's signature motion must be the newly wired WORK_STIR");

        helper.getLevel().setDayTime(3000); // mid-morning: working hours

        Production.Recipe recipe = recipeById(BuildingType.KITCHEN, "baked_potato");
        helper.assertTrue(recipe.inputCount() == recipe.outputCount(),
            "this test's conservation check assumes a 1:1 exchange rate, "
                + "got " + recipe.inputCount() + " -> " + recipe.outputCount());
        int cycles = 3;
        int initialPotato = recipe.inputCount() * cycles;

        // fails-if-idle: nothing to stir yet. Give the goal several
        // LOOK_INTERVAL cycles (CrafterWorkGoal, 20 ticks) to have looked and
        // found nothing, then prove it did nothing.
        final long[] productionStart = {-1};
        helper.runAtTickTime(60, () -> {
            helper.assertTrue(countOf(chest, Items.BAKED_POTATO) == 0,
                "an empty chest must produce no baked potatoes at all");
            helper.assertTrue(kokk.getActivity() != SettlerActivity.WORK_STIR,
                "with nothing to cook, the cook must not be playing the stir "
                    + "motion over nothing, got " + kokk.getActivity());
            chest.setItem(0, new ItemStack(Items.POTATO, initialPotato));
            productionStart[0] = helper.getLevel().getGameTime();
        });

        final boolean[] sawStir = {false};
        helper.succeedWhen(() -> {
            if (productionStart[0] < 0) {
                helper.assertTrue(false, "waiting for potatoes to be delivered");
                return;
            }
            if (kokk.getActivity() == SettlerActivity.WORK_STIR) {
                sawStir[0] = true;
            }
            int potatoLeft = countOf(chest, Items.POTATO);
            int baked = countOf(chest, Items.BAKED_POTATO);
            helper.assertTrue(baked % recipe.outputCount() == 0,
                "baked potatoes must only ever appear in whole batches of "
                    + recipe.outputCount() + ", saw " + baked);
            int completedCycles = baked / recipe.outputCount();
            helper.assertTrue(
                potatoLeft == initialPotato - completedCycles * recipe.inputCount(),
                "chest deltas must add up exactly: potato=" + potatoLeft
                    + " baked=" + baked + " cycles=" + completedCycles
                    + " initialPotato=" + initialPotato);
            helper.assertTrue(potatoLeft + baked == initialPotato,
                "count must be conserved 1:1: potato+baked=" + (potatoLeft + baked)
                    + " vs initial=" + initialPotato);

            long elapsed = helper.getLevel().getGameTime() - productionStart[0];
            helper.assertTrue(baked >= recipe.outputCount() * 2,
                "two full batches must be cooked back to back, no re-hire "
                    + "needed between them (act=" + kokk.getActivity()
                    + " potato=" + potatoLeft + " baked=" + baked
                    + " elapsed=" + elapsed + ")");
            helper.assertTrue(elapsed <= 2L * recipe.ticks() + 40,
                "two batches must finish inside a bounded tick budget, not "
                    + "stall indefinitely: elapsed=" + elapsed
                    + " budget=" + (2L * recipe.ticks() + 40));
            helper.assertTrue(sawStir[0],
                "WORK_STIR must actually have been observed while potatoes "
                    + "were being cooked, not just declared by motionOf");
        });
    }
}
