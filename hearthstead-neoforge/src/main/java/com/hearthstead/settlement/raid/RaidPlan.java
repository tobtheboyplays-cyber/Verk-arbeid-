package com.hearthstead.settlement.raid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Optional;
import java.util.UUID;

/**
 * One scheduled raid: who is coming, what for, and from where.
 *
 * <p>Produced the night the roll says yes, and persisted, so a scheduled
 * raid survives a save/reload instead of evaporating — the failure mode
 * MineColonies shipped as "deliveries that silently never happen", applied
 * to raids.
 */
public record RaidPlan(UUID captainId, RaidObjective objective,
                       float approachDegrees, long night) {

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("CaptainId", captainId);
        tag.putString("Objective", objective.id());
        tag.putFloat("Approach", approachDegrees);
        tag.putLong("Night", night);
        return tag;
    }

    /**
     * Strict shared parser for both the transitional legacy field and the
     * versioned lifecycle. It never invents defaults for malformed save data.
     */
    public static Optional<RaidPlan> tryReadNbt(Tag rawTag) {
        if (!(rawTag instanceof CompoundTag tag)
            || !tag.hasUUID("CaptainId")
            || !tag.contains("Objective", Tag.TAG_STRING)
            || !tag.contains("Approach", Tag.TAG_FLOAT)
            || !tag.contains("Night", Tag.TAG_LONG)) {
            return Optional.empty();
        }
        RaidObjective objective = null;
        String id = tag.getString("Objective");
        for (RaidObjective candidate : RaidObjective.values()) {
            if (candidate.id().equals(id)) {
                objective = candidate;
                break;
            }
        }
        float approach = tag.getFloat("Approach");
        long night = tag.getLong("Night");
        if (objective == null || !Float.isFinite(approach)
            || approach < -180.0F || approach >= 180.0F || night < 0L) {
            return Optional.empty();
        }
        return Optional.of(new RaidPlan(tag.getUUID("CaptainId"), objective,
            approach, night));
    }

    public static boolean isValid(RaidPlan plan) {
        return plan != null && plan.captainId() != null && plan.objective() != null
            && Float.isFinite(plan.approachDegrees())
            && plan.approachDegrees() >= -180.0F
            && plan.approachDegrees() < 180.0F
            && plan.night() >= 0L;
    }

    /** Kept for source compatibility; invalid data now fails explicitly. */
    public static RaidPlan readNbt(CompoundTag tag) {
        return tryReadNbt(tag).orElseThrow(() ->
            new IllegalArgumentException("Invalid persisted Hearthstead RaidPlan"));
    }
}
