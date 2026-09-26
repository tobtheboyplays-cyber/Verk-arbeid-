package com.hearthstead.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Perspective size is proportional to world scale divided by distance. */
class SettlerLabelScaleTest {
    @Test
    void normalViewingDistancesKeepVanillaWorldSize() {
        for (double distance : new double[] {2, 3, 4, 5, 12, 24, 64}) {
            assertEquals(1.0F, SettlerRenderer.nearLabelScale(distance), 1e-6);
        }
    }

    @Test
    void closeLabelsNeverExceedTheirApparentHeightAtTwoBlocks() {
        double reference = SettlerRenderer.nearLabelScale(2.0) / 2.0;
        for (double distance = 0.001; distance < 2.0; distance += 0.001) {
            assertTrue(SettlerRenderer.nearLabelScale(distance) / distance <= reference + 1e-6,
                "Oversized label at " + distance);
        }
    }

    @Test
    void labelsRecedeBelowOneAndAHalfBlocksWithoutABoundaryJump() {
        assertEquals(0.5, SettlerRenderer.nearLabelScale(1.5) / 1.5, 1e-6);
        assertEquals(0.25, SettlerRenderer.nearLabelScale(0.75) / 0.75, 1e-6);
        for (double boundary : new double[] {1.5, 2.0}) {
            assertEquals(SettlerRenderer.nearLabelScale(boundary - 0.000001),
                SettlerRenderer.nearLabelScale(boundary + 0.000001), 0.00001);
        }
    }

    @Test
    void zeroAndInvalidDistancesDoNotProduceOversizedOrInvalidScales() {
        for (double distance : new double[] {0, -1, Double.NaN, Double.NEGATIVE_INFINITY}) {
            assertEquals(0.0F, SettlerRenderer.nearLabelScale(distance));
        }
        assertEquals(1.0F, SettlerRenderer.nearLabelScale(Double.POSITIVE_INFINITY));
    }
}
