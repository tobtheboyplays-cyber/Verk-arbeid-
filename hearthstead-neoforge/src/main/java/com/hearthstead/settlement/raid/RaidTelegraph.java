package com.hearthstead.settlement.raid;

import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/**
 * The dread before the raid: a scout at the treeline, and the bard's unease.
 *
 * <p>DESIGN.md calls for raids "escalating, telegraphed 1-2 days ahead
 * (scouts, bard's unease)". The naive way to build that is to spoil the
 * night's own dice roll a day early, and that is exactly the trap D-A3-1
 * exists to avoid: the moment a telegraph is a reliable predictor of the
 * actual roll, the nights it does NOT fire become provably safe, which is
 * the MineColonies failure this whole slice is built against.
 *
 * <p>So a telegraph here is a separate, independent commitment: whenever a
 * settlement is tense enough ({@link RaidPressure.Stage} above ROLIG), it
 * may schedule an omen 1-2 nights out ({@link #rollForecast}). When that
 * night's dusk arrives, the omen fires -- a lone scout appears near the
 * settlement and a line goes out to every nearby player. Whether an actual
 * raid also lands that night, the night before, or three nights later is
 * still decided entirely by {@link RaidPressure#rollForNight}, unaffected
 * by any of this. The omen is honest about the settlement's danger; it is
 * not a spoiler for the schedule.
 *
 * <p>The scout itself is a real, killable {@link RaiderEntity} (flagged via
 * {@link RaiderEntity#markScout}), not a ghost or a particle effect -- it
 * can be seen, tracked and fought, it simply never starts that fight itself
 * (see {@link com.hearthstead.entity.ai.RaiderScoutGoal}).
 */
public final class RaidTelegraph {

    /**
     * Time of day the omen fires, strictly before {@link RaidDirector#ROLL_AT_DAYTIME}
     * so the warning always lands before the night's own roll on the same
     * calendar day, and well before dark so it reads as dusk rather than an
     * ambush.
     */
    public static final int DUSK_TIME = 12000;

    /**
     * How far PAST the settlement's own edge the scout stands -- relative to
     * {@link Settlement#radius}, unlike a raiding band's fixed approach
     * distance ({@link RaidDirector#SPAWN_MIN_DISTANCE}-
     * {@link RaidDirector#SPAWN_MAX_DISTANCE}), so "the treeline" stays true
     * to its name for a small young settlement and a sprawling old one alike
     * rather than landing inside a large settlement's own radius.
     */
    public static final int SCOUT_MARGIN_MIN = 4;
    public static final int SCOUT_MARGIN_MAX = 14;

    private RaidTelegraph() {
    }

    /** How readable the dread is: rarer at Uro, closer to certain under Beleiring. */
    private static double scheduleChanceFor(RaidPressure.Stage stage) {
        return switch (stage) {
            case ROLIG -> 0.0;
            case URO -> 0.22;
            case VARSEL -> 0.4;
            case BELEIRING -> 0.65;
        };
    }

    /**
     * How much warning the omen gives. Shorter at the top of the curve on
     * purpose -- Beleiring is a designed crescendo (D-A3-4 lets consecutive
     * nights through at that stage), and a long lead time on every single
     * one of those nights would read as noise rather than mounting dread.
     */
    private static int forecastDelayFor(RaidPressure.Stage stage) {
        return switch (stage) {
            case ROLIG -> 0; // unreachable: scheduleChanceFor(ROLIG) is 0
            case URO, VARSEL -> 2;
            case BELEIRING -> 1;
        };
    }

    /**
     * Decides whether tonight commits to an omen 1-2 nights out. Takes its
     * roll as a parameter, exactly like {@link RaidPressure#rollForNight},
     * so this is exactly testable and never itself draws randomness.
     *
     * @return true if a forecast was (newly) scheduled
     */
    public static boolean rollForecast(Settlement settlement, long night, double roll) {
        RaidPressure pressure = settlement.raidPressure;
        RaidPressure.Stage stage = pressure.stage();
        if (stage == RaidPressure.Stage.ROLIG) {
            return false; // an omen is a sign of real pressure, not ambient noise
        }
        if (pressure.forecastNight() >= night) {
            return false; // one pending (or just-shown) omen at a time
        }
        if (roll >= scheduleChanceFor(stage)) {
            return false;
        }
        pressure.scheduleForecast(night + forecastDelayFor(stage));
        return true;
    }

    /** Pure arithmetic, mirroring {@link RaidDirector#isRollTime} for the same reason:
     * a GameTest that flips the shared level's day time stalls every settler
     * in every other test running beside it in the same batch, so this is
     * tested directly rather than by setting the world clock. */
    public static boolean isDuskOrLater(long dayTime) {
        return dayTime % RaidDirector.DAY_LENGTH >= DUSK_TIME;
    }

    /**
     * Called from {@link RaidDirector#tick}. Cheap and idempotent: at most
     * one omen per scheduled night, and the whole check is a handful of
     * field comparisons -- no scanning, matching every other budgeted tick
     * in this slice.
     */
    public static void tick(ServerLevel level, Settlement settlement) {
        if (!RaidPressure.worthRaiding(settlement)) {
            return; // nothing here is worth an omen either
        }
        RaidPressure pressure = settlement.raidPressure;
        long dayTime = level.getDayTime();
        long night = RaidDirector.nightOf(dayTime);
        if (pressure.forecastNight() != night
            || pressure.lastTelegraphedNight() >= night
            || !isDuskOrLater(dayTime)) {
            return;
        }
        // Best-effort: bad terrain on every bearing simply means no scout
        // appears tonight, but the line below still goes out. The text is
        // the reliable half of the omen; the entity is the flavour.
        spawnScout(level, settlement);
        RaidBroadcast.send(level, settlement,
            Component.translatable("hearthstead.message.raid_omen", settlement.name));
        pressure.markTelegraphed(night);
        SettlementSavedData.get(level).setDirty();
    }

    /**
     * Brings one scout into the world near the settlement. Public so it is
     * directly testable without driving the world's day time (see
     * {@link #isDuskOrLater}) -- the same reason {@link RaidDirector#spawnBand}
     * is public.
     */
    public static RaiderEntity spawnScout(ServerLevel level, Settlement settlement) {
        RandomSource random = level.getRandom();
        float bearing = random.nextFloat() * 360.0F - 180.0F;
        int distance = settlement.radius + SCOUT_MARGIN_MIN
            + random.nextInt(Math.max(1, SCOUT_MARGIN_MAX - SCOUT_MARGIN_MIN + 1));
        BlockPos ground = RaidDirector.standableNear(level,
            RaidDirector.formUpAt(settlement.center, bearing, distance));
        if (ground == null) {
            // A lone scout does not sweep for footing the way a raid's
            // captain does (RaidDirector#footingFor) -- it is flavour, not a
            // raid the schedule depends on. One direct attempt, then the
            // settlement's own ground, then simply no scout tonight.
            ground = RaidDirector.standableNear(level, settlement.center);
            if (ground == null) {
                return null;
            }
        }
        RaiderEntity scout = ModEntities.RAIDER.get().create(level);
        if (scout == null) {
            return null;
        }
        scout.moveTo(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5,
            bearing + 180.0F, 0.0F);
        scout.markScout(settlement.id);
        level.addFreshEntity(scout);
        return scout;
    }
}
