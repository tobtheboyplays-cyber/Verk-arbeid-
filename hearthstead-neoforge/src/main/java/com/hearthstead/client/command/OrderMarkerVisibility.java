package com.hearthstead.client.command;

import java.util.Map;

/** Client presentation clock. Snapshot motion/revisions never extend an order flash. */
public final class OrderMarkerVisibility {
    public static final long DISPLAY_MS = 2000L;
    public record Order(int id, boolean holdFire) { }
    private Map<Integer, Order> previous;
    private long shownAt = Long.MIN_VALUE;

    public void snapshot(Map<Integer, Order> orders, long now) {
        if (previous != null && orders.entrySet().stream()
                .anyMatch(e -> !e.getValue().equals(previous.get(e.getKey())))) issued(now);
        previous = Map.copyOf(orders);
    }
    public void issued(long now) { shownAt = now; }
    public boolean recent(long now) { return shownAt != Long.MIN_VALUE && now >= shownAt && now - shownAt < DISPLAY_MS; }
    public long age(long now) { return recent(now) ? now - shownAt : Long.MAX_VALUE; }
    public void clear() { previous = null; shownAt = Long.MIN_VALUE; }
    public static boolean visible(boolean always, boolean commanding, boolean recent, boolean needsAttention) {
        return always || commanding || recent || needsAttention;
    }
    public static boolean needsAttention(boolean outOfAmmo, float health, float maximum) {
        return outOfAmmo || maximum > 0 && health > 0 && health < maximum * 0.4F;
    }
}
