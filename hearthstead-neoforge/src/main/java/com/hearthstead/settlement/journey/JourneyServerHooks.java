package com.hearthstead.settlement.journey;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.menu.SettlerInventoryMenu;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.RecruitmentTransaction;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.DevelopmentState;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.raid.RaidLogEntry;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.raid.FirstRaidReadinessService;
import com.hearthstead.settlement.request.RequestLedger;
import com.hearthstead.settlement.request.RequestLedgerSavedData;
import com.hearthstead.settlement.request.RequestLedgerSnapshot;
import com.hearthstead.settlement.request.RequestRecord;
import com.hearthstead.settlement.request.RequestState;
import com.hearthstead.settlement.request.RequestType;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.GuardOrder;
import com.hearthstead.settlement.work.WorkerProvenanceSavedData;
import com.hearthstead.settlement.work.WorkerProvenanceSavedData.ActionView;
import com.hearthstead.settlement.work.WorkerProvenanceSavedData.DepositReceipt;
import com.hearthstead.settlement.work.WorkerProvenanceSavedData.Kind;
import com.hearthstead.settlement.work.WorkerStorageAuthority;
import com.hearthstead.settlement.workzone.WorkZone;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Narrow adapters from genuine server commits into Journey evidence.
 *
 * <p>There is no generic public "complete step" method. Every adapter
 * re-observes the committed domain state on the Minecraft server thread and
 * then authors one stable fact. Network packets can request the domain action;
 * they cannot submit evidence.
 */
public final class JourneyServerHooks {

    public static boolean noteSettlementFounded(ServerLevel level,
                                                 Settlement settlement) {
        if (!live(level, settlement) || settlement.population() != SettlementManager.FOUNDER_COUNT) {
            return false;
        }
        UUID transaction = JourneyTransactionIds.forRevision("founding",
            settlement.id, settlement.id, 0L);
        return accepted(record(level, settlement,
            JourneyIds.FJ_010_FOUND_HEARTH,
            JourneyEvent.SETTLEMENT_FOUNDED_COMMITTED,
            transaction, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), JourneyOutcome.NONE));
    }

    /** Exact open-Hearth session observation, not a client tab assertion. */
    public static boolean noteJourneyViewOpened(ServerPlayer player,
                                                Settlement settlement) {
        if (player == null || settlement == null
            || !(player.containerMenu instanceof HearthMenu menu)
            || menu.getContainerId() != player.containerMenu.containerId
            || !menu.getSettlementId().equals(settlement.id)
            || !menu.getHearthPos().equals(settlement.center)
            || !menu.stillValid(player)) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        boolean reconciled = reconcileOwnedTechUnlock(player, settlement);
        reconciled |= reconcilePhysicalHousingCapacity(level, settlement);
        reconciled |= reconcileWatchRecruitmentSlot(level, settlement);
        long token = Integer.toUnsignedLong(java.util.Objects.hash(
            level.getGameTime(), menu.getContainerId(), settlement.journeyState.revision()));
        UUID transaction = JourneyTransactionIds.forRevision("journey_view",
            settlement.id, player.getUUID(), token);
        boolean journeyOpened = accepted(record(level, settlement,
            JourneyIds.FJ_020_OPEN_JOURNEY, JourneyEvent.JOURNEY_VIEW_OPENED,
            transaction, Optional.of(player.getUUID()), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), JourneyOutcome.NONE));
        if (settlement.journeyState.isCompleted(
                JourneyIds.FJ_610_FIRST_RAID_RESOLVED)) {
            noteRaidAftermathViewed(player, settlement);
        }
        // The founder Mayor already holds a real persisted seat. Observe it
        // after FJ020 exists, using this actual open-Hearth player session.
        // Never record a duplicate receipt with a newly invented timestamp.
        boolean mayorObserved = false;
        if (!settlement.journeyState.isCompleted(JourneyIds.FJ_030_APPOINT_MAYOR)
            && settlement.journeyState.isCompleted(JourneyIds.FJ_020_OPEN_JOURNEY)
            && settlement.mayorId != null
            && level.getEntity(settlement.mayorId) instanceof SettlerEntity mayor) {
            mayorObserved = noteMayorAppointed(player, settlement, mayor);
        }
        return reconciled || journeyOpened || mayorObserved;
    }

    public static boolean noteMayorAppointed(ServerPlayer player,
                                             Settlement settlement,
                                             SettlerEntity mayor) {
        if (player == null || mayor == null || settlement == null
            || !settlement.id.equals(mayor.getSettlementId())
            || !mayor.getUUID().equals(settlement.mayorId)
            || settlement.record(mayor.getUUID()) == null) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        UUID transaction = JourneyTransactionIds.forRevision("mayor_appointment",
            settlement.id, mayor.getUUID(), Math.max(0L, settlement.mayorSince));
        return accepted(record(level, settlement,
            JourneyIds.FJ_030_APPOINT_MAYOR,
            JourneyEvent.MAYOR_APPOINTED_COMMITTED,
            transaction, Optional.of(player.getUUID()), Optional.of(mayor.getUUID()),
            Optional.empty(), Optional.empty(), Optional.empty(), JourneyOutcome.NONE));
    }

    public static Optional<UUID> noteTechUnlocked(ServerPlayer player,
                                                   Settlement settlement,
                                                   DevelopmentNode node,
                                                   int committedRevision) {
        ResourceLocation step = techStep(node);
        if (player == null || settlement == null || step == null
            || committedRevision <= 0
            || !Development.of(player.serverLevel(), settlement).unlocked(node)) {
            return Optional.empty();
        }
        UUID subject = stableSubject("development_node", node.id());
        UUID transaction = JourneyTransactionIds.forRevision("development",
            settlement.id, subject, committedRevision);
        JourneyApplyResult result = record(player.serverLevel(), settlement, step,
            JourneyEvent.TECH_UNLOCKED_COMMITTED, transaction,
            Optional.of(player.getUUID()), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of("node:" + node.id()), JourneyOutcome.NONE);
        boolean applied = accepted(result);
        if (applied && node == DevelopmentNode.ARM_THE_WATCH) {
            // Existing homes are normally built before this branch unlocks.
            // Re-observe their loaded physical bed heads at the exact gate;
            // the player must never relink a valid home just to continue.
            reconcilePhysicalHousingCapacity(player.serverLevel(), settlement);
            // A natural guest may already be waiting when this gate opens.
            // Bind its persisted qualification/arrival now, before admission
            // can acknowledge ordinary recruitment and retire the transaction.
            reconcileWatchRecruitmentSlot(player.serverLevel(), settlement);
        }
        return applied ? Optional.of(transaction) : Optional.empty();
    }

    /**
     * Heals the one bounded cross-SavedData tear where Arm the Watch ownership
     * reached disk but its derived Journey fact did not. Re-opening Journey or
     * replaying the already-owned purchase records one stable receipt and
     * never enters the payment path again.
     */
    public static boolean reconcileOwnedTechUnlock(ServerPlayer player,
                                                    Settlement settlement) {
        if (player == null || settlement == null) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        DevelopmentState development = Development.of(level, settlement);
        Optional<UUID> transaction = ownedArmReconciliationTransaction(
            settlement.journeyState, development);
        if (transaction.isEmpty()) {
            return false;
        }
        JourneyApplyResult result = record(level, settlement,
            JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH,
            JourneyEvent.TECH_UNLOCKED_COMMITTED, transaction.get(),
            Optional.of(player.getUUID()), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of("node:arm_the_watch;owned_revision:"
                + development.revision()), JourneyOutcome.NONE);
        boolean applied = accepted(result);
        if (applied) {
            reconcilePhysicalHousingCapacity(level, settlement);
            reconcileWatchRecruitmentSlot(level, settlement);
        }
        return applied;
    }

    public static boolean noteBuildingLinked(ServerLevel level,
                                             Settlement settlement,
                                             Building building) {
        ResourceLocation step = buildingStep(building == null ? null : building.type);
        if (!live(level, settlement)
            || !exactRegistered(settlement, building) || !building.valid) {
            return false;
        }
        boolean observed = false;
        if (step != null) {
            UUID transaction = JourneyTransactionIds.forRevision("building_link",
                settlement.id, building.id, 0L);
            JourneyEvent event = building.type.housesResidents()
                ? JourneyEvent.HOUSING_CAPACITY_COMMITTED
                : JourneyEvent.BUILDING_LINKED_VALID_COMMITTED;
            // A first-link receipt is immutable even when this same building's
            // beds or validity are observed again. Preserve its original time;
            // all other payload fields still pass the ledger's strict equality
            // check, so an actual same-key conflict remains quarantined.
            long committedAt = settlement.journeyState.evidence().stream()
                .filter(item -> item.event() == event
                    && item.transactionId().equals(transaction))
                .mapToLong(JourneyEvidence::gameTime).findFirst()
                .orElse(Math.max(0L, level.getGameTime()));
            observed = accepted(record(level, settlement, step, event, transaction,
                Optional.empty(), Optional.empty(), Optional.of(building.id),
                Optional.empty(), Optional.empty(), JourneyOutcome.NONE, committedAt));
        }
        if (building.type == BuildingType.FISHERY
            && settlement.journeyState.evidence().stream().noneMatch(e ->
                e.stepId().equals(JourneyIds.FJ_330_SET_FARM_ZONE))) {
            observed |= accepted(record(level, settlement, JourneyIds.FJ_330_SET_FARM_ZONE,
                JourneyEvent.BUILDING_LINKED_VALID_COMMITTED,
                stableSubject("fishery_site", building.id.toString()), Optional.empty(),
                Optional.empty(), Optional.of(building.id), Optional.empty(),
                Optional.of("validated_fishery"), JourneyOutcome.NONE));
        }
        if (building.type.housesResidents()) {
            observed |= reconcilePhysicalHousingCapacity(level, settlement);
        }
        if (settlement.journeyState.isCompleted(
                JourneyIds.FJ_552_ADD_FIFTH_BED)) {
            observed |= reconcileWatchRecruitmentSlot(level, settlement);
        }
        return observed;
    }

    /** Preserves the frozen WorkZone bridge: building id plus zone revision. */
    public static boolean noteWorkZoneCommitted(ServerLevel level,
                                                Settlement settlement,
                                                Building building,
                                                WorkZone zone) {
        ResourceLocation step = zone == null ? null : switch (zone.type()) {
            case LUMBER -> JourneyIds.FJ_140_SET_LUMBER_ZONE;
            case FARM -> JourneyIds.FJ_330_SET_FARM_ZONE;
        };
        if (step == null || !live(level, settlement)
            || !exactRegistered(settlement, building) || !building.valid
            || !zone.settlementId().equals(settlement.id)
            || !zone.buildingId().equals(building.id)
            || !zone.equals(building.workZone().orElse(null))
            || building.workZoneRevision() != zone.revision()) {
            return false;
        }
        UUID transaction = JourneyTransactionIds.forRevision("work_zone",
            settlement.id, building.id, zone.revision());
        return accepted(record(level, settlement, step,
            JourneyEvent.WORK_ZONE_COMMITTED, transaction,
            Optional.empty(), Optional.empty(), Optional.of(building.id),
            Optional.empty(), Optional.empty(), JourneyOutcome.NONE));
    }

    /**
     * Records the physical Lumberer inventory view only after Minecraft has
     * installed the exact entity-backed menu on the server player.
     */
    public static boolean noteSettlerInventoryViewed(ServerPlayer player,
                                                      Settlement settlement,
                                                      SettlerEntity settler) {
        if (player == null || settlement == null || settler == null
            || !(player.containerMenu instanceof SettlerInventoryMenu menu)
            || menu.entityId() != settler.getId()
            || !menu.settlerId().equals(settler.getUUID())
            || menu.settler() != settler
            || !menu.stillValid(player)
            || player.getMainHandItem().isEmpty() == false
            || player.getOffhandItem().isEmpty() == false
            || !settlement.id.equals(settler.getSettlementId())
            || settlement.record(settler.getUUID()) == null
            || Employment.professionOf(settlement, settler.getUUID())
                != Profession.LUMBERER) {
            return false;
        }
        long token = Integer.toUnsignedLong(java.util.Objects.hash(
            player.serverLevel().getGameTime(), menu.containerId,
            settlement.journeyState.revision()));
        UUID transaction = JourneyTransactionIds.forRevision(
            "settler_inventory_view", settlement.id, settler.getUUID(), token);
        return accepted(record(player.serverLevel(), settlement,
            JourneyIds.FJ_130_OPEN_LUMBERER_INVENTORY,
            JourneyEvent.SETTLER_INVENTORY_VIEW_OPENED, transaction,
            Optional.of(player.getUUID()), Optional.of(settler.getUUID()),
            Optional.ofNullable(Employment.employerOf(settlement,
                settler.getUUID())).map(building -> building.id),
            Optional.empty(), Optional.empty(), JourneyOutcome.NONE));
    }

    /** Called once, immediately after one persistent equipment request opens. */
    public static boolean noteEquipmentRequestOpened(ServerLevel level,
                                                     Settlement settlement,
                                                     Building workplace,
                                                     SettlerEntity settler,
                                                     EquipmentRequest request) {
        ResourceLocation step = equipmentStep(request == null
            ? null : request.profession(), false);
        if (step == null || !live(level, settlement)
            || !exactRegistered(settlement, workplace) || !workplace.valid
            || settler == null || !settler.isAlive() || settler.level() != level
            || level.getEntity(settler.getId()) != settler
            || settlement.record(settler.getUUID()) == null
            || !settlement.id.equals(settler.getSettlementId())
            || request.status() != EquipmentRequest.Status.OPEN
            || !workplace.equipmentRequests.contains(request)
            || !request.requesterId().equals(settler.getUUID())
            || !request.destinationBuildingId().equals(workplace.id)
            || Employment.employerOf(settlement, settler.getUUID()) != workplace
            || Employment.professionOf(settlement, settler.getUUID())
                != request.profession()
            || request.requirement().serviceable(
                settler.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND))) {
            return false;
        }
        String fingerprint = equipmentFingerprint(
            request.requirement().preferredItem().getDefaultInstance());
        return accepted(record(level, settlement, step,
            JourneyEvent.EQUIPMENT_REQUEST_OPENED, request.id(),
            Optional.empty(), Optional.of(settler.getUUID()),
            Optional.of(workplace.id), Optional.of(request.id()),
            Optional.of(fingerprint), JourneyOutcome.NONE));
    }

    /**
     * Called after one exact request disappeared because its physical tool is
     * now the worker's real main-hand stack. Merely marking a Courier row as
     * delivered is intentionally insufficient for this milestone.
     */
    public static boolean noteEquipmentRequestSatisfied(ServerLevel level,
                                                        Settlement settlement,
                                                        Building workplace,
                                                        SettlerEntity settler,
                                                        EquipmentRequest request) {
        ResourceLocation step = equipmentStep(request == null
            ? null : request.profession(), true);
        ItemStack equipped = settler == null ? ItemStack.EMPTY
            : settler.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        if (step == null || !live(level, settlement)
            || !exactRegistered(settlement, workplace) || !workplace.valid
            || settler == null || !settler.isAlive() || settler.level() != level
            || level.getEntity(settler.getId()) != settler
            || settlement.record(settler.getUUID()) == null
            || !settlement.id.equals(settler.getSettlementId())
            || !request.requesterId().equals(settler.getUUID())
            || !request.destinationBuildingId().equals(workplace.id)
            || workplace.equipmentRequests.contains(request)
            || EquipmentRequests.requestFor(workplace, settler.getUUID()) != null
            || Employment.employerOf(settlement, settler.getUUID()) != workplace
            || Employment.professionOf(settlement, settler.getUUID())
                != request.profession()
            || !request.requirement().serviceable(equipped)) {
            return false;
        }
        return accepted(record(level, settlement, step,
            JourneyEvent.REQUEST_SATISFIED_COMMITTED, request.id(),
            Optional.empty(), Optional.of(settler.getUUID()),
            Optional.of(workplace.id), Optional.of(request.id()),
            Optional.of(equipmentFingerprint(equipped)), JourneyOutcome.NONE));
    }

    /** Exact Hearth-owned global ledger session, built on the server thread. */
    public static boolean noteRequestLedgerViewed(ServerPlayer player,
                                                  Settlement settlement,
                                                  RequestLedgerSnapshot snapshot) {
        if (player == null || settlement == null || snapshot == null
            || !(player.containerMenu instanceof HearthMenu menu)
            || !menu.getSettlementId().equals(settlement.id)
            || !menu.getHearthPos().equals(settlement.center)
            || !menu.stillValid(player)
            || !snapshot.settlementId().equals(settlement.id)
            || snapshot.generatedTick() != player.serverLevel().getGameTime()) {
            return false;
        }
        RequestLedger ledger = RequestLedgerSavedData.get(player.serverLevel())
            .existing(settlement.id);
        long liveRevision = ledger == null ? 0L : ledger.revision();
        if (snapshot.typedRevision() != liveRevision
            || snapshot.equipmentRevision()
                != settlement.equipmentRequestQueue.revision()) {
            return false;
        }
        long token = Integer.toUnsignedLong(java.util.Objects.hash(
            menu.getContainerId(), snapshot.typedRevision(),
            snapshot.equipmentRevision(), snapshot.rows().size(),
            snapshot.generatedTick()));
        UUID transaction = JourneyTransactionIds.forRevision(
            "request_ledger_view", settlement.id, player.getUUID(), token);
        return accepted(record(player.serverLevel(), settlement,
            JourneyIds.FJ_230_OPEN_REQUEST_LEDGER,
            JourneyEvent.REQUEST_LEDGER_VIEW_OPENED, transaction,
            Optional.of(player.getUUID()), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(snapshot.truncated()
                ? "snapshot:truncated" : "snapshot:bounded"),
            JourneyOutcome.NONE));
    }

    /** Re-observes an exact active OPEN output row after SavedData dirtying. */
    public static boolean noteOutputPickupRequestOpened(ServerLevel level,
                                                        Settlement settlement,
                                                        Building source,
                                                        Building target,
                                                        RequestRecord request) {
        RequestLedger ledger = requestLedger(level, settlement);
        if (!liveOutput(level, settlement, source, target, request, ledger)
            || ledger.active(request.id()) != request
            || request.state() != RequestState.OPEN
            || source.type != BuildingType.LUMBER_CAMP
            || !isLog(request)) {
            return false;
        }
        return accepted(record(level, settlement,
            JourneyIds.FJ_240_REQUEST_FIRST_PICKUP,
            JourneyEvent.OUTPUT_PICKUP_REQUEST_OPENED, request.id(),
            Optional.empty(), Optional.empty(), Optional.of(source.id),
            Optional.of(request.id()), Optional.of(requestFingerprint(request)),
            JourneyOutcome.NONE));
    }

    /** Re-observes the unique persisted Courier reservation. */
    public static boolean noteOutputPickupReserved(ServerLevel level,
                                                   Settlement settlement,
                                                   SettlerEntity courier,
                                                   RequestRecord request) {
        RequestLedger ledger = requestLedger(level, settlement);
        Building source = request == null ? null
            : exactBuilding(settlement, request.sourceBuildingId());
        Building target = request == null ? null
            : exactBuilding(settlement, request.targetBuildingId());
        if (!liveOutput(level, settlement, source, target, request, ledger)
            || ledger.active(request.id()) != request
            || request.state() != RequestState.RESERVED
            || courier == null || courier.level() != level || !courier.isAlive()
            || !courier.getUUID().equals(request.courierId())
            || !settlement.id.equals(courier.getSettlementId())
            || settlement.record(courier.getUUID()) == null
            || Employment.professionOf(settlement, courier.getUUID())
                != Profession.COURIER
            || source.type != BuildingType.LUMBER_CAMP || !isLog(request)) {
            return false;
        }
        return accepted(record(level, settlement,
            JourneyIds.FJ_250_COURIER_CLAIMS_PICKUP,
            JourneyEvent.REQUEST_RESERVATION_COMMITTED, request.id(),
            Optional.empty(), Optional.of(courier.getUUID()),
            Optional.of(source.id), Optional.of(request.id()),
            Optional.of(requestFingerprint(request)), JourneyOutcome.NONE));
    }

    /**
     * Re-observes bounded terminal history plus the complete physical state
     * trace. Logs complete FJ-260; approved crops reuse the same authority for
     * FJ-380.
     */
    public static boolean noteOutputPickupSatisfied(ServerLevel level,
                                                    Settlement settlement,
                                                    Building source,
                                                    Building target,
                                                    SettlerEntity courier,
                                                    RequestRecord request) {
        RequestLedger ledger = requestLedger(level, settlement);
        if (!liveOutput(level, settlement, source, target, request, ledger)
            || ledger.any(request.id()) != request
            || !ledger.terminalHistory().contains(request)
            || request.state() != RequestState.SATISFIED
            || !request.hasFullTransportTrace()
            || courier == null || courier.level() != level
            || !courier.getUUID().equals(request.courierId())
            || !settlement.id.equals(courier.getSettlementId())
            || target.type != BuildingType.WAREHOUSE) {
            return false;
        }
        ResourceLocation step;
        if (source.type == BuildingType.LUMBER_CAMP && isLog(request)) {
            step = JourneyIds.FJ_260_WAREHOUSE_RECEIVES_LOG;
        } else if (source.type == BuildingType.FARMHOUSE && isCrop(request)
            || source.type == BuildingType.FISHERY
                && new ItemStack(BuiltInRegistries.ITEM.get(request.fingerprint().itemId()))
                    .is(ItemTags.FISHES)) {
            step = JourneyIds.FJ_380_WAREHOUSE_RECEIVES_CROP;
        } else {
            return false;
        }
        return accepted(record(level, settlement, step,
            JourneyEvent.REQUEST_SATISFIED_COMMITTED, request.id(),
            Optional.empty(), Optional.of(courier.getUUID()),
            Optional.of(target.id), Optional.of(request.id()),
            Optional.of(requestFingerprint(request)), JourneyOutcome.NONE));
    }

    /** Persisted qualification proof; later monotonic phases remain valid. */
    public static boolean noteRecruitmentQualificationCommitted(
            ServerLevel level, Settlement settlement,
            UUID persistedTransactionId) {
        RecruitmentTransaction transaction = recruitmentTransaction(level,
            settlement, persistedTransactionId);
        Building tavern = recruitmentTavern(level, settlement, transaction);
        ResourceLocation step = recruitmentStep(settlement == null
                ? null : settlement.journeyState, transaction,
            JourneyEvent.RECRUITMENT_QUALIFICATION_STARTED_COMMITTED);
        if (transaction == null || tavern == null
            || transaction.qualificationStartedTick() < 0L) {
            return false;
        }
        // Valid natural transactions outside an active tutorial admission
        // slot still need their derived evidence bit acknowledged so normal
        // recurring recruitment can never stall behind presentation state.
        if (step == null) {
            return true;
        }
        JourneyApplyResult result = record(level, settlement,
            step,
            JourneyEvent.RECRUITMENT_QUALIFICATION_STARTED_COMMITTED,
            persistedTransactionId, Optional.empty(), Optional.empty(),
            Optional.of(tavern.id), Optional.empty(),
            Optional.of("cycle:" + transaction.cycle()), JourneyOutcome.NONE);
        emitRecruitment(result, level,
            AuthorityTelemetry.Event.RECRUITMENT_QUALIFICATION_STARTED,
            settlement, transaction, tavern.id, 0, 0, 0,
            "qualification_persisted");
        return acceptedEvidence(result);
    }

    /** Persisted arrival proof; ADMITTED/LEFT still prove the prior arrival. */
    public static boolean noteTravelerArrivedAtTavern(ServerLevel level,
                                                       Settlement settlement,
                                                       UUID persistedTransactionId) {
        RecruitmentTransaction transaction = recruitmentTransaction(level,
            settlement, persistedTransactionId);
        Building tavern = recruitmentTavern(level, settlement, transaction);
        ResourceLocation step = recruitmentStep(settlement == null
                ? null : settlement.journeyState, transaction,
            JourneyEvent.TRAVELER_ARRIVED_AT_TAVERN_COMMITTED);
        if (transaction == null || tavern == null
            || transaction.travelerId() == null
            || transaction.spawnedTick() < 0L
            || transaction.arrivedTick() < transaction.spawnedTick()) {
            return false;
        }
        if (step == null) {
            return true;
        }
        JourneyApplyResult result = record(level, settlement,
            step,
            JourneyEvent.TRAVELER_ARRIVED_AT_TAVERN_COMMITTED,
            persistedTransactionId, Optional.empty(),
            Optional.of(transaction.travelerId()), Optional.of(tavern.id),
            Optional.empty(), Optional.of("arrival:" + transaction.arrivedTick()),
            JourneyOutcome.NONE);
        emitRecruitment(result, level,
            AuthorityTelemetry.Event.TRAVELER_ARRIVED_AT_TAVERN,
            settlement, transaction, transaction.travelerId(), 0, 0, 0,
            "arrival_persisted");
        return acceptedEvidence(result);
    }

    /** Exact persisted admission/payment receipt; never a client assertion. */
    public static boolean noteTravelerAdmitted(ServerLevel level,
                                               Settlement settlement,
                                               UUID persistedTransactionId) {
        RecruitmentTransaction transaction = recruitmentTransaction(level,
            settlement, persistedTransactionId);
        RecruitmentTransaction.AdmissionReceipt receipt = transaction == null
            ? null : transaction.admissionReceipt();
        ResourceLocation step = recruitmentStep(settlement == null
                ? null : settlement.journeyState, transaction,
            JourneyEvent.TRAVELER_ADMITTED_COMMITTED);
        if (transaction == null
            || transaction.status() != RecruitmentTransaction.Status.ADMITTED
            || transaction.terminalReason()
                != RecruitmentTransaction.TerminalReason.ADMITTED
            || transaction.travelerId() == null || receipt == null
            || !receipt.valid()
            || settlement.record(transaction.travelerId()) == null) {
            return false;
        }
        if (step == null) {
            return true;
        }
        JourneyApplyResult result = record(level, settlement,
            step,
            JourneyEvent.TRAVELER_ADMITTED_COMMITTED,
            persistedTransactionId, Optional.of(receipt.playerId()),
            Optional.of(transaction.travelerId()), Optional.empty(),
            Optional.empty(), Optional.of(receipt.paymentFingerprint()),
            JourneyOutcome.NONE);
        emitRecruitment(result, level,
            AuthorityTelemetry.Event.TRAVELER_ADMITTED, settlement,
            transaction, transaction.travelerId(),
            receipt.removedItemCount(), 0, -receipt.removedItemCount(),
            "admission_payment_persisted");
        return acceptedEvidence(result);
    }

    public static boolean noteChargedJobBinding(ServerPlayer player,
                                                Settlement settlement,
                                                Building building,
                                                SettlerEntity settler,
                                                UUID emblemTransaction) {
        ResourceLocation step = staffStep(settler == null
            ? null : settler.getProfession());
        if (step == null || player == null || emblemTransaction == null
            || settler == null || !settler.isAlive()
            || settler.level() != player.serverLevel()
            || player.serverLevel().getEntity(settler.getId()) != settler
            || !live(player.serverLevel(), settlement)
            || !exactRegistered(settlement, building) || !building.valid
            || !building.workers.contains(settler.getUUID())
            || Employment.employerOf(settlement, settler.getUUID()) != building
            || !settlement.id.equals(settler.getSettlementId())
            || settlement.record(settler.getUUID()) == null
            || !staffingMatchesBuilding(settler.getProfession(), building.type)) {
            return false;
        }
        return accepted(record(player.serverLevel(), settlement, step,
            JourneyEvent.JOB_EMBLEM_BOUND_COMMITTED, emblemTransaction,
            Optional.of(player.getUUID()), Optional.of(settler.getUUID()),
            Optional.of(building.id), Optional.empty(), Optional.empty(),
            JourneyOutcome.NONE));
    }

    /** Re-observes one terminal, persisted natural-tree action. */
    public static boolean noteLumberTreeCommitted(ServerLevel level,
                                                   Settlement settlement,
                                                   Building building,
                                                   SettlerEntity worker,
                                                   UUID actionId) {
        ActionView action = terminalWorkerAction(level, settlement, building,
            worker, actionId, Kind.LUMBER_TREE);
        if (action == null || action.produced().isEmpty()) {
            return false;
        }
        JourneyApplyResult result = record(level, settlement,
            JourneyIds.FJ_170_LUMBERER_FELLS_TREE,
            JourneyEvent.LUMBERER_TREE_COMPLETED, action.id(),
            Optional.empty(), Optional.of(worker.getUUID()),
            Optional.of(building.id), Optional.empty(),
            Optional.of(workerActionFingerprint(action)), JourneyOutcome.NONE);
        return acceptedEvidence(result);
    }

    /** Re-observes a consumed, planted seed and exact one-point tool cost. */
    public static boolean noteFarmSeedPlantedCommitted(ServerLevel level,
                                                        Settlement settlement,
                                                        Building building,
                                                        SettlerEntity worker,
                                                        UUID actionId) {
        ActionView action = terminalWorkerAction(level, settlement, building,
            worker, actionId, Kind.FARM_PLANT);
        if (action == null || action.inputItem() == null
            || action.inputCount() != 1 || action.resolved().size() != 1) {
            return false;
        }
        JourneyApplyResult result = record(level, settlement,
            JourneyIds.FJ_360_SUPPLY_FIRST_SEED,
            JourneyEvent.MATERIAL_INPUT_COMMITTED, action.id(),
            Optional.empty(), Optional.of(worker.getUUID()),
            Optional.of(building.id), Optional.empty(),
            Optional.of(workerActionFingerprint(action)), JourneyOutcome.NONE);
        return acceptedEvidence(result);
    }

    /**
     * Farm harvest is a domain-authority seam. FJ-370 is deliberately not
     * authored here: it requires the later physical workplace deposit receipt.
     */
    public static boolean noteFarmHarvestCommitted(ServerLevel level,
                                                    Settlement settlement,
                                                    Building building,
                                                    SettlerEntity worker,
                                                    UUID actionId) {
        ActionView action = terminalWorkerAction(level, settlement, building,
            worker, actionId, Kind.FARM_HARVEST);
        if (action == null || action.produced().isEmpty()) {
            return false;
        }
        return true;
    }

    /** Re-observes the immutable receipt for an exact linked workplace chest. */
    public static boolean noteWorkplaceOutputCommitted(ServerLevel level,
                                                        Settlement settlement,
                                                        Building building,
                                                        SettlerEntity worker,
                                                        UUID receiptId) {
        if (!live(level, settlement) || receiptId == null) {
            return false;
        }
        WorkerProvenanceSavedData data = WorkerProvenanceSavedData.get(level);
        DepositReceipt receipt = data.receipt(receiptId);
        ActionView action = receipt == null ? null : data.action(receipt.actionId());
        if (data.quarantined() || receipt == null || action == null
            || action.phase() != WorkerProvenanceSavedData.Phase.OUTPUT_COMMITTED
            || terminalWorkerAction(level, settlement, building, worker,
                action.id(), action.kind()) == null
            || !receipt.settlementId().equals(settlement.id)
            || !receipt.buildingId().equals(building.id)
            || !receipt.workerId().equals(worker.getUUID())
            || !receipt.dimension().equals(level.dimension().location())
            || !WorkerStorageAuthority.loadedContainers(level, building)
                .contains(receipt.containerPos())
            || action.deposited().getOrDefault(receipt.itemId(), 0)
                < receipt.count()
            || action.produced().getOrDefault(receipt.itemId(), 0)
                < action.deposited().getOrDefault(receipt.itemId(), 0)
            || !receiptTotalsMatch(data, action, receipt.itemId())) {
            return false;
        }
        ResourceLocation step = switch (action.kind()) {
            case LUMBER_TREE -> JourneyIds.FJ_180_LUMBER_CAMP_STORES_LOG;
            case FARM_HARVEST -> JourneyIds.FJ_370_FARMHOUSE_STORES_CROP;
            case FARM_PLANT -> null;
        };
        if (step == null) {
            return false;
        }
        JourneyApplyResult result = record(level, settlement, step,
            JourneyEvent.WORKPLACE_OUTPUT_COMMITTED, receipt.id(),
            Optional.empty(), Optional.of(worker.getUUID()),
            Optional.of(building.id), Optional.empty(),
            Optional.of("item:" + receipt.itemId() + ";count:"
                + receipt.count()), JourneyOutcome.NONE);
        return acceptedEvidence(result);
    }

    /** Genuine Fisher rack output reuses the stable food-security milestones. */
    public static boolean noteFisheryOutputCommitted(ServerLevel level,
            Settlement settlement, Building building, SettlerEntity worker) {
        if (!live(level, settlement) || !exactRegistered(settlement, building)
            || !building.valid || building.type != BuildingType.FISHERY
            || worker == null || !worker.isAlive() || worker.level() != level
            || !settlement.id.equals(worker.getSettlementId())
            || worker.getProfession() != Profession.FISHER
            || !building.workers.contains(worker.getUUID())
            || !com.hearthstead.settlement.work.FisherEvidenceSavedData.hasStoredCatch(level, settlement)) {
            return false;
        }
        boolean applied = false;
        for (ResourceLocation step : List.of(JourneyIds.FJ_360_SUPPLY_FIRST_SEED,
                JourneyIds.FJ_370_FARMHOUSE_STORES_CROP)) {
            if (settlement.journeyState.evidence().stream().anyMatch(e ->
                    e.stepId().equals(step) && e.event() == JourneyEvent.WORKPLACE_OUTPUT_COMMITTED)) continue;
            UUID transaction = stableSubject("fisher_output", settlement.id + ":" + step);
            applied |= accepted(record(level, settlement, step,
                JourneyEvent.WORKPLACE_OUTPUT_COMMITTED, transaction,
                Optional.empty(), Optional.of(worker.getUUID()), Optional.of(building.id),
                Optional.empty(), Optional.of("fishery_rack_output"), JourneyOutcome.NONE));
        }
        return applied;
    }

    public static boolean noteEmblemPurchased(ServerPlayer player,
                                              Settlement settlement,
                                              Profession profession,
                                              UUID emblemTransaction) {
        ResourceLocation step = staffStep(profession);
        if (step == null || player == null || emblemTransaction == null) {
            return false;
        }
        return accepted(record(player.serverLevel(), settlement, step,
            JourneyEvent.EMBLEM_TRADE_COMMITTED, emblemTransaction,
            Optional.of(player.getUUID()), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of("profession:" + profession.key()),
            JourneyOutcome.NONE));
    }

    /** Re-observes one persisted, active Guard order after its revision commit. */
    public static boolean noteGuardAssignmentCommitted(ServerPlayer player,
                                                       Settlement settlement,
                                                       SettlerEntity guard,
                                                       GuardOrder order,
                                                       int committedRevision) {
        Profession profession = guard == null ? null : guard.getProfession();
        ResourceLocation step = profession == Profession.GUARD
            ? JourneyIds.FJ_550_SET_GUARD_ORDER
            : profession == Profession.ARCHER
                ? JourneyIds.FJ_559B_SET_TOWER_POST : null;
        boolean validMode = profession == Profession.GUARD
            ? order != null && (order.mode() == GuardOrder.Mode.STAND_POST
                || order.mode() == GuardOrder.Mode.PATROL_ROUTE)
            : profession == Profession.ARCHER
                && order != null && order.mode() == GuardOrder.Mode.TOWER_POST;
        if (player == null || settlement == null || guard == null || order == null
            || step == null || !validMode
            || committedRevision <= 0 || order.revision() != committedRevision
            || settlement.guardOrders.order(guard.getUUID()).orElse(null) != order
            || !order.activeAt(player.serverLevel().getGameTime())
            || !settlement.id.equals(guard.getSettlementId())
            || settlement.record(guard.getUUID()) == null
            || !order.ownedBy(settlement.id, guard.getUUID(),
                player.serverLevel().dimension().location())) {
            return false;
        }
        Building employer = Employment.employerOf(settlement, guard.getUUID());
        var requirement = EquipmentRequests.requirementFor(profession);
        BuildingType expectedEmployer = profession == Profession.GUARD
            ? BuildingType.BARRACKS : BuildingType.WATCHTOWER;
        if (!live(player.serverLevel(), settlement)
            || !exactRegistered(settlement, employer) || !employer.valid
            || employer.type != expectedEmployer
            || order.linkedBuildingId().filter(employer.id::equals).isEmpty()
            || requirement == null || !requirement.serviceable(
                guard.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND))) {
            return false;
        }
        if (profession == Profession.ARCHER
            && !noteArcherAmmunitionStored(player.serverLevel(), settlement,
                employer, guard, order)) {
            return false;
        }
        UUID transaction = JourneyTransactionIds.forRevision("guard_assignment",
            settlement.id, guard.getUUID(), committedRevision);
        String fingerprint = "mode:" + order.mode().id()
            + ";points:" + order.patrolPoints().size();
        return accepted(record(player.serverLevel(), settlement,
            step,
            JourneyEvent.GUARD_ASSIGNMENT_COMMITTED, transaction,
            Optional.of(player.getUUID()), Optional.of(guard.getUUID()),
            Optional.of(employer.id), Optional.empty(), Optional.of(fingerprint),
            JourneyOutcome.NONE));
    }

    /**
     * Exact physical-ammunition observation for the current linked tower.
     *
     * <p>There is no synthetic ammo request. The accepted fact is at least one
     * real Arrow stack in a loaded container inside this Archer's exact linked
     * Watchtower, observed while their persisted Tower Post order commits.
     * This method is also a narrow future adapter for a real Courier delivery
     * receipt; an unrelated tower or an Archer bag cannot satisfy it.
     */
    public static boolean noteArcherAmmunitionStored(ServerLevel level,
                                                     Settlement settlement,
                                                     Building tower,
                                                     SettlerEntity archer,
                                                     GuardOrder towerOrder) {
        if (!live(level, settlement) || archer == null || towerOrder == null
            || !archer.isAlive() || archer.level() != level
            || level.getEntity(archer.getId()) != archer
            || archer.getProfession() != Profession.ARCHER
            || !settlement.id.equals(archer.getSettlementId())
            || settlement.record(archer.getUUID()) == null
            || !exactRegistered(settlement, tower) || !tower.valid
            || tower.type != BuildingType.WATCHTOWER
            || Employment.employerOf(settlement, archer.getUUID()) != tower
            || !tower.workers.contains(archer.getUUID())
            || settlement.guardOrders.order(archer.getUUID()).orElse(null)
                != towerOrder
            || towerOrder.mode() != GuardOrder.Mode.TOWER_POST
            || !towerOrder.activeAt(level.getGameTime())
            || !towerOrder.ownedBy(settlement.id, archer.getUUID(),
                level.dimension().location())
            || towerOrder.linkedBuildingId().filter(tower.id::equals).isEmpty()) {
            return false;
        }
        int arrows = physicalTowerArrows(level, tower);
        if (arrows <= 0) {
            return false;
        }
        long token = ((long) towerOrder.revision() << 32)
            ^ Integer.toUnsignedLong(arrows);
        UUID transaction = JourneyTransactionIds.forRevision(
            "archer_ammunition", settlement.id, tower.id, token);
        if (hasEvidence(settlement,
                JourneyIds.FJ_559A_SUPPLY_ARCHER_AMMUNITION,
                JourneyEvent.ARCHER_AMMUNITION_STORED_COMMITTED,
                transaction)) {
            return true;
        }
        return accepted(record(level, settlement,
            JourneyIds.FJ_559A_SUPPLY_ARCHER_AMMUNITION,
            JourneyEvent.ARCHER_AMMUNITION_STORED_COMMITTED,
            transaction, Optional.empty(), Optional.of(archer.getUUID()),
            Optional.of(tower.id), Optional.empty(),
            Optional.of("item:minecraft:arrow;count:" + arrows
                + ";order_revision:" + towerOrder.revision()),
            JourneyOutcome.NONE));
    }

    public static boolean noteFirstRaidReadiness(ServerLevel level,
                                                 Settlement settlement) {
        if (!live(level, settlement)
            || settlement.raidLifecycle.firstState() != FirstRaidState.SCHEDULED
            || !JourneyReadinessGate.canRecordDeclaration(settlement.journeyState)
            || !FirstRaidReadinessService.assessScheduledCommitBridge(level,
                settlement).ready()) {
            return false;
        }
        UUID transaction = JourneyTransactionIds.forRevision("first_raid_ready",
            settlement.id, settlement.id,
            Math.max(0L, settlement.raidLifecycle.firstAttackNight()));
        // Skip is presentation-only. Passing the live domain assessment lets
        // the existing persisted raid calendar proceed, but authors no fake
        // FJ-010..FJ-560 evidence and grants no value.
        if (settlement.journeyState.mode() == JourneyPresentationMode.SKIPPED) {
            return true;
        }
        return accepted(record(level, settlement,
            JourneyIds.FJ_560_DECLARE_RAID_READY,
            JourneyEvent.FIRST_RAID_READINESS_COMMITTED,
            transaction, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), JourneyOutcome.NONE));
    }

    public static boolean noteFirstRaidWarning(ServerLevel level,
                                               Settlement settlement,
                                               RaidPlan plan) {
        if (!live(level, settlement) || !RaidPlan.isValid(plan)
            || settlement.raidLifecycle.firstState() != FirstRaidState.SCHEDULED
            || settlement.raidLifecycle.integrityLost()
            || !plan.equals(settlement.raidLifecycle.queuedPlan().orElse(null))
            || plan.night() != settlement.raidLifecycle.firstAttackNight()
            || RaidDirector.leaderNameOf(settlement,
                plan.captainId()).isEmpty()) {
            return false;
        }
        JourneyPresentationMode mode = settlement.journeyState.mode();
        if (mode == JourneyPresentationMode.SKIPPED) {
            return true;
        }
        if (mode == JourneyPresentationMode.QUARANTINED
            || !settlement.journeyState.completedThrough(
                JourneyIds.FJ_560_DECLARE_RAID_READY)) {
            return false;
        }
        if (mode != JourneyPresentationMode.ACTIVE) {
            return false;
        }
        if (settlement.journeyState.isCompleted(
                JourneyIds.FJ_600_RECEIVE_FIRST_WARNING)) {
            return true;
        }
        String authorityTarget = raidWarningAuthorityTarget(plan);
        String authorityReason = raidWarningAuthorityReason(plan);
        if (!AuthorityTelemetry.isCanonicalToken(authorityTarget)
            || !AuthorityTelemetry.isCanonicalToken(authorityReason)) {
            return false;
        }
        UUID transaction = JourneyTransactionIds.forRevision("first_raid_warning",
            settlement.id, plan.captainId(), plan.night());
        int revisionBefore = settlement.journeyState.revision();
        int completedBefore = settlement.journeyState.completedCount();
        JourneyApplyResult result = record(level, settlement,
            JourneyIds.FJ_600_RECEIVE_FIRST_WARNING,
            JourneyEvent.FIRST_RAID_WARNING_COMMITTED,
            transaction, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of("objective:" + plan.objective().id()),
            JourneyOutcome.NONE);
        int revisionAfter = settlement.journeyState.revision();
        int completedAfter = settlement.journeyState.completedCount();
        if (raidAuthorityTransitionApplied(result, revisionBefore, revisionAfter,
                completedBefore, completedAfter)) {
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.RAID_WARNING_COMMITTED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.state(settlement.id,
                    authorityTarget, revisionBefore, revisionAfter,
                    completedBefore, completedAfter, authorityReason));
        }
        return accepted(result);
    }

    public static boolean noteFirstRaidResolved(ServerLevel level,
                                                Settlement settlement,
                                                RaidPlan plan,
                                                JourneyOutcome outcome) {
        if (!live(level, settlement) || plan == null || outcome == null
            || !outcome.terminal()
            || settlement.raidLifecycle.firstState() != FirstRaidState.COMPLETED
            || settlement.raidLifecycle.integrityLost()
            || !plan.equals(settlement.raidLifecycle.activePlan().orElse(null))
            || !exactFirstRaidTerminal(settlement, plan, outcome)) {
            return false;
        }
        JourneyPresentationMode mode = settlement.journeyState.mode();
        if (mode == JourneyPresentationMode.SKIPPED) {
            return true;
        }
        if (mode == JourneyPresentationMode.QUARANTINED
            || !settlement.journeyState.completedThrough(
                JourneyIds.FJ_600_RECEIVE_FIRST_WARNING)) {
            return false;
        }
        if (settlement.journeyState.isCompleted(
                JourneyIds.FJ_610_FIRST_RAID_RESOLVED)) {
            return settlement.journeyState.outcome() == outcome;
        }
        if (mode != JourneyPresentationMode.ACTIVE) {
            return false;
        }
        UUID transaction = JourneyTransactionIds.forRevision("first_raid_resolution",
            settlement.id, plan.captainId(), plan.night());
        boolean recorded = accepted(record(level, settlement,
            JourneyIds.FJ_610_FIRST_RAID_RESOLVED,
            JourneyEvent.FIRST_RAID_RESOLVED_COMMITTED,
            transaction, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of("objective:" + plan.objective().id()),
            outcome));
        return recorded && settlement.journeyState.isCompleted(
                JourneyIds.FJ_610_FIRST_RAID_RESOLVED)
            && settlement.journeyState.outcome() == outcome;
    }

    private static boolean exactFirstRaidTerminal(Settlement settlement,
                                                  RaidPlan plan,
                                                  JourneyOutcome outcome) {
        var terminal = settlement.raidLifecycle.firstRaidTerminal().orElse(null);
        if (terminal == null || terminal.outcome() != outcome
            || !terminal.matches(plan)
            || settlement.raidLog.isEmpty()
            || settlement.raidLog.size() > RaidDirector.MAX_RAID_LOG) {
            return false;
        }
        int exactMatches = 0;
        for (RaidLogEntry entry : settlement.raidLog) {
            if (!RaidLogEntry.isValid(entry)) {
                return false;
            }
            if (terminal.aftermath().equals(entry)) {
                exactMatches++;
            }
        }
        return exactMatches == 1
            && terminal.aftermath().equals(settlement.raidLog.getLast());
    }

    /** Stable terminal telemetry vocabulary; HIT must never alias LOST. */
    public static String raidResolutionAuthorityReason(JourneyOutcome outcome) {
        return switch (outcome) {
            case HELD -> "settlement_held";
            case HIT -> "settlement_hit";
            case SETTLEMENT_LOST -> "settlement_lost";
            case NONE -> "invalid_outcome";
        };
    }

    public static boolean noteRaidAftermathViewed(ServerPlayer player,
                                                  Settlement settlement) {
        if (player == null || settlement == null || settlement.raidLog.isEmpty()
            || !(player.containerMenu instanceof HearthMenu menu)
            || !menu.getSettlementId().equals(settlement.id)
            || !menu.getHearthPos().equals(settlement.center)
            || !menu.stillValid(player)) {
            return false;
        }
        RaidLogEntry entry = settlement.raidLog.getLast();
        if (!RaidLogEntry.isValid(entry)) {
            return false;
        }
        String authorityTarget = raidAftermathAuthorityTarget(entry);
        String authorityReason = raidAftermathAuthorityReason(entry);
        if (!AuthorityTelemetry.isCanonicalToken(authorityTarget)
            || !AuthorityTelemetry.isCanonicalToken(authorityReason)) {
            return false;
        }
        long night = entry.night();
        UUID transaction = JourneyTransactionIds.forRevision("raid_aftermath_view",
            settlement.id, player.getUUID(), night);
        int revisionBefore = settlement.journeyState.revision();
        int completedBefore = settlement.journeyState.completedCount();
        JourneyApplyResult result = record(player.serverLevel(), settlement,
            JourneyIds.FJ_620_REVIEW_AFTERMATH,
            JourneyEvent.RAID_AFTERMATH_VIEW_OPENED,
            transaction, Optional.of(player.getUUID()), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of("raid_night:" + night),
            JourneyOutcome.NONE);
        int revisionAfter = settlement.journeyState.revision();
        int completedAfter = settlement.journeyState.completedCount();
        if (raidAuthorityTransitionApplied(result, revisionBefore, revisionAfter,
                completedBefore, completedAfter)) {
            AuthorityTelemetry.emit(player.serverLevel(),
                AuthorityTelemetry.Event.RAID_AFTERMATH_VIEWED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.state(settlement.id,
                    authorityTarget, revisionBefore, revisionAfter,
                    completedBefore, completedAfter, authorityReason));
        }
        return accepted(result);
    }

    static boolean raidAuthorityTransitionApplied(JourneyApplyResult result,
                                                  int revisionBefore,
                                                  int revisionAfter,
                                                  int completedBefore,
                                                  int completedAfter) {
        return result == JourneyApplyResult.APPLIED
            && revisionBefore >= 0
            && (long) revisionAfter == (long) revisionBefore + 1L
            && completedBefore >= 0
            && (long) completedAfter == (long) completedBefore + 1L;
    }

    static String raidWarningAuthorityTarget(RaidPlan plan) {
        return "first_raid_warning:" + plan.night() + ":captain:"
            + plan.captainId();
    }

    static String raidWarningAuthorityReason(RaidPlan plan) {
        return "persisted_plan:" + plan.objective().id() + ":approach_bits:"
            + Integer.toUnsignedString(Float.floatToIntBits(
                plan.approachDegrees()));
    }

    static String raidAftermathAuthorityTarget(RaidLogEntry entry) {
        String reportFingerprint = AuthorityTelemetry.fingerprint(
            "raid_aftermath_report_v1", Long.toString(entry.night()),
            entry.captainName(), entry.objectiveId(), Boolean.toString(entry.held()),
            Integer.toString(entry.itemsStolen()),
            Integer.toString(entry.settlersHurt()), entry.stageAfterId());
        return "raid_aftermath:" + entry.night() + ":report:"
            + reportFingerprint;
    }

    static String raidAftermathAuthorityReason(RaidLogEntry entry) {
        return "report_viewed:" + (entry.held() ? "held" : "lost")
            + ":" + entry.objectiveId() + ":stolen:" + entry.itemsStolen()
            + ":hurt:" + entry.settlersHurt() + ":stage:"
            + entry.stageAfterId();
    }

    private static JourneyApplyResult record(ServerLevel level,
                                             Settlement settlement,
                                             ResourceLocation step,
                                             JourneyEvent event,
                                             UUID transaction,
                                             Optional<UUID> actor,
                                             Optional<UUID> subject,
                                             Optional<UUID> building,
                                             Optional<UUID> request,
                                             Optional<String> fingerprint,
                                             JourneyOutcome outcome) {
        if (!live(level, settlement)) {
            return JourneyApplyResult.QUARANTINED;
        }
        return record(level, settlement, step, event, transaction, actor, subject,
            building, request, fingerprint, outcome, Math.max(0L, level.getGameTime()));
    }

    private static JourneyApplyResult record(ServerLevel level,
                                             Settlement settlement,
                                             ResourceLocation step,
                                             JourneyEvent event,
                                             UUID transaction,
                                             Optional<UUID> actor,
                                             Optional<UUID> subject,
                                             Optional<UUID> building,
                                             Optional<UUID> request,
                                             Optional<String> fingerprint,
                                             JourneyOutcome outcome,
                                             long committedAt) {
        if (!live(level, settlement)) {
            return JourneyApplyResult.QUARANTINED;
        }
        JourneyEvidence evidence = new JourneyEvidence(step, event, transaction,
            committedAt, settlement.id, actor, subject,
            building, request, fingerprint, JourneySource.SURVIVAL, outcome);
        java.util.Optional<ResourceLocation> chapterBefore = settlement.journeyState.currentChapter();
        JourneyApplyResult result = new JourneyProgressService(
            settlement.journeyState).recordCommitted(evidence);
        if (result == JourneyApplyResult.APPLIED && chapterBefore.isPresent()
            && !chapterBefore.equals(settlement.journeyState.currentChapter())) {
            com.hearthstead.fx.FxHooks.journeyChapter(level, settlement);
        }
        if (result == JourneyApplyResult.APPLIED
            || result == JourneyApplyResult.RECORDED_NON_PROGRESS) {
            SettlementManager.data(level).setDirty();
        }
        return result;
    }

    private static boolean live(ServerLevel level, Settlement settlement) {
        return level != null && settlement != null
            && level.getServer().isSameThread()
            && settlement.journeyState != null
            && settlement.id.equals(settlement.journeyState.settlementId())
            && SettlementManager.byId(level, settlement.id) == settlement;
    }

    private static boolean exactRegistered(Settlement settlement, Building building) {
        if (settlement == null || building == null || building.id == null) {
            return false;
        }
        int identities = 0;
        boolean same = false;
        for (Building registered : settlement.buildings) {
            if (registered == null || registered.id == null) {
                return false;
            }
            if (registered.id.equals(building.id)) {
                identities++;
                same |= registered == building;
            }
        }
        return same && identities == 1;
    }

    private static RequestLedger requestLedger(ServerLevel level,
                                               Settlement settlement) {
        if (!live(level, settlement)) {
            return null;
        }
        return RequestLedgerSavedData.get(level).existing(settlement.id);
    }

    private static RecruitmentTransaction recruitmentTransaction(
            ServerLevel level, Settlement settlement, UUID transactionId) {
        if (!live(level, settlement) || transactionId == null
            || settlement.recruitment == null
            || !transactionId.equals(settlement.recruitment.transactionId())) {
            return null;
        }
        RecruitmentTransaction persisted = SettlementSavedData.get(level)
            .transaction(transactionId).orElse(null);
        return persisted == settlement.recruitment ? persisted : null;
    }

    private static Building recruitmentTavern(ServerLevel level,
                                              Settlement settlement,
                                              RecruitmentTransaction transaction) {
        if (transaction == null || !transaction.hasLockedTavern()
            || !level.dimension().location().equals(transaction.dimension())) {
            return null;
        }
        Building tavern = exactBuilding(settlement,
            transaction.tavernBuildingId());
        return tavern != null && tavern.valid
            && tavern.type == BuildingType.TAVERN
            && java.util.Objects.equals(tavern.plaquePos,
                transaction.tavernPlaquePos())
            && java.util.Objects.equals(tavern.anchor,
                transaction.tavernAnchor())
            ? tavern : null;
    }

    private static ActionView terminalWorkerAction(ServerLevel level,
                                                   Settlement settlement,
                                                   Building building,
                                                   SettlerEntity worker,
                                                   UUID actionId,
                                                   Kind expectedKind) {
        if (!live(level, settlement) || actionId == null || expectedKind == null
            || !exactRegistered(settlement, building) || !building.valid
            || worker == null || !worker.isAlive() || worker.level() != level
            || level.getEntity(worker.getId()) != worker
            || !settlement.id.equals(worker.getSettlementId())
            || settlement.record(worker.getUUID()) == null
            || !building.workers.contains(worker.getUUID())
            || Employment.employerOf(settlement, worker.getUUID()) != building
            || Employment.professionOf(settlement, worker.getUUID())
                != expectedKindProfession(expectedKind)
            || building.type != expectedKindBuilding(expectedKind)) {
            return null;
        }
        WorkZone zone = building.workZone().orElse(null);
        WorkerProvenanceSavedData data = WorkerProvenanceSavedData.get(level);
        ActionView action = data.action(actionId);
        if (data.quarantined() || zone == null || action == null
            || building.workZoneQuarantined()
            || action.kind() != expectedKind || !action.workTerminal()
            || !action.workerId().equals(worker.getUUID())
            || !action.zone().equals(zone)
            || !zone.settlementId().equals(settlement.id)
            || !zone.buildingId().equals(building.id)
            || !zone.dimension().equals(level.dimension().location())
            || zone.type() != expectedKindZone(expectedKind)
            || !zone.withinPersistentLimits()
            || action.planned().isEmpty()
            || action.resolved().size() != action.planned().size()
            || !action.resolved().containsAll(action.planned())
            || action.appliedToolDamage() != action.resolved().size()) {
            return null;
        }
        return action;
    }

    private static boolean receiptTotalsMatch(WorkerProvenanceSavedData data,
                                              ActionView action,
                                              ResourceLocation item) {
        long total = 0L;
        for (DepositReceipt receipt : data.receiptsFor(action.id())) {
            if (receipt.itemId().equals(item)) {
                total += receipt.count();
                if (total > Integer.MAX_VALUE) {
                    return false;
                }
            }
        }
        return total == action.deposited().getOrDefault(item, 0);
    }

    private static Profession expectedKindProfession(Kind kind) {
        return kind == Kind.LUMBER_TREE ? Profession.LUMBERER : Profession.FARMER;
    }

    private static BuildingType expectedKindBuilding(Kind kind) {
        return kind == Kind.LUMBER_TREE
            ? BuildingType.LUMBER_CAMP : BuildingType.FARMHOUSE;
    }

    private static WorkZone.Type expectedKindZone(Kind kind) {
        return kind == Kind.LUMBER_TREE
            ? WorkZone.Type.LUMBER : WorkZone.Type.FARM;
    }

    private static String workerActionFingerprint(ActionView action) {
        return "kind:" + action.kind().name().toLowerCase(java.util.Locale.ROOT)
            + ";resolved:" + action.resolved().size()
            + ";tool_damage:" + action.appliedToolDamage();
    }

    private static void emitRecruitment(JourneyApplyResult result,
                                        ServerLevel level,
                                        AuthorityTelemetry.Event event,
                                        Settlement settlement,
                                        RecruitmentTransaction transaction,
                                        UUID target, long itemBefore,
                                        long itemAfter, long expectedDelta,
                                        String reason) {
        if (result != JourneyApplyResult.APPLIED
            && result != JourneyApplyResult.RECORDED_NON_PROGRESS) {
            return;
        }
        String item = transaction.admissionReceipt() == null ? "none"
            : transaction.admissionReceipt().paymentFingerprint();
        AuthorityTelemetry.emit(level, event,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.items(settlement.id,
                "transaction:" + transaction.transactionId(),
                Math.max(0L, transaction.revision() - 1L),
                transaction.revision(), settlement.population(),
                settlement.population(), item, itemBefore, itemAfter,
                expectedDelta, reason + ":target:" + target));
    }

    private static boolean liveOutput(ServerLevel level, Settlement settlement,
                                      Building source, Building target,
                                      RequestRecord request,
                                      RequestLedger ledger) {
        return request != null && ledger != null && !ledger.quarantined()
            && request.type() == RequestType.OUTPUT_PICKUP
            && request.settlementId().equals(settlement.id)
            && request.dimensionId().equals(level.dimension().location())
            && exactRegistered(settlement, source) && source.valid
            && exactRegistered(settlement, target) && target.valid
            && request.sourceBuildingId().equals(source.id)
            && request.targetBuildingId().equals(target.id);
    }

    private static Building exactBuilding(Settlement settlement, UUID id) {
        if (settlement == null || id == null) {
            return null;
        }
        Building found = null;
        for (Building building : settlement.buildings) {
            if (building.id.equals(id)) {
                if (found != null) {
                    return null;
                }
                found = building;
            }
        }
        return found;
    }

    private static boolean isLog(RequestRecord request) {
        // RegistryAccess.EMPTY cannot decode component-bearing stacks. The
        // item id itself is canonical and the built-in item registry is the
        // tag holder; use a fresh default stack only for this family test.
        net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(
            request.fingerprint().itemId());
        return item != null && item.getDefaultInstance().is(ItemTags.LOGS);
    }

    private static boolean isCrop(RequestRecord request) {
        ResourceLocation id = request.fingerprint().itemId();
        return id.equals(BuiltInRegistries.ITEM.getKey(Items.WHEAT))
            || id.equals(BuiltInRegistries.ITEM.getKey(Items.CARROT))
            || id.equals(BuiltInRegistries.ITEM.getKey(Items.POTATO))
            || id.equals(BuiltInRegistries.ITEM.getKey(Items.BEETROOT))
            || id.equals(BuiltInRegistries.ITEM.getKey(Items.SUGAR_CANE));
    }

    private static String requestFingerprint(RequestRecord request) {
        return "item:" + request.fingerprint().itemId() + ";digest:"
            + request.fingerprint().digest().substring(0, 16) + ";count:"
            + request.fingerprint().count();
    }

    /**
     * The Call to Arms speed-up follows the unfinished Watch admission slot,
     * not a raw attempt number. Every rejection can therefore retry quickly,
     * while ordinary recruitment before and after this slot stays unchanged.
     */
    public static boolean useCallToArmsTiming(ServerLevel level,
                                              Settlement settlement) {
        return live(level, settlement) && settlement.journeyState != null
            && settlement.journeyState.mode() == JourneyPresentationMode.ACTIVE
            && settlement.journeyState.isCompleted(
                JourneyIds.FJ_552_ADD_FIFTH_BED)
            && !settlement.journeyState.isCompleted(
                JourneyIds.FJ_555_ADMIT_FIFTH_SETTLER);
    }

    /** Package-visible pure routing seam for restart/retry JVM contracts. */
    static ResourceLocation recruitmentStep(JourneyState state,
                                             RecruitmentTransaction transaction,
                                             JourneyEvent event) {
        if (state == null || transaction == null || event == null
            || state.mode() != JourneyPresentationMode.ACTIVE
            || !transaction.survivalAuthored()) {
            return null;
        }
        if (!state.isCompleted(JourneyIds.FJ_460_ADMIT_TRAVELER)
            && transactionStartedAfter(state,
                JourneyIds.FJ_430_LINK_TAVERN, transaction)) {
            return switch (event) {
                case RECRUITMENT_QUALIFICATION_STARTED_COMMITTED ->
                    JourneyIds.FJ_440_RECRUITMENT_WINDOW_STARTS;
                case TRAVELER_ARRIVED_AT_TAVERN_COMMITTED ->
                    JourneyIds.FJ_450_TRAVELER_ARRIVES;
                case TRAVELER_ADMITTED_COMMITTED ->
                    JourneyIds.FJ_460_ADMIT_TRAVELER;
                default -> null;
            };
        }
        if (transactionBoundToFirstRecruitmentSlot(state, transaction)) {
            return null;
        }
        boolean watchBound = transactionBoundToStep(state,
            JourneyIds.FJ_553_SECOND_RECRUITMENT_WINDOW, transaction);
        boolean watchStartedAfterGate = transactionStartedAfter(state,
            JourneyIds.FJ_552_ADD_FIFTH_BED, transaction);
        boolean adoptQualification = event
                == JourneyEvent.RECRUITMENT_QUALIFICATION_STARTED_COMMITTED
            && watchRecruitmentCanBeAdopted(state, transaction);
        if (!state.isCompleted(JourneyIds.FJ_555_ADMIT_FIFTH_SETTLER)
            && (watchBound || watchStartedAfterGate || adoptQualification)) {
            return switch (event) {
                case RECRUITMENT_QUALIFICATION_STARTED_COMMITTED ->
                    JourneyIds.FJ_553_SECOND_RECRUITMENT_WINDOW;
                case TRAVELER_ARRIVED_AT_TAVERN_COMMITTED ->
                    JourneyIds.FJ_554_SECOND_TRAVELER_ARRIVES;
                case TRAVELER_ADMITTED_COMMITTED ->
                    JourneyIds.FJ_555_ADMIT_FIFTH_SETTLER;
                default -> null;
            };
        }
        return null;
    }

    static Optional<UUID> ownedArmReconciliationTransaction(
            JourneyState journey, DevelopmentState development) {
        if (journey == null || development == null
            || journey.mode() != JourneyPresentationMode.ACTIVE
            || development.quarantined() || development.revision() <= 0
            || !development.unlocked(DevelopmentNode.ARM_THE_WATCH)
            || !journey.isCompleted(JourneyIds.FJ_550_SET_GUARD_ORDER)
            || journey.isCompleted(JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH)
            || journey.currentStep().isEmpty()
            || !journey.currentStep().orElseThrow().id().equals(
                JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH)) {
            return Optional.empty();
        }
        UUID subject = stableSubject("development_node",
            DevelopmentNode.ARM_THE_WATCH.id());
        return Optional.of(JourneyTransactionIds.forRevision(
            "development_owned_reconcile", journey.settlementId(), subject,
            development.revision()));
    }

    private static boolean reconcileWatchRecruitmentSlot(
            ServerLevel level, Settlement settlement) {
        if (!live(level, settlement) || settlement.recruitment == null
            || !watchRecruitmentCanBeAdopted(settlement.journeyState,
                settlement.recruitment)) {
            return false;
        }
        RecruitmentTransaction transaction = settlement.recruitment;
        RecruitmentTransaction accelerated = transaction
            .adoptCallToArmsWindow();
        if (accelerated != transaction) {
            settlement.applyRecruitment(accelerated);
            SettlementManager.data(level).setDirty();
            transaction = accelerated;
        }
        // These are observations of an existing transaction, not new commits.
        // Re-authoring its event with today's gameTime would collide with the
        // original immutable evidence after a later tick or save/load.
        boolean observed = false;
        if (!transactionBoundToStep(settlement.journeyState,
                JourneyIds.FJ_553_SECOND_RECRUITMENT_WINDOW, transaction)) {
            observed = noteRecruitmentQualificationCommitted(level,
                settlement, transaction.transactionId());
        }
        if (transaction.spawnedTick() >= 0L
            && transaction.arrivedTick() >= transaction.spawnedTick()
            && !transactionBoundToStep(settlement.journeyState,
                JourneyIds.FJ_554_SECOND_TRAVELER_ARRIVES, transaction)) {
            observed |= noteTravelerArrivedAtTavern(level, settlement,
                transaction.transactionId());
        }
        if (transaction.status() == RecruitmentTransaction.Status.ADMITTED
            && !transactionBoundToStep(settlement.journeyState,
                JourneyIds.FJ_555_ADMIT_FIFTH_SETTLER, transaction)) {
            observed |= noteTravelerAdmitted(level, settlement,
                transaction.transactionId());
        }
        return observed;
    }

    private static boolean watchRecruitmentCanBeAdopted(
            JourneyState state, RecruitmentTransaction transaction) {
        if (state == null || transaction == null
            || state.mode() != JourneyPresentationMode.ACTIVE
            || !state.isCompleted(JourneyIds.FJ_552_ADD_FIFTH_BED)
            || state.isCompleted(JourneyIds.FJ_555_ADMIT_FIFTH_SETTLER)
            || !transaction.survivalAuthored()
            || transaction.transactionId() == null
            || transaction.qualificationStartedTick() < 0L
            || transactionBoundToFirstRecruitmentSlot(state, transaction)) {
            return false;
        }
        return switch (transaction.status()) {
            case QUALIFYING, READY_TO_SPAWN, TRAVELING, WAITING_ADMISSION,
                 ADMITTED -> true;
            default -> false;
        };
    }

    private static boolean transactionBoundToFirstRecruitmentSlot(
            JourneyState state, RecruitmentTransaction transaction) {
        // A terminal first recruit may still be current until the next server
        // recruitment tick. Its globally keyed events cannot fill both slots.
        return transactionBoundToStep(state,
                JourneyIds.FJ_440_RECRUITMENT_WINDOW_STARTS, transaction)
            || transactionBoundToStep(state,
                JourneyIds.FJ_450_TRAVELER_ARRIVES, transaction)
            || transactionBoundToStep(state,
                JourneyIds.FJ_460_ADMIT_TRAVELER, transaction);
    }

    private static boolean transactionBoundToStep(
            JourneyState state, ResourceLocation stepId,
            RecruitmentTransaction transaction) {
        if (transaction.transactionId() == null) {
            return false;
        }
        JourneyStep step = JourneyDefinition.CURRENT.step(stepId).orElse(null);
        if (step == null) {
            return false;
        }
        for (JourneyEvidence evidence : state.evidence()) {
            if (evidence.stepId().equals(stepId)
                && evidence.transactionId().equals(transaction.transactionId())
                && evidence.progressEligible(step.migrationAllowed())) {
                return true;
            }
        }
        return false;
    }

    private static boolean transactionStartedAfter(
            JourneyState state, ResourceLocation gateStep,
            RecruitmentTransaction transaction) {
        if (!state.isCompleted(gateStep)
            || transaction.qualificationStartedTick() < 0L) {
            return false;
        }
        long gateTime = -1L;
        JourneyStep gate = JourneyDefinition.CURRENT.step(gateStep).orElse(null);
        if (gate == null) {
            return false;
        }
        for (JourneyEvidence evidence : state.evidence()) {
            if (evidence.stepId().equals(gateStep)
                && evidence.progressEligible(gate.migrationAllowed())) {
                gateTime = Math.max(gateTime, evidence.gameTime());
            }
        }
        return gateTime >= 0L
            && transaction.qualificationStartedTick() >= gateTime;
    }

    private static boolean staffingMatchesBuilding(Profession profession,
                                                    BuildingType type) {
        return profession == Profession.LUMBERER && type == BuildingType.LUMBER_CAMP
            || profession == Profession.COURIER && type == BuildingType.WAREHOUSE
            || profession == Profession.FARMER && type == BuildingType.FARMHOUSE
            || profession == Profession.FISHER && type == BuildingType.FISHERY
            || profession == Profession.GUARD && type == BuildingType.BARRACKS
            || profession == Profession.ARCHER && type == BuildingType.WATCHTOWER;
    }

    /** Counts only unique, loaded physical bed heads in valid linked homes. */
    private static int physicalHousingBeds(ServerLevel level,
                                           Settlement settlement) {
        if (!live(level, settlement)) {
            return -1;
        }
        int total = 0;
        Set<UUID> buildings = new HashSet<>();
        Set<BlockPos> globalBeds = new HashSet<>();
        for (Building building : settlement.buildings) {
            if (building == null || building.id == null
                || !buildings.add(building.id)
                || !exactRegistered(settlement, building)) {
                // A duplicated/corrupt building identity makes the aggregate
                // ambiguous. Never accept the first copy and silently ignore
                // the second when this count grants recruitment authority.
                return -1;
            }
            if (!building.valid
                || building.type == null || !building.type.housesResidents()
                || building.bounds == null || building.beds == null) {
                continue;
            }
            int inBuilding = 0;
            for (BlockPos pos : building.beds) {
                if (inBuilding >= com.hearthstead.settlement.techtree.effects.CommonsEffects
                        .houseCapacity(level, settlement, building.type)
                    || pos == null || !globalBeds.add(pos)
                    || !building.contains(pos) || !level.hasChunkAt(pos)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof BedBlock
                    && state.hasProperty(BedBlock.PART)
                    && state.getValue(BedBlock.PART) == BedPart.HEAD) {
                    inBuilding++;
                    total++;
                }
            }
        }
        return total;
    }

    /**
     * Authors fifth-bed capacity only after Arm the Watch is real Journey
     * authority. The observation is event-driven (unlock, home link or
     * Journey reopen), bounded by the registered building list and never
     * chunk-loads. This prevents an early house survey from silently crossing
     * the later watch gate while still healing a persisted unlock/receipt tear.
     */
    private static boolean reconcilePhysicalHousingCapacity(
            ServerLevel level, Settlement settlement) {
        if (!live(level, settlement)
            || !settlement.journeyState.isCompleted(
                JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH)
            || settlement.journeyState.isCompleted(
                JourneyIds.FJ_552_ADD_FIFTH_BED)) {
            return false;
        }
        int physicalBeds = physicalHousingBeds(level, settlement);
        if (physicalBeds < 5) {
            return false;
        }
        Building authority = null;
        for (Building candidate : settlement.buildings) {
            if (candidate == null || candidate.id == null || !candidate.valid
                || !exactRegistered(settlement, candidate)
                || candidate.type == null || !candidate.type.housesResidents()
                || candidate.bounds == null || candidate.beds == null) {
                continue;
            }
            boolean hasLoadedHead = false;
            for (BlockPos bed : candidate.beds) {
                if (bed == null || !candidate.contains(bed)
                    || !level.hasChunkAt(bed)) {
                    continue;
                }
                BlockState state = level.getBlockState(bed);
                if (state.getBlock() instanceof BedBlock
                    && state.hasProperty(BedBlock.PART)
                    && state.getValue(BedBlock.PART) == BedPart.HEAD) {
                    hasLoadedHead = true;
                    break;
                }
            }
            if (hasLoadedHead && (authority == null
                    || candidate.id.compareTo(authority.id) < 0)) {
                authority = candidate;
            }
        }
        if (authority == null) {
            return false;
        }
        UUID capacityTransaction = JourneyTransactionIds.forRevision(
            "physical_fifth_bed", settlement.id, authority.id, physicalBeds);
        return accepted(record(level, settlement,
            JourneyIds.FJ_552_ADD_FIFTH_BED,
            JourneyEvent.HOUSING_CAPACITY_COMMITTED,
            capacityTransaction, Optional.empty(), Optional.empty(),
            Optional.of(authority.id), Optional.empty(),
            Optional.of("physical_bed_heads:" + physicalBeds),
            JourneyOutcome.NONE));
    }

    /** Exact linked tower chest count, saturated and never chunk-loading. */
    private static int physicalTowerArrows(ServerLevel level, Building tower) {
        long arrows = 0L;
        for (BlockPos pos : WarehouseIndex.containers(level, tower)) {
            if (!(level.getBlockEntity(pos) instanceof Container container)) {
                continue;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.is(Items.ARROW)) {
                    arrows += stack.getCount();
                    if (arrows >= Integer.MAX_VALUE) {
                        return Integer.MAX_VALUE;
                    }
                }
            }
        }
        return (int) arrows;
    }

    private static boolean hasEvidence(Settlement settlement,
                                       ResourceLocation step,
                                       JourneyEvent event,
                                       UUID transaction) {
        if (settlement == null || settlement.journeyState == null) {
            return false;
        }
        for (JourneyEvidence evidence : settlement.journeyState.evidence()) {
            if (evidence.stepId().equals(step) && evidence.event() == event
                && evidence.transactionId().equals(transaction)
                && evidence.settlementId().equals(settlement.id)
                && evidence.source() == JourneySource.SURVIVAL) {
                return true;
            }
        }
        return false;
    }

    private static ResourceLocation techStep(DevelopmentNode node) {
        if (node == null) {
            return null;
        }
        return switch (node) {
            case TIMBER_RIGHTS -> JourneyIds.FJ_100_UNLOCK_LUMBER_CAMP;
            case STORES_AND_ROADS -> JourneyIds.FJ_200_UNLOCK_WAREHOUSE;
            case CULTIVATED_GROUND, SHORE_PROVISIONS -> JourneyIds.FJ_300_UNLOCK_FARMHOUSE;
            case HOME -> JourneyIds.FJ_400_UNLOCK_HOME;
            case HOSPITALITY -> JourneyIds.FJ_420_UNLOCK_TAVERN;
            case FIRST_WATCH -> JourneyIds.FJ_500_UNLOCK_FIRST_WATCH;
            case ARM_THE_WATCH -> JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH;
            default -> null;
        };
    }

    private static ResourceLocation buildingStep(BuildingType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case LUMBER_CAMP -> JourneyIds.FJ_110_LINK_LUMBER_CAMP;
            case WAREHOUSE -> JourneyIds.FJ_210_LINK_WAREHOUSE;
            case FARMHOUSE, FISHERY -> JourneyIds.FJ_310_LINK_FARMHOUSE;
            case HOUSE, LODGING -> JourneyIds.FJ_410_LINK_FIRST_HOME;
            case TAVERN -> JourneyIds.FJ_430_LINK_TAVERN;
            case BARRACKS -> JourneyIds.FJ_510_LINK_BARRACKS;
            case WATCHTOWER -> JourneyIds.FJ_556_LINK_WATCHTOWER;
            default -> null;
        };
    }

    private static ResourceLocation staffStep(Profession profession) {
        if (profession == null) {
            return null;
        }
        return switch (profession) {
            case LUMBERER -> JourneyIds.FJ_120_STAFF_LUMBER_CAMP;
            case COURIER -> JourneyIds.FJ_220_STAFF_WAREHOUSE;
            case FARMER, FISHER -> JourneyIds.FJ_320_STAFF_FARMHOUSE;
            case GUARD -> JourneyIds.FJ_520_STAFF_BARRACKS;
            case ARCHER -> JourneyIds.FJ_557_STAFF_WATCHTOWER;
            default -> null;
        };
    }

    private static ResourceLocation equipmentStep(Profession profession,
                                                  boolean satisfied) {
        if (profession == null) {
            return null;
        }
        return switch (profession) {
            case LUMBERER -> satisfied
                ? JourneyIds.FJ_160_GIVE_LUMBERER_AXE
                : JourneyIds.FJ_150_LUMBERER_REQUESTS_AXE;
            case FARMER, FISHER -> satisfied
                ? JourneyIds.FJ_350_EQUIP_FARMER
                : JourneyIds.FJ_340_FARMER_REQUESTS_HOE;
            case GUARD -> satisfied
                ? JourneyIds.FJ_540_EQUIP_GUARD
                : JourneyIds.FJ_530_GUARD_REQUESTS_WEAPON;
            case ARCHER -> satisfied
                ? JourneyIds.FJ_559_EQUIP_ARCHER
                : JourneyIds.FJ_558_ARCHER_REQUESTS_BOW;
            default -> null;
        };
    }

    private static String equipmentFingerprint(ItemStack stack) {
        return "item:" + BuiltInRegistries.ITEM.getKey(stack.getItem())
            + ";count:" + stack.getCount()
            + ";damage:" + stack.getDamageValue();
    }

    private static UUID stableSubject(String domain, String id) {
        return UUID.nameUUIDFromBytes(("hearthstead:" + domain + ":" + id)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static boolean accepted(JourneyApplyResult result) {
        return result == JourneyApplyResult.APPLIED
            || result == JourneyApplyResult.DUPLICATE
            || result == JourneyApplyResult.RECORDED_NON_PROGRESS
            || result == JourneyApplyResult.ALREADY_COMPLETED;
    }

    private static boolean acceptedEvidence(JourneyApplyResult result) {
        return accepted(result);
    }

    private JourneyServerHooks() {
    }
}
