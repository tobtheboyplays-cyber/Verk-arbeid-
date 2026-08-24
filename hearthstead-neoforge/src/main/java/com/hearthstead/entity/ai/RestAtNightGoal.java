package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.BuildingManager;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.BedBlock;

import java.util.EnumSet;

/**
 * Night rest: settlers with a claimed bed walk home and genuinely sleep in
 * it; the homeless doze rough by the hearth at a morale cost. Anyone can
 * also collapse into rest when exhausted. Guards skip the night curfew.
 */
public class RestAtNightGoal extends Goal {
    private final SettlerEntity settler;
    private boolean resting;
    private boolean roughNightCharged;
    private int repathTimer;
    private int claimRetryTimer;

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
        roughNightCharged = false;
        claimBedIfNeeded();
        path();
    }

    private void claimBedIfNeeded() {
        if (settler.getClaimedBed() != null
            || !(settler.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Settlement settlement = settler.settlement();
        if (settlement != null) {
            BlockPos free = BuildingManager.findFreeBed(serverLevel, settlement);
            if (free != null) {
                settler.claimBed(free);
            }
        }
    }

    private BlockPos restTarget() {
        BlockPos bed = settler.getClaimedBed();
        return bed != null ? bed : settler.getHearthPos();
    }

    private void path() {
        BlockPos target = restTarget();
        if (target != null) {
            settler.getNavigation().moveTo(target.getX() + 0.5, target.getY() + 1,
                target.getZ() + 0.5, 0.9);
        }
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
        BlockPos target = restTarget();
        if (target == null) {
            return;
        }
        if (settler.isSleeping()) {
            return; // vanilla sleep handles pose and position
        }
        if (settler.getClaimedBed() == null && --claimRetryTimer <= 0) {
            // Homes can register mid-night; the homeless keep checking.
            claimRetryTimer = 40;
            claimBedIfNeeded();
            if (settler.getClaimedBed() != null) {
                resting = false;
                settler.setActivity(SettlerActivity.IDLE);
                path();
                return;
            }
        }
        if (resting) {
            // Rough rest at the hearth: stay put, gaze into the fire.
            settler.getNavigation().stop();
            settler.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5,
                target.getZ() + 0.5);
            if (settler.blockPosition().distSqr(target) > 49) {
                resting = false; // shoved away; wander back
                path();
                settler.setActivity(SettlerActivity.IDLE);
            }
            return;
        }

        BlockPos bed = settler.getClaimedBed();
        if (bed != null) {
            // The claim can rot: bed broken or its home invalidated.
            if (!(settler.level().getBlockState(bed).getBlock() instanceof BedBlock)) {
                settler.releaseBed();
                claimBedIfNeeded();
                path();
                return;
            }
            if (settler.blockPosition().distSqr(bed) <= 4.5) {
                settler.getNavigation().stop();
                settler.startSleeping(bed);
                settler.setActivity(SettlerActivity.SLEEPING);
                return;
            }
        } else if (settler.blockPosition().distSqr(target) <= 20) {
            resting = true;
            settler.getNavigation().stop();
            settler.setActivity(SettlerActivity.RESTING);
            if (nightCurfew() && !roughNightCharged) {
                roughNightCharged = true;
                settler.addMorale(-3.0F); // slept rough on cold ground
            }
            return;
        }
        if (--repathTimer <= 0) {
            repathTimer = 60;
            path();
        }
    }

    @Override
    public void stop() {
        if (settler.isSleeping()) {
            settler.stopSleeping();
            // WAKE_STRETCH: only for a genuine bed-wake, not every goal stop
            // (e.g. being attacked mid-rest at the hearth).
            settler.triggerWakeStretch();
        }
        resting = false;
        settler.setActivity(SettlerActivity.IDLE);
    }
}
