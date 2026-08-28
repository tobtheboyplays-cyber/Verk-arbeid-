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

    /**
     * Fractional remainder banked between {@link #spendResearched} calls, in
     * TENTHS of a unit — never persisted, and never touched by anything
     * else. See {@link #spendResearched}'s own doc for why it exists and why
     * it is safe to lose on reload, the same call {@code RepairWorkGoal}
     * makes for its own {@code SCAR_MENDS} tally.
     */
    private int carryTenths = 0;

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

    /**
     * Pays for one completed action a settlement's own research has made
     * cheaper — see {@code docs/project/BALANCE_AUDIT.md} finding 2's
     * follow-up. {@code baseUnits} is the trade's ordinary flat cost (2 for
     * every crafting batch); {@code multiplier} is the same completed
     * project's {@code ResearchProject.bonus()} that already shaves ticks
     * off the recipe (0.85 for a 15% cut) — ONE number now buys both the
     * felt "this looks quicker" (the tick side, unchanged, still real) and
     * the actually-binding "I get more done today" (this side, new).
     *
     * <h2>Why an accumulator, not a rounded spend</h2>
     *
     * <p>2 × 0.85 = 1.7, and {@link #spend} only ever takes a whole int —
     * rounding 1.7 to 2 every single time would spend the discount into
     * nothing, and rounding it to 1 every time would overpay the discount
     * (a 50% cut, not 15%). Neither is what "15% cheaper" is supposed to
     * mean, and a coin flip between the two is exactly the kind of
     * non-determinism this project has spent a night hunting out of the
     * suite. So the shortfall is banked instead: {@code baseUnits × 10 ×
     * multiplier} tenths are added to {@link #carryTenths} on every call,
     * whatever whole units that carry now holds are spent immediately, and
     * the remainder (always 0–9) waits for next time. Nothing is ever
     * dropped and nothing is ever invented — the running total spent after
     * N calls is always exactly {@code floor(N × baseUnits × 10 ×
     * multiplier / 10)}, a pure function of N, {@code baseUnits} and
     * {@code multiplier} with no clock, no RNG and no settler-specific
     * state anywhere in it. The SAME settlement doing the SAME work with
     * the SAME research always spends the SAME long-run total, batch for
     * batch — {@code RepairWorkGoal#shouldMendFree} is this exact idea
     * (a discount too fine-grained for a flat int, made deterministic with
     * a plain running counter) applied to "one action in N is free" instead
     * of "every action is a little cheaper"; this is the finer-grained
     * shape that fits a *per-batch* discount rather than an occasional
     * free one.
     *
     * <p>{@code multiplier == 1.0F} (no research, or a project that does
     * not touch this key) reproduces {@link #spend}'s old behaviour bit for
     * bit: {@code baseUnits × 10} is always exactly divisible by 10, so the
     * remainder is always 0 and the whole spend lands on the very call that
     * earned it — an unresearched trade is charged on the identical tick it
     * always was, not "eventually, once the accumulator catches up".
     */
    public void spendResearched(int baseUnits, float multiplier, int staminaAttribute) {
        if (baseUnits <= 0) {
            return;
        }
        carryTenths += Math.round(baseUnits * 10 * multiplier);
        int whole = carryTenths / 10;
        carryTenths -= whole * 10;
        spend(whole, staminaAttribute);
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
