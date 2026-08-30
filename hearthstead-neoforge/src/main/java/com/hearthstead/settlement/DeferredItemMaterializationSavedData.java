package com.hearthstead.settlement;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable, dimension-local ownership for deferred physical item creation.
 *
 * <p>A source is not allowed to clear a quiver, bag slot, world block or paid
 * delivery merely because an {@link ItemEntity} was constructed. The exact
 * serialized stack enters this SavedData first; only then does the caller
 * clear the source. The ledger immediately tries to materialize the stack and
 * retains sole ownership when {@link ServerLevel#addFreshEntity(Entity)} is
 * rejected. A bounded number of loaded entries retry every level tick and
 * across restart; a hard row cap refuses before source mutation.
 *
 * <p>Each pending row uses its own stable UUID as the eventual ItemEntity UUID.
 * That makes a save tear fail closed: if both the physical entity and the
 * pending row survived, the retry observes the already materialized entity
 * instead of spawning a duplicate.
 *
 * <p>Queue and source-clear execute synchronously on the server thread. Calling
 * {@link #setDirty()} does not write during the method, so the ordinary death
 * transaction has no save boundary between those two operations: a process
 * loss before source-clear persists neither in-memory change, while a later
 * save sees the cleared source and queued/materialized authority together.
 * External multi-ledger transactions use the deterministic queue overload so
 * their own persisted action UUID can replay the exact handoff idempotently.
 */
public final class DeferredItemMaterializationSavedData extends SavedData {
    public static final int DATA_VERSION = 1;
    public static final int RETRIES_PER_TICK = 8;
    /** Hard live/save bound: roughly 160 simultaneously full settler bags. */
    public static final int MAX_PENDING_ROWS = 4_096;
    /**
     * No-eviction replay receipts for deterministic external deliveries.
     * They deliberately fail closed at a finite lifetime bound instead of
     * forgetting an old identity and reopening duplicate delivery.
     */
    public static final int MAX_COMPLETED_RECEIPTS = 4_096;
    public static final int MAX_ENTITY_METADATA_CHARS = 8_192;
    public static final int MAX_STACK_NBT_CHARS = 32_768;

    private static final String DATA_NAME =
        "hearthstead_deferred_item_materializations";
    private static final double WORLD_LIMIT = 30_000_000.0D;
    /** Allows ordinary falling/water motion, but not cross-map UUID capture. */
    private static final double MAX_REPLAY_DISPLACEMENT_SQR = 64.0D * 64.0D;
    private static final String REPLAY_PROOF_TAG =
        "HearthsteadDeferredMaterializationProof";

    private static final Factory<DeferredItemMaterializationSavedData> FACTORY =
        new Factory<>(DeferredItemMaterializationSavedData::new,
            DeferredItemMaterializationSavedData::load, null);

    /** Exact result for deterministic, save-tear-safe enqueue operations. */
    public enum QueueResult {
        INSERTED,
        IDEMPOTENT,
        CAPACITY,
        COLLISION,
        INVALID,
        QUARANTINED;

        public boolean accepted() {
            return this == INSERTED || this == IDEMPOTENT;
        }
    }

    /**
     * Optional bounded ItemEntity presentation/lease state. The physical
     * ItemStack remains the authority; this metadata only preserves loader
     * persistent-data tags and vanilla pickup ownership across deferral.
     */
    public record ItemEntityOptions(CompoundTag persistentData, int pickupDelay,
                                    @Nullable UUID target,
                                    boolean extendedLifetime,
                                    boolean retainCompletionReceipt) {
        public static final ItemEntityOptions NONE =
            new ItemEntityOptions(new CompoundTag(), -1, null, false, false);

        /** Source-compatible ordinary transfer: no permanent receipt. */
        public ItemEntityOptions(CompoundTag persistentData, int pickupDelay,
                                 @Nullable UUID target,
                                 boolean extendedLifetime) {
            this(persistentData, pickupDelay, target, extendedLifetime, false);
        }

        public ItemEntityOptions {
            persistentData = persistentData == null
                ? new CompoundTag() : persistentData.copy();
            if (persistentData.toString().length() > MAX_ENTITY_METADATA_CHARS
                || pickupDelay < -1 || pickupDelay > 32_767
                || (target != null && target.equals(new UUID(0L, 0L)))) {
                throw new IllegalArgumentException(
                    "Deferred ItemEntity metadata exceeds its bounded contract");
            }
        }

        @Override
        public CompoundTag persistentData() {
            return persistentData.copy();
        }
    }

    static final class PendingDrop {
        final UUID id;
        final double x;
        final double y;
        final double z;
        final CompoundTag stackTag;
        final ItemEntityOptions options;

        PendingDrop(UUID id, double x, double y, double z,
                    CompoundTag stackTag, ItemEntityOptions options) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.stackTag = stackTag.copy();
            this.options = options == null ? ItemEntityOptions.NONE : options;
        }

        ItemStack stack(HolderLookup.Provider registries) {
            return registries == null ? ItemStack.EMPTY
                : ItemStack.parseOptional(registries, stackTag.copy());
        }

        boolean structurallyValid(HolderLookup.Provider registries) {
            if (id == null || id.equals(new UUID(0L, 0L))
                || !finiteWorldCoordinate(x) || !finiteWorldCoordinate(y)
                || !finiteWorldCoordinate(z) || options == null
                || stackTag.toString().length() > MAX_STACK_NBT_CHARS) {
                return false;
            }
            ItemStack decoded = stack(registries);
            return !decoded.isEmpty() && decoded.getCount() > 0
                && decoded.getCount() <= decoded.getMaxStackSize();
        }
    }

    private final Map<UUID, PendingDrop> pending = new LinkedHashMap<>();
    /** Exact, bounded, no-eviction proofs; never physical item authority. */
    private final Map<UUID, PendingDrop> completed = new LinkedHashMap<>();
    @Nullable private ResourceLocation loadedDimension;
    private boolean quarantined;
    private String quarantineReason = "none";

    public DeferredItemMaterializationSavedData() {
    }

    public static DeferredItemMaterializationSavedData get(ServerLevel level) {
        DeferredItemMaterializationSavedData data = level.getDataStorage()
            .computeIfAbsent(FACTORY, DATA_NAME);
        data.bindDimension(level.dimension().location());
        return data;
    }

    @Nullable
    public static DeferredItemMaterializationSavedData existing(ServerLevel level) {
        return level == null ? null
            : level.getDataStorage().get(FACTORY, DATA_NAME);
    }

    /**
     * Takes durable ownership of one exact stack without materializing it.
     * The caller must clear its source only after this method returns.
     */
    @Nullable
    public UUID queue(ServerLevel level, double x, double y, double z,
                      ItemStack stack) {
        return queue(level, x, y, z, stack, ItemEntityOptions.NONE);
    }

    /** Same durable transfer with bounded ItemEntity lease/persistent tags. */
    @Nullable
    public UUID queue(ServerLevel level, double x, double y, double z,
                      ItemStack stack, ItemEntityOptions options) {
        if (quarantined || pending.size() >= MAX_PENDING_ROWS
            || options != null && options.retainCompletionReceipt()
                && retainedReceiptReservations() >= MAX_COMPLETED_RECEIPTS) {
            return null;
        }
        UUID id;
        do {
            id = UUID.randomUUID();
        } while (pending.containsKey(id) || completed.containsKey(id));
        return queue(level, id, x, y, z, stack, options)
                == QueueResult.INSERTED ? id : null;
    }

    /**
     * Deterministic enqueue for an external sale/offer/action transaction.
     * Replaying the exact same row is idempotent; reusing the identity for any
     * other position, stack or ItemEntity metadata fails closed.
     */
    public QueueResult queue(ServerLevel level, UUID stableId,
                             double x, double y, double z, ItemStack stack,
                             ItemEntityOptions options) {
        if (quarantined) {
            return QueueResult.QUARANTINED;
        }
        if (options != null && options.retainCompletionReceipt()
            && retainedReceiptReservations() >= MAX_COMPLETED_RECEIPTS
            && (stableId == null || !pending.containsKey(stableId)
                && !completed.containsKey(stableId))) {
            return QueueResult.CAPACITY;
        }
        PendingDrop candidate = candidate(level, stableId, x, y, z, stack,
            options);
        if (candidate == null) {
            return QueueResult.INVALID;
        }
        PendingDrop existing = pending.get(stableId);
        if (existing != null) {
            return sameRow(existing, candidate)
                ? QueueResult.IDEMPOTENT : QueueResult.COLLISION;
        }
        PendingDrop receipt = completed.get(stableId);
        if (receipt != null) {
            return sameRow(receipt, candidate)
                ? QueueResult.IDEMPOTENT : QueueResult.COLLISION;
        }
        if (pending.size() >= MAX_PENDING_ROWS) {
            return QueueResult.CAPACITY;
        }
        pending.put(stableId, candidate);
        setDirty();
        return QueueResult.INSERTED;
    }

    public boolean containsExact(ServerLevel level, UUID stableId,
                                 double x, double y, double z, ItemStack stack,
                                 ItemEntityOptions options) {
        if (quarantined) {
            return false;
        }
        PendingDrop candidate = candidate(level, stableId, x, y, z, stack,
            options);
        PendingDrop existing = stableId == null ? null : pending.get(stableId);
        return candidate != null && existing != null
            && sameRow(existing, candidate);
    }

    /**
     * Proves an already materialized exact transfer after its pending row was
     * consumed. This is read-only and deliberately accepts normal bounded
     * ItemEntity movement while requiring the stable UUID, exact stack,
     * replay proof, options, target and required persistent metadata.
     */
    public boolean materializedExact(ServerLevel level, UUID stableId,
                                     double x, double y, double z,
                                     ItemStack stack,
                                     ItemEntityOptions options) {
        if (completedExact(level, stableId, x, y, z, stack, options)) {
            return true;
        }
        PendingDrop expected = candidate(level, stableId, x, y, z, stack,
            options);
        if (expected == null) {
            return false;
        }
        Entity existing = level.getEntity(stableId);
        return existing != null
            && matches(existing, expected, level.registryAccess());
    }

    /**
     * Exact persisted completion proof for an external deterministic handoff.
     * It remains valid while the physical ItemEntity is unloaded or after the
     * intended player picked it up. Receipts are bounded and never evicted.
     */
    public boolean completedExact(ServerLevel level, UUID stableId,
                                  double x, double y, double z,
                                  ItemStack stack, ItemEntityOptions options) {
        if (level == null) {
            return false;
        }
        return completedExact(level.dimension().location(),
            level.registryAccess(), stableId, x, y, z, stack, options);
    }

    /** Package seam for deterministic SavedData/JVM restart regression. */
    boolean completedExact(ResourceLocation dimension,
                           HolderLookup.Provider registries, UUID stableId,
                           double x, double y, double z, ItemStack stack,
                           ItemEntityOptions options) {
        if (quarantined) {
            return false;
        }
        PendingDrop expected = candidate(dimension, registries, stableId,
            x, y, z, stack, options);
        PendingDrop receipt = stableId == null ? null : completed.get(stableId);
        return expected != null && receipt != null
            && sameRow(receipt, expected);
    }

    /** Rollback used only while the source still owns the exact same stack. */
    public boolean cancel(UUID id) {
        if (quarantined || id == null || pending.remove(id) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    /** Immediate/retry materialization. Rejection leaves the saved row intact. */
    public boolean materialize(ServerLevel level, UUID id) {
        PendingDrop row = id == null ? null : pending.get(id);
        if (quarantined || row == null || level == null
            || !level.dimension().location().equals(loadedDimension)) {
            return false;
        }
        BlockPos pos = BlockPos.containing(row.x, row.y, row.z);
        if (!level.isLoaded(pos)) {
            return false;
        }

        Entity existing = level.getEntity(row.id);
        if (existing != null) {
            if (matches(existing, row, level.registryAccess())) {
                return commitMaterialized(row);
            }
            // UUID collision: retain the only owned stack; never replace or
            // reinterpret an unrelated entity.
            return false;
        }

        ItemStack stack = row.stack(level.registryAccess());
        if (stack.isEmpty()) {
            return false;
        }
        ItemEntity physical = new ItemEntity(level, row.x, row.y, row.z, stack);
        physical.setUUID(row.id);
        CompoundTag persistent = row.options.persistentData();
        if (!persistent.isEmpty()) {
            physical.getPersistentData().merge(persistent);
        }
        physical.getPersistentData().put(REPLAY_PROOF_TAG,
            replayProof(row));
        if (row.options.pickupDelay() >= 0) {
            physical.setPickUpDelay(row.options.pickupDelay());
        }
        if (row.options.target() != null) {
            physical.setTarget(row.options.target());
        }
        if (row.options.extendedLifetime()) {
            physical.setExtendedLifetime();
        }
        if (!level.addFreshEntity(physical)) {
            // A save-tear replay may reject the duplicate UUID even though the
            // exact physical row is now loaded. Accept only an exact match.
            existing = level.getEntity(row.id);
            if (existing == null
                || !matches(existing, row, level.registryAccess())) {
                return false;
            }
        }
        return commitMaterialized(row);
    }

    /** Level-tick entry point; does not create empty SavedData. */
    public static void retryLoaded(ServerLevel level) {
        DeferredItemMaterializationSavedData data = existing(level);
        if (data == null) {
            return;
        }
        data.bindDimension(level.dimension().location());
        int attempted = 0;
        for (UUID id : List.copyOf(data.pending.keySet())) {
            if (attempted++ >= RETRIES_PER_TICK) {
                break;
            }
            data.materialize(level, id);
        }
    }

    public int pendingRows() {
        return pending.size();
    }

    public int completedReceipts() {
        return completed.size();
    }

    public boolean quarantined() {
        return quarantined;
    }

    public String quarantineReason() {
        return quarantineReason;
    }

    public int pendingItems(HolderLookup.Provider registries, Item item) {
        if (registries == null || item == null) {
            return 0;
        }
        int total = 0;
        for (PendingDrop row : pending.values()) {
            ItemStack stack = row.stack(registries);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @Override
    public CompoundTag save(CompoundTag tag,
                            HolderLookup.Provider registries) {
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putBoolean("Quarantined", quarantined);
        tag.putString("QuarantineReason", boundedReason(quarantineReason));
        if (loadedDimension != null) {
            tag.putString("Dimension", loadedDimension.toString());
        }
        ListTag rows = new ListTag();
        for (PendingDrop row : pending.values()) {
            rows.add(writeRow(row));
        }
        tag.put("Pending", rows);
        ListTag receipts = new ListTag();
        for (PendingDrop row : completed.values()) {
            receipts.add(writeRow(row));
        }
        tag.put("Completed", receipts);
        return tag;
    }

    public static DeferredItemMaterializationSavedData load(
            CompoundTag tag, HolderLookup.Provider registries) {
        DeferredItemMaterializationSavedData data =
            new DeferredItemMaterializationSavedData();
        if (tag.isEmpty()) {
            return data;
        }
        if (!tag.contains("DataVersion", Tag.TAG_INT)
            || tag.getInt("DataVersion") != DATA_VERSION) {
            data.quarantineLoaded("unsupported_or_missing_version");
            return data;
        }
        data.quarantined = tag.getBoolean("Quarantined");
        data.quarantineReason = boundedReason(
            tag.getString("QuarantineReason"));
        if (tag.contains("Dimension", Tag.TAG_STRING)) {
            data.loadedDimension = ResourceLocation.tryParse(
                tag.getString("Dimension"));
            if (data.loadedDimension == null) {
                data.quarantineLoaded("invalid_dimension");
                return data;
            }
        }
        Tag rawRows = tag.get("Pending");
        if (!(rawRows instanceof ListTag rows)
            || rows.size() > MAX_PENDING_ROWS) {
            data.quarantineLoaded("missing_wrong_or_oversized_rows");
            return data;
        }
        for (int i = 0; i < rows.size(); i++) {
            Tag raw = rows.get(i);
            PendingDrop row = raw instanceof CompoundTag compound
                ? readRow(compound, registries) : null;
            if (row == null || data.pending.putIfAbsent(row.id, row) != null) {
                data.pending.clear();
                data.completed.clear();
                data.quarantineLoaded("malformed_or_duplicate_row");
                return data;
            }
        }
        Tag rawReceipts = tag.get("Completed");
        ListTag receipts;
        if (rawReceipts == null) {
            // Additive v1 compatibility: old ledgers had no receipt list.
            receipts = new ListTag();
        } else if (rawReceipts instanceof ListTag list
                   && list.size() <= MAX_COMPLETED_RECEIPTS) {
            receipts = list;
        } else {
            data.pending.clear();
            data.completed.clear();
            data.quarantineLoaded("wrong_or_oversized_completed_receipts");
            return data;
        }
        for (int i = 0; i < receipts.size(); i++) {
            Tag raw = receipts.get(i);
            PendingDrop receipt = raw instanceof CompoundTag compound
                ? readRow(compound, registries) : null;
            if (receipt == null
                || !receipt.options.retainCompletionReceipt()
                || data.pending.containsKey(receipt.id)
                || data.completed.putIfAbsent(receipt.id, receipt) != null) {
                data.pending.clear();
                data.completed.clear();
                data.quarantineLoaded(
                    "malformed_or_duplicate_completed_receipt");
                return data;
            }
        }
        if (data.retainedReceiptReservations() > MAX_COMPLETED_RECEIPTS) {
            data.pending.clear();
            data.completed.clear();
            data.quarantineLoaded("oversubscribed_receipt_reservations");
            return data;
        }
        if ((!data.pending.isEmpty() || !data.completed.isEmpty())
            && data.loadedDimension == null) {
            data.pending.clear();
            data.completed.clear();
            data.quarantineLoaded("rows_without_dimension");
        }
        return data;
    }

    private void bindDimension(ResourceLocation dimension) {
        if (dimension == null || quarantined) {
            return;
        }
        if (loadedDimension == null) {
            loadedDimension = dimension;
            setDirty();
        } else if (!loadedDimension.equals(dimension)) {
            Hearthstead.LOGGER.error(
                "Deferred item ledger belongs to {}, not {}; quarantining all rows",
                loadedDimension, dimension);
            quarantined = true;
            quarantineReason = "dimension_mismatch";
            setDirty();
        }
    }

    private boolean commitMaterialized(PendingDrop row) {
        if (row == null || pending.get(row.id) != row) {
            return false;
        }
        if (row.options.retainCompletionReceipt()) {
            PendingDrop existing = completed.get(row.id);
            if (existing != null && !sameRow(existing, row)) {
                quarantineRuntime("completion_receipt_collision");
                return false;
            }
            if (existing == null
                && retainedReceiptReservations()
                    > MAX_COMPLETED_RECEIPTS) {
                quarantineRuntime("completion_receipt_capacity_invariant");
                return false;
            }
            // Same SavedData mutation: the proof is installed before the
            // pending authority is removed; no save callback can interleave.
            completed.putIfAbsent(row.id, row);
        }
        pending.remove(row.id);
        setDirty();
        return true;
    }

    private static boolean matches(Entity entity, PendingDrop row,
                                   HolderLookup.Provider registries) {
        if (!(entity instanceof ItemEntity item)) {
            return false;
        }
        ItemStack expected = row.stack(registries);
        ItemStack actual = item.getItem();
        double dx = item.getX() - row.x;
        double dy = item.getY() - row.y;
        double dz = item.getZ() - row.z;
        if (expected.isEmpty() || !item.isAlive()
            || dx * dx + dy * dy + dz * dz > MAX_REPLAY_DISPLACEMENT_SQR
            || actual.getCount() != expected.getCount()
            || !ItemStack.isSameItemSameComponents(actual, expected)
            || !Objects.equals(item.getTarget(), row.options.target())) {
            return false;
        }
        if (row.options.pickupDelay() > 0 && !item.hasPickUpDelay()) {
            return false;
        }
        if (row.options.pickupDelay() == 0 && item.hasPickUpDelay()) {
            return false;
        }
        CompoundTag liveData = item.getPersistentData();
        if (!(liveData.get(REPLAY_PROOF_TAG) instanceof CompoundTag proof)
            || !proof.equals(replayProof(row))) {
            return false;
        }
        CompoundTag required = row.options.persistentData();
        for (String key : required.getAllKeys()) {
            if (!Objects.equals(required.get(key), liveData.get(key))) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private PendingDrop candidate(ServerLevel level, UUID id,
                                  double x, double y, double z,
                                  ItemStack stack, ItemEntityOptions options) {
        return level == null ? null : candidate(level.dimension().location(),
            level.registryAccess(), id, x, y, z, stack, options);
    }

    @Nullable
    private PendingDrop candidate(ResourceLocation dimension,
                                  HolderLookup.Provider registries, UUID id,
                                  double x, double y, double z,
                                  ItemStack stack, ItemEntityOptions options) {
        if (dimension == null || registries == null || id == null
            || id.equals(new UUID(0L, 0L))
            || stack == null || stack.isEmpty() || options == null
            || !finiteWorldCoordinate(x) || !finiteWorldCoordinate(y)
            || !finiteWorldCoordinate(z)
            || (loadedDimension != null
                && !loadedDimension.equals(dimension))) {
            return null;
        }
        try {
            Tag encoded = stack.copy().saveOptional(registries);
            if (!(encoded instanceof CompoundTag stackTag)) {
                return null;
            }
            PendingDrop row = new PendingDrop(id, x, y, z, stackTag, options);
            return row.structurallyValid(registries) ? row : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static boolean sameRow(PendingDrop left, PendingDrop right) {
        return left.id.equals(right.id)
            && Double.doubleToLongBits(left.x) == Double.doubleToLongBits(right.x)
            && Double.doubleToLongBits(left.y) == Double.doubleToLongBits(right.y)
            && Double.doubleToLongBits(left.z) == Double.doubleToLongBits(right.z)
            && left.stackTag.equals(right.stackTag)
            && left.options.equals(right.options);
    }

    private static CompoundTag replayProof(PendingDrop row) {
        CompoundTag proof = new CompoundTag();
        proof.putUUID("Id", row.id);
        proof.put("Options", writeOptions(row.options));
        return proof;
    }

    private static CompoundTag writeOptions(ItemEntityOptions options) {
        CompoundTag tag = new CompoundTag();
        CompoundTag persistent = options.persistentData();
        if (!persistent.isEmpty()) {
            tag.put("PersistentData", persistent);
        }
        tag.putInt("PickupDelay", options.pickupDelay());
        if (options.target() != null) {
            tag.putUUID("Target", options.target());
        }
        tag.putBoolean("ExtendedLifetime", options.extendedLifetime());
        if (options.retainCompletionReceipt()) {
            tag.putBoolean("RetainCompletionReceipt", true);
        }
        return tag;
    }

    private static CompoundTag writeRow(PendingDrop row) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", row.id);
        tag.putDouble("X", row.x);
        tag.putDouble("Y", row.y);
        tag.putDouble("Z", row.z);
        tag.put("Stack", row.stackTag.copy());
        CompoundTag persistent = row.options.persistentData();
        if (!persistent.isEmpty()) {
            tag.put("PersistentData", persistent);
        }
        if (row.options.pickupDelay() >= 0) {
            tag.putInt("PickupDelay", row.options.pickupDelay());
        }
        if (row.options.target() != null) {
            tag.putUUID("Target", row.options.target());
        }
        if (row.options.extendedLifetime()) {
            tag.putBoolean("ExtendedLifetime", true);
        }
        if (row.options.retainCompletionReceipt()) {
            tag.putBoolean("RetainCompletionReceipt", true);
        }
        return tag;
    }

    @Nullable
    private static PendingDrop readRow(CompoundTag tag,
                                       HolderLookup.Provider registries) {
        if (!tag.hasUUID("Id") || !tag.contains("X", Tag.TAG_DOUBLE)
            || !tag.contains("Y", Tag.TAG_DOUBLE)
            || !tag.contains("Z", Tag.TAG_DOUBLE)
            || !(tag.get("Stack") instanceof CompoundTag stackTag)) {
            return null;
        }
        try {
            CompoundTag persistent = tag.get("PersistentData")
                    instanceof CompoundTag metadata
                ? metadata : new CompoundTag();
            int pickupDelay = tag.contains("PickupDelay", Tag.TAG_INT)
                ? tag.getInt("PickupDelay") : -1;
            UUID target = tag.hasUUID("Target") ? tag.getUUID("Target") : null;
            PendingDrop row = new PendingDrop(tag.getUUID("Id"),
                tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"),
                stackTag, new ItemEntityOptions(persistent, pickupDelay, target,
                    tag.getBoolean("ExtendedLifetime"),
                    tag.getBoolean("RetainCompletionReceipt")));
            return row.structurallyValid(registries) ? row : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static boolean finiteWorldCoordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= WORLD_LIMIT;
    }

    /** Completed rows plus pending rows that already reserved a receipt slot. */
    private int retainedReceiptReservations() {
        int reserved = completed.size();
        for (PendingDrop row : pending.values()) {
            if (row.options.retainCompletionReceipt()) {
                reserved++;
            }
        }
        return reserved;
    }

    private void quarantineLoaded(String reason) {
        quarantined = true;
        quarantineReason = boundedReason(reason);
    }

    private void quarantineRuntime(String reason) {
        quarantined = true;
        quarantineReason = boundedReason(reason);
        setDirty();
        Hearthstead.LOGGER.error(
            "Deferred item materialization quarantined at runtime: {}",
            quarantineReason);
    }

    private static String boundedReason(String reason) {
        String safe = reason == null || reason.isBlank() ? "none" : reason;
        return safe.length() <= 96 ? safe : safe.substring(0, 96);
    }
}
