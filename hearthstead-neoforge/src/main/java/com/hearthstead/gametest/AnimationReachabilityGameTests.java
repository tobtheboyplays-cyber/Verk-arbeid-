package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * One-shot animations must be BROADCAST, never started in place.
 *
 * <h2>The bug this exists to prevent</h2>
 *
 * <p>An {@code AnimationState} only means anything on the CLIENT: the model's
 * {@code animate(entity.someState, CLIP, ...)} call runs in {@code setupAnim},
 * which is client-only. A trigger method called from a goal runs on the
 * SERVER. So {@code someState.start(tickCount)} in a trigger starts the state
 * on the server's copy of the entity -- the copy no renderer will ever look
 * at -- and the clip never plays for anybody, silently, with no error and no
 * failing test.
 *
 * <p>Three clips shipped that way and were found on 2026-08-26:
 * GATHER_LOG (the lumberjack's stoop), LEAP_STRIKE (the sergeant's leap), and
 * -- by the same reasoning one level along, reading server-only state from
 * the model instead of starting server-only state from a goal -- the raider's
 * SPRINT. Authored keyframes, reviewed and committed, that no player could
 * ever see. The lumberjack's was the worst: it also parked him in an activity
 * with no clip, so he stood motionless through most of every tree.
 *
 * <p>The correct idiom is {@code level().broadcastEntityEvent(this, EV_X)},
 * with the state started in {@code handleEntityEvent}, which runs on the
 * client. {@code triggerPickup()} always did it that way and is the control
 * in the test below.
 *
 * <h2>What is actually asserted, and why it is a real check</h2>
 *
 * <p>A GameTest server has no client, so this cannot watch a clip play. What
 * it CAN see is the tell that distinguishes the two idioms: after a correct
 * trigger, the state is NOT started on the server copy, because the start
 * happened in a packet handler that only clients run. After the broken
 * idiom it IS started on the server. That asymmetry is exactly the bug, so
 * asserting on it catches a regression the moment someone writes
 * {@code state.start()} in a trigger again.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class AnimationReachabilityGameTests {

    private static SettlerEntity spawn(GameTestHelper helper) {
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(2, 1, 2));
        settler.setSettlerName("Mime");
        return settler;
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "anim_reach_one_shot_triggers_broadcast_instead_of_starting_in_place")
    public void oneShotTriggersBroadcastInsteadOfStartingInPlace(GameTestHelper helper) {
        SettlerEntity settler = spawn(helper);

        // The control: pickup has always used the broadcast idiom, so if this
        // assertion ever fails the test's own premise is wrong and every
        // verdict below is meaningless -- check this first, deliberately.
        settler.triggerPickup();
        helper.assertTrue(!settler.pickupState.isStarted(),
            "premise check: triggerPickup uses broadcastEntityEvent, so the SERVER copy's "
                + "pickupState must stay unstarted. If this fails, this test is measuring "
                + "the wrong thing, not finding a bug");

        settler.triggerGatherLog();
        helper.assertTrue(!settler.gatherState.isStarted(),
            "triggerGatherLog must broadcast EV_GATHER_LOG, not start gatherState here: a "
                + "state started on the server copy is a clip no client ever plays, and the "
                + "lumberjack's stoop shipped dead exactly that way");

        settler.triggerLeapStrike();
        helper.assertTrue(!settler.leapState.isStarted(),
            "triggerLeapStrike must broadcast EV_LEAP_STRIKE, not start leapState here: the "
                + "guard's authored leap shipped dead exactly that way and he played the "
                + "plain walk cycle through the air instead");

        helper.succeed();
    }

    /**
     * The lumberjack's stoop must not cost him his working animation.
     *
     * <p>The original {@code triggerGatherLog} also set the activity to
     * GATHERING_LOG, which matches no clip gate anywhere and was cleared only
     * inside the client-only {@code setupAnimationStates()}. On the server it
     * was therefore permanent: one stoop and the woodcutter stood in the bare
     * rig for the rest of the tree.
     */
    @GameTest(template = "empty16", timeoutTicks = 100, batch = "anim_reach_gathering_a_log_does_not_strand_the_lumberjack_out_of_his_work_clip")
    public void gatheringALogDoesNotStrandTheLumberjackOutOfHisWorkClip(GameTestHelper helper) {
        SettlerEntity settler = spawn(helper);
        settler.setActivity(com.hearthstead.entity.SettlerActivity.WORK_CHOP);

        settler.triggerGatherLog();

        helper.assertTrue(settler.getActivity() == com.hearthstead.entity.SettlerActivity.WORK_CHOP,
            "after stooping for a log the lumberjack must still be WORK_CHOP -- he is still "
                + "chopping, and any activity with no clip gate leaves him animating nothing "
                + "at all until something else happens to set one; got "
                + settler.getActivity());
        helper.succeed();
    }
}
