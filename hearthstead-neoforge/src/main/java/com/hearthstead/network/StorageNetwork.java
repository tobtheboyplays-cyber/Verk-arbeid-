package com.hearthstead.network;

import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.warehouse.WarehouseStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Server side of the read-only Storage view. */
public final class StorageNetwork {

    /**
     * Answers "what is my settlement storing?" from the sender's own
     * position — the client never names a settlement, so it cannot ask
     * about one it is not standing in.
     *
     * <p>Every warehouse in the settlement is summed. Chests are re-read
     * through {@link WarehouseStorage}, which is derived from the world,
     * so this can never report something the chests do not actually hold.
     */
    public static void handleRequest(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Settlement settlement = nearestSettlement(level, player.blockPosition());
        if (settlement == null) {
            send(player, new StorageIndexPayload("", 0, 0, List.of()));
            return;
        }

        Map<Item, Integer> combined = new HashMap<>();
        int total = 0;
        int warehouses = 0;
        for (Building building : settlement.buildings) {
            if (building.type != com.hearthstead.building.BuildingType.WAREHOUSE
                || !building.valid) {
                continue;
            }
            warehouses++;
            WarehouseStorage storage = WarehouseStorage.of(level, building);
            for (Map.Entry<Item, Integer> entry : storage.tally().entrySet()) {
                combined.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
            total += storage.totalItems();
        }

        if (warehouses == 0) {
            send(player, new StorageIndexPayload("", -1, 0, List.of()));
            return;
        }

        List<ItemStack> top = new ArrayList<>();
        combined.entrySet().stream()
            .sorted(Comparator.<Map.Entry<Item, Integer>>comparingInt(Map.Entry::getValue)
                .reversed())
            .limit(StorageIndexPayload.MAX_LISTED)
            .forEach(e -> {
                ItemStack stack = new ItemStack(e.getKey());
                // Count is display-only here; it can exceed a real stack
                // size because it is a settlement-wide total, not a stack.
                stack.setCount(Math.min(e.getValue(), 9999));
                top.add(stack);
            });

        send(player, new StorageIndexPayload(settlement.name, combined.size(), total, top));
    }

    private static Settlement nearestSettlement(ServerLevel level, BlockPos pos) {
        Settlement best = null;
        double bestDist = Double.MAX_VALUE;
        for (Settlement s : SettlementSavedData.get(level).settlements.values()) {
            double dist = s.center.distSqr(pos);
            if (dist < bestDist && dist <= (double) s.radius * s.radius) {
                bestDist = dist;
                best = s;
            }
        }
        return best;
    }

    private static void send(ServerPlayer player, StorageIndexPayload payload) {
        // "top" is already the settlement's real chest totals sorted and
        // capped server-side (see StorageIndexPayload) -- the client never
        // computes or caches a total of its own, so what the screen shows
        // can never disagree with the chests it was read from.
        PacketDistributor.sendToPlayer(player, payload);
    }

    private StorageNetwork() {
    }
}
