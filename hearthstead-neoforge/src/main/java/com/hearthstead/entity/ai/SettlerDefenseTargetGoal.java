package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * Guards target hostiles that intrude on the settlement -- and, among every
 * hostile currently in range, protect-civilians-first (DESIGN.md system 5 /
 * the plan's R19): a raider standing over a settler outranks one merely
 * standing near the wall, and one attacking the player outranks any other
 * that is not actively hurting anybody.
 *
 * <h2>What "protect-civilians-first" means here, concretely</h2>
 *
 * <p>{@link #findTarget()} sorts every in-range, attackable {@link Monster}
 * into three tiers, nearest-first within each: (1) a raider whose OWN
 * {@code getTarget()} is a live {@link SettlerEntity} -- it is actively
 * hunting or fighting one of ours right now; (2) failing that, one whose
 * target is the {@link Player}; (3) failing that, simply the nearest hostile
 * in range, exactly the old behaviour. "Currently attacking" is read off the
 * candidate's own live AI target rather than a swing timer or animation
 * state -- the same signal {@link RaiderBreachGoal#destinationFor} already
 * treats as ground truth for "what this raider is doing right now", so a
 * guard and a raider's own goals never disagree about who is under attack.
 *
 * <h2>Making the preference visible, not just the initial pick</h2>
 *
 * <p>A priority that only applies at the MOMENT a guard first acquires a
 * target is not protect-civilians-first, it is protect-civilians-first-by-
 * coincidence: a guard already mid-fight with the nearest raider would
 * finish that fight to the end even if a second raider started mauling a
 * settler three blocks away, because vanilla's own {@code TargetGoal}
 * machinery only ever calls {@link #findTarget()} once, from {@code canUse()},
 * before the goal starts running. {@link #canContinueToUse()} is overridden
 * to re-run {@link #findTarget()} on a cheap cooldown
 * ({@link #REEVALUATE_INTERVAL}) even WHILE already engaged, and to call
 * {@code Mob#setTarget} the instant a strictly higher-tier threat appears --
 * so a guard genuinely abandons a distant, harmless enemy to intercept one
 * standing over a civilian, mid-fight, the way the task asks for it to read.
 * {@code GuardMeleeGoal}/vanilla {@code MeleeAttackGoal} need no change to
 * follow along: {@code MeleeAttackGoal#tick} already re-reads
 * {@code mob.getTarget()} fresh every tick rather than caching it at start,
 * so the moment this goal calls {@code setTarget}, the very next melee tick
 * paths and swings at the new target on its own.
 *
 * <h2>Bounded means bounded</h2>
 *
 * <p>{@link #REEVALUATE_INTERVAL} throttles the extra work: one bounded box
 * query (identical to the one {@code canUse()} already performs once per
 * acquisition) every {@value #REEVALUATE_INTERVAL} ticks per currently-
 * fighting guard, the same order of magnitude vanilla's own default
 * {@code randomInterval} (10) already costs for a goal that has not yet
 * started -- this does not add a new, wider, or per-tick scan, it only lets
 * the existing one keep running a little longer than vanilla would.
 */
public class SettlerDefenseTargetGoal extends NearestAttackableTargetGoal<Monster> {
    /** How often an already-engaged guard re-checks for a higher-priority
     * threat. See the class doc's "bounded means bounded" section for why
     * this number, not zero (every tick) and not never (vanilla default). */
    private static final int REEVALUATE_INTERVAL = 10;

    private final SettlerEntity settler;
    private int reevaluateTimer;

    public SettlerDefenseTargetGoal(SettlerEntity settler) {
        super(settler, Monster.class, 10, true, false, target -> {
            Settlement s = settler.settlement();
            if (s == null) {
                return false;
            }
            double range = s.radius + 8;
            return target.blockPosition().distSqr(s.center) <= range * range;
        });
        this.settler = settler;
    }

    @Override
    public boolean canUse() {
        return settler.getProfession().martial() && super.canUse();
    }

    @Override
    public void start() {
        super.start();
        reevaluateTimer = REEVALUATE_INTERVAL;
    }

    @Override
    public boolean canContinueToUse() {
        if (--reevaluateTimer <= 0) {
            reevaluateTimer = REEVALUATE_INTERVAL;
            findTarget();
            if (target != null && target != mob.getTarget()) {
                // A strictly higher tier appeared (or the old target
                // stopped qualifying) -- switch now rather than finishing
                // the old engagement. See the class doc's second section.
                mob.setTarget(target);
            }
        }
        return super.canContinueToUse();
    }

    /**
     * Three tiers, nearest-first within each -- see the class doc. Every
     * candidate still passes the exact same {@link #targetConditions} the
     * plain nearest-search would have (range, line of sight, the
     * settlement-radius predicate, alive/attackable/not-allied): this only
     * changes which of the entities that already qualify gets picked.
     */
    @Override
    protected void findTarget() {
        List<Monster> candidates = mob.level().getEntitiesOfClass(targetType,
            getTargetSearchArea(getFollowDistance()), c -> true);

        LivingEntity bestAttackingSettler = null;
        double bestAttackingSettlerDistSqr = Double.MAX_VALUE;
        LivingEntity bestAttackingPlayer = null;
        double bestAttackingPlayerDistSqr = Double.MAX_VALUE;
        LivingEntity bestOverall = null;
        double bestOverallDistSqr = Double.MAX_VALUE;

        for (Monster candidate : candidates) {
            if (!targetConditions.test(mob, candidate)) {
                continue;
            }
            double distSqr = mob.distanceToSqr(candidate);
            if (distSqr < bestOverallDistSqr) {
                bestOverallDistSqr = distSqr;
                bestOverall = candidate;
            }
            LivingEntity theirTarget = candidate instanceof Mob m ? m.getTarget() : null;
            if (theirTarget instanceof SettlerEntity) {
                if (distSqr < bestAttackingSettlerDistSqr) {
                    bestAttackingSettlerDistSqr = distSqr;
                    bestAttackingSettler = candidate;
                }
            } else if (theirTarget instanceof Player) {
                if (distSqr < bestAttackingPlayerDistSqr) {
                    bestAttackingPlayerDistSqr = distSqr;
                    bestAttackingPlayer = candidate;
                }
            }
        }

        target = bestAttackingSettler != null ? bestAttackingSettler
            : bestAttackingPlayer != null ? bestAttackingPlayer
            : bestOverall;
    }
}
