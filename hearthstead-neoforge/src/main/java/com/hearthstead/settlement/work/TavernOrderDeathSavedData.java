package com.hearthstead.settlement.work;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded death proof only. Physical Coins remain owned by the saved serving or deferred item ledger. */
public final class TavernOrderDeathSavedData extends SavedData {
    private static final String DATA_NAME = "hearthstead_tavern_order_deaths";
    private static final int MAX_NOTICES = 4096;
    private static final Factory<TavernOrderDeathSavedData> FACTORY =
        new Factory<>(TavernOrderDeathSavedData::new, TavernOrderDeathSavedData::load, null);
    public record Notice(UUID orderId, UUID payerId, String dimension, double x, double y, double z) {}
    private final Map<UUID, Notice> notices = new LinkedHashMap<>();
    private boolean quarantined;
    private CompoundTag rawQuarantine;

    public static TavernOrderDeathSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Called only after LivingDeathEvent leaves the settler actually dead. Retry from tickDeath on capacity. */
    public boolean record(ServerLevel level, UUID orderId, UUID payerId, double x, double y, double z) {
        if (quarantined || level == null || orderId == null || payerId == null
            || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) return false;
        Notice old = notices.get(orderId);
        if (old != null) return old.payerId().equals(payerId)
            && old.dimension().equals(level.dimension().location().toString());
        if (notices.size() >= MAX_NOTICES) return false;
        notices.put(orderId, new Notice(orderId, payerId,
            level.dimension().location().toString(), x, y + .3, z));
        setDirty();
        return true;
    }

    /** Exact owner and payer UUIDs plus current dimension prevent another order's death from authorizing release. */
    public Notice exact(ServerLevel level, UUID orderId, UUID payerId) {
        if (quarantined || level == null || orderId == null || payerId == null) return null;
        Notice notice = notices.get(orderId);
        return notice != null && notice.payerId().equals(payerId)
            && notice.dimension().equals(level.dimension().location().toString()) ? notice : null;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rawQuarantine != null) return rawQuarantine.copy();
        tag.putInt("Schema", 1);
        tag.putBoolean("Quarantined", quarantined);
        ListTag rows = new ListTag();
        for (Notice notice : notices.values()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("Order", notice.orderId());
            row.putUUID("Payer", notice.payerId());
            row.putString("Dimension", notice.dimension());
            row.putDouble("X", notice.x()); row.putDouble("Y", notice.y()); row.putDouble("Z", notice.z());
            rows.add(row);
        }
        tag.put("Notices", rows);
        return tag;
    }

    public static TavernOrderDeathSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TavernOrderDeathSavedData data = new TavernOrderDeathSavedData();
        if (tag.isEmpty()) return data;
        if (!tag.contains("Schema", Tag.TAG_INT) || tag.getInt("Schema") != 1
            || !(tag.get("Notices") instanceof ListTag rawRows)
            || !rawRows.isEmpty() && rawRows.getElementType() != Tag.TAG_COMPOUND) {
            data.quarantined = true; data.rawQuarantine = tag.copy(); return data;
        }
        ListTag rows = tag.getList("Notices", Tag.TAG_COMPOUND);
        if (rows.size() > MAX_NOTICES || tag.getBoolean("Quarantined")) {
            data.quarantined = true; data.rawQuarantine = tag.copy(); return data;
        }
        for (Tag raw : rows) {
            if (!(raw instanceof CompoundTag row) || !row.hasUUID("Order") || !row.hasUUID("Payer")
                || !row.contains("Dimension", Tag.TAG_STRING)
                || !row.contains("X", Tag.TAG_DOUBLE) || !row.contains("Y", Tag.TAG_DOUBLE)
                || !row.contains("Z", Tag.TAG_DOUBLE)) {
                data.quarantined = true; data.rawQuarantine = tag.copy(); data.notices.clear(); return data;
            }
            UUID id = row.getUUID("Order"), payer = row.getUUID("Payer");
            double x = row.getDouble("X"), y = row.getDouble("Y"), z = row.getDouble("Z");
            String dimension = row.getString("Dimension");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                || dimension.isBlank() || dimension.length() > 128
                || data.notices.putIfAbsent(id, new Notice(id, payer, dimension, x, y, z)) != null) {
                data.quarantined = true; data.rawQuarantine = tag.copy(); data.notices.clear(); return data;
            }
        }
        return data;
    }
}
