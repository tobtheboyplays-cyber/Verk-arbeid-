package com.hearthstead.settlement.warehouse;

import com.hearthstead.settlement.Building;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A derived, revisioned view of what a warehouse building is holding.
 *
 * <p><b>Chests are the truth</b> (D-A2a-2). Nothing here stores items: the
 * tally is a cache for display and for deciding <em>whether</em> to walk
 * somewhere, and every actual transfer re-reads the container it is about
 * to touch. A stale tally can therefore waste a courier's trip; it can
 * never move, duplicate or destroy an item.
 *
 * <p>This is deliberately NOT persisted. It is rebuilt from the world on
 * demand, so a save/reload — or a player rearranging chests by hand —
 * cannot leave a wrong index behind. MineColonies' delivery system has a
 * long tail of bugs where a request index and the real chests disagree
 * (see docs/project/REFERENCE_ANALYSIS.md); being derived-only is how we
 * avoid that class entirely.
 */
public final class WarehouseStorage {

    /** Minimum ticks between full refreshes of one building's index. */
    public static final int REFRESH_INTERVAL_TICKS = 100;

    private static final Map<UUID, WarehouseStorage> CACHE = new HashMap<>();

    private final UUID buildingId;
    private final List<BlockPos> containers = new ArrayList<>();
    private final Map<Item, Integer> tally = new LinkedHashMap<>();
    private int totalItems;
    private long lastRefreshTick;
    /**
     * Whether {@link #refresh} has ever run. A sentinel tick cannot stand in
     * for this: {@code getGameTime() - Long.MIN_VALUE} overflows to a
     * negative age, so a never-refreshed index would read as fresh and
     * report an empty warehouse forever.
     */
    private boolean everRefreshed;
    private int revision;
    /** Containers visited by the last refresh — asserted by the GameTest. */
    private int lastVisitCount;

    private WarehouseStorage(UUID buildingId) {
        this.buildingId = buildingId;
    }

    /**
     * The index for this building, refreshed if it is older than
     * {@link #REFRESH_INTERVAL_TICKS}. Cheap to call every tick.
     *
     * <p>The age is an absolute difference on purpose. The cache is static
     * and survives a world unload within one JVM, so a later world can
     * report a game time <em>earlier</em> than the stamp left by the last
     * one. A signed comparison reads that as "refreshed in the future" and
     * hands back block positions from a world that is gone.
     */
    public static WarehouseStorage of(ServerLevel level, Building building) {
        WarehouseStorage storage =
            CACHE.computeIfAbsent(building.id, WarehouseStorage::new);
        if (!storage.everRefreshed
            || Math.abs(level.getGameTime() - storage.lastRefreshTick)
                >= REFRESH_INTERVAL_TICKS) {
            storage.refresh(level, building);
        }
        return storage;
    }

    /** Forces a rebuild regardless of age (after a transfer, or in tests). */
    public static WarehouseStorage refreshed(ServerLevel level, Building building) {
        WarehouseStorage storage =
            CACHE.computeIfAbsent(building.id, WarehouseStorage::new);
        storage.refresh(level, building);
        return storage;
    }

    /** Drops every cached index. Call when a settlement or world unloads. */
    public static void clearAll() {
        CACHE.clear();
    }

    /** Drops one building's index — e.g. when its plaque is removed. */
    public static void forget(UUID buildingId) {
        CACHE.remove(buildingId);
    }

    private void refresh(ServerLevel level, Building building) {
        containers.clear();
        tally.clear();
        totalItems = 0;
        lastVisitCount = 0;
        containers.addAll(WarehouseIndex.containers(level, building));
        for (BlockPos pos : containers) {
            Container container = containerAt(level, pos);
            if (container == null) {
                continue;
            }
            lastVisitCount++;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                tally.merge(stack.getItem(), stack.getCount(), Integer::sum);
                totalItems += stack.getCount();
            }
        }
        lastRefreshTick = level.getGameTime();
        everRefreshed = true;
        revision++;
    }

    private static Container containerAt(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container container ? container : null;
    }

    /**
     * Inserts as much of {@code stack} as the warehouse will take and
     * returns the remainder.
     *
     * <p>Ordering matters (D-A2a-3): the destination slot is written first
     * and the source count decremented only by what was actually accepted,
     * so an interruption at any point leaves the items somewhere real. The
     * container is re-read here rather than trusted from the index.
     */
    public ItemStack insert(ServerLevel level, Building building, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        for (BlockPos pos : WarehouseIndex.containers(level, building)) {
            Container container = containerAt(level, pos);
            if (container == null) {
                continue;
            }
            remaining = insertInto(container, remaining);
            if (remaining.isEmpty()) {
                break;
            }
        }
        // The world changed underneath the cache; make that visible at once
        // rather than letting a courier act on a pre-transfer picture.
        refresh(level, building);
        return remaining;
    }

    /** Merges into matching stacks first, then fills empty slots. */
    private static ItemStack insertInto(Container container, ItemStack stack) {
        ItemStack remaining = stack;
        for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }
            int room = Math.min(container.getMaxStackSize(), existing.getMaxStackSize())
                - existing.getCount();
            if (room <= 0) {
                continue;
            }
            int moved = Math.min(room, remaining.getCount());
            existing.grow(moved);
            container.setItem(slot, existing);
            remaining.shrink(moved);
        }
        for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
            if (!container.getItem(slot).isEmpty()) {
                continue;
            }
            int moved = Math.min(
                Math.min(container.getMaxStackSize(), remaining.getMaxStackSize()),
                remaining.getCount());
            ItemStack placed = remaining.copy();
            placed.setCount(moved);
            container.setItem(slot, placed);
            remaining.shrink(moved);
        }
        container.setChanged();
        return remaining;
    }

    public UUID buildingId() {
        return buildingId;
    }

    public List<BlockPos> containers() {
        return List.copyOf(containers);
    }

    public Map<Item, Integer> tally() {
        return Map.copyOf(tally);
    }

    public int distinctTypes() {
        return tally.size();
    }

    public int totalItems() {
        return totalItems;
    }

    public int revision() {
        return revision;
    }

    public int lastVisitCount() {
        return lastVisitCount;
    }

    public boolean hasRoom(ServerLevel level, Building building) {
        for (BlockPos pos : WarehouseIndex.containers(level, building)) {
            Container container = containerAt(level, pos);
            if (container == null) {
                continue;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack existing = container.getItem(slot);
                if (existing.isEmpty()
                    || existing.getCount() < existing.getMaxStackSize()) {
                    return true;
                }
            }
        }
        return false;
    }
}
