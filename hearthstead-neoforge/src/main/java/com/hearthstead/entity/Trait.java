package com.hearthstead.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * What a settler is like — the buff they arrive with.
 *
 * <h2>Every trait costs something</h2>
 *
 * <p>A trait that is only an advantage is not a character, it is a stat point
 * with a name, and a roster of pure advantages collapses into "reroll until you
 * get the good one". So <b>each trait here has a real trade-off</b>, and the
 * interesting ones trade in a different currency than they pay in: the strong
 * back carries a quarter more and walks slower, the quick study learns half
 * again as fast and starts a point behind on everything.
 *
 * <p>That is the same rule the tradition tree is designed against — new
 * mechanics with built-in trade-offs rather than flat upgrades — applied to
 * people.
 *
 * <p>A settler arrives with <b>one</b> trait, and one in ten arrives with two.
 * Two is uncommon on purpose: a settler who is three things is a settler you
 * cannot summarise, and you should be able to summarise every one of them in a
 * sentence.
 */
public enum Trait {

    /** Hauls a quarter more; slower on their feet for it. */
    STRONG_BACK("strong_back", 1.25F, 0.92F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F),

    /** Sees trouble a third farther off; too busy watching to learn quickly. */
    WATCHFUL("watchful", 1.0F, 1.0F, 1.0F, 0.90F, 1.0F, 1.0F, 1.0F, 1.30F,
        Flag.WATCHFUL),

    /** Up and working at dawn; spent by evening. */
    EARLY_RISER("early_riser", 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F,
        Flag.EARLY_RISER),

    /** Made for the night watch; poor company before noon. */
    NIGHT_OWL("night_owl", 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F,
        Flag.NIGHT_OWL),

    /** Things grow for them. They eat like it, too. */
    GREEN_FINGERS("green_fingers", 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.15F, 1.0F,
        Flag.GREEN_FINGERS),

    /** Hard to dishearten — and hard to cheer. */
    STOIC("stoic", 1.0F, 1.0F, 1.0F, 1.0F, 0.70F, 0.70F, 1.0F, 1.0F),

    /** Learns half again as fast, from a step further back. */
    QUICK_STUDY("quick_study", 1.0F, 1.0F, 1.0F, 1.40F, 1.0F, 1.0F, 1.0F, 1.0F,
        Flag.SLOW_START),

    /** Works hard on a full stomach, and insists on the full stomach. */
    BIG_EATER("big_eater", 1.0F, 1.0F, 1.15F, 1.0F, 1.0F, 1.0F, 1.40F, 1.0F),

    /** Frightens early, and is very fast about it. Survives things. */
    FEARFUL("fearful", 1.0F, 1.15F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F,
        Flag.FEARFUL),

    /** Travellers stay for them; the work waits while they talk. */
    WELCOMING("welcoming", 1.0F, 1.0F, 0.92F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F,
        Flag.WELCOMING);

    /** Behaviour a number cannot express. */
    public enum Flag {
        /** Full effect on the night watch, sluggish in the morning. */
        NIGHT_OWL,
        /** Works through the RISE phase, tires early in the evening. */
        EARLY_RISER,
        /** Larger raid-spotting radius. */
        WATCHFUL,
        /** Better yield from fields. */
        GREEN_FINGERS,
        /** Panics at a higher morale than most, and runs faster. */
        FEARFUL,
        /** Recruits better at the tavern. */
        WELCOMING,
        /** Starts one point lower on every attribute. */
        SLOW_START
    }

    public static final Trait[] ALL = values();

    private final String key;
    private final float carry;
    private final float speed;
    private final float work;
    private final float growth;
    private final float moraleDecay;
    private final float moraleGain;
    private final float hunger;
    private final float sight;
    private final EnumSet<Flag> flags;

    Trait(String key, float carry, float speed, float work, float growth,
          float moraleDecay, float moraleGain, float hunger, float sight,
          Flag... flags) {
        this.key = key;
        this.carry = carry;
        this.speed = speed;
        this.work = work;
        this.growth = growth;
        this.moraleDecay = moraleDecay;
        this.moraleGain = moraleGain;
        this.hunger = hunger;
        this.sight = sight;
        this.flags = flags.length == 0 ? EnumSet.noneOf(Flag.class)
            : EnumSet.copyOf(List.of(flags));
    }

    public String key() {
        return key;
    }

    public float carry() {
        return carry;
    }

    public float speed() {
        return speed;
    }

    public float work() {
        return work;
    }

    public float growth() {
        return growth;
    }

    public float moraleDecay() {
        return moraleDecay;
    }

    public float moraleGain() {
        return moraleGain;
    }

    public float hunger() {
        return hunger;
    }

    public float sight() {
        return sight;
    }

    public boolean has(Flag flag) {
        return flags.contains(flag);
    }

    public Component displayName() {
        return Component.translatable("hearthstead.trait." + key);
    }

    /** The one-line "what this means", for the hire card and the Tingbok. */
    public Component describe() {
        return Component.translatable("hearthstead.trait." + key + ".desc");
    }

    // ------------------------------------------------------------ rolling ---

    /**
     * The traits a newcomer arrives with: one, and one time in ten, two.
     */
    public static EnumSet<Trait> roll(RandomSource random) {
        EnumSet<Trait> out = EnumSet.of(ALL[random.nextInt(ALL.length)]);
        if (random.nextInt(10) == 0) {
            out.add(ALL[random.nextInt(ALL.length)]);
        }
        return out;
    }

    // ------------------------------------------------- combining a handful ---
    // Multipliers compose by multiplication, so two traits pulling the same way
    // stack and two pulling against each other cancel -- which is the honest
    // arithmetic and needs no special cases.

    public static float carry(EnumSet<Trait> traits) {
        return product(traits, Trait::carry);
    }

    public static float speed(EnumSet<Trait> traits) {
        return product(traits, Trait::speed);
    }

    public static float work(EnumSet<Trait> traits) {
        return product(traits, Trait::work);
    }

    public static float growth(EnumSet<Trait> traits) {
        return product(traits, Trait::growth);
    }

    public static float moraleDecay(EnumSet<Trait> traits) {
        return product(traits, Trait::moraleDecay);
    }

    public static float moraleGain(EnumSet<Trait> traits) {
        return product(traits, Trait::moraleGain);
    }

    public static float hunger(EnumSet<Trait> traits) {
        return product(traits, Trait::hunger);
    }

    public static float sight(EnumSet<Trait> traits) {
        return product(traits, Trait::sight);
    }

    public static boolean any(EnumSet<Trait> traits, Flag flag) {
        for (Trait trait : traits) {
            if (trait.has(flag)) {
                return true;
            }
        }
        return false;
    }

    private static float product(EnumSet<Trait> traits,
                                 java.util.function.ToDoubleFunction<Trait> of) {
        float total = 1.0F;
        for (Trait trait : traits) {
            total *= (float) of.applyAsDouble(trait);
        }
        return total;
    }

    /** Persisted as keys, so reordering the enum cannot rewrite anyone. */
    public static List<String> keys(EnumSet<Trait> traits) {
        List<String> out = new ArrayList<>(traits.size());
        for (Trait trait : traits) {
            out.add(trait.key());
        }
        return out;
    }

    public static EnumSet<Trait> fromKeys(List<String> keys) {
        EnumSet<Trait> out = EnumSet.noneOf(Trait.class);
        for (String key : keys) {
            for (Trait trait : ALL) {
                if (trait.key().equals(key)) {
                    out.add(trait);
                }
            }
        }
        return out;
    }
}
