package com.hearthstead.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * A plain list stock with NBT save/load, for event content that just wants a
 * peddler's bag. Stacks are merged by item + components. Also hosts the
 * registry of named stocks that graph replies open with {@code .barter(id)}.
 */
public final class ListBarterStock implements BarterStock {
    public static final int MAX_STACKS = 27;
    private static final Map<String, Function<ConversationContext, BarterStock>> NAMED = new ConcurrentHashMap<>();
    private final List<ItemStack> stacks = new ArrayList<>();
    private Runnable onChange = () -> { };

    public ListBarterStock() {
    }

    public ListBarterStock(List<ItemStack> initial) {
        for (ItemStack stack : initial) merge(stack);
    }

    /** Named stock for {@code .barter("peddler.stock")} replies. */
    public static void register(String stockId, Function<ConversationContext, BarterStock> factory) {
        NAMED.put(stockId, factory);
    }

    @Nullable
    static BarterStock named(String stockId, ConversationContext ctx) {
        Function<ConversationContext, BarterStock> factory = NAMED.get(stockId);
        return factory == null ? null : factory.apply(ctx);
    }

    /** Called after every accepted change (e.g. to mark saved data dirty). */
    public ListBarterStock onChange(Runnable listener) {
        this.onChange = listener == null ? () -> { } : listener;
        return this;
    }

    @Override
    public List<ItemStack> items() {
        List<ItemStack> copy = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) copy.add(stack.copy());
        return copy;
    }

    @Override
    public boolean remove(List<ItemStack> taken) {
        List<ItemStack> work = items();
        for (ItemStack want : taken) {
            if (want.isEmpty() || want.getCount() <= 0) return false;
            int left = want.getCount();
            for (ItemStack have : work) {
                if (left <= 0) break;
                if (!ItemStack.isSameItemSameComponents(have, want)) continue;
                int take = Math.min(left, have.getCount());
                have.shrink(take);
                left -= take;
            }
            if (left > 0) return false;
        }
        stacks.clear();
        for (ItemStack stack : work) if (!stack.isEmpty()) stacks.add(stack);
        onChange.run();
        return true;
    }

    @Override
    public void add(List<ItemStack> given) {
        for (ItemStack stack : given) merge(stack);
        onChange.run();
    }

    private void merge(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        for (ItemStack have : stacks) {
            if (ItemStack.isSameItemSameComponents(have, stack)) {
                have.grow(stack.getCount());
                return;
            }
        }
        if (stacks.size() < MAX_STACKS) stacks.add(stack.copy());
    }

    public ListTag save(HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (ItemStack stack : stacks) {
            CompoundTag entry = new CompoundTag();
            entry.put("Stack", stack.copyWithCount(1).save(registries));
            entry.putInt("Count", stack.getCount());
            list.add(entry);
        }
        return list;
    }

    public static ListBarterStock load(ListTag list, HolderLookup.Provider registries) {
        ListBarterStock stock = new ListBarterStock();
        for (Tag raw : list) {
            if (!(raw instanceof CompoundTag entry)) continue;
            ItemStack.parse(registries, entry.getCompound("Stack")).ifPresent(stack -> {
                int count = Math.max(1, entry.getInt("Count"));
                stock.merge(stack.copyWithCount(count));
            });
        }
        return stock;
    }
}
