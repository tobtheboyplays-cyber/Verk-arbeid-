package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.building.BuildingType;
import com.hearthstead.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The PLAYER'S path into a plaque, not the API's.
 *
 * <h2>The coverage gap this closes</h2>
 *
 * <p>Every existing test that fits a plan into a plaque calls
 * {@code plaque.insertPlan(level, stack)} directly. That proves the mechanic
 * and it proved it well — but it steps straight over the only route a player
 * actually has: holding a Build Plan and right-clicking the board, which
 * enters through {@code PlaqueBlock#useItemOn}. Everything between the click
 * and {@code insertPlan} — the client-side early return, the block-entity
 * lookup, the EMPTY-state guard, the {@code BuildPlanItem} type check, and
 * whether the stack is actually consumed from the player's hand — was
 * untested.
 *
 * <p>That gap has real weight: fitting the plan is step 9 of the eleven in
 * the quick-start the owner reads first, and the whole build loop is behind
 * it. The in-game playtest scenario does exercise the click, but through
 * simulated input, which has its own well-documented reliability problems
 * (KF-035, GLFW grab desync) — so when that step fails, the evidence cannot
 * tell "the game is broken" apart from "the click never landed". These tests
 * answer that question deterministically, with no window, no input layer and
 * no grab.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class PlaqueUseGameTests {

    private static BlockPos hangPlaque(GameTestHelper helper, BlockPos rel) {
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        GameTestFixtures.placePlaque(helper, rel);
        return rel;
    }

    /** A right-click on the plaque's own face, as the player's crosshair would land. */
    private static BlockHitResult hitOn(GameTestHelper helper, BlockPos rel) {
        BlockPos abs = helper.absolutePos(rel);
        return new BlockHitResult(
            new Vec3(abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5),
            Direction.NORTH, abs, false);
    }


    /**
     * Performs the right-click, tolerating the ONE failure a mock player
     * cannot avoid.
     *
     * <p>A successful fit opens the plaque screen, which sends the
     * {@code plaque_snapshot} payload. {@code makeMockServerPlayerInLevel}
     * builds a player with no real network connection, so that send throws
     * "Payload hearthstead:plaque_snapshot may not be sent to the client!"
     * — inside a GameTest server there is no client for it to reach.
     *
     * <p>This is narrow on purpose and it is NOT exception-silencing: the
     * throw is re-raised unless it is exactly that payload complaint, so any
     * real failure in the use path still fails the test loudly. And the
     * assertions that matter all run AFTER this returns, against the world:
     * the plan is only in the plaque, and only out of the hand, if the server
     * really did the work before it reached the screen.
     */
    private static void rightClick(GameTestHelper helper, ServerPlayer player, BlockPos rel) {
        try {
            helper.getLevel().getBlockState(helper.absolutePos(rel)).useItemOn(
                player.getItemInHand(InteractionHand.MAIN_HAND), helper.getLevel(), player,
                InteractionHand.MAIN_HAND, hitOn(helper, rel));
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            if (!message.contains("may not be sent to the client")) {
                throw e;
            }
            // Reached the screen-open, which means the fit itself already
            // happened. The assertions below check that against the world.
        }
    }

    /**
     * The headline: a player holding a stamped Build Plan and right-clicking a
     * blank plaque fits the plan, and the plaque stops being EMPTY.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "plaque_use_right_clicking_a_blank_plaque_with_a_plan_fits_it")
    public void rightClickingABlankPlaqueWithAPlanFitsIt(GameTestHelper helper) {
        BlockPos rel = hangPlaque(helper, new BlockPos(2, 2, 2));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        helper.assertTrue(
            helper.getLevel().getBlockEntity(helper.absolutePos(rel))
                instanceof PlaqueBlockEntity be && be.state() == PlaqueState.EMPTY,
            "setup: a freshly hung plaque must start EMPTY");

        ItemStack plan = PlaqueItemData.stamped(
            new ItemStack(ModItems.BUILD_PLAN.get()), BuildingType.LUMBER_CAMP);
        player.setItemInHand(InteractionHand.MAIN_HAND, plan);

        rightClick(helper, player, rel);

        helper.assertTrue(
            helper.getLevel().getBlockEntity(helper.absolutePos(rel))
                instanceof PlaqueBlockEntity be && be.state() != PlaqueState.EMPTY,
            "right-clicking a blank plaque while holding a stamped Build Plan must fit the "
                + "plan -- this is step 9 of the quick-start and the whole build loop is "
                + "behind it");
        helper.succeed();
    }

    /**
     * The plan must be a real cost: fitting it takes the item out of the
     * player's hand. A plaque that accepts a plan and hands it back would let
     * one sheet of paper found every building in the settlement.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "plaque_use_fitting_a_plan_consumes_it_from_the_players_hand")
    public void fittingAPlanConsumesItFromThePlayersHand(GameTestHelper helper) {
        BlockPos rel = hangPlaque(helper, new BlockPos(2, 2, 2));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        // Survival abilities, explicitly. A mock player comes up with
        // instabuild set, and PlaqueBlock#useItemOn deliberately skips the
        // shrink for a creative player -- correctly, since creative never
        // consumes. Testing the default would therefore have asserted the
        // creative rule while claiming to test the survival one, and passed
        // for the wrong reason. This is the survival path, which is the one
        // the cost argument below is about.
        player.getAbilities().instabuild = false;
        player.onUpdateAbilities();
        player.setItemInHand(InteractionHand.MAIN_HAND, PlaqueItemData.stamped(
            new ItemStack(ModItems.BUILD_PLAN.get()), BuildingType.LUMBER_CAMP));

        rightClick(helper, player, rel);

        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(),
            "fitting a Build Plan must consume it -- otherwise one sheet of paper founds "
                + "every building in the settlement; hand still holds "
                + player.getItemInHand(InteractionHand.MAIN_HAND));
        helper.succeed();
    }

    /**
     * An ordinary item is not a key. Right-clicking with something that is not
     * a Build Plan must leave the plaque blank rather than half-arming it.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "plaque_use_an_ordinary_item_does_not_arm_a_plaque")
    public void anOrdinaryItemDoesNotArmAPlaque(GameTestHelper helper) {
        BlockPos rel = hangPlaque(helper, new BlockPos(2, 2, 2));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setItemInHand(InteractionHand.MAIN_HAND,
            new ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 4));

        rightClick(helper, player, rel);

        helper.assertTrue(
            helper.getLevel().getBlockEntity(helper.absolutePos(rel))
                instanceof PlaqueBlockEntity be && be.state() == PlaqueState.EMPTY,
            "a plaque must stay EMPTY when right-clicked with anything that is not a Build "
                + "Plan -- no plaque, no building, and no half-armed middle state");
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).getCount() == 4,
            "and it must not eat the item it refused");
        helper.succeed();
    }
}
