package com.hearthstead.settlement.development;

import com.hearthstead.settlement.journey.JourneyDefinition;
import com.hearthstead.settlement.journey.JourneyIds;
import com.hearthstead.settlement.journey.JourneyStep;
import com.hearthstead.settlement.techtree.TechTreeData;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Survival QA U8: the Tech Tree preselects the node the Journey asks for
 * next, so a new player's first Coin goes to Timber Rights (Lumber Camp).
 */
class TechJourneyTest {
    private static final List<ResourceLocation> STEPS = JourneyDefinition.CURRENT.orderedSteps().stream()
        .map(JourneyStep::id).toList();

    @Test
    void everyMappedStepAndNodeExists() {
        for (var e : TechJourney.STEP_NODES.entrySet()) {
            assertTrue(STEPS.contains(e.getKey()), e.getKey() + " is not a Journey step");
            assertNotNull(TechTreeData.get().node(e.getValue()), e.getValue() + " is not a tech node");
        }
    }

    @Test
    void freshWorldRecommendsTimberRightsNotHouses() {
        Optional<String> first = TechJourney.recommend(STEPS, step -> false, node -> false);
        assertEquals(Optional.of("timber_rights"), first, "the first Coin belongs to the Lumber Camp");
    }

    @Test
    void recommendationFollowsJourneyProgress() {
        Set<ResourceLocation> done = new HashSet<>();
        Set<String> learned = new HashSet<>();
        done.add(JourneyIds.FJ_100_UNLOCK_LUMBER_CAMP);
        learned.add("timber_rights");
        assertEquals(Optional.of("stores_and_roads"), TechJourney.recommend(STEPS, done::contains, learned::contains));
        // A node learned out of order is skipped even if its step is not marked done yet.
        learned.add("stores_and_roads");
        assertEquals(Optional.of("cultivated_ground"), TechJourney.recommend(STEPS, done::contains, learned::contains));
        learned.addAll(TechJourney.STEP_NODES.values());
        assertEquals(Optional.empty(), TechJourney.recommend(STEPS, done::contains, learned::contains),
            "nothing left to recommend once every Journey node is learned");
    }
}
