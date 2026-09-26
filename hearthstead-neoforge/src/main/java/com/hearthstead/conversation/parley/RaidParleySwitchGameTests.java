package com.hearthstead.conversation.parley;

import com.hearthstead.Hearthstead;
import com.hearthstead.conversation.ConversationConfig;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Codex T14: switching conversations or the raid parley off mid-parley
 * releases the held band and closes a duel, with no payout and no duel score;
 * the raid then goes on as an ordinary raid.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaidParleySwitchGameTests {

    private static Settlement arena(GameTestHelper helper, String name) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), name, helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 8;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        // Control state: conversations and the parley explicitly ON (the
        // GameTest server's config default is not what these tests measure).
        ConversationConfig.overrideForTests(true, true);
        return settlement;
    }

    private static RaiderEntity raider(GameTestHelper helper, Settlement s, BlockPos at, boolean captain) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), at);
        raider.setVariant(RaiderEntity.Variant.SKIRMISHER);
        raider.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, captain);
        return raider;
    }

    private static void restore(GameTestHelper helper, Settlement s) {
        ConversationConfig.overrideForTests(null, null);
        RaidParley.clearDuelForTests(s.id);
        SettlementSavedData.get(helper.getLevel()).settlements.remove(s.id);
    }

    @GameTest(template = "empty16", batch = "bughunt_parley_switch", timeoutTicks = 60)
    public void aWaitingParleyReleasesItsBandWhenSwitchedOff(GameTestHelper helper) {
        Settlement s = arena(helper, "Holdfast");
        RaiderEntity captain = raider(helper, s, new BlockPos(8, 1, 12), true);
        RaiderEntity left = raider(helper, s, new BlockPos(6, 1, 12), false);
        RaiderEntity right = raider(helper, s, new BlockPos(10, 1, 12), false);
        List<RaiderEntity> band = List.of(captain, left, right);
        RaidParley.startWaitingForTests(helper.getLevel(), s.id, captain, band);
        helper.assertTrue(RaidParley.holding(s.id) && band.stream().allMatch(RaiderEntity::isNoAi),
            "control: the band holds for the parley");
        ConversationConfig.overrideForTests(true, false);
        helper.runAfterDelay(3, () -> {
            try {
                helper.assertFalse(RaidParley.holding(s.id), "switched off: the parley is gone");
                for (RaiderEntity raider : band) {
                    helper.assertTrue(raider.isAlive() && !raider.isNoAi()
                            && !raider.getPersistentData().getBoolean(RaidParley.HOLD_TAG),
                        "every held raider is released to fight on: " + raider.getUUID());
                }
                helper.assertTrue(RaidParley.duelOutcomesForTests(s.id).isEmpty(), "nothing was scored");
            } finally {
                restore(helper, s);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", batch = "bughunt_parley_switch", timeoutTicks = 60)
    public void aDuelClosesUnscoredWhenSwitchedOff(GameTestHelper helper) {
        Settlement s = arena(helper, "Duelmoor");
        RaiderEntity captain = raider(helper, s, new BlockPos(8, 1, 10), true);
        RaiderEntity bystander = raider(helper, s, new BlockPos(12, 1, 12), false);
        bystander.setNoAi(true);
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        BlockPos at = helper.absolutePos(new BlockPos(8, 1, 6));
        player.teleportTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
        RaidParley.startDuelForTests(helper.getLevel(), s.id, captain, player);
        var fromBystander = player.damageSources().mobAttack(bystander);
        helper.assertTrue(RaidParley.duelActiveForTests(s.id)
                && RaidParley.blockDamage(player, fromBystander, 1.0F),
            "control: with the parley on, nobody but the captain may strike the duelist");
        ConversationConfig.overrideForTests(true, false);
        try {
            helper.assertFalse(RaidParley.blockDamage(player, fromBystander, 1.0F),
                "switched off: the duel's fairness rules no longer apply");
            helper.assertFalse(RaidParley.yieldOnDamage(captain,
                    captain.damageSources().playerAttack(player), 1000.0F),
                "switched off: a blow never scores the duel");
        } catch (RuntimeException | AssertionError e) {
            restore(helper, s);
            throw e;
        }
        helper.runAfterDelay(3, () -> {
            try {
                helper.assertFalse(RaidParley.duelActiveForTests(s.id), "the duel is closed");
                helper.assertTrue(RaidParley.duelOutcomesForTests(s.id).isEmpty(),
                    "no winner, no loser: nothing is paid or remembered");
                helper.assertTrue(captain.isAlive() && !captain.isNoAi(),
                    "the captain fights on as an ordinary raider");
            } finally {
                restore(helper, s);
            }
            helper.succeed();
        });
    }
}
