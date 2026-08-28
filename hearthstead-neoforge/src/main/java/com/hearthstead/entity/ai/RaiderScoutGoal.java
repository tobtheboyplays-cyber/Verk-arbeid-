package com.hearthstead.entity.ai;

import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.settlement.Settlement;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * What a telegraph scout does: stands near the treeline, watches the
 * settlement it may soon be part of raiding, and fades back into the woods.
 *
 * <p>Deliberately inert. A scout that fought or looted would just be an
 * early, weaker raider -- the point of the telegraph
 * ({@link com.hearthstead.settlement.raid.RaidTelegraph}) is an omen the
 * player can <em>read</em> (a shape at the edge of the light, the bard's
 * unease broadcast the same evening), not a free skirmish. It can still be
 * killed -- {@link RaiderEntity}'s {@code HurtByTargetGoal} and
 * {@code MeleeAttackGoal} still apply once something else starts that fight
 * -- this goal only stops the scout from starting one itself (see the
 * {@code !isScout()} guards on {@code RaiderEntity}'s target-selector goals).
 */
public class RaiderScoutGoal extends Goal {

    /**
     * Long enough to actually be noticed at dusk; short enough not to
     * linger afterward as an NPC nobody asked for. Bounded on purpose --
     * every settler task/entity behaviour in this mod has a real end, not
     * an unbounded per-tick presence.
     */
    public static final int LIFESPAN_TICKS = 1800;

    private final RaiderEntity raider;
    private int age;

    public RaiderScoutGoal(RaiderEntity raider) {
        this.raider = raider;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return raider.isScout();
    }

    @Override
    public boolean canContinueToUse() {
        // getTarget() != null the instant something (HurtByTargetGoal) makes
        // this scout fight back; stopping here hands MOVE/LOOK to
        // MeleeAttackGoal instead of holding them through combat.
        return raider.isScout() && raider.getTarget() == null && age < LIFESPAN_TICKS;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        age = 0;
    }

    @Override
    public void tick() {
        age++;
        Settlement s = raider.settlement();
        if (s != null) {
            raider.getLookControl().setLookAt(
                s.center.getX() + 0.5, s.center.getY() + 1.0, s.center.getZ() + 0.5);
        }
        if (age >= LIFESPAN_TICKS) {
            // Withdraws quietly -- the omen was seen; it does not need to be
            // fought to have done its job.
            raider.discard();
        }
    }
}
