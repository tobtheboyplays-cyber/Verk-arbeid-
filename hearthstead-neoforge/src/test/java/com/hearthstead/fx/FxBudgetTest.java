package com.hearthstead.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FxBudgetTest {

    @Test
    void reserveStopsAtTheHardCap() {
        FxBudget budget = new FxBudget();
        int cap = FxBudget.capFor(1);
        for (int i = 0; i < cap; i++) {
            assertTrue(budget.tryReserve(cap), "reservation " + i + " is under the cap");
        }
        assertFalse(budget.tryReserve(cap), "the cap is hard");
        assertEquals(1, budget.refused());
        assertEquals(cap, budget.estimate());
    }

    @Test
    void liveCountIsRederivedEveryTickSoItNeverLeaks() {
        FxBudget budget = new FxBudget();
        int cap = 10;
        for (int i = 0; i < cap; i++) {
            budget.tryReserve(cap);
        }
        budget.endTick();
        assertEquals(10, budget.estimate(), "fresh particles count before their first tick");
        // next tick: only 3 of them survived (the engine dropped the rest silently)
        for (int i = 0; i < 3; i++) {
            budget.markAlive();
        }
        budget.endTick();
        assertEquals(3, budget.estimate());
        assertTrue(budget.tryReserve(cap), "dropped particles free their budget");
        budget.endTick();
        budget.endTick();
        assertEquals(0, budget.estimate(), "nothing alive, nothing counted");
    }

    @Test
    void resetClearsEverything() {
        FxBudget budget = new FxBudget();
        budget.tryReserve(5);
        budget.markAlive();
        budget.endTick();
        budget.reset();
        assertEquals(0, budget.estimate());
    }

    @Test
    void capsGrowWithIntensityAndClamp() {
        assertTrue(FxBudget.capFor(0) < FxBudget.capFor(1));
        assertTrue(FxBudget.capFor(1) < FxBudget.capFor(2));
        assertEquals(FxBudget.capFor(0), FxBudget.capFor(-5));
        assertEquals(FxBudget.capFor(2), FxBudget.capFor(9));
    }

    @Test
    void vanillaParticleOptionScales() {
        float all = FxBudget.scale(1, FxBudget.STATUS_ALL, false);
        float decreased = FxBudget.scale(1, FxBudget.STATUS_DECREASED, false);
        assertEquals(1.0F, all);
        assertEquals(0.5F, decreased);
        assertEquals(0.0F, FxBudget.scale(1, FxBudget.STATUS_MINIMAL, false), "Minimal drops ambience");
        assertTrue(FxBudget.scale(1, FxBudget.STATUS_MINIMAL, true) > 0.0F, "Minimal keeps a trace of feedback");
        assertTrue(FxBudget.scale(0, FxBudget.STATUS_ALL, false) < all);
        assertTrue(FxBudget.scale(2, FxBudget.STATUS_ALL, false) > all);
    }

    @Test
    void countRoundsStochasticallyAndNeverGoesNegative() {
        assertEquals(0, FxBudget.count(10, 0.0F, 0.0D));
        assertEquals(0, FxBudget.count(0, 1.0F, 0.0D));
        assertEquals(0, FxBudget.count(-3, 1.0F, 0.0D));
        assertEquals(10, FxBudget.count(10, 1.0F, 0.99D));
        // 3 * 0.5 = 1.5: roll below .5 rounds up, above rounds down
        assertEquals(2, FxBudget.count(3, 0.5F, 0.2D));
        assertEquals(1, FxBudget.count(3, 0.5F, 0.8D));
    }

    @Test
    void distanceCullingIsASphere() {
        assertTrue(FxBudget.inRange(0, 0, 0, FxBudget.DEFAULT_CULL_RANGE));
        assertTrue(FxBudget.inRange(48, 0, 0, 48));
        assertFalse(FxBudget.inRange(48, 1, 0, 48));
        assertFalse(FxBudget.inRange(30, 30, 30, 48), "diagonal distance counts, not per axis");
        assertTrue(FxBudget.inRange(-20, 10, -20, 48));
    }
}
