package com.hearthstead.qa.watchdog;

/** The stall classes the worker watchdog reports. Diagnostic only. */
public enum WatchdogFlag {
    /** Work goal active in a work phase, but no displacement and no output. */
    STUCK,
    /** Employed, in a work phase, and no work-shaped goal running at all. */
    IDLE_IN_WORK,
    /** The same work goal restarting over and over without producing anything. */
    LOOP,
    /** Repeated recorded route failures / vanilla navigation stuck detection. */
    PATH_FAIL,
    /** The same non-empty carried inventory held for a very long time. */
    ORPHANED_ITEMS;

    public static final WatchdogFlag[] VALUES = values();

    public int bit() {
        return 1 << ordinal();
    }
}
