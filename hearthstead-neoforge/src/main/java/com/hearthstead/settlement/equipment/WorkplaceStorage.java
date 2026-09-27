package com.hearthstead.settlement.equipment;

import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.warehouse.WarehouseStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Comparator;

/** Bounded, chest-true operations shared by workers and couriers. */
public final class WorkplaceStorage {

    @Nullable
    public static BlockPos nearestContainer(ServerLevel level, Building building,
                                            BlockPos from) {
        return WarehouseIndex.containers(level, building).stream()
            .min(Comparator.comparingDouble(from::distSqr))
            .orElse(null);
    }

    public static ItemStack insert(ServerLevel level, Building building,
                                   ItemStack stack) {
        return WarehouseStorage.of(level, building).insert(level, building, stack);
    }

    public static boolean hasMatching(ServerLevel level, Building building,
                                      EquipmentRequirement requirement) {
        return findMatching(level, building, requirement) != null;
    }

    /**
     * Removes one matching tool from a real workplace container. The caller
     * must immediately place the returned stack in another real inventory.
     */
    public static ItemStack extractOne(ServerLevel level, Building building,
                                       EquipmentRequirement requirement) {
        Found found = findMatching(level, building, requirement);
        if (found == null) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = found.container().removeItem(found.slot(), 1);
        found.container().setChanged();
        WarehouseStorage.refreshed(level, building);
        return removed;
    }

    @Nullable
    public static BlockPos nearestMatchingContainer(ServerLevel level,
                                                    Building building,
                                                    EquipmentRequirement requirement,
                                                    BlockPos from) {
        List<BlockPos> matching = matchingContainers(level, building,
            requirement, from);
        return matching.isEmpty() ? null : matching.get(0);
    }

    /**
     * Every loaded matching source in deterministic nearest-first order.
     * Callers which own navigation may skip a temporarily failed exact source
     * without allowing that nearer source to starve another usable chest.
     */
    public static List<BlockPos> matchingContainers(ServerLevel level,
                                                    Building building,
                                                    EquipmentRequirement requirement,
                                                    BlockPos from) {
        java.util.ArrayList<BlockPos> matching = new java.util.ArrayList<>();
        for (BlockPos pos : WarehouseIndex.containers(level, building)) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof Container container)
                || findMatching(container, requirement) < 0) {
                continue;
            }
            matching.add(pos.immutable());
        }
        matching.sort(Comparator.<BlockPos>comparingDouble(from::distSqr)
            .thenComparingLong(BlockPos::asLong));
        return List.copyOf(matching);
    }

    /** Exact-container form used by the worker standing at that chest. */
    public static ItemStack swapOneAt(ServerLevel level, Building building,
                                      BlockPos containerPos,
                                      EquipmentRequirement requirement,
                                      ItemStack displaced) {
        if (!WarehouseIndex.containers(level, building).contains(containerPos)
            || !(level.getBlockEntity(containerPos) instanceof Container container)) {
            return ItemStack.EMPTY;
        }
        int slot = findMatching(container, requirement);
        if (slot < 0) {
            return ItemStack.EMPTY;
        }
        ItemStack source = container.getItem(slot);
        if (source.getCount() != 1 || (!displaced.isEmpty()
            && displaced.getCount() != 1)) {
            return ItemStack.EMPTY;
        }
        ItemStack supplied = source.copy();
        container.setItem(slot, displaced.isEmpty()
            ? ItemStack.EMPTY : displaced.copy());
        container.setChanged();
        WarehouseStorage.refreshed(level, building);
        return supplied;
    }

    @Nullable
    private static Found findMatching(ServerLevel level, Building building,
                                      EquipmentRequirement requirement) {
        for (BlockPos pos : WarehouseIndex.containers(level, building)) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof Container container)) {
                continue;
            }
            int slot = findMatching(container, requirement);
            if (slot >= 0) {
                return new Found(container, slot);
            }
        }
        return null;
    }

    private static int findMatching(Container container,
                                    EquipmentRequirement requirement) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (requirement.serviceable(container.getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private record Found(Container container, int slot) {
    }

    private WorkplaceStorage() {
    }
}
