package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Hearthstead.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<HearthBlockEntity>>
        HEARTH = BLOCK_ENTITIES.register("hearth",
            () -> BlockEntityType.Builder.of(HearthBlockEntity::new, ModBlocks.HEARTH.get())
                .build(null));

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
    }

    private ModBlockEntities() {
    }
}
