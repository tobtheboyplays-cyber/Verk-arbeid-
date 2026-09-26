package com.hearthstead.item.role;

import com.hearthstead.entity.combat.role.HealLedger;
import com.hearthstead.entity.combat.role.RoleWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/**
 * A linen bandage (plan/BATTLE-ROLES.md §3). The Healer's main supply; a
 * player can also wrap their own wound: hold use for 1.5 s, then 8 HP comes
 * back over 8 s through the same heal-over-time ledger the Healer uses. A
 * bandage is never spent when the wearer already has as much healing queued.
 */
public class BandageItem extends Item {
    public static final int USE_TICKS = 30;
    private final float heal;

    public BandageItem(float heal, Properties properties) {
        super(properties);
        this.heal = heal;
    }

    public float healAmount() {
        return heal;
    }

    /** The heal a stack gives in a healer's hands, or 0 if it is no supply. */
    public static float supplyHeal(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0F;
        }
        if (stack.getItem() instanceof BandageItem bandage) {
            return bandage.heal;
        }
        return stack.is(com.hearthstead.registry.RoleItems.HEALING_HERBS)
            ? HealLedger.HERB_TOTAL : 0.0F;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return USE_TICKS;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.getHealth() >= player.getMaxHealth()) {
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity user) {
        if (level instanceof ServerLevel server
            && RoleWorld.startHeal(server, user, heal)) {
            server.playSound(null, user.blockPosition(),
                com.hearthstead.registry.RoleItems.BANDAGE_SOUND.get(), SoundSource.PLAYERS,
                0.8F, 1.0F);
            if (!(user instanceof Player p && p.getAbilities().instabuild)) {
                stack.shrink(1);
            }
        }
        return stack;
    }
}
