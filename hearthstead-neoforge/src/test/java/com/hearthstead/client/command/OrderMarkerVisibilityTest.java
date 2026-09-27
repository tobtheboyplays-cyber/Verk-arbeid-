package com.hearthstead.client.command;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OrderMarkerVisibilityTest {
    @Test void initialSnapshotDoesNotFlashExistingOrders() {
        var clock = new OrderMarkerVisibility();
        clock.snapshot(Map.of(1, new OrderMarkerVisibility.Order(7, false)), 100);
        assertFalse(clock.recent(100));
    }
    @Test void periodicSnapshotsCannotKeepTheMarkerAlive() {
        var clock = new OrderMarkerVisibility();
        clock.snapshot(Map.of(), 0);
        var order = Map.of(1, new OrderMarkerVisibility.Order(7, false));
        clock.snapshot(order, 100);
        assertTrue(clock.recent(2099));
        clock.snapshot(order, 2099);
        assertFalse(clock.recent(2100));
        assertEquals(Long.MAX_VALUE, clock.age(2100));
    }
    @Test void newOrdersAndStanceChangesFlashButRemovalDoesNot() {
        var clock = new OrderMarkerVisibility();
        clock.snapshot(Map.of(1, new OrderMarkerVisibility.Order(7, false)), 0);
        clock.snapshot(Map.of(1, new OrderMarkerVisibility.Order(8, false)), 3000);
        assertTrue(clock.recent(3000));
        clock.snapshot(Map.of(1, new OrderMarkerVisibility.Order(8, true)), 6000);
        assertTrue(clock.recent(6000));
        clock.snapshot(Map.of(), 9000);
        assertFalse(clock.recent(9000));
    }
    @Test void dispatchedOrdersExpireAndDisconnectClearsEveryTimer() {
        var clock = new OrderMarkerVisibility();
        clock.issued(100);
        assertTrue(clock.recent(2099));
        assertFalse(clock.recent(2100));
        clock.issued(3000);
        clock.clear();
        assertFalse(clock.recent(3001));
        clock.snapshot(Map.of(1, new OrderMarkerVisibility.Order(8, false)), 3002);
        assertFalse(clock.recent(3002));
    }
    @Test void attentionIsPerSoldierAndMatchesExistingFortyPercentThreshold() {
        assertTrue(OrderMarkerVisibility.needsAttention(true, 20, 20));
        assertTrue(OrderMarkerVisibility.needsAttention(false, 7.9F, 20));
        assertFalse(OrderMarkerVisibility.needsAttention(false, 8, 20));
        assertFalse(OrderMarkerVisibility.needsAttention(false, 20, 20));
        assertFalse(OrderMarkerVisibility.needsAttention(false, 0, 20));
        assertFalse(OrderMarkerVisibility.visible(false, false, false, false));
        assertTrue(OrderMarkerVisibility.visible(false, false, false, true));
        assertTrue(OrderMarkerVisibility.visible(false, true, false, false));
        assertTrue(OrderMarkerVisibility.visible(false, false, true, false));
        assertTrue(OrderMarkerVisibility.visible(true, false, false, false));
    }
}
