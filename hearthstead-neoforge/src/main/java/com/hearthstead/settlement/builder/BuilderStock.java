package com.hearthstead.settlement.builder;

import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.warehouse.WarehouseStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Chest truth for the Builder: every count is read from real containers at
 * the moment it matters, and every move takes from one real slot and puts
 * into another in the same call. Nothing here remembers a count.
 */
public final class BuilderStock {

    private BuilderStock() {
    }

    /** The containers of a (valid) Builder's Hut: where materials arrive. */
    public static List<Container> hutContainers(ServerLevel level, @Nullable Building hut) {
        List<Container> out = new ArrayList<>();
        if (hut == null || hut.bounds == null) {
            return out;
        }
        for (BlockPos pos : WarehouseIndex.containers(level, hut)) {
            if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof Container container) {
                out.add(container);
            }
        }
        return out;
    }

    /** Warehouse containers of a settlement (for self-fetch when no Courier works). */
    public static List<BlockPos> warehouseContainersHolding(ServerLevel level, Settlement settlement,
                                                            Item item) {
        List<BlockPos> out = new ArrayList<>();
        for (Building building : settlement.buildings) {
            if (!building.valid || building.type != BuildingType.WAREHOUSE || building.bounds == null) {
                continue;
            }
            for (BlockPos pos : WarehouseIndex.containers(level, building)) {
                if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof Container container
                    && count(List.of(container), item) > 0) {
                    out.add(pos);
                }
            }
        }
        return out;
    }

    /** How many of {@code item} the settlement's warehouses hold (cached tally). */
    public static int warehouseCount(ServerLevel level, Settlement settlement, Item item) {
        int total = 0;
        for (Building building : settlement.buildings) {
            if (building.valid && building.type == BuildingType.WAREHOUSE && building.bounds != null) {
                total += WarehouseStorage.of(level, building).tally().getOrDefault(item, 0);
            }
        }
        return total;
    }

    public static int count(List<Container> containers, Item item) {
        int total = 0;
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && stack.is(item) && plain(stack)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    /**
     * Only plain stacks are building material: a renamed or enchanted item
     * (a named chest a player cares about) is never used up as a block.
     */
    static boolean plain(ItemStack stack) {
        return stack.getComponentsPatch().isEmpty();
    }

    /**
     * Moves up to {@code wanted} of {@code item} from the containers into the
     * bag. Returns how many moved. Each unit leaves a real slot and lands in
     * the bag in this same call; if the bag fills, the remainder stays put.
     */
    public static int moveToBag(List<Container> from, Item item, int wanted, Container bag) {
        int moved = 0;
        for (Container container : from) {
            for (int slot = 0; slot < container.getContainerSize() && moved < wanted; slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || !stack.is(item) || !plain(stack)) {
                    continue;
                }
                int take = Math.min(wanted - moved, stack.getCount());
                ItemStack moving = stack.copyWithCount(take);
                int fitted = take - insert(bag, moving);
                if (fitted <= 0) {
                    return moved;
                }
                stack.shrink(fitted);
                if (stack.isEmpty()) {
                    container.setItem(slot, ItemStack.EMPTY);
                }
                container.setChanged();
                moved += fitted;
            }
        }
        return moved;
    }

    /** Count of a plain item in the bag. */
    public static int bagCount(Container bag, Item item) {
        return count(List.of(bag), item);
    }

    /**
     * Takes exactly {@code n} of {@code item} out of the bag, or nothing.
     * The all-or-nothing check comes first so a short bag is never
     * half-charged.
     */
    public static boolean takeFromBag(Container bag, Item item, int n) {
        if (n <= 0) {
            return true;
        }
        if (bagCount(bag, item) < n) {
            return false;
        }
        int left = n;
        for (int slot = 0; slot < bag.getContainerSize() && left > 0; slot++) {
            ItemStack stack = bag.getItem(slot);
            if (stack.isEmpty() || !stack.is(item) || !plain(stack)) {
                continue;
            }
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            if (stack.isEmpty()) {
                bag.setItem(slot, ItemStack.EMPTY);
            }
            left -= take;
        }
        bag.setChanged();
        return left == 0;
    }

    /** Inserts into a container; returns the count that did NOT fit. */
    public static int insert(Container container, ItemStack stack) {
        int left = stack.getCount();
        // Merge into matching stacks first, then empty slots.
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int slot = 0; slot < container.getContainerSize() && left > 0; slot++) {
                ItemStack in = container.getItem(slot);
                if (pass == 0) {
                    if (in.isEmpty() || !ItemStack.isSameItemSameComponents(in, stack)) {
                        continue;
                    }
                    int room = Math.min(in.getMaxStackSize(), container.getMaxStackSize()) - in.getCount();
                    int put = Math.min(room, left);
                    if (put > 0) {
                        in.grow(put);
                        left -= put;
                        container.setChanged();
                    }
                } else if (in.isEmpty() && container.canPlaceItem(slot, stack)) {
                    int put = Math.min(left, Math.min(stack.getMaxStackSize(), container.getMaxStackSize()));
                    container.setItem(slot, stack.copyWithCount(put));
                    left -= put;
                    container.setChanged();
                }
            }
        }
        return left;
    }

    /** Inserts into the first containers with room; returns what did not fit. */
    public static ItemStack insertAll(List<Container> containers, ItemStack stack) {
        int left = stack.getCount();
        for (Container container : containers) {
            if (left <= 0) {
                break;
            }
            left = insert(container, stack.copyWithCount(left));
        }
        return left <= 0 ? ItemStack.EMPTY : stack.copyWithCount(left);
    }

    /**
     * Puts salvaged or returned items into the hut, and anything that does
     * not fit on the ground at {@code where} -- physical, never deleted.
     */
    public static void store(ServerLevel level, List<Container> hut, ItemStack stack, BlockPos where) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack rest = insertAll(hut, stack);
        if (!rest.isEmpty()) {
            ItemEntity drop = new ItemEntity(level, where.getX() + 0.5, where.getY() + 0.5,
                where.getZ() + 0.5, rest);
            drop.setDefaultPickUpDelay();
            level.addFreshEntity(drop);
        }
    }

    /** Empties the bag into the hut (overflow drops at {@code where}). */
    public static void emptyBag(ServerLevel level, Container bag, List<Container> hut, BlockPos where) {
        for (int slot = 0; slot < bag.getContainerSize(); slot++) {
            ItemStack stack = bag.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            bag.setItem(slot, ItemStack.EMPTY);
            store(level, hut, stack, where);
        }
        bag.setChanged();
    }

    /** Total items in the bag. */
    public static int bagLoad(Container bag) {
        int n = 0;
        for (int slot = 0; slot < bag.getContainerSize(); slot++) {
            n += bag.getItem(slot).getCount();
        }
        return n;
    }
}
