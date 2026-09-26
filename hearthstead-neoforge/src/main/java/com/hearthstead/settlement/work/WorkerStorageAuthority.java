package com.hearthstead.settlement.work;

import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.workzone.WorkZoneService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Exact, loaded, no-force-load workplace container transactions. */
public final class WorkerStorageAuthority {
    private static final int MAX_SCAN_VOLUME = 16_384;

    public record Source(BlockPos pos, int slot, ItemStack observed) {
        public Source {
            pos = pos.immutable();
            observed = observed.copy();
        }
    }

    public record Transfer(int sourceBefore, int sourceAfter,
                           int bagBefore, int bagAfter, ItemStack moved) {
        public Transfer {
            moved = moved.copy();
        }

        public boolean conserved() {
            return sourceBefore - sourceAfter == 1
                && bagAfter - bagBefore == 1 && moved.getCount() == 1;
        }
    }

    public record Insert(ItemStack remainder, int inserted,
                         int destinationBefore, int destinationAfter) {
        public Insert {
            remainder = remainder.copy();
        }

        public boolean conserved() {
            return inserted >= 0
                && destinationAfter - destinationBefore == inserted;
        }
    }

    /** Returns only real chest/barrel positions whose chunks are already live. */
    public static List<BlockPos> loadedContainers(ServerLevel level,
                                                   Building building) {
        if (level == null || building == null || building.bounds == null
            || !building.valid) {
            return List.of();
        }
        BoundingBox bounds = building.bounds;
        if (!withinScanBudget(bounds)) {
            return List.of();
        }
        // One authority for "which containers does this building manage":
        // the cached WarehouseIndex (level capacity, nearest-to-plaque, scan
        // order). Only live chests/barrels are handed to workers here.
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : WarehouseIndex.containers(level, building)) {
            if (!WorkZoneService.livePositionAvailable(level, pos)) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof ChestBlockEntity
                || blockEntity instanceof BarrelBlockEntity) {
                result.add(pos);
            }
        }
        return List.copyOf(result);
    }

    /** Overflow-safe before any coordinate loop or chunk query. */
    static boolean withinScanBudget(BoundingBox bounds) {
        if (bounds == null) {
            return false;
        }
        long sx = (long) bounds.maxX() - bounds.minX() + 1L;
        long sy = (long) bounds.maxY() - bounds.minY() + 1L;
        long sz = (long) bounds.maxZ() - bounds.minZ() + 1L;
        if (sx <= 0L || sy <= 0L || sz <= 0L
            || sx > MAX_SCAN_VOLUME || sy > MAX_SCAN_VOLUME
            || sz > MAX_SCAN_VOLUME) {
            return false;
        }
        long xy = sx * sy;
        return xy <= MAX_SCAN_VOLUME
            && xy * sz <= MAX_SCAN_VOLUME;
    }

    @Nullable
    public static BlockPos nearestLoadedContainer(ServerLevel level,
                                                  Building building,
                                                  BlockPos from) {
        if (from == null) {
            return null;
        }
        return loadedContainers(level, building).stream()
            .min(Comparator.comparingDouble(from::distSqr)).orElse(null);
    }

    @Nullable
    public static Source find(ServerLevel level, Building building,
                              Predicate<ItemStack> accepted,
                              BlockPos from) {
        if (accepted == null) {
            return null;
        }
        List<BlockPos> containers = new ArrayList<>(
            loadedContainers(level, building));
        if (from != null) {
            containers.sort(Comparator.comparingDouble(from::distSqr));
        }
        for (BlockPos pos : containers) {
            Container container = containerAt(level, building, pos);
            if (container == null) {
                continue;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && accepted.test(stack)) {
                    return new Source(pos, slot, stack);
                }
            }
        }
        return null;
    }

    /**
     * Resolves one accepted slot from one exact linked container. Unlike
     * {@link #find}, this never falls through to another chest when the named
     * source changed while a worker was walking to it.
     */
    @Nullable
    public static Source findAt(ServerLevel level, Building building,
                                BlockPos sourcePos,
                                Predicate<ItemStack> accepted) {
        if (accepted == null) {
            return null;
        }
        Container container = containerAt(level, building, sourcePos);
        if (container == null) {
            return null;
        }
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && accepted.test(stack)) {
                return new Source(sourcePos, slot, stack);
            }
        }
        return null;
    }

    /**
     * One physical source-to-bag move. The action tagger runs on the removed
     * single item before it enters the bag. Capacity is preflighted, so no
     * stamped remainder ever needs to merge back into an untagged source row.
     */
    @Nullable
    public static Transfer transferOneToBag(ServerLevel level,
                                            Building building,
                                            Source source,
                                            SimpleContainer bag,
                                            UnaryOperator<ItemStack> tagger) {
        if (level == null || building == null || source == null || bag == null
            || tagger == null) {
            return null;
        }
        Container container = containerAt(level, building, source.pos());
        if (container == null || source.slot() < 0
            || source.slot() >= container.getContainerSize()) {
            return null;
        }
        ItemStack live = container.getItem(source.slot());
        if (live.isEmpty() || live.getCount() < 1
            || !ItemStack.isSameItemSameComponents(live, source.observed())) {
            return null;
        }
        ItemStack sourceBeforeStack = live.copy();
        ItemStack candidate = live.copyWithCount(1);
        candidate = tagger.apply(candidate);
        if (candidate == null || candidate.isEmpty() || candidate.getCount() != 1
            || !canAccept(bag, candidate)) {
            return null;
        }
        List<ItemStack> bagBeforeStacks = snapshot(bag);
        int sourceBefore = countItem(container, live.getItem());
        int bagBefore = countItem(bag, candidate.getItem());
        ItemStack removed = container.removeItem(source.slot(), 1);
        if (removed.isEmpty() || removed.getCount() != 1
            || !ItemStack.isSameItemSameComponents(removed, source.observed())) {
            // removeItem is allowed to be implemented by arbitrary Container
            // code. Restore the exact observed slot if it violates the
            // one-item contract; never let a bad implementation eat input.
            container.setItem(source.slot(), sourceBeforeStack);
            container.setChanged();
            return null;
        }
        removed = tagger.apply(removed);
        if (removed == null || removed.isEmpty() || removed.getCount() != 1) {
            container.setItem(source.slot(), sourceBeforeStack);
            restore(bag, bagBeforeStacks);
            container.setChanged();
            return null;
        }
        ItemStack remainder = bag.addItem(removed);
        if (!remainder.isEmpty()) {
            // Preflight and same-thread mutation make this unreachable. An
            // exact slot restore is nevertheless required: the transit tag
            // deliberately makes the removed unit unable to merge back into
            // its untagged source stack.
            container.setItem(source.slot(), sourceBeforeStack);
            restore(bag, bagBeforeStacks);
            container.setChanged();
            return null;
        }
        container.setChanged();
        // removeItem can empty the original stack in place. Its getItem()
        // then becomes AIR, hiding other source rows of the transferred item.
        int sourceAfter = countItem(container, sourceBeforeStack.getItem());
        int bagAfter = countItem(bag, removed.getItem());
        Transfer transfer = new Transfer(sourceBefore, sourceAfter, bagBefore,
            bagAfter, removed);
        if (!transfer.conserved()) {
            container.setItem(source.slot(), sourceBeforeStack);
            restore(bag, bagBeforeStacks);
            container.setChanged();
            return null;
        }
        return transfer;
    }

    /** Inserts only at the named still-loaded linked workplace container. */
    public static Insert insertAt(ServerLevel level, Building building,
                                  BlockPos target, ItemStack source,
                                  int maximum, boolean stripTransit) {
        if (source == null || source.isEmpty() || maximum <= 0) {
            return new Insert(source == null ? ItemStack.EMPTY : source,
                0, 0, 0);
        }
        Container container = containerAt(level, building, target);
        if (container == null) {
            return new Insert(source, 0, 0, 0);
        }
        int offered = Math.min(maximum, source.getCount());
        ItemStack send = source.copyWithCount(offered);
        if (stripTransit && !WorkerStackProvenance.clearTransit(send)) {
            return new Insert(source, 0, 0, 0);
        }
        Item item = send.getItem();
        List<ItemStack> destinationSnapshot = snapshot(container);
        int before = countItem(container, item);
        ItemStack remainderOfSend = insertInto(container, send);
        int inserted = offered - remainderOfSend.getCount();
        int after = countItem(container, item);
        ItemStack sourceRemainder = source.copy();
        sourceRemainder.shrink(inserted);
        container.setChanged();
        Insert receipt = new Insert(sourceRemainder, inserted, before, after);
        if (!receipt.conserved()) {
            // Never report the source as untouched after a destination has
            // partially mutated. Restore the complete bounded container
            // image first, then return an exact no-op result.
            restore(container, destinationSnapshot);
            container.setChanged();
            return new Insert(source, 0, before, before);
        }
        return receipt;
    }

    /**
     * Atomically places a bounded output batch in the physical worker bag.
     * Capacity is simulated first and every slot is restored byte-for-byte if
     * a custom container implementation violates that preflight.
     */
    public static boolean storeAllInBag(SimpleContainer bag,
                                        List<ItemStack> outputs) {
        if (bag == null || outputs == null || outputs.isEmpty()) {
            return false;
        }
        SimpleContainer simulated = new SimpleContainer(bag.getContainerSize());
        for (int slot = 0; slot < bag.getContainerSize(); slot++) {
            simulated.setItem(slot, bag.getItem(slot).copy());
        }
        long offered = 0L;
        for (ItemStack output : outputs) {
            if (output == null || output.isEmpty()) {
                return false;
            }
            offered += output.getCount();
            if (offered > Integer.MAX_VALUE
                || !simulated.addItem(output.copy()).isEmpty()) {
                return false;
            }
        }

        List<ItemStack> before = snapshot(bag);
        long countBefore = countAll(bag);
        for (ItemStack output : outputs) {
            if (!bag.addItem(output.copy()).isEmpty()) {
                restore(bag, before);
                return false;
            }
        }
        if (countAll(bag) - countBefore != offered) {
            restore(bag, before);
            return false;
        }
        return true;
    }

    @Nullable
    private static Container containerAt(ServerLevel level, Building building,
                                         BlockPos pos) {
        if (level == null || building == null || pos == null
            || !building.valid || building.bounds == null
            || !building.contains(pos)
            || !WorkZoneService.livePositionAvailable(level, pos)) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof ChestBlockEntity
            || blockEntity instanceof BarrelBlockEntity
            ? (Container) blockEntity : null;
    }

    private static boolean canAccept(SimpleContainer bag, ItemStack stack) {
        int room = 0;
        for (int slot = 0; slot < bag.getContainerSize(); slot++) {
            ItemStack existing = bag.getItem(slot);
            if (existing.isEmpty()) {
                room += stack.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                room += Math.max(0, Math.min(existing.getMaxStackSize(),
                    bag.getMaxStackSize()) - existing.getCount());
            }
            if (room >= stack.getCount()) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack insertInto(Container container, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < container.getContainerSize()
             && !remaining.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()
                || !ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }
            int room = Math.min(existing.getMaxStackSize(),
                container.getMaxStackSize()) - existing.getCount();
            int moved = Math.min(Math.max(0, room), remaining.getCount());
            if (moved > 0) {
                existing.grow(moved);
                container.setItem(slot, existing);
                remaining.shrink(moved);
            }
        }
        for (int slot = 0; slot < container.getContainerSize()
             && !remaining.isEmpty(); slot++) {
            if (!container.getItem(slot).isEmpty()) {
                continue;
            }
            int moved = Math.min(Math.min(container.getMaxStackSize(),
                remaining.getMaxStackSize()), remaining.getCount());
            ItemStack placed = remaining.copyWithCount(moved);
            container.setItem(slot, placed);
            remaining.shrink(moved);
        }
        return remaining;
    }

    private static int countItem(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static long countAll(Container container) {
        long count = 0L;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            count += container.getItem(slot).getCount();
        }
        return count;
    }

    private static List<ItemStack> snapshot(Container container) {
        List<ItemStack> snapshot = new ArrayList<>(container.getContainerSize());
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            snapshot.add(container.getItem(slot).copy());
        }
        return snapshot;
    }

    private static void restore(Container container, List<ItemStack> snapshot) {
        if (snapshot.size() != container.getContainerSize()) {
            throw new IllegalArgumentException("container snapshot size changed");
        }
        for (int slot = 0; slot < snapshot.size(); slot++) {
            container.setItem(slot, snapshot.get(slot).copy());
        }
        container.setChanged();
    }

    private WorkerStorageAuthority() {
    }
}
