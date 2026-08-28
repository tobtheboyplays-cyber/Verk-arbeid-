package com.hearthstead.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

public class HearthBlockItem extends BlockItem {
    public HearthBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.hearthstead.hearth.tooltip1")
            .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.hearthstead.hearth.tooltip2")
            .withStyle(ChatFormatting.GRAY));
    }
}
