package com.hearthstead.mixin;

import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The Crafter screen's result preview follows the same settlement gate as the block. */
@Mixin(CrafterMenu.class)
public abstract class CrafterMenuMixin {
    @Shadow
    @Final
    private CraftingContainer container;

    @Inject(method = "refreshRecipeResult", at = @At("HEAD"))
    private void hearthstead$enter(CallbackInfo ci) {
        if (container instanceof BlockEntity be) {
            com.hearthstead.settlement.development.TechCraftGate.CRAFTER_CONTEXT.set(be.getBlockPos());
        }
    }

    @Inject(method = "refreshRecipeResult", at = @At("RETURN"))
    private void hearthstead$exit(CallbackInfo ci) {
        com.hearthstead.settlement.development.TechCraftGate.CRAFTER_CONTEXT.remove();
    }
}
