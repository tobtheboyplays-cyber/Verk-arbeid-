package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlock;
import com.hearthstead.block.PlaqueBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(Registries.BLOCK, Hearthstead.MODID);

    public static final DeferredHolder<Block, HearthBlock> HEARTH = BLOCKS.register("hearth",
        () -> new HearthBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(3.5F)
            .requiresCorrectToolForDrops()
            .sound(SoundType.STONE)
            .lightLevel(state -> 13)
            .noOcclusion()));

    public static final DeferredHolder<Block, PlaqueBlock> PLAQUE = BLOCKS.register("plaque",
        () -> new PlaqueBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD)
            .strength(1.5F)
            .sound(SoundType.WOOD)
            // The plaque's own glow is its status light, not a lamp: bright
            // enough to read across a square, too dim to light a room (which
            // would let a plaque satisfy its own light requirement).
            .lightLevel(state -> state.getValue(PlaqueBlock.GLOW)
                == PlaqueBlock.Glow.GREEN ? 5 : 3)
            .noOcclusion()
            .noCollission()));

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
    }

    private ModBlocks() {
    }
}
