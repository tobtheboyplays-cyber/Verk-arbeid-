package com.hearthstead.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The Builder's Plan (BUILDER lane, plan/BUILDER.md section 4): a rolled
 * drawing on a board. Right-click opens the Builder catalog (blueprints,
 * defense lines, Upgrade Orders, sites). While placing, the client-side
 * placement controller owns the clicks; this item only opens the screen.
 */
public class BuildersPlanItem extends Item {

    public BuildersPlanItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            com.hearthstead.client.ClientHooks.openBuilderPlan();
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.hearthstead.builders_plan.tooltip.1")
            .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.hearthstead.builders_plan.tooltip.2")
            .withStyle(ChatFormatting.GRAY));
    }
}
