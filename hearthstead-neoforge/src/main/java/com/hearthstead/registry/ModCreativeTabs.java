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
                output.accept(ModItems.HEARTH.get());
                output.accept(ModItems.HANDBOOK.get());
                output.accept(ModItems.SETTLER_SPAWN_EGG.get());
            })
            .build());

    public static void register(IEventBus bus) {
        TABS.register(bus);
    }

    private ModCreativeTabs() {
    }
}
