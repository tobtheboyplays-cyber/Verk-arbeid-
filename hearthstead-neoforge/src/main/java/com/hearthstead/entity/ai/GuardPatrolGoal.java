package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/** Guards walk a ring of waypoints around the hearth, pausing to scan. */
public class GuardPatrolGoal extends Goal {
    private static final int WAYPOINTS = 8;

    private final SettlerEntity settler;
    private int waypointIndex;
    private int pauseTicks;
    private BlockPos currentWaypoint;

    public GuardPatrolGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (settler.getProfession() != Profession.GUARD
            || settler.getTarget() != null
            || settler.getEnergy() <= 10) {
            return false;
        }
        Settlement settlement = settler.settlement();
        // Half the garrison stands the night watch. Off-watch guards fall
        // through to the ordinary day -- meals, the tavern, their own bed.
        return settlement != null
            && Schedule.onWatch(settlement, settler, settler.dayPhase());
    }

    @Override
    public void start() {
        settler.setActivity(SettlerActivity.PATROLLING);
        nextWaypoint();
    }

    /**
     * A guard's work is the walking, so that is what is counted and what is
     * heard: one waypoint reached is one unit (job standard, point 8), and the
     * armour answers at each one (point 6) — a patrol you can hear passing
     * outside at night is worth more than one you can only see.
     */
    private void reachedWaypoint() {
        settler.train(com.hearthstead.entity.Attribute.STAMINA, 1.0F);
        if (settler.level() instanceof net.minecraft.server.level.ServerLevel level) {
            level.playSound(null, settler.blockPosition(),
                com.hearthstead.registry.ModSounds.ARMOUR_CLINK.get(),
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.55F,
                0.94F + settler.getRandom().nextFloat() * 0.12F);
        }
    }

    private void nextWaypoint() {
        Settlement s = settler.settlement();
        if (s == null) {
            return;
        }
        waypointIndex = (waypointIndex + 1) % WAYPOINTS;
        double angle = waypointIndex * (Math.PI * 2 / WAYPOINTS);
        double r = s.radius * 0.55;
        int x = s.center.getX() + (int) (Math.cos(angle) * r);
        int z = s.center.getZ() + (int) (Math.sin(angle) * r);
        BlockPos surface = settler.level().getHeightmapPos(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, s.center.getY(), z));
        currentWaypoint = surface;
        settler.getNavigation().moveTo(surface.getX() + 0.5, surface.getY(),
            surface.getZ() + 0.5, 0.9);
        pauseTicks = 0;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        if (settler.getNavigation().isDone()) {
            if (pauseTicks == 0) {
                reachedWaypoint();
            }
            pauseTicks++;
            if (pauseTicks % 25 == 0) {
                // Scan the surroundings while paused.
                double angle = settler.getRandom().nextDouble() * Math.PI * 2;
                settler.getLookControl().setLookAt(
                    settler.getX() + Math.cos(angle) * 8, settler.getEyeY(),
                    settler.getZ() + Math.sin(angle) * 8);
            }
            if (pauseTicks > 50) {
                nextWaypoint();
            }
        } else if (currentWaypoint != null
            && settler.blockPosition().distSqr(currentWaypoint) < 4) {
            settler.getNavigation().stop();
        }
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
    }
}
