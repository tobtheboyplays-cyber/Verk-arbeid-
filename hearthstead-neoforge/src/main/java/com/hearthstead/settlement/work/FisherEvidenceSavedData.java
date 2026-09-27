package com.hearthstead.settlement.work;

import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.block.FishRackBlockEntity;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Historical proof of autonomous completed rack deposits, independent of player-stocked containers. */
public final class FisherEvidenceSavedData extends SavedData {
    private final Map<UUID, Map<UUID, Long>> stored = new HashMap<>();
    private static final Factory<FisherEvidenceSavedData> FACTORY =
        new Factory<>(FisherEvidenceSavedData::new,FisherEvidenceSavedData::load,null);
    public static FisherEvidenceSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY,"hearthstead_fisher_evidence");
    }
    public static void recordDeposit(ServerLevel level, Settlement settlement, Building building, int count) {
        if (count <= 0 || building.type != BuildingType.FISHERY || !building.valid) return;
        var data = get(level);
        data.stored.computeIfAbsent(settlement.id,k -> new HashMap<>())
            .merge(building.id,(long)count,(a,b) -> Math.min(Integer.MAX_VALUE,a+b));
        data.setDirty();
    }
    /** Exact credited deposits for one fishery (read-only; tests and diagnostics). */
    public static long storedCount(ServerLevel level, Settlement settlement, Building building) {
        var found = get(level).stored.get(settlement.id);
        return found == null ? 0L : found.getOrDefault(building.id, 0L);
    }
    public static boolean hasStoredCatch(ServerLevel level, Settlement settlement) {
        var found = get(level).stored.get(settlement.id);
        return found != null && settlement.buildings.stream().anyMatch(b -> b.valid
            && b.type == BuildingType.FISHERY && !b.workers.isEmpty() && found.getOrDefault(b.id,0L) > 0
            && WarehouseIndex.containers(level,b).stream().anyMatch(p -> level.hasChunkAt(p)
                && level.getBlockEntity(p) instanceof FishRackBlockEntity));
    }
    public static FisherEvidenceSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new FisherEvidenceSavedData();
        ListTag entries = tag.getList("Deposits",Tag.TAG_COMPOUND);
        for (int i=0;i<entries.size();i++) {
            CompoundTag row = entries.getCompound(i);
            if (row.hasUUID("Settlement") && row.hasUUID("Building") && row.getLong("Count") > 0)
                data.stored.computeIfAbsent(row.getUUID("Settlement"),k -> new HashMap<>())
                    .put(row.getUUID("Building"),Math.min(Integer.MAX_VALUE,row.getLong("Count")));
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        stored.forEach((settlement,buildings) -> buildings.forEach((building,count) -> {
            CompoundTag row = new CompoundTag(); row.putUUID("Settlement",settlement);
            row.putUUID("Building",building); row.putLong("Count",count); entries.add(row);
        }));
        tag.put("Deposits",entries); return tag;
    }
}
