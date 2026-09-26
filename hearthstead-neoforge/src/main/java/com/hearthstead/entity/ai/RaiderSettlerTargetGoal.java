package com.hearthstead.entity.ai;

import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;

import java.util.Comparator;
import java.util.function.Predicate;

/**
 * A raider's settler target choice (26 Sep, civilian-safety pass). Vanilla's
 * nearest-first rule made a raider walking through a street take whichever
 * civilian was fleeing past, while the Guard fighting it two blocks away was
 * ignored. The same candidates (visibility, KF-027 war scope) are now ranked
 * by an effective distance: a fleeing civilian counts
 * {@link #FLEEING_PENALTY_BLOCKS_OTHER} further away for Grain and Fire
 * raids, which came for stores and buildings. Blood raids keep plain
 * nearest-first (see {@link #effectiveDistance(double, boolean, boolean,
 * RaidObjective)} for the measurement that ruled out defender-first).
 * Retaliation is untouched: {@code HurtByTargetGoal} still runs first.
 */
public final class RaiderSettlerTargetGoal extends NearestAttackableTargetGoal<SettlerEntity> {
    public static final double FLEEING_PENALTY_BLOCKS_OTHER = 16.0D;

    private final RaiderEntity raider;

    public RaiderSettlerTargetGoal(RaiderEntity raider, Predicate<LivingEntity> scope) {
        super(raider, SettlerEntity.class, true, scope);
        this.raider = raider;
    }

    @Override
    protected void findTarget() {
        if (!CivilianSafety.enabled) {
            super.findTarget();
            return;
        }
        target = raider.level().getEntitiesOfClass(SettlerEntity.class,
                getTargetSearchArea(getFollowDistance()),
                candidate -> targetConditions.test(raider, candidate))
            .stream()
            .min(Comparator.comparingDouble(this::effectiveDistance))
            .orElse(null);
    }

    double effectiveDistance(SettlerEntity candidate) {
        return effectiveDistance(Math.sqrt(raider.distanceToSqr(candidate)),
            candidate.getProfession().martial(),
            candidate.getActivity() == SettlerActivity.FLEEING,
            raider.objective());
    }

    /** Pure ranking rule, unit-tested. */
    public static double effectiveDistance(double blocks, boolean defender, boolean fleeing,
                                           RaidObjective objective) {
        // W20 measurement (26 Sep): ranking defenders first made all five
        // raiders converge on the two Guards, who died, and the band then
        // killed 8/8 civilians (1/8 with plain nearest-first). A Blood band
        // therefore keeps plain nearest-first; only Grain and Fire bands,
        // which did not come for people, let a fleeing civilian go.
        if (fleeing && !defender && objective != RaidObjective.BLOD) {
            return blocks + FLEEING_PENALTY_BLOCKS_OTHER;
        }
        return blocks;
    }
}
