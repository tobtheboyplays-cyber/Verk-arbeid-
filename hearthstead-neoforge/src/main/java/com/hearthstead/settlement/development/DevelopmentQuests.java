package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.equipment.WorkplaceStorage;
import com.hearthstead.settlement.state.FirstRaidState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Validation boundary for every server-measured Development quest fact.
 *
 * <p>World systems call the narrow {@code note*} methods only after their
 * physical transaction has committed. This class re-checks settlement,
 * building, worker, item and inserted amount before advancing bounded,
 * persistent counters. Client packets have no route into these methods.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class DevelopmentQuests {
    public record Progress(DevelopmentObjective objective, int progress, int target) {
        public Progress {
            progress = Math.max(0, Math.min(Math.max(1, target), progress));
            target = Math.max(1, target);
        }

        public boolean complete() {
            return progress >= target;
        }
    }

    private static final EnumSet<BuildingType> PRODUCTIVE_SOURCES = EnumSet.of(
        BuildingType.LUMBER_CAMP, BuildingType.FARMHOUSE, BuildingType.SAWMILL,
        BuildingType.MILL, BuildingType.MINE, BuildingType.FISHERY,
        BuildingType.HUNTERS_LODGE, BuildingType.PASTURE);

    public static List<Progress> progress(ServerLevel level, Settlement settlement,
                                          HearthBlockEntity hearth,
                                          DevelopmentState state,
                                          DevelopmentNode node) {
        ArrayList<Progress> progress = new ArrayList<>(node.quests().size());
        for (DevelopmentNode.QuestRequirement requirement : node.quests()) {
            int measured = measure(level, settlement, hearth, state,
                node, requirement.objective());
            progress.add(new Progress(requirement.objective(), measured,
                requirement.target()));
        }
        return List.copyOf(progress);
    }

    public static boolean complete(ServerLevel level, Settlement settlement,
                                   HearthBlockEntity hearth,
                                   DevelopmentState state,
                                   DevelopmentNode node) {
        for (DevelopmentNode.QuestRequirement requirement : node.quests()) {
            if (measure(level, settlement, hearth, state, node,
                    requirement.objective()) < requirement.target()) {
                return false;
            }
        }
        return true;
    }

    static boolean ensureEligibleBaselines(DevelopmentState state) {
        boolean changed = false;
        for (DevelopmentNode node : DevelopmentNode.PRESENTATION_ORDER) {
            if (!node.implemented() || state.unlocked(node)
                || !prerequisitesMet(state, node)) {
                continue;
            }
            for (DevelopmentNode.QuestRequirement requirement : node.quests()) {
                changed |= state.ensureQuestBaseline(node, requirement.objective());
            }
        }
        return changed;
    }

    public static boolean noteLumberLogsStored(ServerLevel level,
                                                Settlement settlement,
                                                Building lumberCamp,
                                                SettlerEntity lumberer,
                                                ItemStack offered,
                                                int insertedCount) {
        if (!validWorker(level, settlement, lumberCamp, lumberer,
                BuildingType.LUMBER_CAMP, Profession.LUMBERER)
            || offered == null || offered.isEmpty() || !offered.is(ItemTags.LOGS)
            || insertedCount <= 0 || insertedCount > offered.getCount()) {
            return false;
        }
        DevelopmentState state = Development.of(level, settlement);
        return dirtyIf(level, state.noteLumberLogs(insertedCount));
    }

    public static boolean noteFarmCropsStored(ServerLevel level,
                                               Settlement settlement,
                                               Building farmhouse,
                                               SettlerEntity farmer,
                                               ItemStack offered,
                                               int insertedCount) {
        if (!validWorker(level, settlement, farmhouse, farmer,
                BuildingType.FARMHOUSE, Profession.FARMER)
            || offered == null || offered.isEmpty() || !isHarvest(offered)
            || insertedCount <= 0 || insertedCount > offered.getCount()) {
            return false;
        }
        DevelopmentState state = Development.of(level, settlement);
        return dirtyIf(level, state.noteFarmCrops(insertedCount));
    }

    /** Called exactly once after a completed, physically inserted route. */
    public static boolean noteCourierDelivery(ServerLevel level,
                                               Settlement settlement,
                                               SettlerEntity courier,
                                               @Nullable Building source,
                                               @Nullable Building destination,
                                               int physicallyInsertedItems) {
        if (level == null || settlement == null || courier == null
            || physicallyInsertedItems <= 0
            || courier.level() != level
            || courier.getProfession() != Profession.COURIER
            || !Objects.equals(courier.getSettlementId(), settlement.id)
            || settlement.record(courier.getUUID()) == null
            || settlement.record(courier.getUUID()).profession != Profession.COURIER
            || !validCourierEmployer(settlement, courier)
            || (source == null && destination == null)
            || (source != null && !validRegisteredBuilding(settlement, source))
            || (destination != null && !validRegisteredBuilding(settlement, destination))) {
            return false;
        }
        int productiveItems = source != null && destination != null
            && destination.type == BuildingType.WAREHOUSE
            && PRODUCTIVE_SOURCES.contains(source.type)
            ? physicallyInsertedItems : 0;
        DevelopmentState state = Development.of(level, settlement);
        return dirtyIf(level, state.noteCourierDelivery(productiveItems));
    }

    /** The request seam has already proven CLAIMED -> DELIVERED physically. */
    public static boolean noteEquipmentRequestDelivered(ServerLevel level,
                                                         Settlement settlement,
                                                         UUID requestId,
                                                         UUID courierId) {
        if (level == null || settlement == null || requestId == null
            || courierId == null) {
            return false;
        }
        EquipmentRequest request = EquipmentRequests.byId(settlement, requestId);
        Building destination = request == null ? null
            : buildingById(settlement, request.destinationBuildingId());
        SettlerEntity requester = request == null ? null
            : level.getEntity(request.requesterId()) instanceof SettlerEntity settler
                ? settler : null;
        SettlerEntity courier = level.getEntity(courierId) instanceof SettlerEntity settler
            ? settler : null;
        if (request == null || request.status() != EquipmentRequest.Status.DELIVERED
            || !validRegisteredBuilding(settlement, destination)
            || Employment.tradeOf(destination.type) != request.profession()
            || requester == null
            || !validWorker(level, settlement, destination, requester,
                destination.type, request.profession())
            || settlement.record(request.requesterId()) == null
            || settlement.record(request.requesterId()).profession
                != request.profession()
            || courier == null || courier.getProfession() != Profession.COURIER
            || !Objects.equals(courier.getSettlementId(), settlement.id)
            || settlement.record(courierId) == null
            || settlement.record(courierId).profession != Profession.COURIER
            || !validCourierEmployer(settlement, courier)
            || !WorkplaceStorage.hasMatching(level, destination,
                request.requirement())) {
            return false;
        }
        DevelopmentState state = Development.of(level, settlement);
        boolean changed = state.noteEquipmentRequest(requestId);
        if (request.profession() == Profession.GUARD
            && destination.type == BuildingType.BARRACKS
            && Objects.equals(EquipmentRequests.requirementFor(Profession.GUARD),
                request.requirement())) {
            changed |= state.noteGuardEquipmentRequest(requestId);
        }
        return dirtyIf(level, changed);
    }

    /** Counts exact XP actually added, never attempted/capped awards. */
    public static boolean noteGuardExperience(ServerLevel level,
                                              Settlement settlement,
                                              SettlerEntity guard,
                                              int actualAdded) {
        if (level == null || settlement == null || guard == null || actualAdded <= 0
            || guard.level() != level || guard.getProfession() != Profession.GUARD
            || !Objects.equals(guard.getSettlementId(), settlement.id)
            || settlement.record(guard.getUUID()) == null
            || settlement.record(guard.getUUID()).profession != Profession.GUARD
            || !validGuardEmployer(settlement, guard)) {
            return false;
        }
        DevelopmentState state = Development.of(level, settlement);
        return dirtyIf(level, state.noteGuardExperience(actualAdded));
    }

    /** One-second cadence for the only duration objective in the release tree. */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
            || level.getGameTime() % 20L != 0L) {
            return;
        }
        Development data = Development.get(level);
        for (Settlement settlement : SettlementSavedData.get(level).settlements.values()) {
            DevelopmentState state = data.existingState(settlement.id);
            if (state == null || state.quarantined()
                || !state.unlocked(DevelopmentNode.FIRST_RAID_AFTERMATH)
                || state.activeDoctrine() != null) {
                continue;
            }
            if (state.updateAllHousedTicks(allResidentsHoused(settlement), 20)) {
                data.setDirty();
            }
        }
    }

    private static int measure(ServerLevel level, Settlement settlement,
                               HearthBlockEntity hearth, DevelopmentState state,
                               DevelopmentNode node,
                               DevelopmentObjective objective) {
        return switch (objective) {
            case FOUNDATION_READY -> foundationReady(level, settlement, hearth) ? 1 : 0;
            case HOUSED_SETTLERS -> housedResidents(settlement);
            case FIRST_RAID_COMPLETE -> settlement.raidLifecycle.firstState()
                == FirstRaidState.COMPLETED ? 1 : 0;
            case ALL_HOUSED_TICKS -> state.counter(objective);
            default -> state.counterProgress(node, objective);
        };
    }

    private static boolean foundationReady(ServerLevel level, Settlement settlement,
                                           HearthBlockEntity hearth) {
        if (level == null || settlement == null || hearth == null
            || settlement.mayorId == null
            || !settlement.center.equals(hearth.getBlockPos())
            || !settlement.id.equals(hearth.getSettlementId())
            || level.getBlockEntity(hearth.getBlockPos()) != hearth) {
            return false;
        }
        if (!(level.getEntity(settlement.mayorId) instanceof SettlerEntity mayor)
            || !mayor.isAlive()
            || !Objects.equals(mayor.getSettlementId(), settlement.id)
            || settlement.record(mayor.getUUID()) == null) {
            return false;
        }
        // The first research choice must be reachable before Home knowledge.
        // A valid Hearth, a live bound Mayor and the founded settlement are
        // the complete First Fire proof; housing is deliberately later.
        return settlement.population() >= 3;
    }

    private static int housedResidents(Settlement settlement) {
        return Math.min(settlement.population(), settlement.validBedCount());
    }

    private static boolean allResidentsHoused(Settlement settlement) {
        return settlement.population() > 0
            && housedResidents(settlement) >= settlement.population();
    }

    private static boolean validWorker(ServerLevel level, Settlement settlement,
                                       Building building, SettlerEntity worker,
                                       BuildingType expectedBuilding,
                                       Profession expectedProfession) {
        return level != null && settlement != null && worker != null
            && worker.level() == level
            && validRegisteredBuilding(settlement, building)
            && building.type == expectedBuilding
            && building.workers.contains(worker.getUUID())
            && Objects.equals(worker.getSettlementId(), settlement.id)
            && worker.getProfession() == expectedProfession
            && Employment.employerOf(settlement, worker.getUUID()) == building;
    }

    private static boolean validRegisteredBuilding(Settlement settlement,
                                                   Building building) {
        if (settlement == null || building == null || !building.valid) {
            return false;
        }
        for (Building registered : settlement.buildings) {
            if (registered == building && registered.id.equals(building.id)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Building buildingById(Settlement settlement, UUID buildingId) {
        if (settlement == null || buildingId == null) {
            return null;
        }
        for (Building building : settlement.buildings) {
            if (building.id.equals(buildingId)) {
                return building;
            }
        }
        return null;
    }

    private static boolean validCourierEmployer(Settlement settlement,
                                                SettlerEntity courier) {
        Building employer = Employment.employerOf(settlement, courier.getUUID());
        return validRegisteredBuilding(settlement, employer)
            && employer.type == BuildingType.WAREHOUSE;
    }

    private static boolean validGuardEmployer(Settlement settlement,
                                              SettlerEntity guard) {
        Building employer = Employment.employerOf(settlement, guard.getUUID());
        return validRegisteredBuilding(settlement, employer)
            && employer.type == BuildingType.BARRACKS;
    }

    private static boolean prerequisitesMet(DevelopmentState state,
                                            DevelopmentNode node) {
        for (String id : node.prerequisites()) {
            DevelopmentNode prerequisite = DevelopmentNode.byId(id);
            if (prerequisite == null || !state.unlocked(prerequisite)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHarvest(ItemStack stack) {
        return stack.is(Items.WHEAT) || stack.is(Items.CARROT)
            || stack.is(Items.POTATO) || stack.is(Items.BEETROOT)
            || stack.is(Items.MELON_SLICE) || stack.is(Items.PUMPKIN)
            || stack.is(Items.SUGAR_CANE);
    }

    private static boolean dirtyIf(ServerLevel level, boolean changed) {
        if (changed) {
            Development.get(level).setDirty();
        }
        return changed;
    }

    private DevelopmentQuests() {
    }
}
