package com.hearthstead;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(Hearthstead.MODID)
public class Hearthstead {
    public static final String MODID = "hearthstead";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Hearthstead() {
        LOGGER.info("Hearthstead is kindling the fire...");
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }
}
