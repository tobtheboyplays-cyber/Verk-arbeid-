package com.hearthstead.entity.ai;

import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.combat.GuardMove;
import com.hearthstead.entity.combat.GuardMoveSelector;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.monster.Enemy;

import java.util.UUID;

/**
 * The Guard's melee moveset driver: light slash, telegraphed heavy, the
 * light, light, finisher combo, and a shield bash that interrupts an enemy
 * heavy wind-up. Move data lives in {@link GuardMove}; the choice itself is
 * the pure {@link GuardMoveSelector}.
 *
 * <p>Every move is server-authoritative and two-phase. Starting a move
 * broadcasts its clip event and issues exactly one contact ticket; damage is
 * applied only on that move's hit tick, and only if the same target is still
 * authorized, in the move's reach and inside the swing arc locked at wind-up.
 * Losing any condition makes the swing a visible miss.
 *
 * <p>The goal owns its own cadence ({@code nextOpenerTick}) and never spends
 * vanilla's 20-tick attack cooldown, so {@link #canPerformAttack} is used only
 * for its reach and line-of-sight half.
 */
public class GuardMeleeGoal extends MeleeAttackGoal {

    /** MELEE's authored sword contact: t=0.20 s on Minecraft's 20 Hz clock. */
    public static final int MELEE_CONTACT_TICK = 4;
    /** Bounded Guard-only starter strength experiment; equipment/rank still add normally. */
    public static final double GUARD_TRAINING_DAMAGE = 4.0D;
    private static final ResourceLocation TRAINING_EDGE_ID =
        ResourceLocation.fromNamespaceAndPath("hearthstead", "guard_training_edge");

    /** The rank edge's transient modifier id; on the guard only for the one
     *  tick of the one blow, never persisted. */
    private static final ResourceLocation RANK_EDGE_ID =
        ResourceLocation.fromNamespaceAndPath("hearthstead", "guard_rank_edge");
    /** Modest role counter: no wrong-role penalty and no captain bonus. */
    private static final ResourceLocation BRUTE_COUNTER_ID =
        ResourceLocation.fromNamespaceAndPath("hearthstead",
            "guard_brute_counter");
    /** The move's damage multiplier, present for the one damage pass only. */
    private static final ResourceLocation MOVE_POWER_ID =
        ResourceLocation.fromNamespaceAndPath("hearthstead", "guard_move_power");
    public static final double COUNTER_DAMAGE_MULTIPLIER = 1.25D;
    /** Heavy weapons (great axe, warhammer) bite harder into a Brute. */
    public static final double HEAVY_WEAPON_BRUTE_MULTIPLIER = 1.35D;
    /** Non-raider enemies this armoured or this tough count as "strong". */
    public static final int STRONG_ARMOR = 10;
    public static final float STRONG_MAX_HEALTH = 40.0F;
    /** Vanilla's horizontal melee inflation (sqrt(2.04) - 0.6). */
    private static final double VANILLA_REACH_INFLATE = 0.828D;

    private final SettlerEntity settler;

    // ----- the one pending swing ---------------------------------------
    private long pendingContactTicket;
    private long pendingContactTick = Long.MIN_VALUE;
    private UUID pendingTargetId;
    private GuardMove pendingMove;
    /** Weapon type the pending swing was started with (timing/chaining). */
    private com.hearthstead.entity.combat.WeaponClass pendingWeapon;
    private long pendingStartTick;
    private float pendingSwingYaw;

    // ----- combo chain ---------------------------------------------------
    private boolean comboPlanned;
    /** Index of the most recently started combo link, or -1. */
    private int comboLinkIndex = -1;
    private boolean awaitingNextLink;
    private long nextLinkOpens = Long.MIN_VALUE;
    private long nextLinkCloses = Long.MIN_VALUE;

    // ----- cadence and reactions ----------------------------------------
    private long nextOpenerTick = Long.MIN_VALUE;
    private long nextBashTick = Long.MIN_VALUE;
    private long evadeUntil = Long.MIN_VALUE;

    // ----- deterministic test seams and read-only evidence ---------------
    private GuardMove forcedNextMove;
    private boolean forcedCombo;
    private GuardMove lastResolvedMove;
    private boolean lastResolvedLanded;
    private int landedMoves;

    public GuardMeleeGoal(SettlerEntity settler) {
        super(settler, 1.15, true);
        this.settler = settler;
    }

    @Override
    public boolean canUse() {
        return settler.getProfession() == Profession.GUARD
            && settler.level() instanceof ServerLevel level
            && EquipmentRequests.readyForProfession(level, settler,
                Profession.GUARD)
            && isAuthorizedHostile(settler.getTarget())
            && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return settler.getProfession() == Profession.GUARD
            && settler.level() instanceof ServerLevel level
            && EquipmentRequests.readyForProfession(level, settler,
                Profession.GUARD)
            && isAuthorizedHostile(settler.getTarget())
            && super.canContinueToUse();
    }

    @Override
    public void start() {
        super.start();
        settler.setActivity(SettlerActivity.COMBAT);
    }

    /**
     * Staggered guards stand rocked; an evading guard back-steps out of an
     * incoming heavy while still facing it. Otherwise vanilla chases and calls
     * {@link #checkAndPerformAttack}.
     */
    @Override
    public void tick() {
        if (settler.level() instanceof ServerLevel level) {
            long now = level.getGameTime();
            if (settler.isCombatStaggered()) {
                cancelPendingContact();
                breakCombo();
                settler.getNavigation().stop();
                return;
            }
            if (now < evadeUntil) {
                LivingEntity target = settler.getTarget();
                if (target != null) {
                    settler.getLookControl().setLookAt(target, 30.0F, 30.0F);
                }
                settler.getNavigation().stop();
                settler.getMoveControl().strafe(-0.8F, 0.0F);
                return;
            }
        }
        super.tick();
    }

    /**
     * Vanilla's start gate with the weapon's reach: a spear or halberd can
     * start (and land) a swing from further out than a sword. The cooldown
     * half is never spent by this goal, so isTimeToAttack stays true.
     */
    @Override
    protected boolean canPerformAttack(LivingEntity target) {
        return isTimeToAttack() && settler.isWithinGuardReach(target)
            && settler.getSensing().hasLineOfSight(target);
    }

    @Override
    protected void checkAndPerformAttack(LivingEntity target) {
        if (!(settler.level() instanceof ServerLevel level)) {
            cancelPendingContact();
            return;
        }
        long now = level.getGameTime();
        if (pendingContactTicket != 0L) {
            resolvePending(level, target, now);
            return;
        }
        if (awaitingNextLink) {
            tryNextLink(target, now);
            return;
        }
        tryOpener(target, now);
    }

    // ------------------------------------------------------------ phases

    private void resolvePending(ServerLevel level, LivingEntity target, long now) {
        // A target switch never transfers a cocked blade to the newcomer.
        if (pendingTargetId == null || !pendingTargetId.equals(target.getUUID())) {
            cancelPendingContact();
            breakCombo();
            return;
        }
        if (now < pendingContactTick) {
            if (now == pendingContactTick - 1L) {
                playWhoosh(level, pendingMove);
            }
            return;
        }

        long ticket = pendingContactTicket;
        long dueTick = pendingContactTick;
        GuardMove move = pendingMove;
        long started = pendingStartTick;
        float swingYaw = pendingSwingYaw;
        // Clear this goal's copy before entering vanilla damage hooks. The
        // entity ledger also consumes before hurt(), giving both layers the
        // same retry/re-entrancy guarantee.
        clearPendingFields();
        if (now != dueTick
            || settler.guardMoveFor(ticket) != move
            || !isAuthorizedContact(target)
            || !inMoveReach(move, target)
            || !inSwingArc(swingYaw, target, move)) {
            settler.cancelMeleeContact(ticket);
            onResolved(move, false, started);
            return;
        }

        boolean hit = performRankedContact(ticket, target, move);
        if (hit) {
            // Training, impact and cleave are consequences of an actual
            // accepted damage pass, never of reaching a timer or a swing.
            landedMoves++;
            settler.train(Attribute.STRENGTH, GuardRank.TRAIN_COMBAT);
            applyImpact(level, move, target, swingYaw);
            if (move != GuardMove.SHIELD_BASH) {
                cleave(target);
            }
        }
        onResolved(move, hit, started);
    }

    private void onResolved(GuardMove move, boolean landed, long started) {
        lastResolvedMove = move;
        lastResolvedLanded = landed;
        if (comboPlanned && move != null && move.chains(settler.guardWeaponClass()) && landed
            && comboLinkIndex + 1 < GuardMove.COMBO_LENGTH) {
            awaitingNextLink = true;
            nextLinkOpens = started + move.chainTick();
            nextLinkCloses = nextLinkOpens
                + com.hearthstead.settlement.techtree.effects.WatchEffects.comboWindow(settler,
                    HearthsteadServerConfig.guardComboWindowTicks());
            return;
        }
        // A missed link breaks the chain; its cadence was spent at start.
        breakCombo();
    }

    private void tryNextLink(LivingEntity target, long now) {
        if (now < nextLinkOpens) {
            return;
        }
        GuardMoveSelector.Choice reaction = GuardMoveSelector.react(situation(target, now));
        if (reaction != null) {
            breakCombo();
            act(reaction, target, now);
            return;
        }
        int next = comboLinkIndex + 1;
        GuardMove link = next < GuardMove.COMBO_LENGTH ? GuardMove.comboLink(next) : null;
        boolean inReach = link != null && canPerformAttack(target)
            && isAuthorizedContact(target) && inMoveReach(link, target);
        if (GuardMoveSelector.continueCombo(next, lastResolvedLanded, inReach, now,
                nextLinkOpens, nextLinkCloses)) {
            awaitingNextLink = false;
            if (!startMove(target, link, now, next)) {
                breakCombo();
            }
        } else if (now > nextLinkCloses || !lastResolvedLanded) {
            breakCombo();
        }
    }

    private void tryOpener(LivingEntity target, long now) {
        GuardMoveSelector.Choice choice;
        if (forcedNextMove != null) {
            choice = new GuardMoveSelector.Choice(GuardMoveSelector.Action.ATTACK,
                forcedNextMove, forcedCombo, 0);
        } else {
            GuardMoveSelector.Situation situation = situation(target, now);
            // Reactions (bash / step back) ignore the opener cadence; only the
            // bash's own cooldown gates it.
            choice = GuardMoveSelector.react(situation);
            if (choice == null) {
                if (now < nextOpenerTick) {
                    // Still recovering, so no light can be traded into the
                    // blow: an imminent enemy heavy is sidestepped instead.
                    int in = situation.enemyHeavyContactIn();
                    if (in >= 0 && in <= GuardMoveSelector.EVADE_LOOKAHEAD_TICKS) {
                        evadeUntil = now + in + 2L;
                    }
                    return;
                }
                choice = GuardMoveSelector.chooseOpener(situation,
                    settler.getRandom().nextDouble(), settler.getRandom().nextDouble());
            }
        }
        if (choice.action() == GuardMoveSelector.Action.ATTACK
            && choice.move() != GuardMove.SHIELD_BASH && now < nextOpenerTick) {
            return;
        }
        if (act(choice, target, now) && forcedNextMove != null) {
            forcedNextMove = null;
            forcedCombo = false;
        }
    }

    private boolean act(GuardMoveSelector.Choice choice, LivingEntity target, long now) {
        if (choice.action() == GuardMoveSelector.Action.EVADE) {
            evadeUntil = now + choice.evadeTicks();
            return true;
        }
        // Two-handed weapons commit to single blows: never plan a chain.
        boolean combo = choice.planCombo()
            && GuardMove.LIGHT_A.chains(settler.guardWeaponClass());
        comboPlanned = combo;
        boolean started = startMove(target, choice.move(), now, combo ? 0 : -1);
        if (!started) {
            comboPlanned = false;
        }
        return started;
    }

    /**
     * Revalidates vanilla reach/LOS, settlement authority and the move's own
     * reach, locks the swing direction, and issues the ticket.
     */
    private boolean startMove(LivingEntity target, GuardMove move, long now, int linkIndex) {
        // canPerformAttack is vanilla's reach + LOS gate (its cooldown half
        // is never spent by this goal). The extra predicates make the target
        // a settlement-authorized enemy inside this move's reach and reassert
        // the physical sword before any animation is broadcast.
        if (move == null || !canPerformAttack(target) || !isAuthorizedContact(target)
            || !inMoveReach(move, target)) {
            return false;
        }
        float yaw = yawTo(target);
        long ticket = settler.beginGuardMove(target, move);
        if (ticket == 0L) {
            return false;
        }
        // Lock the swing: the body commits to the target's bearing now, and
        // contact later must still fall inside this arc.
        settler.setYRot(yaw);
        settler.setYHeadRot(yaw);
        settler.yBodyRot = yaw;
        pendingContactTicket = ticket;
        pendingTargetId = target.getUUID();
        com.hearthstead.entity.combat.WeaponClass weapon = settler.guardWeaponClass();
        pendingWeapon = weapon;
        pendingContactTick = now + move.hitTick(weapon);
        pendingMove = move;
        pendingStartTick = now;
        pendingSwingYaw = yaw;
        comboLinkIndex = linkIndex;
        // Dexterity: quicker recovery between swings (plan/ATTRIBUTES.md).
        nextOpenerTick = now + com.hearthstead.entity.AttributeRuntime.meleeCadence(settler, move.cadenceTicks(weapon));
        if (move == GuardMove.SHIELD_BASH) {
            nextBashTick = now + com.hearthstead.settlement.techtree.effects.WatchEffects.shieldBashCooldown(
                settler, HearthsteadServerConfig.guardShieldBashCooldownTicks());
        }
        // The entity event is the sole presentation owner for this one-shot;
        // no vanilla swing packet is sent (see SettlerEntity#beginGuardMove).
        return true;
    }

    // ------------------------------------------------------------ helpers

    private GuardMoveSelector.Situation situation(LivingEntity target, long now) {
        boolean strong;
        int heavyIn = -1;
        boolean staggered = false;
        if (target instanceof RaiderEntity raider) {
            strong = raider.isCaptain() || raider.variant() == RaiderEntity.Variant.BRUTE;
            heavyIn = raider.ticksUntilHeavyContact();
            // A raider over-committed after a whiffed slam is as open as a
            // staggered one: punish it with the heavy.
            staggered = raider.isStaggered() || raider.isRecoveringFromWhiff();
        } else {
            strong = target.getArmorValue() >= STRONG_ARMOR
                || target.getMaxHealth() >= STRONG_MAX_HEALTH;
        }
        GuardRank rank = GuardRank.of(settler);
        // Shield Bash is the Spearman's rank ability (GuardRank table): a
        // Recruit carrying a shield still steps back instead.
        boolean shield = settler.hasPhysicalOffhandShield()
            && rank.atLeast(GuardRank.SPEARMAN)
            && GuardMove.allowsShieldBash(settler.guardWeaponClass());
        return new GuardMoveSelector.Situation(strong, heavyIn, staggered,
            target.isBlocking(), rank.ordinal(), shield,
            now >= nextBashTick,
            shield && canPerformAttack(target) && inMoveReach(GuardMove.SHIELD_BASH, target));
    }

    private float yawTo(LivingEntity target) {
        double dx = target.getX() - settler.getX();
        double dz = target.getZ() - settler.getZ();
        if (dx * dx + dz * dz < 1.0E-6D) {
            return settler.getYHeadRot();
        }
        return (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
    }

    private boolean inSwingArc(float swingYaw, LivingEntity target, GuardMove move) {
        return Mth.degreesDifferenceAbs(swingYaw, yawTo(target)) <= move.arcHalfDegrees();
    }

    /** A shorter move (the bash) must be closer than vanilla melee reach. */
    private boolean inMoveReach(GuardMove move, LivingEntity target) {
        if (move == null) {
            return false;
        }
        if (move.reachScale() >= 1.0D) {
            return true;
        }
        double full = settler.getBbWidth() * 0.5D + VANILLA_REACH_INFLATE
            + target.getBbWidth() * 0.5D;
        double dx = target.getX() - settler.getX();
        double dz = target.getZ() - settler.getZ();
        double max = full * move.reachScale();
        return dx * dx + dz * dz <= max * max;
    }

    /** Knockback, stagger, particles and impact audio for one landed move. */
    private void applyImpact(ServerLevel level, GuardMove move, LivingEntity target,
                             float swingYaw) {
        double sin = Mth.sin(swingYaw * Mth.DEG_TO_RAD);
        double cos = Mth.cos(swingYaw * Mth.DEG_TO_RAD);
        if (move.knockback() > 0.0D && target.isAlive()) {
            target.knockback(move.knockback(), sin, -cos);
        }
        if (move.staggers() && target.isAlive()) {
            staggerTarget(target, move.staggerTicks());
        }
        switch (move) {
            case HEAVY -> {
                level.sendParticles(ParticleTypes.SWEEP_ATTACK,
                    settler.getX() - sin * 1.1D, settler.getY(0.6D),
                    settler.getZ() + cos * 1.1D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                level.playSound(null, target.blockPosition(),
                    ModSounds.COMBAT_HEAVY_IMPACT.get(), SoundSource.NEUTRAL,
                    0.9F, 0.8F + settler.getRandom().nextFloat() * 0.1F);
            }
            case COMBO_FINISHER -> level.playSound(null, target.blockPosition(),
                ModSounds.COMBAT_HEAVY_IMPACT.get(), SoundSource.NEUTRAL,
                0.75F, 1.0F + settler.getRandom().nextFloat() * 0.1F);
            case SHIELD_BASH -> level.playSound(null, target.blockPosition(),
                ModSounds.SHIELD_THUD.get(), SoundSource.NEUTRAL,
                0.9F, 0.9F + settler.getRandom().nextFloat() * 0.1F);
            default -> {
            }
        }
    }

    /** A raider loses its wind-up; any other mob is briefly rooted. */
    private static void staggerTarget(LivingEntity target, int ticks) {
        if (target instanceof RaiderEntity raider) {
            raider.stagger(ticks);
        } else if (target instanceof Mob mob) {
            mob.getNavigation().stop();
            mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                ticks, 3, false, false));
        }
    }

    /** The swing's air sound, one tick before contact (combat.* events). */
    private void playWhoosh(ServerLevel level, GuardMove move) {
        if (move == null) {
            return;
        }
        float jitter = settler.getRandom().nextFloat() * 0.1F;
        switch (move) {
            case LIGHT_A, LIGHT_B -> level.playSound(null, settler.blockPosition(),
                ModSounds.COMBAT_SWING_LIGHT.get(), SoundSource.NEUTRAL, 0.4F, 1.35F + jitter);
            case COMBO_FINISHER -> level.playSound(null, settler.blockPosition(),
                ModSounds.COMBAT_SWING_HEAVY.get(), SoundSource.NEUTRAL, 0.65F, 1.0F + jitter);
            case HEAVY -> level.playSound(null, settler.blockPosition(),
                ModSounds.COMBAT_SWING_HEAVY.get(), SoundSource.NEUTRAL, 0.85F, 0.7F + jitter);
            case SHIELD_BASH -> level.playSound(null, settler.blockPosition(),
                ModSounds.COMBAT_BASH_SWING.get(), SoundSource.NEUTRAL, 0.5F, 0.85F + jitter);
        }
    }

    /** One vanilla damage pass with the rank edge and move power present for that pass only. */
    private boolean performRankedContact(long ticket, LivingEntity target, GuardMove move) {
        AttributeInstance attack = settler.getAttribute(Attributes.ATTACK_DAMAGE);
        boolean trained = attack != null && !attack.hasModifier(TRAINING_EDGE_ID);
        if (trained) attack.addTransientModifier(new AttributeModifier(TRAINING_EDGE_ID,
            GUARD_TRAINING_DAMAGE, AttributeModifier.Operation.ADD_VALUE));
        double edge = GuardRank.MELEE_EDGE_PER_RANK * GuardRank.of(settler).ordinal();
        boolean edged = attack != null && edge > 0.0 && !attack.hasModifier(RANK_EDGE_ID);
        double counter = counterDamageMultiplier(target)
            * heavyWeaponMultiplier(settler.guardWeaponClass(), target);
        boolean countered = attack != null && counter > 1.0D
            && !attack.hasModifier(BRUTE_COUNTER_ID);
        double power = (move == null ? 1.0D : move.damageMultiplier())
            // Tech tree: Veteran Techniques heavy x1.3, Order of Knights x1.25 (Sergeant+).
            * com.hearthstead.settlement.techtree.effects.WatchEffects.guardMovePower(settler, move);
        boolean powered = attack != null && power != 1.0D
            && !attack.hasModifier(MOVE_POWER_ID);
        if (edged) {
            attack.addTransientModifier(new AttributeModifier(RANK_EDGE_ID, edge,
                AttributeModifier.Operation.ADD_VALUE));
        }
        if (countered) {
            attack.addTransientModifier(new AttributeModifier(BRUTE_COUNTER_ID,
                counter - 1.0D,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        if (powered) {
            attack.addTransientModifier(new AttributeModifier(MOVE_POWER_ID,
                power - 1.0D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        // Strength: +0..20% melee damage for this pass only (plan/ATTRIBUTES.md).
        double might = com.hearthstead.entity.AttributeRuntime.meleeGain(settler) - 1.0D;
        boolean mighty = attack != null && might > 0.0D
            && !attack.hasModifier(com.hearthstead.entity.AttributeRuntime.STRENGTH_MELEE_ID);
        if (mighty) {
            attack.addTransientModifier(new AttributeModifier(
                com.hearthstead.entity.AttributeRuntime.STRENGTH_MELEE_ID, might,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        if (bypassesHurtCooldown(move)) {
            // Chained links land 6-8 ticks apart, inside vanilla's 10-tick
            // hurt-immunity half window, which would silently eat them (and a
            // bash's interrupt). Each link is its own ticketed, rate-limited
            // blow, so its one damage pass may ignore the previous blow's
            // immunity. It never enables a second pass for the same ticket.
            target.invulnerableTime = 0;
        }
        try {
            return settler.commitMeleeContact(ticket, target);
        } finally {
            if (trained) attack.removeModifier(TRAINING_EDGE_ID);
            if (mighty) attack.removeModifier(com.hearthstead.entity.AttributeRuntime.STRENGTH_MELEE_ID);
            if (powered) {
                attack.removeModifier(MOVE_POWER_ID);
            }
            if (countered) {
                attack.removeModifier(BRUTE_COUNTER_ID);
            }
            if (edged) {
                attack.removeModifier(RANK_EDGE_ID);
            }
        }
    }

    /** Combo follow-ups and the bash chain faster than vanilla hurt immunity. */
    public static boolean bypassesHurtCooldown(GuardMove move) {
        return move == GuardMove.LIGHT_B || move == GuardMove.COMBO_FINISHER
            || move == GuardMove.SHIELD_BASH;
    }

    /** Exact counter seam used both by contact damage and deterministic QA. */
    public static double counterDamageMultiplier(LivingEntity target) {
        return target instanceof RaiderEntity raider
            && !raider.isCaptain()
            && raider.variant() == RaiderEntity.Variant.BRUTE
                ? COUNTER_DAMAGE_MULTIPLIER : 1.0D;
    }

    /** Great axe or warhammer against any Brute (captains included). */
    public static double heavyWeaponMultiplier(com.hearthstead.entity.combat.WeaponClass weapon,
                                               LivingEntity target) {
        return target instanceof RaiderEntity raider
            ? heavyWeaponMultiplier(weapon, raider.variant()) : 1.0D;
    }

    /** Pure overload of {@link #heavyWeaponMultiplier(com.hearthstead.entity.combat.WeaponClass, LivingEntity)}. */
    public static double heavyWeaponMultiplier(com.hearthstead.entity.combat.WeaponClass weapon,
                                               RaiderEntity.Variant variant) {
        return variant == RaiderEntity.Variant.BRUTE
            && (weapon == com.hearthstead.entity.combat.WeaponClass.GREAT_AXE
                || weapon == com.hearthstead.entity.combat.WeaponClass.WARHAMMER)
            ? HEAVY_WEAPON_BRUTE_MULTIPLIER : 1.0D;
    }

    /** Pure overload: no world/entity fixture needed to pin the design rule. */
    public static double counterDamageMultiplier(
            RaiderEntity.Variant variant, boolean captain) {
        return !captain && variant == RaiderEntity.Variant.BRUTE
            ? COUNTER_DAMAGE_MULTIPLIER : 1.0D;
    }

    /** Contact-time validation deliberately excludes the cooldown already
     * spent at wind-up, while repeating every physical/authority condition. */
    private boolean isAuthorizedContact(LivingEntity target) {
        return settler.isAuthorizedMeleeContactTarget(target);
    }

    /**
     * The target must still be a live server-side enemy of this settlement.
     * Ordinary monsters qualify only inside its defended ring; a raid-bound
     * raider must additionally name this exact settlement, so two nearby
     * settlements cannot damage each other's raid actors.
     */
    private boolean isAuthorizedHostile(LivingEntity target) {
        if (!(settler.level() instanceof ServerLevel level)
            || target == null || target.level() != level
            || !target.isAlive() || target.isRemoved()
            || !(target instanceof Enemy)
            || !settler.canAttack(target)) {
            return false;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null) {
            return false;
        }
        if (com.hearthstead.settlement.guard.BannerTeams.active(settler) != null) {
            return com.hearthstead.settlement.guard.BannerTeams.allowsTarget(settler, target);
        }
        double defendedRadius = settlement.radius + 8.0;
        if (target instanceof RaiderEntity raider && raider.lootCount() > 0) {
            // Match SettlerDefenseTargetGoal: a looting raider stays fair game
            // until RaiderLootGoal would count it escaped.
            defendedRadius = settlement.radius + RaiderLootGoal.ESCAPE_MARGIN;
        }
        if (target.blockPosition().distSqr(settlement.center)
            > defendedRadius * defendedRadius) {
            return false;
        }
        if (target instanceof RaiderEntity raider
            && raider.settlementId() != null
            && !settlement.id.equals(raider.settlementId())) {
            return false;
        }
        return true;
    }

    /**
     * A veteran's swing catches a second enemy.
     *
     * <p>Secondary targets take {@link GuardRank#CLEAVE_SHARE}, matching
     * vanilla's sweep: an area attack that hits everything for full damage
     * stops being a special move and becomes the only move.
     */
    private void cleave(LivingEntity target) {
        if (!GuardRank.of(settler).atLeast(GuardRank.VETERAN)) {
            return;
        }
        if (!(settler.level() instanceof ServerLevel level)) {
            return;
        }
        float share = GuardRank.CLEAVE_SHARE;
        for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class,
                settler.getBoundingBox().inflate(2.2))) {
            // Splash is HOSTILE-ONLY: the swing follows through into the
            // raid, never into a bystander — a passing cow, somebody's pet,
            // or a player leaning in to watch must not catch the edge of it.
            // (RaiderEntity is never a SettlerEntity, so this also keeps the
            // old never-your-own-people rule, plus the canAttack filter.)
            if (other == settler || other == target
                || !(other instanceof RaiderEntity)
                || !isAuthorizedHostile(other)) {
                continue;
            }
            other.hurt(level.damageSources().mobAttack(settler), 3.0F * share);
            break;  // ONE extra, not a whirlwind
        }
    }

    @Override
    public void stop() {
        cancelPendingContact();
        breakCombo();
        evadeUntil = Long.MIN_VALUE;
        super.stop();
        settler.setActivity(SettlerActivity.IDLE);
    }

    private void breakCombo() {
        comboPlanned = false;
        comboLinkIndex = -1;
        awaitingNextLink = false;
        nextLinkOpens = Long.MIN_VALUE;
        nextLinkCloses = Long.MIN_VALUE;
    }

    private void cancelPendingContact() {
        if (pendingContactTicket != 0L) {
            settler.cancelMeleeContact(pendingContactTicket);
        }
        clearPendingFields();
    }

    private void clearPendingFields() {
        pendingContactTicket = 0L;
        pendingContactTick = Long.MIN_VALUE;
        pendingTargetId = null;
        pendingMove = null;
        pendingWeapon = null;
        pendingStartTick = Long.MIN_VALUE;
    }

    // ------------------------------------------------ test seams / evidence

    /** Deterministic QA seam: the next opener is exactly this move. */
    public void forceNextMove(GuardMove move, boolean planCombo) {
        this.forcedNextMove = move;
        this.forcedCombo = planCombo && move == GuardMove.LIGHT_A;
    }

    /** The move whose wind-up is live, or null. */
    public GuardMove pendingMove() {
        return pendingMove;
    }

    /** Index of the last started combo link (0..2), or -1 outside a combo. */
    public int comboLinkIndex() {
        return comboLinkIndex;
    }

    public boolean awaitingComboLink() {
        return awaitingNextLink;
    }

    public GuardMove lastResolvedMove() {
        return lastResolvedMove;
    }

    public boolean lastResolvedLanded() {
        return lastResolvedLanded;
    }

    public int landedMoves() {
        return landedMoves;
    }

    public boolean isEvading() {
        return settler.level() instanceof ServerLevel level
            && level.getGameTime() < evadeUntil;
    }
}
