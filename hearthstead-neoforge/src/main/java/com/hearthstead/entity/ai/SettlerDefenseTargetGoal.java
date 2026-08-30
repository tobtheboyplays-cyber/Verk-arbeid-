package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

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
 * into three tiers: (1) a raider whose OWN
 * {@code getTarget()} is a live {@link SettlerEntity} -- it is actively
 * hunting or fighting one of ours right now; (2) failing that, one whose
 * target is the {@link Player}; (3) failing that, simply the nearest hostile
 * in range, exactly the old behaviour. "Currently attacking" is read off the
 * candidate's own live AI target rather than a swing timer or animation
 * state -- the same signal {@link RaiderBreachGoal#destinationFor} already
 * treats as ground truth for "what this raider is doing right now", so a
 * guard and a raider's own goals never disagree about who is under attack.
 * Within those tiers, guards use the live assignments of the same loaded
 * garrison to cover unclaimed enemies before piling onto an ordinary one.
 * A threat attacking a person has room for two defenders and a raid captain
 * for three; if every threat is already covered, nobody idles -- the least
 * loaded suitable target wins. This makes a two-guard demo read as a team,
 * while retaining deliberate focus fire on the threats that justify it.
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
 * {@code Mob#setTarget} when a higher-tier threat or meaningfully less-covered
 * assignment appears -- so a guard genuinely abandons a distant, harmless
 * enemy to intercept one standing over a civilian, while stable equal choices
 * do not oscillate every review.
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
            if (target instanceof RaiderEntity raider
                && raider.settlementId() != null
                && !s.id.equals(raider.settlementId())) {
                // A neighbouring settlement's raid is not ours. Guard melee
                // already rejects it at contact; acquisition must reject it
                // too so Guards do not chase and Archers cannot shoot it.
                return false;
            }
            double range = s.radius + 8;
            return target.blockPosition().distSqr(s.center) <= range * range;
        });
        this.settler = settler;
    }

    @Override
    public boolean canUse() {
        Profession profession = settler.getProfession();
        return profession.martial()
            && settler.level() instanceof ServerLevel level
            && EquipmentRequests.readyForProfession(level, settler, profession)
            && super.canUse();
    }

    @Override
    public void start() {
        super.start();
        reevaluateTimer = REEVALUATE_INTERVAL;
    }

    @Override
    public boolean canContinueToUse() {
        Profession profession = settler.getProfession();
        if (!profession.martial()
            || !(settler.level() instanceof ServerLevel level)
            || !EquipmentRequests.readyForProfession(level, settler,
                profession)) {
            return false;
        }
        if (--reevaluateTimer <= 0) {
            reevaluateTimer = REEVALUATE_INTERVAL;
            findTarget();
            if (target == null) {
                // TargetGoal#canContinueToUse does not re-run this subclass'
                // settlement/foreign-raider predicate. Clear stale authority
                // explicitly so a Guard cannot remain combat-locked and an
                // Archer cannot keep firing at a target this coordinator now
                // rejects.
                mob.setTarget(null);
                return false;
            }
            if (target != mob.getTarget()) {
                // A strictly higher tier appeared (or the old target
                // stopped qualifying) -- switch now rather than finishing
                // the old engagement. See the class doc's second section.
                mob.setTarget(target);
            }
        }
        return super.canContinueToUse();
    }

    /**
     * Three urgency tiers with bounded garrison load-balancing -- see the
     * class doc. Every
     * candidate still passes the exact same {@link #targetConditions} the
     * plain nearest-search would have (range, line of sight, the
     * settlement-radius predicate, alive/attackable/not-allied): this only
     * changes which of the entities that already qualify gets picked.
     */
    @Override
    protected void findTarget() {
        List<Monster> candidates = mob.level().getEntitiesOfClass(targetType,
            getTargetSearchArea(getFollowDistance()), c -> true);
        Settlement settlement = settler.settlement();
        if (settlement == null || !(settler.level() instanceof ServerLevel level)) {
            target = null;
            return;
        }
        // The settlement roster is already bounded and authoritative. This
        // runs only on the existing 10-tick acquisition/re-evaluation cadence,
        // never every render or AI tick and never as a global entity scan.
        List<SettlerEntity> garrison = SettlementManager.loadedMembers(level,
            settlement).stream().filter(member -> member.isAlive()
                && member.getProfession().martial()).toList();

        Monster bestAvailable = null;
        Monster bestFallback = null;
        for (Monster candidate : candidates) {
            if (!targetConditions.test(mob, candidate)) {
                continue;
            }
            int load = assignedDefenders(garrison, candidate);
            int capacity = defenderCapacity(candidate);
            if (load < capacity
                && better(candidate, load, capacity, bestAvailable,
                    bestAvailable == null ? 0
                        : assignedDefenders(garrison, bestAvailable),
                    bestAvailable == null ? 1
                        : defenderCapacity(bestAvailable))) {
                bestAvailable = candidate;
            }
            if (better(candidate, load, capacity, bestFallback,
                bestFallback == null ? 0
                    : assignedDefenders(garrison, bestFallback),
                bestFallback == null ? 1
                    : defenderCapacity(bestFallback))) {
                bestFallback = candidate;
            }
        }
        target = bestAvailable != null ? bestAvailable : bestFallback;
    }

    /**
     * Shared bounded acquisition seam for the Archer combat goal. The entity's
     * target selector normally owns acquisition; if goal-selector ordering or
     * a direct GameTest asks the Archer for a fallback first, this runs the
     * exact same urgency, coverage, role-fit and stability algorithm instead
     * of a private nearest-hostile scan.
     */
    @Nullable
    Monster acquireNow() {
        findTarget();
        return target instanceof Monster monster ? monster : null;
    }

    /**
     * Cheap fail-closed continuation predicate shared with the Archer goal.
     * Unlike {@link #acquireNow()}, this performs no entity scan: it only
     * revalidates one existing target against the same authoritative
     * settlement, range, hostility and line-of-sight conditions.
     */
    boolean accepts(@Nullable LivingEntity value) {
        return value instanceof Monster monster
            && targetConditions.test(mob, monster);
    }

    /** Number of OTHER defenders already committed to this exact target. */
    private int assignedDefenders(List<SettlerEntity> garrison,
                                  Monster candidate) {
        int count = 0;
        for (SettlerEntity member : garrison) {
            // Excluding self makes keeping the current target count as the
            // same projected assignment as selecting it for the first time.
            if (member != settler && member.getTarget() == candidate) {
                count++;
            }
        }
        return count;
    }

    /**
     * Visible focus-fire budget. Ordinary enemies should be covered one each;
     * a live attacker earns a second defender and a named captain can hold
     * three. If these are all full, {@link #findTarget()} still chooses a
     * fallback, so extra guards never stand idle beside a lone enemy.
     */
    static int defenderCapacity(Monster candidate) {
        int capacity = candidate instanceof RaiderEntity raider
            && raider.isCaptain() ? 3 : 1;
        LivingEntity victim = candidate instanceof Mob m ? m.getTarget() : null;
        if (victim instanceof SettlerEntity || victim instanceof Player) {
            capacity = Math.max(capacity, 2);
        }
        return capacity;
    }

    private boolean better(Monster candidate, int load, int capacity,
                           Monster incumbent, int incumbentLoad,
                           int incumbentCapacity) {
        if (incumbent == null) {
            return true;
        }
        int urgency = urgency(candidate);
        int incumbentUrgency = urgency(incumbent);
        if (urgency != incumbentUrgency) {
            return urgency < incumbentUrgency;
        }
        // Compare load/capacity without floating point drift. A captain with
        // one defender is less covered than an ordinary raider with one.
        int loadComparison = Integer.compare(load * incumbentCapacity,
            incumbentLoad * capacity);
        if (loadComparison != 0) {
            return loadComparison < 0;
        }
        int roleFit = roleMismatch(candidate);
        int incumbentRoleFit = roleMismatch(incumbent);
        if (roleFit != incumbentRoleFit) {
            return roleFit < incumbentRoleFit;
        }
        // Stability prevents two equally good assignments swapping every
        // review. This is also the clean hook for future formation commands.
        boolean current = mob.getTarget() == candidate;
        boolean incumbentCurrent = mob.getTarget() == incumbent;
        if (current != incumbentCurrent) {
            return current;
        }
        int distance = Double.compare(mob.distanceToSqr(candidate),
            mob.distanceToSqr(incumbent));
        if (distance != 0) {
            return distance < 0;
        }
        return compareUuid(candidate.getUUID(), incumbent.getUUID()) < 0;
    }

    private static int urgency(Monster candidate) {
        LivingEntity victim = candidate instanceof Mob m ? m.getTarget() : null;
        if (victim instanceof SettlerEntity) {
            return 0;
        }
        if (victim instanceof Player) {
            return 1;
        }
        return 2;
    }

    /**
     * A target-choice hook for the later counter system, without inventing
     * damage bonuses in this P0 patch: melee guards prefer the heavy that can
     * breach the line; archers prefer exposed skirmishers. Urgency and cover
     * always outrank this tie-breaker.
     */
    private int roleMismatch(Monster candidate) {
        if (!(candidate instanceof RaiderEntity raider)) {
            return 0;
        }
        if (raider.isCaptain()) {
            return 0; // captain is a neutral high-capacity threat, not a counter
        }
        return switch (settler.getProfession()) {
            case GUARD -> raider.variant() == RaiderEntity.Variant.BRUTE ? 0 : 1;
            case ARCHER -> raider.variant() == RaiderEntity.Variant.SKIRMISHER ? 0 : 1;
            default -> 0;
        };
    }

    private static int compareUuid(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(),
            right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(
            left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
