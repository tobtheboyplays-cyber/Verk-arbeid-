package com.hearthstead.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The one place an attribute becomes a number in play (attributes lane,
 * 26 Sep; plan/ATTRIBUTES.md). Pure: no entity, no world, no config read.
 * Call sites pass the attribute value and the {@code [attributes]
 * effectStrength} multiplier they got from {@code AttributeConfig}.
 *
 * <h2>The curve</h2>
 *
 * <p>Every effect is {@code max x sqrt(v / 99) x strength}. The square root
 * is the design: attributes start between 1 and 15 and grow slowly, so a
 * linear curve would make every newcomer identical (4 vs 12 would be a
 * rounding error). With the root, 4 already gives 20% of an effect's
 * maximum, 15 gives 39%, 50 gives 71% and 99 gives all of it; the last
 * stretch pays least, which is also what stops a veteran snowballing.
 *
 * <h2>Bounds</h2>
 *
 * <p>{@code strength} is clamped to [0, {@value #MAX_STRENGTH}] and every
 * effect has its own hard cap (never more than {@link Effect#cap}), so a
 * misconfigured server cannot turn a stat into a runaway. Effects of the
 * same kind never stack past {@link #MAX_WORK_TIME_CUT} work time.
 */
public final class AttributeEffects {

    public static final double MAX_STRENGTH = 2.0D;
    /** Everything that shortens one piece of work together, at most. */
    public static final double MAX_WORK_TIME_CUT = 0.30D;
    /** Job fit: what the job's primary / secondary attribute take off work time at 99. */
    public static final double JOB_FIT_PRIMARY = 0.10D;
    public static final double JOB_FIT_SECONDARY = 0.05D;

    /**
     * Every attribute-driven effect in play. {@code max} is the size at value
     * 99 and strength 1; {@code cap} is the hard bound at any strength.
     * Fractions unless the javadoc says items / points / hit points.
     */
    public enum Effect {
        // ---- Strength
        /** Melee damage of any fighting settler: +0..20%. */
        MELEE_DAMAGE(Attribute.STRENGTH, "melee_damage", 0.20D, 0.30D),
        /** Items per trip for couriers and gatherers: +0..4 items. */
        HAUL_CAPACITY(Attribute.STRENGTH, "haul_capacity", 4.0D, 6.0D),
        // ---- Stamina
        /** Energy lost while working: -0..20%. */
        ENERGY_DRAIN(Attribute.STAMINA, "energy_drain", 0.20D, 0.30D),
        /** Max health: +0..4 hit points (two hearts). */
        MAX_HEALTH(Attribute.STAMINA, "max_health", 4.0D, 6.0D),
        // ---- Dexterity
        /** Arrow spread of archers: -0..30%. */
        RANGED_SPREAD(Attribute.DEXTERITY, "ranged_spread", 0.30D, 0.45D),
        /** Recovery between melee swings of any fighting settler: -0..15%. */
        MELEE_TEMPO(Attribute.DEXTERITY, "melee_tempo", 0.15D, 0.20D),
        // ---- Spirit
        /** The settler's own morale loss: -0..20%. */
        MORALE_DECAY(Attribute.SPIRIT, "morale_decay", 0.20D, 0.30D),
        /** A healer's heal amount: +0..25%. */
        HEAL_AMOUNT(Attribute.SPIRIT, "heal_amount", 0.25D, 0.40D),
        // ---- Perception
        /** Search radius for prey (hunter) and archer shot range: +0..25%. */
        DETECTION_RANGE(Attribute.PERCEPTION, "detection_range", 0.25D, 0.35D),
        /** Chance of one extra item on a gathered unit (farmer, miner, herder, fisher): +0..10%. */
        EXTRA_FIND(Attribute.PERCEPTION, "extra_find", 0.10D, 0.15D),
        // ---- Focus
        /** Batch time at any workbench (and the butcher's table): -0..12%. */
        CRAFT_TIME(Attribute.FOCUS, "craft_time", 0.12D, 0.18D),
        /** Archer draw time and rune mage cast time: -0..20%. */
        DRAW_CAST_TIME(Attribute.FOCUS, "draw_cast_time", 0.20D, 0.30D),
        /** Research progress per scholar session: +0..10% of a session. */
        STUDY(Attribute.FOCUS, "study", 0.10D, 0.15D),
        // ---- Presence
        /** Persuasion chance in conversations, from the settlement's best speaker: +0..10 points. */
        PERSUASION(Attribute.PRESENCE, "persuasion", 0.10D, 0.15D),
        /** Coins the trader gets from the merchant: +0..10%. */
        TRADE_PRICE(Attribute.PRESENCE, "trade_price", 0.10D, 0.15D),
        /** Morale an innkeeper's meal and ale give a guest: +0..25%. */
        HOSPITALITY(Attribute.PRESENCE, "hospitality", 0.25D, 0.40D);

        private final Attribute attribute;
        private final String key;
        private final double max;
        private final double cap;

        Effect(Attribute attribute, String key, double max, double cap) {
            this.attribute = attribute;
            this.key = key;
            this.max = max;
            this.cap = cap;
        }

        public Attribute attribute() {
            return attribute;
        }

        public String key() {
            return key;
        }

        public double max() {
            return max;
        }

        public double cap() {
            return cap;
        }

        public String translationKey() {
            return "hearthstead.attribute.effect." + key;
        }
    }

    private static final Map<Attribute, List<Effect>> BY_ATTRIBUTE = index();

    private AttributeEffects() {
    }

    // ------------------------------------------------------------ core ---

    /** 0..1: {@code sqrt(v/99)}, v clamped to 0..99. */
    public static double curve(int value) {
        int v = Math.max(0, Math.min(SettlerAttributes.CEILING, value));
        return Math.sqrt(v / (double) SettlerAttributes.CEILING);
    }

    public static double clampStrength(double strength) {
        if (!Double.isFinite(strength)) {
            return 1.0D;
        }
        return Math.max(0.0D, Math.min(MAX_STRENGTH, strength));
    }

    /** The effect's size for this value: 0..{@link Effect#cap}. */
    public static double amount(Effect effect, int value, double strength) {
        double raw = effect.max * curve(value) * clampStrength(strength);
        return Math.max(0.0D, Math.min(effect.cap, raw));
    }

    /** {@code 1 + amount}: for things that grow (damage, range, heal, price). */
    public static double gain(Effect effect, int value, double strength) {
        return 1.0D + amount(effect, value, strength);
    }

    /** {@code 1 - amount}: for things that shrink (drain, spread, time). */
    public static double cut(Effect effect, int value, double strength) {
        return 1.0D - amount(effect, value, strength);
    }

    /** Whole items/points, rounded down (haul capacity, +hp shown as whole). */
    public static int whole(Effect effect, int value, double strength) {
        return (int) Math.floor(amount(effect, value, strength) + 1.0E-9D);
    }

    /** A chance roll: true when {@code roll} (uniform [0,1)) lands inside the amount. */
    public static boolean rolls(Effect effect, int value, double strength, double roll) {
        return roll < amount(effect, value, strength);
    }

    /** Shortens a tick count by {@code amount}, never below one tick if it was positive. */
    public static int shorten(int ticks, double cutFraction) {
        if (ticks <= 0) {
            return ticks;
        }
        double cut = Math.max(0.0D, Math.min(MAX_WORK_TIME_CUT, cutFraction));
        return Math.max(1, ticks - (int) Math.floor(ticks * cut));
    }

    // --------------------------------------------------------- job fit ---

    /**
     * Job fit: the job's primary and secondary attribute make every timed
     * piece of that job quicker, 0..15% together at 99/99. This is what makes
     * "the strong one is the better smith" true on every trade.
     */
    public static double jobFit(int primary, int secondary, double strength) {
        double s = clampStrength(strength);
        double fit = (JOB_FIT_PRIMARY * curve(primary) + JOB_FIT_SECONDARY * curve(secondary)) * s;
        return Math.max(0.0D, Math.min(MAX_WORK_TIME_CUT, fit));
    }

    /**
     * Trade-level speed and job fit together, capped at
     * {@link #MAX_WORK_TIME_CUT}: the two add, they never multiply.
     */
    public static double combinedWorkCut(double levelBonus, double jobFit) {
        double sum = Math.max(0.0D, levelBonus) + Math.max(0.0D, jobFit);
        return Math.min(MAX_WORK_TIME_CUT, sum);
    }

    // ------------------------------------------------------- mayor boons ---

    /** Combined caps where a boon adds to an attribute effect of the same kind. */
    public static final double MAX_EXTRA_FIND = 0.20D;
    public static final double MAX_MORALE_CUT = 0.35D;
    public static final double MAX_RANGE_GAIN = 0.40D;
    public static final double MAX_PERSUASION = 0.15D;

    /**
     * The settlement-wide boon a mayor brings (their knack picks it, see
     * {@code settlement.Mayor.Boon}). Size = {@code max x (0.5 + 0.5 x
     * sqrt(v/99)) x strength}: any mayor gives half, a mayor strong in the
     * boon's attribute gives it all. Long Days and Good Counsel keep their
     * older fixed values and are not listed here.
     */
    public enum Boon {
        /** Every trade's timed work: -5..10% (inside the 30% work cap). */
        HARD_HANDS(Attribute.STRENGTH, 0.10D, 0.15D),
        /** Chance of one extra gathered item: +5..10% (combined cap 20%). */
        CAREFUL_WORK(Attribute.DEXTERITY, 0.10D, 0.15D),
        /** Everyone's morale loss: -10..20% (combined cap 35%). */
        OPEN_HEARTH(Attribute.SPIRIT, 0.20D, 0.30D),
        /** Hunter search and archer shot range: +7.5..15% (combined cap 40%). */
        CLEAR_SIGHT(Attribute.PERCEPTION, 0.15D, 0.25D),
        /** Batch time at every bench: -4..8% (inside the 30% work cap). */
        STEADY_PURPOSE(Attribute.FOCUS, 0.08D, 0.12D),
        /** Persuasion in conversations: +4..8 points (combined cap 15). */
        COMMON_VOICE(Attribute.PRESENCE, 0.08D, 0.12D);

        private final Attribute attribute;
        private final double max;
        private final double cap;

        Boon(Attribute attribute, double max, double cap) {
            this.attribute = attribute;
            this.max = max;
            this.cap = cap;
        }

        public Attribute attribute() {
            return attribute;
        }

        public double max() {
            return max;
        }

        public double cap() {
            return cap;
        }
    }

    /** A boon's size for a mayor with {@code value} in its attribute: 0..cap. */
    public static double boonAmount(Boon boon, int value, double strength) {
        double raw = boon.max * (0.5D + 0.5D * curve(value)) * clampStrength(strength);
        return Math.max(0.0D, Math.min(boon.cap, raw));
    }

    // ------------------------------------------------------------ traits ---

    /** Bounds on the two trait multipliers that were defined but unread. */
    public static final double MIN_TRAIT_SPEED = 0.85D;
    public static final double MAX_TRAIT_SPEED = 1.15D;
    public static final double MAX_TRAIT_SIGHT = 1.30D;
    /** A civilian's threat scan: normal, and at most (FEARFUL). */
    public static final double PANIC_SCAN = 12.0D;
    public static final double FEARFUL_SCAN = 16.0D;

    /** Trait speed ({@code Trait.speed}), clamped, faded in by strength (0 = off, 1+ = full). */
    public static double traitSpeed(double traitSpeed, double strength) {
        double t = Math.max(MIN_TRAIT_SPEED, Math.min(MAX_TRAIT_SPEED,
            Double.isFinite(traitSpeed) ? traitSpeed : 1.0D));
        return 1.0D + (t - 1.0D) * Math.min(1.0D, clampStrength(strength));
    }

    /** Trait sight ({@code Trait.sight}) as a range GAIN: 0..0.30, faded in by strength. */
    public static double traitSightGain(double traitSight, double strength) {
        double t = Math.max(1.0D, Math.min(MAX_TRAIT_SIGHT,
            Double.isFinite(traitSight) ? traitSight : 1.0D));
        return (t - 1.0D) * Math.min(1.0D, clampStrength(strength));
    }

    /**
     * A civilian's threat-scan radius: 12, 16 for the FEARFUL (flees
     * earlier), 12 x sight for the WATCHFUL (spots sooner); never above 16.
     */
    public static double panicScanRadius(boolean fearful, double traitSight, double strength) {
        double on = Math.min(1.0D, clampStrength(strength));
        double fear = fearful ? (FEARFUL_SCAN - PANIC_SCAN) * on : 0.0D;
        double sight = PANIC_SCAN * traitSightGain(traitSight, strength);
        return Math.min(FEARFUL_SCAN, PANIC_SCAN + Math.max(fear, sight));
    }

    // ------------------------------------------------ trait work & time ---

    /** A trait may slow timed work by at most 10% or speed it by at most 20%. */
    public static final double MIN_TRAIT_WORK_CUT = -0.10D;
    public static final double MAX_TRAIT_WORK_CUT = 0.20D;
    /** EARLY_RISER lives an hour ahead of the village clock, NIGHT_OWL an hour behind. */
    public static final long EARLY_RISER_SHIFT = 1000L;
    public static final long NIGHT_OWL_SHIFT = -1000L;
    /** EARLY_RISER in the morning, NIGHT_OWL in the late hours: work x1.05. */
    public static final double SHIFT_WORK_BONUS = 1.05D;
    /** NIGHT_OWL working at night: energy drain x0.75. */
    public static final double NIGHT_OWL_NIGHT_DRAIN = 0.75D;
    /** WELCOMING in a social job (innkeeper, trader): work x1.05 instead of x0.92. */
    public static final double WELCOMING_SOCIAL_WORK = 1.05D;
    /** BIG_EATER's x1.15 only pays at or above this hunger. */
    public static final float BIG_EATER_FED = 50.0F;
    /** A WELCOMING member: guests wait x1.5, tavern visits last x1.5, +3 persuasion with visitors. */
    public static final double WELCOME_PATIENCE = 1.5D;
    public static final double WELCOME_TAVERN_STAY = 1.5D;
    public static final double WELCOME_PERSUASION = 0.03D;
    /** GREEN_FINGERS: one extra crop random tick per this many ticks while working a field. */
    public static final int GREEN_FINGERS_INTERVAL = 60;
    /** GREEN_FINGERS: extra harvest chance for a farmer (inside the 20% find cap). */
    public static final double GREEN_FINGERS_FIND = 0.05D;

    /** Minecraft day time 0..24000 (0 = 06:00): the morning is [0, 6000). */
    public static boolean morning(long dayTime) {
        return Math.floorMod(dayTime, 24000L) < 6000L;
    }

    /** The late hours a night owl is at its best: [9000, 23000) (15:00 to 05:00). */
    public static boolean lateHours(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        return t >= 9000L && t < 23000L;
    }

    /** Night: [13000, 23000). */
    public static boolean night(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        return t >= 13000L && t < 23000L;
    }

    /** A trait work multiplier (product) as a signed work cut, faded in by strength, bounded. */
    public static double traitWorkCut(double workMultiplier, double strength) {
        double w = Double.isFinite(workMultiplier) ? workMultiplier : 1.0D;
        double cut = (w - 1.0D) * Math.min(1.0D, clampStrength(strength));
        // Round away float dust (1.15 - 1 = 0.1499999...) so 15% is 15%.
        cut = Math.round(cut * 1.0E6D) / 1.0E6D;
        return Math.max(MIN_TRAIT_WORK_CUT, Math.min(MAX_TRAIT_WORK_CUT, cut));
    }

    /**
     * Level, job fit and boons (never negative) plus a signed trait cut:
     * bounded to [-10%, +30%] of the work time.
     */
    public static double combinedWorkCut(double levelBonus, double jobFit, double traitCut) {
        double sum = Math.max(0.0D, levelBonus) + Math.max(0.0D, jobFit) + traitCut;
        return Math.max(MIN_TRAIT_WORK_CUT, Math.min(MAX_WORK_TIME_CUT, sum));
    }

    /** Signed {@link #shorten}: a negative cut lengthens, by at most 10%. */
    public static int shortenSigned(int ticks, double cut) {
        if (ticks <= 0) {
            return ticks;
        }
        double c = Math.max(MIN_TRAIT_WORK_CUT, Math.min(MAX_WORK_TIME_CUT, cut));
        if (c >= 0.0D) {
            return shorten(ticks, c);
        }
        return ticks + (int) Math.floor(ticks * -c);
    }

    /** A clock shift faded in by strength (whole ticks). */
    public static long clockShift(long shift, double strength) {
        return Math.round(shift * Math.min(1.0D, clampStrength(strength)));
    }

    // ---------------------------------------------------------- display ---

    /** Whether the effect makes a number smaller (time, drain, spread, loss). */
    public static boolean reduces(Effect effect) {
        return switch (effect) {
            case ENERGY_DRAIN, RANGED_SPREAD, MELEE_TEMPO, MORALE_DECAY, CRAFT_TIME,
                 DRAW_CAST_TIME -> true;
            default -> false;
        };
    }

    /**
     * Signed, unit-aware amount for a UI line: "+6%", "-4%", "+1" (items),
     * "+1.5" (health), "+3" (persuasion points). Locale-neutral digits.
     */
    public static String format(Effect effect, int value, double strength) {
        double a = amount(effect, value, strength);
        return switch (effect) {
            case HAUL_CAPACITY -> "+" + whole(effect, value, strength);
            case MAX_HEALTH -> String.format(java.util.Locale.ROOT, "+%.1f", Math.round(a * 2.0D) / 2.0D);
            case PERSUASION -> "+" + Math.round(a * 100.0D);
            default -> (reduces(effect) ? "-" : "+")
                + String.format(java.util.Locale.ROOT, "%.0f%%", a * 100.0D);
        };
    }

    // ------------------------------------------------------- catalogue ---

    /** The live effects of one attribute, in display order. */
    public static List<Effect> effectsOf(Attribute attribute) {
        return BY_ATTRIBUTE.getOrDefault(attribute, List.of());
    }

    /**
     * Effects that were already live before this lane and are not driven by
     * {@link Effect} (rank ladders, effort pool, learning). Keys for the
     * Skills-tab "affects" line; the count feeds the two-effects rule.
     */
    public static List<String> structuralEffectKeys(Attribute attribute) {
        return switch (attribute) {
            case STRENGTH -> List.of("guard_rank", "lumber_contacts");
            case STAMINA -> List.of("effort_pool", "fatigue_floor", "carry_relief");
            case WITS -> List.of("attribute_growth", "trade_xp", "research");
            case DEXTERITY -> List.of("archer_rank", "fisher_cast", "field_size", "craft_quality");
            case SPIRIT, PERCEPTION, FOCUS, PRESENCE -> List.of();
        };
    }

    /** Everything an attribute does in play: structural plus {@link Effect}s. */
    public static int liveEffectCount(Attribute attribute) {
        return effectsOf(attribute).size() + structuralEffectKeys(attribute).size();
    }

    private static Map<Attribute, List<Effect>> index() {
        EnumMap<Attribute, List<Effect>> map = new EnumMap<>(Attribute.class);
        for (Attribute attribute : Attribute.ALL) {
            map.put(attribute, new ArrayList<>());
        }
        for (Effect effect : Effect.values()) {
            map.get(effect.attribute).add(effect);
        }
        EnumMap<Attribute, List<Effect>> frozen = new EnumMap<>(Attribute.class);
        map.forEach((k, v) -> frozen.put(k, Collections.unmodifiableList(v)));
        return Collections.unmodifiableMap(frozen);
    }
}
