package com.hearthstead.menu;

import com.hearthstead.block.ArrowBarrelBlockEntity;
import com.hearthstead.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Nine arrow-only slots plus the "Call archers to resupply" button, sent as
 * vanilla's container button click ({@link #BUTTON_CALL}) and validated on
 * the server by {@link com.hearthstead.settlement.guard.ArrowBarrelCall}.
 */
public final class ArrowBarrelMenu extends AbstractContainerMenu {
    public static final int BUTTON_CALL = 0;
    private final Container barrel;
    private final BlockPos pos;

    public ArrowBarrelMenu(int id, Inventory inventory, FriendlyByteBuf ignored) {
        this(id, inventory, new SimpleContainer(ArrowBarrelBlockEntity.SLOTS), BlockPos.ZERO);
    }

    public ArrowBarrelMenu(int id, Inventory inventory, Container barrel, BlockPos pos) {
        super(ModMenus.ARROW_BARREL.get(), id);
        checkContainerSize(barrel, ArrowBarrelBlockEntity.SLOTS);
        this.barrel = barrel;
        this.pos = pos.immutable();
        for (int col = 0; col < ArrowBarrelBlockEntity.SLOTS; col++) {
            addSlot(new Slot(barrel, col, 20 + 18 * col, 52) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return ArrowBarrelBlockEntity.accepts(stack);
                }
            });
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 20 + 18 * col, 122 + 18 * row));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 20 + 18 * col, 180));
        }
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id != BUTTON_CALL || !(player instanceof ServerPlayer server)) {
            return false;
        }
        com.hearthstead.settlement.guard.ArrowBarrelCall.call(server, pos);
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return barrel.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        int n = ArrowBarrelBlockEntity.SLOTS;
        if (index < n) {
            if (!moveItemStackTo(stack, n, slots.size(), true)) return ItemStack.EMPTY;
        } else if (!ArrowBarrelBlockEntity.accepts(stack) || !moveItemStackTo(stack, 0, n, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return before;
    }
}
