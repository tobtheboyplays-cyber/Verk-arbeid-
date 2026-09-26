package com.hearthstead.entity.ai;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.Summons;
import com.hearthstead.settlement.guard.BannerTeams;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Where a hired Archer stands on watch when nobody has given it an order.
 *
 * <p>Before this, an Archer without a player-set Tower Post had no goal at
 * all during its watch: ArcherAttackGoal needs a target, and the schedule
 * posts martial trades nowhere, so it idled 99-100% of the time (reliability
 * soak 2026-09-25). Now it walks to its own Watchtower's highest standable
 * floor (or the Banner if it has no valid tower) and holds there, looking out.
 *
 * <p>Strictly a default: any explicit guard order, banner team or field
 * order, a Summons, an alert or a target wins, and this goal steps aside the
 * moment one appears. It grants no range bonus; that stays tied to the real
 * {@code TOWER_POST} order in {@link ArcherTowerPost}.
 */
public class ArcherDefaultPostGoal extends Goal {
    private static final int RECHECK_TICKS = 40;
    private static final double HOLD_REACH_SQR = 4.0D;
    private static final int MAX_POST_SCAN = 1_024;

    private final SettlerEntity settler;
    private BlockPos post;
    private int recheck;
    private int repath;
    private int lookTicks;

    public ArcherDefaultPostGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (--recheck > 0) {
            return false;
        }
        recheck = RECHECK_TICKS;
        post = mayDefault() ? findPost() : null;
        return post != null;
    }

    private boolean mayDefault() {
        if (settler.getProfession() != Profession.ARCHER || !settler.isBound()
            || settler.getTarget() != null || !(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null || !Schedule.onWatch(settlement, settler, settler.dayPhase())
            || settlement.alertActive(level.getGameTime())
            || BannerTeams.active(settler) != null || Summons.active(settler)) {
            return false;
        }
        // A real player order (Tower Post, Stand, Patrol) always wins.
        return !GuardAssignmentService.validate(level, settlement, settler, false).valid();
    }

    @Override
    public boolean canContinueToUse() {
        if (post == null) {
            return false;
        }
        if (--recheck > 0) {
            return settler.getTarget() == null;
        }
        recheck = RECHECK_TICKS;
        return mayDefault();
    }

    @Override
    public void start() {
        repath = 0;
        lookTicks = 0;
        settler.setActivity(SettlerActivity.PATROLLING);
    }

    @Override
    public void stop() {
        post = null;
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.IDLE);
    }

    @Override
    public void tick() {
        if (post == null) {
            return;
        }
        if (settler.distanceToSqr(post.getX() + 0.5, post.getY(), post.getZ() + 0.5) > HOLD_REACH_SQR) {
            if (--repath <= 0) {
                repath = 40;
                if (!settler.getNavigation().moveTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5, 0.9)) {
                    settler.recordRouteFailure("archer_default_post_unreachable");
                }
            }
            return;
        }
        settler.getNavigation().stop();
        // Look out over the approaches, a slow sweep rather than a stare.
        if (++lookTicks % 60 == 0) {
            double angle = settler.getRandom().nextDouble() * Math.PI * 2.0D;
            settler.getLookControl().setLookAt(settler.getX() + Math.cos(angle) * 16.0D,
                settler.getEyeY(), settler.getZ() + Math.sin(angle) * 16.0D);
        }
    }

    /** Highest standable floor of the Archer's own Watchtower, else the Banner. */
    private BlockPos findPost() {
        Settlement settlement = settler.settlement();
        if (settlement == null || !(settler.level() instanceof ServerLevel level)) {
            return null;
        }
        Building tower = Employment.employerOf(settlement, settler.getUUID());
        if (tower != null && tower.valid && tower.type == BuildingType.WATCHTOWER && tower.bounds != null) {
            BlockPos best = null;
            int scanned = 0;
            var b = tower.bounds;
            for (int y = b.maxY(); y >= b.minY() && best == null; y--) {
                for (int x = b.minX(); x <= b.maxX() && best == null; x++) {
                    for (int z = b.minZ(); z <= b.maxZ(); z++) {
                        if (++scanned > MAX_POST_SCAN) {
                            break;
                        }
                        BlockPos p = new BlockPos(x, y, z);
                        if (standable(level, p)) {
                            best = p;
                            break;
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
            if (tower.anchor != null) {
                return tower.anchor;
            }
        }
        return settlement.center;
    }

    private static boolean standable(ServerLevel level, BlockPos feet) {
        return level.isLoaded(feet)
            && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
            && level.getFluidState(feet).isEmpty()
            && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP);
    }
}
