package com.hearthstead.entity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, non-ranking attribute vocabulary for every employed profession.
 *
 * <p>The registry explains which two attributes define a job and, optionally,
 * which third attribute supports it. It deliberately contains no score,
 * sorting, recommendation or candidate-selection API: choosing a person stays
 * with the player.
 */
public record JobAttributeProfile(Profession profession, List<Slot> slots) {

    public enum Importance {
        CORE,
        SUPPORT
    }

    /**
     * Stable semantic identifiers for what an attribute does IN THIS JOB
     * (attributes lane, plan/ATTRIBUTES.md); localized copy may change
     * independently. Every id names an effect that runs in play.
     */
    public enum EffectId {
        /** Job fit: the job's timed work is quicker (primary 10%, secondary 5%). */
        WORK_PACE,
        /** Strength: guard / blade rank ladder and +0..20% melee damage. */
        MELEE_DAMAGE,
        /** Dexterity: -0..15% recovery between melee swings. */
        MELEE_TEMPO,
        /** Strength: +0..4 items per courier trip. */
        HAUL_CAPACITY,
        /** Strength: fewer axe contacts and larger lumber carries. */
        LUMBER_CONTACTS,
        /** Stamina: effort pool, tired pace, -energy drain, +max health. */
        ENDURANCE,
        /** Wits: faster attribute growth and trade XP. */
        LEARNING_RATE,
        /** Wits / Focus: scholar research progress per session. */
        RESEARCH,
        /** Dexterity: archer rank ladder and -0..30% arrow spread. */
        RANGED,
        /** Dexterity: fisher cast time and catch grades. */
        FISHING,
        /** Perception: hunter search radius / archer shot range +0..25%. */
        DETECTION,
        /** Perception: 0..10% chance of one extra gathered item. */
        EXTRA_FIND,
        /** Focus: archer draw / rune mage cast -0..20%. */
        DRAW_CAST,
        /** Focus: batch time at a bench -0..12%. */
        CRAFT_TIME,
        /** Spirit: healer heal +0..25%. */
        HEAL_AMOUNT,
        /** Spirit: own morale loss -0..20% (innkeeper: guest +1 morale roll). */
        MORALE,
        /** Presence: meal and ale morale +0..25%. */
        HOSPITALITY,
        /** Presence: speaks for the settlement, persuasion +0..10 points. */
        PERSUASION,
        /** Presence: merchant payout +0..10%. */
        TRADE_PRICE
    }

    /**
     * UI may call an effect live only after it reaches LIVE_VERIFIED.
     * CALCULATOR_READY means a pure tested formula exists but no runtime wiring
     * or in-game verification is implied.
     */
    public enum EffectStatus {
        FOUNDATION_ONLY,
        CALCULATOR_READY,
        LIVE_VERIFIED;

        public boolean mayDescribeAsLive() {
            return this == LIVE_VERIFIED;
        }
    }

    public record Slot(Attribute attribute, Importance importance,
                       EffectId effect, EffectStatus status) {
        public Slot {
            Objects.requireNonNull(attribute, "attribute");
            Objects.requireNonNull(importance, "importance");
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(status, "status");
        }
    }

    private static final Map<Profession, JobAttributeProfile> REGISTRY = build();

    public JobAttributeProfile {
        Objects.requireNonNull(profession, "profession");
        if (!profession.employed()) {
            throw new IllegalArgumentException("job profile requires employed profession");
        }
        slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        long coreCount = slots.stream()
            .filter(slot -> slot.importance() == Importance.CORE).count();
        long supportCount = slots.stream()
            .filter(slot -> slot.importance() == Importance.SUPPORT).count();
        if (coreCount != 2L || supportCount > 1L
            || slots.size() != coreCount + supportCount) {
            throw new IllegalArgumentException(
                "job profile requires exactly two core and at most one support slot");
        }
        EnumSet<Attribute> unique = EnumSet.noneOf(Attribute.class);
        for (Slot slot : slots) {
            if (!unique.add(slot.attribute())) {
                throw new IllegalArgumentException("duplicate job attribute: "
                    + slot.attribute());
            }
        }
    }

    /** The {@link AttributeEffects.Effect} behind a job effect id, if it is a scaled one. */
    public static Optional<AttributeEffects.Effect> scaledEffect(EffectId effect) {
        return Optional.ofNullable(switch (effect) {
            case MELEE_DAMAGE -> AttributeEffects.Effect.MELEE_DAMAGE;
            case MELEE_TEMPO -> AttributeEffects.Effect.MELEE_TEMPO;
            case HAUL_CAPACITY -> AttributeEffects.Effect.HAUL_CAPACITY;
            case ENDURANCE -> AttributeEffects.Effect.ENERGY_DRAIN;
            case RANGED -> AttributeEffects.Effect.RANGED_SPREAD;
            case DETECTION -> AttributeEffects.Effect.DETECTION_RANGE;
            case EXTRA_FIND -> AttributeEffects.Effect.EXTRA_FIND;
            case DRAW_CAST -> AttributeEffects.Effect.DRAW_CAST_TIME;
            case CRAFT_TIME -> AttributeEffects.Effect.CRAFT_TIME;
            case HEAL_AMOUNT -> AttributeEffects.Effect.HEAL_AMOUNT;
            case MORALE -> AttributeEffects.Effect.MORALE_DECAY;
            case HOSPITALITY -> AttributeEffects.Effect.HOSPITALITY;
            case PERSUASION -> AttributeEffects.Effect.PERSUASION;
            case TRADE_PRICE -> AttributeEffects.Effect.TRADE_PRICE;
            case WORK_PACE, LUMBER_CONTACTS, LEARNING_RATE, RESEARCH, FISHING -> null;
        });
    }

    public static Optional<JobAttributeProfile> find(Profession profession) {
        return Optional.ofNullable(REGISTRY.get(profession));
    }

    public static Map<Profession, JobAttributeProfile> all() {
        return REGISTRY;
    }

    private static Map<Profession, JobAttributeProfile> build() {
        EnumMap<Profession, JobAttributeProfile> profiles =
            new EnumMap<>(Profession.class);
        // Rows reviewed 26 Sep by the attributes lane (plan/ATTRIBUTES.md
        // Part 2): primary, secondary, optional support. Changed rows:
        // FARMER support Focus->Perception (extra crop find), GUARD
        // Str/Presence/Sta (the guard speaks in raid parley), TRADER
        // Presence first (payout), ARCHER support Presence->Focus (steady
        // draw), SPEARMAN support Perception->Dexterity (swing tempo).
        register(profiles, profile(Profession.TRADER,
            core(Profession.TRADER, Attribute.PRESENCE), core(Profession.TRADER, Attribute.WITS),
            support(Profession.TRADER, Attribute.STAMINA)));
        register(profiles, profile(Profession.FARMER,
            core(Profession.FARMER, Attribute.DEXTERITY), core(Profession.FARMER, Attribute.STAMINA),
            support(Profession.FARMER, Attribute.PERCEPTION)));
        register(profiles, profile(Profession.LUMBERER,
            core(Profession.LUMBERER, Attribute.STRENGTH), core(Profession.LUMBERER, Attribute.STAMINA),
            support(Profession.LUMBERER, Attribute.WITS)));
        register(profiles, profile(Profession.GUARD,
            core(Profession.GUARD, Attribute.STRENGTH), core(Profession.GUARD, Attribute.PRESENCE),
            support(Profession.GUARD, Attribute.STAMINA)));
        register(profiles, profile(Profession.COURIER,
            core(Profession.COURIER, Attribute.STRENGTH), core(Profession.COURIER, Attribute.STAMINA)));
        register(profiles, profile(Profession.BAKER,
            core(Profession.BAKER, Attribute.DEXTERITY), core(Profession.BAKER, Attribute.FOCUS),
            support(Profession.BAKER, Attribute.STAMINA)));
        register(profiles, profile(Profession.COOK,
            core(Profession.COOK, Attribute.DEXTERITY), core(Profession.COOK, Attribute.WITS),
            support(Profession.COOK, Attribute.STAMINA)));
        register(profiles, profile(Profession.BUTCHER,
            core(Profession.BUTCHER, Attribute.STRENGTH), core(Profession.BUTCHER, Attribute.DEXTERITY),
            support(Profession.BUTCHER, Attribute.STAMINA)));
        register(profiles, profile(Profession.SMELTER,
            core(Profession.SMELTER, Attribute.STRENGTH), core(Profession.SMELTER, Attribute.FOCUS),
            support(Profession.SMELTER, Attribute.STAMINA)));
        register(profiles, profile(Profession.SMITH,
            core(Profession.SMITH, Attribute.STRENGTH), core(Profession.SMITH, Attribute.DEXTERITY),
            support(Profession.SMITH, Attribute.FOCUS)));
        register(profiles, profile(Profession.SAWYER,
            core(Profession.SAWYER, Attribute.DEXTERITY), core(Profession.SAWYER, Attribute.STRENGTH),
            support(Profession.SAWYER, Attribute.STAMINA)));
        register(profiles, profile(Profession.CARPENTER,
            core(Profession.CARPENTER, Attribute.DEXTERITY), core(Profession.CARPENTER, Attribute.WITS),
            support(Profession.CARPENTER, Attribute.FOCUS)));
        register(profiles, profile(Profession.MASON,
            core(Profession.MASON, Attribute.STRENGTH), core(Profession.MASON, Attribute.DEXTERITY),
            support(Profession.MASON, Attribute.STAMINA)));
        register(profiles, profile(Profession.FLETCHER,
            core(Profession.FLETCHER, Attribute.DEXTERITY), core(Profession.FLETCHER, Attribute.WITS),
            support(Profession.FLETCHER, Attribute.FOCUS)));
        register(profiles, profile(Profession.WEAVER,
            core(Profession.WEAVER, Attribute.DEXTERITY), core(Profession.WEAVER, Attribute.FOCUS),
            support(Profession.WEAVER, Attribute.WITS)));
        register(profiles, profile(Profession.TANNER,
            core(Profession.TANNER, Attribute.DEXTERITY), core(Profession.TANNER, Attribute.STRENGTH),
            support(Profession.TANNER, Attribute.STAMINA)));
        register(profiles, profile(Profession.MINER,
            core(Profession.MINER, Attribute.STRENGTH), core(Profession.MINER, Attribute.PERCEPTION),
            support(Profession.MINER, Attribute.STAMINA)));
        register(profiles, profile(Profession.INNKEEPER,
            core(Profession.INNKEEPER, Attribute.PRESENCE), core(Profession.INNKEEPER, Attribute.SPIRIT),
            support(Profession.INNKEEPER, Attribute.WITS)));
        register(profiles, profile(Profession.SCHOLAR,
            core(Profession.SCHOLAR, Attribute.WITS), core(Profession.SCHOLAR, Attribute.FOCUS),
            support(Profession.SCHOLAR, Attribute.SPIRIT)));
        register(profiles, profile(Profession.MILLER,
            core(Profession.MILLER, Attribute.STRENGTH), core(Profession.MILLER, Attribute.FOCUS),
            support(Profession.MILLER, Attribute.STAMINA)));
        register(profiles, profile(Profession.BREWER,
            core(Profession.BREWER, Attribute.WITS), core(Profession.BREWER, Attribute.DEXTERITY),
            support(Profession.BREWER, Attribute.FOCUS)));
        register(profiles, profile(Profession.ARCHER,
            core(Profession.ARCHER, Attribute.DEXTERITY), core(Profession.ARCHER, Attribute.PERCEPTION),
            support(Profession.ARCHER, Attribute.FOCUS)));
        register(profiles, profile(Profession.ARMOURER,
            core(Profession.ARMOURER, Attribute.STRENGTH), core(Profession.ARMOURER, Attribute.DEXTERITY),
            support(Profession.ARMOURER, Attribute.FOCUS)));
        register(profiles, profile(Profession.HERDER,
            core(Profession.HERDER, Attribute.SPIRIT), core(Profession.HERDER, Attribute.PERCEPTION),
            support(Profession.HERDER, Attribute.STAMINA)));
        register(profiles, profile(Profession.FISHER,
            core(Profession.FISHER, Attribute.DEXTERITY), core(Profession.FISHER, Attribute.PERCEPTION),
            support(Profession.FISHER, Attribute.STAMINA)));
        register(profiles, profile(Profession.HUNTER,
            core(Profession.HUNTER, Attribute.PERCEPTION), core(Profession.HUNTER, Attribute.DEXTERITY),
            support(Profession.HUNTER, Attribute.STAMINA)));
        register(profiles, profile(Profession.SPEARMAN,
            core(Profession.SPEARMAN, Attribute.STRENGTH), core(Profession.SPEARMAN, Attribute.STAMINA),
            support(Profession.SPEARMAN, Attribute.DEXTERITY)));
        register(profiles, profile(Profession.LONGSWORDSMAN,
            core(Profession.LONGSWORDSMAN, Attribute.STRENGTH), core(Profession.LONGSWORDSMAN, Attribute.DEXTERITY),
            support(Profession.LONGSWORDSMAN, Attribute.STAMINA)));
        register(profiles, profile(Profession.HEALER,
            core(Profession.HEALER, Attribute.SPIRIT), core(Profession.HEALER, Attribute.DEXTERITY),
            support(Profession.HEALER, Attribute.WITS)));
        register(profiles, profile(Profession.RUNE_MAGE,
            core(Profession.RUNE_MAGE, Attribute.FOCUS), core(Profession.RUNE_MAGE, Attribute.WITS),
            support(Profession.RUNE_MAGE, Attribute.SPIRIT)));
        register(profiles, profile(Profession.BUILDER,
            core(Profession.BUILDER, Attribute.DEXTERITY), core(Profession.BUILDER, Attribute.WITS),
            support(Profession.BUILDER, Attribute.STAMINA)));
        for (Profession profession : Profession.BY_ID) {
            if (profession.employed() && !profiles.containsKey(profession)) {
                throw new IllegalStateException("missing job profile: " + profession);
            }
        }
        return Map.copyOf(profiles);
    }

    private static JobAttributeProfile profile(Profession profession,
                                               Slot coreA, Slot coreB,
                                               Slot... support) {
        List<Slot> slots = new ArrayList<>(2 + support.length);
        slots.add(coreA);
        slots.add(coreB);
        slots.addAll(List.of(support));
        return new JobAttributeProfile(profession, slots);
    }

    private static Slot core(Profession profession, Attribute attribute) {
        EffectId effect = effectFor(profession, attribute);
        return new Slot(attribute, Importance.CORE, effect, statusFor(effect));
    }

    private static Slot support(Profession profession, Attribute attribute) {
        EffectId effect = effectFor(profession, attribute);
        return new Slot(attribute, Importance.SUPPORT, effect, statusFor(effect));
    }

    /** What this attribute does in this job; every answer is a live effect. */
    static EffectId effectFor(Profession profession, Attribute attribute) {
        return switch (attribute) {
            case STRENGTH -> switch (profession) {
                case LUMBERER -> EffectId.LUMBER_CONTACTS;
                case GUARD, SPEARMAN, LONGSWORDSMAN -> EffectId.MELEE_DAMAGE;
                case COURIER -> EffectId.HAUL_CAPACITY;
                default -> EffectId.WORK_PACE;
            };
            case STAMINA -> EffectId.ENDURANCE;
            case WITS -> profession == Profession.SCHOLAR ? EffectId.RESEARCH
                : EffectId.LEARNING_RATE;
            case DEXTERITY -> switch (profession) {
                case ARCHER -> EffectId.RANGED;
                case FISHER -> EffectId.FISHING;
                case SPEARMAN, LONGSWORDSMAN -> EffectId.MELEE_TEMPO;
                default -> EffectId.WORK_PACE;
            };
            case SPIRIT -> switch (profession) {
                case HEALER -> EffectId.HEAL_AMOUNT;
                case HERDER -> EffectId.WORK_PACE;
                default -> EffectId.MORALE;
            };
            case PERCEPTION -> switch (profession) {
                case ARCHER, HUNTER -> EffectId.DETECTION;
                default -> EffectId.EXTRA_FIND;
            };
            case FOCUS -> switch (profession) {
                case SCHOLAR -> EffectId.RESEARCH;
                case ARCHER, RUNE_MAGE -> EffectId.DRAW_CAST;
                default -> EffectId.CRAFT_TIME;
            };
            case PRESENCE -> switch (profession) {
                case INNKEEPER -> EffectId.HOSPITALITY;
                case TRADER -> EffectId.TRADE_PRICE;
                default -> EffectId.PERSUASION;
            };
        };
    }

    /**
     * Evidence per effect. LIVE_VERIFIED = wired AND exercised by a GameTest
     * (AttributeGameTests, batch attributes_*, or the older rank / lumber /
     * effort suites); CALCULATOR_READY = wired with a pure tested formula
     * only, not yet measured in a running world.
     */
    static EffectStatus statusFor(EffectId effect) {
        return switch (effect) {
            case WORK_PACE, MELEE_DAMAGE, MELEE_TEMPO, HAUL_CAPACITY, LUMBER_CONTACTS,
                 ENDURANCE, LEARNING_RATE, RESEARCH, RANGED, FISHING, DETECTION,
                 EXTRA_FIND, DRAW_CAST, CRAFT_TIME, HEAL_AMOUNT, MORALE, HOSPITALITY,
                 PERSUASION, TRADE_PRICE -> EffectStatus.LIVE_VERIFIED;
        };
    }

    private static void register(Map<Profession, JobAttributeProfile> profiles,
                                 JobAttributeProfile profile) {
        if (profiles.put(profile.profession(), profile) != null) {
            throw new IllegalStateException("duplicate job profile: "
                + profile.profession());
        }
    }
}
