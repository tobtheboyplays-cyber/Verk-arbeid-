package com.hearthstead.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A tiny ledger carried by the physical projectile itself.
 *
 * <p>The compound lives in the arrow's normal persistent entity data, so a
 * flying Hearthstead arrow retains owner, projectile identity, revision and
 * already-contacted victims through chunk unload and server restart. Binding
 * the stored projectile UUID prevents copying the tag onto another arrow.</p>
 */
public final class OwnedProjectileLedger {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_CONTACTS = 8;
    public static final String ROOT_KEY = "hearthstead:owned_projectile_v1";

    private static final String KEY_SCHEMA = "Schema";
    private static final String KEY_PROJECTILE = "Projectile";
    private static final String KEY_OWNER = "Owner";
    private static final String KEY_REVISION = "Revision";
    private static final String KEY_COUNT = "Count";
    private static final String KEY_VICTIMS = "Victims";
    private static final String KEY_ID = "Id";

    public record ContactCommit(UUID projectileId, UUID ownerId, UUID victimId,
                                long revisionBefore, long revisionAfter,
                                long countBefore, long countAfter) {
    }

    /**
     * Read-only authority carried by the projectile itself.  Unlike a contact
     * commit, this inspection does not require the owner entity to be loaded:
     * incoming-damage policy needs to recognize an owned arrow precisely when
     * that owner cannot be resolved and fail closed instead of treating it as
     * an ordinary vanilla projectile.
     */
    public record Inspection(UUID projectileId, UUID ownerId,
                             long committedContacts) {
    }

    /** Issues ownership once; existing or malformed data is never overwritten. */
    public static boolean issue(AbstractArrow arrow, SettlerEntity archer) {
        if (arrow == null || archer == null
            || !(arrow.level() instanceof ServerLevel level)
            || archer.level() != level
            || archer.getProfession() != Profession.ARCHER
            || arrow.getOwner() != archer) {
            return false;
        }
        return issue(arrow.getPersistentData(), arrow.getUUID(), archer.getUUID());
    }

    static boolean issue(CompoundTag persistentData, UUID projectileId,
                         UUID ownerId) {
        if (persistentData == null || !validId(projectileId)
            || !validId(ownerId) || persistentData.contains(ROOT_KEY)) {
            return false;
        }
        CompoundTag ledger = new CompoundTag();
        ledger.putInt(KEY_SCHEMA, SCHEMA_VERSION);
        ledger.putUUID(KEY_PROJECTILE, projectileId);
        ledger.putUUID(KEY_OWNER, ownerId);
        ledger.putLong(KEY_REVISION, 0L);
        ledger.putLong(KEY_COUNT, 0L);
        ledger.put(KEY_VICTIMS, new ListTag());
        persistentData.put(ROOT_KEY, ledger);
        return true;
    }

    @Nullable
    public static ContactCommit commit(AbstractArrow arrow, SettlerEntity archer,
                                       LivingEntity victim) {
        if (arrow == null || archer == null || victim == null
            || !(arrow.level() instanceof ServerLevel level)
            || archer.level() != level || victim.level() != level
            || archer.getProfession() != Profession.ARCHER
            || arrow.getOwner() != archer) {
            return null;
        }
        return commit(arrow.getPersistentData(), arrow.getUUID(),
            archer.getUUID(), victim.getUUID());
    }

    @Nullable
    static ContactCommit commit(CompoundTag persistentData, UUID projectileId,
                                 UUID ownerId, UUID victimId) {
        State state = read(persistentData, projectileId, ownerId);
        if (state == null || !validId(victimId)
            || state.victims.contains(victimId)
            || state.victims.size() >= MAX_CONTACTS
            || state.revision == Long.MAX_VALUE
            || state.count == Long.MAX_VALUE) {
            return null;
        }
        long revisionBefore = state.revision;
        long countBefore = state.count;
        state.victims.add(victimId);
        state.revision++;
        state.count++;
        write(persistentData, state);
        return new ContactCommit(projectileId, ownerId, victimId,
            revisionBefore, state.revision, countBefore, state.count);
    }

    public static long committedCount(AbstractArrow arrow) {
        Inspection inspection = inspect(arrow);
        return inspection == null ? -1L : inspection.committedContacts();
    }

    /** True for any projectile which contains the Hearthstead authority key. */
    public static boolean claimsOwnership(AbstractArrow arrow) {
        return arrow != null && arrow.getPersistentData().contains(ROOT_KEY);
    }

    /**
     * Strictly validates the self-contained projectile ledger without
     * consulting {@link AbstractArrow#getOwner()}.  A null result means the
     * claim is malformed or copied and must never grant gameplay authority.
     */
    @Nullable
    public static Inspection inspect(AbstractArrow arrow) {
        return arrow == null ? null
            : inspect(arrow.getPersistentData(), arrow.getUUID());
    }

    @Nullable
    static Inspection inspect(CompoundTag persistentData, UUID projectileId) {
        if (persistentData == null || !validId(projectileId)
            || !persistentData.contains(ROOT_KEY, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag tag = persistentData.getCompound(ROOT_KEY);
        if (!tag.hasUUID(KEY_OWNER)) {
            return null;
        }
        UUID ownerId = tag.getUUID(KEY_OWNER);
        State state = read(persistentData, projectileId, ownerId);
        return state == null ? null : new Inspection(state.projectileId,
            state.ownerId, state.count);
    }

    @Nullable
    private static State read(CompoundTag persistentData, UUID projectileId,
                              UUID ownerId) {
        if (persistentData == null || !validId(projectileId) || !validId(ownerId)
            || !persistentData.contains(ROOT_KEY, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag tag = persistentData.getCompound(ROOT_KEY);
        if (!tag.contains(KEY_SCHEMA, Tag.TAG_INT)
            || tag.getInt(KEY_SCHEMA) != SCHEMA_VERSION
            || !tag.hasUUID(KEY_PROJECTILE) || !tag.hasUUID(KEY_OWNER)
            || !projectileId.equals(tag.getUUID(KEY_PROJECTILE))
            || !ownerId.equals(tag.getUUID(KEY_OWNER))
            || !tag.contains(KEY_REVISION, Tag.TAG_LONG)
            || !tag.contains(KEY_COUNT, Tag.TAG_LONG)
            || !tag.contains(KEY_VICTIMS, Tag.TAG_LIST)) {
            return null;
        }
        long revision = tag.getLong(KEY_REVISION);
        long count = tag.getLong(KEY_COUNT);
        if (revision < 0 || count < 0 || revision != count) {
            return null;
        }
        Tag rawVictims = tag.get(KEY_VICTIMS);
        if (!(rawVictims instanceof ListTag victimsTag)
            || (!victimsTag.isEmpty()
                && victimsTag.getElementType() != Tag.TAG_COMPOUND)) {
            return null;
        }
        if (victimsTag.size() > MAX_CONTACTS || count != victimsTag.size()) {
            return null;
        }
        Set<UUID> victims = new LinkedHashSet<>();
        for (int i = 0; i < victimsTag.size(); i++) {
            CompoundTag row = victimsTag.getCompound(i);
            if (!row.hasUUID(KEY_ID)) {
                return null;
            }
            UUID victim = row.getUUID(KEY_ID);
            if (!validId(victim) || !victims.add(victim)) {
                return null;
            }
        }
        return new State(projectileId, ownerId, revision, count, victims);
    }

    private static void write(CompoundTag persistentData, State state) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_SCHEMA, SCHEMA_VERSION);
        tag.putUUID(KEY_PROJECTILE, state.projectileId);
        tag.putUUID(KEY_OWNER, state.ownerId);
        tag.putLong(KEY_REVISION, state.revision);
        tag.putLong(KEY_COUNT, state.count);
        ListTag victims = new ListTag();
        for (UUID victim : state.victims) {
            CompoundTag row = new CompoundTag();
            row.putUUID(KEY_ID, victim);
            victims.add(row);
        }
        tag.put(KEY_VICTIMS, victims);
        persistentData.put(ROOT_KEY, tag);
    }

    private static boolean validId(@Nullable UUID id) {
        return id != null && (id.getMostSignificantBits() != 0L
            || id.getLeastSignificantBits() != 0L);
    }

    private static final class State {
        private final UUID projectileId;
        private final UUID ownerId;
        private long revision;
        private long count;
        private final Set<UUID> victims;

        private State(UUID projectileId, UUID ownerId, long revision,
                      long count, Set<UUID> victims) {
            this.projectileId = projectileId;
            this.ownerId = ownerId;
            this.revision = revision;
            this.count = count;
            this.victims = victims;
        }
    }

    private OwnedProjectileLedger() {
    }
}
