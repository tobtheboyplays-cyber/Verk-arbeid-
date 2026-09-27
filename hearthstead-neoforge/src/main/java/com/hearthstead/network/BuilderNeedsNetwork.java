package com.hearthstead.network;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.menu.SettlerInventoryMenu;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.builder.*;
import com.hearthstead.settlement.request.*;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.*;

/** Read-only stock observation; never claims a job, requests supplies or moves items. */
public final class BuilderNeedsNetwork {
    public static final int REFRESH_TICKS = 40;
    private static final Map<ServerPlayer, Session> SESSIONS = new WeakHashMap<>();
    /** Separate from sessions: CLOSE, inventory and switching Builders cannot reset the budget. */
    private static final Map<ServerPlayer, Integer> LAST_SNAPSHOT = new WeakHashMap<>();
    private record Session(int entityId, UUID builder, UUID token) { }
    private BuilderNeedsNetwork() { }

    public static boolean mayView(ServerPlayer player, SettlerEntity builder) {
        if (!builder.isAlive() || builder.getProfession() != Profession.BUILDER
            || player.level() != builder.level() || player.isSpectator() || !player.mayBuild()
            || player.distanceToSqr(builder) > SettlerInventoryMenu.REACH_SQUARED) return false;
        Settlement settlement = settlement(builder);
        return settlement != null && settlement.record(builder.getUUID()) != null
            && settlement.members.contains(player.getUUID());
    }

    /** Returns the new session only when the open was admitted. */
    @Nullable public static UUID open(ServerPlayer player, SettlerEntity builder) {
        if (!mayView(player, builder)) {
            player.displayClientMessage(Component.translatable("hearthstead.builder.needs.refused"), true);
            return null;
        }
        if (!admitSnapshot(player)) return null;
        UUID token = UUID.randomUUID();
        SESSIONS.put(player, new Session(builder.getId(), builder.getUUID(), token));
        PayloadSend.toPlayer(player, snapshot(player.serverLevel(), builder, token, true));
        return token;
    }

    /** Whether this action was admitted; unsuccessful requests never rebuild a snapshot. */
    public static boolean handle(ServerPlayer player, BuilderNeedsRequest request) {
        Session session = SESSIONS.get(player);
        if (session == null || session.entityId != request.entityId()
            || !session.builder.equals(request.builderId()) || !session.token.equals(request.sessionId())) return false;
        if (request.action() == BuilderNeedsRequest.CLOSE) {
            SESSIONS.remove(player);
            return true;
        }
        if (!(player.serverLevel().getEntity(request.entityId()) instanceof SettlerEntity builder)
            || !builder.getUUID().equals(session.builder) || !mayView(player, builder)) {
            SESSIONS.remove(player);
            return false;
        }
        if (request.action() == BuilderNeedsRequest.INVENTORY) {
            SESSIONS.remove(player);
            builder.reconcileEquipmentNeedNow();
            player.openMenu(new SimpleMenuProvider((id, inventory, ignored) ->
                new SettlerInventoryMenu(id, inventory, builder),
                Component.translatable("hearthstead.settler.inventory.title", builder.getSettlerName())), buf -> {
                    buf.writeVarInt(builder.getId());
                    buf.writeUUID(builder.getUUID());
                });
            com.hearthstead.settlement.journey.JourneyServerHooks.noteSettlerInventoryViewed(
                player, settlement(builder), builder);
            return true;
        } else if (request.action() == BuilderNeedsRequest.REFRESH) {
            if (!admitSnapshot(player)) return false;
            PayloadSend.toPlayer(player, snapshot(player.serverLevel(), builder, session.token, false));
            return true;
        }
        return false;
    }

    private static boolean admitSnapshot(ServerPlayer player) {
        int now = player.serverLevel().getServer().getTickCount();
        Integer previous = LAST_SNAPSHOT.get(player);
        // int subtraction also keeps a short interval correct across the server tick counter's wrap.
        if (previous != null && now - previous < REFRESH_TICKS) return false;
        LAST_SNAPSHOT.put(player, now);
        return true;
    }

    @Nullable private static Settlement settlement(SettlerEntity builder) {
        return builder.level() instanceof ServerLevel level && builder.getSettlementId() != null
            ? SettlementManager.byId(level, builder.getSettlementId()) : null;
    }

    /** Public for a world-backed regression test of exactly what the player receives. */
    public static BuilderNeedsPayload snapshot(ServerLevel level, SettlerEntity builder, UUID token, boolean opening) {
        Settlement settlement = settlement(builder);
        BuildSiteSavedData data = BuildSiteSavedData.existing(level);
        BuildJob job = null;
        boolean queued = false;
        if (settlement != null && data != null) {
            List<BuildJob> jobs = data.activeJobs(settlement.id);
            for (BuildJob candidate : jobs) {
                if (builder.getUUID().equals(candidate.claimant) && candidate.workable()
                    && candidate.leaseUntil > level.getGameTime()) {
                    job = candidate;
                    break;
                }
            }
            if (job == null) {
                for (BuildJob candidate : jobs) {
                    if (!candidate.workable() || BuildJobs.heldByOther(level, candidate, builder.getUUID())) continue;
                    if (candidate.exhausted() && candidate.blockedCount() > 0 && !candidate.allowOverwrite) continue;
                    job = candidate;
                    queued = true;
                    break;
                }
            }
        }
        if (job == null) return new BuilderNeedsPayload(builder.getId(), builder.getUUID(), token, opening,
            limited(builder.getSettlerName(), 128), "", false, -1, 0, 0, BuildStatus.QUEUED.ordinal(),
            List.of(), List.of(), 0, List.of(), 0);

        Building hut = BuildJobs.hutOf(settlement, builder);
        List<Container> hutStock = BuilderStock.hutContainers(level, hut);
        List<Container> warehouses = new ArrayList<>();
        Set<Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.addAll(hutStock);
        for (Building building : settlement.buildings) {
            if (!building.valid || building.type != BuildingType.WAREHOUSE || building.bounds == null) continue;
            for (var pos : WarehouseIndex.containers(level, building)) {
                if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof Container container
                    && seen.add(container)) warehouses.add(container);
            }
        }
        Map<Item, Integer> carrying = carriedToHut(level, settlement, hut);
        List<BuilderPayloads.Stock> stock = new ArrayList<>();
        for (var entry : BuilderMaterials.remaining(job).entrySet()) {
            Item item = entry.getKey();
            stock.add(new BuilderPayloads.Stock(item, entry.getValue(),
                BuilderStock.count(hutStock, item) + BuilderStock.bagCount(builder.bag, item),
                BuilderStock.count(warehouses, item), carrying.getOrDefault(item, 0)));
        }
        stock.sort(Comparator.comparingInt(BuilderPayloads.Stock::shortfall).reversed()
            .thenComparing(s -> BuiltInRegistries.ITEM.getKey(s.item()).toString()));
        int materialCount = stock.size();
        if (stock.size() > BuilderNeedsPayload.MAX_MATERIALS)
            stock = new ArrayList<>(stock.subList(0, BuilderNeedsPayload.MAX_MATERIALS));
        List<BuilderNeedsPayload.Help> help = new ArrayList<>();
        int helpCount = 0;
        int phase = -1;
        for (int i = 0; i < job.size(); i++) {
            if (job.isDone(i)) continue;
            if (phase < 0 && !job.isSkipped(i) && !job.isBlocked(i)) phase = job.phase(i).ordinal();
            if (job.isSkipped(i) || job.isBlocked(i)) {
                helpCount++;
                if (help.size() < BuilderNeedsPayload.MAX_HELP)
                    help.add(new BuilderNeedsPayload.Help(job.pos(i), job.state(i).getBlock().getDescriptionId()));
            }
        }
        return new BuilderNeedsPayload(builder.getId(), builder.getUUID(), token, opening,
            limited(builder.getSettlerName(), 128), limited(job.label, 128), queued, phase,
            job.doneCount(), job.size(), job.status.ordinal(), job.statusArgs.stream()
                .limit(8).map(s -> limited(s, 128)).toList(), List.copyOf(stock), materialCount,
            List.copyOf(help), helpCount);
    }

    /** OPEN/RESERVED are still warehouse stock. Only picked-up, undelivered physical cargo counts. */
    private static Map<Item, Integer> carriedToHut(ServerLevel level, Settlement settlement, @Nullable Building hut) {
        Map<Item, Integer> out = new HashMap<>();
        RequestLedgerSavedData saved = RequestLedgerSavedData.existing(level);
        RequestLedger ledger = saved == null ? null : saved.existing(settlement.id);
        if (hut == null || ledger == null) return out;
        Map<UUID, Map<Item, Integer>> counted = new HashMap<>();
        for (RequestRecord row : ledger.active()) {
            if (row.type() != RequestType.MATERIAL_INPUT || !hut.id.equals(row.targetBuildingId())
                || row.courierId() == null || !row.dimensionId().equals(level.dimension().location())
                || !(level.getEntity(row.courierId()) instanceof SettlerEntity courier) || !courier.isAlive()) continue;
            ItemStack prototype = row.fingerprint().prototype(level.registryAccess());
            if (prototype.isEmpty() || !prototype.getComponentsPatch().isEmpty()) continue;
            Item item = prototype.getItem();
            Map<Item, Integer> used = counted.computeIfAbsent(courier.getUUID(), id -> new HashMap<>());
            int available = Math.max(0, BuilderStock.bagCount(courier.bag, item) - used.getOrDefault(item, 0));
            int units = Math.min(available, Math.max(0, row.movedCount() - row.deliveredCount()));
            out.merge(item, units, Integer::sum);
            used.merge(item, units, Integer::sum);
        }
        return out;
    }

    private static String limited(String text, int max) { return text.length() <= max ? text : text.substring(0, max); }
}
