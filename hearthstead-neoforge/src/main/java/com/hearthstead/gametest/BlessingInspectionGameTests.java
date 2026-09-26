package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.PlaqueNetwork;
import com.hearthstead.network.PlaqueSnapshot;
import com.hearthstead.network.SettlerNetwork;
import com.hearthstead.network.SettlerSnapshotPayload;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.TargetBlessingState;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-to-inspection proof for permanent target Blessings.
 *
 * <p>Both tests call the real private snapshot factories, then the real wire
 * codecs. This catches a UI-only field, a swapped Blessing mapping, and a
 * codec field omitted on either side. Deliberately invalid constructor ranks
 * also prove that the fixed three-byte projection fails closed to 0..III.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BlessingInspectionGameTests {

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "blessing_inspection_settler_snapshot_maps_and_bounds_target_ranks")
    public void settlerSnapshotMapsAndBoundsTargetRanks(GameTestHelper helper) {
        Settlement settlement = settlement(helper, "Inspectstead");
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(8, 1, 8));
        settler.setSettlerName("Yrsa");
        settler.bindTo(settlement.id, settlement.center);
        settlement.putRecord(settler.getUUID(), settler.getSettlerName(), Profession.NONE);
        applySettler(helper, settler, BlessingId.WARDEN_OATH, 1);
        applySettler(helper, settler, BlessingId.HEARTHWARD, 2);
        applySettler(helper, settler, BlessingId.THORNED_ROADS, 3);

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(settler.getX(), settler.getY(), settler.getZ());
        SettlerSnapshotPayload authored = settlerSnapshot(player, settler);
        SettlerSnapshotPayload wire = settlerThroughWire(helper, authored);

        helper.assertTrue(authored.equals(wire),
            "settler Blessing ranks changed or disappeared on their real codec");
        assertRanks(helper, wire.blessingRank(BlessingId.WARDEN_OATH),
            wire.blessingRank(BlessingId.HEARTHWARD),
            wire.blessingRank(BlessingId.THORNED_ROADS), "settler snapshot");

        SettlerSnapshotPayload bounded = new SettlerSnapshotPayload(
            authored.entityId(), authored.settlerId(), authored.sessionId(),
            authored.revision(), authored.canManage(),
            authored.attributeValues(), authored.knackOrdinal(), authored.traitOrdinals(),
            authored.bagItemIds(), authored.bagCounts(), authored.employerBuildingId(),
            authored.guardWatchNight(), authored.isMayor(), authored.mayorSettling(),
            true, true, authored.boonKey(), -9, 4, Integer.MAX_VALUE,
            Optional.empty());
        SettlerSnapshotPayload boundedWire = settlerThroughWire(helper, bounded);
        helper.assertTrue(boundedWire.blessingRank(BlessingId.WARDEN_OATH) == 0
                && boundedWire.blessingRank(BlessingId.HEARTHWARD) == 3
                && boundedWire.blessingRank(BlessingId.THORNED_ROADS) == 3
                && boundedWire.mourning() && boundedWire.mayorVacant(),
            "settler wire must clamp malformed target ranks to 0..III, got "
                + boundedWire.wardenOathBlessingRank() + "/"
                + boundedWire.hearthwardBlessingRank() + "/"
                + boundedWire.thornedRoadsBlessingRank());
        helper.assertTrue(boundedWire.blessingRank(null) == 0,
            "a null Blessing id must fail closed rather than alias a real rank");
        SettlerSnapshotPayload emptyRanks = new SettlerSnapshotPayload(
            authored.entityId(), authored.settlerId(), authored.sessionId(),
            authored.revision(), authored.canManage(),
            authored.attributeValues(), authored.knackOrdinal(), authored.traitOrdinals(),
            authored.bagItemIds(), authored.bagCounts(), authored.employerBuildingId(),
            authored.guardWatchNight(), authored.isMayor(), authored.mayorSettling(),
            authored.mourning(), authored.boonKey(), 0, 0, 0, Optional.empty());
        helper.assertTrue(settlerWireSize(helper, emptyRanks)
                == settlerWireSize(helper, boundedWire),
            "three settler ranks must occupy a fixed wire width at 0 and III");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "blessing_inspection_plaque_snapshot_maps_and_bounds_building_ranks")
    public void plaqueSnapshotMapsAndBoundsBuildingRanks(GameTestHelper helper) {
        Settlement settlement = settlement(helper, "Plaquestead");
        Building building = GameTestFixtures.register(helper, settlement,
            BuildingType.HOUSE, 4, 4);
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) helper.getLevel().getBlockEntity(
            building.plaquePos);
        helper.assertTrue(plaque != null,
            "setup: the registered building must have its physical plaque");
        linkPlaque(plaque, building.id);

        applyBuilding(helper, building, BlessingId.WARDEN_OATH, 1);
        applyBuilding(helper, building, BlessingId.HEARTHWARD, 2);
        applyBuilding(helper, building, BlessingId.THORNED_ROADS, 3);

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(building.plaquePos.getX() + 0.5D,
            building.plaquePos.getY() + 0.5D, building.plaquePos.getZ() + 0.5D);
        PlaqueSnapshot authored = plaqueSnapshot(player, plaque);
        PlaqueSnapshot wire = plaqueThroughWire(helper, authored);

        helper.assertTrue(authored.equals(wire),
            "plaque Blessing ranks changed or disappeared on their real codec");
        assertRanks(helper, wire.blessingRank(BlessingId.WARDEN_OATH),
            wire.blessingRank(BlessingId.HEARTHWARD),
            wire.blessingRank(BlessingId.THORNED_ROADS), "plaque snapshot");

        PlaqueSnapshot bounded = new PlaqueSnapshot(authored.pos(), authored.buildingId(),
            authored.sessionId(), authored.buildingType(),
            authored.state(), authored.revision(), authored.level(), authored.requirements(),
            authored.occupants(), authored.candidates(), authored.capacity(),
            authored.mayManage(), -1, 99, 4, Optional.empty());
        PlaqueSnapshot boundedWire = plaqueThroughWire(helper, bounded);
        helper.assertTrue(boundedWire.blessingRank(BlessingId.WARDEN_OATH) == 0
                && boundedWire.blessingRank(BlessingId.HEARTHWARD) == 3
                && boundedWire.blessingRank(BlessingId.THORNED_ROADS) == 3,
            "plaque wire must clamp malformed building ranks to 0..III, got "
                + boundedWire.wardenOathBlessingRank() + "/"
                + boundedWire.hearthwardBlessingRank() + "/"
                + boundedWire.thornedRoadsBlessingRank());
        helper.assertTrue(boundedWire.blessingRank(null) == 0,
            "a null Blessing id must fail closed rather than alias a building rank");
        PlaqueSnapshot emptyRanks = new PlaqueSnapshot(authored.pos(),
            authored.buildingId(), authored.sessionId(), authored.buildingType(),
            authored.state(), authored.revision(), authored.level(),
            authored.requirements(), authored.occupants(), authored.candidates(),
            authored.capacity(), authored.mayManage(), 0, 0, 0, Optional.empty());
        helper.assertTrue(plaqueWireSize(helper, emptyRanks)
                == plaqueWireSize(helper, boundedWire),
            "three plaque ranks must occupy a fixed wire width at 0 and III");
        helper.succeed();
    }

    private static Settlement settlement(GameTestHelper helper, String name) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), name,
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 12;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static void applySettler(GameTestHelper helper, SettlerEntity settler,
                                     BlessingId blessing, int ranks) {
        for (int rank = 0; rank < ranks; rank++) {
            helper.assertTrue(settler.applyBlessing(blessing)
                    == TargetBlessingState.ApplyResult.APPLIED,
                "setup: settler refused " + blessing + " rank " + (rank + 1));
        }
    }

    private static void applyBuilding(GameTestHelper helper, Building building,
                                      BlessingId blessing, int ranks) {
        for (int rank = 0; rank < ranks; rank++) {
            helper.assertTrue(building.applyBlessing(blessing)
                    == TargetBlessingState.ApplyResult.APPLIED,
                "setup: building refused " + blessing + " rank " + (rank + 1));
        }
    }

    private static void assertRanks(GameTestHelper helper, int warden, int hearthward,
                                    int thornedRoads, String source) {
        helper.assertTrue(warden == 1 && hearthward == 2 && thornedRoads == 3,
            source + " swapped or omitted target ranks: " + warden + "/"
                + hearthward + "/" + thornedRoads);
    }

    private static SettlerSnapshotPayload settlerSnapshot(ServerPlayer player,
                                                           SettlerEntity settler) {
        try {
            Method method = SettlerNetwork.class.getDeclaredMethod("snapshot",
                ServerPlayer.class, SettlerEntity.class, UUID.class,
                Optional.class, SettlerSnapshotPayload.Delivery.class);
            method.setAccessible(true);
            return (SettlerSnapshotPayload) method.invoke(null, player, settler,
                UUID.randomUUID(), Optional.empty(),
                SettlerSnapshotPayload.Delivery.UPDATE);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SettlerNetwork#snapshot is not callable", e);
        }
    }

    private static PlaqueSnapshot plaqueSnapshot(ServerPlayer player,
                                                  PlaqueBlockEntity plaque) {
        try {
            Method method = PlaqueNetwork.class.getDeclaredMethod("snapshot",
                ServerPlayer.class, PlaqueBlockEntity.class, UUID.class,
                PlaqueSnapshot.Delivery.class);
            method.setAccessible(true);
            return (PlaqueSnapshot) method.invoke(null, player, plaque,
                UUID.randomUUID(), PlaqueSnapshot.Delivery.UPDATE);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PlaqueNetwork#snapshot is not callable", e);
        }
    }

    private static void linkPlaque(PlaqueBlockEntity plaque, UUID buildingId) {
        try {
            Field field = PlaqueBlockEntity.class.getDeclaredField("buildingId");
            field.setAccessible(true);
            field.set(plaque, buildingId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("test fixture could not link the plaque", e);
        }
    }

    private static SettlerSnapshotPayload settlerThroughWire(
            GameTestHelper helper, SettlerSnapshotPayload snapshot) {
        RegistryFriendlyByteBuf buffer = buffer(helper);
        SettlerSnapshotPayload.CODEC.encode(buffer, snapshot);
        return SettlerSnapshotPayload.CODEC.decode(buffer);
    }

    private static PlaqueSnapshot plaqueThroughWire(GameTestHelper helper,
                                                     PlaqueSnapshot snapshot) {
        RegistryFriendlyByteBuf buffer = buffer(helper);
        PlaqueSnapshot.CODEC.encode(buffer, snapshot);
        return PlaqueSnapshot.CODEC.decode(buffer);
    }

    private static int settlerWireSize(GameTestHelper helper,
                                       SettlerSnapshotPayload snapshot) {
        RegistryFriendlyByteBuf buffer = buffer(helper);
        SettlerSnapshotPayload.CODEC.encode(buffer, snapshot);
        return buffer.writerIndex();
    }

    private static int plaqueWireSize(GameTestHelper helper, PlaqueSnapshot snapshot) {
        RegistryFriendlyByteBuf buffer = buffer(helper);
        PlaqueSnapshot.CODEC.encode(buffer, snapshot);
        return buffer.writerIndex();
    }

    private static RegistryFriendlyByteBuf buffer(GameTestHelper helper) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),
            helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
    }
}
