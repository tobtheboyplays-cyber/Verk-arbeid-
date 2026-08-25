package com.hearthstead.settlement.raid;

import com.hearthstead.Hearthstead;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.util.RandomSource;

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

    /**
     * Chance that a raid is led by a captain the settlement has met before,
     * when there is one to reuse. High on purpose: an enemy you recognise is
     * the whole point, and a fresh nobody every time is what makes both
     * references' raids feel like weather rather than like people.
     */
    public static final float RETURNING_CAPTAIN_CHANCE = 0.7F;

    /** Captains one settlement will remember at once. */
    public static final int MAX_REMEMBERED_CAPTAINS = 5;

    private RaidDirector() {
    }

    /**
     * Whether raids are possible at this difficulty at all.
     *
     * <p>On Peaceful, {@code Monster.shouldDespawnInPeaceful()} is true and
     * vanilla discards every hostile on the next tick — so a raider would
     * spawn and vanish before it took a step. Found by looking at the game:
     * the QA world runs Peaceful, raiders were reported "Summoned" and were
     * gone a tick later.
     *
     * <p>Without this gate the schedule would keep accumulating pressure and
     * announcing BELEIRING in the Tingbok while nothing could ever arrive —
     * a threat display that is quietly lying to the player, which is worse
     * than no threat at all.
     */
    public static boolean raidsPossibleAt(Difficulty difficulty) {
        return difficulty != Difficulty.PEACEFUL;
    }

    /**
     * Builds tonight's raid: who leads it, what they want, and the road they
     * take. Public so it is directly testable with a seeded random.
     */
    public static RaidPlan planRaid(ServerLevel level, Settlement settlement,
                                    long night) {
        RandomSource random = level.getRandom();
        RaidCaptain captain = pickCaptain(settlement, random);
        RaidObjective objective = RaidObjective.pick(settlement, random);
        float approach = captain.nextApproachDegrees(random);
        captain.recordApproach(approach, objective);
        return new RaidPlan(captain.id(), objective, approach, night);
    }

    /**
     * A returning enemy where possible. New captains are only generated
     * when there is nobody to send or the roll calls for reinforcements, and
     * the gallery is capped so a long-lived settlement remembers a cast
     * rather than a crowd.
     */
    public static RaidCaptain pickCaptain(Settlement settlement, RandomSource random) {
        if (!settlement.raidCaptains.isEmpty()
            && random.nextFloat() < RETURNING_CAPTAIN_CHANCE) {
            return settlement.raidCaptains.get(
                random.nextInt(settlement.raidCaptains.size()));
        }
        RaidCaptain fresh = RaidCaptain.generate(random);
        settlement.raidCaptains.add(fresh);
        while (settlement.raidCaptains.size() > MAX_REMEMBERED_CAPTAINS) {
            settlement.raidCaptains.remove(0); // the oldest grudge fades first
        }
        return fresh;
    }

    /** The remembered captain with this id, or null if they are forgotten. */
    public static RaidCaptain captainOf(Settlement settlement, java.util.UUID id) {
        for (RaidCaptain c : settlement.raidCaptains) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
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
        if (!raidsPossibleAt(level.getDifficulty())) {
            return; // peaceful: no raiders can exist, so no pressure either
        }
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
            RaidPlan plan = planRaid(level, settlement, night);
            settlement.pendingRaid = plan;
            RaidCaptain captain = captainOf(settlement, plan.captainId());
            // Logged rather than silently dropped, so "the schedule fired
            // and nothing happened" is visible in evidence instead of
            // looking like the roll never ran.
            Hearthstead.LOGGER.info(
                "Raid scheduled for {} on night {}: {} comes for {} from {}"
                    + " degrees (pressure {}, stage {}, menace {})"
                    + " -- no raiders exist yet (A3 step 2)",
                settlement.name, night,
                captain == null ? "?" : captain.name(),
                plan.objective().id(), Math.round(plan.approachDegrees()),
                pressure.pressure(), pressure.stage().id(),
                captain == null ? "?" : captain.menace());
        }
    }
}
