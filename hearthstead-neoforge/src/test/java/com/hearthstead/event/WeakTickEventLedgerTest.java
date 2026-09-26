package com.hearthstead.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WeakTickEventLedgerTest {

    @Test
    void sameTerminalObjectReusesOneIdentity() {
        WeakTickEventLedger<Object, Object> ledger = new WeakTickEventLedger<>();
        Object server = new Object();
        Object terminal = new Object();

        UUID first = ledger.actionFor(server, terminal, 20L, 4);
        UUID replay = ledger.actionFor(server, terminal, 20L, 4);

        assertNotNull(first);
        assertEquals(first, replay);
        assertEquals(1, ledger.eventCount(server));
    }

    @Test
    void equalButDistinctTerminalsKeepDistinctIdentity() {
        record EqualTerminal(int value) {
        }
        WeakTickEventLedger<Object, EqualTerminal> ledger =
            new WeakTickEventLedger<>();
        Object server = new Object();
        EqualTerminal firstEvent = new EqualTerminal(7);
        EqualTerminal secondEvent = new EqualTerminal(7);

        UUID first = ledger.actionFor(server, firstEvent, 20L, 4);
        UUID second = ledger.actionFor(server, secondEvent, 20L, 4);

        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second,
            "event equality must never collapse two physical terminals");
        assertEquals(2, ledger.eventCount(server));
    }

    @Test
    void onlyRealTerminalCallsSpendTheBoundAndNextTickStartsFresh() {
        WeakTickEventLedger<Object, Object> ledger = new WeakTickEventLedger<>();
        Object server = new Object();

        assertNull(ledger.actionFor(server, null, 30L, 2),
            "a rejected incoming hit has no terminal object and spends nothing");
        assertEquals(0, ledger.eventCount(server));

        List<Object> terminals = new ArrayList<>();
        terminals.add(new Object());
        terminals.add(new Object());
        terminals.add(new Object());
        Object nextTickTerminal = new Object();
        assertNotNull(ledger.actionFor(server, terminals.get(0), 30L, 2));
        assertNotNull(ledger.actionFor(server, terminals.get(1), 30L, 2));
        assertNull(ledger.actionFor(server, terminals.get(2), 30L, 2));
        assertEquals(2, ledger.eventCount(server));

        assertNotNull(ledger.actionFor(server, nextTickTerminal, 31L, 2));
        assertEquals(1, ledger.eventCount(server));
    }

    @Test
    void serverBucketsAreIsolatedAndExplicitlyCleared() {
        WeakTickEventLedger<Object, Object> ledger = new WeakTickEventLedger<>();
        Object firstServer = new Object();
        Object secondServer = new Object();
        Object firstTerminal = new Object();
        Object secondTerminal = new Object();

        assertNotNull(ledger.actionFor(firstServer, firstTerminal, 50L, 1));
        assertNotNull(ledger.actionFor(secondServer, secondTerminal, 50L, 1));
        assertEquals(2, ledger.serverCount());

        ledger.clear(firstServer);
        assertEquals(0, ledger.eventCount(firstServer));
        assertEquals(1, ledger.eventCount(secondServer));
        assertEquals(1, ledger.serverCount());
    }

    @Test
    void invalidTickOrCapCannotCreateState() {
        WeakTickEventLedger<Object, Object> ledger = new WeakTickEventLedger<>();
        Object server = new Object();

        assertNull(ledger.actionFor(server, new Object(), -1L, 2));
        assertNull(ledger.actionFor(server, new Object(), 1L, 0));
        assertEquals(0, ledger.serverCount());
    }
}
