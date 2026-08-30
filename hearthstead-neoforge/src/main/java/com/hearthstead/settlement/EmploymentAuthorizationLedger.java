package com.hearthstead.settlement;

import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.journey.JourneyDefinition;
import com.hearthstead.settlement.journey.JourneyEvent;
import com.hearthstead.settlement.journey.JourneyEvidence;
import com.hearthstead.settlement.journey.JourneyIds;
import com.hearthstead.settlement.journey.JourneyPresentationMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Current, bounded proof that one physical Job Emblem authorized one exact
 * employment relation.
 *
 * <p>Journey evidence is immutable tutorial history. It cannot be the live
 * credential because a dead worker, rebuilt workplace or ordinary reassignment
 * must consume a new emblem after that tutorial step is already complete.
 * This ledger therefore stores only the current relation, overwrites it on a
 * newly charged hire, and clears it before dismissal, admin hire or building
 * dissolution. Readiness still re-observes the live worker and building; this
 * receipt proves only the charged authorization that created that relation.
 */
public final class EmploymentAuthorizationLedger {
    public static final int DATA_VERSION = 1;
    public static final int MAX_RECEIPTS = 256;
    public static final int MAX_SPENT_SALES = 4_096;
    public static final long MAX_REVISION = 1_000_000_000L;

    public record Receipt(UUID settlementId, UUID workerId, UUID buildingId,
                          Profession profession, UUID saleTransactionId,
                          long revision) {
        boolean validFor(UUID expectedSettlement) {
            return expectedSettlement != null
                && expectedSettlement.equals(settlementId)
                && !isNil(workerId) && !isNil(buildingId)
                && profession != null && profession != Profession.NONE
                && !isNil(saleTransactionId)
                && revision > 0L && revision <= MAX_REVISION;
        }
    }

    private final Map<UUID, Receipt> byWorker = new LinkedHashMap<>();
    /** Immutable bounded tombstones: clearing a job never unspends an emblem. */
    private final Set<UUID> spentSales = new LinkedHashSet<>();
    private long revision;
    private boolean quarantined;

    public static EmploymentAuthorizationLedger fresh() {
        return new EmploymentAuthorizationLedger();
    }

    public static EmploymentAuthorizationLedger quarantined() {
        EmploymentAuthorizationLedger ledger = new EmploymentAuthorizationLedger();
        ledger.quarantined = true;
        return ledger;
    }

    public boolean quarantinedState() {
        return quarantined;
    }

    public long revision() {
        return revision;
    }

    public int size() {
        return byWorker.size();
    }

    public int spentSaleCount() {
        return spentSales.size();
    }

    @Nullable
    public Receipt receipt(UUID workerId) {
        return workerId == null ? null : byWorker.get(workerId);
    }

    public boolean canAuthorize(UUID settlementId, UUID workerId,
                                UUID buildingId, Profession profession,
                                UUID saleTransactionId) {
        if (quarantined || !validIdentity(settlementId, workerId, buildingId,
                profession, saleTransactionId)) {
            return false;
        }
        for (Receipt receipt : byWorker.values()) {
            if (!saleTransactionId.equals(receipt.saleTransactionId())) {
                continue;
            }
            // One paid emblem sale authorizes one exact relation. A replay of
            // that same relation is idempotent; a copied stamped stack may not
            // authorize another worker, building or profession.
            return receipt.workerId().equals(workerId)
                && receipt.settlementId().equals(settlementId)
                && receipt.buildingId().equals(buildingId)
                && receipt.profession() == profession;
        }
        return !spentSales.contains(saleTransactionId)
            && spentSales.size() < MAX_SPENT_SALES
            && revision < MAX_REVISION
            && (byWorker.containsKey(workerId)
                || byWorker.size() < MAX_RECEIPTS);
    }

    /** Commits/overwrites one exact current relation after emblem consumption. */
    public boolean authorize(UUID settlementId, UUID workerId,
                             UUID buildingId, Profession profession,
                             UUID saleTransactionId) {
        if (!canAuthorize(settlementId, workerId, buildingId, profession,
                saleTransactionId)) {
            return false;
        }
        Receipt existing = byWorker.get(workerId);
        if (existing != null
            && existing.saleTransactionId().equals(saleTransactionId)
            && existing.settlementId().equals(settlementId)
            && existing.buildingId().equals(buildingId)
            && existing.profession() == profession) {
            return true;
        }
        long nextRevision = revision + 1L;
        if (!spentSales.add(saleTransactionId)) {
            return false;
        }
        byWorker.put(workerId, new Receipt(settlementId, workerId, buildingId,
            profession, saleTransactionId, nextRevision));
        revision = nextRevision;
        return true;
    }

    /** Clears stale authority before any non-charged or terminal roster move. */
    public boolean clear(UUID workerId) {
        if (quarantined || workerId == null || revision >= MAX_REVISION
            || byWorker.remove(workerId) == null) {
            return false;
        }
        revision++;
        return true;
    }

    public boolean matches(UUID settlementId, UUID workerId, UUID buildingId,
                           Profession profession) {
        if (quarantined || workerId == null) {
            return false;
        }
        Receipt receipt = byWorker.get(workerId);
        return receipt != null && receipt.validFor(settlementId)
            && receipt.workerId().equals(workerId)
            && receipt.buildingId().equals(buildingId)
            && receipt.profession() == profession;
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putLong("Revision", revision);
        tag.putBoolean("Quarantined", quarantined);
        ListTag rows = new ListTag();
        if (!quarantined) {
            for (Receipt receipt : byWorker.values()) {
                CompoundTag row = new CompoundTag();
                row.putUUID("Settlement", receipt.settlementId());
                row.putUUID("Worker", receipt.workerId());
                row.putUUID("Building", receipt.buildingId());
                row.putString("Profession", receipt.profession().key());
                row.putUUID("SaleTransaction", receipt.saleTransactionId());
                row.putLong("Revision", receipt.revision());
                rows.add(row);
            }
        }
        tag.put("Receipts", rows);
        ListTag spent = new ListTag();
        if (!quarantined) {
            for (UUID saleTransaction : spentSales) {
                CompoundTag row = new CompoundTag();
                row.putUUID("SaleTransaction", saleTransaction);
                spent.add(row);
            }
        }
        tag.put("SpentSales", spent);
        return tag;
    }

    public static EmploymentAuthorizationLedger readNbt(CompoundTag tag,
                                                         UUID settlementId) {
        if (tag == null || settlementId == null
            || !tag.contains("DataVersion", Tag.TAG_INT)
            || tag.getInt("DataVersion") != DATA_VERSION
            || !tag.contains("Revision", Tag.TAG_LONG)
            || tag.getLong("Revision") < 0L
            || tag.getLong("Revision") > MAX_REVISION
            || tag.getBoolean("Quarantined")
            || !(tag.get("Receipts") instanceof ListTag rows)
            || rows.size() > MAX_RECEIPTS
            || !(tag.get("SpentSales") instanceof ListTag spentRows)
            || spentRows.size() > MAX_SPENT_SALES) {
            return quarantined();
        }
        EmploymentAuthorizationLedger loaded = fresh();
        loaded.revision = tag.getLong("Revision");
        for (int i = 0; i < spentRows.size(); i++) {
            if (!(spentRows.get(i) instanceof CompoundTag row)
                || !row.hasUUID("SaleTransaction")) {
                return quarantined();
            }
            UUID saleTransaction = row.getUUID("SaleTransaction");
            if (isNil(saleTransaction)
                || !loaded.spentSales.add(saleTransaction)) {
                return quarantined();
            }
        }
        long greatestReceiptRevision = 0L;
        Set<UUID> saleTransactions = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            if (!(rows.get(i) instanceof CompoundTag row)) {
                return quarantined();
            }
            Receipt receipt = readReceipt(row);
            if (receipt == null || !receipt.validFor(settlementId)
                || !saleTransactions.add(receipt.saleTransactionId())
                || !loaded.spentSales.contains(receipt.saleTransactionId())
                || loaded.byWorker.putIfAbsent(receipt.workerId(), receipt)
                    != null) {
                return quarantined();
            }
            greatestReceiptRevision = Math.max(greatestReceiptRevision,
                receipt.revision());
        }
        if (greatestReceiptRevision > loaded.revision
            || (!loaded.byWorker.isEmpty() && loaded.revision == 0L)) {
            return quarantined();
        }
        return loaded;
    }

    /**
     * One-time bridge for saves authored before this live ledger existed.
     * Only an exact same-transaction Journey sale+binding pair can seed the
     * relation. SKIPPED/admin worlds intentionally migrate no credential.
     */
    public static EmploymentAuthorizationLedger migrateLegacy(
            Settlement settlement) {
        EmploymentAuthorizationLedger migrated = fresh();
        if (settlement == null || settlement.journeyState == null
            || settlement.journeyState.mode() == JourneyPresentationMode.SKIPPED
            || settlement.journeyState.evidence().size()
                > com.hearthstead.settlement.journey.JourneyState.MAX_EVIDENCE) {
            return migrated;
        }
        for (Settlement.SettlerRecord record : settlement.settlers) {
            Building employer = exactEmployer(settlement, record.entityId);
            if (employer == null) {
                continue;
            }
            UUID transaction = legacyTransaction(settlement, record.profession,
                record.entityId, employer.id);
            if (transaction != null && !migrated.authorize(settlement.id,
                    record.entityId, employer.id, record.profession,
                    transaction)) {
                return quarantined();
            }
        }
        return migrated;
    }

    @Nullable
    private static UUID legacyTransaction(Settlement settlement,
                                          Profession profession,
                                          UUID workerId, UUID buildingId) {
        ResourceLocation step = stepFor(profession);
        var definition = step == null ? java.util.Optional
            .<com.hearthstead.settlement.journey.JourneyStep>empty()
            : JourneyDefinition.CURRENT.step(step);
        if (step == null || definition.isEmpty()) {
            return null;
        }
        Set<UUID> sales = new HashSet<>();
        Set<UUID> bindings = new HashSet<>();
        List<JourneyEvidence> evidenceRows = settlement.journeyState.evidence();
        for (JourneyEvidence evidence : evidenceRows) {
            if (evidence == null || !step.equals(evidence.stepId())
                || !settlement.id.equals(evidence.settlementId())
                || !evidence.progressEligible(
                    definition.orElseThrow().migrationAllowed())) {
                continue;
            }
            if (evidence.event() == JourneyEvent.EMBLEM_TRADE_COMMITTED) {
                sales.add(evidence.transactionId());
            } else if (evidence.event()
                    == JourneyEvent.JOB_EMBLEM_BOUND_COMMITTED
                && evidence.subjectEntityId().filter(workerId::equals).isPresent()
                && evidence.buildingId().filter(buildingId::equals).isPresent()) {
                bindings.add(evidence.transactionId());
            }
        }
        sales.retainAll(bindings);
        return sales.size() == 1 ? sales.iterator().next() : null;
    }

    @Nullable
    private static Building exactEmployer(Settlement settlement, UUID workerId) {
        Building employer = null;
        Set<UUID> buildingIds = new HashSet<>();
        for (Building building : settlement.buildings) {
            if (!buildingIds.add(building.id)) {
                return null;
            }
            int matches = 0;
            for (UUID worker : building.workers) {
                if (workerId.equals(worker)) {
                    matches++;
                }
            }
            if (matches > 1 || (matches == 1 && employer != null)) {
                return null;
            }
            if (matches == 1) {
                employer = building;
            }
        }
        return employer;
    }

    @Nullable
    private static Receipt readReceipt(CompoundTag row) {
        if (!row.hasUUID("Settlement") || !row.hasUUID("Worker")
            || !row.hasUUID("Building") || !row.hasUUID("SaleTransaction")
            || !row.contains("Profession", Tag.TAG_STRING)
            || !row.contains("Revision", Tag.TAG_LONG)) {
            return null;
        }
        Profession profession = profession(row.getString("Profession"));
        if (profession == Profession.NONE) {
            return null;
        }
        return new Receipt(row.getUUID("Settlement"), row.getUUID("Worker"),
            row.getUUID("Building"), profession,
            row.getUUID("SaleTransaction"), row.getLong("Revision"));
    }

    private static Profession profession(String key) {
        for (Profession candidate : Profession.values()) {
            if (candidate.key().equals(key)) {
                return candidate;
            }
        }
        return Profession.NONE;
    }

    @Nullable
    private static ResourceLocation stepFor(Profession profession) {
        return switch (profession) {
            case LUMBERER -> JourneyIds.FJ_120_STAFF_LUMBER_CAMP;
            case COURIER -> JourneyIds.FJ_220_STAFF_WAREHOUSE;
            case FARMER -> JourneyIds.FJ_320_STAFF_FARMHOUSE;
            case GUARD -> JourneyIds.FJ_520_STAFF_BARRACKS;
            case ARCHER -> JourneyIds.FJ_557_STAFF_WATCHTOWER;
            default -> null;
        };
    }

    private static boolean validIdentity(UUID settlementId, UUID workerId,
                                         UUID buildingId,
                                         Profession profession,
                                         UUID saleTransactionId) {
        return !isNil(settlementId) && !isNil(workerId) && !isNil(buildingId)
            && profession != null && profession != Profession.NONE
            && !isNil(saleTransactionId);
    }

    private static boolean isNil(@Nullable UUID value) {
        return value == null || (value.getMostSignificantBits() == 0L
            && value.getLeastSignificantBits() == 0L);
    }
}
