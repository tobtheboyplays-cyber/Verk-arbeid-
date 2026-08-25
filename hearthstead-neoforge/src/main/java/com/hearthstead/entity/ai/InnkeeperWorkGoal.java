package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * The innkeeper's work: no recipe, no chest, just being there for the guests.
 *
 * <p>SLICE RECRUIT-1 (DESIGN.md system 8). Every other trade in
 * {@link Employment}'s trade table makes something a chest can hold, so
 * {@link CrafterWorkGoal} covers all twelve of them with one shape built
 * around a {@link com.hearthstead.building.Production.Recipe}. The tavern has
 * nothing to craft — {@code Production} has no recipe for it and never will,
 * because hospitality is not an item — so the innkeeper gets a goal of their
 * own rather than a made-up recipe that would exist only to keep
 * {@code CrafterWorkGoal}'s assumption true.
 *
 * <p>What being hired here actually buys the settlement lives entirely in
 * {@link com.hearthstead.settlement.SettlementManager#tickRecruitment}: a
 * waiting guest's patience doubles and the recruit gauge climbs faster while
 * the tavern building's own worker list is non-empty — read straight off the
 * employment roster the way Employment's class doc says it must be, never a
 * second flag this goal has to keep in step. This goal only ever makes that
 * already-true state visible: the innkeeper actually standing at the bar,
 * working, whenever a guest could be watching them do it.
 */
public class InnkeeperWorkGoal extends Goal {

    /** How often to ask whether there is a post to stand at. */
    private static final int LOOK_INTERVAL = 20;

    private final SettlerEntity settler;
    private Building tavern;
    private int lookCooldown;
    private int workTicks;

    public InnkeeperWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (settler.getProfession() != Profession.INNKEEPER || !settler.isBound()
            || settler.getTarget() != null || settler.getEnergy() <= 15.0F) {
            return false;
        }
        if (lookCooldown > 0) {
            lookCooldown--;
            return false;
        }
        lookCooldown = LOOK_INTERVAL;
        Settlement settlement = settler.settlement();
        if (settlement == null
            || !Schedule.shouldWork(settlement, settler, settler.dayPhase())) {
            return false;
        }
        Building building = Employment.employerOf(settlement, settler.getUUID());
        if (building == null || !building.valid || building.anchor == null) {
            return false;
        }
        // Work happens AT the tavern, the same way it does for every other
        // trade that works at its building (Employment#worksAtTheBuilding):
        // getting there is GoToPostGoal's job, not this goal's.
        if (!settler.blockPosition().closerThan(building.anchor, Schedule.AT_POST)) {
            return false;
        }
        tavern = building;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        Settlement settlement = settler.settlement();
        return tavern != null && settler.getTarget() == null
            && settlement != null
            && Schedule.shouldWork(settlement, settler, settler.dayPhase());
    }

    @Override
    public void start() {
        settler.getNavigation().stop();
        settler.setActivity(Employment.motionOf(tavern.type));
        workTicks = 0;
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        tavern = null;
    }

    @Override
    public void tick() {
        if (!(settler.level() instanceof ServerLevel level)) {
            return;
        }
        // The sound rides the clip, not a timer of its own — same idiom as
        // CrafterWorkGoal, so an innkeeper reads exactly like every other
        // trade even though there is no recipe underneath this one.
        int period = Employment.soundPeriodOf(tavern.type);
        if (++workTicks % period == 0) {
            level.playSound(null, settler.blockPosition(),
                Employment.soundOf(tavern.type),
                SoundSource.NEUTRAL, 0.7F,
                0.94F + settler.getRandom().nextFloat() * 0.12F);
            settler.train(Employment.trainedBy(tavern.type), 1.0F);
        }
    }
}
