package com.hearthstead.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded table reservations only. No food or glass lives in this ledger. */
public final class TavernServingClaims {
    public static final int MAX_CLAIMS = 64;
    private final Map<Long, UUID> claims = new LinkedHashMap<>();
    private CompoundTag quarantine;
    public boolean occupied(BlockPos table) { return quarantine != null || claims.containsKey(table.asLong()); }
    public boolean owns(BlockPos table, UUID exactServing) { return quarantine == null && exactServing.equals(claims.get(table.asLong())); }
    public boolean claim(BlockPos table, UUID exactServing) {
        if (quarantine != null || exactServing == null || claims.size() >= MAX_CLAIMS || occupied(table)) return false;
        claims.put(table.asLong(), exactServing); return true;
    }
    public boolean release(BlockPos table, UUID exactServing) {
        return quarantine == null && claims.remove(table.asLong(), exactServing);
    }
    public CompoundTag save() {
        if (quarantine != null) return quarantine.copy();
        CompoundTag tag = new CompoundTag(); ListTag rows = new ListTag();
        claims.forEach((table, serving) -> {
            CompoundTag row = new CompoundTag(); row.putLong("Table", table); row.putUUID("Serving", serving); rows.add(row);
        });
        tag.put("Claims", rows); return tag;
    }
    public static TavernServingClaims load(CompoundTag tag) {
        TavernServingClaims result = new TavernServingClaims();
        if (tag.contains("Claims") && (!(tag.get("Claims") instanceof ListTag raw)
            || !raw.isEmpty() && raw.getElementType() != 10)) {
            result.quarantine = tag.copy(); return result;
        }
        ListTag rows = tag.getList("Claims", 10);
        if (rows.size() > MAX_CLAIMS) { result.quarantine = tag.copy(); return result; }
        for (int i = 0; i < rows.size(); i++) {
            CompoundTag row = rows.getCompound(i);
            if (!row.contains("Table", 4) || !row.hasUUID("Serving")
                || result.claims.putIfAbsent(row.getLong("Table"), row.getUUID("Serving")) != null) {
                result.quarantine = tag.copy(); return result;
            }
        }
        return result;
    }
}
