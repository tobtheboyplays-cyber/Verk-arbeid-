package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(ForgeRegistries.BLOCKS, Hearthstead.MODID);

    public static final RegistryObject<Block> HEARTH = BLOCKS.register("hearth",
        () -> new HearthBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(3.5F)
            .requiresCorrectToolForDrops()
            .sound(SoundType.STONE)
            .lightLevel(state -> 13)
            .noOcclusion()));

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
    }

    private ModBlocks() {
    }
}
