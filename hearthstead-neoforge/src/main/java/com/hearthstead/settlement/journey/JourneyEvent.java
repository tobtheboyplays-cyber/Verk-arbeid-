package com.hearthstead.settlement.journey;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * Stable server-domain event catalogue.
 *
 * <p>The flags are minimum identity requirements for normal SURVIVAL
 * evidence. MIGRATION evidence is authored by a separate bounded reconciler
 * and may legitimately have no online player/session.
 */
public enum JourneyEvent {
    SETTLEMENT_FOUNDED_COMMITTED("settlement_founded_committed", false, false, false, false, false),
    JOURNEY_VIEW_OPENED("journey_view_opened", true, false, false, false, false),
    MAYOR_APPOINTED_COMMITTED("mayor_appointed_committed", true, true, false, false, false),
    TECH_UNLOCKED_COMMITTED("tech_unlocked_committed", true, false, false, false, false),
    BUILDING_LINKED_VALID_COMMITTED("building_linked_valid_committed", false, false, true, false, false),
    EMBLEM_TRADE_COMMITTED("emblem_trade_committed", true, false, false, false, false),
    JOB_EMBLEM_BOUND_COMMITTED("job_emblem_bound_committed", true, true, true, false, false),
    SETTLER_INVENTORY_VIEW_OPENED("settler_inventory_view_opened", true, true, false, false, false),
    WORK_ZONE_COMMITTED("work_zone_committed", false, false, true, false, false),
    EQUIPMENT_REQUEST_OPENED("equipment_request_opened", false, true, true, true, false),
    REQUEST_SATISFIED_COMMITTED("request_satisfied_committed", false, true, true, true, false),
    LUMBERER_TREE_COMPLETED("lumberer_tree_completed", false, true, true, false, false),
    WORKPLACE_OUTPUT_COMMITTED("workplace_output_committed", false, true, true, false, false),
    REQUEST_LEDGER_VIEW_OPENED("request_ledger_view_opened", true, false, false, false, false),
    OUTPUT_PICKUP_REQUEST_OPENED("output_pickup_request_opened", false, false, true, true, false),
    REQUEST_RESERVATION_COMMITTED("request_reservation_committed", false, true, true, true, false),
    MATERIAL_INPUT_COMMITTED("material_input_committed", false, false, true, false, false),
    HOUSING_CAPACITY_COMMITTED("housing_capacity_committed", false, false, true, false, false),
    RECRUITMENT_QUALIFICATION_STARTED_COMMITTED(
        "recruitment_qualification_started_committed", false, false, false, false, false),
    TRAVELER_ARRIVED_AT_TAVERN_COMMITTED(
        "traveler_arrived_at_tavern_committed", false, true, true, false, false),
    TRAVELER_ADMITTED_COMMITTED("traveler_admitted_committed", true, true, false, false, false),
    ARCHER_AMMUNITION_STORED_COMMITTED(
        "archer_ammunition_stored_committed", false, true, true, false, false),
    GUARD_ASSIGNMENT_COMMITTED("guard_assignment_committed", true, true, true, false, false),
    FIRST_RAID_READINESS_COMMITTED("first_raid_readiness_committed", false, false, false, false, false),
    FIRST_RAID_WARNING_COMMITTED("first_raid_warning_committed", false, false, false, false, false),
    FIRST_RAID_RESOLVED_COMMITTED("first_raid_resolved_committed", false, false, false, false, true),
    RAID_AFTERMATH_VIEW_OPENED("raid_aftermath_view_opened", true, false, false, false, false);

    private final ResourceLocation id;
    private final boolean actorRequired;
    private final boolean subjectRequired;
    private final boolean buildingRequired;
    private final boolean requestRequired;
    private final boolean terminalOutcomeRequired;

    JourneyEvent(String path, boolean actorRequired, boolean subjectRequired,
                 boolean buildingRequired, boolean requestRequired,
                 boolean terminalOutcomeRequired) {
        this.id = ResourceLocation.fromNamespaceAndPath(
            "hearthstead", "journey/event/" + path);
        this.actorRequired = actorRequired;
        this.subjectRequired = subjectRequired;
        this.buildingRequired = buildingRequired;
        this.requestRequired = requestRequired;
        this.terminalOutcomeRequired = terminalOutcomeRequired;
    }

    public ResourceLocation id() {
        return id;
    }

    public boolean actorRequired() {
        return actorRequired;
    }

    public boolean subjectRequired() {
        return subjectRequired;
    }

    public boolean buildingRequired() {
        return buildingRequired;
    }

    public boolean requestRequired() {
        return requestRequired;
    }

    public boolean terminalOutcomeRequired() {
        return terminalOutcomeRequired;
    }

    public static Optional<JourneyEvent> tryFromId(ResourceLocation id) {
        if (id == null) {
            return Optional.empty();
        }
        for (JourneyEvent event : values()) {
            if (event.id.equals(id)) {
                return Optional.of(event);
            }
        }
        return Optional.empty();
    }
}
