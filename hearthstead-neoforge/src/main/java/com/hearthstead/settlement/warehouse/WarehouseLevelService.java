package com.hearthstead.settlement.warehouse;

import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.development.DevelopmentBonuses;
import com.hearthstead.settlement.development.PostRaidUpgrade;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * Glue between the settlement (Logistics tree), the room checklist level
 * the plaque scan writes into {@code Building.level}, and the container
 * index. Read-only apart from the runtime {@code warehouseTechMax} field.
 */
public final class WarehouseLevelService {

    private WarehouseLevelService() {
    }

    /** Everything the Storage tab / plaque needs about one warehouse. */
    public record Status(int level, int builtLevel, int techMax,
                         int capacity, int managed, int unmanaged,
                         boolean complete, int nextLevel, int nextCapacity,
                         @Nullable PostRaidUpgrade nextGate,
                         boolean nextGateOwned) {
        /** The room already meets the next checklist but the tree does not recognise it. */
        public boolean techLocked() {
            return nextLevel > 0 && builtLevel >= nextLevel && !nextGateOwned;
        }
    }

    /** Highest warehouse level this settlement's Logistics tree recognises (2..5). */
    public static int techMaxLevel(ServerLevel level, @Nullable Settlement settlement) {
        return DevelopmentBonuses.warehouseTechMaxLevel(level, settlement);
    }

    /** Tree node that unlocks recognition of {@code warehouseLevel}; null for L1-L2. */
    @Nullable
    public static PostRaidUpgrade gateFor(int warehouseLevel) {
        return DevelopmentBonuses.warehouseGateFor(warehouseLevel);
    }

    /** Re-reads the tree cap for one warehouse (and any building, harmlessly). */
    public static void sync(ServerLevel level, @Nullable Settlement settlement,
                            Building building) {
        if (building != null) {
            building.warehouseTechMax =
                DevelopmentBonuses.warehouseTechMaxLevel(level, settlement);
        }
    }

    /** Periodic: every warehouse of every settlement in this level. */
    public static void syncAll(ServerLevel level) {
        for (Settlement settlement : SettlementSavedData.get(level).settlements.values()) {
            int techMax = -1;
            for (Building building : settlement.buildings) {
                if (building.type != BuildingType.WAREHOUSE) {
                    continue;
                }
                if (techMax < 0) {
                    techMax = DevelopmentBonuses.warehouseTechMaxLevel(level, settlement);
                }
                building.warehouseTechMax = techMax;
            }
        }
    }

    /**
     * Player control seam (Work Scepter / UI): cycles one container's mark
     * none -> priority -> excluded -> none. Only positions inside the
     * building count; the map is bounded. Returns the new mark. The caller
     * marks the settlement data dirty. The selection re-runs on the next
     * index query (the marks are part of its cache key).
     */
    public static byte cycleMark(Building building, net.minecraft.core.BlockPos pos) {
        if (building == null || pos == null || !building.contains(pos)) {
            return WarehouseLevels.MARK_NONE;
        }
        long key = pos.asLong();
        byte current = building.containerMarks.getOrDefault(key, WarehouseLevels.MARK_NONE);
        byte next = current == WarehouseLevels.MARK_NONE ? WarehouseLevels.MARK_PRIORITY
            : current == WarehouseLevels.MARK_PRIORITY ? WarehouseLevels.MARK_EXCLUDED
            : WarehouseLevels.MARK_NONE;
        if (next == WarehouseLevels.MARK_NONE) {
            building.containerMarks.remove(key);
        } else if (building.containerMarks.containsKey(key)
            || building.containerMarks.size() < Building.MAX_CONTAINER_MARKS) {
            building.containerMarks.put(key, next);
        } else {
            return current;
        }
        return next;
    }

    public static Status status(ServerLevel level, @Nullable Settlement settlement,
                                Building building) {
        sync(level, settlement, building);
        WarehouseIndex.View view = WarehouseIndex.view(level, building);
        int effective = WarehouseIndex.effectiveLevel(building);
        int next = WarehouseLevels.nextLevel(effective);
        PostRaidUpgrade gate = next < 0 ? null : DevelopmentBonuses.warehouseGateFor(next);
        boolean gateOwned = gate == null
            || DevelopmentBonuses.owned(level, settlement, gate);
        return new Status(effective, WarehouseLevels.clampLevel(building.level),
            building.warehouseTechMax, view.capacity(), view.managed().size(),
            view.unmanaged().size(), view.complete(), next,
            next < 0 ? 0 : WarehouseLevels.capacity(next), gate, gateOwned);
    }
}
