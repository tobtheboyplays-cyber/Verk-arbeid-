package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.hearthstead.menu.HearthMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(Registries.MENU, Hearthstead.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<HearthMenu>> HEARTH =
        MENUS.register("hearth", () -> IMenuTypeExtension.create(HearthMenu::new));

    public static void register(IEventBus bus) {
        MENUS.register(bus);
    }

    private ModMenus() {
    }
}
