package com.hearthstead.entity.ai;

import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidEscalation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Bandit morale: once half of the band's original participants are down
 * ({@link RaidEscalation#bandBroken}), every living ordinary bandit breaks
 * and runs away from the settlement centre, then quietly leaves the world
 * when it is far enough out and no player can see it. Bandit captains never
 * flee. The break is one-way and runtime-only.
 *
 * <p>{@code discard()} is the only ledger touch: the raid lane's removal path
 * records the fled bandit as terminal for whichever raid ledger owns it.
 */
public final class RaiderMoraleGoal extends Goal {

    public static final int CHECK_INTERVAL_TICKS = 20;
    public static final double FLEE_SPEED = 1.35D;
    /** Blocks beyond the settlement radius before a fled bandit may vanish. */
    public static final double VANISH_MARGIN = 24.0D;
    /** No player this close (or with sight of it) may watch it vanish. */
    public static final double UNSEEN_RADIUS = 24.0D;
    /** After this long fleeing, any unseen bandit may vanish wherever it is. */
    public static final int GIVE_UP_TICKS = 600;

    private final RaiderEntity raider;
    private boolean broken;
    private long nextCheckTick = Long.MIN_VALUE;
    private long brokeAtTick = Long.MIN_VALUE;
    private long nextPathTick = Long.MIN_VALUE;
    private Boolean forcedBroken;

    public RaiderMoraleGoal(RaiderEntity raider) {
        this.raider = raider;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean eligible() {
        return raider.isBandit() && !raider.isCaptain() && raider.isAlive();
    }

    @Override
    public boolean canUse() {
        if (!eligible() || !(raider.level() instanceof ServerLevel level)) {
            return false;
        }
        if (broken) {
            return true;
        }
        long now = level.getGameTime();
        if (now < nextCheckTick) {
            return false;
        }
        nextCheckTick = now + CHECK_INTERVAL_TICKS;
        boolean bandBroken = forcedBroken != null ? forcedBroken
            : RaidEscalation.bandBroken(raider.settlement());
        if (bandBroken) {
            broken = true;
            brokeAtTick = now;
        }
        return broken;
    }

    @Override
    public boolean canContinueToUse() {
        return broken && eligible();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        raider.cancelPendingMeleeMove();
        raider.setTarget(null);
        raider.setAggressive(false);
        nextPathTick = Long.MIN_VALUE;
    }

    @Override
    public void tick() {
        if (!(raider.level() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getGameTime();
        // A fleeing bandit does not turn back to fight, even if hit.
        raider.setTarget(null);
        Settlement settlement = raider.settlement();
        Vec3 centre = settlement == null ? raider.position()
            : Vec3.atCenterOf(settlement.center);
        Vec3 away = new Vec3(raider.getX() - centre.x, 0.0D, raider.getZ() - centre.z);
        double out = away.length();
        if (out < 1.0E-3D) {
            double a = raider.getRandom().nextDouble() * Math.PI * 2.0D;
            away = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
        } else {
            away = away.scale(1.0D / out);
        }
        if (now >= nextPathTick) {
            nextPathTick = now + 10L;
            Vec3 goal = raider.position().add(away.scale(16.0D));
            raider.getNavigation().moveTo(goal.x, goal.y, goal.z, FLEE_SPEED);
        }
        raider.getLookControl().setLookAt(raider.getX() + away.x * 4.0D,
            raider.getEyeY(), raider.getZ() + away.z * 4.0D);

        double vanishAt = (settlement == null ? 0.0D : settlement.radius) + VANISH_MARGIN;
        boolean farEnough = out >= vanishAt || now - brokeAtTick >= GIVE_UP_TICKS;
        if (farEnough && unseen(level)) {
            // Terminal through the raid lane's removal path.
            raider.discard();
        }
    }

    private boolean unseen(ServerLevel level) {
        for (Player player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            if (player.distanceToSqr(raider) < UNSEEN_RADIUS * UNSEEN_RADIUS
                || player.hasLineOfSight(raider)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void stop() {
        raider.getNavigation().stop();
    }

    // ------------------------------------------------ test seams / evidence

    /** Deterministic QA seam: treat the band as broken (true) or intact (false). */
    public void forceBandBroken(Boolean value) {
        this.forcedBroken = value;
        this.nextCheckTick = Long.MIN_VALUE;
    }

    public boolean isFleeing() {
        return broken;
    }
}
