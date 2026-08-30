package com.hearthstead.settlement.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Optional;

/**
 * Permanent Blessing ranks attached to one physical target.
 *
 * <p>This is deliberately a tiny, server-authoritative value object. Reads
 * are direct {@link EnumMap} lookups; nothing here ticks, scans the world, or
 * synchronizes entity data. Mutation is server-thread confined, avoiding a
 * monitor on effect reads. A malformed saved ledger is quarantined as a whole:
 * it grants no effect and cannot be used as a fresh empty ledger to apply
 * replacement ranks for free.
 */
public final class TargetBlessingState {
    public static final int DATA_VERSION = 1;
    public static final int MAX_RANK = 3;

    public enum ApplyResult {
        APPLIED,
        MAXED,
        INVALID
    }

    private final EnumMap<BlessingId, Integer> ranks =
        new EnumMap<>(BlessingId.class);
    private boolean quarantined;

    /** Applies exactly one rank, up to rank III. */
    public ApplyResult apply(@Nullable BlessingId blessing) {
        if (quarantined || blessing == null) {
            return ApplyResult.INVALID;
        }
        int current = rank(blessing);
        if (current >= MAX_RANK) {
            return ApplyResult.MAXED;
        }
        ranks.put(blessing, current + 1);
        return ApplyResult.APPLIED;
    }

    /** Constant-time, fail-closed lookup; null and quarantined state have no effect. */
    public int rank(@Nullable BlessingId blessing) {
        if (quarantined || blessing == null) {
            return 0;
        }
        return ranks.getOrDefault(blessing, 0);
    }

    public boolean quarantined() {
        return quarantined;
    }

    /** Explicit inert state for a present but malformed persisted ledger. */
    public static TargetBlessingState quarantinedEmpty() {
        TargetBlessingState state = new TargetBlessingState();
        state.quarantined = true;
        return state;
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putBoolean("Quarantined", quarantined);
        ListTag rankList = new ListTag();
        if (!quarantined) {
            for (BlessingId blessing : BlessingId.values()) {
                int savedRank = ranks.getOrDefault(blessing, 0);
                if (savedRank <= 0) {
                    continue;
                }
                CompoundTag entry = new CompoundTag();
                entry.putInt("WireId", blessing.wireId());
                entry.putString("Id", blessing.id());
                entry.putInt("Rank", savedRank);
                rankList.add(entry);
            }
        }
        tag.put("Ranks", rankList);
        return tag;
    }

    /**
     * Strict bounded decoder. Unknown ids, duplicate ids, mismatched stable
     * ids, missing/wrongly typed fields, and out-of-range ranks all quarantine
     * the complete target ledger.
     */
    public static TargetBlessingState readNbt(@Nullable CompoundTag tag) {
        if (tag == null) {
            return quarantinedEmpty();
        }

        boolean auditable = tag.contains("DataVersion", Tag.TAG_INT)
            && tag.getInt("DataVersion") == DATA_VERSION
            && tag.contains("Quarantined", Tag.TAG_BYTE);
        boolean persistedQuarantine = tag.contains("Quarantined", Tag.TAG_BYTE)
            && tag.getBoolean("Quarantined");
        TargetBlessingState parsedState = new TargetBlessingState();

        Tag rawRanks = tag.get("Ranks");
        if (!(rawRanks instanceof ListTag rankList)
            || rankList.size() > BlessingId.values().length) {
            auditable = false;
        } else {
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
                int savedRank = entry.getInt("Rank");
                if (blessing.isEmpty() || savedRank <= 0 || savedRank > MAX_RANK
                    || parsedState.ranks.containsKey(blessing.get())) {
                    auditable = false;
                    continue;
                }
                parsedState.ranks.put(blessing.get(), savedRank);
            }
        }

        if (!auditable || persistedQuarantine) {
            return quarantinedEmpty();
        }
        return parsedState;
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
}
