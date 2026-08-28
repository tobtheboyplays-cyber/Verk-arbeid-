package com.hearthstead;

import com.hearthstead.network.HearthsteadNetwork;
import com.hearthstead.registry.ModBlockEntities;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModCreativeTabs;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.registry.ModMenus;
import com.hearthstead.registry.ModSounds;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(Hearthstead.MODID)
public class Hearthstead {
    public static final String MODID = "hearthstead";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Hearthstead() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModBlocks.register(modBus);
        ModItems.register(modBus);
        ModBlockEntities.register(modBus);
        ModEntities.register(modBus);
        ModMenus.register(modBus);
        ModSounds.register(modBus);
        ModCreativeTabs.register(modBus);
        modBus.addListener(this::commonSetup);
        LOGGER.info("Hearthstead is kindling the fire...");
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(HearthsteadNetwork::register);
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }
}
