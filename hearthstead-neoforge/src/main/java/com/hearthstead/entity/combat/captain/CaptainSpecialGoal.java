package com.hearthstead.entity.combat.captain;

import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GuardMeleeGoal;
import com.hearthstead.entity.combat.role.RoleCombat;
import com.hearthstead.entity.combat.role.RoleCombatRules;
import com.hearthstead.entity.combat.role.RuneMageBrain;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The hero Captain's special attacks (plan/CAPTAIN.md). Every few ticks the
 * pure {@link CaptainBrain} reads the fight; a chosen special telegraphs for
 * its wind-up (fx/sound hooks, fallback clip), then resolves ONCE, then starts
 * its cooldown. Every hit goes through {@link RoleCombat#isAuthorizedHostile}:
 * no special ever touches a settler, a player, a pet or a bystander.
 * Being staggered during the wind-up breaks the special (cooldown spent).
 */
public class CaptainSpecialGoal extends Goal {
    private static final double SCAN = 24.0D;
    private static final int THINK_TICKS = 5;

    private final SettlerEntity captain;
    @Nullable private CaptainSpecial active;
    @Nullable private UUID targetId;
    private int age;
    private boolean resolved;
    private boolean done;
    private float lockedYaw;
    @Nullable private Vec3 aim;
    private final Set<UUID> hitThisPhase = new HashSet<>();
    private long nextThink = Long.MIN_VALUE;

    // ----- deterministic seam + evidence ---------------------------------
    @Nullable private CaptainSpecial forced;
    private final List<LivingEntity> lastHits = new ArrayList<>();

    public CaptainSpecialGoal(SettlerEntity captain) {
        this.captain = captain;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private boolean able() {
        return captain.level() instanceof ServerLevel && CaptainStatus.isHero(captain)
            && !captain.isCombatStaggered() && CaptainKit.heldLoadout(captain) != null;
    }

    @Override
    public boolean canUse() {
        if (!able()) {
            return false;
        }
        ServerLevel level = (ServerLevel) captain.level();
        long now = level.getGameTime();
        CaptainState cs = CaptainWorld.stateOf(captain);
        if (forced != null) {
            if (!cs.ready(forced, now)) {
                forced = null;
                return false;
            }
            return choose(level, cs, forced, now);
        }
        if (now < nextThink) {
            return false;
        }
        nextThink = now + THINK_TICKS;
        List<LivingEntity> foes = enemies(level, SCAN);
        if (foes.isEmpty()) {
            return false;
        }
        cs.sawEnemy(now);
        CaptainWorld.save(captain, cs);
        LivingEntity target = target(foes);
        CaptainSpecial sp = CaptainBrain.choose(cs.loadout(), situation(level, cs, foes, target, now),
            s -> cs.ready(s, now));
        return sp != null && choose(level, cs, sp, now);
    }

    private boolean choose(ServerLevel level, CaptainState cs, CaptainSpecial sp, long now) {
        List<LivingEntity> foes = enemies(level, SCAN);
        LivingEntity target = target(foes);
        active = sp;
        targetId = target == null ? null : target.getUUID();
        aim = target == null ? captain.position() : target.position();
        if (sp == CaptainSpecial.ARROW_VOLLEY) {
            List<double[]> pts = new ArrayList<>();
            for (LivingEntity f : foes) {
                pts.add(new double[] {f.getX(), f.getY(), f.getZ()});
            }
            RuneMageBrain.Aim a = RuneMageBrain.bestCluster(pts, CaptainSpecial.VOLLEY_RADIUS);
            if (a != null) {
                aim = new Vec3(a.x(), a.y(), a.z());
            }
        }
        lockedYaw = target == null ? captain.getYRot() : RoleCombat.yawTo(captain, target);
        age = 0;
        resolved = false;
        done = false;
        forced = null;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !done && active != null && captain.level() instanceof ServerLevel
            && CaptainStatus.isHero(captain) && !captain.isCombatStaggered();
    }

    @Override
    public void start() {
        captain.setActivity(SettlerActivity.COMBAT);
        captain.getNavigation().stop();
        if (captain.level() instanceof ServerLevel level && active != null) {
            level.broadcastEntityEvent(captain, CaptainClips.startEvent(active));
        }
    }

    @Override
    public void stop() {
        if (active != null && !resolved && captain.level() instanceof ServerLevel level) {
            // Broken wind-up: the moment is lost, the cooldown is spent.
            CaptainState cs = CaptainWorld.stateOf(captain);
            cs.fire(active, level.getGameTime());
            CaptainWorld.save(captain, cs);
        }
        active = null;
        done = true;
        captain.setActivity(SettlerActivity.IDLE);
    }

    @Override
    public void tick() {
        ServerLevel level = (ServerLevel) captain.level();
        CaptainSpecial sp = active;
        if (sp == null) {
            done = true;
            return;
        }
        LivingEntity target = targetId == null ? null
            : level.getEntity(targetId) instanceof LivingEntity l ? l : null;
        if (target != null) {
            captain.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
        if (age < sp.windupTicks()) {
            if (sp != CaptainSpecial.SHIELD_CHARGE) {
                captain.getNavigation().stop();
            }
            CaptainFx.windup(level, captain, sp, age);
            age++;
            return;
        }
        int phase = age - sp.windupTicks();
        if (phase == 0) {
            resolved = true;
            CaptainState cs = CaptainWorld.stateOf(captain);
            cs.fire(sp, level.getGameTime());
            CaptainWorld.save(captain, cs);
            lastHits.clear();
            CaptainFx.impact(level, captain, sp, aim == null ? captain.position() : aim);
        }
        boolean finished = resolvePhase(level, sp, target, phase);
        age++;
        if (finished) {
            done = true;
            CaptainNetwork.broadcastState(captain);
        }
    }

    // ------------------------------------------------------------ effects

    /** Applies phase {@code phase} of the special; returns true when the special is over. */
    private boolean resolvePhase(ServerLevel level, CaptainSpecial sp, @Nullable LivingEntity target,
                                 int phase) {
        Settlement settlement = captain.settlement();
        switch (sp) {
            case RALLY_CRY -> {
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                        captain.getBoundingBox().inflate(12.0D, 4.0D, 12.0D))) {
                    boolean soldier = e == captain || e instanceof Player
                        || e instanceof SettlerEntity s && s.getProfession().battlefield();
                    if (soldier && (e == captain || RoleCombat.isAlly(settlement, e))) {
                        e.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, CaptainSpecial.RALLY_TICKS, 0));
                        e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, CaptainSpecial.RALLY_TICKS, 0));
                    }
                }
                return true;
            }
            case SECOND_WIND -> {
                captain.heal(captain.getMaxHealth() * CaptainSpecial.SECOND_WIND_HEAL_SHARE);
                captain.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0));
                return true;
            }
            case EXECUTION -> {
                // Owner rule (finisher lane): only an off-balance target below 10% HP.
                if (valid(target, sp.range())
                        && com.hearthstead.finisher.FinisherHooks.executionAllowed(target)) {
                    hit(level, target, target.getHealth() + 10.0F, true);
                }
                return true;
            }
            case SHIELD_CHARGE -> {
                if (phase < 6) {
                    Vec3 dir = Vec3.directionFromRotation(0.0F, lockedYaw).scale(0.9D);
                    captain.setDeltaMovement(dir.x, captain.getDeltaMovement().y, dir.z);
                    captain.hurtMarked = true;
                    for (LivingEntity e : inLine(level, 1.6D)) {
                        if (hitThisPhase.add(e.getUUID())) {
                            if (hit(level, e, blow(0.5D), false)) {
                                RoleCombat.knockback(captain, e, 1.2D, lockedYaw);
                                RoleCombat.stagger(e, 10);
                            }
                        }
                    }
                    return false;
                }
                hitThisPhase.clear();
                return true;
            }
            case HOLD_THE_LINE -> {
                for (LivingEntity e : enemies(level, sp.range())) {
                    if (e instanceof Mob mob) {
                        mob.setTarget(captain);
                    }
                }
                captain.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, CaptainSpecial.HOLD_TICKS, 1));
                return true;
            }
            case POMMEL_STUN -> {
                if (valid(target, sp.range()) && hit(level, target, blow(0.5D), true)) {
                    RoleCombat.stagger(target, CaptainSpecial.STUN_TICKS);
                }
                return true;
            }
            case BLADE_WHIRL -> {
                if (phase % 4 == 0) {
                    for (LivingEntity e : enemies(level, sp.range())) {
                        hit(level, e, blow(0.5D), true);
                    }
                }
                return phase >= 8;
            }
            case TWIN_THRUST -> {
                if ((phase == 0 || phase == 4) && valid(target, sp.range())) {
                    hit(level, target, blow(0.9D), phase == 4);
                }
                return phase >= 4;
            }
            case DISARM -> {
                if (valid(target, sp.range()) && hit(level, target, blow(0.3D), true)) {
                    RoleCombat.stagger(target, 20);
                    target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, CaptainSpecial.DISARM_TICKS, 2));
                }
                return true;
            }
            case DODGE_STEP -> {
                Vec3 side = Vec3.directionFromRotation(0.0F, lockedYaw + 90.0F).scale(0.7D);
                captain.setDeltaMovement(side.x, 0.1D, side.z);
                captain.hurtMarked = true;
                return true;
            }
            case AXE_CLEAVE -> {
                int n = 0;
                for (LivingEntity e : inArc(level, sp.range(), 90.0F, target)) {
                    if (n >= 4) {
                        break;
                    }
                    hit(level, e, blow(n == 0 ? 1.3D : 0.8D), false);
                    n++;
                }
                return true;
            }
            case SPINNING_CHOP -> {
                if (phase == 0 || phase == 6) {
                    for (LivingEntity e : enemies(level, sp.range())) {
                        hit(level, e, blow(0.8D), true);
                    }
                }
                return phase >= 6;
            }
            case ARMOUR_BREAKER -> {
                if (valid(target, sp.range())) {
                    double mult = 1.8D * (target instanceof RaiderEntity r
                        && r.variant() == RaiderEntity.Variant.BRUTE ? CaptainSpecial.ARMOUR_BREAKER_BRUTE_BONUS : 1.0D);
                    if (hit(level, target, blow(mult), true)) {
                        CaptainWorld.shred(level, target, CaptainSpecial.SHRED_ARMOR, CaptainSpecial.SHRED_TICKS);
                    }
                }
                return true;
            }
            case ARROW_VOLLEY -> {
                if (phase % CaptainSpecial.VOLLEY_WAVE_GAP == 0 && aim != null) {
                    consumeArrow();
                    level.sendParticles(ParticleTypes.CRIT, aim.x, aim.y + 4.0D, aim.z, 30,
                        CaptainSpecial.VOLLEY_RADIUS * 0.6D, 1.5D, CaptainSpecial.VOLLEY_RADIUS * 0.6D, 0.0D);
                    AABB box = new AABB(aim, aim).inflate(CaptainSpecial.VOLLEY_RADIUS, 3.0D, CaptainSpecial.VOLLEY_RADIUS);
                    for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
                        if (horizontalDistSqr(e.position(), aim) <= CaptainSpecial.VOLLEY_RADIUS * CaptainSpecial.VOLLEY_RADIUS) {
                            hit(level, e, CaptainSpecial.VOLLEY_DAMAGE, true);
                        }
                    }
                }
                return phase >= (CaptainSpecial.VOLLEY_WAVES - 1) * CaptainSpecial.VOLLEY_WAVE_GAP;
            }
            case PIERCING_SHOT -> {
                if (target != null) {
                    consumeArrow();
                    Vec3 from = captain.getEyePosition();
                    Vec3 dir = target.getEyePosition().subtract(from).normalize();
                    Vec3 to = from.add(dir.scale(sp.range()));
                    for (int i = 0; i < 24; i++) {
                        Vec3 p = from.lerp(to, i / 24.0D);
                        level.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 1, 0, 0, 0, 0);
                    }
                    for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                            new AABB(from, to).inflate(1.0D))) {
                        if (distanceToSegment(e.getBoundingBox().getCenter(), from, to)
                                <= CaptainSpecial.PIERCE_HALF_WIDTH + e.getBbWidth() * 0.5D) {
                            hit(level, e, CaptainSpecial.PIERCE_DAMAGE, true);
                        }
                    }
                }
                return true;
            }
            case MARK_TARGET -> {
                if (target != null && RoleCombat.isAuthorizedHostile(captain, target) && settlement != null) {
                    target.addEffect(new MobEffectInstance(MobEffects.GLOWING, CaptainSpecial.MARK_TICKS, 0));
                    CaptainWorld.mark(level, target, settlement.id, CaptainSpecial.MARK_TICKS);
                    for (SettlerEntity s : level.getEntitiesOfClass(SettlerEntity.class,
                            captain.getBoundingBox().inflate(20.0D))) {
                        if (s != captain && s.getProfession().martial()
                            && settlement.id.equals(s.getSettlementId())
                            && RoleCombat.isAuthorizedHostile(s, target)) {
                            s.setTarget(target);
                        }
                    }
                    lastHits.add(target);
                }
                return true;
            }
            case HALBERD_SWEEP -> {
                for (LivingEntity e : inArc(level, sp.range(), 60.0F, target)) {
                    if (hit(level, e, blow(1.1D), false)) {
                        RoleCombat.knockback(captain, e, 0.8D, lockedYaw);
                    }
                }
                return true;
            }
            case BRACE_CHARGE -> {
                if (valid(target, sp.range()) && hit(level, target, blow(2.0D), true)) {
                    RoleCombat.stagger(target, 40);
                }
                return true;
            }
            case HOOK_PULL -> {
                if (valid(target, sp.range()) && hit(level, target, blow(0.5D), true)) {
                    Vec3 pull = captain.position().subtract(target.position()).normalize().scale(0.9D);
                    target.setDeltaMovement(pull.x, 0.3D, pull.z);
                    target.hurtMarked = true;
                    RoleCombat.stagger(target, 20);
                }
                return true;
            }
            case SHIELD_BREAKER -> {
                if (valid(target, sp.range())) {
                    RoleCombat.breakGuard(target, 60);
                    if (hit(level, target, blow(1.4D), true)) {
                        RoleCombat.stagger(target, 20);
                    }
                }
                return true;
            }
            case GROUND_SLAM -> {
                for (LivingEntity e : enemies(level, sp.range())) {
                    if (hit(level, e, blow(0.8D), true)) {
                        RoleCombat.stagger(e, CaptainSpecial.SLAM_STUN_TICKS);
                    }
                }
                return true;
            }
            case CRUSHING_BLOW -> {
                if (valid(target, sp.range()) && hit(level, target, blow(2.4D), true)) {
                    RoleCombat.knockback(captain, target, 1.0D, lockedYaw);
                }
                return true;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ helpers

    private boolean valid(@Nullable LivingEntity target, double range) {
        return target != null && RoleCombat.isAuthorizedHostile(captain, target)
            && captain.distanceToSqr(target) <= (range + 1.0D) * (range + 1.0D);
    }

    /** One damage pass on an AUTHORIZED enemy only (friendly fire off). */
    private boolean hit(ServerLevel level, LivingEntity e, float amount, boolean ignoreCooldown) {
        if (!RoleCombat.isAuthorizedHostile(captain, e)) {
            return false;
        }
        boolean landed = RoleCombat.strike(captain, e, amount, ignoreCooldown);
        if (landed) {
            lastHits.add(e);
        }
        return landed;
    }

    private float blow(double mult) {
        double attack = captain.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double edge = GuardRank.MELEE_EDGE_PER_RANK * GuardRank.of(captain).ordinal();
        return (float) ((attack + GuardMeleeGoal.GUARD_TRAINING_DAMAGE + edge) * mult);
    }

    private void consumeArrow() {
        ItemStack off = captain.getOffhandItem();
        if (off.is(Items.ARROW)) {
            off.shrink(1);
        }
    }

    List<LivingEntity> enemies(ServerLevel level, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                captain.getBoundingBox().inflate(radius, 4.0D, radius))) {
            if (e != captain && RoleCombat.isAuthorizedHostile(captain, e)
                && captain.distanceToSqr(e) <= radius * radius) {
                out.add(e);
            }
        }
        out.sort(java.util.Comparator.comparingDouble(captain::distanceToSqr));
        return out;
    }

    private List<LivingEntity> inArc(ServerLevel level, double range, float halfArc,
                                     @Nullable LivingEntity primary) {
        List<LivingEntity> out = new ArrayList<>();
        if (primary != null && valid(primary, range)) {
            out.add(primary);
        }
        for (LivingEntity e : enemies(level, range + 0.5D)) {
            if (e != primary && RoleCombatRules.inArc(lockedYaw, RoleCombat.yawTo(captain, e), halfArc)) {
                out.add(e);
            }
        }
        return out;
    }

    private List<LivingEntity> inLine(ServerLevel level, double ahead) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : enemies(level, ahead + 1.0D)) {
            if (RoleCombatRules.inArc(lockedYaw, RoleCombat.yawTo(captain, e), 50.0F)) {
                out.add(e);
            }
        }
        return out;
    }

    @Nullable
    private LivingEntity target(List<LivingEntity> foes) {
        LivingEntity t = captain.getTarget();
        if (t != null && foes.contains(t)) {
            return t;
        }
        return foes.isEmpty() ? null : foes.get(0);
    }

    private CaptainBrain.Situation situation(ServerLevel level, CaptainState cs, List<LivingEntity> foes,
                                             @Nullable LivingEntity target, long now) {
        double[] d = new double[foes.size()];
        for (int i = 0; i < foes.size(); i++) {
            d[i] = Math.sqrt(RoleCombat.horizontalDistSqr(captain, foes.get(i)));
        }
        boolean civilians = false;
        int heavyIn = -1;
        for (LivingEntity f : foes) {
            if (f instanceof Mob mob && mob.getTarget() != null && f.distanceToSqr(captain) <= 256.0D) {
                LivingEntity victim = mob.getTarget();
                if (victim instanceof Player || victim instanceof SettlerEntity s && !s.getProfession().battlefield()) {
                    civilians = true;
                }
            }
            if (f instanceof RaiderEntity r && r.getTarget() == captain) {
                int in = r.ticksUntilHeavyContact();
                if (in >= 0 && (heavyIn < 0 || in < heavyIn)) {
                    heavyIn = in;
                }
            }
        }
        boolean charging = false;
        if (target != null) {
            Vec3 v = target.getDeltaMovement();
            charging = RoleCombatRules.isCharging(RoleCombatRules.closingSpeed(captain.getX(), captain.getZ(),
                target.getX(), target.getZ(), v.x, v.z), RoleCombat.knownCharger(target));
        }
        return new CaptainBrain.Situation(captain.getHealth() / captain.getMaxHealth(), cs.secondWindUsed(),
            cs.fightTicks(now), d,
            target == null ? -1.0D : Math.sqrt(RoleCombat.horizontalDistSqr(captain, target)),
            target != null && RoleCombat.strong(target), target != null && RoleCombat.guarded(target),
            // EXECUTION only reads this: a target that fails the finisher rule reads as healthy.
            target == null || !com.hearthstead.finisher.FinisherHooks.executionAllowed(target)
                ? 1.0F : target.getHealth() / target.getMaxHealth(),
            charging, heavyIn, civilians);
    }

    private static double horizontalDistSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static double distanceToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double t = Mth.clamp(p.subtract(a).dot(ab) / Math.max(1.0E-6D, ab.lengthSqr()), 0.0D, 1.0D);
        return p.distanceTo(a.add(ab.scale(t)));
    }

    // --------------------------------------------- test seams / evidence ---

    /** Deterministic QA: try exactly this special next (still respects cooldown and loadout). */
    public void forceNext(CaptainSpecial special) {
        this.forced = special;
    }

    @Nullable
    public CaptainSpecial activeSpecial() {
        return active;
    }

    public List<LivingEntity> lastHits() {
        return List.copyOf(lastHits);
    }
}
