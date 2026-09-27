package com.hearthstead.mixin;

import com.hearthstead.settlement.development.TechCraftGate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmithingRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

/**
 * Tech-gated crafting for the smithing table (netherite weapon upgrades are
 * smithing_transform recipes): the result is not offered, and a cached
 * result cannot be taken, unless the player's settlement learned the node.
 * Every take path (click, shift-click, swap, drop) goes through the result
 * slot's mayPickup, which asks this menu's mayPickup.
 */
@Mixin(SmithingMenu.class)
public abstract class SmithingMenuMixin {
    @Shadow
    @Nullable
    private RecipeHolder<SmithingRecipe> selectedRecipe;

    @Inject(method = "createResult", at = @At("TAIL"))
    private void hearthstead$techGate(CallbackInfo ci) {
        Player player = hearthstead$player();
        if (player != null && !hearthstead$allowed(player)) {
            hearthstead$clear();
        }
    }

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void hearthstead$techGateTake(Player player, boolean hasStack, CallbackInfoReturnable<Boolean> cir) {
        if (!hearthstead$allowed(player)) {
            hearthstead$clear();
            cir.setReturnValue(false);
        }
    }

    private boolean hearthstead$allowed(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(serverPlayer.level() instanceof ServerLevel level)) {
            return true; // the server decides; the client only predicts
        }
        Slot result = hearthstead$resultSlot();
        ItemStack out = result == null ? ItemStack.EMPTY : result.getItem();
        if (out.isEmpty()) {
            return true;
        }
        return TechCraftGate.allowed(level, serverPlayer,
            selectedRecipe == null ? null : selectedRecipe.id(), out.getItem());
    }

    private void hearthstead$clear() {
        Slot result = hearthstead$resultSlot();
        if (result != null) {
            result.set(ItemStack.EMPTY);
        }
        selectedRecipe = null;
    }

    @Nullable
    private Slot hearthstead$resultSlot() {
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
        int index = ((ItemCombinerMenu) (Object) this).getResultSlot();
        return index >= 0 && index < self.slots.size() ? self.getSlot(index) : null;
    }

    @Nullable
    private Player hearthstead$player() {
        for (Slot slot : ((AbstractContainerMenu) (Object) this).slots) {
            if (slot.container instanceof Inventory inventory) {
                return inventory.player;
            }
        }
        return null;
    }
}
