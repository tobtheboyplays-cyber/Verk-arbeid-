package com.hearthstead.network;

import com.mojang.authlib.GameProfile;
import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.state.GuardOrder;
import com.hearthstead.settlement.state.GuardOrderBook;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Exact-session, stale-revision and real packet-shape guard-order proof. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GuardOrderNetworkGameTests {

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "guard_order_authorized_server_position_and_stale_replay")
    public void authorizedActionUsesServerPositionAndStaleReplayIsInert(
            GameTestHelper helper) {
        Fixture f = fixture(helper);
        PayloadProbe packets = new PayloadProbe(f.player, "authority");
        UUID session = SettlerNetwork.openFor(f.player, f.guard);
        flush(packets);
        packets.clear();

        GuardOrderSnapshotPayload current = act(helper, f, packets,
            new GuardOrderActionPayload(f.guard.getId(), f.guard.getUUID(),
                session, SettlerActionPayload.NO_SETTLER,
                GuardOrderActionPayload.Kind.REFRESH, -1));
        helper.assertTrue(current.outcome()
                == GuardOrderSnapshotPayload.Outcome.NEUTRAL
                && !current.towerPostAvailable(),
            "refresh must expose server truth and keep Tower explicitly blocked");

        BlockPos held = helper.absolutePos(new BlockPos(7, 1, 6));
        f.player.setPos(held.getX() + 0.5D, held.getY(), held.getZ() + 0.5D);
        GuardOrderActionPayload original = new GuardOrderActionPayload(
            f.guard.getId(), f.guard.getUUID(), session, f.settlement.id,
            GuardOrderActionPayload.Kind.HOLD_HERE, current.revision());
        GuardOrderSnapshotPayload applied = act(helper, f, packets, original);
        helper.assertTrue(applied.outcome()
                == GuardOrderSnapshotPayload.Outcome.APPLIED
                && order(f).mode() == GuardOrder.Mode.STAND_POST
                && order(f).pos().orElseThrow().equals(held),
            "Hold Here must use the live server player block, never a client coordinate");

        BlockPos replayPosition = helper.absolutePos(new BlockPos(9, 1, 6));
        f.player.setPos(replayPosition.getX() + 0.5D,
            replayPosition.getY(), replayPosition.getZ() + 0.5D);
        GuardOrderSnapshotPayload refused = act(helper, f, packets, original);
        helper.assertTrue(refused.outcome()
                == GuardOrderSnapshotPayload.Outcome.REFUSED
                && order(f).revision() == applied.revision()
                && order(f).pos().orElseThrow().equals(held),
            "a replay against an old revision must preserve the exact last order");

        packets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "guard_order_route_actions_wire_and_save_round_trip")
    public void twoPlayerDefinedPointsStartExactPersistedRoute(
            GameTestHelper helper) {
        Fixture f = fixture(helper);
        PayloadProbe packets = new PayloadProbe(f.player, "route");
        UUID session = SettlerNetwork.openFor(f.player, f.guard);
        flush(packets);
        packets.clear();
        GuardOrderSnapshotPayload state = act(helper, f, packets,
            action(f, session, GuardOrderActionPayload.Kind.REFRESH,
                SettlerActionPayload.NO_SETTLER, -1));

        BlockPos first = helper.absolutePos(new BlockPos(5, 1, 6));
        f.player.setPos(first.getX() + 0.5D, first.getY(), first.getZ() + 0.5D);
        state = act(helper, f, packets, action(f, session,
            GuardOrderActionPayload.Kind.ADD_PATROL_POINT, f.settlement.id,
            state.revision()));
        BlockPos second = helper.absolutePos(new BlockPos(11, 1, 6));
        f.player.setPos(second.getX() + 0.5D, second.getY(),
            second.getZ() + 0.5D);
        state = act(helper, f, packets, action(f, session,
            GuardOrderActionPayload.Kind.ADD_PATROL_POINT, f.settlement.id,
            state.revision()));
        state = act(helper, f, packets, action(f, session,
            GuardOrderActionPayload.Kind.START_PATROL, f.settlement.id,
            state.revision()));

        helper.assertTrue(state.outcome()
                == GuardOrderSnapshotPayload.Outcome.APPLIED
                && state.modeWireId() == GuardOrder.Mode.PATROL_ROUTE.wireId()
                && state.patrolPoints().equals(List.of(first, second)),
            "the server must preserve the player's exact numbered two-point route");
        Settlement loaded = Settlement.readNbt(f.settlement.writeNbt());
        GuardOrder loadedOrder = loaded.guardOrders.order(f.guard.getUUID())
            .orElseThrow();
        helper.assertTrue(loadedOrder.mode() == GuardOrder.Mode.PATROL_ROUTE
                && loadedOrder.patrolPoints().equals(List.of(first, second))
                && loadedOrder.revision() == state.revision(),
            "the active route and revision must survive the settlement save/reload boundary");

        packets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "guard_order_forged_session_range_and_membership_fail_closed")
    public void forgedSessionRangeAndMembershipCannotMutate(
            GameTestHelper helper) {
        Fixture f = fixture(helper);
        PayloadProbe packets = new PayloadProbe(f.player, "forged");
        UUID session = SettlerNetwork.openFor(f.player, f.guard);
        flush(packets);
        packets.clear();
        int originalRevision = currentRevision(f);

        GuardOrderNetwork.handle(f.player, action(f, UUID.randomUUID(),
            GuardOrderActionPayload.Kind.HOLD_HERE, f.settlement.id,
            originalRevision));
        flush(packets);
        helper.assertTrue(currentRevision(f) == originalRevision
                && packets.guards().isEmpty(),
            "a forged inspection session must have no mutation and no reply oracle");

        f.player.setPos(f.guard.getX() + 9.0D, f.guard.getY(), f.guard.getZ());
        GuardOrderNetwork.handle(f.player, action(f, session,
            GuardOrderActionPayload.Kind.HOLD_HERE, f.settlement.id,
            originalRevision));
        flush(packets);
        helper.assertTrue(currentRevision(f) == originalRevision,
            "a player outside the exact inspection range cannot issue orders");

        f.player.setPos(f.guard.getX(), f.guard.getY(), f.guard.getZ());
        f.barracks.workers.remove(f.guard.getUUID());
        GuardOrderNetwork.handle(f.player, action(f, session,
            GuardOrderActionPayload.Kind.HOLD_HERE, f.settlement.id,
            originalRevision));
        flush(packets);
        helper.assertTrue(currentRevision(f) == originalRevision,
            "a synced Guard without the authoritative Barracks roster row is not a member authority");

        packets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 250,
        batch = "guard_order_two_players_one_atomic_revision_winner")
    public void twoPlayersCannotOverwriteOneGuardFromTheSameRevision(
            GameTestHelper helper) {
        Fixture first = fixture(helper);
        ServerPlayer secondPlayer = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(
            secondPlayer.connection.getConnection());
        secondPlayer.setPos(first.guard.getX(), first.guard.getY(),
            first.guard.getZ());
        Fixture second = new Fixture(first.settlement, first.barracks,
            first.guard, secondPlayer);
        PayloadProbe firstPackets = new PayloadProbe(first.player, "winner");
        PayloadProbe secondPackets = new PayloadProbe(second.player, "stale");
        UUID firstSession = SettlerNetwork.openFor(first.player, first.guard);
        UUID secondSession = SettlerNetwork.openFor(second.player, second.guard);
        flush(firstPackets);
        flush(secondPackets);
        GuardOrderSnapshotPayload firstState = act(helper, first, firstPackets,
            action(first, firstSession, GuardOrderActionPayload.Kind.REFRESH,
                SettlerActionPayload.NO_SETTLER, -1));
        GuardOrderSnapshotPayload secondState = act(helper, second, secondPackets,
            action(second, secondSession, GuardOrderActionPayload.Kind.REFRESH,
                SettlerActionPayload.NO_SETTLER, -1));
        BlockPos winningPost = helper.absolutePos(new BlockPos(7, 1, 6));
        first.player.setPos(winningPost.getX() + 0.5D, winningPost.getY(),
            winningPost.getZ() + 0.5D);
        GuardOrderSnapshotPayload winner = act(helper, first, firstPackets,
            action(first, firstSession, GuardOrderActionPayload.Kind.HOLD_HERE,
                first.settlement.id, firstState.revision()));
        BlockPos stalePost = helper.absolutePos(new BlockPos(9, 1, 6));
        second.player.setPos(stalePost.getX() + 0.5D, stalePost.getY(),
            stalePost.getZ() + 0.5D);
        GuardOrderSnapshotPayload stale = act(helper, second, secondPackets,
            action(second, secondSession,
                GuardOrderActionPayload.Kind.HOLD_HERE,
                second.settlement.id, secondState.revision()));

        helper.assertTrue(winner.outcome()
                == GuardOrderSnapshotPayload.Outcome.APPLIED
                && stale.outcome() == GuardOrderSnapshotPayload.Outcome.REFUSED
                && order(first).revision() == winner.revision()
                && order(first).pos().orElseThrow().equals(winningPost),
            "exactly one player may win one expected revision; stale player must be inert");
        firstPackets.close();
        secondPackets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "guard_order_cross_settlement_rejected")
    public void crossSettlementIdentityIsRejectedWithZeroMutation(
            GameTestHelper helper) {
        Fixture f = fixture(helper);
        PayloadProbe packets = new PayloadProbe(f.player, "cross_settlement");
        UUID session = SettlerNetwork.openFor(f.player, f.guard);
        flush(packets);
        GuardOrderSnapshotPayload state = act(helper, f, packets,
            action(f, session, GuardOrderActionPayload.Kind.REFRESH,
                SettlerActionPayload.NO_SETTLER, -1));
        GuardOrderSnapshotPayload refused = act(helper, f, packets,
            action(f, session, GuardOrderActionPayload.Kind.HOLD_HERE,
                UUID.randomUUID(), state.revision()));
        helper.assertTrue(refused.outcome()
                == GuardOrderSnapshotPayload.Outcome.REFUSED
                && currentRevision(f) == 0,
            "a foreign settlement id must never create or mutate this Guard's order");
        packets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "guard_order_deleted_or_dismissed_authority_rejected")
    public void deletedEmployerAndDismissalInvalidatePersistedOrder(
            GameTestHelper helper) {
        Fixture f = fixture(helper);
        PayloadProbe packets = new PayloadProbe(f.player, "deleted");
        UUID session = SettlerNetwork.openFor(f.player, f.guard);
        flush(packets);
        GuardOrderSnapshotPayload state = act(helper, f, packets,
            action(f, session, GuardOrderActionPayload.Kind.REFRESH,
                SettlerActionPayload.NO_SETTLER, -1));
        state = act(helper, f, packets, action(f, session,
            GuardOrderActionPayload.Kind.HOLD_HERE, f.settlement.id,
            state.revision()));
        int committedRevision = state.revision();
        f.settlement.buildings.remove(f.barracks);
        packets.clear();
        GuardOrderNetwork.handle(f.player, action(f, session,
            GuardOrderActionPayload.Kind.CLEAR_ORDER, f.settlement.id,
            committedRevision));
        flush(packets);
        helper.assertTrue(order(f).revision() == committedRevision
                && order(f).mode() == GuardOrder.Mode.STAND_POST
                && packets.guards().isEmpty(),
            "deleted linked building must invalidate the order without mutation");
        f.settlement.buildings.add(f.barracks);
        Employment.dismiss(helper.getLevel(), f.settlement, f.guard);
        packets.clear();
        GuardOrderNetwork.handle(f.player, action(f, session,
            GuardOrderActionPayload.Kind.CLEAR_ORDER, f.settlement.id,
            committedRevision));
        flush(packets);
        helper.assertTrue(order(f).revision() == committedRevision,
            "dismissed or wrong-profession Guard must remain mutation-inert");
        packets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "guard_order_relinked_employer_rejected")
    public void relinkedEmployerCannotRewriteTheOldBuildingOrder(
            GameTestHelper helper) {
        Fixture f = fixture(helper);
        PayloadProbe packets = new PayloadProbe(f.player, "relinked");
        UUID session = SettlerNetwork.openFor(f.player, f.guard);
        flush(packets);
        GuardOrderSnapshotPayload state = act(helper, f, packets,
            action(f, session, GuardOrderActionPayload.Kind.REFRESH,
                SettlerActionPayload.NO_SETTLER, -1));
        state = act(helper, f, packets, action(f, session,
            GuardOrderActionPayload.Kind.HOLD_HERE, f.settlement.id,
            state.revision()));
        int committedRevision = state.revision();
        UUID originalBuilding = order(f).linkedBuildingId().orElseThrow();

        Building replacement = GameTestFixtures.register(helper, f.settlement,
            BuildingType.BARRACKS, 12, 2);
        Employment.dismiss(helper.getLevel(), f.settlement, f.guard);
        helper.assertTrue(Employment.hire(helper.getLevel(), f.settlement,
                replacement, f.guard).ok(),
            "fixture: Guard must be physically relinked to the replacement Barracks");
        packets.clear();
        GuardOrderNetwork.handle(f.player, action(f, session,
            GuardOrderActionPayload.Kind.CLEAR_ORDER, f.settlement.id,
            committedRevision));
        flush(packets);

        helper.assertTrue(order(f).revision() == committedRevision
                && order(f).mode() == GuardOrder.Mode.STAND_POST
                && order(f).linkedBuildingId().orElseThrow()
                    .equals(originalBuilding)
                && packets.guards().isEmpty(),
            "relinking this Guard must invalidate the old building-bound order without mutation");
        packets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "guard_order_spectator_rejected")
    public void spectatorSessionIsReadOnlyAndCannotCreateAnOrder(
            GameTestHelper helper) {
        Fixture f = fixture(helper, true);
        try (PayloadProbe packets = new PayloadProbe(f.player, "spectator")) {
            helper.assertFalse(f.player.isSpectator(),
                "fixture: the management session must begin as a non-spectator");
            UUID session = SettlerNetwork.openFor(f.player, f.guard);
            flush(packets);
            GuardOrderSnapshotPayload state = act(helper, f, packets,
                action(f, session, GuardOrderActionPayload.Kind.REFRESH,
                    SettlerActionPayload.NO_SETTLER, -1));

            helper.assertTrue(f.player.gameMode.changeGameModeForPlayer(
                    GameType.SPECTATOR),
                "fixture: the real server player must enter spectator mode");
            helper.assertTrue(f.player.isSpectator(),
                "fixture: the authoritative player must actually be a spectator");
            GuardOrderSnapshotPayload refused = act(helper, f, packets,
                action(f, session, GuardOrderActionPayload.Kind.HOLD_HERE,
                    f.settlement.id, state.revision()));

            helper.assertTrue(refused.outcome()
                    == GuardOrderSnapshotPayload.Outcome.REFUSED
                    && currentRevision(f) == 0
                    && f.settlement.guardOrders.size() == 0,
                "spectators may refresh server truth but cannot create Guard authority");
        } finally {
            InspectionViewers.clear(helper.getLevel().getServer());
            helper.getLevel().getServer().getPlayerList().remove(f.player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "guard_order_dead_guard_rejected")
    public void deadGuardSessionCannotCreateOrMutateAnOrder(
            GameTestHelper helper) {
        Fixture f = fixture(helper);
        PayloadProbe packets = new PayloadProbe(f.player, "dead_guard");
        UUID session = SettlerNetwork.openFor(f.player, f.guard);
        flush(packets);
        packets.clear();
        f.guard.setHealth(0.0F);
        GuardOrderNetwork.handle(f.player, action(f, session,
            GuardOrderActionPayload.Kind.HOLD_HERE, f.settlement.id, 0));
        flush(packets);

        helper.assertTrue(!f.guard.isAlive()
                && currentRevision(f) == 0
                && f.settlement.guardOrders.size() == 0
                && packets.guards().isEmpty(),
            "a dead exact Guard must have no mutation and no reply oracle");
        packets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "guard_order_wrong_dimension_and_saturation_fail_closed")
    public void wrongDimensionAndSaturatedRevisionCannotMutate(
            GameTestHelper helper) {
        Fixture f = fixture(helper);
        f.settlement.guardOrders = GuardOrderBook.fresh();
        GuardOrder wrongDimension = f.settlement.guardOrders.orderForMutation(
            f.settlement.id, f.guard.getUUID(),
            ResourceLocation.withDefaultNamespace("the_nether")).orElseThrow();
        helper.assertTrue(wrongDimension.issueStand(f.guard.blockPosition(),
            net.minecraft.core.Direction.NORTH, 8, UUID.randomUUID(),
            f.barracks.id, helper.getLevel().getGameTime()),
            "fixture: forged cross-dimension order");
        PayloadProbe packets = new PayloadProbe(f.player, "dimension");
        UUID session = SettlerNetwork.openFor(f.player, f.guard);
        flush(packets);
        packets.clear();
        GuardOrderNetwork.handle(f.player, action(f, session,
            GuardOrderActionPayload.Kind.CLEAR_ORDER, f.settlement.id,
            wrongDimension.revision()));
        flush(packets);
        helper.assertTrue(wrongDimension.revision() == 1
                && packets.guards().isEmpty(),
            "an order copied from another dimension must fail closed");

        GuardOrderBook correct = GuardOrderBook.fresh();
        GuardOrder authored = correct.orderForMutation(f.settlement.id,
            f.guard.getUUID(), helper.getLevel().dimension().location())
            .orElseThrow();
        helper.assertTrue(authored.issueStand(f.guard.blockPosition(),
            net.minecraft.core.Direction.NORTH, 8, UUID.randomUUID(),
            f.barracks.id, helper.getLevel().getGameTime()),
            "fixture: valid current-dimension order");
        CompoundTag persisted = correct.writeNbt();
        persisted.getList("Orders", Tag.TAG_COMPOUND).getCompound(0)
            .putInt("Revision", Integer.MAX_VALUE);
        f.settlement.guardOrders = GuardOrderBook.readNbt(persisted);
        InspectionViewers.closeSettler(f.player, f.guard.getId(),
            f.guard.getUUID(), session);
        UUID saturatedSession = SettlerNetwork.openFor(f.player, f.guard);
        flush(packets);
        GuardOrderSnapshotPayload refused = act(helper, f, packets,
            action(f, saturatedSession, GuardOrderActionPayload.Kind.CLEAR_ORDER,
                f.settlement.id, Integer.MAX_VALUE));
        helper.assertTrue(refused.outcome()
                == GuardOrderSnapshotPayload.Outcome.REFUSED
                && order(f).revision() == Integer.MAX_VALUE
                && order(f).mode() == GuardOrder.Mode.STAND_POST,
            "MAX_VALUE revision must survive and reject without wrapping");
        packets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "guard_order_reconnect_preserves_exact_revision")
    public void reconnectGetsTheSameOrderWithoutDuplicatingIt(
            GameTestHelper helper) {
        Fixture f = fixture(helper);
        PayloadProbe packets = new PayloadProbe(f.player, "reconnect");
        UUID firstSession = SettlerNetwork.openFor(f.player, f.guard);
        flush(packets);
        GuardOrderSnapshotPayload state = act(helper, f, packets,
            action(f, firstSession, GuardOrderActionPayload.Kind.REFRESH,
                SettlerActionPayload.NO_SETTLER, -1));
        state = act(helper, f, packets, action(f, firstSession,
            GuardOrderActionPayload.Kind.HOLD_HERE, f.settlement.id,
            state.revision()));
        int revision = state.revision();
        InspectionViewers.closeSettler(f.player, f.guard.getId(),
            f.guard.getUUID(), firstSession);
        UUID reconnect = SettlerNetwork.openFor(f.player, f.guard);
        flush(packets);
        GuardOrderSnapshotPayload reloaded = act(helper, f, packets,
            action(f, reconnect, GuardOrderActionPayload.Kind.REFRESH,
                SettlerActionPayload.NO_SETTLER, -1));
        helper.assertTrue(reloaded.revision() == revision
                && reloaded.modeWireId() == GuardOrder.Mode.STAND_POST.wireId()
                && f.settlement.guardOrders.size() == 1,
            "reconnect must preserve one exact order and revision without duplication");
        packets.close();
        InspectionViewers.clear(helper.getLevel().getServer());
        helper.succeed();
    }

    private static GuardOrderSnapshotPayload act(GameTestHelper helper,
                                                 Fixture fixture,
                                                 PayloadProbe packets,
                                                 GuardOrderActionPayload raw) {
        packets.clear();
        GuardOrderActionPayload wire = throughWire(helper, raw);
        GuardOrderNetwork.handle(fixture.player, wire);
        flush(packets);
        List<GuardOrderSnapshotPayload> replies = packets.guards();
        helper.assertTrue(replies.size() == 1,
            "one authorized action must author exactly one Guard snapshot, got "
                + replies.size());
        return replies.getFirst();
    }

    private static GuardOrderActionPayload action(Fixture fixture,
                                                  UUID session,
                                                  GuardOrderActionPayload.Kind kind,
                                                  UUID settlementId,
                                                  int revision) {
        return new GuardOrderActionPayload(fixture.guard.getId(),
            fixture.guard.getUUID(), session, settlementId, kind, revision);
    }

    private static GuardOrder order(Fixture fixture) {
        return fixture.settlement.guardOrders.order(fixture.guard.getUUID())
            .orElseThrow();
    }

    private static int currentRevision(Fixture fixture) {
        return fixture.settlement.guardOrders.order(fixture.guard.getUUID())
            .map(GuardOrder::revision).orElse(0);
    }

    private static GuardOrderActionPayload throughWire(GameTestHelper helper,
                                                       GuardOrderActionPayload action) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
            Unpooled.buffer(), helper.getLevel().registryAccess(),
            ConnectionType.NEOFORGE);
        GuardOrderActionPayload.CODEC.encode(buf, action);
        return GuardOrderActionPayload.CODEC.decode(buf);
    }

    private static Fixture fixture(GameTestHelper helper) {
        return fixture(helper, false);
    }

    private static Fixture fixture(GameTestHelper helper,
                                   boolean modeAwarePlayer) {
        InspectionViewers.clear(helper.getLevel().getServer());
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y < 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        Settlement settlement = new Settlement(UUID.randomUUID(), "Commandholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 12;
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 2, 2);
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(6, 1, 6));
        guard.setSettlerName("Ward");
        guard.bindTo(settlement.id, settlement.center);
        settlement.putRecord(guard.getUUID(), guard.getSettlerName(),
            Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                barracks, guard).ok(),
            "fixture: a valid Barracks must hire the exact guard member");
        guard.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_SWORD));

        ServerPlayer player = modeAwarePlayer
            ? makeModeAwareServerPlayer(helper)
            : helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setPos(guard.getX(), guard.getY(), guard.getZ());
        return new Fixture(settlement, barracks, guard, player);
    }

    /** Vanilla's deprecated GameTest mock hard-overrides spectator to false. */
    private static ServerPlayer makeModeAwareServerPlayer(
            GameTestHelper helper) {
        GameProfile profile = new GameProfile(UUID.randomUUID(),
            "guard-order-player");
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
            profile, false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(),
            helper.getLevel(), profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(
            connection, player, cookie);
        return player;
    }

    private static void flush(PayloadProbe probe) {
        probe.channel.runPendingTasks();
        Object outbound;
        while ((outbound = probe.channel.readOutbound()) != null) {
            ReferenceCountUtil.release(outbound);
        }
    }

    private record Fixture(Settlement settlement, Building barracks,
                           SettlerEntity guard, ServerPlayer player) {
    }

    private static final class PayloadProbe implements AutoCloseable {
        private final EmbeddedChannel channel;
        private final String handlerName;
        private final List<Object> payloads = new ArrayList<>();

        private PayloadProbe(ServerPlayer player, String suffix) {
            channel = (EmbeddedChannel) player.connection.getConnection().channel();
            handlerName = "hearthstead_guard_order_" + suffix;
            channel.pipeline().addLast(handlerName,
                new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext context,
                                      Object message,
                                      ChannelPromise promise) throws Exception {
                        if (message instanceof ClientboundCustomPayloadPacket packet) {
                            payloads.add(packet.payload());
                        }
                        super.write(context, message, promise);
                    }
                });
        }

        private List<GuardOrderSnapshotPayload> guards() {
            return payloads.stream()
                .filter(GuardOrderSnapshotPayload.class::isInstance)
                .map(GuardOrderSnapshotPayload.class::cast).toList();
        }

        private void clear() {
            payloads.clear();
        }

        @Override
        public void close() {
            if (channel.pipeline().get(handlerName) != null) {
                channel.pipeline().remove(handlerName);
            }
        }
    }
}
