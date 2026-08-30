package com.hearthstead.settlement.state;

import com.hearthstead.settlement.PendingPlayerDeliveryLedger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Settlement-wide, server-authoritative ledger for unclaimed physical seals.
 *
 * <p>A raid earns one offer and the Hearth converts exactly one offer into
 * exactly one physical {@code BlessingSealItem}. Permanent gameplay ranks do
 * not live here; they live on the settler or building that eventually receives
 * the seal. The issued counters are audit/telemetry only and never grant an
 * effect by themselves.
 *
 * <p>The compare-and-commit operation is synchronized even though normal game
 * mutation happens on the server thread. This makes the one-offer/one-seal
 * invariant explicit if packet handling ever moves off-thread.
 */
public final class BlessingState {
    public static final int DATA_VERSION = 3;
    public static final int MAX_COUNTER = 1_000_000;
    public static final int MAX_REVISION = MAX_COUNTER * 2;
    /** Root v3 is where the nested Blessing DataVersion became mandatory.
     *  This is historical schema truth, not "whatever the current root is":
     *  tying it to CURRENT_DATA_VERSION would silently reclassify a valid v3
     *  world as legacy as soon as the root advances to v4. */
    private static final int ROOT_REQUIRING_NESTED_VERSION = 3;

    /**
     * Source compatibility for pre-seal tests and callers. Target ranks are
     * capped by {@link TargetBlessingState}; this ledger itself is not.
     */
    @Deprecated(forRemoval = true)
    public static final int MAX_EFFECT_RANK = TargetBlessingState.MAX_RANK;
    @Deprecated(forRemoval = true)
    public static final int MAX_TOTAL_EFFECT_RANKS =
        MAX_EFFECT_RANK * BlessingId.values().length;

    public enum CommitResult {
        ACCEPTED,
        STALE,
        NO_OFFER,
        /** Retained as a stable protocol value; physical seal offers do not max. */
        MAXED,
        INVALID,
        /** No source mutation occurred because the durable outbox is full/inert. */
        DELIVERY_BACKLOG
    }

    private int earned;
    private int spent;
    private int revision;
    /** Persisted fail-closed quarantine; only an explicit repair may clear it. */
    private boolean quarantined;
    private final EnumMap<BlessingId, Integer> issued =
        new EnumMap<>(BlessingId.class);
    /** Same persisted compound as offer spend and issued counters. */
    private PendingPlayerDeliveryLedger pendingDeliveries =
        new PendingPlayerDeliveryLedger();

    public synchronized int earned() {
        return earned;
    }

    public synchronized int spent() {
        return spent;
    }

    public synchronized int revision() {
        return revision;
    }

    /** Serial shown for the next offer, or zero when none is available. */
    public synchronized int offerSerial() {
        return spent < earned ? spent + 1 : 0;
    }

    /** Number of physical seals of this type issued by this settlement. */
    public synchronized int issuedCount(@Nullable BlessingId blessing) {
        return blessing == null ? 0 : issued.getOrDefault(blessing, 0);
    }

    public synchronized Map<BlessingId, Integer> issuedCounts() {
        return Map.copyOf(issued);
    }

    /** Compatibility alias. This value is an issued count, not an effect rank. */
    @Deprecated(forRemoval = true)
    public synchronized int rank(@Nullable BlessingId blessing) {
        return issuedCount(blessing);
    }

    /** Compatibility alias. These values never grant settlement-wide effects. */
    @Deprecated(forRemoval = true)
    public synchronized Map<BlessingId, Integer> ranks() {
        return issuedCounts();
    }

    public synchronized boolean quarantined() {
        return quarantined;
    }

    /** Whether another raid may safely add one physical-seal offer. */
    public synchronized boolean hasCapacityForOffer() {
        return !quarantined && earned < MAX_COUNTER && revision < MAX_REVISION;
    }

    /** Compatibility name: every seal type remains selectable while an offer exists. */
    public synchronized boolean hasSelectableBlessing() {
        return !quarantined;
    }

    /** Fail-closed state for a present but malformed ledger. */
    public static BlessingState quarantinedEmpty() {
        BlessingState state = new BlessingState();
        state.quarantined = true;
        return state;
    }

    /** Adds one earned choice after an authoritative raid victory. */
    public synchronized boolean grantOffer() {
        if (!hasCapacityForOffer()) {
            return false;
        }
        earned++;
        revision++;
        return true;
    }

    /**
     * Atomically reserves one physical seal of the selected type.
     *
     * <p>Revision is checked before availability. After player A spends offer
     * one, player B's stale resend cannot spend offer two even if it already
     * exists. The caller must deliver the resulting one-item stack immediately
     * after {@link CommitResult#ACCEPTED}.
     */
    public synchronized CommitResult compareAndCommit(int expectedRevision,
                                                       int expectedOfferSerial,
                                                       @Nullable BlessingId blessing) {
        return compareAndCommit(expectedRevision, expectedOfferSerial, blessing,
            null);
    }

    /**
     * Atomically commits an accepted offer together with its exact output row.
     * A refused reservation leaves earned/spent/revision/issued untouched.
     */
    public synchronized CommitResult compareAndCommit(int expectedRevision,
                                                       int expectedOfferSerial,
                                                       @Nullable BlessingId blessing,
                                                       @Nullable PendingPlayerDeliveryLedger.Reservation reservation) {
        if (quarantined || blessing == null || expectedRevision < 0
            || expectedOfferSerial <= 0) {
            return CommitResult.INVALID;
        }
        if (expectedRevision != revision) {
            return CommitResult.STALE;
        }
        if (spent >= earned) {
            return CommitResult.NO_OFFER;
        }
        if (expectedOfferSerial != spent + 1) {
            return CommitResult.STALE;
        }
        int previousIssued = issuedCount(blessing);
        if (spent >= MAX_COUNTER || previousIssued >= MAX_COUNTER
            || revision >= MAX_REVISION) {
            return CommitResult.INVALID;
        }
        if (reservation != null
            && !pendingDeliveries.reserve(reservation).accepted()) {
            return CommitResult.DELIVERY_BACKLOG;
        }
        issued.put(blessing, previousIssued + 1);
        spent++;
        revision++;
        return CommitResult.ACCEPTED;
    }

    /** Rollback seam, valid only before compare-and-commit returns ACCEPTED. */
    public synchronized boolean cancelDelivery(UUID deliveryId) {
        return pendingDeliveries.cancel(deliveryId);
    }

    public synchronized PendingPlayerDeliveryLedger.DeliveryResult deliverPending(
            ServerLevel level, ServerPlayer player, UUID deliveryId) {
        if (quarantined) {
            return new PendingPlayerDeliveryLedger.DeliveryResult(
                PendingPlayerDeliveryLedger.Outcome.PENDING, false);
        }
        return pendingDeliveries.deliver(level, player, deliveryId);
    }

    public synchronized int retryPending(ServerLevel level,
                                         ServerPlayer player) {
        return quarantined ? 0 : pendingDeliveries.retry(level, player);
    }

    public synchronized int pendingDeliveryCount() {
        return pendingDeliveries.pendingCount();
    }

    public synchronized CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putInt("Earned", earned);
        tag.putInt("Spent", spent);
        tag.putInt("Revision", revision);
        tag.putBoolean("Quarantined", quarantined);
        ListTag issuedList = new ListTag();
        if (!quarantined) {
            for (BlessingId blessing : BlessingId.values()) {
                int count = issued.getOrDefault(blessing, 0);
                if (count <= 0) {
                    continue;
                }
                CompoundTag entry = new CompoundTag();
                entry.putInt("WireId", blessing.wireId());
                entry.putString("Id", blessing.id());
                entry.putInt("Count", count);
                issuedList.add(entry);
            }
        }
        tag.put("Issued", issuedList);
        tag.put("PendingPlayerDeliveries", pendingDeliveries.writeNbt());
        return tag;
    }

    /**
     * Strict nested decoder with a one-time legacy-rank migration.
     *
     * <p>Standalone state round-trips do not carry the outer settlement
     * version, so this overload retains the explicit legacy-fixture contract.
     * Real world loads must use {@link #readNbt(CompoundTag, int)} so deleting
     * a current nested {@code DataVersion} can never make corrupt current data
     * masquerade as an older schema.
     */
    public static BlessingState readNbt(@Nullable CompoundTag tag) {
        return readNbt(tag, true);
    }

    /** Root-version-aware decoder used by every persisted settlement load. */
    public static BlessingState readNbt(@Nullable CompoundTag tag,
                                        int rootSourceVersion) {
        boolean actualLegacyRoot = rootSourceVersion > 0
            && rootSourceVersion < ROOT_REQUIRING_NESTED_VERSION;
        return readNbt(tag, actualLegacyRoot);
    }

    private static BlessingState readNbt(@Nullable CompoundTag tag,
                                         boolean allowMissingNestedVersion) {
        if (tag == null) {
            return quarantinedEmpty();
        }
        Tag rawVersion = tag.get("DataVersion");
        if (rawVersion != null) {
            if (!tag.contains("DataVersion", Tag.TAG_INT)) {
                return quarantinedEmpty();
            }
            int version = tag.getInt("DataVersion");
            if (version != 2 && version != DATA_VERSION) {
                return quarantinedEmpty();
            }
            return readLedger(tag, "Issued", "Count", MAX_COUNTER,
                version >= 3);
        }
        if (!allowMissingNestedVersion) {
            return quarantinedEmpty();
        }
        // The pre-seal ledger stored issuance history under Ranks/Rank. Despite
        // the old name, those counters were already bounded by MAX_COUNTER,
        // not by the rank-III gameplay cap. Preserving the full value is
        // required for save compatibility with settlements that completed
        // more than three raids of the same reward type.
        return readLedger(tag, "Ranks", "Rank", MAX_COUNTER, false);
    }

    private static BlessingState readLedger(CompoundTag tag, String listKey,
                                            String countKey, int perTypeMax,
                                            boolean requirePendingDeliveries) {
        BlessingState state = new BlessingState();
        boolean auditable = tag.contains("Quarantined", Tag.TAG_BYTE)
            && tag.contains("Earned", Tag.TAG_INT)
            && tag.contains("Spent", Tag.TAG_INT)
            && tag.contains("Revision", Tag.TAG_INT);
        boolean persistedQuarantine = tag.contains("Quarantined", Tag.TAG_BYTE)
            && tag.getBoolean("Quarantined");
        if (requirePendingDeliveries) {
            if (tag.get("PendingPlayerDeliveries")
                    instanceof CompoundTag pendingTag) {
                state.pendingDeliveries =
                    PendingPlayerDeliveryLedger.readNbt(pendingTag);
                if (state.pendingDeliveries.quarantined()) {
                    auditable = false;
                }
            } else {
                auditable = false;
            }
        } else if (tag.contains("PendingPlayerDeliveries")) {
            // A v2/legacy tag cannot borrow v3 ownership semantics.
            auditable = false;
        }

        int rawEarned = tag.contains("Earned", Tag.TAG_INT)
            ? tag.getInt("Earned") : 0;
        int rawSpent = tag.contains("Spent", Tag.TAG_INT)
            ? tag.getInt("Spent") : 0;
        int rawRevision = tag.contains("Revision", Tag.TAG_INT)
            ? tag.getInt("Revision") : 0;
        if (rawEarned < 0 || rawEarned > MAX_COUNTER
            || rawSpent < 0 || rawSpent > rawEarned
            || rawRevision < 0 || rawRevision > MAX_REVISION
            || rawRevision != rawEarned + rawSpent) {
            auditable = false;
        }

        EnumMap<BlessingId, Integer> parsed = new EnumMap<>(BlessingId.class);
        long issuedTotal = 0L;
        Tag rawList = tag.get(listKey);
        if (!(rawList instanceof ListTag entries)
            || entries.size() > BlessingId.values().length) {
            auditable = false;
        } else {
            for (int i = 0; i < entries.size(); i++) {
                Tag rawEntry = entries.get(i);
                if (!(rawEntry instanceof CompoundTag entry)
                    || !entry.contains("WireId", Tag.TAG_INT)
                    || !entry.contains("Id", Tag.TAG_STRING)
                    || !entry.contains(countKey, Tag.TAG_INT)) {
                    auditable = false;
                    continue;
                }
                Optional<BlessingId> blessing = decodeId(entry);
                int count = entry.getInt(countKey);
                if (blessing.isEmpty() || count <= 0 || count > perTypeMax
                    || parsed.containsKey(blessing.get())) {
                    auditable = false;
                    continue;
                }
                parsed.put(blessing.get(), count);
                issuedTotal += count;
                if (issuedTotal > MAX_COUNTER) {
                    auditable = false;
                }
            }
        }
        if (issuedTotal != rawSpent) {
            auditable = false;
        }

        if (!auditable || persistedQuarantine) {
            // Conservatively consume every bounded earned offer. A corrupt
            // ledger can neither grant an effect nor mint replacement seals.
            state.earned = boundCounter(rawEarned);
            state.spent = state.earned;
            state.revision = Math.min(MAX_REVISION,
                state.earned + state.spent);
            state.quarantined = true;
            return state;
        }

        state.earned = rawEarned;
        state.spent = rawSpent;
        state.revision = rawRevision;
        state.issued.putAll(parsed);
        return state;
    }

    private static Optional<BlessingId> decodeId(CompoundTag entry) {
        Optional<BlessingId> byWire = BlessingId.tryFromWireId(
            entry.getInt("WireId"));
        if (byWire.isEmpty()
            || !byWire.get().id().equals(entry.getString("Id"))) {
            return Optional.empty();
        }
        return byWire;
    }

    private static int boundCounter(int value) {
        return Math.max(0, Math.min(MAX_COUNTER, value));
    }
}
