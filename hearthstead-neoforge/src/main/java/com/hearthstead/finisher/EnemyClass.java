package com.hearthstead.finisher;

/**
 * Who is being executed, as far as the choreography cares. A Brute captain is
 * choreographed as a BRUTE (its size decides the moves) but still drops the
 * captain's trophy (see FinisherService).
 */
public enum EnemyClass {
    SKIRMISHER,
    BRUTE,
    CAPTAIN,
    GOBLIN,
    /** Any other hostile (zombies, skeletons, pillagers, modded mobs). */
    OTHER;

    /** Pure classification so JUnit can pin the precedence. */
    public static EnemyClass of(boolean raider, boolean brute, boolean captain, boolean goblin) {
        if (!raider) {
            return OTHER;
        }
        if (goblin) {
            return GOBLIN;
        }
        if (brute) {
            return BRUTE;
        }
        if (captain) {
            return CAPTAIN;
        }
        return SKIRMISHER;
    }

    /** Big enough for the co-op double execution. */
    public boolean allowsDouble() {
        return this == BRUTE || this == CAPTAIN;
    }

    /** Camera-shake / dust scale: size of the body hitting the ground. */
    public float impactScale() {
        return switch (this) {
            case BRUTE -> 1.0F;
            case CAPTAIN -> 0.8F;
            case GOBLIN -> 0.35F;
            default -> 0.6F;
        };
    }
}
