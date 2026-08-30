package com.hearthstead.item;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.workzone.WorkZoneService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
        if (hand != InteractionHand.MAIN_HAND
            || !(target instanceof SettlerEntity settler)) {
            return InteractionResult.PASS;
        }
        if (player.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            WorkZoneService.selectWorker(serverPlayer, settler);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.FAIL;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (context.getPlayer() instanceof ServerPlayer player) {
            WorkZoneService.setFirstCorner(player, context.getClickedPos());
            return InteractionResult.CONSUME;
        }
        return InteractionResult.FAIL;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.hearthstead.work_scepter.tooltip.1")
            .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.hearthstead.work_scepter.tooltip.2")
            .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.hearthstead.work_scepter.tooltip.3")
            .withStyle(ChatFormatting.DARK_GRAY));
    }
}
