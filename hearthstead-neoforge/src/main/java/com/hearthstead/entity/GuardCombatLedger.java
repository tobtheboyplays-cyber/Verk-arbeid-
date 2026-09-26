package com.hearthstead.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Minimal persisted proof for combat terminals that outlive one entity tick.
 *
 * <p>Kill-XP sources are retained for the whole useful XP domain. The smallest
 * award is ten and combat XP caps at 480, so sixty-four identities cover every
 * possible successful award without eviction. Shield actions use a monotonic
 * tick floor plus a bounded same-tick set: an old action can never return after
 * restart, while legitimate future blocks do not accumulate an unbounded UUID
 * history.</p>
 *
 * <p>Malformed or future data quarantines this evidence layer. Gameplay damage
 * is never suppressed because telemetry failed, but quarantined evidence can
 * never author a committed telemetry record.</p>
 */
public final class GuardCombatLedger {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_XP_SOURCES = 64;
    public static final int MAX_SHIELD_ACTIONS_PER_TICK = 32;

    private static final String KEY_SCHEMA = "Schema";
    private static final String KEY_QUARANTINED = "Quarantined";
    private static final String KEY_XP_REVISION = "XpRevision";
    private static final String KEY_XP_COUNT = "XpCount";
    private static final String KEY_XP_SOURCES = "XpSources";
    private static final String KEY_SHIELD_REVISION = "ShieldRevision";
    private static final String KEY_SHIELD_COUNT = "ShieldCount";
    private static final String KEY_LAST_SHIELD_TICK = "LastShieldTick";
    private static final String KEY_SHIELD_ACTIONS = "ShieldActions";
    private static final String KEY_ID = "Id";

    private final Set<UUID> xpSources = new LinkedHashSet<>();
    private final Set<UUID> shieldActionsAtLastTick = new LinkedHashSet<>();
    private long xpRevision;
    private long xpCount;
    private long shieldRevision;
    private long shieldCount;
    private long lastShieldTick = Long.MIN_VALUE;
    private boolean quarantined;

    public record XpCommit(UUID sourceId, long revisionBefore,
                           long revisionAfter, long countBefore,
                           long countAfter, int experienceBefore,
                           int experienceAfter) {
        public int added() {
            return experienceAfter - experienceBefore;
        }
    }

    public record ShieldCommit(UUID actionId, long revisionBefore,
                               long revisionAfter, long countBefore,
                               long countAfter, long tick) {
    }

    /**
     * Commits one positive, already-bounded XP transition. Callers apply the
     * matching synced XP value immediately after this non-throwing mutation.
     */
    @Nullable
    public XpCommit commitXp(UUID sourceId, int before, int after) {
        if (quarantined || !validId(sourceId) || before < 0 || after <= before
            || after > GuardExperience.MAX_EXPERIENCE
            || xpSources.contains(sourceId)
            || xpSources.size() >= MAX_XP_SOURCES
            || xpRevision == Long.MAX_VALUE || xpCount == Long.MAX_VALUE) {
            return null;
        }
        long revisionBefore = xpRevision;
        long countBefore = xpCount;
        xpSources.add(sourceId);
        xpRevision++;
        xpCount++;
        return new XpCommit(sourceId, revisionBefore, xpRevision,
            countBefore, xpCount, before, after);
    }

    /**
     * Commits one genuine shield terminal. The immutable final-damage Post
     * object receives one server-tick UUID; duplicate delivery of that same
     * physical terminal therefore reaches this ledger with the same identity.
     */
    @Nullable
    public ShieldCommit commitShield(UUID actionId, long tick) {
        if (quarantined || !validId(actionId) || tick < 0
            || tick < lastShieldTick
            || shieldRevision == Long.MAX_VALUE
            || shieldCount == Long.MAX_VALUE) {
            return null;
        }
        if (tick > lastShieldTick) {
            lastShieldTick = tick;
            shieldActionsAtLastTick.clear();
        }
        if (shieldActionsAtLastTick.contains(actionId)
            || shieldActionsAtLastTick.size() >= MAX_SHIELD_ACTIONS_PER_TICK) {
            return null;
        }
        long revisionBefore = shieldRevision;
        long countBefore = shieldCount;
        shieldActionsAtLastTick.add(actionId);
        shieldRevision++;
        shieldCount++;
        return new ShieldCommit(actionId, revisionBefore, shieldRevision,
            countBefore, shieldCount, tick);
    }

    public boolean containsXpSource(UUID sourceId) {
        return validId(sourceId) && xpSources.contains(sourceId);
    }

    public long xpCount() {
        return xpCount;
    }

    public long shieldCount() {
        return shieldCount;
    }

    public boolean quarantined() {
        return quarantined;
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_SCHEMA, SCHEMA_VERSION);
        if (quarantined) {
            tag.putBoolean(KEY_QUARANTINED, true);
            return tag;
        }
        tag.putLong(KEY_XP_REVISION, xpRevision);
        tag.putLong(KEY_XP_COUNT, xpCount);
        tag.put(KEY_XP_SOURCES, writeIds(xpSources));
        tag.putLong(KEY_SHIELD_REVISION, shieldRevision);
        tag.putLong(KEY_SHIELD_COUNT, shieldCount);
        if (lastShieldTick != Long.MIN_VALUE) {
            tag.putLong(KEY_LAST_SHIELD_TICK, lastShieldTick);
            tag.put(KEY_SHIELD_ACTIONS, writeIds(shieldActionsAtLastTick));
        }
        return tag;
    }

    /**
     * Reads the optional entity-owned ledger field without conflating absence
     * with corruption. A missing field is the one valid pre-ledger migration
     * path and starts empty. Once the field exists, its NBT type is part of
     * the schema: a non-compound value is quarantined instead of being washed
     * into an apparently trustworthy legacy ledger.
     */
    public void readOptionalNbt(@Nullable Tag rawTag) {
        if (rawTag == null) {
            readNbt(null);
        } else if (rawTag instanceof CompoundTag compoundTag) {
            readNbt(compoundTag);
        } else {
            quarantine();
        }
    }

    /** Replaces the entire domain atomically or quarantines it. */
    public void readNbt(@Nullable CompoundTag tag) {
        reset();
        if (tag == null || tag.isEmpty()) {
            return;
        }
        if (!tag.contains(KEY_SCHEMA, Tag.TAG_INT)
            || tag.getInt(KEY_SCHEMA) != SCHEMA_VERSION
            || tag.getBoolean(KEY_QUARANTINED)) {
            quarantine();
            return;
        }
        if (!tag.contains(KEY_XP_REVISION, Tag.TAG_LONG)
            || !tag.contains(KEY_XP_COUNT, Tag.TAG_LONG)
            || !tag.contains(KEY_XP_SOURCES, Tag.TAG_LIST)
            || !tag.contains(KEY_SHIELD_REVISION, Tag.TAG_LONG)
            || !tag.contains(KEY_SHIELD_COUNT, Tag.TAG_LONG)) {
            quarantine();
            return;
        }
        long readXpRevision = tag.getLong(KEY_XP_REVISION);
        long readXpCount = tag.getLong(KEY_XP_COUNT);
        long readShieldRevision = tag.getLong(KEY_SHIELD_REVISION);
        long readShieldCount = tag.getLong(KEY_SHIELD_COUNT);
        Set<UUID> readXpSources = readIds(tag, KEY_XP_SOURCES,
            MAX_XP_SOURCES);
        Set<UUID> readShieldActions = readIds(tag, KEY_SHIELD_ACTIONS,
            MAX_SHIELD_ACTIONS_PER_TICK);
        boolean hasLastShieldTick = tag.contains(KEY_LAST_SHIELD_TICK,
            Tag.TAG_LONG);
        long readLastShieldTick = hasLastShieldTick
            ? tag.getLong(KEY_LAST_SHIELD_TICK) : Long.MIN_VALUE;

        if (readXpSources == null || readShieldActions == null
            || readXpRevision < 0 || readXpCount < 0
            || readXpRevision != readXpCount
            || readXpCount != readXpSources.size()
            || readShieldRevision < 0 || readShieldCount < 0
            || readShieldRevision != readShieldCount
            || (hasLastShieldTick && readLastShieldTick < 0)
            || (readShieldCount == 0L
                && (hasLastShieldTick || !readShieldActions.isEmpty()))
            || (readShieldCount > 0L
                && (!hasLastShieldTick || readShieldActions.isEmpty()))) {
            quarantine();
            return;
        }
        xpRevision = readXpRevision;
        xpCount = readXpCount;
        shieldRevision = readShieldRevision;
        shieldCount = readShieldCount;
        lastShieldTick = readLastShieldTick;
        xpSources.addAll(readXpSources);
        shieldActionsAtLastTick.addAll(readShieldActions);
    }

    private static ListTag writeIds(Set<UUID> ids) {
        ListTag list = new ListTag();
        for (UUID id : ids) {
            CompoundTag row = new CompoundTag();
            row.putUUID(KEY_ID, id);
            list.add(row);
        }
        return list;
    }

    @Nullable
    private static Set<UUID> readIds(CompoundTag tag, String key, int cap) {
        if (!tag.contains(key)) {
            return new LinkedHashSet<>();
        }
        Tag raw = tag.get(key);
        if (!(raw instanceof ListTag list)
            || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) {
            return null;
        }
        if (list.size() > cap) {
            return null;
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag row = list.getCompound(i);
            if (!row.hasUUID(KEY_ID)) {
                return null;
            }
            UUID id = row.getUUID(KEY_ID);
            if (!validId(id) || !ids.add(id)) {
                return null;
            }
        }
        return ids;
    }

    private void reset() {
        xpSources.clear();
        shieldActionsAtLastTick.clear();
        xpRevision = 0L;
        xpCount = 0L;
        shieldRevision = 0L;
        shieldCount = 0L;
        lastShieldTick = Long.MIN_VALUE;
        quarantined = false;
    }

    private void quarantine() {
        reset();
        quarantined = true;
    }

    private static boolean validId(@Nullable UUID id) {
        return id != null && (id.getMostSignificantBits() != 0L
            || id.getLeastSignificantBits() != 0L);
    }
}
