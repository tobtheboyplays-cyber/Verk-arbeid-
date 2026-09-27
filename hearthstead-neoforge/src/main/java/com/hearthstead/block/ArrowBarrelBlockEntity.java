package com.hearthstead.block;

import com.hearthstead.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nine slots of plain arrows, nothing else. Loaded barrels register their
 * position per dimension so archers can find the nearest one without a world
 * scan; the registry is a cache only, every use re-checks the block entity.
 */
public final class ArrowBarrelBlockEntity extends BlockEntity implements Container, MenuProvider {
    public static final int SLOTS = 9;
    /** Server-thread only: loaded barrels per dimension. */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> LOADED = new HashMap<>();

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);

    public ArrowBarrelBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ARROW_BARREL.get(), pos, state);
    }

    /** Plain arrows only: named or tipped arrows stay out of the shared barrel. */
    public static boolean accepts(ItemStack stack) {
        return stack.is(Items.ARROW) && stack.getComponentsPatch().isEmpty();
    }

    /** Loaded barrel positions in this dimension (a copy). */
    public static List<BlockPos> loaded(ServerLevel level) {
        Set<BlockPos> set = LOADED.get(level.dimension());
        return set == null ? List.of() : new ArrayList<>(set);
    }

    /** Plain arrows in this barrel. */
    public int arrows() {
        int total = 0;
        for (ItemStack stack : items) {
            if (accepts(stack)) total += stack.getCount();
        }
        return total;
    }

    /** Removes up to {@code count} arrows; returns how many were removed. */
    public int takeArrows(int count) {
        int taken = 0;
        for (int slot = 0; slot < items.size() && taken < count; slot++) {
            ItemStack stack = items.get(slot);
            if (!accepts(stack)) continue;
            int n = Math.min(count - taken, stack.getCount());
            stack.shrink(n);
            if (stack.isEmpty()) items.set(slot, ItemStack.EMPTY);
            taken += n;
        }
        if (taken > 0) setChanged();
        return taken;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            LOADED.computeIfAbsent(level.dimension(), k -> new HashSet<>()).add(worldPosition.immutable());
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        forget();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        forget();
    }

    private void forget() {
        if (level != null && !level.isClientSide) {
            Set<BlockPos> set = LOADED.get(level.dimension());
            if (set != null) set.remove(worldPosition);
        }
    }

    @Override public int getContainerSize() { return items.size(); }
    @Override public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }
    @Override public ItemStack getItem(int slot) { return items.get(slot); }
    @Override public ItemStack removeItem(int slot, int count) {
        ItemStack taken = ContainerHelper.removeItem(items, slot, count);
        if (!taken.isEmpty()) setChanged();
        return taken;
    }
    @Override public ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items, slot); }
    @Override public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        setChanged();
    }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return accepts(stack); }
    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }
    @Override public void clearContent() { items.clear(); setChanged(); }
    @Override public Component getDisplayName() { return Component.translatable("container.hearthstead.arrow_barrel"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new com.hearthstead.menu.ArrowBarrelMenu(id, inventory, this, worldPosition);
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
    }
}
