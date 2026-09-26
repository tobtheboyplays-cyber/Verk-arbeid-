package com.hearthstead.settlement.request;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Crafting orders persist beside the request ledger in their own file, so an
 * old world (no file) simply starts with no orders and the ledger's schema
 * and quarantine rules stay untouched.
 */
public final class CraftingOrderSavedData extends SavedData {
    public static final int DATA_VERSION = 1;
    public static final int MAX_SETTLEMENTS = 2_048;
    private static final String DATA_NAME = "hearthstead_crafting_orders";

    private final Map<UUID, CraftingOrderBook> books = new HashMap<>();

    private static final Factory<CraftingOrderSavedData> FACTORY =
        new Factory<>(CraftingOrderSavedData::new, CraftingOrderSavedData::load, null);

    public static CraftingOrderSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Read-only lookup that never creates an empty file. */
    @Nullable
    public static CraftingOrderSavedData existing(ServerLevel level) {
        return level == null ? null : level.getDataStorage().get(FACTORY, DATA_NAME);
    }

    public static CraftingOrderSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        CraftingOrderSavedData data = new CraftingOrderSavedData();
        if (tag == null) return data;
        ListTag settlements = tag.getList("Settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < settlements.size() && data.books.size() < MAX_SETTLEMENTS; i++) {
            CompoundTag entry = settlements.getCompound(i);
            if (!entry.hasUUID("Settlement")) continue;
            data.books.put(entry.getUUID("Settlement"),
                CraftingOrderBook.load(entry.getList("Orders", Tag.TAG_COMPOUND)));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("DataVersion", DATA_VERSION);
        ListTag settlements = new ListTag();
        for (Map.Entry<UUID, CraftingOrderBook> entry : books.entrySet()) {
            if (entry.getValue().all().isEmpty()) continue;
            CompoundTag row = new CompoundTag();
            row.putUUID("Settlement", entry.getKey());
            row.put("Orders", entry.getValue().save());
            settlements.add(row);
        }
        tag.put("Settlements", settlements);
        return tag;
    }

    public CraftingOrderBook book(UUID settlementId) {
        return books.computeIfAbsent(settlementId, id -> new CraftingOrderBook());
    }

    @Nullable
    public CraftingOrderBook existingBook(UUID settlementId) {
        return books.get(settlementId);
    }

    /** Test/reload seam: replaces one settlement's book (e.g. after an NBT round trip). */
    public void replace(UUID settlementId, CraftingOrderBook book) {
        books.put(settlementId, book);
        setDirty();
    }
}
