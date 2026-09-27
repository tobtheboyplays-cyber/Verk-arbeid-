package com.hearthstead.building;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Named home tiers for a House (owner, 26 Sep: the plaque shows Hut, then
 * Cottage, Townhouse, Manor). A tier is what the room really is AND what the
 * village knows: the House's checklist level ({@link BuildingLevels}) must
 * reach the tier's level, and the tier's Commons node must be learned. Tiers
 * are climbed in order: a missing node stops the climb at the tier below it,
 * however well the room is furnished (the tree's own requires already make
 * Townhouses need Cottages and Manors need Townhouses).
 *
 * <pre>
 *   HUT        House level 1   Houses &amp; Lodging (home)
 *   COTTAGE    House level 2   Cottages (sturdy_beds)
 *   TOWNHOUSE  House level 3   Townhouses (two_storey_houses)
 *   MANOR      House level 4   Manors (manors)
 * </pre>
 *
 * Pure (a node predicate instead of a world), so JUnit covers the mapping.
 * What a tier DOES stays with its node's effect (CommonsEffects.homeMorale
 * reads the Manor tier); this class only says which tier a House is.
 */
public enum HomeTier {
    HUT(1, "home"),
    COTTAGE(2, "sturdy_beds"),
    TOWNHOUSE(3, "two_storey_houses"),
    MANOR(4, "manors");

    private final int level;
    private final String node;

    HomeTier(int level, String node) {
        this.level = level;
        this.node = node;
    }

    /** House checklist level this tier needs. */
    public int level() {
        return level;
    }

    /** Tech node that names this tier. */
    public String node() {
        return node;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The tier of a House at checklist {@code houseLevel} in a village where
     * {@code learned} tells which nodes are known. Null for an unregistered
     * room (level 0) or a village without the Houses node (cannot happen for
     * a registered House, whose plan needs it, but stays fail-closed).
     */
    @Nullable
    public static HomeTier of(int houseLevel, Predicate<String> learned) {
        HomeTier best = null;
        for (HomeTier tier : values()) {
            if (houseLevel < tier.level || !learned.test(tier.node)) {
                break;
            }
            best = tier;
        }
        return best;
    }

    /** The tier after this one, or null at the top. */
    @Nullable
    public HomeTier next() {
        int i = ordinal() + 1;
        return i < values().length ? values()[i] : null;
    }

    /** What the plaque says about the next tier. */
    public enum Goal {
        /** Build the next level; the node is already known. */
        BUILD,
        /** Build the next level and learn the node. */
        BUILD_AND_LEARN,
        /** The room already meets the next level: only the node is missing. */
        LEARN
    }

    /** The goal toward {@code next} for a House at {@code houseLevel}. */
    public static Goal goal(HomeTier next, int houseLevel, Predicate<String> learned) {
        boolean known = learned.test(next.node);
        if (houseLevel >= next.level) {
            return known ? Goal.BUILD : Goal.LEARN;
        }
        return known ? Goal.BUILD : Goal.BUILD_AND_LEARN;
    }
}
