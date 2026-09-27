package com.hearthstead.event.worldevent;

import com.hearthstead.Hearthstead;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Entity types of the small world events, registered here (mod bus) so the
 * shared ModEntities/ModBusEvents registries stay untouched.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class WorldEventEntities {
    public static final ResourceLocation PACK_WOLF_ID = Hearthstead.id("pack_wolf");
    public static final ResourceLocation WILD_BOAR_ID = Hearthstead.id("wild_boar");

    public static final DeferredHolder<EntityType<?>, EntityType<PackWolfEntity>> PACK_WOLF =
        DeferredHolder.create(Registries.ENTITY_TYPE, PACK_WOLF_ID);
    public static final DeferredHolder<EntityType<?>, EntityType<WildBoarEntity>> WILD_BOAR =
        DeferredHolder.create(Registries.ENTITY_TYPE, WILD_BOAR_ID);

    private WorldEventEntities() {
    }

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(Registries.ENTITY_TYPE, helper -> {
            helper.register(PACK_WOLF_ID, EntityType.Builder
                .<PackWolfEntity>of(PackWolfEntity::new, MobCategory.MONSTER)
                .sized(0.6F, 0.85F).eyeHeight(0.68F).clientTrackingRange(10)
                .build("pack_wolf"));
            helper.register(WILD_BOAR_ID, EntityType.Builder
                .<WildBoarEntity>of(WildBoarEntity::new, MobCategory.MONSTER)
                .sized(1.1F, 1.05F).clientTrackingRange(10)
                .build("wild_boar"));
        });
    }

    @SubscribeEvent
    public static void attributes(EntityAttributeCreationEvent event) {
        event.put(PACK_WOLF.get(), PackWolfEntity.createAttributes().build());
        event.put(WILD_BOAR.get(), WildBoarEntity.createAttributes().build());
    }
}
