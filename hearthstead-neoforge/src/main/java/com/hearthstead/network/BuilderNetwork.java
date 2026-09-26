package com.hearthstead.network;

import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.building.BuildingLevelChecklist;
import com.hearthstead.building.BuildingLevels;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.builder.Blueprint;
import com.hearthstead.settlement.builder.BlueprintLibrary;
import com.hearthstead.settlement.builder.BlueprintStyles;
import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildJobs;
import com.hearthstead.settlement.builder.BuildPlanner;
import com.hearthstead.settlement.builder.BuildSiteSavedData;
import com.hearthstead.settlement.builder.BuilderMaterials;
import com.hearthstead.settlement.builder.BuilderStock;
import com.hearthstead.settlement.builder.BuilderSupply;
import com.hearthstead.settlement.builder.BuilderUnlocks;
import com.hearthstead.settlement.builder.UpgradePlanner;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of the Builder's Plan: every request is re-validated here
 * against the live world, the settlement's unlocks and the feature switch.
 * Nothing the client sends is trusted beyond "where" and "which".
 */
public final class BuilderNetwork {

    /** Sites are pushed to players within this distance of the settlement edge. */
    public static final int SITES_RANGE = 64;
    private static final Map<UUID, Integer> SENT_REVISION = new HashMap<>();
    /**
     * Which player objects already hold each settlement's current snapshot.
     * Weak and per instance: a player who walks into range later, joins late
     * or relogs (a new ServerPlayer) still receives the unchanged snapshot
     * (BH-26: waiting sites stayed invisible to them until the next change).
     */
    private static final Map<UUID, java.util.Set<ServerPlayer>> SENT_TO = new HashMap<>();

    private BuilderNetwork() {
    }

    public static void handle(ServerPlayer player, BuilderActionPayload payload) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (!HearthsteadServerConfig.builderEnabled()) {
            // Inert when switched off: answer honestly, change nothing.
            if (payload.action() == BuilderActionPayload.Action.CATALOG) {
                PayloadSend.toPlayer(player, new BuilderPayloads.Catalog(false, false, false,
                    false, false, List.of(), List.of(), 0, 0, 0, false));
            } else {
                refuse(player, payload, BuilderPayloads.Validation.BLUEPRINT, "hearthstead.builder.refuse.disabled");
            }
            return;
        }
        Settlement settlement = SettlementManager.at(level, player.blockPosition());
        if (settlement == null) {
            refuse(player, payload, BuilderPayloads.Validation.BLUEPRINT, "hearthstead.builder.refuse.no_settlement");
            return;
        }
        switch (payload.action()) {
            case CATALOG -> {
                PayloadSend.toPlayer(player, catalog(level, settlement));
                sendSites(level, settlement, player);
            }
            case PREVIEW -> preview(level, settlement, player, payload.text(), matchTownStyle(payload));
            case VALIDATE, PLACE -> blueprint(level, settlement, player, payload);
            case VALIDATE_LINE, PLACE_LINE -> line(level, settlement, player, payload);
            case SITE -> {
                String key = BuildJobs.act(level, settlement, payload.id(),
                    BuildJobs.SiteAction.byOrdinal(payload.number()), player);
                player.displayClientMessage(Component.translatable(key), true);
                sendSites(level, settlement, player);
            }
            case VALIDATE_UPGRADE, ORDER_UPGRADE -> upgrade(level, settlement, player, payload);
            case SAVE_DESIGN -> saveDesign(level, settlement, player, payload);
            case DELETE_DESIGN -> {
                var designs = com.hearthstead.settlement.builder.PlayerDesignSavedData.get(level.getServer());
                var design = designs.get(payload.text());
                // QA Q-007: only its surveyor (or an operator) deletes a design.
                if (design != null && design.owner() != null && !design.owner().equals(player.getUUID())
                    && !player.hasPermissions(2)) {
                    player.displayClientMessage(Component.translatable("hearthstead.builder.design.not_yours")
                        .withStyle(ChatFormatting.RED), true);
                    return;
                }
                boolean removed = designs.remove(payload.text());
                if (removed) {
                    player.displayClientMessage(Component.translatable("hearthstead.builder.design.deleted"), true);
                }
                PayloadSend.toPlayer(player, catalog(level, settlement));
            }
            case SETTINGS -> {
                var pickups = BuildSiteSavedData.Pickup.values();
                var fills = BuildSiteSavedData.Fill.values();
                int p = Math.floorMod(payload.number(), pickups.length);
                int f = Math.floorMod(payload.rotation(), fills.length);
                BuildSiteSavedData.get(level).setSettings(settlement.id,
                    new BuildSiteSavedData.Settings(pickups[p], fills[f]));
                PayloadSend.toPlayer(player, catalog(level, settlement));
            }
            case DECONSTRUCT -> deconstruct(level, settlement, player, payload);
        }
    }

    /** Survey Rod: capture the box as a shared design ("Our Designs"). */
    private static void saveDesign(ServerLevel level, Settlement settlement, ServerPlayer player,
                                   BuilderActionPayload p) {
        String refusal = com.hearthstead.settlement.builder.PlayerDesignSavedData.refusal(p.a(), p.b());
        // The surveyor must stand by what he surveys: no remote captures.
        double reach = 48.0D;
        if (refusal == null && (player.blockPosition().distSqr(p.a()) > reach * reach
            || player.blockPosition().distSqr(p.b()) > reach * reach)) {
            refusal = "hearthstead.builder.refuse.unloaded";
        }
        if (refusal != null) {
            player.displayClientMessage(Component.translatable(refusal).withStyle(ChatFormatting.RED), true);
            return;
        }
        var data = com.hearthstead.settlement.builder.PlayerDesignSavedData.get(level.getServer());
        var design = data.capture(level, p.a(), p.b(), p.text(), player.getUUID());
        if (design == null) {
            player.displayClientMessage(Component.translatable("hearthstead.builder.design.full")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        player.displayClientMessage(Component.translatable("hearthstead.builder.design.saved", design.name(),
            design.cells().length / 2).withStyle(ChatFormatting.GOLD), false);
    }

    private static void deconstruct(ServerLevel level, Settlement settlement, ServerPlayer player,
                                    BuilderActionPayload p) {
        Building building = null;
        for (Building b : settlement.buildings) {
            if (b.id.equals(p.id())) {
                building = b;
            }
        }
        BuildPlanner.Plan plan = BuildPlanner.planDeconstruct(level, settlement, building, player.getUUID());
        if (p.flag() && plan.job() != null && plan.validation().ok()) {
            commit(level, settlement, player, plan.job(), false);
            return;
        }
        send(player, p, BuilderPayloads.Validation.DECONSTRUCT, plan.validation(), level, settlement, p.id());
    }

    // ------------------------------------------------------------ catalog ---

    static BuilderPayloads.Catalog catalog(ServerLevel level, Settlement settlement) {
        List<BuilderPayloads.CatalogEntry> entries = new ArrayList<>();
        for (Blueprint blueprint : BlueprintLibrary.all(level.getServer())) {
            String lock = BuilderUnlocks.blueprintLock(level, settlement, blueprint);
            Map<Item, Integer> bill = new java.util.LinkedHashMap<>();
            for (Blueprint.Cell cell : blueprint.cells()) {
                if (cell.state().isAir()) {
                    continue;
                }
                for (BuilderMaterials.ItemCount cost : BuilderMaterials.costsOf(cell.state())) {
                    if (cost.buildable()) {
                        bill.merge(cost.item(), cost.count(), Integer::sum);
                    }
                }
            }
            List<net.minecraft.core.BlockPos> water = new ArrayList<>();
            for (Blueprint.Cell cell : blueprint.cells()) {
                if (BuilderMaterials.waterSource(cell.state())) {
                    water.add(new net.minecraft.core.BlockPos(cell.x(), cell.y(), cell.z()));
                }
            }
            if (!water.isEmpty()) {
                bill.merge(net.minecraft.world.item.Items.WATER_BUCKET, BuilderMaterials.waterBuckets(water), Integer::sum);
            }
            List<BuilderPayloads.ItemLine> lines = new ArrayList<>();
            bill.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(BuilderPayloads.MAX_LINES)
                .forEach(e -> lines.add(new BuilderPayloads.ItemLine(e.getKey(), e.getValue())));
            var meta = blueprint.meta();
            BlueprintLibrary.PresetInfo preset = BlueprintLibrary.presetOf(blueprint);
            entries.add(new BuilderPayloads.CatalogEntry(meta.id(), meta.name() == null ? "" : meta.name(),
                meta.category(), meta.kind().id(), meta.style(), blueprint.sizeX(), blueprint.sizeY(),
                blueprint.sizeZ(), blueprint.solidCount(), lock == null ? "" : lock, lines,
                preset.group(), preset.label()));
        }
        List<BuilderPayloads.UpgradeRow> upgrades = new ArrayList<>();
        for (Building building : settlement.buildings) {
            if (!building.valid || BuildingLevels.maxLevel(building.type) <= 1) {
                continue;
            }
            List<BuilderPayloads.GapRow> gaps = new ArrayList<>();
            for (BuildingLevelChecklist.Gap gap : building.nextLevelGap) {
                gaps.add(new BuilderPayloads.GapRow(gap.id(), gap.have(), gap.needed(), gap.fix().builderCanDo()));
            }
            upgrades.add(new BuilderPayloads.UpgradeRow(building.id, building.type.id(), building.level,
                BuildingLevels.maxLevel(building.type), building.plaquePos, gaps));
        }
        return new BuilderPayloads.Catalog(true, BuildJobs.hasBuilder(level, settlement),
            BuilderUnlocks.owns(level, settlement, BuilderUnlocks.DEFENSE_PLANS),
            BuilderUnlocks.owns(level, settlement, BuilderUnlocks.MASONRY),
            BuilderUnlocks.owns(level, settlement, BuilderUnlocks.BUILDERS_HUT),
            entries, upgrades, BuilderUnlocks.hutLevel(settlement),
            BuildSiteSavedData.get(level).settings(settlement.id).pickup().ordinal(),
            BuildSiteSavedData.get(level).settings(settlement.id).fill().ordinal(),
            BlueprintStyles.available());
    }

    /** PREVIEW / VALIDATE / PLACE: bit 1 of {@code number} = "Match town style". */
    public static final int MATCH_TOWN_STYLE = 1;

    private static boolean matchTownStyle(BuilderActionPayload payload) {
        return (payload.number() & MATCH_TOWN_STYLE) != 0;
    }

    private static void preview(ServerLevel level, Settlement settlement, ServerPlayer player, String id,
                                boolean townStyle) {
        Blueprint blueprint = BlueprintStyles.apply(level, settlement,
            BlueprintLibrary.get(level.getServer(), id), townStyle);
        if (blueprint == null) {
            return;
        }
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        List<Integer> packed = new ArrayList<>();
        for (Blueprint.Cell cell : blueprint.cells()) {
            if (cell.state().isAir()) {
                continue;
            }
            Integer i = index.get(cell.state());
            if (i == null) {
                i = palette.size();
                palette.add(cell.state());
                index.put(cell.state(), i);
            }
            packed.add(cell.x() | cell.y() << 8 | cell.z() << 16);
            packed.add(i);
        }
        int[] cells = new int[packed.size()];
        for (int k = 0; k < cells.length; k++) {
            cells[k] = packed.get(k);
        }
        PayloadSend.toPlayer(player, new BuilderPayloads.Preview(blueprint.id(), blueprint.sizeX(),
            blueprint.sizeY(), blueprint.sizeZ(), palette, cells));
    }

    // --------------------------------------------------------- blueprints ---

    private static void blueprint(ServerLevel level, Settlement settlement, ServerPlayer player,
                                  BuilderActionPayload p) {
        Blueprint blueprint = BlueprintStyles.apply(level, settlement,
            BlueprintLibrary.get(level.getServer(), p.text()), matchTownStyle(p));
        if (blueprint == null) {
            refuse(player, p, BuilderPayloads.Validation.BLUEPRINT, "hearthstead.builder.refuse.unknown");
            return;
        }
        String lock = BuilderUnlocks.blueprintLock(level, settlement, blueprint);
        if (lock != null) {
            refuse(player, p, BuilderPayloads.Validation.BLUEPRINT, lock);
            return;
        }
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(level, settlement, blueprint, p.a(),
            p.rotation(), p.mirror(), player.getUUID());
        String hutLock = plan.job() == null ? null : BuilderUnlocks.sizeLock(settlement,
            Math.max(blueprint.sizeX(), blueprint.sizeZ()), 0, plan.job().size());
        if (hutLock != null) {
            refuse(player, p, BuilderPayloads.Validation.BLUEPRINT, hutLock);
            return;
        }
        if (p.action() == BuilderActionPayload.Action.PLACE && plan.job() != null && plan.validation().ok()) {
            commit(level, settlement, player, plan.job(), p.flag());
            return;
        }
        send(player, p, BuilderPayloads.Validation.BLUEPRINT, plan.validation(), level, settlement,
            BuilderActionPayload.NONE);
    }

    private static void line(ServerLevel level, Settlement settlement, ServerPlayer player,
                             BuilderActionPayload p) {
        String kind = p.text();
        if (!BuildPlanner.PALISADE.equals(kind) && !BuildPlanner.STONE.equals(kind)
            && !BuildPlanner.BARRICADE.equals(kind)) {
            refuse(player, p, BuilderPayloads.Validation.LINE, "hearthstead.builder.refuse.unknown");
            return;
        }
        String unlock = BuilderUnlocks.forLine(kind);
        if (!BuilderUnlocks.owns(level, settlement, unlock)) {
            refuse(player, p, BuilderPayloads.Validation.LINE, "hearthstead.builder.lock." + unlock);
            return;
        }
        BuildPlanner.Plan plan = BuildPlanner.planLine(level, settlement, p.a(), p.b(), kind, p.flag(),
            p.number(), player.getUUID());
        if (p.action() == BuilderActionPayload.Action.PLACE_LINE && plan.job() != null && plan.validation().ok()) {
            commit(level, settlement, player, plan.job(), false);
            return;
        }
        send(player, p, BuilderPayloads.Validation.LINE, plan.validation(), level, settlement,
            BuilderActionPayload.NONE);
    }

    private static void upgrade(ServerLevel level, Settlement settlement, ServerPlayer player,
                                BuilderActionPayload p) {
        if (!BuilderUnlocks.owns(level, settlement, BuilderUnlocks.BUILDERS_HUT)) {
            refuse(player, p, BuilderPayloads.Validation.UPGRADE, "hearthstead.builder.lock.builders_hut");
            return;
        }
        if (BuilderUnlocks.hutLevel(settlement) < BuilderUnlocks.UPGRADE_ORDER_HUT_LEVEL) {
            refuse(player, p, BuilderPayloads.Validation.UPGRADE, "hearthstead.builder.lock.hut_upgrades");
            return;
        }
        Building building = null;
        for (Building b : settlement.buildings) {
            if (b.id.equals(p.id())) {
                building = b;
            }
        }
        UpgradePlanner.Result result = building == null ? null
            : UpgradePlanner.plan(level, settlement, building, player.getUUID());
        if (result == null) {
            refuse(player, p, BuilderPayloads.Validation.UPGRADE, "hearthstead.builder.site.missing");
            return;
        }
        BuildPlanner.Plan plan = result.plan();
        if (p.action() == BuilderActionPayload.Action.ORDER_UPGRADE && plan.job() != null
            && plan.validation().ok()) {
            commit(level, settlement, player, plan.job(), false);
            return;
        }
        send(player, p, BuilderPayloads.Validation.UPGRADE, plan.validation(), level, settlement, p.id());
    }

    private static void commit(ServerLevel level, Settlement settlement, ServerPlayer player, BuildJob job,
                               boolean allowOverwrite) {
        job.allowOverwrite = allowOverwrite;
        String refusal = BuildJobs.commit(level, settlement, job);
        if (refusal != null) {
            player.displayClientMessage(Component.translatable(refusal).withStyle(ChatFormatting.RED), false);
            return;
        }
        player.displayClientMessage(Component.translatable("hearthstead.builder.placed", job.label)
            .withStyle(ChatFormatting.GOLD), false);
        sendSites(level, settlement, player);
    }

    // --------------------------------------------------------- validation ---

    private static void refuse(ServerPlayer player, BuilderActionPayload p, int kind, String key) {
        PayloadSend.toPlayer(player, new BuilderPayloads.Validation(kind, p.text(), p.a(), p.b(),
            p.rotation(), p.mirror(), p.number(), p.flag(), p.id(), false, key, List.of(), 0, 0, 0,
            List.of(), 0, List.of()));
    }

    private static void send(ServerPlayer player, BuilderActionPayload p, int kind,
                             BuildPlanner.Validation v, ServerLevel level, Settlement settlement, UUID target) {
        List<BuilderPayloads.Stock> stock = new ArrayList<>();
        Building hut = BuildJobs.anyHut(settlement);
        List<Container> hutStock = BuilderStock.hutContainers(level, hut);
        for (Map.Entry<Item, Integer> e : v.materials().entrySet()) {
            stock.add(new BuilderPayloads.Stock(e.getKey(), e.getValue(), BuilderStock.count(hutStock, e.getKey()),
                BuilderStock.warehouseCount(level, settlement, e.getKey()), 0));
        }
        PayloadSend.toPlayer(player, new BuilderPayloads.Validation(kind, p.text(), p.a(), p.b(),
            p.rotation(), p.mirror(), p.number(), p.flag(), target, v.ok(), v.reasonKey(), v.reasonArgs(),
            v.steps(), v.clears(), v.fills(), v.playerBlocks(), v.playerBlockCount(), stock));
    }

    // -------------------------------------------------------------- sites ---

    /** The sites snapshot of a settlement: active first (queue order), then recent. */
    public static BuilderPayloads.Sites sites(ServerLevel level, Settlement settlement) {
        BuildSiteSavedData data = BuildSiteSavedData.get(level);
        List<BuildJob> ordered = new ArrayList<>(data.activeJobs(settlement.id));
        for (BuildJob job : data.jobs(settlement.id)) {
            if (job.state != BuildJob.State.ACTIVE) {
                ordered.add(job);
            }
        }
        List<BuilderPayloads.Site> out = new ArrayList<>();
        int position = 0;
        for (BuildJob job : ordered) {
            if (out.size() >= BuilderPayloads.MAX_SITES) {
                break;
            }
            List<BuilderPayloads.Stock> missing = new ArrayList<>();
            if (job.state == BuildJob.State.ACTIVE) {
                Building hut = BuildJobs.anyHut(settlement);
                Container bag = null;
                if (job.claimant != null && level.getEntity(job.claimant) instanceof SettlerEntity builder) {
                    bag = builder.bag;
                    Building own = BuildJobs.hutOf(settlement, builder);
                    if (own != null) {
                        hut = own;
                    }
                }
                for (BuilderSupply.Line line : BuilderSupply.missing(level, settlement, job, hut, bag)) {
                    missing.add(new BuilderPayloads.Stock(line.item(), line.needed(), line.inHut(),
                        line.inWarehouse(), line.onTheWay()));
                }
            }
            out.add(new BuilderPayloads.Site(job.id, job.label, job.kind.ordinal(), job.bounds.minX(),
                job.bounds.minY(), job.bounds.minZ(), job.bounds.maxX(), job.bounds.maxY(), job.bounds.maxZ(),
                job.progress(), job.status.ordinal(), job.statusArgs, job.paused, job.rush, job.skippedCount(),
                job.blockedCount(), job.claimant == null ? BuilderActionPayload.NONE : job.claimant,
                job.state == BuildJob.State.ACTIVE ? position++ : -1, missing));
        }
        return new BuilderPayloads.Sites(settlement.id, data.revision(), out);
    }

    public static void sendSites(ServerLevel level, Settlement settlement, ServerPlayer player) {
        PayloadSend.toPlayer(player, sites(level, settlement));
    }

    /**
     * Periodic push (every 40 ticks from the level tick): players near a
     * settlement with sites get its snapshot when it changed, or while a site
     * is active (progress moves). Bounded: one snapshot per nearby player.
     */
    public static void broadcast(ServerLevel level, Settlement settlement) {
        BuildSiteSavedData data = BuildSiteSavedData.existing(level);
        if (data == null || data.jobs(settlement.id).isEmpty() || settlement.center == null) {
            return;
        }
        boolean active = !data.activeJobs(settlement.id).isEmpty();
        Integer sent = SENT_REVISION.get(settlement.id);
        boolean fresh = active || sent == null || sent != data.revision();
        java.util.Set<ServerPlayer> holders = SENT_TO.computeIfAbsent(settlement.id,
            ignored -> java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));
        if (fresh) {
            SENT_REVISION.put(settlement.id, data.revision());
            holders.clear();
        }
        BuilderPayloads.Sites snapshot = null;
        double reach = settlement.radius + SITES_RANGE;
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(settlement.center) > reach * reach) {
                continue;
            }
            if (!fresh && holders.contains(player)) {
                continue;
            }
            if (snapshot == null) {
                snapshot = sites(level, settlement);
            }
            if (PayloadSend.toPlayer(player, snapshot)) {
                holders.add(player);
            }
        }
    }

    /** Test/diagnostic window. */
    @Nullable
    static BlockPos unused() {
        return null;
    }
}
