package com.hearthstead.item.weapon;

import com.hearthstead.Hearthstead;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Settler / mob side of the captain weapon traits. Players get them from the item itself
 * ({@link CaptainWeaponItem#getAttackDamageBonus} / {@code postHurtEnemy}); a mob's melee
 * ({@code Mob.doHurtTarget}, or a role goal's direct {@code hurt}) never calls the item, so a
 * direct melee hit from a non-player holding a captain weapon gets the bonus and the stun here.
 * The bonus must change the incoming amount; the stun waits for the hit to land (T30): incoming
 * damage fires before a shield block, the invulnerability-frame early return and later
 * cancels, none of which may stun -- the player's {@code postHurtEnemy} never does either.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class WeaponEvents {
    private WeaponEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity attacker = meleeAttacker(event.getSource());
        WeaponType type = attacker == null ? null : WeaponTraits.typeOf(attacker.getMainHandItem());
        if (type == null || !WeaponTraits.enabled()) {
            return;
        }
        float extra = WeaponTraits.bonus(type, attacker, event.getEntity(), event.getAmount());
        if (extra > 0.0F) {
            event.setAmount(event.getAmount() + extra);
        }
    }

    /** The stun: only a hit that really cost health (a full shield block reaches here as 0). */
    @SubscribeEvent
    public static void onDamageApplied(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0.0F) {
            return;
        }
        LivingEntity attacker = meleeAttacker(event.getSource());
        WeaponType type = attacker == null ? null : WeaponTraits.typeOf(attacker.getMainHandItem());
        if (type == null || !WeaponTraits.enabled()) {
            return;
        }
        WeaponTraits.rollStun(type, event.getEntity(), attacker.getRandom().nextFloat());
    }

    /** A non-player's own direct melee hit, or null. */
    private static LivingEntity meleeAttacker(DamageSource source) {
        if (!(source.getEntity() instanceof LivingEntity attacker) || attacker instanceof Player
            || source.getDirectEntity() != attacker) {
            return null;
        }
        return attacker;
    }
}
