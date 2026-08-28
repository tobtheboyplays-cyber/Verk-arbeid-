package com.hearthstead.item;

import com.hearthstead.client.ClientHooks;
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

public class HandbookItem extends Item {
    public HandbookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player,
                                                  InteractionHand hand) {
        // Sneak-use asks the server what the settlement is storing; a
        // plain use opens the handbook. The request carries no arguments --
        // the server resolves the settlement from the sender's position.
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                com.hearthstead.network.StorageNetwork.handleRequest(sp);
            }
        } else if (level.isClientSide) {
            ClientHooks.openHandbook();
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),
            level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.hearthstead.handbook.tooltip")
            .withStyle(ChatFormatting.GRAY));
    }
}
