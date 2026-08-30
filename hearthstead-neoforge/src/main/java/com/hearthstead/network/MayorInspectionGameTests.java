package com.hearthstead.network;

import com.mojang.authlib.GameProfile;
import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Mayor;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.development.Development;
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
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Native Mayor-shop entry contract for the ordinary settler sheet. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class MayorInspectionGameTests {

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "mayor_shop_exact_identity_opens_ordinary_settler_sheet")
    public void exactShopSnapshotOpensOrdinaryMayorSheet(GameTestHelper helper) {
        Fixture f = fixture(helper);
        try (PayloadProbe packets = new PayloadProbe(f.player, "valid")) {
            DevelopmentNetwork.openEmblemShop(f.player, f.mayor,
                f.settlement, f.hearth);
            flush(packets);
            List<DevelopmentSnapshotPayload> shopReplies = packets.development();
            helper.assertTrue(shopReplies.size() == 1,
                "speaking to the Mayor must author exactly one shop snapshot");
            DevelopmentSnapshotPayload shop = shopReplies.getFirst();
            helper.assertTrue(shop.view()
                    == DevelopmentActionPayload.View.EMBLEM_SHOP
                    && shop.mayorId().equals(f.mayor.getUUID())
                    && shop.mayorEntityId() == f.mayor.getId(),
                "the shop must expose both exact live Mayor identities");
            DevelopmentSnapshotPayload wireShop = throughWire(helper, shop);
            helper.assertTrue(wireShop.equals(shop),
                "the shop snapshot must round-trip its Mayor entity id");

            packets.clear();
            DevelopmentActionPayload raw = new DevelopmentActionPayload(
                shop.hearthPos(), shop.settlementId(), shop.mayorId(),
                DevelopmentActionPayload.View.EMBLEM_SHOP,
                DevelopmentActionPayload.Kind.INSPECT_MAYOR,
                shop.mayorEntityId(), shop.revision());
            DevelopmentActionPayload action = throughWire(helper, raw);
            helper.assertTrue(action.equals(raw),
                "Inspect Mayor must round-trip stable kind 3 and both identities");
            UUID session = DevelopmentNetwork.openMayorInspection(f.player,
                action);
            flush(packets);

            List<Object> opened = packets.sheetOpens();
            helper.assertTrue(session != null && opened.size() == 2
                    && opened.get(0) instanceof OpenSettlerScreenPayload open
                    && open.entityId() == f.mayor.getId()
                    && opened.get(1) instanceof SettlerSnapshotPayload sheet
                    && sheet.delivery() == SettlerSnapshotPayload.Delivery.OPEN
                    && sheet.entityId() == f.mayor.getId()
                    && sheet.settlerId().equals(f.mayor.getUUID())
                    && sheet.sessionId().equals(session)
                    && sheet.isMayor() && sheet.canManage(),
                "one accepted button must open the exact ordinary Mayor sheet in order");
            helper.assertTrue(InspectionViewers.authorizeSettler(f.player,
                    f.mayor, f.mayor.getUUID(), session),
                "the opened sheet must own one exact live inspection session");
        } finally {
            InspectionViewers.clear(helper.getLevel().getServer());
            helper.getLevel().getServer().getPlayerList().remove(f.player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "mayor_shop_stale_range_and_identity_fail_closed")
    public void StaleRangeAndWrongIdentityCannotOpenMayorSheet(
            GameTestHelper helper) {
        Fixture f = fixture(helper);
        try (PayloadProbe packets = new PayloadProbe(f.player, "refused")) {
            int revision = Development.revisionOf(helper.getLevel(),
                f.settlement);
            DevelopmentActionPayload exact = action(f, f.mayor.getId(),
                f.mayor.getUUID(), revision);

            UUID stale = DevelopmentNetwork.openMayorInspection(f.player,
                action(f, f.mayor.getId(), f.mayor.getUUID(), revision - 1));
            flush(packets);
            assertNoSheet(helper, packets, stale,
                "an old Development revision must not open a sheet");

            packets.clear();
            UUID wrongRuntime = DevelopmentNetwork.openMayorInspection(f.player,
                action(f, f.mayor.getId() + 100_000, f.mayor.getUUID(), revision));
            flush(packets);
            assertNoSheet(helper, packets, wrongRuntime,
                "a wrong/reused runtime id must not retarget the Mayor UUID");

            packets.clear();
            UUID wrongUuid = DevelopmentNetwork.openMayorInspection(f.player,
                action(f, f.mayor.getId(), UUID.randomUUID(), revision));
            flush(packets);
            assertNoSheet(helper, packets, wrongUuid,
                "a forged UUID must not retarget the runtime entity id");

            packets.clear();
            f.player.setPos(f.mayor.getX() + 6.0D, f.mayor.getY(),
                f.mayor.getZ());
            UUID distant = DevelopmentNetwork.openMayorInspection(f.player,
                exact);
            flush(packets);
            assertNoSheet(helper, packets, distant,
                "a player beyond the Mayor interaction radius must not open a sheet");
        } finally {
            InspectionViewers.clear(helper.getLevel().getServer());
            helper.getLevel().getServer().getPlayerList().remove(f.player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "mayor_and_settler_sheets_are_spectator_read_only")
    public void spectatorCannotOpenOrMutateManagementSheet(
            GameTestHelper helper) {
        Fixture f = fixture(helper);
        Building camp = GameTestFixtures.register(helper, f.settlement,
            BuildingType.LUMBER_CAMP, 10, 10);
        SettlerEntity worker = settler(helper, f.settlement,
            "Read Only Worker", new BlockPos(9, 1, 9));
        helper.assertTrue(Employment.hire(helper.getLevel(), f.settlement,
                camp, worker).ok(),
            "spectator fixture needs one real employment row");

        try (PayloadProbe packets = new PayloadProbe(f.player, "spectator")) {
            // The GameTest mock's CommonHooks transition may refuse a public
            // mode-change event. Set the authoritative game-mode holder
            // directly so this row tests production isSpectator() checks.
            helper.assertTrue(f.player.gameMode.changeGameModeForPlayer(
                    GameType.SPECTATOR),
                "fixture must change the authoritative game-mode holder");
            helper.assertTrue(f.player.isSpectator(),
                "fixture must enter authoritative spectator mode");
            f.player.setPos(f.mayor.getX(), f.mayor.getY(), f.mayor.getZ());
            // Drain any custom payload queued by the game-mode transition;
            // only the following explicit button action belongs to this row.
            flush(packets);
            packets.clear();
            int revision = Development.revisionOf(helper.getLevel(),
                f.settlement);
            UUID refused = DevelopmentNetwork.openMayorInspection(f.player,
                action(f, f.mayor.getId(), f.mayor.getUUID(), revision));
            flush(packets);
            helper.assertTrue(refused == null,
                "a spectator cannot receive a Mayor inspection session");
            helper.assertTrue(packets.sheetOpens().isEmpty(),
                "a spectator cannot receive a Mayor management-sheet OPEN payload");

            packets.clear();
            f.player.setPos(worker.getX(), worker.getY(), worker.getZ());
            UUID session = SettlerNetwork.openFor(f.player, worker);
            flush(packets);
            SettlerSnapshotPayload readOnly = packets.settlers().getFirst();
            helper.assertTrue(!readOnly.canManage()
                    && readOnly.sessionId().equals(session),
                "a spectator inspection snapshot must be visibly read-only");

            packets.clear();
            SettlerNetwork.handle(f.player, new SettlerActionPayload(
                worker.getId(), worker.getUUID(), session,
                SettlerActionPayload.Kind.DISMISS, readOnly.revision()));
            flush(packets);
            helper.assertTrue(Employment.employerOf(f.settlement,
                    worker.getUUID()) == camp,
                "a crafted spectator DISMISS must leave employment unchanged");
        } finally {
            InspectionViewers.clear(helper.getLevel().getServer());
            helper.getLevel().getServer().getPlayerList().remove(f.player);
        }
        helper.succeed();
    }

    private static DevelopmentActionPayload action(Fixture fixture,
            int entityId, UUID mayorId, int revision) {
        return new DevelopmentActionPayload(fixture.settlement.center,
            fixture.settlement.id, mayorId,
            DevelopmentActionPayload.View.EMBLEM_SHOP,
            DevelopmentActionPayload.Kind.INSPECT_MAYOR, entityId, revision);
    }

    private static void assertNoSheet(GameTestHelper helper,
                                      PayloadProbe packets,
                                      UUID session,
                                      String message) {
        helper.assertTrue(session == null && packets.sheetOpens().isEmpty(),
            message);
    }

    private static DevelopmentActionPayload throughWire(GameTestHelper helper,
            DevelopmentActionPayload action) {
        RegistryFriendlyByteBuf buffer = buffer(helper);
        DevelopmentActionPayload.CODEC.encode(buffer, action);
        return DevelopmentActionPayload.CODEC.decode(buffer);
    }

    private static DevelopmentSnapshotPayload throughWire(GameTestHelper helper,
            DevelopmentSnapshotPayload snapshot) {
        RegistryFriendlyByteBuf buffer = buffer(helper);
        DevelopmentSnapshotPayload.CODEC.encode(buffer, snapshot);
        return DevelopmentSnapshotPayload.CODEC.decode(buffer);
    }

    private static RegistryFriendlyByteBuf buffer(GameTestHelper helper) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),
            helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
    }

    private static Fixture fixture(GameTestHelper helper) {
        InspectionViewers.clear(helper.getLevel().getServer());
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y < 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        BlockPos hearthRelative = new BlockPos(4, 1, 4);
        helper.setBlock(hearthRelative, ModBlocks.HEARTH.get());
        BlockPos hearthPos = helper.absolutePos(hearthRelative);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel()
            .getBlockEntity(hearthPos);
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Mayor Sheet", hearthPos);
        settlement.radius = 12;
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);
        Development.revisionOf(helper.getLevel(), settlement);

        SettlerEntity mayor = settler(helper, settlement, "Mayor Rowan",
            new BlockPos(6, 1, 6));
        helper.assertTrue(Mayor.appoint(helper.getLevel(), settlement, mayor) == null,
            "fixture must appoint the exact live Mayor");

        ServerPlayer player = makeModeAwareServerPlayer(helper);
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setPos(mayor.getX(), mayor.getY(), mayor.getZ());
        return new Fixture(settlement, hearth, mayor, player);
    }

    /** Vanilla's deprecated helper hard-overrides isSpectator() to false. */
    private static ServerPlayer makeModeAwareServerPlayer(
            GameTestHelper helper) {
        GameProfile profile = new GameProfile(UUID.randomUUID(),
            "mayor-sheet-player");
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

    private static SettlerEntity settler(GameTestHelper helper,
                                         Settlement settlement,
                                         String name,
                                         BlockPos relative) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), relative);
        settler.setSettlerName(name);
        settler.bindTo(settlement.id, settlement.center);
        settlement.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    private static void flush(PayloadProbe probe) {
        probe.channel.runPendingTasks();
        Object outbound;
        while ((outbound = probe.channel.readOutbound()) != null) {
            ReferenceCountUtil.release(outbound);
        }
    }

    private record Fixture(Settlement settlement, HearthBlockEntity hearth,
                           SettlerEntity mayor, ServerPlayer player) {
    }

    private static final class PayloadProbe implements AutoCloseable {
        private final EmbeddedChannel channel;
        private final String handlerName;
        private final List<Object> payloads = new ArrayList<>();

        private PayloadProbe(ServerPlayer player, String suffix) {
            channel = (EmbeddedChannel) player.connection.getConnection().channel();
            handlerName = "hearthstead_mayor_inspection_" + suffix;
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

        private List<DevelopmentSnapshotPayload> development() {
            return payloads.stream()
                .filter(DevelopmentSnapshotPayload.class::isInstance)
                .map(DevelopmentSnapshotPayload.class::cast).toList();
        }

        private List<SettlerSnapshotPayload> settlers() {
            return payloads.stream()
                .filter(SettlerSnapshotPayload.class::isInstance)
                .map(SettlerSnapshotPayload.class::cast).toList();
        }

        private List<Object> sheetOpens() {
            return payloads.stream()
                .filter(payload -> payload instanceof OpenSettlerScreenPayload
                    || payload instanceof SettlerSnapshotPayload)
                .toList();
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
