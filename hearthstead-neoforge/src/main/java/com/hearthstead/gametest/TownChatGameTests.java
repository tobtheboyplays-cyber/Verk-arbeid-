package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.TownChat;
import com.hearthstead.settlement.TownChatConfig;
import com.hearthstead.settlement.raid.RaidBroadcast;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.List;
import java.util.UUID;

/**
 * Town chat (owner, 26 Sep): every kind of line reaches the settlement's
 * member exactly once and a non-member standing right beside the Banner not
 * at all. Membership comes from really using the Banner; the death line
 * comes from a real settler death; same-tick bursts fold into one line.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TownChatGameTests {

    private static final BlockPos BANNER = new BlockPos(8, 1, 8);

    private record Heard(UUID player, Component line) {
    }

    private record Fixture(Settlement settlement, HearthBlockEntity hearth, ServerPlayer member,
                           ServerPlayer stranger, List<Heard> heard,
                           java.util.function.BiConsumer<ServerPlayer, Component> tap) {
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper, BlockPos rel) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setGameMode(GameType.SURVIVAL);
        BlockPos at = helper.absolutePos(rel);
        player.setPos(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
        return player;
    }

    private static Fixture fixture(GameTestHelper helper, boolean join) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        helper.setBlock(BANNER, ModBlocks.HEARTH.get());
        BlockPos abs = helper.absolutePos(BANNER);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(abs);
        // A unique name: lines of other tests' towns never count here.
        Settlement s = new Settlement(UUID.randomUUID(), "Chatholm-" + UUID.randomUUID().toString().substring(0, 6), abs);
        s.radius = 8;
        SettlementSavedData.get(helper.getLevel()).settlements.put(s.id, s);
        hearth.bindSettlement(s.id);

        ServerPlayer member = player(helper, BANNER.west(2));
        ServerPlayer stranger = player(helper, BANNER.east(2));
        if (join) {
            // The real door: sneak-use the Banner (opens its menu, skips any Blessing offer).
            member.setShiftKeyDown(true);
            helper.getBlockState(BANNER).useWithoutItem(helper.getLevel(), member,
                new BlockHitResult(Vec3.atCenterOf(abs), Direction.WEST, abs, false));
            member.closeContainer();
            helper.assertTrue(s.members.contains(member.getUUID()), "using the Banner makes a member");
            helper.assertTrue(!s.members.contains(stranger.getUUID()), "standing beside it does not");
        }

        List<Heard> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        String town = s.name;
        java.util.function.BiConsumer<ServerPlayer, Component> tap = (to, line) -> {
            if (!line.getSiblings().isEmpty()
                && line.getSiblings().get(0).getContents() instanceof TranslatableContents prefix
                && prefix.getArgs().length > 0 && town.equals(String.valueOf(prefix.getArgs()[0]))
                && (to == member || to == stranger)) {
                heard.add(new Heard(to.getUUID(), line));
            }
        };
        TownChat.addTestTap(tap);
        return new Fixture(s, hearth, member, stranger, heard, tap);
    }

    private static void cleanup(GameTestHelper helper, Fixture f) {
        TownChat.removeTestTap(f.tap());
        TownChatConfig.overrideForTests(TownChat.Kind.DEATH, null);
        helper.setBlock(BANNER, Blocks.AIR);
        SettlementSavedData.get(helper.getLevel()).settlements.remove(f.settlement().id);
        helper.getLevel().getServer().getPlayerList().remove(f.member());
        helper.getLevel().getServer().getPlayerList().remove(f.stranger());
    }

    private static long count(Fixture f, ServerPlayer who, TownChat.Kind kind) {
        return f.heard().stream()
            .filter(h -> h.player().equals(who.getUUID()))
            .filter(h -> h.line().getStyle().getColor() != null
                && h.line().getStyle().getColor().getValue() == kind.color().getColor())
            .count();
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s, String name) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(10, 1, 10));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "town_chat")
    public void everyKindReachesTheMemberOnceAndNeverAStranger(GameTestHelper helper) {
        Fixture f = fixture(helper, true);
        Settlement s = f.settlement();
        var level = helper.getLevel();

        // RAID: the warning headline, sent twice in one tick, is heard once.
        Component warning = Component.translatable("hearthstead.message.raid_omen", s.name);
        RaidBroadcast.town(level, s, warning);
        RaidBroadcast.town(level, s, warning);
        // DEATH: a real settler death through SettlerEntity#die.
        settler(helper, s, "Alda").kill();
        // BUILDING: two buildings finished in the same tick fold into one line.
        TownChat.send(level, s, TownChat.Kind.BUILDING, Component.literal("Cottage"));
        TownChat.send(level, s, TownChat.Kind.BUILDING, Component.literal("Well"));
        // UPGRADE, RESEARCH, TRADE through the same door their senders use.
        TownChat.send(level, s, TownChat.Kind.UPGRADE,
            Component.translatable("hearthstead.requirement.home_tier.cottage"));
        TownChat.send(level, s, TownChat.Kind.RESEARCH,
            Component.translatable("hearthstead.techtree.announce.learned", "Cottages"));
        TownChat.send(level, s, TownChat.Kind.TRADE, Component.literal("Rolf sold 12 Oak Logs for 5 Coins"));

        helper.runAfterDelay(2, () -> {
            try {
                long raid = count(f, f.member(), TownChat.Kind.RAID);
                long death = count(f, f.member(), TownChat.Kind.DEATH);
                long green = count(f, f.member(), TownChat.Kind.BUILDING); // building + upgrade share green
                long research = count(f, f.member(), TownChat.Kind.RESEARCH);
                long trade = count(f, f.member(), TownChat.Kind.TRADE);
                helper.assertTrue(raid == 1, "member hears the raid line once, got " + raid);
                helper.assertTrue(death == 1, "member hears the death once, got " + death);
                helper.assertTrue(green == 2, "one building line + one upgrade line, got " + green);
                helper.assertTrue(research == 1, "member hears the research once, got " + research);
                helper.assertTrue(trade == 1, "member hears the sale once, got " + trade);
                long buildingLines = f.heard().stream()
                    .filter(h -> h.player().equals(f.member().getUUID()))
                    .filter(h -> h.line().getString().contains("Cottage, Well"))
                    .count();
                helper.assertTrue(buildingLines == 1, "the two buildings share one line");
                boolean deathHasCause = f.heard().stream()
                    .filter(h -> h.player().equals(f.member().getUUID()))
                    .anyMatch(h -> h.line().getSiblings().get(2).getContents() instanceof TranslatableContents body
                        && "hearthstead.chat.death".equals(body.getKey()));
                helper.assertTrue(deathHasCause, "the death line carries its cause");
                long strangerHeard = f.heard().stream()
                    .filter(h -> h.player().equals(f.stranger().getUUID())).count();
                helper.assertTrue(strangerHeard == 0,
                    "a non-member beside the Banner hears nothing, got " + strangerHeard);
                helper.assertTrue(f.heard().size() == 6, "six lines in all, got " + f.heard().size());
            } finally {
                cleanup(helper, f);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "town_chat")
    public void aSwitchedOffKindIsNotSent(GameTestHelper helper) {
        Fixture f = fixture(helper, true);
        TownChatConfig.overrideForTests(TownChat.Kind.DEATH, false);
        settler(helper, f.settlement(), "Bram").kill();
        TownChat.send(helper.getLevel(), f.settlement(), TownChat.Kind.RESEARCH, Component.literal("Wells"));
        helper.runAfterDelay(2, () -> {
            try {
                helper.assertTrue(count(f, f.member(), TownChat.Kind.DEATH) == 0, "death switched off: no line");
                helper.assertTrue(count(f, f.member(), TownChat.Kind.RESEARCH) == 1, "other kinds still go out");
            } finally {
                cleanup(helper, f);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "town_chat")
    public void aTownNobodyJoinedFallsBackToPlayersNearby(GameTestHelper helper) {
        Fixture f = fixture(helper, false);
        TownChat.send(helper.getLevel(), f.settlement(), TownChat.Kind.RAID, Component.literal("Raiders sighted"));
        helper.runAfterDelay(2, () -> {
            try {
                helper.assertTrue(count(f, f.member(), TownChat.Kind.RAID) == 1, "nearby player A hears it");
                helper.assertTrue(count(f, f.stranger(), TownChat.Kind.RAID) == 1, "nearby player B hears it");
            } finally {
                cleanup(helper, f);
            }
            helper.succeed();
        });
    }
}
