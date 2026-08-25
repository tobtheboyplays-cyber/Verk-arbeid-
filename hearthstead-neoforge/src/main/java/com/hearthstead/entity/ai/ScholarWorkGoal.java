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
            || settler.getTarget() != null || settler.getEnergy() <= 15.0F
            // The daily labor pool (PLAN_EFFORT.md): once spent, no new
            // session starts, exactly CrafterWorkGoal's own guard.
            || settler.isEffortSpent()) {
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
        if (!Research.hasActiveProject(level, settlement.id)) {
            return false; // nothing chosen yet -- the player picks at the screen
        }
        study = building;
        return true;
    }

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
        if (settlement != null && Research.hasActiveProject(level, settlement.id)) {
            Research.advanceSession(level, settlement.id);
            settler.train(Attribute.WITS, 1.0F);
            settler.addMorale(0.5F);
            // One completed session is the same 6 units of the daily pool
            // every session costs (see EFFORT_PER_SESSION's own doc note),
            // charged on the tick the session actually completes -- the
            // same rule CrafterWorkGoal's batch pays under.
            settler.spendEffort(EFFORT_PER_SESSION);
        }

        // Chain straight into the next session, the way a crafter chains
        // batches -- UNLESS the project just finished or the effort pool
        // just ran dry.
        if (settlement == null || !Research.hasActiveProject(level, settlement.id)
            || settler.isEffortSpent()) {
            working = false;
            return;
        }
        ticksLeft = SESSION_TICKS;
        workedTicks = 0;
    }
}
