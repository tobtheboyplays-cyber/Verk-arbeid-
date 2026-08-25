package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(Registries.ENTITY_TYPE, Hearthstead.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<SettlerEntity>> SETTLER =
        ENTITY_TYPES.register("settler",
            () -> EntityType.Builder.of(SettlerEntity::new, MobCategory.CREATURE)
                .sized(0.62F, 1.95F)
                .clientTrackingRange(10)
                .build("settler"));

    public static final DeferredHolder<EntityType<?>, EntityType<RaiderEntity>> RAIDER =
        ENTITY_TYPES.register("raider",
            () -> EntityType.Builder.of(RaiderEntity::new, MobCategory.MONSTER)
                .sized(0.6F, 1.9F)
                .clientTrackingRange(12)
                .build("raider"));

    public static void register(IEventBus bus) {
        ENTITY_TYPES.register(bus);
    }

    private ModEntities() {
    }
}
