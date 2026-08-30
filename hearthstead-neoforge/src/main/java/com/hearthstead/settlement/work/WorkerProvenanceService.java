package com.hearthstead.settlement.work;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.equipment.EquipmentRequirement;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.settlement.work.WorkerProvenanceSavedData.ActionView;
import com.hearthstead.settlement.work.WorkerProvenanceSavedData.DepositReceipt;
import com.hearthstead.settlement.work.WorkerProvenanceSavedData.Kind;
import com.hearthstead.settlement.work.WorkerProvenanceSavedData.Phase;
import com.hearthstead.settlement.work.WorkerStackProvenance.Transit;
import com.hearthstead.settlement.work.WorkerStackProvenance.TransitKind;
import com.hearthstead.settlement.workzone.WorkZone;
import com.hearthstead.settlement.workzone.WorkZoneService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Server-thread transaction owner for Lumberer and Farmer provenance. */
public final class WorkerProvenanceService {
    private static final int MIN_NATURAL_LEAVES = 4;
    private static final int MAX_TREE_DRIFT = 8;
    private static final int MAX_READINESS_RESIDENTS = 256;

    public record DepositResult(ItemStack remainder,
                                @Nullable DepositReceipt receipt) {
        public DepositResult {
            remainder = remainder == null ? ItemStack.EMPTY : remainder.copy();
        }

        public int inserted(int offered) {
            return Math.max(0, offered - remainder.getCount());
        }
    }

    /**
     * Bounded, read-only first-raid input. It never scans chunks or mutates
     * state: current buildings, persisted action/receipt rows and directly
     * indexed loaded resident inventories are the complete observation set.
     */
    public record ReadinessEvidence(boolean authoritative,
                                    boolean lumberZoneCommitted,
                                    boolean farmZoneCommitted,
                                    boolean lumberWorkCommitted,
                                    boolean farmSeedPlantedCommitted,
                                    boolean farmHarvestCommitted,
                                    boolean lumberOutputCommitted,
                                    boolean farmOutputCommitted,
                                    boolean allResidentStacksObserved,
                                    boolean unresolvedWorkerTransit,
                                    boolean conflictingStackOwnership,
                                    int actionRows, int receiptRows,
                                    int inspectedResidents,
                                    int inspectedTransitStacks) {
        public boolean ready() {
            return authoritative && lumberZoneCommitted && farmZoneCommitted
                && lumberWorkCommitted && farmSeedPlantedCommitted
                && farmHarvestCommitted && lumberOutputCommitted
                && farmOutputCommitted && allResidentStacksObserved
                && !unresolvedWorkerTransit && !conflictingStackOwnership;
        }
    }

    /** See {@link ReadinessEvidence}; no chunk/entity search is performed. */
    public static ReadinessEvidence readinessEvidence(ServerLevel level,
                                                       Settlement settlement) {
        if (level == null || settlement == null
            || level.getServer() == null || !level.getServer().isSameThread()
            || !liveReadOnly(level, settlement)) {
            return unavailableReadiness();
        }
        WorkerProvenanceSavedData persisted =
            WorkerProvenanceSavedData.existing(level);
        if (persisted != null
            && !persisted.readableIn(level.dimension().location())) {
            return unavailableReadiness();
        }
        // Absence is authoritative evidence that no work has been committed;
        // keep that projection ephemeral so the query remains truly read-only.
        WorkerProvenanceSavedData data = persisted == null
            ? new WorkerProvenanceSavedData() : persisted;
        boolean lumberZone = false;
        boolean farmZone = false;
        for (Building building : settlement.buildings) {
            if (building == null || building.id == null
                || building.type == null) {
                return unavailableReadiness();
            }
            WorkZone zone = building.workZone().orElse(null);
            if (!building.valid || zone == null || building.workZoneQuarantined()
                || building.workZoneRevision() <= 0
                || !zone.settlementId().equals(settlement.id)
                || !zone.buildingId().equals(building.id)
                || !zone.dimension().equals(level.dimension().location())
                || !exactRegistered(settlement, building)) {
                continue;
            }
            lumberZone |= building.type == BuildingType.LUMBER_CAMP
                && zone.type() == WorkZone.Type.LUMBER;
            farmZone |= building.type == BuildingType.FARMHOUSE
                && zone.type() == WorkZone.Type.FARM;
        }

        List<ActionView> actions = data.actionsForSettlement(settlement.id);
        Map<UUID, ActionView> byId = new HashMap<>();
        boolean lumberWork = false;
        boolean farmPlant = false;
        boolean farmHarvest = false;
        boolean lumberOutput = false;
        boolean farmOutput = false;
        boolean persistedUnresolved = false;
        int receiptRows = 0;
        boolean conflicting = false;
        for (ActionView action : actions) {
            if (byId.putIfAbsent(action.id(), action) != null) {
                conflicting = true;
                continue;
            }
            boolean current = currentActionBuilding(settlement, level, action);
            if (current && action.workTerminal()) {
                lumberWork |= action.kind() == Kind.LUMBER_TREE;
                farmPlant |= action.kind() == Kind.FARM_PLANT;
                farmHarvest |= action.kind() == Kind.FARM_HARVEST;
            }
            if (hasPersistedRemainder(action)) {
                // This catches world drops, sacks and unloaded physical
                // carriers without scanning for them. The persisted
                // conservation remainder itself is sufficient authority.
                persistedUnresolved = true;
                if (!current) {
                    // A stale zone/building can never accept this remainder;
                    // retain the stronger conflict signal after restart even
                    // when the physical carrier itself is unloaded.
                    conflicting = true;
                }
            }
            List<DepositReceipt> receipts = data.receiptsFor(action.id());
            receiptRows += receipts.size();
            Map<ResourceLocation, Integer> receiptTotals = new HashMap<>();
            for (DepositReceipt receipt : receipts) {
                boolean validReceipt = receipt.settlementId().equals(settlement.id)
                    && receipt.buildingId().equals(action.zone().buildingId())
                    && receipt.workerId().equals(action.workerId())
                    && receipt.dimension().equals(level.dimension().location())
                    && action.deposited().getOrDefault(receipt.itemId(), 0)
                        >= receipt.count();
                if (!validReceipt) {
                    conflicting = true;
                    continue;
                }
                int previous = receiptTotals.getOrDefault(receipt.itemId(), 0);
                if (previous > Integer.MAX_VALUE - receipt.count()) {
                    conflicting = true;
                    continue;
                }
                receiptTotals.put(receipt.itemId(), previous + receipt.count());
                if (current && action.kind() == Kind.LUMBER_TREE) {
                    lumberOutput = true;
                } else if (current && action.kind() == Kind.FARM_HARVEST) {
                    farmOutput = true;
                }
            }
            for (Map.Entry<ResourceLocation, Integer> total
                    : receiptTotals.entrySet()) {
                if (total.getValue()
                    > action.deposited().getOrDefault(total.getKey(), 0)) {
                    conflicting = true;
                }
            }
        }

        int residents = 0;
        int transitStacks = 0;
        boolean allObserved = true;
        boolean unresolved = persistedUnresolved;
        Map<StackKey, Integer> carried = new HashMap<>();
        Set<UUID> observedRecords = new HashSet<>();
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (++residents > MAX_READINESS_RESIDENTS) {
                allObserved = false;
                conflicting = true;
                break;
            }
            if (record == null || record.entityId == null
                || record.profession == null
                || !observedRecords.add(record.entityId)) {
                allObserved = false;
                conflicting = true;
                continue;
            }
            Entity entity = level.getEntity(record.entityId);
            if (!(entity instanceof SettlerEntity settler)
                || !settler.isAlive()
                || settler.isTraveler()
                || !settlement.id.equals(settler.getSettlementId())
                || !java.util.Objects.equals(settlement.center,
                    settler.getHearthPos())
                || record.profession != settler.getProfession()) {
                allObserved = false;
                continue;
            }
            List<ItemStack> observed = new ArrayList<>(
                settler.bag.getContainerSize() + 2);
            for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
                observed.add(settler.bag.getItem(slot));
            }
            observed.add(settler.getMainHandItem());
            observed.add(settler.getOffhandItem());
            for (ItemStack stack : observed) {
                if (!WorkerStackProvenance.hasTransitMarker(stack)) {
                    continue;
                }
                transitStacks++;
                Transit transit = WorkerStackProvenance.readTransit(stack)
                    .orElse(null);
                ResourceLocation item = itemId(stack);
                ActionView action = transit == null ? null
                    : byId.get(transit.actionId());
                if (transit == null || item == null || action == null
                    || !transit.settlementId().equals(settlement.id)
                    || !transit.workerId().equals(settler.getUUID())
                    || !action.workerId().equals(settler.getUUID())
                    || !transit.buildingId().equals(action.zone().buildingId())
                    || !transit.dimension().equals(level.dimension().location())
                    || (transit.kind() == TransitKind.FARM_SEED_INPUT
                        && transit.sourcePos() != action.source().asLong())
                    || !currentActionBuilding(settlement, level, action)) {
                    conflicting = true;
                    continue;
                }
                boolean seed = transit.kind() == TransitKind.FARM_SEED_INPUT;
                BlockPos outputSource = BlockPos.of(transit.sourcePos());
                boolean compatible = seed
                    ? action.kind() == Kind.FARM_PLANT
                        && action.phase() == Phase.INPUT_HELD
                        && java.util.Objects.equals(action.inputItem(), item)
                    : transit.kind() == TransitKind.LUMBER_LOG
                        ? action.kind() == Kind.LUMBER_TREE
                            && action.resolved().contains(outputSource)
                        : action.kind() == Kind.FARM_HARVEST
                            && action.resolved().contains(outputSource);
                if (!compatible) {
                    conflicting = true;
                    continue;
                }
                carried.merge(new StackKey(action.id(), item, seed),
                    stack.getCount(), Integer::sum);
                unresolved = true;
            }
        }
        for (Map.Entry<StackKey, Integer> row : carried.entrySet()) {
            ActionView action = byId.get(row.getKey().actionId());
            int authorised = row.getKey().seed()
                ? action.inputCount() : action.remaining(row.getKey().item());
            if (row.getValue() <= 0 || row.getValue() > authorised) {
                conflicting = true;
            }
        }
        return new ReadinessEvidence(true, lumberZone, farmZone, lumberWork,
            farmPlant, farmHarvest, lumberOutput, farmOutput, allObserved,
            unresolved, conflicting, actions.size(), receiptRows,
            Math.min(residents, MAX_READINESS_RESIDENTS), transitStacks);
    }

    private static boolean currentActionBuilding(Settlement settlement,
                                                 ServerLevel level,
                                                 ActionView action) {
        Building building = exactBuilding(settlement,
            action.zone().buildingId());
        return building != null && building.valid
            && !building.workZoneQuarantined()
            && building.workZoneRevision() > 0
            && building.workZoneRevision() == action.zone().revision()
            && action.zone().withinPersistentLimits()
            && action.zone().settlementId().equals(settlement.id)
            && action.zone().buildingId().equals(building.id)
            && action.zone().equals(building.workZone().orElse(null))
            && action.zone().dimension().equals(level.dimension().location())
            && switch (action.kind()) {
                case LUMBER_TREE -> building.type == BuildingType.LUMBER_CAMP
                    && action.zone().type() == WorkZone.Type.LUMBER;
                case FARM_PLANT, FARM_HARVEST ->
                    building.type == BuildingType.FARMHOUSE
                        && action.zone().type() == WorkZone.Type.FARM;
            };
    }

    /** Pure bounded conservation predicate shared with restart tests. */
    static boolean hasPersistedRemainder(ActionView action) {
        return action != null && action.workTerminal()
            && action.produced().entrySet().stream().anyMatch(row ->
                action.remaining(row.getKey()) > 0);
    }

    @Nullable
    private static Building exactBuilding(Settlement settlement, UUID id) {
        if (settlement == null || id == null) {
            return null;
        }
        Building found = null;
        for (Building building : settlement.buildings) {
            if (building == null || building.id == null) {
                return null;
            }
            if (building.id.equals(id)) {
                if (found != null) {
                    return null;
                }
                found = building;
            }
        }
        return found;
    }

    private static ReadinessEvidence unavailableReadiness() {
        return new ReadinessEvidence(false, false, false, false, false,
            false, false, false, false, true, true, 0, 0, 0, 0);
    }

    private record StackKey(UUID actionId, ResourceLocation item,
                            boolean seed) {
    }

    @Nullable
    public static UUID beginLumberTree(ServerLevel level, Settlement settlement,
                                       Building camp, SettlerEntity worker,
                                       WorkZone zone, BlockPos root,
                                       List<BlockPos> plannedLogs) {
        Context context = context(level, settlement, camp, worker,
            BuildingType.LUMBER_CAMP, WorkZone.Type.LUMBER);
        if (context == null || !context.zone.equals(zone)
            || !naturalTreeMatches(level, zone, root, plannedLogs)
            || !serviceableTool(worker, Profession.LUMBERER)) {
            return null;
        }
        WorkerProvenanceSavedData data = WorkerProvenanceSavedData.get(level);
        if (data.quarantined()
            || data.activeFor(worker.getUUID(), Kind.LUMBER_TREE) != null) {
            return null;
        }
        UUID id = UUID.randomUUID();
        var action = new WorkerProvenanceSavedData.Action(id,
            Kind.LUMBER_TREE, Phase.ACTIVE, worker.getUUID(), zone, root,
            plannedLogs, level.getGameTime());
        return data.add(action) ? id : null;
    }

    @Nullable
    public static ActionView resumableLumberTree(ServerLevel level,
                                                 Settlement settlement,
                                                 Building camp,
                                                 SettlerEntity worker) {
        Context context = context(level, settlement, camp, worker,
            BuildingType.LUMBER_CAMP, WorkZone.Type.LUMBER);
        if (context == null) {
            return null;
        }
        WorkerProvenanceSavedData data = WorkerProvenanceSavedData.get(level);
        ActionView action = data.activeFor(worker.getUUID(), Kind.LUMBER_TREE);
        return action != null && action.zone().equals(context.zone)
            ? action : null;
    }

    public static boolean prepareLumberLog(ServerLevel level,
                                           Settlement settlement,
                                           Building camp,
                                           SettlerEntity worker,
                                           UUID actionId, BlockPos log) {
        Context context = context(level, settlement, camp, worker,
            BuildingType.LUMBER_CAMP, WorkZone.Type.LUMBER);
        return context != null && prepareToolOperation(level, context, actionId,
            Kind.LUMBER_TREE, log, Profession.LUMBERER);
    }

    /** Called only after the stamped physical log exists in world authority. */
    public static boolean completeLumberLog(ServerLevel level,
                                            Settlement settlement,
                                            Building camp,
                                            SettlerEntity worker,
                                            UUID actionId, BlockPos log,
                                            ItemStack produced) {
        Context context = context(level, settlement, camp, worker,
            BuildingType.LUMBER_CAMP, WorkZone.Type.LUMBER);
        ResourceLocation item = itemId(produced);
        if (context == null || item == null || produced.getCount() != 1) {
            return false;
        }
        return completeOperation(level, context, actionId, Kind.LUMBER_TREE,
            log, Map.of(item, 1), false);
    }

    /** Whole-tree terminal seam, after limbing/replant and all per-log uses. */
    public static boolean commitLumberTree(ServerLevel level,
                                           Settlement settlement,
                                           Building camp,
                                           SettlerEntity worker,
                                           UUID actionId) {
        Context context = context(level, settlement, camp, worker,
            BuildingType.LUMBER_CAMP, WorkZone.Type.LUMBER);
        if (context == null || !commitWork(level, context, actionId,
                Kind.LUMBER_TREE)) {
            return false;
        }
        JourneyServerHooks.noteLumberTreeCommitted(level, settlement, camp,
            worker, actionId);
        return true;
    }

    /**
     * Withdraws one accepted physical seed from the exact linked Farmhouse.
     * The action stays INPUT_HELD and authors no Journey evidence until that
     * same tagged unit is consumed into a planted block in its exact zone.
     */
    @Nullable
    public static UUID supplyOneSeed(ServerLevel level, Settlement settlement,
                                     Building farmhouse, SettlerEntity worker,
                                     Predicate<ItemStack> accepted) {
        WorkerStorageAuthority.Source source = WorkerStorageAuthority.find(level,
            farmhouse, accepted, worker == null ? null : worker.blockPosition());
        return source == null ? null : supplyOneSeedAt(level, settlement,
            farmhouse, worker, source.pos(), accepted, null);
    }

    /**
     * Withdraws from one exact, physically reached Farmhouse container. The
     * optional plant target is persisted in the same action before the source
     * inventory can change, making the paid input restart-safe from its first
     * authoritative tick.
     */
    @Nullable
    public static UUID supplyOneSeedAt(ServerLevel level,
                                       Settlement settlement,
                                       Building farmhouse,
                                       SettlerEntity worker,
                                       BlockPos sourcePos,
                                       Predicate<ItemStack> accepted,
                                       @Nullable BlockPos plannedTarget) {
        Context context = context(level, settlement, farmhouse, worker,
            BuildingType.FARMHOUSE, WorkZone.Type.FARM);
        if (context == null || accepted == null || sourcePos == null
            || (plannedTarget != null && !WorkZoneService.livePositionAllowed(
                level, context.zone, plannedTarget))
            || !ContainerApproach.inspect(level, worker, sourcePos).canInteract()
            || !serviceableTool(worker, Profession.FARMER)) {
            return null;
        }
        WorkerProvenanceSavedData data = WorkerProvenanceSavedData.get(level);
        if (data.quarantined()
            || data.activeFor(worker.getUUID(), Kind.FARM_PLANT) != null) {
            return null;
        }
        WorkerStorageAuthority.Source source = WorkerStorageAuthority.findAt(
            level, farmhouse, sourcePos, accepted);
        if (source == null) {
            return null;
        }
        UUID id = UUID.randomUUID();
        var action = new WorkerProvenanceSavedData.Action(id, Kind.FARM_PLANT,
            Phase.INPUT_HELD, worker.getUUID(), context.zone, source.pos(),
            plannedTarget == null ? List.of() : List.of(plannedTarget),
            level.getGameTime());
        action.inputItem = itemId(source.observed());
        action.inputCount = 1;
        if (action.inputItem == null || !data.add(action)) {
            return null;
        }
        var transfer = WorkerStorageAuthority.transferOneToBag(level, farmhouse,
            source, worker.bag, stack -> {
                boolean stamped = WorkerStackProvenance.stampTransit(stack, id,
                    TransitKind.FARM_SEED_INPUT, settlement.id, farmhouse.id,
                    worker.getUUID(), context.zone.dimension(),
                    source.pos().asLong());
                return stamped ? stack : ItemStack.EMPTY;
            });
        if (transfer == null || !transfer.conserved()) {
            data.removePending(id);
            return null;
        }
        action.updatedTick = level.getGameTime();
        data.changed(action, level.getGameTime());
        return id;
    }

    /** Exact active Farmhouse input action carried by this physical seed. */
    public static Optional<UUID> farmSeedAction(ServerLevel level,
                                                Settlement settlement,
                                                Building farmhouse,
                                                SettlerEntity worker,
                                                ItemStack seed) {
        Context context = context(level, settlement, farmhouse, worker,
            BuildingType.FARMHOUSE, WorkZone.Type.FARM);
        Transit transit = WorkerStackProvenance.readTransit(seed).orElse(null);
        if (context == null || transit == null
            || transit.kind() != TransitKind.FARM_SEED_INPUT
            || !transit.settlementId().equals(settlement.id)
            || !transit.buildingId().equals(farmhouse.id)
            || !transit.workerId().equals(worker.getUUID())
            || !transit.dimension().equals(context.zone.dimension())) {
            return Optional.empty();
        }
        WorkerProvenanceSavedData data = WorkerProvenanceSavedData.get(level);
        if (data.quarantined()) {
            return Optional.empty();
        }
        ActionView action = data.action(transit.actionId());
        ResourceLocation seedItem = itemId(seed);
        return action != null && action.kind() == Kind.FARM_PLANT
            && action.phase() == Phase.INPUT_HELD
            && action.zone().equals(context.zone)
            && java.util.Objects.equals(seedItem, action.inputItem())
            ? Optional.of(action.id()) : Optional.empty();
    }

    /**
     * Returns one still-unconsumed tagged input to the exact chest it came
     * from. This is the fail-closed recovery for a persisted plant target that
     * became invalid during rest, reload or another world change.
     */
    public static boolean returnFarmSeedAt(ServerLevel level,
                                           Settlement settlement,
                                           Building farmhouse,
                                           SettlerEntity worker,
                                           UUID actionId,
                                           BlockPos sourcePos) {
        ActionView recoverable = recoverableFarmPlant(level, settlement, worker);
        if (recoverable == null || farmhouse == null || actionId == null
            || sourcePos == null || !recoverable.id().equals(actionId)
            || !recoverable.zone().buildingId().equals(farmhouse.id)
            || !recoverable.source().equals(sourcePos)
            || !ContainerApproach.inspect(level, worker, sourcePos).canInteract()) {
            return false;
        }
        WorkerProvenanceSavedData data = WorkerProvenanceSavedData.get(level);
        WorkerProvenanceSavedData.Action action = data.mutable(actionId);
        if (action == null || action.kind != Kind.FARM_PLANT
            || !action.workerId.equals(worker.getUUID())
            || !action.zone.equals(recoverable.zone())
            || action.phase != Phase.INPUT_HELD
            || action.inputItem == null || action.inputCount != 1
            || !action.source.equals(sourcePos)
            || !action.resolved.isEmpty() || action.pendingOperation != null
            || !action.produced.isEmpty()) {
            return false;
        }
        int seedSlot = -1;
        ItemStack seed = ItemStack.EMPTY;
        for (int slot = 0; slot < worker.bag.getContainerSize(); slot++) {
            ItemStack candidate = worker.bag.getItem(slot);
            Transit transit = WorkerStackProvenance.readTransit(candidate)
                .orElse(null);
            ResourceLocation candidateItem = itemId(candidate);
            if (candidate.getCount() == 1 && transit != null
                && transit.actionId().equals(actionId)
                && transit.kind() == TransitKind.FARM_SEED_INPUT
                && transit.settlementId().equals(settlement.id)
                && transit.buildingId().equals(farmhouse.id)
                && transit.workerId().equals(worker.getUUID())
                && transit.dimension().equals(level.dimension().location())
                && transit.sourcePos() == sourcePos.asLong()
                && java.util.Objects.equals(candidateItem, action.inputItem)) {
                if (seedSlot >= 0) {
                    return false;
                }
                seedSlot = slot;
                seed = candidate;
            }
        }
        if (seedSlot < 0 || !data.removePending(actionId)) {
            return false;
        }
        WorkerStorageAuthority.Insert returned = WorkerStorageAuthority.insertAt(
            level, farmhouse, sourcePos, seed, 1, true);
        if (returned.inserted() != 1 || !returned.remainder().isEmpty()) {
            // The action was removed only to make the return one transaction.
            // Re-add the same in-memory row when the physical source refused
            // the item; the tagged bag unit remains untouched and resumable.
            data.add(action);
            return false;
        }
        worker.bag.setItem(seedSlot, ItemStack.EMPTY);
        return true;
    }

    /** Restart-safe exact target for an already prepared seed action. */
    @Nullable
    public static ActionView resumableFarmPlant(ServerLevel level,
                                                Settlement settlement,
                                                Building farmhouse,
                                                SettlerEntity worker) {
        ActionView action = recoverableFarmPlant(level, settlement, worker);
        return action != null && farmhouse != null
            && action.zone().buildingId().equals(farmhouse.id)
            && currentActionBuilding(settlement, level, action)
            ? action : null;
    }

    /**
     * Persisted seed-input authority that remains recoverable after the field
     * volume is replaced or quarantined. It can only return the exact tagged
     * unit to its exact employer building; it cannot mutate a field or author
     * progression evidence without {@link #resumableFarmPlant}'s live-zone
     * check.
     */
    @Nullable
    public static ActionView recoverableFarmPlant(ServerLevel level,
                                                  Settlement settlement,
                                                  SettlerEntity worker) {
        if (level == null || settlement == null || worker == null
            || !level.getServer().isSameThread()
            || SettlementManager.byId(level, settlement.id) != settlement
            || worker.level() != level || !worker.isAlive()
            || level.getEntity(worker.getId()) != worker
            || !settlement.id.equals(worker.getSettlementId())
            || settlement.record(worker.getUUID()) == null) {
            return null;
        }
        WorkerProvenanceSavedData data = WorkerProvenanceSavedData.get(level);
        ActionView action = data.activeFor(worker.getUUID(), Kind.FARM_PLANT);
        if (data.quarantined() || action == null
            || action.phase() != Phase.INPUT_HELD
            || action.zone().type() != WorkZone.Type.FARM
            || !action.zone().withinPersistentLimits()
            || !action.zone().settlementId().equals(settlement.id)
            || !action.zone().dimension().equals(level.dimension().location())) {
            return null;
        }
        Building farmhouse = exactBuilding(settlement,
            action.zone().buildingId());
        return farmhouse != null && farmhouse.valid
            && farmhouse.type == BuildingType.FARMHOUSE
            && farmhouse.contains(action.source())
            && farmhouse.workers.contains(worker.getUUID())
            && Employment.employerOf(settlement, worker.getUUID()) == farmhouse
            ? action : null;
    }

    public static boolean prepareFarmPlant(ServerLevel level,
                                           Settlement settlement,
                                           Building farmhouse,
                                           SettlerEntity worker,
                                           UUID actionId, BlockPos target) {
        Context context = context(level, settlement, farmhouse, worker,
            BuildingType.FARMHOUSE, WorkZone.Type.FARM);
        if (context == null || !WorkZoneService.livePositionAllowed(level,
                context.zone, target)) {
            return false;
        }
        WorkerProvenanceSavedData data = WorkerProvenanceSavedData.get(level);
        WorkerProvenanceSavedData.Action action = data.mutable(actionId);
        if (!matches(action, context, Kind.FARM_PLANT)
            || action.phase != Phase.INPUT_HELD) {
            return false;
        }
        if (action.planned.isEmpty()) {
            action.planned.add(target.immutable());
            data.changed(action, level.getGameTime());
        } else if (!action.planned.getFirst().equals(target)) {
            return false;
        }
        return prepareToolOperation(level, context, actionId, Kind.FARM_PLANT,
            target, Profession.FARMER);
    }

    /** Called after the exact tagged seed is consumed and crop block exists. */
    public static boolean commitFarmPlant(ServerLevel level,
                                          Settlement settlement,
                                          Building farmhouse,
                                          SettlerEntity worker,
                                          UUID actionId, BlockPos target,
                                          ResourceLocation plantedBlock) {
        Context context = context(level, settlement, farmhouse, worker,
            BuildingType.FARMHOUSE, WorkZone.Type.FARM);
        if (context == null || plantedBlock == null
            || !WorkZoneService.livePositionAllowed(level, context.zone, target)
            || !BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(target).getBlock()).equals(plantedBlock)) {
            return false;
        }
        if (!completeOperation(level, context, actionId, Kind.FARM_PLANT,
                target, Map.of(), true)) {
            return false;
        }
        JourneyServerHooks.noteFarmSeedPlantedCommitted(level, settlement,
            farmhouse, worker, actionId);
        return true;
    }

    @Nullable
    public static UUID beginFarmHarvest(ServerLevel level,
                                        Settlement settlement,
                                        Building farmhouse,
                                        SettlerEntity worker,
                                        BlockPos crop) {
        Context context = context(level, settlement, farmhouse, worker,
            BuildingType.FARMHOUSE, WorkZone.Type.FARM);
        if (context == null || !WorkZoneService.livePositionAllowed(level,
                context.zone, crop) || !serviceableTool(worker, Profession.FARMER)) {
            return null;
        }
        WorkerProvenanceSavedData data = WorkerProvenanceSavedData.get(level);
        ActionView existing = data.activeFor(worker.getUUID(), Kind.FARM_HARVEST);
        if (data.quarantined()) {
            return null;
        }
        if (existing != null) {
            return existing.zone().equals(context.zone)
                && existing.planned().size() == 1
                && existing.planned().getFirst().equals(crop)
                && prepareToolOperation(level, context, existing.id(),
                    Kind.FARM_HARVEST, crop, Profession.FARMER)
                ? existing.id() : null;
        }
        UUID id = UUID.randomUUID();
        var action = new WorkerProvenanceSavedData.Action(id, Kind.FARM_HARVEST,
            Phase.ACTIVE, worker.getUUID(), context.zone, crop, List.of(crop),
            level.getGameTime());
        if (!data.add(action)
            || !prepareToolOperation(level, context, id, Kind.FARM_HARVEST,
                crop, Profession.FARMER)) {
            data.removePending(id);
            return null;
        }
        return id;
    }

    /** Called after the block is gone and every stamped drop is physical. */
    public static boolean commitFarmHarvest(ServerLevel level,
                                            Settlement settlement,
                                            Building farmhouse,
                                            SettlerEntity worker,
                                            UUID actionId, BlockPos crop,
                                            List<ItemStack> produced) {
        Context context = context(level, settlement, farmhouse, worker,
            BuildingType.FARMHOUSE, WorkZone.Type.FARM);
        if (context == null || produced == null || produced.isEmpty()) {
            return false;
        }
        java.util.LinkedHashMap<ResourceLocation, Integer> counts =
            new java.util.LinkedHashMap<>();
        for (ItemStack stack : produced) {
            ResourceLocation item = itemId(stack);
            if (item == null || stack.getCount() <= 0) {
                return false;
            }
            counts.merge(item, stack.getCount(), Integer::sum);
            if (counts.size() > WorkerProvenanceSavedData.MAX_ITEM_ROWS) {
                return false;
            }
        }
        if (!completeOperation(level, context, actionId, Kind.FARM_HARVEST,
                crop, counts, true)) {
            return false;
        }
        JourneyServerHooks.noteFarmHarvestCommitted(level, settlement,
            farmhouse, worker, actionId);
        return true;
    }

    /**
     * Stamps a produced copy before it enters world or bag authority. The
     * full live workplace context is deliberately mandatory: a persisted
     * action alone is not authority after its building, dimension, worker or
     * exact Work Zone has changed.
     */
    public static boolean stampOutput(ServerLevel level, Settlement settlement,
                                      Building workplace, SettlerEntity worker,
                                      UUID actionId, ItemStack stack,
                                      BlockPos source) {
        WorkerProvenanceSavedData data = WorkerProvenanceSavedData.get(level);
        WorkerProvenanceSavedData.Action action = data.mutable(actionId);
        if (action == null || stack == null || stack.isEmpty() || source == null
            || !action.active() || action.pendingOperation == null
            || !action.pendingOperation.equals(source)
            || !action.pendingToolApplied) {
            return false;
        }
        BuildingType buildingType = switch (action.kind) {
            case LUMBER_TREE -> BuildingType.LUMBER_CAMP;
            case FARM_HARVEST -> BuildingType.FARMHOUSE;
            default -> null;
        };
        WorkZone.Type zoneType = switch (action.kind) {
            case LUMBER_TREE -> WorkZone.Type.LUMBER;
            case FARM_HARVEST -> WorkZone.Type.FARM;
            default -> null;
        };
        Context context = context(level, settlement, workplace, worker,
            buildingType, zoneType);
        if (context == null || !matches(action, context, action.kind)
            || !WorkZoneService.livePositionAllowed(level, context.zone,
                source)) {
            return false;
        }
        TransitKind transitKind = switch (action.kind) {
            case LUMBER_TREE -> TransitKind.LUMBER_LOG;
            case FARM_HARVEST -> TransitKind.FARM_CROP;
            default -> null;
        };
        return transitKind != null && WorkerStackProvenance.stampTransit(stack,
            action.id, transitKind, context.zone.settlementId(),
            context.zone.buildingId(), action.workerId,
            context.zone.dimension(), source.asLong());
    }

    /** Tagged output path. Permanent receipt is committed after physical insert. */
    public static DepositResult depositOutput(ServerLevel level,
                                              Settlement settlement,
                                              Building workplace,
                                              SettlerEntity worker,
                                              BlockPos target,
                                              ItemStack source) {
        int offered = source == null ? 0 : source.getCount();
        Transit transit = WorkerStackProvenance.readTransit(source).orElse(null);
        if (transit == null || offered <= 0) {
            return new DepositResult(source, null);
        }
        BuildingType expectedBuilding = transit.kind() == TransitKind.LUMBER_LOG
            ? BuildingType.LUMBER_CAMP : transit.kind() == TransitKind.FARM_CROP
                ? BuildingType.FARMHOUSE : null;
        WorkZone.Type expectedZone = transit.kind() == TransitKind.LUMBER_LOG
            ? WorkZone.Type.LUMBER : transit.kind() == TransitKind.FARM_CROP
                ? WorkZone.Type.FARM : null;
        Context context = context(level, settlement, workplace, worker,
            expectedBuilding, expectedZone);
        WorkerProvenanceSavedData data = WorkerProvenanceSavedData.get(level);
        WorkerProvenanceSavedData.Action action = data.mutable(transit.actionId());
        ResourceLocation item = itemId(source);
        if (context == null || action == null || item == null
            || !action.phase.workTerminal() || !action.zone.equals(context.zone)
            || !transit.settlementId().equals(settlement.id)
            || !transit.buildingId().equals(workplace.id)
            || !transit.workerId().equals(worker.getUUID())
            || !transit.dimension().equals(level.dimension().location())) {
            return new DepositResult(source, null);
        }
        Kind expectedKind = transit.kind() == TransitKind.LUMBER_LOG
            ? Kind.LUMBER_TREE : Kind.FARM_HARVEST;
        if (action.kind != expectedKind) {
            return new DepositResult(source, null);
        }
        int already = action.deposited.getOrDefault(item, 0);
        int authorised = Math.min(offered,
            Math.max(0, action.produced.getOrDefault(item, 0) - already));
        if (authorised <= 0) {
            return new DepositResult(source, null);
        }
        // This is the final authority boundary, not merely a routing hint:
        // a door or wall may change during the worker's contact animation.
        // Do not insert, advance provenance, or mint a receipt unless the
        // exact worker can still touch this exact live container now.
        if (!ContainerApproach.inspect(level, worker, target).canInteract()) {
            return new DepositResult(source, null);
        }
        WorkerStorageAuthority.Insert inserted = WorkerStorageAuthority.insertAt(
            level, workplace, target, source, authorised, true);
        if (!inserted.conserved() || inserted.inserted() <= 0) {
            return new DepositResult(source, null);
        }
        action.deposited.merge(item, inserted.inserted(), Integer::sum);
        action.phase = Phase.OUTPUT_COMMITTED;
        data.changed(action, level.getGameTime());
        UUID receiptId = WorkerProvenanceSavedData.receiptId(action.id, item,
            already);
        DepositReceipt receipt = new DepositReceipt(receiptId, action.id,
            settlement.id, workplace.id, worker.getUUID(),
            level.dimension().location(), target, item, inserted.inserted(),
            inserted.destinationBefore(), inserted.destinationAfter(),
            level.getGameTime());
        if (!data.addReceipt(receipt)) {
            // The destination already owns the physical items. Keep the action
            // aggregate but author no hook: evidence is incomplete, never fake.
            return new DepositResult(inserted.remainder(), null);
        }
        JourneyServerHooks.noteWorkplaceOutputCommitted(level, settlement,
            workplace, worker, receipt.id());
        return new DepositResult(inserted.remainder(), receipt);
    }

    /** Untagged cargo is real cargo, but can never author provenance evidence. */
    public static DepositResult depositOrdinary(ServerLevel level,
                                                Settlement settlement,
                                                Building workplace,
                                                SettlerEntity worker,
                                                BlockPos target,
                                                ItemStack source,
                                                BuildingType buildingType,
                                                WorkZone.Type zoneType) {
        // Untagged output is already physical worker cargo and cannot author
        // Journey/provenance evidence. Returning that cargo to the exact live
        // employer therefore uses logistics authority, not field-mutation
        // authority: a missing, superseded or quarantined Work Zone may stop
        // new work, but may never strand ordinary matter in the worker bag.
        if (!logisticsAuthority(level, settlement, workplace, worker,
                buildingType, zoneType)
            || source == null || source.isEmpty()
            || WorkerStackProvenance.hasTransitMarker(source)) {
            return new DepositResult(source, null);
        }
        // Ordinary cargo has no receipt, but it still requires the same
        // exact, server-authoritative physical container contact as tagged
        // output. A failed contact is a strict no-op for caller retry logic.
        if (!ContainerApproach.inspect(level, worker, target).canInteract()) {
            return new DepositResult(source, null);
        }
        var inserted = WorkerStorageAuthority.insertAt(level, workplace, target,
            source, source.getCount(), false);
        return inserted.conserved()
            ? new DepositResult(inserted.remainder(), null)
            : new DepositResult(source, null);
    }

    /**
     * Exact physical workplace authority for cargo-only recovery. This is
     * intentionally narrower than a generic container insert and deliberately
     * does not require a live Work Zone: it cannot start or credit work.
     */
    private static boolean logisticsAuthority(ServerLevel level,
                                               Settlement settlement,
                                               Building building,
                                               SettlerEntity worker,
                                               BuildingType buildingType,
                                               WorkZone.Type zoneType) {
        return level != null && settlement != null && building != null
            && worker != null && buildingType != null && zoneType != null
            && zoneType.buildingType() == buildingType
            && zoneType.profession() == worker.getProfession()
            && level.getServer().isSameThread()
            && SettlementManager.byId(level, settlement.id) == settlement
            && worker.level() == level && worker.isAlive()
            && level.getEntity(worker.getId()) == worker
            && settlement.id.equals(worker.getSettlementId())
            && settlement.record(worker.getUUID()) != null
            && exactRegistered(settlement, building) && building.valid
            && building.type == buildingType
            && building.workers.contains(worker.getUUID())
            && Employment.employerOf(settlement, worker.getUUID()) == building;
    }

    private static boolean prepareToolOperation(ServerLevel level,
                                                Context context,
                                                UUID actionId, Kind kind,
                                                BlockPos operation,
                                                Profession profession) {
        WorkerProvenanceSavedData data = WorkerProvenanceSavedData.get(level);
        WorkerProvenanceSavedData.Action action = data.mutable(actionId);
        if (!matches(action, context, kind) || !action.active()
            || operation == null || !action.planned.contains(operation)
            || action.resolved.contains(operation)
            || !WorkZoneService.livePositionAllowed(level, context.zone,
                operation)) {
            return false;
        }
        if (action.pendingOperation != null) {
            if (!action.pendingOperation.equals(operation)) {
                return false;
            }
            if (action.pendingToolApplied) {
                // Never let a persisted boolean author a world mutation by
                // itself. The exact still-held physical tool receipt is the
                // authority after restart, equipment churn or hand swaps.
                return pendingToolMatches(context, action, operation);
            }
            // Restart-safe PREPARED seam: either the physical stack already
            // carries the exact receipt/damage, or it still has the exact
            // before-state and can receive the one authorised point now.
            ItemStack pendingTool = context.worker.getMainHandItem();
            ResourceLocation pendingItem = itemId(pendingTool);
            var receipt = WorkerStackProvenance.readToolUse(pendingTool)
                .orElse(null);
            if (receipt != null && receipt.actionId().equals(action.id)
                && receipt.operationPos() == operation.asLong()
                && receipt.itemId().equals(action.pendingToolItem)
                && receipt.damageBefore() == action.pendingToolBefore
                && receipt.damageAfter() == action.pendingToolAfter
                && pendingTool.getDamageValue() == action.pendingToolAfter) {
                action.pendingToolApplied = true;
                if (action.appliedToolDamage == action.resolved.size()) {
                    action.appliedToolDamage++;
                } else if (action.appliedToolDamage
                    != action.resolved.size() + 1) {
                    return false;
                }
                data.changed(action, level.getGameTime());
                return !data.quarantined();
            }
            if (pendingItem == null || !pendingItem.equals(action.pendingToolItem)
                || pendingTool.getDamageValue() != action.pendingToolBefore
                || !pendingTool.isDamageableItem()
                || action.pendingToolAfter >= pendingTool.getMaxDamage()
                || !WorkerStackProvenance.stampToolUse(pendingTool, action.id,
                    operation.asLong(), pendingItem, action.pendingToolBefore,
                    action.pendingToolAfter)) {
                return false;
            }
            pendingTool.setDamageValue(action.pendingToolAfter);
            context.worker.setItemSlot(EquipmentSlot.MAINHAND, pendingTool);
            action.pendingToolApplied = true;
            action.appliedToolDamage++;
            data.changed(action, level.getGameTime());
            return !data.quarantined();
        }
        EquipmentRequirement requirement = EquipmentRequests.requirementFor(
            profession);
        ItemStack tool = context.worker.getMainHandItem();
        if (requirement == null || !requirement.serviceable(tool)
            || !tool.isDamageableItem()) {
            return false;
        }
        ResourceLocation toolItem = itemId(tool);
        int before = tool.getDamageValue();
        int after = before + 1;
        if (toolItem == null || after >= tool.getMaxDamage()) {
            // Requests deliberately replace tools before the last few uses;
            // never destroy the receipt-bearing physical stack mid-action.
            return false;
        }
        action.pendingOperation = operation.immutable();
        action.pendingToolItem = toolItem;
        action.pendingToolBefore = before;
        action.pendingToolAfter = after;
        action.pendingToolApplied = false;
        data.changed(action, level.getGameTime());

        if (!WorkerStackProvenance.stampToolUse(tool, action.id,
                operation.asLong(), toolItem, before, after)) {
            clearPending(action);
            data.changed(action, level.getGameTime());
            return false;
        }
        tool.setDamageValue(after);
        context.worker.setItemSlot(EquipmentSlot.MAINHAND, tool);
        action.pendingToolApplied = true;
        action.appliedToolDamage++;
        data.changed(action, level.getGameTime());
        return true;
    }

    private static boolean completeOperation(ServerLevel level, Context context,
                                             UUID actionId, Kind kind,
                                             BlockPos operation,
                                             Map<ResourceLocation, Integer> output,
                                             boolean commitImmediately) {
        WorkerProvenanceSavedData data = WorkerProvenanceSavedData.get(level);
        WorkerProvenanceSavedData.Action action = data.mutable(actionId);
        if (!matches(action, context, kind) || !action.active()
            || !completionCanApply(action, operation, output,
                commitImmediately)) {
            return false;
        }
        ItemStack tool = context.worker.getMainHandItem();
        if (!pendingToolMatches(context, action, operation)
            || !WorkerStackProvenance.clearToolUse(tool, action.id,
                operation.asLong())) {
            return false;
        }
        context.worker.setItemSlot(EquipmentSlot.MAINHAND, tool);
        // completionCanApply proved every terminal and overflow invariant.
        // From here onward there is no conditional partial-mutation exit.
        completionApply(action, operation, output, commitImmediately);
        data.changed(action, level.getGameTime());
        return !data.quarantined();
    }

    private static boolean pendingToolMatches(Context context,
                                              WorkerProvenanceSavedData.Action action,
                                              BlockPos operation) {
        if (context == null || action == null || operation == null) {
            return false;
        }
        ItemStack tool = context.worker.getMainHandItem();
        var toolUse = WorkerStackProvenance.readToolUse(tool).orElse(null);
        ResourceLocation toolItem = itemId(tool);
        return toolUse != null && toolItem != null
            && toolUse.actionId().equals(action.id)
            && toolUse.operationPos() == operation.asLong()
            && toolUse.itemId().equals(action.pendingToolItem)
            && toolItem.equals(action.pendingToolItem)
            && toolUse.damageBefore() == action.pendingToolBefore
            && toolUse.damageAfter() == action.pendingToolAfter
            && tool.getDamageValue() == action.pendingToolAfter;
    }

    /** Pure preflight shared with adversarial atomicity tests. */
    static boolean completionCanApply(WorkerProvenanceSavedData.Action action,
                                      BlockPos operation,
                                      Map<ResourceLocation, Integer> output,
                                      boolean commitImmediately) {
        if (action == null || operation == null || output == null
            || !action.structurallyValid() || !action.active()
            || action.pendingOperation == null
            || !action.pendingOperation.equals(operation)
            || !action.pendingToolApplied
            || !action.planned.contains(operation)
            || action.resolved.contains(operation)
            || action.appliedToolDamage != action.resolved.size() + 1
            || (action.kind == Kind.FARM_PLANT) != output.isEmpty()
            || (!commitImmediately && action.kind != Kind.LUMBER_TREE)
            || (commitImmediately && action.kind == Kind.LUMBER_TREE)) {
            return false;
        }
        Set<ResourceLocation> projectedItems = new HashSet<>(
            action.produced.keySet());
        projectedItems.addAll(output.keySet());
        if (projectedItems.size() > WorkerProvenanceSavedData.MAX_ITEM_ROWS) {
            return false;
        }
        for (Map.Entry<ResourceLocation, Integer> entry : output.entrySet()) {
            if (entry.getKey() == null || entry.getValue() <= 0) {
                return false;
            }
            int previous = action.produced.getOrDefault(entry.getKey(), 0);
            if (previous > Integer.MAX_VALUE - entry.getValue()) {
                return false;
            }
        }
        int resolvedAfter = action.resolved.size() + 1;
        if (resolvedAfter > action.planned.size()) {
            return false;
        }
        return !commitImmediately || resolvedAfter == action.planned.size();
    }

    /** Applies only after {@link #completionCanApply} succeeds. */
    static void completionApply(WorkerProvenanceSavedData.Action action,
                                BlockPos operation,
                                Map<ResourceLocation, Integer> output,
                                boolean commitImmediately) {
        action.resolved.add(operation.immutable());
        for (Map.Entry<ResourceLocation, Integer> entry : output.entrySet()) {
            action.produced.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
        clearPending(action);
        if (commitImmediately) {
            action.phase = Phase.WORK_COMMITTED;
        }
    }

    private static boolean commitWork(ServerLevel level, Context context,
                                      UUID actionId, Kind kind) {
        WorkerProvenanceSavedData data = WorkerProvenanceSavedData.get(level);
        WorkerProvenanceSavedData.Action action = data.mutable(actionId);
        if (!matches(action, context, kind) || !action.active()
            || action.pendingOperation != null
            || action.resolved.size() != action.planned.size()
            || action.appliedToolDamage != action.resolved.size()) {
            return false;
        }
        action.phase = Phase.WORK_COMMITTED;
        data.changed(action, level.getGameTime());
        return !data.quarantined();
    }

    private static void clearPending(WorkerProvenanceSavedData.Action action) {
        action.pendingOperation = null;
        action.pendingToolApplied = false;
        action.pendingToolItem = null;
        action.pendingToolBefore = 0;
        action.pendingToolAfter = 0;
    }

    private static boolean matches(@Nullable WorkerProvenanceSavedData.Action action,
                                   Context context, Kind kind) {
        return action != null && context != null && action.kind == kind
            && action.workerId.equals(context.worker.getUUID())
            && action.zone.equals(context.zone);
    }

    @Nullable
    private static Context context(ServerLevel level, Settlement settlement,
                                   Building building, SettlerEntity worker,
                                   @Nullable BuildingType buildingType,
                                   @Nullable WorkZone.Type zoneType) {
        if (level == null || settlement == null || building == null
            || worker == null || buildingType == null || zoneType == null
            || !level.getServer().isSameThread()
            || SettlementManager.byId(level, settlement.id) != settlement
            || worker.level() != level || !worker.isAlive()
            || level.getEntity(worker.getId()) != worker
            || !settlement.id.equals(worker.getSettlementId())
            || settlement.record(worker.getUUID()) == null
            || !exactRegistered(settlement, building) || !building.valid
            || building.type != buildingType
            || !building.workers.contains(worker.getUUID())
            || Employment.employerOf(settlement, worker.getUUID()) != building) {
            return null;
        }
        WorkZone zone = building.workZone().orElse(null);
        if (zone == null || building.workZoneQuarantined()
            || zone.type() != zoneType
            || !zone.settlementId().equals(settlement.id)
            || !zone.buildingId().equals(building.id)
            || !zone.dimension().equals(level.dimension().location())
            || !zone.withinPersistentLimits()) {
            return null;
        }
        return new Context(settlement, building, worker, zone);
    }

    private static boolean exactRegistered(Settlement settlement,
                                           Building building) {
        if (settlement == null || building == null || building.id == null) {
            return false;
        }
        int identities = 0;
        boolean same = false;
        for (Building candidate : settlement.buildings) {
            if (candidate == null || candidate.id == null) {
                return false;
            }
            if (candidate.id.equals(building.id)) {
                identities++;
                same |= candidate == building;
            }
        }
        return same && identities == 1;
    }

    /** Exact root lookup without create/reconcile/observe side effects. */
    private static boolean liveReadOnly(ServerLevel level,
                                        Settlement settlement) {
        SettlementSavedData data = SettlementSavedData.existing(level);
        return data != null && settlement.id != null
            && data.settlements.get(settlement.id) == settlement;
    }

    private static boolean serviceableTool(SettlerEntity worker,
                                           Profession profession) {
        EquipmentRequirement requirement = EquipmentRequests.requirementFor(
            profession);
        ItemStack held = worker == null ? ItemStack.EMPTY
            : worker.getMainHandItem();
        return requirement != null && requirement.serviceable(held)
            && held.isDamageableItem();
    }

    private static boolean naturalTreeMatches(ServerLevel level, WorkZone zone,
                                              BlockPos root,
                                              List<BlockPos> expectedLogs) {
        if (level == null || zone == null || root == null || expectedLogs == null
            || expectedLogs.isEmpty()
            || expectedLogs.size() > WorkerProvenanceSavedData.MAX_POSITIONS
            || !expectedLogs.contains(root)
            || !WorkZoneService.livePositionAllowed(level, zone, root)
            || !WorkZoneService.livePositionAllowed(level, zone, root.below())
            || !level.getBlockState(root).is(BlockTags.LOGS_THAT_BURN)
            || !level.getBlockState(root.below()).is(BlockTags.DIRT)) {
            return false;
        }
        Set<BlockPos> expected = new HashSet<>();
        for (BlockPos pos : expectedLogs) {
            if (!expected.add(pos.immutable())
                || !WorkZoneService.livePositionAllowed(level, zone, pos)
                || !level.getBlockState(pos).is(BlockTags.LOGS_THAT_BURN)) {
                return false;
            }
        }
        Set<BlockPos> foundLogs = new HashSet<>();
        Set<BlockPos> leaves = new HashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(root.immutable());
        foundLogs.add(root.immutable());
        while (!frontier.isEmpty()) {
            BlockPos current = frontier.removeFirst();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = current.offset(dx, dy, dz);
                        if (!zone.contains(next)
                            || Math.abs(next.getX() - root.getX()) > MAX_TREE_DRIFT
                            || Math.abs(next.getZ() - root.getZ()) > MAX_TREE_DRIFT
                            || next.getY() < root.getY()) {
                            continue;
                        }
                        if (!WorkZoneService.livePositionAllowed(level, zone, next)) {
                            return false;
                        }
                        BlockState state = level.getBlockState(next);
                        if (state.is(BlockTags.LOGS_THAT_BURN)
                            && foundLogs.add(next.immutable())) {
                            if (foundLogs.size()
                                > WorkerProvenanceSavedData.MAX_POSITIONS) {
                                return false;
                            }
                            frontier.addLast(next.immutable());
                        } else if (state.getBlock() instanceof LeavesBlock
                            && !state.getValue(LeavesBlock.PERSISTENT)) {
                            leaves.add(next.immutable());
                        }
                    }
                }
            }
        }
        return foundLogs.equals(expected) && leaves.size() >= MIN_NATURAL_LEAVES;
    }

    @Nullable
    private static ResourceLocation itemId(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null
            : BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    private record Context(Settlement settlement, Building building,
                           SettlerEntity worker, WorkZone zone) {
    }

    private WorkerProvenanceService() {
    }
}
