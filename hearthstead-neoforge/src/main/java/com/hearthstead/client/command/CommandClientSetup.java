package com.hearthstead.client.command;

import com.hearthstead.Hearthstead;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** Mod-bus client registration for the R/G command keys and HUD layer. */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class CommandClientSetup {
    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        CommandKeys.ALL_MAPPINGS.forEach(event::register);
    }

    @SubscribeEvent
    public static void clientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(SummonClient::install);
    }

    @SubscribeEvent
    public static void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, Hearthstead.id("field_command_hud"), CommandHud::render);
    }

    private CommandClientSetup() {
    }
}
