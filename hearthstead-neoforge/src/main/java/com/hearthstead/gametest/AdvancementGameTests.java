package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Coverage for the first-steps advancement chain
 * ({@code data/hearthstead/advancement/hearthstead/}), scoped to exactly
 * what that chain actually ships.
 *
 * <h2>Why only one node is tested here</h2>
 *
 * <p>The chain the owner-critic asked for was six milestones long: hang a
 * plaque, a room gets accepted, hire a settler, goods reach the hearth,
 * recruit a traveler, survive a raid. Only the first two — placing a Hearth
 * (the chain's {@code root}) and placing a Plaque
 * ({@code hang_first_plaque}) — are things vanilla's own advancement
 * criteria observe for free: both items are plain {@code BlockItem}s, and
 * {@code BlockItem.place} already calls {@code CriteriaTriggers.PLACED_BLOCK}
 * for any successful placement, with no mod code of ours in the path at all.
 *
 * <p>The other four milestones are pure server-internal state transitions —
 * {@code RoomScanner} accepting a room, {@code Employment.hire} from a
 * screen button, a courier's delivery into a chest, {@code
 * SettlementManager}'s own traveler conversion, a raid director's win
 * condition — that no vanilla criterion trigger observes, and wiring one
 * would mean adding trigger calls inside files this worker does not own
 * ({@code PlaqueBlockEntity}/{@code RoomScanner}, {@code Employment}, {@code
 * HearthBlockEntity}, {@code SettlementManager}, the raid director). Rather
 * than invent a criterion that never fires, or approximate one dishonestly
 * (an item pickup does not mean a hire happened), those four are left
 * un-shipped; see the fix-worker report for the exact follow-up spec.
 *
 * <p>So: this file tests the one node whose criterion the task explicitly
 * named and that is honestly, mechanically real —
 * {@code hang_first_plaque}. {@code root}'s own criterion (placing a Hearth)
 * is the identical mechanism one block earlier and is not re-proven here.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class AdvancementGameTests {

    /**
     * Places a real Plaque item through the real item-placement path — not
     * {@code helper.setBlock}, which would prove nothing about the
     * advancement, since {@code CriteriaTriggers.PLACED_BLOCK} only fires
     * from {@code BlockItem.place} itself. Mirrors the mock-player pattern
     * {@code HearthsteadGameTests}/{@code SummonsGameTests} already use
     * ({@code helper.makeMockServerPlayerInLevel()}), and tolerates the same
     * harness-only gap {@code HearthsteadGameTests
     * .useBlockTolerateNoRealConnection} documents: a mock player's
     * {@code Connection} never completes NeoForge's real handshake, so the
     * first server-to-client payload this mod ever sends it can be refused.
     * The block placement and the advancement grant are both plain
     * server-side state that already happened before any packet send is
     * attempted, so this only silences that expected failure.
     */
    @GameTest(batch = "advancement", template = "empty16", timeoutTicks = 100)
    public void hangingAPlaqueGrantsTheFirstStepsAdvancement(GameTestHelper helper) {
        BlockPos wallRel = new BlockPos(2, 1, 2);
        helper.setBlock(wallRel, Blocks.STONE_BRICKS);
        BlockPos wallAbs = helper.absolutePos(wallRel);

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        // Stand the mock player at the wall before using the item: a mock
        // spawns at the world origin, and BlockItem.place refuses a placement
        // the player could not physically reach. Without this the item's own
        // path never runs, so PLACED_BLOCK never fires and the test fails on
        // its own precondition rather than on the advancement.
        BlockPos standRel = wallRel.relative(Direction.NORTH, 2);
        player.setPos(helper.absolutePos(standRel).getX() + 0.5,
            helper.absolutePos(standRel).getY(),
            helper.absolutePos(standRel).getZ() + 0.5);
        ItemStack plaque = new ItemStack(ModItems.PLAQUE.get());
        BlockHitResult hit = new BlockHitResult(
            Vec3.atCenterOf(wallAbs), Direction.NORTH, wallAbs, false);
        UseOnContext ctx = new UseOnContext(
            helper.getLevel(), player, InteractionHand.MAIN_HAND, plaque, hit);

        // TEMP DIAGNOSTIC (strip before landing): walk BlockItem.place's own
        // gates by hand so a failed placement says WHICH gate refused it,
        // instead of just "the block isn't there". Grep gametest.log for
        // "[HS-DIAG]".
        net.minecraft.world.item.context.BlockPlaceContext bpc =
            new net.minecraft.world.item.context.BlockPlaceContext(ctx);
        net.minecraft.world.level.block.state.BlockState placementState =
            ModBlocks.PLAQUE.get().getStateForPlacement(bpc);
        com.hearthstead.Hearthstead.LOGGER.info(
            "[HS-DIAG] clickedPos={} replaceClicked={} canPlace={} placementState={}"
                + " wallBlockState={} wallAbs={} playerPos={} playerLevel={} arenaLevel={}",
            bpc.getClickedPos(), bpc.replacingClickedOnBlock(), bpc.canPlace(),
            placementState, helper.getLevel().getBlockState(wallAbs), wallAbs,
            player.position(), player.level().dimension().location(),
            helper.getLevel().dimension().location());
        if (placementState != null) {
            com.hearthstead.Hearthstead.LOGGER.info(
                "[HS-DIAG] canSurvive={} isUnobstructed={}",
                placementState.canSurvive(helper.getLevel(), bpc.getClickedPos()),
                helper.getLevel().isUnobstructed(placementState, bpc.getClickedPos(),
                    net.minecraft.world.phys.shapes.CollisionContext.of(player)));
        }

        net.minecraft.world.InteractionResult placeResult;
        try {
            placeResult = plaque.getItem().useOn(ctx);
        } catch (UnsupportedOperationException e) {
            placeResult = null;
            if (e.getMessage() == null || !e.getMessage().contains("may not be sent")) {
                throw e;
            }
        }
        com.hearthstead.Hearthstead.LOGGER.info(
            "[HS-DIAG] useOn result={} wallBlockStateAfter={} placedBlockState={}",
            placeResult, helper.getLevel().getBlockState(wallAbs),
            helper.getLevel().getBlockState(wallAbs.relative(Direction.NORTH)));

        BlockPos placedRel = wallRel.relative(Direction.NORTH);
        helper.assertBlockState(placedRel,
            state -> state.is(ModBlocks.PLAQUE.get()),
            () -> "the plaque item must actually place its block for this test to mean anything");

        AdvancementHolder holder = helper.getLevel().getServer().getAdvancements()
            .get(Hearthstead.id("hearthstead/hang_first_plaque"));
        helper.assertTrue(holder != null,
            "hearthstead:hearthstead/hang_first_plaque must be a loaded advancement");

        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
        helper.assertTrue(progress.isDone(),
            "placing a plaque via the real item-placement path must grant hang_first_plaque, "
                + "remaining=" + progress.getRemainingCriteria());

        helper.succeed();
    }
}
