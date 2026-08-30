package com.hearthstead.network;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Exact-session validation, reordering and snapshots for Request Queue. */
public final class EquipmentRequestListNetwork {

    private static final double INSPECTION_RANGE_SQR = 64.0D;

    /** Refreshes only the exact Courier sheet/session named by the client. */
    public static void handle(ServerPlayer player,
                              EquipmentRequestListRequestPayload request) {
        Resolved resolved = resolve(player, request.courierEntityId(),
            request.courierId(), request.sessionId());
        if (resolved == null) {
            return;
        }
        send(player, snapshot(resolved.level, resolved.settlement,
            resolved.courier, request.sessionId(), mayReorder(player)));
    }

    /**
     * Commits one drag only against its exact open session and queue revision.
     * Every outcome sends current server truth, so a stale client recovers
     * without guessing or applying an optimistic order as authority.
     */
    public static void handleMove(ServerPlayer player,
                                  EquipmentRequestMovePayload move) {
        Resolved resolved = resolve(player, move.courierEntityId(),
            move.courierId(), move.sessionId());
        if (resolved == null) {
            return;
        }
        if (mayReorder(player)
            && resolved.settlement.id.equals(move.settlementId())) {
            EquipmentRequests.reorder(resolved.level, resolved.settlement,
                move.movedRequestId(), move.beforeRequestId(),
                move.queueRevision());
        }
        send(player, snapshot(resolved.level, resolved.settlement,
            resolved.courier, move.sessionId(), mayReorder(player)));
    }

    /** Test/cold seam: read-only, no inspection authority. */
    public static EquipmentRequestListPayload snapshot(ServerLevel level,
                                                       Settlement settlement,
                                                       int courierEntityId) {
        return snapshot(level, settlement, courierEntityId,
            new UUID(0L, 0L), new UUID(0L, 0L), false);
    }

    /** Server-authored queue snapshot in the exact order Courier AI consumes. */
    public static EquipmentRequestListPayload snapshot(ServerLevel level,
                                                       Settlement settlement,
                                                       SettlerEntity courier,
                                                       UUID sessionId,
                                                       boolean canReorder) {
        return snapshot(level, settlement, courier.getId(), courier.getUUID(),
            sessionId, canReorder);
    }

    private static EquipmentRequestListPayload snapshot(ServerLevel level,
                                                        Settlement settlement,
                                                        int courierEntityId,
                                                        UUID courierId,
                                                        UUID sessionId,
                                                        boolean canReorder) {
        // Reconciliation may cancel stale worker rows. Iterate a copy first,
        // then re-read the authoritative order used by both AI and payload.
        for (EquipmentRequest request : EquipmentRequests.list(level, settlement)) {
            Building destination = buildingById(settlement,
                request.destinationBuildingId());
            if (destination != null) {
                EquipmentRequests.reconcile(level, settlement, destination,
                    request);
            }
        }
        List<EquipmentRequest> ordered = EquipmentRequests.list(level,
            settlement);
        int rowCount = Math.min(ordered.size(),
            EquipmentRequestListPayload.MAX_ROWS);
        List<EquipmentRequestListPayload.Row> rows = new ArrayList<>(rowCount);
        for (int index = 0; index < rowCount; index++) {
            EquipmentRequest request = ordered.get(index);
            Building destination = buildingById(settlement,
                request.destinationBuildingId());
            if (destination == null) {
                continue;
            }
            ItemStack requested = new ItemStack(
                request.requirement().preferredItem(), request.count());
            rows.add(new EquipmentRequestListPayload.Row(request.id(),
                request.requesterId(), requesterName(settlement,
                    request.requesterId()), request.destinationBuildingId(),
                destination.type.id(), index + 1,
                request.priority().ordinal(), request.status().ordinal(),
                requested, request.count(), request.reason().ordinal()));
        }
        UUID next = ordered.size() > rowCount
            ? ordered.get(rowCount).id() : EquipmentRequestMovePayload.END;
        return new EquipmentRequestListPayload(courierEntityId, courierId,
            sessionId, settlement.id,
            EquipmentRequests.queueRevision(level, settlement), canReorder,
            level.getGameTime(), ordered.size(), rows, next);
    }

    @Nullable
    private static Resolved resolve(ServerPlayer player, int courierEntityId,
                                    UUID courierId, UUID sessionId) {
        ServerLevel level = player.serverLevel();
        Entity entity = level.getEntity(courierEntityId);
        if (!(entity instanceof SettlerEntity courier)
            || !courier.isAlive()
            || !courier.getUUID().equals(courierId)
            || courier.getProfession() != Profession.COURIER
            || player.distanceToSqr(courier) > INSPECTION_RANGE_SQR
            || !InspectionViewers.authorizeSettler(player, courier, courierId,
                sessionId)) {
            return null;
        }
        Settlement settlement = courier.settlement();
        return settlement == null ? null
            : new Resolved(level, settlement, courier);
    }

    /** Current co-op policy: participating players manage; spectators read. */
    private static boolean mayReorder(ServerPlayer player) {
        return !player.isSpectator();
    }

    private static String requesterName(Settlement settlement,
                                        UUID requesterId) {
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (record.entityId.equals(requesterId)) {
                return record.name;
            }
        }
        return requesterId.toString().substring(0, 8);
    }

    @Nullable
    private static Building buildingById(Settlement settlement,
                                         UUID buildingId) {
        for (Building building : settlement.buildings) {
            if (building.id.equals(buildingId)) {
                return building;
            }
        }
        return null;
    }

    private static void send(ServerPlayer player,
                             EquipmentRequestListPayload snapshot) {
        PacketDistributor.sendToPlayer(player, snapshot);
    }

    private record Resolved(ServerLevel level, Settlement settlement,
                            SettlerEntity courier) {
    }

    private EquipmentRequestListNetwork() {
    }
}
