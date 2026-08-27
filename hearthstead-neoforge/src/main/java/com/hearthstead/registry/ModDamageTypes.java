package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;

/** Data-driven damage types whose registry entries live in the mod pack. */
public final class ModDamageTypes {
    /**
     * Raider melee that remains real on a Peaceful world. Its JSON uses
     * scaling=never; armour, shields and enchantment hooks remain ordinary.
     */
    public static final ResourceKey<DamageType> RAIDER_ATTACK = ResourceKey.create(
        Registries.DAMAGE_TYPE, Hearthstead.id("raider_attack"));

    private ModDamageTypes() {
    }
}
