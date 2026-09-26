package com.hearthstead.conversation;

import com.hearthstead.Hearthstead;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.locale.Language;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Survival QA: persuasion lines must match the real town. In a day-0 town
 * (no guards, no barracks or watchtower) no visible reply of any visitor,
 * event or parley talk may claim walls, guards or towers.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class HonestPersuasionGameTests {
    private static final List<String> GRAPHS = List.of("hearthstead:brute_toll", "hearthstead:raid_parley",
        "hearthstead:refugees", "hearthstead:minstrels", "hearthstead:peddler", "hearthstead:traveller");
    private static final List<String> CLAIMS = List.of("wall", "guard", "tower", "palisade", "spear");

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "talk_honest_persuasion")
    public void aBareTownNeverThreatensWithWallsOrGuards(GameTestHelper helper) {
        ConversationConfig.overrideForTests(true, null);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        Settlement bare = new Settlement(UUID.randomUUID(), "Barehold", helper.absolutePos(new BlockPos(8, 1, 8)));
        bare.radius = 10;
        SettlementSavedData.get(helper.getLevel()).settlements.put(bare.id, bare);
        helper.assertTrue(!TownFactsLive.of(bare).defended(), "fixture: a bare town is not defended");
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
            new com.mojang.authlib.GameProfile(UUID.randomUUID(), "honest-talker"), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
            cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        BlockPos at = helper.absolutePos(new BlockPos(6, 1, 8));
        player.teleportTo(helper.getLevel(), at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, 0.0F, 0.0F);
        List<String> lies = new ArrayList<>();
        int checked = 0;
        try {
            Villager speaker = helper.spawn(EntityType.VILLAGER, new BlockPos(8, 1, 8));
            speaker.setNoAi(true);
            for (String graphId : GRAPHS) {
                if (ConversationGraphs.get(graphId) == null) continue;
                ConversationService.closeFor(player, null);
                ConversationService.bind(speaker, graphId, new SpeakerProfile(UUID.randomUUID(), "Test", "", "visitor"),
                    java.util.Map.of("toll", 16, "food", 30, "tribute", 15, "victories", 0, "defeats", 0), bare.id);
                if (!ConversationService.openBound(player, speaker, false)) continue;
                checked++;
                for (String optionKey : ConversationService.visibleOptionTextsForTest(player)) {
                    String english = Language.getInstance().getOrDefault(optionKey).toLowerCase(Locale.ROOT);
                    for (String claim : CLAIMS) {
                        if (english.contains(claim)) lies.add(graphId + " -> \"" + english + "\"");
                    }
                }
            }
        } finally {
            ConversationService.closeFor(player, null);
            helper.getLevel().getServer().getPlayerList().remove(player);
            SettlementSavedData.get(helper.getLevel()).settlements.remove(bare.id);
            ConversationConfig.overrideForTests(null, null);
        }
        helper.assertTrue(checked >= 2, "at least the parley and one visitor talk were checked, got " + checked);
        helper.assertTrue(lies.isEmpty(), "a bare town is offered defence claims: " + lies);
        helper.succeed();
    }
}
