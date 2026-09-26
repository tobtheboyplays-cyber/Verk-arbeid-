package com.hearthstead.client.finisher;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.command.CommandKeyHooks;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * Client wiring: the R-key interceptor (shared with the Knights command, which
 * owns the one R binding), the OPTIONAL separate finisher key (unbound by
 * default), and the torso-glow layer on every living renderer.
 *
 * <p>Key design: F is vanilla's swap-offhand, so the owner moved finishers to
 * R, context-sensitive with the Knights command (agreed with the command lane
 * 2026-09-26): crosshair on a glowing enemy in reach = finisher, anything else
 * = Knights command. Rebinding "Command Knights" moves both. Binding the
 * separate "Finish" key splits them: R then only commands.</p>
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class FinisherClientSetup {
    public static final String KNIGHTS_KEY = "key.hearthstead.command_knights";
    public static final KeyMapping FINISH_KEY = new KeyMapping("key.hearthstead.finisher",
        KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN,
        "key.categories.hearthstead");
    private static KeyMapping knightsKey;

    private FinisherClientSetup() {
    }

    @SubscribeEvent
    public static void onKeys(RegisterKeyMappingsEvent event) {
        event.register(FINISH_KEY);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            CommandKeyHooks.registerKnightsInterceptor(FinisherClient::tryConsume);
            FinisherPoseHooks.registerPlayerProvider();
        });
    }

    @SubscribeEvent
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (EntityType<?> type : event.getEntityTypes()) {
            EntityRenderer<?> renderer = event.getRenderer((EntityType) type);
            if (renderer instanceof LivingEntityRenderer living) {
                living.addLayer(new FinishableGlowLayer(living));
            }
        }
    }

    public static boolean separateKeyBound() {
        return !FINISH_KEY.isUnbound();
    }

    static void pollSeparateKey(Minecraft mc) {
        while (FINISH_KEY.consumeClick()) {
            if (separateKeyBound() && mc.screen == null && !FinisherClient.isLocalPlayerExecuting()) {
                FinisherClient.tryStart(mc);
            }
        }
    }

    /** The key the prompt names: the separate key when bound, else the Knights key (R). */
    static Component promptKeyName() {
        if (separateKeyBound()) {
            return FINISH_KEY.getTranslatedKeyMessage();
        }
        KeyMapping knights = knightsKey;
        if (knights == null) {
            for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
                if (KNIGHTS_KEY.equals(mapping.getName())) {
                    knights = mapping;
                    knightsKey = mapping;
                    break;
                }
            }
        }
        return knights == null ? Component.literal("R") : knights.getTranslatedKeyMessage();
    }
}
