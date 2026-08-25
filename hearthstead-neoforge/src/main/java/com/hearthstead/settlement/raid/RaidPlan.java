package com.hearthstead.settlement.raid;

import net.minecraft.nbt.CompoundTag;

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

    public static RaidPlan readNbt(CompoundTag tag) {
        RaidObjective objective = RaidObjective.BLOD;
        String id = tag.getString("Objective");
        for (RaidObjective o : RaidObjective.values()) {
            if (o.id().equals(id)) {
                objective = o;
                break;
            }
        }
        return new RaidPlan(tag.getUUID("CaptainId"), objective,
            tag.getFloat("Approach"), tag.getLong("Night"));
    }
}
