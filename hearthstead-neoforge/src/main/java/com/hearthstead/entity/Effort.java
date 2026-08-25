package com.hearthstead.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * A settler's daily labor pool: the natural limit under every trade.
 *
 * <h2>The problem this answers</h2>
 *
 * <p>*"I don't want the farmer farming forever."* Energy already stops a
 * settler eventually, but energy is a <b>survival</b> number — it drains from
 * walking and standing about as fast as from swinging a hoe, it tops back up
 * from a nap by the hearth, and a settler sitting at 40 energy is still
 * perfectly willing to work. It was never the right lever for "how much of a
 * trade does one person actually do in a day," so capping work at low energy
 * never produced that feeling.
 *
 * <h2>The fix: a second number spent by finishing work, not by time</h2>
 *
 * <p>Effort is not drained by the clock and it does not care how long a
 * settler stands around with nothing to do. It is spent in fixed amounts by
 * <b>completed</b> work actions — a crop harvested, a tree felled, a batch
 * baked, a block cut — via {@link #spend}. Hit zero and the trade's own work
 * goal simply refuses to start a new action for the rest of the day
 * ({@link #isSpent}); the settler is not broken, unhappy, or stuck, they are
 * <b>done</b>, and the existing stroll/rest goals pick them up the instant
 * the work goal steps aside in the priority list. Nobody had to write "go be
 * idle now" anywhere for that to happen.
 *
 * <h2>Capacity is earned; the refill is a housing decision</h2>
 *
 * <p>{@link #capacity} runs from {@value #BASE_CAPACITY} for a newcomer up
 * towards the high thirties as STAMINA is trained (see
 * {@link SettlerAttributes}) — the same "earned, not rolled" shape as the
 * rest of the attribute system, so a settlement that has worked a settler
 * hard for weeks gets more out of them, not because the number was tuned up
 * but because the settler genuinely got tougher.
 *
 * <p>The refill is the real economic lever, and it is the settlement's own
 * housing that pulls it: a genuine night in a bed the settler has claimed
 * brings the pool back to full ({@link #refillFull}), but rough rest with no
 * bed only brings it back most of the way ({@link #refillRough}). A village
 * of open-air sleepers is visibly less productive than one with real houses,
 * and the fix is the one thing the game already asks the player to build.
 */
public final class Effort {

    /** Everyone starts the day able to do this much, before STAMINA adds more. */
    public static final int BASE_CAPACITY = 20;
    /** Every this many points of STAMINA buys one more unit of daily capacity. */
    public static final int STAMINA_PER_UNIT = 5;
    /** A genuine night in a claimed bed: the pool comes back whole. */
    public static final float BED_REFILL_FRACTION = 1.0F;
    /** Rough rest with no bed still restores the settler — just not all the way. */
    public static final float ROUGH_REFILL_FRACTION = 0.6F;

    /**
     * -1 means "never touched this life". Both a brand new settler and one
     * loaded from a save with no {@code EffortLeft} key read as a full pool —
     * the NBT contract is deliberately "absent means full", so an untouched
     * settler's save is never bloated with a number that already is the
     * default, and a settler saved before this system existed simply starts
     * the day rested rather than starving on effort it never had a chance to
     * bank.
     */
    private float left = -1.0F;

    private Effort() {
    }

    /** A settler who has not spent anything yet today. */
    public static Effort full() {
        return new Effort();
    }

    /**
     * How much the pool holds today. STAMINA-scaled: {@value #BASE_CAPACITY}
     * for a newcomer, rising towards the high thirties for a settler whose
     * STAMINA has been trained hard — see the class doc for why that is the
     * whole point.
     */
    public int capacity(int staminaAttribute) {
        return BASE_CAPACITY + staminaAttribute / STAMINA_PER_UNIT;
    }

    /** What is left to spend today, clamped to the current capacity so a
     *  capacity that shrinks (it never does, but defensively) cannot leave
     *  a stale "left" above the ceiling. */
    public int left(int staminaAttribute) {
        int cap = capacity(staminaAttribute);
        return left < 0.0F ? cap : Mth.clamp(Math.round(left), 0, cap);
    }

    /** What has already gone today — capacity minus what remains. */
    public int spent(int staminaAttribute) {
        return capacity(staminaAttribute) - left(staminaAttribute);
    }

    /** The one question every work goal must ask before it starts a NEW
     *  action. Never mid-action: a batch already begun always finishes. */
    public boolean isSpent(int staminaAttribute) {
        return left(staminaAttribute) <= 0;
    }

    /**
     * Pays for one completed work action. Never goes negative — a settler
     * with 1 unit left who finishes a 3-unit action still finishes it (the
     * felled tree does not un-fall), they are simply spent immediately after,
     * which is exactly the point: the cost is charged on completion, not
     * reserved in advance.
     */
    public void spend(int units, int staminaAttribute) {
        if (units <= 0) {
            return;
        }
        left = Math.max(0, left(staminaAttribute) - units);
    }

    /** A genuine night in a claimed bed: the pool comes back whole. */
    public void refillFull(int staminaAttribute) {
        left = capacity(staminaAttribute) * BED_REFILL_FRACTION;
    }

    /** Rough rest, no bed under them. Sleep quality is a real economic
     *  input — this is the number that makes it one. */
    public void refillRough(int staminaAttribute) {
        left = capacity(staminaAttribute) * ROUGH_REFILL_FRACTION;
    }

    /** "14/32" — no hidden numbers, job standard point 1. Wired into
     *  {@code /hearthstead why}; see docs/project/PLAN_EFFORT.md §5. */
    public String describe(int staminaAttribute) {
        return left(staminaAttribute) + "/" + capacity(staminaAttribute);
    }

    // --------------------------------------------------------- persistence ---

    /** Writes directly onto the settler's own save tag — one float, one key,
     *  omitted entirely when the pool is still full so an untouched
     *  settler's save carries no extra bytes for a number at its default. */
    public void writeTo(CompoundTag tag) {
        if (left >= 0.0F) {
            tag.putFloat("EffortLeft", left);
        }
    }

    /** Absent {@code EffortLeft} reads as full, for a fresh life and for a
     *  settler saved before this system existed alike. */
    public static Effort readFrom(CompoundTag tag) {
        Effort effort = new Effort();
        if (tag.contains("EffortLeft")) {
            effort.left = tag.getFloat("EffortLeft");
        }
        return effort;
    }
}
