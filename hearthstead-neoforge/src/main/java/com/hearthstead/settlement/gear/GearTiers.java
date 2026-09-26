package com.hearthstead.settlement.gear;

import com.hearthstead.Hearthstead;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TridentItem;

/**
 * Which {@link GearTier} an item belongs to.
 *
 * <p>Data first: the item tags {@code #hearthstead:gear_tier/t0} ..
 * {@code t4} (datapack-tunable, synced to clients with every other tag). An
 * item in several tier tags takes the HIGHEST, so a pack can move an item up
 * by adding it to a higher tag. Untagged items (other mods) fall back to a
 * material heuristic so a modded diamond-class sword is never Common by
 * accident: armour by its material's protection and toughness, tools and
 * weapons by their {@link Tier}'s durability.
 */
public final class GearTiers {
    private GearTiers() {
    }

    @SuppressWarnings("unchecked")
    private static final TagKey<Item>[] TAGS = new TagKey[GearTier.MAX + 1];

    static {
        for (int t = 0; t <= GearTier.MAX; t++) {
            TAGS[t] = TagKey.create(Registries.ITEM, Hearthstead.id("gear_tier/t" + t));
        }
    }

    public static TagKey<Item> tag(int tier) {
        return TAGS[Math.max(0, Math.min(GearTier.MAX, tier))];
    }

    /** 0..4. Empty stacks and non-gear are 0 (Common). */
    public static int tierOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        int tagged = taggedTier(stack);
        return tagged >= 0 ? tagged : classify(stack.getItem());
    }

    public static GearTier gearTierOf(ItemStack stack) {
        return GearTier.of(tierOf(stack));
    }

    /** Highest tier tag holding the item, or -1 when untagged (or tags unbound). */
    static int taggedTier(ItemStack stack) {
        try {
            for (int t = GearTier.MAX; t >= 0; t--) {
                if (stack.is(TAGS[t])) {
                    return t;
                }
            }
        } catch (IllegalStateException unboundTags) {
            // Unit tests without a bound registry: the heuristic still answers.
        }
        return -1;
    }

    /**
     * The built-in heuristic. Also the answer for every vanilla item, so the
     * shipped tags and this method agree (JUnit keeps them honest).
     */
    public static int classify(Item item) {
        if (item instanceof ArmorItem armor) {
            return armorTier(armor.getMaterial());
        }
        if (item instanceof TieredItem tiered) {
            return toolTier(tiered.getTier());
        }
        if (item instanceof TridentItem || item instanceof MaceItem) {
            return GearTier.DIAMOND.level();
        }
        if (item instanceof CrossbowItem) {
            return GearTier.PLATE.level();
        }
        if (item instanceof ShieldItem) {
            return GearTier.MAIL.level();
        }
        return GearTier.COMMON.level();
    }

    static int armorTier(Holder<ArmorMaterial> material) {
        if (material == null) {
            return 0;
        }
        if (same(material, ArmorMaterials.LEATHER) || same(material, ArmorMaterials.GOLD)
            || same(material, ArmorMaterials.ARMADILLO)) {
            return GearTier.COMMON.level();
        }
        if (same(material, ArmorMaterials.CHAIN) || same(material, ArmorMaterials.TURTLE)) {
            return GearTier.MAIL.level();
        }
        if (same(material, ArmorMaterials.IRON)) {
            return GearTier.PLATE.level();
        }
        if (same(material, ArmorMaterials.DIAMOND)) {
            return GearTier.DIAMOND.level();
        }
        if (same(material, ArmorMaterials.NETHERITE)) {
            return GearTier.NETHERITE.level();
        }
        return armorTierByStats(material.value());
    }

    private static boolean same(Holder<ArmorMaterial> a, Holder<ArmorMaterial> b) {
        if (a == b) {
            return true;
        }
        return a.unwrapKey().isPresent() && a.unwrapKey().equals(b.unwrapKey());
    }

    /** Modded armour: protection summed over the four pieces, plus toughness. */
    static int armorTierByStats(ArmorMaterial material) {
        int defense = 0;
        for (ArmorItem.Type type : new ArmorItem.Type[] {ArmorItem.Type.HELMET,
                ArmorItem.Type.CHESTPLATE, ArmorItem.Type.LEGGINGS, ArmorItem.Type.BOOTS}) {
            defense += material.defense().getOrDefault(type, 0);
        }
        return armorTierByStats(defense, material.toughness(), material.knockbackResistance());
    }

    /** Vanilla anchors: leather 7, chain 12, iron 15, diamond 20 (+2 tough), netherite +3 tough. */
    public static int armorTierByStats(int totalDefense, float toughness, float knockback) {
        if (toughness >= 3.0F || knockback > 0.0F) {
            return GearTier.NETHERITE.level();
        }
        if (toughness >= 2.0F || totalDefense >= 18) {
            return GearTier.DIAMOND.level();
        }
        if (totalDefense >= 15) {
            return GearTier.PLATE.level();
        }
        if (totalDefense >= 11) {
            return GearTier.MAIL.level();
        }
        return GearTier.COMMON.level();
    }

    static int toolTier(Tier tier) {
        if (tier == Tiers.WOOD || tier == Tiers.STONE || tier == Tiers.GOLD
            || tier == Tiers.IRON) {
            return GearTier.COMMON.level();
        }
        if (tier == Tiers.DIAMOND) {
            return GearTier.DIAMOND.level();
        }
        if (tier == Tiers.NETHERITE) {
            return GearTier.NETHERITE.level();
        }
        return toolTierByUses(tier == null ? 0 : tier.getUses());
    }

    /** Modded tool tiers by durability: iron 250, diamond 1561, netherite 2031. */
    public static int toolTierByUses(int uses) {
        if (uses >= 2000) {
            return GearTier.NETHERITE.level();
        }
        if (uses >= 1200) {
            return GearTier.DIAMOND.level();
        }
        return GearTier.COMMON.level();
    }
}
