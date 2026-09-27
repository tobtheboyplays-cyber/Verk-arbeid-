package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Item data components. */
public final class ModComponents {
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, Hearthstead.MODID);

    /**
     * Which building a plaque is dedicated to. Stored as the type's id rather
     * than its ordinal so that adding or reordering building types can never
     * silently turn someone's warehouse plaque into a farmhouse.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>>
        BUILDING_TYPE = COMPONENTS.register("building_type",
            () -> DataComponentType.<String>builder()
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>>
        GOODS_QUALITY = COMPONENTS.register("goods_quality",
            () -> DataComponentType.<Integer>builder()
                .persistent(com.hearthstead.settlement.work.GoodsQuality.CODEC)
                .networkSynchronized(com.hearthstead.settlement.work.GoodsQuality.STREAM_CODEC)
                .build());

    /** Hunter carcass: species and the exact vanilla death loot it replaced. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<com.hearthstead.item.CarcassData>>
        CARCASS = COMPONENTS.register("carcass",
            () -> DataComponentType.<com.hearthstead.item.CarcassData>builder()
                .persistent(com.hearthstead.item.CarcassData.CODEC)
                .networkSynchronized(com.hearthstead.item.CarcassData.STREAM_CODEC)
                .build());

    public static void register(IEventBus bus) {
        COMPONENTS.register(bus);
    }

    private ModComponents() {
    }
}
