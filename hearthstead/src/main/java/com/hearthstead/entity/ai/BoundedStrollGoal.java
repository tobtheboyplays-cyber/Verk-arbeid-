package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/** Idle wandering that never drifts outside the settlement radius. */
public class BoundedStrollGoal extends WaterAvoidingRandomStrollGoal {
    private final SettlerEntity settler;

    public BoundedStrollGoal(SettlerEntity settler) {
        super(settler, 0.85);
        this.settler = settler;
        setInterval(140);
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
