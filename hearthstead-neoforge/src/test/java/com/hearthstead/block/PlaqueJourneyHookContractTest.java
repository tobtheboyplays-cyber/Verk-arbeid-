package com.hearthstead.block;

import com.hearthstead.building.BuildingType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaqueJourneyHookContractTest {

    @Test
    void healthyHouseBedCapacityChangeNotifiesJourney() {
        assertTrue(PlaqueBlockEntity.shouldNotifyJourneyOfBuildingUpdate(
            BuildingType.HOUSE, false, true, false, true));
        assertFalse(PlaqueBlockEntity.shouldNotifyJourneyOfBuildingUpdate(
            BuildingType.HOUSE, false, true, false, false));
        assertFalse(PlaqueBlockEntity.shouldNotifyJourneyOfBuildingUpdate(
            BuildingType.LUMBER_CAMP, false, true, false, true));
    }

    @Test
    void originalLinkTransitionsStillNotifyJourney() {
        assertTrue(PlaqueBlockEntity.shouldNotifyJourneyOfBuildingUpdate(
            BuildingType.HOUSE, true, false, false, false));
        assertTrue(PlaqueBlockEntity.shouldNotifyJourneyOfBuildingUpdate(
            BuildingType.HOUSE, false, false, false, false));
        assertTrue(PlaqueBlockEntity.shouldNotifyJourneyOfBuildingUpdate(
            BuildingType.HOUSE, false, true, true, false));
    }
}
