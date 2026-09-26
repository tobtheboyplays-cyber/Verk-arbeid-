package com.hearthstead.settlement.work;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

/** Physical ale reserve prepared by the Innkeeper at an own-Tavern tap barrel. */
public final class TavernAleService {
    public static final int WHEAT_PER_ALE = 3;
    public static final int BREW_COOLDOWN_TICKS = 200;
    public static final int ALE_RESERVE = 8;
    private static final String NEXT_BREW_KEY = "TavernAleNextBrew";

    private TavernAleService() {}

    public static boolean isAle(ItemStack stack) {
        return stack != null && stack.is(ModItems.ALE.get());
    }

    public static int aleSlot(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++)
            if (isAle(container.getItem(slot))) return slot;
        return -1;
    }

    /** Read-only work selection. Caller owns walking, retries and serving priority. */
    public static BlockPos findRefillSource(SettlerEntity host, Building tavern) {
        if (!authorizedRefill(host, tavern) || !WorkerStorageAuthority.withinScanBudget(tavern.bounds))
            return null;
        ServerLevel level = (ServerLevel) host.level();
        for (BlockPos source : WarehouseIndex.containers(level, tavern)) {
            if (canRefillSource(host, tavern, source)) return source.immutable();
        }
        return null;
    }

    /** Recheck an intended barrel without requesting contact or moving any item. */
    public static boolean canRefillSource(SettlerEntity host, Building tavern, BlockPos source) {
        if (!authorizedRefill(host, tavern) || source == null) return false;
        ServerLevel level = (ServerLevel) host.level();
        if (AleTapService.resolveTapForBarrel(level, tavern, source) == null) return false;
        return level.getBlockEntity(source) instanceof BarrelBlockEntity barrel
            && aleCount(barrel) < ALE_RESERVE && wheatCount(barrel) >= WHEAT_PER_ALE
            && aleOutputSlotAfterWheatDebit(barrel) >= 0;
    }

    private static boolean authorizedRefill(SettlerEntity host, Building tavern) {
        return host != null && host.level() instanceof ServerLevel level && tavern != null
            && tavern.type == BuildingType.TAVERN && tavern.valid
            && TavernHostService.authorizedHost(host, tavern.id)
            && Employment.employerOf(host.settlement(), host.getUUID()) == tavern
            && !TavernHostService.hasSession(host)
            && level.getGameTime() >= host.getPersistentData().getLong(NEXT_BREW_KEY);
    }

    /**
     * Signature retained for existing callers. A chest or disconnected barrel
     * no longer brews: preparation requires the actual own-Tavern tap reserve.
     */
    public static boolean brewAtContact(SettlerEntity host, Building tavern,
                                        BlockPos source, Container live) {
        if (!canRefillSource(host, tavern, source)) return false;
        ServerLevel level = (ServerLevel) host.level();
        if ((Object) level.getBlockEntity(source) != live
            || !ContainerApproach.inspect(level, host, source).canInteract()) return false;

        // A single-threaded vanilla barrel transaction. Plan every debit and
        // destination before shrinking wheat; a full store never loses inputs.
        int outputSlot = aleOutputSlotAfterWheatDebit(live);
        if (outputSlot < 0) return false;
        int wheatBefore = wheatCount(live);
        removeWheat(live, WHEAT_PER_ALE);
        ItemStack held = live.getItem(outputSlot);
        ItemStack output = held.isEmpty() ? new ItemStack(ModItems.ALE.get()) : held.copy();
        if (!held.isEmpty()) output.grow(1);
        live.setItem(outputSlot, output);
        live.setChanged();
        host.getPersistentData().putLong(NEXT_BREW_KEY,
            level.getGameTime() + BREW_COOLDOWN_TICKS);
        level.playSound(null, source.getX() + .5, source.getY() + .5, source.getZ() + .5,
            com.hearthstead.registry.ModSounds.TAVERN_POUR.get(), SoundSource.BLOCKS, .35F,
            .92F + host.getRandom().nextFloat() * .08F);
        com.hearthstead.Hearthstead.LOGGER.info(
            "HEARTHSTEAD_TAVERN_ALE_BREW host={} source={} wheatBefore={} wheatConsumed={} wheatAfter={} aleProduced=1",
            host.getUUID(), source.toShortString(), wheatBefore, WHEAT_PER_ALE, wheatCount(live));
        if (com.hearthstead.util.QaTrace.ENABLED) com.hearthstead.util.QaTrace.event(host,
            "tavern_ale_brew_commit", "source=" + source.toShortString() + ";wheatConsumed="
                + WHEAT_PER_ALE + ";aleProduced=1");
        return true;
    }

    /** Count every existing ALE, including decorated stacks, against the reserve. */
    private static int aleCount(Container container) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (isAle(stack)) count += stack.getCount();
        }
        return count;
    }

    private static int aleOutputSlotAfterWheatDebit(Container container) {
        ItemStack offered = new ItemStack(ModItems.ALE.get());
        int limit = Math.min(container.getMaxStackSize(), offered.getMaxStackSize());
        if (limit < 1) return -1;
        // Plain brewing must not overwrite custom names, quality or other ALE components.
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, offered)
                && stack.getCount() < limit && container.canPlaceItem(slot, offered)) return slot;
        }
        int remaining = WHEAT_PER_ALE;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            int removed = stack.is(Items.WHEAT) ? Math.min(remaining, stack.getCount()) : 0;
            remaining -= removed;
            if (stack.getCount() - removed == 0 && container.canPlaceItem(slot, offered)) return slot;
        }
        return -1;
    }

    private static int wheatCount(Container container) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(Items.WHEAT)) total += stack.getCount();
        }
        return total;
    }

    private static void removeWheat(Container container, int amount) {
        for (int slot = 0; slot < container.getContainerSize() && amount > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.is(Items.WHEAT)) continue;
            int removed = Math.min(amount, stack.getCount());
            stack.shrink(removed);
            amount -= removed;
            if (stack.isEmpty()) container.setItem(slot, ItemStack.EMPTY);
        }
    }
}
