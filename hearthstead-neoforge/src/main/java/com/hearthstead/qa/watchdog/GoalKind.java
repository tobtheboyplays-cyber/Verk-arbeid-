package com.hearthstead.qa.watchdog;

/**
 * What the dominant running goal of a settler is doing, for the watchdog's
 * time accounting. Ordered by precedence: when several goals run at once the
 * highest ordinal wins (a guard in combat is fighting even while the door goal
 * also runs).
 */
public enum GoalKind {
    /** Nothing but look/door/float helpers: the settler is doing nothing. */
    NONE,
    /** Stroll, free time, companion chatter, salutes, drifting home. */
    IDLE,
    /** Walking to the scheduled post. Part of the workday, not output. */
    COMMUTE,
    /** The trade goal itself. */
    WORK,
    /** Equipment pick-up, self-crafting a tool, repairs of the kit. */
    SUPPORT,
    /** Eating, sleeping, tavern visit, recovery. Legitimate non-work. */
    NEED,
    /** Panic, combat, alarms, summons. Legitimate non-work. */
    THREAT;

    public static final GoalKind[] VALUES = values();

    /** A goal kind that means "the settler is trying to do their job". */
    public boolean jobShaped() {
        return this == WORK || this == COMMUTE || this == SUPPORT;
    }

    /** No goal that could explain the settler's time. */
    public boolean idle() {
        return this == NONE || this == IDLE;
    }
}
