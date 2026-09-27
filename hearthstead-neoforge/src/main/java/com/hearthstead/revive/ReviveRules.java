package com.hearthstead.revive;

/**
 * Pure, Minecraft-free rules for the co-op "downed / revive your comrade"
 * feature: who goes down instead of dying, the bleed-out timer, the revive
 * hold and the numbers a revive restores. Everything here is deterministic so
 * JUnit covers it without a server; {@link ReviveService} feeds it live state.
 */
public final class ReviveRules {
    /** Server ticks per second. */
    public static final int TPS = 20;
    /**
     * A held use key re-sends its interact packet every 4 client ticks
     * (Minecraft#rightClickDelay). A revive stays alive while pings keep
     * arriving within this many ticks; a gap longer than it means the key was
     * released or the reviver looked away.
     */
    public static final int REVIVE_PING_GRACE_TICKS = 7;
    /** Two sneak+use pings closer than this are one held press, not a new toggle. */
    public static final int DRAG_TOGGLE_DEBOUNCE_TICKS = 7;
    /** A reviver must stay within this many blocks (squared below) of the downed player. */
    public static final double REVIVE_REACH = 3.5D;
    public static final double REVIVE_REACH_SQR = REVIVE_REACH * REVIVE_REACH;
    /** A drag tether breaks beyond this distance. */
    public static final double DRAG_BREAK_DISTANCE = 6.0D;
    /** Teammates see downed allies (icon through walls) within this range. */
    public static final double MARKER_RANGE = 64.0D;

    private ReviveRules() {
    }

    /** Everything the "down instead of die?" decision needs, already resolved. */
    public record DownContext(boolean featureEnabled,
                              boolean creativeOrSpectator,
                              boolean bypassesInvulnerability,
                              boolean alreadyDowned,
                              boolean raidActiveOrGrace,
                              boolean atSettlement,
                              boolean standingAllyNearby,
                              boolean soloAllowed) {
    }

    /**
     * True when a lethal hit should down the player instead of killing them.
     *
     * <p>Never for /kill, the void or anything else that bypasses
     * invulnerability (that includes our own bleed-out damage), never in
     * creative or spectator, never twice (a downed player who is hit to zero
     * again is finished off), and only during or just after a raid at the
     * player's settlement with a standing teammate near it (unless the solo
     * option is on).
     */
    public static boolean shouldDown(DownContext c) {
        if (c == null || !c.featureEnabled() || c.creativeOrSpectator()
            || c.bypassesInvulnerability() || c.alreadyDowned()) {
            return false;
        }
        if (!c.raidActiveOrGrace() || !c.atSettlement()) {
            return false;
        }
        return c.standingAllyNearby() || c.soloAllowed();
    }

    /** How many times (once per second) a refused crash-recovery kill is retried before waiting for the next login. */
    public static final int LOGIN_RECOVERY_RETRIES = 5;

    /** What to do for a player whose save still carries the "downed" crash marker. */
    public enum LoginRecovery {
        /** No marker: nothing to do. */
        NOTHING,
        /** Already dead, or creative/spectator: the marker can go, nobody is killed. */
        CLEAR_MARKER,
        /** Alive survival player saved while down: bleed them out now. */
        KILL
    }

    /**
     * Crash recovery decision. The marker is only ever cleared once death is
     * confirmed ({@link #markerMayClear}); a refused kill keeps it so the
     * next attempt (a retry or the next login) still applies.
     */
    public static LoginRecovery loginRecovery(boolean marker, boolean alive, boolean creativeOrSpectator) {
        if (!marker) {
            return LoginRecovery.NOTHING;
        }
        if (!alive || creativeOrSpectator) {
            return LoginRecovery.CLEAR_MARKER;
        }
        return LoginRecovery.KILL;
    }

    /** After a recovery kill attempt: the marker may go only if the player really died. */
    public static boolean markerMayClear(boolean aliveAfterKill) {
        return !aliveAfterKill;
    }

    /** True while {@code now} is within {@code graceTicks} after the raid was last seen active. */
    public static boolean inGrace(long now, long lastActive, long graceTicks) {
        if (lastActive < 0L || graceTicks <= 0L) {
            return false;
        }
        long since = now - lastActive;
        return since >= 0L && since <= graceTicks;
    }

    /** Health (half-hearts) a revive restores: a percentage of max, at least 1. */
    public static float reviveHealth(float maxHealth, int percent) {
        int clamped = Math.max(1, Math.min(100, percent));
        float health = maxHealth * clamped / 100.0F;
        return Math.max(1.0F, Math.min(maxHealth, health));
    }

    /** Seconds (config) to ticks, at least one tick. */
    public static int secondsToTicks(double seconds) {
        return Math.max(1, (int) Math.round(seconds * TPS));
    }

    /**
     * Heartbeat period for the downed player's client, in ticks: a calm
     * 1.1 s at the start of the bleed-out, racing to 0.66 s at the end (the
     * heartbeat sample itself is 0.62 s long).
     */
    public static int heartbeatInterval(int ticksLeft, int ticksTotal) {
        float fraction = ticksTotal <= 0 ? 0.0F
            : Math.max(0.0F, Math.min(1.0F, ticksLeft / (float) ticksTotal));
        return Math.round(13.0F + 9.0F * fraction);
    }

    /** Bleed-out countdown. Paused while a revive is in progress. */
    public static final class BleedTimer {
        private final int total;
        private int left;

        public BleedTimer(int totalTicks) {
            this.total = Math.max(1, totalTicks);
            this.left = this.total;
        }

        /** Advances one tick unless paused; returns true exactly when it runs out. */
        public boolean tick(boolean paused) {
            if (paused || left <= 0) {
                return false;
            }
            left--;
            return left == 0;
        }

        public boolean expired() {
            return left <= 0;
        }

        public int ticksLeft() {
            return left;
        }

        public int totalTicks() {
            return total;
        }
    }

    /** What one server tick did to a revive in progress. */
    public enum Step {
        /** Nobody is reviving. */
        IDLE,
        /** Still holding; progress advanced. */
        PROGRESS,
        /** The hold reached the required time this tick. */
        COMPLETE,
        /** Pings stopped (key released / looked away): progress is lost. */
        LAPSED
    }

    /**
     * Server-side progress of one held revive. The reviver's client re-sends
     * an interact packet every 4 ticks while the key is held; each is a
     * {@link #ping}. Progress advances one per server tick while pings are
     * fresh and resets on {@link #interrupt} (damage) or a lapse.
     */
    public static final class ReviveProgress {
        private final int required;
        private int progress;
        private long lastPing = Long.MIN_VALUE;
        private boolean active;

        public ReviveProgress(int requiredTicks) {
            this.required = Math.max(1, requiredTicks);
        }

        public void ping(long now) {
            lastPing = now;
            active = true;
        }

        public Step tick(long now) {
            if (!active) {
                return Step.IDLE;
            }
            if (now - lastPing > REVIVE_PING_GRACE_TICKS) {
                reset();
                return Step.LAPSED;
            }
            progress++;
            if (progress >= required) {
                progress = required;
                active = false;
                return Step.COMPLETE;
            }
            return Step.PROGRESS;
        }

        public void interrupt() {
            reset();
        }

        private void reset() {
            progress = 0;
            active = false;
            lastPing = Long.MIN_VALUE;
        }

        public boolean active() {
            return active;
        }

        public int progressTicks() {
            return progress;
        }

        public int requiredTicks() {
            return required;
        }

        public float fraction() {
            return progress / (float) required;
        }
    }
}
