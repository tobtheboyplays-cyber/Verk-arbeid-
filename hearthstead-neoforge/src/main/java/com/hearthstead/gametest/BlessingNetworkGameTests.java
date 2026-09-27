package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.event.ModBusEvents;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.network.BlessingActionPayload;
import com.hearthstead.network.BlessingSnapshotPayload;
import com.hearthstead.network.HearthMayorAction;
import com.hearthstead.network.HearthNetwork;
import com.hearthstead.network.ResearchActionPayload;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.journey.JourneyPresentationMode;
import com.hearthstead.settlement.journey.JourneyState;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.Objects;
import java.util.UUID;

/** Common-side wire and presentation-state contracts; loads no client class. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BlessingNetworkGameTests {

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "blessing_network_protocol_generation")
    public void incompatibleActionLayoutsRequireCurrentProtocol(GameTestHelper helper) {
        helper.assertTrue("20".equals(ModBusEvents.NETWORK_PROTOCOL),
            "Current incompatible payload layouts require matching peers, so they "
                + "must negotiate generation 20");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "blessing_network_wire_ids")
    public void unknownWireIdsFailClosedWithoutIndexing(GameTestHelper helper) {
        UUID settlementId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        BlessingActionPayload unknownBlessing = new BlessingActionPayload(
            settlementId, sessionId, BlessingActionPayload.Kind.CONFIRM,
            4, 2, Integer.MAX_VALUE);
        helper.assertTrue(unknownBlessing.choice().isEmpty(),
            "unknown Blessing wire ids must not decode or index an enum array");
        BlessingActionPayload known = new BlessingActionPayload(settlementId,
            sessionId, BlessingActionPayload.Kind.CONFIRM, 4, 2,
            BlessingId.THORNED_ROADS.wireId());
        helper.assertTrue(known.choice()
                .orElseThrow() == BlessingId.THORNED_ROADS,
            "a stable known Blessing wire id should decode");

        RegistryFriendlyByteBuf encoded = buffer(helper);
        BlessingActionPayload.CODEC.encode(encoded, known);
        helper.assertTrue(known.equals(BlessingActionPayload.CODEC.decode(encoded)),
            "CONFIRM must round-trip its settlement, session and stable kind id");
        BlessingActionPayload close = new BlessingActionPayload(settlementId,
            sessionId, BlessingActionPayload.Kind.CLOSE, 4, 2, -1);
        RegistryFriendlyByteBuf closeEncoded = buffer(helper);
        BlessingActionPayload.CODEC.encode(closeEncoded, close);
        BlessingActionPayload decodedClose = BlessingActionPayload.CODEC.decode(
            closeEncoded);
        helper.assertTrue(close.equals(decodedClose)
                && decodedClose.choice().isEmpty(),
            "CLOSE must round-trip explicitly and can never carry a choice");

        RegistryFriendlyByteBuf unknownKindWire = buffer(helper);
        unknownKindWire.writeUUID(settlementId);
        unknownKindWire.writeUUID(sessionId);
        unknownKindWire.writeVarInt(999);
        unknownKindWire.writeVarInt(4);
        unknownKindWire.writeVarInt(2);
        unknownKindWire.writeVarInt(BlessingId.WARDEN_OATH.wireId());
        BlessingActionPayload unknownKind = BlessingActionPayload.CODEC.decode(
            unknownKindWire);
        helper.assertTrue(unknownKind.kind() == BlessingActionPayload.Kind.UNKNOWN
                && unknownKind.choice().isEmpty(),
            "an unknown action kind must decode to inert UNKNOWN, never CONFIRM");
        helper.assertTrue(BlessingSnapshotPayload.Feedback.fromWireId(999)
                == BlessingSnapshotPayload.Feedback.UNAVAILABLE,
            "unknown feedback must become inert/unavailable");
        helper.assertTrue(BlessingSnapshotPayload.Delivery.fromWireId(999)
                == BlessingSnapshotPayload.Delivery.RESULT,
            "unknown delivery modes must be terminal and unable to open UI");

        BlockPos actionPos = new BlockPos(4, 5, 6);
        UUID target = UUID.randomUUID();
        HearthMayorAction knownMayor = new HearthMayorAction(actionPos,
            settlementId, 17, HearthMayorAction.Kind.APPOINT, target, 29);
        RegistryFriendlyByteBuf encodedMayor = buffer(helper);
        HearthMayorAction.CODEC.encode(encodedMayor, knownMayor);
        helper.assertTrue(knownMayor.equals(HearthMayorAction.CODEC.decode(encodedMayor)),
            "mayor actions must round-trip exact hearth, settlement and menu identity");
        RegistryFriendlyByteBuf unknownMayorKindWire = buffer(helper);
        unknownMayorKindWire.writeBlockPos(actionPos);
        unknownMayorKindWire.writeUUID(settlementId);
        unknownMayorKindWire.writeVarInt(17);
        unknownMayorKindWire.writeVarInt(Integer.MAX_VALUE);
        unknownMayorKindWire.writeUUID(target);
        unknownMayorKindWire.writeVarInt(29);
        helper.assertTrue(HearthMayorAction.CODEC.decode(unknownMayorKindWire).kind()
                == HearthMayorAction.Kind.UNKNOWN,
            "an unknown mayor action must be inert, never APPOINT");
        helper.assertTrue(HearthMayorAction.Kind.fromWireId(2)
                == HearthMayorAction.Kind.SKIP_JOURNEY,
            "the journey skip action must keep stable wire id 2");

        ResearchActionPayload knownResearch = new ResearchActionPayload(actionPos,
            ResearchActionPayload.Kind.START, 2, 31);
        RegistryFriendlyByteBuf encodedResearch = buffer(helper);
        ResearchActionPayload.CODEC.encode(encodedResearch, knownResearch);
        helper.assertTrue(knownResearch.equals(
                ResearchActionPayload.CODEC.decode(encodedResearch)),
            "research actions must round-trip stable kind ids");
        RegistryFriendlyByteBuf unknownResearchKindWire = buffer(helper);
        unknownResearchKindWire.writeBlockPos(actionPos);
        unknownResearchKindWire.writeVarInt(Integer.MAX_VALUE);
        unknownResearchKindWire.writeVarInt(0);
        unknownResearchKindWire.writeVarInt(31);
        helper.assertTrue(ResearchActionPayload.CODEC.decode(
                unknownResearchKindWire).kind() == ResearchActionPayload.Kind.UNKNOWN,
            "an unknown research action must be inert, never START or CANCEL");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "hearth_mayor_exact_open_menu_authority")
    public void mayorActionsRequireExactOpenHearthMenu(GameTestHelper helper) {
        // Server authority proof: proximity alone is not enough. Only an
        // action echoing the exact currently-open HearthMenu generation can
        // mutate the settlement.
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        BlockPos hearthRelative = new BlockPos(1, 1, 1);
        helper.setBlock(hearthRelative, ModBlocks.HEARTH.get());
        BlockPos hearthPos = helper.absolutePos(hearthRelative);
        UUID settlementId = UUID.randomUUID();
        Settlement settlement = new Settlement(settlementId, "Wireholm", hearthPos);
        settlement.radius = 4;
        settlement.journeyState = JourneyState.fresh(settlement.id);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel()
            .getBlockEntity(hearthPos);
        helper.assertTrue(hearth != null, "the exact-menu fixture must have a Hearth");
        hearth.bindSettlement(settlement.id);
        SettlerEntity candidate = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(2, 1, 2));
        candidate.setSettlerName("Eir");
        candidate.bindTo(settlement.id, settlement.center);
        settlement.putRecord(candidate.getUUID(), candidate.getSettlerName(),
            Profession.NONE);

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setPos(hearthPos.getX() + 0.5D, hearthPos.getY() + 0.5D,
            hearthPos.getZ() + 0.5D);
        // The Mayor office is retired, so authority is proven with the one
        // remaining mutating kind: a deliberate Founding Journey skip.
        HearthNetwork.handle(player, new HearthMayorAction(hearthPos,
            settlement.id, 0, HearthMayorAction.Kind.SKIP_JOURNEY,
            HearthMayorAction.NO_ID, settlement.journeyState.revision()));
        helper.assertTrue(settlement.journeyState.mode() == JourneyPresentationMode.ACTIVE,
            "standing near a Hearth without its exact open menu must grant no authority");

        player.openMenu(hearth, buf -> {
            buf.writeBlockPos(hearthPos);
            buf.writeUUID(settlement.id);
            buf.writeUtf(settlement.name);
        });
        helper.assertTrue(player.containerMenu instanceof HearthMenu,
            "fixture must open the real HearthMenu");
        HearthMenu menu = (HearthMenu) player.containerMenu;
        HearthNetwork.handle(player, new HearthMayorAction(hearthPos,
            settlement.id, menu.getContainerId() + 1,
            HearthMayorAction.Kind.SKIP_JOURNEY, HearthMayorAction.NO_ID,
            settlement.journeyState.revision()));
        HearthNetwork.handle(player, new HearthMayorAction(hearthPos.east(),
            settlement.id, menu.getContainerId(), HearthMayorAction.Kind.SKIP_JOURNEY,
            HearthMayorAction.NO_ID, settlement.journeyState.revision()));
        HearthNetwork.handle(player, new HearthMayorAction(hearthPos,
            UUID.randomUUID(), menu.getContainerId(),
            HearthMayorAction.Kind.SKIP_JOURNEY, HearthMayorAction.NO_ID,
            settlement.journeyState.revision()));
        HearthNetwork.handle(player, new HearthMayorAction(hearthPos,
            settlement.id, menu.getContainerId(), HearthMayorAction.Kind.UNKNOWN,
            HearthMayorAction.NO_ID, settlement.journeyState.revision()));
        helper.assertTrue(settlement.journeyState.mode() == JourneyPresentationMode.ACTIVE,
            "wrong menu, hearth, settlement and UNKNOWN kind must all be inert");

        int closedContainerId = menu.getContainerId();
        player.closeContainer();
        HearthNetwork.handle(player, new HearthMayorAction(hearthPos,
            settlement.id, closedContainerId, HearthMayorAction.Kind.SKIP_JOURNEY,
            HearthMayorAction.NO_ID, settlement.journeyState.revision()));
        helper.assertTrue(settlement.journeyState.mode() == JourneyPresentationMode.ACTIVE,
            "a packet replayed after its exact HearthMenu closed must be inert");

        player.openMenu(hearth, buf -> {
            buf.writeBlockPos(hearthPos);
            buf.writeUUID(settlement.id);
            buf.writeUtf(settlement.name);
        });
        helper.assertTrue(player.containerMenu instanceof HearthMenu,
            "fixture must reopen a fresh HearthMenu generation");
        menu = (HearthMenu) player.containerMenu;
        helper.assertTrue(menu.getContainerId() != closedContainerId,
            "a reopened HearthMenu must have a fresh server container id");
        HearthNetwork.handle(player, new HearthMayorAction(hearthPos,
            settlement.id, menu.getContainerId(), HearthMayorAction.Kind.APPOINT,
            candidate.getUUID(), Objects.hash(settlement.mayorId, settlement.mayorSince,
                settlement.mourningUntil)));
        helper.assertTrue(settlement.mayorId == null,
            "the Mayor office is retired: even the exact current menu must appoint nobody");

        int journeyRevision = settlement.journeyState.revision();
        HearthNetwork.handle(player, new HearthMayorAction(hearthPos,
            settlement.id, menu.getContainerId(),
            HearthMayorAction.Kind.SKIP_JOURNEY, UUID.randomUUID(), journeyRevision));
        HearthNetwork.handle(player, new HearthMayorAction(hearthPos,
            settlement.id, menu.getContainerId(),
            HearthMayorAction.Kind.SKIP_JOURNEY, HearthMayorAction.NO_ID,
            journeyRevision + 1));
        helper.assertTrue(settlement.journeyState.mode()
                == JourneyPresentationMode.ACTIVE
                && settlement.journeyState.revision() == journeyRevision,
            "non-canonical target and stale revision must not skip the journey");
        HearthNetwork.handle(player, new HearthMayorAction(hearthPos,
            settlement.id, menu.getContainerId(),
            HearthMayorAction.Kind.SKIP_JOURNEY, HearthMayorAction.NO_ID,
            journeyRevision));
        helper.assertTrue(settlement.journeyState.mode()
                == JourneyPresentationMode.SKIPPED
                && settlement.journeyState.revision() == 0,
            "the exact current menu and journey revision must authorize one deliberate skip");
        HearthNetwork.handle(player, new HearthMayorAction(hearthPos,
            settlement.id, menu.getContainerId(),
            HearthMayorAction.Kind.SKIP_JOURNEY, HearthMayorAction.NO_ID,
            journeyRevision));
        helper.assertTrue(settlement.journeyState.mode()
                == JourneyPresentationMode.SKIPPED
                && settlement.journeyState.revision() == 0,
            "replaying the old skip request must be inert and idempotent");
        player.closeContainer();
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "blessing_network_fixed_snapshot")
    public void fixedSnapshotNamesAndBoundsAllThreeIssuedCounts(GameTestHelper helper) {
        BlessingSnapshotPayload snapshot = new BlessingSnapshotPayload(
            UUID.randomUUID(), UUID.randomUUID(), "Wireholm", 7, 3,
            -40, 2, 400,
            BlessingSnapshotPayload.Delivery.OPEN,
            BlessingSnapshotPayload.Feedback.NONE, -1);
        helper.assertTrue(snapshot.issuedCount(BlessingId.WARDEN_OATH) == 0,
            "negative issued input must render as zero");
        helper.assertTrue(snapshot.issuedCount(BlessingId.HEARTHWARD) == 2,
            "the middle fixed field must map to Hearthward by name");
        helper.assertTrue(snapshot.issuedCount(BlessingId.THORNED_ROADS) == 400,
            "issued telemetry must not be mistaken for target rank III");
        helper.assertTrue(snapshot.hasPendingOffer(),
            "a NONE-feedback snapshot with a positive serial is actionable");

        BlessingSnapshotPayload tooFar = new BlessingSnapshotPayload(
            snapshot.settlementId(), snapshot.sessionId(),
            snapshot.settlementName(), 7, 3,
            0, 0, 0, BlessingSnapshotPayload.Delivery.RESULT,
            BlessingSnapshotPayload.Feedback.TOO_FAR, -1);
        helper.assertTrue(!tooFar.hasPendingOffer(),
            "a reach refusal must leave the client screen inert");
        BlessingSnapshotPayload manyPreviouslyIssued = new BlessingSnapshotPayload(
            snapshot.settlementId(), snapshot.sessionId(),
            snapshot.settlementName(), 7, 3,
            30, 30, 30,
            BlessingSnapshotPayload.Delivery.OPEN,
            BlessingSnapshotPayload.Feedback.NONE, -1);
        helper.assertTrue(manyPreviouslyIssued.hasPendingOffer()
                && manyPreviouslyIssued.mayOpenScreen(),
            "prior issued seals must never saturate a fresh physical choice");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "blessing_network_delivery_modes")
    public void onlyOpenCanCreateUiAndStackedFeedbackCanAdvance(
            GameTestHelper helper) {
        UUID settlementId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        BlessingSnapshotPayload opening = new BlessingSnapshotPayload(
            settlementId, sessionId, "Stackholm", 2, 1, 0, 0, 0,
            BlessingSnapshotPayload.Delivery.OPEN,
            BlessingSnapshotPayload.Feedback.NONE, -1);
        helper.assertTrue(opening.mayOpenScreen(),
            "a valid explicit OPEN snapshot should be able to create UI");

        BlessingSnapshotPayload acceptedWithNext = new BlessingSnapshotPayload(
            settlementId, sessionId, "Stackholm", 4, 2, 1, 0, 0,
            BlessingSnapshotPayload.Delivery.UPDATE,
            BlessingSnapshotPayload.Feedback.ACCEPTED,
            BlessingId.WARDEN_OATH.wireId());
        helper.assertTrue(!acceptedWithNext.mayOpenScreen(),
            "an accepted UPDATE must never create UI by itself");
        helper.assertTrue(acceptedWithNext.hasFollowUpOffer(),
            "accepted feedback should preserve a stacked follow-up offer");
        BlessingSnapshotPayload next = acceptedWithNext.asChoiceUpdate();
        helper.assertTrue(next.delivery() == BlessingSnapshotPayload.Delivery.UPDATE
                && next.feedback() == BlessingSnapshotPayload.Feedback.NONE
                && next.sessionId().equals(sessionId),
            "advancing should clear transient feedback without forging OPEN");
        helper.assertTrue(next.hasPendingOffer()
                && next.feedbackBlessing().isEmpty(),
            "the next shared offer should be actionable and never pre-armed");

        BlessingSnapshotPayload terminal = new BlessingSnapshotPayload(
            settlementId, sessionId, "Stackholm", 4, 0, 1, 0, 0,
            BlessingSnapshotPayload.Delivery.RESULT,
            BlessingSnapshotPayload.Feedback.ACCEPTED,
            BlessingId.WARDEN_OATH.wireId());
        helper.assertTrue(!terminal.hasFollowUpOffer()
                && terminal.asChoiceUpdate() == terminal,
            "a terminal result must not synthesize another choice");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "blessing_network_snapshot_session_codec")
    public void snapshotCodecPreservesOpaqueSessionIdentity(GameTestHelper helper) {
        BlessingSnapshotPayload snapshot = new BlessingSnapshotPayload(
            UUID.randomUUID(), UUID.randomUUID(), "Codecstead", 9, 4,
            2, 3, 5, BlessingSnapshotPayload.Delivery.UPDATE,
            BlessingSnapshotPayload.Feedback.STALE,
            BlessingId.HEARTHWARD.wireId());
        RegistryFriendlyByteBuf encoded = buffer(helper);
        BlessingSnapshotPayload.CODEC.encode(encoded, snapshot);
        helper.assertTrue(snapshot.equals(
                BlessingSnapshotPayload.CODEC.decode(encoded)),
            "every Blessing snapshot field, including session UUID, must round-trip");
        helper.succeed();
    }

    private static RegistryFriendlyByteBuf buffer(GameTestHelper helper) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),
            helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
    }
}
