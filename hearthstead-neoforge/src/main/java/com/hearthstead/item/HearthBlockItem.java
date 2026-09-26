package com.hearthstead.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

import java.util.List;

public class HearthBlockItem extends BlockItem {
    public HearthBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        // The Banner's pole and cloth need room; say so instead of failing silently.
        if (context.canPlace()
            && !com.hearthstead.block.HearthBlock.hasClearance(context.getLevel(), context.getClickedPos())) {
            if (!context.getLevel().isClientSide && context.getPlayer() != null) {
                context.getPlayer().displayClientMessage(
                    Component.translatable("hearthstead.settlement_banner.needs_space")
                        .withStyle(ChatFormatting.RED), true);
            }
            return InteractionResult.FAIL;
        }
        return super.place(context);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.hearthstead.hearth.tooltip1")
            .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.hearthstead.hearth.tooltip2")
            .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("hearthstead.settlement_banner.heraldry_hint")
            .withStyle(ChatFormatting.DARK_GRAY));
    }
}
