package com.hearthstead.client.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortraitLabelsTest {
    @Test void onlyTheExactPortraitSubjectIsHidden() {
        Object subject = new Object();
        Object worldEntity = new Object();
        assertFalse(PortraitLabels.hidden(subject));
        PortraitLabels.duringPortrait(subject, () -> {
            assertTrue(PortraitLabels.hidden(subject));
            assertFalse(PortraitLabels.hidden(worldEntity));
        });
        assertFalse(PortraitLabels.hidden(subject));
    }

    @Test void nestedDrawAndFailureRestoreThePreviousScope() {
        Object outer = new Object();
        Object inner = new Object();
        PortraitLabels.duringPortrait(outer, () -> {
            assertThrows(IllegalStateException.class, () -> PortraitLabels.duringPortrait(inner, () -> {
                assertTrue(PortraitLabels.hidden(inner));
                assertFalse(PortraitLabels.hidden(outer));
                throw new IllegalStateException("draw failed");
            }));
            assertTrue(PortraitLabels.hidden(outer));
            assertFalse(PortraitLabels.hidden(inner));
        });
        assertFalse(PortraitLabels.hidden(outer));
        assertFalse(PortraitLabels.hidden(inner));
    }
}
