package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import com.hearthstead.settlement.state.GuardOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

/**
 * During a live raid, at most one armed melee guard assigned to a Stand Post
 * may close around a player inside that post's authored leash. A Patrol guard
 * keeps patrolling and an Archer keeps their Tower Post: this priority-3 goal
 * is a local interpretation of one Stand area, never a settlement-wide order
 * override. Combat remains priority 2 and interrupts it immediately; the
 * exact persisted Stand order resumes as soon as the player leaves its area
 * or the raid ends.
 *
 * <p>The formation is deliberately local and bounded. It considers only
 * loaded members and each Stand order's exact loaded issuer, elects by
 * distance then stable UUID, recomputes movement at most once per {@value
 * #REPATH_INTERVAL} ticks, and refuses to cross the authored leash. There is
 * no global mob scan.</p>
 */
public final class GuardRaidEscortGoal extends Goal {
    private static final int REPATH_INTERVAL = 10;
    private static final double SLOT_REACH_SQR = 3.0D;
    private static final double PLAYER_BATTLE_PADDING = 24.0D;
    private static final double GUARD_RADIUS = 3.0D;

    private final SettlerEntity settler;
    private ServerPlayer anchor;
    private BlockPos standPost;
    private int standLeash;
    private BlockPos destination;
    private int repathIn;

    public GuardRaidEscortGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(settler.level() instanceof ServerLevel level)
            || settler.getTarget() != null) {
            return false;
        }
        if (settler.getProfession() != Profession.GUARD
            || !EquipmentRequests.readyForProfession(level, settler,
                Profession.GUARD)) {
            return false;
        }
        Settlement settlement = settler.settlement();
        if (!raidActive(settlement)) {
            return false;
        }
        Assignment assignment = elect(level, settlement);
        if (assignment == null || assignment.guard() != settler) {
            return false;
        }
        capture(assignment);
        destination = formationSlot(anchor, standPost, standLeash);
        return destination != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(settler.level() instanceof ServerLevel level)
            || settler.getTarget() != null) {
            return false;
        }
        Settlement settlement = settler.settlement();
        if (settler.getProfession() != Profession.GUARD
            || !raidActive(settlement)
            || !EquipmentRequests.readyForProfession(level, settler,
                Profession.GUARD)) {
            return false;
        }
        Assignment assignment = elect(level, settlement);
        if (assignment == null || assignment.guard() != settler) {
            return false;
        }
        capture(assignment);
        return true;
    }

    @Override
    public void start() {
        repathIn = 0;
        settler.setActivity(SettlerActivity.PATROLLING);
        moveToSlot();
    }

    @Override
    public void tick() {
        if (!(settler.level() instanceof ServerLevel level) || anchor == null) {
            return;
        }
        settler.getLookControl().setLookAt(anchor, 30.0F, 30.0F);
        if (--repathIn > 0) {
            return;
        }
        destination = formationSlot(anchor, standPost, standLeash);
        moveToSlot();
    }

    private void moveToSlot() {
        repathIn = REPATH_INTERVAL;
        if (destination == null) {
            settler.getNavigation().stop();
            return;
        }
        if (settler.blockPosition().distSqr(destination) <= SLOT_REACH_SQR) {
            settler.getNavigation().stop();
            return;
        }
        Path path = settler.getNavigation().createPath(destination, 0);
        if (path != null && path.canReach()
            && settler.getNavigation().moveTo(path, 1.15D)) {
            return;
        }
        // Player movement can place the ideal screen cell in a fresh gap or
        // behind a new wall. The validated Stand post is the bounded, honest
        // fallback; never pretend a failed move is an active bodyguard slot.
        if (standPost != null && !standPost.equals(destination)) {
            Path fallback = settler.getNavigation().createPath(standPost, 0);
            if (fallback != null && fallback.canReach()
                && settler.getNavigation().moveTo(fallback, 1.0D)) {
                destination = standPost;
                return;
            }
        }
        // Neither the bodyguard slot nor the persisted Stand post accepted a
        // physical path. Stop honestly instead of publishing a destination
        // the navigation rejected; the bounded retry will reassess later.
        settler.getNavigation().stop();
        destination = null;
    }

    @Override
    public void stop() {
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.IDLE);
        anchor = null;
        standPost = null;
        standLeash = 0;
        destination = null;
        repathIn = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /**
     * One pure election for this settlement. Each eligible Stand guard and
     * its exact issuing player inside that Stand leash form a candidate pair;
     * the closest pair wins, then stable guard/player UUIDs. Another nearby
     * multiplayer guest cannot steal a Guard they did not command. Every
     * guard computes the same result, so a raid cannot silently create
     * several bodyguards.
     */
    private static Assignment elect(ServerLevel level,
                                    Settlement settlement) {
        if (!raidActive(settlement)) {
            return null;
        }
        Assignment best = null;
        double bestDistance = Double.MAX_VALUE;
        for (SettlerEntity guard : SettlementManager.loadedMembers(level,
                settlement)) {
            if (!guard.isAlive() || guard.getProfession() != Profession.GUARD
                || !GuardAssignmentService.hasServiceableEquipment(guard)) {
                continue;
            }
            GuardAssignmentService.Validation validation =
                GuardAssignmentService.validate(level, settlement, guard,
                    false);
            if (!validation.valid()) {
                continue;
            }
            GuardOrder order = validation.order().orElseThrow();
            BlockPos post = order.pos().orElse(null);
            if (order.modeAt(level.getGameTime()) != GuardOrder.Mode.STAND_POST
                || post == null) {
                // PATROL and TOWER never enter this branch.
                continue;
            }
            ServerPlayer player = order.issuerId()
                .map(level::getPlayerByUUID)
                .filter(ServerPlayer.class::isInstance)
                .map(ServerPlayer.class::cast)
                .orElse(null);
            if (!eligibleAnchor(level, player, settlement, post,
                    order.leashRadius())) {
                continue;
            }
            double distance = guard.distanceToSqr(player);
            if (betterPair(distance, guard, player, bestDistance, best)) {
                best = new Assignment(guard, player, post.immutable(),
                    order.leashRadius());
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean eligibleAnchor(ServerLevel level,
                                          ServerPlayer player,
                                          Settlement settlement,
                                          BlockPos post, int leash) {
        if (player == null || settlement == null || !player.isAlive()
            || player.isSpectator() || player.level() != level) {
            return false;
        }
        double radius = settlement.radius + PLAYER_BATTLE_PADDING;
        return player.blockPosition().distSqr(settlement.center)
                <= radius * radius
            && player.blockPosition().distSqr(post)
                <= (double) leash * leash;
    }

    private static BlockPos formationSlot(ServerPlayer player, BlockPos post,
                                          int leash) {
        if (player == null || post == null || leash <= 0) {
            return null;
        }
        Vec3 look = player.getLookAngle();
        double length = Math.sqrt(look.x * look.x + look.z * look.z);
        double forwardX = length < 1.0E-4D ? 0.0D : look.x / length;
        double forwardZ = length < 1.0E-4D ? 1.0D : look.z / length;
        double x = player.getX() + forwardX * GUARD_RADIUS;
        double y = player.getY();
        double z = player.getZ() + forwardZ * GUARD_RADIUS;

        // The desired screen point may be beyond the post when the player is
        // standing on the leash edge. Clamp the guard, not the authored area.
        double dx = x - (post.getX() + 0.5D);
        double dy = y - post.getY();
        double dz = z - (post.getZ() + 0.5D);
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double safeLeash = Math.max(0.5D, leash - 0.5D);
        if (distance > safeLeash) {
            double scale = safeLeash / distance;
            x = post.getX() + 0.5D + dx * scale;
            y = post.getY() + dy * scale;
            z = post.getZ() + 0.5D + dz * scale;
        }
        return BlockPos.containing(x, y, z);
    }

    private void capture(Assignment assignment) {
        anchor = assignment.player();
        standPost = assignment.post();
        standLeash = assignment.leash();
    }

    private static boolean betterPair(double distance, SettlerEntity guard,
                                      ServerPlayer player,
                                      double incumbentDistance,
                                      Assignment incumbent) {
        if (incumbent == null || distance < incumbentDistance) {
            return true;
        }
        if (distance > incumbentDistance) {
            return false;
        }
        int guards = compareUuid(guard.getUUID(),
            incumbent.guard().getUUID());
        return guards < 0 || guards == 0 && compareUuid(player.getUUID(),
            incumbent.player().getUUID()) < 0;
    }

    private static boolean raidActive(Settlement settlement) {
        // PendingRaid is written only after a complete band is sealed and is
        // cleared at terminal resolution. It is the shared active authority
        // for authored and recurring raids.
        return settlement != null && settlement.pendingRaid != null;
    }

    private static int compareUuid(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(),
            right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(
            left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

    private record Assignment(SettlerEntity guard, ServerPlayer player,
                              BlockPos post, int leash) {
    }
}
