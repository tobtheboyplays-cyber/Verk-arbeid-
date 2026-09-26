package com.hearthstead.client.weapon;

import com.hearthstead.Hearthstead;
import com.hearthstead.item.weapon.CaptainWeaponItem;
import com.hearthstead.registry.WeaponItems;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Client wiring of the captain weapons: the two-handed guard arm pose for players holding a
 * double axe / halberd / warhammer with an empty offhand, and the standalone vanilla nocked-bow
 * model used by {@link SettlerBowHold}'s low-ready carry.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class WeaponClient {
    private WeaponClient() {
    }

    @SubscribeEvent
    public static void registerExtensions(RegisterClientExtensionsEvent event) {
        IClientItemExtensions guard = new IClientItemExtensions() {
            @Override
            @Nullable
            public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                if (hand == InteractionHand.MAIN_HAND && entity.getOffhandItem().isEmpty()
                    && !entity.isUsingItem() && WeaponArmPoses.enabled()) {
                    try {
                        return WeaponArmPoses.TWO_HANDED_GUARD.getValue();
                    } catch (RuntimeException | LinkageError notExtended) {
                        // enumextensions.json missing from a build: fall back to vanilla's two-hand hold
                        return HumanoidModel.ArmPose.CROSSBOW_HOLD;
                    }
                }
                return null;
            }
        };
        List<Item> twoHanders = new ArrayList<>();
        for (DeferredHolder<Item, ? extends Item> h : WeaponItems.ITEMS.getEntries()) {
            if (h.isBound() && h.get() instanceof CaptainWeaponItem w && w.isTwoHanded()) {
                twoHanders.add(w);
            }
        }
        if (!twoHanders.isEmpty()) {
            event.registerItem(guard, twoHanders.toArray(new Item[0]));
        }
    }

    @SubscribeEvent
    public static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(SettlerBowHold.BOW_NOCKED);
    }
}
