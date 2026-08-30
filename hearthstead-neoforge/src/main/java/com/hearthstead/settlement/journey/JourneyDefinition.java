package com.hearthstead.settlement.journey;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Versioned, append-only Journey definition.
 *
 * <p>Definition 2 is retained solely to validate and migrate an exact shipped
 * save. Definition 3 keeps every v2 ID at the same ordinal, appends eleven
 * First Watch objectives at ordinals 45..55, and uses explicit dependency
 * edges to place those objectives between FJ-550 and FJ-560. Numeric-looking
 * IDs are labels; save compatibility never depends on parsing them.
 */
public final class JourneyDefinition {
    public static final int DEFINITION_VERSION = 3;
    public static final JourneyDefinition LEGACY_V2 = buildLegacyV2();
    public static final JourneyDefinition V3 = buildV3();
    public static final JourneyDefinition CURRENT = V3;

    /**
     * Source compatibility for the existing call sites in this release.
     * New code should say {@link #CURRENT}; the actual legacy definition is
     * {@link #LEGACY_V2}.
     */
    @Deprecated(forRemoval = false)
    public static final JourneyDefinition V2 = CURRENT;

    private final int version;
    private final List<JourneyStep> orderedSteps;
    private final Map<ResourceLocation, JourneyStep> byId;
    private final Map<JourneyEvent, List<JourneyStep>> byEvent;
    private final Map<ResourceLocation, List<JourneyStep>> successors;
    private final List<ResourceLocation> chapters;

    private JourneyDefinition(int version, List<JourneyStep> orderedSteps,
                              List<ResourceLocation> expectedIds) {
        this.version = version;
        if (orderedSteps.size() != expectedIds.size()) {
            throw new IllegalArgumentException("Journey definition size mismatch");
        }
        LinkedHashMap<ResourceLocation, JourneyStep> indexed = new LinkedHashMap<>();
        HashMap<JourneyEvent, List<JourneyStep>> eventIndex = new HashMap<>();
        ArrayList<ResourceLocation> chapterOrder = new ArrayList<>();
        for (int i = 0; i < orderedSteps.size(); i++) {
            JourneyStep step = orderedSteps.get(i);
            if (step.ordinal() != i || !step.id().equals(expectedIds.get(i))
                || indexed.put(step.id(), step) != null) {
                throw new IllegalArgumentException(
                    "Unstable or duplicate Journey step at " + i);
            }
            if (i == 0 && !step.prerequisites().isEmpty()) {
                throw new IllegalArgumentException(
                    "First Journey step cannot have a prerequisite");
            }
            if (i > 0 && step.prerequisites().isEmpty()) {
                throw new IllegalArgumentException(
                    "Journey step is disconnected at " + i);
            }
            if (!chapterOrder.contains(step.chapterId())) {
                chapterOrder.add(step.chapterId());
            }
            for (JourneyEvent event : step.requiredEvents()) {
                eventIndex.computeIfAbsent(event, ignored -> new ArrayList<>())
                    .add(step);
            }
        }
        if (new HashSet<>(expectedIds).size() != expectedIds.size()) {
            throw new IllegalStateException("Duplicate frozen Journey ID");
        }

        HashMap<ResourceLocation, List<JourneyStep>> mutableSuccessors =
            new HashMap<>();
        for (JourneyStep step : orderedSteps) {
            for (ResourceLocation prerequisite : step.prerequisites()) {
                if (!indexed.containsKey(prerequisite)
                    || prerequisite.equals(step.id())) {
                    throw new IllegalArgumentException(
                        "Unknown or self-referential prerequisite for " + step.id());
                }
                mutableSuccessors.computeIfAbsent(prerequisite,
                    ignored -> new ArrayList<>()).add(step);
            }
        }
        Set<ResourceLocation> reachable = new HashSet<>();
        for (int pass = 0; pass < orderedSteps.size(); pass++) {
            boolean changed = false;
            for (JourneyStep step : orderedSteps) {
                if (!reachable.contains(step.id())
                    && reachable.containsAll(step.prerequisites())) {
                    reachable.add(step.id());
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        if (reachable.size() != orderedSteps.size()) {
            throw new IllegalArgumentException("Journey dependency graph is cyclic");
        }

        this.orderedSteps = List.copyOf(orderedSteps);
        this.byId = Collections.unmodifiableMap(indexed);
        HashMap<JourneyEvent, List<JourneyStep>> immutableEvents = new HashMap<>();
        eventIndex.forEach((event, steps) ->
            immutableEvents.put(event, List.copyOf(steps)));
        this.byEvent = Collections.unmodifiableMap(immutableEvents);
        HashMap<ResourceLocation, List<JourneyStep>> immutableSuccessors =
            new HashMap<>();
        mutableSuccessors.forEach((id, steps) ->
            immutableSuccessors.put(id, List.copyOf(steps)));
        this.successors = Collections.unmodifiableMap(immutableSuccessors);
        this.chapters = List.copyOf(chapterOrder);
    }

    public int version() {
        return version;
    }

    public List<JourneyStep> orderedSteps() {
        return orderedSteps;
    }

    public List<ResourceLocation> chapters() {
        return chapters;
    }

    public Optional<JourneyStep> step(ResourceLocation id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<JourneyStep> stepsFor(JourneyEvent event) {
        return byEvent.getOrDefault(event, List.of());
    }

    public JourneyStep stepAt(int ordinal) {
        return orderedSteps.get(ordinal);
    }

    /** The unique authored next objective in the current linear play path. */
    public Optional<JourneyStep> nextAfter(ResourceLocation stepId) {
        List<JourneyStep> next = successors.getOrDefault(stepId, List.of());
        return next.size() == 1 ? Optional.of(next.getFirst()) : Optional.empty();
    }

    private static JourneyDefinition buildLegacyV2() {
        return new JourneyDefinition(2, buildV2Steps(), JourneyIds.V2_STEPS);
    }

    private static JourneyDefinition buildV3() {
        ArrayList<JourneyStep> steps = buildV2Steps();
        int readinessOrdinal = JourneyIds.V2_STEPS.indexOf(
            JourneyIds.FJ_560_DECLARE_RAID_READY);
        JourneyStep oldReadiness = steps.get(readinessOrdinal);
        steps.set(readinessOrdinal, withPrerequisite(oldReadiness,
            JourneyIds.FJ_559B_SET_TOWER_POST));

        addAfter(steps, JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH,
            JourneyIds.CHAPTER_FIRST_WATCH, JourneyIds.FJ_550_SET_GUARD_ORDER,
            false, false, JourneyEvent.TECH_UNLOCKED_COMMITTED);
        addAfter(steps, JourneyIds.FJ_552_ADD_FIFTH_BED,
            JourneyIds.CHAPTER_FIRST_WATCH,
            JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH,
            false, false, JourneyEvent.HOUSING_CAPACITY_COMMITTED);
        addAfter(steps, JourneyIds.FJ_553_SECOND_RECRUITMENT_WINDOW,
            JourneyIds.CHAPTER_FIRST_WATCH, JourneyIds.FJ_552_ADD_FIFTH_BED,
            false, false,
            JourneyEvent.RECRUITMENT_QUALIFICATION_STARTED_COMMITTED);
        addAfter(steps, JourneyIds.FJ_554_SECOND_TRAVELER_ARRIVES,
            JourneyIds.CHAPTER_FIRST_WATCH,
            JourneyIds.FJ_553_SECOND_RECRUITMENT_WINDOW,
            false, false, JourneyEvent.TRAVELER_ARRIVED_AT_TAVERN_COMMITTED);
        addAfter(steps, JourneyIds.FJ_555_ADMIT_FIFTH_SETTLER,
            JourneyIds.CHAPTER_FIRST_WATCH,
            JourneyIds.FJ_554_SECOND_TRAVELER_ARRIVES,
            false, false, JourneyEvent.TRAVELER_ADMITTED_COMMITTED);
        addAfter(steps, JourneyIds.FJ_556_LINK_WATCHTOWER,
            JourneyIds.CHAPTER_FIRST_WATCH,
            JourneyIds.FJ_555_ADMIT_FIFTH_SETTLER,
            false, false, JourneyEvent.BUILDING_LINKED_VALID_COMMITTED);
        addAfter(steps, JourneyIds.FJ_557_STAFF_WATCHTOWER,
            JourneyIds.CHAPTER_FIRST_WATCH,
            JourneyIds.FJ_556_LINK_WATCHTOWER,
            true, false, JourneyEvent.EMBLEM_TRADE_COMMITTED,
            JourneyEvent.JOB_EMBLEM_BOUND_COMMITTED);
        addAfter(steps, JourneyIds.FJ_558_ARCHER_REQUESTS_BOW,
            JourneyIds.CHAPTER_FIRST_WATCH,
            JourneyIds.FJ_557_STAFF_WATCHTOWER,
            false, false, JourneyEvent.EQUIPMENT_REQUEST_OPENED);
        addAfter(steps, JourneyIds.FJ_559_EQUIP_ARCHER,
            JourneyIds.CHAPTER_FIRST_WATCH,
            JourneyIds.FJ_558_ARCHER_REQUESTS_BOW,
            false, false, JourneyEvent.REQUEST_SATISFIED_COMMITTED);
        addAfter(steps, JourneyIds.FJ_559A_SUPPLY_ARCHER_AMMUNITION,
            JourneyIds.CHAPTER_FIRST_WATCH,
            JourneyIds.FJ_559_EQUIP_ARCHER,
            false, false, JourneyEvent.ARCHER_AMMUNITION_STORED_COMMITTED);
        addAfter(steps, JourneyIds.FJ_559B_SET_TOWER_POST,
            JourneyIds.CHAPTER_FIRST_WATCH,
            JourneyIds.FJ_559A_SUPPLY_ARCHER_AMMUNITION,
            false, false, JourneyEvent.GUARD_ASSIGNMENT_COMMITTED);
        return new JourneyDefinition(3, steps, JourneyIds.ALL_STEPS);
    }

    private static ArrayList<JourneyStep> buildV2Steps() {
        ArrayList<JourneyStep> steps = new ArrayList<>(JourneyIds.V2_STEPS.size());
        add(steps, JourneyIds.FJ_010_FOUND_HEARTH, JourneyIds.CHAPTER_FOUNDATION,
            false, true, JourneyEvent.SETTLEMENT_FOUNDED_COMMITTED);
        add(steps, JourneyIds.FJ_020_OPEN_JOURNEY, JourneyIds.CHAPTER_FOUNDATION,
            false, false, JourneyEvent.JOURNEY_VIEW_OPENED);
        add(steps, JourneyIds.FJ_030_APPOINT_MAYOR, JourneyIds.CHAPTER_FOUNDATION,
            false, true, JourneyEvent.MAYOR_APPOINTED_COMMITTED);

        add(steps, JourneyIds.FJ_100_UNLOCK_LUMBER_CAMP, JourneyIds.CHAPTER_FIRST_LABOR,
            false, true, JourneyEvent.TECH_UNLOCKED_COMMITTED);
        add(steps, JourneyIds.FJ_110_LINK_LUMBER_CAMP, JourneyIds.CHAPTER_FIRST_LABOR,
            false, true, JourneyEvent.BUILDING_LINKED_VALID_COMMITTED);
        add(steps, JourneyIds.FJ_120_STAFF_LUMBER_CAMP, JourneyIds.CHAPTER_FIRST_LABOR,
            true, true, JourneyEvent.EMBLEM_TRADE_COMMITTED,
            JourneyEvent.JOB_EMBLEM_BOUND_COMMITTED);
        add(steps, JourneyIds.FJ_130_OPEN_LUMBERER_INVENTORY, JourneyIds.CHAPTER_FIRST_LABOR,
            false, false, JourneyEvent.SETTLER_INVENTORY_VIEW_OPENED);
        add(steps, JourneyIds.FJ_140_SET_LUMBER_ZONE, JourneyIds.CHAPTER_FIRST_LABOR,
            false, true, JourneyEvent.WORK_ZONE_COMMITTED);
        add(steps, JourneyIds.FJ_150_LUMBERER_REQUESTS_AXE, JourneyIds.CHAPTER_FIRST_LABOR,
            false, true, JourneyEvent.EQUIPMENT_REQUEST_OPENED);
        add(steps, JourneyIds.FJ_160_GIVE_LUMBERER_AXE, JourneyIds.CHAPTER_FIRST_LABOR,
            false, false, JourneyEvent.REQUEST_SATISFIED_COMMITTED);
        add(steps, JourneyIds.FJ_170_LUMBERER_FELLS_TREE, JourneyIds.CHAPTER_FIRST_LABOR,
            false, false, JourneyEvent.LUMBERER_TREE_COMPLETED);
        add(steps, JourneyIds.FJ_180_LUMBER_CAMP_STORES_LOG, JourneyIds.CHAPTER_FIRST_LABOR,
            false, false, JourneyEvent.WORKPLACE_OUTPUT_COMMITTED);

        add(steps, JourneyIds.FJ_200_UNLOCK_WAREHOUSE, JourneyIds.CHAPTER_LOGISTICS,
            false, true, JourneyEvent.TECH_UNLOCKED_COMMITTED);
        add(steps, JourneyIds.FJ_210_LINK_WAREHOUSE, JourneyIds.CHAPTER_LOGISTICS,
            false, true, JourneyEvent.BUILDING_LINKED_VALID_COMMITTED);
        add(steps, JourneyIds.FJ_220_STAFF_WAREHOUSE, JourneyIds.CHAPTER_LOGISTICS,
            true, true, JourneyEvent.EMBLEM_TRADE_COMMITTED,
            JourneyEvent.JOB_EMBLEM_BOUND_COMMITTED);
        add(steps, JourneyIds.FJ_230_OPEN_REQUEST_LEDGER, JourneyIds.CHAPTER_LOGISTICS,
            false, false, JourneyEvent.REQUEST_LEDGER_VIEW_OPENED);
        add(steps, JourneyIds.FJ_240_REQUEST_FIRST_PICKUP, JourneyIds.CHAPTER_LOGISTICS,
            false, false, JourneyEvent.OUTPUT_PICKUP_REQUEST_OPENED);
        add(steps, JourneyIds.FJ_250_COURIER_CLAIMS_PICKUP, JourneyIds.CHAPTER_LOGISTICS,
            false, false, JourneyEvent.REQUEST_RESERVATION_COMMITTED);
        add(steps, JourneyIds.FJ_260_WAREHOUSE_RECEIVES_LOG, JourneyIds.CHAPTER_LOGISTICS,
            false, false, JourneyEvent.REQUEST_SATISFIED_COMMITTED);

        add(steps, JourneyIds.FJ_300_UNLOCK_FARMHOUSE, JourneyIds.CHAPTER_FOOD_SECURITY,
            false, true, JourneyEvent.TECH_UNLOCKED_COMMITTED);
        add(steps, JourneyIds.FJ_310_LINK_FARMHOUSE, JourneyIds.CHAPTER_FOOD_SECURITY,
            false, true, JourneyEvent.BUILDING_LINKED_VALID_COMMITTED);
        add(steps, JourneyIds.FJ_320_STAFF_FARMHOUSE, JourneyIds.CHAPTER_FOOD_SECURITY,
            true, true, JourneyEvent.EMBLEM_TRADE_COMMITTED,
            JourneyEvent.JOB_EMBLEM_BOUND_COMMITTED);
        add(steps, JourneyIds.FJ_330_SET_FARM_ZONE, JourneyIds.CHAPTER_FOOD_SECURITY,
            false, true, JourneyEvent.WORK_ZONE_COMMITTED);
        add(steps, JourneyIds.FJ_340_FARMER_REQUESTS_HOE, JourneyIds.CHAPTER_FOOD_SECURITY,
            false, true, JourneyEvent.EQUIPMENT_REQUEST_OPENED);
        add(steps, JourneyIds.FJ_350_EQUIP_FARMER, JourneyIds.CHAPTER_FOOD_SECURITY,
            false, false, JourneyEvent.REQUEST_SATISFIED_COMMITTED);
        add(steps, JourneyIds.FJ_360_SUPPLY_FIRST_SEED, JourneyIds.CHAPTER_FOOD_SECURITY,
            false, false, JourneyEvent.MATERIAL_INPUT_COMMITTED);
        add(steps, JourneyIds.FJ_370_FARMHOUSE_STORES_CROP, JourneyIds.CHAPTER_FOOD_SECURITY,
            false, false, JourneyEvent.WORKPLACE_OUTPUT_COMMITTED);
        add(steps, JourneyIds.FJ_380_WAREHOUSE_RECEIVES_CROP, JourneyIds.CHAPTER_FOOD_SECURITY,
            false, false, JourneyEvent.REQUEST_SATISFIED_COMMITTED);

        add(steps, JourneyIds.FJ_400_UNLOCK_HOME, JourneyIds.CHAPTER_GROWTH,
            false, true, JourneyEvent.TECH_UNLOCKED_COMMITTED);
        add(steps, JourneyIds.FJ_410_LINK_FIRST_HOME, JourneyIds.CHAPTER_GROWTH,
            false, true, JourneyEvent.HOUSING_CAPACITY_COMMITTED);
        add(steps, JourneyIds.FJ_420_UNLOCK_TAVERN, JourneyIds.CHAPTER_GROWTH,
            false, true, JourneyEvent.TECH_UNLOCKED_COMMITTED);
        add(steps, JourneyIds.FJ_430_LINK_TAVERN, JourneyIds.CHAPTER_GROWTH,
            false, true, JourneyEvent.BUILDING_LINKED_VALID_COMMITTED);
        add(steps, JourneyIds.FJ_440_RECRUITMENT_WINDOW_STARTS, JourneyIds.CHAPTER_GROWTH,
            false, true, JourneyEvent.RECRUITMENT_QUALIFICATION_STARTED_COMMITTED);
        add(steps, JourneyIds.FJ_450_TRAVELER_ARRIVES, JourneyIds.CHAPTER_GROWTH,
            false, false, JourneyEvent.TRAVELER_ARRIVED_AT_TAVERN_COMMITTED);
        add(steps, JourneyIds.FJ_460_ADMIT_TRAVELER, JourneyIds.CHAPTER_GROWTH,
            false, false, JourneyEvent.TRAVELER_ADMITTED_COMMITTED);

        add(steps, JourneyIds.FJ_500_UNLOCK_FIRST_WATCH, JourneyIds.CHAPTER_FIRST_WATCH,
            false, true, JourneyEvent.TECH_UNLOCKED_COMMITTED);
        add(steps, JourneyIds.FJ_510_LINK_BARRACKS, JourneyIds.CHAPTER_FIRST_WATCH,
            false, true, JourneyEvent.BUILDING_LINKED_VALID_COMMITTED);
        add(steps, JourneyIds.FJ_520_STAFF_BARRACKS, JourneyIds.CHAPTER_FIRST_WATCH,
            true, true, JourneyEvent.EMBLEM_TRADE_COMMITTED,
            JourneyEvent.JOB_EMBLEM_BOUND_COMMITTED);
        add(steps, JourneyIds.FJ_530_GUARD_REQUESTS_WEAPON, JourneyIds.CHAPTER_FIRST_WATCH,
            false, true, JourneyEvent.EQUIPMENT_REQUEST_OPENED);
        add(steps, JourneyIds.FJ_540_EQUIP_GUARD, JourneyIds.CHAPTER_FIRST_WATCH,
            false, false, JourneyEvent.REQUEST_SATISFIED_COMMITTED);
        add(steps, JourneyIds.FJ_550_SET_GUARD_ORDER, JourneyIds.CHAPTER_FIRST_WATCH,
            false, true, JourneyEvent.GUARD_ASSIGNMENT_COMMITTED);
        add(steps, JourneyIds.FJ_560_DECLARE_RAID_READY, JourneyIds.CHAPTER_FIRST_WATCH,
            false, false, JourneyEvent.FIRST_RAID_READINESS_COMMITTED);

        add(steps, JourneyIds.FJ_600_RECEIVE_FIRST_WARNING, JourneyIds.CHAPTER_FIRST_RAID,
            false, true, JourneyEvent.FIRST_RAID_WARNING_COMMITTED);
        add(steps, JourneyIds.FJ_610_FIRST_RAID_RESOLVED, JourneyIds.CHAPTER_FIRST_RAID,
            false, true, JourneyEvent.FIRST_RAID_RESOLVED_COMMITTED);
        add(steps, JourneyIds.FJ_620_REVIEW_AFTERMATH, JourneyIds.CHAPTER_FIRST_RAID,
            false, false, JourneyEvent.RAID_AFTERMATH_VIEW_OPENED);
        return steps;
    }

    private static JourneyStep withPrerequisite(JourneyStep step,
                                                ResourceLocation prerequisite) {
        return new JourneyStep(step.id(), step.chapterId(), step.ordinal(),
            Set.of(prerequisite), step.requiredEvents(),
            step.sameTransactionRequired(), step.migrationAllowed(),
            step.titleKey(), step.descriptionKey());
    }

    private static void add(List<JourneyStep> steps, ResourceLocation id,
                            ResourceLocation chapterId,
                            boolean sameTransactionRequired,
                            boolean migrationAllowed,
                            JourneyEvent... requiredEvents) {
        ResourceLocation prerequisite = steps.isEmpty()
            ? null : steps.getLast().id();
        addAfter(steps, id, chapterId, prerequisite, sameTransactionRequired,
            migrationAllowed, requiredEvents);
    }

    private static void addAfter(List<JourneyStep> steps, ResourceLocation id,
                                 ResourceLocation chapterId,
                                 ResourceLocation prerequisite,
                                 boolean sameTransactionRequired,
                                 boolean migrationAllowed,
                                 JourneyEvent... requiredEvents) {
        int ordinal = steps.size();
        Set<ResourceLocation> prerequisites = prerequisite == null
            ? Set.of() : Set.of(prerequisite);
        String leaf = id.getPath().substring(id.getPath().lastIndexOf('/') + 1);
        steps.add(new JourneyStep(id, chapterId, ordinal, prerequisites,
            List.of(requiredEvents), sameTransactionRequired, migrationAllowed,
            "journey.hearthstead.step." + leaf + ".title",
            "journey.hearthstead.step." + leaf + ".description"));
    }
}
