package com.hearthstead.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/** Physical survival tool for authoritative Lumber and Farm Work Zones. */
public final class WorkScepterItem extends Item {
    public WorkScepterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                   LivingEntity target,
                                                   InteractionHand hand) {
        // The client router captures main-hand right-click for Work Zone
        // selection before this method runs. Consume here as the server-side
        // fallback so a delayed interaction cannot use the target normally.
        return hand == InteractionHand.MAIN_HAND
            ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        // Work Scepter world actions are captured by WorkZoneClient's
        // right-click input. This server fallback must not open another UI.
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("hearthstead.work_scepter.tooltip.1")
            .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("hearthstead.work_scepter.tooltip.2")
            .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("hearthstead.work_scepter.tooltip.3")
            .withStyle(ChatFormatting.DARK_GRAY));
    }
}
