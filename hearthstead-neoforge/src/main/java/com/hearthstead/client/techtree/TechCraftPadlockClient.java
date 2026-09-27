package com.hearthstead.client.techtree;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.settlement.techtree.TechNodeDef;
import com.hearthstead.settlement.techtree.TechTreeData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tech-gated crafting, client side: when the grid matches a recipe whose
 * node is not learned, the (server-emptied) result slot shows the item
 * greyed with a padlock, and hovering it says which node unlocks it.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class TechCraftPadlockClient {
    private TechCraftPadlockClient() {
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int w;
        int h;
        if (menu instanceof CraftingMenu) {
            w = 3;
            h = 3;
        } else if (menu instanceof InventoryMenu) {
            w = 2;
            h = 2;
        } else {
            return;
        }
        if (menu.slots.size() <= w * h || !menu.getSlot(0).getItem().isEmpty()
            || !TechKnowledgeClient.gatingOn()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        List<ItemStack> items = new ArrayList<>(w * h);
        boolean any = false;
        for (int i = 1; i <= w * h; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            items.add(stack);
            any |= !stack.isEmpty();
        }
        if (!any) {
            return;
        }
        CraftingInput input = CraftingInput.of(w, h, items);
        Optional<RecipeHolder<CraftingRecipe>> recipe = mc.level.getRecipeManager()
            .getRecipeFor(RecipeType.CRAFTING, input, mc.level);
        if (recipe.isEmpty()) {
            return;
        }
        ItemStack out = recipe.get().value().assemble(input, mc.level.registryAccess());
        Optional<String> node = TechKnowledgeClient.locked(recipe.get().id(), out.getItem());
        if (node.isEmpty()) {
            return;
        }
        Slot result = menu.getSlot(0);
        int x = screen.getGuiLeft() + result.x;
        int y = screen.getGuiTop() + result.y;
        GuiGraphics g = event.getGuiGraphics();
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        g.renderItem(out, x, y);
        g.pose().translate(0, 0, 150);
        g.fill(x, y, x + 16, y + 16, 0xA8D9D0BE);
        g.fill(x + 9, y + 8, x + 16, y + 16, Ui2Palette.INK_DISABLED);
        Ui2Surface.lockGlyph(g, x + 10, y + 9, Ui2Palette.ON_ACCENT);
        g.pose().popPose();
        int mx = event.getMouseX();
        int my = event.getMouseY();
        if (mx >= x && mx < x + 16 && my >= y && my < y + 16) {
            TechNodeDef def = TechTreeData.get().node(node.get());
            Component name = def == null ? Component.literal(node.get()) : def.displayName();
            List<FormattedCharSequence> lines = new ArrayList<>();
            lines.add(out.getHoverName().getVisualOrderText());
            lines.add(Component.translatableWithFallback("hearthstead.techtree.craft_locked",
                "Unlocked by: %s (Tech Tree)", name).withColor(0xFFE0B070).getVisualOrderText());
            g.renderTooltip(mc.font, lines, mx, my);
        }
    }
}
