package com.hearthstead.building;

import java.util.Locale;

/**
 * What a hung plaque currently knows about its room.
 *
 * <p>These are the states the block can be IN. {@code NO_PERMISSION} from the
 * spec is deliberately absent: permission is a property of the player looking,
 * not of the plaque, so it is decided per viewer when a screen opens and never
 * stored or synced. Storing it would make one player's rights visible in
 * another player's world state.
 *
 * <p>D-006: a plaque is hung blank and does nothing until a separate Build
 * Plan item is inserted into it. {@link #EMPTY} is that blank state — no UI,
 * no survey, a dark lamp. Inserting a plan is what starts the surveyor.
 */
public enum PlaqueState {
    /** Hung, no Build Plan inserted. No UI opens; nothing is ever surveyed. */
    EMPTY,
    /** A plan is fitted, but no enclosed room was found from any seed candidate. */
    PLAN_INSERTED_UNLINKED,
    /** A room was found, but its requirements are not all satisfied yet. */
    LINKED_INCOMPLETE,
    /** Requirements met; the building is registered and live. */
    LINKED_VALID,
    /** The building this plaque declared is gone from the settlement. */
    ORPHANED;

    public boolean hasBuilding() {
        return this == LINKED_VALID;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Ids written by saves from before PLAQUE-1's state rename. Kept as a
     * lookup table, never as enum ids themselves, so the new names are the
     * only ones this build ever writes.
     */
    private static PlaqueState legacyAlias(String id) {
        return switch (id) {
            case "unlinked" -> PLAN_INSERTED_UNLINKED;
            case "incomplete" -> LINKED_INCOMPLETE;
            case "linked" -> LINKED_VALID;
            case "orphaned" -> ORPHANED;
            default -> null;
        };
    }

    /**
     * Resolves a saved state id. Old-world ids are mapped forward explicitly
     * (see {@link #legacyAlias}). An id this build has never heard of at all
     * falls back on whether the tag still carries a {@code Building} UUID:
     * with one, {@link #LINKED_VALID} — this plaque was linked to something,
     * and the next survey corrects that downward if it no longer qualifies;
     * with none, {@link #EMPTY}.
     *
     * <p>This must never default blindly to {@code EMPTY} regardless of the
     * building id: renaming the enum's ids means every plaque already saved
     * in a live world writes an id this build cannot recognise as a fresh
     * literal match, and a bare {@code EMPTY} fallback would silently un-home
     * every already-registered building on its first post-update load. See
     * {@code PLAN_PLAQUE-1.md}, "W2 refined".
     */
    public static PlaqueState byId(String id, boolean hasBuildingId) {
        for (PlaqueState state : values()) {
            if (state.id().equals(id)) {
                return state;
            }
        }
        PlaqueState legacy = legacyAlias(id);
        if (legacy != null) {
            return legacy;
        }
        return hasBuildingId ? LINKED_VALID : EMPTY;
    }
}
