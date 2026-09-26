package com.hearthstead.entity.combat;

import com.hearthstead.Hearthstead;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

/**
 * Owner rule (26 Sep): animations MATCH THE WEAPON. Whoever holds a weapon (hero Captain, guard,
 * battle role, any armed settler) plays the clip set of the weapon's TYPE, mapped by item TAG so
 * every material tier (wood .. netherite) shares one set. Each type also owns its swing timing:
 * a slower weapon has a longer wind-up, so damage lands on the clip's contact frame.
 *
 * <p>Precedence (most specific first): the Captain weapon tags, the battle-role tags, then
 * {@code #minecraft:swords}, then {@code #minecraft:axes}; a bow by item or tag.
 */
public enum WeaponClass {
    /** Sword (and shield): the Guard moveset. */
    SWORD("guard", 10, 4, 1.0D),
    /** Short/dual swords: the Captain's dual clips (fast). */
    SHORT_SWORD("captain_dual", 10, 4, 1.0D),
    /** Two-handed longsword: the longsword clips. */
    LONGSWORD("longsword", 16, 8, 1.25D),
    /** Spear: the spear clips (reach). */
    SPEAR("spear", 12, 5, 1.5D),
    /** Double/great axe: the great-axe clips (heavy). */
    GREAT_AXE("captain_axe", 18, 9, 1.2D),
    /** Halberd: thrust, sweep, hook (long reach, medium speed). */
    HALBERD("captain_halberd", 16, 7, 1.5D),
    /** Warhammer: overhead crush, slam (slowest, longest wind-up). */
    WARHAMMER("captain_hammer", 24, 12, 1.2D),
    /** One-handed axe: swung with the Guard moveset. */
    AXE("guard", 10, 4, 1.0D),
    /** Bow: the archer draw/release cycle. */
    BOW("archer", 30, 14, 0.0D),
    /** Nothing usable in hand. */
    NONE("none", 0, 0, 0.0D);

    public static final TagKey<Item> DUAL_SWORDS = tag("captain/dual_swords");
    public static final TagKey<Item> GREAT_AXES = tag("captain/great_axes");
    public static final TagKey<Item> HALBERDS = tag("captain/halberds");
    public static final TagKey<Item> WARHAMMERS = tag("captain/warhammers");
    public static final TagKey<Item> BOWS = tag("captain/bows");
    public static final TagKey<Item> LONGSWORDS = tag("longswords");
    public static final TagKey<Item> SPEARS = tag("spears");

    private final String clipSet;
    private final int swingLength;
    private final int contactTick;
    private final double reachScale;

    WeaponClass(String clipSet, int swingLength, int contactTick, double reachScale) {
        this.clipSet = clipSet;
        this.swingLength = swingLength;
        this.contactTick = contactTick;
        this.reachScale = reachScale;
    }

    /** Clip-set prefix the animation engine selects by (e.g. {@code captain_axe_*}). */
    public String clipSet() { return clipSet; }
    /** Plain swing length in ticks. */
    public int swingLength() { return swingLength; }
    /** Plain swing contact tick = the clip's impact frame (release tick for a bow). */
    public int contactTick() { return contactTick; }
    /** Reach as a multiple of vanilla melee reach (0 = ranged). */
    public double reachScale() { return reachScale; }
    public boolean ranged() { return this == BOW; }
    public boolean twoHanded() {
        return this == LONGSWORD || this == SPEAR || this == GREAT_AXE || this == HALBERD
            || this == WARHAMMER || this == BOW;
    }

    /**
     * Pure classification: {@code has} answers "is the item in this tag", {@code vanillaBow}
     * whether it is minecraft:bow. JUnit-testable without a registry.
     */
    public static WeaponClass classify(Predicate<TagKey<Item>> has, boolean vanillaBow) {
        if (has.test(DUAL_SWORDS)) return SHORT_SWORD;
        if (has.test(GREAT_AXES)) return GREAT_AXE;
        if (has.test(HALBERDS)) return HALBERD;
        if (has.test(WARHAMMERS)) return WARHAMMER;
        if (vanillaBow || has.test(BOWS)) return BOW;
        if (has.test(LONGSWORDS)) return LONGSWORD;
        if (has.test(SPEARS)) return SPEAR;
        if (has.test(ItemTags.SWORDS)) return SWORD;
        if (has.test(ItemTags.AXES)) return AXE;
        return NONE;
    }

    /** The weapon class of a held stack. */
    public static WeaponClass of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return NONE;
        }
        return classify(t -> {
            try {
                return stack.is(t);
            } catch (IllegalStateException unbound) {
                return false;
            }
        }, stack.is(Items.BOW));
    }

    private static TagKey<Item> tag(String path) {
        return TagKey.create(Registries.ITEM, Hearthstead.id(path));
    }
}
