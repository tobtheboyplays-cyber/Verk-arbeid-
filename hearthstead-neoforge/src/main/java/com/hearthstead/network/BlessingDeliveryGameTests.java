package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.event.CommonEvents;
import com.hearthstead.item.BlessingSealItem;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.DeferredItemMaterializationSavedData;
import com.hearthstead.settlement.PendingPlayerDeliveryLedger;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.BlessingState;
import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Exact, lossless physical-seal delivery order. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BlessingDeliveryGameTests {

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "blessing_delivery_one_hundred_exact")
    public void oneHundredAcceptedOffersMintExactlyOneHundredSeals(
            GameTestHelper helper) {
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = helper.getBlockEntity(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.values().removeIf(old -> helper.getBounds().contains(
            old.center.getX() + 0.5D, old.center.getY() + 0.5D,
            old.center.getZ() + 0.5D));
        Settlement settlement = new Settlement(UUID.randomUUID(), "Hundredstead",
            helper.absolutePos(hearthRel));
        settlement.radius = 12;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getInventory().clearContent();
        NetworkRegistry.configureMockConnection(
            player.connection.getConnection());
        player.setPos(settlement.center.getX() + 0.5D,
            settlement.center.getY() + 1.0D, settlement.center.getZ() + 0.5D);
        BlessingNetwork.clear(helper.getLevel().getServer());
        EmbeddedChannel channel = (EmbeddedChannel) player.connection
            .getConnection().channel();
        List<BlessingSnapshotPayload> payloads = new java.util.ArrayList<>();
        installBlessingCapture(channel, "hearthstead_blessing_hundred", payloads);
        BlessingState ledger = settlement.blessingState;

        for (int i = 0; i < 100; i++) {
            helper.assertTrue(ledger.grantOffer(),
                "trial " + i + " must earn exactly one offer");

            payloads.clear();
            helper.assertTrue(BlessingNetwork.openFor(player, settlement),
                "trial " + i + " must open the live authoritative offer");
            flushAndDrain(channel);
            helper.assertTrue(payloads.size() == 1,
                "trial " + i + " must decode exactly one OPEN payload");
            BlessingSnapshotPayload open = payloads.getFirst();
            helper.assertTrue(open.settlementId().equals(settlement.id)
                    && open.delivery() == BlessingSnapshotPayload.Delivery.OPEN
                    && open.feedback() == BlessingSnapshotPayload.Feedback.NONE
                    && open.revision() == ledger.revision()
                    && open.offerSerial() == ledger.offerSerial(),
                "trial " + i + " must use the exact server-authored offer proof");

            payloads.clear();
            BlessingNetwork.handle(player, new BlessingActionPayload(
                open.settlementId(), open.sessionId(),
                BlessingActionPayload.Kind.CONFIRM, open.revision(),
                open.offerSerial(),
                BlessingId.WARDEN_OATH.wireId()));
            flushAndDrain(channel);
            helper.assertTrue(payloads.size() == 1
                    && isTerminalFeedback(payloads.getFirst(), settlement.id,
                        BlessingSnapshotPayload.Feedback.ACCEPTED,
                        BlessingId.WARDEN_OATH.wireId())
                    && payloads.getFirst().sessionId().equals(open.sessionId())
                    && ledger.earned() == i + 1
                    && ledger.spent() == i + 1
                    && ledger.revision() == (i + 1) * 2
                    && ledger.offerSerial() == 0
                    && ledger.issuedCount(BlessingId.WARDEN_OATH) == i + 1
                    && countSealItems(player) + countSealDrops(helper) == i + 1
                    && BlessingNetwork.viewerSessionCountForTest(
                        helper.getLevel().getServer()) == 0,
                "trial " + i + " must decode one ACCEPTED result, mint one "
                    + "seal and fully reconcile the live session/ledger");
        }

        Settlement saved = Settlement.readNbt(settlement.writeNbt());
        helper.assertTrue(ledger.earned() == 100 && ledger.spent() == 100
                && ledger.issuedCount(BlessingId.WARDEN_OATH) == 100
                && ledger.revision() == 200 && ledger.offerSerial() == 0
                && countSealItems(player) + countSealDrops(helper) == 100
                && saved.id.equals(settlement.id)
                && saved.blessingState.earned() == 100
                && saved.blessingState.spent() == 100
                && saved.blessingState.revision() == 200
                && saved.blessingState.offerSerial() == 0
                && saved.blessingState.issuedCount(BlessingId.WARDEN_OATH) == 100
                && !saved.blessingState.quarantined(),
            "100 real network ACCEPTED transactions must save/round-trip as "
                + "exactly 100 physical seals with no duplicate drop");
        channel.pipeline().remove("hearthstead_blessing_hundred");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "blessing_delivery_twenty_two_player_races")
    public void twentyNetworkedTwoPlayerRacesConvergeWithoutDoubleDelivery(
            GameTestHelper helper) {
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = helper.getBlockEntity(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.values().removeIf(old -> helper.getBounds().contains(
            old.center.getX() + 0.5D, old.center.getY() + 0.5D,
            old.center.getZ() + 0.5D));
        Settlement settlement = new Settlement(UUID.randomUUID(), "Racestead",
            helper.absolutePos(hearthRel));
        settlement.radius = 12;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);

        ServerPlayer playerA = helper.makeMockServerPlayerInLevel();
        ServerPlayer playerB = helper.makeMockServerPlayerInLevel();
        ServerPlayer observer = helper.makeMockServerPlayerInLevel();
        playerA.getInventory().clearContent();
        playerB.getInventory().clearContent();
        observer.getInventory().clearContent();
        NetworkRegistry.configureMockConnection(
            playerA.connection.getConnection());
        NetworkRegistry.configureMockConnection(
            playerB.connection.getConnection());
        NetworkRegistry.configureMockConnection(
            observer.connection.getConnection());
        playerA.setPos(settlement.center.getX() + 0.5D,
            settlement.center.getY() + 1.0D, settlement.center.getZ() + 0.5D);
        playerB.setPos(settlement.center.getX() + 1.5D,
            settlement.center.getY() + 1.0D, settlement.center.getZ() + 0.5D);
        observer.setPos(settlement.center.getX() + 0.5D,
            settlement.center.getY() + 1.0D, settlement.center.getZ() + 1.5D);
        BlessingNetwork.clear(helper.getLevel().getServer());

        EmbeddedChannel channelA = (EmbeddedChannel) playerA.connection
            .getConnection().channel();
        EmbeddedChannel channelB = (EmbeddedChannel) playerB.connection
            .getConnection().channel();
        EmbeddedChannel observerChannel = (EmbeddedChannel) observer.connection
            .getConnection().channel();
        List<BlessingSnapshotPayload> payloadsA = new java.util.ArrayList<>();
        List<BlessingSnapshotPayload> payloadsB = new java.util.ArrayList<>();
        List<BlessingSnapshotPayload> observerPayloads = new java.util.ArrayList<>();
        installBlessingCapture(channelA, "hearthstead_blessing_race_a", payloadsA);
        installBlessingCapture(channelB, "hearthstead_blessing_race_b", payloadsB);
        installBlessingCapture(observerChannel,
            "hearthstead_blessing_race_observer", observerPayloads);

        BlessingState ledger = settlement.blessingState;

        for (int trial = 0; trial < 20; trial++) {
            helper.assertTrue(ledger.grantOffer(),
                "race " + trial + " must earn one shared offer");

            payloadsA.clear();
            payloadsB.clear();
            observerPayloads.clear();
            helper.assertTrue(BlessingNetwork.openFor(playerA, settlement)
                    && BlessingNetwork.openFor(playerB, settlement)
                    && BlessingNetwork.openFor(observer, settlement),
                "both players and the independent observer must open race " + trial
                    + " from the same authoritative offer");
            flushAndDrain(channelA, channelB, observerChannel);
            helper.assertTrue(payloadsA.size() == 1 && payloadsB.size() == 1
                    && observerPayloads.size() == 1,
                "race " + trial + " must decode exactly one OPEN payload per viewer");
            BlessingSnapshotPayload openA = payloadsA.getFirst();
            BlessingSnapshotPayload openB = payloadsB.getFirst();
            BlessingSnapshotPayload observerOpen = observerPayloads.getFirst();
            helper.assertTrue(openA.settlementId().equals(settlement.id)
                    && openB.settlementId().equals(settlement.id)
                    && observerOpen.settlementId().equals(settlement.id)
                    && !openA.sessionId().equals(openB.sessionId())
                    && !openA.sessionId().equals(observerOpen.sessionId())
                    && !openB.sessionId().equals(observerOpen.sessionId())
                    && openA.delivery() == BlessingSnapshotPayload.Delivery.OPEN
                    && openA.feedback() == BlessingSnapshotPayload.Feedback.NONE
                    && openA.revision() == ledger.revision()
                    && openB.revision() == openA.revision()
                    && observerOpen.revision() == openA.revision()
                    && openA.offerSerial() == ledger.offerSerial()
                    && openB.offerSerial() == openA.offerSerial()
                    && observerOpen.offerSerial() == openA.offerSerial(),
                "race " + trial + " viewers must share the offer identity but "
                    + "receive three independent server session UUIDs");

            BlessingActionPayload actionA = new BlessingActionPayload(
                openA.settlementId(), openA.sessionId(),
                BlessingActionPayload.Kind.CONFIRM, openA.revision(),
                openA.offerSerial(),
                BlessingId.WARDEN_OATH.wireId());
            BlessingActionPayload actionB = new BlessingActionPayload(
                openB.settlementId(), openB.sessionId(),
                BlessingActionPayload.Kind.CONFIRM, openB.revision(),
                openB.offerSerial(),
                BlessingId.HEARTHWARD.wireId());
            boolean aWins = (trial & 1) == 0;
            ServerPlayer winner = aWins ? playerA : playerB;
            ServerPlayer loser = aWins ? playerB : playerA;
            BlessingActionPayload winningAction = aWins ? actionA : actionB;
            BlessingActionPayload losingAction = aWins ? actionB : actionA;
            BlessingId winningBlessing = aWins
                ? BlessingId.WARDEN_OATH : BlessingId.HEARTHWARD;
            UUID winnerSessionId = aWins ? openA.sessionId() : openB.sessionId();
            UUID loserSessionId = aWins ? openB.sessionId() : openA.sessionId();
            int sealsBefore = countSealItems(playerA) + countSealItems(playerB)
                + countSealItems(observer) + countSealDrops(helper);

            payloadsA.clear();
            payloadsB.clear();
            observerPayloads.clear();
            BlessingNetwork.handle(winner, winningAction);
            flushAndDrain(channelA, channelB, observerChannel);
            List<BlessingSnapshotPayload> winnerPayloads = aWins
                ? payloadsA : payloadsB;
            List<BlessingSnapshotPayload> loserPayloads = aWins
                ? payloadsB : payloadsA;
            helper.assertTrue(winnerPayloads.size() == 1
                    && isTerminalFeedback(winnerPayloads.getFirst(), settlement.id,
                        BlessingSnapshotPayload.Feedback.ACCEPTED,
                        winningBlessing.wireId())
                    && winnerPayloads.getFirst().sessionId()
                        .equals(winnerSessionId),
                "race " + trial + " winner must decode one exact ACCEPTED result");
            helper.assertTrue(loserPayloads.size() == 1
                    && isTerminalFeedback(loserPayloads.getFirst(), settlement.id,
                        BlessingSnapshotPayload.Feedback.OTHER_PLAYER_CHOSE, -1)
                    && loserPayloads.getFirst().sessionId().equals(loserSessionId),
                "race " + trial + " losing viewer must immediately decode the "
                    + "shared OTHER_PLAYER_CHOSE terminal state");
            helper.assertTrue(observerPayloads.size() == 1
                    && isTerminalFeedback(observerPayloads.getFirst(), settlement.id,
                        BlessingSnapshotPayload.Feedback.OTHER_PLAYER_CHOSE, -1)
                    && observerPayloads.getFirst().sessionId()
                        .equals(observerOpen.sessionId()),
                "race " + trial + " independent observer must decode the same "
                    + "precise shared terminal state");

            // This is the second packet already in flight from the exact OPEN
            // snapshot. It must never mint a second seal, and its final response
            // must preserve the precise shared-race outcome rather than degrade
            // to a generic error after the winner's broadcast closed sessions.
            BlessingNetwork.handle(loser, losingAction);
            flushAndDrain(channelA, channelB, observerChannel);
            BlessingSnapshotPayload loserFinal = loserPayloads.getLast();
            helper.assertTrue(loserPayloads.size() == 2
                    && isTerminalFeedback(loserFinal, settlement.id,
                    BlessingSnapshotPayload.Feedback.OTHER_PLAYER_CHOSE, -1)
                    && loserFinal.sessionId().equals(loserSessionId),
                "race " + trial + " late losing action must converge on the "
                    + "precise OTHER_PLAYER_CHOSE result");

            // The observer's one-shot receipt is deliberately attacked with
            // alternating stale and unknown payloads. Neither may inherit the
            // precise co-op status merely because the observer once had a live
            // session, and neither may mutate or deliver anything.
            BlessingActionPayload forgedObserverAction = (trial & 1) == 0
                ? new BlessingActionPayload(observerOpen.settlementId(),
                    observerOpen.sessionId(), BlessingActionPayload.Kind.CONFIRM,
                    observerOpen.revision() - 1, observerOpen.offerSerial(),
                    BlessingId.THORNED_ROADS.wireId())
                : new BlessingActionPayload(observerOpen.settlementId(),
                    observerOpen.sessionId(), BlessingActionPayload.Kind.UNKNOWN,
                    observerOpen.revision(), observerOpen.offerSerial(),
                    Integer.MAX_VALUE);
            BlessingNetwork.handle(observer, forgedObserverAction);
            flushAndDrain(channelA, channelB, observerChannel);
            helper.assertTrue(observerPayloads.size() == 2
                    && isClosedFeedback(observerPayloads.getLast(), settlement.id,
                        BlessingSnapshotPayload.Feedback.UNAVAILABLE)
                    && observerPayloads.getLast().sessionId()
                        .equals(observerOpen.sessionId()),
                "race " + trial + " stale/unknown observer payload must fail "
                    + "closed instead of receiving OTHER_PLAYER_CHOSE");

            BlessingNetwork.handle(loser, losingAction);
            flushAndDrain(channelA, channelB, observerChannel);
            helper.assertTrue(loserPayloads.size() == 3
                    && isClosedFeedback(loserPayloads.getLast(), settlement.id,
                        BlessingSnapshotPayload.Feedback.UNAVAILABLE)
                    && loserPayloads.getLast().sessionId().equals(loserSessionId),
                "race " + trial + " replay after consuming the exact terminal "
                    + "receipt must fail closed");

            int sealsAfter = countSealItems(playerA) + countSealItems(playerB)
                + countSealItems(observer) + countSealDrops(helper);
            helper.assertTrue(sealsAfter == sealsBefore + 1
                    && countSealItems(winner) == (trial / 2) + 1
                    && countSealItems(loser) == (trial + 1) / 2
                    && countSealItems(observer) == 0,
                "race " + trial + " must mint exactly one physical seal across "
                    + "both hands, inventories and item entities");
            helper.assertTrue(ledger.earned() == trial + 1
                    && ledger.spent() == trial + 1
                    && ledger.offerSerial() == 0
                    && ledger.revision() == (trial + 1) * 2
                    && ledger.issuedCount(BlessingId.WARDEN_OATH)
                        == (trial + 2) / 2
                    && ledger.issuedCount(BlessingId.HEARTHWARD)
                        == (trial + 1) / 2
                    && ledger.issuedCount(BlessingId.THORNED_ROADS) == 0
                    && ledger.issuedCount(winningBlessing)
                        == countSealItems(winner)
                    && BlessingNetwork.viewerSessionCountForTest(
                        helper.getLevel().getServer()) == 0,
                "race " + trial + " ledger, issued counters, delivery and "
                    + "viewer sessions must converge after both packets");
        }

        helper.assertTrue(ledger.earned() == 20 && ledger.spent() == 20
                && ledger.issuedCount(BlessingId.WARDEN_OATH) == 10
                && ledger.issuedCount(BlessingId.HEARTHWARD) == 10,
            "20 shared races must reconcile to 20 spends, never 40");
        helper.assertTrue(countSealItems(playerA) == 10
                && countSealItems(playerB) == 10,
            "alternating race winners must each receive exactly ten physical seals");
        channelA.pipeline().remove("hearthstead_blessing_race_a");
        channelB.pipeline().remove("hearthstead_blessing_race_b");
        observerChannel.pipeline().remove("hearthstead_blessing_race_observer");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "blessing_session_binds_exact_snapshot")
    public void sessionProofBindsTheExactServerAuthoredOffer(GameTestHelper helper) {
        java.util.UUID settlementId = java.util.UUID.randomUUID();
        java.util.UUID sessionId = java.util.UUID.randomUUID();
        BlessingSnapshotPayload shown = new BlessingSnapshotPayload(settlementId,
            sessionId,
            "Sessionstead", 7, 3, 1, 2, 0,
            BlessingSnapshotPayload.Delivery.OPEN,
            BlessingSnapshotPayload.Feedback.NONE, -1);
        BlessingActionPayload exact = new BlessingActionPayload(settlementId,
            sessionId, BlessingActionPayload.Kind.CONFIRM,
            7, 3, BlessingId.WARDEN_OATH.wireId());
        helper.assertTrue(BlessingNetwork.actionMatchesSnapshot(exact, shown),
            "the exact session/revision/serial shown by the server must be proof");
        helper.assertTrue(!BlessingNetwork.actionMatchesSnapshot(
                new BlessingActionPayload(settlementId, sessionId,
                    BlessingActionPayload.Kind.CONFIRM, 8, 3,
                    BlessingId.WARDEN_OATH.wireId()), shown)
                && !BlessingNetwork.actionMatchesSnapshot(
                    new BlessingActionPayload(settlementId, sessionId,
                        BlessingActionPayload.Kind.CONFIRM, 7, 4,
                        BlessingId.WARDEN_OATH.wireId()), shown)
                && !BlessingNetwork.actionMatchesSnapshot(
                    new BlessingActionPayload(java.util.UUID.randomUUID(), sessionId,
                        BlessingActionPayload.Kind.CONFIRM, 7, 3,
                        BlessingId.WARDEN_OATH.wireId()), shown)
                && !BlessingNetwork.actionMatchesSnapshot(
                    new BlessingActionPayload(settlementId, UUID.randomUUID(),
                        BlessingActionPayload.Kind.CONFIRM, 7, 3,
                        BlessingId.WARDEN_OATH.wireId()), shown)
                && !BlessingNetwork.actionMatchesSnapshot(
                    new BlessingActionPayload(settlementId, sessionId,
                        BlessingActionPayload.Kind.CLOSE, 7, 3,
                        BlessingId.WARDEN_OATH.wireId()), shown),
            "guessed session/offer identity and CLOSE must fail confirmation proof");

        BlessingSnapshotPayload followUp = new BlessingSnapshotPayload(settlementId,
            sessionId,
            "Sessionstead", 8, 4, 2, 2, 0,
            BlessingSnapshotPayload.Delivery.UPDATE,
            BlessingSnapshotPayload.Feedback.ACCEPTED,
            BlessingId.WARDEN_OATH.wireId());
        helper.assertTrue(BlessingNetwork.actionMatchesSnapshot(
                new BlessingActionPayload(settlementId, sessionId,
                    BlessingActionPayload.Kind.CONFIRM, 8, 4,
                    BlessingId.HEARTHWARD.wireId()), followUp),
            "a server-authored follow-up remains valid after its brief result animation");
        BlessingSnapshotPayload terminal = new BlessingSnapshotPayload(settlementId,
            sessionId,
            "Sessionstead", 8, 0, 2, 2, 0,
            BlessingSnapshotPayload.Delivery.RESULT,
            BlessingSnapshotPayload.Feedback.OTHER_PLAYER_CHOSE, -1);
        helper.assertTrue(!BlessingNetwork.actionMatchesSnapshot(exact, terminal),
            "a terminal snapshot can never authorize another choice");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "blessing_session_reopen_close_and_forgery")
    public void delayedCloseAndForgedActionsCannotTouchReopenedSession(
            GameTestHelper helper) {
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = helper.getBlockEntity(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.values().removeIf(old -> helper.getBounds().contains(
            old.center.getX() + 0.5D, old.center.getY() + 0.5D,
            old.center.getZ() + 0.5D));
        Settlement settlement = new Settlement(UUID.randomUUID(), "Reopenstead",
            helper.absolutePos(hearthRel));
        settlement.radius = 12;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);
        helper.assertTrue(settlement.blessingState.grantOffer(),
            "fixture must own one unspent offer");

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getInventory().clearContent();
        NetworkRegistry.configureMockConnection(
            player.connection.getConnection());
        player.setPos(settlement.center.getX() + 0.5D,
            settlement.center.getY() + 1.0D, settlement.center.getZ() + 0.5D);
        EmbeddedChannel channel = (EmbeddedChannel) player.connection
            .getConnection().channel();
        List<BlessingSnapshotPayload> payloads = new java.util.ArrayList<>();
        installBlessingCapture(channel, "hearthstead_blessing_reopen", payloads);
        BlessingNetwork.clear(helper.getLevel().getServer());

        helper.assertTrue(BlessingNetwork.openFor(player, settlement),
            "first Hearth use must create a server session");
        flushAndDrain(channel);
        helper.assertTrue(payloads.size() == 1,
            "first Hearth use must emit one OPEN");
        BlessingSnapshotPayload firstOpen = payloads.getFirst();

        payloads.clear();
        helper.assertTrue(BlessingNetwork.openFor(player, settlement),
            "second Hearth use must replace the session even for the same offer");
        flushAndDrain(channel);
        helper.assertTrue(payloads.size() == 1,
            "reopen must emit exactly one replacement OPEN");
        BlessingSnapshotPayload secondOpen = payloads.getFirst();
        helper.assertTrue(firstOpen.settlementId().equals(secondOpen.settlementId())
                && firstOpen.revision() == secondOpen.revision()
                && firstOpen.offerSerial() == secondOpen.offerSerial()
                && !firstOpen.sessionId().equals(secondOpen.sessionId())
                && BlessingNetwork.hasExactViewerSessionForTest(player,
                    settlement.id, secondOpen.sessionId()),
            "identical offer identity must still receive a fresh opaque session");

        int earned = settlement.blessingState.earned();
        int spent = settlement.blessingState.spent();
        int revision = settlement.blessingState.revision();
        int serial = settlement.blessingState.offerSerial();

        payloads.clear();
        BlessingNetwork.handle(player, new BlessingActionPayload(
            settlement.id, firstOpen.sessionId(),
            BlessingActionPayload.Kind.CLOSE, Integer.MAX_VALUE,
            Integer.MAX_VALUE, BlessingId.WARDEN_OATH.wireId()));
        flushAndDrain(channel);
        helper.assertTrue(payloads.isEmpty()
                && BlessingNetwork.hasExactViewerSessionForTest(player,
                    settlement.id, secondOpen.sessionId()),
            "delayed CLOSE from the old screen must neither reply nor close its successor");

        BlessingActionPayload oldConfirm = new BlessingActionPayload(
            settlement.id, firstOpen.sessionId(),
            BlessingActionPayload.Kind.CONFIRM, firstOpen.revision(),
            firstOpen.offerSerial(), BlessingId.WARDEN_OATH.wireId());
        payloads.clear();
        BlessingNetwork.handle(player, oldConfirm);
        flushAndDrain(channel);
        helper.assertTrue(payloads.size() == 1
                && isClosedFeedback(payloads.getFirst(), settlement.id,
                    BlessingSnapshotPayload.Feedback.UNAVAILABLE)
                && payloads.getFirst().sessionId().equals(firstOpen.sessionId())
                && BlessingNetwork.hasExactViewerSessionForTest(player,
                    settlement.id, secondOpen.sessionId()),
            "old CONFIRM must fail on its old session without removing the new one");

        UUID forgedSessionId = UUID.randomUUID();
        payloads.clear();
        BlessingNetwork.handle(player, new BlessingActionPayload(
            settlement.id, forgedSessionId, BlessingActionPayload.Kind.CONFIRM,
            secondOpen.revision(), secondOpen.offerSerial(),
            BlessingId.HEARTHWARD.wireId()));
        flushAndDrain(channel);
        helper.assertTrue(payloads.size() == 1
                && payloads.getFirst().sessionId().equals(forgedSessionId)
                && payloads.getFirst().feedback()
                    == BlessingSnapshotPayload.Feedback.UNAVAILABLE
                && BlessingNetwork.hasExactViewerSessionForTest(player,
                    settlement.id, secondOpen.sessionId()),
            "guessed session UUID must receive only an inert mismatched result");

        payloads.clear();
        BlessingNetwork.handle(player, new BlessingActionPayload(
            settlement.id, secondOpen.sessionId(),
            BlessingActionPayload.Kind.UNKNOWN, secondOpen.revision(),
            secondOpen.offerSerial(), BlessingId.THORNED_ROADS.wireId()));
        flushAndDrain(channel);
        helper.assertTrue(payloads.size() == 1
                && payloads.getFirst().sessionId().equals(secondOpen.sessionId())
                && payloads.getFirst().delivery()
                    == BlessingSnapshotPayload.Delivery.UPDATE
                && payloads.getFirst().feedback()
                    == BlessingSnapshotPayload.Feedback.INVALID_CHOICE
                && BlessingNetwork.hasExactViewerSessionForTest(player,
                    settlement.id, secondOpen.sessionId()),
            "UNKNOWN must fail closed while leaving the exact active session recoverable");

        payloads.clear();
        BlessingNetwork.handle(player, new BlessingActionPayload(
            settlement.id, secondOpen.sessionId(),
            BlessingActionPayload.Kind.CLOSE, 0, 0, -1));
        flushAndDrain(channel);
        helper.assertTrue(payloads.isEmpty()
                && !BlessingNetwork.hasViewerSessionForTest(player, settlement.id)
                && settlement.blessingState.earned() == earned
                && settlement.blessingState.spent() == spent
                && settlement.blessingState.revision() == revision
                && settlement.blessingState.offerSerial() == serial
                && countSealItems(player) == 0 && countSealDrops(helper) == 0,
            "exact CLOSE may release only its session and can never spend or mint");
        channel.pipeline().remove("hearthstead_blessing_reopen");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "blessing_viewer_sessions_cleanup_without_future_traffic")
    public void viewerSessionsCleanUpOnLogoutAndServerStop(GameTestHelper helper) {
        ServerPlayer first = helper.makeMockServerPlayerInLevel();
        ServerPlayer second = helper.makeMockServerPlayerInLevel();
        UUID firstSettlement = UUID.randomUUID();
        UUID secondSettlement = UUID.randomUUID();
        BlessingNetwork.clear(helper.getLevel().getServer());
        BlessingNetwork.rememberForTest(first, new BlessingSnapshotPayload(
            firstSettlement, UUID.randomUUID(), "Firststead", 1, 1, 0, 0, 0,
            BlessingSnapshotPayload.Delivery.OPEN,
            BlessingSnapshotPayload.Feedback.NONE, -1));
        BlessingNetwork.rememberForTest(second, new BlessingSnapshotPayload(
            secondSettlement, UUID.randomUUID(), "Secondstead", 1, 1, 0, 0, 0,
            BlessingSnapshotPayload.Delivery.OPEN,
            BlessingSnapshotPayload.Feedback.NONE, -1));
        helper.assertTrue(BlessingNetwork.viewerSessionCountForTest(
                helper.getLevel().getServer()) == 2,
            "fixture must hold two independent bounded viewer sessions");

        CommonEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(first));
        helper.assertTrue(BlessingNetwork.viewerSessionCountForTest(
                helper.getLevel().getServer()) == 1
                && !BlessingNetwork.hasViewerSessionForTest(first, firstSettlement)
                && BlessingNetwork.hasViewerSessionForTest(second, secondSettlement),
            "logout cleanup must remove only that player's session immediately");

        UUID reconnectingId = UUID.randomUUID();
        UUID reconnectSettlement = UUID.randomUUID();
        ServerPlayer oldConnection = detachedPlayer(helper, reconnectingId,
            "reconnecting-old");
        ServerPlayer replacementConnection = detachedPlayer(helper, reconnectingId,
            "reconnecting-new");
        BlessingNetwork.rememberForTest(oldConnection, new BlessingSnapshotPayload(
            reconnectSettlement, UUID.randomUUID(), "Old reconnect",
            1, 1, 0, 0, 0,
            BlessingSnapshotPayload.Delivery.OPEN,
            BlessingSnapshotPayload.Feedback.NONE, -1));
        BlessingNetwork.rememberForTest(replacementConnection,
            new BlessingSnapshotPayload(reconnectSettlement, UUID.randomUUID(),
                "New reconnect",
                2, 2, 0, 0, 0, BlessingSnapshotPayload.Delivery.OPEN,
                BlessingSnapshotPayload.Feedback.NONE, -1));
        helper.assertTrue(BlessingNetwork.viewerSessionCountForTest(
                helper.getLevel().getServer()) == 2
                && BlessingNetwork.hasViewerSessionForTest(
                    replacementConnection, reconnectSettlement),
            "replacement connection must atomically own the shared UUID entry");

        CommonEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(
            oldConnection));
        helper.assertTrue(BlessingNetwork.viewerSessionCountForTest(
                helper.getLevel().getServer()) == 2
                && BlessingNetwork.hasViewerSessionForTest(
                    replacementConnection, reconnectSettlement),
            "delayed logout from the old same-UUID player object must not erase "
                + "the replacement connection's session");

        CommonEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(
            replacementConnection));
        helper.assertTrue(BlessingNetwork.viewerSessionCountForTest(
                helper.getLevel().getServer()) == 1
                && BlessingNetwork.hasViewerSessionForTest(second, secondSettlement),
            "logout from the identity that owns the UUID entry must remove it");

        CommonEvents.onServerStopped(new ServerStoppedEvent(
            helper.getLevel().getServer()));
        helper.assertTrue(BlessingNetwork.viewerSessionCountForTest(
                helper.getLevel().getServer()) == 0,
            "server-stop cleanup must release the entire inner UUID map");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "blessing_two_player_moved_viewer_gets_terminal")
    public void movedViewerGetsTerminalWhenOtherPlayerCommits(
            GameTestHelper helper) {
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = helper.getBlockEntity(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.values().removeIf(old -> helper.getBounds().contains(
            old.center.getX() + 0.5D, old.center.getY() + 0.5D,
            old.center.getZ() + 0.5D));
        Settlement settlement = new Settlement(UUID.randomUUID(), "Coopstead",
            helper.absolutePos(hearthRel));
        settlement.radius = 12;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);
        helper.assertTrue(settlement.blessingState.grantOffer(),
            "fixture must own one authoritative shared offer");

        ServerPlayer winner = helper.makeMockServerPlayerInLevel();
        ServerPlayer movedViewer = helper.makeMockServerPlayerInLevel();
        // Vanilla's GameTest helper creates an EmbeddedChannel directly and
        // therefore skips NeoForge's configuration-phase payload negotiation.
        // Use NeoForge's dedicated test hook so these mock peers advertise the
        // same registered play payloads as a real compatible client.
        NetworkRegistry.configureMockConnection(
            winner.connection.getConnection());
        NetworkRegistry.configureMockConnection(
            movedViewer.connection.getConnection());
        winner.setPos(settlement.center.getX() + 0.5D,
            settlement.center.getY() + 1.0D, settlement.center.getZ() + 0.5D);
        movedViewer.setPos(settlement.center.getX() + 1.5D,
            settlement.center.getY() + 1.0D, settlement.center.getZ() + 0.5D);
        helper.assertTrue(BlessingNetwork.openFor(winner, settlement)
                && BlessingNetwork.openFor(movedViewer, settlement),
            "both connected players must open the exact same offer");
        UUID winnerSessionId = BlessingNetwork.viewerSessionIdForTest(
            winner, settlement.id);
        UUID movedSessionId = BlessingNetwork.viewerSessionIdForTest(
            movedViewer, settlement.id);
        helper.assertTrue(winnerSessionId != null && movedSessionId != null
                && !winnerSessionId.equals(movedSessionId),
            "co-op viewers must own independent server-generated sessions");

        EmbeddedChannel winnerChannel = (EmbeddedChannel) winner.connection
            .getConnection().channel();
        EmbeddedChannel movedChannel = (EmbeddedChannel) movedViewer.connection
            .getConnection().channel();
        // Flush and discard both initial OPEN snapshots before installing the
        // terminal-payload probe. This keeps the assertion exact even if the
        // EmbeddedEventLoop defers the writes scheduled by openFor().
        winnerChannel.runPendingTasks();
        movedChannel.runPendingTasks();
        drainOutbound(winnerChannel);
        drainOutbound(movedChannel);
        List<BlessingSnapshotPayload> capturedBlessingPayloads =
            new java.util.ArrayList<>();
        movedChannel.pipeline().addLast("hearthstead_blessing_test_capture",
            new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext context, Object message,
                                  ChannelPromise promise) throws Exception {
                    if (message instanceof ClientboundCustomPayloadPacket packet
                        && packet.payload() instanceof BlessingSnapshotPayload payload) {
                        capturedBlessingPayloads.add(payload);
                    }
                    super.write(context, message, promise);
                }
            });
        movedViewer.setPos(settlement.center.getX() + 10.5D,
            settlement.center.getY() + 1.0D, settlement.center.getZ() + 0.5D);
        helper.assertTrue(BlessingNetwork.hasViewerSessionForTest(
                movedViewer, settlement.id)
                && BlessingNetwork.viewerRefusalForTest(movedViewer, settlement)
                    == BlessingSnapshotPayload.Feedback.TOO_FAR,
            "walking away must preserve the live session until the server sends "
                + "a precise terminal TOO_FAR result");

        BlessingNetwork.handle(winner, new BlessingActionPayload(settlement.id,
            winnerSessionId, BlessingActionPayload.Kind.CONFIRM,
            settlement.blessingState.revision(),
            settlement.blessingState.offerSerial(),
            BlessingId.WARDEN_OATH.wireId()));
        movedChannel.runPendingTasks();
        drainOutbound(movedChannel);
        helper.assertTrue(capturedBlessingPayloads.size() == 1
                && capturedBlessingPayloads.getFirst().settlementId()
                    .equals(settlement.id)
                && capturedBlessingPayloads.getFirst().delivery()
                    == BlessingSnapshotPayload.Delivery.RESULT
                && capturedBlessingPayloads.getFirst().feedback()
                    == BlessingSnapshotPayload.Feedback.TOO_FAR
                && capturedBlessingPayloads.getFirst().sessionId()
                    .equals(movedSessionId)
                && !BlessingNetwork.hasViewerSessionForTest(
                    movedViewer, settlement.id),
            "the moved co-op viewer must receive exactly one decoded RESULT/TOO_FAR "
                + "Blessing payload before its server session is removed");
        movedChannel.pipeline().remove("hearthstead_blessing_test_capture");
        helper.assertTrue(settlement.blessingState.spent() == 1
                && settlement.blessingState.offerSerial() == 0,
            "the nearby winner must still commit exactly one shared offer");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "blessing_delivery_hand")
    public void emptyMainHandReceivesExactChosenSeal(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getInventory().clearContent();

        PendingPlayerDeliveryLedger.DeliveryResult destination =
            deliverReservedSeal(helper, player, BlessingId.WARDEN_OATH);

        helper.assertTrue(destination.outcome()
                == PendingPlayerDeliveryLedger.Outcome.MAIN_HAND
                && player.getMainHandItem().is(ModItems.WARDEN_OATH_SEAL.get())
                && player.getMainHandItem().getCount() == 1,
            "an empty main hand must receive exactly one chosen seal");
        helper.assertTrue(countSealItems(player) == 1,
            "hand delivery must not duplicate into another inventory slot");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "blessing_delivery_offhand")
    public void occupiedMainHandUsesEmptyOffhand(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getInventory().clearContent();
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
            new ItemStack(Items.STONE));

        PendingPlayerDeliveryLedger.DeliveryResult destination =
            deliverReservedSeal(helper, player, BlessingId.HEARTHWARD);

        helper.assertTrue(destination.outcome()
                == PendingPlayerDeliveryLedger.Outcome.OFF_HAND
                && player.getMainHandItem().is(Items.STONE),
            "delivery must never overwrite the player's occupied hand");
        helper.assertTrue(player.getOffhandItem().is(ModItems.HEARTHWARD_SEAL.get())
                && player.getOffhandItem().getCount() == 1
                && count(player, ModItems.HEARTHWARD_SEAL.get()) == 1,
            "an occupied main hand should still place exactly one seal in an empty offhand");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "blessing_delivery_inventory")
    public void occupiedHandsFallBackToInventory(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getInventory().clearContent();
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
            new ItemStack(Items.STONE));
        player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,
            new ItemStack(Items.SHIELD));

        PendingPlayerDeliveryLedger.DeliveryResult destination =
            deliverReservedSeal(helper, player, BlessingId.HEARTHWARD);

        helper.assertTrue(destination.outcome()
                == PendingPlayerDeliveryLedger.Outcome.INVENTORY
                && player.getMainHandItem().is(Items.STONE)
                && player.getOffhandItem().is(Items.SHIELD),
            "inventory fallback must never overwrite either occupied hand");
        helper.assertTrue(count(player, ModItems.HEARTHWARD_SEAL.get()) == 1,
            "occupied hands should place exactly one seal in inventory");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "blessing_delivery_full_inventory")
    public void fullInventoryDropsExactlyOneSealAtPlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        // Pin the dangerous path explicitly: vanilla creative Inventory#add
        // may report a full inventory as handled by deleting its input.
        player.getAbilities().instabuild = true;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            player.getInventory().setItem(slot,
                new ItemStack(Items.COBBLESTONE, Items.COBBLESTONE.getDefaultMaxStackSize()));
        }
        AABB nearby = player.getBoundingBox().inflate(2.0D);
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, nearby).forEach(
            entity -> entity.discard());

        BlessingState state = new BlessingState();
        helper.assertTrue(state.grantOffer(),
            "fixture: one seal offer must be available");
        UUID deliveryId = UUID.randomUUID();
        PendingPlayerDeliveryLedger.Reservation reservation =
            PendingPlayerDeliveryLedger.reservation(helper.getLevel(), deliveryId,
                player, BlessingSealItem.stackFor(BlessingId.THORNED_ROADS));
        helper.assertTrue(reservation != null
                && state.compareAndCommit(state.revision(), state.offerSerial(),
                    BlessingId.THORNED_ROADS, reservation)
                    == BlessingState.CommitResult.ACCEPTED,
            "fixture: accepted offer and exact pending seal must commit together");
        CompoundTag sourceBeforeDelivery = state.writeNbt();

        Consumer<EntityJoinLevelEvent> rejectSealSpawn = event -> {
            if (event.getLevel() == helper.getLevel()
                && event.getEntity() instanceof ItemEntity item
                && item.getUUID().equals(deliveryId)) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,
            EntityJoinLevelEvent.class, rejectSealSpawn);
        PendingPlayerDeliveryLedger.DeliveryResult refused;
        try {
            refused = state.deliverPending(helper.getLevel(), player, deliveryId);
        } finally {
            NeoForge.EVENT_BUS.unregister(rejectSealSpawn);
        }
        helper.assertTrue(refused.outcome()
                == PendingPlayerDeliveryLedger.Outcome.PENDING
                && state.pendingDeliveryCount() == 1
                && helper.getLevel().getEntity(deliveryId) == null,
            "a rejected ItemEntity spawn must report pending and retain sole durable ownership");

        CompoundTag failedSave = state.writeNbt();
        BlessingState restarted = BlessingState.readNbt(failedSave);
        helper.assertTrue(!restarted.quarantined()
                && restarted.pendingDeliveryCount() == 1,
            "the accepted seal outbox must retain its exact row across reload");
        PendingPlayerDeliveryLedger.DeliveryResult destination =
            restarted.deliverPending(helper.getLevel(), player, deliveryId);

        helper.assertTrue(destination.outcome()
                == PendingPlayerDeliveryLedger.Outcome.DROP
                && restarted.pendingDeliveryCount() == 0
                && count(player, ModItems.THORNED_ROADS_SEAL.get()) == 0,
            "a full inventory must remain intact rather than hide the seal");
        List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(
            ItemEntity.class, nearby,
            entity -> entity.getItem().is(ModItems.THORNED_ROADS_SEAL.get()));
        int droppedCount = drops.stream().mapToInt(
            entity -> entity.getItem().getCount()).sum();
        helper.assertTrue(drops.size() == 1 && droppedCount == 1,
            "full inventory must create one visible one-item seal drop");

        // Opposite save-tear edge: Deferred already consumed its row and the
        // physical ItemEntity survived, but the source outbox reloaded from a
        // pre-delivery (worldFallback=false) save. Moving the physical item is
        // normal; its stable proof must be acknowledged without a second seal.
        ItemEntity physical = drops.getFirst();
        physical.setPos(physical.getX() + 1.25D, physical.getY(), physical.getZ());
        BlessingState staleSource = BlessingState.readNbt(sourceBeforeDelivery);
        PendingPlayerDeliveryLedger.DeliveryResult replay =
            staleSource.deliverPending(helper.getLevel(), player, deliveryId);
        List<ItemEntity> afterReplay = helper.getLevel().getEntitiesOfClass(
            ItemEntity.class, player.getBoundingBox().inflate(3.0D),
            entity -> entity.getItem().is(ModItems.THORNED_ROADS_SEAL.get()));
        helper.assertTrue(replay.outcome()
                == PendingPlayerDeliveryLedger.Outcome.ALREADY_DELIVERED
                && staleSource.pendingDeliveryCount() == 0
                && afterReplay.size() == 1
                && afterReplay.getFirst().getItem().getCount() == 1,
            "a surviving moved physical seal must consume stale source authority without duplication");

        DeferredItemMaterializationSavedData deferred =
            DeferredItemMaterializationSavedData.get(helper.getLevel());
        ItemStack exact = ItemStack.parseOptional(
            helper.getLevel().registryAccess(), reservation.stackTag());
        DeferredItemMaterializationSavedData.ItemEntityOptions options =
            new DeferredItemMaterializationSavedData.ItemEntityOptions(
                new CompoundTag(), 10, player.getUUID(), true, true);
        helper.assertTrue(deferred.completedExact(helper.getLevel(), deliveryId,
                reservation.x(), reservation.y(), reservation.z(), exact, options),
            "materialization must retain an exact bounded completion receipt");
        CompoundTag deferredSave = deferred.save(new CompoundTag(),
            helper.getLevel().registryAccess());
        DeferredItemMaterializationSavedData deferredRestart =
            DeferredItemMaterializationSavedData.load(deferredSave,
                helper.getLevel().registryAccess());
        helper.assertTrue(deferredRestart.completedExact(helper.getLevel(),
                deliveryId, reservation.x(), reservation.y(), reservation.z(),
                exact, options),
            "the exact completion receipt must survive Deferred SavedData reload");

        // Simulate the physical entity being unloaded: getEntity can no longer
        // prove it, but the permanent exact receipt can. Source replay must
        // acknowledge the completed transfer and never mint into the full bag.
        physical.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        BlessingState unloadedSource = BlessingState.readNbt(sourceBeforeDelivery);
        PendingPlayerDeliveryLedger.DeliveryResult unloadedReplay =
            unloadedSource.deliverPending(helper.getLevel(), player, deliveryId);
        helper.assertTrue(unloadedReplay.outcome()
                == PendingPlayerDeliveryLedger.Outcome.ALREADY_DELIVERED
                && unloadedSource.pendingDeliveryCount() == 0
                && helper.getLevel().getEntity(deliveryId) == null,
            "an unloaded completed item plus stale source save must not duplicate after retry");
        helper.succeed();
    }

    private static int countSealItems(ServerPlayer player) {
        return count(player, ModItems.WARDEN_OATH_SEAL.get())
            + count(player, ModItems.HEARTHWARD_SEAL.get())
            + count(player, ModItems.THORNED_ROADS_SEAL.get());
    }

    private static PendingPlayerDeliveryLedger.DeliveryResult deliverReservedSeal(
            GameTestHelper helper, ServerPlayer player, BlessingId blessing) {
        BlessingState state = new BlessingState();
        helper.assertTrue(state.grantOffer(),
            "delivery fixture must grant one authoritative offer");
        UUID deliveryId = UUID.randomUUID();
        PendingPlayerDeliveryLedger.Reservation reservation =
            PendingPlayerDeliveryLedger.reservation(helper.getLevel(), deliveryId,
                player, BlessingSealItem.stackFor(blessing));
        helper.assertTrue(reservation != null
                && state.compareAndCommit(state.revision(), state.offerSerial(),
                    blessing, reservation) == BlessingState.CommitResult.ACCEPTED,
            "delivery fixture must atomically reserve the exact accepted seal");
        return state.deliverPending(helper.getLevel(), player, deliveryId);
    }

    private static ServerPlayer detachedPlayer(GameTestHelper helper, UUID id,
                                                String name) {
        return new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
            new GameProfile(id, name), ClientInformation.createDefault());
    }

    private static int countSealDrops(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class,
            helper.getBounds(), entity ->
                entity.getItem().is(ModItems.WARDEN_OATH_SEAL.get())
                    || entity.getItem().is(ModItems.HEARTHWARD_SEAL.get())
                    || entity.getItem().is(ModItems.THORNED_ROADS_SEAL.get()))
            .stream().mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    private static boolean isTerminalFeedback(BlessingSnapshotPayload payload,
                                              UUID settlementId,
                                              BlessingSnapshotPayload.Feedback feedback,
                                              int blessingWireId) {
        return payload.settlementId().equals(settlementId)
            && payload.delivery() == BlessingSnapshotPayload.Delivery.RESULT
            && payload.feedback() == feedback
            && payload.feedbackBlessingWireId() == blessingWireId
            && payload.offerSerial() == 0;
    }

    private static boolean isClosedFeedback(BlessingSnapshotPayload payload,
                                            UUID settlementId,
                                            BlessingSnapshotPayload.Feedback feedback) {
        return payload.settlementId().equals(settlementId)
            && payload.delivery() == BlessingSnapshotPayload.Delivery.RESULT
            && payload.feedback() == feedback
            && payload.feedbackBlessingWireId() == -1;
    }

    private static void installBlessingCapture(
            EmbeddedChannel channel, String handlerName,
            List<BlessingSnapshotPayload> captured) {
        channel.pipeline().addLast(handlerName,
            new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext context, Object message,
                                  ChannelPromise promise) throws Exception {
                    if (message instanceof ClientboundCustomPayloadPacket packet
                        && packet.payload() instanceof BlessingSnapshotPayload payload) {
                        captured.add(payload);
                    }
                    super.write(context, message, promise);
                }
            });
    }

    private static void flushAndDrain(EmbeddedChannel... channels) {
        for (EmbeddedChannel channel : channels) {
            channel.runPendingTasks();
            drainOutbound(channel);
        }
    }

    private static void drainOutbound(EmbeddedChannel channel) {
        Object packet;
        while ((packet = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(packet);
        }
    }

    private static int count(ServerPlayer player, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
