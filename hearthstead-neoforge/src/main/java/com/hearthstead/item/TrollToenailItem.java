package com.hearthstead.item;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** An always-edible unpleasant trophy with a deliberately severe finish. */
public final class TrollToenailItem extends Item {
    public static final int NAUSEA_TICKS = 200;
    public static final int NAUSEA_AMPLIFIER = 0;

    public TrollToenailItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
            java.util.List<net.minecraft.network.chat.Component> tooltip,
            net.minecraft.world.item.TooltipFlag flag) {
        tooltip.add(net.minecraft.network.chat.Component.translatable(
            "item.hearthstead.troll_toenail.tooltip").withStyle(net.minecraft.ChatFormatting.RED));
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level,
                                     LivingEntity consumer) {
        ItemStack remaining = super.finishUsingItem(stack, level, consumer);
        if (!level.isClientSide && consumer instanceof Player player) {
            // This is a current-health consequence, never a max-health change.
            player.setHealth(1.0F);
            player.addEffect(new MobEffectInstance(MobEffects.CONFUSION,
                NAUSEA_TICKS, NAUSEA_AMPLIFIER), player);
        }
        return remaining;
    }
}
