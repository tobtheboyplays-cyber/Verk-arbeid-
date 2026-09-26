package com.hearthstead.ambient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "Has this settler greeted this player today?" Pure and in-memory (unit
 * tested). A restart may let a settler say good morning twice; that is
 * harmless and not worth a save file.
 */
public final class GreetingLedger {
    private static final int PRUNE_AT = 2048;

    private final Map<UUID, Map<UUID, Long>> daysBySettler = new HashMap<>();

    public synchronized boolean shouldGreet(UUID settler, UUID player, long day) {
        if (settler == null || player == null) {
            return false;
        }
        Map<UUID, Long> seen = daysBySettler.get(settler);
        Long last = seen == null ? null : seen.get(player);
        return last == null || last != day;
    }

    public synchronized void mark(UUID settler, UUID player, long day) {
        if (settler == null || player == null) {
            return;
        }
        if (daysBySettler.size() > PRUNE_AT) {
            daysBySettler.values().forEach(seen -> seen.values().removeIf(d -> d < day - 1));
            daysBySettler.values().removeIf(Map::isEmpty);
        }
        daysBySettler.computeIfAbsent(settler, id -> new HashMap<>()).put(player, day);
    }

    public synchronized void clear() {
        daysBySettler.clear();
    }
}
