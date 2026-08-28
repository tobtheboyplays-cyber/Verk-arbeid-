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
    /**
     * One named settler, seized and carried off.
     *
     * <p><b>Disarmed 2026-08-26 (raid-night audit).</b> The enum value, its
     * saga epithets ({@link com.hearthstead.saga.Captain#epithetsFor}) and
     * its lang strings are kept — see {@link #isAvailableAt} for why it is
     * never actually offered.
     */
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
            // DISARMED 2026-08-26 (raid-night audit): never offered. A
            // LOSEPENGER raid today would be a lie the game tells the
            // player -- the objective is selectable, the morning report
            // would name it "Ransom", and a successful captain would earn
            // "the Ransomer"/"Chain-Bringer" (Captain#epithetsFor), but no
            // code anywhere seizes a settler, carries one off, or holds one
            // anywhere: every raider's destination is the settlement centre
            // regardless of objective (RaidDirector#spawnBand), and there is
            // no captured-settler state, no camp, no cage, no rescue path.
            // A LOSEPENGER raid would therefore play out pixel-for-pixel
            // identically to a BLOD raid while claiming to be something
            // else entirely -- the "game reports something that did not
            // happen" defect class this project treats as unforgivable.
            // Bring it back only alongside the real thing: a raider goal
            // that seizes a live settler target instead of just fighting
            // it, a way to mark that settler captured (not dead) and carry
            // them off screen, and a report/epithet that only fire once a
            // seizure actually happened this raid -- see the raid-night
            // audit report (2026-08-26) for the fuller sketch. Until then
            // this arm stays a hard `false`, never a population check.
            case LOSEPENGER -> false;
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
