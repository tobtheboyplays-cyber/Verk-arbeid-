package com.hearthstead.settlement.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Settlement-wide Blessing ledger.
 *
 * <p>The compare-and-commit operation is synchronized even though normal
 * Minecraft mutation happens on the server thread. That makes the atomicity
 * contract explicit and prevents a future async packet path from turning two
 * simultaneous player clicks into two spends. Player identity deliberately
 * never appears here: one settlement owns one shared offer ledger.
 */
public final class BlessingState {
    public static final int MAX_COUNTER = 1_000_000;
    public static final int MAX_REVISION = MAX_COUNTER * 2;

    public enum CommitResult {
        ACCEPTED,
        STALE,
        NO_OFFER,
        INVALID
    }

    private int earned;
    private int spent;
    private int revision;
    /** Persisted fail-closed quarantine; only an explicit future repair may clear it. */
    private boolean quarantined;
    private final EnumMap<BlessingId, Integer> ranks =
        new EnumMap<>(BlessingId.class);

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

    public synchronized int rank(BlessingId blessing) {
        return blessing == null ? 0 : ranks.getOrDefault(blessing, 0);
    }

    public synchronized Map<BlessingId, Integer> ranks() {
        return Map.copyOf(ranks);
    }

    public synchronized boolean quarantined() {
        return quarantined;
    }

    /** Fail-closed state for a missing or wrongly typed v1 ledger. */
    public static BlessingState quarantinedEmpty() {
        BlessingState state = new BlessingState();
        state.quarantined = true;
        return state;
    }

    /**
     * Adds one earned choice. Slice B calls this only after a raid result has
     * passed {@link RaidLifecycle#mayGrantReward()}; this state intentionally
     * does not infer eligibility from legacy raid fields.
     */
    public synchronized boolean grantOffer() {
        if (quarantined || earned >= MAX_COUNTER || revision >= MAX_REVISION) {
            return false;
        }
        earned++;
        revision++;
        return true;
    }

    /**
     * Atomically consumes exactly the offer the client saw.
     *
     * <p>Revision is checked before offer availability. That ordering is the
     * crucial two-player invariant: after player A spends token one, player
     * B's stale resend cannot accidentally consume token two even if a second
     * offer already exists.
     */
    public synchronized CommitResult compareAndCommit(int expectedRevision,
                                                       int expectedOfferSerial,
                                                       BlessingId blessing) {
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
        if (spent >= MAX_COUNTER || rank(blessing) >= MAX_COUNTER
            || revision >= MAX_REVISION) {
            return CommitResult.INVALID;
        }
        ranks.put(blessing, rank(blessing) + 1);
        spent++;
        revision++;
        return CommitResult.ACCEPTED;
    }

    public synchronized CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Earned", earned);
        tag.putInt("Spent", spent);
        tag.putInt("Revision", revision);
        tag.putBoolean("Quarantined", quarantined);
        ListTag rankList = new ListTag();
        for (BlessingId blessing : BlessingId.values()) {
            int rank = ranks.getOrDefault(blessing, 0);
            if (rank <= 0) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("WireId", blessing.wireId());
            entry.putString("Id", blessing.id());
            entry.putInt("Rank", rank);
            rankList.add(entry);
        }
        tag.put("Ranks", rankList);
        return tag;
    }

    public static BlessingState readNbt(CompoundTag tag) {
        BlessingState state = new BlessingState();
        boolean auditable = true;
        boolean persistedQuarantine = false;
        if (tag.contains("Quarantined", Tag.TAG_BYTE)) {
            persistedQuarantine = tag.getBoolean("Quarantined");
        } else {
            auditable = false;
        }
        int rawEarned = 0;
        if (tag.contains("Earned", Tag.TAG_INT)) {
            rawEarned = tag.getInt("Earned");
        } else {
            auditable = false;
        }
        if (rawEarned < 0 || rawEarned > MAX_COUNTER) {
            auditable = false;
        }
        state.earned = boundCounter(rawEarned);

        int rawSpent = 0;
        if (tag.contains("Spent", Tag.TAG_INT)) {
            rawSpent = tag.getInt("Spent");
        } else {
            auditable = false;
        }
        if (rawSpent < 0 || rawSpent > state.earned) {
            auditable = false;
        }
        int savedSpent = boundCounter(rawSpent);

        int rawRevision = 0;
        if (tag.contains("Revision", Tag.TAG_INT)) {
            rawRevision = tag.getInt("Revision");
        } else {
            auditable = false;
        }
        if (rawRevision < 0 || rawRevision > MAX_REVISION
            || rawRevision != state.earned + savedSpent) {
            auditable = false;
        }
        state.revision = Math.max(0, Math.min(MAX_REVISION, rawRevision));

        EnumMap<BlessingId, Integer> parsed = new EnumMap<>(BlessingId.class);
        long rankTotal = 0L;
        Tag rawRanks = tag.get("Ranks");
        ListTag rankList;
        if (rawRanks instanceof ListTag list) {
            rankList = list;
        } else {
            auditable = false;
            rankList = new ListTag();
        }
        for (int i = 0; i < rankList.size(); i++) {
            Tag rawEntry = rankList.get(i);
            if (!(rawEntry instanceof CompoundTag entry)
                || !entry.contains("WireId", Tag.TAG_INT)
                || !entry.contains("Id", Tag.TAG_STRING)
                || !entry.contains("Rank", Tag.TAG_INT)) {
                auditable = false;
                continue;
            }
            Optional<BlessingId> blessing = decodeId(entry);
            int rank = entry.getInt("Rank");
            if (blessing.isEmpty() || rank <= 0 || rank > MAX_COUNTER) {
                auditable = false;
                continue;
            }
            if (parsed.containsKey(blessing.get())) {
                auditable = false;
                continue;
            }
            parsed.put(blessing.get(), rank);
            rankTotal += rank;
            if (rankTotal > MAX_COUNTER) {
                auditable = false;
            }
        }

        if (rankTotal != savedSpent) {
            auditable = false;
        }
        if (!auditable || persistedQuarantine) {
            // No structurally corrupt v1 ledger may grant a perk or turn a
            // missing/unknown spend into a fresh offer. Preserve only the
            // bounded earned count, conservatively accounting every token.
            state.ranks.clear();
            state.spent = state.earned;
            state.revision = Math.max(state.revision,
                Math.min(MAX_REVISION, state.earned + state.spent));
            state.quarantined = true;
            return state;
        }
        state.ranks.putAll(parsed);
        state.spent = savedSpent;
        return state;
    }

    private static Optional<BlessingId> decodeId(CompoundTag entry) {
        if (entry.contains("WireId", Tag.TAG_INT)) {
            Optional<BlessingId> wire = BlessingId.tryFromWireId(entry.getInt("WireId"));
            if (wire.isEmpty()) {
                return Optional.empty();
            }
            if (entry.contains("Id", Tag.TAG_STRING)
                && !wire.get().id().equals(entry.getString("Id"))) {
                return Optional.empty();
            }
            return wire;
        }
        return BlessingId.tryFromId(entry.getString("Id"));
    }

    private static int boundCounter(int value) {
        return Math.max(0, Math.min(MAX_COUNTER, value));
    }
}
