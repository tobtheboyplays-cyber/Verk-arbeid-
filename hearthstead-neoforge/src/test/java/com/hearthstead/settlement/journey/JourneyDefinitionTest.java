package com.hearthstead.settlement.journey;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JourneyDefinitionTest {

    @Test
    void definitionThreeAppendsWithoutMovingAnyFrozenV2Ordinal() {
        JourneyDefinition legacy = JourneyDefinition.LEGACY_V2;
        JourneyDefinition definition = JourneyDefinition.CURRENT;
        assertEquals(2, legacy.version());
        assertEquals(45, legacy.orderedSteps().size());
        assertEquals(JourneyIds.V2_STEPS,
            legacy.orderedSteps().stream().map(JourneyStep::id).toList());
        assertEquals(3, definition.version());
        assertEquals(56, definition.orderedSteps().size());
        assertEquals(JourneyIds.ALL_STEPS,
            definition.orderedSteps().stream().map(JourneyStep::id).toList());
        assertEquals(56, new HashSet<>(JourneyIds.ALL_STEPS).size());
        for (int ordinal = 0; ordinal < JourneyIds.V2_STEPS.size(); ordinal++) {
            assertEquals(JourneyIds.V2_STEPS.get(ordinal),
                definition.stepAt(ordinal).id());
        }
        assertEquals(List.of(
            JourneyIds.CHAPTER_FOUNDATION,
            JourneyIds.CHAPTER_FIRST_LABOR,
            JourneyIds.CHAPTER_LOGISTICS,
            JourneyIds.CHAPTER_FOOD_SECURITY,
            JourneyIds.CHAPTER_GROWTH,
            JourneyIds.CHAPTER_FIRST_WATCH,
            JourneyIds.CHAPTER_FIRST_RAID), definition.chapters());
    }

    @Test
    void v2StaysLinearWhileV3RoutesAppendedWatchBeforeReadiness() {
        List<JourneyStep> steps = JourneyDefinition.LEGACY_V2.orderedSteps();
        assertTrue(steps.getFirst().prerequisites().isEmpty());
        for (int i = 1; i < steps.size(); i++) {
            assertEquals(Set.of(steps.get(i - 1).id()),
                steps.get(i).prerequisites());
            assertEquals(i, steps.get(i).ordinal());
            assertFalse(steps.get(i).titleKey().isBlank());
            assertFalse(steps.get(i).descriptionKey().isBlank());
        }
        assertEquals(Set.of(JourneyIds.FJ_550_SET_GUARD_ORDER),
            JourneyDefinition.CURRENT.step(
                JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH).orElseThrow()
                .prerequisites());
        assertEquals(Set.of(JourneyIds.FJ_559B_SET_TOWER_POST),
            JourneyDefinition.CURRENT.step(
                JourneyIds.FJ_560_DECLARE_RAID_READY).orElseThrow()
                .prerequisites());
        assertEquals(JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH,
            JourneyDefinition.CURRENT.nextAfter(
                JourneyIds.FJ_550_SET_GUARD_ORDER).orElseThrow().id());
        assertEquals(JourneyIds.FJ_560_DECLARE_RAID_READY,
            JourneyDefinition.CURRENT.nextAfter(
                JourneyIds.FJ_559B_SET_TOWER_POST).orElseThrow().id());
    }

    @Test
    void uiObservationsAreNeverMigrationAuthority() {
        Set<ResourceLocation> viewSteps = Set.of(
            JourneyIds.FJ_020_OPEN_JOURNEY,
            JourneyIds.FJ_130_OPEN_LUMBERER_INVENTORY,
            JourneyIds.FJ_230_OPEN_REQUEST_LEDGER,
            JourneyIds.FJ_620_REVIEW_AFTERMATH);
        for (ResourceLocation id : viewSteps) {
            assertFalse(JourneyDefinition.CURRENT.step(id).orElseThrow().migrationAllowed());
        }
        for (ResourceLocation id : JourneyIds.V3_APPENDED_STEPS) {
            assertFalse(JourneyDefinition.CURRENT.step(id).orElseThrow()
                .migrationAllowed(), "new live proof may not be grandfathered");
        }
        assertTrue(JourneyEvent.JOURNEY_VIEW_OPENED.actorRequired());
        assertTrue(JourneyEvent.SETTLER_INVENTORY_VIEW_OPENED.actorRequired());
        assertTrue(JourneyEvent.SETTLER_INVENTORY_VIEW_OPENED.subjectRequired());
        assertTrue(JourneyEvent.RAID_AFTERMATH_VIEW_OPENED.actorRequired());
    }
}
