package com.hearthstead.entity.ai;

import com.hearthstead.entity.ArcherRank;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.OwnedProjectileLedger;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.DeferredItemMaterializationSavedData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.Summons;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import com.hearthstead.settlement.state.GuardOrder;
import com.hearthstead.util.QaTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.UUID;

/**
 * The watchtower archer: hold the ring, keep the distance, and make every
 * arrow one the fletcher actually made.
 *
 * <h2>Chest-true arrows — the fletcher finally has a consumer</h2>
 *
 * <p>FLOWS.md's table has carried the edge "fletcher → barracks/watchtower"
 * since it was written, and until this goal nothing on the receiving end
 * consumed a single arrow. Now it is literal: the archer's quiver is
 * restocked, item for item, from the WATCHTOWER's own chests
 * ({@link WarehouseIndex#containers} over the building's scanned bounds), a
 * fired arrow is a real {@link AbstractArrow} entity in the world, and an
 * empty tower means an archer who cannot shoot — which is precisely the
 * pressure that makes hiring a fletcher matter. Every arrow is conserved
 * exactly: chest → quiver on restock, quiver → world on release, quiver →
 * chest again when the archer stands down with shafts unspent. Quiver
 * ownership lives on {@link SettlerEntity} and is persisted, so unloading or
 * restarting between restock and release cannot erase the borrowed shafts.
 *
 * <p>Fired arrows are spawned with {@code pickup = DISALLOWED}, the way
 * vanilla skeleton arrows are. That is the <b>sanctioned consumption
 * sink</b>: the martial chain is the economy's one set of goods that is
 * allowed to be genuinely used up (FLOWS.md, acyclicity — "arms decay
 * through wear"), and a spent shaft in the grass that could be picked back
 * up would quietly turn the tower into an arrow fountain.
 *
 * <h2>Kiting</h2>
 *
 * <p>An archer is not a guard with a longer sword: they prefer the
 * {@value #PREFER_MIN}–{@value #PREFER_MAX} block ring, close the gap only
 * when the target is beyond it, and back away the moment anything comes
 * inside {@value #BACK_AWAY_UNDER}. The bow draw continues during a
 * retreat while sight and physical ammunition remain valid.
 *
 * <h2>What is deliberately mirrored from the guard trade</h2>
 *
 * <ul>
 *   <li>Rank is earned by doing ({@link ArcherRank}, on DEXTERITY, trained
 *       per volley and per arrow that strikes — never on a timer).
 *   <li>Effort is spent (1 per volley-cycle) but <b>never gates combat</b>,
 *       the same rule {@code GuardPatrolGoal} documents: a spent archer
 *       still defends. Safety beats bookkeeping.
 *   <li>Target acquisition is delegated to the exact same bounded
 *       {@link SettlerDefenseTargetGoal} coordinator used by guards, including
 *       urgency, role fit and live defender load. There is no private nearest
 *       scan that can silently collapse the formation back into a dogpile.
 * </ul>
 */
public class ArcherAttackGoal extends Goal {

    public static final double COUNTER_DAMAGE_MULTIPLIER = 1.25D;

    /** Arrows a RECRUIT carries at once (owner, 27 Sep: six). Small on
     *  purpose: the quiver is a handful borrowed from the tower rack, not a
     *  second warehouse. Rank and a bigger Watchtower raise it -- see
     *  {@link #quiverCapacity(SettlerEntity)}. SettlerEntity's
     *  ARCHER_QUIVER_CAPACITY (16) stays the hard persisted ceiling and the
     *  tower stock target the Courier/Hunter deliveries aim for. */
    public static final int QUIVER_SIZE = ArcherRank.QUIVER_BASE;
    /** How far from the tower's room a restock (or return) still counts as
     *  "at the rack". */
    private static final int RESTOCK_REACH = 8;
    /** Owner, 27 Sep: dry archers hold the line in battle; only the player's
     *  RESUPPLY field order sends them to the rack. false = the old walk-back. */
    static final boolean HOLD_THE_LINE = true;

    /** This archer's quiver: rank ladder 6/8/10/12, +2 at a level-2+ tower. */
    public static int quiverCapacity(SettlerEntity settler) {
        Building tower = towerOf(settler);
        return ArcherRank.of(settler).quiverCapacity(tower == null ? 1 : tower.level);
    }

    /** The preferred fighting ring, in blocks. */
    public static final double PREFER_MIN = 8.0;
    public static final double PREFER_MAX = 16.0;
    /** Inside this, open the distance without starving the bow draw. */
    public static final double BACK_AWAY_UNDER = 6.0;
    /** Beyond this an unposted archer never looses, even mid-draw. */
    public static final double NORMAL_SHOT_RANGE = 18.0;

    /** Vanilla Arrow launch speed; Tower Post only solves the existing air arc. */
    private static final double ARROW_SPEED = 1.6D;
    /** Vanilla air update after each movement step: velocity * .99, then gravity. */
    private static final double ARROW_AIR_DRAG = .99D;
    private static final double ARROW_GRAVITY = .05D;
    /** Bisection steps after a two-degree lower-arc scan finds its bracket. */
    private static final int TOWER_BALLISTIC_SOLVE_STEPS = 28;
    private static final double TOWER_BALLISTIC_MIN_ANGLE = -70.0D;
    private static final double TOWER_BALLISTIC_MAX_ANGLE = 70.0D;
    private static final double TOWER_BALLISTIC_ANGLE_STEP = 2.0D;
    private static final int TOWER_BALLISTIC_FLIGHT_TICKS = 160;

    /** Ticks of steady aim before an ordinary volley releases. */
    private static final int ORDINARY_DRAW_TICKS = 20;
    /** Ticks of rest between volleys, so the cycle reads as aim-loose-lower
     *  rather than a turret. One full ordinary cycle is ~35 ticks. */
    private static final int VOLLEY_RECOVERY_TICKS = 15;
    /** Draw tick of the string creak: ARCHER_DRAW raises the bow first and starts the pull on this tick. */
    public static final int DRAW_CREAK_TICK = 6;
    /** Ticks after the release when ARCHER_RELOAD's right hand grips an arrow in the quiver (0.28-0.30 s). */
    public static final int QUIVER_RUSTLE_TICK = 6;

    /** Re-scan interval for self-acquisition, mirroring the 10-tick
     *  randomInterval {@link SettlerDefenseTargetGoal} passes to vanilla —
     *  one bounded AABB query per interval, never per tick (budgeted). */
    private static final int RETARGET_INTERVAL = 10;
    /** Path creation while a target is behind cover is throttled to the same
     * bounded cadence as target acquisition. */
    private static final int LOS_REPOSITION_INTERVAL = 10;

    /** How far the "out of arrows" line reaches, in blocks -- narrower than
     *  {@code RaidBroadcast}'s settlement-wide radius+32 on purpose: this is
     *  a post-specific complaint ("this tower is empty"), not settlement
     *  news, so it should only reach someone standing near enough to have
     *  noticed the archer in the first place. See class doc "Starving
     *  speaks". */
    private static final double ANNOUNCE_RANGE = 12.0;

    private final SettlerEntity settler;
    /** Same selector as the entity target goal; retained only as a fallback
     * for selector ordering and direct goal tests, never a second policy. */
    private final SettlerDefenseTargetGoal coordinatedTargeting;

    private int drawTicks;
    private int recoverTicks;
    /** Game time of the pending quiver rustle (presentation only), or -1. */
    private long quiverRustleAt = -1L;
    private int retargetIn;
    private int losRepositionIn;
    private int retreatRepositionIn;
    private GuardOrder movementOrder;
    private int movementRevision = -1;
    private long movementRetryAt;
    private boolean movementBlocked;
    /** Decided at the moment a draw begins, so the long pause telegraphs
     *  the Power Shot before it exists. */
    private boolean drawingPowerShot;
    private boolean drawingTripleShot;
    /** True for the span of ONE continuous starvation episode: set the
     *  first tick a live target goes unshot for want of arrows, cleared the
     *  moment the rack has arrows again (restock succeeds). Goal restarts
     *  do not end starvation. Gates the line to once per episode rather than
     *  once per tick -- see {@link #reportOutOfAmmo}. */
    private boolean outOfAmmoAnnounced;

    // Test seams (ArcherGameTests): cadence and ammo are asserted through
    // these, because "design for testability" beats poking at private state.
    private int shotsFired;
    private int powerShotsFired;
    // ACCEPT-JOBS audit (2026-08-26): Triple Shot had zero test coverage --
    // only powerShotsFired existed as a seam, so nothing could tell a MASTER
    // archer's 5th-volley fan apart from an ordinary shot without reaching
    // into private state. Same seam shape as powerShotsFired, one cycle
    // later.
    private int tripleShotsFired;

    // Diagnostic continuity only; never read by gameplay decisions.
    private String qaLastDecision;
    private UUID qaLastTarget;
    private long qaLastDecisionTick = -1;
    private int qaContiguousDecisionTicks;
    private int qaQuiverBeforeRelease;
    private int qaShotsBeforeRelease;

    public ArcherAttackGoal(SettlerEntity settler) {
        this.settler = settler;
        this.coordinatedTargeting = new SettlerDefenseTargetGoal(settler);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        // Draw timing is the whole feel of the trade; counting it in the
        // selector's 2-tick steps would make the Power Shot pause mushy.
        return true;
    }

    @Override
    public boolean canUse() {
        if (settler.getProfession() != Profession.ARCHER) {
            return false;
        }
        if (!(settler.level() instanceof ServerLevel level)
            || !EquipmentRequests.readyForProfession(level, settler,
                Profession.ARCHER)) {
            return false;
        }
        LivingEntity target = settler.getTarget();
        if (coordinatedTargeting.accepts(target)) {
            return true;
        }
        // A target can become foreign, leave the defended ring, or otherwise
        // lose authority while still alive. Never let that stale reference
        // bypass the shared coordinator through the Archer's direct goal.
        if (target != null) {
            settler.setTarget(null);
        }
        if (--retargetIn > 0) {
            return false;
        }
        retargetIn = RETARGET_INTERVAL;
        LivingEntity acquired = acquire();
        if (acquired == null) {
            return false;
        }
        settler.setTarget(acquired);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = settler.getTarget();
        boolean valid = settler.getProfession() == Profession.ARCHER
            && settler.level() instanceof ServerLevel level
            && EquipmentRequests.readyForProfession(level, settler,
                Profession.ARCHER)
            && coordinatedTargeting.acceptsForPursuit(target);
        if (!valid && target != null && settler.getTarget() == target) {
            settler.setTarget(null);
        }
        return valid;
    }

    @Override
    public void start() {
        settler.setActivity(SettlerActivity.COMBAT);
        drawTicks = 0;
        recoverTicks = 0;
        losRepositionIn = 0;
        retreatRepositionIn = 0;
        planNextVolley();
        if (QaTrace.ENABLED) qaDecision("START", settler.getTarget());
    }

    @Override
    public void tick() {
        LivingEntity target = settler.getTarget();
        if (target == null || !(settler.level() instanceof ServerLevel level)) {
            if (QaTrace.ENABLED) qaDecision("NO_TARGET_OR_SERVER", target);
            return;
        }
        if (quiverRustleAt >= 0L && level.getGameTime() >= quiverRustleAt) {
            quiverRustleAt = -1L;       // sound only: no gameplay reads this
            level.playSound(null, settler.blockPosition(),
                com.hearthstead.registry.ModSounds.WORK_QUIVER_RUSTLE.get(), SoundSource.NEUTRAL,
                0.55F, 0.92F + settler.getRandom().nextFloat() * 0.16F);
        }
        refreshMovementOrder(level);
        if (movementBlocked) {
            settler.getNavigation().stop();
            cancelDraw();
            if (QaTrace.ENABLED) qaDecision("MOVEMENT_BLOCKED", target);
            return;
        }
        settler.getLookControl().setLookAt(target, 30.0F, 30.0F);

        // A persisted quiver is physical ownership, but only its recorded
        // source Watchtower may authorize use. Reassignment A -> B must not
        // turn A's borrowed arrows into B's readiness or combat ammunition.
        if (quiverCount() > 0 && !quiverBelongsToCurrentTower()) {
            settler.setActivity(SettlerActivity.OUT_OF_AMMO);
            cancelDraw();
            if (QaTrace.ENABLED) qaDecision("WRONG_QUIVER", target);
            return;
        }
        if (ArcherResupplyGoal.returning(settler)) {
            // Back from a player-called RESUPPLY, even if its source ran dry: rejoin the slot
            // before loosing again, so the call never dissolves the formation.
            // Guard/tower orders already return via RETURN_TO_ORDER below.
            BlockPos slot = com.hearthstead.settlement.guard.BannerTeams.active(settler) != null
                ? com.hearthstead.settlement.guard.BannerTeams.anchor(settler)
                : movementOrder == null ? null : movementOrder.pos().orElse(null);
            if (slot != null && settler.blockPosition().distSqr(slot) > 16.0D) {
                if (settler.getNavigation().isDone() || level.getGameTime() % 20L == 0L) {
                    settler.getNavigation().moveTo(slot.getX() + 0.5D, slot.getY(),
                        slot.getZ() + 0.5D, 1.3D);
                }
                cancelDraw();
                if (QaTrace.ENABLED) qaDecision("RETURN_AFTER_RESUPPLY", target);
                return;
            }
            ArcherResupplyGoal.arrived(settler);
        }


        if (quiverCount() <= 0 && !restock(level)) {
            // Owner, 27 Sep: a dry archer HOLDS THE LINE. He does not leave
            // his post or formation mid-fight to run for arrows; he stands,
            // shows the arrow bubble over his head (OUT_OF_AMMO is synced
            // and drawn by SettlerThoughtBubble) and waits. Out of combat
            // ArcherResupplyGoal refills him at the rack. An archer whose
            // post is AT the tower still restocks in place above.
            reportOutOfAmmo(level);
            if (HOLD_THE_LINE) {
                settler.getNavigation().stop();
            } else {
                walkTowardsTower(level);
            }
            cancelDraw();
            if (QaTrace.ENABLED) qaDecision("DRY_HOLD", target);
            return;
        }
        if (outOfAmmoAnnounced) {
            // The rack has arrows again: this starvation episode is over.
            // Clearing the flag here (not just on stop/start) is what makes
            // the NEXT empty spell against the SAME target announce fresh,
            // rather than staying silently "already told them once" for the
            // rest of the fight.
            outOfAmmoAnnounced = false;
        }
        settler.setActivity(SettlerActivity.COMBAT);


        boolean summonsActive = Summons.active(settler);
        boolean activeTowerOrder = movementOrder != null
            && movementOrder.modeAt(level.getGameTime()) == GuardOrder.Mode.TOWER_POST
            && !summonsActive;
        if (movementOrder != null && !activeTowerOrder
            && !insideMovementRegion(settler.blockPosition())) {
            navigateWithinOrder(level, Vec3.atBottomCenterOf(movementOrder.pos().orElseThrow()), 1.15);
            cancelDraw();
            if (QaTrace.ENABLED) qaDecision("RETURN_TO_ORDER", target);
            return;
        }
        Settlement settlement = settler.settlement();
        boolean towerPostLocked = ArcherTowerPost.atActiveOwnPost(level,
            settlement, settler);
        boolean returningToTowerPost = activeTowerOrder
            && !towerPostLocked;
        if (returningToTowerPost) {
            // A Tower order holds the actual firing cell, not merely its
            // eight-block order region. Do not grant a displaced archer a
            // tower shot or let it step down to chase while returning.
            navigateWithinOrder(level,
                Vec3.atBottomCenterOf(movementOrder.pos().orElseThrow()), 1.15);
            cancelDraw();
            if (QaTrace.ENABLED) qaDecision("RETURN_TO_TOWER_POST", target);
            return;
        }
        double distance = settler.distanceTo(target);
        // Paid Longbow Drill (+2), Perception (+0..25%) and the height bonus
        // (+1 per block above the target, cap +8): one authority shared with
        // target selection, so "in range" never disagrees (ArcherHeightAdvantage).
        double normalShotRange = ArcherHeightAdvantage.normalShotRange(level,
            settlement, settler, target);
        double shotRange = towerPostLocked
            ? Math.max(ArcherTowerPost.SHOT_RANGE, normalShotRange)
            : normalShotRange;
        if (towerPostLocked) {
            // A tower sniper holds the elevated post. It neither retreats
            // from a close target nor descends to close a distant/blocked
            // shot; fresh sight remains mandatory for every volley.
            settler.getNavigation().stop();
            retreatRepositionIn = 0;
            losRepositionIn = 0;
            if (!settler.hasLineOfSight(target)) {
                cancelDraw();
                if (QaTrace.ENABLED) qaDecision("TOWER_LOS_BLOCKED", target);
                return;
            }
            if (distance > shotRange) {
                cancelDraw();
                if (QaTrace.ENABLED) qaDecision("TOWER_OUT_OF_SHOT_RANGE", target);
                return;
            }
        }
        boolean retreating = !towerPostLocked && distance < BACK_AWAY_UNDER;
        if (retreating) {
            // Movement is not a cancelled attack. A pursuer can stay inside
            // six blocks indefinitely; resetting here prevented every shot
            // and even stopped the previous volley's recovery from advancing.
            // Keep the existing safe order-bound path choice, at a bounded
            // cadence, while the ordinary LOS/draw/ammo checks run below.
            if (--retreatRepositionIn <= 0) {
                retreatRepositionIn = LOS_REPOSITION_INTERVAL;
                Vec3 away = movementOrder == null
                    ? DefaultRandomPos.getPosAway(settler, 8, 4, target.position())
                    : settler.position().add(settler.position().subtract(
                        target.position()).multiply(1, 0, 1).normalize().scale(8));
                if (away != null) {
                    navigateWithinOrder(level, away, 1.15);
                }
            }
        } else {
            retreatRepositionIn = 0;
        }
        // Between the preferred ring and the real shot range a visible target
        // is shot, not chased: a Stand-post leash can pin the archer at its
        // edge, and chasing there cancelled every draw at 16-18 blocks.
        boolean shootBeyondRing = !towerPostLocked && distance > PREFER_MAX
            && distance <= normalShotRange && settler.hasLineOfSight(target);
        if (!towerPostLocked && distance > PREFER_MAX && !shootBeyondRing) {
            if (movementOrder == null && com.hearthstead.settlement.guard.BannerTeams.active(settler) == null) settler.getNavigation().moveTo(target, 1.05);
            else navigateWithinOrder(level, target.position(), 1.05);
            cancelDraw();
            if (QaTrace.ENABLED) qaDecision("CHASE_GT16", target);
            return;
        }

        // A target inside the preferred ring can still be hidden by a gate,
        // wall or corner. Stopping before the LOS check created an infinite
        // stop/cancel loop. Keep the same bounded target and ask normal path
        // navigation to close around the obstacle at most once per ten ticks;
        // door handling remains owned by SettlerDoorGoal and no teleport or
        // global position scan is introduced.
        if (!towerPostLocked && !settler.hasLineOfSight(target)) {
            if (--losRepositionIn <= 0) {
                losRepositionIn = LOS_REPOSITION_INTERVAL;
                if (movementOrder == null && com.hearthstead.settlement.guard.BannerTeams.active(settler) == null) settler.getNavigation().moveTo(target, 1.05D);
                else navigateWithinOrder(level, target.position(), 1.05D);
            }
            cancelDraw();
            if (QaTrace.ENABLED) qaDecision("LOS_REPOSITION", target);
            return;
        }
        losRepositionIn = 0;
        if (!towerPostLocked && !retreating) {
            movementRetryAt = 0;
            settler.getNavigation().stop();
        }

        if (recoverTicks > 0) {
            recoverTicks--;
            if (QaTrace.ENABLED) qaDecision("RECOVERY", target);
            return;
        }
        if (distance > shotRange) {
            cancelDraw();
            if (QaTrace.ENABLED) qaDecision("OUT_OF_SHOT_RANGE", target);
            return;
        }
        // A visible target permits the same physical draw while stationary
        // or retreating. Vanilla synced bow use remains the presentation
        // clock; movement never manufactures or skips an arrow release.
        if (drawTicks == 0) {
            // The synced vanilla use-item state is the physical bow draw.
            // Clients derive the arm/string pull from this server-owned clock;
            // no client timer is allowed to invent or finish a volley.
            settler.startUsingItem(InteractionHand.MAIN_HAND);
        }
        drawTicks++;
        if (drawTicks == DRAW_CREAK_TICK) {
            // The string creak as the pull starts (sound only; pitch varies per shot so a line of
            // archers never creaks in unison).
            level.playSound(null, settler.blockPosition(),
                com.hearthstead.registry.ModSounds.WORK_BOW_DRAW.get(), SoundSource.NEUTRAL,
                0.45F, 0.9F + settler.getRandom().nextFloat() * 0.2F);
        }
        // Focus: -0..20% draw time (plan/ATTRIBUTES.md).
        int needed = com.hearthstead.entity.AttributeRuntime.drawCast(settler,
            com.hearthstead.settlement.techtree.effects.WatchEffects.archerDrawTicks(level, settlement,
                com.hearthstead.settlement.development.ArcherDrill
                    .ordinaryDrawTicks(level, settlement, ORDINARY_DRAW_TICKS)))
            + (drawingPowerShot ? ArcherRank.POWER_SHOT_DRAW_TICKS : 0);
        if (QaTrace.ENABLED) qaDecision("DRAW", target);
        if (drawTicks >= needed) {
            boolean released = loose(level, target, towerPostLocked);
            settler.stopUsingItem();
            // The presentation event is the visible completion of a real
            // projectile release. Keep a failed addFreshEntity attempt from
            // producing a bow snap with no arrow or loose sound.
            if (released) {
                settler.triggerArcherLoose();
                quiverRustleAt = level.getGameTime() + QUIVER_RUSTLE_TICK;
            }
            drawTicks = 0;
            recoverTicks = VOLLEY_RECOVERY_TICKS;
            planNextVolley();
        }
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        settler.getNavigation().stop();
        cancelDraw();
        losRepositionIn = 0;
        retreatRepositionIn = 0;
        movementRetryAt = 0;
        LivingEntity target = settler.getTarget();
        if (target != null && !target.isAlive()) {
            settler.setTarget(null);
        }
        // Standing down with nothing left to fight: the unspent handful goes
        // back in the rack (exact conservation -- the quiver is borrowed,
        // not owned). While hostiles remain, keep it: mid-raid churn of
        // take-out/put-back between kills would hammer the same chest.
        if (settler.level() instanceof ServerLevel level
            && settler.getTarget() == null && acquire() == null) {
            returnUnspent(level);
        }
        if (QaTrace.ENABLED) qaDecision("STOP", settler.getTarget());
    }

    /** Called only behind QaTrace.ENABLED; no state here controls the goal. */
    private void qaDecision(String decision, @Nullable LivingEntity target) {
        long tick = settler.level().getGameTime();
        UUID targetId = target == null ? null : target.getUUID();
        boolean continuous = tick == qaLastDecisionTick + 1
            && decision.equals(qaLastDecision)
            && java.util.Objects.equals(targetId, qaLastTarget);
        String ended = "";
        if (!continuous && qaLastDecision != null) {
            ended = " previousDecision=" + qaLastDecision
                + " previousTarget=" + qaLastTarget
                + " previousEndTick=" + qaLastDecisionTick
                + " previousSpan=" + qaContiguousDecisionTicks;
        }
        qaContiguousDecisionTicks = continuous ? qaContiguousDecisionTicks + 1 : 1;
        qaLastDecision = decision;
        qaLastTarget = targetId;
        qaLastDecisionTick = tick;
        if (!continuous || qaContiguousDecisionTicks % 20 == 0) {
            QaTrace.event(settler, "ARCHER_DECISION",
                "decision=" + decision + " contiguousDecisionTicks=" + qaContiguousDecisionTicks
                    + ended + " " + qaSnapshot(target));
        }
    }

    /** Read-only snapshot taken after the existing gameplay branch acts. */
    private String qaSnapshot(@Nullable LivingEntity target) {
        Path path = settler.getNavigation().getPath();
        return "target=" + (target == null ? "none" : target.getUUID())
            + " targetName=" + (target == null ? "none" : target.getName().getString())
            + " distance=" + (target == null ? -1 : settler.distanceTo(target))
            + " hasLOS=" + (target != null && settler.hasLineOfSight(target))
            + " order=" + (movementOrder == null ? "none" : movementOrder.mode())
            + " post=" + (movementOrder == null ? "none" : movementOrder.pos().orElse(null))
            + " leash=" + (movementOrder == null ? -1 : movementOrder.leashRadius())
            + " insideOrder=" + (movementOrder == null || insideMovementRegion(settler.blockPosition()))
            + " navDone=" + settler.getNavigation().isDone()
            + " navTarget=" + (path == null ? "none" : path.getTarget())
            + " drawTicks=" + drawTicks + " recoverTicks=" + recoverTicks
            + " neededDrawTicks=" + (ORDINARY_DRAW_TICKS
                + (drawingPowerShot ? ArcherRank.POWER_SHOT_DRAW_TICKS : 0))
            + " quiver=" + quiverCount() + " shotsFired=" + shotsFired
            + " powerShotsFired=" + powerShotsFired + " tripleShotsFired=" + tripleShotsFired
            + " isUsingItem=" + settler.isUsingItem();
    }

    /** Cancels a pre-contact draw without emitting a release or spawning an arrow. */
    private void cancelDraw() {
        drawTicks = 0;
        if (settler.isUsingItem()
            && settler.getUsedItemHand() == InteractionHand.MAIN_HAND) {
            settler.stopUsingItem();
        }
    }

    // ------------------------------------------------------------ cadence ---

    /**
     * THE CADENCE, decided here and only here, at the moment a draw begins
     * (so the drawn-long pause telegraphs the Power Shot before it exists).
     * Volleys are counted on {@link #shotsFired}; the NEXT volley is number
     * {@code shotsFired + 1}:
     *
     * <pre>
     *   Power Shot  (SHARPSHOOTER+): every 4th  -> 4, 8, 12, 16, 20, ...
     *   Triple Shot (MASTER):        every 5th  -> 5, 10, 15,     ...
     * </pre>
     *
     * <p>They <b>never stack on the same shot</b>: where both cadences land
     * together (volley 20, 40 — every lcm(4,5) = 20th), the Power Shot wins
     * and the fan simply waits for its next multiple of five. A 2.5× piercing
     * shot that also fanned into three would stop being a special move and
     * start being the only move — the exact failure GuardRank.CLEAVE_SHARE's
     * doc exists to prevent.
     */
    private void planNextVolley() {
        ArcherRank rank = ArcherRank.of(settler);
        int next = shotsFired + 1;
        drawingPowerShot = rank.atLeast(ArcherRank.SHARPSHOOTER)
            && next % ArcherRank.POWER_SHOT_EVERY == 0;
        drawingTripleShot = !drawingPowerShot
            && rank.atLeast(ArcherRank.MASTER)
            && next % ArcherRank.TRIPLE_SHOT_EVERY == 0;
    }

    // ------------------------------------------------------------ loosing ---

    private boolean loose(ServerLevel level, LivingEntity target, boolean towerPostLocked) {
        if (QaTrace.ENABLED) {
            qaQuiverBeforeRelease = quiverCount();
            qaShotsBeforeRelease = shotsFired;
        }
        // A thinning quiver fans down honestly: a "triple" with one arrow
        // left is one arrow. Conservation beats spectacle.
        int requested = drawingTripleShot ? Math.min(3, quiverCount())
            : Math.min(1, quiverCount());
        UUID sourceTowerId = settler.archerQuiverSourceBuildingId();
        int arrows = settler.takeArcherQuiverArrows(requested);
        if (arrows <= 0) {
            if (QaTrace.ENABLED) qaRelease(target, "NO_AMMO", 0);
            return false;
        }
        ArcherRank rank = ArcherRank.of(settler);
        boolean power = drawingPowerShot;
        ItemStack weapon = power ? powerShotWeapon(level) : null;
        // Dexterity: -0..30% spread on top of the rank (plan/ATTRIBUTES.md).
        float inaccuracy = com.hearthstead.entity.AttributeRuntime.spread(settler,
            power ? ArcherRank.POWER_SHOT_INACCURACY
            : rank.atLeast(ArcherRank.MARKSMAN)
                ? ArcherRank.MARKSMAN_INACCURACY : ArcherRank.BASE_INACCURACY);
        // Height: a steadier shot from a tower, wall or roof (ArcherHeightAdvantage).
        inaccuracy *= (float) ArcherHeightAdvantage.spreadScale(
            ArcherHeightAdvantage.advantage(settler, target));

        int spawned = 0;
        for (int i = 0; i < arrows; i++) {
            float yawOffset = arrows == 1 ? 0.0F
                : (i - (arrows - 1) / 2.0F) * ArcherRank.TRIPLE_SHOT_YAW_DEGREES;
            if (spawnArrow(level, target, weapon, power, rank, inaccuracy, towerPostLocked,
                    yawOffset)) {
                spawned++;
            }
        }
        // addFreshEntity can fail during shutdown or another terminal world
        // transition. Any shaft that never entered the world remains owned
        // by the persisted quiver instead of becoming an invisible sink.
        if (arrows > spawned && sourceTowerId != null) {
            settler.storeArcherQuiverArrows(sourceTowerId, arrows - spawned);
        }
        if (spawned <= 0) {
            if (QaTrace.ENABLED) qaRelease(target, "SPAWN_REJECTED", 0);
            return false;
        }

        // The twang. Ordinary volleys are the vanilla arrow loose; the Power
        // Shot is the crossbow's heavier snap pitched far down -- a deeper
        // voice for a heavier shot (v1: pitch-shifted vanilla until the
        // sound pipeline grows a bespoke archer loose).
        if (power) {
            level.playSound(null, settler.blockPosition(), SoundEvents.CROSSBOW_SHOOT,
                SoundSource.NEUTRAL, 1.0F,
                0.65F + settler.getRandom().nextFloat() * 0.06F);
        } else if (com.hearthstead.settlement.techtree.effects.WatchEffects
                .crossbows(level, settler.settlement())) {
            // Crossbows: the heavy-bolt snap instead of the bow twang.
            level.playSound(null, settler.blockPosition(), SoundEvents.CROSSBOW_SHOOT,
                SoundSource.NEUTRAL, 1.0F, 0.9F + settler.getRandom().nextFloat() * 0.1F);
        } else {
            level.playSound(null, settler.blockPosition(), com.hearthstead.registry.ModSounds.WORK_BOW_LOOSE.get(),
                SoundSource.NEUTRAL, 1.0F,
                (drawingTripleShot ? 0.9F : 1.0F)
                    / (settler.getRandom().nextFloat() * 0.4F + 0.8F));
        }

        shotsFired++;
        if (power) {
            powerShotsFired++;
        } else if (drawingTripleShot) {
            tripleShotsFired++;
        }
        // One volley loosed is one unit of the trade done: train at the
        // moment the work completes (job standard point 8), spend effort the
        // same way -- and never GATE on effort here; combat is exempt, the
        // same "safety beats bookkeeping" rule GuardPatrolGoal documents.
        settler.train(Attribute.DEXTERITY, ArcherRank.TRAIN_SHOT);
        settler.spendEffort(1);
        if (QaTrace.ENABLED) qaRelease(target, "SPAWNED", spawned);
        return true;
    }

    /** A release attempt/cycle is not a hit or a complete conservation proof. */
    private void qaRelease(LivingEntity target, String result, int spawned) {
        QaTrace.event(settler, "ARCHER_RELEASE",
            "decision=RELEASE result=" + result + " spawned=" + spawned
                + " quiverBefore=" + qaQuiverBeforeRelease + " quiverAfter=" + quiverCount()
                + " shotsBefore=" + qaShotsBeforeRelease + " shotsAfter=" + shotsFired
                + " " + qaSnapshot(target));
    }

    private boolean spawnArrow(ServerLevel level, LivingEntity target,
                               @Nullable ItemStack weapon, boolean power,
                               ArcherRank rank, float inaccuracy, boolean towerPostLocked,
                               float yawOffsetDeg) {
        SettlerEntity archer = settler;
        // A real registered vanilla Arrow from a real item (the shaft this
        // call removed from the quiver's chest-true count). Hit training is
        // deliberately not an anonymous subclass hook: entity reload restores
        // the registered Arrow type and would silently lose that Java override.
        // CombatTerminalEvents trains only after the persisted owned-projectile
        // contact commits, so unload/restart and live hits share one authority.
        Arrow arrow = new Arrow(level, archer, new ItemStack(Items.ARROW), weapon);
        // The sanctioned consumption sink (see class doc): spent shafts are
        // used up, exactly like vanilla skeletons' -- retrievable arrows
        // would turn the tower into an arrow fountain.
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;

        double mult = 1.0D;
        if (rank.atLeast(ArcherRank.MARKSMAN)) {
            mult *= ArcherRank.MARKSMAN_DAMAGE_MULT;
        }
        if (power) {
            // 2.5x the rank's own ordinary shot: the multipliers compose, so
            // a sharpshooter's Power Shot is heavier than a recruit's would
            // ever have been -- experience shoots harder, the same rule as
            // GuardRank.MELEE_EDGE_PER_RANK.
            mult *= ArcherRank.POWER_SHOT_DAMAGE_MULT;
        }
        // Tech tree (Crossbows): heavy bolts, +40% per hit.
        mult *= com.hearthstead.settlement.techtree.effects.WatchEffects
            .archerDamageScale(level, archer.settlement());
        arrow.setBaseDamage(arrow.getBaseDamage() * mult);

        // The skeleton's own aim math, with the fan rotated in around Y.
        double dx = target.getX() - settler.getX();
        double dy = target.getY(0.3333) - arrow.getY();
        double dz = target.getZ() - settler.getZ();
        // Elevated shots (and Tower Post long shots) fly a solved arc; lead a
        // walking target by its current ground speed over the flight time.
        boolean elevatedShot = ArcherHeightAdvantage.advantage(settler, target) > 0.0D;
        boolean solvedArc = elevatedShot || towerPostLocked
            && settler.distanceTo(target) > NORMAL_SHOT_RANGE;
        if (solvedArc) {
            Vec3 lead = leadOffset(target, Math.sqrt(dx * dx + dz * dz));
            dx += lead.x;
            dz += lead.z;
        }
        if (yawOffsetDeg != 0.0F) {
            double a = Math.toRadians(yawOffsetDeg);
            double cos = Math.cos(a);
            double sin = Math.sin(a);
            double rx = dx * cos - dz * sin;
            double rz = dx * sin + dz * cos;
            dx = rx;
            dz = rz;
        }
        double flat = Math.sqrt(dx * dx + dz * dz);
        // The ordinary 18-block volley keeps its original vanilla-style aim.
        // Beyond that range, an Archer physically holding an active Tower Post
        // uses the same real Arrow, speed, gravity and collision pipeline, but
        // solves its lower air arc instead of aiming several blocks short.
        boolean towerLongShot = solvedArc && flat > 1.0E-4D;
        double aimY = towerLongShot ? towerBallisticVerticalInput(flat, dy) : dy + flat * 0.2D;
        arrow.shoot(dx, aimY, dz, (float) ARROW_SPEED, inaccuracy);
        // The physical arrow carries its own bounded ownership/contact ledger.
        // If this evidence write ever fails, gameplay still fires the shaft;
        // only terminal telemetry fails closed.
        OwnedProjectileLedger.issue(arrow, archer);
        return level.addFreshEntity(arrow);
    }

    /** Most lead (blocks) ever added for a moving target. */
    private static final double MAX_LEAD = 3.0D;

    /**
     * Horizontal lead for a walking target: its current ground velocity times
     * the approximate arrow flight time (vanilla speed with air drag), capped.
     * Aim only; the arrow itself stays a plain vanilla Arrow.
     */
    static Vec3 leadOffset(LivingEntity target, double horizontalDistance) {
        Vec3 v = target.getDeltaMovement();
        double ticks = horizontalDistance / (ARROW_SPEED * .95D);
        Vec3 lead = new Vec3(v.x * ticks, 0.0D, v.z * ticks);
        double length = lead.length();
        if (!Double.isFinite(length)) return Vec3.ZERO;
        return length > MAX_LEAD ? lead.scale(MAX_LEAD / length) : lead;
    }

    /**
     * Returns the vertical input for {@link AbstractArrow#shoot(double, double,
     * double, float, float)} that reaches {@code targetDeltaY} at the supplied
     * horizontal distance. The integration mirrors vanilla Arrow flight in air:
     * position first, then {@code velocity * .99}, then {@code -0.05Y} gravity.
     *
     * <p>This is deliberately an aim correction, not extra range, velocity,
     * damage or target authority. Callers retain the normal LOS and physical
     * projectile collision checks.</p>
     */
    public static double towerBallisticVerticalInput(double horizontalDistance,
                                                      double targetDeltaY) {
        if (!Double.isFinite(horizontalDistance) || !Double.isFinite(targetDeltaY)
            || horizontalDistance <= 1.0E-4D) {
            return targetDeltaY;
        }
        double fallback = targetDeltaY + horizontalDistance * .2D;
        // Height is not monotonic across every possible launch angle: an
        // over-steep upper endpoint is already on the descending/high arc and
        // can falsely report a reachable raised target as unreachable. Scan
        // the bounded physical angles for the *first* upward crossing, then
        // bisect only that lower-arc bracket. At 70 degrees an Arrow still has
        // enough horizontal speed to cross the Tower Post's 32-block range.
        double lowerAngle = TOWER_BALLISTIC_MIN_ANGLE;
        double lowerInput = horizontalDistance
            * Math.tan(Math.toRadians(lowerAngle));
        double lowerHeight = towerBallisticHeightAtDistance(horizontalDistance, lowerInput);
        boolean bracketed = false;
        double upperAngle = lowerAngle;
        for (double angle = lowerAngle + TOWER_BALLISTIC_ANGLE_STEP;
             angle <= TOWER_BALLISTIC_MAX_ANGLE + 1.0E-8D;
             angle += TOWER_BALLISTIC_ANGLE_STEP) {
            double input = horizontalDistance * Math.tan(Math.toRadians(angle));
            double height = towerBallisticHeightAtDistance(horizontalDistance, input);
            if (Double.isFinite(lowerHeight) && Double.isFinite(height)
                && lowerHeight <= targetDeltaY && height >= targetDeltaY) {
                upperAngle = angle;
                bracketed = true;
                break;
            }
            lowerAngle = angle;
            lowerInput = input;
            lowerHeight = height;
        }
        if (!bracketed) return fallback;
        for (int step = 0; step < TOWER_BALLISTIC_SOLVE_STEPS; step++) {
            double middleAngle = (lowerAngle + upperAngle) * .5D;
            double middle = horizontalDistance * Math.tan(Math.toRadians(middleAngle));
            double height = towerBallisticHeightAtDistance(horizontalDistance, middle);
            if (!Double.isFinite(height)) return fallback;
            if (height < targetDeltaY) lowerAngle = middleAngle;
            else upperAngle = middleAngle;
        }
        return horizontalDistance * Math.tan(Math.toRadians((lowerAngle + upperAngle) * .5D));
    }

    private static double towerBallisticHeightAtDistance(double horizontalDistance,
                                                          double verticalInput) {
        double length = Math.hypot(horizontalDistance, verticalInput);
        if (!Double.isFinite(length) || length <= 1.0E-8D) return Double.NaN;
        double horizontalVelocity = ARROW_SPEED * horizontalDistance / length;
        double verticalVelocity = ARROW_SPEED * verticalInput / length;
        double x = 0.0D, y = 0.0D;
        for (int tick = 0; tick < TOWER_BALLISTIC_FLIGHT_TICKS; tick++) {
            double nextX = x + horizontalVelocity;
            double nextY = y + verticalVelocity;
            if (nextX >= horizontalDistance) {
                double fraction = (horizontalDistance - x) / (nextX - x);
                return y + (nextY - y) * fraction;
            }
            x = nextX;
            y = nextY;
            horizontalVelocity *= ARROW_AIR_DRAG;
            verticalVelocity = verticalVelocity * ARROW_AIR_DRAG - ARROW_GRAVITY;
        }
        return Double.NaN;
    }

    /**
     * The transient weapon a Power Shot is "fired from". Punch and Piercing
     * ride the vanilla enchantment pipeline ({@code AbstractArrow} reads the
     * firing weapon's enchantments for knockback and pierce count), so the
     * strong knockback and the pierce-one behave exactly like their vanilla
     * selves -- resistance, deflection and all -- instead of a hand-rolled
     * shove. The stack exists only on the arrow; nothing ever holds it.
     */
    private ItemStack powerShotWeapon(ServerLevel level) {
        Registry<Enchantment> enchantments =
            level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        ItemStack bow = new ItemStack(Items.BOW);
        bow.enchant(enchantments.getHolderOrThrow(Enchantments.PUNCH),
            ArcherRank.POWER_SHOT_PUNCH);
        bow.enchant(enchantments.getHolderOrThrow(Enchantments.PIERCING),
            ArcherRank.POWER_SHOT_PIERCE);
        return bow;
    }

    // ------------------------------------------------------------- quiver ---

    /** The WATCHTOWER that employs this archer, or null. Derived per call
     *  (D-011): employment lives on the building, never cached here. */
    @Nullable
    private Building tower() {
        return towerOf(settler);
    }

    /** The WATCHTOWER that employs {@code settler}, or null (derived per call). */
    @Nullable
    public static Building towerOf(SettlerEntity settler) {
        Settlement settlement = settler.settlement();
        if (settlement == null) {
            return null;
        }
        Building employer = Employment.employerOf(settlement, settler.getUUID());
        return employer != null && employer.valid
            && employer.type == BuildingType.WATCHTOWER ? employer : null;
    }

    private boolean quiverBelongsToCurrentTower() {
        Building employer = tower();
        return employer != null && settler.archerQuiverOwnedBy(employer.id);
    }

    private boolean nearTower(Building tower) {
        return nearTower(settler, tower);
    }

    /** Whether {@code settler} stands within reach of {@code tower}'s rack. */
    public static boolean nearTower(SettlerEntity settler, Building tower) {
        if (tower.bounds != null
            && tower.bounds.inflatedBy(RESTOCK_REACH).isInside(settler.blockPosition())) {
            return true;
        }
        return tower.anchor != null
            && settler.blockPosition().closerThan(tower.anchor, RESTOCK_REACH);
    }

    private void walkTowardsTower(ServerLevel level) {
        if (com.hearthstead.settlement.guard.BannerTeams.active(settler) != null) {
            settler.getNavigation().stop();
            return;
        }
        Building tower = tower();
        if (tower == null || tower.anchor == null || nearTower(tower)) {
            return;
        }
        navigateWithinOrder(level, Vec3.atBottomCenterOf(tower.anchor), 1.1);
    }

    /** Movement authority is narrower than urgent target eligibility. */
    private void refreshMovementOrder(ServerLevel level) {
        if (com.hearthstead.settlement.guard.BannerTeams.active(settler) != null) {
            movementOrder = null;
            movementRevision = -1;
            movementBlocked = false;
            return;
        }
        Settlement settlement = settler.settlement();
        GuardOrder next = null;
        boolean blocked = false;
        if (settlement != null) {
            var validation = GuardAssignmentService.validate(level, settlement, settler, true);
            boolean persisted = settlement.guardOrders.order(settler.getUUID()).isPresent()
                || settlement.guardOrders.quarantined()
                || validation.reason() == GuardAssignmentService.InvalidReason.LEGACY_PENDING;
            blocked = persisted
                && validation.reason() != GuardAssignmentService.InvalidReason.NONE;
            if (!blocked && validation.order().isPresent()) {
                GuardOrder order = validation.order().orElseThrow();
                GuardOrder.Mode mode = order.modeAt(level.getGameTime());
                if (mode == GuardOrder.Mode.STAND_POST || mode == GuardOrder.Mode.TOWER_POST) {
                    next = order;
                }
            }
        }
        // A player summons the worker away from the post. Keep the persisted
        // order intact, but release this goal's local movement clamp and
        // Tower Post coverage until the live summons request is consumed.
        if (next != null && next.modeAt(level.getGameTime()) == GuardOrder.Mode.TOWER_POST
            && Summons.active(settler)) {
            next = null;
        }
        int revision = next == null ? -1 : next.revision();
        if (next != movementOrder || revision != movementRevision || blocked != movementBlocked) {
            settler.getNavigation().stop();
            movementRetryAt = 0;
        }
        movementOrder = next;
        movementRevision = revision;
        movementBlocked = blocked;
        Path current = settler.getNavigation().getPath();
        if (next != null && current != null && !pathWithinOrder(current)) {
            settler.getNavigation().stop();
        }
    }

    private boolean insideMovementRegion(BlockPos pos) {
        return movementOrder != null && movementOrder.pos().isPresent()
            && pos.distSqr(movementOrder.pos().orElseThrow())
                <= (double) movementOrder.leashRadius() * movementOrder.leashRadius();
    }

    /** A displaced entity may take a bounded outside prefix, then never re-exit. */
    private boolean pathWithinOrder(Path path) {
        boolean entered = insideMovementRegion(settler.blockPosition());
        BlockPos anchor = movementOrder.pos().orElseThrow();
        double envelope = Math.max(movementOrder.leashRadius(),
            Math.sqrt(settler.blockPosition().distSqr(anchor))) + 1.0;
        if (path.getNodeCount() - path.getNextNodeIndex() > 64) return false;
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            BlockPos pos = path.getNode(i).asBlockPos();
            boolean inside = insideMovementRegion(pos);
            if (entered && !inside || pos.distSqr(anchor) > envelope * envelope) return false;
            entered |= inside;
        }
        return entered && path.getEndNode() != null
            && insideMovementRegion(path.getEndNode().asBlockPos());
    }

    private void navigateWithinOrder(ServerLevel level, Vec3 desired, double speed) {
        if (com.hearthstead.settlement.guard.BannerTeams.active(settler) != null) {
            BlockPos anchor = com.hearthstead.settlement.guard.BannerTeams.anchor(settler);
            if (anchor == null) return;
            Vec3 center = Vec3.atBottomCenterOf(anchor);
            Vec3 delta = desired.subtract(center);
            Vec3 bounded = delta.lengthSqr() > 36 ? center.add(delta.normalize().scale(6)) : desired;
            if (level.getGameTime() < movementRetryAt) return;
            movementRetryAt = level.getGameTime() + LOS_REPOSITION_INTERVAL;
            Path path = settler.getNavigation().createPath(BlockPos.containing(bounded), 0);
            boolean entered = settler.blockPosition().distSqr(anchor) <= 49;
            boolean valid = path != null && path.canReach();
            if (valid) for (int i=0;i<path.getNodeCount();i++) {
                boolean inside = path.getNodePos(i).distSqr(anchor) <= 49;
                if (entered && !inside) { valid=false; break; }
                entered |= inside;
            }
            if (valid && entered) settler.getNavigation().moveTo(path, speed);
            else settler.getNavigation().stop();
            return;
        }
        if (movementOrder == null) {
            settler.getNavigation().moveTo(desired.x, desired.y, desired.z, speed);
            return;
        }
        if (level.getGameTime() < movementRetryAt) return;
        movementRetryAt = level.getGameTime() + LOS_REPOSITION_INTERVAL;
        Vec3 center = Vec3.atBottomCenterOf(movementOrder.pos().orElseThrow());
        Vec3 offset = desired.subtract(center);
        double inset = Math.max(0.5, movementOrder.leashRadius() - 1.0);
        Vec3 preferred = offset.lengthSqr() > inset * inset
            ? center.add(offset.normalize().scale(inset)) : desired;
        Vec3 toward = preferred.subtract(settler.position()).multiply(1, 0, 1).normalize();
        if (toward.lengthSqr() < 0.01) {
            toward = desired.subtract(settler.position()).multiply(1, 0, 1).normalize();
        }
        Vec3 side = new Vec3(-toward.z, 0, toward.x).scale(3);
        Vec3[] candidates = {preferred, settler.position().add(side),
            settler.position().subtract(side), center};
        for (Vec3 candidate : candidates) {
            BlockPos base = BlockPos.containing(candidate);
            for (int dy : new int[]{0, 1, -1}) {
                BlockPos feet = base.offset(0, dy, 0);
                if (feet.equals(settler.blockPosition())
                    || !insideMovementRegion(feet) || !level.isLoaded(feet)
                    || !level.isLoaded(feet.below())
                    || !level.getFluidState(feet).isEmpty()) continue;
                // Collision shapes retain slabs/stairs; require physical support
                // and clear body space rather than assuming two air blocks.
                var support = level.getBlockState(feet.below()).getCollisionShape(level, feet.below());
                if (support.isEmpty()) continue;
                double floorY = feet.getY() - 1 + support.max(net.minecraft.core.Direction.Axis.Y);
                Vec3 landing = new Vec3(feet.getX() + 0.5, floorY, feet.getZ() + 0.5);
                if (!level.noCollision(settler, settler.getBoundingBox().move(
                    landing.subtract(settler.position())))) continue;
                Path path = settler.getNavigation().createPath(feet, 0);
                if (path != null && path.canReach() && path.getEndNode() != null
                    && !path.getEndNode().asBlockPos().equals(settler.blockPosition())
                    && pathWithinOrder(path)
                    && settler.getNavigation().moveTo(path, speed)) return;
            }
        }
        settler.getNavigation().stop();
    }

    /**
     * "No arrows in the tower = no shooting" is the correct call (chest
     * truth) -- but saying nothing about it is the defect the owner's bug
     * report actually named: a hired archer standing at post, target in
     * sight, doing nothing, gives the player zero signal that the mod is
     * behaving correctly rather than being broken.
     *
     * <h2>Starving speaks</h2>
     *
     * <p>Two channels, both bounded to ONE continuous starvation episode by
     * {@link #outOfAmmoAnnounced} (cleared the instant the rack has arrows
     * again, in {@code tick()}, retained across goal restarts):
     *
     * <ul>
     *   <li>The settler's {@code SettlerActivity} flips to
     *       {@link SettlerActivity#OUT_OF_AMMO} every tick this branch runs
     *       -- cheap and idempotent, so it stays true, not just "was true
     *       once" -- which the nameplate/sheet already render for free
     *       through {@code SettlerActivity#displayName()}
     *       ({@code SettlerRenderer} reads {@code getActivity()} generically,
     *       no per-activity renderer code needed).
     *   <li>A chat line to players near enough to plausibly be watching this
     *       archer ({@link #ANNOUNCE_RANGE}), fired ONCE per episode -- the
     *       same "say it, don't spam it" shape {@code RaidBroadcast} uses
     *       for settlement-wide lines, narrowed here to a post-specific
     *       radius rather than the whole settlement.
     * </ul>
     */
    private void reportOutOfAmmo(ServerLevel level) {
        // Owner, 27 Sep: no chat line, a bubble over his head instead. The
        // cue is the arrow bubble SettlerThoughtBubble draws for the synced
        // OUT_OF_AMMO activity. The episode flag stays as the test seam.
        settler.setActivity(SettlerActivity.OUT_OF_AMMO);
        outOfAmmoAnnounced = true;
    }

    /**
     * Fills the quiver from the tower's own chests, arrow for arrow.
     *
     * <p>Directly supplied plain bag arrows are enlisted first. Rack fallback
     * still reads only a container inside the WATCHTOWER's
     * scanned bounds ({@link WarehouseIndex#containers} -- a doubly bounded
     * walk), the only quantity is what was physically removed, and an
     * archer out of reach of the rack gets nothing. This is the consumer
     * end of FLOWS' fletcher edge: arrows leave the economy here.
     *
     * @return whether the quiver now holds anything
     */
    private boolean restock(ServerLevel level) {
        Building tower = tower();
        if (tower == null) return false;
        if (quiverCount() <= 0) loadSuppliedBagArrows(tower);
        if (!nearTower(tower)) return settler.archerQuiverOwnedBy(tower.id);
        if (quiverCount() > 0) {
            return settler.archerQuiverOwnedBy(tower.id);
        }
        return refillFromRack(level, settler, tower) >= 0
            && settler.archerQuiverOwnedBy(tower.id);
    }

    /**
     * Tops the quiver up to {@link #quiverCapacity(SettlerEntity)} from the
     * tower's own chests, arrow for arrow (exact custody: every accepted
     * arrow is removed from the chest in the same step). The caller must
     * already have checked reach. Returns how many arrows moved.
     */
    public static int refillFromRack(ServerLevel level, SettlerEntity settler, Building tower) {
        if (settler.archerQuiverCount() > 0 && !settler.archerQuiverOwnedBy(tower.id)) {
            return 0;
        }
        int need = quiverCapacity(settler) - settler.archerQuiverCount();
        int moved = 0;
        for (BlockPos pos : WarehouseIndex.containers(level, tower)) {
            if (need <= 0) {
                break;
            }
            if (!(level.getBlockEntity(pos) instanceof Container chest)) {
                continue;
            }
            for (int slot = 0; slot < chest.getContainerSize() && need > 0; slot++) {
                ItemStack stack = chest.getItem(slot);
                if (!stack.is(Items.ARROW)) {
                    continue;
                }
                int n = Math.min(need, stack.getCount());
                int accepted = settler.storeArcherQuiverArrows(tower.id, n);
                if (accepted <= 0) {
                    return moved;
                }
                stack.shrink(accepted);
                if (stack.isEmpty()) {
                    chest.setItem(slot, ItemStack.EMPTY);
                }
                chest.setChanged();
                need -= accepted;
                moved += accepted;
            }
        }
        return moved;
    }

    /** Plain arrows currently in {@code tower}'s rack (bounded container walk). */
    public static int rackArrows(ServerLevel level, Building tower) {
        int total = 0;
        for (BlockPos pos : WarehouseIndex.containers(level, tower)) {
            if (!(level.getBlockEntity(pos) instanceof Container chest)) {
                continue;
            }
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (stack.is(Items.ARROW)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    /**
     * Directly supplied personal ammunition is already physically at the
     * Archer. Enlist only plain arrows into the current employer's bounded
     * quiver; named/component-bearing arrows remain intact in the bag because
     * this legacy integer quiver cannot preserve their individual components.
     * Transfers use the same persisted quiver and dismissal/death lifecycle
     * as rack stock, without accessing any remote container.
     */
    private void loadSuppliedBagArrows(Building tower) {
        if (quiverCount() != 0) return;
        int need = quiverCapacity(settler);
        for (int slot = 0; slot < settler.bag.getContainerSize() && need > 0; slot++) {
            ItemStack stack = settler.bag.getItem(slot);
            if (!stack.is(Items.ARROW) || !stack.getComponentsPatch().isEmpty()) continue;
            int accepted = settler.storeArcherQuiverArrows(tower.id, Math.min(need, stack.getCount()));
            if (accepted <= 0) break;
            stack.shrink(accepted);
            if (stack.isEmpty()) settler.bag.setItem(slot, ItemStack.EMPTY);
            settler.bag.setChanged();
            need -= accepted;
        }
    }

    /** Puts unspent arrows back in the rack -- the reverse of
     *  {@link #restock}, with the same reach rule and the same exactness.
     *  Whatever no chest has room for stays honestly in the quiver. */
    private void returnUnspent(ServerLevel level) {
        Settlement settlement = settler.settlement();
        Building source = exactSourceTower(settlement,
            settler.archerQuiverSourceBuildingId());
        if (quiverCount() <= 0 || source == null || !nearTower(source)) {
            return;
        }
        returnToRack(level, source, settler);
    }

    /**
     * Materializes every borrowed shaft before employment authority changes.
     * The exact source rack gets first refusal even if the Archer has walked
     * away. A missing/destroyed/full rack falls back to one visible world
     * stack at the settler. Before a terminal death can remove the entity, a
     * rejected world spawn transfers the exact stack into the persistent
     * death-drop ledger. State is cleared only after the rack, physical entity
     * or durable retry row owns the corresponding arrows.
     */
    public static boolean releaseBorrowedArrows(ServerLevel level,
                                                Settlement settlement,
                                                SettlerEntity settler) {
        if (settler == null || settler.archerQuiverCount() <= 0) {
            return true;
        }
        Building source = exactSourceTower(settlement,
            settler.archerQuiverSourceBuildingId());
        if (source != null) {
            returnToRack(level, source, settler);
        }
        int remainder = settler.archerQuiverCount();
        if (remainder <= 0) {
            return true;
        }
        DeferredItemMaterializationSavedData drops =
            DeferredItemMaterializationSavedData.get(level);
        UUID transfer = drops.queue(level, settler.getX(),
            settler.getY() + 0.3D, settler.getZ(),
            new ItemStack(Items.ARROW, remainder));
        if (transfer == null) {
            // Capacity/quarantine refusal happens before the source changes.
            // A dying entity's tickDeath override will retry instead of
            // allowing the corpse (and its still-owned shafts) to disappear.
            return false;
        }
        int removed = settler.takeArcherQuiverArrows(remainder);
        if (removed != remainder || settler.archerQuiverCount() != 0
            || settler.archerQuiverSourceBuildingId() != null) {
            // The durable row was staged while the settler still owned the
            // shafts. If the exact source clear ever fails, roll it back so
            // the same arrows never have two authorities.
            drops.cancel(transfer);
            return false;
        }
        // Rejection is not failure now: the persisted row remains the sole
        // owner and the level-tick retry will materialize it later.
        drops.materialize(level, transfer);
        return true;
    }

    @Nullable
    private static Building exactSourceTower(@Nullable Settlement settlement,
                                             @Nullable UUID sourceId) {
        if (settlement == null || sourceId == null) {
            return null;
        }
        Building found = null;
        for (Building candidate : settlement.buildings) {
            if (!sourceId.equals(candidate.id)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = candidate;
        }
        return found != null && found.valid
            && found.type == BuildingType.WATCHTOWER ? found : null;
    }

    private static void returnToRack(ServerLevel level, Building tower,
                                     SettlerEntity settler) {
        for (BlockPos pos : WarehouseIndex.containers(level, tower)) {
            if (settler.archerQuiverCount() <= 0) {
                break;
            }
            if (!(level.getBlockEntity(pos) instanceof Container chest)) {
                continue;
            }
            for (int slot = 0; slot < chest.getContainerSize()
                    && settler.archerQuiverCount() > 0; slot++) {
                ItemStack stack = chest.getItem(slot);
                if (stack.isEmpty()) {
                    int n = Math.min(settler.archerQuiverCount(),
                        new ItemStack(Items.ARROW).getMaxStackSize());
                    chest.setItem(slot, new ItemStack(Items.ARROW, n));
                    chest.setChanged();
                    settler.takeArcherQuiverArrows(n);
                } else if (stack.is(Items.ARROW)
                    && stack.getCount() < stack.getMaxStackSize()) {
                    int n = Math.min(settler.archerQuiverCount(),
                        stack.getMaxStackSize() - stack.getCount());
                    stack.grow(n);
                    chest.setChanged();
                    settler.takeArcherQuiverArrows(n);
                }
            }
        }
    }

    // ---------------------------------------------------------- targeting ---

    /**
     * The exact same bounded coordinator used by the entity target selector.
     * This fallback exists for selector ordering and direct tests only; it may
     * not invent a private nearest-hostile policy.
     */
    @Nullable
    private LivingEntity acquire() {
        return coordinatedTargeting.acquireNow();
    }

    /** Exact counter seam used both by projectile construction and unit QA. */
    public static double counterDamageMultiplier(LivingEntity target) {
        return target instanceof RaiderEntity raider
            && !raider.isCaptain()
            && raider.variant() == RaiderEntity.Variant.SKIRMISHER
                ? COUNTER_DAMAGE_MULTIPLIER : 1.0D;
    }

    /** Pure overload: no world/entity fixture needed to pin the design rule. */
    public static double counterDamageMultiplier(
            RaiderEntity.Variant variant, boolean captain) {
        return !captain && variant == RaiderEntity.Variant.SKIRMISHER
            ? COUNTER_DAMAGE_MULTIPLIER : 1.0D;
    }

    // --------------------------------------------------------- test seams ---

    /** Volleys loosed since this goal was constructed (a triple counts once). */
    public int shotsFired() {
        return shotsFired;
    }

    /** How many of those volleys were Power Shots. */
    public int powerShotsFired() {
        return powerShotsFired;
    }

    /** How many of those volleys were Triple Shots (a 4th-multiple always
     *  wins the slot instead -- see {@link #planNextVolley}). */
    public int tripleShotsFired() {
        return tripleShotsFired;
    }

    /** Arrows currently in hand. */
    public int quiverCount() {
        return settler.archerQuiverCount();
    }

    /** Whether the current starvation episode (if any) has already sent its
     *  one player-facing line -- see {@link #reportOutOfAmmo}. */
    public boolean outOfAmmoAnnounced() {
        return outOfAmmoAnnounced;
    }
}
