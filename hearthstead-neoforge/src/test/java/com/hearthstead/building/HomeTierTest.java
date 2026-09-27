package com.hearthstead.building;

import com.hearthstead.settlement.techtree.TechTreeData;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Home tiers (Hut, Cottage, Townhouse, Manor) from House level + learned Commons nodes. */
class HomeTierTest {

    private static Predicate<String> knows(String... ids) {
        Set<String> set = Set.of(ids);
        return set::contains;
    }

    @Test
    void levelAndNodeTogetherNameTheTier() {
        Predicate<String> all = knows("home", "sturdy_beds", "two_storey_houses", "manors");
        assertNull(HomeTier.of(0, all), "an unregistered room has no tier");
        assertEquals(HomeTier.HUT, HomeTier.of(1, all));
        assertEquals(HomeTier.COTTAGE, HomeTier.of(2, all));
        assertEquals(HomeTier.TOWNHOUSE, HomeTier.of(3, all));
        assertEquals(HomeTier.MANOR, HomeTier.of(4, all));
    }

    @Test
    void aMissingNodeStopsTheClimbHoweverFineTheRoom() {
        assertEquals(HomeTier.HUT, HomeTier.of(4, knows("home")));
        assertEquals(HomeTier.COTTAGE, HomeTier.of(4, knows("home", "sturdy_beds")));
        assertEquals(HomeTier.TOWNHOUSE, HomeTier.of(4, knows("home", "sturdy_beds", "two_storey_houses")));
        assertNull(HomeTier.of(3, knows()), "no Houses node: fail closed");
    }

    @Test
    void theGoalSaysWhetherToBuildLearnOrBoth() {
        assertEquals(HomeTier.Goal.BUILD_AND_LEARN, HomeTier.goal(HomeTier.COTTAGE, 1, knows("home")));
        assertEquals(HomeTier.Goal.BUILD, HomeTier.goal(HomeTier.COTTAGE, 1, knows("home", "sturdy_beds")));
        assertEquals(HomeTier.Goal.LEARN, HomeTier.goal(HomeTier.COTTAGE, 3, knows("home")));
        assertNull(HomeTier.MANOR.next(), "the Manor is the top tier");
        assertEquals(HomeTier.MANOR, HomeTier.TOWNHOUSE.next());
    }

    @Test
    void everyTierNodeIsARealCommonsNodeInTreeOrder() {
        int previousTier = 0;
        for (HomeTier tier : HomeTier.values()) {
            var def = TechTreeData.get().node(tier.node());
            assertNotNull(def, tier + " names unknown node " + tier.node());
            assertEquals("commons", def.branch(), tier.node());
            assertTrue(def.tier() >= previousTier, tier + " is learned no earlier than the tier before it");
            previousTier = def.tier();
            assertEquals(tier.ordinal() + 1, tier.level(), tier + " level");
        }
    }

    @Test
    void theHouseChecklistReachesTheManorLevel() {
        assertEquals(HomeTier.MANOR.level(), BuildingLevels.maxLevel(BuildingType.HOUSE),
            "every tier's level exists in the House checklist");
    }
}
