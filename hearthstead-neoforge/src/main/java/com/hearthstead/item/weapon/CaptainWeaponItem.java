package com.hearthstead.item.weapon;

import com.hearthstead.Hearthstead;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;

import java.util.List;

/**
 * Short sword, double axe, halberd and warhammer in every material tier (weapons lane, 26 Sep;
 * plan/WEAPONS.md). Real tiered weapons: durability, repair material and enchantability come from
 * the {@link Tier}, like a vanilla sword. Traits live in {@link WeaponTraits}.
 *
 * <ul>
 *   <li>Players: the halberd's reach is an entity-interaction-range modifier, the warhammer's
 *       knockback an attack-knockback modifier, the trait bonus comes through
 *       {@link #getAttackDamageBonus}, the stun through {@link #postHurtEnemy}.</li>
 *   <li>Settlers (the Captain): {@link WeaponEvents} adds the same bonus and stun to their melee
 *       hits; reach and the two-handed rule are the battle-roles lane's AI.</li>
 * </ul>
 */
public class CaptainWeaponItem extends SwordItem {
    private final WeaponType type;

    public CaptainWeaponItem(WeaponType type, Tier tier, Item.Properties properties) {
        super(tier, properties.attributes(attributes(type, tier)));
        this.type = type;
    }

    public WeaponType type() {
        return type;
    }

    public boolean isTwoHanded() {
        return type.twoHanded();
    }

    public static ItemAttributeModifiers attributes(WeaponType type, Tier tier) {
        ItemAttributeModifiers.Builder b = ItemAttributeModifiers.builder()
            .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE_ID,
                type.baseDamage() + tier.getAttackDamageBonus(), AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND)
            .add(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_ATTACK_SPEED_ID,
                type.speed(), AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
        if (type == WeaponType.HALBERD) {
            b.add(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(Hearthstead.id("halberd_reach"),
                WeaponType.HALBERD_REACH, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
        }
        if (type == WeaponType.WARHAMMER) {
            b.add(Attributes.ATTACK_KNOCKBACK, new AttributeModifier(Hearthstead.id("warhammer_knockback"),
                WeaponType.WARHAMMER_KNOCKBACK, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
        }
        return b.build();
    }

    @Override
    public boolean canPerformAction(ItemStack stack, ItemAbility ability) {
        if (ability == ItemAbilities.SWORD_SWEEP && !type.sweeps()) {
            return false;
        }
        return super.canPerformAction(stack, ability);
    }

    @Override
    public boolean canDisableShield(ItemStack stack, ItemStack shield, LivingEntity entity, LivingEntity attacker) {
        return type.breaksShields() && WeaponTraits.enabled();
    }

    @Override
    public float getAttackDamageBonus(Entity target, float baseDamage, DamageSource source) {
        LivingEntity attacker = source.getEntity() instanceof LivingEntity l ? l : null;
        return super.getAttackDamageBonus(target, baseDamage, source)
            + WeaponTraits.bonus(type, attacker, target, baseDamage);
    }

    @Override
    public void postHurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        super.postHurtEnemy(stack, target, attacker);
        if (!attacker.level().isClientSide) {
            WeaponTraits.rollStun(type, target, attacker.getRandom().nextFloat());
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, lines, flag);
        String k = "item.hearthstead.weapon." + type.id();
        lines.add(Component.translatable(k + ".trait").withStyle(ChatFormatting.GOLD));
        if (type.twoHanded()) {
            lines.add(Component.translatable("item.hearthstead.weapon.two_handed").withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.translatable(k + ".detail").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable(k + ".captain").withStyle(ChatFormatting.DARK_AQUA));
    }
}
