package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * A2a STEP 0 stub: registered so the goal slot, flags and priority are
 * fixed, but inert until the courier piece lands its state machine
 * (idle -> hearth pickup -> laden walk -> warehouse set-down -> sorting).
 */
public class CourierWorkGoal extends Goal {
    private final SettlerEntity settler;

    public CourierWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return false;
    }
}
