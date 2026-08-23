package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Settlers gather at the hearth to rest through the night; anyone can also
 * collapse into a rest when exhausted. Guards skip the night curfew.
 */
public class RestAtNightGoal extends Goal {
    private final SettlerEntity settler;
    private boolean resting;
    private int repathTimer;

    public RestAtNightGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    private boolean nightCurfew() {
        return settler.getProfession() != Profession.GUARD
            && settler.dayPhase() == SettlerEntity.DayPhase.REST;
    }

    @Override
    public boolean canUse() {
        return settler.isBound() && settler.getHearthPos() != null
            && (nightCurfew() || settler.getEnergy() < 12);
    }

    @Override
    public void start() {
        resting = false;
        path();
    }

    private void path() {
        BlockPos hearth = settler.getHearthPos();
        settler.getNavigation().moveTo(hearth.getX() + 0.5, hearth.getY() + 1,
            hearth.getZ() + 0.5, 0.9);
    }

    @Override
    public boolean canContinueToUse() {
        if (!settler.isBound() || settler.getHearthPos() == null) {
            return false;
        }
        if (nightCurfew()) {
            return true;
        }
        return settler.getEnergy() < 60;
    }

    @Override
    public void tick() {
        BlockPos hearth = settler.getHearthPos();
        if (hearth == null) {
            return;
        }
        if (resting) {
            // Stay put, gaze into the fire.
            settler.getNavigation().stop();
            settler.getLookControl().setLookAt(hearth.getX() + 0.5, hearth.getY() + 0.5,
                hearth.getZ() + 0.5);
            if (settler.blockPosition().distSqr(hearth) > 49) {
                resting = false; // shoved away; wander back
                path();
                settler.setActivity(SettlerActivity.IDLE);
            }
            return;
        }
        if (settler.blockPosition().distSqr(hearth) <= 20) {
            resting = true;
            settler.getNavigation().stop();
            settler.setActivity(SettlerActivity.RESTING);
        } else if (--repathTimer <= 0) {
            repathTimer = 60;
            path();
        }
    }

    @Override
    public void stop() {
        resting = false;
        settler.setActivity(SettlerActivity.IDLE);
    }
}
