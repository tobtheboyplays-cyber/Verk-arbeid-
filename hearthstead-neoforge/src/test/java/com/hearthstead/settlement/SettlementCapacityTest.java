package com.hearthstead.settlement;

import com.hearthstead.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Owner decision 26 Sep: a new settlement holds its 4 founders; beds raise that beyond 4. */
class SettlementCapacityTest {

    @Test
    void founderPlacesMatchTheFounderCount() {
        assertEquals(SettlementManager.FOUNDER_COUNT, Settlement.FOUNDER_PLACES,
            "a fresh village must never read 4 / 3 people");
    }

    @Test
    void freshSettlementHoldsItsFoundersWithoutBeds() {
        Settlement settlement = settlement();
        assertEquals(0, settlement.validBedCount());
        assertEquals(4, settlement.capacity());
    }

    @Test
    void bedsOnlyAddRoomBeyondTheFounders() {
        Settlement settlement = settlement();
        Building house = home(settlement, 1);
        assertEquals(4, settlement.capacity(), "founders take the first beds");
        addBeds(house, 3);
        assertEquals(4, settlement.capacity());
        Building lodging = home(settlement, 2);
        assertEquals(6, settlement.capacity(), "six beds hold six settlers, not ten");
        lodging.valid = false;
        assertEquals(4, settlement.capacity(), "a lost home falls back to the founders");
    }

    private static Settlement settlement() {
        return new Settlement(UUID.randomUUID(), "Capacity", BlockPos.ZERO);
    }

    private static Building home(Settlement settlement, int beds) {
        Building building = new Building(UUID.randomUUID(), BuildingType.HOUSE,
            BlockPos.ZERO, BlockPos.ZERO, new BoundingBox(BlockPos.ZERO));
        building.valid = true;
        addBeds(building, beds);
        settlement.buildings.add(building);
        return building;
    }

    private static void addBeds(Building building, int count) {
        for (int i = 0; i < count; i++) {
            building.beds.add(new BlockPos(building.beds.size(), 64, 0));
        }
    }
}
