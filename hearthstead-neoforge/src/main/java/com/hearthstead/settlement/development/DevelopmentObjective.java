package com.hearthstead.settlement.development;

import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

/** Stable server-measured objective kinds used by Development nodes. */
public enum DevelopmentObjective {
    FOUNDATION_READY(0, "foundation_ready", false),
    HOUSED_SETTLERS(1, "housed_settlers", false),
    LUMBER_LOGS_STORED(2, "lumber_logs_stored", true),
    FARM_CROPS_STORED(3, "farm_crops_stored", true),
    COURIER_DELIVERIES(4, "courier_deliveries", true),
    EQUIPMENT_REQUESTS_SERVED(5, "equipment_requests_served", true),
    FIRST_RAID_COMPLETE(6, "first_raid_complete", false),
    GUARD_XP_EARNED(7, "guard_xp_earned", true),
    PRODUCTIVE_GOODS_MOVED(8, "productive_goods_moved", true),
    ALL_HOUSED_TICKS(9, "all_housed_ticks", false),
    /** One unique Guard request physically delivered by a live Courier. */
    GUARD_EQUIPMENT_DELIVERIES(10, "guard_equipment_deliveries", true),
    /** Current living residents, including residents without a bed. */
    POPULATION(11, "population", false);

    private final int wireId;
    private final String id;
    private final boolean baselineCounter;

    DevelopmentObjective(int wireId, String id, boolean baselineCounter) {
        this.wireId = wireId;
        this.id = id;
        this.baselineCounter = baselineCounter;
    }

    public int wireId() {
        return wireId;
    }

    public String id() {
        return id;
    }

    public boolean baselineCounter() {
        return baselineCounter;
    }

    public Component progressText(int progress, int target) {
        if (this == ALL_HOUSED_TICKS) {
            return Component.translatable("hearthstead.development.quest." + id,
                Math.max(0, progress) / 1_200, Math.max(1, target) / 1_200);
        }
        return Component.translatable("hearthstead.development.quest." + id,
            Math.max(0, progress), Math.max(1, target));
    }

    @Nullable
    public static DevelopmentObjective byWireId(int wireId) {
        for (DevelopmentObjective objective : values()) {
            if (objective.wireId == wireId) {
                return objective;
            }
        }
        return null;
    }
}
