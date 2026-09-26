package com.hearthstead.entity;

import com.hearthstead.settlement.Employment;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Employment-derived endurance and footing; changing jobs never heals. */
public final class GuardHealth {
    private static final ResourceLocation CAPACITY =
        ResourceLocation.fromNamespaceAndPath("hearthstead", "guard_health_capacity");
    private static final double EXTRA_HEALTH = 56.0;
    private static final ResourceLocation FOOTING =
        ResourceLocation.fromNamespaceAndPath("hearthstead", "guard_footing");
    private static final double FOOTING_RESISTANCE = 0.65;

    private GuardHealth() {}

    public static void refresh(SettlerEntity settler) {
        if (settler.level().isClientSide) return;
        var maximum = settler.getAttribute(Attributes.MAX_HEALTH);
        if (maximum == null) return;
        var settlement = settler.settlement();
        boolean guard = settlement != null && !settler.isTraveler()
            && Employment.professionOf(settlement, settler.getUUID()) == Profession.GUARD;
        refreshFooting(settler, guard);
        var current = maximum.getModifier(CAPACITY);
        if (guard && current != null && current.amount() == EXTRA_HEALTH
            && current.operation() == AttributeModifier.Operation.ADD_VALUE) return;
        if (!guard && current == null) return;
        float health = settler.getHealth();
        maximum.removeModifier(CAPACITY);
        if (guard) {
            // Persistent attributes load before Health, retaining saved Guard HP > 24.
            // Employment revalidates this capacity on the first server tick.
            maximum.addPermanentModifier(new AttributeModifier(CAPACITY, EXTRA_HEALTH,
                AttributeModifier.Operation.ADD_VALUE));
        }
        settler.setHealth(Math.min(health, settler.getMaxHealth()));
    }

    private static void refreshFooting(SettlerEntity settler, boolean guard) {
        var resistance = settler.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (resistance == null) return;
        var current = resistance.getModifier(FOOTING);
        if (guard && current != null && current.amount() == FOOTING_RESISTANCE
            && current.operation() == AttributeModifier.Operation.ADD_VALUE) return;
        if (!guard && current == null) return;
        resistance.removeModifier(FOOTING);
        if (guard) {
            // Actual mob knockback previously kept the starter airborne through
            // most four-tick sword contacts. Trained footing reduces that force;
            // it does not grant damage immunity, a shield or extra attack reach.
            resistance.addPermanentModifier(new AttributeModifier(FOOTING,
                FOOTING_RESISTANCE, AttributeModifier.Operation.ADD_VALUE));
        }
    }
}
