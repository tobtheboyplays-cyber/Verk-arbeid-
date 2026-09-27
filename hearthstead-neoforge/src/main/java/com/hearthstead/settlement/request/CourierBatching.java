package com.hearthstead.settlement.request;

import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.development.TechTree;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * Stout Straps pickup batching (owner, 26 Sep: "real two-per-trip now").
 *
 * <p>A Courier with free carry capacity who has just lifted request A may
 * reserve ONE more request B whose source container is within
 * {@link #PARTNER_RADIUS} blocks, picks B up, delivers B, then delivers A.
 * A's cargo waits in the Courier's separate {@code batchStow} container
 * while B runs, so the bag keeps its one rule: it holds exactly the active
 * request's items (every ledger/session check stays as it was).
 *
 * <p>Two numbers, on purpose:
 * <ul>
 *   <li>{@link #maxNewOwned}: how many requests a Courier may RESERVE up to --
 *       1, or 2 with the switch on AND Stout Straps learned. Only new
 *       reservations read it.</li>
 *   <li>{@link #MAX_OWNED}: the hard ownership ceiling recovery accepts (2).
 *       It never depends on the switch, so turning
 *       {@code logistics.courierBatching} off mid-trip lets a batch already
 *       under way finish instead of quarantining the ledger.</li>
 * </ul>
 */
public final class CourierBatching {
    /** Hard ceiling of requests one Courier may own at once. */
    public static final int MAX_OWNED = 2;
    /** B's source container must be this close to where A was lifted. */
    public static final int PARTNER_RADIUS = 16;
    /** The tech node that turns batching on. */
    public static final String NODE = "stout_straps";

    private static volatile Boolean testOverride;

    private CourierBatching() {
    }

    /**
     * The server switch {@code [logistics] courierBatching} (default on). A
     * JVM property {@code -Dhearthstead.courierBatching=false} overrides it,
     * so the GameTest server can run every Courier batch with the switch off.
     */
    public static boolean enabled() {
        Boolean override = testOverride;
        if (override != null) {
            return override;
        }
        String property = System.getProperty("hearthstead.courierBatching");
        if (property != null) {
            return Boolean.parseBoolean(property);
        }
        return HearthsteadServerConfig.courierBatchingEnabled();
    }

    /** GameTest/JUnit hook; {@code null} restores the config value. */
    public static void overrideForTests(@Nullable Boolean enabled) {
        testOverride = enabled;
    }

    /** True when this settlement may form NEW batches right now. */
    public static boolean allowed(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return enabled() && level != null && settlement != null
            && TechTree.has(level, settlement, NODE);
    }

    /** Requests a Courier of this settlement may reserve up to (1 or 2). */
    public static int maxNewOwned(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return allowed(level, settlement) ? MAX_OWNED : 1;
    }

    /** Pure N rule: 2 only with the switch on AND Stout Straps learned, else 1. */
    public static int maxNewOwned(boolean switchOn, boolean strapsLearned) {
        return switchOn && strapsLearned ? MAX_OWNED : 1;
    }

    /** Pure recovery ceiling: never more than {@link #MAX_OWNED}, whatever the switch says. */
    public static boolean ownershipAllowed(int owned) {
        return owned >= 0 && owned <= MAX_OWNED;
    }

    /**
     * Pure conservation rule for the stow: it holds exactly one request's
     * remaining cargo ({@code matching}) and nothing else ({@code total}).
     */
    public static boolean stowHolds(int matching, int total, int remaining) {
        return matching > 0 && matching == total && matching == remaining;
    }

    /** Pure capacity rule: both loads together fit one trip's carry budget. */
    public static boolean fits(int stowedCount, int partnerCount, int carryCapacity) {
        return stowedCount > 0 && partnerCount > 0
            && (long) stowedCount + partnerCount <= carryCapacity;
    }

    /** Pure distance rule, squared block distance from where A was lifted. */
    public static boolean near(double distanceSqr) {
        return distanceSqr <= (double) PARTNER_RADIUS * PARTNER_RADIUS;
    }

    /** Request kinds whose whole route runs through the ledger and may batch. */
    public static boolean batchable(RequestType type) {
        return type == RequestType.OUTPUT_PICKUP || type == RequestType.FOOD
            || type == RequestType.MATERIAL_INPUT || type == RequestType.AMMUNITION;
    }
}
