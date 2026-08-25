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
 * VISUAL-2 — the carpenter's production loop, end to end.
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
 * the plaque survey (which would additionally require two real crafting
 * tables, two storage blocks, a door and two lights — the CARPENTER
 * requirements in {@link BuildingType#CARPENTER}), because {@link Production}
 * only ever reads a building's own containers and this test's job is the
 * recipe loop, not the survey that already has its own coverage.
 *
 * <p>Only sticks go in the chest, never oak planks: {@code CARPENTER} has
 * THREE recipes ({@code sticks}: 2 planks -> 4 sticks; {@code barrel}: 7
 * planks -> 1 barrel; {@code ladder}: 7 sticks -> 3 ladders), and
 * {@link Production#ready} always runs the first one in the table whose
 * input count is met. Stocking planks would let {@code sticks} fire
 * (soonest-satisfied, listed first) and its own output would then cross the
 * {@code ladder} threshold after only two batches (4 sticks/batch), turning
 * a clean single-recipe loop into an unpredictable cascade the moment it did.
 * Stocking sticks alone sidesteps that entirely: {@code sticks} and
 * {@code barrel} both need planks (always zero here, so neither can ever
 * fire) and nothing in this table consumes a ladder, so however many batches
 * this test's tick budget lets complete, which recipe ran is never in doubt.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeCarpenterGameTests {

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
        Settlement s = new Settlement(UUID.randomUUID(), "Snekkerbakken",
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
     * The whole carpenter loop, end to end.
     *
     * <p>First an honest negative: with no sticks in the chest, a hired
     * carpenter at their post must sit idle rather than fake the clip — this
     * is what stops a trade's motion from becoming decoration playing over
     * nothing (fails-if-idle). Then sticks appear, and inside a bounded
     * number of ticks the carpenter planes them into ladders — WORK_PLANE
     * (this trade's newly wired signature motion, and the thing that proves
     * {@link Employment#motionOf}'s new CARPENTER -> WORK_PLANE mapping is
     * actually wired into {@code CrafterWorkGoal} rather than merely
     * declared in the switch) is actually observed doing it, chest deltas
     * match the recipe's own numbers exactly (seven sticks in for three
     * ladders out, so the count is conserved at that ratio, never more and
     * never fewer), and a second batch runs straight after the first with no
     * re-hire, proving this is a loop and not a one-shot.
     */
    @GameTest(batch = "trade_carpenter", template = "empty16", timeoutTicks = 900)
    public void aHiredCarpenterPlanesSticksIntoLadders(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building carpentry = building(helper, s, BuildingType.CARPENTER, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        Container chest = containerAt(helper, new BlockPos(5, 1, 4));
        helper.assertTrue(chest != null, "the arena chest should be a container");

        SettlerEntity snekker = settler(helper, s, "Snekker", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, carpentry, snekker).ok(),
            "a carpenter's shop must be able to take a carpenter");
        helper.assertTrue(snekker.getProfession() == Profession.CARPENTER,
            "hired into a carpenter's shop, they carpenter");
        helper.assertTrue(
            Employment.motionOf(BuildingType.CARPENTER) == SettlerActivity.WORK_PLANE,
            "the carpenter's signature motion must be the newly wired WORK_PLANE");

        helper.getLevel().setDayTime(3000); // mid-morning: working hours

        Production.Recipe recipe = recipeById(BuildingType.CARPENTER, "ladder");
        helper.assertTrue(recipe.input().test(new ItemStack(Items.STICK))
                && !recipe.input().test(new ItemStack(Items.OAK_PLANKS)),
            "this test relies on 'ladder' taking sticks, not planks, so that "
                + "stocking only sticks cannot also feed 'sticks' or 'barrel'");
        int cycles = 3;
        int initialSticks = recipe.inputCount() * cycles;

        // fails-if-idle: nothing to plane yet. Give the goal several
        // LOOK_INTERVAL cycles (CrafterWorkGoal, 20 ticks) to have looked and
        // found nothing, then prove it did nothing.
        final long[] productionStart = {-1};
        helper.runAtTickTime(60, () -> {
            helper.assertTrue(countOf(chest, Items.LADDER) == 0,
                "an empty chest must produce no ladders at all");
            helper.assertTrue(snekker.getActivity() != SettlerActivity.WORK_PLANE,
                "with nothing to plane, the carpenter must not be playing the "
                    + "plane motion over nothing, got " + snekker.getActivity());
            chest.setItem(0, new ItemStack(Items.STICK, initialSticks));
            productionStart[0] = helper.getLevel().getGameTime();
        });

        final boolean[] sawPlane = {false};
        helper.succeedWhen(() -> {
            if (productionStart[0] < 0) {
                helper.assertTrue(false, "waiting for sticks to be delivered");
                return;
            }
            if (snekker.getActivity() == SettlerActivity.WORK_PLANE) {
                sawPlane[0] = true;
            }
            int sticksLeft = countOf(chest, Items.STICK);
            int ladders = countOf(chest, Items.LADDER);
            int consumed = initialSticks - sticksLeft;
            if (ladders > 0) {
                helper.assertTrue(consumed > 0,
                    "ladders appeared without a single stick leaving the "
                        + "chest — that is duplication, not production");
                helper.assertTrue(
                    consumed % recipe.inputCount() == 0
                        && ladders == (consumed / recipe.inputCount()) * recipe.outputCount(),
                    "conservation broken: " + consumed + " stick(s) gone must "
                        + "leave exactly "
                        + ((consumed / recipe.inputCount()) * recipe.outputCount())
                        + " ladder(s) at this recipe's " + recipe.inputCount() + ":"
                        + recipe.outputCount() + " ratio, chest holds " + ladders);
            }
            helper.assertTrue(ladders % recipe.outputCount() == 0,
                "ladders must only ever appear in whole batches of "
                    + recipe.outputCount() + ", saw " + ladders);
            int completedCycles = ladders / recipe.outputCount();
            helper.assertTrue(
                sticksLeft == initialSticks - completedCycles * recipe.inputCount(),
                "chest deltas must add up exactly: sticks=" + sticksLeft
                    + " ladders=" + ladders + " cycles=" + completedCycles
                    + " initialSticks=" + initialSticks);

            long elapsed = helper.getLevel().getGameTime() - productionStart[0];
            helper.assertTrue(ladders >= recipe.outputCount() * 2,
                "two full batches must be planed back to back, no re-hire "
                    + "needed between them (act=" + snekker.getActivity()
                    + " sticks=" + sticksLeft + " ladders=" + ladders
                    + " elapsed=" + elapsed + ")");
            helper.assertTrue(elapsed <= 2L * recipe.ticks() + 40,
                "two batches must finish inside a bounded tick budget, not "
                    + "stall indefinitely: elapsed=" + elapsed
                    + " budget=" + (2L * recipe.ticks() + 40));
            helper.assertTrue(sawPlane[0],
                "WORK_PLANE must actually have been observed while sticks "
                    + "were being planed, not just declared by motionOf");
        });
    }
}
