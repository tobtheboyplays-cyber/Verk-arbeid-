package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.hearthstead.menu.HearthMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(ForgeRegistries.MENU_TYPES, Hearthstead.MODID);

    public static final RegistryObject<MenuType<HearthMenu>> HEARTH =
        MENUS.register("hearth", () -> IForgeMenuType.create(HearthMenu::new));

    public static void register(IEventBus bus) {
        MENUS.register(bus);
    }

    private ModMenus() {
    }
}
