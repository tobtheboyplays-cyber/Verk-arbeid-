package com.hearthstead.item.weapon;

import com.hearthstead.Hearthstead;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Settler / mob side of the captain weapon traits. Players get them from the item itself
 * ({@link CaptainWeaponItem#getAttackDamageBonus} / {@code postHurtEnemy}); a mob's melee
 * ({@code Mob.doHurtTarget}, or a role goal's direct {@code hurt}) never calls the item, so a
 * direct melee hit from a non-player holding a captain weapon gets the bonus and the stun here.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class WeaponEvents {
    private WeaponEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof LivingEntity attacker) || attacker instanceof Player
            || source.getDirectEntity() != attacker) {
            return;
        }
        ItemStack weapon = attacker.getMainHandItem();
        WeaponType type = WeaponTraits.typeOf(weapon);
        if (type == null || !WeaponTraits.enabled()) {
            return;
        }
        LivingEntity target = event.getEntity();
        float extra = WeaponTraits.bonus(type, attacker, target, event.getAmount());
        if (extra > 0.0F) {
            event.setAmount(event.getAmount() + extra);
        }
        WeaponTraits.rollStun(type, target, attacker.getRandom().nextFloat());
    }
}
