package com.hearthstead.client;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.screen.CoinMerchantScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Promotes only a vanilla merchant whose received offers contain a Hearthstead Coin.
 * The active MerchantMenu must survive the visual hand-off: AbstractContainerScreen's
 * normal removed() method closes that menu, so this adapter initializes the replacement
 * in place instead of calling Minecraft.setScreen().
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class CoinMerchantScreenAdapter {
    private static final java.util.Map<MerchantMenu, Integer> PURSES = new java.util.WeakHashMap<>();

    public static void acceptPurse(com.hearthstead.network.MerchantPursePayload payload) {
        var player = Minecraft.getInstance().player;
        if (player != null && player.containerMenu instanceof MerchantMenu menu
                && menu.containerId == payload.containerId()) {
            PURSES.put(menu, payload.coins());
        }
    }

    /** Unknown and legacy menus have no shared-budget claim. */
    public static int purse(MerchantMenu menu) {
        return PURSES.getOrDefault(menu, -1);
    }

    private CoinMerchantScreenAdapter() {
    }

    @SubscribeEvent
    public static void afterClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof MerchantScreen vanillaScreen)
            || minecraft.player == null) {
            return;
        }

        MerchantMenu menu = vanillaScreen.getMenu();
        if (!CoinMerchantScreen.supports(menu)) {
            return;
        }

        CoinMerchantScreen replacement = new CoinMerchantScreen(menu,
            minecraft.player.getInventory(), vanillaScreen.getTitle());

        // Cached 1.21.1 Minecraft#setScreen calls old.removed(); for an
        // AbstractContainerScreen that invokes MerchantMenu.removed(player) and
        // closes the live trade. We keep the same menu and replace only its
        // client view, then run the same added/init steps for the new screen.
        minecraft.screen = replacement;
        replacement.added();
        replacement.init(minecraft, minecraft.getWindow().getGuiScaledWidth(),
            minecraft.getWindow().getGuiScaledHeight());
    }
}
