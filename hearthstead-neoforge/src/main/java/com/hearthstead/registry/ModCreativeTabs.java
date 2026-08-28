package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Hearthstead.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN =
        TABS.register("hearthstead", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.hearthstead"))
            .icon(() -> new ItemStack(ModItems.HEARTH.get()))
            .displayItems((params, output) -> {
                // EVERYTHING the mod registers, in play order -- found live
                // (2026-08-26, the owner's first demo session): this used to
                // list three items, so the plaque, the build plans and every
                // chain good were simply absent from creative, and the only
                // way to test a building was to craft its plan in survival.
                // The tab is the mod's own index; an item missing here reads
                // as an item that does not exist.
                output.accept(ModItems.HEARTH.get());
                output.accept(ModItems.PLAQUE.get());
                output.accept(ModItems.BUILD_PLAN.get());
                output.accept(ModItems.HANDBOOK.get());
                output.accept(ModItems.SETTLER_SPAWN_EGG.get());
                // The chain goods, in FLOWS order.
                output.accept(ModItems.FLOUR.get());
                output.accept(ModItems.MALT.get());
                output.accept(ModItems.ALE.get());
                output.accept(ModItems.IRON_BLOOM.get());
                output.accept(ModItems.TIMBER_BEAM.get());
                output.accept(ModItems.CURED_HIDE.get());
                output.accept(ModItems.WOOL_BOLT.get());
                // One pre-stamped Build Plan per building type, so any
                // building can be tested from creative without crafting its
                // plan first -- the fastest route from "I want to see the
                // sawmill" to a green plaque.
                for (com.hearthstead.building.BuildingType type
                        : com.hearthstead.building.BuildingType.values()) {
                    output.accept(com.hearthstead.block.PlaqueItemData.stamped(
                        new ItemStack(ModItems.BUILD_PLAN.get()), type));
                }
            })
            .build());

    public static void register(IEventBus bus) {
        TABS.register(bus);
    }

    private ModCreativeTabs() {
    }
}
