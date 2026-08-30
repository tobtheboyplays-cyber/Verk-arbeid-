package com.hearthstead.settlement.request;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded persistent request ledger for exactly one settlement.
 *
 * <p>Active rows and terminal history hold intent/audit data only. Physical
 * items remain in world containers or entity bags. Any malformed save
 * quarantines the settlement ledger instead of dropping suspicious rows and
 * opening duplicate work.
 */
public final class RequestLedger {
    public static final int DATA_VERSION = 1;
    public static final int MAX_ACTIVE = 256;
    public static final int MAX_TERMINAL = 512;
    public static final int MAX_QUARANTINE_REASON = 128;
    public static final long MAX_REVISION = 1_000_000_000L;

    enum OpenResult {
        CREATED,
        DUPLICATE,
        CAPACITY,
        QUARANTINED
    }

    record OpenDecision(OpenResult result, @Nullable RequestRecord record) {
    }

    private final UUID settlementId;
    private final Map<UUID, RequestRecord> active = new LinkedHashMap<>();
    private final Map<UUID, RequestRecord> terminal = new LinkedHashMap<>();
    private long revision = 1L;
    private boolean quarantined;
    private String quarantineReason = "none";

    public RequestLedger(UUID settlementId) {
        this.settlementId = requireUuid(settlementId);
    }

    private RequestLedger(UUID settlementId, long revision,
                          boolean quarantined, String quarantineReason) {
        this.settlementId = requireUuid(settlementId);
        this.revision = revision;
        this.quarantined = quarantined;
        this.quarantineReason = boundedReason(quarantineReason);
    }

    public UUID settlementId() {
        return settlementId;
    }

    public long revision() {
        return revision;
    }

    public boolean quarantined() {
        return quarantined;
    }

    public String quarantineReason() {
        return quarantineReason;
    }

    public List<RequestRecord> active() {
        return List.copyOf(active.values());
    }

    public List<RequestRecord> terminalHistory() {
        return List.copyOf(terminal.values());
    }

    @Nullable
    public RequestRecord active(UUID requestId) {
        return active.get(requestId);
    }

    @Nullable
    public RequestRecord any(UUID requestId) {
        RequestRecord row = active.get(requestId);
        return row != null ? row : terminal.get(requestId);
    }

    List<RequestRecord> activeForCourier(UUID courierId) {
        if (courierId == null) {
            return List.of();
        }
        ArrayList<RequestRecord> found = new ArrayList<>();
        for (RequestRecord record : active.values()) {
            if (courierId.equals(record.courierId())) {
                found.add(record);
            }
        }
        found.sort(Comparator.comparingLong(RequestRecord::createdAt)
            .thenComparing(record -> record.id().toString()));
        return List.copyOf(found);
    }

    OpenDecision open(RequestRecord candidate) {
        if (quarantined) {
            return new OpenDecision(OpenResult.QUARANTINED, null);
        }
        if (candidate == null || !settlementId.equals(candidate.settlementId())
            || candidate.state() != RequestState.OPEN) {
            quarantine("invalid_open_candidate");
            return new OpenDecision(OpenResult.QUARANTINED, null);
        }
        for (RequestRecord existing : active.values()) {
            if (existing.duplicateKey().equals(candidate.duplicateKey())) {
                return new OpenDecision(OpenResult.DUPLICATE, existing);
            }
        }
        if (active.size() >= MAX_ACTIVE || active.containsKey(candidate.id())
            || terminal.containsKey(candidate.id())) {
            return new OpenDecision(OpenResult.CAPACITY, null);
        }
        active.put(candidate.id(), candidate);
        bumpRevision();
        return new OpenDecision(OpenResult.CREATED, candidate);
    }

    boolean mutationCommitted(RequestRecord record) {
        if (quarantined || record == null || active.get(record.id()) != record) {
            return false;
        }
        if (record.state().terminal()) {
            active.remove(record.id());
            if (terminal.size() >= MAX_TERMINAL) {
                UUID oldest = terminal.values().stream()
                    .min(Comparator.comparingLong(RequestRecord::updatedAt)
                        .thenComparing(row -> row.id().toString()))
                    .map(RequestRecord::id).orElse(null);
                if (oldest != null) {
                    terminal.remove(oldest);
                }
            }
            terminal.put(record.id(), record);
        }
        bumpRevision();
        return true;
    }

    void quarantine(String reason) {
        quarantined = true;
        quarantineReason = boundedReason(reason);
        bumpRevisionSafely();
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putUUID("Settlement", settlementId);
        tag.putLong("Revision", revision);
        tag.putBoolean("Quarantined", quarantined);
        tag.putString("QuarantineReason", quarantineReason);
        ListTag activeRows = new ListTag();
        for (RequestRecord record : active.values()) {
            activeRows.add(record.writeNbt());
        }
        tag.put("Active", activeRows);
        ListTag terminalRows = new ListTag();
        for (RequestRecord record : terminal.values()) {
            terminalRows.add(record.writeNbt());
        }
        tag.put("Terminal", terminalRows);
        return tag;
    }

    public static RequestLedger readNbt(CompoundTag tag,
                                        HolderLookup.Provider registries,
                                        UUID expectedSettlementId) {
        if (tag == null || registries == null || expectedSettlementId == null
            || tag.getInt("DataVersion") != DATA_VERSION
            || !tag.hasUUID("Settlement")
            || !expectedSettlementId.equals(tag.getUUID("Settlement"))
            || !tag.contains("Revision", Tag.TAG_LONG)
            || !tag.contains("Quarantined", Tag.TAG_BYTE)
            || !tag.contains("QuarantineReason", Tag.TAG_STRING)
            || !tag.contains("Active", Tag.TAG_LIST)
            || !tag.contains("Terminal", Tag.TAG_LIST)) {
            return quarantined(expectedSettlementId, "malformed_ledger_header");
        }
        long revision = tag.getLong("Revision");
        String reason = tag.getString("QuarantineReason");
        if (revision <= 0L || revision > MAX_REVISION
            || reason.length() > MAX_QUARANTINE_REASON) {
            return quarantined(expectedSettlementId, "ledger_bounds");
        }
        RequestLedger decoded = new RequestLedger(expectedSettlementId,
            revision, tag.getBoolean("Quarantined"), reason);
        if (decoded.quarantined) {
            return decoded;
        }
        ListTag activeRows = tag.getList("Active", Tag.TAG_COMPOUND);
        ListTag terminalRows = tag.getList("Terminal", Tag.TAG_COMPOUND);
        if (activeRows.size() > MAX_ACTIVE || terminalRows.size() > MAX_TERMINAL) {
            return quarantined(expectedSettlementId, "ledger_cardinality");
        }
        Set<UUID> ids = new HashSet<>();
        Set<String> duplicateKeys = new HashSet<>();
        for (int i = 0; i < activeRows.size(); i++) {
            RequestRecord row = RequestRecord.readNbt(activeRows.getCompound(i),
                registries, expectedSettlementId);
            if (row == null || row.state().terminal() || !ids.add(row.id())
                || !duplicateKeys.add(row.duplicateKey())) {
                return quarantined(expectedSettlementId, "malformed_active_row");
            }
            decoded.active.put(row.id(), row);
        }
        for (int i = 0; i < terminalRows.size(); i++) {
            RequestRecord row = RequestRecord.readNbt(terminalRows.getCompound(i),
                registries, expectedSettlementId);
            if (row == null || !row.state().terminal() || !ids.add(row.id())) {
                return quarantined(expectedSettlementId, "malformed_terminal_row");
            }
            decoded.terminal.put(row.id(), row);
        }
        return decoded;
    }

    public static RequestLedger quarantined(UUID settlementId, String reason) {
        return new RequestLedger(settlementId, 1L, true, reason);
    }

    private void bumpRevision() {
        if (revision >= MAX_REVISION) {
            quarantine("revision_bound");
            return;
        }
        revision++;
    }

    private void bumpRevisionSafely() {
        revision = revision >= MAX_REVISION ? MAX_REVISION : revision + 1L;
    }

    private static String boundedReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < reason.length()
                && safe.length() < MAX_QUARANTINE_REASON; i++) {
            char value = reason.charAt(i);
            safe.append(Character.isLetterOrDigit(value) || value == '_'
                || value == '-' ? value : '_');
        }
        return safe.isEmpty() ? "unknown" : safe.toString();
    }

    private static UUID requireUuid(UUID id) {
        if (id == null || id.getMostSignificantBits() == 0L
            && id.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException("ledger requires settlement UUID");
        }
        return id;
    }
}
