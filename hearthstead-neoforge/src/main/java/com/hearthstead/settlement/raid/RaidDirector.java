package com.hearthstead.settlement.raid;

import com.hearthstead.Hearthstead;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.server.level.ServerLevel;

/**
 * Runs the nightly roll for one settlement.
 *
 * <p>Kept deliberately thin: every rule lives in {@link RaidPressure},
 * which takes its roll as a parameter and is therefore exactly testable.
 * This class only decides <em>when</em> to ask.
 *
 * <p><b>Step 1 scope, stated plainly.</b> The schedule is live — pressure
 * really does evolve night by night in a running world, and the stage is
 * readable through {@code /hearthstead info}. Nothing is spawned yet: when
 * the roll says "tonight", that is logged and the pressure state records
 * it, and the faction, captain and objective land in step 2. This class
 * exists now rather than later so the schedule is not dead code sitting
 * beside a model nothing calls.
 */
public final class RaidDirector {

    /**
     * Time of day at which the night's roll is taken. Sits inside the REST
     * phase (which begins at 12700) rather than at its edge, so a settlement
     * whose chunks load a moment late still gets its roll.
     */
    public static final int ROLL_AT_DAYTIME = 13000;

    /** Length of a Minecraft day, and therefore the night index's divisor. */
    public static final long DAY_LENGTH = 24000L;

    private RaidDirector() {
    }

    /** The night index a given world time belongs to. */
    public static long nightOf(long dayTime) {
        return dayTime / DAY_LENGTH;
    }

    /**
     * Whether the night's roll is due at this world time.
     *
     * <p>Pure on purpose. The obvious way to test the time gate is to set
     * the level's time in a GameTest, but {@code setDayTime} is level-wide
     * and every test in a batch shares that level — a test that flipped the
     * world to night would stall every concurrently running settler whose
     * work goal checks {@code dayPhase()}. Testing the arithmetic directly
     * costs nothing and cannot poison its neighbours.
     */
    public static boolean isRollTime(long dayTime) {
        return dayTime % DAY_LENGTH >= ROLL_AT_DAYTIME;
    }

    /**
     * Called from the hearth's once-a-second settlement tick. Safe to call
     * as often as you like: {@link RaidPressure#rollForNight} is idempotent
     * per night, so a re-entrant or duplicated tick cannot double-roll.
     */
    public static void tick(ServerLevel level, Settlement settlement) {
        long dayTime = level.getDayTime();
        if (!isRollTime(dayTime)) {
            return; // not yet tonight
        }
        long night = nightOf(dayTime);
        RaidPressure pressure = settlement.raidPressure;
        if (night <= pressure.lastRolledNight()) {
            return; // already asked tonight; skip the random draw entirely
        }
        boolean raid = pressure.rollForNight(settlement, night,
            level.getRandom().nextDouble());
        SettlementSavedData.get(level).setDirty();
        if (raid) {
            // Step 2 consumes this. Logged rather than silently dropped so
            // that "the schedule fired but nothing happened" is visible in
            // evidence instead of looking like the roll never ran.
            Hearthstead.LOGGER.info(
                "Raid scheduled for {} on night {} (pressure {}, stage {}) "
                    + "-- no raiders exist yet (A3 step 1)",
                settlement.name, night, pressure.pressure(),
                pressure.stage().id());
        }
    }
}
