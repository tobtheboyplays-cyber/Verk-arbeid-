package com.hearthstead.settlement;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who works where. MineColonies' hire/fire, with the six things it gets wrong
 * fixed — see {@code docs/project/PLAN_EMPLOYMENT.md}.
 *
 * <h2>D-011: employment is a relationship to a BUILDING</h2>
 *
 * <p>The old shape was TekTopia's: a job was an item you used on a person, and
 * the settler carried a {@link Profession} that nothing connected to a room.
 * The new shape is the opposite and it is the better one — <b>you hire a person
 * into a building, and the building decides the trade.</b>
 *
 * <p>So {@link Building#workers} is the <b>only</b> record of employment, and a
 * settler's profession is <b>derived</b> from whichever building lists them.
 * The settler's synced profession is a projection kept for the client (outfit,
 * tool in hand, animation set), the way the plaque's occupancy is: recomputed
 * on the server, never a second source of truth. Two places to write one fact
 * is what the plaque invariant exists to forbid.
 *
 * <p>The other consequences fall out of that: twenty-eight buildings need no
 * writ items or sprites, hiring is commanded at the plaque like everything
 * else, and the player's flow stops dead-ending the moment a building
 * registers.
 */
public final class Employment {

    /** Which shift a guard stands. Civilians are always {@link Watch#DAY}. */
    public enum Watch {
        DAY, NIGHT
    }

    /**
     * What hiring this settler would cost the settlement, in words.
     *
     * <p>MineColonies' worst habit is taking a worker out of another building
     * silently: you find out the farm has no farmer when the bread stops. So
     * the cost is computed <b>before</b> the press and shown in the sentence
     * that offers it.
     *
     * @param loses      the building that would lose them, or null
     * @param leavesEmpty whether that building would be left with no worker
     */
    public record Cost(@Nullable Building loses, boolean leavesEmpty) {
        public static final Cost FREE = new Cost(null, false);

        public Component sentence() {
            if (loses == null) {
                return Component.translatable("hearthstead.employ.cost.none");
            }
            return Component.translatable(leavesEmpty
                    ? "hearthstead.employ.cost.leaves_empty"
                    : "hearthstead.employ.cost.moves",
                loses.type.displayName());
        }
    }

    /** One row of the hire list. */
    public record Candidate(SettlerEntity settler, @Nullable Building current,
                            int fitness, Cost cost, boolean worksHere) {
    }

    /** What a hire did, so the caller can report it truthfully. */
    public record Hired(boolean ok, Cost cost, @Nullable Component refusal) {
        public static Hired refused(String key) {
            return new Hired(false, Cost.FREE, Component.translatable(key));
        }
    }

    // --------------------------------------------------------- the trade ---

    private static final Map<BuildingType, Profession> TRADES =
        new EnumMap<>(BuildingType.class);

    static {
        // Only the trades that are actually implemented. A building whose
        // trade does not exist yet must NOT be hireable: a worker standing in
        // a bakery doing nothing is a worse answer than an honest refusal,
        // and D-014 says a control that cannot act is disabled with a reason
        // rather than quietly doing nothing.
        TRADES.put(BuildingType.FARMHOUSE, Profession.FARMER);
        TRADES.put(BuildingType.LUMBER_CAMP, Profession.LUMBERER);
        TRADES.put(BuildingType.WAREHOUSE, Profession.COURIER);
        TRADES.put(BuildingType.BARRACKS, Profession.GUARD);
        TRADES.put(BuildingType.WATCHTOWER, Profession.GUARD);

        // CHAINS-1: every building whose work exists in Production.
        TRADES.put(BuildingType.BAKERY, Profession.BAKER);
        TRADES.put(BuildingType.KITCHEN, Profession.COOK);
        TRADES.put(BuildingType.BUTCHER, Profession.BUTCHER);
        TRADES.put(BuildingType.SMELTER, Profession.SMELTER);
        TRADES.put(BuildingType.SMITHY, Profession.SMITH);
        TRADES.put(BuildingType.SAWMILL, Profession.SAWYER);
        TRADES.put(BuildingType.CARPENTER, Profession.CARPENTER);
        TRADES.put(BuildingType.MASON, Profession.MASON);
        TRADES.put(BuildingType.FLETCHER, Profession.FLETCHER);
        TRADES.put(BuildingType.WEAVER, Profession.WEAVER);
        TRADES.put(BuildingType.TANNERY, Profession.TANNER);
    }

    /**
     * The motion a trade actually performs, which is what gets animated.
     *
     * <p>D-015: clips are keyed to the action, not the job title. A butcher and
     * a tanner both cleave at a bench; a smith and a mason both swing a hammer
     * at a hard surface. Eleven trades, six real actions — and none of them is
     * a generic work loop, which is what the invariant is actually protecting.
     */
    public static SettlerActivity motionOf(BuildingType type) {
        return switch (tradeOf(type)) {
            case BAKER -> SettlerActivity.WORK_OVEN;
            case COOK -> SettlerActivity.WORK_KNEAD;
            case BUTCHER, TANNER -> SettlerActivity.WORK_CLEAVE;
            case SMELTER -> SettlerActivity.WORK_STOKE;
            case SMITH, MASON -> SettlerActivity.WORK_HAMMER;
            case SAWYER, CARPENTER -> SettlerActivity.WORK_SAW;
            case WEAVER, FLETCHER -> SettlerActivity.WORK_WEAVE;
            default -> SettlerActivity.IDLE;
        };
    }

    /** The attribute a trade's work trains, so doing the job makes you better at it. */
    public static Attribute trainedBy(BuildingType type) {
        return switch (tradeOf(type)) {
            case SMITH, MASON, SMELTER, LUMBERER -> Attribute.STRENGTH;
            case COURIER, GUARD -> Attribute.STAMINA;
            case BAKER, COOK, BUTCHER, TANNER, SAWYER, CARPENTER,
                 FLETCHER, WEAVER, FARMER -> Attribute.DEXTERITY;
            default -> Attribute.WITS;
        };
    }

    /** The trade practised in this kind of building, or NONE if none yet is. */
    public static Profession tradeOf(BuildingType type) {
        return TRADES.getOrDefault(type, Profession.NONE);
    }

    public static boolean teaches(BuildingType type) {
        return tradeOf(type) != Profession.NONE;
    }

    // ------------------------------------------------------- the relation ---

    /** The building that employs this settler, or null. The one lookup. */
    @Nullable
    public static Building employerOf(Settlement settlement, UUID settler) {
        for (Building building : settlement.buildings) {
            if (building.workers.contains(settler)) {
                return building;
            }
        }
        return null;
    }

    /** The profession this settler should have, derived from their employer. */
    public static Profession professionOf(Settlement settlement, UUID settler) {
        Building employer = employerOf(settlement, settler);
        return employer == null ? Profession.NONE : tradeOf(employer.type);
    }

    /**
     * Puts the derived profession back onto the settler's synced projection.
     *
     * <p>Call after anything that could change employment — hiring, dismissal,
     * a building dissolving, a settler loading back in. Doing nothing when it
     * already agrees keeps this cheap enough to call freely.
     */
    public static void refresh(Settlement settlement, SettlerEntity settler) {
        Profession should = professionOf(settlement, settler.getUUID());
        if (settler.getProfession() != should) {
            settler.setProfessionProjection(should);
        }
    }

    // ------------------------------------------------------------ hiring ---

    /**
     * What taking this settler would cost. Pure — call it to draw a button.
     */
    public static Cost costOfHiring(Settlement settlement, SettlerEntity settler) {
        Building current = employerOf(settlement, settler.getUUID());
        if (current == null) {
            return Cost.FREE;
        }
        return new Cost(current, current.workers.size() <= 1);
    }

    /**
     * Hires a settler into a building.
     *
     * <p>Atomic in the way that matters: they leave their old post in the same
     * operation that gives them the new one, so there is no instant in which a
     * settler holds two jobs or none.
     */
    public static Hired hire(ServerLevel level, Settlement settlement,
                             Building building, SettlerEntity settler) {
        if (!building.valid) {
            return Hired.refused("hearthstead.employ.refused.invalid");
        }
        if (!teaches(building.type)) {
            return Hired.refused("hearthstead.employ.refused.no_trade");
        }
        if (building.workers.contains(settler.getUUID())) {
            return Hired.refused("hearthstead.employ.refused.already");
        }
        if (building.workers.size() >= building.type.workerCapacity()) {
            return Hired.refused("hearthstead.employ.refused.full");
        }
        Cost cost = costOfHiring(settlement, settler);
        if (cost.loses() != null) {
            cost.loses().workers.remove(settler.getUUID());
        }
        building.workers.add(settler.getUUID());
        settler.setProfessionProjection(tradeOf(building.type));
        settler.onHired(level, building);
        SettlementManager.data(level).setDirty();
        return new Hired(true, cost, null);
    }

    /**
     * Dismisses a settler from whatever employs them.
     *
     * <p>Dismissal has weight (PLAN_EMPLOYMENT 3.5): they take a morale hit and
     * they walk out. They are not deleted and they are not hidden — an
     * unemployed settler is visibly in the village, which is the point.
     *
     * @return the building they left, or null if they had no job
     */
    @Nullable
    public static Building dismiss(ServerLevel level, Settlement settlement,
                                   SettlerEntity settler) {
        Building employer = employerOf(settlement, settler.getUUID());
        if (employer == null) {
            return null;
        }
        employer.workers.remove(settler.getUUID());
        settler.setProfessionProjection(Profession.NONE);
        settler.onDismissed(level, employer);
        SettlementManager.data(level).setDirty();
        return employer;
    }

    /**
     * Frees everyone a building employed, because the building is gone.
     *
     * <p>A settler pointing at a building that no longer exists is the exact
     * class of bug KF-013 and KF-014 both were. It is cheaper to make it
     * impossible than to find it twice.
     */
    public static void freeWorkers(ServerLevel level, Settlement settlement,
                                   Building building) {
        if (building.workers.isEmpty()) {
            return;
        }
        List<UUID> leaving = List.copyOf(building.workers);
        building.workers.clear();
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            if (leaving.contains(settler.getUUID())) {
                settler.setProfessionProjection(Profession.NONE);
            }
        }
    }

    // -------------------------------------------------------- the roster ---

    /**
     * Everyone who could take this post, best first.
     *
     * <p>Sorted so the answer is obvious without reading: people already doing
     * this trade, then the unemployed, then everyone else — and within that, by
     * how little taking them costs. The list is people, not a column of digits
     * (PLAN_EMPLOYMENT 3.1); {@link Candidate#fitness} is drawn as pips.
     */
    public static List<Candidate> candidatesFor(ServerLevel level,
                                                Settlement settlement,
                                                Building building) {
        List<Candidate> out = new ArrayList<>();
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            if (settler.isTraveler()) {
                continue;
            }
            Building current = employerOf(settlement, settler.getUUID());
            boolean here = current == building;
            out.add(new Candidate(settler, current,
                fitness(settlement, settler, building),
                here ? Cost.FREE : costOfHiring(settlement, settler), here));
        }
        out.sort((a, b) -> {
            if (a.worksHere() != b.worksHere()) {
                return a.worksHere() ? -1 : 1;
            }
            if (a.fitness() != b.fitness()) {
                return b.fitness() - a.fitness();
            }
            int costA = a.cost().leavesEmpty() ? 2 : a.cost().loses() != null ? 1 : 0;
            int costB = b.cost().leavesEmpty() ? 2 : b.cost().loses() != null ? 1 : 0;
            return costA - costB;
        });
        return out;
    }

    /**
     * Which of the five numbers this trade actually leans on.
     *
     * <p>Naming it per trade is what makes the hire screen a decision: the
     * strongest settler is the obvious lumberer and the wrong courier, and you
     * can see that without being told.
     */
    public static Attribute keyAttributeOf(BuildingType type) {
        return switch (tradeOf(type)) {
            case LUMBERER, GUARD -> Attribute.STRENGTH;
            case COURIER -> Attribute.STAMINA;
            case FARMER -> Attribute.DEXTERITY;
            default -> Attribute.WITS;
        };
    }

    /**
     * How well suited a settler is, 0..5, drawn as pips.
     *
     * <p>Pips rather than the raw number, because you read "four of five" at a
     * glance and never read "62" at a glance — which is the concrete fix for
     * the wall-of-digits complaint about MineColonies' hire tab.
     *
     * <p>This was a placeholder until attributes existed; it now reads the real
     * thing, and nothing above it changed. That is what the seam was for.
     */
    public static int fitness(Settlement settlement, SettlerEntity settler,
                              Building building) {
        Attribute key = keyAttributeOf(building.type);
        int score = settler.attributes().pips(key);
        if (settler.getProfession() == tradeOf(building.type)
            && tradeOf(building.type) != Profession.NONE) {
            score += 1;
        }
        if (settler.getEnergy() < 25.0F || settler.getMorale() < 25.0F) {
            score -= 1;
        }
        return Math.max(0, Math.min(5, score));
    }

    /**
     * Why this candidate is the suggested one, in one sentence.
     *
     * <p>MineColonies sorts, and a sort order tells you <i>that</i> someone is
     * on top, never <i>why</i>. An explanation is a decision; a sort order is a
     * shrug. D-013: this is a suggestion the player accepts, never something
     * the settlement does on its own.
     */
    public static Component reasonFor(Settlement settlement, Candidate candidate,
                                      Building building) {
        Attribute key = keyAttributeOf(building.type);
        if (candidate.worksHere()) {
            return Component.translatable("hearthstead.employ.reason.already_here",
                candidate.settler().getSettlerName());
        }
        if (candidate.settler().attributes().knack() == key) {
            return Component.translatable("hearthstead.employ.reason.knack",
                candidate.settler().getSettlerName(), key.displayName());
        }
        if (candidate.current() == null) {
            return Component.translatable("hearthstead.employ.reason.free",
                candidate.settler().getSettlerName());
        }
        return Component.translatable("hearthstead.employ.reason.best",
            candidate.settler().getSettlerName(), key.displayName());
    }

    /**
     * The shift a guard stands, so a garrison is not all asleep at midnight.
     *
     * <p>Derived, never stored: a guard's index in their own barracks' worker
     * list decides it, which splits any garrison exactly in half and survives
     * a reload because the list does. A guard with no barracks falls back to
     * the parity of their UUID — still deterministic, still about half.
     */
    public static Watch watchOf(Settlement settlement, SettlerEntity settler) {
        Building employer = employerOf(settlement, settler.getUUID());
        int index = employer == null ? -1 : employer.workers.indexOf(settler.getUUID());
        if (index < 0) {
            return (settler.getUUID().hashCode() & 1) == 0 ? Watch.DAY : Watch.NIGHT;
        }
        return index % 2 == 0 ? Watch.DAY : Watch.NIGHT;
    }

    private Employment() {
    }
}
