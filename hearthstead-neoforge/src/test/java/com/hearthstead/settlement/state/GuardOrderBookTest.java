package com.hearthstead.settlement.state;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardOrderBookTest {
    private static final ResourceLocation OVERWORLD =
        ResourceLocation.withDefaultNamespace("overworld");

    @Test
    void appendedModesKeepEveryExistingWireIdStable() {
        assertEquals(0, GuardOrder.Mode.NONE.wireId());
        assertEquals(1, GuardOrder.Mode.RALLY_HERE.wireId());
        assertEquals(2, GuardOrder.Mode.DEFEND_HEARTH.wireId());
        assertEquals(3, GuardOrder.Mode.PATROL_ROUTE.wireId());
        assertEquals(4, GuardOrder.Mode.STAND_POST.wireId());
        assertEquals(5, GuardOrder.Mode.TOWER_POST.wireId());
    }

    @Test
    void exactIdentityIssuerBuildingAndRevisionSurviveRestart() {
        UUID settlement = UUID.randomUUID();
        UUID guard = UUID.randomUUID();
        UUID issuer = UUID.randomUUID();
        UUID building = UUID.randomUUID();
        GuardOrderBook book = GuardOrderBook.fresh();
        GuardOrder order = book.orderForMutation(settlement, guard, OVERWORLD)
            .orElseThrow();
        assertTrue(order.issueStand(new BlockPos(4, 70, 9), Direction.WEST,
            11, issuer, building, 240L));

        GuardOrderBook loaded = GuardOrderBook.readNbt(book.writeNbt());
        GuardOrder persisted = loaded.order(guard).orElseThrow();
        assertTrue(persisted.ownedBy(settlement, guard, OVERWORLD));
        assertEquals(issuer, persisted.issuerId().orElseThrow());
        assertEquals(building, persisted.linkedBuildingId().orElseThrow());
        assertEquals(Direction.WEST, persisted.facing());
        assertEquals(11, persisted.leashRadius());
        assertEquals(1, persisted.revision());
        assertEquals(240L, persisted.issuedGameTime());
    }

    @Test
    void towerIdentityFacingArcAndTimestampsSurviveRestart() {
        UUID settlement = UUID.randomUUID();
        UUID guard = UUID.randomUUID();
        UUID issuer = UUID.randomUUID();
        UUID watchtower = UUID.randomUUID();
        GuardOrderBook book = GuardOrderBook.fresh();
        GuardOrder order = book.orderForMutation(settlement, guard, OVERWORLD)
            .orElseThrow();
        assertTrue(order.issueTower(new BlockPos(9, 82, -4), Direction.EAST,
            120, issuer, watchtower, 900L));

        GuardOrder persisted = GuardOrderBook.readNbt(book.writeNbt())
            .order(guard).orElseThrow();
        assertEquals(GuardOrder.Mode.TOWER_POST, persisted.mode());
        assertEquals(new BlockPos(9, 82, -4), persisted.pos().orElseThrow());
        assertEquals(Direction.EAST, persisted.facing());
        assertEquals(120, persisted.facingArc());
        assertEquals(watchtower, persisted.linkedBuildingId().orElseThrow());
        assertEquals(issuer, persisted.issuerId().orElseThrow());
        assertEquals(900L, persisted.issuedGameTime());
        assertEquals(900L, persisted.updatedGameTime());
    }

    @Test
    void duplicateGuardIdsQuarantineWithoutSelectingAWinner() {
        UUID settlement = UUID.randomUUID();
        UUID guard = UUID.randomUUID();
        GuardOrderBook book = GuardOrderBook.fresh();
        GuardOrder order = book.orderForMutation(settlement, guard, OVERWORLD)
            .orElseThrow();
        assertTrue(order.issueStand(BlockPos.ZERO, Direction.NORTH, 8,
            UUID.randomUUID(), UUID.randomUUID(), 1L));
        CompoundTag forged = book.writeNbt();
        ListTag orders = forged.getList("Orders", CompoundTag.TAG_COMPOUND);
        orders.add(orders.getCompound(0).copy());

        GuardOrderBook loaded = GuardOrderBook.readNbt(forged);
        assertTrue(loaded.quarantined());
        assertEquals("duplicate_guard_id", loaded.quarantineReason());
        assertEquals(0, loaded.size());
    }

    @Test
    void legacyMovesOnlyToOnePersistedAndOneMatchingLiveGuard() {
        UUID settlement = UUID.randomUUID();
        UUID guard = UUID.randomUUID();
        UUID building = UUID.randomUUID();
        GuardOrder legacy = new GuardOrder();
        assertTrue(legacy.issue(GuardOrder.Mode.RALLY_HERE,
            new BlockPos(3, 64, 3), Long.MAX_VALUE));
        GuardOrderBook book = GuardOrderBook.pendingLegacy(legacy);

        assertEquals(GuardOrderBook.ReconcileResult.WAITING_FOR_LIVE_GUARD,
            book.reconcileLegacy(settlement, OVERWORLD,
                List.of(new GuardOrderBook.Candidate(guard, building)),
                List.of(), 10L));
        assertEquals(GuardOrderBook.ReconcileResult.MIGRATED,
            book.reconcileLegacy(settlement, OVERWORLD,
                List.of(new GuardOrderBook.Candidate(guard, building)),
                List.of(guard), 11L));
        GuardOrder migrated = book.order(guard).orElseThrow();
        assertTrue(migrated.migratedLegacy());
        assertTrue(migrated.issuerId().isEmpty());
        assertEquals(building, migrated.linkedBuildingId().orElseThrow());
    }

    @Test
    void ambiguityAndNullCandidatesFailClosed() {
        UUID settlement = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        GuardOrder legacy = new GuardOrder();
        assertTrue(legacy.issue(GuardOrder.Mode.DEFEND_HEARTH,
            BlockPos.ZERO, Long.MAX_VALUE));
        GuardOrderBook ambiguous = GuardOrderBook.pendingLegacy(legacy);
        assertEquals(GuardOrderBook.ReconcileResult.QUARANTINED_AMBIGUOUS,
            ambiguous.reconcileLegacy(settlement, OVERWORLD, List.of(
                    new GuardOrderBook.Candidate(first, UUID.randomUUID()),
                    new GuardOrderBook.Candidate(second, UUID.randomUUID())),
                List.of(first, second), 1L));
        assertTrue(ambiguous.quarantined());

        GuardOrder anotherLegacy = new GuardOrder();
        assertTrue(anotherLegacy.issue(GuardOrder.Mode.RALLY_HERE,
            BlockPos.ZERO, Long.MAX_VALUE));
        GuardOrderBook nullRow = GuardOrderBook.pendingLegacy(anotherLegacy);
        ArrayList<GuardOrderBook.Candidate> corrupt = new ArrayList<>();
        corrupt.add(null);
        assertEquals(GuardOrderBook.ReconcileResult.QUARANTINED_INVALID,
            nullRow.reconcileLegacy(settlement, OVERWORLD, corrupt,
                List.of(first), 1L));
        assertTrue(nullRow.quarantined());
    }

    @Test
    void saturatedRevisionSurvivesButCannotMutateOrWrap() {
        UUID settlement = UUID.randomUUID();
        UUID guard = UUID.randomUUID();
        UUID issuer = UUID.randomUUID();
        UUID building = UUID.randomUUID();
        GuardOrderBook book = GuardOrderBook.fresh();
        GuardOrder order = book.orderForMutation(settlement, guard, OVERWORLD)
            .orElseThrow();
        assertTrue(order.issueStand(BlockPos.ZERO, Direction.SOUTH, 8,
            issuer, building, 5L));
        CompoundTag tag = book.writeNbt();
        tag.getList("Orders", CompoundTag.TAG_COMPOUND).getCompound(0)
            .putInt("Revision", Integer.MAX_VALUE);
        GuardOrderBook loaded = GuardOrderBook.readNbt(tag);
        GuardOrder saturated = loaded.order(guard).orElseThrow();
        assertTrue(saturated.revisionSaturated());
        assertFalse(saturated.issueStand(new BlockPos(1, 0, 1), Direction.NORTH,
            8, issuer, building, 6L));
        assertEquals(Integer.MAX_VALUE, saturated.revision());
    }

    @Test
    void malformedListAndLegacyStatusMismatchQuarantine() {
        CompoundTag wrongList = GuardOrderBook.fresh().writeNbt();
        ListTag strings = new ListTag();
        strings.add(StringTag.valueOf("not-an-order"));
        wrongList.put("Orders", strings);
        GuardOrderBook listResult = GuardOrderBook.readNbt(wrongList);
        assertTrue(listResult.quarantined());
        assertEquals("malformed_order_list", listResult.quarantineReason());

        CompoundTag wrongStatus = GuardOrderBook.fresh().writeNbt();
        wrongStatus.putInt("LegacyStatusWireId", 3);
        wrongStatus.putString("LegacyStatus", "quarantined");
        GuardOrderBook statusResult = GuardOrderBook.readNbt(wrongStatus);
        assertTrue(statusResult.quarantined());
        assertEquals("legacy_status_mismatch",
            statusResult.quarantineReason());
    }

    @Test
    void fullValidBookRefusesOneMoreWithoutErasingExistingOrders() {
        UUID settlement = UUID.randomUUID();
        GuardOrderBook book = GuardOrderBook.fresh();
        for (int i = 0; i < GuardOrderBook.MAX_ORDERS; i++) {
            UUID guard = new UUID(0L, i + 1L);
            assertTrue(book.orderForMutation(settlement, guard, OVERWORLD)
                .isPresent());
        }
        assertTrue(book.orderForMutation(settlement, UUID.randomUUID(),
            OVERWORLD).isEmpty());
        assertFalse(book.quarantined());
        assertEquals(GuardOrderBook.MAX_ORDERS, book.size());
    }
}
