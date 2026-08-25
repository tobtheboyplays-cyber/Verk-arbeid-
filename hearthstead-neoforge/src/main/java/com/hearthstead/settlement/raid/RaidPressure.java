package com.hearthstead.settlement.raid;

import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * How badly the world wants to attack this settlement tonight.
 *
 * <p><b>There is no timer.</b> Both reference mods pace raids off a night
 * counter with a floor, and the cost of that is measured in
 * {@code docs/project/RAID_REFERENCE_RESEARCH.md}: MineColonies ships
 * {@code minimumnumberofnightsbetweenraids=10} against an average of 14, so
 * nine nights in every cycle are <em>provably</em> safe and players learn it
 * within two cycles. TekTopia is worse — its only hostile visitor rolls at
 * {@code villagers/10} and can be switched off entirely by rotating the town
 * hall marker, so danger arrives exactly when it has stopped mattering.
 *
 * <p>The naive reading of "make raids more frequent" is to shorten that
 * timer, and that is a trap: it reproduces MineColonies #4838, where a raid
 * lands every single night until the server restarts. What this model
 * changes instead is that <b>no night is ever provably safe</b>. Every night
 * rolls, on the shape of vanilla's zombie siege — a real chance each night,
 * gated on the place actually being a settlement worth attacking.
 *
 * <p><b>The feedback loop points forward</b> (D-A3-2). Surviving a raid
 * <em>raises</em> pressure: you proved the settlement is worth the trouble
 * and you kept your goods. Losing lowers it slightly — but you paid in
 * settlers, buildings and stores to get that relief, so it can never be a
 * strategy. This is the deliberate inverse of MineColonies, where losing
 * more than 15% of the population both lowers difficulty and buys six extra
 * quiet nights, which is why that system converges on "rare and survivable"
 * no matter how the player plays.
 *
 * <p>Deliberately free of world state: the roll is passed in rather than
 * drawn here, so every rule below is exactly testable.
 */
public final class RaidPressure {

    /** Below this, a place is not worth a raid. Keeps day one peaceful. */
    public static final int MIN_WORTH = 15;
    public static final int MAX_PRESSURE = 100;

    /**
     * Chance of a raid on the calmest possible qualifying settlement, and on
     * the most besieged one. The floor is never zero — that is the whole
     * point — and is close to vanilla's 10% nightly zombie-siege roll.
     */
    public static final double MIN_CHANCE = 0.05;
    public static final double MAX_CHANCE = 0.55;

    /** Pressure gained by repelling a raid, and released by losing one. */
    public static final int REPEL_GAIN = 12;
    public static final int LOSS_RELIEF = 8;

    /** Quiet-night pressure gain is clamped to this band. */
    public static final int MIN_QUIET_GAIN = 1;
    public static final int MAX_QUIET_GAIN = 5;

    /** Stage boundaries, in pressure. Public because the UI names them. */
    public static final int URO_THRESHOLD = 20;
    public static final int VARSEL_THRESHOLD = 45;
    public static final int BELEIRING_THRESHOLD = 75;

    /**
     * How readable the threat is. MineColonies' own wiki concedes that "how
     * quickly they increase in difficulty or what affects their difficulty
     * is not publicly known" — a threat nobody can read produces annoyance,
     * not dread. These names are shown to the player.
     */
    public enum Stage {
        ROLIG, URO, VARSEL, BELEIRING;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private int pressure;
    /**
     * Starts high on purpose: a newly founded settlement has not just
     * survived a raid, so it must not inherit the morning-after grace.
     */
    private int nightsSinceRaid = 99;
    private long lastRolledNight = Long.MIN_VALUE;
    /**
     * The night index a telegraph omen ({@link com.hearthstead.settlement.raid.RaidTelegraph})
     * is scheduled for, or {@code Long.MIN_VALUE} when none is pending. Set
     * 1-2 nights ahead of when it fires, so the scout at the treeline and the
     * bard's unease genuinely give warning rather than firing the same hour
     * a raid lands (DESIGN.md: "telegraphed 1-2 days ahead"). Deliberately
     * NOT tied to whether a raid actually happens that far out -- the roll
     * that decides that stays nightly and independent, so an omen is a sign
     * of danger, never a guarantee either way (D-A3-1: no night is provably
     * safe, and none becomes provably raided either).
     */
    private long forecastNight = Long.MIN_VALUE;
    /** The last night an omen was actually shown, so it fires once per forecast. */
    private long lastTelegraphedNight = Long.MIN_VALUE;

    public int pressure() {
        return pressure;
    }

    public int nightsSinceRaid() {
        return nightsSinceRaid;
    }

    public long lastRolledNight() {
        return lastRolledNight;
    }

    public long forecastNight() {
        return forecastNight;
    }

    public long lastTelegraphedNight() {
        return lastTelegraphedNight;
    }

    /** Called by {@code RaidTelegraph} when it commits to warning about a future night. */
    public void scheduleForecast(long night) {
        forecastNight = night;
    }

    /** Called by {@code RaidTelegraph} once the omen for {@code night} has actually appeared. */
    public void markTelegraphed(long night) {
        lastTelegraphedNight = night;
    }

    public Stage stage() {
        if (pressure >= BELEIRING_THRESHOLD) {
            return Stage.BELEIRING;
        }
        if (pressure >= VARSEL_THRESHOLD) {
            return Stage.VARSEL;
        }
        if (pressure >= URO_THRESHOLD) {
            return Stage.URO;
        }
        return Stage.ROLIG;
    }

    /**
     * What the settlement is worth attacking for: its people and the
     * buildings they have filled. Deliberately NOT a sum of the player's
     * stat sheet the way MineColonies' raid level is — that is what makes
     * its raiders read as a mirror of your own guards rather than as an
     * outside force.
     */
    public static int worthOf(Settlement settlement) {
        int builtCount = 0;
        for (Building b : settlement.buildings) {
            if (b.valid) {
                builtCount++;
            }
        }
        return settlement.population() * 3 + builtCount * 4;
    }

    /** A settlement too small to bother with is genuinely left alone. */
    public static boolean worthRaiding(Settlement settlement) {
        return worthOf(settlement) >= MIN_WORTH;
    }

    /** Tonight's raid chance, before the never-two-nights guarantee. */
    public double chanceTonight() {
        double t = (double) pressure / MAX_PRESSURE;
        return MIN_CHANCE + (MAX_CHANCE - MIN_CHANCE) * Mth.clamp(t, 0.0, 1.0);
    }

    /** A richer settlement draws attention faster while it is left alone. */
    public static int quietGainFor(Settlement settlement) {
        return Mth.clamp(1 + worthOf(settlement) / 20,
            MIN_QUIET_GAIN, MAX_QUIET_GAIN);
    }

    /**
     * The one hard guarantee (D-A3-4): a raid never lands on the night
     * straight after a raid unless the settlement is already under siege.
     * Without it, a nightly roll degenerates into MineColonies #4838 —
     * "once a raid happens it happens again every night, night-after-night".
     */
    public boolean inGracePeriod() {
        return nightsSinceRaid < 1 && stage() != Stage.BELEIRING;
    }

    /**
     * Resolves one night. {@code roll} is a uniform value in [0, 1) drawn by
     * the caller, so this method is fully deterministic and every rule above
     * is directly testable.
     *
     * @return true if a raid should begin tonight
     */
    public boolean rollForNight(Settlement settlement, long night, double roll) {
        if (night <= lastRolledNight) {
            return false; // one roll per night, however often this is called
        }
        lastRolledNight = night;

        if (!worthRaiding(settlement)) {
            // Nothing to come for, so nothing accumulates either. A hamlet
            // does not quietly build up a debt of violence.
            nightsSinceRaid++;
            return false;
        }
        if (inGracePeriod()) {
            nightsSinceRaid++;
            addPressure(quietGainFor(settlement));
            return false;
        }
        if (roll < chanceTonight()) {
            nightsSinceRaid = 0;
            return true;
        }
        nightsSinceRaid++;
        addPressure(quietGainFor(settlement));
        return false;
    }

    /** The settlement held. It is now a more attractive target, not a safer one. */
    public void recordRepelled() {
        addPressure(REPEL_GAIN);
    }

    /** The raiders got what they came for. Some heat comes off — at a price. */
    public void recordLost() {
        addPressure(-LOSS_RELIEF);
    }

    private void addPressure(int delta) {
        pressure = Mth.clamp(pressure + delta, 0, MAX_PRESSURE);
    }

    /**
     * Adopts another instance's state. The settlement holds this field
     * {@code final} so the reference stays stable for anything watching it,
     * so load copies into the existing object rather than replacing it.
     */
    public void copyFrom(RaidPressure other) {
        this.pressure = other.pressure;
        this.nightsSinceRaid = other.nightsSinceRaid;
        this.lastRolledNight = other.lastRolledNight;
        this.forecastNight = other.forecastNight;
        this.lastTelegraphedNight = other.lastTelegraphedNight;
    }

    /** Test and debug seam; never called by the simulation itself. */
    public void setPressureForTesting(int value) {
        pressure = Mth.clamp(value, 0, MAX_PRESSURE);
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Pressure", pressure);
        tag.putInt("NightsSinceRaid", nightsSinceRaid);
        tag.putLong("LastRolledNight", lastRolledNight);
        tag.putLong("ForecastNight", forecastNight);
        tag.putLong("LastTelegraphedNight", lastTelegraphedNight);
        return tag;
    }

    public static RaidPressure readNbt(CompoundTag tag) {
        RaidPressure p = new RaidPressure();
        p.pressure = Mth.clamp(tag.getInt("Pressure"), 0, MAX_PRESSURE);
        p.nightsSinceRaid = tag.getInt("NightsSinceRaid");
        p.lastRolledNight = tag.contains("LastRolledNight")
            ? tag.getLong("LastRolledNight") : Long.MIN_VALUE;
        // Both new keys: absent on an older save, and Long.MIN_VALUE (no
        // omen pending / none yet shown) is exactly the right default.
        p.forecastNight = tag.contains("ForecastNight")
            ? tag.getLong("ForecastNight") : Long.MIN_VALUE;
        p.lastTelegraphedNight = tag.contains("LastTelegraphedNight")
            ? tag.getLong("LastTelegraphedNight") : Long.MIN_VALUE;
        return p;
    }
}
