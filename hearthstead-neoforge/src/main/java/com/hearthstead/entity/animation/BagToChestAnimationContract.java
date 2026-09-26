package com.hearthstead.entity.animation;

/**
 * Runtime binding for the independently reviewed Blender candidate.
 *
 * <p>The content hash is deliberately part of executable source: changing the
 * authored motion without changing this identity must fail the focused source
 * gate. Inventory remains owned by the courier goal; this class only defines
 * the one-shot's 20 TPS contact clock.</p>
 */
public final class BagToChestAnimationContract {
    public static final String CANDIDATE_HASH = "e91d5ba0f661be9d";
    public static final int DURATION_TICKS = 80;
    public static final int BAG_WORLD_CONTACT_TICK = 12;
    public static final int ITEM_HAND_CONTACT_TICK = 30;
    public static final int LID_CONTACT_TICK = 31;
    public static final int LID_OPEN_TICK = 36;
    public static final int LID_RELEASE_TICK = 37;
    public static final int DEPOSIT_COMMIT_TICK = 48;
    public static final int ITEM_BELOW_RIM_TICK = 49;
    public static final int LID_CLOSED_TICK = 64;

    /** Re-enter the planted sorting pose after the previous lid closes. */
    public static final int GROUNDED_REPEAT_TICK = 24;

    public static boolean continuesGroundedSession(int clock, boolean committed,
                                                    boolean completionPending) {
        return clock == LID_CLOSED_TICK && committed && !completionPending;
    }

    private BagToChestAnimationContract() {
    }

    /** True once per cycle, exactly where the visible item reaches the chest. */
    public static boolean mayCommit(int elapsedTicks, boolean alreadyCommitted) {
        return !alreadyCommitted
            && Math.floorMod(elapsedTicks, DURATION_TICKS) == DEPOSIT_COMMIT_TICK;
    }

    /** The next visual cycle may start only after the previous 80 ticks finish. */
    public static boolean beginsCycle(int elapsedTicks) {
        return elapsedTicks > 0
            && Math.floorMod(elapsedTicks, DURATION_TICKS) == 0;
    }
}
