package com.hearthstead.settlement.journey;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Bounded server projection for a chaptered Hearth Journey UI. */
public record JourneySnapshot(
    int schemaVersion,
    int definitionVersion,
    UUID settlementId,
    JourneyPresentationMode mode,
    Optional<ResourceLocation> chapterId,
    String chapterTitleKey,
    JourneyOutcome outcome,
    int revision,
    int completedCount,
    int totalCount,
    List<Objective> objectives
) {
    public static final int MAX_OBJECTIVES = 2;

    public JourneySnapshot {
        chapterId = chapterId == null ? Optional.empty() : chapterId;
        chapterTitleKey = chapterTitleKey == null ? "" : chapterTitleKey;
        objectives = List.copyOf(objectives);
        if (objectives.size() > MAX_OBJECTIVES) {
            throw new IllegalArgumentException("Journey UI projection is unbounded");
        }
    }

    public static JourneySnapshot from(JourneyState state) {
        ArrayList<Objective> objectives = new ArrayList<>(MAX_OBJECTIVES);
        if (state.mode() == JourneyPresentationMode.ACTIVE) {
            state.currentStep().ifPresent(current -> {
                objectives.add(Objective.from(current, ObjectiveState.CURRENT));
                JourneyDefinition.CURRENT.nextAfter(current.id()).ifPresent(next ->
                    objectives.add(Objective.from(next, ObjectiveState.NEXT)));
            });
        }
        Optional<ResourceLocation> chapter = state.currentChapter();
        return new JourneySnapshot(
            JourneyState.SCHEMA_VERSION,
            JourneyState.DEFINITION_VERSION,
            state.settlementId(),
            state.mode(),
            chapter,
            chapter.map(JourneySnapshot::chapterTitleKey).orElse(""),
            state.outcome(),
            state.revision(),
            state.completedCount(),
            JourneyDefinition.CURRENT.orderedSteps().size(),
            objectives);
    }

    private static String chapterTitleKey(ResourceLocation id) {
        String leaf = id.getPath().substring(id.getPath().lastIndexOf('/') + 1);
        return "journey.hearthstead.chapter." + leaf + ".title";
    }

    public enum ObjectiveState {
        CURRENT,
        NEXT
    }

    public record Objective(ResourceLocation stepId, ResourceLocation chapterId,
                            int ordinal, ObjectiveState state,
                            String titleKey, String descriptionKey) {
        private static Objective from(JourneyStep step, ObjectiveState state) {
            return new Objective(step.id(), step.chapterId(), step.ordinal(), state,
                step.titleKey(), step.descriptionKey());
        }
    }
}
