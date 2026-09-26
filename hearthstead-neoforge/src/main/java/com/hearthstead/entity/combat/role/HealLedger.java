package com.hearthstead.entity.combat.role;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Heal-over-time bookkeeping for the Healer (plan/BATTLE-ROLES.md §3). Pure:
 * the caller passes game time and applies the returned pulses to live
 * entities, skipping any that are dead or unloaded. A patient never stacks
 * two heals: a new bandage refreshes to whichever has more left.
 */
public final class HealLedger {
    public static final int PULSE_TICKS = 20;
    public static final int PULSES = 8;
    public static final float BANDAGE_TOTAL = 8.0F;
    public static final float HERB_TOTAL = 4.0F;
    /** Triage threshold: below this health fraction a settler needs care. */
    public static final float NEEDS_CARE_FRACTION = 0.7F;
    /** Evacuate at or below this fraction while enemies are near (GuardRecoveryPolicy). */
    public static final float EVACUATE_FRACTION = 0.30F;

    private static final class Hot {
        float remaining;
        final float perPulse;
        long nextPulse;

        Hot(float total, int pulses, long now) {
            this.remaining = total;
            this.perPulse = total / Math.max(1, pulses);
            this.nextPulse = now + PULSE_TICKS;
        }
    }

    public record Pulse(UUID patient, float amount) {
    }

    private final Map<UUID, Hot> active = new LinkedHashMap<>();
    private long totalStarted;

    /**
     * Starts (or refreshes) a heal of {@code total} over {@link #PULSES}
     * pulses. Returns false when the patient already has at least as much
     * healing queued (the supply should then not be consumed).
     */
    public boolean start(UUID patient, float total, long now) {
        if (patient == null || !(total > 0.0F)) {
            return false;
        }
        Hot current = active.get(patient);
        if (current != null && current.remaining >= total) {
            return false;
        }
        active.put(patient, new Hot(total, PULSES, now));
        totalStarted++;
        return true;
    }

    /** Pulses due at {@code now}; finished heals are dropped. */
    public List<Pulse> due(long now) {
        List<Pulse> out = new ArrayList<>();
        Iterator<Map.Entry<UUID, Hot>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Hot> e = it.next();
            Hot h = e.getValue();
            while (h.remaining > 1.0E-4F && now >= h.nextPulse) {
                float amount = Math.min(h.perPulse, h.remaining);
                h.remaining -= amount;
                h.nextPulse += PULSE_TICKS;
                out.add(new Pulse(e.getKey(), amount));
            }
            if (h.remaining <= 1.0E-4F) {
                it.remove();
            }
        }
        return out;
    }

    public void cancel(UUID patient) {
        active.remove(patient);
    }

    public boolean healing(UUID patient) {
        return active.containsKey(patient);
    }

    public float remaining(UUID patient) {
        Hot h = active.get(patient);
        return h == null ? 0.0F : h.remaining;
    }

    public int size() {
        return active.size();
    }

    public long totalStarted() {
        return totalStarted;
    }

    /** One wounded candidate the healer can see. */
    public record Patient<T>(T id, float healthFraction, double distSqr,
                             boolean enemyNear, boolean alreadyHealing) {
    }

    /**
     * Triage: only those under {@link #NEEDS_CARE_FRACTION} and not already
     * being healed; safe patients first, then the most hurt, then the nearest.
     */
    @Nullable
    public static <T> T triage(List<Patient<T>> candidates) {
        return candidates.stream()
            .filter(p -> p != null && p.id() != null
                && p.healthFraction() > 0.0F
                && p.healthFraction() < NEEDS_CARE_FRACTION
                && !p.alreadyHealing())
            .min(Comparator.<Patient<T>, Boolean>comparing(Patient::enemyNear)
                .thenComparingDouble(Patient::healthFraction)
                .thenComparingDouble(Patient::distSqr))
            .map(Patient::id)
            .orElse(null);
    }

    /** Evacuate: badly hurt AND in danger. */
    public static boolean shouldEvacuate(float healthFraction, boolean enemiesNear) {
        return enemiesNear && healthFraction > 0.0F && healthFraction <= EVACUATE_FRACTION;
    }
}
