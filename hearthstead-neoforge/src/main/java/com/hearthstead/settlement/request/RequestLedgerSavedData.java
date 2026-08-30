package com.hearthstead.settlement.request;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Separate request SavedData, avoiding Settlement schema collisions. */
public final class RequestLedgerSavedData extends SavedData {
    public static final int DATA_VERSION = 1;
    public static final int MAX_SETTLEMENTS = 2_048;
    private static final String DATA_NAME = "hearthstead_request_ledger";

    private final Map<UUID, RequestLedger> ledgers = new HashMap<>();
    private boolean rootQuarantined;
    private String quarantineReason = "none";

    private static final Factory<RequestLedgerSavedData> FACTORY =
        new Factory<>(RequestLedgerSavedData::new,
            RequestLedgerSavedData::load, null);

    public static RequestLedgerSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Read-only lookup that never creates an empty request ledger file. */
    @Nullable
    public static RequestLedgerSavedData existing(ServerLevel level) {
        return level == null ? null
            : level.getDataStorage().get(FACTORY, DATA_NAME);
    }

    public static RequestLedgerSavedData load(CompoundTag tag,
                                               HolderLookup.Provider registries) {
        RequestLedgerSavedData data = new RequestLedgerSavedData();
        if (tag == null || registries == null) {
            data.quarantineRoot("malformed_root");
            return data;
        }
        // The predecessor had no typed request file. An actually empty tag is
        // therefore the one safe legacy shape: there is no intent to invent,
        // drop or reconcile. A non-empty versionless tag remains corrupt.
        if (tag.isEmpty()) {
            return data;
        }
        if (!tag.contains("DataVersion", Tag.TAG_INT)) {
            data.quarantineRoot("missing_root_version");
            return data;
        }
        int version = tag.getInt("DataVersion");
        if (version == 0) {
            boolean knownKeys = tag.getAllKeys().stream().allMatch(key ->
                key.equals("DataVersion") || key.equals("Settlements"));
            if (!knownKeys || tag.contains("Settlements")
                    && (!tag.contains("Settlements", Tag.TAG_LIST)
                        || !tag.getList("Settlements",
                            Tag.TAG_COMPOUND).isEmpty())) {
                data.quarantineRoot("legacy_rows_not_supported");
            }
            return data;
        }
        if (!tag.contains("RootQuarantined", Tag.TAG_BYTE)
            || !tag.contains("QuarantineReason", Tag.TAG_STRING)
            || !tag.contains("Settlements", Tag.TAG_LIST)) {
            data.quarantineRoot("malformed_root");
            return data;
        }
        ListTag saved = tag.getList("Settlements", Tag.TAG_COMPOUND);
        if (version != DATA_VERSION || saved.size() > MAX_SETTLEMENTS) {
            data.quarantineRoot(version > DATA_VERSION
                ? "future_root_version" : "root_bounds");
            return data;
        }
        if (tag.getBoolean("RootQuarantined")) {
            data.quarantineRoot(tag.getString("QuarantineReason"));
            return data;
        }
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < saved.size(); i++) {
            CompoundTag entry = saved.getCompound(i);
            if (!entry.hasUUID("Id")
                || !(entry.get("Ledger") instanceof CompoundTag ledgerTag)
                || !seen.add(entry.getUUID("Id"))) {
                data.quarantineRoot("duplicate_or_malformed_settlement");
                data.ledgers.clear();
                return data;
            }
            UUID settlementId = entry.getUUID("Id");
            data.ledgers.put(settlementId, RequestLedger.readNbt(ledgerTag,
                registries, settlementId));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag,
                            HolderLookup.Provider registries) {
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putBoolean("RootQuarantined", rootQuarantined);
        tag.putString("QuarantineReason", quarantineReason);
        ListTag list = new ListTag();
        List<Map.Entry<UUID, RequestLedger>> ordered = new ArrayList<>(
            ledgers.entrySet());
        ordered.sort(Comparator.comparing(entry -> entry.getKey().toString()));
        int limit = Math.min(ordered.size(), MAX_SETTLEMENTS);
        for (int i = 0; i < limit; i++) {
            Map.Entry<UUID, RequestLedger> entry = ordered.get(i);
            CompoundTag saved = new CompoundTag();
            saved.putUUID("Id", entry.getKey());
            saved.put("Ledger", entry.getValue().writeNbt());
            list.add(saved);
        }
        tag.put("Settlements", list);
        return tag;
    }

    public RequestLedger ledger(UUID settlementId) {
        if (rootQuarantined) {
            return ledgers.computeIfAbsent(settlementId, id ->
                RequestLedger.quarantined(id, quarantineReason));
        }
        if (ledgers.size() >= MAX_SETTLEMENTS
            && !ledgers.containsKey(settlementId)) {
            rootQuarantined = true;
            quarantineReason = "settlement_bound";
            setDirty();
            return RequestLedger.quarantined(settlementId, quarantineReason);
        }
        return ledgers.computeIfAbsent(settlementId, RequestLedger::new);
    }

    @Nullable
    public RequestLedger existing(UUID settlementId) {
        return ledgers.get(settlementId);
    }

    public boolean rootQuarantined() {
        return rootQuarantined;
    }

    public String quarantineReason() {
        return quarantineReason;
    }

    private void quarantineRoot(String reason) {
        rootQuarantined = true;
        quarantineReason = reason == null || reason.isBlank()
            ? "unknown" : reason;
    }
}
