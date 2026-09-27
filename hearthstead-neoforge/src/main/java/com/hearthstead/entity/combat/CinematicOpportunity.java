package com.hearthstead.entity.combat;

import java.util.Objects;
import java.util.UUID;

/**
 * One short, server-owned invitation for a low-health enemy to receive an
 * interruptible cinematic finish. This is deliberately not a target lock:
 * callers must clear it after any accepted intervening hit, movement failure
 * or target loss, and ordinary combat remains authoritative throughout.
 *
 * <p>The class has no entity, level, network or persistence dependency so its
 * exact lifecycle can be unit-tested. A live Raider owns one instance only in
 * memory; it is never saved across reloads.</p>
 */
public final class CinematicOpportunity {
    public static final float FINISH_HEALTH_FRACTION = 0.35F;
    /**
     * A Guard spends a 20-tick ordinary melee cooldown before its next
     * wind-up, then contacts four ticks later. A 20-tick window opened at the
     * first contact therefore reaches the next ordinary contact exactly; it
     * never changes attack cadence or grants extra damage.
     */
    public static final int WINDOW_TICKS = 20;

    public enum State {
        OFFERED,
        CLAIMED,
        CLEARED
    }

    public enum ClearReason {
        EXPIRED,
        INTERVENING_HIT,
        TARGET_LOST,
        FINISHER_RESOLVED
    }

    private final UUID targetId;
    private final UUID openingGuardId;
    private final long openedAt;
    private final long expiresAt;
    private State state = State.OFFERED;
    private UUID claimingGuardId;
    private ClearReason clearReason;

    private CinematicOpportunity(UUID targetId, UUID openingGuardId,
                                 long openedAt, long expiresAt) {
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.openingGuardId = Objects.requireNonNull(openingGuardId,
            "openingGuardId");
        this.openedAt = openedAt;
        this.expiresAt = expiresAt;
    }

    /** Opens only when the hit actually leaves a living target in finisher range. */
    public static CinematicOpportunity open(UUID targetId, UUID openingGuardId,
                                            float healthAfter,
                                            float maxHealth, long now) {
        if (!Float.isFinite(healthAfter) || !Float.isFinite(maxHealth)
            || healthAfter <= 0.0F || maxHealth <= 0.0F
            || healthAfter > maxHealth * FINISH_HEALTH_FRACTION) {
            return null;
        }
        return new CinematicOpportunity(targetId, openingGuardId, now,
            now + WINDOW_TICKS);
    }

    public UUID targetId() { return targetId; }
    public UUID openingGuardId() { return openingGuardId; }
    public long openedAt() { return openedAt; }
    public long expiresAt() { return expiresAt; }
    public State state() { return state; }
    public UUID claimingGuardId() { return claimingGuardId; }
    public ClearReason clearReason() { return clearReason; }

    /**
     * Read-only liveness check. RaiderEntity owns the one expiry transition so
     * it can notify and cancel the claiming Guard's exact ticket atomically.
     */
    public boolean isLiveAt(long now) {
        return state != State.CLEARED && now <= expiresAt;
    }

    /**
     * One Guard may start the presentation. Claiming reserves no target,
     * damage or movement; an arrow or side strike still clears it normally.
     */
    public boolean claim(UUID guardId, long now) {
        Objects.requireNonNull(guardId, "guardId");
        if (!isLiveAt(now) || state != State.OFFERED) {
            return false;
        }
        state = State.CLAIMED;
        claimingGuardId = guardId;
        return true;
    }

    /** The exact finisher contact must belong to its own claimed Guard. */
    public boolean mayResolve(UUID guardId, long now) {
        Objects.requireNonNull(guardId, "guardId");
        return isLiveAt(now) && state == State.CLAIMED
            && guardId.equals(claimingGuardId);
    }

    /** Side melee, an arrow, knockback or removal immediately returns to live combat. */
    public void clear(ClearReason reason) {
        if (state == State.CLEARED) {
            return;
        }
        state = State.CLEARED;
        clearReason = Objects.requireNonNull(reason, "reason");
    }

}
