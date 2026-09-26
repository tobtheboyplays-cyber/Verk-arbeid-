package com.hearthstead.settlement.journey;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FisherJourneyDefinitionTest {
    @Test void eitherFoodRouteClosesAndSurvivesReload() {
        for (boolean fisher : new boolean[] {false, true}) {
            var settlement = JourneyStateTest.uuid(1);
            var state = JourneyState.fresh(settlement);
            for (JourneyStep step : JourneyDefinition.CURRENT.orderedSteps()) {
                if (step.ordinal() > JourneyDefinition.CURRENT.step(
                        JourneyIds.FJ_380_WAREHOUSE_RECEIVES_CROP).orElseThrow().ordinal()) break;
                var events = step.requiredEvents();
                if (step.id().equals(JourneyIds.FJ_330_SET_FARM_ZONE)
                    || step.id().equals(JourneyIds.FJ_360_SUPPLY_FIRST_SEED)) {
                    events = java.util.List.of(events.get(fisher ? 1 : 0));
                }
                for (JourneyEvent event : events) {
                    state.record(JourneyStateTest.evidence(step.id(), event,
                        JourneyStateTest.uuid(9000 + step.ordinal()), JourneySource.SURVIVAL,
                        JourneyOutcome.NONE), JourneyDefinition.CURRENT);
                }
            }
            assertTrue(state.isCompleted(JourneyIds.FJ_380_WAREHOUSE_RECEIVES_CROP));
            var loaded = JourneyState.readNbt(state.writeNbt(), settlement);
            assertNotEquals(JourneyPresentationMode.QUARANTINED, loaded.mode());
            assertTrue(loaded.isCompleted(JourneyIds.FJ_380_WAREHOUSE_RECEIVES_CROP));
        }
    }

    @Test void foodSiteAcceptsValidatedFisheryWithoutWeakeningLumberZone() {
        assertTrue(JourneyDefinition.CURRENT.step(JourneyIds.FJ_330_SET_FARM_ZONE)
            .orElseThrow().accepts(JourneyEvent.BUILDING_LINKED_VALID_COMMITTED));
        assertTrue(JourneyDefinition.CURRENT.step(JourneyIds.FJ_330_SET_FARM_ZONE)
            .orElseThrow().accepts(JourneyEvent.WORK_ZONE_COMMITTED));
        assertFalse(JourneyDefinition.CURRENT.step(JourneyIds.FJ_140_SET_LUMBER_ZONE)
            .orElseThrow().accepts(JourneyEvent.BUILDING_LINKED_VALID_COMMITTED));
    }
    @Test void foodProductionAcceptsCatchAndPreservesSeedAuthority() {
        var step = JourneyDefinition.CURRENT.step(JourneyIds.FJ_360_SUPPLY_FIRST_SEED).orElseThrow();
        assertTrue(step.accepts(JourneyEvent.MATERIAL_INPUT_COMMITTED));
        assertTrue(step.accepts(JourneyEvent.WORKPLACE_OUTPUT_COMMITTED));
        assertFalse(step.accepts(JourneyEvent.SETTLER_INVENTORY_VIEW_OPENED));
    }
}
