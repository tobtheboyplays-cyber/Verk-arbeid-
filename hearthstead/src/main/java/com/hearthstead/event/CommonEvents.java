package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.command.HearthsteadCommand;
import com.hearthstead.entity.SettlerEntity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Hearthstead.MODID)
public final class CommonEvents {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        HearthsteadCommand.register(event.getDispatcher());
    }

    /** Settlers never trample the farmland they tend. */
    @SubscribeEvent
    public static void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (event.getEntity() instanceof SettlerEntity) {
            event.setCanceled(true);
        }
    }

    private CommonEvents() {
    }
}
