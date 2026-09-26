package com.hearthstead.entity.ai;

import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.research.Research;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * The scholar's work: stand at the lectern and advance whatever the
 * settlement's one active project is.
 *
 * <p>Shaped after {@link CrafterWorkGoal} — look, work a fixed stint, pay the
 * cost, chain into the next stint if there is one — but there is no {@code
 * Production.Recipe} underneath it, the same reason {@link InnkeeperWorkGoal}
 * exists rather than reusing {@code CrafterWorkGoal} directly: research is a
 * multi-day undertaking with one shared state
 * ({@link com.hearthstead.settlement.research.ResearchState}), not a batch
 * recipe a chest can gate.
 *
 * <h2>What one "session" means</h2>
 *
 * <p>A completed session is what {@code ResearchProject#workDays} counts —
 * see that field's own doc. {@link #EFFORT_PER_SESSION} is set so a fresh
 * scholar's daily {@code Effort} pool (20 units — {@code Effort#BASE_CAPACITY})
 * affords roughly three sessions before the pool runs dry, which is
 * deliberate: "3 scholar work-days" in the project ledger is meant to read as
 * "about three real work-days of dedicated attention," and it does, without
 * this goal ever having to know what day it is.
 *
 * @see com.hearthstead.settlement.research.ResearchProject
 */
public class ScholarWorkGoal extends Goal {

    /** How often to ask whether there is a project to advance. */
    private static final int LOOK_INTERVAL = 20;
    /** One session's active-work length, on the same scale as every other
     *  crafting trade's recipe ticks (140–300 across {@code Production}). */
    private static final int SESSION_TICKS = 200;
    /** See the class doc's "What one session means". */
    private static final int EFFORT_PER_SESSION = 6;

    private final SettlerEntity settler;
    private Building study;
    private boolean working;
    private int lookCooldown;
    private int ticksLeft;
    private int workedTicks;

    public ScholarWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (settler.getProfession() != Profession.SCHOLAR || !settler.isBound()
            || settler.getTarget() != null) {
            return false;
        }
        if (lookCooldown > 0) {
            lookCooldown--;
            return false;
        }
        lookCooldown = LOOK_INTERVAL;
        if (!(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null
            || !Schedule.shouldWork(settlement, settler, settler.dayPhase())) {
            return false;
        }
        Building building = Employment.employerOf(settlement, settler.getUUID());
        if (building == null || !building.valid || building.anchor == null) {
            return false;
        }
        // Work happens AT the lectern, the same rule every building-bound
        // trade follows (Employment#worksAtTheBuilding): getting there is
        // GoToPostGoal's job, not this goal's.
        if (!settler.blockPosition().closerThan(building.anchor, Schedule.AT_POST)) {
            return false;
        }
        if (!Research.hasActiveProject(level, settlement.id)
            && !startNextProject(level, settlement, building)
            && !com.hearthstead.settlement.development.TechTree.anyStudy(level, settlement)) {
            // Nothing chosen, nothing the study can afford, no tech being
            // studied: say so on the sheet instead of idling silently.
            if (settler.logisticsStopReason() != com.hearthstead.logistics.StopReason.NOTHING_TO_STUDY) {
                settler.setLogisticsStop(com.hearthstead.logistics.StopReason.NOTHING_TO_STUDY,
                    building.anchor, 0);
            }
            return false;
        }
        if (settler.logisticsStopReason() == com.hearthstead.logistics.StopReason.NOTHING_TO_STUDY) {
            settler.clearLogisticsStop();
        }
        study = building;
        return true;
    }

    /**
     * With no project running, the scholar picks up the next unfinished,
     * released project whose materials are already stocked in the study
     * (the player still chooses by what they stock, or by starting one at
     * the screen). Before this, a finished project left the scholar idle for
     * the rest of the game (reliability soak: 83-88% of the workday idle).
     * Research.start takes the materials only on success.
     */
    private boolean startNextProject(ServerLevel level, Settlement settlement, Building building) {
        long now = level.getGameTime();
        if (now < nextAutoStartTick) {
            return false;
        }
        nextAutoStartTick = now + AUTO_START_INTERVAL;
        for (com.hearthstead.settlement.research.ResearchProject project
                : com.hearthstead.settlement.research.ResearchProject.values()) {
            if (Research.start(level, settlement, building, project) == null) {
                return true;
            }
        }
        return false;
    }

    private static final long AUTO_START_INTERVAL = 600L;
    /**
     * Extra study clock per completed session while a tech node is studied:
     * one session (200 ticks) at the lectern adds 200 day-time ticks, so a
     * Scholar working the whole workday roughly doubles study speed. A
     * tuning number (plan/RELIABILITY_THROUGHPUT_PROPOSAL.md), not a law.
     */
    private static final long TECH_STUDY_DAY_TICKS_PER_SESSION = 200L;
    private long nextAutoStartTick;

    @Override
    public boolean canContinueToUse() {
        Settlement settlement = settler.settlement();
        return working && study != null && settler.getTarget() == null
            && settlement != null
            && Schedule.shouldWork(settlement, settler, settler.dayPhase());
    }

    @Override
    public void start() {
        settler.getNavigation().stop();
        settler.setActivity(Employment.motionOf(study.type));
        working = true;
        ticksLeft = SESSION_TICKS;
        workedTicks = 0;
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        working = false;
        study = null;
        ticksLeft = 0;
    }

    @Override
    public void tick() {
        if (!(settler.level() instanceof ServerLevel level)) {
            return;
        }
        // The sound rides the clip, not a timer of its own -- same idiom as
        // every other trade goal (CrafterWorkGoal, InnkeeperWorkGoal).
        int period = Employment.soundPeriodOf(study.type);
        if (++workedTicks % period == 0) {
            level.playSound(null, settler.blockPosition(),
                Employment.soundOf(study.type),
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.6F,
                0.94F + settler.getRandom().nextFloat() * 0.12F);
        }
        if (--ticksLeft > 0) {
            return;
        }

        Settlement settlement = settler.settlement();
        boolean researching = settlement != null && Research.hasActiveProject(level, settlement.id);
        if (!researching && settlement != null
            && com.hearthstead.settlement.development.TechTree.scholarSession(level, settlement,
                TECH_STUDY_DAY_TICKS_PER_SESSION)) {
            // Town+ tech study at the lectern: the same training, morale and
            // effort as a research session (reliability soak 2026-09-26).
            settler.train(Attribute.WITS, 1.0F);
            com.hearthstead.entity.SkillLevels.completeUnit(settler, 6, Attribute.WITS);
            settler.addMorale(0.5F);
            settler.spendEffort(EFFORT_PER_SESSION);
        }
        if (researching) {
            // Trade skill (Intelligence, level 2+): +1% progress per WITS
            // point above 15, cap +25%, banked as a fraction of a session.
            // Focus adds 0..10% of a session on top (AttributeRuntime.study, plan/ATTRIBUTES.md).
            Research.advanceSession(level, settlement.id,
                (float) com.hearthstead.entity.SkillLevels.researchBonus(settler)
                    + com.hearthstead.entity.AttributeRuntime.study(settler));
            settler.train(Attribute.WITS, 1.0F);
            com.hearthstead.entity.SkillLevels.completeUnit(settler, 6, Attribute.WITS);
            settler.addMorale(0.5F);
            // One completed session is the same 6 units of the daily pool
            // every session costs (see EFFORT_PER_SESSION's own doc note),
            // charged on the tick the session actually completes -- the
            // same rule CrafterWorkGoal's batch pays under.
            settler.spendEffort(EFFORT_PER_SESSION);
        }

        // Chain straight into the next session while the project remains active.
        // Fatigue changes pace centrally; it does not invalidate valid work.
        if (settlement == null || !Research.hasActiveProject(level, settlement.id)
                && !com.hearthstead.settlement.development.TechTree.anyStudy(level, settlement)) {
            working = false;
            return;
        }
        ticksLeft = SESSION_TICKS;
        workedTicks = 0;
    }
}
