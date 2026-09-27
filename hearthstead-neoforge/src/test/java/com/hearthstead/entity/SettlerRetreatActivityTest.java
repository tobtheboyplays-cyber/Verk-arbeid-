package com.hearthstead.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlerRetreatActivityTest {
    @Test void retreatIsDistinctAndExistingWireIdsRemainStable() {
        String[] existing = {"IDLE","WORK_FARM","WORK_CHOP","EATING","RESTING","PATROLLING","COMBAT","FLEEING","TRAVELING","CELEBRATING","WORK_PLANT","WORK_HARVEST","WORK_WATER","WORK_LIMB","HAULING_LOG","SLEEPING","CARRYING","SORTING","WORK_KNEAD","WORK_CLEAVE","WORK_STOKE","WORK_HAMMER","WORK_SAW","WORK_WEAVE","GATHERING_LOG","WORK_OVEN","WORK_SOW","WORK_MINE","WORK_STIR","WORK_PLANE","WORK_CHISEL","WORK_FLETCH","WORK_SCRAPE","WORK_SHEAR","WORK_FISH","WORK_HUNT","OUT_OF_AMMO","COLLECTING_ITEMS","WORK_CRAFT","STORE_CRAFT_OUTPUT"};
        for (int id=0; id<existing.length; id++) {
            assertEquals(existing[id], SettlerActivity.byId(id).name());
        }
        assertEquals(existing.length, SettlerActivity.RETREATING.id());
        assertEquals(SettlerActivity.RETREATING,
            SettlerActivity.byId(SettlerActivity.RETREATING.id()));
        assertNotEquals(SettlerActivity.FLEEING.id(), SettlerActivity.RETREATING.id());
        assertEquals("retreating", SettlerActivity.RETREATING.key());
    }
}
