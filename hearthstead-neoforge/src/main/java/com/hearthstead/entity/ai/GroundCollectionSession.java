package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.DeferredItemMaterializationSavedData;
import com.hearthstead.settlement.work.WorkerStorageAuthority;
import com.hearthstead.util.QaTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import javax.annotation.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Server-authoritative physical-item transaction used by field workers.
 *
 * <p>The world {@link ItemEntity} is authoritative until the authored
 * pickup contact. One bounded source portion then moves into the worker's
 * synced and persisted offhand. It does not enter the persistent work-container
 * inventory ({@link SettlerEntity#bag}) until the worker is back at the
 * placed container and the stow contact lands. This gives every future
 * gatherer the same no-teleport/no-dupe boundary instead of reimplementing
 * a subtly different pickup loop per profession.
 *
 * <p>The owned-drop table is deliberately bounded. UUID plus last known
 * block position is intent, never a second copy of an item: if a loaded
 * entity disappears, the row is discarded; if its chunk is unloaded, the
 * row is retained without guessing that the physical item vanished. Pickup
 * protection is a finite, periodically renewed lease. If the worker or its
 * goal unloads, the real item becomes normally pickupable after the lease
 * instead of being stranded forever.
 */
public final class GroundCollectionSession {
    public static final int OWNERSHIP_LEASE_TICKS = 100;
    public static final int OWNERSHIP_REFRESH_TICKS = 20;
    private static final String PERSISTENT_OWNER_TAG =
        "HearthsteadGroundCollectionOwner";
    private static final String PERSISTENT_LEASE_EXPIRY_TAG =
        "HearthsteadGroundCollectionLeaseExpiry";
    /** Relative lease survives an arbitrarily delayed deferred materialization. */
    private static final String PERSISTENT_LEASE_DURATION_TAG =
        "HearthsteadGroundCollectionLeaseDuration";
    /**
     * The carried contact stack lives in the settler's real offhand, but the
     * session object that knows it owns that hand is transient. Keep only that
     * ownership bit on the owning entity; the stack itself remains the sole
     * item authority in the ordinary persisted equipment slot.
     */
    public static final String PERSISTENT_OFFHAND_OWNERSHIP_TAG =
        "HearthsteadGroundCollectionOwnsOffhand";
    private static final String PERSISTENT_DROP_INDEX_TAG =
        "HearthsteadGroundCollectionDrops";
    private static final String DROP_ID_TAG = "Id";
    private static final String DROP_POS_TAG = "Pos";

    public enum PickupResult {
        PICKED,
        NO_TARGET,
        TOO_FAR,
        OCCLUDED,
        OFFHAND_OCCUPIED,
        INELIGIBLE
    }

    public enum StowResult {
        STOWED,
        FULL,
        NO_CARRIED_ITEM
    }

    private final SettlerEntity worker;
    private final Predicate<ItemStack> eligible;
    private final int maxTracked;
    private final Map<UUID, BlockPos> ownedDrops = new LinkedHashMap<>();
    @Nullable
    private UUID selectedDrop;
    private boolean ownsOffhand;
    private int refreshIn;

    public GroundCollectionSession(SettlerEntity worker,
                                   Predicate<ItemStack> eligible,
                                   int maxTracked) {
        if (maxTracked < 1) {
            throw new IllegalArgumentException("maxTracked must be positive");
        }
        this.worker = worker;
        this.eligible = eligible;
        this.maxTracked = maxTracked;
    }

    /**
     * Applies the same finite worker lease to an ItemEntity that another
     * authoritative world event already created. The item remains the sole
     * stack authority; this method never copies, inserts or recreates it.
     * Hoppers may still consume the entity immediately and players regain
     * ordinary pickup when the finite lease expires.
     */
    public static boolean leaseExisting(SettlerEntity worker,
                                        ItemEntity item) {
        if (worker == null || item == null || !worker.isAlive()
            || !item.isAlive() || item.getItem().isEmpty()
            || worker.level() != item.level()
            || item.level().isClientSide) {
            return false;
        }
        CompoundTag persistent = item.getPersistentData();
        if (persistent.hasUUID(PERSISTENT_OWNER_TAG)
            && !worker.getUUID().equals(
                persistent.getUUID(PERSISTENT_OWNER_TAG))) {
            return false;
        }
        persistent.putUUID(PERSISTENT_OWNER_TAG, worker.getUUID());
        persistent.putInt(PERSISTENT_LEASE_DURATION_TAG,
            OWNERSHIP_LEASE_TICKS);
        persistent.putLong(PERSISTENT_LEASE_EXPIRY_TAG,
            item.level().getGameTime() + OWNERSHIP_LEASE_TICKS);
        item.setExtendedLifetime();
        item.setPickUpDelay(OWNERSHIP_LEASE_TICKS);
        item.setTarget(worker.getUUID());
        return true;
    }

    /**
     * Turns one produced stack into a real world drop at its real source.
     * A rejected/overflow row is still spawned, just not claimed, so a full
     * tracker can never delete output.
     */
    public boolean spawnPhysical(ServerLevel level, BlockPos source,
                                 ItemStack produced) {
        UUID transferId = queuePhysical(level, source, produced);
        if (transferId == null) {
            return false;
        }
        // A rejected entity insertion is still success here: the dimension's
        // SavedData owns the exact stack and retries it with the same UUID.
        // Callers may therefore clear their source only after queuePhysical
        // returned a real transfer id.
        materializeQueued(level, source, transferId);
        return true;
    }

    /**
     * Phase one of a physical source transfer. The exact stack and the
     * worker's bounded pickup lease enter durable dimension ownership before
     * a block, bag slot or other source is cleared.
     *
     * @return the stable eventual ItemEntity UUID, or {@code null} while the
     *         bounded escrow cannot safely accept another stack
     */
    @Nullable
    public UUID queuePhysical(ServerLevel level, BlockPos source,
                              ItemStack produced) {
        return queuePhysical(level, source, produced, 0L);
    }

    public UUID queuePhysical(ServerLevel level, BlockPos source,
                              ItemStack produced, long notBeforeGameTime) {
        if (level == null || source == null || produced == null
            || produced.isEmpty()) {
            return null;
        }
        boolean canOwn = eligible.test(produced)
            && ownedDrops.size() < maxTracked;
        CompoundTag persistent = new CompoundTag();
        if (notBeforeGameTime > level.getGameTime()) {
            long delay = Math.min(100L, notBeforeGameTime - level.getGameTime());
            persistent.putLong("HearthsteadMaterializeNotBefore", level.getGameTime() + delay);
        }
        if (canOwn) {
            persistent.putUUID(PERSISTENT_OWNER_TAG, worker.getUUID());
            // Author the absolute expiry only when the ItemEntity actually
            // materializes. A world that rejects insertion for 100+ ticks
            // must not create a real-but-already-abandoned log on retry.
            persistent.putInt(PERSISTENT_LEASE_DURATION_TAG,
                OWNERSHIP_LEASE_TICKS);
        }
        DeferredItemMaterializationSavedData.ItemEntityOptions options =
            new DeferredItemMaterializationSavedData.ItemEntityOptions(
                persistent, canOwn ? OWNERSHIP_LEASE_TICKS : 0,
                canOwn ? worker.getUUID() : null, true);
        try {
            UUID queued = DeferredItemMaterializationSavedData.get(level).queue(level,
                source.getX() + 0.5, source.getY() + 0.5,
                source.getZ() + 0.5, produced, options);
            if (queued != null && canOwn) {
                // The durable queue is already the only item authority even
                // before ItemEntity insertion succeeds. Track its stable UUID
                // now so a one-tick materialization delay cannot look like an
                // empty forest and let the worker abandon this exact output.
                ownedDrops.put(queued, source.immutable());
                persistOwnedDropIndex();
                trace("ground_collection_queued", "id=" + queued
                    + ";source=" + source + ";tracked=" + ownedDrops.size());
            }
            return queued;
        } catch (IllegalArgumentException | IllegalStateException refused) {
            return null;
        }
    }

    /** Phase-two attempt; a false result remains safely owned by SavedData. */
    public boolean materializeQueued(ServerLevel level, BlockPos source,
                                     UUID transferId) {
        if (level == null || source == null || transferId == null) {
            return false;
        }
        boolean materialized = DeferredItemMaterializationSavedData.get(level)
            .materialize(level, transferId);
        if (materialized && level.getEntity(transferId) instanceof ItemEntity item
            && item.isAlive() && eligible.test(item.getItem())
            && ownedByWorker(item) && activeOwnershipLease(item)
            && (ownedDrops.containsKey(transferId)
                || ownedDrops.size() < maxTracked)) {
            ownedDrops.put(transferId, source.immutable());
            persistOwnedDropIndex();
        }
        trace("ground_collection_materialized", "id=" + transferId
            + ";result=" + materialized
            + ";entity=" + (level.getEntity(transferId) == null
                ? "missing" : level.getEntity(transferId).getClass().getSimpleName())
            + ";tracked=" + ownedDrops.size());
        return materialized;
    }

    /** Rollback is legal only before the caller clears the physical source. */
    public boolean cancelQueued(ServerLevel level, UUID transferId) {
        if (level == null || transferId == null
            || !DeferredItemMaterializationSavedData.get(level)
                .cancel(transferId)) {
            return false;
        }
        ownedDrops.remove(transferId);
        if (transferId.equals(selectedDrop)) {
            selectedDrop = null;
        }
        persistOwnedDropIndex();
        return true;
    }

    /**
     * Rebuilds the transient bounded index from physical entities after an
     * entity save/reload. Ownership is stored on the item entity itself, not
     * as a copied stack, so recovery can never manufacture cargo. The caller
     * supplies a profession-appropriate bounded search volume.
     */
    public int recoverOwned(ServerLevel level, AABB bounds) {
        return recoverOwned(level, bounds, ignored -> true, ignored -> true);
    }

    /**
     * Exact-authority recovery. Unloaded rows retain only in-zone positional
     * intent; a loaded entity must additionally pass the live predicate before
     * it can be re-indexed or have its pickup delay renewed.
     */
    public int recoverOwned(ServerLevel level, AABB bounds,
                            Predicate<BlockPos> persistedAuthority,
                            Predicate<BlockPos> liveAuthority) {
        restorePersistedIndex(level, persistedAuthority, liveAuthority);
        if (ownedDrops.size() >= maxTracked) {
            return 0;
        }
        int before = ownedDrops.size();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                bounds, candidate -> candidate.isAlive()
                    && eligible.test(candidate.getItem())
                    && ownedByWorker(candidate)
                    && activeOwnershipLease(candidate)
                    && liveAuthority.test(candidate.blockPosition()))) {
            if (ownedDrops.size() >= maxTracked) {
                break;
            }
            ownedDrops.put(item.getUUID(), item.blockPosition().immutable());
            item.setPickUpDelay(OWNERSHIP_LEASE_TICKS);
        }
        persistOwnedDropIndex();
        return ownedDrops.size() - before;
    }

    /**
     * Restores only UUID plus last-known position intent from the owning
     * settler. No ItemStack is serialized here: the physical ItemEntity must
     * still resolve with the same live owner lease before any transfer.
     */
    public void restorePersistedIndex(ServerLevel level) {
        restorePersistedIndex(level, ignored -> true, ignored -> true);
    }

    /** See {@link #recoverOwned(ServerLevel, AABB, Predicate, Predicate)}. */
    public void restorePersistedIndex(ServerLevel level,
                                      Predicate<BlockPos> persistedAuthority,
                                      Predicate<BlockPos> liveAuthority) {
        ListTag rows = worker.getPersistentData().getList(
            PERSISTENT_DROP_INDEX_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < rows.size()
             && ownedDrops.size() < maxTracked; index++) {
            CompoundTag row = rows.getCompound(index);
            if (!row.hasUUID(DROP_ID_TAG)
                || !row.contains(DROP_POS_TAG, Tag.TAG_LONG)) {
                continue;
            }
            UUID id = row.getUUID(DROP_ID_TAG);
            BlockPos last = BlockPos.of(row.getLong(DROP_POS_TAG));
            Entity entity = level.getEntity(id);
            if (entity instanceof ItemEntity item && item.isAlive()) {
                if (eligible.test(item.getItem()) && ownedByWorker(item)
                    && activeOwnershipLease(item)
                    && liveAuthority.test(item.blockPosition())) {
                    ownedDrops.put(id, item.blockPosition().immutable());
                }
            } else if (DeferredItemMaterializationSavedData.get(level)
                    .pendingForTarget(id, worker.getUUID())
                && persistedAuthority.test(last)
                && liveAuthority.test(last)) {
                // The SavedData row, not this positional hint, owns the item.
                // Retain the UUID only so the worker waits for its real entity.
                ownedDrops.put(id, last.immutable());
            } else if (!level.hasChunkAt(last)
                && persistedAuthority.test(last)) {
                // An unloaded UUID remains intent only. It can never become an
                // item until the original entity is loaded and verified.
                ownedDrops.put(id, last.immutable());
            }
        }
        persistOwnedDropIndex();
    }

    /**
     * Narrow Farmer field-bag recovery for a UUID recorded before the finite
     * public lease expired.  Generic collection recovery deliberately cannot
     * call this: an unowned world item is reclaimed only after the caller has
     * independently proved its original UUID, exact stack image and current
     * work authority.
     */
    boolean reclaimExactExpiredFieldOutput(ServerLevel level, UUID id,
                                            ItemStack expected,
                                            Predicate<ItemEntity> authorized) {
        if (level == null || id == null || expected == null || expected.isEmpty()
            || authorized == null) {
            return false;
        }
        Entity entity = level.getEntity(id);
        if (!(entity instanceof ItemEntity item) || !item.isAlive()
            || !ItemStack.matches(expected, item.getItem())
            || !eligible.test(item.getItem()) || !authorized.test(item)) {
            return false;
        }
        // A live lease owned by somebody else always wins.  An absent owner
        // is admissible only through this persisted exact-field proof.
        if (item.getPersistentData().hasUUID(PERSISTENT_OWNER_TAG)
            && !ownedByWorker(item)) {
            return false;
        }
        if (!ownedDrops.containsKey(id) && ownedDrops.size() >= maxTracked) {
            return false;
        }
        ownedDrops.put(id, item.blockPosition().immutable());
        markOwned(item);
        item.setPickUpDelay(OWNERSHIP_LEASE_TICKS);
        persistOwnedDropIndex();
        trace("ground_collection_exact_field_reclaim", "id=" + id
            + ";tracked=" + ownedDrops.size());
        return true;
    }
    /** Refreshes loaded ownership leases and last-known positions. */
    public void heartbeat(ServerLevel level) {
        if (--refreshIn > 0) {
            return;
        }
        refreshIn = OWNERSHIP_REFRESH_TICKS;
        Iterator<Map.Entry<UUID, BlockPos>> iterator = ownedDrops.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, BlockPos> row = iterator.next();
            Entity entity = level.getEntity(row.getKey());
            if (entity instanceof ItemEntity item && item.isAlive()
                && eligible.test(item.getItem())
                && ownedByWorker(item) && activeOwnershipLease(item)) {
                row.setValue(item.blockPosition().immutable());
                markOwned(item);
                item.setPickUpDelay(OWNERSHIP_LEASE_TICKS);
            } else if (DeferredItemMaterializationSavedData.get(level)
                    .pendingForTarget(row.getKey(), worker.getUUID())) {
                // Loaded absence is expected while the durable queue retries
                // ItemEntity insertion; it is not proof that output vanished.
                continue;
            } else if (level.hasChunkAt(row.getValue())) {
                traceRemoval("ground_collection_heartbeat_remove", level, row);
                iterator.remove();
                if (row.getKey().equals(selectedDrop)) {
                    selectedDrop = null;
                }
            }
        }
        persistOwnedDropIndex();
    }

    /** Nearest loaded, still-real eligible drop; null can mean unloaded rows. */
    @Nullable
    public ItemEntity nearestLoaded(ServerLevel level, BlockPos origin) {
        pruneLoadedAbsences(level);
        ItemEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (Map.Entry<UUID, BlockPos> row : ownedDrops.entrySet()) {
            Entity entity = level.getEntity(row.getKey());
            if (!(entity instanceof ItemEntity item) || !item.isAlive()
                || !eligible.test(item.getItem()) || !ownedByWorker(item)
                || !activeOwnershipLease(item)) {
                continue;
            }
            row.setValue(item.blockPosition().immutable());
            double distance = item.blockPosition().distSqr(origin);
            if (distance < best) {
                best = distance;
                nearest = item;
            }
        }
        persistOwnedDropIndex();
        return nearest;
    }

    public void select(ItemEntity item) {
        selectedDrop = ownedDrops.containsKey(item.getUUID())
            ? item.getUUID() : null;
    }

    public void clearSelection() {
        selectedDrop = null;
    }

    @Nullable
    public ItemEntity selected(ServerLevel level) {
        if (selectedDrop == null) {
            return null;
        }
        Entity entity = level.getEntity(selectedDrop);
        if (entity instanceof ItemEntity item && item.isAlive()
            && eligible.test(item.getItem()) && ownedByWorker(item)
            && activeOwnershipLease(item)) {
            ownedDrops.put(selectedDrop, item.blockPosition().immutable());
            persistOwnedDropIndex();
            return item;
        }
        BlockPos last = ownedDrops.get(selectedDrop);
        if (last != null && level.hasChunkAt(last)) {
            ownedDrops.remove(selectedDrop);
            selectedDrop = null;
            persistOwnedDropIndex();
        }
        return null;
    }

    /**
     * Atomic contact-frame transfer: one real item leaves the selected
     * entity and appears in the synced offhand. The main-hand work tool is
     * never replaced.
     */
    public PickupResult takeOneToOffhand(ServerLevel level,
                                         double maxDistanceSqr) {
        // The offhand is a separate physical owner. Legacy one-item pickup
        // may hold an item while the bag is full; stowOne enforces capacity.
        return takeToOffhand(level, maxDistanceSqr, 1, false);
    }

    /**
     * Moves one bounded physical stack portion at the existing pickup
     * contact. The source entity, offhand and sack remain the only item
     * authorities. Callers opt in explicitly; ordinary gatherers keep the
     * exact-one method above.
     */
    public PickupResult takeUpToOffhand(ServerLevel level,
                                        double maxDistanceSqr,
                                        int maxUnits) {
        return takeToOffhand(level, maxDistanceSqr, maxUnits, true);
    }

    private PickupResult takeToOffhand(ServerLevel level,
                                       double maxDistanceSqr,
                                       int maxUnits,
                                       boolean boundByBagCapacity) {
        ItemEntity item = selected(level);
        if (item == null) {
            return PickupResult.NO_TARGET;
        }
        if (worker.distanceToSqr(item) > maxDistanceSqr) {
            return PickupResult.TOO_FAR;
        }
        if (!hasClearPickupLine(level, item)) {
            return PickupResult.OCCLUDED;
        }
        if (!worker.getOffhandItem().isEmpty()) {
            return PickupResult.OFFHAND_OCCUPIED;
        }
        ItemStack source = item.getItem();
        if (source.isEmpty() || !eligible.test(source)) {
            return PickupResult.INELIGIBLE;
        }

        int availableCapacity = boundByBagCapacity
            ? worker.getCarryCapacity() - load() : 1;
        int moved = Math.min(source.getCount(), Math.min(maxUnits,
            availableCapacity));
        if (moved < 1) {
            return PickupResult.INELIGIBLE;
        }

        UUID physicalId = item.getUUID();
        int sourceCount = source.getCount();
        ItemStack carried = source.copyWithCount(moved);
        worker.setItemSlot(EquipmentSlot.OFFHAND, carried);
        ownsOffhand = true;
        worker.getPersistentData().putBoolean(
            PERSISTENT_OFFHAND_OWNERSHIP_TAG, true);
        if (source.getCount() == moved) {
            ownedDrops.remove(item.getUUID());
            item.discard();
            selectedDrop = null;
        } else {
            source.shrink(moved);
            item.setItem(source);
            ownedDrops.put(item.getUUID(), item.blockPosition().immutable());
        }
        persistOwnedDropIndex();
        trace("ground_collection_take", "id=" + physicalId
            + ";sourceBefore=" + sourceCount
            + ";sourceAfter=" + (item.isAlive() ? item.getItem().getCount() : 0)
            + ";tracked=" + ownedDrops.size());
        return PickupResult.PICKED;
    }

    /**
     * Block-only contact ray aimed at the upper face of the real item box.
     * Vanilla {@code hasLineOfSight(Entity)} aims at an ItemEntity's very low
     * eye point; once a drop settles, that point can numerically intersect the
     * supporting floor and reject every legitimate ground pickup. The upper
     * face remains part of the same physical entity while still being fully
     * occluded by a wall.
     */
    public boolean hasClearPickupLine(ServerLevel level, ItemEntity item) {
        return hasClearPickupLineFrom(level, worker.getEyePosition(), item);
    }

    /**
     * The same block-authoritative pickup ray from a prospective standing
     * node. Route selection uses this before spending pathfinding work so it
     * never commits to the nearest side of a stump when another side is the
     * one that actually has hand contact.
     */
    public boolean hasClearPickupLineFrom(ServerLevel level, Vec3 eye,
                                          ItemEntity item) {
        if (level == null || item == null || !item.isAlive()
            || item.level() != level) {
            return false;
        }
        AABB box = item.getBoundingBox();
        Vec3 contact = new Vec3(item.getX(), box.maxY + 1.0E-3D, item.getZ());
        return level.clip(new ClipContext(eye, contact,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, worker))
            .getType() == HitResult.Type.MISS;
    }

    /**
     * Atomic container-contact transfer. Capacity is an item-count limit,
     * independent of the eight physical bag slots; both must have room.
     */
    public StowResult stowOne() {
        ItemStack carried = worker.getOffhandItem();
        if (!ownsOffhand || carried.isEmpty() || !eligible.test(carried)) {
            return StowResult.NO_CARRIED_ITEM;
        }
        if (!hasCapacity()) {
            return StowResult.FULL;
        }

        ItemStack offered = carried.copyWithCount(1);
        ItemStack remainder = worker.bag.addItem(offered);
        if (!remainder.isEmpty()) {
            return StowResult.FULL;
        }
        carried.shrink(1);
        worker.setItemSlot(EquipmentSlot.OFFHAND,
            carried.isEmpty() ? ItemStack.EMPTY : carried);
        if (carried.isEmpty()) {
            ownsOffhand = false;
            clearOffhandOwnershipMarker();
        }
        return StowResult.STOWED;
    }

    /**
     * Atomically stows the session-owned offhand stack. The authority helper
     * preflights and restores the exact bag image on a failed insertion, so a
     * partial stack never loses its physical owner at the contact boundary.
     */
    public StowResult stowAll() {
        ItemStack carried = worker.getOffhandItem();
        if (!ownsOffhand || carried.isEmpty() || !eligible.test(carried)) {
            return StowResult.NO_CARRIED_ITEM;
        }
        if (carried.getCount() > worker.getCarryCapacity() - load()
            || !WorkerStorageAuthority.storeAllInBag(worker.bag,
                java.util.List.of(carried.copy()))) {
            return StowResult.FULL;
        }
        worker.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        ownsOffhand = false;
        clearOffhandOwnershipMarker();
        return StowResult.STOWED;
    }

    /**
     * Exact hand-off out of the session-owned offhand (e.g. a Hunter laying a
     * carcass on a table or butchering it). Clears the hand, the persisted
     * marker and the session flag together and returns the one stack; the
     * caller must commit it to its next single owner in the same tick.
     * Returns empty (and changes nothing) when this session owns no item.
     */
    public ItemStack releaseCarriedForHandoff() {
        ItemStack carried = worker.getOffhandItem();
        if (!ownsOffhand || carried.isEmpty()) {
            return ItemStack.EMPTY;
        }
        worker.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        ownsOffhand = false;
        clearOffhandOwnershipMarker();
        return carried;
    }

    /**
     * Exact hand-off INTO an empty offhand from another single owner (a Lodge
     * chest slot). The caller removes the source in the same tick only after
     * this returns true.
     */
    public boolean acceptHandoff(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !eligible.test(stack)
            || !worker.getOffhandItem().isEmpty()) {
            return false;
        }
        worker.setItemSlot(EquipmentSlot.OFFHAND, stack.copy());
        ownsOffhand = true;
        worker.getPersistentData().putBoolean(PERSISTENT_OFFHAND_OWNERSHIP_TAG, true);
        return true;
    }

    /**
     * Adopt a persisted mid-pickup item only when its session-owned marker
     * survived with the real equipment stack.
     */
    public boolean adoptEligibleOffhand() {
        return adoptEligibleOffhand(false);
    }

    /**
     * Explicit recovery seam for a caller that opted into bounded stack
     * pickup. Ordinary gatherers keep the exact-one recovery rule above.
     */
    public boolean adoptEligibleStackOffhand() {
        return adoptEligibleOffhand(true);
    }

    private boolean adoptEligibleOffhand(boolean allowStack) {
        ItemStack held = worker.getOffhandItem();
        boolean persistedOwner = worker.getPersistentData().getBoolean(
            PERSISTENT_OFFHAND_OWNERSHIP_TAG);
        boolean countAllowed = !held.isEmpty()
            && (allowStack || held.getCount() == 1);
        if (!ownsOffhand && persistedOwner && countAllowed
            && eligible.test(held)) {
            ownsOffhand = true;
        } else if (persistedOwner && (!countAllowed
            || !eligible.test(held))) {
            // Fail closed if the physical hand and its ownership marker ever
            // disagree. Never claim an arbitrary replacement stack.
            clearOffhandOwnershipMarker();
        }
        return ownsOffhand;
    }

    /**
     * One-save migration seam for a pre-marker pickup interrupted while the
     * same persisted sack was visibly placed. The placed container is the
     * extra physical authority that makes adopting this exact single log safe.
     */
    public boolean adoptLegacyEligibleOffhandAtPlacedContainer() {
        if (adoptEligibleOffhand()) {
            return true;
        }
        ItemStack held = worker.getOffhandItem();
        if (!ownsOffhand && held.getCount() == 1 && eligible.test(held)) {
            ownsOffhand = true;
            worker.getPersistentData().putBoolean(
                PERSISTENT_OFFHAND_OWNERSHIP_TAG, true);
        }
        return ownsOffhand;
    }

    /**
     * Re-materialises the carried item before an interruption. The offhand
     * is only cleared after the world accepts the entity, so a failed spawn
     * cannot lose or duplicate it.
     */
    /**
     * Re-materialises carried cargo and returns the actual replacement entity
     * UUID. The item remains the only stack authority; offhand clearing waits
     * until world insertion succeeded.
     */
    @Nullable
    UUID returnCarriedToWorldId(ServerLevel level, BlockPos position,
                                boolean retainOwnership) {
        ItemStack carried = worker.getOffhandItem();
        if (!ownsOffhand || carried.isEmpty() || !eligible.test(carried)) {
            return null;
        }
        ItemEntity drop = new ItemEntity(level, position.getX() + 0.5,
            position.getY() + 0.25, position.getZ() + 0.5, carried.copy());
        drop.setExtendedLifetime();
        boolean canOwn = retainOwnership && ownedDrops.size() < maxTracked;
        if (canOwn) {
            drop.setPickUpDelay(OWNERSHIP_LEASE_TICKS);
            markOwned(drop);
        }
        if (!level.addFreshEntity(drop)) {
            return null;
        }
        worker.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        ownsOffhand = false;
        clearOffhandOwnershipMarker();
        selectedDrop = null;
        if (canOwn) {
            ownedDrops.put(drop.getUUID(), position.immutable());
            persistOwnedDropIndex();
        } else {
            drop.setNoPickUpDelay();
        }
        return drop.getUUID();
    }

    public boolean returnCarriedToWorld(ServerLevel level, BlockPos position,
                                        boolean retainOwnership) {
        return returnCarriedToWorldId(level, position, retainOwnership) != null;
    }

    /** Ordinary goal interruption: materialise carried cargo, retain drops. */
    public void suspend(ServerLevel level) {
        returnCarriedToWorld(level, worker.blockPosition(), true);
        selectedDrop = null;
    }

    /**
     * QA-JOBS J-03: every loaded drop this worker still claims stops aging, so
     * game left in the field at the evening bell (an extended ItemEntity lives
     * about 12,000 ticks, a night is longer) is still there in the morning.
     * It persists until someone picks it up; nothing is created or removed.
     */
    public void persistOwnedDrops(ServerLevel level) {
        for (UUID id : ownedDrops.keySet()) {
            if (level.getEntity(id) instanceof ItemEntity item && item.isAlive()) {
                item.setUnlimitedLifetime();
            }
        }
    }

    /** Permanent cancellation: every loaded drop becomes normally pickupable. */
    public void abandon(ServerLevel level) {
        returnCarriedToWorld(level, worker.blockPosition(), false);
        for (UUID id : ownedDrops.keySet()) {
            if (level.getEntity(id) instanceof ItemEntity item && item.isAlive()) {
                clearOwnership(item);
                item.setNoPickUpDelay();
            }
        }
        ownedDrops.clear();
        selectedDrop = null;
        // A rejected ItemEntity insertion leaves the real stack in the
        // persisted offhand. Keep its marker so a reconstructed worker can
        // resume recovery instead of treating that occupied hand as foreign.
        ItemStack retained = worker.getOffhandItem();
        boolean retainedOwnedOffhand = ownsOffhand && !retained.isEmpty()
            && eligible.test(retained)
            && worker.getPersistentData().getBoolean(
                PERSISTENT_OFFHAND_OWNERSHIP_TAG);
        if (!retainedOwnedOffhand) {
            ownsOffhand = false;
            clearOffhandOwnershipMarker();
        }
        persistOwnedDropIndex();
    }

    /** Release one unreachable item without destroying it. */
    public void releaseSelected(ServerLevel level) {
        if (selectedDrop != null && level.getEntity(selectedDrop) instanceof ItemEntity item
            && item.isAlive()) {
            clearOwnership(item);
            item.setNoPickUpDelay();
        }
        ownedDrops.remove(selectedDrop);
        selectedDrop = null;
        persistOwnedDropIndex();
    }

    /** Forget unresolved unloaded rows; their finite pickup lease expires safely. */
    public void forgetUnresolved() {
        selectedDrop = null;
        ownedDrops.clear();
        persistOwnedDropIndex();
    }

    public boolean hasCapacity() {
        return load() < worker.getCarryCapacity();
    }

    public int load() {
        int count = 0;
        for (int slot = 0; slot < worker.bag.getContainerSize(); slot++) {
            count += worker.bag.getItem(slot).getCount();
        }
        return count;
    }

    public int trackedCount() {
        return ownedDrops.size();
    }

    public boolean hasTrackedDrops() {
        return !ownedDrops.isEmpty();
    }

    public boolean ownsOffhandItem() {
        return ownsOffhand;
    }

    @Nullable
    public UUID selectedDropId() {
        return selectedDrop;
    }

    private void pruneLoadedAbsences(ServerLevel level) {
        Iterator<Map.Entry<UUID, BlockPos>> iterator = ownedDrops.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, BlockPos> row = iterator.next();
            Entity entity = level.getEntity(row.getKey());
            if (entity instanceof ItemEntity item && item.isAlive()
                && eligible.test(item.getItem()) && ownedByWorker(item)
                && activeOwnershipLease(item)) {
                row.setValue(item.blockPosition().immutable());
            } else if (DeferredItemMaterializationSavedData.get(level)
                    .pendingForTarget(row.getKey(), worker.getUUID())) {
                continue;
            } else if (level.hasChunkAt(row.getValue())) {
                traceRemoval("ground_collection_prune_remove", level, row);
                iterator.remove();
                if (row.getKey().equals(selectedDrop)) {
                    selectedDrop = null;
                }
            }
        }
        persistOwnedDropIndex();
    }

    private void persistOwnedDropIndex() {
        if (ownedDrops.isEmpty()) {
            worker.getPersistentData().remove(PERSISTENT_DROP_INDEX_TAG);
            return;
        }
        ListTag rows = new ListTag();
        int written = 0;
        for (Map.Entry<UUID, BlockPos> owned : ownedDrops.entrySet()) {
            if (written++ >= maxTracked) {
                break;
            }
            CompoundTag row = new CompoundTag();
            row.putUUID(DROP_ID_TAG, owned.getKey());
            row.putLong(DROP_POS_TAG, owned.getValue().asLong());
            rows.add(row);
        }
        worker.getPersistentData().put(PERSISTENT_DROP_INDEX_TAG, rows);
    }

    /** QA-only ownership trace; ordinary worlds pay no string-building cost. */
    private void trace(String event, String detail) {
        if (QaTrace.ENABLED) {
            QaTrace.event(worker, event, detail);
        }
    }

    private void traceRemoval(String event, ServerLevel level,
                              Map.Entry<UUID, BlockPos> row) {
        if (!QaTrace.ENABLED) {
            return;
        }
        Entity entity = level.getEntity(row.getKey());
        boolean pending = DeferredItemMaterializationSavedData.get(level)
            .pendingForTarget(row.getKey(), worker.getUUID());
        String state = entity == null ? "missing"
            : entity.getClass().getSimpleName() + ":alive=" + entity.isAlive();
        trace(event, "id=" + row.getKey() + ";last=" + row.getValue()
            + ";entity=" + state + ";pending=" + pending
            + ";chunkLoaded=" + level.hasChunkAt(row.getValue())
            + ";trackedBefore=" + ownedDrops.size());
    }

    private void markOwned(ItemEntity item) {
        item.getPersistentData().putUUID(PERSISTENT_OWNER_TAG, worker.getUUID());
        item.getPersistentData().putInt(PERSISTENT_LEASE_DURATION_TAG,
            OWNERSHIP_LEASE_TICKS);
        item.getPersistentData().putLong(PERSISTENT_LEASE_EXPIRY_TAG,
            item.level().getGameTime() + OWNERSHIP_LEASE_TICKS);
        // Vanilla ItemEntity merging compares this pickup target before it
        // combines stacks. Giving every active session its worker UUID lets
        // the same worker's logs merge, while preventing cross-worker and
        // owned-vs-unowned merges that would otherwise discard one entity's
        // persistent ownership row. The independent expiry hook below clears
        // this target even if the worker/session unloads.
        item.setTarget(worker.getUUID());
    }

    private boolean ownedByWorker(ItemEntity item) {
        return item.getPersistentData().hasUUID(PERSISTENT_OWNER_TAG)
            && worker.getUUID().equals(
                item.getPersistentData().getUUID(PERSISTENT_OWNER_TAG));
    }

    /**
     * Recovery must never renew an already-dead lease. This method is called
     * synchronously by recovery and lookup, before the ItemEntity necessarily
     * receives its first tick after a chunk/entity reload.
     */
    private static boolean activeOwnershipLease(ItemEntity item) {
        if (!item.getPersistentData().hasUUID(PERSISTENT_OWNER_TAG)) {
            return false;
        }
        if (!item.getPersistentData().contains(PERSISTENT_LEASE_EXPIRY_TAG,
                Tag.TAG_LONG)
            && item.getPersistentData().contains(PERSISTENT_LEASE_DURATION_TAG,
                Tag.TAG_INT)) {
            int duration = item.getPersistentData().getInt(
                PERSISTENT_LEASE_DURATION_TAG);
            if (duration > 0 && duration <= OWNERSHIP_LEASE_TICKS) {
                item.getPersistentData().putLong(PERSISTENT_LEASE_EXPIRY_TAG,
                    item.level().getGameTime() + duration);
            }
        }
        if (!item.getPersistentData().contains(PERSISTENT_LEASE_EXPIRY_TAG,
                Tag.TAG_LONG)
            || item.level().getGameTime() >= item.getPersistentData().getLong(
                PERSISTENT_LEASE_EXPIRY_TAG)) {
            clearOwnership(item);
            item.setNoPickUpDelay();
            return false;
        }
        return true;
    }

    private void clearOffhandOwnershipMarker() {
        worker.getPersistentData().remove(PERSISTENT_OFFHAND_OWNERSHIP_TAG);
    }

    private static void clearOwnership(ItemEntity item) {
        item.getPersistentData().remove(PERSISTENT_OWNER_TAG);
        item.getPersistentData().remove(PERSISTENT_LEASE_EXPIRY_TAG);
        item.getPersistentData().remove(PERSISTENT_LEASE_DURATION_TAG);
        item.setTarget(null);
    }

    /**
     * Entity-owned fail-safe for the finite pickup/merge lease. It runs on
     * the ItemEntity itself, so a dead, unloaded or interrupted worker cannot
     * leave a permanently reserved stack behind. A chunk loaded after the
     * deadline clears on its first entity tick.
     */
    public static void expireOwnershipLease(ItemEntity item) {
        if (item.level().isClientSide
            || !item.getPersistentData().hasUUID(PERSISTENT_OWNER_TAG)) {
            return;
        }
        activeOwnershipLease(item);
    }
}
