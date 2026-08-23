package com.hearthstead.building;

/**
 * What a hung plaque currently knows about its room.
 *
 * <p>These are the states the block can be IN. {@code NO_PERMISSION} from the
 * spec is deliberately absent: permission is a property of the player looking,
 * not of the plaque, so it is decided per viewer when a screen opens and never
 * stored or synced. Storing it would make one player's rights visible in
 * another player's world state.
 */
public enum PlaqueState {
    /** Hung, but no enclosed room was found from any seed candidate. */
    UNLINKED,
    /** A room was found, but its requirements are not all satisfied yet. */
    INCOMPLETE,
    /** Requirements met; the building is registered and live. */
    LINKED,
    /** The building this plaque declared is gone from the settlement. */
    ORPHANED;

    public boolean hasBuilding() {
        return this == LINKED;
    }

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static PlaqueState byId(String id) {
        for (PlaqueState state : values()) {
            if (state.id().equals(id)) {
                return state;
            }
        }
        return UNLINKED;
    }
}
