package com.hearthstead.client.pickup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PickupNoticeQueueTest {
    private static final long HOLD = 4_000L;
    private static final int ROWS = 6;

    private static List<String> keys(PickupNoticeQueue<String, String> queue) {
        return queue.entries().stream().map(PickupNoticeQueue.Entry::key).toList();
    }

    @Test
    void identicalItemsMergeAndRefreshTheirTimer() {
        PickupNoticeQueue<String, String> queue = new PickupNoticeQueue<>();
        queue.add("oak_log", "Oak Log", 4, 0L, ROWS, HOLD);
        queue.add("oak_log", "Oak Log", 8, 3_000L, ROWS, HOLD);
        assertEquals(1, queue.size());
        PickupNoticeQueue.Entry<String, String> row = queue.entries().get(0);
        assertEquals(12L, row.count());
        assertEquals(0L, row.bornMs());
        assertEquals(3_000L, row.refreshedMs());
        // Without the refresh it would be gone at 4350; with it, still fully visible.
        queue.prune(5_000L, HOLD);
        assertEquals(1, queue.size());
        assertEquals(1F, PickupNoticeQueue.alpha(row, 5_000L, HOLD));
    }

    @Test
    void differentItemsGetTheirOwnRowsNewestLast() {
        PickupNoticeQueue<String, String> queue = new PickupNoticeQueue<>();
        queue.add("oak_log", "Oak Log", 1, 0L, ROWS, HOLD);
        queue.add("cobblestone", "Cobblestone", 3, 10L, ROWS, HOLD);
        queue.add("oak_log", "Oak Log", 1, 20L, ROWS, HOLD);
        assertEquals(List.of("oak_log", "cobblestone"), keys(queue));
        assertEquals(2L, queue.entries().get(0).count());
    }

    @Test
    void rowsExpireAfterHoldPlusFadeOut() {
        PickupNoticeQueue<String, String> queue = new PickupNoticeQueue<>();
        queue.add("dirt", "Dirt", 1, 1_000L, ROWS, HOLD);
        long expiry = 1_000L + HOLD + PickupNoticeQueue.FADE_OUT_MS;
        queue.prune(expiry - 1, HOLD);
        assertEquals(1, queue.size());
        queue.prune(expiry, HOLD);
        assertTrue(queue.isEmpty());
    }

    @Test
    void expiredRowIsNotMergedIntoANewPickup() {
        PickupNoticeQueue<String, String> queue = new PickupNoticeQueue<>();
        queue.add("dirt", "Dirt", 5, 0L, ROWS, HOLD);
        queue.add("dirt", "Dirt", 2, 10_000L, ROWS, HOLD);
        assertEquals(1, queue.size());
        assertEquals(2L, queue.entries().get(0).count());
        assertEquals(10_000L, queue.entries().get(0).bornMs());
    }

    @Test
    void overflowDropsTheStalestRow() {
        PickupNoticeQueue<String, String> queue = new PickupNoticeQueue<>();
        queue.add("a", "A", 1, 0L, 3, HOLD);
        queue.add("b", "B", 1, 10L, 3, HOLD);
        queue.add("c", "C", 1, 20L, 3, HOLD);
        queue.add("a", "A", 1, 30L, 3, HOLD); // refresh a: b is now stalest
        queue.add("d", "D", 1, 40L, 3, HOLD);
        assertEquals(List.of("a", "c", "d"), keys(queue));
    }

    @Test
    void alphaFadesInThenHoldsThenFadesOut() {
        PickupNoticeQueue<String, String> queue = new PickupNoticeQueue<>();
        queue.add("a", "A", 1, 0L, ROWS, HOLD);
        PickupNoticeQueue.Entry<String, String> row = queue.entries().get(0);
        assertEquals(0F, PickupNoticeQueue.alpha(row, 0L, HOLD));
        assertEquals(0.5F, PickupNoticeQueue.alpha(row, PickupNoticeQueue.FADE_IN_MS / 2, HOLD), 1e-4F);
        assertEquals(1F, PickupNoticeQueue.alpha(row, 2_000L, HOLD));
        long fadeStart = HOLD;
        assertEquals(1F, PickupNoticeQueue.alpha(row, fadeStart, HOLD));
        assertEquals(0.5F, PickupNoticeQueue.alpha(row, fadeStart + PickupNoticeQueue.FADE_OUT_MS / 2, HOLD), 0.01F);
        assertEquals(0F, PickupNoticeQueue.alpha(row, fadeStart + PickupNoticeQueue.FADE_OUT_MS, HOLD));
    }

    @Test
    void refreshDuringFadeOutRestoresFullOpacity() {
        PickupNoticeQueue<String, String> queue = new PickupNoticeQueue<>();
        queue.add("a", "A", 1, 0L, ROWS, HOLD);
        PickupNoticeQueue.Entry<String, String> row = queue.entries().get(0);
        long fading = HOLD + 200L;
        assertTrue(PickupNoticeQueue.alpha(row, fading, HOLD) < 1F);
        queue.add("a", "A", 1, fading, ROWS, HOLD);
        assertEquals(1F, PickupNoticeQueue.alpha(row, fading, HOLD));
        assertEquals(2L, row.count());
    }

    @Test
    void slideEasesFromFullOffsetToZero() {
        PickupNoticeQueue<String, String> queue = new PickupNoticeQueue<>();
        queue.add("a", "A", 1, 0L, ROWS, HOLD);
        PickupNoticeQueue.Entry<String, String> row = queue.entries().get(0);
        assertEquals(1F, PickupNoticeQueue.slideRemaining(row, 0L));
        float mid = PickupNoticeQueue.slideRemaining(row, PickupNoticeQueue.FADE_IN_MS / 2);
        assertTrue(mid > 0F && mid < 0.5F, "ease-out moves fastest at the start");
        assertEquals(0F, PickupNoticeQueue.slideRemaining(row, PickupNoticeQueue.FADE_IN_MS));
    }

    @Test
    void nonPositiveCountsAreIgnoredAndLargeCountsSaturate() {
        PickupNoticeQueue<String, String> queue = new PickupNoticeQueue<>();
        queue.add("a", "A", 0, 0L, ROWS, HOLD);
        queue.add("a", "A", -3, 0L, ROWS, HOLD);
        assertTrue(queue.isEmpty());
        queue.add("a", "A", Integer.MAX_VALUE, 0L, ROWS, HOLD);
        queue.add("a", "A", Integer.MAX_VALUE, 1L, ROWS, HOLD);
        assertEquals(PickupNoticeQueue.MAX_COUNT, queue.entries().get(0).count());
    }
}
