package com.hearthstead.client.pickup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Render-free model of the item pickup feed shown in a HUD corner.
 *
 * <p>Each entry is one kind of item. Picking up more of a kind that is still
 * on screen merges into its row (the count grows and its timer restarts)
 * instead of adding a duplicate row. Rows fade in over {@link #FADE_IN_MS},
 * hold for the caller's hold time after their last refresh, then fade out
 * over {@link #FADE_OUT_MS} and are dropped.
 *
 * <p>All times are caller-supplied milliseconds so the class stays pure and
 * deterministic under test. Not thread-safe; the client uses it on the render
 * thread only.
 *
 * @param <K> merge key (equal keys merge into one row)
 * @param <V> display payload carried alongside the key (e.g. the item stack)
 */
public final class PickupNoticeQueue<K, V> {
    public static final long FADE_IN_MS = 120L;
    public static final long FADE_OUT_MS = 350L;
    /** Upper bound so a very long pickup streak can never overflow the row text. */
    public static final long MAX_COUNT = 999_999_999L;

    /** One visible row. Mutable only through the owning queue. */
    public static final class Entry<K, V> {
        private final K key;
        private V display;
        private long count;
        private final long bornMs;
        private long refreshedMs;

        private Entry(K key, V display, long count, long bornMs) {
            this.key = key;
            this.display = display;
            this.count = count;
            this.bornMs = bornMs;
            this.refreshedMs = bornMs;
        }

        public K key() { return key; }
        public V display() { return display; }
        public long count() { return count; }
        public long bornMs() { return bornMs; }
        public long refreshedMs() { return refreshedMs; }
    }

    /** Oldest-inserted first; the newest row is last. */
    private final List<Entry<K, V>> entries = new ArrayList<>();

    /**
     * Records {@code count} more of {@code key}. Merges into a live row of the
     * same key, otherwise adds a row and, if that exceeds {@code maxRows},
     * drops the row that was refreshed longest ago.
     */
    public void add(K key, V display, int count, long nowMs, int maxRows, long holdMs) {
        Objects.requireNonNull(key, "key");
        if (count <= 0) {
            return;
        }
        prune(nowMs, holdMs);
        for (Entry<K, V> entry : entries) {
            if (entry.key.equals(key)) {
                entry.count = Math.min(MAX_COUNT, entry.count + count);
                entry.refreshedMs = nowMs;
                entry.display = display;
                return;
            }
        }
        entries.add(new Entry<>(key, display, Math.min(MAX_COUNT, count), nowMs));
        int cap = Math.max(1, maxRows);
        while (entries.size() > cap) {
            int stalest = 0;
            for (int i = 1; i < entries.size(); i++) {
                if (entries.get(i).refreshedMs < entries.get(stalest).refreshedMs) {
                    stalest = i;
                }
            }
            entries.remove(stalest);
        }
    }

    /** Drops rows whose fade-out has finished. */
    public void prune(long nowMs, long holdMs) {
        entries.removeIf(entry -> nowMs >= expiresAt(entry, holdMs));
    }

    /** Live rows, oldest-inserted first (newest last). */
    public List<Entry<K, V>> entries() {
        return Collections.unmodifiableList(entries);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void clear() {
        entries.clear();
    }

    public static long expiresAt(Entry<?, ?> entry, long holdMs) {
        return entry.refreshedMs + Math.max(0L, holdMs) + FADE_OUT_MS;
    }

    /** Opacity in [0,1]: fade in from birth, fade out after the hold ends. */
    public static float alpha(Entry<?, ?> entry, long nowMs, long holdMs) {
        float in = fadeInProgress(entry, nowMs);
        long remaining = expiresAt(entry, holdMs) - nowMs;
        float out = clamp01(remaining / (float) FADE_OUT_MS);
        return Math.min(in, out);
    }

    /** Raw fade-in progress in [0,1] measured from the row's birth. */
    public static float fadeInProgress(Entry<?, ?> entry, long nowMs) {
        return clamp01((nowMs - entry.bornMs) / (float) FADE_IN_MS);
    }

    /**
     * Remaining slide distance as a fraction in [0,1]: 1 when the row has just
     * appeared, easing out (cubic) to 0 once the fade-in completes.
     */
    public static float slideRemaining(Entry<?, ?> entry, long nowMs) {
        float t = 1F - fadeInProgress(entry, nowMs);
        return t * t * t;
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value) || value <= 0F) {
            return 0F;
        }
        return Math.min(1F, value);
    }
}
