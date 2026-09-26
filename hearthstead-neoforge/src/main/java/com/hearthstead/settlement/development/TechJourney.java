package com.hearthstead.settlement.development;

import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.journey.JourneyDefinition;
import com.hearthstead.settlement.journey.JourneyIds;
import com.hearthstead.settlement.journey.JourneyState;
import com.hearthstead.settlement.journey.JourneyStep;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Which tech node the Journey asks for next (survival QA U8: the Tech Tree
 * must point new players at the Journey's step instead of letting the only
 * early Coin go elsewhere). The inverse of the Journey's tech-unlock steps.
 */
public final class TechJourney {
    /** Journey unlock step -> the node that completes it. */
    public static final Map<ResourceLocation, String> STEP_NODES = Map.of(
        JourneyIds.FJ_100_UNLOCK_LUMBER_CAMP, "timber_rights",
        JourneyIds.FJ_200_UNLOCK_WAREHOUSE, "stores_and_roads",
        JourneyIds.FJ_300_UNLOCK_FARMHOUSE, "cultivated_ground",
        JourneyIds.FJ_400_UNLOCK_HOME, "home",
        JourneyIds.FJ_420_UNLOCK_TAVERN, "hospitality",
        JourneyIds.FJ_500_UNLOCK_FIRST_WATCH, "first_watch",
        JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH, "arm_the_watch");

    private TechJourney() {
    }

    /**
     * Pure selection: walking the Journey in order, the first step that is
     * not complete and asks for a node not yet learned. Empty when the
     * Journey needs no more tech (or is skipped).
     */
    public static Optional<String> recommend(List<ResourceLocation> orderedSteps,
                                             Predicate<ResourceLocation> completed,
                                             Predicate<String> learned) {
        for (ResourceLocation step : orderedSteps) {
            String node = STEP_NODES.get(step);
            if (node != null && !completed.test(step) && !learned.test(node)) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    /** The node this settlement's Journey asks for next, or null. */
    @Nullable
    public static String recommended(Settlement settlement, DevelopmentState state) {
        JourneyState journey = settlement == null ? null : settlement.journeyState;
        if (journey == null || journey.currentStep().isEmpty()) {
            return null;
        }
        List<ResourceLocation> steps = JourneyDefinition.CURRENT.orderedSteps().stream()
            .map(JourneyStep::id).toList();
        return recommend(steps, journey::completedThrough, id -> TechTree.learned(state, id)).orElse(null);
    }
}
