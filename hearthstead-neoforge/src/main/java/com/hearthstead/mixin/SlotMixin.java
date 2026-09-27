package com.hearthstead.mixin;

import com.hearthstead.settlement.development.TechCraftGate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tech-gated crafting, take-time check: every way of taking a crafting result
 * (click, shift-click, number-key swap, drop) asks {@code mayPickup} first. A
 * cached result whose settlement context is gone (the Banner removed, the
 * player moved away) is cleared and refused here, before any inventory or
 * grid item changes.
 */
@Mixin(Slot.class)
public abstract class SlotMixin {
    @Shadow
    @Final
    public Container container;

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void hearthstead$techGateTake(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ResultSlot) || !(container instanceof ResultContainer result)
            || !(player instanceof ServerPlayer serverPlayer)
            || !(serverPlayer.level() instanceof ServerLevel level)) {
            return;
        }
        ItemStack out = result.getItem(0);
        if (out.isEmpty()) {
            return;
        }
        RecipeHolder<?> used = result.getRecipeUsed();
        ResourceLocation id = used == null ? null : used.id();
        if (!TechCraftGate.allowed(level, serverPlayer, id, out.getItem())) {
            result.setItem(0, ItemStack.EMPTY);
            result.setRecipeUsed(null);
            cir.setReturnValue(false);
        }
    }
}
