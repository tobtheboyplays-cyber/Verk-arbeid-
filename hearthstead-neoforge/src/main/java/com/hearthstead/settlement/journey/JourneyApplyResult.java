package com.hearthstead.settlement.journey;

/** Result of applying one already-committed, server-domain event. */
public enum JourneyApplyResult {
    APPLIED,
    DUPLICATE,
    RECORDED_NON_PROGRESS,
    ALREADY_COMPLETED,
    IGNORED_PRESENTATION,
    QUARANTINED
}
