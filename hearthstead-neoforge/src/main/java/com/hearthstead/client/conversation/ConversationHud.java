package com.hearthstead.client.conversation;

import com.hearthstead.Hearthstead;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * While talking, the HUD steps aside: no crosshair over the talk bar (it read
 * as a stray "x" on the frame) and no hotbar, hearts or XP under it.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class ConversationHud {
    private static final Set<ResourceLocation> HIDDEN = Set.of(VanillaGuiLayers.CROSSHAIR, VanillaGuiLayers.HOTBAR,
        VanillaGuiLayers.EXPERIENCE_BAR, VanillaGuiLayers.EXPERIENCE_LEVEL, VanillaGuiLayers.PLAYER_HEALTH,
        VanillaGuiLayers.ARMOR_LEVEL, VanillaGuiLayers.FOOD_LEVEL, VanillaGuiLayers.AIR_LEVEL,
        VanillaGuiLayers.SELECTED_ITEM_NAME, VanillaGuiLayers.JUMP_METER, VanillaGuiLayers.VEHICLE_HEALTH);

    private ConversationHud() {
    }

    @SubscribeEvent
    public static void onLayer(RenderGuiLayerEvent.Pre event) {
        if (ConversationClient.active() && HIDDEN.contains(event.getName())) event.setCanceled(true);
    }
}
