package com.hearthstead.qa.watchdog;

/**
 * Pure, allocation-free stall detector behind the worker watchdog.
 *
 * <p>No Minecraft types: the live sampler copies a settler's observable state
 * into a reusable {@link Sample}, and this class advances one {@link Track}
 * per settler. Every rule is expressed in game ticks, so a {@code /tick sprint}
 * soak ages exactly like real play. Nothing here can influence a settler: the
 * detector only ever sees copies.
 */
public final class WatchdogDetector {
    /** A stall must persist this long before it is flagged (60 s). */
    public static final long STALL_TICKS = 1_200L;
    /** Moving back and forth without any output for this long is a stall too (3 min). */
    public static final long NO_OUTPUT_TICKS = 3_600L;
    /** Unchanged carried inventory for this long is orphaned (10 min). */
    public static final long ORPHAN_TICKS = 12_000L;
    /** Displacement that counts as "meaningful movement". */
    public static final double MOVE_EPSILON = 2.0D;
    /** Work-goal starts within {@link #STALL_TICKS} that count as a loop. */
    public static final int LOOP_STARTS = 5;
    /** Route failures within {@link #STALL_TICKS} that count as a path failure. */
    public static final int PATH_FAILS = 3;
    /** A sample gap larger than this means the settler was unloaded: reset timers. */
    public static final long GAP_TICKS = 200L;

    /** Reusable input. The sampler overwrites every field each time. */
    public static final class Sample {
        public long tick;
        public boolean workPhase;
        public boolean employed;
        /** Guards/archers holding a post may legitimately stand still. */
        public boolean stationaryOk;
        /** A patrol/watch produces nothing but presence: exempt from the no-output rule. */
        public boolean outputFree;
        public GoalKind kind = GoalKind.NONE;
        /** Stable identity of the dominant job-shaped goal instance, 0 when none. */
        public int jobGoalId;
        public double x;
        public double y;
        public double z;
        /** Hash of everything the settler carries (bag + both hands). */
        public int carryHash;
        public boolean carrying;
        /** Any output witness: effort spent, trade XP, activity, carry hash. */
        public int outputHash;
        /** Game tick of the latest recorded route failure, or Long.MIN_VALUE. */
        public long routeFailureTick = Long.MIN_VALUE;
        /** Vanilla navigation's own stuck detection fired this sample. */
        public boolean navigationStuck;
        /** Energy + hunger: a NEED goal that never raises this is not recovering. */
        public float needLevel;
    }

    /** Per-settler state. One instance per settler for the whole session. */
    public static final class Track {
        public long lastSampleTick = Long.MIN_VALUE;
        double anchorX;
        double anchorY;
        double anchorZ;
        long lastMoveTick;
        int lastOutputHash;
        long lastOutputTick;
        GoalKind lastKind = GoalKind.NONE;
        long kindSince;
        int lastJobGoalId;
        long jobGoalSince;
        final long[] starts = new long[8];
        int startCursor;
        long lastRouteFailureTick = Long.MIN_VALUE;
        final long[] routeFails = new long[8];
        int routeCursor;
        int lastCarryHash;
        long carrySince = Long.MIN_VALUE;
        long idleSince = Long.MIN_VALUE;
        float needMark;
        long lastNeedGainTick;
        /** Bit set of {@link WatchdogFlag}s currently raised. */
        public int active;
        /** Tick each currently raised flag was raised at. */
        public final long[] raisedAt = new long[WatchdogFlag.VALUES.length];
        /** Sub-cause of the current STUCK episode: true = moving but no output. */
        public boolean stuckNoOutput;
        /** Sub-cause of the current STUCK episode: a need goal that never recovers. */
        public boolean stuckNeed;
        /** Whether this sample counted as productive work. */
        public boolean productive;
        /** Whether the previous processed sample was in a work phase. */
        boolean wasWorkPhase;

        public Track() {
            java.util.Arrays.fill(starts, Long.MIN_VALUE);
            java.util.Arrays.fill(routeFails, Long.MIN_VALUE);
        }

        public long lastOutputTick() {
            return lastOutputTick;
        }

        public long lastMoveTick() {
            return lastMoveTick;
        }

        public long jobGoalSince() {
            return jobGoalSince;
        }

        public long carrySince() {
            return carrySince;
        }

        public long idleSince() {
            return idleSince;
        }

        void reset(Sample s) {
            anchorX = s.x;
            anchorY = s.y;
            anchorZ = s.z;
            lastMoveTick = s.tick;
            lastOutputHash = s.outputHash;
            lastOutputTick = s.tick;
            lastKind = s.kind;
            kindSince = s.tick;
            lastJobGoalId = s.jobGoalId;
            jobGoalSince = s.tick;
            java.util.Arrays.fill(starts, Long.MIN_VALUE);
            java.util.Arrays.fill(routeFails, Long.MIN_VALUE);
            lastRouteFailureTick = s.routeFailureTick;
            lastCarryHash = s.carryHash;
            carrySince = s.carrying ? s.tick : Long.MIN_VALUE;
            idleSince = Long.MIN_VALUE;
            needMark = s.needLevel;
            lastNeedGainTick = s.tick;
            wasWorkPhase = s.workPhase;
        }
    }

    /** Result bits of one update: which flags were raised and which cleared. */
    public static final class Result {
        public int raised;
        public int cleared;
        /** For each cleared flag, how long its episode lasted. */
        public final long[] clearedDuration = new long[WatchdogFlag.VALUES.length];
    }

    private WatchdogDetector() {
    }

    /**
     * Advances one track with one sample. Writes the transitions into
     * {@code out} (which is cleared first) and never allocates.
     */
    public static void update(Track t, Sample s, Result out) {
        out.raised = 0;
        out.cleared = 0;
        if (t.lastSampleTick == Long.MIN_VALUE || s.tick - t.lastSampleTick > GAP_TICKS
            || s.tick < t.lastSampleTick) {
            // First sight, or the settler was unloaded/unsampled: time that
            // passed while nobody could observe it is not a stall. Close any
            // open episode so it is not stretched across the gap.
            closeAll(t, t.lastSampleTick == Long.MIN_VALUE ? s.tick : t.lastSampleTick, out);
            t.reset(s);
            t.lastSampleTick = s.tick;
            t.productive = s.workPhase && s.kind == GoalKind.WORK;
            return;
        }
        long now = s.tick;

        // --- movement ---------------------------------------------------
        double dx = s.x - t.anchorX;
        double dy = s.y - t.anchorY;
        double dz = s.z - t.anchorZ;
        if (dx * dx + dy * dy + dz * dz >= MOVE_EPSILON * MOVE_EPSILON) {
            t.anchorX = s.x;
            t.anchorY = s.y;
            t.anchorZ = s.z;
            t.lastMoveTick = now;
        }
        // --- output -----------------------------------------------------
        if (s.outputHash != t.lastOutputHash) {
            t.lastOutputHash = s.outputHash;
            t.lastOutputTick = now;
        }
        // --- goal identity ----------------------------------------------
        if (s.kind != t.lastKind) {
            t.lastKind = s.kind;
            t.kindSince = now;
            t.needMark = s.needLevel;
            t.lastNeedGainTick = now;
        } else if (s.needLevel >= t.needMark + 1.0F) {
            t.needMark = s.needLevel;
            t.lastNeedGainTick = now;
        }
        if (s.jobGoalId != t.lastJobGoalId) {
            if (s.jobGoalId != 0) {
                t.starts[t.startCursor] = now;
                t.startCursor = (t.startCursor + 1) % t.starts.length;
            }
            t.lastJobGoalId = s.jobGoalId;
            t.jobGoalSince = now;
        }
        // --- route failures ---------------------------------------------
        boolean newFailure = false;
        if (s.routeFailureTick != Long.MIN_VALUE && s.routeFailureTick != t.lastRouteFailureTick) {
            t.lastRouteFailureTick = s.routeFailureTick;
            newFailure = true;
        }
        if (newFailure || s.navigationStuck) {
            t.routeFails[t.routeCursor] = now;
            t.routeCursor = (t.routeCursor + 1) % t.routeFails.length;
        }
        // --- carry --------------------------------------------------------
        if (!s.carrying) {
            t.carrySince = Long.MIN_VALUE;
        } else if (t.carrySince == Long.MIN_VALUE || s.carryHash != t.lastCarryHash) {
            t.carrySince = now;
        }
        t.lastCarryHash = s.carryHash;
        // A new work phase starts every clock fresh: the night is not a stall.
        if (s.workPhase && !t.wasWorkPhase) {
            t.lastMoveTick = now;
            t.lastOutputTick = now;
            t.jobGoalSince = now;
            t.kindSince = now;
        }
        t.wasWorkPhase = s.workPhase;

        // --- rules ------------------------------------------------------
        boolean jobShaped = s.kind.jobShaped();
        long quietSince = Math.max(Math.max(t.lastMoveTick, t.lastOutputTick), t.kindSince);
        boolean staticStall = s.workPhase && jobShaped && !s.stationaryOk
            && now - quietSince >= STALL_TICKS;
        boolean noOutputStall = s.workPhase && jobShaped && !s.stationaryOk && !s.outputFree
            && now - Math.max(t.lastOutputTick, t.kindSince) >= NO_OUTPUT_TICKS;
        // A meal or a rest that runs into the workday must actually recover
        // the settler; one that walks about for minutes without raising
        // energy or hunger is holding the worker hostage (soak 2026-09-25:
        // the bed/Hearth oscillation).
        boolean needStall = s.workPhase && s.kind == GoalKind.NEED
            && now - Math.max(t.lastNeedGainTick, t.kindSince) >= NO_OUTPUT_TICKS;
        boolean stuck = staticStall || noOutputStall || needStall;
        if (stuck && (t.active & WatchdogFlag.STUCK.bit()) == 0) {
            t.stuckNeed = needStall && !staticStall && !noOutputStall;
            t.stuckNoOutput = !staticStall && !t.stuckNeed;
        }
        apply(t, WatchdogFlag.STUCK, stuck, now, out);

        if (s.workPhase && s.employed && s.kind.idle()) {
            if (t.idleSince == Long.MIN_VALUE) {
                t.idleSince = now;
            }
        } else {
            t.idleSince = Long.MIN_VALUE;
        }
        apply(t, WatchdogFlag.IDLE_IN_WORK,
            t.idleSince != Long.MIN_VALUE && now - t.idleSince >= STALL_TICKS, now, out);

        int recentStarts = countSince(t.starts, now - STALL_TICKS);
        boolean loop = s.workPhase && recentStarts >= LOOP_STARTS
            && now - t.lastOutputTick >= STALL_TICKS / 2;
        boolean loopActive = (t.active & WatchdogFlag.LOOP.bit()) != 0;
        // Hysteresis: once looping, stay flagged until output resumes or
        // the restarts stop for a whole window.
        if (loopActive) {
            loop = s.workPhase && recentStarts > 0 && now - t.lastOutputTick >= STALL_TICKS / 2;
        }
        apply(t, WatchdogFlag.LOOP, loop, now, out);

        int recentFails = countSince(t.routeFails, now - STALL_TICKS);
        boolean pathFail = recentFails >= PATH_FAILS
            || ((t.active & WatchdogFlag.PATH_FAIL.bit()) != 0 && recentFails > 0);
        apply(t, WatchdogFlag.PATH_FAIL, pathFail, now, out);

        apply(t, WatchdogFlag.ORPHANED_ITEMS,
            t.carrySince != Long.MIN_VALUE && now - t.carrySince >= ORPHAN_TICKS, now, out);

        t.productive = s.workPhase && s.kind == GoalKind.WORK
            && (t.active & (WatchdogFlag.STUCK.bit() | WatchdogFlag.LOOP.bit())) == 0;
        t.lastSampleTick = now;
    }

    /** Closes every open episode, e.g. when the settler leaves or dies. */
    public static void closeAll(Track t, long now, Result out) {
        for (WatchdogFlag flag : WatchdogFlag.VALUES) {
            if ((t.active & flag.bit()) != 0) {
                t.active &= ~flag.bit();
                out.cleared |= flag.bit();
                out.clearedDuration[flag.ordinal()] = Math.max(0L, now - t.raisedAt[flag.ordinal()]);
            }
        }
    }

    private static void apply(Track t, WatchdogFlag flag, boolean on, long now, Result out) {
        int bit = flag.bit();
        boolean was = (t.active & bit) != 0;
        if (on && !was) {
            t.active |= bit;
            t.raisedAt[flag.ordinal()] = now;
            out.raised |= bit;
        } else if (!on && was) {
            t.active &= ~bit;
            out.cleared |= bit;
            out.clearedDuration[flag.ordinal()] = now - t.raisedAt[flag.ordinal()];
        }
    }

    private static int countSince(long[] ring, long since) {
        int n = 0;
        for (long v : ring) {
            if (v != Long.MIN_VALUE && v >= since) {
                n++;
            }
        }
        return n;
    }
}
