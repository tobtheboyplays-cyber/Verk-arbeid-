package com.hearthstead.event;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server authority for visitor demand only. Coins and merchant offers remain
 * physical; this stores neither an inventory nor a player balance.
 *
 * <p>Rows are bounded and fail closed on malformed persisted state.
 */
public final class MerchantDemandSavedData extends SavedData {
    /** Four Minecraft days, so the 48k visitor cadence can actually carry demand forward. */
    public static final long RECOVERY_TICKS = 96_000L;
    public static final int MAX_PRESSURE = 4;
    private static final int VERSION = 1;
    private static final int MAX_SETTLEMENTS = 256;
    private static final int MAX_WANTED = 3;
    private static final int MAX_PRESSURES = 21;
    private static final String DATA_NAME = "hearthstead_merchant_demand";
    private static final Factory<MerchantDemandSavedData> FACTORY = new Factory<>(
        MerchantDemandSavedData::new, MerchantDemandSavedData::load, null);

    /** Exactly the pre-existing Coin-purchase catalogue; no new currency input. */
    private static final Set<String> MARKET_GOODS = Set.of(
        "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log",
        "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log",
        "minecraft:mangrove_log", "minecraft:cherry_log", "minecraft:oak_planks",
        "minecraft:wheat", "minecraft:carrot", "minecraft:potato",
        "minecraft:beetroot", "minecraft:iron_ingot", "minecraft:honey_bottle",
        "minecraft:pumpkin", "minecraft:cod", "minecraft:salmon",
        "hearthstead:brown_trout", "hearthstead:silver_pike", "hearthstead:golden_char");

    private final Map<UUID, Row> rows = new LinkedHashMap<>();
    private boolean quarantined;

    private static final class Row {
        long visitSequence;
        long lastVisit;
        final List<ResourceLocation> previousWanted = new ArrayList<>();
        final Map<ResourceLocation, Pressure> pressures = new LinkedHashMap<>();
    }

    private record Pressure(int tier, long observedAt) { }

    /** Immutable data used to create a one-visitor offer list. */
    public record VisitSnapshot(long sequence, List<ResourceLocation> wanted,
                                Map<ResourceLocation, Integer> pressure) {
        public VisitSnapshot {
            wanted = List.copyOf(wanted);
            pressure = Map.copyOf(pressure);
        }
    }

    public static MerchantDemandSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    @Nullable
    public static MerchantDemandSavedData existing(ServerLevel level) {
        return level.getDataStorage().get(FACTORY, DATA_NAME);
    }

    /** Strict shared allow-list for catalogue, offer construction and loader checks. */
    public static boolean supports(@Nullable ResourceLocation item) {
        return item != null && MARKET_GOODS.contains(item.toString());
    }

    /** Whether this settlement has already received at least one published market. */
    public boolean hasVisit(UUID settlement) {
        return !quarantined && rows.containsKey(settlement);
    }

    /**
     * Advances one published visitor. `candidates` must already be a bounded,
     * deterministic list from actual settlement production/local stock. The
     * caller supplies its bootstrap wood separately for visit zero.
     */
    @Nullable
    public VisitSnapshot beginVisit(UUID settlement, List<ResourceLocation> candidates,
                                    long now, int wantedCount) {
        if (quarantined || invalidSettlement(settlement) || now < 0
            || wantedCount < 1 || wantedCount > MAX_WANTED) return null;
        List<ResourceLocation> clean = distinctSupported(candidates);
        if (clean.isEmpty()) return null;
        Row row = rows.get(settlement);
        if (row == null) {
            if (rows.size() >= MAX_SETTLEMENTS) return null;
            row = new Row();
            rows.put(settlement, row);
        }
        decay(row, now);
        List<ResourceLocation> wanted = rotateWithoutImmediateRepeat(
            clean, row.previousWanted, row.visitSequence, settlement, wantedCount);
        if (wanted.isEmpty()) return null;
        Map<ResourceLocation, Integer> pressure = new LinkedHashMap<>();
        for (ResourceLocation item : wanted) {
            Pressure value = row.pressures.get(item);
            pressure.put(item, value == null ? 0 : value.tier());
        }
        long sequence = row.visitSequence;
        row.visitSequence++;
        row.lastVisit = now;
        row.previousWanted.clear();
        row.previousWanted.addAll(wanted);
        setDirty();
        return new VisitSnapshot(sequence, wanted, pressure);
    }

    /** Records one completed one-Coin shipment; quality shares the item's market. */
    public boolean recordCompletedSale(UUID settlement, ResourceLocation item, long now) {
        if (quarantined || invalidSettlement(settlement) || !supports(item) || now < 0) return false;
        Row row = rows.get(settlement);
        if (row == null) return false; // never create demand without a published visit.
        decay(row, now);
        Pressure before = row.pressures.get(item);
        int tier = Math.min(MAX_PRESSURE, (before == null ? 0 : before.tier()) + 1);
        if (before == null && row.pressures.size() >= MAX_PRESSURES) return false;
        row.pressures.put(item, new Pressure(tier, now));
        setDirty();
        return true;
    }

    /** Read seam for focused GameTests. It applies the same lazy recovery rule. */
    public int pressure(UUID settlement, ResourceLocation item, long now) {
        Row row = rows.get(settlement);
        if (quarantined || row == null || !supports(item) || now < 0) return 0;
        if (decay(row, now)) setDirty();
        Pressure pressure = row.pressures.get(item);
        return pressure == null ? 0 : pressure.tier();
    }

    private boolean decay(Row row, long now) {
        boolean changed = false;
        for (var iterator = row.pressures.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            Pressure pressure = entry.getValue();
            if (now < pressure.observedAt()) continue; // monotonic server time only
            long periods = (now - pressure.observedAt()) / RECOVERY_TICKS;
            if (periods <= 0) continue;
            int tier = (int)Math.max(0L, pressure.tier() - periods);
            if (tier == 0) { iterator.remove(); changed = true; }
            else {
                long remainder = (now - pressure.observedAt()) % RECOVERY_TICKS;
                entry.setValue(new Pressure(tier, now - remainder));
                changed = true;
            }
        }
        return changed;
    }

    private static List<ResourceLocation> rotateWithoutImmediateRepeat(
            List<ResourceLocation> candidates, List<ResourceLocation> previous, long sequence,
            UUID settlement, int wantedCount) {
        int limit = Math.min(wantedCount, candidates.size());
        if (sequence == 0L) return List.copyOf(candidates.subList(0, limit));
        int start = Math.floorMod((int)(sequence + settlement.hashCode()), candidates.size());
        List<ResourceLocation> result = new ArrayList<>(limit);
        // Take every available novel good first. If there are fewer than the
        // desired rows, rotate old goods only to fill the remaining finite slots.
        for (int offset = 0; offset < candidates.size() && result.size() < limit; offset++) {
            ResourceLocation next = candidates.get((start + offset) % candidates.size());
            if (!previous.contains(next)) result.add(next);
        }
        for (int offset = 0; offset < candidates.size() && result.size() < limit; offset++) {
            ResourceLocation next = candidates.get((start + offset) % candidates.size());
            if (!result.contains(next)) result.add(next);
        }
        return result;
    }

    private static List<ResourceLocation> distinctSupported(List<ResourceLocation> candidates) {
        if (candidates == null || candidates.size() > MAX_PRESSURES) return List.of();
        Set<ResourceLocation> clean = new LinkedHashSet<>();
        for (ResourceLocation item : candidates) if (supports(item)) clean.add(item);
        return List.copyOf(clean);
    }

    private static boolean invalidSettlement(UUID id) {
        return id == null || (id.getMostSignificantBits() == 0L && id.getLeastSignificantBits() == 0L);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version", VERSION);
        tag.putBoolean("Quarantined", quarantined);
        ListTag saved = new ListTag();
        rows.forEach((settlement, row) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Settlement", settlement);
            entry.putLong("Sequence", row.visitSequence);
            entry.putLong("LastVisit", row.lastVisit);
            ListTag previous = new ListTag();
            for (ResourceLocation item : row.previousWanted) previous.add(StringTag.valueOf(item.toString()));
            entry.put("Previous", previous);
            ListTag pressures = new ListTag();
            row.pressures.forEach((item, pressure) -> {
                CompoundTag value = new CompoundTag();
                value.putString("Item", item.toString());
                value.putInt("Tier", pressure.tier());
                value.putLong("ObservedAt", pressure.observedAt());
                pressures.add(value);
            });
            entry.put("Pressure", pressures);
            saved.add(entry);
        });
        tag.put("Rows", saved);
        return tag;
    }

    public static MerchantDemandSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        MerchantDemandSavedData data = new MerchantDemandSavedData();
        if (tag.getInt("Version") != VERSION || !(tag.get("Rows") instanceof ListTag rows)
            || rows.size() > MAX_SETTLEMENTS || (!rows.isEmpty() && rows.getElementType() != Tag.TAG_COMPOUND)) {
            data.quarantined = true;
            return data;
        }
        data.quarantined = tag.getBoolean("Quarantined");
        for (Tag raw : rows) {
            CompoundTag entry = (CompoundTag)raw;
            if (!entry.hasUUID("Settlement") || !entry.contains("Sequence", Tag.TAG_LONG)
                || !entry.contains("LastVisit", Tag.TAG_LONG)
                || !(entry.get("Previous") instanceof ListTag previous)
                || !(entry.get("Pressure") instanceof ListTag pressure)
                || previous.size() > MAX_WANTED || pressure.size() > MAX_PRESSURES
                || (!previous.isEmpty() && previous.getElementType() != Tag.TAG_STRING)
                || (!pressure.isEmpty() && pressure.getElementType() != Tag.TAG_COMPOUND)) {
                data.quarantined = true;
                break;
            }
            UUID settlement = entry.getUUID("Settlement");
            Row row = new Row();
            row.visitSequence = entry.getLong("Sequence");
            row.lastVisit = entry.getLong("LastVisit");
            if (invalidSettlement(settlement) || row.visitSequence < 0 || row.lastVisit < 0
                || data.rows.putIfAbsent(settlement, row) != null) {
                data.quarantined = true;
                break;
            }
            for (Tag wanted : previous) {
                ResourceLocation item = ResourceLocation.tryParse(wanted.getAsString());
                if (!supports(item) || row.previousWanted.contains(item)) { data.quarantined = true; break; }
                row.previousWanted.add(item);
            }
            for (Tag rawPressure : pressure) {
                CompoundTag value = (CompoundTag)rawPressure;
                ResourceLocation item = ResourceLocation.tryParse(value.getString("Item"));
                int tier = value.getInt("Tier");
                long observed = value.getLong("ObservedAt");
                if (!value.contains("Item", Tag.TAG_STRING) || !value.contains("Tier", Tag.TAG_INT)
                    || !value.contains("ObservedAt", Tag.TAG_LONG) || !supports(item)
                    || tier < 1 || tier > MAX_PRESSURE || observed < 0
                    || row.pressures.putIfAbsent(item, new Pressure(tier, observed)) != null) {
                    data.quarantined = true;
                    break;
                }
            }
            if (data.quarantined) break;
        }
        return data;
    }
}
