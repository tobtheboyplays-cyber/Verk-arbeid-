package com.hearthstead.ambient;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps barks rare. Pure (time is passed in) so it is unit tested.
 *
 * <ul>
 *   <li>per settler: one line per {@link #SETTLER_GAP} ticks, whatever the
 *       context;</li>
 *   <li>per settler and context: an everyday context (hungry, rain, job...)
 *       is not repeated for {@link #CONTEXT_GAP} ticks;</li>
 *   <li>per area ({@link #CELL}-block cells): one everyday line per
 *       {@link #AREA_GAP} ticks, so a crowd never talks over itself.
 *       Greetings use {@link #GREET_AREA_GAP}; event lines (a won raid, a
 *       death, a newcomer) the shorter {@link #EVENT_AREA_GAP}.</li>
 *   <li>body cues (a wave, a shiver) without a line: one per
 *       {@link #CUE_GAP} ticks per settler.</li>
 * </ul>
 * In-memory only: a restart simply forgets who spoke last, which is harmless.
 */
public final class BarkLimiter {
    public static final long SETTLER_GAP = 900L;
    public static final long CONTEXT_GAP = 6000L;
    public static final long AREA_GAP = 300L;
    /** Greetings answer the player walking in, so they may come a little closer together. */
    public static final long GREET_AREA_GAP = 60L;
    public static final long EVENT_AREA_GAP = 20L;
    public static final long CUE_GAP = 200L;
    public static final int CELL = 24;
    private static final int PRUNE_AT = 1024;

    private final Map<UUID, Long> lastBySettler = new HashMap<>();
    private final Map<UUID, long[]> lastByContext = new HashMap<>();
    private final Map<Long, Long> lastByArea = new HashMap<>();
    private final Map<UUID, Long> lastCue = new HashMap<>();

    /** Area cell of a world position. */
    public static long cell(double x, double z) {
        long cx = Math.floorDiv((long) Math.floor(x), CELL);
        long cz = Math.floorDiv((long) Math.floor(z), CELL);
        return (cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** True (and recorded) when this settler may say a {@code context} line now. */
    public synchronized boolean tryAcquire(UUID settler, BarkContext context, double x, double z, long now) {
        if (!allows(settler, context, x, z, now)) {
            return false;
        }
        lastBySettler.put(settler, now);
        lastByContext.computeIfAbsent(settler, id -> newContextRow())[context.ordinal()] = now;
        lastByArea.put(cell(x, z), now);
        prune(now);
        return true;
    }

    /** The same checks as {@link #tryAcquire} without recording anything. */
    public synchronized boolean allows(UUID settler, BarkContext context, double x, double z, long now) {
        if (settler == null || context == null) {
            return false;
        }
        if (!elapsed(lastBySettler.get(settler), now, SETTLER_GAP)) {
            return false;
        }
        long[] row = lastByContext.get(settler);
        if (row != null && !elapsed(row[context.ordinal()], now, contextGap(context))) {
            return false;
        }
        return elapsed(lastByArea.get(cell(x, z)), now, areaGap(context));
    }

    /**
     * A chorus line (several settlers answering one moment together, like a
     * won raid): only the per-settler gap applies. Recorded like any line.
     */
    public synchronized boolean tryAcquireChorus(UUID settler, BarkContext context, long now) {
        if (settler == null || context == null || !elapsed(lastBySettler.get(settler), now, SETTLER_GAP)) {
            return false;
        }
        lastBySettler.put(settler, now);
        lastByContext.computeIfAbsent(settler, id -> newContextRow())[context.ordinal()] = now;
        prune(now);
        return true;
    }

    /** True (and recorded) when this settler may play a line-less body cue now. */
    public synchronized boolean tryCue(UUID settler, long now) {
        if (settler == null || !elapsed(lastCue.get(settler), now, CUE_GAP)) {
            return false;
        }
        lastCue.put(settler, now);
        return true;
    }

    static long areaGap(BarkContext context) {
        return context.eventLine() ? EVENT_AREA_GAP
            : context == BarkContext.GREET ? GREET_AREA_GAP : AREA_GAP;
    }

    /** GREET is bounded by the once-a-day ledger; event lines by their event. */
    static long contextGap(BarkContext context) {
        return context == BarkContext.GREET || context.eventLine() ? 0L : CONTEXT_GAP;
    }

    public synchronized void clear() {
        lastBySettler.clear();
        lastByContext.clear();
        lastByArea.clear();
        lastCue.clear();
    }

    private static long[] newContextRow() {
        long[] row = new long[BarkContext.values().length];
        java.util.Arrays.fill(row, Long.MIN_VALUE);
        return row;
    }

    /** A clock that went backwards (world reload, /time) never blocks forever. */
    private static boolean elapsed(Long last, long now, long gap) {
        return last == null || last == Long.MIN_VALUE || now < last || now - last >= gap;
    }

    private static boolean elapsed(long last, long now, long gap) {
        return last == Long.MIN_VALUE || now < last || now - last >= gap;
    }

    private void prune(long now) {
        if (lastBySettler.size() > PRUNE_AT) {
            Iterator<Map.Entry<UUID, Long>> it = lastBySettler.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Long> entry = it.next();
                if (now - entry.getValue() >= CONTEXT_GAP) {
                    lastByContext.remove(entry.getKey());
                    it.remove();
                }
            }
        }
        if (lastByArea.size() > PRUNE_AT) {
            lastByArea.values().removeIf(at -> now - at >= AREA_GAP || at > now);
        }
        if (lastCue.size() > PRUNE_AT) {
            lastCue.values().removeIf(at -> now - at >= CUE_GAP);
        }
    }
}
