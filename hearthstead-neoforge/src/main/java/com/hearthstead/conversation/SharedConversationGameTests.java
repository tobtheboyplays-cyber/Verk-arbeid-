package com.hearthstead.conversation;

import com.hearthstead.Hearthstead;
import com.hearthstead.conversation.net.ConvActionPayload;
import com.hearthstead.conversation.parley.RaidParley;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.gametest.GameTestTicks;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.RaidLifecycle;
import java.util.ArrayList;
import java.util.List;
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
 * Co-op shared conversations (batch shared_talk_*; owner, 27 Sep): with a
 * partner online the talk waits ("Continue alone" after a delay), a partner
 * in reach joins the same session, the first click decides for both and a
 * later click on the same state is stale; solo play never waits; a waiting
 * talk never holds an event's own budget.
 *
 * <p>GameTest mock players have no conversation channel, so the partner is
 * marked as a client with {@link ConversationService#markClientForTests}.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class SharedConversationGameTests {
    private static final String GRAPH = "hearthstead:traveller";

    /** Everything a test must undo, even when an assertion fails half-way. */
    private static final class Fixture {
        final List<ServerPlayer> players = new ArrayList<>();
        final List<Runnable> undo = new ArrayList<>();
        boolean done;

        void cleanup(GameTestHelper helper) {
            if (done) return;
            done = true;
            for (ServerPlayer player : players) {
                ConversationService.markClientForTests(player, false);
                ConversationService.closeFor(player, null);
                if (helper.getLevel().getServer().getPlayerList().getPlayer(player.getUUID()) != null) {
                    helper.getLevel().getServer().getPlayerList().remove(player);
                }
            }
            for (Runnable r : undo) {
                try {
                    r.run();
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
            ConversationService.shareTimingForTests(-1, -1);
            ConversationConfig.overrideSharedForTests(null);
            ConversationConfig.overrideForTests(null, null);
        }
    }

    /** A step that cleans the fixture up when it fails. */
    private static void step(GameTestHelper helper, long t, Fixture fx, Runnable body) {
        GameTestTicks.at(helper, t, () -> {
            try {
                body.run();
            } catch (RuntimeException | AssertionError failure) {
                fx.cleanup(helper);
                throw failure;
            }
        });
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
    }

    private static ServerPlayer player(GameTestHelper helper, Fixture fx, String name, BlockPos rel) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
            new com.mojang.authlib.GameProfile(UUID.randomUUID(), name), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
            cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        fx.players.add(player);
        moveTo(helper, player, rel);
        player.getInventory().clearContent();
        return player;
    }

    private static void moveTo(GameTestHelper helper, ServerPlayer player, BlockPos rel) {
        BlockPos abs = helper.absolutePos(rel);
        player.teleportTo(helper.getLevel(), abs.getX() + 0.5D, abs.getY(), abs.getZ() + 0.5D, 0.0F, 0.0F);
    }

    private static Settlement settlement(GameTestHelper helper, Fixture fx, String name) {
        Settlement settlement = new Settlement(UUID.randomUUID(), name, helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 10;
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        fx.undo.add(() -> {
            data.settlements.remove(settlement.id);
            data.setDirty();
        });
        return settlement;
    }

    private static Villager traveller(GameTestHelper helper, Fixture fx, Settlement settlement) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(6, 1, 4));
        villager.setNoAi(true);
        ConversationService.bind(villager, GRAPH, new SpeakerProfile(UUID.randomUUID(), "Edda",
            "conversation.hearthstead.title.traveller", "traveller"), java.util.Map.of(), settlement.id);
        fx.undo.add(villager::discard);
        return villager;
    }

    private static int bread(ServerPlayer player) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(Items.BREAD)) n += player.getInventory().getItem(i).getCount();
        }
        return n;
    }

    // ---------------------------------------------------------------------

    @GameTest(template = "empty16", timeoutTicks = 120, batch = "shared_talk_first_click")
    public void partnerJoinsAndTheFirstClickDecidesForBoth(GameTestHelper helper) {
        Fixture fx = new Fixture();
        ConversationConfig.overrideForTests(true, null);
        ConversationConfig.overrideSharedForTests(true);
        floor(helper);
        Settlement settlement = settlement(helper, fx, "Twofold");
        ServerPlayer a = player(helper, fx, "coop-a", new BlockPos(4, 1, 4));
        ServerPlayer b = player(helper, fx, "coop-b", new BlockPos(4, 1, 4));
        moveTo(helper, b, new BlockPos(4, 1, 44));
        settlement.addMember(a.getUUID());
        settlement.addMember(b.getUUID());
        ConversationService.markClientForTests(b, true);
        a.getInventory().add(new ItemStack(Items.BREAD, 5));
        b.getInventory().add(new ItemStack(Items.BREAD, 5));
        Villager edda = traveller(helper, fx, settlement);
        int[] staleRevision = {-1};
        step(helper, 1, fx, () -> {
            helper.assertTrue(ConversationService.openBound(a, edda, false), "A opens the talk");
            helper.assertTrue(ConversationService.isWaiting(a), "a partner is online: A waits for B (1/2)");
            helper.assertTrue(ConversationService.participantIdsOf(a).equals(List.of(a.getUUID())), "only A so far");
            helper.assertTrue(ConversationService.sessionIdOf(b) == -1, "B, 40 blocks away, is not pulled in");
            ConversationService.chooseForTest(a, "bread");
            helper.assertTrue(bread(a) == 5 && "start".equals(ConversationService.nodeOf(a)),
                "no graph reply counts while waiting");
            moveTo(helper, b, new BlockPos(8, 1, 4));
        });
        step(helper, 4, fx, () -> {
            helper.assertTrue(ConversationService.sessionIdOf(b) == ConversationService.sessionIdOf(a),
                "B walked within reach and joined the SAME session");
            helper.assertTrue(!ConversationService.isWaiting(a) && !ConversationService.isWaiting(b), "the talk started");
            helper.assertTrue(ConversationService.participantIdsOf(b).equals(List.of(a.getUUID(), b.getUUID())),
                "both are participants, A leads");
            staleRevision[0] = ConversationService.revisionOf(b);
            helper.assertTrue(staleRevision[0] == ConversationService.revisionOf(a), "one revision for both");
            // A clicks first: decides for both.
            helper.assertTrue(ConversationService.chooseForTest(a, "bread"), "A answers");
            helper.assertTrue(bread(a) == 3 && bread(b) == 5, "the chooser alone paid, once: A=" + bread(a) + " B=" + bread(b));
            helper.assertTrue("news".equals(ConversationService.nodeOf(b)), "B's panel advanced with A's choice");
            // B's click on the state A already answered is stale and changes nothing.
            int session = ConversationService.sessionIdOf(b);
            ConversationService.handle(b, ConvActionPayload.choose(session, staleRevision[0], 0, "bread"));
            helper.assertTrue(bread(a) == 3 && bread(b) == 5 && "news".equals(ConversationService.nodeOf(a)),
                "a stale click by B moves nothing");
            // A click from a player outside the talk is refused.
            helper.assertTrue(ConversationService.revisionOf(a) != staleRevision[0], "the revision moved on");
            // B answers the next state; the last "leave" closes both panels together.
            helper.assertTrue(ConversationService.chooseForTest(b, "leave"), "B answers the next state");
            helper.assertTrue("__end".equals(ConversationService.nodeOf(a)), "A sees B's choice");
            helper.assertTrue(ConversationService.chooseForTest(a, "leave"), "the closing reply");
            helper.assertTrue(ConversationService.sessionIdOf(a) == -1 && ConversationService.sessionIdOf(b) == -1,
                "both panels closed together");
            helper.assertTrue(!ConversationService.isTalking(edda), "nothing stays locked");
            fx.cleanup(helper);
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "shared_talk_solo")
    public void soloAndSwitchedOffNeverWait(GameTestHelper helper) {
        Fixture fx = new Fixture();
        ConversationConfig.overrideForTests(true, null);
        ConversationConfig.overrideSharedForTests(true);
        floor(helper);
        Settlement settlement = settlement(helper, fx, "Onefold");
        ServerPlayer a = player(helper, fx, "solo-a", new BlockPos(4, 1, 4));
        settlement.addMember(a.getUUID());
        a.getInventory().add(new ItemStack(Items.BREAD, 5));
        Villager edda = traveller(helper, fx, settlement);
        step(helper, 1, fx, () -> {
            helper.assertTrue(ConversationService.openBound(a, edda, false), "opens");
            helper.assertTrue(!ConversationService.isWaiting(a), "alone: no waiting");
            helper.assertTrue(ConversationService.chooseForTest(a, "bread") && bread(a) == 3
                && "news".equals(ConversationService.nodeOf(a)), "answers at once, as before");
            ConversationService.closeFor(a, null);
            helper.assertTrue(ConversationService.sessionIdOf(a) == -1 && !ConversationService.isTalking(edda), "closed");
            // Kill switch: a partner online, but sharedConversations=false is today's behaviour.
            ServerPlayer b = player(helper, fx, "solo-b", new BlockPos(4, 1, 44));
            settlement.addMember(b.getUUID());
            ConversationService.markClientForTests(b, true);
            ConversationConfig.overrideSharedForTests(false);
            ConversationService.bind(edda, GRAPH, new SpeakerProfile(UUID.randomUUID(), "Edda",
                "conversation.hearthstead.title.traveller", "traveller"), java.util.Map.of(), settlement.id);
            helper.assertTrue(ConversationService.openBound(a, edda, false), "reopens");
            helper.assertTrue(!ConversationService.isWaiting(a), "switched off: no waiting");
            moveTo(helper, b, new BlockPos(8, 1, 4));
        });
        step(helper, 4, fx, () -> {
            helper.assertTrue(ConversationService.sessionIdOf(b(fx)) == -1, "switched off: nobody is pulled in");
            fx.cleanup(helper);
            helper.succeed();
        });
    }

    private static ServerPlayer b(Fixture fx) {
        return fx.players.get(fx.players.size() - 1);
    }

    @GameTest(template = "empty16", timeoutTicks = 160, batch = "shared_talk_continue_alone")
    public void continueAloneThenLateJoinAndLeave(GameTestHelper helper) {
        Fixture fx = new Fixture();
        ConversationConfig.overrideForTests(true, null);
        ConversationConfig.overrideSharedForTests(true);
        ConversationService.shareTimingForTests(20, 400);
        floor(helper);
        Settlement settlement = settlement(helper, fx, "Aloneford");
        ServerPlayer a = player(helper, fx, "alone-a", new BlockPos(4, 1, 4));
        ServerPlayer b = player(helper, fx, "alone-b", new BlockPos(4, 1, 4));
        moveTo(helper, b, new BlockPos(4, 1, 44));
        settlement.addMember(a.getUUID());
        settlement.addMember(b.getUUID());
        ConversationService.markClientForTests(b, true);
        a.getInventory().add(new ItemStack(Items.BREAD, 5));
        Villager edda = traveller(helper, fx, settlement);
        step(helper, 1, fx, () -> {
            helper.assertTrue(ConversationService.openBound(a, edda, false), "opens");
            helper.assertTrue(ConversationService.isWaiting(a), "waits for B");
            helper.assertTrue(!ConversationService.continueAloneForTest(a), "Continue alone is locked at first");
        });
        step(helper, 30, fx, () -> {
            helper.assertTrue(ConversationService.isWaiting(a), "still waiting (the wait limit is far off)");
            helper.assertTrue(ConversationService.continueAloneForTest(a), "Continue alone after the delay");
            helper.assertTrue(ConversationService.chooseForTest(a, "bread") && bread(a) == 3, "A talks alone");
            moveTo(helper, b, new BlockPos(8, 1, 4));
        });
        step(helper, 34, fx, () -> {
            helper.assertTrue(ConversationService.sessionIdOf(b) == ConversationService.sessionIdOf(a),
                "B still joins when they come");
            // B presses Esc: A carries on.
            ConversationService.handle(b, ConvActionPayload.simple(ConversationService.sessionIdOf(b), ConvActionPayload.LEAVE));
            helper.assertTrue(ConversationService.sessionIdOf(b) == -1, "B left");
            helper.assertTrue(ConversationService.sessionIdOf(a) != -1 && "news".equals(ConversationService.nodeOf(a)),
                "A's talk goes on");
            // A disconnects: the talk ends, nothing leaks.
            helper.getLevel().getServer().getPlayerList().remove(a);
            ConversationService.closeFor(a, null);
        });
        step(helper, 38, fx, () -> {
            helper.assertTrue(!ConversationService.isTalking(edda), "the speaker is free again");
            helper.assertTrue(ConversationService.sessionIdOf(b) == -1, "B was not pulled back in");
            fx.cleanup(helper);
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80, batch = "shared_talk_partner_leaves")
    public void aPartnerWhoLogsOutIsNotWaitedFor(GameTestHelper helper) {
        Fixture fx = new Fixture();
        ConversationConfig.overrideForTests(true, null);
        ConversationConfig.overrideSharedForTests(true);
        floor(helper);
        Settlement settlement = settlement(helper, fx, "Leaveby");
        ServerPlayer a = player(helper, fx, "gone-a", new BlockPos(4, 1, 4));
        ServerPlayer b = player(helper, fx, "gone-b", new BlockPos(4, 1, 4));
        moveTo(helper, b, new BlockPos(4, 1, 44));
        settlement.addMember(a.getUUID());
        settlement.addMember(b.getUUID());
        ConversationService.markClientForTests(b, true);
        a.getInventory().add(new ItemStack(Items.BREAD, 5));
        Villager edda = traveller(helper, fx, settlement);
        step(helper, 1, fx, () -> {
            helper.assertTrue(ConversationService.openBound(a, edda, false) && ConversationService.isWaiting(a), "waits");
            helper.getLevel().getServer().getPlayerList().remove(b);
        });
        step(helper, 4, fx, () -> {
            helper.assertTrue(!ConversationService.isWaiting(a), "B logged out: A goes on at once");
            helper.assertTrue(ConversationService.chooseForTest(a, "bread") && bread(a) == 3, "A answers");
            fx.cleanup(helper);
            helper.succeed();
        });
    }

    // --------------------------------------------------- event budget ---

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

    /**
     * A raid captain's parley has its own budget. Waiting for a partner does
     * not hold it: the budget runs out, the band charges and the waiting talk
     * ends cleanly (no session, no lock, the partner is not pulled in).
     */
    @GameTest(template = "empty16", timeoutTicks = 1400, batch = "shared_talk_event_budget")
    public void waitingNeverHoldsAnEventsBudget(GameTestHelper helper) {
        Fixture fx = new Fixture();
        ConversationConfig.overrideForTests(true, true);
        ConversationConfig.overrideSharedForTests(true);
        floor(helper);
        Settlement settlement = settlement(helper, fx, "Budgetholm");
        settlement.raidLifecycle = completedFirst(helper);
        ServerPlayer a = player(helper, fx, "budget-a", new BlockPos(8, 1, 8));
        ServerPlayer b = player(helper, fx, "budget-b", new BlockPos(8, 1, 8));
        moveTo(helper, b, new BlockPos(8, 1, 40));
        settlement.addMember(a.getUUID());
        settlement.addMember(b.getUUID());
        ConversationService.markClientForTests(b, true);
        RaidCaptain captainRecord = RaidDirector.pickCaptain(settlement, helper.getLevel().getRandom());
        RaidPlan plan = new RaidPlan(captainRecord.id(), RaidObjective.KORN, 0.0F, 14L);
        helper.assertTrue(settlement.recurringRaidRun.queue(plan), "fixture: queue one recurring raid");
        List<RaiderEntity> band = RaidDirector.startQueuedRecurringRaid(helper.getLevel(), settlement);
        fx.undo.add(() -> {
            for (RaiderEntity raider : band) if (!raider.isRemoved()) raider.discard();
        });
        helper.assertTrue(!band.isEmpty(), "fixture: the band arrives");
        RaiderEntity captain = band.stream().filter(RaiderEntity::isCaptain).findFirst().orElse(null);
        helper.assertTrue(captain != null, "fixture: the band has a captain");
        helper.assertTrue(RaidParley.tryStart(helper.getLevel(), settlement) || RaidParley.holding(settlement.id),
            "fixture: the band halts for a parley");
        a.teleportTo(helper.getLevel(), captain.getX() + 2.0D, captain.getY(), captain.getZ(), 0.0F, 0.0F);
        long started = helper.getLevel().getGameTime();
        step(helper, 1, fx, () -> {
            helper.assertTrue(ConversationService.openBound(a, captain, false), "the captain talks");
            helper.assertTrue(ConversationService.isWaiting(a), "A waits for B");
        });
        helper.succeedWhen(() -> {
            try {
                long waited = helper.getLevel().getGameTime() - started;
                helper.assertTrue(!RaidParley.holding(settlement.id), "the parley still holds after " + waited + " ticks");
                helper.assertTrue(waited >= ConversationConfig.parleyTicks() - 5,
                    "the band kept its full budget (" + ConversationConfig.parleyTicks() + "), waited " + waited);
                helper.assertTrue(settlement.recurringRaidRun.isActive(), "the raid goes on (timed out)");
                helper.assertTrue(ConversationService.sessionIdOf(a) == -1 && !ConversationService.isTalking(captain)
                    && ConversationService.sessionIdOf(b) == -1, "the waiting talk ended cleanly");
            } catch (RuntimeException | AssertionError notYet) {
                if (helper.getLevel().getGameTime() - started > 1350) fx.cleanup(helper);
                throw notYet;
            }
            fx.cleanup(helper);
        });
    }
}
