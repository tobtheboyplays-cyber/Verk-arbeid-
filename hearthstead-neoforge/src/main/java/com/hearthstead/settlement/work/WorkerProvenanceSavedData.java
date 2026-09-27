package com.hearthstead.settlement.work;

import com.hearthstead.settlement.workzone.WorkZone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded, dimension-local authority ledger for physical field work.
 *
 * <p>Items remain the only cargo authority. This ledger records why a tagged
 * physical item may be treated as worker output and which exact workplace may
 * accept it. It never recreates an item. Corrupt, future, cross-dimension or
 * over-cap data quarantines the whole domain and all runtime callers fail
 * closed until an operator repairs the save.
 */
public final class WorkerProvenanceSavedData extends SavedData {
    public static final int DATA_VERSION = 1;
    public static final int MAX_ACTIONS = 512;
    public static final int MAX_RECEIPTS = 512;
    public static final int MAX_POSITIONS = 96;
    public static final int MAX_ITEM_ROWS = 16;
    private static final String DATA_NAME = "hearthstead_worker_provenance";

    public enum Kind {
        LUMBER_TREE,
        FARM_PLANT,
        FARM_HARVEST
    }

    public enum Phase {
        ACTIVE,
        INPUT_HELD,
        WORK_COMMITTED,
        OUTPUT_COMMITTED,
        /** Physical output is unavailable; preserve all production/deposit evidence. */
        OUTPUT_UNAVAILABLE,
        QUARANTINED,
        RETIRED;

        public boolean workTerminal() {
            return this == WORK_COMMITTED || this == OUTPUT_COMMITTED
                || this == OUTPUT_UNAVAILABLE;
        }
    }

    public record ActionView(UUID id, Kind kind, Phase phase, UUID workerId,
                             WorkZone zone, BlockPos source,
                             List<BlockPos> planned,
                             Set<BlockPos> resolved,
                             @Nullable BlockPos pendingOperation,
                             boolean pendingToolApplied,
                             int appliedToolDamage,
                             @Nullable ResourceLocation inputItem,
                             int inputCount,
                             Map<ResourceLocation, Integer> produced,
                             Map<ResourceLocation, Integer> deposited,
                             long createdTick, long updatedTick) {
        public ActionView {
            planned = List.copyOf(planned);
            resolved = Set.copyOf(resolved);
            produced = Map.copyOf(produced);
            deposited = Map.copyOf(deposited);
        }

        public int remaining(ResourceLocation item) {
            return Math.max(0, produced.getOrDefault(item, 0)
                - deposited.getOrDefault(item, 0));
        }

        public boolean workTerminal() {
            return phase.workTerminal();
        }
    }

    public record DepositReceipt(UUID id, UUID actionId, UUID settlementId,
                                 UUID buildingId, UUID workerId,
                                 ResourceLocation dimension,
                                 BlockPos containerPos,
                                 ResourceLocation itemId, int count,
                                 int destinationBefore, int destinationAfter,
                                 long committedTick) {
        public DepositReceipt {
            if (!valid(id) || !valid(actionId) || !valid(settlementId)
                || !valid(buildingId) || !valid(workerId) || dimension == null
                || containerPos == null || itemId == null || count <= 0
                || destinationBefore < 0
                || destinationAfter - destinationBefore != count
                || committedTick < 0L) {
                throw new IllegalArgumentException("Malformed deposit receipt");
            }
            containerPos = containerPos.immutable();
        }
    }

    static final class Action {
        final UUID id;
        final Kind kind;
        Phase phase;
        final UUID workerId;
        final WorkZone zone;
        final BlockPos source;
        final List<BlockPos> planned = new ArrayList<>();
        final Set<BlockPos> resolved = new LinkedHashSet<>();
        @Nullable BlockPos pendingOperation;
        boolean pendingToolApplied;
        @Nullable ResourceLocation pendingToolItem;
        int pendingToolBefore;
        int pendingToolAfter;
        int appliedToolDamage;
        @Nullable ResourceLocation inputItem;
        int inputCount;
        final Map<ResourceLocation, Integer> produced = new LinkedHashMap<>();
        final Map<ResourceLocation, Integer> deposited = new LinkedHashMap<>();
        final long createdTick;
        long updatedTick;

        Action(UUID id, Kind kind, Phase phase, UUID workerId, WorkZone zone,
               BlockPos source, List<BlockPos> planned, long tick) {
            this.id = id;
            this.kind = kind;
            this.phase = phase;
            this.workerId = workerId;
            this.zone = zone;
            this.source = source.immutable();
            for (BlockPos pos : planned) {
                this.planned.add(pos.immutable());
            }
            this.createdTick = tick;
            this.updatedTick = tick;
        }

        ActionView view() {
            return new ActionView(id, kind, phase, workerId, zone, source,
                planned, resolved, pendingOperation, pendingToolApplied,
                appliedToolDamage, inputItem, inputCount, produced, deposited,
                createdTick, updatedTick);
        }

        boolean active() {
            return phase == Phase.ACTIVE || phase == Phase.INPUT_HELD;
        }

        boolean structurallyValid() {
            if (!valid(id) || kind == null || phase == null || !valid(workerId)
                || zone == null || !zone.withinPersistentLimits()
                || source == null || createdTick < 0L || updatedTick < createdTick
                || planned.size() > MAX_POSITIONS
                || resolved.size() > planned.size()
                || appliedToolDamage < 0 || inputCount < 0
                || produced.size() > MAX_ITEM_ROWS
                || deposited.size() > MAX_ITEM_ROWS) {
                return false;
            }
            if (kind == Kind.LUMBER_TREE
                && (zone.type() != WorkZone.Type.LUMBER || planned.isEmpty())) {
                return false;
            }
            if ((kind == Kind.FARM_PLANT || kind == Kind.FARM_HARVEST)
                && zone.type() != WorkZone.Type.FARM) {
                return false;
            }
            if (kind == Kind.FARM_HARVEST && planned.size() != 1) {
                return false;
            }
            if (kind == Kind.FARM_PLANT && planned.size() > 1) {
                return false;
            }
            LinkedHashSet<BlockPos> unique = new LinkedHashSet<>(planned);
            if (unique.size() != planned.size() || !unique.containsAll(resolved)) {
                return false;
            }
            for (BlockPos pos : planned) {
                if (!zone.contains(pos)) {
                    return false;
                }
            }
            if (pendingOperation != null && (!planned.contains(pendingOperation)
                || resolved.contains(pendingOperation)
                || pendingToolItem == null || pendingToolBefore < 0
                || pendingToolAfter != pendingToolBefore + 1)) {
                return false;
            }
            if (pendingOperation == null && (pendingToolApplied
                || pendingToolItem != null || pendingToolBefore != 0
                || pendingToolAfter != 0)) {
                return false;
            }
            if (phase.workTerminal()
                && (planned.isEmpty() || resolved.size() != planned.size()
                    || pendingOperation != null
                    || appliedToolDamage != resolved.size())) {
                return false;
            }
            // Retirement is not completed work: retain the original plan and
            // exact resolved subset, and never forget an in-flight tool receipt.
            if (phase == Phase.RETIRED && (kind != Kind.LUMBER_TREE
                || pendingOperation != null || appliedToolDamage != resolved.size())) {
                return false;
            }
            if (kind == Kind.FARM_PLANT
                && (inputItem == null || inputCount != 1)) {
                return false;
            }
            for (Map.Entry<ResourceLocation, Integer> row : produced.entrySet()) {
                int depositedCount = deposited.getOrDefault(row.getKey(), 0);
                if (row.getKey() == null || row.getValue() <= 0
                    || depositedCount < 0 || depositedCount > row.getValue()) {
                    return false;
                }
            }
            for (ResourceLocation item : deposited.keySet()) {
                if (!produced.containsKey(item)) {
                    return false;
                }
            }
            return true;
        }
    }

    private final Map<UUID, Action> actions = new LinkedHashMap<>();
    private final Map<UUID, DepositReceipt> receipts = new LinkedHashMap<>();
    private boolean quarantined;
    private String quarantineReason = "none";
    @Nullable private ResourceLocation loadedDimension;

    private static final Factory<WorkerProvenanceSavedData> FACTORY =
        new Factory<>(WorkerProvenanceSavedData::new,
            WorkerProvenanceSavedData::load, null);

    public static WorkerProvenanceSavedData get(ServerLevel level) {
        WorkerProvenanceSavedData data = level.getDataStorage()
            .computeIfAbsent(FACTORY, DATA_NAME);
        data.bindDimension(level.dimension().location());
        return data;
    }

    /**
     * Returns an already persisted/loaded ledger without creating an empty
     * SavedData entry. Readiness checks use this path so merely opening a
     * screen cannot dirty an otherwise untouched world.
     */
    @Nullable
    public static WorkerProvenanceSavedData existing(ServerLevel level) {
        return level == null ? null
            : level.getDataStorage().get(FACTORY, DATA_NAME);
    }

    /** Pure dimension check; unlike {@link #bindDimension(ResourceLocation)}
     * this never repairs, quarantines or marks the save dirty. */
    public boolean readableIn(ResourceLocation dimension) {
        if (dimension == null || quarantined || loadedDimension == null
            || !loadedDimension.equals(dimension)) {
            return false;
        }
        for (Action action : actions.values()) {
            if (!action.zone.dimension().equals(dimension)) {
                return false;
            }
        }
        for (DepositReceipt receipt : receipts.values()) {
            if (!receipt.dimension().equals(dimension)) {
                return false;
            }
        }
        return true;
    }

    public static WorkerProvenanceSavedData load(CompoundTag tag,
                                                  HolderLookup.Provider registries) {
        WorkerProvenanceSavedData data = new WorkerProvenanceSavedData();
        if (!tag.contains("DataVersion", Tag.TAG_INT)) {
            if (!tag.isEmpty()) {
                data.quarantine("missing_version");
            }
            return data;
        }
        if (tag.getInt("DataVersion") != DATA_VERSION) {
            data.quarantine("unsupported_version");
            return data;
        }
        data.quarantined = tag.getBoolean("Quarantined");
        data.quarantineReason = boundedReason(tag.getString("Reason"));
        if (tag.contains("Dimension", Tag.TAG_STRING)) {
            data.loadedDimension = ResourceLocation.tryParse(
                tag.getString("Dimension"));
            if (data.loadedDimension == null) {
                data.quarantine("bad_dimension");
                return data;
            }
        }
        ListTag actionRows = tag.getList("Actions", Tag.TAG_COMPOUND);
        ListTag receiptRows = tag.getList("Receipts", Tag.TAG_COMPOUND);
        if (actionRows.size() > MAX_ACTIONS || receiptRows.size() > MAX_RECEIPTS) {
            data.quarantine("row_cap_exceeded");
            return data;
        }
        for (int index = 0; index < actionRows.size(); index++) {
            Action action = readAction(actionRows.getCompound(index));
            if (action == null || data.actions.putIfAbsent(action.id, action) != null) {
                data.quarantine("malformed_action");
                return data;
            }
        }
        for (int index = 0; index < receiptRows.size(); index++) {
            DepositReceipt receipt = readReceipt(receiptRows.getCompound(index));
            if (receipt == null || !data.actions.containsKey(receipt.actionId())
                || data.receipts.putIfAbsent(receipt.id(), receipt) != null) {
                data.quarantine("malformed_receipt");
                return data;
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putBoolean("Quarantined", quarantined);
        tag.putString("Reason", boundedReason(quarantineReason));
        if (loadedDimension != null) {
            tag.putString("Dimension", loadedDimension.toString());
        }
        ListTag actionRows = new ListTag();
        for (Action action : actions.values()) {
            actionRows.add(writeAction(action));
        }
        tag.put("Actions", actionRows);
        ListTag receiptRows = new ListTag();
        for (DepositReceipt receipt : receipts.values()) {
            receiptRows.add(writeReceipt(receipt));
        }
        tag.put("Receipts", receiptRows);
        return tag;
    }

    public boolean quarantined() {
        return quarantined;
    }

    public String quarantineReason() {
        return quarantineReason;
    }

    @Nullable
    public ActionView action(UUID id) {
        Action action = id == null ? null : actions.get(id);
        return action == null ? null : action.view();
    }

    @Nullable
    public ActionView activeFor(UUID workerId, Kind kind) {
        if (quarantined || workerId == null || kind == null) {
            return null;
        }
        Action found = null;
        for (Action action : actions.values()) {
            if (action.workerId.equals(workerId) && action.kind == kind
                && action.active()) {
                if (found != null) {
                    quarantine("duplicate_active_worker_action");
                    return null;
                }
                found = action;
            }
        }
        return found == null ? null : found.view();
    }

    /** Close only an obsolete, quiescent Lumber action. No item/world mutation. */
    boolean retireLumber(UUID workerId, @Nullable WorkZone liveZone, long tick) {
        ActionView active = activeFor(workerId, Kind.LUMBER_TREE);
        if (active == null || active.zone().equals(liveZone)) return false;
        Action action = mutable(active.id());
        if (action == null || action.pendingOperation != null
            || action.appliedToolDamage != action.resolved.size()) return false;
        action.phase = Phase.RETIRED;
        changed(action, tick);
        return !quarantined;
    }

    /** Caller proves loaded-world absence. No fabricated deposit or replacement output. */
    boolean suspendUnavailableLumberOutput(UUID actionId, UUID workerId, long tick) {
        return suspendUnavailableOutput(actionId, workerId, Kind.LUMBER_TREE, tick);
    }

    boolean suspendUnavailableFarmOutput(UUID actionId, UUID workerId, long tick) {
        return suspendUnavailableOutput(actionId, workerId, Kind.FARM_HARVEST, tick);
    }

    private boolean suspendUnavailableOutput(UUID actionId, UUID workerId, Kind kind, long tick) {
        Action action = mutable(actionId);
        if (action == null || !action.workerId.equals(workerId)
            || action.kind != kind
            || (action.phase != Phase.WORK_COMMITTED && action.phase != Phase.OUTPUT_COMMITTED)
            || action.pendingOperation != null || tick < action.updatedTick
            || tick - action.updatedTick < 600
            || action.produced.entrySet().stream().noneMatch(row ->
                row.getValue() > action.deposited.getOrDefault(row.getKey(), 0))) return false;
        action.phase = Phase.OUTPUT_UNAVAILABLE;
        changed(action, tick);
        return !quarantined;
    }

    @Nullable
    public DepositReceipt receipt(UUID id) {
        return id == null ? null : receipts.get(id);
    }

    public List<DepositReceipt> receiptsFor(UUID actionId) {
        if (actionId == null) {
            return List.of();
        }
        List<DepositReceipt> found = new ArrayList<>();
        for (DepositReceipt receipt : receipts.values()) {
            if (receipt.actionId().equals(actionId)) {
                found.add(receipt);
            }
        }
        return List.copyOf(found);
    }

    /** Bounded immutable rows for readiness; never exposes mutable authority. */
    public List<ActionView> actionsForSettlement(UUID settlementId) {
        if (quarantined || settlementId == null) {
            return List.of();
        }
        List<ActionView> found = new ArrayList<>();
        for (Action action : actions.values()) {
            if (action.zone.settlementId().equals(settlementId)) {
                found.add(action.view());
            }
        }
        return List.copyOf(found);
    }

    @Nullable
    Action mutable(UUID id) {
        return quarantined || id == null ? null : actions.get(id);
    }

    boolean add(Action action) {
        if (quarantined || action == null || !action.structurallyValid()
            || actions.containsKey(action.id)) {
            return false;
        }
        for (Action existing : actions.values()) {
            if (existing.workerId.equals(action.workerId) && existing.active()
                && action.active()) {
                return false;
            }
        }
        pruneForCapacity();
        if (actions.size() >= MAX_ACTIONS) {
            return false;
        }
        actions.put(action.id, action);
        setDirty();
        return true;
    }

    boolean addReceipt(DepositReceipt receipt) {
        if (quarantined || receipt == null || receipts.containsKey(receipt.id())
            || !actions.containsKey(receipt.actionId())) {
            return false;
        }
        while (receipts.size() >= MAX_RECEIPTS) {
            UUID oldest = receipts.keySet().iterator().next();
            receipts.remove(oldest);
        }
        receipts.put(receipt.id(), receipt);
        setDirty();
        return true;
    }

    void changed(Action action, long tick) {
        if (action != null) {
            action.updatedTick = Math.max(action.updatedTick, Math.max(0L, tick));
            if (!action.structurallyValid()) {
                quarantine("runtime_action_invariant");
            }
        }
        setDirty();
    }

    boolean removePending(UUID actionId) {
        Action action = actions.get(actionId);
        if (action != null && action.active() && action.resolved.isEmpty()
            && action.pendingOperation == null && action.produced.isEmpty()) {
            actions.remove(actionId);
            setDirty();
            return true;
        }
        return false;
    }

    // Package-visible so deterministic persistence tests can exercise the
    // exact same level-binding gate as get(ServerLevel), without inventing a
    // second decoder or weakening the production path.
    void bindDimension(ResourceLocation dimension) {
        if (dimension == null) {
            quarantine("null_level_dimension");
            return;
        }
        if (loadedDimension == null) {
            loadedDimension = dimension;
            setDirty();
        } else if (!loadedDimension.equals(dimension)) {
            quarantine("saveddata_dimension_mismatch");
        }
        for (Action action : actions.values()) {
            if (!action.zone.dimension().equals(dimension)) {
                quarantine("action_dimension_mismatch");
                return;
            }
        }
        for (DepositReceipt receipt : receipts.values()) {
            if (!receipt.dimension().equals(dimension)) {
                quarantine("receipt_dimension_mismatch");
                return;
            }
        }
    }

    private void pruneForCapacity() {
        if (actions.size() < MAX_ACTIONS) {
            return;
        }
        var iterator = actions.entrySet().iterator();
        while (actions.size() >= MAX_ACTIONS && iterator.hasNext()) {
            Action action = iterator.next().getValue();
            if ((action.phase == Phase.OUTPUT_COMMITTED || action.phase == Phase.RETIRED)
                && action.produced.entrySet().stream().allMatch(row ->
                    action.deposited.getOrDefault(row.getKey(), 0).equals(row.getValue()))) {
                iterator.remove();
                receipts.entrySet().removeIf(
                    row -> row.getValue().actionId().equals(action.id));
            }
        }
    }

    private void quarantine(String reason) {
        quarantined = true;
        quarantineReason = boundedReason(reason);
        setDirty();
    }

    private static CompoundTag writeAction(Action action) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", action.id);
        tag.putString("Kind", action.kind.name());
        tag.putString("Phase", action.phase.name());
        tag.putUUID("Worker", action.workerId);
        tag.put("Zone", action.zone.writeNbt());
        tag.putLong("Source", action.source.asLong());
        tag.putLongArray("Planned", action.planned.stream()
            .mapToLong(BlockPos::asLong).toArray());
        tag.putLongArray("Resolved", action.resolved.stream()
            .mapToLong(BlockPos::asLong).toArray());
        if (action.pendingOperation != null) {
            tag.putLong("Pending", action.pendingOperation.asLong());
            tag.putBoolean("PendingToolApplied", action.pendingToolApplied);
            tag.putString("PendingTool", action.pendingToolItem.toString());
            tag.putInt("PendingBefore", action.pendingToolBefore);
            tag.putInt("PendingAfter", action.pendingToolAfter);
        }
        tag.putInt("AppliedToolDamage", action.appliedToolDamage);
        if (action.inputItem != null) {
            tag.putString("InputItem", action.inputItem.toString());
            tag.putInt("InputCount", action.inputCount);
        }
        tag.put("Produced", writeCounts(action.produced));
        tag.put("Deposited", writeCounts(action.deposited));
        tag.putLong("CreatedTick", action.createdTick);
        tag.putLong("UpdatedTick", action.updatedTick);
        return tag;
    }

    @Nullable
    private static Action readAction(CompoundTag tag) {
        try {
            if (!tag.hasUUID("Id") || !tag.hasUUID("Worker")
                || !tag.contains("Kind", Tag.TAG_STRING)
                || !tag.contains("Phase", Tag.TAG_STRING)
                || !tag.contains("Zone", Tag.TAG_COMPOUND)
                || !tag.contains("Source", Tag.TAG_LONG)
                || !tag.contains("Planned", Tag.TAG_LONG_ARRAY)
                || !tag.contains("Resolved", Tag.TAG_LONG_ARRAY)
                || !tag.contains("AppliedToolDamage", Tag.TAG_INT)
                || !tag.contains("Produced", Tag.TAG_LIST)
                || !tag.contains("Deposited", Tag.TAG_LIST)
                || !tag.contains("CreatedTick", Tag.TAG_LONG)
                || !tag.contains("UpdatedTick", Tag.TAG_LONG)) {
                return null;
            }
            Kind kind = Kind.valueOf(tag.getString("Kind"));
            Phase phase = Phase.valueOf(tag.getString("Phase"));
            WorkZone zone = WorkZone.readNbt(tag.getCompound("Zone"));
            long[] plannedRaw = tag.getLongArray("Planned");
            long[] resolvedRaw = tag.getLongArray("Resolved");
            if (zone == null || plannedRaw.length > MAX_POSITIONS
                || resolvedRaw.length > plannedRaw.length) {
                return null;
            }
            List<BlockPos> planned = new ArrayList<>(plannedRaw.length);
            for (long raw : plannedRaw) {
                planned.add(BlockPos.of(raw));
            }
            Action action = new Action(tag.getUUID("Id"), kind, phase,
                tag.getUUID("Worker"), zone, BlockPos.of(tag.getLong("Source")),
                planned, tag.getLong("CreatedTick"));
            for (long raw : resolvedRaw) {
                action.resolved.add(BlockPos.of(raw));
            }
            if (tag.contains("Pending", Tag.TAG_LONG)) {
                action.pendingOperation = BlockPos.of(tag.getLong("Pending"));
                action.pendingToolApplied = tag.getBoolean("PendingToolApplied");
                action.pendingToolItem = ResourceLocation.tryParse(
                    tag.getString("PendingTool"));
                action.pendingToolBefore = tag.getInt("PendingBefore");
                action.pendingToolAfter = tag.getInt("PendingAfter");
            }
            action.appliedToolDamage = tag.getInt("AppliedToolDamage");
            if (tag.contains("InputItem", Tag.TAG_STRING)) {
                action.inputItem = ResourceLocation.tryParse(
                    tag.getString("InputItem"));
                action.inputCount = tag.getInt("InputCount");
            }
            Map<ResourceLocation, Integer> produced = readCounts(
                tag.getList("Produced", Tag.TAG_COMPOUND));
            Map<ResourceLocation, Integer> deposited = readCounts(
                tag.getList("Deposited", Tag.TAG_COMPOUND));
            if (produced == null || deposited == null) {
                return null;
            }
            action.produced.putAll(produced);
            action.deposited.putAll(deposited);
            action.updatedTick = tag.getLong("UpdatedTick");
            return action.structurallyValid() ? action : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static CompoundTag writeReceipt(DepositReceipt receipt) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", receipt.id());
        tag.putUUID("Action", receipt.actionId());
        tag.putUUID("Settlement", receipt.settlementId());
        tag.putUUID("Building", receipt.buildingId());
        tag.putUUID("Worker", receipt.workerId());
        tag.putString("Dimension", receipt.dimension().toString());
        tag.putLong("Container", receipt.containerPos().asLong());
        tag.putString("Item", receipt.itemId().toString());
        tag.putInt("Count", receipt.count());
        tag.putInt("Before", receipt.destinationBefore());
        tag.putInt("After", receipt.destinationAfter());
        tag.putLong("Tick", receipt.committedTick());
        return tag;
    }

    @Nullable
    private static DepositReceipt readReceipt(CompoundTag tag) {
        try {
            if (!tag.hasUUID("Id") || !tag.hasUUID("Action")
                || !tag.hasUUID("Settlement") || !tag.hasUUID("Building")
                || !tag.hasUUID("Worker")
                || !tag.contains("Dimension", Tag.TAG_STRING)
                || !tag.contains("Container", Tag.TAG_LONG)
                || !tag.contains("Item", Tag.TAG_STRING)
                || !tag.contains("Count", Tag.TAG_INT)
                || !tag.contains("Before", Tag.TAG_INT)
                || !tag.contains("After", Tag.TAG_INT)
                || !tag.contains("Tick", Tag.TAG_LONG)) {
                return null;
            }
            ResourceLocation dimension = ResourceLocation.tryParse(
                tag.getString("Dimension"));
            ResourceLocation item = ResourceLocation.tryParse(
                tag.getString("Item"));
            return new DepositReceipt(tag.getUUID("Id"), tag.getUUID("Action"),
                tag.getUUID("Settlement"), tag.getUUID("Building"),
                tag.getUUID("Worker"), dimension,
                BlockPos.of(tag.getLong("Container")), item,
                tag.getInt("Count"), tag.getInt("Before"),
                tag.getInt("After"), tag.getLong("Tick"));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static ListTag writeCounts(Map<ResourceLocation, Integer> counts) {
        ListTag rows = new ListTag();
        for (Map.Entry<ResourceLocation, Integer> entry : counts.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putString("Item", entry.getKey().toString());
            row.putInt("Count", entry.getValue());
            rows.add(row);
        }
        return rows;
    }

    @Nullable
    private static Map<ResourceLocation, Integer> readCounts(ListTag rows) {
        if (rows.size() > MAX_ITEM_ROWS) {
            return null;
        }
        Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            CompoundTag row = rows.getCompound(index);
            ResourceLocation item = ResourceLocation.tryParse(
                row.getString("Item"));
            int count = row.getInt("Count");
            if (item == null || count <= 0 || counts.putIfAbsent(item, count) != null) {
                return null;
            }
        }
        return counts;
    }

    static UUID receiptId(UUID actionId, ResourceLocation item,
                          int depositedBefore) {
        String seed = "hearthstead-worker-receipt-v1|" + actionId + "|"
            + item + "|" + depositedBefore;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static String boundedReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "none";
        }
        String safe = reason.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return safe.substring(0, Math.min(64, safe.length()));
    }

    private static boolean valid(UUID id) {
        return id != null && (id.getMostSignificantBits() != 0L
            || id.getLeastSignificantBits() != 0L);
    }
}
