package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Civilians sprint to the hearth while danger is near. */
public class SettlerPanicGoal extends Goal {
    // RUN_PANIC's accent contract (catalogue §1.4): a breath yelp at tick 3
    // of the first 11-tick cycle, then throttled to one vocal per 2 s so a
    // fleeing crowd is not a wall of noise. Must agree with the clip
    // comment in SettlerAnimations and the assertion in tools/anim_check.py.
    public static final int PANIC_YELP_TICK = 3;
    public static final int PANIC_VOCAL_THROTTLE = 40;

    private final SettlerEntity settler;
    private int panicTicks;

    public SettlerPanicGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true; // the yelp's tick math needs a per-tick clock
    }

    private boolean danger() {
        if (settler.hurtTime > 0) {
            return true;
        }
        Settlement s = settler.settlement();
        return s != null && s.alertActive(settler.level().getGameTime());
    }

    @Override
    public boolean canUse() {
        if (settler.getProfession() == Profession.GUARD || !settler.isBound()) {
            return false;
        }
        BlockPos hearth = settler.getHearthPos();
        return hearth != null && danger() && settler.blockPosition().distSqr(hearth) > 16;
    }

    @Override
    public void start() {
        if (settler.isSleeping()) {
            settler.stopSleeping();
        }
        panicTicks = 0;
        BlockPos hearth = settler.getHearthPos();
        settler.getNavigation().moveTo(hearth.getX() + 0.5, hearth.getY() + 1,
            hearth.getZ() + 0.5, 1.25);
        settler.setActivity(SettlerActivity.FLEEING);
    }

    @Override
    public boolean canContinueToUse() {
        return danger() && !settler.getNavigation().isDone();
    }

    @Override
    public void tick() {
        panicTicks++;
        if (panicTicks % PANIC_VOCAL_THROTTLE == PANIC_YELP_TICK) {
            settler.level().playSound(null, settler.getX(), settler.getY(),
                settler.getZ(), com.hearthstead.registry.ModSounds.SETTLER_PANIC.get(),
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.9F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
        }
        if (settler.getNavigation().isDone()) {
            BlockPos hearth = settler.getHearthPos();
            if (hearth != null && settler.blockPosition().distSqr(hearth) > 16) {
                settler.getNavigation().moveTo(hearth.getX() + 0.5, hearth.getY() + 1,
                    hearth.getZ() + 0.5, 1.25);
            }
        }
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
    }
}
