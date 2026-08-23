package com.hearthstead;

import com.hearthstead.registry.ModBlockEntities;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModComponents;
import com.hearthstead.registry.ModCreativeTabs;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.registry.ModMenus;
import com.hearthstead.registry.ModSounds;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(Hearthstead.MODID)
public class Hearthstead {
    public static final String MODID = "hearthstead";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Hearthstead(IEventBus modBus) {
        ModBlocks.register(modBus);
        ModComponents.register(modBus);
        ModItems.register(modBus);
        ModBlockEntities.register(modBus);
        ModEntities.register(modBus);
        ModMenus.register(modBus);
        ModSounds.register(modBus);
        ModCreativeTabs.register(modBus);
        LOGGER.info("Hearthstead is kindling the fire...");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
