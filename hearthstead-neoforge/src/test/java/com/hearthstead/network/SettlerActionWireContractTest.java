package com.hearthstead.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettlerActionWireContractTest {

    @Test
    void existingIdsStayFrozenAndControlActionsOnlyAppend() {
        assertEquals(0, SettlerActionPayload.Kind.DISMISS.wireId());
        assertEquals(1, SettlerActionPayload.Kind.APPOINT.wireId());
        assertEquals(2, SettlerActionPayload.Kind.CLOSE.wireId());
        assertEquals(3, SettlerActionPayload.Kind.OPEN_INVENTORY.wireId());
        assertEquals(4, SettlerActionPayload.Kind.EDIT_WORK_ZONE.wireId());
        assertEquals(5, SettlerActionPayload.Kind.OPEN_WORKPLACE.wireId());
        assertEquals(6, SettlerActionPayload.Kind.LOCATE.wireId());

        for (int wireId = 0; wireId <= 6; wireId++) {
            assertEquals(wireId,
                SettlerActionPayload.Kind.fromWireId(wireId).wireId());
        }
        assertEquals(SettlerActionPayload.Kind.UNKNOWN,
            SettlerActionPayload.Kind.fromWireId(7));
        assertEquals(SettlerActionPayload.Kind.UNKNOWN,
            SettlerActionPayload.Kind.fromWireId(-1));
        assertEquals(SettlerActionPayload.Kind.UNKNOWN,
            SettlerActionPayload.Kind.fromWireId(Integer.MAX_VALUE));
    }
}
