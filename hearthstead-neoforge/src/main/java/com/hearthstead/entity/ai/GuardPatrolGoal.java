package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/** Guards walk a ring of waypoints around the hearth, pausing to scan. */
public class GuardPatrolGoal extends Goal {
    private static final int WAYPOINTS = 8;
    private static final double WAYPOINT_REACH_SQR = 4.0D;
    private static final int FAILED_WAYPOINT_RETRY_TICKS = 20;
    private static final int FAILURE_REPORT_THRESHOLD = 3;

    private final SettlerEntity settler;
    private int waypointIndex;
    private int pauseTicks;
    private BlockPos currentWaypoint;
    private int failedWaypoints;
    private int retryWaypointIn;

    /**
     * Vaktdrill's multiplier on the peacetime drill (RESEARCH-1's handoff).
     * Combat training is deliberately NOT multiplied: the project is a
     * drill yard, not a war.
     */
    private float drillBonus() {
        com.hearthstead.settlement.Settlement settlement = settler.settlement();
        if (!(settler.level() instanceof net.minecraft.server.level.ServerLevel level)
            || settlement == null) {
            return 1.0F;
        }
        return com.hearthstead.settlement.research.Research.bonus(level, settlement.id,
            com.hearthstead.settlement.research.ResearchKey.GUARD_TRAINING);
    }

    public GuardPatrolGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    /**
     * Who walks the rounds between fights: Guards, and the battle roles that
     * fight on foot (J-02: Spearman, Longswordsman, Rune Mage used to only
     * stroll). They share the Guards' watch rota ({@link Schedule#onWatch}),
     * salute and sleep. With the battle-roles switch off the roles stand down.
     */
    public static boolean patrols(Profession profession) {
        if (profession == Profession.GUARD) {
            return true;
        }
        return (profession == Profession.SPEARMAN || profession == Profession.LONGSWORDSMAN
                || profession == Profession.RUNE_MAGE)
            && com.hearthstead.entity.combat.role.RoleCombat.enabled();
    }

    @Override
    public boolean canUse() {
        if (!patrols(settler.getProfession())
            || settler.getTarget() != null) {
            return false;
        }
        Settlement settlement = settler.settlement();
        // A guard walking a player-drawn patrol route (PatrolRouteGoal) is
        // off these rounds; everyone else keeps them.
        if (com.hearthstead.settlement.guard.patrol.PatrolService.assigned(settler)) {
            return false;
        }
        // Half the garrison stands the night watch. Off-watch guards fall
        // through to the ordinary day -- meals, the tavern, their own bed.
        return settlement != null
            && Schedule.onWatch(settlement, settler, settler.dayPhase());
    }

    @Override
    public void start() {
        settler.setActivity(SettlerActivity.PATROLLING);
        failedWaypoints = 0;
        retryWaypointIn = 0;
        nextWaypoint();
    }

    /**
     * The same waypoint drill for a guard walking a player-drawn route
     * (PatrolRouteGoal): stamina, the Strength rep and the armour foley.
     */
    public static void drillAtWaypoint(SettlerEntity settler) {
        new GuardPatrolGoal(settler).reachedWaypoint();
    }

    /**
     * A guard's work is the walking, so that is what is counted and what is
     * heard: one waypoint reached is one unit (job standard, point 8), and the
     * armour answers at each one (point 6) — a patrol you can hear passing
     * outside at night is worth more than one you can only see.
     */
    private void reachedWaypoint() {
        settler.train(com.hearthstead.entity.Attribute.STAMINA, 1.0F);
        // Being seen on the rounds is what builds a guard's Presence, their
        // secondary since the attributes rework (plan/ATTRIBUTES.md).
        settler.train(com.hearthstead.entity.Attribute.PRESENCE, 0.5F);
        // The peacetime drill: rank reads STRENGTH (GuardRank.of), so the
        // guard's own rounds must train it — without this, only lumberjacks
        // and miners trained Strength and a career guard could never leave
        // RECRUIT. Every waypoint is one rep; the arithmetic (SPEARMAN's
        // threshold 20 in ~3.5-4 in-game days of full-time patrols, with
        // combat paying 5x per event) lives on GuardRank.TRAIN_DRILL.
        settler.train(com.hearthstead.entity.Attribute.STRENGTH,
            com.hearthstead.entity.GuardRank.TRAIN_DRILL * drillBonus());
        settler.spendEffort(1);
        if (hasAudibleArmor()
                && settler.level() instanceof net.minecraft.server.level.ServerLevel level) {
            level.playSound(null, settler.blockPosition(),
                com.hearthstead.registry.ModSounds.ARMOUR_CLINK.get(),
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.55F,
                0.94F + settler.getRandom().nextFloat() * 0.12F);
        }
    }

    /** Leather, turtle shell and empty slots do not produce metal equipment foley. */
    private boolean hasAudibleArmor() {
        for (net.minecraft.world.item.ItemStack stack : settler.getArmorSlots()) {
            if (!(stack.getItem() instanceof net.minecraft.world.item.ArmorItem armor)) continue;
            var material = armor.getMaterial();
            if (material.equals(net.minecraft.world.item.ArmorMaterials.IRON)
                || material.equals(net.minecraft.world.item.ArmorMaterials.CHAIN)
                || material.equals(net.minecraft.world.item.ArmorMaterials.GOLD)
                || material.equals(net.minecraft.world.item.ArmorMaterials.DIAMOND)
                || material.equals(net.minecraft.world.item.ArmorMaterials.NETHERITE)) return true;
        }
        return false;
    }

    private void nextWaypoint() {
        Settlement s = settler.settlement();
        if (s == null) {
            return;
        }
        waypointIndex = (waypointIndex + 1) % WAYPOINTS;
        double angle = waypointIndex * (Math.PI * 2 / WAYPOINTS);
        double r = s.radius * 0.55;
        int x = s.center.getX() + (int) (Math.cos(angle) * r);
        int z = s.center.getZ() + (int) (Math.sin(angle) * r);
        BlockPos surface = settler.level().getHeightmapPos(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, s.center.getY(), z));
        currentWaypoint = surface;
        boolean moving = settler.getNavigation().moveTo(surface.getX() + 0.5,
            surface.getY(), surface.getZ() + 0.5, 0.9);
        if (!moving && settler.blockPosition().distSqr(surface)
                > WAYPOINT_REACH_SQR) {
            rejectUnreachedWaypoint();
            return;
        }
        pauseTicks = 0;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        if (currentWaypoint == null) {
            if (--retryWaypointIn <= 0) {
                nextWaypoint();
            }
            return;
        }
        if (settler.getNavigation().isDone()) {
            // Navigation being done is not proof that the guard arrived. A
            // failed/partial path used to award drill progression while the
            // guard stood still, and could do so forever on bad terrain.
            if (settler.blockPosition().distSqr(currentWaypoint)
                    > WAYPOINT_REACH_SQR) {
                rejectUnreachedWaypoint();
                return;
            }
            if (pauseTicks == 0) {
                reachedWaypoint();
                failedWaypoints = 0;
            }
            pauseTicks++;
            if (pauseTicks % 25 == 0) {
                // Scan the surroundings while paused.
                double angle = settler.getRandom().nextDouble() * Math.PI * 2;
                settler.getLookControl().setLookAt(
                    settler.getX() + Math.cos(angle) * 8, settler.getEyeY(),
                    settler.getZ() + Math.sin(angle) * 8);
            }
            if (pauseTicks > 50) {
                nextWaypoint();
            }
        } else if (currentWaypoint != null
            && settler.blockPosition().distSqr(currentWaypoint)
                <= WAYPOINT_REACH_SQR) {
            settler.getNavigation().stop();
        }
    }

    private void rejectUnreachedWaypoint() {
        settler.getNavigation().stop();
        currentWaypoint = null;
        pauseTicks = 0;
        retryWaypointIn = FAILED_WAYPOINT_RETRY_TICKS;
        failedWaypoints++;
        if (failedWaypoints == FAILURE_REPORT_THRESHOLD) {
            settler.recordRouteFailure("guard_patrol:no_path");
        }
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
    }
}
