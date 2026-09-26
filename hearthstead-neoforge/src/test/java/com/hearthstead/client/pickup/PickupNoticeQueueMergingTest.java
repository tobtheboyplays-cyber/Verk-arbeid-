package com.hearthstead.client.pickup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PickupNoticeQueueMergingTest {
    @Test
    void mergingRefreshesDisplayButKeepsOriginalBirthAndRowPosition() {
        PickupNoticeQueue<String, String> queue = new PickupNoticeQueue<>();
        queue.add("log", "Old name", 2, 0, 3, 4_000);
        queue.add("stone", "Stone", 1, 10, 3, 4_000);
        queue.add("log", "New name", 3, 20, 3, 4_000);
        assertEquals(List.of("log", "stone"),
            queue.entries().stream().map(PickupNoticeQueue.Entry::key).toList());
        var merged = queue.entries().get(0);
        assertEquals("New name", merged.display());
        assertEquals(5L, merged.count());
        assertEquals(0L, merged.bornMs());
        assertEquals(20L, merged.refreshedMs());
    }

    @Test
    void equalRefreshTimesEvictTheOldestInsertionAndZeroRowsClampToOne() {
        PickupNoticeQueue<String, String> queue = new PickupNoticeQueue<>();
        queue.add("a", "A", 1, 0, 2, 4_000);
        queue.add("b", "B", 1, 0, 2, 4_000);
        queue.add("c", "C", 1, 1, 2, 4_000);
        assertEquals(List.of("b", "c"),
            queue.entries().stream().map(PickupNoticeQueue.Entry::key).toList());
        queue.add("d", "D", 1, 2, 0, 4_000);
        assertEquals(List.of("d"),
            queue.entries().stream().map(PickupNoticeQueue.Entry::key).toList());
    }
}
