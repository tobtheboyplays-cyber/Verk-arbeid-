package com.hearthstead.finisher;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side finish-window bookkeeping, with no entity, level or network
 * dependency so the whole lifecycle is JUnit-testable.
 *
 * <p><b>Owner rule (2026-09-26):</b> an enemy can be finished ONLY while BOTH
 * hold: (1) it has LOST BALANCE, i.e. an explicit off-balance state lasting
 * {@link #OFF_BALANCE_TICKS}, set by a heavy hit, a shield bash, a warhammer
 * stun, a parry, a big knockback or any stagger; and (2) its health is BELOW
 * {@link #LOW_HEALTH_FRACTION} of max. The window is OPEN exactly while both
 * hold (the prompt and the red torso glow follow it), an execution RESERVES
 * it, and a resolved execution marks the enemy EXECUTED for good.</p>
 */
public final class FinishWindowTracker {
    /** Strictly below 10% of max health. */
    public static final float LOW_HEALTH_FRACTION = 0.10F;
    /** Off-balance lasts 2.5 s after the blow that caused it (owner: about 1.5-3 s). */
    public static final int OFF_BALANCE_TICKS = 50;
    /** Kept for callers that size UI off the window: the longest a window can stay open. */
    public static final int WINDOW_TICKS = OFF_BALANCE_TICKS;

    public enum State { NONE, OPEN, RESERVED, EXECUTED }

    public enum Transition { NONE, OPENED, CLOSED }

    private static final class Entry {
        long offBalanceUntil = Long.MIN_VALUE / 2;
        State state = State.NONE;
    }

    private final Map<UUID, Entry> entries = new HashMap<>();

    /** Pure rule: off-balance AND below 10% health (and alive). */
    public static boolean eligible(boolean offBalance, float health, float maxHealth) {
        return offBalance && health > 0.0F && maxHealth > 0.0F
            && Float.isFinite(health) && Float.isFinite(maxHealth)
            && health < maxHealth * LOW_HEALTH_FRACTION;
    }

    /**
     * The enemy lost its balance now. Returns true when this starts a new
     * off-balance spell (the caller broadcasts the wobble), false when it
     * only extends a running one.
     */
    public boolean markOffBalance(UUID id, long now, int ticks) {
        Entry e = entry(id);
        boolean fresh = e.offBalanceUntil <= now;
        e.offBalanceUntil = Math.max(e.offBalanceUntil, now + Math.max(1, ticks));
        return fresh;
    }

    public boolean isOffBalance(UUID id, long now) {
        Entry e = entries.get(id);
        return e != null && now < e.offBalanceUntil;
    }

    public int offBalanceTicksLeft(UUID id, long now) {
        Entry e = entries.get(id);
        return e == null ? 0 : (int) Math.max(0L, e.offBalanceUntil - now);
    }

    /** Opens / closes the window as the two conditions start / stop holding together. */
    public Transition evaluate(UUID id, long now, float health, float maxHealth) {
        Entry e = entries.get(id);
        if (e == null) {
            return Transition.NONE;
        }
        boolean ok = eligible(now < e.offBalanceUntil, health, maxHealth);
        if (e.state == State.OPEN && !ok) {
            e.state = State.NONE;
            return Transition.CLOSED;
        }
        if (e.state == State.NONE && ok) {
            e.state = State.OPEN;
            return Transition.OPENED;
        }
        return Transition.NONE;
    }

    public State state(UUID id) {
        Entry e = entries.get(id);
        return e == null ? State.NONE : e.state;
    }

    public boolean isOpen(UUID id, long now) {
        Entry e = entries.get(id);
        return e != null && e.state == State.OPEN && now < e.offBalanceUntil;
    }

    public int ticksLeft(UUID id, long now) {
        Entry e = entries.get(id);
        return e == null || e.state != State.OPEN ? 0 : (int) Math.max(0L, e.offBalanceUntil - now);
    }

    /**
     * Claims the window for an execution. Server-authoritative re-check of BOTH
     * conditions on use: off-balance now and health below 10% now.
     */
    public boolean reserve(UUID id, long now, float health, float maxHealth) {
        Entry e = entries.get(id);
        if (e == null || e.state != State.OPEN || !eligible(now < e.offBalanceUntil, health, maxHealth)) {
            return false;
        }
        e.state = State.RESERVED;
        return true;
    }

    /** An aborted execution hands the enemy back to ordinary combat. */
    public void release(UUID id, long now) {
        Entry e = entries.get(id);
        if (e != null && e.state == State.RESERVED) {
            e.state = State.NONE;
        }
    }

    /** Terminal: this enemy has been finished and can never be finished again. */
    public void markExecuted(UUID id) {
        entry(id).state = State.EXECUTED;
    }

    public void forget(UUID id) {
        entries.remove(id);
    }

    public int size() {
        return entries.size();
    }

    public void prune(java.util.function.Predicate<UUID> stillPresent) {
        entries.keySet().removeIf(id -> !stillPresent.test(id));
    }

    private Entry entry(UUID id) {
        return entries.computeIfAbsent(id, k -> new Entry());
    }
}
