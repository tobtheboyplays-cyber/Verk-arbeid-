package com.hearthstead.client.role;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.RoleItems;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

import javax.annotation.Nullable;

/**
 * The longsword is held in both hands on humanoid models (players, and any
 * humanoid renderer that honours item arm poses): vanilla's two-handed
 * crossbow-hold pose while the offhand is free and nothing is being used.
 * Settlers pose through their own animation engine (plan/BATTLE-ROLES.md
 * section 6 asks the animation lane for the matching SettlerModel grip).
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class RoleClientExtensions {
    private RoleClientExtensions() {
    }

    @SubscribeEvent
    public static void register(RegisterClientExtensionsEvent event) {
        IClientItemExtensions twoHanded = new IClientItemExtensions() {
            @Override
            @Nullable
            public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand,
                                                    ItemStack stack) {
                if (hand == InteractionHand.MAIN_HAND && entity.getOffhandItem().isEmpty()
                    && !entity.isUsingItem()) {
                    return HumanoidModel.ArmPose.CROSSBOW_HOLD;
                }
                return null;
            }
        };
        event.registerItem(twoHanded, RoleItems.IRON_LONGSWORD.get(), RoleItems.DIAMOND_LONGSWORD.get());
    }
}
