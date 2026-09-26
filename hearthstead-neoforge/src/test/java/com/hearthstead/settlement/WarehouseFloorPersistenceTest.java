package com.hearthstead.settlement;

import com.hearthstead.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BH-14 (Codex P2): an old-save warehouse whose grandfathered level is still
 * pending (-1, resolved by the first complete container scan) must stay
 * pending across any number of save/load cycles before that scan. Clamping
 * the sentinel to 1 silently capped a formerly large warehouse at level 1.
 */
class WarehouseFloorPersistenceTest {

    private static Building warehouse() {
        return new Building(UUID.randomUUID(), BuildingType.WAREHOUSE,
            BlockPos.ZERO, BlockPos.ZERO, new BoundingBox(BlockPos.ZERO));
    }

    private static Building roundTrip(Building building) {
        return Building.readNbt(building.writeNbt());
    }

    @Test
    void oldSaveWithoutAFloorLoadsAsPending() {
        CompoundTag tag = warehouse().writeNbt();
        tag.remove("WarehouseLevelFloor");
        assertEquals(Building.WAREHOUSE_FLOOR_PENDING, Building.readNbt(tag).warehouseLevelFloor);
    }

    @Test
    void pendingFloorSurvivesRepeatedSaveAndLoadBeforeTheFirstScan() {
        Building building = warehouse();
        building.warehouseLevelFloor = Building.WAREHOUSE_FLOOR_PENDING;
        Building once = roundTrip(building);
        assertEquals(Building.WAREHOUSE_FLOOR_PENDING, once.warehouseLevelFloor, "first reload");
        Building twice = roundTrip(once);
        assertEquals(Building.WAREHOUSE_FLOOR_PENDING, twice.warehouseLevelFloor, "second reload");
    }

    @Test
    void resolvedFloorsAreKeptAndOutOfRangeValuesClamped() {
        for (int level = 1; level <= 5; level++) {
            Building building = warehouse();
            building.warehouseLevelFloor = level;
            assertEquals(level, roundTrip(building).warehouseLevelFloor, "level " + level);
        }
        CompoundTag high = warehouse().writeNbt();
        high.putInt("WarehouseLevelFloor", 9);
        assertEquals(5, Building.readNbt(high).warehouseLevelFloor);
        CompoundTag low = warehouse().writeNbt();
        low.putInt("WarehouseLevelFloor", -7);
        assertEquals(1, Building.readNbt(low).warehouseLevelFloor);
    }
}
