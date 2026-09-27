package com.hearthstead.item.role;

import com.hearthstead.Hearthstead;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * The battle-role weapons (plan/BATTLE-ROLES.md §1-2): spears and
 * longswords. Real tiered weapons (durability, repair, enchanting as a
 * sword) so they sit in the gear-tier ladder by their {@link Tier} without a
 * special case. Deliberately NOT in {@code #minecraft:swords}: a Guard's
 * sword request never takes a spear, and a spear never counts as the Guard's
 * physical sword.
 *
 * <p>For settlers the spear's reach is the goal's own 1.5x rule; for a player
 * holding one it is a +1 block entity-interaction range in the main hand.
 */
public class RoleWeaponItem extends SwordItem {
    public enum Kind { SPEAR, LONGSWORD }

    private final Kind kind;

    public RoleWeaponItem(Kind kind, Tier tier, Item.Properties properties) {
        super(tier, properties.attributes(attributes(kind, tier)));
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isSpear() {
        return kind == Kind.SPEAR;
    }

    public boolean isLongsword() {
        return kind == Kind.LONGSWORD;
    }

    /**
     * Spear: +2 over the tier bonus (wood 2, iron 4, diamond 5), -2.8 speed,
     * +1 player reach. Longsword: +4 over the bonus (iron 6, diamond 7),
     * -3.0 speed (slow, heavy, two-handed).
     */
    static ItemAttributeModifiers attributes(Kind kind, Tier tier) {
        float base = kind == Kind.SPEAR ? 2.0F : 4.0F;
        float speed = kind == Kind.SPEAR ? -2.8F : -3.0F;
        ItemAttributeModifiers.Builder b = ItemAttributeModifiers.builder()
            .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE_ID,
                base + tier.getAttackDamageBonus(), AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND)
            .add(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_ATTACK_SPEED_ID,
                speed, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
        if (kind == Kind.SPEAR) {
            b.add(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(
                Hearthstead.id("spear_reach"), 1.0D, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND);
        }
        return b.build();
    }
}
