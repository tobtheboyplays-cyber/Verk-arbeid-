package com.hearthstead.client.ui2.handbook;

import com.hearthstead.Hearthstead;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;

/**
 * In-world help on every catalogued mod item: one grey "how you use it"
 * line, and under Shift the 2-4 numbered steps plus the key that opens its
 * handbook page. Driven entirely by {@link HandbookItems}, so an item added
 * to the catalog gets its tooltip with no code.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class ItemHowToTooltips {
    private ItemHowToTooltips() {
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().isEmpty()) return;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem());
        if (!Hearthstead.MODID.equals(id.getNamespace())) return;
        HandbookItems.Family family = HandbookLoader.items().familyOf(id.toString());
        if (family == null || family.isInternal()) return;
        List<Component> tip = event.getToolTip();
        // Insert right under the name, above the item's own lines and the advanced id.
        int at = Math.min(1, tip.size());
        if (family.useKey() != null) {
            tip.add(at++, Component.translatable(family.useKey()).withStyle(ChatFormatting.GRAY));
        }
        if (family.steps().isEmpty()) return;
        if (Screen.hasShiftDown()) {
            int n = 1;
            for (String step : family.steps()) {
                tip.add(at++, Component.literal(n++ + ". ").withStyle(ChatFormatting.GOLD)
                    .append(Component.translatable(step).withStyle(ChatFormatting.GRAY)));
            }
            if (family.page() != null && !HandbookHints.OPEN_KEY.isUnbound()) {
                tip.add(at, Component.translatable("hearthstead.howto.handbook",
                        HandbookHints.OPEN_KEY.getTranslatedKeyMessage())
                    .withStyle(ChatFormatting.DARK_GRAY));
            }
        } else {
            tip.add(at, Component.translatable("hearthstead.howto.shift")
                .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** Resource or datapack reload: drop the cached catalog and textures. */
    @SubscribeEvent
    public static void onRecipes(RecipesUpdatedEvent event) {
        HandbookLoader.invalidate();
        HandbookHints.invalidate();
    }
}
