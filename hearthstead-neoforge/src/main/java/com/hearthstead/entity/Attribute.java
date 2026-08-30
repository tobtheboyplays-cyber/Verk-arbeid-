package com.hearthstead.entity;

import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * What a settler is made of. Eight numbers on a player-facing 0–100 scale.
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
    /** Judgement. How quickly they understand and learn new work. */
    WITS("wits"),
    /** Hands. Fine work — fields, benches, looms and bowstrings. */
    DEXTERITY("dexterity"),
    /** Heart. Morale under pressure, and how much they lift the people near them. */
    SPIRIT("spirit"),
    /** Awareness. Finding targets, reading terrain and noticing change. */
    PERCEPTION("perception"),
    /** Continuity. Setting up cleanly and staying on the current task. */
    FOCUS("focus"),
    /** Social force. Leading, reassuring and making an impression on others. */
    PRESENCE("presence");

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

    /**
     * Resolves persisted or network data without inventing Strength for an
     * invalid ordinal. Callers must choose an explicit repair or rejection.
     */
    public static Optional<Attribute> byOrdinal(int index) {
        return index >= 0 && index < COUNT
            ? Optional.of(ALL[index]) : Optional.empty();
    }
}
