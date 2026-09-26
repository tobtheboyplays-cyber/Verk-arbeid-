package com.hearthstead.entity.combat.role;

import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared two-phase melee driver for the Spearman and the Longswordsman.
 *
 * <p>Same contract as the Guard's {@code GuardMeleeGoal}, in its own file so
 * the combat lane's goal is never touched: starting a move broadcasts its
 * clip and locks the swing direction; damage lands only on the move's
 * contact tick(s), only if the same target is still an authorized enemy, in
 * the move's reach and inside the locked arc. Each contact tick is consumed
 * BEFORE damage is applied, so a retry or re-entrant call can never land it
 * twice. Losing any condition is a visible miss.
 */
public abstract class RoleMeleeGoal extends Goal {
    protected final SettlerEntity settler;
    private final Profession profession;

    // ----- the one pending move -----------------------------------------
    @Nullable private RoleMove pending;
    private long pendingStart = Long.MIN_VALUE;
    private float pendingYaw;
    @Nullable private UUID pendingTarget;
    private int[] contacts = new int[0];
    private int nextContact;
    private long nextOpener = Long.MIN_VALUE;
    private long repathAt = Long.MIN_VALUE;

    // ----- deterministic test seam + read-only evidence -----------------
    @Nullable private RoleMove forcedNext;
    private int landedContacts;
    @Nullable private RoleMove lastResolved;
    private final List<UUID> lastSwingHits = new ArrayList<>();
    private final Map<UUID, Integer> hitsByTarget = new HashMap<>();

    protected RoleMeleeGoal(SettlerEntity settler, Profession profession) {
        this.settler = settler;
        this.profession = profession;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    protected boolean ready() {
        return RoleCombat.enabled()
            && settler.getProfession() == profession
            && settler.level() instanceof ServerLevel level
            && EquipmentRequests.readyForProfession(level, settler, profession)
            && holdsWeapon();
    }

    /** The physical weapon this role needs in the main hand. */
    protected abstract boolean holdsWeapon();

    @Override
    public boolean canUse() {
        return ready() && RoleCombat.isAuthorizedHostile(settler, settler.getTarget());
    }

    @Override
    public boolean canContinueToUse() {
        return ready() && (pending != null
            || RoleCombat.isAuthorizedHostile(settler, settler.getTarget()));
    }

    @Override
    public void start() {
        settler.setActivity(SettlerActivity.COMBAT);
    }

    @Override
    public void stop() {
        pending = null;
        pendingTarget = null;
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.IDLE);
    }

    @Override
    public void tick() {
        if (!(settler.level() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getGameTime();
        if (settler.isCombatStaggered()) {
            pending = null;
            settler.getNavigation().stop();
            return;
        }
        preTick(level, now);
        LivingEntity target = settler.getTarget();
        if (pending != null) {
            resolve(level, now, target);
            return;
        }
        if (target == null || !RoleCombat.isAuthorizedHostile(settler, target)) {
            return;
        }
        settler.getLookControl().setLookAt(target, 30.0F, 30.0F);
        double distSqr = RoleCombat.horizontalDistSqr(settler, target);
        double reach = RoleCombatRules.reach(settler.getBbWidth(), target.getBbWidth(),
            engageReachScale());
        if (holdGround(level, now)) {
            settler.getNavigation().stop();
        } else if (distSqr > reach * reach * 0.72D) {
            if (now >= repathAt) {
                settler.getNavigation().moveTo(target, 1.15D);
                repathAt = now + 10;
            }
        } else {
            settler.getNavigation().stop();
        }
        if (now < nextOpener || !settler.getSensing().hasLineOfSight(target)) {
            return;
        }
        RoleMove move = forcedNext != null ? forcedNext : choose(level, target, now);
        if (move == null || !inReach(move, target)) {
            return;
        }
        begin(level, move, target, now);
        forcedNext = null;
    }

    private void begin(ServerLevel level, RoleMove move, LivingEntity target, long now) {
        float yaw = RoleCombat.yawTo(settler, target);
        settler.setYRot(yaw);
        settler.setYHeadRot(yaw);
        settler.yBodyRot = yaw;
        pending = move;
        pendingStart = now;
        pendingYaw = yaw;
        pendingTarget = target.getUUID();
        contacts = move.contactTicks();
        nextContact = 0;
        // Dexterity: quicker recovery between swings (plan/ATTRIBUTES.md).
        nextOpener = now + com.hearthstead.entity.AttributeRuntime.meleeCadence(settler, move.cadenceTicks());
        // The move's authored role clip (plan/BATTLE-ROLES.md §6; RoleMotionAnimations).
        level.broadcastEntityEvent(settler, move.clipEvent());
        onBegin(level, move, target, now);
    }

    private void resolve(ServerLevel level, long now, @Nullable LivingEntity target) {
        RoleMove move = pending;
        long due = pendingStart + contacts[nextContact];
        if (now < due) {
            if (now == due - 1) {
                playWhoosh(level, move);
            }
            return;
        }
        // Consume this contact before any damage is attempted.
        int index = nextContact++;
        if (nextContact >= contacts.length) {
            pending = null;
        }
        lastResolved = move;
        if (target == null || !target.getUUID().equals(pendingTarget)
            || !RoleCombat.isAuthorizedHostile(settler, target)
            || !inReach(move, target)
            || !RoleCombatRules.inArc(pendingYaw, RoleCombat.yawTo(settler, target),
                move.arcHalfDegrees())) {
            if (index == 0) {
                lastSwingHits.clear();
            }
            pending = null;
            return;
        }
        if (index == 0) {
            lastSwingHits.clear();
        }
        List<LivingEntity> hits = contact(level, move, target, pendingYaw, index, now);
        for (LivingEntity hit : hits) {
            landedContacts++;
            lastSwingHits.add(hit.getUUID());
            hitsByTarget.merge(hit.getUUID(), 1, Integer::sum);
        }
    }

    protected boolean inReach(RoleMove move, LivingEntity target) {
        return RoleCombatRules.inReach(RoleCombat.horizontalDistSqr(settler, target),
            settler.getBbWidth(), target.getBbWidth(), move.reachScale());
    }

    protected int rankOrdinal() {
        return GuardRank.of(settler).ordinal();
    }

    // ------------------------------------------------------ role hooks ---

    /** Per-tick bookkeeping before targeting (brace scan, etc.). */
    protected void preTick(ServerLevel level, long now) {
    }

    /** Stand still instead of chasing (a braced spearman). */
    protected boolean holdGround(ServerLevel level, long now) {
        return false;
    }

    /** Reach multiple used to decide when to stop closing in. */
    protected abstract double engageReachScale();

    @Nullable
    protected abstract RoleMove choose(ServerLevel level, LivingEntity target, long now);

    protected void onBegin(ServerLevel level, RoleMove move, LivingEntity target, long now) {
    }

    /** Applies one contact; returns every entity that actually took damage. */
    protected abstract List<LivingEntity> contact(ServerLevel level, RoleMove move,
                                                  LivingEntity target, float swingYaw,
                                                  int contactIndex, long now);

    protected abstract void playWhoosh(ServerLevel level, RoleMove move);

    // --------------------------------------------- test seams / evidence ---

    public void forceNextMove(@Nullable RoleMove move) {
        this.forcedNext = move;
        this.nextOpener = Long.MIN_VALUE;
    }

    @Nullable
    public RoleMove pendingMove() {
        return pending;
    }

    @Nullable
    public RoleMove lastResolvedMove() {
        return lastResolved;
    }

    public int landedContacts() {
        return landedContacts;
    }

    public List<UUID> lastSwingHits() {
        return List.copyOf(lastSwingHits);
    }

    public int hitsOn(UUID target) {
        return hitsByTarget.getOrDefault(target, 0);
    }
}
