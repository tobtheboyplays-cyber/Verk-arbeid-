package com.hearthstead.entity.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Runtime-only, per-door passage queue for RoadNavigation settlers.
 *
 * <p>One holder may enter a narrow wooden doorway at a time. Waiting actors
 * retain their normal path but receive no forward move request until their
 * turn. Leases are short and unsaved: interruption, removal, or a blocked
 * holder therefore releases the doorway without teleporting anybody.
 */
public final class DoorPassageReservations {
    /** Two seconds: enough to cross one doorway, short enough to recover
     * promptly when a route is interrupted or its holder stops progressing. */
    private static final int LEASE_TICKS = 40;
    private static final double RESERVE_REACH_SQR = 9.0D;
    private static final double ENTERED_REACH_SQR = 1.25D;
    private static final double CLEAR_REACH_SQR = 4.0D;
    private static final Map<Level, Map<BlockPos, Queue>> QUEUES = new WeakHashMap<>();

    private DoorPassageReservations() {}

    /** Returns true only for the current holder of a nearby path door. */
    public static boolean permit(Level level, Mob actor, @Nullable Path path) {
        if (level == null || level.isClientSide || actor == null || path == null) return true;
        BlockPos door = nextWoodenDoor(level, actor, path);
        if (door == null) return true;
        long now = level.getGameTime();
        Queue queue = QUEUES.computeIfAbsent(level, ignored -> new HashMap<>())
            .computeIfAbsent(door, ignored -> new Queue());
        queue.prune(now);
        UUID id = actor.getUUID();
        if (id.equals(queue.holder)) {
            queue.observe(actor, door, now);
            return true;
        }
        queue.waiters.putIfAbsent(id, now + LEASE_TICKS);
        if (queue.holder == null && queue.firstWaiting() != null
                && queue.firstWaiting().equals(id)) {
            queue.waiters.remove(id);
            queue.holder = id;
            queue.entered = false;
            queue.closestDistanceSqr = Double.POSITIVE_INFINITY;
            queue.observe(actor, door, now);
            return true;
        }
        return false;
    }

    /** Called every navigation tick. Clears only after the holder actually
     * reached and then cleared the doorway; interruption otherwise expires. */
    public static void maintain(Level level, Mob actor) {
        if (level == null || level.isClientSide || actor == null) return;
        Map<BlockPos, Queue> queues = QUEUES.get(level);
        if (queues == null) return;
        long now = level.getGameTime();
        Iterator<Map.Entry<BlockPos, Queue>> entries = queues.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<BlockPos, Queue> entry = entries.next();
            Queue queue = entry.getValue();
            queue.prune(now);
            if (actor.getUUID().equals(queue.holder)) {
                queue.observe(actor, entry.getKey(), now);
                if (queue.entered && actor.distanceToSqr(entry.getKey().getX() + .5D,
                        actor.getY(), entry.getKey().getZ() + .5D) > CLEAR_REACH_SQR) {
                    queue.holder = null;
                    queue.until = 0L;
                    queue.entered = false;
                }
            }
            if (queue.empty()) entries.remove();
        }
        if (queues.isEmpty()) QUEUES.remove(level);
    }

    @Nullable private static BlockPos nextWoodenDoor(Level level, Mob actor, Path path) {
        int first = Math.max(0, path.getNextNodeIndex() - 1);
        int last = Math.min(path.getNodeCount(), path.getNextNodeIndex() + 3);
        for (int index = first; index < last; index++) {
            Node node = path.getNode(index);
            BlockPos lower = woodenDoorLower(level, new BlockPos(node.x, node.y, node.z));
            if (lower != null && actor.distanceToSqr(lower.getX() + .5D,
                    actor.getY(), lower.getZ() + .5D) <= RESERVE_REACH_SQR) return lower;
        }
        return null;
    }

    @Nullable private static BlockPos woodenDoorLower(Level level, BlockPos pos) {
        for (BlockPos candidate : new BlockPos[]{pos, pos.above()}) {
            var state = level.getBlockState(candidate);
            if (!(state.getBlock() instanceof DoorBlock) || !DoorBlock.isWoodenDoor(level, candidate)) continue;
            return state.hasProperty(DoorBlock.HALF)
                    && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
                ? candidate.below() : candidate;
        }
        return null;
    }

    private static final class Queue {
        @Nullable UUID holder;
        long until;
        boolean entered;
        double closestDistanceSqr = Double.POSITIVE_INFINITY;
        final LinkedHashMap<UUID, Long> waiters = new LinkedHashMap<>();
        void prune(long now) {
            if (holder != null && until <= now) {
                holder = null;
                until = 0L;
                entered = false;
                closestDistanceSqr = Double.POSITIVE_INFINITY;
            }
            waiters.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        @Nullable UUID firstWaiting() { return waiters.isEmpty() ? null : waiters.keySet().iterator().next(); }
        void observe(Mob actor, BlockPos door, long now) {
            double distanceSqr = actor.distanceToSqr(door.getX() + .5D,
                actor.getY(), door.getZ() + .5D);
            // Progress toward the sill renews the short lease. A stuck holder
            // cannot keep a doorway forever merely because its navigation
            // continues ticking.
            if (distanceSqr + .01D < closestDistanceSqr) {
                closestDistanceSqr = distanceSqr;
                until = now + LEASE_TICKS;
            }
            if (distanceSqr <= ENTERED_REACH_SQR) entered = true;
        }
        boolean empty() { return holder == null && waiters.isEmpty(); }
    }
}


