package com.hearthstead.mixin;

import com.hearthstead.settlement.development.TechCraftGate;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * Tech-gated crafting (owner rule): the crafting table and the 2x2 inventory
 * grid both compute their result here. A recipe whose tech node the player's
 * settlement has not learned yields nothing, so taking, shift-clicking and
 * recipe-book placement have nothing to take.
 */
@Mixin(CraftingMenu.class)
public abstract class CraftingMenuMixin {
    @Inject(method = "slotChangedCraftingGrid", at = @At("TAIL"))
    private static void hearthstead$techGate(AbstractContainerMenu menu, Level level, Player player,
                                             CraftingContainer craftSlots, ResultContainer resultSlots,
                                             @Nullable RecipeHolder<CraftingRecipe> recipe, CallbackInfo ci) {
        if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        ItemStack out = resultSlots.getItem(0);
        if (out.isEmpty()) {
            return;
        }
        RecipeHolder<?> used = resultSlots.getRecipeUsed();
        ResourceLocation id = used == null ? null : used.id();
        if (TechCraftGate.allowed(server, serverPlayer, id, out.getItem())) {
            return;
        }
        resultSlots.setItem(0, ItemStack.EMPTY);
        resultSlots.setRecipeUsed(null);
        menu.setRemoteSlot(0, ItemStack.EMPTY);
        serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(menu.containerId,
            menu.incrementStateId(), 0, ItemStack.EMPTY));
    }
}
