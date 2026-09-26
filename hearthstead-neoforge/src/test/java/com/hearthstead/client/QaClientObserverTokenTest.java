package com.hearthstead.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class QaClientObserverTokenTest {

    @Test
    void nestedUiStateCannotBreakTheStrictAckGrammar() {
        String encoded = QaClientObserver.safeToken(
            "view=mayor,panel=16:25:326:224,ledgerOverlap=true");

        assertEquals(
            "view:mayor,panel:16:25:326:224,ledgerOverlap:true", encoded);
        assertFalse(encoded.contains("="),
            "the outer ACK field must own the token's only equals sign");
    }

    @Test
    void blankUiStateRemainsAnExplicitToken() {
        assertEquals("none", QaClientObserver.safeToken("  "));
    }
}
