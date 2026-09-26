package com.hearthstead.settlement.state;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardOrderTest {

    @Test
    void existingWireIdsStayStableAndUnknownIdsFailClosed() {
        assertEquals(0, GuardOrder.Mode.NONE.wireId());
        assertEquals(1, GuardOrder.Mode.RALLY_HERE.wireId());
        assertEquals(2, GuardOrder.Mode.DEFEND_HEARTH.wireId());
        assertEquals(3, GuardOrder.Mode.PATROL_ROUTE.wireId());
        assertTrue(GuardOrder.Mode.tryFromWireId(99).isEmpty());
    }

    @Test
    void routeNeedsTwoUniquePointsAndIsImmutableFromCallers() {
        GuardOrder order = new GuardOrder();
        BlockPos first = new BlockPos(1, 64, 1);
        BlockPos second = new BlockPos(8, 64, 8);
        assertTrue(order.appendPatrolPoint(first));
        assertFalse(order.appendPatrolPoint(first));
        assertFalse(order.issuePatrol(Long.MAX_VALUE));
        assertTrue(order.appendPatrolPoint(second));
        assertTrue(order.issuePatrol(Long.MAX_VALUE));
        assertEquals(GuardOrder.Mode.PATROL_ROUTE, order.mode());
        assertEquals(List.of(first, second), order.patrolPoints());
        assertThrows(UnsupportedOperationException.class,
            () -> order.patrolPoints().add(BlockPos.ZERO));
    }

    @Test
    void activeRouteRoundTripsWithoutChangingOldModes() {
        GuardOrder order = new GuardOrder();
        assertTrue(order.appendPatrolPoint(new BlockPos(2, 70, 3)));
        assertTrue(order.appendPatrolPoint(new BlockPos(9, 70, 11)));
        assertTrue(order.issuePatrol(Long.MAX_VALUE));

        GuardOrder loaded = GuardOrder.readNbt(order.writeNbt());
        assertEquals(order.revision(), loaded.revision());
        assertEquals(GuardOrder.Mode.PATROL_ROUTE, loaded.mode());
        assertEquals(order.patrolPoints(), loaded.patrolPoints());
        assertEquals(order.patrolPoints().getFirst(), loaded.pos().orElseThrow());

        GuardOrder legacy = new GuardOrder();
        assertTrue(legacy.issue(GuardOrder.Mode.DEFEND_HEARTH,
            new BlockPos(4, 65, 4), 500L));
        GuardOrder legacyLoaded = GuardOrder.readNbt(legacy.writeNbt());
        assertEquals(GuardOrder.Mode.DEFEND_HEARTH, legacyLoaded.mode());
        assertTrue(legacyLoaded.patrolPoints().isEmpty());
    }

    @Test
    void corruptOversizedRouteCannotBecomeActive() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("ModeWireId", GuardOrder.Mode.PATROL_ROUTE.wireId());
        tag.putString("Mode", GuardOrder.Mode.PATROL_ROUTE.id());
        tag.putLong("Until", Long.MAX_VALUE);
        ListTag points = new ListTag();
        for (int i = 0; i <= GuardOrder.MAX_PATROL_POINTS; i++) {
            points.add(new IntArrayTag(new int[]{i, 64, i}));
        }
        tag.put("PatrolPoints", points);

        GuardOrder loaded = GuardOrder.readNbt(tag);
        assertEquals(GuardOrder.Mode.NONE, loaded.mode());
        assertTrue(loaded.patrolPoints().isEmpty());
        assertFalse(loaded.activeAt(0L));
    }
}
