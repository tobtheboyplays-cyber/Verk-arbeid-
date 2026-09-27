package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;

/** Idle wandering that never drifts outside the settlement radius. */
public class BoundedStrollGoal extends WaterAvoidingRandomStrollGoal {
    private final SettlerEntity settler;
    @Nullable private Path strollPath;

    public BoundedStrollGoal(SettlerEntity settler) {
        super(settler, 0.85);
        this.settler = settler;
        setInterval(140);
    }

    @Override
    public boolean canUse() {
        strollPath = null;
        if (!super.canUse()) return false;
        Path candidate = settler.getNavigation().createPath(
            BlockPos.containing(wantedX, wantedY, wantedZ), 0);
        // An idle outing has no reason to follow a partial path into a pit.
        // It must also avoid one-way drops that a resident cannot walk back up.
        if (!safeStrollPath(candidate)) return false;
        strollPath = candidate;
        return true;
    }

    private boolean safeStrollPath(@Nullable Path candidate) {
        if (candidate == null || !candidate.canReach()) return false;
        int previousY = settler.blockPosition().getY();
        for (int i = candidate.getNextNodeIndex(); i < candidate.getNodeCount(); i++) {
            int y = candidate.getNode(i).y;
            if (previousY - y > 1) return false;
            previousY = y;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        // Block updates may replace the navigator's route after start().
        return super.canContinueToUse() && safeStrollPath(settler.getNavigation().getPath());
    }

    @Override
    public void start() {
        // Use the exact inspected route, not coordinate moveTo's new partial path.
        if (strollPath != null) settler.getNavigation().moveTo(strollPath, speedModifier);
    }

    @Override
    public void stop() {
        super.stop();
        strollPath = null;
    }

    @Nullable
    @Override
    protected Vec3 getPosition() {
        Vec3 candidate = super.getPosition();
        Settlement s = settler.settlement();
        if (candidate == null || s == null) {
            return candidate;
        }
        double dx = candidate.x - s.center.getX();
        double dz = candidate.z - s.center.getZ();
        if (dx * dx + dz * dz > (double) s.radius * s.radius) {
            return LandRandomPos.getPosTowards(settler, 10, 7,
                Vec3.atBottomCenterOf(s.center));
        }
        return candidate;
    }
}
