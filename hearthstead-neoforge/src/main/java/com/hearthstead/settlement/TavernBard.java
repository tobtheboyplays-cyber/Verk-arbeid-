package com.hearthstead.settlement;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tavern evening bard: during the social evening window one resident who is
 * already seated in a Tavern plays music for the room. Residents who then
 * finish a real Tavern meal or Ale get one small morale lift per game day.
 *
 * <p>Server-authoritative and presentation-light. This class never seats,
 * paths, teleports or hands items to anyone; it only reads who is already
 * there. Selection is deterministic (lowest UUID among eligible residents),
 * keeps an incumbent while it stays eligible, and looks for a new performer at
 * most once every {@link #REEVALUATE_TICKS}. The per-Tavern stage is transient:
 * after a reload the next seated tick simply re-selects.
 */
public final class TavernBard {
    public static final int REEVALUATE_TICKS = 100;
    /** Requested through {@link SettlerEntity#addMorale}, so traits still scale it and 100 still caps it. */
    public static final float EVENING_LIFT = 5.0F;
    /** Below this a resident is too hungry to perform; the meal comes first. */
    public static final float CRITICAL_HUNGER = 20.0F;
    /** Persisted in the settler's NeoForge persistent data; absent on old saves means "never lifted". */
    public static final String LIFT_DAY_KEY = "HearthsteadTavernBardLiftDay";

    private record Stage(UUID bard, long nextEvaluation) {}
    private static final Map<UUID, Stage> STAGES = new ConcurrentHashMap<>();

    private TavernBard() {}

    /**
     * Live eligibility for performing in {@code tavern}: a bound, non-martial,
     * non-Innkeeper resident seated in that exact Tavern during the evening
     * window, awake, not eating, not critically hungry and not in danger.
     */
    public static boolean eligible(SettlerEntity actor, Settlement settlement, Building tavern) {
        if (!(actor.level() instanceof ServerLevel level) || settlement == null || tavern == null
            || !tavern.valid || tavern.type != BuildingType.TAVERN) return false;
        if (!actor.isAlive() || actor.isRemoved() || actor.isTraveler()
            || !settlement.id.equals(actor.getSettlementId())
            || settlement.record(actor.getUUID()) == null) return false;
        Profession profession = actor.getProfession();
        if (profession.martial() || profession == Profession.INNKEEPER) return false;
        if (actor.isSleeping() || actor.hasMeal() || actor.getHunger() < CRITICAL_HUNGER
            || actor.getTarget() != null || actor.hurtTime > 0 || actor.isOnFire()
            || Summons.active(actor)) return false;
        if (settlement.pendingRaid != null || settlement.alertUntilGameTime > level.getGameTime()) return false;
        if (!TavernVisitSchedule.isOpen(level.getDayTime())) return false;
        return TavernSeating.currentTavern(actor) == tavern;
    }

    /** The single performing bard of this Tavern right now, or null. */
    public static SettlerEntity bard(ServerLevel level, Settlement settlement, Building tavern) {
        if (settlement == null || tavern == null || tavern.bounds == null) return null;
        long now = level.getGameTime();
        Stage stage = STAGES.get(tavern.id);
        if (stage != null && stage.bard() != null) {
            if (level.getEntity(stage.bard()) instanceof SettlerEntity current
                && eligible(current, settlement, tavern)) return current;
            // The performer left, sat down to eat, slept or the evening ended:
            // the set ends now; a successor waits for the normal cadence.
            stage = new Stage(null, stage.nextEvaluation());
            STAGES.put(tavern.id, stage);
        }
        // A stage from an unrelated clock (another level/test) is stale, not a lock-out.
        boolean fresh = stage != null && stage.nextEvaluation() - now <= REEVALUATE_TICKS;
        if (fresh && now < stage.nextEvaluation()) return null;
        if (!TavernVisitSchedule.isOpen(level.getDayTime())) {
            STAGES.remove(tavern.id);
            return null;
        }
        SettlerEntity chosen = null;
        for (SettlerEntity candidate : level.getEntitiesOfClass(SettlerEntity.class,
                AABB.of(tavern.bounds).inflate(1.0), c -> eligible(c, settlement, tavern))) {
            if (chosen == null || candidate.getUUID().compareTo(chosen.getUUID()) < 0) chosen = candidate;
        }
        STAGES.put(tavern.id, new Stage(chosen == null ? null : chosen.getUUID(), now + REEVALUATE_TICKS));
        if (chosen != null && com.hearthstead.util.QaTrace.ENABLED) {
            com.hearthstead.util.QaTrace.event(chosen, "tavern_bard_selected", "tavern=" + tavern.id);
        }
        return chosen;
    }

    /** True while {@code actor} is the selected, still-eligible performer of the Tavern it sits in. */
    public static boolean isBard(SettlerEntity actor) {
        if (!(actor.level() instanceof ServerLevel level)) return false;
        Building tavern = TavernSeating.currentTavern(actor);
        return tavern != null && bard(level, TavernSeating.visitSettlement(actor), tavern) == actor;
    }

    /**
     * Exact completion seam: {@code guest} has just finished a real Tavern
     * meal or Ale served in {@code tavernId}. Grants {@link #EVENING_LIFT}
     * once per settler per game day while a bard performs in that same Tavern.
     *
     * @return true only when the lift was granted by this call
     */
    public static boolean onRefreshmentCompleted(SettlerEntity guest, UUID tavernId) {
        // Tech tree (Bard's Songbook): a traveller who heard the bard tips 1 Coin.
        if (guest.isTraveler()) {
            com.hearthstead.settlement.techtree.effects.CommonsEffects.bardTip(guest, tavernId);
            return false;
        }
        if (!(guest.level() instanceof ServerLevel level) || !guest.isAlive()) return false;
        Settlement settlement = guest.settlement();
        Building tavern = TavernSeating.building(settlement, tavernId);
        if (tavern == null) return false;
        SettlerEntity bard = bard(level, settlement, tavern);
        if (bard == null || bard == guest) return false;
        long day = Math.floorDiv(level.getDayTime(), 24000L);
        CompoundTag data = guest.getPersistentData();
        if (data.contains(LIFT_DAY_KEY, Tag.TAG_LONG) && data.getLong(LIFT_DAY_KEY) == day) return false;
        data.putLong(LIFT_DAY_KEY, day);
        // Tech tree (Bard's Songbook 5 -> 8, Hall of Revels x2).
        float lift = com.hearthstead.settlement.techtree.effects.CommonsEffects
            .bardLift(level, settlement, EVENING_LIFT);
        guest.addMorale(lift);
        // One quiet note above the listener; the morale joy cue does the rest.
        level.sendParticles(ParticleTypes.NOTE, guest.getX(), guest.getY() + guest.getBbHeight() + 0.4,
            guest.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
        Hearthstead.LOGGER.info("HEARTHSTEAD_TAVERN_BARD_LIFT guest={} bard={} tavern={} day={} morale={}",
            guest.getUUID(), bard.getUUID(), tavern.id, day, lift);
        return true;
    }

    /** Test/QA seam: forget every transient stage so selection starts fresh. */
    public static void resetForTests() {
        STAGES.clear();
    }
}
