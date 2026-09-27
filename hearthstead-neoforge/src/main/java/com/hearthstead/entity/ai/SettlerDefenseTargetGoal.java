package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.raid.RaidThreatBoard;
import com.hearthstead.settlement.state.GuardOrder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
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
    /** Same authority/range contract as initial acquisition, but without the
     * line-of-sight requirement. It is used only for a target that was
     * already acquired visibly, so pathing goals get vanilla's bounded
     * unseen-memory window to move around cover rather than losing the target
     * on the first blocked tick. */
    private final TargetingConditions pursuitConditions;
    private int reevaluateTimer;

    public SettlerDefenseTargetGoal(SettlerEntity settler) {
        super(settler, Monster.class, 10, true, false,
            target -> withinDefendedSettlement(settler, target));
        this.settler = settler;
        this.pursuitConditions = TargetingConditions.forCombat()
            .range(getFollowDistance())
            .ignoreLineOfSight()
            .selector(target -> withinDefendedSettlement(settler, target));
    }

    private static boolean withinDefendedSettlement(SettlerEntity settler,
                                                     LivingEntity target) {
        if (com.hearthstead.settlement.guard.BannerTeams.active(settler) != null)
            return com.hearthstead.settlement.guard.BannerTeams.allowsTarget(settler, target);
        Settlement settlement = settler.settlement();
        if (settlement == null) {
            return false;
        }
        if (target instanceof RaiderEntity raider
            && raider.settlementId() != null
            && !settlement.id.equals(raider.settlementId())) {
            // A neighbouring settlement's raid is not ours. Guard melee
            // already rejects it at contact; acquisition and pursuit must
            // reject it too so Guards do not chase and Archers cannot shoot.
            return false;
        }
        double range = settlement.radius + 8;
        if (target instanceof RaiderEntity raider && raider.lootCount() > 0) {
            // A raider carrying loot only counts as escaped at
            // RaiderLootGoal.ESCAPE_MARGIN past the edge; keep chasing it that
            // far, or the band between the two rings is a free getaway.
            range = settlement.radius + RaiderLootGoal.ESCAPE_MARGIN;
        }
        return target.blockPosition().distSqr(settlement.center) <= range * range;
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
        if (settler.level() instanceof ServerLevel level) {
            RaidThreatBoard.claim(level, settler.settlement(), settler,
                mob.getTarget() instanceof Monster monster ? monster : null);
            raiseRaidSightingAlarm(level, mob.getTarget());
        }
        reevaluateTimer = REEVALUATE_INTERVAL;
    }

    /**
     * Civilian-safety pass (26 Sep): a defender sighting a raider of this
     * settlement's own war raises the ALARM at once, so the village shelters
     * when the first Guard or Archer sees the band -- not only after a
     * civilian is hurt or reaches a Guard. Shared by initial acquisition and
     * by re-evaluation (a Guard busy with a zombie that switches to a raider
     * must also sound it). Raised once: an active ALARM is left alone.
     */
    private void raiseRaidSightingAlarm(ServerLevel level, @Nullable LivingEntity sighted) {
        Settlement settlement = settler.settlement();
        if (CivilianSafety.enabled && settlement != null
            && sighted instanceof RaiderEntity raider
            && settlement.id.equals(raider.settlementId())
            && !raider.isGoblinThiefDemo() && !raider.isScout()
            && !settlement.alertActive(level.getGameTime())) {
            com.hearthstead.settlement.SettlementManager.raiseAlert(level, settlement,
                raider.blockPosition());
        }
    }

    @Override
    public void stop() {
        if (settler.level() instanceof ServerLevel level) {
            RaidThreatBoard.release(level, settler.settlement(), settler);
        }
        super.stop();
    }

    @Override
    public boolean canContinueToUse() {
        if (com.hearthstead.settlement.guard.BannerTeams.active(settler) != null
            && !com.hearthstead.settlement.guard.BannerTeams.allowsTarget(settler, mob.getTarget())) {
            mob.setTarget(null);
            return false;
        }
        Profession profession = settler.getProfession();
        if (!profession.martial()
            || !(settler.level() instanceof ServerLevel level)
            || !EquipmentRequests.readyForProfession(level, settler,
                profession)) {
            return false;
        }
        if (--reevaluateTimer <= 0) {
            reevaluateTimer = REEVALUATE_INTERVAL;
            LivingEntity incumbent = mob.getTarget();
            findTarget();
            if (target == null) {
                if (acceptsForPursuit(incumbent)) {
                    // No NEW visible target outranks the incumbent. Preserve
                    // the already-seen target and let TargetGoal's bounded
                    // unseen-memory timer decide how long pursuit may last.
                    // This does not grant wall vision to initial acquisition.
                    target = (Monster) incumbent;
                } else {
                    // TargetGoal#canContinueToUse does not re-run this
                    // subclass' settlement/foreign-raider predicate. Clear
                    // stale authority explicitly so a Guard cannot remain
                    // combat-locked and an Archer cannot keep firing at it.
                    mob.setTarget(null);
                    return false;
                }
            }
            if (target != mob.getTarget()) {
                // A strictly higher tier appeared (or the old target
                // stopped qualifying) -- switch now rather than finishing
                // the old engagement. See the class doc's second section.
                mob.setTarget(target);
                raiseRaidSightingAlarm(level, target);
            }
            RaidThreatBoard.claim(level, settler.settlement(), settler,
                target instanceof Monster monster ? monster : null);
        }
        return super.canContinueToUse();
    }

    /**
     * Three urgency tiers with bounded garrison load-balancing -- see the
     * class doc. Every
     * ordinary candidate still passes the exact same {@link #targetConditions}
     * the plain nearest-search would have (range, line of sight, the
     * settlement-radius predicate, alive/attackable/not-allied). The one
     * narrow exception is a sealed active-raid participant assigned to a
     * Guard: it may be acquired through temporary building cover so the
     * Guard can physically route to a breach. Combat contact still has its
     * own fresh range and line-of-sight gate.
     */
    @Override
    protected void findTarget() {
        Settlement settlement = settler.settlement();
        if (settlement == null || !(settler.level() instanceof ServerLevel level)) {
            target = null;
            return;
        }
        var banner = com.hearthstead.settlement.guard.BannerTeams.active(settler);
        if (banner != null && com.hearthstead.settlement.guard.FieldOrders.engaging(settler)) {
            // A focus order whose mark fell mid-raid: keep attacking, and let the
            // coordinator (protect-civilians-first, load balanced) pick the next raider.
            Monster assigned = RaidThreatBoard.assignedTarget(level, settlement, settler);
            if (assigned != null && targetConditions.test(mob, assigned)) {
                target = assigned;
                return;
            }
        }
        if (banner != null) {
            target = level.getEntitiesOfClass(Monster.class, settler.getBoundingBox().inflate(getFollowDistance()),
                enemy -> targetConditions.test(mob, enemy)).stream()
                .min(java.util.Comparator.<Monster>comparingInt(enemy -> enemy.getUUID().equals(banner.enemy()) ? 0 : 1)
                    .thenComparingDouble(settler::distanceToSqr)).orElse(null);
            return;
        }
        Monster assigned = RaidThreatBoard.assignedTarget(level, settlement,
            settler);
        target = assigned != null && (targetConditions.test(mob, assigned)
            || RaidThreatBoard.mayAcquireSealedRaidThreatThroughCover(level,
                settlement, settler, assigned)) ? assigned : null;
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
        Monster acquired = target instanceof Monster monster ? monster : null;
        if (settler.level() instanceof ServerLevel level) {
            RaidThreatBoard.claim(level, settler.settlement(), settler, acquired);
        }
        return acquired;
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

    /**
     * Revalidates an already-visible target for bounded movement around
     * cover. Unlike {@link #accepts(LivingEntity)}, this deliberately ignores
     * line of sight; all combat, alliance, range and settlement authority
     * checks remain active through {@link #pursuitConditions}.
     */
    boolean acceptsForPursuit(@Nullable LivingEntity value) {
        Settlement settlement = settler.settlement();
        return settlement != null && value instanceof Monster monster
            && pursuitConditions.test(mob, monster)
            && withinAuthoredLeash(settlement, monster);
    }

    /** Number of OTHER defenders already committed to this exact target. */
    private int assignedDefenders(ServerLevel level, Settlement settlement,
                                  Monster candidate) {
        return RaidThreatBoard.load(level, settlement, candidate, settler);
    }

    /**
     * Visible focus-fire budget. Ordinary enemies should be covered one each;
     * a live attacker earns a second defender and a named captain can hold
     * three. If these are all full, {@link #findTarget()} still chooses a
     * fallback, so extra guards never stand idle beside a lone enemy.
     */
    private int defenderCapacity(Monster candidate) {
        int capacity;
        if (candidate instanceof RaiderEntity raider) {
            capacity = raider.isCaptain()
                ? 1 : raider.variant() == RaiderEntity.Variant.BRUTE ? 2 : 1;
        } else {
            capacity = 1;
        }
        // Ranged claims have their own cap: two on a captain, one otherwise.
        if (settler.getProfession() == Profession.ARCHER) {
            capacity = candidate instanceof RaiderEntity raider
                && raider.isCaptain() ? 2 : 1;
        }
        LivingEntity victim = candidate instanceof Mob m ? m.getTarget() : null;
        if (victim instanceof SettlerEntity || victim instanceof Player) {
            capacity++;
        }
        return capacity;
    }

    private boolean withinAuthoredLeash(Settlement settlement,
                                        Monster candidate) {
        if (com.hearthstead.settlement.guard.BannerTeams.active(settler) != null)
            return com.hearthstead.settlement.guard.BannerTeams.allowsTarget(settler, candidate);
        if (settler.level() instanceof ServerLevel level
            && ArcherTowerPost.coversVisibleTarget(level, settlement, settler, candidate)) {
            return true;
        }
        GuardOrder order = settlement.guardOrders.order(settler.getUUID())
            .orElse(null);
        if (order == null || !order.activeAt(settler.level().getGameTime())) {
            return true;
        }
        java.util.List<net.minecraft.core.BlockPos> anchors =
            order.mode() == GuardOrder.Mode.PATROL_ROUTE
                ? order.patrolPoints()
                : order.pos().map(java.util.List::of).orElse(java.util.List.of());
        if (anchors.isEmpty()) {
            return false;
        }
        double nearest = anchors.stream().mapToDouble(anchor ->
            candidate.blockPosition().distSqr(anchor)).min()
            .orElse(Double.POSITIVE_INFINITY);
        double leash = order.leashRadius();
        if (settler.level() instanceof ServerLevel level
            && RaidThreatBoard.rangedReachAllows(level, settlement, settler,
                candidate, nearest)) {
            return true; // the leash bounds an Archer's feet, not his bow
        }
        LivingEntity victim = candidate instanceof Mob mob ? mob.getTarget() : null;
        boolean urgent = victim == settler
            || victim instanceof Player
            || victim instanceof SettlerEntity other
                && other.settlement() != null
                && settlement.id.equals(other.settlement().id);
        return RaidThreatBoard.leashAllows(nearest, leash, urgent);
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
