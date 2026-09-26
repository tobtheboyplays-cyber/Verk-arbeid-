package com.hearthstead.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/**
 * The Survey Rod (BUILDER lane; MineColonies' scan tool): right-click two
 * corners of something you built by hand, name it, and it becomes a design
 * in the Builder's catalog under "Our Designs". The client-side controller
 * owns the clicks; this is only the server fallback.
 */
public class SurveyRodItem extends Item {

    public SurveyRodItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("item.hearthstead.survey_rod.tooltip.1").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.hearthstead.survey_rod.tooltip.2").withStyle(ChatFormatting.GRAY));
    }
}
