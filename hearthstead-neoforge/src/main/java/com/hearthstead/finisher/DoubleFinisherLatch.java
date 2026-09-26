package com.hearthstead.finisher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pairs two players' finisher presses on the same Brute or captain into one
 * co-op double execution. Pure bookkeeping (JUnit-tested).
 *
 * <p>The first press on a double-capable enemy with another player in reach
 * opens a latch: the first player steadies in the ready pose and the enemy is
 * held. A second, different player pressing within {@link #PAIR_TICKS} joins
 * as partner. If nobody joins, the latch lapses and the first player's solo
 * move starts. Nothing is ever started twice: the latch is consumed by
 * whichever of {@link #join} or {@link #drainExpired} resolves it first.</p>
 */
public final class DoubleFinisherLatch {
    /** "Within about 0.5 s of the other." */
    public static final int PAIR_TICKS = 10;

    public record Pending(UUID victim, UUID lead, long openedAt) {
        public long deadline() {
            return openedAt + PAIR_TICKS;
        }
    }

    private final Map<UUID, Pending> pending = new HashMap<>();

    public boolean isPending(UUID victim) {
        return pending.containsKey(victim);
    }

    public Pending get(UUID victim) {
        return pending.get(victim);
    }

    /** False if a latch is already open on this victim. */
    public boolean open(UUID victim, UUID lead, long now) {
        Objects.requireNonNull(victim, "victim");
        Objects.requireNonNull(lead, "lead");
        if (pending.containsKey(victim)) {
            return false;
        }
        pending.put(victim, new Pending(victim, lead, now));
        return true;
    }

    /**
     * A second player's press. Returns the consumed latch when {@code partner}
     * joins in time, or null (no latch, same player, or too late).
     */
    public Pending join(UUID victim, UUID partner, long now) {
        Pending p = pending.get(victim);
        if (p == null || p.lead().equals(partner) || now > p.deadline() || now < p.openedAt()) {
            return null;
        }
        pending.remove(victim);
        return p;
    }

    /** Latches whose pairing time ran out; they are removed and returned for a solo start. */
    public List<Pending> drainExpired(long now) {
        List<Pending> out = new ArrayList<>();
        pending.values().removeIf(p -> {
            if (now > p.deadline()) {
                out.add(p);
                return true;
            }
            return false;
        });
        return out;
    }

    /** The victim died, the lead left, etc. */
    public Pending cancel(UUID victim) {
        return pending.remove(victim);
    }

    public int size() {
        return pending.size();
    }
}
