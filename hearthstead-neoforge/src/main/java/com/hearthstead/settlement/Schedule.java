package com.hearthstead.settlement;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;

/**
 * Where a settler is supposed to be, right now.
 *
 * <p>{@link DayPhase} says <i>when</i>; {@link Employment} says <i>where their
 * work is</i>; this joins them and answers the only question the AI needs:
 * "walk to which block, and what are you doing when you get there?"
 *
 * <h2>Why a village needs this to look alive</h2>
 *
 * <p>Both references get criticised for villages that read as busy rather than
 * alive, and the reason is that everyone acts independently: a farmer tills at
 * three in the morning because nothing told him not to. Life is <b>synchrony</b>
 * — the square fills at the meal and empties at dusk, and you can tell the time
 * by looking out of the window. That needs one clock and one answer to "where
 * should you be", which is this class.
 *
 * <h2>A posting is a default, never a law</h2>
 *
 * <p>An alarm overrides it, and so does a need that has become urgent: an
 * exhausted settler sleeps through the morning shift. That ordering lives in
 * the goal priorities — panic and combat above eating, eating above rest, rest
 * above going to your post — not here. This class only ever answers what the
 * ordinary day asks for.
 *
 * <h2>Guards keep the other half of the clock</h2>
 *
 * <p>A garrison that all sleeps at midnight is a garrison that is not a
 * garrison. Guards are split into a day watch and a night watch by
 * {@link Employment#watchOf}, and the off-watch half sleeps through the
 * <i>afternoon</i> instead. So there is always someone walking, and a raid at
 * 2am meets guards who are already awake rather than a village of sleepers.
 */
public final class Schedule {

    /** How close counts as "at your post". A room, not a block. */
    public static final int AT_POST = 4;

    /**
     * @param where    the block to walk to
     * @param activity what they are doing once there
     * @param reason   why, in one word, for traces and the Tingbok
     */
    public record Posting(BlockPos where, SettlerActivity activity, String reason) {
    }

    /**
     * Whether this settler should be asleep now.
     *
     * <p>For everyone but a night-watch guard this is simply the rest phase.
     * The night watch sleeps through the afternoon so it can stand the dark.
     */
    public static boolean shouldSleep(Settlement settlement, SettlerEntity settler,
                                      DayPhase phase) {
        if (settler.getProfession() == Profession.GUARD
            && Employment.watchOf(settlement, settler) == Employment.Watch.NIGHT) {
            return phase == DayPhase.AFTERNOON_WORK || phase == DayPhase.MEAL;
        }
        return phase.rest();
    }

    /** Whether this settler should be practising their trade now. */
    public static boolean shouldWork(Settlement settlement, SettlerEntity settler,
                                     DayPhase phase) {
        if (!settler.getProfession().employed()) {
            return false;
        }
        if (settler.getProfession() == Profession.GUARD) {
            return onWatch(settlement, settler, phase);
        }
        return phase.work();
    }

    /** Whether a guard is standing their own shift. */
    public static boolean onWatch(Settlement settlement, SettlerEntity settler,
                                  DayPhase phase) {
        return Employment.watchOf(settlement, settler) == Employment.Watch.NIGHT
            ? phase.rest() || phase == DayPhase.EVENING || phase == DayPhase.RISE
            : phase.work() || phase.meal() || phase == DayPhase.EVENING;
    }

    /**
     * The block this settler should be standing at, or null when the ordinary
     * day has nothing to say — during rest (the bed goal owns that) and while
     * a guard is on watch (the patrol owns that).
     */
    @Nullable
    public static Posting postFor(Settlement settlement, SettlerEntity settler,
                                  DayPhase phase) {
        if (shouldSleep(settlement, settler, phase)) {
            return null;
        }
        if (settler.getProfession() == Profession.GUARD
            && onWatch(settlement, settler, phase)) {
            return null;
        }
        if (phase.work()) {
            Building work = Employment.employerOf(settlement, settler.getUUID());
            if (work != null && work.valid) {
                // Only if the work is actually THERE. A farmer's fields and
                // a lumberjack's trees are not, and posting them to the
                // building has them walk back to the shed between stints.
                if (!Employment.worksAtTheBuilding(work.type)) {
                    return null;
                }
                return new Posting(work.anchor, SettlerActivity.TRAVELING, "work");
            }
            // Unemployed in working hours: they gather where the idle gather,
            // in plain sight. A village whose unemployed are visible is a
            // village whose problem the player can see.
            return gathering(settlement, "idle");
        }
        if (phase.meal()) {
            BlockPos hall = firstValid(settlement, BuildingType.DINING_HALL);
            return new Posting(hall != null ? hall : settlement.center,
                SettlerActivity.TRAVELING, "meal");
        }
        if (phase.social()) {
            BlockPos tavern = firstValid(settlement, BuildingType.TAVERN);
            return new Posting(tavern != null ? tavern : settlement.center,
                SettlerActivity.TRAVELING, "evening");
        }
        // RISE: the settlement wakes together and the square fills. It is the
        // cheapest moment of life in the whole day and it costs one branch.
        return gathering(settlement, "rise");
    }

    private static Posting gathering(Settlement settlement, String reason) {
        BlockPos tavern = firstValid(settlement, BuildingType.TAVERN);
        return new Posting(tavern != null ? tavern : settlement.center,
            SettlerActivity.TRAVELING, reason);
    }

    /** The anchor of the first valid building of a type, or null. */
    @Nullable
    public static BlockPos firstValid(Settlement settlement, BuildingType type) {
        for (Building building : settlement.buildings) {
            if (building.valid && building.type == type && building.anchor != null) {
                return building.anchor;
            }
        }
        return null;
    }

    private Schedule() {
    }
}
