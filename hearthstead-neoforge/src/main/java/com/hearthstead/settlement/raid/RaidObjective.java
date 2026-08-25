package com.hearthstead.settlement.raid;

import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a raid has come <em>for</em>.
 *
 * <p>This is the answer to the loudest complaint the research turned up
 * about MineColonies' raiders: a dev reply states the design intent is for
 * them to be "similar to guards", and players report the result as
 * undifferentiated HP sponges — <em>"chief raiders don't even stand out
 * from regular raiders"</em> (#11655). That happens because their raid
 * strength is computed from the player's own citizen, building and research
 * totals, so the enemy is literally a mirror of your stat sheet rather than
 * an outside force with its own intent.
 *
 * <p>An objective fixes that cheaply: it decides what the raiders path
 * toward, what counts as their win, and what the player must defend.
 * Defending becomes a decision instead of a brawl.
 *
 * <p>Objectives are chosen from what the settlement <em>actually has</em>,
 * not from a bare die roll — nobody comes to steal grain from a settlement
 * with no stores.
 */
public enum RaidObjective {
    /** Stores. Straight for the warehouse, take what can be carried, leave. */
    KORN,
    /** People. Hunt settlers wherever they are. */
    BLOD,
    /** Buildings. Arson, and let it spread. */
    BRANN,
    /** One named settler, seized and carried off. */
    LOSEPENGER;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "hearthstead.raid.objective." + id();
    }

    /** Whether this settlement has anything that would draw this objective. */
    public boolean isAvailableAt(Settlement settlement) {
        return switch (this) {
            // Something worth carrying off has to exist first.
            case KORN -> hasStorage(settlement);
            // People are the one thing every settlement has.
            case BLOD -> settlement.population() > 0;
            // Arson needs something built to burn.
            case BRANN -> builtCount(settlement) > 0;
            // Taking a hostage needs a settlement big enough to miss one.
            case LOSEPENGER -> settlement.population() >= 4;
        };
    }

    private static boolean hasStorage(Settlement settlement) {
        for (Building b : settlement.buildings) {
            if (b.valid && b.type == BuildingType.WAREHOUSE) {
                return true;
            }
        }
        return false;
    }

    private static int builtCount(Settlement settlement) {
        int n = 0;
        for (Building b : settlement.buildings) {
            if (b.valid) {
                n++;
            }
        }
        return n;
    }

    /** Every objective this settlement could plausibly attract tonight. */
    public static List<RaidObjective> availableAt(Settlement settlement) {
        List<RaidObjective> out = new ArrayList<>();
        for (RaidObjective o : values()) {
            if (o.isAvailableAt(settlement)) {
                out.add(o);
            }
        }
        return out;
    }

    /**
     * Picks tonight's objective. {@code BLOD} is the fallback because a
     * settlement with people always has something to lose — so a raid can
     * never be scheduled and then quietly cancelled for want of a target,
     * which is the shape of MineColonies' "deliveries that silently never
     * happen" class of bug applied to raids.
     */
    public static RaidObjective pick(Settlement settlement, RandomSource random) {
        List<RaidObjective> options = availableAt(settlement);
        if (options.isEmpty()) {
            return BLOD;
        }
        return options.get(random.nextInt(options.size()));
    }
}
