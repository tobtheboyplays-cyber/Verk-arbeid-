package com.hearthstead.settlement.raid;

import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.ArcherTowerPost;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.state.GuardOrder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Runtime-only, settlement-scoped hostile snapshot and defender claim board.
 *
 * <p>The persisted participant roster remains raid/count authority. This
 * board only coordinates currently loaded AI: one bounded scan per settlement
 * per ten ticks replaces one scan per defender, while claims prevent every
 * Guard and Archer from independently piling onto the same first target.
 * Claims are deliberately reconstructible after restart and never become
 * completion, reward, damage or HUD authority.</p>
 */
public final class RaidThreatBoard {
    public enum Channel { MELEE, RANGED }
    public static final int REFRESH_TICKS = 10;
    private static final Map<ServerLevel, Map<UUID, Board>> BOARDS =
        new WeakHashMap<>();

    public static synchronized List<Monster> candidates(ServerLevel level,
                                                        Settlement settlement) {
        if (level == null || settlement == null) {
            return List.of();
        }
        Board board = board(level, settlement);
        long bucket = Math.floorDiv(level.getGameTime(), REFRESH_TICKS);
        if (board.bucket != bucket) {
            refresh(level, settlement, board, bucket);
        }
        return board.candidates;
    }

    /** Exact result of one settlement-wide deterministic allocation pass. */
    @Nullable
    public static synchronized Monster assignedTarget(ServerLevel level,
                                                       Settlement settlement,
                                                       SettlerEntity defender) {
        candidates(level, settlement);
        Board board = board(level, settlement);
        UUID targetId = board.assignments.get(defender.getUUID());
        if (targetId == null) return null;
        if (level.getEntity(targetId) instanceof Monster monster
            && monster.isAlive()) return monster;
        // Death/removal is a terminal allocation edge, not a reason to wait
        // for the ordinary ten-tick scan cadence.
        refresh(level, settlement, board,
            Math.floorDiv(level.getGameTime(), REFRESH_TICKS));
        targetId = board.assignments.get(defender.getUUID());
        return targetId != null
            && level.getEntity(targetId) instanceof Monster reassigned
            && reassigned.isAlive() ? reassigned : null;
    }

    public static synchronized int load(ServerLevel level, Settlement settlement,
                                        Monster target, SettlerEntity excluding) {
        if (target == null) {
            return 0;
        }
        Board board = board(level, settlement);
        int count = 0;
        Channel channel = channelOf(excluding);
        for (Map.Entry<UUID, Claim> claim : board.claims.entrySet()) {
            if ((excluding == null || !claim.getKey().equals(excluding.getUUID()))
                && claim.getValue().targetId().equals(target.getUUID())
                && claim.getValue().channel() == channel
                // UUID priority makes the converged allocation independent
                // of entity insertion and goal tick order: a later UUID may
                // never reserve a slot ahead of an earlier eligible UUID.
                && (excluding == null || compareUuid(claim.getKey(),
                    excluding.getUUID()) < 0)) {
                count++;
            }
        }
        return count;
    }

    public static synchronized void claim(ServerLevel level, Settlement settlement,
                                          SettlerEntity defender,
                                          @Nullable Monster target) {
        if (level == null || settlement == null || defender == null) {
            return;
        }
        Board board = board(level, settlement);
        if (target == null || !target.isAlive()
            || target.level() != level || !validFor(settlement, target)) {
            board.claims.remove(defender.getUUID());
        } else {
            board.claims.put(defender.getUUID(), new Claim(target.getUUID(),
                channelOf(defender)));
        }
    }

    public static synchronized void release(ServerLevel level,
                                            Settlement settlement,
                                            SettlerEntity defender) {
        if (level != null && settlement != null && defender != null) {
            board(level, settlement).claims.remove(defender.getUUID());
        }
    }

    public static synchronized void clear(ServerLevel level) {
        if (level != null) {
            BOARDS.remove(level);
        }
    }

    private static Board board(ServerLevel level, Settlement settlement) {
        return BOARDS.computeIfAbsent(level, ignored -> new HashMap<>())
            .computeIfAbsent(settlement.id, ignored -> new Board());
    }

    private static void refresh(ServerLevel level, Settlement settlement,
                                Board board, long bucket) {
        double reach = settlement.radius + 8.0D;
        AABB bounds = new AABB(settlement.center).inflate(reach);
        List<Monster> threats = level.getEntitiesOfClass(Monster.class, bounds,
            threat -> threat.isAlive() && validFor(settlement, threat));
        threats.sort((left, right) -> compareUuid(left.getUUID(), right.getUUID()));
        Map<UUID, Monster> live = new HashMap<>();
        for (Monster threat : threats) {
            live.put(threat.getUUID(), threat);
        }
        board.candidates = List.copyOf(threats);
        publishAssignments(level, settlement, board, threats);
        board.claims.entrySet().removeIf(entry -> {
            Monster threat = live.get(entry.getValue().targetId());
            if (threat == null || !threat.isAlive()) {
                return true;
            }
            var defender = level.getEntity(entry.getKey());
            return !(defender instanceof SettlerEntity settler)
                || !settler.isAlive()
                || settler.settlement() == null
                || !settlement.id.equals(settler.settlement().id)
                || settler.getTarget() == null
                || !entry.getValue().targetId().equals(
                    settler.getTarget().getUUID())
                || entry.getValue().channel() != channelOf(settler);
        });
        board.bucket = bucket;
    }

    private static void publishAssignments(ServerLevel level,
                                           Settlement settlement, Board board,
                                           List<Monster> threats) {
        List<SettlerEntity> defenders = SettlementManager.loadedMembers(level,
            settlement).stream().filter(defender -> defender.isAlive()
                && defender.getProfession().martial()).sorted((left, right) ->
                    compareUuid(left.getUUID(), right.getUUID())).toList();
        Map<Slot, Integer> used = new HashMap<>();
        Map<UUID, UUID> published = new HashMap<>();
        for (SettlerEntity defender : defenders) {
            Channel channel = channelOf(defender);
            Monster selected = null;
            for (Monster threat : threats) {
                Slot slot = new Slot(threat.getUUID(), channel);
                if (used.getOrDefault(slot, 0) >= capacity(threat, channel)
                    || !eligible(level, settlement, defender, threat)
                    || selected != null && compareFor(defender, threat,
                        selected) >= 0) {
                    continue;
                }
                selected = threat;
            }
            if (selected != null) {
                Slot slot = new Slot(selected.getUUID(), channel);
                used.merge(slot, 1, Integer::sum);
                published.put(defender.getUUID(), selected.getUUID());
            }
        }
        board.assignments = Map.copyOf(published);
    }

    private static int capacity(Monster threat, Channel channel) {
        var victim = threat.getTarget();
        boolean urgent = victim instanceof SettlerEntity
            || victim instanceof net.minecraft.world.entity.player.Player;
        return threat instanceof RaiderEntity raider
            ? capacityFor(raider.isCaptain(), raider.variant(), channel, urgent)
            : urgent ? 2 : 1;
    }

    /** Pure contract used by the live allocator and focused unit tests. */
    public static int capacityFor(boolean captain, RaiderEntity.Variant variant,
                                  Channel channel, boolean urgent) {
        int base = captain ? channel == Channel.RANGED ? 2 : 1
            : variant == RaiderEntity.Variant.BRUTE
                ? channel == Channel.MELEE ? 2 : 1 : 1;
        return base + (urgent ? 1 : 0);
    }

    private static boolean eligible(ServerLevel level, Settlement settlement,
                                    SettlerEntity defender, Monster threat) {
        double follow = defender.getAttributeValue(
            net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
        return defender.canAttack(threat)
            && defender.distanceToSqr(threat) <= follow * follow
            && withinLeash(level, settlement, defender, threat)
            && (defender.hasLineOfSight(threat)
                || mayAcquireSealedRaidThreatThroughCover(level, settlement,
                    defender, threat));
    }

    /**
     * A currently sealed participant in the authored first raid may be
     * acquired across temporary building cover, but only after all ordinary
     * attack, follow-range and authored-post leash checks have already passed.
     *
     * <p>This is target awareness, not attack authority: {@code GuardMeleeGoal}
     * still requires a physically reachable target, melee range and a fresh
     * line of sight before it starts a wind-up or commits damage. The narrow
     * exception lets a posted Guard route to a real breach instead of idling
     * behind the very door a known raid participant is breaking.</p>
     */
    public static boolean mayAcquireSealedRaidThreatThroughCover(
            ServerLevel level, Settlement settlement, SettlerEntity defender,
            Monster threat) {
        if (level == null || settlement == null || defender == null
            || defender.getProfession()
                != com.hearthstead.entity.Profession.GUARD
            || !(threat instanceof RaiderEntity raider)
            || raider.isGoblinThiefDemo()
            || !settlement.raidLifecycle.isAuthoredFirstRaidActive()
            || !settlement.raidLifecycle.participantsTracked()
            || !settlement.id.equals(raider.settlementId())
            || !settlement.raidLifecycle.isParticipant(raider.getUUID())
            || settlement.raidLifecycle.terminalParticipants()
                .contains(raider.getUUID())
            || !defender.canAttack(raider)) {
            return false;
        }
        double follow = defender.getAttributeValue(
            net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
        return defender.distanceToSqr(raider) <= follow * follow
            && withinLeash(level, settlement, defender, raider);
    }

    private static boolean withinLeash(ServerLevel level, Settlement settlement,
                                       SettlerEntity defender, Monster threat) {
        if (ArcherTowerPost.coversVisibleTarget(level, settlement, defender, threat)) return true;
        GuardOrder order = settlement.guardOrders.order(defender.getUUID())
            .orElse(null);
        if (order == null || !order.activeAt(level.getGameTime())) return true;
        List<net.minecraft.core.BlockPos> anchors =
            order.mode() == GuardOrder.Mode.PATROL_ROUTE ? order.patrolPoints()
                : order.pos().map(List::of).orElse(List.of());
        double nearest = anchors.stream().mapToDouble(pos ->
            threat.blockPosition().distSqr(pos)).min()
            .orElse(Double.POSITIVE_INFINITY);
        double leash = order.leashRadius();
        if (nearest <= leash * leash) return true;
        // A posted Archer's leash bounds where he may WALK, not how far he may
        // SHOOT (archer fire fix, 27 Sep): the melee leash of 8 measured post
        // to threat meant a roof or wall Archer never got a raider assigned
        // and stood silent while it fought the Guards 11 blocks away.
        if (rangedReachAllows(level, settlement, defender, threat, nearest)) return true;
        var victim = threat.getTarget();
        boolean urgent = victim == defender
            || victim instanceof net.minecraft.world.entity.player.Player
            || victim instanceof SettlerEntity other
                && other.settlement() != null
                && settlement.id.equals(other.settlement().id);
        return leashAllows(nearest, leash, urgent);
    }

    /**
     * Ranged target authority for a posted Archer: a threat within his real
     * ordinary shot range (drill, Perception and height bonus included) of
     * the post is his to shoot. Movement stays clamped by the order itself.
     */
    public static boolean rangedReachAllows(ServerLevel level, Settlement settlement,
                                            SettlerEntity defender, Monster threat,
                                            double nearestAnchorDistanceSquared) {
        if (defender == null || threat == null
            || defender.getProfession() != com.hearthstead.entity.Profession.ARCHER
            || !(nearestAnchorDistanceSquared >= 0.0D)) return false;
        double reach = com.hearthstead.entity.ai.ArcherHeightAdvantage.normalShotRange(
            level, settlement, defender, threat);
        return nearestAnchorDistanceSquared <= reach * reach;
    }

    /** Squared-distance leash rule shared by runtime and boundary tests. */
    public static boolean leashAllows(double distanceSquared, double leash,
                                      boolean urgent) {
        if (leash <= 0.0D || distanceSquared < 0.0D) return false;
        double allowed = leash + (urgent ? 4.0D : 0.0D);
        return distanceSquared <= allowed * allowed;
    }

    private static int compareFor(SettlerEntity defender, Monster left,
                                  Monster right) {
        int urgency = Integer.compare(urgency(left), urgency(right));
        if (urgency != 0) return urgency;
        int fit = Integer.compare(roleMismatch(defender, left),
            roleMismatch(defender, right));
        if (fit != 0) return fit;
        // Finish an eligible close engagement before chasing a marginally
        // nearer peer. Urgency, role fit and allocation capacity still win.
        // Without this, knockback changes nearest order every board refresh,
        // spreading two-hit sword damage across an entire group of enemies.
        int engagement = Boolean.compare(closeIncumbent(defender, right),
            closeIncumbent(defender, left));
        if (engagement != 0) return engagement;
        int distance = Double.compare(defender.distanceToSqr(left),
            defender.distanceToSqr(right));
        return distance != 0 ? distance
            : compareUuid(left.getUUID(), right.getUUID());
    }

    private static boolean closeIncumbent(SettlerEntity defender,
                                          Monster threat) {
        return defender.getTarget() == threat
            && defender.distanceToSqr(threat) <= 16.0D;
    }

    private static int urgency(Monster threat) {
        var victim = threat.getTarget();
        return victim instanceof SettlerEntity ? 0
            : victim instanceof net.minecraft.world.entity.player.Player ? 1 : 2;
    }

    private static int roleMismatch(SettlerEntity defender, Monster threat) {
        if (!(threat instanceof RaiderEntity raider)) return 0;
        if (defender.getProfession() == com.hearthstead.entity.Profession.GUARD)
            return raider.variant() == RaiderEntity.Variant.BRUTE ? 0 : 1;
        if (defender.getProfession() == com.hearthstead.entity.Profession.ARCHER)
            return raider.variant() == RaiderEntity.Variant.SKIRMISHER
                || raider.isCaptain() ? 0 : 1;
        return 0;
    }

    private static boolean validFor(Settlement settlement, Monster threat) {
        if (threat instanceof RaiderEntity raider) {
            if (!settlement.id.equals(raider.settlementId())) {
                return false;
            }
            if (settlement.raidLifecycle.isAuthoredFirstRaidActive()) {
                return settlement.raidLifecycle.isParticipant(raider.getUUID())
                    && !settlement.raidLifecycle.terminalParticipants().contains(
                        raider.getUUID());
            }
            // Outside an authored active raid this is ordinary settlement
            // defence (including training/ad-hoc hostile fixtures). The
            // same-settlement check above still prevents cross-settlement
            // pursuit; only an ACTIVE authored encounter requires sealing.
            return true;
        }
        double range = settlement.radius + 8.0D;
        return threat.blockPosition().distSqr(settlement.center) <= range * range;
    }

    public static int compareUuid(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(),
            right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(
            left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

    private static Channel channelOf(@Nullable SettlerEntity defender) {
        return defender != null
            && defender.getProfession() == com.hearthstead.entity.Profession.ARCHER
            ? Channel.RANGED : Channel.MELEE;
    }

    private record Claim(UUID targetId, Channel channel) {}
    private record Slot(UUID targetId, Channel channel) {}

    private static final class Board {
        private long bucket = Long.MIN_VALUE;
        private List<Monster> candidates = List.of();
        private Map<UUID, UUID> assignments = Map.of();
        private final Map<UUID, Claim> claims = new HashMap<>();
    }

    private RaidThreatBoard() {
    }
}
