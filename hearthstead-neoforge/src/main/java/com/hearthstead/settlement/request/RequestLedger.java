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
        boolean savedQuarantine = tag.getBoolean("Quarantined");
        if (savedQuarantine && !repairableSavedQuarantine(reason)) {
            return new RequestLedger(expectedSettlementId, revision, true,
                reason);
        }
        // A runtime quarantine persisted by an earlier session still carries
        // every row, strictly decoded below. It is repaired after decoding
        // instead of freezing the settlement's Couriers forever.
        RequestLedger decoded = new RequestLedger(expectedSettlementId,
            revision, false, "none");
        ListTag activeRows = tag.getList("Active", Tag.TAG_COMPOUND);
        ListTag terminalRows = tag.getList("Terminal", Tag.TAG_COMPOUND);
        if (activeRows.size() > MAX_ACTIVE || terminalRows.size() > MAX_TERMINAL) {
            return quarantined(expectedSettlementId, "ledger_cardinality");
        }
        Set<UUID> ids = new HashSet<>();
        Set<String> duplicateKeys = new HashSet<>();
        for (int i = 0; i < activeRows.size(); i++) {
            CompoundTag raw = activeRows.getCompound(i);
            boolean legacyZeroCargo = RequestRecord.isLegacyZeroCargoOutputDigest(
                raw, registries);
            RequestRecord row = legacyZeroCargo
                ? RequestRecord.readLegacyZeroCargoOutputAsExpiredNbt(raw,
                    registries, expectedSettlementId)
                : RequestRecord.readNbt(raw, registries, expectedSettlementId);
            if (legacyZeroCargo) {
                // The saved row cannot be resumed: its old checksum describes
                // presentation only, it owns zero items, and the prior state
                // already records a fingerprint mismatch. Preserve its trace as
                // EXPIRED so a new scan may observe the real source safely.
                if (row == null || !ids.add(row.id())
                    || decoded.terminal.size() >= MAX_TERMINAL
                    || row.state() != RequestState.EXPIRED) {
                    return quarantined(expectedSettlementId,
                        "legacy_zero_cargo_expiry_failed");
                }
                decoded.terminal.put(row.id(), row);
                continue;
            }
            if (row == null || row.state().terminal() || !ids.add(row.id())
                || !duplicateKeys.add(row.duplicateKey())) {
                return quarantined(expectedSettlementId, "malformed_active_row");
            }
            decoded.active.put(row.id(), row);
        }
        for (int i = 0; i < terminalRows.size(); i++) {
            CompoundTag raw = terminalRows.getCompound(i);
            RequestRecord row = RequestRecord.readNbt(raw, registries, expectedSettlementId);
            if (row == null) {
                row = RequestRecord.readLegacyTerminalNbt(raw, registries, expectedSettlementId);
            }
            if (row == null || !row.state().terminal() || !ids.add(row.id())
                || decoded.terminal.size() >= MAX_TERMINAL) {
                return quarantined(expectedSettlementId, "malformed_terminal_row");
            }
            decoded.terminal.put(row.id(), row);
        }
        if (savedQuarantine
            && !decoded.repairAfterRuntimeQuarantine(reason)) {
            return new RequestLedger(expectedSettlementId, revision, true,
                reason);
        }
        return decoded;
    }

    /**
     * Reasons written by strict loading or structural bounds. Their rows were
     * never decoded (a load-time quarantine persists no rows), so nothing can
     * be proven about Courier bags and the ledger stays fail-closed.
     */
    private static final Set<String> NON_REPAIRABLE_REASONS = Set.of(
        "unknown", "malformed_ledger_header", "ledger_bounds",
        "ledger_cardinality", "legacy_zero_cargo_expiry_failed",
        "malformed_active_row", "malformed_terminal_row", "revision_bound",
        "malformed_root", "missing_root_version", "legacy_rows_not_supported",
        "future_root_version", "root_bounds",
        "duplicate_or_malformed_settlement", "settlement_bound");

    static boolean repairableSavedQuarantine(String reason) {
        return reason != null && !reason.isBlank()
            && !reason.equals("none")
            && !NON_REPAIRABLE_REASONS.contains(reason);
    }

    private boolean repairedFromQuarantine;
    private String repairedReason = "none";

    /** True when this instance was loaded from a runtime-quarantined image and repaired. */
    public boolean repairedFromQuarantine() {
        return repairedFromQuarantine;
    }

    public String repairedReason() {
        return repairedReason;
    }

    /**
     * Bookkeeping-only recovery for a ledger an earlier session quarantined
     * at runtime (for example {@code deliver_satisfaction_proof_failed}).
     * Physical items never live in the ledger: they are in containers or
     * Courier bags, and this repair moves none of them.
     *
     * <ul>
     *   <li>DELIVERED rows already proved every deposit's exact bag/target
     *       delta, so they close as SATISFIED under their own Courier.</li>
     *   <li>Cargo-free self-loop rows (source container == target container,
     *       which older builds opened when a producer building overlapped
     *       the Warehouse) can never complete, so they expire; the items
     *       never left that container.</li>
     *   <li>Every other row, including any with cargo in a Courier bag,
     *       stays active under its normal owner and recovery rules.</li>
     * </ul>
     * Returns false (the caller then keeps the quarantine) if any edge is
     * refused.
     */
    boolean repairAfterRuntimeQuarantine(String reason) {
        if (!repairableSavedQuarantine(reason)) {
            return false;
        }
        List<RequestRecord> rows = new ArrayList<>(active.values());
        for (RequestRecord row : rows) {
            RequestState effective = row.effectiveState();
            boolean closed;
            if (row.state() == RequestState.DELIVERED) {
                closed = row.courierId() != null
                    && row.markSatisfied(row.courierId(), row.updatedAt());
            } else if (row.sourceContainer().equals(row.targetContainer())
                && row.movedCount() == 0
                && (effective == RequestState.OPEN
                    || effective == RequestState.RESERVED)) {
                closed = row.expire(row.updatedAt());
            } else {
                continue;
            }
            if (!closed || !row.state().terminal()) {
                return false;
            }
            retire(row);
        }
        quarantined = false;
        quarantineReason = "none";
        repairedFromQuarantine = true;
        repairedReason = boundedReason(reason);
        bumpRevision();
        return !quarantined;
    }

    private void retire(RequestRecord record) {
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
