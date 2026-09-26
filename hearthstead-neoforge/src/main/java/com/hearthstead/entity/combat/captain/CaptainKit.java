package com.hearthstead.entity.combat.captain;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;

import javax.annotation.Nullable;

/**
 * The hero Captain's body: extra health, armour, damage and knockback
 * resistance (config {@code [captain]}), applied as transient modifiers that
 * exist exactly while {@link CaptainStatus#isHero} holds; and the kit rules
 * (which loadout his hands actually hold).
 */
public final class CaptainKit {
    static final ResourceLocation HEALTH_ID = Hearthstead.id("captain_health");
    static final ResourceLocation ARMOR_ID = Hearthstead.id("captain_armor");
    static final ResourceLocation DAMAGE_ID = Hearthstead.id("captain_damage");
    static final ResourceLocation KNOCKBACK_ID = Hearthstead.id("captain_knockback");

    private CaptainKit() {
    }

    /** Idempotent: hero modifiers on while hero, gone otherwise. Returns whether he is hero. */
    public static boolean sync(SettlerEntity settler) {
        boolean hero = CaptainStatus.isHero(settler);
        boolean wasHero = has(settler, Attributes.MAX_HEALTH, HEALTH_ID);
        set(settler, Attributes.MAX_HEALTH, HEALTH_ID, hero ? CaptainConfig.healthBonus() : 0.0D);
        set(settler, Attributes.ARMOR, ARMOR_ID, hero ? CaptainConfig.armorBonus() : 0.0D);
        set(settler, Attributes.ATTACK_DAMAGE, DAMAGE_ID, hero ? CaptainConfig.damageBonus() : 0.0D);
        set(settler, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID,
            hero ? CaptainConfig.knockbackResistance() : 0.0D);
        if (hero && !wasHero) {
            settler.setHealth(Math.min(settler.getMaxHealth(),
                settler.getHealth() + (float) CaptainConfig.healthBonus()));
        }
        if (settler.getHealth() > settler.getMaxHealth()) {
            settler.setHealth(settler.getMaxHealth());
        }
        return hero;
    }

    /** The loadout his hands hold right now (null = no complete kit). */
    @Nullable
    public static CaptainLoadout heldLoadout(SettlerEntity settler) {
        CaptainLoadout l = CaptainLoadout.of(settler.getMainHandItem(), settler.getOffhandItem());
        return l != null && CaptainConfig.loadoutAllowed(l) ? l : null;
    }

    /**
     * A hero holding a complete, allowed kit counts as properly equipped even
     * without the Guard's plain sword (EquipmentRequests asks this first).
     */
    public static boolean holdsKit(SettlerEntity settler) {
        return CaptainStatus.isHero(settler) && heldLoadout(settler) != null;
    }

    /** The hero kit's max-health bonus currently applied (0 when none); base health depends on other lanes. */
    public static double healthBonusOf(SettlerEntity settler) {
        AttributeInstance inst = settler.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        AttributeModifier mod = inst == null ? null : inst.getModifier(HEALTH_ID);
        return mod == null ? 0.0D : mod.amount();
    }

    private static boolean has(SettlerEntity s, Holder<Attribute> attr, ResourceLocation id) {
        AttributeInstance inst = s.getAttribute(attr);
        return inst != null && inst.hasModifier(id);
    }

    private static void set(SettlerEntity s, Holder<Attribute> attr, ResourceLocation id, double amount) {
        AttributeInstance inst = s.getAttribute(attr);
        if (inst == null) {
            return;
        }
        AttributeModifier cur = inst.getModifier(id);
        if (amount == 0.0D) {
            if (cur != null) {
                inst.removeModifier(id);
            }
            return;
        }
        if (cur == null || cur.amount() != amount) {
            inst.removeModifier(id);
            inst.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
        }
    }
}
