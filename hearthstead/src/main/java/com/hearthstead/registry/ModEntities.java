package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Hearthstead.MODID);

    public static final RegistryObject<EntityType<SettlerEntity>> SETTLER =
        ENTITY_TYPES.register("settler",
            () -> EntityType.Builder.of(SettlerEntity::new, MobCategory.CREATURE)
                .sized(0.62F, 1.95F)
                .clientTrackingRange(10)
                .build("settler"));

    public static void register(IEventBus bus) {
        ENTITY_TYPES.register(bus);
    }

    private ModEntities() {
    }
}
