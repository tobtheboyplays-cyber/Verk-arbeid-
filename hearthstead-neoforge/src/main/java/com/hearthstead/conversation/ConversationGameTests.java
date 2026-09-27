package com.hearthstead.conversation;

import com.hearthstead.Hearthstead;
import com.hearthstead.conversation.net.ConvActionPayload;
import com.hearthstead.conversation.parley.RaidParley;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.RaidLifecycle;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Conversation contracts (batch talk_*): a reply's cost is taken exactly
 * once, barter swaps exactly (and a replayed or tampered offer moves
 * nothing), paying a raid captain's tribute makes the raid leave, and the
 * walk-up pull-in needs a clear line of sight.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class ConversationGameTests {
    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
    }

    /** A real logged-in survival player (vanilla login path, not a spectator mock). */
    private static ServerPlayer player(GameTestHelper helper, String name, BlockPos rel) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
            new com.mojang.authlib.GameProfile(UUID.randomUUID(), name), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
            cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        BlockPos abs = helper.absolutePos(rel);
        player.teleportTo(helper.getLevel(), abs.getX() + 0.5D, abs.getY(), abs.getZ() + 0.5D, 0.0F, 0.0F);
        player.getInventory().clearContent();
        return player;
    }

    private static void logout(GameTestHelper helper, ServerPlayer player) {
        ConversationService.closeFor(player, null);
        helper.getLevel().getServer().getPlayerList().remove(player);
    }

    private static Villager npc(GameTestHelper helper, BlockPos rel) {
        Villager villager = helper.spawn(EntityType.VILLAGER, rel);
        villager.setNoAi(true);
        return villager;
    }

    private static int count(ServerPlayer player, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) n += stack.getCount();
        }
        return n;
    }

    private static SpeakerProfile profile(String name) {
        return new SpeakerProfile(UUID.randomUUID(), name, "conversation.hearthstead.title.traveller", "traveller");
    }

    // ----------------------------------------------------------------------

    @GameTest(template = "empty16", timeoutTicks = 80, batch = "talk_cost_once")
    public void replyCostIsTakenExactlyOnce(GameTestHelper helper) {
        ConversationConfig.overrideForTests(true, null);
        floor(helper, 16);
        ServerPlayer player = player(helper, "talk-cost", new BlockPos(4, 1, 4));
        try {
            Villager traveller = npc(helper, new BlockPos(6, 1, 4));
            helper.assertTrue(ConversationGraphs.get("hearthstead:traveller") != null,
                "the shipped JSON graph must load and validate");
            player.getInventory().add(new ItemStack(Items.BREAD, 5));
            ConversationService.bind(traveller, "hearthstead:traveller", profile("Edda"));
            helper.assertTrue(ConversationService.openBound(player, traveller, false), "right-click path opens");
            int session = ConversationService.sessionIdOf(player);
            int revision = ConversationService.revisionOf(player);
            helper.assertTrue(ConversationService.chooseForTest(player, "bread"), "bread reply is offered");
            helper.assertTrue(count(player, Items.BREAD) == 3, "exactly 2 bread taken, have " + count(player, Items.BREAD));
            helper.assertTrue("news".equals(ConversationService.nodeOf(player)), "the talk moved on");
            // A replayed click on the old reply index moves nothing: it is no longer that reply.
            ConversationService.handle(player, ConvActionPayload.choose(session, revision, 0, "bread"));
            ConversationService.handle(player, ConvActionPayload.choose(session, revision, 0, "bread"));
            helper.assertTrue(count(player, Items.BREAD) == 3, "a replayed reply must not charge again");
            helper.assertTrue(!ConversationService.chooseForTest(player, "bread"), "bread is not a reply in the next node");

            // Unaffordable: a fresh talk with 1 bread keeps the bread and the node.
            ConversationService.closeFor(player, null);
            player.getInventory().clearContent();
            player.getInventory().add(new ItemStack(Items.BREAD, 1));
            ConversationService.bind(traveller, "hearthstead:traveller", profile("Edda"));
            helper.assertTrue(ConversationService.openBound(player, traveller, false), "reopens after rebinding");
            ConversationService.chooseForTest(player, "bread");
            helper.assertTrue(count(player, Items.BREAD) == 1 && "start".equals(ConversationService.nodeOf(player)),
                "an unaffordable reply takes nothing and goes nowhere");

            // A paid reply that loops back to the same node: a double-click in one state pays once.
            ConversationService.closeFor(player, null);
            ConversationGraphs.register(Conversation.graph("hearthstead:test_tip").noEncounter()
                .node("start", n -> n.line("conversation.hearthstead.traveller.greet")
                    .option("tip", o -> o.text("conversation.hearthstead.traveller.bread")
                        .cost(Conversation.coins(1)).relation(1).goTo("start"))
                    .option("leave", o -> o.text("conversation.hearthstead.leave").end()))
                .build());
            player.getInventory().clearContent();
            player.getInventory().add(new ItemStack(ModItems.GOLD_COIN.get(), 5));
            helper.assertTrue(ConversationService.open(player, traveller, "hearthstead:test_tip", profile("Tipper")),
                "code graph opens");
            int tipSession = ConversationService.sessionIdOf(player);
            int tipRevision = ConversationService.revisionOf(player);
            ConvActionPayload tip = ConvActionPayload.choose(tipSession, tipRevision, 0, "tip");
            ConversationService.handle(player, tip);
            ConversationService.handle(player, tip);
            ConversationService.handle(player, tip);
            helper.assertTrue(count(player, ModItems.GOLD_COIN.get()) == 4,
                "three sends of one paid reply in one state pay exactly once, left "
                    + count(player, ModItems.GOLD_COIN.get()));
            ConversationService.handle(player, ConvActionPayload.choose(tipSession, ConversationService.revisionOf(player), 0, "tip"));
            helper.assertTrue(count(player, ModItems.GOLD_COIN.get()) == 3, "the next state's answer pays again");
        } finally {
            logout(helper, player);
            ConversationConfig.overrideForTests(null, null);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 80, batch = "talk_barter_exact")
    public void barterSwapsExactlyAndReplaysMoveNothing(GameTestHelper helper) {
        ConversationConfig.overrideForTests(true, null);
        floor(helper, 16);
        ServerPlayer player = player(helper, "talk-barter", new BlockPos(4, 1, 4));
        try {
            Villager peddler = npc(helper, new BlockPos(6, 1, 4));
            ListBarterStock stock = new ListBarterStock(List.of(new ItemStack(Items.EMERALD, 5),
                new ItemStack(Items.DIAMOND, 1)));
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 64));
            helper.assertTrue(ConversationService.openBarter(player, peddler, profile("Brannoc"), stock), "barter opens");
            int session = ConversationService.sessionIdOf(player);
            var give10 = List.of(new ConvActionPayload.Line(0, 10));
            var take1 = List.of(new ConvActionPayload.Line(0, 1));
            // One emerald (100) needs 115 at a neutral relation: 9 logs (108) is not enough, 10 (120) is.
            ConversationService.handle(player, ConvActionPayload.accept(session, ConversationService.revisionOf(player),
                List.of(new ConvActionPayload.Line(0, 9)), take1));
            helper.assertTrue(count(player, Items.OAK_LOG) == 64 && count(player, Items.EMERALD) == 0
                && countIn(stock, Items.EMERALD) == 5, "an unsatisfying offer moves nothing");
            int revision = ConversationService.revisionOf(player);
            ConvActionPayload deal = ConvActionPayload.accept(session, revision, give10, take1);
            ConversationService.handle(player, deal);
            helper.assertTrue(count(player, Items.OAK_LOG) == 54 && count(player, Items.EMERALD) == 1,
                "player gave exactly 10 logs and got exactly 1 emerald");
            helper.assertTrue(countIn(stock, Items.EMERALD) == 4 && countIn(stock, Items.OAK_LOG) == 10,
                "peddler holds 4 emeralds and 10 logs");
            // Replay / double-click: the same accept again (54 logs would still cover it) does nothing.
            ConversationService.handle(player, deal);
            ConversationService.handle(player, deal);
            helper.assertTrue(count(player, Items.OAK_LOG) == 54 && count(player, Items.EMERALD) == 1
                && countIn(stock, Items.EMERALD) == 4, "a replayed accept is ignored: revision already used");
            // Hostile duplicate rows that would overflow an int sum: refused whole.
            ConversationService.handle(player, ConvActionPayload.accept(session, ConversationService.revisionOf(player),
                List.of(new ConvActionPayload.Line(0, 1)),
                List.of(new ConvActionPayload.Line(0, Integer.MAX_VALUE), new ConvActionPayload.Line(0, 1),
                    new ConvActionPayload.Line(1, 1))));
            helper.assertTrue(count(player, Items.DIAMOND) == 0 && countIn(stock, Items.DIAMOND) == 1
                && count(player, Items.OAK_LOG) == 54, "duplicate take rows are refused, nothing moves");
            // Negative and zero counts: refused.
            ConversationService.handle(player, ConvActionPayload.accept(session, ConversationService.revisionOf(player),
                List.of(new ConvActionPayload.Line(0, -5)), List.of(new ConvActionPayload.Line(1, 1))));
            ConversationService.handle(player, ConvActionPayload.accept(session, ConversationService.revisionOf(player),
                List.of(new ConvActionPayload.Line(0, 64)), List.of(new ConvActionPayload.Line(1, 0))));
            helper.assertTrue(count(player, Items.DIAMOND) == 0 && count(player, Items.OAK_LOG) == 54,
                "negative or zero counts are refused");
            // Duplicate give rows (the same slot twice) are refused too.
            ConversationService.handle(player, ConvActionPayload.accept(session, ConversationService.revisionOf(player),
                List.of(new ConvActionPayload.Line(0, 30), new ConvActionPayload.Line(0, 30)), take1));
            helper.assertTrue(count(player, Items.OAK_LOG) == 54 && count(player, Items.EMERALD) == 1,
                "the same slot offered twice is refused");
            // Asking for more than the stock holds: refused whole.
            // Slot 20 (not 1: the bought emerald landed in the first free slot).
            player.getInventory().setItem(20, new ItemStack(ModItems.GOLD_COIN.get(), 64));
            ConversationService.handle(player, ConvActionPayload.accept(session, ConversationService.revisionOf(player),
                List.of(new ConvActionPayload.Line(20, 64)), List.of(new ConvActionPayload.Line(0, 9))));
            helper.assertTrue(count(player, ModItems.GOLD_COIN.get()) == 64 && count(player, Items.EMERALD) == 1,
                "taking more than the stock holds is refused whole");
        } finally {
            logout(helper, player);
            ConversationConfig.overrideForTests(null, null);
        }
        helper.succeed();
    }

    private static int countIn(ListBarterStock stock, net.minecraft.world.item.Item item) {
        int n = 0;
        for (ItemStack s : stock.items()) if (s.is(item)) n += s.getCount();
        return n;
    }

    @GameTest(template = "empty16", timeoutTicks = 80, batch = "talk_replay")
    public void foreignStaleAndReopenedPacketsMoveNothing(GameTestHelper helper) {
        ConversationConfig.overrideForTests(true, null);
        floor(helper, 16);
        ServerPlayer alice = player(helper, "talk-alice", new BlockPos(4, 1, 4));
        ServerPlayer bob = player(helper, "talk-bob", new BlockPos(4, 1, 6));
        try {
            Villager first = npc(helper, new BlockPos(6, 1, 4));
            Villager second = npc(helper, new BlockPos(6, 1, 7));
            ListBarterStock stockA = new ListBarterStock(List.of(new ItemStack(Items.EMERALD, 5)));
            ListBarterStock stockB = new ListBarterStock(List.of(new ItemStack(Items.EMERALD, 5)));
            alice.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 64));
            bob.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 64));
            var give10 = List.of(new ConvActionPayload.Line(0, 10));
            var take1 = List.of(new ConvActionPayload.Line(0, 1));
            helper.assertTrue(ConversationService.openBarter(alice, first, profile("Aldric"), stockA), "alice trades");
            int aliceSession = ConversationService.sessionIdOf(alice);
            int aliceRevision = ConversationService.revisionOf(alice);

            // (a) Bob replays Alice's session id and revision: nothing moves for either of them.
            ConversationService.handle(bob, ConvActionPayload.accept(aliceSession, aliceRevision, give10, take1));
            helper.assertTrue(count(alice, Items.OAK_LOG) == 64 && count(bob, Items.OAK_LOG) == 64
                && count(bob, Items.EMERALD) == 0 && countIn(stockA, Items.EMERALD) == 5,
                "another player's session and revision are refused");
            helper.assertTrue(ConversationService.revisionOf(alice) == aliceRevision,
                "a foreign packet must not use up the owner's revision");

            // (b) Alice switches to a different NPC; the old table's packet is stale.
            helper.assertTrue(ConversationService.openBarter(alice, second, profile("Brannoc"), stockB), "alice moves on");
            int secondSession = ConversationService.sessionIdOf(alice);
            helper.assertTrue(secondSession != aliceSession, "a new NPC is a new session");
            ConversationService.handle(alice, ConvActionPayload.accept(aliceSession, aliceRevision, give10, take1));
            ConversationService.handle(alice, ConvActionPayload.accept(secondSession, aliceRevision, give10, take1));
            helper.assertTrue(count(alice, Items.OAK_LOG) == 64 && count(alice, Items.EMERALD) == 0
                && countIn(stockA, Items.EMERALD) == 5 && countIn(stockB, Items.EMERALD) == 5,
                "a packet for the previous NPC's table moves nothing at either NPC");

            // (c) Close and reopen the same NPC: the old session and revision are dead.
            int staleSession = ConversationService.sessionIdOf(alice);
            int staleRevision = ConversationService.revisionOf(alice);
            ConversationService.closeFor(alice, null);
            helper.assertTrue(ConversationService.openBarter(alice, second, profile("Brannoc"), stockB), "reopens");
            ConversationService.handle(alice, ConvActionPayload.accept(staleSession, staleRevision, give10, take1));
            if (ConversationService.revisionOf(alice) != staleRevision) {
                ConversationService.handle(alice, ConvActionPayload.accept(ConversationService.sessionIdOf(alice),
                    staleRevision, give10, take1));
            }
            helper.assertTrue(count(alice, Items.OAK_LOG) == 64 && count(alice, Items.EMERALD) == 0
                && countIn(stockB, Items.EMERALD) == 5, "packets from before close/reopen move nothing");

            // The live table still works exactly once.
            ConvActionPayload live = ConvActionPayload.accept(ConversationService.sessionIdOf(alice),
                ConversationService.revisionOf(alice), give10, take1);
            ConversationService.handle(alice, live);
            ConversationService.handle(alice, live);
            helper.assertTrue(count(alice, Items.OAK_LOG) == 54 && count(alice, Items.EMERALD) == 1
                && countIn(stockB, Items.EMERALD) == 4, "the current table deals exactly once");
        } finally {
            logout(helper, alice);
            logout(helper, bob);
            ConversationConfig.overrideForTests(null, null);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 80, batch = "talk_encounter_sight")
    public void pullInNeedsClearLineOfSight(GameTestHelper helper) {
        ConversationConfig.overrideForTests(true, null);
        floor(helper, 16);
        ServerPlayer player = player(helper, "talk-sight", new BlockPos(3, 1, 8));
        try {
            Villager traveller = npc(helper, new BlockPos(8, 1, 8));
            for (int y = 1; y <= 4; y++) {
                for (int z = 5; z <= 11; z++) helper.setBlock(new BlockPos(5, y, z), Blocks.OAK_PLANKS);
            }
            ConversationService.bind(traveller, "hearthstead:traveller", profile("Wall"));
            ConversationService.tryEncounter(player);
            helper.assertTrue(ConversationService.sessionIdOf(player) < 0, "a wall between them: no pull-in");
            for (int y = 1; y <= 4; y++) {
                for (int z = 5; z <= 11; z++) helper.setBlock(new BlockPos(5, y, z), Blocks.AIR);
            }
            ConversationService.tryEncounter(player);
            helper.assertTrue(ConversationService.sessionIdOf(player) >= 0, "wall removed: the traveller pulls the player in");
            // Walking away and back does not pull in again until the NPC has something new to say.
            ConversationService.closeFor(player, null);
            ConversationService.tryEncounter(player);
            helper.assertTrue(ConversationService.sessionIdOf(player) < 0, "no retrigger for the same binding");
        } finally {
            logout(helper, player);
            ConversationConfig.overrideForTests(null, null);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "talk_parley_tribute")
    public void payingTributeMakesTheRaidLeave(GameTestHelper helper) {
        ConversationConfig.overrideForTests(true, true);
        floor(helper, 16);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Parleyholm", helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 10;
        settlement.raidLifecycle = completedFirst(helper);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        ServerPlayer player = player(helper, "talk-parley", new BlockPos(8, 1, 8));
        try {
            RaidCaptain captainRecord = RaidDirector.pickCaptain(settlement, helper.getLevel().getRandom());
            RaidPlan plan = new RaidPlan(captainRecord.id(), RaidObjective.KORN, 0.0F, 14L);
            helper.assertTrue(settlement.recurringRaidRun.queue(plan), "queue one recurring raid");
            List<RaiderEntity> band = RaidDirector.startQueuedRecurringRaid(helper.getLevel(), settlement);
            helper.assertTrue(!band.isEmpty() && settlement.recurringRaidRun.isActive(), "the band arrives");
            RaiderEntity captain = band.stream().filter(RaiderEntity::isCaptain).findFirst().orElse(null);
            helper.assertTrue(captain != null, "the band has a captain");
            helper.assertTrue(RaidParley.tryStart(helper.getLevel(), settlement) || RaidParley.holding(settlement.id),
                "a fresh raid with a player near halts for a parley");
            helper.assertTrue(band.stream().allMatch(RaiderEntity::isNoAi), "the whole band holds");
            helper.assertTrue(!RaidParley.tryStart(helper.getLevel(), settlement), "one parley per raid");
            player.teleportTo(helper.getLevel(), captain.getX() + 2.0D, captain.getY(), captain.getZ(), 0.0F, 0.0F);
            int tribute = RaidParley.tributeCoins(band.size());
            player.getInventory().add(new ItemStack(ModItems.GOLD_COIN.get(), tribute + 5));
            helper.assertTrue(ConversationService.openBound(player, captain, false), "the captain talks");
            helper.assertTrue(ConversationService.chooseForTest(player, "tribute_coins"), "tribute reply offered");
            helper.assertTrue(count(player, ModItems.GOLD_COIN.get()) == 5,
                "exactly the tribute was paid, left " + count(player, ModItems.GOLD_COIN.get()));
            helper.assertTrue(!settlement.recurringRaidRun.isActive() && settlement.pendingRaid == null,
                "the raid is over");
            // Owner rule (no poof): the band is released and walks off; it counts for no raid any more.
            helper.assertTrue(!RaidParley.holding(settlement.id) && captain.isAlive() && !captain.isNoAi()
                && Departure.isDeparting(captain) && captain.settlementId() == null && captain.getTarget() == null,
                "the band walks away, detached from the raid and non-hostile");
            for (RaiderEntity raider : band) {
                helper.assertTrue(!raider.isAlive() || Departure.isDeparting(raider), "every raider leaves with the captain");
            }
            helper.assertTrue(settlement.raidLifecycle != null, "lifecycle intact");
        } finally {
            logout(helper, player);
            for (RaiderEntity leaver : helper.getLevel().getEntitiesOfClass(RaiderEntity.class,
                new net.minecraft.world.phys.AABB(settlement.center).inflate(96.0D), Departure::isDeparting)) {
                leaver.discard();
            }
            data.settlements.remove(settlement.id);
            data.setDirty();
            ConversationConfig.overrideForTests(null, null);
        }
        helper.succeed();
    }

    private static RaidLifecycle completedFirst(GameTestHelper helper) {
        RaidLifecycle lifecycle = new RaidLifecycle();
        RaidPlan first = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN, 0.0F, 4L);
        UUID participant = UUID.randomUUID();
        helper.assertTrue(lifecycle.initializeAtFounding(0L, 4, 2)
                && lifecycle.queueFirstPlan(first)
                && lifecycle.beginFirstRaid(first)
                && lifecycle.recordParticipant(participant)
                && lifecycle.sealParticipants()
                && lifecycle.recordTerminalParticipant(participant)
                && lifecycle.completeFirstRaid(false),
            "fixture: one cleanly completed first raid");
        return lifecycle;
    }

    /** Reads the identity a binding stores (test only). */
    static final class ConversationServiceTestAccess {
        static UUID identity(net.minecraft.world.entity.Entity npc) {
            SpeakerProfile profile = SpeakerProfile.read(npc.getPersistentData()
                .getCompound(ConversationService.BIND_TAG).getCompound("Profile"));
            return profile == null ? new UUID(0L, 0L) : profile.identity();
        }
    }

    @SuppressWarnings("unused")
    private static Map<String, Integer> none() {
        return Map.of();
    }
}
