package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.Summons;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import com.hearthstead.settlement.state.GuardOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * The narrow contract for a posted Watchtower archer.
 *
 * <p>The longer sight line is earned only while the actual Archer stands at
 * their own validated {@link GuardOrder.Mode#TOWER_POST}. It does not turn
 * Watchtower employment, a nearby order region, or an old saved post into a
 * range bonus. Target authority, ally protection, ammunition and hit contact
 * remain owned by their existing callers.</p>
 */
public final class ArcherTowerPost {
    /** Settler follow range is 32; keep the post's coverage inside it. */
    public static final double SHOT_RANGE = 32.0D;
    /** Same physical post contact envelope used by {@link GuardOrderGoal}. */
    private static final double POST_REACH_SQR = 2.25D;

    private ArcherTowerPost() {
    }

    /**
     * True only for an Archer physically holding its currently valid own
     * Tower Post. A displaced Archer must return through ordinary navigation
     * before it can use tower coverage again.
     */
    public static boolean atActiveOwnPost(ServerLevel level, Settlement settlement,
                                          SettlerEntity archer) {
        if (archer != null && com.hearthstead.settlement.guard.BannerTeams.active(archer) != null) return false;
        if (level == null || settlement == null || archer == null
            || archer.getProfession() != Profession.ARCHER
            || archer.settlement() != settlement || Summons.active(archer)) {
            return false;
        }
        GuardAssignmentService.Validation validation =
            GuardAssignmentService.validate(level, settlement, archer, false);
        if (!validation.valid()) {
            return false;
        }
        GuardOrder order = validation.order().orElse(null);
        if (order == null
            || order.modeAt(level.getGameTime()) != GuardOrder.Mode.TOWER_POST) {
            return false;
        }
        BlockPos post = order.pos().orElse(null);
        return post != null && archer.blockPosition().distSqr(post) <= POST_REACH_SQR;
    }

    /**
     * The Tower Post extension is visible coverage only. It deliberately does
     * not decide whether a target is hostile, tracked, assigned or legal to
     * damage; target selection and projectile contact retain those checks.
     */
    public static boolean coversVisibleTarget(ServerLevel level,
                                              Settlement settlement,
                                              SettlerEntity archer,
                                              LivingEntity target) {
        return target != null
            && atActiveOwnPost(level, settlement, archer)
            && archer.distanceToSqr(target) <= SHOT_RANGE * SHOT_RANGE
            && archer.hasLineOfSight(target);
    }
}
