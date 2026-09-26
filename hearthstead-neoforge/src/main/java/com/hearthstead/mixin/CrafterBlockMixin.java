package com.hearthstead.mixin;

import com.hearthstead.settlement.development.TechCraftGate;
import com.hearthstead.settlement.techtree.TechRecipeGates;
import com.hearthstead.settlement.techtree.TechTreeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Tech-gated crafting for the autocrafter (owner policy: "not before
 * unlock", not "never automated"): a Crafter inside a settlement's claim, or
 * within {@link TechCraftGate#PLAYER_RADIUS} of its Banner, may make what
 * that settlement has learned; elsewhere a gated recipe is refused. The block
 * position reaches the static result lookup through {@link TechCraftGate#CRAFTER_CONTEXT}, set by
 * {@code dispenseFrom} and by the Crafter screen's preview.
 */
@Mixin(CrafterBlock.class)
public abstract class CrafterBlockMixin {
    @Inject(method = "dispenseFrom", at = @At("HEAD"))
    private void hearthstead$enter(BlockState state, ServerLevel level, BlockPos pos, CallbackInfo ci) {
        TechCraftGate.CRAFTER_CONTEXT.set(pos);
    }

    @Inject(method = "dispenseFrom", at = @At("RETURN"))
    private void hearthstead$exit(BlockState state, ServerLevel level, BlockPos pos, CallbackInfo ci) {
        TechCraftGate.CRAFTER_CONTEXT.remove();
    }

    @Inject(method = "getPotentialResults", at = @At("RETURN"), cancellable = true)
    private static void hearthstead$techGate(Level level, CraftingInput input,
                                             CallbackInfoReturnable<Optional<RecipeHolder<CraftingRecipe>>> cir) {
        Optional<RecipeHolder<CraftingRecipe>> found = cir.getReturnValue();
        if (found == null || found.isEmpty() || !TechTreeConfig.gateCrafting()) {
            return;
        }
        RecipeHolder<CraftingRecipe> holder = found.get();
        var output = holder.value().getResultItem(level.registryAccess()).getItem();
        if (!TechRecipeGates.gated(holder.id(), output)) {
            return;
        }
        BlockPos pos = TechCraftGate.CRAFTER_CONTEXT.get();
        if (pos == null || !(level instanceof ServerLevel server)
            || !TechCraftGate.allowedAt(server, pos, holder.id(), output)) {
            cir.setReturnValue(Optional.empty());
        }
    }
}
