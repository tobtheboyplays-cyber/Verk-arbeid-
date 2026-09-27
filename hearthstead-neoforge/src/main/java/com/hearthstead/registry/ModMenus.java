package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.menu.SettlerInventoryMenu;
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

    /**
     * The settler's real carried inventory. Unlike the inspection sheet this
     * is a vanilla container menu: slot changes are authored by the server and
     * synchronized through the ordinary menu protocol.
     */
    public static final DeferredHolder<MenuType<?>, MenuType<SettlerInventoryMenu>>
        SETTLER_INVENTORY = MENUS.register("settler_inventory",
            () -> IMenuTypeExtension.create(SettlerInventoryMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<com.hearthstead.menu.FishRackMenu>> FISH_RACK =
        MENUS.register("fish_rack", () -> IMenuTypeExtension.create(com.hearthstead.menu.FishRackMenu::new));

    public static void register(IEventBus bus) {
        MENUS.register(bus);
    }

    private ModMenus() {
    }
}
