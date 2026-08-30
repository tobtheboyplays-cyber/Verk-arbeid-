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

    /** Stable semantic identifiers; localized copy may change independently. */
    public enum EffectId {
        PHYSICAL_OUTPUT,
        FATIGUE_PACE,
        LEARNING_RATE,
        PRECISION_EXECUTION,
        MORALE_RESILIENCE,
        TARGET_DISCOVERY,
        TASK_CONTINUITY,
        SOCIAL_INFLUENCE,
        CARRY_CAPACITY,
        LUMBER_CONTACTS
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

    public static Optional<JobAttributeProfile> find(Profession profession) {
        return Optional.ofNullable(REGISTRY.get(profession));
    }

    public static Map<Profession, JobAttributeProfile> all() {
        return REGISTRY;
    }

    private static Map<Profession, JobAttributeProfile> build() {
        EnumMap<Profession, JobAttributeProfile> profiles =
            new EnumMap<>(Profession.class);
        register(profiles, profile(Profession.FARMER,
            core(Attribute.DEXTERITY), core(Attribute.STAMINA),
            support(Attribute.FOCUS)));
        register(profiles, profile(Profession.LUMBERER,
            core(Attribute.STRENGTH, EffectId.LUMBER_CONTACTS),
            core(Attribute.STAMINA), support(Attribute.WITS)));
        register(profiles, profile(Profession.GUARD,
            core(Attribute.STRENGTH), core(Attribute.STAMINA),
            support(Attribute.PRESENCE)));
        register(profiles, profile(Profession.COURIER,
            core(Attribute.STRENGTH, EffectId.CARRY_CAPACITY),
            core(Attribute.STAMINA)));
        register(profiles, profile(Profession.BAKER,
            core(Attribute.DEXTERITY), core(Attribute.FOCUS),
            support(Attribute.STAMINA)));
        register(profiles, profile(Profession.COOK,
            core(Attribute.DEXTERITY), core(Attribute.WITS),
            support(Attribute.STAMINA)));
        register(profiles, profile(Profession.BUTCHER,
            core(Attribute.STRENGTH), core(Attribute.DEXTERITY),
            support(Attribute.STAMINA)));
        register(profiles, profile(Profession.SMELTER,
            core(Attribute.STRENGTH), core(Attribute.FOCUS),
            support(Attribute.STAMINA)));
        register(profiles, profile(Profession.SMITH,
            core(Attribute.STRENGTH), core(Attribute.DEXTERITY),
            support(Attribute.FOCUS)));
        register(profiles, profile(Profession.SAWYER,
            core(Attribute.DEXTERITY), core(Attribute.STRENGTH),
            support(Attribute.STAMINA)));
        register(profiles, profile(Profession.CARPENTER,
            core(Attribute.DEXTERITY), core(Attribute.WITS),
            support(Attribute.FOCUS)));
        register(profiles, profile(Profession.MASON,
            core(Attribute.STRENGTH), core(Attribute.DEXTERITY),
            support(Attribute.STAMINA)));
        register(profiles, profile(Profession.FLETCHER,
            core(Attribute.DEXTERITY), core(Attribute.WITS),
            support(Attribute.FOCUS)));
        register(profiles, profile(Profession.WEAVER,
            core(Attribute.DEXTERITY), core(Attribute.FOCUS),
            support(Attribute.WITS)));
        register(profiles, profile(Profession.TANNER,
            core(Attribute.DEXTERITY), core(Attribute.STRENGTH),
            support(Attribute.STAMINA)));
        register(profiles, profile(Profession.MINER,
            core(Attribute.STRENGTH), core(Attribute.PERCEPTION),
            support(Attribute.STAMINA)));
        register(profiles, profile(Profession.INNKEEPER,
            core(Attribute.PRESENCE), core(Attribute.SPIRIT),
            support(Attribute.WITS)));
        register(profiles, profile(Profession.SCHOLAR,
            core(Attribute.WITS), core(Attribute.FOCUS),
            support(Attribute.SPIRIT)));
        register(profiles, profile(Profession.MILLER,
            core(Attribute.STRENGTH), core(Attribute.FOCUS),
            support(Attribute.STAMINA)));
        register(profiles, profile(Profession.BREWER,
            core(Attribute.WITS), core(Attribute.DEXTERITY),
            support(Attribute.FOCUS)));
        register(profiles, profile(Profession.ARCHER,
            core(Attribute.DEXTERITY), core(Attribute.PERCEPTION),
            support(Attribute.PRESENCE)));
        register(profiles, profile(Profession.ARMOURER,
            core(Attribute.STRENGTH), core(Attribute.DEXTERITY),
            support(Attribute.FOCUS)));
        register(profiles, profile(Profession.HERDER,
            core(Attribute.SPIRIT), core(Attribute.PERCEPTION),
            support(Attribute.STAMINA)));
        register(profiles, profile(Profession.FISHER,
            core(Attribute.PERCEPTION), core(Attribute.DEXTERITY),
            support(Attribute.STAMINA)));
        register(profiles, profile(Profession.HUNTER,
            core(Attribute.PERCEPTION), core(Attribute.DEXTERITY),
            support(Attribute.STAMINA)));

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

    private static Slot core(Attribute attribute) {
        return core(attribute, effectFor(attribute));
    }

    private static Slot core(Attribute attribute, EffectId effect) {
        return new Slot(attribute, Importance.CORE, effect, statusFor(effect));
    }

    private static Slot support(Attribute attribute) {
        EffectId effect = effectFor(attribute);
        return new Slot(attribute, Importance.SUPPORT, effect, statusFor(effect));
    }

    private static EffectId effectFor(Attribute attribute) {
        return switch (attribute) {
            case STRENGTH -> EffectId.PHYSICAL_OUTPUT;
            case STAMINA -> EffectId.FATIGUE_PACE;
            case WITS -> EffectId.LEARNING_RATE;
            case DEXTERITY -> EffectId.PRECISION_EXECUTION;
            case SPIRIT -> EffectId.MORALE_RESILIENCE;
            case PERCEPTION -> EffectId.TARGET_DISCOVERY;
            case FOCUS -> EffectId.TASK_CONTINUITY;
            case PRESENCE -> EffectId.SOCIAL_INFLUENCE;
        };
    }

    private static EffectStatus statusFor(EffectId effect) {
        return switch (effect) {
            case FATIGUE_PACE, CARRY_CAPACITY, LUMBER_CONTACTS ->
                EffectStatus.CALCULATOR_READY;
            default -> EffectStatus.FOUNDATION_ONLY;
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
