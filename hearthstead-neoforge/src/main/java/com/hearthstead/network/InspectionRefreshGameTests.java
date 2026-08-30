package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.event.CommonEvents;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.Summons;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.TargetBlessingState;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Multiplayer proof for event-driven, update-only inspection refreshes. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class InspectionRefreshGameTests {

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "blessing_settler_two_player_exact_update_only_refresh")
    public void settlerAppliedRefreshesOnlyExactLiveSheets(GameTestHelper helper) {
        InspectionViewers.clear(helper.getLevel().getServer());
        Settlement settlement = settlement(helper, "Sheetstead");
        SettlerEntity target = settler(helper, settlement, "Yrsa", new BlockPos(5, 1, 5));
        SettlerEntity other = settler(helper, settlement, "Kari", new BlockPos(11, 1, 5));

        ServerPlayer first = viewer(helper, target.position());
        ServerPlayer second = viewer(helper, target.position());
        ServerPlayer unrelated = viewer(helper, other.position());
        ServerPlayer moved = viewer(helper, target.position());
        PayloadProbe firstPackets = new PayloadProbe(first, "settler_first");
        PayloadProbe secondPackets = new PayloadProbe(second, "settler_second");
        PayloadProbe unrelatedPackets = new PayloadProbe(unrelated, "settler_unrelated");
        PayloadProbe movedPackets = new PayloadProbe(moved, "settler_moved");

        SettlerNetwork.openFor(first, target);
        SettlerNetwork.openFor(second, target);
        SettlerNetwork.openFor(unrelated, other);
        SettlerNetwork.openFor(moved, target);
        flush(firstPackets, secondPackets, unrelatedPackets, movedPackets);
        helper.assertTrue(firstPackets.onlySettler().size() == 1
                && firstPackets.onlySettler().getFirst().delivery()
                    == SettlerSnapshotPayload.Delivery.OPEN
                && secondPackets.onlySettler().size() == 1,
            "explicit opens must author one OPEN snapshot for both exact viewers");
        SettlerSnapshotPayload firstOpen = firstPackets.onlySettler().getFirst();
        SettlerSnapshotPayload secondOpen = secondPackets.onlySettler().getFirst();
        clear(firstPackets, secondPackets, unrelatedPackets, movedPackets);

        moved.setPos(target.getX() + 10.0D, target.getY(), target.getZ());
        helper.assertTrue(target.applyBlessing(BlessingId.WARDEN_OATH)
                == TargetBlessingState.ApplyResult.APPLIED,
            "setup: the first physical rank must apply");
        flush(firstPackets, secondPackets, unrelatedPackets, movedPackets);

        assertSettlerUpdate(helper, firstPackets, target, firstOpen.sessionId(),
            1, "first exact viewer");
        assertSettlerUpdate(helper, secondPackets, target, secondOpen.sessionId(),
            1, "second exact viewer");
        helper.assertTrue(unrelatedPackets.onlySettler().isEmpty(),
            "a player inspecting another settler must receive no broadcast packet");
        helper.assertTrue(movedPackets.onlySettler().isEmpty()
                && !InspectionViewers.hasSettlerForTest(moved, target.getUUID()),
            "a moved viewer must be pruned without receiving a stale update");

        clear(firstPackets, secondPackets, unrelatedPackets, movedPackets);
        SettlerNetwork.handle(first, new SettlerActionPayload(target.getId(),
            target.getUUID(), firstOpen.sessionId(),
            SettlerActionPayload.Kind.CLOSE, firstOpen.revision()));
        helper.assertTrue(target.applyBlessing(BlessingId.WARDEN_OATH)
                == TargetBlessingState.ApplyResult.APPLIED,
            "setup: the second physical rank must apply");
        flush(firstPackets, secondPackets, unrelatedPackets, movedPackets);
        helper.assertTrue(firstPackets.onlySettler().isEmpty()
                && !InspectionViewers.hasSettlerForTest(first, target.getUUID()),
            "a closed exact sheet must receive zero later update packets");
        assertSettlerUpdate(helper, secondPackets, target, secondOpen.sessionId(), 2,
            "still-open co-op viewer");
        helper.assertTrue(unrelatedPackets.onlySettler().isEmpty(),
            "UPDATE delivery must not steal or replace an unrelated sheet");

        clear(firstPackets, secondPackets, unrelatedPackets, movedPackets);
        helper.assertTrue(target.applyBlessing(BlessingId.WARDEN_OATH)
                == TargetBlessingState.ApplyResult.APPLIED,
            "setup: settler rank III must apply");
        flush(firstPackets, secondPackets, unrelatedPackets, movedPackets);
        clear(firstPackets, secondPackets, unrelatedPackets, movedPackets);
        helper.assertTrue(target.applyBlessing(BlessingId.WARDEN_OATH)
                == TargetBlessingState.ApplyResult.MAXED
                && target.applyBlessing(null)
                    == TargetBlessingState.ApplyResult.INVALID,
            "MAXED and INVALID settler bindings must both refuse mutation");
        flush(firstPackets, secondPackets, unrelatedPackets, movedPackets);
        helper.assertTrue(secondPackets.onlySettler().isEmpty(),
            "MAXED/INVALID settler results must author zero refresh packets");

        firstPackets.close();
        secondPackets.close();
        unrelatedPackets.close();
        movedPackets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "blessing_plaque_two_player_exact_update_only_refresh")
    public void plaqueAppliedRefreshesOnlyExactRegisteredSheets(GameTestHelper helper) {
        InspectionViewers.clear(helper.getLevel().getServer());
        Settlement settlement = settlement(helper, "Runestead");
        PlaqueBlockEntity target = plaque(helper, settlement, BuildingType.HOUSE, 4, 4);
        PlaqueBlockEntity other = plaque(helper, settlement, BuildingType.WAREHOUSE, 11, 4);

        ServerPlayer first = viewer(helper, center(target.getBlockPos()));
        ServerPlayer second = viewer(helper, center(target.getBlockPos()));
        ServerPlayer unrelated = viewer(helper, center(other.getBlockPos()));
        ServerPlayer moved = viewer(helper, center(target.getBlockPos()));
        PayloadProbe firstPackets = new PayloadProbe(first, "plaque_first");
        PayloadProbe secondPackets = new PayloadProbe(second, "plaque_second");
        PayloadProbe unrelatedPackets = new PayloadProbe(unrelated, "plaque_unrelated");
        PayloadProbe movedPackets = new PayloadProbe(moved, "plaque_moved");

        PlaqueNetwork.openFor(first, target);
        PlaqueNetwork.openFor(second, target);
        PlaqueNetwork.openFor(unrelated, other);
        PlaqueNetwork.openFor(moved, target);
        flush(firstPackets, secondPackets, unrelatedPackets, movedPackets);
        helper.assertTrue(firstPackets.onlyPlaque().size() == 1
                && firstPackets.onlyPlaque().getFirst().delivery()
                    == PlaqueSnapshot.Delivery.OPEN,
            "the explicit plaque interaction must be the sole OPEN authority");
        PlaqueSnapshot firstOpen = firstPackets.onlyPlaque().getFirst();
        PlaqueSnapshot secondOpen = secondPackets.onlyPlaque().getFirst();
        clear(firstPackets, secondPackets, unrelatedPackets, movedPackets);

        moved.setPos(target.getBlockPos().getX() + 10.5D,
            target.getBlockPos().getY() + 0.5D,
            target.getBlockPos().getZ() + 0.5D);
        helper.assertTrue(target.applyBlessing(BlessingId.HEARTHWARD)
                == TargetBlessingState.ApplyResult.APPLIED,
            "setup: the registered plaque must apply one permanent rank");
        flush(firstPackets, secondPackets, unrelatedPackets, movedPackets);

        assertPlaqueUpdate(helper, firstPackets, target, firstOpen.sessionId(),
            1, "first exact viewer");
        assertPlaqueUpdate(helper, secondPackets, target, secondOpen.sessionId(),
            1, "second exact viewer");
        helper.assertTrue(unrelatedPackets.onlyPlaque().isEmpty(),
            "another plaque's sheet must receive zero packets");
        helper.assertTrue(movedPackets.onlyPlaque().isEmpty()
                && !InspectionViewers.hasPlaqueForTest(moved, target.getBlockPos()),
            "a moved plaque viewer must be pruned without a packet");

        clear(firstPackets, secondPackets, unrelatedPackets, movedPackets);
        PlaqueNetwork.handle(first, new PlaqueAction(target.getBlockPos(),
            firstOpen.buildingId(), firstOpen.sessionId(),
            PlaqueAction.Kind.CLOSE, PlaqueAction.NO_BUILDING,
            firstOpen.revision()));
        helper.assertTrue(target.applyBlessing(BlessingId.HEARTHWARD)
                == TargetBlessingState.ApplyResult.APPLIED,
            "setup: rank II must apply after one viewer closes");
        flush(firstPackets, secondPackets, unrelatedPackets, movedPackets);
        helper.assertTrue(firstPackets.onlyPlaque().isEmpty(),
            "the explicit CLOSE must prevent every later refresh packet");
        assertPlaqueUpdate(helper, secondPackets, target, secondOpen.sessionId(), 2,
            "still-open plaque viewer");

        // Fill to III, then prove non-APPLIED results are silent.
        clear(firstPackets, secondPackets, unrelatedPackets, movedPackets);
        helper.assertTrue(target.applyBlessing(BlessingId.HEARTHWARD)
                == TargetBlessingState.ApplyResult.APPLIED,
            "setup: rank III must apply");
        flush(firstPackets, secondPackets, unrelatedPackets, movedPackets);
        clear(firstPackets, secondPackets, unrelatedPackets, movedPackets);
        helper.assertTrue(target.applyBlessing(BlessingId.HEARTHWARD)
                == TargetBlessingState.ApplyResult.MAXED,
            "rank IV must be rejected as MAXED");
        flush(firstPackets, secondPackets, unrelatedPackets, movedPackets);
        helper.assertTrue(secondPackets.onlyPlaque().isEmpty(),
            "MAXED must author zero inspection updates");
        Building building = target.building(helper.getLevel());
        helper.assertTrue(building != null, "fixture: target building must still resolve");
        building.valid = false;
        helper.assertTrue(target.applyBlessing(BlessingId.WARDEN_OATH)
                == TargetBlessingState.ApplyResult.INVALID,
            "an invalid registered link must reject the seal");
        flush(firstPackets, secondPackets, unrelatedPackets, movedPackets);
        helper.assertTrue(secondPackets.onlyPlaque().isEmpty(),
            "INVALID must author zero inspection updates");

        firstPackets.close();
        secondPackets.close();
        unrelatedPackets.close();
        movedPackets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    /**
     * Structural performance proof, deliberately not a stopwatch test.
     *
     * <p>A housing sheet used to run the same AABB entity query from inside
     * {@code blockedReason} once for every candidate. At the supported
     * 100-settler scale, two co-op viewers therefore authored two snapshots
     * but performed two hundred identical entity queries. The probe is scoped
     * to this single real refresh and proves the cost is now one member
     * collection plus at most one housed query per matching viewer snapshot.
     */
    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "blessing_plaque_snapshot_queries_are_linear_at_100_settlers")
    public void plaqueSnapshotQueriesAreLinearAtOneHundredSettlersAndMultipleViewers(
            GameTestHelper helper) {
        InspectionViewers.clear(helper.getLevel().getServer());
        Settlement settlement = settlement(helper, "Hundredstead");
        PlaqueBlockEntity target = plaque(helper, settlement, BuildingType.HOUSE, 12, 12);

        List<SettlerEntity> members = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            int x = 2 + index % 10;
            int z = 2 + index / 10;
            members.add(settler(helper, settlement,
                String.format("Member%03d", index), new BlockPos(x, 1, z)));
        }
        helper.assertTrue(settlement.settlers.size() == 100,
            "fixture must register exactly 100 authoritative settlement members");

        ServerPlayer first = viewer(helper, center(target.getBlockPos()));
        ServerPlayer second = viewer(helper, center(target.getBlockPos()));
        PayloadProbe firstPackets = new PayloadProbe(first, "plaque_scale_first");
        PayloadProbe secondPackets = new PayloadProbe(second, "plaque_scale_second");
        PlaqueNetwork.openFor(first, target);
        PlaqueNetwork.openFor(second, target);
        flush(firstPackets, secondPackets);
        clear(firstPackets, secondPackets);

        PlaqueNetwork.SnapshotProbe queryProbe = new PlaqueNetwork.SnapshotProbe();
        InspectionViewers.refreshPlaque(helper.getLevel(), target, queryProbe);
        flush(firstPackets, secondPackets);

        helper.assertTrue(queryProbe.loadedMemberCollections() == 2,
            "two exact viewers must author two snapshots from exactly two member "
                + "collections, got " + queryProbe.loadedMemberCollections());
        helper.assertTrue(queryProbe.housedEntityQueries() == 2,
            "each housing snapshot may perform at most one housed entity query; "
                + "two viewers at 100 members produced "
                + queryProbe.housedEntityQueries());
        helper.assertTrue(queryProbe.housingCandidateDecisions() == 200,
            "the fixture must exercise all 100 real housing candidates for both "
                + "viewers; it exercised "
                + queryProbe.housingCandidateDecisions());
        assertHundredMemberSnapshot(helper, firstPackets, "first viewer");
        assertHundredMemberSnapshot(helper, secondPackets, "second viewer");

        // A link which is not ready must author the disabled rows without
        // querying loaded entities a second time for housing capacity.
        clear(firstPackets, secondPackets);
        setPlaqueState(target, PlaqueState.LINKED_INCOMPLETE);
        PlaqueNetwork.SnapshotProbe incompleteProbe = new PlaqueNetwork.SnapshotProbe();
        InspectionViewers.refreshPlaque(helper.getLevel(), target, incompleteProbe);
        flush(firstPackets, secondPackets);
        assertNotReadyHundredMemberSnapshot(helper, firstPackets,
            "incomplete first viewer");
        assertNotReadyHundredMemberSnapshot(helper, secondPackets,
            "incomplete second viewer");
        helper.assertTrue(incompleteProbe.housedEntityQueries() == 0
                && incompleteProbe.housingCandidateDecisions() == 0,
            "LINKED_INCOMPLETE snapshots must skip the housed AABB query");

        clear(firstPackets, secondPackets);
        setPlaqueState(target, PlaqueState.EMPTY);
        PlaqueNetwork.SnapshotProbe emptyProbe = new PlaqueNetwork.SnapshotProbe();
        InspectionViewers.refreshPlaque(helper.getLevel(), target, emptyProbe);
        flush(firstPackets, secondPackets);
        assertNotReadyHundredMemberSnapshot(helper, firstPackets,
            "empty first viewer");
        assertNotReadyHundredMemberSnapshot(helper, secondPackets,
            "empty second viewer");
        helper.assertTrue(emptyProbe.housedEntityQueries() == 0
                && emptyProbe.housingCandidateDecisions() == 0,
            "EMPTY snapshots must skip the housed AABB query");

        // Occupants-only is the other important lazy path: there is no
        // candidate decision whose answer could depend on housedCount.
        clear(firstPackets, secondPackets);
        setPlaqueState(target, PlaqueState.LINKED_VALID);
        Building targetBuilding = target.building(helper.getLevel());
        helper.assertTrue(targetBuilding != null,
            "fixture: linked plaque must still resolve its building");
        for (SettlerEntity member : members) {
            targetBuilding.workers.add(member.getUUID());
        }
        PlaqueNetwork.SnapshotProbe occupantsOnlyProbe =
            new PlaqueNetwork.SnapshotProbe();
        InspectionViewers.refreshPlaque(helper.getLevel(), target,
            occupantsOnlyProbe);
        flush(firstPackets, secondPackets);
        assertOccupantsOnlyHundredMemberSnapshot(helper, firstPackets,
            "occupants-only first viewer");
        assertOccupantsOnlyHundredMemberSnapshot(helper, secondPackets,
            "occupants-only second viewer");
        helper.assertTrue(occupantsOnlyProbe.housedEntityQueries() == 0
                && occupantsOnlyProbe.housingCandidateDecisions() == 0,
            "occupants-only snapshots must skip the housed AABB query");

        firstPackets.close();
        secondPackets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "blessing_inspection_sessions_bounded_ttl_logout_stop_cleanup")
    public void sessionsAreBoundedAndCleanedWithoutTickScan(GameTestHelper helper) {
        InspectionViewers.clear(helper.getLevel().getServer());
        Settlement settlement = settlement(helper, "Cleanstead");
        SettlerEntity settler = settler(helper, settlement, "Liv", new BlockPos(5, 1, 5));
        PlaqueBlockEntity plaque = plaque(helper, settlement, BuildingType.HOUSE, 10, 8);
        ServerPlayer first = viewer(helper, settler.position());
        ServerPlayer second = viewer(helper, settler.position());

        SettlerNetwork.openFor(first, settler);
        SettlerNetwork.openFor(second, settler);
        helper.assertTrue(InspectionViewers.sessionCountForTest(
                helper.getLevel().getServer()) == 2,
            "two players may hold two sessions, but never more than one each");
        first.setPos(center(plaque.getBlockPos()));
        PlaqueNetwork.openFor(first, plaque);
        helper.assertTrue(InspectionViewers.sessionCountForTest(
                helper.getLevel().getServer()) == 2
                && !InspectionViewers.hasSettlerForTest(first, settler.getUUID())
                && InspectionViewers.hasPlaqueForTest(first, plaque.getBlockPos()),
            "opening another screen must replace, not append, that player's session");

        long now = helper.getLevel().getGameTime();
        InspectionViewers.ageForTest(first,
            now - InspectionViewers.SESSION_TTL_TICKS - 1L);
        InspectionViewers.pruneForTest(helper.getLevel().getServer(), now);
        helper.assertTrue(InspectionViewers.sessionCountForTest(
                helper.getLevel().getServer()) == 1,
            "the event-driven TTL prune must release a lost-close safety entry");

        CommonEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(second));
        helper.assertTrue(InspectionViewers.sessionCountForTest(
                helper.getLevel().getServer()) == 0,
            "logout must eagerly release only that player's remaining entry");
        SettlerNetwork.openFor(first, settler);
        CommonEvents.onServerStopped(new ServerStoppedEvent(
            helper.getLevel().getServer()));
        helper.assertTrue(InspectionViewers.sessionCountForTest(
                helper.getLevel().getServer()) == 0,
            "server stop must release the entire server-scoped map");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "inspection_settler_actions_require_exact_live_session")
    public void settlerActionsRequireExactLiveSession(GameTestHelper helper) {
        InspectionViewers.clear(helper.getLevel().getServer());
        Settlement settlement = settlement(helper, "Lockstead");
        SettlerEntity target = settler(helper, settlement, "Runa",
            new BlockPos(7, 1, 7));
        ServerPlayer player = viewer(helper, target.position());
        PayloadProbe packets = new PayloadProbe(player, "settler_authority");

        SettlerNetwork.handle(player, new SettlerActionPayload(target.getId(),
            target.getUUID(), UUID.randomUUID(),
            SettlerActionPayload.Kind.APPOINT, 0));
        helper.assertTrue(settlement.mayorId == null
                && InspectionViewers.sessionCountForTest(player.server) == 0,
            "a direct packet without an opened sheet must not appoint a mayor");

        UUID firstSession = SettlerNetwork.openFor(player, target);
        flush(packets);
        SettlerSnapshotPayload firstOpen = packets.onlySettler().getLast();
        helper.assertTrue(firstOpen.sessionId().equals(firstSession),
            "OPEN must carry the exact opaque server session it registered");
        clear(packets);

        SettlerNetwork.handle(player, new SettlerActionPayload(target.getId(),
            target.getUUID(), UUID.randomUUID(),
            SettlerActionPayload.Kind.APPOINT, firstOpen.revision()));
        flush(packets);
        helper.assertTrue(settlement.mayorId == null
                && packets.onlySettler().isEmpty()
                && InspectionViewers.hasSettlerForTest(player, target.getUUID()),
            "a forged session must be silent, non-mutating and must not destroy the real sheet");

        UUID secondSession = SettlerNetwork.openFor(player, target);
        flush(packets);
        SettlerSnapshotPayload secondOpen = packets.onlySettler().getLast();
        clear(packets);
        SettlerNetwork.handle(player, new SettlerActionPayload(target.getId(),
            target.getUUID(), firstSession, SettlerActionPayload.Kind.CLOSE,
            firstOpen.revision()));
        helper.assertTrue(InspectionViewers.hasSettlerForTest(player, target.getUUID()),
            "a delayed CLOSE from an older session must not close the newer sheet");

        SettlerNetwork.handle(player, new SettlerActionPayload(target.getId(),
            UUID.randomUUID(), secondSession, SettlerActionPayload.Kind.APPOINT,
            secondOpen.revision()));
        helper.assertTrue(settlement.mayorId == null,
            "a reused runtime entity id with the wrong stable settler UUID must be inert");

        UUID expiredSession = SettlerNetwork.openFor(player, target);
        flush(packets);
        SettlerSnapshotPayload expiredOpen = packets.onlySettler().getLast();
        clear(packets);
        long now = helper.getLevel().getGameTime();
        InspectionViewers.ageForTest(player,
            now - InspectionViewers.SESSION_TTL_TICKS - 1L);
        SettlerNetwork.handle(player, new SettlerActionPayload(target.getId(),
            target.getUUID(), expiredSession, SettlerActionPayload.Kind.APPOINT,
            expiredOpen.revision()));
        helper.assertTrue(settlement.mayorId == null
                && !InspectionViewers.hasSettlerForTest(player, target.getUUID()),
            "an expired sheet must not authorize a delayed mutation");

        UUID liveSession = SettlerNetwork.openFor(player, target);
        flush(packets);
        SettlerSnapshotPayload liveOpen = packets.onlySettler().getLast();
        clear(packets);
        SettlerNetwork.handle(player, new SettlerActionPayload(target.getId(),
            target.getUUID(), liveSession, SettlerActionPayload.Kind.APPOINT,
            liveOpen.revision()));
        flush(packets);
        helper.assertTrue(target.getUUID().equals(settlement.mayorId),
            "the exact current session, stable target and revision must still work normally");
        SettlerNetwork.handle(player, new SettlerActionPayload(target.getId(),
            target.getUUID(), liveSession, SettlerActionPayload.Kind.CLOSE,
            liveOpen.revision()));
        helper.assertTrue(!InspectionViewers.hasSettlerForTest(player, target.getUUID()),
            "the exact current CLOSE must release the sheet");

        packets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "inspection_plaque_actions_require_exact_live_session")
    public void plaqueActionsRequireExactLiveSession(GameTestHelper helper) {
        InspectionViewers.clear(helper.getLevel().getServer());
        Settlement settlement = settlement(helper, "Sealstead");
        PlaqueBlockEntity target = plaque(helper, settlement,
            BuildingType.FARMHOUSE, 8, 8);
        SettlerEntity candidate = settler(helper, settlement, "Eira",
            new BlockPos(5, 1, 5));
        Building building = target.building(helper.getLevel());
        helper.assertTrue(building != null && building.workers.isEmpty()
                && candidate.getProfession() == Profession.NONE,
            "fixture must provide an unemployed settler and an empty real workplace");
        ServerPlayer player = viewer(helper, center(target.getBlockPos()));
        PayloadProbe packets = new PayloadProbe(player, "plaque_authority");
        // The production player path now requires the physical emblem that
        // matches the workplace. Keep this test about session authority by
        // giving it the legitimate Farmer authorization up front; every
        // forged/expired action below must leave that exact item untouched.
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
            new ItemStack(ModItems.FARMER_EMBLEM.get()));

        int untouchedRevision = target.revision();
        for (PlaqueAction.Kind kind : List.of(PlaqueAction.Kind.ASSIGN,
                PlaqueAction.Kind.EVICT, PlaqueAction.Kind.REFRESH,
                PlaqueAction.Kind.SUMMON)) {
            PlaqueNetwork.handle(player, new PlaqueAction(target.getBlockPos(),
                target.buildingId(), UUID.randomUUID(), kind,
                candidate.getUUID(), untouchedRevision));
        }
        helper.assertTrue(!building.workers.contains(candidate.getUUID())
                && !Summons.active(candidate)
                && target.revision() == untouchedRevision
                && player.getMainHandItem().is(ModItems.FARMER_EMBLEM.get())
                && InspectionViewers.sessionCountForTest(player.server) == 0,
            "every plaque mutation kind must be inert and preserve the emblem "
                + "without an opened exact sheet");

        UUID firstSession = PlaqueNetwork.openFor(player, target);
        flush(packets);
        PlaqueSnapshot firstOpen = packets.onlyPlaque().getLast();
        clear(packets);
        PlaqueNetwork.handle(player, new PlaqueAction(target.getBlockPos(),
            target.buildingId(), UUID.randomUUID(), PlaqueAction.Kind.ASSIGN,
            candidate.getUUID(), firstOpen.revision()));
        flush(packets);
        helper.assertTrue(!building.workers.contains(candidate.getUUID())
                && packets.onlyPlaque().isEmpty()
                && InspectionViewers.hasPlaqueForTest(player, target.getBlockPos()),
            "a forged plaque session must not assign a resident or close the real sheet");

        UUID secondSession = PlaqueNetwork.openFor(player, target);
        flush(packets);
        PlaqueSnapshot secondOpen = packets.onlyPlaque().getLast();
        clear(packets);
        PlaqueNetwork.handle(player, new PlaqueAction(target.getBlockPos(),
            target.buildingId(), firstSession, PlaqueAction.Kind.CLOSE,
            PlaqueAction.NO_BUILDING, firstOpen.revision()));
        helper.assertTrue(InspectionViewers.hasPlaqueForTest(player, target.getBlockPos()),
            "a delayed old plaque CLOSE must not erase the replacement session");

        PlaqueNetwork.handle(player, new PlaqueAction(target.getBlockPos(),
            UUID.randomUUID(), secondSession, PlaqueAction.Kind.ASSIGN,
            candidate.getUUID(), secondOpen.revision()));
        helper.assertTrue(!building.workers.contains(candidate.getUUID()),
            "a matching position with the wrong stable building UUID must be inert");

        UUID expiredSession = PlaqueNetwork.openFor(player, target);
        flush(packets);
        PlaqueSnapshot expiredOpen = packets.onlyPlaque().getLast();
        clear(packets);
        long now = helper.getLevel().getGameTime();
        InspectionViewers.ageForTest(player,
            now - InspectionViewers.SESSION_TTL_TICKS - 1L);
        PlaqueNetwork.handle(player, new PlaqueAction(target.getBlockPos(),
            target.buildingId(), expiredSession, PlaqueAction.Kind.ASSIGN,
            candidate.getUUID(), expiredOpen.revision()));
        helper.assertTrue(!building.workers.contains(candidate.getUUID())
                && !InspectionViewers.hasPlaqueForTest(player, target.getBlockPos()),
            "an expired plaque sheet must not authorize a delayed assignment");

        UUID liveSession = PlaqueNetwork.openFor(player, target);
        flush(packets);
        PlaqueSnapshot liveOpen = packets.onlyPlaque().getLast();
        clear(packets);
        PlaqueNetwork.handle(player, new PlaqueAction(target.getBlockPos(),
            target.buildingId(), liveSession, PlaqueAction.Kind.ASSIGN,
            candidate.getUUID(), liveOpen.revision()));
        flush(packets);
        helper.assertTrue(!building.workers.contains(candidate.getUUID()),
            "even an exact current plaque session must not restore the removed "
                + "workplace Hire path");
        helper.assertTrue(player.getMainHandItem().is(ModItems.FARMER_EMBLEM.get())
                && player.getMainHandItem().getCount() == 1,
            "the rejected legacy work ASSIGN must preserve its Farmer emblem");
        helper.assertTrue(!packets.onlyPlaque().isEmpty()
                && packets.onlyPlaque().getLast().candidates().isEmpty(),
            "the authoritative rejection must refresh the old client with no candidates");

        PlaqueNetwork.handle(player, new PlaqueAction(target.getBlockPos(),
            target.buildingId(), liveSession, PlaqueAction.Kind.CLOSE,
            PlaqueAction.NO_BUILDING, target.revision()));
        PlaqueNetwork.handle(player, new PlaqueAction(target.getBlockPos(),
            target.buildingId(), UUID.randomUUID(), PlaqueAction.Kind.EVICT,
            candidate.getUUID(), target.revision()));
        helper.assertTrue(!building.workers.contains(candidate.getUUID()),
            "an EVICT sent after exact close must not invent employment state");

        UUID unknownSession = PlaqueNetwork.openFor(player, target);
        flush(packets);
        clear(packets);
        PlaqueNetwork.handle(player, new PlaqueAction(target.getBlockPos(),
            target.buildingId(), unknownSession, PlaqueAction.Kind.UNKNOWN,
            candidate.getUUID(), target.revision()));
        helper.assertTrue(!building.workers.contains(candidate.getUUID())
                && InspectionViewers.hasPlaqueForTest(player, target.getBlockPos()),
            "UNKNOWN must be inert and must not corrupt the current valid session");

        packets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "inspection_unknown_wire_actions_fail_closed")
    public void unknownInspectionWireActionsFailClosed(GameTestHelper helper) {
        UUID targetId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        RegistryFriendlyByteBuf settlerWire = buffer(helper);
        settlerWire.writeVarInt(71);
        settlerWire.writeUUID(targetId);
        settlerWire.writeUUID(sessionId);
        settlerWire.writeVarInt(999_999);
        settlerWire.writeVarInt(13);
        SettlerActionPayload settlerAction = SettlerActionPayload.CODEC.decode(settlerWire);
        helper.assertTrue(settlerAction.kind() == SettlerActionPayload.Kind.UNKNOWN
                && settlerAction.settlerId().equals(targetId)
                && settlerAction.sessionId().equals(sessionId),
            "an unknown settler action id must decode to inert UNKNOWN without shifting identity fields");

        BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
        UUID buildingId = UUID.randomUUID();
        RegistryFriendlyByteBuf plaqueWire = buffer(helper);
        plaqueWire.writeBlockPos(pos);
        plaqueWire.writeUUID(buildingId);
        plaqueWire.writeUUID(sessionId);
        plaqueWire.writeVarInt(Integer.MAX_VALUE);
        plaqueWire.writeUUID(targetId);
        plaqueWire.writeVarInt(17);
        PlaqueAction plaqueAction = PlaqueAction.CODEC.decode(plaqueWire);
        helper.assertTrue(plaqueAction.kind() == PlaqueAction.Kind.UNKNOWN
                && plaqueAction.pos().equals(pos)
                && plaqueAction.buildingId().equals(buildingId)
                && plaqueAction.sessionId().equals(sessionId)
                && plaqueAction.target().equals(targetId),
            "an unknown plaque action id must decode to inert UNKNOWN without becoming a mutation");
        helper.assertTrue(new SettlerActionPayload(1, null, null, null, 0).kind()
                == SettlerActionPayload.Kind.UNKNOWN
                && new PlaqueAction(pos, null, null, null, null, 0).kind()
                == PlaqueAction.Kind.UNKNOWN,
            "null in-memory action fields must also canonicalize to fail-closed sentinels");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "inspection_plaque_session_tracks_authorized_link_transition")
    public void plaqueSessionTracksAuthorizedLinkTransition(GameTestHelper helper) {
        InspectionViewers.clear(helper.getLevel().getServer());
        Settlement settlement = settlement(helper, "Linkstead");
        PlaqueBlockEntity plaque = plaque(helper, settlement,
            BuildingType.FARMHOUSE, 8, 8);
        ServerPlayer player = viewer(helper, center(plaque.getBlockPos()));
        PayloadProbe packets = new PayloadProbe(player, "plaque_link_transition");
        UUID oldBuildingId = plaque.buildingId();
        UUID sessionId = PlaqueNetwork.openFor(player, plaque);
        flush(packets);
        PlaqueSnapshot opening = packets.onlyPlaque().getLast();
        clear(packets);
        helper.assertTrue(oldBuildingId.equals(opening.buildingId())
                && InspectionViewers.authorizePlaque(player, plaque,
                    oldBuildingId, sessionId)
                    == InspectionViewers.PlaqueAuthorization.AUTHORIZED,
            "fixture must begin with one exact authorized plaque identity");

        UUID serverAuthoredReplacement = UUID.randomUUID();
        setPlaqueBuildingId(plaque, serverAuthoredReplacement);
        helper.assertTrue(InspectionViewers.updatePlaqueIdentityAfterAuthorizedMutation(
                player, plaque, sessionId),
            "an already-authorized same-BE refresh must atomically adopt its new building id");
        helper.assertTrue(InspectionViewers.authorizePlaque(player, plaque,
                oldBuildingId, sessionId)
                == InspectionViewers.PlaqueAuthorization.STALE
                && InspectionViewers.authorizePlaque(player, plaque,
                    serverAuthoredReplacement, sessionId)
                    == InspectionViewers.PlaqueAuthorization.AUTHORIZED,
            "the old id must become stale without destroying the new authoritative session");

        packets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    private static void assertSettlerUpdate(GameTestHelper helper, PayloadProbe probe,
                                            SettlerEntity target, UUID sessionId, int rank,
                                            String viewer) {
        List<SettlerSnapshotPayload> packets = probe.onlySettler();
        helper.assertTrue(packets.size() == 1
                && packets.getFirst().entityId() == target.getId()
                && packets.getFirst().settlerId().equals(target.getUUID())
                && packets.getFirst().sessionId().equals(sessionId)
                && packets.getFirst().delivery() == SettlerSnapshotPayload.Delivery.UPDATE
                && packets.getFirst().blessingRank(BlessingId.WARDEN_OATH) == rank,
            viewer + " must receive exactly one authoritative UPDATE for the exact settler");
    }

    private static void assertPlaqueUpdate(GameTestHelper helper, PayloadProbe probe,
                                          PlaqueBlockEntity target, UUID sessionId, int rank,
                                          String viewer) {
        List<PlaqueSnapshot> packets = probe.onlyPlaque();
        helper.assertTrue(packets.size() == 1
                && packets.getFirst().pos().equals(target.getBlockPos())
                && packets.getFirst().buildingId().equals(target.buildingId())
                && packets.getFirst().sessionId().equals(sessionId)
                && packets.getFirst().delivery() == PlaqueSnapshot.Delivery.UPDATE
                && packets.getFirst().blessingRank(BlessingId.HEARTHWARD) == rank,
            viewer + " must receive exactly one authoritative UPDATE for the exact plaque");
    }

    private static void assertHundredMemberSnapshot(GameTestHelper helper,
                                                     PayloadProbe probe,
                                                     String viewer) {
        List<PlaqueSnapshot> packets = probe.onlyPlaque();
        helper.assertTrue(packets.size() == 1
                && packets.getFirst().delivery() == PlaqueSnapshot.Delivery.UPDATE,
            viewer + " must receive exactly one update-only snapshot");
        List<PlaqueSnapshot.Candidate> candidates = packets.getFirst().candidates();
        helper.assertTrue(candidates.size() == 100,
            viewer + " must retain all 100 candidates, got " + candidates.size());
        helper.assertTrue(candidates.getFirst().name().equals("Member000")
                && candidates.getLast().name().equals("Member099"),
            viewer + " must preserve authoritative member/wire order");
        for (PlaqueSnapshot.Candidate candidate : candidates) {
            helper.assertTrue(candidate.blockedReason()
                    .equals("hearthstead.plaque.blocked.full"),
                viewer + " must exercise the linked/full decision for "
                    + candidate.name() + ", got " + candidate.blockedReason());
        }
    }

    private static void assertNotReadyHundredMemberSnapshot(GameTestHelper helper,
                                                             PayloadProbe probe,
                                                             String viewer) {
        List<PlaqueSnapshot> packets = probe.onlyPlaque();
        helper.assertTrue(packets.size() == 1
                && packets.getFirst().candidates().size() == 100,
            viewer + " must keep all 100 candidates without a housing query");
        for (PlaqueSnapshot.Candidate candidate : packets.getFirst().candidates()) {
            helper.assertTrue(candidate.blockedReason()
                    .equals("hearthstead.plaque.blocked.not_ready"),
                viewer + " must fail closed as not-ready");
        }
    }

    private static void assertOccupantsOnlyHundredMemberSnapshot(
            GameTestHelper helper, PayloadProbe probe, String viewer) {
        List<PlaqueSnapshot> packets = probe.onlyPlaque();
        helper.assertTrue(packets.size() == 1
                && packets.getFirst().occupants().size() == 100
                && packets.getFirst().candidates().isEmpty(),
            viewer + " must author 100 occupants and zero candidates");
    }

    private static Settlement settlement(GameTestHelper helper, String name) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), name,
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 20;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement settlement,
                                         String name, BlockPos relativePos) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), relativePos);
        settler.setSettlerName(name);
        settler.bindTo(settlement.id, settlement.center);
        settlement.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    private static PlaqueBlockEntity plaque(GameTestHelper helper, Settlement settlement,
                                            BuildingType type, int x, int z) {
        Building building = GameTestFixtures.register(helper, settlement, type, x, z);
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) helper.getLevel()
            .getBlockEntity(building.plaquePos);
        if (plaque == null) {
            throw new IllegalStateException("fixture did not create a plaque");
        }
        try {
            Field buildingId = PlaqueBlockEntity.class.getDeclaredField("buildingId");
            buildingId.setAccessible(true);
            buildingId.set(plaque, building.id);
            Field plaqueType = PlaqueBlockEntity.class.getDeclaredField("type");
            plaqueType.setAccessible(true);
            plaqueType.set(plaque, type);
            Field state = PlaqueBlockEntity.class.getDeclaredField("state");
            state.setAccessible(true);
            state.set(plaque, PlaqueState.LINKED_VALID);
            Field insertedPlan = PlaqueBlockEntity.class
                .getDeclaredField("insertedPlan");
            insertedPlan.setAccessible(true);
            insertedPlan.set(plaque, PlaqueItemData.stamped(
                new ItemStack(ModItems.BUILD_PLAN.get()), type));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("fixture could not link plaque", e);
        }
        return plaque;
    }

    private static void setPlaqueState(PlaqueBlockEntity plaque,
                                       PlaqueState state) {
        try {
            Field field = PlaqueBlockEntity.class.getDeclaredField("state");
            field.setAccessible(true);
            field.set(plaque, state);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("fixture could not set plaque state", e);
        }
    }

    private static void setPlaqueBuildingId(PlaqueBlockEntity plaque,
                                            UUID buildingId) {
        try {
            Field field = PlaqueBlockEntity.class.getDeclaredField("buildingId");
            field.setAccessible(true);
            field.set(plaque, buildingId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("fixture could not transition plaque link", e);
        }
    }

    private static ServerPlayer viewer(GameTestHelper helper,
                                       net.minecraft.world.phys.Vec3 position) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setPos(position.x, position.y, position.z);
        return player;
    }

    private static net.minecraft.world.phys.Vec3 center(BlockPos pos) {
        return new net.minecraft.world.phys.Vec3(pos.getX() + 0.5D,
            pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private static RegistryFriendlyByteBuf buffer(GameTestHelper helper) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),
            helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
    }

    private static void flush(PayloadProbe... probes) {
        for (PayloadProbe probe : probes) {
            probe.channel.runPendingTasks();
            Object outbound;
            while ((outbound = probe.channel.readOutbound()) != null) {
                ReferenceCountUtil.release(outbound);
            }
        }
    }

    private static void clear(PayloadProbe... probes) {
        for (PayloadProbe probe : probes) {
            probe.payloads.clear();
        }
    }

    private static final class PayloadProbe implements AutoCloseable {
        private final EmbeddedChannel channel;
        private final String handlerName;
        private final List<CustomPacketPayload> payloads = new ArrayList<>();

        private PayloadProbe(ServerPlayer player, String suffix) {
            channel = (EmbeddedChannel) player.connection.getConnection().channel();
            handlerName = "hearthstead_inspection_" + suffix;
            channel.pipeline().addLast(handlerName, new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext context, Object message,
                                  ChannelPromise promise) throws Exception {
                    if (message instanceof ClientboundCustomPayloadPacket packet) {
                        payloads.add(packet.payload());
                    }
                    super.write(context, message, promise);
                }
            });
        }

        private List<SettlerSnapshotPayload> onlySettler() {
            return payloads.stream().filter(SettlerSnapshotPayload.class::isInstance)
                .map(SettlerSnapshotPayload.class::cast).toList();
        }

        private List<PlaqueSnapshot> onlyPlaque() {
            return payloads.stream().filter(PlaqueSnapshot.class::isInstance)
                .map(PlaqueSnapshot.class::cast).toList();
        }

        @Override
        public void close() {
            if (channel.pipeline().get(handlerName) != null) {
                channel.pipeline().remove(handlerName);
            }
        }
    }
}
