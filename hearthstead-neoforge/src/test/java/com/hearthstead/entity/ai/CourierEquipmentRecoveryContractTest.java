package com.hearthstead.entity.ai;

import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.request.RequestBlocker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic restart decision for a physically loaded equipment route. */
class CourierEquipmentRecoveryContractTest {

    @Test
    void bagStageWithConcreteTargetFailureReturnsBeforeGenericCargo() {
        assertTrue(CourierWorkGoal.shouldReturnPersistedEquipment(
            EquipmentRequest.TraceStage.COURIER_BAG,
            RequestBlocker.TARGET_UNLOADED));
        assertTrue(CourierWorkGoal.shouldReturnPersistedEquipment(
            EquipmentRequest.TraceStage.COURIER_BAG,
            RequestBlocker.TARGET_INVALID));
        assertTrue(CourierWorkGoal.shouldReturnPersistedEquipment(
            EquipmentRequest.TraceStage.COURIER_BAG,
            RequestBlocker.TARGET_FULL));
    }

    @Test
    void validBagRouteContinuesAndSourceStageNeverPretendsToReturnCargo() {
        assertFalse(CourierWorkGoal.shouldReturnPersistedEquipment(
            EquipmentRequest.TraceStage.COURIER_BAG, RequestBlocker.NONE));
        assertFalse(CourierWorkGoal.shouldReturnPersistedEquipment(
            EquipmentRequest.TraceStage.SOURCE,
            RequestBlocker.TARGET_UNLOADED));
    }
}
