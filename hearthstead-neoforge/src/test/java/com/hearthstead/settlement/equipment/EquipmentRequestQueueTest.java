package com.hearthstead.settlement.equipment;

import com.hearthstead.entity.Profession;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EquipmentRequestQueueTest {

    @Test
    void manualMoveIsPersistentAndReplaySafe() {
        EquipmentRequest urgent = request(EquipmentRequest.Priority.URGENT);
        EquipmentRequest high = request(EquipmentRequest.Priority.HIGH);
        EquipmentRequest normal = request(EquipmentRequest.Priority.NORMAL);
        List<EquipmentRequest> active = List.of(normal, urgent, high);
        EquipmentRequestQueue queue = new EquipmentRequestQueue();

        queue.reconcile(active);
        assertEquals(List.of(urgent.id(), high.id(), normal.id()),
            ids(queue.ordered(active)));
        long before = queue.revision();
        assertEquals(EquipmentRequestQueue.MoveResult.APPLIED,
            queue.move(active, normal.id(), urgent.id(), before));
        assertEquals(List.of(normal.id(), urgent.id(), high.id()),
            ids(queue.ordered(active)));
        assertNotEquals(before, queue.revision());

        assertEquals(EquipmentRequestQueue.MoveResult.STALE,
            queue.move(active, normal.id(), urgent.id(), before),
            "the same delayed packet must never apply twice");
        EquipmentRequestQueue restored = EquipmentRequestQueue.readNbt(
            queue.writeNbt());
        assertEquals(queue.revision(), restored.revision());
        assertEquals(List.of(normal.id(), urgent.id(), high.id()),
            ids(restored.ordered(active)));
    }

    @Test
    void foreignAndNoOpMovesCannotMutateRevision() {
        EquipmentRequest first = request(EquipmentRequest.Priority.URGENT);
        EquipmentRequest second = request(EquipmentRequest.Priority.HIGH);
        List<EquipmentRequest> active = List.of(first, second);
        EquipmentRequestQueue queue = new EquipmentRequestQueue();
        queue.reconcile(active);
        long revision = queue.revision();

        assertEquals(EquipmentRequestQueue.MoveResult.INVALID,
            queue.move(active, UUID.randomUUID(), first.id(), revision));
        assertEquals(EquipmentRequestQueue.MoveResult.INVALID,
            queue.move(active, first.id(), second.id(), revision),
            "moving #1 immediately before #2 is a no-op");
        assertEquals(revision, queue.revision());
    }

    @Test
    void newlyPublishedUrgentRequestUsesDefaultPolicyWithoutErasingManualOrder() {
        EquipmentRequest urgent = request(EquipmentRequest.Priority.URGENT);
        EquipmentRequest normal = request(EquipmentRequest.Priority.NORMAL);
        List<EquipmentRequest> firstActive = new ArrayList<>(List.of(urgent, normal));
        EquipmentRequestQueue queue = new EquipmentRequestQueue();
        queue.reconcile(firstActive);
        queue.move(firstActive, normal.id(), urgent.id(), queue.revision());

        EquipmentRequest newUrgent = request(EquipmentRequest.Priority.URGENT);
        List<EquipmentRequest> expanded = List.of(urgent, normal, newUrgent);
        queue.reconcile(expanded);
        assertEquals(newUrgent.id(), queue.ordered(expanded).get(0).id(),
            "new urgent work enters ahead of a manually promoted normal row");
        assertEquals(List.of(normal.id(), urgent.id()),
            ids(queue.ordered(expanded)).subList(1, 3),
            "existing rows retain their manual relative order");
    }

    private static EquipmentRequest request(EquipmentRequest.Priority priority) {
        return new EquipmentRequest(UUID.randomUUID(), UUID.randomUUID(),
            Profession.FARMER, new EquipmentRequirement(Items.IRON_HOE,
                ResourceLocation.withDefaultNamespace("hoes"), 8), 1,
            priority, EquipmentRequest.Reason.MISSING);
    }

    private static List<UUID> ids(List<EquipmentRequest> requests) {
        return requests.stream().map(EquipmentRequest::id).toList();
    }
}
