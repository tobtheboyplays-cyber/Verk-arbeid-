package com.hearthstead.item;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A crude goblin weapon. The same server-side poison rule is used by a
 * recovered player weapon and by a landed goblin melee strike.
 */
public final class PoopStickItem extends Item {
    public static final int POISON_TICKS = 100;
    public static final int POISON_AMPLIFIER = 0;

    public PoopStickItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
            java.util.List<net.minecraft.network.chat.Component> tooltip,
            net.minecraft.world.item.TooltipFlag flag) {
        tooltip.add(net.minecraft.network.chat.Component.translatable(
            "item.hearthstead.poop_stick.tooltip").withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target,
                             LivingEntity attacker) {
        applyPoison(target, attacker);
        return super.hurtEnemy(stack, target, attacker);
    }

    /** Applies only after the caller has established a real landed melee hit. */
    public static void applyPoison(LivingEntity target, LivingEntity attacker) {
        if (!target.level().isClientSide) {
            target.addEffect(new MobEffectInstance(MobEffects.POISON,
                POISON_TICKS, POISON_AMPLIFIER), attacker);
        }
    }
}
