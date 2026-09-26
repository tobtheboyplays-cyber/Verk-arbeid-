package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.ModComponents;
import com.hearthstead.settlement.work.CraftedQuality;
import com.hearthstead.settlement.work.GoodsQuality;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * The coloured grade line under the item name. Shown on every stack that
 * carries a grade, and as "Basic quality" on every good a settler makes or
 * gathers (workshop outputs that can be graded, logs, planks, crops) so the
 * player can tell a Basic good from a graded one at a glance. Graded goods
 * also say what the grade did: extra durability or saturation. Attack damage
 * and toughness bonuses appear in vanilla's own attribute lines.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class GoodsQualityTooltip {
    private GoodsQualityTooltip() {}

    @SubscribeEvent
    public static void append(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !shown(stack)) return;
        int quality = GoodsQuality.of(stack);
        event.getToolTip().add(Component.translatable("hearthstead.goods_quality." + key(quality))
            .withStyle(color(quality)));
        if (quality <= GoodsQuality.BASIC) return;
        int durability = CraftedQuality.realDurabilityBonusPercent(stack);
        if (durability > 0) {
            event.getToolTip().add(Component.translatableWithFallback(
                "hearthstead.goods_quality.durability", "Durability +%s%%", durability)
                .withStyle(ChatFormatting.DARK_GREEN));
        }
        int saturation = CraftedQuality.realSaturationBonusPercent(stack);
        if (saturation > 0) {
            event.getToolTip().add(Component.translatableWithFallback(
                "hearthstead.goods_quality.saturation", "Saturation +%s%%", saturation)
                .withStyle(ChatFormatting.DARK_GREEN));
        }
    }

    public static boolean shown(ItemStack stack) {
        if (stack.has(ModComponents.GOODS_QUALITY.get())) return true;
        if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS) || stack.is(Items.WHEAT)
            || stack.is(Items.CARROT) || stack.is(Items.POTATO) || stack.is(Items.BEETROOT)) return true;
        return CraftedQuality.bearsQuality(stack)
            && com.hearthstead.building.Production.isWorkshopOutput(stack.getItem());
    }

    public static String key(int quality) {
        return switch (quality) {
            case GoodsQuality.FINE -> "fine";
            case GoodsQuality.SUPERIOR -> "superior";
            case GoodsQuality.EXCEPTIONAL -> "exceptional";
            case GoodsQuality.MASTERWORK -> "masterwork";
            case GoodsQuality.LEGENDARY -> "legendary";
            default -> "basic";
        };
    }

    public static ChatFormatting color(int quality) {
        return switch (quality) {
            case GoodsQuality.FINE -> ChatFormatting.GREEN;
            case GoodsQuality.SUPERIOR -> ChatFormatting.AQUA;
            case GoodsQuality.EXCEPTIONAL -> ChatFormatting.GOLD;
            case GoodsQuality.MASTERWORK -> ChatFormatting.LIGHT_PURPLE;
            case GoodsQuality.LEGENDARY -> ChatFormatting.DARK_PURPLE;
            default -> ChatFormatting.GRAY;
        };
    }
}
