package com.hearthstead.entity;

import net.minecraft.network.chat.Component;

/**
 * What a settler is made of. Five numbers, 0–100, that everything else reads.
 *
 * <p>They exist so that two settlers with the same job are not the same person.
 * A settlement of thirty interchangeable workers is a spreadsheet; a settlement
 * where you know that Astrid is the strong one and Bjørn learns fast is a
 * place. That is also what makes the hire screen a decision rather than a list.
 *
 * <p>The numbers are deliberately <b>small at the start and slow to grow</b> —
 * see {@link SettlerAttributes}. A newcomer is a nobody. A settler at 70 is
 * someone the settlement built, over weeks, by giving them that work.
 */
public enum Attribute {
    /** Force. Heavy work, what they can carry, what a blow lands for. */
    STRENGTH("strength"),
    /** Endurance. How slowly they tire and how long a shift they can stand. */
    STAMINA("stamina"),
    /** Judgement. How fast they learn <i>everything</i>, and craft precision. */
    WITS("wits"),
    /** Hands. Fine work — fields, benches, looms, bowstrings. */
    DEXTERITY("dexterity"),
    /** Heart. Morale under pressure, and how much they lift the people near them. */
    SPIRIT("spirit");

    public static final Attribute[] ALL = values();
    public static final int COUNT = ALL.length;

    private final String key;

    Attribute(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public Component displayName() {
        return Component.translatable("hearthstead.attribute." + key);
    }

    /** The work that raises it, for the Tingbok and for tooltips. */
    public Component trainedBy() {
        return Component.translatable("hearthstead.attribute." + key + ".trained_by");
    }

    public static Attribute byOrdinal(int index) {
        return index >= 0 && index < COUNT ? ALL[index] : STRENGTH;
    }
}
