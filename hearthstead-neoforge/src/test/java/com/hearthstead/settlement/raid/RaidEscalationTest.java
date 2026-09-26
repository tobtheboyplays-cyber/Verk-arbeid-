package com.hearthstead.settlement.raid;

import com.hearthstead.entity.RaiderEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owner request 26 Sep: bandits early, then the raids grow with the town. */
class RaidEscalationTest {

    private static RaidEscalation.Band band(int raid, int fighters, double score) {
        return RaidEscalation.compose(raid, new RaidEscalation.Strength(fighters, score), 0.0D);
    }

    @Test
    void firstRaidIsThreeOrFourBanditsOnly() {
        for (int fighters = 0; fighters <= 8; fighters++) {
            RaidEscalation.Band first = band(1, fighters, fighters * 1.2D);
            assertEquals(RaiderEntity.Variant.BANDIT, first.captain());
            assertEquals(0, first.skirmishers() + first.brutes());
            assertTrue(first.size() >= 3 && first.size() <= 4, "raid 1 size " + first.size());
            assertTrue(first.slots().stream().allMatch(v -> v == RaiderEntity.Variant.BANDIT));
        }
    }

    @Test
    void noBruteInRaidsOneToThreeWhateverTheStrength() {
        for (int raid = 1; raid <= 3; raid++) {
            for (int fighters = 0; fighters <= 10; fighters++) {
                RaidEscalation.Band b = band(raid, fighters, fighters * 1.5D + 2.0D);
                assertEquals(0, b.brutes(), "raid " + raid + " fighters " + fighters);
                assertTrue(b.captain() != RaiderEntity.Variant.BRUTE);
            }
        }
    }

    @Test
    void raidsTwoAndThreeAddOneOrTwoSkirmishersToBandits() {
        RaidEscalation.Band weak = band(2, 1, 1.0D);
        RaidEscalation.Band strong = band(3, 3, 3.5D);
        assertTrue(weak.bandits() >= 2 && weak.skirmishers() == 1);
        assertTrue(strong.bandits() >= 2 && strong.skirmishers() == 2);
    }

    @Test
    void aBruteJoinsRaidsFourAndFiveOnlyWithThreeFighters() {
        assertEquals(0, band(4, 2, 2.4D).brutes());
        assertEquals(1, band(4, 3, 3.0D).brutes());
        assertEquals(1, band(5, 4, 4.0D).brutes());
    }

    @Test
    void laterRaidsGrowWithTheTownAndShrinkWhenItLosesItsGuards() {
        RaidEscalation.Band small = band(8, 2, 2.0D);
        RaidEscalation.Band grown = band(8, 6, 7.0D);
        RaidEscalation.Band big = band(8, 9, 10.0D);
        RaidEscalation.Band defenceless = band(8, 0, 1.0D);
        assertTrue(small.brutes() >= 1 && grown.brutes() == 2 && big.brutes() == 3);
        assertTrue(small.size() <= grown.size() && grown.size() <= big.size());
        assertEquals(0, defenceless.brutes(), "a town without fighters is not hit by Brutes");
        assertTrue(defenceless.size() < grown.size());
        assertTrue(big.size() <= RaidDirector.MAX_BAND);
    }

    @Test
    void gearWeightsAreOrdered() {
        assertTrue(RaidEscalation.gearWeight(null) < 0.5D);
        assertTrue(RaidEscalation.MAX_DAY_STRENGTH <= 2.0D);
    }
}
