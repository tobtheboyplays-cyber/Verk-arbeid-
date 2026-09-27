package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.journey.JourneyState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.List;
import java.util.UUID;

/**
 * Realm map authority and scope: a subscription needs the exact open Banner
 * menu, and a projection never names another settlement's settlers, even one
 * recorded by mistake, nor anything the server has not loaded.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class RealmMapScopeGameTests {

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "realm_map_scope")
    public void projectionIsBoundedToOneSettlementAndItsLoadedMembers(GameTestHelper helper) {
        floor(helper);
        Settlement home = settlement(helper, "Homeward", new BlockPos(4, 1, 4));
        Settlement other = settlement(helper, "Elsewhere", new BlockPos(12, 1, 12));
        SettlerEntity ours = settler(helper, home, "Ours", 3, 3);
        SettlerEntity theirs = settler(helper, other, "Theirs", 12, 11);
        // A record that names the other settlement's settler must not leak it.
        home.putRecord(theirs.getUUID(), "Mis-recorded", Profession.FARMER);
        // A persisted member whose chunk/entity is not loaded is roster-only.
        UUID away = UUID.randomUUID();
        home.putRecord(away, "Away", Profession.LUMBERER);

        List<RealmMapMarkersPayload.Marker> markers = RealmMapNetwork.markersFor(helper.getLevel(), home);
        helper.assertTrue(markers.size() == 1 && markers.get(0).id().equals(ours.getUUID()),
            "only the loaded member of this settlement gets a marker, got " + markers.size());
        helper.assertTrue(markers.get(0).entityId() == ours.getId()
                && Math.abs(home.center.getX() + (double) markers.get(0).x() - ours.getX()) < 1.0E-3
                && Math.abs(home.center.getZ() + (double) markers.get(0).z() - ours.getZ()) < 1.0E-3,
            "the marker carries the exact server position (centre-relative) and runtime id");

        RealmMapLayoutPayload layout = RealmMapNetwork.buildLayout(helper.getLevel(), home, 1, null);
        helper.assertTrue(layout.settlementId().equals(home.id) && layout.roster().size() == 3,
            "the roster is exactly this settlement's records");
        RealmMapLayoutPayload.RosterEntry misrecorded = layout.roster().get(1);
        helper.assertTrue(!misrecorded.loaded() && misrecorded.appearanceSeed() == -1,
            "a record bound elsewhere exposes no live identity");
        RealmMapLayoutPayload.RosterEntry absent = layout.roster().get(2);
        helper.assertTrue(absent.id().equals(away) && !absent.loaded() && "Away".equals(absent.name()),
            "an unloaded member is named from persisted facts only");
        helper.assertTrue(layout.roster().stream().noneMatch(r -> "Theirs".equals(r.name())),
            "the other settlement's own record never appears");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "realm_map_scope")
    public void subscriptionRequiresTheExactOpenBannerMenu(GameTestHelper helper) {
        floor(helper);
        BlockPos rel = new BlockPos(2, 1, 2);
        helper.setBlock(rel, ModBlocks.HEARTH.get());
        BlockPos hearthPos = helper.absolutePos(rel);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Mapholm", hearthPos);
        settlement.radius = 6;
        settlement.journeyState = JourneyState.fresh(settlement.id);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(hearthPos);
        helper.assertTrue(hearth != null, "fixture needs a Banner block entity");
        hearth.bindSettlement(settlement.id);

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setPos(hearthPos.getX() + 0.5D, hearthPos.getY() + 0.5D, hearthPos.getZ() + 0.5D);

        RealmMapNetwork.handle(player, new RealmMapRequestPayload(hearthPos, settlement.id, 0,
            RealmMapRequestPayload.Kind.SUBSCRIBE, RealmMapRequestPayload.NO_FOCUS));
        helper.assertFalse(RealmMapNetwork.isSubscribed(player, settlement.id),
            "standing at the Banner without its open menu must not subscribe");

        player.openMenu(hearth, buf -> {
            buf.writeBlockPos(hearthPos);
            buf.writeUUID(settlement.id);
            buf.writeUtf(settlement.name);
        });
        helper.assertTrue(player.containerMenu instanceof HearthMenu, "fixture opens the real menu");
        HearthMenu menu = (HearthMenu) player.containerMenu;
        RealmMapNetwork.handle(player, new RealmMapRequestPayload(hearthPos, UUID.randomUUID(),
            menu.getContainerId(), RealmMapRequestPayload.Kind.SUBSCRIBE, RealmMapRequestPayload.NO_FOCUS));
        RealmMapNetwork.handle(player, new RealmMapRequestPayload(hearthPos, settlement.id,
            menu.getContainerId() + 1, RealmMapRequestPayload.Kind.SUBSCRIBE, RealmMapRequestPayload.NO_FOCUS));
        RealmMapNetwork.handle(player, new RealmMapRequestPayload(hearthPos.east(), settlement.id,
            menu.getContainerId(), RealmMapRequestPayload.Kind.SUBSCRIBE, RealmMapRequestPayload.NO_FOCUS));
        helper.assertFalse(RealmMapNetwork.isSubscribed(player, settlement.id),
            "a wrong settlement, container generation or Banner position is inert");

        RealmMapNetwork.handle(player, new RealmMapRequestPayload(hearthPos, settlement.id,
            menu.getContainerId(), RealmMapRequestPayload.Kind.SUBSCRIBE, RealmMapRequestPayload.NO_FOCUS));
        helper.assertTrue(RealmMapNetwork.isSubscribed(player, settlement.id),
            "the exact open menu subscribes");

        int closed = menu.getContainerId();
        player.closeContainer();
        helper.assertTrue(RealmMapNetwork.resolve(player, hearthPos, settlement.id, closed) == null,
            "after closing, the same identity no longer resolves (the broadcast drops it)");
        RealmMapNetwork.handle(player, new RealmMapRequestPayload(hearthPos, settlement.id, closed,
            RealmMapRequestPayload.Kind.FOCUS, UUID.randomUUID()));
        helper.assertFalse(RealmMapNetwork.isSubscribed(player, settlement.id),
            "a request replayed after close removes the subscription");
        helper.succeed();
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "realm_map_scope")
    public void talkMarkersListOnlyUnansweredTalksForThisSettlement(GameTestHelper helper) {
        floor(helper);
        Settlement home = settlement(helper, "Talkton", new BlockPos(4, 1, 4));
        net.minecraft.world.entity.Entity peddler = helper.spawn(net.minecraft.world.entity.EntityType.WANDERING_TRADER,
            new BlockPos(6, 1, 6));
        net.minecraft.world.entity.Entity quiet = helper.spawn(net.minecraft.world.entity.EntityType.WANDERING_TRADER,
            new BlockPos(8, 1, 6));
        net.minecraft.world.entity.Entity elsewhere = helper.spawn(
            net.minecraft.world.entity.EntityType.WANDERING_TRADER, new BlockPos(10, 1, 6));
        com.hearthstead.conversation.ConversationService.bind(peddler, "maptest",
            new com.hearthstead.conversation.SpeakerProfile(UUID.randomUUID(), "Aldric",
                "conversation.hearthstead.title.peddler", "peddler"), java.util.Map.of(), home.id);
        com.hearthstead.conversation.ConversationService.bind(elsewhere, "maptest",
            new com.hearthstead.conversation.SpeakerProfile(UUID.randomUUID(), "Envoy", "", "envoy"),
            java.util.Map.of(), UUID.randomUUID());

        List<RealmMapMarkersPayload.Talker> talkers = RealmMapNetwork.talkersNear(helper.getLevel(), home);
        helper.assertTrue(talkers.stream().anyMatch(t -> t.entityId() == peddler.getId()
                && "Aldric".equals(t.name()) && "conversation.hearthstead.title.peddler".equals(t.titleKey())
                && Math.abs(home.center.getX() + (double) t.x() - peddler.getX()) < 1.0E-3),
            "a bound, unanswered talk near the claim is in the snapshot with its name, title and position");
        helper.assertTrue(talkers.stream().noneMatch(t -> t.entityId() == quiet.getId()),
            "someone with nothing to say gets no marker");
        helper.assertTrue(talkers.stream().noneMatch(t -> t.entityId() == elsewhere.getId()),
            "a talk bound to another settlement is not shown on this map");

        // Answered (unbound) talks disappear on the next snapshot.
        com.hearthstead.conversation.ConversationService.unbind(peddler);
        helper.assertTrue(RealmMapNetwork.talkersNear(helper.getLevel(), home).stream()
                .noneMatch(t -> t.entityId() == peddler.getId()),
            "an answered talk leaves the map");
        helper.succeed();
    }

    private static Settlement settlement(GameTestHelper helper, String name, BlockPos rel) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), name, helper.absolutePos(rel));
        settlement.radius = 6;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement settlement, String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(settlement.id, settlement.center);
        settlement.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }
}
