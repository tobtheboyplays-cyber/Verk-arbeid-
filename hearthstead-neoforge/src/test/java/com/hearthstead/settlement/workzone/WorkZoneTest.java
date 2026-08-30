package com.hearthstead.settlement.workzone;

import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.state.FoundingJourney;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure persistence and optimistic-lock preflight for the Work Zone schema. */
class WorkZoneTest {
    private static final ResourceLocation OVERWORLD =
        ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    @Test
    void betweenNormalizesInclusiveThreeDimensionalBounds() {
        UUID settlement = UUID.randomUUID();
        UUID building = UUID.randomUUID();
        WorkZone zone = WorkZone.between(settlement, building,
            WorkZone.Type.LUMBER, OVERWORLD,
            new BlockPos(8, 12, 7), new BlockPos(3, 4, 2), 1);

        assertEquals(new BlockPos(3, 4, 2), zone.min());
        assertEquals(new BlockPos(8, 12, 7), zone.max());
        assertEquals(6, zone.sizeX());
        assertEquals(9, zone.sizeY());
        assertEquals(6, zone.sizeZ());
        assertEquals(324L, zone.volume());
        assertTrue(zone.contains(zone.min()));
        assertTrue(zone.contains(zone.max()));
        assertFalse(zone.contains(new BlockPos(3, 13, 2)),
            "Y is authoritative; this is not a flat X/Z claim");
    }

    @Test
    void strictNbtRoundTripPreservesEveryIdentityAndBound() {
        WorkZone original = zone(UUID.randomUUID(), UUID.randomUUID(), 7);
        assertEquals(original, WorkZone.readNbt(original.writeNbt()));
    }

    @Test
    void legacyFlatFarmZoneMigratesToBoundedGrowingHeadroom() {
        UUID settlement = UUID.randomUUID();
        UUID building = UUID.randomUUID();
        WorkZone flat = WorkZone.between(settlement, building,
            WorkZone.Type.FARM, OVERWORLD, new BlockPos(2, 67, 3),
            new BlockPos(6, 67, 7), 1);
        CompoundTag legacy = flat.writeNbt();
        legacy.putInt("DataVersion", 1);

        WorkZone migrated = WorkZone.readNbt(legacy);

        assertEquals(new BlockPos(2, 67, 3), migrated.min());
        assertEquals(new BlockPos(6, 70, 7), migrated.max());
        assertEquals(4, migrated.sizeY());
        assertEquals(1, migrated.revision());
    }

    @Test
    void currentFlatFarmZoneIsRejectedInsteadOfBecomingPermanentIdle() {
        WorkZone flat = WorkZone.between(UUID.randomUUID(), UUID.randomUUID(),
            WorkZone.Type.FARM, OVERWORLD, new BlockPos(2, 67, 3),
            new BlockPos(6, 67, 7), 1);

        assertNull(WorkZone.readNbt(flat.writeNbt()));
    }

    @Test
    void legacyLumberZoneKeepsItsExactHeight() {
        WorkZone lumber = WorkZone.between(UUID.randomUUID(), UUID.randomUUID(),
            WorkZone.Type.LUMBER, OVERWORLD, new BlockPos(2, 67, 3),
            new BlockPos(6, 67, 7), 1);
        CompoundTag legacy = lumber.writeNbt();
        legacy.putInt("DataVersion", 1);

        assertEquals(lumber, WorkZone.readNbt(legacy));
    }

    @Test
    void buildingLoaderAcceptsOnlyPairedLegacySchemaAndMigratesFarm() {
        UUID settlement = UUID.randomUUID();
        Building farmhouse = building(UUID.randomUUID(), BuildingType.FARMHOUSE);
        WorkZone flat = WorkZone.between(settlement, farmhouse.id,
            WorkZone.Type.FARM, OVERWORLD, new BlockPos(2, 67, 3),
            new BlockPos(6, 67, 7), 1);
        assertTrue(farmhouse.commitWorkZone(0, flat));
        CompoundTag legacy = farmhouse.writeNbt();
        legacy.putInt("WorkZoneSchema", 1);
        legacy.getCompound("WorkZone").putInt("DataVersion", 1);

        Building migrated = Building.readNbt(legacy,
            SettlementSavedData.CURRENT_DATA_VERSION);

        assertFalse(migrated.workZoneQuarantined());
        assertEquals(new BlockPos(6, 70, 7),
            migrated.workZone().orElseThrow().max());
        assertEquals(1, migrated.workZoneRevision());

        CompoundTag torn = legacy.copy();
        torn.putInt("WorkZoneSchema", WorkZone.DATA_VERSION);
        Building rejected = Building.readNbt(torn,
            SettlementSavedData.CURRENT_DATA_VERSION);
        assertTrue(rejected.workZoneQuarantined(),
            "mixed header/payload versions must fail closed");
    }

    @Test
    void malformedPresentNbtNeverDecodesAsNoZone() {
        WorkZone original = zone(UUID.randomUUID(), UUID.randomUUID(), 1);
        CompoundTag missing = original.writeNbt();
        missing.remove("Revision");
        assertNull(WorkZone.readNbt(missing));

        CompoundTag mismatchedType = original.writeNbt();
        mismatchedType.putString("Type", WorkZone.Type.FARM.id());
        assertNull(WorkZone.readNbt(mismatchedType));

        CompoundTag badDimension = original.writeNbt();
        badDimension.putString("Dimension", "%%%not-a-dimension");
        assertNull(WorkZone.readNbt(badDimension));

        CompoundTag zeroRevision = original.writeNbt();
        zeroRevision.putInt("Revision", 0);
        assertNull(WorkZone.readNbt(zeroRevision));

        CompoundTag enormous = original.writeNbt();
        enormous.put("Max", net.minecraft.nbt.NbtUtils.writeBlockPos(
            new BlockPos(Integer.MAX_VALUE, 9, 6)));
        assertNull(WorkZone.readNbt(enormous),
            "current NBT may not overflow/bypass the frozen scan limits");
    }

    @Test
    void persistedZoneOutsideItsParentSettlementQuarantinesAtRootLoad() {
        UUID settlementId = UUID.randomUUID();
        Settlement settlement = new Settlement(settlementId, "Root", BlockPos.ZERO);
        Building camp = building(UUID.randomUUID(), BuildingType.LUMBER_CAMP);
        settlement.buildings.add(camp);
        WorkZone outside = WorkZone.between(settlementId, camp.id,
            WorkZone.Type.LUMBER, OVERWORLD, new BlockPos(80, 2, 80),
            new BlockPos(84, 8, 84), 1);
        assertTrue(camp.commitWorkZone(0, outside));

        Settlement loaded = Settlement.readNbt(settlement.writeNbt(),
            SettlementSavedData.CURRENT_DATA_VERSION);
        Building loadedCamp = loaded.buildings.getFirst();
        assertTrue(loadedCamp.workZoneQuarantined());
        assertTrue(loadedCamp.workZone().isEmpty());
        assertEquals(1, loadedCamp.workZoneRevision(),
            "quarantine must preserve the last monotonic revision");
    }

    @Test
    void persistedZoneWithMixedCornerOutsideSphereQuarantinesAtRootLoad() {
        UUID settlementId = UUID.randomUUID();
        Settlement settlement = new Settlement(settlementId, "Mixed Corner",
            BlockPos.ZERO);
        settlement.radius = 48;
        Building camp = building(UUID.randomUUID(), BuildingType.LUMBER_CAMP);
        settlement.buildings.add(camp);
        WorkZone diagonalEscape = WorkZone.between(settlementId, camp.id,
            WorkZone.Type.LUMBER, OVERWORLD,
            new BlockPos(-40, 0, 0), new BlockPos(0, 0, 40), 1);

        assertTrue(settlement.inside(diagonalEscape.min()));
        assertTrue(settlement.inside(diagonalEscape.max()));
        assertFalse(settlement.insideBox(diagonalEscape.min(),
            diagonalEscape.max()),
            "a mixed X/Z corner lies outside the spherical settlement");
        assertTrue(camp.commitWorkZone(0, diagonalEscape));

        Settlement loaded = Settlement.readNbt(settlement.writeNbt(),
            SettlementSavedData.CURRENT_DATA_VERSION);
        Building loadedCamp = loaded.buildings.getFirst();
        assertTrue(loadedCamp.workZoneQuarantined());
        assertTrue(loadedCamp.workZone().isEmpty());
        assertEquals(1, loadedCamp.workZoneRevision(),
            "quarantine must preserve the last monotonic revision");
    }

    @Test
    void duplicateBuildingIdentityIsRejectedByPersistenceAndWorkerLookup() {
        UUID duplicated = UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        Settlement settlement = new Settlement(UUID.randomUUID(), "Duplicate",
            BlockPos.ZERO);
        Building first = building(duplicated, BuildingType.LUMBER_CAMP);
        Building second = building(duplicated, BuildingType.LUMBER_CAMP);
        first.workers.add(worker);
        settlement.buildings.add(first);
        settlement.buildings.add(second);

        assertNull(Employment.employerOf(settlement, worker),
            "runtime lookup must not choose the first ambiguous identity");
        assertThrows(SettlementSavedData.DataVersionException.class,
            () -> Settlement.readNbt(settlement.writeNbt(),
                SettlementSavedData.CURRENT_DATA_VERSION),
            "restart must reject duplicate building UUIDs before worker use");
    }

    @Test
    void restartQuarantinesWorkZoneFromAnotherDimensionWithoutRevisionReset() {
        ResourceLocation nether = ResourceLocation.fromNamespaceAndPath(
            "minecraft", "the_nether");
        UUID settlementId = UUID.randomUUID();
        Settlement settlement = new Settlement(settlementId, "Dimension",
            BlockPos.ZERO);
        Building camp = building(UUID.randomUUID(), BuildingType.LUMBER_CAMP);
        settlement.buildings.add(camp);
        WorkZone wrongDimension = WorkZone.between(settlementId, camp.id,
            WorkZone.Type.LUMBER, nether, new BlockPos(2, 3, 2),
            new BlockPos(6, 9, 6), 1);
        assertTrue(camp.commitWorkZone(0, wrongDimension));

        Settlement restarted = Settlement.readNbt(settlement.writeNbt(),
            SettlementSavedData.CURRENT_DATA_VERSION);
        Building restartedCamp = restarted.buildings.getFirst();
        assertTrue(restarted.quarantineWorkZonesForDimension(OVERWORLD));
        assertTrue(restartedCamp.workZoneQuarantined());
        assertTrue(restartedCamp.workZone().isEmpty());
        assertEquals(1, restartedCamp.workZoneRevision(),
            "dimension quarantine must preserve optimistic-lock history");
        assertFalse(restarted.quarantineWorkZonesForDimension(OVERWORLD),
            "sticky quarantine must be stable on later SavedData access");
    }

    @Test
    void currentBuildingWithAbsentHeaderQuarantinesFailClosed() {
        Building building = building(UUID.randomUUID(), BuildingType.LUMBER_CAMP);
        CompoundTag tag = building.writeNbt();
        tag.remove("WorkZoneRevision");

        Building loaded = Building.readNbt(tag,
            SettlementSavedData.CURRENT_DATA_VERSION);
        assertTrue(loaded.workZoneQuarantined());
        assertTrue(loaded.workZone().isEmpty());
        assertFalse(loaded.commitWorkZone(0,
            zone(UUID.randomUUID(), loaded.id, 1)));
    }

    @Test
    void legacyBuildingAbsenceMigratesToTruthfulRevisionZeroIdle() {
        Building building = building(UUID.randomUUID(), BuildingType.LUMBER_CAMP);
        CompoundTag tag = building.writeNbt();
        tag.remove("WorkZoneSchema");
        tag.remove("WorkZoneRevision");
        tag.remove("WorkZoneQuarantined");
        tag.remove("WorkZone");

        Building loaded = Building.readNbt(tag, 4);
        assertFalse(loaded.workZoneQuarantined());
        assertEquals(0, loaded.workZoneRevision());
        assertTrue(loaded.workZone().isEmpty());
    }

    @Test
    void malformedCurrentZoneQuarantinesInsteadOfResettingRevision() {
        UUID settlement = UUID.randomUUID();
        Building building = building(UUID.randomUUID(), BuildingType.LUMBER_CAMP);
        WorkZone committed = zone(settlement, building.id, 1);
        assertTrue(building.commitWorkZone(0, committed));
        CompoundTag tag = building.writeNbt();
        tag.getCompound("WorkZone").putInt("Revision", 2);

        Building loaded = Building.readNbt(tag,
            SettlementSavedData.CURRENT_DATA_VERSION);
        assertTrue(loaded.workZoneQuarantined());
        assertEquals(1, loaded.workZoneRevision(),
            "the last persisted monotonic revision must not reset to zero");
        assertTrue(loaded.workZone().isEmpty());
    }

    @Test
    void optimisticRevisionAllowsExactlyOneCompetingCommit() {
        UUID settlement = UUID.randomUUID();
        Building building = building(UUID.randomUUID(), BuildingType.LUMBER_CAMP);
        WorkZone playerOne = zone(settlement, building.id, 1);
        WorkZone playerTwo = WorkZone.between(settlement, building.id,
            WorkZone.Type.LUMBER, OVERWORLD, new BlockPos(10, 3, 10),
            new BlockPos(13, 8, 13), 1);

        assertTrue(building.commitWorkZone(0, playerOne));
        assertFalse(building.commitWorkZone(0, playerTwo),
            "the second player/replayed expected revision must be stale");
        assertEquals(playerOne, building.workZone().orElseThrow());
        assertEquals(1, building.workZoneRevision());
    }

    @Test
    void wrongBuildingOrTypeCannotConsumeARevision() {
        UUID settlement = UUID.randomUUID();
        Building camp = building(UUID.randomUUID(), BuildingType.LUMBER_CAMP);
        WorkZone wrongBuilding = zone(settlement, UUID.randomUUID(), 1);
        WorkZone wrongType = WorkZone.between(settlement, camp.id,
            WorkZone.Type.FARM, OVERWORLD, BlockPos.ZERO,
            new BlockPos(2, 2, 2), 1);

        assertFalse(camp.commitWorkZone(0, wrongBuilding));
        assertFalse(camp.commitWorkZone(0, wrongType));
        assertEquals(0, camp.workZoneRevision());
        assertTrue(camp.workZone().isEmpty());
    }

    @Test
    void synchronousValidationHasAFrozenReadBudget() {
        assertEquals(16_384L, WorkZoneService.MAX_VOLUME);
        assertEquals(32_768, WorkZoneService.MAX_CONTENT_BLOCK_READS);
        assertEquals(65_536L,
            WorkZoneService.worstCaseContentReadsPerTransaction(),
            "preview plus confirm may never become an unbounded server scan");
    }

    @Test
    void v1JourneyDeliveryMigratesBackToMandatoryZoneStep() {
        CompoundTag v1 = new CompoundTag();
        v1.putInt("DataVersion", 1);
        v1.putInt("PhaseWireId", 2);
        v1.putString("Phase", "deliver_first_log");
        v1.putInt("Revision", 2);

        FoundingJourney journey = FoundingJourney.readNbt(v1);
        assertEquals(FoundingJourney.Phase.SET_LUMBER_ZONE, journey.phase());
        assertEquals(2, journey.revision());
        assertFalse(journey.noteFirstLogDelivered());
        assertTrue(journey.noteLumberZoneCommitted());
        assertTrue(journey.noteFirstLogDelivered());
        assertEquals(4, journey.revision());
    }

    @Test
    void v1CompletedJourneyStaysTerminalAtV2Revision() {
        CompoundTag v1 = new CompoundTag();
        v1.putInt("DataVersion", 1);
        v1.putInt("PhaseWireId", 3);
        v1.putString("Phase", "complete");
        v1.putInt("Revision", 3);

        FoundingJourney journey = FoundingJourney.readNbt(v1);
        assertEquals(FoundingJourney.Phase.COMPLETE, journey.phase());
        assertEquals(4, journey.revision());
        assertFalse(journey.noteLumberZoneCommitted());
        assertFalse(journey.noteFirstLogDelivered());
    }

    private static WorkZone zone(UUID settlementId, UUID buildingId,
                                 int revision) {
        return WorkZone.between(settlementId, buildingId, WorkZone.Type.LUMBER,
            OVERWORLD, new BlockPos(2, 3, 2), new BlockPos(6, 9, 6), revision);
    }

    private static Building building(UUID id, BuildingType type) {
        Building building = new Building(id, type, BlockPos.ZERO,
            BlockPos.ZERO, new BoundingBox(BlockPos.ZERO));
        building.valid = true;
        return building;
    }
}
