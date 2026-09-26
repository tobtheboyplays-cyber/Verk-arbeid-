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
                // Match vanilla's villager-tuned doorway width. At 0.62 a
                // SOUTH/LEFT open door left only 0.0025 blocks of lateral
                // tolerance, so tiny path offsets could wedge a settler on
                // the sill even though the door was visibly open.
                .sized(0.60F, 1.95F)
                .clientTrackingRange(10)
                .build("settler"));

    public static final DeferredHolder<EntityType<?>, EntityType<RaiderEntity>> RAIDER =
        ENTITY_TYPES.register("raider",
            () -> EntityType.Builder.of(RaiderEntity::new, MobCategory.MONSTER)
                .sized(0.6F, 1.9F)
                .clientTrackingRange(12)
                .build("raider"));

    public static final DeferredHolder<EntityType<?>, EntityType<com.hearthstead.entity.TavernSeatEntity>> TAVERN_SEAT =
        ENTITY_TYPES.register("tavern_seat",
            () -> EntityType.Builder.of(com.hearthstead.entity.TavernSeatEntity::new, MobCategory.MISC)
                .sized(0.1F, 0.1F).clientTrackingRange(10).updateInterval(20)
                .build("tavern_seat"));

    public static final DeferredHolder<EntityType<?>, EntityType<com.hearthstead.entity.TavernServingEntity>> TAVERN_SERVING =
        ENTITY_TYPES.register("tavern_serving", () -> EntityType.Builder
            .<com.hearthstead.entity.TavernServingEntity>of(com.hearthstead.entity.TavernServingEntity::new, MobCategory.MISC)
            .sized(.25F, .25F).clientTrackingRange(10).updateInterval(1).build("tavern_serving"));

    public static final DeferredHolder<EntityType<?>, EntityType<com.hearthstead.entity.FisherSeatEntity>> FISHER_SEAT =
        ENTITY_TYPES.register("fisher_seat", () -> EntityType.Builder.of(com.hearthstead.entity.FisherSeatEntity::new, MobCategory.MISC)
            .sized(.1F, .1F).clientTrackingRange(10).updateInterval(20).build("fisher_seat"));

    public static void register(IEventBus bus) {
        ENTITY_TYPES.register(bus);
    }

    public static final DeferredHolder<EntityType<?>, EntityType<com.hearthstead.entity.FallingTreeEntity>> FALLING_TREE =
        ENTITY_TYPES.register("falling_tree", () -> EntityType.Builder
            .<com.hearthstead.entity.FallingTreeEntity>of(com.hearthstead.entity.FallingTreeEntity::new, MobCategory.MISC)
            .sized(.1F,.1F).clientTrackingRange(12).updateInterval(20).build("falling_tree"));

    private ModEntities() {
    }
}
