package com.hearthstead.settlement.journey;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One immutable milestone in the frozen Journey definition. */
public record JourneyStep(
    ResourceLocation id,
    ResourceLocation chapterId,
    int ordinal,
    Set<ResourceLocation> prerequisites,
    List<JourneyEvent> requiredEvents,
    boolean sameTransactionRequired,
    boolean migrationAllowed,
    String titleKey,
    String descriptionKey
) {
    public JourneyStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(chapterId, "chapterId");
        Objects.requireNonNull(prerequisites, "prerequisites");
        Objects.requireNonNull(requiredEvents, "requiredEvents");
        Objects.requireNonNull(titleKey, "titleKey");
        Objects.requireNonNull(descriptionKey, "descriptionKey");
        if (ordinal < 0 || requiredEvents.isEmpty()
            || requiredEvents.size() > 3
            || prerequisites.size() > 3
            || titleKey.isBlank() || descriptionKey.isBlank()) {
            throw new IllegalArgumentException("Malformed Journey step definition");
        }
        prerequisites = Set.copyOf(prerequisites);
        requiredEvents = List.copyOf(requiredEvents);
        if (sameTransactionRequired && requiredEvents.size() < 2) {
            throw new IllegalArgumentException(
                "Transaction correlation needs at least two events");
        }
    }

    public boolean accepts(JourneyEvent event) {
        return event != null && requiredEvents.contains(event);
    }
}
