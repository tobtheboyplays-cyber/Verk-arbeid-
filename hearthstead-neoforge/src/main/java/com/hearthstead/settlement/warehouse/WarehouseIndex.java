package com.hearthstead.settlement.warehouse;

import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Cached, incrementally maintained view of the containers a building
 * manages. Chests are the truth (D-A2a-2): this class never holds items and
 * is never the basis for a transfer decision without re-reading the
 * container it points at.
 *
 * <h2>Managed vs. not managed</h2>
 * A building manages at most {@link #capacityFor} containers: the warehouse
 * level capacity (16 to 256, {@link WarehouseLevels}) or
 * {@link WarehouseLevels#WORKPLACE_CAPACITY} for every other building.
 * Containers beyond that are <em>not managed (warehouse full)</em>: workers
 * ignore them and the UI lists them, but nothing fails, and nothing blocks
 * readiness. The managed subset is deterministic: player PRIORITY marks
 * first, then nearest to the plaque, ties in the old y/x/z scan order.
 * {@link #containers} returns it in that scan order, so "the first chest
 * with room" is unchanged whenever nothing overflows.
 *
 * <h2>Incremental</h2>
 * One full walk of the bounds builds an entry. After that the entry is kept
 * current by {@link #onBlockChanged} (every block change with neighbour
 * updates inside the bounds, from {@code BlockEvent.NeighborNotifyEvent})
 * and by a slow revalidation sweep of {@link #SWEEP_BUDGET_PER_TICK}
 * positions per server tick, which catches the rare flag-2 edits that fire
 * no event. Contents are read on demand only. Nothing here ever loads a
 * chunk: positions in unloaded chunks are skipped, and an entry built while
 * part of the room was unloaded is marked incomplete and rebuilt once the
 * whole room is loaded.
 */
public final class WarehouseIndex {

    /** Positions revalidated per server tick, across every cached entry. */
    public static final int SWEEP_BUDGET_PER_TICK = 256;
    /** Entries nobody asked for in this many ticks are dropped. */
    private static final long EVICT_AFTER_TICKS = 12_000L;
    /** A burst of edits bigger than this simply triggers one rebuild. */
    private static final int MAX_DIRTY = 4096;

    private static final Map<ServerLevel, LevelIndex> LEVELS = new WeakHashMap<>();

    private WarehouseIndex() {
    }

    // ------------------------------------------------------------ public ---

    /**
     * The managed containers whose chunks are loaded, in y/x/z scan order.
     * A fresh mutable list, like before. Cheap after the first call.
     */
    public static List<BlockPos> containers(ServerLevel level, Building building) {
        Entry entry = current(level, building);
        if (entry == null) {
            return new ArrayList<>();
        }
        List<BlockPos> out = new ArrayList<>(entry.managed.length);
        for (long packed : entry.managed) {
            BlockPos pos = BlockPos.of(packed);
            if (level.hasChunkAt(pos)) {
                out.add(pos);
            }
        }
        return out;
    }

    /** Full picture for UI, readiness and QA. */
    public record View(List<BlockPos> managed, List<BlockPos> unmanaged,
                       int capacity, int level, boolean complete) {
        public int known() {
            return managed.size() + unmanaged.size();
        }
    }

    public static View view(ServerLevel level, Building building) {
        Entry entry = current(level, building);
        if (entry == null) {
            return new View(List.of(), List.of(), capacityFor(building),
                effectiveLevel(building), false);
        }
        List<BlockPos> managed = new ArrayList<>(entry.managed.length);
        LongOpenHashSet managedSet = new LongOpenHashSet(entry.managed);
        for (long packed : entry.managed) {
            managed.add(BlockPos.of(packed));
        }
        long[] rest = new long[entry.known.size() - managedSet.size()];
        int r = 0;
        for (LongIterator it = entry.known.iterator(); it.hasNext(); ) {
            long packed = it.nextLong();
            if (!managedSet.contains(packed) && r < rest.length) {
                rest[r++] = packed;
            }
        }
        WarehouseLevels.sortScanOrder(rest);
        List<BlockPos> unmanaged = new ArrayList<>(rest.length);
        for (long packed : rest) {
            unmanaged.add(BlockPos.of(packed));
        }
        return new View(List.copyOf(managed), List.copyOf(unmanaged),
            entry.capacity, effectiveLevel(building), entry.complete);
    }

    /** Every container known inside the bounds, managed or not. */
    public static int knownCount(ServerLevel level, Building building) {
        Entry entry = current(level, building);
        return entry == null ? 0 : entry.known.size();
    }

    /** Throws the cached entry away and walks the bounds again right now. */
    public static void rescan(ServerLevel level, Building building) {
        Entry entry = current(level, building);
        if (entry != null && enabled()) {
            rebuild(level, building, entry);
            applySelection(building, entry);
        }
    }

    /** Full rebuilds of this building's entry so far (GameTest evidence). */
    public static int rebuildCount(ServerLevel level, Building building) {
        LevelIndex index = LEVELS.get(level);
        Entry entry = index == null || building == null ? null
            : index.entries.get(building.id);
        return entry == null ? 0 : entry.rebuilds;
    }

    /** Managed-container capacity for this building right now. */
    public static int capacityFor(Building building) {
        if (building == null) {
            return 0;
        }
        return building.type == BuildingType.WAREHOUSE && enabled()
            ? WarehouseLevels.capacity(effectiveLevel(building))
            : WarehouseLevels.WORKPLACE_CAPACITY;
    }

    /**
     * Effective warehouse level: room checklist level ({@code building.level},
     * written by the plaque scan), capped by the settlement's Logistics tree
     * ({@code warehouseTechMax}, synced by WarehouseLevelService), never below
     * the grandfather floor. An unresolved old save counts as L3 until its
     * first complete count, so it can never shrink in the meantime.
     */
    public static int effectiveLevel(Building building) {
        if (building == null) {
            return WarehouseLevels.MIN_LEVEL;
        }
        int techMax = building.warehouseTechMax > 0
            ? building.warehouseTechMax : WarehouseLevels.BASE_TECH_LEVEL;
        int floor = building.warehouseLevelFloor == Building.WAREHOUSE_FLOOR_PENDING
            ? WarehouseLevels.GRANDFATHER_MAX_LEVEL
            : building.warehouseLevelFloor;
        return WarehouseLevels.effectiveLevel(building.level, techMax, floor);
    }

    /**
     * A storage screen reads only a complete loaded room. It never turns a
     * player's UI refresh into a chunk request, and it never calls a partial
     * room complete simply because its visible chests happen to be empty.
     */
    public static boolean fullyLoaded(ServerLevel level, Building building) {
        if (level == null || building == null || building.bounds == null) {
            return false;
        }
        BoundingBox bounds = building.bounds;
        for (int chunkX = bounds.minX() >> 4; chunkX <= bounds.maxX() >> 4; chunkX++) {
            for (int chunkZ = bounds.minZ() >> 4; chunkZ <= bounds.maxZ() >> 4; chunkZ++) {
                if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Kill-switch ({@code [features] warehouseLevels}, owner rule for the
     * Sunday test). When false the index behaves like before this lane:
     * a fresh bounded walk on every query, the first 64 containers in scan
     * order, no levels, no grandfather writes, no cache. Nothing is ever
     * moved or deleted by switching, so every stored item stays where it is.
     */
    public static boolean enabled() {
        return com.hearthstead.HearthsteadServerConfig.warehouseLevelsEnabled();
    }

    /** What counts as a storage container for this building type. */
    public static boolean isContainer(@Nullable BlockEntity be, BuildingType type) {
        return (be instanceof ChestBlockEntity && type != BuildingType.FISHERY)
            || be instanceof BarrelBlockEntity
            || be instanceof com.hearthstead.block.FishRackBlockEntity;
    }

    /** Drops one building's entry, e.g. when its plaque is removed. */
    public static void forget(UUID buildingId) {
        for (LevelIndex index : LEVELS.values()) {
            Entry entry = index.entries.remove(buildingId);
            if (entry != null) {
                index.unregister(entry);
            }
        }
    }

    /** Drops every cached entry (world unload). */
    public static void clearAll() {
        LEVELS.clear();
    }

    // ------------------------------------------------------------ events ---

    /**
     * A block changed. O(1) when nothing is indexed near it: one chunk-key
     * lookup. Positions inside an entry's bounds are only marked dirty; the
     * block entity is read on the next query.
     */
    public static void onBlockChanged(ServerLevel level, BlockPos pos) {
        LevelIndex index = LEVELS.get(level);
        if (index == null || index.byChunk.isEmpty()) {
            return;
        }
        if (!enabled()) {
            // Events are not tracked while disabled; drop the cache so a
            // later re-enable starts from a fresh walk, never a stale one.
            LEVELS.remove(level);
            return;
        }
        List<Entry> near = index.byChunk.get(
            ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (near == null) {
            return;
        }
        for (Entry entry : near) {
            if (entry.bounds.isInside(pos)) {
                if (entry.dirty.size() >= MAX_DIRTY) {
                    entry.forceRebuild = true;
                    entry.dirty.clear();
                } else if (!entry.forceRebuild) {
                    entry.dirty.add(pos.asLong());
                }
            }
        }
    }

    /**
     * Slow background revalidation: {@link #SWEEP_BUDGET_PER_TICK} positions
     * per tick, one entry per tick round-robin. Skips unloaded chunks.
     */
    public static void tick(ServerLevel level) {
        LevelIndex index = LEVELS.get(level);
        if (index == null || index.entries.isEmpty()) {
            return;
        }
        if (!enabled()) {
            LEVELS.remove(level);
            return;
        }
        long now = level.getGameTime();
        if (now % 200L == 0L) {
            Iterator<Entry> it = index.entries.values().iterator();
            while (it.hasNext()) {
                Entry entry = it.next();
                if (Math.abs(now - entry.lastAccess) > EVICT_AFTER_TICKS) {
                    it.remove();
                    index.unregister(entry);
                }
            }
            if (index.entries.isEmpty()) {
                return;
            }
        }
        List<Entry> all = new ArrayList<>(index.entries.values());
        Entry entry = all.get(Math.floorMod(index.sweepTurn++, all.size()));
        sweep(level, entry, SWEEP_BUDGET_PER_TICK);
    }

    // ---------------------------------------------------------- internals --

    private static final class LevelIndex {
        final Map<UUID, Entry> entries = new HashMap<>();
        final Long2ObjectOpenHashMap<List<Entry>> byChunk = new Long2ObjectOpenHashMap<>();
        int sweepTurn;

        void register(Entry entry) {
            BoundingBox b = entry.bounds;
            for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++) {
                for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
                    byChunk.computeIfAbsent(ChunkPos.asLong(cx, cz),
                        ignored -> new ArrayList<>(2)).add(entry);
                }
            }
        }

        void unregister(Entry entry) {
            BoundingBox b = entry.bounds;
            if (b == null) {
                return;
            }
            for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++) {
                for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
                    long key = ChunkPos.asLong(cx, cz);
                    List<Entry> list = byChunk.get(key);
                    if (list != null) {
                        list.remove(entry);
                        if (list.isEmpty()) {
                            byChunk.remove(key);
                        }
                    }
                }
            }
        }
    }

    private static final class Entry {
        BoundingBox bounds;
        BuildingType type;
        final LongOpenHashSet known = new LongOpenHashSet();
        final LongOpenHashSet dirty = new LongOpenHashSet();
        long[] managed = new long[0];
        boolean complete;
        boolean forceRebuild = true;
        boolean selectionDirty = true;
        int capacity = -1;
        long anchor;
        int marksHash;
        long sweepCursor;
        long lastAccess;
        int rebuilds;
        long lastRebuildTick;
    }

    /** The entry for this building, brought up to date. Null = no bounds. */
    @Nullable
    private static Entry current(ServerLevel level, Building building) {
        if (level == null || building == null || building.bounds == null
            || building.id == null) {
            return null;
        }
        if (!enabled()) {
            return legacy(level, building);
        }
        LevelIndex index = LEVELS.computeIfAbsent(level, ignored -> new LevelIndex());
        Entry entry = index.entries.get(building.id);
        if (entry == null) {
            entry = new Entry();
            index.entries.put(building.id, entry);
        }
        entry.lastAccess = level.getGameTime();
        if (entry.bounds == null || !sameBox(entry.bounds, building.bounds)) {
            if (entry.bounds != null) {
                index.unregister(entry);
            }
            BoundingBox src = building.bounds;
            // Own copy: BoundingBox has mutators (encapsulate/move).
            entry.bounds = new BoundingBox(src.minX(), src.minY(), src.minZ(),
                src.maxX(), src.maxY(), src.maxZ());
            index.register(entry);
            entry.forceRebuild = true;
        }
        if (entry.type != building.type) {
            entry.type = building.type;
            entry.forceRebuild = true;
        }
        if (entry.forceRebuild
            || (!entry.complete
                && Math.abs(entry.lastAccess - entry.lastRebuildTick) >= 20L
                && fullyLoaded(level, building))) {
            rebuild(level, building, entry);
        } else if (!entry.dirty.isEmpty()) {
            applyDirty(level, entry);
        }
        dropVanished(level, entry);
        resolveGrandfather(level, building, entry);
        applySelection(building, entry);
        return entry;
    }

    /** Kill-switch path: an uncached walk, first 64 in scan order (pre-level behaviour). */
    private static Entry legacy(ServerLevel level, Building building) {
        Entry entry = new Entry();
        BoundingBox src = building.bounds;
        entry.bounds = new BoundingBox(src.minX(), src.minY(), src.minZ(),
            src.maxX(), src.maxY(), src.maxZ());
        entry.type = building.type;
        rebuild(level, building, entry);
        long[] all = entry.known.toLongArray();
        WarehouseLevels.sortScanOrder(all);
        entry.managed = java.util.Arrays.copyOf(all,
            Math.min(all.length, WarehouseLevels.WORKPLACE_CAPACITY));
        entry.capacity = WarehouseLevels.WORKPLACE_CAPACITY;
        entry.selectionDirty = false;
        return entry;
    }

    private static void rebuild(ServerLevel level, Building building, Entry entry) {
        entry.known.clear();
        entry.dirty.clear();
        entry.forceRebuild = false;
        entry.complete = true;
        entry.selectionDirty = true;
        entry.rebuilds++;
        entry.lastRebuildTick = level.getGameTime();
        BoundingBox b = entry.bounds;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = b.minY(); y <= b.maxY(); y++) {
            for (int x = b.minX(); x <= b.maxX(); x++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    cursor.set(x, y, z);
                    if (!level.hasChunkAt(cursor)) {
                        entry.complete = false;
                        continue;
                    }
                    if (isContainer(level.getBlockEntity(cursor), building.type)) {
                        entry.known.add(cursor.asLong());
                    }
                }
            }
        }
    }

    private static void applyDirty(ServerLevel level, Entry entry) {
        for (LongIterator it = entry.dirty.iterator(); it.hasNext(); ) {
            long packed = it.nextLong();
            BlockPos pos = BlockPos.of(packed);
            if (level.hasChunkAt(pos)) {
                recheck(level, entry, pos, packed);
            }
        }
        entry.dirty.clear();
    }

    /** A managed position that no longer holds a container is dropped at once. */
    private static void dropVanished(ServerLevel level, Entry entry) {
        for (long packed : entry.managed) {
            BlockPos pos = BlockPos.of(packed);
            if (level.hasChunkAt(pos)
                && !isContainer(level.getBlockEntity(pos), entry.type)
                && entry.known.remove(packed)) {
                entry.selectionDirty = true;
            }
        }
    }

    private static void recheck(ServerLevel level, Entry entry, BlockPos pos, long packed) {
        boolean container = isContainer(level.getBlockEntity(pos), entry.type);
        boolean changed = container ? entry.known.add(packed) : entry.known.remove(packed);
        if (changed) {
            entry.selectionDirty = true;
        }
    }

    private static void sweep(ServerLevel level, Entry entry, int budget) {
        BoundingBox b = entry.bounds;
        if (b == null || entry.forceRebuild) {
            return;
        }
        long sx = (long) b.getXSpan();
        long sz = (long) b.getZSpan();
        long volume = sx * sz * (long) b.getYSpan();
        if (volume <= 0L) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < budget && i < volume; i++) {
            long idx = Math.floorMod(entry.sweepCursor++, volume);
            int y = b.minY() + (int) (idx / (sx * sz));
            long rest = idx % (sx * sz);
            int x = b.minX() + (int) (rest / sz);
            int z = b.minZ() + (int) (rest % sz);
            cursor.set(x, y, z);
            if (level.hasChunkAt(cursor)) {
                recheck(level, entry, cursor, cursor.asLong());
            }
        }
    }

    /**
     * Old saves (floor still pending) get the level covering what they
     * already have, up to L3, from the first COMPLETE count only.
     */
    private static void resolveGrandfather(ServerLevel level, Building building, Entry entry) {
        if (building.warehouseLevelFloor != Building.WAREHOUSE_FLOOR_PENDING
            || !entry.complete) {
            return;
        }
        if (building.type != BuildingType.WAREHOUSE) {
            // Nothing to protect; settle the flag so it persists as 1.
            building.warehouseLevelFloor = WarehouseLevels.MIN_LEVEL;
        } else {
            building.warehouseLevelFloor =
                WarehouseLevels.grandfatherLevel(entry.known.size());
        }
        entry.selectionDirty = true;
        com.hearthstead.settlement.SettlementSavedData.get(level).setDirty();
    }

    private static void applySelection(Building building, Entry entry) {
        int capacity = capacityFor(building);
        long anchor = (building.plaquePos != null ? building.plaquePos
            : entry.bounds.getCenter()).asLong();
        int marksHash = building.containerMarks.hashCode();
        if (!entry.selectionDirty && capacity == entry.capacity
            && anchor == entry.anchor && marksHash == entry.marksHash) {
            return;
        }
        Set<Long> priority = new HashSet<>();
        Set<Long> excluded = new HashSet<>();
        for (Map.Entry<Long, Byte> mark : building.containerMarks.entrySet()) {
            if (mark.getValue() == WarehouseLevels.MARK_PRIORITY) {
                priority.add(mark.getKey());
            } else if (mark.getValue() == WarehouseLevels.MARK_EXCLUDED) {
                excluded.add(mark.getKey());
            }
        }
        List<Long> candidates = new ArrayList<>(entry.known.size());
        for (LongIterator it = entry.known.iterator(); it.hasNext(); ) {
            candidates.add(it.nextLong());
        }
        entry.managed = WarehouseLevels.selectManaged(candidates, anchor,
            capacity, priority, excluded);
        entry.capacity = capacity;
        entry.anchor = anchor;
        entry.marksHash = marksHash;
        entry.selectionDirty = false;
    }

    private static boolean sameBox(BoundingBox a, BoundingBox b) {
        return a.minX() == b.minX() && a.minY() == b.minY() && a.minZ() == b.minZ()
            && a.maxX() == b.maxX() && a.maxY() == b.maxY() && a.maxZ() == b.maxZ();
    }
}
