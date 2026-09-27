package com.hearthstead.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owner, 27 Sep: an archer carries 6 arrows; rank and a bigger Watchtower raise it. */
final class ArcherQuiverCapacityTest {
    @Test
    void rankLadderIsSixEightTenTwelve() {
        assertEquals(6, ArcherRank.RECRUIT.quiverCapacity(1));
        assertEquals(8, ArcherRank.MARKSMAN.quiverCapacity(1));
        assertEquals(10, ArcherRank.SHARPSHOOTER.quiverCapacity(1));
        assertEquals(12, ArcherRank.MASTER.quiverCapacity(1));
    }

    @Test
    void levelTwoWatchtowerAddsTwo() {
        assertEquals(8, ArcherRank.RECRUIT.quiverCapacity(2));
        assertEquals(12, ArcherRank.SHARPSHOOTER.quiverCapacity(3));
        assertEquals(14, ArcherRank.MASTER.quiverCapacity(2));
    }

    @Test
    void neverAboveThePersistedCeiling() {
        for (ArcherRank rank : ArcherRank.values()) {
            for (int level = 0; level <= 5; level++) {
                int capacity = rank.quiverCapacity(level);
                assertTrue(capacity >= ArcherRank.QUIVER_BASE && capacity <= ArcherRank.QUIVER_CEILING,
                    rank + " at tower level " + level + " carries " + capacity);
            }
        }
        assertEquals(16, ArcherRank.QUIVER_CEILING, "matches SettlerEntity.ARCHER_QUIVER_CAPACITY");
    }
}
