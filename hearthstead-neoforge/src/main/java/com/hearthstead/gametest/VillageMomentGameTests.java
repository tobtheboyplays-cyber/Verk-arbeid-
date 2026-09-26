package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.VillageSocial;
import com.hearthstead.entity.ai.WorkCompanionGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The first living-village slice: an optional, cancellable conversation. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class VillageMomentGameTests {
    private static final class ReloadProbe extends SettlerEntity {
        ReloadProbe(ServerLevel level) {
            super(ModEntities.SETTLER.get(), level);
        }

        CompoundTag saveForReload() {
            CompoundTag tag = new CompoundTag();
            addAdditionalSaveData(tag);
            return tag;
        }

        void loadForReload(CompoundTag tag) {
            readAdditionalSaveData(tag);
        }
    }

    private static Settlement settlement(GameTestHelper helper) {
        Settlement settlement = new Settlement(UUID.randomUUID(), "Hearthmere",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 8;
        SettlementSavedData.get(helper.getLevel()).settlements.put(settlement.id, settlement);
        return settlement;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement settlement, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, 1, z));
        settler.bindTo(settlement.id, settlement.center);
        settler.setNoAi(true); // The test drives the real goal instance deliberately.
        return settler;
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    @GameTest(batch = "village_moments", template = "empty16", timeoutTicks = 100)
    public void idleNeighboursCanTalkThenYieldCleanlyToAnUrgentNeed(GameTestHelper helper) {
        floor(helper);
        Settlement settlement = settlement(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(helper.absolutePos(new BlockPos(8, 1, 8)).getCenter());
        SettlerEntity first = settler(helper, settlement, 5, 8);
        SettlerEntity second = settler(helper, settlement, 9, 8);
        SettlerEntity onlooker = settler(helper, settlement, 13, 8);
        WorkCompanionGoal firstGoal = new WorkCompanionGoal(first);
        WorkCompanionGoal secondGoal = new WorkCompanionGoal(second);
        WorkCompanionGoal onlookerGoal = new WorkCompanionGoal(onlooker);

        helper.assertTrue(firstGoal.canUse(), "an idle nearby civilian should start one sparse moment");
        firstGoal.start();
        helper.assertTrue(secondGoal.canUse(), "the selected companion should join the same moment");
        secondGoal.start();
        firstGoal.tick();
        secondGoal.tick();
        helper.assertTrue(first.getActivity() == SettlerActivity.SOCIALIZING
                && second.getActivity() == SettlerActivity.SOCIALIZING,
            "both participants must visibly enter the shared social activity");
        helper.assertTrue(first.villageSocialMode() == VillageSocial.WELCOME
                && second.villageSocialMode() == VillageSocial.LISTEN,
            "the starter waves while their companion uses the distinct listening pose");
        helper.assertTrue(!onlookerGoal.canUse()
                && first.getActivity() == SettlerActivity.SOCIALIZING
                && second.getActivity() == SettlerActivity.SOCIALIZING,
            "a third idle settler must not cancel the selected pair's running moment");

        second.setHunger(20.0F);
        helper.assertTrue(!secondGoal.canContinueToUse(),
            "a real urgent need must interrupt the optional scene immediately");
        secondGoal.stop();
        helper.assertTrue(first.getActivity() == SettlerActivity.IDLE
                && second.getActivity() == SettlerActivity.IDLE
                && first.villageSocialMode() == VillageSocial.NONE
                && second.villageSocialMode() == VillageSocial.NONE,
            "interruption must return both settlers without holding a route, item or social cue");
        helper.succeed();
    }

    @GameTest(batch = "village_moments", template = "empty16", timeoutTicks = 100)
    public void aHeldItemStopsAnActiveConversation(GameTestHelper helper) {
        floor(helper);
        Settlement settlement = settlement(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(helper.absolutePos(new BlockPos(8, 1, 8)).getCenter());
        SettlerEntity first = settler(helper, settlement, 5, 8);
        SettlerEntity second = settler(helper, settlement, 9, 8);
        WorkCompanionGoal firstGoal = new WorkCompanionGoal(first);
        WorkCompanionGoal secondGoal = new WorkCompanionGoal(second);
        helper.assertTrue(firstGoal.canUse(), "fixture must create one social pair");
        firstGoal.start();
        helper.assertTrue(secondGoal.canUse(), "fixture companion must join the same pair");
        secondGoal.start();
        firstGoal.tick();
        secondGoal.tick();
        second.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        helper.assertTrue(!secondGoal.canContinueToUse(),
            "a participant receiving a real held item must release MOVE/LOOK immediately");
        secondGoal.stop();
        helper.assertTrue(first.getActivity() == SettlerActivity.IDLE
                && second.getActivity() == SettlerActivity.IDLE,
            "the paired scene must clear for both participants after new work ownership");
        helper.succeed();
    }

    @GameTest(batch = "village_moments", template = "empty16", timeoutTicks = 100)
    public void aReloadClearsTheEphemeralSocialCueWithoutARecoveryTask(GameTestHelper helper) {
        ReloadProbe original = new ReloadProbe(helper.getLevel());
        original.setActivity(SettlerActivity.SOCIALIZING);
        original.setVillageSocial(VillageSocial.CHAT, helper.getLevel().getGameTime());
        ReloadProbe restored = new ReloadProbe(helper.getLevel());
        restored.loadForReload(original.saveForReload());
        helper.assertTrue(restored.getActivity() == SettlerActivity.IDLE
                && restored.villageSocialMode() == VillageSocial.NONE,
            "social activity and its cue are intentionally runtime-only across reload");
        helper.succeed();
    }

    @GameTest(batch = "village_moments", template = "empty16", timeoutTicks = 100)
    public void workActivityCannotStartACompanionMoment(GameTestHelper helper) {
        floor(helper);
        Settlement settlement = settlement(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(helper.absolutePos(new BlockPos(8, 1, 8)).getCenter());
        SettlerEntity worker = settler(helper, settlement, 5, 8);
        settler(helper, settlement, 9, 8);
        worker.setActivity(SettlerActivity.WORK_FARM);

        helper.assertTrue(!new WorkCompanionGoal(worker).canUse(),
            "a real work activity must keep priority over a village moment");
        helper.succeed();
    }
}
