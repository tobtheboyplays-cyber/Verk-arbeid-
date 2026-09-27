package com.hearthstead.entity;

import com.hearthstead.building.BuildingType;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.settlement.BuildingManager;
import com.hearthstead.settlement.DayPhase;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import net.minecraft.server.level.ServerLevel;

/**
 * The one unmet life need a resident would "think" about, as a small synced
 * code for the thought bubble. Pure presentation of server truth: it never
 * drives a goal, moves an item or changes a number. Recomputed on the
 * settler's existing once-a-second needs tick.
 *
 * <p>Codes are a wire format (synced byte) -- append only.</p>
 */
public final class LifeNeed {
    public static final int NONE = 0;
    /** Hungry and the Hearth holds no food: the player can fix this. */
    public static final int HUNGRY_NO_FOOD = 1;
    /** Night is coming and no bed is claimed or free. */
    public static final int HOMELESS = 2;
    /** Shaken during and shortly after a raid or alarm. */
    public static final int FRIGHTENED = 3;
    /** Energy nearly gone. */
    public static final int EXHAUSTED = 4;
    /** Evening with no Tavern to go to. */
    public static final int WANTS_TAVERN = 5;
    /**
     * Seated at a staffed Tavern, hungry enough to be served, but no Tavern chest or barrel holds
     * a ready meal together with an empty bottle (what one table service takes) and nothing is on
     * its way. The visit still ends on time; a Courier refills the store from the Banner.
     */
    public static final int TAVERN_NO_FOOD = 6;
    /** A Tavern guest is served below this hunger (TavernHostService.pickup). */
    static final float TAVERN_SERVE_BELOW = 75.0F;

    /** How long a resident stays shaken after the threat has passed. */
    public static final long FRIGHT_LINGER_TICKS = 1200L;
    static final float HUNGRY_BELOW = 30.0F;
    static final float EXHAUSTED_BELOW = 12.0F;

    private LifeNeed() {
    }

    /** Critical needs outrank mild ones; both yield to any work blocker. */
    public static boolean critical(int code) {
        return code == HUNGRY_NO_FOOD || code == FRIGHTENED;
    }

    /** Any raid or alarm the village is currently living through. */
    public static boolean threatActive(Settlement settlement, long now) {
        return settlement.pendingRaid != null
            || settlement.raidLifecycle.isAuthoredFirstRaidActive()
            || settlement.recurringRaidRun.isActive()
            || settlement.alertActive(now);
    }

    /**
     * The most pressing unmet need, highest priority first. Cheap: no world
     * scan beyond the settler's own Hearth and, only for a bedless resident
     * near nightfall, the settlement's bed list.
     */
    public static int compute(SettlerEntity settler, ServerLevel level, long frightenedUntil) {
        if (!settler.isAlive() || !settler.isBound() || settler.isTraveler()) {
            return NONE;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null) {
            return NONE;
        }
        long now = level.getGameTime();
        if (!settler.hasMeal() && settler.getHunger() < HUNGRY_BELOW) {
            HearthBlockEntity hearth = settler.hearth();
            if (hearth == null || hearth.countFoodUnits() <= 0) {
                return HUNGRY_NO_FOOD;
            }
        }
        if (now < frightenedUntil && !settler.getProfession().battlefield()) {
            return FRIGHTENED;
        }
        if (tavernWithoutFood(settler, level, settlement)) {
            return TAVERN_NO_FOOD;
        }
        DayPhase phase = settler.dayPhase();
        boolean nightfall = phase == DayPhase.EVENING || phase == DayPhase.REST;
        if (nightfall && settler.getClaimedBed() == null
            && BuildingManager.findFreeBed(level, settlement) == null) {
            return HOMELESS;
        }
        if (settler.getEnergy() < EXHAUSTED_BELOW && !settler.isSleeping()
            && settler.getActivity() != SettlerActivity.RESTING) {
            return EXHAUSTED;
        }
        if (phase == DayPhase.EVENING && !settler.getProfession().martial()
            && Schedule.firstValid(settlement, BuildingType.TAVERN) == null) {
            return WANTS_TAVERN;
        }
        return NONE;
    }

    /** Only for a seated guest (one bounded store scan a second); a service in flight counts. */
    static boolean tavernWithoutFood(SettlerEntity settler, ServerLevel level, Settlement settlement) {
        if (settler.hasMeal() || settler.getHunger() >= TAVERN_SERVE_BELOW) {
            return false;
        }
        com.hearthstead.settlement.Building tavern = com.hearthstead.settlement.TavernSeating.currentTavern(settler);
        if (tavern == null || tavern.bounds == null
            || !com.hearthstead.settlement.TavernSeating.staffed(level, settlement, tavern)
            || com.hearthstead.settlement.work.TavernHostService.hasOrderForGuest(settler)) {
            return false;
        }
        java.util.UUID id = settler.getUUID();
        net.minecraft.world.phys.AABB area = net.minecraft.world.phys.AABB.of(tavern.bounds).inflate(2.0D);
        if (!level.getEntitiesOfClass(TavernServingEntity.class, area,
            s -> s.isAlive() && id.equals(s.guestId())).isEmpty()) {
            return false;
        }
        return com.hearthstead.settlement.work.TavernHostService.source(settler, tavern) == null;
    }
}
