package com.hearthstead.item;

import com.hearthstead.settlement.guard.patrol.PatrolService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The Patrol Map (PATROL ROUTES lane): the player's tool for drawing guard
 * patrol routes. Right-click the ground to set the next numbered waypoint,
 * click marker 1 to close the loop, sneak-click a marker to remove it;
 * right-click the air for the route screen (pick guards, per-shift count,
 * formation). While held, the selected settlement's routes show in the
 * world. Everything is decided on the server ({@link PatrolService}).
 */
public final class PatrolMapItem extends Item {
    public PatrolMapItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() instanceof ServerPlayer player) {
            PatrolService.useOnBlock(player, context.getHand(), context.getClickedPos(), context.getClickedFace());
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            PatrolService.openFor(serverPlayer, hand);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("item.hearthstead.patrol_map.tooltip.1").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.hearthstead.patrol_map.tooltip.2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.hearthstead.patrol_map.tooltip.3").withStyle(ChatFormatting.GRAY));
    }
}
