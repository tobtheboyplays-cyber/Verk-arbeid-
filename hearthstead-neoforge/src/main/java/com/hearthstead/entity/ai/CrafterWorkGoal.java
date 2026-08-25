package com.hearthstead.entity.ai;

import com.hearthstead.building.Production;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * The work every crafting trade does: stand at your bench and make the thing.
 *
 * <p>One goal, eleven trades. The shape is identical — take from your own
 * chests, spend time, put something back — so it is written once here and
 * differs only in the recipe table ({@link Production}) and the motion
 * ({@link Employment#motionOf}). Eleven bespoke work goals would be eleven
 * places for the same bug.
 *
 * <h2>What it is careful about</h2>
 *
 * <p><b>It does not scan every tick.</b> Asking whether there is work means
 * reading the building's containers, so it is asked on a cooldown and not
 * otherwise. All world scanning here is budgeted, like everything else.
 *
 * <p><b>The work takes the time the recipe says.</b> The output appears when
 * the clip finishes, not when the goal starts — a profession that teleported
 * its result would make the animation decoration rather than the work.
 *
 * <p><b>Doing the job makes you better at it.</b> One completed action trains
 * the attribute that trade leans on ({@link Employment#trainedBy}), which is
 * what "learning by doing" has to mean if it is to mean anything: counted on
 * completion, never on a timer.
 */
public class CrafterWorkGoal extends Goal {

    /** How often to ask the chests whether there is anything to do. */
    private static final int LOOK_INTERVAL = 20;

    private final SettlerEntity settler;
    private Production.Recipe recipe;
    private Building bench;
    private int lookCooldown;
    private int ticksLeft;
    private int workedTicks;

    public CrafterWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!settler.isBound() || settler.getTarget() != null
            || settler.getEnergy() <= 15.0F) {
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
        if (building == null || !building.valid || building.anchor == null
            || !Production.produces(building.type)) {
            return false;
        }
        // Work happens AT the bench. Getting there is GoToPostGoal's job; if
        // the settler is not there yet, this simply is not their turn.
        if (!settler.blockPosition().closerThan(building.anchor, Schedule.AT_POST)) {
            return false;
        }
        Production.Recipe ready = Production.ready(level, building);
        if (ready == null) {
            return false;
        }
        bench = building;
        recipe = ready;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        Settlement settlement = settler.settlement();
        return recipe != null && bench != null && settler.getTarget() == null
            && settlement != null
            && Schedule.shouldWork(settlement, settler, settler.dayPhase());
    }

    @Override
    public void start() {
        settler.getNavigation().stop();
        settler.setActivity(Employment.motionOf(bench.type));
        ticksLeft = recipe.ticks();
        workedTicks = 0;
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        recipe = null;
        bench = null;
        ticksLeft = 0;
    }

    @Override
    public void tick() {
        if (!(settler.level() instanceof ServerLevel level)) {
            return;
        }
        // The sound rides the clip, not a timer of its own: one per loop of
        // whatever motion this trade performs, so what you hear and what you
        // see are the same action (job standard, point 6).
        int period = Employment.soundPeriodOf(bench.type);
        if (++workedTicks % period == 0) {
            level.playSound(null, settler.blockPosition(),
                Employment.soundOf(bench.type),
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.75F,
                0.94F + settler.getRandom().nextFloat() * 0.12F);
        }
        if (--ticksLeft > 0) {
            return;
        }
        boolean made = Production.run(level, bench, recipe);
        if (made) {
            settler.train(Employment.trainedBy(bench.type), 1.0F);
            settler.addMorale(0.5F);
        }
        // Look for the next piece of work straight away: a crafter with a full
        // chest of wheat should not pause between loaves.
        Production.Recipe next = Production.ready(level, bench);
        if (next == null) {
            recipe = null;
            return;
        }
        recipe = next;
        ticksLeft = next.ticks();
    }
}
