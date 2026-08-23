package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Hearthstead.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModBusEvents {

    @SubscribeEvent
    public static void onAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ModEntities.SETTLER.get(), SettlerEntity.createAttributes().build());
    }

    private ModBusEvents() {
    }
}
