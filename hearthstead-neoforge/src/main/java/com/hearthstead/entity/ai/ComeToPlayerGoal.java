package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.summon.PlayerSummons;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Carries out a player summon ({@link PlayerSummons}): soldiers run, civilians
 * jog, following the player's live position until within about three blocks;
 * then the settler stands at the player's right side facing where the player
 * looks until the summon ends. Combat (priority 2) interrupts it; the summon
 * stays live and resumes afterwards. A starving settler eats first.
 *
 * <p>Holding only MOVE/LOOK is what keeps a civilian's job safe: work goals
 * stop the same way they do for an alarm or a meal (bag contents, claims and
 * leases stay with the settler and its own goals), and re-plan from their own
 * state when the summon ends.
 */
public final class ComeToPlayerGoal extends Goal {
    private static final double SOLDIER_SPEED = 1.35D;
    private static final double CIVILIAN_SPEED = 1.15D;
    private static final double RESUME_FOLLOW_SQR = 5.0D * 5.0D;
    private static final int REPATH_TICKS = 10;
    private static final float STARVING_HUNGER = 40.0F;

    private final SettlerEntity settler;
    private static final int MAX_FAILED_ROUTES = 8;

    private int repathIn;
    private int failedRoutes;
    private boolean following = true;

    public ComeToPlayerGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return settler.getTarget() == null && !settler.isSleeping()
            && settler.getHunger() >= STARVING_HUNGER
            && PlayerSummons.active(settler) != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repathIn = 0;
        failedRoutes = 0;
        following = true;
        settler.setActivity(SettlerActivity.TRAVELING);
    }

    @Override
    public void tick() {
        PlayerSummons.Entry entry = PlayerSummons.active(settler);
        if (entry == null) return;
        ServerPlayer player = PlayerSummons.summoningPlayer(settler, entry);
        if (player == null) {
            settler.getNavigation().stop();
            return;
        }
        double distanceSqr = settler.distanceToSqr(player);
        if (following && distanceSqr <= PlayerSummons.ARRIVE_DISTANCE * PlayerSummons.ARRIVE_DISTANCE) {
            following = false;
            settler.getNavigation().stop();
            PlayerSummons.arrived(settler);
            settler.setActivity(SettlerActivity.IDLE);
        } else if (!following && distanceSqr > RESUME_FOLLOW_SQR) {
            following = true; // the player walked on: keep up
            repathIn = 0;
            settler.setActivity(SettlerActivity.TRAVELING);
        }
        if (following) {
            settler.getLookControl().setLookAt(player, 30.0F, 30.0F);
            if (--repathIn > 0) return;
            repathIn = REPATH_TICKS;
            Path path = settler.getNavigation().createPath(player.blockPosition(), 1);
            BlockPos from = settler.blockPosition();
            BlockPos end = path == null || path.getNodeCount() == 0 ? null
                : path.getNodePos(path.getNodeCount() - 1);
            boolean progressing = end != null && end.distSqr(player.blockPosition()) < from.distSqr(player.blockPosition());
            if (path != null && (path.canReach() || progressing)
                && settler.getNavigation().moveTo(path, entry.soldier ? SOLDIER_SPEED : CIVILIAN_SPEED)) {
                failedRoutes = 0;
            } else if (settler.onGround() && ++failedRoutes >= MAX_FAILED_ROUTES) {
                settler.recordRouteFailure("summon:no_path");
                PlayerSummons.unreachable(settler);
            }
            return;
        }
        // Waiting at the player's right side, facing where the player looks.
        Vec3 look = player.getLookAngle().multiply(1, 0, 1);
        if (look.lengthSqr() < 1.0E-4D) look = new Vec3(0, 0, 1);
        look = look.normalize();
        Vec3 right = new Vec3(-look.z, 0, look.x);
        Vec3 side = player.position().add(right.scale(1.6D));
        BlockPos spot = BlockPos.containing(side);
        if (settler.blockPosition().distSqr(spot) > 2.25D && --repathIn <= 0) {
            repathIn = REPATH_TICKS;
            settler.getNavigation().moveTo(side.x, side.y, side.z, 0.9D);
        } else if (settler.blockPosition().distSqr(spot) <= 2.25D) {
            settler.getNavigation().stop();
        }
        Vec3 face = settler.position().add(look.scale(6.0D));
        settler.getLookControl().setLookAt(face.x, settler.getEyeY(), face.z);
    }

    @Override
    public void stop() {
        settler.getNavigation().stop();
        if (settler.getActivity() == SettlerActivity.TRAVELING) settler.setActivity(SettlerActivity.IDLE);
        following = true;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
