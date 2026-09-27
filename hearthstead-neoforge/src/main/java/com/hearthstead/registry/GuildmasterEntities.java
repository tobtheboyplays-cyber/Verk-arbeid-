package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.GuildmasterEntity;
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
 * The Guildmaster entity type, registered here (mod bus) so the shared
 * ModEntities/ModBusEvents registries stay untouched.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class GuildmasterEntities {
    public static final ResourceLocation GUILDMASTER_ID = Hearthstead.id("guildmaster");

    public static final DeferredHolder<EntityType<?>, EntityType<GuildmasterEntity>> GUILDMASTER =
        DeferredHolder.create(Registries.ENTITY_TYPE, GUILDMASTER_ID);

    private GuildmasterEntities() {
    }

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(Registries.ENTITY_TYPE, helper -> helper.register(GUILDMASTER_ID,
            EntityType.Builder.<GuildmasterEntity>of(GuildmasterEntity::new, MobCategory.MISC)
                // Seated on his stool: lower than a standing settler.
                .sized(0.7F, 1.55F)
                .eyeHeight(1.35F)
                .fireImmune()
                .clientTrackingRange(10)
                .build("guildmaster")));
    }

    @SubscribeEvent
    public static void attributes(EntityAttributeCreationEvent event) {
        event.put(GUILDMASTER.get(), GuildmasterEntity.createAttributes().build());
    }
}
