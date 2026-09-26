package com.hearthstead.settlement;

/** Tavern guest hours share the village clock; safety and duty still gate each actor. */
public final class TavernVisitSchedule {
    private TavernVisitSchedule() {}

    /**
     * Minecraft tick0 is 06:00. Tavern visits are evening-only:
     * [11000,12700), 17:00-18:42. DayPhase owns the boundaries.
     * This is also the existing visit-continuation rule, not a new sleep grace.
     * Urgent Hearth food and already-owned meals do not use this predicate.
     */
    public static boolean isOpen(long dayTime) {
        DayPhase phase = DayPhase.of(dayTime);
        return phase.social();
    }
}
