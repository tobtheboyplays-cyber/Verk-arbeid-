package com.hearthstead.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuardOrderWireContractTest {

    @Test
    void existingActionIdsStayFrozenAndNewActionsOnlyAppend() {
        assertEquals(0, GuardOrderActionPayload.Kind.REFRESH.wireId());
        assertEquals(1, GuardOrderActionPayload.Kind.HOLD_HERE.wireId());
        assertEquals(2, GuardOrderActionPayload.Kind.DEFEND_HEARTH.wireId());
        assertEquals(3, GuardOrderActionPayload.Kind.ADD_PATROL_POINT.wireId());
        assertEquals(4,
            GuardOrderActionPayload.Kind.REMOVE_PATROL_POINT.wireId());
        assertEquals(5, GuardOrderActionPayload.Kind.START_PATROL.wireId());
        assertEquals(6, GuardOrderActionPayload.Kind.CLEAR_ORDER.wireId());
        assertEquals(7, GuardOrderActionPayload.Kind.TOWER_POST.wireId());
        assertEquals(8,
            GuardOrderActionPayload.Kind.TOGGLE_TRAVERSAL.wireId());
        assertEquals(GuardOrderActionPayload.Kind.UNKNOWN,
            GuardOrderActionPayload.Kind.fromWireId(9));
        assertEquals(GuardOrderActionPayload.Kind.UNKNOWN,
            GuardOrderActionPayload.Kind.fromWireId(-1));
    }
}
