package com.hearthstead;

import com.hearthstead.registry.ModBlockEntities;
import com.hearthstead.registry.ModAttachments;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModComponents;
import com.hearthstead.registry.ModCreativeTabs;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.registry.ModMenus;
import com.hearthstead.registry.ModSounds;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Hearthstead.MODID)
public class Hearthstead {
    public static final String MODID = "hearthstead";
    /** Stable category used by both ordinary logs and fail-closed QA evidence. */
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public Hearthstead(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, HearthsteadServerConfig.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, HearthsteadClientConfig.SPEC);
        ModAttachments.register(modBus);
        ModBlocks.register(modBus);
        ModComponents.register(modBus);
        ModItems.register(modBus);
        com.hearthstead.registry.RoleItems.register(modBus);
        // Anim lane: display-only trade tools drawn by clip props (never in an inventory).
        com.hearthstead.registry.PropItems.register(modBus);
        // Trades-unlock lane: the 15 extended-trade Mayor emblems.
        com.hearthstead.registry.TradeEmblemItems.register(modBus);
        // Weapons lane: captain weapons in every material tier (plan/WEAPONS.md).
        com.hearthstead.registry.WeaponItems.register(modBus);
        ModBlockEntities.register(modBus);
        ModEntities.register(modBus);
        ModMenus.register(modBus);
        ModSounds.register(modBus);
        // FX lane: Bannerhold particle types (client/fx draws them).
        com.hearthstead.registry.ModParticles.register(modBus);
        ModCreativeTabs.register(modBus);
        // Blueprint-artist lane: "Match town style" for the Builder's Plan.
        com.hearthstead.settlement.builder.TownStyle.install();
        LOGGER.info("Hearthstead is kindling the fire... build={}",
            BuildIdentity.display());
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
