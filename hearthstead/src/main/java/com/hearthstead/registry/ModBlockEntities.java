package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Hearthstead.MODID);

    public static final RegistryObject<BlockEntityType<HearthBlockEntity>> HEARTH =
        BLOCK_ENTITIES.register("hearth",
            () -> BlockEntityType.Builder.of(HearthBlockEntity::new, ModBlocks.HEARTH.get()).build(null));

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
    }

    private ModBlockEntities() {
    }
}
