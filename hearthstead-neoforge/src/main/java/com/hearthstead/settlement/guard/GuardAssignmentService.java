package com.hearthstead.settlement.guard;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.DevelopmentState;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.equipment.EquipmentRequirement;
import com.hearthstead.settlement.state.GuardOrder;
import com.hearthstead.settlement.state.GuardOrderBook;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;

/** Exact server validation shared by Guard networking, AI and raid readiness. */
public final class GuardAssignmentService {

    public enum InvalidReason {
        NONE("none"),
        BOOK_QUARANTINED("guard_book_quarantined"),
        LEGACY_PENDING("legacy_guard_order_pending"),
        DEAD_OR_UNLOADED("guard_dead_or_unloaded"),
        SETTLEMENT_MISMATCH("guard_settlement_mismatch"),
        ROSTER_MISMATCH("guard_roster_mismatch"),
        WRONG_PROFESSION("guard_wrong_profession"),
        EMPLOYER_MISSING("guard_employer_missing"),
        EMPLOYER_AMBIGUOUS("guard_employer_ambiguous"),
        EMPLOYER_RELINKED("guard_employer_relinked"),
        ORDER_MISSING("guard_order_missing"),
        ORDER_IDENTITY("guard_order_identity"),
        TARGET_INVALID("guard_order_target_invalid"),
        TOWER_LOCKED("guard_tower_locked");

        private final String id;
        InvalidReason(String id) { this.id = id; }
        public String id() { return id; }
    }

    public record Validation(Optional<GuardOrder> order,
                             Optional<Building> employer,
                             InvalidReason reason) {
        public boolean valid() {
            return order.isPresent() && employer.isPresent()
                && reason == InvalidReason.NONE;
        }
    }

    /**
     * Frozen readiness API. It is bounded, read-only and never loads a chunk.
     * Only the exact armed defender's profession-specific persisted order can
     * pass: a Guard must Stand/Patrol, while an Archer must hold a Tower Post.
     */
    public static boolean hasValidReadyOrder(ServerLevel level,
                                             Settlement settlement,
                                             SettlerEntity armedGuard) {
        if (level == null || settlement == null || armedGuard == null) {
            return false;
        }
        Validation validation = validate(level, settlement, armedGuard, false);
        if (!validation.valid()) return false;
        GuardOrder.Mode mode = validation.order().orElseThrow().modeAt(
            level.getGameTime());
        Profession profession = armedGuard.getProfession();
        if (profession == Profession.GUARD
                ? mode != GuardOrder.Mode.STAND_POST
                    && mode != GuardOrder.Mode.PATROL_ROUTE
                : profession != Profession.ARCHER
                    || mode != GuardOrder.Mode.TOWER_POST) return false;
        return hasServiceableEquipment(armedGuard);
    }

    /**
     * The authored first-raid tutorial promises one local melee bodyguard and
     * one tower Archer.  A Patrol route is a valid ordinary Guard order, but
     * it cannot satisfy that narrower promise because raid escort is anchored
     * by a Stand Post and its exact issuing player.
     */
    public static boolean hasValidFirstRaidOrder(ServerLevel level,
                                                 Settlement settlement,
                                                 SettlerEntity defender) {
        if (level == null || settlement == null || defender == null) {
            return false;
        }
        Validation validation = validate(level, settlement, defender, false);
        if (!validation.valid() || !hasServiceableEquipment(defender)) {
            return false;
        }
        GuardOrder.Mode mode = validation.order().orElseThrow().modeAt(
            level.getGameTime());
        return switch (defender.getProfession()) {
            case GUARD -> mode == GuardOrder.Mode.STAND_POST;
            case ARCHER -> mode == GuardOrder.Mode.TOWER_POST;
            default -> false;
        };
    }

    /** Pure physical main-hand predicate; never refreshes or creates a request. */
    public static boolean hasServiceableEquipment(SettlerEntity guard) {
        if (guard == null || !guard.getProfession().martial()) return false;
        EquipmentRequirement requirement = EquipmentRequests.requirementFor(
            guard.getProfession());
        return requirement != null
            && requirement.serviceable(guard.getMainHandItem());
    }

    /** Read-only exact current order for AI and snapshots. */
    public static Validation validate(ServerLevel level, Settlement settlement,
                                      SettlerEntity guard,
                                      boolean allowEmptyOrder) {
        if (level == null || settlement == null || guard == null
            || !guard.isAlive() || guard.level() != level
            || level.getEntity(guard.getId()) != guard) {
            return invalid(InvalidReason.DEAD_OR_UNLOADED);
        }
        SettlementSavedData root = SettlementSavedData.existing(level);
        if (root == null || root.settlements.get(settlement.id) != settlement) {
            return invalid(InvalidReason.SETTLEMENT_MISMATCH);
        }
        if (settlement.guardOrders.quarantined()) {
            return invalid(InvalidReason.BOOK_QUARANTINED);
        }
        if (settlement.guardOrders.legacyStatus()
            == GuardOrderBook.LegacyStatus.PENDING) {
            return invalid(InvalidReason.LEGACY_PENDING);
        }
        if (!settlement.id.equals(guard.getSettlementId())
            || !Objects.equals(guard.getHearthPos(), settlement.center)
            || guard.isTraveler()) {
            return invalid(InvalidReason.SETTLEMENT_MISMATCH);
        }
        Settlement.SettlerRecord record = null;
        int matchingRecords = 0;
        for (Settlement.SettlerRecord candidate : settlement.settlers) {
            if (candidate == null || candidate.entityId == null
                || candidate.profession == null) {
                return invalid(InvalidReason.ROSTER_MISMATCH);
            }
            if (candidate.entityId.equals(guard.getUUID())) {
                matchingRecords++;
                record = candidate;
            }
        }
        if (matchingRecords != 1 || record == null) {
            return invalid(InvalidReason.ROSTER_MISMATCH);
        }
        Profession profession = guard.getProfession();
        if (!profession.martial() || record.profession != profession) {
            return invalid(InvalidReason.WRONG_PROFESSION);
        }
        List<Building> employers = employers(settlement, guard.getUUID());
        if (employers == null) return invalid(InvalidReason.ROSTER_MISMATCH);
        if (employers.isEmpty()) return invalid(InvalidReason.EMPLOYER_MISSING);
        if (employers.size() != 1) return invalid(InvalidReason.EMPLOYER_AMBIGUOUS);
        Building employer = employers.getFirst();
        if (!validEmployer(employer, profession)) {
            return invalid(InvalidReason.EMPLOYER_RELINKED);
        }
        Optional<GuardOrder> found = settlement.guardOrders.order(guard.getUUID());
        if (found.isEmpty()) {
            return allowEmptyOrder
                ? new Validation(Optional.empty(), Optional.of(employer),
                    InvalidReason.NONE)
                : invalid(InvalidReason.ORDER_MISSING);
        }
        GuardOrder order = found.get();
        if (!order.ownedBy(settlement.id, guard.getUUID(),
                level.dimension().location())) {
            return invalid(InvalidReason.ORDER_IDENTITY);
        }
        if (order.revision() > 0
            && !order.linkedBuildingId().filter(employer.id::equals).isPresent()) {
            return invalid(InvalidReason.EMPLOYER_RELINKED);
        }
        InvalidReason target = validateTarget(level, settlement, employer, order);
        return target == InvalidReason.NONE
            ? new Validation(Optional.of(order), Optional.of(employer), target)
            : invalid(target);
    }

    /**
     * Runtime-only legacy reconciliation. It inspects only persisted roster
     * rows, persisted building worker lists and already-loaded exact entities.
     */
    public static GuardOrderBook.ReconcileResult reconcileLegacy(
            ServerLevel level, Settlement settlement) {
        if (level == null || settlement == null) {
            return GuardOrderBook.ReconcileResult.QUARANTINED_INVALID;
        }
        SettlementSavedData root = SettlementSavedData.existing(level);
        if (root == null || root.settlements.get(settlement.id) != settlement) {
            return GuardOrderBook.ReconcileResult.QUARANTINED_INVALID;
        }
        if (settlement.guardOrders.legacyStatus()
            != GuardOrderBook.LegacyStatus.PENDING) {
            return GuardOrderBook.ReconcileResult.NOT_PENDING;
        }
        List<GuardOrderBook.Candidate> persisted = new ArrayList<>();
        List<UUID> live = new ArrayList<>();
        Set<UUID> seenRecords = new HashSet<>();
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (record == null || record.entityId == null
                || record.profession == null) {
                return settlement.guardOrders.quarantinePendingLegacy(
                    "legacy_null_settler_record");
            }
            if (!seenRecords.add(record.entityId)) {
                return settlement.guardOrders.quarantinePendingLegacy(
                    "legacy_duplicate_settler_record");
            }
            if (!record.profession.martial()) continue;
            List<Building> employers = employers(settlement, record.entityId);
            if (employers == null) {
                return settlement.guardOrders.quarantinePendingLegacy(
                    "legacy_malformed_employer_roster");
            }
            if (employers.size() != 1
                || !validEmployer(employers.getFirst(), record.profession)) {
                continue;
            }
            persisted.add(new GuardOrderBook.Candidate(record.entityId,
                employers.getFirst().id));
            if (level.getEntity(record.entityId) instanceof SettlerEntity guard
                && guard.isAlive() && guard.level() == level
                && !guard.isTraveler()
                && guard.getProfession() == record.profession
                && settlement.id.equals(guard.getSettlementId())
                && Objects.equals(guard.getHearthPos(), settlement.center)) {
                live.add(record.entityId);
            }
        }
        return settlement.guardOrders.reconcileLegacy(settlement.id,
            level.dimension().location(), persisted, live, level.getGameTime());
    }

    public static boolean towerPostAvailable(ServerLevel level,
                                             Settlement settlement,
                                             Building employer) {
        if (level == null || settlement == null || employer == null
            || employer.type != BuildingType.WATCHTOWER || !employer.valid
            || !settlement.buildings.contains(employer)) return false;
        DevelopmentState state = Development.existing(level, settlement.id);
        return state != null && !state.quarantined()
            && state.unlocked(DevelopmentNode.ARM_THE_WATCH);
    }

    private static InvalidReason validateTarget(ServerLevel level,
                                                Settlement settlement,
                                                Building employer,
                                                GuardOrder order) {
        if (order.mode() == GuardOrder.Mode.NONE) return InvalidReason.NONE;
        BlockPos destination = order.pos().orElse(null);
        if (destination == null || !level.isLoaded(destination)) {
            return InvalidReason.TARGET_INVALID;
        }
        // Only a Tower Post has an exact employer-bound destination.  Stand
        // and patrol remain player-authored city points and keep their radius
        // policy below.
        if (order.mode() == GuardOrder.Mode.TOWER_POST) {
            if (!towerPostAvailable(level, settlement, employer)) {
                return InvalidReason.TOWER_LOCKED;
            }
            if (!employer.contains(destination)) {
                return InvalidReason.TARGET_INVALID;
            }
        } else if (!settlement.inside(destination)) {
            return InvalidReason.TARGET_INVALID;
        }
        if (order.mode() == GuardOrder.Mode.PATROL_ROUTE) {
            List<BlockPos> points = order.patrolPoints();
            if (points.size() < GuardOrder.MIN_PATROL_POINTS
                || points.size() > GuardOrder.MAX_PATROL_POINTS) {
                return InvalidReason.TARGET_INVALID;
            }
            for (BlockPos point : points) {
                if (!settlement.inside(point) || !level.isLoaded(point)) {
                    return InvalidReason.TARGET_INVALID;
                }
            }
        }
        return InvalidReason.NONE;
    }

    @Nullable
    private static List<Building> employers(Settlement settlement,
                                            UUID guardId) {
        List<Building> matches = new ArrayList<>(2);
        for (Building building : settlement.buildings) {
            if (building == null || building.workers == null) return null;
            int occurrences = 0;
            for (UUID worker : building.workers) {
                if (worker == null) return null;
                if (worker.equals(guardId)) occurrences++;
            }
            if (occurrences > 1) return null;
            if (occurrences == 1) matches.add(building);
        }
        return matches;
    }

    private static boolean validEmployer(Building employer,
                                         Profession profession) {
        return employer != null && employer.id != null
            && employer.type != null && employer.valid
            && Employment.tradeOf(employer.type) == profession
            && (employer.type == BuildingType.BARRACKS
                || employer.type == BuildingType.WATCHTOWER);
    }

    private static Validation invalid(InvalidReason reason) {
        return new Validation(Optional.empty(), Optional.empty(), reason);
    }

    private GuardAssignmentService() {
    }
}
