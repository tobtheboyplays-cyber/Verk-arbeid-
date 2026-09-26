package com.hearthstead.menu;

import com.hearthstead.registry.ModMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Vanilla server-owned slot transfers; the client only mirrors the hanging stock. */
public final class FishRackMenu extends AbstractContainerMenu {
    private final Container rack;
    public FishRackMenu(int id, Inventory inventory, FriendlyByteBuf ignored) {
        this(id, inventory, new SimpleContainer(4));
    }
    public FishRackMenu(int id, Inventory inventory, Container rack) {
        super(ModMenus.FISH_RACK.get(), id);
        checkContainerSize(rack, 4);
        this.rack = rack;
        for (int col=0; col<4; col++)
            addSlot(new Slot(rack,col,39+36*col,52) {
                @Override public boolean mayPlace(ItemStack stack) { return stack.is(ItemTags.FISHES); }
                @Override public int getMaxStackSize() { return 1; }
            });
        for (int row=0; row<3; row++) for (int col=0; col<9; col++)
            addSlot(new Slot(inventory,col+row*9+9,20+18*col,122+18*row));
        for (int col=0; col<9; col++) addSlot(new Slot(inventory,col,20+18*col,180));
    }
    @Override public boolean stillValid(Player player) { return rack.stillValid(player); }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack=slot.getItem(), before=stack.copy();
        if (index<4) {
            if (!moveItemStackTo(stack,4,slots.size(),true)) return ItemStack.EMPTY;
        } else if (!stack.is(ItemTags.FISHES) || !moveItemStackTo(stack,0,4,false)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        return before;
    }
}
