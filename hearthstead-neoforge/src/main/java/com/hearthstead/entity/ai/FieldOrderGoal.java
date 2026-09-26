package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.guard.FieldOrderRules.Kind;
import com.hearthstead.settlement.guard.FieldOrders;
import com.hearthstead.settlement.guard.FormationMath;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;

/**
 * Walks a soldier to its field-order slot and holds it: knights form the
 * shield line facing the ordered direction and raise shields when enemies
 * close in; archers take their spot or tower cell; a charge or focus closes
 * on the named enemy until combat goals take over; an escort keeps its ring
 * around the commander. Combat goals (priority 2) own the soldier whenever it
 * has a target; target choice is filtered by {@link FieldOrders#allowsTarget}.
 */
public final class FieldOrderGoal extends Goal {
    private static final double ARRIVE_SQR = 1.5D * 1.5D;
    private static final int REPATH_TICKS = 10;
    private static final int MAX_FAILED_PATHS = 6;
    private static final double BRACE_ALERT_SQR = 10.0D * 10.0D;

    private final SettlerEntity settler;
    private FieldOrders.Assignment seen;
    private int repathIn;
    private int failedPaths;
    private boolean bracing;

    public FieldOrderGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return settler.level() instanceof ServerLevel level
            && settler.getTarget() == null
            && FieldOrders.assignment(settler) != null
            && EquipmentRequests.readyForProfession(level, settler, settler.getProfession());
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repathIn = 0;
        failedPaths = 0;
        settler.setActivity(SettlerActivity.PATROLLING);
    }

    @Override
    public void tick() {
        FieldOrders.Assignment assignment = FieldOrders.assignment(settler);
        if (assignment == null) return;
        if (assignment != seen) {
            seen = assignment;
            failedPaths = 0;
            repathIn = 0;
            raiseShield(false);
        }
        Kind kind = assignment.order.kind;
        BlockPos anchor = FieldOrders.anchor(settler);
        if (anchor == null) return;

        if (kind == Kind.ATTACK) {
            LivingEntity enemy = enemyOf(assignment);
            if (enemy != null) {
                settler.getLookControl().setLookAt(enemy, 30.0F, 30.0F);
                boolean ranged = FieldOrders.roleOf(settler)
                    .map(com.hearthstead.settlement.guard.FieldOrderRules.Group::ranged).orElse(false);
                double reach = ranged ? 24.0D : 32.0D;
                if (settler.distanceToSqr(enemy) <= reach * reach
                    && settler.getSensing().hasLineOfSight(enemy)
                    && FieldOrders.allowsTarget(settler, enemy)) {
                    settler.setTarget(enemy); // combat goals take over next tick
                    return;
                }
            }
        }

        double distance = settler.blockPosition().distSqr(anchor);
        boolean escort = kind == Kind.FOLLOW;
        double arrive = escort ? 2.5D * 2.5D : ARRIVE_SQR;
        if (distance <= arrive) {
            settler.getNavigation().stop();
            face(assignment);
            brace(assignment);
            return;
        }
        raiseShield(false);
        if (--repathIn > 0) return;
        repathIn = REPATH_TICKS;
        double speed = kind == Kind.ATTACK ? 1.3D : distance > 64.0D ? 1.2D : 1.05D;
        Path path = settler.getNavigation().createPath(anchor, 0);
        BlockPos start = settler.blockPosition();
        BlockPos end = path == null || path.getNodeCount() == 0 ? null
            : path.getNodePos(path.getNodeCount() - 1);
        boolean progressing = end != null && end.distSqr(anchor) < start.distSqr(anchor);
        if (path != null && (path.canReach() || progressing) && settler.getNavigation().moveTo(path, speed)) {
            failedPaths = 0;
            if (kind != Kind.ATTACK) FieldOrders.markReachable(settler, true);
            return;
        }
        settler.getNavigation().stop();
        if (++failedPaths >= MAX_FAILED_PATHS) {
            settler.recordRouteFailure("field_order:no_path");
            if (kind != Kind.ATTACK) FieldOrders.markReachable(settler, false);
            repathIn = 60;
            failedPaths = 0;
        }
    }

    private LivingEntity enemyOf(FieldOrders.Assignment assignment) {
        if (assignment.order.enemy == null || !(settler.level() instanceof ServerLevel level)) return null;
        return level.getEntity(assignment.order.enemy) instanceof LivingEntity living && living.isAlive()
            ? living : null;
    }

    /** Face the ordered direction (line/tower) or the commander (escort). */
    private void face(FieldOrders.Assignment assignment) {
        int octant = assignment.order.octant;
        BlockPos at = settler.blockPosition();
        double x = at.getX() + 0.5D + FormationMath.forwardX(octant) * 6.0D;
        double z = at.getZ() + 0.5D + FormationMath.forwardZ(octant) * 6.0D;
        if (assignment.order.kind == Kind.FOLLOW && settler.level() instanceof ServerLevel level) {
            var leader = level.getServer().getPlayerList().getPlayer(assignment.order.issuer);
            if (leader != null && leader.level() == level) {
                x = at.getX() + 0.5D + leader.getLookAngle().x * 6.0D;
                z = at.getZ() + 0.5D + leader.getLookAngle().z * 6.0D;
            }
        }
        settler.getLookControl().setLookAt(x, settler.getEyeY(), z);
        float yaw = (float) (Math.toDegrees(Math.atan2(z - settler.getZ(), x - settler.getX())) - 90.0D);
        settler.setYBodyRot(yaw);
        settler.setYHeadRot(yaw);
    }

    /** Knights in a shield line raise shields while enemies close in. */
    private void brace(FieldOrders.Assignment assignment) {
        boolean wants = FieldOrders.bracing(settler) && settler.getOffhandItem().is(Items.SHIELD)
            && enemyNear();
        raiseShield(wants);
    }

    private boolean enemyNear() {
        return !settler.level().getEntitiesOfClass(Mob.class, settler.getBoundingBox().inflate(10.0D),
            mob -> mob instanceof Enemy && mob.isAlive()
                && mob.distanceToSqr(settler) <= BRACE_ALERT_SQR).isEmpty();
    }

    private void raiseShield(boolean raise) {
        if (raise && !bracing) {
            settler.startUsingItem(InteractionHand.OFF_HAND);
            bracing = true;
        } else if (!raise && bracing) {
            if (settler.isUsingItem() && settler.getUsedItemHand() == InteractionHand.OFF_HAND) {
                settler.stopUsingItem();
            }
            bracing = false;
        }
    }

    @Override
    public void stop() {
        raiseShield(false);
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.IDLE);
        seen = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
